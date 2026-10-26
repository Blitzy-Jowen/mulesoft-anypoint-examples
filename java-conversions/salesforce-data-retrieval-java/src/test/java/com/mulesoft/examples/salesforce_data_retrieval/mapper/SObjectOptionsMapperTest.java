/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;

/**
 * Unit test of {@link SObjectOptionsMapper}, the reimplementation of the {@code showFormFlow} script
 * {@code div: { (payload.sobjects map { option @(value: $.name): $.label }) }} over the
 * {@code sfdc:describe-global} result.
 *
 * <p>Expected documents use {@link DwXmlWriter}'s layout and escaping as its own test fixes them: the
 * single-quoted declaration, tab indentation, LF line ends, nothing after the root end tag, {@code <div/>} for a
 * root without children, and {@code &amp;}, {@code &lt;}, {@code &gt;} in text plus {@code &quot;} in attribute
 * values. The full option list comes only from the page captured from the original runtime,
 * {@code src/test/resources/index.html}, never from values written here.
 */
class SObjectOptionsMapperTest {

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    /** What the writer produces for a {@code div} without options. */
    private static final String EMPTY_DIV_DOCUMENT = DECLARATION_LINE + "<div/>";

    /** {@code OPTION_REGEX} of the original {@code SalesforceIdRetrievalIT.testDisplayData}. */
    private static final Pattern ORIGINAL_OPTION_REGEX = Pattern.compile("<option\\svalue=\\\".*\\\">.*<\\/option>");

    /** One option line of the captured page: attribute value and text, both entity-escaped. */
    private static final Pattern CAPTURED_OPTION = Pattern.compile("<option value=\"([^\"]*)\">([^<]*)</option>");

    private static final int CAPTURED_OPTION_COUNT = 292;

    private final SObjectOptionsMapper mapper = new SObjectOptionsMapper(new DwXmlWriter());

    @Test
    void sampleDescribeGlobalResultGivesOneOptionPerSObjectInDataWeaveLayout() {
        // Name and label pairs of the Studio sample DescribeGlobalResult.dwl, which is not carried.
        String xml = mapper.toOptionsXml(describe(sobject("LabelName1", "Label1"), sobject("LabelName2", "Label2")));

        assertThat(xml).isEqualTo("<?xml version='1.0' encoding='UTF-8'?>\n<div>\n"
                + "\t<option value=\"LabelName1\">Label1</option>\n"
                + "\t<option value=\"LabelName2\">Label2</option>\n"
                + "</div>");
        assertThat(ORIGINAL_OPTION_REGEX.matcher(xml).find())
                .as("the original IT's option pattern finds at least one option")
                .isTrue();
    }

    @Test
    void optionsKeepTheOrderSalesforceReturnedThem() {
        String xml = mapper.toOptionsXml(describe(sobject("B", "b"), sobject("A", "a")));

        assertThat(xml).isEqualTo(DECLARATION_LINE + "<div>\n"
                + "\t<option value=\"B\">b</option>\n"
                + "\t<option value=\"A\">a</option>\n"
                + "</div>");
    }

    @Test
    void markupInNamesAndLabelsIsEscapedAsTheWriterEscapesAttributesAndText() {
        String xml = mapper.toOptionsXml(describe(sobject("A&B<\"x\">", "L&<\"y\">")));

        assertThat(xml).isEqualTo(DECLARATION_LINE + "<div>\n"
                + "\t<option value=\"A&amp;B&lt;&quot;x&quot;&gt;\">L&amp;&lt;\"y\"&gt;</option>\n"
                + "</div>");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultsWithoutSObjects")
    void resultWithoutSObjectsGivesTheWritersEmptyDiv(String description, DescribeGlobalResult result) {
        assertThat(mapper.toOptionsXml(result)).isEqualTo(EMPTY_DIV_DOCUMENT);
    }

    static Stream<Arguments> resultsWithoutSObjects() {
        return Stream.of(
                Arguments.of("empty sobjects array", describe()),
                Arguments.of("sobjects set to null", describe((DescribeGlobalSObjectResult[]) null)),
                Arguments.of("null result", null));
    }

    @Test
    void nullSObjectEntriesAreSkipped() {
        String xml = mapper.toOptionsXml(describe(null, sobject("Account", "Account"), null));

        assertThat(xml).isEqualTo(DECLARATION_LINE + "<div>\n"
                + "\t<option value=\"Account\">Account</option>\n"
                + "</div>");
    }

    @Test
    void optionsOfTheCapturedOriginalPageAreReproducedInOrder() throws IOException {
        String page;
        try (InputStream in = SObjectOptionsMapperTest.class.getResourceAsStream("/index.html")) {
            assertThat(in).as("captured page /index.html on the test classpath").isNotNull();
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = page.indexOf("<div>");
        int end = page.indexOf("</div>");
        assertThat(start).as("start of the captured div").isNotNegative();
        assertThat(end).as("end of the captured div").isGreaterThan(start);
        String expectedDiv = page.substring(start, end + "</div>".length());

        List<DescribeGlobalSObjectResult> sobjects = new ArrayList<>();
        Matcher option = CAPTURED_OPTION.matcher(expectedDiv);
        while (option.find()) {
            sobjects.add(sobject(unescape(option.group(1)), unescape(option.group(2))));
        }
        assertThat(sobjects).hasSize(CAPTURED_OPTION_COUNT);
        assertThat(sobjects.get(0).getName()).isEqualTo("AcceptedEventRelation");
        assertThat(sobjects.get(0).getLabel()).isEqualTo("Accepted Event Relation");
        assertThat(sobjects.get(CAPTURED_OPTION_COUNT - 1).getName()).isEqualTo("WebLink");
        assertThat(sobjects.get(CAPTURED_OPTION_COUNT - 1).getLabel()).isEqualTo("Custom Button or Link");
        assertThat(sobjects)
                .filteredOn(sobject -> "DandBCompany".equals(sobject.getName()))
                .singleElement()
                .extracting(DescribeGlobalSObjectResult::getLabel)
                .isEqualTo("D&B Company");

        String output = mapper.toOptionsXml(describe(sobjects.toArray(new DescribeGlobalSObjectResult[0])));
        assertThat(output).startsWith(DECLARATION_LINE);
        String actual = output.substring(DECLARATION_LINE.length());

        // Deliberately not a byte comparison: the captured page indents its options with two spaces, while
        // DwXmlWriter writes DataWeave's tab layout, so the byte form is a Tier 2A verification point (AAP 0.8.4).
        // Default positional matching still compares element names, attribute values, text, order and count.
        Diff diff = DiffBuilder.compare(Input.fromString(expectedDiv))
                .withTest(Input.fromString(actual))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as(diff::toString).isFalse();
    }

    private static DescribeGlobalSObjectResult sobject(String name, String label) {
        DescribeGlobalSObjectResult sobject = new DescribeGlobalSObjectResult();
        sobject.setName(name);
        sobject.setLabel(label);
        return sobject;
    }

    private static DescribeGlobalResult describe(DescribeGlobalSObjectResult... sobjects) {
        DescribeGlobalResult result = new DescribeGlobalResult();
        result.setSobjects(sobjects);
        return result;
    }

    /** Reverses the entity escaping of the captured page; {@code &amp;} goes last so no entity is decoded twice. */
    private static String unescape(String escaped) {
        return escaped.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
