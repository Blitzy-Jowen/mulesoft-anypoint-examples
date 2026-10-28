/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.soap.client.SoapFaultClientException;

import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient.OutboundSecurity;
import com.mulesoft.examples.soap_webservice_security.config.WrongPasswordCallback;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient.Response;
import com.mulesoft.examples.soap_webservice_security.support.PortInUseRetryingContextLoader;

/**
 * Port of the original example's {@code SoapWebserviceSecurityIT} functional test: the same nine requests against the
 * six Greeter paths of the running application, with the same assertions. Each test first fetches the path's
 * {@code ?wsdl}, as the original's CXF {@code Service.create} did, then sends {@code greet("Mule")} through
 * {@link SecureGreeterClient} with the WS-Security profile the original {@code SecureClient} built; the six positive
 * calls must answer {@code Hello Mule} exactly, and the three negative calls must fail with a SOAP fault whose
 * {@code faultstring} contains the original substring.
 *
 * <p>The demo users, keystore password and SAML issuer key password of the committed keystore come from profile
 * {@code test}.
 */
// The SOAP listener runs on a random port (@LocalServerPort) rather than a pre-allocated DEFINED_PORT: the plan maps
// the original fixed ports to RANDOM_PORT plus @DynamicPropertySource, and a random port leaves no window between
// choosing and binding it. Only the additional client listener, which must have a concrete port, gets a free one,
// drawn for each load attempt; PortInUseRetryingContextLoader loads the context again when another process takes
// that port before the listener binds it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
final class SoapWebserviceSecurityTest {

    private static final String SECURITY_EXCEPTION_EXPECTED = "Request should have thrown a security exception";

    @LocalServerPort
    private int port;

    @Autowired
    private SecureGreeterClient secureGreeterClient;

    /** Spring calls this for every load attempt of the context, so each attempt binds a newly drawn port. */
    @DynamicPropertySource
    static void clientListenerPort(DynamicPropertyRegistry registry) {
        int clientPort = TestSocketUtils.findAvailableTcpPort();
        // listeners.additional[0].port reads ${ports.client}, so the scalar is overridden and the list keeps its
        // name, host and routes from application.yml.
        registry.add("ports.client", () -> clientPort);
    }

    @Test
    void testUnsecure() {
        fetchWsdl("unsecure");
        String reply = secureGreeterClient.greet(service("unsecure"), OutboundSecurity.NONE, "Mule");
        assertEquals("Hello Mule", reply);
    }

    @Test
    void testUsernameToken() {
        fetchWsdl("username");
        String reply = secureGreeterClient.greet(service("username"),
                secureGreeterClient.getUsernameTokenProps("UsernameToken Timestamp"), "Mule");
        assertEquals("Hello Mule", reply);
    }

    @Test
    void testUsernameTokenWrongPasswordError() {
        OutboundSecurity wss4jProps = secureGreeterClient.getUsernameTokenProps("UsernameToken Timestamp")
                .withPasswordCallback(new WrongPasswordCallback());

        fetchWsdl("username");
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class,
                () -> secureGreeterClient.greet(service("username"), wss4jProps, "Mule"),
                SECURITY_EXCEPTION_EXPECTED);
        assertTrue(e.getMessage().contains("security token"), e::getMessage);
    }

    @Test
    void testUsernameTokenSigned() {
        fetchWsdl("signed");
        String reply = secureGreeterClient.greet(service("signed"),
                secureGreeterClient.getUsernameTokenProps("UsernameToken Signature Timestamp"), "Mule");
        assertEquals("Hello Mule", reply);
    }

    @Test
    void testUsernameTokenUnsignedError() {
        fetchWsdl("signed");
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class,
                () -> secureGreeterClient.greet(service("signed"),
                        secureGreeterClient.getUsernameTokenProps("UsernameToken Timestamp"), "Mule"),
                SECURITY_EXCEPTION_EXPECTED);
        assertTrue(e.getMessage().contains("<wsse:Security> header"), e::getMessage);
    }

    @Test
    void testUsernameTokenEncrypted() {
        fetchWsdl("encrypted");
        String reply = secureGreeterClient.greet(service("encrypted"),
                secureGreeterClient.getUsernameTokenProps("UsernameToken Timestamp Encrypt"), "Mule");
        assertEquals("Hello Mule", reply);
    }

    @Test
    void testSamlToken() {
        fetchWsdl("saml");
        String reply = secureGreeterClient.greet(service("saml"),
                secureGreeterClient.getSamlTokenProps("SAMLTokenUnsigned Timestamp", "saml.properties"), "Mule");
        assertEquals("Hello Mule", reply);
    }

    @Test
    void testSamlTokenWrongIssuer() {
        fetchWsdl("saml");
        SoapFaultClientException e = assertThrows(SoapFaultClientException.class,
                () -> secureGreeterClient.greet(service("saml"),
                        secureGreeterClient.getSamlTokenProps("SAMLTokenUnsigned Timestamp", "wrong-saml.properties"),
                        "Mule"),
                SECURITY_EXCEPTION_EXPECTED);
        assertTrue(e.getMessage().contains("<wsse:Security> header"), e::getMessage);
    }

    @Test
    void testSignedSamlToken() {
        fetchWsdl("signedsaml");
        String reply = secureGreeterClient.greet(service("signedsaml"),
                secureGreeterClient.getSignedSamlTokenProps("SAMLTokenSigned", "saml.properties"), "Mule");
        assertEquals("Hello Mule", reply);
    }

    /** The Greeter path {@code /services/<path>} on the SOAP listener. */
    private URI service(String path) {
        return URI.create("http://localhost:" + port + "/services/" + path);
    }

    /**
     * The metadata fetch the original made through {@code Service.create(<path>?wsdl, {...}GreeterService)}: the
     * path publishes the Greeter WSDL, whose service is {@code GreeterService}.
     */
    private void fetchWsdl(String path) {
        Response response = HttpTestClient.localhost(port).get("/services/" + path + "?wsdl");
        assertEquals(200, response.status(), response::toString);
        assertTrue(response.bodyAsString().contains("GreeterService"), response::toString);
    }
}
