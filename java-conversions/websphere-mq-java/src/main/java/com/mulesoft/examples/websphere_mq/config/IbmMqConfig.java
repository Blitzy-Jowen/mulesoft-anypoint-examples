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

    /** Bean name of the container factory for queue {@code in} (flow {@code MessageProcessor}). */
    public static final String TRANSACTED_LISTENER_FACTORY = "transactedListenerContainerFactory";

    /** Bean name of the container factory for queue {@code out} (flow {@code Output}). */
    public static final String AUTO_ACK_LISTENER_FACTORY = "autoAckListenerContainerFactory";

    /** Port used when {@code wmq.port} is absent or blank: the one the original test sets, IBM MQ's default. */
    private static final int DEFAULT_PORT = 1414;

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
     * <p>The method is public and static, and not a bean, so that the property binding can be verified against any
     * {@link Environment} without a queue manager.
     *
     * @param env the environment holding the {@code wmq.*} keys
     * @return a client-mode {@link MQConnectionFactory}; it connects only when a connection is created
     * @throws JMSException          when the IBM client rejects a value
     * @throws NumberFormatException when {@code wmq.port} has text that is not an integer
     */
    public static MQConnectionFactory mqConnectionFactory(Environment env) throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);

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
