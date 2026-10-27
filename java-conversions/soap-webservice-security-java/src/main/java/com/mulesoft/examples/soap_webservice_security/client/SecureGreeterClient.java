/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

import com.mulesoft.examples.soap_webservice_security.config.PasswordCallback;
import com.mulesoft.examples.soap_webservice_security.config.SAMLCallbackHandler;
import com.mulesoft.examples.soap_webservice_security.config.SoapSecurityProperties;
import com.mulesoft.examples.soap_webservice_security.config.WebServiceConfig;
import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;

/**
 * Outbound Greeter client of the {@code /client} front: one WS-Security client per original client type, each
 * calling its Greeter path on the SOAP listener ({@code http://0.0.0.0:63081/services/<path>} by default).
 *
 * <p>Replaces the CXF {@code SecureClient}, the six {@code cxf:jaxws-client} + {@code http:request} sub-flows of the
 * original {@code service-clients.xml} (lines 41-110) and the {@code SecurityClients} choice that selects one of them
 * (lines 12-34). Each secured type sends through a Spring WS {@link Wss4jSecurityInterceptor} configured with the
 * original action set, user, crypto and callbacks:
 * <table>
 *   <caption>Client types</caption>
 *   <tr><th>Client type</th><th>Path</th><th>Actions</th></tr>
 *   <tr><td>{@code unsecure}</td><td>{@code unsecure}</td><td>none</td></tr>
 *   <tr><td>{@code usernameToken}</td><td>{@code username}</td><td>{@code UsernameToken Timestamp}</td></tr>
 *   <tr><td>{@code usernameTokenSigned}</td><td>{@code signed}</td>
 *       <td>{@code UsernameToken Signature Timestamp}</td></tr>
 *   <tr><td>{@code usernameTokenEncrypted}</td><td>{@code encrypted}</td>
 *       <td>{@code UsernameToken Timestamp Encrypt}</td></tr>
 *   <tr><td>{@code samlToken}</td><td>{@code saml}</td><td>{@code SAMLTokenUnsigned Timestamp}</td></tr>
 *   <tr><td>{@code samlTokenSigned}</td><td>{@code signedsaml}</td>
 *       <td>{@code SAMLTokenSigned}, {@code DirectReference}</td></tr>
 * </table>
 * Every other WS-Security setting keeps its WSS4J default, as the CXF clients did (password type
 * {@code PasswordDigest}, Timestamp TTL, key identifiers, algorithms, {@code mustUnderstand}, body-content
 * encryption). Responses are not validated: the original clients had out-interceptors only.
 *
 * <p>Nothing here catches: a SOAP fault raises {@code SoapFaultClientException} carrying the server's
 * {@code faultstring}, a transport failure {@code WebServiceIOException}, and missing configuration
 * {@link IllegalStateException}; the original catch branch is {@code GreeterClientExceptionHandler}.
 *
 * <p>Every user name and password comes from {@code soap-security.*} through the injected beans. Nothing that needs a
 * secret is touched before a client type is first called, so the application starts with every credential
 * placeholder empty; each type's template is built on its first call and kept only once built, so a type that failed
 * for missing configuration is built again on its next call. Templates and their interceptors are not modified after
 * they are built, so one instance serves concurrent callers.
 */
@Component
public class SecureGreeterClient {

    /** The {@code otherwise} payload of the original choice (service-clients.xml:32). */
    public static final String UNSUPPORTED_CLIENT_TYPE = "Client type is not supported";

    public static final String CLIENT_TYPE_UNSECURE = "unsecure";
    public static final String CLIENT_TYPE_USERNAME_TOKEN = "usernameToken";
    public static final String CLIENT_TYPE_USERNAME_TOKEN_SIGNED = "usernameTokenSigned";
    public static final String CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED = "usernameTokenEncrypted";
    public static final String CLIENT_TYPE_SAML_TOKEN = "samlToken";
    public static final String CLIENT_TYPE_SAML_TOKEN_SIGNED = "samlTokenSigned";

    /** WS-Security action sets of the original {@code cxf:ws-config} {@code action} properties. */
    public static final String USERNAME_TOKEN_TIMESTAMP = "UsernameToken Timestamp";
    public static final String USERNAME_TOKEN_SIGNATURE_TIMESTAMP = "UsernameToken Signature Timestamp";
    public static final String USERNAME_TOKEN_TIMESTAMP_ENCRYPT = "UsernameToken Timestamp Encrypt";
    public static final String SAML_TOKEN_UNSIGNED_TIMESTAMP = "SAMLTokenUnsigned Timestamp";
    public static final String SAML_TOKEN_SIGNED = "SAMLTokenSigned";

    /** Signature key identifier of the {@code samlTokenSigned} client. */
    public static final String DIRECT_REFERENCE = "DirectReference";

    /** Greeter path segments under {@code services}, the original {@code http:request path} values. */
    public static final String PATH_UNSECURE = "unsecure";
    public static final String PATH_USERNAME = "username";
    public static final String PATH_SIGNED = "signed";
    public static final String PATH_ENCRYPTED = "encrypted";
    public static final String PATH_SAML = "saml";
    public static final String PATH_SIGNED_SAML = "signedsaml";

    /** Port of the original {@code HTTP_Request_Configuration} (service-clients.xml:7). */
    static final int DEFAULT_SERVICE_PORT = 63081;

    /** The original request-config {@code basePath}, with the separators around it. */
    private static final String SERVICES_PATH = "/services/";

    private static final String NO_SECURITY = "NoSecurity";

    /** Written by Spring WS as the quoted empty string {@code ""}. */
    private static final String EMPTY_SOAP_ACTION = "";

    /** Actions for which WSS4J asks for the user's password: the token password or the private-key password. */
    private static final Set<String> PASSWORD_ACTIONS = Set.of(ConfigurationConstants.USERNAME_TOKEN,
            ConfigurationConstants.SIGNATURE, ConfigurationConstants.SAML_TOKEN_SIGNED);

    /** Actions for which WSS4J loads a crypto. */
    private static final Set<String> CRYPTO_ACTIONS = Set.of(ConfigurationConstants.SIGNATURE,
            ConfigurationConstants.ENCRYPT, ConfigurationConstants.ENCRYPTION,
            ConfigurationConstants.SAML_TOKEN_SIGNED);

    private static final Set<String> ENCRYPT_ACTIONS =
            Set.of(ConfigurationConstants.ENCRYPT, ConfigurationConstants.ENCRYPTION);

    private final SoapSecurityProperties properties;
    private final Supplier<Crypto> crypto;
    private final PasswordCallback passwordCallback;
    private final SAMLCallbackHandler samlCallbackHandler;
    private final ObjectProvider<SaajSoapMessageFactory> messageFactoryProvider;
    private final ObjectProvider<Jaxb2Marshaller> marshallerProvider;
    private final Environment environment;

    private final ResourceLoader resourceLoader = new DefaultResourceLoader(SecureGreeterClient.class.getClassLoader());

    /** Template of each client type, filled on the type's first successful build. */
    private final ConcurrentMap<String, WebServiceTemplate> templates = new ConcurrentHashMap<>();

    private final Object transportLock = new Object();

    private volatile Transport transport;

    /**
     * @param properties          the {@code soap-security.*} settings: client user, encryption user, host, base URL,
     *                            response timeout and SAML issuer key password
     * @param crypto              the Merlin crypto of {@code soap-security.keystore.*}; read only by the client types
     *                            that sign or encrypt, when they are first called
     * @param passwordCallback    the original {@code passwordCallbackClass}, supplying the client user's password
     * @param samlCallbackHandler the original {@code samlCallbackClass} for {@code soap-security.saml.properties}
     * @param messageFactory      the SOAP 1.1 message factory; a default SOAP 1.1 factory when none is defined
     * @param marshaller          the {@code greet}/{@code greetResponse} marshaller; a plain one when none is defined
     * @param environment         source of the running SOAP listener's port
     */
    public SecureGreeterClient(SoapSecurityProperties properties,
            @Qualifier("merlinCrypto") Supplier<Crypto> crypto,
            PasswordCallback passwordCallback,
            SAMLCallbackHandler samlCallbackHandler,
            ObjectProvider<SaajSoapMessageFactory> messageFactory,
            @Qualifier(WebServiceConfig.GREETER_MARSHALLER_BEAN_NAME) ObjectProvider<Jaxb2Marshaller> marshaller,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.passwordCallback = Objects.requireNonNull(passwordCallback, "passwordCallback");
        this.samlCallbackHandler = Objects.requireNonNull(samlCallbackHandler, "samlCallbackHandler");
        this.messageFactoryProvider = Objects.requireNonNull(messageFactory, "messageFactory");
        this.marshallerProvider = Objects.requireNonNull(marshaller, "marshaller");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * The original {@code SecurityClients} choice: calls the client of {@code clientType}, compared exactly and
     * case-sensitively in the original order, and returns the greeting the service answered.
     *
     * @param clientType one of the {@code CLIENT_TYPE_*} values; any other value, {@code null} included, selects the
     *                   {@code otherwise} branch
     * @param name       the name to greet; {@code null} sends an empty {@code greet} element
     * @return the service's greeting, or {@link #UNSUPPORTED_CLIENT_TYPE} without any request for an unknown type
     */
    public String invoke(String clientType, String name) {
        if (CLIENT_TYPE_UNSECURE.equals(clientType)) {
            return unsecure(name);
        } else if (CLIENT_TYPE_USERNAME_TOKEN.equals(clientType)) {
            return usernameToken(name);
        } else if (CLIENT_TYPE_USERNAME_TOKEN_SIGNED.equals(clientType)) {
            return usernameTokenSigned(name);
        } else if (CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED.equals(clientType)) {
            return usernameTokenEncrypted(name);
        } else if (CLIENT_TYPE_SAML_TOKEN.equals(clientType)) {
            return samlToken(name);
        } else if (CLIENT_TYPE_SAML_TOKEN_SIGNED.equals(clientType)) {
            return samlTokenSigned(name);
        }
        return UNSUPPORTED_CLIENT_TYPE;
    }

    /** Sub-flow {@code unsecure}: no security header, path {@code unsecure}. */
    public String unsecure(String name) {
        return send(CLIENT_TYPE_UNSECURE, PATH_UNSECURE, () -> OutboundSecurity.NONE, name);
    }

    /** Sub-flow {@code usernameToken}: {@code UsernameToken Timestamp}, path {@code username}. */
    public String usernameToken(String name) {
        return send(CLIENT_TYPE_USERNAME_TOKEN, PATH_USERNAME,
                () -> getUsernameTokenProps(USERNAME_TOKEN_TIMESTAMP), name);
    }

    /** Sub-flow {@code usernameTokenSigned}: {@code UsernameToken Signature Timestamp}, path {@code signed}. */
    public String usernameTokenSigned(String name) {
        return send(CLIENT_TYPE_USERNAME_TOKEN_SIGNED, PATH_SIGNED,
                () -> getUsernameTokenProps(USERNAME_TOKEN_SIGNATURE_TIMESTAMP), name);
    }

    /** Sub-flow {@code usernameTokenEncrypted}: {@code UsernameToken Timestamp Encrypt}, path {@code encrypted}. */
    public String usernameTokenEncrypted(String name) {
        return send(CLIENT_TYPE_USERNAME_TOKEN_ENCRYPTED, PATH_ENCRYPTED,
                () -> getUsernameTokenProps(USERNAME_TOKEN_TIMESTAMP_ENCRYPT), name);
    }

    /**
     * Sub-flow {@code samlToken}: {@code SAMLTokenUnsigned Timestamp} from {@code soap-security.saml.properties},
     * path {@code saml}.
     */
    public String samlToken(String name) {
        return send(CLIENT_TYPE_SAML_TOKEN, PATH_SAML,
                () -> new OutboundSecurity(SAML_TOKEN_UNSIGNED_TIMESTAMP, null, null, null, null, samlCallbackHandler),
                name);
    }

    /**
     * Sub-flow {@code samlTokenSigned}: {@code SAMLTokenSigned} with a {@code DirectReference} signing token, signed
     * for the client user, path {@code signedsaml}.
     */
    public String samlTokenSigned(String name) {
        return send(CLIENT_TYPE_SAML_TOKEN_SIGNED, PATH_SIGNED_SAML,
                () -> new OutboundSecurity(SAML_TOKEN_SIGNED, properties.getClient().getUser(), null,
                        DIRECT_REFERENCE, passwordCallback, samlCallbackHandler),
                name);
    }

    /**
     * The original {@code SecureClient.getUsernameTokenProps}: {@code action} for the client user with the
     * configured password callback; signing and encryption use the Merlin crypto (the original
     * {@code signaturePropFile} and {@code encryptionPropFile}) and encrypt for the configured encryption user.
     */
    public OutboundSecurity getUsernameTokenProps(String action) {
        SoapSecurityProperties.Client client = properties.getClient();
        return new OutboundSecurity(action, client.getUser(), client.getEncryptionUser(), null, passwordCallback,
                null);
    }

    /**
     * The original {@code SecureClient.getSamlTokenProps}: {@code action} with a SAML callback built for
     * {@code samlPropertiesResource}, without user or password.
     *
     * @param samlPropertiesResource Spring resource location of the SAML properties file; a plain name such as
     *                               {@code wrong-saml.properties} is read from the classpath
     */
    public OutboundSecurity getSamlTokenProps(String action, String samlPropertiesResource) {
        return new OutboundSecurity(action, null, null, null, null, samlCallbackHandler(samlPropertiesResource));
    }

    /**
     * The original {@code SecureClient.getSignedSamlTokenProps}: {@code action} with a SAML callback built for
     * {@code samlPropertiesResource}, a {@code DirectReference} signing token, the client user and the configured
     * password callback.
     *
     * @param samlPropertiesResource Spring resource location of the SAML properties file; a plain name such as
     *                               {@code saml.properties} is read from the classpath
     */
    public OutboundSecurity getSignedSamlTokenProps(String action, String samlPropertiesResource) {
        return new OutboundSecurity(action, properties.getClient().getUser(), null, DIRECT_REFERENCE,
                passwordCallback, samlCallbackHandler(samlPropertiesResource));
    }

    /**
     * The URL of a Greeter path: {@code soap-security.client.base-url} followed by {@code /<servicePath>} when the
     * base URL is set, otherwise
     * {@code http://<soap-security.client.host>:<SOAP listener port>/services/<servicePath>}, the original
     * request-config host, port and {@code basePath}. The port is resolved on every call.
     *
     * @param servicePath the path segment, such as {@code unsecure}; leading slashes are ignored
     * @throws IllegalStateException when neither a base URL nor a host is configured, or they form no valid URL
     */
    public URI endpoint(String servicePath) {
        String segment = Objects.requireNonNull(servicePath, "servicePath");
        while (segment.startsWith("/")) {
            segment = segment.substring(1);
        }
        SoapSecurityProperties.Client client = properties.getClient();
        String baseUrl = client.getBaseUrl().trim();
        if (!baseUrl.isEmpty()) {
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            try {
                return new URI(baseUrl + "/" + segment);
            } catch (URISyntaxException ex) {
                throw new IllegalStateException("soap-security.client.base-url does not form a valid URL", ex);
            }
        }
        String host = client.getHost().trim();
        if (host.isEmpty()) {
            throw new IllegalStateException("Neither soap-security.client.base-url nor soap-security.client.host "
                    + "is configured");
        }
        try {
            // The multi-argument constructor brackets an IPv6 literal host.
            return new URI("http", null, host, mainPort(), SERVICES_PATH + segment, null, null);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("soap-security.client.host does not form a valid URL", ex);
        }
    }

    /**
     * Sends one {@code greet} to {@code endpoint} with a template built for {@code security} alone and not kept, as
     * the original tests created one CXF client per call; used to reproduce the original negative requests.
     *
     * @param security the outbound profile; {@code null} means {@link OutboundSecurity#NONE}
     * @return the service's greeting
     */
    public String greet(URI endpoint, OutboundSecurity security, String name) {
        Objects.requireNonNull(endpoint, "endpoint");
        return send(buildTemplate(security == null ? OutboundSecurity.NONE : security), endpoint, name);
    }

    private String send(String clientType, String servicePath, Supplier<OutboundSecurity> profile, String name) {
        // computeIfAbsent stores nothing when the build throws, so a type that lacked configuration is retried.
        WebServiceTemplate template = templates.computeIfAbsent(clientType, type -> buildTemplate(profile.get()));
        return send(template, endpoint(servicePath), name);
    }

    private static String send(WebServiceTemplate template, URI endpoint, String name) {
        // The shared message factory adds no SOAPAction header by itself, and a secured message only gets one when
        // WSS4J rebuilds it; setting it here sends the empty SOAPAction the CXF clients sent on every type.
        Object response = template.marshalSendAndReceive(endpoint.toString(), new Greet(name),
                message -> ((SoapMessage) message).setSoapAction(EMPTY_SOAP_ACTION));
        if (response instanceof GreetResponse greetResponse) {
            return greetResponse.getName();
        }
        throw new IllegalStateException("The Greeter service at " + endpoint + " answered with "
                + (response == null ? "no payload" : response.getClass().getName()) + " instead of greetResponse");
    }

    /**
     * The main listener's port. Spring Boot publishes the first listener of the server as {@code local.server.port};
     * {@code ListenerPorts} adds the {@code client} listener after the {@code server.port} listener, so this is always
     * the SOAP listener's actual port, a random one included, never the client front's.
     */
    private int mainPort() {
        Integer localPort = environment.getProperty("local.server.port", Integer.class);
        if (localPort != null && localPort > 0) {
            return localPort;
        }
        Integer configuredPort = environment.getProperty("server.port", Integer.class);
        if (configuredPort != null && configuredPort > 0) {
            return configuredPort;
        }
        return DEFAULT_SERVICE_PORT;
    }

    private WebServiceTemplate buildTemplate(OutboundSecurity security) {
        Transport resolved = transport();
        WebServiceTemplate template = new WebServiceTemplate(resolved.messageFactory());
        template.setMarshaller(resolved.marshaller());
        template.setUnmarshaller(resolved.marshaller());

        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        Duration timeout = Duration.ofMillis(properties.getClient().getResponseTimeout());
        sender.setConnectionTimeout(timeout);
        sender.setReadTimeout(timeout);
        template.setMessageSender(sender);

        if (security.isSecured()) {
            template.setInterceptors(new ClientInterceptor[] { buildInterceptor(security) });
        }
        return template;
    }

    private Wss4jSecurityInterceptor buildInterceptor(OutboundSecurity security) {
        Set<String> actions = actionTokens(security.actions());
        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        interceptor.setSecurementActions(security.actions());

        String user = security.user();
        if (StringUtils.hasText(user)) {
            interceptor.setSecurementUsername(user);
            interceptor.setSecurementSignatureUser(user);
        }
        if (containsAny(actions, PASSWORD_ACTIONS)) {
            // Spring WS 4.1 takes no securement password callback, so the password the CXF passwordCallbackClass
            // supplied is resolved here; WSS4J uses it as the UsernameToken password and the signing key password.
            interceptor.setSecurementPassword(resolvePassword(security, actions));
        }
        if (containsAny(actions, CRYPTO_ACTIONS)) {
            Crypto merlin = crypto.get();
            interceptor.setSecurementSignatureCrypto(merlin);
            interceptor.setSecurementEncryptionCrypto(merlin);
        }
        if (containsAny(actions, ENCRYPT_ACTIONS) && StringUtils.hasText(security.encryptionUser())) {
            interceptor.setSecurementEncryptionUser(security.encryptionUser());
        }
        if (security.samlCallback() != null) {
            interceptor.setSecurementSamlCallbackHandler(security.samlCallback());
        }
        if (StringUtils.hasText(security.signatureKeyIdentifier())) {
            interceptor.setSecurementSignatureKeyIdentifier(security.signatureKeyIdentifier());
        }
        interceptor.setValidationActions(NO_SECURITY);
        interceptor.setValidateResponse(false);
        try {
            interceptor.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialise the WS-Security client for actions ["
                    + security.actions() + "]", ex);
        }
        return interceptor;
    }

    /**
     * Asks the profile's password callback for the user's password, as WSS4J asked the CXF
     * {@code passwordCallbackClass}: for a UsernameToken when the actions include one, otherwise for a signature key.
     * Failures name the user, never a password.
     */
    private static String resolvePassword(OutboundSecurity security, Set<String> actions) {
        String user = security.user();
        if (!StringUtils.hasText(user)) {
            throw new IllegalStateException("WS-Security actions [" + security.actions()
                    + "] need a user; soap-security.client.user is not configured");
        }
        CallbackHandler handler = security.passwordCallback();
        if (handler == null) {
            throw new IllegalStateException("No password callback is set for WS-Security user " + user);
        }
        int usage = actions.contains(ConfigurationConstants.USERNAME_TOKEN)
                ? WSPasswordCallback.USERNAME_TOKEN
                : WSPasswordCallback.SIGNATURE;
        WSPasswordCallback callback = new WSPasswordCallback(user, usage);
        try {
            handler.handle(new Callback[] { callback });
        } catch (IOException | UnsupportedCallbackException ex) {
            throw new IllegalStateException("Cannot obtain the password of WS-Security user " + user, ex);
        }
        String password = callback.getPassword();
        if (!StringUtils.hasLength(password)) {
            throw new IllegalStateException("No password is configured for WS-Security user " + user);
        }
        return password;
    }

    private SAMLCallbackHandler samlCallbackHandler(String samlPropertiesResource) {
        Objects.requireNonNull(samlPropertiesResource, "samlPropertiesResource");
        return new SAMLCallbackHandler(resourceLoader.getResource(samlPropertiesResource),
                properties.getSaml().getIssuerKeyPassword(), crypto);
    }

    private Transport transport() {
        Transport resolved = transport;
        if (resolved == null) {
            synchronized (transportLock) {
                resolved = transport;
                if (resolved == null) {
                    resolved = new Transport(resolveMessageFactory(), resolveMarshaller());
                    transport = resolved;
                }
            }
        }
        return resolved;
    }

    private SaajSoapMessageFactory resolveMessageFactory() {
        SaajSoapMessageFactory factory = messageFactoryProvider.getIfAvailable();
        if (factory != null) {
            return factory;
        }
        SaajSoapMessageFactory fallback = new SaajSoapMessageFactory();
        fallback.setSoapVersion(SoapVersion.SOAP_11);
        fallback.afterPropertiesSet();
        return fallback;
    }

    private Jaxb2Marshaller resolveMarshaller() {
        Jaxb2Marshaller marshaller = marshallerProvider.getIfAvailable();
        if (marshaller != null) {
            return marshaller;
        }
        Jaxb2Marshaller fallback = new Jaxb2Marshaller();
        fallback.setClassesToBeBound(Greet.class, GreetResponse.class);
        try {
            fallback.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialise the Greeter marshaller", ex);
        }
        return fallback;
    }

    private static Set<String> actionTokens(String actions) {
        if (!StringUtils.hasText(actions)) {
            return Set.of();
        }
        return Arrays.stream(actions.trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean containsAny(Set<String> actions, Set<String> wanted) {
        for (String action : wanted) {
            if (actions.contains(action)) {
                return true;
            }
        }
        return false;
    }

    /** The message factory and marshaller every template of this client shares. */
    private record Transport(SaajSoapMessageFactory messageFactory, Jaxb2Marshaller marshaller) {
    }

    /**
     * One outbound WS-Security profile, the counterpart of the WSS4J property map the original {@code SecureClient}
     * built for CXF's {@code WSS4JOutInterceptor}.
     *
     * @param actions                WSS4J action set, such as {@code UsernameToken Timestamp}; blank sends no security
     *                               header
     * @param user                   UsernameToken user and signature key alias
     * @param encryptionUser         certificate alias the body is encrypted for
     * @param signatureKeyIdentifier signature key identifier type, such as {@code DirectReference}; WSS4J's default
     *                               when blank
     * @param passwordCallback       supplies {@code user}'s password
     * @param samlCallback           populates the SAML assertion of the SAML actions
     */
    public record OutboundSecurity(String actions, String user, String encryptionUser, String signatureKeyIdentifier,
            CallbackHandler passwordCallback, CallbackHandler samlCallback) {

        /** No security header, the {@code unsecure} client. */
        public static final OutboundSecurity NONE = new OutboundSecurity(null, null, null, null, null, null);

        /** @return whether this profile adds a security header */
        public boolean isSecured() {
            return StringUtils.hasText(actions);
        }

        /**
         * @return a copy whose password comes from {@code callback}, as the original tests swapped the
         *         {@code passwordCallbackClass}
         */
        public OutboundSecurity withPasswordCallback(CallbackHandler callback) {
            return new OutboundSecurity(actions, user, encryptionUser, signatureKeyIdentifier, callback, samlCallback);
        }

        /** @return a copy whose SAML assertion is populated by {@code callback} */
        public OutboundSecurity withSamlCallback(CallbackHandler callback) {
            return new OutboundSecurity(actions, user, encryptionUser, signatureKeyIdentifier, passwordCallback,
                    callback);
        }
    }
}
