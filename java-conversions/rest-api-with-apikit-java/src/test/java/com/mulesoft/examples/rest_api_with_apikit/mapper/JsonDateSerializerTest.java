/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;

/**
 * Contract of the ported {@link JsonDateSerializer}: a {@link Date} is written as one JSON string in the
 * pattern {@code yyyy-MM-dd'T'HH:mm:ssZ}, rendered in the JVM default time zone and format locale, whether
 * the serializer is registered on a module or named by {@code @JsonSerialize} on a field, and it stays
 * correct when one instance serves many threads.
 *
 * <p>Expected values come from a fresh {@link SimpleDateFormat} over the same pattern, so the assertions hold
 * in whatever default zone the build runs. A winter and a summer instant are used so a zone with daylight
 * saving shows both of its offsets. No Spring context is started.
 */
class JsonDateSerializerTest {

    /** The pattern the original serializer declared; spelled out here so a change to the class is caught. */
    private static final String EXPECTED_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ";

    /** Northern-hemisphere winter: standard time in a zone with daylight saving. */
    private static final Date WINTER = Date.from(Instant.parse("2014-01-15T10:30:45Z"));

    /** Northern-hemisphere summer: daylight saving time in a zone that observes it. */
    private static final Date SUMMER = Date.from(Instant.parse("2014-07-15T22:05:09Z"));

    private static final int THREADS = 8;
    private static final int ITERATIONS = 1000;

    /** 2000-01-01T00:00:00Z; with {@link #STEP} every concurrent instant falls between 2000 and 2002. */
    private static final long BASE = 946_684_800_000L;

    /** About 2 h 12 min, so successive instants differ in every formatted field from hours to seconds. */
    private static final long STEP = 7_919_000L;

    /** Carries a {@link Date} through the field-level annotation, as {@code model.response.Match#date} does. */
    static final class Holder {

        @JsonSerialize(using = JsonDateSerializer.class)
        public Date when;

        Holder(Date when) {
            this.when = when;
        }
    }

    static Stream<Date> instants() {
        return Stream.of(WINTER, SUMMER);
    }

    /** The JSON text the serializer must write for {@code date}: the formatted pattern in double quotes. */
    private static String expected(Date date) {
        return '"' + new SimpleDateFormat(EXPECTED_PATTERN).format(date) + '"';
    }

    /** A mapper that serializes every {@link Date} through {@link JsonDateSerializer}. */
    private static ObjectMapper mapperWithModule() {
        return new ObjectMapper()
                .registerModule(new SimpleModule().addSerializer(Date.class, new JsonDateSerializer()));
    }

    @ParameterizedTest
    @MethodSource("instants")
    void serializesDateAsQuotedPatternStringViaModule(Date date) throws Exception {
        assertEquals(expected(date), mapperWithModule().writeValueAsString(date));
    }

    @ParameterizedTest
    @MethodSource("instants")
    void serializesAnnotatedFieldOfHolder(Date date) throws Exception {
        assertEquals("{\"when\":" + expected(date) + "}", new ObjectMapper().writeValueAsString(new Holder(date)));
    }

    @ParameterizedTest
    @MethodSource("instants")
    void serializesMatchDateFieldThroughItsAnnotation(Date date) throws Exception {
        Match match = new Match();
        match.setHomeTeam("BAR");
        match.setAwayTeam("RMA");
        match.setDate(date);
        match.setHomeTeamScore(null);
        match.setAwayTeamScore(null);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(match));

        assertEquals("BAR", tree.path("homeTeam").textValue());
        assertEquals("RMA", tree.path("awayTeam").textValue());
        JsonNode dateNode = tree.get("date");
        assertNotNull(dateNode, "date is written");
        assertTrue(dateNode.isTextual(), () -> "date is a JSON string, was " + dateNode.getNodeType());
        String quoted = expected(date);
        assertEquals(quoted.substring(1, quoted.length() - 1), dateNode.textValue());
        assertFalse(tree.has("homeTeamScore"), "a null homeTeamScore is omitted");
        assertFalse(tree.has("awayTeamScore"), "a null awayTeamScore is omitted");
    }

    @Test
    void writesStringTokenNotNumber() throws Exception {
        ObjectMapper mapper = mapperWithModule();
        String json = mapper.writeValueAsString(WINTER);

        try (JsonParser parser = mapper.createParser(json)) {
            assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
            assertNull(parser.nextToken(), "exactly one token is written");
        }
    }

    @Test
    @Timeout(60)
    void isThreadSafeUnderConcurrentUse() throws Exception {
        ObjectMapper mapper = mapperWithModule();
        CountDownLatch start = new CountDownLatch(1);
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger serialized = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        boolean terminated;
        try {
            List<Future<Void>> futures = new ArrayList<>(THREADS);
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                Callable<Void> worker = () -> {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        Date date = new Date(BASE + (thread * (long) ITERATIONS + i) * STEP);
                        String want = expected(date);
                        try {
                            String actual = mapper.writeValueAsString(date);
                            serialized.incrementAndGet();
                            if (!want.equals(actual)) {
                                failures.add("t=" + thread + " i=" + i + " expected=" + want + " actual=" + actual);
                            }
                        } catch (Exception e) {
                            // Recorded rather than rethrown so one worker's failure cannot hide the others'.
                            failures.add("t=" + thread + " i=" + i + " " + e);
                        }
                    }
                    return null;
                };
                futures.add(pool.submit(worker));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
            terminated = pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertTrue(terminated, "worker threads terminate");
        assertTrue(failures.isEmpty(), () -> failures.size() + " failed serializations, first: "
                + failures.stream().limit(5).collect(Collectors.joining("; ")));
        assertEquals(THREADS * ITERATIONS, serialized.get());
    }
}
