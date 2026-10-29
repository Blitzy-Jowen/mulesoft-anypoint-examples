/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mulesoft.examples.mule_expression_language_basics.service.GreetingService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
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
 * the same file; a CORS pre-flight, an {@code OPTIONS} request carrying {@code Origin} and
 * {@code Access-Control-Request-Method}, does the same, because {@code config/PreflightDispatchConfig} dispatches it
 * as a plain {@code OPTIONS} request and no CORS policy applies. {@code TRACE} does too, because
 * {@code application.yml} sets {@code spring.mvc.dispatch-trace-request: true}; the complete, committed reply keeps
 * the servlet's {@code message/http} echo of the request off the wire. A {@code HEAD} reply carries the headers
 * without the body.
 * Paths match exactly: {@code /greet1/}, {@code /greet1.json}, {@code /} or {@code /greet7} fall through to the
 * no-listener 404 of {@code GlobalExceptionHandler}.
 *
 * <p>Every reply is a 200 carrying the reply's UTF-8 bytes with their {@code Content-Length} and no
 * {@code Content-Type}: each flow ends in a {@code set-payload} that declares no MIME type, and the Mule listener sent
 * no Content-Type for such a payload. The reply is written to the servlet response directly, so no message converter
 * adds one and {@code Accept} is never consulted. The absent header is a Tier 2A verification point of the
 * conversion plan: should a captured fixture of the original show a value, such as {@code application/csv} after
 * {@code greet4}'s DataWeave step, only {@link #ok(HttpServletResponse, String)} changes.
 */
@RestController
public class GreetingController {

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
     * @param request  the request, whose raw query string supplies {@code username}
     * @param response the response 200 {@code Hello <username>} is written to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet1")
    public void greet1(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ok(response, greetingService.greet1(queryParams(request).get(USERNAME)));
    }

    /**
     * {@code greetingFlow1} for {@code OPTIONS}. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the request, whose raw query string supplies {@code username}
     * @param response the response the reply of {@link #greet1(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet1", method = RequestMethod.OPTIONS)
    public void greet1Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet1(request, response);
    }

    /**
     * {@code greetingFlow2} ({@code greeting.xml:15-26}) for every method except {@code OPTIONS}.
     *
     * @param request  the request, whose raw query string supplies {@code username}
     * @param response the response 200 {@code No username provided} for an empty username, otherwise
     *                 {@code Hello <username>}, is written to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet2")
    public void greet2(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ok(response, greetingService.greet2(queryParams(request).get(USERNAME)));
    }

    /**
     * {@code greetingFlow2} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request  the request, whose raw query string supplies {@code username}
     * @param response the response the reply of {@link #greet2(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet2", method = RequestMethod.OPTIONS)
    public void greet2Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet2(request, response);
    }

    /**
     * {@code docs-greetingFlow3} ({@code greeting.xml:30-37}) for every method except {@code OPTIONS}: the service
     * writes {@code <username>, <age>, <age > 18>} to a new file.
     *
     * @param request  the request, whose raw query string supplies {@code username} and {@code age}
     * @param response the response 200 {@code Hello <username>} is written to
     * @throws Exception whatever the service raises, such as the failed comparison of a non-numeric {@code age},
     *                   left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet3")
    public void greet3(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Map<String, String> query = queryParams(request);
        ok(response, greetingService.greet3(query.get(USERNAME), query.get(AGE)));
    }

    /**
     * {@code docs-greetingFlow3} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request  the request, whose raw query string supplies {@code username} and {@code age}
     * @param response the response the reply of {@link #greet3(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet3", method = RequestMethod.OPTIONS)
    public void greet3Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet3(request, response);
    }

    /**
     * {@code docs-greetingFlow4} ({@code greeting.xml:41-60}) for every method except {@code OPTIONS}: the service
     * writes the header-less DataWeave CSV line {@code <username>,<age>,<age_of>} to a new file.
     *
     * @param request  the request, whose raw query string supplies {@code username} and {@code age}
     * @param response the response 200 {@code Hello <username>} is written to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet4")
    public void greet4(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Map<String, String> query = queryParams(request);
        ok(response, greetingService.greet4(query.get(USERNAME), query.get(AGE)));
    }

    /**
     * {@code docs-greetingFlow4} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request  the request, whose raw query string supplies {@code username} and {@code age}
     * @param response the response the reply of {@link #greet4(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet4", method = RequestMethod.OPTIONS)
    public void greet4Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet4(request, response);
    }

    /**
     * {@code docs-greetingFlow5} ({@code greeting.xml:64-71}) for every method except {@code OPTIONS}: the service
     * reads {@code /user/username} and {@code /user/age} from the XML body and writes them to a new file.
     *
     * <p>The body is bound by {@link #listenerBody(HttpServletRequest)}, alike for every method: an empty or
     * form-urlencoded body as {@code null}, which the service rejects with its 500, and any other body as its bytes
     * whatever its {@code Content-Type}, absent included; the service detects the XML encoding itself.
     *
     * @param request  the request, whose body is the XML document
     * @param response the response 200 {@code Hello <username>} is written to
     * @throws Exception whatever the service raises, such as the parse failure of a malformed document, left to
     *                   {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet5")
    public void greet5(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ok(response, greetingService.greet5(listenerBody(request)));
    }

    /**
     * {@code docs-greetingFlow5} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request  the request, whose body is the XML document
     * @param response the response the reply of {@link #greet5(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet5", method = RequestMethod.OPTIONS)
    public void greet5Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet5(request, response);
    }

    /**
     * {@code greetingFlow6} ({@code greeting.xml:75-83}) for every method except {@code OPTIONS}: the service parses
     * the JSON body to an object, reads {@code username} and {@code age} and writes them to a new file.
     *
     * <p>The body is bound by {@link #listenerBody(HttpServletRequest)}, alike for every method: an empty or
     * form-urlencoded body as {@code null}, which the service rejects with its 500, and any other body as its bytes
     * whatever its {@code Content-Type}, absent included.
     *
     * @param request  the request, whose body is the JSON document
     * @param response the response 200 {@code Hello <username>} is written to
     * @throws Exception whatever the service raises, such as the parse failure of malformed JSON, left to
     *                   {@code GlobalExceptionHandler}
     */
    @RequestMapping("/greet6")
    public void greet6(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ok(response, greetingService.greet6(listenerBody(request)));
    }

    /**
     * {@code greetingFlow6} for {@code OPTIONS}, which the method-less mapping does not match.
     *
     * @param request  the request, whose body is the JSON document
     * @param response the response the reply of {@link #greet6(HttpServletRequest, HttpServletResponse)} is written
     *                 to
     * @throws Exception whatever the service raises, left to {@code GlobalExceptionHandler}
     */
    @RequestMapping(path = "/greet6", method = RequestMethod.OPTIONS)
    public void greet6Options(HttpServletRequest request, HttpServletResponse response) throws Exception {
        greet6(request, response);
    }

    /**
     * Writes the 200 every listener answered a flow's reply with: the reply's UTF-8 bytes with their
     * {@code Content-Length} and no {@code Content-Type}. Every caller passes the reply the service already
     * returned, so nothing is written before the flow has completed and an exception still finds an uncommitted
     * response for the 500 of {@code GlobalExceptionHandler}. The flushed reply is complete and committed, so
     * {@code FrameworkServlet} adds neither the {@code message/http} echo of a {@code TRACE} nor the {@code Allow}
     * header of an {@code OPTIONS} request.
     *
     * @param response the response the reply is written to
     * @param reply    the flow's reply
     * @throws IOException when writing the reply fails
     */
    private static void ok(HttpServletResponse response, String reply) throws IOException {
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Binds the body {@code greet5} and {@code greet6} read as the payload the Mule 3.8 listener handed their flows,
     * the same way for every method:
     * <ul>
     *   <li>an empty body: {@code null}, the listener's {@code NullPayload};</li>
     *   <li>a {@code Content-Type} of {@code application/x-www-form-urlencoded}, whatever its parameters:
     *       {@code null}. The listener turned such a body into its map of form parameters for every method, which
     *       {@code xpath()} cannot evaluate and {@code json-to-object-transformer} cannot read, so the flow failed
     *       with a 500 before writing a file; the service rejects {@code null} the same way. The exact text of the
     *       Mule failure is a Tier 2A verification point;</li>
     *   <li>any other body, whatever its {@code Content-Type}, absent or unparseable included: its bytes.</li>
     * </ul>
     *
     * <p>The body is read from the servlet stream, in full for every request, rather than through
     * {@code @RequestBody}, which rebuilds a form-urlencoded {@code POST} body from the container's parsed parameters
     * but hands the same body sent with {@code PUT} through unchanged, so the outcome would depend on the method.
     *
     * @param request the request whose {@code Content-Type} header and body are read
     * @return the body bytes, or {@code null} when the body is empty or form-urlencoded
     * @throws IOException when reading the request body fails
     */
    private static byte[] listenerBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            return null;
        }
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        boolean formUrlEncoded;
        try {
            formUrlEncoded = contentType != null
                    && MediaType.APPLICATION_FORM_URLENCODED
                            .equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            // An unparseable Content-Type declares no form, so the body reaches the flow as it came.
            formUrlEncoded = false;
        }
        return formUrlEncoded ? null : body;
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
