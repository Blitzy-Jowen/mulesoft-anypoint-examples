/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.mule_expression_language_basics.mapper.DwCsvWriter;
import com.mulesoft.examples.mule_expression_language_basics.mapper.GreetingCsvMapper;

/**
 * Unit test of {@link GreetingService}, the six greeting flows of
 * {@code mule-expression-language-basics/src/main/app/greeting.xml} with their MEL expressions written as Java.
 *
 * <p>The original {@code MuleExpressionLanguageBasicsIT} sends {@code username=Mule&age=1} (or the carried
 * {@code message.xml} and {@code message.json}) to every flow, expects {@code Hello Mule} and, for
 * {@code greet3} to {@code greet6}, exactly one file holding {@code Mule, 1, false} ({@code Mule,1,false} for
 * {@code greet4}) once both sides are trimmed. Here the exact, untrimmed bytes are asserted: no line end after the
 * MEL template line, a single LF after the DataWeave CSV line. Every reachable branch is exercised: the
 * {@code greet2} choice, each operand type of the MEL {@code > 18} comparison, the XPath selections of
 * {@code greet5} and the payload shapes of {@code greet6}, together with the failures that write no file.
 *
 * <p>The collaborators are real: {@link GreetingCsvMapper} over {@link DwCsvWriter}, and
 * {@link FileOutboundWriter} writing into {@code Path_of_your_choice} under a JUnit {@link TempDir}, which the
 * service must create itself. No Spring context and no mock is used, and nothing is written outside the
 * temporary directory.
 */
class GreetingServiceTest {

    /** The name Mule gave an outbound file without {@code outputPattern}: {@code #[function:uuid].dat}. */
    private static final String DEFAULT_FILE_NAME = "[0-9a-f-]{36}\\.dat";

    @TempDir
    Path tempDir;

    private Path outDir;

    private GreetingService service;

    @BeforeEach
    void setUp() {
        // Not created here: the service creates the endpoint's directory on its first write.
        outDir = tempDir.resolve("Path_of_your_choice");
        service = new GreetingService(new GreetingCsvMapper(new DwCsvWriter()), new FileOutboundWriter(),
                outDir.toString());
    }

    // ---------------------------------------------------------------------------------------------- greet1

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("greet1Cases")
    @DisplayName("greet1: Hello <username>, the username rendered as the MEL template renders it; no file")
    void greet1GreetsUsername(String username, String expected) {
        assertThat(service.greet1(username)).isEqualTo(expected);
        assertThat(outDir).doesNotExist();
    }

    static Stream<Arguments> greet1Cases() {
        return Stream.of(
                Arguments.of(Named.of("\"Mule\"", "Mule"), "Hello Mule"),
                // GreetingService#greet1 Javadoc: "Hello null without a username"; a MEL template renders a null
                // value as the text null.
                Arguments.of(Named.of("null", null), "Hello null"),
                Arguments.of(Named.of("\"\"", ""), "Hello "),
                Arguments.of(Named.of("\"   \"", "   "), "Hello    "));
    }

    // ---------------------------------------------------------------------------------------------- greet2

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("greet2Cases")
    @DisplayName("greet2: an empty username -> No username provided, otherwise Hello <username>; no file")
    void greet2RoutesOnEmptyUsername(String username, String expected) {
        assertThat(service.greet2(username)).isEqualTo(expected);
        assertThat(outDir).doesNotExist();
    }

    static Stream<Arguments> greet2Cases() {
        return Stream.of(
                // The original melFlow2Test calls greet2 without a query: the absent parameter is empty.
                Arguments.of(Named.of("null", null), "No username provided"),
                Arguments.of(Named.of("\"\"", ""), "No username provided"),
                // GreetingService#greet2 Javadoc: the engine's blank literal does not trim, so a whitespace-only
                // username is not empty.
                Arguments.of(Named.of("\"   \"", "   "), "Hello    "),
                Arguments.of(Named.of("\" \\t\\n\"", " \t\n"), "Hello  \t\n"),
                Arguments.of(Named.of("\"Mule\"", "Mule"), "Hello Mule"),
                // The README's example request ?username=yourName.
                Arguments.of(Named.of("\"yourName\"", "yourName"), "Hello yourName"),
                // GreetingService#greet2 Javadoc: a String "0" is not empty.
                Arguments.of(Named.of("\"0\"", "0"), "Hello 0"),
                // The greeting keeps the username exactly as given.
                Arguments.of(Named.of("\" Mule \"", " Mule "), "Hello  Mule "));
    }

    // ---------------------------------------------------------------------------------- melGreaterThan18

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("comparableCases")
    @DisplayName("MEL value > 18: null, Boolean, map and list false; numbers and plain-number Strings compared")
    void melGreaterThan18ComparesWithEighteen(Object value, boolean expected) {
        assertThat(GreetingService.melGreaterThan18(value)).isEqualTo(expected);
    }

    static Stream<Arguments> comparableCases() {
        return Stream.of(
                // A String the engine reads as a plain number is compared by its double value.
                Arguments.of(Named.of("\"1\"", "1"), false),
                Arguments.of(Named.of("\"5\"", "5"), false),
                Arguments.of(Named.of("\"18\"", "18"), false),
                Arguments.of(Named.of("\"18.0\"", "18.0"), false),
                Arguments.of(Named.of("\"-22\"", "-22"), false),
                Arguments.of(Named.of("\"22\"", "22"), true),
                Arguments.of(Named.of("\"18.5\"", "18.5"), true),
                Arguments.of(Named.of("\"0022\"", "0022"), true),
                Arguments.of(Named.of("\"100\"", "100"), true),
                // The engine's number test accepts a terminal decimal point, which Double.parseDouble reads.
                Arguments.of(Named.of("\"19.\"", "19."), true),
                Arguments.of(Named.of("\"5.\"", "5."), false),
                Arguments.of(Named.of("\"18.\"", "18."), false),
                Arguments.of(Named.of("\"-5.\"", "-5."), false),
                // A null left operand is not comparable, and the comparison yields false.
                Arguments.of(Named.of("null", null), false),
                // Both operands become Booleans (18 becomes true); nothing compares greater than true.
                Arguments.of(Named.of("Boolean.TRUE", Boolean.TRUE), false),
                Arguments.of(Named.of("Boolean.FALSE", Boolean.FALSE), false),
                // Neither Comparable nor Number: not comparable and not greater.
                Arguments.of(Named.of("Map {a=1}", Map.of("a", 1)), false),
                Arguments.of(Named.of("List [1]", List.of(1)), false),
                // Integral types by their long value.
                Arguments.of(Named.of("Integer 19", 19), true),
                Arguments.of(Named.of("Integer 18", 18), false),
                Arguments.of(Named.of("Long 18", 18L), false),
                Arguments.of(Named.of("Long 19", 19L), true),
                Arguments.of(Named.of("Short 19", (short) 19), true),
                Arguments.of(Named.of("Byte 18", (byte) 18), false),
                // BigInteger and BigDecimal exactly, by compareTo.
                Arguments.of(Named.of("BigInteger 18", BigInteger.valueOf(18)), false),
                Arguments.of(Named.of("BigInteger 12345678901234567890", new BigInteger("12345678901234567890")),
                        true),
                Arguments.of(Named.of("BigDecimal 18.00", new BigDecimal("18.00")), false),
                Arguments.of(Named.of("BigDecimal 18.0000000000000001", new BigDecimal("18.0000000000000001")),
                        true),
                // Any other number by its double value, so NaN is not greater.
                Arguments.of(Named.of("Double 18.5", 18.5d), true),
                Arguments.of(Named.of("Double 18.0", 18.0d), false),
                Arguments.of(Named.of("Float 18.0", 18.0f), false),
                Arguments.of(Named.of("Double NaN", Double.NaN), false),
                Arguments.of(Named.of("Double +Infinity", Double.POSITIVE_INFINITY), true),
                Arguments.of(Named.of("AtomicInteger 19", new AtomicInteger(19)), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uncomparableCases")
    @DisplayName("MEL value > 18: any other String or Comparable cannot be compared and fails the expression")
    void melGreaterThan18RejectsUncomparableValue(Object value, String message) {
        assertThatThrownBy(() -> GreetingService.melGreaterThan18(value))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> uncomparableCases() {
        return Stream.of(
                Arguments.of(Named.of("\"\"", ""), "uncomparable values <<>> and <<18>>"),
                Arguments.of(Named.of("\"abc\"", "abc"), "uncomparable values <<abc>> and <<18>>"),
                Arguments.of(Named.of("\" 22\"", " 22"), "uncomparable values << 22>> and <<18>>"),
                Arguments.of(Named.of("\"1e3\"", "1e3"), "uncomparable values <<1e3>> and <<18>>"),
                Arguments.of(Named.of("\"0x20\"", "0x20"), "uncomparable values <<0x20>> and <<18>>"),
                Arguments.of(Named.of("\"22L\"", "22L"), "uncomparable values <<22L>> and <<18>>"),
                Arguments.of(Named.of("\"-\"", "-"), "uncomparable values <<->> and <<18>>"),
                Arguments.of(Named.of("\"1.2.3\"", "1.2.3"), "uncomparable values <<1.2.3>> and <<18>>"),
                // The engine's number test admits these digitless forms, which then fail Double.parseDouble there.
                Arguments.of(Named.of("\".\"", "."), "uncomparable values <<.>> and <<18>>"),
                Arguments.of(Named.of("\"-.\"", "-."), "uncomparable values <<-.>> and <<18>>"),
                // Any other Comparable fails, as its compareTo(18) did.
                Arguments.of(Named.of("Character 'a'", 'a'), "uncomparable values <<a>> and <<18>>"),
                Arguments.of(Named.of("LocalDate 2014-11-04", LocalDate.of(2014, 11, 4)),
                        "uncomparable values <<2014-11-04>> and <<18>>"));
    }

    // ---------------------------------------------------------------------------------------------- greet3

    @ParameterizedTest(name = "username={0}, age={1} -> {3}")
    @MethodSource("greet3Cases")
    @DisplayName("greet3: writes <username>, <age>, <age > 18> without a line end and returns Hello <username>")
    void greet3WritesMelLine(String username, String age, String reply, String file) throws Exception {
        assertThat(service.greet3(username, age)).isEqualTo(reply);
        assertThat(onlyFileContent()).isEqualTo(file);
    }

    static Stream<Arguments> greet3Cases() {
        return Stream.of(
                // The original melFlow3Test request.
                Arguments.of("Mule", "1", "Hello Mule", "Mule, 1, false"),
                // The README's example request ?username=yourName&age=22.
                Arguments.of("yourName", "22", "Hello yourName", "yourName, 22, true"),
                Arguments.of("Mule", "18", "Hello Mule", "Mule, 18, false"),
                Arguments.of("Mule", "18.5", "Hello Mule", "Mule, 18.5, true"),
                // A terminal decimal point: compared as 19.0, the age rendered as given.
                Arguments.of("Mule", "19.", "Hello Mule", "Mule, 19., true"),
                // GreetingService Javadoc: absent inputs appear as the text null in responses and files.
                Arguments.of(null, null, "Hello null", "null, null, false"));
    }

    @ParameterizedTest(name = "age={0}")
    @MethodSource("greet3UncomparableAges")
    @DisplayName("greet3: an age MEL cannot compare with 18 fails the call and writes no file")
    void greet3RejectsUncomparableAge(String age, String message) {
        assertThatThrownBy(() -> service.greet3("Mule", age))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(outDir).doesNotExist();
    }

    static Stream<Arguments> greet3UncomparableAges() {
        return Stream.of(
                Arguments.of("abc", "uncomparable values <<abc>> and <<18>>"),
                Arguments.of("", "uncomparable values <<>> and <<18>>"));
    }

    // ---------------------------------------------------------------------------------------------- greet4

    @ParameterizedTest(name = "username={0}, age={1} -> {3}")
    @MethodSource("greet4Cases")
    @DisplayName("greet4: writes the header-less DataWeave CSV line with its LF and returns Hello <username>")
    void greet4WritesCsvLine(String username, String age, String reply, String file) throws Exception {
        assertThat(service.greet4(username, age)).isEqualTo(reply);
        assertThat(onlyFileContent()).isEqualTo(file);
    }

    static Stream<Arguments> greet4Cases() {
        return Stream.of(
                // The original melFlow4Test request.
                Arguments.of("Mule", "1", "Hello Mule", "Mule,1,false\n"),
                // The README's example request ?username=yourName&age=22.
                Arguments.of("yourName", "22", "Hello yourName", "yourName,22,true\n"),
                // GreetingCsvMapper Javadoc: a null value becomes an empty field and a missing age is never over 18.
                Arguments.of("Mule", null, "Hello Mule", "Mule,,false\n"),
                // The reply is the MEL template, which renders null as null; the CSV field is empty.
                Arguments.of(null, "1", "Hello null", ",1,false\n"),
                // GreetingCsvMapper Javadoc: age_of follows DataWeave, comparing a String age with "18" as text, so
                // "abc" is over 18 and no comparison fails, unlike greet3's MEL; a Tier 2A verification point.
                Arguments.of("Mule", "abc", "Hello Mule", "Mule,abc,true\n"));
    }

    // ---------------------------------------------------------------------------------------------- greet5

    @Test
    @DisplayName("greet5: the original's message.xml -> Hello Mule and the file Mule, 1, false")
    void greet5WritesOriginalMessage() throws Exception {
        byte[] xml = resource("/message.xml");
        assertThat(xml).hasSize(55);

        assertThat(service.greet5(xml)).isEqualTo("Hello Mule");
        assertThat(onlyFileContent()).isEqualTo("Mule, 1, false");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("greet5Cases")
    @DisplayName("greet5: .text of /user/username and /user/age; writes <username>, <age>, <age > 18>")
    void greet5WritesXpathLine(String xml, String reply, String file) throws Exception {
        assertThat(service.greet5(xml.getBytes(StandardCharsets.UTF_8))).isEqualTo(reply);
        assertThat(onlyFileContent()).isEqualTo(file);
    }

    static Stream<Arguments> greet5Cases() {
        return Stream.of(
                Arguments.of(Named.of("test, 21", "<user><username>test</username><age>21</age></user>"),
                        "Hello test", "test, 21, true"),
                Arguments.of(Named.of("age 18.5", "<user><username>Mule</username><age>18.5</age></user>"),
                        "Hello Mule", "Mule, 18.5, true"),
                Arguments.of(Named.of("age 19.", "<user><username>Mule</username><age>19.</age></user>"),
                        "Hello Mule", "Mule, 19., true"),
                // GreetingService#greet5 Javadoc: .text is dom4j's Element.getText(), the direct text and CDATA
                // without the text of child elements and comments.
                Arguments.of(Named.of("text, comment, CDATA and child element",
                        "<user><username>Mu<!-- c --><![CDATA[le]]><b>x</b></username><age>1</age></user>"),
                        "Hello Mule", "Mule, 1, false"),
                // GreetingService#greet5 Javadoc: internal entities are expanded into text.
                Arguments.of(Named.of("internal entity",
                        "<!DOCTYPE user [<!ENTITY n \"Mule\">]><user><username>&n;</username><age>1</age></user>"),
                        "Hello Mule", "Mule, 1, false"),
                // An empty element has no text: the username renders as the empty String.
                Arguments.of(Named.of("empty username", "<user><username/><age>1</age></user>"),
                        "Hello ", ", 1, false"));
    }

    @Test
    @DisplayName("greet5: the body's encoding is read from its XML declaration; the file is written as UTF-8")
    void greet5ReadsEncodingFromXmlDeclaration() throws Exception {
        byte[] latin1 = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                + "<user><username>M\u00fcller</username><age>30</age></user>").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(service.greet5(latin1)).isEqualTo("Hello M\u00fcller");
        assertThat(onlyFileContent()).isEqualTo("M\u00fcller, 30, true");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("externalReferences")
    @DisplayName("greet5: an external entity or external DTD subset is never loaded; its reference reads as no text")
    void greet5NeverLoadsExternalReferences(String doctypeTemplate, String externalContent) throws Exception {
        // GreetingService#parseXml Javadoc: external entities and the external DTD subset are never loaded.
        Path external = Files.writeString(tempDir.resolve("external.txt"), externalContent);
        String xml = doctypeTemplate.formatted(external.toUri())
                + "<user><username>a&x;b</username><age>1</age></user>";

        assertThat(service.greet5(xml.getBytes(StandardCharsets.UTF_8))).isEqualTo("Hello ab");
        assertThat(onlyFileContent()).isEqualTo("ab, 1, false").doesNotContain("SECRET");
    }

    static Stream<Arguments> externalReferences() {
        return Stream.of(
                Arguments.of(Named.of("external general entity", "<!DOCTYPE user [<!ENTITY x SYSTEM \"%s\">]>"),
                        "SECRET"),
                Arguments.of(Named.of("external DTD subset declaring x", "<!DOCTYPE user SYSTEM \"%s\">"),
                        "<!ENTITY x \"SECRET\">"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("greet5SelectionFailures")
    @DisplayName("greet5: an XPath that does not select exactly one element, or an uncomparable age, writes no file")
    void greet5RejectsUnusableDocument(String xml, Class<? extends Exception> type, String message) {
        assertThatThrownBy(() -> service.greet5(xml.getBytes(StandardCharsets.UTF_8)))
                .isExactlyInstanceOf(type)
                .hasMessage(message);
        assertThat(outDir).doesNotExist();
    }

    static Stream<Arguments> greet5SelectionFailures() {
        return Stream.of(
                Arguments.of(Named.of("missing <age>", "<user><username>Mule</username></user>"),
                        IllegalStateException.class, "xpath('/user/age') selected 0 nodes"),
                Arguments.of(Named.of("missing <username>", "<user><age>1</age></user>"),
                        IllegalStateException.class, "xpath('/user/username') selected 0 nodes"),
                Arguments.of(Named.of("repeated <username>",
                        "<user><username>a</username><username>b</username><age>1</age></user>"),
                        IllegalStateException.class, "xpath('/user/username') selected 2 nodes"),
                // GreetingService#greet5 Javadoc: an unprefixed name matches only an element in no namespace.
                Arguments.of(Named.of("default namespace",
                        "<user xmlns=\"urn:x\"><username>Mule</username><age>1</age></user>"),
                        IllegalStateException.class, "xpath('/user/username') selected 0 nodes"),
                Arguments.of(Named.of("age abc", "<user><username>Mule</username><age>abc</age></user>"),
                        IllegalArgumentException.class, "uncomparable values <<abc>> and <<18>>"),
                Arguments.of(Named.of("empty <age/>", "<user><username>Mule</username><age/></user>"),
                        IllegalArgumentException.class, "uncomparable values <<>> and <<18>>"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"<user><username>Mule</user>", "<user><username>Mule</username><age>1</age>",
            "<a:user><username>Mule</username><age>1</age></a:user>", "not xml"})
    @DisplayName("greet5: a body that is not well-formed, namespace-aware XML fails the parse and writes no file")
    void greet5RejectsMalformedXml(String xml) {
        assertThatThrownBy(() -> service.greet5(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SAXParseException.class);
        assertThat(outDir).doesNotExist();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("greet5: an absent or empty body fails and writes no file")
    void greet5RejectsMissingBody(byte[] body) {
        assertThatThrownBy(() -> service.greet5(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("greet5 requires an XML body");
        assertThat(outDir).doesNotExist();
    }

    // ---------------------------------------------------------------------------------------------- greet6

    @Test
    @DisplayName("greet6: the original's message.json -> Hello Mule and the file Mule, 1, false")
    void greet6WritesOriginalMessage() throws Exception {
        byte[] json = resource("/message.json");
        assertThat(json).hasSize(34);

        assertThat(service.greet6(json)).isEqualTo("Hello Mule");
        assertThat(onlyFileContent()).isEqualTo("Mule, 1, false");
    }

    @ParameterizedTest(name = "age {0} -> {1}")
    @MethodSource("greet6AgeCases")
    @DisplayName("greet6: the age keeps the JSON type it was parsed to, rendered and compared as MEL does")
    void greet6WritesAgeAsParsed(String age, String file) throws Exception {
        byte[] json = ("{\"username\":\"test\",\"age\":" + age + "}").getBytes(StandardCharsets.UTF_8);

        assertThat(service.greet6(json)).isEqualTo("Hello test");
        assertThat(onlyFileContent()).isEqualTo(file);
    }

    static Stream<Arguments> greet6AgeCases() {
        return Stream.of(
                Arguments.of("21", "test, 21, true"),
                Arguments.of("18", "test, 18, false"),
                Arguments.of("21.5", "test, 21.5, true"),
                Arguments.of("\"21\"", "test, 21, true"),
                // A JSON String with a terminal decimal point (a bare 19. is not valid JSON).
                Arguments.of("\"19.\"", "test, 19., true"),
                Arguments.of("12345678901234567890", "test, 12345678901234567890, true"),
                // GreetingService Javadoc: a null value renders as null and is not greater than 18.
                Arguments.of("null", "test, null, false"),
                // GreetingService#melGreaterThan18 Javadoc: no Boolean compares greater than true.
                Arguments.of("true", "test, true, false"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("greet6ShapeCases")
    @DisplayName("greet6: a missing key reads as null; a nested object or array renders with its toString()")
    void greet6RendersPayloadShapes(String json, String reply, String file) throws Exception {
        assertThat(service.greet6(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(reply);
        assertThat(onlyFileContent()).isEqualTo(file);
    }

    static Stream<Arguments> greet6ShapeCases() {
        return Stream.of(
                Arguments.of(Named.of("{}", "{}"), "Hello null", "null, null, false"),
                Arguments.of(Named.of("nested object and array", "{\"username\":{\"x\":1},\"age\":[1,2]}"),
                        "Hello {x=1}", "{x=1}, [1, 2], false"),
                Arguments.of(Named.of("extra key", "{\"age\":22,\"username\":\"Mule\",\"extra\":true}"),
                        "Hello Mule", "Mule, 22, true"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"username\":", "not json", "{\"username\":\"Mule\",}"})
    @DisplayName("greet6: a body that is not valid JSON fails the parse and writes no file")
    void greet6RejectsMalformedJson(String json) {
        // GreetingService#greet6 throws Jackson's JsonProcessingException, an IOException.
        assertThatThrownBy(() -> service.greet6(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
        assertThat(outDir).doesNotExist();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("greet6Rejections")
    @DisplayName("greet6: a payload that is not an object, or an uncomparable age, fails and writes no file")
    void greet6RejectsUnusablePayload(String json, String message) {
        assertThatThrownBy(() -> service.greet6(json.getBytes(StandardCharsets.UTF_8)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        assertThat(outDir).doesNotExist();
    }

    static Stream<Arguments> greet6Rejections() {
        return Stream.of(
                Arguments.of("[1,2]", "greet6 requires a JSON object"),
                Arguments.of("null", "greet6 requires a JSON object"),
                Arguments.of("\"Mule\"", "greet6 requires a JSON object"),
                Arguments.of("42", "greet6 requires a JSON object"),
                Arguments.of("{\"username\":\"test\",\"age\":\"abc\"}", "uncomparable values <<abc>> and <<18>>"),
                Arguments.of("{\"username\":\"test\",\"age\":\"\"}", "uncomparable values <<>> and <<18>>"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("greet6: an absent or empty body fails and writes no file")
    void greet6RejectsMissingBody(byte[] body) {
        assertThatThrownBy(() -> service.greet6(body))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("greet6 requires a JSON body");
        assertThat(outDir).doesNotExist();
    }

    // ------------------------------------------------------------------------------------ greet3 to greet6

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileWritingCalls")
    @DisplayName("greet3..greet6: every successful call writes one new <uuid>.dat file")
    void eachCallWritesNewFile(GreetingCall call, String file) throws Exception {
        assertThat(call.invoke(service)).isEqualTo("Hello Mule");
        assertThat(call.invoke(service)).isEqualTo("Hello Mule");

        List<Path> files = outputFiles();
        assertThat(files).hasSize(2);
        assertThat(files.get(0)).isNotEqualTo(files.get(1));
        for (Path written : files) {
            assertThat(written.getFileName().toString()).matches(DEFAULT_FILE_NAME);
            assertThat(Files.readAllBytes(written)).isEqualTo(file.getBytes(StandardCharsets.UTF_8));
        }
    }

    // The source's second argument, the expected file content, is not declared: no file can be written here.
    @ParameterizedTest(name = "{0}")
    @MethodSource("fileWritingCalls")
    @DisplayName("greet3..greet6: a failed write reaches the caller unchanged")
    void writeFailureReachesCaller(GreetingCall call) throws IOException {
        // A regular file where the endpoint's directory belongs: the directory cannot be created.
        Files.createFile(outDir);

        assertThatThrownBy(() -> call.invoke(service)).isExactlyInstanceOf(FileAlreadyExistsException.class);
        assertThat(outDir).isRegularFile().isEmptyFile();
    }

    static Stream<Arguments> fileWritingCalls() {
        return Stream.of(
                Arguments.of(Named.<GreetingCall>of("greet3", s -> s.greet3("Mule", "1")), "Mule, 1, false"),
                Arguments.of(Named.<GreetingCall>of("greet4", s -> s.greet4("Mule", "1")), "Mule,1,false\n"),
                Arguments.of(Named.<GreetingCall>of("greet5", s -> s.greet5(resource("/message.xml"))),
                        "Mule, 1, false"),
                Arguments.of(Named.<GreetingCall>of("greet6", s -> s.greet6(resource("/message.json"))),
                        "Mule, 1, false"));
    }

    // --------------------------------------------------------------------------------------------- helpers

    /** One call of a file-writing greeting flow. */
    @FunctionalInterface
    interface GreetingCall {
        String invoke(GreetingService service) throws Exception;
    }

    /** The regular files in the output directory, sorted; none while the directory does not exist. */
    private List<Path> outputFiles() throws IOException {
        if (Files.notExists(outDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(outDir)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** Asserts that the output directory holds exactly one {@code <uuid>.dat} file and returns its UTF-8 text. */
    private String onlyFileContent() throws IOException {
        List<Path> files = outputFiles();
        assertThat(files).as("files written to %s", outDir).hasSize(1);
        Path file = files.get(0);
        assertThat(file.getFileName().toString()).matches(DEFAULT_FILE_NAME);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Reads a classpath resource, failing the test when it is missing. */
    private static byte[] resource(String path) throws IOException {
        try (InputStream in = GreetingServiceTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return in.readAllBytes();
        }
    }
}
