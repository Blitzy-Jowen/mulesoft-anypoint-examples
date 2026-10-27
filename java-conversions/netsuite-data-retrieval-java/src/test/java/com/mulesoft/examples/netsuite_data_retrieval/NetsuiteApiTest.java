/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;
import com.mulesoft.examples.netsuite_data_retrieval.support.NetsuiteTestRecords;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Port of the original example's MUnit suite
 * {@code netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml}: its three tests, one method each under
 * the MUnit test name, with the same requests and the same assertions and comparison semantics: status 200, payload
 * not null, and for customers a substring check of the {@code "companyName": "<name>"} pair as the DataWeave JSON
 * writer spaces it.
 *
 * <p>The original ran {@code netsuite-api.xml} in an embedded Mule runtime against a live NetSuite account. Here the
 * whole application runs on a random port, ST-1 {@link HttpTestClient} sends each request to {@code /api}, and the
 * SC-16 {@link NetsuiteRestClient} is a {@link MockBean} answering with the REST fixtures under
 * {@code src/test/resources/netsuite/}, parsed as the client parses NetSuite's bodies. Everything between the socket
 * and the client is real: the controllers and their RAML validation, {@link NetsuiteSearchService}, the record-shape
 * mapping and the DataWeave JSON writer. The mocked account holds the committed customer only while the test has
 * created it, so the customers assertion depends on the {@code createCustomerInNetsuite} step, as the original's did.
 *
 * <p>The test data are read from profile {@code test} ({@code application-test.yml}) under the original MUnit
 * property names. No request leaves the JVM and no credential is read; the live run of the same suite is the
 * failsafe {@code NetsuiteApiIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NetsuiteApiTest {

    private static final String OPPORTUNITY_TITLE_KEY = "nets.opportunity.title";

    private static final String ITEM_QUANTITY_KEY = "nets.item.quantity";

    /** The internal ID of the committed customer of {@code rest-customer.json} and its SuiteQL ID row. */
    private static final String CREATED_CUSTOMER_ID = "-5";

    /** The internal ID of the committed opportunity of {@code rest-opportunity.json}. */
    private static final String OPPORTUNITY_ID = "1271";

    /** The MUnit assertion messages, kept as the failure messages of the ported assertions. */
    private static final String STATUS_MESSAGE = "The HTTP Status code is not correct!";

    private static final String NULL_PAYLOAD_MESSAGE = "Payload is null!";

    private static final String COMPANY_NAME_MESSAGE = "The testing company name should be included in the response!";

    private static final String LINKS = "links";

    private static final String ITEMS = "items";

    private static final String ID = "id";

    /** Parses fixtures as the client parses NetSuite bodies: ordered maps, integers, decimals as BigDecimal. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private static final TypeReference<List<LinkedHashMap<String, Object>>> JSON_ROWS = new TypeReference<>() { };

    @MockBean
    private NetsuiteRestClient client;

    @LocalServerPort
    private int port;

    @Autowired
    private Environment env;

    /** The customers the mocked account holds: added by {@code createRecord}, removed by {@code deleteRecord}. */
    private final Set<String> existingCustomers = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void stubNetsuite() {
        String customerQuery = NetsuiteSearchService.customerIdQuery(
                env.getRequiredProperty(NetsuiteTestRecords.COMPANY_NAME_KEY));
        String opportunityQuery = NetsuiteSearchService.opportunityIdQuery(
                env.getRequiredProperty(OPPORTUNITY_TITLE_KEY));

        // Matched against the service's own query builders, so the stub follows their quoting exactly.
        when(client.suiteQl(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (customerQuery.equals(query)) {
                return rows("rest-suiteql-customer-ids.json").stream()
                        .filter(row -> existingCustomers.contains(String.valueOf(row.get(ID))))
                        .toList();
            }
            if (opportunityQuery.equals(query)) {
                return rows("rest-suiteql-opportunity-ids.json");
            }
            return List.of();
        });
        when(client.getRecord(NetsuiteSearchService.CUSTOMER, CREATED_CUSTOMER_ID))
                .thenAnswer(invocation -> object("rest-customer.json"));
        when(client.getRecord(NetsuiteSearchService.OPPORTUNITY, OPPORTUNITY_ID))
                .thenAnswer(invocation -> object("rest-opportunity.json"));
        when(client.getRecord(NetsuiteSearchService.ITEM_SUPPLY_PLAN, "9"))
                .thenAnswer(invocation -> object("rest-itemsupplyplan-9.json"));
        when(client.getRecord(NetsuiteSearchService.ITEM_SUPPLY_PLAN, "18"))
                .thenAnswer(invocation -> object("rest-itemsupplyplan-18.json"));
        when(client.listRecordIds(NetsuiteSearchService.ITEM_SUPPLY_PLAN))
                .thenAnswer(invocation -> rows("rest-itemsupplyplan-list.json").stream()
                        .map(row -> String.valueOf(row.get(ID)))
                        .toList());
        when(client.metadataCatalog(NetsuiteSearchService.CUSTOMER))
                .thenAnswer(invocation -> object("rest-customer-catalog.json"));
        when(client.metadataCatalog(NetsuiteSearchService.OPPORTUNITY))
                .thenAnswer(invocation -> object("rest-opportunity-catalog.json"));
        when(client.metadataCatalog(NetsuiteSearchService.ITEM_SUPPLY_PLAN))
                .thenAnswer(invocation -> object("rest-itemsupplyplan-catalog.json"));
        when(client.customFieldSourceIds(any()))
                .thenAnswer(invocation -> customFieldSources(invocation.getArgument(0)));
        when(client.createRecord(eq(NetsuiteTestRecords.CUSTOMER_RECORD_TYPE), anyMap())).thenAnswer(invocation -> {
            existingCustomers.add(CREATED_CUSTOMER_ID);
            return CREATED_CUSTOMER_ID;
        });
        doAnswer(invocation -> {
            String id = invocation.getArgument(1);
            existingCustomers.remove(id);
            return null;
        }).when(client).deleteRecord(eq(NetsuiteTestRecords.CUSTOMER_RECORD_TYPE), anyString());
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/customers:netsuite-api-configTest")
    void customers() {
        String companyName = env.getRequiredProperty(NetsuiteTestRecords.COMPANY_NAME_KEY);
        Logger recordsLogger = (Logger) LoggerFactory.getLogger(NetsuiteTestRecords.class);
        Level previousLevel = recordsLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        recordsLogger.addAppender(appender);
        recordsLogger.setLevel(Level.INFO);
        try {
            String id = NetsuiteTestRecords.createCustomerInNetsuite(client, env);
            // Deleted in finally rather than after the assertions, so a failing assertion leaves no test customer.
            try {
                HttpTestClient.Response response = get("/api/customers?name=" + encode(companyName));

                assertOkJson(response);
                String payload = response.bodyAsString();
                assertNotNull(payload, NULL_PAYLOAD_MESSAGE);
                assertTrue(payload.contains("\"companyName\": \"" + companyName + "\""),
                        () -> COMPANY_NAME_MESSAGE + " " + response);
            } finally {
                NetsuiteTestRecords.deleteCustomerInNetsuite(client, id);
            }
            assertEquals(List.of("Test customer created with ID: " + CREATED_CUSTOMER_ID,
                            "Test customer with ID " + CREATED_CUSTOMER_ID + " was deleted."),
                    infoMessages(appender));
        } finally {
            recordsLogger.detachAppender(appender);
            appender.stop();
            recordsLogger.setLevel(previousLevel);
        }

        InOrder order = inOrder(client);
        order.verify(client).createRecord(NetsuiteTestRecords.CUSTOMER_RECORD_TYPE,
                NetsuiteTestRecords.restCreateBody(NetsuiteTestRecords.customer(env)));
        order.verify(client).suiteQl(NetsuiteSearchService.customerIdQuery(companyName));
        order.verify(client).deleteRecord(NetsuiteTestRecords.CUSTOMER_RECORD_TYPE, CREATED_CUSTOMER_ID);
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/opportunities:netsuite-api-configTest")
    void opportunities() {
        String title = env.getRequiredProperty(OPPORTUNITY_TITLE_KEY);

        HttpTestClient.Response response = get("/api/opportunities?title=" + encode(title));

        assertOkJson(response);
        assertNotNull(response.bodyAsString(), NULL_PAYLOAD_MESSAGE);
        verify(client).suiteQl(NetsuiteSearchService.opportunityIdQuery(title));
    }

    @Test
    @DisplayName("netsuite-api-test-suite-get:/items:netsuite-api-configTest")
    void items() {
        String quantity = env.getRequiredProperty(ITEM_QUANTITY_KEY);

        HttpTestClient.Response response = get("/api/items?quantity=" + encode(quantity));

        assertOkJson(response);
        assertNotNull(response.bodyAsString(), NULL_PAYLOAD_MESSAGE);
        verify(client).listRecordIds(NetsuiteSearchService.ITEM_SUPPLY_PLAN);
    }

    private HttpTestClient.Response get(String path) {
        return HttpTestClient.localhost(port).get(path);
    }

    /** The MUnit {@code http.status} assertion, plus the RAML-declared {@code application/json} response type. */
    private static void assertOkJson(HttpTestClient.Response response) {
        assertEquals(200, response.status(), () -> STATUS_MESSAGE + " " + response);
        String contentType = response.header("Content-Type");
        assertTrue(contentType != null && contentType.startsWith("application/json"),
                () -> "unexpected Content-Type: " + response);
    }

    /**
     * Percent-encodes a query value as the Mule HTTP requester sent it: spaces as {@code %20}, never {@code +}, so
     * the controller receives the property value unchanged.
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> infoMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * The client's answer for a custom-field source lookup: the requested script IDs, trimmed and lower-cased, that
     * {@code rest-suiteql-customfield.json} resolves, mapped to their source IDs in first-seen order.
     */
    private static Map<String, String> customFieldSources(Collection<String> scriptIds) throws IOException {
        Map<String, String> known = new LinkedHashMap<>();
        for (Map<String, Object> row : rows("rest-suiteql-customfield.json")) {
            known.put(String.valueOf(row.get("scriptid")).toLowerCase(Locale.ROOT),
                    String.valueOf(row.get("fieldvaluetyperecord")));
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String scriptId : scriptIds) {
            if (scriptId == null || scriptId.isBlank()) {
                continue;
            }
            String id = scriptId.trim().toLowerCase(Locale.ROOT);
            String source = known.get(id);
            if (source != null) {
                resolved.putIfAbsent(id, source);
            }
        }
        return resolved;
    }

    /** The {@code items} of a paged fixture without their {@code links}, as the client returns rows. */
    private static List<Map<String, Object>> rows(String fixture) throws IOException {
        List<LinkedHashMap<String, Object>> items = JSON.convertValue(object(fixture).get(ITEMS), JSON_ROWS);
        return items.stream()
                .<Map<String, Object>>map(item -> {
                    item.remove(LINKS);
                    return item;
                })
                .toList();
    }

    /** A fixture of {@code src/test/resources/netsuite/}, freshly parsed on each call. */
    private static LinkedHashMap<String, Object> object(String fixture) throws IOException {
        try (InputStream in = new ClassPathResource("netsuite/" + fixture).getInputStream()) {
            return JSON.readValue(in, JSON_OBJECT);
        }
    }
}
