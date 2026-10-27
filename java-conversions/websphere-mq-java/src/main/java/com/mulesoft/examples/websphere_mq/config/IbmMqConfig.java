/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
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
 *   <li>{@code validateConnections="true"} has no separate counterpart: the listener containers connect at
 *       start-up and, while the queue manager is unreachable or after a connection breaks, keep recovering with
 *       Spring's default back-off (a new attempt every 5 s, without limit), which also stands for the
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
 * </ul>
 *
 * <p>The IBM MQ factory is a bean only while {@link WmqEnabledCondition} holds: {@code wmq.enabled} is not
 * {@code false} and {@code wmq.host} has text. Otherwise this class defines no {@code ConnectionFactory}. The tests
 * then run on the embedded Artemis broker that Boot's auto-configuration supplies (Artemis is a test-scope
 * dependency only), while an application started without {@code wmq.host} fails at start-up for want of a
 * {@code ConnectionFactory}; no other broker is ever substituted for IBM MQ.
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
     * Builds the IBM MQ client connection factory from the {@code wmq.*} keys.
     *
     * <p>Host, channel and queue manager are set only when their key has text; otherwise the IBM client's own
     * default stays in place, so no connection value is ever supplied by this class. Credentials are not set here:
     * {@link #connectionFactory(Environment)} applies them to each connection.
     *
     * <p>Before the factory is constructed, {@link #applyTransportTimeouts(Environment)} sets the JVM-wide IBM client
     * properties that hold the connect, negotiation and read budgets of its connections, because the client reads
     * them only then. Client reconnection is disabled, so a broken connection fails the operation instead of being
     * re-established behind it (see the class description).
     *
     * <p>The method is public and static, and not a bean, so that the property binding can be verified against any
     * {@link Environment} without a queue manager.
     *
     * @param env the environment holding the {@code wmq.*} keys
     * @return a client-mode {@link MQConnectionFactory}; it connects only when a connection is created
     * @throws JMSException             when the IBM client rejects a value
     * @throws NumberFormatException    when {@code wmq.port} has text that is not an integer
     * @throws IllegalArgumentException when a {@code wmq.transport.*} budget is not a whole number of seconds
     *                                  between 1 and 2147483
     */
    public static MQConnectionFactory mqConnectionFactory(Environment env) throws JMSException {
        applyTransportTimeouts(env);
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
     * The application's {@code ConnectionFactory}: the IBM MQ factory, wrapped so that every connection is created
     * with {@code wmq.username} and {@code wmq.password} when a user name is configured.
     *
     * <p>Without a user name the factory is returned unwrapped and connections are created without credentials,
     * as the original test runs the connector with empty ones.
     *
     * @param env the environment holding the {@code wmq.*} keys
     * @return the connection factory shared by both listener containers and {@link #jmsTemplate(ConnectionFactory)}
     * @throws JMSException when the IBM client rejects a configured value
     */
    @Bean
    @Conditional(WmqEnabledCondition.class)
    public ConnectionFactory connectionFactory(Environment env) throws JMSException {
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
     * Holds when {@code wmq.enabled} is not {@code false} and {@code wmq.host} has text.
     *
     * <p>{@code @ConditionalOnProperty} is not used because it treats an empty value as present, and
     * {@code application.yml} sets {@code wmq.host: ${WMQ_HOST:}}: the tests would then try to reach a queue
     * manager instead of running on the embedded broker.
     */
    public static class WmqEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            return env.getProperty(ENABLED_KEY, Boolean.class, Boolean.TRUE)
                    && StringUtils.hasText(env.getProperty(HOST_KEY));
        }
    }
}
