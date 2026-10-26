/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.service;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.querying_a_mysql_database.client.EmployeeRepository;

import org.springframework.stereotype.Service;

/**
 * Reproduces the processor chain of flow {@code database-to-jsonFlow}
 * (querying-a-mysql-database/src/main/app/database-to-json.xml:5-11) after its HTTP listener:
 * <ol>
 *   <li>the MEL expression {@code #[message.inboundProperties['http.query.params'].lastname]} becomes
 *       {@link #renderQueryValue(String)};</li>
 *   <li>{@code db:select} becomes {@link EmployeeRepository#findFirstNamesByLastName(String)};</li>
 *   <li>{@code json:object-to-json-transformer} becomes Jackson serialization of the returned rows.</li>
 * </ol>
 *
 * <p>The original flow declares no exception strategy, so no failure is handled here: database errors
 * propagate unchanged to the global exception handler's 500.
 */
@Service
public class EmployeeQueryService {

    /**
     * The text MEL renders for a missing {@code lastname} query parameter inside the original dynamic
     * query, which therefore compared {@code last_name} with the literal {@code null} and matched no row.
     */
    public static final String MISSING_VALUE = "null";

    private final EmployeeRepository repository;

    private final ObjectMapper objectMapper;

    public EmployeeQueryService(EmployeeRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Selects the first names of the employees with the given last name and returns them as the JSON the
     * original {@code object-to-json} transformer produced, for example
     * {@code [{"first_name":"Chava"},{"first_name":"Quentin"}]} for {@code Puckett}.
     *
     * @param lastname the {@code lastname} query parameter, or {@code null} when the request has none
     * @return the matching rows in database order as a compact JSON array, {@code []} when none match
     * @throws UncheckedIOException when the rows cannot be serialized
     */
    public String findFirstNamesAsJson(String lastname) {
        List<Map<String, Object>> rows = repository.findFirstNamesByLastName(renderQueryValue(lastname));
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Renders the query parameter as MEL interpolated it into the original query: a present value passes
     * through unchanged (no trimming, case change or rejection of empty input), a missing one becomes
     * {@value #MISSING_VALUE}.
     */
    static String renderQueryValue(String lastname) {
        return lastname == null ? MISSING_VALUE : lastname;
    }
}
