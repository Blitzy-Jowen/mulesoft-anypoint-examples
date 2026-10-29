package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.client.CreditAgencyClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CustomerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The loan-broker orchestration. It replaces flow {@code loan-broker-sync} of {@code loanbroker-simple.xml}
 * (lines 20-65) and its four sub-flows {@code lookupCustomerCreditProfile}, {@code lookupBanks},
 * {@code lookupLoanQuote} and {@code findLowestLoanQuote} (lines 67-134), one method each, with the flow's processors
 * as statements in their original order.
 * <p>
 * The SOAP calls belong to {@link CreditAgencyClient} and {@link BankClient}, and the HTTP binding of the query
 * parameters to the loan-broker controller. Nothing here is caught: a {@link NumberFormatException} from the request
 * building and every client exception reach the controller advice that reproduces the flow's
 * {@code catch-exception-strategy} ({@code Error processing loan request}, lines 62-64).
 */
@Service
public class LoanBrokerService {

    private static final Logger log = LoggerFactory.getLogger(LoanBrokerService.class);

    private final CreditAgencyClient creditAgencyClient;
    private final BankClient bankClient;
    private final String outboundHost;
    private final int bank1Port;
    private final int bank2Port;
    private final int bank3Port;
    private final int bank4Port;
    private final int bank5Port;

    /**
     * @param creditAgencyClient the credit agency's {@code getCreditProfile} client
     * @param bankClient         the banks' {@code getLoanQuote} client
     * @param outboundHost       {@code loanbroker.outbound-host}, the host of every bank URI
     * @param bank1Port          {@code ports.bank1}, the port of {@code /mule/TheBank1}
     * @param bank2Port          {@code ports.bank2}, the port of {@code /mule/TheBank2}
     * @param bank3Port          {@code ports.bank3}, the port of {@code /mule/TheBank3}
     * @param bank4Port          {@code ports.bank4}, the port of {@code /mule/TheBank4}
     * @param bank5Port          {@code ports.bank5}, the port of {@code /mule/TheBank5}
     */
    public LoanBrokerService(CreditAgencyClient creditAgencyClient,
                             BankClient bankClient,
                             @Value("${loanbroker.outbound-host:0.0.0.0}") String outboundHost,
                             @Value("${ports.bank1:10080}") int bank1Port,
                             @Value("${ports.bank2:20080}") int bank2Port,
                             @Value("${ports.bank3:30080}") int bank3Port,
                             @Value("${ports.bank4:40080}") int bank4Port,
                             @Value("${ports.bank5:50080}") int bank5Port) {
        this.creditAgencyClient = creditAgencyClient;
        this.bankClient = bankClient;
        this.outboundHost = outboundHost;
        this.bank1Port = bank1Port;
        this.bank2Port = bank2Port;
        this.bank3Port = bank3Port;
        this.bank4Port = bank4Port;
        this.bank5Port = bank5Port;
    }

    /**
     * Flow {@code loan-broker-sync} after its listener: the required-field choice, the customer request, the two
     * enrichers, the {@code foreach} over the selected banks, the lowest quote and the object-to-string transformer.
     *
     * @param name   query parameter {@code name}
     * @param ssn    query parameter {@code ssn}, parsed with {@link Integer#parseInt(String)}
     * @param amount query parameter {@code amount}, parsed with {@link Integer#parseInt(String)}
     * @param term   query parameter {@code term}, parsed with {@link Integer#parseInt(String)}
     * @return {@code Error: incomplete request} when a parameter is absent, otherwise the lowest quote as text, for
     *         example {@code Bank #1, rate: 2.5}
     * @throws NumberFormatException when {@code ssn}, {@code amount} or {@code term} is not an integer
     */
    public String quote(String name, String ssn, String amount, String term) {
        // The choice's when-expression copied verbatim (loanbroker-simple.xml:29), with its otherwise as the else.
        if (!(name == null || ssn == null || amount == null || term == null)) {
            CustomerQuoteRequest request = new CustomerQuoteRequest(new Customer(name,
                    Integer.parseInt(ssn)),
                    Integer.parseInt(amount),
                    Integer.parseInt(term));

            CreditProfile creditProfile = lookupCustomerCreditProfile(request.getCustomer());

            List<URI> banks = lookupBanks(request);

            List<LoanQuote> quotes = new LinkedList<>();
            for (URI bankUri : banks) {
                quotes.add(lookupLoanQuote(bankUri, creditProfile));
            }

            return String.valueOf(findLowestLoanQuote(quotes));
        } else {
            return "Error: incomplete request";
        }
    }

    /**
     * Sub-flow {@code lookupCustomerCreditProfile}: the credit agency's profile of the customer.
     *
     * @param customer the customer of the request
     * @return the credit agency's profile
     */
    public CreditProfile lookupCustomerCreditProfile(Customer customer) {
        CreditProfile creditProfile = creditAgencyClient.getCreditProfile(customer);
        log.info("Credit profile: {}", creditProfile);
        return creditProfile;
    }

    /**
     * Sub-flow {@code lookupBanks}: the banks to ask for a quote, chosen by loan amount.
     *
     * @param request the customer request
     * @return the bank URIs in contact order, as a mutable {@link ArrayList}, the type of the original's MEL inline
     *         lists
     */
    public List<URI> lookupBanks(CustomerQuoteRequest request) {
        List<URI> banks;
        if (request.getLoanAmount() >= 20000) {
            banks = new ArrayList<>(List.of(bankUri(1, bank1Port), bankUri(2, bank2Port)));
        } else if (request.getLoanAmount() >= 10000 || request.getLoanAmount() <= 19999) {
            // Copied verbatim from loanbroker-simple.xml line 85, although it holds for every amount.
            banks = new ArrayList<>(List.of(bankUri(3, bank3Port), bankUri(4, bank4Port)));
        } else {
            // Unreachable as declared in loanbroker-simple.xml lines 88-90: any amount below 10000 satisfies
            // "<= 19999" (AAP 0.8.2).
            banks = new ArrayList<>(List.of(bankUri(5, bank5Port)));
        }

        log.info("Banks to contact: {}", banks);
        return banks;
    }

    /**
     * Sub-flow {@code lookupLoanQuote}: one bank's quote for a request that carries only the credit profile.
     *
     * @param bankUri       the bank to ask
     * @param creditProfile the customer's credit profile
     * @return the bank's quote
     */
    public LoanQuote lookupLoanQuote(URI bankUri, CreditProfile creditProfile) {
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(creditProfile);

        LoanQuote quote = bankClient.getLoanQuote(bankUri, request);
        log.info("LoanQuote from {}: {}", bankUri, quote);
        return quote;
    }

    /**
     * Sub-flow {@code findLowestLoanQuote}: the quote with the lowest interest rate. A later quote replaces the current
     * lowest only when its rate is strictly lower, so the first of equal rates wins.
     *
     * @param quotes the quotes in the order the banks answered
     * @return the lowest quote, or {@code null} for an empty list
     */
    public LoanQuote findLowestLoanQuote(List<LoanQuote> quotes) {
        LoanQuote lowestQuote = null;

        for (LoanQuote loanQuote : quotes) {
            if (lowestQuote == null) {
                lowestQuote = loanQuote;
            } else if (loanQuote.getInterestRate() < lowestQuote.getInterestRate()) {
                lowestQuote = loanQuote;
            }
        }

        log.info("Lowest loan quote: {}", lowestQuote);
        return lowestQuote;
    }

    /** Rebuilds the literals {@code http://0.0.0.0:10080/mule/TheBank1} to {@code ...:50080/mule/TheBank5}, :83-89. */
    private URI bankUri(int bankNumber, int port) {
        return URI.create("http://" + outboundHost + ":" + port + "/mule/TheBank" + bankNumber);
    }
}
