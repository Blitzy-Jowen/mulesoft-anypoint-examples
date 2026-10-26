/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService;

/**
 * HTTP trigger of flow {@code exampleFlow} (munit-short-tutorial/src/main/app/production-code.xml:6-21): the
 * listener {@code http:listener allowedMethods="GET" path="/"} of line 7. Host and port of the
 * {@code http:listener-config} (line 4) are {@code server.address} and {@code server.port} in
 * {@code application.yml}.
 *
 * <p>The class only binds the request's query parameters and writes the flow's payload; setting the original
 * payload, the {@code exampleFlow2} routing and the final choice are {@link ExampleFlowService}'s. No handler declares
 * {@code produces}, {@code consumes} or parameter conditions, so any {@code Accept} or {@code Content-Type} the
 * caller sends is ignored rather than rejected with 406 or 415, as the Mule listener ignored them. The payload is
 * written with its {@code Content-Length} and no {@code Content-Type}: the flow's {@code set-payload} processors
 * declare no MIME type, and the Mule listener sent no Content-Type for such a payload.
 *
 * <p>The listener accepted {@code GET} only, compared ignoring case as Mule 3.8's {@code MethodRequestMatcher} did,
 * so {@code get} runs the flow too. Every other method on {@code /} and every other path reach the generic handler
 * of the exception package, which answers 405 {@code Method not allowed for endpoint: /} and 404
 * {@code No listener for endpoint: <path>}.
 */
@RestController
public class ExampleController {

    private static final String ALLOWED_METHOD = "GET";

    private final ExampleFlowService exampleFlowService;

    /**
     * @param exampleFlowService runs flow {@code exampleFlow} after its listener
     */
    public ExampleController(ExampleFlowService exampleFlowService) {
        this.exampleFlowService = exampleFlowService;
    }

    /**
     * Runs flow {@code exampleFlow} for {@code GET /}, in any letter case. The match is exact: {@code /x} and
     * {@code /index} are unserved paths.
     *
     * <p>The mapping declares no method, so that the method can be compared with {@code allowedMethods} ignoring
     * case; it matches every method but {@code OPTIONS}, and the check rejects each one other than {@code GET},
     * {@code HEAD} included, which Spring MVC would otherwise answer through a {@code GET} mapping. The payload is
     * written only after the flow returns, so a failure still finds an uncommitted response for the generic 500.
     *
     * @param request  the request, whose method and query string are read
     * @param response the response the flow's payload is written to, as 200 with its UTF-8 bytes
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code GET} in some letter case
     * @throws IOException                            when writing the response fails
     */
    @RequestMapping("/")
    public void exampleFlow(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        if (!ALLOWED_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
        }
        String result = exampleFlowService.exampleFlow(queryParams(request.getQueryString()));
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Builds the replacement for {@code message.inboundProperties['http.query.params']} from the raw query string,
     * as Mule 3.8's {@code HttpParser.decodeQueryString} filled it: pairs split on {@code &} and at the first
     * {@code =}, name and value decoded with {@link URLDecoder} in UTF-8 ({@code +} becomes a space), a pair
     * without {@code =} given a {@code null} value, and a repeated name keeping its first position and its last
     * value, which is what the parameter map returned for a single-valued lookup.
     *
     * <p>The query string is parsed here rather than read from {@code getParameterMap()}: the servlet container
     * folds a form-urlencoded request body into the parameter map even on {@code GET}, which would let a body
     * select a branch that the original, reading the query alone, never selected. Undertow answers a query with a
     * malformed percent escape with its own empty 400 before dispatch, so only well-formed escapes reach
     * {@link URLDecoder}.
     */
    private static Map<String, String> queryParams(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? null : pair.substring(separator + 1);
            params.put(decode(name), decode(value));
        }
        return params;
    }

    private static String decode(String text) {
        return text == null ? null : URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    /**
     * Rejects {@code OPTIONS} on {@code /} with the listener's 405, which the generic handler of the exception
     * package writes as {@code Method not allowed for endpoint: /}.
     *
     * <p>A mapping without methods does not match {@code OPTIONS}, so without this one Spring MVC would answer it
     * itself with 200 and an {@code Allow} header, which the GET-only listener refused; the explicit match keeps
     * the handler mapping from reaching its default {@code OPTIONS} handler. Every other method reaches
     * {@link #exampleFlow}, which rejects it.
     *
     * @param request the rejected request, whose method is reported
     * @return never returns normally
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> rejectOptions(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
    }
}
