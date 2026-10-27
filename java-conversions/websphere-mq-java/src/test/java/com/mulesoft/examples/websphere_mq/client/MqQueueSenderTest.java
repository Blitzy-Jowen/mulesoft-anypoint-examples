/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.JmsException;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

/**
 * Unit test of {@link MqQueueSender}, the outbound WMQ adapter replacing the two {@code wmq:outbound-endpoint}s of
 * {@code websphere-mq/src/main/app/websphere-mq.xml}: {@code queue="in"} of flow {@code Input} (lines 8-14, reached
 * from the Ajax channel {@code /services/wmqExample/enqueue}) and {@code queue="out"} of flow
 * {@code MessageProcessor} (lines 20-22).
 *
 * <p>Plain Mockito doubles stand in for the template, the Ajax broker and the JMS session, so no Spring context,
 * broker or IBM MQ connection is involved. Each captured {@link MessageCreator} is run against the mocked session,
 * which pins the exact text written and proves that nothing else is set on the message: the original deleted
 * {@code MULE_REPLYTO} before the send (lines 10-12) and its Ajax connector set {@code disableReplyTo="true"}
 * (line 7), so a converted message carries no reply destination. Transactions belong to the template and are not
 * exercised here.
 */
class MqQueueSenderTest {

    private static final String SERVER_PATH = "/services/wmqExample";

    private static final String ENQUEUE_CHANNEL = "/services/wmqExample/enqueue";

    private static final String IN_QUEUE = "in";

    private static final String OUT_QUEUE = "out";

    private JmsTemplate jmsTemplate;

    private AjaxChannelBroker broker;

    private Session session;

    private TextMessage textMessage;

    private MqQueueSender sender;

    @BeforeEach
    void setUp() throws JMSException {
        jmsTemplate = mock(JmsTemplate.class);
        broker = mock(AjaxChannelBroker.class);
        session = mock(Session.class);
        textMessage = mock(TextMessage.class);
        when(session.createTextMessage(anyString())).thenReturn(textMessage);
        sender = new MqQueueSender(jmsTemplate, broker, new ObjectMapper(), SERVER_PATH, IN_QUEUE, OUT_QUEUE);
    }

    @Test
    void register_registersSingleEnqueueHandler() {
        verifyNoInteractions(broker);

        sender.register();

        ArgumentCaptor<AjaxChannelBroker.InboundHandler> handler =
                ArgumentCaptor.forClass(AjaxChannelBroker.InboundHandler.class);
        verify(broker, times(1)).registerInbound(eq(ENQUEUE_CHANNEL), handler.capture());
        verifyNoMoreInteractions(broker);
        verifyNoInteractions(jmsTemplate);
    }

    @Test
    void registeredHandler_reachesEnqueue_andSendsToIn() throws Exception {
        sender.register();
        ArgumentCaptor<AjaxChannelBroker.InboundHandler> handler =
                ArgumentCaptor.forClass(AjaxChannelBroker.InboundHandler.class);
        verify(broker).registerInbound(eq(ENQUEUE_CHANNEL), handler.capture());

        Object result = handler.getValue().handle("hello");

        assertEquals("hello", result);
        assertTextSent(sentMessage(IN_QUEUE), "hello");
        verify(jmsTemplate, never()).send(eq(OUT_QUEUE), any(MessageCreator.class));
    }

    @Test
    void enqueue_sendsPayloadTextToIn_withoutReplyTo() throws JMSException {
        assertEquals("hello", sender.enqueue("hello"));

        assertTextSent(sentMessage(IN_QUEUE), "hello");
        verify(jmsTemplate, never()).send(eq(OUT_QUEUE), any(MessageCreator.class));
        verifyNoInteractions(broker);
    }

    @Test
    void sendToIn_sendsPayloadTextToIn_withoutReplyTo() throws JMSException {
        assertEquals("hello", sender.sendToIn("hello"));

        assertTextSent(sentMessage(IN_QUEUE), "hello");
        verify(jmsTemplate, never()).send(eq(OUT_QUEUE), any(MessageCreator.class));
        verifyNoInteractions(broker);
    }

    @Test
    void sendToOut_sendsPayloadTextToOut_withoutReplyTo() throws JMSException {
        // " - processed" with its leading space is the appendString of the original test:component (line 19).
        assertEquals("hello - processed", sender.sendToOut("hello - processed"));

        assertTextSent(sentMessage(OUT_QUEUE), "hello - processed");
        verify(jmsTemplate, never()).send(eq(IN_QUEUE), any(MessageCreator.class));
        verifyNoInteractions(broker);
    }

    @Test
    void enqueue_mapPayload_isSentAsJacksonJson() throws Exception {
        // A single entry keeps the serialized key order deterministic.
        Map<String, Object> payload = Map.of("a", 1);

        String result = sender.enqueue(payload);

        assertEquals("{\"a\":1}", result);
        assertEquals(new ObjectMapper().writeValueAsString(payload), result);
        assertTextSent(sentMessage(IN_QUEUE), "{\"a\":1}");
        verify(jmsTemplate, never()).send(eq(OUT_QUEUE), any(MessageCreator.class));
    }

    @Test
    void enqueue_unserializablePayload_failsWithoutSending() {
        // Jackson refuses a bean with no properties (FAIL_ON_EMPTY_BEANS), a JsonProcessingException.
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> sender.enqueue(new Object()));

        assertEquals("Ajax payload cannot be serialized to JSON text", thrown.getMessage());
        assertInstanceOf(JsonProcessingException.class, thrown.getCause());
        verifyNoInteractions(jmsTemplate);
    }

    static Stream<Arguments> sends() {
        return Stream.of(
                Arguments.of("enqueue", (Consumer<MqQueueSender>) s -> s.enqueue("hello")),
                Arguments.of("sendToIn", (Consumer<MqQueueSender>) s -> s.sendToIn("hello")),
                Arguments.of("sendToOut", (Consumer<MqQueueSender>) s -> s.sendToOut("hello")));
    }

    /**
     * The failure must escape unwrapped: on the transacted {@code in} listener thread that is what rolls the
     * receive back, as the original's {@code ALWAYS_BEGIN} transaction did.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sends")
    void jmsFailure_propagatesUnchanged(String operation, Consumer<MqQueueSender> send) {
        UncategorizedJmsException failure = new UncategorizedJmsException("broker unavailable");
        doThrow(failure).when(jmsTemplate).send(anyString(), any(MessageCreator.class));

        JmsException thrown = assertThrows(JmsException.class, () -> send.accept(sender));

        assertSame(failure, thrown);
    }

    /**
     * Captures the single creator handed to {@link JmsTemplate#send(String, MessageCreator)} for {@code queue} and
     * runs it against the mocked session.
     */
    private TextMessage sentMessage(String queue) throws JMSException {
        ArgumentCaptor<MessageCreator> creator = ArgumentCaptor.forClass(MessageCreator.class);
        verify(jmsTemplate).send(eq(queue), creator.capture());
        Message message = creator.getValue().createMessage(session);
        assertSame(textMessage, message);
        return textMessage;
    }

    /**
     * Asserts that the session created exactly one text message holding {@code expectedText} and that the sender
     * then left it untouched: no reply destination, correlation ID or property.
     */
    private void assertTextSent(TextMessage message, String expectedText) throws JMSException {
        verify(session).createTextMessage(expectedText);
        verifyNoMoreInteractions(session);
        verify(message, never()).setJMSReplyTo(any());
        verifyNoInteractions(message);
    }
}
