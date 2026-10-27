package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.service_orchestration_and_choice_routing.exception.GlobalExceptionHandler;
import com.mulesoft.examples.service_orchestration_and_choice_routing.service.AjaxChannelBroker;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.HttpTestClient;

/**
 * Wire-level test of SC-12's {@link BayeuxLongPollingController}: real Bayeux exchanges on Undertow, posted as
 * {@code application/x-www-form-urlencoded} {@code message=<JSON array>} exactly as {@code dojox.cometd} sends them,
 * through ST-1 {@link HttpTestClient}, so status lines, headers and body bytes are asserted as written.
 *
 * <p>The test is project-neutral and travels with the controller: it registers its own inbound channel
 * {@value #ECHO_CHANNEL} on the real {@link AjaxChannelBroker} and uses no project channel. The context
 * ({@link WireConfig}) is the controller, the broker and SC-1 on a random port with {@code ajax.connect-timeout-ms}
 * lowered to {@value #CONNECT_TIMEOUT_MS} ms, so a held connect returns quickly.
 */
@SpringBootTest(classes = BayeuxLongPollingControllerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=" + BayeuxLongPollingControllerTest.LOOPBACK,
                "ajax.connect-timeout-ms=" + BayeuxLongPollingControllerTest.CONNECT_TIMEOUT_MS})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BayeuxLongPollingControllerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    static final long CONNECT_TIMEOUT_MS = 2000;

    /** Inbound channel handled by the test's own handler, which answers {@code echo:<data>}. */
    static final String ECHO_CHANNEL = "/test/echo";

    /** Channel without a handler: publications reach its subscribers. */
    private static final String PLAIN_CHANNEL = "/test/plain";

    private static final String COMETD = "/ajax/cometd";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON_CONTENT_TYPE = "text/json;charset=UTF-8";
    private static final String JSONP_CONTENT_TYPE = "text/javascript;charset=UTF-8";

    /** Upper bound for an answer that must not wait for the connect timeout. */
    private static final long PROMPT_MS = 1500;

    private static final String HANDSHAKE = "{\"channel\":\"/meta/handshake\",\"version\":\"1.0\","
            + "\"minimumVersion\":\"0.9\",\"supportedConnectionTypes\":[\"long-polling\",\"callback-polling\"],"
            + "\"id\":\"0\"}";

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private AjaxChannelBroker broker;

    @BeforeAll
    void registerEchoHandler() {
        broker.registerInbound(ECHO_CHANNEL, data -> "echo:" + data);
    }

    @Test
    void handshakeRegistersAClientAndAdvertisesLongPolling() throws Exception {
        HttpTestClient.Response response = post("[" + HANDSHAKE + "]");

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(JSON_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        assertTrue(response.bodyAsString().startsWith("["), response::toString);
        JsonNode reply = single(json.readTree(response.body()));
        assertEquals("/meta/handshake", reply.path("channel").asText());
        assertTrue(reply.path("successful").asBoolean(), reply::toString);
        assertEquals("1.0", reply.path("version").asText());
        assertTrue(reply.path("supportedConnectionTypes").toString().contains("\"long-polling\""), reply::toString);
        assertTrue(broker.isKnownClient(reply.path("clientId").asText()), reply::toString);
        assertEquals("retry", reply.path("advice").path("reconnect").asText());
        assertEquals(0, reply.path("advice").path("interval").asInt());
        assertEquals(CONNECT_TIMEOUT_MS, reply.path("advice").path("timeout").asLong());
        assertEquals("0", reply.path("id").asText());
    }

    @Test
    void rpcReplyReleasesTheHeldConnect() throws Exception {
        String clientId = handshake();
        String replyTo = ECHO_CHANNEL + "#" + clientId;

        JsonNode subscribed = single(bayeux(subscribe(clientId, "\"" + replyTo + "\"", "1")));
        assertEquals("/meta/subscribe", subscribed.path("channel").asText());
        assertEquals(clientId, subscribed.path("clientId").asText());
        assertEquals(replyTo, subscribed.path("subscription").asText());
        assertTrue(subscribed.path("successful").asBoolean(), subscribed::toString);
        assertEquals("1", subscribed.path("id").asText());

        long firstStart = System.nanoTime();
        JsonNode first = single(bayeux(connect(clientId, "2")));
        assertTrue(elapsedMs(firstStart) < PROMPT_MS, "first connect must be answered at once");
        assertEquals("/meta/connect", first.path("channel").asText());
        assertTrue(first.path("successful").asBoolean(), first::toString);

        CompletableFuture<JsonNode> held = CompletableFuture.supplyAsync(() -> bayeux(connect(clientId, "3")));
        // Give the second connect time to reach the broker's wait before the reply is produced.
        Thread.sleep(300);
        assertFalse(held.isDone(), "the second connect must be held");

        JsonNode ack = single(bayeux(publish(clientId, ECHO_CHANNEL,
                "{\"data\":\"x\",\"replyTo\":\"" + replyTo + "\"}", "5")));
        long publishedAt = System.nanoTime();
        assertEquals(ECHO_CHANNEL, ack.path("channel").asText());
        assertTrue(ack.path("successful").asBoolean(), ack::toString);
        assertEquals("5", ack.path("id").asText());
        assertFalse(ack.has("data"), "a publication's acknowledgement must carry no data");

        JsonNode released = held.get(CONNECT_TIMEOUT_MS + BayeuxLongPollingController.HOLD_GRACE_MS,
                TimeUnit.MILLISECONDS);
        assertTrue(elapsedMs(publishedAt) < PROMPT_MS, "the reply must release the held connect");
        assertEquals(2, released.size(), released::toString);
        assertEquals("/meta/connect", released.get(0).path("channel").asText());
        assertTrue(released.get(0).path("successful").asBoolean(), released::toString);
        assertEquals(replyTo, released.get(1).path("channel").asText());
        assertEquals("echo:x", released.get(1).path("data").asText());
        assertTrue(released.get(1).path("id").isTextual(), released::toString);
    }

    @Test
    void firstConnectDrainsQueuedDeliveriesWithTheirDataUnchanged() throws Exception {
        String clientId = handshake();
        single(bayeux(subscribe(clientId, "\"" + PLAIN_CHANNEL + "\"", "1")));
        String data = "{\"n\":1,\"list\":[true,null,\"s\"],\"text\":\"a\u2028b\"}";
        JsonNode ack = single(bayeux(publish(clientId, PLAIN_CHANNEL, data, "2")));
        assertTrue(ack.path("successful").asBoolean(), ack::toString);

        long start = System.nanoTime();
        HttpTestClient.Response response = post(connect(clientId, "3"));

        assertTrue(elapsedMs(start) < PROMPT_MS, "first connect must be answered at once");
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        String body = response.bodyAsString();
        assertTrue(body.contains("a\\u2028b") && body.indexOf('\u2028') < 0,
                "U+2028 must be escaped for the client's eval: " + body);
        JsonNode messages = json.readTree(body);
        assertEquals(2, messages.size(), body);
        assertEquals("/meta/connect", messages.get(0).path("channel").asText());
        assertEquals(PLAIN_CHANNEL, messages.get(1).path("channel").asText());
        assertEquals(json.readTree(data), messages.get(1).path("data"));
    }

    @Test
    void connectWithNothingQueuedReturnsAfterTheTimeoutWithItsReplyAlone() throws Exception {
        String clientId = handshake();
        single(bayeux(connect(clientId, "1")));

        long start = System.nanoTime();
        JsonNode messages = bayeux(connect(clientId, "2"));
        long elapsed = elapsedMs(start);

        assertTrue(elapsed >= CONNECT_TIMEOUT_MS, "held for " + elapsed + " ms");
        assertTrue(elapsed < CONNECT_TIMEOUT_MS + BayeuxLongPollingController.HOLD_GRACE_MS,
                "held for " + elapsed + " ms");
        JsonNode reply = single(messages);
        assertEquals("/meta/connect", reply.path("channel").asText());
        assertTrue(reply.path("successful").asBoolean(), reply::toString);
        assertEquals("2", reply.path("id").asText());
    }

    static Stream<Arguments> messagesOfUnknownClients() {
        return Stream.of(
                Arguments.of("{\"channel\":\"/meta/connect\",\"clientId\":\"unknown\",\"id\":\"1\"}", false),
                Arguments.of("{\"channel\":\"/meta/connect\",\"id\":\"1\"}", false),
                Arguments.of("{\"channel\":\"/meta/subscribe\",\"subscription\":\"/test/x\",\"clientId\":\"unknown\","
                        + "\"id\":\"1\"}", true),
                Arguments.of("{\"channel\":\"" + ECHO_CHANNEL + "\",\"data\":\"x\",\"clientId\":\"unknown\","
                        + "\"id\":\"1\"}", false));
    }

    @ParameterizedTest
    @MethodSource("messagesOfUnknownClients")
    void unknownClientIsSentBackToTheHandshakeAtOnce(String message, boolean hasSubscription) throws Exception {
        long start = System.nanoTime();
        JsonNode reply = single(bayeux("[" + message + "]"));

        assertTrue(elapsedMs(start) < PROMPT_MS, "an unknown client's message is never held");
        assertFalse(reply.path("successful").asBoolean(), reply::toString);
        assertEquals("402::Unknown client", reply.path("error").asText());
        assertEquals("handshake", reply.path("advice").path("reconnect").asText());
        assertEquals(0, reply.path("advice").path("interval").asInt());
        assertEquals("1", reply.path("id").asText());
        assertEquals(hasSubscription, reply.has("subscription"), reply::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "other=1", "message=", "message=%7B", "message=42", "message=%22text%22",
            "message=null", "message=%5B1%5D", "message=%5B%7B%7D%2C2%5D"})
    void malformedRequestIsAnswered400WithAnEmptyArray(String form) {
        HttpTestClient.Response response = client().post(COMETD, FORM, form);

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(JSON_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        assertEquals("[]", response.bodyAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cb", "dojo.io.script.jsonp_dojoIoScript1._jsonpCallback"})
    void callbackPollingWrapsTheArrayInTheCallback(String callback) throws Exception {
        HttpTestClient.Response response = client().get(COMETD + "?message=" + encode("[" + HANDSHAKE + "]")
                + "&jsonp=" + callback);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(JSONP_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        String body = response.bodyAsString();
        assertTrue(body.startsWith(callback + "([") && body.endsWith("])"), body);
        JsonNode reply = single(json.readTree(body.substring(callback.length() + 1, body.length() - 1)));
        assertTrue(reply.path("successful").asBoolean(), body);
    }

    @Test
    void callbackThatIsNotAFunctionReferenceIsRefused() {
        HttpTestClient.Response response = client().get(COMETD + "?message=" + encode("[" + HANDSHAKE + "]")
                + "&jsonp=" + encode("alert(1)//"));

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(JSON_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        assertEquals("[]", response.bodyAsString());
    }

    @Test
    void methodOtherThanGetOrPostGetsTheListener405() {
        HttpTestClient.Response response = client().put(COMETD, FORM, "message=" + encode("[" + HANDSHAKE + "]"));

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertEquals("Method not allowed for endpoint: " + COMETD, response.bodyAsString());
    }

    @Test
    void singleMessageObjectIsABatchOfOneAndItsIdIsEchoedUnchanged() throws Exception {
        JsonNode reply = single(bayeux("{\"channel\":\"/meta/handshake\",\"id\":7}"));

        assertTrue(reply.path("successful").asBoolean(), reply::toString);
        assertTrue(reply.path("id").isInt() && reply.path("id").asInt() == 7, reply::toString);
    }

    @Test
    void metaChannelsActOnTheBrokerAndReportFailuresInBand() throws Exception {
        String clientId = handshake();

        JsonNode unknownMeta = single(bayeux("[{\"channel\":\"/meta/ping\",\"clientId\":\"" + clientId
                + "\",\"id\":\"1\"}]"));
        assertFalse(unknownMeta.path("successful").asBoolean(), unknownMeta::toString);
        assertEquals("404::Unknown meta channel", unknownMeta.path("error").asText());
        assertEquals("1", unknownMeta.path("id").asText());

        JsonNode missing = single(bayeux("[{\"channel\":\"/meta/subscribe\",\"clientId\":\"" + clientId
                + "\",\"id\":\"2\"}]"));
        assertFalse(missing.path("successful").asBoolean(), missing::toString);
        assertTrue(missing.path("error").asText().contains("subscription"), missing::toString);

        String channels = "[\"" + PLAIN_CHANNEL + "\",\"/test/other\"]";
        JsonNode subscribed = single(bayeux(subscribe(clientId, channels, "3")));
        assertTrue(subscribed.path("successful").asBoolean(), subscribed::toString);
        assertEquals(json.readTree(channels), subscribed.path("subscription"));

        JsonNode unsubscribed = single(bayeux("[{\"channel\":\"/meta/unsubscribe\",\"subscription\":" + channels
                + ",\"clientId\":\"" + clientId + "\",\"id\":\"4\"}]"));
        assertTrue(unsubscribed.path("successful").asBoolean(), unsubscribed::toString);
        assertEquals(json.readTree(channels), unsubscribed.path("subscription"));

        JsonNode disconnected = single(bayeux("[{\"channel\":\"/meta/disconnect\",\"clientId\":\"" + clientId
                + "\",\"id\":\"5\"}]"));
        assertTrue(disconnected.path("successful").asBoolean(), disconnected::toString);
        assertFalse(broker.isKnownClient(clientId));
        JsonNode afterDisconnect = single(bayeux(connect(clientId, "6")));
        assertEquals("402::Unknown client", afterDisconnect.path("error").asText());
    }

    private String handshake() {
        return single(bayeux("[" + HANDSHAKE + "]")).path("clientId").asText();
    }

    private static String connect(String clientId, String id) {
        return "[{\"channel\":\"/meta/connect\",\"connectionType\":\"long-polling\",\"clientId\":\"" + clientId
                + "\",\"id\":\"" + id + "\"}]";
    }

    private static String subscribe(String clientId, String subscriptionJson, String id) {
        return "[{\"channel\":\"/meta/subscribe\",\"subscription\":" + subscriptionJson + ",\"clientId\":\""
                + clientId + "\",\"id\":\"" + id + "\"}]";
    }

    private static String publish(String clientId, String channel, String dataJson, String id) {
        return "[{\"data\":" + dataJson + ",\"channel\":\"" + channel + "\",\"clientId\":\"" + clientId
                + "\",\"id\":\"" + id + "\"}]";
    }

    /** Posts a batch, asserts the plain-JSON 200 framing and returns the parsed array. */
    private JsonNode bayeux(String messages) {
        HttpTestClient.Response response = post(messages);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(JSON_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        try {
            JsonNode array = json.readTree(response.body());
            assertTrue(array.isArray(), response::toString);
            return array;
        } catch (IOException e) {
            throw new AssertionError("Response is not JSON: " + response, e);
        }
    }

    private HttpTestClient.Response post(String messages) {
        return client().post(COMETD, FORM, "message=" + encode(messages));
    }

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }

    private static JsonNode single(JsonNode array) {
        assertEquals(1, array.size(), array::toString);
        return array.get(0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow with Jackson, the controller, the broker
     * and SC-1. It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds no project service, broker connection, database or SOAP endpoint, and leaves out SC-10's listener
     * routing, which would confine {@code /ajax/**} to the Ajax port; and it is not a {@code @SpringBootConfiguration},
     * so the project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({BayeuxLongPollingController.class, AjaxChannelBroker.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }
}
