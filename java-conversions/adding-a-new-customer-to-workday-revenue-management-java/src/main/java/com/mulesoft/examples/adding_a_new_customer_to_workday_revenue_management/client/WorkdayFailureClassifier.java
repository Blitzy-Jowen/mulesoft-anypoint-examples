/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.xml.namespace.QName;

import org.springframework.stereotype.Component;
import org.springframework.ws.soap.client.SoapFaultClientException;

/**
 * Classifies failed Workday {@code Revenue_Management} calls for {@code UpstreamCallExecutor}: the Workday row of the
 * approved connector failure handling (shared component SC-9).
 *
 * <ul>
 *   <li>A timeout or connectivity failure anywhere in the cause chain → {@link UpstreamFailureKind#UNAVAILABLE},
 *       never retried. Spring WS raises it as a {@code WebServiceIOException} with the socket exception as its cause,
 *       which {@link UpstreamFailureClassifier#isUnavailable(Throwable)} finds.</li>
 *   <li>A {@link WorkdayRevenueManagementClient.HttpStatusException} with HTTP 401, or a
 *       {@link SoapFaultClientException} whose fault code is the WS-Security {@code wsse:FailedAuthentication} or
 *       {@code wsse:InvalidSecurity} → {@link UpstreamFailureKind#AUTHENTICATION}: the executor rebuilds the
 *       UsernameToken ({@code WorkdayRevenueManagementClient::reauthenticate}) and retries the call once. Workday
 *       rejects such a request before processing it, so retrying a {@code Put_Customer} cannot add the customer
 *       twice.</li>
 *   <li>A {@link WorkdayRevenueManagementClient.HttpStatusException} with HTTP 429 → a rate limit carrying Workday's
 *       {@code Retry-After} value, or {@code null} when Workday sent none; never retried.</li>
 *   <li>Everything else → {@link UpstreamFailureKind#OTHER}, rethrown unchanged to the flow's original branch, which
 *       has no exception strategy and so ends in the generic 500: every other HTTP status, Workday's
 *       {@code Validation_Fault} and {@code Processing_Fault} SOAP faults ({@code SOAP-ENV:Client...},
 *       {@code SOAP-ENV:Server...}), marshalling failures and any other exception.</li>
 * </ul>
 *
 * <p>The caller-facing message is the inherited {@link #upstreamMessage(Throwable)}: the first non-blank message of
 * the chain, which is the SOAP fault string or the HTTP reason phrase with its status.
 *
 * <p>Stateless and thread-safe; it logs nothing, because the executor writes the one ERROR line per failure.
 */
@Component
public class WorkdayFailureClassifier implements UpstreamFailureClassifier {

    /** Local part of the WS-Security 1.0 fault code {@code wsse:FailedAuthentication}. */
    private static final String FAILED_AUTHENTICATION = "FailedAuthentication";

    /** Local part of the WS-Security 1.0 fault code {@code wsse:InvalidSecurity}. */
    private static final String INVALID_SECURITY = "InvalidSecurity";

    private static final int HTTP_UNAUTHORIZED = 401;

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /**
     * @return {@link UpstreamSystem#WORKDAY}
     */
    @Override
    public UpstreamSystem system() {
        return UpstreamSystem.WORKDAY;
    }

    /**
     * Classifies a throwable raised by a Workday call or by its re-authentication step.
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
        // Unavailable is decided before the HTTP status and fault code, as the UpstreamFailureClassifier contract
        // requires: a timed-out or disconnected Put_Customer may already have been processed, so a 401 or security
        // fault found in the same chain must not trigger the re-authentication retry. HttpStatusException and
        // SoapFaultClientException carry no socket cause, so a plain 401, 429 or security fault is unaffected.
        if (UpstreamFailureClassifier.isUnavailable(failure)) {
            return UpstreamFailureKind.UNAVAILABLE;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof WorkdayRevenueManagementClient.HttpStatusException httpError) {
                if (httpError.statusCode() == HTTP_UNAUTHORIZED) {
                    return UpstreamFailureKind.AUTHENTICATION;
                }
                if (httpError.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    return UpstreamFailureKind.rateLimit(httpError.retryAfter());
                }
            } else if (current instanceof SoapFaultClientException fault && isSecurityRejection(fault)) {
                return UpstreamFailureKind.AUTHENTICATION;
            }
        }
        return UpstreamFailureKind.OTHER;
    }

    /**
     * Tells whether a SOAP fault is a WS-Security rejection of the UsernameToken. The fault code is matched on its
     * local part alone, because a fault that writes the {@code wsse} prefix without declaring it parses to a code
     * without a namespace.
     *
     * @param fault the SOAP fault Spring WS raised
     * @return {@code true} for {@code wsse:FailedAuthentication} and {@code wsse:InvalidSecurity}
     */
    private static boolean isSecurityRejection(SoapFaultClientException fault) {
        QName faultCode;
        try {
            faultCode = fault.getFaultCode();
        } catch (RuntimeException unreadable) {
            // SAAJ reads the code lazily from the fault element; a classifier must never throw, so a code it
            // cannot read is no security rejection and the fault is rethrown unchanged as OTHER.
            return false;
        }
        if (faultCode == null) {
            return false;
        }
        String localPart = faultCode.getLocalPart();
        return FAILED_AUTHENTICATION.equals(localPart) || INVALID_SECURITY.equals(localPart);
    }
}
