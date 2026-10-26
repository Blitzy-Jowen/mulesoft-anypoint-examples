/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

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
 * defaults, and the live test supplies them from the git-ignored {@code application-it.yml}. Nothing is validated
 * at bind time, so the application starts with every one of them empty; Workday then rejects the call.
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
     * The WS-Security UsernameToken name the Workday connector sent, {@code user@tenant}.
     *
     * <p>Deliberately not a getter, so no {@code wday.username} property exists. It is computed on every call, so a
     * re-authentication picks up a changed user or tenant; unset values are concatenated as they are, and Workday
     * rejects the resulting name.
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
}
