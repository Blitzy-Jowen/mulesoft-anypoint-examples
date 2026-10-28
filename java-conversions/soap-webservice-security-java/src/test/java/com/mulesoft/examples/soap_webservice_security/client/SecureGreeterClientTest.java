/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.xml.SimpleNamespaceContext;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.soap_webservice_security.SoapWebserviceSecurityApplication;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient.OutboundSecurity;
import com.mulesoft.examples.soap_webservice_security.config.SoapSecurityProperties;
import com.mulesoft.examples.soap_webservice_security.config.WrongPasswordCallback;

/**
 * Unit test of {@link SecureGreeterClient}, the replacement of the original CXF {@code SecureClient}, the six
 * {@code cxf:jaxws-client} + {@code http:request} sub-flows and the {@code SecurityClients} choice of
 * {@code service-clients.xml}.
 *
 * <p>The Greeter service is a WireMock stub, so every request the client sends is captured and its WS-Security header
 * inspected on the wire: the security headers come from the real {@code merlinCrypto}, {@code passwordCallback},
 * {@code samlCallbackHandler}, message factory and marshaller beans, configured with the demo values of the
 * {@code test} profile. The context uses the default mock web environment, so no listener (63081, 63080) is bound;
 * {@code soap-security.client.base-url} points the client at the stub.
 */
@SpringBootTest(classes = SoapWebserviceSecurityApplication.class)
@ActiveProfiles("test")
class SecureGreeterClientTest {

    private static final String SERVICES = "/services/";

    private static final String NAME = "Mule";

    private static final String GREETING = "Hello Mule";

    /** The user every secured client type of the original sends (service-clients.xml:50,63,76,103). */
    private static final String CLIENT_USER = "joe";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String SENDER_VOUCHES = "urn:oasis:names:tc:SAML:2.0:cm:sender-vouches";

    private static final String SECURITY = "/soap:Envelope/soap:Header/wsse:Security";

    private static final String GREET_NAME = "/soap:Envelope/soap:Body/g:greet/name";

    private static final String GREET_RESPONSE = "<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Body>"
            + "<ns2:greetResponse xmlns:ns2=\"http://security.example.mule.mulesoft.com/\"><name>" + GREETING
            + "</name></ns2:greetResponse></soap:Body></soap:Envelope>";

    private static final Map<String, String> NAMESPACES = namespaces();

    @RegisterExtension
    static final WireMockExtension WIREMOCK = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    /** The client derives each Greeter URL as {@code <base-url>/<path>}, so the base URL carries {@code /services}. */
    @DynamicPropertySource
    static void greeterService(DynamicPropertyRegistry registry) {
        registry.add("soap-security.client.base-url", () -> WIREMOCK.baseUrl() + "/services");
    }

    @Autowired
    private SecureGreeterClient client;

    @Autowired
    private SoapSecurityProperties properties;

    @BeforeEach
    void stubGreeterService() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlPathMatching("/services/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(GREET_RESPONSE)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SecurityClients choice and the otherwise literal
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> /services/{1}")
    @CsvSource({
            "unsecure,               unsecure",
            "usernameToken,          username",
            "usernameTokenSigned,    signed",
            "usernameTokenEncrypted, encrypted",
            "samlToken,              saml",
            "samlTokenSigned,        signedsaml"
    })
    void invokeSendsOneSoap11RequestToTheClientTypesPath(String clientType, String path) {
        assertThat(client.invoke(clientType, NAME)).isEqualTo(GREETING);

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + path)));
        LoggedRequest request = singleRequest();
        assertThat(request.getHeader("Content-Type")).contains("text/xml");
        // The CXF clients sent an empty SOAPAction on every client type.
        assertThat(request.getHeader("SOAPAction")).isEqualTo("\"\"");
    }

    @Test
    void eachSubFlowMethodCallsItsOwnPath() {
        Map<String, Function<String, String>> subFlows = new LinkedHashMap<>();
        subFlows.put(SecureGreeterClient.PATH_UNSECURE, client::unsecure);
        subFlows.put(SecureGreeterClient.PATH_USERNAME, client::usernameToken);
        subFlows.put(SecureGreeterClient.PATH_SIGNED, client::usernameTokenSigned);
        subFlows.put(SecureGreeterClient.PATH_ENCRYPTED, client::usernameTokenEncrypted);
        subFlows.put(SecureGreeterClient.PATH_SAML, client::samlToken);
        subFlows.put(SecureGreeterClient.PATH_SIGNED_SAML, client::samlTokenSigned);

        subFlows.forEach((path, subFlow) -> assertThat(subFlow.apply(NAME)).as(path).isEqualTo(GREETING));

        subFlows.keySet().forEach(path -> WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + path))));
        assertThat(WIREMOCK.getAllServeEvents()).hasSize(subFlows.size());
    }

    @ParameterizedTest(name = "[{0}]")
    @NullSource
    @ValueSource(strings = { "foo", "", "UsernameToken", "UNSECURE", "samltoken", " unsecure" })
    void unknownClientTypeAnswersTheOtherwiseLiteralWithoutAnyRequest(String clientType) {
        assertThat(client.invoke(clientType, NAME)).isEqualTo(SecureGreeterClient.UNSUPPORTED_CLIENT_TYPE);

        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void otherwiseLiteralIsTheOriginalPayload() {
        assertThat(SecureGreeterClient.UNSUPPORTED_CLIENT_TYPE).isEqualTo("Client type is not supported");
    }

    @ParameterizedTest
    @ValueSource(strings = { "unsecure", "username", "signed", "encrypted", "saml", "signedsaml" })
    void endpointAppendsThePathToTheConfiguredServicesUrl(String path) {
        URI expected = URI.create(WIREMOCK.baseUrl() + SERVICES + path);

        assertThat(client.endpoint(path)).isEqualTo(expected);
        assertThat(client.endpoint("/" + path)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Security header of each client type, as captured on the wire
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unsecureSendsNoSecurityHeader() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME);

        Document envelope = lastEnvelope();
        assertThat(count(envelope, SECURITY)).isZero();
        assertThat(text(envelope, GREET_NAME)).isEqualTo(NAME);
    }

    @Test
    void usernameTokenSendsADigestTokenAndATimestamp() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, NAME);

        Document envelope = lastEnvelope();
        assertUsernameTokenDigest(envelope, configuredPassword());
        assertTimestamp(envelope);
        assertThat(count(envelope, "//ds:Signature")).isZero();
        assertThat(count(envelope, "//xenc:EncryptedKey")).isZero();
        assertThat(text(envelope, GREET_NAME)).isEqualTo(NAME);
    }

    @Test
    void usernameTokenSignedAlsoSignsTheBody() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_SIGNED, NAME);

        Document envelope = lastEnvelope();
        assertUsernameTokenDigest(envelope, configuredPassword());
        assertTimestamp(envelope);
        assertThat(count(envelope, SECURITY + "/ds:Signature")).isEqualTo(1);
        assertSignatureCoversTheBody(envelope);
        assertThat(count(envelope, "//xenc:EncryptedKey")).isZero();
        assertThat(text(envelope, GREET_NAME)).isEqualTo(NAME);
    }

    @Test
    void usernameTokenEncryptedEncryptsTheBodyForTheEncryptionUser() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED, NAME);

        String body = lastRequestBody();
        Document envelope = parse(body);
        assertThat(count(envelope, SECURITY + "/xenc:EncryptedKey")).isEqualTo(1);
        assertThat(count(envelope, "/soap:Envelope/soap:Body/xenc:EncryptedData")).isEqualTo(1);
        assertThat(count(envelope, SECURITY + "/xenc:EncryptedKey/xenc:ReferenceList/xenc:DataReference"
                + "[@URI = concat('#', /soap:Envelope/soap:Body/xenc:EncryptedData/@Id)]")).isEqualTo(1);
        assertThat(count(envelope, "//g:greet")).isZero();
        assertThat(body).doesNotContain(">" + NAME + "<");
        assertUsernameTokenDigest(envelope, configuredPassword());
        assertTimestamp(envelope);
        assertThat(count(envelope, "//ds:Signature")).isZero();
    }

    @Test
    void samlTokenSendsAnUnsignedSenderVouchesAssertionAndATimestamp() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN, NAME);

        Document envelope = lastEnvelope();
        assertSamlAssertion(envelope, "self");
        assertThat(count(envelope, SECURITY + "/saml2:Assertion/saml2:AuthnStatement")).isEqualTo(1);
        assertThat(text(envelope, SECURITY + "/saml2:Assertion/saml2:AuthnStatement/saml2:AuthnContext"
                + "/saml2:AuthnContextClassRef")).endsWith("Password");
        assertTimestamp(envelope);
        assertThat(count(envelope, "//ds:Signature")).isZero();
        assertThat(count(envelope, "//wsse:UsernameToken")).isZero();
        assertThat(text(envelope, GREET_NAME)).isEqualTo(NAME);
    }

    @Test
    void samlTokenSignedSignsTheMessageWithADirectReferenceToken() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN_SIGNED, NAME);

        assertSignedSamlRequest(lastEnvelope());
    }

    @Test
    void everyCallOfAMemoizedClientIsSecuredAnew() throws Exception {
        client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, NAME);
        client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, NAME);

        List<LoggedRequest> requests =
                WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_USERNAME)));
        assertThat(requests).hasSize(2);
        List<String> nonces = new ArrayList<>();
        for (LoggedRequest request : requests) {
            nonces.add(text(parse(request.getBodyAsString()), SECURITY + "/wsse:UsernameToken/wsse:Nonce"));
        }
        assertThat(nonces).doesNotContain("").doesNotHaveDuplicates();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Profile factories: the request shapes of the original negative tests (SoapWebserviceSecurityIT)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void usernameTokenProfileOnTheSignedPathCarriesNoSignature() throws Exception {
        String reply = client.greet(client.endpoint(SecureGreeterClient.PATH_SIGNED),
                client.getUsernameTokenProps(SecureGreeterClient.USERNAME_TOKEN_TIMESTAMP), NAME);

        assertThat(reply).isEqualTo(GREETING);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_SIGNED)));
        Document envelope = lastEnvelope();
        assertUsernameTokenDigest(envelope, configuredPassword());
        assertTimestamp(envelope);
        assertThat(count(envelope, "//ds:Signature")).isZero();
    }

    @Test
    void samlProfileForWrongSamlPropertiesCarriesTheWrongIssuer() throws Exception {
        String reply = client.greet(client.endpoint(SecureGreeterClient.PATH_SAML),
                client.getSamlTokenProps(SecureGreeterClient.SAML_TOKEN_UNSIGNED_TIMESTAMP, "wrong-saml.properties"),
                NAME);

        assertThat(reply).isEqualTo(GREETING);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_SAML)));
        Document envelope = lastEnvelope();
        assertSamlAssertion(envelope, "www.example.com");
        assertTimestamp(envelope);
    }

    @Test
    void signedSamlProfileMatchesTheSamlTokenSignedClient() throws Exception {
        String reply = client.greet(client.endpoint(SecureGreeterClient.PATH_SIGNED_SAML),
                client.getSignedSamlTokenProps(SecureGreeterClient.SAML_TOKEN_SIGNED, "saml.properties"), NAME);

        assertThat(reply).isEqualTo(GREETING);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_SIGNED_SAML)));
        assertSignedSamlRequest(lastEnvelope());
    }

    @Test
    void wrongPasswordCallbackDigestsTheWrongPassword() throws Exception {
        WrongPasswordCallback wrongPasswordCallback = new WrongPasswordCallback();
        OutboundSecurity wrongPassword = client.getUsernameTokenProps(SecureGreeterClient.USERNAME_TOKEN_TIMESTAMP)
                .withPasswordCallback(wrongPasswordCallback);

        client.greet(client.endpoint(SecureGreeterClient.PATH_USERNAME), wrongPassword, NAME);

        String wrong = passwordOf(wrongPasswordCallback, CLIENT_USER);
        assertThat(wrong).isEqualTo("hacker").isNotEqualTo(configuredPassword());
        Document envelope = lastEnvelope();
        assertUsernameTokenDigest(envelope, wrong);
        assertThat(text(envelope, SECURITY + "/wsse:UsernameToken/wsse:Password"))
                .isNotEqualTo(digestOf(envelope, configuredPassword()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Failures propagate: the client catches nothing (the catch branch is GreeterClientExceptionHandler)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void soapFaultOfTheUnsecurePathPropagates() {
        WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_UNSECURE)).atPriority(1)
                .willReturn(fault("soap:Server", "", "boom")));

        SoapFaultClientException fault = assertThrows(SoapFaultClientException.class,
                () -> client.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME));

        assertThat(fault.getFaultStringOrReason()).isEqualTo("boom");
        assertThat(fault.getFaultCode()).isEqualTo(new QName(SOAP_NS, "Server"));
    }

    @Test
    void securityFaultOfASecuredPathPropagates() {
        WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_USERNAME)).atPriority(1)
                .willReturn(fault("wsse:FailedAuthentication", " xmlns:wsse=\"" + WSSE_NS + "\"",
                        "The security token could not be authenticated or authorized")));

        SoapFaultClientException fault = assertThrows(SoapFaultClientException.class,
                () -> client.invoke(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, NAME));

        assertThat(fault.getMessage()).contains("security token");
        assertThat(fault.getFaultCode()).isEqualTo(new QName(WSSE_NS, "FailedAuthentication"));
    }

    @Test
    void unreachableServiceRaisesWebServiceIOException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        URI unreachable = URI.create("http://localhost:" + closedPort + SERVICES + SecureGreeterClient.PATH_UNSECURE);

        WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                () -> client.greet(unreachable, OutboundSecurity.NONE, NAME));

        assertThat(causes(failure)).anyMatch(ConnectException.class::isInstance);
        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    /** A context of its own whose client gives up reading after {@code soap-security.client.response-timeout}. */
    @Nested
    @TestPropertySource(properties = "soap-security.client.response-timeout=500")
    class ResponseTimeout {

        @Autowired
        private SecureGreeterClient timedClient;

        @Test
        void slowServiceRaisesWebServiceIOExceptionAfterTheResponseTimeout() {
            WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + SecureGreeterClient.PATH_UNSECURE)).atPriority(1)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/xml;charset=UTF-8")
                            .withBody(GREET_RESPONSE)
                            .withFixedDelay(3000)));

            long started = System.nanoTime();
            WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                    () -> timedClient.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(causes(failure)).anyMatch(SocketTimeoutException.class::isInstance);
            assertThat(elapsedMillis).isLessThan(2500);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Assertions shared by several client types
    // ---------------------------------------------------------------------------------------------------------------

    /** A {@code PasswordDigest} UsernameToken of the client user whose digest was computed from {@code password}. */
    private static void assertUsernameTokenDigest(Document envelope, String password) throws Exception {
        String token = SECURITY + "/wsse:UsernameToken";
        assertThat(count(envelope, token)).isEqualTo(1);
        assertThat(text(envelope, token + "/wsse:Username")).isEqualTo(CLIENT_USER);
        assertThat(text(envelope, token + "/wsse:Password/@Type")).endsWith("#PasswordDigest");
        assertThat(text(envelope, token + "/wsse:Nonce")).isNotBlank();
        assertThat(text(envelope, token + "/wsu:Created")).isNotBlank();
        assertThat(text(envelope, token + "/wsse:Password")).isEqualTo(digestOf(envelope, password));
    }

    private static void assertTimestamp(Document envelope) throws XPathExpressionException {
        String timestamp = SECURITY + "/wsu:Timestamp";
        assertThat(count(envelope, timestamp)).isEqualTo(1);
        assertThat(text(envelope, timestamp + "/wsu:Created")).isNotBlank();
        assertThat(text(envelope, timestamp + "/wsu:Expires")).isNotBlank();
    }

    /** One signature reference points at the {@code wsu:Id} of the SOAP body. */
    private static void assertSignatureCoversTheBody(Document envelope) throws XPathExpressionException {
        assertThat(text(envelope, "/soap:Envelope/soap:Body/@wsu:Id")).isNotBlank();
        assertThat(count(envelope, SECURITY + "/ds:Signature/ds:SignedInfo/ds:Reference"
                + "[@URI = concat('#', /soap:Envelope/soap:Body/@wsu:Id)]")).isEqualTo(1);
    }

    /** The assertion {@code SAMLCallbackHandler} builds, issued by {@code issuer}. */
    private static void assertSamlAssertion(Document envelope, String issuer) throws XPathExpressionException {
        String assertion = SECURITY + "/saml2:Assertion";
        assertThat(count(envelope, assertion + "[@Version = '2.0']")).isEqualTo(1);
        assertThat(text(envelope, assertion + "/saml2:Issuer")).isEqualTo(issuer);
        assertThat(text(envelope, assertion + "/saml2:Subject/saml2:NameID")).isEqualTo("AllowGreetingServices");
        assertThat(text(envelope, assertion + "/saml2:Subject/saml2:NameID/@NameQualifier"))
                .isEqualTo("www.example.com");
        assertThat(text(envelope, assertion + "/saml2:Subject/saml2:SubjectConfirmation/@Method"))
                .isEqualTo(SENDER_VOUCHES);
    }

    /**
     * {@code SAMLTokenSigned} with {@code DirectReference}: the unsigned sender-vouches assertion, a message signature
     * whose key is the X.509 {@code BinarySecurityToken} it references directly, and no Timestamp.
     */
    private static void assertSignedSamlRequest(Document envelope) throws XPathExpressionException {
        assertSamlAssertion(envelope, "self");
        assertThat(count(envelope, SECURITY + "/ds:Signature")).isEqualTo(1);
        assertThat(count(envelope, SECURITY + "/wsse:BinarySecurityToken")).isEqualTo(1);
        assertThat(text(envelope, SECURITY + "/wsse:BinarySecurityToken/@ValueType")).endsWith("#X509v3");
        String tokenId = text(envelope, SECURITY + "/wsse:BinarySecurityToken/@wsu:Id");
        assertThat(tokenId).isNotBlank();
        assertThat(text(envelope,
                SECURITY + "/ds:Signature/ds:KeyInfo/wsse:SecurityTokenReference/wsse:Reference/@URI"))
                .isEqualTo("#" + tokenId);
        assertSignatureCoversTheBody(envelope);
        // Sender-vouches: the message signature also covers the assertion, through a token reference to its ID.
        assertThat(count(envelope, SECURITY + "/ds:Signature/ds:SignedInfo/ds:Reference[@URI = concat('#', "
                + SECURITY + "/wsse:SecurityTokenReference[wsse:KeyIdentifier = " + SECURITY
                + "/saml2:Assertion/@ID]/@wsu:Id)]")).isEqualTo(1);
        assertThat(count(envelope, "//wsu:Timestamp")).isZero();
        assertThat(count(envelope, "//saml2:Assertion/ds:Signature")).isZero();
        assertThat(text(envelope, GREET_NAME)).isEqualTo(NAME);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** The client user's password of the {@code test} profile ({@code soap-security.users.joe}). */
    private String configuredPassword() {
        return properties.passwordFor(CLIENT_USER)
                .orElseThrow(() -> new IllegalStateException("soap-security.users.joe is not configured"));
    }

    /** The password {@code handler} supplies for a UsernameToken of {@code user}. */
    private static String passwordOf(CallbackHandler handler, String user) throws Exception {
        WSPasswordCallback callback = new WSPasswordCallback(user, WSPasswordCallback.USERNAME_TOKEN);
        handler.handle(new Callback[] { callback });
        return callback.getPassword();
    }

    /** {@code Base64(SHA-1(nonce + created + password))}, the WS-Security UsernameToken Profile 1.0 digest. */
    private static String digestOf(Document envelope, String password)
            throws XPathExpressionException, NoSuchAlgorithmException {
        String token = SECURITY + "/wsse:UsernameToken";
        byte[] nonce = Base64.getMimeDecoder().decode(text(envelope, token + "/wsse:Nonce"));
        String created = text(envelope, token + "/wsu:Created");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(nonce);
        sha1.update(created.getBytes(StandardCharsets.UTF_8));
        sha1.update(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sha1.digest());
    }

    private static ResponseDefinitionBuilder fault(String faultCode, String extraNamespaces, String faultString) {
        return aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody("<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"" + extraNamespaces + "><soap:Body>"
                        + "<soap:Fault><faultcode>" + faultCode + "</faultcode><faultstring>" + faultString
                        + "</faultstring></soap:Fault></soap:Body></soap:Envelope>");
    }

    /** The only request the stub received. */
    private static LoggedRequest singleRequest() {
        List<ServeEvent> events = WIREMOCK.getAllServeEvents();
        assertThat(events).hasSize(1);
        return events.get(0).getRequest();
    }

    private static String lastRequestBody() {
        return singleRequest().getBodyAsString();
    }

    private static Document lastEnvelope() throws Exception {
        return parse(lastRequestBody());
    }

    /** Namespace-aware parsing with DOCTYPEs and external entities refused. */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String text(Document document, String expression) throws XPathExpressionException {
        return xpath().evaluate(expression, document);
    }

    private static int count(Document document, String expression) throws XPathExpressionException {
        Double value = (Double) xpath().evaluate("count(" + expression + ")", document, XPathConstants.NUMBER);
        return value.intValue();
    }

    private static XPath xpath() {
        SimpleNamespaceContext context = new SimpleNamespaceContext();
        context.setBindings(NAMESPACES);
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(context);
        return xpath;
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static Map<String, String> namespaces() {
        Map<String, String> namespaces = new LinkedHashMap<>();
        namespaces.put("soap", SOAP_NS);
        namespaces.put("wsse", WSSE_NS);
        namespaces.put("wsu", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd");
        namespaces.put("ds", "http://www.w3.org/2000/09/xmldsig#");
        namespaces.put("xenc", "http://www.w3.org/2001/04/xmlenc#");
        namespaces.put("saml2", "urn:oasis:names:tc:SAML:2.0:assertion");
        namespaces.put("g", "http://security.example.mule.mulesoft.com/");
        return namespaces;
    }
}
