package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

/**
 * Tests SC-11's metadata publication by {@link WsdlQueryPublisher}: {@code GET <path>?wsdl} serves the registered WSDL
 * with its SOAP address set to the request URL, and {@code GET <path>?xsd=<location>} serves every schema the WSDL
 * reaches, each reference rewritten to such a URL, as CXF published them.
 *
 * <p>Every SC-11 consumer carries this class with only its {@code package} line changed. The publisher itself is
 * exercised on documents written to a temporary classpath, and {@link ConsumerContext} discovers the consumer's own
 * registrations from its application context, so no WSDL, path or service of any project is named here.
 */
class WsdlQueryPublisherTest {

    /** Scheme, host and port of every request built by {@link #request}. */
    private static final String ORIGIN = "http://localhost:8080";
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP11_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
    /** Attributes that carry a SOAP address or a reference location: the only ones the publisher rewrites. */
    private static final Set<String> LOCATION_ATTRIBUTES = Set.of("location", "schemaLocation");

    private static final String ECHO_PATH = "/svc/Echo";
    private static final String ECHO_WSDL = "svc/Echo.wsdl";
    /** Root-relative, as written in the WSDL: resolving it next to {@code svc/Echo.wsdl} finds nothing. */
    private static final String MESSAGE_KEY = "svc/xsd/EchoMessage.xsd";
    /** Sibling-relative, as written in {@code svc/xsd/EchoMessage.xsd}. */
    private static final String MODEL_KEY = "EchoModel.xsd";
    /** A location whose space and non-ASCII letter must be percent-encoded in the rewritten query. */
    private static final String SPACED_KEY = "xsd/Echo Types \u00fc.xsd";
    private static final String SPACED_KEY_ENCODED = "xsd/Echo%20Types%20%C3%BC.xsd";

    private static final String ECHO_WSDL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                    xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                    xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                    xmlns:msg="urn:example:echo:message"
                    xmlns:tns="urn:example:echo"
                    name="Echo" targetNamespace="urn:example:echo">
                <wsdl:types>
                    <xsd:schema targetNamespace="urn:example:echo">
                        <xsd:import namespace="urn:example:echo:message" schemaLocation="svc/xsd/EchoMessage.xsd"/>
                    </xsd:schema>
                </wsdl:types>
                <wsdl:message name="echo">
                    <wsdl:part name="parameters" element="msg:echo"/>
                </wsdl:message>
                <wsdl:message name="echoResponse">
                    <wsdl:part name="parameters" element="msg:echoResponse"/>
                </wsdl:message>
                <wsdl:portType name="Echo">
                    <wsdl:operation name="echo">
                        <wsdl:input message="tns:echo"/>
                        <wsdl:output message="tns:echoResponse"/>
                    </wsdl:operation>
                </wsdl:portType>
                <wsdl:binding name="EchoSoapBinding" type="tns:Echo">
                    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="echo">
                        <soap:operation soapAction=""/>
                        <wsdl:input>
                            <soap:body use="literal"/>
                        </wsdl:input>
                        <wsdl:output>
                            <soap:body use="literal"/>
                        </wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="EchoService">
                    <wsdl:port name="EchoPort" binding="tns:EchoSoapBinding">
                        <soap:address location="http://placeholder.invalid/echo"/>
                    </wsdl:port>
                </wsdl:service>
            </wsdl:definitions>
            """;

    private static final String ECHO_MESSAGE_XSD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema xmlns="http://www.w3.org/2001/XMLSchema" xmlns:mod="urn:example:echo:model"
                    targetNamespace="urn:example:echo:message" elementFormDefault="unqualified">
                <import namespace="urn:example:echo:model" schemaLocation="EchoModel.xsd"/>
                <element name="echo">
                    <complexType>
                        <sequence>
                            <element ref="mod:text"/>
                        </sequence>
                    </complexType>
                </element>
                <element name="echoResponse">
                    <complexType>
                        <sequence>
                            <element ref="mod:text"/>
                        </sequence>
                    </complexType>
                </element>
            </schema>
            """;

    private static final String ECHO_MODEL_XSD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:echo:model">
                <xsd:element name="text" type="xsd:string"/>
            </xsd:schema>
            """;

    /** A WSDL whose types section imports each given location; nothing else of it matters to the publisher. */
    private static final String IMPORTING_WSDL_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                    xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:echo">
                <wsdl:types>
                    <xsd:schema targetNamespace="urn:example:echo">
            %s        </xsd:schema>
                </wsdl:types>
            </wsdl:definitions>
            """;

    /** A schema importing each given location. */
    private static final String IMPORTING_XSD_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:echo:part">
            %s</xsd:schema>
            """;

    // ---------------------------------------------------------------------------------------------------------------
    // The publisher on a temporary classpath
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void registrationHoldsTheWsdlPatternsAndTransitiveSchemaClosure(@TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            Resource wsdl = classpath.resource(ECHO_WSDL);
            WsdlQueryPublisher publisher = new WsdlQueryPublisher().register(wsdl, ECHO_PATH, ECHO_PATH + "/**");

            assertThat(publisher.registrations()).hasSize(1);
            WsdlQueryPublisher.Registration registration = publisher.registrations().get(0);
            assertThat(registration.wsdl()).isSameAs(wsdl);
            assertThat(registration.patterns()).containsExactly(ECHO_PATH, ECHO_PATH + "/**");
            // Breadth-first discovery order; the root-relative key fell back to the classpath root.
            assertThat(registration.schemas().keySet()).containsExactly(MESSAGE_KEY, MODEL_KEY);
            assertThat(registration.schemas().values()).allMatch(Resource::exists);
            assertThat(file(registration.schemas().get(MESSAGE_KEY)))
                    .isEqualTo(root.resolve("svc/xsd/EchoMessage.xsd").toRealPath());
            assertThat(file(registration.schemas().get(MODEL_KEY)))
                    .isEqualTo(root.resolve("svc/xsd/EchoModel.xsd").toRealPath());
            assertThatThrownBy(() -> publisher.registrations().add(registration))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /** Every request path a registered pattern matches, and the {@code wsdl} query in any case. */
    static Stream<Arguments> wsdlQueries() {
        return Stream.of(
                Arguments.of(ECHO_PATH, "wsdl"),
                Arguments.of(ECHO_PATH, "WSDL"),
                Arguments.of(ECHO_PATH + "/sub", "wsdl"));
    }

    @ParameterizedTest(name = "GET {0}?{1}")
    @MethodSource("wsdlQueries")
    void wsdlQueryServesTheWsdlWithAddressAndReferencesRewritten(String path, String query, @TempDir Path root)
            throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = classpath.echoPublisher();

            String served = metadata(publisher, path, query);

            String requestUrl = ORIGIN + path;
            assertThat(served).contains("location=\"" + requestUrl + "\"").doesNotContain("placeholder.invalid");
            assertThat(addresses(served)).containsExactly(requestUrl);
            assertThat(locations(served)).containsExactly(requestUrl + "?xsd=" + MESSAGE_KEY);
            assertSameDocumentApartFromLocations(ECHO_WSDL_XML, served);
        }
    }

    @Test
    void everyRegisteredSchemaKeyIsServedWithItsOwnReferencesRewritten(@TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = classpath.echoPublisher();
            Map<String, List<String>> expectedReferences = Map.of(
                    MESSAGE_KEY, List.of(ORIGIN + ECHO_PATH + "?xsd=" + MODEL_KEY),
                    MODEL_KEY, List.of());

            for (Map.Entry<String, Resource> schema : publisher.registrations().get(0).schemas().entrySet()) {
                // Fully form-encoded here, unlike the publisher's own rewrite (which crawl() follows): the key is
                // matched after the container decodes the parameter, so either encoding selects the same schema.
                String served = metadata(publisher, ECHO_PATH, "xsd=" + URLEncoder.encode(schema.getKey(), UTF_8));

                assertThat(locations(served)).as("references of %s", schema.getKey())
                        .isEqualTo(expectedReferences.get(schema.getKey()));
                assertSameDocumentApartFromLocations(new String(schema.getValue().getContentAsByteArray(), UTF_8),
                        served);
            }
        }
    }

    @Test
    void everyRewrittenReferenceResolvesTransitively(@TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = classpath.echoPublisher();

            assertThat(crawl(publisher, ECHO_PATH)).containsExactly(MESSAGE_KEY, MODEL_KEY);
        }
    }

    @Test
    void locationNeedingPercentEncodingRoundTripsThroughTheRewrittenQuery(@TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = new WsdlQueryPublisher()
                    .register(classpath.resource("svc/Spaced.wsdl"), "/svc/Spaced");

            assertThat(publisher.registrations().get(0).schemas().keySet()).containsExactly(SPACED_KEY);
            assertThat(locations(metadata(publisher, "/svc/Spaced", "wsdl")))
                    .containsExactly(ORIGIN + "/svc/Spaced?xsd=" + SPACED_KEY_ENCODED);
            assertThat(crawl(publisher, "/svc/Spaced")).containsExactly(SPACED_KEY);
        }
    }

    /** WSDLs whose registration must fail start-up, each with the message fragment that names the cause. */
    static Stream<Arguments> unpublishableWsdls() {
        return Stream.of(
                // "Common.xsd" is svc/Common.xsd in svc/Clash.wsdl but svc/xsd/Common.xsd in svc/xsd/Clash.xsd.
                Arguments.of(Named.of("key denoting two resources", "svc/Clash.wsdl"),
                        "Schema location 'Common.xsd' in class path resource [svc/xsd/Clash.xsd] resolves to"),
                Arguments.of(Named.of("location found nowhere", "svc/Broken.wsdl"),
                        "Schema location 'xsd/Missing.xsd' referenced by class path resource [svc/Broken.wsdl]"
                                + " exists neither relative to it nor at the classpath root"),
                Arguments.of(Named.of("missing WSDL", "svc/Absent.wsdl"),
                        "class path resource [svc/Absent.wsdl] does not exist"),
                Arguments.of(Named.of("malformed schema", "svc/Malformed.wsdl"),
                        "Cannot parse class path resource [svc/xsd/Malformed.xsd]"),
                Arguments.of(Named.of("schema with a DOCTYPE", "svc/Doctype.wsdl"),
                        "Cannot parse class path resource [svc/xsd/Doctype.xsd]"),
                // The location holds a line feed (&#10;); the message names it escaped, on one line.
                Arguments.of(Named.of("location with a line feed", "svc/Forged.wsdl"),
                        "Schema location 'xsd/Missing\\n.xsd' referenced by"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unpublishableWsdls")
    void unpublishableWsdlFailsRegistrationAndLeavesEarlierOnesServed(String wsdl, String expectedMessage,
            @TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = classpath.echoPublisher();

            assertThatThrownBy(() -> publisher.register(classpath.resource(wsdl), "/svc/Failed"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(expectedMessage)
                    .message().doesNotContain("\n", "\r");
            assertThat(publisher.registrations()).hasSize(1);
            assertThat(serve(publisher, get("/svc/Failed", "wsdl")).chainInvoked()).isTrue();
            assertThat(crawl(publisher, ECHO_PATH)).containsExactly(MESSAGE_KEY, MODEL_KEY);
        }
    }

    @Test
    void keysOfSeparateRegistrationsAreIndependent(@TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            WsdlQueryPublisher publisher = classpath.echoPublisher()
                    .register(classpath.resource(ECHO_WSDL), "/svc/EchoAgain");

            assertThat(publisher.registrations()).hasSize(2);
            assertThat(crawl(publisher, ECHO_PATH)).containsExactly(MESSAGE_KEY, MODEL_KEY);
            assertThat(crawl(publisher, "/svc/EchoAgain")).containsExactly(MESSAGE_KEY, MODEL_KEY);
            assertThat(addresses(metadata(publisher, "/svc/EchoAgain", "wsdl")))
                    .containsExactly(ORIGIN + "/svc/EchoAgain");
        }
    }

    /** Requests the publisher must hand down the chain untouched: wrong path, no or unknown query, or not GET. */
    static Stream<Arguments> passThroughRequests() {
        return Stream.of(
                Arguments.of("GET", "/other/path", "wsdl"),
                Arguments.of("GET", ECHO_PATH, null),
                Arguments.of("GET", ECHO_PATH, "xsd=unknown.xsd"),
                Arguments.of("POST", ECHO_PATH, "wsdl"),
                Arguments.of("PUT", ECHO_PATH, "wsdl"),
                Arguments.of("HEAD", ECHO_PATH, "wsdl"),
                Arguments.of("POST", ECHO_PATH, "xsd=" + MODEL_KEY));
    }

    @ParameterizedTest(name = "{0} {1}?{2}")
    @MethodSource("passThroughRequests")
    void requestNotForPublishedMetadataPassesDownTheChainUntouched(String method, String path, String query,
            @TempDir Path root) throws Exception {
        try (TemporaryClasspath classpath = TemporaryClasspath.of(root)) {
            MockHttpServletRequest request = request(method, path, query);

            Served served = serve(classpath.echoPublisher(), request);

            assertThat(served.chainInvoked()).isTrue();
            assertThat(served.chain().getRequest()).isSameAs(request);
            assertThat(served.response().getStatus()).isEqualTo(200);
            assertThat(served.response().getContentType()).isNull();
            assertThat(served.response().getContentAsByteArray()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The consumer's own registrations
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Boots the consuming application, found by package scan, and serves every WSDL and schema its
     * {@link FilterRegistrationBean}-installed publishers registered, so each one is readable and its whole closure
     * resolves offline. A consumer that publishes no metadata installs no publisher and has nothing to serve.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
    class ConsumerContext {

        @Autowired
        ApplicationContext context;

        @Test
        void everyRegisteredWsdlAndSchemaIsServedOnItsListenerPath() throws Exception {
            List<WsdlQueryPublisher> publishers = context.getBeansOfType(FilterRegistrationBean.class).values()
                    .stream()
                    .map(FilterRegistrationBean::getFilter)
                    .filter(WsdlQueryPublisher.class::isInstance)
                    .map(WsdlQueryPublisher.class::cast)
                    .toList();
            // No publisher at all is a valid consumer: one whose SOAP listeners accept POST only publishes no metadata,
            // and a publishing consumer's endpoint tests assert its ?wsdl. An installed publisher must serve something.
            assertThat(publishers).as("WsdlQueryPublisher filters installed by FilterRegistrationBean")
                    .allMatch(publisher -> !publisher.registrations().isEmpty(), "holds at least one registration");

            for (WsdlQueryPublisher publisher : publishers) {
                for (WsdlQueryPublisher.Registration registration : publisher.registrations()) {
                    String path = registration.patterns().stream()
                            .filter(pattern -> !pattern.contains("*"))
                            .findFirst()
                            .orElse(null);
                    assertThat(path).as("literal listener path among %s", registration.patterns()).isNotNull();

                    assertThat(addresses(metadata(publisher, path, "wsdl"))).as("SOAP addresses served at %s", path)
                            .isNotEmpty()
                            .containsOnly(ORIGIN + path);
                    for (String key : registration.schemas().keySet()) {
                        metadata(publisher, path, "xsd=" + URLEncoder.encode(key, UTF_8));
                    }
                    assertThat(crawl(publisher, path)).as("schema closure served at %s", path)
                            .containsExactlyInAnyOrderElementsOf(registration.schemas().keySet());
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The fixture documents written under a temporary directory and an isolated loader over it, so the publisher's
     * classpath-root fallback can only find them through the referring resource's own loader.
     */
    private record TemporaryClasspath(URLClassLoader loader) implements AutoCloseable {

        static TemporaryClasspath of(Path root) throws IOException {
            write(root, ECHO_WSDL, ECHO_WSDL_XML);
            write(root, MESSAGE_KEY, ECHO_MESSAGE_XSD);
            write(root, "svc/xsd/" + MODEL_KEY, ECHO_MODEL_XSD);

            write(root, "svc/Spaced.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports(SPACED_KEY)));
            write(root, "svc/" + SPACED_KEY, IMPORTING_XSD_TEMPLATE.formatted(""));

            write(root, "svc/Clash.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports("Common.xsd", "xsd/Clash.xsd")));
            write(root, "svc/Common.xsd", IMPORTING_XSD_TEMPLATE.formatted(""));
            write(root, "svc/xsd/Clash.xsd", IMPORTING_XSD_TEMPLATE.formatted(imports("Common.xsd")));
            write(root, "svc/xsd/Common.xsd", IMPORTING_XSD_TEMPLATE.formatted(""));

            write(root, "svc/Broken.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports("xsd/Missing.xsd")));
            write(root, "svc/Forged.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports("xsd/Missing&#10;.xsd")));
            write(root, "svc/Malformed.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports("xsd/Malformed.xsd")));
            write(root, "svc/xsd/Malformed.xsd", "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">");
            write(root, "svc/Doctype.wsdl", IMPORTING_WSDL_TEMPLATE.formatted(imports("xsd/Doctype.xsd")));
            write(root, "svc/xsd/Doctype.xsd", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE xsd:schema [<!ENTITY part "echo">]>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:echo:part"/>
                    """);

            return new TemporaryClasspath(new URLClassLoader(new URL[] {root.toUri().toURL()}, null));
        }

        Resource resource(String path) {
            return new ClassPathResource(path, loader);
        }

        WsdlQueryPublisher echoPublisher() {
            return new WsdlQueryPublisher().register(resource(ECHO_WSDL), ECHO_PATH, ECHO_PATH + "/**");
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }

        private static void write(Path root, String path, String content) throws IOException {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, UTF_8);
        }

        private static String imports(String... locations) {
            StringBuilder imports = new StringBuilder();
            for (String location : locations) {
                imports.append("        <xsd:import namespace=\"urn:example:echo:part\" schemaLocation=\"")
                        .append(location).append("\"/>\n");
            }
            return imports.toString();
        }
    }

    /** The response and chain of one filter pass. */
    record Served(MockHttpServletResponse response, MockFilterChain chain) {

        boolean chainInvoked() {
            return chain.getRequest() != null;
        }

        String body() {
            return new String(response.getContentAsByteArray(), UTF_8);
        }
    }

    static MockHttpServletRequest get(String path, String query) {
        return request("GET", path, query);
    }

    /**
     * A request for {@code http://localhost:8080<path>[?<query>]} as a servlet container presents it at the root
     * context: the raw query string and, decoded, its parameters.
     */
    static MockHttpServletRequest request(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setContextPath("");
        request.setServletPath(path);
        request.setPathInfo(null);
        request.setQueryString(query);
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String name = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                request.addParameter(URLDecoder.decode(name, UTF_8), URLDecoder.decode(value, UTF_8));
            }
        }
        return request;
    }

    static Served serve(WsdlQueryPublisher publisher, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        publisher.doFilter(request, response, chain);
        return new Served(response, chain);
    }

    /** Fetches {@code GET <path>?<query>}, asserts the publisher answered it as metadata, and returns the body. */
    static String metadata(WsdlQueryPublisher publisher, String path, String query) throws Exception {
        Served served = serve(publisher, get(path, query));
        String request = "GET " + path + "?" + query;
        assertThat(served.chainInvoked()).as("%s passed down the chain", request).isFalse();
        assertThat(served.response().getStatus()).as("status of %s", request).isEqualTo(200);
        assertThat(served.response().getContentType()).as("content type of %s", request).isEqualTo(CONTENT_TYPE);
        assertThat(served.response().getContentLength()).as("content length of %s", request)
                .isEqualTo(served.response().getContentAsByteArray().length);
        assertThat(served.body()).as("body of %s", request).startsWith(XML_DECLARATION);
        return served.body();
    }

    /**
     * Fetches {@code ?wsdl} and then, breadth first, every reference of every served document, asserting that each
     * one was rewritten to {@code http://localhost:8080<path>?xsd=} and is served.
     *
     * @return the schema keys visited, in visiting order
     */
    static Set<String> crawl(WsdlQueryPublisher publisher, String path) throws Exception {
        String prefix = ORIGIN + path + "?";
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(locations(metadata(publisher, path, "wsdl")));
        while (!pending.isEmpty()) {
            String location = pending.poll();
            assertThat(location).as("reference served at %s", path).startsWith(prefix + "xsd=");
            String query = location.substring(prefix.length());
            if (visited.add(URLDecoder.decode(query.substring("xsd=".length()), UTF_8))) {
                pending.addAll(locations(metadata(publisher, path, query)));
            }
        }
        return visited;
    }

    /**
     * Every reference location in document order: {@code schemaLocation} of {@code xsd:import}, {@code xsd:include}
     * and {@code xsd:redefine}, and {@code location} of {@code wsdl:import}.
     */
    static List<String> locations(String xml) throws Exception {
        List<String> found = new ArrayList<>();
        NodeList elements = parse(xml).getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String namespace = element.getNamespaceURI();
            String localName = element.getLocalName();
            String attribute = null;
            if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace)
                    && Set.of("import", "include", "redefine").contains(localName)) {
                attribute = "schemaLocation";
            } else if (WSDL_NS.equals(namespace) && "import".equals(localName)) {
                attribute = "location";
            }
            if (attribute != null && element.hasAttributeNS(null, attribute)) {
                found.add(element.getAttributeNS(null, attribute));
            }
        }
        return found;
    }

    /** The {@code location} of every {@code soap:address} and {@code soap12:address}. */
    static List<String> addresses(String xml) throws Exception {
        List<String> found = new ArrayList<>();
        NodeList elements = parse(xml).getElementsByTagNameNS("*", "address");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String namespace = element.getNamespaceURI();
            if (SOAP11_BINDING_NS.equals(namespace) || SOAP12_BINDING_NS.equals(namespace)) {
                found.add(element.getAttributeNS(null, "location"));
            }
        }
        return found;
    }

    /** {@code served} is {@code source} with nothing changed but SOAP addresses and reference locations. */
    static void assertSameDocumentApartFromLocations(String source, String served) {
        Diff diff = DiffBuilder.compare(Input.fromString(source))
                .withTest(Input.fromString(served))
                .withAttributeFilter(attribute -> attribute.getNamespaceURI() != null
                        || !LOCATION_ATTRIBUTES.contains(attribute.getLocalName()))
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as("differences: %s", diff).isFalse();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(UTF_8)));
    }

    private static Path file(Resource resource) throws IOException {
        return resource.getFile().toPath().toRealPath();
    }
}
