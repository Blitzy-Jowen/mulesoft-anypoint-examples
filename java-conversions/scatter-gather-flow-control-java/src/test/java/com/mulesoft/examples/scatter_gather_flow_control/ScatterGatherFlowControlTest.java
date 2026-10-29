/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Map;

import jakarta.mail.internet.MimeMessage;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mulesoft.examples.scatter_gather_flow_control.support.HttpTestClient;

/**
 * Port of the original example's {@code ScatterGatherFlowControlIT} functional test, keeping its POST request and
 * its assertions: the flow answers 200 with a payload, and the aggregation report mail reaches the SMTP server.
 *
 * <p>An in-process GreenMail SMTP server, authenticating the {@code smtp.user} of profile {@code test}, stands in for
 * the Gmail account the original read and cleaned up over IMAPS, so the test needs no credentials and leaves nothing
 * to delete. The exact body and the mail's structure are asserted by {@code controller/ScatterGatherControllerTest}
 * and {@code client/AggregationReportMailerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ScatterGatherFlowControlTest {

    /** Upper bound for the report mail to arrive; replaces the original's fixed ten-second sleep. */
    private static final Duration MAIL_DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    // A free port instead of GreenMail's fixed 3025, because concurrent builds share this host, so a fixed port may
    // already be taken. The server runs for the whole class, not per method, because the Spring context reads the
    // bound port once, as smtp.port, when it is created; emptyMailboxes() gives every method an empty server instead.
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("scatter@localhost", "secret"))
            .withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void smtpPort(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> greenMail.getSmtp().getPort());
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void emptyMailboxes() throws FolderException {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @Test
    void httpPostToFlowUrlSentMessage() throws Exception {
        // ST-1 connects to localhost, which the 0.0.0.0 listener serves; a null body sends no body bytes, as the
        // original's null payload did.
        HttpTestClient.Response result =
                HttpTestClient.localhost(port).send("POST", "/scatterGather", Map.of(), null);
        assertNotNull(result);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(result.body().length == 0);
        assertEquals(200, result.status());

        // One recipient, receiver@localhost, receives exactly one message.
        Awaitility.await().atMost(MAIL_DELIVERY_TIMEOUT).until(() -> greenMail.getReceivedMessages().length == 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertNotNull(messages[messages.length - 1].getContent());
    }
}
