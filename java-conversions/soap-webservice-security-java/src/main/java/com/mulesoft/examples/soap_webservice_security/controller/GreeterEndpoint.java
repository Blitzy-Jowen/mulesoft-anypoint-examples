/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.controller;

import javax.xml.namespace.QName;

import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;
import com.mulesoft.examples.soap_webservice_security.service.GreeterService;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * The Greeter service the original exposed six times, as the flows {@code UnsecureServiceFlow},
 * {@code UsernameTokenServiceFlow}, {@code UsernameTokenSignedServiceFlow}, {@code UsernameTokenEncryptedServiceFlow},
 * {@code SamlTokenServiceFlow} and {@code SignedSamlTokenServiceFlow}: each an HTTP listener on 63081 under
 * {@code basePath="services"}, a {@code cxf:jaxws-service} over the {@code Greeter} interface and the
 * {@code GreeterService} component. The paths are {@code /services/unsecure}, {@code /services/username},
 * {@code /services/signed}, {@code /services/encrypted}, {@code /services/saml} and {@code /services/signedsaml}.
 *
 * <p>The six flows differ only in their WS-Security action sets, so this one endpoint answers the
 * document/literal-wrapped {@code greet} operation in {@value #NAMESPACE_URI} on every path and delegates unchanged to
 * {@link GreeterService}.
 *
 * <p>Nothing else belongs here:
 * <ul>
 *   <li>each path's action set is enforced, and an encrypted body decrypted, by
 *       {@code config/PathScopedSecurityInterceptor}, which {@code config/WebServiceConfig} runs before any endpoint is
 *       selected;</li>
 *   <li>faults come from {@code config/SoapFaultConfig}, the security interceptor and
 *       {@code config/SoapListenerBoundaryAdapter}, and the CXF envelope style from
 *       {@code config/SoapEnvelopeStyleInterceptor};</li>
 *   <li>{@code ?wsdl} on the six paths is served from {@code wsdl/GreeterService.wsdl} by
 *       {@code config/WsdlQueryPublisher};</li>
 *   <li>{@code config/PortRoutingFilter} keeps the six paths off the 63080 client listener.</li>
 * </ul>
 */
@Endpoint
public class GreeterEndpoint {

    /** Target namespace of the original Greeter service. */
    public static final String NAMESPACE_URI = "http://security.example.mule.mulesoft.com/";

    /** Local name of the operation's request wrapper. */
    public static final String GREET_LOCAL_PART = "greet";

    /** Qualified name of the request wrapper, the body root this endpoint is mapped to. */
    public static final QName PAYLOAD_ROOT = new QName(NAMESPACE_URI, GREET_LOCAL_PART);

    private final GreeterService greeterService;

    /**
     * @param greeterService the greeting all six paths share, as every original flow ran the same component class
     */
    public GreeterEndpoint(GreeterService greeterService) {
        this.greeterService = greeterService;
    }

    /**
     * Greets the request's name exactly as the original component did, so a request without {@code name} is answered
     * {@code Hello null}.
     *
     * <p>Mapped to {@code greet} alone, with no {@code xenc:EncryptedData} mapping: the request on
     * {@code /services/encrypted} is decrypted in place before endpoint selection, so it already names {@code greet}
     * here. A body root still encrypted at that point was not decrypted by its path's policy and keeps the
     * unknown-part fault CXF answered it with, rather than reaching this method.
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = GREET_LOCAL_PART)
    @ResponsePayload
    public GreetResponse greet(@RequestPayload Greet request) {
        return new GreetResponse(greeterService.greet(request.getName()));
    }
}
