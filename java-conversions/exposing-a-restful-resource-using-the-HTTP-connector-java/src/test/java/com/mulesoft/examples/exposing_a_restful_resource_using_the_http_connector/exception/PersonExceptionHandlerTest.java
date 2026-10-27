/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.support.HttpTestClient;

/**
 * Tests {@link PersonExceptionHandler}, the {@code choice-exception-strategy} of {@code retrievePersonFlow}
 * ({@code exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:37-42}).
 *
 * <p>In the original, the throwing {@code message-filter} (lines 33-35) rejected an unknown {@code personId}, the
 * {@code rollback-exception-strategy} selected by {@code FilterUnacceptedException} set the flow variables
 * {@code errorStatusCode} = {@code 404} and {@code errorReasonPhrase} = {@code Not Found} (lines 39-40), and the
 * listener's {@code http:error-response-builder} (line 29) put both on the status line of an empty response.
 * Three layers check that this branch survives as its own {@code @ExceptionHandler} returning a
 * {@link ResponseEntity}:
 * <ul>
 *   <li>On the wire: the real application on Undertow at a random port, called through the project's ST-1 client,
 *       answers {@code GET /person/999} with the raw status line {@code HTTP/1.1 404 Not Found}, no body and no
 *       Content-Type, so neither of SC-1's {@link GlobalExceptionHandler} answers (its JSON 500 or its
 *       {@code text/plain} no-listener 404) can stand in for it.</li>
 *   <li>Precedence: standalone MockMvc registers SC-1 first and this handler second, the reverse of their
 *       precedence, so only {@code @Order} lets the 404 win over SC-1's catch-all {@link Exception} method, while an
 *       exception the original branch does not name still reaches SC-1's 500. A precedence defect shows up here as a
 *       500 and must be fixed in the advice, never by reordering this registration.</li>
 *   <li>Contract: the branch's two constants, the single exception type the handler declares, its {@code @Order}
 *       relative to SC-1, its ERROR log line and the exception's id and filter message.</li>
 * </ul>
 *
 * <p>MockMvc cannot observe a reason phrase; that the standalone request completes shows SC-2's
 * {@code ReasonPhrase.set} is a no-op without an Undertow exchange, and the wire test shows the phrase is sent.
 */
// No classes attribute and no context-altering annotation: the context is the application found by package, so
// it is cached and shared with the project's other application-level tests.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonExceptionHandlerTest {

    /**
     * An id the person store does not hold even in a context shared with other tests: ids come from a counter
     * starting at 1, and this class stores no person.
     */
    private static final int UNKNOWN_PERSON_ID = 999;

    /** SC-1's 500 body for {@code Integer.valueOf("abc")}, with APIkit's spacing. */
    private static final String NUMBER_FORMAT_BODY = "{ \"message\": \"For input string: \\\"abc\\\"\" }";

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(),
                                     new PersonExceptionHandler())
                .build();
    }

    @Test
    void getUnknownPersonReturns404NotFoundWithEmptyBody() {
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/person/" + UNKNOWN_PERSON_ID);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(PersonExceptionHandler.ERROR_STATUS_CODE, response.status(), response::toString);
        assertEquals(PersonExceptionHandler.ERROR_REASON_PHRASE, response.reason(), response::toString);
        assertEquals("0", response.header(HttpHeaders.CONTENT_LENGTH), response::toString);
        assertEquals(0, response.body().length, response::toString);
        assertNull(response.header(HttpHeaders.CONTENT_TYPE), response::toString);
        String body = response.bodyAsString();
        assertFalse(body.contains("No listener for endpoint"), response::toString);
        assertFalse(body.contains("\"message\""), response::toString);
    }

    @Test
    void personNotFoundIsHandledByPersonHandlerAheadOfGlobal() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/person-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andReturn();

        assertEquals(PersonExceptionHandler.ERROR_STATUS_CODE, result.getResponse().getStatus());
        assertNull(result.getResponse().getContentType());
        assertEquals(0, result.getResponse().getContentAsByteArray().length);
        PersonNotFoundException resolved =
                assertInstanceOf(PersonNotFoundException.class, result.getResolvedException());
        assertEquals(UNKNOWN_PERSON_ID, resolved.getPersonId());
    }

    @Test
    void otherExceptionsStillReachGlobalHandler() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/number-format"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        assertEquals(NUMBER_FORMAT_BODY, ErrorResponse.json("For input string: \"abc\""));
        byte[] actual = result.getResponse().getContentAsByteArray();
        assertArrayEquals(NUMBER_FORMAT_BODY.getBytes(StandardCharsets.UTF_8), actual,
                () -> "body was: " + new String(actual, StandardCharsets.UTF_8));
        assertInstanceOf(NumberFormatException.class, result.getResolvedException());
    }

    @Test
    void handlerReturns404WithoutBodyWhenCalledDirectly() {
        ResponseEntity<Void> response = assertDoesNotThrow(
                () -> new PersonExceptionHandler().handlePersonNotFound(new PersonNotFoundException(UNKNOWN_PERSON_ID)));

        assertEquals(404, response.getStatusCode().value());
        assertFalse(response.hasBody());
        assertNull(response.getHeaders().getContentType());
    }

    @Test
    void handlerLogsTheRejectedPersonAtErrorLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(PersonExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PersonExceptionHandler().handlePersonNotFound(new PersonNotFoundException(UNKNOWN_PERSON_ID));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size(), () -> "events: " + appender.list);
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(event.getFormattedMessage().contains(String.valueOf(UNKNOWN_PERSON_ID)), event::getFormattedMessage);
    }

    @Test
    void branchConstantsAreTheOriginalFlowVariables() {
        assertEquals(404, PersonExceptionHandler.ERROR_STATUS_CODE);
        assertEquals("Not Found", PersonExceptionHandler.ERROR_REASON_PHRASE);
    }

    @Test
    void handlerDeclaresOnlyTheExceptionItsOriginalBranchNames() {
        List<Method> handlers = Arrays.stream(ReflectionUtils.getAllDeclaredMethods(PersonExceptionHandler.class))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, ExceptionHandler.class))
                .toList();
        List<Class<? extends Throwable>> declared = new ArrayList<>();
        for (Method handler : handlers) {
            ExceptionHandler annotation = AnnotatedElementUtils.findMergedAnnotation(handler, ExceptionHandler.class);
            assertNotNull(annotation);
            declared.addAll(Arrays.asList(annotation.value()));
        }

        assertEquals(List.of(PersonNotFoundException.class), declared);
        assertEquals(1, handlers.size());
        assertEquals(ResponseEntity.class, handlers.get(0).getReturnType());
        assertTrue(AnnotatedElementUtils.hasAnnotation(PersonExceptionHandler.class, ControllerAdvice.class));
    }

    @Test
    void personHandlerPrecedesGlobalHandler() {
        Order person = AnnotationUtils.findAnnotation(PersonExceptionHandler.class, Order.class);
        Order global = AnnotationUtils.findAnnotation(GlobalExceptionHandler.class, Order.class);

        assertNotNull(person);
        assertNotNull(global);
        assertEquals(0, person.value());
        assertEquals(Ordered.LOWEST_PRECEDENCE, global.value());
        assertTrue(person.value() < global.value());
    }

    @Test
    void personNotFoundExceptionCarriesIdAndFilterMessage() {
        PersonNotFoundException exception = new PersonNotFoundException(UNKNOWN_PERSON_ID);

        assertEquals(UNKNOWN_PERSON_ID, exception.getPersonId());
        assertEquals("Message has been rejected by filter. Message payload is of type: NullPayload",
                PersonNotFoundException.MESSAGE);
        assertEquals(PersonNotFoundException.MESSAGE, exception.getMessage());
    }

    @Test
    void probeControllerStaysOutOfTheApplicationContext() {
        assertEquals(0, applicationContext.getBeanNamesForType(ThrowingController.class).length);
    }

    /**
     * Raises the branch's exception and, as a control, one the original branch does not name, without calling
     * any service, so the two advices are the only behaviour under test. As a static nested class of a test class it
     * is excluded from the application's component scan, so its probe paths never reach the real context.
     */
    @RestController
    static class ThrowingController {

        @GetMapping("/probe/person-not-found")
        String personNotFound() {
            throw new PersonNotFoundException(UNKNOWN_PERSON_ID);
        }

        @GetMapping("/probe/number-format")
        String numberFormat() {
            return String.valueOf(Integer.valueOf("abc"));
        }
    }
}
