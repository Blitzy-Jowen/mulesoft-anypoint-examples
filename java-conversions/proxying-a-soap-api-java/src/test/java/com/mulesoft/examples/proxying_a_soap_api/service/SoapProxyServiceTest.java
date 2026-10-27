/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.mulesoft.examples.proxying_a_soap_api.client.ShopServiceClient;
import com.mulesoft.examples.proxying_a_soap_api.mapper.ProxyHeaderMapper;

/**
 * Unit test of {@link SoapProxyService}, the original flow {@code main} of {@code soap-api-proxy.xml}: SC-13 header
 * copying on the caller's headers, the CXF proxy client's SOAP 1.1 outbound defaults (Content-Type and an empty
 * {@code SOAPAction}), one upstream POST of the envelope bytes, the requester's default success-status validator, and
 * SC-13 header copying on the upstream response. The client is a Mockito mock and the header mapper a spy on the real
 * SC-13 component, so the headers each leg carries are the ones the running proxy produces and the order of the calls
 * is observable.
 *
 * <p>Header names are compared through {@link HttpHeaders#get} and {@link HttpHeaders#containsKey}, which ignore
 * case; where the number of keys matters, names are counted ignoring case.
 */
class SoapProxyServiceTest {

    private static final String SOAP_ACTION = "SOAPAction";

    /** The empty SOAP 1.1 action the CXF proxy client sends: the two double-quote characters. */
    private static final String EMPTY_SOAP_ACTION = "\"\"";

    private static final String CALLER_SOAP_ACTION = "\"urn:create\"";

    private static final String SOAP11_CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final String SOAP12_CONTENT_TYPE = "application/soap+xml;charset=UTF-8;action=\"urn:create\"";

    /** The SOAP 1.1 default Content-Type the CXF proxy client sends, with a space after {@code ;}. */
    private static final String EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** The Mule 3.8 requester's default success-status validator message; {@code %d} is the upstream status. */
    private static final String EXPECTED_VALIDATOR_FAILURE_FORMAT =
            "Response code %d mapped as failure. Message payload is of type: BufferInputStream";

    /** The original test request {@code src/test/resources/message.xml}, byte for byte. */
    private static final byte[] REQUEST_ENVELOPE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" \
            xmlns:ns="http://predic8.com/wsdl/shop/1/">
            \t<soapenv:Header/>
            \t<soapenv:Body>
            \t<ns:create>
            \t<customerId>001</customerId>
            \t</ns:create>
            \t</soapenv:Body>
            </soapenv:Envelope>""".getBytes(UTF_8);

    private static final byte[] RESPONSE_ENVELOPE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>\
            <ns2:createResponse xmlns:ns2="http://predic8.com/wsdl/shop/1/"><cartId>7</cartId>\
            </ns2:createResponse></soap:Body></soap:Envelope>""".getBytes(UTF_8);

    private static final byte[] FAULT_ENVELOPE = """
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>\
            <faultcode>soap:Server</faultcode><faultstring>Customer 001 could not be processed</faultstring>\
            </soap:Fault></soap:Body></soap:Envelope>""".getBytes(UTF_8);

    private ShopServiceClient client;

    private ProxyHeaderMapper mapper;

    private SoapProxyService service;

    @BeforeEach
    void setUp() {
        client = mock(ShopServiceClient.class);
        mapper = spy(new ProxyHeaderMapper());
        service = new SoapProxyService(mapper, client);
    }

    /** The service's constants hold the wire values, so a wrong constant fails here and not only on the wire. */
    @Test
    void theDefaultContentTypeAndValidatorMessageConstantsHoldTheOriginalWireValues() {
        assertThat(SoapProxyService.UPSTREAM_SOAP11_CONTENT_TYPE).isEqualTo(EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE);
        assertThat(SoapProxyService.VALIDATOR_FAILURE_MESSAGE).isEqualTo(EXPECTED_VALIDATOR_FAILURE_FORMAT);
    }

    @Test
    void theFlowCopiesHeadersPostsOnceAndCopiesTheResponseHeadersInThatOrder() {
        HttpHeaders inbound = soap11RequestHeaders();
        HttpHeaders upstreamHeaders = upstreamResponseHeaders();
        stubUpstream(200, upstreamHeaders, RESPONSE_ENVELOPE);

        service.forward(inbound, REQUEST_ENVELOPE);

        InOrder order = inOrder(mapper, client);
        order.verify(mapper).toUpstream(eq(inbound));
        order.verify(client).post(any(HttpHeaders.class), eq(REQUEST_ENVELOPE));
        order.verify(mapper).toCaller(eq(upstreamHeaders));
        verify(mapper, times(1)).toUpstream(any());
        verify(mapper, times(1)).toCaller(any());
        verify(client, times(1)).post(any(), any());
        verifyNoMoreInteractions(client);
    }

    /** The body the caller sent and the bytes the client must receive: the envelope as sent, absent as empty. */
    static Stream<Arguments> requestBodies() {
        return Stream.of(
                Arguments.of(Named.of("the message.xml envelope", REQUEST_ENVELOPE), REQUEST_ENVELOPE),
                Arguments.of(Named.of("empty bytes", new byte[0]), new byte[0]),
                Arguments.of(Named.of("no body", null), new byte[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requestBodies")
    void theRequestBodyReachesTheClientUnchanged(byte[] sent, byte[] expected) {
        byte[] sentSnapshot = sent == null ? null : sent.clone();
        stubUpstream(200, new HttpHeaders(), RESPONSE_ENVELOPE);

        service.forward(soap11RequestHeaders(), sent);

        assertThat(forwardedBody()).isNotNull().isEqualTo(expected).hasSize(expected.length);
        assertThat(sent).isEqualTo(sentSnapshot);
    }

    /**
     * Caller Content-Type and the one the upstream must receive: a {@code text/xml} or {@code application/soap+xml}
     * type is kept as written, parameters included; an absent, blank, unparseable or other type is replaced.
     */
    static Stream<Arguments> contentTypes() {
        return Stream.of(
                Arguments.of(Named.of("absent", null), EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of(Named.of("blank", "   "), EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of(Named.of("unparseable xml", "xml"), EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of("text/plain", EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of("application/xml", EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of("application/json", EXPECTED_DEFAULT_SOAP11_CONTENT_TYPE),
                Arguments.of("text/xml", "text/xml"),
                Arguments.of("text/xml; charset=ISO-8859-1", "text/xml; charset=ISO-8859-1"),
                Arguments.of("application/soap+xml", "application/soap+xml"),
                Arguments.of(SOAP12_CONTENT_TYPE, SOAP12_CONTENT_TYPE));
    }

    @ParameterizedTest(name = "caller Content-Type {0}")
    @MethodSource("contentTypes")
    void theUpstreamReceivesExactlyOneSoapContentType(String callerContentType, String expected) {
        HttpHeaders inbound = new HttpHeaders();
        if (callerContentType != null) {
            inbound.add(HttpHeaders.CONTENT_TYPE, callerContentType);
        }
        HttpHeaders inboundSnapshot = deepCopy(inbound);
        stubUpstream(200, new HttpHeaders(), RESPONSE_ENVELOPE);

        service.forward(inbound, REQUEST_ENVELOPE);

        HttpHeaders upstream = forwardedHeaders();
        assertThat(upstream.get(HttpHeaders.CONTENT_TYPE)).containsExactly(expected);
        assertThat(keysNamed(upstream, HttpHeaders.CONTENT_TYPE)).hasSize(1);
        assertThat(inbound).isEqualTo(inboundSnapshot);
    }

    /**
     * SOAP 1.1 requests, by caller Content-Type ({@code null}: none sent; an absent, non-SOAP or unparseable type is
     * replaced by the SOAP 1.1 type), caller action header name and value ({@code null}: none sent), and the single
     * action the upstream must receive.
     */
    static Stream<Arguments> soap11Actions() {
        return Stream.of(
                Arguments.of(SOAP11_CONTENT_TYPE, null, null, EMPTY_SOAP_ACTION),
                Arguments.of(null, null, null, EMPTY_SOAP_ACTION),
                Arguments.of("application/json", null, null, EMPTY_SOAP_ACTION),
                Arguments.of("not a type", null, null, EMPTY_SOAP_ACTION),
                Arguments.of(SOAP11_CONTENT_TYPE, SOAP_ACTION, CALLER_SOAP_ACTION, CALLER_SOAP_ACTION),
                Arguments.of(SOAP11_CONTENT_TYPE, "soapaction", CALLER_SOAP_ACTION, CALLER_SOAP_ACTION));
    }

    @ParameterizedTest(name = "Content-Type {0}, caller header {1}: {2}")
    @MethodSource("soap11Actions")
    void aSoap11RequestReachesTheUpstreamWithExactlyOneSoapAction(String callerContentType, String actionName,
                                                                  String actionValue, String expected) {
        HttpHeaders inbound = new HttpHeaders();
        if (callerContentType != null) {
            inbound.add(HttpHeaders.CONTENT_TYPE, callerContentType);
        }
        if (actionName != null) {
            inbound.add(actionName, actionValue);
        }
        HttpHeaders inboundSnapshot = deepCopy(inbound);
        stubUpstream(200, new HttpHeaders(), RESPONSE_ENVELOPE);

        service.forward(inbound, REQUEST_ENVELOPE);

        HttpHeaders upstream = forwardedHeaders();
        assertThat(keysNamed(upstream, SOAP_ACTION)).hasSize(1);
        assertThat(upstream.get(SOAP_ACTION)).containsExactly(expected);
        assertThat(inbound).isEqualTo(inboundSnapshot);
    }

    @Test
    void aSoap12RequestWithoutSoapActionReachesTheUpstreamWithoutOne() {
        HttpHeaders inbound = new HttpHeaders();
        inbound.add(HttpHeaders.CONTENT_TYPE, SOAP12_CONTENT_TYPE);
        stubUpstream(200, new HttpHeaders(), RESPONSE_ENVELOPE);

        service.forward(inbound, REQUEST_ENVELOPE);

        HttpHeaders upstream = forwardedHeaders();
        assertThat(upstream.containsKey(SOAP_ACTION)).isFalse();
        assertThat(keysNamed(upstream, SOAP_ACTION)).isEmpty();
        assertThat(upstream.get(HttpHeaders.CONTENT_TYPE)).containsExactly(SOAP12_CONTENT_TYPE);
    }

    @Test
    void theCallersHeadersReachTheUpstreamWithoutTransportAndMuleProperties() {
        HttpHeaders inbound = new HttpHeaders();
        inbound.add(HttpHeaders.HOST, "localhost:8081");
        inbound.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(REQUEST_ENVELOPE.length));
        inbound.add(HttpHeaders.CONNECTION, "keep-alive");
        inbound.add(HttpHeaders.TRANSFER_ENCODING, "chunked");
        inbound.add("http.method", "POST");
        inbound.add("MULE_ENCODING", "UTF-8");
        inbound.add(HttpHeaders.AUTHORIZATION, "Bearer caller-token");
        inbound.add("X-Custom", "keep");
        inbound.add(HttpHeaders.CONTENT_TYPE, SOAP11_CONTENT_TYPE);
        inbound.add(SOAP_ACTION, CALLER_SOAP_ACTION);
        HttpHeaders inboundSnapshot = deepCopy(inbound);
        stubUpstream(200, new HttpHeaders(), RESPONSE_ENVELOPE);

        service.forward(inbound, REQUEST_ENVELOPE);

        HttpHeaders upstream = forwardedHeaders();
        assertThat(upstream).isNotSameAs(inbound);
        for (String removed : List.of(HttpHeaders.HOST, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONNECTION,
                HttpHeaders.TRANSFER_ENCODING, "http.method", "MULE_ENCODING")) {
            assertThat(upstream.containsKey(removed)).as(removed).isFalse();
        }
        // The caller's identity reaches the upstream exactly as received.
        assertThat(upstream.get(HttpHeaders.AUTHORIZATION)).containsExactly("Bearer caller-token");
        assertThat(upstream.get("X-Custom")).containsExactly("keep");
        assertThat(upstream.get(HttpHeaders.CONTENT_TYPE)).containsExactly(SOAP11_CONTENT_TYPE);
        assertThat(upstream.get(SOAP_ACTION)).containsExactly(CALLER_SOAP_ACTION);
        assertThat(upstream.size()).isEqualTo(4);
        assertThat(inbound).isEqualTo(inboundSnapshot);
    }

    @ParameterizedTest(name = "upstream {0}")
    @ValueSource(ints = {200, 302, 399})
    void everyUpstreamStatusBelow400IsAnswered200WithTheUnparsedBodyAndTheCopiedHeaders(int upstreamStatus) {
        HttpHeaders upstreamHeaders = upstreamResponseHeaders();
        stubUpstream(upstreamStatus, upstreamHeaders, RESPONSE_ENVELOPE);

        ResponseEntity<byte[]> result = service.forward(soap11RequestHeaders(), REQUEST_ENVELOPE);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(RESPONSE_ENVELOPE);
        HttpHeaders returned = result.getHeaders();
        assertThat(returned).isEqualTo(new ProxyHeaderMapper().toCaller(upstreamHeaders));
        assertThat(returned.get(HttpHeaders.CONTENT_TYPE)).containsExactly(SOAP11_CONTENT_TYPE);
        assertThat(returned.get("X-Upstream")).containsExactly("predic8");
        for (String removed : List.of(HttpHeaders.HOST, HttpHeaders.CONNECTION, HttpHeaders.TRANSFER_ENCODING,
                HttpHeaders.CONTENT_LENGTH, "MULE_ENCODING", "MULE_CORRELATION_ID")) {
            assertThat(returned.containsKey(removed)).as(removed).isFalse();
        }
        assertThat(returned.size()).isEqualTo(2);
        verify(client, times(1)).post(any(), any());
        verifyNoMoreInteractions(client);
    }

    /**
     * Failing upstream statuses with the body each answers with and the validator message spelled out; the status
     * alone decides the outcome.
     */
    static Stream<Arguments> failedUpstreamResponses() {
        return Stream.of(
                Arguments.of(400, Named.of("a text body", "Bad Request".getBytes(UTF_8)),
                        "Response code 400 mapped as failure. Message payload is of type: BufferInputStream"),
                Arguments.of(404, Named.of("an empty body", new byte[0]),
                        "Response code 404 mapped as failure. Message payload is of type: BufferInputStream"),
                Arguments.of(500, Named.of("a SOAP fault", FAULT_ENVELOPE),
                        "Response code 500 mapped as failure. Message payload is of type: BufferInputStream"),
                Arguments.of(503, Named.of("no body", null),
                        "Response code 503 mapped as failure. Message payload is of type: BufferInputStream"));
    }

    @ParameterizedTest(name = "upstream {0} with {1}")
    @MethodSource("failedUpstreamResponses")
    void everyUpstreamStatusFrom400FailsWithTheDefaultValidatorMessage(int upstreamStatus, byte[] upstreamBody,
                                                                       String expectedMessage) {
        stubUpstream(upstreamStatus, upstreamResponseHeaders(), upstreamBody);

        assertThatThrownBy(() -> service.forward(soap11RequestHeaders(), REQUEST_ENVELOPE))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage)
                .hasMessage(String.format(EXPECTED_VALIDATOR_FAILURE_FORMAT, upstreamStatus))
                .hasNoCause();
        verify(client, times(1)).post(any(), any());
        verifyNoMoreInteractions(client);
        // The validator fails before the second copy-headers, so no response header is mapped.
        verify(mapper, never()).toCaller(any());
    }

    @Test
    void anUpstreamResponseWithoutABodyIsAnsweredWithEmptyBytes() {
        HttpHeaders upstreamHeaders = upstreamResponseHeaders();
        stubUpstream(200, upstreamHeaders, null);

        ResponseEntity<byte[]> result = service.forward(soap11RequestHeaders(), REQUEST_ENVELOPE);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull().isEmpty();
        assertThat(result.getHeaders()).isEqualTo(new ProxyHeaderMapper().toCaller(upstreamHeaders));
    }

    @Test
    void aClientFailurePropagatesUnchangedAndIsNeverRetried() {
        ResourceAccessException timeout = new ResourceAccessException(
                "I/O error on POST request for \"http://www.predic8.com:8080/shop/ShopService\": Read timed out",
                new SocketTimeoutException("Read timed out"));
        when(client.post(any(), any())).thenThrow(timeout);

        assertThatThrownBy(() -> service.forward(soap11RequestHeaders(), REQUEST_ENVELOPE)).isSameAs(timeout);
        verify(client, times(1)).post(any(HttpHeaders.class), eq(REQUEST_ENVELOPE));
        verifyNoMoreInteractions(client);
        verify(mapper, never()).toCaller(any());
    }

    private void stubUpstream(int status, HttpHeaders headers, byte[] body) {
        when(client.post(any(), any())).thenReturn(ResponseEntity.status(status).headers(headers).body(body));
    }

    /** The headers of the single upstream post, after verifying it was the client's only call. */
    private HttpHeaders forwardedHeaders() {
        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(client, times(1)).post(headers.capture(), any());
        verifyNoMoreInteractions(client);
        return headers.getValue();
    }

    /** The body of the single upstream post, after verifying it was the client's only call. */
    private byte[] forwardedBody() {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(client, times(1)).post(any(), body.capture());
        verifyNoMoreInteractions(client);
        return body.getValue();
    }

    /** A SOAP 1.1 request as a SOAP client sends it to the proxy. */
    private static HttpHeaders soap11RequestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, SOAP11_CONTENT_TYPE);
        headers.add(SOAP_ACTION, EMPTY_SOAP_ACTION);
        headers.add(HttpHeaders.HOST, "localhost:8081");
        return headers;
    }

    /** Upstream response headers: two the caller receives and the transport and Mule ones copy-headers removes. */
    private static HttpHeaders upstreamResponseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, SOAP11_CONTENT_TYPE);
        headers.add("X-Upstream", "predic8");
        headers.add(HttpHeaders.HOST, "www.predic8.com:8080");
        headers.add(HttpHeaders.CONNECTION, "close");
        headers.add(HttpHeaders.TRANSFER_ENCODING, "chunked");
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(RESPONSE_ENVELOPE.length));
        headers.add("MULE_ENCODING", "UTF-8");
        headers.add("MULE_CORRELATION_ID", "4b1f0a52");
        return headers;
    }

    /** Every key of {@code headers} equal to {@code name} ignoring case, in their original casing. */
    private static List<String> keysNamed(HttpHeaders headers, String name) {
        return headers.keySet().stream().filter(name::equalsIgnoreCase).toList();
    }

    /**
     * Copies every name and value list. {@code new HttpHeaders(MultiValueMap)} is not used: it wraps the given map
     * as a live view and would not detect a modification.
     */
    private static HttpHeaders deepCopy(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        return copy;
    }
}
