/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService;

/**
 * Port of the original example's MUnit suite munit-short-tutorial/src/test/munit/production-code-test-suite.xml,
 * which tests the flows of production-code.xml. Each MUnit test becomes one JUnit 5 test, in the suite's order:
 *
 * <ul>
 * <li>{@link #docTestExampleFlow2Test1()}: {@code doc-test-exampleFlow2Test1} (line 19)</li>
 * <li>{@link #docTestExampleFlow2Test2()}: {@code doc-test-exampleFlow2Test2} (line 29)</li>
 * <li>{@link #docTestExampleFlowUnitTest1()}: {@code doc-test-exampleFlow-unit-Test_1} (line 40)</li>
 * <li>{@link #docTestExampleFlowUnitTest2()}: {@code doc-test-exampleFlow-unit-Test_2} (line 61)</li>
 * <li>{@link #docTestExampleFlowFunctionalTest1()}: {@code doc-test-exampleFlow-functionalTest_1} (line 83)</li>
 * <li>{@link #docTestExampleFlowFunctionalTest2()}: {@code doc-test-exampleFlow-functionalTest_2} (line 93)</li>
 * </ul>
 *
 * <p>The suite imported production-code.xml into a running Mule context; its counterpart here is the application's
 * Spring context with {@link ExampleFlowService}, the flow graph, replaced by a Mockito spy. The service calls its own
 * flow, sub-flow and {@code Set Original Payload} methods through {@code this.}, so the spy records and can stub every
 * step the MUnit mocks and verifications addressed. MUnit constructs translate as follows:
 *
 * <ul>
 * <li>{@code munit:set} payload and inbound property {@code http.query.params}: the argument of the service method.
 * The functional tests' {@code munit:set payload="#['']"} has no counterpart, because {@code Set Original Payload}
 * overwrites that payload before anything reads it.</li>
 * <li>{@code flow-ref} to the flow under test: a direct call of the service method.</li>
 * <li>{@code mock:verify-call} on {@code mule:sub-flow} with {@code times}: {@code verify} with {@code times} on the
 * sub-flow method.</li>
 * <li>{@code mock:when ... mock:then-return}: {@code doReturn} for the mocked {@code set-payload} (payload
 * {@code #[]}, a null payload) and {@code doAnswer} for the mocked flow {@code exampleFlow2}, which sets the returned
 * invocation property {@code my_variable} in the caller's flow variables.</li>
 * <li>{@code munit:assert-payload-equals}: {@code assertEquals} with the suite's message.</li>
 * </ul>
 *
 * <p>Expected values are the suite's literals rather than the service's constants, so the test pins the original
 * values. The spy's stubs and recorded invocations are reset after each test ({@code MockReset.AFTER}), as MUnit reset
 * its mocks between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductionCodeTestSuiteTest {

    @SpyBean
    private ExampleFlowService exampleFlowService;

    /** {@code doc-test-exampleFlow2Test1} (production-code-test-suite.xml:19-27). */
    @Test
    void docTestExampleFlow2Test1() {
        exampleFlowService.exampleFlow2("payload_1", new HashMap<>());

        // mock:verify-call messageProcessor="mule:sub-flow" name matchContains('exampleSub_Flow1') times="1"
        verify(exampleFlowService, times(1)).subFlow1(anyMap());
        verify(exampleFlowService, never()).subFlow2(anyMap());
    }

    /** {@code doc-test-exampleFlow2Test2} (production-code-test-suite.xml:29-37). */
    @Test
    void docTestExampleFlow2Test2() {
        exampleFlowService.exampleFlow2("payload_2", new HashMap<>());

        // mock:verify-call messageProcessor="mule:sub-flow" name matchContains('exampleSub_Flow2') times="1"
        verify(exampleFlowService, times(1)).subFlow2(anyMap());
        verify(exampleFlowService, never()).subFlow1(anyMap());
    }

    /** {@code doc-test-exampleFlow-unit-Test_1} (production-code-test-suite.xml:40-59). */
    @Test
    void docTestExampleFlowUnitTest1() {
        stubExampleFlowCollaborators("var_value_1");

        // The MUnit test sent no inbound properties; the mocked Set Original Payload never reads them.
        String result = exampleFlowService.exampleFlow(null);

        assertEquals("response_payload_1", result, "oops, wrong payload!");
    }

    /** {@code doc-test-exampleFlow-unit-Test_2} (production-code-test-suite.xml:61-80). */
    @Test
    void docTestExampleFlowUnitTest2() {
        stubExampleFlowCollaborators("var_value_2");

        // The MUnit test sent no inbound properties; the mocked Set Original Payload never reads them.
        String result = exampleFlowService.exampleFlow(null);

        assertEquals("response_payload_2", result, "oops, wrong payload!");
    }

    /** {@code doc-test-exampleFlow-functionalTest_1} (production-code-test-suite.xml:83-91). */
    @Test
    void docTestExampleFlowFunctionalTest1() {
        // inbound property http.query.params = ['url_key':'payload_1']
        String result = exampleFlowService.exampleFlow(Map.of("url_key", "payload_1"));

        assertEquals("response_payload_1", result, "oops, wrong payload!");
    }

    /** {@code doc-test-exampleFlow-functionalTest_2} (production-code-test-suite.xml:93-101). */
    @Test
    void docTestExampleFlowFunctionalTest2() {
        // inbound property http.query.params = ['url_key':'payload_2']. The original munit:set is labelled
        // doc:name "Set Message url_key:payload_1" (line 94) while its value is payload_2 (line 96); the value is
        // what the test sends, so payload_2 is ported.
        String result = exampleFlowService.exampleFlow(Map.of("url_key", "payload_2"));

        assertEquals("response_payload_2", result, "oops, wrong payload!");
    }

    /**
     * The two {@code mock:when} elements the unit tests share: {@code set-payload} {@code Set Original Payload}
     * returns payload {@code #[]} (null), and flow {@code exampleFlow2} returns invocation property
     * {@code my_variable} with the given value.
     */
    private void stubExampleFlowCollaborators(String myVariable) {
        doReturn(null).when(exampleFlowService).setOriginalPayload(any());
        doAnswer(invocation -> {
            Map<String, Object> flowVars = invocation.getArgument(1);
            flowVars.put("my_variable", myVariable);
            return null;
        }).when(exampleFlowService).exampleFlow2(any(), anyMap());
    }
}
