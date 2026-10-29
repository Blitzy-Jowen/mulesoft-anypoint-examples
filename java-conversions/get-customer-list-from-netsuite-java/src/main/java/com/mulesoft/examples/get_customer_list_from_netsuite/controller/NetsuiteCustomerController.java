/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mulesoft.examples.get_customer_list_from_netsuite.service.NetsuiteCustomerService;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code get-customer-list-from-netsuiteFlow}
 * (get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:5,8,9,34): the listener on
 * {@code /customers} of the {@code 0.0.0.0:${http.port}} listener configuration (lines 5 and 8), whose filter,
 * NetSuite query, DataWeave script and template run in {@link NetsuiteCustomerService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method is accepted and answered alike, the request
 * body is never read, and no handler declares {@code produces}, {@code consumes} or parameter conditions: any
 * {@code Accept}, {@code Content-Type} or body the caller sends is ignored rather than rejected with 406, 415 or
 * 400. The match is exact; any other path, {@code /customers/} included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}, and so does {@code /customers} with {@code ;} segment parameters, such as
 * {@code /customers;x=1}, which {@code config/ListenerPathConfig} refuses before either handler runs.
 *
 * <p>The controller only binds input and returns output. The {@code lastName} value the expression filter tests
 * (line 9) is read from the raw URL query alone, as Mule's {@code http.query.params} held it (see
 * {@link #queryParameter}); the filter decision itself is the service's. A rendered page is answered as
 * {@link #TEXT_HTML_UTF8}, the {@code mimeType} of the flow's {@code object-to-string-transformer} (line 34), with
 * the cache, sniffing and framing headers {@link #customers} describes.
 *
 * <p>The flow declares no exception strategy, so every exception propagates: the SC-9
 * {@code UpstreamExceptionHandler} answers the approved NetSuite failure modes (502, 429, 503) and the SC-1
 * {@code GlobalExceptionHandler} every other failure with its 500.
 */
@RestController
@RequestMapping("/customers")
public class NetsuiteCustomerController {

    /**
     * Content-Type of a rendered page: {@code text/html} from {@code object-to-string-transformer}, with the
     * UTF-8 charset the page bytes are encoded in.
     */
    static final MediaType TEXT_HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    /** Header that holds a browser to the page's declared Content-Type instead of a sniffed one. */
    private static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    private static final String NO_SNIFF = "nosniff";

    /** Header that keeps the page out of every frame. */
    private static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";

    private static final String DENY_FRAMING = "DENY";

    /** The query parameter the expression filter and the customer query read. */
    private static final String LAST_NAME_PARAMETER = "lastName";

    private static final String PAIR_SEPARATOR = "&";

    private static final char NAME_VALUE_SEPARATOR = '=';

    private final NetsuiteCustomerService service;

    /**
     * Creates the controller.
     *
     * @param service runs the flow's processor chain
     */
    public NetsuiteCustomerController(NetsuiteCustomerService service) {
        this.service = service;
    }

    /**
     * Answers {@code /customers} for every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body.
     *
     * <p>When the filter rejects the request (no {@code lastName} value), the reply is the Mule listener's answer to
     * the flow's missing payload for every method: 200 with an empty body and no Content-Type. That reply is
     * committed here rather than returned as an empty {@link ResponseEntity}, because {@code FrameworkServlet}
     * completes an uncommitted reply to {@code OPTIONS} with an {@code Allow} header and to {@code TRACE} with the
     * servlet's {@code message/http} echo of the request. A page is returned before anything is written, so every
     * exception of the service still finds an uncommitted response for the project's exception handlers.
     *
     * <p>A page carries customer records (names and e-mail addresses), so its reply also sends
     * {@code Cache-Control: no-store}, which keeps clients and shared caches from storing it, and
     * {@code X-Content-Type-Options: nosniff}, which holds browsers to the declared {@code text/html}; because the
     * page is an HTML document, it also sends {@code X-Frame-Options: DENY}, which keeps it out of every frame. The
     * original declared none of these headers. They are a deliberate hardening of the page reply alone, recorded in
     * the root {@code DECISIONS.md} (section 8): the page's status, reason phrase, Content-Type and body bytes are
     * unchanged, and the filter-rejection reply, which carries no data, sends none of them. For Tier 2A they are the
     * expected difference from a captured reply of the original: the fixture replay compares {@code Content-Type}
     * and {@code Location} only, so it neither confirms nor contradicts them.
     *
     * @param request  the request, whose raw query string supplies {@code lastName}
     * @param response the response the filter-rejection reply is committed to
     * @return 200 with the customer list page as {@link #TEXT_HTML_UTF8} and the three headers above, or
     *         {@code null} once the filter-rejection reply has been committed
     * @throws IOException when committing the filter-rejection reply fails
     */
    @RequestMapping
    public ResponseEntity<byte[]> customers(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Optional<String> page = service.customerListPage(
                queryParameter(request.getQueryString(), LAST_NAME_PARAMETER));
        if (page.isEmpty()) {
            // Not ResponseEntity.ok().build(): an uncommitted empty reply gains an Allow header or a TRACE echo.
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLength(0);
            response.flushBuffer();
            return null;
        }
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header(CONTENT_TYPE_OPTIONS_HEADER, NO_SNIFF)
                .header(FRAME_OPTIONS_HEADER, DENY_FRAMING)
                .body(page.get().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers {@code OPTIONS /customers} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request  the request, whose raw query string supplies {@code lastName}
     * @param response the response the filter-rejection reply is committed to
     * @return the response of {@link #customers(HttpServletRequest, HttpServletResponse)}
     * @throws IOException when committing the filter-rejection reply fails
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> customersOptions(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return customers(request, response);
    }

    /**
     * Reads one query parameter from the raw query string as the Mule 3.8 listener built and read
     * {@code http.query.params}: {@code org.mule.module.http.internal.HttpParser#decodeQueryString} returns an
     * empty map for a blank query; otherwise it splits the query on {@code &} ({@link String#split} semantics, so
     * trailing empty pairs are dropped) and each pair on its first {@code =}, URL-decodes the name and the value of
     * every pair as UTF-8 ({@code +} as a space, {@code %xx} escapes decoded), and stores a pair without {@code =}
     * with a {@code null} value; {@code org.mule.module.http.internal.ParameterMap#get} then returns the last value
     * stored under the name.
     *
     * <p>Only the URL query is read. The servlet request parameters are not used, because they also hold the
     * fields of a form-urlencoded body, which Mule's {@code http.query.params} never did. Names are compared
     * case-sensitively, as Mule's map compared them.
     *
     * <p>Because Mule decoded every pair eagerly, a malformed percent escape anywhere in the query raises the
     * {@link IllegalArgumentException} of {@link URLDecoder}, which is left to the 500 of
     * {@code GlobalExceptionHandler}.
     *
     * @param rawQuery the raw, undecoded query string, or {@code null} when the request has none
     * @param name     the parameter name, matched against each decoded pair name
     * @return the decoded value of the last pair named {@code name}: {@code ""} for {@code name=}, {@code null} for
     *         a bare {@code name}, and {@code null} when no pair carries that name
     * @throws IllegalArgumentException if any pair name or value holds a malformed percent escape
     */
    static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String value = null;
        for (String pair : rawQuery.split(PAIR_SEPARATOR)) {
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String pairName = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            String pairValue = separator < 0
                    ? null
                    : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (pairName.equals(name)) {
                value = pairValue;
            }
        }
        return value;
    }
}
