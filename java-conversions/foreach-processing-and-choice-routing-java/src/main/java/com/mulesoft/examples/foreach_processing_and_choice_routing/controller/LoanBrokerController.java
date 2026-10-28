/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.mulesoft.examples.foreach_processing_and_choice_routing.service.LoanBrokerService;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * HTTP trigger of flow {@code loan-broker-sync} (foreach-processing-and-choice-routing/src/main/app/
 * loanbroker-simple.xml:20-65): the {@code http:listener path="/*"} of {@code HTTP_Listener_Configuration_1}
 * (host {@code 0.0.0.0}, port 11081, no base path) and the expression component that copies the query parameters
 * {@code name}, {@code ssn}, {@code amount} and {@code term} into flow variables (lines 23-26).
 *
 * <p>The controller only binds input and writes output: the required-field choice, the credit lookup, the bank
 * selection, the {@code foreach} and the lowest quote belong to {@link LoanBrokerService}, and the flow's
 * {@code catch-exception-strategy} ({@code Error processing loan request}, lines 62-64) to the project's exception
 * handler. Nothing is caught here, so a {@link NumberFormatException} from a non-numeric {@code ssn}, {@code amount}
 * or {@code term} and every SOAP client failure reach that handler with the response still uncommitted.
 *
 * <p>The listener declares no {@code allowedMethods} and its path {@code /*} without a base path covers every path
 * on 11081, {@code /}, {@code /error} and {@code /mule/**} included, so the mapping without methods and its
 * {@code OPTIONS} counterpart serve every path and every method token, extension methods such as {@code PROPFIND}
 * included. A CORS pre-flight, an {@code OPTIONS} request carrying {@code Origin} and
 * {@code Access-Control-Request-Method}, reaches the {@code OPTIONS} mapping exactly as a plain {@code OPTIONS}
 * request does, because {@code config.PreflightDispatchConfig} dispatches it without Spring's CORS processing: it
 * runs the flow, and its reply carries no {@code Access-Control-*} or {@code Vary} header.
 *
 * <p>The reply is a 200 with a {@code Content-Length} and no {@code Content-Type}: the flow's string payload carries
 * no MIME type, and the Mule listener sent none for it (plan default; a Tier 2A fixture of the original governs). It
 * is written to the servlet response directly, so no message converter adds a Content-Type or a charset.
 */
@Controller
public class LoanBrokerController {

    private static final String PAIR_SEPARATOR = "&";

    private static final char NAME_VALUE_SEPARATOR = '=';

    private final LoanBrokerService loanBrokerService;

    public LoanBrokerController(LoanBrokerService loanBrokerService) {
        this.loanBrokerService = loanBrokerService;
    }

    /**
     * Runs the loan broker for every method except {@code OPTIONS} on the main listener and writes its result.
     *
     * <p>The mapping declares no method, so every method token runs the flow, an extension method such as
     * {@code PROPFIND} included, as the listener without {@code allowedMethods} accepted it. {@code TRACE} reaches
     * this method only because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request}. A
     * {@code HEAD} reply carries the headers without the body. No {@code consumes}, {@code produces},
     * {@code params} or {@code headers} condition is declared, so no request is rejected with 406 or 415: the
     * original listener read neither the body nor the content negotiation headers.
     *
     * @param request  the request, read for its raw query string only
     * @param response the response the loan broker's result is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/**")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        loanBrokerResponse(request, response);
    }

    /**
     * Runs the loan broker for {@code OPTIONS}, a CORS pre-flight included, like every other method. A mapping
     * without methods deliberately does not match {@code OPTIONS}, which Spring MVC would otherwise answer itself
     * with an {@code Allow} header and no body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the request, read for its raw query string only
     * @param response the response the loan broker's result is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/**", method = RequestMethod.OPTIONS)
    public void handleOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        loanBrokerResponse(request, response);
    }

    /**
     * Binds the four query parameters, runs the loan broker and writes its result as 200 with a
     * {@code Content-Length} and no {@code Content-Type}. The result is obtained before the response is touched, so
     * a failure still finds an uncommitted response for the exception handler.
     *
     * <p>The reply is fully written, and so committed, before this method returns, which keeps
     * {@code FrameworkServlet} from completing an {@code OPTIONS} reply with an {@code Allow} header or a
     * {@code TRACE} reply with its {@code message/http} echo.
     *
     * @param request  the request, read for its raw query string only
     * @param response the response the loan broker's result is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    private void loanBrokerResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> queryParams = queryParams(request.getQueryString());

        String result = loanBrokerService.quote(queryParams.get("name"), queryParams.get("ssn"),
                queryParams.get("amount"), queryParams.get("term"));

        // quote never returns null (the bank list is never empty); the guard only keeps a null from failing here.
        byte[] body = result == null ? new byte[0] : result.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /**
     * Decodes the raw query string as the Mule 3.8 listener built {@code http.query.params}
     * ({@code HttpParser.decodeQueryString}): pairs separated by {@code &}, each split at its first {@code =},
     * name and value URL-decoded as UTF-8, a pair without {@code =} giving its name a {@code null} value, and a
     * repeated name yielding its last value, as Mule's {@code ParameterMap.get} returned it, so a later pair without
     * {@code =} leaves that name {@code null}. Only the query string is read, never {@code getParameter*}, because
     * the servlet merges a form-urlencoded body into those and Mule's {@code http.query.params} held the query alone.
     *
     * <p>Names without {@code =} and malformed percent-encodings are Tier 2A verification points.
     * Undertow already answers a request target with a malformed percent-encoding with a bare 400 before any
     * servlet runs; one that reaches this method fails {@link URLDecoder#decode(String, java.nio.charset.Charset)}
     * with an {@link IllegalArgumentException}, which propagates to the exception handler unhandled.
     *
     * @param queryString the raw query string, {@code null} when the request has none
     * @return the decoded parameters; empty for a {@code null} or empty query string
     */
    private static Map<String, String> queryParams(String queryString) {
        Map<String, String> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }
        for (String pair : queryString.split(PAIR_SEPARATOR)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String name;
            String value;
            if (separator < 0) {
                name = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = null;
            } else {
                name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
            params.put(name, value);
        }
        return params;
    }
}
