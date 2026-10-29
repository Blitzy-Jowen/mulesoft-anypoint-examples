/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code xml-only-soap-webservice} (Apache License 2.0, see the
 * project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=mocks.xml,Hospital_Admissions_SOA.xml} loaded both flow files into one Mule runtime; this
 * class starts them as one standalone process on Undertow, serving the three SOAP listeners of the original's single
 * listener configuration on {@code 0.0.0.0:${http.port}} (8081, {@code application.yml}):
 * <ul>
 *   <li>{@code /AdmissionService} — flow {@code admitPatientService} of {@code Hospital_Admissions_SOA.xml}, which
 *       calls the two mocks below over HTTP;</li>
 *   <li>{@code /PatientService} — mock flow {@code PatientService} of {@code mocks.xml};</li>
 *   <li>{@code /EHRService} — mock flow {@code EHRService} of {@code mocks.xml}.</li>
 * </ul>
 *
 * <p>Each listener is a Spring WS endpoint working on the SOAP Body as a DOM element, with the DataWeave scripts
 * reimplemented as hand-written mappers; no JAX-WS, CXF or DataWeave runtime is involved.
 *
 * <p>Spring WS is configured by the project's {@code WsConfigurer} ({@code config.WebServiceConfig}) alone, never
 * through Spring WS's own enabling annotation: that annotation switches off Boot's web-services auto-configuration
 * and with it the {@code MessageDispatcherServlet} that serves all three paths.
 */
@SpringBootApplication
public class XmlOnlySoapWebserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmlOnlySoapWebserviceApplication.class, args);
    }
}
