/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.mapper.DwJsonWriter;
import com.mulesoft.examples.web_service_consumer.mapper.DwXmlToJson;
import com.mulesoft.examples.web_service_consumer.mapper.DwXmlWriter;
import com.mulesoft.examples.web_service_consumer.mapper.ListInventoryResponseMapper;
import com.mulesoft.examples.web_service_consumer.mapper.OrderTshirtRequestMapper;
import com.mulesoft.examples.web_service_consumer.mapper.OrderTshirtResponseMapper;

/**
 * Contract of {@link TshirtOrderService}, the two flows of {@code tshirt-service-consumer.xml}:
 * <ul>
 *   <li>{@code orderTshirt}: the JSON body becomes {@code ns0#OrderTshirt}, members unqualified and in body order,
 *       is sent through {@code OrderTshirt}, and the response payload is written as DataWeave JSON;</li>
 *   <li>{@code listInventory}: an absent or empty body sends the empty {@code ListInventory} request, an XML body
 *       is sent as it is, and {@code payload.ns0#ListInventoryResponse} is written as DataWeave JSON with one
 *       {@code "inventory"} key per item;</li>
 *   <li>a body neither flow can read is rejected before the service is called, and every client failure (SOAP
 *       fault, transport I/O error) reaches the caller as the same instance after exactly one call: neither flow
 *       declares an exception strategy, so nothing is caught or retried.</li>
 * </ul>
 * The original {@code WebServiceConsumerIT} assertions are kept with their contains semantics: the order response
 * contains {@code orderId}; the inventory response contains {@code "productCode": "1412"},
 * {@code "description": "Prueba2"} and {@code "count": "5"}.
 *
 * <p>Only the SOAP client is mocked. The mappers run over the project's real DataWeave readers and writers, and
 * the upstream responses are the original example's sample payloads, inlined.
 */
@ExtendWith(MockitoExtension.class)
class TshirtOrderServiceTest {

    private static final String TSHIRT_NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** {@code sample_data/OrderTshirtResponse.xml}, as the service answers it: qualified root, unqualified child. */
    private static final String ORDER_RESPONSE_XML =
            "<ns2:OrderTshirtResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
            + "<orderId>1</orderId>"
            + "</ns2:OrderTshirtResponse>";

    /** The {@code payload} script's JSON of {@link #ORDER_RESPONSE_XML}: prefix dropped, leaf as a string. */
    private static final String ORDER_RESPONSE_JSON = "{\n"
            + "  \"OrderTshirtResponse\": {\n"
            + "    \"orderId\": \"1\"\n"
            + "  }\n"
            + "}";

    /** {@code sample_data/ListInventoryResponse.xml}: five unqualified {@code inventory} items. */
    private static final String INVENTORY_RESPONSE_XML =
            "<ns2:ListInventoryResponse xmlns:ns2=\"http://mulesoft.org/tshirt-service\">"
            + inventory("4102", "L", "Prueba", "2")
            + inventory("1412", "L", "Foo", "9")
            + inventory("5656", "S", "Bar", "2")
            + inventory("5657", "M", "Prueba2", "3")
            + inventory("1411", "M", "Awesome Tshirt", "5")
            + "</ns2:ListInventoryResponse>";

    private static final String LIST_INVENTORY_REQUEST_XML =
            "<ns0:ListInventory xmlns:ns0=\"http://mulesoft.org/tshirt-service\"/>";

    private static final Pattern INVENTORY_KEY = Pattern.compile("\"inventory\"");

    @Mock
    private TshirtServiceClient client;

    private DwXmlToJson dwXmlToJson;

    private TshirtOrderService service;

    private byte[] messageBytes;

    @BeforeEach
    void setUp() throws IOException {
        dwXmlToJson = new DwXmlToJson();
        DwJsonWriter dwJsonWriter = new DwJsonWriter();
        service = new TshirtOrderService(
                new OrderTshirtRequestMapper(new DwXmlWriter()),
                client,
                new OrderTshirtResponseMapper(dwXmlToJson, dwJsonWriter),
                new ListInventoryResponseMapper(dwXmlToJson, dwJsonWriter),
                dwXmlToJson);
        messageBytes = readMessage();
    }

    @Test
    void orderTshirtWritesResponsePayloadAsDataWeaveJson() throws Exception {
        when(client.orderTshirt(any())).thenReturn(dwXmlToJson.parse(ORDER_RESPONSE_XML));

        String result = service.orderTshirt(messageBytes);

        assertThat(result).isEqualTo(ORDER_RESPONSE_JSON);
        assertThat(result).contains("orderId");
    }

    @Test
    void orderTshirtSendsOrderTshirtElementWithMembersInBodyOrder() throws Exception {
        when(client.orderTshirt(any())).thenReturn(dwXmlToJson.parse(ORDER_RESPONSE_XML));

        service.orderTshirt(messageBytes);

        ArgumentCaptor<Document> request = ArgumentCaptor.forClass(Document.class);
        verify(client, times(1)).orderTshirt(request.capture());
        verifyNoMoreInteractions(client);

        Element root = request.getValue().getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(root.getPrefix()).isEqualTo("ns0");

        List<Element> members = elementChildren(root);
        assertThat(members).allSatisfy(member -> assertThat(member.getNamespaceURI()).isNull());
        assertThat(members).extracting(Element::getLocalName).containsExactly(
                "email", "address1", "address2", "city", "country", "name", "postalCode", "size",
                "stateOrProvince");
        assertThat(members).extracting(Element::getTextContent).containsExactly(
                "customerID@gmail.com", "Corrientes 316", "EP", "Buenos Aires", "Argentina", "MuleSoft Argentina",
                "C1043AAQ", "L", "CABA");
    }

    @Test
    void orderTshirtReturnsNullWhenServiceReturnsNoPayload() throws Exception {
        // The unstubbed mock answers null, the client's result for a response without a Body child.
        assertThat(service.orderTshirt(messageBytes)).isNull();

        verify(client, times(1)).orderTshirt(any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unreadableOrderBodies")
    void orderTshirtRejectsUnreadableBodyBeforeCallingService(byte[] body, String message) throws Exception {
        assertThatThrownBy(() -> service.orderTshirt(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);

        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("absentBodies")
    void listInventorySendsEmptyRequestWhenBodyIsAbsent(byte[] body) throws Exception {
        when(client.listInventory(isNull())).thenReturn(dwXmlToJson.parse(INVENTORY_RESPONSE_XML));

        String result = service.listInventory(body);

        verify(client, times(1)).listInventory(isNull());
        verifyNoMoreInteractions(client);
        assertThat(result)
                .contains("\"productCode\": \"1412\"")
                .contains("\"description\": \"Prueba2\"")
                .contains("\"count\": \"5\"");
        assertThat(INVENTORY_KEY.matcher(result).results().count()).isEqualTo(5);
    }

    @Test
    void listInventorySendsSuppliedXmlBodyAsRequest() throws Exception {
        when(client.listInventory(any())).thenReturn(dwXmlToJson.parse(INVENTORY_RESPONSE_XML));

        String result = service.listInventory(LIST_INVENTORY_REQUEST_XML.getBytes(UTF_8));

        ArgumentCaptor<Document> request = ArgumentCaptor.forClass(Document.class);
        verify(client, times(1)).listInventory(request.capture());
        verifyNoMoreInteractions(client);
        assertThat(request.getValue()).isNotNull();
        Element root = request.getValue().getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("ListInventory");
        assertThat(root.getNamespaceURI()).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(result).isNotNull().contains("\"productCode\": \"1412\"");
    }

    @Test
    void listInventoryReturnsNullWhenServiceReturnsNoPayload() throws Exception {
        // The unstubbed mock answers null, the client's result for a response without a Body child.
        assertThat(service.listInventory(null)).isNull();

        verify(client, times(1)).listInventory(isNull());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unreadableInventoryBodies")
    void listInventoryRejectsNonXmlBodyBeforeCallingService(byte[] body, String message) throws Exception {
        assertThatThrownBy(() -> service.listInventory(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);

        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("clientFailures")
    void orderTshirtPropagatesClientFailureUnchangedWithoutRetry(RuntimeException failure) throws Exception {
        when(client.orderTshirt(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.orderTshirt(messageBytes)).isSameAs(failure);

        verify(client, times(1)).orderTshirt(any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("clientFailures")
    void listInventoryPropagatesClientFailureUnchangedWithoutRetry(RuntimeException failure) throws Exception {
        when(client.listInventory(isNull())).thenThrow(failure);

        assertThatThrownBy(() -> service.listInventory(null)).isSameAs(failure);

        verify(client, times(1)).listInventory(isNull());
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> unreadableOrderBodies() {
        return Stream.of(
                Arguments.of(Named.of("no body", null), "Request body is empty"),
                Arguments.of(Named.of("empty body", new byte[0]), "Request body is empty"),
                Arguments.of(Named.of("malformed JSON", "{not json".getBytes(UTF_8)), "Unexpected character"),
                Arguments.of(Named.of("JSON array", "[]".getBytes(UTF_8)),
                        "XML output requires a single root element"));
    }

    static Stream<Arguments> absentBodies() {
        return Stream.of(
                Arguments.of(Named.of("no body", null)),
                Arguments.of(Named.of("empty body", new byte[0])));
    }

    static Stream<Arguments> unreadableInventoryBodies() {
        return Stream.of(
                Arguments.of(Named.of("not XML", "not xml".getBytes(UTF_8)), "Content is not allowed in prolog"),
                Arguments.of(Named.of("whitespace only", " \n\t".getBytes(UTF_8)), "XML payload is empty"),
                Arguments.of(Named.of("unclosed element", "<ListInventory>".getBytes(UTF_8)),
                        "XML document structures must start and end within the same entity"));
    }

    static Stream<Arguments> clientFailures() throws Exception {
        return Stream.of(
                Arguments.of(Named.of("SOAP fault", soapFault())),
                Arguments.of(Named.of("I/O failure",
                        new WebServiceIOException("I/O error", new SocketTimeoutException("Read timed out")))));
    }

    /** A real SOAP 1.1 client fault, as {@code WebServiceTemplate} raises it for a fault response. */
    private static SoapFaultClientException soapFault() throws Exception {
        SaajSoapMessageFactory factory =
                new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        SoapMessage message = factory.createWebServiceMessage();
        message.getSoapBody().addClientOrSenderFault("Invalid API key", Locale.ENGLISH);
        return new SoapFaultClientException(message);
    }

    private static String inventory(String productCode, String size, String description, String count) {
        return "<inventory>"
                + "<productCode>" + productCode + "</productCode>"
                + "<size>" + size + "</size>"
                + "<description>" + description + "</description>"
                + "<count>" + count + "</count>"
                + "</inventory>";
    }

    /** Element children only: the tab-indented DataWeave XML keeps whitespace text nodes between members. */
    private static List<Element> elementChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static byte[] readMessage() throws IOException {
        try (InputStream in = TshirtOrderServiceTest.class.getResourceAsStream("/message.json")) {
            assertThat(in).as("test resource /message.json (copy of the original sample order)").isNotNull();
            return in.readAllBytes();
        }
    }
}
