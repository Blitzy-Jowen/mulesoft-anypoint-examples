/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.service;

import org.springframework.stereotype.Service;

/** Reproduces the {@code set-payload} processor of flow {@code HelloWorldFlow1} (hello-world/src/main/app/HelloWorld.xml:6). */
@Service
public class HelloWorldService {

    /** The constant payload the original flow returns for every request, byte for byte. */
    public static final String HELLO_WORLD = "Hello World";

    /**
     * Returns the response payload of {@code /helloWorld}.
     *
     * @return {@value #HELLO_WORLD}
     */
    public String hello() {
        return HELLO_WORLD;
    }
}
