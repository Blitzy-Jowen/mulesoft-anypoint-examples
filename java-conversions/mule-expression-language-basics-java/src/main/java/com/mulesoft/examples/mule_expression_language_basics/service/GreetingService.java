/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.mule_expression_language_basics.mapper.GreetingCsvMapper;

/**
 * The six greeting flows of {@code mule-expression-language-basics/src/main/app/greeting.xml}, with every MEL
 * expression they evaluate rewritten as plain Java. The controller only binds the request and returns what these
 * methods produce; every literal, branch and comparison lives here.
 *
 * <table>
 *   <caption>Methods and the flows they reproduce</caption>
 *   <tr><th>Method</th><th>Flow</th><th>{@code greeting.xml} lines</th></tr>
 *   <tr><td>{@link #greet1(String)}</td><td>{@code greetingFlow1}</td><td>7-11</td></tr>
 *   <tr><td>{@link #greet2(String)}</td><td>{@code greetingFlow2}</td><td>15-26</td></tr>
 *   <tr><td>{@link #greet3(String, String)}</td><td>{@code docs-greetingFlow3}</td><td>30-37</td></tr>
 *   <tr><td>{@link #greet4(String, String)}</td><td>{@code docs-greetingFlow4}</td><td>41-60</td></tr>
 *   <tr><td>{@link #greet5(byte[])}</td><td>{@code docs-greetingFlow5}</td><td>64-71</td></tr>
 *   <tr><td>{@link #greet6(byte[])}</td><td>{@code greetingFlow6}</td><td>75-83</td></tr>
 * </table>
 *
 * <p>{@code greet3} to {@code greet6} each write exactly one new file per successful call into the configured
 * {@code greeting.output-dir} (the endpoints' {@code path="Path_of_your_choice"}) through {@link FileOutboundWriter},
 * named as Mule named an outbound file without {@code outputPattern}. The file content is computed in full before
 * the write, so a call whose expressions fail writes nothing. The flows declare no logger and no exception strategy:
 * nothing is logged here and every exception reaches the caller unchanged, where the platform-default 500 answers
 * it.
 *
 * <p>A MEL template renders a {@code null} value as {@code null}, so absent inputs appear as that text in responses
 * and files.
 *
 * <p>The only mutable state is per call (XML parser and XPath objects are created inside {@link #greet5(byte[])}),
 * so the singleton is safe to share between threads.
 */
@Service
public class GreetingService {

    /** The prefix of every greeting the flows return ({@code Hello #[...]}). */
    private static final String HELLO = "Hello ";

    /** The payload {@code greetingFlow2} sets when no username is given ({@code greeting.xml:20}). */
    private static final String NO_USERNAME = "No username provided";

    /** The separator of the three values in the {@code greet3}, {@code greet5} and {@code greet6} files. */
    private static final String SEPARATOR = ", ";

    private static final String USERNAME_XPATH = "/user/username";
    private static final String AGE_XPATH = "/user/age";

    /** The Integer literal of every MEL {@code > 18} comparison. */
    private static final long THRESHOLD = 18L;
    private static final BigInteger BIG_INTEGER_THRESHOLD = BigInteger.valueOf(THRESHOLD);
    private static final BigDecimal BIG_DECIMAL_THRESHOLD = BigDecimal.valueOf(THRESHOLD);

    /**
     * Parses {@code greet6} bodies with Jackson's defaults, the types Mule's {@code json-to-object-transformer}
     * produced for {@code returnClass="java.lang.Object"}: objects as {@code LinkedHashMap}, arrays as
     * {@code ArrayList}, integers as {@code Integer}, {@code Long} or {@code BigInteger}, decimals as {@code Double}.
     * Boot's shared mapper is not used, because its customisations must not change those types. An
     * {@code ObjectMapper} is thread-safe once configured.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GreetingCsvMapper greetingCsvMapper;
    private final FileOutboundWriter fileOutboundWriter;
    private final String outputDir;

    /**
     * Creates the service.
     *
     * @param greetingCsvMapper  the owner of the {@code docs-greetingFlow4} DataWeave script
     * @param fileOutboundWriter the writer that reproduces the flows' {@code file:outbound-endpoint}
     * @param outputDir          the directory every greeting file is written to, absolute or relative to the
     *                           working directory
     */
    public GreetingService(GreetingCsvMapper greetingCsvMapper,
                           FileOutboundWriter fileOutboundWriter,
                           @Value("${greeting.output-dir}") String outputDir) {
        this.greetingCsvMapper = greetingCsvMapper;
        this.fileOutboundWriter = fileOutboundWriter;
        this.outputDir = outputDir;
    }

    /**
     * {@code greetingFlow1}: {@code Hello #[message.inboundProperties['http.query.params'].username]}.
     *
     * @param username the {@code username} query parameter, or {@code null} when absent
     * @return {@code Hello <username>}; {@code Hello null} without a username
     */
    public String greet1(String username) {
        return HELLO + v(username);
    }

    /**
     * {@code greetingFlow2}: the choice {@code #[...username == empty]} returns {@code No username provided}, and
     * otherwise {@code Hello <username>}.
     *
     * <p>MEL's {@code empty} literal is the blank literal of the expression engine Mule 3.8.0 runs
     * ({@code org.mule.mvel:mule-mvel2:2.1.9-MULE-010}). Its equality test accepts {@code null} and the empty String
     * and does not trim, so a whitespace-only username is not empty and is greeted as given ({@code "   "} gives
     * {@code "Hello    "}). A String {@code "0"} is not empty either.
     *
     * @param username the {@code username} query parameter, or {@code null} when absent
     * @return {@code No username provided} for an empty username, otherwise {@code Hello <username>}
     */
    public String greet2(String username) {
        if (username == null || username.isEmpty()) {
            return NO_USERNAME;
        }
        return HELLO + username;
    }

    /**
     * {@code docs-greetingFlow3}: writes {@code <username>, <age>, <age > 18>} to a new file and returns
     * {@code Hello <username>}. The comparison follows {@link #melGreaterThan18(Object)} on the query String.
     *
     * @param username the {@code username} query parameter, or {@code null} when absent
     * @param age      the {@code age} query parameter, or {@code null} when absent
     * @return {@code Hello <username>}
     * @throws IllegalArgumentException when {@code age} cannot be compared with 18; no file is written
     * @throws Exception                when the file cannot be written
     */
    public String greet3(String username, String age) throws Exception {
        String line = v(username) + SEPARATOR + v(age) + SEPARATOR + melGreaterThan18(age);
        fileOutboundWriter.write(outputDir, FileOutboundWriter.defaultFileName(), line);
        return HELLO + v(username);
    }

    /**
     * {@code docs-greetingFlow4}: builds the map {@code ['username' : ..., 'age' : ...]} from the query parameters,
     * writes the header-less CSV line {@link GreetingCsvMapper} produces from it to a new file and returns
     * {@code Hello <username>}. The mapper owns the script's {@code age_of} rule, which follows DataWeave rather
     * than MEL.
     *
     * @param username the {@code username} query parameter, or {@code null} when absent
     * @param age      the {@code age} query parameter, or {@code null} when absent
     * @return {@code Hello <username>}
     * @throws Exception when the file cannot be written
     */
    public String greet4(String username, String age) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("age", age);
        String csv = greetingCsvMapper.toCsv(payload);
        fileOutboundWriter.write(outputDir, FileOutboundWriter.defaultFileName(), csv);
        return HELLO + v(username);
    }

    /**
     * {@code docs-greetingFlow5}: reads {@code xpath('/user/username').text} and {@code xpath('/user/age').text}
     * from the XML body, writes {@code <username>, <age>, <age > 18>} to a new file and returns
     * {@code Hello <username>}.
     *
     * <p>Mule 3.8 turned the payload into a namespace-aware dom4j document, with internal entities expanded, and
     * {@code .text} is dom4j's {@code Element.getText()}: the text and CDATA directly inside the element, without
     * the text of child elements and comments. Both are reproduced. Each expression must select exactly one element,
     * because {@code .text} fails on the {@code null} an empty selection yields and on the list a repeated element
     * yields. An unprefixed name matches only an element in no namespace, so {@code <user xmlns="urn:x">} selects
     * nothing.
     *
     * @param xml the request body; its encoding is read from its byte order mark or XML declaration
     * @return {@code Hello <username>}
     * @throws IllegalArgumentException when the body is absent or empty, or the age cannot be compared with 18
     * @throws IllegalStateException    when {@code /user/username} or {@code /user/age} does not select exactly one
     *                                  element
     * @throws org.xml.sax.SAXException when the body is not well-formed XML
     * @throws Exception                when the file cannot be written
     */
    public String greet5(byte[] xml) throws Exception {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("greet5 requires an XML body");
        }
        Document document = parseXml(xml);
        XPath xpath = XPathFactory.newInstance().newXPath();
        String username = singleText(select(xpath, USERNAME_XPATH, document), USERNAME_XPATH);
        String age = singleText(select(xpath, AGE_XPATH, document), AGE_XPATH);
        String line = username + SEPARATOR + age + SEPARATOR + melGreaterThan18(age);
        fileOutboundWriter.write(outputDir, FileOutboundWriter.defaultFileName(), line);
        return HELLO + username;
    }

    /**
     * {@code greetingFlow6}: parses the JSON body as {@code json-to-object-transformer returnClass="java.lang.Object"}
     * did, reads {@code payload.username} and {@code payload.age}, writes
     * {@code <username>, <age>, <age > 18>} to a new file and returns {@code Hello <username>}. A missing key reads
     * as {@code null}; a nested object or array renders with its {@code toString()}, for example {@code {x=1}}.
     *
     * @param json the request body
     * @return {@code Hello <username>}
     * @throws IllegalArgumentException when the body is absent, empty or not a JSON object, or the age cannot be
     *                                  compared with 18
     * @throws com.fasterxml.jackson.core.JsonProcessingException when the body is not valid JSON
     * @throws Exception                when the file cannot be written
     */
    public String greet6(byte[] json) throws Exception {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("greet6 requires a JSON body");
        }
        Object parsed = OBJECT_MAPPER.readValue(json, Object.class);
        // payload.username fails on a null, list or scalar payload, as property access on it did.
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("greet6 requires a JSON object");
        }
        Object username = map.get("username");
        Object age = map.get("age");
        String line = v(username) + SEPARATOR + v(age) + SEPARATOR + melGreaterThan18(age);
        fileOutboundWriter.write(outputDir, FileOutboundWriter.defaultFileName(), line);
        return HELLO + v(username);
    }

    /**
     * Evaluates the MEL comparison {@code value > 18}, whose right operand is an Integer literal, as the expression
     * engine's math processor does:
     * <ul>
     *   <li>{@code null} is not greater: a {@code null} left operand is not comparable, and the comparison then
     *       yields {@code false}.</li>
     *   <li>A {@link Number} is compared with 18 numerically and exactly: integral types by their {@code long}
     *       value, {@link BigInteger} and {@link BigDecimal} by {@code compareTo}, any other number by its
     *       {@code double} value, so {@code NaN} is not greater.</li>
     *   <li>A String the engine reads as a plain number (an optional {@code -}, then decimal digits with at most one
     *       {@code .}, holding at least one digit, a terminal {@code .} included) is compared by its {@code double}
     *       value: {@code "22"}, {@code "18.5"}, {@code "0022"} and {@code "19."} are greater, {@code "1"},
     *       {@code "18"}, {@code "18.0"} and {@code "5."} are not. Any other String, such as {@code ""},
     *       {@code "abc"}, {@code " 22"}, {@code "1e3"}, {@code "0x20"} or {@code "22L"}, cannot be compared and
     *       fails the expression.</li>
     *   <li>A {@link Boolean} is compared after both operands become Booleans (18 becomes {@code true}), and neither
     *       {@code true} nor {@code false} compares greater than {@code true}.</li>
     *   <li>Any other {@link Comparable} fails the expression, as its {@code compareTo(18)} did.</li>
     *   <li>Anything else, a map or a list for instance, is not comparable and is not greater.</li>
     * </ul>
     *
     * <p>The engine's own number test also admits {@code ~}-prefixed text and the digitless {@code "."} and
     * {@code "-."}, which then fail {@link Double#parseDouble(String)} there, so they fail here as well. Exponent,
     * hexadecimal and type-suffixed forms are refused. Tier 2A verification point: the message of a failed comparison
     * differs from the text of Mule's expression failure, which the platform-default 500 carried.
     *
     * @param value the left operand as the flow holds it
     * @return whether {@code value} is greater than 18
     * @throws IllegalArgumentException when {@code value} cannot be compared with 18
     */
    static boolean melGreaterThan18(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return numberGreaterThan18(number);
        }
        if (value instanceof String text) {
            if (isMvelPlainNumber(text)) {
                return Double.parseDouble(text) > THRESHOLD;
            }
            throw uncomparable(text);
        }
        if (value instanceof Boolean) {
            return false;
        }
        if (value instanceof Comparable<?>) {
            throw uncomparable(v(value));
        }
        return false;
    }

    private static boolean numberGreaterThan18(Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte) {
            return number.longValue() > THRESHOLD;
        }
        if (number instanceof BigInteger bigInteger) {
            return bigInteger.compareTo(BIG_INTEGER_THRESHOLD) > 0;
        }
        if (number instanceof BigDecimal bigDecimal) {
            return bigDecimal.compareTo(BIG_DECIMAL_THRESHOLD) > 0;
        }
        return number.doubleValue() > THRESHOLD;
    }

    /**
     * Tells whether the engine compares {@code text} as a number: an optional single leading {@code -}, then ASCII
     * digits with at most one {@code .}, holding at least one digit. A terminal {@code .}, as in {@code "19."}, is
     * accepted, as the engine's number test accepts it and {@link Double#parseDouble(String)} reads it.
     */
    private static boolean isMvelPlainNumber(String text) {
        int length = text.length();
        int start = length > 0 && text.charAt(0) == '-' ? 1 : 0;
        boolean digit = false;
        boolean point = false;
        for (int i = start; i < length; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == '.' && !point) {
                point = true;
            } else {
                return false;
            }
        }
        return digit;
    }

    private static IllegalArgumentException uncomparable(String value) {
        return new IllegalArgumentException("uncomparable values <<" + value + ">> and <<18>>");
    }

    /**
     * Parses a {@code greet5} body as Mule 3.8 did for {@code xpath()}: namespace-aware, with the document type
     * declaration allowed and internal entities expanded into text, the parser detecting the encoding from the
     * bytes.
     *
     * <p>External entities, external parameter entities, the external DTD subset and XInclude are never loaded,
     * and secure processing caps entity expansion. This hardening against XML external entity attacks is a
     * deliberate difference from Mule 3.8.0, whose reader resolved external entities; a document without external
     * references parses exactly as before.
     */
    private static Document parseXml(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(RaisingErrorHandler.INSTANCE);
        return builder.parse(new ByteArrayInputStream(xml));
    }

    private static NodeList select(XPath xpath, String expression, Document document)
            throws XPathExpressionException {
        return (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
    }

    /**
     * Returns {@code .text} of the single element {@code nodes} holds: the concatenated data of its direct text and
     * CDATA children, as dom4j's {@code Element.getText()} returned it.
     *
     * @throws IllegalStateException when {@code nodes} does not hold exactly one node
     */
    private static String singleText(NodeList nodes, String expression) {
        int count = nodes.getLength();
        if (count != 1) {
            throw new IllegalStateException("xpath('" + expression + "') selected " + count + " nodes");
        }
        StringBuilder text = new StringBuilder();
        for (Node child = nodes.item(0).getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Text data) {
                text.append(data.getData());
            }
        }
        return text.toString();
    }

    /** Renders a value as a MEL template does: {@code String.valueOf}, so {@code null} becomes {@code null}. */
    private static String v(Object value) {
        return String.valueOf(value);
    }

    /**
     * Ends a parse on every error the parser reports, raising it instead of the parser's default report on standard
     * error. Stateless, so one instance serves every parse.
     */
    private static final class RaisingErrorHandler implements ErrorHandler {

        private static final RaisingErrorHandler INSTANCE = new RaisingErrorHandler();

        @Override
        public void warning(SAXParseException exception) {
            // A warning never ends a parse; the document is still read in full.
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    }
}
