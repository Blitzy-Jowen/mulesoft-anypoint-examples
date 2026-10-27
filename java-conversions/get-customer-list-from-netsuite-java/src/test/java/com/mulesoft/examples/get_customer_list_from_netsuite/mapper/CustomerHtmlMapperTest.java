/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.DifferenceEvaluators;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Output of {@link CustomerHtmlMapper}, the owner of the {@code %dw 1.0} script "Customer List to XML" of
 * {@code get-customer-list-from-netsuite.xml}, rendered through the SC-4 {@link DwXmlWriter}.
 *
 * <p>The populated and the empty input are the {@code items} rows of the SuiteQL responses
 * {@code src/test/resources/netsuite/suiteql-customers.json} and {@code suiteql-empty.json}, as Jackson
 * deserializes them, per-row {@code links} included; the four customers are those of the original
 * {@code src/test/resources/example.xml}, in its order. Every other row is keyed by the literal wire keys declared
 * here, never by the mapper's column constants, so a selector that misses NetSuite's column labels fails these
 * tests. Expected documents are written out by hand with explicit {@code \n} and {@code \t}; none is computed
 * through the mapper or the writer. Null cells and escaped text follow the SC-4 contract asserted by
 * {@link DwXmlWriterTest}: an element without text is {@code <td/>}, and {@code &}, {@code <}, {@code >} are
 * written as entity references.
 */
class CustomerHtmlMapperTest {

    /** NetSuite's SuiteQL column label of the last name, as {@code suiteql-customers.json} carries it. */
    private static final String WIRE_LAST_NAME = "lastname";

    /** NetSuite's SuiteQL column label of the first name, as {@code suiteql-customers.json} carries it. */
    private static final String WIRE_FIRST_NAME = "firstname";

    /** NetSuite's SuiteQL column label of the email address, as {@code suiteql-customers.json} carries it. */
    private static final String WIRE_EMAIL = "email";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    private static final String ABBOTT_LINE = "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Abbott</td>\n"
            + "\t\t\t<td>Byron</td>\n"
            + "\t\t\t<td>ab@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n";

    private static final String NORMAN_LINE = "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Norman</td>\n"
            + "\t\t\t<td>Gustaffson</td>\n"
            + "\t\t\t<td>gustaff@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n";

    private static final String ANALYTICS_LINE = "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Analytics</td>\n"
            + "\t\t\t<td>Angels</td>\n"
            + "\t\t\t<td>aa@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n";

    private static final String MCGILL_LINE = "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>McGill</td>\n"
            + "\t\t\t<td>Stuart</td>\n"
            + "\t\t\t<td>mcgill@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n";

    /** The four customers of {@code example.xml}, in its order: 454 characters ending at {@code </div>}. */
    private static final String FOUR_CUSTOMERS_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + ABBOTT_LINE
            + NORMAN_LINE
            + ANALYTICS_LINE
            + MCGILL_LINE
            + "</div>";

    /** The same four customers in reverse input order. */
    private static final String REVERSED_CUSTOMERS_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + MCGILL_LINE
            + ANALYTICS_LINE
            + NORMAN_LINE
            + ABBOTT_LINE
            + "</div>";

    /** The {@code otherwise} branch, still under the root {@code div}: 114 characters. */
    private static final String NO_CUSTOMERS_DOCUMENT = "<?xml version='1.0' encoding='UTF-8'?>\n<div>\n\t<line>\n"
            + "\t\t<tr>\n\t\t\t<td>No customers found</td>\n\t\t</tr>\n\t</line>\n</div>";

    private static final String SINGLE_ABBOTT_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + ABBOTT_LINE
            + "</div>";

    private static final String NULL_FIRST_NAME_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Abbott</td>\n"
            + "\t\t\t<td/>\n"
            + "\t\t\t<td>ab@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "</div>";

    private static final String NULL_EMAIL_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Norman</td>\n"
            + "\t\t\t<td>Gustaffson</td>\n"
            + "\t\t\t<td/>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "</div>";

    /** A {@code null} row reads like a row without keys: three cells without text. */
    private static final String NULL_ROW_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td/>\n"
            + "\t\t\t<td/>\n"
            + "\t\t\t<td/>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "</div>";

    private static final String ESCAPED_DOCUMENT = DECLARATION_LINE
            + "<div>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Smith &amp; &lt;Sons&gt;</td>\n"
            + "\t\t\t<td>A&gt;B</td>\n"
            + "\t\t\t<td>x&amp;y@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "</div>";

    private CustomerHtmlMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new CustomerHtmlMapper(new DwXmlWriter());
    }

    @Test
    void fourCustomersRenderAsTheExactDataWeaveDocument() throws Exception {
        String actual = mapper.toXml(exampleCustomers());

        assertThat(actual).isEqualTo(FOUR_CUSTOMERS_DOCUMENT);
        assertThat(actual).hasSize(454).doesNotEndWith("\n");
    }

    @Test
    void fourCustomersMatchTheOriginalExampleXmlStructurally() throws Exception {
        String actual = mapper.toXml(exampleCustomers());

        // example.xml declares windows-1250 and uses a two-space indent, so it is compared by structure only.
        try (InputStream example = CustomerHtmlMapperTest.class.getResourceAsStream("/example.xml")) {
            assertThat(example).as("test-classpath example.xml").isNotNull();
            Diff diff = DiffBuilder.compare(Input.fromString(actual))
                    .withTest(Input.fromStream(example))
                    .ignoreWhitespace()
                    .checkForSimilar()
                    .withDifferenceEvaluator(DifferenceEvaluators.chain(
                            DifferenceEvaluators.Default,
                            DifferenceEvaluators.downgradeDifferencesToEqual(ComparisonType.XML_ENCODING)))
                    .build();
            assertThat(diff.hasDifferences()).as(diff.toString()).isFalse();
        }
    }

    @Test
    void noCustomersWritesTheSingleNoCustomersFoundCellUnderDiv() throws Exception {
        assertThat(NO_CUSTOMERS_DOCUMENT).hasSize(114).contains(CustomerHtmlMapper.NO_CUSTOMERS);

        assertThat(mapper.toXml(suiteQlItems("suiteql-empty.json"))).isEqualTo(NO_CUSTOMERS_DOCUMENT);
        assertThat(mapper.toXml(null)).isEqualTo(NO_CUSTOMERS_DOCUMENT);
        assertThat(mapper.toXml(List.of())).isEqualTo(NO_CUSTOMERS_DOCUMENT);
    }

    @Test
    void nullAndAbsentValuesKeepTheirCellAsAnElementWithoutText() {
        assertThat(mapper.toXml(List.of(row("Abbott", null, "ab@example.com"))))
                .isEqualTo(NULL_FIRST_NAME_DOCUMENT);
        assertThat(mapper.toXml(List.of(row("Norman", "Gustaffson", null))))
                .isEqualTo(NULL_EMAIL_DOCUMENT);

        // $.firstName on an absent key yields null, so a row without the key renders like a null value.
        Map<String, Object> withoutFirstName = new LinkedHashMap<>();
        withoutFirstName.put(WIRE_LAST_NAME, "Abbott");
        withoutFirstName.put(WIRE_EMAIL, "ab@example.com");
        assertThat(mapper.toXml(List.of(withoutFirstName))).isEqualTo(NULL_FIRST_NAME_DOCUMENT);

        List<Map<String, Object>> nullRow = new ArrayList<>();
        nullRow.add(null);
        assertThat(mapper.toXml(nullRow)).isEqualTo(NULL_ROW_DOCUMENT);
    }

    @Test
    void markupCharactersInCellsAreEscapedAndRoundTrip() throws Exception {
        String lastName = "Smith & <Sons>";
        String firstName = "A>B";
        String email = "x&y@example.com";

        String actual = mapper.toXml(List.of(row(lastName, firstName, email)));

        assertThat(actual).isEqualTo(ESCAPED_DOCUMENT);
        assertThat(cellTexts(parse(actual))).containsExactly(lastName, firstName, email);
    }

    @Test
    void cellOrderIsLastNameFirstNameEmailWhateverTheRowKeyOrder() {
        Map<String, Object> emailFirst = new LinkedHashMap<>();
        emailFirst.put(WIRE_EMAIL, "ab@example.com");
        emailFirst.put(WIRE_FIRST_NAME, "Byron");
        emailFirst.put(WIRE_LAST_NAME, "Abbott");

        assertThat(mapper.toXml(List.of(emailFirst))).isEqualTo(SINGLE_ABBOTT_DOCUMENT);
    }

    @Test
    void rowsKeepTheirInputOrderWithoutSorting() throws Exception {
        List<Map<String, Object>> reversed = new ArrayList<>(exampleCustomers());
        Collections.reverse(reversed);

        String actual = mapper.toXml(reversed);

        assertThat(actual).isEqualTo(REVERSED_CUSTOMERS_DOCUMENT);
        assertThat(firstCellOfEachRow(parse(actual))).containsExactly("McGill", "Analytics", "Norman", "Abbott");
    }

    /**
     * The customers of {@code example.xml}, in its order: the {@code items} rows of {@code suiteql-customers.json}
     * (Abbott, Norman, Analytics, McGill), read from the fixture rather than built from the mapper's constants.
     */
    private static List<Map<String, Object>> exampleCustomers() throws IOException {
        return suiteQlItems("suiteql-customers.json");
    }

    /**
     * The {@code items} rows of a SuiteQL response under {@code /netsuite/} on the test classpath, as Jackson
     * deserializes them: insertion-ordered maps keyed by NetSuite's column labels, per-row {@code links} included,
     * which the mapper does not read.
     *
     * @param resource the file name under {@code src/test/resources/netsuite/}
     * @return the rows in fixture order
     * @throws IOException when the resource cannot be read or is not JSON
     */
    private static List<Map<String, Object>> suiteQlItems(String resource) throws IOException {
        try (InputStream response = CustomerHtmlMapperTest.class.getResourceAsStream("/netsuite/" + resource)) {
            assertThat(response).as("test-classpath netsuite/%s", resource).isNotNull();
            JsonNode items = JSON.readTree(response).path("items");
            assertThat(items.isArray()).as("netsuite/%s has an items array", resource).isTrue();
            return JSON.convertValue(items, new TypeReference<List<Map<String, Object>>>() { });
        }
    }

    /**
     * A SuiteQL result row keyed by the literal wire keys {@link #WIRE_LAST_NAME}, {@link #WIRE_FIRST_NAME} and
     * {@link #WIRE_EMAIL}, independent of the mapper's constants; values may be {@code null}.
     */
    private static Map<String, Object> row(String lastname, String firstname, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(WIRE_LAST_NAME, lastname);
        row.put(WIRE_FIRST_NAME, firstname);
        row.put(WIRE_EMAIL, email);
        return row;
    }

    /** Parses with DOCTYPE declarations disallowed, so the output is read as plain markup only. */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static List<String> cellTexts(Document document) {
        NodeList cells = document.getElementsByTagName("td");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < cells.getLength(); i++) {
            texts.add(cells.item(i).getTextContent());
        }
        return texts;
    }

    private static List<String> firstCellOfEachRow(Document document) {
        NodeList rows = document.getElementsByTagName("tr");
        List<String> firstCells = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            Node child = rows.item(i).getFirstChild();
            while (child != null && child.getNodeType() != Node.ELEMENT_NODE) {
                child = child.getNextSibling();
            }
            assertThat(child).as("first td of row %d", i + 1).isNotNull();
            assertThat(((Element) child).getTagName()).isEqualTo("td");
            firstCells.add(child.getTextContent());
        }
        return firstCells;
    }
}
