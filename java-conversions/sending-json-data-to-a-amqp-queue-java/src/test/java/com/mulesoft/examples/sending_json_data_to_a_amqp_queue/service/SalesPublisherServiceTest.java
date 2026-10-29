/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config.AmqpConfig;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ChannelContinuationTimeoutException;
import com.rabbitmq.client.ShutdownSignalException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link SalesPublisherService}, the {@code logger} and request-response
 * {@code amqp:outbound-endpoint} of flow {@code json-to-rabbitmqFlow} ({@code json-to-rabbitmq.xml:8-9}). The
 * {@link RabbitTemplate} and the {@link Channel} its callback receives are Mockito mocks, so no broker is
 * involved; the INFO logger line is read from the service's own Logback logger.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalesPublisherService")
class SalesPublisherServiceTest {

    private static final String EXCHANGE = "sales_exchange";

    private static final String QUEUE = "sales_queue";

    /** The body the original {@code SendingJsonDataToAmqpQueueIT} posts and expects on {@code sales_queue}. */
    private static final String PAYLOAD = "test";

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private Channel channel;

    private SalesPublisherService service;

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    private Level originalLevel;

    /** Log events already recorded when the template ran the service's channel callback; -1 until it runs. */
    private final AtomicInteger logEventsAtChannelCallback = new AtomicInteger(-1);

    /** The exception the mocked template raised from the channel callback, as {@code RabbitTemplate} raises it. */
    private final AtomicReference<RuntimeException> templateFailure = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        AmqpConfig.SalesDestination destination =
                new AmqpConfig.SalesDestination(EXCHANGE, QUEUE, AmqpConfig.DEFAULT_ROUTING_KEY, 10000L);
        service = new SalesPublisherService(rabbitTemplate, destination);

        logger = (Logger) LoggerFactory.getLogger(SalesPublisherService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("checks sales_exchange and sales_queue passively")
    void publishChecksExchangeAndQueuePassively() throws Exception {
        runChannelCallbackOnMockedChannel();

        service.publish(bytes(PAYLOAD));

        verify(channel).exchangeDeclarePassive(EXCHANGE);
        verify(channel).queueDeclarePassive(QUEUE);
    }

    @Test
    @DisplayName("never declares, binds or otherwise changes the exchange or the queue")
    void publishNeverDeclaresOrBindsAnything() throws Exception {
        runChannelCallbackOnMockedChannel();

        service.publish(bytes(PAYLOAD));

        verify(channel).exchangeDeclarePassive(EXCHANGE);
        verify(channel).queueDeclarePassive(QUEUE);
        // Every exchangeDeclare, queueDeclare, queueBind, exchangeBind and delete overload is a further
        // channel interaction, so none of them was made.
        verifyNoMoreInteractions(channel);
        verify(rabbitTemplate).execute(any());
        verify(rabbitTemplate).sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class));
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("logs, then checks the exchange and the queue, then publishes, in the flow's order")
    void passiveChecksRunBeforeSend() throws Exception {
        runChannelCallbackOnMockedChannel();

        service.publish(bytes(PAYLOAD));

        assertThat(logEventsAtChannelCallback).as("log events written before the passive checks").hasValue(1);
        InOrder order = inOrder(rabbitTemplate, channel);
        order.verify(rabbitTemplate).execute(any());
        order.verify(channel).exchangeDeclarePassive(EXCHANGE);
        order.verify(channel).queueDeclarePassive(QUEUE);
        order.verify(rabbitTemplate)
                .sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class));
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloads")
    @DisplayName("publishes the unchanged body as a persistent message, with no content type or priority, to"
            + " sales_exchange with the empty routing key")
    void publishSendsUnchangedBodyAsPersistentMessage(String description, byte[] payload) {
        runChannelCallbackOnMockedChannel();
        byte[] sent = payload.clone();

        service.publish(sent);

        ArgumentCaptor<Message> published = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), published.capture());
        assertThat(published.getValue().getBody()).isEqualTo(payload);
        MessageProperties properties = published.getValue().getMessageProperties();
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getContentType()).as("no content type, as the original set none").isNull();
        assertThat(properties.getPriority()).as("no priority, as the original set none").isNull();
        // The properties as RabbitTemplate publishes them, through the same converter.
        AMQP.BasicProperties wire =
                new DefaultMessagePropertiesConverter().fromMessageProperties(properties, "UTF-8");
        assertThat(wire.getContentType()).as("content_type on the wire").isNull();
        assertThat(wire.getPriority()).as("priority on the wire").isNull();
        assertThat(wire.getDeliveryMode()).as("delivery_mode on the wire").isEqualTo(2);
        assertThat(sent).as("the caller's array is left as it was").isEqualTo(payload);
    }

    /**
     * Bodies the original forwarded byte for byte: its own test payload, JSON, text that is not JSON, the
     * empty body and bytes that are not valid UTF-8, which the service must not re-encode.
     */
    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of("original test payload", bytes(PAYLOAD)),
                Arguments.of("JSON object", bytes("{\"sale\": {\"id\": 1, \"amount\": 10.50}}")),
                Arguments.of("text that is not JSON", bytes("{ not json")),
                Arguments.of("empty body", new byte[0]),
                Arguments.of("bytes that are not valid UTF-8", new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF}));
    }

    @Test
    @DisplayName("returns the body of the reply")
    void publishReturnsReplyBody() {
        runChannelCallbackOnMockedChannel();
        byte[] replyBody = bytes("{\"status\": \"received\"}");
        when(rabbitTemplate.sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class)))
                .thenReturn(MessageBuilder.withBody(replyBody).build());

        byte[] result = service.publish(bytes(PAYLOAD));

        assertThat(result).isEqualTo(replyBody);
    }

    @Test
    @DisplayName("returns null when no reply arrives within the reply timeout, without publishing again")
    void publishReturnsNullWhenReplyTimesOut() {
        runChannelCallbackOnMockedChannel();
        when(rabbitTemplate.sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class)))
                .thenReturn(null);

        byte[] result = service.publish(bytes(PAYLOAD));

        assertThat(result).isNull();
        verify(rabbitTemplate).execute(any());
        verify(rabbitTemplate, times(1))
                .sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class));
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @ParameterizedTest(name = "missing {0}")
    @EnumSource(PassiveCheck.class)
    @DisplayName("a failed passive check reaches the caller unchanged and nothing is published")
    void passiveCheckFailurePropagatesUnchangedWithoutSend(PassiveCheck failing) throws Exception {
        runChannelCallbackOnMockedChannel();
        IOException notFound =
                new IOException("NOT_FOUND - no " + failing.kind + " '" + failing.destination + "' in vhost '/'");
        if (failing == PassiveCheck.EXCHANGE) {
            doThrow(notFound).when(channel).exchangeDeclarePassive(EXCHANGE);
        } else {
            doThrow(notFound).when(channel).queueDeclarePassive(QUEUE);
        }

        Throwable thrown = catchThrowable(() -> service.publish(bytes(PAYLOAD)));

        assertThat(thrown).isInstanceOf(AmqpIOException.class).isSameAs(templateFailure.get());
        assertThat(thrown.getCause()).isSameAs(notFound);
        verify(channel).exchangeDeclarePassive(EXCHANGE);
        verify(channel, failing == PassiveCheck.EXCHANGE ? never() : times(1)).queueDeclarePassive(QUEUE);
        verifyNoMoreInteractions(channel);
        verify(rabbitTemplate).execute(any());
        verifyNoMoreInteractions(rabbitTemplate);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("passiveCheckFailures")
    @DisplayName("a failed passive check is logged at ERROR with the broker's reason, then reaches the caller"
            + " unchanged")
    void passiveCheckFailureIsLoggedWithBrokerReason(String description, PassiveCheck failing, IOException failure,
                                                     String expectedReason) throws Exception {
        runChannelCallbackOnMockedChannel();
        if (failing == PassiveCheck.EXCHANGE) {
            doThrow(failure).when(channel).exchangeDeclarePassive(EXCHANGE);
        } else {
            doThrow(failure).when(channel).queueDeclarePassive(QUEUE);
        }

        Throwable thrown = catchThrowable(() -> service.publish(bytes(PAYLOAD)));

        assertThat(thrown).isInstanceOf(AmqpIOException.class).isSameAs(templateFailure.get());
        assertThat(thrown.getCause()).isSameAs(failure);
        // execute() was the template's only call, so no sendAndReceive overload published anything.
        verify(rabbitTemplate).execute(any());
        verifyNoMoreInteractions(rabbitTemplate);
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0)).as("the INFO logger line stays first").satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("payload to be sent: test");
        });
        assertThat(appender.list.get(1)).satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getLoggerName()).isEqualTo(SalesPublisherService.class.getName());
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Passive check of " + failing.kind + " '" + failing.destination + "' failed: " + expectedReason);
            assertThat(event.getThrowableProxy()).as("no stack trace is attached").isNull();
        });
    }

    /**
     * What a failed passive declaration throws, for each check, with the reason its ERROR line must give: the
     * broker's {@code channel.close} and {@code connection.close}, which amqp-client delivers as the cause of an
     * {@link IOException} without a message, the application's own {@code connection.close}, and failures that
     * carry no broker reason (a withheld channel RPC, a lost connection, a cause chain that loops back on itself).
     */
    static Stream<Arguments> passiveCheckFailures() {
        return Stream.of(PassiveCheck.values()).flatMap(check -> {
            String notFound = "NOT_FOUND - no " + check.kind + " '" + check.destination + "' in vhost '/'";
            // channel.close names the method the broker refused: class 40 is exchange, 50 is queue, 10 is declare.
            ShutdownSignalException channelClosed = new ShutdownSignalException(false, false,
                    new AMQP.Channel.Close.Builder().replyCode(404).replyText(notFound)
                            .classId(check == PassiveCheck.EXCHANGE ? 40 : 50).methodId(10).build(),
                    null);
            String forced = "CONNECTION_FORCED - broker forced connection closure with reason 'shutdown'";
            ShutdownSignalException connectionClosed = new ShutdownSignalException(true, false,
                    new AMQP.Connection.Close.Builder().replyCode(320).replyText(forced).build(), null);
            // The application's own connection.close, as when its connection factory is destroyed mid-request.
            ShutdownSignalException connectionClosedByApplication = new ShutdownSignalException(true, true,
                    new AMQP.Connection.Close.Builder().replyCode(200).replyText("OK").build(), null);
            ChannelContinuationTimeoutException rpcTimeout = new ChannelContinuationTimeoutException(
                    new TimeoutException(), "AMQChannel(amqp://guest@localhost:5672/,1)", 1,
                    check == PassiveCheck.EXCHANGE
                            ? new AMQP.Exchange.Declare.Builder().exchange(check.destination).passive(true).build()
                            : new AMQP.Queue.Declare.Builder().queue(check.destination).passive(true).build());
            ShutdownSignalException connectionLost = new ShutdownSignalException(true, false, null, null);
            connectionLost.initCause(new SocketException("Connection reset"));
            IOException first = new IOException("first");
            IOException second = new IOException("second", first);
            first.initCause(second);
            return Stream.of(
                    // amqp-client hands the waiting caller a clone of the signal, caused by the original.
                    Arguments.of(check.kind + ": channel closed by the broker", check,
                            withoutMessageCausedBy(channelClosed.sensibleClone()),
                            "the broker closed the channel with reply-code=404, reply-text=" + notFound),
                    Arguments.of(check.kind + ": connection closed by the broker", check,
                            withoutMessageCausedBy(connectionClosed),
                            "the broker closed the connection with reply-code=320, reply-text=" + forced),
                    Arguments.of(check.kind + ": connection closed by the application", check,
                            withoutMessageCausedBy(connectionClosedByApplication),
                            "the application closed the connection with reply-code=200, reply-text=OK"),
                    Arguments.of(check.kind + ": channel RPC timed out", check, rpcTimeout,
                            rpcTimeout + " <- java.util.concurrent.TimeoutException"),
                    Arguments.of(check.kind + ": connection lost without a broker reason", check,
                            withoutMessageCausedBy(connectionLost),
                            "java.io.IOException <- com.rabbitmq.client.ShutdownSignalException: connection error"
                                    + " <- java.net.SocketException: Connection reset"),
                    Arguments.of(check.kind + ": cause chain that loops back on itself", check, first,
                            "java.io.IOException: first <- java.io.IOException: second"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sendFailures")
    @DisplayName("a failed publish reaches the caller unchanged after exactly one attempt")
    void sendFailurePropagatesUnchangedAfterOneAttempt(String description, AmqpException failure) {
        runChannelCallbackOnMockedChannel();
        when(rabbitTemplate.sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class)))
                .thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.publish(bytes(PAYLOAD)));

        assertThat(thrown).isSameAs(failure);
        verify(rabbitTemplate, times(1)).execute(any());
        verify(rabbitTemplate, times(1))
                .sendAndReceive(eq(EXCHANGE), eq(AmqpConfig.DEFAULT_ROUTING_KEY), any(Message.class));
        verifyNoMoreInteractions(rabbitTemplate);
    }

    /** Failures {@code sendAndReceive} raises: an unreachable broker, a channel error, a withheld channel RPC. */
    static Stream<Arguments> sendFailures() {
        return Stream.of(
                Arguments.of("broker unreachable",
                        new AmqpConnectException(new ConnectException("Connection refused"))),
                Arguments.of("channel error",
                        new AmqpIOException(new IOException("NOT_FOUND - no exchange 'sales_exchange' in vhost '/'"))),
                Arguments.of("channel RPC timeout",
                        new AmqpTimeoutException("channel RPC timed out", new TimeoutException())));
    }

    @Test
    @DisplayName("logs 'payload to be sent: <body>' at INFO")
    void publishLogsPayloadAtInfo() {
        runChannelCallbackOnMockedChannel();

        service.publish(bytes(PAYLOAD));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getLoggerName()).isEqualTo(SalesPublisherService.class.getName());
            assertThat(event.getFormattedMessage()).isEqualTo("payload to be sent: test");
        });
    }

    /**
     * Makes the mocked template run the service's channel callback on the mocked channel, as
     * {@link RabbitTemplate#execute(ChannelCallback)} does, translating a checked failure with Spring AMQP's
     * own {@link RabbitExceptionTranslator} and recording what it raised.
     */
    private void runChannelCallbackOnMockedChannel() {
        when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
            logEventsAtChannelCallback.set(appender.list.size());
            ChannelCallback<?> callback = invocation.getArgument(0);
            try {
                return callback.doInRabbit(channel);
            } catch (Exception e) {
                RuntimeException translated = RabbitExceptionTranslator.convertRabbitAccessException(e);
                templateFailure.set(translated);
                throw translated;
            }
        });
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** An {@link IOException} without a message caused by {@code signal}, as amqp-client's channel RPC throws. */
    private static IOException withoutMessageCausedBy(ShutdownSignalException signal) {
        return new IOException((String) null, signal);
    }

    /** The two passive checks the service makes before it publishes, with the broker's word for each kind. */
    enum PassiveCheck {
        EXCHANGE("exchange", SalesPublisherServiceTest.EXCHANGE),
        QUEUE("queue", SalesPublisherServiceTest.QUEUE);

        private final String kind;

        private final String destination;

        PassiveCheck(String kind, String destination) {
            this.kind = kind;
            this.destination = destination;
        }
    }
}
