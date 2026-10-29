/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.client.UpstreamSystem;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.CustomerSearchMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.DwJsonWriter;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.ItemSupplyPlanMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.OpportunitySearchMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link NetsuiteSearchService}, the processor chains of the three APIkit flows of
 * {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}: the search criteria scripts, the record
 * acquisition over SC-16 (AAP 0.7.4), the {@code payload map $} output and the success log line of each flow.
 *
 * <p>{@link NetsuiteRestClient} is a Mockito mock under strict stubs, so every stub must be used and every call the
 * service makes must be one the test arranged. The mappers are the real ones, so the JSON each search returns is the
 * JSON the controllers answer. REST records, metadata catalogs, SuiteQL rows and custom-field source IDs are built
 * in memory as {@code LinkedHashMap}s with integers as {@code Integer}/{@code Long} and decimals as
 * {@code BigDecimal}, as the client parses them; the committed-response tests read them instead from the REST
 * fixtures of {@code src/test/resources/netsuite}, parsed the same way, and compare the answers with the committed
 * {@code api/*-response.json} files. The success line of each flow is read from a Logback {@link ListAppender} on the
 * service's logger. No Spring context or network is used and no file is written.
 */
@ExtendWith(MockitoExtension.class)
class NetsuiteSearchServiceTest {

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static final String CUSTOMERS_LOGGED = "Get customers completes successfully.";

    private static final String ITEMS_LOGGED = "Get items completes successfully.";

    /** The original flow's spelling. */
    private static final String OPPORTUNITIES_LOGGED = "Get oportunities completes successfully.";

    private static final String RAMSEY = "Ramsey Inc. Wholesale Distribution V2011.1";

    private static final String RAMSEY_ELECTRONICS = "Ramsey Electronics";

    private static final Map<String, Object> STRING = Map.of("type", "string");

    private static final Map<String, Object> NUMBER = Map.of("type", "number", "format", "double");

    private static final Map<String, Object> INTEGER = Map.of("type", "integer", "format", "int64");

    private static final Map<String, Object> BOOLEAN = Map.of("type", "boolean");

    private static final Map<String, Object> DATE = Map.of("type", "string", "format", "date");

    private static final Map<String, Object> OBJECT = Map.of("type", "object");

    private static final Map<String, Object> RECORD_REF = Map.of("type", "object", "properties",
            Map.of("id", Map.of("type", "string"), "refName", Map.of("type", "string")));

    /** The select sources of the two customers' custom select fields, keyed as the client reports them. */
    private static final Map<String, String> CUSTOMER_SOURCE_IDS = Map.of("custentity7", "17", "custentity8", "16");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Mock
    private NetsuiteRestClient client;

    private NetsuiteSearchService service;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        NetsuiteRecordShapeMapper shape = new NetsuiteRecordShapeMapper(LOS_ANGELES);
        DwJsonWriter writer = new DwJsonWriter();
        service = new NetsuiteSearchService(client, new CustomerSearchMapper(shape, writer),
                new ItemSupplyPlanMapper(shape, writer), new OpportunitySearchMapper(shape, writer), shape);
        logger = (Logger) LoggerFactory.getLogger(NetsuiteSearchService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    // Search criteria scripts

    static Stream<Arguments> startsWithCriteria() {
        BiFunction<NetsuiteSearchService, String, Map<String, Object>> customer =
                NetsuiteSearchService::customerCriteria;
        BiFunction<NetsuiteSearchService, String, Map<String, Object>> opportunity =
                NetsuiteSearchService::opportunityCriteria;
        return Stream.of(
                Arguments.of(named("customerCriteria", customer), "companyName", "Ramsey"),
                Arguments.of(named("customerCriteria", customer), "companyName", ""),
                Arguments.of(named("opportunityCriteria", opportunity), "title", "Short wave"),
                Arguments.of(named("opportunityCriteria", opportunity), "title", ""));
    }

    /**
     * A present value, the empty one included, becomes the {@code STARTS_WITH} criterion under {@code basic}, with
     * {@code operator} before {@code searchValue} and a {@code LinkedHashMap} at every level.
     */
    @ParameterizedTest(name = "{0}(\"{2}\")")
    @MethodSource("startsWithCriteria")
    void startsWithCriteriaHoldTheCriterion(BiFunction<NetsuiteSearchService, String, Map<String, Object>> criteria,
                                            String field, String value) {
        Map<String, Object> built = criteria.apply(service, value);

        assertEquals("{basic={" + field + "={operator=STARTS_WITH, searchValue=" + value + "}}}", built.toString());
        assertLinkedHashMapsAtEveryLevel(built);
        Map<String, Object> basic = cast(built.get("basic"));
        Map<String, Object> condition = cast(basic.get(field));
        assertEquals(List.of("operator", "searchValue"), new ArrayList<>(condition.keySet()));
        assertSame(value, condition.get("searchValue"));
    }

    static Stream<Arguments> startsWithCriteriaMethods() {
        BiFunction<NetsuiteSearchService, String, Map<String, Object>> customer =
                NetsuiteSearchService::customerCriteria;
        BiFunction<NetsuiteSearchService, String, Map<String, Object>> opportunity =
                NetsuiteSearchService::opportunityCriteria;
        return Stream.of(
                Arguments.of(named("customerCriteria", customer)),
                Arguments.of(named("opportunityCriteria", opportunity)));
    }

    /** An absent value leaves {@code basic} present and empty, so the search returns every record. */
    @ParameterizedTest(name = "{0}(null)")
    @MethodSource("startsWithCriteriaMethods")
    void startsWithCriteriaWithoutValueHoldAnEmptyBasic(
            BiFunction<NetsuiteSearchService, String, Map<String, Object>> criteria) {
        Map<String, Object> built = criteria.apply(service, null);

        assertEquals("{basic={}}", built.toString());
        assertLinkedHashMapsAtEveryLevel(built);
    }

    /**
     * The item supply plan criterion: {@code quantity} holding the operator as given, unchecked here, and the
     * quantity as the {@code Integer} {@code as :number} produced.
     */
    @ParameterizedTest(name = "itemSupplyPlanCriteria({0}, {1})")
    @CsvSource({
        "0, GREATER_THAN",
        "25, LESS_THAN_OR_EQUAL_TO",
        "-3, EQUAL_TO",
        "7, BETWEEN"
    })
    void itemSupplyPlanCriteriaHoldTheQuantityCondition(int quantity, String operator) {
        Map<String, Object> built = service.itemSupplyPlanCriteria(quantity, operator);

        assertEquals("{quantity={operator=" + operator + ", searchValue=" + quantity + "}}", built.toString());
        assertLinkedHashMapsAtEveryLevel(built);
        Map<String, Object> condition = cast(built.get("quantity"));
        assertEquals(List.of("operator", "searchValue"), new ArrayList<>(condition.keySet()));
        assertEquals(Integer.valueOf(quantity), condition.get("searchValue"));
    }

    // SuiteQL ID queries

    static Stream<Arguments> idQueries() {
        Function<String, String> customer = NetsuiteSearchService::customerIdQuery;
        Function<String, String> opportunity = NetsuiteSearchService::opportunityIdQuery;
        return Stream.of(
                Arguments.of(named("customerIdQuery", customer), "Ramsey",
                        "SELECT id FROM customer WHERE companyname LIKE 'Ramsey%' ORDER BY id"),
                Arguments.of(named("customerIdQuery", customer), "O'Brien",
                        "SELECT id FROM customer WHERE companyname LIKE 'O''Brien%' ORDER BY id"),
                Arguments.of(named("customerIdQuery", customer), "x' OR '1'='1",
                        "SELECT id FROM customer WHERE companyname LIKE 'x'' OR ''1''=''1%' ORDER BY id"),
                Arguments.of(named("customerIdQuery", customer), "",
                        "SELECT id FROM customer WHERE companyname LIKE '%' ORDER BY id"),
                Arguments.of(named("customerIdQuery", customer), null,
                        "SELECT id FROM customer ORDER BY id"),
                Arguments.of(named("opportunityIdQuery", opportunity), "Short wave",
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE 'Short wave%' ORDER BY id"),
                Arguments.of(named("opportunityIdQuery", opportunity), "It's",
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE 'It''s%' ORDER BY id"),
                Arguments.of(named("opportunityIdQuery", opportunity), "",
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE '%' ORDER BY id"),
                Arguments.of(named("opportunityIdQuery", opportunity), null,
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' ORDER BY id"));
    }

    /**
     * The prefix sits inside the {@code LIKE} literal with each quote doubled, so no value can close the literal; an
     * absent prefix selects every record; both order by {@code id}.
     */
    @ParameterizedTest(name = "{0}(\"{1}\")")
    @MethodSource("idQueries")
    void idQueriesSelectMatchingIdsOrderedById(Function<String, String> query, String prefix, String expected) {
        assertEquals(expected, query.apply(prefix));
    }

    // get:/customers

    static Stream<Arguments> customerSearches() {
        return Stream.of(
                Arguments.of("Ramsey",
                        "SELECT id FROM customer WHERE companyname LIKE 'Ramsey%' ORDER BY id"),
                Arguments.of("", "SELECT id FROM customer WHERE companyname LIKE '%' ORDER BY id"),
                Arguments.of(null, "SELECT id FROM customer ORDER BY id"));
    }

    /**
     * The SuiteQL rows are read one record each, in row order, whatever the type of the row's {@code id}; the
     * customer catalog is read once, the source IDs of the custom select fields the records hold are resolved, and
     * the records are written through the customer mapper, so the MUnit {@code "companyName": "<name>"} holds.
     */
    @ParameterizedTest(name = "name = \"{0}\"")
    @MethodSource("customerSearches")
    void getCustomersReadsEachMatchedCustomerAndWritesThemInRowOrder(String name, String expectedQuery)
            throws Exception {
        when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "-5"), record("id", 1066)));
        when(client.getRecord("customer", "-5")).thenReturn(ramseyCustomer());
        when(client.getRecord("customer", "1066")).thenReturn(ramseyElectronicsCustomer());
        when(client.metadataCatalog("customer")).thenReturn(customerCatalog());
        when(client.customFieldSourceIds(Set.of("custentity7", "custentity8"))).thenReturn(CUSTOMER_SOURCE_IDS);

        String json = service.getCustomers(name);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        InOrder calls = inOrder(client);
        calls.verify(client).suiteQl(query.capture());
        calls.verify(client).getRecord("customer", "-5");
        calls.verify(client).getRecord("customer", "1066");
        calls.verify(client).metadataCatalog("customer");
        calls.verify(client).customFieldSourceIds(Set.of("custentity7", "custentity8"));
        verifyNoMoreInteractions(client);
        assertEquals(expectedQuery, query.getValue());

        String independent = new CustomerSearchMapper(new NetsuiteRecordShapeMapper(LOS_ANGELES), new DwJsonWriter())
                .toJson(List.of(ramseyCustomer(), ramseyElectronicsCustomer()), customerCatalog(), CUSTOMER_SOURCE_IDS);
        assertEquals(independent, json);
        List<Map<String, Object>> customers = parseArray(json);
        assertEquals(List.of("-5", "1066"), values(customers, "internalId"));
        assertEquals(List.of(RAMSEY, RAMSEY_ELECTRONICS), values(customers, "companyName"));
        assertEquals("16", selectTypeId(customers.get(0), "SelectCustomFieldRef__custentity8"));
        assertEquals("17", selectTypeId(customers.get(1), "SelectCustomFieldRef__custentity7"));
        assertTrue(json.contains("\"companyName\": \"" + RAMSEY + "\""), json);
        assertLoggedOnce(CUSTOMERS_LOGGED);
    }

    /**
     * A custom select field whose source ID the client did not resolve fails the request with the shape mapper's
     * exception naming the script ID; no {@code typeId} is guessed and no success is logged.
     */
    @Test
    void getCustomersFailsNamingAnUnresolvedSelectSource() {
        when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "-5")));
        when(client.getRecord("customer", "-5")).thenReturn(ramseyCustomer());
        when(client.metadataCatalog("customer")).thenReturn(customerCatalog());
        when(client.customFieldSourceIds(Set.of("custentity8"))).thenReturn(Map.of());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.getCustomers("Ramsey"));

        assertEquals("No select source (typeId) resolved for NetSuite custom field custentity8", thrown.getMessage());
        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    static Stream<Arguments> rowsWithoutId() {
        Function<NetsuiteSearchService, String> customers = search -> invoke(() -> search.getCustomers("Ramsey"));
        Function<NetsuiteSearchService, String> opportunities =
                search -> invoke(() -> search.getOpportunities("Short wave"));
        return Stream.of(
                Arguments.of(named("getCustomers", customers), named("row without id", record("companyname", "x"))),
                Arguments.of(named("getCustomers", customers), named("row with null id", record("id", null))),
                Arguments.of(named("getOpportunities", opportunities), named("row without id", record())),
                Arguments.of(named("getOpportunities", opportunities), named("row with null id", record("id", null))));
    }

    /** A SuiteQL row without an {@code id} fails the search before any record is read. */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("rowsWithoutId")
    void suiteQlRowWithoutIdFailsBeforeAnyRecordIsRead(Function<NetsuiteSearchService, String> search,
                                                       Map<String, Object> row) {
        when(client.suiteQl(anyString())).thenReturn(List.of(row));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> search.apply(service));

        assertEquals("SuiteQL row without id", thrown.getMessage());
        verifyNoMoreInteractions(client);
        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    // get:/items

    /**
     * Plan 9 has one order line of quantity 10 and no {@code units}; plan 18 has lines of 5 and 25 and
     * {@code units}. A plan is kept when any line satisfies {@code quantity <operator> searchValue}; every listed
     * plan is read, in listing order, whether it is kept or not.
     */
    @ParameterizedTest(name = "quantity {1} {0} keeps [{2}]")
    @CsvSource(delimiter = '|', textBlock = """
             0 | GREATER_THAN             | 9,18
            10 | GREATER_THAN             | 18
            25 | GREATER_THAN             | ''
            10 | EQUAL_TO                 | 9
            25 | EQUAL_TO                 | 18
             7 | EQUAL_TO                 | ''
             5 | LESS_THAN                | ''
             6 | LESS_THAN                | 18
            11 | LESS_THAN                | 9,18
             5 | LESS_THAN_OR_EQUAL_TO    | 18
             4 | LESS_THAN_OR_EQUAL_TO    | ''
            10 | LESS_THAN_OR_EQUAL_TO    | 9,18
            25 | GREATER_THAN_OR_EQUAL_TO | 18
            26 | GREATER_THAN_OR_EQUAL_TO | ''
            10 | GREATER_THAN_OR_EQUAL_TO | 9,18
            """)
    void getItemsKeepsPlansWithAnyOrderLineSatisfyingTheComparison(int quantity, String operator, String keptIds)
            throws Exception {
        when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("9", "18"));
        when(client.getRecord("itemsupplyplan", "9")).thenReturn(plan9());
        when(client.getRecord("itemsupplyplan", "18")).thenReturn(plan18());
        when(client.metadataCatalog("itemsupplyplan")).thenReturn(emptyCatalog());

        String json = service.getItems(quantity, operator);

        InOrder calls = inOrder(client);
        calls.verify(client).listRecordIds("itemsupplyplan");
        calls.verify(client).getRecord("itemsupplyplan", "9");
        calls.verify(client).getRecord("itemsupplyplan", "18");
        calls.verify(client).metadataCatalog("itemsupplyplan");
        verifyNoMoreInteractions(client);
        List<Map<String, Object>> plans = parseArray(json);
        assertEquals(keptIds.isEmpty() ? List.of() : List.of(keptIds.split(",")), values(plans, "internalId"));
        for (Map<String, Object> plan : plans) {
            assertEquals("18".equals(plan.get("internalId")), plan.containsKey("units"), json);
        }
        assertLoggedOnce(ITEMS_LOGGED);
    }

    /**
     * The kept plans are written through the item supply plan mapper: {@code units} only on the plan that sets it,
     * the {@code order} lines omitted.
     */
    @Test
    void getItemsWritesKeptPlansThroughTheItemSupplyPlanMapper() throws Exception {
        when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("9", "18"));
        when(client.getRecord("itemsupplyplan", "9")).thenReturn(plan9());
        when(client.getRecord("itemsupplyplan", "18")).thenReturn(plan18());
        when(client.metadataCatalog("itemsupplyplan")).thenReturn(emptyCatalog());

        String json = service.getItems(0, "GREATER_THAN");

        String independent = new ItemSupplyPlanMapper(new NetsuiteRecordShapeMapper(LOS_ANGELES), new DwJsonWriter())
                .toJson(List.of(plan9(), plan18()), emptyCatalog(), Map.of());
        assertEquals(independent, json);
        List<Map<String, Object>> plans = parseArray(json);
        assertEquals(List.of("internalId", "item", "location", "subsidiary"), new ArrayList<>(plans.get(0).keySet()));
        assertEquals(List.of("internalId", "item", "location", "units", "subsidiary"),
                new ArrayList<>(plans.get(1).keySet()));
        Map<String, Object> units = cast(plans.get(1).get("units"));
        assertEquals("Square Feet", units.get("name"));
        verify(client, never()).customFieldSourceIds(any());
        assertLoggedOnce(ITEMS_LOGGED);
    }

    static Stream<Arguments> plansWithoutQuantityLines() {
        Map<String, Object> line = record("links", List.of(), "line", 1, "orderDate", "2015-03-02");
        return Stream.of(
                Arguments.of(named("no order sublist", plan("77", null))),
                Arguments.of(named("order sublist without lines",
                        plan("77", record("links", List.of(), "items", List.of(), "totalResults", 0)))),
                Arguments.of(named("order sublist without items",
                        plan("77", record("links", List.of(), "totalResults", 0)))),
                Arguments.of(named("order that is not an object",
                        plan("77", List.of(record("quantity", 10))))),
                Arguments.of(named("order line that is not an object",
                        plan("77", record("links", List.of(), "items", List.of(10), "totalResults", 1)))),
                Arguments.of(named("order line without quantity",
                        plan("77", record("links", List.of(), "items", List.of(line), "totalResults", 1)))),
                Arguments.of(named("order line with null quantity",
                        plan("77", record("links", List.of(), "items", List.of(record("quantity", null)),
                                "totalResults", 1)))));
    }

    /**
     * A plan with no order line holding a quantity is never kept, even by a comparison every quantity satisfies,
     * while a plan listed beside it is still compared and kept.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("plansWithoutQuantityLines")
    void getItemsDropsPlansWithoutAQuantityLine(Map<String, Object> plan) throws Exception {
        when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("77", "9"));
        when(client.getRecord("itemsupplyplan", "77")).thenReturn(plan);
        when(client.getRecord("itemsupplyplan", "9")).thenReturn(plan9());
        when(client.metadataCatalog("itemsupplyplan")).thenReturn(emptyCatalog());

        String json = service.getItems(0, "GREATER_THAN_OR_EQUAL_TO");

        assertEquals(List.of("9"), values(parseArray(json), "internalId"));
        assertLoggedOnce(ITEMS_LOGGED);
    }

    static Stream<Arguments> lineQuantities() {
        return Stream.of(
                Arguments.of(10, 10, "EQUAL_TO", true),
                Arguments.of(10L, 10, "EQUAL_TO", true),
                Arguments.of(BigInteger.TEN, 10, "EQUAL_TO", true),
                Arguments.of(new BigDecimal("10.0"), 10, "EQUAL_TO", true),
                Arguments.of(new BigDecimal("10.000"), 10, "EQUAL_TO", true),
                Arguments.of(10.0d, 10, "EQUAL_TO", true),
                Arguments.of("10", 10, "EQUAL_TO", true),
                Arguments.of(new BigDecimal("10.5"), 10, "EQUAL_TO", false),
                Arguments.of(new BigDecimal("10.5"), 10, "GREATER_THAN", true),
                Arguments.of(new BigDecimal("9.99"), 10, "LESS_THAN", true),
                Arguments.of(new BigDecimal("9.99"), 10, "GREATER_THAN_OR_EQUAL_TO", false),
                Arguments.of(new BigDecimal("-1.0"), 0, "LESS_THAN", true),
                Arguments.of(new BigDecimal("-1.0"), -1, "EQUAL_TO", true));
    }

    /** Line quantities compare by numeric value whatever their parsed form, {@code 10.0} equal to {@code 10}. */
    @ParameterizedTest(name = "line {0} {2} {1} kept = {3}")
    @MethodSource("lineQuantities")
    void getItemsComparesLineQuantitiesByValue(Object lineQuantity, int quantity, String operator, boolean kept)
            throws Exception {
        when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("30"));
        when(client.getRecord("itemsupplyplan", "30")).thenReturn(plan("30", orderLines(lineQuantity)));
        when(client.metadataCatalog("itemsupplyplan")).thenReturn(emptyCatalog());

        String json = service.getItems(quantity, operator);

        assertEquals(kept ? List.of("30") : List.of(), values(parseArray(json), "internalId"));
        assertLoggedOnce(ITEMS_LOGGED);
    }

    /** An operator outside the RAML enum fails before NetSuite is called; the names are case-sensitive. */
    @ParameterizedTest(name = "operator = \"{0}\"")
    @NullSource
    @ValueSource(strings = {"BETWEEN", "greater_than", "", " GREATER_THAN"})
    void getItemsRejectsAnUnknownOperatorBeforeCallingNetsuite(String operator) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.getItems(0, operator));

        assertEquals("Unsupported operator: " + operator, thrown.getMessage());
        verifyNoInteractions(client);
        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    // get:/oportunities

    static Stream<Arguments> opportunitySearches() {
        return Stream.of(
                Arguments.of("Short wave",
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE 'Short wave%' ORDER BY id"),
                Arguments.of("Short wave' OR 'a'='a",
                        "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE "
                                + "'Short wave'' OR ''a''=''a%' ORDER BY id"),
                Arguments.of(null, "SELECT id FROM transaction WHERE type = 'Opprtnty' ORDER BY id"));
    }

    /**
     * The matched opportunity is read, the opportunity catalog read once, no source lookup made for a record without
     * custom select fields, and the record written through the opportunity mapper.
     */
    @ParameterizedTest(name = "title = \"{0}\"")
    @MethodSource("opportunitySearches")
    void getOpportunitiesReadsTheMatchedOpportunityAndWritesItThroughTheOpportunityMapper(String title,
                                                                                         String expectedQuery)
            throws Exception {
        when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "1271")));
        when(client.getRecord("opportunity", "1271")).thenReturn(shortWaveOpportunity());
        when(client.metadataCatalog("opportunity")).thenReturn(opportunityCatalog());

        String json = service.getOpportunities(title);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        InOrder calls = inOrder(client);
        calls.verify(client).suiteQl(query.capture());
        calls.verify(client).getRecord("opportunity", "1271");
        calls.verify(client).metadataCatalog("opportunity");
        verifyNoMoreInteractions(client);
        assertEquals(expectedQuery, query.getValue());

        String independent =
                new OpportunitySearchMapper(new NetsuiteRecordShapeMapper(LOS_ANGELES), new DwJsonWriter())
                        .toJson(List.of(shortWaveOpportunity()), opportunityCatalog(), Map.of());
        assertEquals(independent, json);
        assertEquals(List.of("1271"), values(parseArray(json), "internalId"));
        assertTrue(json.contains("\"title\": \"Short wave\""), json);
        assertTrue(json.contains("\"status\": \"Issued Quote\""), json);
        assertLoggedOnce(OPPORTUNITIES_LOGGED);
    }

    // Committed responses, from the REST fixtures of src/test/resources/netsuite

    /**
     * REST customer -5 answers the committed customer byte for byte: the SuiteQL row's record is read, the sources
     * of its four custom select fields are looked up in one call and their raw {@code fieldvaluetyperecord} values
     * become the {@code typeId}s {@code 16}, {@code 17}, {@code -30} and {@code 15}, so the MUnit
     * {@code "companyName": "<name>"} assertion holds.
     */
    @Test
    void getCustomersAnswersTheCommittedCustomerFromTheRestFixtures() throws Exception {
        Set<String> selectScriptIds =
                Set.of("custentity6", "custentity7", "custentity8", "custentity_fmt_cur_sales_order");
        when(client.suiteQl(anyString())).thenReturn(rows("rest-suiteql-customer-ids.json"));
        when(client.getRecord("customer", "-5")).thenReturn(fixture("rest-customer.json"));
        when(client.metadataCatalog("customer")).thenReturn(fixture("rest-customer-catalog.json"));
        when(client.customFieldSourceIds(selectScriptIds)).thenReturn(sourceIds());

        String json = service.getCustomers(RAMSEY);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        InOrder calls = inOrder(client);
        calls.verify(client).suiteQl(query.capture());
        calls.verify(client).getRecord("customer", "-5");
        calls.verify(client).metadataCatalog("customer");
        calls.verify(client).customFieldSourceIds(selectScriptIds);
        verifyNoMoreInteractions(client);
        assertEquals("SELECT id FROM customer WHERE companyname LIKE '" + RAMSEY + "%' ORDER BY id", query.getValue());
        assertEquals(resource("api/customers-response.json"), json);
        assertTrue(json.contains("\"companyName\": \"" + RAMSEY + "\""), json);
        assertLoggedOnce(CUSTOMERS_LOGGED);
    }

    /**
     * The listed REST plans 9 and 18, searched with the RAML defaults {@code quantity} 0 and {@code GREATER_THAN},
     * answer the committed plans byte for byte: {@code units} only on plan 18, the {@code order} lines omitted.
     */
    @Test
    void getItemsAnswersTheCommittedPlansFromTheRestFixtures() throws Exception {
        when(client.listRecordIds("itemsupplyplan")).thenReturn(listedIds("rest-itemsupplyplan-list.json"));
        when(client.getRecord("itemsupplyplan", "9")).thenReturn(fixture("rest-itemsupplyplan-9.json"));
        when(client.getRecord("itemsupplyplan", "18")).thenReturn(fixture("rest-itemsupplyplan-18.json"));
        when(client.metadataCatalog("itemsupplyplan")).thenReturn(fixture("rest-itemsupplyplan-catalog.json"));

        String json = service.getItems(0, "GREATER_THAN");

        InOrder calls = inOrder(client);
        calls.verify(client).listRecordIds("itemsupplyplan");
        calls.verify(client).getRecord("itemsupplyplan", "9");
        calls.verify(client).getRecord("itemsupplyplan", "18");
        calls.verify(client).metadataCatalog("itemsupplyplan");
        verifyNoMoreInteractions(client);
        assertEquals(resource("api/items-response.json"), json);
        assertLoggedOnce(ITEMS_LOGGED);
    }

    /**
     * REST opportunity 1271, which holds no custom field and so needs no source lookup, answers the committed
     * opportunity. The committed file is not valid JSON: each {@code addrText} holds raw line breaks that a
     * pretty-printer re-indented, while the writer escapes them as {@code \n}. Those two values are therefore
     * compared with the REST {@code addrText}, and every other byte with the committed file.
     */
    @Test
    void getOpportunitiesAnswersTheCommittedOpportunityFromTheRestFixtures() throws Exception {
        when(client.suiteQl(anyString())).thenReturn(rows("rest-suiteql-opportunity-ids.json"));
        when(client.getRecord("opportunity", "1271")).thenReturn(fixture("rest-opportunity.json"));
        when(client.metadataCatalog("opportunity")).thenReturn(fixture("rest-opportunity-catalog.json"));

        String json = service.getOpportunities("Short wave");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        InOrder calls = inOrder(client);
        calls.verify(client).suiteQl(query.capture());
        calls.verify(client).getRecord("opportunity", "1271");
        calls.verify(client).metadataCatalog("opportunity");
        verifyNoMoreInteractions(client);
        assertEquals("SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE 'Short wave%' ORDER BY id",
                query.getValue());

        String independent =
                new OpportunitySearchMapper(new NetsuiteRecordShapeMapper(LOS_ANGELES), new DwJsonWriter())
                        .toJson(List.of(fixture("rest-opportunity.json")), fixture("rest-opportunity-catalog.json"),
                                Map.of());
        assertEquals(independent, json);
        assertEquals(withoutAddrText(resource("api/opportunities-response.json")), withoutAddrText(json));
        List<Map<String, Object>> opportunities = parseArray(json);
        assertEquals(1, opportunities.size(), json);
        Map<String, Object> opportunity = opportunities.get(0);
        assertEquals("1271", opportunity.get("internalId"));
        assertEquals("Short wave", opportunity.get("title"));
        Map<String, Object> restRecord = fixture("rest-opportunity.json");
        for (String address : List.of("billingAddress", "shippingAddress")) {
            Map<String, Object> restAddress = cast(restRecord.get(address));
            Map<String, Object> written = cast(opportunity.get(address));
            assertEquals(restAddress.get("addrText"), written.get("addrText"), address);
        }
        assertTrue(json.contains("\"title\": \"Short wave\""), json);
        assertLoggedOnce(OPPORTUNITIES_LOGGED);
    }

    // Every flow

    /** The three flows, each with a search that matches nothing. */
    enum Flow {
        CUSTOMERS("customer", CUSTOMERS_LOGGED) {
            @Override
            void arrangeNoMatch(NetsuiteRestClient client) {
                when(client.suiteQl(anyString())).thenReturn(List.of());
            }

            @Override
            String invoke(NetsuiteSearchService service) throws Exception {
                return service.getCustomers("Nobody");
            }
        },
        ITEMS("itemsupplyplan", ITEMS_LOGGED) {
            @Override
            void arrangeNoMatch(NetsuiteRestClient client) {
                when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of());
            }

            @Override
            String invoke(NetsuiteSearchService service) throws Exception {
                return service.getItems(0, "GREATER_THAN");
            }
        },
        OPPORTUNITIES("opportunity", OPPORTUNITIES_LOGGED) {
            @Override
            void arrangeNoMatch(NetsuiteRestClient client) {
                when(client.suiteQl(anyString())).thenReturn(List.of());
            }

            @Override
            String invoke(NetsuiteSearchService service) throws Exception {
                return service.getOpportunities("Nobody");
            }
        };

        private final String recordType;

        private final String logged;

        Flow(String recordType, String logged) {
            this.recordType = recordType;
            this.logged = logged;
        }

        abstract void arrangeNoMatch(NetsuiteRestClient client);

        abstract String invoke(NetsuiteSearchService service) throws Exception;
    }

    /**
     * A search matching nothing answers {@code []}, reads no record and looks up no source, and still logs its
     * success line once.
     */
    @ParameterizedTest
    @EnumSource(Flow.class)
    void searchMatchingNothingAnswersAnEmptyArray(Flow flow) throws Exception {
        flow.arrangeNoMatch(client);
        when(client.metadataCatalog(flow.recordType)).thenReturn(emptyCatalog());

        String json = flow.invoke(service);

        assertEquals("[]", json);
        verify(client, never()).getRecord(anyString(), anyString());
        verify(client, never()).customFieldSourceIds(any());
        assertLoggedOnce(flow.logged);
    }

    /** Each client call of each flow, failing with the arranged exception after the calls before it succeeded. */
    enum FailingCall {
        CUSTOMERS_SUITEQL {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenThrow(failure);
            }
        },
        CUSTOMERS_GET_RECORD {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "-5")));
                when(client.getRecord("customer", "-5")).thenThrow(failure);
            }
        },
        CUSTOMERS_METADATA_CATALOG {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "-5")));
                when(client.getRecord("customer", "-5")).thenReturn(ramseyCustomer());
                when(client.metadataCatalog("customer")).thenThrow(failure);
            }
        },
        CUSTOMERS_CUSTOM_FIELD_SOURCE_IDS {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "-5")));
                when(client.getRecord("customer", "-5")).thenReturn(ramseyCustomer());
                when(client.metadataCatalog("customer")).thenReturn(customerCatalog());
                when(client.customFieldSourceIds(Set.of("custentity8"))).thenThrow(failure);
            }
        },
        ITEMS_LIST_RECORD_IDS {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.listRecordIds("itemsupplyplan")).thenThrow(failure);
            }
        },
        ITEMS_GET_RECORD {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("9"));
                when(client.getRecord("itemsupplyplan", "9")).thenThrow(failure);
            }
        },
        ITEMS_METADATA_CATALOG {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.listRecordIds("itemsupplyplan")).thenReturn(List.of("9"));
                when(client.getRecord("itemsupplyplan", "9")).thenReturn(plan9());
                when(client.metadataCatalog("itemsupplyplan")).thenThrow(failure);
            }
        },
        OPPORTUNITIES_SUITEQL {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenThrow(failure);
            }
        },
        OPPORTUNITIES_GET_RECORD {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "1271")));
                when(client.getRecord("opportunity", "1271")).thenThrow(failure);
            }
        },
        OPPORTUNITIES_METADATA_CATALOG {
            @Override
            void arrange(NetsuiteRestClient client, RuntimeException failure) {
                when(client.suiteQl(anyString())).thenReturn(List.of(record("id", "1271")));
                when(client.getRecord("opportunity", "1271")).thenReturn(shortWaveOpportunity());
                when(client.metadataCatalog("opportunity")).thenThrow(failure);
            }
        };

        abstract void arrange(NetsuiteRestClient client, RuntimeException failure);

        String invoke(NetsuiteSearchService service) throws Exception {
            return switch (this) {
                case CUSTOMERS_SUITEQL, CUSTOMERS_GET_RECORD, CUSTOMERS_METADATA_CATALOG,
                     CUSTOMERS_CUSTOM_FIELD_SOURCE_IDS -> service.getCustomers("Ramsey");
                case ITEMS_LIST_RECORD_IDS, ITEMS_GET_RECORD, ITEMS_METADATA_CATALOG ->
                        service.getItems(0, "GREATER_THAN");
                case OPPORTUNITIES_SUITEQL, OPPORTUNITIES_GET_RECORD, OPPORTUNITIES_METADATA_CATALOG ->
                        service.getOpportunities("Short wave");
            };
        }
    }

    static Stream<Arguments> failingCalls() {
        return Stream.of(FailingCall.values()).flatMap(call -> Stream.<RuntimeException>of(
                        new UpstreamUnavailableException(UpstreamSystem.NETSUITE, "Connection refused",
                                new ConnectException("Connection refused")),
                        new UpstreamAuthenticationException(UpstreamSystem.NETSUITE, "Invalid login attempt.",
                                new IllegalStateException("401 Unauthorized")),
                        new UpstreamRateLimitException(UpstreamSystem.NETSUITE, "Request limit exceeded", "30",
                                new IllegalStateException("429 Too Many Requests")),
                        new IllegalStateException("Unparsable NetSuite response"))
                .map(failure -> Arguments.of(call, failure)));
    }

    /**
     * The service catches nothing: the SC-9 upstream exceptions, which {@code UpstreamExceptionHandler} answers, and
     * any other client failure reach the caller as the same instance, no later call is made and no success is logged.
     */
    @ParameterizedTest(name = "{0} throws {1}")
    @MethodSource("failingCalls")
    void clientFailurePropagatesUnchanged(FailingCall call, RuntimeException failure) {
        call.arrange(client, failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> call.invoke(service));

        assertSame(failure, thrown);
        verifyNoMoreInteractions(client);
        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    // Fixtures

    /** The customer catalog: the body fields and the custom fields of the two customers, typed as REST types them. */
    private static Map<String, Object> customerCatalog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("companyName", STRING);
        properties.put("entityId", STRING);
        properties.put("refName", STRING);
        properties.put("subsidiary", RECORD_REF);
        properties.put("custentity7", RECORD_REF);
        properties.put("custentity8", RECORD_REF);
        properties.put("custentity18", BOOLEAN);
        return catalog(properties);
    }

    /** Customer -5: a custom select ({@code custentity8}) and a custom boolean. */
    private static Map<String, Object> ramseyCustomer() {
        return record(
                "links", self("customer", "-5"),
                "id", "-5",
                "refName", "Alex Wolfe",
                "companyName", RAMSEY,
                "entityId", "Alex Wolfe",
                "subsidiary", ref("1", "Honeycomb Mfg."),
                "custentity8", ref("3", "Less than $5 million"),
                "custentity18", false);
    }

    /** Customer 1066: a different custom select ({@code custentity7}) and a custom boolean. */
    private static Map<String, Object> ramseyElectronicsCustomer() {
        return record(
                "links", self("customer", "1066"),
                "id", "1066",
                "refName", RAMSEY_ELECTRONICS,
                "companyName", RAMSEY_ELECTRONICS,
                "entityId", RAMSEY_ELECTRONICS,
                "subsidiary", ref("1", "Honeycomb Mfg."),
                "custentity7", ref("1", "1 - 99 Employees"),
                "custentity18", true);
    }

    /** The opportunity catalog: every field of {@link #shortWaveOpportunity()}, typed as REST types it. */
    private static Map<String, Object> opportunityCatalog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", STRING);
        properties.put("tranId", STRING);
        properties.put("refName", STRING);
        properties.put("probability", NUMBER);
        properties.put("daysOpen", INTEGER);
        properties.put("expectedCloseDate", DATE);
        properties.put("entity", RECORD_REF);
        properties.put("status", OBJECT);
        properties.put("billingAddress", OBJECT);
        properties.put("item", OBJECT);
        return catalog(properties);
    }

    /** Opportunity 1271 with its status, a billing address and an {@code item} sublist, no custom field. */
    private static Map<String, Object> shortWaveOpportunity() {
        return record(
                "links", self("opportunity", "1271"),
                "id", "1271",
                "refName", "Opportunity #OPP00000044",
                "tranId", "OPP00000044",
                "title", "Short wave",
                "probability", new BigDecimal("50.0"),
                "daysOpen", 349,
                "expectedCloseDate", "2014-12-05",
                "entity", ref("1066", "Williams Electronics and Communications"),
                "status", record("id", "issuedEstimate", "refName", "Issued Quote"),
                "billingAddress", record(
                        "links", List.of(),
                        "addr1", "123 Main St.",
                        "addressee", "Williams Electronics and Communications",
                        "city", "San Mate",
                        "country", "US",
                        "state", "CA",
                        "zip", "94002"),
                "item", record(
                        "links", List.of(),
                        "items", List.of(record("links", List.of(), "line", 1, "quantity", new BigDecimal("1.0"))),
                        "totalResults", 1));
    }

    /** Plan 9: one order line of quantity 10, no {@code units}. */
    private static Map<String, Object> plan9() {
        return record(
                "links", self("itemsupplyplan", "9"),
                "id", "9",
                "memo", "Spring build",
                "item", ref("702", "CUS00001"),
                "location", ref("1", "02: Boston"),
                "subsidiary", ref("1", "Honeycomb Mfg."),
                "order", orderLines(10));
    }

    /** Plan 18: order lines of quantity 5.0 and 25.0, with {@code units}. */
    private static Map<String, Object> plan18() {
        return record(
                "links", self("itemsupplyplan", "18"),
                "id", "18",
                "item", ref("721", "SHT00001"),
                "location", ref("1", "02: Boston"),
                "units", ref("15", "Square Feet"),
                "subsidiary", ref("1", "Honeycomb Mfg."),
                "order", orderLines(new BigDecimal("5.0"), new BigDecimal("25.0")));
    }

    /** A bare plan with the given {@code order} value, or no {@code order} key when it is {@code null}. */
    private static Map<String, Object> plan(String id, Object order) {
        Map<String, Object> plan = record("links", self("itemsupplyplan", id), "id", id);
        if (order != null) {
            plan.put("order", order);
        }
        return plan;
    }

    /** An expanded {@code order} sublist with one line per quantity. */
    private static Map<String, Object> orderLines(Object... quantities) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            lines.add(record("links", List.of(), "line", i + 1, "orderDate", "2015-03-02", "quantity",
                    quantities[i]));
        }
        return record("links", List.of(), "items", lines, "totalResults", lines.size());
    }

    private static Map<String, Object> emptyCatalog() {
        return catalog(new LinkedHashMap<>());
    }

    private static Map<String, Object> catalog(Map<String, Object> properties) {
        return record("type", "object", "properties", properties);
    }

    private static Map<String, Object> ref(String id, String refName) {
        return record("links", List.of(), "id", id, "refName", refName);
    }

    private static List<Map<String, Object>> self(String type, String id) {
        return List.of(record("rel", "self", "href",
                "https://123456.suitetalk.api.netsuite.com/services/rest/record/v1/" + type + "/" + id));
    }

    /** A {@code LinkedHashMap} of alternating keys and values; unlike {@link Map#of} it accepts null values. */
    private static Map<String, Object> record(Object... keysAndValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            record.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return record;
    }

    /**
     * A REST fixture of {@code src/test/resources/netsuite} parsed as the client parses NetSuite bodies
     * ({@code LinkedHashMap}s, decimals as {@code BigDecimal}), a new copy on every call.
     */
    private static Map<String, Object> fixture(String file) {
        String path = "netsuite/" + file;
        try {
            return JSON.readValue(resource(path), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Fixture " + path + " is not a JSON object", e);
        }
    }

    /**
     * The {@code items} of a SuiteQL or record-list page fixture, each without its {@code links}, as the client
     * returns them.
     */
    private static List<Map<String, Object>> rows(String file) {
        List<Map<String, Object>> items = cast(fixture(file).get("items"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.remove("links");
            rows.add(row);
        }
        return rows;
    }

    /** The {@code id}s of a record-list page fixture as text, in listing order, as {@code listRecordIds} returns. */
    private static List<String> listedIds(String file) {
        List<String> ids = new ArrayList<>();
        rows(file).forEach(row -> ids.add(String.valueOf(row.get("id"))));
        return ids;
    }

    /**
     * The {@code customfield} lookup fixture as {@code customFieldSourceIds} returns it: lower-cased script ID → the
     * raw {@code fieldvaluetyperecord}.
     */
    private static Map<String, String> sourceIds() {
        Map<String, String> sourceIds = new LinkedHashMap<>();
        for (Map<String, Object> row : rows("rest-suiteql-customfield.json")) {
            sourceIds.put(String.valueOf(row.get("scriptid")).toLowerCase(Locale.ROOT),
                    String.valueOf(row.get("fieldvaluetyperecord")));
        }
        return sourceIds;
    }

    // Assertions and helpers

    /** Exactly one event was logged by the service: the given INFO line. */
    private void assertLoggedOnce(String message) {
        assertEquals(1, appender.list.size(), appender.list::toString);
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(NetsuiteSearchService.class.getName(), event.getLoggerName());
        assertEquals(message, event.getFormattedMessage());
    }

    private static void assertLinkedHashMapsAtEveryLevel(Map<String, Object> map) {
        assertEquals(LinkedHashMap.class, map.getClass(), map::toString);
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> nested) {
                assertLinkedHashMapsAtEveryLevel(cast(nested));
            }
        }
    }

    /** The {@code typeId} of a select inside the single {@code customFieldList.customField} element. */
    private static String selectTypeId(Map<String, Object> customer, String key) {
        Map<String, Object> customFieldList = cast(customer.get("customFieldList"));
        List<Map<String, Object>> customField = cast(customFieldList.get("customField"));
        Map<String, Object> select = cast(customField.get(0).get(key));
        return (String) select.get("typeId");
    }

    private static List<Object> values(List<Map<String, Object>> elements, String key) {
        List<Object> values = new ArrayList<>();
        elements.forEach(element -> values.add(element.get(key)));
        return values;
    }

    private static List<Map<String, Object>> parseArray(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Service output does not parse as a JSON array: " + json, e);
        }
    }

    /** A UTF-8 classpath resource, from the main or the test resources. */
    private static String resource(String path) {
        try (InputStream in = NetsuiteSearchServiceTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + path + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text with every {@code addrText} string value emptied, raw line breaks inside the value included. */
    private static String withoutAddrText(String json) {
        return json.replaceAll("\"addrText\": \"(?:[^\"\\\\]|\\\\.)*\"", "\"addrText\": \"\"");
    }

    /** Runs a search whose checked exception the test does not expect to see, so it can serve as a function. */
    private static String invoke(Search search) {
        try {
            return search.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected checked exception", e);
        }
    }

    @FunctionalInterface
    private interface Search {

        String run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
