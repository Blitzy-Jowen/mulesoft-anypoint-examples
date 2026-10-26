/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Unit test of {@link EmployeesXmlMapper}, the owner of the "JSON to XML" script of {@code upload-to-ftp.xml}: the
 * original's {@code message.json} and the bundled {@code employees.json} produce {@link #EXPECTED} byte for byte,
 * that document equals the original's {@code reply.xml} with all whitespace removed (the original test's comparison),
 * and every input decision the mapper documents behaves as stated.
 *
 * <p>The collaborators are real: {@link DwXmlWriter} writes the bytes and a plain {@link ObjectMapper} stands in for
 * the application's. No Spring context is started.
 */
class EmployeesXmlMapperTest {

    private static final String DECLARATION_LINE = "<?xml version='1.0' encoding='UTF-8'?>\n";

    /**
     * The script's output for {@code message.json}: the declaration, a line feed, the document in tab layout with LF
     * line ends, and nothing after {@code </employees>}. It is {@code reply.xml} with the street values' inner spaces
     * restored from the input and without that hand-edited file's final line feed.
     */
    private static final String EXPECTED = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<employees>\n"
            + "\t<employee>\n"
            + "\t\t<name>John</name>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t\t<addresses>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>123 Main Street</street>\n"
            + "\t\t\t\t<zipCode>111</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>987 Cypress Avenue</street>\n"
            + "\t\t\t\t<zipCode>222</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t</addresses>\n"
            + "\t</employee>\n"
            + "\t<employee>\n"
            + "\t\t<name>Jane</name>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t\t<addresses>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>345 Main Street</street>\n"
            + "\t\t\t\t<zipCode>111</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>654 Sunset Boulevard</street>\n"
            + "\t\t\t\t<zipCode>333</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t</addresses>\n"
            + "\t</employee>\n"
            + "</employees>";

    private static final int EXPECTED_LENGTH = 637;

    private static final String EXPECTED_MD5 = "86159f75689d7a5b40bb14284901d8d6";

    /** The document of an input whose {@code employees} yields no employee. */
    private static final String EMPTY_ROOT = DECLARATION_LINE + "<employees/>";

    /** The John Doe employee of {@link #EXPECTED}, the first child of its root. */
    private static final String JOHN_DOE_EMPLOYEE = "\t<employee>\n"
            + "\t\t<name>John</name>\n"
            + "\t\t<lastName>Doe</lastName>\n"
            + "\t\t<addresses>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>123 Main Street</street>\n"
            + "\t\t\t\t<zipCode>111</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t\t<address>\n"
            + "\t\t\t\t<street>987 Cypress Avenue</street>\n"
            + "\t\t\t\t<zipCode>222</zipCode>\n"
            + "\t\t\t</address>\n"
            + "\t\t</addresses>\n"
            + "\t</employee>\n";

    /** The addresses object of the John Doe employee in {@code message.json}. */
    private static final String JOHN_DOE_ADDRESSES = "{\"address\": ["
            + "{\"street\": \"123 Main Street\",\"zipCode\": \"111\"},"
            + "{\"street\": \"987 Cypress Avenue\",\"zipCode\": \"222\"}]}";

    private final ObjectMapper json = new ObjectMapper();

    private EmployeesXmlMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new EmployeesXmlMapper(new DwXmlWriter(), new ObjectMapper());
    }

    @Test
    @DisplayName("EXPECTED is the verified document: 637 UTF-8 bytes with its recorded MD5")
    void expectedLiteralIsTheVerifiedDocument() throws NoSuchAlgorithmException {
        byte[] expected = EXPECTED.getBytes(StandardCharsets.UTF_8);

        assertThat(expected).hasSize(EXPECTED_LENGTH);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(expected)))
                .isEqualTo(EXPECTED_MD5);
    }

    @Test
    @DisplayName("message.json -> EXPECTED exactly: declaration and LF first, nothing after </employees>")
    void messageJsonProducesExactDocument() throws IOException {
        String output = mapper.toXml(resource("/message.json"));

        assertThat(output).isEqualTo(EXPECTED);
        assertThat(output).startsWith(DECLARATION_LINE);
        assertThat(output).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("message.json output equals reply.xml with all whitespace removed, the original test's comparison")
    void messageJsonMatchesReplyXmlWithWhitespaceRemoved() throws IOException {
        assertThat(resource("/reply.xml")).hasSize(634);
        String reply = text("/reply.xml");

        String output = mapper.toXml(resource("/message.json"));

        assertThat(output.replaceAll("\\s", "")).isEqualTo(reply.replaceAll("\\s", ""));
        assertThat(output).isNotEqualTo(reply);
    }

    @Test
    @DisplayName("the bundled pretty-printed employees.json -> the same exact document as message.json")
    void employeesJsonProducesSameExactDocument() throws IOException {
        byte[] employees = resource("/employees.json");
        assertThat(employees).hasSize(597);

        String output = mapper.toXml(employees);

        assertThat(output).isEqualTo(EXPECTED);
        assertThat(output).isEqualTo(mapper.toXml(resource("/message.json")));
    }

    @Test
    @DisplayName("toXmlBytes is the UTF-8 encoding of toXml: 637 bytes")
    void toXmlBytesEqualsUtf8OfToXml() throws IOException {
        byte[] message = resource("/message.json");

        byte[] bytes = mapper.toXmlBytes(message);

        assertThat(bytes).hasSize(EXPECTED_LENGTH);
        assertThat(bytes).containsExactly(mapper.toXml(message).getBytes(StandardCharsets.UTF_8));
        assertThat(bytes).containsExactly(EXPECTED.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("address: $ copies the address object as it is, every key in input order")
    void addressObjectIsCopiedAsIsInKeyOrder() {
        String output = mapper.toXml(utf8("{\"employees\": {\"employee\": [{\"name\": \"Homer\","
                + "\"lastName\": \"Simpson\",\"addresses\": {\"address\": "
                + "[{\"zipCode\": \"9\",\"street\": \"1 A St\",\"city\": \"Springfield\"}]}}]}}"));

        assertThat(output).contains("\t\t\t\t<zipCode>9</zipCode>\n\t\t\t\t<street>1 A St</street>\n"
                + "\t\t\t\t<city>Springfield</city>\n");
        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>Homer</name>\n"
                + "\t\t<lastName>Simpson</lastName>\n"
                + "\t\t<addresses>\n"
                + "\t\t\t<address>\n"
                + "\t\t\t\t<zipCode>9</zipCode>\n"
                + "\t\t\t\t<street>1 A St</street>\n"
                + "\t\t\t\t<city>Springfield</city>\n"
                + "\t\t\t</address>\n"
                + "\t\t</addresses>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    static Stream<Arguments> nullOrMissingNames() {
        return Stream.of(
                Arguments.of("name", true, "\t\t<name>John</name>\n", "\t\t<name/>\n"),
                Arguments.of("name", false, "\t\t<name>John</name>\n", "\t\t<name/>\n"),
                Arguments.of("lastName", true, "\t\t<lastName>Doe</lastName>\n", "\t\t<lastName/>\n"),
                Arguments.of("lastName", false, "\t\t<lastName>Doe</lastName>\n", "\t\t<lastName/>\n"));
    }

    @ParameterizedTest(name = "John Doe''s {0}, null (true) or missing (false): {1}")
    @MethodSource("nullOrMissingNames")
    @DisplayName("a null or missing name or lastName is an empty element in its place; the rest is unchanged")
    void nullNameIsWrittenAsEmptyElement(String field, boolean nullValue, String written, String empty)
            throws IOException {
        ObjectNode payload = (ObjectNode) json.readTree(resource("/message.json"));
        ObjectNode john = (ObjectNode) payload.get("employees").get("employee").get(0);
        if (nullValue) {
            john.putNull(field);
        } else {
            john.remove(field);
        }

        String output = mapper.toXml(json.writeValueAsBytes(payload));

        assertThat(output).contains(empty);
        int at = EXPECTED.indexOf(written);
        assertThat(output).isEqualTo(EXPECTED.substring(0, at) + empty + EXPECTED.substring(at + written.length()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"employees\":{}}",
        "{\"employees\":{\"employee\":[]}}",
        "{\"employees\":{\"employee\":null}}",
        "{\"employees\":null}",
        "{\"employees\":\"text\"}",
        "{\"employees\":[]}",
        "{}"
    })
    @DisplayName("employees missing, null or not an object, or employee missing, null or empty -> <employees/>")
    void missingEmployeeYieldsEmptyRoot(String input) {
        assertThat(mapper.toXml(utf8(input))).isEqualTo(EMPTY_ROOT);
        assertThat(mapper.toXmlBytes(utf8(input))).containsExactly(EMPTY_ROOT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("employee as a single object is a one-element array, as the mapper documents")
    void singleObjectEmployeeFollowsDocumentedRule() {
        String output = mapper.toXml(utf8("{\"employees\": {\"employee\": {\"name\": \"John\",\"lastName\": \"Doe\","
                + "\"addresses\": " + JOHN_DOE_ADDRESSES + "}}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE + "<employees>\n" + JOHN_DOE_EMPLOYEE + "</employees>");
    }

    @Test
    @DisplayName("address as a single object is a one-element array, as the mapper documents")
    void singleObjectAddressIsOneElementArray() {
        String output = mapper.toXml(utf8("{\"employees\": {\"employee\": [{\"name\": \"John\",\"lastName\": \"Doe\","
                + "\"addresses\": {\"address\": {\"street\": \"123 Main Street\",\"zipCode\": \"111\"}}}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>John</name>\n"
                + "\t\t<lastName>Doe</lastName>\n"
                + "\t\t<addresses>\n"
                + "\t\t\t<address>\n"
                + "\t\t\t\t<street>123 Main Street</street>\n"
                + "\t\t\t\t<zipCode>111</zipCode>\n"
                + "\t\t\t</address>\n"
                + "\t\t</addresses>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    @ParameterizedTest(name = "employee fields after lastName: [{0}]")
    @ValueSource(strings = {
        "",
        ",\"addresses\":null",
        ",\"addresses\":\"text\"",
        ",\"addresses\":[]",
        ",\"addresses\":{}",
        ",\"addresses\":{\"address\":null}",
        ",\"addresses\":{\"address\":[]}"
    })
    @DisplayName("addresses missing, null or not an object, or address missing, null or empty -> <addresses/>")
    void missingAddressYieldsEmptyAddresses(String addresses) {
        String output = mapper.toXml(utf8(
                "{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\"" + addresses + "}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>John</name>\n"
                + "\t\t<lastName>Doe</lastName>\n"
                + "\t\t<addresses/>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    @ParameterizedTest(name = "employee item {0}")
    @ValueSource(strings = {"\"text\"", "7", "true", "null", "[]"})
    @DisplayName("an employee item that is not an object -> empty name and lastName and <addresses/>")
    void nonObjectEmployeeItemYieldsEmptyFields(String item) {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[" + item + "]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name/>\n"
                + "\t\t<lastName/>\n"
                + "\t\t<addresses/>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    @Test
    @DisplayName("an array-valued name repeats its element once per item, as the DataWeave writer repeats a key")
    void arrayValuedNameRepeatsItsElement() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":[\"John\",\"Johnny\"],"
                + "\"lastName\":\"Doe\",\"addresses\":" + JOHN_DOE_ADDRESSES + "}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + JOHN_DOE_EMPLOYEE.replace("\t\t<name>John</name>\n",
                        "\t\t<name>John</name>\n\t\t<name>Johnny</name>\n")
                + "</employees>");
    }

    @Test
    @DisplayName("a duplicate JSON key keeps its last value")
    void duplicateKeyKeepsLastValue() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":\"Jack\",\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":" + JOHN_DOE_ADDRESSES + "}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE + "<employees>\n" + JOHN_DOE_EMPLOYEE + "</employees>");
    }

    @Test
    @DisplayName("decimals keep their scale; integers and booleans are written as read")
    void numbersAndBooleansAreWrittenAsRead() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"zipCode\":1.50,\"floor\":7,\"primary\":true}]}}]}}"));

        assertThat(output).contains("\t\t\t\t<zipCode>1.50</zipCode>\n"
                + "\t\t\t\t<floor>7</floor>\n"
                + "\t\t\t\t<primary>true</primary>\n");
    }

    @Test
    @DisplayName("non-ASCII text is written as is and encoded in UTF-8, never as character references")
    void multiByteCharactersSurviveUtf8() {
        byte[] input = utf8("{\"employees\":{\"employee\":[{\"name\":\"Zo\u00eb\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"street\":\"\u65e5\u672c\",\"zipCode\":\"111\"}]}}]}}");

        String output = mapper.toXml(input);
        byte[] bytes = mapper.toXmlBytes(input);

        assertThat(output).contains("\t\t<name>Zo\u00eb</name>\n");
        assertThat(output).contains("\t\t\t\t<street>\u65e5\u672c</street>\n");
        assertThat(output).doesNotContain("&#");
        assertThat(bytes).containsExactly(output.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(output);
        assertThat(indexOf(bytes, new byte[] {'Z', 'o', (byte) 0xC3, (byte) 0xAB, '<'})).isNotNegative();
        assertThat(indexOf(bytes, new byte[] {'>', (byte) 0xE6, (byte) 0x97, (byte) 0xA5, (byte) 0xE6, (byte) 0x9C,
            (byte) 0xAC, '<'})).isNotNegative();
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("empty bytes", new byte[0]),
                Arguments.of("null", null),
                Arguments.of("blank", utf8("   ")),
                Arguments.of("malformed", utf8("{\"employees\": [")),
                Arguments.of("trailing content", utf8("{} {}")),
                Arguments.of("top-level array", utf8("[]")),
                Arguments.of("top-level string", utf8("\"text\"")),
                Arguments.of("top-level number", utf8("1")),
                Arguments.of("top-level boolean", utf8("true")),
                Arguments.of("top-level null", utf8("null")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    @DisplayName("empty, malformed, trailing or non-object input raises IllegalArgumentException from both methods")
    void invalidInputIsRejected(String label, byte[] input) {
        assertThatThrownBy(() -> mapper.toXml(input)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.toXmlBytes(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the injected ObjectMapper keeps its settings")
    void injectedObjectMapperIsNotModified() {
        ObjectMapper applicationMapper = new ObjectMapper();

        new EmployeesXmlMapper(new DwXmlWriter(), applicationMapper);

        assertThat(applicationMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
        assertThat(applicationMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = EmployeesXmlMapperTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String text(String path) throws IOException {
        return new String(resource(path), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }
}
