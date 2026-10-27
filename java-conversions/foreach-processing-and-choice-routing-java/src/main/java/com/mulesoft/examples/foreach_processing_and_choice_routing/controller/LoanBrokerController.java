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
 * on 11081, {@code /}, {@code /error} and {@code /mule/**} included, so one mapping serves every path and method.
 * The reply is a 200 with a {@code Content-Length} and no {@code Content-Type}: the flow's string payload carries no
 * MIME type, and the Mule listener sent none for it (plan default; a Tier 2A fixture of the original governs). It is
 * written to the servlet response directly, so no message converter adds a Content-Type or a charset.
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
     * Runs the loan broker for any request on the main listener and writes its result.
     *
     * <p>All eight methods are listed because a mapping without methods does not match {@code OPTIONS}, which
     * Spring MVC would then answer itself with an {@code Allow} header instead of running the flow. {@code TRACE}
     * reaches this method only because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request}. A
     * {@code HEAD} reply carries the headers without the body. No {@code consumes}, {@code produces},
     * {@code params} or {@code headers} condition is declared, so no request is rejected with 406 or 415: the
     * original listener read neither the body nor the content negotiation headers.
     *
     * <p>The reply is fully written, and so committed, before this method returns, which keeps
     * {@code FrameworkServlet} from completing an {@code OPTIONS} reply with an {@code Allow} header or a
     * {@code TRACE} reply with its {@code message/http} echo.
     *
     * @param request  the request, read for its raw query string only
     * @param response the response the loan broker's result is written to, as 200 with its UTF-8 bytes
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS,
            RequestMethod.TRACE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
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
     * repeated name keeping its first value, which is what Mule's {@code ParameterMap.get} returned. Only the query
     * string is read, never {@code getParameter*}, because the servlet merges a form-urlencoded body into those and
     * Mule's {@code http.query.params} held the query alone.
     *
     * <p>Repeated names, names without {@code =} and malformed percent-encodings are Tier 2A verification points.
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
            // containsKey rather than putIfAbsent: a first occurrence without '=' holds null, and putIfAbsent
            // would let a later value replace it, while Mule's first value stays null.
            if (!params.containsKey(name)) {
                params.put(name, value);
            }
        }
        return params;
    }
}
