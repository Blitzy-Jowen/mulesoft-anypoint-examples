/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.SerializationException;
import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.mule_component_bindings.client.TwitterSearchClient;
import com.mulesoft.examples.mule_component_bindings.exception.ErrorResponse;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient.Response;

import twitter4j.QueryResult;
import twitter4j.Status;
import twitter4j.TwitterException;

/**
 * Wire-level test of {@link TwitterSearchController}, the HTTP trigger of flow {@code mule-component-bindingsFlow1}:
 * the listener on {@code localhost:8081} path {@code /} without {@code allowedMethods}, whose fixed
 * {@code twitter:search} ({@code query="AAPL" since="2014-10-26" until="2014-11-04"}) and
 * {@code TwitterResponseProcessor} produce an {@code ArrayList<Tweet>} that the listener wrote by Java serialization
 * (mule-component-bindings/src/main/app/mule-component-bindings.xml:31-36).
 *
 * <p>The whole application runs on Undertow under profile {@code test}: the main listener on a random port and the
 * stock-statistics listener on a free port reserved before the context starts, because SC-10's
 * {@code PortRoutingFilter} tells the listeners apart by local port. Only the outbound Twitter adapter
 * {@link TwitterSearchClient} is mocked, so the real {@code TwitterSearchService}, {@code TwitterResponseMapper} and
 * SC-1 {@code GlobalExceptionHandler} produce every response and nothing leaves the JVM. Requests go through ST-1
 * {@link HttpTestClient}, which adds no {@code Accept} or {@code Content-Type} of its own, so the status line,
 * headers and body bytes are asserted as written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TwitterSearchControllerTest {

    /** Port of the stock-statistics listener ({@code ports.stock-stats}), reserved once for the whole class. */
    private static final int STOCK_STATS_PORT = TestSocketUtils.findAvailableTcpPort();

    /** The {@code query}, {@code since} and {@code until} the flow's {@code twitter:search} fixes. */
    private static final String QUERY = "AAPL";
    private static final String SINCE = "2014-10-26";
    private static final String UNTIL = "2014-11-04";

    /** The listener's payload for a search without tweets: the Java-serialized empty {@code ArrayList}. */
    private static final byte[] SERIALIZED_EMPTY_LIST = SerializationUtils.serialize(new ArrayList<Tweet>());

    @LocalServerPort
    private int port;

    @MockBean
    private TwitterSearchClient twitterSearchClient;

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("ports.stock-stats", () -> STOCK_STATS_PORT);
    }

    /**
     * Every method reaches the flow, because the listener declares no {@code allowedMethods} (AAP 0.4.9), and the
     * controller ignores the request's body and {@code Content-Type}. {@code OPTIONS} runs through the controller's
     * explicit mapping instead of Spring MVC's own {@code OPTIONS} answer, and {@code TRACE} reaches the controller
     * because the application dispatches it to handlers.
     */
    @ParameterizedTest(name = "[{index}] {0} / with Content-Type {1}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
        "GET     | null             | null",
        "POST    | text/plain       | ''",
        "PUT     | application/json | '{}'",
        "PATCH   | application/xml  | <a/>",
        "DELETE  | null             | null",
        "OPTIONS | null             | null",
        "TRACE   | null             | null",
    })
    void everyMethodOnRootRunsTheFixedSearchAndReturnsTheSerializedEmptyList(String method, String contentType,
            String body) throws Exception {
        stubSearchResult(List.of());

        Response response = client().send(method, "/",
                contentType == null ? Map.of() : Map.of("Content-Type", contentType),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertThat(response.header("Content-Type")).as(response.toString()).isNull();
        assertThat(response.header("Content-Length")).isEqualTo(String.valueOf(SERIALIZED_EMPTY_LIST.length));
        assertThat(response.body()).isEqualTo(SERIALIZED_EMPTY_LIST);
        verify(twitterSearchClient, times(1)).search(QUERY, SINCE, UNTIL);
    }

    /** {@code HEAD /} runs the same flow and carries the payload's headers without its body. */
    @Test
    void headOnRootRunsTheFixedSearchAndSendsTheHeadersWithoutTheBody() throws Exception {
        stubSearchResult(List.of());

        Response response = client().send("HEAD", "/", null, null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).as(response.toString()).isNull();
        assertThat(response.header("Content-Length")).isEqualTo(String.valueOf(SERIALIZED_EMPTY_LIST.length));
        assertThat(response.body()).isEmpty();
        verify(twitterSearchClient, times(1)).search(QUERY, SINCE, UNTIL);
    }

    /**
     * {@code Tweet} is not {@code Serializable}, so the listener payload of a search with tweets cannot be written;
     * the {@code SerializationException} reaches SC-1's 500 with the library's own message.
     */
    @Test
    void searchWithTweetsFailsToSerializeAndReturns500WithTheSerializationMessage() throws Exception {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(42L);
        when(status.getText()).thenReturn("hello");
        stubSearchResult(List.of(status));
        SerializationException expected = catchThrowableOfType(
                () -> SerializationUtils.serialize(new ArrayList<>(List.of(new Tweet()))),
                SerializationException.class);
        assertThat(expected).as("serializing a list of tweets must fail").isNotNull();

        Response response = client().get("/");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly("application/json");
        assertThat(response.bodyAsString())
                .startsWith("{ \"message\": ")
                .isEqualTo(ErrorResponse.json(expected.getMessage()));
        verify(twitterSearchClient, times(1)).search(QUERY, SINCE, UNTIL);
    }

    /** A failed Twitter search is rethrown with its message and answered with SC-1's 500. */
    @Test
    void twitterFailureReturns500WithTheTwitterMessage() throws Exception {
        when(twitterSearchClient.search(anyString(), anyString(), anyString()))
                .thenThrow(new TwitterException("boom"));

        Response response = client().get("/");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly("application/json");
        assertThat(response.bodyAsString())
                .isEqualTo(ErrorResponse.json(new TwitterException("boom").getMessage()))
                .isEqualTo("{ \"message\": \"boom\" }");
    }

    /**
     * The main listener serves {@code /} only, so SC-10's {@code PortRoutingFilter} answers every other path with the
     * no-listener 404, reporting the path without its query string; the Twitter search is never run.
     */
    @ParameterizedTest(name = "[{index}] GET {0}")
    @CsvSource(delimiter = '|', value = {
        "/favicon.ico                              | /favicon.ico",
        "/api/stockStats?stock=AAPL&date=2012-10-26 | /api/stockStats",
    })
    void otherPathsOnTheMainListenerGetTheNoListener404(String target, String reportedPath) {
        Response response = client().get(target);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + reportedPath);
        verifyNoInteractions(twitterSearchClient);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    private void stubSearchResult(List<Status> tweets) throws TwitterException {
        QueryResult result = mock(QueryResult.class);
        when(result.getTweets()).thenReturn(tweets);
        when(twitterSearchClient.search(anyString(), anyString(), anyString())).thenReturn(result);
    }
}
