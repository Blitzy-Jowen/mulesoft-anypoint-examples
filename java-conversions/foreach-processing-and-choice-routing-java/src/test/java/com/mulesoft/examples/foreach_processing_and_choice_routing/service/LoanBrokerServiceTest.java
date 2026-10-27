package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CustomerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;

/**
 * Unit test of {@link LoanBrokerService}, the port of flow {@code loan-broker-sync} and its sub-flows
 * {@code lookupCustomerCreditProfile}, {@code lookupBanks}, {@code lookupLoanQuote} and {@code findLowestLoanQuote}
 * (foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml, lines 20-134).
 *
 * <p>Both SOAP clients are Mockito mocks under strict stubs, and the service is built directly with the original's
 * outbound host {@code 0.0.0.0} and bank ports 10080 to 50080, so the bank URIs are the literals of lines 83-89. The
 * credit profiles and rates the real services return are random, so every profile and quote here is a fixed value.
 *
 * <p>Every reachable branch is exercised: the required-field choice and its otherwise, and the bank choice's two
 * whens. The otherwise of the bank choice (Bank 5) is unreachable, because {@code amount >= 10000 || amount <= 19999}
 * holds for every amount; the boundary amounts -1, 0, 9999, 10000, 19999 and 20000 are asserted never to select it,
 * and no invented request is given to it.
 *
 * <p>The four sub-flow loggers are captured with a Logback {@link ListAppender} and compared with the original
 * logger messages {@code Credit profile: #[payload]}, {@code Banks to contact: #[payload]},
 * {@code LoanQuote from #[flowVars.bankUri]: #[payload]} and {@code Lowest loan quote: #[payload]}.
 */
@ExtendWith(MockitoExtension.class)
class LoanBrokerServiceTest {

    private static final String OUTBOUND_HOST = "0.0.0.0";
    private static final int BANK1_PORT = 10080;
    private static final int BANK2_PORT = 20080;
    private static final int BANK3_PORT = 30080;
    private static final int BANK4_PORT = 40080;
    private static final int BANK5_PORT = 50080;

    private static final URI BANK1_URI = URI.create("http://0.0.0.0:10080/mule/TheBank1");
    private static final URI BANK2_URI = URI.create("http://0.0.0.0:20080/mule/TheBank2");
    private static final URI BANK3_URI = URI.create("http://0.0.0.0:30080/mule/TheBank3");
    private static final URI BANK4_URI = URI.create("http://0.0.0.0:40080/mule/TheBank4");
    private static final URI BANK5_URI = URI.create("http://0.0.0.0:50080/mule/TheBank5");
    private static final List<URI> BANK_URIS = List.of(BANK1_URI, BANK2_URI, BANK3_URI, BANK4_URI, BANK5_URI);

    private static final String INCOMPLETE_REQUEST = "Error: incomplete request";

    @Mock
    private CreditAgencyClient creditAgencyClient;

    @Mock
    private BankClient bankClient;

    private LoanBrokerService service;

    private Logger serviceLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new LoanBrokerService(creditAgencyClient, bankClient, OUTBOUND_HOST,
                BANK1_PORT, BANK2_PORT, BANK3_PORT, BANK4_PORT, BANK5_PORT);

        serviceLogger = (Logger) LoggerFactory.getLogger(LoanBrokerService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    @ParameterizedTest(name = "amount {0} -> Banks {1} and {2}, lowest {5}")
    @CsvSource({
            "20000, 1, 2, 4.2, 1.5,  'Bank #2, rate: 1.5'",
            "15000, 3, 4, 2.5, 6.0,  'Bank #3, rate: 2.5'",
            "5000,  3, 4, 7.0, 3.25, 'Bank #4, rate: 3.25'"
    })
    void quoteContactsBanksChosenByAmountAndReturnsLowestQuote(String amount, int firstBank, int secondBank,
                                                               double firstRate, double secondRate,
                                                               String expected) {
        CreditProfile profile = profile(700, 10);
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenReturn(profile);
        when(bankClient.getLoanQuote(eq(bankUri(firstBank)), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #" + firstBank, firstRate));
        when(bankClient.getLoanQuote(eq(bankUri(secondBank)), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #" + secondBank, secondRate));

        assertEquals(expected, service.quote("Muley", "1234", amount, "48"));

        verify(creditAgencyClient).getCreditProfile(any(Customer.class));
        verify(bankClient).getLoanQuote(eq(bankUri(firstBank)), any(LoanBrokerQuoteRequest.class));
        verify(bankClient).getLoanQuote(eq(bankUri(secondBank)), any(LoanBrokerQuoteRequest.class));
        verifyNoMoreInteractions(creditAgencyClient, bankClient);
    }

    @Test
    void quoteLogsEverySubFlowInOrder() {
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenReturn(profile(700, 10));
        when(bankClient.getLoanQuote(eq(BANK1_URI), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #1", 4.2));
        when(bankClient.getLoanQuote(eq(BANK2_URI), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #2", 1.5));

        service.quote("Muley", "1234", "20000", "48");

        assertEquals(List.of(
                "Credit profile: creditScore: 700, creditHistory: 10",
                "Banks to contact: [http://0.0.0.0:10080/mule/TheBank1, http://0.0.0.0:20080/mule/TheBank2]",
                "LoanQuote from http://0.0.0.0:10080/mule/TheBank1: Bank #1, rate: 4.2",
                "LoanQuote from http://0.0.0.0:20080/mule/TheBank2: Bank #2, rate: 1.5",
                "Lowest loan quote: Bank #2, rate: 1.5"), formattedMessages());
        assertEquals(List.of(
                "Credit profile: {}",
                "Banks to contact: {}",
                "LoanQuote from {}: {}",
                "LoanQuote from {}: {}",
                "Lowest loan quote: {}"), appender.list.stream().map(ILoggingEvent::getMessage).toList());
        for (ILoggingEvent event : appender.list) {
            assertEquals(Level.INFO, event.getLevel());
            assertEquals(LoanBrokerService.class.getName(), event.getLoggerName());
        }
    }

    /** The choice checks presence only, before any parsing, so a missing field wins over a non-numeric one. */
    @ParameterizedTest(name = "name={0}, ssn={1}, amount={2}, term={3}")
    @CsvSource(value = {
            "null,  1234, 20000,   48",
            "Muley, null, 20000,   48",
            "Muley, 1234, null,    48",
            "Muley, 1234, 20000,   null",
            "null,  null, null,    null",
            "null,  abc,  20000.5, x"
    }, nullValues = "null")
    void quoteWithMissingParameterReturnsIncompleteRequest(String name, String ssn, String amount, String term) {
        assertEquals(INCOMPLETE_REQUEST, service.quote(name, ssn, amount, term));

        verifyNoInteractions(creditAgencyClient, bankClient);
        assertEquals(List.of(), formattedMessages());
    }

    /**
     * A present but non-integer {@code ssn}, {@code amount} or {@code term} fails {@link Integer#parseInt(String)}
     * before any call; the controller advice answers it with the flow's catch branch.
     */
    @ParameterizedTest(name = "ssn=''{1}'', amount=''{2}'', term=''{3}''")
    @CsvSource({
            "Muley, 'abc',   '20000',      '48'",
            "Muley, ' 1234', '20000',      '48'",
            "Muley, '1234',  '20000.5',    '48'",
            "Muley, '1234',  '3000000000', '48'",
            "Muley, '1234',  '20000',      ''"
    })
    void quoteWithNonIntegerNumberThrowsNumberFormatException(String name, String ssn, String amount, String term) {
        assertThrows(NumberFormatException.class, () -> service.quote(name, ssn, amount, term));

        verifyNoInteractions(creditAgencyClient, bankClient);
        assertEquals(List.of(), formattedMessages());
    }

    /** {@code new Customer(flowVars.name, Integer.parseInt(flowVars.ssn))}; an empty name is present, not missing. */
    @ParameterizedTest(name = "name=''{0}'', ssn=''{1}'' -> Customer({0}, {2})")
    @CsvSource({
            "'Muley', '1234',  1234",
            "'',      '1234',  1234",
            "'Muley', '007',   7",
            "'Muley', '+42',   42",
            "'Muley', '-1',    -1"
    })
    void quoteSendsCustomerWithNameAndParsedSsn(String name, String ssn, int expectedSsn) {
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenReturn(profile(650, 5));
        when(bankClient.getLoanQuote(any(URI.class), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #1", 2.0));

        service.quote(name, ssn, "20000", "48");

        ArgumentCaptor<Customer> customer = ArgumentCaptor.forClass(Customer.class);
        verify(creditAgencyClient).getCreditProfile(customer.capture());
        assertEquals(name, customer.getValue().getName());
        assertEquals(expectedSsn, customer.getValue().getSsn());
    }

    /**
     * The foreach asks the banks in list order, each with a new request that carries only the credit profile, and
     * the quotes keep that order, so the first bank of equal rates wins.
     */
    @Test
    void quoteAsksBanksInOrderWithOwnRequestsAndFirstOfEqualRatesWins() {
        CreditProfile profile = profile(820, 15);
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenReturn(profile);
        when(bankClient.getLoanQuote(eq(BANK1_URI), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #1", 3.0));
        when(bankClient.getLoanQuote(eq(BANK2_URI), any(LoanBrokerQuoteRequest.class)))
                .thenReturn(quote("Bank #2", 3.0));

        assertEquals("Bank #1, rate: 3.0", service.quote("Muley", "1234", "25000", "48"));

        ArgumentCaptor<LoanBrokerQuoteRequest> first = ArgumentCaptor.forClass(LoanBrokerQuoteRequest.class);
        ArgumentCaptor<LoanBrokerQuoteRequest> second = ArgumentCaptor.forClass(LoanBrokerQuoteRequest.class);
        InOrder order = inOrder(creditAgencyClient, bankClient);
        order.verify(creditAgencyClient).getCreditProfile(any(Customer.class));
        order.verify(bankClient).getLoanQuote(eq(BANK1_URI), first.capture());
        order.verify(bankClient).getLoanQuote(eq(BANK2_URI), second.capture());
        order.verifyNoMoreInteractions();

        assertNotSame(first.getValue(), second.getValue());
        for (LoanBrokerQuoteRequest request : List.of(first.getValue(), second.getValue())) {
            assertSame(profile, request.getCreditProfile());
            assertNull(request.getCustomerRequest());
            assertNull(request.getLoanQuote());
        }
    }

    @Test
    void creditAgencyFailurePropagatesUnwrapped() {
        WebServiceIOException failure = new WebServiceIOException("I/O error: Connection refused");
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenThrow(failure);

        assertSame(failure, assertThrows(WebServiceIOException.class,
                () -> service.quote("Muley", "1234", "20000", "48")));

        verifyNoInteractions(bankClient);
        assertEquals(List.of(), formattedMessages());
    }

    @Test
    void bankFailurePropagatesUnwrappedAndEndsTheLoop() {
        WebServiceTransportException failure = new WebServiceTransportException("Internal Server Error [500]");
        when(creditAgencyClient.getCreditProfile(any(Customer.class))).thenReturn(profile(700, 10));
        when(bankClient.getLoanQuote(eq(BANK3_URI), any(LoanBrokerQuoteRequest.class))).thenThrow(failure);

        assertSame(failure, assertThrows(WebServiceTransportException.class,
                () -> service.quote("Muley", "1234", "15000", "48")));

        verify(bankClient).getLoanQuote(eq(BANK3_URI), any(LoanBrokerQuoteRequest.class));
        verifyNoMoreInteractions(bankClient);
        assertEquals(List.of(
                "Credit profile: creditScore: 700, creditHistory: 10",
                "Banks to contact: [http://0.0.0.0:30080/mule/TheBank3, http://0.0.0.0:40080/mule/TheBank4]"),
                formattedMessages());
    }

    /**
     * The first when ({@code >= 20000}) selects Banks 1 and 2; the second ({@code >= 10000 || <= 19999}) holds for
     * every other amount, so the otherwise (Bank 5) is never selected.
     */
    @ParameterizedTest(name = "amount {0} -> Banks {1} and {2}")
    @CsvSource({
            "-2147483648, 3, 4",
            "-1,          3, 4",
            "0,           3, 4",
            "9999,        3, 4",
            "10000,       3, 4",
            "19999,       3, 4",
            "19999.99,    3, 4",
            "20000,       1, 2",
            "2147483647,  1, 2"
    })
    void lookupBanksRoutesByAmountAndNeverSelectsBank5(double amount, int firstBank, int secondBank) {
        List<URI> banks = service.lookupBanks(request(amount));

        assertEquals(List.of(bankUri(firstBank), bankUri(secondBank)), banks);
        assertFalse(banks.contains(BANK5_URI), banks::toString);
        assertEquals(List.of("Banks to contact: " + banks), formattedMessages());
        verifyNoInteractions(creditAgencyClient, bankClient);
    }

    @Test
    void lookupBanksBuildsUrisFromOutboundHostAndPorts() {
        LoanBrokerService configured = new LoanBrokerService(creditAgencyClient, bankClient, "loanbroker.example",
                21001, 21002, 21003, 21004, 21005);

        assertEquals(List.of(URI.create("http://loanbroker.example:21001/mule/TheBank1"),
                        URI.create("http://loanbroker.example:21002/mule/TheBank2")),
                configured.lookupBanks(request(20000)));
        assertEquals(List.of(URI.create("http://loanbroker.example:21003/mule/TheBank3"),
                        URI.create("http://loanbroker.example:21004/mule/TheBank4")),
                configured.lookupBanks(request(15000)));
    }

    /** The original's MEL inline list is a mutable {@link ArrayList}; each lookup builds its own. */
    @Test
    void lookupBanksReturnsNewMutableArrayList() {
        List<URI> first = service.lookupBanks(request(20000));
        List<URI> second = service.lookupBanks(request(20000));

        assertInstanceOf(ArrayList.class, first);
        assertNotSame(first, second);
        first.add(BANK3_URI);
        assertEquals(List.of(BANK1_URI, BANK2_URI), second);
    }

    /** {@link BankClient} returns {@code null} for a reply without a {@code return} element. */
    @Test
    void lookupLoanQuoteReturnsAndLogsNullReply() {
        CreditProfile profile = profile(450, 3);
        when(bankClient.getLoanQuote(eq(BANK3_URI), any(LoanBrokerQuoteRequest.class))).thenReturn(null);

        assertNull(service.lookupLoanQuote(BANK3_URI, profile));

        ArgumentCaptor<LoanBrokerQuoteRequest> request = ArgumentCaptor.forClass(LoanBrokerQuoteRequest.class);
        verify(bankClient).getLoanQuote(eq(BANK3_URI), request.capture());
        assertSame(profile, request.getValue().getCreditProfile());
        assertEquals(List.of("LoanQuote from http://0.0.0.0:30080/mule/TheBank3: null"), formattedMessages());
        verifyNoInteractions(creditAgencyClient);
    }

    static Stream<Arguments> rates() {
        return Stream.of(
                Arguments.of(new double[] {4.2, 1.5}, 1),
                Arguments.of(new double[] {1.5, 4.2}, 0),
                Arguments.of(new double[] {3.0, 3.0}, 0),
                Arguments.of(new double[] {5.0, 2.0, 2.0}, 1),
                Arguments.of(new double[] {9.99, 0.0}, 1),
                Arguments.of(new double[] {7.25}, 0));
    }

    /** A later quote replaces the lowest only when its rate is strictly lower. */
    @ParameterizedTest(name = "rates {0} -> index {1}")
    @MethodSource("rates")
    void findLowestLoanQuotePicksFirstLowestRate(double[] rates, int expectedIndex) {
        List<LoanQuote> quotes = new ArrayList<>();
        for (int i = 0; i < rates.length; i++) {
            quotes.add(quote("Bank #" + (i + 1), rates[i]));
        }

        LoanQuote lowest = service.findLowestLoanQuote(quotes);

        assertSame(quotes.get(expectedIndex), lowest, () -> Arrays.toString(rates));
        assertEquals(List.of("Lowest loan quote: " + lowest), formattedMessages());
    }

    @Test
    void findLowestLoanQuoteOfNoQuotesIsNull() {
        assertNull(service.findLowestLoanQuote(List.of()));

        assertEquals(List.of("Lowest loan quote: null"), formattedMessages());
    }

    /**
     * The original loop: a leading {@code null} is replaced by the next quote, while a {@code null} after a quote
     * fails on {@code getInterestRate()}, which reaches the flow's catch branch.
     */
    @Test
    void findLowestLoanQuoteHandlesNullQuotesAsTheOriginalLoop() {
        LoanQuote quote = quote("Bank #2", 5.5);

        assertSame(quote, service.findLowestLoanQuote(Arrays.asList(null, quote)));
        assertThrows(NullPointerException.class, () -> service.findLowestLoanQuote(Arrays.asList(quote, null)));
    }

    private List<String> formattedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static URI bankUri(int bankNumber) {
        return BANK_URIS.get(bankNumber - 1);
    }

    private static CreditProfile profile(int creditScore, int creditHistory) {
        CreditProfile profile = new CreditProfile();
        profile.setCreditScore(creditScore);
        profile.setCreditHistory(creditHistory);
        return profile;
    }

    private static LoanQuote quote(String bankName, double interestRate) {
        LoanQuote quote = new LoanQuote();
        quote.setBankName(bankName);
        quote.setInterestRate(interestRate);
        return quote;
    }

    private static CustomerQuoteRequest request(double amount) {
        return new CustomerQuoteRequest(new Customer("Muley", 1234), amount, 48);
    }
}
