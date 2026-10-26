/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.PersonNotFoundException;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.model.Person;

/**
 * The in-memory person resource of {@code http-restful-resource.xml}: the singleton {@code personDataStore}
 * ({@code java.util.HashMap}) and {@code personId} ({@code AtomicInteger}) beans of lines 6-9, and every
 * processing step of the flows {@code retrieveAllPersonsFlow}, {@code storePersonFlow} and
 * {@code retrievePersonFlow}.
 *
 * <p>State lives only in memory and is lost on restart, as with the original singleton beans. HTTP status
 * codes, the {@code Location} header and reason phrases belong to {@code controller/PersonController} and
 * {@code exception/PersonExceptionHandler}. The unused {@code http:request-config} of line 5 is
 * intentionally not reproduced: no flow references it.
 */
@Service
public class PersonService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonService.class);

    /** The {@code storePersonFlow} success payload, byte for byte as the {@code set-payload} of line 24. */
    public static final String STORE_SUCCESS_RESPONSE =
            "{ \"status\": \"success\", \"statusDescription\": \"person created successfully\"}";

    // java.util.HashMap, the original bean class, so values() iterates in the order the original returned.
    private final Map<Integer, Person> personDataStore = new HashMap<>();

    private final AtomicInteger personId = new AtomicInteger();

    // A plain mapper rather than Boot's auto-configured one, which disables FAIL_ON_UNKNOWN_PROPERTIES: the
    // original Jackson 1.x transformers rejected unknown properties, wrote nulls and produced compact output.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@code retrieveAllPersonsFlow} (lines 10-14): every stored person as a compact JSON array.
     *
     * @return the stored values in {@code HashMap} iteration order; {@code []} when none is stored
     * @throws IOException when a stored person cannot be serialized
     */
    public String retrieveAllPersonsJson() throws IOException {
        List<Person> snapshot;
        synchronized (personDataStore) {
            snapshot = new ArrayList<>(personDataStore.values());
        }
        return objectMapper.writeValueAsString(snapshot);
    }

    /**
     * {@code storePersonFlow} (lines 15-25): parses the body into a {@link Person}, then stores it under the
     * next id. A body that fails to parse consumes no id, because the original converted before incrementing.
     *
     * @param body the request body; {@code null} is read as an empty body
     * @return the id the person was stored under, starting at 1
     * @throws IOException when the body is empty, malformed or carries an unknown property
     */
    public int storePerson(byte[] body) throws IOException {
        Person person = objectMapper.readValue(body == null ? new byte[0] : body, Person.class);
        int id = personId.incrementAndGet();
        synchronized (personDataStore) {
            personDataStore.put(id, person);
        }
        return id;
    }

    /**
     * {@code retrievePersonFlow} (lines 27-43): the stored person for the URI parameter, as compact JSON.
     *
     * @param personIdParam the {@code personId} URI parameter
     * @return the stored person's JSON; the text {@code null} when a JSON {@code null} body was stored under the id
     * @throws NumberFormatException when the parameter is not an integer, before anything is logged
     * @throws PersonNotFoundException when no person is stored under the id, after the ERROR log
     * @throws IOException when the stored person cannot be serialized
     */
    public String retrievePersonJson(String personIdParam) throws IOException {
        Integer id = Integer.valueOf(personIdParam);
        LOGGER.error("Person id is {}", id);
        Person person;
        synchronized (personDataStore) {
            // containsKey, not a null check on get, keeps the original filter's verdict for a stored null.
            if (!personDataStore.containsKey(id)) {
                throw new PersonNotFoundException(id);
            }
            person = personDataStore.get(id);
        }
        return objectMapper.writeValueAsString(person);
    }
}
