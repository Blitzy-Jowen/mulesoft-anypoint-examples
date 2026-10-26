/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LDAP connection settings, replacing the {@code ldap:config} element of the original {@code ldap.xml}.
 *
 * <p>Bound keys, under their original placeholder names: {@code ldap.url} (the connector URL, which carries
 * the base DN), {@code ldap.port} (embedded in {@code ldap.url} by {@code application.yml}), {@code ldap.dn}
 * (the original {@code authDn}) and {@code ldap.password} (the original {@code authPassword}).
 *
 * <p>The bind DN and password are a configured service account and come only from configuration; nothing in a
 * request can set them. Neither has a built-in value, and both are required: an empty DN or password fails the
 * first directory search, never the application start.
 *
 * <p>The class is registered solely through {@code @EnableConfigurationProperties} on the application class,
 * so it carries no stereotype annotation that would register a second bean.
 */
@ConfigurationProperties(prefix = "ldap")
public class LdapProperties {

    private static final String PASSWORD_MASK = "****";

    /** Connector URL, for example {@code ldap://localhost:389/dc=my-domain,dc=com}. */
    private String url;

    /** Bind DN; required, empty until configured. */
    private String dn = "";

    /** Bind password. */
    private String password = "";

    /**
     * Directory port. The URL is not composed from it here: {@code application.yml} embeds
     * {@code ${ldap.port}} in {@code ldap.url}, so an override of this key already reaches {@link #getUrl()}.
     */
    private int port = 389;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the bind DN.
     *
     * @return the bind DN, never {@code null}; empty when not configured, which fails the first search
     */
    public String getDn() {
        return dn;
    }

    /**
     * Sets the bind DN.
     *
     * @param dn the bind DN; {@code null} is stored as the empty string
     */
    public void setDn(String dn) {
        this.dn = dn == null ? "" : dn;
    }

    /**
     * Returns the bind password.
     *
     * @return the bind password, never {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the bind password.
     *
     * @param password the bind password; {@code null} is stored as the empty string
     */
    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Describes the settings with the password masked, so logging this object never discloses it.
     */
    @Override
    public String toString() {
        return "LdapProperties{url=" + url
                + ", dn=" + dn
                + ", password=" + (password.isEmpty() ? "" : PASSWORD_MASK)
                + ", port=" + port
                + "}";
    }
}
