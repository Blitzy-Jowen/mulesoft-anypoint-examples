/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the OAuth 2.0 provider, its single registered client and its resource owner, bound
 * from the {@code oauth.provider.*} keys of {@code application.yml}.
 *
 * <p>The original example hard-codes these values in {@code http-oauth-provider.xml}: the
 * {@code oauth2-provider:config} provider name and scopes (line 26), the client registration (line 28)
 * with its redirect URI (line 30), grant type (line 33) and scopes (lines 36-37), the scope
 * {@code /resources} validates (line 46) and the {@code ss:user-service} resource owner (line 16).
 * Non-secret values keep the original literals as defaults in {@code application.yml}. The client
 * credentials and the owner credentials are never written here: they come only from the empty
 * {@code ${ENV:}} placeholders of {@code application.yml} and stay empty until supplied, the original
 * demo values being given only by the {@code test} profile. A caller can never override them.
 *
 * <p>The record only carries the values. Every {@code null} {@code String} is normalised to {@code ""},
 * every {@code null} list to an empty list and every bound list to an immutable copy, so consumers never
 * test for {@code null}; scope checks, redirect-URI wildcard matching, TTL arithmetic and owner
 * authentication belong to the services that inject it. It is registered solely by
 * {@code @EnableConfigurationProperties} on the application class and is constructor-bound.
 *
 * @param providerName                {@code provider-name}, the provider module's {@code providerName}
 *                                    (xml line 26)
 * @param clientId                    {@code client-id}, the registered client's {@code clientId}
 *                                    (xml line 28); secret, no default
 * @param clientSecret                {@code client-secret}, the registered client's {@code secret}
 *                                    (xml line 28); secret, no default
 * @param clientName                  {@code client-name}, the client's {@code clientName} (xml line 28)
 * @param clientDescription           {@code client-description}, the client's {@code description}
 *                                    (xml line 28)
 * @param clientType                  {@code client-type}, the client's {@code type}, {@code CONFIDENTIAL}
 *                                    in the original (xml line 28)
 * @param redirectUris                {@code redirect-uris}, the client's redirect-URI patterns, a trailing
 *                                    {@code *} being a wildcard (xml line 30)
 * @param authorizedGrantTypes        {@code authorized-grant-types}, the grant types the client may use
 *                                    (xml line 33)
 * @param scopes                      {@code scopes}, the scopes the provider and the client declare
 *                                    (xml lines 26, 36-37)
 * @param resourceScope               {@code resource-scope}, the scope a token must carry for
 *                                    {@code /resources} (xml line 46)
 * @param tokenTtlSeconds             {@code token-ttl-seconds}, access-token lifetime in seconds, the
 *                                    provider module's default
 * @param authorizationCodeTtlSeconds {@code authorization-code-ttl-seconds}, authorization-code lifetime
 *                                    in seconds, the provider module's default
 * @param owner                       {@code owner}, the resource owner the authorization page
 *                                    authenticates (xml line 16)
 */
@ConfigurationProperties(prefix = "oauth.provider")
public record OAuthProviderProperties(
        String providerName,
        String clientId,
        String clientSecret,
        String clientName,
        String clientDescription,
        String clientType,
        List<String> redirectUris,
        List<String> authorizedGrantTypes,
        List<String> scopes,
        String resourceScope,
        long tokenTtlSeconds,
        long authorizationCodeTtlSeconds,
        Owner owner) {

    public OAuthProviderProperties {
        providerName = providerName == null ? "" : providerName;
        clientId = clientId == null ? "" : clientId;
        clientSecret = clientSecret == null ? "" : clientSecret;
        clientName = clientName == null ? "" : clientName;
        clientDescription = clientDescription == null ? "" : clientDescription;
        clientType = clientType == null ? "" : clientType;
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        authorizedGrantTypes = authorizedGrantTypes == null ? List.of() : List.copyOf(authorizedGrantTypes);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        resourceScope = resourceScope == null ? "" : resourceScope;
        owner = owner == null ? new Owner("", "", List.of()) : owner;
    }

    /**
     * The resource owner of the original's {@code ss:user-service} (xml line 16), bound from
     * {@code oauth.provider.owner.*}.
     *
     * <p>The username and password come only from the empty {@code ${ENV:}} placeholders of
     * {@code application.yml} and stay empty until supplied; the original demo values are given only by
     * the {@code test} profile. {@code null} values are normalised as in the enclosing record.
     *
     * @param username    {@code owner.username}; secret, no default
     * @param password    {@code owner.password}; secret, no default
     * @param authorities {@code owner.authorities}, {@code RESOURCE_OWNER} in the original
     */
    public record Owner(String username, String password, List<String> authorities) {

        public Owner {
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            authorities = authorities == null ? List.of() : List.copyOf(authorities);
        }
    }
}
