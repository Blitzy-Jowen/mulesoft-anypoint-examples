/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;

/**
 * HTTP front of the WS-Security clients: the {@code http:listener path="client"} of flow {@code SecurityClients}
 * (soap-webservice-security/src/main/app/service-clients.xml:6-40) on {@code HTTP_Listener_Configuration}, host
 * {@code 0.0.0.0}, port 63080, no base path. The original README drives it as
 * {@code localhost:63080/client?clientType=unsecure&name=John}.
 *
 * <p>The controller only binds the two query parameters (lines 10-11) and writes the {@code text/plain} reply
 * (line 35). The {@code clientType} choice and its {@code Client type is not supported} default (lines 12-34) belong
 * to {@link SecureGreeterClient#invoke(String, String)}, and the flow's {@code catch-exception-strategy}
 * ({@code There has been an Error processing the request}, lines 36-39) to {@code GreeterClientExceptionHandler}.
 * Nothing is caught here, so every client failure reaches that handler with the response still uncommitted.
 *
 * <p>The listener is opened by {@code config/ListenerPorts} as the additional listener {@code client}, and
 * {@code config/PortRoutingFilter} keeps {@code /client} off the SOAP listener, where it answers the no-listener 404.
 *
 * <p>The listener declares no {@code allowedMethods}, so the mapping without methods and its {@code OPTIONS}
 * counterpart run the flow for every method token. A CORS pre-flight, an {@code OPTIONS} request carrying
 * {@code Origin} and {@code Access-Control-Request-Method}, reaches the {@code OPTIONS} mapping exactly as a plain
 * {@code OPTIONS} request does, because {@code config.PreflightDispatchConfig} dispatches it without Spring's CORS
 * processing: it runs the flow, and its response carries no {@code Access-Control-*} or {@code Vary} header.
 */
@RestController
public class GreeterClientController {

    private static final String PAIR_SEPARATOR = "&";

    private static final char NAME_VALUE_SEPARATOR = '=';

    private static final String NAME_PARAMETER = "name";

    private static final String CLIENT_TYPE_PARAMETER = "clientType";

    private final SecureGreeterClient secureGreeterClient;

    public GreeterClientController(SecureGreeterClient secureGreeterClient) {
        this.secureGreeterClient = secureGreeterClient;
    }

    /**
     * Runs the {@code SecurityClients} flow for every method except {@code OPTIONS} on {@code /client} and answers
     * 200 {@code text/plain} with the greeting, or with {@code Client type is not supported} for an unknown or
     * missing {@code clientType}.
     *
     * <p>The listener declares no {@code allowedMethods}, so the mapping declares no method and every method token
     * runs the flow, an extension method such as {@code PROPFIND} included. {@code TRACE} reaches this method
     * because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request}; a {@code HEAD} reply carries
     * the headers without the body. No {@code consumes} or {@code produces} condition is declared, and the preset
     * Content-Type makes Spring write the body without consulting {@code Accept}, so no request is rejected with 406
     * or 415: the original listener read neither the body nor the negotiation headers.
     *
     * @param request the request, read for its raw query string only
     * @return 200 {@code text/plain} carrying the result of the selected client
     */
    @RequestMapping("/client")
    public ResponseEntity<String> client(HttpServletRequest request) {
        return clientResponse(request);
    }

    /**
     * Runs the {@code SecurityClients} flow for {@code OPTIONS}, a CORS pre-flight included, like every other
     * method. A mapping without methods deliberately does not match {@code OPTIONS}, which Spring MVC would otherwise
     * answer itself with an {@code Allow} header and no body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request the request, read for its raw query string only
     * @return the response of {@link #client(HttpServletRequest)}
     */
    @RequestMapping(path = "/client", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> clientOptions(HttpServletRequest request) {
        return clientResponse(request);
    }

    /**
     * Binds query {@code name} and {@code clientType}, runs the selected client and wraps its result.
     *
     * @param request the request, read for its raw query string only
     * @return 200 {@code text/plain} carrying the result of the selected client
     */
    private ResponseEntity<String> clientResponse(HttpServletRequest request) {
        String rawQuery = request.getQueryString();
        String name = queryParam(rawQuery, NAME_PARAMETER);                    // service-clients.xml:10
        String clientType = queryParam(rawQuery, CLIENT_TYPE_PARAMETER);       // service-clients.xml:11
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)                             // service-clients.xml:35
                .body(secureGreeterClient.invoke(clientType, name));
    }

    /**
     * Reads one entry of {@code http.query.params} as the Mule 3.8 listener built it
     * ({@code HttpParser.decodeQueryString}) and as {@code ParameterMap.get} returned it: pairs separated by
     * {@code &}, each split at its first {@code =}, name and value URL-decoded as UTF-8 ({@code +} becomes a space),
     * a pair without {@code =} giving its name a {@code null} value, and a repeated name yielding its last value.
     * Names compare exactly and case-sensitively after decoding.
     *
     * <p>Only the raw query string is read, never {@code getParameter*} or {@code @RequestParam}: the servlet merges
     * a form-urlencoded body into those and joins repeated values, while Mule's {@code http.query.params} held the
     * query alone. Every pair is decoded, as Mule decoded the whole query, so a malformed percent-encoding anywhere
     * in it fails {@link URLDecoder#decode(String, java.nio.charset.Charset)} with an
     * {@link IllegalArgumentException}. Undertow answers such a query with a bare 400 before dispatch, so the
     * exception arises only outside Undertow, for example under MockMvc, where it reaches
     * {@code GreeterClientExceptionHandler} like every other failure of this controller (a Tier 2A verification
     * point).
     *
     * @param rawQuery the raw query string, {@code null} when the request has none
     * @param key      the decoded parameter name
     * @return the decoded value of the last pair named {@code key}; {@code null} when the query is {@code null} or
     *         empty, when no pair is named {@code key}, or when that pair has no {@code =}
     */
    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        String value = null;
        for (String pair : rawQuery.split(PAIR_SEPARATOR)) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String pairName;
            String pairValue;
            if (separator < 0) {
                pairName = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                pairValue = null;
            } else {
                pairName = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                pairValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
            if (key.equals(pairName)) {
                value = pairValue;
            }
        }
        return value;
    }
}
