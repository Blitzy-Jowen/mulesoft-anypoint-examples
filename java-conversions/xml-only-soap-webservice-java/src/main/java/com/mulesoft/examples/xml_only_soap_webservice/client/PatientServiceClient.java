/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.client;

import java.util.Objects;

import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.mulesoft.examples.xml_only_soap_webservice.config.SoapEnvelopeStyleInterceptor;
import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;

/**
 * Outbound SOAP call to the PatientService, replacing sub-flow {@code invokePatientService} of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml} (lines 66-69):
 * <pre>
 * &lt;cxf:proxy-client payload="body"/&gt;
 * &lt;http:request config-ref="HTTP_Request_Configuration" method="POST" path="PatientService"/&gt;
 * </pre>
 * {@code HTTP_Request_Configuration} (line 17) targets host {@code 0.0.0.0} and port {@code ${http.port}}, the
 * application's own listener, so the answering service is the project's PatientService mock.
 * <p>
 * The CXF proxy-client in body mode wrapped the request payload (the {@code ns0:upsertPatient} document) in a SOAP 1.1
 * envelope, POSTed it, and handed the next processor the response Body child ({@code ns0:upsertPatientResponse}).
 * {@link #send(Element)} does the same over the shared {@link WebServiceTemplate}: the payload root becomes the only
 * Body child, the envelope is restyled into the CXF proxy-client form ({@code soap:} prefix, no empty {@code Header}),
 * and no {@code SOAPAction} beyond the template default, no extra HTTP header and no credential is sent, because the
 * original call carried none. The target is {@code http://{http.request.host}:{http.request.port}}
 * {@value WebServiceConfig#PATIENT_PATH}, read from the environment on every call: {@code http.request.port} resolves
 * to the port Undertow bound, which exists only once the server has started.
 * <p>
 * Failure contract, unchanged from the original, which declared no exception strategy on this path: nothing is caught,
 * wrapped or retried. A fault response surfaces as {@code SoapFaultClientException}, any other HTTP error as
 * {@code WebServiceTransportException}, a refused connection, timeout or unknown host as
 * {@code WebServiceIOException}, and every other {@link RuntimeException} as thrown. Each call makes exactly one
 * attempt; no retry is ever made on a timeout or connectivity failure.
 * <p>
 * Transport only: building the request and reading the response belong to the mappers. The class holds no per-call
 * state, so one instance serves all requests concurrently.
 */
@Component
public class PatientServiceClient {

    /** Property naming the host of the PatientService listener. */
    static final String HOST_KEY = "http.request.host";

    /** Property naming the port of the PatientService listener. */
    static final String PORT_KEY = "http.request.port";

    private final WebServiceTemplate webServiceTemplate;
    private final Environment environment;
    private final SoapEnvelopeStyleInterceptor styler;

    /**
     * @param webServiceTemplate the shared outbound SOAP 1.1 template of {@link WebServiceConfig}, used as configured
     * @param environment        the source of {@value #HOST_KEY} and {@value #PORT_KEY}, read on every call
     * @param styler             the CXF envelope styler applied to every outbound envelope
     */
    public PatientServiceClient(WebServiceTemplate webServiceTemplate, Environment environment,
            SoapEnvelopeStyleInterceptor styler) {
        this.webServiceTemplate = webServiceTemplate;
        this.environment = environment;
        this.styler = styler;
    }

    /**
     * Sends {@code requestBodyRoot} as the SOAP Body of one POST to the PatientService and returns the response Body
     * root.
     *
     * @param requestBodyRoot the request payload, a namespace-aware DOM element such as {@code ns0:upsertPatient}
     * @return the first element of the response Body, with its namespaces and prefixes, or {@code null} when the
     *         response has no message or an empty Body
     * @throws NullPointerException when {@code requestBodyRoot} is {@code null}
     * @throws IllegalStateException when {@value #HOST_KEY} or {@value #PORT_KEY} is not set
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the service answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceTransportException when the service answers with an HTTP error
     *         that carries no fault
     * @throws org.springframework.ws.client.WebServiceIOException when the service cannot be reached or does not
     *         answer in time
     */
    public Element send(Element requestBodyRoot) {
        Objects.requireNonNull(requestBodyRoot, "requestBodyRoot");
        String uri = "http://" + environment.getRequiredProperty(HOST_KEY) + ":"
                + environment.getRequiredProperty(PORT_KEY) + WebServiceConfig.PATIENT_PATH;
        DOMResult result = new DOMResult();
        // Spring WS writes the payload into the Body before it runs the callback, so the restyle sees the whole
        // outbound envelope.
        boolean received = webServiceTemplate.sendSourceAndReceiveToResult(uri, new DOMSource(requestBodyRoot),
                message -> styler.restyle((SoapMessage) message), result);
        return bodyRoot(received, result);
    }

    /**
     * The element the identity transform of the response payload produced: {@code null} without a response message,
     * and {@code null} when the Body was empty, because nothing was then transformed into {@code result}.
     */
    private static Element bodyRoot(boolean received, DOMResult result) {
        if (!received) {
            return null;
        }
        Node node = result.getNode();
        if (node == null) {
            return null;
        }
        if (node instanceof Document document) {
            return document.getDocumentElement();
        }
        if (node instanceof Element element) {
            return element;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                return element;
            }
        }
        return null;
    }
}
