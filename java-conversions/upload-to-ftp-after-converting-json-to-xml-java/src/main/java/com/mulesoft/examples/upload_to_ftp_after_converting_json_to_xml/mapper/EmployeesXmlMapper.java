/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * <p>JSON objects keep their key order, decimals are read as {@link java.math.BigDecimal} with their scale
 * kept ({@code 1.50}), and every other value is carried as read and rendered by {@link DwXmlWriter}. Input that
 * is empty, not JSON, followed by trailing content, or whose root is not an object raises
 * {@link IllegalArgumentException}.
 *
 * <p>Decisions for input the committed examples do not exercise, each a verification point for fixtures
 * captured from the original runtime, which govern where they differ:
 * <ul>
 *   <li>{@code employees} missing, {@code null} or not an object, or {@code employees.employee} missing,
 *       {@code null} or an empty array, gives {@code <employees/>}.</li>
 *   <li>{@code addresses} missing, {@code null} or not an object, or {@code addresses.address} missing,
 *       {@code null} or an empty array, gives {@code <addresses/>}.</li>
 *   <li>A single value where an array is expected ({@code employee} or {@code address}) is treated as a
 *       one-element array. DataWeave 1.0 {@code map} over an object iterates the object's values instead.</li>
 *   <li>A {@code null} or missing {@code name} or {@code lastName} gives an empty element. An employee item
 *       that is not an object gives empty {@code name} and {@code lastName} elements and
 *       {@code <addresses/>}.</li>
 *   <li>Duplicate JSON keys keep the last value, whereas DataWeave keeps every occurrence.</li>
 * </ul>
 *
 * <p>The mapper holds no mutable state and is thread-safe.
 */
@Component
public class EmployeesXmlMapper {

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
        // A derived reader: the injected ObjectMapper is shared by the whole application and keeps its settings.
        // Trailing tokens fail the read, as DataWeave's JSON reader rejects content after the root value.
        this.jsonReader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .forType(Object.class);
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
        Map<?, ?> payload = readPayload(json);
        DwXmlWriter.Element employees = DwXmlWriter.element("employees");
        for (Object employee : asList(field(payload.get("employees"), "employee"))) {
            employees.child(dwXmlWriter.fromObject("employee", employeeFields(employee)));
        }
        return employees;
    }

    /**
     * The {@code employee: {...}} object of the script for one input item. The employee element is converted
     * from this field map by {@link DwXmlWriter#fromObject(String, Object)} rather than field by field, so an
     * array-valued {@code name}, {@code lastName} or address item repeats its element, as the DataWeave XML
     * writer repeats a key over an array, instead of failing as a single-root conversion would.
     */
    private static Map<String, Object> employeeFields(Object employee) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", field(employee, "name"));
        fields.put("lastName", field(employee, "lastName"));
        Map<String, Object> addresses = new LinkedHashMap<>();
        addresses.put("address", asList(field(field(employee, "addresses"), "address")));
        fields.put("addresses", addresses);
        return fields;
    }

    private Map<?, ?> readPayload(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("JSON payload is empty");
        }
        Object payload;
        try {
            payload = jsonReader.readValue(json);
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            throw new IllegalArgumentException(message != null ? message : e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (payload instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("Expected a JSON object at the root but found " + kindOf(payload));
    }

    /** The script's {@code value.key} selector: the key's value on an object, otherwise {@code null}. */
    private static Object field(Object value, String key) {
        return value instanceof Map<?, ?> map ? map.get(key) : null;
    }

    /** The items a script {@code map} iterates: an array as it is, nothing for null, a single value alone. */
    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
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
}
