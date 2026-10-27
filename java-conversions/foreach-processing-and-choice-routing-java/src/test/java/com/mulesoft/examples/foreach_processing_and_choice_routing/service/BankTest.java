/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit test of {@link Bank}, ported unchanged from the original bank
 * (foreach-processing-and-choice-routing/src/main/java/org/mule/example/loanbroker/bank/Bank.java)
 * that the loan broker calls through {@code getLoanQuote} on 10080 to 50080.
 *
 * <p>The original draws its three rates from {@link Math#random()} as {@code Math.random() * 10}, so each lies in
 * [0, 10), and sorts them ascending: index 0 is the platinum rate, 1 the gold rate and 2 the basic rate. The rates
 * are random by design, so their range and order are checked over many banks rather than pinned to any value, and
 * every other assertion reads the bank's own {@code rates} array.
 *
 * <p>The credit score selects the rate: below 500 the basic rate, below 700 the gold rate, otherwise the platinum
 * rate. The score bands are pinned at their bounds and at the ends of the 300 to 899 range the credit agency
 * draws from.
 */
class BankTest {

    private static final int PLATINUM_PROFILE_INDEX = 0;
    private static final int GOLD_PROFILE_INDEX = 1;
    private static final int RATE_COUNT = 3;
    private static final double MAX_RATE_EXCLUSIVE = 10;
    private static final int BANKS = 100;

    private Bank bank;
    private double[] rates;

    private Logger bankLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        bank = new Bank();
        bank.setBankName("Bank #1");
        rates = (double[]) ReflectionTestUtils.getField(bank, "rates");

        bankLogger = (Logger) LoggerFactory.getLogger(Bank.class);
        previousLevel = bankLogger.getLevel();
        bankLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        bankLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        bankLogger.detachAppender(appender);
        appender.stop();
        bankLogger.setLevel(previousLevel);
    }

    @Test
    void ratesAreSortedAscendingAndInRange() {
        assertRatesSortedAndInRange(rates);

        for (int i = 0; i < BANKS; i++) {
            assertRatesSortedAndInRange((double[]) ReflectionTestUtils.getField(new Bank(), "rates"));
        }
    }

    @ParameterizedTest(name = "credit score {0} -> rate index {1}")
    @CsvSource({
            "300, 2",
            "499, 2",
            "500, 1",
            "699, 1",
            "700, 0",
            "899, 0"
    })
    void creditScoreBands(int creditScore, int rateIndex) {
        LoanQuote quote = bank.getLoanQuote(request(creditScore));

        assertEquals(rates[rateIndex], quote.getInterestRate(), 0.0);
    }

    @Test
    void sameScoreGivesSameRate() {
        LoanQuote first = bank.getLoanQuote(request(650));
        LoanQuote second = bank.getLoanQuote(request(650));

        assertNotSame(first, second);
        assertEquals(first.getInterestRate(), second.getInterestRate(), 0.0);
        assertEquals(rates[GOLD_PROFILE_INDEX], second.getInterestRate(), 0.0);
    }

    @Test
    void quoteCarriesBankNameAndToString() {
        LoanQuote quote = bank.getLoanQuote(request(750));

        assertEquals("Bank #1", quote.getBankName());
        assertEquals(rates[PLATINUM_PROFILE_INDEX], quote.getInterestRate(), 0.0);
        assertEquals("Bank #1, rate: " + rates[PLATINUM_PROFILE_INDEX], quote.toString());
    }

    @Test
    void bankNameAccessors() {
        bank.setBankName("Bank #5");

        assertEquals("Bank #5", bank.getBankName());
        assertEquals("Bank #5", bank.getLoanQuote(request(750)).getBankName());

        Bank unnamed = new Bank();
        double[] unnamedRates = (double[]) ReflectionTestUtils.getField(unnamed, "rates");
        LoanQuote unnamedQuote = unnamed.getLoanQuote(request(750));

        assertNull(unnamed.getBankName());
        assertNull(unnamedQuote.getBankName());
        assertTrue(unnamedQuote.toString().startsWith("null, rate: "), unnamedQuote::toString);
        assertEquals("null, rate: " + unnamedRates[PLATINUM_PROFILE_INDEX], unnamedQuote.toString());
    }

    @Test
    void implementsBankService() {
        assertInstanceOf(BankService.class, bank);

        BankService service = bank;
        LoanQuote quote = service.getLoanQuote(request(600));

        assertEquals(bank.getLoanQuote(request(600)).getInterestRate(), quote.getInterestRate(), 0.0);
        assertEquals(rates[GOLD_PROFILE_INDEX], quote.getInterestRate(), 0.0);
        assertEquals("Bank #1", quote.getBankName());
    }

    @Test
    void logsReturningRate() {
        LoanQuote quote = bank.getLoanQuote(request(750));

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("Returning Rate is: " + quote, event.getFormattedMessage());
        assertEquals("Returning Rate is: Bank #1, rate: " + rates[PLATINUM_PROFILE_INDEX], event.getFormattedMessage());
    }

    @Test
    void requestWithoutCreditProfileThrowsNullPointerException() {
        // The original does not guard against a request without a credit profile; the port keeps that behaviour.
        assertThrows(NullPointerException.class, () -> bank.getLoanQuote(new LoanBrokerQuoteRequest()));
    }

    /**
     * A broker request whose only set field is a credit profile with the given score, which is all the bank reads.
     */
    private static LoanBrokerQuoteRequest request(int creditScore) {
        CreditProfile creditProfile = new CreditProfile();
        creditProfile.setCreditScore(creditScore);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(creditProfile);
        return request;
    }

    private static void assertRatesSortedAndInRange(double[] bankRates) {
        assertNotNull(bankRates);
        assertEquals(RATE_COUNT, bankRates.length);
        for (int i = 0; i < bankRates.length; i++) {
            double rate = bankRates[i];
            assertTrue(rate >= 0 && rate < MAX_RATE_EXCLUSIVE, () -> "rate " + rate + " outside [0, 10)");
            if (i > 0) {
                double previous = bankRates[i - 1];
                assertTrue(previous <= rate, () -> "rates not ascending: " + previous + " > " + rate);
            }
        }
    }
}
