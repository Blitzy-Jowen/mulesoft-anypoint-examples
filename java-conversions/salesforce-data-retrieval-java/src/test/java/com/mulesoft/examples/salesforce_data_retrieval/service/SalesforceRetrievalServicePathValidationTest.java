/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link SalesforceRetrievalService} against a Mockito-mocked {@link SalesforcePartnerClient}, with the
 * real {@link SObjectOptionsMapper}, {@link QueryResultFormatter} and {@link ParseTemplateRenderer}; no Spring context,
 * network or credentials.
 *
 * <p>The describe fixture is a small Partner API organization: {@code User} (with the self-reference
 * {@code ManagerId → Manager → {User}}), {@code Group}, {@code Account} ({@code OwnerId → Owner → {User}}),
 * {@code Contact} ({@code OwnerId → Owner → {User}}, {@code AccountId → Account → {Account}}), {@code Lead} with the
 * polymorphic {@code OwnerId → Owner → {Group, User}}, and {@code Orphan}, {@code Hollow} and {@code Broken}, whose
 * relationships and describes are degenerate. {@code describeSObject} answers case-insensitively, as Salesforce does,
 * and the exact SOQL is read from the {@code queryAll} argument.
 *
 * <p>It fixes: the original MEL-built SOQL for legitimate forms, relationship paths included; the escaping of
 * {@code searchValue} alone; the rejection, before any query, of every object, search key or field entry Salesforce
 * does not describe; the hop limit and the one-describe-per-type rule of the path walk; the unchanged propagation of
 * every client failure; the absent-{@code field} and absent-value semantics; and the form page, the returned text and
 * the {@code Result:} INFO line.
 */
@ExtendWith(MockitoExtension.class)
class SalesforceRetrievalServicePathValidationTest {

    private static final String TEMPLATE = "retrieval/index.html";

    private static final String PAYLOAD_EXPRESSION = "#[message.payload]";

    /** Two {@code user} records as the original IT's {@code reply.txt} lists them, in a fixed key order. */
    private static final List<Map<String, Object>> RECORDS = List.of(
            record("kicks+a@mulesoft.com", "00520000003LtYCAA0", "Mule orgA"),
            record("kicks+a-integration@mulesoft.com", "00520000003LtvGAAS", "Mule orgA Integration"));

    private static final String RECORDS_TEXT = "[{type=User, Email=kicks+a@mulesoft.com, Id=00520000003LtYCAA0, "
            + "Name=Mule orgA}, {type=User, Email=kicks+a-integration@mulesoft.com, Id=00520000003LtvGAAS, "
            + "Name=Mule orgA Integration}]";

    /** A path of exactly five relationships, SOQL's limit: Owner, then Manager four times. */
    private static final String FIVE_HOPS = "Owner.Manager.Manager.Manager.Manager.Name";

    /** A path of six relationships, one more than SOQL allows. */
    private static final String SIX_HOPS = "Owner.Manager.Manager.Manager.Manager.Manager.Name";

    @Mock
    private SalesforcePartnerClient client;

    @Spy
    private ParseTemplateRenderer renderer = new ParseTemplateRenderer();

    private final QueryResultFormatter formatter = new QueryResultFormatter();

    /** This test's describe results by lower-cased type name; a type absent here is described as {@code null}. */
    private final Map<String, DescribeSObjectResult> describes = new HashMap<>();

    /** The object names {@code describeGlobal} lists, in order; a {@code null} stands for a {@code null} element. */
    private final List<String> globalNames = new ArrayList<>();

    private SalesforceRetrievalService service;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        sobject("User", field("Id"), field("Name"), field("Email"), field("Username"), null,
                reference("ManagerId", "Manager", "User"));
        sobject("Group", field("Id"), field("Name"), field("DeveloperName"));
        sobject("Account", field("Id"), field("Name"), reference("OwnerId", "Owner", "User"));
        sobject("Contact", field("Id"), field("Name"), field("Email"), reference("OwnerId", "Owner", "User"),
                reference("AccountId", "Account", "Account"));
        sobject("Lead", field("Id"), field("Name"), field("Email"), reference("OwnerId", "Owner", "Group", "User"));
        sobject("Orphan", field("Id"), field("Name"),
                reference("ParentId", "Parent", "Missing"),
                reference("HollowRefId", "HollowRef", "Hollow"),
                reference("GhostId", "Ghost"),
                reference("LooseId", "Loose", (String[]) null),
                reference("BlankId", "Blank", null, ""),
                reference("MixedId", "Mixed", "Missing", "User"));
        DescribeSObjectResult hollow = describe("Hollow");
        hollow.setFields(null);
        describes.put("hollow", hollow);
        globalNames.add("Hollow");
        globalNames.add(null);
        // Listed by describeGlobal, but describeSObject answers null for it, as for the never-listed "Missing".
        globalNames.add("Broken");

        lenient().when(client.describeGlobal()).thenAnswer(invocation -> global(globalNames));
        lenient().when(client.describeSObject(anyString())).thenAnswer(
                invocation -> describes.get(invocation.<String>getArgument(0).toLowerCase(Locale.ROOT)));
        lenient().when(client.queryAll(anyString())).thenReturn(RECORDS);

        service = new SalesforceRetrievalService(client, new SObjectOptionsMapper(new DwXmlWriter()), formatter,
                renderer);

        logger = (Logger) LoggerFactory.getLogger(SalesforceRetrievalService.class);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("legitimateForms")
    void legitimateFormRunsTheOriginalSoql(String description, String object, String field, String searchKey,
            String searchValue, String expectedSoql) throws Exception {
        String text = service.query(form(object, field, searchKey, searchValue));

        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(client).queryAll(soql.capture());
        assertThat(soql.getValue()).isEqualTo(expectedSoql);
        assertThat(text).isEqualTo(RECORDS_TEXT);
    }

    static Stream<Arguments> legitimateForms() {
        return Stream.of(
                Arguments.of("the original IT's form", "user", "email", "name", "mule",
                        "SELECT id, name ,email from user where name like '%mule%'"),
                Arguments.of("an empty field leaves the two-space select list", "User", "", "Name", "mule",
                        "SELECT id, name  from User where Name like '%mule%'"),
                Arguments.of("a comma-separated field list", "User", "email,Name", "Name", "mule",
                        "SELECT id, name ,email,Name from User where Name like '%mule%'"),
                Arguments.of("caller casing and spacing are kept", "uSeR", "EMAIL, userName", "NAME", "Mule",
                        "SELECT id, name ,EMAIL, userName from uSeR where NAME like '%Mule%'"),
                Arguments.of("one relationship", "Contact", "Owner.Name", "Name", "mule",
                        "SELECT id, name ,Owner.Name from Contact where Name like '%mule%'"),
                Arguments.of("two relationships", "Contact", "Account.Owner.Name", "Name", "mule",
                        "SELECT id, name ,Account.Owner.Name from Contact where Name like '%mule%'"),
                Arguments.of("a path in other casing", "contact", "owner.NAME", "name", "mule",
                        "SELECT id, name ,owner.NAME from contact where name like '%mule%'"),
                Arguments.of("a path as searchKey", "Contact", "Email", "Owner.Name", "mule",
                        "SELECT id, name ,Email from Contact where Owner.Name like '%mule%'"),
                Arguments.of("polymorphic leaf only the second target has", "Lead", "Owner.Email", "Name", "mule",
                        "SELECT id, name ,Owner.Email from Lead where Name like '%mule%'"),
                Arguments.of("polymorphic leaf only the first target has", "Lead", "Owner.DeveloperName", "Name",
                        "mule", "SELECT id, name ,Owner.DeveloperName from Lead where Name like '%mule%'"),
                Arguments.of("polymorphic relationship with one target described as null", "Orphan", "Mixed.Name",
                        "Name", "mule", "SELECT id, name ,Mixed.Name from Orphan where Name like '%mule%'"),
                Arguments.of("exactly five relationships", "Contact", FIVE_HOPS, "Name", "mule",
                        "SELECT id, name ," + FIVE_HOPS + " from Contact where Name like '%mule%'"),
                Arguments.of("quote and backslash of searchValue escaped", "User", "email", "Name", "O'Brien\\",
                        "SELECT id, name ,email from User where Name like '%O\\'Brien\\\\%'"),
                Arguments.of("backslash escaped before quote", "User", "email", "Name", "\\'",
                        "SELECT id, name ,email from User where Name like '%\\\\\\'%'"),
                Arguments.of("quote injection stays inside the literal", "User", "email", "Name", "' OR Name != '",
                        "SELECT id, name ,email from User where Name like '%\\' OR Name != \\'%'"),
                Arguments.of("LIKE wildcards stay wildcards", "User", "email", "Name", "50%_off",
                        "SELECT id, name ,email from User where Name like '%50%_off%'"),
                Arguments.of("empty searchValue", "User", "email", "Name", "",
                        "SELECT id, name ,email from User where Name like '%%'"),
                Arguments.of("absent searchValue renders as null, as MEL concatenation did", "User", "email", "Name",
                        null, "SELECT id, name ,email from User where Name like '%null%'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedForms")
    void identifierSalesforceDoesNotDescribeIsRejectedBeforeAnyQuery(String description, String object,
            String field, String searchKey, String expectedMessage) throws Exception {
        Throwable thrown = catchThrowable(() -> service.query(form(object, field, searchKey, "mule")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
        verify(client, never()).queryAll(any());
        assertThat(appender.list).isEmpty();
    }

    static Stream<Arguments> rejectedForms() {
        return Stream.of(
                Arguments.of("unknown object", "Nonexistent", "email", "Name",
                        "Unknown Salesforce object: Nonexistent"),
                Arguments.of("absent object", null, "email", "Name", "Unknown Salesforce object: null"),
                Arguments.of("SOQL in the object", "User where Name != ''", "", "Name",
                        "Unknown Salesforce object: User where Name != ''"),
                Arguments.of("listed object described as null", "Broken", "", "Name", "Unknown field: Name"),
                Arguments.of("object whose describe has no field list", "Hollow", "", "Name", "Unknown field: Name"),
                Arguments.of("unknown searchKey", "User", "email", "Phone", "Unknown field: Phone"),
                Arguments.of("absent searchKey", "User", "email", null, "Unknown field: null"),
                Arguments.of("SOQL in the searchKey", "User", "email", "Name like '%' OR Name",
                        "Unknown field: Name like '%' OR Name"),
                Arguments.of("unknown field", "User", "Phone", "Name", "Invalid field list: Phone"),
                Arguments.of("unknown entry of a field list", "User", "email,Phone", "Name",
                        "Invalid field list: email,Phone"),
                Arguments.of("empty last entry", "User", "email,", "Name", "Invalid field list: email,"),
                Arguments.of("empty first entry", "User", ",email", "Name", "Invalid field list: ,email"),
                Arguments.of("empty middle entry", "User", "email,,Name", "Name", "Invalid field list: email,,Name"),
                Arguments.of("blank field", "User", " ", "Name", "Invalid field list:  "),
                Arguments.of("SOQL in the field", "User", "email from User where Name != null or Name", "Name",
                        "Invalid field list: email from User where Name != null or Name"),
                Arguments.of("subquery in a field list", "Contact", "Email, (SELECT Id FROM Cases)", "Name",
                        "Invalid field list: Email, (SELECT Id FROM Cases)"),
                Arguments.of("unknown leaf after a relationship", "Contact", "Owner.Nonexistent", "Name",
                        "Invalid field list: Owner.Nonexistent"),
                Arguments.of("unknown segment inside a path", "Contact", "Owner.Nonexistent.Other", "Name",
                        "Invalid field list: Owner.Nonexistent.Other"),
                Arguments.of("unknown path as searchKey", "Contact", "Email", "Owner.Nonexistent",
                        "Unknown field: Owner.Nonexistent"),
                Arguments.of("relationship name used as a field", "Contact", "Owner", "Name",
                        "Invalid field list: Owner"),
                Arguments.of("path ending at a relationship", "Contact", "Account.Owner", "Name",
                        "Invalid field list: Account.Owner"),
                Arguments.of("leaf of a type the relationship does not refer to", "Contact", "Owner.DeveloperName",
                        "Name", "Invalid field list: Owner.DeveloperName"),
                Arguments.of("polymorphic relationship none of whose targets has the leaf", "Lead",
                        "Owner.Nonexistent", "Name", "Invalid field list: Owner.Nonexistent"),
                Arguments.of("relationship to a type described as null", "Orphan", "Parent.Name", "Name",
                        "Invalid field list: Parent.Name"),
                Arguments.of("relationship to a type without a field list", "Orphan", "HollowRef.Name", "Name",
                        "Invalid field list: HollowRef.Name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pathsThatReachNoRelationshipTarget")
    void pathIsRejectedWithoutDescribingAnyTypeBeyondTheQueriedObject(String description, String object,
            String field, String searchKey, String expectedMessage) throws Exception {
        Throwable thrown = catchThrowable(() -> service.query(form(object, field, searchKey, "mule")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
        verify(client).describeGlobal();
        verify(client).describeSObject(object);
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> pathsThatReachNoRelationshipTarget() {
        return Stream.of(
                Arguments.of("six relationships as a field", "Contact", SIX_HOPS, "Name",
                        "Invalid field list: " + SIX_HOPS),
                Arguments.of("six relationships through two types", "Contact",
                        "Account.Owner.Manager.Manager.Manager.Manager.Name", "Name",
                        "Invalid field list: Account.Owner.Manager.Manager.Manager.Manager.Name"),
                Arguments.of("six relationships as searchKey", "Contact", "", SIX_HOPS, "Unknown field: " + SIX_HOPS),
                Arguments.of("empty leaf", "Contact", "Owner.", "Name", "Invalid field list: Owner."),
                Arguments.of("SOQL after a path", "Contact", "", "Owner.Name like '%' OR Name",
                        "Unknown field: Owner.Name like '%' OR Name"),
                Arguments.of("leaf starting with a digit", "Contact", "Owner.1Name", "Name",
                        "Invalid field list: Owner.1Name"),
                Arguments.of("leaf with punctuation", "Contact", "Owner.Na-me", "Name",
                        "Invalid field list: Owner.Na-me"),
                Arguments.of("unknown relationship", "Contact", "Nonexistent.Name", "Name",
                        "Invalid field list: Nonexistent.Name"),
                Arguments.of("empty relationship", "Contact", ".Name", "Name", "Invalid field list: .Name"),
                Arguments.of("path through a field that is no reference", "Contact", "Email.Name", "Name",
                        "Invalid field list: Email.Name"),
                Arguments.of("relationship with an empty referenceTo", "Orphan", "Ghost.Name", "Name",
                        "Invalid field list: Ghost.Name"),
                Arguments.of("relationship with a null referenceTo", "Orphan", "Loose.Name", "Name",
                        "Invalid field list: Loose.Name"),
                Arguments.of("relationship whose referenceTo holds only null and empty names", "Orphan",
                        "Blank.Name", "Name", "Invalid field list: Blank.Name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("globalsWithoutObjects")
    void describeGlobalWithoutObjectsKnowsNoObject(String description, DescribeGlobalResult global)
            throws Exception {
        doReturn(global).when(client).describeGlobal();

        Throwable thrown = catchThrowable(() -> service.query(form("User", "email", "Name", "mule")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown Salesforce object: User");
        verify(client).describeGlobal();
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> globalsWithoutObjects() {
        DescribeGlobalResult nullObjects = new DescribeGlobalResult();
        nullObjects.setSobjects(null);
        return Stream.of(
                Arguments.of("null result", null),
                Arguments.of("null object list", nullObjects),
                Arguments.of("empty object list", new DescribeGlobalResult()));
    }

    @Test
    void eachTypeIsDescribedOncePerRequestHoweverOftenThePathsRevisitIt() throws Exception {
        service.query(form("Contact", "Account.Owner.Email," + FIVE_HOPS + ",Owner.Username", "Owner.Name", "mule"));

        verify(client).describeSObject("Contact");
        verify(client).describeSObject("Account");
        verify(client).describeSObject("User");
        verify(client, times(3)).describeSObject(anyString());
        verify(client).queryAll("SELECT id, name ,Account.Owner.Email," + FIVE_HOPS
                + ",Owner.Username from Contact where Owner.Name like '%mule%'");
    }

    @Test
    void everyTargetOfAPolymorphicRelationshipIsDescribedOncePerRequest() throws Exception {
        service.query(form("Lead", "Owner.Email,Owner.DeveloperName", "Owner.Name", "mule"));

        verify(client).describeSObject("Lead");
        verify(client).describeSObject("Group");
        verify(client).describeSObject("User");
        verify(client, times(3)).describeSObject(anyString());
    }

    @Test
    void queriedObjectIsNotDescribedAgainWhenAPathReachesItUnderOtherCasing() throws Exception {
        service.query(form("user", "Manager.Manager.Email", "Manager.Name", "mule"));

        verify(client).describeSObject("user");
        verify(client, times(1)).describeSObject(anyString());
        verify(client).queryAll("SELECT id, name ,Manager.Manager.Email from user where Manager.Name like '%mule%'");
    }

    @Test
    void describesAreReadAfreshOnEveryRequest() throws Exception {
        Map<String, String> form = form("Contact", "Owner.Name", "Name", "mule");

        service.query(form);
        service.query(form);

        verify(client, times(2)).describeGlobal();
        verify(client, times(2)).describeSObject("Contact");
        verify(client, times(2)).describeSObject("User");
        verify(client, times(2)).queryAll("SELECT id, name ,Owner.Name from Contact where Name like '%mule%'");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formsWithoutField")
    void absentFieldFailsBeforeSalesforceIsCalled(String description, Map<String, String> form) {
        Throwable thrown = catchThrowable(() -> service.query(form));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("Form field 'field' is missing");
        verifyNoInteractions(client);
        assertThat(appender.list).isEmpty();
    }

    static Stream<Arguments> formsWithoutField() {
        return Stream.of(
                Arguments.of("no form", null),
                Arguments.of("empty form", Map.of()),
                Arguments.of("every value but field", form("user", null, "name", "mule")));
    }

    @ParameterizedTest(name = "{1} from {0}")
    @MethodSource("clientFailures")
    void failureOfASalesforceCallPropagatesUnchanged(FailingCall call, Exception failure) throws Exception {
        call.arrange(client, failure);

        Throwable thrown = catchThrowable(() -> service.query(form("Contact", "Owner.Name", "Name", "mule")));

        assertThat(thrown).isSameAs(failure);
        if (call != FailingCall.QUERY_ALL) {
            verify(client, never()).queryAll(any());
        }
        assertThat(appender.list).isEmpty();
    }

    static Stream<Arguments> clientFailures() {
        return Stream.of(FailingCall.values()).flatMap(call -> failures().map(failure -> Arguments.of(call, failure)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void formPropagatesADescribeGlobalFailureUnchanged(Exception failure) throws Exception {
        doThrow(failure).when(client).describeGlobal();

        Throwable thrown = catchThrowable(() -> service.showForm());

        assertThat(thrown).isSameAs(failure);
        verifyNoInteractions(renderer);
    }

    static Stream<Exception> failures() {
        return Stream.of(new ConnectionException("Salesforce connection reset"),
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Salesforce is unavailable", null));
    }

    @Test
    void formRendersTheTemplateWithTheOptionListAsItsOnlyExpression() throws Exception {
        doReturn(global(List.of("Account", "Contact", "User"))).when(client).describeGlobal();
        String options = "<?xml version='1.0' encoding='UTF-8'?>\n<div>\n"
                + "\t<option value=\"Account\">Account</option>\n"
                + "\t<option value=\"Contact\">Contact</option>\n"
                + "\t<option value=\"User\">User</option>\n"
                + "</div>";
        String template = classpathText(TEMPLATE);

        String page = service.showForm();

        verify(renderer).render(TEMPLATE, Map.of(PAYLOAD_EXPRESSION, options));
        assertThat(template.split(Pattern.quote(PAYLOAD_EXPRESSION), -1)).hasSize(2);
        assertThat(page).isEqualTo(template.replace(PAYLOAD_EXPRESSION, options));
        verify(client).describeGlobal();
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryResults")
    void queryReturnsTheFormattedRecordsAndLogsThemAtInfo(String description, List<Map<String, Object>> records,
            String expectedText) throws Exception {
        doReturn(records).when(client).queryAll(anyString());

        String text = service.query(form("user", "email", "name", "mule"));

        assertThat(text).isEqualTo(expectedText).isEqualTo(formatter.format(records));
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("Result: " + expectedText);
        });
    }

    static Stream<Arguments> queryResults() {
        return Stream.of(
                Arguments.of("two records", RECORDS, RECORDS_TEXT),
                Arguments.of("no records", List.of(), "[]"),
                Arguments.of("null record list", null, "[]"));
    }

    /** The client call that fails in {@link #failureOfASalesforceCallPropagatesUnchanged}. */
    enum FailingCall {
        DESCRIBE_GLOBAL {
            @Override
            void arrange(SalesforcePartnerClient client, Exception failure) throws Exception {
                doThrow(failure).when(client).describeGlobal();
            }
        },
        DESCRIBE_QUERIED_OBJECT {
            @Override
            void arrange(SalesforcePartnerClient client, Exception failure) throws Exception {
                doThrow(failure).when(client).describeSObject("Contact");
            }
        },
        DESCRIBE_RELATIONSHIP_TARGET {
            @Override
            void arrange(SalesforcePartnerClient client, Exception failure) throws Exception {
                doThrow(failure).when(client).describeSObject("User");
            }
        },
        QUERY_ALL {
            @Override
            void arrange(SalesforcePartnerClient client, Exception failure) throws Exception {
                doThrow(failure).when(client).queryAll(anyString());
            }
        };

        abstract void arrange(SalesforcePartnerClient client, Exception failure) throws Exception;
    }

    /** A posted form holding only the values that are not {@code null}, as a form without those inputs. */
    private static Map<String, String> form(String object, String field, String searchKey, String searchValue) {
        Map<String, String> form = new HashMap<>();
        putPresent(form, "object", object);
        putPresent(form, "field", field);
        putPresent(form, "searchKey", searchKey);
        putPresent(form, "searchValue", searchValue);
        return form;
    }

    private static void putPresent(Map<String, String> form, String key, String value) {
        if (value != null) {
            form.put(key, value);
        }
    }

    /** Registers a described type and lists it in {@code describeGlobal}. */
    private void sobject(String name, Field... fields) {
        describes.put(name.toLowerCase(Locale.ROOT), describe(name, fields));
        globalNames.add(name);
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

    private static DescribeGlobalResult global(List<String> names) {
        DescribeGlobalSObjectResult[] sobjects = new DescribeGlobalSObjectResult[names.size()];
        for (int i = 0; i < sobjects.length; i++) {
            String name = names.get(i);
            if (name != null) {
                sobjects[i] = new DescribeGlobalSObjectResult();
                sobjects[i].setName(name);
                sobjects[i].setLabel(name);
            }
        }
        DescribeGlobalResult global = new DescribeGlobalResult();
        global.setSobjects(sobjects);
        return global;
    }

    private static Map<String, Object> record(String email, String id, String name) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "User");
        record.put("Email", email);
        record.put("Id", id);
        record.put("Name", name);
        return record;
    }

    private static String classpathText(String location) throws IOException {
        try (InputStream in = SalesforceRetrievalServicePathValidationTest.class.getClassLoader()
                .getResourceAsStream(location)) {
            assertThat(in).as("classpath resource %s", location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
