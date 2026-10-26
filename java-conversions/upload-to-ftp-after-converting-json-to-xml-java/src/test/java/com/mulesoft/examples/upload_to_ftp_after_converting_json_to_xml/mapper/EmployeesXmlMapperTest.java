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

import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
        "{\"employees\":{\"employee\":{}}}",
        "{\"employees\":null}",
        "{\"employees\":\"text\"}",
        "{\"employees\":[]}",
        "{}"
    })
    @DisplayName("employees missing, null or not an object, or employee missing, null, an empty array or an empty "
            + "object -> <employees/>")
    void missingEmployeeYieldsEmptyRoot(String input) {
        assertThat(mapper.toXml(utf8(input))).isEqualTo(EMPTY_ROOT);
        assertThat(mapper.toXmlBytes(utf8(input))).containsExactly(EMPTY_ROOT.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Object-valued {@code employee} cases. The employee objects and the repeated key give the structure the original
     * script produces on the public DataWeave 1.x engine {@code com.mulesoft.weave} 1.2.0-M4. For the string members
     * the engine applies the same coercion, then fails with "Type mismatch for 'map' operator" because
     * {@code $.addresses} is {@code null} for each item and DataWeave 1.x cannot map {@code null}; the expected
     * documents combine that coercion with the mapper's documented decision that a missing {@code addresses} gives
     * {@code <addresses/>}.
     */
    static Stream<Arguments> objectEmployees() {
        String john = "{\"name\": \"John\",\"lastName\": \"Doe\",\"addresses\": " + JOHN_DOE_ADDRESSES + "}";
        String jane = "{\"name\": \"Jane\",\"lastName\": \"Doe\",\"addresses\": {\"address\": ["
                + "{\"street\": \"345 Main Street\",\"zipCode\": \"111\"},"
                + "{\"street\": \"654 Sunset Boulevard\",\"zipCode\": \"333\"}]}}";
        return Stream.of(
                Arguments.of("employee objects under distinct keys", "{\"first\":" + john + ",\"second\":" + jane + "}",
                        EXPECTED),
                Arguments.of("employee objects under a repeated key", "{\"e\":" + john + ",\"e\":" + jane + "}",
                        EXPECTED),
                Arguments.of("string members name and lastName, then an addresses object, which is the third item "
                        + "itself; the engine fails on null map, the mapper gives <addresses/>",
                        "{\"name\": \"John\",\"lastName\": \"Doe\",\"addresses\": " + JOHN_DOE_ADDRESSES + "}",
                        DECLARATION_LINE
                        + "<employees>\n"
                        + "\t<employee>\n"
                        + "\t\t<name>John</name>\n"
                        + "\t\t<lastName/>\n"
                        + "\t\t<addresses/>\n"
                        + "\t</employee>\n"
                        + "\t<employee>\n"
                        + "\t\t<name/>\n"
                        + "\t\t<lastName>Doe</lastName>\n"
                        + "\t\t<addresses/>\n"
                        + "\t</employee>\n"
                        + "\t<employee>\n"
                        + "\t\t<name/>\n"
                        + "\t\t<lastName/>\n"
                        + "\t\t<addresses/>\n"
                        + "\t</employee>\n"
                        + "</employees>"),
                Arguments.of("a repeated string key name, once per occurrence; the engine fails on null map, the "
                        + "mapper gives <addresses/>",
                        "{\"name\":\"John\",\"name\":\"Jack\"}",
                        DECLARATION_LINE
                        + "<employees>\n"
                        + "\t<employee>\n"
                        + "\t\t<name>John</name>\n"
                        + "\t\t<lastName/>\n"
                        + "\t\t<addresses/>\n"
                        + "\t</employee>\n"
                        + "\t<employee>\n"
                        + "\t\t<name>Jack</name>\n"
                        + "\t\t<lastName/>\n"
                        + "\t\t<addresses/>\n"
                        + "\t</employee>\n"
                        + "</employees>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objectEmployees")
    @DisplayName("employee as an object maps each member's value in input order, a repeated key included; a string "
            + "value answers only a selector naming its own key")
    void objectEmployeeMapsEachMemberValue(String label, String employee, String expected) {
        String output = mapper.toXml(utf8("{\"employees\": {\"employee\": " + employee + "}}"));

        assertThat(output).isEqualTo(expected);
    }

    /**
     * Object-valued {@code address} cases. Except the array member, each gives the structure the original script
     * produces on the public DataWeave 1.x engine {@code com.mulesoft.weave} 1.2.0-M4, in {@link DwXmlWriter}'s layout
     * and with {@code null} as that writer renders it. The engine fails to write an array as an element ("Cannot
     * coerce a :array to a :object"), so the array member follows the mapper's documented decision that an
     * array-valued address item repeats its element.
     */
    static Stream<Arguments> objectAddresses() {
        return Stream.of(
                Arguments.of("string members, each written as its value",
                        "{\"street\": \"123 Main Street\",\"zipCode\": \"111\"}",
                        "\t\t\t<address>123 Main Street</address>\n"
                        + "\t\t\t<address>111</address>\n"),
                Arguments.of("object members, each written as that object",
                        "{\"home\":{\"street\":\"a\",\"zipCode\":\"1\"},\"work\":{\"street\":\"b\"}}",
                        "\t\t\t<address>\n"
                        + "\t\t\t\t<street>a</street>\n"
                        + "\t\t\t\t<zipCode>1</zipCode>\n"
                        + "\t\t\t</address>\n"
                        + "\t\t\t<address>\n"
                        + "\t\t\t\t<street>b</street>\n"
                        + "\t\t\t</address>\n"),
                Arguments.of("a repeated string key, once per occurrence", "{\"street\":\"a\",\"street\":\"b\"}",
                        "\t\t\t<address>a</address>\n"
                        + "\t\t\t<address>b</address>\n"),
                Arguments.of("string and object members mixed, in input order",
                        "{\"street\":\"123 Main Street\",\"home\":{\"street\":\"a\"},\"zipCode\":\"111\"}",
                        "\t\t\t<address>123 Main Street</address>\n"
                        + "\t\t\t<address>\n"
                        + "\t\t\t\t<street>a</street>\n"
                        + "\t\t\t</address>\n"
                        + "\t\t\t<address>111</address>\n"),
                Arguments.of("a repeated object key, once per occurrence",
                        "{\"a\":{\"street\":\"123 Main Street\",\"zipCode\":\"111\"},"
                        + "\"a\":{\"street\":\"987 Cypress Avenue\",\"zipCode\":\"222\"}}",
                        "\t\t\t<address>\n"
                        + "\t\t\t\t<street>123 Main Street</street>\n"
                        + "\t\t\t\t<zipCode>111</zipCode>\n"
                        + "\t\t\t</address>\n"
                        + "\t\t\t<address>\n"
                        + "\t\t\t\t<street>987 Cypress Avenue</street>\n"
                        + "\t\t\t\t<zipCode>222</zipCode>\n"
                        + "\t\t\t</address>\n"),
                Arguments.of("null, number and boolean members",
                        "{\"street\":null,\"zipCode\":1.50,\"flag\":true}",
                        "\t\t\t<address/>\n"
                        + "\t\t\t<address>1.50</address>\n"
                        + "\t\t\t<address>true</address>\n"),
                Arguments.of("an array member, which is the item itself; the engine fails to write it, the mapper "
                        + "repeats the element",
                        "{\"lines\":[\"x\",\"y\"]}",
                        "\t\t\t<address>x</address>\n"
                        + "\t\t\t<address>y</address>\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objectAddresses")
    @DisplayName("address as an object maps each member's value in input order, a repeated key included, and "
            + "address: $ writes that value without its key")
    void objectAddressMapsEachMemberValue(String label, String address, String addressLines) {
        String output = mapper.toXml(utf8("{\"employees\": {\"employee\": [{\"name\": \"John\",\"lastName\": \"Doe\","
                + "\"addresses\": {\"address\": " + address + "}}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>John</name>\n"
                + "\t\t<lastName>Doe</lastName>\n"
                + "\t\t<addresses>\n"
                + addressLines
                + "\t\t</addresses>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    @Test
    @DisplayName("address neither an array nor an object is a one-element array, as the mapper documents")
    void scalarAddressIsOneElementArray() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":\"123 Main Street\"}}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>John</name>\n"
                + "\t\t<lastName>Doe</lastName>\n"
                + "\t\t<addresses>\n"
                + "\t\t\t<address>123 Main Street</address>\n"
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
        ",\"addresses\":{\"address\":[]}",
        ",\"addresses\":{\"address\":{}}"
    })
    @DisplayName("addresses missing, null or not an object, or address missing, null, an empty array or an empty "
            + "object -> <addresses/>")
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

    @ParameterizedTest(name = "employee {0}")
    @ValueSource(strings = {"[\"text\"]", "[7]", "[true]", "[null]", "[[]]", "\"text\"", "7", "true"})
    @DisplayName("an employee item that is not an object, or an employee that is neither an array nor an object "
            + "(a one-element array) -> one employee with empty name and lastName and <addresses/>")
    void nonObjectEmployeeItemYieldsEmptyFields(String employee) {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":" + employee + "}}"));

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

    static Stream<Arguments> repeatedSelectedKeys() {
        String otherAddresses = "{\"address\":[{\"street\":\"1 A St\"}]}";
        String johnDoe = "{\"name\":\"John\",\"lastName\":\"Doe\",\"addresses\":" + JOHN_DOE_ADDRESSES + "}";
        String janeRoe = "{\"name\":\"Jane\",\"lastName\":\"Roe\",\"addresses\":" + otherAddresses + "}";
        return Stream.of(
                Arguments.of("name", "{\"employees\":{\"employee\":[{\"name\":\"Jack\",\"name\":\"John\","
                        + "\"lastName\":\"Doe\",\"addresses\":" + JOHN_DOE_ADDRESSES + "}]}}",
                        JOHN_DOE_EMPLOYEE.replace("<name>John</name>", "<name>Jack</name>")),
                Arguments.of("lastName", "{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                        + "\"lastName\":\"Roe\",\"addresses\":" + JOHN_DOE_ADDRESSES + "}]}}", JOHN_DOE_EMPLOYEE),
                Arguments.of("addresses", "{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                        + "\"addresses\":" + JOHN_DOE_ADDRESSES + ",\"addresses\":" + otherAddresses + "}]}}",
                        JOHN_DOE_EMPLOYEE),
                Arguments.of("address", "{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                        + "\"addresses\":{\"address\":[{\"street\":\"123 Main Street\",\"zipCode\":\"111\"},"
                        + "{\"street\":\"987 Cypress Avenue\",\"zipCode\":\"222\"}],"
                        + "\"address\":[{\"street\":\"1 A St\"}]}}]}}", JOHN_DOE_EMPLOYEE),
                Arguments.of("employee", "{\"employees\":{\"employee\":[" + johnDoe + "],"
                        + "\"employee\":[" + janeRoe + "]}}", JOHN_DOE_EMPLOYEE),
                Arguments.of("employees", "{\"employees\":{\"employee\":[" + johnDoe + "]},"
                        + "\"employees\":{\"employee\":[" + janeRoe + "]}}", JOHN_DOE_EMPLOYEE));
    }

    @ParameterizedTest(name = "repeated {0}")
    @MethodSource("repeatedSelectedKeys")
    @DisplayName("a repeated key under a selector gives its first member, as DataWeave's single-value selector does")
    void repeatedKeySelectsFirstMember(String key, String input, String employee) {
        String output = mapper.toXml(utf8(input));

        assertThat(output).isEqualTo(DECLARATION_LINE + "<employees>\n" + employee + "</employees>");
    }

    @Test
    @DisplayName("address: $ writes every member of an address object, a repeated key once per occurrence, in order")
    void repeatedAddressKeysAreAllWrittenInOrder() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 A St\",\"zipCode\":\"9\",\"street\":\"2 B St\"}]}}]}}"));

        assertThat(output).isEqualTo(DECLARATION_LINE
                + "<employees>\n"
                + "\t<employee>\n"
                + "\t\t<name>John</name>\n"
                + "\t\t<lastName>Doe</lastName>\n"
                + "\t\t<addresses>\n"
                + "\t\t\t<address>\n"
                + "\t\t\t\t<street>1 A St</street>\n"
                + "\t\t\t\t<zipCode>9</zipCode>\n"
                + "\t\t\t\t<street>2 B St</street>\n"
                + "\t\t\t</address>\n"
                + "\t\t</addresses>\n"
                + "\t</employee>\n"
                + "</employees>");
    }

    @Test
    @DisplayName("decimals keep their scale; integers and booleans are written as read")
    void numbersAndBooleansAreWrittenAsRead() {
        String output = mapper.toXml(utf8("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"zipCode\":1.50,\"floor\":7,\"primary\":true,"
                + "\"shared\":false}]}}]}}"));

        assertThat(output).contains("\t\t\t\t<zipCode>1.50</zipCode>\n"
                + "\t\t\t\t<floor>7</floor>\n"
                + "\t\t\t\t<primary>true</primary>\n"
                + "\t\t\t\t<shared>false</shared>\n");
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
        String notAnObject = "Expected a JSON object at the root but found ";
        return Stream.of(
                Arguments.of("empty bytes", new byte[0], "JSON payload is empty", null),
                Arguments.of("null", null, "JSON payload is empty", null),
                Arguments.of("blank", utf8("   "), "JSON payload is empty", null),
                Arguments.of("malformed", utf8("{\"employees\": ["), "Unexpected end-of-input",
                        JsonParseException.class),
                Arguments.of("trailing content", utf8("{} {}"),
                        "Trailing token (of type START_OBJECT) found after the JSON value", JsonParseException.class),
                // Jackson's encoding detection rejects this UCS-4 byte order with a CharConversionException, an
                // IOException that is not a JsonProcessingException.
                Arguments.of("unsupported UCS-4 byte order", new byte[] {0, 0, (byte) 0xFF, (byte) 0xFE, 0, 0, 0, '{'},
                        "Unsupported UCS-4 endianness", CharConversionException.class),
                Arguments.of("top-level array", utf8("[]"), notAnObject + "array", null),
                Arguments.of("top-level string", utf8("\"text\""), notAnObject + "string", null),
                Arguments.of("top-level number", utf8("1"), notAnObject + "number", null),
                Arguments.of("top-level boolean", utf8("true"), notAnObject + "boolean", null),
                Arguments.of("top-level null", utf8("null"), notAnObject + "null", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidInputs")
    @DisplayName("empty, malformed, trailing or non-object input raises IllegalArgumentException from both methods, "
            + "with its message and the parser's exception as cause")
    void invalidInputIsRejected(String label, byte[] input, String message, Class<? extends Throwable> cause) {
        assertRejected(assertThatThrownBy(() -> mapper.toXml(input)), message, cause);
        assertRejected(assertThatThrownBy(() -> mapper.toXmlBytes(input)), message, cause);
    }

    @Test
    @DisplayName("the injected ObjectMapper and its factory keep their settings, strict duplicate detection included")
    void injectedObjectMapperIsNotModified() {
        ObjectMapper applicationMapper = new ObjectMapper();
        ObjectMapper strictMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        byte[] repeated = utf8("{\"employees\":{\"employee\":[{\"name\":\"Jack\",\"name\":\"John\"}]}}");

        String output = new EmployeesXmlMapper(new DwXmlWriter(), applicationMapper).toXml(repeated);
        String strictOutput = new EmployeesXmlMapper(new DwXmlWriter(), strictMapper).toXml(repeated);

        assertThat(output).contains("\t\t<name>Jack</name>\n").doesNotContain("John");
        assertThat(strictOutput).isEqualTo(output);
        assertThat(applicationMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
        assertThat(applicationMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        assertThat(applicationMapper.getFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION)).isFalse();
        assertThat(strictMapper.getFactory().isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION)).isTrue();
        assertThatThrownBy(() -> strictMapper.readTree(repeated)).isInstanceOf(JsonParseException.class)
                .hasMessageContaining("Duplicate field 'name'");
    }

    private static void assertRejected(AbstractThrowableAssert<?, ? extends Throwable> rejection, String message,
            Class<? extends Throwable> cause) {
        rejection.isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith(message);
        if (cause == null) {
            rejection.hasNoCause();
        } else {
            rejection.hasCauseInstanceOf(cause);
        }
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
