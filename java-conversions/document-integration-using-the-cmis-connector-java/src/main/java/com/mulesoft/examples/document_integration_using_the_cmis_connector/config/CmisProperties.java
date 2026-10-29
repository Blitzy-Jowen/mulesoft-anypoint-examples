/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings of the CMIS 1.1 AtomPub repository, bound from the {@code cmis.*} keys of
 * {@code application.yml}.
 *
 * <p>Replaces the global element {@code <cmis:config name="openkm" .../>} of the original flow
 * {@code cmis-document-integration.xml}: its {@code baseUrl}, {@code repositoryId}, {@code username}
 * and {@code password} attributes become {@code cmis.base-url}, {@code cmis.repository-id},
 * {@code cmis.username} and {@code cmis.password}. The original declares no {@code endpoint}, so the
 * AtomPub binding applies and {@code baseUrl} is the AtomPub service-document URL.
 *
 * <p>The original hard-coded its demo account in the flow. Here the credentials have no default:
 * {@code application.yml} externalizes them as {@code ${CMIS_USERNAME:}} and {@code ${CMIS_PASSWORD:}},
 * and the test configuration supplies the demo values. Nothing is validated at bind time, so the
 * application starts with the credentials unset; the client then rejects each repository operation,
 * naming the missing key, before it makes any request.
 *
 * <p>The connect and read timeouts default to 10 seconds, the Mule default response timeout.
 *
 * <p>{@code cmis.trusted-origins} has no counterpart in the original. The client sends a request, and with it
 * the service account, only to the origin of {@code cmis.base-url} or to one listed here, so a deployment whose
 * repository advertises its links on another host, for example behind a proxy, lists that origin. It is empty by
 * default and comes only from configuration.
 *
 * <p>Registered by {@code @EnableConfigurationProperties(CmisProperties.class)} on the application
 * class. It carries no stereotype annotation, because one would register a second bean of this type.
 */
@ConfigurationProperties(prefix = "cmis")
public class CmisProperties {

    private static final Duration MULE_DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    /** AtomPub service-document URL of the repository ({@code cmis:config baseUrl}). */
    private String baseUrl = "http://demo.openkm.com/OpenKM/cmis/atom";

    /** Repository whose workspace is used ({@code cmis:config repositoryId}). */
    private String repositoryId = "default";

    /** Service-account user name; null or blank fails every repository operation before any request. */
    private String username;

    /** Service-account password. */
    private String password;

    /** Connection timeout of every repository request. */
    private Duration connectTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    /** Read timeout of every repository request. */
    private Duration readTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    /**
     * Repository origins ({@code scheme://host[:port]}, {@code http} or {@code https}) trusted besides that of
     * {@link #baseUrl}; the client refuses to request a URL the repository's responses name on any other origin.
     */
    private List<String> trustedOrigins = new ArrayList<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public List<String> getTrustedOrigins() {
        return trustedOrigins;
    }

    public void setTrustedOrigins(List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins;
    }

    /** Renders every setting except the password, which appears only as a mask when it is set. */
    @Override
    public String toString() {
        String maskedPassword = (password == null || password.isEmpty()) ? password : "****";
        return "CmisProperties{baseUrl='" + baseUrl + "', repositoryId='" + repositoryId
                + "', username='" + username + "', password='" + maskedPassword
                + "', connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", trustedOrigins=" + trustedOrigins + '}';
    }
}
