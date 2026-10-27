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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test of {@link DefaultCreditAgency}, ported unchanged from the original credit agency
 * (foreach-processing-and-choice-routing/src/main/java/org/mule/example/loanbroker/creditagency/DefaultCreditAgency.java)
 * that the loan broker calls through {@code getCreditProfile} on 18080.
 *
 * <p>The original draws both values from {@link Math#random()}: the credit score as
 * {@code (int) (Math.random() * 600 + 300)}, so 300 to 899, and the credit history length as
 * {@code (int) (Math.random() * 19 + 1)}, so 1 to 19. The output is random by design, so the random generators
 * are range-checked over many draws rather than pinned to any single value.
 *
 * <p>A deterministic recording subclass pins how {@code getCreditProfile} wires those generators: its two hooks
 * return fixed values no draw can produce, and the test asserts that the customer's SSN is forwarded to both
 * hooks, each called once with the credit history hook first, and that each returned value reaches its own
 * profile field.
 */
class DefaultCreditAgencyTest {

    private static final int MIN_CREDIT_SCORE = 300;
    private static final int MAX_CREDIT_SCORE = 899;
    private static final int MIN_CREDIT_HISTORY = 1;
    private static final int MAX_CREDIT_HISTORY = 19;
    private static final int DRAWS = 1000;

    // Outside both generator ranges, distinct from each other and from every test SSN, so a value that reaches
    // a profile field can only have come from the matching recording hook.
    private static final int RECORDED_CREDIT_SCORE = 951;
    private static final int RECORDED_CREDIT_HISTORY = 37;

    private final DefaultCreditAgency agency = new DefaultCreditAgency();

    @Test
    void creditProfileValuesStayWithinOriginalRanges() {
        Customer customer = new Customer("Muley", 1234);

        for (int i = 0; i < DRAWS; i++) {
            assertProfileWithinRanges(agency.getCreditProfile(customer));
        }
    }

    @Test
    void protectedGeneratorsStayWithinRanges() {
        for (int i = 0; i < DRAWS; i++) {
            assertCreditScoreWithinRange(agency.getCreditScore(1234));
            assertCreditHistoryWithinRange(agency.getCreditHistoryLength(1234));
        }
    }

    @Test
    void returnsNewProfileForEveryCall() {
        Customer customer = new Customer("Muley", 1234);

        assertNotSame(agency.getCreditProfile(customer), agency.getCreditProfile(customer));
    }

    /**
     * The four SSNs, plus the Customer the no-argument constructor creates (no name, SSN 0), which is how the
     * JAXB-bound Customer of a SOAP request is instantiated.
     */
    static Stream<Arguments> customers() {
        return Stream.of(
                Arguments.of(Named.of("ssn 0", new Customer("Muley", 0))),
                Arguments.of(Named.of("ssn -1", new Customer("Muley", -1))),
                Arguments.of(Named.of("ssn 1234", new Customer("Muley", 1234))),
                Arguments.of(Named.of("ssn Integer.MAX_VALUE", new Customer("Muley", Integer.MAX_VALUE))),
                Arguments.of(Named.of("no-arg constructor", new Customer())));
    }

    @ParameterizedTest
    @MethodSource("customers")
    void ssnIsIgnored(Customer customer) {
        // The original forwards the SSN to both generators, which never use it, so no SSN value can push a profile
        // out of range.
        assertProfileWithinRanges(agency.getCreditProfile(customer));
    }

    @ParameterizedTest
    @MethodSource("customers")
    void creditProfileForwardsSsnToBothGeneratorsAndKeepsEachValue(Customer customer) {
        RecordingCreditAgency recordingAgency = new RecordingCreditAgency();

        CreditProfile profile = recordingAgency.getCreditProfile(customer);

        assertEquals(List.of("history:" + customer.getSsn(), "score:" + customer.getSsn()), recordingAgency.calls);
        assertEquals(RECORDED_CREDIT_SCORE, profile.getCreditScore());
        assertEquals(RECORDED_CREDIT_HISTORY, profile.getCreditHistory());
    }

    @Test
    void implementsCreditAgencyService() {
        assertInstanceOf(CreditAgencyService.class, agency);

        CreditAgencyService service = agency;
        assertProfileWithinRanges(service.getCreditProfile(new Customer("Muley", 1234)));
    }

    @Test
    void toStringUsesProfileFields() {
        CreditProfile profile = agency.getCreditProfile(new Customer("Muley", 1234));

        assertEquals("creditScore: " + profile.getCreditScore() + ", creditHistory: " + profile.getCreditHistory(),
                profile.toString());
    }

    @Test
    void nullCustomerThrowsNullPointerException() {
        // The original does not guard against a missing customer; the port keeps that behaviour.
        assertThrows(NullPointerException.class, () -> agency.getCreditProfile(null));
    }

    private static void assertProfileWithinRanges(CreditProfile profile) {
        assertNotNull(profile);
        assertCreditScoreWithinRange(profile.getCreditScore());
        assertCreditHistoryWithinRange(profile.getCreditHistory());
    }

    private static void assertCreditScoreWithinRange(int creditScore) {
        assertTrue(creditScore >= MIN_CREDIT_SCORE && creditScore <= MAX_CREDIT_SCORE,
                () -> "credit score " + creditScore + " outside " + MIN_CREDIT_SCORE + ".." + MAX_CREDIT_SCORE);
    }

    private static void assertCreditHistoryWithinRange(int creditHistory) {
        assertTrue(creditHistory >= MIN_CREDIT_HISTORY && creditHistory <= MAX_CREDIT_HISTORY,
                () -> "credit history " + creditHistory + " outside " + MIN_CREDIT_HISTORY + ".." + MAX_CREDIT_HISTORY);
    }

    /**
     * Replaces both random generators with fixed values and records each call as {@code "<hook>:<ssn>"} in call
     * order, {@code history} for {@code getCreditHistoryLength} and {@code score} for {@code getCreditScore}.
     */
    private static final class RecordingCreditAgency extends DefaultCreditAgency {

        private final List<String> calls = new ArrayList<>();

        @Override
        protected int getCreditScore(int ssn) {
            calls.add("score:" + ssn);
            return RECORDED_CREDIT_SCORE;
        }

        @Override
        protected int getCreditHistoryLength(int ssn) {
            calls.add("history:" + ssn);
            return RECORDED_CREDIT_HISTORY;
        }
    }
}
