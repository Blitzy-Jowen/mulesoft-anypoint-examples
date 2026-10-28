/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoClient;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.trigger.CsvFileTrigger;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.trigger.FileInboundPoller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The whole application started as it is shipped, from the main {@code application.yml}, with
 * {@code database.name}, {@code database.user} and {@code database.password} empty, as unset environment variables
 * leave them: the context starts with {@link UnconfiguredMongoDatabaseFactory} and no {@code MongoClient}, the
 * startup log carries the single warning naming {@code database.name} and {@code DATABASE_NAME}, and a polled
 * {@code input.csv} fails at its first database call, which {@link FileInboundPoller} logs at ERROR, naming the
 * factory as the throw site, before it deletes the file.
 *
 * <p>The three keys are emptied by the test's inline properties, which outrank profile {@code test}'s values.
 * {@link OutputCaptureExtension} is declared first, so it captures the console from before the context starts; the
 * poller's ERROR line is read through a Logback {@link ListAppender} on its logger, attached for each test and
 * detached afterwards. The input directory is a temporary directory and the polling frequency one hour, so the
 * scheduled startup poll, on the still empty directory, is the only scheduled one.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"database.name=", "database.user=", "database.password="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnconfiguredMongoDatabaseImportTest {

    private static final String NAME_WARNING = "database.name (environment variable DATABASE_NAME) is not "
            + "configured: the application starts without a MongoDB connection, and every import fails until it is "
            + "supplied";

    /** The poller's ERROR line: type and throw site of the failure, never its message. */
    private static final Pattern IMPORT_FAILURE = Pattern.compile("Exception processing file input\\.csv: "
            + "java\\.lang\\.IllegalStateException at " + Pattern.quote(UnconfiguredMongoDatabaseFactory.class.getName())
            + "\\.getMongoDatabase\\(UnconfiguredMongoDatabaseFactory\\.java:\\d+\\)");

    /** Bounds the wait for the polled file's deletion. */
    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(5);

    @TempDir
    static Path workDir;

    @Autowired
    ApplicationContext context;

    @Autowired
    CsvFileTrigger csvFileTrigger;

    @Autowired
    MongoTemplate mongoTemplate;

    private Logger pollerLogger;

    private ListAppender<ILoggingEvent> pollerAppender;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("csv-import.input-dir", () -> workDir.resolve("input").toString());
        registry.add("csv-import.polling-frequency", () -> "3600000");
    }

    @BeforeEach
    void attachAppender() {
        pollerLogger = (Logger) LoggerFactory.getLogger(FileInboundPoller.class);
        pollerAppender = new ListAppender<>();
        pollerAppender.start();
        pollerLogger.addAppender(pollerAppender);
    }

    @AfterEach
    void detachAppender() {
        pollerLogger.detachAppender(pollerAppender);
        pollerAppender.stop();
    }

    @Test
    void startsWithoutMongoClientAndWarnsOnceNamingTheKey(CapturedOutput output) {
        assertInstanceOf(UnconfiguredMongoDatabaseFactory.class, context.getBean(MongoDatabaseFactory.class));
        assertNull(context.getBeanProvider(MongoClient.class).getIfAvailable(), "no MongoDB client is created");

        String log = output.getAll();
        assertEquals(1, log.split(Pattern.quote(NAME_WARNING), -1).length - 1, log);
        assertTrue(log.lines().anyMatch(line -> line.contains(" WARN ") && line.endsWith(NAME_WARNING)), log);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> mongoTemplate.collectionExists("customers"));
        assertEquals("database.name is not configured", failure.getMessage());
    }

    @Test
    void polledFileFailsAtItsFirstDatabaseCallIsLoggedAtErrorAndDeleted() throws IOException {
        Path input = csvFileTrigger.inputDirectory().resolve("input.csv");
        Path staged = Files.write(Files.createDirectories(workDir.resolve("staging")).resolve("input.csv"),
                new ClassPathResource("input.csv").getContentAsByteArray());
        // Staged and moved in, so the scheduled poll on its own thread never lists a half-written file.
        moveInto(staged, input);

        // Not asserted: the scheduled startup poll may take the file first, and the in-process mark keeps either
        // run from handing it over twice.
        csvFileTrigger.pollOnce();

        await().atMost(AWAIT_LIMIT).untilAsserted(() -> {
            assertFalse(Files.exists(input), "no moveToDirectory, so the failed file is deleted");
            assertEquals(1, pollerAppender.list.size(), pollerAppender.list::toString);
        });
        List<ILoggingEvent> events = pollerAppender.list;
        ILoggingEvent error = events.get(0);
        assertEquals(Level.ERROR, error.getLevel());
        Matcher matcher = IMPORT_FAILURE.matcher(error.getFormattedMessage());
        assertTrue(matcher.matches(), error.getFormattedMessage());
    }

    private static void moveInto(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, target);
        }
    }
}
