/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code sending-a-csv-file-through-email-using-smtp}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=csv-to-smtp.xml} loaded the single flow {@code csv-to-smtpFlow}: a CSV file polled from
 * {@code src/main/resources/input} is mapped to a list of order lines, logged, and sent as the body of an email over
 * SMTP. Like the original, the process opens no HTTP listener ({@code application.yml} starts no embedded server);
 * its {@code JavaMailSender} comes from Boot's mail auto-configuration over {@code spring.mail.*}.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.CsvFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code csv-email.polling-frequency} milliseconds between polls (default 1000,
 * Mule's default, since the endpoint declares no {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class SendingACsvFileThroughEmailUsingSmtpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SendingACsvFileThroughEmailUsingSmtpApplication.class, args);
    }
}
