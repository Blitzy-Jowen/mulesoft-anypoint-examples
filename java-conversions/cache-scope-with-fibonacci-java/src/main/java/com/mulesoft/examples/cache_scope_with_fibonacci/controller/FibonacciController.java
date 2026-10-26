/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciService;

/**
 * Replaces the HTTP listener of flow {@code cache-exampleFlow1}
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:40,44) and the Mule HTTP transport's conversion of the
 * flow result into the response. It only binds the request and writes the reply; the favicon filter, the
 * {@code n} limit and the calculation belong to {@link FibonacciService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method runs the flow: {@code OPTIONS} reaches
 * {@link #fibonacciOptions}, every other method {@link #fibonacci}, and both answer alike. Host {@code localhost}
 * and port {@code ${http.port}} come from {@code application.yml}. Exceptions are not caught here: the flow has no
 * exception strategy, so they reach the generic 500 of {@code exception/GlobalExceptionHandler}.
 */
@RestController
public class FibonacciController {

    private final FibonacciService fibonacciService;

    /**
     * @param fibonacciService the port of the flow's processors after its listener
     */
    public FibonacciController(FibonacciService fibonacciService) {
        this.fibonacciService = fibonacciService;
    }

    /**
     * Serves {@code /fibonacci} for every method except {@code OPTIONS}, which {@link #fibonacciOptions} hands
     * here.
     *
     * <p>Input: the query parameters {@code n} and {@code nocache} (the original {@code http.query.params}), the
     * {@code nocache} request header (the inbound property the cache scope's filter reads) and the raw body (the
     * payload the favicon wildcard-filter tests). Output: status 200, the {@code cost} header when the reply carries
     * a cost (the original outbound property {@code cost}), and the reply text as UTF-8 bytes with an explicit
     * Content-Length. No Content-Type is set, because the original reply carried none (a Tier 2A verification
     * point).
     *
     * @param request  the HTTP request
     * @param response the HTTP response, written completely by this method
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/fibonacci")
    public void fibonacci(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Parsed from the raw query string: the servlet parameter API would also merge, and consume, a
        // form-encoded body that must reach the service intact.
        Map<String, String> query = parseQuery(request.getQueryString());
        String n = query.get("n");
        boolean nocacheQuery = query.get("nocache") != null;
        boolean nocacheHeader = request.getHeader("nocache") != null;
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        FibonacciService.FibonacciReply reply = fibonacciService.handle(n, body, nocacheHeader, nocacheQuery);

        response.setStatus(HttpServletResponse.SC_OK);
        if (reply.cost() != null) {
            response.setHeader("cost", String.valueOf(reply.cost()));
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /**
     * Serves {@code OPTIONS /fibonacci} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the HTTP request
     * @param response the HTTP response, written completely by {@link #fibonacci}
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping(value = "/fibonacci", method = RequestMethod.OPTIONS)
    public void fibonacciOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        fibonacci(request, response);
    }

    /**
     * Decodes a raw query string into its parameters, in order of first appearance.
     *
     * <p>Segments are separated by {@code &}, and empty segments are skipped. Each segment splits at its first
     * {@code =}: the value is everything after it, further {@code =} included. A bare name without {@code =} maps to
     * null. Names and values are URL-decoded as UTF-8. When a name occurs more than once the last occurrence wins,
     * as Mule's {@code ParameterMap.get} returned it, so a later bare name yields null.
     *
     * @param rawQuery the undecoded query string, or null when the request has none
     * @return the parameters; empty for a null or empty query string
     * @throws IllegalArgumentException when a name or value holds a malformed percent escape
     */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String segment : rawQuery.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int separator = segment.indexOf('=');
            String name;
            String value;
            if (separator < 0) {
                name = URLDecoder.decode(segment, StandardCharsets.UTF_8);
                value = null;
            } else {
                name = URLDecoder.decode(segment.substring(0, separator), StandardCharsets.UTF_8);
                value = URLDecoder.decode(segment.substring(separator + 1), StandardCharsets.UTF_8);
            }
            // Mule's ParameterMap kept every value of a name and its get returned the last, so each occurrence
            // replaces the previous one, a bare name's null included: "?n=5&n" yields null, "?n&n=5" yields "5".
            parameters.put(name, value);
        }
        return parameters;
    }
}
