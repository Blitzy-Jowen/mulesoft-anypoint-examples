/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.mongo.StandardMongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;

/**
 * Tests {@link MongoConfig} together with the two Spring Boot auto-configurations it cooperates with,
 * {@link MongoAutoConfiguration} and {@link MongoDataAutoConfiguration}, in an {@link ApplicationContextRunner}.
 *
 * <p>Each context gets the three {@code database.*} keys and {@code spring.data.mongodb.database: ${database.name}},
 * as {@code application.yml} binds it, through a {@link MapPropertySource} rather than {@code withPropertyValues},
 * which trims values, so empty and whitespace-only values reach the configuration unchanged.
 * <ul>
 *   <li>A missing setting starts the context with {@link UnconfiguredMongoDatabaseFactory} and no
 *       {@code MongoClient}, logs exactly one WARN naming the key and its environment variable without any value,
 *       and makes the {@code MongoTemplate}'s first call fail with an {@link IllegalStateException} naming the key.
 *       Host and port point at a loopback socket that must receive no connection.</li>
 *   <li>Complete settings leave Spring Boot's {@code MongoClient} and {@link SimpleMongoClientDatabaseFactory} in place
 *       and log nothing. The client settings are rebuilt from the context exactly as Spring Boot's
 *       {@code MongoClientFactory} builds them, the {@code MongoClientSettings} bean customized by every
 *       {@link MongoClientSettingsBuilderCustomizer} in order, to read the credential the client receives. Host,
 *       port and connection strings point at an in-memory mongo-java-server bound to a free loopback port, which the
 *       client's monitor may reach.</li>
 *   <li>A connection string the driver cannot parse is not reported as a missing setting: Spring Boot's own startup
 *       check rejects it.</li>
 * </ul>
 * The log oracle is a Logback {@link ListAppender} on {@link MongoConfig}'s logger, attached for each test and
 * detached afterwards.
 */
class MongoConfigTest {

    private static final String USER = "csv_import_user";

    /** A password no log line may contain. */
    private static final String PASSWORD = "csv-import-test-password";

    private static final String NAME_WARNING = "database.name (environment variable DATABASE_NAME) is not "
            + "configured: the application starts without a MongoDB connection, and every import fails until it is "
            + "supplied";

    private static final String USER_WARNING = "database.user (environment variable DATABASE_USER) is not "
            + "configured while database.password is: the application starts without a MongoDB connection, and every "
            + "import fails until it is supplied";

    private static final String PASSWORD_WARNING = "database.password (environment variable DATABASE_PASSWORD) is "
            + "not configured while database.user is: the application starts without a MongoDB connection, and every "
            + "import fails until it is supplied";

    /** How long the loopback socket of an unconfigured context waits for a connection that must not come. */
    private static final int NO_CONNECTION_WAIT_MILLIS = 500;

    private static MongoServer mongoServer;

    private static InetSocketAddress mongoAddress;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, MongoDataAutoConfiguration.class))
            .withUserConfiguration(MongoConfig.class);

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void startMongoServer() {
        mongoServer = new MongoServer(new MemoryBackend());
        mongoAddress = mongoServer.bind();
    }

    @AfterAll
    static void stopMongoServer() {
        mongoServer.shutdownNow();
    }

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(MongoConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    static Stream<Arguments> missingSettings() {
        return Stream.of(
                Arguments.of("every database key empty",
                        Map.of("database.name", "", "database.user", "", "database.password", ""),
                        "database.name is not configured", NAME_WARNING),
                Arguments.of("database name whitespace only",
                        Map.of("database.name", " \t", "database.user", "", "database.password", ""),
                        "database.name is not configured", NAME_WARNING),
                Arguments.of("database name empty, user and password supplied",
                        Map.of("database.name", "", "database.user", USER, "database.password", PASSWORD),
                        "database.name is not configured", NAME_WARNING),
                Arguments.of("database name empty, connection string without database",
                        Map.of("database.name", "", "database.user", "", "database.password", "",
                                "spring.data.mongodb.uri", "mongodb://127.0.0.1:${test.no-connection.port}"),
                        "database.name is not configured", NAME_WARNING),
                Arguments.of("user without password",
                        Map.of("database.name", "customers", "database.user", USER, "database.password", ""),
                        "database.password is not configured", PASSWORD_WARNING),
                Arguments.of("password without user",
                        Map.of("database.name", "customers", "database.user", "", "database.password", PASSWORD),
                        "database.user is not configured", USER_WARNING));
    }

    /**
     * @param description     the case, naming it in the report
     * @param settings        the {@code database.*} keys and any connection string
     * @param expectedMessage the message of every failed database access
     * @param expectedWarning the single startup warning
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSettings")
    void missingSettingStartsWithoutClientAndFailsAtUseNamingTheKey(String description, Map<String, String> settings,
            String expectedMessage, String expectedWarning) throws IOException {
        try (ServerSocket mongoPort = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            mongoPort.setSoTimeout(NO_CONNECTION_WAIT_MILLIS);
            Map<String, Object> properties = withDatabaseMapping(settings);
            properties.put("spring.data.mongodb.host", "127.0.0.1");
            properties.put("spring.data.mongodb.port", mongoPort.getLocalPort());
            properties.put("test.no-connection.port", mongoPort.getLocalPort());

            withProperties(properties).run(context -> {
                assertNull(context.getStartupFailure());
                assertInstanceOf(UnconfiguredMongoDatabaseFactory.class, context.getBean(MongoDatabaseFactory.class));
                assertEquals(0, context.getBeanNamesForType(MongoClient.class).length,
                        "no MongoDB client exists while a setting is missing");

                MongoTemplate mongoTemplate = context.getBean(MongoTemplate.class);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> mongoTemplate.collectionExists("customers"));
                assertEquals(expectedMessage, failure.getMessage());

                // The credential customizer exists in every context; without a usable database it applies none.
                MongoClientSettings.Builder builder = MongoClientSettings.builder();
                context.getBeanProvider(MongoClientSettingsBuilderCustomizer.class).orderedStream()
                        .forEach(customizer -> customizer.customize(builder));
                assertNull(builder.build().getCredential());
            });

            assertThrows(SocketTimeoutException.class, mongoPort::accept, "no MongoDB connection is attempted");
        }

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), events::toString);
        ILoggingEvent warning = events.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        assertEquals(expectedWarning, warning.getFormattedMessage());
        assertFalse(warning.getFormattedMessage().contains(PASSWORD), "the warning never carries a value");
        assertFalse(warning.getFormattedMessage().contains(USER), "the warning never carries a value");
        Object[] arguments = warning.getArgumentArray();
        assertTrue(arguments == null || arguments.length == 0, warning::toString);
        assertNull(warning.getThrowableProxy());
    }

    static Stream<Arguments> credentialSources() {
        return Stream.of(
                Arguments.of("no authentication database: the database name",
                        Map.of("database.name", "customers", "database.user", USER, "database.password", PASSWORD),
                        "customers"),
                Arguments.of("authentication database admin",
                        Map.of("database.name", "customers", "database.user", USER, "database.password", PASSWORD,
                                "spring.data.mongodb.authentication-database", "admin"),
                        "admin"));
    }

    /**
     * @param description    the case, naming it in the report
     * @param settings       the {@code database.*} keys and any authentication database
     * @param expectedSource the database the credential authenticates against
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("credentialSources")
    void userAndPasswordWithoutConnectionStringBecomeTheClientsCredential(String description,
            Map<String, String> settings, String expectedSource) {
        withProperties(withServerAddress(settings)).run(context -> {
            assertConfigured(context, "customers");

            MongoCredential credential = clientSettings(context).getCredential();
            assertNotNull(credential);
            assertEquals(USER, credential.getUserName());
            assertEquals(expectedSource, credential.getSource());
            assertArrayEquals(PASSWORD.toCharArray(), credential.getPassword());
            assertNull(credential.getMechanism(), "the mechanism is negotiated, as for a user in a connection string");
        });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    static Stream<Arguments> settingsWithoutCredential() {
        return Stream.of(
                Arguments.of("no user and no password",
                        Map.of("database.name", "customers", "database.user", "", "database.password", ""),
                        "customers"),
                Arguments.of("connection string: database name selects the database",
                        Map.of("database.name", "customers", "database.user", USER, "database.password", PASSWORD,
                                "spring.data.mongodb.uri", "mongodb://${test.mongo.address}/other"),
                        "customers"),
                Arguments.of("connection string: its database applies while the database name is empty",
                        Map.of("database.name", "", "database.user", USER, "database.password", PASSWORD,
                                "spring.data.mongodb.uri", "mongodb://${test.mongo.address}/fromuri"),
                        "fromuri"),
                Arguments.of("connection string: a user without password is not used",
                        Map.of("database.name", "customers", "database.user", USER, "database.password", "",
                                "spring.data.mongodb.uri", "mongodb://${test.mongo.address}"),
                        "customers"));
    }

    /**
     * @param description      the case, naming it in the report
     * @param settings         the {@code database.*} keys and any connection string
     * @param expectedDatabase the database the factory selects
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("settingsWithoutCredential")
    void noCredentialWithoutUserAndPasswordOrOnceAConnectionStringIsSet(String description,
            Map<String, String> settings, String expectedDatabase) {
        withProperties(withServerAddress(settings)).run(context -> {
            assertConfigured(context, expectedDatabase);
            assertNull(clientSettings(context).getCredential());
        });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    /**
     * A connection string the driver cannot parse names no database, but is not reported as a missing
     * {@code database.name}: Spring Boot rejects it at startup with the driver's own message, as without this class.
     */
    @Test
    void malformedConnectionStringIsLeftToSpringBootsStartupCheck() {
        withProperties(withServerAddress(Map.of("database.name", "", "database.user", "", "database.password", "",
                "spring.data.mongodb.uri", "not-a-mongodb-uri"))).run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    Throwable rootCause = failure;
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    assertInstanceOf(IllegalArgumentException.class, rootCause, failure::toString);
                    assertTrue(rootCause.getMessage().startsWith("The connection string is invalid."),
                            rootCause::getMessage);
                });

        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }

    @Test
    void credentialCustomizerRunsAfterBootsStandardCustomizer() {
        withProperties(withServerAddress(
                Map.of("database.name", "customers", "database.user", USER, "database.password", PASSWORD)))
                .run(context -> {
                    List<MongoClientSettingsBuilderCustomizer> customizers = context
                            .getBeanProvider(MongoClientSettingsBuilderCustomizer.class).orderedStream().toList();
                    assertEquals(2, customizers.size(), customizers::toString);
                    assertInstanceOf(StandardMongoClientSettingsBuilderCustomizer.class, customizers.get(0));
                    assertInstanceOf(MongoConfig.DatabaseUserCredentialCustomizer.class, customizers.get(1));
                });
    }

    private static void assertConfigured(AssertableApplicationContext context, String expectedDatabase) {
        assertNull(context.getStartupFailure());
        assertEquals(1, context.getBeanNamesForType(MongoClient.class).length);
        MongoDatabaseFactory factory = context.getBean(MongoDatabaseFactory.class);
        assertInstanceOf(SimpleMongoClientDatabaseFactory.class, factory);
        assertEquals(expectedDatabase, factory.getMongoDatabase().getName());
        assertNotNull(context.getBean(MongoTemplate.class));
    }

    /** The settings Spring Boot's {@code MongoClientFactory} gives the client of {@code context}. */
    private static MongoClientSettings clientSettings(AssertableApplicationContext context) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder(context.getBean(MongoClientSettings.class));
        context.getBeanProvider(MongoClientSettingsBuilderCustomizer.class).orderedStream()
                .forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /** {@code settings} with host and port of this class's mongo-java-server, which connection strings name too. */
    private static Map<String, Object> withServerAddress(Map<String, String> settings) {
        Map<String, Object> properties = withDatabaseMapping(settings);
        properties.put("spring.data.mongodb.host", mongoAddress.getHostString());
        properties.put("spring.data.mongodb.port", mongoAddress.getPort());
        properties.put("test.mongo.address", mongoAddress.getHostString() + ":" + mongoAddress.getPort());
        return properties;
    }

    /** {@code settings} with the database mapping of {@code application.yml}. */
    private static Map<String, Object> withDatabaseMapping(Map<String, String> settings) {
        Map<String, Object> properties = new HashMap<>(settings);
        properties.put(MongoDatabaseSettings.DATABASE_KEY, "${database.name}");
        return properties;
    }

    private ApplicationContextRunner withProperties(Map<String, Object> properties) {
        return contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("mongoSettings", properties)));
    }
}
