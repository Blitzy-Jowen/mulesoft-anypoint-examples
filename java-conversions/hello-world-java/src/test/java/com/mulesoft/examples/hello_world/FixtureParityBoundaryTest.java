package com.mulesoft.examples.hello_world;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Isolation of the fixture replay (ST-2) from the systems around it: {@link FixtureParityTest} suppresses every
 * scheduled trigger in its application context, and refuses a configuration that reaches a target the replay does
 * not own ({@link FixtureParityTest#effectiveProperties}, {@link FixtureParityTest#boundaryViolations},
 * {@link FixtureParityTest#replayOwnedDatabase}, {@link FixtureParityTest#replayOwnedStore}).
 *
 * <p>The engine's static helpers are called alone, on a plain Spring context, on an environment whose host sources
 * the test sets, and on literal property maps and host lists, so none of the replay state {@link FixtureParityTest}
 * keeps for its own run is touched. {@link #OWNED_PORT} stands for a port
 * a replay server listens on; nothing listens there. The refusals mask the credentials of the values they print
 * ({@link FixtureParityTest#redact}).
 */
class FixtureParityBoundaryTest {

    private static final int OWNED_PORT = 45_678;
    private static final Set<Integer> OWNED_PORTS = Set.of(OWNED_PORT);
    private static final String SCHEDULED_PROCESSOR = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;

    private static final String RABBIT = "org.springframework.amqp.rabbit.connection.CachingConnectionFactory";
    private static final String ARTEMIS = "org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory";
    private static final String ARTEMIS_EMBEDDED = "org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ";
    private static final String MONGO = "com.mongodb.client.MongoClient";
    private static final String LDAP = "org.springframework.ldap.core.LdapTemplate";

    private static WireMockServer upstream;

    /** Stands for the replay's temporary root. */
    @TempDir
    Path replayRoot;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void stubUpstream() {
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/poll")).willReturn(ok("polled")));
    }

    @Test
    void suppressedSchedulingLeavesOnlyTheExplicitSingleRun() throws Exception {
        AtomicBoolean suppressed = new AtomicBoolean();
        try (AnnotationConfigApplicationContext context = pollerContext(beanFactory ->
                suppressed.set(FixtureParityTest.suppressScheduledTriggers(beanFactory)))) {
            CountingPoller poller = context.getBean(CountingPoller.class);

            assertTrue(suppressed.get(), "@EnableScheduling declared the processor the replay removes");
            assertFalse(context.containsBeanDefinition(SCHEDULED_PROCESSOR));
            Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                    .until(() -> poller.runs() == 0);
            assertEquals(0, upstream.getAllServeEvents().size(), "no automatic upstream call");

            poller.pollOnce();

            assertEquals(1, poller.runs(), "exactly the explicit single run");
            upstream.verify(1, getRequestedFor(urlEqualTo("/poll")));
            assertEquals(1, upstream.getAllServeEvents().size(), "exactly the one outbound call of that run");
        }
    }

    @Test
    void withoutSuppressionTheScheduleRunsByItself() {
        try (AnnotationConfigApplicationContext context = pollerContext(beanFactory -> { })) {
            CountingPoller poller = context.getBean(CountingPoller.class);

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> poller.runs() > 0);
            assertTrue(context.containsBeanDefinition(SCHEDULED_PROCESSOR));
        }
    }

    static Stream<Arguments> targetsTheReplayDoesNotOwn() {
        return Stream.of(
                arguments("a production HTTPS endpoint",
                        Map.of("sfdc.auth-endpoint", "https://login.salesforce.com/services/Soap/u/65.0"), Set.of(),
                        "sfdc.auth-endpoint"),
                arguments("a loopback broker on a port no replay server owns",
                        Map.of("spring.artemis.broker-url", "tcp://localhost:61616"), Set.of(),
                        "spring.artemis.broker-url"),
                arguments("the JDBC URL of a local MySQL server",
                        Map.of("jdbc.url", "jdbc:mysql://localhost:3306/db"), Set.of(), "jdbc.url"),
                arguments("an IBM MQ host(port) list",
                        Map.of("ibm.mq.conn-name", "localhost(1414)"), Set.of(), "ibm.mq.conn-name"),
                arguments("a host key naming a remote host",
                        Map.of("wday.hostname", "impl-cc.workday.com"), Set.of(), "wday.hostname"),
                arguments("a loopback host key whose sibling port no replay server owns",
                        Map.of("ftp.host", "localhost", "ftp.port", "21"), Set.of(), "ftp.host"),
                arguments("an empty base URL, from which the client derives a live host",
                        Map.of("netsuite.base-url", ""), Set.of(), "netsuite.base-url"),
                arguments("a multi-host MongoDB URI with one remote host",
                        Map.of("app.mongo-uri", "mongodb://localhost:45678,db.example.com:27017/orders"), Set.of(),
                        "app.mongo-uri"),
                arguments("a failover list with one remote broker",
                        Map.of("app.broker", "failover:(tcp://localhost:45678,tcp://broker.example.com:61616)"),
                        Set.of(), "app.broker"),
                arguments("a loopback URL on an unowned port behind user information holding $",
                        Map.of("scratch.upstream", "http://user:p$ss@127.0.0.1:1/poll"), Set.of(), "scratch.upstream"),
                arguments("a CORS origin pattern no fixture exempts",
                        Map.of("cors.allowed-origins", "http://localhost*"), Set.of(), "cors.allowed-origins"),
                arguments("port 0 of a remote host",
                        Map.of("app.callback-url", "http://remote.example:0/x"), Set.of(), "app.callback-url"),
                arguments("a host key holding a placeholder-like host",
                        Map.of("smtp.host", "<host>"), Set.of(), "smtp.host"),
                arguments("an Oracle thin URL naming a remote host and SID",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@db.example.com:1521:x"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle thin URL naming a remote host and service",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@//db.example.com:1521/svc"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle OCI URL naming a TNS alias",
                        Map.of("spring.datasource.url", "jdbc:oracle:oci:@TNSALIAS"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle thin URL naming a TNS descriptor",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)"
                                + "(HOST=localhost)(PORT=45678))(CONNECT_DATA=(SERVICE_NAME=svc)))"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle thin URL on a loopback port no replay server owns",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@localhost:1521:x"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle thin URL on port 0 of a loopback host",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@localhost:0:x"), Set.of(),
                        "spring.datasource.url"),
                arguments("an Oracle thin URL on a loopback host without a port",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@localhost"), Set.of(),
                        "spring.datasource.url"),
                arguments("an upper-case JDBC prefix naming a remote host",
                        Map.of("app.db-url", "JDBC:ORACLE:THIN:@DB.EXAMPLE.COM:1521:X"), Set.of(), "app.db-url"),
                arguments("a remote Oracle host behind a parameter naming an owned loopback URL",
                        Map.of("spring.datasource.url",
                                "jdbc:oracle:thin:@db.example.com:1521/svc?proxy=http://localhost:45678/"),
                        Set.of(), "spring.datasource.url"),
                arguments("a JDBC URL whose driver picks the host, such as PostgreSQL's localhost:5432",
                        Map.of("spring.datasource.url", "jdbc:postgresql:orders"), Set.of(),
                        "spring.datasource.url"),
                arguments("a JDBC URL naming only an ODBC data source",
                        Map.of("jdbc.url", "jdbc:odbc:orders"), Set.of(), "jdbc.url"),
                arguments("the Spring AMQP default localhost:5672",
                        Map.of(), Set.of(RABBIT), "spring.rabbitmq.host"),
                arguments("the Artemis default tcp://localhost:61616 without an embedded server",
                        Map.of(), Set.of(ARTEMIS), "spring.artemis.broker-url"),
                arguments("Artemis in native mode beside an embedded server",
                        Map.of("spring.artemis.mode", "native"), Set.of(ARTEMIS, ARTEMIS_EMBEDDED),
                        "spring.artemis.broker-url"),
                arguments("the MongoDB driver default localhost:27017",
                        Map.of(), Set.of(MONGO), "spring.data.mongodb.host"),
                arguments("the Spring LDAP default ldap://localhost:389",
                        Map.of(), Set.of(LDAP), "spring.ldap.urls"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targetsTheReplayDoesNotOwn")
    void rejectsATargetTheReplayDoesNotOwn(String description, Map<String, String> properties, Set<String> classes,
            String key) {
        List<String> violations = FixtureParityTest.boundaryViolations(properties, OWNED_PORTS, Set.of(),
                classes::contains);

        assertEquals(1, violations.size(), () -> "one violation, under " + key + ": " + violations);
        assertTrue(violations.get(0).startsWith(key + " = \""), () -> "names the key and its value: " + violations);
        String message = FixtureParityTest.boundaryMessage(violations);
        assertTrue(message.contains(violations.get(0)), message);
        assertTrue(message.contains("state.properties") && message.contains("state.notContacted"),
                () -> "names the remedy: " + message);
    }

    static Stream<Arguments> targetsTheReplayOwns() {
        return Stream.of(
                arguments("a loopback URL on an owned port",
                        Map.of("proxy.upstream-url", "http://localhost:45678/api"), Set.of(), Set.of()),
                arguments("an IPv6 loopback URL on an owned port",
                        Map.of("proxy.upstream-url", "http://[::1]:45678/api"), Set.of(), Set.of()),
                arguments("an in-memory H2 database",
                        Map.of("spring.datasource.url", "jdbc:h2:mem:replay;MODE=MySQL"), Set.of(), Set.of()),
                arguments("an embedded Derby database",
                        Map.of("spring.datasource.url", "jdbc:derby:muleEmbeddedDB;create=true"), Set.of(), Set.of()),
                arguments("an in-memory HSQLDB database",
                        Map.of("spring.datasource.url", "jdbc:hsqldb:mem:orders"), Set.of(), Set.of()),
                arguments("an embedded SQLite database",
                        Map.of("spring.datasource.url", "jdbc:sqlite:orders.db"), Set.of(), Set.of()),
                arguments("an embedded H2 database written with an upper-case prefix",
                        Map.of("spring.datasource.url", "JDBC:H2:mem:replay"), Set.of(), Set.of()),
                arguments("an embedded H2 database whose parameters hold an @",
                        Map.of("spring.datasource.url", "jdbc:h2:mem:replay;USER=sa@replay"), Set.of(), Set.of()),
                arguments("an Oracle thin URL with SID on an owned loopback port",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:@localhost:45678:x"), Set.of(), Set.of()),
                arguments("an Oracle thin URL with user, service and an owned IPv6 loopback port",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:scott/tiger@//[::1]:45678/svc"), Set.of(),
                        Set.of()),
                arguments("an in-VM Artemis broker",
                        Map.of("spring.artemis.mode", "native", "spring.artemis.broker-url", "vm://0"),
                        Set.of(ARTEMIS), Set.of()),
                arguments("Artemis started embedded by default",
                        Map.of(), Set.of(ARTEMIS, ARTEMIS_EMBEDDED), Set.of()),
                arguments("a key the original never contacted",
                        Map.of("box.authorization-url", "https://www.box.com/api/oauth2/authorize"), Set.of(),
                        Set.of("box.authorization-url")),
                arguments("a CORS origin pattern the original never contacted",
                        Map.of("cors.allowed-origins", "http://localhost*"), Set.of(),
                        Set.of("cors.allowed-origins")),
                arguments("the application's own RANDOM_PORT listener",
                        Map.of("oauth.redirect-uri", "http://localhost:0/web/loginDone"), Set.of(), Set.of()),
                arguments("a loopback host key on an owned sibling port",
                        Map.of("spring.mail.host", "localhost", "spring.mail.port", "45678"), Set.of(), Set.of()),
                arguments("Spring AMQP on an owned port",
                        Map.of("spring.rabbitmq.port", "45678"), Set.of(RABBIT), Set.of()),
                arguments("a Spring AMQP address with user information on an owned port",
                        Map.of("spring.rabbitmq.addresses", "guest:guest@localhost:45678"), Set.of(RABBIT), Set.of()),
                arguments("the MongoDB URI of the replay server",
                        Map.of("spring.data.mongodb.uri", "mongodb://127.0.0.1:45678/test"), Set.of(MONGO), Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("targetsTheReplayOwns")
    void acceptsATargetTheReplayOwns(String description, Map<String, String> properties, Set<String> classes,
            Set<String> exemptKeys) {
        assertEquals(List.of(), FixtureParityTest.boundaryViolations(properties, OWNED_PORTS, exemptKeys,
                classes::contains));
    }

    static Stream<Arguments> connectionKeysSetOnlyByTheHost() {
        return Stream.of(
                arguments("SPRING_DATASOURCE_URL naming a production MySQL server",
                        Map.of("SPRING_DATASOURCE_URL", "jdbc:mysql://prod-db.example.com:3306/orders"), Map.of(),
                        Map.of(), "spring.datasource.url"),
                arguments("SPRING_DATASOURCE_HIKARI_JDBC_URL naming a remote Oracle server",
                        Map.of("SPRING_DATASOURCE_HIKARI_JDBC_URL", "jdbc:oracle:thin:@db.example.com:1521:x"), Map.of(),
                        Map.of(), "spring.datasource.hikari.jdbc-url"),
                arguments("SPRING_R2DBC_URL naming a remote PostgreSQL server",
                        Map.of("SPRING_R2DBC_URL", "r2dbc:postgresql://db.example.com:5432/orders"), Map.of(), Map.of(),
                        "spring.r2dbc.url"),
                arguments("SPRING_MAIL_HOST naming a remote mail server",
                        Map.of("SPRING_MAIL_HOST", "smtp.example.com"), Map.of(), Map.of(), "spring.mail.host"),
                arguments("SPRING_MAIL_PORT moving a declared loopback mail host to an unowned port",
                        Map.of("SPRING_MAIL_PORT", "2525"), Map.of(), Map.of("spring.mail.host", "localhost"),
                        "spring.mail.host"),
                arguments("SPRING_DATA_MONGODB_URI naming a remote MongoDB server",
                        Map.of("SPRING_DATA_MONGODB_URI", "mongodb://mongo.example.com:27017/orders"), Map.of(),
                        Map.of(), "spring.data.mongodb.uri"),
                arguments("SPRING_LDAP_URLS naming a remote directory",
                        Map.of("SPRING_LDAP_URLS", "ldap://ldap.example.com:389"), Map.of(), Map.of(),
                        "spring.ldap.urls"),
                arguments("the system property spring.datasource.url naming a production server",
                        Map.of(), Map.of("spring.datasource.url", "jdbc:mysql://prod-db.example.com:3306/orders"),
                        Map.of(), "spring.datasource.url"));
    }

    /** The host's own variables and properties are not enumerated, but Boot's connection keys are read through them. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionKeysSetOnlyByTheHost")
    void rejectsAConnectionKeySetOnlyByTheHost(String description, Map<String, String> variables,
            Map<String, String> systemProperties, Map<String, String> declared, String key) {
        Map<String, String> effective =
                FixtureParityTest.effectiveProperties(hostEnvironment(variables, systemProperties, declared));
        List<String> violations = FixtureParityTest.boundaryViolations(effective, OWNED_PORTS, Set.of(),
                className -> false);

        assertTrue(effective.containsKey(key), () -> key + " is read through the host's sources: " + effective);
        assertEquals(1, violations.size(), () -> "one violation, under " + key + ": " + violations);
        assertTrue(violations.get(0).startsWith(key + " = \""), () -> "names the key and its value: " + violations);
    }

    @Test
    void readsOnlyTheConnectionKeysFromTheHostsOwnSources() {
        Map<String, String> effective = FixtureParityTest.effectiveProperties(hostEnvironment(
                Map.of("SPRING_DATASOURCE_URL", "jdbc:h2:mem:replay", "UNRELATED_SERVICE_URL", "https://example.com"),
                Map.of("unrelated.callback-url", "https://example.com/callback"), Map.of("app.greeting", "hello")));

        assertEquals(Map.of("spring.datasource.url", "jdbc:h2:mem:replay", "app.greeting", "hello"), effective,
                "a connection key enters only when it resolves; other host variables are not enumerated");
        assertEquals(List.of(), FixtureParityTest.boundaryViolations(effective, OWNED_PORTS, Set.of(),
                className -> false));
    }

    /**
     * An environment whose host variables and system properties are exactly {@code variables} and
     * {@code systemProperties}, with {@code declared} as the application's own configuration file.
     */
    private static StandardEnvironment hostEnvironment(Map<String, String> variables,
            Map<String, String> systemProperties, Map<String, String> declared) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(variables)));
        sources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, new HashMap<>(systemProperties)));
        sources.addLast(new MapPropertySource("Config resource 'class path resource [application.yml]'",
                new HashMap<>(declared)));
        return environment;
    }

    /** {@code {root}} in a URL stands for the replay root. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "jdbc:h2:mem:replay;MODE=MySQL                                  | true",
            "jdbc:derby:memory:orders;create=true                           | true",
            "jdbc:hsqldb:mem:orders                                         | true",
            "jdbc:h2:tcp://localhost:45678/mem:x                            | true",
            "jdbc:mysql://localhost:3306/mule                               | false",
            "jdbc:h2:tcp://localhost:9092/mem:x                             | false",
            "jdbc:mysql://db.example.com:45678/mule                         | false",
            "jdbc:oracle:thin:@db.example.com:1521:x                        | false",
            "jdbc:oracle:thin:@//localhost:45678/x                          | false",
            "jdbc:h2:file:{root}/db/orders                                  | true",
            "jdbc:h2:{root}/db/orders;MODE=MySQL;DB_CLOSE_DELAY=-1          | true",
            "jdbc:h2:file:{root}/db/orders?ignored=true                     | true",
            "jdbc:derby:directory:{root}/derby/orders;create=true           | true",
            "jdbc:derby:{root}/derby/orders                                 | true",
            "jdbc:hsqldb:file:{root}/db/orders;shutdown=true                | true",
            "jdbc:h2:file:/opt/outside-the-replay/preexisting               | false",
            "jdbc:h2:file:{root}/../outside                                 | false",
            "jdbc:h2:file:{root}                                            | false",
            "jdbc:derby:directory:/opt/outside-the-replay/orders            | false",
            "jdbc:hsqldb:file:/opt/outside-the-replay/orders                | false",
            "jdbc:h2:zip:{root}/db.zip!/orders                              | false",
            "jdbc:h2:nio:{root}/db/orders                                   | false",
            "jdbc:h2:file:split:{root}/db/orders                            | false",
            "jdbc:derby:classpath:orders                                    | false",
            "jdbc:derby:jar:({root}/db.jar)orders                           | false",
            "jdbc:hsqldb:res:{root}/orders                                  | false",
            "jdbc:hsqldb:{root}/orders                                      | false",
            "jdbc:sqlite:{root}/orders.db                                   | false",
            "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://db.example.com/x.sql' | false",
    })
    void confirmsOnlyADatabaseTheReplayOwns(String url, boolean owned) {
        assertEquals(owned, FixtureParityTest.replayOwnedDatabase(url.replace("{root}", replayRoot.toString()),
                OWNED_PORTS, replayRoot));
    }

    /**
     * A relative or {@code ~} database path is read against the directory its driver reads it against, here placed
     * inside the replay root or beside it. The system properties are restored afterwards.
     */
    @ParameterizedTest(name = "{1} {0}: {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "user.dir          | inside  | jdbc:h2:./orders                       | true",
            "user.dir          | inside  | jdbc:h2:file:./orders;MODE=MySQL       | true",
            "user.dir          | inside  | jdbc:h2:file:../../escaped             | false",
            "user.dir          | outside | jdbc:h2:./orders                       | false",
            "user.dir          | inside  | jdbc:derby:muleEmbeddedDB;create=true  | true",
            "user.dir          | inside  | jdbc:hsqldb:file:orders;shutdown=true  | true",
            "derby.system.home | inside  | jdbc:derby:muleEmbeddedDB;create=true  | true",
            "derby.system.home | inside  | jdbc:derby:directory:orders            | true",
            "derby.system.home | outside | jdbc:derby:muleEmbeddedDB;create=true  | false",
            "h2.baseDir        | inside  | jdbc:h2:./orders                       | true",
            "h2.baseDir        | outside | jdbc:h2:./orders                       | false",
            "user.home         | inside  | jdbc:h2:~/orders                       | true",
            "user.home         | inside  | jdbc:hsqldb:file:~/orders              | true",
            "user.home         | outside | jdbc:h2:file:~/orders                  | false",
    })
    void readsARelativeDatabasePathAgainstItsBaseDirectory(String property, String where, String url, boolean owned) {
        Path base = where.equals("inside") ? replayRoot.resolve("base")
                : replayRoot.resolveSibling(replayRoot.getFileName() + "-outside");
        List<String> isolated = List.of("user.dir", "user.home", "derby.system.home", "h2.baseDir");
        Map<String, String> saved = new HashMap<>();
        isolated.forEach(name -> saved.put(name, System.getProperty(name)));
        try {
            System.clearProperty("derby.system.home");
            System.clearProperty("h2.baseDir");
            System.setProperty(property, base.toString());

            assertEquals(owned, FixtureParityTest.replayOwnedDatabase(url, OWNED_PORTS, replayRoot));
        } finally {
            saved.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
        }
    }

    @Test
    void refusesAFileDatabaseBehindASymbolicLinkOutOfTheRoot(@TempDir Path outside) throws IOException {
        Files.createSymbolicLink(replayRoot.resolve("link"), outside);

        assertFalse(FixtureParityTest.replayOwnedDatabase("jdbc:h2:file:" + replayRoot.resolve("link/orders"),
                OWNED_PORTS, replayRoot));
    }

    static Stream<Arguments> mongoClientHosts() {
        return Stream.of(
                arguments("the embedded server on an owned loopback port", List.of("127.0.0.1:45678"), true),
                arguments("localhost and the IPv6 loopback on owned ports", List.of("localhost:45678", "[::1]:45678"),
                        true),
                arguments("a loopback host on a port no replay server owns", List.of("127.0.0.1:27017"), false),
                arguments("a remote host on the owned port number", List.of("mongo.example.com:45678"), false),
                arguments("a replica set mixing the embedded server with a remote member",
                        List.of("127.0.0.1:45678", "mongo.example.com:27017"), false),
                arguments("a replica set mixing owned and unowned loopback ports",
                        List.of("127.0.0.1:45678", "127.0.0.1:27018"), false),
                arguments("port 0 of a loopback host", List.of("localhost:0"), false),
                arguments("a host without a port", List.of("localhost"), false),
                arguments("no server address at all", List.of(), false));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("mongoClientHosts")
    void confirmsOnlyACollectionStoreTheReplayOwns(String description, List<String> hosts, boolean owned) {
        assertEquals(owned, FixtureParityTest.replayOwnedStore(hosts, OWNED_PORTS));
    }

    static Stream<Arguments> credentialsInRejectedValues() {
        return Stream.of(
                arguments("a JDBC password query parameter",
                        Map.of("jdbc.url", "jdbc:mysql://localhost:3306/db?user=u&password=S3cretValue"), Set.of(),
                        "jdbc.url", "S3cretValue", "localhost:3306"),
                arguments("URI user information",
                        Map.of("scratch.upstream", "http://user:p$ss@127.0.0.1:1/x"), Set.of(),
                        "scratch.upstream", "p$ss", "127.0.0.1:1"),
                arguments("an H2 PASSWORD setting",
                        Map.of("spring.datasource.url", "jdbc:h2:tcp://localhost:9092/x;PASSWORD=S3cretValue"),
                        Set.of(), "spring.datasource.url", "S3cretValue", "localhost:9092"),
                arguments("a key that names a secret",
                        Map.of("app.api-key", "http://127.0.0.1:1/S3cretValue"), Set.of(),
                        "app.api-key", "S3cretValue", "127.0.0.1:1"),
                arguments("the user information of a client's address list",
                        Map.of("spring.rabbitmq.addresses", "u:S3cretValue@broker.example.com:5672"), Set.of(RABBIT),
                        "spring.rabbitmq.addresses", "S3cretValue", "broker.example.com:5672"),
                arguments("the user and password of an Oracle thin URL with SID",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:scott/S3cretValue@db.example.com:1521:x"),
                        Set.of(), "spring.datasource.url", "S3cretValue", "db.example.com:1521"),
                arguments("the user and password of an Oracle thin URL with service",
                        Map.of("spring.datasource.url",
                                "jdbc:oracle:thin:scott/S3cretValue@//db.example.com:1521/svc"),
                        Set.of(), "spring.datasource.url", "S3cretValue", "db.example.com:1521"),
                arguments("the user and password of an Oracle OCI URL",
                        Map.of("spring.datasource.url", "jdbc:oracle:oci:scott/S3cretValue@TNSALIAS"), Set.of(),
                        "spring.datasource.url", "S3cretValue", "TNSALIAS"),
                arguments("a password holding @ and : in an Oracle thin URL",
                        Map.of("spring.datasource.url", "jdbc:oracle:thin:scott/S3cret:Val@ue@db.example.com:1521:x"),
                        Set.of(), "spring.datasource.url", "S3cret", "db.example.com:1521"),
                arguments("the user and password of an upper-case JDBC prefix",
                        Map.of("app.db-url", "JDBC:ORACLE:THIN:scott/S3cretValue@db.example.com:1521:x"), Set.of(),
                        "app.db-url", "S3cretValue", "db.example.com:1521"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentialsInRejectedValues")
    void masksCredentialsInTheRejection(String description, Map<String, String> properties, Set<String> classes,
            String key, String secret, String target) {
        String message = FixtureParityTest.boundaryMessage(FixtureParityTest.boundaryViolations(properties,
                OWNED_PORTS, Set.of(), classes::contains));

        assertFalse(message.contains(secret), () -> "masks the credential: " + message);
        assertTrue(message.contains(key + " = \""), () -> "names the key: " + message);
        assertTrue(message.contains(target), () -> "names the target " + target + ": " + message);
    }

    /** The URL a DataSource reports, printed when its database is refused, keeps its host and loses its password. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "jdbc:mysql://u:S3cretValue@db.example.com:3306/x                   | db.example.com:3306",
            "jdbc:sqlserver://db.example.com:1433;user=u;password=S3cretValue   | db.example.com:1433",
            "jdbc:oracle:thin:scott/S3cretValue@//db.example.com:1521/x         | db.example.com:1521",
            "jdbc:h2:file:/opt/outside-the-replay/db;CIPHER=AES;PASSWORD=S3cretValue | /opt/outside-the-replay/db",
    })
    void masksCredentialsInADataSourceUrl(String url, String target) {
        String shown = FixtureParityTest.redact(null, url);

        assertFalse(shown.contains("S3cretValue"), shown);
        assertTrue(shown.contains(target), shown);
    }

    private static AnnotationConfigApplicationContext pollerContext(BeanFactoryPostProcessor replayPreparation) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(SchedulingConfiguration.class);
        URI poll = URI.create(upstream.baseUrl() + "/poll");
        context.registerBean(CountingPoller.class, () -> new CountingPoller(poll));
        // Added to the context as the replay's initializer adds it: run after configuration parsing, before beans.
        context.addBeanFactoryPostProcessor(replayPreparation);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {
    }

    /** A scheduled trigger in the shape of the converted ones: a frequent {@code poll()} and a single-run method. */
    static class CountingPoller {

        private final HttpClient client = HttpClient.newHttpClient();
        private final URI upstreamUrl;
        private final AtomicInteger runs = new AtomicInteger();

        CountingPoller(URI upstreamUrl) {
            this.upstreamUrl = upstreamUrl;
        }

        @Scheduled(fixedDelay = 5)
        public void poll() throws IOException, InterruptedException {
            pollOnce();
        }

        /** One run: one outbound call. */
        public void pollOnce() throws IOException, InterruptedException {
            runs.incrementAndGet();
            HttpResponse<String> reply = client.send(HttpRequest.newBuilder(upstreamUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() != 200) {
                throw new IllegalStateException("The upstream answered " + reply.statusCode());
            }
        }

        int runs() {
            return runs.get();
        }
    }
}
