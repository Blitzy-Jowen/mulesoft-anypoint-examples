package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.get_customer_list_from_netsuite.config.NetsuiteProperties;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamRateLimitException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Tests {@link NetsuiteRestClient} (SC-16) against WireMock standing in for the NetSuite REST services, with the token
 * provider mocked and the real SC-9 executor and NetSuite classifier, so the tests need no account and no
 * credential. They cover SuiteQL paging, the record API, the metadata catalog and the custom-field source lookup of
 * AAP 0.7.4, SuiteQL literal escaping (AAP 0.4.10), the approved failure handling of AAP 0.4.8 as the client drives
 * it, and the start-up of the client with every {@code application.yml} placeholder empty.
 *
 * <p>{@code netsuite-data-retrieval-java} carries this class with the client, changing only the package line and
 * the import prefix.
 */
class NetsuiteRestClientTest {

    @RegisterExtension
    static WireMockExtension netsuite = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String SUITEQL = "/services/rest/query/v1/suiteql";

    private static final String CUSTOMER_RECORDS = "/services/rest/record/v1/customer";

    private static final String CUSTOMER_CATALOG = "/services/rest/record/v1/metadata-catalog/customer";

    private static final String CUSTOM_FIELD_QUERY_PREFIX =
            "SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE UPPER(scriptid) IN ";

    private NetsuiteProperties props;

    private NetsuiteTokenProvider tokenProvider;

    private NetsuiteRestClient client;

    @BeforeEach
    void setUp() {
        props = new NetsuiteProperties();
        props.setBaseUrl(netsuite.baseUrl());
        props.setReadTimeout(Duration.ofSeconds(2));
        tokenProvider = mock(NetsuiteTokenProvider.class);
        when(tokenProvider.accessToken()).thenReturn("token-1");
        client = newClient(props);
    }

    private NetsuiteRestClient newClient(NetsuiteProperties properties) {
        UpstreamCallExecutor executor = new UpstreamCallExecutor(List.of(new NetsuiteFailureClassifier()));
        return new NetsuiteRestClient(properties, tokenProvider, executor, WebClient.builder());
    }

    // ---------------------------------------------------------------------------------------------- SuiteQL

    @Test
    void suiteQlPagesUntilHasMoreIsFalseAndStripsLinks() {
        String query = "SELECT email, firstname, lastname FROM customer WHERE lastname LIKE 'O''Br%'\nORDER BY lastname";
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("""
                        {"links": [{"rel": "next", "href": "x"}], "count": 2, "hasMore": true, "offset": 0,
                         "items": [
                           {"links": [], "email": "ob@example.com", "firstname": "Ann", "lastname": "OBrien",
                            "balance": 50.0, "id": 107},
                           {"links": [], "email": "oc@example.com", "firstname": "Bob", "lastname": "OBryan"}
                         ]}""")));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("""
                        {"links": [], "count": 1, "hasMore": false, "offset": 2,
                         "items": [{"links": [], "email": "od@example.com", "firstname": "Cy", "lastname": "OBryce"}]}
                        """)));

        List<Map<String, Object>> rows = client.suiteQl(query);

        assertThat(rows).extracting(row -> row.get("lastname")).containsExactly("OBrien", "OBryan", "OBryce");
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContainKey("links"));
        assertThat(new ArrayList<>(rows.get(0).keySet()))
                .containsExactly("email", "firstname", "lastname", "balance", "id");
        assertThat(rows.get(0).get("balance")).isEqualTo(new BigDecimal("50.0"));
        assertThat(rows.get(0).get("id")).isEqualTo(107);
        assertThatThrownBy(() -> rows.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);

        for (String offset : List.of("0", "2")) {
            netsuite.verify(postRequestedFor(urlEqualTo(SUITEQL + "?limit=1000&offset=" + offset))
                    .withHeader("Prefer", equalTo("transient"))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("Authorization", equalTo("Bearer token-1"))
                    .withRequestBody(equalToJson("{\"q\": \"SELECT email, firstname, lastname FROM customer"
                            + " WHERE lastname LIKE 'O''Br%'\\nORDER BY lastname\"}")));
        }
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @Test
    void suiteQlAdvancesByItemCountWhenCountIsAbsent() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("{\"hasMore\": true, \"items\": [{\"id\": 1}, {\"id\": 2}, {\"id\": 3}]}")));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).withQueryParam("offset", equalTo("3"))
                .willReturn(okJson("{\"hasMore\": false, \"items\": [{\"id\": 4}]}")));

        assertThat(client.suiteQl("SELECT id FROM customer")).extracting(row -> row.get("id"))
                .containsExactly(1, 2, 3, 4);
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @ParameterizedTest
    @MethodSource("pagesEndingTheRead")
    void suiteQlStopsAfterAPageThatEndsTheRead(String pageBody) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson(pageBody)));

        assertThat(client.suiteQl("SELECT id FROM customer")).isEmpty();
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    static Stream<String> pagesEndingTheRead() {
        return Stream.of(
                "{\"count\": 0, \"hasMore\": false, \"items\": []}",
                // A server that reports more but delivers nothing must not keep the client looping.
                "{\"count\": 0, \"hasMore\": true, \"items\": []}",
                "{\"hasMore\": true}",
                "");
    }

    @Test
    void suiteQlRejectsANullQueryWithoutARequest() {
        assertThatThrownBy(() -> client.suiteQl(null)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void suiteQlReportsAnUnparsableBody() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson("{\"items\": [ {\"email\": ")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unparsable NetSuite response")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void suiteQlRejectsItemsThatAreNotAnArrayOfObjects() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(okJson("{\"hasMore\": false, \"items\": [1]}")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an object");
    }

    @ParameterizedTest
    @MethodSource("literals")
    void suiteQlLiteralDoublesQuotesOnly(String value, String expected) {
        assertThat(NetsuiteRestClient.suiteQlLiteral(value)).isEqualTo(expected);
    }

    static Stream<Arguments> literals() {
        return Stream.of(
                Arguments.of("O'Brien\\%", "O''Brien\\%"),
                Arguments.of("a_b%", "a_b%"),
                Arguments.of("''", "''''"),
                Arguments.of("", ""));
    }

    @Test
    void suiteQlLiteralRejectsNull() {
        assertThatThrownBy(() -> NetsuiteRestClient.suiteQlLiteral(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------- Record API

    @Test
    void getRecordExpandsSubResourcesAndReturnsTheRecordUnchanged() {
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS + "/107"))
                .withQueryParam("expandSubResources", equalTo("true"))
                .willReturn(okJson("""
                        {"links": [{"rel": "self", "href": "x"}], "id": "107", "companyName": "Acme",
                         "balance": 12.50, "custentity8": {"id": "3", "refName": "Gold"}}""")));

        Map<String, Object> record = client.getRecord("customer", "107");

        assertThat(new ArrayList<>(record.keySet()))
                .containsExactly("links", "id", "companyName", "balance", "custentity8");
        assertThat(record.get("balance")).isEqualTo(new BigDecimal("12.50"));
        assertThat(record.get("custentity8")).isInstanceOf(LinkedHashMap.class)
                .isEqualTo(Map.of("id", "3", "refName", "Gold"));
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/107?expandSubResources=true"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer token-1")));
    }

    @Test
    void recordPathVariablesAreEncodedSoTheyCannotChangeTheRequest() {
        netsuite.stubFor(get(anyUrl()).willReturn(okJson("{}")));

        client.getRecord("customer", "1?expandSubResources=false&x=y");

        netsuite.verify(getRequestedFor(urlEqualTo(
                CUSTOMER_RECORDS + "/1%3FexpandSubResources%3Dfalse%26x%3Dy?expandSubResources=true")));
    }

    @ParameterizedTest
    @MethodSource("blankArguments")
    void recordMethodsRejectBlankTypesAndIdsWithoutARequest(String type, String id) {
        assertThatThrownBy(() -> client.getRecord(type, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteRecord(type, id)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> blankArguments() {
        return Stream.of(
                Arguments.of(null, "1"),
                Arguments.of(" ", "1"),
                Arguments.of("customer", null),
                Arguments.of("customer", ""));
    }

    @Test
    void listRecordIdsPagesAndSkipsItemsWithoutAnId() {
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS)).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson("""
                        {"count": 2, "hasMore": true,
                         "items": [{"links": [], "id": "11"}, {"links": [], "id": 12}]}""")));
        netsuite.stubFor(get(urlPathEqualTo(CUSTOMER_RECORDS)).withQueryParam("offset", equalTo("2"))
                .willReturn(okJson("{\"count\": 2, \"hasMore\": false, \"items\": [{\"id\": \"13\"}, {\"links\": []}]}")));

        assertThat(client.listRecordIds("customer")).containsExactly("11", "12", "13");
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "?limit=1000&offset=0"))
                .withHeader("Authorization", equalTo("Bearer token-1")));
        netsuite.verify(getRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "?limit=1000&offset=2")));
        netsuite.verify(2, getRequestedFor(urlPathEqualTo(CUSTOMER_RECORDS)));
    }

    @ParameterizedTest
    @MethodSource("locations")
    void createRecordReturnsTheIdFromLocation(String location) {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(noContent().withHeader("Location", location)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", "O'Brien & Co");
        body.put("subsidiary", Map.of("id", "1"));

        assertThat(client.createRecord("customer", body)).isEqualTo("4711");

        netsuite.verify(1, postRequestedFor(urlEqualTo(CUSTOMER_RECORDS))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .withRequestBody(equalToJson("{\"companyName\": \"O'Brien & Co\", \"subsidiary\": {\"id\": \"1\"}}")));
    }

    static Stream<String> locations() {
        return Stream.of(
                "https://tstdrv1234567.suitetalk.api.netsuite.com/services/rest/record/v1/customer/4711",
                "https://tstdrv1234567.suitetalk.api.netsuite.com/services/rest/record/v1/customer/4711/",
                "/services/rest/record/v1/customer/4711");
    }

    @Test
    void createRecordWithoutLocationFails() {
        netsuite.stubFor(post(urlEqualTo(CUSTOMER_RECORDS)).willReturn(noContent()));

        assertThatThrownBy(() -> client.createRecord("customer", Map.of("companyName", "Acme")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NetSuite create customer returned no Location header");
    }

    @Test
    void createRecordRejectsANullBodyWithoutARequest() {
        assertThatThrownBy(() -> client.createRecord("customer", null)).isInstanceOf(IllegalArgumentException.class);
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void deleteRecordSendsDelete() {
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORDS + "/4711")).willReturn(noContent()));

        client.deleteRecord("customer", "4711");

        netsuite.verify(1, deleteRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/4711"))
                .withHeader("Authorization", equalTo("Bearer token-1")));
    }

    @Test
    void deleteRecordPassesNetsuiteErrorsThroughUnwrapped() {
        netsuite.stubFor(delete(urlEqualTo(CUSTOMER_RECORDS + "/4711")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"title\": \"Not Found\", \"status\": 404}")));

        assertThatThrownBy(() -> client.deleteRecord("customer", "4711"))
                .isInstanceOf(WebClientResponseException.NotFound.class);
        netsuite.verify(1, deleteRequestedFor(urlEqualTo(CUSTOMER_RECORDS + "/4711")));
    }

    // ------------------------------------------------------------------------------------- Metadata catalog

    @Test
    void metadataCatalogAsksForJsonSchemaAndIsCached() {
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).willReturn(aResponse()
                .withHeader("Content-Type", "application/schema+json")
                .withBody("{\"type\": \"object\", \"properties\": {\"custentity8\": {\"title\": \"Tier\","
                        + " \"x-ns-custom-field\": true}}}")));

        Map<String, Object> first = client.metadataCatalog("customer");
        Map<String, Object> second = client.metadataCatalog("customer");

        assertThat(second).isSameAs(first);
        assertThat(first).containsEntry("type", "object");
        netsuite.verify(1, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG))
                .withHeader("Accept", equalTo("application/schema+json"))
                .withHeader("Authorization", equalTo("Bearer token-1")));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) first.get("properties");
        assertThatThrownBy(() -> first.put("type", "array")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.remove("custentity8")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metadataCatalogDoesNotCacheAFailure() {
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        netsuite.stubFor(get(urlEqualTo(CUSTOMER_CATALOG)).inScenario("catalog").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"type\": \"object\"}")));

        assertThatThrownBy(() -> client.metadataCatalog("customer"))
                .isInstanceOf(WebClientResponseException.InternalServerError.class);
        assertThat(client.metadataCatalog("customer")).containsEntry("type", "object");
        assertThat(client.metadataCatalog("customer")).containsEntry("type", "object");
        netsuite.verify(2, getRequestedFor(urlEqualTo(CUSTOMER_CATALOG)));
    }


    // -------------------------------------------------------------------------- Custom-field source lookup

    @Test
    void customFieldSourceIdsSendsOneUpperCasedEscapedQueryAndRendersTheRawSources() {
        stubCustomFieldLookup("'CUSTENTITY8', 'CUSTENTITY7', 'CUSTENTITY_FMT_CUR_SALES_ORDER', 'CUSTENTITY6',"
                + " 'CUST''X', 'CUSTENTITY_MISSING', 'CUSTENTITY_DECIMAL'", """
                {"scriptid": "CUSTENTITY8", "fieldvaluetyperecord": 16},
                {"scriptid": "CUSTENTITY7", "fieldvaluetyperecord": " 17 "},
                {"SCRIPTID": "custentity_fmt_cur_sales_order", "FieldValueTypeRecord": -30},
                {"scriptid": "CUSTENTITY6", "fieldvaluetyperecord": 15.0},
                {"scriptid": "CUST'X", "fieldvaluetyperecord": "-112"},
                {"scriptid": "CUSTENTITY_MISSING", "fieldvaluetyperecord": null},
                {"scriptid": "CUSTENTITY_DECIMAL", "fieldvaluetyperecord": 1.50}""");

        Map<String, String> sources = client.customFieldSourceIds(Arrays.asList("custentity8", "CUSTENTITY7",
                "custentity_fmt_cur_sales_order", "custentity6", "cust'x", "custentity_missing", "CustEntity8", " ",
                null, "custentity_decimal"));

        // The committed customers-response.json values (custentity8 16, custentity7 17, custentity6 15,
        // custentity_fmt_cur_sales_order -30), in first-seen order; the unresolved ID is absent.
        assertThat(new ArrayList<>(sources.entrySet())).containsExactly(
                Map.entry("custentity8", "16"),
                Map.entry("custentity7", "17"),
                Map.entry("custentity_fmt_cur_sales_order", "-30"),
                Map.entry("custentity6", "15"),
                Map.entry("cust'x", "-112"),
                Map.entry("custentity_decimal", "1.50"));
        assertThatThrownBy(() -> sources.put("custentity9", "1")).isInstanceOf(UnsupportedOperationException.class);
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Prefer", equalTo("transient")));
    }

    @Test
    void customFieldSourceIdsServesResolvedIdsFromTheCacheAndLooksUnresolvedOnesUpAgain() {
        stubCustomFieldLookup("'CUSTENTITY8', 'CUSTENTITY_MISSING'",
                "{\"scriptid\": \"CUSTENTITY8\", \"fieldvaluetyperecord\": 16}");
        stubCustomFieldLookup("'CUSTENTITY_MISSING'", "");

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity_missing")))
                .containsExactly(Map.entry("custentity8", "16"));
        assertThat(client.customFieldSourceIds(List.of("CUSTENTITY8"))).containsExactly(Map.entry("custentity8", "16"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity_missing")))
                .containsExactly(Map.entry("custentity8", "16"));
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withRequestBody(equalToJson(customFieldQueryJson("'CUSTENTITY_MISSING'"))));
    }

    @Test
    void customFieldSourceIdsLetsOverridesWinOverLookupAndCache() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("CustEntity8", " 99 ");
        overrides.put("custentity7", "  ");
        props.setCustomFieldTypeIds(overrides);
        stubCustomFieldLookup("'CUSTENTITY7'", "{\"scriptid\": \"CUSTENTITY7\", \"fieldvaluetyperecord\": 17}");

        assertThat(client.customFieldSourceIds(List.of("custentity8", "custentity7")))
                .containsExactly(Map.entry("custentity8", "99"), Map.entry("custentity7", "17"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));

        // custentity7 is now cached as 17; a later override still takes precedence.
        props.setCustomFieldTypeIds(Map.of("CUSTENTITY7", "42"));
        assertThat(client.customFieldSourceIds(List.of("custentity7"))).containsExactly(Map.entry("custentity7", "42"));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @ParameterizedTest
    @MethodSource("scriptIdsNeedingNoLookup")
    void customFieldSourceIdsSendsNoRequestWhenNothingIsLeftToLookUp(List<String> scriptIds) {
        props.setCustomFieldTypeIds(Map.of("custentity8", "16"));

        Map<String, String> sources = client.customFieldSourceIds(scriptIds);

        assertThat(sources).allSatisfy((id, source) -> assertThat(id).isEqualTo("custentity8"));
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    static Stream<List<String>> scriptIdsNeedingNoLookup() {
        return Stream.of(null, List.of(), Arrays.asList(" ", null, ""), List.of("custentity8"));
    }

    private void stubCustomFieldLookup(String inList, String rows) {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL))
                .withRequestBody(equalToJson(customFieldQueryJson(inList)))
                .willReturn(okJson("{\"count\": 1, \"hasMore\": false, \"items\": [" + rows + "]}")));
    }

    private static String customFieldQueryJson(String inList) {
        return "{\"q\": \"" + CUSTOM_FIELD_QUERY_PREFIX + "(" + inList + ")\"}";
    }

    // ------------------------------------------------------------------------------------ Failure handling

    @Test
    void unauthorizedEvictsTheTokenAndRetriesOnceWithAFreshOne() {
        when(tokenProvider.accessToken()).thenReturn("token-1", "token-2");
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).inScenario("auth").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401)).willSetStateTo("renewed"));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).inScenario("auth").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"hasMore\": false, \"items\": [{\"lastname\": \"Abbott\"}]}")));

        assertThat(client.suiteQl("SELECT lastname FROM customer")).containsExactly(Map.of("lastname", "Abbott"));

        verify(tokenProvider, times(2)).accessToken();
        verify(tokenProvider, times(1)).evict();
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Authorization", equalTo("Bearer token-1")));
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL))
                .withHeader("Authorization", equalTo("Bearer token-2")));
    }

    @Test
    void unauthorizedRetryThatIsRejectedAgainEndsTheAttempt() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(UpstreamAuthenticationException.class);
        verify(tokenProvider, times(1)).evict();
        netsuite.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @Test
    void rateLimitIsNotRetriedAndCarriesRetryAfter() {
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "30")));

        assertThatThrownBy(() -> client.suiteQl("SELECT id FROM customer"))
                .isInstanceOfSatisfying(UpstreamRateLimitException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo("30"));
        verify(tokenProvider, never()).evict();
        netsuite.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timedOutCalls")
    void readTimeoutIsUnavailableAfterExactlyOneRequest(String name, TimedOutCall call) {
        props.setReadTimeout(Duration.ofMillis(300));
        NetsuiteRestClient timingOut = newClient(props);
        netsuite.stubFor(post(anyUrl()).willReturn(okJson("{}").withFixedDelay(1500)));

        assertThatThrownBy(() -> call.invoke(timingOut)).isInstanceOf(UpstreamUnavailableException.class);

        verify(tokenProvider, never()).evict();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> netsuite.verify(1, anyRequestedFor(anyUrl())));
        netsuite.verify(1, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> timedOutCalls() {
        return Stream.of(
                Arguments.of("suiteQl", (TimedOutCall) c -> c.suiteQl("SELECT id FROM customer")),
                // A write is never repeated after a timeout: NetSuite may already have created the record.
                Arguments.of("createRecord", (TimedOutCall) c -> c.createRecord("customer", Map.of("companyName", "A"))));
    }

    @FunctionalInterface
    interface TimedOutCall {
        void invoke(NetsuiteRestClient client);
    }

    // -------------------------------------------------------------------------------------- Configuration

    @Test
    void missingAccountFailsTheFirstCallNamingTheKeyBeforeAnyRequest() {
        NetsuiteRestClient unconfigured = newClient(new NetsuiteProperties());

        assertThatThrownBy(() -> unconfigured.suiteQl("SELECT id FROM customer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("netsuite.account");
        verify(tokenProvider, never()).accessToken();
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void contextStartsWithTheEmptyPlaceholdersOfApplicationYml() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(NetsuiteRestClient.class);
            assertThatThrownBy(() -> context.getBean(NetsuiteRestClient.class).suiteQl("SELECT id FROM customer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("netsuite.account");
        });
    }

    @Test
    void contextWithABaseUrlButNoOauthCredentialsFailsInTheTokenProviderNamingTheKey() {
        contextRunner().withPropertyValues("netsuite.base-url=" + netsuite.baseUrl()).run(context -> {
            assertThatThrownBy(() -> context.getBean(NetsuiteRestClient.class).suiteQl("SELECT id FROM customer"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("netsuite.oauth.");
        });
        netsuite.verify(0, anyRequestedFor(anyUrl()));
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        CodecsAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(ClientConfiguration.class);
    }

    /** The SC-16 and SC-9 beans as the application's component scan registers them. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import({NetsuiteProperties.class, NetsuiteTokenProvider.class, NetsuiteFailureClassifier.class,
            UpstreamCallExecutor.class, NetsuiteRestClient.class})
    static class ClientConfiguration {
    }
}

