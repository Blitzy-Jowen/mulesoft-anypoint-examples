/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.websphere_mq.client.MqQueueSender;
import com.mulesoft.examples.websphere_mq.service.AjaxChannelBroker;
import com.mulesoft.examples.websphere_mq.service.MqProcessingService;
import com.mulesoft.examples.websphere_mq.trigger.InQueueListener;
import com.mulesoft.examples.websphere_mq.trigger.OutQueueListener;

/**
 * Port of {@code websphere-mq/src/test/java/org/mule/examples/WebsphereMQIT.java}, which ran flows {@code Input},
 * {@code MessageProcessor} and {@code Output} of {@code websphere-mq.xml} with every WMQ and Ajax
 * {@code outbound-endpoint} mocked to return {@code "test"}, and asserted each flow's resulting payload.
 *
 * <p>Each flow's Java replacement is invoked directly, with the same outbound step replaced by a Mockito stub:
 * {@code Input} is {@link MqQueueSender#enqueue(Object)} with {@link MqQueueSender#sendToIn(String)} stubbed;
 * {@code MessageProcessor} is {@link InQueueListener#process(String)} running the real
 * {@link MqProcessingService} (without its wait) with {@link MqQueueSender#sendToOut(String)} stubbed;
 * {@code Output} is {@link OutQueueListener#forward(String)} with {@link OutQueueListener#publishToDequeue(String)}
 * stubbed. No Spring context, queue manager or Ajax client is involved. The original's {@code @BeforeClass} setting
 * the {@code wmq.*} system properties is not ported: nothing here reads them.
 */
public class WebsphereMQTest {

    /** Server path of the original Ajax connector's {@code serverUrl}. */
    private static final String SERVER_PATH = "/services/wmqExample";

    private static final String MESSAGE = "test";

    private MqQueueSender inputSender;

    private InQueueListener processor;

    private OutQueueListener output;

    @BeforeEach
    public void setUp() {
        // Flow Input: the wmq:outbound-endpoint queue="in" returns MESSAGE.
        inputSender = spy(new MqQueueSender(mock(JmsTemplate.class), mock(AjaxChannelBroker.class),
                new ObjectMapper(), SERVER_PATH, "in", "out"));
        doReturn(MESSAGE).when(inputSender).sendToIn(anyString());

        // Flow MessageProcessor: the wmq:outbound-endpoint queue="out" returns MESSAGE.
        MqQueueSender outSender = mock(MqQueueSender.class);
        when(outSender.sendToOut(anyString())).thenReturn(MESSAGE);
        processor = new InQueueListener(new MqProcessingService(0, ms -> { }), outSender);

        // Flow Output: the ajax:outbound-endpoint on the dequeue channel returns MESSAGE.
        output = spy(new OutQueueListener(mock(AjaxChannelBroker.class), SERVER_PATH));
        doReturn(MESSAGE).when(output).publishToDequeue(anyString());
    }

    @Test
    public void testWMQ() {
        Assertions.assertEquals(MESSAGE, inputSender.enqueue(""));
        Assertions.assertEquals(MESSAGE, processor.process(""));
        Assertions.assertEquals(MESSAGE, output.forward(""));
    }
}
