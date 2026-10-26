/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reproduces processors 6 to 15 of flow {@code javascript-calculatorFlow1}
 * (addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:6-15): the byte-array-to-string
 * transformer, the JavaScript transformer
 * {@code var list = eval("(" + payload + ")"); var sum = 0; for (var i in list) sum += list[i]; payload = sum;},
 * the INFO logger {@code Sum is: #[payload]} and the payload {@code Sum is: #[payload].}.
 *
 * <p>No script engine is used and the request body is never evaluated: it is parsed as data, as a JSON document
 * extended with the JavaScript literal forms the parser can read safely (unquoted and single-quoted names and
 * strings, comments, {@code NaN}, {@code Infinity}, trailing commas, {@code .5}, {@code 5.} and {@code +5}). The
 * script's {@code for-in} loop and its {@code +} operator are then reimplemented as ES5 defines them (11.6.1,
 * 9.8): numbers add as doubles, and once either side is a string, an object or an array the sum becomes string
 * concatenation. The result keeps Rhino's types, so a numeric sum is a {@link Double} rendered by MEL with
 * {@link Double#toString(double)} ({@code Sum is: 3.0.}), and a concatenated sum is the JavaScript string.
 *
 * <p>Input that only a JavaScript evaluator would accept fails with {@link IllegalArgumentException}, which the
 * flow, having no exception strategy, answers with the platform 500: hexadecimal ({@code 0x10}) and octal
 * ({@code 010}) numbers, {@code undefined}, a signed {@code NaN}, escapes outside JSON's set ({@code \x41},
 * {@code \v}), expressions, function literals and array holes ({@code [1,,2]}). This is the one intentional
 * difference from the original for hostile input. Conversely, the parser's own {@code +INF}/{@code -INF} and a
 * body ending inside a {@code //} comment are rejected because JavaScript rejects them.
 *
 * <p>Tier 2A verification points: {@code Sum is: 0.0.} for an empty object, an empty array and a number, boolean
 * or {@code null} root, which the loop does not iterate; and the 500 body for unparseable input, whose message is
 * the parser's rather than Rhino's.
 */
@Service
public class AdditionService {

    private static final Logger log = LoggerFactory.getLogger(AdditionService.class);

    static final String NO_CONTENT = "No content to map due to end-of-input";

    static final String LINE_COMMENT_AT_END = "Unexpected end-of-input within a line comment";

    static final String SIGNED_INF = "Unrecognized token 'INF': JavaScript defines no INF literal";

    /*
     * Leading zeros stay disallowed (Rhino reads 010 as octal 8) and missing values stay disallowed (for-in skips
     * an array hole rather than reading null), so both fail instead of producing a different sum. Duplicate keys
     * keep their first position with the last value, as a JavaScript object literal does.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
                    JsonReadFeature.ALLOW_SINGLE_QUOTES,
                    JsonReadFeature.ALLOW_JAVA_COMMENTS,
                    JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,
                    JsonReadFeature.ALLOW_TRAILING_COMMA,
                    JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,
                    JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS,
                    JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final RoundingMode[] SHORTEST_DIGIT_MODES = {
        RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING
    };

    private static final BigDecimal PLAIN_LOWER_BOUND = new BigDecimal("1e-6");

    private static final BigDecimal PLAIN_UPPER_BOUND = new BigDecimal("1e21");

    /**
     * Runs the flow from the request body to the response payload.
     *
     * @param body the request body bytes; {@code null} is read as an empty body
     * @param cs the request charset; {@code null} means the application encoding, UTF-8
     * @return {@code Sum is: <sum>.}
     * @throws IllegalArgumentException when the body is empty or not a literal the script could evaluate
     */
    public String handle(byte[] body, Charset cs) {
        Charset charset = cs == null ? StandardCharsets.UTF_8 : cs;
        String text = body == null ? "" : new String(body, charset);
        Object result = sum(text);
        String rendered = render(result);
        log.info("Sum is: {}", rendered);
        return "Sum is: " + rendered + ".";
    }

    /**
     * Sums the values of {@code text} as the original script's {@code for-in} loop does.
     *
     * @param text the request body as a string
     * @return a {@link Double} when every value added was a number, boolean or {@code null}, otherwise the
     *         concatenated {@link String}
     * @throws IllegalArgumentException when {@code text} is blank or not a literal the script could evaluate
     */
    public Object sum(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(NO_CONTENT);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new IllegalArgumentException(NO_CONTENT);
        }
        rejectNonJavaScriptSyntax(text);

        Object acc = Double.valueOf(0.0);
        for (Object value : forInValues(root)) {
            if (acc instanceof String || concatenates(value)) {
                acc = jsToString(acc) + jsToString(value);
            } else {
                acc = (Double) acc + toNumber((JsonNode) value);
            }
        }
        return acc;
    }

    /**
     * Renders the script result as MEL's {@code #[payload]} does: {@link Double#toString(double)} for the
     * {@link Double} Rhino returns, the text itself for a string.
     */
    static String render(Object value) {
        if (value instanceof Double d) {
            return Double.toString(d);
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Unsupported script result type: " + typeName(value));
    }

    /**
     * Converts a value to its JavaScript string form (ES5 9.8, with ToPrimitive applied to objects and arrays).
     *
     * <p>Besides the parsed {@link JsonNode}s, the plain Java forms of JavaScript values are accepted: {@code null},
     * {@link String}, {@link Number}, {@link Boolean}, an {@link Iterable} as an array and a {@link Map} as an
     * object.
     *
     * @param o the value
     * @return the string {@code String(o)} would produce in JavaScript
     * @throws IllegalArgumentException for any other type
     */
    static String jsToString(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return s;
        }
        if (o instanceof Number n) {
            return numberToString(n.doubleValue());
        }
        if (o instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (o instanceof JsonNode node) {
            return jsonNodeToString(node);
        }
        if (o instanceof Iterable<?> elements) {
            return joinArray(elements);
        }
        if (o instanceof Map<?, ?>) {
            return "[object Object]";
        }
        throw new IllegalArgumentException("Unsupported value type: " + o.getClass().getName());
    }

    private static String jsonNodeToString(JsonNode node) {
        return switch (node.getNodeType()) {
            case STRING -> node.textValue();
            case NUMBER -> numberToString(node.doubleValue());
            case BOOLEAN -> node.booleanValue() ? "true" : "false";
            case NULL -> "null";
            case OBJECT -> "[object Object]";
            case ARRAY -> joinArray(node);
            default -> throw new IllegalArgumentException("Unsupported value type: " + node.getNodeType());
        };
    }

    /** {@code Array.prototype.join(",")}: elements by ToString, a {@code null} element as the empty string. */
    private static String joinArray(Iterable<?> elements) {
        StringJoiner joined = new StringJoiner(",");
        for (Object element : elements) {
            boolean isNull = element == null || element instanceof JsonNode node && node.isNull();
            joined.add(isNull ? "" : jsToString(element));
        }
        return joined.toString();
    }

    /**
     * Converts a number to its JavaScript string form (ES5 9.8.1): the shortest decimal that reads back as
     * {@code d}, written plainly when {@code 1e-6 <= |d| < 1e21} and in exponent form ({@code 1e-7},
     * {@code 1.5e+21}) otherwise.
     *
     * <p>The digits are not taken from {@link Double#toString(double)}, which on JDK 17 is not always shortest
     * (JDK-4511638: {@code 2^60} prints {@code 1.15292150460684698E18}, JavaScript {@code 1152921504606847000}).
     * Rounding the exact binary value to 1, 2, ... 17 significant digits finds the shortest candidate; trying
     * half-even first picks the closest one, and floor and ceiling cover the asymmetric rounding interval of a
     * power of two.
     */
    static String numberToString(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0.0) {
            return "0";
        }
        BigDecimal shortest = shortestDecimal(d);
        BigDecimal magnitude = shortest.abs();
        if (magnitude.compareTo(PLAIN_LOWER_BOUND) >= 0 && magnitude.compareTo(PLAIN_UPPER_BOUND) < 0) {
            return shortest.toPlainString();
        }
        int exponent = shortest.precision() - shortest.scale() - 1;
        String digits = shortest.unscaledValue().abs().toString();
        StringBuilder out = new StringBuilder(digits.length() + 8);
        if (d < 0) {
            out.append('-');
        }
        out.append(digits.charAt(0));
        if (digits.length() > 1) {
            out.append('.').append(digits, 1, digits.length());
        }
        out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        return out.toString();
    }

    private static BigDecimal shortestDecimal(double d) {
        BigDecimal exact = new BigDecimal(d);
        for (int precision = 1; precision <= 17; precision++) {
            for (RoundingMode mode : SHORTEST_DIGIT_MODES) {
                BigDecimal candidate = exact.round(new MathContext(precision, mode));
                if (candidate.doubleValue() == d) {
                    return candidate.stripTrailingZeros();
                }
            }
        }
        // 17 significant digits always identify a double; reaching here would mean the JDK broke that guarantee.
        throw new IllegalStateException("No decimal of at most 17 digits reads back as " + d);
    }

    /**
     * Returns the values {@code for (var i in list)} visits, in visiting order: the values of an object in
     * document order, the elements of an array in index order, the one-character strings of a string root (for-in
     * enumerates a string primitive's indices), and nothing for a number, boolean or {@code null} root.
     */
    private static List<Object> forInValues(JsonNode root) {
        List<Object> values = new ArrayList<>();
        if (root.isContainerNode()) {
            root.elements().forEachRemaining(values::add);
        } else if (root.isTextual()) {
            String s = root.textValue();
            for (int i = 0; i < s.length(); i++) {
                values.add(String.valueOf(s.charAt(i)));
            }
        }
        return values;
    }

    /**
     * Whether {@code +} with this right operand concatenates: a string, or an object or array, whose primitive
     * value is a string.
     */
    private static boolean concatenates(Object value) {
        if (value instanceof String) {
            return true;
        }
        JsonNode node = (JsonNode) value;
        return node.isTextual() || node.isContainerNode();
    }

    /** JavaScript ToNumber (ES5 9.3) of a number, boolean or {@code null} value. */
    private static double toNumber(JsonNode value) {
        return switch (value.getNodeType()) {
            case NUMBER -> value.doubleValue();
            case BOOLEAN -> value.booleanValue() ? 1.0 : 0.0;
            case NULL -> 0.0;
            default -> throw new IllegalArgumentException("Unsupported value type: " + value.getNodeType());
        };
    }

    /**
     * Rejects, after a successful parse, the two forms the parser accepts but JavaScript's
     * {@code eval("(" + text + ")")} does not: a body that ends inside a {@code //} comment, which swallows the
     * closing parenthesis, and a signed {@code INF}, which JavaScript reads as an undefined variable. One pass
     * tracks five lexical states so that quoted text and comments are never mistaken for either form.
     */
    private static void rejectNonJavaScriptSyntax(String text) {
        LexState state = LexState.CODE;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            char next = i + 1 < length ? text.charAt(i + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (c == '"') {
                        state = LexState.DOUBLE_QUOTED;
                    } else if (c == '\'') {
                        state = LexState.SINGLE_QUOTED;
                    } else if (c == '/' && next == '*') {
                        state = LexState.BLOCK_COMMENT;
                        i++;
                    } else if (c == '/' && next == '/') {
                        state = LexState.LINE_COMMENT;
                        i++;
                    } else if ((c == '+' || c == '-') && text.startsWith("INF", i + 1)) {
                        throw new IllegalArgumentException(SIGNED_INF);
                    }
                }
                case DOUBLE_QUOTED, SINGLE_QUOTED -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == (state == LexState.DOUBLE_QUOTED ? '"' : '\'')) {
                        state = LexState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = LexState.CODE;
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
                        state = LexState.CODE;
                    }
                }
            }
        }
        if (state == LexState.LINE_COMMENT) {
            throw new IllegalArgumentException(LINE_COMMENT_AT_END);
        }
    }

    /** Lexical states of {@link #rejectNonJavaScriptSyntax(String)}. */
    private enum LexState {
        CODE, DOUBLE_QUOTED, SINGLE_QUOTED, BLOCK_COMMENT, LINE_COMMENT
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
