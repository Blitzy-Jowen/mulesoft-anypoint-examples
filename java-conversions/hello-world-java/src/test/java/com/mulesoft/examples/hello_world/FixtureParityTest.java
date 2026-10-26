package com.mulesoft.examples.hello_world;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
 * {@link DynamicPropertySource}. {@code deterministic} scenarios are compared byte for byte without
 * normalization; {@code nondeterministic} ones compare status, reason and headers exactly and bodies, files and
 * messages by structure only, and are reported {@code UNVERIFIED — nondeterministic output}. A fixture is never
 * adjusted to the Java output: a mismatch fails its scenario.
 *
 * <p>With no fixtures the class is reported skipped, never failed. Every run writes
 * {@code target/fixture-parity/summary.properties} with each scenario's status, which feeds the {@code PARITY:}
 * labels of the README and of {@code SUMMARY.md} (AAP 0.8.7). {@code -Dfixtures.dir=<dir>} replaces the classpath
 * fixtures with {@code <dir>/*.json} (and {@code <dir>/SCENARIOS.md} when present) for local engine checks.
 */
@ExtendWith(FixtureParityTest.FixtureGate.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    private static final List<String> MAIL_PROTOCOLS = List.of("smtp", "smtps", "imap", "imaps", "pop3", "pop3s");

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern SEPARATOR_CELL = Pattern.compile(":?-+:?");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern GREENMAIL_PORT =
            Pattern.compile("greenmail\\.(smtp|smtps|imap|imaps|pop3|pop3s)\\.port");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]+");
    private static final Pattern BOUNDARY_PARAMETER =
            Pattern.compile(";\\s*boundary\\s*=\\s*(\"[^\"]*\"|[^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLDED_LINE = Pattern.compile("\\r?\\n[ \\t]+");

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
    private static final String MONGO_SERVER_CLASS = "de.bwaldvogel.mongo.MongoServer";
    private static final String MONGO_BACKEND_CLASS = "de.bwaldvogel.mongo.backend.memory.MemoryBackend";
    private static final String MONGO_TEMPLATE_CLASS = "org.springframework.data.mongodb.core.MongoTemplate";
    private static final String BSON_DOCUMENT_CLASS = "org.bson.Document";
    private static final String LDAP_SERVER_CLASS = "com.unboundid.ldap.listener.InMemoryDirectoryServer";
    private static final String LDAP_CONFIG_CLASS = "com.unboundid.ldap.listener.InMemoryDirectoryServerConfig";
    private static final String LDAP_LISTENER_CLASS = "com.unboundid.ldap.listener.InMemoryListenerConfig";
    private static final String SAX_ERROR_HANDLER_CLASS = "org.xml.sax.ErrorHandler";

    private static final Path SUMMARY_FILE = Path.of("target", "fixture-parity", "summary.properties");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** Injected before {@code @BeforeAll}; {@link #tempRoot()} creates its own directory when used earlier. */
    @TempDir
    static Path tempDir;

    private static final Map<String, String> STATUSES = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Ports by name; {@code ${freePort:<name>}} and the embedded servers share these names. */
    private static final Map<String, Integer> FREE_PORTS = new HashMap<>();
    /** Every {@code ${dir:<name>}} directory, emptied after each scenario. */
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

    /** The embedded servers a replay talks to. */
    record Needs(boolean wireMock, boolean greenMail, boolean qpid, boolean mongo, boolean ldap) {

        static final Needs NONE = new Needs(false, false, false, false, false);

        Needs or(Needs other) {
            return new Needs(wireMock || other.wireMock, greenMail || other.greenMail, qpid || other.qpid,
                    mongo || other.mongo, ldap || other.ldap);
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
        boolean wire = json.path("upstream").size() > 0
                || placeholders.stream().anyMatch(name -> name.startsWith("wiremock."));
        boolean mail = row.trigger().equals("mail") || state.has("mail") || response.has("mails")
                || placeholders.stream().anyMatch(name -> name.startsWith("greenmail."));
        boolean amqp = row.trigger().equals("amqp") || state.has("amqp");
        boolean mongo = state.has("collections") || response.has("collections") || placeholders.contains("mongo.uri");
        boolean ldap = state.has("ldap") || placeholders.contains("ldap.port");
        return new Needs(wire, mail, amqp, mongo, ldap);
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
            return directory(name.substring("dir:".length()), true).toString();
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

    /** A directory key or {@code request.file.directory}: a value without {@code ${} means {@code ${dir:<value>}}. */
    static Path resolveDirectory(String spec, String fixture) {
        return spec.contains("${") ? Path.of(resolve(spec, fixture)) : directory(spec, true);
    }

    /** {@code <temporary root>/<name>}, created; {@code register} adds it to the directories emptied per scenario. */
    static synchronized Path directory(String name, boolean register) {
        try {
            Path root = tempRoot();
            Path directory = root.resolve(name).normalize();
            if (!directory.startsWith(root) || directory.equals(root)) {
                throw new IllegalStateException("${dir:" + name + "} must name a directory inside " + root);
            }
            Files.createDirectories(directory);
            if (register) {
                DIRECTORIES.add(directory);
            }
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the replay directory " + name, e);
        }
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

    /** A free port, the same one for every use of {@code name}. */
    static synchronized int freePort(String name) {
        Integer port = FREE_PORTS.get(name);
        if (port == null) {
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot reserve a free port for " + name, e);
            }
            FREE_PORTS.put(name, port);
        }
        return port;
    }


    // ---------------------------------------------------------------------------------------------------------
    // Embedded servers, started in load() only when a fixture needs them and stopped after the class.
    // ---------------------------------------------------------------------------------------------------------

    private static void startServers() throws Exception {
        Needs union = Needs.NONE;
        for (Fixture fixture : fixtures.values()) {
            union = union.or(fixture.needs());
        }
        if (union.wireMock()) {
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
        // Every protocol on a port named greenmail.<protocol>, so ${greenmail.<protocol>.port} is known before
        // the application context starts.
        Object setups = Array.newInstance(setupClass, MAIL_PROTOCOLS.size());
        for (int i = 0; i < MAIL_PROTOCOLS.size(); i++) {
            String protocol = MAIL_PROTOCOLS.get(i);
            Array.set(setups, i,
                    Reflect.construct(setupClass, freePort("greenmail." + protocol), "127.0.0.1", protocol));
        }
        Object server = Reflect.construct(greenMailClass, setups);
        Reflect.call(server, "start");
        greenMail = server;
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
        if (launcherClass == null) {
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
        brokerContext.put("qpid.amqp_port", String.valueOf(freePort("amqp")));
        // Not registered for per-scenario emptying: the running broker keeps its store and logs there.
        brokerContext.put("qpid.work_dir", directory("qpid-work", false).toString());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("type", "Memory");
        attributes.put("initialConfigurationLocation", configuration.toExternalForm());
        attributes.put("startupLoggedToSystemOut", false);
        attributes.put("context", brokerContext);
        Object launcher = Reflect.construct(launcherClass);
        Reflect.call(launcher, "startup", attributes);
        qpid = launcher;
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
        Object listener = Reflect.callStatic(listenerClass, "createLDAPConfig", "default", freePort("ldap"));
        Reflect.call(config, "setListenerConfigs", List.of(listener));
        Object server = Reflect.construct(serverClass, config);
        Reflect.call(server, "startListening");
        ldapServer = server;
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

    /** One scenario's replay: seeds its state, drives its trigger, compares every captured effect, then resets. */
    static final class Replay {

        private enum Broker { NONE, JMS, AMQP }

        private final ApplicationContext context;
        private final Row row;
        private final Fixture fixture;
        private final String name;
        private final JsonNode request;
        private final JsonNode state;
        private final JsonNode response;
        private final long awaitMillis;
        private final List<Object> deliveredMails = new ArrayList<>();
        private final Map<String, Object> jmsConsumers = new LinkedHashMap<>();
        private final Set<String> jmsRequestQueues = new LinkedHashSet<>();
        private final Set<String> amqpQueues = new LinkedHashSet<>();
        private Broker broker = Broker.NONE;
        private Object jmsConnection;
        private Object jmsSession;
        private Object rabbitTemplate;

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
        }

        void execute() throws Exception {
            requireDrivers();
            ensureAppender();
            clearAppender();
            prepareMessages();
            seedState();
            registerUpstream();
            try {
                switch (row.trigger()) {
                    case "http", "soap" -> replayHttp();
                    case "file", "scheduler" -> replayFile();
                    case "jms", "mq" -> replayJms();
                    case "amqp" -> replayAmqp();
                    case "mail" -> replayMail();
                    default -> throw new IllegalStateException(at("no replay driver for trigger " + row.trigger()));
                }
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

        /** Returns every shared server and store to the state the next scenario expects. */
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
            attempt(failures, this::drainQueues);
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

        private void requireDrivers() {
            Needs needs = fixture.needs();
            requireServer(needs.greenMail(), "greenmail", greenMail);
            requireServer(needs.qpid(), "qpid", qpid);
            requireServer(needs.mongo(), "mongo", mongoServer);
            requireServer(needs.ldap(), "ldap", ldapServer);
            if (row.trigger().equals("mail") || response.has("mails")) {
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
        }

        /** Writes the file beside the target first and moves it in, so a poller never reads it half-written. */
        private void writeFile(Path directory, JsonNode file, String what) throws IOException {
            String fileName = text(file, "name", what + "[].name");
            Path target = directory.resolve(fileName).normalize();
            if (!target.startsWith(directory) || target.equals(directory)) {
                throw new IllegalStateException(what + ": file name " + fileName + " leaves its directory");
            }
            Files.createDirectories(target.getParent());
            Path staged = Files.createTempFile(tempRoot(), "staged-", ".tmp");
            Files.write(staged, bodyBytes(file, what + "." + fileName));
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void seedTables() throws Exception {
            DataSource dataSource = (DataSource) requireBean(DataSource.class, DataSource.class.getName());
            try (Connection connection = dataSource.getConnection()) {
                for (Map.Entry<String, JsonNode> table : entries(state.get("tables"), at("state.tables"))) {
                    String tableName = identifier(table.getKey());
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
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String table : tables) {
                    statement.executeUpdate("DELETE FROM " + table);
                }
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            }
        }

        private void seedCollections() throws Exception {
            Object template = requireBean(requireClass(MONGO_TEMPLATE_CLASS), MONGO_TEMPLATE_CLASS);
            Class<?> document = requireClass(BSON_DOCUMENT_CLASS);
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
                return;
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

        private void replayHttp() throws Exception {
            Class<?> clientClass = requireClass(CLIENT_CLASS);
            String portProperty = request.hasNonNull("portProperty")
                    ? text(request, "portProperty", at("request.portProperty")) : DEFAULT_PORT_PROPERTY;
            Integer port = context.getEnvironment().getProperty(portProperty, Integer.class);
            if (port == null) {
                missingDriver("the listener port property " + portProperty);
                return;
            }
            Object client = request.hasNonNull("tls")
                    ? httpsClient(clientClass, port, object(request.get("tls"), at("request.tls")))
                    : Reflect.callStatic(clientClass, "localhost", port);
            String method = text(request, "method", at("request.method"));
            String path = text(request, "path", at("request.path"));
            String query = request.hasNonNull("query") ? text(request, "query", at("request.query")) : "";
            String target = query.isEmpty() ? path : path + "?" + query;
            List<Map.Entry<String, String>> headers = new ArrayList<>();
            boolean framed = false;
            for (Map.Entry<String, String> header : headerPairs(request.get("headers"), at("request.headers"))) {
                String headerName = lower(header.getKey());
                framed |= headerName.equals("content-length") || headerName.equals("transfer-encoding");
                if (!TRANSPORT_HEADERS.contains(headerName)) {
                    headers.add(header);
                }
            }
            byte[] body = bodyBytes(request, at("request"));
            // An empty body goes out without Content-Length unless the captured request framed one, as curl did.
            Method send = Reflect.method(clientClass, "send", 4);
            Object reply = send(clientClass, client, method, target, headers, body.length == 0 && !framed ? null : body);
            compareHttpResponse(send.getReturnType(), reply, port);
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

        private Object httpsClient(Class<?> clientClass, int port, JsonNode tls) throws Exception {
            String trustStore = text(tls, "trustStore", at("request.tls.trustStore"));
            String type = tls.hasNonNull("type") ? text(tls, "type", at("request.tls.type")) : "JKS";
            String password = tls.hasNonNull("password") ? text(tls, "password", at("request.tls.password")) : null;
            return Reflect.callStatic(clientClass, "https", "localhost", port, trustStorePath(trustStore), type,
                    password);
        }

        /** The trust store resolved from the classpath first, then as a file, in the form ST-1 opens. */
        private String trustStorePath(String trustStore) throws URISyntaxException {
            URL resource = Reflect.loader().getResource(stripLeadingSlash(trustStore));
            if (resource != null) {
                // ST-1 tries a file before the classpath, so a classpath store is handed over as its file path,
                // or by resource name when it sits inside a jar.
                return "file".equals(resource.getProtocol()) ? Path.of(resource.toURI()).toString()
                        : stripLeadingSlash(trustStore);
            }
            Path file = Path.of(trustStore);
            if (Files.isRegularFile(file)) {
                return file.toAbsolutePath().toString();
            }
            throw new IllegalStateException(at("request.tls.trustStore " + trustStore
                    + " is neither on the test classpath nor a file"));
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
            deliveredMails.add(message);
            invokeIfPresent();
        }

        // ----- messages --------------------------------------------------------------------------------------

        /**
         * Opens the broker side before the trigger runs: expected queues are drained and topic subscribers
         * created, so only messages the scenario produces are compared. HTTP- or file-triggered flows that
         * publish use the project's RabbitTemplate when it has one, otherwise its JMS connection factory.
         */
        private void prepareMessages() throws Exception {
            switch (row.trigger()) {
                case "amqp" -> broker = Broker.AMQP;
                case "jms", "mq" -> broker = Broker.JMS;
                default -> {
                    if (!response.has("messages")) {
                        return;
                    }
                    Class<?> rabbit = Reflect.optionalClass(RABBIT_TEMPLATE_CLASS);
                    broker = rabbit != null && context.getBeanProvider(rabbit).getIfAvailable() != null
                            ? Broker.AMQP : Broker.JMS;
                }
            }
            List<JsonNode> messages = elements(response.get("messages"), at("response.messages"));
            if (broker == Broker.AMQP) {
                rabbitTemplate = requireBean(requireClass(RABBIT_TEMPLATE_CLASS), RABBIT_TEMPLATE_CLASS);
                for (JsonNode message : messages) {
                    amqpQueues.add(text(message, "destination", at("response.messages[].destination")));
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
                for (JsonNode message : messages) {
                    consumer(text(message, "destination", at("response.messages[].destination")),
                            message.path("pubSub").asBoolean(false));
                }
            }
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

        /** The consumer of a destination, created on first use; a new queue consumer drains the queue first. */
        private Object consumer(String destination, boolean pubSub) throws Exception {
            String key = (pubSub ? "topic " : "queue ") + destination;
            Object consumer = jmsConsumers.get(key);
            if (consumer == null) {
                Object target = Reflect.call(jmsSession, pubSub ? "createTopic" : "createQueue", destination);
                consumer = Reflect.call(jmsSession, "createConsumer", target);
                jmsConsumers.put(key, consumer);
                if (!pubSub) {
                    drainJms(consumer);
                }
            }
            return consumer;
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
                    for (String queue : jmsRequestQueues) {
                        consumer(queue, false);
                    }
                    for (Map.Entry<String, Object> consumer : jmsConsumers.entrySet()) {
                        if (consumer.getKey().startsWith("queue ")) {
                            drainJms(consumer.getValue());
                        }
                    }
                } finally {
                    Object connection = jmsConnection;
                    jmsConnection = null;
                    jmsSession = null;
                    jmsConsumers.clear();
                    Reflect.call(connection, "close");
                }
            }
        }

        private void verifyMessages() throws Exception {
            List<JsonNode> expected = elements(response.get("messages"), at("response.messages"));
            for (int i = 0; i < expected.size(); i++) {
                JsonNode message = expected.get(i);
                String what = at("response.messages[" + i + "]");
                String destination = text(message, "destination", what + ".destination");
                String label = "message " + (i + 1) + " on " + destination;
                Object received;
                byte[] body;
                if (broker == Broker.AMQP) {
                    received = Reflect.call(rabbitTemplate, "receive", destination, awaitMillis);
                    body = received == null ? null : (byte[]) Reflect.call(received, "getBody");
                } else {
                    received = Reflect.call(consumer(destination, message.path("pubSub").asBoolean(false)), "receive",
                            awaitMillis);
                    body = received == null ? null : jmsBody(received);
                }
                if (received == null) {
                    throw new AssertionError(name + ": " + label + " did not arrive within " + awaitMillis + " ms");
                }
                compareContent(label, bodyBytes(message, what), body, null);
                for (Map.Entry<String, JsonNode> property : entries(message.get("properties"), what + ".properties")) {
                    Object actual = broker == Broker.AMQP ? amqpProperty(received, property.getKey())
                            : Reflect.call(received, "getObjectProperty", property.getKey());
                    if (!scalarEquals(property.getValue(), actual)) {
                        throw new AssertionError(name + ": " + label + " property " + property.getKey() + " expected "
                                + property.getValue() + " but was " + (actual == null ? "absent" : actual));
                    }
                }
            }
            if (broker == Broker.AMQP) {
                for (String queue : amqpQueues) {
                    Object extra = Reflect.call(rabbitTemplate, "receive", queue, NO_FURTHER_MESSAGE_MILLIS);
                    if (extra != null) {
                        throw new AssertionError(name + ": an unexpected further message arrived on queue " + queue
                                + ": " + Content.render((byte[]) Reflect.call(extra, "getBody")));
                    }
                }
            } else {
                for (Map.Entry<String, Object> consumer : jmsConsumers.entrySet()) {
                    Object extra = Reflect.call(consumer.getValue(), "receive", NO_FURTHER_MESSAGE_MILLIS);
                    if (extra != null) {
                        throw new AssertionError(name + ": an unexpected further message arrived on "
                                + consumer.getKey() + ": " + Content.render(jmsBody(extra)));
                    }
                }
            }
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
            if (response.has("messages")) {
                verifyMessages();
            }
            if (response.has("mails")) {
                awaitMatch("mails", this::checkMails);
            }
            if (response.has("logs")) {
                awaitMatch("logs", this::checkLogs);
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

        /** Same-named files pair up; the rest pair in sorted order and must agree with digit runs read as {@code #}. */
        private void compareFileStructure(String label, Map<String, byte[]> expected, Map<String, byte[]> actual) {
            if (expected.size() != actual.size()) {
                throw new AssertionError(name + ": " + label + " holds " + actual.keySet() + " but the original left "
                        + expected.keySet());
            }
            List<String> unpairedExpected = new ArrayList<>();
            for (Map.Entry<String, byte[]> file : expected.entrySet()) {
                if (actual.containsKey(file.getKey())) {
                    compareContent(label + "/" + file.getKey(), file.getValue(), actual.get(file.getKey()), null);
                } else {
                    unpairedExpected.add(file.getKey());
                }
            }
            List<String> unpairedActual = new ArrayList<>();
            for (String fileName : actual.keySet()) {
                if (!expected.containsKey(fileName)) {
                    unpairedActual.add(fileName);
                }
            }
            for (int i = 0; i < unpairedExpected.size(); i++) {
                String expectedName = unpairedExpected.get(i);
                String actualName = unpairedActual.get(i);
                if (!maskDigits(expectedName).equals(maskDigits(actualName))) {
                    throw new AssertionError(name + ": " + label + " holds " + actualName + " where the original left "
                            + expectedName + " (names compared with digit runs as #)");
                }
                compareContent(label + "/" + actualName, expected.get(expectedName), actual.get(actualName), null);
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

        private void checkMails() throws Exception {
            List<MailView> actual = new ArrayList<>();
            for (Object received : (Object[]) Reflect.call(greenMail, "getReceivedMessages")) {
                actual.add(MailView.of(received));
            }
            // Mails this replay delivered as its input sit in their mailbox too; they are not output.
            for (Object delivered : deliveredMails) {
                actual.remove(MailView.of(delivered));
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

        private void checkLogs() {
            List<JsonNode> expected = elements(response.get("logs"), at("response.logs"));
            List<ILoggingEvent> events = loggedEvents();
            int next = 0;
            for (ILoggingEvent event : events) {
                if (next < expected.size() && logMatches(expected.get(next), next, event)) {
                    next++;
                }
            }
            if (next < expected.size()) {
                throw new AssertionError(name + ": the log does not hold, in order, entry " + (next + 1) + " of "
                        + expected.size() + " " + expected.get(next) + "\n  logged: " + describeEvents(events));
            }
        }

        private boolean logMatches(JsonNode entry, int index, ILoggingEvent event) {
            String what = at("response.logs[" + index + "]");
            String message = entry.hasNonNull("message") ? text(entry, "message", what + ".message") : "";
            String formatted = event.getFormattedMessage() == null ? "" : event.getFormattedMessage();
            boolean messageMatches = row.deterministic() ? message.equals(formatted)
                    : message.isEmpty() == formatted.isEmpty();
            return messageMatches
                    && text(entry, "logger", what + ".logger").equals(event.getLoggerName())
                    && text(entry, "level", what + ".level").equals(String.valueOf(event.getLevel()));
        }

        private static String describeEvents(List<ILoggingEvent> events) {
            StringBuilder described = new StringBuilder();
            int first = Math.max(0, events.size() - 50);
            for (ILoggingEvent event : events.subList(first, events.size())) {
                described.append("\n    ").append(event.getLevel()).append(' ').append(event.getLoggerName())
                        .append(" - ").append(event.getFormattedMessage());
            }
            return events.isEmpty() ? "(nothing)" : described.toString();
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
                JsonNode expectedTree = parseJson(expected);
                if (expectedTree != null) {
                    JsonNode actualTree = parseJson(actual);
                    return actualTree == null ? "the actual content is not JSON"
                            : jsonDifference(expectedTree, actualTree, "$");
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

        private static JsonNode parseJson(byte[] content) {
            if (content.length == 0) {
                return null;
            }
            try {
                JsonNode tree = JSON.readTree(content);
                return tree == null || tree.isMissingNode() ? null : tree;
            } catch (IOException notJson) {
                return null;
            }
        }

        /** Key names in order, value kinds and array lengths, recursively. */
        private static String jsonDifference(JsonNode expected, JsonNode actual, String path) {
            if (expected.getNodeType() != actual.getNodeType()) {
                return path + ": expected " + expected.getNodeType() + " but was " + actual.getNodeType();
            }
            if (expected.isObject()) {
                List<String> expectedKeys = fieldNames(expected);
                List<String> actualKeys = fieldNames(actual);
                if (!expectedKeys.equals(actualKeys)) {
                    return path + ": expected keys " + expectedKeys + " but was " + actualKeys;
                }
                for (String key : expectedKeys) {
                    String difference = jsonDifference(expected.get(key), actual.get(key), path + "." + key);
                    if (difference != null) {
                        return difference;
                    }
                }
            } else if (expected.isArray()) {
                if (expected.size() != actual.size()) {
                    return path + ": expected " + expected.size() + " elements but was " + actual.size();
                }
                for (int i = 0; i < expected.size(); i++) {
                    String difference = jsonDifference(expected.get(i), actual.get(i), path + "[" + i + "]");
                    if (difference != null) {
                        return difference;
                    }
                }
            }
            return null;
        }

        private static List<String> fieldNames(JsonNode node) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
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

