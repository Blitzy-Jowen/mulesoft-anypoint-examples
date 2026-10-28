/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteFailureClassifier;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteTokenProvider;
import com.mulesoft.examples.netsuite_data_retrieval.client.UpstreamCallExecutor;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper.RecordKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit test of {@link NetsuiteRecordShapeMapper} over the REST fixtures {@code src/test/resources/netsuite/rest-*.json}
 * (AAP 0.5.2): the decisive record-shape cases of AAP 0.7.4 that rebuild, from NetSuite REST records, the SuiteTalk
 * records the Mule connector returned to {@code payload map $} ({@code netsuite-api.xml} l.44, 70, 99). The parity
 * targets are the committed examples {@code api/customers-response.json}, {@code api/items-response.json} and
 * {@code api/opportunities-response.json}, read and never modified.
 *
 * <p>Plain JUnit 5 and AssertJ with no Spring context. The one test that needs the SC-16 {@code customfield} lookup
 * runs the real {@link NetsuiteRestClient}, {@link NetsuiteTokenProvider}, SC-9 {@link UpstreamCallExecutor} and
 * {@link NetsuiteFailureClassifier} against an in-process WireMock server, so no test reaches a live host or needs a
 * credential (AAP 0.8.3).
 */
class NetsuiteRecordShapeMapperTest {

    /**
     * Parses as SC-16 does: objects as {@code LinkedHashMap}, integers as {@code Integer}/{@code Long}, decimals as
     * {@code BigDecimal}.
     */
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** Reads the committed opportunity, whose {@code addrText} values hold raw line feeds. */
    private static final JsonMapper LENIENT_JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    private static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    /** The select sources of the committed customer {@code [customers-response.json:51-75]}. */
    private static final Map<String, String> COMMITTED_SOURCE_IDS = Map.of(
            "custentity8", "16",
            "custentity7", "17",
            "custentity_fmt_cur_sales_order", "-30",
            "custentity6", "15");

    private static final List<String> BOOLEAN_CUSTOM_FIELDS = List.of(
            "custentity_fmt_customer_credit_on_hold",
            "custentity_fmt_cust_credit_on_hold",
            "custentity_2663_direct_debit",
            "custentity18",
            "custentity_2663_customer_refund");

    /** The committed key order of a SuiteTalk {@code RecordRef}. */
    private static final List<String> REFERENCE_KEYS = List.of("externalId", "type", "internalId", "name");

    /** The committed key order of a SuiteTalk {@code Address} {@code [opportunities-response.json:shippingAddress]}. */
    private static final List<String> ADDRESS_KEYS = List.of("zip", "country", "addr2", "addr1", "city", "addr3",
            "addrText", "addrPhone", "internalId", "addressee", "attention", "state", "override", "nullFieldList",
            "customFieldList");

    private static final List<String> ADDRESS_FIELDS = List.of("shippingAddress", "billingAddress");

    /**
     * Leaf equality of a tree comparison: numbers are equal when they have the same value and the same integral or
     * decimal form, everything else by {@link JsonNode#equals(Object)}. {@code valueToTree} turns the mapper's
     * {@code BigInteger} into a {@code BigIntegerNode} while {@code readTree} reads the committed {@code 0} as an
     * {@code IntNode}, and the two node classes never compare equal by themselves.
     */
    private static final Comparator<JsonNode> SAME_VALUE_AND_FORM = (left, right) -> {
        if (left.isNumber() && right.isNumber()) {
            return left.isIntegralNumber() == right.isIntegralNumber()
                    && left.decimalValue().compareTo(right.decimalValue()) == 0 ? 0 : 1;
        }
        return left.equals(right) ? 0 : 1;
    };

    private NetsuiteRecordShapeMapper mapper;

    private DwJsonWriter writer;

    @BeforeEach
    void setUp() {
        mapper = new NetsuiteRecordShapeMapper(LOS_ANGELES);
        writer = new DwJsonWriter();
    }

    @Test
    void recordKindsAreCustomerItemSupplyPlanAndOpportunity() {
        assertThat(RecordKind.values())
                .containsExactly(RecordKind.CUSTOMER, RecordKind.ITEM_SUPPLY_PLAN, RecordKind.OPPORTUNITY);
    }

    @Test
    void selectCustomFieldScriptIdsListsOnlyTheFourCustomerSelects() {
        assertThat(mapper.selectCustomFieldScriptIds(List.of(customer()), customerCatalog()))
                .containsExactly("custentity6", "custentity7", "custentity8", "custentity_fmt_cur_sales_order");
        assertThat(mapper.selectCustomFieldScriptIds(List.of(opportunity()), opportunityCatalog())).isEmpty();
        assertThat(mapper.selectCustomFieldScriptIds(List.of(plan(9), plan(18)), itemSupplyPlanCatalog())).isEmpty();
    }

    /** The ten custom fields of the committed customer, with the select {@code typeId}s 16, 17, -30 and 15. */
    @Test
    void customFieldListCarriesTheFourCommittedSelectTypeIds() {
        Map<String, Object> out =
                mapper.toSuiteTalk(RecordKind.CUSTOMER, customer(), customerCatalog(), customFieldSourceIds());

        Map<String, Object> customFieldList = asMap(out.get("customFieldList"));
        assertThat(customFieldList).containsOnlyKeys("customField");
        assertThat(asList(customFieldList.get("customField"))).hasSize(1);
        Map<String, Object> fields = customFields(out);
        assertThat(fields).hasSize(10);
        BOOLEAN_CUSTOM_FIELDS.forEach(scriptId ->
                assertThat(fields).containsEntry("BooleanCustomFieldRef__" + scriptId, "false"));
        assertThat(fields).containsEntry("LongCustomFieldRef__custentity_cust_priority", "50");
        assertSelect(fields, "custentity8", "3", "Less than $5 million", "16");
        assertSelect(fields, "custentity7", "1", "1 - 99 Employees", "17");
        assertSelect(fields, "custentity_fmt_cur_sales_order", "2570", "Sales Order #SLS00000452", "-30");
        assertSelect(fields, "custentity6", "4", "Computers, Peripherals, and Software", "15");

        JsonNode expected = committedCustomer().get("customFieldList");
        JsonNode actual = JSON.valueToTree(out.get("customFieldList"));
        assertTreeEqual(expected, actual);
        assertSameFieldOrder(expected, actual, "$.customFieldList");
    }

    /**
     * A non-empty {@code netsuite.custom-field-type-ids} entry wins over the SC-16 {@code customfield} lookup: the
     * lookup page answers {@code custentity8} with 16, the override says 99, and 99 reaches the select value while
     * the other three selects keep their looked-up sources.
     */
    @Test
    void customFieldTypeIdOverrideWinsOverTheCustomFieldLookup(@TempDir Path tmp) throws Exception {
        WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        try {
            wireMock.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(
                    okJson("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
            wireMock.stubFor(post(urlPathEqualTo(SUITEQL_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(resource("netsuite/rest-suiteql-customfield.json"))));
            NetsuiteProperties props = new NetsuiteProperties();
            props.setAccount("TSTDRV1234567");
            props.setBaseUrl(wireMock.baseUrl());
            props.getOauth().setClientId("test-client-id");
            props.getOauth().setCertificateId("test-certificate-id");
            props.getOauth().setPrivateKeyPath(writePkcs8Pem(tmp).toString());
            props.setCustomFieldTypeIds(Map.of("custentity8", "99"));
            NetsuiteRestClient client = new NetsuiteRestClient(props,
                    new NetsuiteTokenProvider(props, WebClient.builder()),
                    new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier())),
                    WebClient.builder());
            Map<String, Object> customer = customer();
            Map<String, Object> catalog = customerCatalog();

            Set<String> ids = mapper.selectCustomFieldScriptIds(List.of(customer), catalog);
            Map<String, String> sourceIds = client.customFieldSourceIds(ids);

            assertThat(sourceIds).containsOnly(
                    entry("custentity8", "99"),
                    entry("custentity7", "17"),
                    entry("custentity_fmt_cur_sales_order", "-30"),
                    entry("custentity6", "15"));
            Map<String, Object> fields = customFields(mapper.toSuiteTalk(RecordKind.CUSTOMER, customer, catalog,
                    sourceIds));
            assertThat(asMap(fields.get("SelectCustomFieldRef__custentity8"))).containsEntry("typeId", "99");
            assertThat(asMap(fields.get("SelectCustomFieldRef__custentity7"))).containsEntry("typeId", "17");
            assertThat(asMap(fields.get("SelectCustomFieldRef__custentity_fmt_cur_sales_order")))
                    .containsEntry("typeId", "-30");
            assertThat(asMap(fields.get("SelectCustomFieldRef__custentity6"))).containsEntry("typeId", "15");
            wireMock.verify(postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                    .withHeader("Authorization", equalTo("Bearer test-token")));
        } finally {
            wireMock.stop();
        }
    }

    /** {@code typeId} is never written null or guessed: an unresolved select fails, naming its script ID. */
    @Test
    void unresolvedSelectSourceFailsNamingTheScriptIdAndLeavesTheRecordUnchanged() {
        Map<String, Object> customer = customer();
        Map<String, String> withoutCustentity6 = new HashMap<>(customFieldSourceIds());
        withoutCustentity6.remove("custentity6");

        assertThatThrownBy(() -> mapper.toSuiteTalk(RecordKind.CUSTOMER, customer, customerCatalog(),
                withoutCustentity6))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custentity6");
        assertThat(customer).isEqualTo(customer());

        assertThatThrownBy(() -> mapper.toSuiteTalk(RecordKind.CUSTOMER, customer, customerCatalog(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage()).containsAnyOf(
                        "custentity8", "custentity7", "custentity_fmt_cur_sales_order", "custentity6"));
        assertThat(customer).isEqualTo(customer());
    }

    /** {@code units} is written only when the plan sets it; the {@code order} sublist never is. */
    @Test
    void itemSupplyPlanWritesUnitsOnlyWhenSetAndOmitsTheOrderSublist() {
        Map<String, Object> plan9 = plan(9);
        Map<String, Object> plan18 = plan(18);
        assertThat(plan9).containsKey("order").doesNotContainKey("units");
        assertThat(plan18).containsKeys("order", "units");

        Map<String, Object> first = mapper.toSuiteTalk(RecordKind.ITEM_SUPPLY_PLAN, plan9, itemSupplyPlanCatalog(),
                Map.of());
        Map<String, Object> second = mapper.toSuiteTalk(RecordKind.ITEM_SUPPLY_PLAN, plan18, itemSupplyPlanCatalog(),
                Map.of());

        assertThat(first.keySet()).containsExactly("internalId", "item", "location", "subsidiary");
        assertThat(second.keySet()).containsExactly("internalId", "item", "location", "units", "subsidiary");
        assertThat(first).containsEntry("internalId", "9");
        assertThat(second).containsEntry("internalId", "18");
        assertReference(first.get("item"), "702", "CUS00001");
        assertReference(second.get("item"), "721", "SHT00001");
        assertReference(second.get("units"), "15", "Square Feet");
        for (Map<String, Object> plan : List.of(first, second)) {
            assertReference(plan.get("location"), "1", "02: Boston");
            assertReference(plan.get("subsidiary"), "1", "Honeycomb Mfg.");
        }

        JsonNode committed = readTree(JSON, "api/items-response.json");
        assertThat(committed.size()).isEqualTo(2);
        List<Map<String, Object>> outputs = List.of(first, second);
        for (int i = 0; i < outputs.size(); i++) {
            JsonNode actual = JSON.valueToTree(outputs.get(i));
            assertTreeEqual(committed.get(i), actual);
            assertSameFieldOrder(committed.get(i), actual, "$[" + i + "]");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "receivablesAccount | -10 | Use System Preference",
            "accessRole         | 14  | Customer Center",
            "currency           | 1   | USA",
            "subsidiary         | 1   | Honeycomb Mfg.",
            "entityStatus       | 13  | CUSTOMER-Closed Won"
    })
    void customerReferencesAreWrittenExternalIdTypeInternalIdName(String field, String internalId, String name) {
        assertReference(mapped(RecordKind.CUSTOMER).get(field), internalId, name);
    }

    @ParameterizedTest
    @CsvSource({
            "CUSTOMER,    dateCreated,       2013-07-22T00:00:00-07:00",
            "CUSTOMER,    startDate,         2015-04-19T00:00:00-07:00",
            "CUSTOMER,    lastModifiedDate,  2015-01-22T20:10:20-08:00",
            "CUSTOMER,    firstVisit,        2015-01-02T08:03:20-08:00",
            "CUSTOMER,    lastVisit,         2016-02-24T11:41:36-08:00",
            "OPPORTUNITY, expectedCloseDate, 2014-12-05T00:00:00-08:00",
            "OPPORTUNITY, tranDate,          2015-04-03T00:00:00-07:00",
            "OPPORTUNITY, createdDate,       2015-05-03T10:55:10-07:00",
            "OPPORTUNITY, lastModifiedDate,  2015-01-01T12:13:44-08:00"
    })
    void datesAndDateTimesCarryTheAccountOffset(RecordKind kind, String field, String expected) {
        assertThat(mapped(kind)).containsEntry(field, expected);
    }

    @Test
    void enumerationsBecomeSuiteTalkConstantsAndOpportunityStatusKeepsItsText() {
        assertThat(mapped(RecordKind.CUSTOMER))
                .containsEntry("stage", "CUSTOMER")
                .containsEntry("creditHoldOverride", "AUTO");

        Map<String, Object> opportunity = mapped(RecordKind.OPPORTUNITY);
        ADDRESS_FIELDS.forEach(field ->
                assertThat(asMap(opportunity.get(field))).containsEntry("country", "UNITED_STATES"));
        assertThat(opportunity).containsEntry("status", "Issued Quote");
    }

    @Test
    void unmappedAddressCountryCodeFailsNamingTheCode() {
        Map<String, Object> opportunity = opportunity();
        Object country = asMap(opportunity.get("shippingAddress")).get("country");
        assertThat(country).isInstanceOf(Map.class);
        asMap(country).put("id", "ZZ");

        assertThatThrownBy(() -> mapper.toSuiteTalk(RecordKind.OPPORTUNITY, opportunity, opportunityCatalog(),
                Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZZ");
    }

    /**
     * Each opportunity address is the fixed 15-key SuiteTalk {@code Address} with nulls kept; the shipping address
     * carries the REST {@code id} and {@code override}, the billing address has neither.
     */
    @ParameterizedTest
    @CsvSource({
            "shippingAddress, 2475, false",
            "billingAddress,      ,"
    })
    void opportunityAddressHasTheFifteenCommittedKeysWithNullsKept(String field, String internalId,
                                                                    Boolean override) {
        Map<String, Object> rest = asMap(opportunity().get(field));

        Map<String, Object> address = asMap(mapped(RecordKind.OPPORTUNITY).get(field));

        assertThat(address.keySet()).containsExactlyElementsOf(ADDRESS_KEYS);
        assertThat(address)
                .containsEntry("addr3", null)
                .containsEntry("addrPhone", null)
                .containsEntry("attention", null)
                .containsEntry("nullFieldList", null)
                .containsEntry("customFieldList", null)
                .containsEntry("internalId", internalId)
                .containsEntry("override", override)
                .containsEntry("zip", "94002")
                .containsEntry("addr1", "123 Main St.")
                .containsEntry("addr2", "Suite 700")
                .containsEntry("city", "San Mate")
                .containsEntry("state", "CA")
                .containsEntry("addressee", "Williams Electronics and Communications")
                .containsEntry("addrText", rest.get("addrText"));
    }

    @Test
    void unsetFieldProducesNoKeyAndNoTopLevelValueIsNull() {
        Map<String, Object> customer = customer();
        customer.remove("phone");

        assertThat(mapper.toSuiteTalk(RecordKind.CUSTOMER, customer, customerCatalog(), customFieldSourceIds()))
                .doesNotContainKey("phone");
        assertThat(mapped(RecordKind.CUSTOMER)).containsEntry("phone", "650-555-9788");
        assertThat(mapped(RecordKind.CUSTOMER).values()).doesNotContainNull();
        assertThat(mapped(RecordKind.OPPORTUNITY).values()).doesNotContainNull();
    }

    @Test
    void numbersKeepTheirIntegerOrDecimalFormAndBooleansStayNative() {
        Map<String, Object> out = mapped(RecordKind.CUSTOMER);

        assertThat(out.get("consolBalance")).isEqualTo(new BigDecimal("-32.3"));
        assertThat(out.get("unbilledOrders")).isEqualTo(new BigDecimal("49.98"));
        assertThat(String.valueOf(out.get("visits"))).isEqualTo("31");
        assertThat(out.get("isInactive")).isEqualTo(Boolean.FALSE);
        assertThat(out.get("isPerson")).isEqualTo(Boolean.TRUE);
        assertThat(writer.write(List.of(out))).contains("\"consolBalance\": -32.3", "\"visits\": 31");
    }

    @Test
    void integralDecimalLongCustomFieldIsWrittenWithoutFraction() {
        Map<String, Object> customer = customer();
        customer.put("custentity_cust_priority", new BigDecimal("50.0"));

        Map<String, Object> out =
                mapper.toSuiteTalk(RecordKind.CUSTOMER, customer, customerCatalog(), customFieldSourceIds());

        assertThat(customFields(out)).containsEntry("LongCustomFieldRef__custentity_cust_priority", "50");
        assertThat(writer.write(List.of(out))).contains("\"LongCustomFieldRef__custentity_cust_priority\": \"50\"");
    }

    /**
     * Every output object is a {@code HashMap} except the references, which are {@code LinkedHashMap}s, and the
     * whole customer and opportunity match the committed examples in value and key order. The REST fixture and the
     * committed opportunity differ only in the whitespace around each {@code <br>} of {@code addrText}, which the
     * mapper passes through unchanged (asserted by
     * {@link #opportunityAddressHasTheFifteenCommittedKeysWithNullsKept}), so that whitespace is collapsed on both
     * sides before the opportunity is compared.
     */
    @Test
    void outputObjectsAreHashMapsAndMatchTheCommittedExamplesInCommittedOrder() {
        Map<String, Object> customer = mapped(RecordKind.CUSTOMER);
        Map<String, Object> opportunity = mapped(RecordKind.OPPORTUNITY);

        assertThat(customer).isExactlyInstanceOf(HashMap.class);
        assertThat(customFields(customer)).isExactlyInstanceOf(HashMap.class);
        assertThat(opportunity).isExactlyInstanceOf(HashMap.class);
        ADDRESS_FIELDS.forEach(field -> assertThat(opportunity.get(field)).isExactlyInstanceOf(HashMap.class));
        assertMapKinds(customer, "$");
        assertMapKinds(opportunity, "$");

        JsonNode expectedCustomer = committedCustomer();
        JsonNode actualCustomer = JSON.valueToTree(customer);
        assertTreeEqual(expectedCustomer, actualCustomer);
        assertSameFieldOrder(expectedCustomer, actualCustomer, "$");

        JsonNode expectedOpportunity = readTree(LENIENT_JSON, "api/opportunities-response.json").get(0);
        JsonNode actualOpportunity = JSON.valueToTree(opportunity);
        normalizeAddrTexts(expectedOpportunity);
        normalizeAddrTexts(actualOpportunity);
        assertTreeEqual(expectedOpportunity, actualOpportunity);
        assertSameFieldOrder(expectedOpportunity, actualOpportunity, "$");
    }

    @Test
    void propertiesConstructorRendersInTheDefaultAccountTimeZone() {
        NetsuiteProperties properties = new NetsuiteProperties();
        assertThat(properties.getTimeZone()).isEqualTo(LOS_ANGELES);

        Map<String, Object> fromProperties = new NetsuiteRecordShapeMapper(properties)
                .toSuiteTalk(RecordKind.CUSTOMER, customer(), customerCatalog(), customFieldSourceIds());

        assertThat(fromProperties).isEqualTo(mapped(RecordKind.CUSTOMER));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------

    /** Maps the fresh REST customer, with the committed source IDs, or the fresh REST opportunity. */
    private Map<String, Object> mapped(RecordKind kind) {
        return switch (kind) {
            case CUSTOMER -> mapper.toSuiteTalk(kind, customer(), customerCatalog(), customFieldSourceIds());
            case OPPORTUNITY -> mapper.toSuiteTalk(kind, opportunity(), opportunityCatalog(), Map.of());
            case ITEM_SUPPLY_PLAN -> throw new IllegalArgumentException("item supply plans are mapped per plan");
        };
    }

    private static Map<String, Object> customer() {
        return load("netsuite/rest-customer.json");
    }

    private static Map<String, Object> customerCatalog() {
        return load("netsuite/rest-customer-catalog.json");
    }

    private static Map<String, Object> opportunity() {
        return load("netsuite/rest-opportunity.json");
    }

    private static Map<String, Object> opportunityCatalog() {
        return load("netsuite/rest-opportunity-catalog.json");
    }

    private static Map<String, Object> plan(int internalId) {
        return load("netsuite/rest-itemsupplyplan-" + internalId + ".json");
    }

    private static Map<String, Object> itemSupplyPlanCatalog() {
        return load("netsuite/rest-itemsupplyplan-catalog.json");
    }

    /**
     * The {@code customfield} lookup result of {@code netsuite/rest-suiteql-customfield.json}: each row's
     * {@code scriptid} trimmed and lower-cased as {@code NetsuiteRestClient.customFieldSourceIds} reports it, mapped
     * to its {@code fieldvaluetyperecord}. It must hold exactly the committed customer's four select sources.
     */
    private static Map<String, String> customFieldSourceIds() {
        Map<String, String> sourceIds = new LinkedHashMap<>();
        for (Object item : asList(load("netsuite/rest-suiteql-customfield.json").get("items"))) {
            Map<String, Object> row = asMap(item);
            sourceIds.put(String.valueOf(row.get("scriptid")).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(row.get("fieldvaluetyperecord")));
        }
        assertThat(sourceIds).as("customfield lookup fixture").isEqualTo(COMMITTED_SOURCE_IDS);
        return sourceIds;
    }

    private static JsonNode committedCustomer() {
        return readTree(JSON, "api/customers-response.json").get(0);
    }

    /** Reads a classpath JSON object afresh, so a test that mutates it never affects another. */
    private static Map<String, Object> load(String classpathPath) {
        try {
            return JSON.readValue(resource(classpathPath), JSON_OBJECT);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse classpath resource " + classpathPath, e);
        }
    }

    private static JsonNode readTree(ObjectMapper json, String classpathPath) {
        try {
            return json.readTree(resource(classpathPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse classpath resource " + classpathPath, e);
        }
    }

    private static byte[] resource(String classpathPath) {
        try (InputStream in = NetsuiteRecordShapeMapperTest.class.getClassLoader()
                .getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + classpathPath + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + classpathPath, e);
        }
    }

    /** Writes a fresh RSA-2048 private key as the unencrypted PKCS#8 PEM file the token provider signs with. */
    private static Path writePkcs8Pem(Path directory) throws IOException, NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        Path file = directory.resolve("netsuite-test-key.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);
        return file;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------------------

    /** The single {@code customFieldList.customField} element of a mapped record. */
    private static Map<String, Object> customFields(Map<String, Object> record) {
        return asMap(asList(asMap(record.get("customFieldList")).get("customField")).get(0));
    }

    private static void assertSelect(Map<String, Object> fields, String scriptId, String internalId, String name,
                                     String typeId) {
        assertThat(asMap(fields.get("SelectCustomFieldRef__" + scriptId)))
                .as(scriptId)
                .containsOnlyKeys("internalId", "name", "externalId", "typeId")
                .containsEntry("internalId", internalId)
                .containsEntry("name", name)
                .containsEntry("externalId", null)
                .containsEntry("typeId", typeId);
    }

    /** A SuiteTalk {@code RecordRef}: a {@code LinkedHashMap} iterating {@code externalId, type, internalId, name}. */
    private static void assertReference(Object value, String internalId, String name) {
        assertThat(value).isExactlyInstanceOf(LinkedHashMap.class);
        Map<String, Object> reference = asMap(value);
        assertThat(reference.keySet()).containsExactlyElementsOf(REFERENCE_KEYS);
        assertThat(reference.values()).containsExactly(null, null, internalId, name);
    }

    /** Walks the output: a reference must be a {@code LinkedHashMap}, every other object a {@code HashMap}. */
    private static void assertMapKinds(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            boolean reference = REFERENCE_KEYS.equals(new ArrayList<>(map.keySet()));
            assertThat(map).as(path).isExactlyInstanceOf(reference ? LinkedHashMap.class : HashMap.class);
            map.forEach((key, child) -> assertMapKinds(child, path + "." + key));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertMapKinds(list.get(i), path + "[" + i + "]");
            }
        }
    }

    private static void assertTreeEqual(JsonNode expected, JsonNode actual) {
        assertThat(expected.equals(SAME_VALUE_AND_FORM, actual))
                .as("tree equality%nexpected: %s%nactual:   %s", expected, actual)
                .isTrue();
    }

    /** Asserts that every object of {@code actual} lists its field names in the order of {@code expected}. */
    private static void assertSameFieldOrder(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as("%s is an object", path).isTrue();
            List<String> names = fieldNames(expected);
            assertThat(fieldNames(actual)).as("field order of %s", path).containsExactlyElementsOf(names);
            for (String name : names) {
                assertSameFieldOrder(expected.get(name), actual.get(name), path + "." + name);
            }
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as("%s is an array", path).isTrue();
            assertThat(actual.size()).as("size of %s", path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameFieldOrder(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void normalizeAddrTexts(JsonNode opportunity) {
        for (String field : ADDRESS_FIELDS) {
            ObjectNode address = (ObjectNode) opportunity.get(field);
            address.put("addrText", normalizeAddrText(address.get("addrText").asText()));
        }
    }

    private static String normalizeAddrText(String text) {
        return text.replaceAll("\\s*<br>\\s*", "<br>").replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static List<?> asList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<?>) value;
    }
}
