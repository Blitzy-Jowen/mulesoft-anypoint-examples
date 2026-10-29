/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.xml_only_soap_webservice.support.HttpTestClient;

/**
 * Port of the original example's {@code XMLWithSoapIT} functional test, keeping its request and its four
 * assertions with their contains-semantics. The committed SOAP request {@code message.xml} is posted to the
 * {@code AdmissionService} listener, and the reply must contain the bill's {@code runningTotal} and {@code status}.
 *
 * <p>Nothing is mocked: as in the original, which loaded {@code Hospital_Admissions_SOA.xml} and {@code mocks.xml}
 * into one Mule application, the whole application runs in one process on a random port, and the orchestration
 * reaches the {@code /PatientService} and {@code /EHRService} mocks over HTTP on that same port. A failed mock call
 * would answer with a {@code soap:Server} fault, which lacks both substrings.
 *
 * <p>Wire-level checks of status, headers, faults and the exact response shape live in
 * {@code controller/AdmissionServiceEndpointTest} and the mapper tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class XMLWithSoapTest {

    private String MESSAGE;
    private String REPLY1 = "<runningTotal>500</runningTotal>";
    private String REPLY2 = "<status>ADMITTED</status>";

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/message.xml")) {
            if (in == null) {
                throw new IllegalStateException("test resource /message.xml is not on the test classpath");
            }
            MESSAGE = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void testXMLwithSoap() throws Exception {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response result = client.post("/AdmissionService", "text/xml;charset=UTF-8", MESSAGE);
        assertNotNull(result);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(result.bodyAsString().isEmpty(), () -> "reply payload must not be empty: " + result);
        assertTrue(result.bodyAsString().contains(REPLY1), () -> "reply lacks " + REPLY1 + ": " + result);
        assertTrue(result.bodyAsString().contains(REPLY2), () -> "reply lacks " + REPLY2 + ": " + result);
    }
}
