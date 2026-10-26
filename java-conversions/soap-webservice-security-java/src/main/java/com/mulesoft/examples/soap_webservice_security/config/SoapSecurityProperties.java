/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * WS-Security material and Greeter client settings, bound from the {@code soap-security.*} keys of
 * {@code application.yml}.
 *
 * <p>The original example hard-coded these values: the WS-Security users of {@code PasswordCallback}, the store
 * password in {@code wssecurity.properties}, the SAML issuer key password in {@code saml.properties} and the
 * {@code HTTP_Request_Configuration} of {@code service-clients.xml} (host {@code 0.0.0.0}, port 63081, base path
 * {@code services}). No password has a default here, so credentials come only from configuration; the demo values
 * the committed keystore needs live in the {@code test} profile.
 *
 * <p>An empty value means "not configured". Binding never rejects one, so the application starts with every
 * credential unset and only the client types that need a missing value fail when called. Getters never return
 * {@code null}.
 *
 * <p>Registered by {@code @EnableConfigurationProperties} on the application class.
 */
@ConfigurationProperties("soap-security")
public class SoapSecurityProperties {

    /** WS-Security user name to password ({@code soap-security.users.<user>}). */
    private Map<String, String> users = new LinkedHashMap<>();

    private Keystore keystore = new Keystore();

    private Saml saml = new Saml();

    private Client client = new Client();

    public SoapSecurityProperties() {
    }

    /**
     * The configured WS-Security users and their passwords, in declaration order. A user whose password is
     * empty is declared but not configured.
     */
    public Map<String, String> getUsers() {
        return users;
    }

    public void setUsers(Map<String, String> users) {
        this.users = (users == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(users);
    }

    /**
     * The configured password of {@code user}, or empty when the user is unknown or has no password.
     */
    public Optional<String> passwordFor(String user) {
        if (user == null) {
            return Optional.empty();
        }
        String password = users.get(user);
        return (password == null || password.isEmpty()) ? Optional.empty() : Optional.of(password);
    }

    public Keystore getKeystore() {
        return keystore;
    }

    public void setKeystore(Keystore keystore) {
        this.keystore = (keystore == null) ? new Keystore() : keystore;
    }

    public Saml getSaml() {
        return saml;
    }

    public void setSaml(Saml saml) {
        this.saml = (saml == null) ? new Saml() : saml;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = (client == null) ? new Client() : client;
    }

    private static String emptyIfNull(String value) {
        return (value == null) ? "" : value;
    }

    /**
     * Merlin crypto of the signing, signature-verification, encryption and decryption steps
     * ({@code soap-security.keystore.*}).
     */
    public static class Keystore {

        private static final String DEFAULT_PROPERTIES = "wssecurity.properties";

        /** Merlin settings file; its store-password key is supplied from {@link #getPassword()} instead. */
        private Resource properties = new ClassPathResource(DEFAULT_PROPERTIES);

        /** Store password of {@code keystore.jks}; empty until supplied. */
        private String password = "";

        public Resource getProperties() {
            return properties;
        }

        /** A {@code null} resource restores the default {@code classpath:wssecurity.properties}. */
        public void setProperties(Resource properties) {
            this.properties = (properties == null) ? new ClassPathResource(DEFAULT_PROPERTIES) : properties;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = emptyIfNull(password);
        }
    }

    /**
     * SAML issuer settings of the {@code samlToken} and {@code samlTokenSigned} clients
     * ({@code soap-security.saml.*}).
     */
    public static class Saml {

        private static final String DEFAULT_PROPERTIES = "saml.properties";

        /** SAML issuer settings file; its issuer key-password key is supplied from {@link #getIssuerKeyPassword()}. */
        private Resource properties = new ClassPathResource(DEFAULT_PROPERTIES);

        /** Password of the issuer's signing key; empty until supplied. */
        private String issuerKeyPassword = "";

        public Resource getProperties() {
            return properties;
        }

        /** A {@code null} resource restores the default {@code classpath:saml.properties}. */
        public void setProperties(Resource properties) {
            this.properties = (properties == null) ? new ClassPathResource(DEFAULT_PROPERTIES) : properties;
        }

        public String getIssuerKeyPassword() {
            return issuerKeyPassword;
        }

        public void setIssuerKeyPassword(String issuerKeyPassword) {
            this.issuerKeyPassword = emptyIfNull(issuerKeyPassword);
        }
    }

    /**
     * Outbound Greeter client of the {@code /client} front ({@code soap-security.client.*}), replacing the
     * {@code HTTP_Request_Configuration} and the {@code user}/{@code encryptionUser} properties of the
     * {@code cxf:jaxws-client} sub-flows.
     */
    public static class Client {

        /** Host of the Greeter service; the port is the running SOAP listener's. */
        private String host = "0.0.0.0";

        /** Full service base URL; empty means {@code http://<host>:<SOAP listener port>/services}. */
        private String baseUrl = "";

        /** UsernameToken and signature user of the secured client types. */
        private String user = "joe";

        /** Certificate alias the {@code usernameTokenEncrypted} client encrypts for. */
        private String encryptionUser = "joe";

        /** Connect and read timeout in milliseconds, the Mule default response timeout; 0 waits indefinitely. */
        private int responseTimeout = 10000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = emptyIfNull(host);
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = emptyIfNull(baseUrl);
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = emptyIfNull(user);
        }

        public String getEncryptionUser() {
            return encryptionUser;
        }

        public void setEncryptionUser(String encryptionUser) {
            this.encryptionUser = emptyIfNull(encryptionUser);
        }

        public int getResponseTimeout() {
            return responseTimeout;
        }

        /**
         * @throws IllegalArgumentException for a negative value, which no HTTP connection accepts as a timeout
         */
        public void setResponseTimeout(int responseTimeout) {
            if (responseTimeout < 0) {
                throw new IllegalArgumentException(
                        "soap-security.client.response-timeout must not be negative: " + responseTimeout);
            }
            this.responseTimeout = responseTimeout;
        }
    }
}
