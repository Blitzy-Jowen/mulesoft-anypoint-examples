/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Box OAuth 2.0 and TLS configuration of {@code http-authorization-code-web.xml}, the single holder of every
 * value the Box client, the grant logic and the controllers read:
 * <ul>
 *   <li>{@code oauth2:authorization-code-grant-type} (line 6): {@code clientId="${box.id}"},
 *       {@code clientSecret="${box.secret}"} and {@code redirectionUrl};</li>
 *   <li>{@code oauth2:authorization-request} (line 7): {@code authorizationUrl} and
 *       {@code localAuthorizationUrl};</li>
 *   <li>{@code oauth2:token-request} (line 13): {@code tokenUrl};</li>
 *   <li>{@code HTTP_Request_Configuration_HTTPS} (line 5): HTTPS, {@code api.box.com}, port 443, base path
 *       {@code /2.0};</li>
 *   <li>{@code boxUserLoginFlow} (line 30): the {@code Location} of its 302 response;</li>
 *   <li>{@code TLS_Context} (lines 21-24), which both the Box request configuration and the grant's token
 *       request reference: trust store {@code trust-store} and key store {@code keystore.jks} with the
 *       {@code truststore.password}, {@code keystore.password} and {@code keystore.keyPassword} placeholders.</li>
 * </ul>
 *
 * <p>Every key keeps its original placeholder name. The client identity and the three store passwords have no
 * value in the repository and default to empty; they come only from configuration, so the class has no setter.
 * Passwords have no getter, appear in no exception message, and {@link Object#toString()} is not overridden.
 *
 * <p>The client identity is not checked at construction either, so the application starts without it. Each
 * operation that sends it first asks {@link #firstMissing(ClientCredential...)} for the credentials it needs and
 * fails locally, naming the missing key, rather than sending Box an empty value.
 *
 * <p>The {@link SSLContext} of {@code TLS_Context} is built on the first {@link #sslContext()} call, never at
 * construction: loading a JKS store with an empty or wrong password fails, and the application context must
 * be creatable without credentials, as it is when every Box URL points at a plain-HTTP stub.
 */
@Component
public final class BoxProperties {

    private static final String STORE_TYPE = "JKS";
    private static final String PROTOCOL = "TLS";

    private final String id;
    private final String secret;
    private final String authorizationUrl;
    private final String localAuthorizationUrl;
    private final String redirectUrl;
    private final String tokenUrl;
    private final String apiBaseUrl;
    private final String loginRedirectLocation;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreKeyPassword;
    private final String truststorePath;
    private final String truststorePassword;

    private final Object sslContextLock = new Object();
    private volatile SSLContext sslContext;

    /**
     * Records the configuration without reading or checking any of it. A {@code null} argument is stored as the
     * empty string.
     *
     * <p>The three local URL defaults are the {@code application.yml} expressions, with the original ports
     * {@code 8082} ({@code ports.box-oauth}) and {@code 8081} ({@code server.port}) as nested fallbacks, so the
     * annotation and the file never disagree and both reduce to the flow's literals.
     *
     * @param id                    {@code box.id}: Box OAuth client ID
     * @param secret                {@code box.secret}: Box OAuth client secret
     * @param authorizationUrl      {@code box.authorization-url}: Box authorization endpoint
     * @param localAuthorizationUrl {@code box.local-authorization-url}: local URL that starts the grant
     * @param redirectUrl           {@code box.redirect-url}: redirection URL registered with Box
     * @param tokenUrl              {@code box.token-url}: Box token endpoint
     * @param apiBaseUrl            {@code box.api-base-url}: scheme, host, port and base path of the Box API
     * @param loginRedirectLocation {@code box.login-redirect-location}: {@code Location} of the {@code /web} 302
     * @param keystorePath          {@code keystore.path}: JKS key store of {@code TLS_Context}
     * @param keystorePassword      {@code keystore.password}: store password of the key store
     * @param keystoreKeyPassword   {@code keystore.keyPassword}: password of the private key in the key store
     * @param truststorePath        {@code truststore.path}: JKS trust store of {@code TLS_Context}
     * @param truststorePassword    {@code truststore.password}: store password of the trust store
     */
    public BoxProperties(
            @Value("${box.id:}") String id,
            @Value("${box.secret:}") String secret,
            @Value("${box.authorization-url:https://www.box.com/api/oauth2/authorize}") String authorizationUrl,
            @Value("${box.local-authorization-url:https://localhost:${ports.box-oauth:8082}/authorization}")
            String localAuthorizationUrl,
            @Value("${box.redirect-url:https://localhost:${ports.box-oauth:8082}/redirectUrl}") String redirectUrl,
            @Value("${box.token-url:https://www.box.com/api/oauth2/token}") String tokenUrl,
            @Value("${box.api-base-url:https://api.box.com:443/2.0}") String apiBaseUrl,
            @Value("${box.login-redirect-location:https://localhost:${ports.box-oauth:8082}/authorization"
                    + "?onCompleteRedirectTo=http://localhost:${server.port:8081}/web/loginDone}")
            String loginRedirectLocation,
            @Value("${keystore.path:keystore.jks}") String keystorePath,
            @Value("${keystore.password:}") String keystorePassword,
            @Value("${keystore.keyPassword:}") String keystoreKeyPassword,
            @Value("${truststore.path:trust-store}") String truststorePath,
            @Value("${truststore.password:}") String truststorePassword) {
        this.id = Objects.requireNonNullElse(id, "");
        this.secret = Objects.requireNonNullElse(secret, "");
        this.authorizationUrl = Objects.requireNonNullElse(authorizationUrl, "");
        this.localAuthorizationUrl = Objects.requireNonNullElse(localAuthorizationUrl, "");
        this.redirectUrl = Objects.requireNonNullElse(redirectUrl, "");
        this.tokenUrl = Objects.requireNonNullElse(tokenUrl, "");
        this.apiBaseUrl = Objects.requireNonNullElse(apiBaseUrl, "");
        this.loginRedirectLocation = Objects.requireNonNullElse(loginRedirectLocation, "");
        this.keystorePath = Objects.requireNonNullElse(keystorePath, "");
        this.keystorePassword = Objects.requireNonNullElse(keystorePassword, "");
        this.keystoreKeyPassword = Objects.requireNonNullElse(keystoreKeyPassword, "");
        this.truststorePath = Objects.requireNonNullElse(truststorePath, "");
        this.truststorePassword = Objects.requireNonNullElse(truststorePassword, "");
    }

    /** @return {@code box.id}, the Box OAuth client ID ({@code clientId}) */
    public String getId() {
        return id;
    }

    /** @return {@code box.secret}, the Box OAuth client secret ({@code clientSecret}) */
    public String getSecret() {
        return secret;
    }

    /**
     * The Box client credentials of {@code oauth2:authorization-code-grant-type} (line 6), in the order an operation
     * checks them. Each names its key and the message an operation fails with when the key has no value; neither
     * ever carries the value.
     */
    public enum ClientCredential {

        /** {@code box.id}, the client ID ({@code clientId="${box.id}"}). */
        ID("box.id", "Box client ID"),

        /** {@code box.secret}, the client secret ({@code clientSecret="${box.secret}"}). */
        SECRET("box.secret", "Box client secret");

        private final String key;
        private final String description;

        ClientCredential(String key, String description) {
            this.key = key;
            this.description = description;
        }

        /** @return the configuration key, {@code box.id} or {@code box.secret} */
        public String key() {
            return key;
        }

        /**
         * @return the failure message naming this key, {@code Box client ID (box.id) is not configured} or
         *         {@code Box client secret (box.secret) is not configured}
         */
        public String notConfiguredMessage() {
            return description + " (" + key + ") is not configured";
        }
    }

    /**
     * The first of {@code needed}, in the order given, whose key has no value: unset, empty or whitespace only, as
     * the empty defaults of {@code application.yml} leave both keys. Only the credential is returned, never its
     * value.
     *
     * @param needed the credentials an operation is about to send, in the order it reports them
     * @return the first missing credential, or empty when every one has a value
     */
    public Optional<ClientCredential> firstMissing(ClientCredential... needed) {
        for (ClientCredential credential : needed) {
            String value = credential == ClientCredential.ID ? id : secret;
            if (!StringUtils.hasText(value)) {
                return Optional.of(credential);
            }
        }
        return Optional.empty();
    }

    /** @return {@code box.authorization-url}, the Box authorization endpoint ({@code authorizationUrl}) */
    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    /** @return {@code box.local-authorization-url}, the local grant start URL ({@code localAuthorizationUrl}) */
    public String getLocalAuthorizationUrl() {
        return localAuthorizationUrl;
    }

    /** @return {@code box.redirect-url}, the redirection URL sent to Box ({@code redirectionUrl}) */
    public String getRedirectUrl() {
        return redirectUrl;
    }

    /** @return {@code box.token-url}, the Box token endpoint ({@code tokenUrl}) */
    public String getTokenUrl() {
        return tokenUrl;
    }

    /** @return {@code box.api-base-url}, the Box API base of {@code HTTP_Request_Configuration_HTTPS} */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /** @return {@code box.login-redirect-location}, the {@code Location} header of {@code boxUserLoginFlow} */
    public String getLoginRedirectLocation() {
        return loginRedirectLocation;
    }

    /**
     * Returns the {@code TLS_Context} as an {@link SSLContext}: key managers over the key store, trust managers
     * over the trust store only, so neither the JDK {@code cacerts} nor any trust-all manager is consulted. The
     * context is built on the first call and the same instance is returned afterwards; a failed build caches
     * nothing, so a later call tries again.
     *
     * @return the initialized {@code TLS} context
     * @throws IllegalStateException when a store cannot be found, opened or loaded, its private key cannot be
     *                               recovered, or the context cannot be initialized; the message names the store
     *                               path and role, never a password, and the underlying exception is the cause
     */
    public SSLContext sslContext() {
        SSLContext context = sslContext;
        if (context == null) {
            synchronized (sslContextLock) {
                context = sslContext;
                if (context == null) {
                    context = buildSslContext();
                    sslContext = context;
                }
            }
        }
        return context;
    }

    private SSLContext buildSslContext() {
        char[] keyStorePasswordChars = keystorePassword.toCharArray();
        char[] keyPasswordChars = keystoreKeyPassword.toCharArray();
        char[] trustStorePasswordChars = truststorePassword.toCharArray();
        try {
            KeyStore keyStore = loadStore(keystorePath, "key store", keyStorePasswordChars);
            KeyManagerFactory keyManagers;
            try {
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, keyPasswordChars);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot load key store '" + keystorePath
                        + "': its private key cannot be recovered with keystore.keyPassword", e);
            }

            KeyStore trustStore = loadStore(truststorePath, "trust store", trustStorePasswordChars);
            TrustManagerFactory trustManagers;
            try {
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trustStore);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot load trust store '" + truststorePath
                        + "': its trust managers cannot be initialized", e);
            }

            try {
                SSLContext context = SSLContext.getInstance(PROTOCOL);
                context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
                return context;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Cannot initialize a " + PROTOCOL + " context from key store '"
                        + keystorePath + "' and trust store '" + truststorePath + "'", e);
            }
        } finally {
            // The factories keep copies of what they need, so the password copies made here are cleared.
            Arrays.fill(keyStorePasswordChars, '\0');
            Arrays.fill(keyPasswordChars, '\0');
            Arrays.fill(trustStorePasswordChars, '\0');
        }
    }

    private static KeyStore loadStore(String path, String role, char[] password) {
        String failure = "Cannot load " + role + " '" + path + "'";
        if (path.isBlank()) {
            throw new IllegalStateException(failure + ": no path is configured");
        }
        try (InputStream in = resolve(path).getInputStream()) {
            KeyStore store = KeyStore.getInstance(STORE_TYPE);
            store.load(in, password);
            return store;
        } catch (IOException | GeneralSecurityException e) {
            // FileNotFoundException for a missing store lands here too.
            throw new IllegalStateException(failure, e);
        }
    }

    /**
     * Resolves a store path the way the HTTPS listener of the same {@code TLS_Context} does (config/ListenerPorts)
     * and as Mule resolved tls store paths, rather than on the classpath alone: a {@code classpath:} or
     * {@code file:} location as given, otherwise the readable file at that path, otherwise the classpath
     * resource of that name. The defaults {@code keystore.jks} and {@code trust-store} therefore resolve to the
     * stores carried in {@code src/main/resources} unless such a file sits in the working directory.
     */
    private static Resource resolve(String location) {
        if (ResourceUtils.isUrl(location)) {
            return new DefaultResourceLoader().getResource(location);
        }
        return isReadableFile(location) ? new FileSystemResource(location) : new ClassPathResource(location);
    }

    private static boolean isReadableFile(String location) {
        try {
            return new FileSystemResource(location).isReadable();
        } catch (InvalidPathException e) {
            // Not expressible as a file-system path, so only the classpath can hold it.
            return false;
        }
    }
}
