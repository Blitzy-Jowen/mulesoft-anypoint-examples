package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

/**
 * Unit test of {@link PutCustomerRequestMapper}, the sole owner of the "XML to Workday PutCustomerRequest XML" script
 * ({@code add_a_new_customer.xml:12}). Every case maps a parsed {@code root/Account} document, marshals the result with
 * the XJC binding of the Revenue_Management v25.0 schema and asserts the marshalled DOM by XPath, so what is checked is
 * the request the Workday client puts on the wire: the values, their cardinality, the {@code wd:}-qualified
 * {@code type} attributes, the schema element order and the parts the script never wrote.
 */
@DisplayName("PutCustomerRequestMapper (DW add_a_new_customer.xml:12)")
final class PutCustomerRequestMapperTest {

    private static final String WD = "urn:com.workday/bsvc";

    private static final String REQUEST = "/wd:Put_Customer_Request";
    private static final String CUSTOMER_DATA = "/wd:Put_Customer_Request/wd:Customer_Data";
    private static final String CUSTOMER_ID = CUSTOMER_DATA + "/wd:Customer_ID";
    private static final String CUSTOMER_REFERENCE_ID = CUSTOMER_DATA + "/wd:Customer_Reference_ID";
    private static final String CUSTOMER_NAME = CUSTOMER_DATA + "/wd:Customer_Name";
    private static final String BUSINESS_ENTITY_DATA = CUSTOMER_DATA + "/wd:Business_Entity_Data";
    private static final String BUSINESS_ENTITY_NAME = BUSINESS_ENTITY_DATA + "/wd:Business_Entity_Name";
    private static final String CATEGORY_ID = CUSTOMER_DATA + "/wd:Customer_Category_Reference/wd:ID";
    private static final String STATUS_DATA = CUSTOMER_DATA + "/wd:Customer_Status_Data";
    private static final String STATUS_ID = STATUS_DATA + "/wd:Customer_Status_Value_Reference/wd:ID";

    /** Inline input whose every field holds a distinct value, so each output traces back to its own source field. */
    private static final String DISTINCT_VALUES_XML = """
            <root>
              <Account>
                <CustomerName>Acme</CustomerName>
                <BusinessEntityName>Acme Corp</BusinessEntityName>
                <Customer_Category_Reference_Type>Customer_Category_ID</Customer_Category_Reference_Type>
                <Customer_Category_Reference_Value>CUSTOMER_CATEGORY-9</Customer_Category_Reference_Value>
                <Customer_Status_Reference_Type>WID</Customer_Status_Reference_Type>
                <Customer_Status_Reference_Value>abc123</Customer_Status_Reference_Value>
              </Account>
            </root>
            """;

    /** The same values under a prefix bound to a foreign namespace, which DataWeave's un-prefixed selectors match. */
    private static final String PREFIXED_DISTINCT_VALUES_XML = """
            <c:root xmlns:c="urn:example:customer">
              <c:Account>
                <c:CustomerName>Acme</c:CustomerName>
                <c:BusinessEntityName>Acme Corp</c:BusinessEntityName>
                <c:Customer_Category_Reference_Type>Customer_Category_ID</c:Customer_Category_Reference_Type>
                <c:Customer_Category_Reference_Value>CUSTOMER_CATEGORY-9</c:Customer_Category_Reference_Value>
                <c:Customer_Status_Reference_Type>WID</c:Customer_Status_Reference_Type>
                <c:Customer_Status_Reference_Value>abc123</c:Customer_Status_Reference_Value>
              </c:Account>
            </c:root>
            """;

    private static JAXBContext context;

    private final PutCustomerRequestMapper mapper = new PutCustomerRequestMapper();

    private final JAXPXPathEngine engine = new JAXPXPathEngine();

    PutCustomerRequestMapperTest() {
        engine.setNamespaceContext(Map.of("wd", WD));
    }

    @BeforeAll
    static void createContext() throws JAXBException {
        context = JAXBContext.newInstance(ObjectFactory.class);
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 wraps customer.xml in a wd:Put_Customer_Request element")
    void wrapsCustomerXmlInPutCustomerRequest() throws Exception {
        JAXBElement<PutCustomerRequestType> element = mapper.map(customerXml());

        assertThat(PutCustomerRequestMapper.WORKDAY_NAMESPACE).isEqualTo("urn:com.workday/bsvc");
        assertThat(element.getName()).isEqualTo(new QName(WD, "Put_Customer_Request"));
        assertThat(element.getValue().getCustomerReference()).isNull();
        assertThat(element.getValue().isAddOnly()).isNull();
        assertThat(element.getValue().getVersion()).isNull();

        Element root = marshal(element).getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("Put_Customer_Request");
        assertThat(root.getNamespaceURI()).isEqualTo(WD);
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 maps every customer.xml field to its Customer_Data element")
    void mapsCustomerXmlValues() throws Exception {
        Document request = marshal(mapper.map(customerXml()));

        assertThat(xpath(request, "string(" + CUSTOMER_ID + ")")).isEqualTo("NAME");
        assertThat(xpath(request, "string(" + CUSTOMER_REFERENCE_ID + ")")).isEqualTo("NAME");
        assertThat(xpath(request, "string(" + CUSTOMER_NAME + ")")).isEqualTo("NAME");
        assertThat(xpath(request, "string(" + BUSINESS_ENTITY_NAME + ")")).isEqualTo("NAME");
        assertThat(xpath(request, "string(" + CATEGORY_ID + ")")).isEqualTo("CUSTOMER_CATEGORY-5");
        assertThat(xpath(request, "string(" + CATEGORY_ID + "/@wd:type)")).isEqualTo("Customer_Category_ID");
        assertThat(xpath(request, "string(" + STATUS_ID + ")")).isEqualTo("ACTIVE");
        assertThat(xpath(request, "string(" + STATUS_ID + "/@wd:type)"))
                .isEqualTo("Business_Entity_Status_Value_ID");
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 writes one status, status ID, category ID and business entity field")
    void writesOneOfEachReference() throws Exception {
        Document request = marshal(mapper.map(customerXml()));

        assertThat(xpath(request, "count(" + STATUS_DATA + ")")).isEqualTo("1");
        assertThat(xpath(request, "count(" + STATUS_ID + ")")).isEqualTo("1");
        assertThat(xpath(request, "count(" + CATEGORY_ID + ")")).isEqualTo("1");
        assertThat(xpath(request, "count(" + BUSINESS_ENTITY_DATA + "/*)")).isEqualTo("1");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {CATEGORY_ID, STATUS_ID})
    @DisplayName("add_a_new_customer.xml:12 writes each ID type as the wd:-qualified attribute ns0#type")
    void qualifiesTypeAttribute(String idPath) throws Exception {
        Document request = marshal(mapper.map(customerXml()));

        assertThat(xpath(request, "namespace-uri(" + idPath + "/@*[local-name()='type'])")).isEqualTo(WD);
        assertThat(xpath(request, "count(" + idPath + "/@type)")).isEqualTo("0");
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 never writes Customer_Reference, wd:Add_Only or wd:version")
    void leavesUnmappedPartsAbsent() throws Exception {
        Document request = marshal(mapper.map(customerXml()));

        assertThat(xpath(request, "count(" + REQUEST + "/wd:Customer_Reference)")).isEqualTo("0");
        assertThat(xpath(request, "count(" + REQUEST + "/@*)")).isEqualTo("0");
        assertThat(xpath(request, "count(" + REQUEST + "/@wd:Add_Only | " + REQUEST + "/@wd:version)"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 keys are marshalled in the v25.0 Customer_WWS_DataType sequence")
    void marshalsCustomerDataInSchemaOrder() throws Exception {
        Document request = marshal(mapper.map(customerXml()));

        // DW line 12 writes Business_Entity_Data before Customer_Category_Reference, but JAXB marshals in the XJC
        // propOrder of the v25.0 Customer_WWS_DataType sequence (verified in catalog/Workday__md__io__.xml). JAXB
        // cannot emit the DW order and Workday validates schema order, so the six DW keys are asserted present in
        // schema order.
        assertThat(childElementNames(customerData(request))).containsExactly(
                "Customer_ID",
                "Customer_Reference_ID",
                "Customer_Name",
                "Customer_Category_Reference",
                "Business_Entity_Data",
                "Customer_Status_Data");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("distinctValueInputs")
    @DisplayName("add_a_new_customer.xml:12 takes each output from its own input field, matched by local name")
    void takesEachValueFromItsOwnField(String description, String xml, boolean namespaceAware) throws Exception {
        Document request = marshal(mapper.map(parse(xml, namespaceAware)));

        assertThat(xpath(request, "string(" + CUSTOMER_ID + ")")).isEqualTo("Acme");
        assertThat(xpath(request, "string(" + CUSTOMER_REFERENCE_ID + ")")).isEqualTo("Acme");
        assertThat(xpath(request, "string(" + CUSTOMER_NAME + ")")).isEqualTo("Acme");
        assertThat(xpath(request, "string(" + BUSINESS_ENTITY_NAME + ")")).isEqualTo("Acme Corp");
        assertThat(xpath(request, "string(" + CATEGORY_ID + ")")).isEqualTo("CUSTOMER_CATEGORY-9");
        assertThat(xpath(request, "string(" + CATEGORY_ID + "/@wd:type)")).isEqualTo("Customer_Category_ID");
        assertThat(xpath(request, "string(" + STATUS_ID + ")")).isEqualTo("abc123");
        assertThat(xpath(request, "string(" + STATUS_ID + "/@wd:type)")).isEqualTo("WID");
    }

    static Stream<Arguments> distinctValueInputs() {
        return Stream.of(
                Arguments.of("un-namespaced input, namespace-aware DOM", DISTINCT_VALUES_XML, true),
                Arguments.of("prefixed input, namespace-aware DOM", PREFIXED_DISTINCT_VALUES_XML, true),
                Arguments.of("prefixed input, DOM without namespace awareness", PREFIXED_DISTINCT_VALUES_XML, false));
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 writes a present but empty type element as an empty wd:type")
    void writesEmptyTypeForEmptyTypeElement() throws Exception {
        Document request = marshal(mapper.map(parse("""
                <root>
                  <Account>
                    <CustomerName>Acme</CustomerName>
                    <Customer_Category_Reference_Type/>
                    <Customer_Category_Reference_Value>CUSTOMER_CATEGORY-9</Customer_Category_Reference_Value>
                    <Customer_Status_Reference_Type></Customer_Status_Reference_Type>
                    <Customer_Status_Reference_Value>abc123</Customer_Status_Reference_Value>
                  </Account>
                </root>
                """)));

        for (String idPath : List.of(CATEGORY_ID, STATUS_ID)) {
            assertThat(xpath(request, "count(" + idPath + "/@wd:type)")).as(idPath).isEqualTo("1");
            assertThat(xpath(request, "string(" + idPath + "/@wd:type)")).as(idPath).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"<root><Account/></root>", "<root/>"})
    @DisplayName("add_a_new_customer.xml:12 maps absent fields to empty elements without failing")
    void mapsAbsentInputToEmptyElements(String xml) throws Exception {
        assertMapsToEmptyRequest(parse(xml));
    }

    @Test
    @DisplayName("add_a_new_customer.xml:12 maps a null document like an empty one")
    void mapsNullDocumentToEmptyElements() {
        assertMapsToEmptyRequest(null);
    }

    /**
     * Every mandatory element of the script is still written, empty, and both {@code ID}s carry no attribute at all,
     * which proves an absent type element leaves {@code wd:type} unset rather than writing it as {@code ""}.
     */
    private void assertMapsToEmptyRequest(Document input) {
        JAXBElement<PutCustomerRequestType> element = assertDoesNotThrow(() -> mapper.map(input));
        Document request = assertDoesNotThrow(() -> marshal(element));

        for (String path : List.of(CUSTOMER_ID, CUSTOMER_REFERENCE_ID, CUSTOMER_NAME, BUSINESS_ENTITY_NAME)) {
            assertThat(xpath(request, "count(" + path + ")")).as(path).isEqualTo("1");
            assertThat(xpath(request, "string(" + path + ")")).as(path).isEmpty();
        }
        for (String idPath : List.of(CATEGORY_ID, STATUS_ID)) {
            assertThat(xpath(request, "count(" + idPath + ")")).as(idPath).isEqualTo("1");
            assertThat(xpath(request, "string(" + idPath + ")")).as(idPath).isEmpty();
            assertThat(xpath(request, "count(" + idPath + "/@*)")).as(idPath).isEqualTo("0");
        }
        assertThat(xpath(request, "count(" + STATUS_DATA + ")")).isEqualTo("1");
        assertThat(xpath(request, "count(" + REQUEST + "/wd:Customer_Reference)")).isEqualTo("0");
    }

    private Document customerXml() throws ParserConfigurationException, SAXException, IOException {
        try (InputStream in = getClass().getResourceAsStream("/customer.xml")) {
            assertThat(in).as("classpath resource /customer.xml").isNotNull();
            return parse(in);
        }
    }

    private static Document parse(InputStream in) throws ParserConfigurationException, SAXException, IOException {
        return parse(in, true);
    }

    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return parse(xml, true);
    }

    private static Document parse(String xml, boolean namespaceAware)
            throws ParserConfigurationException, SAXException, IOException {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), namespaceAware);
    }

    private static Document parse(InputStream in, boolean namespaceAware)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        return factory.newDocumentBuilder().parse(in);
    }

    private static Document marshal(JAXBElement<PutCustomerRequestType> element) throws JAXBException {
        Marshaller marshaller = context.createMarshaller();
        DOMResult result = new DOMResult();
        marshaller.marshal(element, result);
        return (Document) result.getNode();
    }

    private String xpath(Document document, String expression) {
        return engine.evaluate(expression, new DOMSource(document));
    }

    private static Element customerData(Document request) {
        NodeList matches = request.getElementsByTagNameNS(WD, "Customer_Data");
        assertThat(matches.getLength()).as("wd:Customer_Data elements").isEqualTo(1);
        return (Element) matches.item(0);
    }

    private static List<String> childElementNames(Element parent) {
        List<String> names = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                names.add(child.getLocalName());
            }
        }
        return names;
    }
}
