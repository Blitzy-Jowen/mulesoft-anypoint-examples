/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.CryptoFactory;
import org.apache.wss4j.common.crypto.Merlin;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import com.mulesoft.examples.soap_webservice_security.config.PathScopedSecurityInterceptor.PathPolicy;

/**
 * WS-Security wiring of the six Greeter paths and their clients.
 *
 * <p>Replaces the six {@code cxf:ws-security} blocks of the original {@code mule-config.xml}, whose
 * {@code signaturePropFile} and {@code decryptionPropFile} both named {@code wssecurity.properties} and whose
 * {@code passwordCallbackClass} was {@code PasswordCallback}, and the Spring beans {@code samlCustomValidator}
 * ({@code mule-config.xml}) and {@code samlCallbackHandler} ({@code service-clients.xml}).
 *
 * <p>Every credential comes from {@code soap-security.*} configuration, and nothing here touches the keystore while
 * the context starts: the Merlin crypto is built on first use by {@link LazyMerlinCrypto}, so the application starts
 * with every credential placeholder of {@code application.yml} empty, and only the paths and client types that need
 * the keystore fail until its password is supplied.
 *
 * <p>No {@code @EnableWs}: it would switch off Boot's {@code WebServicesAutoConfiguration} and leave no
 * {@code MessageDispatcherServlet}. {@link SoapSecurityProperties} is registered by the application class.
 */
@Configuration(proxyBeanMethods = false)
public class WsSecurityConfig {

    static final String UNSECURE_PATH = "/services/unsecure";
    static final String USERNAME_PATH = "/services/username";
    static final String SIGNED_PATH = "/services/signed";
    static final String ENCRYPTED_PATH = "/services/encrypted";
    static final String SAML_PATH = "/services/saml";
    static final String SIGNED_SAML_PATH = "/services/signedsaml";

    /**
     * The Merlin crypto of {@code soap-security.keystore.*}: the signature-verification and decryption crypto of the
     * inbound paths and the signing, encryption and SAML issuer crypto of the clients. It is the only
     * {@code Supplier<Crypto>} bean.
     */
    @Bean
    public LazyMerlinCrypto merlinCrypto(SoapSecurityProperties properties) {
        return new LazyMerlinCrypto(properties.getKeystore().getProperties(), properties.getKeystore().getPassword());
    }

    /**
     * The original {@code passwordCallbackClass}: the expected UsernameToken passwords of the configured users, and
     * the private-key password of keystore alias {@code joe} (joe's user password) for decryption and signing.
     */
    @Bean
    public PasswordCallback passwordCallback(SoapSecurityProperties properties) {
        return new PasswordCallback(properties.getUsers());
    }

    /** The original {@code saml2-token-validator} bean of {@code /services/saml} and {@code /services/signedsaml}. */
    @Bean
    public SAMLCustomValidator samlCustomValidator() {
        return new SAMLCustomValidator();
    }

    /**
     * The original {@code samlCallbackClass} of the {@code samlToken} and {@code samlTokenSigned} clients, built for
     * the configured SAML properties file ({@code classpath:saml.properties} by default).
     */
    @Bean
    public SAMLCallbackHandler samlCallbackHandler(SoapSecurityProperties properties, LazyMerlinCrypto merlinCrypto) {
        return new SAMLCallbackHandler(properties.getSaml().getProperties(),
                properties.getSaml().getIssuerKeyPassword(), merlinCrypto);
    }

    /**
     * The required WS-Security action set of each Greeter path, keyed by the request path without the context path,
     * as the original {@code cxf:ws-config} {@code action} properties declared them.
     */
    @Bean
    public Map<String, PathPolicy> pathPolicies(PasswordCallback passwordCallback,
            SAMLCustomValidator samlCustomValidator, LazyMerlinCrypto merlinCrypto) {
        Map<String, PathPolicy> policies = new LinkedHashMap<>();
        policies.put(UNSECURE_PATH, PathPolicy.UNSECURED);
        policies.put(USERNAME_PATH,
                PathPolicy.of("UsernameToken Timestamp", passwordCallback, null, null, null));
        policies.put(SIGNED_PATH,
                PathPolicy.of("UsernameToken Signature Timestamp", passwordCallback, merlinCrypto, null, null));
        // The password callback also supplies the private-key password of alias joe, which decryption needs.
        policies.put(ENCRYPTED_PATH,
                PathPolicy.of("UsernameToken Timestamp Encrypt", passwordCallback, null, merlinCrypto, null));
        // Compatibility fallback: SAML subject-confirmation validation is off on this path only. WSS4J 4 rejects the
        // original unsigned sender-vouches request ("Assertion fails sender-vouches requirements") because no message
        // signature covers the assertion, a check WSS4J 1.6 under CXF 2.7 did not make. Every SAMLCustomValidator
        // check (issuer self, SAML 2.0, sender-vouches, subject AllowGreetingServices) still runs.
        policies.put(SAML_PATH,
                new PathPolicy("SAMLTokenUnsigned Timestamp", null, null, null, samlCustomValidator, false, false));
        policies.put(SIGNED_SAML_PATH,
                PathPolicy.of("SAMLTokenUnsigned Signature", null, merlinCrypto, null, samlCustomValidator));
        return Collections.unmodifiableMap(policies);
    }

    /**
     * Inbound enforcement of {@link #pathPolicies}; run once per request, before endpoint selection, by the message
     * receiver of {@code WebServiceConfig}.
     */
    @Bean
    public PathScopedSecurityInterceptor pathScopedSecurityInterceptor(
            @Qualifier("pathPolicies") Map<String, PathPolicy> pathPolicies) {
        return new PathScopedSecurityInterceptor(pathPolicies);
    }

    /**
     * A Merlin crypto loaded from a properties resource on first use and kept afterwards.
     *
     * <p>The store password is not read from the file: it is supplied to the constructor from configuration and
     * written under the Merlin key prefix the file uses. The carried {@code wssecurity.properties} keeps the WSS4J 1.6
     * keys ({@code org.apache.ws.security.crypto.merlin.*}, provider
     * {@code org.apache.ws.security.components.crypto.Merlin}), which WSS4J 4 still reads; Merlin picks one prefix
     * from the keys present, so a password under the other prefix would be ignored.
     *
     * <p>No revocation, CRL or certificate constraint is configured: the demo keystore's single self-signed
     * {@code joe} certificate (RSA-1024, MD5withRSA) is trusted directly because the keystore holds it, as WSS4J 1.6
     * trusted it, while Java 17 certificate-path building would reject its MD5 signature.
     *
     * <p>Thread-safe. A failed load, including an unconfigured password, is not remembered, so a later call retries.
     */
    public static final class LazyMerlinCrypto implements Supplier<Crypto> {

        private static final String LEGACY_PASSWORD_KEY = Merlin.OLD_PREFIX + Merlin.KEYSTORE_PASSWORD;

        private static final String PASSWORD_KEY = Merlin.PREFIX + Merlin.KEYSTORE_PASSWORD;

        private final Resource cryptoProperties;

        private final String keystorePassword;

        private volatile Crypto crypto;

        /**
         * @param cryptoProperties Merlin settings without the store password ({@code wssecurity.properties})
         * @param keystorePassword store password of the keystore the settings name; {@code null} or empty when not
         *                         configured
         * @throws NullPointerException if {@code cryptoProperties} is {@code null}
         */
        public LazyMerlinCrypto(Resource cryptoProperties, String keystorePassword) {
            this.cryptoProperties = Objects.requireNonNull(cryptoProperties, "cryptoProperties");
            this.keystorePassword = keystorePassword;
        }

        /**
         * @return the loaded crypto, the same instance on every call once loaded
         * @throws IllegalStateException when {@code soap-security.keystore.password} is not configured, or the
         *                               properties or the keystore cannot be loaded
         */
        @Override
        public Crypto get() {
            Crypto loaded = crypto;
            if (loaded == null) {
                synchronized (this) {
                    loaded = crypto;
                    if (loaded == null) {
                        loaded = load();
                        crypto = loaded;
                    }
                }
            }
            return loaded;
        }

        private Crypto load() {
            if (keystorePassword == null || keystorePassword.isEmpty()) {
                throw new IllegalStateException("soap-security.keystore.password is not configured");
            }
            try {
                Properties properties = PropertiesLoaderUtils.loadProperties(cryptoProperties);
                properties.setProperty(passwordKey(properties), keystorePassword);
                // The class's own loader, not the calling thread's: the crypto is built on whichever request thread
                // first needs it, and Merlin resolves the keystore file through this loader.
                return CryptoFactory.getInstance(properties, LazyMerlinCrypto.class.getClassLoader(), null);
            } catch (IOException | WSSecurityException ex) {
                throw new IllegalStateException(
                        "Cannot load the WS-Security crypto from " + cryptoProperties.getDescription(), ex);
            }
        }

        /** The WSS4J 2+ key only when the file uses that prefix alone; otherwise the WSS4J 1.6 key it carries. */
        private static String passwordKey(Properties properties) {
            boolean currentPrefix = false;
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(Merlin.OLD_PREFIX)) {
                    return LEGACY_PASSWORD_KEY;
                }
                currentPrefix |= key.startsWith(Merlin.PREFIX);
            }
            return currentPrefix ? PASSWORD_KEY : LEGACY_PASSWORD_KEY;
        }
    }
}
