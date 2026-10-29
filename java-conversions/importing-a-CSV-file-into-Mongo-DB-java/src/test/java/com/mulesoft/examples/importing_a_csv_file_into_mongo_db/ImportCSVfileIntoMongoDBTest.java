/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.trigger.CsvFileTrigger;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;

/**
 * Port of the original {@code ImportCSVfileIntoMongoDBIT}: {@code input.csv} dropped into the input directory of
 * flow {@code csv-to-mongodbFlow1} must leave exactly one document in collection {@code customers} of database
 * {@code customers}.
 *
 * <p>The original ran the Mule flow against a Flapdoodle MongoDB 2.0.5 on port 27017. Here the whole Spring Boot
 * application runs against mongo-java-server, a MongoDB wire-protocol server held in memory and bound to a free
 * loopback port, so the test needs no external server and no fixed port:
 * <ul>
 *   <li>{@code client.dispatch} to {@code file://./src/main/resources/input} with {@code outputPattern}
 *       {@code input.csv} and {@code Thread.sleep(5000)} become {@code input.csv} moved into this class's input
 *       directory, {@link CsvFileTrigger#pollOnce()} and a wait bounded by the same 5 seconds;</li>
 *   <li>{@code db.addUser}/{@code dropUser} are gone: the in-memory server checks no credentials, and the connection
 *       string below carries none. Profile {@code test} still supplies the original {@code database.user}
 *       ({@code test_user}) and {@code database.password} ({@code test_password}), which
 *       {@code config.MongoConfig} does not apply once the connection string is set, and {@code database.name}
 *       ({@code customers}), which selects the database;</li>
 *   <li>{@code getCount()} on the legacy driver's {@code DBCollection} becomes {@code countDocuments()}.</li>
 * </ul>
 * The file endpoint's disposition (no {@code moveToDirectory}, so the polled file is deleted) and the stored
 * document's fields are checked as well.
 *
 * <p>The context is closed after the class: its {@code MongoClient} points at the server {@link #stop()} shuts down,
 * and its scheduled trigger at the directory {@link #stop()} deletes, so it must not stay in the context cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ImportCSVfileIntoMongoDBTest {

    /** Database and collection name of the original test (l.44, l.68, l.109). */
    private static final String DB_NAME = "customers";

    /** The original's {@code MONGO_HOST}; the server below is bound to a loopback address. */
    private static final String MONGO_HOST = "localhost";

    /** The name the original dispatched the file under ({@code outputPattern} {@code input.csv}). */
    private static final String INPUT_CSV = "input.csv";

    /** Longest wait for the polled {@code input.csv} to be deleted and one document to be in {@code customers}. */
    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(5);

    /** Bound before the context starts, because {@link #properties} reads its port. */
    private static final MongoServer MONGO_SERVER = new MongoServer(new MemoryBackend());

    private static final InetSocketAddress MONGO_ADDRESS;

    private static final Path WORK_DIR;

    /** The directory {@link CsvFileTrigger} polls in this class's context. */
    private static final Path INPUT_DIR;

    /** Outside the input directory and on the same file store, so a staged file can be moved in atomically. */
    private static final Path STAGING_DIR;

    static {
        MONGO_ADDRESS = MONGO_SERVER.bind();
        try {
            WORK_DIR = Files.createTempDirectory("csv-import-mongo-test-");
            INPUT_DIR = Files.createDirectory(WORK_DIR.resolve("input"));
            STAGING_DIR = Files.createDirectory(WORK_DIR.resolve("staging"));
        } catch (IOException e) {
            MONGO_SERVER.shutdownNow();
            throw new UncheckedIOException("Cannot create the test directories", e);
        }
    }

    @Autowired
    CsvFileTrigger csvFileTrigger;

    @Autowired
    MongoTemplate mongoTemplate;

    /**
     * Points the MongoDB connection and the file endpoint at this class's server and directory. Spring Boot uses the
     * connection string alone once {@code spring.data.mongodb.uri} is set, so host {@code localhost}, port
     * {@code 27017} and the {@code database.*} credentials are not used. Dynamic properties also outrank the
     * surefire system property that points {@code csv-import.input-dir} under {@code target/}. The polling frequency
     * of one hour leaves the scheduled startup poll, on the still empty directory, as the only scheduled one.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> "mongodb://" + MONGO_HOST + ":" + MONGO_ADDRESS.getPort() + "/" + DB_NAME);
        registry.add("csv-import.input-dir", INPUT_DIR::toString);
        registry.add("csv-import.polling-frequency", () -> "3600000");
    }

    @AfterAll
    static void stop() throws IOException {
        MONGO_SERVER.shutdownNow();
        deleteRecursively(WORK_DIR);
    }

    /**
     * Every run starts on the "collection absent" branch of the flow's choice ({@code csv-to-mongodb.xml} l.27-34).
     * The drop is guarded because mongo-java-server logs a dropped absent collection as an ERROR with a stack trace,
     * although the driver ignores the reply.
     */
    @BeforeEach
    void dropCustomersCollection() {
        if (mongoTemplate.collectionExists(DB_NAME)) {
            mongoTemplate.dropCollection(DB_NAME);
        }
    }

    @Test
    void sendCSVFile() throws Exception {
        byte[] payload = new ClassPathResource(INPUT_CSV).getContentAsByteArray();
        Path input = INPUT_DIR.resolve(INPUT_CSV);
        // Staged and moved in: the scheduled startup poll runs on its own thread and must never list a half-written
        // file.
        moveInto(Files.write(STAGING_DIR.resolve(INPUT_CSV), payload), input);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        csvFileTrigger.pollOnce();

        await().atMost(AWAIT_LIMIT).untilAsserted(() -> {
            assertFalse(Files.exists(input), "no moveToDirectory, so the polled file is deleted");
            assertEquals(1, mongoTemplate.getCollection(DB_NAME).countDocuments());
        });

        // The original counted in mongo.getDB("customers").
        assertEquals(DB_NAME, mongoTemplate.getDb().getName());

        List<Document> documents = mongoTemplate.findAll(Document.class, DB_NAME);
        assertEquals(1, documents.size());
        Document customer = documents.get(0);
        assertEquals("John", customer.get("firstname"));
        assertEquals("Doe", customer.get("surname"));
        // A string, with its leading zero: the flow coerces no CSV value.
        assertInstanceOf(String.class, customer.get("phone"));
        assertEquals("096548763", customer.get("phone"));
        // The CRLF belongs to the header line, so the last value of the record carries no '\r'.
        assertEquals("john.doe@texasComp.com", customer.get("email"));
        // Mule's save-object-from-map stored the map as it was: its four keys and the generated _id, no _class.
        assertFalse(customer.containsKey("_class"));
        assertEquals(Set.of("_id", "firstname", "surname", "phone", "email"), customer.keySet());
        assertEquals(List.of("firstname", "surname", "phone", "email"),
                customer.keySet().stream().filter(key -> !"_id".equals(key)).toList(),
                "the record keeps the key order of the flow's CSV to Map script");
    }

    private static void moveInto(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, target);
        }
    }

    /** Deletes a directory tree; entries that are already gone are skipped. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(root)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }
}
