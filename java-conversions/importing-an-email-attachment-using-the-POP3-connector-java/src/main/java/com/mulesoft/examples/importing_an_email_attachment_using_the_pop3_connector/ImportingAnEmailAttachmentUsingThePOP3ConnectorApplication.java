/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code importing-an-email-attachment-using-the-POP3-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded {@code pop-to-xml.xml}
 * and its single flow {@code pop-to-xmlFlow1} into the Mule runtime; this class starts the application as a
 * standalone process without a web server, as {@code application.yml} sets.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.Pop3MailPoller.poll()}, the replacement of the flow's
 * {@code pop3s:inbound-endpoint}, with {@code pop3.check-frequency} milliseconds between polls (default 100, the
 * original {@code pop3s:connector checkFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportingAnEmailAttachmentUsingThePOP3ConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingAnEmailAttachmentUsingThePOP3ConnectorApplication.class, args);
    }
}
