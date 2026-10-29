/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import jakarta.jms.ConnectionFactory;

import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;

import com.arjuna.ats.internal.jdbc.drivers.modifiers.ModifierFactory;
import com.arjuna.ats.internal.jdbc.drivers.modifiers.SupportsMultipleConnectionsModifier;

/**
 * XA transaction infrastructure replacing the global elements of {@code fulfillment.xml} (lines 4-15 and 23):
 * <ul>
 *   <li>{@code jms:activemq-xa-connector brokerURL="vm://localhost"}: Boot's {@code ArtemisXAConnectionFactoryConfiguration}
 *       builds the embedded broker ({@code spring.artemis.*}, queue {@code inhouseOrder}), the {@code @Primary}
 *       {@code jmsConnectionFactory} that Narayana's {@code XAConnectionFactoryWrapper} enlists in JTA transactions,
 *       and the unwrapped {@code nonXaJmsConnectionFactory}.</li>
 *   <li>The {@code StandardXADataSource} bean over {@code jdbc:derby:muleEmbeddedDB;create=true}: Boot's
 *       {@code XADataSourceAutoConfiguration} builds {@code dataSource} from {@code spring.datasource.xa.*}
 *       (Derby {@code EmbeddedXADataSource}) and Narayana's {@code XADataSourceWrapper} enlists its connections;
 *       {@link #derbyConnectionModifier} registers Narayana's {@code SupportsMultipleConnectionsModifier} for Derby, so
 *       each connection stays bound to its transaction until the transaction completes.</li>
 *   <li>{@code jbossts:transaction-manager}: the Narayana starter's {@code transactionManager}
 *       ({@code JtaTransactionManager}), with {@code narayana.default-timeout} 600 and its object store under
 *       {@code narayana.log-dir}.</li>
 *   <li>{@code db:generic-config} over that data source: {@link #jdbcTemplate}.</li>
 * </ul>
 * None of the auto-configured beans is redeclared. Boot enables JMS listeners, transaction management and Spring WS
 * itself, so this class carries none of their {@code Enable...} annotations; enabling Spring WS here would also switch
 * off Boot's {@code MessageDispatcherServlet}.
 *
 * <p>The flows served: {@code inhouseOrder} (lines 98-129, {@code xa-transaction ALWAYS_BEGIN}, the insert
 * {@code ALWAYS_JOIN}) through {@link #inHouseListenerContainerFactory}; the request-response dispatch of
 * {@code orderService} (line 39) through {@link #inHouseRequestJmsTemplate}; {@code auditService} (lines 130-142), which
 * runs its own JTA transaction, and the untransacted DDL of {@code databaseInitialisation} (lines 159-172), both
 * through {@link #jdbcTemplate}.
 */
@Configuration(proxyBeanMethods = false)
public class XaTransactionConfig {

	/** JVM system property Derby reads, once, when its engine boots. */
	static final String DERBY_SYSTEM_HOME_PROPERTY = "derby.system.home";

	/** Environment key naming the Derby system home when the system property is not set. */
	static final String DERBY_SYSTEM_HOME_KEY = "derby.system-home";

	/** Default Derby system home, inside the build directory so {@code muleEmbeddedDB} never lands in the project root. */
	static final String DEFAULT_DERBY_SYSTEM_HOME = "target/derby";

	/**
	 * The driver name Derby's embedded {@code DatabaseMetaData.getDriverName()} reports, under which Narayana looks up
	 * the modifier of each connection it enlists. Narayana's built-in modifier list has no entry for it.
	 */
	static final String DERBY_EMBEDDED_DRIVER_NAME = "Apache Derby Embedded JDBC Driver";

	/** Narayana's version wildcard: the modifier applies to every major and minor version of the driver. */
	static final int ANY_DRIVER_VERSION = -1;

	/**
	 * The Narayana modifier registered for Derby. It declares that the driver supports several connections per
	 * transaction, so Narayana keeps each connection bound to its transaction and defers the close the application
	 * requests until the transaction completes. Without a modifier Narayana logs {@code ARJUNA017008} and hands the
	 * pooled connection to another thread while its transaction is still committing, which leaves orphaned Derby
	 * transactions and connections behind. Its {@code Xid} and {@code XAResource.start} flags are Derby's own.
	 */
	static final String DERBY_CONNECTION_MODIFIER = SupportsMultipleConnectionsModifier.class.getName();

	/** ActiveMQ's default redelivery delay, which the original XA connector used for the rolled-back in-house deliveries. */
	static final long IN_HOUSE_REDELIVERY_DELAY_MS = 1000L;

	/** Artemis' value for unlimited delivery attempts: the broker never moves an in-house message to a dead-letter address. */
	static final int UNLIMITED_DELIVERY_ATTEMPTS = -1;

	/**
	 * Points Derby at its system home before the XA {@code DataSource} boots the engine. A bean factory post-processor
	 * runs before any regular bean is instantiated, and it is static so that this configuration class is not
	 * instantiated that early. A value already set (surefire sets one per build) is left unchanged.
	 *
	 * @param environment the application environment holding {@code derby.system-home}
	 * @return the post-processor that creates the directory and sets {@code derby.system.home}
	 */
	@Bean
	public static BeanFactoryPostProcessor derbySystemHome(Environment environment) {
		return beanFactory -> {
			if (System.getProperty(DERBY_SYSTEM_HOME_PROPERTY) != null) {
				return;
			}
			String home = environment.getProperty(DERBY_SYSTEM_HOME_KEY, DEFAULT_DERBY_SYSTEM_HOME);
			try {
				Files.createDirectories(Path.of(home));
			} catch (IOException e) {
				throw new BeanInitializationException("Cannot create the Derby system home " + home, e);
			}
			System.setProperty(DERBY_SYSTEM_HOME_PROPERTY, home);
		};
	}

	/**
	 * Registers {@link #DERBY_CONNECTION_MODIFIER} for {@link #DERBY_EMBEDDED_DRIVER_NAME}, any version, before the XA
	 * {@code DataSource} opens its first connection. A bean factory post-processor runs before any regular bean is
	 * instantiated, and it is static so that this configuration class is not instantiated that early. Narayana's
	 * modifier registry is JVM-wide, so registering it again replaces the entry with an identical one. Narayana skips a
	 * modifier it cannot instantiate without failing, so the registration is read back and the start-up fails when it
	 * is missing.
	 *
	 * @return the post-processor that registers the Derby modifier with Narayana's {@code ModifierFactory}
	 */
	@Bean
	public static BeanFactoryPostProcessor derbyConnectionModifier() {
		return beanFactory -> {
			ModifierFactory.putModifier(DERBY_EMBEDDED_DRIVER_NAME, ANY_DRIVER_VERSION, ANY_DRIVER_VERSION,
					DERBY_CONNECTION_MODIFIER);
			if (!(ModifierFactory.getModifier(DERBY_EMBEDDED_DRIVER_NAME, ANY_DRIVER_VERSION,
					ANY_DRIVER_VERSION) instanceof SupportsMultipleConnectionsModifier)) {
				throw new BeanInitializationException("Narayana did not register " + DERBY_CONNECTION_MODIFIER + " for "
						+ DERBY_EMBEDDED_DRIVER_NAME);
			}
		};
	}

	/**
	 * Redelivery settings of the embedded broker's in-house queue. A rolled-back delivery returns after 1000 ms, and
	 * the broker never dead-letters it: {@code trigger/InHouseOrderListener} counts {@code JMSXDeliveryCount} itself and,
	 * once the {@code rollback-exception-strategy maxRedeliveryAttempts="3"} limit is exhausted, runs
	 * {@code defaultErrorHandler} and commits. Boot applies customizers to the embedded broker only.
	 *
	 * @param queue the in-house queue name, {@code orders.inhouse.queue}
	 * @return the customizer adding the address setting for that queue
	 */
	@Bean
	public ArtemisConfigurationCustomizer inHouseQueueAddressSettings(
			@Value("${orders.inhouse.queue:inhouseOrder}") String queue) {
		return configuration -> configuration.addAddressSetting(queue, new AddressSettings()
				.setRedeliveryDelay(IN_HOUSE_REDELIVERY_DELAY_MS)
				.setMaxDeliveryAttempts(UNLIMITED_DELIVERY_ATTEMPTS));
	}

	/**
	 * Container factory of the {@code inhouseOrder} listener, reproducing {@code xa-transaction action="ALWAYS_BEGIN"}:
	 * each delivery is received in a new JTA transaction that the XA Artemis session and the in-house insert
	 * ({@code ALWAYS_JOIN}) both join, and the listener's reply is sent on that session, so it leaves only when the
	 * insert commits. A single consumer processes deliveries, their redeliveries and replies strictly one after another;
	 * the number of concurrent receivers changes throughput only, never the outcome of a delivery. The default
	 * {@code SimpleMessageConverter} is kept, so {@code OrderItem} and {@code PurchaseReceipt} travel as
	 * {@code ObjectMessage} bodies.
	 *
	 * <p>The container keeps one Artemis connection open, as the original XA connector did ({@code CACHE_CONNECTION}).
	 * With a transaction manager set it would default to {@code CACHE_NONE} and open and close a connection on every
	 * receive, about once a second while the queue is idle. Sessions stay uncached: each receive creates its session
	 * inside its own JTA transaction, which is where Narayana's connection proxy enlists the XA session.
	 *
	 * @param jmsConnectionFactory the {@code @Primary} XA-wrapped Artemis connection factory
	 * @param transactionManager   the Narayana {@code JtaTransactionManager}
	 * @return the JTA-transacted, single-consumer listener container factory
	 */
	@Bean
	public DefaultJmsListenerContainerFactory inHouseListenerContainerFactory(ConnectionFactory jmsConnectionFactory,
			PlatformTransactionManager transactionManager) {
		DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
		factory.setConnectionFactory(jmsConnectionFactory);
		factory.setTransactionManager(transactionManager);
		factory.setSessionTransacted(true);
		factory.setConcurrency("1");
		factory.setCacheLevel(DefaultMessageListenerContainer.CACHE_CONNECTION);
		return factory;
	}

	/**
	 * General-purpose template over the XA connection factory; inside a JTA transaction it sends on the enlisted
	 * session. Declaring it keeps an unqualified {@code JmsTemplate} injectable, because Boot's own
	 * {@code jmsTemplate} backs off once {@link #inHouseRequestJmsTemplate} exists.
	 *
	 * @param jmsConnectionFactory the {@code @Primary} XA-wrapped Artemis connection factory
	 * @return the primary {@code JmsTemplate}
	 */
	@Bean
	@Primary
	public JmsTemplate jmsTemplate(ConnectionFactory jmsConnectionFactory) {
		return new JmsTemplate(jmsConnectionFactory);
	}

	/**
	 * Template of the request-response {@code jms:outbound-endpoint queue="inhouseOrder"} (line 39). The outbound
	 * dispatch is not part of a transaction, and {@code JmsTemplate.sendAndReceive} creates a temporary reply queue,
	 * which needs a non-transacted session on a connection that is not enlisted in JTA; hence the
	 * {@code nonXaJmsConnectionFactory}. The receive timeout is Mule's default response timeout of 10000 ms. Callers
	 * inject it with {@code @Qualifier("inHouseRequestJmsTemplate")}; an unqualified {@code JmsTemplate} resolves to
	 * the primary XA one.
	 *
	 * @param connectionFactory the non-XA Artemis connection factory
	 * @param replyTimeoutMs    how long to wait for the in-house reply, {@code orders.inhouse.reply-timeout-ms}
	 * @return the non-transacted request-reply template
	 * @throws IllegalArgumentException when {@code replyTimeoutMs} is not between 1 and {@link Long#MAX_VALUE}, since
	 *                                  {@code JmsTemplate} waits for ever on 0 and does not wait at all on a negative
	 *                                  value; no template is built
	 */
	@Bean
	public JmsTemplate inHouseRequestJmsTemplate(
			@Qualifier("nonXaJmsConnectionFactory") ConnectionFactory connectionFactory,
			@Value("${orders.inhouse.reply-timeout-ms:10000}") long replyTimeoutMs) {
		if (replyTimeoutMs < 1) {
			throw new IllegalArgumentException("orders.inhouse.reply-timeout-ms must be between 1 and " + Long.MAX_VALUE
					+ ", was " + replyTimeoutMs);
		}
		JmsTemplate template = new JmsTemplate(connectionFactory);
		template.setSessionTransacted(false);
		template.setReceiveTimeout(replyTimeoutMs);
		return template;
	}

	/**
	 * {@code db:generic-config} over the Narayana-wrapped XA {@code DataSource}; Boot's {@code JdbcTemplate} backs off.
	 * Inside a JTA transaction (the in-house insert, the audit insert in its own transaction) the connection is enlisted
	 * in that transaction; outside one, Derby auto-commits each statement, as {@code db:execute-ddl} did for
	 * {@code /populate}.
	 *
	 * @param dataSource the XA {@code DataSource} built from {@code spring.datasource.xa.*}
	 * @return the {@code JdbcTemplate} of every database operation
	 */
	@Bean
	public JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}
}
