/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.mulesoft.examples.mule_expression_language_basics.service.GreetingService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP triggers of the six flows of {@code mule-expression-language-basics/src/main/app/greeting.xml}: the listeners
 * on {@code /greet1} to {@code /greet6} of {@code HTTP_Listener_Configuration} ({@code greeting.xml:4}), whose
 * processors run in {@link GreetingService}.
 *
 * <table>
 *   <caption>Paths and the flows they trigger</caption>
 *   <tr><th>Path</th><th>Flow</th><th>Input bound</th></tr>
 *   <tr><td>{@code /greet1}</td><td>{@code greetingFlow1}</td><td>query {@code username}</td></tr>
 *   <tr><td>{@code /greet2}</td><td>{@code greetingFlow2}</td><td>query {@code username}</td></tr>
 *   <tr><td>{@code /greet3}</td><td>{@code docs-greetingFlow3}</td><td>query {@code username}, {@code age}</td></tr>
 *   <tr><td>{@code /greet4}</td><td>{@code docs-greetingFlow4}</td><td>query {@code username}, {@code age}</td></tr>
 *   <tr><td>{@code /greet5}</td><td>{@code docs-greetingFlow5}</td><td>body bytes (XML)</td></tr>
 *   <tr><td>{@code /greet6}</td><td>{@code greetingFlow6}</td><td>body bytes (JSON)</td></tr>
 * </table>
 *
 * <p>The controller only binds input and returns output: every literal, branch, comparison and file write lives in
 * the service. Every exception the service raises reaches the 500 of {@code GlobalExceptionHandler} unchanged, and no
 * file is written for a failed call; nothing is caught or logged here, as the flows declare no exception strategy and
 * no logger.
 *
 * <p>No listener declares {@code allowedMethods}, so every path is served for every method, non-standard tokens
 * included, and no mapping declares {@code produces}, {@code consumes}, {@code params} or {@code headers}: any
 * {@code Accept}, {@code Content-Type} or body is accepted rather than answered with 406, 415 or 400. {@code OPTIONS}
 * runs the flow like every other method, returning the same reply and, for {@code greet3} to {@code greet6}, writing
 * the same file. {@code TRACE} does too, because {@code application.yml} sets
 * {@code spring.mvc.dispatch-trace-request: true}; the committed reply keeps the servlet's {@code message/http} echo
 * off the wire. A {@code HEAD} reply carries the headers without the body. Paths match exactly: {@code /greet1/},
 * {@code /greet1.json}, {@code /} or {@code /greet7} fall through to the no-listener 404 of
 * {@code GlobalExceptionHandler}.
 *
 * <p>Every reply is a 200 with {@code Content-Type: text/plain;charset=UTF-8}, preset on the entity so Spring writes
 * the String without consulting {@code Accept}. The header is a Tier 2A verification point of the conversion plan:
 * should a captured fixture of the original show another value, such as {@code application/csv} after
 * {@code greet4}'s DataWeave step, or none, only {@code TEXT_PLAIN_UTF8} changes.
 */
@RestController
public class GreetingController {

    /** Content-Type of every reply: {@code text/plain;charset=UTF-8}. */
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    /** Key of {@code http.query.params} every query-driven flow greets with. */
    private static final String USERNAME = "username";

    /** Key of {@code http.query.params} {@code greet3} and {@code greet4} compare with 18. */
    private static final String AGE = "age";

    private static final String PAIR_SEPARATOR = "&";

    private static final char NAME_VALUE_SEPARATOR = '=';

    private final GreetingService greetingService;

    public GreetingController(GreetingService greetingService) {
        this.greetingService = greetingService;
    }

    /**
     * {@code greetingFlow1} ({@code greeting.xml:7-11}) for every method except {@code OPTIONS}.
     *
     * @param request the request, whose raw query string supplies {@code username}
     * @return 200 {@code Hello <username>}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet1")
    public ResponseEntity<String> greet1(HttpServletRequest request) throws Exception {
        return ok(greetingService.greet1(queryParams(request).get(USERNAME)));
    }

    /**
     * {@code greetingFlow1} for {@code OPTIONS}. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request the request, whose raw query string supplies {@code username}
     * @return the reply of {@link #greet1(HttpServletRequest)}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet1", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet1Options(HttpServletRequest request) throws Exception {
        return greet1(request);
    }

    /**
     * {@code greetingFlow2} ({@code greeting.xml:15-26}) for every method except {@code OPTIONS}.
     *
     * @param request the request, whose raw query string supplies {@code username}
     * @return 200 {@code No username provided} for an empty username, otherwise {@code Hello <username>}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet2")
    public ResponseEntity<String> greet2(HttpServletRequest request) throws Exception {
        return ok(greetingService.greet2(queryParams(request).get(USERNAME)));
    }

    /**
     * {@code greetingFlow2} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request the request, whose raw query string supplies {@code username}
     * @return the reply of {@link #greet2(HttpServletRequest)}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet2", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet2Options(HttpServletRequest request) throws Exception {
        return greet2(request);
    }

    /**
     * {@code docs-greetingFlow3} ({@code greeting.xml:30-37}) for every method except {@code OPTIONS}: the service
     * writes {@code <username>, <age>, <age > 18>} to a new file.
     *
     * @param request the request, whose raw query string supplies {@code username} and {@code age}
     * @return 200 {@code Hello <username>}
     * @throws Exception whatever the service raises, such as the failed comparison of a non-numeric {@code age},
     *                   left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet3")
    public ResponseEntity<String> greet3(HttpServletRequest request) throws Exception {
        Map<String, String> query = queryParams(request);
        return ok(greetingService.greet3(query.get(USERNAME), query.get(AGE)));
    }

    /**
     * {@code docs-greetingFlow3} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request the request, whose raw query string supplies {@code username} and {@code age}
     * @return the reply of {@link #greet3(HttpServletRequest)}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet3", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet3Options(HttpServletRequest request) throws Exception {
        return greet3(request);
    }

    /**
     * {@code docs-greetingFlow4} ({@code greeting.xml:41-60}) for every method except {@code OPTIONS}: the service
     * writes the header-less DataWeave CSV line {@code <username>,<age>,<age_of>} to a new file.
     *
     * @param request the request, whose raw query string supplies {@code username} and {@code age}
     * @return 200 {@code Hello <username>}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet4")
    public ResponseEntity<String> greet4(HttpServletRequest request) throws Exception {
        Map<String, String> query = queryParams(request);
        return ok(greetingService.greet4(query.get(USERNAME), query.get(AGE)));
    }

    /**
     * {@code docs-greetingFlow4} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request the request, whose raw query string supplies {@code username} and {@code age}
     * @return the reply of {@link #greet4(HttpServletRequest)}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet4", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet4Options(HttpServletRequest request) throws Exception {
        return greet4(request);
    }

    /**
     * {@code docs-greetingFlow5} ({@code greeting.xml:64-71}) for every method except {@code OPTIONS}: the service
     * reads {@code /user/username} and {@code /user/age} from the XML body and writes them to a new file.
     *
     * <p>The body is bound as bytes whatever its {@code Content-Type}, absent included, because
     * {@code ByteArrayHttpMessageConverter} reads every media type; the service detects the XML encoding itself. An
     * empty body is bound as {@code null}, which the service rejects.
     *
     * @param body the request body, or {@code null} when the request has none
     * @return 200 {@code Hello <username>}
     * @throws Exception whatever the service raises, such as the parse failure of a malformed document, left to
     *                   {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet5")
    public ResponseEntity<String> greet5(@RequestBody(required = false) byte[] body) throws Exception {
        return ok(greetingService.greet5(body));
    }

    /**
     * {@code docs-greetingFlow5} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param body the request body, or {@code null} when the request has none
     * @return the reply of {@link #greet5(byte[])}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet5", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet5Options(@RequestBody(required = false) byte[] body) throws Exception {
        return greet5(body);
    }

    /**
     * {@code greetingFlow6} ({@code greeting.xml:75-83}) for every method except {@code OPTIONS}: the service parses
     * the JSON body to an object, reads {@code username} and {@code age} and writes them to a new file.
     *
     * <p>The body is bound as bytes whatever its {@code Content-Type}, absent included; an empty body is bound as
     * {@code null}, which the service rejects.
     *
     * @param body the request body, or {@code null} when the request has none
     * @return 200 {@code Hello <username>}
     * @throws Exception whatever the service raises, such as the parse failure of malformed JSON, left to
     *                   {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet6")
    public ResponseEntity<String> greet6(@RequestBody(required = false) byte[] body) throws Exception {
        return ok(greetingService.greet6(body));
    }

    /**
     * {@code greetingFlow6} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param body the request body, or {@code null} when the request has none
     * @return the reply of {@link #greet6(byte[])}
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet6", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> greet6Options(@RequestBody(required = false) byte[] body) throws Exception {
        return greet6(body);
    }

    /** Wraps a flow's reply in the 200 every listener answered with. */
    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(body);
    }

    /**
     * Rebuilds the {@code http.query.params} map of the Mule 3.8 listener from the raw query string, as
     * {@code org.mule.module.http.internal.HttpParser#decodeQueryString} filled its {@code ParameterMap}: a missing
     * or blank query gives an empty map; otherwise the string is split on {@code &} ({@link String#split} semantics,
     * so a leading empty pair is kept as the name {@code ""} and trailing ones are dropped) and each pair on its first
     * {@code =}, name and value are URL-decoded as UTF-8, and a pair without {@code =} gets a {@code null} value.
     * {@code ParameterMap#get} returned the last value of a repeated name, so a later pair replaces an earlier one:
     * {@code ?username=a&username=Mule} greets {@code Mule}.
     *
     * <p>{@link HttpServletRequest#getParameter} and {@code @RequestParam} are not used: they also read the form
     * fields of a form-urlencoded body, which {@code http.query.params} never held, and return the first of repeated
     * values. Every pair is decoded, as Mule decoded the whole map, so a malformed percent escape in any pair raises
     * {@link URLDecoder}'s {@link IllegalArgumentException}, left to {@code GlobalExceptionHandler}; Undertow
     * normally rejects such a query with its own 400 before dispatch.
     *
     * @param request the request whose raw, undecoded query string is read
     * @return the decoded query parameters, the last value of each name
     * @throws IllegalArgumentException if a name or value holds a malformed percent escape
     */
    private static Map<String, String> queryParams(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return params;
        }
        for (String pair : queryString.split(PAIR_SEPARATOR)) {
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            if (separator != -1) {
                params.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
            } else {
                params.put(decode(pair), null);
            }
        }
        return params;
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
}
