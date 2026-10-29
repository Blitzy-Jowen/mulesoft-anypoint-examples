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
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.oxm.UnmarshallingFailureException;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.xml.SimpleNamespaceContext;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.soap_webservice_security.SoapWebserviceSecurityApplication;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient.OutboundSecurity;
import com.mulesoft.examples.soap_webservice_security.config.PasswordCallback;
import com.mulesoft.examples.soap_webservice_security.config.SAMLCallbackHandler;
import com.mulesoft.examples.soap_webservice_security.config.SoapSecurityProperties;
import com.mulesoft.examples.soap_webservice_security.config.WebServiceConfig;
import com.mulesoft.examples.soap_webservice_security.config.WrongPasswordCallback;
import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;

/**
 * Unit test of {@link SecureGreeterClient}, the replacement of the original CXF {@code SecureClient}, the six
 * {@code cxf:jaxws-client} + {@code http:request} sub-flows and the {@code SecurityClients} choice of
 * {@code service-clients.xml}.
 *
 * <p>The Greeter service is a WireMock stub, so every request the client sends is captured and its WS-Security header
 * inspected on the wire: the security headers come from the real {@code merlinCrypto}, {@code passwordCallback},
 * {@code samlCallbackHandler}, message factory and marshaller beans, configured with the demo values of the
 * {@code test} profile. The context uses the default mock web environment, so no listener (63081, 63080) is bound;
 * {@code soap-security.client.base-url} points the client at the stub. The nested {@link UnderRootTrace} run covers
 * the logger levels {@code application.yml} pins for the client's side of each call.
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

    /** The request element {@code greet}, which the Greeter marshaller binds to {@code Greet}. */
    private static final String GREET_PAYLOAD = "<ns2:greet xmlns:ns2=\"http://security.example.mule.mulesoft.com/\">"
            + "<name>" + NAME + "</name></ns2:greet>";

    /** An element the Greeter marshaller binds to no class. */
    private static final String UNBOUND_PAYLOAD = "<x:other xmlns:x=\"urn:other\"/>";

    private static final Map<String, String> NAMESPACES = namespaces();

    /** Milliseconds; the Mule default response timeout that {@code soap-security.client.response-timeout} keeps. */
    private static final int MULE_DEFAULT_RESPONSE_TIMEOUT = 10000;

    private static final String NON_POSITIVE_RESPONSE_TIMEOUT =
            "soap-security.client.response-timeout must be a positive number of milliseconds: ";

    /** Binds {@code soap-security.*} alone, as the application does at start-up. */
    private static final ApplicationContextRunner PROPERTIES_BINDING =
            new ApplicationContextRunner().withUserConfiguration(PropertiesBinding.class);

    /**
     * The WireMock Greeter service, on a dynamic port. It is started and stopped by this class's own
     * {@code @BeforeAll} and {@code @AfterAll}, which JUnit runs for the outer class container alone. A
     * {@code WireMockExtension} registered here would be inherited by {@link ResponseTimeout} and
     * {@link UnderRootTrace} and would stop the server when either nested container ends, so every outer test running
     * after it, as when the nested classes are selected first, would call a closed port.
     */
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    /** The client derives each Greeter URL as {@code <base-url>/<path>}, so the base URL carries {@code /services}. */
    @DynamicPropertySource
    static void greeterService(DynamicPropertyRegistry registry) {
        registry.add("soap-security.client.base-url", () -> WIREMOCK.baseUrl() + "/services");
    }

    @BeforeAll
    static void startGreeterService() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopGreeterService() {
        WIREMOCK.stop();
    }

    @Autowired
    private SecureGreeterClient client;

    @Autowired
    private SoapSecurityProperties properties;

    @Autowired
    @Qualifier("merlinCrypto")
    private Supplier<Crypto> merlinCrypto;

    @Autowired
    private PasswordCallback passwordCallback;

    @Autowired
    private SAMLCallbackHandler samlCallbackHandler;

    @Autowired
    private ObjectProvider<SaajSoapMessageFactory> messageFactory;

    @Autowired
    @Qualifier(WebServiceConfig.GREETER_MARSHALLER_BEAN_NAME)
    private ObjectProvider<Jaxb2Marshaller> marshaller;

    @Autowired
    private Environment environment;

    @BeforeEach
    void stubGreeterService() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlPathMatching("/services/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(GREET_RESPONSE)));
    }

    // SecurityClients choice and the otherwise literal

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
        // One fixed-length body: Content-Length is the envelope's byte count and nothing is chunked.
        assertThat(request.getHeader("Content-Length")).isEqualTo(String.valueOf(request.getBody().length));
        assertThat(request.containsHeader("Transfer-Encoding")).isFalse();
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

    // Security header of each client type, as captured on the wire

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

    /** The original token {@code Encrypt} and WSS4J's {@code Encryption} both encrypt with the configured crypto. */
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = { "UsernameToken Timestamp Encrypt", "UsernameToken Timestamp Encryption" })
    void eitherEncryptionTokenEncryptsTheBody(String actions) throws Exception {
        String reply = client.greet(client.endpoint(SecureGreeterClient.PATH_ENCRYPTED),
                client.getUsernameTokenProps(actions), NAME);

        assertThat(reply).isEqualTo(GREETING);
        String body = lastRequestBody();
        Document envelope = parse(body);
        assertThat(count(envelope, SECURITY + "/xenc:EncryptedKey")).isEqualTo(1);
        assertThat(count(envelope, "/soap:Envelope/soap:Body/xenc:EncryptedData")).isEqualTo(1);
        assertThat(body).doesNotContain(">" + NAME + "<");
        assertUsernameTokenDigest(envelope, configuredPassword());
        assertTimestamp(envelope);
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

    // Profile factories: the request shapes of the original negative tests (SoapWebserviceSecurityIT)

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

    // Failures propagate: the client catches nothing (the catch branch is GreeterClientExceptionHandler)

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
        // A port held by a socket that never listens, so no other process can serve it during the call.
        try (Socket reserved = reserveRefusingPort()) {
            URI unreachable = URI.create(
                    "http://localhost:" + reserved.getLocalPort() + SERVICES + SecureGreeterClient.PATH_UNSECURE);

            WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                    () -> client.greet(unreachable, OutboundSecurity.NONE, NAME));

            assertThat(causes(failure)).anyMatch(ConnectException.class::isInstance);
            WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    /**
     * The service closes or resets the connection after reading the request and before any response byte. A
     * buffered JDK connection would silently send the POST again; every client type sends it exactly once and the
     * connectivity failure reaches the caller.
     */
    @ParameterizedTest(name = "{0} -> /services/{1}, {2}")
    @CsvSource({
            "unsecure,               unsecure,   EMPTY_RESPONSE",
            "unsecure,               unsecure,   CONNECTION_RESET_BY_PEER",
            "usernameToken,          username,   EMPTY_RESPONSE",
            "usernameToken,          username,   CONNECTION_RESET_BY_PEER",
            "usernameTokenSigned,    signed,     EMPTY_RESPONSE",
            "usernameTokenSigned,    signed,     CONNECTION_RESET_BY_PEER",
            "usernameTokenEncrypted, encrypted,  EMPTY_RESPONSE",
            "usernameTokenEncrypted, encrypted,  CONNECTION_RESET_BY_PEER",
            "samlToken,              saml,       EMPTY_RESPONSE",
            "samlToken,              saml,       CONNECTION_RESET_BY_PEER",
            "samlTokenSigned,        signedsaml, EMPTY_RESPONSE",
            "samlTokenSigned,        signedsaml, CONNECTION_RESET_BY_PEER"
    })
    void connectionLostBeforeTheResponseRaisesWebServiceIOExceptionAfterExactlyOneRequest(String clientType,
            String path, Fault fault) {
        WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + path)).atPriority(1)
                .willReturn(aResponse().withFault(fault)));

        WebServiceIOException failure = assertThrows(WebServiceIOException.class,
                () -> client.invoke(clientType, NAME));

        assertThat(causes(failure)).anyMatch(SocketException.class::isInstance);
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + path)));
        WIREMOCK.verify(1, anyRequestedFor(anyUrl()));
    }

    /**
     * The service answers 200 without a {@code greetResponse}. Spring WS reads a reply without an entity or with an
     * empty SOAP Body as no payload, and the Greeter marshaller reads a {@code greet} element as {@code Greet}; the
     * client accepts only {@code GreetResponse}, so each raises {@link IllegalStateException} naming what the service
     * answered with. Responses are not validated, so every client type fails alike, after exactly one request.
     */
    @ParameterizedTest(name = "{0} -> /services/{1}, {2}")
    @MethodSource("repliesWithoutGreetResponseOfEveryClientType")
    void replyWithoutGreetResponseRaisesIllegalStateExceptionAfterExactlyOneRequest(String clientType, String path,
            ReplyWithoutGreetResponse reply) {
        WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + path)).atPriority(1).willReturn(reply.response().get()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.invoke(clientType, NAME));

        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("The Greeter service at " + WIREMOCK.baseUrl() + SERVICES + path
                        + " answered with " + reply.answered() + " instead of greetResponse");
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + path)));
        WIREMOCK.verify(1, anyRequestedFor(anyUrl()));
    }

    /**
     * The service answers 200 with a SOAP Body element the Greeter marshaller binds to no class: the reply fails to
     * unmarshal before the client sees a payload, and the marshaller's failure propagates after exactly one request.
     */
    @ParameterizedTest(name = "{0} -> /services/{1}")
    @MethodSource("clientTypesAndPaths")
    void payloadTheGreeterMarshallerDoesNotBindFailsToUnmarshalAfterExactlyOneRequest(String clientType,
            String path) {
        WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + path)).atPriority(1)
                .willReturn(soapReply(UNBOUND_PAYLOAD)));

        UnmarshallingFailureException failure = assertThrows(UnmarshallingFailureException.class,
                () -> client.invoke(clientType, NAME));

        assertThat(failure).isExactlyInstanceOf(UnmarshallingFailureException.class)
                .hasMessage("JAXB unmarshalling exception");
        assertThat(causes(failure)).anyMatch(cause -> String.valueOf(cause.getMessage())
                .contains("unexpected element (uri:\"urn:other\", local:\"other\")"));
        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(SERVICES + path)));
        WIREMOCK.verify(1, anyRequestedFor(anyUrl()));
    }

    /** Every client type with its Greeter path, in the order of the original choice. */
    static Stream<Arguments> clientTypesAndPaths() {
        return Stream.of(
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_UNSECURE, SecureGreeterClient.PATH_UNSECURE),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, SecureGreeterClient.PATH_USERNAME),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_SIGNED, SecureGreeterClient.PATH_SIGNED),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED,
                        SecureGreeterClient.PATH_ENCRYPTED),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN, SecureGreeterClient.PATH_SAML),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN_SIGNED, SecureGreeterClient.PATH_SIGNED_SAML));
    }

    /** Each {@link ReplyWithoutGreetResponse} for each client type and its Greeter path. */
    static Stream<Arguments> repliesWithoutGreetResponseOfEveryClientType() {
        return clientTypesAndPaths().flatMap(typeAndPath -> repliesWithoutGreetResponse()
                .map(reply -> Arguments.of(typeAndPath.get()[0], typeAndPath.get()[1], reply)));
    }

    static Stream<ReplyWithoutGreetResponse> repliesWithoutGreetResponse() {
        return Stream.of(
                // The explicit Content-Length keeps WireMock from answering the client's Accept-Encoding: gzip with
                // a gzip entity of an empty document, which is not a reply without an entity.
                new ReplyWithoutGreetResponse("no entity, Content-Length 0",
                        () -> aResponse().withStatus(200).withHeader("Content-Length", "0"), "no payload"),
                new ReplyWithoutGreetResponse("empty SOAP Body", () -> soapReply(""), "no payload"),
                new ReplyWithoutGreetResponse("greet element", () -> soapReply(GREET_PAYLOAD), Greet.class.getName()));
    }

    /**
     * A 200 reply without a {@code greetResponse}.
     *
     * @param name     the case's display name
     * @param response builds the stub's response
     * @param answered what the client's failure names in place of {@code greetResponse}
     */
    record ReplyWithoutGreetResponse(String name, Supplier<ResponseDefinitionBuilder> response, String answered) {

        @Override
        public String toString() {
            return name;
        }
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

    // Logger levels: no security header, secret or greeted name in the log under a root TRACE level

    /** The client calls of {@link LoggedClientCalls} under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    @DirtiesContext
    class UnderRootTrace extends LoggedClientCalls {
    }

    /**
     * Tests the logger levels {@code application.yml} pins, which keep the WS-Security header the client sends, the
     * configured secrets and the plaintext that signing and encryption cover out of the log under a root {@code TRACE}
     * level: {@code service-clients.xml} declares no logger.
     *
     * <p>The nested run keeps this class's configuration, the WireMock Greeter service included, in a context of its
     * own whose client user is {@link #SENTINEL_USER} with the password {@link #SENTINEL_PASSWORD}, a user that only
     * sends UsernameTokens; the signing profiles keep the original user {@code joe}, whose password is also his
     * private-key password. Each call, the one the stub answers with a security fault included, is asserted to have
     * sent its security header, then its own log events are asserted to hold the unpinned {@code WebServiceTemplate}
     * {@code DEBUG} connection line and the JDK HTTP client's {@code TRACE} proxy line, which prove the capture sees
     * those levels, and no {@code WARN} of Santuario's {@code DOMReference}, and the console output is asserted to hold
     * no serialized security-header element, no sentinel and not the greeted name. The run restores the logger levels
     * it found and closes its context, so later test classes do not run at {@code TRACE}.
     */
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedClientCalls {

        /** A UsernameToken user configured for this run alone, and its password; neither may reach the log. */
        static final String SENTINEL_USER = "sentinelutuser";
        static final String SENTINEL_PASSWORD = "SENTINEL_UT_PASSWORD";

        /** The name every call greets: it travels in the signed or encrypted body. */
        static final String SENTINEL_NAME = "SentinelGreetedName";

        /** The start tags of the security-header elements WSS4J writes, which only a serialized envelope holds. */
        static final List<String> SECURITY_HEADER_ELEMENTS = List.of("<wsse:Security", "<wsse:UsernameToken",
                "<wsse:Password", "<wsse:Nonce", "<wsse:BinarySecurityToken", "<saml2:Assertion",
                "<ds:SignatureValue", "<xenc:CipherValue");

        /** The JDK HTTP client's logger, whose proxy line each new request connection writes at {@code TRACE}. */
        private static final String JDK_HTTP_LOGGER = "sun.net.www.protocol.http.HttpURLConnection";

        /** Santuario's reference logger, whose null-digest-input WARN a signed assertion's STR-Transform raises. */
        private static final String DOM_REFERENCE_LOGGER = "org.apache.jcp.xml.dsig.internal.dom.DOMReference";

        /** How long lines written after a call get to arrive before the output is scanned. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @Autowired
        SecureGreeterClient client;

        @Autowired
        SoapSecurityProperties properties;

        @Autowired
        @Qualifier("passwordCallback")
        PasswordCallback passwordCallback;

        @Autowired
        @Qualifier("samlCallbackHandler")
        SAMLCallbackHandler samlCallbackHandler;

        @DynamicPropertySource
        static void sentinelUser(DynamicPropertyRegistry registry) {
            registry.add("soap-security.users." + SENTINEL_USER, () -> SENTINEL_PASSWORD);
            registry.add("soap-security.client.user", () -> SENTINEL_USER);
        }

        @BeforeAll
        static void recordLevels() {
            LEVELS_BEFORE.clear();
            for (Logger logger : loggerContext().getLoggerList()) {
                if (logger.getLevel() != null) {
                    LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
                }
            }
        }

        @AfterAll
        static void restoreLevels() {
            for (Logger logger : loggerContext().getLoggerList()) {
                Level before = LEVELS_BEFORE.get(logger.getName());
                // The root logger always has a level and cannot be given none.
                if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                    logger.setLevel(before);
                }
            }
        }

        static Stream<ClientCall> clientCalls() {
            return Stream.of(
                    new ClientCall("usernameToken", SecureGreeterClient.PATH_USERNAME,
                            SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, null, null, false, false),
                    new ClientCall("usernameTokenEncrypted", SecureGreeterClient.PATH_ENCRYPTED,
                            SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED, null, null, false, false),
                    new ClientCall("samlToken", SecureGreeterClient.PATH_SAML,
                            SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN, null, null, false, false),
                    new ClientCall("usernameTokenSigned profile as joe", SecureGreeterClient.PATH_SIGNED, null,
                            SecureGreeterClient.USERNAME_TOKEN_SIGNATURE_TIMESTAMP, null, false, false),
                    new ClientCall("samlTokenSigned profile as joe", SecureGreeterClient.PATH_SIGNED_SAML, null,
                            SecureGreeterClient.SAML_TOKEN_SIGNED, SecureGreeterClient.DIRECT_REFERENCE, true, false),
                    new ClientCall("usernameToken answered with a security fault", SecureGreeterClient.PATH_USERNAME,
                            SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN, null, null, false, true));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("clientCalls")
        void callSendsItsSecurityHeaderWhileTheLogHoldsNone(ClientCall call, CapturedOutput output) {
            String url = WIREMOCK.baseUrl() + SERVICES + call.path();
            if (call.securityFault()) {
                WIREMOCK.stubFor(post(urlPathEqualTo(SERVICES + call.path())).atPriority(1)
                        .willReturn(fault("wsse:FailedAuthentication", " xmlns:wsse=\"" + WSSE_NS + "\"",
                                "The security token could not be authenticated or authorized")));
            }
            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            String greeting = null;
            SoapFaultClientException fault = null;
            try {
                greeting = send(call);
            } catch (SoapFaultClientException e) {
                fault = e;
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the call wrote.
            List<ILoggingEvent> logged;
            synchronized (events) {
                logged = List.copyOf(events.list);
            }

            if (call.securityFault()) {
                assertThat(fault).as("the security fault the stub answered").isNotNull();
                assertThat(fault.getFaultCode()).isEqualTo(new QName(WSSE_NS, "FailedAuthentication"));
            } else {
                assertThat(fault).as("a fault of the accepted call").isNull();
                assertThat(greeting).isEqualTo(GREETING);
            }
            String sent = lastRequestBody();
            assertThat(sent).as("the request the stub received").contains("<wsse:Security");
            if (call.clientType() != null && !SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN.equals(call.clientType())) {
                assertThat(sent).as("the UsernameToken of the sentinel user").contains(SENTINEL_USER);
            }

            assertThat(logged).as("the unpinned WebServiceTemplate DEBUG connection line of the call")
                    .anyMatch(event -> event.getLevel() == Level.DEBUG
                            && WebServiceTemplate.class.getName().equals(event.getLoggerName())
                            && event.getFormattedMessage().startsWith("Opening [")
                            && event.getFormattedMessage().endsWith("] to [" + url + "]"));
            ILoggingEvent proxyLine = logged.stream()
                    .filter(event -> event.getLevel() == Level.TRACE
                            && JDK_HTTP_LOGGER.equals(event.getLoggerName())
                            && event.getFormattedMessage().equals("ProxySelector Request for " + url))
                    .findFirst()
                    .orElse(null);
            assertThat(proxyLine).as("the unpinned JDK HTTP client TRACE line of the call").isNotNull();
            assertThat(output.getAll()).as("the console capture holds the TRACE line of the call")
                    .contains(proxyLine.getFormattedMessage());
            assertThat(logged).as("WARN events of Santuario's DOMReference, which a signed SAML assertion's"
                            + " STR-Transform reference raises")
                    .noneMatch(event -> DOM_REFERENCE_LOGGER.equals(event.getLoggerName())
                            && event.getLevel().isGreaterOrEqual(Level.WARN));

            List<String> forbidden = forbiddenValues();
            // Lines written after the call get SETTLE to arrive; the capture only grows, so one scan covers the wait.
            await().pollDelay(SETTLE).until(() -> true);
            assertThat(linesHolding(output.getAll(), forbidden))
                    .as("console lines holding a security-header element, a secret or the greeted name").isEmpty();
        }

        /** The call's client type through {@link SecureGreeterClient#invoke}, otherwise its profile as {@code joe}. */
        private String send(ClientCall call) {
            if (call.clientType() != null) {
                return client.invoke(call.clientType(), SENTINEL_NAME);
            }
            OutboundSecurity security = new OutboundSecurity(call.actions(), CLIENT_USER,
                    properties.getClient().getEncryptionUser(), call.keyIdentifier(), passwordCallback,
                    call.saml() ? samlCallbackHandler : null);
            return client.greet(client.endpoint(call.path()), security, SENTINEL_NAME);
        }

        /** Every value no console line may hold; the keystore password is read from the configuration. */
        private List<String> forbiddenValues() {
            List<String> values = new ArrayList<>(SECURITY_HEADER_ELEMENTS);
            values.add(SENTINEL_USER);
            values.add(SENTINEL_PASSWORD);
            values.add(SENTINEL_NAME);
            values.add(properties.getKeystore().getPassword());
            return values;
        }

        /** The distinct lines of {@code output} holding one of {@code values}, at most {@link #REPORTED_LINES}. */
        private static List<String> linesHolding(String output, List<String> values) {
            // The capture of a TRACE run is large, so it is split into lines only when it holds a value at all.
            if (values.stream().noneMatch(output::contains)) {
                return List.of();
            }
            return output.lines()
                    .filter(line -> values.stream().anyMatch(line::contains))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }
    }

    /**
     * One logged client call and what it must produce.
     *
     * @param name          the case's display name
     * @param path          the Greeter path the call reaches
     * @param clientType    the client type invoked, or {@code null} to send {@code actions} as {@code joe}
     * @param actions       the WSS4J actions of a profile sent as {@code joe}
     * @param keyIdentifier the signature key identifier of that profile, or {@code null} for WSS4J's default
     * @param saml          whether that profile carries the SAML assertion of {@code saml.properties}
     * @param securityFault whether the stub answers the call with a {@code wsse:FailedAuthentication} fault
     */
    record ClientCall(String name, String path, String clientType, String actions, String keyIdentifier,
                      boolean saml, boolean securityFault) {

        @Override
        public String toString() {
            return name;
        }
    }

    // Response timeout: a positive number of milliseconds, never the indefinite wait of zero

    @Test
    void responseTimeoutDefaultsToTheMuleDefaultResponseTimeout() {
        assertThat(properties.getClient().getResponseTimeout()).isEqualTo(MULE_DEFAULT_RESPONSE_TIMEOUT);
        PROPERTIES_BINDING.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SoapSecurityProperties.class).getClient().getResponseTimeout())
                    .isEqualTo(MULE_DEFAULT_RESPONSE_TIMEOUT);
        });
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = { "0", "-1", "-2147483648" })
    void nonPositiveResponseTimeoutFailsStartUp(String responseTimeout) {
        PROPERTIES_BINDING.withPropertyValues("soap-security.client.response-timeout=" + responseTimeout)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(NON_POSITIVE_RESPONSE_TIMEOUT + responseTimeout));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(ints = { 0, -1, Integer.MIN_VALUE })
    void responseTimeoutSetterRejectsANonPositiveValueAndKeepsTheCurrentOne(int responseTimeout) {
        SoapSecurityProperties.Client settings = new SoapSecurityProperties.Client();

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> settings.setResponseTimeout(responseTimeout));

        assertThat(rejected).hasMessage(NON_POSITIVE_RESPONSE_TIMEOUT + responseTimeout);
        assertThat(settings.getResponseTimeout()).isEqualTo(MULE_DEFAULT_RESPONSE_TIMEOUT);
    }

    /** Settings whose getter bypasses the setter's check still never reach a connection. */
    @ParameterizedTest(name = "[{0}]")
    @ValueSource(ints = { 0, -1 })
    void nonPositiveResponseTimeoutBuildsNoTemplateAndSendsNothing(int responseTimeout) {
        SoapSecurityProperties unchecked = new SoapSecurityProperties();
        unchecked.setClient(new SoapSecurityProperties.Client() {
            @Override
            public int getResponseTimeout() {
                return responseTimeout;
            }
        });
        unchecked.getClient().setBaseUrl(WIREMOCK.baseUrl() + "/services");
        SecureGreeterClient guarded = new SecureGreeterClient(unchecked, merlinCrypto, passwordCallback,
                samlCallbackHandler, messageFactory, marshaller, environment);

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> guarded.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME));

        assertThat(rejected).hasMessage(NON_POSITIVE_RESPONSE_TIMEOUT + responseTimeout);
        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    // Assertions shared by several client types

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

    // Helpers

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

    /** 200 with a SOAP 1.1 envelope whose Body holds {@code bodyContent}, empty included. */
    private static ResponseDefinitionBuilder soapReply(String bodyContent) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody("<soap:Envelope xmlns:soap=\"" + SOAP_NS + "\"><soap:Body>" + bodyContent
                        + "</soap:Body></soap:Envelope>");
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

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    /** Registers {@link SoapSecurityProperties} for binding, as {@code @EnableConfigurationProperties} on the application. */
    @EnableConfigurationProperties(SoapSecurityProperties.class)
    static class PropertiesBinding {
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
