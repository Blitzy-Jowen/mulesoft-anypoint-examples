/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.mulesoft.examples.salesforce_data_retrieval.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_retrieval.controller.SalesforceRetrievalController;
import com.mulesoft.examples.salesforce_data_retrieval.service.SalesforceRetrievalService;
import com.sforce.ws.ConnectionException;

/**
 * Tests {@link SalesforceRetrievalExceptionHandler}, the {@code catch-exception-strategy} of
 * {@code salesforceDataRetrievalFlow} (POST {@code /}, {@code salesforce-id-retrieval.xml:37-39}), whose only
 * processor sets the payload {@code Invalid Salesforce query.}, after which the listener answered 200. The GET flow
 * {@code showFormFlow} declares no strategy, so its failures keep the platform-default 500 of
 * {@link GlobalExceptionHandler}.
 *
 * <p>Standalone MockMvc carries the real controller with a mocked service, the controller's nested
 * {@code HEAD}/{@code OPTIONS} guard, and the project's three advices, registered on purpose in reverse precedence
 * order: Spring sorts them by {@code @Order}, so only {@link UpstreamExceptionHandler}'s highest precedence keeps the
 * 502, 429 and 503 of the approved connector failure modes ahead of the catch-all, and only this advice's
 * {@code @Order(0)} keeps the catch-all ahead of SC-1's 500. A precedence defect shows up here as a wrong status and
 * must be fixed in the advices, never by reordering this registration. The advice's {@code assignableTypes} scope
 * keeps the listener-default 404 and 405 for requests no {@link SalesforceRetrievalController} method handles.
 *
 * <p>Every expected body is a literal compared exactly, because the original catch payload and APIkit's
 * {@code { "message": ... }} spacing are the contract. Reason phrases and Undertow framing are asserted on the wire
 * by the controller and ported original tests, not here.
 */
@DisplayName("SalesforceRetrievalExceptionHandler: the POST flow's catch branch among the project's advices")
class SalesforceRetrievalExceptionHandlerTest {

    /** The payload the POST flow's catch branch sets (xml:38): trailing period, no line break. */
    private static final String INVALID = "Invalid Salesforce query.";

    private static final String TEXT_UTF8 = "text/plain;charset=UTF-8";

    /** The form of the original {@code SalesforceIdRetrievalIT.testGetData}. */
    private static final String FORM = "object=user&field=email&searchKey=name&searchValue=mule";

    private SalesforceRetrievalService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SalesforceRetrievalService.class);
        GlobalExceptionHandler global = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SalesforceRetrievalController(service),
                                 new SalesforceRetrievalController.MethodNotAllowedGuard())
                // Reverse precedence on purpose: only @Order may decide which advice answers.
                .setControllerAdvice(global,
                                     new SalesforceRetrievalExceptionHandler(global),
                                     new UpstreamExceptionHandler())
                .build();
    }

    static Stream<Exception> queryFailures() {
        return Stream.of(
                new IllegalArgumentException("Unknown object: foo"),
                new NullPointerException(),
                new ConnectionException("INVALID_TYPE: sObject type 'foo' is not supported"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFailures")
    @DisplayName("a failed POST query answers the original catch branch: 200 text/plain 'Invalid Salesforce query.'")
    void postFailureAnswersOriginalCatchBranch(Exception failure) throws Exception {
        given(service.query(anyMap())).willThrow(failure);

        mockMvc.perform(postForm())
                .andExpect(status().isOk())
                .andExpect(content().contentType(TEXT_UTF8))
                .andExpect(content().string(INVALID))
                .andExpect(result -> assertThat(result.getResolvedException()).isSameAs(failure));

        verify(service).query(anyMap());
    }

    static Stream<Arguments> formFailures() {
        return Stream.of(
                Arguments.of(new ConnectionException("boom"), "{ \"message\": \"boom\" }"),
                Arguments.of(new IllegalStateException("bad"), "{ \"message\": \"bad\" }"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formFailures")
    @DisplayName("a failed GET form keeps SC-1's 500, because showFormFlow declares no exception strategy")
    void getFailureDelegatesToGlobal500(Exception failure, String expectedBody) throws Exception {
        given(service.showForm()).willThrow(failure);

        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(expectedBody))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEqualTo(INVALID);
        assertThat(result.getResolvedException()).isSameAs(failure);
    }

    /**
     * The three approved connector failure modes, plus an unavailable upstream carried as the cause of another
     * exception, which the upstream advice still claims ahead of the catch-all.
     */
    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE,
                                "INVALID_SESSION_ID: Session expired or invalid", null),
                        502,
                        "{ \"message\": \"INVALID_SESSION_ID: Session expired or invalid\","
                                + " \"upstream\": \"Salesforce\" }"),
                Arguments.of(new UpstreamRateLimitException(UpstreamSystem.SALESFORCE,
                                "REQUEST_LIMIT_EXCEEDED: TotalRequests Limit exceeded.", null, null),
                        429,
                        "{ \"message\": \"REQUEST_LIMIT_EXCEEDED: TotalRequests Limit exceeded.\","
                                + " \"upstream\": \"Salesforce\" }"),
                Arguments.of(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "connect timed out", null),
                        503,
                        "{ \"message\": \"connect timed out\", \"upstream\": \"Salesforce\" }"),
                Arguments.of(new IllegalStateException("query failed",
                                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out", null)),
                        503,
                        "{ \"message\": \"Read timed out\", \"upstream\": \"Salesforce\" }"));
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("upstreamFailures")
    @DisplayName("upstream failures of the POST query reach SC-9 ahead of the 'Invalid Salesforce query.' catch-all")
    void upstreamExceptionsWinOverCatchAllOnPost(Exception failure, int expectedStatus, String expectedBody)
            throws Exception {
        given(service.query(anyMap())).willThrow(failure);

        assertUpstreamResponse(mockMvc.perform(postForm()), expectedStatus, expectedBody);
    }

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("upstreamFailures")
    @DisplayName("upstream failures of the GET form reach SC-9 ahead of SC-1's 500")
    void upstreamExceptionsWinOverGlobalOnGet(Exception failure, int expectedStatus, String expectedBody)
            throws Exception {
        given(service.showForm()).willThrow(failure);

        assertUpstreamResponse(mockMvc.perform(get("/")), expectedStatus, expectedBody);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET, /nope", "POST, /nope", "POST, /nope/deeper"})
    @DisplayName("an unserved path keeps the listener-default 404, not the scoped catch-all")
    void unmappedPathIsListenerDefault404(String method, String path) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("No listener for endpoint: " + path))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(NoHandlerFoundException.class));

        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0} /")
    @ValueSource(strings = {"PUT", "DELETE", "PATCH"})
    @DisplayName("a method neither listener accepts keeps the listener-default 405")
    void wrongMethodIsListenerDefault405(String method) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), "/"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Method not allowed for endpoint: /"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("OPTIONS / rejected by the controller's guard keeps the listener-default 405, not the catch-all")
    void guardRejectionIsListenerDefault405() throws Exception {
        MvcResult result = mockMvc.perform(options("/"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("Method not allowed for endpoint: /"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEqualTo(INVALID);
        assertThat(result.getResolvedException()).isInstanceOf(HttpRequestMethodNotSupportedException.class);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the advice is scoped to the controller at @Order(0), between SC-9 and SC-1")
    void adviceDeclaresOriginalScopeAndOrder() {
        RestControllerAdvice advice =
                AnnotationUtils.findAnnotation(SalesforceRetrievalExceptionHandler.class, RestControllerAdvice.class);
        assertThat(advice).isNotNull();
        assertThat(advice.assignableTypes()).containsExactly(SalesforceRetrievalController.class);
        assertThat(advice.basePackages()).isEmpty();
        assertThat(advice.basePackageClasses()).isEmpty();
        assertThat(advice.annotations()).isEmpty();

        int upstream = orderOf(UpstreamExceptionHandler.class);
        int project = orderOf(SalesforceRetrievalExceptionHandler.class);
        int global = orderOf(GlobalExceptionHandler.class);
        assertThat(upstream).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(project).isZero();
        assertThat(global).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(upstream).isLessThan(project);
        assertThat(project).isLessThan(global);
    }

    @Test
    @DisplayName("a successful POST query passes through untouched by every advice, untyped as the original's payload")
    void successfulPostIsNotTouchedByAdvice() throws Exception {
        String records = "Email=a@b.c\nId=1\nName=Mule\n";
        byte[] recordBytes = records.getBytes(StandardCharsets.UTF_8);
        given(service.query(anyMap())).willReturn(records);

        mockMvc.perform(postForm())
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, recordBytes.length))
                .andExpect(content().bytes(recordBytes))
                .andExpect(result -> assertThat(result.getResolvedException()).isNull());

        verify(service).query(Map.of("object", "user", "field", "email", "searchKey", "name", "searchValue", "mule"));
    }

    private static MockHttpServletRequestBuilder postForm() {
        return post("/").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(FORM);
    }

    /** Salesforce sends no {@code Retry-After}, so none of the cases carries one (AAP 0.4.8). */
    private static void assertUpstreamResponse(ResultActions actions, int expectedStatus, String expectedBody)
            throws Exception {
        actions.andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(expectedBody))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isNotEqualTo(INVALID));
    }

    private static int orderOf(Class<?> adviceType) {
        Order order = AnnotationUtils.findAnnotation(adviceType, Order.class);
        assertThat(order).as("@Order on %s", adviceType.getSimpleName()).isNotNull();
        return order.value();
    }
}
