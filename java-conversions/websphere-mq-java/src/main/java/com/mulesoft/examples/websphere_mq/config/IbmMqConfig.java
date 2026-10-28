/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.util.StringUtils;

/**
 * IBM MQ connection and JMS plumbing of the three flows of {@code websphere-mq.xml}, replacing its
 * {@code wmq:connector} global element (websphere-mq/src/main/app/websphere-mq.xml:4-5).
 *
 * <ul>
 *   <li>{@code transportType="CLIENT_MQ_TCPIP"} becomes {@link WMQConstants#WMQ_CM_CLIENT}: a client
 *       connection over TCP/IP to the queue manager's listener.</li>
 *   <li>{@code hostName}, {@code port} and {@code channel} come from {@code wmq.host}, {@code wmq.port} and
 *       {@code wmq.channel}. {@code queueManager} comes from {@code wmq.queue}, as in the original, so that key
 *       names the queue manager, not a queue.</li>
 *   <li>{@code username} and {@code password} ({@code wmq.username}, {@code wmq.password}) are applied to each
 *       connection by a {@link UserCredentialsConnectionFactoryAdapter}, because JMS passes credentials to
 *       {@code createConnection}, not to the factory.</li>
 *   <li>{@code validateConnections="true"} becomes {@link ConnectionValidator}: once every singleton exists, and
 *       before the listener containers and the HTTP listener start, it opens one connection through the
 *       {@code ConnectionFactory} bean, credentials included, and closes it again. When the queue manager is
 *       unreachable or refuses the connection, the application does not start, as the original did not; the check is
 *       made once and never retried. A connection that breaks after start-up is recovered by the listener containers
 *       with Spring's default back-off (a new attempt every 5 s, without limit), which also stands for the
 *       {@code ee:reconnect-forever} the original README shows.</li>
 *   <li>The connector sets no transport timeout, and the IBM client's defaults leave a connect, the queue-manager
 *       negotiation and every later read unbounded or bounded only by the channel heartbeat. Three budgets in whole
 *       seconds, each {@value #DEFAULT_TRANSPORT_TIMEOUT_SECONDS} by default (the Mule default response timeout),
 *       bound them instead: {@code wmq.transport.connect-timeout-seconds} the TCP connect,
 *       {@code wmq.transport.negotiation-timeout-seconds} each reply of the negotiation flow that opens a
 *       connection, and {@code wmq.transport.receive-timeout-seconds} each read once connected. On a first read
 *       timeout the client sends a heartbeat and on a second declares the connection broken (reason 2009), so a
 *       peer that stops answering mid-session is detected within about twice that budget. A send is bounded
 *       through the read of its reply and that heartbeat as long as the socket can take its bytes. A write that
 *       blocks because the queue manager or the network keeps the connection open but stops reading once the
 *       socket buffers are full is not bounded: the IBM client offers no write timeout, its heartbeat waits
 *       behind the blocked write, and neither closing the JMS connection nor interrupting the thread ends it.
 *       Such a write ends only when the operating system or the network fails the connection. The budgets reach the
 *       client as JVM-wide system properties, which it reads once per JVM when the first {@link MQConnectionFactory}
 *       is constructed ({@link #applyTransportTimeouts(Environment)}). Client reconnection is disabled: otherwise a
 *       host {@code mqclient.ini} with {@code DefRecon=YES} would turn a detected break into a transparent reconnect
 *       holding the calling thread for up to the reconnect timeout (1800 s by default). A timed-out operation
 *       therefore fails once with a {@link JMSException}. Nothing in this class retries or resends it: an
 *       uncommitted transacted send or receive is backed out by the queue manager once it detects the broken
 *       connection, and the listener containers recover as described above.</li>
 *   <li>The IBM client keeps diagnostic files of its own: an error log ({@code mqjms.log.0} to {@code .2}, which
 *       records broken connections, for example) and, when its trace or First Failure Data Capture runs, a trace file
 *       and an {@code FFDC} directory. By default it writes them to the working directory, which is the project
 *       directory when the application is started there. They go to {@code wmq.client.diagnostics-dir} instead,
 *       {@value #DEFAULT_CLIENT_DIAGNOSTICS_DIR} by default, unless the operator has set their locations
 *       ({@link #applyClientDiagnosticsDirectory(Environment)}).</li>
 * </ul>
 *
 * <p>The IBM MQ factory and its start-up check are beans only while {@link WmqEnabledCondition} holds:
 * {@code wmq.enabled} is not {@code false}. The factory then requires {@code wmq.host}, and an application started
 * without it fails at start-up with a message naming {@code wmq.host} and its environment variable {@code WMQ_HOST}
 * ({@link #connectionFactory(Environment)}). With {@code wmq.enabled: false}, which only the tests set, this class
 * defines no {@code ConnectionFactory} and the tests run on the embedded Artemis broker that Boot's
 * auto-configuration supplies (Artemis is a test-scope dependency only); no other broker is ever substituted for
 * IBM MQ.
 *
 * <p>The listener factories and the template take the single {@code ConnectionFactory} bean exactly as injected,
 * and none of them has a transaction manager; {@link #jmsTemplate(ConnectionFactory)} explains why the
 * transactions of flow {@code MessageProcessor} depend on both.
 */
@Configuration(proxyBeanMethods = false)
@EnableJms
public class IbmMqConfig {

    /** Switch for the IBM MQ factory; absent means enabled. */
    public static final String ENABLED_KEY = "wmq.enabled";

    /** Host name of the queue manager's listener ({@code hostName} of the original connector). */
    public static final String HOST_KEY = "wmq.host";

    /** Port of the queue manager's listener ({@code port} of the original connector). */
    public static final String PORT_KEY = "wmq.port";

    /** Server-connection channel ({@code channel} of the original connector). */
    public static final String CHANNEL_KEY = "wmq.channel";

    /** Queue manager name; the original maps {@code queueManager="${wmq.queue}"}. */
    public static final String QUEUE_MANAGER_KEY = "wmq.queue";

    /** User name applied to every connection ({@code username} of the original connector). */
    public static final String USERNAME_KEY = "wmq.username";

    /** Password applied with {@link #USERNAME_KEY} ({@code password} of the original connector). */
    public static final String PASSWORD_KEY = "wmq.password";

    /**
     * Budget, in whole seconds, for opening the TCP connection to the queue manager's listener. Sets the IBM client
     * property {@code com.ibm.mq.cfg.TCP.Connect_Timeout}.
     */
    public static final String CONNECT_TIMEOUT_KEY = "wmq.transport.connect-timeout-seconds";

    /**
     * Budget, in whole seconds, for each reply of the queue-manager negotiation flow that opens a connection (channel
     * start, connect and authentication). Sets the IBM client property
     * {@code com.ibm.mq.cfg.TCP.QmgrNegotiationFlowTimeout}.
     */
    public static final String NEGOTIATION_TIMEOUT_KEY = "wmq.transport.negotiation-timeout-seconds";

    /**
     * Budget, in whole seconds, for each socket read once a connection is established; a peer that stops answering
     * is declared broken after about twice this value unless a write to it is blocked (see the class description).
     * Sets the IBM client property {@code com.ibm.mq.cfg.MQRCVBLKTO}.
     */
    public static final String RECEIVE_TIMEOUT_KEY = "wmq.transport.receive-timeout-seconds";

    /**
     * Default of each {@code wmq.transport.*} budget, in seconds: the Mule default response timeout, the connect and
     * read budget of the other converted connectors.
     */
    public static final int DEFAULT_TRANSPORT_TIMEOUT_SECONDS = 10;

    /**
     * Directory of the IBM client's own diagnostic files: its error log and, when they are produced, its trace and
     * FFDC files. A relative path resolves against the working directory.
     */
    public static final String CLIENT_DIAGNOSTICS_DIR_KEY = "wmq.client.diagnostics-dir";

    /**
     * Default of {@link #CLIENT_DIAGNOSTICS_DIR_KEY}, also used when it is blank: inside the build directory, so the
     * IBM client's files never land in the project directory the application is started from.
     */
    public static final String DEFAULT_CLIENT_DIAGNOSTICS_DIR = "target/mqclient";

    /** Bean name of the container factory for queue {@code in} (flow {@code MessageProcessor}). */
    public static final String TRANSACTED_LISTENER_FACTORY = "transactedListenerContainerFactory";

    /** Bean name of the container factory for queue {@code out} (flow {@code Output}). */
    public static final String AUTO_ACK_LISTENER_FACTORY = "autoAckListenerContainerFactory";

    /** Port used when {@code wmq.port} is absent or blank: the one the original test sets, IBM MQ's default. */
    private static final int DEFAULT_PORT = 1414;

    /**
     * Largest accepted transport budget, in seconds. The IBM client turns each budget into an {@code int} of
     * milliseconds, which overflows above this value; the client itself caps the negotiation budget here.
     */
    private static final int MAX_TRANSPORT_TIMEOUT_SECONDS = 2147483;

    /** IBM client property for {@link #CONNECT_TIMEOUT_KEY}: attribute {@code Connect_Timeout} of stanza TCP. */
    private static final String IBM_CONNECT_TIMEOUT_PROPERTY = "com.ibm.mq.cfg.TCP.Connect_Timeout";

    /** IBM client property for {@link #NEGOTIATION_TIMEOUT_KEY}: {@code QmgrNegotiationFlowTimeout} of stanza TCP. */
    private static final String IBM_NEGOTIATION_TIMEOUT_PROPERTY = "com.ibm.mq.cfg.TCP.QmgrNegotiationFlowTimeout";

    /** IBM client property for {@link #RECEIVE_TIMEOUT_KEY}: the receive block timeout {@code MQRCVBLKTO}. */
    private static final String IBM_RECEIVE_TIMEOUT_PROPERTY = "com.ibm.mq.cfg.MQRCVBLKTO";

    /** IBM client property naming its error log file; the client's default is {@value #IBM_LOG_FILE_NAME}. */
    private static final String IBM_LOG_OUTPUT_PROPERTY = "com.ibm.msg.client.commonservices.log.outputName";

    /**
     * IBM client property naming its trace file; the client's default is {@value #IBM_TRACE_FILE_NAME}. The client
     * writes FFDC files to the {@code FFDC} subdirectory of the directory this file is in.
     */
    private static final String IBM_TRACE_OUTPUT_PROPERTY = "com.ibm.msg.client.commonservices.trace.outputName";

    /** IBM client property holding the URL of its configuration file. */
    private static final String IBM_CONFIG_LOCATION_PROPERTY = "com.ibm.msg.client.config.location";

    /** Configuration file the IBM client reads from the system class path when no URL is set. */
    private static final String IBM_DEFAULT_CONFIG_RESOURCE = "META-INF/jms.config";

    /**
     * File name of the IBM client's error log, its own default, kept in the diagnostics directory. The client rolls
     * it over into {@code mqjms.log.0} to {@code mqjms.log.2}.
     */
    private static final String IBM_LOG_FILE_NAME = "mqjms.log";

    /**
     * File name of the IBM client's trace, its own default ({@code TraceFilename} of the client's
     * {@code META-INF/product.properties}), kept in the diagnostics directory; the client replaces {@code %PID%}.
     */
    private static final String IBM_TRACE_FILE_NAME = "mqjavaclient_%PID%.trc";

    /**
     * Consumers per listener, as minimum and maximum. The {@code wmq:connector} sets no {@code numberOfConsumers},
     * so the Mule 3.8 JMS-family default of four concurrent receivers applies to both inbound endpoints
     * ({@code AbstractConnector.DEFAULT_NUM_CONCURRENT_TX_RECEIVERS}, served by {@code MultiConsumerJmsMessageReceiver}
     * for transacted and non-transacted endpoints alike). Up to four messages sent together are therefore confirmed
     * together after the 15 s wait, as in the original. A single consumer is deliberately not used: it would confirm
     * them one after another, 15 s apart, which is not the original's behaviour.
     */
    private static final String ORIGINAL_CONSUMERS = "4-4";

    /**
     * Start-up failure message of an enabled IBM MQ factory without a queue manager host. It names the key and the
     * environment variable {@code application.yml} reads it from, and no configured value.
     */
    private static final String MISSING_HOST = HOST_KEY + " (environment variable WMQ_HOST) must name the host of the"
            + " IBM MQ queue manager's listener while " + ENABLED_KEY + " is not false; " + ENABLED_KEY
            + "=false is meant only for tests on the embedded broker";

    /**
     * Start-up failure message of {@link ConnectionValidator}. It names the keys the connection is made from and no
     * configured value; the IBM client's {@link JMSException}, its cause, carries the reason code.
     */
    private static final String CONNECTION_VALIDATION_FAILED = "IBM MQ connection validation failed at start-up"
            + " (validateConnections=\"true\" of the original wmq:connector): no connection could be opened to the"
            + " queue manager configured by " + HOST_KEY + ", " + PORT_KEY + ", " + CHANNEL_KEY + " and "
            + QUEUE_MANAGER_KEY + ", with the credentials of " + USERNAME_KEY + " and " + PASSWORD_KEY
            + " when a user name is set";

    /**
     * Builds the IBM MQ client connection factory from the {@code wmq.*} keys.
     *
     * <p>Host, channel and queue manager are set only when their key has text; otherwise the IBM client's own
     * default stays in place, so no connection value is ever supplied by this class. Credentials are not set here:
     * {@link #connectionFactory(Environment)} applies them to each connection.
     *
     * <p>Before the factory is constructed, {@link #applyTransportTimeouts(Environment)} sets the JVM-wide IBM client
     * properties that hold the connect, negotiation and read budgets of its connections, and
     * {@link #applyClientDiagnosticsDirectory(Environment)} the ones that place its log, trace and FFDC files, because
     * the client reads them only then. Client reconnection is disabled, so a broken connection fails the operation
     * instead of being re-established behind it (see the class description).
     *
     * <p>The method is public and static, and not a bean, so that the property binding can be verified against any
     * {@link Environment} without a queue manager.
     *
     * @param env the environment holding the {@code wmq.*} keys
     * @return a client-mode {@link MQConnectionFactory}; it connects only when a connection is created
     * @throws JMSException             when the IBM client rejects a value
     * @throws NumberFormatException    when {@code wmq.port} has text that is not an integer
     * @throws IllegalArgumentException when a {@code wmq.transport.*} budget is not a whole number of seconds
     *                                  between 1 and 2147483, or {@code wmq.client.diagnostics-dir} is not a usable
     *                                  directory name
     * @throws UncheckedIOException     when the {@code wmq.client.diagnostics-dir} directory cannot be created
     */
    public static MQConnectionFactory mqConnectionFactory(Environment env) throws JMSException {
        applyTransportTimeouts(env);
        applyClientDiagnosticsDirectory(env);
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setClientReconnectOptions(WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED);

        String host = env.getProperty(HOST_KEY);
        if (StringUtils.hasText(host)) {
            factory.setHostName(host);
        }
        factory.setPort(port(env));

        String channel = env.getProperty(CHANNEL_KEY);
        if (StringUtils.hasText(channel)) {
            factory.setChannel(channel);
        }
        String queueManager = env.getProperty(QUEUE_MANAGER_KEY);
        if (StringUtils.hasText(queueManager)) {
            factory.setQueueManager(queueManager);
        }
        return factory;
    }

    /**
     * Reads {@code wmq.port}, falling back to {@value #DEFAULT_PORT} when it is absent or blank.
     *
     * @throws NumberFormatException naming the key when the value is not an integer, so misconfiguration fails
     *                               at start-up
     */
    private static int port(Environment env) {
        String value = env.getProperty(PORT_KEY);
        if (!StringUtils.hasText(value)) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new NumberFormatException(PORT_KEY + " must be an integer port number, was '" + value + "'");
        }
    }

    /**
     * Sets the IBM client properties that hold the connect, negotiation and read budgets of an IBM MQ client
     * connection from {@link #CONNECT_TIMEOUT_KEY}, {@link #NEGOTIATION_TIMEOUT_KEY} and {@link #RECEIVE_TIMEOUT_KEY},
     * each defaulting to {@value #DEFAULT_TRANSPORT_TIMEOUT_SECONDS} seconds when absent or blank.
     *
     * <p>The properties are JVM-wide, not per factory, and the IBM client reads them once per JVM, when the first
     * {@link MQConnectionFactory} is constructed. This must therefore run before that construction; a value set
     * afterwards, a different one included, has no effect. All three values are checked before any property is set,
     * so a rejected configuration leaves the JVM's properties unchanged.
     *
     * @param env the environment holding the {@code wmq.transport.*} keys
     * @throws IllegalArgumentException naming the key and its value when a budget is not a whole number of seconds
     *                                  between 1 and 2147483: zero or a negative value would mean no timeout or fail
     *                                  every connection, and a larger one overflows the client's milliseconds
     */
    public static void applyTransportTimeouts(Environment env) {
        int connectSeconds = transportTimeout(env, CONNECT_TIMEOUT_KEY);
        int negotiationSeconds = transportTimeout(env, NEGOTIATION_TIMEOUT_KEY);
        int receiveSeconds = transportTimeout(env, RECEIVE_TIMEOUT_KEY);
        System.setProperty(IBM_CONNECT_TIMEOUT_PROPERTY, Integer.toString(connectSeconds));
        System.setProperty(IBM_NEGOTIATION_TIMEOUT_PROPERTY, Integer.toString(negotiationSeconds));
        System.setProperty(IBM_RECEIVE_TIMEOUT_PROPERTY, Integer.toString(receiveSeconds));
    }

    /**
     * Reads one transport budget, falling back to {@value #DEFAULT_TRANSPORT_TIMEOUT_SECONDS} when the key is absent
     * or blank.
     *
     * @param env the environment holding the key
     * @param key one of the {@code wmq.transport.*} keys
     * @return the budget in seconds, between 1 and {@value #MAX_TRANSPORT_TIMEOUT_SECONDS}
     * @throws IllegalArgumentException naming the key and its value when the value is not an integer or lies outside
     *                                  1 to {@value #MAX_TRANSPORT_TIMEOUT_SECONDS}, so misconfiguration fails at
     *                                  start-up
     */
    private static int transportTimeout(Environment env, String key) {
        String value = env.getProperty(key);
        if (!StringUtils.hasText(value)) {
            return DEFAULT_TRANSPORT_TIMEOUT_SECONDS;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(invalidTransportTimeout(key, value), e);
        }
        if (seconds < 1 || seconds > MAX_TRANSPORT_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(invalidTransportTimeout(key, value));
        }
        return seconds;
    }

    /**
     * The start-up failure message for a rejected transport budget.
     *
     * @param key   the {@code wmq.transport.*} key
     * @param value the rejected value as configured
     * @return the message naming the key, the accepted range and the value
     */
    private static String invalidTransportTimeout(String key, String value) {
        return key + " must be a whole number of seconds between 1 and " + MAX_TRANSPORT_TIMEOUT_SECONDS
                + ", was '" + value + "'";
    }

    /**
     * Moves the IBM client's diagnostic files out of the working directory, its default, into the directory named by
     * {@link #CLIENT_DIAGNOSTICS_DIR_KEY}, or {@value #DEFAULT_CLIENT_DIAGNOSTICS_DIR} when that key is absent or
     * blank. The files are the error log {@code mqjms.log} (rolled over into {@code mqjms.log.0} to
     * {@code mqjms.log.2}), the trace file {@code mqjavaclient_%PID%.trc} with the process id in place of
     * {@code %PID%}, and the {@code FFDC} directory beside the trace file. The method sets the IBM client properties
     * {@code com.ibm.msg.client.commonservices.log.outputName} and
     * {@code com.ibm.msg.client.commonservices.trace.outputName}, keeping the client's own file names. It also creates
     * the directory, because the client does not create one for its log.
     *
     * <p>A location the operator chose is kept. A property already set as a JVM system property, or defined in the
     * IBM client's configuration file, is left unchanged, because a system property set here would take precedence
     * over the file. The file is the one the client reads: the URL in {@code com.ibm.msg.client.config.location},
     * or else {@code META-INF/jms.config} on the system class path. When both locations are kept, no directory is
     * created.
     *
     * <p>The properties are JVM-wide, and the IBM client reads them once per JVM, when the first
     * {@link MQConnectionFactory} is constructed. This must therefore run before that construction. The directory is
     * created before any property is set, so a rejected directory leaves the JVM's properties unchanged.
     *
     * @param env the environment holding {@link #CLIENT_DIAGNOSTICS_DIR_KEY}
     * @throws IllegalArgumentException naming the key and its value when the value is not a path on this platform, or
     *                                  contains {@code %}, which the IBM client and {@code java.util.logging} read in
     *                                  a file name as the start of a pattern such as {@code %PID%} or {@code %t}
     * @throws UncheckedIOException     naming the key and the directory when the directory cannot be created, for
     *                                  example because a file of that name exists
     */
    public static void applyClientDiagnosticsDirectory(Environment env) {
        String value = env.getProperty(CLIENT_DIAGNOSTICS_DIR_KEY);
        String name = StringUtils.hasText(value) ? value.trim() : DEFAULT_CLIENT_DIAGNOSTICS_DIR;
        if (name.indexOf('%') >= 0) {
            throw new IllegalArgumentException(CLIENT_DIAGNOSTICS_DIR_KEY + " must not contain '%', which the IBM MQ"
                    + " client reads as the start of a file-name pattern, was '" + value + "'");
        }
        Path directory;
        try {
            directory = Path.of(name);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(CLIENT_DIAGNOSTICS_DIR_KEY + " must be a directory path, was '" + value
                    + "'", e);
        }

        Properties configurationFile = ibmClientConfigurationFile();
        boolean placeLog = System.getProperty(IBM_LOG_OUTPUT_PROPERTY) == null
                && !configurationFile.containsKey(IBM_LOG_OUTPUT_PROPERTY);
        boolean placeTrace = System.getProperty(IBM_TRACE_OUTPUT_PROPERTY) == null
                && !configurationFile.containsKey(IBM_TRACE_OUTPUT_PROPERTY);
        if (!placeLog && !placeTrace) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(CLIENT_DIAGNOSTICS_DIR_KEY + " names a directory that cannot be created: "
                    + directory.toAbsolutePath(), e);
        }
        if (placeLog) {
            System.setProperty(IBM_LOG_OUTPUT_PROPERTY, directory.resolve(IBM_LOG_FILE_NAME).toString());
        }
        if (placeTrace) {
            System.setProperty(IBM_TRACE_OUTPUT_PROPERTY, directory.resolve(IBM_TRACE_FILE_NAME).toString());
        }
    }

    /**
     * Reads the IBM client's configuration file from where the client looks for it: the URL in
     * {@code com.ibm.msg.client.config.location}, or else {@code META-INF/jms.config} on the system class path.
     *
     * @return the file's properties; empty when there is no file, or when its URL is malformed or it cannot be read,
     *         because the client then reads nothing from it either
     */
    private static Properties ibmClientConfigurationFile() {
        String location = System.getProperty(IBM_CONFIG_LOCATION_PROPERTY);
        Properties properties = new Properties();
        try {
            URL url = location != null ? new URL(location) : ClassLoader.getSystemResource(IBM_DEFAULT_CONFIG_RESOURCE);
            if (url != null) {
                try (InputStream in = url.openStream()) {
                    properties.load(in);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // A file that cannot be read sets no location, for the client as for this class; drop any partial read.
            properties.clear();
        }
        return properties;
    }

    /**
     * The application's {@code ConnectionFactory}: the IBM MQ factory, wrapped so that every connection is created
     * with {@code wmq.username} and {@code wmq.password} when a user name is configured.
     *
     * <p>Without a user name the factory is returned unwrapped and connections are created without credentials,
     * as the original test runs the connector with empty ones.
     *
     * <p>{@code wmq.host} is required, because the IBM client would otherwise connect to a host of its own choosing.
     * Without it the method fails before any factory is built or any IBM client property is set, and so before any
     * bean that needs the factory is created.
     *
     * @param env the environment holding the {@code wmq.*} keys
     * @return the connection factory shared by both listener containers and {@link #jmsTemplate(ConnectionFactory)}
     * @throws JMSException          when the IBM client rejects a configured value
     * @throws IllegalStateException naming {@code wmq.host} and its environment variable {@code WMQ_HOST}, and no
     *                               configured value, when {@code wmq.host} is absent or blank
     */
    @Bean
    @Conditional(WmqEnabledCondition.class)
    public ConnectionFactory connectionFactory(Environment env) throws JMSException {
        if (!StringUtils.hasText(env.getProperty(HOST_KEY))) {
            throw new IllegalStateException(MISSING_HOST);
        }
        MQConnectionFactory mq = mqConnectionFactory(env);
        String username = env.getProperty(USERNAME_KEY);
        if (!StringUtils.hasText(username)) {
            return mq;
        }
        UserCredentialsConnectionFactoryAdapter adapter = new UserCredentialsConnectionFactoryAdapter();
        adapter.setTargetConnectionFactory(mq);
        adapter.setUsername(username);
        adapter.setPassword(env.getProperty(PASSWORD_KEY, ""));
        return adapter;
    }

    /**
     * Container factory for queue {@code in}, replacing {@code wmq:inbound-endpoint queue="in"} with
     * {@code wmq:transaction action="ALWAYS_BEGIN"} (websphere-mq.xml:16-18).
     *
     * <p>Every receive runs in a locally transacted session, committed when the listener returns and rolled back
     * when it throws, which returns the message to {@code in}. While the listener runs, the container exposes that
     * session to {@link #jmsTemplate(ConnectionFactory)} on the same thread, so the send to {@code out} belongs to
     * the same transaction (see there).
     *
     * @param connectionFactory the application's single {@code ConnectionFactory} bean
     * @return the session-transacted listener container factory
     */
    @Bean(TRANSACTED_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory transactedListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(true);
        factory.setConcurrency(ORIGINAL_CONSUMERS);
        return factory;
    }

    /**
     * Container factory for queue {@code out}, replacing the {@code wmq:inbound-endpoint queue="out"} of flow
     * {@code Output}, which declares no transaction (websphere-mq.xml:25).
     *
     * <p>Sessions are not transacted and acknowledge on receipt, so a message is consumed whatever the listener
     * does with it, as with the original non-transacted endpoint.
     *
     * @param connectionFactory the application's single {@code ConnectionFactory} bean
     * @return the auto-acknowledging listener container factory
     */
    @Bean(AUTO_ACK_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory autoAckListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(false);
        factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
        factory.setConcurrency(ORIGINAL_CONSUMERS);
        return factory;
    }

    /**
     * Template for every send to IBM MQ: the sends to {@code in} (flow {@code Input}) and to {@code out}
     * (flow {@code MessageProcessor}). Being a {@code JmsOperations} bean, it makes Boot's own template back off.
     *
     * <p>Transaction semantics, which are why one shared factory instance and a transacted template are required:
     * <ul>
     *   <li>With no transaction manager and {@code exposeListenerSession} left {@code true}, a listener container
     *       binds its transacted session to {@code TransactionSynchronizationManager} under its own
     *       {@code ConnectionFactory} instance while the listener runs.</li>
     *   <li>A send this template makes on the {@code in} listener thread looks up a session under the same
     *       factory instance, finds that one and reuses it, leaving commit and rollback to the container. The send
     *       to {@code out} therefore commits or rolls back together with the receive from {@code in}: the original
     *       {@code JOIN_IF_POSSIBLE} (websphere-mq.xml:20-22).</li>
     *   <li>A send from the Ajax broker's executor thread ({@code client/MqQueueSender.enqueue}, flow
     *       {@code Input}) has no surrounding session, so the template opens a transacted session of its own and
     *       commits it immediately after the send.</li>
     * </ul>
     * A transaction manager on the containers, or a factory wrapped or unwrapped differently for the template, would
     * give the {@code out} send a session of its own, whose message would survive a rollback of the receive.
     *
     * <p>Each send and commit is bounded by the transport budgets of the class description, except a write the queue
     * manager or the network stops reading, which the class description explains. One that times out fails once,
     * with Spring's {@code JmsException} wrapping the client's {@link JMSException}, and the template never repeats
     * it, so an uncertain write is not sent twice.
     *
     * @param connectionFactory the application's single {@code ConnectionFactory} bean, the same instance the
     *                          listener containers use
     * @return the session-transacted template
     */
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        return template;
    }

    /**
     * The start-up check of {@code validateConnections="true"} (websphere-mq.xml:4), defined with the IBM MQ factory.
     *
     * @param connectionFactory the application's single {@code ConnectionFactory} bean as the context holds it, the
     *                          credential adapter when {@code wmq.username} has text
     * @return the check, run by the context once every singleton exists
     */
    @Bean
    @Conditional(WmqEnabledCondition.class)
    public ConnectionValidator connectionValidator(ConnectionFactory connectionFactory) {
        return new ConnectionValidator(connectionFactory);
    }

    /**
     * Opens one connection at start-up and fails the start when it cannot, reproducing the original connector's
     * {@code validateConnections="true"}, under which the Mule application did not start while its queue manager
     * was unreachable or refused the connection.
     *
     * <p>The context calls {@link #afterSingletonsInstantiated()} once every singleton exists, which is before any
     * lifecycle bean starts: the JMS listener containers and the Undertow listener start afterwards, so on a
     * failure no queue is consumed and the page is never served. The connection is made through the
     * {@code ConnectionFactory} bean the listener containers and the template use, so wrong credentials fail the
     * start as an unreachable queue manager does.
     *
     * <p>The check is made once and never retried. The connection it opens is closed again at once; a close that
     * fails after a successful open is logged at WARN and does not fail the start, because the queue manager has
     * accepted the connection. A connection that breaks after start-up is the listener containers' to recover.
     */
    public static final class ConnectionValidator implements SmartInitializingSingleton {

        private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionValidator.class);

        private final ConnectionFactory connectionFactory;

        /**
         * Creates the check for one factory.
         *
         * @param connectionFactory the factory a connection is opened through
         * @throws NullPointerException when {@code connectionFactory} is {@code null}
         */
        public ConnectionValidator(ConnectionFactory connectionFactory) {
            this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        }

        /**
         * Opens a connection and closes it again.
         *
         * @throws IllegalStateException naming {@code validateConnections} and the {@code wmq.*} connection keys, with
         *                               no configured value and the client's {@link JMSException} as its cause,
         *                               when no connection can be opened; thrown out of the context refresh, it
         *                               stops the application from starting
         */
        @Override
        public void afterSingletonsInstantiated() {
            Connection connection;
            try {
                connection = connectionFactory.createConnection();
            } catch (JMSException e) {
                throw new IllegalStateException(CONNECTION_VALIDATION_FAILED, e);
            }
            try {
                connection.close();
            } catch (JMSException e) {
                LOGGER.warn("IBM MQ start-up validation connection was opened but could not be closed;"
                        + " start-up continues", e);
            }
        }
    }

    /**
     * Holds when {@code wmq.enabled} is not {@code false}. The key is read as a {@code Boolean}, an absent or empty
     * value meaning enabled; {@code @ConditionalOnProperty} is not used because it would read every value other than
     * {@code false} as enabled, {@code off} and {@code no} included.
     *
     * <p>The condition reads the switch alone. A missing {@code wmq.host} does not switch the IBM MQ factory off:
     * {@link #connectionFactory(Environment)} rejects it with a message naming the key, so an application started
     * without a queue manager host fails on that key rather than for want of a {@code ConnectionFactory}.
     */
    public static class WmqEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(ENABLED_KEY, Boolean.class, Boolean.TRUE);
        }
    }
}
