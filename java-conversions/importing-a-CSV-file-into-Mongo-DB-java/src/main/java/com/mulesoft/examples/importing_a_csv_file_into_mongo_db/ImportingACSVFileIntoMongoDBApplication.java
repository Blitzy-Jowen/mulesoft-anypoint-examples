/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the converted Mule example {@code importing-a-CSV-file-into-Mongo-DB} (Apache License
 * 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, whose
 * {@code config.resources=csv-to-mongodb.xml} loaded the single flow {@code csv-to-mongodbFlow1} into the Mule
 * runtime; this class starts the application as a standalone process without a web server, as
 * {@code application.yml} sets, because the flow has no HTTP listener. Boot's MongoDB auto-configuration supplies
 * the {@code MongoTemplate} that replaces the flow's {@code mongo:config}, from the {@code database.*} keys as
 * {@code config.MongoConfig} wires them; while one is missing, the application still starts and every import fails.
 *
 * <p>{@code @EnableScheduling} drives {@code trigger.CsvFileTrigger.poll()}, the replacement of the flow's
 * {@code file:inbound-endpoint}, with {@code csv-import.polling-frequency} milliseconds between polls (default 1000,
 * the Mule file connector's default, since the original declares no {@code pollingFrequency}).
 */
@SpringBootApplication
@EnableScheduling
public class ImportingACSVFileIntoMongoDBApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportingACSVFileIntoMongoDBApplication.class, args);
    }
}
