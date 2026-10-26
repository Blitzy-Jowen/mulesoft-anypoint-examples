/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link GreeterService}, the {@code greet} operation every Greeter endpoint serves
 * (soap-webservice-security/src/main/java/com/mulesoft/mule/example/security/GreeterService.java).
 *
 * <p>Expected values are literals, so the tests pin the original reply text rather than re-deriving it.
 */
class GreeterServiceTest {

    private final GreeterService service = new GreeterService();

    @Test
    void greetReturnsHelloMuleAsTheOriginalAsserts() {
        // SoapWebserviceSecurityIT asserts this reply on every positive security path.
        assertEquals("Hello Mule", service.greet("Mule"));
    }

    @Test
    void greetPrefixesName() {
        assertEquals("Hello John", service.greet("John"));
    }

    @Test
    void greetWithNullNameUsesJavaConcatenation() {
        assertEquals("Hello null", service.greet(null));
    }

    @Test
    void greetWithEmptyNameKeepsTrailingSpace() {
        assertEquals("Hello ", service.greet(""));
    }
}
