/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import com.mulesoft.examples.dataweave_with_flowreflookup.service.SalesRegionLookupService;

/**
 * Unit test of {@link AccountMapper}, the hand-written owner of the {@code CSV to Maps} script of
 * {@code dataweave-with-flowref.xml} (lines 9-19), under {@code %output application/java}:
 * <pre>
 * payload map {
 *   Name: $.company_name,
 *   BillingStreet: $.company_address,
 *   BillingCity: $.company_city,
 *   BillingState: upper $.company_state,
 *   BillingPostalCode: $.company_zip,
 *   Region__c: lookup("LookUpSalesRegionFlow", $).region
 * }
 * </pre>
 *
 * <p>Inputs are built inline as the CSV reader delivers them, one {@link LinkedHashMap} of strings per row in the
 * header order of {@code companies.csv}; no resource, CSV reader or Spring context is used. The region lookup is a
 * Mockito spy over the real {@link SalesRegionLookupService}, so regions come from the original Groovy table while
 * the calls the mapper makes stay observable; the lookup's full table, console line and log line are tested by
 * {@code SalesRegionLookupServiceTest}. Expected field names and values are literals, so they are checked
 * independently of the mapper's constants. The mapper has no {@code SObject} conversion, because
 * {@code SalesforcePartnerClient.create(String, List)} builds the WSC objects from these maps itself.
 */
class AccountMapperTest {

    /** The Account fields of the script, in its declaration order. */
    private static final List<String> ACCOUNT_FIELDS =
            List.of("Name", "BillingStreet", "BillingCity", "BillingState", "BillingPostalCode", "Region__c");

    private SalesRegionLookupService lookup;

    private AccountMapper mapper;

    @BeforeEach
    void setUp() {
        lookup = spy(new SalesRegionLookupService());
        mapper = new AccountMapper(lookup);
    }

    // shipped input

    @Test
    @DisplayName("The companies.csv rows map to the two Accounts, in input order and script key order")
    void mapsTheShippedRowsToExactAccounts() {
        List<Map<String, Object>> accounts = mapper.map(List.of(universalExports(), bestWidgets()));

        assertThat(accounts).hasSize(2);
        assertAccount(accounts.get(0),
                "Universal Exports", "55 Main St", "Miami", "FL", "33126", "South East");
        assertAccount(accounts.get(1),
                "Best Widgets", "1966 Latrobe Rd", "El Dorado Hills", "CA", "95762", "West Coast");
    }

    @Test
    @DisplayName("Universal Exports gets Region__c South East, the original IT assertion")
    void universalExportsIsSouthEast() {
        Map<String, Object> account = mapper.map(List.of(universalExports())).get(0);

        assertThat(account.get("Region__c")).isEqualTo("South East");
    }

    // value fidelity

    @Test
    @DisplayName("upper $.company_state: fl becomes FL in BillingState while the input row keeps fl, unmodified")
    void upperCasesTheStateWithoutMutatingTheInput() {
        Map<String, String> row = universalExports();
        Map<String, String> before = new LinkedHashMap<>(row);
        List<String> keysBefore = new ArrayList<>(row.keySet());
        List<Map<String, String>> rows = new ArrayList<>(List.of(row));

        List<Map<String, Object>> accounts = mapper.map(rows);
        accounts.get(0).put("Name", "Changed");

        assertThat(accounts.get(0).get("BillingState")).isEqualTo("FL");
        assertThat(row.get("company_state")).isEqualTo("fl");
        assertThat(row).isEqualTo(before);
        assertThat(new ArrayList<>(row.keySet())).isEqualTo(keysBefore);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isSameAs(row);
    }

    @Test
    @DisplayName("BillingPostalCode stays the reader's String; a leading zero (02134) is kept")
    void postalCodeStaysAString() {
        Map<String, Object> miami = mapper.map(List.of(universalExports())).get(0);
        Map<String, Object> boston =
                mapper.map(List.of(row("Acme", "1 Beacon St", "Boston", "ma", "02134", ""))).get(0);

        assertThat(miami.get("BillingPostalCode")).isInstanceOf(String.class).isEqualTo("33126");
        assertThat(boston.get("BillingPostalCode")).isInstanceOf(String.class).isEqualTo("02134");
        assertAccount(boston, "Acme", "1 Beacon St", "Boston", "MA", "02134", "North East");
    }

    @Test
    @DisplayName("Values other than the state are copied as read: no trimming, re-casing or empty-to-null")
    void otherValuesArePassedThroughUntouched() {
        Map<String, Object> account =
                mapper.map(List.of(row(" universal exports ", "", "miami", "Fl", " 33126", ""))).get(0);

        assertAccount(account, " universal exports ", "", "miami", "FL", " 33126", "South East");
    }

    @Test
    @DisplayName("The reversed input gives the reversed output: Best Widgets first")
    void preservesInputOrder() {
        List<Map<String, Object>> accounts = mapper.map(List.of(bestWidgets(), universalExports()));

        assertThat(accounts).extracting(account -> account.get("Name"))
                .containsExactly("Best Widgets", "Universal Exports");
        assertThat(accounts).extracting(account -> account.get("Region__c"))
                .containsExactly("West Coast", "South East");
    }

    // absent values

    @Test
    @DisplayName("A row without company_state keeps BillingState with a null value, and Region__c is UNKNOWN")
    void missingStateGivesNullBillingStateAndUnknownRegion() {
        Map<String, String> row = universalExports();
        row.remove("company_state");

        Map<String, Object> account = mapper.map(List.of(row)).get(0);

        assertThat(account).containsKey("BillingState");
        assertThat(account.get("BillingState")).isNull();
        assertAccount(account, "Universal Exports", "55 Main St", "Miami", null, "33126", "UNKNOWN");
    }

    @Test
    @DisplayName("An empty company_state stays empty (upper \"\") and Region__c is UNKNOWN")
    void emptyStateGivesEmptyBillingStateAndUnknownRegion() {
        Map<String, Object> account =
                mapper.map(List.of(row("Universal Exports", "55 Main St", "Miami", "", "33126", ""))).get(0);

        assertAccount(account, "Universal Exports", "55 Main St", "Miami", "", "33126", "UNKNOWN");
    }

    @Test
    @DisplayName("A row holding only company_state TX gives the six keys in order, the other four null")
    void otherMissingColumnsGiveNullFields() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("company_state", "TX");

        Map<String, Object> account = mapper.map(List.of(row)).get(0);

        for (String field : List.of("Name", "BillingStreet", "BillingCity", "BillingPostalCode")) {
            assertThat(account).containsKey(field);
            assertThat(account.get(field)).as(field).isNull();
        }
        assertAccount(account, null, null, null, "TX", null, "South East");
    }

    // ignored columns

    @Test
    @DisplayName("has_given_contact_permission (\"\" or yes) and extra columns never reach the Account")
    void unmappedColumnsAreIgnored() {
        Map<String, String> withEmptyPermission = universalExports();
        withEmptyPermission.put("notes", "call back");
        Map<String, String> withPermission = row("Best Widgets", "1966 Latrobe Rd", "El Dorado Hills", "CA",
                "95762", "yes");
        withPermission.put("notes", "VIP");

        List<Map<String, Object>> accounts = mapper.map(List.of(withEmptyPermission, withPermission));

        for (Map<String, Object> account : accounts) {
            assertThat(account).doesNotContainKeys("has_given_contact_permission", "notes", "company_name",
                    "company_address", "company_city", "company_state", "company_zip");
            assertThat(account.keySet()).containsExactlyElementsOf(ACCOUNT_FIELDS);
        }
        assertAccount(accounts.get(0), "Universal Exports", "55 Main St", "Miami", "FL", "33126", "South East");
        assertAccount(accounts.get(1), "Best Widgets", "1966 Latrobe Rd", "El Dorado Hills", "CA", "95762",
                "West Coast");
    }

    // lookup contract

    @Test
    @DisplayName("lookup(\"LookUpSalesRegionFlow\", $) runs once per row and receives the raw row ($), in row order")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void lookupReceivesEachRawRowOnce() {
        Map<String, String> first = universalExports();
        Map<String, String> second = bestWidgets();
        ArgumentCaptor<Map<String, ?>> records = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);

        mapper.map(List.of(first, second));

        verify(lookup, times(2)).lookUpSalesRegion(records.capture());
        assertThat(records.getAllValues()).containsExactly(
                row("Universal Exports", "55 Main St", "Miami", "fl", "33126", ""),
                row("Best Widgets", "1966 Latrobe Rd", "El Dorado Hills", "CA", "95762", ""));
        assertThat(records.getAllValues().get(0)).isSameAs(first);
        assertThat(records.getAllValues().get(1)).isSameAs(second);
    }

    @Test
    @DisplayName("Region__c is the lookup's region entry, whatever the lookup returns")
    void regionComesFromTheLookupRegionEntry() {
        doReturn(Map.of("region", "Stubbed Region")).when(lookup).lookUpSalesRegion(any());

        Map<String, Object> account = mapper.map(List.of(universalExports())).get(0);

        assertAccount(account, "Universal Exports", "55 Main St", "Miami", "FL", "33126", "Stubbed Region");
    }

    @ParameterizedTest(name = "company_state {0} -> Region__c {1}")
    @CsvSource({"ct, North East", "il, Mid West", "az, South West", "fl, South East", "ca, West Coast",
            "XX, UNKNOWN"})
    @DisplayName("One state per region label resolves through the real lookup")
    void resolvesEachRegionLabelThroughTheRealLookup(String state, String region) {
        Map<String, Object> account =
                mapper.map(List.of(row("Acme", "1 Main St", "Springfield", state, "00001", ""))).get(0);

        assertAccount(account, "Acme", "1 Main St", "Springfield", state.toUpperCase(Locale.ROOT),
                "00001", region);
    }

    // collection handling

    @Test
    @DisplayName("An empty input gives an empty, mutable list and no lookup")
    void emptyInputGivesEmptyListWithoutLookup() {
        List<Map<String, Object>> accounts = mapper.map(List.of());

        assertThat(accounts).isNotNull().isEmpty();
        accounts.add(new LinkedHashMap<>());
        assertThat(accounts).hasSize(1);
        verifyNoInteractions(lookup);
    }

    @Test
    @DisplayName("A null row list is rejected with a NullPointerException naming rows")
    void nullInputIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> mapper.map(null)).withMessage("rows");
        verifyNoInteractions(lookup);
    }

    @Test
    @DisplayName("A null row is rejected before the lookup, which failed on a null record in the original")
    void nullRowIsRejectedBeforeTheLookup() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(null);

        assertThatNullPointerException().isThrownBy(() -> mapper.map(rows))
                .withMessageContaining("null row");
        verifyNoInteractions(lookup);
    }

    @Test
    @DisplayName("The mapper cannot be built without its region lookup")
    void constructorRejectsNullLookup() {
        assertThatNullPointerException().isThrownBy(() -> new AccountMapper(null))
                .withMessage("salesRegionLookupService");
    }

    @Test
    @DisplayName("The records are created as type Account, the type of the original sfdc:create")
    void sObjectTypeIsAccount() {
        assertThat(AccountMapper.SOBJECT_TYPE).isEqualTo("Account");
    }

    // fixtures and assertions

    /** A CSV row keyed by the {@code companies.csv} header, in header order. */
    private static Map<String, String> row(String name, String address, String city, String state, String zip,
            String permission) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("company_name", name);
        row.put("company_address", address);
        row.put("company_city", city);
        row.put("company_state", state);
        row.put("company_zip", zip);
        row.put("has_given_contact_permission", permission);
        return row;
    }

    /** First data row of {@code companies.csv}; its empty last column reads as {@code ""}. */
    private static Map<String, String> universalExports() {
        return row("Universal Exports", "55 Main St", "Miami", "fl", "33126", "");
    }

    /** Second data row of {@code companies.csv}. */
    private static Map<String, String> bestWidgets() {
        return row("Best Widgets", "1966 Latrobe Rd", "El Dorado Hills", "CA", "95762", "");
    }

    /** The Account record the script produces for the given values, in its field order. */
    private static Map<String, Object> account(String name, String street, String city, String state,
            String postalCode, String region) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("Name", name);
        account.put("BillingStreet", street);
        account.put("BillingCity", city);
        account.put("BillingState", state);
        account.put("BillingPostalCode", postalCode);
        account.put("Region__c", region);
        return account;
    }

    /**
     * Asserts the record is a {@link LinkedHashMap} holding exactly the six fields in script order with the given
     * values; the key order is checked separately because {@link Map#equals(Object)} ignores it.
     */
    private static void assertAccount(Map<String, Object> actual, String name, String street, String city,
            String state, String postalCode, String region) {
        assertThat(actual).isInstanceOf(LinkedHashMap.class);
        assertThat(new ArrayList<>(actual.keySet())).containsExactly(
                "Name", "BillingStreet", "BillingCity", "BillingState", "BillingPostalCode", "Region__c");
        assertThat(actual).isEqualTo(account(name, street, city, state, postalCode, region));
    }
}
