/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.connection.SingleConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests {@link ArtemisClientConfig}: the post-processor on its own, then on Boot's auto-configured Artemis client
 * with {@code src/main/resources/application.yml} loaded as the application loads it.
 *
 * <p>No external broker is used. Embedded-mode cases run Boot's in-VM server with its data directory under
 * {@code target}; native-mode cases point the client at a local socket that accepts TCP connections and never
 * answers, which stands in for an unresponsive broker. The broker URLs of the unit cases are parsed, never dialled.
 */
@DisplayName("ArtemisClientConfig")
@ExtendWith(OutputCaptureExtension.class)
class ArtemisClientConfigTest {

    /** A native broker URL that no unit case ever connects to. */
    private static final String UNDIALLED_URL = "tcp://localhost:61616";

    /**
     * Data directory of every in-VM broker these contexts start, inside the build's {@code target} directory (Maven
     * runs tests from the project directory) rather than Boot's shared {@code java.io.tmpdir} default. The brokers are
     * non-persistent, so they keep no journal there.
     */
    private static final String ARTEMIS_DATA =
            Path.of("target", "artemis-data", "ArtemisClientConfigTest").toAbsolutePath().toString();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class, JmsAutoConfiguration.class))
            .withUserConfiguration(ArtemisClientConfig.class);

    /** How a unit case presents the Artemis factory to the post-processor. */
    enum Presentation {
        BEAN(factory -> factory),
        SINGLE_CONNECTION_TARGET(SingleConnectionFactory::new),
        CACHING_TARGET(CachingConnectionFactory::new);

        private final Function<ActiveMQConnectionFactory, Object> wrap;

        Presentation(Function<ActiveMQConnectionFactory, Object> wrap) {
            this.wrap = wrap;
        }
    }

    static Stream<Arguments> artemisFactories() {
        return Stream.of(
                arguments(null, UNDIALLED_URL, Presentation.BEAN, 10_000L),
                arguments("2500", UNDIALLED_URL, Presentation.BEAN, 2_500L),
                arguments(null, UNDIALLED_URL + "?callTimeout=5000", Presentation.BEAN, 5_000L),
                arguments(null, UNDIALLED_URL, Presentation.SINGLE_CONNECTION_TARGET, 10_000L),
                arguments(null, UNDIALLED_URL, Presentation.CACHING_TARGET, 10_000L),
                arguments("2500", UNDIALLED_URL + "?callTimeout=5000", Presentation.CACHING_TARGET, 5_000L));
    }

    @ParameterizedTest(name = "jms.call-timeout={0}, {1} as {2} -> callTimeout {3}")
    @MethodSource("artemisFactories")
    @DisplayName("an Artemis factory at Artemis' default gets jms.call-timeout; an explicit URL callTimeout is kept")
    void appliesTheBoundOnlyOverArtemisDefault(String configured, String brokerUrl, Presentation presentation,
            long expected) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        Object bean = presentation.wrap.apply(factory);

        Object processed = postProcessor(configured).postProcessAfterInitialization(bean, "jmsConnectionFactory");

        assertThat(processed).isSameAs(bean);
        assertThat(factory.getCallTimeout()).isEqualTo(expected);
    }

    static Stream<Arguments> unrelatedBeans() {
        return Stream.of(
                arguments("a bean that is no connection factory",
                        (Function<ConnectionFactory, Object>) vendor -> "not a connection factory"),
                arguments("a connection factory of another vendor",
                        (Function<ConnectionFactory, Object>) vendor -> vendor),
                arguments("a SingleConnectionFactory over another vendor's factory",
                        (Function<ConnectionFactory, Object>) SingleConnectionFactory::new),
                arguments("a CachingConnectionFactory over another vendor's factory",
                        (Function<ConnectionFactory, Object>) CachingConnectionFactory::new),
                arguments("a SingleConnectionFactory without a target",
                        (Function<ConnectionFactory, Object>) vendor -> new SingleConnectionFactory()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unrelatedBeans")
    @DisplayName("any other bean, a factory of another vendor or a wrapper without an Artemis target is untouched")
    void leavesEveryOtherBeanUnchanged(String description, Function<ConnectionFactory, Object> beanOverVendor) {
        ConnectionFactory vendor = mock(ConnectionFactory.class);
        Object bean = beanOverVendor.apply(vendor);

        Object processed = postProcessor(null).postProcessAfterInitialization(bean, "someBean");

        assertThat(processed).isSameAs(bean);
        verifyNoInteractions(vendor);
    }

    @ParameterizedTest(name = "jms.call-timeout={0}")
    @ValueSource(strings = {"0", "-1", "-9223372036854775808"})
    @DisplayName("a zero or negative jms.call-timeout is rejected")
    void rejectsANonPositiveBound(String configured) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> postProcessor(configured))
                .withMessage("jms.call-timeout must be a positive number of milliseconds, but was " + configured);
    }

    @ParameterizedTest(name = "spring.jms.cache.enabled={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("application.yml's embedded broker: Boot's jmsConnectionFactory gets 10000 ms and still delivers")
    void boundsBootsEmbeddedModeFactory(boolean cacheEnabled, CapturedOutput output) {
        contextRunner
                .withPropertyValues(
                        "spring.jms.cache.enabled=" + cacheEnabled,
                        "spring.artemis.embedded.data-directory=" + ARTEMIS_DATA)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddedActiveMQ.class);
                    ConnectionFactory jmsConnectionFactory = jmsConnectionFactory(context);
                    assertThat(jmsConnectionFactory).isInstanceOf(
                            cacheEnabled ? CachingConnectionFactory.class : ActiveMQConnectionFactory.class);
                    assertThat(artemisFactory(jmsConnectionFactory).getCallTimeout()).isEqualTo(10_000L);

                    JmsTemplate template = context.getBean(JmsTemplate.class);
                    template.setReceiveTimeout(5_000L);
                    template.convertAndSend("sales", "bounded");
                    assertThat(template.receiveAndConvert("sales")).isEqualTo("bounded");
                });
        // A post-processor from an instance @Bean method would make Spring create this configuration class early.
        assertThat(output).doesNotContain("is not eligible for getting processed by all BeanPostProcessors")
                .doesNotContain("returns an object assignable to Spring's BeanPostProcessor interface");
    }

    @ParameterizedTest(name = "spring.artemis.embedded.enabled={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("native mode starts the in-VM broker unless spring.artemis.embedded.enabled is false")
    void nativeModeStartsNoEmbeddedBrokerOnlyWhenDisabled(boolean embeddedEnabled) throws IOException {
        try (ServerSocket unresponsiveBroker = unresponsiveBroker()) {
            contextRunner
                    .withPropertyValues(
                            "spring.artemis.mode=native",
                            "spring.artemis.embedded.enabled=" + embeddedEnabled,
                            "spring.artemis.embedded.data-directory=" + ARTEMIS_DATA,
                            "spring.artemis.broker-url=" + brokerUrl(unresponsiveBroker))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(EmbeddedActiveMQ.class)).hasSize(embeddedEnabled ? 1 : 0);
                        assertThat(artemisFactory(jmsConnectionFactory(context)).getCallTimeout())
                                .isEqualTo(10_000L);
                    });
        }
    }

    @Test
    @DisplayName("native mode: a broker that never answers fails the connection after jms.call-timeout, not 30 s")
    void unresponsiveBrokerFailsWithinTheBound() throws IOException {
        long boundMillis = 1_000L;
        try (ServerSocket unresponsiveBroker = unresponsiveBroker()) {
            contextRunner
                    .withPropertyValues(
                            "spring.artemis.mode=native",
                            "spring.artemis.embedded.enabled=false",
                            "spring.artemis.broker-url=" + brokerUrl(unresponsiveBroker),
                            "jms.call-timeout=" + boundMillis)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ConnectionFactory jmsConnectionFactory = jmsConnectionFactory(context);

                        long started = System.nanoTime();
                        Throwable failure = assertTimeoutPreemptively(Duration.ofSeconds(25),
                                () -> catchThrowable(jmsConnectionFactory::createConnection));
                        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

                        assertThat(failure).isInstanceOf(JMSException.class)
                                .hasRootCauseInstanceOf(ActiveMQConnectionTimedOutException.class);
                        // Artemis' own default would hold the call for 30 s; the 1 s bound ends it within seconds.
                        assertThat(elapsedMillis).isGreaterThanOrEqualTo(boundMillis)
                                .isLessThan(10 * boundMillis)
                                .isLessThan(ActiveMQClient.DEFAULT_CALL_TIMEOUT);
                    });
        }
    }

    @Test
    @DisplayName("a zero jms.call-timeout stops the application from starting")
    void nonPositiveBoundFailsStartup() {
        contextRunner
                .withPropertyValues(
                        "jms.call-timeout=0",
                        "spring.artemis.embedded.data-directory=" + ARTEMIS_DATA)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("jms.call-timeout must be a positive number of milliseconds, but was 0"));
    }

    /** The post-processor built from an environment holding {@code configured}, or no value when {@code null}. */
    private static BeanPostProcessor postProcessor(String configured) {
        MockEnvironment environment = new MockEnvironment();
        if (configured != null) {
            environment.setProperty(ArtemisClientConfig.CALL_TIMEOUT_PROPERTY, configured);
        }
        return ArtemisClientConfig.artemisCallTimeoutPostProcessor(environment);
    }

    private static ConnectionFactory jmsConnectionFactory(ApplicationContext context) {
        return context.getBean("jmsConnectionFactory", ConnectionFactory.class);
    }

    /** The Artemis factory Boot's {@code jmsConnectionFactory} is or wraps. */
    private static ActiveMQConnectionFactory artemisFactory(ConnectionFactory jmsConnectionFactory) {
        ConnectionFactory target = jmsConnectionFactory instanceof SingleConnectionFactory single
                ? single.getTargetConnectionFactory()
                : jmsConnectionFactory;
        assertThat(target).isInstanceOf(ActiveMQConnectionFactory.class);
        return (ActiveMQConnectionFactory) target;
    }

    /**
     * A loopback socket on a free port that is never accepted from: the kernel completes each TCP handshake, so the
     * client connects, but no byte is ever answered, like a paused broker.
     */
    private static ServerSocket unresponsiveBroker() throws IOException {
        return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    private static String brokerUrl(ServerSocket socket) {
        return "tcp://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
    }
}
