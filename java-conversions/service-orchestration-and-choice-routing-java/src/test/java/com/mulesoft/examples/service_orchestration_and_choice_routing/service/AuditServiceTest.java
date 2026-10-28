/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.sql.DatabaseMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Stream;

import org.apache.derby.jdbc.EmbeddedDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.arjuna.ats.internal.jdbc.drivers.modifiers.ConnectionModifier;
import com.arjuna.ats.internal.jdbc.drivers.modifiers.ModifierFactory;
import com.arjuna.ats.internal.jdbc.drivers.modifiers.SupportsMultipleConnectionsModifier;
import com.mulesoft.examples.service_orchestration_and_choice_routing.ServiceOrchestrationAndChoiceRoutingApplication;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;
import com.mulesoft.examples.service_orchestration_and_choice_routing.support.PortInUseRetryingContextLoader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests of {@link AuditService}, the {@code auditService} flow of {@code fulfillment.xml} (lines 130-143): the
 * {@code order_audits} insert and its {@code choice-exception-strategy}, rollback when {@code totalValue > 5000},
 * otherwise catch and run {@code defaultErrorHandler}.
 *
 * <p>Four layers:
 * <ul>
 *   <li>Mockito, with the service built directly: the bound insert, each exception branch, the threshold condition and
 *       the form of the failure log line.</li>
 *   <li>{@link RealDatabaseFailure}: embedded in-memory Derby with the original {@code order_audits} DDL, where an
 *       order ID longer than {@code VARCHAR(256)} fails the insert with a message quoting the ID.</li>
 *   <li>{@link AsyncRollbackRoute}: a Spring context with {@code @EnableAsync}, where the failure the rollback branch
 *       rethrows reaches {@link AuditService.AsyncFailureLogging}'s handler on the executor thread.</li>
 *   <li>{@link InApplicationContext}: the whole application, where the audit dispatched through its {@code @Async}
 *       and {@code REQUIRES_NEW} proxy commits its row in its own JTA transaction over XA Derby, and concurrent audits
 *       release their Derby transactions when they complete.</li>
 * </ul>
 *
 * <p>Failures carry a sentinel built at run time from a random UUID, standing for a sensitive caller-supplied order
 * ID; no log event may contain it or carry a throwable, which Logback would print with its message and causes.
 * Loggers are captured with Logback {@link ListAppender}s at INFO, detached and restored after each test.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    private static final BigDecimal ROLLBACK_THRESHOLD = new BigDecimal("5000");

    private static final String ORDER_ID = "12";

    /** Unique to the run and absent from every source file, so finding it in a log proves a leak. */
    private static final String SENTINEL = "ord-" + "SENTINEL-" + UUID.randomUUID();

    /** {@code Create order_audits Table} (fulfillment.xml:166). */
    private static final String ORDER_AUDITS_DDL = "CREATE TABLE order_audits "
            + "(i int generated always as identity, order_id varchar(256), total_value integer)";

    private static final String FAILURE_LOG_PREFIX = "Audit insert into order_audits failed: ";

    private static final String ASYNC_THREAD_PREFIX = "audit-async-";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectProvider<OrderOrchestrationService> orderOrchestrationProvider;

    @Mock
    private OrderOrchestrationService orderOrchestrationService;

    private LogCapture auditLog;

    private LogCapture orchestrationLog;

    @BeforeEach
    void captureLogs() {
        auditLog = new LogCapture(AuditService.class);
        orchestrationLog = new LogCapture(OrderOrchestrationService.class);
    }

    @AfterEach
    void releaseLogs() {
        try {
            auditLog.close();
        } finally {
            orchestrationLog.close();
        }
    }

    @Test
    void insertCarriesOrderIdAndTotalValue() {
        service().audit(order(ORDER_ID), "02550");

        verify(jdbcTemplate).update(AuditService.INSERT_ORDER_AUDIT, ORDER_ID, "02550");
        verifyNoInteractions(orderOrchestrationProvider, orderOrchestrationService);
        assertThat(auditLog.events()).isEmpty();
    }

    @Test
    void failureAboveThresholdIsRethrown() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(SENTINEL);
        when(jdbcTemplate.update(AuditService.INSERT_ORDER_AUDIT, ORDER_ID, "6000")).thenThrow(failure);

        assertThatThrownBy(() -> service().audit(order(ORDER_ID), "6000")).isSameAs(failure);

        verifyNoInteractions(orderOrchestrationProvider, orderOrchestrationService);
        ILoggingEvent logged = onlyEvent(auditLog, Level.ERROR);
        assertThat(logged.getFormattedMessage())
                .startsWith(FAILURE_LOG_PREFIX + DataAccessResourceFailureException.class.getName())
                .doesNotContain(SENTINEL);
        assertThat(logged.getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2550", "abc"})
    void failureAtOrBelowThresholdIsLoggedAndSwallowed(String totalValue) {
        when(jdbcTemplate.update(AuditService.INSERT_ORDER_AUDIT, ORDER_ID, totalValue))
                .thenThrow(new DataAccessResourceFailureException(SENTINEL));
        when(orderOrchestrationProvider.getObject()).thenReturn(orderOrchestrationService);
        Order order = order(ORDER_ID);

        assertThatCode(() -> service().audit(order, totalValue)).doesNotThrowAnyException();

        verify(orderOrchestrationService).logError(order);
        ILoggingEvent logged = onlyEvent(auditLog, Level.ERROR);
        assertThat(logged.getFormattedMessage())
                .startsWith(FAILURE_LOG_PREFIX + DataAccessResourceFailureException.class.getName())
                .doesNotContain(SENTINEL);
        assertThat(logged.getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"5000, false", "5001, true", "5000.5, true", ", false", "abc, false"})
    void thresholdBoundary(String totalValue, boolean exceeds) {
        assertThat(service().exceedsRollbackThreshold(totalValue)).isEqualTo(exceeds);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureDescriptions")
    void failureIsLoggedAsTypeIdentifiersOnly(String description, RuntimeException failure, String expected) {
        // An answer that throws keeps the crafted stack trace, which thenThrow would refill.
        when(jdbcTemplate.update(AuditService.INSERT_ORDER_AUDIT, ORDER_ID, "6000")).thenAnswer(invocation -> {
            throw failure;
        });

        assertThatThrownBy(() -> service().audit(order(ORDER_ID), "6000")).isSameAs(failure);

        ILoggingEvent logged = onlyEvent(auditLog, Level.ERROR);
        assertThat(logged.getFormattedMessage()).isEqualTo(FAILURE_LOG_PREFIX + expected);
        assertThat(logged.getThrowableProxy()).isNull();
    }

    static Stream<Arguments> failureDescriptions() {
        RuntimeException circular = withTrace(new RuntimeException(SENTINEL));
        circular.initCause(new IllegalStateException(SENTINEL, circular));
        return Stream.of(
                Arguments.of("no stack trace",
                        withTrace(new IllegalStateException(SENTINEL)),
                        "java.lang.IllegalStateException"),
                Arguments.of("throw site and causes in order",
                        withTrace(new DataIntegrityViolationException(SENTINEL, new SQLDataException(SENTINEL,
                                        "22001", new IllegalArgumentException(SENTINEL))),
                                new StackTraceElement("com.example.AuditDao", "insert", "AuditDao.java", 42)),
                        "org.springframework.dao.DataIntegrityViolationException at com.example.AuditDao.insert"
                                + "(AuditDao.java:42) <- java.sql.SQLDataException"
                                + " <- java.lang.IllegalArgumentException"),
                Arguments.of("native method",
                        withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("com.example.Native", "call", "Native.java", -2)),
                        "java.lang.IllegalStateException at com.example.Native.call(Native Method)"),
                Arguments.of("unknown source",
                        withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("com.example.Generated", "run", null, -1)),
                        "java.lang.IllegalStateException at com.example.Generated.run(Unknown Source)"),
                Arguments.of("file without line number",
                        withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("com.example.AuditDao", "insert", "AuditDao.java", -1)),
                        "java.lang.IllegalStateException at com.example.AuditDao.insert(AuditDao.java)"),
                Arguments.of("circular cause chain",
                        circular,
                        "java.lang.RuntimeException <- java.lang.IllegalStateException <- [circular]"),
                Arguments.of("ten causes, all named",
                        withTrace(new RuntimeException(SENTINEL, causeChain(10))),
                        "java.lang.RuntimeException" + " <- java.lang.IllegalStateException".repeat(9)
                                + " <- java.lang.IllegalArgumentException"),
                Arguments.of("eleven causes, cut after ten",
                        withTrace(new RuntimeException(SENTINEL, causeChain(11))),
                        "java.lang.RuntimeException" + " <- java.lang.IllegalStateException".repeat(10) + " <- ..."),
                Arguments.of("names escaped and bounded",
                        withTrace(new IllegalStateException(SENTINEL),
                                new StackTraceElement("com.example.\nForged\u202eName\\", "m\u0000\ud83d\ude00\ud800",
                                        "F".repeat(300), 7)),
                        "java.lang.IllegalStateException at com.example.\\u000aForged\\u202eName\\u005c"
                                + ".m\\u0000\ud83d\ude00\\ud800(" + "F".repeat(256) + "...[truncated]:7)"));
    }

    private AuditService service() {
        return new AuditService(jdbcTemplate, orderOrchestrationProvider, ROLLBACK_THRESHOLD);
    }

    /**
     * The finding's failure from a real database: embedded in-memory Derby with the original {@code order_audits} DDL
     * rejects an order ID longer than {@code VARCHAR(256)} with SQLState 22001, and its message quotes the rejected
     * value. Each test gets its own database, dropped afterwards; Derby writes only its log, under the
     * {@code derby.system.home} surefire sets inside {@code target/}.
     */
    @Nested
    class RealDatabaseFailure {

        private String databaseName;

        private JdbcTemplate derby;

        @BeforeEach
        void createAuditTable() {
            databaseName = "memory:auditServiceTest-" + UUID.randomUUID();
            EmbeddedDataSource dataSource = new EmbeddedDataSource();
            dataSource.setDatabaseName(databaseName);
            dataSource.setCreateDatabase("create");
            derby = new JdbcTemplate(dataSource);
            derby.execute(ORDER_AUDITS_DDL);
        }

        @AfterEach
        void dropDatabase() {
            EmbeddedDataSource drop = new EmbeddedDataSource();
            drop.setDatabaseName(databaseName);
            drop.setConnectionAttributes("drop=true");
            // Derby reports a successful drop as SQLState 08006.
            assertThatThrownBy(drop::getConnection)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("08006"));
        }

        @Test
        void databaseMessageQuotesTheRejectedOrderId() {
            Throwable failure = catchThrowable(
                    () -> derby.update(AuditService.INSERT_ORDER_AUDIT, overlongSentinelId(), "02550"));

            assertThat(failure).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(SENTINEL);
            assertThat(failure.getCause()).isInstanceOfSatisfying(SQLException.class, cause -> {
                assertThat(cause.getSQLState()).isEqualTo("22001");
                assertThat(cause.getMessage()).contains(SENTINEL);
            });
        }

        @Test
        void catchBranchLogsTypeIdentifiersAndDefaultErrorHandlerLogsTheOrder() {
            // Real logError without running a constructor.
            OrderOrchestrationService defaultErrorHandler =
                    Mockito.mock(OrderOrchestrationService.class, Mockito.CALLS_REAL_METHODS);
            when(orderOrchestrationProvider.getObject()).thenReturn(defaultErrorHandler);
            Order order = order(overlongSentinelId());

            assertThatCode(() -> databaseService().audit(order, "02550")).doesNotThrowAnyException();

            ILoggingEvent failure = onlyEvent(auditLog, Level.ERROR);
            assertThat(failure.getFormattedMessage())
                    .startsWith(FAILURE_LOG_PREFIX + "org.springframework.dao.")
                    .doesNotContain(SENTINEL);
            assertThat(failure.getThrowableProxy()).isNull();
            ILoggingEvent handled = onlyEvent(orchestrationLog, Level.INFO);
            assertThat(handled.getMessage()).isEqualTo("Error occurred: {}");
            assertThat(handled.getFormattedMessage()).isEqualTo("Error occurred: " + String.valueOf(order));
            assertThat(handled.getThrowableProxy()).isNull();
            assertThat(derby.queryForObject("select count(*) from order_audits", Integer.class)).isZero();
        }

        @Test
        void rollbackBranchRethrowsTheFailureWithoutLoggingItsText() {
            Throwable thrown = catchThrowable(() -> databaseService().audit(order(overlongSentinelId()), "6000"));

            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(SENTINEL);
            ILoggingEvent failure = onlyEvent(auditLog, Level.ERROR);
            assertThat(failure.getFormattedMessage())
                    .startsWith(FAILURE_LOG_PREFIX + thrown.getClass().getName() + " at ")
                    .contains(" <- " + thrown.getCause().getClass().getName())
                    .doesNotContain(SENTINEL);
            assertThat(failure.getThrowableProxy()).isNull();
            verifyNoInteractions(orderOrchestrationProvider);
            assertThat(orchestrationLog.events()).isEmpty();
        }

        private AuditService databaseService() {
            return new AuditService(derby, orderOrchestrationProvider, ROLLBACK_THRESHOLD);
        }
    }

    /**
     * The rollback branch's rethrown failure on the asynchronous route: {@link AuditService#audit(Order, String)}
     * called through the {@code @Async} proxy, so the failure escapes on the executor thread to the handler of
     * {@link AuditService.AsyncFailureLogging}, the context's only {@code AsyncConfigurer}. Spring's default handler,
     * which would log the exception itself, must receive nothing.
     */
    @Nested
    @SpringJUnitConfig(AsyncRouteConfig.class)
    class AsyncRollbackRoute {

        @Autowired
        private AuditService proxiedAuditService;

        @Autowired
        private OrderOrchestrationService orchestrationBean;

        private LogCapture defaultHandlerLog;

        @BeforeEach
        void captureDefaultHandlerLog() {
            defaultHandlerLog = new LogCapture(SimpleAsyncUncaughtExceptionHandler.class);
        }

        @AfterEach
        void releaseDefaultHandlerLog() {
            defaultHandlerLog.close();
        }

        @Test
        void rethrownFailureIsLoggedWithoutItsTextOrTheOrder() {
            Order order = order(SENTINEL);

            proxiedAuditService.audit(order, "6000");

            List<ILoggingEvent> errors = await().atMost(Duration.ofSeconds(10))
                    .until(() -> auditLog.events(Level.ERROR), events -> events.size() >= 2);
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0).getFormattedMessage())
                    .startsWith(FAILURE_LOG_PREFIX + DataIntegrityViolationException.class.getName());
            ILoggingEvent uncaught = errors.get(1);
            assertThat(uncaught.getFormattedMessage())
                    .startsWith("Unexpected exception occurred invoking async method " + AuditService.class.getName()
                            + ".audit: " + DataIntegrityViolationException.class.getName() + " at ")
                    .endsWith(" <- " + SQLDataException.class.getName());
            assertThat(uncaught.getThreadName()).startsWith(ASYNC_THREAD_PREFIX);
            assertThat(errors).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SENTINEL).doesNotContain(String.valueOf(order));
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(defaultHandlerLog.events()).isEmpty();
            verifyNoInteractions(orchestrationBean);
        }
    }

    /**
     * {@code @EnableAsync} over the service and its {@code AsyncConfigurer}, with a {@code JdbcTemplate} whose insert
     * fails with a sentinel-bearing message and cause, and a {@code taskExecutor} naming its threads.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @Import({AuditService.class, AuditService.AsyncFailureLogging.class})
    static class AsyncRouteConfig {

        @Bean
        JdbcTemplate jdbcTemplate() {
            String derbyText = "A truncation error was encountered trying to shrink VARCHAR '" + SENTINEL
                    + "' to length 256.";
            JdbcTemplate failingTemplate = Mockito.mock(JdbcTemplate.class);
            when(failingTemplate.update(AuditService.INSERT_ORDER_AUDIT, SENTINEL, "6000"))
                    .thenThrow(new DataIntegrityViolationException("PreparedStatementCallback; " + derbyText,
                            new SQLDataException(derbyText, "22001")));
            return failingTemplate;
        }

        @Bean
        OrderOrchestrationService orderOrchestrationService() {
            return Mockito.mock(OrderOrchestrationService.class);
        }

        @Bean
        TaskExecutor taskExecutor() {
            return new SimpleAsyncTaskExecutor(ASYNC_THREAD_PREFIX);
        }
    }

    /**
     * The one-way VM {@code audit} endpoint with its own {@code xa-transaction ALWAYS_BEGIN}, in the running
     * application: {@link AuditService#audit(Order, String)} called through the application's {@code @Async} and
     * {@code @Transactional(REQUIRES_NEW)} proxy returns at once, and the insert commits in its own Narayana JTA
     * transaction over the XA Derby {@code DataSource}, visible to a later read outside any transaction. Concurrent
     * audits each release their Derby transaction and connection when their JTA transaction completes.
     *
     * <p>The application starts on free local ports with a fresh in-memory Derby database and an embedded,
     * non-persistent Artemis server of its own, and the context is closed after the class, so it shares no port,
     * database or broker with another test class in the JVM. The ports are drawn for each load attempt: a drawn port
     * is free when drawn, not when bound, so when another process takes one in between,
     * {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports. Derby's log and the
     * Narayana object store stay under the {@code target/} directories surefire sets.
     */
    @Nested
    @SpringBootTest(classes = ServiceOrchestrationAndChoiceRoutingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class InApplicationContext {

        /** Above the small server IDs Spring Boot's per-JVM counter and fixed test settings hand out. */
        private static final int MIN_ARTEMIS_SERVER_ID = 1_000_000;

        /**
         * Threads of the application's task executor, {@code spring.task.execution.pool.core-size}, in place of the
         * default eight: two concurrent audits already contend for one pooled connection. Narayana's connection pool
         * holds at most ten connections for the whole JVM and keeps those of a closed context, so each connection this
         * context opens is one fewer for the contexts of later test classes.
         */
        private static final int AUDIT_THREADS = 2;

        /** Audits dispatched at once and queued on the task executor. */
        private static final int CONCURRENT_AUDITS = 3000;

        /** Narayana's JDBC logger, which jboss-logging routes to Logback. */
        private static final String NARAYANA_JDBC_LOGGER = "com.arjuna.ats.jdbc";

        /** Narayana's {@code No modifier information found for db. Connection will be closed immediately}. */
        private static final String NO_MODIFIER_MESSAGE_ID = "ARJUNA017008";

        /**
         * The XA connection Narayana's recovery helper opens on the data source, outside Narayana's connection pool,
         * while a recovery scan runs.
         */
        private static final int RECOVERY_SCAN_CONNECTIONS = 1;

        /**
         * Most Derby transactions open once every audit has completed: {@value #AUDIT_THREADS} pooled connections and
         * the recovery scan's, three. Narayana opens another pooled connection only while every one it holds for the
         * data source is in use, so it holds as many as the threads that have used the database at once. While the
         * audits run those are the audit threads alone: the test thread waits on the task executor, not the database,
         * and its reads afterwards reuse a free pooled connection. {@code asyncAuditCommitsRow}, which shares this
         * context, uses at most two at once, its audit and the test thread's count query, and the in-house listener
         * receives nothing here. With no transaction in progress each connection holds exactly one Derby transaction,
         * the idle one of its current connection, and each audit transaction left behind after its commit adds one
         * more.
         */
        private static final int MAX_OPEN_DERBY_TRANSACTIONS = AUDIT_THREADS + RECOVERY_SCAN_CONNECTIONS;

        @Autowired
        private AuditService proxiedAuditService;

        @Autowired
        private OrderOrchestrationService orchestration;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        /** The executor {@code @Async} resolves, Spring Boot's {@code applicationTaskExecutor}. */
        @Autowired
        @Qualifier("applicationTaskExecutor")
        private ThreadPoolTaskExecutor auditExecutor;

        @DynamicPropertySource
        static void isolatedResources(DynamicPropertyRegistry registry) throws IOException {
            int[] ports = freePorts(5);
            String databaseName = "memory:auditServiceTest-" + UUID.randomUUID();
            int artemisServerId = ThreadLocalRandom.current().nextInt(MIN_ARTEMIS_SERVER_ID, Integer.MAX_VALUE);
            registry.add("server.port", () -> ports[0]);
            // listeners.additional reads its ports through these placeholders: Spring Boot binds that list from one
            // property source, so overriding an entry's port directly would fail binding. orders.samsung.url and
            // orders.price.url follow ports.samsung and ports.prices; orders.soap.url names port 1080 literally.
            registry.add("ajax.port", () -> ports[1]);
            registry.add("ports.populate", () -> ports[2]);
            registry.add("ports.samsung", () -> ports[3]);
            registry.add("ports.prices", () -> ports[4]);
            registry.add("orders.soap.url", () -> "http://localhost:" + ports[0] + "/orders");
            registry.add("spring.datasource.xa.properties.databaseName", () -> databaseName);
            registry.add("spring.datasource.xa.properties.createDatabase", () -> "create");
            registry.add("spring.artemis.embedded.persistent", () -> "false");
            registry.add("spring.artemis.embedded.server-id", () -> artemisServerId);
            registry.add("spring.task.execution.pool.core-size", () -> AUDIT_THREADS);
        }

        @Test
        void asyncAuditCommitsRow() {
            assertThat(AopUtils.isAopProxy(proxiedAuditService)).isTrue();
            // The tests of this class share one context, so another of them may have created the tables first.
            assertThat(orchestration.populateDatabase()).isIn("db populated", "table already populated");

            proxiedAuditService.audit(order(ORDER_ID), "02550");

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from order_audits where order_id = ?", Integer.class, ORDER_ID)).isEqualTo(1));
            // Derby converts the bound string "02550" for the integer column, as the original insert of totalValue did.
            assertThat(jdbcTemplate.queryForObject(
                    "select total_value from order_audits where order_id = ?", Integer.class, ORDER_ID))
                    .isEqualTo(2550);
            assertThat(auditLog.events()).isEmpty();
        }

        /**
         * Concurrent audits, as orders under load dispatch them, each release their Derby transaction and connection
         * when their JTA transaction completes. Narayana finds a modifier for the driver name and version the Derby
         * connection reports, so it logs no {@code ARJUNA017008} and keeps every connection bound to its transaction
         * until the transaction completes. A connection handed to another audit while its transaction still commits
         * leaves an orphaned idle transaction in {@code SYSCS_DIAG.TRANSACTION_TABLE} for minutes, one per such audit.
         *
         * <p>The test thread waits for the audits on the task executor and reads the database only once they have
         * finished, so it takes no pooled connection beyond those of the audit threads. Each audit's transaction runs
         * inside its executor task, so a completed task has committed and run its completion callbacks.
         */
        @Test
        void concurrentAuditsReleaseTheirDerbyTransactionsOnCompletion() {
            assertThat(orchestration.populateDatabase()).isIn("db populated", "table already populated");
            ThreadPoolExecutor auditPool = auditExecutor.getThreadPoolExecutor();
            // The audit of another test of this class may still be completing. Its row has committed, so its task has
            // started, and an idle executor means it has finished: only the audits below are counted from here.
            await().atMost(Duration.ofSeconds(10)).until(() -> isIdle(auditPool));
            long completedBefore = auditPool.getCompletedTaskCount();
            String orderIdPrefix = "concurrent-" + UUID.randomUUID() + "-";
            LogCapture narayanaJdbcLog = new LogCapture(NARAYANA_JDBC_LOGGER);
            Integer auditRows;
            List<Map<String, Object>> derbyTransactions;
            try {
                for (int i = 0; i < CONCURRENT_AUDITS; i++) {
                    proxiedAuditService.audit(order(orderIdPrefix + i), "1");
                }
                // The completed count, because a task taken from the queue but not yet started is neither active
                // nor queued.
                await().atMost(Duration.ofSeconds(60)).until(() ->
                        auditPool.getCompletedTaskCount() - completedBefore >= CONCURRENT_AUDITS && isIdle(auditPool));
                auditRows = jdbcTemplate.queryForObject("select count(*) from order_audits where order_id like ?",
                        Integer.class, orderIdPrefix + "%");
                derbyTransactions = jdbcTemplate.queryForList("select * from syscs_diag.transaction_table");
            } finally {
                narayanaJdbcLog.close();
            }
            ConnectionModifier derbyModifier = jdbcTemplate.execute(
                    (ConnectionCallback<ConnectionModifier>) connection -> {
                        DatabaseMetaData metaData = connection.getMetaData();
                        return ModifierFactory.getModifier(metaData.getDriverName(),
                                metaData.getDriverMajorVersion(), metaData.getDriverMinorVersion());
                    });

            assertThat(auditRows).isEqualTo(CONCURRENT_AUDITS);
            assertThat(derbyTransactions)
                    .as("Derby transactions after %d committed audits", CONCURRENT_AUDITS)
                    .hasSizeLessThanOrEqualTo(MAX_OPEN_DERBY_TRANSACTIONS);
            assertThat(narayanaJdbcLog.events())
                    .noneMatch(event -> event.getFormattedMessage().startsWith(NO_MODIFIER_MESSAGE_ID));
            assertThat(derbyModifier).isInstanceOf(SupportsMultipleConnectionsModifier.class);
        }

        /** No task is running or queued; a task taken from the queue but not yet started counts as neither. */
        private static boolean isIdle(ThreadPoolExecutor pool) {
            return pool.getActiveCount() == 0 && pool.getQueue().isEmpty();
        }

        /** Distinct free local ports: every socket stays open until all are chosen, and all are closed before use. */
        private static int[] freePorts(int count) throws IOException {
            ServerSocket[] sockets = new ServerSocket[count];
            try {
                int[] ports = new int[count];
                for (int i = 0; i < count; i++) {
                    sockets[i] = new ServerSocket(0);
                    ports[i] = sockets[i].getLocalPort();
                }
                return ports;
            } finally {
                for (ServerSocket socket : sockets) {
                    if (socket != null) {
                        socket.close();
                    }
                }
            }
        }
    }

    private static Order order(String orderId) {
        Order order = new Order();
        order.setOrderId(orderId);
        return order;
    }

    /** The sentinel first, where Derby's message quotes it, padded beyond the 256 characters of {@code order_id}. */
    private static String overlongSentinelId() {
        return SENTINEL + "-" + "x".repeat(300);
    }

    private static <T extends Throwable> T withTrace(T throwable, StackTraceElement... frames) {
        throwable.setStackTrace(frames);
        return throwable;
    }

    /**
     * Returns {@code length} nested causes: {@code IllegalStateException}s around an innermost
     * {@code IllegalArgumentException}.
     */
    private static Throwable causeChain(int length) {
        Throwable cause = new IllegalArgumentException(SENTINEL);
        for (int i = 1; i < length; i++) {
            cause = new IllegalStateException(SENTINEL, cause);
        }
        return cause;
    }

    private static ILoggingEvent onlyEvent(LogCapture capture, Level level) {
        List<ILoggingEvent> events = capture.events(level);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    /** A Logback {@link ListAppender} on one logger, which is set to INFO until {@link #close()}. */
    private static final class LogCapture {

        private final Logger logger;

        private final Level previousLevel;

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogCapture(Class<?> loggerClass) {
            this(loggerClass.getName());
        }

        /**
         * Captures a logger by name, for a library logger that is not named after a class.
         *
         * @param loggerName the name of the logger to capture
         */
        LogCapture(String loggerName) {
            logger = (Logger) LoggerFactory.getLogger(loggerName);
            previousLevel = logger.getLevel();
            logger.setLevel(Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        /** Every event captured so far, in logging order. */
        List<ILoggingEvent> events() {
            // Executor threads log concurrently; ListAppender appends while holding its own monitor.
            synchronized (appender) {
                return new ArrayList<>(appender.list);
            }
        }

        List<ILoggingEvent> events(Level level) {
            return events().stream().filter(event -> event.getLevel() == level).toList();
        }

        void close() {
            try {
                logger.detachAppender(appender);
                appender.stop();
            } finally {
                logger.setLevel(previousLevel);
            }
        }
    }
}
