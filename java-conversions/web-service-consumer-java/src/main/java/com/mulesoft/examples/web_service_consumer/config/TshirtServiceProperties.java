/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the {@code TshirtService} SOAP client, bound from the {@code tshirt.*} keys of
 * {@code application.yml}.
 *
 * <p>Replaces two values the original flow {@code tshirt-service-consumer.xml} hard-coded:
 * <ul>
 *   <li>line 3, {@code <ws:consumer-config serviceAddress="http://tshirt-service.cloudhub.io"/>}, the
 *       address of port {@code TshirtServicePort} (the {@code soap:address} of {@code tshirt.wsdl}), kept as
 *       the default of {@code tshirt.service-address} because it is a public service URL;</li>
 *   <li>line 22, the {@code apiKey} of the {@code AuthenticationHeader} SOAP header, now
 *       {@code tshirt.api-key}. It is a credential, so it has no default here and {@code application.yml}
 *       externalizes it as {@code ${TSHIRT_API_KEY:}}; the demo value is supplied only by
 *       {@code src/test/resources/application-test.yml}.</li>
 * </ul>
 *
 * <p>{@code tshirt.connect-timeout} and {@code tshirt.read-timeout} default to 10 seconds, the Mule default
 * response timeout. Each must be at least 1 ms and at most {@link Integer#MAX_VALUE} ms in whole milliseconds,
 * because the HTTP connection takes an {@code int} of milliseconds and waits forever on 0, which a zero or
 * sub-millisecond value truncates to.
 *
 * <p>Nothing is validated or trimmed at bind time: the client receives exactly what is configured, and
 * {@code TshirtServiceClient} rejects a timeout outside those bounds when it is created at startup, with a message
 * naming the key; a timeout bound to {@code null} gets the 10 s default there.
 *
 * <p>Registered by {@code @EnableConfigurationProperties(TshirtServiceProperties.class)} on the application
 * class. It carries no stereotype annotation, because one would register a second bean of this type.
 */
@ConfigurationProperties(prefix = "tshirt")
public class TshirtServiceProperties {

    private static final Duration MULE_DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    /** SOAP endpoint of {@code TshirtService}/{@code TshirtServicePort} ({@code ws:consumer-config serviceAddress}). */
    private String serviceAddress = "http://tshirt-service.cloudhub.io";

    /** Value of the {@code AuthenticationHeader/apiKey} SOAP header; empty until configured. */
    private String apiKey = "";

    /** Connection timeout of every SOAP request. */
    private Duration connectTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    /** Read timeout of every SOAP request. */
    private Duration readTimeout = MULE_DEFAULT_RESPONSE_TIMEOUT;

    public String getServiceAddress() {
        return serviceAddress;
    }

    public void setServiceAddress(String serviceAddress) {
        this.serviceAddress = serviceAddress;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
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

    /** Renders every setting except the API key, which appears only as a mask when it is set. */
    @Override
    public String toString() {
        String maskedApiKey = (apiKey == null || apiKey.isEmpty()) ? "" : "****";
        return "TshirtServiceProperties{serviceAddress='" + serviceAddress + "', apiKey='" + maskedApiKey
                + "', connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + '}';
    }
}
