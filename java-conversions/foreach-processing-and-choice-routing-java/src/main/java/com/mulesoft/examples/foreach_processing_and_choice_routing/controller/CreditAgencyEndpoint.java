package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.SoapFaultConfig;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfileResponse;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.CreditAgencyService;
import java.util.OptionalInt;
import javax.xml.namespace.QName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

/**
 * The mock credit agency the original exposed as the {@code TheCreditAgencyService} flow: an HTTP listener on 18080
 * with {@code basePath="/mule/TheCreditAgencyService"} and {@code path="/*"}, a {@code cxf:jaxws-service} over
 * {@code DefaultCreditAgency}, and that class as a singleton component.
 *
 * <p>It answers the document/literal-wrapped {@code getCreditProfile} operation JAX-WS derived from the
 * {@code CreditAgencyService} interface: request {@code getCreditProfile} with the customer as {@code arg0}, response
 * {@code getCreditProfileResponse} with the profile as {@code return}, both in
 * {@value #NAMESPACE_URI}. The work is delegated unchanged to the one {@link CreditAgencyService} bean, which stands
 * for the original singleton object.
 *
 * <p>Faults, envelope style and {@code ?wsdl} belong to the project's Spring WS configuration, not to this class.
 */
@Endpoint
public class CreditAgencyEndpoint {

    /** Target namespace of the original credit agency service. */
    public static final String NAMESPACE_URI = "http://creditagency.loanbroker.example.mule.org/";

    /** Local name of the operation's request wrapper. */
    public static final String LOCAL_PART = "getCreditProfile";

    /** Qualified name of the request wrapper, the body root this endpoint is mapped to. */
    public static final QName PAYLOAD_ROOT = new QName(NAMESPACE_URI, LOCAL_PART);

    private final CreditAgencyService creditAgency;
    private final int port;

    /**
     * @param creditAgency the credit agency all requests share, as the original's {@code singleton-object} was
     * @param port         {@code ports.credit-agency}, the only listener port whose service has this operation
     */
    public CreditAgencyEndpoint(CreditAgencyService creditAgency, @Value("${ports.credit-agency}") int port) {
        this.creditAgency = creditAgency;
        this.port = port;
    }

    /**
     * Returns the credit profile of the request's customer.
     *
     * <p>{@code @PayloadRoot} mappings apply to every port the message dispatcher serves, while in the original only
     * the credit agency's CXF service knew this operation; the bank services answered it as an unknown message part.
     * A request received on any other listener port is therefore rejected with that same fault. Without an HTTP
     * servlet connection (an in-memory test client) the port is unknown and the request is served.
     *
     * <p>A request without {@code arg0} fails in the credit agency, as the original component did, and the resulting
     * exception is answered as a SOAP fault by the fault resolver.
     *
     * @throws SoapFaultConfig.UnknownOperationException when the request arrived on another listener's port
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = LOCAL_PART)
    @ResponsePayload
    public GetCreditProfileResponse getCreditProfile(@RequestPayload GetCreditProfile request) {
        OptionalInt localPort = currentLocalPort();
        if (localPort.isPresent() && localPort.getAsInt() != port) {
            throw new SoapFaultConfig.UnknownOperationException(PAYLOAD_ROOT);
        }
        return new GetCreditProfileResponse(creditAgency.getCreditProfile(request.getArg0()));
    }

    /** The local port of the HTTP connection carrying the current request, when there is one. */
    private static OptionalInt currentLocalPort() {
        TransportContext context = TransportContextHolder.getTransportContext();
        if (context != null && context.getConnection() instanceof HttpServletConnection connection) {
            return OptionalInt.of(connection.getHttpServletRequest().getLocalPort());
        }
        return OptionalInt.empty();
    }
}
