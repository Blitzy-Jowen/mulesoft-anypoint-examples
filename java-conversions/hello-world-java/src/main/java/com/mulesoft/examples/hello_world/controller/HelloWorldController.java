/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.controller;

import com.mulesoft.examples.hello_world.service.HelloWorldService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code HelloWorldFlow1} (hello-world/src/main/app/HelloWorld.xml:5-6): the listener on
 * {@code /helloWorld}, whose payload is set by {@link HelloWorldService}.
 *
 * <p>The listener declares no {@code allowedMethods} and the flow reads no query, header or body, so every method
 * is answered with the same 200 {@code text/plain;charset=UTF-8} {@code Hello World}, and no handler declares
 * {@code produces}, {@code consumes}, parameters or a request body: any {@code Accept}, {@code Content-Type} or body
 * the caller sends is ignored rather than rejected with 406, 415 or 400. The match is exact; any other path,
 * {@code /helloWorld/} and {@code /helloWorld/extra} included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/helloWorld")
public class HelloWorldController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final HelloWorldService helloWorldService;

    public HelloWorldController(HelloWorldService helloWorldService) {
        this.helloWorldService = helloWorldService;
    }

    /**
     * Answers {@code /helloWorld} for every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body.
     *
     * @return 200 {@code text/plain;charset=UTF-8} {@code Hello World}
     */
    @RequestMapping
    public ResponseEntity<String> helloWorld() {
        return helloWorldResponse();
    }

    /**
     * Answers {@code OPTIONS /helloWorld} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @return 200 {@code text/plain;charset=UTF-8} {@code Hello World}
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<String> helloWorldOptions() {
        return helloWorldResponse();
    }

    /**
     * The Content-Type is preset on the entity, so Spring writes the body without consulting {@code Accept} and
     * can never answer 406.
     */
    private ResponseEntity<String> helloWorldResponse() {
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(helloWorldService.hello());
    }
}
