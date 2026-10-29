/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config.AmqpConfig.SalesDestination;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ChannelContinuationTimeoutException;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.AMQImpl;
import com.rabbitmq.client.impl.Frame;
import com.rabbitmq.client.impl.LongStringHelper;
import com.rabbitmq.client.impl.Method;

/**
 * Tests {@link AmqpConfig} on Boot's auto-configured RabbitMQ client, with
 * {@code src/main/resources/application.yml} loaded as the application loads it. The transport-budget
 * cases run against {@link AmqpProtocolStub}, an AMQP 0-9-1 peer that completes the handshake and then
 * withholds one reply the temporary-reply-queue setup waits for.
 */
@DisplayName("AmqpConfig")
class AmqpConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(AmqpConfig.class);

    private AmqpProtocolStub stub;

    @AfterEach
    void closeStub() throws IOException {
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    @DisplayName("defaults keep the original destination, a 10000 ms reply timeout and temporary reply queues,"
            + " on the correlating template")
    void defaultsKeepTheOriginalEndpointSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SalesDestination.class))
                    .isEqualTo(new SalesDestination("sales_exchange", "sales_queue", "", 10000L));

            RabbitTemplate template = context.getBean(RabbitTemplate.class);
            assertThat(template).isExactlyInstanceOf(CorrelatedReplyRabbitTemplate.class);
            assertThat(ReflectionTestUtils.getField(template, "replyTimeout")).isEqualTo(10000L);
            assertThat(ReflectionTestUtils.getField(template, "useTemporaryReplyQueues")).isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(template, "retryTemplate")).isNull();
            assertThat(context.getBeansOfType(Declarable.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("application.yml gives Boot's native connection factory 10 s transport budgets and no recovery")
    void defaultsBoundTheTransport() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ConnectionFactory nativeFactory =
                    context.getBean(CachingConnectionFactory.class).getRabbitConnectionFactory();
            assertThat(nativeFactory.getConnectionTimeout()).isEqualTo(10000);
            assertThat(nativeFactory.getChannelRpcTimeout()).isEqualTo(10000);
            assertThat(nativeFactory.getHandshakeTimeout()).isEqualTo(10000);
            assertThat(nativeFactory.isAutomaticRecoveryEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("the context starts without opening a broker connection")
    void contextStartsWithoutConnecting() throws IOException {
        stub = AmqpProtocolStub.start(Withheld.NOTHING);
        contextRunner.withPropertyValues(stub.connectionProperties()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RabbitTemplate.class)).isNotNull();
            assertThat(stub.acceptedConnections()).isZero();
        });
    }

    @ParameterizedTest(name = "amqp.response-timeout={0}")
    @ValueSource(longs = {1L, 25000L})
    @DisplayName("a positive amqp.response-timeout reaches the template and the destination")
    void positiveResponseTimeoutIsApplied(long responseTimeoutMillis) {
        contextRunner.withPropertyValues("amqp.response-timeout=" + responseTimeoutMillis).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SalesDestination.class).responseTimeoutMillis())
                    .isEqualTo(responseTimeoutMillis);
            assertThat(ReflectionTestUtils.getField(context.getBean(RabbitTemplate.class), "replyTimeout"))
                    .isEqualTo(responseTimeoutMillis);
        });
    }

    @ParameterizedTest(name = "amqp.response-timeout={0}")
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("a zero or negative amqp.response-timeout stops the application from starting")
    void nonPositiveResponseTimeoutFailsStartup(long responseTimeoutMillis) {
        contextRunner.withPropertyValues("amqp.response-timeout=" + responseTimeoutMillis).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("amqp.response-timeout must be a positive number of milliseconds, but was "
                            + responseTimeoutMillis);
        });
    }

    @ParameterizedTest(name = "responseTimeoutMillis={0}")
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("SalesDestination rejects a zero or negative reply timeout")
    void salesDestinationRejectsNonPositiveResponseTimeout(long responseTimeoutMillis) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SalesDestination("sales_exchange", "sales_queue", "", responseTimeoutMillis))
                .withMessage("responseTimeoutMillis must be a positive number of milliseconds, but was "
                        + responseTimeoutMillis);
    }

    @ParameterizedTest(name = "{0}=0")
    @ValueSource(strings = {"spring.rabbitmq.connection-timeout", "spring.rabbitmq.channel-rpc-timeout"})
    @DisplayName("a zero (unlimited) transport timeout stops the application from starting")
    void unlimitedTransportTimeoutFailsStartup(String key) {
        contextRunner.withPropertyValues(key + "=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith(key + " must be a positive duration, but was 0 ms");
        });
    }

    @Test
    @DisplayName("a withheld queue.declare-ok fails sendAndReceive after the 10 s channel RPC budget, once")
    void withheldQueueDeclareOkFailsAfterTheDefaultChannelRpcBudget() throws IOException {
        stub = AmqpProtocolStub.start(Withheld.QUEUE_DECLARE_OK);
        contextRunner.withPropertyValues(stub.connectionProperties()).run(context -> {
            RabbitTemplate template = context.getBean(RabbitTemplate.class);

            long elapsedMillis = assertTimeoutPreemptively(Duration.ofSeconds(40),
                    () -> millisUntilSendAndReceiveFails(template));

            assertThat(elapsedMillis).isBetween(9_500L, 30_000L);
            assertThat(stub.acceptedConnections()).isEqualTo(1);
            List<AMQP.Queue.Declare> declares = stub.received(AMQP.Queue.Declare.class);
            assertThat(declares).hasSize(1);
            AMQP.Queue.Declare declare = declares.get(0);
            assertThat(declare.getQueue()).isEmpty();
            assertThat(declare.getPassive()).isFalse();
            assertThat(declare.getExclusive()).isTrue();
            assertThat(declare.getAutoDelete()).isTrue();
            assertThat(stub.received(AMQP.Basic.Consume.class)).isEmpty();
            assertThat(stub.received(AMQP.Basic.Publish.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("a withheld basic.consume-ok fails sendAndReceive after the channel RPC budget, before publishing")
    void withheldBasicConsumeOkFailsAfterTheChannelRpcBudget() throws IOException {
        stub = AmqpProtocolStub.start(Withheld.BASIC_CONSUME_OK);
        contextRunner.withPropertyValues(stub.connectionProperties())
                .withPropertyValues("spring.rabbitmq.channel-rpc-timeout=1s")
                .run(context -> {
                    RabbitTemplate template = context.getBean(RabbitTemplate.class);

                    long elapsedMillis = assertTimeoutPreemptively(Duration.ofSeconds(20),
                            () -> millisUntilSendAndReceiveFails(template));

                    assertThat(elapsedMillis).isBetween(900L, 9_000L);
                    assertThat(stub.acceptedConnections()).isEqualTo(1);
                    assertThat(stub.received(AMQP.Queue.Declare.class)).hasSize(1);
                    List<AMQP.Basic.Consume> consumes = stub.received(AMQP.Basic.Consume.class);
                    assertThat(consumes).hasSize(1);
                    assertThat(consumes.get(0).getQueue()).isEqualTo(AmqpProtocolStub.REPLY_QUEUE);
                    assertThat(stub.received(AMQP.Basic.Publish.class)).isEmpty();
                });
    }

    /**
     * Sends a request-reply message to {@code sales_exchange} with the empty routing key and times how
     * long the call takes to fail.
     *
     * @param template Boot's template, customized by {@link AmqpConfig}
     * @return the milliseconds from the call until its {@link AmqpException}
     */
    private static long millisUntilSendAndReceiveFails(RabbitTemplate template) {
        Message request = MessageBuilder.withBody("test".getBytes(StandardCharsets.UTF_8)).build();
        long start = System.nanoTime();
        assertThatExceptionOfType(AmqpException.class)
                .isThrownBy(() -> template.sendAndReceive("sales_exchange", "", request))
                .withCauseInstanceOf(ChannelContinuationTimeoutException.class);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** The setup reply {@link AmqpProtocolStub} never sends. */
    private enum Withheld {
        NOTHING,
        QUEUE_DECLARE_OK,
        BASIC_CONSUME_OK
    }

    /**
     * A minimal AMQP 0-9-1 peer on a free loopback port, built from amqp-client's own frame and method
     * codecs. It answers the connection handshake, {@code channel.open}, {@code queue.declare},
     * {@code basic.consume}, {@code basic.cancel} and both close methods, except the one reply named by
     * its {@link Withheld} mode. Every method it receives is recorded; heartbeats and content frames are
     * ignored.
     */
    private static final class AmqpProtocolStub implements AutoCloseable {

        /** The server-named reply queue every answered {@code queue.declare} returns. */
        static final String REPLY_QUEUE = "amq.gen-stub-reply";

        private static final byte[] PROTOCOL_HEADER = {'A', 'M', 'Q', 'P', 0, 0, 9, 1};

        private final ServerSocket serverSocket;

        private final Withheld withheld;

        private final AtomicInteger acceptedConnections = new AtomicInteger();

        private final List<Method> receivedMethods = new CopyOnWriteArrayList<>();

        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        private final List<Thread> threads = new CopyOnWriteArrayList<>();

        private AmqpProtocolStub(ServerSocket serverSocket, Withheld withheld) {
            this.serverSocket = serverSocket;
            this.withheld = withheld;
        }

        static AmqpProtocolStub start(Withheld withheld) throws IOException {
            AmqpProtocolStub stub = new AmqpProtocolStub(
                    new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), withheld);
            stub.startThread("amqp-stub-acceptor", stub::acceptLoop);
            return stub;
        }

        /** The {@code spring.rabbitmq.*} values that point Boot's client at this stub. */
        String[] connectionProperties() {
            return new String[] {
                "spring.rabbitmq.host=" + serverSocket.getInetAddress().getHostAddress(),
                "spring.rabbitmq.port=" + serverSocket.getLocalPort()
            };
        }

        int acceptedConnections() {
            return acceptedConnections.get();
        }

        <T> List<T> received(Class<T> methodType) {
            return receivedMethods.stream().filter(methodType::isInstance).map(methodType::cast).toList();
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    acceptedConnections.incrementAndGet();
                    sockets.add(socket);
                    startThread("amqp-stub-connection", () -> serve(socket));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                byte[] header = new byte[PROTOCOL_HEADER.length];
                in.readFully(header);
                if (!Arrays.equals(header, PROTOCOL_HEADER)) {
                    return;
                }
                send(out, 0, new AMQImpl.Connection.Start(0, 9, Map.of(),
                        LongStringHelper.asLongString("PLAIN"), LongStringHelper.asLongString("en_US")));
                while (true) {
                    Frame frame = Frame.readFrom(in, Integer.MAX_VALUE);
                    if (frame == null || frame.type != AMQP.FRAME_METHOD) {
                        continue;
                    }
                    Method method = AMQImpl.readMethodFrom(frame.getInputStream());
                    receivedMethods.add(method);
                    Method reply = replyTo(method);
                    if (reply != null) {
                        send(out, frame.channel, reply);
                    }
                    if (method instanceof AMQP.Connection.Close) {
                        return;
                    }
                }
            } catch (IOException connectionEnded) {
                // The client closed the socket, or close() did: this connection is over.
            }
        }

        private Method replyTo(Method method) {
            if (method instanceof AMQP.Connection.StartOk) {
                return new AMQImpl.Connection.Tune(2047, 131072, 0);
            }
            if (method instanceof AMQP.Connection.Open) {
                return new AMQImpl.Connection.OpenOk("");
            }
            if (method instanceof AMQP.Channel.Open) {
                return new AMQImpl.Channel.OpenOk(LongStringHelper.asLongString(""));
            }
            if (method instanceof AMQP.Queue.Declare) {
                return withheld == Withheld.QUEUE_DECLARE_OK
                        ? null
                        : new AMQImpl.Queue.DeclareOk(REPLY_QUEUE, 0, 0);
            }
            if (method instanceof AMQP.Basic.Consume consume) {
                return withheld == Withheld.BASIC_CONSUME_OK
                        ? null
                        : new AMQImpl.Basic.ConsumeOk(consume.getConsumerTag());
            }
            if (method instanceof AMQP.Basic.Cancel cancel) {
                return new AMQImpl.Basic.CancelOk(cancel.getConsumerTag());
            }
            if (method instanceof AMQP.Channel.Close) {
                return new AMQImpl.Channel.CloseOk();
            }
            if (method instanceof AMQP.Connection.Close) {
                return new AMQImpl.Connection.CloseOk();
            }
            return null;
        }

        private static void send(DataOutputStream out, int channel, Method method) throws IOException {
            method.toFrame(channel).writeTo(out);
            out.flush();
        }

        private void startThread(String name, Runnable task) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : sockets) {
                socket.close();
            }
            try {
                for (Thread thread : threads) {
                    thread.join(TimeUnit.SECONDS.toMillis(5));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
