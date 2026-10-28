/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workday connection settings, bound from the {@code wday.*} keys of {@code application.yml}.
 *
 * <p>Replaces the Mule global connector configuration of {@code add_a_new_customer.xml}:
 * {@code <wd-connector:config hostName="${wday.hostname}" tenantName="${wday.tenant}" username="${wday.user}"
 * password="${wday.password}"/>}. The four keys keep their original names. The original declared no timeouts, so
 * the Mule default response timeout of 10 s applies to both the connect and the read timeout.
 *
 * <p>The host, tenant and credentials have no default and are never hard-coded: {@code application.yml} reads them
 * from {@code WDAY_HOSTNAME}, {@code WDAY_TENANT}, {@code WDAY_USER} and {@code WDAY_PASSWORD} with empty
 * defaults, and the live test supplies them from the git-ignored {@code application-it.yml}. Missing values fail
 * late: nothing is validated at bind time, so the application starts with every one of them empty, and each Workday
 * call first runs {@link #requireConfigured()}, which throws {@link IllegalStateException} naming the first missing
 * or invalid key before anything is sent. The message never repeats a configured value.
 *
 * <p>The MUnit test-data keys {@code wday.status.id} and {@code wday.reason.id} are not bound here: only the live
 * test's request helper reads them.
 *
 * <p>Registered by {@code @ConfigurationPropertiesScan} on the application class. It carries no stereotype
 * annotation, because one would register a second bean of this type.
 */
@ConfigurationProperties(prefix = "wday")
public class WorkdayProperties {

    private static final Duration MULE_DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private static final String MASK = "****";

    /** {@code wday.hostname}, the connector's {@code hostName}: the Workday web-services host. */
    private String hostname;

    /** {@code wday.tenant}, the connector's {@code tenantName}: the Workday tenant ID. */
    private String tenant;

    /** {@code wday.user}, the connector's {@code username}: the integration user, sent as {@code user@tenant}. */
    private String user;

    /** {@code wday.password}, the connector's {@code password}: the integration user's password. */
    private String password;

    /**
     * {@code wday.endpoint}: the Revenue_Management v25.0 service URL the connector derived from host and tenant.
     * {@code application.yml} builds it from {@code wday.hostname} and {@code wday.tenant}; tests point it at a stub.
     */
    private String endpoint;

    /** {@code wday.connect-timeout}: connect timeout of every Workday call; a bare number binds as milliseconds. */
    private Duration connectTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    /** {@code wday.read-timeout}: read timeout of every Workday call; a bare number binds as milliseconds. */
    private Duration readTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Checks that every value a Workday call needs is configured, so that a call never builds a request from a
     * missing one. The client runs it at the start of each call, before anything is logged or sent; start-up and the
     * construction of the client never run it, so the application starts with every {@code wday.*} value empty.
     *
     * <p>The checks run in this order, and the first failing one throws:
     * <ol>
     *   <li>the endpoint: a {@code wday.endpoint} that is an absolute {@code http} or {@code https} URL with a host is
     *       used as it is, so an explicit override needs no {@code wday.hostname}. Otherwise a blank
     *       {@code wday.hostname} is reported when the endpoint names no host (it is blank, does not parse, or has
     *       no host, as the default
     *       {@code https://${wday.hostname}/ccx/service/${wday.tenant}/Revenue_Management/v25.0} has without one);
     *       then a blank {@code wday.endpoint}; and any other value as not a valid Workday service URL;</li>
     *   <li>{@code wday.tenant}, which forms the UsernameToken name {@code user@tenant} and the default endpoint's
     *       path;</li>
     *   <li>{@code wday.user};</li>
     *   <li>{@code wday.password}.</li>
     * </ol>
     * A value is missing when it is {@code null} or blank after trimming. With every value empty the message is
     * {@code wday.hostname is not configured}.
     *
     * @throws IllegalStateException {@code "<key> is not configured"} for the first missing key, or
     *         {@code "wday.endpoint is not a valid Workday service URL: ..."} with the rule it breaks; the message
     *         names the key and never repeats the configured value
     */
    public void requireConfigured() {
        requireEndpoint();
        required(tenant, "wday.tenant");
        required(user, "wday.user");
        required(password, "wday.password");
    }

    /**
     * The WS-Security UsernameToken name the Workday connector sent, {@code user@tenant}.
     *
     * <p>Deliberately not a getter, so no {@code wday.username} property exists. It is computed on every call, so a
     * re-authentication picks up a changed user or tenant. It concatenates the values as they are; a name built from
     * a missing user or tenant is never sent, because every call runs {@link #requireConfigured()} first.
     *
     * @return {@code user + "@" + tenant}
     */
    public String username() {
        return user + "@" + tenant;
    }

    /** Renders every setting except the password, which appears only as a mask when it is set. */
    @Override
    public String toString() {
        String maskedPassword = (password == null || password.isEmpty()) ? password : MASK;
        return "WorkdayProperties{hostname='" + hostname + "', tenant='" + tenant
                + "', user='" + user + "', password='" + maskedPassword
                + "', endpoint='" + endpoint
                + "', connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + '}';
    }

    /** The endpoint step of {@link #requireConfigured()}. */
    private void requireEndpoint() {
        URI uri = parse(endpoint);
        if (uri != null && isHttpUrlWithHost(uri)) {
            return;
        }
        if ((uri == null || uri.getHost() == null) && isBlank(hostname)) {
            throw new IllegalStateException("wday.hostname is not configured");
        }
        required(endpoint, "wday.endpoint");
        throw new IllegalStateException("wday.endpoint is not a valid Workday service URL: it must be an absolute"
                + " http or https URL with a host");
    }

    private static void required(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalStateException(key + " is not configured");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Parses the endpoint exactly as the client passes it to Spring WS, which converts it with
     * {@link URI#create(String)}. A {@link URISyntaxException} yields {@code null} and is not propagated, because its
     * message repeats the input.
     *
     * @return the parsed URI, or {@code null} when the value is blank or is no URI
     */
    private static URI parse(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Whether the URI is absolute, with the scheme {@code http} or {@code https} (any case) and a host. */
    private static boolean isHttpUrlWithHost(URI uri) {
        String scheme = uri.getScheme();
        return uri.isAbsolute()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null;
    }
}
