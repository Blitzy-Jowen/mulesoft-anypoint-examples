/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties;
import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties.Owner;

/**
 * Unit test of {@link ResourceOwnerAuthenticator}, which replaces the {@code ss:authentication-manager} and its
 * in-memory {@code ss:user-service} of {@code http-oauth-provider.xml} (lines 13-24): one resource owner, compared
 * exactly and case-sensitively, holding the authority {@code RESOURCE_OWNER}.
 *
 * <p>It covers the accepted owner; every rejected attempt (wrong, case-changed, truncated, extended or padded
 * values, and {@code null}, empty or blank submissions); the fail-closed rejection while the configured owner is
 * blank or absent; and the configured authorities. The original demo values are built here in code, so no Spring
 * context, profile or network is involved.
 */
class ResourceOwnerAuthenticatorTest {

    /** The original {@code ss:user} (xml line 16), supplied in production through {@code oauth.provider.owner.*}. */
    private static final Owner DEMO_OWNER = new Owner("mule", "mule", List.of("RESOURCE_OWNER"));

    /** An owner other than the demo one, showing that only configuration decides who is accepted. */
    private static final Owner OTHER_OWNER = new Owner("alice", "s3cret", List.of("RESOURCE_OWNER"));

    /**
     * Provider configuration of the original {@code oauth2-provider:config} and its client (xml lines 26-37) with
     * the given resource owner.
     */
    private static OAuthProviderProperties properties(Owner owner) {
        return new OAuthProviderProperties(
                "SampleAPI",
                "myclientid",
                "myclientsecret",
                "Mule Bookstore",
                "Mule-powered On-line Bookstore",
                "CONFIDENTIAL",
                List.of("http://localhost*"),
                List.of("AUTHORIZATION_CODE"),
                List.of("READ_RESOURCE", "POST_RESOURCE"),
                "READ_RESOURCE",
                86400L,
                600L,
                owner);
    }

    private static ResourceOwnerAuthenticator authenticator(Owner owner) {
        return new ResourceOwnerAuthenticator(properties(owner));
    }

    static Stream<Arguments> acceptedAttempts() {
        return Stream.of(
                Arguments.of("configured demo owner", DEMO_OWNER, "mule", "mule"),
                Arguments.of("configured owner other than the demo one", OTHER_OWNER, "alice", "s3cret"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedAttempts")
    void configuredCredentialsAreAccepted(String scenario, Owner owner, String username, String password) {
        assertThat(authenticator(owner).authenticate(username, password)).isTrue();
    }

    static Stream<Arguments> rejectedAttempts() {
        return Stream.of(
                Arguments.of("wrong username", "admin", "mule"),
                Arguments.of("wrong password", "mule", "wrong-pw"),
                Arguments.of("both values wrong", "admin", "wrong-pw"),
                Arguments.of("credentials of an owner that is not configured", "alice", "s3cret"),
                Arguments.of("username differing only in case", "Mule", "mule"),
                Arguments.of("password differing only in case", "mule", "MULE"),
                Arguments.of("username a prefix of the configured one", "mul", "mule"),
                Arguments.of("password a prefix of the configured one", "mule", "mul"),
                Arguments.of("username extending the configured one", "mulex", "mule"),
                Arguments.of("password extending the configured one", "mule", "mulex"),
                Arguments.of("username with leading space, not trimmed", " mule", "mule"),
                Arguments.of("password with trailing space, not trimmed", "mule", "mule "),
                Arguments.of("null username", null, "mule"),
                Arguments.of("null password", "mule", null),
                Arguments.of("both null", null, null),
                Arguments.of("empty username", "", "mule"),
                Arguments.of("empty password", "mule", ""),
                Arguments.of("both empty", "", ""),
                Arguments.of("blank username", "   ", "mule"),
                Arguments.of("blank password", "mule", "\t"),
                Arguments.of("both blank", " ", " "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAttempts")
    void otherCredentialsAreRejected(String scenario, String username, String password) {
        assertThat(authenticator(DEMO_OWNER).authenticate(username, password)).isFalse();
    }

    static Stream<Arguments> unconfiguredOwners() {
        return Stream.of(
                Arguments.of("empty owner, demo values submitted", new Owner("", "", List.of()), "mule", "mule"),
                Arguments.of("empty owner, empty values submitted", new Owner("", "", List.of()), "", ""),
                Arguments.of("unset owner normalised to empty, demo values submitted",
                        new Owner(null, null, null), "mule", "mule"),
                Arguments.of("unset owner normalised to empty, null values submitted",
                        new Owner(null, null, null), null, null),
                Arguments.of("empty configured password only, empty password submitted",
                        new Owner("mule", "", List.of("RESOURCE_OWNER")), "mule", ""),
                Arguments.of("empty configured username only, empty username submitted",
                        new Owner("", "mule", List.of("RESOURCE_OWNER")), "", "mule"),
                Arguments.of("blank configured password only, same blank submitted",
                        new Owner("mule", "  ", List.of("RESOURCE_OWNER")), "mule", "  "),
                Arguments.of("blank configured username only, same blank submitted",
                        new Owner("\t", "mule", List.of("RESOURCE_OWNER")), "\t", "mule"),
                Arguments.of("blank configured owner, same blanks submitted",
                        new Owner(" ", " ", List.of("RESOURCE_OWNER")), " ", " "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfiguredOwners")
    void blankConfiguredOwnerFailsClosed(String scenario, Owner owner, String username, String password) {
        assertThat(authenticator(owner).authenticate(username, password)).isFalse();
    }

    @Test
    void absentOwnerIsNormalisedToAnEmptyOwnerThatAuthenticatesNoOne() {
        OAuthProviderProperties props = properties(null);
        ResourceOwnerAuthenticator authenticator = new ResourceOwnerAuthenticator(props);

        assertThat(props.owner()).isEqualTo(new Owner("", "", List.of()));
        assertThat(authenticator.authenticate("mule", "mule")).isFalse();
        assertThat(authenticator.authenticate("", "")).isFalse();
        assertThat(authenticator.authenticate(null, null)).isFalse();
        assertThat(authenticator.authorities()).isEmpty();
    }

    @Test
    void authoritiesAreTheConfiguredOnesAndUnmodifiable() {
        List<String> authorities = authenticator(DEMO_OWNER).authorities();

        assertThat(authorities).isEqualTo(List.of("RESOURCE_OWNER"));
        assertThatThrownBy(() -> authorities.add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingPropertiesAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceOwnerAuthenticator(null))
                .withMessage("props");
    }
}
