/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.mapper;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Copies HTTP headers across the proxy boundary (shared component SC-13), replacing the Mule
 * {@code copy-headers} sub-flow of the original proxy examples ({@code proxying-a-rest-api.xml:22-30},
 * and the identical sub-flow of {@code soap-api-proxy.xml}).
 *
 * <p>That sub-flow copied every inbound property to the outbound message and then removed {@code Host},
 * {@code Content-Length}, {@code Connection}, {@code Transfer-Encoding} and every name starting with
 * {@code http.} (Mule's synthetic HTTP properties) or {@code MULE_}. Mule 3 property names are
 * case-insensitive, so every match here ignores case. Every other header passes unchanged in both
 * directions, under its original name and with all its values in their original order. The caller's
 * {@code Authorization} header in particular reaches the upstream exactly as received: it is the only
 * identity the proxy carries, and nothing here drops, rewrites or adds it.
 *
 * <p>The owning proxy service ({@code RestProxyService} in the REST proxy, {@code SoapProxyService} in the
 * SOAP proxy) calls it in both directions, as the original flow called the sub-flow before and after its
 * outbound request: {@link #toUpstream} on the caller's request headers before the upstream call, and
 * {@link #toCaller} on the upstream response headers. Method, path, query and upstream address are handled
 * by that service and its client, never here.
 *
 * <p>The class is stateless and thread-safe. Copied into {@code proxying-a-soap-api-java} with only the
 * {@code package} line changed; do not add project-specific logic.
 */
@Component
public class ProxyHeaderMapper {

    /** Header names removed in both directions, matched ignoring case. */
    public static final List<String> REMOVED_HEADERS =
            List.of("Host", "Content-Length", "Connection", "Transfer-Encoding");

    /** Name prefixes removed in both directions, matched ignoring case at the start of the name. */
    public static final List<String> REMOVED_PREFIXES = List.of("http.", "MULE_");

    /**
     * Tells whether a header is dropped when headers are copied.
     *
     * <p>Matching ignores case without consulting the default locale, so the result is the same under
     * every locale.
     *
     * @param name the header name; {@code null} is never copied
     * @return {@code true} when {@code name} is {@code null}, equals an entry of {@link #REMOVED_HEADERS},
     *         or starts with an entry of {@link #REMOVED_PREFIXES}, all ignoring case; {@code false}
     *         otherwise (for example {@code Authorization}, {@code httpfoo} or {@code X-Mule-Id})
     */
    public boolean isRemoved(String name) {
        if (name == null) {
            return true;
        }
        for (String removed : REMOVED_HEADERS) {
            if (removed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        for (String prefix : REMOVED_PREFIXES) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the headers to send upstream for a caller's request.
     *
     * @param inbound the caller's request headers; {@code null} yields empty headers. The argument is
     *                never modified.
     * @return new, writable headers holding every inbound header except those {@link #isRemoved} rejects
     *         (matched ignoring case), with names, values and order unchanged
     */
    public HttpHeaders toUpstream(HttpHeaders inbound) {
        return copy(inbound);
    }

    /**
     * Returns the headers to send back to the caller for an upstream response.
     *
     * @param upstreamResponse the upstream response headers; {@code null} yields empty headers. The
     *                         argument is never modified.
     * @return new, writable headers holding every upstream header except those {@link #isRemoved} rejects
     *         (matched ignoring case), with names, values and order unchanged
     */
    public HttpHeaders toCaller(HttpHeaders upstreamResponse) {
        return copy(upstreamResponse);
    }

    /**
     * Copies {@code source} into new headers, skipping removed names and names without a value list. A
     * header with an empty value list is kept, so it stays present. {@link HttpHeaders#addAll} copies the
     * values into the result's own list, so the source's lists are never shared or modified.
     */
    private HttpHeaders copy(HttpHeaders source) {
        HttpHeaders result = new HttpHeaders();
        if (source != null) {
            source.forEach((name, values) -> {
                if (values != null && !isRemoved(name)) {
                    result.addAll(name, values);
                }
            });
        }
        return result;
    }
}
