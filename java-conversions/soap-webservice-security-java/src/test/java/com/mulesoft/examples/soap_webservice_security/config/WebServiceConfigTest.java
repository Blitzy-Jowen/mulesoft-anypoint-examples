package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.wss4j.common.WSS4JConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.xml.transform.StringSource;
import org.springframework.xml.transform.TransformerFactoryUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.soap_webservice_security.config.WsSecurityConfig.LazyMerlinCrypto;
import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;
import com.mulesoft.examples.soap_webservice_security.service.GreeterService;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient;

/**
 * Proves, through the real {@code MessageDispatcherServlet} on a random port, the request order
 * {@link WebServiceConfig} gives the six Greeter paths: each path's WS-Security policy is enforced exactly once per
 * request, before any endpoint mapping selects an operation, and the operation is then selected on the decrypted
 * payload.
 * <ul>
 *   <li>An encrypted body, whose root on the wire is {@code xenc:EncryptedData}, reaches {@code greet}.</li>
 *   <li>A secured path rejects a request without {@code wsse:Security} with {@code wsse:InvalidSecurity}, whatever
 *       its body root, so an unknown operation cannot bypass the policy through the unknown-operation fallback.</li>
 *   <li>A {@code mustUnderstand} security header is understood on a secured path for every mapping, the fallback
 *       included, and still not understood on {@code /services/unsecure}.</li>
 *   <li>{@code ?wsdl} stays anonymous.</li>
 * </ul>
 * The only endpoint maps {@code greet} alone and nothing maps {@code EncryptedData}, so a greeting for an encrypted
 * request proves the body was decrypted before the operation was selected. Wire assertions read the raw response
 * through ST-1; secured requests are built by Spring's client-side {@link Wss4jSecurityInterceptor} with the original
 * client action sets and the demo credentials of profile {@code test}.
 */
@SpringBootTest(
        classes = WebServiceConfigTest.GreeterServicesConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + WebServiceConfigTest.LOOPBACK)
@ActiveProfiles("test")
class WebServiceConfigTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    private static final String NO_SECURITY = "";

    private static final String USERNAME_TOKEN_TIMESTAMP = "UsernameToken Timestamp";

    private static final String USERNAME_TOKEN_TIMESTAMP_ENCRYPT = "UsernameToken Timestamp Encrypt";

    private static final String GREET_XML =
            "<ns:greet xmlns:ns=\"" + Greet.NAMESPACE + "\"><name>Mule</name></ns:greet>";

    private static final String UNKNOWN_OPERATION_XML =
            "<ns:unknownOperation xmlns:ns=\"" + Greet.NAMESPACE + "\"/>";

    private static final QName GREET = new QName(Greet.NAMESPACE, "greet");

    private static final QName ENCRYPTED_DATA = new QName(WSS4JConstants.ENC_NS, "EncryptedData");

    private static final String HELLO_MULE_RESPONSE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<ns2:greetResponse xmlns:ns2=\"http://security.example.mule.mulesoft.com/\">"
                    + "<name>Hello Mule</name></ns2:greetResponse></soap:Body></soap:Envelope>";

    private static final String INVALID_SECURITY_FAULT_STRING =
            "An error was discovered processing the <wsse:Security> header";

    private static final String UNKNOWN_OPERATION_FAULT_STRING = "Message part {" + Greet.NAMESPACE
            + "}unknownOperation was not recognized.  (Does it exist in service WSDL?)";

    private static final QName SECURITY_HEADER = new QName(WSS4JConstants.WSSE_NS, "Security");

    @LocalServerPort
    int port;

    @Autowired
    private SaajSoapMessageFactory messageFactory;

    @Autowired
    @Qualifier(WebServiceConfig.GREETER_MARSHALLER_BEAN_NAME)
    private Jaxb2Marshaller greeterMarshaller;

    @Autowired
    private LazyMerlinCrypto merlinCrypto;

    @Autowired
    private SoapSecurityProperties properties;

    /** The configured enforcement itself, observed so each test can count how often it ran. */
    @SpyBean
    private PathScopedSecurityInterceptor securityInterceptor;

    /** Path, client action set (blank for no security header), and the body root the request carries on the wire. */
    static Stream<Arguments> greetings() {
        return Stream.of(
                Arguments.of(WsSecurityConfig.UNSECURE_PATH, NO_SECURITY, GREET),
                Arguments.of(WsSecurityConfig.USERNAME_PATH, USERNAME_TOKEN_TIMESTAMP, GREET),
                Arguments.of(WsSecurityConfig.ENCRYPTED_PATH, USERNAME_TOKEN_TIMESTAMP_ENCRYPT, ENCRYPTED_DATA));
    }

    @ParameterizedTest(name = "{0} with [{1}]")
    @MethodSource("greetings")
    void greetIsSelectedOnThePayloadLeftAfterSecurityProcessing(String path, String actions, QName wireBodyRoot)
            throws Exception {
        byte[] request = envelope(actions, GREET_XML);
        assertThat(bodyRoot(parse(request))).as("body root on the wire").isEqualTo(wireBodyRoot);

        HttpTestClient.Response response = post(path, request);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.bodyAsString()).isEqualTo(HELLO_MULE_RESPONSE);
        verify(securityInterceptor, times(1)).handleRequest(any(), any());
    }

    static Stream<Arguments> unsecuredRequestsToSecuredPaths() {
        return Stream.of(WsSecurityConfig.USERNAME_PATH, WsSecurityConfig.SIGNED_PATH,
                        WsSecurityConfig.ENCRYPTED_PATH, WsSecurityConfig.SAML_PATH, WsSecurityConfig.SIGNED_SAML_PATH)
                .flatMap(path -> Stream.of(Arguments.of(path, UNKNOWN_OPERATION_XML), Arguments.of(path, GREET_XML)));
    }

    @ParameterizedTest(name = "{0} with body {1}")
    @MethodSource("unsecuredRequestsToSecuredPaths")
    void securedPathRejectsARequestWithoutSecurityWhateverItsOperation(String path, String bodyXml)
            throws Exception {
        HttpTestClient.Response response = post(path, envelope(NO_SECURITY, bodyXml));

        assertFault(response, "wsse:InvalidSecurity", WSS4JConstants.WSSE_NS, INVALID_SECURITY_FAULT_STRING);
        verify(securityInterceptor, times(1)).handleRequest(any(), any());
    }

    /**
     * Path and client action set of requests whose policy is met and whose body root no endpoint maps; the
     * encrypted one names its operation only after decryption.
     */
    static Stream<Arguments> unknownOperationsPassingSecurity() {
        return Stream.of(
                Arguments.of(WsSecurityConfig.UNSECURE_PATH, NO_SECURITY),
                Arguments.of(WsSecurityConfig.USERNAME_PATH, USERNAME_TOKEN_TIMESTAMP),
                Arguments.of(WsSecurityConfig.ENCRYPTED_PATH, USERNAME_TOKEN_TIMESTAMP_ENCRYPT));
    }

    @ParameterizedTest(name = "{0} with [{1}]")
    @MethodSource("unknownOperationsPassingSecurity")
    void unknownOperationPassingSecurityGetsTheUnknownPartFault(String path, String actions) throws Exception {
        HttpTestClient.Response response = post(path, envelope(actions, UNKNOWN_OPERATION_XML));

        // Not a MustUnderstand fault: the fallback mapping's chain understands the security header too.
        assertFault(response, "soap:Client", SoapEnvelopeStyleInterceptor.SOAP11_NS, UNKNOWN_OPERATION_FAULT_STRING);
        verify(securityInterceptor, times(1)).handleRequest(any(), any());
    }

    @Test
    void securedClientSendingAMustUnderstandHeaderIsGreeted() throws Exception {
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(greeterMarshaller);
        template.setUnmarshaller(greeterMarshaller);
        template.setInterceptors(new ClientInterceptor[] { securer(USERNAME_TOKEN_TIMESTAMP) });

        Object response = template.marshalSendAndReceive(uri(WsSecurityConfig.USERNAME_PATH), new Greet("Mule"));

        assertThat(response).isInstanceOf(GreetResponse.class);
        assertThat(((GreetResponse) response).getName()).isEqualTo("Hello Mule");
        verify(securityInterceptor, atLeastOnce())
                .understands(argThat(header -> SECURITY_HEADER.equals(header.getName())));
        verify(securityInterceptor, times(1)).handleRequest(any(), any());
    }

    @Test
    void unsecuredPathDoesNotUnderstandAMustUnderstandSecurityHeader() throws Exception {
        String header = "<soapenv:Header><wsse:Security xmlns:wsse=\"" + WSS4JConstants.WSSE_NS
                + "\" soapenv:mustUnderstand=\"1\"/></soapenv:Header>";

        HttpTestClient.Response response = post(WsSecurityConfig.UNSECURE_PATH, rawEnvelope(header, GREET_XML));

        assertFault(response, "soap:MustUnderstand", SoapEnvelopeStyleInterceptor.SOAP11_NS,
                "MustUnderstand headers: [{" + WSS4JConstants.WSSE_NS + "}Security] are not understood.");
    }

    @ParameterizedTest
    @ValueSource(strings = { WsSecurityConfig.USERNAME_PATH, WsSecurityConfig.ENCRYPTED_PATH })
    void wsdlIsPublishedWithoutCredentialsOnASecuredPath(String path) {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).get(path + "?wsdl");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).contains("<wsdl:definitions")
                .contains("location=\"" + uri(path) + "\"");
        verify(securityInterceptor, never()).handleRequest(any(), any());
    }

    /**
     * A SOAP 1.1 envelope carrying {@code bodyXml}, secured by the client action set {@code actions}, or sent without
     * any header when {@code actions} is blank.
     */
    private byte[] envelope(String actions, String bodyXml) throws Exception {
        if (actions.isBlank()) {
            return rawEnvelope("", bodyXml);
        }
        SaajSoapMessage message = messageFactory.createWebServiceMessage();
        TransformerFactoryUtils.newInstance().newTransformer()
                .transform(new StringSource(bodyXml), message.getPayloadResult());
        MessageContext context = new DefaultMessageContext(message, messageFactory);
        Wss4jSecurityInterceptor securer = securer(actions);
        assertThat(securer.handleRequest(context)).as("request secured").isTrue();
        securer.afterCompletion(context, null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        context.getRequest().writeTo(bytes);
        return bytes.toByteArray();
    }

    private static byte[] rawEnvelope(String header, String bodyXml) {
        return ("<soapenv:Envelope xmlns:soapenv=\"" + SoapEnvelopeStyleInterceptor.SOAP11_NS + "\">" + header
                + "<soapenv:Body>" + bodyXml + "</soapenv:Body></soapenv:Envelope>").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The original client's securing interceptor for {@code actions}: the configured client user and that user's
     * password, and for {@code Encrypt} the configured encryption user's certificate from the Merlin crypto.
     */
    private Wss4jSecurityInterceptor securer(String actions) throws Exception {
        String user = properties.getClient().getUser();
        Wss4jSecurityInterceptor securer = new Wss4jSecurityInterceptor();
        securer.setSecurementActions(actions);
        securer.setSecurementUsername(user);
        securer.setSecurementPassword(properties.passwordFor(user).orElseThrow());
        if (actions.contains("Encrypt")) {
            securer.setSecurementEncryptionCrypto(merlinCrypto.get());
            securer.setSecurementEncryptionUser(properties.getClient().getEncryptionUser());
        }
        securer.afterPropertiesSet();
        return securer;
    }

    private HttpTestClient.Response post(String path, byte[] envelope) {
        return new HttpTestClient(LOOPBACK, port).send("POST", path, Map.of("Content-Type", TEXT_XML_UTF8), envelope);
    }

    private String uri(String path) {
        return "http://" + LOOPBACK + ":" + port + path;
    }

    /**
     * A SOAP 1.1 fault over HTTP 500 in CXF's envelope style, whose {@code faultcode} reads {@code faultCode} with its
     * prefix bound to {@code faultCodeNamespace}, and whose {@code faultstring} is exactly {@code faultString}.
     */
    private static void assertFault(HttpTestClient.Response response, String faultCode, String faultCodeNamespace,
            String faultString) throws Exception {
        assertThat(response.status()).as("status of %s", response).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).startsWith("<soap:Envelope").doesNotContain("<soap:Header")
                .doesNotContain("xml:lang");

        Document document = parse(response.body());
        Element code = single(document, "faultcode");
        assertThat(code.getTextContent()).isEqualTo(faultCode);
        String prefix = faultCode.substring(0, faultCode.indexOf(':'));
        assertThat(code.lookupNamespaceURI(prefix)).as("namespace of fault code prefix %s", prefix)
                .isEqualTo(faultCodeNamespace);
        assertThat(single(document, "faultstring").getTextContent()).isEqualTo(faultString);
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element single(Document document, String localName) {
        NodeList matches = document.getElementsByTagNameNS("*", localName);
        assertThat(matches.getLength()).as("number of <%s> elements", localName).isEqualTo(1);
        return (Element) matches.item(0);
    }

    /** The qualified name of the first element under the SOAP 1.1 {@code Body}. */
    private static QName bodyRoot(Document envelope) {
        NodeList bodies = envelope.getElementsByTagNameNS(SoapEnvelopeStyleInterceptor.SOAP11_NS, "Body");
        assertThat(bodies.getLength()).as("number of SOAP bodies").isEqualTo(1);
        for (Node child = bodies.item(0).getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return new QName(child.getNamespaceURI(), child.getLocalName());
            }
        }
        throw new AssertionError("SOAP body has no child element");
    }

    /**
     * The project's SOAP and WS-Security configuration on Spring Boot's auto-configured web and web-services stack,
     * around {@link GreetOnlyEndpoint}.
     * <p>
     * Nothing is component-scanned, so no other endpoint can map {@code greet}, and the routing filter of the
     * listener ports, which needs the fixed client port, stays out. It is deliberately a plain configuration and not
     * a {@code @SpringBootConfiguration}: Spring Boot looks for that annotation in the test's package and every
     * package below it, so a second one here would stop the project's other {@code @SpringBootTest} classes from
     * finding the application class.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(SoapSecurityProperties.class)
    @Import({ WebServiceConfig.class, WsSecurityConfig.class, SoapFaultConfig.class, SoapEnvelopeStyleInterceptor.class,
            GreeterService.class, GreetOnlyEndpoint.class })
    static class GreeterServicesConfig {
    }

    /** Maps the Greeter operation {@code greet} and nothing else, in particular no {@code xenc:EncryptedData}. */
    @Endpoint
    static class GreetOnlyEndpoint {

        private final GreeterService greeterService;

        GreetOnlyEndpoint(GreeterService greeterService) {
            this.greeterService = greeterService;
        }

        @PayloadRoot(namespace = Greet.NAMESPACE, localPart = "greet")
        @ResponsePayload
        public GreetResponse greet(@RequestPayload Greet request) {
            return new GreetResponse(greeterService.greet(request.getName()));
        }
    }
}
