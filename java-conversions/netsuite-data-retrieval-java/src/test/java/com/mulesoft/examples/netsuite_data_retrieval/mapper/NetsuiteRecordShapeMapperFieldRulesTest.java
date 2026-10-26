/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper.RecordKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test of the field rules of {@link NetsuiteRecordShapeMapper}: the SuiteTalk record shape rebuilt from a
 * NetSuite REST record (AAP 0.7.4). REST records and metadata-catalog schemas are built in memory; the committed
 * examples {@code api/customers-response.json}, {@code items-response.json} and {@code opportunities-response.json}
 * are the parity targets of the three whole-record cases, written through {@link DwJsonWriter} as the controllers
 * write them. Plain JUnit 5 and AssertJ: no Spring context and no network.
 */
class NetsuiteRecordShapeMapperFieldRulesTest {

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static final NetsuiteRecordShapeMapper MAPPER = new NetsuiteRecordShapeMapper(LOS_ANGELES);

    private static final DwJsonWriter WRITER = new DwJsonWriter();

    /**
     * Reads JSON into {@code LinkedHashMap}s with decimals as {@code BigDecimal}. Unescaped control characters are
     * accepted because the committed {@code opportunities-response.json} carries raw line feeds inside
     * {@code addrText}.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private static final Map<String, Object> STRING = Map.of("type", "string");

    private static final Map<String, Object> NUMBER = Map.of("type", "number", "format", "double");

    private static final Map<String, Object> INTEGER = Map.of("type", "integer", "format", "int64");

    private static final Map<String, Object> BOOLEAN = Map.of("type", "boolean");

    private static final Map<String, Object> DATE = Map.of("type", "string", "format", "date");

    private static final Map<String, Object> DATE_TIME = Map.of("type", "string", "format", "date-time");

    private static final Map<String, Object> OBJECT = Map.of("type", "object");

    private static final Map<String, Object> ARRAY = Map.of("type", "array");

    private static final Map<String, Object> RECORD_REF = Map.of("type", "object", "properties",
            Map.of("id", Map.of("type", "string"), "refName", Map.of("type", "string")));

    private static final Map<String, Object> SELECT_REF =
            Map.of("$ref", "/services/rest/record/v1/metadata-catalog/salesorder");

    private static final Map<String, Object> MULTI_SELECT_ARRAY = Map.of("type", "array", "items", RECORD_REF);

    private static final Map<String, Object> MULTI_SELECT_OBJECT = Map.of("type", "object", "properties",
            Map.of("items", Map.of("type", "array", "items", RECORD_REF)));

    /** The committed key order of a SuiteTalk {@code Address} {@code [opportunities-response.json:billingAddress]}. */
    private static final List<String> ADDRESS_KEYS = List.of("zip", "country", "addr2", "addr1", "city", "addr3",
            "addrText", "addrPhone", "internalId", "addressee", "attention", "state", "override", "nullFieldList",
            "customFieldList");

    /** The select sources of the committed customer {@code [customers-response.json:51-75]}. */
    private static final Map<String, String> CUSTOMER_SOURCE_IDS = Map.of(
            "custentity8", "16",
            "custentity7", "17",
            "custentity6", "15",
            "custentity_fmt_cur_sales_order", "-30");

    /**
     * The REST customer behind the committed example, its fields in alphabetical order rather than the committed
     * one, with the root {@code links} and {@code refName}, an {@code addressBook} sublist, a null field and an
     * undeclared field ({@code balance}) that the shape must drop.
     */
    private static final String REST_CUSTOMER = """
            {
              "links": [{"rel": "self", "href": "/services/rest/record/v1/customer/-5"}],
              "accessRole": {"links": [], "id": "14", "refName": "Customer Center"},
              "addressBook": {"links": [], "items": [{"links": [], "id": "1"}], "totalResults": 1},
              "aging": 0.0,
              "aging1": 0.0,
              "aging2": 0.0,
              "aging3": 0.0,
              "aging4": -32.3,
              "altEmail": "awolfe@huffmanmfg.com",
              "balance": 12.5,
              "billPay": false,
              "clickStream": "HOME",
              "comments": null,
              "companyName": "Ramsey Inc. Wholesale Distribution V2011.1",
              "consolAging": 0.0,
              "consolAging1": 0.0,
              "consolAging2": 0.0,
              "consolAging3": 0.0,
              "consolAging4": -32.3,
              "consolBalance": -32.3,
              "consolDepositBalance": 0.0,
              "consolOverdueBalance": 0.0,
              "consolUnbilledOrders": 49.98,
              "creditHoldOverride": {"id": "AUTO", "refName": "Auto"},
              "currency": {"links": [], "id": "1", "refName": "USA"},
              "custentity18": false,
              "custentity6": {"links": [], "id": "4", "refName": "Computers, Peripherals, and Software"},
              "custentity7": {"links": [], "id": "1", "refName": "1 - 99 Employees"},
              "custentity8": {"links": [], "id": "3", "refName": "Less than $5 million"},
              "custentity_2663_customer_refund": false,
              "custentity_2663_direct_debit": false,
              "custentity_cust_priority": 50,
              "custentity_fmt_cur_sales_order": {"links": [], "id": "2570", "refName": "Sales Order #SLS00000452"},
              "custentity_fmt_cust_credit_on_hold": false,
              "custentity_fmt_customer_credit_on_hold": false,
              "dateCreated": "2013-07-22T07:00:00Z",
              "defaultAddress": "US",
              "email": "suchi.deshpande@mulesoft.com",
              "entityId": "Alex Wolfe",
              "entityStatus": {"links": [], "id": "13", "refName": "CUSTOMER-Closed Won"},
              "externalId": "entity-5",
              "firstName": "Alex",
              "firstVisit": "2015-01-02T16:03:20Z",
              "giveAccess": true,
              "id": "-5",
              "isInactive": false,
              "isPerson": true,
              "lastModifiedDate": "2015-01-23T04:10:20Z",
              "lastName": "Wolfe",
              "lastPageVisited": "Shopping Cart",
              "lastVisit": "2016-02-24T19:41:36Z",
              "phone": "650-555-9788",
              "receivablesAccount": {"links": [], "id": "-10", "refName": "Use System Preference"},
              "refName": "Alex Wolfe",
              "shipComplete": false,
              "stage": {"id": "CUSTOMER", "refName": "Customer"},
              "startDate": "2015-04-19",
              "subsidiary": {"links": [], "id": "1", "refName": "Honeycomb Mfg."},
              "taxable": false,
              "unbilledOrders": 49.98,
              "visits": 31
            }
            """;

    /** The committed billing {@code addrText}, line feeds and indentation included. */
    private static final String BILLING_ADDR_TEXT = "Williams Electronics and Communications\n"
            + "        <br>123 Main St.\n"
            + "          <br>Suite 700\n"
            + "            <br>San Mate CA 94002\n"
            + "              <br>United States";

    /** The committed shipping {@code addrText}, line feeds and indentation included. */
    private static final String SHIPPING_ADDR_TEXT = "Williams Electronics and Communications\n"
            + "<br>123 Main St.\n"
            + "  <br>Suite 700\n"
            + "    <br>San Mate CA 94002\n"
            + "      <br>United States";

    /**
     * The REST opportunity behind the committed example, fields in alphabetical order, with an {@code item}
     * sublist, a null body field and a null custom field. The shipping address carries its country as
     * {@code {id, refName}}, the billing address as the bare code; {@link #restOpportunity()} adds the
     * {@code addrText} values.
     */
    private static final String REST_OPPORTUNITY = """
            {
              "links": [{"rel": "self", "href": "/services/rest/record/v1/opportunity/1271"}],
              "billingAddress": {
                "links": [],
                "addr1": "123 Main St.",
                "addr2": "Suite 700",
                "addressee": "Williams Electronics and Communications",
                "city": "San Mate",
                "country": "US",
                "state": "CA",
                "zip": "94002"
              },
              "createdDate": "2015-05-03T17:55:10Z",
              "currency": {"links": [], "id": "1", "refName": "USA"},
              "currencyName": "USA",
              "custbody_unset": null,
              "daysOpen": 349,
              "entity": {"links": [], "id": "1066", "refName": "Williams Electronics and Communications"},
              "entityStatus": {"links": [], "id": "10", "refName": "Proposal"},
              "estGrossProfit": 3875.85,
              "estGrossProfitPercent": 100.0,
              "exchangeRate": 1.0,
              "expectedCloseDate": "2014-12-05",
              "forecastType": {"id": "0", "refName": "Omitted"},
              "id": "1271",
              "isBudgetApproved": false,
              "item": {"links": [], "items": [{"links": [], "line": 1, "quantity": 1.0}], "totalResults": 1},
              "lastModifiedDate": "2015-01-01T20:13:44Z",
              "leadSource": {"links": [], "id": "100003", "refName": "Electronics Retail Trade Show"},
              "location": {"links": [], "id": "2", "refName": "01: San Francisco"},
              "memo": null,
              "probability": 50.0,
              "projectedTotal": 3875.85,
              "refName": "Opportunity #OPP00000044",
              "salesRep": {"links": [], "id": "23", "refName": "Clark Koozer"},
              "shipIsResidential": false,
              "shippingAddress": {
                "links": [],
                "id": "2475",
                "addr1": "123 Main St.",
                "addr2": "Suite 700",
                "addressee": "Williams Electronics and Communications",
                "city": "San Mate",
                "country": {"id": "US", "refName": "United States"},
                "override": false,
                "state": "CA",
                "zip": "94002"
              },
              "status": {"id": "issuedEstimate", "refName": "Issued Quote"},
              "subsidiary": {"links": [], "id": "1", "refName": "Honeycomb Mfg."},
              "title": "Short wave",
              "totalCostEstimate": 0.0,
              "tranDate": "2015-04-03",
              "tranId": "OPP00000044",
              "weightedTotal": 1937.93
            }
            """;

    /**
     * The two REST item supply plans behind the committed example, with body references in a non-committed
     * order, a body field outside the four references and the {@code order} sublist; only the second sets
     * {@code units}.
     */
    private static final String REST_ITEM_SUPPLY_PLANS = """
            [
              {
                "links": [{"rel": "self", "href": "/services/rest/record/v1/itemsupplyplan/9"}],
                "id": "9",
                "memo": "Spring build",
                "subsidiary": {"links": [], "id": "1", "refName": "Honeycomb Mfg."},
                "location": {"links": [], "id": "1", "refName": "02: Boston"},
                "item": {"links": [], "id": "702", "refName": "CUS00001"},
                "order": {"links": [], "items": [{"links": [], "orderDate": "2015-03-02", "quantity": 12.0}],
                          "totalResults": 1}
              },
              {
                "links": [{"rel": "self", "href": "/services/rest/record/v1/itemsupplyplan/18"}],
                "id": "18",
                "subsidiary": {"links": [], "id": "1", "refName": "Honeycomb Mfg."},
                "units": {"links": [], "id": "15", "refName": "Square Feet"},
                "location": {"links": [], "id": "1", "refName": "02: Boston"},
                "item": {"links": [], "id": "721", "refName": "SHT00001"},
                "order": {"links": [], "items": [{"links": [], "orderDate": "2015-03-09", "quantity": 4.0}],
                          "totalResults": 1}
              }
            ]
            """;

    // ---------------------------------------------------------------------------------------------------------
    // Whole records against the committed examples
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The committed customer, byte for byte: catalog-declared fields only, references, enumerations, dates in
     * the account zone, integral and decimal numbers, and the ten custom fields with the four select
     * {@code typeId}s, every object in its committed key order. The REST record is left unchanged.
     */
    @Test
    void customerReproducesCommittedExampleBytes() {
        Map<String, Object> rest = parseObject(REST_CUSTOMER);

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, customerCatalog(),
                CUSTOMER_SOURCE_IDS);

        assertThat(mapped).isExactlyInstanceOf(HashMap.class);
        assertThat(WRITER.write(List.of(mapped))).isEqualTo(resource("api/customers-response.json"));
        assertThat(rest).isEqualTo(parseObject(REST_CUSTOMER));
    }

    /**
     * The committed opportunity as a JSON tree with key order: the status text, both 15-key addresses with
     * nulls kept and {@code US} → {@code UNITED_STATES}, and no {@code item} sublist or custom field list. The
     * committed file holds raw line feeds inside {@code addrText}, so it is compared after both sides pass through
     * {@link DwJsonWriter}, which escapes them alike.
     */
    @Test
    void opportunityReproducesCommittedExampleTree() {
        Map<String, Object> rest = restOpportunity();

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.OPPORTUNITY, rest, opportunityCatalog(), Map.of());

        List<Object> committed = parse(resource("api/opportunities-response.json"), new TypeReference<>() { });
        assertThat(mapped).isExactlyInstanceOf(HashMap.class).containsEntry("status", "Issued Quote");
        assertThat(WRITER.write(List.of(mapped))).isEqualTo(WRITER.write(committed));
        assertThat(rest).isEqualTo(restOpportunity());
    }

    /**
     * The committed item supply plans, byte for byte: {@code internalId}, {@code item}, {@code location},
     * {@code units} only on the plan that sets it, {@code subsidiary}; the {@code order} lines and other body
     * fields omitted. A {@code null} catalog and {@code null} source IDs read as empty.
     */
    @Test
    void itemSupplyPlansReproduceCommittedExampleBytes() {
        List<Map<String, Object>> plans = parse(REST_ITEM_SUPPLY_PLANS, new TypeReference<>() { });

        Map<String, Object> first = MAPPER.toSuiteTalk(RecordKind.ITEM_SUPPLY_PLAN, plans.get(0), null, null);
        Map<String, Object> second = MAPPER.toSuiteTalk(RecordKind.ITEM_SUPPLY_PLAN, plans.get(1), null, null);

        assertThat(first.keySet()).containsExactly("internalId", "item", "location", "subsidiary");
        assertThat(second.keySet()).containsExactly("internalId", "item", "location", "units", "subsidiary");
        assertThat(WRITER.write(List.of(first, second))).isEqualTo(resource("api/items-response.json"));
    }

    /** A null {@code id} or reference of an item supply plan produces no key. */
    @Test
    void itemSupplyPlanOmitsNullFields() {
        Map<String, Object> rest = record(
                "id", null,
                "item", record("links", List.of(), "id", "702", "refName", "CUS00001"),
                "location", null,
                "units", null);

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.ITEM_SUPPLY_PLAN, rest, null, null);

        assertThat(mapped).containsOnlyKeys("item");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Body fields
    // ---------------------------------------------------------------------------------------------------------

    static Stream<Arguments> restIds() {
        return Stream.of(RecordKind.values()).flatMap(kind -> Stream.of(
                Arguments.of(kind, "1271"),
                Arguments.of(kind, 1271)));
    }

    /** REST {@code id} becomes the {@code internalId} text; root {@code links} and {@code refName} are dropped. */
    @ParameterizedTest
    @MethodSource("restIds")
    void restIdBecomesInternalIdTextAndRootLinksAndRefNameAreDropped(RecordKind kind, Object id) {
        Map<String, Object> rest = record(
                "links", List.of(Map.of("rel", "self", "href", "/services/rest/record/v1/x/1271")),
                "refName", "Short wave",
                "id", id);
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), STRING, "links", "refName"));

        assertThat(MAPPER.toSuiteTalk(kind, rest, catalog, Map.of())).isEqualTo(Map.of("internalId", "1271"));
    }

    /**
     * A null field produces no key, even when declared; a field the catalog does not declare is dropped; a null
     * custom field leaves no {@code customFieldList}.
     */
    @Test
    void nullAndUndeclaredFieldsProduceNoKey() {
        Map<String, Object> rest = record(
                "id", "7",
                "companyName", "Acme",
                "comments", null,
                "balance", new BigDecimal("12.5"),
                "custentity_flag", null);
        Map<String, Object> properties = declare(new LinkedHashMap<>(), STRING, "companyName", "comments");
        declare(properties, BOOLEAN, "custentity_flag");

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties), Map.of());

        assertThat(mapped).isEqualTo(Map.of("internalId", "7", "companyName", "Acme"));
    }

    /**
     * A {@code null} catalog declares nothing, so only {@code internalId} and the custom fields, typed from their
     * values, remain; {@code null} source IDs read as empty.
     */
    @Test
    void nullCatalogDeclaresNoBodyField() {
        Map<String, Object> rest = record("id", 7, "companyName", "Acme", "custentity_flag", true);

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, null, null);

        assertThat(mapped).isEqualTo(Map.of(
                "internalId", "7",
                "customFieldList", Map.of("customField", List.of(Map.of("BooleanCustomFieldRef__custentity_flag",
                        "true")))));
    }

    static Stream<Arguments> numbers() {
        return Stream.of(
                Arguments.of(new BigDecimal("100.0"), BigInteger.valueOf(100)),
                Arguments.of(new BigDecimal("-32.3"), new BigDecimal("-32.3")),
                Arguments.of(new BigDecimal("49.980"), new BigDecimal("49.98")),
                Arguments.of(new BigDecimal("0.0"), BigInteger.ZERO),
                Arguments.of(31, BigInteger.valueOf(31)),
                Arguments.of(9_007_199_254_740_993L, BigInteger.valueOf(9_007_199_254_740_993L)),
                Arguments.of(new BigInteger("123456789012345678901234567890"),
                        new BigInteger("123456789012345678901234567890")));
    }

    /** Numbers lose trailing zeros: {@code BigInteger} when integral, otherwise {@code BigDecimal}. */
    @ParameterizedTest
    @MethodSource("numbers")
    void numbersKeepIntegerOrDecimalForm(Object restValue, Object expected) {
        Map<String, Object> rest = record("id", "1", "consolBalance", restValue);
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), NUMBER, "consolBalance"));

        Object value = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, Map.of()).get("consolBalance");

        assertThat(value).isExactlyInstanceOf(expected.getClass()).isEqualTo(expected);
    }

    /** Booleans stay native and strings pass through, a date-like string without a date format included. */
    @Test
    void booleansStayNativeAndStringsPassThrough() {
        Map<String, Object> rest = record(
                "id", "1",
                "isPerson", true,
                "taxable", false,
                "phone", "650-555-9788",
                "comments", "2015-04-19");
        Map<String, Object> properties = declare(new LinkedHashMap<>(), BOOLEAN, "isPerson", "taxable");
        declare(properties, STRING, "phone", "comments");

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties), Map.of());

        assertThat(mapped)
                .containsEntry("isPerson", Boolean.TRUE)
                .containsEntry("taxable", Boolean.FALSE)
                .containsEntry("phone", "650-555-9788")
                .containsEntry("comments", "2015-04-19");
    }

    /**
     * A {@code format: date} value is midnight in the account zone; a {@code format: date-time} value is the same
     * instant in that zone, to the second; a {@code format: date} value carrying a time reads as a date-time.
     */
    @ParameterizedTest
    @CsvSource({
            "date,      2015-04-19,               2015-04-19T00:00:00-07:00",
            "date,      2014-12-05,               2014-12-05T00:00:00-08:00",
            "date,      2013-07-22T07:00:00Z,     2013-07-22T00:00:00-07:00",
            "date-time, 2015-01-23T04:10:20Z,     2015-01-22T20:10:20-08:00",
            "date-time, 2015-05-03T17:55:10.640Z, 2015-05-03T10:55:10-07:00",
            "date-time, 2015-01-23T13:10:20+09:00, 2015-01-22T20:10:20-08:00"
    })
    void datesRenderInTheAccountZone(String format, String restValue, String expected) {
        Map<String, Object> rest = record("id", "1", "startDate", restValue);
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(),
                Map.of("type", "string", "format", format), "startDate"));

        assertThat(MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, Map.of()))
                .containsEntry("startDate", expected);
    }

    /** {@code {id, refName}} becomes {@code {externalId: null, type: null, internalId, name}} in that order. */
    @Test
    void referenceIsWrittenInCommittedKeyOrder() {
        Map<String, Object> rest = record(
                "id", "1",
                "subsidiary", record("links", List.of(), "id", 1, "refName", "Honeycomb Mfg."));
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), RECORD_REF, "subsidiary"));

        Object reference = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, Map.of()).get("subsidiary");

        assertThat(reference).isExactlyInstanceOf(LinkedHashMap.class);
        assertThat(WRITER.write(reference)).isEqualTo("""
                {
                  "externalId": null,
                  "type": null,
                  "internalId": "1",
                  "name": "Honeycomb Mfg."
                }""");
    }

    /** The named customer enumerations become SuiteTalk constant names, whatever the catalog says. */
    @ParameterizedTest
    @CsvSource({
            "stage,              CUSTOMER, CUSTOMER",
            "stage,              _customer, CUSTOMER",
            "creditHoldOverride, AUTO,     AUTO",
            "creditHoldOverride, onHold,   ON_HOLD"
    })
    void namedCustomerEnumerationsBecomeConstantNames(String field, String id, String expected) {
        Map<String, Object> rest = record("id", "1", field, record("id", id, "refName", "Label"));
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), RECORD_REF, field));

        assertThat(MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, Map.of())).containsEntry(field, expected);
    }

    static Stream<Arguments> enumerationFallbacks() {
        Map<String, Object> fieldEnum = Map.of("type", "object", "enum", List.of("1", "2", "3"));
        Map<String, Object> idEnum = Map.of("type", "object", "properties",
                Map.of("id", Map.of("type", "string", "enum", List.of("1", "2", "3"))));
        return Stream.of(RecordKind.CUSTOMER, RecordKind.OPPORTUNITY).flatMap(kind -> Stream.of(
                Arguments.of(kind, fieldEnum, "2", "2"),
                Arguments.of(kind, idEnum, "3", "3"),
                Arguments.of(kind, OBJECT, "_default", "DEFAULT"),
                Arguments.of(kind, OBJECT, "level2Approved", "LEVEL2_APPROVED")));
    }

    /**
     * Beyond the named fields, an object is an enumeration when the catalog carries an {@code enum} keyword on the
     * field or on its {@code id}, or when its {@code id} is not an integer literal.
     */
    @ParameterizedTest
    @MethodSource("enumerationFallbacks")
    void enumerationFallbacksBecomeConstantNames(RecordKind kind, Map<String, Object> schema, String id,
                                                 String expected) {
        Map<String, Object> rest = record("id", "1", "emailPreference", record("id", id, "refName", "Label"));
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), schema, "emailPreference"));

        assertThat(MAPPER.toSuiteTalk(kind, rest, catalog, Map.of())).containsEntry("emailPreference", expected);
    }

    /**
     * Sublists (maps holding {@code items}, with or without an {@code id}), subrecords (maps without {@code id})
     * and lists produce no key; outside the opportunity, an address subrecord is such a subrecord.
     */
    @Test
    void sublistsSubrecordsAndListsAreOmitted() {
        Map<String, Object> rest = record(
                "id", "1",
                "addressBook", record("links", List.of(), "items", List.of(record("id", "1")), "totalResults", 1),
                "contactRoles", record("links", List.of(), "id", "1", "items", List.of(), "totalResults", 0),
                "billingAddress", record("links", List.of(), "addr1", "123 Main St.", "country", "US"),
                "groupPricing", List.of(record("id", "1", "refName", "Level 1")));
        Map<String, Object> properties = declare(new LinkedHashMap<>(), OBJECT, "addressBook", "contactRoles",
                "billingAddress");
        declare(properties, ARRAY, "groupPricing");

        assertThat(MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties), Map.of()))
                .isEqualTo(Map.of("internalId", "1"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Opportunity addresses
    // ---------------------------------------------------------------------------------------------------------

    /** An opportunity address is the fixed 15-key object in committed order, absent values kept as null. */
    @ParameterizedTest
    @CsvSource({"billingAddress", "shippingAddress"})
    void opportunityAddressHasFifteenKeysWithNullsKept(String field) {
        Map<String, Object> rest = record("id", "1271", field, record("links", List.of()));

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.OPPORTUNITY, rest, null, Map.of());

        Map<String, Object> address = cast(mapped.get(field));
        assertThat(address).isExactlyInstanceOf(HashMap.class);
        assertThat(address.keySet()).containsExactlyElementsOf(ADDRESS_KEYS);
        assertThat(address.values()).containsOnlyNulls();
    }

    static Stream<Arguments> countries() {
        return Stream.of(
                Arguments.of("US", "UNITED_STATES"),
                Arguments.of(record("id", "US", "refName", "United States"), "UNITED_STATES"),
                Arguments.of(record("id", "CA", "refName", "Canada"), "CANADA"),
                Arguments.of("GB", "UNITED_KINGDOM"));
    }

    /** The address country, as a code or as {@code {id, refName}}, is translated through the country table. */
    @ParameterizedTest
    @MethodSource("countries")
    void addressCountryIsTranslatedThroughCountryTable(Object country, String expected) {
        Map<String, Object> rest = record("id", "1271", "shippingAddress", record("country", country));

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.OPPORTUNITY, rest, null, Map.of());

        Map<String, Object> address = cast(mapped.get("shippingAddress"));
        assertThat(address).containsEntry("country", expected);
    }

    /** A country code missing from the table fails, naming the code; the REST record is left unchanged. */
    @Test
    void unmappedCountryCodeFails() {
        Map<String, Object> rest = record("id", "1271", "billingAddress", record("country", "ZZ"));
        Object before = deepCopy(rest);

        assertThatThrownBy(() -> MAPPER.toSuiteTalk(RecordKind.OPPORTUNITY, rest, null, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZZ");
        assertThat(rest).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Custom fields
    // ---------------------------------------------------------------------------------------------------------

    static Stream<Arguments> customScalars() {
        return Stream.of(
                Arguments.of(BOOLEAN, false, "BooleanCustomFieldRef__custentity_x", "false"),
                Arguments.of(INTEGER, 50, "LongCustomFieldRef__custentity_x", "50"),
                Arguments.of(INTEGER, new BigDecimal("50.0"), "LongCustomFieldRef__custentity_x", "50"),
                Arguments.of(INTEGER, "12", "LongCustomFieldRef__custentity_x", "12"),
                Arguments.of(NUMBER, new BigDecimal("2.5"), "DoubleCustomFieldRef__custentity_x", "2.5"),
                Arguments.of(NUMBER, 3, "DoubleCustomFieldRef__custentity_x", "3.0"),
                Arguments.of(NUMBER, "1.5", "DoubleCustomFieldRef__custentity_x", "1.5"),
                Arguments.of(STRING, "Gold", "StringCustomFieldRef__custentity_x", "Gold"),
                Arguments.of(DATE, "2015-04-19", "DateCustomFieldRef__custentity_x", "2015-04-19T00:00:00-07:00"),
                Arguments.of(DATE_TIME, "2015-01-23T04:10:20Z", "DateCustomFieldRef__custentity_x",
                        "2015-01-22T20:10:20-08:00"),
                Arguments.of(null, true, "BooleanCustomFieldRef__custentity_x", "true"),
                Arguments.of(null, 7, "LongCustomFieldRef__custentity_x", "7"),
                Arguments.of(null, new BigDecimal("7.0"), "LongCustomFieldRef__custentity_x", "7"),
                Arguments.of(null, new BigDecimal("1.25"), "DoubleCustomFieldRef__custentity_x", "1.25"),
                Arguments.of(null, "text", "StringCustomFieldRef__custentity_x", "text"));
    }

    /**
     * A scalar custom field is keyed {@code <Type>CustomFieldRef__<scriptId>}, the type from the catalog or, when
     * the catalog lacks the field, from the value, and its value is written as text.
     */
    @ParameterizedTest
    @MethodSource("customScalars")
    void customScalarIsKeyedByTypeAndWrittenAsText(Map<String, Object> schema, Object value, String key,
                                                   String text) {
        Map<String, Object> rest = record("id", "1", "custentity_x", value);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (schema != null) {
            declare(properties, schema, "custentity_x");
        }

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties), Map.of());

        assertThat(customFields(mapped)).containsExactly(Map.<String, Object>of(key, text));
    }

    static Stream<Arguments> selectSchemas() {
        return Stream.of(Arguments.of(RECORD_REF), Arguments.of(SELECT_REF), Arguments.of((Object) null));
    }

    /**
     * A select custom field, declared as an object, as a {@code $ref} or inferred from a map value, is written
     * {@code {internalId, name, externalId: null, typeId}} with the {@code typeId} from the source IDs.
     */
    @ParameterizedTest
    @MethodSource("selectSchemas")
    void selectIsWrittenWithSourceTypeId(Map<String, Object> schema) {
        Map<String, Object> rest = record("id", "-5",
                "custentity8", record("links", List.of(), "id", "3", "refName", "Less than $5 million"));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (schema != null) {
            declare(properties, schema, "custentity8");
        }

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties),
                CUSTOMER_SOURCE_IDS);

        Map<String, Object> element = customFields(mapped).get(0);
        assertThat(element).containsOnlyKeys("SelectCustomFieldRef__custentity8");
        assertThat(WRITER.write(element.get("SelectCustomFieldRef__custentity8"))).isEqualTo("""
                {
                  "internalId": "3",
                  "name": "Less than $5 million",
                  "externalId": null,
                  "typeId": "16"
                }""");
    }

    static Stream<Arguments> multiSelects() {
        List<Object> values = List.of(
                record("links", List.of(), "id", "1", "refName", "East"),
                record("links", List.of(), "id", "2", "refName", "West"));
        Map<String, Object> sublist = record("links", List.of(), "items", values, "totalResults", 2);
        return Stream.of(
                Arguments.of(MULTI_SELECT_ARRAY, values),
                Arguments.of(MULTI_SELECT_OBJECT, sublist),
                Arguments.of(null, values),
                Arguments.of(null, sublist));
    }

    /** A multi-select custom field is written as the list of its select values, each with the source typeId. */
    @ParameterizedTest
    @MethodSource("multiSelects")
    void multiSelectIsWrittenAsListOfSelects(Map<String, Object> schema, Object value) {
        Map<String, Object> rest = record("id", "1", "custentity_regions", value);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (schema != null) {
            declare(properties, schema, "custentity_regions");
        }

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(properties),
                Map.of("custentity_regions", "42"));

        Map<String, Object> element = customFields(mapped).get(0);
        assertThat(element).containsOnlyKeys("MultiSelectCustomFieldRef__custentity_regions");
        assertThat(WRITER.write(element.get("MultiSelectCustomFieldRef__custentity_regions"))).isEqualTo("""
                [
                  {
                    "internalId": "1",
                    "name": "East",
                    "externalId": null,
                    "typeId": "42"
                  },
                  {
                    "internalId": "2",
                    "name": "West",
                    "externalId": null,
                    "typeId": "42"
                  }
                ]""");
    }

    static Stream<Arguments> sourceIdLookups() {
        return Stream.of(
                Arguments.of(Map.of("custentityAbc", "22", "custentityabc", "21"), "22"),
                Arguments.of(Map.of("custentityabc", "21"), "21"));
    }

    /** The source ID is looked up by the exact script ID first, then by its lower-case form. */
    @ParameterizedTest
    @MethodSource("sourceIdLookups")
    void sourceIdIsLookedUpExactlyThenLowerCased(Map<String, String> sourceIds, String typeId) {
        Map<String, Object> rest = record("id", "1", "custentityAbc", record("id", "5", "refName", "Five"));
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), RECORD_REF, "custentityAbc"));

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, sourceIds);

        Map<String, Object> select = cast(customFields(mapped).get(0).get("SelectCustomFieldRef__custentityAbc"));
        assertThat(select).containsEntry("typeId", typeId);
    }

    static Stream<Arguments> unresolvedSources() {
        Map<String, Object> select = record("links", List.of(), "id", "3", "refName", "Less than $5 million");
        Map<String, String> blank = new HashMap<>();
        blank.put("custentity8", "  ");
        return Stream.of(
                Arguments.of(RECORD_REF, select, null),
                Arguments.of(RECORD_REF, select, Map.of()),
                Arguments.of(RECORD_REF, select, blank),
                Arguments.of(RECORD_REF, select, Map.of("custentity7", "17")),
                Arguments.of(MULTI_SELECT_ARRAY, List.of(select), Map.of("custentity7", "17")));
    }

    /**
     * A select or multi-select whose source ID is absent or blank fails with {@link IllegalStateException} naming
     * the script ID, and the REST record is left unchanged.
     */
    @ParameterizedTest
    @MethodSource("unresolvedSources")
    void unresolvedSelectSourceFailsNamingScriptId(Map<String, Object> schema, Object value,
                                                   Map<String, String> sourceIds) {
        Map<String, Object> rest = record("id", "-5", "custentity8", value);
        Object before = deepCopy(rest);
        Map<String, Object> catalog = catalog(declare(new LinkedHashMap<>(), schema, "custentity8"));

        assertThatThrownBy(() -> MAPPER.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog, sourceIds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custentity8");
        assertThat(rest).isEqualTo(before);
    }

    /**
     * Opportunity {@code custbody*} fields form the custom field list too; a custom field outside the committed
     * ones keeps its key.
     */
    @Test
    void opportunityCustomBodyFieldsFormCustomFieldList() {
        Map<String, Object> rest = record(
                "id", "1271",
                "custbody_approved", true,
                "custbody_source_order", record("id", "2570", "refName", "Sales Order #SLS00000452"));
        Map<String, Object> properties = declare(new LinkedHashMap<>(), BOOLEAN, "custbody_approved");
        declare(properties, SELECT_REF, "custbody_source_order");

        Map<String, Object> mapped = MAPPER.toSuiteTalk(RecordKind.OPPORTUNITY, rest, catalog(properties),
                Map.of("custbody_source_order", "-30"));

        assertThat(mapped).containsOnlyKeys("internalId", "customFieldList");
        Map<String, Object> element = customFields(mapped).get(0);
        assertThat(element)
                .containsOnlyKeys("BooleanCustomFieldRef__custbody_approved",
                        "SelectCustomFieldRef__custbody_source_order")
                .containsEntry("BooleanCustomFieldRef__custbody_approved", "true");
        Map<String, Object> select = cast(element.get("SelectCustomFieldRef__custbody_source_order"));
        assertThat(select).containsEntry("internalId", "2570").containsEntry("typeId", "-30");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Select script IDs
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The select and multi-select custom script IDs that hold a value in at least one record, sorted; scalar
     * custom fields, null-only fields and body fields are left out.
     */
    @Test
    void selectCustomFieldScriptIdsListsSortedSelectsHoldingValue() {
        Map<String, Object> first = record(
                "id", "1",
                "companyName", "Acme",
                "custentity8", record("id", "3", "refName", "Less than $5 million"),
                "custentity_multi", List.of(record("id", "1", "refName", "East")),
                "custentity_flag", false,
                "custentity_unset", null,
                "custbody_x", null);
        Map<String, Object> second = record(
                "id", "2",
                "custentity6", record("id", "4", "refName", "Computers, Peripherals, and Software"),
                "custentity_unset", null,
                "custbody_x", record("id", "9", "refName", "Nine"));
        Map<String, Object> properties = declare(new LinkedHashMap<>(), RECORD_REF,
                "custentity8", "custentity_unset", "custbody_x");
        declare(properties, STRING, "companyName");
        declare(properties, SELECT_REF, "custentity6");
        declare(properties, MULTI_SELECT_ARRAY, "custentity_multi");
        declare(properties, BOOLEAN, "custentity_flag");

        assertThat(MAPPER.selectCustomFieldScriptIds(List.of(first, second), catalog(properties)))
                .containsExactly("custbody_x", "custentity6", "custentity8", "custentity_multi");
    }

    /** {@code null} records read as empty; a {@code null} catalog leaves the types to the values. */
    @Test
    void selectCustomFieldScriptIdsReadsNullInputsAsEmpty() {
        Map<String, Object> rest = record(
                "custentity_tags", List.of(record("id", "1", "refName", "One")),
                "custentity_size", record("id", "2", "refName", "Large"),
                "custentity_count", 5);

        assertThat(MAPPER.selectCustomFieldScriptIds(null, catalog(new LinkedHashMap<>()))).isEmpty();
        assertThat(MAPPER.selectCustomFieldScriptIds(List.of(rest), null))
                .containsExactly("custentity_size", "custentity_tags");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Account time zone
    // ---------------------------------------------------------------------------------------------------------

    /** The Spring constructor renders dates in {@code netsuite.time-zone}, {@code America/Los_Angeles} by default. */
    @ParameterizedTest
    @CsvSource(value = {
            "NONE,       2015-04-19T00:00:00-07:00, 2015-01-22T20:10:20-08:00",
            "Asia/Tokyo, 2015-04-19T00:00:00+09:00, 2015-01-23T13:10:20+09:00",
            "UTC,        2015-04-19T00:00:00+00:00, 2015-01-23T04:10:20+00:00"
    })
    void propertiesConstructorUsesConfiguredTimeZone(String zone, String date, String dateTime) {
        NetsuiteProperties properties = new NetsuiteProperties();
        if (!"NONE".equals(zone)) {
            properties.setTimeZone(ZoneId.of(zone));
        }
        NetsuiteRecordShapeMapper mapper = new NetsuiteRecordShapeMapper(properties);
        Map<String, Object> rest = record("id", "1", "startDate", "2015-04-19",
                "lastModifiedDate", "2015-01-23T04:10:20Z");
        Map<String, Object> catalogProperties = declare(new LinkedHashMap<>(), DATE, "startDate");
        declare(catalogProperties, DATE_TIME, "lastModifiedDate");

        Map<String, Object> mapped = mapper.toSuiteTalk(RecordKind.CUSTOMER, rest, catalog(catalogProperties),
                Map.of());

        assertThat(mapped).containsEntry("startDate", date).containsEntry("lastModifiedDate", dateTime);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Catalogs and helpers
    // ---------------------------------------------------------------------------------------------------------

    /** The customer catalog: every field of {@link #REST_CUSTOMER} but {@code balance}, typed as REST types it. */
    private static Map<String, Object> customerCatalog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        declare(properties, STRING, "altEmail", "clickStream", "comments", "companyName", "defaultAddress", "email",
                "entityId", "externalId", "firstName", "lastName", "lastPageVisited", "phone", "refName");
        declare(properties, NUMBER, "aging", "aging1", "aging2", "aging3", "aging4", "consolAging", "consolAging1",
                "consolAging2", "consolAging3", "consolAging4", "consolBalance", "consolDepositBalance",
                "consolOverdueBalance", "consolUnbilledOrders", "unbilledOrders");
        declare(properties, INTEGER, "visits", "custentity_cust_priority");
        declare(properties, BOOLEAN, "billPay", "giveAccess", "isInactive", "isPerson", "shipComplete", "taxable",
                "custentity18", "custentity_2663_customer_refund", "custentity_2663_direct_debit",
                "custentity_fmt_cust_credit_on_hold", "custentity_fmt_customer_credit_on_hold");
        declare(properties, DATE_TIME, "dateCreated", "firstVisit", "lastModifiedDate", "lastVisit");
        declare(properties, DATE, "startDate");
        declare(properties, RECORD_REF, "accessRole", "currency", "entityStatus", "receivablesAccount", "subsidiary",
                "custentity6", "custentity7", "custentity8");
        declare(properties, OBJECT, "addressBook", "creditHoldOverride", "stage");
        declare(properties, SELECT_REF, "custentity_fmt_cur_sales_order");
        return catalog(properties);
    }

    /** The opportunity catalog: every field of {@link #REST_OPPORTUNITY}, typed as REST types it. */
    private static Map<String, Object> opportunityCatalog() {
        Map<String, Object> properties = new LinkedHashMap<>();
        declare(properties, STRING, "currencyName", "memo", "refName", "title", "tranId");
        declare(properties, NUMBER, "estGrossProfit", "estGrossProfitPercent", "exchangeRate", "probability",
                "projectedTotal", "totalCostEstimate", "weightedTotal");
        declare(properties, INTEGER, "daysOpen");
        declare(properties, BOOLEAN, "isBudgetApproved", "shipIsResidential", "custbody_unset");
        declare(properties, DATE, "expectedCloseDate", "tranDate");
        declare(properties, DATE_TIME, "createdDate", "lastModifiedDate");
        declare(properties, RECORD_REF, "currency", "entity", "entityStatus", "forecastType", "leadSource",
                "location", "salesRep", "subsidiary");
        declare(properties, OBJECT, "billingAddress", "item", "shippingAddress", "status");
        return catalog(properties);
    }

    /** {@link #REST_OPPORTUNITY} with the committed {@code addrText} of each address. */
    private static Map<String, Object> restOpportunity() {
        Map<String, Object> rest = parseObject(REST_OPPORTUNITY);
        Map<String, Object> billing = cast(rest.get("billingAddress"));
        billing.put("addrText", BILLING_ADDR_TEXT);
        Map<String, Object> shipping = cast(rest.get("shippingAddress"));
        shipping.put("addrText", SHIPPING_ADDR_TEXT);
        return rest;
    }

    private static Map<String, Object> declare(Map<String, Object> properties, Map<String, Object> schema,
                                               String... fields) {
        for (String field : fields) {
            properties.put(field, schema);
        }
        return properties;
    }

    private static Map<String, Object> catalog(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    /** A {@code LinkedHashMap} of alternating keys and values; unlike {@link Map#of} it accepts null values. */
    private static Map<String, Object> record(Object... keysAndValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            record.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return record;
    }

    /** A structural copy of maps and lists, so a later comparison detects any change made to the original. */
    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, deepCopy(entry)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        }
        return value;
    }

    /** The single {@code customFieldList.customField} element list of a mapped record. */
    private static List<Map<String, Object>> customFields(Map<String, Object> mapped) {
        Map<String, Object> customFieldList = cast(mapped.get("customFieldList"));
        return cast(customFieldList.get("customField"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static Map<String, Object> parseObject(String json) {
        return parse(json, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static <T> T parse(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Test JSON does not parse", e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = NetsuiteRecordShapeMapperFieldRulesTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + path + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
