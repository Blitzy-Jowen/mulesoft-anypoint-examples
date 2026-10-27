/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the Mule 3.8 OAuth module generated from {@code oauth2:authorization-code-grant-type} in
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}; no flow
 * declares them:
 * <ul>
 *   <li>{@code /authorization}, the local authorization URL of {@code localAuthorizationUrl} (line 7), answered by
 *       {@link BoxOAuthService#authorization(String)} from the {@code onCompleteRedirectTo} query parameter;</li>
 *   <li>{@code /redirectUrl}, the OAuth redirect URL of {@code redirectionUrl} (line 6), answered by
 *       {@link BoxOAuthService#redirectCallback(String, String, String)} from the {@code code} and {@code state}
 *       query parameters and the request URI.</li>
 * </ul>
 *
 * <p>Like every Mule listener without {@code allowedMethods}, both paths accept every HTTP method. The matches are
 * exact. Both are served only by the HTTPS listener behind those URLs: {@code ListenerPorts} opens it, requiring a
 * client certificate as the original {@code TLS_Context} did, and {@code PortRoutingFilter} answers the no-listener
 * 404 for these paths on any other port, so this class checks neither port, scheme nor certificate.
 *
 * <p>The controller only binds the query and writes the {@link BoxOAuthService.Reply} as it is: status, the
 * {@code Location} and {@code Content-Type} headers only when the reply carries them, {@code Content-Length} and the
 * body bytes. The reply is written to the servlet response directly, so no message converter adds a
 * {@code Content-Type}. Every exception, a malformed {@code %} escape in the query included, propagates to
 * {@code GlobalExceptionHandler}; since the service returns the reply before the response is touched, a failure
 * still finds an uncommitted response for its 500.
 */
@RestController
public class BoxAuthorizationController {

    private final BoxOAuthService service;

    /**
     * @param service the grant's behaviour: authorization URL, state, token exchange and storage, markers and texts
     */
    public BoxAuthorizationController(BoxOAuthService service) {
        this.service = service;
    }

    /**
     * Answers {@code /authorization} for every method except {@code OPTIONS}: the redirect to Box's authorization
     * endpoint whose state carries {@code onCompleteRedirectTo}.
     *
     * @param request  the request whose query is read
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/authorization")
    public void authorization(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String onCompleteRedirectTo = queryParam(request.getQueryString(), "onCompleteRedirectTo");
        writeReply(response, service.authorization(onCompleteRedirectTo));
    }

    /**
     * Answers {@code OPTIONS /authorization} like every other method. A mapping without methods deliberately does
     * not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header; the
     * Mule listener answered {@code OPTIONS} like any other method.
     *
     * @param request  the request whose query is read
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/authorization", method = RequestMethod.OPTIONS)
    public void authorizationOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        authorization(request, response);
    }

    /**
     * Answers {@code /redirectUrl} for every method except {@code OPTIONS}: the OAuth provider's callback, which the
     * service turns into a redirect to {@code onCompleteRedirectTo} or a platform text.
     *
     * @param request  the request whose query and URI are read
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping("/redirectUrl")
    public void redirectUrl(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String query = request.getQueryString();
        String code = queryParam(query, "code");
        String state = queryParam(query, "state");
        // The raw path and raw query, as received: the service quotes them in its no-code text.
        String requestUri = request.getRequestURI() + (query != null ? "?" + query : "");
        writeReply(response, service.redirectCallback(code, state, requestUri));
    }

    /**
     * Answers {@code OPTIONS /redirectUrl} like every other method, for the reason given at
     * {@link #authorizationOptions(HttpServletRequest, HttpServletResponse)}.
     *
     * @param request  the request whose query and URI are read
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(value = "/redirectUrl", method = RequestMethod.OPTIONS)
    public void redirectUrlOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        redirectUrl(request, response);
    }

    /**
     * Reads one query parameter the way Mule 3.8 filled {@code http.query.params}: from the raw query string only,
     * never from a form body, which is why the servlet parameter API is not used.
     *
     * <ul>
     *   <li>Pairs are separated by {@code &}; empty pairs are skipped.</li>
     *   <li>Names and values are URL-decoded as UTF-8, {@code +} as a space.</li>
     *   <li>A pair without {@code =} has a {@code null} value, and {@code name=} the empty string.</li>
     *   <li>A repeated name yields its last value, as Mule's {@code ParameterMap.get} did, even when that last pair
     *       has no {@code =}.</li>
     * </ul>
     *
     * @param rawQuery the undecoded query string without {@code ?}, or {@code null}
     * @param name     the decoded parameter name
     * @return the decoded value of the last pair named {@code name}; {@code null} when there is none or it has no
     *         {@code =}
     * @throws IllegalArgumentException when any name or value holds a malformed {@code %} escape
     */
    static String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        String value = null;
        int start = 0;
        while (start <= rawQuery.length()) {
            int end = rawQuery.indexOf('&', start);
            if (end < 0) {
                end = rawQuery.length();
            }
            if (end > start) {
                String pair = rawQuery.substring(start, end);
                int eq = pair.indexOf('=');
                // Every pair is decoded, matching or not: Mule decoded the whole query before the flow ran, so a
                // malformed escape anywhere in it failed the request.
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String decoded = eq < 0 ? null : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    value = decoded;
                }
            }
            start = end + 1;
        }
        return value;
    }

    /**
     * Writes {@code reply} unchanged. {@code Location} is set as a plain header rather than through
     * {@code sendRedirect}, which would rewrite the value and add a body of its own. {@link BoxOAuthService.Reply}
     * never holds a {@code null} body, so the body is written as given.
     */
    private static void writeReply(HttpServletResponse response, BoxOAuthService.Reply reply) throws IOException {
        response.setStatus(reply.status());
        if (reply.location() != null) {
            response.setHeader("Location", reply.location());
        }
        if (reply.contentType() != null) {
            response.setContentType(reply.contentType());
        }
        byte[] body = reply.body();
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
