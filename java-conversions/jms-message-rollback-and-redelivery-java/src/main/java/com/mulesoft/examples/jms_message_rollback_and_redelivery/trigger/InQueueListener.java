/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.JmsConfig;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Inbound endpoint of flow {@code JMSRedeliver}: {@code <jms:inbound-endpoint queue="in">} with
 * {@code <jms:transaction action="ALWAYS_BEGIN"/>} ({@code jms-redelivery.xml}, lines 21-24).
 *
 * <p>Every delivery runs in the locally transacted session of the container built by
 * {@link JmsConfig#IN_QUEUE_LISTENER_FACTORY}: the session commits when {@link #onMessage(Message)} returns and
 * rolls back when it throws, which undoes the receive together with any {@code topic1} send made on that session,
 * and the broker redelivers.
 *
 * <p>The class only binds the message to the flow's input and hands it on. The choice on
 * {@code JMSXDeliveryCount}, the send to {@code topic1}, the logging and both exception branches belong to
 * {@link RedeliveryService}; the timing of the redelivery belongs to {@link RedeliveryDelay}.
 *
 * <p>{@link RedeliveryService#process(Object, int)} describes what a hard crash during the pause before a rollback
 * does to the delivery count.
 *
 * <p>The body of an object message is never handed to the provider to deserialize: {@link #onMessage(Message)}
 * describes the filtered reader that takes its place.
 */
@Component
public class InQueueListener {

    /**
     * Classes an object message body may contain, as {@link ObjectInputFilter.Config#createFilter(String)}
     * patterns, ending with {@code !*}, which rejects every other class. Each pattern names one class exactly, so a
     * subclass, which the stream names under its own name, is rejected with every other class.
     *
     * <ul>
     *   <li>The JDK's immutable scalar value types. {@code java.lang.Number} is listed because it is the serial
     *       superclass of the boxed numbers, {@code BigInteger} and {@code BigDecimal}, so their stream names it
     *       too.</li>
     *   <li>{@code java.util.Date}, whose {@code readObject} reads one {@code long}. Its subclasses, such as
     *       {@code java.sql.Timestamp} and {@code java.sql.Date}, are not listed.</li>
     *   <li>{@code java.util.ArrayList}, whose {@code readObject} checks its declared size against the limits
     *       before it allocates its {@code Object[]} storage and then reads its elements into it in order, without
     *       hashing, comparing or calling any method of them. Each element is read through this same filter, so a
     *       list admits only permitted classes, other lists included. Other lists and collections, such as
     *       {@code LinkedList}, {@code Vector}, {@code Arrays.asList} or {@code List.of} lists, are not listed.</li>
     *   <li>{@code java.lang.Object}, because {@code ArrayList} submits its {@code Object[]} storage to the filter,
     *       which judges an array by its element type. No instance of {@code Object} itself can be deserialized, as
     *       the class is not serializable, so the pattern admits only {@code Object} arrays, each element of which
     *       the filter still judges.</li>
     * </ul>
     *
     * <p>Arrays of the listed types, and arrays of primitives, pass as well, because the filter judges an array by
     * its element type and leaves primitives to the limits. No set, map or other hashing collection is permitted,
     * because nested sets and maps make deserialization hash its way into exponential work, and no application or
     * other library class is, because its {@code readObject} would run on bytes the sender chose.
     */
    private static final String PERMITTED_OBJECT_CLASSES = "java.lang.String;java.lang.Boolean;"
            + "java.lang.Character;java.lang.Byte;java.lang.Short;java.lang.Integer;java.lang.Long;java.lang.Float;"
            + "java.lang.Double;java.lang.Number;java.math.BigInteger;java.math.BigDecimal;java.util.Date;"
            + "java.util.ArrayList;java.lang.Object;!*";

    /**
     * Deepest nesting of objects and arrays an object message body may declare, as the filter counts it. The
     * deepest graph of permitted classes that holds no list or array inside another, a list or an array of
     * {@code BigDecimal} (the list or array, a {@code BigDecimal}, its {@code BigInteger}, that one's {@code byte[]}
     * magnitude), needs 4. Each list or array inside another adds one level, so 8 admits, for example, strings in
     * lists nested eight deep, integers in lists nested six deep and arrays of up to eight dimensions.
     */
    private static final int MAX_OBJECT_DEPTH = 8;

    /** Text of the failure for an object message the Artemis client did not create, which has no readable body. */
    static final String FOREIGN_OBJECT_MESSAGE =
            "Object message body is not read: only an Artemis object message can be read without its provider "
                    + "deserializing it";

    /** Text of the failure for an object message body whose length prefix does not match the body. */
    static final String MALFORMED_OBJECT_BODY =
            "Object message body is malformed: its length prefix is cut short, negative or longer than the body";

    /** Text of the failure for an object message body the deserialization filter rejects or that cannot be read. */
    static final String UNREADABLE_OBJECT_BODY =
            "Object message body is not deserialized: it holds a class or a graph the deserialization filter rejects, "
                    + "or it is not a readable serialization stream";

    private final RedeliveryService redeliveryService;

    private final RedeliveryDelay redeliveryDelay;

    /**
     * Creates the listener.
     *
     * @param redeliveryService the body of flow {@code JMSRedeliver}
     * @param redeliveryDelay   the pause that precedes the rollback of a failed delivery
     */
    public InQueueListener(RedeliveryService redeliveryService, RedeliveryDelay redeliveryDelay) {
        this.redeliveryService = redeliveryService;
        this.redeliveryDelay = redeliveryDelay;
    }

    /**
     * Receives one delivery from queue {@code in} and runs the flow on its payload and delivery count.
     *
     * <p>The payload follows Mule's inbound JMS transformer ({@code JmsMessageUtils.toObject}): the body of a
     * text, bytes, map or object message; the values of a stream message, read in order up to its end or its
     * first {@code null} value, as a {@code List}; and the message itself for a message of any other type, such
     * as a body-less {@link Message}, which is then published unchanged.
     *
     * <p>An object message is the one exception to reading the body through the provider. On the original's
     * ActiveMQ 5.10.0 client, {@code JmsMessageUtils.toObject} deserialized an object message's body into any class
     * on the classpath, because that client had no class filter before ActiveMQ 5.12.2, and the Artemis client's
     * {@code getObject()} likewise trusts every class while the connection factory sets no allow list. The
     * listener therefore reads the serialized form itself ({@link #readObjectBody(ObjectMessage)}) and
     * deserializes it only through a filter that permits the JDK's immutable scalar value types,
     * {@code java.util.Date}, {@code java.util.ArrayList} lists of permitted values, and arrays of permitted types or
     * of primitives, and caps the nesting depth, the references, the array and list lengths and the bytes read,
     * checked before any object is created or any array allocated. This is a deliberate hardening against hostile
     * input: a legitimate object body of a permitted type reaches the flow unchanged, so the fifth delivery of a
     * {@code Date} is published as an object message and that of a list of strings, primitive wrappers,
     * {@code null}s and {@code byte[]}s as a stream message, by the rules of {@code client/TopicPublisher}; a body
     * that holds any other class or exceeds a limit, a malformed body, and an object message the Artemis client did
     * not create fail with a {@link MessageFormatException} and take the catch branch below, so the delivery commits
     * and nothing is published, as an object body the original could not deserialize did.
     *
     * <p>Reading the payload or the {@code JMSXDeliveryCount} property is part of the flow, as the transformer
     * was in Mule, so a failure there goes to the flow's choice-exception strategy
     * ({@link RedeliveryService#handleFailure(Exception)}); not caused by {@link MyException}, it takes the catch
     * branch and the delivery commits. Should that strategy take its rollback branch instead, its
     * {@code MyException} propagates without a pause, because no delivery count is known.
     *
     * <p>When the flow takes its rollback branch, the listener pauses for the redelivery delay while the
     * transacted session is still open and then rethrows the same {@link MyException}, so the container rolls the
     * session back and the next delivery starts after the original ActiveMQ policy's interval (5000 ms after the
     * first delivery, 2000 ms after each later one). Any other exception the flow lets escape propagates
     * unchanged and without a pause.
     *
     * @param message the delivered message
     * @throws MyException when the flow takes its rollback branch, so the delivery is rolled back and redelivered
     */
    @JmsListener(destination = "${jms.inbound-queue:in}", containerFactory = JmsConfig.IN_QUEUE_LISTENER_FACTORY)
    public void onMessage(Message message) throws MyException {
        Object payload;
        int deliveryCount;
        try {
            payload = toPayload(message);
            // JMS-defined property, counted from 1 by Artemis as by ActiveMQ, so the flow's "== 5" holds on the
            // fifth delivery.
            deliveryCount = message.getIntProperty("JMSXDeliveryCount");
        } catch (JMSException | RuntimeException e) {
            redeliveryService.handleFailure(e);
            return;
        }
        try {
            redeliveryService.process(payload, deliveryCount);
        } catch (MyException e) {
            // The pause must precede the throw: the rollback, and with it the broker's redelivery, starts only once
            // the exception leaves this method.
            redeliveryDelay.pauseBeforeRollback(deliveryCount);
            throw e;
        }
    }

    /**
     * The flow's payload for a delivered message, as Mule's {@code JmsMessageUtils.toObject} derived it, except that
     * an object message's body is deserialized only through the filter of {@link #readObjectBody(ObjectMessage)}.
     * The provider decodes text, bytes and map bodies without Java serialization.
     *
     * @param message the delivered message
     * @return the body of a text, bytes or map message, the permitted object of an object message, the values of a
     *         stream message, or the message itself for any other type
     * @throws JMSException when the body cannot be read, for example an object message body the deserialization
     *                      filter rejects ({@link MessageFormatException})
     */
    private static Object toPayload(Message message) throws JMSException {
        if (message instanceof StreamMessage stream) {
            return readStream(stream);
        }
        if (message instanceof ObjectMessage object) {
            return readObjectBody(object);
        }
        if (message instanceof TextMessage || message instanceof BytesMessage || message instanceof MapMessage) {
            return message.getBody(Object.class);
        }
        return message;
    }

    /**
     * The object of an object message, deserialized from the serialized form the Artemis client sent without asking
     * the provider to deserialize it.
     *
     * <p>{@code ActiveMQObjectMessage} writes its body as an {@code int} length followed by that many bytes of Java
     * serialization; {@code setObject(null)} writes nothing. The body is read from
     * {@code getReadOnlyBodyBuffer()}, a read-only view from the start of the body that the receive has not
     * consumed, which holds the whole body of a large message too once the listener container has completed it.
     * An empty body or a zero length yields {@code null}, as {@code getObject()} returns then. The bytes are then
     * deserialized by a plain {@link ObjectInputStream} whose filter is set before anything is read: the permitted
     * classes of {@link #PERMITTED_OBJECT_CLASSES}, nesting up to {@link #MAX_OBJECT_DEPTH}, and at most as many
     * references, array or list elements and bytes as the serialized form is long, which no legitimate graph
     * exceeds and which stops a short body from declaring a huge array or list.
     *
     * @param message the delivered object message
     * @return the deserialized object, or {@code null} for a body holding no object
     * @throws MessageFormatException when the message was not created by the Artemis client, so its serialized form
     *                                cannot be read without its provider deserializing it; when the length prefix
     *                                is negative, exceeds the bytes that follow it or is cut short; or when the
     *                                filter rejects the serialized graph or it cannot be read, the
     *                                {@link IOException} or {@link ClassNotFoundException} then being both the
     *                                linked exception and the cause. The text never names a class or quotes the
     *                                body
     */
    private static Object readObjectBody(ObjectMessage message) throws MessageFormatException {
        if (!(message instanceof ActiveMQMessage artemis)) {
            throw new MessageFormatException(FOREIGN_OBJECT_MESSAGE);
        }
        ActiveMQBuffer body = artemis.getCoreMessage().getReadOnlyBodyBuffer();
        if (body.readableBytes() == 0) {
            return null;
        }
        if (body.readableBytes() < Integer.BYTES) {
            throw new MessageFormatException(MALFORMED_OBJECT_BODY);
        }
        int length = body.readInt();
        if (length < 0 || length > body.readableBytes()) {
            throw new MessageFormatException(MALFORMED_OBJECT_BODY);
        }
        if (length == 0) {
            return null;
        }
        byte[] serialized = new byte[length];
        body.readBytes(serialized);
        ObjectInputFilter filter = ObjectInputFilter.Config.createFilter("maxdepth=" + MAX_OBJECT_DEPTH
                + ";maxrefs=" + length + ";maxarray=" + length + ";maxbytes=" + length + ";"
                + PERMITTED_OBJECT_CLASSES);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            in.setObjectInputFilter(filter);
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            MessageFormatException failure = new MessageFormatException(UNREADABLE_OBJECT_BODY);
            failure.setLinkedException(e);
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * The values of a stream message in the order they were written, ending before its first {@code null} value
     * or at the end of the stream, whichever comes first, as Mule read them.
     *
     * @param stream the delivered stream message
     * @return the values read, possibly none
     * @throws JMSException when a value cannot be read
     */
    private static List<Object> readStream(StreamMessage stream) throws JMSException {
        List<Object> values = new ArrayList<>();
        while (true) {
            Object value;
            try {
                value = stream.readObject();
            } catch (MessageEOFException endOfStream) {
                return values;
            }
            if (value == null) {
                return values;
            }
            values.add(value);
        }
    }
}
