/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.mulesoft.examples.salesforce_data_retrieval.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_retrieval.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.DwXmlWriter;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.QueryResultFormatter;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.SObjectOptionsMapper;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link SalesforceRetrievalService}, the two flows of {@code salesforce-id-retrieval.xml}, against a
 * Mockito-mocked {@link SalesforcePartnerClient} and the real {@link SObjectOptionsMapper} and
 * {@link QueryResultFormatter}; no Spring context, network or credentials.
 *
 * <p>The organization is the one the original test queried: {@code User}, {@code Account} and {@code Contact}, whose
 * {@code OwnerId} refers to {@code User} through the relationship {@code Owner}. The records are the two of the
 * original {@code reply.txt}, built as {@code SalesforcePartnerClient.toMap} builds them, {@code HashMap}s carrying
 * the sObject {@code type}.
 *
 * <p>It fixes the flow contract: the original MEL-concatenated SOQL for legitimate forms, the escaping of
 * {@code searchValue} alone, the rejection of identifiers Salesforce does not describe before any query, the response
 * text and its {@code Result:} INFO line, the form page, and the unchanged propagation of client failures. The
 * relationship-path walk in depth (hop limit, one describe per type, polymorphic and degenerate describes) is
 * {@link SalesforceRetrievalServicePathValidationTest}'s and is not repeated here.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
// Lenient: setUp stubs the whole organization, and a rejected form never reaches describeSObject or queryAll.
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesforceRetrievalServiceTest {

    private static final String TEMPLATE = "retrieval/index.html";

    private static final String PAYLOAD_EXPRESSION = "#[message.payload]";

    /** The six lines of the original test's {@code reply.txt}; the response must contain each of them. */
    private static final String[] REPLY_LINES = {
        "Email=kicks+a@mulesoft.com", "Id=00520000003LtYCAA0", "Name=Mule orgA",
        "Email=kicks+a-integration@mulesoft.com", "Id=00520000003LtvGAAS", "Name=Mule orgA Integration"};

    /** The two records rendered as the original list of {@code HashMap}s, in their iteration order. */
    private static final String RECORDS_TEXT =
            "[{Email=kicks+a@mulesoft.com, Id=00520000003LtYCAA0, type=User, Name=Mule orgA}, "
            + "{Email=kicks+a-integration@mulesoft.com, Id=00520000003LtvGAAS, type=User, "
            + "Name=Mule orgA Integration}]";

    @Mock
    private SalesforcePartnerClient client;

    @Mock
    private ParseTemplateRenderer renderer;

    private DescribeGlobalResult global;

    private SalesforceRetrievalService service;

    @BeforeEach
    void setUp() throws Exception {
        global = global("User", "Account", "Contact");
        Map<String, DescribeSObjectResult> describes = Map.of(
                "user", describe("User", field("Id"), field("Name"), field("Email"), field("Username")),
                "account", describe("Account", field("Id"), field("Name")),
                "contact", describe("Contact", field("Id"), field("Name"), field("Email"),
                        reference("OwnerId", "Owner", "User")));
        when(client.describeGlobal()).thenReturn(global);
        // Salesforce matches type names case-insensitively; a type it does not know is described as null.
        when(client.describeSObject(anyString())).thenAnswer(
                invocation -> describes.get(invocation.<String>getArgument(0).toLowerCase(Locale.ROOT)));
        when(client.queryAll(anyString())).thenReturn(userRecords());
        service = service(renderer);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legitimateForms")
    void formRunsTheOriginalConcatenatedSoql(String description, Map<String, String> form, String expectedSoql)
            throws Exception {
        service.query(form);

        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(client).queryAll(soql.capture());
        assertThat(soql.getValue()).isEqualTo(expectedSoql);
    }

    static Stream<Arguments> legitimateForms() {
        return Stream.of(
                Arguments.of("the original test's form", form("user", "email", "name", "mule"),
                        "SELECT id, name ,email from user where name like '%mule%'"),
                Arguments.of("an empty field leaves two spaces before from", form("User", "", "Name", "mule"),
                        "SELECT id, name  from User where Name like '%mule%'"),
                Arguments.of("a comma-separated field list is concatenated unchanged",
                        form("user", "email,Name", "name", "mule"),
                        "SELECT id, name ,email,Name from user where name like '%mule%'"),
                Arguments.of("the caller's casing is kept", form("USER", "EMAIL", "NAME", "mule"),
                        "SELECT id, name ,EMAIL from USER where NAME like '%mule%'"),
                Arguments.of("quote and backslash of searchValue are escaped",
                        form("user", "email", "name", "O'Brien\\"),
                        "SELECT id, name ,email from user where name like '%O\\'Brien\\\\%'"),
                Arguments.of("LIKE wildcards are not escaped", form("user", "email", "name", "a%b_c"),
                        "SELECT id, name ,email from user where name like '%a%b_c%'"),
                Arguments.of("an absent searchValue renders as null, as MEL concatenation did",
                        form("user", "email", "name", null),
                        "SELECT id, name ,email from user where name like '%null%'"));
    }

    @Test
    void relationshipPathIsResolvedThroughTheDescribeOfTheObjectItRefersTo() throws Exception {
        service.query(form("Contact", "Owner.Name", "Name", "mule"));

        verify(client).describeSObject("Contact");
        verify(client).describeSObject("User");
        verify(client).queryAll("SELECT id, name ,Owner.Name from Contact where Name like '%mule%'");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedForms")
    void formSalesforceDoesNotDescribeIsRejectedBeforeAnyQuery(String description, Map<String, String> form,
            String expectedMessage) throws Exception {
        assertThatThrownBy(() -> service.query(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
        verify(client, never()).queryAll(any());
    }

    static Stream<Arguments> rejectedForms() {
        return Stream.of(
                Arguments.of("unknown object", form("Bogus", "email", "name", "mule"),
                        "Unknown Salesforce object: Bogus"),
                Arguments.of("absent object", form(null, "email", "name", "mule"),
                        "Unknown Salesforce object: null"),
                Arguments.of("unknown field", form("user", "bogus", "name", "mule"), "Invalid field list: bogus"),
                Arguments.of("unknown searchKey", form("user", "email", "bogus", "mule"), "Unknown field: bogus"),
                Arguments.of("empty entry of a field list", form("user", "email,", "name", "mule"),
                        "Invalid field list: email,"),
                Arguments.of("empty segment of a relationship path", form("Contact", "Owner.", "Name", "mule"),
                        "Invalid field list: Owner."),
                Arguments.of("leaf the referenced object does not describe",
                        form("Contact", "Owner.Bogus", "Name", "mule"), "Invalid field list: Owner.Bogus"),
                Arguments.of("SOQL injected through searchKey",
                        form("user", "email", "Name like '%' OR Name", "mule"),
                        "Unknown field: Name like '%' OR Name"),
                Arguments.of("absent field, on which the original's payload.field.isEmpty() failed",
                        form("user", null, "name", "mule"), "Form field 'field' is missing"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryResults")
    void queryReturnsTheRecordsAsTheOriginalListTextAndLogsItAtInfo(String description,
            List<Map<String, Object>> records, String expectedText, CapturedOutput output) throws Exception {
        when(client.queryAll(anyString())).thenReturn(records);

        String text = service.query(form("user", "email", "name", "mule"));

        assertThat(text).isEqualTo(expectedText)
                .isEqualTo(new QueryResultFormatter().format(records))
                .isEqualTo(records.toString());
        assertThat(output.getAll().lines())
                .anySatisfy(line -> assertThat(line).contains("INFO").endsWith("Result: " + expectedText));
    }

    static Stream<Arguments> queryResults() {
        return Stream.of(
                Arguments.of("the original test's two User records", userRecords(), RECORDS_TEXT),
                Arguments.of("no records", List.of(), "[]"));
    }

    @Test
    void responseContainsEveryLineTheOriginalTestExpects() throws Exception {
        assertThat(service.query(form("user", "email", "name", "mule"))).contains(REPLY_LINES);
    }

    @Test
    void formRendersTheTemplateWithTheOptionListAsItsOnlyExpression() throws Exception {
        when(renderer.render(eq(TEMPLATE), any())).thenReturn("<html>rendered</html>");

        String page = service.showForm();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> values = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(TEMPLATE), values.capture());
        assertThat(page).isEqualTo("<html>rendered</html>");
        assertThat(values.getValue()).containsOnlyKeys(PAYLOAD_EXPRESSION);
        assertThat(values.getValue().get(PAYLOAD_EXPRESSION))
                .isEqualTo(new SObjectOptionsMapper(new DwXmlWriter()).toOptionsXml(global));
        verify(client).describeGlobal();
        verifyNoMoreInteractions(client);
    }

    @Test
    void formPageFromTheCopiedTemplateListsEverySalesforceObject() throws Exception {
        String page = service(new ParseTemplateRenderer()).showForm();

        assertThat(page)
                .contains("<select name=\"object\" >", "<option value=\"User\">User</option>",
                        "<option value=\"Account\">Account</option>", "<option value=\"Contact\">Contact</option>")
                .doesNotContain(PAYLOAD_EXPRESSION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientFailures")
    void describeGlobalFailureReachesTheCallerOfShowFormUnchanged(Exception failure) throws Exception {
        doThrow(failure).when(client).describeGlobal();

        assertThatThrownBy(service::showForm).isSameAs(failure);
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientFailures")
    void queryAllFailureReachesTheCallerOfQueryUnchanged(Exception failure, CapturedOutput output) throws Exception {
        doThrow(failure).when(client).queryAll(anyString());

        assertThatThrownBy(() -> service.query(form("user", "email", "name", "mule"))).isSameAs(failure);
        assertThat(output.getAll()).doesNotContain("Result: ");
    }

    /** An SC-9 upstream exception and a checked Partner API exception, neither of which the service may wrap. */
    static Stream<Exception> clientFailures() {
        return Stream.of(
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                        new SocketTimeoutException("Read timed out")),
                new ConnectionException("connection reset"));
    }

    private SalesforceRetrievalService service(ParseTemplateRenderer templateRenderer) {
        return new SalesforceRetrievalService(client, new SObjectOptionsMapper(new DwXmlWriter()),
                new QueryResultFormatter(), templateRenderer);
    }

    /** A posted form holding only the values that are not {@code null}, as a form without those inputs. */
    private static Map<String, String> form(String object, String field, String searchKey, String searchValue) {
        Map<String, String> form = new HashMap<>();
        form.put("object", object);
        form.put("field", field);
        form.put("searchKey", searchKey);
        form.put("searchValue", searchValue);
        form.values().removeIf(Objects::isNull);
        return form;
    }

    private static List<Map<String, Object>> userRecords() {
        return List.of(
                userRecord("kicks+a@mulesoft.com", "00520000003LtYCAA0", "Mule orgA"),
                userRecord("kicks+a-integration@mulesoft.com", "00520000003LtvGAAS", "Mule orgA Integration"));
    }

    private static Map<String, Object> userRecord(String email, String id, String name) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "User");
        record.put("Email", email);
        record.put("Id", id);
        record.put("Name", name);
        return record;
    }

    private static DescribeGlobalResult global(String... names) {
        DescribeGlobalSObjectResult[] sobjects = new DescribeGlobalSObjectResult[names.length];
        for (int i = 0; i < names.length; i++) {
            sobjects[i] = new DescribeGlobalSObjectResult();
            sobjects[i].setName(names[i]);
            sobjects[i].setLabel(names[i]);
        }
        DescribeGlobalResult result = new DescribeGlobalResult();
        result.setSobjects(sobjects);
        return result;
    }

    private static DescribeSObjectResult describe(String name, Field... fields) {
        DescribeSObjectResult describe = new DescribeSObjectResult();
        describe.setName(name);
        describe.setFields(fields);
        return describe;
    }

    private static Field field(String name) {
        Field field = new Field();
        field.setName(name);
        return field;
    }

    private static Field reference(String name, String relationshipName, String... referenceTo) {
        Field field = field(name);
        field.setRelationshipName(relationshipName);
        field.setReferenceTo(referenceTo);
        return field;
    }
}
