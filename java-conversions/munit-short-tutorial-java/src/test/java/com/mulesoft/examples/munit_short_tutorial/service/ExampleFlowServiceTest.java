/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Context-free branch and edge-case test of {@link ExampleFlowService}, the flow graph of
 * munit-short-tutorial/src/main/app/production-code.xml. The six MUnit tests of production-code-test-suite.xml are
 * ported separately, against the Spring context, by {@code ProductionCodeTestSuiteTest}.
 *
 * <p>The service is a Mockito spy, so the calls it makes to its own flow and sub-flow methods are recorded and can be
 * stubbed. Expected values are string literals rather than the service's constants, so a changed constant fails a
 * test; {@link #constantsHoldTheOriginalLiterals()} is the only test that reads the constants.
 */
class ExampleFlowServiceTest {

    private ExampleFlowService service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new ExampleFlowService());
    }

    static Stream<Arguments> queryParamsRoutedToSubFlow2() {
        return Stream.of(
                Arguments.of(Named.of("url_key=payload_2", Map.of("url_key", "payload_2"))),
                // 'payload_1'.equals(payload) is a case-sensitive String.equals.
                Arguments.of(Named.of("url_key=Payload_1", Map.of("url_key", "Payload_1"))),
                Arguments.of(Named.of("url_key empty", Map.of("url_key", ""))),
                Arguments.of(Named.of("url_key absent (null payload)", Map.of())));
    }

    static Stream<Arguments> nonStringPayloads() {
        return Stream.of(
                Arguments.of(Named.of("Integer 1", Integer.valueOf(1))),
                Arguments.of(Named.of("plain Object", new Object())),
                // Same characters as payload_1, but String.equals accepts only a String.
                Arguments.of(Named.of("StringBuilder payload_1", new StringBuilder("payload_1"))));
    }

    @Test
    void exampleFlowRoutesPayload1ToSubFlow1() {
        assertEquals("response_payload_1", service.exampleFlow(Map.of("url_key", "payload_1")));

        verify(service, times(1)).subFlow1(any());
        verify(service, never()).subFlow2(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryParamsRoutedToSubFlow2")
    void exampleFlowRoutesEveryOtherPayloadToSubFlow2(Map<String, String> queryParams) {
        assertEquals("response_payload_2", service.exampleFlow(queryParams));

        verify(service).exampleFlow2(eq(queryParams.get("url_key")), any());
        verify(service, times(1)).subFlow2(any());
        verify(service, never()).subFlow1(any());
    }

    @Test
    void exampleFlow2RoutesNullPayloadToSubFlow2WithoutThrowing() {
        Map<String, Object> flowVars = new HashMap<>();

        assertDoesNotThrow(() -> service.exampleFlow2(null, flowVars));

        assertEquals(Map.of("my_variable", "var_value_2"), flowVars);
        verify(service, times(1)).subFlow2(flowVars);
        verify(service, never()).subFlow1(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStringPayloads")
    void exampleFlow2RoutesNonStringPayloadsToSubFlow2(Object payload) {
        Map<String, Object> flowVars = new HashMap<>();

        service.exampleFlow2(payload, flowVars);

        assertEquals(Map.of("my_variable", "var_value_2"), flowVars);
        verify(service, times(1)).subFlow2(flowVars);
        verify(service, never()).subFlow1(any());
    }

    @Test
    void subFlowsSetOnlyMyVariable() {
        Map<String, Object> subFlow1Vars = new HashMap<>();
        service.subFlow1(subFlow1Vars);
        assertEquals(Map.of("my_variable", "var_value_1"), subFlow1Vars);

        Map<String, Object> subFlow2Vars = new HashMap<>();
        service.subFlow2(subFlow2Vars);
        assertEquals(Map.of("my_variable", "var_value_2"), subFlow2Vars);

        // set-variable replaces an existing value of the same flow variable.
        service.subFlow1(subFlow2Vars);
        assertEquals(Map.of("my_variable", "var_value_1"), subFlow2Vars);
    }

    @Test
    void setOriginalPayloadReturnsUrlKeyOrNull() {
        assertEquals("x", service.setOriginalPayload(Map.of("url_key", "x")));
        assertNull(service.setOriginalPayload(Map.of()));
    }

    @Test
    void exampleFlowFailsWhenExampleFlow2SetsNoVariable() {
        // flowVars['my_variable'].equals('var_value_1') dereferences the variable; SC-1 turns the failure into a 500.
        doNothing().when(service).exampleFlow2(any(), any());

        assertThrows(NullPointerException.class, () -> service.exampleFlow(Map.of("url_key", "payload_1")));
    }

    @Test
    void exampleFlowFailsWithoutQueryParameterMap() {
        assertThrows(NullPointerException.class, () -> service.exampleFlow(null));
    }

    @Test
    void exampleFlowRunsProcessorsInFlowOrderOnOneFlowVariableMap() {
        Map<String, String> queryParams = Map.of("url_key", "payload_1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> flowVarsCaptor = ArgumentCaptor.forClass(Map.class);

        assertEquals("response_payload_1", service.exampleFlow(queryParams));

        InOrder inOrder = Mockito.inOrder(service);
        inOrder.verify(service).exampleFlow(queryParams);
        inOrder.verify(service).setOriginalPayload(queryParams);
        inOrder.verify(service).exampleFlow2(eq("payload_1"), flowVarsCaptor.capture());
        inOrder.verify(service).subFlow1(flowVarsCaptor.capture());
        verifyNoMoreInteractions(service);

        // The variable a sub-flow sets is visible to the calling flow, as Mule 3 flow variables are.
        List<Map<String, Object>> flowVars = flowVarsCaptor.getAllValues();
        assertSame(flowVars.get(0), flowVars.get(1));
        assertEquals(Map.of("my_variable", "var_value_1"), flowVars.get(1));
    }

    @Test
    void constantsHoldTheOriginalLiterals() {
        assertEquals("url_key", ExampleFlowService.URL_KEY);
        assertEquals("payload_1", ExampleFlowService.PAYLOAD_1);
        assertEquals("my_variable", ExampleFlowService.MY_VARIABLE);
        assertEquals("var_value_1", ExampleFlowService.VAR_VALUE_1);
        assertEquals("var_value_2", ExampleFlowService.VAR_VALUE_2);
        assertEquals("response_payload_1", ExampleFlowService.RESPONSE_PAYLOAD_1);
        assertEquals("response_payload_2", ExampleFlowService.RESPONSE_PAYLOAD_2);
    }
}
