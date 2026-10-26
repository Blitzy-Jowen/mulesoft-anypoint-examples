/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client.FimProxyClient;

/**
 * Unit test of {@link FimProxyService}, the processor logic of the {@code /FIM/*} flow
 * {@code http-client-credentialsFlow} of
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml}, with
 * {@link FimProxyClient} mocked.
 *
 * <p>It covers the {@code uri-param client_id=${client.id}} substitution of every literal {@code {client_id}}
 * placeholder (the value percent-encoded exactly once, the rest of the raw URI unchanged; a blank or unset
 * {@code client.id} refused before any call only where a placeholder needs it), the unchanged method, the requester's
 * {@code sendBodyMode} AUTO (no body and no Content-Type for GET, HEAD and OPTIONS in any letter case; both sent
 * unchanged otherwise), the listener's response (always 200, the upstream body bytes, only the upstream
 * Content-Type, an empty body when the upstream sent none) and the absence of an exception strategy (every client
 * failure reaches the caller as thrown, after a single call). No Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class FimProxyServiceTest {

    /** The {@code client.id} value used where the test is not about substitution. */
    private static final String CLIENT_ID = "cid-1";

    /** A raw URI without the {@code {client_id}} placeholder. */
    private static final String PLAIN_URI = "/FIM/sps/oauth/oauth20/token";

    @Mock
    FimProxyClient fimProxyClient;

    @Captor
    ArgumentCaptor<String> sentMethod;

    @Captor
    ArgumentCaptor<String> sentUri;

    @Captor
    ArgumentCaptor<MediaType> sentContentType;

    @Captor
    ArgumentCaptor<byte[]> sentBody;

    @ParameterizedTest(name = "[{index}] client.id={0}, {1} {2}")
    @CsvSource({
            // no placeholder: forwarded unchanged
            "cid-1, GET,    /FIM/sps/oauth/oauth20/authorize?response_type=code, "
                    + "/FIM/sps/oauth/oauth20/authorize?response_type=code",
            // a percent-encoded placeholder is not the literal token, so it is forwarded unchanged
            "cid-1, GET,    /FIM/a%20b?x=%7Bclient_id%7D, /FIM/a%20b?x=%7Bclient_id%7D",
            // single placeholder
            "cid-1, POST,   /FIM/sps/oauth/oauth20/authorize?client_id={client_id}, "
                    + "/FIM/sps/oauth/oauth20/authorize?client_id=cid-1",
            // every placeholder, in the path and the query alike
            "cid-1, get,    /FIM/{client_id}/x?client_id={client_id}&y={client_id}, "
                    + "/FIM/cid-1/x?client_id=cid-1&y=cid-1"
    })
    void clientIdPlaceholdersAreSubstitutedAndMethodIsForwardedUnchanged(String clientId, String method,
            String rawUri, String expectedUri) {
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        serviceWith(clientId).forward(method, rawUri, null, null);

        verify(fimProxyClient).send(sentMethod.capture(), sentUri.capture(), any(), any());
        assertThat(sentMethod.getValue()).isEqualTo(method);
        assertThat(sentUri.getValue()).isEqualTo(expectedUri);
        verifyNoMoreInteractions(fimProxyClient);
    }

    @ParameterizedTest(name = "[{index}] client.id={0}")
    @CsvSource({
            // unreserved characters are kept as they are
            "cid-1._~,      /FIM/token?client_id={client_id}, /FIM/token?client_id=cid-1._~",
            // path, query and fragment delimiters, and '+', stay inside the one value
            "a/b,           /FIM/token?client_id={client_id}, /FIM/token?client_id=a%2Fb",
            "a?b=c&d#e,     /FIM/token?client_id={client_id}, /FIM/token?client_id=a%3Fb%3Dc%26d%23e",
            "a+b,           /FIM/token?client_id={client_id}, /FIM/token?client_id=a%2Bb",
            // a space, which URI.create would reject
            "a b,           /FIM/token?client_id={client_id}, /FIM/token?client_id=a%20b",
            // encoded once: a '%' in the value is itself encoded, never read as an escape
            "%41,           /FIM/token?client_id={client_id}, /FIM/token?client_id=%2541",
            // non-ASCII as UTF-8 octets
            "\u00e9,        /FIM/token?client_id={client_id}, /FIM/token?client_id=%C3%A9",
            // a value spelling the placeholder is encoded, not substituted again
            "{client_id},   /FIM/token?client_id={client_id}, /FIM/token?client_id=%7Bclient_id%7D",
            // every placeholder gets the same encoded value; the rest of the raw URI is not re-encoded
            "a/b,           /FIM/{client_id}/x?client_id={client_id}&x=%2F, /FIM/a%2Fb/x?client_id=a%2Fb&x=%2F"
    })
    void clientIdIsPercentEncodedOnceAndTheRestOfTheUriIsForwardedUnchanged(String clientId, String rawUri,
            String expectedUri) {
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        serviceWith(clientId).forward("GET", rawUri, null, null);

        verify(fimProxyClient).send(any(), sentUri.capture(), any(), any());
        assertThat(sentUri.getValue()).isEqualTo(expectedUri);
        verifyNoMoreInteractions(fimProxyClient);
    }

    @ParameterizedTest(name = "[{index}] client.id=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankClientIdIsRefusedBeforeAnyCallWhenThePlaceholderNeedsIt(String clientId) {
        Throwable thrown = catchThrowable(
                () -> serviceWith(clientId).forward("GET", "/FIM/token?client_id={client_id}", null, null));

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("client.id is not configured");
        verifyNoInteractions(fimProxyClient);
    }

    @ParameterizedTest(name = "[{index}] client.id=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void blankClientIdStillForwardsAUriWithoutThePlaceholderUnchanged(String clientId) {
        String rawUri = "/FIM/a%20b?x=%7Bclient_id%7D&client_id=";
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        serviceWith(clientId).forward("POST", rawUri, null, null);

        verify(fimProxyClient).send(any(), sentUri.capture(), any(), any());
        assertThat(sentUri.getValue()).isEqualTo(rawUri);
        verifyNoMoreInteractions(fimProxyClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "get"})
    void bodylessMethodsSendNeitherBodyNorContentType(String method) {
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        serviceWith(CLIENT_ID).forward(method, PLAIN_URI, MediaType.APPLICATION_JSON,
                "{\"grant_type\":\"client_credentials\"}".getBytes(UTF_8));

        verify(fimProxyClient).send(sentMethod.capture(), sentUri.capture(), sentContentType.capture(),
                sentBody.capture());
        assertThat(sentMethod.getValue()).isEqualTo(method);
        assertThat(sentUri.getValue()).isEqualTo(PLAIN_URI);
        assertThat(sentContentType.getValue()).isNull();
        assertThat(sentBody.getValue()).isNull();
        verifyNoMoreInteractions(fimProxyClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void otherMethodsSendBodyAndContentTypeUnchanged(String method) {
        MediaType contentType = MediaType.parseMediaType("application/x-www-form-urlencoded;charset=UTF-8");
        byte[] body = "grant_type=client_credentials&scope=a%20b".getBytes(UTF_8);
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        serviceWith(CLIENT_ID).forward(method, PLAIN_URI, contentType, body);

        verify(fimProxyClient).send(sentMethod.capture(), sentUri.capture(), sentContentType.capture(),
                sentBody.capture());
        assertThat(sentMethod.getValue()).isEqualTo(method);
        assertThat(sentUri.getValue()).isEqualTo(PLAIN_URI);
        assertThat(sentContentType.getValue()).isSameAs(contentType);
        assertThat(sentBody.getValue()).isSameAs(body);
        verifyNoMoreInteractions(fimProxyClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;charset=UTF-8", "text/xml; charset=ISO-8859-1", "not a media type"})
    void responseIs200WithUpstreamBodyAndOnlyTheUpstreamContentTypeVerbatim(String upstreamContentType) {
        byte[] upstreamBody = "{\"access_token\":\"t\u00e9\"}".getBytes(UTF_8);
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, upstreamContentType)
                .header("X-Fim", "1")
                .header(HttpHeaders.SET_COOKIE, "PD-S-SESSION-ID=abc")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(upstreamBody.length))
                .body(upstreamBody));

        ResponseEntity<byte[]> response = serviceWith(CLIENT_ID).forward("GET", PLAIN_URI, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().keySet()).containsExactly(HttpHeaders.CONTENT_TYPE);
        assertThat(response.getHeaders().get(HttpHeaders.CONTENT_TYPE)).containsExactly(upstreamContentType);
        assertThat(response.getHeaders().containsKey("X-Fim")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void responseCarriesNoContentTypeWhenTheUpstreamHasNone(String upstreamContentType) {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.add("X-Fim", "1");
        if (upstreamContentType != null) {
            upstreamHeaders.add(HttpHeaders.CONTENT_TYPE, upstreamContentType);
        }
        byte[] upstreamBody = "plain bytes".getBytes(UTF_8);
        when(fimProxyClient.send(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().headers(upstreamHeaders).body(upstreamBody));

        ResponseEntity<byte[]> response = serviceWith(CLIENT_ID).forward("GET", PLAIN_URI, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 302})
    void upstreamSuccessStatusIsAnsweredWith200(int upstreamStatus) {
        byte[] upstreamBody = "created".getBytes(UTF_8);
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.status(upstreamStatus)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .header(HttpHeaders.LOCATION, "https://tfim01.demos.ibm.com/FIM/next")
                .body(upstreamBody));

        ResponseEntity<byte[]> response = serviceWith(CLIENT_ID).forward("POST", PLAIN_URI, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().keySet()).containsExactly(HttpHeaders.CONTENT_TYPE);
    }

    @Test
    void upstreamWithoutBodyIsAnsweredWithAnEmptyBody() {
        when(fimProxyClient.send(any(), any(), any(), any())).thenReturn(ResponseEntity.ok().build());

        ResponseEntity<byte[]> response = serviceWith(CLIENT_ID).forward("HEAD", PLAIN_URI, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
        assertThat(response.getHeaders()).isEmpty();
    }

    static Stream<Arguments> clientFailures() {
        HttpHeaders errorHeaders = new HttpHeaders();
        errorHeaders.setContentType(MediaType.TEXT_HTML);
        return Stream.of(
                Arguments.of("upstream 404", new RestClientResponseException("Response code 404 mapped as failure.",
                        HttpStatusCode.valueOf(404), "Not Found", errorHeaders,
                        "<html>missing</html>".getBytes(UTF_8), UTF_8)),
                Arguments.of("upstream 500", new RestClientResponseException("Response code 500 mapped as failure.",
                        HttpStatusCode.valueOf(500), "Internal Server Error", new HttpHeaders(), new byte[0],
                        null)),
                Arguments.of("connection refused", new ResourceAccessException(
                        "I/O error on GET request for \"https://tfim01.demos.ibm.com:443/FIM/x\": Connection refused",
                        new ConnectException("Connection refused"))));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("clientFailures")
    void clientFailurePropagatesAsThrownAfterASingleCall(String description, RuntimeException failure) {
        when(fimProxyClient.send(any(), any(), any(), any())).thenThrow(failure);

        Throwable thrown = catchThrowable(
                () -> serviceWith(CLIENT_ID).forward("GET", "/FIM/x?client_id={client_id}", null, null));

        assertThat(thrown).isSameAs(failure);
        verify(fimProxyClient, times(1)).send(any(), any(), any(), any());
        verifyNoMoreInteractions(fimProxyClient);
    }

    private FimProxyService serviceWith(String clientId) {
        return new FimProxyService(fimProxyClient, clientId);
    }
}
