/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link HelloWorldService}, which replaces the {@code set-payload value="Hello World"} processor of
 * flow {@code HelloWorldFlow1} (hello-world/src/main/app/HelloWorld.xml:6).
 *
 * <p>Expected values are string literals rather than {@link HelloWorldService#HELLO_WORLD}, so the tests pin the
 * original payload bytes instead of whatever the constant holds.
 */
class HelloWorldServiceTest {

    @Test
    void helloReturnsHelloWorld() {
        String result = new HelloWorldService().hello();

        // The original HelloWorldIT asserted a non-null message whose payload was not NullPayload.
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("Hello World", result);
    }

    @Test
    void helloWorldConstantMatchesOriginalPayload() {
        assertEquals("Hello World", HelloWorldService.HELLO_WORLD);
    }

    @Test
    void helloIsStableAcrossCalls() {
        HelloWorldService service = new HelloWorldService();

        assertEquals(service.hello(), service.hello());
    }
}
