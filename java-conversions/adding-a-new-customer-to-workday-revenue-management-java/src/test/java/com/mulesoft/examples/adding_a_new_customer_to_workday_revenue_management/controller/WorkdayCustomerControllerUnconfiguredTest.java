/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link WorkdayCustomerController} with no Workday values, as {@code application.yml} leaves them
 * when none is supplied: {@code wday.hostname}, {@code wday.tenant}, {@code wday.user} and {@code wday.password} are
 * empty, so the default {@code wday.endpoint} derives to {@code https:///ccx/service//Revenue_Management/v25.0},
 * which names no host.
 *
 * <p>The whole application starts on Undertow on a random port without profile {@code test}, which proves that no
 * {@code wday.*} value is needed at start-up. {@code POST /} then fails before any Workday call with SC-1's 500
 * {@code { "message": "wday.hostname is not configured" }}, the first missing key, sent through ST-1
 * {@link HttpTestClient} so the status line, headers and body bytes are asserted as they leave the server. The
 * client-level cases of {@code client/WorkdayRevenueManagementClientTest} prove that no connection is opened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"wday.hostname=", "wday.tenant=", "wday.user=", "wday.password="})
class WorkdayCustomerControllerUnconfiguredTest {

    @LocalServerPort
    int port;

    @Autowired
    WorkdayProperties properties;

    @Test
    void postRootWithoutWorkdayValuesNamesTheMissingKey() throws IOException {
        assertThat(properties.getEndpoint()).isEqualTo("https:///ccx/service//Revenue_Management/v25.0");

        Response response = HttpTestClient.localhost(port)
                .send("POST", "/", Map.of("Content-Type", "application/xml"), customerXml());

        assertThat(response.status()).as("status of %s", response).isEqualTo(500);
        assertThat(response.reason()).as("reason phrase of %s", response).isNotBlank();
        MediaType contentType = MediaType.parseMediaType(response.header("Content-Type"));
        assertThat(contentType.getType() + "/" + contentType.getSubtype()).isEqualTo("application/json");
        assertThat(new String(response.body(), UTF_8))
                .isEqualTo("{ \"message\": \"wday.hostname is not configured\" }");
    }

    /** The original test resource {@code customer.xml}: {@code root/Account} with {@code CustomerName} {@code NAME}. */
    private byte[] customerXml() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/customer.xml")) {
            assertThat(in).as("classpath resource /customer.xml").isNotNull();
            return in.readAllBytes();
        }
    }
}
