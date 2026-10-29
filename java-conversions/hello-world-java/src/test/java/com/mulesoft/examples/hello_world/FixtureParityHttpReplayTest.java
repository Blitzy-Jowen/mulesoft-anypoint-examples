package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.hello_world.support.HttpTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP side of the fixture replay (ST-2): the read budget of ST-1 it sends through
 * ({@link HttpTestClient#withReadTimeout}), and the static helpers by which {@code FixtureParityTest.Replay} reads
 * {@code request.readTimeoutMillis}, {@code state.clearCaches}, {@code state.requests} and the {@code tls} of a
 * request, builds ST-1's TLS client from that {@code tls} and empties the application's caches before the prior
 * requests and the input. The client factory is driven against stand-ins for ST-1 that record the {@code https}
 * overload it calls; that the key-store client presents its key to a listener requiring a client certificate is
 * proven by {@code FixtureParityTlsDriverTest} in oauth2-authorization-code-using-the-HTTP-connector-java, the
 * project that carries such a listener and its stores.
 *
 * <p>ST-1 is driven against {@link DelayedServer}, a loopback server on a free port that answers only after a delay,
 * so a budget shorter than the delay must give up before the reply and a longer one must read it. The replay's
 * helpers are called alone, on literal fixture JSON and on a plain Spring context, so none of the replay state
 * {@link FixtureParityTest} keeps for its own run is touched.
 */
class FixtureParityHttpReplayTest {

    private static final String REQUEST = "scenario: request";
    private static final String CLEAR_CACHES = "scenario: state.clearCaches";
    private static final String PRIOR_REQUESTS = "scenario: state.requests";
    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();
    /** Longer than any read budget below gives up after, so a reply never arrives before such a budget ends. */
    private static final Duration NO_TIMELY_REPLY = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    // ST-1's read budget

    @Test
    void theDefaultReadBudgetIsTheConnectTimeout() {
        assertEquals(HttpTestClient.TIMEOUT_MILLIS, new HttpTestClient(LOOPBACK, 1).readTimeoutMillis());
        assertEquals(10_000, HttpTestClient.TIMEOUT_MILLIS);
    }

    @Test
    void withReadTimeoutCopiesTheClientForTheSameEndpointAndLeavesItUnchanged() {
        HttpTestClient client = new HttpTestClient(LOOPBACK, 1);

        HttpTestClient patient = client.withReadTimeout(30_000);

        assertEquals(30_000, patient.readTimeoutMillis());
        assertEquals(client.baseUrl(), patient.baseUrl());
        assertEquals(HttpTestClient.TIMEOUT_MILLIS, client.readTimeoutMillis(), "the client itself is unchanged");
    }

    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsAReadBudgetThatIsNotPositive(int readTimeoutMillis) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new HttpTestClient(LOOPBACK, 1).withReadTimeout(readTimeoutMillis));

        assertEquals("read timeout must be a positive number of milliseconds: " + readTimeoutMillis,
                failure.getMessage());
    }

    @Test
    void aBudgetShorterThanTheServersDelayGivesUpBeforeTheReply() throws Exception {
        try (DelayedServer server = new DelayedServer(NO_TIMELY_REPLY)) {
            HttpTestClient client = new HttpTestClient(LOOPBACK, server.port()).withReadTimeout(200);

            assertGivesUpBeforeTheReply(() -> client.get("/delayed"));
        }
    }

    @Test
    void aBudgetLongerThanTheServersDelayReadsTheReply() throws Exception {
        try (DelayedServer server = new DelayedServer(Duration.ofMillis(500))) {
            HttpTestClient.Response reply =
                    new HttpTestClient(LOOPBACK, server.port()).withReadTimeout(5_000).get("/delayed");

            assertEquals("HTTP/1.1 200 OK", reply.statusLine());
            assertEquals("ok", reply.bodyAsString());
        }
    }

    @Test
    void theBudgetOfATlsCopyAppliesToItsHandshake(@TempDir Path directory) throws Exception {
        Path trustStore = emptyTrustStore(directory);
        try (DelayedServer server = new DelayedServer(NO_TIMELY_REPLY)) {
            HttpTestClient client = HttpTestClient.https(LOOPBACK, server.port(), trustStore.toString(), "PKCS12",
                    "changeit").withReadTimeout(200);

            assertEquals(new HttpTestClient(LOOPBACK, server.port()).baseUrl().replace("http://", "https://"),
                    client.baseUrl(), "the copy keeps TLS mode");
            // The server never sends its ServerHello, so the handshake's first read waits out the budget.
            assertGivesUpBeforeTheReply(() -> client.get("/delayed"));
        }
    }

    // request.readTimeoutMillis

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "null", value = {
            "'{}',                                   null",
            "'{\"readTimeoutMillis\": null}',        null",
            "'{\"readTimeoutMillis\": 1}',           1",
            "'{\"readTimeoutMillis\": 30000}',       30000",
            "'{\"readTimeoutMillis\": 2147483647}',  2147483647",
    })
    void readsAnOptionalReadBudget(String request, Integer expected) {
        assertEquals(expected, FixtureParityTest.Replay.readTimeoutMillis(json(request), REQUEST));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-1", "1.5", "2147483648", "\"30000\"", "true", "[]", "{}"})
    void rejectsAReadBudgetThatIsNotAPositiveWholeNumber(String value) {
        JsonNode request = json("{\"readTimeoutMillis\": " + value + "}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.readTimeoutMillis(request, REQUEST));

        assertEquals(REQUEST + ".readTimeoutMillis must be a positive whole number", failure.getMessage());
    }

    // state.clearCaches

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'{}',                          false",
            "'{\"clearCaches\": null}',     false",
            "'{\"clearCaches\": false}',    false",
            "'{\"clearCaches\": true}',     true",
    })
    void readsAnOptionalCacheClearing(String state, boolean expected) {
        assertEquals(expected, FixtureParityTest.Replay.clearsCaches(json(state), CLEAR_CACHES));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"true\"", "1", "0", "{}", "[]"})
    void rejectsACacheClearingThatIsNotABoolean(String value) {
        JsonNode state = json("{\"clearCaches\": " + value + "}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.clearsCaches(state, CLEAR_CACHES));

        assertEquals(CLEAR_CACHES + " must be true or false", failure.getMessage());
    }

    @Test
    void clearingEmptiesEveryCacheOfEveryCacheManagerAndKeepsTheCaches() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TwoCacheManagers.class)) {
            List<CacheManager> managers = List.copyOf(context.getBeansOfType(CacheManager.class).values());
            managers.forEach(manager -> manager.getCacheNames()
                    .forEach(cacheName -> manager.getCache(cacheName).put("10", "cached")));

            assertEquals(2, FixtureParityTest.Replay.clearCaches(context));

            for (CacheManager manager : managers) {
                for (String cacheName : manager.getCacheNames()) {
                    Cache cache = manager.getCache(cacheName);
                    assertNotNull(cache, cacheName + " still exists");
                    assertNull(cache.get("10"), cacheName + " holds no entry");
                }
            }
        }
    }

    @Test
    void clearingFindsNoCacheManagerInAContextWithoutOne() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();

            assertEquals(0, FixtureParityTest.Replay.clearCaches(context));
        }
    }

    // state.requests

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"requests\": null}", "{\"requests\": []}"})
    void readsNoPriorRequestWhenNoneIsListed(String state) {
        assertEquals(List.of(), FixtureParityTest.Replay.priorRequests(json(state), PRIOR_REQUESTS));
    }

    @Test
    void readsThePriorRequestsInTheirOrder() {
        JsonNode state = json("{\"requests\": ["
                + "{\"method\": \"GET\", \"path\": \"/fibonacci\", \"query\": \"n=10\","
                + " \"headers\": [[\"Accept\", \"*/*\"]]},"
                + "{\"method\": \"POST\", \"path\": \"/fibonacci\", \"query\": \"\","
                + " \"headers\": {\"Content-Type\": \"text/plain\"}, \"body\": \"x\","
                + " \"portProperty\": \"local.server.port\", \"tls\": {\"trustStore\": \"trust-store\"},"
                + " \"readTimeoutMillis\": 30000},"
                + "{\"method\": \"PUT\", \"path\": \"/\", \"bodyBase64\": \"AAE=\"}]}");

        List<JsonNode> priors = FixtureParityTest.Replay.priorRequests(state, PRIOR_REQUESTS);

        assertEquals(List.of(state.get("requests").get(0), state.get("requests").get(1), state.get("requests").get(2)),
                priors);
    }

    static Stream<Arguments> malformedPriorRequests() {
        return Stream.of(
                arguments("an object instead of an array", "{\"method\": \"GET\", \"path\": \"/\"}",
                        " must be a JSON array"),
                arguments("an element that is not an object", "[\"GET /fibonacci\"]", "[0] must be a JSON object"),
                arguments("a null element", "[null]", "[0] must be a JSON object"),
                arguments("no method", "[{\"path\": \"/\"}]", "[0].method must be a string"),
                arguments("a path that is not a string", "[{\"method\": \"GET\", \"path\": 1}]",
                        "[0].path must be a string"),
                arguments("a query that is not a string", "[{\"method\": \"GET\", \"path\": \"/\", \"query\": 1}]",
                        "[0].query must be a string"),
                arguments("headers that are not pairs",
                        "[{\"method\": \"GET\", \"path\": \"/\", \"headers\": [[\"Accept\"]]}]",
                        "[0].headers must hold [name, value] string pairs"),
                arguments("a body that is not a string", "[{\"method\": \"GET\", \"path\": \"/\", \"body\": 1}]",
                        "[0].body must be a string"),
                arguments("a bodyBase64 that is not Base64",
                        "[{\"method\": \"GET\", \"path\": \"/\", \"bodyBase64\": \"%%\"}]",
                        "[0].bodyBase64 is not valid Base64"),
                arguments("a portProperty that is not a string",
                        "[{\"method\": \"GET\", \"path\": \"/\", \"portProperty\": 8081}]",
                        "[0].portProperty must be a string"),
                arguments("tls without its trust store", "[{\"method\": \"GET\", \"path\": \"/\", \"tls\": {}}]",
                        "[0].tls.trustStore must be a string"),
                arguments("a tls key store password without its key store",
                        "[{\"method\": \"GET\", \"path\": \"/\","
                                + " \"tls\": {\"trustStore\": \"trust-store\", \"keyStorePassword\": \"p2\"}}]",
                        "[0].tls.keyStorePassword is set without keyStore"),
                arguments("a read budget of zero in the second request",
                        "[{\"method\": \"GET\", \"path\": \"/\"},"
                                + " {\"method\": \"GET\", \"path\": \"/\", \"readTimeoutMillis\": 0}]",
                        "[1].readTimeoutMillis must be a positive whole number"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedPriorRequests")
    void rejectsAMalformedPriorRequest(String description, String requests, String problem) {
        JsonNode state = json("{\"requests\": " + requests + "}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.priorRequests(state, PRIOR_REQUESTS));

        assertEquals(PRIOR_REQUESTS + problem, failure.getMessage());
    }

    // request.tls

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"tls\": null}"})
    void readsNoTlsWhenNoneIsGiven(String request) {
        assertNull(FixtureParityTest.Replay.tls(json(request), REQUEST));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"trustStore\": \"trust-store\"}",
            "{\"trustStore\": \"trust-store\", \"type\": \"PKCS12\", \"password\": \"p1\"}",
            "{\"trustStore\": \"trust-store\", \"keyStore\": \"keystore.jks\"}",
            "{\"trustStore\": \"trust-store\", \"type\": \"JKS\", \"password\": \"p1\", \"keyStore\": \"keystore.jks\","
                    + " \"keyStoreType\": \"PKCS12\", \"keyStorePassword\": \"p2\"}",
            "{\"trustStore\": \"trust-store\", \"type\": null, \"password\": null, \"keyStore\": null,"
                    + " \"keyStoreType\": null, \"keyStorePassword\": null}",
    })
    void acceptsATlsOfStringMembers(String tls) {
        JsonNode request = json("{\"tls\": " + tls + "}");

        assertSame(request.get("tls"), FixtureParityTest.Replay.tls(request, REQUEST));
    }

    static Stream<Arguments> malformedTls() {
        return Stream.of(
                arguments("a string instead of an object", "\"trust-store\"", " must be a JSON object"),
                arguments("an array instead of an object", "[\"trust-store\"]", " must be a JSON object"),
                arguments("no trust store", "{\"keyStore\": \"keystore.jks\"}", ".trustStore must be a string"),
                arguments("a trust store that is not a string", "{\"trustStore\": 1}", ".trustStore must be a string"),
                arguments("a type that is not a string", "{\"trustStore\": \"trust-store\", \"type\": true}",
                        ".type must be a string"),
                arguments("a password that is not a string", "{\"trustStore\": \"trust-store\", \"password\": 1}",
                        ".password must be a string"),
                arguments("a key store that is not a string", "{\"trustStore\": \"trust-store\", \"keyStore\": {}}",
                        ".keyStore must be a string"),
                arguments("a key store type that is not a string",
                        "{\"trustStore\": \"trust-store\", \"keyStore\": \"keystore.jks\", \"keyStoreType\": []}",
                        ".keyStoreType must be a string"),
                arguments("a key store password that is not a string",
                        "{\"trustStore\": \"trust-store\", \"keyStore\": \"keystore.jks\", \"keyStorePassword\": 1}",
                        ".keyStorePassword must be a string"),
                arguments("a key store type without a key store",
                        "{\"trustStore\": \"trust-store\", \"keyStoreType\": \"JKS\"}",
                        ".keyStoreType is set without keyStore"),
                arguments("a key store password without a key store",
                        "{\"trustStore\": \"trust-store\", \"keyStore\": null, \"keyStorePassword\": \"p2\"}",
                        ".keyStorePassword is set without keyStore"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedTls")
    void rejectsAMalformedTls(String description, String tls, String problem) {
        JsonNode request = json("{\"tls\": " + tls + "}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.tls(request, REQUEST));

        assertEquals(REQUEST + ".tls" + problem, failure.getMessage());
    }

    static Stream<Arguments> tlsClients() {
        return Stream.of(
                arguments("{\"trustStore\": \"<trust>\"}", "https(localhost, 8443, <trust>, JKS, null)"),
                arguments("{\"trustStore\": \"<trust>\", \"type\": \"PKCS12\", \"password\": \"p1\"}",
                        "https(localhost, 8443, <trust>, PKCS12, p1)"),
                arguments("{\"trustStore\": \"<trust>\", \"keyStore\": \"<key>\"}",
                        "https(localhost, 8443, <trust>, JKS, null, <key>, JKS, null)"),
                arguments("{\"trustStore\": \"<trust>\", \"type\": \"PKCS12\", \"password\": \"p1\","
                                + " \"keyStore\": \"<key>\", \"keyStorePassword\": \"p2\"}",
                        "https(localhost, 8443, <trust>, PKCS12, p1, <key>, PKCS12, p2)"),
                arguments("{\"trustStore\": \"<trust>\", \"type\": \"PKCS12\", \"keyStore\": \"<key>\","
                                + " \"keyStoreType\": \"JKS\"}",
                        "https(localhost, 8443, <trust>, PKCS12, null, <key>, JKS, null)"));
    }

    /**
     * The client factory hands each {@code tls} member to the {@code https} overload it belongs to: the trust-store
     * overload without {@code keyStore}, the key-store overload with it, {@code keyStoreType} defaulting to the
     * effective {@code type} and every store resolved to the file ST-1 opens.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tlsClients")
    void theClientFactoryCallsTheOverloadTheTlsNames(String tls, String expectedCall, @TempDir Path directory)
            throws Exception {
        Path trustStore = Files.createFile(directory.resolve("trust.store"));
        Path keyStore = Files.createFile(directory.resolve("key.store"));
        JsonNode spec = json("{\"tls\": " + tls.replace("\"<trust>\"", JSON.writeValueAsString(trustStore.toString()))
                .replace("\"<key>\"", JSON.writeValueAsString(keyStore.toString())) + "}");

        Object client = FixtureParityTest.Replay.httpsClient(RecordingClient.class, 8443,
                FixtureParityTest.Replay.tls(spec, REQUEST), REQUEST + ".tls");

        assertEquals(expectedCall.replace("<trust>", trustStore.toString()).replace("<key>", keyStore.toString()),
                client);
    }

    @Test
    void theClientFactoryNamesAKeyStoreThatIsNeitherOnTheClasspathNorAFile(@TempDir Path directory) throws Exception {
        Path trustStore = Files.createFile(directory.resolve("trust.store"));
        String missing = directory.resolve("absent.store").toString();
        JsonNode spec = json("{\"tls\": {\"trustStore\": " + JSON.writeValueAsString(trustStore.toString())
                + ", \"keyStore\": " + JSON.writeValueAsString(missing) + "}}");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FixtureParityTest.Replay.httpsClient(RecordingClient.class, 8443,
                        FixtureParityTest.Replay.tls(spec, REQUEST), REQUEST + ".tls"));

        assertEquals(REQUEST + ".tls.keyStore " + missing + " is neither on the test classpath nor a file",
                failure.getMessage());
    }

    static Stream<Arguments> clientClasses() {
        return Stream.of(arguments(HttpTestClient.class, true), arguments(TrustOnlyClient.class, false),
                arguments(InstanceKeyStoreClient.class, false));
    }

    /** Only a public static key-store overload of {@code https} replays a {@code tls} that names a key store. */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("clientClasses")
    void detectsTheKeyStoreOverloadOfTheClient(Class<?> clientClass, boolean presentsClientKeys) {
        assertEquals(presentsClientKeys, FixtureParityTest.Replay.presentsClientKeys(clientClass));
    }

    // helpers

    /** The call fails on a read timeout well before the server would have answered. */
    private static void assertGivesUpBeforeTheReply(Executable call) {
        long started = System.nanoTime();
        UncheckedIOException failure = assertThrows(UncheckedIOException.class, call);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertInstanceOf(SocketTimeoutException.class, failure.getCause(), failure::getMessage);
        assertTrue(elapsedMillis < NO_TIMELY_REPLY.toMillis(),
                "the read gave up after its budget, not after the server's delay: " + elapsedMillis + " ms");
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not JSON: " + text, e);
        }
    }

    /** A PKCS12 trust store without any certificate: TLS mode builds from it, and no server can be trusted. */
    private static Path emptyTrustStore(Path directory) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        Path file = directory.resolve("empty-trust-store.p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, "changeit".toCharArray());
        }
        return file;
    }

    /** A stand-in for ST-1 whose two {@code https} overloads return the call they received, written out. */
    static final class RecordingClient {

        private RecordingClient() {
        }

        public static String https(String host, int port, String trustStorePath, String trustStoreType,
                String trustStorePassword) {
            return "https(" + String.join(", ", host, String.valueOf(port), trustStorePath, trustStoreType,
                    String.valueOf(trustStorePassword)) + ")";
        }

        public static String https(String host, int port, String trustStorePath, String trustStoreType,
                String trustStorePassword, String keyStorePath, String keyStoreType, String keyStorePassword) {
            return "https(" + String.join(", ", host, String.valueOf(port), trustStorePath, trustStoreType,
                    String.valueOf(trustStorePassword), keyStorePath, keyStoreType,
                    String.valueOf(keyStorePassword)) + ")";
        }
    }

    /** An ST-1 copy from before the key-store overload: it has only the trust-store {@code https}. */
    static final class TrustOnlyClient {

        private TrustOnlyClient() {
        }

        public static TrustOnlyClient https(String host, int port, String trustStorePath, String trustStoreType,
                String trustStorePassword) {
            return new TrustOnlyClient();
        }
    }

    /** A client whose key-store {@code https} is an instance method, which no replay can call as a factory. */
    static final class InstanceKeyStoreClient {

        public InstanceKeyStoreClient https(String host, int port, String trustStorePath, String trustStoreType,
                String trustStorePassword, String keyStorePath, String keyStoreType, String keyStorePassword) {
            return this;
        }
    }

    /** Two cache managers, as a context with a composite or a second cache manager holds them. */
    @Configuration
    static class TwoCacheManagers {

        @Bean
        CacheManager fibonacciCacheManager() {
            return new ConcurrentMapCacheManager("fibonacci", "results");
        }

        @Bean
        CacheManager lookupCacheManager() {
            return new ConcurrentMapCacheManager("lookups");
        }
    }

    /**
     * A loopback server on a free port that answers each request {@code delay} after the empty line ending its head,
     * with {@code 200 OK} and the body {@code ok}. A connection that ends before a complete head, as a TLS client's
     * does, gets no answer.
     */
    private static final class DelayedServer implements AutoCloseable {

        private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};
        private static final byte[] REPLY =
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok"
                        .getBytes(StandardCharsets.US_ASCII);

        private final ServerSocket socket;
        private final Duration delay;
        private final Thread thread;

        DelayedServer(Duration delay) throws IOException {
            this.delay = delay;
            this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "delayed-http-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket connection = socket.accept()) {
                    if (readHead(connection.getInputStream())) {
                        Thread.sleep(delay.toMillis());
                        OutputStream out = connection.getOutputStream();
                        out.write(REPLY);
                        out.flush();
                    }
                } catch (IOException e) {
                    // The client gave up and closed the connection first, or close() closed the socket; the loop
                    // condition decides whether another connection is accepted.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Reads through the empty line ending the request head; {@code false} when the connection ends first. */
        private static boolean readHead(InputStream in) throws IOException {
            int matched = 0;
            int b;
            while ((b = in.read()) != -1) {
                matched = b == HEAD_END[matched] ? matched + 1 : b == HEAD_END[0] ? 1 : 0;
                if (matched == HEAD_END.length) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            socket.close();
            thread.interrupt();
            try {
                thread.join(NO_TIMELY_REPLY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
