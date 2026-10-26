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

    /** Longest text {@link #logSafe} emits before appending {@link #TRUNCATION_MARKER}. */
    private static final int MAX_LOGGED_LENGTH = 256;

    /** Appended by {@link #logSafe} where it cuts a value at {@link #MAX_LOGGED_LENGTH}. */
    private static final String TRUNCATION_MARKER = "...";

    /** Throw site logged for a failure that carries no stack frame. */
    private static final String UNKNOWN_THROW_SITE = "unknown";

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
     * so the error handler only logs the failure, and logs only the type and throw site of its most specific
     * cause, never a message or stack trace: those can carry rejected order values and SQL or connection detail.
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
            log.error("JMS listener failed: {} at {}", logSafe(typeName(cause)), logSafe(throwSite(cause)));
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

    /**
     * The failure's simple class name, or its binary name when the class has none (anonymous classes).
     *
     * @param failure the failure the listener raised
     * @return the name identifying the failure's type
     */
    private static String typeName(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * Where the failure was thrown, from its top stack frame: {@code declaringClass.method:line}, without the line
     * when the frame records none (native or debug-less code).
     *
     * @param failure the failure the listener raised
     * @return the throw site, or {@link #UNKNOWN_THROW_SITE} when the stack trace is empty or suppressed
     */
    private static String throwSite(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames.length == 0) {
            return UNKNOWN_THROW_SITE;
        }
        StackTraceElement top = frames[0];
        String site = top.getClassName() + '.' + top.getMethodName();
        return top.getLineNumber() >= 0 ? site + ':' + top.getLineNumber() : site;
    }

    /**
     * Makes a value safe for a single log line: every ISO control character (CR, LF, TAB and the rest) and the
     * Unicode line and paragraph separators become Java-style Unicode escapes (a backslash, {@code u} and four
     * hex digits), so the value cannot start a forged line, and the result is cut at {@link #MAX_LOGGED_LENGTH}
     * characters, marked by {@link #TRUNCATION_MARKER}.
     *
     * @param value the text to log
     * @return the escaped, bounded text
     */
    private static String logSafe(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_LOGGED_LENGTH));
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            boolean escaped = Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                    || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR;
            String text = escaped ? String.format("\\u%04X", codePoint) : new String(Character.toChars(codePoint));
            if (safe.length() + text.length() > MAX_LOGGED_LENGTH) {
                return safe.append(TRUNCATION_MARKER).toString();
            }
            safe.append(text);
            index += Character.charCount(codePoint);
        }
        return safe.toString();
    }
}
