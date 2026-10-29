/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties;

/**
 * Authenticates the resource owner who signs in on the provider's {@code /authorize} login page.
 *
 * <p>It replaces lines 13-24 of the original {@code http-oauth-provider.xml}: the Spring Security
 * {@code ss:authentication-manager} with its in-memory {@code ss:user-service} holding one user with the
 * authority {@code RESOURCE_OWNER} (lines 13-19), and the {@code mule-ss:security-manager} that delegated to it
 * (lines 22-24) as the {@code resourceOwnerSecurityProvider-ref} of {@code oauth2-provider:config} (line 26).
 * It is that owner check and nothing more: no other user, role or authentication step is added.
 *
 * <p>The accepted username, password and authorities come only from {@code oauth.provider.owner.*}
 * ({@link OAuthProviderProperties.Owner}). The check fails closed: a missing or blank supplied credential, or a
 * blank configured one, rejects the attempt, so the application authenticates no one until the
 * {@code oauth.provider.owner.username} and {@code oauth.provider.owner.password} placeholders are supplied.
 * Credential values are never logged.
 *
 * <p>The instance holds no mutable state and is safe for concurrent use.
 */
@Service
public class ResourceOwnerAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ResourceOwnerAuthenticator.class);

    private final OAuthProviderProperties props;

    /**
     * @param props provider configuration whose {@code owner} holds the accepted resource owner
     * @throws NullPointerException if {@code props} is {@code null}
     */
    public ResourceOwnerAuthenticator(OAuthProviderProperties props) {
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * Checks the credentials posted on the login page against the configured resource owner.
     *
     * <p>Both values are compared exactly, case-sensitively and without trimming, as the in-memory user
     * service compared them.
     *
     * @param username the submitted username
     * @param password the submitted password
     * @return {@code true} only when both values equal the configured owner's; {@code false} when either
     *         submitted value is {@code null} or blank, when the configured username or password is blank, or
     *         when either value differs
     */
    public boolean authenticate(String username, String password) {
        OAuthProviderProperties.Owner owner = props.owner();
        String expectedUser = owner == null ? null : owner.username();
        String expectedPassword = owner == null ? null : owner.password();

        if (isBlank(expectedUser) || isBlank(expectedPassword)) {
            log.debug("Resource owner authentication rejected: oauth.provider.owner credentials are not configured");
            return false;
        }
        if (isBlank(username) || isBlank(password)) {
            log.debug("Resource owner authentication failed");
            return false;
        }

        // Both comparisons always run and are combined with the non-short-circuit '&', so the response time
        // does not reveal which of the two values was wrong.
        boolean userOk = MessageDigest.isEqual(
                username.getBytes(StandardCharsets.UTF_8), expectedUser.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8), expectedPassword.getBytes(StandardCharsets.UTF_8));
        boolean authenticated = userOk & passOk;
        if (!authenticated) {
            log.debug("Resource owner authentication failed");
        }
        return authenticated;
    }

    /**
     * The authorities granted to the authenticated resource owner, {@code RESOURCE_OWNER} in the original
     * {@code ss:user} (xml line 16).
     *
     * @return the configured {@code oauth.provider.owner.authorities}, unmodifiable; empty when none are configured
     */
    public List<String> authorities() {
        OAuthProviderProperties.Owner owner = props.owner();
        return owner == null ? List.of() : owner.authorities();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
