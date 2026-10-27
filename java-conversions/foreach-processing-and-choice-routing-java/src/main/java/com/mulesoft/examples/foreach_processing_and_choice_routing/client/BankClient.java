package com.mulesoft.examples.foreach_processing_and_choice_routing.client;

import java.net.URI;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuoteResponse;

import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;

/**
 * Outbound SOAP client of the bank {@code getLoanQuote} operation. It replaces the CXF JAX-WS client of sub-flow
 * {@code lookupLoanQuote} in {@code loanbroker-simple.xml}, lines 106-109: a {@code cxf:jaxws-client} with
 * {@code operation="getLoanQuote"} over the original {@code @WebService BankService} interface, followed by
 * {@code <http:request config-ref="HTTP_Request_Configuration_2" method="POST" path="/*" responseTimeout="10000"/>}.
 * <p>
 * {@code HTTP_Request_Configuration_2} (line 6) takes host, port and base path from {@code flowVars.bankUri}, one of
 * the bank URIs {@code lookupBanks} returns ({@code http://0.0.0.0:10080/mule/TheBank1} to
 * {@code http://0.0.0.0:50080/mule/TheBank5}), so every call goes to {@code <bankUri>/*}, as {@link #targetFor(URI)}
 * builds it. The request is the document/literal-wrapped {@code getLoanQuote} element of
 * {@code http://bank.loanbroker.example.mule.org/} with the unqualified child {@code arg0}; the reply's unqualified
 * {@code return} child is the bank's {@link LoanQuote}.
 * <p>
 * Marshalling, SOAP 1.1, POST, the empty {@code SOAPAction} the CXF client sent, and the 10000 ms connect and read
 * timeouts all come from the shared {@link WebServiceTemplate} of {@code config.WebServiceConfig}, which the
 * credit-agency client uses too, so this class never reconfigures it. The template is thread-safe, so one instance
 * serves concurrent quote lookups.
 * <p>
 * Failures propagate unwrapped ({@code SoapFaultClientException}, {@code WebServiceIOException} on a connect failure
 * or timeout, {@code WebServiceTransportException}, a {@code ClassCastException} for a reply of another element, a
 * {@code NullPointerException} for an empty reply), so the loan broker answers them with the flow's catch branch
 * {@code Error processing loan request} (lines 62-64), as the original did; no retry is made.
 */
@Component
public class BankClient {

    private final WebServiceTemplate webServiceTemplate;

    /**
     * @param webServiceTemplate the single outbound template of {@code config.WebServiceConfig}, shared with the
     *                           credit-agency client
     */
    public BankClient(WebServiceTemplate webServiceTemplate) {
        this.webServiceTemplate = webServiceTemplate;
    }

    /**
     * Asks one bank for a loan quote, as one iteration of the original {@code foreach} over the selected banks did.
     *
     * @param bankUri the bank's URI, for example {@code http://0.0.0.0:10080/mule/TheBank1}
     * @param request the quote request; the loan broker sets only its credit profile, as the original did
     * @return the bank's quote, or {@code null} when the reply carries no {@code return} element
     */
    public LoanQuote getLoanQuote(URI bankUri, LoanBrokerQuoteRequest request) {
        Object reply = webServiceTemplate.marshalSendAndReceive(targetFor(bankUri), new GetLoanQuote(request));
        return ((GetLoanQuoteResponse) reply).getReturn();
    }

    /**
     * The request target: the bank URI followed by the original request's {@code path="/*"}. It is plain
     * concatenation because URI resolution or a URI builder could drop or encode the {@code *}, which is a legal path
     * character the bank listeners accept.
     *
     * @param bankUri the bank's URI
     * @return the bank URI followed by {@code /*}
     */
    static String targetFor(URI bankUri) {
        return bankUri.toString() + "/*";
    }
}
