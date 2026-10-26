/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import com.mulesoft.examples.querying_a_mysql_database.service.EmployeeQueryService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code database-to-jsonFlow}: replaces the {@code http:listener-config} and the
 * {@code http:listener} on {@code /} (querying-a-mysql-database/src/main/app/database-to-json.xml:4,6). The
 * listener's host and port live in {@code application.yml}; the query, its MEL rendering and the JSON
 * serialization belong to {@link EmployeeQueryService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method runs the flow and is answered alike, for
 * example {@code GET /?lastname=Puckett} with 200 {@code application/json}
 * {@code [{"first_name":"Chava"},{"first_name":"Quentin"}]}. The match is exact: any other path, a trailing
 * slash form included, falls through to the no-listener 404 of {@code GlobalExceptionHandler}, which also
 * answers every failure, the flow having no exception strategy. No handler declares {@code produces},
 * {@code consumes}, parameters or a request body, so the caller's {@code Accept}, {@code Content-Type} and
 * body are ignored rather than rejected.
 */
@RestController
public class EmployeeQueryController {

    private static final String LASTNAME_PARAMETER = "lastname";

    private final EmployeeQueryService employeeQueryService;

    public EmployeeQueryController(EmployeeQueryService employeeQueryService) {
        this.employeeQueryService = employeeQueryService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers
     * without the body.
     *
     * @param request the inbound request, whose URL query string alone supplies {@code lastname}
     * @return 200 {@code application/json} with the matching first names, {@code []} when none match
     */
    @RequestMapping("/")
    public ResponseEntity<String> query(HttpServletRequest request) {
        String lastname = lastnameFrom(request.getQueryString());
        // Preset Content-Type: Spring writes the string as is, without consulting Accept, so never a 406.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(employeeQueryService.findFirstNamesAsJson(lastname));
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body;
     * the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request the inbound request
     * @return the response {@link #query(HttpServletRequest)} gives
     */
    @RequestMapping(value = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> options(HttpServletRequest request) {
        return query(request);
    }

    /**
     * Reads {@code lastname} as Mule's {@code http.query.params} held it. The raw query string is parsed here
     * rather than through the servlet request parameters, which also merge a form-encoded request body that
     * the original query never read.
     *
     * <p>Pairs are separated by {@code &}, empty segments skipped; each splits at its first {@code =}, and name
     * and value are URL-decoded as UTF-8 ({@code +} is a space). The name matches case-sensitively. A pair
     * without {@code =} yields {@code null}, {@code lastname=} yields the empty string, and when the name occurs
     * more than once the last occurrence wins, as Mule's {@code ParameterMap.get} returned it. A malformed
     * escape raises {@link IllegalArgumentException} from {@link URLDecoder}, deliberately not caught: the
     * flow has no exception strategy, so the global handler's 500 answers it.
     *
     * @param rawQuery the undecoded query string, {@code null} when the request URL has none
     * @return the decoded value of the last {@code lastname} pair, or {@code null} when there is none
     */
    private static String lastnameFrom(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        String lastname = null;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            String value = separator < 0
                    ? null
                    : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (LASTNAME_PARAMETER.equals(name)) {
                lastname = value;
            }
        }
        return lastname;
    }
}
