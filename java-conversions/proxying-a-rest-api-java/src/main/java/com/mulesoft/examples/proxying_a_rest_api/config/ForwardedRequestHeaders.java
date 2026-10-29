/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

import io.undertow.servlet.spec.HttpServletRequestImpl;
import org.springframework.http.HttpHeaders;

/**
 * Reads the caller's request headers for forwarding, each value as the caller sent it.
 *
 * <p>Undertow 2.3.17's parser stores a header value without its leading and trailing SP and HTAB and with every run
 * of SP and HTAB inside it, a single HTAB included, turned into one SP, so a Digest {@code username="John  Smith"}
 * or a value {@code a<HTAB>b} would reach the upstream changed. The request-framing guard records the fields of each
 * request head before that parser runs ({@link SentHeaderFields}, attached to the Undertow exchange by
 * {@link RequestFramingGuardConfig}); this class puts those values back.
 *
 * <p>It starts from every header the servlet request returns, every name of {@link HttpServletRequest#getHeaderNames()}
 * with every value of {@link HttpServletRequest#getHeaders(String)} in order, and never adds, drops or reorders one.
 * Value {@code i} of a name is replaced by the {@code i}-th sent field of that name, names matched ignoring ASCII
 * case, only when the sent value, normalised as Undertow normalises it, equals the parsed one; otherwise the parsed
 * value is kept. That keeps Undertow's value where the two do not describe the same field: several {@code Cookie}
 * lines, which Undertow joins into one value with {@code ; }, a header a request wrapper added or changed, and every
 * request without recorded fields (a request not served by Undertow, or a connection whose guard conduit another
 * Undertow option wrapped).
 */
public final class ForwardedRequestHeaders {

    private ForwardedRequestHeaders() {
    }

    /**
     * @param request the caller's request, as the servlet container hands it over, possibly wrapped
     * @return the caller's headers, names as the container returns them and values as the caller sent them where
     *         recorded, in a new mutable {@link HttpHeaders}
     */
    public static HttpHeaders from(HttpServletRequest request) {
        return from(request, sentFields(request));
    }

    /**
     * @param request the caller's request
     * @param sent    the fields of its head as sent, or {@code null} when none are recorded
     * @return the headers as {@link #from(HttpServletRequest)} describes
     */
    static HttpHeaders from(HttpServletRequest request, SentHeaderFields sent) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            List<String> sentValues = sent == null ? List.of() : sent.values(name);
            int index = 0;
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(name, index < sentValues.size() ? forwardedValue(value, sentValues.get(index)) : value);
                index++;
            }
        }
        return headers;
    }

    /**
     * @param parsed the value Undertow parsed
     * @param sent   the value of the field at the same position as the client sent it
     * @return {@code sent} when it normalises to {@code parsed}, otherwise {@code parsed}
     */
    static String forwardedValue(String parsed, String sent) {
        return sent != null && normalise(sent).equals(parsed) ? sent : parsed;
    }

    /**
     * Normalises a header value the way Undertow 2.3.17's parser stores it.
     *
     * @param value a header value
     * @return {@code value} without leading and trailing SP and HTAB, every run of them inside it one SP
     */
    static String normalise(String value) {
        StringBuilder normalised = new StringBuilder(value.length());
        boolean spacePending = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t') {
                spacePending = normalised.length() > 0;
            } else {
                if (spacePending) {
                    normalised.append(' ');
                    spacePending = false;
                }
                normalised.append(c);
            }
        }
        return normalised.toString();
    }

    /** @return the fields attached to the Undertow exchange behind {@code request}, or {@code null} */
    private static SentHeaderFields sentFields(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequestImpl undertow
                ? undertow.getExchange().getAttachment(SentHeaderFields.ATTACHMENT)
                : null;
    }
}
