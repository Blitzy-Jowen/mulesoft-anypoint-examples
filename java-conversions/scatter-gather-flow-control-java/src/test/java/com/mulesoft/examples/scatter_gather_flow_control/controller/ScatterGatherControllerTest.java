/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mulesoft.examples.scatter_gather_flow_control.client.AggregationReportMailer;
import com.mulesoft.examples.scatter_gather_flow_control.service.ScatterGatherService;
import com.mulesoft.examples.scatter_gather_flow_control.support.HttpTestClient;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Wire-level test of {@link ScatterGatherController}, the HTTP trigger of flow {@code scatter-gatherFlow}
 * (scatter-gather-flow-control/src/main/app/scatter-gather.xml:6-60), on the application's own Undertow listener
 * with the real {@link ScatterGatherService}, aggregation strategy, CSV mapper and {@link AggregationReportMailer}
 * sending to an in-process GreenMail SMTP server in place of the original's Gmail account.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so the reason phrase, the absence of {@code Content-Type} and the body bytes are asserted exactly as the
 * listener writes them. The happy path covers every method, because the original listener declares no
 * {@code allowedMethods}. The error cases are the two failures the flow leaves to the platform default, because it
 * declares no exception strategy: the SMTP send of {@code outboundFlow} failing and the custom aggregation strategy
 * rejecting a route result; then the listener-level no-listener 404. The original
 * {@code ScatterGatherFlowControlIT} assertions are ported separately in {@code ScatterGatherFlowControlTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ScatterGatherControllerTest {

    private static final String SCATTER_GATHER_PATH = "/scatterGather";

    /** Payload of {@code outboundFlow}'s {@code set-payload} (scatter-gather.xml:63), the flow's response. */
    private static final String REPORT_BODY = "Please find attached your Aggregation Report";

    /** Subject literal of the {@code smtp:outbound-endpoint} (scatter-gather.xml:64). */
    private static final String REPORT_SUBJECT = "Scatter gather example - aggregation";

    /** Message of the original {@code ContactMergeAggregationStrategy} (line 34) when a route has no result. */
    private static final String MISSING_ROUTE_MESSAGE =
            "Data from at least one source was not able to be obtained correctly.";

    private static final long DELIVERY_TIMEOUT_MS = 5000;

    /** Methods sent with an empty body and {@code Content-Length: 0}, as the original test's POST without payload. */
    private static final Set<String> METHODS_WITH_EMPTY_BODY = Set.of("POST", "PUT", "PATCH");

    // GreenMail binds a free port rather than its fixed 3025, and one server serves the whole class: the test
    // profile (application-test.yml) sets no smtp.port and requires the bound port to be registered below before the
    // context starts and to stay valid for the cached context, and fixed ports collide on this shared build host.
    // Mailboxes are purged before each test instead of restarting the server.
    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("scatter@localhost", "secret"))
            .withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void smtpPort(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> greenMail.getSmtp().getPort());
    }

    @SpyBean
    private ScatterGatherService service;

    @SpyBean
    private AggregationReportMailer mailer;

    @LocalServerPort
    private int port;

    @BeforeEach
    void purgeMailboxes() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    /**
     * Every method runs the flow once: both routes, the merged CSV report mailed through SMTP, and the literal
     * payload of {@code outboundFlow} returned with status 200, its length and no {@code Content-Type}, because the
     * flow's final {@code set-payload} declares no MIME type.
     *
     * @param method the request method
     */
    @ParameterizedTest(name = "{0} /scatterGather")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    void everyMethodRunsTheFlowMailsTheReportAndReturnsItsPayload(String method) throws Exception {
        byte[] body = METHODS_WITH_EMPTY_BODY.contains(method) ? new byte[0] : null;

        HttpTestClient.Response response = client().send(method, SCATTER_GATHER_PATH, null, body);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("44", response.header("Content-Length"), response::toString);
        assertEquals(REPORT_BODY, AggregationReportMailer.BODY_TEXT);
        assertEquals(REPORT_BODY, response.bodyAsString(), response::toString);
        assertArrayEquals(REPORT_BODY.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertEquals(44, response.body().length, response::toString);

        assertTrue(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1), "no report mail was delivered");
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals(REPORT_SUBJECT, received[0].getSubject());
        verify(service, times(1)).process();
        verify(mailer, times(1)).send(anyString());
    }

    /**
     * A failing SMTP send propagates out of {@code outboundFlow}; the flow has no exception strategy, so the
     * unhandled-exception default answers with the exception's message.
     */
    @Test
    void mailSendFailureReturns500JsonMessageAndDeliversNoMail() {
        doThrow(new MailSendException("Mail server connection failed")).when(mailer).send(anyString());

        HttpTestClient.Response response = client().get(SCATTER_GATHER_PATH);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"Mail server connection failed\" }", response.bodyAsString(),
                response::toString);
        verify(mailer, times(1)).send(anyString());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    /**
     * A route that fails leaves one successful route result, which the ported aggregation strategy rejects with its
     * own message rather than the route's; no report is rendered or mailed. Both routes still run, as the
     * scatter-gather router runs every route before aggregating.
     */
    @Test
    void failingRouteReturns500WithAggregationMessageAndSendsNoMail() {
        doThrow(new RuntimeException("route A down")).when(service).sourceA();

        HttpTestClient.Response response = client().get(SCATTER_GATHER_PATH);

        assertEquals(500, response.status(), response::toString);
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"" + MISSING_ROUTE_MESSAGE + "\" }", response.bodyAsString(),
                response::toString);
        verify(service, times(1)).sourceA();
        verify(service, times(1)).sourceB();
        verify(mailer, never()).send(anyString());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    /**
     * The listener serves {@code /scatterGather} exactly, so any other path, a sub-path included, gets the
     * no-listener default without running the flow.
     *
     * @param path the unserved request path
     */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/unknown", "/scatterGather/extra"})
    void unservedPathReturnsNoListener404WithoutRunningTheFlow(String path) {
        HttpTestClient.Response response = client().get(path);

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: " + path, response.bodyAsString(), response::toString);
        verify(service, never()).process();
        verify(mailer, never()).send(anyString());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /**
     * Compares the whole value: SC-1 presets the bare media type on the response entity, so the listener writes
     * exactly one {@code Content-Type} header carrying that value.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }
}
