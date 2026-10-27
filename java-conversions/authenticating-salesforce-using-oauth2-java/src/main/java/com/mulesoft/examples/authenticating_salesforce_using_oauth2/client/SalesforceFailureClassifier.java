/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.QName;

import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.ws.SoapFaultException;

import org.springframework.stereotype.Component;

/**
 * Classifies failed Salesforce Partner API calls for {@code UpstreamCallExecutor}: the Salesforce row of the
 * approved connector failure handling (AAP 0.4.8), part of shared component SC-15.
 *
 * <p>A timeout or connectivity failure anywhere in the cause chain → {@link UpstreamFailureKind#UNAVAILABLE},
 * never retried. WSC wraps every transport {@code IOException} as a {@code ConnectionException} with the
 * {@code IOException} as its cause, which {@link UpstreamFailureClassifier#isUnavailable(Throwable)} finds. This
 * check comes first and wins over the Salesforce signals below, also when a fault carrying
 * {@code INVALID_SESSION_ID} or {@code REQUEST_LIMIT_EXCEEDED} sits in the same chain: a timed-out or disconnected
 * request may already have been processed, so logging in again and retrying it could repeat a write.
 *
 * <p>Otherwise the cause chain of the failure is walked from the failure itself, each throwable visited once:
 * <ul>
 *   <li>an {@link ApiFault} (including {@code LoginFault}) whose {@link ApiFault#getExceptionCode() exception code}
 *       is {@link ExceptionCode#INVALID_SESSION_ID} → {@link UpstreamFailureKind#AUTHENTICATION}: the executor logs
 *       in again and retries the call once;</li>
 *   <li>exception code {@link ExceptionCode#REQUEST_LIMIT_EXCEEDED} → a rate limit without a {@code Retry-After}
 *       value, because Salesforce never sends one; the call is not retried;</li>
 *   <li>a {@link SoapFaultException} that carries no exception code (WSC raises one when a fault arrives without a
 *       {@code detail} element it can bind) is classified the same way by the local part of its fault code, which
 *       WSC parses from {@code sf:INVALID_SESSION_ID} as {@code INVALID_SESSION_ID}.</li>
 * </ul>
 *
 * <p>Everything else, for example {@code INVALID_LOGIN}, {@code INVALID_TYPE}, {@code INVALID_FIELD} or
 * {@code MALFORMED_QUERY}, is {@link UpstreamFailureKind#OTHER}: the executor rethrows it unchanged, so the
 * example's own exception branch (in {@code salesforce-data-retrieval} the catch-all
 * {@code Invalid Salesforce query.}) still handles it.
 *
 * <p>This project holds the reference copy. The other Salesforce projects carry an identical copy in which only
 * the {@code package} line differs, so the class depends on nothing but the JDK, Spring, the Salesforce WSC
 * library and its SC-9 siblings.
 */
@Component
public class SalesforceFailureClassifier implements UpstreamFailureClassifier {

    /** Fault-code local part and exception-code name of an expired or invalid session. */
    private static final String INVALID_SESSION_ID = ExceptionCode.INVALID_SESSION_ID.name();

    /** Fault-code local part and exception-code name of an exceeded API request limit. */
    private static final String REQUEST_LIMIT_EXCEEDED = ExceptionCode.REQUEST_LIMIT_EXCEEDED.name();

    /**
     * @return {@link UpstreamSystem#SALESFORCE}
     */
    @Override
    public UpstreamSystem system() {
        return UpstreamSystem.SALESFORCE;
    }

    /**
     * Classifies a throwable raised by a Partner API call or by the re-login that follows a session rejection.
     *
     * <p>A timeout or connectivity failure anywhere in the cause chain is checked first and yields
     * {@link UpstreamFailureKind#UNAVAILABLE} even when the chain also holds an {@code INVALID_SESSION_ID} or
     * {@code REQUEST_LIMIT_EXCEEDED} signal: a timed-out or disconnected request may already have been processed,
     * so logging in again and retrying it could repeat a write. Only a chain without such a cause is searched for
     * those two Salesforce signals.
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
        if (UpstreamFailureClassifier.isUnavailable(failure)) {
            return UpstreamFailureKind.UNAVAILABLE;
        }
        for (Throwable current : causeChain(failure)) {
            UpstreamFailureKind signalled = salesforceSignal(current);
            if (signalled != null) {
                return signalled;
            }
        }
        return UpstreamFailureKind.OTHER;
    }

    /**
     * Returns Salesforce's own text for a failure: the first non-blank {@link ApiFault#getExceptionMessage()} in
     * the cause chain, even when an outer wrapper carries a message of its own. A parsed {@link ApiFault} keeps
     * its text only there ({@code getMessage()} is {@code null}), so the inherited lookup would otherwise miss it
     * and answer with its neutral description. Without such a fault the inherited lookup applies.
     *
     * @param failure the classified throwable; never {@code null}
     * @return the fault's exception message, or the first non-blank message of the chain, or the inherited neutral
     *         description when the chain carries none
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    @Override
    public String upstreamMessage(Throwable failure) {
        for (Throwable current : causeChain(failure)) {
            if (current instanceof ApiFault fault) {
                String exceptionMessage = fault.getExceptionMessage();
                if (exceptionMessage != null && !exceptionMessage.isBlank()) {
                    return exceptionMessage;
                }
            }
        }
        return UpstreamFailureClassifier.super.upstreamMessage(failure);
    }

    /**
     * Reads the authentication or rate-limit signal of one throwable, without its causes. An {@link ApiFault}'s
     * exception code is authoritative when present; the fault code is consulted only when there is none.
     *
     * @param throwable the throwable to inspect; never {@code null}
     * @return the signalled kind, or {@code null} when the throwable signals neither
     */
    private static UpstreamFailureKind salesforceSignal(Throwable throwable) {
        if (throwable instanceof ApiFault fault && fault.getExceptionCode() != null) {
            return kindForCode(fault.getExceptionCode().name());
        }
        if (throwable instanceof SoapFaultException soapFault) {
            QName faultCode = soapFault.getFaultCode();
            if (faultCode != null) {
                return kindForCode(faultCode.getLocalPart());
            }
        }
        return null;
    }

    /**
     * Maps a Salesforce exception-code name or fault-code local part to its kind.
     *
     * @param code the code; may be {@code null}
     * @return {@link UpstreamFailureKind#AUTHENTICATION} for {@code INVALID_SESSION_ID}, a rate limit without
     *         {@code Retry-After} for {@code REQUEST_LIMIT_EXCEEDED}, otherwise {@code null}
     */
    private static UpstreamFailureKind kindForCode(String code) {
        if (INVALID_SESSION_ID.equals(code)) {
            return UpstreamFailureKind.AUTHENTICATION;
        }
        if (REQUEST_LIMIT_EXCEEDED.equals(code)) {
            return UpstreamFailureKind.rateLimit(null);
        }
        return null;
    }

    /**
     * Lists a throwable and its causes in order, each once: identity decides whether a throwable was seen, so a
     * cause chain that loops back on itself ends and a fault overriding {@code equals} is still visited.
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
