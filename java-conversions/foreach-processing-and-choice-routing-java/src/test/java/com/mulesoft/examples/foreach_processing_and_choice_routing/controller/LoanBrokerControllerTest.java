package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.config.PreflightDispatchConfig;
import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.LoanBrokerExceptionHandler;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.PortInUseRetryingContextLoader;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;

/**
 * Wire-level controller test of {@link LoanBrokerController}, the HTTP trigger of flow {@code loan-broker-sync}
 * (foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml, lines 4 and 20-65): an
 * {@code http:listener} on 11081 with {@code path="/*"} and no {@code allowedMethods}.
 *
 * <p>The whole application runs on Undertow: the main listener on a random port stands in for 11081, and the six
 * SOAP listeners (credit agency, Banks 1 to 5) on free ports drawn for each load of the context, never the original
 * 18080 or 10080 to 50080, so this context can live in the test context cache beside any other without a bind
 * collision. A port another process takes between its draw and its bind fails that load, and
 * {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports, so every test reads the ports
 * of the loaded context from its {@link Environment}. Both SOAP
 * clients are Spring mocks: the credit profile is fixed and each bank, recognised by the path of the bank URI the
 * service builds, answers a fixed quote, because the real credit agency and banks answer random values.
 *
 * <p>Every response is read off the socket by ST-1 {@link HttpTestClient}: the raw status line, the headers and
 * the body bytes. The flow's string payload carries no MIME type, so every loan-broker reply is asserted to carry
 * no {@code Content-Type}. Covered: the lowest quote of Banks 1 and 2 and of Banks 3 and 4 (the always-true second
 * condition, so Bank 5 is never asked), the required-field choice's {@code otherwise}, the catch strategy for a
 * non-numeric parameter and for each client failure, every HTTP method, every path of the main listener including
 * {@code /mule/**} and {@code /error}, the query-string-only parameter binding with a repeated name's last value,
 * and the loan broker's absence from the SOAP listeners. The original IT assertion itself (contains {@code Bank}
 * and {@code rate}) is ported by {@code ForEachProcessingAndChoiceRoutingTest}. The nested {@link UnderRootTrace}
 * and {@link UnderRootDebug} runs cover the logger levels {@code application.yml} pins, unmocked, in contexts of
 * their own.
 *
 * <p>"Every HTTP method" includes extension method tokens such as {@code PROPFIND}, and CORS pre-flights
 * ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}), which run the flow and are
 * answered exactly as a plain {@code OPTIONS} request, with no {@code Access-Control-*} or {@code Vary} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
class LoanBrokerControllerTest {

    /** The original IT's request, parameters in its order. */
    private static final String FULL_QUERY = "name=Muley&amount=20000&term=48&ssn=1234";

    private static final String QUOTE_BANK_2 = "Bank #2, rate: 2.25";
    private static final String QUOTE_BANK_4 = "Bank #4, rate: 1.75";
    private static final String INCOMPLETE_REQUEST = "Error: incomplete request";

    /** {@code Origin} of the CORS pre-flights. */
    private static final String PREFLIGHT_ORIGIN = "https://example.invalid";

    /** Prefix of the CORS response headers, compared ignoring case. */
    private static final String ACCESS_CONTROL_PREFIX = "Access-Control-";

    /** The original's fixed listener ports, which this class never binds. */
    private static final Set<Integer> ORIGINAL_PORTS = Set.of(11081, 18080, 10080, 20080, 30080, 40080, 50080);

    private static final int BANK_COUNT = 5;

    /** Property key of the credit agency listener's port. */
    private static final String CREDIT_AGENCY_PORT_KEY = "ports.credit-agency";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @MockBean
    private CreditAgencyClient creditAgencyClient;

    @MockBean
    private BankClient bankClient;

    private CreditProfile creditProfile;

    private HttpTestClient http;

    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        registerDrawnListenerPorts(registry);
    }

    @BeforeEach
    void stubClients() {
        creditProfile = new CreditProfile();
        creditProfile.setCreditScore(750);
        creditProfile.setCreditHistory(10);
        when(creditAgencyClient.getCreditProfile(any())).thenReturn(creditProfile);

        stubBank(1, "Bank #1", 3.5);
        stubBank(2, "Bank #2", 2.25);
        stubBank(3, "Bank #3", 4.0);
        stubBank(4, "Bank #4", 1.75);

        http = HttpTestClient.localhost(port);
    }

    @Test
    void getWithEveryParameterAnswersTheLowestQuoteOfBanksOneAndTwo() {
        HttpTestClient.Response response = http.get("/?" + FULL_QUERY);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(QUOTE_BANK_2.getBytes(UTF_8), response.body(), response::toString);
        assertEquals(String.valueOf(QUOTE_BANK_2.getBytes(UTF_8).length), response.header("Content-Length"),
                response::toString);

        ArgumentCaptor<Customer> customer = ArgumentCaptor.forClass(Customer.class);
        verify(creditAgencyClient).getCreditProfile(customer.capture());
        assertEquals("Muley", customer.getValue().getName());
        assertEquals(1234, customer.getValue().getSsn());

        List<LoanBrokerQuoteRequest> requests = assertBanksAsked(1, 2);
        requests.forEach(request -> assertSame(creditProfile, request.getCreditProfile()));
    }

    /**
     * Every amount below 20000 selects Banks 3 and 4: the second condition {@code amount >= 10000 ||
     * amount <= 19999} holds for all of them, so the {@code otherwise} (Bank 5) is never reached (AAP 0.8.2).
     */
    @ParameterizedTest(name = "amount={0}")
    @ValueSource(ints = {19999, 15000, 10000, 9999, 0, -1})
    void amountBelowTwentyThousandAnswersTheLowestQuoteOfBanksThreeAndFour(int amount) {
        HttpTestClient.Response response = http.get(target(withParam("amount", String.valueOf(amount))));

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(QUOTE_BANK_4, response.bodyAsString(), response::toString);
        assertBanksAsked(3, 4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteRequests")
    void missingRequiredParameterAnswersIncompleteRequest(String description, String target) {
        HttpTestClient.Response response = http.get(target);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(INCOMPLETE_REQUEST.getBytes(UTF_8), response.body(), response::toString);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    static Stream<Arguments> incompleteRequests() {
        Stream<Arguments> withoutOne = Stream.of("name", "ssn", "amount", "term").map(name -> {
            Map<String, String> params = fullParams();
            params.remove(name);
            return Arguments.of("without " + name, target(params));
        });
        // A pair without '=' gives its name a null value, as Mule's http.query.params did.
        Map<String, String> nameWithoutValue = fullParams();
        nameWithoutValue.put("name", null);
        // A later pair without '=' leaves its name null, because ParameterMap.get returned the last value.
        Stream<Arguments> laterWithoutValue = Stream.of("name", "ssn", "amount", "term").map(name ->
                Arguments.of(name + " repeated without '='", target(fullParams()) + "&" + name));
        return Stream.concat(Stream.concat(withoutOne, laterWithoutValue), Stream.of(
                Arguments.of("no query string", "/"),
                Arguments.of("empty query string", "/?"),
                Arguments.of("name without '='", target(nameWithoutValue))));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("nonNumericParameters")
    void nonNumericParameterAnswersTheCatchStrategyPayload(String name, String value) {
        HttpTestClient.Response response = http.get(target(withParam(name, value)));

        assertCatchStrategyReply(response);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    static Stream<Arguments> nonNumericParameters() {
        return Stream.of(
                Arguments.of("amount", "abc"),
                Arguments.of("ssn", "abc"),
                Arguments.of("term", "abc"),
                Arguments.of("amount", ""));
    }

    @Test
    void creditAgencyFailureAnswersTheCatchStrategyPayload() {
        when(creditAgencyClient.getCreditProfile(any())).thenThrow(new WebServiceIOException("connection refused"));

        HttpTestClient.Response response = http.get("/?" + FULL_QUERY);

        assertCatchStrategyReply(response);
        verifyNoInteractions(bankClient);
    }

    @Test
    void bankFailureAnswersTheCatchStrategyPayloadAndStopsTheForeach() {
        when(bankClient.getLoanQuote(argThat(isBank(1)), any()))
                .thenThrow(new WebServiceIOException("connection refused"));

        HttpTestClient.Response response = http.get("/?" + FULL_QUERY);

        assertCatchStrategyReply(response);
        assertBanksAsked(1);
    }

    /**
     * The listener declares no {@code allowedMethods}, so every method token runs the flow, the extension methods
     * {@code PROPFIND} and {@code MKCOL} included.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "PROPFIND", "MKCOL"})
    void everyMethodRunsTheLoanBroker(String method) {
        byte[] emptyBody = Set.of("POST", "PUT", "PATCH").contains(method) ? new byte[0] : null;

        HttpTestClient.Response response = http.send(method, "/?" + FULL_QUERY, null, emptyBody);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        // OPTIONS and TRACE are answered by the flow, not by Spring MVC's Allow reply or message/http echo.
        assertNull(response.header("Allow"), response::toString);
        assertEquals(QUOTE_BANK_2, response.bodyAsString(), response::toString);
        assertBanksAsked(1, 2);
    }

    @Test
    void headRunsTheLoanBrokerAndAnswersWithoutABody() {
        HttpTestClient.Response response = http.send("HEAD", "/?" + FULL_QUERY, null, null);

        assertEquals(200, response.status(), response::toString);
        assertEquals(0, response.body().length, response::toString);
        verify(creditAgencyClient).getCreditProfile(any());
    }

    /**
     * The Mule listener applied no CORS policy, so a CORS pre-flight, whatever method it asks for, runs the flow and
     * is answered exactly as a plain {@code OPTIONS} request to the same target: not with Spring's 403
     * {@code Invalid CORS request}, and without any {@code Access-Control-*} or {@code Vary} header.
     * {@code /mule/TheBank1} is forwarded to the loan broker by the main listener's port routing.
     */
    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}")
    @CsvSource({"/, GET", "/, POST", "/, PROPFIND", "/mule/TheBank1, POST"})
    void corsPreflightRunsTheLoanBrokerLikeAPlainOptionsRequest(String path, String requestedMethod) {
        String target = path + "?" + FULL_QUERY;

        HttpTestClient.Response plain = http.send("OPTIONS", target, null, null);
        HttpTestClient.Response preflight = http.send("OPTIONS", target, preflightHeaders(requestedMethod), null);

        assertSameReply(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertNull(preflight.header("Allow"), preflight::toString);
        assertNoCorsHeaders(preflight);
        assertEquals(QUOTE_BANK_2, preflight.bodyAsString(), preflight::toString);
        assertBanksAsked(1, 2, 1, 2);
    }

    /** {@code path="/*"} without a base path: every path on the main listener belongs to the loan broker. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/deep/path", "/mule", "/mule/TheBank1", "/mule/TheCreditAgencyService", "/error"})
    void everyPathOnTheMainListenerRunsTheLoanBroker(String path) {
        HttpTestClient.Response response = http.get(path + "?" + FULL_QUERY);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(QUOTE_BANK_2, response.bodyAsString(), response::toString);
        assertFalse(response.bodyAsString().contains("Envelope"), response::toString);
    }

    @Test
    void soapPostToABankPathOnTheMainListenerRunsTheLoanBroker() {
        String envelope = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<ns2:getLoanQuote xmlns:ns2=\"http://bank.loanbroker.example.mule.org/\"/>"
                + "</soap:Body></soap:Envelope>";

        HttpTestClient.Response response = http.post("/mule/TheBank1?" + FULL_QUERY, "text/xml; charset=UTF-8",
                envelope);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(QUOTE_BANK_2, response.bodyAsString(), response::toString);
        assertBanksAsked(1, 2);
    }

    /** The flow reads {@code http.query.params} only, so a form body supplies none of the required fields. */
    @Test
    void formBodyParametersAreNotRead() {
        HttpTestClient.Response response = http.post("/", "application/x-www-form-urlencoded", FULL_QUERY);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(INCOMPLETE_REQUEST, response.bodyAsString(), response::toString);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("customerNames")
    void customerNameIsTheLastDecodedQueryValue(String nameParams, String expectedName) {
        HttpTestClient.Response response = http.get("/?" + nameParams + "&amount=20000&term=48&ssn=1234");

        assertEquals(QUOTE_BANK_2, response.bodyAsString(), response::toString);
        ArgumentCaptor<Customer> customer = ArgumentCaptor.forClass(Customer.class);
        verify(creditAgencyClient).getCreditProfile(customer.capture());
        assertEquals(expectedName, customer.getValue().getName());
    }

    static Stream<Arguments> customerNames() {
        return Stream.of(
                Arguments.of("name=A&name=B", "B"),
                Arguments.of("name&name=B", "B"),
                Arguments.of("name=M%C3%BCley", "M\u00fcley"),
                Arguments.of("name=Muley+Smith", "Muley Smith"));
    }

    /** A repeated {@code amount} is routed on its last value, the one Mule's {@code ParameterMap.get} returned. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("repeatedAmounts")
    void repeatedAmountIsRoutedOnItsLastValue(String amountParams, String expectedQuote, int[] expectedBanks) {
        HttpTestClient.Response response = http.get("/?name=Muley&" + amountParams + "&term=48&ssn=1234");

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(expectedQuote, response.bodyAsString(), response::toString);
        assertBanksAsked(expectedBanks);
    }

    static Stream<Arguments> repeatedAmounts() {
        return Stream.of(
                Arguments.of("amount=1&amount=20000", QUOTE_BANK_2, new int[] {1, 2}),
                Arguments.of("amount=20000&amount=1", QUOTE_BANK_4, new int[] {3, 4}));
    }

    /** The SOAP listeners serve only their {@code /mule/<service>} paths, so the loan broker is not reachable there. */
    @ParameterizedTest(name = "port of {0}")
    @MethodSource("soapListenerPortKeys")
    void loanBrokerIsNotServedOnASoapListener(String listener, String portKey) {
        HttpTestClient.Response response = HttpTestClient.localhost(listenerPort(portKey)).get("/?" + FULL_QUERY);

        assertEquals(404, response.status(), response::toString);
        assertEquals("No listener for endpoint: /", response.bodyAsString(), response::toString);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    /** A CORS pre-flight to a path a SOAP listener does not serve gets that listener's 404, as plain OPTIONS does. */
    @ParameterizedTest(name = "port of {0}")
    @MethodSource("soapListenerPortKeys")
    void corsPreflightToAPathASoapListenerDoesNotServeAnswersLikeAPlainOptionsRequest(String listener,
            String portKey) {
        HttpTestClient soapListener = HttpTestClient.localhost(listenerPort(portKey));
        String target = "/?" + FULL_QUERY;

        HttpTestClient.Response plain = soapListener.send("OPTIONS", target, null, null);
        HttpTestClient.Response preflight = soapListener.send("OPTIONS", target, preflightHeaders("POST"), null);

        assertSameReply(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("No listener for endpoint: /", preflight.bodyAsString(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    /**
     * Each SOAP listener's name and the property key of its port: the arguments are built before the context loads,
     * so each test reads the port of the loaded context through {@link #listenerPort(String)}.
     */
    static Stream<Arguments> soapListenerPortKeys() {
        return Stream.concat(
                Stream.of(Arguments.of("credit agency", CREDIT_AGENCY_PORT_KEY)),
                IntStream.rangeClosed(1, BANK_COUNT).mapToObj(bank -> Arguments.of("Bank " + bank, bankPortKey(bank))));
    }

    /** The loan requests of {@link LoggedLoanRequests} under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=TRACE")
    class UnderRootTrace extends LoggedLoanRequests {

        @Override
        Level rootLevel() {
            return Level.TRACE;
        }
    }

    /** The loan requests of {@link LoggedLoanRequests} under {@code logging.level.root=DEBUG}. */
    @Nested
    @TestPropertySource(properties = "logging.level.root=DEBUG")
    class UnderRootDebug extends LoggedLoanRequests {

        @Override
        Level rootLevel() {
            return Level.DEBUG;
        }
    }

    /**
     * Tests the logger levels {@code application.yml} pins, which keep the applicant's name and ssn out of the log
     * under a low root level: {@code loanbroker-simple.xml} logs the credit profile, the banks, the quotes and the
     * rate, never the applicant.
     *
     * <p>The whole application runs unmocked, the real credit agency and banks answering over SOAP, on a random main
     * port and SOAP ports drawn for each load of this context, which never draws a port the mocked context of the
     * enclosing class holds, so it lives beside that context; a port lost between draw and bind is drawn again by
     * {@link PortInUseRetryingContextLoader}, declared here because this configuration overrides the enclosing one.
     * Each request is asserted on the wire, then its own log events are asserted to hold the original flow's logger
     * lines and a line of the root level from an unpinned logger, which proves the capture sees that level, and the
     * console output is asserted to hold neither the name nor the ssn. Each run restores the logger levels it found and
     * closes its context, so later test classes do not run at the low root level.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class LoggedLoanRequests {

        /** The applicant's name and ssn of the reproduction request, which must stay out of the log. */
        static final String APPLICANT_NAME = "Muley";
        static final String APPLICANT_SSN = "987654321";

        /** How long lines written after the reply get to arrive before the output is scanned for applicant data. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** Most offending lines a failure reports; the capture holds the whole run, so it is never dumped. */
        private static final int REPORTED_LINES = 20;

        /**
         * The logger of the request mapping that serves the main listener, whose unpinned {@code Mapped to} line of
         * every request proves the capture sees the root level. A Spring handler mapping logs under its runtime
         * class, and the application registers {@link PreflightDispatchConfig.PreflightDispatchHandlerMapping} in
         * place of {@link RequestMappingHandlerMapping}.
         */
        private static final String HANDLER_MAPPING_LOGGER =
                PreflightDispatchConfig.PreflightDispatchHandlerMapping.class.getName();

        /** The logger levels set before this run's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @LocalServerPort
        int port;

        /** The root level this run sets, which an unpinned logger's line of every request must carry. */
        abstract Level rootLevel();

        @DynamicPropertySource
        static void unmockedListenerPorts(DynamicPropertyRegistry registry) {
            // The enclosing class's context stays open in the context cache, and a port its listeners hold is never
            // drawn, because only a port that can be bound is.
            registerDrawnListenerPorts(registry);
        }

        @BeforeAll
        static void recordLevels() {
            LEVELS_BEFORE.clear();
            for (Logger logger : loggerContext().getLoggerList()) {
                if (logger.getLevel() != null) {
                    LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
                }
            }
        }

        @AfterAll
        static void restoreLevels() {
            for (Logger logger : loggerContext().getLoggerList()) {
                Level before = LEVELS_BEFORE.get(logger.getName());
                // The root logger always has a level and cannot be given none.
                if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                    logger.setLevel(before);
                }
            }
        }

        static Stream<LoggedRequest> loggedRequests() {
            String query = "name=" + APPLICANT_NAME + "&amount=20000&term=48&ssn=";
            return Stream.of(
                    new LoggedRequest("quote for the reproduction request", "/?" + query + APPLICANT_SSN,
                            "Bank #[12], rate: \\d+\\.\\d+(E-?\\d+)?", true,
                            List.of("Credit profile: ", "Banks to contact: ", "Returning Rate is: ", "LoanQuote from ",
                                    "Lowest loan quote: ")),
                    new LoggedRequest("catch strategy for a malformed ssn", "/?" + query + APPLICANT_SSN + "x",
                            Pattern.quote(LoanBrokerExceptionHandler.ERROR_PAYLOAD), false,
                            List.of("Caught exception in Exception Strategy: java.lang.NumberFormatException")),
                    new LoggedRequest("required-field otherwise without term",
                            "/?name=" + APPLICANT_NAME + "&amount=20000&ssn=" + APPLICANT_SSN,
                            Pattern.quote(INCOMPLETE_REQUEST), false, List.of()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("loggedRequests")
        void requestAnswersAsTheFlowWhileTheLogHoldsNoApplicantData(LoggedRequest request, CapturedOutput output) {
            Logger root = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertEquals(rootLevel(), root.getLevel(), "the root level this run sets");
            ListAppender<ILoggingEvent> events = new ListAppender<>();
            events.setContext(loggerContext());
            events.start();
            root.addAppender(events);
            HttpTestClient.Response response;
            try {
                response = HttpTestClient.localhost(port).get(request.target());
            } finally {
                root.detachAppender(events);
                events.stop();
            }
            // The appender appends under its own lock, so reading under it sees every event the request wrote.
            List<ILoggingEvent> logged;
            synchronized (events) {
                logged = List.copyOf(events.list);
            }

            assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
            assertTrue(response.bodyAsString().matches(request.body()), response::toString);

            ILoggingEvent mapped = logged.stream()
                    .filter(event -> event.getLevel() == rootLevel()
                            && HANDLER_MAPPING_LOGGER.equals(event.getLoggerName())
                            && event.getFormattedMessage()
                                    .startsWith("Mapped to " + LoanBrokerController.class.getName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(mapped,
                    "the unpinned PreflightDispatchHandlerMapping " + rootLevel() + " line of this request");
            if (request.soapCalls()) {
                assertTrue(logged.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                                && WebServiceTemplate.class.getName().equals(event.getLoggerName())
                                && event.getFormattedMessage().startsWith("Opening [")),
                        "the unpinned WebServiceTemplate DEBUG connection line of this request");
            }
            for (String line : request.flowLines()) {
                assertTrue(logged.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO)
                                && event.getFormattedMessage().startsWith(line)),
                        () -> "the original flow's logger line \"" + line + "\" of this request");
            }
            assertTrue(output.getAll().contains(mapped.getFormattedMessage()),
                    "the console capture holds the " + rootLevel() + " line of this request");
            // Lines written after the reply get SETTLE to arrive; the capture only grows, so one scan covers the wait.
            await().pollDelay(SETTLE).until(() -> true);
            assertEquals(List.of(), linesHoldingApplicantData(output.getAll()),
                    "console lines holding the applicant's name or ssn");
        }

        /** The distinct lines of {@code output} holding the name or the ssn, at most {@link #REPORTED_LINES}. */
        private static List<String> linesHoldingApplicantData(String output) {
            // The capture of a low-level run is large, so it is split into lines only when it holds a value at all.
            if (!output.contains(APPLICANT_NAME) && !output.contains(APPLICANT_SSN)) {
                return List.of();
            }
            return output.lines()
                    .filter(line -> line.contains(APPLICANT_NAME) || line.contains(APPLICANT_SSN))
                    .distinct()
                    .limit(REPORTED_LINES)
                    .toList();
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }
    }

    /**
     * One logged loan request and what it must produce.
     *
     * @param name      the case's display name
     * @param target    the request target, query included
     * @param body      the regular expression the whole reply body matches
     * @param soapCalls whether the request reaches the credit agency and the banks
     * @param flowLines the prefixes of the original logger lines the request writes at {@code INFO} or above
     */
    record LoggedRequest(String name, String target, String body, boolean soapCalls, List<String> flowLines) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static void assertCatchStrategyReply(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(LoanBrokerExceptionHandler.ERROR_PAYLOAD.getBytes(UTF_8), response.body(),
                response::toString);
    }

    /** The headers that make an {@code OPTIONS} request a CORS pre-flight asking for {@code requestedMethod}. */
    private static Map<String, String> preflightHeaders(String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", PREFLIGHT_ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        return headers;
    }

    /** Same status line, headers in the same order (the {@code Date} header aside) and the same body bytes. */
    private static void assertSameReply(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertEquals(withoutDate(expected.headers()), withoutDate(actual.headers()), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
    }

    private static List<Map.Entry<String, String>> withoutDate(List<Map.Entry<String, String>> headers) {
        return headers.stream().filter(header -> !"Date".equalsIgnoreCase(header.getKey())).toList();
    }

    /** No CORS processing took part: no {@code Access-Control-*} header and no {@code Vary} header. */
    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertFalse(response.headers().stream().anyMatch(header -> header.getKey()
                        .regionMatches(true, 0, ACCESS_CONTROL_PREFIX, 0, ACCESS_CONTROL_PREFIX.length())),
                response::toString);
        assertNull(response.header("Vary"), response::toString);
    }

    /**
     * Asserts that exactly the given banks were asked, in this order, at the URIs the service builds from
     * {@code loanbroker.outbound-host} and the loaded context's {@code ports.bankN}, and returns the requests they
     * were sent.
     */
    private List<LoanBrokerQuoteRequest> assertBanksAsked(int... banks) {
        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<LoanBrokerQuoteRequest> requests = ArgumentCaptor.forClass(LoanBrokerQuoteRequest.class);
        verify(bankClient, times(banks.length)).getLoanQuote(uris.capture(), requests.capture());
        assertEquals(Arrays.stream(banks).mapToObj(this::bankUri).toList(), uris.getAllValues());
        return requests.getAllValues();
    }

    private void stubBank(int bank, String bankName, double interestRate) {
        LoanQuote quote = new LoanQuote();
        quote.setBankName(bankName);
        quote.setInterestRate(interestRate);
        when(bankClient.getLoanQuote(argThat(isBank(bank)), any())).thenReturn(quote);
    }

    /** Matches the bank URI by path alone; null-safe because Mockito probes earlier stubs with null while stubbing. */
    private static ArgumentMatcher<URI> isBank(int bank) {
        String path = "/mule/TheBank" + bank;
        return uri -> uri != null && path.equals(uri.getPath());
    }

    private URI bankUri(int bank) {
        return URI.create("http://localhost:" + listenerPort(bankPortKey(bank)) + "/mule/TheBank" + bank);
    }

    /** The port the loaded context's listener of {@code portKey} is bound to. */
    private int listenerPort(String portKey) {
        return environment.getRequiredProperty(portKey, Integer.class);
    }

    private static String bankPortKey(int bank) {
        return "ports.bank" + bank;
    }

    /** The original IT's parameters as an ordered, mutable map. */
    private static Map<String, String> fullParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", "Muley");
        params.put("amount", "20000");
        params.put("term", "48");
        params.put("ssn", "1234");
        return params;
    }

    private static Map<String, String> withParam(String name, String value) {
        Map<String, String> params = fullParams();
        params.put(name, value);
        return params;
    }

    /** The request target {@code /?<pairs>}; a {@code null} value writes the name without {@code =}. */
    private static String target(Map<String, String> params) {
        StringJoiner query = new StringJoiner("&", "/?", "");
        params.forEach((name, value) -> query.add(value == null ? name : name + "=" + value));
        return query.toString();
    }

    /**
     * Draws six pairwise distinct free ports, none of them an original port, and registers them as the credit
     * agency's and Banks 1 to 5's, with the loopback name as the outbound host. Called from a
     * {@code @DynamicPropertySource} method, so each load of the context, a repeated one included, draws afresh.
     */
    private static void registerDrawnListenerPorts(DynamicPropertyRegistry registry) {
        Set<Integer> taken = new HashSet<>(ORIGINAL_PORTS);
        // listeners.additional[*].port reads these placeholders, so the SOAP listeners bind the drawn ports too.
        int creditAgencyPort = drawPort(taken);
        registry.add(CREDIT_AGENCY_PORT_KEY, () -> creditAgencyPort);
        for (int bank = 1; bank <= BANK_COUNT; bank++) {
            int bankPort = drawPort(taken);
            registry.add(bankPortKey(bank), () -> bankPort);
        }
        registry.add("loanbroker.outbound-host", () -> "localhost");
    }

    /** A free port not yet in {@code taken}, which it is then added to. */
    private static int drawPort(Set<Integer> taken) {
        int candidate;
        do {
            candidate = TestSocketUtils.findAvailableTcpPort();
        } while (!taken.add(candidate));
        return candidate;
    }
}
