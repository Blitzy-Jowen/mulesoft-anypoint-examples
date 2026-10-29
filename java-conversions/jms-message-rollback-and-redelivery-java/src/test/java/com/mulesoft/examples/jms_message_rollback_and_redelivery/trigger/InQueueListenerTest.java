/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.trigger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.management.AttributeList;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

import org.apache.activemq.artemis.api.core.ActiveMQBuffers;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQObjectMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

/**
 * Unit tests for {@link InQueueListener}, the transacted inbound endpoint of flow {@code JMSRedeliver}
 * ({@code jms-redelivery.xml:21-47}, AAP 0.7.3). The listener hands each delivery's body and
 * {@code JMSXDeliveryCount} to {@link RedeliveryService}; when the flow takes its rollback branch it pauses through
 * {@link RedeliveryDelay} and rethrows the very same {@link MyException}, so the session-transacted container rolls
 * back with the original {@code exception.causedBy(org.exceptions.MyException)} cause intact.
 *
 * <p>Object message bodies are read by the listener itself under a deserialization filter: the Artemis cases mock an
 * {@link ActiveMQObjectMessage} whose core message holds the body as the Artemis client writes it, and every case
 * verifies that the provider's {@code getObject()} and {@code getBody(..)} are never called.
 *
 * <p>Both collaborators are Mockito mocks: no Spring context, broker or real pause is involved. The broker side,
 * including the measured redelivery gaps, is covered by {@code JmsMessageRollbackAndRedeliveryTest}, and object
 * bodies on a broker by {@code InQueueListenerObjectBodyTest}.
 */
class InQueueListenerTest {

    /** The message the original {@code JmsMessageRollbackAndRedeliveryIT} dispatches to queue {@code in}. */
    private static final String PAYLOAD = "Message123";

    private RedeliveryService service;

    private RedeliveryDelay delay;

    private InQueueListener listener;

    @BeforeEach
    void setUp() {
        service = mock(RedeliveryService.class);
        delay = mock(RedeliveryDelay.class);
        listener = new InQueueListener(service, delay);
        ReadObjectSentinel.READ.set(false);
    }

    @AfterEach
    void resetSentinel() {
        ReadObjectSentinel.READ.set(false);
    }

    private static TextMessage textMessage(int deliveryCount) throws JMSException {
        TextMessage message = mock(TextMessage.class);
        when(message.getBody(Object.class)).thenReturn(PAYLOAD);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(deliveryCount);
        return message;
    }

    @Test
    @DisplayName("the fifth delivery is handed to the flow and returns without a pause, so the session commits")
    void fifthDeliveryIsProcessedWithoutPause() throws Exception {
        TextMessage message = textMessage(5);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).process(PAYLOAD, 5);
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @ParameterizedTest(name = "JMSXDeliveryCount {0}: pause, then the same MyException is rethrown")
    @ValueSource(ints = {1, 3})
    @DisplayName("the rollback branch pauses for the delivery's redelivery delay and rethrows the same MyException")
    void myExceptionPausesThenRethrowsSameInstance(int deliveryCount) throws Exception {
        MyException failure = new MyException();
        doThrow(failure).when(service).process(PAYLOAD, deliveryCount);
        TextMessage message = textMessage(deliveryCount);

        MyException thrown = assertThrows(MyException.class, () -> listener.onMessage(message));

        assertSame(failure, thrown);
        InOrder order = inOrder(service, delay);
        order.verify(service).process(PAYLOAD, deliveryCount);
        order.verify(delay).pauseBeforeRollback(deliveryCount);
        verifyNoMoreInteractions(delay);
    }

    @Test
    @DisplayName("a normal return from the flow (its catch branch) commits without a pause")
    void normalReturnCommitsWithoutPause() throws Exception {
        TextMessage message = textMessage(2);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).process(PAYLOAD, 2);
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a non-text body is handed to the flow unchanged")
    void bytesPayloadIsForwardedUnchanged() throws Exception {
        BytesMessage message = mock(BytesMessage.class);
        byte[] bytes = {1, 2, 3};
        when(message.getBody(Object.class)).thenReturn(bytes);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        listener.onMessage(message);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).process(payload.capture(), eq(5));
        assertArrayEquals(bytes, (byte[]) payload.getValue());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a body-less JMS Message is itself the payload, as Mule's inbound transformer passed it through")
    void bodylessMessageIsItselfThePayload() throws Exception {
        Message message = mock(Message.class);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        assertDoesNotThrow(() -> listener.onMessage(message));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).process(payload.capture(), eq(5));
        assertSame(message, payload.getValue());
        verify(message, never()).getBody(any());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @ParameterizedTest(name = "stream ends with {0}")
    @ValueSource(strings = {"MessageEOFException", "a null value"})
    @DisplayName("a stream message yields its values up to the end of the stream or its first null value")
    void streamMessageYieldsItsValuesAsList(String end) throws Exception {
        StreamMessage message = mock(StreamMessage.class);
        if (end.equals("MessageEOFException")) {
            when(message.readObject()).thenReturn("a", 1).thenThrow(new MessageEOFException("end of stream"));
        } else {
            when(message.readObject()).thenReturn("a", 1, null, "after the null value");
        }
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        listener.onMessage(message);

        verify(service).process(List.of("a", 1), 5);
        verify(message, never()).getBody(any());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    /** Messages whose payload or delivery count cannot be read, each with the exception the read throws. */
    static Stream<Arguments> unreadableMessages() throws JMSException {
        StreamMessage unreadableStream = mock(StreamMessage.class);
        MessageFormatException streamRead = new MessageFormatException("stream value cannot be read");
        when(unreadableStream.readObject()).thenThrow(streamRead);

        TextMessage countFails = mock(TextMessage.class);
        JMSException countRead = new JMSException("JMSXDeliveryCount cannot be read");
        when(countFails.getBody(Object.class)).thenReturn(PAYLOAD);
        when(countFails.getIntProperty("JMSXDeliveryCount")).thenThrow(countRead);

        Message countMissing = mock(Message.class);
        NumberFormatException missingCount = new NumberFormatException("null");
        when(countMissing.getIntProperty("JMSXDeliveryCount")).thenThrow(missingCount);

        return Stream.of(
                Arguments.of(Named.of("unreadable stream message", unreadableStream), streamRead),
                Arguments.of(Named.of("JMSXDeliveryCount read failure", countFails), countRead),
                Arguments.of(Named.of("JMSXDeliveryCount missing", countMissing), missingCount));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableMessages")
    @DisplayName("a failure reading the message goes to the choice-exception strategy and returns without a pause")
    void readFailureGoesToChoiceExceptionStrategy(Message message, Exception failure) throws Exception {
        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).handleFailure(failure);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("a read failure the strategy routes to its rollback branch rethrows that MyException without a pause")
    void readFailureRolledBackByStrategyRethrowsWithoutPause() throws Exception {
        ObjectMessage message = mock(ObjectMessage.class);
        MyException rollback = new MyException();
        doThrow(rollback).when(service).handleFailure(any(MessageFormatException.class));

        MyException thrown = assertThrows(MyException.class, () -> listener.onMessage(message));

        assertSame(rollback, thrown);
        verifyProviderNeverDeserializes(message);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("an object message the Artemis client did not create fails closed: the choice-exception strategy gets "
            + "a MessageFormatException and the provider is never asked to deserialize the body")
    void foreignObjectMessageFailsClosed() throws Exception {
        ObjectMessage message = mock(ObjectMessage.class);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);

        assertDoesNotThrow(() -> listener.onMessage(message));

        MessageFormatException failure = handedFailure();
        assertEquals(InQueueListener.FOREIGN_OBJECT_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
        verifyProviderNeverDeserializes(message);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    /**
     * Objects of the permitted classes, {@code ArrayList}s of them, and arrays of them or of primitives, up to the
     * depth limit.
     */
    static Stream<Arguments> permittedObjects() {
        return Stream.of(
                Arguments.of(Named.of("Integer", 42)),
                Arguments.of(Named.of("Long", 9_876_543_210L)),
                Arguments.of(Named.of("Boolean", Boolean.TRUE)),
                Arguments.of(Named.of("Character", 'x')),
                Arguments.of(Named.of("Byte", (byte) -7)),
                Arguments.of(Named.of("Short", (short) 1234)),
                Arguments.of(Named.of("Float", 2.5f)),
                Arguments.of(Named.of("Double", 3.0)),
                Arguments.of(Named.of("BigDecimal", new BigDecimal("12345.6789"))),
                Arguments.of(Named.of("BigInteger", new BigInteger("123456789012345678901234567890"))),
                Arguments.of(Named.of("String", PAYLOAD)),
                Arguments.of(Named.of("java.util.Date", new Date(0))),
                Arguments.of(Named.of("ArrayList of strings", new ArrayList<>(List.of("a", "b")))),
                Arguments.of(Named.of("empty ArrayList", new ArrayList<>())),
                Arguments.of(Named.of("ArrayList of primitive wrappers, a String, null and a byte[]",
                        streamValues())),
                Arguments.of(Named.of("ArrayList of a Date, a BigDecimal and an ArrayList",
                        new ArrayList<>(List.of(new Date(0), new BigDecimal("1.5"), new ArrayList<>(List.of(1)))))),
                Arguments.of(Named.of("Object[] of permitted values", new Object[] {PAYLOAD, 1, new Date(0)})),
                Arguments.of(Named.of("byte[]", new byte[] {1, 2, 3})),
                Arguments.of(Named.of("int[]", new int[] {4, 5, 6})),
                Arguments.of(Named.of("BigDecimal[], the deepest permitted graph without nesting",
                        new BigDecimal[] {new BigDecimal("1.5"), new BigDecimal("-200")})),
                Arguments.of(Named.of("ArrayList of BigDecimal, as deep",
                        new ArrayList<>(List.of(new BigDecimal("-2"))))),
                Arguments.of(Named.of("String in ArrayLists nested eight deep, the depth limit",
                        nestedList(8, PAYLOAD))),
                Arguments.of(Named.of("Integer in ArrayLists nested six deep, the depth limit", nestedList(6, 42))),
                Arguments.of(Named.of("eight-dimensional int array, the depth limit", nestedIntArray(8))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permittedObjects")
    @DisplayName("an Artemis object message of a permitted type reaches the flow equal to the object sent")
    void permittedObjectBodyReachesFlow(Object value) throws Exception {
        ActiveMQObjectMessage message = artemisObjectMessage(lengthPrefixed(serialize(value)));

        assertDoesNotThrow(() -> listener.onMessage(message));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(service).process(payload.capture(), eq(5));
        assertEqualValue(value, payload.getValue());
        verify(service, never()).handleFailure(any());
        verifyProviderNeverDeserializes(message);
    }

    /** Bodies holding no object: what {@code setObject(null)} sends, and a length prefix of 0. */
    static Stream<Arguments> emptyObjectBodies() {
        return Stream.of(
                Arguments.of(Named.of("empty body, as setObject(null) sends it", new byte[0])),
                Arguments.of(Named.of("length prefix 0", new byte[Integer.BYTES])));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyObjectBodies")
    @DisplayName("an object message body holding no object is the null payload, as the provider's getObject() returns")
    void emptyObjectBodyIsNullPayload(byte[] body) throws Exception {
        ActiveMQObjectMessage message = artemisObjectMessage(body);

        assertDoesNotThrow(() -> listener.onMessage(message));

        verify(service).process(null, 5);
        verify(service, never()).handleFailure(any());
        verifyProviderNeverDeserializes(message);
    }

    /**
     * Object message bodies the listener must not deserialize, each with the text of the resulting
     * {@link MessageFormatException} and the type of its cause, {@code null} where the body is refused before
     * deserialization starts.
     */
    static Stream<Arguments> unreadableObjectBodies() throws IOException {
        byte[] hugeArray = serialize(new byte[16]);
        // The element count precedes the 16 elements; it now declares an array of 2^31 - 1 bytes.
        ByteBuffer.wrap(hugeArray).putInt(hugeArray.length - 16 - Integer.BYTES, Integer.MAX_VALUE);
        String sentinelName = ReadObjectSentinel.class.getName();
        // Same length as the real name, so the stream stays well formed while naming a class that does not exist.
        byte[] unknownClass = new String(serialize(new ReadObjectSentinel()), StandardCharsets.ISO_8859_1)
                .replace(sentinelName, sentinelName.substring(0, sentinelName.length() - 1) + "X")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] hugeList = serialize(new ArrayList<>(List.of(PAYLOAD)));
        // The list's size field, then its capacity in a block of 4 bytes, both 1; the size now declares 2^31 - 1
        // elements, which ArrayList.readObject submits to the filter before it allocates its storage.
        ByteBuffer.wrap(hugeList).putInt(indexOf(hugeList, new byte[] {0, 0, 0, 1, 0x77, 4, 0, 0, 0, 1}),
                Integer.MAX_VALUE);
        String malformed = InQueueListener.MALFORMED_OBJECT_BODY;
        String unreadable = InQueueListener.UNREADABLE_OBJECT_BODY;
        return Stream.of(
                rejected("readObject sentinel", new ReadObjectSentinel()),
                rejected("Object[] holding the sentinel", new Object[] {new ReadObjectSentinel()}),
                rejected("ArrayList holding the sentinel", new ArrayList<>(List.of(PAYLOAD, new ReadObjectSentinel()))),
                rejected("ArrayList holding a HashSet", new ArrayList<>(List.of(new HashSet<>(Set.of(1))))),
                rejected("HashMap", new HashMap<>(Map.of("key", 1))),
                rejected("nested HashSet of depth 27, whose unfiltered read takes exponential time", serialDos(27)),
                rejected("java.sql.Timestamp, a Date subclass", new Timestamp(0)),
                rejected("java.sql.Date, a Date subclass", new java.sql.Date(0)),
                rejected("javax.management.AttributeList, an ArrayList subclass", new AttributeList()),
                rejected("LinkedList", new LinkedList<>(List.of(PAYLOAD))),
                rejected("Vector", new Vector<>(List.of(PAYLOAD))),
                rejected("Arrays.asList list", Arrays.asList(PAYLOAD)),
                rejected("List.of list", List.of(PAYLOAD)),
                rejected("enum constant", TimeUnit.SECONDS),
                rejected("nine-dimensional int array, beyond the depth limit", nestedIntArray(9)),
                rejected("String in ArrayLists nested nine deep, beyond the depth limit", nestedList(9, PAYLOAD)),
                rejected("Integer in ArrayLists nested seven deep, beyond the depth limit", nestedList(7, 42)),
                Arguments.of(Named.of("byte[] declaring 2^31 - 1 elements", lengthPrefixed(hugeArray)), unreadable,
                        InvalidClassException.class),
                Arguments.of(Named.of("ArrayList declaring 2^31 - 1 elements", lengthPrefixed(hugeList)), unreadable,
                        InvalidClassException.class),
                Arguments.of(Named.of("class not on the classpath", lengthPrefixed(unknownClass)), unreadable,
                        ClassNotFoundException.class),
                Arguments.of(Named.of("not a serialization stream",
                        lengthPrefixed(PAYLOAD.getBytes(StandardCharsets.US_ASCII))), unreadable,
                        StreamCorruptedException.class),
                Arguments.of(Named.of("body shorter than its length prefix", new byte[] {0, 0}), malformed, null),
                Arguments.of(Named.of("negative length prefix", ByteBuffer.allocate(8).putInt(-1).array()), malformed,
                        null),
                Arguments.of(Named.of("length prefix longer than the body",
                        ByteBuffer.allocate(8).putInt(5).array()), malformed, null));
    }

    private static Arguments rejected(String name, Object value) throws IOException {
        return Arguments.of(Named.of(name, lengthPrefixed(serialize(value))), InQueueListener.UNREADABLE_OBJECT_BODY,
                InvalidClassException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableObjectBodies")
    @DisplayName("an object message body that is malformed or that the deserialization filter rejects goes to the "
            + "choice-exception strategy as a MessageFormatException, before any of its classes' code runs")
    void unreadableObjectBodyGoesToChoiceExceptionStrategy(byte[] body, String reason,
                                                           Class<? extends Exception> cause) throws Exception {
        ActiveMQObjectMessage message = artemisObjectMessage(body);

        assertDoesNotThrow(() -> listener.onMessage(message));

        MessageFormatException failure = handedFailure();
        assertEquals(reason, failure.getMessage());
        if (cause == null) {
            assertNull(failure.getCause());
            assertNull(failure.getLinkedException());
        } else {
            assertInstanceOf(cause, failure.getCause());
            assertSame(failure.getCause(), failure.getLinkedException());
            if (cause == InvalidClassException.class) {
                assertTrue(failure.getCause().getMessage().contains("filter status: REJECTED"),
                        () -> "not rejected by the filter: " + failure.getCause());
            }
        }
        assertFalse(ReadObjectSentinel.READ.get(), "the sentinel's readObject ran");
        verifyProviderNeverDeserializes(message);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("java.lang.Object, permitted for the storage of an ArrayList, admits no instance: a body naming it "
            + "as a serializable class goes to the choice-exception strategy as a MessageFormatException")
    void objectClassAdmitsNoInstance() throws Exception {
        // java.lang.Double and java.lang.Object have names of the same length, so the stream stays well formed.
        byte[] body = new String(serialize(3.0), StandardCharsets.ISO_8859_1)
                .replace(Double.class.getName(), Object.class.getName())
                .getBytes(StandardCharsets.ISO_8859_1);
        ActiveMQObjectMessage message = artemisObjectMessage(lengthPrefixed(body));

        assertDoesNotThrow(() -> listener.onMessage(message));

        MessageFormatException failure = handedFailure();
        assertEquals(InQueueListener.UNREADABLE_OBJECT_BODY, failure.getMessage());
        InvalidClassException cause = assertInstanceOf(InvalidClassException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("class invalid for deserialization"),
                () -> "not refused as a class without serialization: " + cause);
        verifyProviderNeverDeserializes(message);
        verify(service, never()).process(any(), anyInt());
        verify(delay, never()).pauseBeforeRollback(anyInt());
    }

    @Test
    @DisplayName("control: a plain ObjectInputStream runs the sentinel's readObject, so the filter is what stops it")
    void sentinelRunsUnderPlainDeserialization() throws Exception {
        byte[] serialized = serialize(new ReadObjectSentinel());

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            assertInstanceOf(ReadObjectSentinel.class, in.readObject());
        }

        assertTrue(ReadObjectSentinel.READ.get(), "the sentinel's readObject did not run");
    }

    /** The exception the listener handed to the choice-exception strategy, which must be a format failure. */
    private MessageFormatException handedFailure() throws MyException {
        ArgumentCaptor<Exception> failure = ArgumentCaptor.forClass(Exception.class);
        verify(service).handleFailure(failure.capture());
        return assertInstanceOf(MessageFormatException.class, failure.getValue());
    }

    private static void verifyProviderNeverDeserializes(ObjectMessage message) throws JMSException {
        verify(message, never()).getObject();
        verify(message, never()).getBody(any());
    }

    /**
     * An Artemis object message on its fifth delivery whose core message holds {@code body} from the start of its
     * read-only body buffer, as the Artemis client hands a received object message to the listener.
     */
    private static ActiveMQObjectMessage artemisObjectMessage(byte[] body) throws JMSException {
        ClientMessage core = mock(ClientMessage.class);
        when(core.getReadOnlyBodyBuffer()).thenReturn(ActiveMQBuffers.wrappedBuffer(body));
        ActiveMQObjectMessage message = mock(ActiveMQObjectMessage.class);
        when(message.getCoreMessage()).thenReturn(core);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(5);
        return message;
    }

    /** The Java serialization of {@code value}, as {@code ActiveMQObjectMessage.setObject} produces it. */
    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /** An object message body as the Artemis client writes it: the {@code int} length of the bytes, then the bytes. */
    private static byte[] lengthPrefixed(byte[] serialized) {
        return ByteBuffer.allocate(Integer.BYTES + serialized.length).putInt(serialized.length).put(serialized).array();
    }

    /**
     * The nested-set graph known as SerialDOS: two sets per level, each holding both sets of the next level, so the
     * graph has {@code 2 × depth} sets but deserializing it rehashes about {@code 2^depth} of them. Each set is
     * added while it is still small, so building and serializing it stay linear.
     */
    private static HashSet<Object> serialDos(int depth) {
        HashSet<Object> root = new HashSet<>();
        Set<Object> left = root;
        Set<Object> right = new HashSet<>();
        for (int level = 0; level < depth; level++) {
            Set<Object> nextLeft = new HashSet<>();
            Set<Object> nextRight = new HashSet<>();
            nextLeft.add("distinct");
            left.add(nextLeft);
            left.add(nextRight);
            right.add(nextLeft);
            right.add(nextRight);
            left = nextLeft;
            right = nextRight;
        }
        return root;
    }

    /**
     * A list of every kind of value a stream message can hold, and so {@code client/TopicPublisher} can publish: the
     * primitive wrappers, a {@code String}, {@code null} and a {@code byte[]}.
     */
    private static ArrayList<Object> streamValues() {
        return new ArrayList<>(Arrays.asList(true, (byte) 3, (short) 4, 'c', 5, 6L, 7.5f, 8.5, PAYLOAD, null,
                new byte[] {1, 2}));
    }

    /** {@code innermost} inside {@code levels} single-element {@code ArrayList}s, each inside the next. */
    private static Object nestedList(int levels, Object innermost) {
        Object value = innermost;
        for (int level = 0; level < levels; level++) {
            value = new ArrayList<>(List.of(value));
        }
        return value;
    }

    /** The index of the only occurrence of {@code pattern} in {@code bytes}; fails the test when there is none. */
    private static int indexOf(byte[] bytes, byte[] pattern) {
        int found = -1;
        for (int start = 0; start + pattern.length <= bytes.length; start++) {
            if (Arrays.equals(bytes, start, start + pattern.length, pattern, 0, pattern.length)) {
                assertEquals(-1, found, "pattern occurs more than once");
                found = start;
            }
        }
        assertTrue(found >= 0, "pattern not found");
        return found;
    }

    /** An {@code int} array of {@code dimensions} dimensions, one element long in each, its innermost element 7. */
    private static Object nestedIntArray(int dimensions) {
        int[] lengths = new int[dimensions];
        Arrays.fill(lengths, 1);
        Object array = Array.newInstance(int.class, lengths);
        Object innermost = array;
        for (int dimension = 1; dimension < dimensions; dimension++) {
            innermost = Array.get(innermost, 0);
        }
        Array.setInt(innermost, 0, 7);
        return array;
    }

    /**
     * Asserts that {@code actual} is of the very class of {@code expected} and holds the same value, comparing array
     * and list elements deeply, so the {@code byte[]} of a list compares by content.
     */
    private static void assertEqualValue(Object expected, Object actual) {
        assertNotNull(actual, "null payload");
        assertSame(expected.getClass(), actual.getClass());
        if (expected instanceof List<?> values) {
            assertArrayEquals(values.toArray(), ((List<?>) actual).toArray());
        } else if (expected instanceof byte[] bytes) {
            assertArrayEquals(bytes, assertInstanceOf(byte[].class, actual));
        } else if (expected instanceof int[] ints) {
            assertArrayEquals(ints, assertInstanceOf(int[].class, actual));
        } else if (expected instanceof Object[] objects) {
            assertArrayEquals(objects, assertInstanceOf(Object[].class, actual));
        } else {
            assertEquals(expected, actual);
        }
    }
}
