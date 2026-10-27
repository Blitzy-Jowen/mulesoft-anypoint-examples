package com.mulesoft.examples.foreach_processing_and_choice_routing.client;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfileResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

/**
 * Outbound SOAP client of the credit agency's {@code getCreditProfile} operation. It replaces the CXF JAX-WS client
 * of sub-flow {@code lookupCustomerCreditProfile} in {@code loanbroker-simple.xml}: the processor chain of lines 71-74,
 * {@code cxf:jaxws-client operation="getCreditProfile"} followed by an {@code http:request} POST to {@code path="/*"},
 * sent through {@code HTTP_Request_Configuration_1} of line 5 (host {@code 0.0.0.0}, port {@code 18080}, basePath
 * {@code /mule/TheCreditAgencyService}).
 * <p>
 * The request is the document/literal-wrapped {@link GetCreditProfile} element and the reply's {@code return} child is
 * unwrapped into the {@link CreditProfile}, as the JAX-WS client did. Timeouts, marshalling and the SOAP 1.1 message
 * factory belong to the shared {@link WebServiceTemplate}, which this class never reconfigures because the bank client
 * uses the same instance. Every Spring WS exception reaches the caller unwrapped, so the loan broker's catch branch
 * answers it as the original flow's exception strategy did.
 * <p>
 * This class deliberately does not implement the service-side {@code CreditAgencyService} interface: the in-process
 * credit agency implements it, and a second implementation would make injecting that interface ambiguous.
 */
@Component
public class CreditAgencyClient {

    /**
     * Sends {@code SOAPAction: ""}, the header CXF's SOAP 1.1 client always wrote. The callback is set explicitly
     * rather than left to the template, because Spring WS 4.1's SAAJ message writes no SOAPAction header at all when
     * none is set; it is stateless, so one instance serves concurrent calls.
     */
    private static final WebServiceMessageCallback EMPTY_SOAP_ACTION = new SoapActionCallback("");

    private final WebServiceTemplate webServiceTemplate;
    private final String targetUri;

    /**
     * @param webServiceTemplate the shared outbound template of {@code WebServiceConfig}
     * @param host               {@code loanbroker.outbound-host}, the request config's host
     * @param port               {@code ports.credit-agency}, the request config's port
     */
    public CreditAgencyClient(WebServiceTemplate webServiceTemplate,
                              @Value("${loanbroker.outbound-host:0.0.0.0}") String host,
                              @Value("${ports.credit-agency:18080}") int port) {
        this.webServiceTemplate = webServiceTemplate;
        // Concatenated rather than built with UriComponentsBuilder, which would percent-encode the literal "*" of the
        // original path="/*"; "*" is a legal RFC 3986 path character and the credit agency's routes match it.
        this.targetUri = "http://" + host + ":" + port + "/mule/TheCreditAgencyService/*";
    }

    /**
     * Calls {@code getCreditProfile} on the credit agency with an HTTP POST carrying {@code SOAPAction: ""}, as the CXF
     * client sent it.
     *
     * @param customer the customer whose credit profile is requested, sent as the unqualified {@code arg0} child
     * @return the credit profile carried by the reply's {@code return} child
     */
    public CreditProfile getCreditProfile(Customer customer) {
        Object reply = webServiceTemplate.marshalSendAndReceive(targetUri, new GetCreditProfile(customer),
                EMPTY_SOAP_ACTION);
        return ((GetCreditProfileResponse) reply).getReturn();
    }

    /** The endpoint every call is sent to: {@code http://<host>:<port>/mule/TheCreditAgencyService/*}. */
    String targetUri() {
        return targetUri;
    }
}
