package com.mulesoft.examples.soap_webservice_security.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.wss4j.common.WSS4JConstants;
import org.apache.wss4j.common.cache.MemoryReplayCache;
import org.apache.wss4j.common.cache.ReplayCache;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.ext.WSSecurityException.ErrorCode;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.engine.WSSecurityEngine;
import org.apache.wss4j.dom.engine.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandler;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.wss4j.dom.validate.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.server.SoapEndpointInterceptor;
import org.springframework.ws.soap.soap11.Soap11Body;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.w3c.dom.Document;

/**
 * Inbound WS-Security enforcement of the six Greeter paths on port 63081, one required action set per request path,
 * reproducing what each original {@code cxf:jaxws-service} with {@code cxf:ws-security} accepted and rejected.
 * <p>
 * For a secured path the request's {@code wsse:Security} header is processed by WSS4J's
 * {@link WSSecurityEngine#processSecurityHeader(Document, RequestData)} with the path's callback handler, crypto and
 * SAML validator, and the results are checked with WSS4J's own receiver check,
 * {@code WSHandler.checkReceiverResultsAnyOrder}: the "do they match, in any order?" check CXF 2.7's
 * {@code WSS4JInInterceptor} applied. That check skips signature-confirmation results, {@code BinarySecurityToken}
 * results and encryption results that reference no data, so the certificate token a signed request carries never
 * fails it; every other result must match one required action and every required action must be matched. The check
 * fails closed: a missing or empty security header, a required action without a result, an action the path does not
 * require and any unexpected runtime failure are all rejected.
 * <p>
 * A rejection is a SOAP 1.1 fault, sent with HTTP 500 by the servlet connection. {@code FAILED_AUTHENTICATION} (a
 * wrong UsernameToken password) is answered with {@code wsse:FailedAuthentication} and "The security token could not
 * be authenticated or authorized"; every other failure, including the {@code FAILURE} the SAML validator raises, with
 * {@code wsse:InvalidSecurity} and "An error was discovered processing the &lt;wsse:Security&gt; header". The original
 * clients' negative cases match those texts by substring. Spring's {@code Wss4jSecurityInterceptor} is not used
 * inbound because its action-mismatch fault text differs from the original's.
 * <p>
 * Replay detection reproduces the recipient defaults of CXF 2.7's {@code WSS4JInInterceptor}, which gave each service
 * endpoint its own replay caches: a nonce cache when the action set holds {@code UsernameToken} or
 * {@code UsernameTokenNoPassword}, a timestamp cache when it holds {@code Timestamp}, and a SAML one-time-use cache
 * when it holds {@code SAMLTokenUnsigned} or {@code SAMLTokenSigned}. WSS4J consults them as it did then: a
 * UsernameToken nonce, a signature with its Timestamp, or a SAML 2.0 assertion with a {@code OneTimeUse} condition
 * that the path has already seen is rejected with {@code INVALID_SECURITY}, hence {@code wsse:InvalidSecurity}. A
 * replayed {@code /services/username}, {@code /services/signed} or {@code /services/encrypted} request is therefore
 * refused, while a repeated {@code /services/saml} or {@code /services/signedsaml} request is accepted again, as the
 * original accepted it: neither carries a nonce or a signature with a Timestamp, and the assertions of the original
 * clients have no {@code OneTimeUse} condition. WSS4J's freshness limits are unchanged. The caches reproduce the
 * original's handling and differ from it only for hostile input, as {@link BoundedReplayCache} documents: the first
 * sighting of an identifier is reserved atomically, so of concurrent copies of one request exactly one is accepted;
 * an identifier is kept for at least 360 seconds, the longest time WSS4J's default limits accept it; and a cache
 * holding 50,000 identifiers refuses new ones until entries expire.
 * <p>
 * Paths absent from the policy table, and requests not received over HTTP, pass through unsecured; the listener's
 * routing filter answers paths the original did not serve. On an unsecured path {@link #understands} declines the
 * security header, so a {@code mustUnderstand} header there gets CXF's MustUnderstand fault from SC-11's message
 * dispatcher, as CXF answered a service without WS-Security.
 * <p>
 * Deliberately neither a {@code SmartEndpointInterceptor} nor a component, and on no endpoint mapping: the
 * web-service configuration's message receiver runs it once per request, before any endpoint is selected, so an
 * encrypted body is decrypted before its operation is chosen and a body root no endpoint maps is checked too; any
 * further registration would validate each request twice. That receiver also consults {@link #understands} for
 * every mapped chain, the unknown-operation fallback included. One instance serves concurrent requests: policies are
 * immutable, {@link RequestData} is created per request, and each secured path's replay caches are shared,
 * thread-safe and closed with the application context ({@link #close()}).
 * <p>
 * Compatibility with the original success cases is configured per path through {@link PathPolicy}, and exactly one
 * fallback is needed: {@code /services/saml} must have {@code validateSamlSubjectConfirmation} set to {@code false}.
 * With WSS4J's default of {@code true}, WSS4J 4 rejects the original unsigned sender-vouches request ("Assertion fails
 * sender-vouches requirements"), because no message signature covers the assertion and the body, a check WSS4J 1.6 under CXF 2.7
 * did not make; every check of {@code SAMLCustomValidator} still runs. {@code /services/signedsaml} passes with the
 * default, and no path needs BSP enforcement disabled.
 */
public class PathScopedSecurityInterceptor implements SoapEndpointInterceptor, AutoCloseable {

    /** Fault string of every rejection other than a failed authentication; WSS4J's {@code INVALID_SECURITY} text. */
    static final String INVALID_SECURITY_FAULT_STRING = "An error was discovered processing the <wsse:Security> header";

    /** Fault string of a failed authentication; WSS4J's {@code FAILED_AUTHENTICATION} text. */
    static final String FAILED_AUTHENTICATION_FAULT_STRING =
            "The security token could not be authenticated or authorized";

    static final QName INVALID_SECURITY_FAULT_CODE = new QName(WSS4JConstants.WSSE_NS, "InvalidSecurity", "wsse");

    static final QName FAILED_AUTHENTICATION_FAULT_CODE =
            new QName(WSS4JConstants.WSSE_NS, "FailedAuthentication", "wsse");

    private static final QName SECURITY_HEADER = new QName(WSS4JConstants.WSSE_NS, "Security");

    private static final Logger LOG = LoggerFactory.getLogger(PathScopedSecurityInterceptor.class);

    private static final ReceiverResultsCheck RECEIVER_RESULTS_CHECK = new ReceiverResultsCheck();

    static {
        WSSConfig.init();
    }

    private final Map<String, PathPolicy> policies;

    /** The replay caches of each secured path, keyed like {@link #policies}. */
    private final Map<String, PathReplayCaches> replayCaches;

    /**
     * Builds the replay caches each secured path's action set needs.
     *
     * @param policies the policy of each served path, keyed by the request path without the context path, for
     *                 example {@code /services/username}
     */
    public PathScopedSecurityInterceptor(Map<String, PathPolicy> policies) {
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
        Map<String, PathReplayCaches> caches = new HashMap<>();
        this.policies.forEach((path, policy) -> {
            if (policy.isSecured()) {
                caches.put(path, PathReplayCaches.of(path, policy));
            }
        });
        this.replayCaches = Map.copyOf(caches);
    }

    /** @return the immutable policy table, keyed by request path */
    public Map<String, PathPolicy> policies() {
        return policies;
    }

    /**
     * Empties every path's replay caches. Spring calls this when the application context closes, as the inferred
     * destroy method of the bean; the interceptor stays usable afterwards, with empty caches.
     */
    @Override
    public void close() {
        replayCaches.values().forEach(PathReplayCaches::close);
    }

    @Override
    public boolean understands(SoapHeaderElement header) {
        return SECURITY_HEADER.equals(header.getName())
                && currentPath().map(policies::get).map(PathPolicy::isSecured).orElse(false);
    }

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) {
        Optional<String> path = currentPath();
        PathPolicy policy = path.map(policies::get).orElse(null);
        if (policy == null || !policy.isSecured()) {
            return true;
        }
        try {
            validate((SoapMessage) messageContext.getRequest(), policy, replayCaches.get(path.get()));
            return true;
        }
        catch (WSSecurityException ex) {
            logRejection(path.get(), ex.getErrorCode(), ex);
            writeFault(messageContext, ex.getErrorCode());
            return false;
        }
        catch (RuntimeException ex) {
            logRejection(path.get(), ErrorCode.INVALID_SECURITY, ex);
            writeFault(messageContext, ErrorCode.INVALID_SECURITY);
            return false;
        }
    }

    @Override
    public boolean handleResponse(MessageContext messageContext, Object endpoint) {
        return true;
    }

    @Override
    public boolean handleFault(MessageContext messageContext, Object endpoint) {
        return true;
    }

    @Override
    public void afterCompletion(MessageContext messageContext, Object endpoint, Exception ex) {
        // Responses are not secured: the original services declared no outbound actions.
    }

    private static void validate(SoapMessage message, PathPolicy policy, PathReplayCaches replayCaches)
            throws WSSecurityException {
        Crypto sigVerCrypto = policy.sigVerCrypto() == null ? null : policy.sigVerCrypto().get();
        Crypto decCrypto = policy.decCrypto() == null ? null : policy.decCrypto().get();

        WSSConfig config = WSSConfig.getNewInstance();
        if (policy.samlValidator() != null) {
            config.setValidator(WSConstants.SAML_TOKEN, policy.samlValidator());
            config.setValidator(WSConstants.SAML2_TOKEN, policy.samlValidator());
        }

        // No subject or issuer certificate constraints and no revocation: the single self-signed "joe" certificate
        // is trusted directly because the keystore holds it, as WSS4J 1.6 trusted it; building a certificate path
        // instead would reject its MD5withRSA signature on Java 17.
        RequestData requestData = new RequestData();
        requestData.setWssConfig(config);
        requestData.setCallbackHandler(policy.callbackHandler());
        requestData.setSigVerCrypto(sigVerCrypto);
        requestData.setDecCrypto(decCrypto);
        requestData.setEnableRevocation(false);
        requestData.setValidateSamlSubjectConfirmation(policy.validateSamlSubjectConfirmation());
        requestData.setDisableBSPEnforcement(policy.disableBspEnforcement());
        replayCaches.applyTo(requestData);

        // Written back so the endpoint reads the decrypted body, as Spring's Wss4jSecurityInterceptor does.
        Document document = message.getDocument();
        WSHandlerResult result = new WSSecurityEngine().processSecurityHeader(document, requestData);
        message.setDocument(document);

        if (result == null || result.getResults() == null || result.getResults().isEmpty()) {
            throw new WSSecurityException(ErrorCode.INVALID_SECURITY);
        }
        List<Integer> requiredActions = WSSecurityUtil.decodeAction(policy.requiredActions());
        if (!RECEIVER_RESULTS_CHECK.matches(result.getResults(), requiredActions)) {
            throw new WSSecurityException(ErrorCode.INVALID_SECURITY);
        }
    }

    private static void writeFault(MessageContext messageContext, ErrorCode errorCode) {
        boolean failedAuthentication = errorCode == ErrorCode.FAILED_AUTHENTICATION;
        QName faultCode = failedAuthentication ? FAILED_AUTHENTICATION_FAULT_CODE : INVALID_SECURITY_FAULT_CODE;
        String faultString = failedAuthentication ? FAILED_AUTHENTICATION_FAULT_STRING : INVALID_SECURITY_FAULT_STRING;

        SoapBody body = ((SoapMessage) messageContext.getResponse()).getSoapBody();
        if (body instanceof Soap11Body soap11Body) {
            // No locale, so no xml:lang on faultstring; the envelope styler removes any SAAJ adds.
            soap11Body.addFault(faultCode, faultString, null);
        }
        else {
            // SOAP 1.2 defines no custom fault codes; only a SOAP 1.2 message factory reaches this.
            body.addClientOrSenderFault(faultString, null);
        }
    }

    /**
     * The request path without the context path, decoded, without path parameters and with dot segments resolved.
     * The raw request URI is deliberately not used: an encoded, {@code ;}-suffixed or dotted spelling of a secured
     * path would miss that path's policy and pass through unsecured.
     */
    private static Optional<String> currentPath() {
        TransportContext context = TransportContextHolder.getTransportContext();
        if (context == null) {
            return Optional.empty();
        }
        WebServiceConnection connection = context.getConnection();
        if (!(connection instanceof HttpServletConnection servletConnection)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletConnection.getHttpServletRequest();
        return Optional.of(StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request)));
    }

    /**
     * Logs a rejected request by its policy path, the WSS4J error code its fault is chosen by and the exception type.
     * WSS4J messages, their causes and the exceptions of callback handlers and validators can quote token content or
     * passwords, so neither the exception's text nor the exception itself is logged. The path matched a key of the
     * policy table, so, like the code and the type, it cannot break the entry across lines.
     */
    private static void logRejection(String path, ErrorCode errorCode, Exception ex) {
        LOG.warn("WS-Security validation failed on {}: error code {}, exception {}", path, errorCode,
                ex.getClass().getName());
    }

    /**
     * The WS-Security requirements of one path.
     *
     * @param requiredActions                 WSS4J action names separated by white space, for example
     *                                        {@code UsernameToken Signature Timestamp}; blank for an unsecured path
     * @param callbackHandler                 password callback for UsernameToken and private keys, or {@code null}
     * @param sigVerCrypto                    signature verification crypto, resolved per request, or {@code null}
     * @param decCrypto                       decryption crypto, resolved per request, or {@code null}
     * @param samlValidator                   validator registered for SAML 1.1 and 2.0 assertions, or {@code null}
     *                                        for WSS4J's default
     * @param validateSamlSubjectConfirmation whether WSS4J checks the subject confirmation requirements of SAML
     *                                        assertions after processing the header
     * @param disableBspEnforcement           whether WSS4J's Basic Security Profile rules are switched off
     */
    public record PathPolicy(String requiredActions, CallbackHandler callbackHandler, Supplier<Crypto> sigVerCrypto,
            Supplier<Crypto> decCrypto, Validator samlValidator, boolean validateSamlSubjectConfirmation,
            boolean disableBspEnforcement) {

        /** A path whose requests are not checked. */
        public static final PathPolicy UNSECURED = new PathPolicy("", null, null, null, null, true, false);

        /**
         * Normalizes the action list to single spaces and rejects an action WSS4J does not know, so a mistyped
         * policy fails at start-up rather than on every request.
         *
         * @throws IllegalArgumentException when {@code requiredActions} names an unknown action
         */
        public PathPolicy {
            requiredActions = requiredActions == null ? "" : requiredActions.trim().replaceAll("\\s+", " ");
            if (!requiredActions.isEmpty()) {
                try {
                    WSSecurityUtil.decodeAction(requiredActions);
                }
                catch (WSSecurityException ex) {
                    throw new IllegalArgumentException("Unknown WS-Security action in '" + requiredActions + "'", ex);
                }
            }
        }

        /**
         * A policy with SAML subject-confirmation validation and BSP enforcement on, WSS4J's defaults.
         */
        public static PathPolicy of(String requiredActions, CallbackHandler callbackHandler,
                Supplier<Crypto> sigVerCrypto, Supplier<Crypto> decCrypto, Validator samlValidator) {
            return new PathPolicy(requiredActions, callbackHandler, sigVerCrypto, decCrypto, samlValidator, true, false);
        }

        /** @return whether requests to the path must carry the required actions */
        public boolean isSecured() {
            return !requiredActions.isBlank();
        }
    }

    /**
     * The replay caches of one secured path, chosen from its action set as CXF 2.7's {@code WSS4JInInterceptor} chose
     * a recipient's; a cache the action set does not need is {@code null}, so WSS4J skips that check.
     *
     * @param nonce          UsernameToken nonces, for {@code UsernameToken} and {@code UsernameTokenNoPassword}
     * @param timestamp      signatures with their Timestamp, for {@code Timestamp}
     * @param samlOneTimeUse SAML 2.0 assertions with a {@code OneTimeUse} condition, for {@code SAMLTokenUnsigned}
     *                       and {@code SAMLTokenSigned}
     */
    private record PathReplayCaches(BoundedReplayCache nonce, BoundedReplayCache timestamp,
            BoundedReplayCache samlOneTimeUse) {

        static PathReplayCaches of(String path, PathPolicy policy) {
            List<Integer> actions;
            try {
                actions = WSSecurityUtil.decodeAction(policy.requiredActions());
            }
            catch (WSSecurityException ex) {
                // PathPolicy rejects an unknown action when it is created; this only translates the checked exception.
                throw new IllegalArgumentException("Unknown WS-Security action in '" + policy.requiredActions() + "'",
                        ex);
            }
            boolean nonceRequired = actions.contains(WSConstants.UT) || actions.contains(WSConstants.UT_NOPASSWORD);
            boolean timestampRequired = actions.contains(WSConstants.TS);
            boolean samlRequired = actions.contains(WSConstants.ST_UNSIGNED) || actions.contains(WSConstants.ST_SIGNED);
            return new PathReplayCaches(
                    nonceRequired ? new BoundedReplayCache(path + " nonce") : null,
                    timestampRequired ? new BoundedReplayCache(path + " timestamp") : null,
                    samlRequired ? new BoundedReplayCache(path + " SAML one-time-use") : null);
        }

        void applyTo(RequestData requestData) {
            requestData.setNonceReplayCache(nonce);
            requestData.setTimestampReplayCache(timestamp);
            requestData.setSamlOneTimeUseReplayCache(samlOneTimeUse);
        }

        void close() {
            if (nonce != null) {
                nonce.close();
            }
            if (timestamp != null) {
                timestamp.close();
            }
            if (samlOneTimeUse != null) {
                samlOneTimeUse.close();
            }
        }
    }

    /**
     * A thread-safe, bounded WSS4J replay cache of one path's identifiers of one kind, each kept until its expiry.
     * <p>
     * Legitimate requests get the answers WSS4J's {@link MemoryReplayCache} gives; only hostile input is treated
     * differently:
     * <ul>
     *   <li>{@link #contains} reserves the first sighting of an identifier atomically. WSS4J calls {@code contains} and
     *   then {@code add} as two calls, so with {@code MemoryReplayCache} two concurrent copies of one request can both
     *   pass; the reservation lets exactly one of them pass. Every WSS4J caller (the UsernameToken nonce check, the
     *   signature-with-Timestamp check and the SAML one-time-use check) adds the identifier right after a
     *   {@code false} answer, so for sequential requests the reservation changes nothing.</li>
     *   <li>An identifier is kept for at least the minimum retention, by default 360 seconds: the longest time an
     *   identifier first seen now can still be accepted under WSS4J's default limits, the 300-second Timestamp and
     *   UsernameToken TTL plus the 60-second allowance for a {@code Created} in the future. WSS4J's own nonce expiry,
     *   now plus the UsernameToken TTL, would forget the nonce of a future-dated token that is still accepted.</li>
     *   <li>At most the maximum number of entries, by default 50,000, is kept. Expired entries are purged at most about
     *   once per second, and at once when the bound is reached. A cache still full after that purge refuses every new
     *   identifier, answering {@code true} so WSS4J rejects the request, until entries expire, so replay protection is
     *   never weakened; one warning naming the path and the cache kind, never an identifier, is logged per such
     *   saturation. The bound is approximate under concurrency: requests that pass the check together each add their
     *   entry, so the cache can exceed it by at most the number of requests processed at once.</li>
     * </ul>
     * An expiry passed to {@link #add(String, Instant)} is clamped to between the minimum and the maximum retention,
     * by default {@link MemoryReplayCache#MAX_TTL} seconds, and an entry keeps the later of its current and its new
     * expiry, so the SAML check's {@code add(id, notOnOrAfter)} followed by {@code add(id)} keeps the assertion's
     * lifetime. {@link #close()} empties the cache and leaves it usable, as {@code MemoryReplayCache.close()} does.
     */
    static final class BoundedReplayCache implements ReplayCache {

        /** Most identifiers a production cache keeps. */
        static final int DEFAULT_MAX_ENTRIES = 50_000;

        /** Longest time an identifier first seen now is accepted under WSS4J's default limits: 360 seconds. */
        static final Duration DEFAULT_MIN_RETENTION = defaultMinRetention();

        /** Longest retention WSS4J's {@link MemoryReplayCache} grants an entry: 3600 seconds. */
        static final Duration DEFAULT_MAX_RETENTION = Duration.ofSeconds(MemoryReplayCache.MAX_TTL);

        /** Shortest interval between two periodic purges of expired entries. */
        private static final long PURGE_INTERVAL_MILLIS = 1_000;

        private final String name;

        private final int maxEntries;

        private final Duration minRetention;

        private final Duration maxRetention;

        private final Clock clock;

        /** Each kept identifier and the instant from which it is forgotten. */
        private final ConcurrentHashMap<String, Instant> entries = new ConcurrentHashMap<>();

        /** Epoch millisecond from which the next periodic purge may run. */
        private final AtomicLong nextPurgeMillis = new AtomicLong(Long.MIN_VALUE);

        /** Whether the cache is full and refusing new identifiers. */
        private final AtomicBoolean saturated = new AtomicBoolean();

        /**
         * A cache with the default bound and retentions on the system clock.
         *
         * @param name the path and the cache kind, for the saturation warning
         */
        BoundedReplayCache(String name) {
            this(name, DEFAULT_MAX_ENTRIES, DEFAULT_MIN_RETENTION, DEFAULT_MAX_RETENTION, Clock.systemUTC());
        }

        /**
         * @param name         the path and the cache kind, for the saturation warning
         * @param maxEntries   most identifiers kept, at least 1
         * @param minRetention shortest time an identifier is kept, positive
         * @param maxRetention longest time an identifier is kept, at least {@code minRetention}
         * @param clock        the clock expiries are measured on
         * @throws IllegalArgumentException when a bound or a retention is out of range
         */
        BoundedReplayCache(String name, int maxEntries, Duration minRetention, Duration maxRetention, Clock clock) {
            this.name = Objects.requireNonNull(name, "name");
            this.minRetention = Objects.requireNonNull(minRetention, "minRetention");
            this.maxRetention = Objects.requireNonNull(maxRetention, "maxRetention");
            this.clock = Objects.requireNonNull(clock, "clock");
            if (maxEntries < 1) {
                throw new IllegalArgumentException("maxEntries must be at least 1: " + maxEntries);
            }
            if (minRetention.isNegative() || minRetention.isZero() || maxRetention.compareTo(minRetention) < 0) {
                throw new IllegalArgumentException("Retentions must satisfy 0 < minRetention <= maxRetention: "
                        + minRetention + ", " + maxRetention);
            }
            this.maxEntries = maxEntries;
        }

        /**
         * Tests and reserves {@code identifier} in one atomic step: a kept, unexpired identifier is a replay; any other
         * is recorded until now plus the minimum retention, and is not one.
         *
         * @return {@code true} when {@code identifier} was seen before and has not expired, or when the cache is full;
         *         {@code false} for {@code null} or an empty identifier, as {@code MemoryReplayCache} answers
         */
        @Override
        public boolean contains(String identifier) {
            if (identifier == null || identifier.isEmpty()) {
                return false;
            }
            Instant now = clock.instant();
            purgeIfDue(now);
            if (refusesNewIdentifiers(now)) {
                return true;
            }
            AtomicBoolean seen = new AtomicBoolean();
            entries.compute(identifier, (id, expiry) -> {
                if (expiry != null && expiry.isAfter(now)) {
                    seen.set(true);
                    return expiry;
                }
                return now.plus(minRetention);
            });
            return seen.get();
        }

        /** Keeps {@code identifier} for at least the minimum retention; ignores {@code null} and empty identifiers. */
        @Override
        public void add(String identifier) {
            add(identifier, null);
        }

        /**
         * Keeps {@code identifier} until {@code expiry} clamped to between now plus the minimum and now plus the
         * maximum retention ({@code null} meaning the minimum), or until its current expiry when that is later.
         * Ignores {@code null} and empty identifiers. Never refused: WSS4J adds only an identifier {@link #contains}
         * has just reserved.
         */
        @Override
        public void add(String identifier, Instant expiry) {
            if (identifier == null || identifier.isEmpty()) {
                return;
            }
            Instant now = clock.instant();
            Instant earliest = now.plus(minRetention);
            Instant latest = now.plus(maxRetention);
            Instant kept;
            if (expiry == null || expiry.isBefore(earliest)) {
                kept = earliest;
            }
            else if (expiry.isAfter(latest)) {
                kept = latest;
            }
            else {
                kept = expiry;
            }
            entries.merge(identifier, kept, (current, added) -> current.isAfter(added) ? current : added);
        }

        /** Forgets every identifier; the cache stays usable. */
        @Override
        public void close() {
            entries.clear();
            saturated.set(false);
        }

        /**
         * Whether the cache is at its bound and refuses new identifiers. On reaching the bound it purges expired
         * entries at once; while it stays full only the periodic purge runs, so a flood of new identifiers costs at
         * most one scan of the cache per second.
         */
        private boolean refusesNewIdentifiers(Instant now) {
            if (entries.size() < maxEntries) {
                if (saturated.get()) {
                    saturated.set(false);
                }
                return false;
            }
            if (saturated.get()) {
                return true;
            }
            purgeExpired(now);
            if (entries.size() < maxEntries) {
                return false;
            }
            if (saturated.compareAndSet(false, true)) {
                LOG.warn("WS-Security replay cache {} holds its bound of {} identifiers: requests with a new identifier"
                        + " are refused until entries expire", name, maxEntries);
            }
            return true;
        }

        /** Purges expired entries when the periodic purge is due; one caller runs it. */
        private void purgeIfDue(Instant now) {
            long nowMillis = now.toEpochMilli();
            long due = nextPurgeMillis.get();
            if (nowMillis >= due && nextPurgeMillis.compareAndSet(due, nowMillis + PURGE_INTERVAL_MILLIS)) {
                purgeExpired(now);
            }
        }

        /** Removes the entries expired at {@code now}; an entry whose expiry another thread extends meanwhile stays. */
        private void purgeExpired(Instant now) {
            entries.values().removeIf(expiry -> !expiry.isAfter(now));
        }

        private static Duration defaultMinRetention() {
            RequestData defaults = new RequestData();
            int timestampWindow = defaults.getTimeStampTTL() + defaults.getTimeStampFutureTTL();
            int usernameTokenWindow = defaults.getUtTTL() + defaults.getUtFutureTTL();
            return Duration.ofSeconds(Math.max(timestampWindow, usernameTokenWindow));
        }
    }

    /** Exposes WSS4J's protected receiver check; the handler's own options are never consulted by it. */
    private static final class ReceiverResultsCheck extends WSHandler {

        boolean matches(List<WSSecurityEngineResult> results, List<Integer> requiredActions) {
            return checkReceiverResultsAnyOrder(results, requiredActions);
        }

        @Override
        public Object getOption(String key) {
            return null;
        }

        @Override
        public Object getProperty(Object msgContext, String key) {
            return null;
        }

        @Override
        public void setProperty(Object msgContext, String key, Object value) {
            // The receiver check reads and writes no handler properties.
        }

        @Override
        public String getPassword(Object msgContext) {
            return null;
        }

        @Override
        public void setPassword(Object msgContext, String password) {
            // The receiver check handles no passwords.
        }
    }
}
