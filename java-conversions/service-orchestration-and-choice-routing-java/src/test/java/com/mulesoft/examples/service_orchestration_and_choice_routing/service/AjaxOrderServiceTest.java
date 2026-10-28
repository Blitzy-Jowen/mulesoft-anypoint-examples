/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.client.OrderProcessingSoapClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.AjaxOrderXmlMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.DwJsonWriter;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.DwXmlToJson;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.DwXmlWriter;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.OrderSummaryJsonMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.SoapEnvelopeTransformer;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.AjaxChannelBroker.InboundHandler;

/**
 * Unit test of {@link AjaxOrderService}, the three flows behind the Ajax inbound endpoints of
 * {@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml} (lines 5-46):
 * <ul>
 *   <li>{@code orderRequest} on {@code /orders/request}: "JSON to XML", {@code XmlToSoapEnvelope.xsl}, POST to the
 *       1080 {@code /orders} service, {@code SoapEnvelopeToBodyChild.xslt}, "XML to JSON";</li>
 *   <li>{@code orderProxy} on {@code /orders/soap}: the published envelope posted unchanged, the reply returned as
 *       text;</li>
 *   <li>{@code manufacturers} on {@code /orders/manufacturers}: the literal {@code ["Samsung","Philips","Sony"]}.</li>
 * </ul>
 *
 * <p>The mappers, the SC-4 DataWeave reader and writers and the stylesheets are the real ones, so the test fixes the
 * whole chain the flow ran; the broker and the 1080 client are Mockito mocks, so no Spring context starts and nothing
 * leaves the JVM. The published order is the original's DataWeave sample {@code sample_data/json_1.json}; the 1080
 * reply is the original test's {@code reply.xml}, whose summary the flow returns as the DataWeave 1.0 JSON that
 * {@code mapper/OrderSummaryJsonMapperTest} also asserts: two-space indentation, {@code "key": value}, LF line ends,
 * no trailing line feed, every leaf a string.
 */
@ExtendWith(MockitoExtension.class)
class AjaxOrderServiceTest {

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String ORD_NS = "http://orders.se.mulesoft.com/";

    private static final String SERVER_PATH = "/orders";

    /** {@code sample_data/json_1.json}, the order the page publishes, with its tabs written as spaces. */
    private static final String ORDER_JSON = """
            {
                "orderId" : "123",
                "customer" : {
                    "firstName": "Nial",
                    "lastName" : "Darbey",
                    "address" : "Buenos Aires"
                },
                "orderItems": [
                    {
                    "item" : {
                        "manufacturer" : "Samsung",
                        "name" : "Galaxy",
                        "productId" : "i9000",
                        "quantity" : "30"
                    }},
                    {
                    "item" : {
                        "manufacturer" : "Philips",
                        "name" : "P3123",
                        "productId" : "23",
                        "quantity" : "10"
                    }
                    }
                ]
            }""";

    /**
     * The {@code processOrder} request built from {@link #ORDER_JSON}: DataWeave selected
     * {@code payload.orderItems.item}, so each item is its own {@code orderItems} element with no {@code item}
     * wrapper, and the customer keeps the members in body order.
     */
    private static final String EXPECTED_ENVELOPE = "<soapenv:Envelope xmlns:soapenv=\"" + SOAPENV_NS
            + "\" xmlns:ord=\"" + ORD_NS + "\"><soapenv:Body><ord:processOrder><order>"
            + "<orderId>123</orderId>"
            + "<customer><firstName>Nial</firstName><lastName>Darbey</lastName><address>Buenos Aires</address>"
            + "</customer>"
            + "<orderItems><manufacturer>Samsung</manufacturer><name>Galaxy</name><productId>i9000</productId>"
            + "<quantity>30</quantity></orderItems>"
            + "<orderItems><manufacturer>Philips</manufacturer><name>P3123</name><productId>23</productId>"
            + "<quantity>10</quantity></orderItems>"
            + "</order></ord:processOrder></soapenv:Body></soapenv:Envelope>";

    /** The 1080 {@code processOrderResponse} of the original test: order 12, one accepted Samsung item. */
    private static final String REPLY_XML = resource("/reply.xml");

    /** The original test's {@code processOrder} request, as the page's SOAP field publishes it on {@code /soap}. */
    private static final String MESSAGE_XML = resource("/message.xml");

    /** The summary of {@link #REPLY_XML} as the "XML to JSON" script wrote it. */
    private static final String EXPECTED_SUMMARY_JSON = """
            {
              "orderId": "12",
              "customer": {
                "address": "Main Street 123",
                "firstName": "John",
                "lastName": "Doe"
              },
              "orderItems": {
                "item": {
                  "manufacturer": "Samsung",
                  "name": "s-1",
                  "productId": "AX02",
                  "purchaseReceipt": {
                    "id": "1",
                    "status": "ACCEPTED",
                    "totalPrice": "2550.0"
                  },
                  "quantity": "1"
                }
              }
            }""";

    @Mock
    private AjaxChannelBroker broker;

    @Mock
    private OrderProcessingSoapClient soapClient;

    private AjaxOrderService service;

    @BeforeEach
    void setUp() {
        service = newService(SERVER_PATH);
    }

    @Test
    void registerBindsThreeInboundChannels() throws Exception {
        when(soapClient.postOrderRequest(anyString())).thenReturn(REPLY_XML);
        when(soapClient.proxy(MESSAGE_XML)).thenReturn(REPLY_XML);

        service.register();

        ArgumentCaptor<String> channels = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InboundHandler> handlers = ArgumentCaptor.forClass(InboundHandler.class);
        verify(broker, times(3)).registerInbound(channels.capture(), handlers.capture());
        verifyNoMoreInteractions(broker);
        assertThat(channels.getAllValues())
                .containsExactly("/orders/request", "/orders/soap", "/orders/manufacturers");

        List<InboundHandler> registered = handlers.getAllValues();
        assertThat(registered.get(0).handle(ORDER_JSON)).isEqualTo(EXPECTED_SUMMARY_JSON);
        assertThat(registered.get(1).handle(MESSAGE_XML)).isEqualTo(REPLY_XML);
        assertThat(registered.get(2).handle("ignored")).isEqualTo(AjaxOrderService.MANUFACTURERS);
        verify(soapClient).postOrderRequest(anyString());
        verify(soapClient).proxy(MESSAGE_XML);
        verifyNoMoreInteractions(soapClient);
    }

    /**
     * The configured path is normalized as the docroot path is, so the channels never carry {@code //}; the root
     * path, and no path at all (the empty first column is {@code null}), hang the channels off {@code /}.
     */
    @ParameterizedTest(name = "[{index}] ajax.server-path=''{0}''")
    @CsvSource(value = {
        "/orders,       /orders",
        "orders,        /orders",
        "/orders/,      /orders",
        "'  /orders// ', /orders",
        "/shop/orders,  /shop/orders",
        "/,             ''",
        ",              ''"
    })
    void serverPathIsNormalizedBeforeChannelsAreNamed(String configured, String prefix) {
        newService(configured).register();

        ArgumentCaptor<String> channels = ArgumentCaptor.forClass(String.class);
        verify(broker, times(3)).registerInbound(channels.capture(), any());
        assertThat(channels.getAllValues())
                .containsExactly(prefix + "/request", prefix + "/soap", prefix + "/manufacturers");
    }

    @Test
    void orderRequestProducesSummaryJson() throws Exception {
        when(soapClient.postOrderRequest(anyString())).thenReturn(REPLY_XML);

        String json = service.orderRequest(ORDER_JSON);

        assertThat(json).isEqualTo(EXPECTED_SUMMARY_JSON);
        ArgumentCaptor<String> posted = ArgumentCaptor.forClass(String.class);
        verify(soapClient).postOrderRequest(posted.capture());
        verify(soapClient, never()).proxy(any());

        Element envelope = parse(posted.getValue()).getDocumentElement();
        assertThat(envelope.getNamespaceURI()).isEqualTo(SOAPENV_NS);
        assertThat(envelope.getLocalName()).isEqualTo("Envelope");
        Element body = firstChildElement(envelope);
        assertThat(body.getNamespaceURI()).isEqualTo(SOAPENV_NS);
        assertThat(body.getLocalName()).isEqualTo("Body");
        Element processOrder = firstChildElement(body);
        assertThat(processOrder.getNamespaceURI()).isEqualTo(ORD_NS);
        assertThat(processOrder.getLocalName()).isEqualTo("processOrder");
        assertXmlSimilar(EXPECTED_ENVELOPE, posted.getValue());
    }

    @Test
    void nonStringPayloadIsSerializedToJsonFirst() throws Exception {
        when(soapClient.postOrderRequest(anyString())).thenReturn(REPLY_XML);
        Map<?, ?> order = new ObjectMapper().readValue(ORDER_JSON, Map.class);

        String fromText = service.orderRequest(ORDER_JSON);
        String fromMap = service.orderRequest(order);

        assertThat(fromMap).isEqualTo(fromText).isEqualTo(EXPECTED_SUMMARY_JSON);
        ArgumentCaptor<String> posted = ArgumentCaptor.forClass(String.class);
        verify(soapClient, times(2)).postOrderRequest(posted.capture());
        Diff diff = DiffBuilder.compare(Input.fromString(posted.getAllValues().get(0)))
                .withTest(Input.fromString(posted.getAllValues().get(1)))
                .ignoreWhitespace()
                .checkForIdentical()
                .build();
        assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
    }

    @Test
    void unserializablePublicationIsRejectedBeforeAnyCall() {
        Object unserializable = new Object();

        assertThatThrownBy(() -> service.orderRequest(unserializable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ajax publication cannot be rendered as JSON")
                .hasCauseInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(soapClient);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("proxiedPublications")
    void orderProxyReturnsUpstreamTextUnchanged(Object published, String sent) {
        when(soapClient.proxy(sent)).thenReturn(REPLY_XML);

        assertThat(service.orderProxy(published)).isEqualTo(REPLY_XML);

        verify(soapClient).proxy(sent);
        verify(soapClient, never()).postOrderRequest(any());
    }

    static Stream<Arguments> proxiedPublications() {
        return Stream.of(
                arguments(named("envelope text, forwarded byte for byte", MESSAGE_XML), MESSAGE_XML),
                arguments(named("nothing published, forwarded as empty text", null), ""));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "{\"manufacturer\": \"Sony\"}")
    void manufacturersIsConstant(String published) {
        assertThat(service.manufacturers(published))
                .isEqualTo("[\"Samsung\",\"Philips\",\"Sony\"]")
                .isEqualTo(AjaxOrderService.MANUFACTURERS);
        verifyNoInteractions(soapClient, broker);
    }

    @Test
    void clientFailurePropagates() {
        RestClientException failure = new RestClientException("down");
        when(soapClient.postOrderRequest(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> service.orderRequest(ORDER_JSON)).isSameAs(failure);
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"{not json", "[1, 2]"})
    void malformedOrderJsonFails(String published) {
        assertThatThrownBy(() -> service.orderRequest(published)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(soapClient);
    }

    private AjaxOrderService newService(String serverPath) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new AjaxOrderService(broker, new AjaxOrderXmlMapper(new DwXmlWriter(), objectMapper),
                new SoapEnvelopeTransformer(), soapClient,
                new OrderSummaryJsonMapper(new DwXmlToJson(), new DwJsonWriter()), objectMapper, serverPath);
    }

    private static void assertXmlSimilar(String expected, String actual) {
        Diff diff = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.fromString(actual))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static Element firstChildElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                return element;
            }
        }
        throw new AssertionError("<" + parent.getTagName() + "> has no child element");
    }

    private static String resource(String name) {
        try (InputStream in = AjaxOrderServiceTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
