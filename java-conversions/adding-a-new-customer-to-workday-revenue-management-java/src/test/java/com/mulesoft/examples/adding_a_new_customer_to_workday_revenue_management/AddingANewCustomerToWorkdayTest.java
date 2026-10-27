package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueManagementClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.WorkdayTestRequests;

/**
 * Surefire port of the original example's only test, the MUnit suite {@code src/test/munit/integration-test.xml},
 * keeping its single test, its steps and its assertion with their original semantics:
 * <ol>
 * <li>before-test (line 32): the customer is named {@code "John" + System.currentTimeMillis()};</li>
 * <li>"Input data" (line 37) builds the {@code root/Account} customer XML and {@code flow-ref add-customer-flow}
 * (line 52) runs the application's {@code POST /} flow, which answers {@code Added customer: <descriptor>};</li>
 * <li>{@code retrieveAccountFromWdayFlow} (lines 62-82) reads the customer back with {@code Get_Customers} by its
 * {@code Customer_Reference_ID}, and {@code assert-on-equals expectedValue="1"} (line 57) compares
 * {@code Get_Customers_Response.Response_Results.Total_Results} as a string;</li>
 * <li>{@code inactivateCustomerInWorkdayFlow} (lines 84-122) inactivates it with a {@code Put_Customer} carrying
 * {@code wday.status.id}, {@code wday.reason.id} and the retrieved customer's {@code Customer_Category_ID}.</li>
 * </ol>
 * The whole application runs on a random port and the add step goes through its HTTP listener, so the controller,
 * service, both mappers and the Workday client with its UsernameToken and failure handling are the production code.
 * The suite's three DataWeave scripts and its assertion are {@link WorkdayTestRequests}, sent through the
 * application's own {@link WorkdayRevenueManagementClient#sendRaw}. An in-process WireMock server stands in for the
 * Workday {@code Revenue_Management} v25.0 endpoint, so no credential is needed and nothing leaves {@code localhost}.
 * The same steps run against a live tenant in the failsafe {@code AddingANewCustomerToWorkdayIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AddingANewCustomerToWorkdayTest {

    /** Revenue_Management v25.0 path of the fake tenant {@code test_pt1} of {@code application-test.yml}. */
    private static final String ENDPOINT_PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final Map<String, String> NAMESPACES =
            Map.of("soapenv", SOAPENV_NS, "wd", WorkdayTestRequests.WD_NS);

    private static final String BODY = "/soapenv:Envelope/soapenv:Body";

    private static final String PUT_CUSTOMER = BODY + "/wd:Put_Customer_Request";

    /**
     * Descriptor of the stubbed {@code Put_Customer_Response}. It differs from the posted name, so the add step proves
     * the response text is taken from Workday's {@code Customer_Reference/@Descriptor}, as the original
     * "Extract Customer Name" script took it, and not echoed from the input.
     */
    private static final String DESCRIPTOR = "John Test Customer";

    /**
     * WID listed before the {@code Customer_Category_ID} entry of the retrieved customer's category reference, the
     * order Workday uses. It must never reach the inactivating request, which proves the original
     * {@code filter $.@type == "Customer_Category_ID"} (line 103) rather than a first-{@code ID} pick.
     */
    private static final String DECOY_CATEGORY_WID = "0123456789abcdef0123456789abcdef";

    private static final String CUSTOMER_CATEGORY_ID = "CUSTOMER_CATEGORY-5";

    private static final String TEXT_XML = "text/xml;charset=UTF-8";

    private static final String PUT_CUSTOMER_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Put_Customer_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Customer_Reference wd:Descriptor="%s">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            </wd:Customer_Reference>\
            </wd:Put_Customer_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""".formatted(DESCRIPTOR);

    /**
     * {@code Get_Customers_Response} in schema order, with {@code {name}}, {@code {decoy}} and {@code {category}}
     * replaced per test by the customer name, {@link #DECOY_CATEGORY_WID} and {@link #CUSTOMER_CATEGORY_ID}.
     */
    private static final String GET_CUSTOMERS_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Get_Customers_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Response_Results>\
            <wd:Total_Results>1</wd:Total_Results>\
            <wd:Total_Pages>1</wd:Total_Pages>\
            <wd:Page_Results>1</wd:Page_Results>\
            <wd:Page>1</wd:Page>\
            </wd:Response_Results>\
            <wd:Response_Data>\
            <wd:Customer>\
            <wd:Customer_Reference wd:Descriptor="{name}">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            <wd:ID wd:type="Customer_Reference_ID">{name}</wd:ID>\
            </wd:Customer_Reference>\
            <wd:Customer_Data>\
            <wd:Customer_ID>{name}</wd:Customer_ID>\
            <wd:Customer_Name>{name}</wd:Customer_Name>\
            <wd:Business_Entity_Data>\
            <wd:Business_Entity_Name>{name}</wd:Business_Entity_Name>\
            </wd:Business_Entity_Data>\
            <wd:Customer_Category_Reference wd:Descriptor="Corporate">\
            <wd:ID wd:type="WID">{decoy}</wd:ID>\
            <wd:ID wd:type="Customer_Category_ID">{category}</wd:ID>\
            </wd:Customer_Category_Reference>\
            </wd:Customer_Data>\
            </wd:Customer>\
            </wd:Response_Data>\
            </wd:Get_Customers_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""";

    /**
     * Started while the class loads, so its port exists when Spring resolves the lazy {@code wday.endpoint} supplier
     * of {@link #workdayEndpoint}; the dynamic port keeps parallel builds on this host apart.
     */
    private static final WireMockServer WORKDAY = startWorkday();

    @LocalServerPort
    private int port;

    @Autowired
    private WorkdayRevenueManagementClient client;

    @Value("${wday.status.id}")
    private String statusId;

    @Value("${wday.reason.id}")
    private String reasonId;

    private String customerName;

    @DynamicPropertySource
    static void workdayEndpoint(DynamicPropertyRegistry registry) {
        registry.add("wday.endpoint", () -> WORKDAY.baseUrl() + ENDPOINT_PATH);
        // Below the 10 s production default, so a stub that never answers fails the build quickly instead of
        // stalling it.
        registry.add("wday.read-timeout", () -> "5s");
    }

    @AfterAll
    static void stopWorkday() {
        WORKDAY.stop();
    }

    @BeforeEach
    void beforeTest() {
        customerName = "John" + System.currentTimeMillis();

        WORKDAY.resetAll();
        WORKDAY.stubFor(post(urlEqualTo(ENDPOINT_PATH))
                .withRequestBody(containing("Get_Customers_Request"))
                .willReturn(soapResponse(GET_CUSTOMERS_RESPONSE
                        .replace("{name}", customerName)
                        .replace("{decoy}", DECOY_CATEGORY_WID)
                        .replace("{category}", CUSTOMER_CATEGORY_ID))));
        // Serves both the add-customer flow's Put_Customer and the inactivating one.
        WORKDAY.stubFor(post(urlEqualTo(ENDPOINT_PATH))
                .withRequestBody(containing("Put_Customer_Request"))
                .willReturn(soapResponse(PUT_CUSTOMER_RESPONSE)));
    }

    @Test
    @DisplayName("MUnit Test: add, retrieve and inactivate customer")
    void test() throws Exception {
        // flow-ref add-customer-flow, with the "Input data" customer XML as payload
        HttpTestClient.Response added = HttpTestClient.localhost(port)
                .post("/", "application/xml", WorkdayTestRequests.customerXml(customerName));

        assertThat(added.status()).as("add-customer-flow response: %s", added).isEqualTo(200);
        assertThat(added.bodyAsString()).startsWith("Added customer: ").isEqualTo("Added customer: " + DESCRIPTOR);
        List<LoggedRequest> adds = workdayRequestsContaining("Put_Customer_Request");
        assertThat(adds).as("Put_Customer calls of the add-customer flow").hasSize(1);
        String addBody = adds.get(0).getBodyAsString();
        assertThat(addBody).contains(customerName);
        assertThat(xpath(addBody, PUT_CUSTOMER + "/wd:Customer_Data/wd:Customer_Name")).isEqualTo(customerName);
        assertThat(allWorkdayRequests()).as("Workday calls after the add step").hasSize(1);

        // retrieveAccountFromWdayFlow
        Document response = client.sendRaw(WorkdayTestRequests.getCustomersRequest(customerName));

        // munit:assert-on-equals expectedValue="1"
        assertThat(WorkdayTestRequests.totalResults(response)).as("One customer should be retrieved").isEqualTo("1");
        List<LoggedRequest> retrievals = workdayRequestsContaining("Get_Customers_Request");
        assertThat(retrievals).as("Get_Customers calls").hasSize(1);
        String retrievalBody = retrievals.get(0).getBodyAsString();
        assertThat(retrievalBody).contains(customerName);
        assertThat(xpath(retrievalBody,
                BODY + "/wd:Get_Customers_Request/wd:Request_Criteria/wd:Customer_Reference_ID"))
                .isEqualTo(customerName);

        // inactivateCustomerInWorkdayFlow
        Document inactivated = client.sendRaw(
                WorkdayTestRequests.inactivateRequest(customerName, response, statusId, reasonId));

        assertThat(inactivated.getDocumentElement().getLocalName()).isEqualTo("Put_Customer_Response");
        List<LoggedRequest> inactivations = WORKDAY.findAll(postRequestedFor(urlEqualTo(ENDPOINT_PATH))
                .withRequestBody(containing("Customer_Status_Value_Reference"))
                .withRequestBody(containing("Reason_for_Customer_Status_Change_Reference")));
        assertThat(inactivations).as("inactivating Put_Customer calls").hasSize(1);
        assertInactivationRequest(inactivations.get(0).getBodyAsString());

        // add, retrieve and inactivate, each sent once: nothing was retried
        assertThat(allWorkdayRequests()).as("Workday calls of the test").hasSize(3);
    }

    /** The inactivating {@code Put_Customer_Request} as the original script (lines 87-116) wrote it. */
    private void assertInactivationRequest(String body) {
        String customerReferenceId = PUT_CUSTOMER + "/wd:Customer_Reference/wd:ID";
        assertThat(xpath(body, customerReferenceId)).isEqualTo(customerName);
        assertThat(xpath(body, customerReferenceId + "/@wd:type")).isEqualTo("Customer_Reference_ID");

        String customerData = PUT_CUSTOMER + "/wd:Customer_Data";
        assertThat(xpath(body, customerData + "/wd:Business_Entity_Data/wd:Business_Entity_Name"))
                .isEqualTo(customerName);
        assertThat(xpath(body, customerData + "/wd:Customer_Name")).isEqualTo(customerName);

        String categoryId = customerData + "/wd:Customer_Category_Reference/wd:ID";
        assertThat(xpath(body, "count(" + categoryId + ")")).isEqualTo("1");
        assertThat(xpath(body, categoryId)).isEqualTo(CUSTOMER_CATEGORY_ID);
        assertThat(xpath(body, categoryId + "/@wd:type")).isEqualTo("Customer_Category_ID");
        assertThat(body).doesNotContain(DECOY_CATEGORY_WID);

        String statusData = customerData + "/wd:Customer_Status_Data";
        String statusValueId = statusData + "/wd:Customer_Status_Value_Reference/wd:ID";
        assertThat(xpath(body, statusValueId)).isEqualTo(statusId).isEqualTo("TEST-STATUS-WID");
        assertThat(xpath(body, statusValueId + "/@wd:type")).isEqualTo("WID");
        String reasonValueId = statusData + "/wd:Reason_for_Customer_Status_Change_Reference/wd:ID";
        assertThat(xpath(body, reasonValueId)).isEqualTo(reasonId).isEqualTo("TEST-REASON-WID");
        assertThat(xpath(body, reasonValueId + "/@wd:type")).isEqualTo("WID");

        // The script's element order reaches the wire unchanged because the request is sent raw.
        assertThat(childNames(body, customerData)).containsExactly(
                "Business_Entity_Data", "Customer_Name", "Customer_Category_Reference", "Customer_Status_Data");
    }

    private static List<LoggedRequest> workdayRequestsContaining(String text) {
        return WORKDAY.findAll(postRequestedFor(urlEqualTo(ENDPOINT_PATH)).withRequestBody(containing(text)));
    }

    private static List<LoggedRequest> allWorkdayRequests() {
        return WORKDAY.findAll(anyRequestedFor(anyUrl()));
    }

    private static ResponseDefinitionBuilder soapResponse(String envelope) {
        return aResponse().withStatus(200).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    private static String xpath(String xml, String expression) {
        return xpathEngine().evaluate(expression, Input.fromString(xml).build());
    }

    /** Local names of the element children of the element {@code expression} selects, in document order. */
    private static List<String> childNames(String xml, String expression) {
        List<String> names = new ArrayList<>();
        for (Node child : xpathEngine().selectNodes(expression + "/*", Input.fromString(xml).build())) {
            names.add(child.getLocalName());
        }
        return names;
    }

    private static JAXPXPathEngine xpathEngine() {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(NAMESPACES);
        return engine;
    }

    private static WireMockServer startWorkday() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
}
