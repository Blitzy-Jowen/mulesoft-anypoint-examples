/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;

/**
 * HTTP trigger of flow {@code mule-component-bindingsFlow1} (mule-component-bindings/src/main/app/
 * mule-component-bindings.xml:31-36): the listener on {@code localhost:8081} path {@code /}, whose fixed Twitter
 * search and {@code TwitterResponseProcessor} mapping run in {@link TwitterSearchService#searchListenerFlow()}.
 *
 * <p>The listener declares no {@code allowedMethods} and the flow reads no query, header or body, so every method is
 * answered by the same flow, and no handler declares {@code produces}, {@code consumes}, parameters or a request body:
 * whatever {@code Accept}, {@code Content-Type} or body the caller sends is ignored rather than rejected with 406, 415
 * or 400. The match is exact; any other path on this listener gets the no-listener 404, and {@code /} requested on the
 * stock-statistics listener is rejected by {@code PortRoutingFilter} before it reaches this class.
 *
 * <p>The reply carries a {@code Content-Length} and no {@code Content-Type}, because the Mule listener sent the
 * Java-serialized payload without one. The bytes are written to the servlet response directly, so no message
 * converter adds a Content-Type. Failures propagate uncaught to {@code GlobalExceptionHandler}, which answers them
 * with its 500 {@code { "message": ... }} body.
 */
@RestController
public class TwitterSearchController {

    private final TwitterSearchService twitterSearchService;

    public TwitterSearchController(TwitterSearchService twitterSearchService) {
        this.twitterSearchService = twitterSearchService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}, TRACE included because the application dispatches
     * TRACE requests to handlers; a {@code HEAD} response carries the headers without the body.
     *
     * @param response the response the flow's payload is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/")
    public void search(HttpServletResponse response) throws IOException {
        writeListenerPayload(response);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param response the response the flow's payload is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void searchOptions(HttpServletResponse response) throws IOException {
        writeListenerPayload(response);
    }

    /**
     * Writes 200 with the flow's payload bytes and their {@code Content-Length}. The payload is obtained before the
     * response is touched, so a failure still finds an uncommitted response for the 500 of
     * {@code GlobalExceptionHandler}.
     */
    private void writeListenerPayload(HttpServletResponse response) throws IOException {
        byte[] bytes = twitterSearchService.searchListenerFlow();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
