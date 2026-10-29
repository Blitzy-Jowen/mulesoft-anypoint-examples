/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.support.HttpTestClient;

/**
 * Port of the original example's {@code WebServiceConsumerIT} functional test: the same two requests, made with
 * the same method and {@code Content-Type}, and its assertions with their original substring semantics.
 *
 * <p>The original ran {@code tshirt-service-consumer.xml} in an embedded Mule runtime and called the public
 * {@code http://tshirt-service.cloudhub.io}. Here the whole application runs on a random port and ST-1
 * {@link HttpTestClient} sends the requests, so the body is read off the socket as received. A WireMock server takes
 * the place of the T-shirt service through {@code tshirt.service-address}, answering each operation, matched by its
 * {@code tshirt.wsdl} {@code soapAction}, with the original's sample response: an order id for
 * {@code OrderTshirt} and the five-item inventory for {@code ListInventory}. The run therefore needs no network
 * beyond localhost. Profile {@code test} supplies the original demo API key ({@code application-test.yml}), which
 * the {@code OrderTshirt} call requires.
 *
 * <p>The original's "payload is not a null payload" checks become "the HTTP body is not empty". Exact response bytes
 * are asserted by the mapper tests, not here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebServiceConsumerTest {

    private static final String SOAP_CONTENT_TYPE = "text/xml; charset=utf-8";

    /** The original's {@code sample_data/OrderTshirtResponse.xml} as the service's SOAP 1.1 answer. */
    private static final String ORDER_TSHIRT_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">\
            <soap:Body>\
            <ns2:OrderTshirtResponse xmlns:ns2="http://mulesoft.org/tshirt-service">\
            <orderId>1</orderId>\
            </ns2:OrderTshirtResponse>\
            </soap:Body>\
            </soap:Envelope>""";

    /** The original's {@code sample_data/ListInventoryResponse.xml} (five items) as the service's SOAP 1.1 answer. */
    private static final String LIST_INVENTORY_RESPONSE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:ListInventoryResponse xmlns:ns2="http://mulesoft.org/tshirt-service">
                  <inventory>
                    <productCode>4102</productCode>
                    <size>L</size>
                    <description>Prueba</description>
                    <count>2</count>
                  </inventory>
                  <inventory>
                    <productCode>1412</productCode>
                    <size>L</size>
                    <description>Foo</description>
                    <count>9</count>
                  </inventory>
                  <inventory>
                    <productCode>5656</productCode>
                    <size>S</size>
                    <description>Bar</description>
                    <count>2</count>
                  </inventory>
                  <inventory>
                    <productCode>5657</productCode>
                    <size>M</size>
                    <description>Prueba2</description>
                    <count>3</count>
                  </inventory>
                  <inventory>
                    <productCode>1411</productCode>
                    <size>M</size>
                    <description>Awesome Tshirt</description>
                    <count>5</count>
                  </inventory>
                </ns2:ListInventoryResponse>
              </soap:Body>
            </soap:Envelope>""";

    private static String MESSAGE;

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tshirt.service-address", upstream::baseUrl);
    }

    @BeforeAll
    static void init() throws IOException {
        // The original swallowed a read failure and left MESSAGE null; failing here names the cause instead.
        try (InputStream in = WebServiceConsumerTest.class.getResourceAsStream("/message.json")) {
            if (in == null) {
                throw new FileNotFoundException("classpath resource /message.json");
            }
            MESSAGE = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // As in the original, this also rewrites the "ID" of the customer e-mail address.
        MESSAGE = MESSAGE.replace("ID", String.valueOf(new Date().getTime() / 1000));
    }

    @BeforeEach
    void stubTshirtService() {
        upstream.resetAll();
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(quoted(TshirtServiceClient.ORDER_TSHIRT_ACTION)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", SOAP_CONTENT_TYPE)
                        .withBody(ORDER_TSHIRT_RESPONSE)));
        upstream.stubFor(post(anyUrl())
                .withHeader("SOAPAction", equalTo(quoted(TshirtServiceClient.LIST_INVENTORY_ACTION)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", SOAP_CONTENT_TYPE)
                        .withBody(LIST_INVENTORY_RESPONSE)));
    }

    @Test
    void testWebServiceConsumer() {
        HttpTestClient client = HttpTestClient.localhost(port);
        Map<String, String> props = Map.of("Content-Type", "application/json");

        HttpTestClient.Response result =
                client.send("POST", "/orders", props, MESSAGE.getBytes(StandardCharsets.UTF_8));
        assertNotNull(result);
        assertEquals(200, result.status(), result::toString);
        assertFalse(result.body().length == 0, result::toString);
        assertTrue(result.bodyAsString().contains("orderId"), result::toString);

        // The original sent a null payload with the same properties: still a POST with the same Content-Type.
        result = client.send("POST", "/inventory", props, new byte[0]);
        assertNotNull(result);
        assertEquals(200, result.status(), result::toString);
        assertFalse(result.body().length == 0, result::toString);
        String inventory = result.bodyAsString();
        assertTrue(inventory.contains("\"productCode\": \"1412\""), result::toString);
        assertTrue(inventory.contains("\"description\": \"Prueba2\""), result::toString);
        assertTrue(inventory.contains("\"count\": \"5\""), result::toString);

        // One upstream call per operation: nothing was retried.
        upstream.verify(1, postRequestedFor(anyUrl())
                .withHeader("SOAPAction", equalTo(quoted(TshirtServiceClient.ORDER_TSHIRT_ACTION))));
        upstream.verify(1, postRequestedFor(anyUrl())
                .withHeader("SOAPAction", equalTo(quoted(TshirtServiceClient.LIST_INVENTORY_ACTION))));
    }

    /** SOAP 1.1 {@code SOAPAction} header value as Spring WS sends it: the action URI in double quotes. */
    private static String quoted(String soapAction) {
        return "\"" + soapAction + "\"";
    }
}
