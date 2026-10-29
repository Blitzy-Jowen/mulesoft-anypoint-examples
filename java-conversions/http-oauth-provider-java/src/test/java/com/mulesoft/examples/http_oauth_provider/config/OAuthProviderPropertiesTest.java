/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties.Owner;

/**
 * Unit test of the {@code toString} renderings of {@link OAuthProviderProperties} and its {@link Owner}, which leave
 * out the client secret and the owner password so the settings can be logged.
 *
 * <p>A set secret renders as {@code ****} and an unset one ({@code null} or empty, both normalised to empty) as an
 * empty value, while every other setting keeps the generated record form: {@code Name[component=value, ...]} in
 * declaration order, the owner rendered masked inside the enclosing record. The records are built in code, so no
 * Spring context is involved.
 */
class OAuthProviderPropertiesTest {

    /** Configured secrets, distinct from every other value, so a secret written into a rendering is detected. */
    private static final String CLIENT_SECRET = "SENTINEL_CLIENT_SECRET_TS";
    private static final String OWNER_PASSWORD = "SENTINEL_OWNER_PW_TS";

    private static final String MASK = "****";

    /** Components whose value a rendering must never show. */
    private static final Set<String> SECRET_COMPONENTS = Set.of("clientSecret", "password");

    /**
     * Provider configuration of the original {@code oauth2-provider:config} and its client (xml lines 26-37) with
     * the given client secret, and its {@code ss:user} (xml line 16) with the given password.
     *
     * @param clientSecret  the client secret, {@code null} allowed
     * @param ownerPassword the owner password, {@code null} allowed
     * @return the normalised properties
     */
    private static OAuthProviderProperties properties(String clientSecret, String ownerPassword) {
        return new OAuthProviderProperties(
                "SampleAPI",
                "myclientid",
                clientSecret,
                "Mule Bookstore",
                "Mule-powered On-line Bookstore",
                "CONFIDENTIAL",
                List.of("http://localhost*"),
                List.of("AUTHORIZATION_CODE"),
                List.of("READ_RESOURCE", "POST_RESOURCE"),
                "READ_RESOURCE",
                86400L,
                600L,
                new Owner("mule", ownerPassword, List.of("RESOURCE_OWNER")));
    }

    static Stream<Arguments> secretSettings() {
        return Stream.of(
                Arguments.of("both secrets set", CLIENT_SECRET, OWNER_PASSWORD, MASK, MASK),
                Arguments.of("client secret set, owner password unset", CLIENT_SECRET, "", MASK, ""),
                Arguments.of("client secret unset, owner password set", null, OWNER_PASSWORD, "", MASK),
                Arguments.of("blank secrets are set values", "  ", "\t", MASK, MASK),
                Arguments.of("both secrets empty", "", "", "", ""),
                Arguments.of("both secrets null, normalised to empty", null, null, "", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secretSettings")
    void toStringMasksTheSecretsAndKeepsEveryOtherSetting(String scenario, String clientSecret, String ownerPassword,
                                                          String clientSecretMask, String ownerPasswordMask) {
        OAuthProviderProperties props = properties(clientSecret, ownerPassword);

        String expectedOwner = "Owner[username=mule, password=" + ownerPasswordMask + ", authorities=[RESOURCE_OWNER]]";
        assertThat(props.owner().toString()).isEqualTo(expectedOwner);
        assertThat(props.toString()).isEqualTo("OAuthProviderProperties[providerName=SampleAPI, clientId=myclientid, "
                + "clientSecret=" + clientSecretMask + ", clientName=Mule Bookstore, "
                + "clientDescription=Mule-powered On-line Bookstore, clientType=CONFIDENTIAL, "
                + "redirectUris=[http://localhost*], authorizedGrantTypes=[AUTHORIZATION_CODE], "
                + "scopes=[READ_RESOURCE, POST_RESOURCE], resourceScope=READ_RESOURCE, tokenTtlSeconds=86400, "
                + "authorizationCodeTtlSeconds=600, owner=" + expectedOwner + "]");
        assertThat(props.toString()).doesNotContain(CLIENT_SECRET, OWNER_PASSWORD);
        assertThat(props.owner().toString()).doesNotContain(CLIENT_SECRET, OWNER_PASSWORD);
    }

    @Test
    void unsetSettingsRenderAsTheirNormalisedEmptyValues() {
        OAuthProviderProperties props = new OAuthProviderProperties(
                null, null, null, null, null, null, null, null, null, null, 0L, 0L, null);

        assertThat(props.toString()).isEqualTo("OAuthProviderProperties[providerName=, clientId=, clientSecret=, "
                + "clientName=, clientDescription=, clientType=, redirectUris=[], authorizedGrantTypes=[], scopes=[], "
                + "resourceScope=, tokenTtlSeconds=0, authorizationCodeTtlSeconds=0, "
                + "owner=Owner[username=, password=, authorities=[]]]");
    }

    /**
     * Holds both renderings to the records' own declarations, so a component added later without a place in
     * {@code toString} is detected: the expected text is the generated record form built from
     * {@link Class#getRecordComponents()}, with each secret component shown as its mask.
     *
     * @throws ReflectiveOperationException when a component accessor cannot be invoked
     */
    @Test
    void toStringRendersEveryComponentInDeclarationOrder() throws ReflectiveOperationException {
        OAuthProviderProperties props = properties(CLIENT_SECRET, OWNER_PASSWORD);

        assertThat(props.owner().toString()).isEqualTo(generatedFormWithSecretsMasked(props.owner()));
        assertThat(props.toString()).isEqualTo(generatedFormWithSecretsMasked(props));
    }

    private static String generatedFormWithSecretsMasked(Record value) throws ReflectiveOperationException {
        StringJoiner rendering = new StringJoiner(", ", value.getClass().getSimpleName() + "[", "]");
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object componentValue = component.getAccessor().invoke(value);
            String rendered;
            if (SECRET_COMPONENTS.contains(component.getName())) {
                rendered = MASK;
            } else if (componentValue instanceof Record nested) {
                rendered = generatedFormWithSecretsMasked(nested);
            } else {
                rendered = String.valueOf(componentValue);
            }
            rendering.add(component.getName() + "=" + rendered);
        }
        return rendering.toString();
    }
}
