/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.mulesoft.examples.content_based_routing.service.LanguageRoutingService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code content-based-routingFlow}
 * (content-based-routing/src/main/app/content-based-routing.xml:5): the listener on {@code /}, whose filter,
 * routing choice and loggers run in {@link LanguageRoutingService}.
 *
 * <p>The controller only binds input and returns output. The listener's payload is the request body, handed over as
 * text exactly as received ({@code null} when the request has none); the flow variable {@code language} is the
 * decoded {@code language} query parameter as the Mule listener exposed it in {@code http.query.params}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method is served. The match is exact: any other
 * path, including the icon path browsers request, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}, and every exception reaches its 500; nothing is caught here.
 */
@RestController
public class GreetingController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private static final String LANGUAGE_PARAMETER = "language";

    private static final String PAIR_SEPARATOR = "&";

    private static final String NAME_VALUE_SEPARATOR = "=";

    private final LanguageRoutingService languageRoutingService;

    public GreetingController(LanguageRoutingService languageRoutingService) {
        this.languageRoutingService = languageRoutingService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers without
     * the body.
     *
     * @param payload the request body as text, or {@code null} when the request has none
     * @param request the request, whose raw query string supplies {@code language}
     * @return the reply as 200 {@code text/plain;charset=UTF-8}, or an empty 200 when the service's payload filter
     *         rejects the request
     */
    @RequestMapping("/")
    public ResponseEntity<String> greeting(@RequestBody(required = false) String payload, HttpServletRequest request) {
        return respond(payload, request);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param payload the request body as text, or {@code null} when the request has none
     * @param request the request, whose raw query string supplies {@code language}
     * @return the same response as {@link #greeting}
     */
    @RequestMapping(value = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greetingOptions(@RequestBody(required = false) String payload,
                                                  HttpServletRequest request) {
        return respond(payload, request);
    }

    /**
     * The Content-Type is preset on the entity, so Spring writes the reply without consulting {@code Accept} and can
     * never answer 406. A filter rejection sets no Content-Type and no body, as the Mule listener answered the
     * flow's null payload.
     */
    private ResponseEntity<String> respond(String payload, HttpServletRequest request) {
        Optional<String> reply = languageRoutingService.reply(payload, languageFrom(request.getQueryString()));
        if (reply.isPresent()) {
            return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(reply.get());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Reads {@code language} from the raw query string the way the Mule 3.8 listener built and read
     * {@code http.query.params}: {@code org.mule.module.http.internal.HttpParser#decodeQueryString} splits the
     * string on {@code &} and each pair on its first {@code =}, URL-decodes name and value as UTF-8, and gives a
     * pair without {@code =} a {@code null} value; {@code org.mule.module.http.internal.ParameterMap#get} then
     * returns {@code values.getLast()}, so of a repeated name the last pair wins.
     *
     * <p>{@code @RequestParam} and {@link HttpServletRequest#getParameter} are not used: the first joins repeated
     * values with commas, the second returns the first value and also reads form fields of the body, so either
     * could select a different routing branch than the original.
     *
     * <p>Undertow already rejects a query string holding a malformed percent escape with an empty 400 while parsing
     * the request, so none reaches this method; should one arrive, the {@link IllegalArgumentException} of
     * {@link URLDecoder} is left to the 500 of {@code GlobalExceptionHandler} rather than caught here.
     *
     * @param queryString the raw, undecoded query string, or {@code null} when the request has none
     * @return the decoded value of the last {@code language} pair: {@code ""} for {@code language=}, {@code null}
     *         for a bare {@code language} or when no pair is named {@code language}
     * @throws IllegalArgumentException if a name, or the value of a {@code language} pair, holds a malformed
     *                                  percent escape
     */
    private static String languageFrom(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        String language = null;
        for (String pair : queryString.split(PAIR_SEPARATOR)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (LANGUAGE_PARAMETER.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                language = separator < 0
                        ? null
                        : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return language;
    }
}
