/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Classifies failed NetSuite REST calls (token request, SuiteQL, record API) for {@code UpstreamCallExecutor}: the
 * NetSuite row of the approved connector failure handling, part of shared component SC-16.
 *
 * <ul>
 *   <li>A timeout or connectivity failure anywhere in the cause chain → {@link UpstreamFailureKind#UNAVAILABLE},
 *       never retried. Reactor Netty's read timeout and connect failures reach the caller wrapped in a
 *       {@code WebClientRequestException}, which {@link UpstreamFailureClassifier#isUnavailable(Throwable)} looks
 *       through.</li>
 *   <li>A {@link WebClientResponseException} with HTTP 401 → {@link UpstreamFailureKind#AUTHENTICATION}: the
 *       executor runs the re-authentication step ({@code NetsuiteTokenProvider::evict}) and retries the call once
 *       with a fresh token.</li>
 *   <li>HTTP 429 → a rate limit carrying NetSuite's {@code Retry-After} header value, or {@code null} when NetSuite
 *       sent none; never retried.</li>
 *   <li>Everything else → {@link UpstreamFailureKind#OTHER}, rethrown unchanged to the example's own exception
 *       branch: 400 (an invalid SuiteQL query), 403, 404, every 5xx, and non-HTTP failures such as the
 *       {@code IllegalStateException} raised for a missing configuration key.</li>
 * </ul>
 *
 * <p>The caller-facing message is NetSuite's own error text, taken from the response body of the REST error format
 * ({@code o:errorDetails[0].detail}, else {@code title}); request headers, and with them the bearer token, never
 * reach it.
 *
 * <p>This project holds the reference copy. {@code netsuite-data-retrieval-java} carries an identical copy in which
 * only the {@code package} line differs, so the class depends on nothing but the JDK, Spring, Jackson and its SC-9
 * siblings in this package.
 */
@Component
public class NetsuiteFailureClassifier implements UpstreamFailureClassifier {

    /** Parses error bodies only; parse failures are swallowed, so no parser setting needs to hide the input. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The REST error format's list of error details, each with a {@code detail} text and an error code. */
    private static final String ERROR_DETAILS = "o:errorDetails";

    private static final String DETAIL = "detail";

    private static final String TITLE = "title";

    /**
     * @return {@link UpstreamSystem#NETSUITE}
     */
    @Override
    public UpstreamSystem system() {
        return UpstreamSystem.NETSUITE;
    }

    /**
     * Classifies a throwable raised by a NetSuite call or by the token request it makes.
     *
     * @param failure the throwable exactly as the call raised it; {@code null} is classified as
     *                {@link UpstreamFailureKind#OTHER}
     * @return the classification; never {@code null}
     */
    @Override
    public UpstreamFailureKind classify(Throwable failure) {
        if (failure == null) {
            return UpstreamFailureKind.OTHER;
        }
        // Unavailable is decided before any HTTP status, as the UpstreamFailureClassifier contract requires: a
        // timed-out or disconnected request may already have been processed, so a 401 found in the same chain
        // must not trigger the re-authentication retry.
        if (UpstreamFailureClassifier.isUnavailable(failure)) {
            return UpstreamFailureKind.UNAVAILABLE;
        }
        for (Throwable current : causeChain(failure)) {
            if (current instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == HttpStatus.UNAUTHORIZED.value()) {
                    return UpstreamFailureKind.AUTHENTICATION;
                }
                if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                    return UpstreamFailureKind.rateLimit(retryAfter(response));
                }
            }
        }
        return UpstreamFailureKind.OTHER;
    }

    /**
     * Returns NetSuite's own text for a failure: for the first {@link WebClientResponseException} in the cause
     * chain, the non-blank {@code o:errorDetails[0].detail} of its JSON body, else the body's non-blank
     * {@code title}. A blank or non-JSON body, a body with neither text, or a chain without a response exception
     * falls back to the inherited lookup.
     *
     * @param failure the classified throwable; never {@code null}
     * @return NetSuite's error text, or the first non-blank message or class name of the chain
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    @Override
    public String upstreamMessage(Throwable failure) {
        for (Throwable current : causeChain(failure)) {
            if (current instanceof WebClientResponseException response) {
                String message = errorBodyMessage(response);
                if (message != null) {
                    return message;
                }
                break;
            }
        }
        return UpstreamFailureClassifier.super.upstreamMessage(failure);
    }

    /**
     * Reads the error text of a NetSuite REST error body, for example
     * {@code {"title":"Bad Request","status":400,"o:errorDetails":[{"detail":"Invalid search query. ...",
     * "o:errorCode":"INVALID_PARAMETER"}]}}.
     *
     * @param response the response exception; never {@code null}
     * @return the first detail text, else the title, or {@code null} when the body yields neither
     */
    private static String errorBodyMessage(WebClientResponseException response) {
        String body = response.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException | RuntimeException e) {
            // The executor asks for the message while reporting the vendor failure; a parser fault must not
            // replace that failure, so any unreadable body falls back to the inherited message.
            return null;
        }
        if (root == null) {
            return null;
        }
        String detail = nonBlankText(root.path(ERROR_DETAILS).path(0).path(DETAIL));
        return detail != null ? detail : nonBlankText(root.path(TITLE));
    }

    /**
     * @param node a JSON node, possibly missing
     * @return the node's text when it is a non-blank JSON string, otherwise {@code null}
     */
    private static String nonBlankText(JsonNode node) {
        if (node.isTextual() && !node.textValue().isBlank()) {
            return node.textValue();
        }
        return null;
    }

    /**
     * @param response a 429 response exception
     * @return NetSuite's {@code Retry-After} header value exactly as sent, or {@code null} when absent
     */
    private static String retryAfter(WebClientResponseException response) {
        HttpHeaders headers = response.getHeaders();
        return headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
    }

    /**
     * Lists a throwable and its causes in order, each once: identity decides whether a throwable was seen, so a
     * cause chain that loops back on itself ends.
     *
     * @param failure the first throwable; {@code null} yields an empty list
     * @return the chain from {@code failure} to its root cause
     */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
