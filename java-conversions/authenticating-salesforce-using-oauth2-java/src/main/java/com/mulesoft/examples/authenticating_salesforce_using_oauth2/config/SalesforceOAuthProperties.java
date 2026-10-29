/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Salesforce OAuth 2.0 authorization-code settings, bound from the {@code sfdc.key}, {@code sfdc.secret} and
 * {@code sfdc.oauth.*} keys of {@code application.yml}.
 *
 * <p>Replaces the OAuth configuration of {@code salesforce-oauth.xml}:
 * <ul>
 *   <li>{@code <sfdc:config-with-oauth consumerKey="${sfdc.key}" consumerSecret="${sfdc.secret}">} &rarr;
 *       {@link #getKey()}, {@link #getSecret()};</li>
 *   <li>{@code <sfdc:oauth-callback-config domain="localhost" localPort="8081" path="oauth2callback"/>} &rarr;
 *       {@link Callback}, whose {@link Callback#redirectUri()} is the {@code redirect_uri} sent on both the
 *       authorize redirect and the code exchange;</li>
 *   <li>{@code <sfdc:authorize authorizationUrl="..." accessTokenUrl="..."/>} &rarr; {@link Oauth}.</li>
 * </ul>
 * The URLs and the callback are non-secret values the original fixed, so they are carried as field defaults, as is
 * the non-secret lifetime {@code sfdc.oauth.state-ttl} of a started authorization, which the conversion adds. The
 * consumer key and secret have no default and are never hard-coded; they come only from configuration
 * ({@code SFDC_KEY}, {@code SFDC_SECRET}, or the git-ignored {@code application-it.yml} of the live test), so the
 * application starts with both empty. {@link #toString()} masks the secret.
 *
 * <p>The {@code sfdc} prefix is shared with {@code SalesforceProperties}, which binds the Partner API keys
 * ({@code username}, {@code password}, {@code securityToken}, {@code authEndpoint}, timeouts). Unknown fields are
 * therefore ignored (the binder default), so each bean skips the other's keys.
 */
@Component
@ConfigurationProperties(prefix = "sfdc")
public class SalesforceOAuthProperties {

    private static final String MASK = "****";

    /** {@code sfdc.key}: the connected app's consumer key, sent as {@code client_id}. */
    private String key;

    /** {@code sfdc.secret}: the connected app's consumer secret, sent as {@code client_secret}. */
    private String secret;

    /** {@code sfdc.oauth.*}: authorization server URLs and the callback. */
    private Oauth oauth = new Oauth();

    /**
     * @return the consumer key, or {@code null} / empty when not configured
     */
    public String getKey() {
        return key;
    }

    /**
     * @param key the consumer key
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * @return the consumer secret, or {@code null} / empty when not configured
     */
    public String getSecret() {
        return secret;
    }

    /**
     * @param secret the consumer secret
     */
    public void setSecret(String secret) {
        this.secret = secret;
    }

    /**
     * @return the authorization server and callback settings
     */
    public Oauth getOauth() {
        return oauth;
    }

    /**
     * @param oauth the authorization server and callback settings
     */
    public void setOauth(Oauth oauth) {
        this.oauth = oauth;
    }

    /**
     * Describes the bound values with the secret masked: it appears as {@code ****} when set and as {@code null}
     * otherwise, so a log line shows whether the secret is configured but never its value.
     */
    @Override
    public String toString() {
        return "SalesforceOAuthProperties{key=" + key
                + ", secret=" + (secret == null || secret.isEmpty() ? null : MASK)
                + ", oauth=" + oauth
                + "}";
    }

    /**
     * The Salesforce authorization server endpoints of {@code sfdc:authorize}, the callback of
     * {@code sfdc:oauth-callback-config} and the lifetime of a started authorization's {@code state}.
     */
    public static class Oauth {

        /** {@code sfdc.oauth.authorization-url}: where the browser is redirected to log in and grant access. */
        private String authorizationUrl = "https://login.salesforce.com/services/oauth2/authorize";

        /** {@code sfdc.oauth.access-token-url}: where the code and refresh-token grants are posted. */
        private String accessTokenUrl = "https://login.salesforce.com/services/oauth2/token";

        /** {@code sfdc.oauth.callback.*}: the local callback Salesforce redirects back to. */
        private Callback callback = new Callback();

        /**
         * {@code sfdc.oauth.state-ttl}: how long the {@code state} of an authorization started at {@code /}, and the
         * browser cookie that carries it, stay valid for the callback. Not a key of the original, whose DevKit
         * connector handled the callback internally.
         */
        private Duration stateTtl = Duration.ofMinutes(10);

        /**
         * @return the authorization endpoint URL
         */
        public String getAuthorizationUrl() {
            return authorizationUrl;
        }

        /**
         * @param authorizationUrl the authorization endpoint URL; tests point it at a WireMock stub
         */
        public void setAuthorizationUrl(String authorizationUrl) {
            this.authorizationUrl = authorizationUrl;
        }

        /**
         * @return the token endpoint URL
         */
        public String getAccessTokenUrl() {
            return accessTokenUrl;
        }

        /**
         * @param accessTokenUrl the token endpoint URL; tests point it at a WireMock stub
         */
        public void setAccessTokenUrl(String accessTokenUrl) {
            this.accessTokenUrl = accessTokenUrl;
        }

        /**
         * @return the callback settings
         */
        public Callback getCallback() {
            return callback;
        }

        /**
         * @param callback the callback settings
         */
        public void setCallback(Callback callback) {
            this.callback = callback;
        }

        /**
         * @return how long a started authorization's {@code state} stays valid for the callback
         */
        public Duration getStateTtl() {
            return stateTtl;
        }

        /**
         * @param stateTtl how long a started authorization's {@code state} stays valid for the callback; must be
         *                 positive and at most 400 days, which the authorize step checks when it issues a state
         */
        public void setStateTtl(Duration stateTtl) {
            this.stateTtl = stateTtl;
        }

        @Override
        public String toString() {
            return "Oauth{authorizationUrl=" + authorizationUrl
                    + ", accessTokenUrl=" + accessTokenUrl
                    + ", callback=" + callback
                    + ", stateTtl=" + stateTtl
                    + "}";
        }
    }

    /**
     * The callback of {@code <sfdc:oauth-callback-config domain="localhost" localPort="8081" path="oauth2callback"/>},
     * served by this application's own {@code /oauth2callback} listener.
     */
    public static class Callback {

        /** {@code sfdc.oauth.callback.domain}: host name of the redirect URI. */
        private String domain = "localhost";

        /** {@code sfdc.oauth.callback.local-port}: port of the redirect URI. */
        private int localPort = 8081;

        /** {@code sfdc.oauth.callback.path}: path of the redirect URI, with or without a leading {@code /}. */
        private String path = "oauth2callback";

        /**
         * @return the callback host name
         */
        public String getDomain() {
            return domain;
        }

        /**
         * @param domain the callback host name
         */
        public void setDomain(String domain) {
            this.domain = domain;
        }

        /**
         * @return the callback port
         */
        public int getLocalPort() {
            return localPort;
        }

        /**
         * @param localPort the callback port
         */
        public void setLocalPort(int localPort) {
            this.localPort = localPort;
        }

        /**
         * @return the callback path as configured
         */
        public String getPath() {
            return path;
        }

        /**
         * @param path the callback path, with or without a leading {@code /}
         */
        public void setPath(String path) {
            this.path = path;
        }

        /**
         * Builds the redirect URI the Mule callback configuration registered,
         * {@code http://<domain>:<localPort>/<path>} ({@code http://localhost:8081/oauth2callback} with the defaults),
         * which is also the callback URL to register in the connected app. One leading {@code /} of {@code path} is dropped so that {@code /oauth2callback} and
         * {@code oauth2callback} yield the same URI; an unset path yields the root.
         *
         * <p>Named without a {@code get} prefix so that the binder treats it as derived, not as a property.
         *
         * @return the redirect URI sent as {@code redirect_uri}
         */
        public String redirectUri() {
            return "http://" + domain + ":" + localPort + requestPath();
        }

        /**
         * The path of {@link #redirectUri()}, {@code /<path>} with one leading {@code /} of {@code path} dropped first
         * ({@code /oauth2callback} with the defaults), so that {@code /oauth2callback} and {@code oauth2callback}
         * yield the same path; an unset path yields {@code /}. It is also the {@code Path} of the cookie that binds a
         * started authorization to the browser, so the browser sends that cookie to the callback alone.
         *
         * <p>Named without a {@code get} prefix so that the binder treats it as derived, not as a property.
         *
         * @return the callback's request path
         */
        public String requestPath() {
            String relative = path == null ? "" : path;
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return "/" + relative;
        }

        @Override
        public String toString() {
            return "Callback{domain=" + domain
                    + ", localPort=" + localPort
                    + ", path=" + path
                    + "}";
        }
    }
}
