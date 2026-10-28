/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Salesforce connection settings, bound from the {@code sfdc.*} keys of {@code application.yml}.
 *
 * <p>Replaces the Mule global connector configuration
 * {@code <sfdc:config username="${sfdc.username}" password="${sfdc.password}" securityToken="${sfdc.securityToken}"/>}.
 * The original set no login URL and no timeouts, so the connector's defaults applied; they are carried here as
 * field defaults: the production Partner SOAP login endpoint and the 10 s Mule default response timeout, used for
 * both the connect and the read timeout. {@code SalesforcePartnerClient} builds its {@code ConnectorConfig} from
 * this bean and logs in lazily on the first call, so the application starts with every secret empty.
 *
 * <p>Key names follow the request rather than every original: five originals read {@code ${sfdc.user}}, which is
 * bound here as {@code sfdc.username}; the project README maps the original name to the new one.
 *
 * <p>Secrets ({@code username}, {@code password}, {@code securityToken}) have no default and are never hard-coded;
 * they come only from configuration ({@code SFDC_USERNAME}, {@code SFDC_PASSWORD}, {@code SFDC_SECURITYTOKEN}, or
 * the git-ignored {@code application-it.yml} of the live tests). {@link #toString()} masks them.
 *
 * <p>Shared component SC-15 (configuration part). This project holds the reference copy; the other Salesforce
 * projects ({@code import-contacts-into-salesforce}, {@code import-leads-into-salesforce},
 * {@code dataweave-with-flowreflookup}, {@code authenticating-salesforce-using-oauth2},
 * {@code salesforce-data-synchronization-using-watermarking-and-batch-processing},
 * {@code salesforce-to-MySQL-DB-using-Batch-Processing}) carry an identical copy in which only the {@code package}
 * line differs. In the OAuth project the client runs in session mode and uses only the timeouts; there
 * {@code username} and {@code password} serve the live test's browser login.
 */
@Component
@ConfigurationProperties(prefix = "sfdc")
public class SalesforceProperties {

    private static final String MASK = "****";

    private static final String UNSET = "<unset>";

    /** {@code sfdc.username}: the Salesforce user the Partner API {@code login} call authenticates. */
    private String username;

    /** {@code sfdc.password}: that user's password, without the security token. */
    private String password;

    /** {@code sfdc.securityToken} (also {@code sfdc.security-token}): appended to the password at login. */
    private String securityToken;

    /** {@code sfdc.auth-endpoint}: Partner SOAP login endpoint of API 65.0, matching force-partner-api 65.0.0. */
    private String authEndpoint = "https://login.salesforce.com/services/Soap/u/65.0";

    /** {@code sfdc.connect-timeout}: connect timeout; a bare number binds as milliseconds. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** {@code sfdc.read-timeout}: read timeout; a bare number binds as milliseconds. */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * @return the Salesforce username, or {@code null} when not configured
     */
    public String getUsername() {
        return username;
    }

    /**
     * @param username the Salesforce username
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * @return the Salesforce password without the security token, or {@code null} when not configured
     */
    public String getPassword() {
        return password;
    }

    /**
     * @param password the Salesforce password without the security token
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the security token exactly as configured; the bean neither trims it nor turns {@code null} into an
     * empty string. In login mode {@code SalesforcePartnerClient} requires it, as it requires the username and the
     * password, and logs in with {@code password + securityToken}, the form the Partner API expects from a user
     * outside a trusted IP range. While it is {@code null} or blank, a login fails naming {@code sfdc.securityToken}
     * before any request is sent.
     *
     * @return the raw security token, or {@code null} when not configured
     */
    public String getSecurityToken() {
        return securityToken;
    }

    /**
     * @param securityToken the Salesforce security token, stored unchanged
     */
    public void setSecurityToken(String securityToken) {
        this.securityToken = securityToken;
    }

    /**
     * @return the Partner SOAP login endpoint passed to {@code ConnectorConfig.setAuthEndpoint}
     */
    public String getAuthEndpoint() {
        return authEndpoint;
    }

    /**
     * @param authEndpoint the Partner SOAP login endpoint; tests point it at a WireMock stub
     */
    public void setAuthEndpoint(String authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    /**
     * @return the connect timeout, which the client passes to {@code ConnectorConfig} in milliseconds
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the connect timeout
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * @return the read timeout, which the client passes to {@code ConnectorConfig} in milliseconds
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * @param readTimeout the read timeout
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Describes the bound values with the secrets masked: {@code password} and {@code securityToken} appear as
     * {@code ****} when set and as {@code <unset>} otherwise, so a log line shows whether a secret is configured but
     * never its value.
     */
    @Override
    public String toString() {
        return "SalesforceProperties{username=" + username
                + ", password=" + mask(password)
                + ", securityToken=" + mask(securityToken)
                + ", authEndpoint=" + authEndpoint
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + "}";
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? UNSET : MASK;
    }
}
