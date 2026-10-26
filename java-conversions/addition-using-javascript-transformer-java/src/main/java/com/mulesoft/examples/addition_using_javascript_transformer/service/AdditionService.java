/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>The reference is the engine Mule 3.8.0 runs that script on: Rhino 1.7R4 behind the JSR-223 bridge
 * {@code javax.script:js-engine:1.1:jdk14}, the {@code rhinoVersion} and {@code rhinoJsr223Version} properties of
 * the Mule 3.8.0 pom. No script engine is used and the request body is never evaluated: it is tokenized and parsed
 * as literal data by the rules of Rhino's {@code TokenStream} and {@code Parser}, and the script's {@code for-in}
 * loop and {@code +} operator are reimplemented with Rhino's conversions. Every body that holds only literal data
 * therefore yields the value Rhino yields, and fails where Rhino fails.
 *
 * <p>Literal data read as Rhino reads it:
 * <ul>
 *   <li>the body as {@code eval} sees it, wrapped as {@code "(" + body + ")"}: a trailing {@code //} comment
 *       swallows the closing parenthesis and fails, while {@code {a:1})//} and {@code {a:1});//} succeed;</li>
 *   <li>numbers: decimal, fraction and exponent forms; {@code 0x} hexadecimal ({@code 0x} alone is {@code NaN});
 *       legacy octal ({@code 010} is 8, {@code 08} and {@code 019} read as decimal); {@code NaN}; {@code Infinity};
 *       and any chain of {@code +} and {@code -} signs, applied with JavaScript ToNumber ({@code -"5"} is -5,
 *       {@code +[5]} is 5, {@code +{}} is {@code NaN});</li>
 *   <li>strings in either quote with Rhino's escapes: {@code \b \f \n \r \t \v}, two-digit {@code \x} and
 *       four-digit Unicode escapes (a malformed one kept as its letter and the characters after it), octal escapes
 *       up to {@code \377}, and line continuations;</li>
 *   <li>{@code true}, {@code false}, {@code null} and {@code undefined};</li>
 *   <li>arrays with holes and a trailing comma: {@code [1,,2]} has length 3 and joins as {@code 1,,2};</li>
 *   <li>objects keyed by identifiers (Unicode escapes included), keywords, strings or numbers, where a number key
 *       is canonicalized ({@code 0x10} and {@code 16} name one property) and a duplicate key keeps its first
 *       position and its last value; {@code __proto__} sets the prototype (an object, an array, a primitive
 *       wrapper, or none for {@code null} and {@code undefined}) and {@code __parent__} creates no property;</li>
 *   <li>whitespace, {@code //}, block, {@code <!--} and line-initial {@code -->} comments, and Unicode format
 *       characters, which are ignored outside strings.</li>
 * </ul>
 *
 * <p>Semantics: {@code for-in} visits an object's own properties in insertion order, then those of each prototype
 * not yet seen, and an array's present indices in ascending order. A string, number, boolean, {@code null} or
 * {@code undefined} root visits nothing, so the sum stays {@code 0.0}; a root whose prototype chain holds an
 * {@code __iterator__} property fails as Rhino's iterator check does. {@code +} adds two numbers; otherwise an
 * object operand is converted by ToPrimitive (Rhino's {@code getDefaultValue}: {@code valueOf}, then
 * {@code toString}, each the first property of that name on the prototype chain, a non-callable data property
 * being skipped), and once either side is a string the operands concatenate. {@code {"a":{"toString":1}}}
 * therefore fails with {@code TypeError: Cannot find default value for object.}, as the original did. The result
 * keeps Rhino's types: a numeric sum is a {@link Double} rendered by MEL with {@link Double#toString(double)}
 * ({@code Sum is: 3.0.}), and a concatenated sum is the JavaScript string.
 *
 * <p>The one intentional difference: input that is JavaScript code rather than literal data fails with
 * {@link IllegalArgumentException} where the original evaluated it, because no script engine may run request input.
 * Code means identifier references other than {@code undefined}, {@code NaN} and {@code Infinity}, function, getter
 * and setter literals, regular expressions, E4X, grouping, operators other than leading signs, member access,
 * calls, the comma operator and further statements. Input Rhino itself rejects fails with a {@code SyntaxError:}
 * message, and a failed conversion with Rhino's {@code TypeError:} text. The flow, having no exception strategy,
 * answers every failure with the platform 500.
 *
 * <p>A body that is empty or holds only whitespace and comments fails with {@value #NO_CONTENT}.
 *
 * <p>Resource bounds: arrays and objects nested more than {@value #MAX_NESTING_DEPTH} levels deep fail (Rhino's own
 * limit is a parser recursion error whose depth depends on the thread's stack, a few hundred levels on a 1 MB
 * stack). Strings are bounded only as the original's were, by the longest string Java holds: a join, a
 * concatenation or a response longer than {@value #MAX_STRING_LENGTH} characters fails, and a join whose commas
 * alone exceed that fails before it appends any. Below that bound every string is built in full, as Rhino builds it:
 * {@code Array.prototype.toString} of an object that inherits it from an array prototype and declares its own
 * {@code length} joins that many elements, up to 2^32 - 1. Parsing, signs and joins keep nested work on explicit
 * stacks, so the accepted depth needs no deep thread stack. Work other than joins is linear in the length of the
 * body.
 *
 * <p>Tier 2A verification points: {@code Sum is: 0.0.} for an empty object, an empty array and a primitive root,
 * which the loop does not iterate; and the 500 body of a failure, whose message is this class's rather than
 * Rhino's.
 */
@Service
public class AdditionService {

    private static final Logger log = LoggerFactory.getLogger(AdditionService.class);

    static final String NO_CONTENT = "No content to map due to end-of-input";

    /** Deepest nesting of arrays and objects a body may use. */
    static final int MAX_NESTING_DEPTH = 1000;

    /**
     * Longest string the evaluation builds: the largest array the JDK's string builders allocate on every VM
     * ({@code ArraysSupport.SOFT_MAX_ARRAY_LENGTH}, and {@code AbstractStringBuilder.MAX_ARRAY_SIZE} on JDK 8, which
     * Mule 3.8.0 runs on). Rhino's joins and concatenations and MEL's {@code Sum is: #[payload].} build their strings
     * in such builders, the original's only bound on them.
     */
    static final int MAX_STRING_LENGTH = Integer.MAX_VALUE - 8;

    /** Rhino 1.7R4's keywords and reserved words, as {@code TokenStream.stringToKeyword} lists them. */
    private static final Set<String> KEYWORDS = Set.of(
            "break", "case", "continue", "default", "delete", "do", "else", "export", "false", "for", "function",
            "if", "in", "let", "new", "null", "return", "switch", "this", "true", "typeof", "var", "void", "while",
            "with", "yield", "abstract", "boolean", "byte", "catch", "char", "class", "const", "debugger", "double",
            "enum", "extends", "final", "finally", "float", "goto", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "package", "private", "protected", "public", "short", "static", "super",
            "synchronized", "throw", "throws", "transient", "try", "volatile");

    /** Punctuators that can begin an expression, so meeting one where a literal is expected means code. */
    private static final Set<String> EXPRESSION_STARTS = Set.of(
            "(", "[", "{", "+", "-", "!", "~", "++", "--", "/", "/=", "<", "@");

    /** Punctuators that continue an expression, so meeting one after a complete literal means code. */
    private static final Set<String> EXPRESSION_CONTINUATIONS = Set.of(
            ".", "..", ".(", "::", "[", "(", "?", "=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", ">>>=", "&=", "|=",
            "^=", "||", "&&", "|", "^", "&", "==", "!=", "===", "!==", "<", "<=", ">", ">=", "<<", ">>", ">>>", "+",
            "-", "*", "/", "%");

    private static final RoundingMode[] SHORTEST_DIGIT_MODES = {
        RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING
    };

    private static final BigDecimal PLAIN_LOWER_BOUND = new BigDecimal("1e-6");

    private static final BigDecimal PLAIN_UPPER_BOUND = new BigDecimal("1e21");

    /** The property name Mule 3.8's {@code PropertiesUtils} registers for masking when it renders a map. */
    private static final String MASKED_PARAMETER = "password";

    /** The text Mule 3.8's {@code PropertiesUtils} writes in place of a masked value. */
    private static final String MASKED_VALUE = "*****";

    /**
     * Runs the flow from the request body to the response payload.
     *
     * @param body the request body bytes; {@code null} is read as an empty body
     * @param cs the request charset; {@code null} means the application encoding, UTF-8
     * @return {@code Sum is: <sum>.}
     * @throws IllegalArgumentException when the body is empty, is JavaScript code rather than literal data, or fails
     *         as the script failed, see {@link #sum(String)}; or when the response would be longer than
     *         {@link #MAX_STRING_LENGTH}
     */
    public String handle(byte[] body, Charset cs) {
        Charset charset = cs == null ? StandardCharsets.UTF_8 : cs;
        String text = body == null ? "" : new String(body, charset);
        return evaluate(text);
    }

    /**
     * Runs the flow from the listener's parameter-map payload to the response payload. The Mule 3.8 listener made a
     * non-empty {@code application/x-www-form-urlencoded} body a parameter map rather than a stream, and line 6's
     * {@code byte-array-to-string-transformer}, which Mule's namespace handler implements with {@code ObjectToString},
     * turned that map into the text {@link #renderParameterMap(Map)} reproduces; the script then evaluates that text
     * like any other. A rendering such as {@code {a=1}} is not a JavaScript literal and fails with Rhino's
     * {@code SyntaxError}, while an empty map renders as {@code {}} and sums to {@code 0.0}.
     *
     * @param parameters the decoded form parameters in first-occurrence order, each name with its last value or
     *                   {@code null}; {@code null} is read as an empty map
     * @return {@code Sum is: <sum>.}
     * @throws IllegalArgumentException when the rendering fails as the script failed, see {@link #sum(String)}; or
     *         when the rendering or the response would be longer than {@link #MAX_STRING_LENGTH}
     */
    public String handle(Map<String, String> parameters) {
        return evaluate(renderParameterMap(parameters));
    }

    /**
     * Lines 7-15 for the payload text: the script's sum, MEL's rendering of it, the logger and the response payload.
     */
    private String evaluate(String text) {
        Object result = sum(text);
        String rendered = render(result);
        // The response is the longest string the flow builds, so checking it first keeps the logged line in bounds.
        requireStringLength("Sum is: ".length() + (long) rendered.length() + ".".length());
        log.info("Sum is: {}", rendered);
        return "Sum is: " + rendered + ".";
    }

    /**
     * Renders a parameter map as Mule 3.8's {@code ObjectToString} rendered the listener's {@code ParameterMap},
     * through {@code StringMessageUtils.toString} and {@code MapUtils.toString(map, false)}:
     * <ul>
     *   <li>an empty map is {@code {}};</li>
     *   <li>otherwise the entries are visited in the order of the {@link HashSet} of
     *       {@link AbstractMap.SimpleEntry} that {@code ParameterMap.entrySet()} built by adding one entry per name,
     *       in the map's name order, so the order is the set's hash order rather than the body's;</li>
     *   <li>each entry is written {@code name=value}, the value being {@code null} when absent and {@code *****} for
     *       the name {@code password}, the one name {@code PropertiesUtils} masks by default;</li>
     *   <li>entries are separated by {@code ", "} and the whole is enclosed in braces.</li>
     * </ul>
     *
     * @param parameters the parameter map; {@code null} renders as {@code {}}, as {@code MapUtils} rendered it
     * @return the rendering
     * @throws IllegalArgumentException when the rendering would be longer than {@link #MAX_STRING_LENGTH}
     */
    static String renderParameterMap(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        Set<Map.Entry<String, String>> entries = new HashSet<>();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(parameter.getKey(), parameter.getValue()));
        }
        StringBuilder rendering = new StringBuilder("{");
        long length = rendering.length() + "}".length();
        String separator = "";
        for (Map.Entry<String, String> entry : entries) {
            String text = separator + entry.getKey() + "=" + maskedValue(entry);
            length += text.length();
            requireStringLength(length);
            rendering.append(text);
            separator = ", ";
        }
        return rendering.append('}').toString();
    }

    /**
     * Mule's {@code PropertiesUtils.maskedPropertyValue}: the text {@code null} for an absent value, whatever the name;
     * {@code *****} for the masked name {@code password}; otherwise the value.
     */
    private static String maskedValue(Map.Entry<String, String> entry) {
        if (entry.getKey() == null || entry.getValue() == null) {
            return "null";
        }
        if (MASKED_PARAMETER.equals(entry.getKey())) {
            return MASKED_VALUE;
        }
        return entry.getValue();
    }

    /**
     * Fails when a string of {@code length} characters would be longer than {@link #MAX_STRING_LENGTH}, which the
     * original could not build either.
     *
     * @throws IllegalArgumentException when {@code length} exceeds {@link #MAX_STRING_LENGTH}
     */
    private static void requireStringLength(long length) {
        if (length > MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Input builds a string longer than " + MAX_STRING_LENGTH
                    + " characters, more than a Java string can hold, so the original failed as well");
        }
    }

    /**
     * Sums the values of {@code text} as the original script's {@code for-in} loop does.
     *
     * @param text the request body as a string
     * @return a {@link Double} when every value added converted to a number, otherwise the concatenated
     *         {@link String}
     * @throws IllegalArgumentException when {@code text} is blank, is JavaScript code rather than literal data,
     *         exceeds a resource bound, or fails as the script failed (Rhino's {@code SyntaxError} or
     *         {@code TypeError})
     */
    public Object sum(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(NO_CONTENT);
        }
        LiteralParser parser = new LiteralParser(text);
        Object literal = parser.parse();
        ScriptSemantics semantics = new ScriptSemantics();
        return semantics.forInSum(semantics.applySigns(literal, parser.pendingSigns));
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
     * Converts a value to its JavaScript string form: ES5 9.8, with Rhino's ToPrimitive applied to objects and
     * arrays.
     *
     * <p>Besides the values the parser produces, the plain Java forms of JavaScript values are accepted:
     * {@code null}, {@link String}, {@link Number}, {@link Boolean}, an {@link Iterable} as an array (a {@code null}
     * element joining as the empty string) and a {@link Map} as an object whose entries are its own data properties,
     * so that a {@code toString} entry shadows the inherited method with a value that cannot be called.
     *
     * @param o the value
     * @return the string {@code String(o)} would produce in JavaScript
     * @throws IllegalArgumentException for any other type, and with Rhino's {@code TypeError} text when the
     *         conversion fails, as it does for a map holding a {@code toString} entry
     */
    static String jsToString(Object o) {
        return new ScriptSemantics().toStringValue(toJsValue(o));
    }

    /**
     * Maps the Java forms {@link #jsToString(Object)} accepts onto the JavaScript values this class models; the
     * modelled values themselves pass unchanged.
     */
    private static Object toJsValue(Object o) {
        if (o == null || o instanceof String || o instanceof Boolean || o instanceof Double
                || o instanceof JsObject || o instanceof JsArray || o == Special.UNDEFINED) {
            return o;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof Iterable<?> iterable) {
            JsArray array = new JsArray();
            for (Object element : iterable) {
                array.elements.add(toJsValue(element));
            }
            return array;
        }
        if (o instanceof Map<?, ?> map) {
            JsObject object = new JsObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.properties.put(propertyKey(entry.getKey()), toJsValue(entry.getValue()));
            }
            return object;
        }
        throw new IllegalArgumentException("Unsupported value type: " + o.getClass().getName());
    }

    /** The property name a Java map key stands for: ToString of a number, {@link String#valueOf(Object)} otherwise. */
    private static String propertyKey(Object key) {
        return key instanceof Number n ? numberToString(n.doubleValue()) : String.valueOf(key);
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
     * Rhino 1.7R4's ToNumber of a string ({@code ScriptRuntime.toNumber(String)}): surrounding whitespace is
     * ignored and leaves 0 when nothing else remains; {@code 0x}, {@code +0x} and {@code -0x} prefix hexadecimal
     * digits read up to the first other character; {@code Infinity} may carry a sign; anything else is a decimal
     * number made only of digits, {@code .}, {@code e}, {@code E}, {@code +} and {@code -}, read by
     * {@link Double#parseDouble(String)}, or {@code NaN}.
     */
    private static double stringToNumber(String s) {
        int length = s.length();
        int start = 0;
        while (start < length && isStrWhiteSpace(s.charAt(start))) {
            start++;
        }
        if (start == length) {
            return 0.0;
        }
        char startChar = s.charAt(start);
        if (startChar == '0' && start + 2 < length && isHexPrefix(s.charAt(start + 1))) {
            return hexPrefixToDouble(s, start + 2);
        }
        if ((startChar == '+' || startChar == '-') && start + 3 < length && s.charAt(start + 1) == '0'
                && isHexPrefix(s.charAt(start + 2))) {
            double value = hexPrefixToDouble(s, start + 3);
            return startChar == '-' ? -value : value;
        }
        int end = length - 1;
        while (isStrWhiteSpace(s.charAt(end))) {
            end--;
        }
        if (s.charAt(end) == 'y') {
            int nameStart = startChar == '+' || startChar == '-' ? start + 1 : start;
            if (nameStart + 7 == end && s.startsWith("Infinity", nameStart)) {
                return startChar == '-' ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            return Double.NaN;
        }
        String decimal = s.substring(start, end + 1);
        for (int i = 0; i < decimal.length(); i++) {
            char c = decimal.charAt(i);
            if (!(c >= '0' && c <= '9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                return Double.NaN;
            }
        }
        try {
            return Double.parseDouble(decimal);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Whether {@code c} is the {@code x} of a {@code 0x} prefix. */
    private static boolean isHexPrefix(char c) {
        return c == 'x' || c == 'X';
    }

    /** The hexadecimal digits of {@code s} from {@code from} up to the first other character, as a number. */
    private static double hexPrefixToDouble(String s, int from) {
        int end = from;
        while (end < s.length() && hexDigit(s.charAt(end), 0) >= 0) {
            end++;
        }
        return radixToDouble(s, from, end, 16);
    }

    /**
     * The double nearest to the base-8 or base-16 integer in {@code digits.substring(from, to)}, ties to even, which
     * is what Rhino's {@code ScriptRuntime.stringToNumber} computes; {@code NaN} when there are no digits. Leading
     * zeros are skipped, and more significant digits than a finite double can hold (2^1024 needs 257 hexadecimal
     * or 343 octal digits) give infinity at once, so a long literal costs linear time.
     */
    private static double radixToDouble(String digits, int from, int to, int radix) {
        if (from == to) {
            return Double.NaN;
        }
        int first = from;
        while (first < to && digits.charAt(first) == '0') {
            first++;
        }
        if (first == to) {
            return 0.0;
        }
        int finiteDigits = radix == 16 ? 256 : 342;
        if (to - first > finiteDigits) {
            return Double.POSITIVE_INFINITY;
        }
        return new BigInteger(digits.substring(first, to), radix).doubleValue();
    }

    /** Rhino's {@code ScriptRuntime.isStrWhiteSpaceChar}: the whitespace ToNumber trims from a string. */
    private static boolean isStrWhiteSpace(int c) {
        return switch (c) {
            case ' ', '\n', '\r', '\t', 0xA0, '\f', 0x0B, 0x2028, 0x2029, 0xFEFF -> true;
            default -> Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }

    /**
     * Rhino's {@code Kit.xDigitToInt}: {@code accumulator} shifted left by four bits plus the value of the
     * hexadecimal digit {@code c}, or -1 when {@code c} is not one.
     */
    private static int hexDigit(int c, int accumulator) {
        int value;
        if (c >= '0' && c <= '9') {
            value = c - '0';
        } else if (c >= 'A' && c <= 'F') {
            value = c - 'A' + 10;
        } else if (c >= 'a' && c <= 'f') {
            value = c - 'a' + 10;
        } else {
            return -1;
        }
        return accumulator << 4 | value;
    }

    /** JavaScript values that have no Java counterpart. */
    private enum Special {
        /** The {@code undefined} value. */
        UNDEFINED,
        /** An elided array element: {@code for-in} skips it and {@code Array.prototype.toString} joins it as "". */
        HOLE
    }

    /** The prototypes an object can end its chain with, besides an object or an array set by {@code __proto__}. */
    private enum Proto {
        /** {@code Object.prototype}, the prototype of every object literal. */
        OBJECT_PROTOTYPE,
        /** No prototype, after {@code __proto__: null} or {@code __proto__: undefined}. */
        NONE,
        /**
         * A Number, String or Boolean wrapper, after {@code __proto__} with a primitive: the three enumerate nothing
         * and their {@code valueOf} and {@code toString} reject any other {@code this}, so they act alike here.
         */
        PRIMITIVE_WRAPPER
    }

    /** What the first property of a method's name on a prototype chain turns out to be. */
    private enum Method {
        /** A data property, which cannot be called and is skipped. */
        DATA,
        /** No property of that name. */
        ABSENT,
        /** {@code Object.prototype.valueOf}, which returns the object itself, so no primitive. */
        OBJECT_VALUE_OF,
        /** {@code Object.prototype.toString}: {@code [object Object]}. */
        OBJECT_TO_STRING,
        /** {@code Array.prototype.toString}: the elements of {@code this}, joined with commas. */
        ARRAY_TO_STRING,
        /** A Number, String or Boolean prototype method, which throws for a {@code this} that is not its wrapper. */
        WRAPPER_METHOD
    }

    /** A JavaScript array: its elements, {@link Special#HOLE} marking the elided ones; its length is their count. */
    private static final class JsArray {

        /** The elements, holes included, appended as the literal parses. */
        final List<Object> elements = new ArrayList<>();

        /** Element {@code index} as {@code Get} reads it: a hole, or an index past the end, reads as undefined. */
        Object element(long index) {
            if (index < elements.size() && elements.get((int) index) != Special.HOLE) {
                return elements.get((int) index);
            }
            return Special.UNDEFINED;
        }
    }

    /** A JavaScript object: its own data properties in insertion order, and its prototype. */
    private static final class JsObject {

        /** Own properties by canonical name; a property keeps its first position when a later key repeats it. */
        final Map<String, Object> properties = new LinkedHashMap<>();

        /** A {@link JsObject}, a {@link JsArray} or a {@link Proto}. */
        Object prototype = Proto.OBJECT_PROTOTYPE;
    }

    /**
     * A literal preceded by {@code +} and {@code -} signs. It stands in its slot until ToNumber of its operand runs,
     * once the whole body has parsed, because Rhino reports every syntax error before it executes anything.
     */
    private static final class PendingSign {

        /** Whether the signs negate: an odd count of {@code -}. */
        final boolean negate;

        /** The literal the signs apply to. */
        final Object operand;

        /** The array or object whose slot this stands in, or {@code null} when nothing holds it. */
        private Object holder;

        /** The slot's index when {@link #holder} is an array. */
        private int index;

        /** The slot's property name when {@link #holder} is an object. */
        private String key;

        /** The number the signs produced, once applied. */
        Double result;

        /**
         * @param negate whether the signs negate
         * @param operand the literal they apply to
         */
        PendingSign(boolean negate, Object operand) {
            this.negate = negate;
            this.operand = operand;
        }

        /** Records that element {@code index} of {@code array} holds this. */
        void heldBy(JsArray array, int index) {
            this.holder = array;
            this.index = index;
        }

        /** Records that property {@code key} of {@code object} holds this. */
        void heldBy(JsObject object, String key) {
            this.holder = object;
            this.key = key;
        }

        /** Takes the number the signs produced and puts it in the slot, unless a repeated key took the slot. */
        void apply(double number) {
            result = number;
            if (holder instanceof JsArray array) {
                array.elements.set(index, result);
            } else if (holder instanceof JsObject object && object.properties.get(key) == this) {
                object.properties.put(key, result);
            }
        }
    }

    /** The kinds of token the {@link Lexer} produces. */
    private enum TokenType {
        /** The end of the source. */
        EOF,
        /** An identifier, keyword or reserved word; {@link Lexer#text} holds the name Rhino gives it. */
        NAME,
        /** A string literal; {@link Lexer#text} holds its value. */
        STRING,
        /** A numeric literal; {@link Lexer#number} holds its value. */
        NUMBER,
        /** An operator or punctuator; {@link Lexer#text} holds its characters. */
        PUNCTUATOR
    }

    /**
     * Rhino 1.7R4's {@code TokenStream} over {@code "(" + body + ")"}, the source {@code eval} reads, for every token
     * a literal can contain and every punctuator, so that whatever is not literal data ends at the token Rhino
     * would end it at. Regular expression and XML bodies, which only code reaches, are not scanned.
     *
     * <p>Line terminators ({@code \r}, {@code \r\n}, U+2028, U+2029) read as {@code \n}. Unicode format characters
     * (category Cf) are skipped, except inside string bodies, where only the character after a backslash and the
     * digits of an escape are read with that skipping; the byte order mark is whitespace instead.
     */
    private static final class Lexer {

        private static final int EOF_CHAR = -1;

        private static final int BYTE_ORDER_MARK = 0xFEFF;

        private static final int LINE_SEPARATOR = 0x2028;

        private static final int PARAGRAPH_SEPARATOR = 0x2029;

        /** Rhino's octal escapes take a third digit only while the value stays at most 0377. */
        private static final int LARGEST_TWO_DIGIT_PREFIX = 31;

        private final String source;

        /** Index in {@link #source} of the next character not yet read. */
        private int position;

        /** Characters read and then pushed back, the last pushed on top, with their indices in {@link #source}. */
        private final int[] pushedChars = new int[4];

        private final int[] pushedStarts = new int[4];

        private int pushed;

        /** Index in {@link #source} of the character read last. */
        private int charStart;

        /** Whether the current line holds a token, which makes {@code -->} an operator rather than a comment. */
        private boolean dirtyLine;

        /**
         * Whether a line terminator separates the current token from the previous one, which ends a statement and
         * keeps {@code ++} and {@code --} from applying to the literal before them.
         */
        boolean newlineBefore;

        /** The current token's kind. */
        TokenType type;

        /** The current token's name, string value or punctuator. */
        String text = "";

        /** The current token's value when it is a number. */
        double number;

        /** Index in {@link #source} where the current token starts. */
        int start;

        /**
         * Rhino's {@code TokenStream.string}: the text of the last name, string or number scanned, which outlives
         * the tokens after it.
         */
        String lastString = "";

        /** @param source the text {@code eval} parses, the body inside parentheses */
        Lexer(String source) {
            this.source = source;
        }

        /** Index in {@link #source} of the closing parenthesis {@code eval} appends. */
        int appendedParenthesis() {
            return source.length() - 1;
        }

        /** Whether the current token is the punctuator {@code p}. */
        boolean isPunctuator(String p) {
            return type == TokenType.PUNCTUATOR && text.equals(p);
        }

        /** Whether the current token can begin an expression, which a literal is not. */
        boolean startsExpression() {
            return type == TokenType.NAME || type == TokenType.STRING || type == TokenType.NUMBER
                    || type == TokenType.PUNCTUATOR && EXPRESSION_STARTS.contains(text);
        }

        /** Whether the current token continues the expression before it: an operator, member access or call. */
        boolean continuesExpression() {
            return type == TokenType.PUNCTUATOR && EXPRESSION_CONTINUATIONS.contains(text)
                    || type == TokenType.NAME && (text.equals("in") || text.equals("instanceof"));
        }

        /**
         * The failure for the current token, which follows a complete literal where it does not belong: Rhino's
         * {@code rhinoMessage}, its increment error for {@code ++} or {@code --} on the literal's line, or the code
         * failure when the token continues an expression.
         */
        IllegalArgumentException afterLiteral(String rhinoMessage) {
            if ((isPunctuator("++") || isPunctuator("--")) && !newlineBefore) {
                return syntaxError(isPunctuator("++") ? "Invalid increment operand." : "Invalid decrement operand.");
            }
            if (continuesExpression()) {
                return unsupported(start, construct(true));
            }
            return syntaxError(rhinoMessage);
        }

        /**
         * The code construct the current token begins or continues, for an error message.
         *
         * @param afterOperand whether the token follows an operand, which makes {@code /} and {@code <} operators
         *        rather than the start of a regular expression or XML literal
         */
        String construct(boolean afterOperand) {
            if (type == TokenType.NAME) {
                return (KEYWORDS.contains(text) ? "the keyword '" : "the identifier reference '") + text + "'";
            }
            if (type != TokenType.PUNCTUATOR) {
                return "a further expression";
            }
            if (afterOperand && !text.equals("(")) {
                return "the operator '" + text + "'";
            }
            return switch (text) {
                case "(" -> "a call or parenthesized expression";
                case "/", "/=" -> "a regular expression literal";
                case "<" -> "an XML literal";
                case "@" -> "an XML attribute selector";
                default -> "the operator '" + text + "'";
            };
        }

        /** Offset in the request body of index {@code at} of {@link #source}. */
        int offset(int at) {
            return Math.max(0, Math.min(at - 1, source.length() - 2));
        }

        /** A failure Rhino reports as {@code SyntaxError: <message>}, located at the current token. */
        IllegalArgumentException syntaxError(String message) {
            return new IllegalArgumentException("SyntaxError: " + message + " (at offset " + offset(start) + ")");
        }

        /** A failure for JavaScript code, which this class never evaluates, located at index {@code at}. */
        IllegalArgumentException unsupported(int at, String construct) {
            return new IllegalArgumentException("Unsupported input at offset " + offset(at) + ": " + construct
                    + " is JavaScript code, and the request body is read as literal data, never evaluated");
        }

        /** Scans the next token, skipping whitespace, line terminators and comments. */
        void next() {
            newlineBefore = false;
            for (;;) {
                int c;
                for (;;) {
                    c = read(true);
                    if (c == EOF_CHAR) {
                        start = charStart;
                        type = TokenType.EOF;
                        text = "";
                        return;
                    }
                    if (c == '\n') {
                        dirtyLine = false;
                        newlineBefore = true;
                    } else if (!isJsSpace(c)) {
                        if (c != '-') {
                            dirtyLine = true;
                        }
                        break;
                    }
                }
                start = charStart;
                if (c == '@') {
                    punctuator("@");
                    return;
                }
                boolean escapedStart = false;
                boolean identifierStart;
                if (c == '\\') {
                    c = read(true);
                    if (c == 'u') {
                        identifierStart = true;
                        escapedStart = true;
                    } else {
                        identifierStart = false;
                        unread(c);
                        c = '\\';
                    }
                } else {
                    identifierStart = Character.isJavaIdentifierStart((char) c);
                }
                if (identifierStart) {
                    name(c, escapedStart);
                    return;
                }
                if (isDigit(c) || c == '.' && isDigit(peek())) {
                    number(c);
                    return;
                }
                if (c == '"' || c == '\'') {
                    string(c);
                    return;
                }
                if (punctuatorOrComment(c)) {
                    return;
                }
            }
        }

        /** Scans an identifier whose first character, or the backslash of its first escape, has been read. */
        private void name(int first, boolean escapedStart) {
            StringBuilder name = new StringBuilder();
            if (!escapedStart) {
                name.append((char) first);
            }
            boolean containsEscape = escapedStart;
            boolean pendingEscape = escapedStart;
            for (;;) {
                if (pendingEscape) {
                    int value = 0;
                    for (int i = 0; i != 4; ++i) {
                        value = hexDigit(read(true), value);
                        if (value < 0) {
                            throw syntaxError("invalid Unicode escape sequence");
                        }
                    }
                    name.append((char) value);
                    pendingEscape = false;
                    continue;
                }
                int c = read(true);
                if (c == '\\') {
                    if (read(true) != 'u') {
                        throw syntaxError("illegal character");
                    }
                    pendingEscape = true;
                    containsEscape = true;
                } else if (c == EOF_CHAR || c == BYTE_ORDER_MARK || !Character.isJavaIdentifierPart((char) c)) {
                    unread(c);
                    break;
                } else {
                    name.append((char) c);
                }
            }
            String word = name.toString();
            if (containsEscape && KEYWORDS.contains(word)) {
                // An escaped spelling of a keyword is a plain name with its last character written back as an escape.
                int last = word.length() - 1;
                word = word.substring(0, last) + String.format("\\u%04x", (int) word.charAt(last));
            }
            type = TokenType.NAME;
            text = word;
            lastString = word;
        }

        /** Scans a number whose first character has been read: Rhino's decimal, legacy octal and hexadecimal forms. */
        private void number(int first) {
            StringBuilder digits = new StringBuilder();
            int base = 10;
            int c = first;
            if (c == '0') {
                c = read(true);
                if (c == 'x' || c == 'X') {
                    base = 16;
                    c = read(true);
                } else if (isDigit(c)) {
                    base = 8;
                } else {
                    digits.append('0');
                }
            }
            if (base == 16) {
                while (hexDigit(c, 0) >= 0) {
                    digits.append((char) c);
                    c = read(true);
                }
            } else {
                while (isDigit(c)) {
                    if (base == 8 && c >= '8') {
                        base = 10;
                    }
                    digits.append((char) c);
                    c = read(true);
                }
            }
            boolean isInteger = true;
            if (base == 10 && (c == '.' || c == 'e' || c == 'E')) {
                isInteger = false;
                if (c == '.') {
                    do {
                        digits.append((char) c);
                        c = read(true);
                    } while (isDigit(c));
                }
                if (c == 'e' || c == 'E') {
                    digits.append((char) c);
                    c = read(true);
                    if (c == '+' || c == '-') {
                        digits.append((char) c);
                        c = read(true);
                    }
                    if (!isDigit(c)) {
                        throw syntaxError("missing exponent");
                    }
                    do {
                        digits.append((char) c);
                        c = read(true);
                    } while (isDigit(c));
                }
            }
            unread(c);
            String numString = digits.toString();
            type = TokenType.NUMBER;
            text = numString;
            lastString = numString;
            number = base == 10 ? Double.parseDouble(numString) : radixToDouble(numString, 0, numString.length(), base);
        }

        /** Scans a string literal whose opening quote has been read. */
        private void string(int quote) {
            StringBuilder value = new StringBuilder();
            int c = read(false);
            scan:
            while (c != quote) {
                if (c == '\n' || c == EOF_CHAR) {
                    throw syntaxError("unterminated string literal");
                }
                if (c == '\\') {
                    c = read(true);
                    switch (c) {
                        case 'b':
                            c = '\b';
                            break;
                        case 'f':
                            c = '\f';
                            break;
                        case 'n':
                            c = '\n';
                            break;
                        case 'r':
                            c = '\r';
                            break;
                        case 't':
                            c = '\t';
                            break;
                        case 'v':
                            c = 0x0B;
                            break;
                        case 'u': {
                            // Without four hex digits the escape is its letter and the digits read, then the rest.
                            int escapeStart = value.length();
                            value.append('u');
                            int escaped = 0;
                            for (int i = 0; i != 4; ++i) {
                                c = read(true);
                                escaped = hexDigit(c, escaped);
                                if (escaped < 0) {
                                    continue scan;
                                }
                                value.append((char) c);
                            }
                            value.setLength(escapeStart);
                            c = escaped;
                            break;
                        }
                        case 'x': {
                            c = read(true);
                            int high = hexDigit(c, 0);
                            if (high < 0) {
                                value.append('x');
                                continue scan;
                            }
                            int firstDigit = c;
                            c = read(true);
                            int escaped = hexDigit(c, high);
                            if (escaped < 0) {
                                value.append('x').append((char) firstDigit);
                                continue scan;
                            }
                            c = escaped;
                            break;
                        }
                        case '\n':
                            // A line continuation: the backslash and the line terminator vanish.
                            c = read(true);
                            continue scan;
                        default:
                            if (c >= '0' && c < '8') {
                                int octal = c - '0';
                                c = read(true);
                                if (c >= '0' && c < '8') {
                                    octal = 8 * octal + c - '0';
                                    c = read(true);
                                    if (c >= '0' && c < '8' && octal <= LARGEST_TWO_DIGIT_PREFIX) {
                                        octal = 8 * octal + c - '0';
                                        c = read(true);
                                    }
                                }
                                unread(c);
                                c = octal;
                            }
                            break;
                    }
                }
                value.append((char) c);
                c = read(false);
            }
            type = TokenType.STRING;
            text = value.toString();
            lastString = text;
        }

        /**
         * Scans the punctuator starting with {@code c}, or skips the comment it starts.
         *
         * @return {@code false} when a comment was skipped and no token produced
         */
        private boolean punctuatorOrComment(int c) {
            switch (c) {
                case ';', '[', ']', '{', '}', '(', ')', ',', '?', '~' -> punctuator(String.valueOf((char) c));
                case ':' -> punctuator(matchChar(':') ? "::" : ":");
                case '.' -> punctuator(matchChar('.') ? ".." : matchChar('(') ? ".(" : ".");
                case '|' -> punctuator(matchChar('|') ? "||" : matchChar('=') ? "|=" : "|");
                case '^' -> punctuator(matchChar('=') ? "^=" : "^");
                case '&' -> punctuator(matchChar('&') ? "&&" : matchChar('=') ? "&=" : "&");
                case '=' -> punctuator(matchChar('=') ? matchChar('=') ? "===" : "==" : "=");
                case '!' -> punctuator(matchChar('=') ? matchChar('=') ? "!==" : "!=" : "!");
                case '*' -> punctuator(matchChar('=') ? "*=" : "*");
                case '%' -> punctuator(matchChar('=') ? "%=" : "%");
                case '+' -> punctuator(matchChar('=') ? "+=" : matchChar('+') ? "++" : "+");
                case '>' -> {
                    if (matchChar('>')) {
                        if (matchChar('>')) {
                            punctuator(matchChar('=') ? ">>>=" : ">>>");
                        } else {
                            punctuator(matchChar('=') ? ">>=" : ">>");
                        }
                    } else {
                        punctuator(matchChar('=') ? ">=" : ">");
                    }
                }
                case '<' -> {
                    // <!-- starts a comment to the end of the line, anywhere.
                    if (matchChar('!')) {
                        int bangStart = charStart;
                        if (matchChar('-')) {
                            int dashStart = charStart;
                            if (matchChar('-')) {
                                skipLine();
                                return false;
                            }
                            unread('-', dashStart);
                        }
                        unread('!', bangStart);
                    }
                    if (matchChar('<')) {
                        punctuator(matchChar('=') ? "<<=" : "<<");
                    } else {
                        punctuator(matchChar('=') ? "<=" : "<");
                    }
                }
                case '/' -> {
                    if (matchChar('/')) {
                        skipLine();
                        return false;
                    }
                    if (matchChar('*')) {
                        boolean lookForSlash = matchChar('*');
                        for (;;) {
                            int d = read(true);
                            if (d == EOF_CHAR) {
                                throw syntaxError("unterminated comment");
                            } else if (d == '*') {
                                lookForSlash = true;
                            } else if (d == '/') {
                                if (lookForSlash) {
                                    return false;
                                }
                            } else {
                                lookForSlash = false;
                            }
                        }
                    }
                    punctuator(matchChar('=') ? "/=" : "/");
                }
                case '-' -> {
                    String op;
                    if (matchChar('=')) {
                        op = "-=";
                    } else if (matchChar('-')) {
                        // --> starts a comment to the end of the line only before any token on that line.
                        if (!dirtyLine && matchChar('>')) {
                            skipLine();
                            return false;
                        }
                        op = "--";
                    } else {
                        op = "-";
                    }
                    dirtyLine = true;
                    punctuator(op);
                }
                default -> throw syntaxError("illegal character");
            }
            return true;
        }

        /** Makes the current token the punctuator {@code op}. */
        private void punctuator(String op) {
            type = TokenType.PUNCTUATOR;
            text = op;
        }

        /** Skips to the end of the line, leaving the line terminator to be read. */
        private void skipLine() {
            int c;
            do {
                c = read(true);
            } while (c != EOF_CHAR && c != '\n');
            unread(c);
        }

        /** Reads the next character if it is {@code expected}. */
        private boolean matchChar(int expected) {
            int c = read(true);
            if (c == expected) {
                return true;
            }
            unread(c);
            return false;
        }

        /** The next character, left unread. */
        private int peek() {
            int c = read(true);
            unread(c);
            return c;
        }

        /**
         * Rhino's {@code getChar}: the next character, a line terminator as {@code \n}, format characters skipped
         * when {@code skipFormat} is set; {@link #EOF_CHAR} at the end.
         */
        private int read(boolean skipFormat) {
            if (pushed > 0) {
                pushed--;
                charStart = pushedStarts[pushed];
                return pushedChars[pushed];
            }
            for (;;) {
                charStart = position;
                if (position == source.length()) {
                    return EOF_CHAR;
                }
                char c = source.charAt(position++);
                if (c == '\r') {
                    if (position < source.length() && source.charAt(position) == '\n') {
                        position++;
                    }
                    return '\n';
                }
                if (c > 127) {
                    if (c == BYTE_ORDER_MARK) {
                        return c;
                    }
                    if (skipFormat && Character.getType(c) == Character.FORMAT) {
                        continue;
                    }
                    if (c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
                        return '\n';
                    }
                }
                return c;
            }
        }

        /** Pushes back {@code c}, the character read last. */
        private void unread(int c) {
            unread(c, charStart);
        }

        /** Pushes back {@code c}, which was read at index {@code at}. */
        private void unread(int c, int at) {
            pushedChars[pushed] = c;
            pushedStarts[pushed] = at;
            pushed++;
        }

        /** Rhino's {@code isJSSpace}: tab, vertical tab, form feed, space, no-break space, BOM and category Zs. */
        private static boolean isJsSpace(int c) {
            if (c <= 127) {
                return c == ' ' || c == '\t' || c == '\f' || c == 0x0B;
            }
            return c == 0xA0 || c == BYTE_ORDER_MARK || Character.getType(c) == Character.SPACE_SEPARATOR;
        }

        /** Whether {@code c} is an ASCII digit. */
        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }
    }

    /**
     * Rhino 1.7R4's {@code Parser} for {@code "(" + body + ")"} where the body is literal data: the parenthesized
     * literal, optionally followed by empty statements. Arrays and objects are built as they parse, on an explicit
     * stack of open literals rather than the thread's, so nesting costs no stack depth; signed literals are left as
     * {@link PendingSign}s.
     */
    private static final class LiteralParser {

        /** Marks that a value began with an opening bracket or brace instead of completing. */
        private static final Object OPENED = new Object();

        /** Marks that the innermost open literal awaits a value at the current token. */
        private static final Object AWAIT_VALUE = new Object();

        private final Lexer lexer;

        /** The signed literals, each after those nested in it: the order Rhino evaluates them in. */
        final List<PendingSign> pendingSigns = new ArrayList<>();

        /** @param text the request body */
        LiteralParser(String text) {
            lexer = new Lexer("(" + text + ")");
        }

        /** An array or object literal whose closing bracket or brace has not been reached. */
        private static final class OpenLiteral {

            /** The array being built, or {@code null} for an object. */
            final JsArray array;

            /** The object being built, or {@code null} for an array. */
            final JsObject object;

            /** Whether signs precede the literal. */
            final boolean signed;

            /** Whether those signs negate. */
            final boolean negate;

            /** For an array: whether no element has followed the opening bracket or the last comma. */
            boolean afterBracketOrComma = true;

            /** For an object: whether a property value was just placed, so a comma or a brace must follow. */
            boolean afterValue;

            /** For an object: the canonical name of the property whose value is being parsed. */
            String key;

            /** The prefix {@code ++} or {@code --} the literal is the operand of, or {@code null}. */
            String increment;

            /** Index in the lexer's source of {@link #increment}. */
            int incrementStart;

            /**
             * @param array the array being built, or {@code null}
             * @param object the object being built, or {@code null}
             * @param signed whether signs precede the literal
             * @param negate whether those signs negate
             */
            OpenLiteral(JsArray array, JsObject object, boolean signed, boolean negate) {
                this.array = array;
                this.object = object;
                this.signed = signed;
                this.negate = negate;
            }

            /**
             * Places a completed value, as Rhino's {@code newArrayLiteral} and {@code newObjectLiteral} do: the next
             * element of an array, or the property {@link #key} of an object, where {@code __proto__} sets the
             * prototype, {@code __parent__} creates no property, and a repeated key keeps its first position and
             * takes the last value.
             */
            void place(Object value) {
                if (array != null) {
                    if (value instanceof PendingSign pending) {
                        pending.heldBy(array, array.elements.size());
                    }
                    array.elements.add(value);
                } else if (key.equals("__proto__")) {
                    // Signs produce a number, whose wrapper becomes the prototype.
                    object.prototype = value instanceof PendingSign ? Proto.PRIMITIVE_WRAPPER : prototypeFor(value);
                } else if (!key.equals("__parent__")) {
                    if (value instanceof PendingSign pending) {
                        pending.heldBy(object, key);
                    }
                    object.properties.put(key, value);
                }
            }

            /** The prototype {@code __proto__: value} sets. */
            private static Object prototypeFor(Object value) {
                if (value instanceof JsObject || value instanceof JsArray) {
                    return value;
                }
                return value == null || value == Special.UNDEFINED ? Proto.NONE : Proto.PRIMITIVE_WRAPPER;
            }
        }

        /**
         * Parses the program.
         *
         * @return the literal: a primitive value, a {@link JsArray}, a {@link JsObject} or a {@link PendingSign}
         * @throws IllegalArgumentException when the body holds only whitespace and comments, is not literal data,
         *         or nests too deep
         */
        Object parse() {
            lexer.next();
            lexer.next();
            if (lexer.type == TokenType.EOF
                    || lexer.isPunctuator(")") && lexer.start == lexer.appendedParenthesis()) {
                throw new IllegalArgumentException(NO_CONTENT);
            }
            Object literal = literal();
            if (!lexer.isPunctuator(")")) {
                if (lexer.isPunctuator(",")) {
                    throw lexer.unsupported(lexer.start, lexer.construct(true));
                }
                throw lexer.afterLiteral("missing ) in parenthetical");
            }
            lexer.next();
            boolean statementEnded = lexer.newlineBefore;
            boolean semicolon = false;
            while (lexer.isPunctuator(";")) {
                statementEnded = true;
                semicolon = true;
                lexer.next();
            }
            if (lexer.type != TokenType.EOF) {
                if (lexer.startsExpression() || lexer.continuesExpression() || lexer.isPunctuator(",")) {
                    // Without a semicolon an operator continues the parenthesized expression.
                    boolean afterOperand = !semicolon && (lexer.continuesExpression() || lexer.isPunctuator(","));
                    throw lexer.unsupported(lexer.start, lexer.construct(afterOperand));
                }
                throw lexer.syntaxError(statementEnded ? "syntax error" : "missing ; before statement");
            }
            return literal;
        }

        /** Parses one literal, with everything nested in it, starting at the current token. */
        private Object literal() {
            Deque<OpenLiteral> open = new ArrayDeque<>();
            for (;;) {
                Object completed = beginValue(open);
                // Place each completed value in the innermost open literal and close the literals it completes,
                // until one awaits another value or the outermost literal is complete.
                for (;;) {
                    OpenLiteral innermost = open.peek();
                    if (completed != OPENED) {
                        if (innermost == null) {
                            return completed;
                        }
                        innermost.place(completed);
                    }
                    completed = innermost.array != null ? arrayContent(innermost) : objectContent(innermost);
                    if (completed == AWAIT_VALUE) {
                        break;
                    }
                    open.pop();
                    if (innermost.increment != null) {
                        throw incrementedLiteral(innermost.increment, innermost.incrementStart);
                    }
                }
            }
        }

        /**
         * Parses the start of a value: its signs, then a primitive literal, which completes the value, or an opening
         * bracket or brace, which opens a literal on {@code open}.
         *
         * @return the completed value, or {@link #OPENED}
         */
        private Object beginValue(Deque<OpenLiteral> open) {
            boolean signed = false;
            boolean negate = false;
            while (lexer.isPunctuator("+") || lexer.isPunctuator("-")) {
                signed = true;
                negate ^= lexer.isPunctuator("-");
                lexer.next();
            }
            if (lexer.isPunctuator("++") || lexer.isPunctuator("--")) {
                beginIncrement(open);
                return OPENED;
            }
            Object operand;
            switch (lexer.type) {
                case NUMBER -> {
                    operand = lexer.number;
                    lexer.next();
                }
                case STRING -> {
                    operand = lexer.text;
                    lexer.next();
                }
                case NAME -> {
                    switch (lexer.text) {
                        case "true" -> operand = Boolean.TRUE;
                        case "false" -> operand = Boolean.FALSE;
                        case "null" -> operand = null;
                        case "undefined" -> operand = Special.UNDEFINED;
                        case "NaN" -> operand = Double.NaN;
                        case "Infinity" -> operand = Double.POSITIVE_INFINITY;
                        default -> throw lexer.unsupported(lexer.start, lexer.construct(false));
                    }
                    lexer.next();
                }
                case PUNCTUATOR -> {
                    if (lexer.isPunctuator("[") || lexer.isPunctuator("{")) {
                        openLiteral(open, signed, negate);
                        return OPENED;
                    }
                    if (lexer.startsExpression()) {
                        throw lexer.unsupported(lexer.start, lexer.construct(false));
                    }
                    throw lexer.syntaxError("syntax error");
                }
                default -> throw lexer.syntaxError("Unexpected end of file");
            }
            return sign(operand, signed, negate);
        }

        /**
         * Opens the array or object literal whose bracket or brace is the current token.
         *
         * @return the open literal, pushed onto {@code open}
         */
        private OpenLiteral openLiteral(Deque<OpenLiteral> open, boolean signed, boolean negate) {
            checkDepth(open.size() + 1);
            OpenLiteral literal = lexer.isPunctuator("[")
                    ? new OpenLiteral(new JsArray(), null, signed, negate)
                    : new OpenLiteral(null, new JsObject(), signed, negate);
            open.push(literal);
            lexer.next();
            return literal;
        }

        /** {@code operand}, or a {@link PendingSign} standing for it when signs precede it. */
        private Object sign(Object operand, boolean signed, boolean negate) {
            if (!signed) {
                return operand;
            }
            PendingSign pending = new PendingSign(negate, operand);
            pendingSigns.add(pending);
            return pending;
        }

        /** Completes an open literal: the array or object, behind its signs. */
        private Object close(OpenLiteral literal) {
            return sign(literal.array != null ? literal.array : literal.object, literal.signed, literal.negate);
        }

        /**
         * Parses an array's content up to its next element or its end, Rhino's {@code Parser.arrayLiteral}: each comma
         * after the bracket or another comma adds a hole.
         *
         * @return {@link #AWAIT_VALUE} when an element starts, or the completed array
         */
        private Object arrayContent(OpenLiteral literal) {
            for (;;) {
                if (lexer.isPunctuator(",")) {
                    lexer.next();
                    if (literal.afterBracketOrComma) {
                        literal.array.elements.add(Special.HOLE);
                    } else {
                        literal.afterBracketOrComma = true;
                    }
                } else if (lexer.isPunctuator("]")) {
                    lexer.next();
                    return close(literal);
                } else if (literal.afterBracketOrComma && lexer.type != TokenType.EOF) {
                    literal.afterBracketOrComma = false;
                    return AWAIT_VALUE;
                } else if (lexer.type == TokenType.NAME && lexer.text.equals("for")) {
                    throw lexer.unsupported(lexer.start, "an array comprehension");
                } else {
                    throw lexer.afterLiteral("missing ] after element list");
                }
            }
        }

        /**
         * Parses an object's content up to its next property value or its end, Rhino's {@code Parser.objectLiteral},
         * with the key canonicalized.
         *
         * @return {@link #AWAIT_VALUE} when a property value starts, or the completed object
         */
        private Object objectContent(OpenLiteral literal) {
            if (literal.afterValue) {
                literal.afterValue = false;
                if (lexer.isPunctuator(",")) {
                    lexer.next();
                } else if (lexer.isPunctuator("}")) {
                    lexer.next();
                    return close(literal);
                } else {
                    throw lexer.afterLiteral("missing } after property list");
                }
            }
            String key;
            if (lexer.type == TokenType.NAME) {
                key = lexer.text;
                int keyStart = lexer.start;
                lexer.next();
                if ((key.equals("get") || key.equals("set"))
                        && !lexer.isPunctuator(",") && !lexer.isPunctuator(":") && !lexer.isPunctuator("}")) {
                    throw lexer.unsupported(keyStart, "a getter or setter");
                }
            } else if (lexer.isPunctuator("}")) {
                lexer.next();
                return close(literal);
            } else if (lexer.type == TokenType.STRING) {
                key = lexer.text;
                lexer.next();
            } else if (lexer.type == TokenType.NUMBER) {
                key = numberToString(lexer.number);
                lexer.next();
            } else if (KEYWORDS.contains(lexer.lastString)) {
                // Rhino names the property after the last name, string or number scanned when that is a keyword,
                // consuming the token in its place: {a:"if",[:1} has a property "if".
                key = lexer.lastString;
                lexer.next();
            } else {
                throw lexer.syntaxError("invalid property id");
            }
            if (!lexer.isPunctuator(":")) {
                throw lexer.syntaxError("missing : after property id");
            }
            lexer.next();
            literal.key = key;
            literal.afterValue = true;
            return AWAIT_VALUE;
        }

        /**
         * Parses a prefix {@code ++} or {@code --}, the current token, which never yields a value. Rhino increments
         * a variable or a member, which is code; it rejects a bare literal operand with its increment error, and a
         * token that begins no operand with a syntax error. An array or object operand is opened on {@code open},
         * and {@link #literal()} reports the failure once it closes, so whatever fails inside it fails first.
         *
         * @throws IllegalArgumentException unless the operand is an array or object literal, now open
         */
        private void beginIncrement(Deque<OpenLiteral> open) {
            String operator = lexer.text;
            int operatorStart = lexer.start;
            lexer.next();
            boolean literalWord = lexer.type == TokenType.NAME
                    && (lexer.text.equals("true") || lexer.text.equals("false") || lexer.text.equals("null"));
            if (lexer.isPunctuator("(") || lexer.type == TokenType.NAME && !literalWord) {
                throw lexer.unsupported(operatorStart, "the operator '" + operator + "'");
            }
            boolean operand = lexer.startsExpression() && !lexer.isPunctuator("+") && !lexer.isPunctuator("-")
                    && !lexer.isPunctuator("!") && !lexer.isPunctuator("~") && !lexer.isPunctuator("++")
                    && !lexer.isPunctuator("--");
            if (!operand) {
                throw lexer.syntaxError("syntax error");
            }
            if (lexer.isPunctuator("[") || lexer.isPunctuator("{")) {
                OpenLiteral literal = openLiteral(open, false, false);
                literal.increment = operator;
                literal.incrementStart = operatorStart;
                return;
            }
            if (lexer.type == TokenType.PUNCTUATOR) {
                // A regular expression or XML operand.
                throw lexer.unsupported(lexer.start, lexer.construct(false));
            }
            lexer.next();
            throw incrementedLiteral(operator, operatorStart);
        }

        /**
         * The failure for prefix {@code operator} at index {@code operatorStart}, whose literal operand has just
         * been parsed: code when member access or a call follows the operand, which then names a property to
         * increment, and otherwise Rhino's increment error.
         */
        private IllegalArgumentException incrementedLiteral(String operator, int operatorStart) {
            if (lexer.isPunctuator(".") || lexer.isPunctuator("..") || lexer.isPunctuator(".(")
                    || lexer.isPunctuator("[") || lexer.isPunctuator("(")) {
                return lexer.unsupported(operatorStart, "the operator '" + operator + "'");
            }
            return lexer.syntaxError(operator.equals("++")
                    ? "Invalid increment operand." : "Invalid decrement operand.");
        }

        /** Fails when a literal opens deeper than {@link #MAX_NESTING_DEPTH}. */
        private void checkDepth(int depth) {
            if (depth > MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("Input nests arrays and objects more than " + MAX_NESTING_DEPTH
                        + " levels deep (at offset " + lexer.offset(lexer.start) + ")");
            }
        }
    }

    /**
     * The script's runtime semantics over the parsed literal: its signs, Rhino's conversions and the {@code for-in}
     * sum. One instance serves one request. Every string it builds is bounded by {@link #MAX_STRING_LENGTH}.
     */
    private static final class ScriptSemantics {

        private static final String[] VALUE_OF_FIRST = {"valueOf", "toString"};

        private static final String[] TO_STRING_FIRST = {"toString", "valueOf"};

        /** What {@code Object.prototype.toString} returns for every object here. */
        private static final String OBJECT_STRING = "[object Object]";

        private static final double TWO_TO_THE_32 = 4294967296.0;

        /** What {@code Array.prototype.toString} puts between elements. */
        private static final String SEPARATOR = ",";

        /** One {@code Array.prototype.toString} call in progress on the explicit stack of {@link #toPrimitive}. */
        private static final class Join {

            /** The {@code this} of the call: an array, or an object whose chain ends in one. */
            final Object thisObject;

            /** Whether the result is the {@code length} the call below it on the stack waits for. */
            final boolean forLength;

            /** How many elements the call joins, or -1 until known. */
            long length = -1;

            /** The index of the next element to append. */
            long next;

            /** Elements the objects on the chain of {@link #thisObject} hold as own properties, by index. */
            Map<Long, Object> indexed;

            /** The array that ends the chain of {@link #thisObject}, which holds the other elements. */
            JsArray base;

            /** The elements joined so far. */
            final StringBuilder joined = new StringBuilder();

            /**
             * @param thisObject the {@code this} of the call
             * @param forLength whether the result is a length the call below waits for
             */
            Join(Object thisObject, boolean forLength) {
                this.thisObject = thisObject;
                this.forLength = forLength;
            }
        }

        /**
         * Applies the signs of the parsed body in Rhino's evaluation order: ToNumber of each operand, negated for an
         * odd count of {@code -}.
         *
         * @return the literal, a number when signs precede it
         */
        Object applySigns(Object literal, List<PendingSign> pendingSigns) {
            for (PendingSign pending : pendingSigns) {
                double number = toNumber(pending.operand);
                pending.apply(pending.negate ? -number : number);
            }
            return literal instanceof PendingSign pending ? pending.result : literal;
        }

        /**
         * Runs {@code var sum = 0; for (var i in list) sum += list[i];}.
         *
         * @return the {@link Double} sum, or the {@link String} once a string was added
         */
        Object forInSum(Object list) {
            double numeric = 0.0;
            StringBuilder concatenated = null;
            for (Object value : forInValues(list)) {
                if (concatenated == null && value instanceof Double d) {
                    numeric += d;
                    continue;
                }
                Object primitive = value instanceof JsObject || value instanceof JsArray
                        ? toPrimitive(value, false) : value;
                if (concatenated != null) {
                    append(concatenated, toStringValue(primitive));
                } else if (primitive instanceof String s) {
                    concatenated = new StringBuilder(numberToString(numeric));
                    append(concatenated, s);
                } else {
                    numeric += toNumber(primitive);
                }
            }
            return concatenated == null ? Double.valueOf(numeric) : concatenated.toString();
        }

        /**
         * The values {@code list[i]} reads, in the order {@code for (var i in list)} visits them (Rhino's
         * {@code ScriptRuntime.enumInit} and {@code enumNext}): an object's own properties, then each prototype's
         * properties not yet seen, an array's present indices in ascending order, and nothing for a primitive, a
         * string included, since Rhino 1.7R4 enumerates no string indices.
         */
        private static List<Object> forInValues(Object root) {
            List<Object> values = new ArrayList<>();
            if (root instanceof JsArray array) {
                addPresentElements(array, values, null);
                return values;
            }
            if (!(root instanceof JsObject object)) {
                return values;
            }
            if (inheritsIterator(object)) {
                throw typeError("Invalid iterator value");
            }
            Set<String> seen = object.prototype instanceof JsObject || object.prototype instanceof JsArray
                    ? new HashSet<>() : null;
            Object current = object;
            while (current instanceof JsObject layer) {
                for (Map.Entry<String, Object> property : layer.properties.entrySet()) {
                    if (seen == null || seen.add(property.getKey())) {
                        values.add(property.getValue());
                    }
                }
                current = layer.prototype;
            }
            if (current instanceof JsArray array) {
                addPresentElements(array, values, seen);
            }
            return values;
        }

        /** Adds the present elements of {@code array} whose index is not in {@code seen} ({@code null}: none). */
        private static void addPresentElements(JsArray array, List<Object> values, Set<String> seen) {
            for (int i = 0; i < array.elements.size(); i++) {
                Object element = array.elements.get(i);
                if (element != Special.HOLE && (seen == null || seen.add(Integer.toString(i)))) {
                    values.add(element);
                }
            }
        }

        /**
         * Whether the chain of {@code object} holds an {@code __iterator__} property, which makes {@code for-in}
         * call it as a JavaScript 1.7 iterator, and fail as no data property can be called.
         */
        private static boolean inheritsIterator(JsObject object) {
            Object current = object;
            while (current instanceof JsObject layer) {
                if (layer.properties.containsKey("__iterator__")) {
                    return true;
                }
                current = layer.prototype;
            }
            return false;
        }

        /**
         * Rhino's ToPrimitive ({@code ScriptableObject.getDefaultValue}) of an object or array. The two methods that
         * can succeed here, {@code Object.prototype.toString} and {@code Array.prototype.toString}, return strings,
         * so the result is one. Nested {@code Array.prototype.toString} calls, one for each array an array holds, run
         * on an explicit stack rather than the thread's, so nesting costs no stack depth.
         */
        private String toPrimitive(Object object, boolean stringHint) {
            if (!joins(object, stringHint)) {
                return OBJECT_STRING;
            }
            Deque<Join> calls = new ArrayDeque<>();
            calls.push(new Join(object, false));
            for (;;) {
                Join call = calls.peek();
                boolean lengthPushed = call.length < 0 && !begin(call, calls);
                if (lengthPushed || !resume(call, calls)) {
                    // A conversion that joins was pushed onto the stack; it completes first.
                    continue;
                }
                calls.pop();
                String result = call.joined.toString();
                Join caller = calls.peek();
                if (caller == null) {
                    return result;
                }
                if (call.forLength) {
                    start(caller, toUint32(stringToNumber(result)));
                } else {
                    append(caller.joined, result);
                }
            }
        }

        /**
         * Which method ToPrimitive of {@code object} succeeds with: {@code valueOf} then {@code toString}, or the
         * reverse for a string hint, each the first property of that name on the chain.
         *
         * @return {@code true} for {@code Array.prototype.toString}, {@code false} for
         *         {@code Object.prototype.toString}
         * @throws IllegalArgumentException with Rhino's {@code TypeError} when no method succeeds
         */
        private static boolean joins(Object object, boolean stringHint) {
            for (String name : stringHint ? TO_STRING_FIRST : VALUE_OF_FIRST) {
                switch (findMethod(object, name)) {
                    case OBJECT_TO_STRING -> {
                        return false;
                    }
                    case ARRAY_TO_STRING -> {
                        return true;
                    }
                    case WRAPPER_METHOD -> throw typeError("Method \"" + name + "\" called on incompatible object.");
                    case DATA, ABSENT, OBJECT_VALUE_OF -> {
                        // Neither a data property nor an absent method can be called, and Object.prototype.valueOf
                        // returns the object itself: Rhino tries the next method.
                    }
                }
            }
            throw typeError("Cannot find default value for object.");
        }

        /** What the first property called {@code name} on the chain of {@code object} is. */
        private static Method findMethod(Object object, String name) {
            Object current = object;
            while (current instanceof JsObject layer) {
                if (layer.properties.containsKey(name)) {
                    return Method.DATA;
                }
                current = layer.prototype;
            }
            boolean toString = name.equals("toString");
            if (current instanceof JsArray) {
                // An array's own properties are its indices and length; Array.prototype defines toString only.
                return toString ? Method.ARRAY_TO_STRING : Method.OBJECT_VALUE_OF;
            }
            if (current == Proto.OBJECT_PROTOTYPE) {
                return toString ? Method.OBJECT_TO_STRING : Method.OBJECT_VALUE_OF;
            }
            return current == Proto.PRIMITIVE_WRAPPER ? Method.WRAPPER_METHOD : Method.ABSENT;
        }

        /**
         * Determines how many elements {@code call} joins: an array's length, or, for an object whose chain ends in an
         * array, ToUint32(ToNumber({@code this.length})), as Rhino's {@code NativeArray.toStringHelper} does.
         *
         * @return {@code false} when that length is an object whose conversion joins, pushed onto {@code calls}
         */
        private boolean begin(Join call, Deque<Join> calls) {
            if (call.thisObject instanceof JsArray array) {
                requireSeparatorsWithinBound(array.elements.size());
                call.length = array.elements.size();
                call.indexed = Map.of();
                call.base = array;
                return true;
            }
            Object length = lengthOf((JsObject) call.thisObject);
            double number;
            if (length instanceof JsObject || length instanceof JsArray) {
                if (joins(length, false)) {
                    calls.push(new Join(length, true));
                    return false;
                }
                number = stringToNumber(OBJECT_STRING);
            } else {
                number = toNumber(length);
            }
            start(call, toUint32(number));
            return true;
        }

        /**
         * Starts a join of {@code length} elements of an object that is not an array. Its element i is the first
         * property "i" on its chain: the objects' own index properties, nearest first, collected once, then the array
         * at the end of the chain.
         */
        private void start(Join call, long length) {
            requireSeparatorsWithinBound(length);
            Map<Long, Object> indexed = new HashMap<>();
            Object current = call.thisObject;
            while (current instanceof JsObject layer) {
                for (Map.Entry<String, Object> property : layer.properties.entrySet()) {
                    long index = arrayIndex(property.getKey());
                    if (index >= 0 && index < length && !indexed.containsKey(index)) {
                        indexed.put(index, property.getValue());
                    }
                }
                current = layer.prototype;
            }
            call.indexed = indexed;
            call.base = (JsArray) current;
            call.length = length;
        }

        /**
         * Appends the elements of {@code call} from where it stopped, with commas: a missing, {@code undefined} or
         * {@code null} element as "", any other by ToString.
         *
         * @return {@code false} when an element is an object whose conversion joins, pushed onto {@code calls} for
         *         its result to be appended first
         */
        private boolean resume(Join call, Deque<Join> calls) {
            while (call.next < call.length) {
                long i = call.next++;
                if (i > 0) {
                    append(call.joined, SEPARATOR);
                }
                Object element = !call.indexed.isEmpty() && call.indexed.containsKey(i)
                        ? call.indexed.get(i) : call.base.element(i);
                if (element instanceof JsObject || element instanceof JsArray) {
                    if (joins(element, true)) {
                        calls.push(new Join(element, false));
                        return false;
                    }
                    append(call.joined, OBJECT_STRING);
                } else if (element != Special.UNDEFINED && element != null) {
                    append(call.joined, toStringValue(element));
                }
            }
            return true;
        }

        /**
         * Fails before a join of {@code length} elements appends anything when its {@code length - 1} separators
         * alone would make a string longer than {@link #MAX_STRING_LENGTH}, which Rhino's join could not build.
         */
        private static void requireSeparatorsWithinBound(long length) {
            requireStringLength((length - 1) * SEPARATOR.length());
        }

        /**
         * Appends {@code text} to {@code builder}, failing first when the result would be longer than
         * {@link #MAX_STRING_LENGTH}.
         */
        private static void append(StringBuilder builder, String text) {
            requireStringLength((long) builder.length() + text.length());
            builder.append(text);
        }

        /** The {@code length} property {@code object} reads through its chain, which ends in an array. */
        private static Object lengthOf(JsObject object) {
            Object current = object;
            while (current instanceof JsObject layer) {
                if (layer.properties.containsKey("length")) {
                    return layer.properties.get("length");
                }
                current = layer.prototype;
            }
            return (double) ((JsArray) current).elements.size();
        }

        /** The index a canonical property name denotes, or -1: decimal digits without a leading zero, below 2^32. */
        private static long arrayIndex(String key) {
            int length = key.length();
            if (length == 0 || length > 10 || length > 1 && key.charAt(0) == '0') {
                return -1;
            }
            for (int i = 0; i < length; i++) {
                if (!Lexer.isDigit(key.charAt(i))) {
                    return -1;
                }
            }
            long index = Long.parseLong(key);
            return index < (long) TWO_TO_THE_32 ? index : -1;
        }

        /** ES5 9.6 ToUint32. */
        private static long toUint32(double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return 0;
            }
            double modulo = (d < 0 ? Math.ceil(d) : Math.floor(d)) % TWO_TO_THE_32;
            return (long) (modulo < 0 ? modulo + TWO_TO_THE_32 : modulo);
        }

        /** Rhino's ToNumber ({@code ScriptRuntime.toNumber(Object)}). */
        private double toNumber(Object value) {
            if (value instanceof Double d) {
                return d;
            }
            if (value instanceof String s) {
                return stringToNumber(s);
            }
            if (value instanceof Boolean b) {
                return b ? 1.0 : 0.0;
            }
            if (value == null) {
                return 0.0;
            }
            if (value == Special.UNDEFINED) {
                return Double.NaN;
            }
            return stringToNumber(toPrimitive(value, false));
        }

        /** Rhino's ToString ({@code ScriptRuntime.toString(Object)}). */
        String toStringValue(Object value) {
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof Double d) {
                return numberToString(d);
            }
            if (value instanceof Boolean b) {
                return b ? "true" : "false";
            }
            if (value == null) {
                return "null";
            }
            if (value == Special.UNDEFINED) {
                return "undefined";
            }
            return toPrimitive(value, true);
        }

        /** A failure Rhino reports as {@code TypeError: <message>}. */
        private static IllegalArgumentException typeError(String message) {
            return new IllegalArgumentException("TypeError: " + message);
        }
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
