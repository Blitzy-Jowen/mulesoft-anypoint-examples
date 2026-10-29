/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.config;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import com.mulesoft.examples.track_a_custom_business_event.controller.BusinessEventController;
import com.mulesoft.examples.track_a_custom_business_event.support.HttpTestClient;

/**
 * Tests the logger levels {@code application.yml} pins at {@code INFO}, {@code DispatcherServlet}'s at
 * {@code WARN}, so that a root {@code DEBUG} or {@code TRACE} never writes what a caller sent to
 * {@code /customBusinessEvents}: its headers, credentials among them, its {@code Content-Type}, its query string or
 * its body bytes.
 *
 * <p>The application runs on its own Undertow listener under {@code logging.level.root=TRACE}. Each exchange goes
 * through ST-1 {@link HttpTestClient} with credential sentinels in {@code Authorization}, {@code Cookie},
 * {@code X-Secret}, {@code X-Api-Key} and {@code Proxy-Authorization}; some also carry one in the query string, the
 * {@code Content-Type} or a form body. The response is asserted as the endpoint answers it, and the business event
 * as {@code BusinessEventTracker} logs it at {@code INFO} with its tracking MDC wherever the flow records one. The
 * console output captured for the exchange must hold no sentinel, neither as text nor in the decimal rendering
 * Spring logs a byte array with, while it holds {@code TRACE} lines that unpinned loggers write for that same
 * request, so the capture is known to see {@code TRACE}. The class restores the logger levels it found and closes
 * its context, so later test classes run at the levels they would have had without it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // ANSI off keeps the captured lines plain text whatever terminal the build runs in.
        properties = {"logging.level.root=TRACE", "spring.output.ansi.enabled=never"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingLevelsTest {

    /** IPv4 loopback literal: the listener binds {@code 0.0.0.0}, and {@code localhost} may resolve to {@code ::1}. */
    private static final String LOOPBACK = "127.0.0.1";

    private static final String PATH = "/customBusinessEvents";

    /** The prefix of every value a caller sends in this test that must not reach the log. */
    private static final String SENTINEL = "SENTINEL_";

    /** {@link #SENTINEL} as Spring renders a byte array in a log line: {@code 83, 69, 78, ...}. */
    private static final String SENTINEL_BYTES = decimalBytes(SENTINEL);

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String SERVER_ERROR = "HTTP/1.1 500 Internal Server Error";

    private static final String SHOES_EVENT =
            "Business event Price {item name=shoes, item units=2, item price per unit=10}";

    private static final String RANDOM_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** How long the output must stay free of sentinels after the exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    @LocalServerPort
    private int port;

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
    }

    /**
     * Under the root {@code TRACE} every pinned logger writes at its pinned level and above only: {@code INFO} for
     * most, {@code WARN} for {@code DispatcherServlet}, whose {@code INFO} stays off so that a root {@code WARN} also
     * silences its servlet start-up lines. An unpinned neighbour writes at every level.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "org.springframework.web.method.HandlerMethod, INFO",
            "org.springframework.web.servlet.DispatcherServlet, WARN",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor, INFO",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver, INFO",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor, INFO",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter, TRACE",
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter, TRACE",
            "com.mulesoft.examples.track_a_custom_business_event.service.BusinessEventTracker, TRACE"
    })
    void loggerLevelUnderRootTrace(String name, String expectedLevel) {
        assertEquals(Level.TRACE, loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel());
        Logger logger = loggerContext().getLogger(name);
        Level expected = Level.toLevel(expectedLevel, null);
        assertEquals(expected, logger.getEffectiveLevel());
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            assertEquals(level.isGreaterOrEqual(expected), logger.isEnabledFor(level), name + " " + level);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exchanges")
    void callerDataStaysOutOfTheLogWhileTheExchangeAndItsBusinessEventAreUnchanged(Exchange exchange,
                                                                                  CapturedOutput output) {
        String marker = "exchange " + UUID.randomUUID() + " starts";
        System.out.println(marker);

        HttpTestClient.Response response =
                new HttpTestClient(LOOPBACK, port).send(exchange.method(), exchange.target(), exchange.headers(),
                        exchange.body());

        assertEquals(exchange.statusLine(), response.statusLine(), response::toString);
        assertEquals(exchange.responseBody(), response.bodyAsString(), response::toString);

        // Written after the handler and the error handling returned, so once it is captured every line the
        // request's handling writes on its worker thread is captured too. The request is named as the filter sees
        // it, which for an unusable charset is RequestCharsetFilter's view rather than Undertow's request.
        Pattern requestContextCleared =
                Pattern.compile("(?m)^.* TRACE .*: Cleared thread-bound request context: \\S.*$");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> requestContextCleared.matcher(since(output, marker)).find());
        String logged = since(output, marker);
        assertTrue(Pattern.compile("(?m)^.* TRACE .*: Mapped to "
                        + Pattern.quote(BusinessEventController.class.getName() + "#handle"))
                .matcher(logged).find(), "the unpinned handler mapping's TRACE line is captured");

        if (exchange.event() != null) {
            assertTrue(Pattern.compile("(?m)^.* INFO .*\\[transactionId=" + RANDOM_UUID
                            + " businessEvent=Price\\] \\S*BusinessEventTracker +: " + Pattern.quote(exchange.event())
                            + "$").matcher(logged).find(),
                    () -> "business event " + exchange.event() + " logged with its tracking MDC");
        } else {
            assertFalse(logged.contains("Business event "), "no business event is recorded");
        }
        Pattern transactionLine = Pattern.compile("(?m)^.* INFO .*\\[transactionId="
                + "custom-business-event-example-transaction businessEvent=\\] \\S*BusinessEventTracker +: "
                + "Transaction id set to custom-business-event-example-transaction$");
        assertEquals(exchange.priced(), transactionLine.matcher(logged).find(),
                "the business transaction id is set exactly when the price is computed");

        await().during(SETTLE).atMost(SETTLE.multipliedBy(4)).untilAsserted(() -> {
            String all = since(output, marker);
            assertAll(
                    () -> assertFalse(all.contains(SENTINEL), "a sentinel reached the log as text"),
                    () -> assertFalse(all.contains(SENTINEL_BYTES), "a sentinel reached the log as body bytes"));
        });
    }

    static Stream<Exchange> exchanges() throws IOException {
        byte[] message = message();
        Map<String, String> preflight = new LinkedHashMap<>();
        preflight.put("Origin", "http://localhost");
        preflight.put("Access-Control-Request-Method", "POST");
        preflight.put("Access-Control-Request-Headers", "X-Secret");
        String notAnObject = "{ \"message\": \"JSON payload is not an object\" }";
        String notJson = "{ \"message\": \"Payload is not valid JSON\" }";
        return Stream.of(
                new Exchange("POST message.json", "POST", PATH, json(), message, OK, "8.5", SHOES_EVENT, true),
                new Exchange("POST message.json with a query token", "POST",
                        PATH + "?access_token=SENTINEL_QUERY", json(), message, OK, "8.5", SHOES_EVENT, true),
                new Exchange("POST message.json with a Content-Type parameter", "POST", PATH,
                        contentType("application/json; token=SENTINEL_CT_PARAM"), message, OK, "8.5", SHOES_EVENT,
                        true),
                new Exchange("GET message.json", "GET", PATH, json(), message, OK, "8.5", SHOES_EVENT, true),
                new Exchange("HEAD message.json", "HEAD", PATH, json(), message, OK, "", SHOES_EVENT, true),
                new Exchange("OPTIONS message.json", "OPTIONS", PATH, json(), message, OK, "8.5", SHOES_EVENT, true),
                new Exchange("CORS pre-flight", "OPTIONS", PATH, preflight, null, SERVER_ERROR, notAnObject, null,
                        false),
                new Exchange("TRACE without a body", "TRACE", PATH, Map.of(), null, SERVER_ERROR, notAnObject, null,
                        false),
                new Exchange("form body", "POST", PATH, contentType("application/x-www-form-urlencoded"),
                        "password=SENTINEL_FORM&item+name=shoes".getBytes(US_ASCII), SERVER_ERROR, notJson, null,
                        false),
                new Exchange("unknown item", "POST", PATH, json(),
                        "{\"item name\":\"socks\",\"item units\":1,\"item price per unit\":10}".getBytes(UTF_8),
                        SERVER_ERROR, "{ \"message\": \"nil can't be coerced into Fixnum\" }",
                        "Business event Price {item name=socks, item units=1, item price per unit=10}", false),
                new Exchange("malformed JSON", "POST", PATH, json(), "{\"item name\": SENTINEL_BODY".getBytes(UTF_8),
                        SERVER_ERROR, notJson, null, false),
                // The 500 body quotes the rejected Content-Type, as it always has; the log must not.
                new Exchange("charset Spring cannot parse", "POST", PATH,
                        contentType("application/json; charset=bad[SENTINEL_CHARSET]"), message, SERVER_ERROR,
                        "{ \"message\": \"Invalid mime type \\\"application/json; charset=bad[SENTINEL_CHARSET]\\\": "
                                + "bad[SENTINEL_CHARSET]\" }", null, false),
                new Exchange("Content-Type without a subtype", "POST", PATH, contentType("SENTINEL_MIME"), message,
                        SERVER_ERROR,
                        "{ \"message\": \"Invalid mime type \\\"SENTINEL_MIME\\\": does not contain '/'\" }", null,
                        false));
    }

    /**
     * One request and what the endpoint answers it with.
     *
     * @param description  the case name
     * @param method       the request method
     * @param target       the request target, query string included
     * @param headers      the request headers besides the caller credentials every exchange sends
     * @param body         the request body, or {@code null} for none
     * @param statusLine   the expected status line
     * @param responseBody the expected response body
     * @param event        the business event line the flow logs, or {@code null} when it records none
     * @param priced       whether the flow computes the price and sets the business transaction id
     */
    record Exchange(String description, String method, String target, Map<String, String> headers, byte[] body,
                    String statusLine, String responseBody, String event, boolean priced) {

        Exchange {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("Authorization", "Bearer SENTINEL_TOKEN");
            all.put("Cookie", "session=SENTINEL_COOKIE");
            all.put("X-Secret", "SENTINEL_HDR");
            all.put("X-Api-Key", "SENTINEL_API_KEY");
            all.put("Proxy-Authorization", "Basic SENTINEL_PROXY");
            all.putAll(headers);
            headers = all;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    private static Map<String, String> json() {
        return contentType("application/json");
    }

    private static Map<String, String> contentType(String value) {
        return Map.of("Content-Type", value);
    }

    /** The bytes of the ported {@code message.json} (shoes, 2 units, price 10), unchanged. */
    private static byte[] message() throws IOException {
        try (InputStream in = RequestLoggingLevelsTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json not on test classpath");
            return in.readAllBytes();
        }
    }

    /**
     * The output captured after {@code marker}. {@link CapturedOutput#getAll()} joins the class capture, which holds
     * the context start and the earlier cases, with this case's own, so the case's lines are those after the last
     * occurrence of the marker it printed.
     */
    private static String since(CapturedOutput output, String marker) {
        String all = output.getAll();
        int at = all.lastIndexOf(marker);
        assertTrue(at >= 0, () -> "marker " + marker + " not captured");
        return all.substring(at + marker.length());
    }

    /** {@code text}'s ASCII bytes as {@code ObjectUtils.nullSafeToString} lists them: decimal, comma and space. */
    private static String decimalBytes(String text) {
        StringJoiner joined = new StringJoiner(", ");
        for (byte b : text.getBytes(US_ASCII)) {
            joined.add(Byte.toString(b));
        }
        return joined.toString();
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
