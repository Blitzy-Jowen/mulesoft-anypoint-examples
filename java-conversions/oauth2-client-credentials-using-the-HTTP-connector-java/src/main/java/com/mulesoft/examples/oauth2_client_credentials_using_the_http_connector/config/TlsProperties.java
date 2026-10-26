/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Key store and trust store of the Box HTTPS resource call, replacing the {@code tls:context}
 * {@code TLS_Context} of {@code http-client-credentials.xml} lines 25-28: the trust store of line 26
 * ({@code truststore.path}, {@code truststore.password}) and the key store of line 27
 * ({@code keystore.path}, {@code keystore.password}, {@code keystore.key.password}).
 *
 * <p>Only the Box resource request uses it, as only {@code HTTP_Request_Configuration} (lines 12-13,
 * HTTPS to {@code app.box.com:443}) references {@code TLS_Context}. The token request of line 16 and the
 * FIM request configuration of line 29 carry no TLS context, so they use the JVM-default TLS.
 *
 * <p>The original key store declares no {@code alias}, so the key managers select no alias. Both stores
 * are JKS. A path is looked up on the classpath first, then on the file system, so the defaults
 * {@code keystore.jks} and {@code trust-store} (the values the original README sets) resolve to the
 * stores carried in {@code src/main/resources}.
 *
 * <p>The repository holds no password for either store: the three password keys default to empty and
 * are supplied through the environment variables {@code application.yml} names. Construction therefore
 * only records the values; the application context starts with every password blank, and the stores are
 * read only when {@link #createSslContext()} is called. No password value ever appears in a message.
 */
@Component
public class TlsProperties {

    private static final String KEYSTORE_PATH_KEY = "keystore.path";
    private static final String KEYSTORE_PASSWORD_KEY = "keystore.password";
    private static final String KEY_PASSWORD_KEY = "keystore.key.password";
    private static final String TRUSTSTORE_PATH_KEY = "truststore.path";
    private static final String TRUSTSTORE_PASSWORD_KEY = "truststore.password";

    private static final String STORE_TYPE = "JKS";
    private static final String PROTOCOL = "TLS";

    private final String keystorePath;
    private final String keystorePassword;
    private final String keyPassword;
    private final String truststorePath;
    private final String truststorePassword;

    /**
     * Records the TLS configuration values without reading or checking any of them.
     *
     * @param keystorePath       {@code keystore.path}: classpath resource or file of the JKS key store
     * @param keystorePassword   {@code keystore.password}: store password of the key store
     * @param keyPassword        {@code keystore.key.password}: password of the private key in the key store
     * @param truststorePath     {@code truststore.path}: classpath resource or file of the JKS trust store
     * @param truststorePassword {@code truststore.password}: store password of the trust store
     */
    public TlsProperties(@Value("${keystore.path:keystore.jks}") String keystorePath,
                         @Value("${keystore.password:}") String keystorePassword,
                         @Value("${keystore.key.password:}") String keyPassword,
                         @Value("${truststore.path:trust-store}") String truststorePath,
                         @Value("${truststore.password:}") String truststorePassword) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keyPassword = keyPassword;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
    }

    /**
     * Builds a new {@code TLS} context from the configured key store and trust store. Every call reads
     * the stores again; the caller caches the result.
     *
     * <p>Checks run in a fixed order, so the first missing or wrong value is the one reported: the three
     * passwords ({@code keystore.password}, {@code keystore.key.password}, {@code truststore.password}),
     * then both paths (key store first), then loading the key store, its key managers, the trust store,
     * its trust managers, and the context itself.
     *
     * @return an initialized {@link SSLContext} whose key managers present the key store's key and whose
     *         trust managers trust the trust store's certificates
     * @throws IllegalStateException naming the configuration key at fault when a password is blank, a
     *                               path resolves to neither a classpath resource nor a regular file, or a
     *                               store, key or context cannot be loaded or initialized; the underlying
     *                               exception, where there is one, is kept as the cause
     */
    public SSLContext createSslContext() {
        requireSet(keystorePassword, KEYSTORE_PASSWORD_KEY, "the store password of the key store");
        requireSet(keyPassword, KEY_PASSWORD_KEY, "the password of the private key in the key store");
        requireSet(truststorePassword, TRUSTSTORE_PASSWORD_KEY, "the store password of the trust store");

        char[] keyStorePasswordChars = keystorePassword.toCharArray();
        char[] keyPasswordChars = keyPassword.toCharArray();
        char[] trustStorePasswordChars = truststorePassword.toCharArray();
        try (InputStream keyStoreIn = open(keystorePath, KEYSTORE_PATH_KEY);
             InputStream trustStoreIn = open(truststorePath, TRUSTSTORE_PATH_KEY)) {
            KeyStore keyStore;
            try {
                keyStore = loadJks(keyStoreIn, keyStorePasswordChars);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("TLS configuration: cannot load the JKS key store '" + keystorePath
                        + "' of key '" + KEYSTORE_PATH_KEY + "' with the password of key '"
                        + KEYSTORE_PASSWORD_KEY + "'", e);
            }

            KeyManagerFactory keyManagers;
            try {
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, keyPasswordChars);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("TLS configuration: cannot recover the private key of the key store '"
                        + keystorePath + "' with the password of key '" + KEY_PASSWORD_KEY + "'", e);
            }

            KeyStore trustStore;
            try {
                trustStore = loadJks(trustStoreIn, trustStorePasswordChars);
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("TLS configuration: cannot load the JKS trust store '" + truststorePath
                        + "' of key '" + TRUSTSTORE_PATH_KEY + "' with the password of key '"
                        + TRUSTSTORE_PASSWORD_KEY + "'", e);
            }

            TrustManagerFactory trustManagers;
            try {
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trustStore);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("TLS configuration: cannot initialize trust managers from the "
                        + "trust store '" + truststorePath + "' of key '" + TRUSTSTORE_PATH_KEY + "'", e);
            }

            try {
                SSLContext context = SSLContext.getInstance(PROTOCOL);
                context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
                return context;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("TLS configuration: cannot initialize a " + PROTOCOL
                        + " context from the key store '" + keystorePath + "' of key '" + KEYSTORE_PATH_KEY
                        + "' and the trust store '" + truststorePath + "' of key '" + TRUSTSTORE_PATH_KEY + "'", e);
            }
        } catch (IOException e) {
            // Only closing a store stream reaches here; every read failure above is already wrapped.
            throw new IllegalStateException("TLS configuration: cannot close the key store '" + keystorePath
                    + "' of key '" + KEYSTORE_PATH_KEY + "' or the trust store '" + truststorePath + "' of key '"
                    + TRUSTSTORE_PATH_KEY + "'", e);
        } finally {
            Arrays.fill(keyStorePasswordChars, '\0');
            Arrays.fill(keyPasswordChars, '\0');
            Arrays.fill(trustStorePasswordChars, '\0');
        }
    }

    private static void requireSet(String value, String key, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("TLS configuration key '" + key + "' is not set: supply " + what);
        }
    }

    /**
     * Opens the store at {@code path}: the classpath resource of that name if there is one, otherwise the
     * regular file at that path.
     */
    private static InputStream open(String path, String key) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("TLS configuration key '" + key
                    + "' is not set: supply the path of the store");
        }
        ClassPathResource resource = new ClassPathResource(path);
        try {
            if (resource.exists()) {
                return resource.getInputStream();
            }
            Path file = Path.of(path);
            if (Files.isRegularFile(file)) {
                return Files.newInputStream(file);
            }
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException covers InvalidPathException for a value that is no file-system path.
            throw new IllegalStateException("TLS configuration key '" + key + "' points to '" + path
                    + "', which cannot be opened", e);
        }
        throw new IllegalStateException("TLS configuration key '" + key + "' points to '" + path
                + "', which is neither a classpath resource nor a regular file");
    }

    private static KeyStore loadJks(InputStream in, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(STORE_TYPE);
        store.load(in, password);
        return store;
    }
}
