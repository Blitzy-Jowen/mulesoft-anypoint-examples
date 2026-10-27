/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;
import com.mulesoft.examples.legacy_modernization.model.OrderItem;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;

/**
 * Unit test of {@link ShippingOrderCsvMapper}, the owner of the "Map Fullfilment order to CSV" script of the
 * original {@code FufillmentWebService.xml} ({@code %output application/csv header=true}): one row per
 * {@code shippingOrder.order.orderItemList} element with the script's fifteen columns in the script's order, the
 * street columns joining {@code line1} and {@code line2} with one space, and the bytes of the DataWeave 1.0 CSV
 * policy (comma separator, header row, LF after every line, the last included).
 *
 * <p>The three-item expectation is the confirmation of the original test request {@code message.xml}, written
 * inline from the script's column list and that request's values. Failures on a {@code null} part the script
 * navigates are the mapper's documented {@link IllegalArgumentException}s; the original runtime's error text is a
 * Tier 2A verification point.
 *
 * <p>The collaborator is real: {@link DwCsvWriter} writes the CSV. No Spring context or mock is used.
 */
class ShippingOrderCsvMapperTest {

    private static final String ORDER_MGMT_NAMESPACE = "http://ordermgmt.org/";

    private static final List<String> COLUMNS = List.of(
            "MSKU", "QTY",
            "BillingAddressName", "BillingAddressStreet", "BillingAddrCity", "BillingAddrState",
            "BillingAddrCountry", "BillingAddrZipCode",
            "ShippingAddrName", "ShippingAddrStreet", "ShippingAddrCity", "ShippingAddrState",
            "ShippingAddrCountry", "ShippingAddrZipCode",
            "ShippingId");

    private static final String HEADER = "MSKU,QTY,BillingAddressName,BillingAddressStreet,BillingAddrCity,"
            + "BillingAddrState,BillingAddrCountry,BillingAddrZipCode,ShippingAddrName,ShippingAddrStreet,"
            + "ShippingAddrCity,ShippingAddrState,ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n";

    private static final String EXPECTED_CSV = HEADER
            + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n";

    private final ShippingOrderCsvMapper mapper = new ShippingOrderCsvMapper(new DwCsvWriter());

    @Test
    @DisplayName("toCsv: three-item Mulesoft confirmation -> header and three rows, LF after every line, no CR")
    void toCsvWritesMulesoftConfirmation() {
        String csv = mapper.toCsv(mulesoftConfirmation());

        assertThat(csv).isEqualTo(EXPECTED_CSV)
                .endsWith("\n")
                .doesNotContain("\r");
        assertThat(csv.chars().filter(c -> c == '\n').count()).isEqualTo(4);
        assertThat(csv.lines()).hasSize(4);
    }

    @Test
    @DisplayName("toCsv: the confirmation of the original test request message.xml, JAXB-unmarshalled -> same CSV")
    void toCsvWritesConfirmationOfOriginalTestMessage() throws Exception {
        Element request = putShippingOrder();
        Unmarshaller unmarshaller = JAXBContext.newInstance(Address.class, Order.class).createUnmarshaller();
        String shippingId = child(request, "shippingId").getTextContent();
        Address billing = unmarshaller.unmarshal(child(request, "billingAddress"), Address.class).getValue();
        Address shipping = unmarshaller.unmarshal(child(request, "shippingAddress"), Address.class).getValue();
        Order order = unmarshaller.unmarshal(child(request, "order"), Order.class).getValue();
        // The original FulfillmentImpl wraps the four operation parameters exactly this way.
        ShippingOrderConfirmation confirmation =
                new ShippingOrderConfirmation(new ShippingOrder(shippingId, billing, shipping, order), true);

        assertThat(mapper.toCsv(confirmation)).isEqualTo(EXPECTED_CSV);
    }

    @Test
    @DisplayName("map: one LinkedHashMap per item, in item order, keyed by the fifteen columns in header order")
    void mapBuildsOneOrderedRowPerItem() {
        List<Map<String, Object>> rows = mapper.map(mulesoftConfirmation());

        assertThat(String.join(",", COLUMNS) + "\n").isEqualTo(HEADER);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("MSKU")).containsExactly("1234", "6789", "9998");
        assertThat(rows).extracting(row -> row.get("QTY")).containsExactly(500, 1500, 5000);
        for (Map<String, Object> row : rows) {
            assertThat(row).isInstanceOf(LinkedHashMap.class);
            assertThat(row.keySet()).containsExactlyElementsOf(COLUMNS);
            assertThat(row.get("QTY")).isInstanceOf(Integer.class);
        }
        assertThat(rows.get(0)).containsExactly(
                Map.entry("MSKU", "1234"),
                Map.entry("QTY", 500),
                Map.entry("BillingAddressName", "Mulesoft"),
                Map.entry("BillingAddressStreet", "77 Geary St Level 4"),
                Map.entry("BillingAddrCity", "San Francisco"),
                Map.entry("BillingAddrState", "CA"),
                Map.entry("BillingAddrCountry", "USA"),
                Map.entry("BillingAddrZipCode", "94108"),
                Map.entry("ShippingAddrName", "Mulesoft"),
                Map.entry("ShippingAddrStreet", "77 Geary St Level 4"),
                Map.entry("ShippingAddrCity", "San Francisco"),
                Map.entry("ShippingAddrState", "CA"),
                Map.entry("ShippingAddrCountry", "USA"),
                Map.entry("ShippingAddrZipCode", "94108"),
                Map.entry("ShippingId", "1234"));
    }

    @Test
    @DisplayName("billing columns come from billingAddress, shipping columns from shippingAddress")
    void billingAndShippingColumnsComeFromTheirOwnAddress() {
        Address billing = new Address("Bill Co", "1 A St", "Suite 1", "Austin", "TX", "USA", "73301");
        ShippingOrderConfirmation confirmation =
                confirmation(billing, mulesoftAddress(), order(new OrderItem("42", 7)));

        assertThat(mapper.toCsv(confirmation)).isEqualTo(HEADER
                + "42,7,Bill Co,1 A St Suite 1,Austin,TX,USA,73301,"
                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n");
        assertThat(mapper.map(confirmation)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("BillingAddressName", "Bill Co")
                .containsEntry("BillingAddressStreet", "1 A St Suite 1")
                .containsEntry("ShippingAddrName", "Mulesoft")
                .containsEntry("ShippingAddrStreet", "77 Geary St Level 4"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullNameCases")
    @DisplayName("a null address name -> an empty field; the column stays, mapped to null")
    void nullAddressNameIsWrittenAsEmptyField(String column, ShippingOrderConfirmation confirmation,
            String expectedRow) {
        assertThat(mapper.toCsv(confirmation)).isEqualTo(HEADER + expectedRow);
        assertThat(mapper.map(confirmation)).singleElement().satisfies(row -> {
            assertThat(row.keySet()).containsExactlyElementsOf(COLUMNS);
            assertThat(row).containsEntry(column, null);
        });
    }

    static Stream<Arguments> nullNameCases() {
        Address unnamed = new Address(null, "77 Geary St", "Level 4", "San Francisco", "CA", "USA", "94108");
        return Stream.of(
                Arguments.of("BillingAddressName",
                        confirmation(unnamed, mulesoftAddress(), order(new OrderItem("1234", 500))),
                        "1234,500,,77 Geary St Level 4,San Francisco,CA,USA,94108,"
                                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"),
                Arguments.of("ShippingAddrName",
                        confirmation(mulesoftAddress(), unnamed, order(new OrderItem("1234", 500))),
                        "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
                                + ",77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"));
    }

    @ParameterizedTest(name = "{0} -> \"{1}\"")
    @MethodSource("nullPartCases")
    @DisplayName("a null part the script navigates -> IllegalArgumentException naming it, from map and toCsv")
    void nullNavigatedPartIsRejected(ShippingOrderConfirmation confirmation, String message) {
        assertThatThrownBy(() -> mapper.map(confirmation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> mapper.toCsv(confirmation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> nullPartCases() {
        Address noLine1 = new Address("Mulesoft", null, "Level 4", "San Francisco", "CA", "USA", "94108");
        Address noLine2 = new Address("Mulesoft", "77 Geary St", null, "San Francisco", "CA", "USA", "94108");
        return Stream.of(
                Arguments.of(Named.of("confirmation null", null),
                        "ShippingOrderConfirmation is null"),
                Arguments.of(Named.of("shippingOrder null", new ShippingOrderConfirmation(null, true)),
                        "shippingOrder is null"),
                Arguments.of(Named.of("order null",
                                confirmation(mulesoftAddress(), mulesoftAddress(), null)),
                        "shippingOrder.order is null"),
                Arguments.of(Named.of("orderItemList null",
                                confirmation(mulesoftAddress(), mulesoftAddress(), new Order(null))),
                        "shippingOrder.order.orderItemList is null"),
                Arguments.of(Named.of("billingAddress null",
                                confirmation(null, mulesoftAddress(), order(new OrderItem("1234", 500)))),
                        "shippingOrder.billingAddress is null"),
                Arguments.of(Named.of("shippingAddress null",
                                confirmation(mulesoftAddress(), null, order(new OrderItem("1234", 500)))),
                        "shippingOrder.shippingAddress is null"),
                Arguments.of(Named.of("billingAddress.line1 null",
                                confirmation(noLine1, mulesoftAddress(), order(new OrderItem("1234", 500)))),
                        "shippingOrder.billingAddress.line1 is null"),
                Arguments.of(Named.of("billingAddress.line2 null",
                                confirmation(noLine2, mulesoftAddress(), order(new OrderItem("1234", 500)))),
                        "shippingOrder.billingAddress.line2 is null"),
                Arguments.of(Named.of("shippingAddress.line1 null",
                                confirmation(mulesoftAddress(), noLine1, order(new OrderItem("1234", 500)))),
                        "shippingOrder.shippingAddress.line1 is null"),
                Arguments.of(Named.of("shippingAddress.line2 null",
                                confirmation(mulesoftAddress(), noLine2, order(new OrderItem("1234", 500)))),
                        "shippingOrder.shippingAddress.line2 is null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyItemListCases")
    @DisplayName("an empty orderItemList -> no rows and no bytes, not even the header")
    void emptyItemListYieldsNoRows(ShippingOrderConfirmation confirmation) {
        assertThat(mapper.toCsv(confirmation)).isEmpty();
        assertThat(mapper.map(confirmation)).isEmpty();
    }

    static Stream<Arguments> emptyItemListCases() {
        // The script's map body, which reads the addresses, never runs over an empty list.
        return Stream.of(
                Arguments.of(Named.of("Mulesoft addresses",
                        confirmation(mulesoftAddress(), mulesoftAddress(), order()))),
                Arguments.of(Named.of("null addresses", confirmation(null, null, order()))));
    }

    private static ShippingOrderConfirmation mulesoftConfirmation() {
        return confirmation(mulesoftAddress(), mulesoftAddress(), order(
                new OrderItem("1234", 500), new OrderItem("6789", 1500), new OrderItem("9998", 5000)));
    }

    private static ShippingOrderConfirmation confirmation(Address billing, Address shipping, Order order) {
        return new ShippingOrderConfirmation(new ShippingOrder("1234", billing, shipping, order), true);
    }

    private static Order order(OrderItem... items) {
        return new Order(new ArrayList<>(List.of(items)));
    }

    private static Address mulesoftAddress() {
        return new Address("Mulesoft", "77 Geary St", "Level 4", "San Francisco", "CA", "USA", "94108");
    }

    private static Element putShippingOrder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document;
        try (InputStream in = ShippingOrderCsvMapperTest.class.getResourceAsStream("/message.xml")) {
            assertThat(in).as("classpath resource /message.xml").isNotNull();
            document = factory.newDocumentBuilder().parse(in);
        }
        NodeList found = document.getElementsByTagNameNS(ORDER_MGMT_NAMESPACE, "putShippingOrder");
        assertThat(found.getLength()).as("putShippingOrder elements in %s", ORDER_MGMT_NAMESPACE).isEqualTo(1);
        return (Element) found.item(0);
    }

    // The operation's parameters are unqualified children of the wrapper, as in the original request.
    private static Element child(Element parent, String localName) {
        Element match = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNamespaceURI() == null
                    && localName.equals(node.getLocalName())) {
                assertThat(match).as("a second <%s> under <%s>", localName, parent.getLocalName()).isNull();
                match = (Element) node;
            }
        }
        assertThat(match).as("<%s> under <%s>", localName, parent.getLocalName()).isNotNull();
        return match;
    }
}
