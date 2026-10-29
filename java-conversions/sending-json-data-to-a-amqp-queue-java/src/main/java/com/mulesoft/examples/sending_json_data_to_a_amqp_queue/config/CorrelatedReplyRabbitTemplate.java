/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ClosingRecoveryListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.RabbitExceptionTranslator;
import org.springframework.util.Assert;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ShutdownListener;

/**
 * The application's {@link RabbitTemplate}: Spring AMQP's template, except that a request-reply exchange over a
 * temporary reply queue returns only the reply correlated with its request.
 *
 * <p>Spring AMQP's own temporary-reply-queue exchange sets no correlation id on the request and returns whatever
 * message reaches the reply queue first. The queue's name travels in the request's {@code reply_to}, and any client
 * allowed to publish on the broker can send to it through the default exchange, so a message that does not answer
 * the request (a forged one, or a stray reply meant for another request) would be returned to the HTTP caller in
 * place of the real reply. This template therefore gives every request a correlation id of its own, a random
 * version-4 {@link UUID} drawn from a cryptographically strong generator, so that it cannot be predicted from earlier
 * requests, and returns only a reply whose {@code correlation_id} equals it exactly. Any other delivery, including one
 * without a correlation id, is discarded with one WARN line, and the wait goes on: the deadline is the reply timeout
 * counted from the publish, and a discarded delivery never extends it. A responder that answers on {@code reply_to}
 * must copy the request's {@code correlation_id} onto its reply, as AMQP request-reply responders do.
 *
 * <p>Everything else is Spring AMQP's {@code doSendAndReceiveWithTemporary}, step for step: the request must not
 * already carry a {@code replyTo}; a server-named, exclusive, auto-delete queue is declared on the request's channel
 * and set as {@code replyTo}; an exclusive auto-acknowledging consumer is registered on it; the request is published
 * through {@link #doSend}; a reply becomes a {@link Message} through the template's properties converter and its
 * after-receive post-processors; an abnormal channel shutdown fails the wait; a mandatory request the broker returns
 * fails it with {@link AmqpMessageReturnedException} when no returns callback is set; no reply within the timeout
 * gives {@code null}; and the consumer is cancelled afterwards. The broker sees the same frames as before, plus the
 * {@code correlation_id} property on the request. The request is sent through the template's own connection
 * factory, which is where Spring sends it too unless the factory is an {@code AbstractRoutingConnectionFactory}
 * selected by a send-connection-factory expression, which this application never builds.
 *
 * <p>Direct reply-to is never used, so every request without a fixed {@code replyAddress} takes this exchange
 * whatever {@link #setUseTemporaryReplyQueues(boolean)} says: Spring's direct reply-to exchange correlates nothing
 * either. A fixed {@code replyAddress} keeps Spring's own exchange, which correlates through its reply listener.
 */
public class CorrelatedReplyRabbitTemplate extends RabbitTemplate {

    private static final Logger log = LoggerFactory.getLogger(CorrelatedReplyRabbitTemplate.class);

    /**
     * Header under which {@link RabbitTemplate} tags a mandatory request when no returns callback is set, so that the
     * broker's {@code basic.return} of the request fails the caller still waiting for its reply. The same name is used
     * here, so that {@link RabbitTemplate#handleReturn(Return)} reports a return arriving after its caller gave up
     * exactly as it reports one of its own requests.
     */
    static final String RETURN_CORRELATION_HEADER = "spring_request_return_correlation";

    /** {@link RabbitTemplate}'s default reply timeout, in milliseconds. */
    static final long DEFAULT_REPLY_TIMEOUT_MILLIS = 5000L;

    /** The pending reply of every mandatory request in flight, by the tag in its {@link #RETURN_CORRELATION_HEADER}. */
    private final Map<String, CompletableFuture<Message>> pendingReturns = new ConcurrentHashMap<>();

    /** The reply timeout {@link RabbitTemplate} keeps in a private field; negative waits without limit. */
    private volatile long replyTimeoutMillis = DEFAULT_REPLY_TIMEOUT_MILLIS;

    /** Whether the reply consumer is no-local, which {@link RabbitTemplate} keeps in a private field. */
    private volatile boolean replyConsumerNoLocal;

    /** Whether a returns callback was set, which {@link RabbitTemplate} keeps in a private field. */
    private volatile boolean returnsCallbackSet;

    /**
     * Creates a template with Spring AMQP's default strategies and no connection factory, for a configurer that sets
     * one, as Spring Boot's {@code RabbitTemplateConfigurer} does.
     */
    public CorrelatedReplyRabbitTemplate() {
        super();
    }

    /**
     * Creates a template with Spring AMQP's default strategies on the given connection factory.
     *
     * @param connectionFactory the factory every channel comes from
     */
    public CorrelatedReplyRabbitTemplate(ConnectionFactory connectionFactory) {
        super(connectionFactory);
    }

    /**
     * Sets the reply timeout, as {@link RabbitTemplate#setReplyTimeout(long)} does, and keeps it for the correlated
     * exchange.
     *
     * @param replyTimeout how long a request waits for its reply after the publish, in milliseconds; a negative
     *                     value waits without limit
     */
    @Override
    public void setReplyTimeout(long replyTimeout) {
        super.setReplyTimeout(replyTimeout);
        this.replyTimeoutMillis = replyTimeout;
    }

    /**
     * Sets whether the reply consumer is no-local, as {@link RabbitTemplate#setNoLocalReplyConsumer(boolean)} does,
     * and keeps it for the correlated exchange.
     *
     * @param noLocalReplyConsumer {@code true} for a no-local reply consumer
     */
    @Override
    public void setNoLocalReplyConsumer(boolean noLocalReplyConsumer) {
        super.setNoLocalReplyConsumer(noLocalReplyConsumer);
        this.replyConsumerNoLocal = noLocalReplyConsumer;
    }

    /**
     * Sets the returns callback, as {@link RabbitTemplate#setReturnsCallback(ReturnsCallback)} does, and records
     * that one is set: with a callback, a returned request goes to it instead of failing the waiting caller.
     *
     * @param returnCallback the callback for returned messages
     */
    @Override
    public void setReturnsCallback(ReturnsCallback returnCallback) {
        super.setReturnsCallback(returnCallback);
        this.returnsCallbackSet = returnCallback != null;
    }

    /**
     * Never uses direct reply-to, so that no request-reply exchange bypasses the correlation check.
     *
     * @return {@code false}
     */
    @Override
    protected boolean useDirectReplyTo() {
        return false;
    }

    /**
     * Sends the request with a generated correlation id and a temporary reply queue, and waits for the reply that
     * carries the same id.
     *
     * @param exchange        the exchange the request is published to
     * @param routingKey      the routing key of the request
     * @param message         the request; it must carry neither a {@code replyTo} nor a {@code correlationId},
     *                        because this method sets both
     * @param correlationData the correlation data for publisher confirms, or {@code null}
     * @return the correlated reply, or {@code null} when none arrived within the reply timeout
     * @throws org.springframework.amqp.AmqpException when the request carries a {@code replyTo} or a
     *                                                {@code correlationId}, when declaring, consuming or publishing
     *                                                fails, when the channel shuts down abnormally during the wait,
     *                                                when the broker returns a mandatory request, or when the waiting
     *                                                thread is interrupted, whose interrupt flag is then restored
     */
    @Override
    protected Message doSendAndReceiveWithTemporary(String exchange, String routingKey, Message message,
            CorrelationData correlationData) {

        return execute(channel -> {
            MessageProperties requestProperties = message.getMessageProperties();
            Assert.isNull(requestProperties.getReplyTo(),
                    "Send-and-receive methods can only be used if the Message does not already have a replyTo"
                            + " property.");
            Assert.isNull(requestProperties.getCorrelationId(),
                    "Send-and-receive methods can only be used if the Message does not already have a correlationId"
                            + " property: the template generates it.");

            String replyTo = channel.queueDeclare().getQueue();
            requestProperties.setReplyTo(replyTo);
            String correlationId = UUID.randomUUID().toString();
            requestProperties.setCorrelationId(correlationId);

            CompletableFuture<Message> pendingReply = new CompletableFuture<>();
            CorrelatedReplyConsumer consumer = new CorrelatedReplyConsumer(channel, replyTo, correlationId,
                    pendingReply);
            ClosingRecoveryListener.addRecoveryListenerIfNecessary(channel);
            ShutdownListener shutdownListener = signal -> {
                if (!RabbitUtils.isNormalChannelClose(signal)) {
                    pendingReply.completeExceptionally(signal);
                }
            };
            channel.addShutdownListener(shutdownListener);
            try {
                String consumerTag = UUID.randomUUID().toString();
                channel.basicConsume(replyTo, true, consumerTag, this.replyConsumerNoLocal, true, null, consumer);
                try {
                    return publishAndAwaitReply(channel, exchange, routingKey, message, correlationData,
                            pendingReply);
                } finally {
                    if (channel.isOpen()) {
                        RabbitUtils.cancel(channel, consumerTag);
                    }
                }
            } finally {
                removeShutdownListenerQuietly(channel, shutdownListener);
            }
        });
    }

    /**
     * Logs a message one of {@link RabbitTemplate}'s receive or reply paths took, at DEBUG, as its prefix and its
     * body's byte count only. Spring AMQP's own line is the whole {@link Message}: a short textual body and every
     * property, application headers included. A received message's body and headers come from whoever published it
     * and may carry credentials or personal data, so none of them is logged, and neither are its correlation id and
     * reply queue.
     *
     * @param prefix  what the message is, as {@link RabbitTemplate} names it ({@code "Received: "} or
     *                {@code "Reply: "})
     * @param message the message taken, or {@code null} when none arrived
     */
    @Override
    protected void logReceived(String prefix, Message message) {
        if (message == null) {
            log.debug("{}no message", prefix);
        } else {
            log.debug("{}{} bytes", prefix, message.getBody().length);
        }
    }

    /**
     * Publishes the prepared request and waits for its correlated reply, as Spring AMQP's {@code exchangeMessages}
     * does: a mandatory request is tagged so that its return fails the wait when no returns callback is set, and a
     * timed-out wait is reported to {@link #replyTimedOut(String)}. The outcome is logged at DEBUG as the reply's
     * byte count, or that none arrived, never as the reply's content.
     *
     * @param channel         the request's channel, already consuming from the reply queue
     * @param exchange        the exchange the request is published to
     * @param routingKey      the routing key of the request
     * @param message         the request, carrying its {@code replyTo} and {@code correlationId}
     * @param correlationData the correlation data for publisher confirms, or {@code null}
     * @param pendingReply    completed by the reply consumer, a channel shutdown or a return
     * @return the correlated reply, or {@code null} when none arrived within the reply timeout
     * @throws InterruptedException when the waiting thread is interrupted; its interrupt flag is restored first
     */
    private Message publishAndAwaitReply(Channel channel, String exchange, String routingKey, Message message,
            CorrelationData correlationData, CompletableFuture<Message> pendingReply)
            throws InterruptedException {

        boolean mandatory = isMandatoryFor(message);
        String returnTag = null;
        if (mandatory && !this.returnsCallbackSet) {
            returnTag = UUID.randomUUID().toString();
            this.pendingReturns.put(returnTag, pendingReply);
            message.getMessageProperties().getHeaders().put(RETURN_CORRELATION_HEADER, returnTag);
        }
        try {
            doSend(channel, exchange, routingKey, message, mandatory, correlationData);
            Message reply = awaitReply(pendingReply);
            if (reply == null) {
                log.debug("No correlated reply within the reply timeout");
                replyTimedOut(message.getMessageProperties().getCorrelationId());
            } else {
                log.debug("Correlated reply received: {} bytes", reply.getBody().length);
            }
            return reply;
        } finally {
            if (returnTag != null) {
                this.pendingReturns.remove(returnTag);
            }
        }
    }

    /**
     * Fails the waiting caller of a returned mandatory request with {@link AmqpMessageReturnedException}, as
     * {@link RabbitTemplate} does for its own requests; every other return goes to
     * {@link RabbitTemplate#handleReturn(Return)}, which hands it to the returns callback or logs it.
     *
     * @param returned the message the broker returned
     */
    @Override
    public void handleReturn(Return returned) {
        Map<String, Object> headers = returned.getProperties() == null ? null : returned.getProperties().getHeaders();
        CompletableFuture<Message> pendingReply = pendingReplyTaggedIn(headers);
        if (pendingReply == null) {
            super.handleReturn(returned);
            return;
        }
        headers.remove(RETURN_CORRELATION_HEADER);
        headers.remove(PublisherCallbackChannel.RETURN_LISTENER_CORRELATION_KEY);
        MessageProperties properties = getMessagePropertiesConverter()
                .toMessageProperties(returned.getProperties(), null, getEncoding());
        ReturnedMessage returnedMessage = new ReturnedMessage(new Message(returned.getBody(), properties),
                returned.getReplyCode(), returned.getReplyText(), returned.getExchange(), returned.getRoutingKey());
        pendingReply.completeExceptionally(new AmqpMessageReturnedException("Message returned", returnedMessage));
    }

    /**
     * Finds the caller a returned request belongs to.
     *
     * @param headers the application headers of the returned message, or {@code null} when it carries none
     * @return the pending reply of the correlated request tagged in {@code headers} while its caller still waits, or
     *         {@code null} when a returns callback is set, the message carries no tag, or the tag is not one of this
     *         template's waiting requests
     */
    private CompletableFuture<Message> pendingReplyTaggedIn(Map<String, Object> headers) {
        if (this.returnsCallbackSet || headers == null) {
            return null;
        }
        Object returnTag = headers.get(RETURN_CORRELATION_HEADER);
        return returnTag == null ? null : this.pendingReturns.get(returnTag.toString());
    }

    /**
     * Waits for the correlated reply until the reply timeout, counted from the call, which follows the publish.
     *
     * @param pendingReply completed by the consumer with the correlated reply, or exceptionally by a channel shutdown
     *                     or a return
     * @return the reply, or {@code null} when the timeout passed first
     * @throws InterruptedException when the waiting thread is interrupted; its interrupt flag is restored first
     */
    private Message awaitReply(CompletableFuture<Message> pendingReply) throws InterruptedException {
        long timeoutMillis = this.replyTimeoutMillis;
        try {
            return timeoutMillis < 0 ? pendingReply.get() : pendingReply.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            return null;
        } catch (ExecutionException failed) {
            throw RabbitExceptionTranslator.convertRabbitAccessException(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    /**
     * Removes the shutdown listener from a channel that may already be closed, in which case there is nothing left to
     * remove.
     *
     * @param channel  the request's channel
     * @param listener the listener added for the request
     */
    private static void removeShutdownListenerQuietly(Channel channel, ShutdownListener listener) {
        try {
            channel.removeShutdownListener(listener);
        } catch (RuntimeException closed) {
            log.debug("Could not remove the reply shutdown listener from {}: {}", channel, closed.toString());
        }
    }

    /**
     * The consumer on one request's temporary reply queue. It completes the pending reply with the first delivery
     * whose {@code correlation_id} equals the request's and discards every other delivery, which auto-acknowledgement
     * has already removed from the queue.
     */
    private final class CorrelatedReplyConsumer extends DefaultConsumer {

        private final String replyTo;

        private final String correlationId;

        private final CompletableFuture<Message> pendingReply;

        /**
         * @param channel       the request's channel
         * @param replyTo       the server-named temporary reply queue
         * @param correlationId the correlation id the request carries
         * @param pendingReply  the future the correlated reply completes
         */
        CorrelatedReplyConsumer(Channel channel, String replyTo, String correlationId,
                CompletableFuture<Message> pendingReply) {
            super(channel);
            this.replyTo = replyTo;
            this.correlationId = correlationId;
            this.pendingReply = pendingReply;
        }

        /**
         * Completes the pending reply when the delivery carries the request's correlation id; otherwise discards it.
         * The WARN line names only the server-named reply queue: the delivery's properties and body come from
         * whoever published it, so none of them is logged.
         */
        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                byte[] body) {
            String deliveredCorrelationId = properties == null ? null : properties.getCorrelationId();
            if (!this.correlationId.equals(deliveredCorrelationId)) {
                log.warn("Discarded a message on temporary reply queue {}: its correlation id does not match the"
                        + " request's", this.replyTo);
                return;
            }
            Message reply = new Message(body,
                    getMessagePropertiesConverter().toMessageProperties(properties, envelope, getEncoding()));
            Collection<MessagePostProcessor> processors = getAfterReceivePostProcessors();
            if (processors != null) {
                for (MessagePostProcessor processor : processors) {
                    reply = processor.postProcessMessage(reply);
                }
            }
            this.pendingReply.complete(reply);
        }

        @Override
        public String toString() {
            return "CorrelatedReplyConsumer [channel=" + getChannel() + ", replyTo=" + this.replyTo + "]";
        }
    }
}
