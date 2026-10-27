/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reports, once the application is ready, which client and resource-owner credentials of
 * {@link OAuthProviderProperties} are not configured.
 *
 * <p>Those credentials come only from empty {@code ${ENV:}} placeholders and stay blank until supplied. While one is
 * blank the provider fails closed: {@code OAuthProviderService} rejects every authorization request without a
 * configured client ID and every token request without a configured client ID or, for a client that is not
 * {@code PUBLIC}, a configured client secret, and {@code ResourceOwnerAuthenticator} rejects every login without a
 * configured owner username and password. Those rejections write nothing at INFO or above, so this check writes one
 * WARN line at startup naming each blank key by its full property name. A value is blank, as those classes test it,
 * when it is empty or whitespace only. Nothing is written when every required credential is configured.
 *
 * <p>The line carries key names only: no value, length or masked form of any credential is ever logged. The check
 * changes no behaviour, so the application still starts with blank credentials.
 */
@Component
public class OAuthProviderConfigurationCheck {

    /** Property of {@link OAuthProviderProperties#clientId()}. */
    static final String CLIENT_ID_KEY = "oauth.provider.client-id";

    /** Property of {@link OAuthProviderProperties#clientSecret()}. */
    static final String CLIENT_SECRET_KEY = "oauth.provider.client-secret";

    /** Property of {@link OAuthProviderProperties.Owner#username()}. */
    static final String OWNER_USERNAME_KEY = "oauth.provider.owner.username";

    /** Property of {@link OAuthProviderProperties.Owner#password()}. */
    static final String OWNER_PASSWORD_KEY = "oauth.provider.owner.password";

    /** The client type whose token requests are not authenticated with a secret. */
    private static final String CLIENT_TYPE_PUBLIC = "PUBLIC";

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderConfigurationCheck.class);

    private final OAuthProviderProperties props;

    /**
     * @param props the bound provider configuration
     * @throws NullPointerException if {@code props} is {@code null}
     */
    public OAuthProviderConfigurationCheck(OAuthProviderProperties props) {
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * Writes one WARN line naming every blank required credential key, in the order client ID, client secret, owner
     * username, owner password, or nothing when none is blank. The client secret is required unless
     * {@code oauth.provider.client-type} is {@code PUBLIC}, compared ignoring case.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportMissingCredentials() {
        List<String> blankKeys = new ArrayList<>(4);
        if (isBlank(props.clientId())) {
            blankKeys.add(CLIENT_ID_KEY);
        }
        if (!CLIENT_TYPE_PUBLIC.equalsIgnoreCase(props.clientType()) && isBlank(props.clientSecret())) {
            blankKeys.add(CLIENT_SECRET_KEY);
        }
        OAuthProviderProperties.Owner owner = props.owner();
        if (owner == null || isBlank(owner.username())) {
            blankKeys.add(OWNER_USERNAME_KEY);
        }
        if (owner == null || isBlank(owner.password())) {
            blankKeys.add(OWNER_PASSWORD_KEY);
        }
        if (!blankKeys.isEmpty()) {
            log.warn("OAuth provider credentials are not configured ({}): authorization and token requests are "
                    + "rejected until they are supplied", String.join(", ", blankKeys));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
