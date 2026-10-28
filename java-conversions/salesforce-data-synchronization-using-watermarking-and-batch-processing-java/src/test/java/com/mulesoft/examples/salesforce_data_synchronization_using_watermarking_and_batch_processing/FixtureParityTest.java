package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Fixture replay engine: shared test component ST-2 of the conversion plan (AAP 0.4.4, 0.8.4).
 *
 * <p>Replays every Tier 2A fixture captured from the original Mule application against this Spring Boot
 * conversion and compares the outcome with what the original produced. The project-specific inputs are
 * {@code fixtures/SCENARIOS.md}, whose {@code ## Scenarios} table names each scenario with its trigger and state
 * and documents the fixture format, and the captured {@code fixtures/*.json}. Every non-RAML project carries a
 * copy of this class that differs from the reference copy kept in hello-world-java in the {@code package} line
 * alone, so nothing here names a project type, and the libraries only some projects carry (JMS, Jakarta Mail,
 * Spring AMQP, Qpid Broker-J, GreenMail, Spring Data MongoDB, BSON, mongo-java-server, UnboundID) are reached by
 * reflection. A trigger whose library, bean or listener port a project lacks is reported
 * {@code UNVERIFIED-missing-driver}; it never fails the run.
 *
 * <p>Replay never contacts a live service: WireMock serves the recorded {@code upstream} exchanges in order,
 * embedded servers hold the seeded {@code state}, and their addresses reach the application through
 * {@link DynamicPropertySource}. The broker reproduces the behaviour the capture seeded as well: it refuses the sends
 * {@code state.jms} names, and the responders of {@code state.amqp.responders} answer requests. Messages are compared
 * on every destination of {@code response.destinations}, one the capture found empty included, which must stay empty.
 * {@code deterministic} scenarios are compared byte for byte without
 * normalization; {@code nondeterministic} ones compare status, reason and headers exactly and bodies, files and
 * messages by structure only, and are reported {@code UNVERIFIED — nondeterministic output}. A fixture is never
 * adjusted to the Java output: a mismatch fails its scenario.
 *
 * <p>{@link ReplayContextInitializer} holds the application context to that boundary before any bean exists. It
 * removes Spring's {@code @Scheduled} processor, so in replay the only trigger executions are the single-run
 * invocations the fixtures name in {@code request.invoke}, which every {@code file}, {@code scheduler} and
 * {@code mail} fixture must carry. It also fails the class, before any client or store connects, when the
 * effective configuration names a target the replay does not own ({@link #boundaryViolations}), a JDBC URL that
 * names its server without {@code ://} (such as {@code jdbc:oracle:thin:@host:1521:sid}) or no address at all
 * included, and a Spring Boot connection key set only in the host's environment variables or system properties
 * ({@link #effectiveProperties}) included; a fixture overrides such a key through {@code state.properties}, or lists
 * in {@code state.notContacted} a key the original never connected to. WireMock runs whenever a fixture is loaded,
 * so an outbound HTTP call that matches no recording fails its scenario, {@code "upstream": []} included. Table
 * state is seeded, emptied and read only in a database the replay owns ({@link #replayOwnedDatabase}): an in-memory
 * one, an embedded file database inside the replay's temporary root, or a server on a replay-owned loopback port.
 * Collection state is dropped, seeded and read only through MongoDB clients configured solely for the embedded
 * server or another replay-owned loopback port ({@link #replayOwnedStore}). Each scenario starts from a fresh reset
 * and gets its upstream stubs before any of its input becomes visible. The boundary's diagnostics mask the
 * credentials a configured value may carry ({@link #redact}).
 *
 * <p>With no fixtures the class is reported skipped, never failed. Every run writes
 * {@code target/fixture-parity/summary.properties} with each scenario's status, which feeds the {@code PARITY:}
 * labels of the README and of {@code SUMMARY.md} (AAP 0.8.7). {@code -Dfixtures.dir=<dir>} replaces the classpath
 * fixtures with {@code <dir>/*.json} (and {@code <dir>/SCENARIOS.md} when present) for local engine checks.
 */
@ExtendWith(FixtureParityTest.FixtureGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = FixtureParityTest.ReplayContextInitializer.class)
class FixtureParityTest {

    static final String NO_FIXTURES = "no Tier 2A fixtures captured";

    static final String VERIFIED = "VERIFIED";
    static final String UNVERIFIED_NONDETERMINISTIC = "UNVERIFIED-nondeterministic";
    static final String UNVERIFIED_NO_UPSTREAM = "UNVERIFIED-no-upstream";
    static final String UNVERIFIED_MISSING_DRIVER = "UNVERIFIED-missing-driver";
    static final String UNVERIFIED_UNCAPTURED = "UNVERIFIED-uncaptured";
    static final String UNREACHABLE = "UNREACHABLE";
    static final String FAILED = "FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(FixtureParityTest.class);

    private static final List<String> COLUMNS =
            List.of("Scenario", "Trigger", "State", "Request", "Seeded state", "Branch", "Source");
    private static final Set<String> TRIGGERS =
            Set.of("http", "soap", "file", "scheduler", "jms", "mq", "amqp", "mail");
    private static final Set<String> STATES = Set.of("deterministic", "nondeterministic", "unreachable");
    /** Triggers the original ran by polling; replay runs them only through {@code request.invoke}. */
    private static final Set<String> INVOKED_TRIGGERS = Set.of("file", "scheduler", "mail");
    private static final List<String> MAIL_PROTOCOLS = List.of("smtp", "smtps", "imap", "imaps", "pop3", "pop3s");

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern GREENMAIL_PORT =
            Pattern.compile("greenmail\\.(smtp|smtps|imap|imaps|pop3|pop3s)\\.port");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]+");
    /**
     * A canonical UUID inside a file name: hexadecimal digits of either case in groups of 8, 4, 4, 4 and 12 joined
     * by hyphens, with no letter or digit run together with it on either side.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "(?<![\\p{L}\\p{Nd}])[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}(?![\\p{L}\\p{Nd}])");
    /** What each {@link #CANONICAL_UUID} of a file name becomes in its {@link #fileNameShape shape}. */
    static final String UUID_TOKEN = "<uuid>";
    private static final Pattern BOUNDARY_PARAMETER =
            Pattern.compile(";\\s*boundary\\s*=\\s*(\"[^\"]*\"|[^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLDED_LINE = Pattern.compile("\\r?\\n[ \\t]+");
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    /** A whole value of {@code host(port)} items, as an IBM MQ {@code connName} lists its queue managers. */
    private static final Pattern HOST_PORT_LIST =
            Pattern.compile("\\s*[^\\s(),]+\\(\\s*[0-9]+\\s*\\)(\\s*,\\s*[^\\s(),]+\\(\\s*[0-9]+\\s*\\))*\\s*");
    private static final Pattern HOST_PORT_ITEM = Pattern.compile("([^\\s(),]+)\\(\\s*([0-9]+)\\s*\\)");
    /** A {@code name=value} parameter of a URL query, a {@code ;} list or a property list, with its separator. */
    private static final Pattern PARAMETER = Pattern.compile("(^|[?&;,(\\s])([^=?&;,()\\s\"']+)=([^&;\\s\"']*)");
    /** Word boundaries of a name: punctuation, and a change from a lower-case letter or digit to an upper-case one. */
    private static final Pattern NAME_WORD_BOUNDARY = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
    /**
     * The words that mark a property key or parameter name as holding a secret, compared ignoring case; compound
     * names such as {@code access_token}, {@code api_key}, {@code api-key} or {@code securityToken} hold one of them.
     */
    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pass", "pwd", "secret", "token",
            "apikey", "key", "credential", "credentials", "auth", "signature", "sig");
    private static final String MASK = "***";

    /** Property sources the replay boundary does not enumerate: the host's own settings and Boot's attached view. */
    private static final Set<String> UNSCANNED_PROPERTY_SOURCES = Set.of(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, "configurationProperties");
    /** Address schemes that never leave the JVM or the local file system. */
    private static final Set<String> IN_JVM_SCHEMES = Set.of("vm", "file", "classpath", "jar");
    /** JDBC subprotocols whose URL without {@code ://} and without {@code @} names an embedded database. */
    private static final Set<String> EMBEDDED_JDBC_SUBPROTOCOLS = Set.of("h2", "derby", "hsqldb", "sqlite");
    /** The port a client of the scheme uses when an address names none. */
    private static final Map<String, Integer> DEFAULT_PORTS = Map.ofEntries(Map.entry("http", 80),
            Map.entry("https", 443), Map.entry("tcp", 61616), Map.entry("ssl", 61616), Map.entry("amqp", 5672),
            Map.entry("amqps", 5671), Map.entry("mongodb", 27017), Map.entry("ldap", 389), Map.entry("ldaps", 636),
            Map.entry("smtp", 25), Map.entry("smtps", 465), Map.entry("imap", 143), Map.entry("imaps", 993),
            Map.entry("pop3", 110), Map.entry("pop3s", 995), Map.entry("ftp", 21));
    /** Spring Boot connection keys whose defaults the boundary checks when the client library is present. */
    private static final List<String> CLIENT_DEFAULT_KEYS = List.of("spring.rabbitmq.addresses",
            "spring.rabbitmq.host", "spring.rabbitmq.port", "spring.artemis.mode", "spring.artemis.broker-url",
            "spring.artemis.embedded.enabled", "spring.data.mongodb.uri", "spring.data.mongodb.host",
            "spring.data.mongodb.port", "spring.ldap.urls");
    /**
     * Spring Boot connection keys the boundary looks up besides {@link #CLIENT_DEFAULT_KEYS}, because Boot binds them
     * from the host's environment variables and system properties too, which the boundary does not enumerate.
     */
    private static final List<String> BOOT_CONNECTION_KEYS = List.of("spring.datasource.url",
            "spring.datasource.hikari.jdbc-url", "spring.r2dbc.url", "spring.mail.host", "spring.mail.port");

    /** Transport fields the replay never sends or compares: the client regenerates them for the replay port. */
    private static final Set<String> TRANSPORT_HEADERS =
            Set.of("host", "content-length", "connection", "transfer-encoding");
    /** Framing fields a recorded upstream reply is served without, because WireMock frames the body itself. */
    private static final Set<String> FRAMING_HEADERS = Set.of("content-length", "transfer-encoding", "connection");
    /** Upstream request fields an HTTP client library sets itself; compared only when the caller passed them on. */
    private static final Set<String> CLIENT_LIBRARY_HEADERS = Set.of("user-agent", "accept-encoding");

    private static final String DEFAULT_PORT_PROPERTY = "local.server.port";
    private static final long DEFAULT_AWAIT_MILLIS = 10_000;
    private static final long DRAIN_MILLIS = 100;
    private static final long NO_FURTHER_MESSAGE_MILLIS = 500;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    /** How far the comparisons of a quiet window may overrun it before the window fails as not completed. */
    static final Duration QUIET_WINDOW_MARGIN = Duration.ofSeconds(1);
    /** {@code jakarta.jms.Session.AUTO_ACKNOWLEDGE}, written out because JMS is reached by reflection. */
    private static final int JMS_AUTO_ACKNOWLEDGE = 1;
    private static final String XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/";

    private static final String CLIENT_CLASS = FixtureParityTest.class.getPackageName() + ".support.HttpTestClient";
    private static final String CONNECTION_FACTORY_CLASS = "jakarta.jms.ConnectionFactory";
    private static final String TEXT_MESSAGE_CLASS = "jakarta.jms.TextMessage";
    private static final String BYTES_MESSAGE_CLASS = "jakarta.jms.BytesMessage";
    private static final String RABBIT_TEMPLATE_CLASS = "org.springframework.amqp.rabbit.core.RabbitTemplate";
    private static final String AMQP_MESSAGE_CLASS = "org.springframework.amqp.core.Message";
    private static final String AMQP_PROPERTIES_CLASS = "org.springframework.amqp.core.MessageProperties";
    private static final String MAIL_SESSION_CLASS = "jakarta.mail.Session";
    private static final String MIME_MESSAGE_CLASS = "jakarta.mail.internet.MimeMessage";
    private static final String RECIPIENT_TYPE_CLASS = "jakarta.mail.Message$RecipientType";
    private static final String GREENMAIL_CLASS = "com.icegreen.greenmail.util.GreenMail";
    private static final String SERVER_SETUP_CLASS = "com.icegreen.greenmail.util.ServerSetup";
    private static final String QPID_CLASS = "org.apache.qpid.server.SystemLauncher";
    private static final String QPID_LISTENER_CLASS = "org.apache.qpid.server.SystemLauncherListener";
    private static final String QPID_AMQP_PORT_CLASS = "org.apache.qpid.server.model.port.AmqpPort";
    private static final String MONGO_SERVER_CLASS = "de.bwaldvogel.mongo.MongoServer";
    private static final String MONGO_BACKEND_CLASS = "de.bwaldvogel.mongo.backend.memory.MemoryBackend";
    private static final String MONGO_TEMPLATE_CLASS = "org.springframework.data.mongodb.core.MongoTemplate";
    private static final String BSON_DOCUMENT_CLASS = "org.bson.Document";
    private static final String LDAP_SERVER_CLASS = "com.unboundid.ldap.listener.InMemoryDirectoryServer";
    private static final String LDAP_CONFIG_CLASS = "com.unboundid.ldap.listener.InMemoryDirectoryServerConfig";
    private static final String LDAP_LISTENER_CLASS = "com.unboundid.ldap.listener.InMemoryListenerConfig";
    private static final String SAX_ERROR_HANDLER_CLASS = "org.xml.sax.ErrorHandler";
    private static final String RABBIT_FACTORY_CLASS =
            "org.springframework.amqp.rabbit.connection.CachingConnectionFactory";
    private static final String ARTEMIS_FACTORY_CLASS =
            "org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory";
    /** The classes whose presence makes Spring Boot start Artemis embedded when no mode is set. */
    private static final List<String> ARTEMIS_EMBEDDED_CLASSES = List.of(
            "org.apache.activemq.artemis.jms.server.embedded.EmbeddedJMS",
            "org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ");
    /** The Artemis server Spring Boot embeds, whose broker plugins reproduce the refused sends of {@code state.jms}. */
    private static final String ARTEMIS_EMBEDDED_SERVER_CLASS =
            "org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ";
    private static final String ARTEMIS_MESSAGE_PLUGIN_CLASS =
            "org.apache.activemq.artemis.core.server.plugin.ActiveMQServerMessagePlugin";
    private static final String ARTEMIS_SECURITY_EXCEPTION_CLASS =
            "org.apache.activemq.artemis.api.core.ActiveMQSecurityException";
    private static final String ARTEMIS_SERVER_SESSION_CLASS =
            "org.apache.activemq.artemis.core.server.impl.ServerSessionImpl";
    private static final String ARTEMIS_IN_VM_CONNECTOR_CLASS =
            "org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory";
    private static final String ARTEMIS_IN_VM_ACCEPTOR_CLASS =
            "org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory";
    /** The synchronous MongoDB client, the one a {@code MongoTemplate} works through. */
    private static final String MONGO_SYNC_CLIENT_CLASS = "com.mongodb.client.MongoClient";
    private static final List<String> MONGO_CLIENT_CLASSES =
            List.of(MONGO_SYNC_CLIENT_CLASS, "com.mongodb.reactivestreams.client.MongoClient");
    private static final String LDAP_TEMPLATE_CLASS = "org.springframework.ldap.core.LdapTemplate";

    private static final Path SUMMARY_FILE = Path.of("target", "fixture-parity", "summary.properties");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** Injected before {@code @BeforeAll}; {@link #tempRoot()} creates its own directory when used earlier. */
    @TempDir
    static Path tempDir;

    private static final Map<String, String> STATUSES = Collections.synchronizedMap(new LinkedHashMap<>());
    /**
     * Ports by name, one distinct port per name; {@code ${freePort:<name>}} and the embedded servers share these
     * names. Created on first use, so the host's socket behaviour is probed only by a replay that allocates a port or
     * starts the application context.
     */
    private static PortRegistry portRegistry;
    /** Every {@code ${dir:<name>}} and every directory a fixture's directory spec resolves to, emptied per scenario. */
    private static final Set<Path> DIRECTORIES = new LinkedHashSet<>();
    /** Server key to the class whose absence kept that embedded server from starting. */
    private static final Map<String, String> MISSING_SERVER_CLASSES = new HashMap<>();

    private static boolean loaded;
    private static RuntimeException loadFailure;
    private static List<Row> rows = List.of();
    private static Set<String> fixtureNames = Set.of();
    private static Map<String, Fixture> fixtures = Map.of();
    private static Map<String, String> properties = Map.of();
    private static Path tempRoot;
    private static boolean tempRootOwned;

    private static WireMockServer wireMock;
    private static Object greenMail;
    private static Object qpid;
    private static Object mongoServer;
    private static String mongoUri;
    private static Object ldapServer;
    private static ch.qos.logback.classic.Logger rootLogger;
    private static ListAppender<ILoggingEvent> appender;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void loadFixtures() {
        load();
        Assumptions.assumeTrue(!fixtures.isEmpty(), NO_FIXTURES);
    }

    @DynamicPropertySource
    static void fixtureProperties(DynamicPropertyRegistry registry) {
        load();
        properties.forEach((key, value) -> registry.add(key, () -> value));
    }

    @TestFactory
    Stream<DynamicTest> replay() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Row row : rows) {
            Fixture fixture = fixtures.get(row.scenario());
            if (fixture != null) {
                tests.add(DynamicTest.dynamicTest(row.scenario(), () -> run(row, fixture)));
            }
        }
        return tests.stream();
    }

    @AfterAll
    static synchronized void writeSummary() {
        try {
            writeSummaryFile();
        } finally {
            stopServers();
        }
    }

    /**
     * Decides the "no fixtures" outcome at class level, before any Spring context or embedded server starts.
     *
     * <p>Surefire 3.1.2 counts a class whose {@code @BeforeAll} aborts as neither run nor skipped, while a class
     * disabled here is reported as one skipped test carrying {@link #NO_FIXTURES}. {@code @BeforeAll} and
     * {@code @AfterAll} do not run for a disabled class, so the summary is written here. With fixtures present, or
     * when loading fails, the class runs and its lifecycle methods load, replay or report the failure.
     */
    static final class FixtureGate implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext extensionContext) {
            if (extensionContext.getTestMethod().isPresent()) {
                return ConditionEvaluationResult.enabled("decided for the class");
            }
            try {
                if (!discoverFixtures().isEmpty()) {
                    return ConditionEvaluationResult.enabled("Tier 2A fixtures present");
                }
                load();
            } catch (IOException | RuntimeException e) {
                return ConditionEvaluationResult.enabled("fixture loading failed; @BeforeAll reports the failure");
            }
            writeSummary();
            return ConditionEvaluationResult.disabled(NO_FIXTURES);
        }
    }

    /**
     * Prepares the replay's application context before it refreshes.
     *
     * <p>The post-processor it adds runs once the configuration classes are parsed and before any application
     * singleton exists. It removes Spring's {@code @Scheduled} processor ({@link #suppressScheduledTriggers}), so in
     * replay the only trigger executions are the single-run invocations the fixtures name in {@code request.invoke};
     * production code keeps its schedules. It then fails the context, so no client or store bean is ever created,
     * when the effective configuration reaches a target the replay does not own ({@link #enforceReplayBoundary}).
     * Last, it registers the two singletons that hand the replay's port reservations over to the application's
     * listeners, whichever acts first: the {@link PortRegistry.WebServerHandover}, which Spring Boot applies when the
     * context creates its embedded web server, before any of the server's listeners is probed or bound, and the
     * {@link PortRegistry.Handover}, which hands them over just before the web server start phase in a context without
     * an embedded web server. The initializer adds the {@link PortRegistry.Securing} that holds every port nothing
     * listens on exclusively again once the context has refreshed.
     */
    static final class ReplayContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            ConfigurableEnvironment environment = applicationContext.getEnvironment();
            applicationContext.addBeanFactoryPostProcessor(beanFactory -> {
                suppressScheduledTriggers(beanFactory);
                enforceReplayBoundary(environment);
                beanFactory.registerSingleton(PortRegistry.Handover.BEAN_NAME,
                        new PortRegistry.Handover(FixtureParityTest::portRegistry));
                beanFactory.registerSingleton(PortRegistry.WebServerHandover.BEAN_NAME,
                        new PortRegistry.WebServerHandover(FixtureParityTest::portRegistry));
            });
            applicationContext.addApplicationListener(
                    new PortRegistry.Securing(applicationContext, FixtureParityTest::portRegistry));
        }
    }


    /** One row of the {@code ## Scenarios} table; only the columns the replay reads are kept. */
    record Row(String scenario, String trigger, String state) {

        boolean deterministic() {
            return "deterministic".equals(state);
        }

        boolean unreachable() {
            return "unreachable".equals(state);
        }
    }

    /** A fixture file as found, before parsing. */
    record FixtureSource(String name, String location, byte[] content) {
    }

    /** A parsed fixture with the embedded servers its replay needs. */
    record Fixture(String name, String location, JsonNode json, Needs needs) {
    }

    /** The embedded servers a replay talks to besides WireMock, which every replay uses. */
    record Needs(boolean greenMail, boolean qpid, boolean mongo, boolean ldap) {

        static final Needs NONE = new Needs(false, false, false, false);

        Needs or(Needs other) {
            return new Needs(greenMail || other.greenMail, qpid || other.qpid, mongo || other.mongo,
                    ldap || other.ldap);
        }
    }

    /** A step that may throw any exception. */
    interface Action {
        void run() throws Exception;
    }

    private void run(Row row, Fixture fixture) throws Throwable {
        String name = row.scenario();
        try {
            if (!fixture.json().has("upstream")) {
                abort(name, UNVERIFIED_NO_UPSTREAM,
                        name + ": the fixture has no upstream recording, so it cannot be replayed");
            }
            Replay replay = new Replay(context, row, fixture);
            try {
                replay.execute();
            } catch (Throwable failure) {
                replay.resetAfter(failure);
                throw failure;
            }
            replay.reset();
            if (row.deterministic()) {
                STATUSES.put(name, VERIFIED);
            } else {
                STATUSES.put(name, UNVERIFIED_NONDETERMINISTIC);
                LOG.info("{}: UNVERIFIED \u2014 nondeterministic output (structure compared, values not)", name);
            }
        } catch (TestAbortedException aborted) {
            STATUSES.putIfAbsent(name, UNVERIFIED_MISSING_DRIVER);
            throw aborted;
        } catch (Throwable failure) {
            STATUSES.put(name, FAILED);
            throw failure;
        }
    }

    /** Records the scenario's status, then aborts it, so the test is reported skipped with {@code message}. */
    private static <T> T abort(String scenario, String status, String message) {
        STATUSES.put(scenario, status);
        return Assumptions.abort(message);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Loading: SCENARIOS.md, fixtures, placeholders and embedded servers, computed once per test class.
    // ---------------------------------------------------------------------------------------------------------

    static synchronized void load() {
        if (loadFailure != null) {
            throw loadFailure;
        }
        if (loaded) {
            return;
        }
        try {
            attachAppender();
            rows = parseScenarios();
            List<FixtureSource> sources = discoverFixtures();
            Set<String> names = new LinkedHashSet<>();
            sources.forEach(source -> names.add(source.name()));
            fixtureNames = names;
            fixtures = parseFixtures(sources);
            startServers();
            properties = resolveProperties();
            loaded = true;
        } catch (RuntimeException e) {
            loadFailure = e;
            throw e;
        } catch (Exception e) {
            loadFailure = new IllegalStateException("Fixture loading failed: " + e, e);
            throw loadFailure;
        }
    }

    private static List<Row> parseScenarios() throws IOException {
        Path override = fixturesDirectory();
        if (override != null && Files.isRegularFile(override.resolve("SCENARIOS.md"))) {
            Path file = override.resolve("SCENARIOS.md");
            return ScenarioTable.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        }
        try (InputStream in = Reflect.loader().getResourceAsStream("fixtures/SCENARIOS.md")) {
            if (in == null) {
                throw new IllegalStateException(
                        "fixtures/SCENARIOS.md is missing from the test classpath (src/test/resources/fixtures/)");
            }
            return ScenarioTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "classpath:fixtures/SCENARIOS.md");
        }
    }

    /** The fixture files, sorted by name: {@code <fixtures.dir>/*.json} when set, else the classpath's. */
    static List<FixtureSource> discoverFixtures() throws IOException {
        List<FixtureSource> sources = new ArrayList<>();
        Path override = fixturesDirectory();
        if (override != null) {
            if (!Files.isDirectory(override)) {
                throw new IllegalStateException("-Dfixtures.dir is not a directory: " + override);
            }
            List<Path> files;
            try (Stream<Path> listing = Files.list(override)) {
                files = listing.filter(file -> isFixtureName(file.getFileName().toString()))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
            }
            for (Path file : files) {
                sources.add(new FixtureSource(fixtureName(file.getFileName().toString()), file.toString(),
                        Files.readAllBytes(file)));
            }
        } else {
            Resource[] resources = new PathMatchingResourcePatternResolver(Reflect.loader())
                    .getResources("classpath*:fixtures/*.json");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null || !isFixtureName(fileName)) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    sources.add(new FixtureSource(fixtureName(fileName), resource.getDescription(),
                            in.readAllBytes()));
                }
            }
            sources.sort(Comparator.comparing(FixtureSource::name));
        }
        Map<String, String> seen = new HashMap<>();
        for (FixtureSource source : sources) {
            String previous = seen.putIfAbsent(source.name(), source.location());
            if (previous != null) {
                throw new IllegalStateException("Two fixtures are named " + source.name() + ".json: " + previous
                        + " and " + source.location());
            }
        }
        return sources;
    }

    private static boolean isFixtureName(String fileName) {
        return fileName.endsWith(".json") && fileName.length() > ".json".length();
    }

    private static String fixtureName(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    private static Path fixturesDirectory() {
        String value = System.getProperty("fixtures.dir");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Map<String, Fixture> parseFixtures(List<FixtureSource> sources) {
        Map<String, Row> rowsByName = new HashMap<>();
        rows.forEach(row -> rowsByName.put(row.scenario(), row));
        Map<String, Fixture> parsed = new LinkedHashMap<>();
        for (FixtureSource source : sources) {
            Row row = rowsByName.get(source.name());
            if (row == null) {
                throw new IllegalStateException("Fixture " + source.location() + " has no row '" + source.name()
                        + "' in the SCENARIOS.md scenario table");
            }
            if (row.unreachable()) {
                STATUSES.put(row.scenario(), FAILED);
                throw new IllegalStateException("Scenario " + row.scenario() + " is unreachable, so it must have no"
                        + " fixture, but " + source.location() + " exists");
            }
            JsonNode json;
            try {
                json = JSON.readTree(source.content());
            } catch (IOException e) {
                throw new IllegalStateException("Fixture " + source.location() + " is not valid JSON: "
                        + e.getMessage(), e);
            }
            if (json == null || !json.isObject()) {
                throw new IllegalStateException("Fixture " + source.location() + " must hold one JSON object");
            }
            for (String section : List.of("request", "response")) {
                if (!json.path(section).isObject()) {
                    throw new IllegalStateException("Fixture " + source.location() + " has no '" + section
                            + "' object");
                }
            }
            if (json.has("state") && !json.get("state").isObject()) {
                throw new IllegalStateException("Fixture " + source.location() + ": 'state' must be an object");
            }
            if (json.has("upstream") && !json.get("upstream").isArray()) {
                throw new IllegalStateException("Fixture " + source.location() + ": 'upstream' must be an array");
            }
            for (JsonNode key : elements(json.path("state").get("notContacted"),
                    "Fixture " + source.location() + ": state.notContacted")) {
                if (!key.isTextual() || key.textValue().isBlank()) {
                    throw new IllegalStateException("Fixture " + source.location()
                            + ": state.notContacted must hold property keys, each a non-blank string, but holds " + key);
                }
            }
            List<String> placeholders = placeholderNames(source.name(), json);
            parsed.put(source.name(), new Fixture(source.name(), source.location(), json,
                    needs(row, json, placeholders)));
        }
        return parsed;
    }

    /** Every placeholder name the fixture uses, each checked against the supported set. */
    private static List<String> placeholderNames(String fixture, JsonNode json) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : entries(json.path("state").path("properties"),
                fixture + ": state.properties")) {
            values.add(propertyText(fixture, property));
        }
        JsonNode file = json.path("request").path("file");
        if (file.isObject() && file.path("directory").isTextual()) {
            values.add(file.path("directory").textValue());
        }
        for (String section : List.of("state", "response")) {
            for (Map.Entry<String, JsonNode> directory : entries(json.path(section).path("directories"),
                    fixture + ": " + section + ".directories")) {
                values.add(directory.getKey());
            }
        }
        List<String> names = new ArrayList<>();
        for (String value : values) {
            Matcher matcher = PLACEHOLDER.matcher(value);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!isSupportedPlaceholder(name)) {
                    throw new IllegalStateException("Fixture " + fixture + " uses the unsupported placeholder ${"
                            + name + "}");
                }
                names.add(name);
            }
        }
        return names;
    }

    private static boolean isSupportedPlaceholder(String name) {
        return name.equals("wiremock.baseUrl") || name.equals("wiremock.port") || name.equals("mongo.uri")
                || name.equals("ldap.port") || GREENMAIL_PORT.matcher(name).matches()
                || (name.startsWith("dir:") && name.length() > "dir:".length())
                || (name.startsWith("freePort:") && name.length() > "freePort:".length());
    }

    private static Needs needs(Row row, JsonNode json, List<String> placeholders) {
        JsonNode state = json.path("state");
        JsonNode response = json.path("response");
        boolean mail = row.trigger().equals("mail") || state.has("mail") || response.has("mails")
                || response.has("mailboxes") || placeholders.stream().anyMatch(name -> name.startsWith("greenmail."));
        boolean amqp = row.trigger().equals("amqp") || state.has("amqp");
        boolean mongo = state.has("collections") || response.has("collections") || placeholders.contains("mongo.uri");
        boolean ldap = state.has("ldap") || placeholders.contains("ldap.port");
        return new Needs(mail, amqp, mongo, ldap);
    }

    /** A {@code state.properties} value as Spring receives it: scalars only. */
    private static String propertyText(String fixture, Map.Entry<String, JsonNode> property) {
        JsonNode value = property.getValue();
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new IllegalStateException("Fixture " + fixture + ": state.properties." + property.getKey()
                    + " must be a string, number or boolean");
        }
        return value.asText();
    }

    private static Map<String, String> resolveProperties() {
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> owners = new HashMap<>();
        for (Fixture fixture : fixtures.values()) {
            for (Map.Entry<String, JsonNode> property : entries(fixture.json().path("state").path("properties"),
                    fixture.name() + ": state.properties")) {
                String value = resolve(propertyText(fixture.name(), property), fixture.name());
                String previous = resolved.putIfAbsent(property.getKey(), value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalStateException("Property " + property.getKey() + " resolves to '" + previous
                            + "' in fixture " + owners.get(property.getKey()) + " but to '" + value
                            + "' in fixture " + fixture.name() + "; one application context serves every fixture");
                }
                owners.putIfAbsent(property.getKey(), fixture.name());
            }
        }
        return resolved;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Placeholders: ${wiremock.baseUrl}, ${wiremock.port}, ${dir:<name>}, ${freePort:<name>},
    // ${greenmail.<protocol>.port}, ${mongo.uri} and ${ldap.port}.
    // ---------------------------------------------------------------------------------------------------------

    static String resolve(String value, String fixture) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(placeholderValue(matcher.group(1), fixture)));
        }
        matcher.appendTail(result);
        if (value.startsWith("${dir:")) {
            // A value led by a replay directory names a path below it: checked, never created, as it may be a file.
            requireInsideRoot(result.toString(), "Fixture " + fixture + ": " + value);
        }
        return result.toString();
    }

    private static String placeholderValue(String name, String fixture) {
        if (name.equals("wiremock.baseUrl") || name.equals("wiremock.port")) {
            if (wireMock == null) {
                throw new IllegalStateException("Fixture " + fixture + " uses ${" + name
                        + "} but WireMock is not running");
            }
            return name.equals("wiremock.port") ? String.valueOf(wireMock.port()) : "http://localhost:" + wireMock.port();
        }
        if (name.startsWith("dir:") && name.length() > "dir:".length()) {
            String directory = name.substring("dir:".length());
            return replayDirectory(Path.of(directory), "Fixture " + fixture + ": ${dir:" + directory + "}", true)
                    .toString();
        }
        if (name.startsWith("freePort:") && name.length() > "freePort:".length()) {
            return String.valueOf(freePort(name.substring("freePort:".length())));
        }
        Matcher greenMailPort = GREENMAIL_PORT.matcher(name);
        if (greenMailPort.matches()) {
            return String.valueOf(freePort("greenmail." + greenMailPort.group(1)));
        }
        if (name.equals("mongo.uri")) {
            // Without mongo-java-server no server listens here; the fixture then aborts as missing-driver.
            return mongoUri != null ? mongoUri : "mongodb://127.0.0.1:" + freePort("mongo");
        }
        if (name.equals("ldap.port")) {
            return String.valueOf(freePort("ldap"));
        }
        throw new IllegalStateException("Fixture " + fixture + " uses the unsupported placeholder ${" + name + "}");
    }

    /**
     * A directory key or {@code request.file.directory}: a value without {@code ${} means {@code ${dir:<value>}}.
     * The directory it resolves to must lie inside the temporary root; it is created and emptied per scenario.
     */
    static Path resolveDirectory(String spec, String fixture) {
        String path = spec.contains("${") ? resolve(spec, fixture) : spec;
        return replayDirectory(Path.of(path), "Fixture " + fixture + ": directory " + spec, true);
    }

    /** {@code <temporary root>/<name>}, created; {@code register} adds it to the directories emptied per scenario. */
    static Path directory(String name, boolean register) {
        return replayDirectory(Path.of(name), "${dir:" + name + "}", register);
    }

    /**
     * {@code candidate} confined to the temporary root and created; {@code register} adds it to the directories
     * emptied per scenario. It is confined again once created, so no symbolic link on its path leaves the root.
     */
    private static synchronized Path replayDirectory(Path candidate, String what, boolean register) {
        try {
            Path root = tempRoot();
            Path directory = confine(root, candidate, what);
            Files.createDirectories(directory);
            confine(root, directory, what);
            if (register) {
                DIRECTORIES.add(directory);
            }
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(what + ": cannot create the replay directory " + candidate, e);
        }
    }

    /** Fails unless {@code path} lies inside the temporary root, as {@link #confine} decides; nothing is created. */
    private static synchronized void requireInsideRoot(String path, String what) {
        try {
            confine(tempRoot(), Path.of(path), what);
        } catch (IOException e) {
            throw new UncheckedIOException(what + ": cannot check the replay path " + path, e);
        }
    }

    /**
     * {@code candidate}, read against {@code root} when relative and normalized, provided it lies strictly inside
     * {@code root}: lexically, and through the real path of its deepest existing part, so neither {@code ..}, an
     * absolute path elsewhere nor a symbolic link leads out of the root, and a directory created there afterwards
     * stays inside it. Nothing is created. {@code what} names the spec and its fixture in the failure.
     */
    static Path confine(Path root, Path candidate, String what) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(candidate).normalize();
        if (!path.startsWith(base) || path.equals(base)) {
            throw new IllegalStateException(what + " resolves to " + path
                    + ", which is not a directory inside the replay root " + base);
        }
        Path realBase = base.toRealPath();
        Path existing = path;
        while (!existing.equals(base) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (NoSuchFileException broken) {
            throw new IllegalStateException(what + " resolves to " + path + " through the broken symbolic link "
                    + existing + ", so it is not a directory known to lie inside the replay root " + base, broken);
        }
        if (!real.startsWith(realBase) || (existing.equals(path) && real.equals(realBase))) {
            throw new IllegalStateException(what + " resolves to " + path + ", but " + existing + " leads to " + real
                    + ", which is not a directory inside the replay root " + realBase);
        }
        return path;
    }

    private static synchronized Path tempRoot() throws IOException {
        if (tempRoot == null) {
            if (tempDir != null) {
                tempRoot = tempDir.toAbsolutePath().normalize();
            } else {
                tempRoot = Files.createTempDirectory("fixture-parity-").toAbsolutePath().normalize();
                tempRootOwned = true;
            }
        }
        return tempRoot;
    }

    /**
     * The port of {@code name}: the same one for every use of the name and distinct from every other name's. It is
     * the port an embedded server bound and published under the name, or else one {@link PortRegistry} reserves for
     * the replay until the class ends: held exclusively until the application context creates its embedded web
     * server, or, without one, until just before the web server start phase, so neither another process nor another
     * name takes it and a connection to it is refused; shared with the application's listeners from then until the
     * context has refreshed, so they probe and bind it; then left to the listener that bound it or else held
     * exclusively again.
     */
    static synchronized int freePort(String name) {
        return portRegistry().port(name);
    }

    /** Records the port an embedded server bound under {@code name}, before any placeholder reads the name. */
    private static synchronized void publishPort(String name, int port) {
        portRegistry().publish(name, port);
    }

    private static synchronized PortRegistry portRegistry() {
        if (portRegistry == null) {
            portRegistry = PortRegistry.forThisHost();
        }
        return portRegistry;
    }

    /**
     * The replay's ports by name: each name keeps one port, and no two names share one.
     *
     * <p>A port the registry allocates is reserved by an unconnected socket bound to the wildcard address, and what
     * the registry holds for it follows the application context's start ({@link Phase}). On Linux:
     * <ul>
     *   <li>from its allocation until the handover ({@link WebServerHandover} or {@link Handover}), an exclusive
     *       reservation, bound without {@code SO_REUSEADDR}: no other socket binds the port, with
     *       {@code SO_REUSEADDR} or without it, no ephemeral bind receives it and a connection to it is refused, so
     *       neither another process nor another name takes it;</li>
     *   <li>from the handover until the context has refreshed, a shared reservation on the same port, bound with
     *       {@code SO_REUSEADDR}, so a listener that sets it, as Undertow, GreenMail, Qpid and the JDK's server
     *       sockets do, binds beside it, and so does a probe that binds a listener's port to check that it is free.
     *       The handover runs when the context creates its embedded web server, before any of the server's listeners
     *       is probed or bound, or, in a context without one, just before the web server start phase. Only in this
     *       window can another socket bind the port as well: one that sets {@code SO_REUSEADDR} throughout, and any
     *       socket in the instant each swap from one reservation to the next takes;</li>
     *   <li>from the {@link Securing}, once the context has refreshed: an exclusive reservation again where nothing
     *       listens, so the port stays a refused connection until {@link #releaseAll} and a listener that binds only
     *       later cannot bind it, and nothing where a listener, the application's, bound it
     *       ({@link Hold#LISTENER_OWNED}), since a listening socket keeps every other socket off its port. A name
     *       allocated from then on is held exclusively at once.</li>
     * </ul>
     * On a host that does not let such a listener bind beside a shared reservation
     * ({@link #listenerRefusalBesideReservation}), as BSD and macOS sockets do not, the handover releases each
     * reservation instead, with one warning, so another process can take a port until the application's listener
     * has bound it; securing then holds each port nothing listens on exclusively again, and the ports stay distinct
     * across names. An embedded server binds port 0 itself and {@link #publish publishes} the port it bound, so it
     * never waits on a port chosen for it and the registry holds nothing for it.
     *
     * <p>Every method is synchronized, and each transition acts once: a second handover or securing changes nothing.
     */
    static final class PortRegistry {

        /** Reservations one allocation opens before it gives up finding a port no other name holds. */
        static final int MAX_ATTEMPTS = 32;

        /** A port held for the replay until {@code handle} is closed. */
        record Reservation(int port, Closeable handle) {

            Reservation {
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("A reservation holds a port from 1 to 65535, not " + port);
                }
                Objects.requireNonNull(handle, "handle");
            }
        }

        /** Opens reservations: unconnected sockets bound to the wildcard address. */
        @FunctionalInterface
        interface ReservationSource {

            /**
             * Opens a reservation on {@code port}, or on a port the host picks when {@code port} is 0: shared, bound
             * with {@code SO_REUSEADDR}, or exclusive, bound without it.
             *
             * @throws BindException when another socket on the port keeps this one from binding it
             * @throws IOException   when the reservation cannot be opened for another reason
             */
            Reservation open(int port, boolean shared) throws IOException;
        }

        /** Where the registry is in the application context's start; it only moves forward, until releaseAll. */
        enum Phase {
            /** Before the handover: every allocated port is held exclusively. */
            RESERVING,
            /** From the handover until securing: allocated ports are shared, or released, for the listeners. */
            HANDED_OVER,
            /** After securing: unlistened ports are held exclusively, listened ones are left to their listeners. */
            SECURED
        }

        /** What the registry holds for a name's port. */
        enum Hold {
            /** Nothing: an embedded server bound the port itself and published it. */
            PUBLISHED,
            /** An exclusive reservation, bound without {@code SO_REUSEADDR}. */
            EXCLUSIVE,
            /** A shared reservation, bound with {@code SO_REUSEADDR}, beside which a listener binds. */
            SHARED,
            /**
             * Nothing: the handover released the reservation, on a host where no listener binds beside a shared one,
             * or another socket bound the port while the reservation was being shared; securing then binds it again.
             */
            RELEASED,
            /** Nothing: securing found another socket, the application's listener, holding the port. */
            LISTENER_OWNED
        }

        private final ReservationSource source;
        private final boolean shareAtHandover;
        private final Map<String, Integer> portsByName = new LinkedHashMap<>();
        private final Map<Integer, String> namesByPort = new HashMap<>();
        private final Map<String, Hold> holds = new LinkedHashMap<>();
        private final Map<String, Reservation> reservations = new LinkedHashMap<>();
        private Phase phase = Phase.RESERVING;

        /**
         * @param source          opens each reservation
         * @param shareAtHandover whether the handover swaps each exclusive reservation for a shared one on the same
         *                        port, or releases it, as on a host where no listener binds beside a shared one
         */
        PortRegistry(ReservationSource source, boolean shareAtHandover) {
            this.source = Objects.requireNonNull(source, "source");
            this.shareAtHandover = shareAtHandover;
        }

        /** Socket reservations, shared at the handover where this host lets a listener bind beside one, or released. */
        static PortRegistry forThisHost() {
            IOException refusal = listenerRefusalBesideReservation();
            if (refusal != null) {
                LOG.warn("Fixture replay: a listener cannot bind beside a shared port reservation on this host ({}),"
                        + " so each ${freePort:<name>} reservation is released at the handover, when the application"
                        + " context creates its embedded web server or, without one, just before the web server start"
                        + " phase, and another process can take the port until the application has bound it",
                        refusal.toString());
            }
            return new PortRegistry(PortRegistry::openSocket, refusal == null);
        }

        /**
         * Why a server socket with {@code SO_REUSEADDR} cannot bind beside a shared reservation that replaced an
         * exclusive one on the same port, as the handover replaces it, on this host, or {@code null} when it binds,
         * as on Linux. BSD and macOS sockets refuse a second wildcard bind of a port without {@code SO_REUSEPORT}.
         */
        static IOException listenerRefusalBesideReservation() {
            Reservation exclusive;
            try {
                exclusive = openSocket(0, false);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot reserve a port to probe this host's sockets", e);
            }
            int port = exclusive.port();
            release(exclusive);
            Reservation shared;
            try {
                shared = openSocket(port, true);
            } catch (IOException refused) {
                return refused;
            }
            try (ServerSocket listener = new ServerSocket()) {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(port));
                return null;
            } catch (IOException refused) {
                return refused;
            } finally {
                release(shared);
            }
        }

        /**
         * An unconnected socket bound to the wildcard address on {@code port}, or on a port the host picks when it is
         * 0, with {@code SO_REUSEADDR} when {@code shared} and without it otherwise.
         */
        static Reservation openSocket(int port, boolean shared) throws IOException {
            Socket socket = new Socket();
            try {
                socket.setReuseAddress(shared);
                socket.bind(new InetSocketAddress(port));
                return new Reservation(socket.getLocalPort(), socket);
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException closing) {
                    e.addSuppressed(closing);
                }
                throw e;
            }
        }

        /**
         * The port of {@code name}: the one it holds, or else the port of a new exclusive reservation that no other
         * name holds. A reservation on a port another name holds stays open while the source is asked again, so the
         * source cannot return it a second time, and is released once the allocation ends. A name allocated between
         * the handover and securing is handed over at once, as the handover would have handed it over.
         *
         * @throws IllegalStateException when {@link #MAX_ATTEMPTS} reservations in a row land on ports other names
         *                               hold
         * @throws UncheckedIOException  when no reservation can be opened, or the one allocated cannot be handed over
         */
        synchronized int port(String name) {
            Integer known = portsByName.get(name);
            if (known != null) {
                return known;
            }
            List<Reservation> duplicates = new ArrayList<>();
            try {
                for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                    Reservation reservation;
                    try {
                        reservation = source.open(0, false);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot reserve a port for " + name, e);
                    }
                    if (namesByPort.containsKey(reservation.port())) {
                        duplicates.add(reservation);
                        continue;
                    }
                    portsByName.put(name, reservation.port());
                    namesByPort.put(reservation.port(), name);
                    holds.put(name, Hold.EXCLUSIVE);
                    reservations.put(name, reservation);
                    if (phase == Phase.HANDED_OVER) {
                        UncheckedIOException failure = handOver(name);
                        if (failure != null) {
                            throw failure;
                        }
                    }
                    return reservation.port();
                }
            } finally {
                for (Reservation duplicate : duplicates) {
                    try {
                        release(duplicate);
                    } catch (UncheckedIOException e) {
                        LOG.warn("Fixture replay: cannot release the duplicate reservation of port {}: {}",
                                duplicate.port(), e.getCause().toString());
                    }
                }
            }
            throw new IllegalStateException("Cannot reserve a port for " + name + ": " + MAX_ATTEMPTS
                    + " reservations in a row landed on ports other names hold " + new TreeMap<>(namesByPort));
        }

        /**
         * Records that {@code name} is on {@code port}, a port an embedded server bound itself, so the registry holds
         * nothing for it; publishing a name on the port it already holds changes nothing.
         *
         * @throws IllegalArgumentException when {@code port} is outside 1 to 65535
         * @throws IllegalStateException    when {@code name} holds another port, or another name holds {@code port}
         */
        synchronized void publish(String name, int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Cannot publish " + name + " on port " + port
                        + ", which is not a port from 1 to 65535");
            }
            Integer known = portsByName.get(name);
            if (known != null && known != port) {
                throw new IllegalStateException("Cannot publish " + name + " on port " + port + ": it holds port "
                        + known);
            }
            String owner = namesByPort.get(port);
            if (owner != null && !owner.equals(name)) {
                throw new IllegalStateException("Cannot publish " + name + " on port " + port + ": " + owner
                        + " holds it");
            }
            portsByName.put(name, port);
            namesByPort.put(port, name);
            holds.putIfAbsent(name, Hold.PUBLISHED);
        }

        /** Every port a name holds, allocated or published, whatever the registry holds for it now. */
        synchronized Set<Integer> ports() {
            return new TreeSet<>(namesByPort.keySet());
        }

        /** Where the registry is in the application context's start. */
        synchronized Phase phase() {
            return phase;
        }

        /** What the registry holds for each name's port, by name. */
        synchronized Map<String, Hold> holds() {
            return new TreeMap<>(holds);
        }

        /**
         * Hands every exclusive reservation over to the application's listeners, once: it is released and, where
         * this host lets a listener bind beside a shared reservation, a shared one is bound on the same port at once;
         * elsewhere the port is left unheld. A port another socket binds in between is left unheld, with a warning.
         * Published ports are untouched, and from now on a new name is handed over as it is allocated.
         *
         * @throws UncheckedIOException when a reservation cannot be released or shared; every other name is handed
         *                              over first
         */
        synchronized void handOver() {
            if (phase != Phase.RESERVING) {
                return;
            }
            phase = Phase.HANDED_OVER;
            UncheckedIOException failure = null;
            for (String name : List.copyOf(reservations.keySet())) {
                failure = combine(failure, handOver(name));
            }
            if (failure != null) {
                throw failure;
            }
        }

        /**
         * Secures every port the handover shared or released, once, after the application's listeners have bound:
         * the shared reservation is released and an exclusive one bound on the same port at once. Where that bind
         * succeeds nothing listens there, and the port is held exclusively until {@link #releaseAll}; where another
         * socket keeps it off, the application's listener holds the port and the registry holds nothing for it
         * ({@link Hold#LISTENER_OWNED}). Published ports, and reservations no handover reached, are untouched, and
         * from now on a new name is held exclusively as it is allocated.
         *
         * @throws UncheckedIOException when a reservation cannot be released or bound for another reason; every other
         *                              name is secured first
         */
        synchronized void secure() {
            if (phase == Phase.SECURED) {
                return;
            }
            phase = Phase.SECURED;
            UncheckedIOException failure = null;
            for (String name : List.copyOf(holds.keySet())) {
                Hold hold = holds.get(name);
                if (hold == Hold.SHARED || hold == Hold.RELEASED) {
                    failure = combine(failure, secure(name));
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /**
         * Releases every held reservation, forgets every name and returns to {@link Phase#RESERVING}, so a later
         * request allocates afresh, as in a new registry.
         *
         * @throws UncheckedIOException when a reservation cannot be released; every other one is released first
         */
        synchronized void releaseAll() {
            UncheckedIOException failure = null;
            for (Reservation reservation : reservations.values()) {
                try {
                    release(reservation);
                } catch (UncheckedIOException e) {
                    failure = combine(failure, e);
                }
            }
            reservations.clear();
            holds.clear();
            portsByName.clear();
            namesByPort.clear();
            phase = Phase.RESERVING;
            if (failure != null) {
                throw failure;
            }
        }

        /** Hands {@code name}'s exclusive reservation over; the failure to release or share it, or {@code null}. */
        private UncheckedIOException handOver(String name) {
            int port = portsByName.get(name);
            UncheckedIOException failure = releaseHeld(name);
            holds.put(name, Hold.RELEASED);
            if (!shareAtHandover) {
                LOG.debug("Fixture replay: port {} of {} released for the application's listeners", port, name);
                return failure;
            }
            try {
                reservations.put(name, reopen(port, true));
                holds.put(name, Hold.SHARED);
                LOG.debug("Fixture replay: port {} of {} shared with the application's listeners", port, name);
            } catch (BindException taken) {
                LOG.warn("Fixture replay: another socket bound port {} of {} while its reservation was handed over,"
                        + " so the application may not bind it: {}", port, name, taken.toString());
            } catch (IOException e) {
                failure = combine(failure, new UncheckedIOException(
                        "Cannot share port " + port + " of " + name + " with the application's listeners", e));
            }
            return failure;
        }

        /** Secures {@code name}'s handed-over port; the failure to release or bind it, or {@code null}. */
        private UncheckedIOException secure(String name) {
            int port = portsByName.get(name);
            UncheckedIOException failure = releaseHeld(name);
            holds.put(name, Hold.RELEASED);
            try {
                reservations.put(name, reopen(port, false));
                holds.put(name, Hold.EXCLUSIVE);
                LOG.debug("Fixture replay: port {} of {} held exclusively again; nothing listens there", port, name);
            } catch (BindException listening) {
                holds.put(name, Hold.LISTENER_OWNED);
                LOG.debug("Fixture replay: port {} of {} left to the listener that holds it ({})", port, name,
                        listening.toString());
            } catch (IOException e) {
                failure = combine(failure, new UncheckedIOException(
                        "Cannot hold port " + port + " of " + name + " exclusively again", e));
            }
            return failure;
        }

        /** A reservation the source opened on exactly {@code port}. */
        private Reservation reopen(int port, boolean shared) throws IOException {
            Reservation reservation = source.open(port, shared);
            if (reservation.port() != port) {
                release(reservation);
                throw new IllegalStateException("Asked to reopen port " + port + ", the reservation source opened"
                        + " port " + reservation.port());
            }
            return reservation;
        }

        /** Releases the reservation {@code name} holds, if any; the failure to release it, or {@code null}. */
        private UncheckedIOException releaseHeld(String name) {
            Reservation reservation = reservations.remove(name);
            if (reservation == null) {
                return null;
            }
            try {
                release(reservation);
                return null;
            } catch (UncheckedIOException e) {
                return e;
            }
        }

        private static UncheckedIOException combine(UncheckedIOException first, UncheckedIOException next) {
            if (first == null) {
                return next;
            }
            if (next != null) {
                first.addSuppressed(next);
            }
            return first;
        }

        private static void release(Reservation reservation) {
            try {
                reservation.handle().close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot release the reservation of port " + reservation.port(), e);
            }
        }

        /**
         * The Spring lifecycle that {@link #handOver hands the registry's reservations over} to the application's
         * listeners just before the web server start phase, in a context without an embedded web server. Where the
         * context has one, the {@link WebServerHandover} handed them over when the server was created, and this start
         * changes nothing. The replay registers it as a singleton before any other singleton exists, so it starts
         * within the context's refresh, in the phase order Spring starts every lifecycle in, and a later start, as
         * after a stop, changes nothing.
         */
        static final class Handover implements SmartLifecycle {

            /** The name of the singleton. */
            static final String BEAN_NAME = "fixtureReplayPortHandover";

            /**
             * One phase before Spring Boot 3.2's web server start-stop lifecycle, {@code DEFAULT_PHASE - 2048}, whose
             * start binds every listener of the embedded server, the main one and each additional one a project adds;
             * Spring starts lifecycles in ascending phase order.
             */
            static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 2049;

            private final Supplier<PortRegistry> registry;
            private volatile boolean running;

            Handover(Supplier<PortRegistry> registry) {
                this.registry = Objects.requireNonNull(registry, "registry");
            }

            @Override
            public void start() {
                registry.get().handOver();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return PHASE;
            }
        }

        /**
         * The embedded web server factory customizer that {@link #handOver hands the registry's reservations over} to
         * the application's listeners when the context creates its embedded web server. Spring Boot applies it while
         * it initializes the web server factory bean, which it does no later than when it asks the factory for the
         * server, so the handover precedes the server's creation: before any builder customizer, such as one that
         * probes the port of each additional listener, runs and before any listener binds. Ordered first, it also
         * precedes every other factory customizer. It accepts every {@link WebServerFactory}, servlet or reactive and
         * of any server, and a context without an embedded web server never applies it, so the {@link Handover} hands
         * over there; whichever acts second changes nothing.
         */
        static final class WebServerHandover implements WebServerFactoryCustomizer<WebServerFactory>, Ordered {

            /** The name of the singleton. */
            static final String BEAN_NAME = "fixtureReplayWebServerPortHandover";

            private final Supplier<PortRegistry> registry;

            WebServerHandover(Supplier<PortRegistry> registry) {
                this.registry = Objects.requireNonNull(registry, "registry");
            }

            @Override
            public void customize(WebServerFactory factory) {
                registry.get().handOver();
            }

            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }
        }

        /**
         * {@link #secure Secures the registry's ports} once the context it was added to has refreshed, after every
         * lifecycle, the web server's included, has started. The refresh of any other context, such as a child
         * context whose events reach this one, changes nothing.
         */
        static final class Securing implements ApplicationListener<ContextRefreshedEvent> {

            private final ApplicationContext context;
            private final Supplier<PortRegistry> registry;

            Securing(ApplicationContext context, Supplier<PortRegistry> registry) {
                this.context = Objects.requireNonNull(context, "context");
                this.registry = Objects.requireNonNull(registry, "registry");
            }

            @Override
            public void onApplicationEvent(ContextRefreshedEvent event) {
                if (event.getApplicationContext() == context) {
                    registry.get().secure();
                }
            }
        }
    }


    // ---------------------------------------------------------------------------------------------------------
    // Embedded servers, started in load() only when a fixture needs them and stopped after the class.
    // ---------------------------------------------------------------------------------------------------------

    private static void startServers() throws Exception {
        Needs union = Needs.NONE;
        for (Fixture fixture : fixtures.values()) {
            union = union.or(fixture.needs());
        }
        if (!fixtures.isEmpty()) {
            // Every replay checks its outbound HTTP calls here, an "upstream": [] scenario included, so a call the
            // recording lacks fails as unmatched instead of going unseen.
            // Gzip stays off, as in the capture's --disable-gzip, so recorded bodies and Content-Encoding pass as-is.
            WireMockServer server =
                    new WireMockServer(WireMockConfiguration.options().dynamicPort().gzipDisabled(true));
            server.start();
            wireMock = server;
        }
        if (union.greenMail()) {
            startGreenMail();
        }
        if (union.qpid()) {
            startQpid();
        }
        if (union.mongo()) {
            startMongo();
        }
        if (union.ldap()) {
            startLdap();
        }
    }

    private static void startGreenMail() throws Exception {
        Class<?> greenMailClass = Reflect.optionalClass(GREENMAIL_CLASS);
        Class<?> setupClass = Reflect.optionalClass(SERVER_SETUP_CLASS);
        if (greenMailClass == null || setupClass == null) {
            MISSING_SERVER_CLASSES.put("greenmail", GREENMAIL_CLASS);
            return;
        }
        // Every protocol binds port 0 and publishes the port it bound as greenmail.<protocol>, so
        // ${greenmail.<protocol>.port} is known before the application context starts.
        Object setups = Array.newInstance(setupClass, MAIL_PROTOCOLS.size());
        for (int i = 0; i < MAIL_PROTOCOLS.size(); i++) {
            Array.set(setups, i, Reflect.construct(setupClass, 0, "127.0.0.1", MAIL_PROTOCOLS.get(i)));
        }
        Object server = Reflect.construct(greenMailClass, setups);
        Reflect.call(server, "start");
        greenMail = server;
        for (String protocol : MAIL_PROTOCOLS) {
            // getSmtp, getSmtps, getImap, getImaps, getPop3 and getPop3s; start() returns once each one listens.
            Object protocolServer = Reflect.call(server,
                    "get" + Character.toUpperCase(protocol.charAt(0)) + protocol.substring(1));
            if (protocolServer == null) {
                throw new IllegalStateException("GreenMail started without its " + protocol + " server");
            }
            publishPort("greenmail." + protocol, ((Number) Reflect.call(protocolServer, "getPort")).intValue());
        }
        for (Fixture fixture : fixtures.values()) {
            registerMailUsers(fixture);
        }
    }

    private static void registerMailUsers(Fixture fixture) throws Exception {
        if (greenMail == null) {
            return;
        }
        String what = fixture.name() + ": state.mail.users";
        for (JsonNode user : elements(fixture.json().path("state").path("mail").path("users"), what)) {
            String email = text(user, "email", what + "[].email");
            String login = user.hasNonNull("login") ? text(user, "login", what + "[].login") : email;
            String password = user.hasNonNull("password") ? text(user, "password", what + "[].password") : "";
            Reflect.call(greenMail, "setUser", email, login, password);
        }
    }

    private static void startQpid() throws Exception {
        Class<?> launcherClass = Reflect.optionalClass(QPID_CLASS);
        Class<?> listenerClass = Reflect.optionalClass(QPID_LISTENER_CLASS);
        Class<?> amqpPortClass = Reflect.optionalClass(QPID_AMQP_PORT_CLASS);
        if (launcherClass == null || listenerClass == null || amqpPortClass == null) {
            MISSING_SERVER_CLASSES.put("qpid", QPID_CLASS);
            return;
        }
        Set<String> configurations = new TreeSet<>();
        for (Fixture fixture : fixtures.values()) {
            if (fixture.needs().qpid()) {
                JsonNode resource = fixture.json().path("state").path("amqp").path("initialConfigurationResource");
                if (!resource.isTextual() || resource.textValue().isBlank()) {
                    throw new IllegalStateException("Fixture " + fixture.name()
                            + " replays AMQP but names no state.amqp.initialConfigurationResource");
                }
                configurations.add(resource.textValue());
            }
        }
        if (configurations.size() != 1) {
            throw new IllegalStateException("One embedded Qpid broker serves every fixture, but the fixtures name"
                    + " the initial configurations " + configurations);
        }
        String resourceName = configurations.iterator().next();
        URL configuration = Reflect.loader().getResource(stripLeadingSlash(resourceName));
        if (configuration == null) {
            throw new IllegalStateException("Qpid initial configuration " + resourceName
                    + " is not on the test classpath");
        }
        Map<String, Object> brokerContext = new HashMap<>();
        // Port 0: the broker binds a port itself, which is published as amqp once it has started.
        brokerContext.put("qpid.amqp_port", "0");
        // Not registered for per-scenario emptying: the running broker keeps its store and logs there.
        brokerContext.put("qpid.work_dir", directory("qpid-work", false).toString());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", configuration.toExternalForm());
        attributes.put("startupLoggedToSystemOut", false);
        attributes.put("context", brokerContext);
        // The launcher hands its system configuration, through which the bound port is read, only to a listener.
        AtomicReference<Object> systemConfig = new AtomicReference<>();
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] {listenerClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "fixture replay's Qpid launcher listener";
                        };
                    }
                    if (method.getName().equals("onContainerResolve") && args != null && args.length == 1) {
                        systemConfig.set(args[0]);
                    }
                    return null;
                });
        Object launcher = Reflect.construct(launcherClass, listener);
        Reflect.call(launcher, "startup", attributes);
        qpid = launcher;
        publishPort("amqp", boundAmqpPort(systemConfig.get(), amqpPortClass, resourceName));
    }

    /**
     * The port the broker's AMQP port bound for {@code ${qpid.amqp_port}}, which the replay sets to 0: the initial
     * configuration must declare exactly one AMQP port configured that way.
     */
    private static int boundAmqpPort(Object systemConfig, Class<?> amqpPortClass, String resourceName)
            throws Exception {
        if (systemConfig == null) {
            throw new IllegalStateException("Qpid Broker-J started from " + resourceName
                    + " without resolving its broker, so the port it bound is unknown");
        }
        Object broker = Reflect.call(systemConfig, "getContainer");
        List<Integer> bound = new ArrayList<>();
        for (Object port : (Collection<?>) Reflect.call(broker, "getPorts")) {
            if (amqpPortClass.isInstance(port) && ((Number) Reflect.call(port, "getPort")).intValue() == 0) {
                bound.add(((Number) Reflect.call(port, "getBoundPort")).intValue());
            }
        }
        if (bound.size() != 1 || bound.get(0) < 1) {
            throw new IllegalStateException("Qpid initial configuration " + resourceName + " must declare exactly"
                    + " one AMQP port on ${qpid.amqp_port}, which the replay sets to 0, and the broker must have bound"
                    + " it; the bound ports of such AMQP ports are " + bound);
        }
        return bound.get(0);
    }

    private static void startMongo() throws Exception {
        Class<?> serverClass = Reflect.optionalClass(MONGO_SERVER_CLASS);
        Class<?> backendClass = Reflect.optionalClass(MONGO_BACKEND_CLASS);
        if (serverClass == null || backendClass == null) {
            MISSING_SERVER_CLASSES.put("mongo", MONGO_SERVER_CLASS);
            return;
        }
        Object server = Reflect.construct(serverClass, Reflect.construct(backendClass));
        InetSocketAddress address = (InetSocketAddress) Reflect.call(server, "bind");
        mongoServer = server;
        mongoUri = "mongodb://" + address.getHostString() + ":" + address.getPort();
        publishPort("mongo", address.getPort());
    }

    private static void startLdap() throws Exception {
        Class<?> serverClass = Reflect.optionalClass(LDAP_SERVER_CLASS);
        Class<?> configClass = Reflect.optionalClass(LDAP_CONFIG_CLASS);
        Class<?> listenerClass = Reflect.optionalClass(LDAP_LISTENER_CLASS);
        if (serverClass == null || configClass == null || listenerClass == null) {
            MISSING_SERVER_CLASSES.put("ldap", LDAP_SERVER_CLASS);
            return;
        }
        Set<String> baseDns = new LinkedHashSet<>();
        Map<String, String> credentials = new LinkedHashMap<>();
        for (Fixture fixture : fixtures.values()) {
            JsonNode ldap = fixture.json().path("state").path("ldap");
            String what = fixture.name() + ": state.ldap";
            if (ldap.hasNonNull("baseDn")) {
                baseDns.add(text(ldap, "baseDn", what + ".baseDn"));
            }
            if (ldap.hasNonNull("bindDn")) {
                String bindDn = text(ldap, "bindDn", what + ".bindDn");
                String password = ldap.hasNonNull("bindPassword") ? text(ldap, "bindPassword", what + ".bindPassword")
                        : "";
                String previous = credentials.putIfAbsent(bindDn, password);
                if (previous != null && !previous.equals(password)) {
                    throw new IllegalStateException("Fixtures give the LDAP bind DN " + bindDn
                            + " different passwords; one directory server serves every fixture");
                }
            }
        }
        if (baseDns.isEmpty()) {
            throw new IllegalStateException("LDAP replay needs state.ldap.baseDn in at least one fixture");
        }
        Object config = Reflect.construct(configClass, (Object) baseDns.toArray(new String[0]));
        for (Map.Entry<String, String> credential : credentials.entrySet()) {
            Reflect.call(config, "addAdditionalBindCredentials", credential.getKey(), credential.getValue());
        }
        // Port 0: the listener binds a port itself, which is published as ldap for ${ldap.port}.
        Object listener = Reflect.callStatic(listenerClass, "createLDAPConfig", "default", 0);
        Reflect.call(config, "setListenerConfigs", List.of(listener));
        Object server = Reflect.construct(serverClass, config);
        Reflect.call(server, "startListening");
        ldapServer = server;
        publishPort("ldap", ((Number) Reflect.call(server, "getListenPort")).intValue());
    }

    private static void attachAppender() {
        if (appender == null
                && LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            ListAppender<ILoggingEvent> list = new ListAppender<>();
            list.setName("fixture-parity");
            list.setContext(root.getLoggerContext());
            list.start();
            root.addAppender(list);
            rootLogger = root;
            appender = list;
        }
    }

    /** Re-attaches the appender: Spring Boot resets the Logback context, detaching it, when the context starts. */
    static synchronized void ensureAppender() {
        if (appender != null) {
            if (!appender.isStarted()) {
                appender.start();
            }
            if (!rootLogger.isAttached(appender)) {
                rootLogger.addAppender(appender);
            }
        }
    }

    static synchronized void clearAppender() {
        if (appender != null) {
            // AppenderBase.doAppend holds the appender's monitor while it adds to the list.
            synchronized (appender) {
                appender.list.clear();
            }
        }
    }

    static synchronized List<ILoggingEvent> loggedEvents() {
        if (appender == null) {
            return List.of();
        }
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private static void stopServers() {
        if (wireMock != null) {
            WireMockServer server = wireMock;
            stopQuietly("WireMock", server::stop);
            wireMock = null;
        }
        if (greenMail != null) {
            Object server = greenMail;
            stopQuietly("GreenMail", () -> Reflect.call(server, "stop"));
            greenMail = null;
        }
        if (qpid != null) {
            Object launcher = qpid;
            stopQuietly("Qpid Broker-J", () -> Reflect.call(launcher, "shutdown"));
            qpid = null;
        }
        if (mongoServer != null) {
            Object server = mongoServer;
            stopQuietly("mongo-java-server", () -> Reflect.call(server, "shutdownNow"));
            mongoServer = null;
        }
        if (ldapServer != null) {
            Object server = ldapServer;
            stopQuietly("UnboundID directory server", () -> Reflect.call(server, "shutDown", true));
            ldapServer = null;
        }
        if (portRegistry != null) {
            // After the servers: a reservation is released only once nothing the replay started needs it.
            PortRegistry registry = portRegistry;
            stopQuietly("the replay's port reservations", registry::releaseAll);
        }
        if (appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
            appender = null;
            rootLogger = null;
        }
        if (tempRootOwned && tempRoot != null) {
            Path root = tempRoot;
            stopQuietly("temporary directory " + root, () -> deleteTree(root, true));
        }
    }

    private static void stopQuietly(String what, Action stop) {
        try {
            stop.run();
        } catch (Exception e) {
            LOG.warn("Stopping {} after the fixture replay failed: {}", what, e.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Replay boundary: no trigger runs by itself, and every target the configuration names belongs to replay.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Removes Spring's {@code @Scheduled} processor from {@code beanFactory} before it is instantiated, so neither a
     * {@code @Scheduled} method nor a {@code SchedulingConfigurer} task runs by itself. It is called after the
     * configuration classes are parsed and before any singleton is created.
     *
     * @return whether the context declared the processor
     */
    static boolean suppressScheduledTriggers(ConfigurableListableBeanFactory beanFactory) {
        String processor = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if (!beanFactory.containsBeanDefinition(processor)) {
            return false;
        }
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            throw new IllegalStateException("Fixture replay cannot suppress scheduled triggers: the bean factory "
                    + beanFactory.getClass().getName() + " cannot remove " + processor);
        }
        registry.removeBeanDefinition(processor);
        LOG.info("Fixture replay: scheduled triggers suppressed; each trigger runs only through request.invoke");
        return true;
    }

    /**
     * Fails the application context, before any bean is created, when its effective configuration reaches a
     * target the replay does not own, so a missing or mistyped fixture override can never reach a live system.
     */
    static void enforceReplayBoundary(ConfigurableEnvironment environment) {
        load();
        List<String> violations = boundaryViolations(effectiveProperties(environment), ownedPorts(),
                notContactedKeys(), className -> Reflect.optionalClass(className) != null);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(boundaryMessage(violations));
        }
    }

    /**
     * The failure for {@code violations}: each key with its value, credentials masked, and reason, and how a fixture
     * resolves them.
     */
    static String boundaryMessage(List<String> violations) {
        return "Fixture replay does not start the application context: its configuration reaches targets the replay"
                + " does not own, and replay never contacts a live service.\n  " + String.join("\n  ", violations)
                + "\nOverride each key through state.properties with a replay-owned value (${wiremock.baseUrl},"
                + " ${freePort:<name>}, an in-memory URL such as jdbc:h2:mem:<name> or vm://0), or list it in"
                + " state.notContacted when the original never connected to it.";
    }

    /**
     * Every key of the context's enumerable property sources but the host's own, with its value as the application
     * reads it, plus the Spring Boot connection keys of {@link #CLIENT_DEFAULT_KEYS} and {@link #BOOT_CONNECTION_KEYS}
     * that resolve. Those are looked up through {@code environment}, so a value set only in the host's environment
     * variables or system properties counts too, under its relaxed name ({@code SPRING_DATASOURCE_URL} for
     * {@code spring.datasource.url}), as Boot binds it. A key enters the map only when it resolves, or with an empty
     * value when a scanned source declares it without one.
     */
    static Map<String, String> effectiveProperties(ConfigurableEnvironment environment) {
        Set<String> keys = new TreeSet<>(properties.keySet());
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable
                    && !UNSCANNED_PROPERTY_SOURCES.contains(source.getName())) {
                keys.addAll(Arrays.asList(enumerable.getPropertyNames()));
            }
        }
        Set<String> lookups = new LinkedHashSet<>(keys);
        lookups.addAll(CLIENT_DEFAULT_KEYS);
        lookups.addAll(BOOT_CONNECTION_KEYS);
        Map<String, String> effective = new TreeMap<>();
        for (String key : lookups) {
            String value;
            try {
                value = environment.getProperty(key);
            } catch (IllegalArgumentException unresolvable) {
                // A placeholder nothing resolves: no client can be configured from the key, so it names no target.
                continue;
            } catch (ConversionException notText) {
                // A value that is no text, such as the empty map of a YAML "key: {}", names no address.
                continue;
            }
            if (value != null) {
                effective.put(key, value);
            } else if (keys.contains(key)) {
                effective.put(key, "");
            }
        }
        return effective;
    }

    /**
     * The loopback ports the replay listens on or holds: WireMock's and every port of the registry, each embedded
     * server's published port and each {@code ${freePort:<name>}} port, reserved or left to the listener that bound it.
     */
    static synchronized Set<Integer> ownedPorts() {
        Set<Integer> owned = new TreeSet<>(portRegistry != null ? portRegistry.ports() : Set.of());
        if (wireMock != null) {
            owned.add(wireMock.port());
        }
        return owned;
    }

    /** The keys every fixture's {@code state.notContacted} names together: keys the original never connected to. */
    private static Set<String> notContactedKeys() {
        Set<String> keys = new TreeSet<>();
        for (Fixture fixture : fixtures.values()) {
            for (JsonNode key : elements(fixture.json().path("state").get("notContacted"),
                    fixture.name() + ": state.notContacted")) {
                keys.add(key.textValue());
            }
        }
        return keys;
    }

    /** A host and the port a client reaches it on; {@code port} is -1 when the address and its scheme give none. */
    record Address(String host, int port) {
    }

    /**
     * The targets {@code properties} reach that the replay does not own, one entry per key naming its value and the
     * reason; empty when the replay owns every one.
     *
     * <p>A target is owned when it is a loopback host ({@code localhost}, {@code 127.0.0.0/8}, {@code ::1},
     * {@code 0.0.0.0}) on one of {@code ownedPorts}, or an in-JVM address: the schemes {@code vm}, {@code file},
     * {@code classpath} and {@code jar}. Whether table state may be written to the database a JDBC URL names is
     * decided later, by {@link #replayOwnedDatabase}, and whether collection state may be written through a MongoDB
     * client by {@link #replayOwnedStore}. The replay passes the map {@link #effectiveProperties} reads, so a Spring
     * Boot connection key set only in the host's environment variables or system properties is checked as well.
     * The checks are:
     * <ul>
     *   <li>every {@code scheme://authority} of a value, each host of a multi-host authority or failover list, with
     *       the scheme's default port when none is given. The host is read after the user information, and a
     *       pattern or template such as {@code localhost*} is a host like any other, so it is not owned. Only port
     *       {@code 0} of a loopback host, the application's own random listener, names no target;</li>
     *   <li>a value starting with {@code jdbc:}, in any case, whose location before its {@code ;} or {@code ?}
     *       parameters holds no {@code ://} ({@link #jdbcViolation}): without an {@code @} there, an {@code h2},
     *       {@code derby}, {@code hsqldb} or {@code sqlite} URL is an embedded database and names no network
     *       target, and any other subprotocol fails closed; with an {@code @}, the {@code host[:port]} after the
     *       last one, read without a leading {@code //}, a {@code /service} or a {@code :sid}, must be a loopback
     *       host on an owned port. Every {@code scheme://} address among its parameters is checked as well;</li>
     *   <li>a value of {@code host(port)} items, as an IBM MQ {@code connName} lists them;</li>
     *   <li>a key whose last segment ends with {@code host} or is {@code hostname}: its value must be a loopback
     *       host, on an owned port when it or its sibling {@code <prefix>.port} gives a port other than 0;</li>
     *   <li>a key whose last segment ends with {@code url}, {@code uri} or {@code endpoint} with an empty value, from
     *       which a client derives a default address;</li>
     *   <li>where Spring AMQP, Artemis, the MongoDB driver or Spring LDAP is on the classpath, the address Spring
     *       Boot connects it to when its keys are unset (an embedded Artemis server is owned).</li>
     * </ul>
     *
     * @param properties   property keys and their effective values, as {@link #effectiveProperties} reads them
     * @param ownedPorts   the loopback ports replay servers listen on
     * @param exemptKeys   the keys the original never connected to, which are not checked
     * @param classPresent whether a class is on the classpath
     * @return one {@code key = "value": reason} entry per violating key, keys in order, client defaults last; the
     *         value is shown with its credentials masked ({@link #redact}) and the reason names the target by
     *         {@code host:port}
     */
    static List<String> boundaryViolations(Map<String, String> properties, Set<Integer> ownedPorts,
            Set<String> exemptKeys, Predicate<String> classPresent) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (Map.Entry<String, String> property : new TreeMap<>(properties).entrySet()) {
            String key = property.getKey();
            String value = property.getValue() == null ? "" : property.getValue();
            String problem = exemptKeys.contains(key) ? null : propertyViolation(key, value, properties, ownedPorts);
            if (problem != null) {
                violations.put(key, violation(key, value, problem));
            }
        }
        if (classPresent.test(RABBIT_FACTORY_CLASS)) {
            String addresses = properties.getOrDefault("spring.rabbitmq.addresses", "");
            if (!addresses.isBlank()) {
                clientDefault(violations, exemptKeys, "spring.rabbitmq.addresses", addresses,
                        brokerAddressesViolation(addresses, ownedPorts), "Spring AMQP");
            } else {
                String host = properties.getOrDefault("spring.rabbitmq.host", "localhost");
                String port = properties.getOrDefault("spring.rabbitmq.port", "5672");
                clientDefault(violations, exemptKeys, "spring.rabbitmq.host", host + ":" + port,
                        hostPortViolation(host, parsePort(port, 5672), ownedPorts), "Spring AMQP");
            }
        }
        if (classPresent.test(ARTEMIS_FACTORY_CLASS)) {
            String mode = properties.getOrDefault("spring.artemis.mode", "").strip();
            boolean embedded = mode.isEmpty()
                    ? !"false".equalsIgnoreCase(properties.getOrDefault("spring.artemis.embedded.enabled", "").strip())
                            && ARTEMIS_EMBEDDED_CLASSES.stream().anyMatch(classPresent)
                    : mode.equalsIgnoreCase("embedded");
            if (!embedded) {
                String url = properties.getOrDefault("spring.artemis.broker-url", "");
                url = url.isBlank() ? "tcp://localhost:61616" : url;
                clientDefault(violations, exemptKeys, "spring.artemis.broker-url", url,
                        url.contains("://") ? urlViolation(url, ownedPorts) : "it is not a broker URL", "Artemis");
            }
        }
        if (MONGO_CLIENT_CLASSES.stream().anyMatch(classPresent)) {
            String uri = properties.getOrDefault("spring.data.mongodb.uri", "");
            if (!uri.isBlank()) {
                clientDefault(violations, exemptKeys, "spring.data.mongodb.uri", uri,
                        uri.contains("://") ? urlViolation(uri, ownedPorts) : "it is not a MongoDB connection string",
                        "the MongoDB driver");
            } else {
                String host = properties.getOrDefault("spring.data.mongodb.host", "localhost");
                String port = properties.getOrDefault("spring.data.mongodb.port", "27017");
                clientDefault(violations, exemptKeys, "spring.data.mongodb.host", host + ":" + port,
                        hostPortViolation(host, parsePort(port, 27017), ownedPorts), "the MongoDB driver");
            }
        }
        if (classPresent.test(LDAP_TEMPLATE_CLASS) && properties.entrySet().stream().noneMatch(property ->
                (property.getKey().equals("spring.ldap.urls") || property.getKey().startsWith("spring.ldap.urls["))
                        && property.getValue() != null && !property.getValue().isBlank())) {
            String url = "ldap://localhost:389";
            clientDefault(violations, exemptKeys, "spring.ldap.urls", url, urlViolation(url, ownedPorts),
                    "Spring LDAP");
        }
        return List.copyOf(violations.values());
    }

    /** The reason one key's value reaches a target the replay does not own, or {@code null}. */
    private static String propertyViolation(String key, String value, Map<String, String> properties,
            Set<Integer> ownedPorts) {
        int dot = key.lastIndexOf('.');
        String segment = lower(key.substring(dot + 1)).replaceFirst("\\[[^\\]]*\\]$", "");
        boolean hostKey = segment.endsWith("host") || segment.equals("hostname");
        if (value.isBlank()) {
            if (segment.endsWith("url") || segment.endsWith("uri") || segment.endsWith("endpoint")) {
                return "the value is empty, so the client derives a default address";
            }
            return hostKey ? "the value is empty, so the client falls back to a default host" : null;
        }
        if (value.strip().regionMatches(true, 0, "jdbc:", 0, "jdbc:".length())
                && !withoutParameters(value).contains("://")) {
            // A parameter's URL is checked too, but never stands in for the database address of the location.
            String problem = value.contains("://") ? urlViolation(value, ownedPorts) : null;
            return problem != null ? problem : jdbcViolation(value, ownedPorts);
        }
        if (value.contains("://")) {
            return urlViolation(value, ownedPorts);
        }
        if (HOST_PORT_LIST.matcher(value).matches()) {
            Matcher item = HOST_PORT_ITEM.matcher(value);
            while (item.find()) {
                String problem = targetProblem(new Address(item.group(1), parsePort(item.group(2), -1)), ownedPorts);
                if (problem != null) {
                    return problem;
                }
            }
            return null;
        }
        if (!hostKey) {
            return null;
        }
        Address address = address(value, -1);
        if (!isLoopback(address.host())) {
            return address.host() + " is not a loopback host";
        }
        int port = address.port() >= 0 ? address.port()
                : parsePort(properties.get(key.substring(0, dot + 1) + "port"), -1);
        return port > 0 && !ownedPorts.contains(port)
                ? "the port of " + endpoint(new Address(address.host(), port)) + " belongs to no replay server" : null;
    }

    /**
     * The reason {@code url}, a JDBC URL whose location (the text before its first {@code ;} or {@code ?}) holds no
     * {@code ://}, reaches a target the replay does not own, or {@code null}. Without an {@code @} in the location,
     * an {@code h2}, {@code derby}, {@code hsqldb} or {@code sqlite} URL names an embedded database and no network
     * target, and the table-state check decides whether the replay owns it ({@link #replayOwnedDatabase}); any other
     * subprotocol names an address the replay cannot read, such as {@code jdbc:postgresql:<database>} on the
     * driver's default host, so it fails closed. With an {@code @}, as {@code jdbc:oracle:thin:@host:1521:sid},
     * {@code jdbc:oracle:thin:@//host:1521/service} or {@code jdbc:oracle:oci:@alias} name their server, the target
     * is the {@code host[:port]} after the last {@code @} ({@link #jdbcAddress}), owned only as a loopback host on
     * one of {@code ownedPorts}.
     */
    private static String jdbcViolation(String url, Set<Integer> ownedPorts) {
        String location = withoutParameters(url);
        int at = location.lastIndexOf('@');
        if (at < 0) {
            int end = location.indexOf(':', "jdbc:".length());
            String subprotocol = lower(location.substring("jdbc:".length(), end < 0 ? location.length() : end));
            return EMBEDDED_JDBC_SUBPROTOCOLS.contains(subprotocol) ? null
                    : "the JDBC URL names no address the replay can place on a replay-owned target";
        }
        Address address = jdbcAddress(location.substring(at + 1));
        if (isLoopback(address.host()) && ownedPorts.contains(address.port())) {
            return null;
        }
        // targetProblem accepts port 0 of a loopback host as the application's own listener; no database is there.
        String problem = targetProblem(address, ownedPorts);
        return problem != null ? problem : "the port of " + endpoint(address) + " belongs to no replay server";
    }

    /**
     * The {@code host[:port]} of the JDBC address {@code target}, the text after the URL's last {@code @}: without a
     * leading {@code //}, a {@code /service} or a {@code :sid}; the port is -1 when it names none. Any other form,
     * such as a TNS descriptor in parentheses, is read as one host, which is no loopback host.
     */
    private static Address jdbcAddress(String target) {
        String text = target.strip();
        if (text.startsWith("//")) {
            text = text.substring("//".length());
        }
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        int portColon = text.indexOf(':', text.startsWith("[") ? Math.max(text.indexOf(']'), 0) : 0);
        int sidColon = portColon < 0 ? -1 : text.indexOf(':', portColon + 1);
        return address(sidColon < 0 ? text : text.substring(0, sidColon), -1);
    }

    /** Records the violation of a client's effective address under {@code key}, unless exempt or already recorded. */
    private static void clientDefault(Map<String, String> violations, Set<String> exemptKeys, String key, String value,
            String problem, String client) {
        if (problem != null && !exemptKeys.contains(key)) {
            violations.putIfAbsent(key, violation(key, value, problem + "; " + client
                    + " is on the classpath and connects there"));
        }
    }

    private static String violation(String key, String value, String problem) {
        return key + " = " + quote(redact(key, value)) + ": " + problem;
    }

    /**
     * {@code value} as a diagnostic may print it, its credentials masked as {@code ***}: the whole value when the
     * last segment of {@code key} names a secret; otherwise the user information of every {@code scheme://}
     * authority ({@code scheme://***@host}), of a JDBC URL that names its user before an {@code @} without
     * {@code ://} ({@code jdbc:oracle:thin:***@host}) and of each entry of an address list without a scheme
     * ({@code ***@host:5672}), and the value of every {@code ?}, {@code &}, {@code ;} or list parameter whose name
     * names a secret ({@code password=***}). A name names a secret when one of its words, split at punctuation and
     * at a lower-to-upper case change, is one of {@link #SECRET_WORDS}. Keys, hosts and ports stay readable, so the
     * diagnostic still names the target it is about.
     *
     * @param key   the property key the value belongs to, or {@code null} for a value without one, such as the URL a
     *              DataSource reports
     * @param value the configured value or URL; {@code null} stays {@code null}
     */
    static String redact(String key, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (key != null && secretName(key.substring(key.lastIndexOf('.') + 1).replaceFirst("\\[[^\\]]*\\]$", ""))) {
            return MASK;
        }
        String masked = PARAMETER.matcher(value).replaceAll(parameter -> Matcher.quoteReplacement(
                secretName(parameter.group(2)) ? parameter.group(1) + parameter.group(2) + "=" + MASK
                        : parameter.group()));
        return masked.contains("://") ? maskAuthorityUserInfo(masked)
                : masked.startsWith("jdbc:") ? maskJdbcUserInfo(masked) : maskAddressUserInfo(masked);
    }

    /** Whether one word of {@code name} is a {@link #SECRET_WORDS secret word}. */
    private static boolean secretName(String name) {
        return Arrays.stream(NAME_WORD_BOUNDARY.split(name)).anyMatch(word -> SECRET_WORDS.contains(lower(word)));
    }

    /**
     * {@code value} with the user information of each {@code scheme://} authority masked. The authority runs to the
     * first {@code /}, {@code ?}, {@code #}, parenthesis, quote, white space or next scheme, so a {@code ;} or a
     * {@code ,} inside it never shortens what is masked.
     */
    private static String maskAuthorityUserInfo(String value) {
        StringBuilder masked = new StringBuilder(value.length());
        int copied = 0;
        int separator = value.indexOf("://");
        while (separator >= 0) {
            int authorityStart = separator + "://".length();
            int next = value.indexOf("://", authorityStart);
            int end = authorityStart;
            while (end < value.length() && !isUriAuthorityEnd(value.charAt(end))) {
                end++;
            }
            if (next >= 0 && next < end) {
                end = Math.max(authorityStart, schemeStart(value, next));
            }
            int at = value.lastIndexOf('@', end - 1);
            if (at >= authorityStart) {
                masked.append(value, copied, authorityStart).append(MASK);
                copied = at;
            }
            separator = next;
        }
        return masked.append(value, copied, value.length()).toString();
    }

    private static boolean isUriAuthorityEnd(char c) {
        return c == '/' || c == '?' || c == '#' || c == '(' || c == ')' || c == '"' || c == '\''
                || Character.isWhitespace(c);
    }

    /**
     * {@code value}, a JDBC URL without {@code ://}, with the user information it names before its last {@code @}
     * masked, as {@code jdbc:oracle:thin:user/password@host} does: from the {@code :} that precedes its first
     * {@code /} or {@code @}, so a {@code :}, {@code /} or {@code @} inside the password is masked too.
     */
    private static String maskJdbcUserInfo(String value) {
        int lastAt = value.lastIndexOf('@');
        if (lastAt < 0) {
            return value;
        }
        int slash = value.indexOf('/');
        int firstAt = value.indexOf('@');
        int start = value.lastIndexOf(':', (slash >= 0 ? Math.min(slash, firstAt) : firstAt) - 1) + 1;
        return start < lastAt ? value.substring(0, start) + MASK + value.substring(lastAt) : value;
    }

    /**
     * {@code value}, a list of addresses without a scheme such as {@code spring.rabbitmq.addresses}, with the user
     * information of each {@code user:password@host:port} entry masked: the text before an entry's last {@code @}.
     */
    private static String maskAddressUserInfo(String value) {
        StringBuilder masked = new StringBuilder(value.length());
        int entryStart = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == ',' || Character.isWhitespace(value.charAt(i))) {
                String entry = value.substring(entryStart, i);
                int at = entry.lastIndexOf('@');
                masked.append(at > 0 ? MASK + entry.substring(at) : entry);
                if (i < value.length()) {
                    masked.append(value.charAt(i));
                }
                entryStart = i + 1;
            }
        }
        return masked.toString();
    }

    /**
     * {@code spring.rabbitmq.addresses}: comma-separated {@code [user:password@]host[:port]} or {@code amqp[s]://}
     * addresses; the host is read after the user information.
     */
    private static String brokerAddressesViolation(String addresses, Set<Integer> ownedPorts) {
        for (String entry : addresses.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String problem = entry.contains("://") ? urlViolation(entry, ownedPorts)
                    : hostPortViolation(entry.substring(entry.lastIndexOf('@') + 1), 5672, ownedPorts);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /** A client's host and port: the replay owns it only as a loopback host on an owned port. */
    private static String hostPortViolation(String host, int defaultPort, Set<Integer> ownedPorts) {
        if (host.isBlank()) {
            return "the host is empty, so the client falls back to a default host";
        }
        return targetProblem(address(host, defaultPort), ownedPorts);
    }

    /** The first {@code scheme://authority} of {@code value} the replay does not own, or {@code null}. */
    private static String urlViolation(String value, Set<Integer> ownedPorts) {
        for (Address address : urlAddresses(value)) {
            String problem = targetProblem(address, ownedPorts);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String targetProblem(Address address, Set<Integer> ownedPorts) {
        boolean loopback = isLoopback(address.host());
        if (loopback && address.port() == 0) {
            // Port 0 of a loopback host is the application's own random listener (RANDOM_PORT), never a service.
            return null;
        }
        if (!loopback) {
            return address.host().isEmpty() ? "the address names no host"
                    : endpoint(address) + " is not a loopback host";
        }
        if (address.port() < 0) {
            return address.host() + " is given without a port, and its scheme has no default";
        }
        return ownedPorts.contains(address.port()) ? null
                : "the port of " + endpoint(address) + " belongs to no replay server";
    }

    /**
     * {@code host:port}, an IPv6 host in brackets, or the host alone without a port: the target a diagnostic names,
     * which stays readable when {@link #redact} masks the value it came from.
     */
    private static String endpoint(Address address) {
        String host = address.host().contains(":") && !address.host().startsWith("[")
                ? "[" + address.host() + "]" : address.host();
        return address.port() < 0 ? host : host + ":" + address.port();
    }

    /**
     * Every host the {@code scheme://authority} occurrences of {@code value} name, with the scheme's default port
     * when the authority gives none, including each host of a multi-host authority ({@code mongodb://a:1,b:2/db})
     * and of a failover list ({@code failover:(tcp://a:1,tcp://b:2)}). The host is read after the last {@code @} of
     * the authority, so no user information hides it, and whatever characters it holds: a pattern or template such
     * as {@code localhost*} is a host like any other. Only addresses of an in-JVM scheme name no target.
     */
    static List<Address> urlAddresses(String value) {
        List<Address> addresses = new ArrayList<>();
        int separator = value.indexOf("://");
        while (separator >= 0) {
            int authorityStart = separator + "://".length();
            int next = value.indexOf("://", authorityStart);
            int end = authorityStart;
            while (end < value.length() && !isAuthorityEnd(value.charAt(end))) {
                end++;
            }
            if (next >= 0 && next < end) {
                // A list such as failover:(tcp://a:1,tcp://b:2): this authority ends where the next scheme starts.
                end = Math.max(authorityStart, schemeStart(value, next));
            }
            String scheme = lower(value.substring(schemeStart(value, separator), separator));
            String authority = value.substring(authorityStart, end);
            if (!scheme.isEmpty() && !IN_JVM_SCHEMES.contains(scheme)) {
                int defaultPort = DEFAULT_PORTS.getOrDefault(scheme, -1);
                List<Address> hosts = new ArrayList<>();
                for (String host : authority.substring(authority.lastIndexOf('@') + 1).split(",")) {
                    if (!host.isBlank()) {
                        hosts.add(address(host, defaultPort));
                    }
                }
                addresses.addAll(hosts.isEmpty() ? List.of(new Address("", defaultPort)) : hosts);
            }
            separator = next;
        }
        return addresses;
    }

    private static boolean isAuthorityEnd(char c) {
        return c == '/' || c == '?' || c == '#' || c == '(' || c == ')' || c == ';' || c == '"' || c == '\''
                || Character.isWhitespace(c);
    }

    /** The start of the URI scheme that ends at {@code separator}, the index of its {@code ://}. */
    private static int schemeStart(String value, int separator) {
        int start = separator;
        while (start > 0 && isSchemeChar(value.charAt(start - 1))) {
            start--;
        }
        while (start < separator && !isAsciiLetter(value.charAt(start))) {
            start++;
        }
        return start;
    }

    private static boolean isSchemeChar(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** {@code host}, {@code host:port} or {@code [v6]:port}, with {@code defaultPort} when it gives no port. */
    static Address address(String hostPort, int defaultPort) {
        String text = hostPort.strip();
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close > 0) {
                String rest = text.substring(close + 1);
                return new Address(text.substring(1, close),
                        rest.startsWith(":") ? parsePort(rest.substring(1), defaultPort) : defaultPort);
            }
            return new Address(text, defaultPort);
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && colon == text.lastIndexOf(':')) {
            return new Address(text.substring(0, colon), parsePort(text.substring(colon + 1), defaultPort));
        }
        // A bare IPv6 literal such as ::1 holds several colons and no port.
        return new Address(text, defaultPort);
    }

    /** A TCP port number; {@code defaultPort} when {@code text} is absent or blank, -1 when it is no port. */
    private static int parsePort(String text, int defaultPort) {
        if (text == null || text.isBlank()) {
            return defaultPort;
        }
        String digits = text.strip();
        if (!DIGIT_RUN.matcher(digits).matches() || digits.length() > 5) {
            return -1;
        }
        int port = Integer.parseInt(digits);
        return port <= 65_535 ? port : -1;
    }

    static boolean isLoopback(String host) {
        String name = lower(host.strip());
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.equals("localhost") || name.equals("::1") || name.equals("0:0:0:0:0:0:0:1")
                || name.equals("0.0.0.0") || LOOPBACK_IPV4.matcher(name).matches();
    }

    /**
     * Whether a JDBC URL names a database the replay owns, so its table state may be seeded, emptied and read. A
     * database is owned through its identity or its location:
     * <ul>
     *   <li>an in-memory database: {@code jdbc:h2:mem:}, {@code jdbc:derby:memory:}, {@code jdbc:hsqldb:mem:};</li>
     *   <li>an embedded file database whose path lies strictly inside {@code replayRoot}, as {@link #confine}
     *       decides, so neither {@code ..} nor a symbolic link leads out of it: H2 {@code jdbc:h2:file:<path>} and
     *       {@code jdbc:h2:<path>}, Derby {@code jdbc:derby:<name or path>} and {@code jdbc:derby:directory:<path>},
     *       and HSQLDB {@code jdbc:hsqldb:file:<path>}. The path is read without its {@code ;} or {@code ?}
     *       parameters; a leading {@code ~} stands for {@code user.home} (H2, HSQLDB), and a relative path is read
     *       against {@code h2.baseDir} (H2) or {@code derby.system.home} (Derby) when that system property is set,
     *       else against {@code user.dir};</li>
     *   <li>a network database, the URL's location before its parameters holding {@code ://}, whose every host is
     *       a loopback address on one of {@code ownedPorts}.</li>
     * </ul>
     * Every {@code scheme://} address elsewhere in the URL, such as a script its parameters load, must be owned in the
     * same way. Every other URL is not owned, an embedded form not listed here included (H2 {@code zip:},
     * {@code nio:} or {@code split:}, Derby {@code classpath:} or {@code jar:}, HSQLDB {@code res:}, SQLite, ...),
     * so the check fails closed.
     *
     * @param url        the URL the application's DataSource reports
     * @param ownedPorts the loopback ports replay servers listen on
     * @param replayRoot the temporary directory the replay owns
     */
    static boolean replayOwnedDatabase(String url, Set<Integer> ownedPorts, Path replayRoot) {
        if (url == null || !url.startsWith("jdbc:")) {
            return false;
        }
        List<Address> addresses = urlAddresses(url);
        if (!addresses.stream().allMatch(address -> isLoopback(address.host())
                && ownedPorts.contains(address.port()))) {
            return false;
        }
        String location = withoutParameters(url);
        if (location.contains("://")) {
            return !addresses.isEmpty();
        }
        String name = location.substring(location.indexOf(':', "jdbc:".length()) + 1);
        if (url.startsWith("jdbc:h2:")) {
            if (name.startsWith("mem:")) {
                return true;
            }
            String path = name.startsWith("file:") ? name.substring("file:".length()) : name;
            return embeddedFileInside(path, System.getProperty("h2.baseDir"), true, replayRoot);
        }
        if (url.startsWith("jdbc:derby:")) {
            if (name.startsWith("memory:")) {
                return true;
            }
            String path = name.startsWith("directory:") ? name.substring("directory:".length()) : name;
            return embeddedFileInside(path, System.getProperty("derby.system.home"), false, replayRoot);
        }
        if (url.startsWith("jdbc:hsqldb:")) {
            if (name.startsWith("mem:")) {
                return true;
            }
            return name.startsWith("file:")
                    && embeddedFileInside(name.substring("file:".length()), null, true, replayRoot);
        }
        return false;
    }

    /**
     * Whether a MongoDB client reaches only servers the replay owns, so collection state may be dropped, seeded and
     * read through it: {@code hosts}, the server addresses the client is configured for, is not empty and each is a
     * loopback host on one of {@code ownedPorts}, such as the embedded MongoDB server on its bind port. Any other
     * list, an entry without a port or on port {@code 0} included, is not owned, so the check fails closed.
     *
     * @param hosts      every server address of the client as {@code host:port}, an IPv6 host in brackets
     * @param ownedPorts the loopback ports replay servers listen on
     */
    static boolean replayOwnedStore(List<String> hosts, Set<Integer> ownedPorts) {
        return !hosts.isEmpty() && hosts.stream().map(host -> address(host, -1))
                .allMatch(address -> isLoopback(address.host()) && ownedPorts.contains(address.port()));
    }

    /** A JDBC URL's location: the text before its first {@code ;} or {@code ?} parameter. */
    private static String withoutParameters(String url) {
        int end = url.length();
        for (char separator : new char[] {';', '?'}) {
            int index = url.indexOf(separator);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return url.substring(0, end).strip();
    }

    /**
     * Whether the embedded database at {@code path} lies inside {@code replayRoot}: {@code ~} expanded when the
     * driver expands it, a relative path read against {@code baseDirectory} when set, else against {@code user.dir}.
     * A path that starts with another file-system prefix ({@code zip:}, {@code classpath:}, ...) is not owned.
     */
    private static boolean embeddedFileInside(String path, String baseDirectory, boolean expandsHome,
            Path replayRoot) {
        if (path.isEmpty() || hasFileSystemPrefix(path)) {
            return false;
        }
        try {
            Path file;
            if (expandsHome && (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\"))) {
                file = Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^[/\\\\]+", ""));
            } else {
                Path given = Path.of(path);
                String base = baseDirectory == null || baseDirectory.isBlank()
                        ? System.getProperty("user.dir") : baseDirectory;
                file = given.isAbsolute() ? given : Path.of(base).resolve(given);
            }
            confine(replayRoot, file.toAbsolutePath().normalize(), "the embedded database " + path);
            return true;
        } catch (InvalidPathException | IllegalStateException | IOException notInside) {
            return false;
        }
    }

    /** Whether {@code path} starts with a {@code prefix:} before its first separator, other than a drive letter. */
    private static boolean hasFileSystemPrefix(String path) {
        int colon = path.indexOf(':');
        if (colon < 0) {
            return false;
        }
        int slash = path.indexOf('/');
        int backslash = path.indexOf('\\');
        int separator = slash < 0 ? backslash : backslash < 0 ? slash : Math.min(slash, backslash);
        boolean driveLetter = colon == 1 && isAsciiLetter(path.charAt(0));
        return !driveLetter && (separator < 0 || colon < separator);
    }


    // ---------------------------------------------------------------------------------------------------------
    // Summary: target/fixture-parity/summary.properties, written without timestamp so reruns compare equal.
    // ---------------------------------------------------------------------------------------------------------

    private static void writeSummaryFile() {
        int verified = 0;
        int unverified = 0;
        int failed = 0;
        List<String> lines = new ArrayList<>();
        for (Row row : rows) {
            String status = statusOf(row);
            if (status.equals(VERIFIED)) {
                verified++;
            } else if (status.equals(FAILED)) {
                failed++;
            } else if (status.startsWith("UNVERIFIED-")) {
                unverified++;
            }
            lines.add("scenario." + row.scenario() + "=" + status);
        }
        StringBuilder summary = new StringBuilder()
                .append("verified=").append(verified).append('\n')
                .append("unverified=").append(unverified).append('\n')
                .append("failed=").append(failed).append('\n');
        lines.forEach(line -> summary.append(line).append('\n'));
        try {
            Files.createDirectories(SUMMARY_FILE.getParent());
            Files.writeString(SUMMARY_FILE, summary, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + SUMMARY_FILE.toAbsolutePath(), e);
        }
        LOG.info("Fixture parity: verified={}, unverified={}, failed={} ({})", verified, unverified, failed,
                SUMMARY_FILE.toAbsolutePath());
    }

    private static String statusOf(Row row) {
        String recorded = STATUSES.get(row.scenario());
        if (recorded != null) {
            return recorded;
        }
        if (fixtureNames.contains(row.scenario())) {
            // Captured but never replayed: the class failed before this scenario ran.
            return FAILED;
        }
        return row.unreachable() ? UNREACHABLE : UNVERIFIED_UNCAPTURED;
    }

    // ---------------------------------------------------------------------------------------------------------
    // SCENARIOS.md: the pipe table under "## Scenarios".
    // ---------------------------------------------------------------------------------------------------------

    static final class ScenarioTable {

        private ScenarioTable() {
        }

        static List<Row> parse(String markdown, String source) {
            List<String> lines = markdown.lines().toList();
            int index = 0;
            while (index < lines.size() && !lines.get(index).strip().equals("## Scenarios")) {
                index++;
            }
            if (index == lines.size()) {
                throw malformed(source, "it has no '## Scenarios' heading");
            }
            index++;
            while (index < lines.size() && !lines.get(index).startsWith("|")) {
                if (lines.get(index).startsWith("#")) {
                    break;
                }
                index++;
            }
            if (index == lines.size() || !lines.get(index).startsWith("|")) {
                throw malformed(source, "no table follows the '## Scenarios' heading");
            }
            List<String> header = cells(lines.get(index));
            if (!header.equals(COLUMNS)) {
                throw malformed(source, "the table header must be exactly '" + String.join(" | ", COLUMNS)
                        + "' but is '" + String.join(" | ", header) + "'");
            }
            index++;
            if (index == lines.size() || !lines.get(index).startsWith("|") || !isSeparator(cells(lines.get(index)))) {
                throw malformed(source, "the table header is not followed by a separator row of '-' cells");
            }
            index++;
            List<Row> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (; index < lines.size() && lines.get(index).startsWith("|"); index++) {
                List<String> cells = cells(lines.get(index));
                String line = "line " + (index + 1);
                if (cells.size() != COLUMNS.size()) {
                    throw malformed(source, line + " has " + cells.size() + " cells instead of " + COLUMNS.size());
                }
                String scenario = cells.get(0);
                String trigger = cells.get(1);
                String state = cells.get(2);
                if (!KEBAB_CASE.matcher(scenario).matches()) {
                    throw malformed(source, line + ": scenario '" + scenario + "' is not kebab-case");
                }
                if (!seen.add(scenario)) {
                    throw malformed(source, line + ": scenario '" + scenario + "' is listed twice");
                }
                if (!TRIGGERS.contains(trigger)) {
                    throw malformed(source, line + ": trigger '" + trigger + "' is not one of " + new TreeSet<>(TRIGGERS));
                }
                if (!STATES.contains(state)) {
                    throw malformed(source, line + ": state '" + state + "' is not one of " + new TreeSet<>(STATES));
                }
                parsed.add(new Row(scenario, trigger, state));
            }
            if (parsed.isEmpty()) {
                throw malformed(source, "the scenario table has no rows");
            }
            return List.copyOf(parsed);
        }

        /** Splits a table line on unescaped {@code |}, unescaping {@code \|}, trimming and unwrapping code spans. */
        static List<String> cells(String line) {
            String row = line.stripTrailing();
            List<String> raw = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            for (int i = 1; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == '\\' && i + 1 < row.length() && row.charAt(i + 1) == '|') {
                    cell.append('|');
                    i++;
                } else if (c == '|') {
                    raw.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            if (!cell.toString().isBlank()) {
                raw.add(cell.toString());
            }
            List<String> cells = new ArrayList<>();
            for (String value : raw) {
                cells.add(unwrapCodeSpan(value.strip()));
            }
            return cells;
        }

        /** Strips the backticks of a cell that is one code span, such as {@code `get-hello-world`}. */
        private static String unwrapCodeSpan(String cell) {
            int ticks = 0;
            while (ticks < cell.length() && cell.charAt(ticks) == '`') {
                ticks++;
            }
            if (ticks == 0 || cell.length() <= 2 * ticks) {
                return cell;
            }
            String fence = "`".repeat(ticks);
            String inner = cell.substring(ticks, cell.length() - ticks);
            if (!cell.endsWith(fence) || inner.contains(fence) || inner.endsWith("`")) {
                return cell;
            }
            return inner.strip();
        }

        private static boolean isSeparator(List<String> cells) {
            return cells.size() == COLUMNS.size() && cells.stream().allMatch(cell -> SEPARATOR_CELL.matcher(cell).matches());
        }

        private static IllegalStateException malformed(String source, String problem) {
            return new IllegalStateException("SCENARIOS.md (" + source + ") is malformed: " + problem);
        }
    }


    // ---------------------------------------------------------------------------------------------------------
    // Replay of one scenario.
    // ---------------------------------------------------------------------------------------------------------

    /**
     * One scenario's replay: resets, installs its upstream stubs, seeds its state, drives its trigger, compares every
     * captured effect, then resets again.
     */
    static final class Replay {

        private enum Broker { NONE, JMS, AMQP }

        /** A destination a fixture names: a topic when {@code pubSub}, otherwise a queue. */
        private record BrokerDestination(String name, boolean pubSub) {

            @Override
            public String toString() {
                return (pubSub ? "topic " : "queue ") + name;
            }
        }

        /** An input mail this replay delivered, with the email address of the mailbox it was delivered to. */
        private record DeliveredMail(String mailbox, MailView view) {
        }

        private final ApplicationContext context;
        private final Row row;
        private final Fixture fixture;
        private final String name;
        private final JsonNode request;
        private final JsonNode state;
        private final JsonNode response;
        private final long awaitMillis;
        /** {@code state.clearCaches}: the application's caches are emptied before the prior requests and the input. */
        private final boolean clearCaches;
        /** {@code state.requests}, the prior requests sent before the input; their replies are discarded. */
        private final List<JsonNode> priorRequests;
        private final List<DeliveredMail> deliveredMails = new ArrayList<>();
        /** {@code response.destinations}, the destinations the capture subscribed to or browsed, in capture order. */
        private List<BrokerDestination> inventory = List.of();
        /** A subscriber per topic of the inventory, open from before the trigger until the reset. */
        private final Map<BrokerDestination, Object> topicSubscribers = new LinkedHashMap<>();
        /** The JMS queues of the inventory: drained before the trigger and at the reset, browsed when compared. */
        private final Set<String> jmsQueues = new LinkedHashSet<>();
        private final Set<String> jmsRequestQueues = new LinkedHashSet<>();
        private final Set<String> amqpQueues = new LinkedHashSet<>();
        /** The responders of {@code state.amqp.responders}, started before the trigger and stopped by the reset. */
        private final List<Responder> responders = new ArrayList<>();
        private Broker broker = Broker.NONE;
        private Object jmsConnection;
        private Object jmsSession;
        private Object rabbitTemplate;
        /** The embedded Artemis server and its plugin refusing the sends of {@code state.jms}, until the reset. */
        private Object artemisServer;
        private Object sendDenial;

        Replay(ApplicationContext context, Row row, Fixture fixture) {
            this.context = context;
            this.row = row;
            this.fixture = fixture;
            this.name = row.scenario();
            this.request = fixture.json().path("request");
            this.state = fixture.json().path("state");
            this.response = fixture.json().path("response");
            JsonNode await = request.path("awaitMillis");
            if (await.isMissingNode() || await.isNull()) {
                this.awaitMillis = DEFAULT_AWAIT_MILLIS;
            } else if (await.isIntegralNumber() && await.canConvertToLong() && await.longValue() > 0) {
                this.awaitMillis = await.longValue();
            } else {
                throw new IllegalStateException(at("request.awaitMillis must be a positive whole number"));
            }
            readTimeoutMillis(request, at("request"));
            this.clearCaches = clearsCaches(state, at("state.clearCaches"));
            this.priorRequests = priorRequests(state, at("state.requests"));
        }

        /**
         * The {@code readTimeoutMillis} of a request-shaped object: ST-1's socket read budget for that request in
         * milliseconds, or {@code null} when it is absent or null, so ST-1 keeps its own default.
         *
         * @param what the object's location in the fixture, for the message of an invalid value
         */
        static Integer readTimeoutMillis(JsonNode spec, String what) {
            JsonNode budget = spec.path("readTimeoutMillis");
            if (budget.isMissingNode() || budget.isNull()) {
                return null;
            }
            if (budget.isIntegralNumber() && budget.canConvertToInt() && budget.intValue() > 0) {
                return budget.intValue();
            }
            throw new IllegalStateException(what + ".readTimeoutMillis must be a positive whole number");
        }

        /** {@code state.clearCaches}: {@code false} when absent or null, otherwise a JSON boolean. */
        static boolean clearsCaches(JsonNode state, String what) {
            JsonNode clear = state.path("clearCaches");
            if (clear.isMissingNode() || clear.isNull()) {
                return false;
            }
            if (!clear.isBoolean()) {
                throw new IllegalStateException(what + " must be true or false");
            }
            return clear.booleanValue();
        }

        /**
         * {@code state.requests}, in order, each checked to have the shape of an {@code http} {@code request}:
         * {@code method} and {@code path} strings, and, when present, a {@code query} string, {@code headers},
         * {@code body} or {@code bodyBase64}, a {@code portProperty} string, a {@code tls} object as {@link #tls}
         * checks it and a {@code readTimeoutMillis}; empty when absent or null.
         */
        static List<JsonNode> priorRequests(JsonNode state, String what) {
            List<JsonNode> requests = elements(state.get("requests"), what);
            for (int i = 0; i < requests.size(); i++) {
                String where = what + "[" + i + "]";
                JsonNode prior = object(requests.get(i), where);
                text(prior, "method", where + ".method");
                text(prior, "path", where + ".path");
                if (prior.hasNonNull("query")) {
                    text(prior, "query", where + ".query");
                }
                headerPairs(prior.get("headers"), where + ".headers");
                bodyBytes(prior, where);
                if (prior.hasNonNull("portProperty")) {
                    text(prior, "portProperty", where + ".portProperty");
                }
                tls(prior, where);
                readTimeoutMillis(prior, where);
            }
            return List.copyOf(requests);
        }

        /**
         * The {@code tls} of a request-shaped object, the scenario's {@code request} or a prior request, checked; or
         * {@code null} when it is absent or null, so the request goes out in plain HTTP. It is an object with a
         * {@code trustStore} string and, each optional and when present a string, {@code type} (the type of both
         * stores unless {@code keyStoreType} names the key store's; {@code JKS} when absent), {@code password} (of the
         * trust store), {@code keyStore} (the client key store, whose key ST-1 presents to a listener that requires
         * a client certificate), {@code keyStoreType} and {@code keyStorePassword} (of the key store and of its key
         * entries). A null member counts as absent. {@code keyStoreType} and {@code keyStorePassword} describe the
         * key store, so either one without {@code keyStore} makes the fixture invalid.
         *
         * @param what the object's location in the fixture, for the message of an invalid value
         */
        static JsonNode tls(JsonNode spec, String what) {
            JsonNode tls = spec.get("tls");
            if (tls == null || tls.isNull()) {
                return null;
            }
            String where = what + ".tls";
            object(tls, where);
            text(tls, "trustStore", where + ".trustStore");
            for (String member : List.of("type", "password", "keyStore", "keyStoreType", "keyStorePassword")) {
                if (tls.hasNonNull(member)) {
                    text(tls, member, where + "." + member);
                }
            }
            if (!tls.hasNonNull("keyStore")) {
                for (String member : List.of("keyStoreType", "keyStorePassword")) {
                    if (tls.hasNonNull(member)) {
                        throw new IllegalStateException(where + "." + member + " is set without keyStore");
                    }
                }
            }
            return tls;
        }

        /**
         * Empties every cache of every {@link CacheManager} bean of {@code context}, each at once, as the restart the
         * capture made before a scenario left the original's in-memory stores empty.
         *
         * @return the number of cache managers found, {@code 0} when the context has none
         */
        static int clearCaches(ApplicationContext context) {
            Collection<CacheManager> managers = context.getBeansOfType(CacheManager.class).values();
            for (CacheManager manager : managers) {
                for (String cacheName : manager.getCacheNames()) {
                    Cache cache = manager.getCache(cacheName);
                    if (cache != null) {
                        cache.invalidate();
                    }
                }
            }
            return managers.size();
        }

        /**
         * Replays the scenario. It starts from a fresh reset, so nothing an earlier scenario or the context start left
         * in the shared servers and stores is visible, and installs the upstream stubs before any input is exposed.
         * The application's own in-memory state carries over from the scenarios before it, in table order; only
         * {@code state.clearCaches} empties the application's caches, once the state is seeded, as the capture's
         * restart emptied the original's. The prior requests of {@code state.requests} are sent next, their replies
         * discarded, and the input last. With scheduling suppressed, the trigger runs only when the driver sends the
         * input or calls the single-run method the fixture names.
         */
        void execute() throws Exception {
            requireInvocation();
            requireDrivers();
            ensureAppender();
            reset();
            prepareMessages();
            registerUpstream();
            seedState();
            if (clearCaches) {
                clearCaches(context);
            }
            sendPriorRequests();
            try {
                switch (row.trigger()) {
                    case "http", "soap" -> replayHttp();
                    case "file", "scheduler" -> replayFile();
                    case "jms", "mq" -> replayJms();
                    case "amqp" -> replayAmqp();
                    case "mail" -> replayMail();
                    default -> throw new IllegalStateException(at("no replay driver for trigger " + row.trigger()));
                }
                awaitResponders();
                verifySideEffects();
            } catch (AssertionError mismatch) {
                // An upstream call that matched no recording usually explains the mismatch; report it alongside.
                if (wireMock != null && !wireMock.findAllUnmatchedRequests().isEmpty()) {
                    mismatch.addSuppressed(new AssertionError(name + ": upstream calls matched no recorded exchange: "
                            + describeRequests(wireMock.findAllUnmatchedRequests())));
                }
                throw mismatch;
            }
            verifyUpstream();
        }

        /** Resets after a failed replay, keeping the replay's failure as the one reported. */
        void resetAfter(Throwable failure) {
            try {
                reset();
            } catch (Exception e) {
                failure.addSuppressed(e);
            }
        }

        /**
         * Returns every shared server and store to the state a scenario starts from; runs before and after each. The
         * fixture's tables that exist are emptied, and one that does not exist yet is left absent, never created
         * ({@link #deleteTables}). It clears no application cache and no other in-memory state of the application, so
         * scenarios that build on one another in table order keep doing so; only a fixture's
         * {@code state.clearCaches} empties the caches.
         */
        void reset() throws Exception {
            List<Exception> failures = new ArrayList<>();
            attempt(failures, () -> {
                if (wireMock != null) {
                    wireMock.resetAll();
                }
            });
            for (Path directory : registeredDirectories()) {
                attempt(failures, () -> deleteTree(directory, false));
            }
            attempt(failures, this::deleteTables);
            attempt(failures, FixtureParityTest::clearAppender);
            attempt(failures, () -> {
                if (greenMail != null) {
                    Reflect.call(greenMail, "purgeEmailFromAllMailboxes");
                }
            });
            attempt(failures, this::stopResponders);
            attempt(failures, this::drainQueues);
            attempt(failures, this::removeSendDenial);
            if (!failures.isEmpty()) {
                Exception first = failures.get(0);
                failures.subList(1, failures.size()).forEach(first::addSuppressed);
                throw first;
            }
        }

        private static void attempt(List<Exception> failures, Action action) {
            try {
                action.run();
            } catch (Exception e) {
                failures.add(e);
            }
        }

        // ----- drivers and servers the fixture needs -------------------------------------------------------

        /**
         * A polled trigger replays only through the single-run method its fixture names: scheduling is suppressed, so
         * without {@code request.invoke} nothing would process the input, and replay never waits on a schedule.
         */
        private void requireInvocation() {
            JsonNode invoke = request.get("invoke");
            if (INVOKED_TRIGGERS.contains(row.trigger()) && (invoke == null || invoke.isNull())) {
                throw new IllegalStateException(at("request.invoke is required for a " + row.trigger() + " trigger:"
                        + " scheduled triggers do not run during replay, so the fixture must name the single-run"
                        + " method that processes its input, for example {\"type\": \"trigger.<Trigger>\","
                        + " \"method\": \"pollOnce\"}"));
            }
        }

        private void requireDrivers() {
            Needs needs = fixture.needs();
            requireServer(needs.greenMail(), "greenmail", greenMail);
            requireServer(needs.qpid(), "qpid", qpid);
            requireServer(needs.mongo(), "mongo", mongoServer);
            requireServer(needs.ldap(), "ldap", ldapServer);
            if (row.trigger().equals("mail") || response.has("mails") || response.has("mailboxes")) {
                requireClass(MIME_MESSAGE_CLASS);
            }
            if (state.has("tables") || response.has("tables")) {
                requireBean(DataSource.class, DataSource.class.getName());
            }
            if (state.has("collections") || response.has("collections")) {
                requireClass(BSON_DOCUMENT_CLASS);
                requireBean(requireClass(MONGO_TEMPLATE_CLASS), MONGO_TEMPLATE_CLASS);
            }
            if (response.has("logs") && appender == null) {
                missingDriver("Logback (ch.qos.logback.classic.Logger) as the SLF4J binding");
            }
            if (state.has("jms")) {
                requireBean(requireClass(ARTEMIS_EMBEDDED_SERVER_CLASS), ARTEMIS_EMBEDDED_SERVER_CLASS);
            }
            if (state.path("amqp").has("responders")) {
                requireBean(requireClass(RABBIT_TEMPLATE_CLASS), RABBIT_TEMPLATE_CLASS);
            }
            if (clearCaches && context.getBeanNamesForType(CacheManager.class).length == 0) {
                // Without a cache manager the replay cannot empty the caches the capture's restart emptied, so the
                // cold start the fixture records cannot be re-created and its comparison would prove nothing.
                missingDriver("a " + CacheManager.class.getName() + " bean, whose caches state.clearCaches empties");
            }
            if (!priorRequests.isEmpty()) {
                requireClass(CLIENT_CLASS);
            }
        }

        private void requireServer(boolean needed, String key, Object server) {
            if (needed && server == null) {
                missingDriver(MISSING_SERVER_CLASSES.getOrDefault(key, key));
            }
        }

        private Class<?> requireClass(String className) {
            Class<?> type = Reflect.optionalClass(className);
            if (type == null) {
                return missingDriver(className);
            }
            return type;
        }

        private Object requireBean(Class<?> type, String typeName) {
            Object bean = context.getBeanProvider(type).getIfAvailable();
            if (bean == null) {
                return missingDriver("a " + typeName + " bean");
            }
            return bean;
        }

        private <T> T missingDriver(String what) {
            return abort(name, UNVERIFIED_MISSING_DRIVER, row.trigger() + " replay needs " + what);
        }

        // ----- state -----------------------------------------------------------------------------------------

        private void seedState() throws Exception {
            for (Map.Entry<String, JsonNode> entry : entries(state.get("directories"), at("state.directories"))) {
                String what = at("state.directories." + entry.getKey());
                Path directory = resolveDirectory(entry.getKey(), name);
                Files.createDirectories(directory);
                for (JsonNode file : elements(entry.getValue(), what)) {
                    writeFile(directory, file, what);
                }
            }
            if (state.has("tables")) {
                seedTables();
            }
            if (state.has("collections")) {
                seedCollections();
            }
            if (state.has("ldap")) {
                seedLdap();
            }
            registerMailUsers(fixture);
            // After prepareMessages has subscribed and drained, before the trigger sends any input.
            installSendDenial();
            startResponders();
        }

        /** Writes the file beside the target first and moves it in, so a poller never reads it half-written. */
        private void writeFile(Path directory, JsonNode file, String what) throws IOException {
            String fileName = text(file, "name", what + "[].name");
            Path target = directory.resolve(fileName).normalize();
            if (!target.startsWith(directory) || target.equals(directory)) {
                throw new IllegalStateException(what + ": file name " + fileName + " leaves its directory");
            }
            // Confined before and after it is created, so no symbolic link below the directory leads out of the root.
            Path root = tempRoot();
            String where = what + ": file " + fileName;
            confine(root, target.getParent(), where);
            Files.createDirectories(target.getParent());
            confine(root, target.getParent(), where);
            Path staged = Files.createTempFile(root, "staged-", ".tmp");
            Files.write(staged, bodyBytes(file, what + "." + fileName));
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        /**
         * Empties each table of {@code state.tables} and inserts its rows. Every such table must already exist
         * ({@link #requireTable}): the replay creates no table, so a seeded table the application has not created
         * fails the scenario before any of its rows is written.
         */
        private void seedTables() throws Exception {
            DataSource dataSource = (DataSource) requireBean(DataSource.class, DataSource.class.getName());
            try (Connection connection = dataSource.getConnection()) {
                confirmReplayDatabase(connection);
                for (Map.Entry<String, JsonNode> table : entries(state.get("tables"), at("state.tables"))) {
                    String tableName = identifier(table.getKey());
                    requireTable(connection, tableName, at("state.tables." + tableName));
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM " + tableName);
                    }
                    for (JsonNode tableRow : elements(table.getValue(), at("state.tables." + tableName))) {
                        insertRow(connection, tableName, tableRow);
                    }
                }
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        }

        private void insertRow(Connection connection, String table, JsonNode tableRow) throws Exception {
            List<Map.Entry<String, JsonNode>> columns = entries(tableRow, at("state.tables." + table + "[]"));
            if (columns.isEmpty()) {
                throw new IllegalStateException(at("state.tables." + table + " holds a row without columns"));
            }
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonNode> column : columns) {
                names.add(identifier(column.getKey()));
            }
            String sql = "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES ("
                    + String.join(", ", Collections.nCopies(names.size(), "?")) + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < columns.size(); i++) {
                    statement.setObject(i + 1, jdbcValue(columns.get(i).getValue()));
                }
                statement.executeUpdate();
            }
        }

        /**
         * Empties, for the reset, every table the fixture names in {@code state.tables} or {@code response.tables}
         * that exists in the replay database ({@link #emptyExistingTables}), and commits. A named table that does not
         * exist yet, such as one the scenario's own request creates, is left absent: the reset never creates or drops
         * a table, so a scenario captured from a database without its tables starts without them.
         */
        private void deleteTables() throws Exception {
            Set<String> tables = new LinkedHashSet<>();
            for (String section : List.of("state", "response")) {
                for (Map.Entry<String, JsonNode> table : entries(fixture.json().path(section).get("tables"),
                        at(section + ".tables"))) {
                    tables.add(identifier(table.getKey()));
                }
            }
            if (tables.isEmpty()) {
                return;
            }
            DataSource dataSource = context.getBeanProvider(DataSource.class).getIfAvailable();
            if (dataSource == null) {
                return;
            }
            try (Connection connection = dataSource.getConnection()) {
                confirmReplayDatabase(connection);
                emptyExistingTables(connection, tables);
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        }

        /**
         * Runs {@code DELETE FROM <table>} for each of {@code tables}, in order, that exists in the connection's
         * current catalog and schema ({@link #tableExists}), and returns the tables it emptied, in that order. A table
         * that does not exist is skipped and stays absent; no table is created or dropped. The caller confirms that
         * the connection reaches a database the replay owns before calling it, and commits afterwards.
         *
         * @throws IllegalStateException when a name does not match {@code [A-Za-z0-9_]+}, before any statement runs
         */
        static List<String> emptyExistingTables(Connection connection, Collection<String> tables) throws SQLException {
            List<String> names = new ArrayList<>();
            for (String table : tables) {
                names.add(identifier(table));
            }
            List<String> emptied = new ArrayList<>();
            try (Statement statement = connection.createStatement()) {
                for (String table : names) {
                    if (tableExists(connection, table)) {
                        statement.executeUpdate("DELETE FROM " + table);
                        emptied.add(table);
                    }
                }
            }
            return emptied;
        }

        /**
         * Fails with an {@link IllegalStateException} naming {@code what} unless {@code table} exists in the
         * connection's current catalog and schema ({@link #tableExists}). {@code state.tables} seeds rows into tables
         * the application creates, and the replay cannot create one, so a seeded table that does not exist is reported
         * as a fixture the scenario cannot start from, never as the database's error on a statement.
         */
        static void requireTable(Connection connection, String table, String what) throws SQLException {
            if (!tableExists(connection, table)) {
                throw new IllegalStateException(what + " seeds rows into table " + table + ", which does not exist in"
                        + " the replay database; state.tables seeds only tables the application has created, since the"
                        + " replay creates no table, so " + table + " must exist before the scenario starts, created"
                        + " when the application starts or by an earlier scenario in table order");
            }
        }

        /**
         * Whether {@code table}, an unquoted identifier, names a table in the connection's current catalog and schema,
         * as an unquoted {@code DELETE FROM}, {@code INSERT INTO} or {@code SELECT * FROM} of it resolves there. The
         * name is looked up in the case the database stores unquoted identifiers in, upper or lower case, otherwise
         * as given; {@code _} and {@code %} are escaped with the driver's search-string escape
         * ({@link #searchPattern}), and only a result whose {@code TABLE_NAME}, and {@code TABLE_SCHEM} when the
         * connection reports a schema, equals the name exactly counts, so a driver without an escape string matches
         * no other table either. A current catalog or schema the driver reports as {@code null} does not narrow the
         * search ({@link #currentSchema}). Every table type counts, since drivers name base tables differently
         * ({@code TABLE}, {@code BASE TABLE}).
         */
        static boolean tableExists(Connection connection, String table) throws SQLException {
            DatabaseMetaData metaData = connection.getMetaData();
            String stored = metaData.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT)
                    : metaData.storesLowerCaseIdentifiers() ? table.toLowerCase(Locale.ROOT) : table;
            String schema = currentSchema(connection, metaData);
            String escape = metaData.getSearchStringEscape();
            try (ResultSet found = metaData.getTables(connection.getCatalog(), searchPattern(schema, escape),
                    searchPattern(stored, escape), null)) {
                while (found.next()) {
                    if (stored.equals(found.getString("TABLE_NAME"))
                            && (schema == null || schema.equals(found.getString("TABLE_SCHEM")))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The connection's current schema, or {@code null} when the driver reports none. A connection wrapper that
         * does not implement {@link Connection#getSchema()}, such as Narayana's JTA connection, which throws an
         * {@link SQLException} without a message for it, is answered by the database connection its metadata comes
         * from, the same session; when that one cannot report it either, the schema is {@code null} and the search is
         * not narrowed by schema.
         */
        private static String currentSchema(Connection connection, DatabaseMetaData metaData) throws SQLException {
            try {
                return connection.getSchema();
            } catch (SQLException unsupported) {
                Connection database = metaData.getConnection();
                if (database == null || database == connection) {
                    return null;
                }
                try {
                    return database.getSchema();
                } catch (SQLException alsoUnsupported) {
                    return null;
                }
            }
        }

        /**
         * {@code name} as a {@link DatabaseMetaData} search pattern that matches only itself: each {@code _} and
         * {@code %}, and each occurrence of {@code escape} itself, is preceded by {@code escape}. A {@code null} name
         * stays {@code null}, and without an escape string, which some drivers report, the name is returned unchanged
         * and the caller's exact match on the results excludes what its wildcards also match.
         */
        static String searchPattern(String name, String escape) {
            if (name == null || escape == null || escape.isEmpty()) {
                return name;
            }
            StringBuilder pattern = new StringBuilder(name.length() + 8);
            int index = 0;
            while (index < name.length()) {
                if (name.startsWith(escape, index)) {
                    pattern.append(escape).append(escape);
                    index += escape.length();
                } else {
                    char character = name.charAt(index);
                    if (character == '_' || character == '%') {
                        pattern.append(escape);
                    }
                    pattern.append(character);
                    index++;
                }
            }
            return pattern.toString();
        }

        /**
         * Fails the scenario before any statement runs unless the application's DataSource is connected to a
         * database the replay owns ({@link #replayOwnedDatabase}): an in-memory one, an embedded file database
         * inside the replay's temporary root, or a server on a replay-owned loopback port. So table state is never
         * seeded, emptied or read in another database, a pre-existing file database outside the root included.
         */
        private void confirmReplayDatabase(Connection connection) throws SQLException, IOException {
            String url = connection.getMetaData().getURL();
            Path root = tempRoot();
            if (!replayOwnedDatabase(url, ownedPorts(), root)) {
                throw new IllegalStateException(at("the application's DataSource is connected to " + redact(null, url)
                        + ", which is not a database the replay owns; table state is seeded, emptied and read only in"
                        + " an in-memory database, an embedded file database inside the replay root " + root
                        + " or a server on a replay-owned port, so override its URL through state.properties (for"
                        + " example jdbc:h2:mem:<name>)"));
            }
        }

        /**
         * Fails the scenario before any collection is dropped, seeded or read unless every
         * {@code com.mongodb.client.MongoClient} bean of the context, the client the application's MongoTemplate
         * works through, is configured only for servers the replay owns ({@link #replayOwnedStore}): the embedded
         * MongoDB server on its loopback port. A context with a MongoTemplate but without that client type or without
         * a client bean is refused too, since the store the template writes to cannot be confirmed. The driver is
         * reached by reflection; reading its cluster settings opens no connection.
         */
        private void confirmReplayStore() throws Exception {
            String remedy = "; collection state is dropped, seeded and read only in the embedded MongoDB server on a"
                    + " replay-owned loopback port, so override spring.data.mongodb.uri through state.properties with"
                    + " ${mongo.uri}";
            Class<?> clientType = Reflect.optionalClass(MONGO_SYNC_CLIENT_CLASS);
            if (clientType == null) {
                throw new IllegalStateException(at("the application has a MongoTemplate but the test classpath lacks "
                        + MONGO_SYNC_CLIENT_CLASS + ", so the store it reaches cannot be confirmed" + remedy));
            }
            List<?> clients = context.getBeanProvider(clientType).orderedStream().toList();
            if (clients.isEmpty()) {
                throw new IllegalStateException(at("the application has a MongoTemplate but no "
                        + MONGO_SYNC_CLIENT_CLASS + " bean, so the store it reaches cannot be confirmed" + remedy));
            }
            Set<Integer> owned = ownedPorts();
            for (Object client : clients) {
                Object settings = Reflect.call(Reflect.call(client, "getClusterDescription"), "getClusterSettings");
                List<String> hosts = new ArrayList<>();
                for (Object server : (List<?>) Reflect.call(settings, "getHosts")) {
                    hosts.add(endpoint(new Address((String) Reflect.call(server, "getHost"),
                            (Integer) Reflect.call(server, "getPort"))));
                }
                Object srvHost = Reflect.hasMethod(settings.getClass(), "getSrvHost", 0)
                        ? Reflect.call(settings, "getSrvHost") : null;
                if (srvHost != null || !replayOwnedStore(hosts, owned)) {
                    String target = srvHost != null ? "the DNS seed list of " + srvHost : String.join(",", hosts);
                    throw new IllegalStateException(at("the application's MongoDB client is configured for "
                            + redact(null, target) + ", which is not a store the replay owns" + remedy));
                }
            }
        }

        private void seedCollections() throws Exception {
            Object template = requireBean(requireClass(MONGO_TEMPLATE_CLASS), MONGO_TEMPLATE_CLASS);
            Class<?> document = requireClass(BSON_DOCUMENT_CLASS);
            confirmReplayStore();
            for (Map.Entry<String, JsonNode> collection : entries(state.get("collections"), at("state.collections"))) {
                Reflect.call(template, "dropCollection", collection.getKey());
                for (JsonNode stored : elements(collection.getValue(), at("state.collections." + collection.getKey()))) {
                    Reflect.call(template, "insert", Reflect.callStatic(document, "parse", stored.toString()),
                            collection.getKey());
                }
            }
        }

        private void seedLdap() throws Exception {
            String ldif = text(state.path("ldap"), "ldif", at("state.ldap.ldif"));
            Reflect.call(ldapServer, "importFromLDIF", true, ldifFile(ldif).toString());
        }

        /** {@code ldif} names a classpath resource when one exists; otherwise it is the LDIF text itself. */
        private Path ldifFile(String ldif) throws IOException {
            URL resource = ldif.contains("\n") ? null : Reflect.loader().getResource(stripLeadingSlash(ldif));
            if (resource != null && "file".equals(resource.getProtocol())) {
                try {
                    return Path.of(resource.toURI());
                } catch (URISyntaxException e) {
                    throw new IllegalStateException(at("state.ldap.ldif resource " + resource + " has no file path"), e);
                }
            }
            Path file = Files.createTempFile(tempRoot(), name + "-", ".ldif");
            if (resource != null) {
                try (InputStream in = resource.openStream()) {
                    Files.write(file, in.readAllBytes());
                }
            } else {
                Files.writeString(file, ldif, StandardCharsets.UTF_8);
            }
            return file;
        }

        // ----- upstream recording ----------------------------------------------------------------------------

        /** Serves the recording in order: stub {@code i} answers only in scenario state {@code step-<i>}. */
        private void registerUpstream() {
            List<JsonNode> exchanges = elements(fixture.json().get("upstream"), at("upstream"));
            for (int i = 0; i < exchanges.size(); i++) {
                String what = at("upstream[" + i + "]");
                JsonNode recordedRequest = object(exchanges.get(i).get("request"), what + ".request");
                JsonNode recordedResponse = object(exchanges.get(i).get("response"), what + ".response");
                MappingBuilder mapping = WireMock.request(text(recordedRequest, "method", what + ".request.method"),
                                WireMock.urlEqualTo(relativeUrl(text(recordedRequest, "url", what + ".request.url"))))
                        .inScenario(name)
                        .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "step-" + i)
                        .willSetStateTo("step-" + (i + 1));
                if (row.deterministic()) {
                    byte[] body = bodyBytes(recordedRequest, what + ".request");
                    // WireMock's equalTo("") never matches a request without a body, so empty bodies match as bytes.
                    mapping = recordedRequest.hasNonNull("bodyBase64") || body.length == 0
                            ? mapping.withRequestBody(WireMock.binaryEqualTo(body))
                            : mapping.withRequestBody(WireMock.equalTo(new String(body, StandardCharsets.UTF_8)));
                }
                ResponseDefinitionBuilder reply = WireMock.aResponse()
                        .withStatus(intValue(recordedResponse, "status", what + ".response.status"))
                        .withBody(bodyBytes(recordedResponse, what + ".response"));
                List<HttpHeader> headers = new ArrayList<>();
                groupByName(headerPairs(recordedResponse.get("headers"), what + ".response.headers"))
                        .forEach((header, values) -> {
                            if (!FRAMING_HEADERS.contains(lower(header))) {
                                headers.add(new HttpHeader(header, values));
                            }
                        });
                if (!headers.isEmpty()) {
                    reply = reply.withHeaders(new HttpHeaders(headers));
                }
                wireMock.stubFor(mapping.willReturn(reply));
            }
        }

        private void verifyUpstream() throws Exception {
            if (wireMock == null) {
                // load() starts WireMock for every loaded fixture; without it no outbound call could be checked.
                throw new IllegalStateException(at("WireMock is not running, so the outbound calls cannot be checked"));
            }
            List<JsonNode> exchanges = elements(fixture.json().get("upstream"), at("upstream"));
            if (!exchanges.isEmpty()) {
                awaitMatch("upstream calls", () -> {
                    int made = wireMock.getAllServeEvents().size();
                    if (made < exchanges.size()) {
                        throw new AssertionError(name + ": " + made + " of the " + exchanges.size()
                                + " recorded upstream calls were made");
                    }
                });
            }
            List<LoggedRequest> unmatched = wireMock.findAllUnmatchedRequests();
            if (!unmatched.isEmpty()) {
                throw new AssertionError(name + ": upstream calls matched no recorded exchange: "
                        + describeRequests(unmatched));
            }
            List<ServeEvent> events = new ArrayList<>(wireMock.getAllServeEvents());
            Collections.reverse(events);
            List<LoggedRequest> calls = new ArrayList<>();
            events.forEach(event -> calls.add(event.getRequest()));
            if (calls.size() != exchanges.size()) {
                throw new AssertionError(name + ": " + exchanges.size() + " upstream calls were recorded but "
                        + calls.size() + " were made: " + describeRequests(calls));
            }
            List<Map.Entry<String, String>> inbound = headerPairs(request.get("headers"), at("request.headers"));
            for (int i = 0; i < calls.size(); i++) {
                String what = "upstream call " + (i + 1);
                JsonNode recorded = exchanges.get(i).get("request");
                LoggedRequest actual = calls.get(i);
                String method = text(recorded, "method", at("upstream[" + i + "].request.method"));
                String url = relativeUrl(text(recorded, "url", at("upstream[" + i + "].request.url")));
                if (!method.equals(actual.getMethod().value()) || !url.equals(actual.getUrl())) {
                    throw new AssertionError(name + ": " + what + " was recorded as " + method + " " + url
                            + " but was " + actual.getMethod().value() + " " + actual.getUrl());
                }
                if (row.deterministic()) {
                    compareContent(what + " body", bodyBytes(recorded, at("upstream[" + i + "].request")),
                            actual.getBody(), null);
                }
                List<Map.Entry<String, String>> recordedHeaders =
                        headerPairs(recorded.get("headers"), at("upstream[" + i + "].request.headers"));
                Set<String> passedOn = passedOnClientHeaders(inbound, recordedHeaders);
                List<String> expectedHeaders = comparableHeaders(recordedHeaders, passedOn);
                List<String> actualHeaders = comparableHeaders(loggedHeaders(actual), passedOn);
                if (!expectedHeaders.equals(actualHeaders)) {
                    throw new AssertionError(name + ": " + what + " headers differ\n  expected: " + expectedHeaders
                            + "\n  actual:   " + actualHeaders);
                }
            }
        }

        /** User-Agent and Accept-Encoding values the caller sent and the recording forwarded unchanged. */
        private static Set<String> passedOnClientHeaders(List<Map.Entry<String, String>> inbound,
                List<Map.Entry<String, String>> recorded) {
            Set<String> passedOn = new HashSet<>();
            for (Map.Entry<String, String> header : recorded) {
                String headerName = lower(header.getKey());
                if (CLIENT_LIBRARY_HEADERS.contains(headerName) && inbound.stream().anyMatch(sent ->
                        lower(sent.getKey()).equals(headerName) && sent.getValue().equals(header.getValue()))) {
                    passedOn.add(headerName);
                }
            }
            return passedOn;
        }

        private static List<String> comparableHeaders(List<Map.Entry<String, String>> headers, Set<String> passedOn) {
            List<String> comparable = new ArrayList<>();
            for (Map.Entry<String, String> header : headers) {
                String headerName = lower(header.getKey());
                if (TRANSPORT_HEADERS.contains(headerName)
                        || (CLIENT_LIBRARY_HEADERS.contains(headerName) && !passedOn.contains(headerName))) {
                    continue;
                }
                comparable.add(headerName + ": " + header.getValue());
            }
            Collections.sort(comparable);
            return comparable;
        }

        private static List<Map.Entry<String, String>> loggedHeaders(LoggedRequest request) {
            List<Map.Entry<String, String>> pairs = new ArrayList<>();
            if (request.getHeaders() != null) {
                for (HttpHeader header : request.getHeaders().all()) {
                    for (String value : header.values()) {
                        pairs.add(Map.entry(header.key(), value));
                    }
                }
            }
            return pairs;
        }

        private static String describeRequests(List<LoggedRequest> requests) {
            List<String> described = new ArrayList<>();
            requests.forEach(logged -> described.add(logged.getMethod().value() + " " + logged.getUrl()));
            return described.toString();
        }


        // ----- triggers --------------------------------------------------------------------------------------

        /** A reply ST-1 read, with the response type its {@code send} declares and the replay port it was sent to. */
        private record HttpExchange(Class<?> responseType, Object reply, int port) {
        }

        private void replayHttp() throws Exception {
            HttpExchange exchange = sendHttp(request, at("request"));
            compareHttpResponse(exchange.responseType(), exchange.reply(), exchange.port());
        }

        /**
         * Sends the prior requests of {@code state.requests} in order, as the capture sent them before the scenario's
         * own; their replies are discarded, as the capture discarded them, so any status is accepted. A request that
         * cannot be sent, or whose reply cannot be read, fails the scenario, whose starting state was not reached.
         * Their log events are then dropped, since {@code response.logs} holds the events from the input on.
         */
        private void sendPriorRequests() throws Exception {
            for (int i = 0; i < priorRequests.size(); i++) {
                String what = at("state.requests[" + i + "]");
                try {
                    sendHttp(priorRequests.get(i), what);
                } catch (UncheckedIOException e) {
                    throw new IllegalStateException(what + " failed, so the scenario's starting state was not"
                            + " reached: " + e.getMessage(), e);
                }
            }
            if (!priorRequests.isEmpty()) {
                clearAppender();
            }
        }

        /**
         * Sends one request-shaped object, the scenario's {@code request} or a prior request, through ST-1 to the
         * listener its {@code portProperty} names, in TLS mode when it carries {@code tls} (presenting the client key
         * of {@code tls.keyStore} when it names one) and with its {@code readTimeoutMillis} as the socket read budget
         * when it carries one, and returns the complete reply. A project without ST-1, without the listener port, for
         * a client key store without ST-1's key-store overload of {@code HttpTestClient.https}
         * ({@link #presentsClientKeys}) or, for a read budget, without {@code HttpTestClient.withReadTimeout} reports
         * the scenario {@code UNVERIFIED-missing-driver}.
         *
         * @param what the object's location in the fixture, such as {@code <scenario>: request}
         */
        private HttpExchange sendHttp(JsonNode spec, String what) throws Exception {
            Class<?> clientClass = requireClass(CLIENT_CLASS);
            String portProperty = spec.hasNonNull("portProperty")
                    ? text(spec, "portProperty", what + ".portProperty") : DEFAULT_PORT_PROPERTY;
            Integer port = context.getEnvironment().getProperty(portProperty, Integer.class);
            if (port == null) {
                return missingDriver("the listener port property " + portProperty);
            }
            JsonNode tls = tls(spec, what);
            if (tls != null && tls.hasNonNull("keyStore") && !presentsClientKeys(clientClass)) {
                return missingDriver("ST-1 " + CLIENT_CLASS + "." + CLIENT_KEY_OVERLOAD + " for " + what
                        + ".tls.keyStore");
            }
            Object client = tls != null
                    ? httpsClient(clientClass, port, tls, what + ".tls")
                    : Reflect.callStatic(clientClass, "localhost", port);
            Integer readTimeout = readTimeoutMillis(spec, what);
            if (readTimeout != null) {
                if (!Reflect.hasMethod(clientClass, "withReadTimeout", 1)) {
                    return missingDriver("ST-1 " + CLIENT_CLASS + ".withReadTimeout(int) for "
                            + what + ".readTimeoutMillis");
                }
                client = Reflect.call(client, "withReadTimeout", readTimeout);
            }
            String method = text(spec, "method", what + ".method");
            String path = text(spec, "path", what + ".path");
            String query = spec.hasNonNull("query") ? text(spec, "query", what + ".query") : "";
            String target = query.isEmpty() ? path : path + "?" + query;
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            boolean framed = false;
            for (Map.Entry<String, String> header : headerPairs(spec.get("headers"), what + ".headers")) {
                String headerName = lower(header.getKey());
                framed |= headerName.equals("content-length") || headerName.equals("transfer-encoding");
                if (!TRANSPORT_HEADERS.contains(headerName)) {
                    headers.add(header);
                }
            }
            byte[] body = bodyBytes(spec, what);
            // An empty body goes out without Content-Length unless the captured request framed one, as curl did.
            Method send = Reflect.method(clientClass, "send", 4);
            Object reply = send(clientClass, client, method, target, headers, body.length == 0 && !framed ? null : body);
            return new HttpExchange(send.getReturnType(), reply, port);
        }

        /** Sends the recorded header lines as recorded through ST-1; a client without header pairs gets them joined. */
        private static Object send(Class<?> clientClass, Object client, String method, String target,
                List<Map.Entry<String, String>> headers, byte[] body) throws Exception {
            if (Reflect.hasMethod(clientClass, "sendHeaderPairs", 4)) {
                return Reflect.call(client, "sendHeaderPairs", method, target, headers, body);
            }
            Map<String, String> joined = new LinkedHashMap<>();
            for (Map.Entry<String, String> header : headers) {
                String key = joined.keySet().stream()
                        .filter(existing -> existing.equalsIgnoreCase(header.getKey()))
                        .findFirst()
                        .orElse(header.getKey());
                joined.merge(key, header.getValue(), (first, second) -> first + ", " + second);
            }
            return Reflect.call(client, "send", method, target, joined, body);
        }

        /** ST-1's overload of {@code https} that presents a client key, as missing-driver reports name it. */
        private static final String CLIENT_KEY_OVERLOAD = "https(String host, int port, String trustStorePath,"
                + " String trustStoreType, String trustStorePassword, String keyStorePath, String keyStoreType,"
                + " String keyStorePassword)";

        /**
         * Whether ST-1 {@code clientClass} has the public static key-store overload of {@code https}, the only way
         * it presents the client key of a {@code tls.keyStore}; a copy without it replays only a {@code tls} that
         * names no key store.
         */
        static boolean presentsClientKeys(Class<?> clientClass) {
            try {
                return Modifier.isStatic(clientClass.getMethod("https", String.class, int.class, String.class,
                        String.class, String.class, String.class, String.class, String.class).getModifiers());
            } catch (NoSuchMethodException absent) {
                return false;
            }
        }

        /**
         * ST-1 {@code clientClass}'s TLS client for {@code localhost:port}, built from a {@code tls} that {@link #tls}
         * accepted. It trusts only {@code trustStore}. When {@code keyStore} is named, it is ST-1's key-store
         * overload of {@code https}, which {@link #sendHttp} has checked for, presenting that store's client key to a
         * listener that requires a client certificate: the key store has the type {@code keyStoreType}, else the
         * trust store's, and is opened, its key entries included, with {@code keyStorePassword}, or with none when
         * that is absent. Without {@code keyStore} it is ST-1's trust-store overload, which presents no client
         * certificate.
         *
         * @param what the location of {@code tls} in the fixture, such as {@code <scenario>: request.tls}
         */
        static Object httpsClient(Class<?> clientClass, int port, JsonNode tls, String what) throws Exception {
            String trustStore = storePath(text(tls, "trustStore", what + ".trustStore"), what + ".trustStore");
            String type = tls.hasNonNull("type") ? text(tls, "type", what + ".type") : "JKS";
            String password = tls.hasNonNull("password") ? text(tls, "password", what + ".password") : null;
            if (!tls.hasNonNull("keyStore")) {
                return Reflect.callStatic(clientClass, "https", "localhost", port, trustStore, type, password);
            }
            String keyStore = storePath(text(tls, "keyStore", what + ".keyStore"), what + ".keyStore");
            String keyStoreType = tls.hasNonNull("keyStoreType")
                    ? text(tls, "keyStoreType", what + ".keyStoreType") : type;
            String keyStorePassword = tls.hasNonNull("keyStorePassword")
                    ? text(tls, "keyStorePassword", what + ".keyStorePassword") : null;
            return Reflect.callStatic(clientClass, "https", "localhost", port, trustStore, type, password, keyStore,
                    keyStoreType, keyStorePassword);
        }

        /**
         * A store a {@code tls} member names, resolved from the classpath first, then as a file, in the form ST-1
         * opens.
         *
         * @param what the member's location in the fixture, such as {@code <scenario>: request.tls.keyStore}
         */
        private static String storePath(String store, String what) throws URISyntaxException {
            URL resource = Reflect.loader().getResource(stripLeadingSlash(store));
            if (resource != null) {
                // ST-1 tries a file before the classpath, so a classpath store is handed over as its file path,
                // or by resource name when it sits inside a jar.
                return "file".equals(resource.getProtocol()) ? Path.of(resource.toURI()).toString()
                        : stripLeadingSlash(store);
            }
            Path file = Path.of(store);
            if (Files.isRegularFile(file)) {
                return file.toAbsolutePath().toString();
            }
            throw new IllegalStateException(what + " " + store + " is neither on the test classpath nor a file");
        }

        private void compareHttpResponse(Class<?> responseType, Object reply, int replayPort) throws Exception {
            int status = (Integer) Reflect.callOn(responseType, reply, "status");
            String reason = (String) Reflect.callOn(responseType, reply, "reason");
            byte[] body = (byte[]) Reflect.callOn(responseType, reply, "body");
            int expectedStatus = intValue(response, "status", at("response.status"));
            String expectedReason = text(response, "reason", at("response.reason"));
            if (status != expectedStatus || !expectedReason.equals(reason)) {
                throw new AssertionError(name + ": status line expected " + expectedStatus + " " + quote(expectedReason)
                        + " but was " + status + " " + quote(reason) + "\n  body: " + Content.render(body));
            }
            String expectedContentType = expectedHeader("Content-Type");
            compareHeader(responseType, reply, "Content-Type", expectedContentType);
            String expectedLocation = expectedHeader("Location");
            JsonNode capturedPort = request.path("port");
            if (expectedLocation != null && capturedPort.canConvertToInt()) {
                // The only substitution: the captured listener port becomes the replay port.
                expectedLocation = Pattern.compile(":" + capturedPort.intValue() + "(?![0-9])")
                        .matcher(expectedLocation)
                        .replaceAll(Matcher.quoteReplacement(":" + replayPort));
            }
            compareHeader(responseType, reply, "Location", expectedLocation);
            List<String> actualContentTypes = headerValues(responseType, reply, "Content-Type");
            String contentType = expectedContentType != null ? expectedContentType
                    : actualContentTypes.isEmpty() ? null : actualContentTypes.get(0);
            compareContent("response body", bodyBytes(response, at("response")), body, contentType);
        }

        /** An absent or null captured value means the header must be absent; otherwise exactly one equal value. */
        private void compareHeader(Class<?> responseType, Object reply, String header, String expected)
                throws Exception {
            List<String> actual = headerValues(responseType, reply, header);
            List<String> wanted = expected == null ? List.of() : List.of(expected);
            if (!actual.equals(wanted)) {
                throw new AssertionError(name + ": response header " + header
                        + (expected == null ? " must be absent" : " expected " + quote(expected))
                        + " but was " + (actual.isEmpty() ? "absent" : actual.toString()));
            }
        }

        private static List<String> headerValues(Class<?> responseType, Object reply, String header) throws Exception {
            List<String> values = new ArrayList<>();
            for (Object value : (List<?>) Reflect.callOn(responseType, reply, "headers", header)) {
                values.add(String.valueOf(value));
            }
            return values;
        }

        private String expectedHeader(String header) {
            for (Map.Entry<String, JsonNode> entry : entries(response.get("headers"), at("response.headers"))) {
                if (entry.getKey().equalsIgnoreCase(header)) {
                    JsonNode value = entry.getValue();
                    if (value == null || value.isNull()) {
                        return null;
                    }
                    if (!value.isTextual()) {
                        throw new IllegalStateException(at("response.headers." + entry.getKey()
                                + " must be a string or null"));
                    }
                    return value.textValue();
                }
            }
            return null;
        }

        private void replayFile() throws Exception {
            JsonNode file = request.get("file");
            if (file != null && !file.isNull()) {
                String what = at("request.file");
                object(file, what);
                Path directory = resolveDirectory(text(file, "directory", what + ".directory"), name);
                Files.createDirectories(directory);
                writeFile(directory, file, what);
            }
            invokeIfPresent();
        }

        /** Calls the trigger's single-run method, such as a poller's {@code pollOnce()}, when the fixture names one. */
        private void invokeIfPresent() throws Exception {
            JsonNode invoke = request.get("invoke");
            if (invoke == null || invoke.isNull()) {
                return;
            }
            String what = at("request.invoke");
            object(invoke, what);
            Object target;
            if (invoke.hasNonNull("bean")) {
                target = context.getBean(text(invoke, "bean", what + ".bean"));
            } else if (invoke.hasNonNull("type")) {
                target = context.getBean(projectClass(text(invoke, "type", what + ".type")));
            } else {
                throw new IllegalStateException(what + " names neither 'bean' nor 'type'");
            }
            Reflect.call(target, text(invoke, "method", what + ".method"));
        }

        /** A class name, fully qualified or relative to this test's package. */
        private Class<?> projectClass(String type) {
            Class<?> resolved = Reflect.optionalClass(type);
            if (resolved == null) {
                resolved = Reflect.optionalClass(FixtureParityTest.class.getPackageName() + "." + type);
            }
            if (resolved == null) {
                throw new IllegalStateException(at("request.invoke.type " + type + " names no class"));
            }
            return resolved;
        }

        private void replayJms() throws Exception {
            JsonNode message = request.get("message");
            if (message != null && !message.isNull()) {
                String what = at("request.message");
                object(message, what);
                String destinationName = text(message, "destination", what + ".destination");
                boolean pubSub = message.path("pubSub").asBoolean(false);
                Object destination = Reflect.call(jmsSession, pubSub ? "createTopic" : "createQueue", destinationName);
                if (!pubSub) {
                    jmsRequestQueues.add(destinationName);
                }
                Object jmsMessage;
                if (message.hasNonNull("bodyBase64")) {
                    jmsMessage = Reflect.call(jmsSession, "createBytesMessage");
                    Reflect.call(jmsMessage, "writeBytes", bodyBytes(message, what));
                } else {
                    jmsMessage = Reflect.call(jmsSession, "createTextMessage",
                            new String(bodyBytes(message, what), StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, JsonNode> property : entries(message.get("properties"), what + ".properties")) {
                    Reflect.call(jmsMessage, "setObjectProperty", property.getKey(),
                            scalar(property.getValue(), what + ".properties." + property.getKey()));
                }
                Object producer = Reflect.call(jmsSession, "createProducer", destination);
                try {
                    Reflect.call(producer, "send", jmsMessage);
                } finally {
                    Reflect.call(producer, "close");
                }
            }
            invokeIfPresent();
        }

        private void replayAmqp() throws Exception {
            JsonNode message = request.get("message");
            if (message != null && !message.isNull()) {
                String what = at("request.message");
                object(message, what);
                Object messageProperties = Reflect.construct(requireClass(AMQP_PROPERTIES_CLASS));
                for (Map.Entry<String, JsonNode> property : entries(message.get("properties"), what + ".properties")) {
                    Reflect.call(messageProperties, "setHeader", property.getKey(),
                            scalar(property.getValue(), what + ".properties." + property.getKey()));
                }
                Object amqpMessage = Reflect.construct(requireClass(AMQP_MESSAGE_CLASS), bodyBytes(message, what),
                        messageProperties);
                String exchange = message.hasNonNull("exchange") ? text(message, "exchange", what + ".exchange") : "";
                String routingKey =
                        message.hasNonNull("routingKey") ? text(message, "routingKey", what + ".routingKey") : "";
                Reflect.call(rabbitTemplate, "send", exchange, routingKey, amqpMessage);
            }
            invokeIfPresent();
        }

        private void replayMail() throws Exception {
            String what = at("request.mail");
            JsonNode mail = object(request.get("mail"), what);
            String user = text(mail, "user", what + ".user");
            Object message = parseMime(base64(text(mail, "mimeBase64", what + ".mimeBase64"), what + ".mimeBase64"));
            Object mailbox = Reflect.call(Reflect.call(greenMail, "getUserManager"), "getUserByEmail", user);
            if (mailbox == null) {
                throw new IllegalStateException(what + ".user " + user + " is not a mailbox of state.mail.users");
            }
            Reflect.call(mailbox, "deliver", message);
            deliveredMails.add(new DeliveredMail(user, MailView.of(message)));
            invokeIfPresent();
        }

        // ----- messages --------------------------------------------------------------------------------------

        /**
         * Opens the broker side before the trigger runs, for every destination of {@code response.destinations},
         * those the capture found empty included, so only messages the scenario produces are compared: a topic gets
         * a subscriber kept until the reset, and a queue is drained, a JMS queue through a consumer closed again at
         * once, so no replay consumer competes with the application for a queue it consumes. HTTP- or file-triggered
         * flows that publish use the project's RabbitTemplate when it has one, otherwise its JMS connection factory;
         * {@code state.jms} needs the JMS broker and {@code state.amqp.responders} the AMQP one.
         */
        private void prepareMessages() throws Exception {
            inventory = destinations();
            boolean denials = state.has("jms");
            boolean responders = state.path("amqp").has("responders");
            switch (row.trigger()) {
                case "amqp" -> broker = Broker.AMQP;
                case "jms", "mq" -> broker = Broker.JMS;
                default -> {
                    if (!response.has("destinations") && !denials && !responders) {
                        return;
                    }
                    if (denials || responders) {
                        broker = denials ? Broker.JMS : Broker.AMQP;
                    } else {
                        Class<?> rabbit = Reflect.optionalClass(RABBIT_TEMPLATE_CLASS);
                        broker = rabbit != null && context.getBeanProvider(rabbit).getIfAvailable() != null
                                ? Broker.AMQP : Broker.JMS;
                    }
                }
            }
            if (denials && broker != Broker.JMS) {
                throw new IllegalStateException(at("state.jms refuses sends on a JMS broker, but this scenario"
                        + " replays on AMQP"));
            }
            if (responders && broker != Broker.AMQP) {
                throw new IllegalStateException(at("state.amqp.responders answer on an AMQP broker, but this scenario"
                        + " replays on JMS"));
            }
            if (broker == Broker.AMQP) {
                rabbitTemplate = requireBean(requireClass(RABBIT_TEMPLATE_CLASS), RABBIT_TEMPLATE_CLASS);
                for (BrokerDestination destination : inventory) {
                    if (destination.pubSub()) {
                        throw new IllegalStateException(at("response.destinations lists " + destination
                                + ", but an AMQP broker is read through queues only, so its pubSub must be false"));
                    }
                    amqpQueues.add(destination.name());
                }
                for (String queue : amqpQueues) {
                    drainAmqp(queue);
                }
            } else {
                Object factory = brokerFactory(
                        requireBean(requireClass(CONNECTION_FACTORY_CLASS), CONNECTION_FACTORY_CLASS));
                jmsConnection = Reflect.call(factory, "createConnection");
                jmsSession = Reflect.call(jmsConnection, "createSession", false, JMS_AUTO_ACKNOWLEDGE);
                Reflect.call(jmsConnection, "start");
                for (BrokerDestination destination : inventory) {
                    if (destination.pubSub()) {
                        topicSubscribers.put(destination, Reflect.call(jmsSession, "createConsumer",
                                Reflect.call(jmsSession, "createTopic", destination.name())));
                    } else {
                        jmsQueues.add(destination.name());
                        drainJmsQueue(destination.name());
                    }
                }
            }
        }

        /**
         * {@code response.destinations}, checked against {@code response.messages}: each is present exactly when the
         * other is, no destination is listed twice, and every message is on a listed destination. A fixture that
         * breaks one of these rules fails its scenario as malformed.
         */
        private List<BrokerDestination> destinations() {
            boolean messages = response.has("messages");
            if (messages != response.has("destinations")) {
                throw new IllegalStateException(at(messages
                        ? "response.messages needs response.destinations, every destination the capture subscribed"
                                + " to or browsed, those it found empty included"
                        : "response.destinations needs response.messages, [] when no listed destination held a"
                                + " message"));
            }
            List<BrokerDestination> listed = new ArrayList<>();
            List<JsonNode> entries = elements(response.get("destinations"), at("response.destinations"));
            for (int i = 0; i < entries.size(); i++) {
                String what = at("response.destinations[" + i + "]");
                BrokerDestination destination = brokerDestination(entries.get(i), what);
                if (listed.contains(destination)) {
                    throw new IllegalStateException(what + " lists " + destination + " a second time");
                }
                listed.add(destination);
            }
            List<JsonNode> expected = elements(response.get("messages"), at("response.messages"));
            for (int i = 0; i < expected.size(); i++) {
                String what = at("response.messages[" + i + "]");
                BrokerDestination destination = brokerDestination(expected.get(i), what);
                if (!listed.contains(destination)) {
                    throw new IllegalStateException(what + " is on " + destination
                            + ", which response.destinations does not list");
                }
            }
            return List.copyOf(listed);
        }

        /** The {@code destination} and {@code pubSub} of a fixture entry; {@code pubSub} defaults to {@code false}. */
        private static BrokerDestination brokerDestination(JsonNode entry, String what) {
            String destination = text(object(entry, what), "destination", what + ".destination");
            if (destination.isBlank()) {
                throw new IllegalStateException(what + ".destination must not be blank");
            }
            JsonNode pubSub = entry.get("pubSub");
            if (pubSub != null && !pubSub.isNull() && !pubSub.isBoolean()) {
                throw new IllegalStateException(what + ".pubSub must be true or false");
            }
            return new BrokerDestination(destination, pubSub != null && pubSub.booleanValue());
        }

        /**
         * The broker's own factory beneath Spring's caching or delegating wrappers: a consumer of Spring's
         * CachingConnectionFactory stays open after its connection is closed and would keep taking messages that a
         * later scenario expects.
         */
        private static Object brokerFactory(Object factory) throws Exception {
            Object current = factory;
            for (int depth = 0; depth < 8 && Reflect.hasMethod(current.getClass(), "getTargetConnectionFactory", 0);
                    depth++) {
                Object target = Reflect.call(current, "getTargetConnectionFactory");
                if (target == null || target == current) {
                    break;
                }
                current = target;
            }
            return current;
        }

        /** Drains a JMS queue through a consumer that is closed again, so it never competes with the application. */
        private void drainJmsQueue(String queue) throws Exception {
            Object consumer =
                    Reflect.call(jmsSession, "createConsumer", Reflect.call(jmsSession, "createQueue", queue));
            try {
                drainJms(consumer);
            } finally {
                Reflect.call(consumer, "close");
            }
        }

        private static void drainJms(Object consumer) throws Exception {
            Object discarded;
            do {
                discarded = Reflect.call(consumer, "receive", DRAIN_MILLIS);
            } while (discarded != null);
        }

        private void drainAmqp(String queue) throws Exception {
            Object discarded;
            do {
                discarded = Reflect.call(rabbitTemplate, "receive", queue, DRAIN_MILLIS);
            } while (discarded != null);
        }

        private void drainQueues() throws Exception {
            if (rabbitTemplate != null) {
                for (String queue : amqpQueues) {
                    drainAmqp(queue);
                }
            }
            if (jmsConnection != null) {
                try {
                    Set<String> queues = new LinkedHashSet<>(jmsRequestQueues);
                    queues.addAll(jmsQueues);
                    for (String queue : queues) {
                        drainJmsQueue(queue);
                    }
                } finally {
                    Object connection = jmsConnection;
                    jmsConnection = null;
                    jmsSession = null;
                    topicSubscribers.clear();
                    Reflect.call(connection, "close");
                }
            }
        }

        /**
         * Compares every destination of the inventory with its {@code response.messages} entries, in order; a
         * destination without entries must have stayed empty. It runs after every other effect has matched, so an
         * asynchronous flow has finished before an empty destination is judged.
         */
        private void verifyMessages() throws Exception {
            List<JsonNode> messages = elements(response.get("messages"), at("response.messages"));
            for (BrokerDestination destination : inventory) {
                List<Map.Entry<String, JsonNode>> expected = new ArrayList<>();
                for (int i = 0; i < messages.size(); i++) {
                    String what = at("response.messages[" + i + "]");
                    if (destination.equals(brokerDestination(messages.get(i), what))) {
                        expected.add(Map.entry(what, messages.get(i)));
                    }
                }
                if (broker == Broker.AMQP) {
                    verifyAmqpQueue(destination, expected);
                } else if (destination.pubSub()) {
                    verifyTopic(destination, expected);
                } else {
                    verifyJmsQueue(destination, expected);
                }
            }
        }

        /** Receives the topic's expected messages in order, each within {@code awaitMillis}; no further may follow. */
        private void verifyTopic(BrokerDestination topic, List<Map.Entry<String, JsonNode>> expected) throws Exception {
            Object subscriber = topicSubscribers.get(topic);
            for (int i = 0; i < expected.size(); i++) {
                Object received = Reflect.call(subscriber, "receive", awaitMillis);
                if (received == null) {
                    throw new AssertionError(name + ": " + topic + " delivered " + i + " of the " + expected.size()
                            + " messages the original received within " + awaitMillis + " ms\n  expected: "
                            + describeExpected(expected));
                }
                compareJmsMessage("message " + (i + 1) + " on " + topic, expected.get(i), received);
            }
            Object extra = Reflect.call(subscriber, "receive", NO_FURTHER_MESSAGE_MILLIS);
            if (extra != null) {
                throw new AssertionError(name + ": " + topic + " delivered a further message after the "
                        + expected.size() + " the original received: " + Content.render(jmsBody(extra)));
            }
        }

        /**
         * Browses the queue, consuming nothing, until it holds exactly the expected messages in order or
         * {@code awaitMillis} ends, then keeps browsing until it has held exactly them throughout the
         * {@link #NO_FURTHER_MESSAGE_MILLIS} quiet window ({@link #awaitQuietWindow}), so a late message fails.
         */
        private void verifyJmsQueue(BrokerDestination queue, List<Map.Entry<String, JsonNode>> expected)
                throws Exception {
            Action check = () -> {
                List<Object> browsed = browse(queue.name());
                if (browsed.size() != expected.size()) {
                    throw new AssertionError(name + ": " + queue + " holds " + browsed.size()
                            + " messages but the original left " + expected.size() + "\n  expected: "
                            + describeExpected(expected) + "\n  actual:   " + describeJms(browsed));
                }
                for (int i = 0; i < browsed.size(); i++) {
                    compareJmsMessage("message " + (i + 1) + " on " + queue, expected.get(i), browsed.get(i));
                }
            };
            awaitMatch(queue.toString(), check);
            awaitQuietWindow(name + ": " + queue, Duration.ofMillis(NO_FURTHER_MESSAGE_MILLIS), check);
        }

        /**
         * Receives the queue's expected messages in order, each within {@code awaitMillis}; no further one may follow.
         * Spring AMQP offers no browse, so the replay consumes from the queue, which it drains at the reset anyway.
         */
        private void verifyAmqpQueue(BrokerDestination queue, List<Map.Entry<String, JsonNode>> expected)
                throws Exception {
            for (int i = 0; i < expected.size(); i++) {
                Object received = Reflect.call(rabbitTemplate, "receive", queue.name(), awaitMillis);
                if (received == null) {
                    throw new AssertionError(name + ": " + queue + " delivered " + i + " of the " + expected.size()
                            + " messages the original left within " + awaitMillis + " ms\n  expected: "
                            + describeExpected(expected));
                }
                String label = "message " + (i + 1) + " on " + queue;
                Map.Entry<String, JsonNode> message = expected.get(i);
                compareContent(label, bodyBytes(message.getValue(), message.getKey()),
                        (byte[]) Reflect.call(received, "getBody"), null);
                for (Map.Entry<String, JsonNode> property : entries(message.getValue().get("properties"),
                        message.getKey() + ".properties")) {
                    compareProperty(label, property, amqpProperty(received, property.getKey()));
                }
            }
            Object extra = Reflect.call(rabbitTemplate, "receive", queue.name(), NO_FURTHER_MESSAGE_MILLIS);
            if (extra != null) {
                throw new AssertionError(name + ": " + queue + " held a further message after the " + expected.size()
                        + " the original left: " + Content.render((byte[]) Reflect.call(extra, "getBody")));
            }
        }

        /** The messages on a JMS queue in queue order, read through a {@code QueueBrowser}, which consumes none. */
        private List<Object> browse(String queue) throws Exception {
            Object browser = Reflect.call(jmsSession, "createBrowser", Reflect.call(jmsSession, "createQueue", queue));
            try {
                List<Object> browsed = new ArrayList<>();
                Enumeration<?> messages = (Enumeration<?>) Reflect.call(browser, "getEnumeration");
                while (messages.hasMoreElements()) {
                    browsed.add(messages.nextElement());
                }
                return browsed;
            } finally {
                Reflect.call(browser, "close");
            }
        }

        private void compareJmsMessage(String label, Map.Entry<String, JsonNode> expected, Object actual)
                throws Exception {
            compareContent(label, bodyBytes(expected.getValue(), expected.getKey()), jmsBody(actual), null);
            for (Map.Entry<String, JsonNode> property : entries(expected.getValue().get("properties"),
                    expected.getKey() + ".properties")) {
                compareProperty(label, property, Reflect.call(actual, "getObjectProperty", property.getKey()));
            }
        }

        private void compareProperty(String label, Map.Entry<String, JsonNode> property, Object actual) {
            if (!scalarEquals(property.getValue(), actual)) {
                throw new AssertionError(name + ": " + label + " property " + property.getKey() + " expected "
                        + property.getValue() + " but was " + (actual == null ? "absent" : actual));
            }
        }

        private static String describeExpected(List<Map.Entry<String, JsonNode>> expected) {
            List<String> bodies = new ArrayList<>();
            for (Map.Entry<String, JsonNode> message : expected) {
                bodies.add(Content.render(bodyBytes(message.getValue(), message.getKey())));
            }
            return bodies.toString();
        }

        private static String describeJms(List<Object> messages) throws Exception {
            List<String> bodies = new ArrayList<>();
            for (Object message : messages) {
                bodies.add(Content.render(jmsBody(message)));
            }
            return bodies.toString();
        }

        private static byte[] jmsBody(Object message) throws Exception {
            Class<?> textType = Reflect.optionalClass(TEXT_MESSAGE_CLASS);
            Class<?> bytesType = Reflect.optionalClass(BYTES_MESSAGE_CLASS);
            if (textType != null && textType.isInstance(message)) {
                Object text = Reflect.callOn(textType, message, "getText");
                return text == null ? new byte[0] : ((String) text).getBytes(StandardCharsets.UTF_8);
            }
            if (bytesType != null && bytesType.isInstance(message)) {
                long length = (Long) Reflect.callOn(bytesType, message, "getBodyLength");
                byte[] content = new byte[Math.toIntExact(length)];
                Reflect.callOn(bytesType, message, "readBytes", content);
                return content;
            }
            throw new AssertionError("Unsupported JMS message type " + message.getClass().getName()
                    + "; fixtures record text or bytes bodies");
        }

        /** A header of the AMQP message, or else the standard property of that name, such as {@code contentType}. */
        private static Object amqpProperty(Object message, String key) throws Exception {
            Object messageProperties = Reflect.call(message, "getMessageProperties");
            Map<?, ?> headers = (Map<?, ?>) Reflect.call(messageProperties, "getHeaders");
            if (headers != null && headers.containsKey(key)) {
                return headers.get(key);
            }
            if (key.isEmpty()) {
                return null;
            }
            String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            return Reflect.hasMethod(messageProperties.getClass(), getter, 0)
                    ? Reflect.call(messageProperties, getter) : null;
        }

        // ----- seeded broker behaviour -----------------------------------------------------------------------

        /**
         * Makes the application's embedded Artemis server refuse every send to a destination of
         * {@code state.jms.denySend}, as the capture's broker refused them, the application's own sends included,
         * until the reset removes the refusal: a broker plugin refuses the message before it is routed, the way Artemis
         * refuses a send its security settings deny. A project whose JMS connection factory does not reach that server
         * in-VM cannot reproduce the refusal, so its scenario is reported {@code UNVERIFIED-missing-driver} instead of
         * running against a broker that accepts the sends.
         */
        private void installSendDenial() throws Exception {
            JsonNode jms = state.get("jms");
            if (jms == null) {
                return;
            }
            String what = at("state.jms");
            List<JsonNode> listed = elements(object(jms, what).get("denySend"), what + ".denySend");
            if (listed.isEmpty()) {
                throw new IllegalStateException(what + ".denySend lists no destination, but state.jms names the"
                        + " destinations whose sends the capture's broker refused");
            }
            List<BrokerDestination> denied = new ArrayList<>();
            for (int i = 0; i < listed.size(); i++) {
                String entry = what + ".denySend[" + i + "]";
                BrokerDestination destination = brokerDestination(listed.get(i), entry);
                if (denied.contains(destination)) {
                    throw new IllegalStateException(entry + " lists " + destination + " a second time");
                }
                denied.add(destination);
            }
            Object server = Reflect.call(
                    requireBean(requireClass(ARTEMIS_EMBEDDED_SERVER_CLASS), ARTEMIS_EMBEDDED_SERVER_CLASS),
                    "getActiveMQServer");
            Object factory = brokerFactory(
                    requireBean(requireClass(CONNECTION_FACTORY_CLASS), CONNECTION_FACTORY_CLASS));
            if (server == null || !reachesInVm(factory, server)) {
                missingDriver("a JMS connection factory that reaches the embedded Artemis server in-VM, so that the"
                        + " broker refusing the sends of state.jms is the one the application sends to");
            }
            Object plugin = sendDenialPlugin(denied);
            Reflect.call(server, "registerBrokerPlugin", plugin);
            artemisServer = server;
            sendDenial = plugin;
        }

        /**
         * Whether every connector of the Artemis connection factory is an in-VM connector to an in-VM acceptor of
         * {@code server}, which is how Spring Boot's embedded mode connects the application to the embedded server.
         */
        private static boolean reachesInVm(Object factory, Object server) throws Exception {
            if (!Reflect.hasMethod(factory.getClass(), "getStaticConnectors", 0)) {
                return false;
            }
            Object[] connectors = (Object[]) Reflect.call(factory, "getStaticConnectors");
            if (connectors == null || connectors.length == 0) {
                return false;
            }
            Set<String> serverIds = new HashSet<>();
            Object configuration = Reflect.call(server, "getConfiguration");
            for (Object acceptor : (Set<?>) Reflect.call(configuration, "getAcceptorConfigurations")) {
                if (ARTEMIS_IN_VM_ACCEPTOR_CLASS.equals(Reflect.call(acceptor, "getFactoryClassName"))) {
                    serverIds.add(inVmServerId(acceptor));
                }
            }
            for (Object connector : connectors) {
                if (!ARTEMIS_IN_VM_CONNECTOR_CLASS.equals(Reflect.call(connector, "getFactoryClassName"))
                        || !serverIds.contains(inVmServerId(connector))) {
                    return false;
                }
            }
            return true;
        }

        /** The in-VM server id of an Artemis transport configuration; Artemis reads an absent one as 0. */
        private static String inVmServerId(Object transport) throws Exception {
            Map<?, ?> params = (Map<?, ?>) Reflect.call(transport, "getParams");
            Object serverId = params == null ? null : params.get("serverId");
            return String.valueOf(serverId == null ? 0 : serverId);
        }

        /**
         * An Artemis message plugin, reached by reflection like the rest of the engine, whose
         * {@code beforeSend(ServerSession, Transaction, Message, boolean, boolean)} refuses a message addressed to a
         * denied destination as Artemis refuses a send its security settings deny: a session that does not commit each
         * send itself, a transacted one, gets its transaction marked rollback-only, so its commit fails, and the send
         * fails with an {@code ActiveMQSecurityException}. Every other plugin method keeps its default.
         */
        private Object sendDenialPlugin(List<BrokerDestination> denied) {
            Class<?> pluginType = requireClass(ARTEMIS_MESSAGE_PLUGIN_CLASS);
            Class<?> refusal = requireClass(ARTEMIS_SECURITY_EXCEPTION_CLASS);
            Class<?> sessionType = requireClass(ARTEMIS_SERVER_SESSION_CLASS);
            // Artemis keeps whether a session commits each send in this protected field and exposes no accessor.
            Field autoCommitSends;
            try {
                autoCommitSends = sessionType.getDeclaredField("autoCommitSends");
            } catch (NoSuchFieldException absent) {
                return missingDriver(ARTEMIS_SERVER_SESSION_CLASS + ".autoCommitSends, which tells a transacted send"
                        + " whose refusal must roll its transaction back");
            }
            if (autoCommitSends.getType() != boolean.class || !autoCommitSends.trySetAccessible()) {
                return missingDriver("a readable boolean " + ARTEMIS_SERVER_SESSION_CLASS + ".autoCommitSends, which"
                        + " tells a transacted send whose refusal must roll its transaction back");
            }
            String scenario = name;
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "refusal of the sends to " + denied + " for " + scenario;
                    };
                }
                if (method.getName().equals("beforeSend") && method.getParameterCount() == 5) {
                    BrokerDestination target = deniedTarget(args[2], denied);
                    if (target != null) {
                        Object refused = Reflect.construct(refusal, scenario + ": sends to " + target
                                + " are refused, as the capture's broker refused them (state.jms.denySend)");
                        boolean transacted = args[1] != null && sessionType.isInstance(args[0])
                                && !autoCommitSends.getBoolean(args[0]);
                        if (transacted) {
                            Reflect.call(args[1], "markAsRollbackOnly", refused);
                        }
                        LOG.info("{}: the broker refused a send to {}{} (state.jms.denySend)", scenario, target,
                                transacted ? " and marked its transaction rollback-only" : "");
                        throw (Throwable) refused;
                    }
                    return null;
                }
                return method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
            };
            return Proxy.newProxyInstance(pluginType.getClassLoader(), new Class<?>[] {pluginType}, handler);
        }

        /** The denied destination a message is addressed to; a routing type the message carries must match too. */
        private static BrokerDestination deniedTarget(Object message, List<BrokerDestination> denied) throws Exception {
            Object address = Reflect.call(message, "getAddress");
            Object routingType = Reflect.call(message, "getRoutingType");
            for (BrokerDestination destination : denied) {
                if (destination.name().equals(address) && (routingType == null
                        || String.valueOf(routingType).equals(destination.pubSub() ? "MULTICAST" : "ANYCAST"))) {
                    return destination;
                }
            }
            return null;
        }

        private void removeSendDenial() throws Exception {
            if (sendDenial != null) {
                Object server = artemisServer;
                Object plugin = sendDenial;
                artemisServer = null;
                sendDenial = null;
                Reflect.call(server, "unRegisterBrokerPlugin", plugin);
            }
        }

        /**
         * Starts one responder per entry of {@code state.amqp.responders}, as the capture ran them, after the drain and
         * before the trigger. Each takes one request off its {@code queue} within {@code awaitMillis} and publishes its
         * {@code reply}, with {@code reply.properties} as headers and the request's correlation id, to the request's
         * reply-to address; {@link #awaitResponders()} joins them before the effects are compared.
         */
        private void startResponders() throws Exception {
            JsonNode configured = state.path("amqp").get("responders");
            if (configured == null) {
                return;
            }
            String what = at("state.amqp.responders");
            List<JsonNode> listed = elements(configured, what);
            if (listed.isEmpty()) {
                throw new IllegalStateException(what + " lists no responder, but it names the responders the capture"
                        + " ran");
            }
            Class<?> propertiesType = requireClass(AMQP_PROPERTIES_CLASS);
            Class<?> messageType = requireClass(AMQP_MESSAGE_CLASS);
            List<Responder> prepared = new ArrayList<>();
            for (int i = 0; i < listed.size(); i++) {
                String entry = what + "[" + i + "]";
                JsonNode responder = object(listed.get(i), entry);
                String queue = text(responder, "queue", entry + ".queue");
                if (queue.isBlank()) {
                    throw new IllegalStateException(entry + ".queue must not be blank");
                }
                JsonNode reply = object(responder.get("reply"), entry + ".reply");
                Map<String, Object> headers = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> property : entries(reply.get("properties"),
                        entry + ".reply.properties")) {
                    headers.put(property.getKey(),
                            scalar(property.getValue(), entry + ".reply.properties." + property.getKey()));
                }
                prepared.add(new Responder(queue, bodyBytes(reply, entry + ".reply"), headers, propertiesType,
                        messageType));
            }
            for (Responder responder : prepared) {
                responders.add(responder);
                responder.start();
            }
        }

        /** Joins every responder after the trigger; one that has not answered fails the scenario. */
        private void awaitResponders() throws InterruptedException {
            for (Responder responder : responders) {
                responder.await();
            }
        }

        private void stopResponders() throws Exception {
            List<Responder> running = new ArrayList<>(responders);
            responders.clear();
            List<Exception> failures = new ArrayList<>();
            for (Responder responder : running) {
                attempt(failures, responder::stop);
            }
            if (!failures.isEmpty()) {
                Exception first = failures.get(0);
                failures.subList(1, failures.size()).forEach(first::addSuppressed);
                throw first;
            }
        }

        /** One responder of {@code state.amqp.responders}, on a daemon thread, using the project's RabbitTemplate. */
        private final class Responder implements Runnable {

            private final String queue;
            private final byte[] body;
            private final Map<String, Object> headers;
            private final Class<?> propertiesType;
            private final Class<?> messageType;
            private final Thread thread;
            private final AtomicReference<Throwable> failure = new AtomicReference<>();

            Responder(String queue, byte[] body, Map<String, Object> headers, Class<?> propertiesType,
                    Class<?> messageType) {
                this.queue = queue;
                this.body = body;
                this.headers = headers;
                this.propertiesType = propertiesType;
                this.messageType = messageType;
                this.thread = new Thread(this, "fixture-responder-" + queue);
                this.thread.setDaemon(true);
            }

            void start() {
                thread.start();
            }

            @Override
            public void run() {
                try {
                    respond();
                } catch (Throwable responderFailure) {
                    // Reported by await() on the replay's thread, which fails the scenario with it.
                    failure.set(responderFailure);
                }
            }

            private void respond() throws Exception {
                Object request = Reflect.call(rabbitTemplate, "receive", queue, awaitMillis);
                if (request == null) {
                    throw new AssertionError(name + ": no request reached " + this + " within " + awaitMillis
                            + " ms");
                }
                Object requestProperties = Reflect.call(request, "getMessageProperties");
                Object replyTo = Reflect.call(requestProperties, "getReplyToAddress");
                if (replyTo == null) {
                    throw new AssertionError(name + ": the request " + this + " took names no reply-to address: "
                            + Content.render((byte[]) Reflect.call(request, "getBody")));
                }
                Object properties = Reflect.construct(propertiesType);
                // The reply carries the captured headers and the correlation id only, as the capture's reply did.
                Reflect.call(properties, "setContentType", (Object) null);
                Reflect.call(properties, "setPriority", (Object) null);
                Reflect.call(properties, "setDeliveryMode", (Object) null);
                for (Map.Entry<String, Object> header : headers.entrySet()) {
                    Reflect.call(properties, "setHeader", header.getKey(), header.getValue());
                }
                Object correlationId = Reflect.call(requestProperties, "getCorrelationId");
                if (correlationId != null) {
                    Reflect.call(properties, "setCorrelationId", correlationId);
                }
                Reflect.call(rabbitTemplate, "send", Reflect.call(replyTo, "getExchangeName"),
                        Reflect.call(replyTo, "getRoutingKey"), Reflect.construct(messageType, body, properties));
            }

            /** Waits for the responder, at most {@code awaitMillis}; a responder that failed fails the scenario. */
            void await() throws InterruptedException {
                thread.join(awaitMillis);
                if (thread.isAlive()) {
                    throw new AssertionError(name + ": " + this + " had not answered " + awaitMillis
                            + " ms after the input was sent");
                }
                Throwable failed = failure.get();
                if (failed instanceof AssertionError mismatch) {
                    throw mismatch;
                }
                if (failed != null) {
                    throw new AssertionError(name + ": " + this + " failed: " + failed, failed);
                }
            }

            /** Stops a responder still waiting for its request; one that does not end fails the reset. */
            void stop() throws InterruptedException {
                if (thread.isAlive()) {
                    thread.interrupt();
                    thread.join(awaitMillis);
                }
                if (thread.isAlive()) {
                    throw new IllegalStateException(name + ": " + this + " did not stop within " + awaitMillis
                            + " ms");
                }
            }

            @Override
            public String toString() {
                return "the responder on queue " + queue;
            }
        }


        // ----- side effects ----------------------------------------------------------------------------------

        private void verifySideEffects() throws Exception {
            if (response.has("directories")) {
                awaitMatch("directories", this::checkDirectories);
            }
            if (response.has("tables")) {
                awaitMatch("tables", this::checkTables);
            }
            if (response.has("collections")) {
                awaitMatch("collections", this::checkCollections);
            }
            if (response.has("mails")) {
                awaitMatch("mails", this::checkMails);
            }
            if (response.has("mailboxes")) {
                awaitMatch("mailboxes", this::checkMailboxes);
            }
            if (response.has("logs")) {
                verifyLogs();
            }
            // Last, so the effects above have shown the flow finished before a destination is judged empty.
            if (response.has("destinations")) {
                verifyMessages();
            }
        }

        /** Polls {@code check} every 100 ms until it passes or {@code awaitMillis} ends, then reports its last mismatch. */
        private void awaitMatch(String what, Action check) {
            AtomicReference<AssertionError> lastMismatch = new AtomicReference<>();
            try {
                Awaitility.await(name + " " + what)
                        .pollInSameThread()
                        .pollDelay(Duration.ZERO)
                        .pollInterval(POLL_INTERVAL)
                        .atMost(Duration.ofMillis(awaitMillis))
                        .until(() -> {
                            try {
                                check.run();
                                return true;
                            } catch (AssertionError mismatch) {
                                lastMismatch.set(mismatch);
                                return false;
                            }
                        });
            } catch (ConditionTimeoutException timeout) {
                AssertionError mismatch = lastMismatch.get();
                if (mismatch != null) {
                    throw mismatch;
                }
                throw new AssertionError(name + ": " + what + " did not match within " + awaitMillis + " ms", timeout);
            }
        }

        /**
         * The quiet window after {@link #awaitMatch}: runs {@code check} at once and then every {@link #POLL_INTERVAL}
         * until it has passed throughout {@code window}, so an effect that arrives late fails. A mismatch ends the
         * window at once with the check's own {@link AssertionError}, and any other failure of the check with that
         * failure. An interrupt ends it at the next comparison with an {@link InterruptedException}, the interrupt
         * status cleared as {@link Thread#sleep} clears it. A window its comparisons cannot complete within
         * {@link #QUIET_WINDOW_MARGIN} of its end fails as an {@link AssertionError} naming {@code label}.
         *
         * @param label the scenario and the effect compared, as {@code <scenario>: <effect>}
         */
        static void awaitQuietWindow(String label, Duration window, Action check) throws Exception {
            Duration limit = window.plus(QUIET_WINDOW_MARGIN);
            try {
                Awaitility.await(label)
                        .pollInSameThread()
                        .pollDelay(Duration.ZERO)
                        .pollInterval(POLL_INTERVAL)
                        .during(window)
                        .atMost(limit)
                        .until(() -> {
                            // Awaitility sleeps between polls uninterruptibly, so the interrupt is taken here.
                            if (Thread.interrupted()) {
                                throw new InterruptedException(label + " was interrupted during its "
                                        + window.toMillis() + " ms quiet window");
                            }
                            check.run();
                            return true;
                        });
            } catch (ConditionTimeoutException timeout) {
                throw new AssertionError(label + " did not hold for the " + window.toMillis()
                        + " ms quiet window: its comparisons did not complete within " + limit.toMillis() + " ms",
                        timeout);
            }
        }

        private void checkDirectories() throws IOException {
            for (Map.Entry<String, JsonNode> entry : entries(response.get("directories"), at("response.directories"))) {
                String what = at("response.directories." + entry.getKey());
                Map<String, byte[]> expected = new TreeMap<>();
                for (JsonNode file : elements(entry.getValue(), what)) {
                    String fileName = text(file, "name", what + "[].name");
                    expected.put(fileName, bodyBytes(file, what + "." + fileName));
                }
                Map<String, byte[]> actual = listFiles(resolveDirectory(entry.getKey(), name));
                String label = "directory " + entry.getKey();
                if (!row.deterministic()) {
                    compareFileStructure(label, expected, actual);
                    continue;
                }
                if (!expected.keySet().equals(actual.keySet())) {
                    throw new AssertionError(name + ": " + label + " holds " + actual.keySet()
                            + " but the original left " + expected.keySet());
                }
                for (Map.Entry<String, byte[]> file : expected.entrySet()) {
                    compareContent(label + "/" + file.getKey(), file.getValue(), actual.get(file.getKey()), null);
                }
            }
        }

        /** Pairs the files by name as {@link #pairFileNames} does and compares each pair's content by structure. */
        private void compareFileStructure(String label, Map<String, byte[]> expected, Map<String, byte[]> actual) {
            Map<String, String> pairs = pairFileNames(name + ": " + label, expected.keySet(), actual.keySet());
            for (Map.Entry<String, String> pair : pairs.entrySet()) {
                compareContent(label + "/" + pair.getValue(), expected.get(pair.getKey()), actual.get(pair.getValue()),
                        null);
            }
        }

        private static Map<String, byte[]> listFiles(Path directory) throws IOException {
            Map<String, byte[]> files = new TreeMap<>();
            if (!Files.isDirectory(directory)) {
                return files;
            }
            List<Path> listed;
            try (Stream<Path> listing = Files.list(directory)) {
                listed = listing.filter(Files::isRegularFile).toList();
            }
            for (Path file : listed) {
                byte[] content = readIfPresent(file);
                if (content != null) {
                    files.put(file.getFileName().toString(), content);
                }
            }
            return files;
        }

        /** {@code null} when the application moved or deleted the file after it was listed; the next poll re-lists. */
        private static byte[] readIfPresent(Path file) throws IOException {
            try {
                return Files.readAllBytes(file);
            } catch (NoSuchFileException vanished) {
                return null;
            }
        }

        private void checkTables() throws Exception {
            DataSource dataSource = (DataSource) requireBean(DataSource.class, DataSource.class.getName());
            try (Connection connection = dataSource.getConnection()) {
                confirmReplayDatabase(connection);
                for (Map.Entry<String, JsonNode> table : entries(response.get("tables"), at("response.tables"))) {
                    String tableName = identifier(table.getKey());
                    List<JsonNode> expectedRows = elements(table.getValue(), at("response.tables." + tableName));
                    List<Map<String, Object>> actualRows = selectAll(connection, tableName);
                    if (expectedRows.size() != actualRows.size()) {
                        throw new AssertionError(name + ": table " + tableName + " holds " + actualRows.size()
                                + " rows but the original left " + expectedRows.size() + "\n  expected: " + expectedRows
                                + "\n  actual:   " + actualRows);
                    }
                    for (int i = 0; i < expectedRows.size(); i++) {
                        compareRow(tableName, i, expectedRows.get(i), actualRows.get(i));
                    }
                }
            }
        }

        /** Compares the captured columns only: keys the database assigns and the flow never reads are not captured. */
        private void compareRow(String table, int index, JsonNode expected, Map<String, Object> actual) {
            for (Map.Entry<String, JsonNode> column : entries(expected, at("response.tables." + table + "[" + index + "]"))) {
                String columnName = lower(identifier(column.getKey()));
                if (!actual.containsKey(columnName)) {
                    throw new AssertionError(name + ": table " + table + " has no column " + column.getKey());
                }
                Object value = actual.get(columnName);
                if (!scalarEquals(column.getValue(), value)) {
                    throw new AssertionError(name + ": table " + table + " row " + (index + 1) + " column "
                            + column.getKey() + " expected " + column.getValue() + " but was "
                            + (value == null ? "NULL" : sqlText(value)));
                }
            }
        }

        private static List<Map<String, Object>> selectAll(Connection connection, String table) throws SQLException {
            List<Map<String, Object>> found = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
                ResultSetMetaData metaData = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int column = 1; column <= metaData.getColumnCount(); column++) {
                        values.put(lower(metaData.getColumnLabel(column)), result.getObject(column));
                    }
                    found.add(values);
                }
            }
            return found;
        }

        private void checkCollections() throws Exception {
            Object template = requireBean(requireClass(MONGO_TEMPLATE_CLASS), MONGO_TEMPLATE_CLASS);
            Class<?> document = requireClass(BSON_DOCUMENT_CLASS);
            confirmReplayStore();
            for (Map.Entry<String, JsonNode> collection : entries(response.get("collections"),
                    at("response.collections"))) {
                List<JsonNode> expected = new ArrayList<>();
                for (JsonNode stored : elements(collection.getValue(), at("response.collections." + collection.getKey()))) {
                    // Rendered through BSON as the actual documents are, so Extended JSON forms compare alike.
                    expected.add(documentTree(Reflect.callStatic(document, "parse", stored.toString())));
                }
                List<JsonNode> actual = new ArrayList<>();
                for (Object stored : (List<?>) Reflect.call(template, "findAll", document, collection.getKey())) {
                    actual.add(documentTree(stored));
                }
                boolean equal = expected.size() == actual.size();
                for (int i = 0; equal && i < expected.size(); i++) {
                    equal = jsonEquals(expected.get(i), actual.get(i));
                }
                if (!equal) {
                    throw new AssertionError(name + ": collection " + collection.getKey() + " differs\n  expected: "
                            + expected + "\n  actual:   " + actual);
                }
            }
        }

        /** The document's relaxed Extended JSON without {@code _id}. */
        private static JsonNode documentTree(Object document) throws Exception {
            JsonNode tree = JSON.readTree((String) Reflect.call(document, "toJson"));
            if (tree instanceof ObjectNode object) {
                object.remove("_id");
            }
            return tree;
        }

        /**
         * Compares the outbound mail of {@code response.mails}: every mail GreenMail holds after the run, in any
         * mailbox, except the input mails this replay delivered. Only this comparison leaves the input out; what a
         * mailbox finally holds, the input included, is {@code response.mailboxes} ({@link #checkMailboxes}).
         */
        private void checkMails() throws Exception {
            List<MailView> actual = new ArrayList<>();
            for (Object received : (Object[]) Reflect.call(greenMail, "getReceivedMessages")) {
                actual.add(MailView.of(received));
            }
            // The input mails this replay delivered are not output: one the flow left in its mailbox is removed here,
            // and whether the flow removed it is compared through response.mailboxes alone.
            for (DeliveredMail delivered : deliveredMails) {
                actual.remove(delivered.view());
            }
            List<MailView> expected = new ArrayList<>();
            for (JsonNode mail : elements(response.get("mails"), at("response.mails"))) {
                expected.add(MailView.of(parseMime(base64(text(mail, "mimeBase64", at("response.mails[].mimeBase64")),
                        at("response.mails[].mimeBase64")))));
            }
            if (expected.size() != actual.size()) {
                throw new AssertionError(name + ": " + actual.size() + " mails arrived but the original produced "
                        + expected.size() + "\n  expected: " + expected + "\n  actual:   " + actual);
            }
            for (int i = 0; i < expected.size(); i++) {
                compareMail("mail " + (i + 1), expected.get(i), actual.get(i));
            }
        }

        /**
         * Compares the final contents of each mailbox {@code response.mailboxes} names, keyed by an email of
         * {@code state.mail.users}: every message its INBOX holds after the run, in mailbox order, the input mail
         * this replay delivered included while it is still there. A message flagged deleted but not expunged is still
         * held, so the message count asserts whether the flow removed its input.
         */
        private void checkMailboxes() throws Exception {
            Set<String> users = new HashSet<>();
            for (JsonNode user : elements(state.path("mail").path("users"), at("state.mail.users"))) {
                users.add(text(user, "email", at("state.mail.users[].email")));
            }
            for (Map.Entry<String, JsonNode> mailbox : entries(response.get("mailboxes"), at("response.mailboxes"))) {
                String email = mailbox.getKey();
                String what = at("response.mailboxes." + email);
                if (!users.contains(email)) {
                    throw new IllegalStateException(what + ": " + email + " is not a mailbox of state.mail.users");
                }
                List<MailView> expected = new ArrayList<>();
                for (JsonNode mail : elements(mailbox.getValue(), what)) {
                    expected.add(MailView.of(parseMime(base64(text(mail, "mimeBase64", what + "[].mimeBase64"),
                            what + "[].mimeBase64"))));
                }
                List<MailView> actual = new ArrayList<>();
                for (Object stored : (List<?>) Reflect.call(inbox(email, what), "getMessages")) {
                    actual.add(MailView.of(Reflect.call(stored, "getMimeMessage")));
                }
                String label = "mailbox " + email;
                if (expected.size() != actual.size()) {
                    boolean inputKept = deliveredMails.stream().anyMatch(
                            delivered -> delivered.mailbox().equals(email) && actual.contains(delivered.view()));
                    throw new AssertionError(name + ": " + label + " holds " + actual.size()
                            + " mails but the original left " + expected.size()
                            + (inputKept ? "; the input mail request.mail the replay delivered to it is still there"
                                    + " (not expunged)" : "")
                            + "\n  expected: " + expected + "\n  actual:   " + actual);
                }
                for (int i = 0; i < expected.size(); i++) {
                    compareMail(label + " mail " + (i + 1), expected.get(i), actual.get(i));
                }
            }
        }

        /** The GreenMail INBOX of the user {@code email}, read by reflection like every GreenMail call. */
        private static Object inbox(String email, String what) throws Exception {
            Object user = Reflect.call(Reflect.call(greenMail, "getUserManager"), "getUserByEmail", email);
            if (user == null) {
                throw new IllegalStateException(what + ": GreenMail holds no mailbox " + email);
            }
            Object hostManager = Reflect.call(Reflect.call(greenMail, "getManagers"), "getImapHostManager");
            return Reflect.call(hostManager, "getInbox", user);
        }

        private void compareMail(String label, MailView expected, MailView actual) {
            if (!expected.envelope().equals(actual.envelope())) {
                throw new AssertionError(name + ": " + label + " differs\n  expected: " + expected.envelope()
                        + "\n  actual:   " + actual.envelope());
            }
            for (int i = 0; i < expected.parts().size(); i++) {
                MailPart part = expected.parts().get(i);
                compareContent(label + " part " + (i + 1), part.content(), actual.parts().get(i).content(),
                        part.contentType());
            }
        }

        /**
         * Compares the log stream with {@code response.logs} ({@link #logStreamDifference}). It polls until the
         * stream matches or {@code awaitMillis} ends, so a stream still short of its entries keeps growing, then
         * keeps comparing until it has matched throughout the {@link #NO_FURTHER_MESSAGE_MILLIS} quiet window
         * ({@link #awaitQuietWindow}), so a further event of those loggers that arrives late fails.
         * {@code "logs": []} names no logger, so it constrains nothing and nothing is awaited.
         */
        private void verifyLogs() throws Exception {
            List<LogEntry> expected = logEntries(response.get("logs"), at("response.logs"));
            if (expected.isEmpty()) {
                return;
            }
            Action check = () -> {
                String difference = logStreamDifference(expected, loggedEvents(), row.deterministic());
                if (difference != null) {
                    throw new AssertionError(name + ": " + difference);
                }
            };
            awaitMatch("logs", check);
            awaitQuietWindow(name + ": logs", Duration.ofMillis(NO_FURTHER_MESSAGE_MILLIS), check);
        }

        /** A {@code response.logs} entry: logger category, level and message text, {@code ""} when absent. */
        record LogEntry(String logger, String level, String message) {

            @Override
            public String toString() {
                return level + " " + logger + " - " + message;
            }
        }

        /**
         * The entries of {@code response.logs} in order: {@code logger} and {@code level} are required strings and
         * {@code message} is a string read as {@code ""} when absent or null. A malformed entry fails the fixture.
         */
        static List<LogEntry> logEntries(JsonNode logs, String what) {
            List<LogEntry> entries = new ArrayList<>();
            List<JsonNode> elements = elements(logs, what);
            for (int i = 0; i < elements.size(); i++) {
                String entryWhat = what + "[" + i + "]";
                JsonNode entry = object(elements.get(i), entryWhat);
                String message = entry.hasNonNull("message") ? text(entry, "message", entryWhat + ".message") : "";
                entries.add(new LogEntry(text(entry, "logger", entryWhat + ".logger"),
                        text(entry, "level", entryWhat + ".level"), message));
            }
            return entries;
        }

        /**
         * The stream {@code expected} is compared with: every event of {@code logged}, in logged order, whose logger
         * is one an entry of {@code expected} names. Events of every other logger, the framework's included, are
         * left out, so no entry has to account for them.
         */
        static List<ILoggingEvent> logStream(List<LogEntry> expected, List<ILoggingEvent> logged) {
            Set<String> loggers = loggerNames(expected);
            List<ILoggingEvent> stream = new ArrayList<>();
            for (ILoggingEvent event : logged) {
                if (loggers.contains(event.getLoggerName())) {
                    stream.add(event);
                }
            }
            return stream;
        }

        /** The loggers the entries name, in the order of their first entry. */
        private static Set<String> loggerNames(List<LogEntry> entries) {
            Set<String> loggers = new LinkedHashSet<>();
            entries.forEach(entry -> loggers.add(entry.logger()));
            return loggers;
        }

        /**
         * Compares the stream of the loggers {@code expected} names ({@link #logStream}) with {@code expected}, entry
         * for entry: the stream must hold exactly as many events, each with its entry's logger and level and, in a
         * {@code deterministic} scenario, exactly its message, in a {@code nondeterministic} one only whether the
         * message is empty. An extra, missing or reordered event is a difference, so a stream holding one rollback
         * delivery more or fewer than the capture differs. With no entries no logger is named, so the stream is
         * empty and matches.
         *
         * @return {@code null} when the stream matches; otherwise its first difference, then both streams
         */
        static String logStreamDifference(List<LogEntry> expected, List<ILoggingEvent> logged, boolean deterministic) {
            List<ILoggingEvent> stream = logStream(expected, logged);
            String difference = null;
            for (int i = 0; i < stream.size() && difference == null; i++) {
                if (i == expected.size()) {
                    difference = "holds a further event after the " + expected.size() + " the original logged: "
                            + describeEvent(stream.get(i));
                } else if (!logMatches(expected.get(i), stream.get(i), deterministic)) {
                    difference = "differs at event " + (i + 1) + ": expected " + expected.get(i) + ", logged "
                            + describeEvent(stream.get(i));
                }
            }
            if (difference == null && stream.size() < expected.size()) {
                difference = "holds " + stream.size() + " of the " + expected.size() + " events the original"
                        + " logged; the next is " + expected.get(stream.size());
            }
            return difference == null ? null : "the log stream of " + loggerNames(expected) + " " + difference
                    + "\n  expected: " + describeEntries(expected) + "\n  logged:   " + describeEvents(stream);
        }

        private static boolean logMatches(LogEntry entry, ILoggingEvent event, boolean deterministic) {
            String formatted = event.getFormattedMessage() == null ? "" : event.getFormattedMessage();
            boolean messageMatches = deterministic ? entry.message().equals(formatted)
                    : entry.message().isEmpty() == formatted.isEmpty();
            return messageMatches
                    && entry.logger().equals(event.getLoggerName())
                    && entry.level().equals(String.valueOf(event.getLevel()));
        }

        private static String describeEntries(List<LogEntry> entries) {
            StringBuilder described = new StringBuilder();
            int first = Math.max(0, entries.size() - 50);
            for (LogEntry entry : entries.subList(first, entries.size())) {
                described.append("\n    ").append(entry);
            }
            return entries.isEmpty() ? "(nothing)" : described.toString();
        }

        private static String describeEvents(List<ILoggingEvent> events) {
            StringBuilder described = new StringBuilder();
            int first = Math.max(0, events.size() - 50);
            for (ILoggingEvent event : events.subList(first, events.size())) {
                described.append("\n    ").append(describeEvent(event));
            }
            return events.isEmpty() ? "(nothing)" : described.toString();
        }

        private static String describeEvent(ILoggingEvent event) {
            return event.getLevel() + " " + event.getLoggerName() + " - " + event.getFormattedMessage();
        }

        private void compareContent(String what, byte[] expected, byte[] actual, String contentType) {
            Content.compare(name, row.deterministic(), what, expected, actual, contentType);
        }

        private String at(String path) {
            return name + ": " + path;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Mail: the fields a mail comparison reads, through Jakarta Mail by reflection.
    // ---------------------------------------------------------------------------------------------------------

    /** One leaf MIME part; the decoded bytes are kept as Base64 so the record compares by value. */
    record MailPart(String contentType, String fileName, String contentBase64) {

        byte[] content() {
            return Base64.getDecoder().decode(contentBase64);
        }
    }

    /** From, To, Cc, Subject and the leaf parts, depth first; Message-ID, Date and boundaries are ignored. */
    record MailView(List<String> from, List<String> to, List<String> cc, String subject, List<MailPart> parts) {

        /** Everything but the part bytes, which nondeterministic scenarios compare by structure only. */
        List<Object> envelope() {
            List<Object> envelope = new ArrayList<>(List.of(from, to, cc, String.valueOf(subject)));
            parts.forEach(part -> envelope.add(part.contentType() + " filename=" + part.fileName()));
            return envelope;
        }

        static MailView of(Object message) throws Exception {
            Class<?> recipientType = Reflect.requiredClass(RECIPIENT_TYPE_CLASS);
            List<MailPart> parts = new ArrayList<>();
            collectParts(message, parts);
            return new MailView(addresses(Reflect.call(message, "getFrom")),
                    addresses(Reflect.call(message, "getRecipients", Reflect.staticField(recipientType, "TO"))),
                    addresses(Reflect.call(message, "getRecipients", Reflect.staticField(recipientType, "CC"))),
                    (String) Reflect.call(message, "getSubject"), List.copyOf(parts));
        }

        private static List<String> addresses(Object addresses) {
            List<String> rendered = new ArrayList<>();
            if (addresses != null) {
                for (Object address : (Object[]) addresses) {
                    rendered.add(String.valueOf(address));
                }
            }
            return List.copyOf(rendered);
        }

        private static void collectParts(Object part, List<MailPart> parts) throws Exception {
            if ((Boolean) Reflect.call(part, "isMimeType", "multipart/*")) {
                Object multipart = Reflect.call(part, "getContent");
                int count = (Integer) Reflect.call(multipart, "getCount");
                for (int i = 0; i < count; i++) {
                    collectParts(Reflect.call(multipart, "getBodyPart", i), parts);
                }
                return;
            }
            String contentType = withoutBoundary((String) Reflect.call(part, "getContentType"));
            String fileName = (String) Reflect.call(part, "getFileName");
            byte[] content;
            try (InputStream in = (InputStream) Reflect.call(part, "getInputStream")) {
                content = in.readAllBytes();
            }
            parts.add(new MailPart(contentType, fileName, Base64.getEncoder().encodeToString(content)));
        }

        private static String withoutBoundary(String contentType) {
            if (contentType == null) {
                return null;
            }
            String unfolded = FOLDED_LINE.matcher(contentType).replaceAll(" ");
            return BOUNDARY_PARAMETER.matcher(unfolded).replaceAll("").strip();
        }
    }

    static Object parseMime(byte[] mime) throws Exception {
        Object session = Reflect.callStatic(Reflect.requiredClass(MAIL_SESSION_CLASS), "getInstance", new Properties());
        return Reflect.construct(Reflect.requiredClass(MIME_MESSAGE_CLASS), session, new ByteArrayInputStream(mime));
    }


    // ---------------------------------------------------------------------------------------------------------
    // Content comparison: bytes for deterministic scenarios, structure for nondeterministic ones.
    // ---------------------------------------------------------------------------------------------------------

    static final class Content {

        private enum Format { JSON, XML, TEXT }

        private static final DocumentBuilderFactory XML_FACTORY = secureXmlFactory();
        /** Keeps the parser from printing each body it rejects to standard error; fatal errors still throw. */
        private static final Object QUIET_ERROR_HANDLER = quietErrorHandler();

        private Content() {
        }

        static void compare(String scenario, boolean deterministic, String what, byte[] expected, byte[] actual,
                String contentType) {
            if (deterministic) {
                if (!Arrays.equals(expected, actual)) {
                    throw mismatch(scenario, what, "differs", expected, actual);
                }
                return;
            }
            String difference = structureDifference(expected, actual == null ? new byte[0] : actual, contentType);
            if (difference != null) {
                throw mismatch(scenario, what, "differs in structure (" + difference + ")", expected, actual);
            }
        }

        private static AssertionError mismatch(String scenario, String what, String problem, byte[] expected,
                byte[] actual) {
            return new AssertionError(scenario + ": " + what + " " + problem + "\n  expected: " + render(expected)
                    + "\n  actual:   " + render(actual));
        }

        /** The bytes as quoted UTF-8 text when they decode cleanly, otherwise as Base64. */
        static String render(byte[] bytes) {
            if (bytes == null) {
                return "(none)";
            }
            try {
                return "\"" + StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)) + "\"";
            } catch (CharacterCodingException e) {
                return "base64:" + Base64.getEncoder().encodeToString(bytes);
            }
        }

        /** {@code null} when the structures agree; content that only looks like JSON or XML compares as text. */
        static String structureDifference(byte[] expected, byte[] actual, String contentType) {
            Format format = detect(contentType, expected);
            if (format == Format.JSON) {
                JsonShape expectedShape = parseJson(expected);
                if (expectedShape != null) {
                    JsonShape actualShape = parseJson(actual);
                    return actualShape == null ? "the actual content is not JSON"
                            : jsonDifference(expectedShape, actualShape, "$");
                }
            } else if (format == Format.XML) {
                Element expectedRoot = parseXml(expected);
                if (expectedRoot != null) {
                    Element actualRoot = parseXml(actual);
                    return actualRoot == null ? "the actual content is not XML"
                            : xmlDifference(expectedRoot, actualRoot, "/" + qualifiedName(expectedRoot));
                }
            }
            boolean expectedEmpty = expected.length == 0;
            if (expectedEmpty == (actual.length == 0)) {
                return null;
            }
            return expectedEmpty ? "expected empty content" : "expected non-empty content";
        }

        private static Format detect(String contentType, byte[] expected) {
            if (contentType != null) {
                String type = contentType.toLowerCase(Locale.ROOT);
                if (type.contains("json")) {
                    return Format.JSON;
                }
                if (type.contains("xml")) {
                    return Format.XML;
                }
            }
            String text = new String(expected, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            text = text.strip();
            if (text.startsWith("{") || text.startsWith("[")) {
                return Format.JSON;
            }
            return text.startsWith("<") ? Format.XML : Format.TEXT;
        }

        /**
         * The structure of the one JSON value {@code content} holds, or {@code null} when it holds none: empty,
         * malformed, or followed by trailing tokens. It is read from the token stream rather than bound into a
         * tree, whose objects keep only the last value of a repeated member name; DataWeave's XML-to-JSON writes
         * repeated elements as repeated keys, so every occurrence is kept, in order.
         */
        private static JsonShape parseJson(byte[] content) {
            if (content.length == 0) {
                return null;
            }
            try (JsonParser parser = JSON.createParser(content)) {
                JsonToken first = parser.nextToken();
                if (first == null) {
                    return null;
                }
                JsonShape shape = JsonShape.read(parser, first);
                return parser.nextToken() == null ? shape : null;
            } catch (IOException notJson) {
                return null;
            }
        }

        /** Member names in order with repeated names kept, value kinds and array lengths, recursively. */
        private static String jsonDifference(JsonShape expected, JsonShape actual, String path) {
            if (expected.kind() != actual.kind()) {
                return path + ": expected " + expected.kind() + " but was " + actual.kind();
            }
            if (expected.kind() == JsonNodeType.OBJECT) {
                if (!expected.names().equals(actual.names())) {
                    return path + ": expected keys " + expected.names() + " but was " + actual.names();
                }
                for (int i = 0; i < expected.names().size(); i++) {
                    String difference = jsonDifference(expected.children().get(i), actual.children().get(i),
                            path + "." + expected.names().get(i));
                    if (difference != null) {
                        return difference;
                    }
                }
            } else if (expected.kind() == JsonNodeType.ARRAY) {
                if (expected.children().size() != actual.children().size()) {
                    return path + ": expected " + expected.children().size() + " elements but was "
                            + actual.children().size();
                }
                for (int i = 0; i < expected.children().size(); i++) {
                    String difference = jsonDifference(expected.children().get(i), actual.children().get(i),
                            path + "[" + i + "]");
                    if (difference != null) {
                        return difference;
                    }
                }
            }
            return null;
        }

        /**
         * One JSON value reduced to its structure: its kind; for an object, its member names in document order,
         * every occurrence of a repeated name kept, with the member values in the same order; for an array, its
         * elements. Scalars keep their kind only.
         */
        private record JsonShape(JsonNodeType kind, List<String> names, List<JsonShape> children) {

            /** Reads the value {@code token} starts, leaving the parser on the value's last token. */
            static JsonShape read(JsonParser parser, JsonToken token) throws IOException {
                return switch (token) {
                    case START_OBJECT -> {
                        List<String> names = new ArrayList<>();
                        List<JsonShape> values = new ArrayList<>();
                        for (JsonToken member = next(parser); member != JsonToken.END_OBJECT; member = next(parser)) {
                            names.add(parser.currentName());
                            values.add(read(parser, next(parser)));
                        }
                        yield new JsonShape(JsonNodeType.OBJECT, names, values);
                    }
                    case START_ARRAY -> {
                        List<JsonShape> elements = new ArrayList<>();
                        for (JsonToken element = next(parser); element != JsonToken.END_ARRAY; element = next(parser)) {
                            elements.add(read(parser, element));
                        }
                        yield new JsonShape(JsonNodeType.ARRAY, List.of(), elements);
                    }
                    case VALUE_STRING -> {
                        // Decoded in full, as tree binding does, so the parser's string-length limit still applies.
                        parser.getText();
                        yield scalar(JsonNodeType.STRING);
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> scalar(JsonNodeType.NUMBER);
                    case VALUE_TRUE, VALUE_FALSE -> scalar(JsonNodeType.BOOLEAN);
                    case VALUE_NULL -> scalar(JsonNodeType.NULL);
                    default -> throw new JsonParseException(parser, "Expected a JSON value but found " + token);
                };
            }

            private static JsonShape scalar(JsonNodeType kind) {
                return new JsonShape(kind, List.of(), List.of());
            }

            /** The next token; the end of content inside a value is a parse error, never a structure. */
            private static JsonToken next(JsonParser parser) throws IOException {
                JsonToken token = parser.nextToken();
                if (token == null) {
                    throw new JsonParseException(parser, "Unexpected end of content inside a JSON value");
                }
                return token;
            }
        }

        private static synchronized Element parseXml(byte[] content) {
            if (content.length == 0) {
                return null;
            }
            try {
                DocumentBuilder builder = XML_FACTORY.newDocumentBuilder();
                Reflect.callOn(DocumentBuilder.class, builder, "setErrorHandler", QUIET_ERROR_HANDLER);
                Document document = builder.parse(new ByteArrayInputStream(content));
                return document.getDocumentElement();
            } catch (Exception notXml) {
                return null;
            }
        }

        /** Child elements in order by namespace and local name, attribute names as a set; text is ignored. */
        private static String xmlDifference(Element expected, Element actual, String path) {
            if (!Objects.equals(expected.getNamespaceURI(), actual.getNamespaceURI())
                    || !Objects.equals(expected.getLocalName(), actual.getLocalName())) {
                return path + ": expected element " + qualifiedName(expected) + " but was " + qualifiedName(actual);
            }
            Set<String> expectedAttributes = attributeNames(expected);
            Set<String> actualAttributes = attributeNames(actual);
            if (!expectedAttributes.equals(actualAttributes)) {
                return path + ": expected attributes " + expectedAttributes + " but was " + actualAttributes;
            }
            List<Element> expectedChildren = childElements(expected);
            List<Element> actualChildren = childElements(actual);
            if (expectedChildren.size() != actualChildren.size()) {
                return path + ": expected " + expectedChildren.size() + " child elements but was "
                        + actualChildren.size();
            }
            for (int i = 0; i < expectedChildren.size(); i++) {
                Element child = expectedChildren.get(i);
                String difference = xmlDifference(child, actualChildren.get(i), path + "/" + qualifiedName(child));
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }

        private static String qualifiedName(Node node) {
            return node.getNamespaceURI() == null ? node.getLocalName()
                    : "{" + node.getNamespaceURI() + "}" + node.getLocalName();
        }

        private static Set<String> attributeNames(Element element) {
            Set<String> names = new TreeSet<>();
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                if (!XMLNS_NAMESPACE.equals(attribute.getNamespaceURI())) {
                    names.add(qualifiedName(attribute));
                }
            }
            return names;
        }

        private static List<Element> childElements(Element element) {
            List<Element> children = new ArrayList<>();
            NodeList nodes = element.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element child) {
                    children.add(child);
                }
            }
            return children;
        }

        private static DocumentBuilderFactory secureXmlFactory() {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                return factory;
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException("The JDK XML parser rejects secure processing", e);
            }
        }

        private static Object quietErrorHandler() {
            Class<?> handlerType = Reflect.requiredClass(SAX_ERROR_HANDLER_CLASS);
            return Proxy.newProxyInstance(Reflect.loader(), new Class<?>[] {handlerType}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "quiet SAX error handler";
                    };
                }
                if (method.getName().equals("fatalError")) {
                    throw (Throwable) arguments[0];
                }
                return null;
            });
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reflection: the only access path to libraries that some projects carry and others lack.
    // ---------------------------------------------------------------------------------------------------------

    static final class Reflect {

        private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(boolean.class, Boolean.class,
                byte.class, Byte.class, char.class, Character.class, short.class, Short.class, int.class,
                Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class,
                void.class, Void.class);

        private Reflect() {
        }

        static ClassLoader loader() {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return loader != null ? loader : FixtureParityTest.class.getClassLoader();
        }

        /** The class, or {@code null} when it or a class it needs is not on the test classpath. */
        static Class<?> optionalClass(String name) {
            try {
                return Class.forName(name, true, loader());
            } catch (ClassNotFoundException | LinkageError absent) {
                return null;
            }
        }

        static Class<?> requiredClass(String name) {
            Class<?> type = optionalClass(name);
            if (type == null) {
                throw new IllegalStateException(name + " is not on the test classpath");
            }
            return type;
        }

        static boolean hasMethod(Class<?> type, String name, int arity) {
            return Arrays.stream(type.getMethods())
                    .anyMatch(method -> method.getName().equals(name) && method.getParameterCount() == arity);
        }

        static Method method(Class<?> type, String name, int arity) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().equals(name) && method.getParameterCount() == arity)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No public " + type.getName() + "." + name
                            + " with " + arity + " parameters"));
        }

        static Object call(Object target, String name, Object... args) throws Exception {
            return callOn(target.getClass(), target, name, args);
        }

        /** Invokes the public instance method of {@code type} that accepts {@code args}, the most specific one. */
        static Object callOn(Class<?> type, Object target, String name, Object... args) throws Exception {
            return invoke(accessible(select(type.getMethods(), type, name, args, false)), target, args);
        }

        static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
            return invoke(accessible(select(type.getMethods(), type, name, args, true)), null, args);
        }

        static Object construct(Class<?> type, Object... args) throws Exception {
            Constructor<?> constructor = select(type.getConstructors(), type, "<init>", args, null);
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            }
        }

        static Object staticField(Class<?> type, String name) throws Exception {
            return type.getField(name).get(null);
        }

        private static <E extends Executable> E select(E[] executables, Class<?> type, String name, Object[] args,
                Boolean isStatic) {
            List<E> candidates = new ArrayList<>();
            for (E executable : executables) {
                boolean named = executable instanceof Constructor<?> || executable.getName().equals(name);
                boolean staticMatches = isStatic == null || Modifier.isStatic(executable.getModifiers()) == isStatic;
                if (named && staticMatches && accepts(executable, args)) {
                    candidates.add(executable);
                }
            }
            if (candidates.isEmpty()) {
                // A present class without the member the replay calls is a programming error, never a missing driver.
                throw new IllegalStateException("No public " + type.getName() + "." + name + " accepts ("
                        + describe(args) + ")");
            }
            E best = candidates.get(0);
            for (E candidate : candidates) {
                if (moreSpecific(candidate, best)) {
                    best = candidate;
                }
            }
            return best;
        }

        private static boolean accepts(Executable executable, Object[] args) {
            Class<?>[] parameters = executable.getParameterTypes();
            if (parameters.length != args.length) {
                return false;
            }
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] == null ? parameters[i].isPrimitive() : !wrap(parameters[i]).isInstance(args[i])) {
                    return false;
                }
            }
            return true;
        }

        private static boolean moreSpecific(Executable candidate, Executable other) {
            Class<?>[] candidateParameters = candidate.getParameterTypes();
            Class<?>[] otherParameters = other.getParameterTypes();
            for (int i = 0; i < candidateParameters.length; i++) {
                if (!wrap(otherParameters[i]).isAssignableFrom(wrap(candidateParameters[i]))) {
                    return false;
                }
            }
            return true;
        }

        private static Class<?> wrap(Class<?> type) {
            return type.isPrimitive() ? WRAPPERS.get(type) : type;
        }

        /**
         * The same method as declared by a public supertype when its implementation class is not public (a JMS
         * message class of a broker, a JDK parser), since only the public declaration may be invoked.
         */
        private static Method accessible(Method method) {
            if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                return method;
            }
            Deque<Class<?>> types = new ArrayDeque<>();
            Set<Class<?>> seen = new HashSet<>();
            types.add(method.getDeclaringClass());
            while (!types.isEmpty()) {
                Class<?> type = types.poll();
                if (!seen.add(type)) {
                    continue;
                }
                if (Modifier.isPublic(type.getModifiers())) {
                    for (Method candidate : type.getMethods()) {
                        if (candidate.getName().equals(method.getName())
                                && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())
                                && Modifier.isPublic(candidate.getDeclaringClass().getModifiers())) {
                            return candidate;
                        }
                    }
                }
                if (type.getSuperclass() != null) {
                    types.add(type.getSuperclass());
                }
                types.addAll(Arrays.asList(type.getInterfaces()));
            }
            method.trySetAccessible();
            return method;
        }

        private static Object invoke(Method method, Object target, Object[] args) throws Exception {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            }
        }

        private static Exception unwrap(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                return exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IllegalStateException(cause);
        }

        private static String describe(Object[] args) {
            List<String> types = new ArrayList<>();
            for (Object arg : args) {
                types.add(arg == null ? "null" : arg.getClass().getName());
            }
            return String.join(", ", types);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fixture JSON and value helpers.
    // ---------------------------------------------------------------------------------------------------------

    private static JsonNode object(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(what + " must be a JSON object");
        }
        return node;
    }

    private static List<Map.Entry<String, JsonNode>> entries(JsonNode node, String what) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return entries;
        }
        object(node, what).fields().forEachRemaining(entries::add);
        return entries;
    }

    private static List<JsonNode> elements(JsonNode node, String what) {
        List<JsonNode> elements = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return elements;
        }
        if (!node.isArray()) {
            throw new IllegalStateException(what + " must be a JSON array");
        }
        node.forEach(elements::add);
        return elements;
    }

    private static String text(JsonNode node, String field, String what) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException(what + " must be a string");
        }
        return value.textValue();
    }

    private static int intValue(JsonNode node, String field, String what) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException(what + " must be a whole number");
        }
        return value.intValue();
    }

    /** {@code bodyBase64} decoded, else {@code body} as UTF-8, else no bytes. */
    private static byte[] bodyBytes(JsonNode node, String what) {
        JsonNode encoded = node.get("bodyBase64");
        if (encoded != null && !encoded.isNull()) {
            if (!encoded.isTextual()) {
                throw new IllegalStateException(what + ".bodyBase64 must be a string");
            }
            return base64(encoded.textValue(), what + ".bodyBase64");
        }
        JsonNode body = node.get("body");
        if (body == null || body.isNull()) {
            return new byte[0];
        }
        if (!body.isTextual()) {
            throw new IllegalStateException(what + ".body must be a string");
        }
        return body.textValue().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] base64(String value, String what) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(what + " is not valid Base64", e);
        }
    }

    /** Headers written as {@code [[name, value], ...]} or as {@code {name: value}} with array values allowed. */
    private static List<Map.Entry<String, String>> headerPairs(JsonNode node, String what) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return pairs;
        }
        if (node.isArray()) {
            for (JsonNode pair : node) {
                if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).isTextual()) {
                    throw new IllegalStateException(what + " must hold [name, value] string pairs");
                }
                pairs.add(Map.entry(pair.get(0).textValue(), pair.get(1).textValue()));
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> header : entries(node, what)) {
                JsonNode value = header.getValue();
                if (value.isArray()) {
                    for (JsonNode element : value) {
                        pairs.add(Map.entry(header.getKey(), headerText(element, what)));
                    }
                } else {
                    pairs.add(Map.entry(header.getKey(), headerText(value, what)));
                }
            }
        } else {
            throw new IllegalStateException(what + " must be [[name, value], ...] or {name: value}");
        }
        return pairs;
    }

    private static String headerText(JsonNode value, String what) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new IllegalStateException(what + " values must be strings");
        }
        return value.asText();
    }

    /** Pairs grouped by case-insensitive name in first-seen order, keeping the first spelling of each name. */
    private static Map<String, List<String>> groupByName(List<Map.Entry<String, String>> pairs) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        Map<String, String> spelling = new HashMap<>();
        for (Map.Entry<String, String> pair : pairs) {
            String key = spelling.computeIfAbsent(lower(pair.getKey()), lowerName -> pair.getKey());
            grouped.computeIfAbsent(key, unused -> new ArrayList<>()).add(pair.getValue());
        }
        return grouped;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Path and query of a recorded URL; an absolute URL loses its scheme and authority. */
    private static String relativeUrl(String url) {
        String lowerUrl = lower(url);
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return url;
        }
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Recorded upstream URL " + url + " is not a valid URI", e);
        }
    }

    /** Table and column names are spliced into SQL, so only {@code [A-Za-z0-9_]+} is accepted. */
    private static String identifier(String name) {
        if (!SQL_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalStateException("Table or column name '" + name + "' must match [A-Za-z0-9_]+");
        }
        return name;
    }

    private static Object jdbcValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return value.isTextual() ? value.textValue() : value.toString();
    }

    /** A JMS or AMQP property value: the Java type a message property can hold. */
    private static Object scalar(JsonNode value, String what) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        throw new IllegalStateException(what + " must be a string, number or boolean");
    }

    /** Numbers by value, JSON null against SQL NULL or an absent property, everything else by its text. */
    static boolean scalarEquals(JsonNode expected, Object actual) {
        boolean expectedNull = expected == null || expected.isNull() || expected.isMissingNode();
        if (actual == null || expectedNull) {
            return actual == null && expectedNull;
        }
        if (actual instanceof Number number && (expected.isNumber() || expected.isTextual())) {
            BigDecimal actualValue = decimal(number.toString());
            BigDecimal expectedValue = expected.isNumber() ? expected.decimalValue() : decimal(expected.textValue());
            if (actualValue != null && expectedValue != null) {
                return actualValue.compareTo(expectedValue) == 0;
            }
        }
        return sqlText(actual).equals(expected.isTextual() ? expected.textValue() : expected.toString());
    }

    /** SQL dates and times as ISO-8601 text, binary values as Base64, anything else by {@code toString()}. */
    static String sqlText(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }

    private static BigDecimal decimal(String text) {
        try {
            return new BigDecimal(text.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Structural JSON equality with numbers compared by value and object keys in any order. */
    static boolean jsonEquals(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey()) || !jsonEquals(field.getValue(), actual.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!jsonEquals(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }

    /**
     * Pairs the files a {@code nondeterministic} scenario produced with the files the original left, by name.
     * Same-named files pair first. Every other name pairs by its {@link #fileNameShape shape}: both sides must hold
     * each shape the same number of times, and the names of one shape pair in sorted order.
     *
     * @param what     the scenario and directory a mismatch names
     * @param expected the names of the files the original left
     * @param actual   the names of the files the replay produced
     * @return each expected name with the actual name it pairs with, the same-named pairs first
     * @throws AssertionError when the names not paired by name differ in their shapes or in how many names of a
     *                        shape each side holds, naming both sides' files and the shapes of those names
     */
    static Map<String, String> pairFileNames(String what, Set<String> expected, Set<String> actual) {
        Map<String, String> pairs = new LinkedHashMap<>();
        Map<String, List<String>> expectedByShape = new TreeMap<>();
        for (String fileName : new TreeSet<>(expected)) {
            if (actual.contains(fileName)) {
                pairs.put(fileName, fileName);
            } else {
                expectedByShape.computeIfAbsent(fileNameShape(fileName), shape -> new ArrayList<>()).add(fileName);
            }
        }
        Map<String, List<String>> actualByShape = new TreeMap<>();
        for (String fileName : new TreeSet<>(actual)) {
            if (!expected.contains(fileName)) {
                actualByShape.computeIfAbsent(fileNameShape(fileName), shape -> new ArrayList<>()).add(fileName);
            }
        }
        boolean sameShapes = expectedByShape.keySet().equals(actualByShape.keySet());
        for (Map.Entry<String, List<String>> shape : expectedByShape.entrySet()) {
            List<String> actualNames = actualByShape.get(shape.getKey());
            sameShapes = sameShapes && actualNames.size() == shape.getValue().size();
        }
        if (!sameShapes) {
            throw new AssertionError(what + " holds " + new TreeSet<>(actual) + " but the original left "
                    + new TreeSet<>(expected) + " (the names not paired by name, by shape: replay " + actualByShape
                    + ", original " + expectedByShape + ")");
        }
        for (Map.Entry<String, List<String>> shape : expectedByShape.entrySet()) {
            List<String> actualNames = actualByShape.get(shape.getKey());
            for (int i = 0; i < actualNames.size(); i++) {
                pairs.put(shape.getValue().get(i), actualNames.get(i));
            }
        }
        return pairs;
    }

    /**
     * The shape a file name pairs by in a {@code nondeterministic} scenario: each {@link #CANONICAL_UUID canonical
     * UUID} becomes {@link #UUID_TOKEN}, then each remaining run of digits becomes {@code #}, and every other
     * character stays as written, so prefixes, fixed segments and suffixes must match exactly. A {@code \},
     * {@code #} or {@code <} of the name itself is written with a {@code \} before it, so no character of a name
     * reads as a token.
     */
    static String fileNameShape(String fileName) {
        StringBuilder shape = new StringBuilder();
        Matcher uuid = CANONICAL_UUID.matcher(fileName);
        int fixedStart = 0;
        while (uuid.find()) {
            shape.append(maskDigits(escapeShapeTokens(fileName.substring(fixedStart, uuid.start()))));
            shape.append(UUID_TOKEN);
            fixedStart = uuid.end();
        }
        return shape.append(maskDigits(escapeShapeTokens(fileName.substring(fixedStart)))).toString();
    }

    private static String escapeShapeTokens(String fixedPart) {
        return fixedPart.replace("\\", "\\\\").replace("#", "\\#").replace("<", "\\<");
    }

    private static String maskDigits(String fileName) {
        return DIGIT_RUN.matcher(fileName).replaceAll("#");
    }

    private static String stripLeadingSlash(String resource) {
        return resource.startsWith("/") ? resource.substring(1) : resource;
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static synchronized List<Path> registeredDirectories() {
        return List.copyOf(DIRECTORIES);
    }

    /** Deletes everything below {@code root}, and {@code root} itself when {@code includeRoot} is set. */
    static void deleteTree(Path root, boolean includeRoot) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (includeRoot || !path.equals(root)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
