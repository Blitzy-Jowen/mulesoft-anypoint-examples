package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mulesoft.examples.get_customer_list_from_netsuite.config.NetsuiteProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Tests {@link NetsuiteTokenProvider} (SC-16) against WireMock standing in for NetSuite's OAuth 2.0 token endpoint,
 * signing with an RSA key generated for each test, together with the {@link NetsuiteProperties} derivations the token
 * URL and the assertion's {@code aud} claim rest on. They cover the client credentials request and its PS256 JWT
 * client assertion (AAP 0.3.3), caching until 60 s before the stated expiry, eviction, the configuration failures
 * that name the {@code netsuite.oauth.*} key (AAP 0.4.6), the private-key loading failures whose caller-facing
 * message leaves the configured location and the cause to the log, and the unwrapped token-endpoint failure the
 * SC-9 classifier relies on (AAP 0.4.8). No Spring context starts and no classpath resource is read.
 *
 * <p>{@code netsuite-data-retrieval-java} carries this class with the provider, changing only the package line and
 * the import prefix.
 */
class NetsuiteTokenProviderTest {

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    private static final String ACCOUNT = "TSTDRV1234567_SB1";

    private static final String DERIVED_BASE_URL = "https://tstdrv1234567-sb1.suitetalk.api.netsuite.com";

    private static final String CLIENT_ID = "test-client-id";

    private static final String CERTIFICATE_ID = "test-certificate-id";

    private static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final String PRIVATE_KEY_PATH = "netsuite.oauth.private-key-path";

    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

    /** The assertion lifetime the provider signs, inside NetSuite's 60-minute limit. */
    private static final long ASSERTION_LIFETIME_SECONDS = 1800;

    @TempDir
    Path tmp;

    private WireMockServer wm;

    private KeyPair keyPair;

    private Path keyFile;

    private NetsuiteProperties props;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException, IOException {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        keyFile = tmp.resolve("netsuite-test-key.pem");
        writePkcs8Pem(keyFile, keyPair.getPrivate());

        props = new NetsuiteProperties();
        props.setAccount(ACCOUNT);
        props.setBaseUrl(wm.baseUrl());
        props.getOauth().setClientId(CLIENT_ID);
        props.getOauth().setCertificateId(CERTIFICATE_ID);
        props.getOauth().setPrivateKeyPath(keyFile.toString());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    // ------------------------------------------------------------------------------------------ token request

    @Test
    void requestsTokenWithClientCredentialsAndJwtAssertion() {
        stubToken("tok-1", "\"3600\"");

        assertThat(provider().accessToken()).isEqualTo("tok-1");

        wm.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded")));
        Map<String, String> form = postedForm();
        assertThat(form).containsOnlyKeys("grant_type", "client_assertion_type", "client_assertion")
                .containsEntry("grant_type", "client_credentials")
                .containsEntry("client_assertion_type", JWT_BEARER);
        assertThat(form.get("client_assertion")).isNotBlank();
    }

    @Test
    void tokenEndpointRejectionPropagatesUnwrappedAndIsNotCached() {
        wm.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\"}")));
        NetsuiteTokenProvider provider = provider();

        // Unwrapped, so NetsuiteFailureClassifier sees the 401 and UpstreamCallExecutor evicts and retries once.
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(provider::accessToken)
                    .isInstanceOfSatisfying(WebClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        }
        wm.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    void responseWithoutAccessTokenIsRejected() {
        wm.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .willReturn(okJson("{\"token_type\":\"bearer\",\"expires_in\":\"3600\"}")));
        NetsuiteTokenProvider provider = provider();

        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NetSuite token response did not contain access_token");
        wm.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    // ------------------------------------------------------------------------------------------------- JWT

    @Test
    void clientAssertionIsPs256SignedJwtWithNetsuiteClaims() throws ParseException, JOSEException {
        stubToken("tok-1", "\"3600\"");
        provider().accessToken();

        SignedJWT jwt = SignedJWT.parse(postedForm().get("client_assertion"));

        assertNetsuiteAssertion(jwt);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(ASSERTION_LIFETIME_SECONDS));
    }

    @Test
    void createClientAssertionUsesGivenInstant() throws ParseException, JOSEException {
        SignedJWT jwt = SignedJWT.parse(provider().createClientAssertion(START));

        assertNetsuiteAssertion(jwt);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(START);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(START.plusSeconds(ASSERTION_LIFETIME_SECONDS));
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    // --------------------------------------------------------------------------------------------- caching

    @Test
    void cachesTokenAcrossCalls() {
        stubTokenSequence("\"3600\"");
        NetsuiteTokenProvider provider = provider();

        assertThat(provider.accessToken()).isEqualTo("tok-1");
        assertThat(provider.accessToken()).isEqualTo("tok-1");

        wm.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /*
     * Each step stays ten seconds clear of the 60 s margin, so the test holds whether the provider compares with
     * < or <=.
     */
    @ParameterizedTest(name = "expires_in \"{0}\"")
    @ValueSource(longs = {3600, 120})
    void reusesTokenInsideExpiryMarginAndRefetchesAfterIt(long expiresIn) throws ParseException {
        stubTokenSequence("\"" + expiresIn + "\"");
        MutableClock clock = new MutableClock(START);
        NetsuiteTokenProvider provider = provider(clock);

        assertThat(provider.accessToken()).isEqualTo("tok-1");

        clock.advance(Duration.ofSeconds(expiresIn - 70));
        assertThat(provider.accessToken()).isEqualTo("tok-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));

        clock.advance(Duration.ofSeconds(20));
        assertThat(provider.accessToken()).isEqualTo("tok-2");
        wm.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));

        // The same clock dates each assertion.
        assertThat(postedIssueTimes())
                .containsExactlyInAnyOrder(START, START.plusSeconds(expiresIn - 50));
    }

    @Test
    void evictForcesNewTokenRequest() {
        stubTokenSequence("\"3600\"");
        NetsuiteTokenProvider provider = provider();

        assertThat(provider.accessToken()).isEqualTo("tok-1");
        provider.evict();
        assertThat(provider.accessToken()).isEqualTo("tok-2");

        wm.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    void acceptsNumericExpiresIn() {
        stubTokenSequence("3600");
        MutableClock clock = new MutableClock(START);
        NetsuiteTokenProvider provider = provider(clock);

        assertThat(provider.accessToken()).isEqualTo("tok-1");
        assertThat(provider.accessToken()).isEqualTo("tok-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));

        clock.advance(Duration.ofSeconds(3550));
        assertThat(provider.accessToken()).isEqualTo("tok-2");
        wm.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    // ------------------------------------------------------------------------------------------ rejections

    @Test
    void rejectsPkcs1PrivateKeyWithOpensslHint() throws IOException {
        // The body is the PKCS#8 encoding; the PKCS#1 armour alone decides the rejection.
        writePem(keyFile, "RSA PRIVATE KEY", keyPair.getPrivate().getEncoded());
        stubToken("tok-1", "\"3600\"");
        NetsuiteTokenProvider provider = provider();

        assertThatThrownBy(() -> provider.createClientAssertion(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("netsuite.oauth.private-key-path")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");
        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");

        wm.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    static Stream<Arguments> blankOauthSettings() {
        return Stream.of(
                Arguments.of("netsuite.oauth.client-id",
                        (Consumer<NetsuiteProperties.OAuth>) oauth -> oauth.setClientId("")),
                Arguments.of("netsuite.oauth.certificate-id",
                        (Consumer<NetsuiteProperties.OAuth>) oauth -> oauth.setCertificateId("")),
                Arguments.of("netsuite.oauth.private-key-path",
                        (Consumer<NetsuiteProperties.OAuth>) oauth -> oauth.setPrivateKeyPath("")));
    }

    /*
     * The setting is blanked before the provider is built: construction reads no credential, so the application
     * starts with empty placeholders and the failure surfaces only when an assertion or a token is needed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("blankOauthSettings")
    void missingOauthSettingsFailWithKeyName(String key, Consumer<NetsuiteProperties.OAuth> blank) {
        stubToken("tok-1", "\"3600\"");
        blank.accept(props.getOauth());
        NetsuiteTokenProvider provider = provider();

        assertThatThrownBy(() -> provider.createClientAssertion(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);
        assertThatThrownBy(provider::accessToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);

        wm.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** Writes a key that cannot be loaded under {@code dir} where needed and returns the location to configure. */
    @FunctionalInterface
    private interface UnloadableKey {

        String locate(Path dir) throws IOException, GeneralSecurityException;
    }

    /*
     * The configured value is trimmed before use, so the malformed path keeps its NUL inside the name. The DER
     * bodies are minimal SEQUENCEs: the PKCS#1 armour alone decides that rejection, and an INTEGER alone is no
     * PKCS#8 key.
     */
    static Stream<Arguments> unloadableKeys() {
        return Stream.of(
                Arguments.of(named("missing file",
                                (UnloadableKey) dir -> dir.resolve("missing-netsuite-key.pem").toString()),
                        "could not be read", NoSuchFileException.class),
                Arguments.of(named("malformed file path",
                                (UnloadableKey) dir -> dir.resolve("malformed") + "\0netsuite-key.pem"),
                        "could not be read", InvalidPathException.class),
                Arguments.of(named("classpath: naming no resource", (UnloadableKey) dir -> "classpath:"),
                        "names no classpath resource", null),
                Arguments.of(named("missing classpath resource",
                                (UnloadableKey) dir -> "classpath:netsuite/missing-netsuite-key.pem"),
                        "could not be read", FileNotFoundException.class),
                Arguments.of(named("file that is not PEM",
                                (UnloadableKey) dir -> Files.writeString(dir.resolve("not-pem-netsuite-key.pem"),
                                        "not a key\n", US_ASCII).toString()),
                        "is not an unencrypted PKCS#8 PEM private key", null),
                Arguments.of(named("PKCS#8 block holding no key",
                                (UnloadableKey) dir -> pemAt(dir.resolve("invalid-netsuite-key.pem"), "PRIVATE KEY",
                                        new byte[] {0x30, 0x03, 0x02, 0x01, 0x00})),
                        "does not hold a valid PKCS#8 RSA private key", InvalidKeySpecException.class),
                Arguments.of(named("PKCS#1 armour",
                                (UnloadableKey) dir -> pemAt(dir.resolve("pkcs1-netsuite-key.pem"), "RSA PRIVATE KEY",
                                        new byte[] {0x30, 0x00})),
                        "points to a PKCS#1 key", null),
                Arguments.of(named("RSA key under 2048 bits", (UnloadableKey) dir -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(1024);
                    return pemAt(dir.resolve("short-netsuite-key.pem"), "PRIVATE KEY",
                            generator.generateKeyPair().getPrivate().getEncoded());
                }), "holds an RSA key of 1024 bits", null));
    }

    /*
     * The unhandled-exception 500 hands the message to the caller, so it names the setting and the problem only;
     * the configured location and the cause go to one ERROR line per failed call, and the cause stays on the
     * exception. A key that failed to load is not cached, so each call fails and logs again.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unloadableKeys")
    @ExtendWith(OutputCaptureExtension.class)
    void unloadableKeyFailsWithCallerSafeMessageAndLogsLocation(UnloadableKey unloadable, String problem,
            Class<? extends Throwable> causeType, CapturedOutput output) throws IOException, GeneralSecurityException {
        String location = unloadable.locate(tmp);
        props.getOauth().setPrivateKeyPath(location);
        stubToken("tok-1", "\"3600\"");
        NetsuiteTokenProvider provider = provider();

        List<Throwable> failures = List.of(
                catchThrowable(() -> provider.createClientAssertion(Instant.now())),
                catchThrowable(provider::accessToken));

        for (Throwable failure : failures) {
            assertCallerSafe(failure, location, problem, causeType);
        }
        wm.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        List<String> logLines = output.getAll().lines()
                .filter(line -> line.contains(PRIVATE_KEY_PATH + " " + problem))
                .toList();
        assertThat(logLines).hasSize(failures.size()).allSatisfy(line -> {
            assertThat(line).contains("ERROR").contains("configured location: " + location);
            if (causeType != null) {
                assertThat(line).contains(failures.get(0).getCause().getClass().getName());
            }
        });
    }

    // ----------------------------------------------------------------------------------------- derivations

    @Test
    void accountHostLowerCasesAndReplacesUnderscore() {
        assertThat(props.accountHost()).isEqualTo("tstdrv1234567-sb1");
    }

    @ParameterizedTest(name = "base-url [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "/"})
    void resolvedBaseUrlDerivesFromAccountWhenBaseUrlEmpty(String baseUrl) {
        props.setBaseUrl(baseUrl);

        assertThat(props.resolvedBaseUrl()).isEqualTo(DERIVED_BASE_URL);
    }

    @Test
    void explicitBaseUrlWins() {
        props.setBaseUrl("http://localhost:12345");

        assertThat(props.resolvedBaseUrl()).isEqualTo("http://localhost:12345");
    }

    @ParameterizedTest(name = "base-url [{0}]")
    @CsvSource({
        "http://localhost:12345, http://localhost:12345/services/rest/auth/oauth2/v1/token",
        "'', https://tstdrv1234567-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"
    })
    void tokenUrlIsBasePlusTokenPath(String baseUrl, String expectedTokenUrl) {
        props.setBaseUrl(baseUrl);

        assertThat(props.tokenUrl())
                .isEqualTo(expectedTokenUrl)
                .isEqualTo(props.resolvedBaseUrl() + TOKEN_PATH);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private NetsuiteTokenProvider provider() {
        return new NetsuiteTokenProvider(props, WebClient.builder());
    }

    private NetsuiteTokenProvider provider(Clock clock) {
        return new NetsuiteTokenProvider(props, WebClient.builder(), clock);
    }

    private void stubToken(String token, String expiresInJson) {
        wm.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson(tokenResponse(token, expiresInJson))));
    }

    /** Answers {@code tok-1} to the first token request and {@code tok-2} to every later one. */
    private void stubTokenSequence(String expiresInJson) {
        wm.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("second")
                .willReturn(okJson(tokenResponse("tok-1", expiresInJson))));
        wm.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .inScenario("token")
                .whenScenarioStateIs("second")
                .willReturn(okJson(tokenResponse("tok-2", expiresInJson))));
    }

    /**
     * @param expiresInJson the {@code expires_in} value as JSON, a quoted string (as NetSuite sends it) or a number
     */
    private static String tokenResponse(String token, String expiresInJson) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_in\":" + expiresInJson + "}";
    }

    private void assertNetsuiteAssertion(SignedJWT jwt) throws ParseException, JOSEException {
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
        JWSHeader header = jwt.getHeader();
        assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.PS256);
        assertThat(header.getKeyID()).isEqualTo(CERTIFICATE_ID);
        assertThat(header.getType()).isEqualTo(JOSEObjectType.JWT);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(CLIENT_ID);
        assertThat(claims.getStringClaim("scope")).isEqualTo("rest_webservices");
        assertThat(claims.getAudience()).isEqualTo(List.of(props.tokenUrl()));
    }

    /**
     * Asserts a key-loading failure the caller may see: an {@link IllegalStateException} whose message is the setting
     * followed by {@code problem}, without the configured location, its directory or file name, or any exception
     * class name, and whose cause is of {@code causeType}, or absent when that is {@code null}.
     */
    private void assertCallerSafe(Throwable failure, String location, String problem,
            Class<? extends Throwable> causeType) {
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(PRIVATE_KEY_PATH + " " + problem);
        String fileName = location.substring(Math.max(location.lastIndexOf('/'), location.lastIndexOf(':')) + 1);
        assertThat(failure.getMessage())
                .doesNotContain(location)
                .doesNotContain(tmp.toString())
                .doesNotContain("Exception");
        if (!fileName.isEmpty()) {
            assertThat(failure.getMessage()).doesNotContain(fileName);
        }
        if (causeType == null) {
            assertThat(failure).hasNoCause();
        } else {
            assertThat(failure.getCause()).isInstanceOf(causeType);
            assertThat(failure.getMessage()).doesNotContain(failure.getCause().getClass().getSimpleName());
        }
    }

    /** The decoded form of the only token request received. */
    private Map<String, String> postedForm() {
        List<Map<String, String>> forms = postedForms();
        assertThat(forms).hasSize(1);
        return forms.get(0);
    }

    private List<Map<String, String>> postedForms() {
        return wm.findAll(postRequestedFor(urlPathEqualTo(TOKEN_PATH))).stream()
                .map(request -> decodeForm(request.getBodyAsString()))
                .toList();
    }

    private List<Instant> postedIssueTimes() throws ParseException {
        List<Instant> issueTimes = new ArrayList<>();
        for (Map<String, String> form : postedForms()) {
            issueTimes.add(SignedJWT.parse(form.get("client_assertion")).getJWTClaimsSet().getIssueTime().toInstant());
        }
        return issueTimes;
    }

    /** Decodes each name and value, so the assertions do not depend on how the client percent-encodes {@code :}. */
    private static Map<String, String> decodeForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] nameAndValue = pair.split("=", 2);
            form.put(URLDecoder.decode(nameAndValue[0], UTF_8),
                    nameAndValue.length > 1 ? URLDecoder.decode(nameAndValue[1], UTF_8) : "");
        }
        return form;
    }

    private static void writePkcs8Pem(Path file, PrivateKey key) throws IOException {
        // An RSA key from KeyPairGenerator encodes as PKCS#8.
        writePem(file, "PRIVATE KEY", key.getEncoded());
    }

    /** {@link #writePem}, returning the file's location as {@code netsuite.oauth.private-key-path} names it. */
    private static String pemAt(Path file, String label, byte[] der) throws IOException {
        writePem(file, label, der);
        return file.toString();
    }

    /** The armour is assembled from its label, so this source holds no literal key block for secret scanners. */
    private static void writePem(Path file, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII)).encodeToString(der);
        Files.writeString(file, "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n",
                US_ASCII);
    }

    /** A clock that stands still until advanced, so a token's expiry is crossed without waiting. */
    private static final class MutableClock extends Clock {

        private final ZoneId zone;

        private Instant now;

        MutableClock(Instant start) {
            this(start, ZoneOffset.UTC);
        }

        private MutableClock(Instant start, ZoneId zone) {
            this.now = start;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(now, newZone);
        }
    }
}
