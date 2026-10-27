/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteTokenProvider;
import com.mulesoft.examples.get_customer_list_from_netsuite.service.NetsuiteCustomerService;
import com.mulesoft.examples.get_customer_list_from_netsuite.support.HttpTestClient;

/**
 * Port of the original example's {@code GetCustomersListFromNetsuiteIT} functional test: the same request,
 * {@code GET /customers?lastName=a}, and its assertion with the original substring semantics, the rendered page
 * contains {@code <h1>Netsuite Customer List</h1>}.
 *
 * <p>The original ran {@code get-customer-list-from-netsuite.xml} in an embedded Mule runtime against a live NetSuite
 * account read from {@code mule.test.properties}. Here the whole application runs on a random port, ST-1
 * {@link HttpTestClient} sends the request, and an in-process WireMock server takes NetSuite's place through
 * {@code netsuite.base-url}. The real NetSuite client stack answers the request: the token provider signs a client
 * assertion with an RSA key generated for this run and POSTs it to the token endpoint, and the REST client POSTs the
 * customer query to SuiteQL with the token it obtained. The stubs match the token request and the SuiteQL request as
 * the clients send them, so a request the clients shape differently finds no stub and fails the test. Profile
 * {@code test} supplies the dummy account and OAuth identifiers ({@code application-test.yml}); no request leaves
 * localhost and no credential or key is committed.
 *
 * <p>Live NetSuite verification of the same assertion belongs to the failsafe tier ({@code *IT}), not to this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GetCustomersListFromNetsuiteTest {

    private static final String LAST_NAME_VALUE = "a";

    private static final String TEST_HTML = "<h1>Netsuite Customer List</h1>";

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    private static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    private static final String ACCESS_TOKEN = "test-access-token";

    private static final String TOKEN_RESPONSE =
            "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}";

    /** The customer page NetSuite answers the SuiteQL query with. */
    private static final String SUITEQL_RESPONSE_RESOURCE = "/netsuite/suiteql-customers.json";

    /*
     * Started while the class initializes, so it is listening before the Spring context resolves the
     * netsuite.base-url supplier below.
     */
    private static final WireMockServer NETSUITE = startNetsuiteStub();

    /** PKCS#8 PEM file of an RSA key generated for this run; it signs the token request's client assertion. */
    private static final Path PRIVATE_KEY_PEM = writeGeneratedPrivateKey();

    @LocalServerPort
    private int port;

    @Autowired
    private NetsuiteTokenProvider tokenProvider;

    @DynamicPropertySource
    static void netsuiteProperties(DynamicPropertyRegistry registry) {
        registry.add("netsuite.base-url", NETSUITE::baseUrl);
        registry.add("netsuite.oauth.private-key-path", () -> PRIVATE_KEY_PEM.toString());
    }

    @AfterAll
    static void stopNetsuiteStub() {
        NETSUITE.stop();
    }

    @BeforeEach
    void stubNetsuite() throws IOException {
        NETSUITE.resetAll();
        NETSUITE.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .willReturn(okJson(TOKEN_RESPONSE)));
        NETSUITE.stubFor(post(urlPathEqualTo(SUITEQL_PATH))
                .withQueryParam("limit", equalTo("1000"))
                .withQueryParam("offset", equalTo("0"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withHeader("Prefer", equalTo("transient"))
                .withRequestBody(matchingJsonPath("$.q",
                        equalTo(NetsuiteCustomerService.customerQuery(LAST_NAME_VALUE))))
                .willReturn(okJson(readResource(SUITEQL_RESPONSE_RESOURCE))));
        // The provider is a context singleton: a token cached by another test of this context must not hide the
        // token request this test expects.
        tokenProvider.evict();
    }

    @Test
    void testRetrieve() {
        HttpTestClient.Response response =
                HttpTestClient.localhost(port).get("/customers?lastName=" + LAST_NAME_VALUE);

        assertEquals(200, response.status(), () -> "unexpected response: " + response);
        assertTrue(response.bodyAsString().contains(TEST_HTML));
        NETSUITE.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        NETSUITE.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
    }

    private static WireMockServer startNetsuiteStub() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    private static String readResource(String location) throws IOException {
        try (InputStream in = GetCustomersListFromNetsuiteTest.class.getResourceAsStream(location)) {
            if (in == null) {
                throw new FileNotFoundException("classpath resource " + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Generates an RSA-2048 key pair and writes its private key as the unencrypted PKCS#8 PEM file the token
     * provider reads, in a temporary directory removed when the JVM exits.
     */
    private static Path writeGeneratedPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs8);
            Path directory = Files.createTempDirectory("netsuite-test-key");
            // Registered before the file, so the JVM deletes the file first and then the emptied directory.
            directory.toFile().deleteOnExit();
            Path pem = directory.resolve("netsuite-test-key.pem");
            Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n",
                    StandardCharsets.US_ASCII);
            pem.toFile().deleteOnExit();
            return pem;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the generated NetSuite test key", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA key generation is unavailable", e);
        }
    }
}
