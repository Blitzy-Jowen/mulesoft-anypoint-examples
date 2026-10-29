/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client.BoxOAuthClient;

/**
 * Unit test of {@link BoxClientCredentialsService}, the processor logic of the {@code /oauth} flow
 * {@code httpclientcredentialsFlow} of
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml}, lines 31-38,
 * with {@link BoxOAuthClient} mocked.
 *
 * <p>It covers the {@code #[payload]} logger (the request body as UTF-8 text at INFO, {@code {NullPayload}} for an
 * absent or empty body, before the Box call), the listener's response (always 200, the Box body bytes, only the
 * raw Box Content-Type, an empty body when Box sent none) and the logging {@code rollback-exception-strategy}
 * (the failure's {@code toString()} at INFO after the payload line, then the same runtime exception rethrown so
 * the platform-default 500 applies, or a checked exception wrapped in an {@link IllegalStateException}). Every
 * case makes exactly one Box call. No Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
class BoxClientCredentialsServiceTest {

    /** How Mule 3 renders {@code #[payload]} for a request without a body. */
    private static final String NULL_PAYLOAD = "{NullPayload}";

    @Mock
    BoxOAuthClient boxOAuthClient;

    BoxClientCredentialsService service;

    Logger serviceLogger;

    Level previousLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createServiceAndCaptureItsLog() {
        service = new BoxClientCredentialsService(boxOAuthClient);
        serviceLogger = (Logger) LoggerFactory.getLogger(BoxClientCredentialsService.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    @ParameterizedTest(name = "[{index}] body={0}")
    @NullAndEmptySource
    void absentBodyIsLoggedAsNullPayloadBeforeTheSingleBoxCall(byte[] body) {
        stubJsonUpstream();

        service.authorize(body);

        assertThat(infoMessages()).containsExactly(NULL_PAYLOAD);
        verifySingleBoxCall();
    }

    @ParameterizedTest(name = "[{index}] body={0}")
    @ValueSource(strings = {"hello", "{\"grant_type\":\"client_credentials\"}", "h\u00e9llo \u20ac"})
    void bodyIsLoggedAsUtf8TextBeforeTheSingleBoxCall(String text) {
        stubJsonUpstream();

        service.authorize(text.getBytes(UTF_8));

        assertThat(infoMessages()).containsExactly(text);
        verifySingleBoxCall();
    }

    @ParameterizedTest(name = "[{index}] Content-Type \"{0}\"")
    @ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, "text/plain; charset=ISO-8859-1", "not a media type"})
    void responseIs200WithTheBoxBodyAndOnlyTheBoxContentTypeVerbatim(String upstreamContentType) {
        byte[] upstreamBody = "{\"type\":\"user\",\"name\":\"t\u00e9st\"}".getBytes(UTF_8);
        when(boxOAuthClient.authorize()).thenReturn(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, upstreamContentType)
                .header("X-Box", "1")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(upstreamBody.length))
                .body(upstreamBody.clone()));

        ResponseEntity<byte[]> response = service.authorize("hello".getBytes(UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().keySet()).containsExactly(HttpHeaders.CONTENT_TYPE);
        assertThat(response.getHeaders().get(HttpHeaders.CONTENT_TYPE)).containsExactly(upstreamContentType);
        assertThat(response.getHeaders().containsKey("X-Box")).isFalse();
    }

    @ParameterizedTest(name = "[{index}] Content-Type \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void responseCarriesNoContentTypeWhenBoxSentNone(String upstreamContentType) {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.add("X-Box", "1");
        if (upstreamContentType != null) {
            upstreamHeaders.add(HttpHeaders.CONTENT_TYPE, upstreamContentType);
        }
        byte[] upstreamBody = "plain bytes".getBytes(UTF_8);
        when(boxOAuthClient.authorize())
                .thenReturn(ResponseEntity.ok().headers(upstreamHeaders).body(upstreamBody.clone()));

        ResponseEntity<byte[]> response = service.authorize(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().getContentType()).isNull();
        assertThat(response.getHeaders()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] Box status {0}")
    @ValueSource(ints = {201, 202, 203})
    void boxSuccessStatusIsAnsweredWith200(int upstreamStatus) {
        byte[] upstreamBody = "created".getBytes(UTF_8);
        when(boxOAuthClient.authorize()).thenReturn(ResponseEntity.status(upstreamStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.LOCATION, "https://app.box.com/next")
                .body(upstreamBody.clone()));

        ResponseEntity<byte[]> response = service.authorize(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(upstreamBody);
        assertThat(response.getHeaders().keySet()).containsExactly(HttpHeaders.CONTENT_TYPE);
    }

    @Test
    void boxResponseWithoutBodyIsAnsweredWithAnEmptyBody() {
        when(boxOAuthClient.authorize()).thenReturn(ResponseEntity.ok().build());

        ResponseEntity<byte[]> response = service.authorize(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
        assertThat(response.getHeaders()).isEmpty();
    }

    /** The runtime failures {@link BoxOAuthClient#authorize()} declares. */
    static Stream<Arguments> boxFailures() {
        return Stream.of(
                Arguments.of("Box 500", new RestClientResponseException("Response code 500 mapped as failure.", 500,
                        "Internal Server Error", null, null, null)),
                Arguments.of("token exchange", new IllegalStateException("token exchange failed")),
                Arguments.of("connection refused", new ResourceAccessException(
                        "I/O error on GET request for \"https://app.box.com:443/\": Connection refused",
                        new ConnectException("Connection refused"))));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("boxFailures")
    void runtimeFailureIsLoggedAfterThePayloadAndRethrownAsTheSameInstance(String description,
            RuntimeException failure) {
        when(boxOAuthClient.authorize()).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.authorize("hello".getBytes(UTF_8)));

        assertThat(thrown).isSameAs(failure);
        assertThat(infoMessages()).containsExactly("hello", failure.toString());
        verifySingleBoxCall();
    }

    @Test
    void checkedFailureIsLoggedAfterThePayloadAndWrappedInIllegalStateException() {
        IOException ioe = new IOException("boom");
        when(boxOAuthClient.authorize()).thenAnswer(invocation -> {
            throw ioe;
        });

        assertThatThrownBy(() -> service.authorize(null))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("boom")
                .cause().isSameAs(ioe);
        assertThat(infoMessages()).containsExactly(NULL_PAYLOAD, "java.io.IOException: boom");
        verifySingleBoxCall();
    }

    /** Box answers 200 {@code application/json} {@code {}}. */
    private void stubJsonUpstream() {
        when(boxOAuthClient.authorize()).thenReturn(
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{}".getBytes(UTF_8)));
    }

    private void verifySingleBoxCall() {
        verify(boxOAuthClient, times(1)).authorize();
        verifyNoMoreInteractions(boxOAuthClient);
    }

    /** The formatted messages the service logged at INFO, in order. */
    private List<String> infoMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
