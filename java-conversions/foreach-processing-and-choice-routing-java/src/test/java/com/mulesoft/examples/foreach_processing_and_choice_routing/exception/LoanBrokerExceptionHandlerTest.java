/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import com.mulesoft.examples.foreach_processing_and_choice_routing.controller.LoanBrokerController;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.LoanBrokerService;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.ws.client.WebServiceIOException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link LoanBrokerExceptionHandler}, the {@code catch-exception-strategy} of flow {@code loan-broker-sync}
 * (foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:62-64): any exception raised behind the
 * loan-broker listener is logged at ERROR and answered 200 with {@code Error processing loan request}, framed like a
 * quote, with a {@code Content-Length} and no {@code Content-Type}.
 *
 * <p>Standalone MockMvc carries the real {@link LoanBrokerController} over a mocked {@link LoanBrokerService}, plus a
 * control controller, with the two advices registered on purpose in reverse precedence order: Spring sorts advices by
 * {@code @Order}, so only the handler's {@code @Order(0)} lets the catch branch win over SC-1's catch-all
 * {@link Exception} method, and only its {@code assignableTypes} keeps it off the control controller, whose failure
 * must still become SC-1's 500. A precedence or scoping defect therefore fails here and must be fixed in the advice,
 * never by reordering this registration.
 *
 * <p>The handler's logger is captured by a Logback {@link ListAppender}, attached at {@code ALL} for each test and
 * detached with the logger's level restored afterwards. The required-field branch ({@code Error: incomplete request},
 * line 58) is a normal return of {@code LoanBrokerService.quote}, not an exception, and is tested with the service.
 */
class LoanBrokerExceptionHandlerTest {

    /** The catch strategy's {@code set-payload} value, copied from loanbroker-simple.xml:63. */
    private static final String FLOW_PAYLOAD = "Error processing loan request";

    private static final byte[] PAYLOAD_BYTES = FLOW_PAYLOAD.getBytes(StandardCharsets.UTF_8);

    /** The original IT's request: every required field present, so the service is called. */
    private static final String QUOTE_URI = "/?name=Muley&ssn=1234&amount=20000&term=48";

    private static final String LOG_PREFIX = "Caught exception in Exception Strategy: ";

    /**
     * Message of the failures handed to the handler directly; distinctive, so that its absence from the logged line,
     * which names this test's own frames, is meaningful.
     */
    private static final String DIRECT_FAILURE_MESSAGE = "direct-failure-sentinel-7c1e";

    private LoanBrokerService service;

    private MockMvc mockMvc;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = mock(LoanBrokerService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LoanBrokerController(service), new OtherThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(), new LoanBrokerExceptionHandler())
                .build();
        logger = (Logger) LoggerFactory.getLogger(LoanBrokerExceptionHandler.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("catch-exception-strategy advice: @Order(0), scoped to LoanBrokerController alone, flow's payload")
    void adviceIsOrderedFirstScopedToTheLoanBrokerAndCarriesTheFlowPayload() {
        Order order = LoanBrokerExceptionHandler.class.getAnnotation(Order.class);
        assertNotNull(order, "@Order missing");
        assertEquals(0, order.value());

        ControllerAdvice advice = LoanBrokerExceptionHandler.class.getAnnotation(ControllerAdvice.class);
        assertNotNull(advice, "@ControllerAdvice missing");
        assertArrayEquals(new Class<?>[] {LoanBrokerController.class}, advice.assignableTypes());
        // @ControllerAdvice applies to a controller matching any one selector, so any other would widen the scope.
        assertEquals(0, advice.value().length);
        assertEquals(0, advice.basePackages().length);
        assertEquals(0, advice.basePackageClasses().length);
        assertEquals(0, advice.annotations().length);

        assertEquals(FLOW_PAYLOAD, LoanBrokerExceptionHandler.ERROR_PAYLOAD);
    }

    static Stream<Exception> loanBrokerFailures() {
        return Stream.of(
                new RuntimeException("boom"),
                new NumberFormatException("For input string: \"abc\""),
                new WebServiceIOException("I/O error: Connection refused"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("loanBrokerFailures")
    @DisplayName("catch-exception-strategy → Error processing loan request, for any exception the broker raises")
    void loanBrokerFailureIsAnsweredWithTheCatchStrategyPayload(Exception failure) throws Exception {
        when(service.quote(any(), any(), any(), any())).thenThrow(failure);

        MvcResult result = performExpectingCatchStrategyReply(get(QUOTE_URI));

        assertSame(failure, result.getResolvedException());
        verify(service).quote("Muley", "1234", "20000", "48");
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("catch-exception-strategy → Error processing loan request on a POST (no allowedMethods)")
    void postFailureReachesTheSameCatchStrategy() throws Exception {
        RuntimeException failure = new RuntimeException("boom");
        when(service.quote(any(), any(), any(), any())).thenThrow(failure);

        MvcResult result = performExpectingCatchStrategyReply(post(QUOTE_URI));

        assertSame(failure, result.getResolvedException());
        verify(service).quote("Muley", "1234", "20000", "48");
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("catch-exception-strategy → Error processing loan request replaces uncommitted partial output")
    void uncommittedPartialOutputIsDiscardedBeforeThePayloadIsWritten() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.getWriter().write("partial");
        assertFalse(response.isCommitted(), "precondition: partial output must stay uncommitted");
        assertEquals("partial", response.getContentAsString(), "precondition: partial output must be buffered");

        RuntimeException failure = new RuntimeException(DIRECT_FAILURE_MESSAGE);
        new LoanBrokerExceptionHandler().handle(failure, response);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertBytes(PAYLOAD_BYTES, response.getContentAsByteArray());
        assertNull(response.getContentType(), "the catch strategy's reply carries no Content-Type");
        assertEquals(PAYLOAD_BYTES.length, response.getContentLength());
        assertTrue(response.isCommitted(), "the payload is flushed before the dispatcher regains control");
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("catch-exception-strategy logs but leaves an already committed reply as it is")
    void committedReplyIsLeftAsItIs() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        byte[] partial = "partial".getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(partial);
        response.flushBuffer();

        RuntimeException failure = new RuntimeException(DIRECT_FAILURE_MESSAGE);
        new LoanBrokerExceptionHandler().handle(failure, response);

        assertEquals(HttpServletResponse.SC_ACCEPTED, response.getStatus());
        assertBytes(partial, response.getContentAsByteArray());
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("another controller's exception bypasses the catch strategy and reaches SC-1's 500")
    void otherControllerFailureReachesTheGlobalHandler() throws Exception {
        MvcResult result = mockMvc.perform(get("/other"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertJsonContentType(result);
        assertBytes("{ \"message\": \"boom\" }".getBytes(StandardCharsets.UTF_8),
                result.getResponse().getContentAsByteArray());
        IllegalStateException resolved = assertInstanceOf(IllegalStateException.class, result.getResolvedException());
        assertEquals("boom", resolved.getMessage());
        assertTrue(appender.list.isEmpty(), () -> "the catch strategy handled it: " + appender.list);
        verifyNoInteractions(service);
    }

    /**
     * Performs {@code request} and asserts the catch strategy's reply as the loan-broker controller frames a quote:
     * 200, exactly the payload's UTF-8 bytes, their {@code Content-Length} and no {@code Content-Type}.
     */
    private MvcResult performExpectingCatchStrategyReply(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().bytes(PAYLOAD_BYTES))
                .andExpect(content().string(FLOW_PAYLOAD))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andReturn();
        assertNull(result.getResponse().getContentType(), "the catch strategy's reply carries no Content-Type");
        assertEquals(PAYLOAD_BYTES.length, result.getResponse().getContentLength());
        return result;
    }

    /**
     * Asserts the one ERROR event the catch strategy logs for {@code failure}. The handler names the failure by its
     * class, throw site and cause classes and never attaches the throwable or its message, because a
     * {@link NumberFormatException}'s message, and so the first line of its stack trace, quotes the caller's
     * {@code ssn}, {@code amount} or {@code term}; the event therefore carries no throwable proxy and no message
     * text, and the class name is read from the logged line instead.
     */
    private void assertSingleCatchStrategyEvent(Exception failure) {
        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), () -> "events: " + events);
        ILoggingEvent event = events.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        String line = event.getFormattedMessage();
        assertTrue(line.startsWith(LOG_PREFIX), line);
        String loggedType = line.substring(LOG_PREFIX.length()).split(" ", 2)[0];
        assertEquals(failure.getClass().getName(), loggedType, line);
        assertNull(event.getThrowableProxy(), "the throwable must not be attached: " + line);
        assertFalse(line.contains(failure.getMessage()), () -> "exception message logged: " + line);
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        assertArrayEquals(expected, actual, () -> "body was: " + new String(actual, StandardCharsets.UTF_8));
    }

    /** Compares type and subtype only: the string converter may append a {@code charset} parameter. */
    private static void assertJsonContentType(MvcResult result) {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "Content-Type missing");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON),
                () -> "expected application/json but Content-Type was " + contentType);
    }

    /**
     * A controller the handler's {@code assignableTypes} does not name. Its path is more specific than the loan
     * broker's {@code /**}, so it wins the mapping, and its failure must reach SC-1.
     */
    @RestController
    static class OtherThrowingController {

        @GetMapping("/other")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }
}
