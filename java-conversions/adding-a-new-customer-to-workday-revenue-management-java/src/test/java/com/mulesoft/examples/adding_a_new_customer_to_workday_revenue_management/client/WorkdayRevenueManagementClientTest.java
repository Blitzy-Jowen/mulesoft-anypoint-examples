/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.transform.Source;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.WorkdayTestRequests;

import jakarta.xml.bind.JAXBElement;

/**
 * Offline test of {@link WorkdayRevenueManagementClient} against a WireMock stand-in for the Workday
 * {@code Revenue_Management} v25.0 SOAP endpoint.
 *
 * <p>The client replaces the Mule Workday connector of {@code add_a_new_customer.xml}
 * ({@code wd-connector:config} at line 5, {@code wd-connector:invoke type="Revenue_Management||Put_Customer"} at line
 * 42) and serves the MUnit suite's {@code Get_Customers} lookup ({@code integration-test.xml} lines 64-80) through
 * {@link WorkdayRevenueManagementClient#sendRaw}. Every case drives a real client behind a real
 * {@link UpstreamCallExecutor} with the real {@link WorkdayFailureClassifier}, so the approved connector failure
 * handling is proved end to end on the wire: what each request carries (SOAP 1.1, no SOAP action, a
 * {@code PasswordText} UsernameToken named {@code user@tenant} without nonce or creation time), one re-authentication
 * and one retry after HTTP 401 or a {@code wsse:FailedAuthentication} fault, no retry after HTTP 429, other faults
 * rethrown unchanged, and no retry, ever, after a read timeout or a refused connection. Every failure case counts the
 * requests WireMock received.
 *
 * <p>Each case builds a fresh client, wrapped in a Mockito spy so the number of {@code reauthenticate()} calls can be
 * verified; the executor's re-authentication step is the spy's own method reference. Every value is fake, every port
 * is ephemeral and only {@code localhost} is contacted. No Spring context is started.
 */
class WorkdayRevenueManagementClientTest {

    /** Revenue_Management v25.0 path of the fake tenant {@link #TENANT}. */
    static final String PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String WD_NS = WorkdayTestRequests.WD_NS;

    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    private static final String WSU_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";

    private static final String PASSWORD_TEXT =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    private static final Map<String, String> NAMESPACES =
            Map.of("soapenv", SOAPENV_NS, "wd", WD_NS, "wsse", WSSE_NS, "wsu", WSU_NS);

    private static final String USERNAME_TOKEN = "/soapenv:Envelope/soapenv:Header/wsse:Security/wsse:UsernameToken";

    private static final String USER = "testuser";

    private static final String TENANT = "test_pt1";

    private static final String PASSWORD = "test-password";

    private static final String CUSTOMER_NAME = "John1";

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** Longer than {@link #TIMEOUT}, so the client's read times out while WireMock still holds the response. */
    private static final int SLOW_RESPONSE_DELAY_MILLIS = 3000;

    private static final String TEXT_XML = "text/xml;charset=UTF-8";

    private static final String PUT_CUSTOMER_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Put_Customer_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Customer_Reference wd:Descriptor="John1">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            <wd:ID wd:type="Customer_Reference_ID">John1</wd:ID>\
            </wd:Customer_Reference>\
            </wd:Put_Customer_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String FAILED_AUTHENTICATION_FAULT = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <soapenv:Fault \
            xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">\
            <faultcode>wsse:FailedAuthentication</faultcode>\
            <faultstring>invalid username or password</faultstring>\
            </soapenv:Fault>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String VALIDATION_FAULT_STRING =
            "Invalid ID value. 'BAD' is not a valid ID value for type = 'Customer_Category_ID'";

    private static final String VALIDATION_FAULT = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <soapenv:Fault xmlns:wd="urn:com.workday/bsvc">\
            <faultcode>wd:Validation_Error</faultcode>\
            <faultstring>%s</faultstring>\
            </soapenv:Fault>\
            </soapenv:Body>\
            </soapenv:Envelope>""".formatted(VALIDATION_FAULT_STRING);

    private static final String GET_CUSTOMERS_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Get_Customers_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Request_Criteria><wd:Customer_Reference_ID>John1</wd:Customer_Reference_ID></wd:Request_Criteria>\
            <wd:Response_Group><wd:Include_Reference>1</wd:Include_Reference>\
            <wd:Include_Customer_Data>1</wd:Include_Customer_Data></wd:Response_Group>\
            <wd:Response_Results>\
            <wd:Total_Results>1</wd:Total_Results>\
            <wd:Total_Pages>1</wd:Total_Pages>\
            <wd:Page_Results>1</wd:Page_Results>\
            <wd:Page>1</wd:Page>\
            </wd:Response_Results>\
            <wd:Response_Data>\
            <wd:Customer>\
            <wd:Customer_Reference wd:Descriptor="John1">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            <wd:ID wd:type="Customer_Reference_ID">John1</wd:ID>\
            </wd:Customer_Reference>\
            <wd:Customer_Data>\
            <wd:Customer_ID>John1</wd:Customer_ID>\
            <wd:Customer_Name>John1</wd:Customer_Name>\
            <wd:Customer_Category_Reference wd:Descriptor="Corporate">\
            <wd:ID wd:type="WID">5c1b0b8a6d8b10000a1c5e2f3d4a0001</wd:ID>\
            <wd:ID wd:type="Customer_Category_ID">CUSTOMER_CATEGORY-5</wd:ID>\
            </wd:Customer_Category_Reference>\
            </wd:Customer_Data>\
            </wd:Customer>\
            </wd:Response_Data>\
            </wd:Get_Customers_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    private static final String AUTH_SCENARIO = "workday-authentication";

    private static final String RETRIED = "retried";

    private static final WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private WorkdayRevenueManagementClient client;

    @BeforeAll
    static void startWorkday() {
        wm.start();
    }

    @AfterAll
    static void stopWorkday() {
        wm.stop();
    }

    @BeforeEach
    void resetWorkdayAndBuildClient() {
        wm.resetAll();
        client = newClient(wm.baseUrl() + PATH);
    }

    @Test
    void putCustomerSendsUsernameTokenAndReturnsDescriptor() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        assertThat(response.getCustomerReference().getID())
                .extracting(CustomerObjectIDType::getType, CustomerObjectIDType::getValue)
                .containsExactly(tuple("WID", "3bc6c1b9f4a0100156c2a0f8e9b10000"),
                        tuple("Customer_Reference_ID", CUSTOMER_NAME));

        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getHeader("Content-Type")).startsWith("text/xml");
        // The v25.0 binding declares no soapAction: the header is absent or the SOAP 1.1 empty action.
        if (request.containsHeader("SOAPAction")) {
            assertThat(request.getHeader("SOAPAction")).isIn("", "\"\"");
        }

        String body = request.getBodyAsString();
        assertThat(xpath(body, "count(/soapenv:Envelope/soapenv:Body/wd:Put_Customer_Request)")).isEqualTo("1");
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_Name")).isEqualTo(CUSTOMER_NAME);
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_ID")).isEqualTo(CUSTOMER_NAME);
        assertThat(xpath(body, "//wd:Customer_Data/wd:Customer_Reference_ID")).isEqualTo(CUSTOMER_NAME);
        assertCarriesPasswordTextUsernameToken(body);

        verify(client, never()).reauthenticate();
    }

    @Test
    void retriesOnceAfterHttp401() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(plainText(401, "Unauthorized"))
                .willSetStateTo(RETRIED));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(RETRIED)
                .willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(2);
        // The retry goes out with the UsernameToken that reauthenticate() rebuilt from the current credentials.
        assertCarriesPasswordTextUsernameToken(requests.get(1).getBodyAsString());
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void failsAfterSecondHttp401() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(plainText(401, "Unauthorized")));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOf(UpstreamAuthenticationException.class);
        assertThat(((UpstreamAuthenticationException) failure).getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        assertThat(failure.getCause()).isInstanceOfSatisfying(WorkdayRevenueManagementClient.HttpStatusException.class,
                cause -> assertThat(cause.statusCode()).isEqualTo(401));
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void rateLimitIsNotRetried() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(plainText(429, "Too Many Requests")
                .withHeader("Retry-After", "30")));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOfSatisfying(UpstreamRateLimitException.class, rateLimit -> {
            assertThat(rateLimit.getRetryAfter()).isEqualTo("30");
            assertThat(rateLimit.getSystem()).isEqualTo(UpstreamSystem.WORKDAY);
        });
        assertThat(failure.getCause()).isInstanceOfSatisfying(WorkdayRevenueManagementClient.HttpStatusException.class,
                cause -> assertThat(cause.statusCode()).isEqualTo(429));
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        verify(client, never()).reauthenticate();
    }

    @Test
    void failedAuthenticationFaultIsRetriedOnce() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(soap(500, FAILED_AUTHENTICATION_FAULT))
                .willSetStateTo(RETRIED));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario(AUTH_SCENARIO)
                .whenScenarioStateIs(RETRIED)
                .willReturn(soap(200, PUT_CUSTOMER_RESPONSE)));

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest(CUSTOMER_NAME));

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
        verify(client, times(1)).reauthenticate();
    }

    @Test
    void genericFaultIsRethrownUnchanged() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(500, VALIDATION_FAULT)));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        // Exactly Spring WS's own fault type: neither wrapped in an upstream exception nor retried.
        assertThat(failure).isExactlyInstanceOf(SoapFaultClientException.class)
                .hasMessageContaining("Invalid ID value");
        SoapFaultClientException fault = (SoapFaultClientException) failure;
        assertThat(fault.getFaultCode().getLocalPart()).isEqualTo("Validation_Error");
        assertThat(fault.getFaultStringOrReason()).isEqualTo(VALIDATION_FAULT_STRING);
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
        verify(client, never()).reauthenticate();
    }

    @Test
    void timeoutIsNotRetried() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, PUT_CUSTOMER_RESPONSE)
                .withFixedDelay(SLOW_RESPONSE_DELAY_MILLIS)));

        Throwable failure = catchThrowable(() -> client.putCustomer(putCustomerRequest(CUSTOMER_NAME)));

        assertThat(failure).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                unavailable -> assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.WORKDAY));
        assertThat(failure.getCause()).isInstanceOf(WebServiceIOException.class);
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(SocketTimeoutException.class);
        wm.verify(lessThanOrExactly(1), postRequestedFor(urlEqualTo(PATH)));
        verify(client, never()).reauthenticate();
    }

    @Test
    void refusedConnectionIsNotRetried() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        WorkdayRevenueManagementClient unreachable = newClient("http://localhost:" + port + PATH);

        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> unreachable.putCustomer(putCustomerRequest(CUSTOMER_NAME)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                unavailable -> assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.WORKDAY));
        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(ConnectException.class);
        verify(unreachable, never()).reauthenticate();
        // A refused connection fails at once; the 10 s default timeout or a retry would show here.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void sendRawGetCustomersReturnsDocument() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(soap(200, GET_CUSTOMERS_RESPONSE)));

        Source request = WorkdayTestRequests.getCustomersRequest(CUSTOMER_NAME);
        Document document = client.sendRaw(request);

        assertThat(document.getDocumentElement().getNamespaceURI()).isEqualTo(WD_NS);
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("Get_Customers_Response");
        assertThat(WorkdayTestRequests.totalResults(document)).isEqualTo("1");

        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        String body = requests.get(0).getBodyAsString();
        assertThat(xpath(body, "count(/soapenv:Envelope/soapenv:Body/wd:Get_Customers_Request)")).isEqualTo("1");
        assertThat(xpath(body, "//wd:Request_Criteria/wd:Customer_Reference_ID")).isEqualTo(CUSTOMER_NAME);
        assertCarriesPasswordTextUsernameToken(body);
        verify(client, never()).reauthenticate();
    }

    /**
     * A spied client for {@code endpoint} with the fake test credentials and 1 s connect and read timeouts, behind a
     * real executor and the real Workday classifier.
     */
    private static WorkdayRevenueManagementClient newClient(String endpoint) {
        WorkdayProperties properties = new WorkdayProperties();
        properties.setHostname("localhost");
        properties.setTenant(TENANT);
        properties.setUser(USER);
        properties.setPassword(PASSWORD);
        properties.setEndpoint(endpoint);
        properties.setConnectTimeout(TIMEOUT);
        properties.setReadTimeout(TIMEOUT);
        return spy(new WorkdayRevenueManagementClient(properties,
                new UpstreamCallExecutor(List.of(new WorkdayFailureClassifier()))));
    }

    /** The original mapping script's {@code Put_Customer_Request}: ID, reference ID and name all {@code name}. */
    private static JAXBElement<PutCustomerRequestType> putCustomerRequest(String name) {
        ObjectFactory factory = new ObjectFactory();
        CustomerWWSDataType customerData = factory.createCustomerWWSDataType();
        customerData.setCustomerID(name);
        customerData.setCustomerReferenceID(name);
        customerData.setCustomerName(name);
        PutCustomerRequestType request = factory.createPutCustomerRequestType();
        request.setCustomerData(customerData);
        return factory.createPutCustomerRequest(request);
    }

    private static ResponseDefinitionBuilder soap(int status, String envelope) {
        return aResponse().withStatus(status).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    private static ResponseDefinitionBuilder plainText(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "text/plain").withBody(body);
    }

    /** Asserts the request's {@code wsse:UsernameToken}: {@code user@tenant}, clear-text password, no nonce or time. */
    private static void assertCarriesPasswordTextUsernameToken(String envelope) {
        assertThat(xpath(envelope, "count(" + USERNAME_TOKEN + ")")).isEqualTo("1");
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Username")).isEqualTo(USER + "@" + TENANT);
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Password/@Type")).isEqualTo(PASSWORD_TEXT);
        assertThat(xpath(envelope, USERNAME_TOKEN + "/wsse:Password")).isEqualTo(PASSWORD);
        assertThat(xpath(envelope, "count(//wsse:Nonce)")).isEqualTo("0");
        assertThat(xpath(envelope, "count(//wsu:Created)")).isEqualTo("0");
    }

    private static String xpath(String xml, String expression) {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(NAMESPACES);
        return engine.evaluate(expression, Input.fromString(xml).build());
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
