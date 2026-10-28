/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.wsdl.Definition;
import javax.wsdl.Port;
import javax.wsdl.PortType;
import javax.wsdl.Service;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.mulesoft.examples.soap_webservice_security.SoapWebserviceSecurityApplication;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient.OutboundSecurity;
import com.mulesoft.examples.soap_webservice_security.config.PasswordCallback;
import com.mulesoft.examples.soap_webservice_security.config.SAMLCallbackHandler;
import com.mulesoft.examples.soap_webservice_security.config.WebServiceConfig;
import com.mulesoft.examples.soap_webservice_security.config.WrongPasswordCallback;
import com.mulesoft.examples.soap_webservice_security.config.WsSecurityConfig.LazyMerlinCrypto;
import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient;

/**
 * Endpoint test of the six Greeter paths the original {@code mule-config.xml} exposed as {@code cxf:jaxws-service}
 * flows on its SOAP listener under {@code /services}: {@link GreeterEndpoint} behind
 * {@code PathScopedSecurityInterceptor} with the {@code pathPolicies} of {@code WsSecurityConfig}, and the SC-11
 * envelope style, fault, listener-boundary and {@code ?wsdl} components {@code WebServiceConfig} wires around it. It is
 * the server half of the original {@code SoapWebserviceSecurityIT}.
 *
 * <p>Each path requires its original action set, matched in any order by WSS4J's receiver check:
 * <ul>
 *   <li>{@code unsecure}: none;</li>
 *   <li>{@code username}: UsernameToken Timestamp;</li>
 *   <li>{@code signed}: UsernameToken Signature Timestamp;</li>
 *   <li>{@code encrypted}: UsernameToken Timestamp Encrypt;</li>
 *   <li>{@code saml}: SAMLTokenUnsigned Timestamp;</li>
 *   <li>{@code signedsaml}: SAMLTokenUnsigned Signature.</li>
 * </ul>
 * The test covers every original success and negative request, a second configured user, reordered action sets, the
 * {@code BinarySecurityToken} results of {@code DirectReference} signatures that the check ignores, missing and extra
 * actions, a missing security header on every secured path, the MustUnderstand fault of the unsecured path, the
 * published WSDL and the CXF response envelope.
 *
 * <p>Requests travel over HTTP to the running application: through {@link SecureGreeterClient} for the original
 * client profiles, through {@link WebServiceTemplate}s built here when the wire request or the HTTP status is asserted,
 * and through ST-1 {@link HttpTestClient} for raw envelopes and status lines. Demo users, keystore password and SAML
 * issuer key password come from profile {@code test}; passwords are obtained from the {@link PasswordCallback} bean.
 */
// The SOAP listener runs on a random port, as SoapWebserviceSecurityTest's does: the plan maps the originals' fixed
// ports to RANDOM_PORT plus @DynamicPropertySource, and a random port leaves no gap between choosing and binding it.
// Only the additional client listener, which ListenerPorts requires to have a concrete port, gets a free port.
@SpringBootTest(classes = SoapWebserviceSecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GreeterEndpointTest {

    private static final String NS = GreeterEndpoint.NAMESPACE_URI;

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String XENC_NS = "http://www.w3.org/2001/04/xmlenc#";

    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String INVALID_SECURITY_TEXT = "An error was discovered processing the <wsse:Security> header";

    private static final String FAILED_AUTH_TEXT = "The security token could not be authenticated or authorized";

    /** The unsecure request of the original README. */
    private static final String GREET_ENVELOPE = "<soap:Envelope xmlns:soap=\"" + SOAP11_NS + "\"><soap:Body>"
            + "<ns2:greet xmlns:ns2=\"" + NS + "\"><name>John</name></ns2:greet></soap:Body></soap:Envelope>";

    /** CXF's answer to {@link #GREET_ENVELOPE}: {@code soap} prefix, no empty Header, {@code ns2}, compact. */
    private static final String HELLO_JOHN_ENVELOPE = "<soap:Envelope xmlns:soap=\"" + SOAP11_NS + "\"><soap:Body>"
            + "<ns2:greetResponse xmlns:ns2=\"" + NS + "\"><name>Hello John</name></ns2:greetResponse>"
            + "</soap:Body></soap:Envelope>";

    private static final String WSDL_CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final String JOE = "joe";

    private static final String STAN = "stan";

    private static final String DIRECT_REFERENCE = "DirectReference";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Port of the {@code /client} listener, allocated once for the class so a cached context and its bound listener
     * keep agreeing on it.
     */
    private static final int CLIENT_PORT = TestSocketUtils.findAvailableTcpPort();

    @LocalServerPort
    private int port;

    @Autowired
    private SecureGreeterClient client;

    @Autowired
    @Qualifier(WebServiceConfig.MESSAGE_FACTORY_BEAN_NAME)
    private SaajSoapMessageFactory messageFactory;

    @Autowired
    @Qualifier(WebServiceConfig.GREETER_MARSHALLER_BEAN_NAME)
    private Jaxb2Marshaller marshaller;

    @Autowired
    @Qualifier("merlinCrypto")
    private LazyMerlinCrypto merlinCrypto;

    @Autowired
    @Qualifier("passwordCallback")
    private PasswordCallback passwordCallback;

    @Autowired
    @Qualifier("samlCallbackHandler")
    private SAMLCallbackHandler samlCallbackHandler;

    @DynamicPropertySource
    static void listeners(DynamicPropertyRegistry registry) {
        // listeners.additional[0].port reads ${ports.client}; overriding the scalar keeps the list's name, host and
        // routes from application.yml, which a registered list element would replace as a whole.
        registry.add("ports.client", () -> CLIENT_PORT);
        // The client's own default host is 0.0.0.0; its named client methods must call this test's listener only.
        registry.add("soap-security.client.host", () -> "localhost");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Original success requests (SoapWebserviceSecurityIT), each answered "Hello Mule"
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unsecurePathGreetsARequestWithoutSecurityHeader() {
        assertThat(client.greet(uri("unsecure"), OutboundSecurity.NONE, "Mule")).isEqualTo("Hello Mule");
    }

    @Test
    void usernamePathAcceptsUsernameTokenAndTimestamp() {
        assertThat(client.greet(uri("username"), client.getUsernameTokenProps("UsernameToken Timestamp"), "Mule"))
                .isEqualTo("Hello Mule");
    }

    @Test
    void signedPathAcceptsASignedUsernameToken() {
        assertThat(client.greet(uri("signed"),
                client.getUsernameTokenProps("UsernameToken Signature Timestamp"), "Mule"))
                .isEqualTo("Hello Mule");
    }

    @Test
    void encryptedPathAcceptsAnEncryptedUsernameTokenRequest() {
        assertThat(client.greet(uri("encrypted"),
                client.getUsernameTokenProps("UsernameToken Timestamp Encrypt"), "Mule"))
                .isEqualTo("Hello Mule");
    }

    @Test
    void samlPathAcceptsAnUnsignedSamlAssertionAndTimestamp() {
        assertThat(client.greet(uri("saml"),
                client.getSamlTokenProps("SAMLTokenUnsigned Timestamp", "saml.properties"), "Mule"))
                .isEqualTo("Hello Mule");
    }

    @Test
    void signedSamlPathAcceptsTheSignedSamlTokenOfTheOriginalClient() {
        assertThat(client.greet(uri("signedsaml"),
                client.getSignedSamlTokenProps("SAMLTokenSigned", "saml.properties"), "Mule"))
                .isEqualTo("Hello Mule");
        // The samlTokenSigned client type of service-clients.xml, which no original test called.
        assertThat(client.samlTokenSigned("Mule")).isEqualTo("Hello Mule");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Further accepted requests: second user, any action order, ignored token results, decryption before dispatch
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void usernamePathAcceptsTheSecondConfiguredUser() throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template = template(securement("UsernameToken Timestamp", STAN, null, null), capture);

        assertThat(greetVia(template, uri("username"), "Mule")).isEqualTo("Hello Mule");
        assertThat(capture.status).isEqualTo(200);
        assertThat(single(capture.request(), WSSE_NS, "Username").getTextContent()).isEqualTo(STAN);
    }

    static Stream<Arguments> reorderedActionSets() {
        return Stream.of(
                Arguments.of("username", "Timestamp UsernameToken"),
                Arguments.of("signed", "Timestamp Signature UsernameToken"),
                Arguments.of("encrypted", "Encrypt Timestamp UsernameToken"));
    }

    @ParameterizedTest(name = "/services/{0} with [{1}]")
    @MethodSource("reorderedActionSets")
    void requiredActionsAreMatchedInAnyOrder(String path, String actions) throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template = template(securement(actions, JOE, null, null), capture);

        assertThat(greetVia(template, uri(path), "Mule")).isEqualTo("Hello Mule");
        assertThat(capture.status).isEqualTo(200);
    }

    @Test
    void signedSamlRequestPassesAlthoughItCarriesABinarySecurityToken() throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template =
                template(securement("SAMLTokenSigned", JOE, DIRECT_REFERENCE, samlCallbackHandler), capture);

        assertThat(greetVia(template, uri("signedsaml"), "Mule")).isEqualTo("Hello Mule");
        assertThat(capture.status).isEqualTo(200);
        // SAMLTokenUnsigned and Signature match the path; the signing certificate's token result is skipped.
        Document request = capture.request();
        assertThat(count(request, WSSE_NS, "BinarySecurityToken")).isEqualTo(1);
        assertThat(count(request, "urn:oasis:names:tc:SAML:2.0:assertion", "Assertion")).isEqualTo(1);
    }

    @Test
    void directReferenceSignedUsernameTokenPassesAlthoughItCarriesABinarySecurityToken() throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template =
                template(securement("UsernameToken Signature Timestamp", JOE, DIRECT_REFERENCE, null), capture);

        assertThat(greetVia(template, uri("signed"), "Mule")).isEqualTo("Hello Mule");
        assertThat(capture.status).isEqualTo(200);
        assertThat(count(capture.request(), WSSE_NS, "BinarySecurityToken")).isEqualTo(1);
    }

    @Test
    void encryptedBodyIsDecryptedBeforeTheGreetOperationIsSelected() throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template = template(securement("UsernameToken Timestamp Encrypt", JOE, null, null), capture);

        assertThat(greetVia(template, uri("encrypted"), "Mule")).isEqualTo("Hello Mule");
        assertThat(capture.status).isEqualTo(200);
        // GreeterEndpoint maps greet alone, so the greeting proves the body was decrypted before dispatch.
        assertThat(bodyRoot(capture.request())).isEqualTo(new QName(XENC_NS, "EncryptedData"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rejected requests: SOAP 1.1 faults over HTTP 500. SecureGreeterClient keeps WebServiceTemplate's default
    // checkConnectionForFault, so a SoapFaultClientException from it means the connection reported HTTP 500 with an
    // XML body; test-built templates record the status themselves.
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "POST /services/{0} without wsse:Security")
    @ValueSource(strings = { "username", "signed", "encrypted", "saml", "signedsaml" })
    void securedPathRejectsARequestWithoutSecurityHeader(String path) throws Exception {
        HttpTestClient.Response response = postEnvelope("/services/" + path, GREET_ENVELOPE);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = rawFault(response);
        assertThat(fault.code()).isEqualTo(new QName(WSSE_NS, "InvalidSecurity"));
        assertThat(fault.text()).isEqualTo(INVALID_SECURITY_TEXT);
    }

    @Test
    void signedPathRejectsAnUnsignedUsernameToken() {
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class, () -> client.greet(uri("signed"),
                client.getUsernameTokenProps("UsernameToken Timestamp"), "Mule"));

        assertSecurityFault(e, "InvalidSecurity", INVALID_SECURITY_TEXT, "<wsse:Security> header");
    }

    static Stream<Arguments> missingActions() {
        return Stream.of(
                Arguments.of("encrypted", "UsernameToken Timestamp"),
                Arguments.of("username", "UsernameToken"),
                Arguments.of("username", "Timestamp"));
    }

    @ParameterizedTest(name = "/services/{0} with [{1}] only")
    @MethodSource("missingActions")
    void requestMissingARequiredActionIsRejected(String path, String actions) throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template = template(securement(actions, JOE, null, null), capture);

        SoapFaultClientException e =
                assertThrows(SoapFaultClientException.class, () -> greetVia(template, uri(path), "Mule"));

        assertThat(capture.status).isEqualTo(500);
        assertSecurityFault(e, "InvalidSecurity", INVALID_SECURITY_TEXT, "<wsse:Security> header");
    }

    @Test
    void signedSamlPathRejectsAnActionItDoesNotRequire() {
        // Every crypto the signed SAML request needs is available; only the extra Timestamp result fails the check.
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class, () -> client.greet(
                uri("signedsaml"), client.getSignedSamlTokenProps("SAMLTokenSigned Timestamp", "saml.properties"),
                "Mule"));

        assertSecurityFault(e, "InvalidSecurity", INVALID_SECURITY_TEXT, "<wsse:Security> header");
    }

    @Test
    void samlPathRejectsAnAssertionFromTheWrongIssuer() {
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class, () -> client.greet(uri("saml"),
                client.getSamlTokenProps("SAMLTokenUnsigned Timestamp", "wrong-saml.properties"), "Mule"));

        assertSecurityFault(e, "InvalidSecurity", INVALID_SECURITY_TEXT, "<wsse:Security> header");
    }

    @Test
    void usernamePathRejectsAWrongPassword() {
        OutboundSecurity wrongPassword = client.getUsernameTokenProps("UsernameToken Timestamp")
                .withPasswordCallback(new WrongPasswordCallback());

        SoapFaultClientException e = assertThrows(SoapFaultClientException.class,
                () -> client.greet(uri("username"), wrongPassword, "Mule"));

        assertSecurityFault(e, "FailedAuthentication", FAILED_AUTH_TEXT, "security token");
    }

    @Test
    void unsecurePathDoesNotUnderstandAMustUnderstandSecurityHeader() throws Exception {
        Capture capture = new Capture();
        WebServiceTemplate template = template(securement("UsernameToken Timestamp", JOE, null, null), capture);

        SoapFaultClientException e =
                assertThrows(SoapFaultClientException.class, () -> greetVia(template, uri("unsecure"), "Mule"));

        assertThat(single(capture.request(), WSSE_NS, "Security").getAttributeNS(SOAP11_NS, "mustUnderstand"))
                .isEqualTo("1");
        assertThat(capture.status).isEqualTo(500);
        // Only the fault code is asserted: the MustUnderstand faultstring CXF wrote is a Tier 2A verification point.
        assertThat(e.getFaultCode()).isEqualTo(new QName(SOAP11_NS, "MustUnderstand"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SC-11: published WSDL, CXF envelope style and the listener boundary
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "GET /services/{0}?wsdl")
    @ValueSource(strings = { "unsecure", "username", "signed", "encrypted", "saml", "signedsaml" })
    void eachPathPublishesTheGreeterWsdlAtTheRequestUrl(String path) throws Exception {
        HttpTestClient.Response response = http().get("/services/" + path + "?wsdl");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo(WSDL_CONTENT_TYPE);

        WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
        reader.setFeature("javax.wsdl.verbose", false);
        reader.setFeature("javax.wsdl.importDocuments", false);
        Definition definition = reader.readWSDL(null, new InputSource(new ByteArrayInputStream(response.body())));

        PortType portType = definition.getPortType(new QName(NS, "Greeter"));
        assertThat(portType).isNotNull();
        assertThat(portType.getOperation("greet", null, null)).isNotNull();
        Service service = definition.getService(new QName(NS, "GreeterService"));
        assertThat(service).isNotNull();
        Port greeterPort = service.getPort("GreeterPort");
        assertThat(greeterPort).isNotNull();
        assertThat(greeterPort.getBinding().getPortType().getQName()).isEqualTo(portType.getQName());
        List<?> extensions = greeterPort.getExtensibilityElements();
        SOAPAddress address = extensions.stream()
                .filter(SOAPAddress.class::isInstance)
                .map(SOAPAddress.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("GreeterPort has no soap:address"));
        assertThat(address.getLocationURI()).isEqualTo(uri(path).toString());
    }

    @Test
    void unsecureGreetingIsAnsweredWithTheCompactCxfEnvelope() {
        HttpTestClient.Response response = postEnvelope("/services/unsecure", GREET_ENVELOPE);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(HELLO_JOHN_ENVELOPE);
    }

    @Test
    void getWithoutMetadataQueryIsReadAsAMessageAndAnsweredWithAClientFault() throws Exception {
        // The original SOAP listener accepts every method, so no Greeter path is POST-only and the empty GET is read
        // as a message.
        HttpTestClient.Response response = http().get("/services/unsecure");

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        Fault fault = rawFault(response);
        assertThat(fault.code()).isEqualTo(new QName(SOAP11_NS, "Client"));
        // The parser's message is the faultstring; its exact text is a Tier 2A verification point.
        assertThat(fault.text()).isNotBlank();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** The Greeter path {@code /services/<path>} on the SOAP listener. */
    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/services/" + path);
    }

    private HttpTestClient http() {
        return HttpTestClient.localhost(port);
    }

    /** A POST of {@code envelope} with the headers the CXF clients sent. */
    private HttpTestClient.Response postEnvelope(String path, String envelope) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/xml; charset=UTF-8");
        headers.put("SOAPAction", "\"\"");
        return http().send("POST", path, headers, envelope.getBytes(StandardCharsets.UTF_8));
    }

    /** A SOAP 1.1 template over the application's message factory and Greeter marshaller, built for one test. */
    private WebServiceTemplate template(ClientInterceptor... interceptors) {
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(TIMEOUT);
        sender.setReadTimeout(TIMEOUT);
        template.setMessageSender(sender);
        template.setInterceptors(interceptors);
        return template;
    }

    private static String greetVia(WebServiceTemplate template, URI uri, String name) {
        Object response = template.marshalSendAndReceive(uri.toString(), new Greet(name));
        assertThat(response).isInstanceOf(GreetResponse.class);
        return ((GreetResponse) response).getName();
    }

    /**
     * A securing client interceptor for {@code actions}, sent as {@code user} with the password the
     * {@link PasswordCallback} bean holds for that user; Spring WS 4.1 takes no securement password callback, so the
     * password is resolved here as {@code SecureGreeterClient} resolves it. Signing uses the user's key, encryption the
     * {@code joe} certificate, both from the application's Merlin crypto.
     *
     * @param keyIdentifier signature key identifier, or {@code null} for WSS4J's default
     * @param samlCallback  SAML assertion callback of the SAML actions, or {@code null}
     */
    private Wss4jSecurityInterceptor securement(String actions, String user, String keyIdentifier,
            CallbackHandler samlCallback) throws Exception {
        Wss4jSecurityInterceptor securer = new Wss4jSecurityInterceptor();
        securer.setSecurementActions(actions);
        securer.setSecurementUsername(user);
        securer.setSecurementSignatureUser(user);
        securer.setSecurementPassword(passwordOf(user,
                actions.contains("UsernameToken") ? WSPasswordCallback.USERNAME_TOKEN : WSPasswordCallback.SIGNATURE));
        securer.setSecurementSignatureCrypto(merlinCrypto.get());
        securer.setSecurementEncryptionCrypto(merlinCrypto.get());
        securer.setSecurementEncryptionUser(JOE);
        if (keyIdentifier != null) {
            securer.setSecurementSignatureKeyIdentifier(keyIdentifier);
        }
        if (samlCallback != null) {
            securer.setSecurementSamlCallbackHandler(samlCallback);
        }
        securer.setValidationActions("NoSecurity");
        securer.setValidateResponse(false);
        securer.afterPropertiesSet();
        return securer;
    }

    private String passwordOf(String user, int usage) throws Exception {
        WSPasswordCallback callback = new WSPasswordCallback(user, usage);
        passwordCallback.handle(new Callback[] { callback });
        assertThat(callback.getPassword()).as("password of %s in profile test", user).isNotEmpty();
        return callback.getPassword();
    }

    /** A WS-Security fault: {@code wsse:<local>} with the exact WSS4J text, containing the original substring. */
    private static void assertSecurityFault(SoapFaultClientException e, String local, String text,
            String originalSubstring) {
        assertThat(e.getFaultCode()).isEqualTo(new QName(WSSE_NS, local));
        assertThat(e.getFaultStringOrReason()).isEqualTo(text);
        assertThat(e.getMessage()).contains(originalSubstring);
    }

    /** The {@code faultcode}, resolved against its prefix, and {@code faultstring} of a raw SOAP 1.1 fault. */
    private static Fault rawFault(HttpTestClient.Response response) throws Exception {
        Element fault = single(parse(response.body()), SOAP11_NS, "Fault");
        Element code = unqualifiedChild(fault, "faultcode");
        Element text = unqualifiedChild(fault, "faultstring");
        String value = code.getTextContent().trim();
        int colon = value.indexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        return new Fault(new QName(code.lookupNamespaceURI(prefix), value.substring(colon + 1)),
                text.getTextContent());
    }

    private static Element unqualifiedChild(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && child.getNamespaceURI() == null
                    && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        throw new AssertionError("<" + parent.getLocalName() + "> has no unqualified <" + localName + ">");
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static int count(Document document, String namespace, String localName) {
        return document.getElementsByTagNameNS(namespace, localName).getLength();
    }

    private static Element single(Document document, String namespace, String localName) {
        NodeList matches = document.getElementsByTagNameNS(namespace, localName);
        assertThat(matches.getLength()).as("number of {%s}%s elements", namespace, localName).isEqualTo(1);
        return (Element) matches.item(0);
    }

    /** The qualified name of the first element under the SOAP 1.1 {@code Body}. */
    private static QName bodyRoot(Document envelope) {
        Element body = single(envelope, SOAP11_NS, "Body");
        for (Node child = body.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return new QName(child.getNamespaceURI(), child.getLocalName());
            }
        }
        throw new AssertionError("empty SOAP Body");
    }

    /** A SOAP 1.1 fault as read from the wire. */
    private record Fault(QName code, String text) {
    }

    /**
     * Records the request as sent, which is the secured message when this interceptor follows the securing one, and
     * the HTTP status of the response or fault the connection received.
     */
    private static final class Capture implements ClientInterceptor {

        private byte[] requestBytes;

        private int status = -1;

        Document request() throws Exception {
            assertThat(requestBytes).as("captured request").isNotNull();
            return parse(requestBytes);
        }

        @Override
        public boolean handleRequest(MessageContext messageContext) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                messageContext.getRequest().writeTo(bytes);
            }
            catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            requestBytes = bytes.toByteArray();
            return true;
        }

        @Override
        public boolean handleResponse(MessageContext messageContext) {
            recordStatus();
            return true;
        }

        @Override
        public boolean handleFault(MessageContext messageContext) {
            recordStatus();
            return true;
        }

        @Override
        public void afterCompletion(MessageContext messageContext, Exception ex) {
            // Nothing to release: the status is read while the connection is still open.
        }

        private void recordStatus() {
            TransportContext context = TransportContextHolder.getTransportContext();
            if (context != null && context.getConnection() instanceof HttpUrlConnection connection) {
                try {
                    status = connection.getConnection().getResponseCode();
                }
                catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
    }
}
