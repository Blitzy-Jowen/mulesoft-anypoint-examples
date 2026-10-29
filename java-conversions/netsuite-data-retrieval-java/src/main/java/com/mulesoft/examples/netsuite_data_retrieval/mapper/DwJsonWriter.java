/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Writes Java values as the bytes DataWeave 1.0's {@code %output application/json} writer produced
 * (shared component SC-4). The mappers that reimplement the original {@code %dw 1.0} scripts build their
 * result as maps, lists and scalars and serialize it here, so the converted application emits the same
 * bytes as the original, for example {@code processing-orders-with-dataweave-and-APIkit/src/main/resources/orders.json}.
 *
 * <p>Layout:
 * <ul>
 *   <li>A non-empty object or array opens with <code>&#123;</code> or {@code [} and a line feed. Each entry
 *       sits on its own line, indented two spaces more than its parent (the root is at indent 0); an object
 *       entry is written {@code "key": value}, an array entry is just its value.</li>
 *   <li>Entries are separated by {@code ,} and a line feed. After the last entry come a line feed, the
 *       parent's indent and the closing <code>&#125;</code> or {@code ]}.</li>
 *   <li>An empty object is {@code {}} and an empty array {@code []}, with nothing between the brackets.</li>
 *   <li>A nested object or array starts on the line of its key ({@code "prices": [}).</li>
 *   <li>Line ends are LF only, and nothing follows the root value: no trailing line feed.</li>
 * </ul>
 *
 * <pre>{@code
 * {
 *   "orders": [
 *     {
 *       "title": "Everyday Italian",
 *       "prices": [
 *         {
 *           "price": 27.60,
 *           "currency": "EUR"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Values, checked in this order:
 * <ul>
 *   <li>{@code null} is {@code null}.</li>
 *   <li>A {@link Map} is an object whose entries follow the map's iteration order (pass a
 *       {@link java.util.LinkedHashMap} for script order, or a {@link java.util.HashMap} where the original
 *       runtime iterated one); each key is written as the JSON string of {@code String.valueOf(key)}.</li>
 *   <li>An {@link Iterable} or an {@code Object[]} is an array in iteration order.</li>
 *   <li>A {@link CharSequence} or {@link Character} is a JSON string; an {@link Enum} is the JSON string of
 *       its {@code name()}.</li>
 *   <li>A {@link BigDecimal} is its {@code toPlainString()}, so the scale of DataWeave arithmetic is kept
 *       ({@code 27.60} stays {@code 27.60}) and exponent notation never appears.</li>
 *   <li>{@link Byte}, {@link Short}, {@link Integer}, {@link Long} and {@link BigInteger} are their
 *       {@code toString()}.</li>
 *   <li>A {@link Double} is written through {@code BigDecimal.valueOf(d)} and a {@link Float} through
 *       {@code new BigDecimal(Float.toString(f))}, both in plain notation, so their digits and scale are
 *       those of {@code Double.toString} and {@code Float.toString} ({@code 3.0} stays {@code 3.0}). The
 *       float is not widened to a double first, so {@code 0.1f} is {@code 0.1}. NaN and infinities have no
 *       JSON form and raise {@link IllegalArgumentException}.</li>
 *   <li>A {@link Boolean} is {@code true} or {@code false}.</li>
 *   <li>Anything else is the JSON string of {@code String.valueOf(value)}. Primitive arrays such as
 *       {@code int[]} are not arrays here: they fall into this case, so callers pass a list or an
 *       {@code Object[]} instead.</li>
 * </ul>
 *
 * <p>Strings escape {@code "} and {@code \} with a backslash; backspace, form feed, line feed, carriage
 * return and tab as {@code \b}, {@code \f}, {@code \n}, {@code \r} and {@code \t}; every other character
 * below U+0020 as {@code \}{@code u00xx} with lowercase hexadecimal digits. The solidus and every non-ASCII
 * character, U+2028, U+2029 and surrogate pairs included, are written unchanged.
 *
 * <p>The class is stateless and thread-safe. It depends on the JDK and {@code @Component} only and is copied
 * into {@code web-service-consumer-java}, {@code netsuite-data-retrieval-java} and
 * {@code service-orchestration-and-choice-routing-java} with only the {@code package} line changed; do not
 * add project-specific logic.
 */
@Component
public class DwJsonWriter {

    private static final int INDENT_STEP = 2;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** Creates a writer; it holds no state, so one instance serves every caller. */
    public DwJsonWriter() {
    }

    /**
     * Renders a value in DataWeave 1.0 JSON layout.
     *
     * @param value the value to write; may be {@code null}
     * @return the JSON text, without a trailing line feed
     * @throws IllegalArgumentException when the value is or contains a NaN or infinite {@link Double} or
     *         {@link Float}
     */
    public String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, 0, out);
        return out.toString();
    }

    /**
     * Renders a value in DataWeave 1.0 JSON layout, encoded in UTF-8.
     *
     * @param value the value to write; may be {@code null}
     * @return the UTF-8 bytes of {@link #write(Object)}
     * @throws IllegalArgumentException when the value is or contains a NaN or infinite {@link Double} or
     *         {@link Float}
     */
    public byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    private void writeValue(Object value, int indent, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, indent, out);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(iterable.iterator(), indent, out);
        } else if (value instanceof Object[] array) {
            writeArray(Arrays.asList(array).iterator(), indent, out);
        } else if (value instanceof CharSequence || value instanceof Character) {
            writeString(value.toString(), out);
        } else if (value instanceof Enum<?> constant) {
            writeString(constant.name(), out);
        } else if (value instanceof BigDecimal decimal) {
            out.append(decimal.toPlainString());
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Double d) {
            requireFinite(d.isNaN(), d.isInfinite(), d > 0);
            out.append(BigDecimal.valueOf(d).toPlainString());
        } else if (value instanceof Float f) {
            requireFinite(f.isNaN(), f.isInfinite(), f > 0);
            out.append(new BigDecimal(Float.toString(f)).toPlainString());
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private void writeObject(Map<?, ?> map, int indent, StringBuilder out) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        int childIndent = indent + INDENT_STEP;
        out.append("{\n");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(",\n");
            }
            first = false;
            out.append(" ".repeat(childIndent));
            writeString(String.valueOf(entry.getKey()), out);
            out.append(": ");
            writeValue(entry.getValue(), childIndent, out);
        }
        out.append('\n').append(" ".repeat(indent)).append('}');
    }

    private void writeArray(Iterator<?> elements, int indent, StringBuilder out) {
        if (!elements.hasNext()) {
            out.append("[]");
            return;
        }
        int childIndent = indent + INDENT_STEP;
        out.append("[\n");
        boolean first = true;
        while (elements.hasNext()) {
            if (!first) {
                out.append(",\n");
            }
            first = false;
            out.append(" ".repeat(childIndent));
            writeValue(elements.next(), childIndent, out);
        }
        out.append('\n').append(" ".repeat(indent)).append(']');
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX_DIGITS[c >> 4]).append(HEX_DIGITS[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void requireFinite(boolean nan, boolean infinite, boolean positive) {
        if (nan) {
            throw new IllegalArgumentException("JSON cannot represent NaN");
        }
        if (infinite) {
            throw new IllegalArgumentException(
                    "JSON cannot represent " + (positive ? "Infinity" : "-Infinity"));
        }
    }
}
