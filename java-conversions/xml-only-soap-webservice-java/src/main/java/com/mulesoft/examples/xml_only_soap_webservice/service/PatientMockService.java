/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.PatientMockResponseMapper;

/**
 * The routing of the mock {@code PatientService} flow ({@code xml-only-soap-webservice/src/main/app/mocks.xml:4-44}),
 * which the hospital {@code AdmissionService} orchestration calls to upsert a patient.
 *
 * <p>The original listener's {@code cxf:proxy-service payload="body"} ({@code mocks.xml:6}) hands the flow the
 * SOAP Body content; {@code controller/PatientServiceMockEndpoint} unwraps the same Body root and passes it to
 * {@link #handle(Element)}. The flow then stores {@code xpath('fn:local-name(/*)')} as {@code operation}
 * ({@code mocks.xml:7}) and chooses between two scripts ({@code mocks.xml:9-43}), both owned by
 * {@link PatientMockResponseMapper} and neither reading the request:
 * <ul>
 *   <li>{@code when flowVars.operation == 'upsertPatient'} ({@code mocks.xml:10}) → {@code upsertPatientResponse}
 *       ({@code mocks.xml:12});</li>
 *   <li>{@code otherwise} ({@code mocks.xml:22}) → the fixed {@code getPatientResponse} ({@code mocks.xml:24}),
 *       for every other operation, an unknown root name and an empty Body alike.</li>
 * </ul>
 * The flow declares no logger and no exception strategy, so nothing is caught here: a mapper failure reaches
 * {@code config/SoapFaultConfig}, which answers it with the {@code soap:Server} fault.
 *
 * <p>Stateless and thread-safe; the mapper is too.
 */
@Service
public class PatientMockService {

    /** The operation literal of {@code mocks.xml:10}, compared case-sensitively. */
    public static final String UPSERT_PATIENT = "upsertPatient";

    private final PatientMockResponseMapper patientMockResponseMapper;

    /**
     * Creates the service.
     *
     * @param patientMockResponseMapper owner of the two response scripts of {@code mocks.xml:12} and {@code :24}
     */
    public PatientMockService(PatientMockResponseMapper patientMockResponseMapper) {
        this.patientMockResponseMapper = patientMockResponseMapper;
    }

    /**
     * Runs the {@code choice} of {@code mocks.xml:9-43} on one request.
     *
     * @param request the SOAP Body's root element, or {@code null} for an empty Body
     * @return {@code ns0:upsertPatientResponse} ({@code mocks.xml:12}) when the root's local name is
     *         {@value #UPSERT_PATIENT} ({@code mocks.xml:10}); otherwise ({@code mocks.xml:22})
     *         {@code ns0:getPatientResponse} ({@code mocks.xml:24})
     */
    public Element handle(Element request) {
        String operation = operationOf(request);
        if (UPSERT_PATIENT.equals(operation)) {
            return patientMockResponseMapper.upsertPatientResponse();
        }
        return patientMockResponseMapper.getPatientResponse();
    }

    /**
     * The flow variable {@code operation} of {@code mocks.xml:7}: XPath {@code fn:local-name(/*)} over the payload,
     * the root element's name without its prefix, neither trimmed nor case-normalized.
     *
     * @param request the SOAP Body's root element, or {@code null}
     * @return the root's local name; the empty string when there is no root, as {@code fn:local-name} yields for an
     *         empty node-set
     */
    static String operationOf(Element request) {
        if (request == null) {
            return "";
        }
        String localName = request.getLocalName();
        if (localName != null) {
            return localName;
        }
        // DOM Level 1 nodes (built without namespace awareness) carry no local name; their node name is the
        // qualified name, so the prefix is stripped to keep local-name() semantics.
        String nodeName = request.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon < 0 ? nodeName : nodeName.substring(colon + 1);
    }
}
