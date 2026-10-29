/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;

/**
 * Unit test of {@link SamsungServiceClient}, the replacement of the {@code samsungWebServiceClient} sub-flow: its
 * {@code cxf:jaxws-client operation="purchase"} and the {@code http:request method="POST" path="samsung/orders"
 * port="9090"} after it (service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:94-97).
 *
 * <p>A WireMock server on a dynamic localhost port stands in for the 9090 {@code SamsungService} listener. The wire
 * contract is the one {@code samsung.wsdl} describes: a SOAP 1.1 document/literal {@code purchase} with
 * {@code soapAction=""}, whose {@code orderRequest}, {@code name} and {@code quantity} are unqualified
 * ({@code elementFormDefault="unqualified"}), answered by {@code purchaseResponse/orderResponse(id, result, price)}.
 * The success reply carries what the original {@code SamsungServiceImpl} answers for one item: {@code id} 1,
 * {@code ACCEPTED} and {@code 2550 * quantity}. The returned status is asserted in every success case, because the
 * caller flow filters on {@code http.status=200} (fulfillment.xml:62-64). Every failure, a SOAP fault, a non-2xx reply,
 * a timeout or a broken connection, must reach the caller unchanged after exactly one request, because the original
 * sub-flow had no retry.
 *
 * <p>No Spring context is started: the SOAP 1.1 message factory and the {@code model.samsung} marshaller are built
 * here as the project's configuration declares them, and the client is constructed directly.
 */
class SamsungServiceClientTest {

    private static final String SAMSUNG_PATH = "/samsung/orders";

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String SAMSUNG_NS = "http://samsung.se.mulesoft.com/";

    /** The wrapped request; the unprefixed step matches only an element without a namespace. */
    private static final String ORDER_REQUEST = "/soap:Envelope/soap:Body/sam:purchase/orderRequest";

    private static final long TIMEOUT_MS = 5000;

    /** Read timeout of the timeout case, far below the stub's delay so the case fails fast and deterministically. */
    private static final long SHORT_TIMEOUT_MS = 300;

    private static final int RESPONSE_DELAY_MS = 2000;

    private static final String XML_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** SamsungServiceImpl's answer to an order of one item. */
    private static final String SUCCESS_BODY = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ns2:purchaseResponse xmlns:ns2="http://samsung.se.mulesoft.com/">
                  <orderResponse><id>1</id><result>ACCEPTED</result><price>2550</price></orderResponse>
                </ns2:purchaseResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private static final String FAULT_BODY = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <soap:Fault>
                  <faultcode>soap:Server</faultcode>
                  <faultstring>Samsung unavailable</faultstring>
                </soap:Fault>
              </soap:Body>
            </soap:Envelope>
            """;

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private SaajSoapMessageFactory messageFactory;

    private Jaxb2Marshaller samsungMarshaller;

    private SamsungServiceClient client;

    @BeforeEach
    void createClient() throws Exception {
        messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        messageFactory.afterPropertiesSet();
        samsungMarshaller = new Jaxb2Marshaller();
        samsungMarshaller.setContextPath(OrderRequest.class.getPackageName());
        samsungMarshaller.afterPropertiesSet();
        client = clientWithTimeout(TIMEOUT_MS);
    }

    @Test
    void purchaseReturnsOrderResponseAndStatus200() {
        stubReply(200, SUCCESS_BODY);

        ResponseEntity<OrderResponse> reply = client.purchase(request("s-1", 1));

        assertEquals(200, reply.getStatusCode().value());
        assertAccepted(reply.getBody());
        assertSingleRequest();
    }

    @Test
    void purchaseSendsSoap11PurchaseEnvelope() {
        stubReply(200, SUCCESS_BODY);

        client.purchase(request("s-1", 1));

        // Spring WS writes the WSDL's soapAction="" as the quoted empty action SOAP 1.1 prescribes.
        wm.verify(1, postRequestedFor(urlEqualTo(SAMSUNG_PATH))
                .withHeader("SOAPAction", equalTo("\"\""))
                .withHeader("Content-Type", containing("text/xml")));
        String envelope = singleRequest().getBodyAsString();
        assertEquals("1", xpath("count(/soap:Envelope/soap:Body/*)", envelope));
        assertEquals("1", xpath("count(/soap:Envelope/soap:Body/sam:purchase/*)", envelope));
        assertEquals("2", xpath("count(" + ORDER_REQUEST + "/*)", envelope));
        assertEquals("s-1", xpath(ORDER_REQUEST + "/*[1][self::name]", envelope));
        assertEquals("1", xpath(ORDER_REQUEST + "/*[2][self::quantity]", envelope));
    }

    @Test
    void purchaseSendsFixedLengthRequestWithoutCompression() {
        stubReply(200, SUCCESS_BODY);

        client.purchase(request("s-1", 1));

        // The single-attempt transport streams the collected envelope with its exact length, never chunked, and
        // advertises no gzip, as neither CXF nor the Mule requester did.
        LoggedRequest sent = singleRequest();
        assertEquals(String.valueOf(sent.getBody().length), sent.getHeader("Content-Length"));
        assertFalse(sent.containsHeader("Transfer-Encoding"), "request must not be chunked");
        assertFalse(sent.containsHeader("Accept-Encoding"), "request must not advertise a content encoding");
    }

    @Test
    void purchaseReturnsTransportStatus() {
        stubReply(201, SUCCESS_BODY);

        ResponseEntity<OrderResponse> reply = client.purchase(request("s-1", 1));

        assertEquals(201, reply.getStatusCode().value());
        assertAccepted(reply.getBody());
        assertSingleRequest();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204})
    void successReplyWithoutSoapMessageReturnsStatusWithoutBody(int status) {
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH)).willReturn(aResponse().withStatus(status)));

        ResponseEntity<OrderResponse> reply = client.purchase(request("s-1", 1));

        assertEquals(status, reply.getStatusCode().value());
        assertNull(reply.getBody());
        assertSingleRequest();
    }

    @Test
    void purchaseResponseWithoutOrderResponseReturnsNullBody() {
        stubReply(200, """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body><ns2:purchaseResponse xmlns:ns2="http://samsung.se.mulesoft.com/"/></soap:Body>
                </soap:Envelope>
                """);

        ResponseEntity<OrderResponse> reply = client.purchase(request("s-1", 1));

        assertEquals(200, reply.getStatusCode().value());
        assertNull(reply.getBody());
        assertSingleRequest();
    }

    @Test
    void unexpectedResponsePayloadThrowsIllegalState() {
        // A bare orderResponse root is bound by the context but is not the purchaseResponse wrapper.
        stubReply(200, """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <ns2:orderResponse xmlns:ns2="http://samsung.se.mulesoft.com/">
                      <id>1</id><result>ACCEPTED</result><price>2550</price>
                    </ns2:orderResponse>
                  </soap:Body>
                </soap:Envelope>
                """);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> client.purchase(request("s-1", 1)));

        assertEquals("Unexpected Samsung purchase response payload: " + OrderResponse.class.getName(),
                thrown.getMessage());
        assertSingleRequest();
    }

    @Test
    void soapFaultPropagatesAsSoapFaultClientException() {
        stubReply(500, FAULT_BODY);

        SoapFaultClientException thrown =
                assertThrows(SoapFaultClientException.class, () -> client.purchase(request("s-1", 1)));

        assertEquals("Samsung unavailable", thrown.getFaultStringOrReason());
        assertSingleRequest();
    }

    @Test
    void methodNotAllowedPropagatesAsTransportException() {
        // The listener's answer to a method it does not allow; the 9090 SamsungService listener accepts POST only.
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH))
                .willReturn(aResponse()
                        .withStatus(405)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Method not allowed for endpoint: " + SAMSUNG_PATH)));

        WebServiceTransportException thrown =
                assertThrows(WebServiceTransportException.class, () -> client.purchase(request("s-1", 1)));

        assertThat(thrown.getMessage()).contains("405");
        assertSingleRequest();
    }

    @Test
    void serverErrorWithoutFaultPropagatesAsTransportException() {
        // A non-XML 500 is not read as a SOAP fault.
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("boom")));

        WebServiceTransportException thrown =
                assertThrows(WebServiceTransportException.class, () -> client.purchase(request("s-1", 1)));

        assertThat(thrown).isNotInstanceOf(SoapFaultClientException.class);
        assertThat(thrown.getMessage()).contains("500");
        assertSingleRequest();
    }

    @Test
    void readTimeoutPropagatesWithoutRetry() {
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", XML_CONTENT_TYPE)
                        .withBody(SUCCESS_BODY)
                        .withFixedDelay(RESPONSE_DELAY_MS)));
        SamsungServiceClient shortTimeoutClient = clientWithTimeout(SHORT_TIMEOUT_MS);

        WebServiceIOException thrown =
                assertThrows(WebServiceIOException.class, () -> shortTimeoutClient.purchase(request("s-1", 1)));

        assertTrue(thrown.contains(SocketTimeoutException.class),
                () -> "expected a SocketTimeoutException in the cause chain of " + thrown);
        assertSingleRequest();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void connectionFailureAfterRequestPropagatesWithoutRetry(Fault fault) {
        // A reset, or a close without any response, after the service has received the purchase: a default
        // HttpURLConnection would silently re-send the POST and place the order twice.
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH)).willReturn(aResponse().withFault(fault)));

        assertThrows(WebServiceIOException.class, () -> client.purchase(request("s-1", 1)));

        assertSingleRequest();
    }

    @Test
    void nullRequestSendsNothing() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> client.purchase(null));

        assertEquals("orderRequest", thrown.getMessage());
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "/samsung/orders", "ftp://localhost/samsung/orders", "http:///samsung/orders",
            "http://local host/samsung/orders"})
    void constructorRejectsInvalidUrl(String url) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SamsungServiceClient(messageFactory, samsungMarshaller, url, TIMEOUT_MS));

        assertThat(thrown.getMessage()).startsWith("orders.samsung.url");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Integer.MAX_VALUE + 1L})
    void constructorRejectsTimeoutOutOfRange(long timeoutMs) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SamsungServiceClient(messageFactory, samsungMarshaller, url(), timeoutMs));

        assertThat(thrown.getMessage()).startsWith("orders.samsung.timeout-ms");
    }

    private SamsungServiceClient clientWithTimeout(long timeoutMs) {
        return new SamsungServiceClient(messageFactory, samsungMarshaller, url(), timeoutMs);
    }

    private static String url() {
        return "http://localhost:" + wm.getPort() + SAMSUNG_PATH;
    }

    /** The order request of message.xml's Samsung item: {@code s-1}, quantity 1. */
    private static OrderRequest request(String name, int quantity) {
        OrderRequest request = new OrderRequest();
        request.setName(name);
        request.setQuantity(quantity);
        return request;
    }

    private static void stubReply(int status, String body) {
        wm.stubFor(post(urlEqualTo(SAMSUNG_PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", XML_CONTENT_TYPE)
                        .withBody(body)));
    }

    private static void assertAccepted(OrderResponse orderResponse) {
        assertNotNull(orderResponse, "orderResponse");
        assertEquals("1", orderResponse.getId());
        assertEquals("ACCEPTED", orderResponse.getResult());
        assertEquals("2550", orderResponse.getPrice());
    }

    /** Exactly one POST reached the Samsung path and no other request reached the server: no retry was made. */
    private static void assertSingleRequest() {
        wm.verify(1, postRequestedFor(urlEqualTo(SAMSUNG_PATH)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    private static LoggedRequest singleRequest() {
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(SAMSUNG_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    /** Evaluates the expression over a namespace-aware DOM of {@code xml}, with {@code soap} and {@code sam} bound. */
    private static String xpath(String expression, String xml) {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(Map.of("soap", SOAP_11_NS, "sam", SAMSUNG_NS));
        return engine.evaluate(expression, Input.fromString(xml).build());
    }
}
