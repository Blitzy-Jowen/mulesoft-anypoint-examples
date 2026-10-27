package com.mulesoft.examples.netsuite_data_retrieval.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Obtains and caches the OAuth 2.0 access token every NetSuite REST and SuiteQL call carries (shared component
 * SC-16, together with {@code NetsuiteRestClient}, {@code NetsuiteFailureClassifier} and {@link NetsuiteProperties}).
 *
 * <p>Replaces the SuiteTalk login of the Mule global configuration
 * {@code <netsuite:config account email password roleId/>}. NetSuite's machine-to-machine flow is the client
 * credentials grant with a JWT client assertion: the assertion is signed PS256 with the private key of the certificate
 * uploaded to the integration's M2M setup, carries that certificate's ID as header {@code kid}, and has the claims
 * {@code iss} = client ID, {@code scope} = {@code rest_webservices}, {@code aud} = token URL, {@code iat} and an
 * {@code exp} under NetSuite's 60-minute limit. The token it returns lasts 60 minutes; it is reused until 60 s before
 * the expiry the token response states, so no call starts with a token about to lapse.
 *
 * <p>Every setting comes from {@link NetsuiteProperties} and is read when first needed, so the application starts
 * with every credential placeholder empty and the first token request fails with an {@link IllegalStateException}
 * naming the missing key. The private key is read once, on first use, and kept in memory.
 *
 * <p>Failures of the token request itself propagate unwrapped ({@code WebClientResponseException} for a non-2xx
 * answer, {@code WebClientRequestException} for a timeout or connection failure): {@code NetsuiteRestClient} fetches
 * the token inside the call it hands to {@code UpstreamCallExecutor}, whose NetSuite classifier turns a 401 into
 * {@link #evict()} plus one retry and a timeout or connectivity failure into a 503 with no retry.
 *
 * <p>Neither the assertion, the token, the key nor the token response body is ever logged or placed in an exception
 * message; logs name only the token URL and the expiry instant.
 */
@Component
public class NetsuiteTokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteTokenProvider.class);

    private static final String PRIVATE_KEY_PATH_KEY = "netsuite.oauth.private-key-path";

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";

    private static final String PKCS8_END = "-----END PRIVATE KEY-----";

    /** The smallest RSA modulus the PS256 signer accepts. */
    private static final int MIN_RSA_KEY_BITS = 2048;

    private static final String CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final String SCOPE = "rest_webservices";

    /** Assertion lifetime; NetSuite rejects an {@code exp} more than 60 minutes after {@code iat}. */
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(30);

    /** NetSuite's token lifetime, used when the response states no usable {@code expires_in}. */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600;

    /** A cached token is not handed out during its last minute. */
    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private final NetsuiteProperties props;

    private final Clock clock;

    private final WebClient webClient;

    /*
     * Jackson can quote the input it fails on, and a token response is where the secret is, so the parser never
     * includes the source in its locations and parse failures are reported without the Jackson exception.
     */
    private final ObjectMapper json = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private String cachedToken;

    private Instant expiresAt;

    private volatile PrivateKey privateKey;

    /**
     * Creates the provider on the system UTC clock.
     *
     * @param props   the {@code netsuite.*} settings
     * @param builder Spring Boot's auto-configured builder; it is cloned, never modified
     */
    @Autowired
    public NetsuiteTokenProvider(NetsuiteProperties props, WebClient.Builder builder) {
        this(props, builder, Clock.systemUTC());
    }

    /**
     * Creates the provider on the given clock, which decides the assertion's {@code iat} and the token's expiry.
     *
     * @param props   the {@code netsuite.*} settings
     * @param builder the builder the token client is cloned from; it is never modified
     * @param clock   the clock
     */
    public NetsuiteTokenProvider(NetsuiteProperties props, WebClient.Builder builder, Clock clock) {
        this.props = Objects.requireNonNull(props, "props");
        this.clock = Objects.requireNonNull(clock, "clock");
        // No base URL: the token URL is resolved from the properties on every request, so start-up needs no account.
        this.webClient = Objects.requireNonNull(builder, "builder").clone()
                .clientConnector(NetsuiteRestClient.connector(props))
                .build();
    }

    /**
     * Returns a valid access token: the cached one while it has more than 60 s left, otherwise a new one from the
     * token endpoint, which is then cached until the expiry its response states ({@code expires_in}, a number or a
     * numeric string; 3600 s when absent or unparsable).
     *
     * @return the bearer token
     * @throws IllegalStateException when a setting the request needs is missing or invalid (the message names the
     *         key), when the private key cannot be loaded, or when the response is not JSON or carries no
     *         {@code access_token}
     * @throws org.springframework.web.reactive.function.client.WebClientResponseException when the token endpoint
     *         answers with a non-2xx status, unwrapped
     * @throws org.springframework.web.reactive.function.client.WebClientRequestException on a timeout or connection
     *         failure, unwrapped
     */
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(expiresAt.minusSeconds(EXPIRY_MARGIN_SECONDS))) {
            return cachedToken;
        }
        String tokenUrl = props.tokenUrl();
        String assertion = createClientAssertion(now);
        LOG.debug("Requesting NetSuite access token from {}", tokenUrl);
        String body = webClient.post()
                .uri(URI.create(tokenUrl))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_assertion_type", CLIENT_ASSERTION_TYPE)
                        .with("client_assertion", assertion))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode response = parse(body);
        JsonNode token = response.get("access_token");
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            throw new IllegalStateException("NetSuite token response did not contain access_token");
        }
        long expiresIn = expiresInSeconds(response.get("expires_in"));
        cachedToken = token.asText();
        expiresAt = now.plusSeconds(expiresIn);
        LOG.info("Obtained NetSuite access token from {}, valid until {}", tokenUrl, expiresAt);
        return cachedToken;
    }

    /**
     * Discards the cached token, so the next {@link #accessToken()} requests a new one. This is the
     * re-authentication step {@code UpstreamCallExecutor} runs after NetSuite rejects a token
     * ({@code tokenProvider::evict} as an {@link UpstreamAction}).
     */
    public synchronized void evict() {
        cachedToken = null;
        expiresAt = null;
        LOG.debug("Evicted cached NetSuite access token");
    }

    /**
     * Builds and signs the JWT client assertion of a token request: header {@code alg} PS256, {@code typ} JWT,
     * {@code kid} = {@code netsuite.oauth.certificate-id}; claims {@code iss} = {@code netsuite.oauth.client-id},
     * {@code scope} = {@code rest_webservices}, {@code aud} = the token URL, {@code iat} = {@code now} and
     * {@code exp} = {@code now} + 30 minutes.
     *
     * @param now the issue time
     * @return the compact serialization of the signed assertion
     * @throws IllegalStateException when a setting is missing or invalid (the message names the key), when the private
     *         key cannot be loaded, or when signing fails
     */
    public String createClientAssertion(Instant now) {
        Objects.requireNonNull(now, "now");
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.PS256)
                .type(JOSEObjectType.JWT)
                .keyID(props.requireCertificateId())
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.requireClientId())
                .claim("scope", SCOPE)
                .audience(props.tokenUrl())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ASSERTION_LIFETIME)))
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign NetSuite client assertion", e);
        }
        return jwt.serialize();
    }

    private PrivateKey privateKey() {
        PrivateKey key = privateKey;
        if (key == null) {
            synchronized (this) {
                key = privateKey;
                if (key == null) {
                    key = loadPrivateKey(props.requirePrivateKeyPath());
                    privateKey = key;
                }
            }
        }
        return key;
    }

    /**
     * Reads an unencrypted PKCS#8 PEM RSA private key from a file path or a {@code classpath:} location. Error
     * messages name the key and the configured location, never the file content.
     */
    private static PrivateKey loadPrivateKey(String location) {
        String pem = readKeyText(location);
        if (pem.contains(PKCS1_BEGIN)) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " points to a PKCS#1 key; NetSuite client"
                    + " assertions need PKCS#8 — convert with: openssl pkcs8 -topk8 -nocrypt -in key.pem"
                    + " -out key-pkcs8.pem");
        }
        int begin = pem.indexOf(PKCS8_BEGIN);
        int end = begin < 0 ? -1 : pem.indexOf(PKCS8_END, begin + PKCS8_BEGIN.length());
        if (begin < 0 || end < 0) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " (" + location
                    + ") is not an unencrypted PKCS#8 PEM private key: expected a block between "
                    + PKCS8_BEGIN + " and " + PKCS8_END);
        }
        String base64 = pem.substring(begin + PKCS8_BEGIN.length(), end).replaceAll("\\s", "");
        PrivateKey key;
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " (" + location
                    + ") does not hold a valid PKCS#8 RSA private key", e);
        }
        // Checked here so the failure names the setting; RSASSASigner would otherwise reject it with a bare message.
        if (key instanceof RSAPrivateKey rsa && rsa.getModulus().bitLength() < MIN_RSA_KEY_BITS) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " (" + location + ") holds an RSA key of "
                    + rsa.getModulus().bitLength() + " bits; PS256 client assertions need at least "
                    + MIN_RSA_KEY_BITS + " bits");
        }
        return key;
    }

    private static String readKeyText(String location) {
        try {
            byte[] bytes;
            if (location.startsWith(CLASSPATH_PREFIX)) {
                String resource = location.substring(CLASSPATH_PREFIX.length()).trim();
                if (resource.isEmpty()) {
                    throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " (" + location
                            + ") names no classpath resource");
                }
                try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                    bytes = in.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(Path.of(location));
            }
            // PEM is ASCII; decoding as UTF-8 also tolerates a byte-order mark or non-ASCII comment lines.
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException covers InvalidPathException for a malformed file path.
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " (" + location
                    + ") could not be read: " + e.getClass().getSimpleName(), e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("NetSuite token response did not contain access_token");
        }
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            // The cause is dropped on purpose: Jackson's message can quote the body, which may hold the token.
            throw new IllegalStateException("NetSuite token response is not valid JSON");
        }
    }

    private static long expiresInSeconds(JsonNode value) {
        if (value == null || value.isNull()) {
            return DEFAULT_EXPIRES_IN_SECONDS;
        }
        if (value.isNumber()) {
            return value.canConvertToLong() ? value.asLong() : DEFAULT_EXPIRES_IN_SECONDS;
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException e) {
                return DEFAULT_EXPIRES_IN_SECONDS;
            }
        }
        return DEFAULT_EXPIRES_IN_SECONDS;
    }
}
