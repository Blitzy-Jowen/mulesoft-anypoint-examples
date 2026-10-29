/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.hello_world.service.HelloWorldService;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code HelloWorldFlow1} (hello-world/src/main/app/HelloWorld.xml:5-6): the listener on
 * {@code /helloWorld}, whose payload is set by {@link HelloWorldService}.
 *
 * <p>The listener declares no {@code allowedMethods} and the flow reads no query, header or body, so every method
 * is answered with the same 200 {@code Hello World}, and no handler declares {@code produces}, {@code consumes},
 * parameters or a request body: any {@code Accept}, {@code Content-Type} or body the caller sends is ignored rather
 * than rejected with 406, 415 or 400. The match is exact; any other path, {@code /helloWorld/} and
 * {@code /helloWorld/extra} included, falls through to the no-listener 404 of {@code GlobalExceptionHandler}.
 *
 * <p>The reply carries a {@code Content-Length} and no {@code Content-Type}: the flow's {@code set-payload}
 * declares no MIME type, and the Mule listener sent no Content-Type for such a payload. It is written to the servlet
 * response directly, so no message converter adds one.
 */
@RestController
@RequestMapping("/helloWorld")
public class HelloWorldController {

    private final HelloWorldService helloWorldService;

    public HelloWorldController(HelloWorldService helloWorldService) {
        this.helloWorldService = helloWorldService;
    }

    /**
     * Answers {@code /helloWorld} for every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body.
     *
     * @param response the response {@code Hello World} is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping
    public void helloWorld(HttpServletResponse response) throws IOException {
        helloWorldResponse(response);
    }

    /**
     * Answers {@code OPTIONS /helloWorld} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param response the response {@code Hello World} is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public void helloWorldOptions(HttpServletResponse response) throws IOException {
        helloWorldResponse(response);
    }

    /**
     * Writes 200 with the payload's UTF-8 bytes and their {@code Content-Length}. The payload is obtained before the
     * response is touched, so a failure still finds an uncommitted response for the 500 of
     * {@code GlobalExceptionHandler}.
     */
    private void helloWorldResponse(HttpServletResponse response) throws IOException {
        byte[] bytes = helloWorldService.hello().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }
}
