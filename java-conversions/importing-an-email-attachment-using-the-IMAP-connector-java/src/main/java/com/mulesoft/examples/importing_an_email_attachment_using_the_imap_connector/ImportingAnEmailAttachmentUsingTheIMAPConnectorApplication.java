/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code importing-an-email-attachment-using-the-IMAP-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded {@code imap-to-xml.xml}
 * and its single flow {@code imap-to-csvFlow1} into the Mule runtime; this class starts the application as a
 * standalone process without a web server, as {@code application.yml} sets.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.ImapMailPoller.poll()}, the replacement of the flow's
 * {@code imaps:inbound-endpoint}, with {@code imap.check-frequency} milliseconds between polls (default 100, the
 * original {@code imaps:connector checkFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication.class, args);
    }
}
