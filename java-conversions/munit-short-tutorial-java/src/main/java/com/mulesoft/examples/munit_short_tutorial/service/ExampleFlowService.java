/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Reproduces the flow graph of munit-short-tutorial/src/main/app/production-code.xml: flow {@code exampleFlow}
 * (lines 6-21), flow {@code exampleFlow2} (lines 23-32) and sub-flows {@code exampleSub_Flow1} (lines 34-36) and
 * {@code exampleSub_Flow2} (lines 38-40).
 *
 * <p>Each flow, sub-flow and the {@code Set Original Payload} processor is its own public, non-final method, and the
 * methods call one another through {@code this.}, so that a Mockito spy intercepts every step.
 * {@code ProductionCodeTestSuiteTest} stubs and verifies them exactly as the MUnit suite
 * (production-code-test-suite.xml) mocked and verified the processors. For that reason the class carries no
 * annotation that would put an AOP proxy in front of the spy.
 *
 * <p>Flow variables live in a {@link HashMap} created for each request and passed down the graph, so a variable set
 * in a referenced flow or sub-flow is visible to the caller afterwards, as in Mule 3.
 */
@Service
public class ExampleFlowService {

    /** Query parameter read by {@code Set Original Payload} (production-code.xml:8). */
    public static final String URL_KEY = "url_key";

    /** Payload that routes {@code exampleFlow2} to {@code exampleSub_Flow1} (production-code.xml:25). */
    public static final String PAYLOAD_1 = "payload_1";

    /** Flow variable set by both sub-flows and tested by {@code exampleFlow} (production-code.xml:14, 35, 39). */
    public static final String MY_VARIABLE = "my_variable";

    /** Value {@code exampleSub_Flow1} assigns to {@value #MY_VARIABLE} (production-code.xml:14, 35). */
    public static final String VAR_VALUE_1 = "var_value_1";

    /** Value {@code exampleSub_Flow2} assigns to {@value #MY_VARIABLE} (production-code.xml:39). */
    public static final String VAR_VALUE_2 = "var_value_2";

    /** Response payload when {@value #MY_VARIABLE} is {@value #VAR_VALUE_1} (production-code.xml:15). */
    public static final String RESPONSE_PAYLOAD_1 = "response_payload_1";

    /** Response payload in every other case (production-code.xml:18). */
    public static final String RESPONSE_PAYLOAD_2 = "response_payload_2";

    /**
     * Flow {@code exampleFlow} (production-code.xml:6-21) after its HTTP listener: sets the original payload, calls
     * {@code exampleFlow2} and chooses the response payload from {@value #MY_VARIABLE}.
     *
     * @param queryParams the request's query parameters, one value per name
     * @return {@value #RESPONSE_PAYLOAD_1} when {@value #MY_VARIABLE} equals {@value #VAR_VALUE_1}, otherwise
     *         {@value #RESPONSE_PAYLOAD_2}
     * @throws NullPointerException when {@code exampleFlow2} sets no {@value #MY_VARIABLE}, which only a stubbed
     *         {@code exampleFlow2} can do
     */
    public String exampleFlow(Map<String, String> queryParams) {
        Map<String, Object> flowVars = new HashMap<>();
        Object payload = this.setOriginalPayload(queryParams);
        this.exampleFlow2(payload, flowVars);
        // The MEL condition flowVars['my_variable'].equals('var_value_1') calls equals on the variable itself, so a
        // missing variable fails the flow; it is deliberately neither null-guarded nor reversed.
        if (flowVars.get(MY_VARIABLE).equals(VAR_VALUE_1)) {
            return RESPONSE_PAYLOAD_1;
        } else {
            return RESPONSE_PAYLOAD_2;
        }
    }

    /**
     * The {@code set-payload} processor {@code Set Original Payload} (production-code.xml:8),
     * {@code #[message.inboundProperties['http.query.params']['url_key']]}.
     *
     * @param queryParams the request's query parameters, one value per name
     * @return the value of {@value #URL_KEY}, unchanged, or {@code null} when it is absent
     */
    public Object setOriginalPayload(Map<String, String> queryParams) {
        return queryParams.get(URL_KEY);
    }

    /**
     * Flow {@code exampleFlow2} (production-code.xml:23-32): {@code #['payload_1'.equals(payload)]} routes to
     * {@code exampleSub_Flow1}, every other payload, {@code null} included, to {@code exampleSub_Flow2}.
     *
     * @param payload the current message payload
     * @param flowVars the flow variables of the calling flow
     */
    public void exampleFlow2(Object payload, Map<String, Object> flowVars) {
        if (PAYLOAD_1.equals(payload)) {
            this.subFlow1(flowVars);
        } else {
            this.subFlow2(flowVars);
        }
    }

    /**
     * Sub-flow {@code exampleSub_Flow1} (production-code.xml:34-36): sets {@value #MY_VARIABLE} to
     * {@value #VAR_VALUE_1}.
     *
     * @param flowVars the flow variables of the calling flow
     */
    public void subFlow1(Map<String, Object> flowVars) {
        flowVars.put(MY_VARIABLE, VAR_VALUE_1);
    }

    /**
     * Sub-flow {@code exampleSub_Flow2} (production-code.xml:38-40): sets {@value #MY_VARIABLE} to
     * {@value #VAR_VALUE_2}.
     *
     * @param flowVars the flow variables of the calling flow
     */
    public void subFlow2(Map<String, Object> flowVars) {
        flowVars.put(MY_VARIABLE, VAR_VALUE_2);
    }
}
