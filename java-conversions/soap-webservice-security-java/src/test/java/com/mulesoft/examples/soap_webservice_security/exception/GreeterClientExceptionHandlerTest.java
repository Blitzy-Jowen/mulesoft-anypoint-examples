/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;

import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;
import com.mulesoft.examples.soap_webservice_security.controller.GreeterClientController;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link GreeterClientExceptionHandler}, the {@code catch-exception-strategy} of flow {@code SecurityClients}
 * (soap-webservice-security/src/main/app/service-clients.xml:36-39): any exception raised behind the {@code /client}
 * listener is logged at ERROR and answered 200 {@code text/plain} with
 * {@code There has been an Error processing the request}, because the strategy marks the exception handled.
 *
 * <p>Standalone MockMvc carries the real {@link GreeterClientController} over a mocked {@link SecureGreeterClient},
 * plus a control controller, with the two advices registered on purpose in reverse precedence order: Spring sorts
 * advices by {@code @Order}, so only the handler's {@code @Order(0)} lets the catch branch win over SC-1's catch-all
 * {@link Exception} method, and only its {@code assignableTypes} keeps it off the control controller, whose failure
 * must still become SC-1's 500 JSON. A precedence or scoping defect therefore fails here and must be fixed in the
 * advice, never by reordering this registration.
 *
 * <p>The handler's logger is captured by a Logback {@link ListAppender}, attached at {@code ALL} for each test and
 * detached with the logger's level restored afterwards. The choice's {@code otherwise} ({@code Client type is not
 * supported}, line 32) is a normal return of {@code SecureGreeterClient.invoke}, not an exception, and is tested with
 * the client. Wire-level checks (Undertow status line, port routing) belong to {@code GreeterClientControllerTest}.
 */
class GreeterClientExceptionHandlerTest {

    /** The catch strategy's {@code set-payload} value, copied from service-clients.xml:37. */
    private static final String FLOW_PAYLOAD = "There has been an Error processing the request";

    private static final byte[] PAYLOAD_BYTES = FLOW_PAYLOAD.getBytes(StandardCharsets.UTF_8);

    private static final String NAME = "Mule";

    private static final String LOG_PREFIX = "Caught exception in Exception Strategy: ";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String FAULT_REASON = "The security token could not be authenticated or authorized";

    private SecureGreeterClient client;

    private MockMvc mockMvc;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        client = mock(SecureGreeterClient.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GreeterClientController(client), new OtherThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler(), new GreeterClientExceptionHandler())
                .build();
        logger = (Logger) LoggerFactory.getLogger(GreeterClientExceptionHandler.class);
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
    @DisplayName("catch-exception-strategy advice: @Order(0), scoped to GreeterClientController alone, one branch")
    void adviceIsOrderedFirstScopedToTheClientFrontAndHasASingleBranch() {
        Order order = AnnotationUtils.findAnnotation(GreeterClientExceptionHandler.class, Order.class);
        assertNotNull(order, "@Order missing");
        assertEquals(0, order.value());

        RestControllerAdvice advice =
                AnnotationUtils.findAnnotation(GreeterClientExceptionHandler.class, RestControllerAdvice.class);
        assertNotNull(advice, "@RestControllerAdvice missing");
        assertArrayEquals(new Class<?>[] {GreeterClientController.class}, advice.assignableTypes());
        // @ControllerAdvice applies to a controller matching any one selector, so any other would widen the scope.
        assertEquals(0, advice.value().length);
        assertEquals(0, advice.basePackages().length);
        assertEquals(0, advice.basePackageClasses().length);
        assertEquals(0, advice.annotations().length);

        // The strategy has one branch for every exception type, so exactly one Exception method may exist.
        List<Method> handlers = Arrays.stream(GreeterClientExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .toList();
        assertEquals(1, handlers.size(), () -> "@ExceptionHandler methods: " + handlers);
        assertArrayEquals(new Class<?>[] {Exception.class},
                handlers.get(0).getAnnotation(ExceptionHandler.class).value());
    }

    @Test
    @DisplayName("ERROR_TEXT is the catch strategy's set-payload value")
    void errorTextIsTheFlowPayload() {
        assertEquals("There has been an Error processing the request", GreeterClientExceptionHandler.ERROR_TEXT);
    }

    @Test
    @DisplayName("the SOAP fault fixture is a real SoapFaultClientException carrying the fault reason")
    void soapFaultFixtureCarriesTheFaultReason() {
        SoapMessage message = faultMessage();
        SoapFaultClientException fault = new SoapFaultClientException(message);

        assertEquals(FAULT_REASON, fault.getMessage());
        assertSame(message, fault.getWebServiceMessage());
        assertNotNull(fault.getSoapFault(), "the deep-stubbed body supplies a fault");
    }

    static Stream<Arguments> clientFailures() {
        return Stream.of(
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_UNSECURE, connectionRefused()),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN,
                        new SoapFaultClientException(faultMessage())),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_USERNAME_TOKEN_SIGNED,
                        new IllegalStateException("Keystore keystore.jks could not be loaded")),
                Arguments.of(SecureGreeterClient.CLIENT_TYPE_SAML_TOKEN_SIGNED,
                        new RuntimeException("WSS4J securement failed")));
    }

    @ParameterizedTest(name = "[{index}] clientType={0}")
    @MethodSource("clientFailures")
    @DisplayName("catch-exception-strategy → 200 text/plain error text, for any exception the client raises")
    void clientFailureIsAnsweredWithTheCatchStrategyPayload(String clientType, RuntimeException failure)
            throws Exception {
        when(client.invoke(clientType, NAME)).thenThrow(failure);

        MvcResult result = performExpectingCatchStrategyReply(get(clientUri(clientType)));

        assertSame(failure, result.getResolvedException());
        verify(client).invoke(clientType, NAME);
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("catch-exception-strategy → 200 text/plain error text on a POST (no allowedMethods)")
    void postFailureReachesTheSameCatchStrategy() throws Exception {
        WebServiceIOException failure = connectionRefused();
        when(client.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME)).thenThrow(failure);

        MvcResult result =
                performExpectingCatchStrategyReply(post(clientUri(SecureGreeterClient.CLIENT_TYPE_UNSECURE)));

        assertSame(failure, result.getResolvedException());
        verify(client).invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME);
        assertSingleCatchStrategyEvent(failure);
    }

    @Test
    @DisplayName("a greeting is answered by the controller itself, and the catch strategy stays silent")
    void successfulGreetingBypassesTheCatchStrategy() throws Exception {
        when(client.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME)).thenReturn("Hello Mule");

        MvcResult result = mockMvc.perform(get(clientUri(SecureGreeterClient.CLIENT_TYPE_UNSECURE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Hello Mule"))
                .andReturn();

        assertNull(result.getResolvedException());
        verify(client).invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME);
        assertTrue(appender.list.isEmpty(), () -> "the catch strategy logged a success: " + appender.list);
    }

    @Test
    @DisplayName("catch-exception-strategy reply carries the greeting's Content-Type (service-clients.xml:35, 38)")
    void catchStrategyReplySharesTheGreetingContentType() throws Exception {
        when(client.invoke(SecureGreeterClient.CLIENT_TYPE_UNSECURE, NAME))
                .thenReturn("Hello Mule")
                .thenThrow(connectionRefused());

        String greetingType = mockMvc.perform(get(clientUri(SecureGreeterClient.CLIENT_TYPE_UNSECURE)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentType();
        String errorType = performExpectingCatchStrategyReply(get(clientUri(SecureGreeterClient.CLIENT_TYPE_UNSECURE)))
                .getResponse().getContentType();

        assertNotNull(greetingType, "Content-Type missing on the greeting");
        assertEquals(greetingType, errorType);
    }

    @Test
    @DisplayName("another controller's exception bypasses the catch strategy and reaches SC-1's 500")
    void otherControllerFailureReachesTheGlobalHandler() throws Exception {
        MvcResult result = mockMvc.perform(get("/other"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertBytes("{ \"message\": \"boom\" }".getBytes(StandardCharsets.UTF_8), body);
        assertBytes(ErrorResponse.json("boom").getBytes(StandardCharsets.UTF_8), body);
        IllegalStateException resolved = assertInstanceOf(IllegalStateException.class, result.getResolvedException());
        assertEquals("boom", resolved.getMessage());
        assertTrue(appender.list.isEmpty(), () -> "the catch strategy handled it: " + appender.list);
        verifyNoInteractions(client);
    }

    /**
     * Performs {@code request} and asserts the catch strategy's reply: 200, a {@code text/plain} Content-Type and
     * exactly the payload's bytes.
     */
    private MvcResult performExpectingCatchStrategyReply(RequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(GreeterClientExceptionHandler.ERROR_TEXT))
                .andExpect(content().bytes(PAYLOAD_BYTES))
                .andReturn();
    }

    /**
     * Asserts the one ERROR event the catch strategy logs for {@code failure}. The handler names the failure by its
     * class, throw site and cause classes and never attaches the throwable or its message, because a fault string or
     * transport error can repeat the caller's query; the event therefore carries no throwable proxy and no message
     * text, and the class names are read from the logged line instead.
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
        if (failure.getCause() != null) {
            assertTrue(line.contains(CAUSE_SEPARATOR + failure.getCause().getClass().getName()), line);
        }
        assertNull(event.getThrowableProxy(), "the throwable must not be attached: " + line);
        assertFalse(line.contains(failure.getMessage()), () -> "exception message logged: " + line);
    }

    /** The query the original README sends, {@code clientType} first; the controller reads the raw query string. */
    private static String clientUri(String clientType) {
        return "/client?clientType=" + clientType + "&name=" + NAME;
    }

    /** What {@code WebServiceTemplate} raises when the Greeter listener refuses the connection. */
    private static WebServiceIOException connectionRefused() {
        return new WebServiceIOException("I/O error: Connection refused", new ConnectException("Connection refused"));
    }

    /**
     * A fault response such as the Greeter service returns for a rejected security header. The message is mocked
     * rather than the exception, because a mocked {@link Throwable} has no stack trace and would not behave as the
     * real exception does when the handler names its throw site.
     */
    private static SoapMessage faultMessage() {
        SoapMessage message = mock(SoapMessage.class, RETURNS_DEEP_STUBS);
        when(message.getFaultReason()).thenReturn(FAULT_REASON);
        return message;
    }

    private static void assertBytes(byte[] expected, byte[] actual) {
        assertArrayEquals(expected, actual, () -> "body was: " + new String(actual, StandardCharsets.UTF_8));
    }

    /** A controller the handler's {@code assignableTypes} does not name; its failure must reach SC-1. */
    @RestController
    static class OtherThrowingController {

        @GetMapping("/other")
        String fail() {
            throw new IllegalStateException("boom");
        }
    }
}
