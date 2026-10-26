/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.springframework.stereotype.Component;

/**
 * Hand-written reimplementation of the "JSON to XML" {@code %dw 1.0} script of flow {@code main} in
 * {@code upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml} (line 9,
 * {@code %output application/xml encoding="UTF-8"}). This class is the script's only owner (AAP 0.5.8); the
 * bytes are written by the SC-4 {@link DwXmlWriter}.
 *
 * <pre>{@code
 * employees: {
 *     (payload.employees.employee map {
 *         employee: {
 *             name: $.name,
 *             lastName: $.lastName,
 *             addresses: {
 *                 ($.addresses.address map {
 *                     address: $
 *                 })
 *             }
 *         }
 *     })
 * }
 * }</pre>
 *
 * <p>JSON objects keep their members in input order, repeated keys included, decimals are read as
 * {@link java.math.BigDecimal} with their scale kept ({@code 1.50}), and every other value is carried as read and
 * rendered by {@link DwXmlWriter}. Input that is empty, not JSON, followed by trailing content, or whose root is not
 * an object raises {@link IllegalArgumentException}.
 *
 * <p>Decisions for input the committed examples do not exercise, each a verification point for fixtures
 * captured from the original runtime, which govern where they differ:
 * <ul>
 *   <li>{@code employees} missing, {@code null} or not an object, or {@code employees.employee} missing,
 *       {@code null} or an empty array, gives {@code <employees/>}.</li>
 *   <li>{@code addresses} missing, {@code null} or not an object, or {@code addresses.address} missing,
 *       {@code null} or an empty array, gives {@code <addresses/>}.</li>
 *   <li>{@code map} over an object ({@code employee} or {@code address}) iterates the values of its members, in
 *       input order, a repeated key once per occurrence, the keys otherwise ignored, so an empty object gives no
 *       item. {@code $} is the member's value, with one quirk: a string, number or boolean value also answers a
 *       selector naming its own key. An object {@code employee} whose members are employee objects thus gives one
 *       employee per member; one with {@code name}, {@code lastName} and {@code addresses} gives three employees,
 *       the first with only the name, the second with only the last name, the third, whose {@code $} is the
 *       addresses object itself, with neither, and each gets {@code <addresses/>} by the decision above for a
 *       missing {@code addresses}. An object {@code address} gives one address per member, written as the member's
 *       value. A value that is neither an array nor an object where the script maps is treated as a one-element
 *       array.</li>
 *   <li>A {@code null} or missing {@code name} or {@code lastName} gives an empty element. An employee item
 *       that is not an object gives empty {@code name} and {@code lastName} elements and
 *       {@code <addresses/>}.</li>
 *   <li>Repeated JSON keys are kept in input order. A selector ({@code payload.employees}, {@code .employee},
 *       {@code $.name}, {@code $.lastName}, {@code $.addresses}, {@code .address}) takes the first member with its
 *       key, as DataWeave's single-value selector does, and {@code address: $} writes every member of an address
 *       object, a repeated key once per occurrence. The rule above for {@code map} over an object and these
 *       repeated-key rules were confirmed by running the original script on the public DataWeave 1.x engine
 *       {@code com.mulesoft.weave} 1.2.0-M4, not on a Mule 3.8 runtime, so they remain verification points for
 *       captured fixtures.</li>
 * </ul>
 *
 * <p>The mapper holds no mutable state and is thread-safe.
 */
@Component
public class EmployeesXmlMapper {

    private static final String EMPTY_PAYLOAD = "JSON payload is empty";

    private final DwXmlWriter dwXmlWriter;

    private final ObjectReader jsonReader;

    /**
     * Creates the mapper.
     *
     * @param dwXmlWriter the DataWeave XML writer
     * @param objectMapper the application's JSON mapper; it is not modified
     */
    public EmployeesXmlMapper(DwXmlWriter dwXmlWriter, ObjectMapper objectMapper) {
        this.dwXmlWriter = dwXmlWriter;
        // A derived reader: parsers get the application's JSON settings, while the injected ObjectMapper, shared by
        // the whole application, and its factory are never reconfigured.
        this.jsonReader = objectMapper.reader();
    }

    /**
     * Converts an employees JSON document to the script's XML output.
     *
     * @param json the JSON payload
     * @return the XML document, declaration included, without a trailing line feed
     * @throws IllegalArgumentException when the payload is empty, malformed, or its root is not an object
     */
    public String toXml(byte[] json) {
        return dwXmlWriter.write(buildEmployees(json));
    }

    /**
     * Converts an employees JSON document to the script's XML output, encoded in UTF-8 as the script's
     * {@code encoding="UTF-8"} directs.
     *
     * @param json the JSON payload
     * @return the UTF-8 bytes of {@link #toXml(byte[])}
     * @throws IllegalArgumentException when the payload is empty, malformed, or its root is not an object
     */
    public byte[] toXmlBytes(byte[] json) {
        return dwXmlWriter.writeBytes(buildEmployees(json));
    }

    private DwXmlWriter.Element buildEmployees(byte[] json) {
        JsonObject payload = readPayload(json);
        DwXmlWriter.Element employees = DwXmlWriter.element("employees");
        for (Object employee : mapItems(field(field(payload, "employees"), "employee"))) {
            employees.child(dwXmlWriter.fromObject("employee", employeeFields(employee)));
        }
        return employees;
    }

    /**
     * The {@code employee: {...}} object of the script for one input item. The employee element is converted
     * from this field map by {@link DwXmlWriter#fromObject(String, Object)} rather than field by field, so an
     * array-valued {@code name}, {@code lastName} or address item repeats its element, as the DataWeave XML
     * writer repeats a key over an array, instead of failing as a single-root conversion would. {@code address: $}
     * writes an address item as it is, and a {@link KeyedScalar} item as its bare scalar.
     */
    private static Map<String, Object> employeeFields(Object employee) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", field(employee, "name"));
        fields.put("lastName", field(employee, "lastName"));
        List<?> items = mapItems(field(field(employee, "addresses"), "address"));
        List<Object> address = new ArrayList<>(items.size());
        for (Object item : items) {
            address.add(item instanceof KeyedScalar scalar ? scalar.value() : item);
        }
        Map<String, Object> addresses = new LinkedHashMap<>();
        addresses.put("address", address);
        fields.put("addresses", addresses);
        return fields;
    }

    private JsonObject readPayload(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException(EMPTY_PAYLOAD);
        }
        Object payload = read(json);
        if (payload instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a JSON object at the root but found " + kindOf(payload));
    }

    /*
     * The payload is read token by token rather than bound to Object: Jackson's untyped Map binding keeps only the
     * last of repeated keys, whereas a DataWeave object is a sequence of key-value pairs that keeps every member.
     * Scalars get the types of that binding with USE_BIG_DECIMAL_FOR_FLOATS, so decimals keep their scale.
     */
    private Object read(byte[] json) {
        try (JsonParser parser = jsonReader.createParser(json)) {
            // On this parser only, so repeated keys are always read; the application's factory keeps its setting.
            parser.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonToken first = parser.nextToken();
            if (first == null) {
                // Whitespace only, or a byte-order mark followed by whitespace: there is no value at all.
                throw new IllegalArgumentException(EMPTY_PAYLOAD);
            }
            Object value = readValue(parser, first);
            JsonToken trailing = parser.nextToken();
            if (trailing != null) {
                // DataWeave's JSON reader rejects content after the root value.
                throw new JsonParseException(parser,
                        "Trailing token (of type " + trailing + ") found after the JSON value");
            }
            return value;
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            throw new IllegalArgumentException(message != null ? message : e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /*
     * Jackson's parser itself rejects end-of-input inside an array or object and a token out of place, so the null
     * check and the default arm only complete the dispatch over JsonToken; JSON text does not reach them.
     */
    private static Object readValue(JsonParser parser, JsonToken token) throws IOException {
        if (token == null) {
            throw new JsonParseException(parser, "Unexpected end-of-input: expected a JSON value");
        }
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new JsonParseException(parser, "Unexpected token (" + token + "): expected a JSON value");
        };
    }

    private static JsonObject readObject(JsonParser parser) throws IOException {
        JsonObject object = new JsonObject();
        for (String key = parser.nextFieldName(); key != null; key = parser.nextFieldName()) {
            object.add(key, readValue(parser, parser.nextToken()));
        }
        return object;
    }

    private static List<Object> readArray(JsonParser parser) throws IOException {
        List<Object> items = new ArrayList<>();
        for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
            items.add(readValue(parser, token));
        }
        return items;
    }

    /**
     * The script's single-value selector {@code value.key}, as DataWeave 1.x applies it to an object: the value of
     * the first member named {@code key}, so a repeated key yields its first occurrence. On a {@link KeyedScalar}
     * item of a {@code map} over an object, the scalar when {@code key} is the member's own key. {@code null} when
     * the object has no such member, the key is another, or {@code value} is neither.
     */
    private static Object field(Object value, String key) {
        if (value instanceof KeyedScalar scalar) {
            return scalar.key().equals(key) ? scalar.value() : null;
        }
        return value instanceof JsonObject object ? object.get(key) : null;
    }

    /**
     * The items a script {@code map} iterates. An array is iterated as it is and {@code null} gives nothing. An
     * object gives the values of its members, as DataWeave 1.x maps over an object: one item per member, in input
     * order, a repeated key once per occurrence, the keys otherwise dropped. A member whose value is an object, an
     * array or {@code null} gives that value itself; a member whose value is a string, number or boolean gives a
     * {@link KeyedScalar}, because DataWeave's {@code $} then still answers a selector naming the member's own key.
     * Any other value is a one-element array.
     */
    private static List<?> mapItems(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof JsonObject object) {
            List<Object> items = new ArrayList<>(object.size());
            for (Map.Entry<String, Object> member : object.entrySet()) {
                Object item = member.getValue();
                items.add(item == null || item instanceof JsonObject || item instanceof List<?>
                        ? item
                        : new KeyedScalar(member.getKey(), item));
            }
            return items;
        }
        return value == null ? List.of() : List.of(value);
    }

    private static String kindOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * The item a {@code map} over an object gives for a member whose value is a string, number or boolean: the
     * value, which {@link #field(Object, String)} also returns for a selector naming {@code key}, and which
     * {@code address: $} writes without the key.
     *
     * @param key the member's key
     * @param value the member's scalar value, never {@code null}
     */
    private record KeyedScalar(String key, Object value) {
    }

    /**
     * A JSON object as DataWeave reads it: its members in input order, a repeated key kept once per occurrence.
     * {@link #entrySet()} therefore counts duplicates, so {@link DwXmlWriter} writes every member, and
     * {@link #get(Object)} sees the first member with the key, as the single-value selector does. Values may be
     * {@code null}. Read-only once built.
     */
    private static final class JsonObject extends AbstractMap<String, Object> {

        private final List<Map.Entry<String, Object>> members = new ArrayList<>();

        private final Set<Map.Entry<String, Object>> entries = new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return Collections.unmodifiableList(members).iterator();
            }

            @Override
            public int size() {
                return members.size();
            }
        };

        private void add(String key, Object value) {
            members.add(new AbstractMap.SimpleImmutableEntry<>(key, value));
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return entries;
        }
    }
}
