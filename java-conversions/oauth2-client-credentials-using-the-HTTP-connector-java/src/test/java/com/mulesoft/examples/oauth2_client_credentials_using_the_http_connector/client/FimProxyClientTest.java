/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * The contract of {@link FimProxyClient}, the {@code http:request} of the {@code /FIM/*} flow in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml} (lines
 * 29 and 41), against a WireMock stand-in for {@code https://tfim01.demos.ibm.com:443}: the inbound method and
 * raw request URI reach the upstream unchanged, a body and Content-Type are sent only when supplied, a success
 * comes back with its status, headers and body, a status of 400 or above fails once with Mule's
 * {@code Response code <code> mapped as failure.}, redirects are followed as {@code http:request} follows them by
 * default, and a refused connection surfaces its {@link ConnectException}.
 */
class FimProxyClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private FimProxyClient client;

    @BeforeEach
    void setUp() {
        wm.resetAll();
        client = new FimProxyClient(wm.baseUrl(), Duration.ofSeconds(2));
    }

    @Test
    void rawUriAndMethodForwardedVerbatim() {
        String rawUri = "/FIM/a%20b/c?x=1&y=%2F";
        wm.stubFor(get(urlEqualTo(rawUri)).willReturn(ok()));

        ResponseEntity<byte[]> response = client.send("GET", rawUri, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        // urlEqualTo compares the URL as received, so a decoded or re-encoded path or query would not match.
        wm.verify(1, getRequestedFor(urlEqualTo(rawUri)).withoutHeader("Content-Type"));
        List<LoggedRequest> requests = wm.findAll(getRequestedFor(urlEqualTo(rawUri)));
        assertThat(requests).singleElement().satisfies(logged -> assertThat(logged.getBody()).isEmpty());
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void postBodyAndContentTypeForwarded(String method) {
        String json = "{\"k\":\"v\"}";
        wm.stubFor(request(method, urlEqualTo("/FIM/submit")).willReturn(ok()));

        ResponseEntity<byte[]> response =
                client.send(method, "/FIM/submit", MediaType.APPLICATION_JSON, json.getBytes(UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        wm.verify(1, requestedFor(method, urlEqualTo("/FIM/submit"))
                .withRequestBody(equalTo(json))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Content-Length", equalTo(String.valueOf(json.getBytes(UTF_8).length))));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void successfulResponseReturnedWithHeadersAndBody() {
        wm.stubFor(get(urlEqualTo("/FIM/ok")).willReturn(aResponse()
                .withStatus(202)
                .withHeader("X-Upstream", "v")
                .withBody("accepted")));

        ResponseEntity<byte[]> response = client.send("GET", "/FIM/ok", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getFirst("X-Upstream")).isEqualTo("v");
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), UTF_8)).isEqualTo("accepted");
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/ok")));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void errorStatusesThrowMappedAsFailure(int status) {
        wm.stubFor(get(urlEqualTo("/FIM/err")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/plain; charset=UTF-8")
                .withBody("upstream-error")));

        assertThatThrownBy(() -> client.send("GET", "/FIM/err", null, null))
                .isInstanceOf(RestClientResponseException.class)
                .hasMessage("Response code " + status + " mapped as failure.")
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(status);
                    assertThat(ex.getResponseBodyAsString()).isEqualTo("upstream-error");
                });
        // Exactly one attempt: a failure status is never retried.
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/err")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void redirectIsFollowed() {
        wm.stubFor(get(urlEqualTo("/FIM/old")).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + "/FIM/new")));
        wm.stubFor(get(urlEqualTo("/FIM/new")).willReturn(ok("new-body")));

        ResponseEntity<byte[]> response = client.send("GET", "/FIM/old", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), UTF_8)).isEqualTo("new-body");
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/old")));
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/new")));
    }

    @Test
    void connectionRefusedSurfacesConnectException() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        FimProxyClient refusedClient = new FimProxyClient("http://127.0.0.1:" + port, Duration.ofSeconds(2));

        Throwable thrown = catchThrowable(() -> refusedClient.send("GET", "/FIM/x", null, null));

        assertThat(thrown).isNotNull();
        assertThat(causeChain(thrown)).anyMatch(ConnectException.class::isInstance);
    }

    /** The throwable and every cause beneath it, each once, so a cyclic cause chain cannot loop. */
    private static List<Throwable> causeChain(Throwable top) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = top; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
