/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.hello_world.service.HelloWorldService;
import com.mulesoft.examples.hello_world.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link HelloWorldController}, the HTTP trigger of flow {@code HelloWorldFlow1}
 * (hello-world/src/main/app/HelloWorld.xml:3-6), on the application's own Undertow listener.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so the reason phrase, the {@code Content-Type} header and the body bytes are asserted exactly as the
 * listener writes them. The happy-path cases cover every method, because the original listener declares no
 * {@code allowedMethods}; the error cases are the listener-level platform defaults of SC-1: the no-listener 404
 * for paths the flow does not serve, {@code /helloWorld} with {@code ;} segment parameters included, and the JSON
 * 500 for an exception no branch handles.
 *
 * <p>The original {@code HelloWorldIT} assertion is ported separately in {@code HelloWorldTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HelloWorldControllerTest {

    private static final String HELLO_WORLD_PATH = "/helloWorld";

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void getHelloWorldReturns200OkWithHelloWorldText() {
        assertHelloWorld(client().get(HELLO_WORLD_PATH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void everyMethodIsAccepted(String method) {
        HttpTestClient.Response response = client().send(method, HELLO_WORLD_PATH,
                Map.of("Content-Type", "text/plain"), "ping".getBytes(StandardCharsets.UTF_8));

        assertHelloWorld(response);
    }

    /**
     * The flow reads no form fields, so a form body that cannot be parsed never fails the request: Boot's form
     * filter is disabled in {@code application.yml}, and PUT, PATCH and DELETE get the reply POST gets instead
     * of a 500 raised before dispatch.
     *
     * @param method the request method, sent with the same form body each time
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE", "POST"})
    void malformedFormBodyIsAnsweredLikeEveryOtherBody(String method) {
        HttpTestClient.Response response = client().send(method, HELLO_WORLD_PATH,
                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE),
                "a=1&b=%ZZ".getBytes(StandardCharsets.UTF_8));

        assertHelloWorld(response);
    }

    @Test
    void optionsIsAnsweredLikeGet() {
        assertHelloWorld(client().send("OPTIONS", HELLO_WORLD_PATH, Map.of(), null));
    }

    @Test
    void acceptHeaderIsIgnored() {
        assertHelloWorld(client().get(HELLO_WORLD_PATH, Map.of("Accept", "application/json")));
    }

    @Test
    void unknownPathReturnsNoListener404() {
        assertNoListener(client().get("/unknown"), "/unknown");
    }

    @Test
    void subPathOfHelloWorldReturnsNoListener404() {
        assertNoListener(client().get("/helloWorld/extra"), "/helloWorld/extra");
    }

    /**
     * A path carrying {@code ;} segment parameters is not the listener's path: the Mule listener compared the raw
     * path, query removed, literally, so every method gets the no-listener 404 naming the path with its parameters,
     * and the flow never runs ({@code config/ListenerPathConfig}).
     *
     * @param method the request method; {@code POST} carries a {@code text/plain} body
     * @param path   {@code /helloWorld} followed by its segment parameters
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pathParameterRequests")
    void pathWithSegmentParametersReturnsNoListener404(String method, String path) {
        boolean withBody = "POST".equals(method);
        HttpTestClient.Response response = client().send(method, path,
                withBody ? Map.of("Content-Type", "text/plain") : Map.of(),
                withBody ? "ping".getBytes(StandardCharsets.UTF_8) : null);

        assertNoListener(response, path);
    }

    private static Stream<Arguments> pathParameterRequests() {
        return Stream.of("GET", "POST", "OPTIONS").flatMap(method ->
                Stream.of(HELLO_WORLD_PATH + ";x=1", HELLO_WORLD_PATH + ";jsessionid=ABC", HELLO_WORLD_PATH + ";")
                        .map(path -> Arguments.of(method, path)));
    }

    /**
     * A failing {@link HelloWorldService} reaches the SC-1 catch-all. The mock gives this class its own
     * application context, so its listener runs on its own random port.
     */
    @Nested
    class WhenServiceFails {

        @MockBean
        private HelloWorldService helloWorldService;

        @LocalServerPort
        private int failingPort;

        @BeforeEach
        void stubFailure() {
            when(helloWorldService.hello()).thenThrow(new RuntimeException("boom"));
        }

        @Test
        void serviceFailureReturns500JsonMessage() {
            HttpTestClient.Response response = HttpTestClient.localhost(failingPort).get(HELLO_WORLD_PATH);

            assertEquals(500, response.status(), response::toString);
            assertEquals("Internal Server Error", response.reason(), response::toString);
            assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
            assertEquals("{ \"message\": \"boom\" }", response.bodyAsString(), response::toString);
            verify(helloWorldService).hello();
        }
    }

    /**
     * The original's {@code set-payload} declares no MIME type, so its listener sent no {@code Content-Type}: the
     * reply carries none, only the length of its 11 bytes.
     */
    private static void assertHelloWorld(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("11", response.header("Content-Length"), response::toString);
        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /**
     * Compares the whole value: SC-1 presets the bare media type on the response entity, so the listener writes
     * exactly one {@code Content-Type} header carrying that value and no charset parameter.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }

    private static void assertNoListener(HttpTestClient.Response response, String path) {
        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: " + path, response.bodyAsString(), response::toString);
    }
}
