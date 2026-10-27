package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import javax.xml.namespace.QName;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.SoapFaultConfig;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuoteResponse;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.Bank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

/**
 * The five mock banks of the loan broker, which the original exposed as the flows {@code Bank1Flow} to
 * {@code Bank5Flow}: each a {@code cxf:jaxws-service} of {@code Bank} on its own listener (10080
 * {@code /mule/TheBank1} to 50080 {@code /mule/TheBank5}) in front of a {@code singleton-object} {@code Bank} named
 * {@code Bank #1} to {@code Bank #5}. The operation keeps the JAX-WS wire shape of {@code BankService.getLoanQuote}:
 * {@code getLoanQuote}/{@code arg0} answered by {@code getLoanQuoteResponse}/{@code return}.
 * <p>
 * Every bank port reaches the one {@code MessageDispatcherServlet}, and {@code @PayloadRoot} mappings are global, so
 * the bank is chosen by the local port the request arrived on:
 * <ul>
 *   <li>a bank port ({@code ports.bank1} to {@code ports.bank5}) is answered by that port's bank;</li>
 *   <li>any other port (the credit agency's or the loan broker's) has no such operation, as the CXF service on that
 *       port had none, and is answered with the {@code soap:Client} fault of
 *       {@link SoapFaultConfig.UnknownOperationException} naming {@link #PAYLOAD_ROOT};</li>
 *   <li>a request that arrives without an HTTP servlet connection (for example through Spring WS's
 *       {@code MockWebServiceClient}) has no port and is answered by {@code Bank #1}.</li>
 * </ul>
 * The path is not inspected: {@code PortRoutingFilter} already confines each bank port to its
 * {@code /mule/TheBankN} route. Bank 5 is served like the others although the loan broker's routing never selects it.
 * <p>
 * Each bank is created once, here, and is not a Spring bean: its three random rates are drawn at construction and
 * stay fixed for the application's lifetime, as the original singleton's did. The banks are never modified after
 * construction, so one instance serves concurrent requests.
 */
@Endpoint
public class BankEndpoint {

    /** Target namespace of the bank service, the one JAX-WS derived from the original bank package. */
    public static final String NAMESPACE_URI = "http://bank.loanbroker.example.mule.org/";

    /** Local name of the operation's request element. */
    public static final String LOCAL_PART = "getLoanQuote";

    /** Qualified name of the operation's request element, named by the fault on a port that serves no bank. */
    public static final QName PAYLOAD_ROOT = new QName(NAMESPACE_URI, LOCAL_PART);

    private static final String BANK_NAME_PREFIX = "Bank #";

    private final Map<Integer, Bank> banksByPort;

    /** {@code Bank #1}, which answers requests that carry no local port. */
    private final Bank defaultBank;

    /**
     * Creates {@code Bank #1} to {@code Bank #5}, each bound to its listener port.
     *
     * @throws IllegalStateException when two bank ports are configured with the same value, which would leave a bank
     *                               without a listener
     */
    public BankEndpoint(@Value("${ports.bank1}") int bank1Port, @Value("${ports.bank2}") int bank2Port,
            @Value("${ports.bank3}") int bank3Port, @Value("${ports.bank4}") int bank4Port,
            @Value("${ports.bank5}") int bank5Port) {
        int[] ports = { bank1Port, bank2Port, bank3Port, bank4Port, bank5Port };
        Map<Integer, Bank> banks = new LinkedHashMap<>();
        for (int index = 0; index < ports.length; index++) {
            Bank bank = new Bank();
            bank.setBankName(BANK_NAME_PREFIX + (index + 1));
            Bank previous = banks.putIfAbsent(ports[index], bank);
            if (previous != null) {
                throw new IllegalStateException("Port " + ports[index] + " is configured for both "
                        + previous.getBankName() + " and " + bank.getBankName()
                        + "; each of ports.bank1 to ports.bank5 needs its own port");
            }
        }
        this.banksByPort = Collections.unmodifiableMap(banks);
        this.defaultBank = banks.get(bank1Port);
    }

    /**
     * Answers a quote request with the quote of the bank listening on the request's port. A request without
     * {@code arg0} or without a credit profile fails inside {@link Bank#getLoanQuote}, as the original component did,
     * and the failure is answered as a {@code soap:Server} fault.
     *
     * @throws SoapFaultConfig.UnknownOperationException when the request arrived on a port that serves no bank
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = LOCAL_PART)
    @ResponsePayload
    public GetLoanQuoteResponse getLoanQuote(@RequestPayload GetLoanQuote request) {
        Bank bank = currentBank();
        return new GetLoanQuoteResponse(bank.getLoanQuote(request.getArg0()));
    }

    private Bank currentBank() {
        OptionalInt localPort = currentLocalPort();
        if (localPort.isEmpty()) {
            return defaultBank;
        }
        Bank bank = banksByPort.get(localPort.getAsInt());
        if (bank == null) {
            throw new SoapFaultConfig.UnknownOperationException(PAYLOAD_ROOT);
        }
        return bank;
    }

    /** The local port of the current HTTP servlet request, or empty when the message did not arrive over one. */
    private static OptionalInt currentLocalPort() {
        TransportContext context = TransportContextHolder.getTransportContext();
        if (context != null && context.getConnection() instanceof HttpServletConnection connection) {
            return OptionalInt.of(connection.getHttpServletRequest().getLocalPort());
        }
        return OptionalInt.empty();
    }
}
