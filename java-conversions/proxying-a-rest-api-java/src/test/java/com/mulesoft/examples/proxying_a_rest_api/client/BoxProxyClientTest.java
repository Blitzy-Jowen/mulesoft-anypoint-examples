/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * The contract of {@link BoxProxyClient}, the original {@code http:request} with {@code parseResponse="false"}
 * against a WireMock stand-in for {@code https://api.box.com:443}: the method, the raw path and query, the given
 * headers and the body bytes reach the upstream unchanged; every upstream status comes back as a value with its
 * headers and unparsed body; redirects are followed; a timeout fails once, within the configured limit, and is
 * never retried; and the TLS key store keeps the original's inverted key names, {@code keystore.key} being the
 * store password and {@code keystore.password} the private-key password.
 */
class BoxProxyClientTest {

    /** Timeout of the timeout case; the other cases use a generous one so that a slow host cannot fail them. */
    private static final long TIMEOUT_MS = 500;

    /** The production default of {@code proxy.response-timeout-ms}, the Mule default response timeout. */
    private static final long RESPONSE_TIMEOUT_MS = 10_000;

    private static final String STORE_PASSWORD = "test-store-password";

    private static final String KEY_PASSWORD = "test-key-password";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    static Path tempDir;

    /** A JKS store whose key password differs from its store password; PKCS12 would ignore a distinct one. */
    private static Path keystoreFile;

    @BeforeAll
    static void generateJksKeystore() throws Exception {
        keystoreFile = tempDir.resolve("keystore.jks");
        Path log = tempDir.resolve("keytool.log");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool").toString(),
                "-genkeypair", "-alias", "mule", "-keyalg", "RSA", "-storetype", "JKS", "-dname", "CN=test",
                "-keystore", keystoreFile.toString(), "-storepass", STORE_PASSWORD, "-keypass", KEY_PASSWORD,
                "-noprompt")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        keytool.getOutputStream().close();
        boolean finished = keytool.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            keytool.destroyForcibly();
        }
        assertThat(finished).as("keytool finished within 60 s; output: %s", Files.readString(log)).isTrue();
        assertThat(keytool.exitValue()).as("keytool exit status; output: %s", Files.readString(log)).isZero();
    }

    private static BoxProxyClient httpClient() {
        return httpClient(RESPONSE_TIMEOUT_MS);
    }

    private static BoxProxyClient httpClient(long timeoutMs) {
        return new BoxProxyClient(new DefaultResourceLoader(), wm.baseUrl(), timeoutMs, "", "", "");
    }

    /** Bytes that are not valid UTF-8, so any decoding or re-encoding on the way changes them. */
    private static byte[] binaryPayload() {
        return new byte[] {0x00, (byte) 0xFF, 0x10, 0x7F, (byte) 0x80, 0x0A};
    }

    private static String keystoreUri() {
        return keystoreFile.toUri().toString();
    }

    @Test
    void rawPathAndQueryAreSentVerbatimWithoutReEncoding() {
        wm.stubFor(get(urlEqualTo("/2.0/search?query=a%20b&type=file")).willReturn(ok("{\"entries\":[]}")));
        wm.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok("{\"id\":\"0\"}")));

        try (BoxProxyClient client = httpClient()) {
            ResponseEntity<byte[]> search = client.exchange(HttpMethod.GET, "/2.0/search", "query=a%20b&type=file",
                    new HttpHeaders(), null);
            ResponseEntity<byte[]> withoutQuery =
                    client.exchange(HttpMethod.GET, "/2.0/folders/0", null, new HttpHeaders(), null);
            ResponseEntity<byte[]> emptyQuery =
                    client.exchange(HttpMethod.GET, "/2.0/folders/0", "", new HttpHeaders(), null);

            assertThat(search.getStatusCode().value()).isEqualTo(200);
            assertThat(search.getBody()).isEqualTo("{\"entries\":[]}".getBytes(UTF_8));
            assertThat(withoutQuery.getStatusCode().value()).isEqualTo(200);
            assertThat(emptyQuery.getStatusCode().value()).isEqualTo(200);
        }
        wm.verify(1, getRequestedFor(urlEqualTo("/2.0/search?query=a%20b&type=file")));
        // An exact URL without "?": neither a null nor an empty query adds one.
        wm.verify(2, getRequestedFor(urlEqualTo("/2.0/folders/0")));
    }

    // The caller's Authorization reaches Box unchanged: the proxy forwards the caller's identity, never its own.
    @Test
    void givenHeadersReachTheUpstreamUnchanged() {
        wm.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok()));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        headers.add("X-Custom", "custom-value");

        try (BoxProxyClient client = httpClient()) {
            client.exchange(HttpMethod.GET, "/2.0/folders/0", null, headers, null);
        }

        wm.verify(1, getRequestedFor(urlEqualTo("/2.0/folders/0"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer abc"))
                .withHeader("X-Custom", equalTo("custom-value")));
    }

    static Stream<Arguments> bodiesToForward() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/2.0/folders", MediaType.APPLICATION_JSON_VALUE,
                        "{\"name\":\"Projects\",\"parent\":{\"id\":\"0\"}}".getBytes(UTF_8)),
                Arguments.of(HttpMethod.PUT, "/2.0/files/1", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        binaryPayload()));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("bodiesToForward")
    void bodyIsForwardedByteForByteWithItsContentType(HttpMethod method, String path, String contentType,
                                                       byte[] body) {
        ResponseEntity<byte[]> response = sendWithBody(method, path, contentType, body);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo("created".getBytes(UTF_8));
        wm.verify(1, requestedFor(method.name(), urlEqualTo(path))
                .withRequestBody(binaryEqualTo(body))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(contentType)));
    }

    // A streamed body without a declared length would go out chunked, which the original never did.
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("bodiesToForward")
    void bodyIsSentWithItsExactContentLengthAndUnchunked(HttpMethod method, String path, String contentType,
                                                          byte[] body) {
        sendWithBody(method, path, contentType, body);

        wm.verify(1, requestedFor(method.name(), urlEqualTo(path))
                .withHeader(HttpHeaders.CONTENT_LENGTH, equalTo(String.valueOf(body.length)))
                .withoutHeader(HttpHeaders.TRANSFER_ENCODING));
    }

    // The byte-array message converter would add application/octet-stream to a request the caller sent without one.
    @Test
    void noContentTypeIsAddedToABodyWithoutOne() {
        wm.stubFor(post(urlEqualTo("/2.0/files/content")).willReturn(aResponse().withStatus(201)));
        byte[] body = binaryPayload();

        try (BoxProxyClient client = httpClient()) {
            client.exchange(HttpMethod.POST, "/2.0/files/content", null, new HttpHeaders(), body);
        }

        wm.verify(1, postRequestedFor(urlEqualTo("/2.0/files/content"))
                .withRequestBody(binaryEqualTo(body))
                .withoutHeader(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void getWithoutBodySendsNoBody() {
        wm.stubFor(get(urlEqualTo("/2.0/users/me")).willReturn(ok("{\"type\":\"user\"}")));

        try (BoxProxyClient client = httpClient()) {
            client.exchange(HttpMethod.GET, "/2.0/users/me", null, new HttpHeaders(), null);
        }

        List<LoggedRequest> requests = wm.findAll(getRequestedFor(urlEqualTo("/2.0/users/me")));
        assertThat(requests).hasSize(1);
        LoggedRequest sent = requests.get(0);
        assertThat(sent.getBody()).isEmpty();
        assertThat(sent.containsHeader(HttpHeaders.TRANSFER_ENCODING)).isFalse();
        // Either framing sends no body; which one a GET gets is BoxProxyClientTransportTest's concern.
        assertThat(sent.getHeader(HttpHeaders.CONTENT_LENGTH)).isIn(null, "0");
    }

    @Test
    void upstreamErrorStatusesAreReturnedAsValuesWithHeadersAndBody() {
        byte[] notFound = "{\"type\":\"error\",\"status\":404}".getBytes(UTF_8);
        byte[] serverError = binaryPayload();
        wm.stubFor(get(urlEqualTo("/2.0/folders/404")).willReturn(aResponse().withStatus(404)
                .withHeader("X-Upstream", "box").withBody(notFound)));
        wm.stubFor(get(urlEqualTo("/2.0/files/1/content")).willReturn(aResponse().withStatus(500)
                .withHeader("X-Upstream", "box").withBody(serverError)));

        try (BoxProxyClient client = httpClient()) {
            ResponseEntity<byte[]> missing =
                    client.exchange(HttpMethod.GET, "/2.0/folders/404", null, new HttpHeaders(), null);
            ResponseEntity<byte[]> failed =
                    client.exchange(HttpMethod.GET, "/2.0/files/1/content", null, new HttpHeaders(), null);

            assertThat(missing.getStatusCode().value()).isEqualTo(404);
            assertThat(missing.getBody()).containsExactly(notFound);
            assertThat(missing.getHeaders().getFirst("X-Upstream")).isEqualTo("box");
            assertThat(failed.getStatusCode().value()).isEqualTo(500);
            assertThat(failed.getBody()).containsExactly(serverError);
            assertThat(failed.getHeaders().getFirst("X-Upstream")).isEqualTo("box");
        }
    }

    // GET, because a 301 or 302 to a POST continues as a GET.
    @Test
    void redirectIsFollowedToTheFinalResponse() {
        wm.stubFor(get(urlEqualTo("/2.0/folders/old")).willReturn(aResponse().withStatus(302)
                .withHeader(HttpHeaders.LOCATION, wm.baseUrl() + "/2.0/folders/new")));
        wm.stubFor(get(urlEqualTo("/2.0/folders/new")).willReturn(ok("{\"id\":\"new\"}")));

        try (BoxProxyClient client = httpClient()) {
            ResponseEntity<byte[]> response =
                    client.exchange(HttpMethod.GET, "/2.0/folders/old", null, new HttpHeaders(), null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("{\"id\":\"new\"}".getBytes(UTF_8));
        }
        wm.verify(1, getRequestedFor(urlEqualTo("/2.0/folders/old")));
        wm.verify(1, getRequestedFor(urlEqualTo("/2.0/folders/new")));
    }

    // A timed-out call is never re-sent: the upstream may have acted on it.
    @Test
    void timeoutFailsWithinTheConfiguredLimitAndIsNotRetried() {
        wm.stubFor(get(urlEqualTo("/2.0/slow")).willReturn(ok().withFixedDelay(2000)));

        try (BoxProxyClient client = httpClient(TIMEOUT_MS)) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.exchange(HttpMethod.GET, "/2.0/slow", null, new HttpHeaders(), null))
                    .isInstanceOf(ResourceAccessException.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).as("elapsed until the %d ms timeout fired", TIMEOUT_MS).isLessThan(1900);
            // Past the stub's delay, so a retry would have been journaled by now.
            await().pollDelay(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(6))
                    .untilAsserted(() -> wm.verify(1, getRequestedFor(urlEqualTo("/2.0/slow"))));
        }
    }

    // As the original tls:key-store maps them: password="${keystore.key}", keyPassword="${keystore.password}".
    @Test
    void keystoreLoadsWithStorePasswordAsKeystoreKeyAndKeyPasswordAsKeystorePassword() {
        wm.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok()));

        assertThatCode(() -> {
            try (BoxProxyClient client = new BoxProxyClient(new DefaultResourceLoader(), wm.baseUrl(),
                    RESPONSE_TIMEOUT_MS, keystoreUri(), STORE_PASSWORD, KEY_PASSWORD)) {
                assertThat(client.exchange(HttpMethod.GET, "/2.0/folders/0", null, new HttpHeaders(), null)
                        .getStatusCode().value()).isEqualTo(200);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void swappedKeystorePasswordsFailNamingKeystorePath() {
        assertThatThrownBy(() -> new BoxProxyClient(new DefaultResourceLoader(), wm.baseUrl(), RESPONSE_TIMEOUT_MS,
                keystoreUri(), KEY_PASSWORD, STORE_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keystore.path")
                .hasMessageNotContaining(STORE_PASSWORD)
                .hasMessageNotContaining(KEY_PASSWORD);
    }

    @Test
    void missingKeystoreFileFailsNamingKeystorePath() {
        String missing = tempDir.resolve("missing.jks").toUri().toString();

        assertThatThrownBy(() -> new BoxProxyClient(new DefaultResourceLoader(), wm.baseUrl(), RESPONSE_TIMEOUT_MS,
                missing, STORE_PASSWORD, KEY_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keystore.path")
                .hasMessageContaining(missing);
    }

    // keystore.path defaults to blank: no client key store, the JVM default TLS context.
    @ParameterizedTest(name = "keystore.path \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void blankKeystorePathUsesTheDefaultTlsContext(String blankPath) {
        wm.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok()));

        assertThatCode(() -> {
            try (BoxProxyClient client = new BoxProxyClient(new DefaultResourceLoader(), wm.baseUrl(),
                    RESPONSE_TIMEOUT_MS, blankPath, "", "")) {
                assertThat(client.exchange(HttpMethod.GET, "/2.0/folders/0", null, new HttpHeaders(), null)
                        .getStatusCode().value()).isEqualTo(200);
            }
        }).doesNotThrowAnyException();
    }

    private static ResponseEntity<byte[]> sendWithBody(HttpMethod method, String path, String contentType,
                                                       byte[] body) {
        wm.stubFor(request(method.name(), urlEqualTo(path))
                .willReturn(aResponse().withStatus(201).withBody("created")));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, contentType);
        try (BoxProxyClient client = httpClient()) {
            return client.exchange(method, path, null, headers, body);
        }
    }
}
