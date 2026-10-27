/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient.Session;
import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient.SessionSource;
import com.mulesoft.examples.import_contacts_into_salesforce.config.SalesforceProperties;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.fault.LoginFault;
import com.sforce.soap.partner.fault.UnexpectedErrorFault;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.bind.TypeMapper;
import com.sforce.ws.bind.XMLizable;
import com.sforce.ws.parser.XmlOutputStream;

/**
 * SC-15 reference test (AAP 0.4.4); copied to every Salesforce project with only the package line changed; offline
 * (WireMock).
 *
 * <p>Drives a real {@link SalesforcePartnerClient} over the Salesforce WSC library against a WireMock server that
 * answers the Partner API SOAP {@code login} at {@link #LOGIN_PATH} and every other operation at the instance
 * endpoint {@link #API_PATH} the login returns, so login and API traffic are told apart by URL and every request is
 * counted exactly. The client runs behind a real {@link UpstreamCallExecutor} with the real
 * {@link SalesforceFailureClassifier}, which proves the approved failure handling end to end: one re-login and one
 * retry after {@code INVALID_SESSION_ID}, no retry after {@code REQUEST_LIMIT_EXCEEDED}, and no retry, ever, after a
 * read timeout or a refused connection.
 *
 * <p>Both connection modes are covered: login mode (no {@link SessionSource} bean, credentials from
 * {@link SalesforceProperties}) and session mode (a {@link SessionSource} bean, as in
 * {@code authenticating-salesforce-using-oauth2}). The test uses no project controller, service, mapper or handler,
 * no Spring context and no mocks, so it runs unchanged in every Salesforce project.
 *
 * <p>Small responses are literal XML. The describe responses are produced by WSC itself from populated result
 * beans ({@link #describeResponse(String, XMLizable)}), because the parser rejects a response that lacks any of the
 * many elements those types require. The two {@code User} rows are those of the original example's
 * {@code reply.txt}.
 */
class SalesforcePartnerClientTest {

    /** The Partner API login endpoint path of API 65.0. */
    private static final String LOGIN_PATH = "/services/Soap/u/65.0";

    /** The instance endpoint path the login returns as {@code serverUrl}. */
    private static final String API_PATH = "/services/Soap/u/65.0/00Dxx0000001gEREAY";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String PARTNER_NS = "urn:partner.soap.sforce.com";

    private static final String SOBJECT_NS = "urn:sobject.partner.soap.sforce.com";

    private static final String FAULT_NS = "urn:fault.partner.soap.sforce.com";

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String TEXT_XML = "text/xml; charset=utf-8";

    private static final String USERNAME = "mule@example.com";

    private static final String PASSWORD = "s3cret";

    private static final String SECURITY_TOKEN = "TOKEN123";

    private static final String SESSION_1 = "00Dxx!SESSION-1";

    private static final String SESSION_2 = "00Dxx!SESSION-2";

    private static final String QUERY_LOCATOR = "01gxx0000000001-2000";

    private static final String CONTACT_ID = "003xx000004TmiQAAS";

    /** The {@code application.yml} key of the connect timeout. */
    private static final String CONNECT_TIMEOUT_KEY = "sfdc.connect-timeout";

    /** The {@code application.yml} key of the read timeout. */
    private static final String READ_TIMEOUT_KEY = "sfdc.read-timeout";

    /** The query of the original flow's POST {@code /} for {@code object=user}, {@code field=email}. */
    private static final String SOQL = "SELECT id, name, email from user where name like '%mule%'";

    private static final String INVALID_SESSION_MESSAGE =
            "Invalid Session ID found in SessionHeader: Illegal Session";

    private static final String[] USER_A = {"00520000003LtYCAA0", "Mule orgA", "kicks+a@mulesoft.com"};

    private static final String[] USER_B = {"00520000003LtvGAAS", "Mule orgA Integration",
            "kicks+a-integration@mulesoft.com"};

    private WireMockServer wm;

    private SalesforceProperties props;

    private UpstreamCallExecutor executor;

    @BeforeEach
    void startUpstream() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        props = new SalesforceProperties();
        props.setUsername(USERNAME);
        props.setPassword(PASSWORD);
        props.setSecurityToken(SECURITY_TOKEN);
        props.setAuthEndpoint("http://localhost:" + wm.port() + LOGIN_PATH);
        // Generous, so a slow first exchange on a loaded host never times out an ordinary stub and the suite is not
        // load-sensitive; a test of timeout behaviour sets its own short read timeout before it builds its client.
        props.setReadTimeout(Duration.ofSeconds(5));
        props.setConnectTimeout(Duration.ofSeconds(2));
        executor = new UpstreamCallExecutor(List.of(new SalesforceFailureClassifier()));
    }

    @AfterEach
    void stopUpstream() {
        wm.stop();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Login mode
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void logsInOnceWithUsernameAndPasswordFollowedBySecurityToken() throws Exception {
        stubLogin();
        stubApi("describeGlobal", soap(describeGlobalResponse()));

        loginModeClient().describeGlobal();

        List<LoggedRequest> logins = loginRequests();
        assertThat(logins).hasSize(1);
        assertThat(logins.get(0).getBodyAsString())
                .contains(">" + USERNAME + "<")
                .contains(">" + PASSWORD + SECURITY_TOKEN + "<");
    }

    @Test
    void queryAllReadsEveryPageThroughTheSessionAndMapsRecordsToHashMaps() throws Exception {
        stubLogin();
        stubApi("queryAll", soap(queryResponse("queryAll", false, QUERY_LOCATOR, 2, userRecord(USER_A))));
        stubApi("queryMore", soap(queryResponse("queryMore", true, null, 2, userRecord(USER_B))));

        List<Map<String, Object>> records = loginModeClient().queryAll(SOQL);

        assertThat(loginRequests()).hasSize(1);
        List<LoggedRequest> queryAll = apiRequests("queryAll");
        List<LoggedRequest> queryMore = apiRequests("queryMore");
        assertThat(queryAll).hasSize(1);
        assertThat(queryMore).hasSize(1);
        assertThat(queryAll.get(0).getBodyAsString()).contains(SOQL);
        assertThat(queryMore.get(0).getBodyAsString()).contains(">" + QUERY_LOCATOR + "<");
        assertThat(List.of(queryAll.get(0), queryMore.get(0)))
                .allSatisfy(request -> assertThat(request.getBodyAsString())
                        .contains("SessionHeader")
                        .contains(">" + SESSION_1 + "<"));

        assertThat(records).hasSize(2);
        assertUserRecord(records.get(0), USER_A);
        assertUserRecord(records.get(1), USER_B);
    }

    @Test
    void queryMakesOneRequestAndMapsTheRecordLikeQueryAll() throws Exception {
        stubLogin();
        stubApi("query", soap(queryResponse("query", true, null, 1, userRecord(USER_A))));

        List<Map<String, Object>> records = loginModeClient().query(SOQL);

        assertThat(apiRequests("query")).hasSize(1);
        assertThat(apiRequests("query").get(0).getBodyAsString()).contains(SOQL).contains(">" + SESSION_1 + "<");
        assertThat(records).hasSize(1);
        assertUserRecord(records.get(0), USER_A);
    }

    @Test
    void toMapConvertsRelationshipsSubQueriesAndNilFields() throws Exception {
        String account = "<records xsi:type=\"sf:sObject\"><sf:type>Account</sf:type>"
                + "<sf:Id>001xx000003DGb2AAG</sf:Id><sf:Id>001xx000003DGb2AAG</sf:Id>"
                + "<sf:Name>MuleSoft</sf:Name>"
                + "<sf:Owner xsi:type=\"sf:sObject\"><sf:type>User</sf:type><sf:Id xsi:nil=\"true\"/>"
                + "<sf:Name>" + USER_A[1] + "</sf:Name></sf:Owner>"
                + "<sf:Contacts xsi:type=\"QueryResult\"><done>true</done><queryLocator xsi:nil=\"true\"/>"
                + "<records xsi:type=\"sf:sObject\"><sf:type>Contact</sf:type><sf:Id xsi:nil=\"true\"/>"
                + "<sf:Id>" + CONTACT_ID + "</sf:Id><sf:Email>" + USER_A[2] + "</sf:Email></records>"
                + "<size>1</size></sf:Contacts>"
                + "<sf:Opportunities xsi:type=\"QueryResult\"><done>true</done><queryLocator xsi:nil=\"true\"/>"
                + "<size>0</size></sf:Opportunities>"
                + "<sf:Description xsi:nil=\"true\"/></records>";
        stubLogin();
        stubApi("query", soap(queryResponse("query", true, null, 1, account)));

        List<Map<String, Object>> records = loginModeClient()
                .query("SELECT Id, Name, Owner.Name, (SELECT Id, Email FROM Contacts), (SELECT Id FROM Opportunities),"
                        + " Description FROM Account");

        assertThat(records).hasSize(1);
        Map<String, Object> record = records.get(0);
        assertThat(record).isExactlyInstanceOf(HashMap.class)
                .containsOnlyKeys("type", "Id", "Name", "Owner", "Contacts", "Opportunities", "Description")
                .containsEntry("type", "Account")
                .containsEntry("Id", "001xx000003DGb2AAG")
                .containsEntry("Name", "MuleSoft")
                .containsEntry("Description", null)
                .containsEntry("Opportunities", List.of());
        assertThat(record).extractingByKey("Owner", InstanceOfAssertFactories.map(String.class, Object.class))
                .isExactlyInstanceOf(HashMap.class)
                .containsOnlyKeys("type", "Id", "Name")
                .containsEntry("type", "User")
                .containsEntry("Id", null)
                .containsEntry("Name", USER_A[1]);
        assertThat(record).extractingByKey("Contacts", InstanceOfAssertFactories.list(Object.class))
                .singleElement(InstanceOfAssertFactories.map(String.class, Object.class))
                .isExactlyInstanceOf(HashMap.class)
                .containsOnlyKeys("type", "Id", "Email")
                .containsEntry("type", "Contact")
                .containsEntry("Id", CONTACT_ID)
                .containsEntry("Email", USER_A[2]);
        assertThat(SalesforcePartnerClient.toMap(null)).isNull();
    }

    @Test
    void describeGlobalParsesTheSObjectNamesAndLabels() throws Exception {
        stubLogin();
        stubApi("describeGlobal", soap(describeGlobalResponse()));

        DescribeGlobalResult result = loginModeClient().describeGlobal();

        assertThat(apiRequests("describeGlobal")).hasSize(1);
        assertThat(result.getEncoding()).isEqualTo("UTF-8");
        assertThat(result.getMaxBatchSize()).isEqualTo(200);
        assertThat(result.getSobjects()).extracting(DescribeGlobalSObjectResult::getName)
                .containsExactly("Account", "User");
        assertThat(result.getSobjects()).extracting(DescribeGlobalSObjectResult::getLabel)
                .containsExactly("Account", "User");
    }

    @Test
    void describeSObjectSendsTheTypeAndParsesItsFields() throws Exception {
        stubLogin();
        stubApi("describeSObject", soap(describeUserResponse()));

        DescribeSObjectResult result = loginModeClient().describeSObject("User");

        List<LoggedRequest> requests = apiRequests("describeSObject");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getBodyAsString()).contains(">User<");
        assertThat(result.getName()).isEqualTo("User");
        assertThat(result.getFields()).extracting(Field::getName).containsExactly("Email", "Name");
    }

    @Test
    void createSendsTheRecordFieldsAndParsesTheSaveResult() throws Exception {
        stubLogin();
        stubApi("create", soap(envelope("<createResponse><result><id>" + CONTACT_ID + "</id>"
                + "<success>true</success></result></createResponse>")));
        SalesforcePartnerClient client = loginModeClient();

        assertThat(client.create("Contact", List.of())).isEmpty();
        assertThat(wm.getAllServeEvents()).isEmpty();

        List<SaveResult> results = client.create("Contact", List.of(contact()));

        List<LoggedRequest> requests = apiRequests("create");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getBodyAsString())
                .contains(">Contact<").contains(">John<").contains(">Doe<").contains(">john.doe@example.com<");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getId()).isEqualTo(CONTACT_ID);
            assertThat(result.isSuccess()).isTrue();
        });
    }

    @Test
    void deleteSendsTheIdAndParsesTheDeleteResult() throws Exception {
        stubLogin();
        stubApi("delete", soap(envelope("<deleteResponse><result><id>" + CONTACT_ID + "</id>"
                + "<success>true</success></result></deleteResponse>")));
        SalesforcePartnerClient client = loginModeClient();

        assertThat(client.delete(List.of())).isEmpty();
        assertThat(wm.getAllServeEvents()).isEmpty();

        List<DeleteResult> results = client.delete(List.of(CONTACT_ID));

        List<LoggedRequest> requests = apiRequests("delete");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getBodyAsString()).contains(">" + CONTACT_ID + "<");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getId()).isEqualTo(CONTACT_ID);
            assertThat(result.isSuccess()).isTrue();
        });
    }

    @Test
    void consecutiveCallsReuseTheLazilyCreatedConnection() throws Exception {
        stubLogin();
        stubApi("describeGlobal", soap(describeGlobalResponse()));
        SalesforcePartnerClient client = loginModeClient();

        assertThat(wm.getAllServeEvents()).isEmpty();
        client.describeGlobal();
        client.describeGlobal();

        assertThat(loginRequests()).hasSize(1);
        assertThat(apiRequests("describeGlobal")).hasSize(2);
    }

    @Test
    void reauthenticateLogsInAgainAndTheNextCallUsesTheNewSession() throws Exception {
        stubLoginSequence();
        stubApi("describeGlobal", soap(describeGlobalResponse()));
        SalesforcePartnerClient client = loginModeClient();

        client.describeGlobal();
        client.reauthenticate();
        client.describeGlobal();

        assertThat(loginRequests()).hasSize(2);
        assertThat(requestsCarrying(apiRequests("describeGlobal"), SESSION_1)).hasSize(1);
        assertThat(requestsCarrying(apiRequests("describeGlobal"), SESSION_2)).hasSize(1);
    }

    @Test
    void invalidSessionOnceLogsInAgainAndRetriesTheCallOnce() throws Exception {
        stubLoginSequence();
        stubApiScenario("queryAll",
                fault("INVALID_SESSION_ID", "UnexpectedErrorFault", INVALID_SESSION_MESSAGE),
                soap(queryResponse("queryAll", true, null, 1, userRecord(USER_A))));

        List<Map<String, Object>> records = loginModeClient().queryAll(SOQL);

        assertThat(loginRequests()).hasSize(2);
        List<LoggedRequest> queryAll = apiRequests("queryAll");
        assertThat(queryAll).hasSize(2);
        assertThat(requestsCarrying(queryAll, SESSION_1)).hasSize(1);
        assertThat(requestsCarrying(queryAll, SESSION_2)).hasSize(1);
        assertThat(records).hasSize(1);
        assertUserRecord(records.get(0), USER_A);
    }

    @Test
    void invalidSessionAgainAfterReloginIsAnUpstreamAuthenticationFailure() {
        stubLogin();
        stubApi("queryAll", fault("INVALID_SESSION_ID", "UnexpectedErrorFault", INVALID_SESSION_MESSAGE));

        Throwable thrown = catchThrowable(() -> loginModeClient().queryAll(SOQL));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamAuthenticationException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(e).hasMessage(INVALID_SESSION_MESSAGE);
            assertThat(e.getCause()).isInstanceOfSatisfying(UnexpectedErrorFault.class,
                    fault -> assertThat(fault.getExceptionCode()).isEqualTo(ExceptionCode.INVALID_SESSION_ID));
        });
        assertThat(loginRequests()).hasSize(2);
        assertThat(apiRequests("queryAll")).hasSize(2);
    }

    @Test
    void requestLimitExceededIsARateLimitWithoutRetryOrRetryAfter() {
        stubLogin();
        stubApi("queryAll", fault("REQUEST_LIMIT_EXCEEDED", "UnexpectedErrorFault",
                "TotalRequests Limit exceeded."));

        Throwable thrown = catchThrowable(() -> loginModeClient().queryAll(SOQL));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(e.getRetryAfter()).satisfiesAnyOf(
                    retryAfter -> assertThat(retryAfter).isNull(),
                    retryAfter -> assertThat(retryAfter).isBlank());
        });
        assertThat(loginRequests()).hasSize(1);
        assertThat(apiRequests("queryAll")).hasSize(1);
    }

    @Test
    void readTimeoutIsUnavailableAndTheCreateIsNeverSentAgain() {
        stubLogin();
        stubApi("create", soap(envelope("<createResponse><result><id>" + CONTACT_ID + "</id>"
                + "<success>true</success></result></createResponse>")).withFixedDelay(2000));
        // Well below the 2000 ms delay, so this client's create times out.
        props.setReadTimeout(Duration.ofMillis(500));

        Throwable thrown = catchThrowable(() -> loginModeClient().create("Contact", List.of(contact())));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(causes(e)).anyMatch(SocketTimeoutException.class::isInstance);
        });
        // WireMock may journal a delayed request only once its response is written.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(apiRequests("create")).hasSize(1));
        await().during(Duration.ofMillis(2500)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(apiRequests("create")).hasSize(1));
        assertThat(loginRequests()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyOperation")
    void refusedConnectionIsUnavailableForEveryOperation(ClientCall operation) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SalesforceProperties unreachable = new SalesforceProperties();
        unreachable.setUsername(USERNAME);
        unreachable.setPassword(PASSWORD);
        unreachable.setSecurityToken(SECURITY_TOKEN);
        unreachable.setAuthEndpoint("http://localhost:" + closedPort + LOGIN_PATH);
        unreachable.setConnectTimeout(Duration.ofSeconds(2));
        unreachable.setReadTimeout(Duration.ofMillis(500));
        SalesforcePartnerClient client = new SalesforcePartnerClient(unreachable, executor, noSessionSource());

        Throwable thrown = catchThrowable(() -> operation.invoke(client));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(causes(e)).anyMatch(ConnectException.class::isInstance);
        });
    }

    @Test
    void invalidLoginIsRethrownUnchangedWithoutRetry() {
        wm.stubFor(post(urlEqualTo(LOGIN_PATH)).willReturn(fault("INVALID_LOGIN", "LoginFault",
                "Invalid username, password, security token; or user locked out.")));

        Throwable thrown = catchThrowable(() -> loginModeClient().describeGlobal());

        assertThat(thrown).isInstanceOf(ApiFault.class).isInstanceOfSatisfying(LoginFault.class,
                fault -> assertThat(fault.getExceptionCode()).isEqualTo(ExceptionCode.INVALID_LOGIN));
        assertThat(loginRequests()).hasSize(1);
        assertThat(wm.findAll(postRequestedFor(urlEqualTo(API_PATH)))).isEmpty();
    }

    @Test
    void propertiesDefaultToTheProductionLoginEndpointAndTenSecondTimeouts() {
        SalesforceProperties defaults = new SalesforceProperties();

        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.getAuthEndpoint()).isEqualTo("https://login.salesforce.com/services/Soap/u/65.0");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Session mode
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void sessionModeUsesTheSuppliedSessionWithoutLoggingIn() throws Exception {
        stubApi("queryAll", soap(queryResponse("queryAll", true, null, 1, userRecord(USER_A))));
        TestSessionSource source = new TestSessionSource(apiUrl());

        List<Map<String, Object>> records = sessionModeClient(source).queryAll(SOQL);

        assertThat(loginRequests()).isEmpty();
        assertThat(apiRequests("queryAll")).singleElement().satisfies(request -> assertThat(request.getBodyAsString())
                .contains("SessionHeader")
                .contains(">" + SESSION_1 + "<"));
        assertThat(source.currentCalls).hasValue(1);
        assertThat(source.refreshCalls).hasValue(0);
        assertThat(records).hasSize(1);
        assertUserRecord(records.get(0), USER_A);
    }

    @Test
    void sessionModeRefreshesTheSessionOnceAfterInvalidSessionAndRetries() throws Exception {
        stubApiScenario("queryAll",
                fault("INVALID_SESSION_ID", "UnexpectedErrorFault", INVALID_SESSION_MESSAGE),
                soap(queryResponse("queryAll", true, null, 1, userRecord(USER_A))));
        TestSessionSource source = new TestSessionSource(apiUrl());

        List<Map<String, Object>> records = sessionModeClient(source).queryAll(SOQL);

        assertThat(source.refreshCalls).hasValue(1);
        assertThat(loginRequests()).isEmpty();
        List<LoggedRequest> queryAll = apiRequests("queryAll");
        assertThat(queryAll).hasSize(2);
        assertThat(requestsCarrying(queryAll, SESSION_1)).hasSize(1);
        // Only the refreshed session carries SESSION_2, so the request holding it is the retry.
        assertThat(requestsCarrying(queryAll, SESSION_2)).hasSize(1);
        assertThat(records).hasSize(1);
        assertUserRecord(records.get(0), USER_A);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Timeout configuration
    // ---------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} = {2}")
    @MethodSource("timeoutsShorterThanOneMillisecond")
    void timeoutShorterThanOneMillisecondIsRejectedAtConstructionNamingItsKey(String key,
            BiConsumer<SalesforceProperties, Duration> setter, Duration timeout) {
        stubLogin();
        setter.accept(props, timeout);

        Throwable thrown = catchThrowable(this::loginModeClient);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key)
                .hasMessageContaining(timeout.toString());
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest(name = "connect {0}, read {1}")
    @MethodSource("acceptedTimeouts")
    void acceptedTimeoutsReachTheConnectorConfigInMilliseconds(Duration connectTimeout, Duration readTimeout,
            int expectedConnectMillis, int expectedReadMillis) throws Exception {
        props.setConnectTimeout(connectTimeout);
        props.setReadTimeout(readTimeout);
        SalesforcePartnerClient client = sessionModeClient(new TestSessionSource(apiUrl()));

        ConnectorConfig config = client.connection().getConfig();

        assertThat(config.getConnectionTimeout()).isEqualTo(expectedConnectMillis);
        assertThat(config.getReadTimeout()).isEqualTo(expectedReadMillis);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /**
     * Each timeout key with the setter that binds it, crossed with every duration WSC would turn into no timeout
     * (zero, or a positive fraction of a millisecond) or an invalid one (negative, down to a value whose milliseconds
     * overflow a {@code long}).
     */
    static Stream<Arguments> timeoutsShorterThanOneMillisecond() {
        Map<String, BiConsumer<SalesforceProperties, Duration>> setters = new LinkedHashMap<>();
        setters.put(CONNECT_TIMEOUT_KEY, SalesforceProperties::setConnectTimeout);
        setters.put(READ_TIMEOUT_KEY, SalesforceProperties::setReadTimeout);
        return setters.entrySet().stream().flatMap(setter -> Stream.of(Duration.ZERO, Duration.ofMillis(-1),
                        Duration.ofNanos(999_999), Duration.ofSeconds(Long.MIN_VALUE))
                .map(timeout -> Arguments.of(setter.getKey(), setter.getValue(), timeout)));
    }

    /**
     * Connect and read timeouts the client accepts, with the milliseconds WSC receives: unset falls back to the 10 s
     * default, 1 ms is the shortest accepted (a sub-millisecond remainder is dropped), and a duration beyond
     * {@link Integer#MAX_VALUE} milliseconds, up to the longest {@link Duration}, is capped there.
     */
    static Stream<Arguments> acceptedTimeouts() {
        return Stream.of(
                Arguments.of(null, null, 10_000, 10_000),
                Arguments.of(Duration.ofMillis(1), Duration.ofNanos(1_999_999), 1, 1),
                Arguments.of(Duration.ofDays(365), Duration.ofSeconds(Long.MAX_VALUE, 999_999_999),
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Clients, stubs and request journal
    // ---------------------------------------------------------------------------------------------------------

    /** A call of one public client operation, for the cases every operation must satisfy alike. */
    @FunctionalInterface
    interface ClientCall {

        void invoke(SalesforcePartnerClient client) throws Exception;
    }

    static Stream<Named<ClientCall>> everyOperation() {
        return Stream.of(
                Named.<ClientCall>of("query", client -> client.query(SOQL)),
                Named.<ClientCall>of("queryAll", client -> client.queryAll(SOQL)),
                Named.<ClientCall>of("create", client -> client.create("Contact", List.of(contact()))),
                Named.<ClientCall>of("delete", client -> client.delete(List.of(CONTACT_ID))),
                Named.<ClientCall>of("describeGlobal", SalesforcePartnerClient::describeGlobal),
                Named.<ClientCall>of("describeSObject", client -> client.describeSObject("User")));
    }

    /** A session source as the OAuth project supplies one: a current session, renewed once on request. */
    private static final class TestSessionSource implements SessionSource {

        private final String serviceEndpoint;

        private final AtomicReference<String> sessionId = new AtomicReference<>(SESSION_1);

        private final AtomicInteger currentCalls = new AtomicInteger();

        private final AtomicInteger refreshCalls = new AtomicInteger();

        TestSessionSource(String serviceEndpoint) {
            this.serviceEndpoint = serviceEndpoint;
        }

        @Override
        public Session current() {
            currentCalls.incrementAndGet();
            return new Session(sessionId.get(), serviceEndpoint);
        }

        @Override
        public Session refresh() {
            refreshCalls.incrementAndGet();
            sessionId.set(SESSION_2);
            return new Session(sessionId.get(), serviceEndpoint);
        }
    }

    private SalesforcePartnerClient loginModeClient() {
        return new SalesforcePartnerClient(props, executor, noSessionSource());
    }

    private SalesforcePartnerClient sessionModeClient(SessionSource source) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("sessionSource", source);
        return new SalesforcePartnerClient(props, executor, beanFactory.getBeanProvider(SessionSource.class));
    }

    private static ObjectProvider<SessionSource> noSessionSource() {
        return new DefaultListableBeanFactory().getBeanProvider(SessionSource.class);
    }

    private String apiUrl() {
        return "http://localhost:" + wm.port() + API_PATH;
    }

    /** Every login answers with {@link #SESSION_1}. */
    private void stubLogin() {
        wm.stubFor(post(urlEqualTo(LOGIN_PATH)).willReturn(soap(loginResponse(SESSION_1))));
    }

    /** The first login answers with {@link #SESSION_1}, every later one with {@link #SESSION_2}. */
    private void stubLoginSequence() {
        wm.stubFor(post(urlEqualTo(LOGIN_PATH)).inScenario("login").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(soap(loginResponse(SESSION_1))).willSetStateTo("logged in again"));
        wm.stubFor(post(urlEqualTo(LOGIN_PATH)).inScenario("login").whenScenarioStateIs("logged in again")
                .willReturn(soap(loginResponse(SESSION_2))));
    }

    private void stubApi(String operation, ResponseDefinitionBuilder response) {
        wm.stubFor(apiCall(operation).willReturn(response));
    }

    /** The first request of {@code operation} gets {@code first}, every later one gets {@code then}. */
    private void stubApiScenario(String operation, ResponseDefinitionBuilder first, ResponseDefinitionBuilder then) {
        wm.stubFor(apiCall(operation).inScenario(operation).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first).willSetStateTo("rejected once"));
        wm.stubFor(apiCall(operation).inScenario(operation).whenScenarioStateIs("rejected once")
                .willReturn(then));
    }

    private static MappingBuilder apiCall(String operation) {
        return post(urlEqualTo(API_PATH)).withRequestBody(operation(operation));
    }

    /**
     * Matches the SOAP body element of one Partner API operation whatever prefix WSC writes, and no operation whose
     * name merely starts with it ({@code query} does not match {@code queryAll} or {@code queryString}). WireMock
     * matches the gunzipped body, so WSC's default request compression stays on.
     */
    private static StringValuePattern operation(String name) {
        return matching("(?s).*<(\\w+:)?" + name + "[\\s/>].*");
    }

    private List<LoggedRequest> loginRequests() {
        return wm.findAll(postRequestedFor(urlEqualTo(LOGIN_PATH)));
    }

    private List<LoggedRequest> apiRequests(String operation) {
        return wm.findAll(postRequestedFor(urlEqualTo(API_PATH)).withRequestBody(operation(operation)));
    }

    private static List<LoggedRequest> requestsCarrying(List<LoggedRequest> requests, String sessionId) {
        return requests.stream()
                .filter(request -> request.getBodyAsString().contains(">" + sessionId + "<"))
                .toList();
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Records and assertions
    // ---------------------------------------------------------------------------------------------------------

    private static Map<String, Object> contact() {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("FirstName", "John");
        contact.put("LastName", "Doe");
        contact.put("Email", "john.doe@example.com");
        return contact;
    }

    /** A {@code User} row as the Partner API returns it, the {@code Id} element repeated. */
    private static String userRecord(String[] user) {
        return "<records xsi:type=\"sf:sObject\"><sf:type>User</sf:type>"
                + "<sf:Id>" + user[0] + "</sf:Id><sf:Id>" + user[0] + "</sf:Id>"
                + "<sf:Name>" + user[1] + "</sf:Name><sf:Email>" + user[2] + "</sf:Email></records>";
    }

    /**
     * Asserts a mapped {@code User} row: a {@link HashMap}, its repeated {@code Id} kept once as a single value, and
     * its rendering holding the original example's {@code reply.txt} lines for that user.
     */
    private static void assertUserRecord(Map<String, Object> record, String[] user) {
        assertThat(record).isExactlyInstanceOf(HashMap.class)
                .containsOnlyKeys("type", "Id", "Name", "Email")
                .containsEntry("type", "User")
                .containsEntry("Id", user[0])
                .containsEntry("Name", user[1])
                .containsEntry("Email", user[2]);
        assertThat(record.toString()).contains("Email=" + user[2], "Id=" + user[0], "Name=" + user[1]);
    }

    // ---------------------------------------------------------------------------------------------------------
    // SOAP responses
    // ---------------------------------------------------------------------------------------------------------

    private static ResponseDefinitionBuilder soap(String envelope) {
        return aResponse().withStatus(200).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    /** A Partner API fault as Salesforce sends it: HTTP 500 with the typed fault in {@code detail}. */
    private static ResponseDefinitionBuilder fault(String code, String faultType, String message) {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns:sf=\"" + FAULT_NS + "\""
                + " xmlns:xsi=\"" + XSI_NS + "\"><soapenv:Body><soapenv:Fault>"
                + "<faultcode>sf:" + code + "</faultcode><faultstring>" + code + ": " + message + "</faultstring>"
                + "<detail><sf:" + faultType + " xsi:type=\"sf:" + faultType + "\">"
                + "<sf:exceptionCode>" + code + "</sf:exceptionCode>"
                + "<sf:exceptionMessage>" + message + "</sf:exceptionMessage>"
                + "</sf:" + faultType + "></detail></soapenv:Fault></soapenv:Body></soapenv:Envelope>";
        return aResponse().withStatus(500).withHeader("Content-Type", TEXT_XML).withBody(body);
    }

    private static String envelope(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns=\"" + PARTNER_NS + "\""
                + " xmlns:sf=\"" + SOBJECT_NS + "\" xmlns:xsi=\"" + XSI_NS + "\">"
                + "<soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>";
    }

    /** A {@code loginResponse} whose {@code serverUrl} points the client at {@link #API_PATH} on WireMock. */
    private String loginResponse(String sessionId) {
        return envelope("<loginResponse><result>"
                + "<metadataServerUrl>http://localhost:" + wm.port() + "/services/Soap/m/65.0/00Dxx0000001gEREAY"
                + "</metadataServerUrl>"
                + "<passwordExpired>false</passwordExpired>"
                + "<sandbox>true</sandbox>"
                + "<serverUrl>" + apiUrl() + "</serverUrl>"
                + "<sessionId>" + sessionId + "</sessionId>"
                + "<userId>005xx000001SwiUAAS</userId>"
                + "</result></loginResponse>");
    }

    /**
     * A {@code query}, {@code queryAll} or {@code queryMore} response.
     *
     * @param locator the locator of the next page, or {@code null} for a nil locator on the last page
     * @param size    the total size of the result over every page
     */
    private static String queryResponse(String operation, boolean done, String locator, int size,
            String... records) {
        return envelope("<" + operation + "Response><result xsi:type=\"QueryResult\">"
                + "<done>" + done + "</done>"
                + (locator == null
                        ? "<queryLocator xsi:nil=\"true\"/>"
                        : "<queryLocator>" + locator + "</queryLocator>")
                + String.join("", records)
                + "<size>" + size + "</size>"
                + "</result></" + operation + "Response>");
    }

    private static String describeGlobalResponse() throws IOException {
        DescribeGlobalResult result = populated(new DescribeGlobalResult());
        result.setEncoding("UTF-8");
        result.setMaxBatchSize(200);
        result.setSobjects(new DescribeGlobalSObjectResult[] {globalSObject("Account"), globalSObject("User")});
        return describeResponse("describeGlobal", result);
    }

    private static DescribeGlobalSObjectResult globalSObject(String name) {
        DescribeGlobalSObjectResult sObject = populated(new DescribeGlobalSObjectResult());
        sObject.setName(name);
        sObject.setLabel(name);
        sObject.setLabelPlural(name + "s");
        return sObject;
    }

    private static String describeUserResponse() throws IOException {
        DescribeSObjectResult result = populated(new DescribeSObjectResult());
        result.setName("User");
        result.setLabel("User");
        result.setLabelPlural("Users");
        result.setFields(new Field[] {field("Email"), field("Name")});
        return describeResponse("describeSObject", result);
    }

    private static Field field(String name) {
        Field field = populated(new Field());
        field.setName(name);
        field.setLabel(name);
        return field;
    }

    /**
     * Writes {@code <operation>Response/result} with WSC's own serializer, so the element names, order and value
     * formats are exactly those its parser expects.
     */
    private static String describeResponse(String operation, XMLizable result) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        XmlOutputStream out = new XmlOutputStream(bytes, false);
        out.startDocument();
        out.setPrefix("soapenv", SOAP_NS);
        out.setPrefix("xsi", XSI_NS);
        out.setPrefix("", PARTNER_NS);
        out.writeStartTag(SOAP_NS, "Envelope");
        out.writeStartTag(SOAP_NS, "Body");
        out.writeStartTag(PARTNER_NS, operation + "Response");
        result.write(new QName(PARTNER_NS, "result"), out, new TypeMapper());
        out.writeEndTag(PARTNER_NS, operation + "Response");
        out.writeEndTag(SOAP_NS, "Body");
        out.writeEndTag(SOAP_NS, "Envelope");
        out.endDocument();
        out.close();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Calls every public one-argument setter of a WSC result bean with a neutral value ({@code false}, {@code 0},
     * {@code ""}, the first enum constant, an empty array), so the bean writes every element its type requires. The
     * only setters left out take a single complex element or an {@code xsd:anyType} value, which every describe type
     * declares optional ({@code minOccurs="0"}).
     */
    private static <T> T populated(T bean) {
        for (Method setter : bean.getClass().getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1
                    || Modifier.isStatic(setter.getModifiers())) {
                continue;
            }
            Object value = neutralValue(setter.getParameterTypes()[0]);
            if (value != null) {
                try {
                    setter.invoke(bean, value);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("Cannot call " + setter, e);
                }
            }
        }
        return bean;
    }

    private static Object neutralValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == String.class) {
            return "";
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        return null;
    }
}
