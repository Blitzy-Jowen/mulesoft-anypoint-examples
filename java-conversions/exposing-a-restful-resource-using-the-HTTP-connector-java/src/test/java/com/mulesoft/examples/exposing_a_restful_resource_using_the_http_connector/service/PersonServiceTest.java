/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception.PersonNotFoundException;

/**
 * Unit test of {@link PersonService}, the processing steps of the three flows of {@code http-restful-resource.xml}
 * on a fresh in-memory store per test: {@code storePersonFlow}'s parse-then-increment id assignment and success
 * payload, {@code retrieveAllPersonsFlow}'s compact JSON array in {@code java.util.HashMap} value order, and
 * {@code retrievePersonFlow}'s {@code Integer.valueOf} conversion, ERROR log {@code Person id is <id>} and
 * missing-id rejection. No Spring context is started; the service's own logger is captured by a Logback
 * {@link ListAppender}, and only its ERROR events are asserted.
 */
class PersonServiceTest {

    /** Byte length of the ported {@code person.json}, which has no trailing newline. */
    private static final int PERSON_JSON_LENGTH = 145;

    /** The {@code person.json} member the order test rewrites to tell its twenty persons apart. */
    private static final String TITO_FIRSTNAME = "\"firstname\":\"Tito\"";

    PersonService service;

    Logger serviceLogger;

    Level previousLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createServiceAndCaptureItsLog() {
        service = new PersonService();
        serviceLogger = (Logger) LoggerFactory.getLogger(PersonService.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /**
     * Bodies {@code storePersonFlow}'s {@code json-to-object-transformer} cannot turn into a {@code Person}: a
     * truncated object, an empty body, and a {@code null} body, which the service reads as empty.
     */
    static Stream<Named<byte[]>> unparseableBodies() {
        return Stream.of(
                Named.of("truncated object '{'", utf8("{")),
                Named.of("empty body", new byte[0]),
                Named.of("null body", (byte[]) null));
    }

    @Test
    void storeReturnsSequentialIds() throws IOException {
        String json = personJson();

        assertThat(service.storePerson(utf8(json))).isEqualTo(1);
        assertThat(service.storePerson(utf8(json))).isEqualTo(2);
    }

    @Test
    void storeSuccessResponseIsTheOriginalSetPayload() {
        assertThat(PersonService.STORE_SUCCESS_RESPONSE)
                .isEqualTo("{ \"status\": \"success\", \"statusDescription\": \"person created successfully\"}");
    }

    @ParameterizedTest
    @MethodSource("unparseableBodies")
    void failedParseConsumesNoId(byte[] body) throws IOException {
        String json = personJson();

        assertThrows(IOException.class, () -> service.storePerson(body));

        assertThat(service.storePerson(utf8(json))).isEqualTo(1);
        assertThat(service.retrieveAllPersonsJson()).isEqualTo("[" + json + "]");
    }

    @Test
    void stringAgeIsCoercedToInt() throws IOException {
        String json = personJson();
        String stringAge = json.replace("\"age\":12", "\"age\": \"12\"");
        assertThat(stringAge).isNotEqualTo(json);

        assertThat(service.storePerson(utf8(stringAge))).isEqualTo(1);

        assertThat(service.retrievePersonJson("1")).isEqualTo(json);
    }

    @Test
    void unknownPropertyIsRejected() throws IOException {
        UnrecognizedPropertyException rejection = assertThrows(UnrecognizedPropertyException.class,
                () -> service.storePerson(utf8("{\"firstname\":\"A\",\"nickname\":\"x\"}")));
        assertThat(rejection.getPropertyName()).isEqualTo("nickname");

        assertThat(service.storePerson(utf8(personJson()))).isEqualTo(1);
    }

    @Test
    void storedPersonRoundTripsToExactBytes() throws IOException {
        byte[] personJsonBytes = utf8(personJson());

        assertThat(service.storePerson(personJsonBytes)).isEqualTo(1);

        assertThat(service.retrievePersonJson("1").getBytes(StandardCharsets.UTF_8)).isEqualTo(personJsonBytes);
    }

    @Test
    void emptyCollectionIsEmptyArray() throws IOException {
        assertThat(service.retrieveAllPersonsJson()).isEqualTo("[]");
    }

    @Test
    void singlePersonCollection() throws IOException {
        String json = personJson();
        service.storePerson(utf8(json));

        assertThat(service.retrieveAllPersonsJson()).isEqualTo("[" + json + "]");
    }

    @Test
    void collectionUsesHashMapValueOrder() throws IOException {
        String json = personJson();
        Map<Integer, String> expectedStore = new HashMap<>();
        for (int n = 1; n <= 20; n++) {
            String person = json.replace(TITO_FIRSTNAME, "\"firstname\":\"P" + n + "\"");
            int id = service.storePerson(utf8(person));
            assertThat(id).isEqualTo(n);
            expectedStore.put(id, person);
        }

        assertThat(service.retrieveAllPersonsJson())
                .isEqualTo("[" + String.join(",", expectedStore.values()) + "]");
    }

    @Test
    void missingIdThrowsPersonNotFound() throws IOException {
        PersonNotFoundException onEmptyStore =
                assertThrows(PersonNotFoundException.class, () -> service.retrievePersonJson("999"));
        assertThat(onEmptyStore.getPersonId()).isEqualTo(999);

        assertThat(service.storePerson(utf8(personJson()))).isEqualTo(1);

        PersonNotFoundException besideStoredPerson =
                assertThrows(PersonNotFoundException.class, () -> service.retrievePersonJson("999"));
        assertThat(besideStoredPerson.getPersonId()).isEqualTo(999);
    }

    @Test
    void nonNumericIdThrowsNumberFormatException() {
        assertThrows(NumberFormatException.class, () -> service.retrievePersonJson("abc"));
    }

    @Test
    void retrieveReturnsThePersonStoredUnderTheId() throws IOException {
        String json = personJson();
        String second = json.replace(TITO_FIRSTNAME, "\"firstname\":\"Second\"");
        service.storePerson(utf8(json));
        service.storePerson(utf8(second));

        assertThat(service.retrievePersonJson("2")).isEqualTo(second);
    }

    @Test
    void successfulLookupLogsPersonIdAtError() throws IOException {
        service.storePerson(utf8(personJson()));

        service.retrievePersonJson("1");

        assertThat(errorMessages()).containsExactly("Person id is 1");
    }

    @Test
    void notFoundLogsBeforeThrowing() {
        assertThrows(PersonNotFoundException.class, () -> service.retrievePersonJson("999"));

        assertThat(errorMessages()).containsExactly("Person id is 999");
    }

    @Test
    void parseFailureLogsNothing() {
        assertThrows(NumberFormatException.class, () -> service.retrievePersonJson("abc"));

        assertThat(errorMessages()).isEmpty();
    }

    /** The ported {@code person.json}, read from the test classpath as UTF-8. */
    private static String personJson() throws IOException {
        String json;
        try (InputStream in = PersonServiceTest.class.getResourceAsStream("/person.json")) {
            assertThat(in).as("/person.json on the test classpath").isNotNull();
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(json).hasSize(PERSON_JSON_LENGTH).endsWith("\"age\":12}");
        return json;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Formatted messages of the ERROR events the service logged during the test, in order. */
    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
