/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import jakarta.jms.ConnectionFactory;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

/**
 * Transaction infrastructure replacing the connectors and the transactional scope of
 * {@code transactions.xml}.
 *
 * <ul>
 *   <li>Line 3, {@code jms:activemq-connector name="Active_MQ"}: Boot's Artemis auto-configuration builds the
 *       {@code ConnectionFactory} from {@code spring.artemis.*}; its {@code maxRedelivery="2"} is enforced by
 *       {@code trigger/OrderQueueListener}, not by the container.</li>
 *   <li>Line 4, {@code db:generic-config url="${jdbc.url}"}: Boot builds the {@code DataSource} from
 *       {@code spring.datasource.url}; {@link #transactionManager} is the only transaction manager over it.</li>
 *   <li>Line 7, {@code jms:inbound-endpoint queue="in"}: {@link #jmsListenerContainerFactory} receives in a
 *       locally transacted session, which is the JMS side of the scope.</li>
 *   <li>Line 12, {@code ee:multi-transactional action="ALWAYS_BEGIN"}: {@code service/OrderTransactionService}
 *       opens a new {@code DataSourceTransactionManager} transaction around the insert, which rolls back when
 *       the component of line 17 throws; the exception then leaves the listener and the container rolls the
 *       session back, so the message is redelivered.</li>
 *   <li>Lines 18-19, {@code jms:outbound-endpoint queue="out"} with {@code JOIN_IF_POSSIBLE}:
 *       {@link #jmsTemplate} sends on the listener's session and leaves its commit to the container.</li>
 * </ul>
 *
 * <p>Both JMS beans must receive the injected {@code ConnectionFactory} as it is (never wrapped in a caching
 * factory, hence {@code spring.jms.cache.enabled: false}) and the container must have no transaction manager.
 * With no transaction manager and the default {@code exposeListenerSession=true}, the container binds its
 * transacted session as a transactional resource keyed by that factory while the listener runs, and the
 * template finds it there. Either change gives the template a session of its own, whose send to {@code out}
 * would commit independently of the receive.
 */
@Configuration
public class TransactionConfig {

    private static final Logger log = LoggerFactory.getLogger(TransactionConfig.class);

    /**
     * Local transaction manager for the database work of the transactional scope. Its bean type makes Boot's
     * {@code DataSourceTransactionManagerAutoConfiguration} back off, so it is the only
     * {@code PlatformTransactionManager} in the context.
     *
     * @param dataSource Boot's {@code DataSource}, built from {@code spring.datasource.url}
     * @return the transaction manager behind every {@code @Transactional} method
     */
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Container factory for the listener on queue {@code in}: one consumer receiving in a locally transacted
     * session. When the listener throws, the container rolls the session back before the error handler runs,
     * so the error handler only logs the failure.
     *
     * @param connectionFactory Boot's Artemis {@code ConnectionFactory}, shared with {@link #jmsTemplate}
     * @return the session-transacted listener container factory
     */
    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(true);
        factory.setErrorHandler(t -> {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(t);
            log.error("JMS listener failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(), t);
        });
        return factory;
    }

    /**
     * Template for the send to queue {@code out}. On the listener thread it joins the listener's transacted
     * session without committing it; anywhere else it opens, uses and commits a session of its own. No default
     * destination is set: the caller names the queue ({@code jms.outbound-queue}).
     *
     * @param connectionFactory the same {@code ConnectionFactory} the listener container uses
     * @return the session-transacted, point-to-point template
     */
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        template.setPubSubDomain(false);
        return template;
    }
}
