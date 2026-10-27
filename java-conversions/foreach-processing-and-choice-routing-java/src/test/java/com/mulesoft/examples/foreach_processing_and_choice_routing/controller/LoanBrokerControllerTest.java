package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.LoanBrokerExceptionHandler;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.client.WebServiceIOException;

/**
 * Wire-level controller test of {@link LoanBrokerController}, the HTTP trigger of flow {@code loan-broker-sync}
 * (foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml, lines 4 and 20-65): an
 * {@code http:listener} on 11081 with {@code path="/*"} and no {@code allowedMethods}.
 *
 * <p>The whole application runs on Undertow: the main listener on a random port stands in for 11081, and the six
 * SOAP listeners (credit agency, Banks 1 to 5) on ports drawn once per class, never the original 18080 or 10080 to
 * 50080, so this context can live in the test context cache beside any other without a bind collision. Both SOAP
 * clients are Spring mocks: the credit profile is fixed and each bank, recognised by the path of the bank URI the
 * service builds, answers a fixed quote, because the real credit agency and banks answer random values.
 *
 * <p>Every response is read off the socket by ST-1 {@link HttpTestClient}: the raw status line, the headers and
 * the body bytes. The flow's string payload carries no MIME type, so every loan-broker reply is asserted to carry
 * no {@code Content-Type}. Covered: the lowest quote of Banks 1 and 2 and of Banks 3 and 4 (the always-true second
 * condition, so Bank 5 is never asked), the required-field choice's {@code otherwise}, the catch strategy for a
 * non-numeric parameter and for each client failure, every HTTP method, every path of the main listener including
 * {@code /mule/**} and {@code /error}, the query-string-only parameter binding, and the loan broker's absence from
 * the SOAP listeners. The original IT assertion itself (contains {@code Bank} and {@code rate}) is ported by
 * {@code ForEachProcessingAndChoiceRoutingTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanBrokerControllerTest {

    /** The original IT's request, parameters in its order. */
    private static final String FULL_QUERY = "name=Muley&amount=20000&term=48&ssn=1234";

    private static final String QUOTE_BANK_2 = "Bank #2, rate: 2.25";
    private static final String QUOTE_BANK_4 = "Bank #4, rate: 1.75";
    private static final String INCOMPLETE_REQUEST = "Error: incomplete request";

    /** The original's fixed listener ports, which this class never binds. */
    private static final Set<Integer> ORIGINAL_PORTS = Set.of(11081, 18080, 10080, 20080, 30080, 40080, 50080);

    private static final int CREDIT_AGENCY_PORT;
    private static final int[] BANK_PORTS = new int[5];

    static {
        Set<Integer> taken = new HashSet<>(ORIGINAL_PORTS);
        CREDIT_AGENCY_PORT = drawPort(taken);
        for (int i = 0; i < BANK_PORTS.length; i++) {
            BANK_PORTS[i] = drawPort(taken);
        }
    }

    @LocalServerPort
    private int port;

    @MockBean
    private CreditAgencyClient creditAgencyClient;

    @MockBean
    private BankClient bankClient;

    private CreditProfile creditProfile;

    private HttpTestClient http;

    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        // listeners.additional[*].port reads these placeholders, so the SOAP listeners bind the drawn ports too.
        registry.add("ports.credit-agency", () -> CREDIT_AGENCY_PORT);
        for (int i = 0; i < BANK_PORTS.length; i++) {
            int bankPort = BANK_PORTS[i];
            registry.add("ports.bank" + (i + 1), () -> bankPort);
        }
        registry.add("loanbroker.outbound-host", () -> "localhost");
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
        return Stream.concat(withoutOne, Stream.of(
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

    /** The listener declares no {@code allowedMethods}, so every method runs the flow. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"})
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
    void customerNameIsTheFirstDecodedQueryValue(String nameParams, String expectedName) {
        HttpTestClient.Response response = http.get("/?" + nameParams + "&amount=20000&term=48&ssn=1234");

        assertEquals(QUOTE_BANK_2, response.bodyAsString(), response::toString);
        ArgumentCaptor<Customer> customer = ArgumentCaptor.forClass(Customer.class);
        verify(creditAgencyClient).getCreditProfile(customer.capture());
        assertEquals(expectedName, customer.getValue().getName());
    }

    static Stream<Arguments> customerNames() {
        return Stream.of(
                Arguments.of("name=A&name=B", "A"),
                Arguments.of("name=M%C3%BCley", "M\u00fcley"),
                Arguments.of("name=Muley+Smith", "Muley Smith"));
    }

    /** The SOAP listeners serve only their {@code /mule/<service>} paths, so the loan broker is not reachable there. */
    @ParameterizedTest(name = "port of {0}")
    @MethodSource("soapListenerPorts")
    void loanBrokerIsNotServedOnASoapListener(String listener, int soapPort) {
        HttpTestClient.Response response = HttpTestClient.localhost(soapPort).get("/?" + FULL_QUERY);

        assertEquals(404, response.status(), response::toString);
        assertEquals("No listener for endpoint: /", response.bodyAsString(), response::toString);
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    static Stream<Arguments> soapListenerPorts() {
        return Stream.concat(
                Stream.of(Arguments.of("credit agency", CREDIT_AGENCY_PORT)),
                IntStream.range(0, BANK_PORTS.length).mapToObj(i -> Arguments.of("Bank " + (i + 1), BANK_PORTS[i])));
    }

    private static void assertCatchStrategyReply(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(LoanBrokerExceptionHandler.ERROR_PAYLOAD.getBytes(UTF_8), response.body(),
                response::toString);
    }

    /**
     * Asserts that exactly the given banks were asked, in this order, at the URIs the service builds from
     * {@code loanbroker.outbound-host} and the drawn {@code ports.bankN}, and returns the requests they were sent.
     */
    private List<LoanBrokerQuoteRequest> assertBanksAsked(int... banks) {
        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<LoanBrokerQuoteRequest> requests = ArgumentCaptor.forClass(LoanBrokerQuoteRequest.class);
        verify(bankClient, times(banks.length)).getLoanQuote(uris.capture(), requests.capture());
        assertEquals(Arrays.stream(banks).mapToObj(LoanBrokerControllerTest::bankUri).toList(), uris.getAllValues());
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

    private static URI bankUri(int bank) {
        return URI.create("http://localhost:" + BANK_PORTS[bank - 1] + "/mule/TheBank" + bank);
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

    /** A free port not yet in {@code taken}, which it is then added to. */
    private static int drawPort(Set<Integer> taken) {
        int candidate;
        do {
            candidate = TestSocketUtils.findAvailableTcpPort();
        } while (!taken.add(candidate));
        return candidate;
    }
}
