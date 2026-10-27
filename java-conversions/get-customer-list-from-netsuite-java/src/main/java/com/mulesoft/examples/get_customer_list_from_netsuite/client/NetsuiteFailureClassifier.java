/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
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
import org.springframework.web.reactive.function.client.WebClientRequestException;
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
 * <p>The caller-facing message is NetSuite's own error text, taken from the response body: the REST error format's
 * {@code o:errorDetails[0].detail}, else its {@code title}, else the OAuth 2.0 token error format's
 * {@code error_description}, else its {@code error}. A response without such text is described by its status, and
 * a failure without a response neutrally, as {@link #upstreamMessage(Throwable)} lists. No {@code WebClient}
 * exception text reaches the caller, because it carries the request URL or the remote address; nor do request
 * headers, and with them the bearer token.
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

    /** The OAuth 2.0 token error format's human-readable text (RFC 6749, section 5.2), optional. */
    private static final String ERROR_DESCRIPTION = "error_description";

    /** The OAuth 2.0 token error format's error code (RFC 6749, section 5.2), for example {@code invalid_client}. */
    private static final String ERROR = "error";

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
     * Returns NetSuite's own text for a failure, decided by the first {@code WebClient} exception in the cause chain:
     * <ul>
     *   <li>a {@link WebClientResponseException}: the non-blank {@code o:errorDetails[0].detail} of its JSON body,
     *       else the body's non-blank {@code title}, else its non-blank {@code error_description}, else its non-blank
     *       {@code error}; a blank or non-JSON body, or a body with none of these, is described by its status as
     *       {@code "NetSuite answered HTTP <status> <reason phrase>"}, the reason phrase omitted for a status
     *       without a standard one;</li>
     *   <li>a {@link WebClientRequestException}, a request that got no response:
     *       {@code "NetSuite did not respond in time"} when the chain holds a timeout,
     *       {@code "NetSuite is unreachable"} for any other failure
     *       {@link UpstreamFailureClassifier#isUnavailable(Throwable)} accepts, and
     *       {@code "NetSuite call failed"} otherwise.</li>
     * </ul>
     * The exceptions' own messages are never returned, because they name the request URL, as in
     * {@code "401 Unauthorized from POST https://..."}, or the remote address that {@code WebClient} copies from the
     * transport failure. A chain without a {@code WebClient} exception, such as the {@code IllegalStateException} of
     * a missing setting, gets the inherited lookup.
     *
     * @param failure the classified throwable; never {@code null}
     * @return NetSuite's error text, the status or neutral description above, or, without a {@code WebClient}
     *         exception in the chain, the inherited message; never {@code null} or blank
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    @Override
    public String upstreamMessage(Throwable failure) {
        List<Throwable> chain = causeChain(failure);
        for (Throwable current : chain) {
            if (current instanceof WebClientResponseException response) {
                String message = errorBodyMessage(response);
                return message != null ? message : statusDescription(response);
            }
            if (current instanceof WebClientRequestException) {
                return transportDescription(failure, chain);
            }
        }
        return UpstreamFailureClassifier.super.upstreamMessage(failure);
    }

    /**
     * Reads the error text of a NetSuite error body: the REST error format, for example
     * {@code {"title":"Bad Request","status":400,"o:errorDetails":[{"detail":"Invalid search query. ...",
     * "o:errorCode":"INVALID_PARAMETER"}]}}, or the OAuth 2.0 token endpoint's, for example
     * {@code {"error":"invalid_client","error_description":"..."}}.
     *
     * @param response the response exception; never {@code null}
     * @return the first detail text, else the title, else the error description, else the error code, or
     *         {@code null} when the body yields none of them
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
            // replace that failure, so any unreadable body is described by its status instead.
            return null;
        }
        if (root == null) {
            return null;
        }
        for (JsonNode candidate : List.of(root.path(ERROR_DETAILS).path(0).path(DETAIL), root.path(TITLE),
                root.path(ERROR_DESCRIPTION), root.path(ERROR))) {
            String text = nonBlankText(candidate);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /**
     * Describes a response whose body carries no error text by its status alone.
     *
     * @param response the response exception; never {@code null}
     * @return for example {@code "NetSuite answered HTTP 401 Unauthorized"}, or {@code "NetSuite answered HTTP 599"}
     *         for a status without a standard reason phrase
     */
    private String statusDescription(WebClientResponseException response) {
        int status = response.getStatusCode().value();
        String description = system().displayName() + " answered HTTP " + status;
        HttpStatus known = HttpStatus.resolve(status);
        return known == null ? description : description + " " + known.getReasonPhrase();
    }

    /**
     * Describes a request that got no response, naming what went wrong but neither the address nor any type.
     *
     * @param failure the classified throwable; never {@code null}
     * @param chain   {@code failure} and its causes, each once
     * @return the timeout, unreachable or generic description of {@link #upstreamMessage(Throwable)}
     */
    private String transportDescription(Throwable failure, List<Throwable> chain) {
        String name = system().displayName();
        if (chain.stream().anyMatch(NetsuiteFailureClassifier::isTimeout)) {
            return name + " did not respond in time";
        }
        if (UpstreamFailureClassifier.isUnavailable(failure)) {
            return name + " is unreachable";
        }
        return name + " call failed";
    }

    /**
     * Tests one throwable, without its causes, against the timeouts {@link UpstreamFailureClassifier} recognises.
     *
     * @param throwable the throwable to test; never {@code null}
     * @return {@code true} for a {@link SocketTimeoutException}, an {@link HttpTimeoutException} or a throwable whose
     *         class or a superclass is named {@link UpstreamFailureClassifier#NETTY_READ_TIMEOUT_EXCEPTION}
     */
    private static boolean isTimeout(Throwable throwable) {
        if (throwable instanceof SocketTimeoutException || throwable instanceof HttpTimeoutException) {
            return true;
        }
        for (Class<?> type = throwable.getClass(); type != null; type = type.getSuperclass()) {
            if (NETTY_READ_TIMEOUT_EXCEPTION.equals(type.getName())) {
                return true;
            }
        }
        return false;
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
