/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.SalesforceToMySQLDBUsingBatchProcessingApplication;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.ContactRepository;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service.BatchJobRunner;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.support.SfdcObjectBuilder;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.trigger.ContactPollScheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The whole application started as it is shipped, from the main {@code application.yml} alone with
 * {@code mysql.host}, {@code mysql.user} and {@code mysql.password} empty, as unset {@code MYSQL_HOST},
 * {@code MYSQL_USER} and {@code MYSQL_PASSWORD} leave them: the context starts with {@link UnconfiguredDataSource},
 * a {@link ContactRepository} call fails with {@link CannotGetJdbcConnectionException} around the data source's
 * {@link SQLNonTransientConnectionException} naming the three keys, and a poll over one Salesforce Contact records
 * that Contact as failed in step {@code queryExistingContactInDbStep}, which SC-7 {@link BatchJobRunner} logs at
 * ERROR by exception class and throw site.
 *
 * <p>No profile is active, so no H2 database of profile {@code test} applies; Salesforce is a mocked
 * {@link SalesforcePartnerClient}. The three keys are set empty here, above any environment variable of the host,
 * and the scheduled poll is held back for an hour, so only {@link ContactPollScheduler#pollOnce()} polls. The
 * watermark lives in this class's {@link TempDir}. The log oracle is a Logback {@link ListAppender} on
 * {@link BatchJobRunner}'s logger, attached for each test after the context has started, because Spring Boot resets
 * the Logback context when an application context starts.
 */
@SpringBootTest(classes = SalesforceToMySQLDBUsingBatchProcessingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"mysql.host=", "mysql.user=", "mysql.password=", "poll.initial-delay=3600000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnconfiguredDataSourcePollTest {

    private static final String NOT_CONFIGURED_MESSAGE = "MySQL connection settings are not configured: "
            + "mysql.host (environment variable MYSQL_HOST), mysql.user (environment variable MYSQL_USER), "
            + "mysql.password (environment variable MYSQL_PASSWORD)";

    private static final String EMAIL = "john.doe@example.com";

    @TempDir
    static Path watermarkDir;

    @MockBean
    SalesforcePartnerClient partnerClient;

    @Autowired
    DataSource dataSource;

    @Autowired
    ContactRepository contactRepository;

    @Autowired
    ContactPollScheduler contactPollScheduler;

    private Logger runnerLogger;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    // Dynamic properties outrank surefire's watermark.store-path system property.
    @DynamicPropertySource
    static void watermarkStorePath(DynamicPropertyRegistry registry) {
        registry.add("watermark.store-path", () -> watermarkDir.resolve("watermark.properties").toString());
    }

    @BeforeEach
    void attachAppender() {
        runnerLogger = (Logger) LoggerFactory.getLogger(BatchJobRunner.class);
        previousAdditive = runnerLogger.isAdditive();
        // The appender records the line; detaching the console keeps the expected failure out of the build log.
        runnerLogger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        runnerLogger.detachAppender(appender);
        appender.stop();
        runnerLogger.setAdditive(previousAdditive);
    }

    @Test
    void contextStartsWithUnconfiguredDataSource() {
        assertInstanceOf(UnconfiguredDataSource.class, dataSource);
    }

    @Test
    void repositoryCallFailsNamingTheMissingKeys() {
        CannotGetJdbcConnectionException failure =
                assertThrows(CannotGetJdbcConnectionException.class, () -> contactRepository.findByEmail(EMAIL));

        SQLNonTransientConnectionException cause =
                assertInstanceOf(SQLNonTransientConnectionException.class, failure.getCause());
        assertEquals(NOT_CONFIGURED_MESSAGE, cause.getMessage());
        assertEquals("08001", cause.getSQLState());
    }

    @Test
    void pollRecordsTheContactAsFailedInItsLookupStep() throws Exception {
        Map<String, Object> contact = SfdcObjectBuilder.aContact()
                .with("Email", EMAIL)
                .with("FirstName", "John")
                .with("LastName", "Doe")
                .with("LastModifiedDate", "2015-10-10T10:10:10.000Z")
                .build();
        when(partnerClient.query(anyString())).thenReturn(new ArrayList<>(List.of(contact)));

        BatchJobResult result = contactPollScheduler.pollOnce();

        assertEquals(BatchJobResult.Status.FAILED_PROCESS_RECORDS, result.status());
        assertEquals(1, result.loadedRecords());
        assertEquals(0, result.successfulRecords());
        assertEquals(1, result.failedRecords());

        List<ILoggingEvent> errors =
                appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), appender.list::toString);
        String line = errors.get(0).getFormattedMessage();
        assertTrue(line.startsWith("Found exception processing record on step 'queryExistingContactInDbStep' "
                + "for job instance '"), line);
        assertTrue(line.contains("' of job 'salesforce-to-database-Batch': "
                + "org.springframework.jdbc.CannotGetJdbcConnectionException at "), line);
        assertTrue(line.contains(" <- java.sql.SQLNonTransientConnectionException at " + UnconfiguredDataSource.class
                .getName() + ".getConnection(UnconfiguredDataSource.java:"), line);
        assertFalse(line.contains(EMAIL), line);
    }
}
