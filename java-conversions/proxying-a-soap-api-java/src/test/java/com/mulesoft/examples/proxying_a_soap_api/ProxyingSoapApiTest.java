/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient;

/**
 * Port of the original example's {@code ProxyingSoapApiIT} functional test, keeping its request (the
 * {@code message.xml} envelope posted to the listener root) and its four assertions on the proxied answer; wire-level
 * checks of status, headers and error paths live in {@code controller/SoapProxyControllerTest}.
 *
 * <p>The original reached the live predic8 ShopService at {@code www.predic8.com:8080/shop/ShopService}. Here a
 * WireMock server stands in for it and answers {@code create} with a {@code createResponse} carrying a cart ID, so the
 * envelope still travels through the whole proxy (listener, header copying on both legs, upstream {@code POST}) with
 * no network access. The WSDL the application publishes at start-up is read from the classpath stand-in
 * {@code upstream/ShopService.wsdl}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "proxy.wsdl-location=classpath:upstream/ShopService.wsdl")
public class ProxyingSoapApiTest {

    /** The {@code http:request path} of the original flow, appended to the stand-in's base URL. */
    private static final String UPSTREAM_PATH = "/shop/ShopService";

    /**
     * The ShopService {@code create} answer; {@code cartId} is unqualified, as {@code upstream/ShopService.xsd}
     * declares.
     */
    private static final String RESPONSE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<ns2:createResponse xmlns:ns2=\"http://predic8.com/wsdl/shop/1/\"><cartId>1001</cartId>"
                    + "</ns2:createResponse></soap:Body></soap:Envelope>";

    /** Static, so its URL exists before {@link #upstream} is read while the application context starts. */
    @RegisterExtension
    static WireMockExtension predic8 = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true))
            .build();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> predic8.baseUrl() + UPSTREAM_PATH);
    }

    @LocalServerPort
    int port;

    @Test
    void httpPostToFlowUrl() throws IOException {
        predic8.stubFor(post(urlEqualTo(UPSTREAM_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(RESPONSE)));

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .send("POST", "/", Map.of("Content-Type", "text/xml; charset=UTF-8"), message());

        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertTrue(response.body().length > 0, response::toString);
        assertTrue(response.bodyAsString().contains("<cartId>"), response::toString);
        assertTrue(response.bodyAsString().contains("</cartId>"), response::toString);
    }

    /** The request envelope of the original test, {@code src/test/resources/message.xml}, read from the classpath. */
    private static byte[] message() throws IOException {
        try (InputStream in = new ClassPathResource("message.xml").getInputStream()) {
            return in.readAllBytes();
        }
    }
}
