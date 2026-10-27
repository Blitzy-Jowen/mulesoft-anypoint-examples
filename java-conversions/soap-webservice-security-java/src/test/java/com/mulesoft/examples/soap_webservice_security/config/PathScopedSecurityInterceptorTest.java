/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;
import javax.xml.transform.TransformerFactory;

import org.apache.wss4j.common.WSS4JConstants;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.transport.TransportInputStream;
import org.springframework.ws.transport.context.DefaultTransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.springframework.xml.transform.StringSource;
import org.springframework.xml.transform.TransformerFactoryUtils;

import com.mulesoft.examples.soap_webservice_security.config.PathScopedSecurityInterceptor.PathPolicy;
import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;

/**
 * Inbound WS-Security enforcement of the six Greeter paths, exercised on the {@link PathScopedSecurityInterceptor}
 * that {@link WsSecurityConfig} builds, without a server.
 *
 * <p>The policies, the Merlin crypto, the password callback and the SAML callback handler are the production beans,
 * created by an {@link ApplicationContextRunner} from {@code WsSecurityConfig} and bound from the {@code test}
 * profile, so the demo credentials come from {@code application-test.yml} alone and no policy is built by hand.
 *
 * <p>Each request is the {@code greet} envelope, secured by a Spring WS {@link Wss4jSecurityInterceptor} configured
 * the way {@code SecureGreeterClient} configures each original client type (the action set, the client user and the
 * password its callback supplies, the Merlin crypto, the encryption user, the SAML callback and the key identifier).
 * The secured request is serialized and parsed again, as the server receives it, and handed to the interceptor under
 * a servlet transport context carrying the request path. A rejection is the SOAP 1.1 fault the interceptor writes
 * into the response, with {@code false} returned so that no endpoint runs.
 *
 * <p>The cases cover each path's required action set of the original {@code mule-config.xml}, the success cases of
 * the original {@code SoapWebserviceSecurityIT} and its three negatives ({@code testUsernameTokenWrongPasswordError},
 * {@code testUsernameTokenUnsignedError}, {@code testSamlTokenWrongIssuer}), the original {@code samlTokenSigned}
 * client path, the any-order match and the fail-closed rules.
 */
class PathScopedSecurityInterceptorTest {

    private static final String UNSECURE = WsSecurityConfig.UNSECURE_PATH;
    private static final String USERNAME = WsSecurityConfig.USERNAME_PATH;
    private static final String SIGNED = WsSecurityConfig.SIGNED_PATH;
    private static final String ENCRYPTED = WsSecurityConfig.ENCRYPTED_PATH;
    private static final String SAML = WsSecurityConfig.SAML_PATH;
    private static final String SIGNED_SAML = WsSecurityConfig.SIGNED_SAML_PATH;

    /** Servlet path of the {@code MessageDispatcherServlet}, the original listener {@code basePath}. */
    private static final String SERVICES = "/services";

    /** The original {@code greet("Mule")} request body; the child is unqualified, as the Greeter contract states. */
    private static final String GREET_XML =
            "<ns:greet xmlns:ns=\"" + Greet.NAMESPACE + "\"><name>Mule</name></ns:greet>";

    private static final QName GREET = new QName(Greet.NAMESPACE, "greet");

    private static final QName ENCRYPTED_DATA = new QName(WSS4JConstants.ENC_NS, "EncryptedData");

    private static final QName SECURITY_HEADER = new QName(WSS4JConstants.WSSE_NS, "Security");

    private static final QName INVALID_SECURITY = new QName(WSS4JConstants.WSSE_NS, "InvalidSecurity");

    private static final QName FAILED_AUTHENTICATION = new QName(WSS4JConstants.WSSE_NS, "FailedAuthentication");

    /** WSS4J's {@code INVALID_SECURITY} text, which the original negatives match by {@code <wsse:Security> header}. */
    private static final String INVALID_SECURITY_TEXT = "An error was discovered processing the <wsse:Security> header";

    /** WSS4J's {@code FAILED_AUTHENTICATION} text, which the original wrong-password negative matches by substring. */
    private static final String FAILED_AUTHENTICATION_TEXT =
            "The security token could not be authenticated or authorized";

    private static final String JOE = "joe";
    private static final String STAN = "stan";
    private static final String UNKNOWN_USER = "bob";

    private static final String DIRECT_REFERENCE = "DirectReference";

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    /** The interceptor never reads its endpoint argument: it runs before any endpoint is selected. */
    private static final Object ENDPOINT = new Object();

    private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactoryUtils.newInstance();

    private static SaajSoapMessageFactory messageFactory;
    private static PathScopedSecurityInterceptor interceptor;
    private static Map<String, PathPolicy> pathPolicies;
    private static Supplier<Crypto> merlinCrypto;
    private static PasswordCallback passwordCallback;
    private static SAMLCallbackHandler samlCallbackHandler;
    private static SoapSecurityProperties properties;

    /** Registers the properties class, as the application class does for the real application. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SoapSecurityProperties.class)
    static class PropertiesConfig {
    }

    /**
     * Builds the production WS-Security beans once. None of them holds a resource the closed context would release,
     * so they serve every test after the runner has closed its context.
     */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void createProductionBeans() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=test")
                .withUserConfiguration(PropertiesConfig.class, WsSecurityConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    interceptor = context.getBean(PathScopedSecurityInterceptor.class);
                    pathPolicies = context.getBean("pathPolicies", Map.class);
                    merlinCrypto = context.getBean("merlinCrypto", WsSecurityConfig.LazyMerlinCrypto.class);
                    passwordCallback = context.getBean(PasswordCallback.class);
                    samlCallbackHandler = context.getBean(SAMLCallbackHandler.class);
                    properties = context.getBean(SoapSecurityProperties.class);
                });

        messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        messageFactory.afterPropertiesSet();
    }

    @AfterEach
    void clearTransportContext() {
        TransportContextHolder.setTransportContext(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Policy table
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void policyTableServesExactlyTheSixGreeterPaths() {
        assertThat(interceptor.policies()).isEqualTo(pathPolicies);
        assertThat(pathPolicies).containsOnlyKeys(UNSECURE, USERNAME, SIGNED, ENCRYPTED, SAML, SIGNED_SAML);
    }

    /** The {@code action} property of each original {@code cxf:ws-config}; blank for the unsecure service. */
    static Stream<Arguments> originalActionSets() {
        return Stream.of(
                Arguments.of(UNSECURE, ""),
                Arguments.of(USERNAME, "UsernameToken Timestamp"),
                Arguments.of(SIGNED, "UsernameToken Signature Timestamp"),
                Arguments.of(ENCRYPTED, "UsernameToken Timestamp Encrypt"),
                Arguments.of(SAML, "SAMLTokenUnsigned Timestamp"),
                Arguments.of(SIGNED_SAML, "SAMLTokenUnsigned Signature"));
    }

    @ParameterizedTest(name = "{0} requires [{1}]")
    @MethodSource("originalActionSets")
    void eachPathRequiresItsOriginalActionSet(String path, String originalActions) {
        PathPolicy policy = pathPolicies.get(path);

        assertThat(policy).isNotNull();
        assertThat(policy.isSecured()).isEqualTo(!originalActions.isEmpty());
        assertThat(actionTokens(policy.requiredActions())).isEqualTo(actionTokens(originalActions));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Accepted requests
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Path, the securing client, and whether the request carries a {@code BinarySecurityToken}: a token result the
     * receiver check must ignore, because no path lists it among its required actions.
     */
    static Stream<Arguments> acceptedRequests() {
        return Stream.of(
                Arguments.of("testUsernameToken: UsernameToken Timestamp as joe", USERNAME,
                        securer(() -> Securer.actions("UsernameToken Timestamp").user(JOE).build()), false),
                Arguments.of("UsernameToken Timestamp as stan", USERNAME,
                        securer(() -> Securer.actions("UsernameToken Timestamp").user(STAN).build()), false),
                Arguments.of("actions in another order: Timestamp UsernameToken", USERNAME,
                        securer(() -> Securer.actions("Timestamp UsernameToken").user(JOE).build()), false),
                Arguments.of("testUsernameTokenSigned: UsernameToken Signature Timestamp", SIGNED,
                        securer(() -> Securer.actions("UsernameToken Signature Timestamp").user(JOE).crypto()
                                .build()), false),
                Arguments.of("UsernameToken Signature Timestamp signed with a DirectReference token", SIGNED,
                        securer(() -> Securer.actions("UsernameToken Signature Timestamp").user(JOE).crypto()
                                .keyIdentifier(DIRECT_REFERENCE).build()), true),
                Arguments.of("testSamlToken: SAMLTokenUnsigned Timestamp issued by self", SAML,
                        securer(() -> Securer.actions("SAMLTokenUnsigned Timestamp").saml(samlCallbackHandler)
                                .build()), false),
                Arguments.of("samlTokenSigned client: SAMLTokenSigned with a DirectReference token", SIGNED_SAML,
                        securer(() -> Securer.actions("SAMLTokenSigned").user(JOE).crypto()
                                .keyIdentifier(DIRECT_REFERENCE).saml(samlCallbackHandler).build()), true));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("acceptedRequests")
    void requestCarryingThePathsActionSetIsAccepted(String description, String path,
            ThrowingSupplier<Wss4jSecurityInterceptor> securer, boolean carriesBinarySecurityToken) throws Throwable {
        SoapMessage request = secure(securer.get());
        assertThat(securityHeader(request)).as("security header").isNotNull();
        assertThat(binarySecurityTokens(request)).as("BinarySecurityToken elements")
                .isEqualTo(carriesBinarySecurityToken ? 1 : 0);

        Outcome outcome = receive(path, request);

        assertAccepted(outcome);
        assertThat(payloadRoot(request)).isEqualTo(GREET);
    }

    /** {@code testUsernameTokenEncrypted}: the body is encrypted for joe and decrypted in place for the endpoint. */
    @Test
    void encryptedRequestIsAcceptedAndItsBodyDecryptedForTheEndpoint() throws Exception {
        SoapMessage request = secure(Securer.actions("UsernameToken Timestamp Encrypt").user(JOE).crypto()
                .encryptionUser(JOE).build());
        assertThat(payloadRoot(request)).as("body root on the wire").isEqualTo(ENCRYPTED_DATA);

        Outcome outcome = receive(ENCRYPTED, request);

        assertAccepted(outcome);
        SoapMessage processed = (SoapMessage) outcome.context().getRequest();
        assertThat(payloadRoot(processed)).as("body root the endpoint reads").isEqualTo(GREET);
        assertThat(serialize(processed)).contains("<name>Mule</name>");
    }

    /**
     * {@code testUnsecure}: the unsecure service checks nothing, so a request passes byte for byte unchanged, with or
     * without a security header.
     */
    static Stream<Arguments> unsecureRequests() {
        return Stream.of(
                Arguments.of("no security header", securer(() -> null)),
                Arguments.of("a UsernameToken Timestamp header",
                        securer(() -> Securer.actions("UsernameToken Timestamp").user(JOE).build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsecureRequests")
    void unsecurePathPassesTheRequestThroughUnprocessed(String description,
            ThrowingSupplier<Wss4jSecurityInterceptor> securer) throws Throwable {
        Wss4jSecurityInterceptor client = securer.get();
        SoapMessage request = client == null ? reparse(greetRequest()) : secure(client);
        String before = serialize(request);

        Outcome outcome = receive(UNSECURE, request);

        assertAccepted(outcome);
        assertThat(serialize((SoapMessage) outcome.context().getRequest())).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rejected requests
    // ---------------------------------------------------------------------------------------------------------------

    /** Fails closed: a secured path rejects a request without any {@code wsse:Security} header. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        WsSecurityConfig.USERNAME_PATH, WsSecurityConfig.SIGNED_PATH, WsSecurityConfig.ENCRYPTED_PATH,
        WsSecurityConfig.SAML_PATH, WsSecurityConfig.SIGNED_SAML_PATH })
    void securedPathRejectsARequestWithoutSecurityHeader(String path) throws Exception {
        SoapMessage request = reparse(greetRequest());
        assertThat(securityHeader(request)).isNull();

        assertRejected(receive(path, request), INVALID_SECURITY, INVALID_SECURITY_TEXT);
    }

    /**
     * Path, the securing client, the expected fault code and text, and the substring the original negative test (or
     * the original fault text for the cases it did not cover) asserts.
     */
    static Stream<Arguments> rejectedRequests() {
        return Stream.of(
                Arguments.of("testUsernameTokenUnsignedError: required Signature missing", SIGNED,
                        securer(() -> Securer.actions("UsernameToken Timestamp").user(JOE).build()),
                        INVALID_SECURITY, INVALID_SECURITY_TEXT, "<wsse:Security> header"),
                Arguments.of("Signature the path does not require", USERNAME,
                        securer(() -> Securer.actions("UsernameToken Signature Timestamp").user(JOE).crypto()
                                .build()),
                        INVALID_SECURITY, INVALID_SECURITY_TEXT, "<wsse:Security> header"),
                Arguments.of("required Timestamp missing", USERNAME,
                        securer(() -> Securer.actions("UsernameToken").user(JOE).build()),
                        INVALID_SECURITY, INVALID_SECURITY_TEXT, "<wsse:Security> header"),
                Arguments.of("testSamlTokenWrongIssuer: assertion issued by www.example.com", SAML,
                        securer(() -> Securer.actions("SAMLTokenUnsigned Timestamp").saml(wrongIssuerHandler())
                                .build()),
                        INVALID_SECURITY, INVALID_SECURITY_TEXT, "<wsse:Security> header"),
                Arguments.of("testUsernameTokenWrongPasswordError: WrongPasswordCallback password", USERNAME,
                        securer(() -> Securer.actions("UsernameToken Timestamp")
                                .user(JOE, new WrongPasswordCallback()).build()),
                        FAILED_AUTHENTICATION, FAILED_AUTHENTICATION_TEXT, "security token"),
                Arguments.of("unknown user bob", USERNAME,
                        securer(() -> Securer.actions("UsernameToken Timestamp")
                                .userWithPassword(UNKNOWN_USER, passwordOf(JOE, passwordCallback)).build()),
                        FAILED_AUTHENTICATION, FAILED_AUTHENTICATION_TEXT, "security token"));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("rejectedRequests")
    void requestNotMatchingThePathsPolicyIsRejected(String description, String path,
            ThrowingSupplier<Wss4jSecurityInterceptor> securer, QName faultCode, String faultString,
            String originalAssertion) throws Throwable {
        Outcome outcome = receive(path, secure(securer.get()));

        assertThat(assertRejected(outcome, faultCode, faultString)).contains(originalAssertion);
    }

    /**
     * Fails closed on an unexpected failure: an empty {@code wsse:Username} makes the password callback fail with a
     * runtime exception, as the original's did, and the request is answered with {@code wsse:InvalidSecurity}.
     */
    @Test
    void usernameTokenWithoutUserNameIsRejected() throws Exception {
        String secured = serialize(secure(Securer.actions("UsernameToken Timestamp").user(JOE).build()));
        String withoutUserName = secured.replaceFirst("(<[\\w-]+:Username[^>]*>)" + JOE + "(</[\\w-]+:Username>)",
                "$1$2");
        assertThat(withoutUserName).as("UsernameToken user name removed").isNotEqualTo(secured);
        SoapMessage request = messageFactory.createWebServiceMessage(
                new XmlTransportInputStream(withoutUserName.getBytes(StandardCharsets.UTF_8)));

        assertRejected(receive(USERNAME, request), INVALID_SECURITY, INVALID_SECURITY_TEXT);
    }

    /**
     * The policy is chosen by the decoded, normalized path within the application, so no other spelling of a secured
     * path reaches it unsecured.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/services/%75sername", "/services/username;jsessionid=0", "/services/./username",
        "/services/other/../username", "/services//username" })
    void otherSpellingOfASecuredPathIsStillEnforced(String requestUri) throws Exception {
        SoapMessage request = reparse(greetRequest());

        assertRejected(receive(requestUri, request), INVALID_SECURITY, INVALID_SECURITY_TEXT);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // understands
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A secured path understands the {@code wsse:Security} header it processes; the unsecure path declines it, so a
     * {@code mustUnderstand} header there is answered by Spring WS as CXF answered a service without WS-Security.
     */
    @Test
    void securityHeaderIsUnderstoodOnlyOnSecuredPaths() throws Exception {
        SoapHeaderElement security = securityHeader(
                secure(Securer.actions("UsernameToken Timestamp").user(JOE).build()));
        assertThat(security).isNotNull();

        bindTransport(UNSECURE);
        assertThat(interceptor.understands(security)).as("on %s", UNSECURE).isFalse();

        for (String securedPath : List.of(USERNAME, SIGNED, ENCRYPTED, SAML, SIGNED_SAML)) {
            bindTransport(securedPath);
            assertThat(interceptor.understands(security)).as("on %s", securedPath).isTrue();
        }
    }

    @Test
    void otherHeadersAndRequestsWithoutServletTransportAreNotUnderstood() throws Exception {
        SoapMessage message = greetRequest();
        SoapHeaderElement other = message.getSoapHeader()
                .addHeaderElement(new QName("urn:example:other", "Other", "o"));
        SoapHeaderElement security = securityHeader(
                secure(Securer.actions("UsernameToken Timestamp").user(JOE).build()));

        bindTransport(USERNAME);
        assertThat(interceptor.understands(other)).as("non-security header on %s", USERNAME).isFalse();

        TransportContextHolder.setTransportContext(null);
        assertThat(interceptor.understands(security)).as("security header without a transport context").isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------------------

    /** Types a case's client factory, so that argument lists can hold lambdas. */
    private static ThrowingSupplier<Wss4jSecurityInterceptor> securer(
            ThrowingSupplier<Wss4jSecurityInterceptor> securer) {
        return securer;
    }

    /**
     * The {@code samlToken} client's callback handler built, like the production bean, by the same constructor from
     * {@code wrong-saml.properties}, whose issuer {@code SAMLCustomValidator} does not accept.
     */
    private static SAMLCallbackHandler wrongIssuerHandler() {
        SAMLCallbackHandler handler = new SAMLCallbackHandler(new ClassPathResource("wrong-saml.properties"),
                properties.getSaml().getIssuerKeyPassword(), merlinCrypto);
        assertThat(samlCallbackHandler.getIssuer()).as("issuer of saml.properties").isEqualTo("self");
        assertThat(handler.getIssuer()).as("issuer of wrong-saml.properties").isEqualTo("www.example.com");
        return handler;
    }

    /** The password {@code handler} supplies for {@code user}, as WSS4J asked the original password callback. */
    private static String passwordOf(String user, CallbackHandler handler) throws Exception {
        return passwordOf(user, handler, WSPasswordCallback.USERNAME_TOKEN);
    }

    private static String passwordOf(String user, CallbackHandler handler, int usage) throws Exception {
        WSPasswordCallback callback = new WSPasswordCallback(user, usage);
        handler.handle(new Callback[] { callback });
        assertThat(callback.getPassword()).as("password of %s", user).isNotEmpty();
        return callback.getPassword();
    }

    /** A new, unsecured {@code greet} request. */
    private static SoapMessage greetRequest() throws Exception {
        SoapMessage message = messageFactory.createWebServiceMessage();
        TRANSFORMER_FACTORY.newTransformer().transform(new StringSource(GREET_XML), message.getPayloadResult());
        return message;
    }

    /** A new {@code greet} request secured by {@code client}, read back as the server receives it. */
    private static SoapMessage secure(Wss4jSecurityInterceptor client) throws Exception {
        MessageContext context = new DefaultMessageContext(greetRequest(), messageFactory);
        assertThat(client.handleRequest(context)).as("request secured").isTrue();
        client.afterCompletion(context, null);
        return reparse((SoapMessage) context.getRequest());
    }

    /** Serializes {@code message} and parses the bytes with a text/xml content type, as a received request. */
    private static SoapMessage reparse(SoapMessage message) throws IOException {
        byte[] bytes = serialize(message).getBytes(StandardCharsets.UTF_8);
        return messageFactory.createWebServiceMessage(new XmlTransportInputStream(bytes));
    }

    private static String serialize(SoapMessage message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Runs the interceptor on {@code request} received on {@code requestUri}. */
    private static Outcome receive(String requestUri, SoapMessage request) {
        bindTransport(requestUri);
        MessageContext context = new DefaultMessageContext(request, messageFactory);
        boolean proceed = interceptor.handleRequest(context, ENDPOINT);
        return new Outcome(proceed, context);
    }

    /**
     * Binds a POST to {@code requestUri} through the {@code MessageDispatcherServlet} mapped at {@code /services}, as
     * the servlet transport does for every request it dispatches.
     */
    private static void bindTransport(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setServletPath(SERVICES);
        request.setPathInfo(requestUri.substring(SERVICES.length()));
        request.setContentType(TEXT_XML_UTF8);
        // The constructor is protected: Spring WS creates the connection inside its servlet adapter.
        HttpServletConnection connection = new HttpServletConnection(request, new MockHttpServletResponse()) {
        };
        TransportContextHolder.setTransportContext(new DefaultTransportContext(connection));
    }

    /** Accepted: the chain proceeds to the endpoint and no response, hence no fault, is created. */
    private static void assertAccepted(Outcome outcome) {
        assertThat(outcome.proceed()).as("handleRequest result").isTrue();
        assertThat(outcome.context().hasResponse()).as("response created").isFalse();
    }

    /**
     * Rejected: the chain stops and the response is the SOAP 1.1 fault {@code faultCode} (prefix {@code wsse}) with
     * exactly {@code faultString}, which is returned.
     */
    private static String assertRejected(Outcome outcome, QName faultCode, String faultString) {
        assertThat(outcome.proceed()).as("handleRequest result").isFalse();
        assertThat(outcome.context().hasResponse()).as("response created").isTrue();
        SoapMessage response = (SoapMessage) outcome.context().getResponse();
        assertThat(response.getVersion()).isEqualTo(SoapVersion.SOAP_11);
        SoapFault fault = response.getSoapBody().getFault();
        assertThat(fault).as("fault in the response").isNotNull();
        assertThat(fault.getFaultCode()).isEqualTo(faultCode);
        assertThat(fault.getFaultCode().getPrefix()).isEqualTo("wsse");
        assertThat(fault.getFaultStringOrReason()).isEqualTo(faultString);
        return fault.getFaultStringOrReason();
    }

    /** The request's {@code wsse:Security} header element, or {@code null} when it has none. */
    private static SoapHeaderElement securityHeader(SoapMessage message) {
        if (message.getSoapHeader() == null) {
            return null;
        }
        Iterator<SoapHeaderElement> elements = message.getSoapHeader().examineAllHeaderElements();
        while (elements.hasNext()) {
            SoapHeaderElement element = elements.next();
            if (SECURITY_HEADER.equals(element.getName())) {
                return element;
            }
        }
        return null;
    }

    private static int binarySecurityTokens(SoapMessage message) {
        return message.getDocument().getElementsByTagNameNS(WSS4JConstants.WSSE_NS, "BinarySecurityToken")
                .getLength();
    }

    private static QName payloadRoot(SoapMessage message) throws Exception {
        return PayloadRootUtils.getPayloadRootQName(message.getPayloadSource(), TRANSFORMER_FACTORY);
    }

    private static Set<String> actionTokens(String actions) {
        return actions.isBlank() ? Set.of()
                : Arrays.stream(actions.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }

    /** The interceptor's verdict and the message context it ran on. */
    private record Outcome(boolean proceed, MessageContext context) {
    }

    /**
     * A securing client interceptor configured as {@code SecureGreeterClient} configures each original client type:
     * user name and signature user, the password the user's callback supplies (the UsernameToken password, or the
     * signing key password when no UsernameToken is sent), the Merlin crypto for signing and encryption, the
     * encryption user, the SAML callback and the signature key identifier. Every other setting keeps WSS4J's
     * default, and responses are not validated.
     */
    private static final class Securer {

        private final String actions;

        private final Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();

        private Securer(String actions) {
            this.actions = actions;
            interceptor.setSecurementActions(actions);
            interceptor.setValidationActions("NoSecurity");
            interceptor.setValidateResponse(false);
        }

        static Securer actions(String actions) {
            return new Securer(actions);
        }

        /** {@code user} with the password the production {@code PasswordCallback} supplies. */
        Securer user(String user) throws Exception {
            return user(user, passwordCallback);
        }

        /** {@code user} with the password {@code handler} supplies, as the original tests swapped the callback. */
        Securer user(String user, CallbackHandler handler) throws Exception {
            int usage = actionTokens(actions).contains("UsernameToken")
                    ? WSPasswordCallback.USERNAME_TOKEN
                    : WSPasswordCallback.SIGNATURE;
            return userWithPassword(user, passwordOf(user, handler, usage));
        }

        Securer userWithPassword(String user, String password) {
            interceptor.setSecurementUsername(user);
            interceptor.setSecurementSignatureUser(user);
            interceptor.setSecurementPassword(password);
            return this;
        }

        /** The production Merlin crypto ({@code wssecurity.properties}) for signing and encryption. */
        Securer crypto() {
            Crypto crypto = merlinCrypto.get();
            interceptor.setSecurementSignatureCrypto(crypto);
            interceptor.setSecurementEncryptionCrypto(crypto);
            return this;
        }

        Securer encryptionUser(String user) {
            interceptor.setSecurementEncryptionUser(user);
            return this;
        }

        Securer keyIdentifier(String keyIdentifier) {
            interceptor.setSecurementSignatureKeyIdentifier(keyIdentifier);
            return this;
        }

        Securer saml(CallbackHandler handler) {
            interceptor.setSecurementSamlCallbackHandler(handler);
            return this;
        }

        Wss4jSecurityInterceptor build() throws Exception {
            interceptor.afterPropertiesSet();
            return interceptor;
        }
    }

    /** A received request body with its text/xml content type; saaj-impl refuses a message without one. */
    private static final class XmlTransportInputStream extends TransportInputStream {

        private final byte[] content;

        XmlTransportInputStream(byte[] content) {
            this.content = content;
        }

        @Override
        protected InputStream createInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public Iterator<String> getHeaderNames() {
            return List.of(CONTENT_TYPE).iterator();
        }

        @Override
        public Iterator<String> getHeaders(String name) {
            return CONTENT_TYPE.equalsIgnoreCase(name)
                    ? List.of(TEXT_XML_UTF8).iterator()
                    : Collections.emptyIterator();
        }
    }
}
