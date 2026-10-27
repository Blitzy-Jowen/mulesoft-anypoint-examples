/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code importing-a-csv-file-into-ms-sharepoint} (Apache
 * License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code importing-a-csv-file-into-ms-sharepoint.xml} and its single flow
 * {@code importing-a-csv-file-into-ms-sharepointFlow} into the Mule runtime; this class starts the application as a
 * standalone process without a web server, as {@code application.yml} sets.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.CsvFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, every {@code csv-import.polling-frequency} milliseconds (default 10000, the
 * original {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportingACsvFileIntoMsSharepointApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingACsvFileIntoMsSharepointApplication.class, args);
    }
}
