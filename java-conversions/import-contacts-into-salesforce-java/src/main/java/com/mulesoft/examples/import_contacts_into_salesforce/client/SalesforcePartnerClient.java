/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.import_contacts_into_salesforce.config.SalesforceProperties;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.bind.XmlObject;

/**
 * Salesforce SOAP Partner API client (shared component SC-15): the only path through which a Salesforce project
 * reaches Salesforce.
 *
 * <p>The {@link PartnerConnection} is built from a WSC {@link ConnectorConfig} in one of two modes:
 * <ul>
 *   <li><b>login mode</b> (no {@link SessionSource} bean): the Partner API SOAP {@code login} with
 *       {@code sfdc.username} and {@code sfdc.password} immediately followed by {@code sfdc.securityToken}, the
 *       protocol of the original Mule {@code sfdc:config}; WSC then adopts the returned {@code serverUrl} and session
 *       ID. This is not an OAuth grant. All three keys are required: before each login the client checks that none is
 *       missing or blank, and otherwise fails the call with an {@link IllegalStateException} naming every missing key
 *       (never a value), without sending any request.</li>
 *   <li><b>session mode</b> (a {@link SessionSource} bean exists): the session ID and SOAP service endpoint the source
 *       supplies are used as they are, no {@code login} request is made and none of the three login keys is
 *       needed.</li>
 * </ul>
 * The connection is created lazily on the first call and cached, so the application starts with every
 * {@code sfdc.*} secret empty and nothing connects at startup; a login that fails, the credential check included,
 * caches nothing, so the next call checks and logs in afresh. In session mode the source is consulted on every call,
 * and the cached connection is rebuilt, without a {@link SessionSource#refresh() refresh}, whenever the session it
 * returns differs from the one the connection was built from (for example after a new authorization).
 *
 * <p>Every vendor call, the lazy login included, runs inside
 * {@link UpstreamCallExecutor#call(UpstreamSystem, UpstreamCall, UpstreamAction)} with {@link #reauthenticate()} as
 * the re-authentication step. The executor alone decides retries: an {@code INVALID_SESSION_ID} rejection is
 * followed by one re-login and one retry, a rate limit and every timeout or connectivity failure are never retried,
 * and any other failure ({@code INVALID_LOGIN}, {@code INVALID_TYPE}, {@code MALFORMED_QUERY}, ...) reaches the
 * caller as the original WSC exception. The credential check runs inside the same callbacks, so its
 * {@link IllegalStateException} is classified like any other failure and reaches the caller unchanged. This class
 * therefore neither wraps nor retries a failure, and the only wait it adds is for a renewal already in flight.
 *
 * <p>Thread-safe and single-flight: at most one login, re-login or session refresh is in flight at a time, and no
 * lock is held while it runs. A call that needs a connection while one is in flight, like a concurrent
 * {@link #reauthenticate()}, sends no request of its own: it waits for that one and receives its connection or,
 * should it fail, the very exception instance it failed with, which the executor classifies exactly as for the
 * caller that sent it (a timeout stays unavailable and is never retried). The wait is therefore bounded by the
 * connect and read timeouts of the request in flight. A call made once a renewal has failed starts a new one. The
 * lock guarding the cached connection, the renewal in flight and the session-mode rebuild (which sends nothing) is
 * held for those local steps only, and {@link #toMap(XmlObject)} is stateless.
 *
 * <p>This project holds the reference copy. The other Salesforce projects carry an identical copy in which only the
 * {@code package} line differs, so the class depends on nothing but the JDK, SLF4J, Spring, the Salesforce WSC
 * library, its SC-9 siblings and {@link SalesforceProperties}.
 */
@Component
public class SalesforcePartnerClient {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforcePartnerClient.class);

    /** Connect and read timeout applied when none is configured: the Mule default response timeout. */
    private static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    /**
     * Shortest timeout accepted. WSC takes whole milliseconds and treats {@code 0} as no timeout at all, so a shorter
     * duration would leave the call unbounded.
     */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /** Longest timeout WSC can take; a longer one is capped to it. */
    private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** Configuration key of the connect timeout, named when its value is rejected. */
    private static final String CONNECT_TIMEOUT_KEY = "sfdc.connect-timeout";

    /** Configuration key of the read timeout, named when its value is rejected. */
    private static final String READ_TIMEOUT_KEY = "sfdc.read-timeout";

    /** Configuration key of the login-mode username, named when it is missing. */
    private static final String USERNAME_KEY = "sfdc.username";

    /** Configuration key of the login-mode password, named when it is missing. */
    private static final String PASSWORD_KEY = "sfdc.password";

    /** Configuration key of the login-mode security token, named when it is missing. */
    private static final String SECURITY_TOKEN_KEY = "sfdc.securityToken";

    /** Map key of the sObject type, and local name of the Partner API element that carries it. */
    private static final String TYPE_KEY = "type";

    /** Local name of the elements that hold the rows of a query result. */
    private static final String RECORDS = "records";

    /** Local part of the XML type of a nested (sub-query) result. */
    private static final String QUERY_RESULT_TYPE = "QueryResult";

    private final SalesforceProperties properties;

    private final UpstreamCallExecutor executor;

    private final ObjectProvider<SessionSource> sessionSourceProvider;

    private final AtomicReference<CachedConnection> connectionRef = new AtomicReference<>();

    /**
     * Guards the replacement of the cached connection and of {@link #renewalInFlight}, and the session-mode rebuild;
     * held only for these local steps, never across a remote call.
     */
    private final Object lock = new Object();

    /**
     * The login, re-login or session refresh in flight, or {@code null} when none is: concurrent callers join it
     * instead of sending a request of their own. Guarded by {@link #lock}; only the caller performing the renewal
     * installs and clears it.
     */
    private CompletableFuture<CachedConnection> renewalInFlight;

    /**
     * Creates the client; no connection is made, and no credential is checked, until the first call. The timeouts are
     * checked here, so an application configured with one that would leave Salesforce calls unbounded refuses to
     * start.
     *
     * @param properties    the {@code sfdc.*} settings: credentials, login endpoint and timeouts
     * @param executor      the SC-9 executor every vendor call runs through
     * @param sessionSource the provider of an optional {@link SessionSource} bean, resolved at each connection; a
     *                      {@code null} provider, or one without a bean, selects login mode
     * @throws NullPointerException     if {@code properties} or {@code executor} is {@code null}
     * @throws IllegalArgumentException if {@code sfdc.connect-timeout} or {@code sfdc.read-timeout} is shorter than
     *                                  1 ms, zero and negative durations included
     */
    public SalesforcePartnerClient(SalesforceProperties properties, UpstreamCallExecutor executor,
            ObjectProvider<SalesforcePartnerClient.SessionSource> sessionSource) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sessionSourceProvider = sessionSource;
        millis(properties.getConnectTimeout(), CONNECT_TIMEOUT_KEY);
        millis(properties.getReadTimeout(), READ_TIMEOUT_KEY);
    }

    /**
     * Supplies an already authenticated Salesforce session, which switches the client to session mode.
     *
     * <p>Implemented by {@code authenticating-salesforce-using-oauth2-java}: the session ID is the access token of
     * the authorization-code flow and the service endpoint is the SOAP Partner URL on the user's instance.
     */
    public interface SessionSource {

        /**
         * Supplies the session the next call runs under. The client consults it on every call and rebuilds its cached
         * connection, without a {@link #refresh()}, when the session differs from the one that connection was built
         * from, for example after a new authorization.
         *
         * @return the session to use for the call
         * @throws Exception when no session can be supplied, for example because no user has authorized yet
         */
        Session current() throws Exception;

        /**
         * Renews the session after Salesforce rejected the current one (for OAuth, the refresh-token exchange).
         *
         * @return the renewed session
         * @throws Exception when the session cannot be renewed
         */
        Session refresh() throws Exception;
    }

    /**
     * An authenticated Salesforce session.
     *
     * @param sessionId       the session ID sent in the Partner API {@code SessionHeader}
     * @param serviceEndpoint the SOAP Partner endpoint of the instance, containing {@code /services/Soap/u/}
     */
    public record Session(String sessionId, String serviceEndpoint) {

        /** Masks the session ID, which is a bearer credential, so a logged session never reveals it. */
        @Override
        public String toString() {
            return "Session[sessionId=" + (sessionId == null ? "<unset>" : "****")
                    + ", serviceEndpoint=" + serviceEndpoint + "]";
        }
    }

    /**
     * The cached connection with the session it was built from, against which session mode checks every call.
     *
     * @param connection the connection
     * @param session    the session the connection carries, or {@code null} in login mode
     */
    private record CachedConnection(PartnerConnection connection, Session session) {
    }

    /**
     * Runs a SOQL {@code query} and reads every page.
     *
     * @param soql the SOQL statement
     * @return the records in the order Salesforce returned them, each converted by {@link #toMap(XmlObject)}; a
     *         mutable list
     * @throws IllegalStateException if the pages Salesforce returns cannot form a valid result (a page that is not
     *                               done brings more records than the reported size, repeats a query locator, or
     *                               exceeds the pages that size allows); no further page is requested
     * @throws Exception             an SC-9 upstream exception, or the original WSC exception for any other failure
     */
    public List<Map<String, Object>> query(String soql) throws Exception {
        QueryResult first = executor.call(UpstreamSystem.SALESFORCE, () -> connection().query(soql),
                this::reauthenticate);
        return readAllPages(first);
    }

    /**
     * Runs a SOQL {@code queryAll}, which also returns deleted and archived records, and reads every page.
     *
     * @param soql the SOQL statement
     * @return the records in the order Salesforce returned them, each converted by {@link #toMap(XmlObject)}; a
     *         mutable list
     * @throws IllegalStateException if the pages Salesforce returns cannot form a valid result (a page that is not
     *                               done brings more records than the reported size, repeats a query locator, or
     *                               exceeds the pages that size allows); no further page is requested
     * @throws Exception             an SC-9 upstream exception, or the original WSC exception for any other failure
     */
    public List<Map<String, Object>> queryAll(String soql) throws Exception {
        QueryResult first = executor.call(UpstreamSystem.SALESFORCE, () -> connection().queryAll(soql),
                this::reauthenticate);
        return readAllPages(first);
    }

    /**
     * Creates records of one sObject type in a single {@code create} call.
     *
     * @param sObjectType the sObject type, for example {@code Contact}
     * @param records     one map per record, field name to value; entries with a {@code null} value are not sent
     * @return the save results in record order; empty, without any call, when {@code records} is {@code null} or
     *         empty
     * @throws IllegalArgumentException if a record holds a {@code null} field name
     * @throws Exception                an SC-9 upstream exception, or the original WSC exception for any other
     *                                  failure
     */
    public List<SaveResult> create(String sObjectType, List<Map<String, Object>> records) throws Exception {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        SObject[] sObjects = new SObject[records.size()];
        int index = 0;
        for (Map<String, Object> record : records) {
            sObjects[index++] = toSObject(sObjectType, record);
        }
        SaveResult[] results = executor.call(UpstreamSystem.SALESFORCE, () -> connection().create(sObjects),
                this::reauthenticate);
        return results == null ? List.of() : Arrays.asList(results);
    }

    /**
     * Deletes records by ID in a single {@code delete} call.
     *
     * @param ids the record IDs
     * @return the delete results in ID order; empty, without any call, when {@code ids} is {@code null} or empty
     * @throws Exception an SC-9 upstream exception, or the original WSC exception for any other failure
     */
    public List<DeleteResult> delete(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String[] idArray = ids.toArray(String[]::new);
        DeleteResult[] results = executor.call(UpstreamSystem.SALESFORCE, () -> connection().delete(idArray),
                this::reauthenticate);
        return results == null ? List.of() : Arrays.asList(results);
    }

    /**
     * Lists the sObject types of the organization.
     *
     * @return the {@code describeGlobal} result
     * @throws Exception an SC-9 upstream exception, or the original WSC exception for any other failure
     */
    public DescribeGlobalResult describeGlobal() throws Exception {
        return executor.call(UpstreamSystem.SALESFORCE, () -> connection().describeGlobal(), this::reauthenticate);
    }

    /**
     * Describes one sObject type, its fields included.
     *
     * @param type the sObject type name, for example {@code User}
     * @return the {@code describeSObject} result
     * @throws Exception an SC-9 upstream exception, or the original WSC exception for any other failure
     */
    public DescribeSObjectResult describeSObject(String type) throws Exception {
        return executor.call(UpstreamSystem.SALESFORCE, () -> connection().describeSObject(type),
                this::reauthenticate);
    }

    /**
     * Drops the cached connection and builds a new one: login mode logs in again, session mode asks the
     * {@link SessionSource} to {@link SessionSource#refresh() refresh} the session. This is the
     * {@link UpstreamAction} the executor runs once after Salesforce rejects the session; if it fails, no connection
     * stays cached and the next call connects afresh.
     *
     * <p>Single-flight: when a login, re-login or refresh is already in flight, this waits for it instead of sending
     * another, so re-authentications that overlap send one request between them. The wait is bounded by that
     * request's connect and read timeouts, and its failure is thrown here as the same exception instance.
     *
     * @throws IllegalStateException in login mode, if {@code sfdc.username}, {@code sfdc.password} or
     *                               {@code sfdc.securityToken} is missing or blank; in session mode, if the source
     *                               returns no session, or one without a session ID or service endpoint
     * @throws InterruptedException  if the thread is interrupted while it waits for a renewal in flight; its
     *                               interrupt flag is set again
     * @throws Exception             the vendor's or the session source's exception, unwrapped
     */
    public void reauthenticate() throws Exception {
        SessionSource source = sessionSource();
        renew(true, () -> {
            Session session = source == null ? null : validated(source.refresh());
            return new CachedConnection(createConnection(session), session);
        });
    }

    /**
     * Returns the connection for one call. Login mode returns the cached connection, creating it on first use; while
     * a login or re-login is in flight it waits for that one instead of sending its own, so calls that overlap it
     * send no login of their own and each receives its connection or the same exception instance it failed with.
     * The wait is bounded by that login's connect and read timeouts. Session mode reads
     * {@link SessionSource#current()} once and returns the cached connection only when it was built from that same
     * session; otherwise it builds one from that session, which sends nothing, and caches it in its place. Called only
     * inside the lambdas handed to the executor, so a failed lazy login is classified like any other vendor failure.
     *
     * @return the connection
     * @throws IllegalStateException in login mode, if no connection is cached and {@code sfdc.username},
     *                               {@code sfdc.password} or {@code sfdc.securityToken} is missing or blank; in
     *                               session mode, if the session source returns no session, or one without a session
     *                               ID or service endpoint
     * @throws InterruptedException  in login mode, if the thread is interrupted while it waits for a login in flight;
     *                               its interrupt flag is set again
     * @throws Exception             the vendor's or the session source's exception, unwrapped
     */
    PartnerConnection connection() throws Exception {
        SessionSource source = sessionSource();
        if (source == null) {
            CachedConnection cached = connectionRef.get();
            if (cached != null) {
                return cached.connection();
            }
            return renew(false, () -> new CachedConnection(createConnection(null), null)).connection();
        }
        Session session = validated(source.current());
        CachedConnection cached = connectionRef.get();
        if (cached != null && session.equals(cached.session())) {
            return cached.connection();
        }
        synchronized (lock) {
            cached = connectionRef.get();
            if (cached == null || !session.equals(cached.session())) {
                cached = new CachedConnection(createConnection(session), session);
                connectionRef.set(cached);
            }
            return cached.connection();
        }
    }

    /**
     * Runs one connection renewal single-flight. Under {@link #lock}, which it holds for these local steps only, the
     * caller either finds a renewal in flight, which it then waits for, or installs its own, which it then performs
     * on its own thread with no lock held. The performer caches the connection and clears the slot before it
     * completes the shared result; on any failure, the credential check and an {@link Error} included, it caches
     * nothing, clears the slot, hands the failure to every waiter and throws it, so the next call starts afresh.
     *
     * @param reauthentication {@code true} to drop the cached connection first and never reuse it; {@code false}
     *                         for the lazy login, which returns a connection cached since the caller last looked
     * @param renewal          the login, re-login or refresh that builds the connection, run only by the performer
     * @return the renewed connection, or for the lazy login the one already cached
     * @throws InterruptedException if the thread is interrupted while it waits; its interrupt flag is set again
     * @throws Exception            the renewal's own exception, the same instance for the performer and every waiter
     */
    private CachedConnection renew(boolean reauthentication, Callable<CachedConnection> renewal) throws Exception {
        CompletableFuture<CachedConnection> own = new CompletableFuture<>();
        CompletableFuture<CachedConnection> inFlight;
        synchronized (lock) {
            if (reauthentication) {
                connectionRef.set(null);
            } else {
                CachedConnection cached = connectionRef.get();
                if (cached != null) {
                    return cached;
                }
            }
            inFlight = renewalInFlight;
            if (inFlight == null) {
                renewalInFlight = own;
            }
        }
        if (inFlight != null) {
            return awaitRenewal(inFlight);
        }
        try {
            CachedConnection renewed = renewal.call();
            synchronized (lock) {
                connectionRef.set(renewed);
                renewalInFlight = null;
            }
            own.complete(renewed);
            return renewed;
        } catch (Throwable failure) {
            synchronized (lock) {
                if (renewalInFlight == own) {
                    renewalInFlight = null;
                }
            }
            // Wrapped once so that get() hands every waiter this very instance as the cause, even when the failure is
            // itself a CompletionException, which get() would otherwise unwrap.
            own.completeExceptionally(new CompletionException(failure));
            throw failure;
        }
    }

    /**
     * Waits for a renewal another caller performs and returns its result.
     *
     * @param renewal the renewal in flight
     * @return its connection
     * @throws InterruptedException if the thread is interrupted while it waits; its interrupt flag is set again
     * @throws Exception            the renewal's failure, the same instance its performer threw
     */
    private static CachedConnection awaitRenewal(CompletableFuture<CachedConnection> renewal) throws Exception {
        try {
            return renewal.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable failure = e.getCause();
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            // Only a Throwable that is neither an Exception nor an Error, which no Java code can throw from the
            // renewal without bypassing the compiler, gets here; it stays the cause.
            throw e;
        }
    }

    /**
     * @return the {@link SessionSource} bean that selects session mode, or {@code null} for login mode
     */
    private SessionSource sessionSource() {
        return sessionSourceProvider == null ? null : sessionSourceProvider.getIfAvailable();
    }

    /**
     * Checks a session the {@link SessionSource} supplied before a connection is built from it.
     *
     * @param session the session returned by {@link SessionSource#current()} or {@link SessionSource#refresh()}
     * @return {@code session}
     * @throws IllegalStateException if {@code session} is {@code null} or its session ID or service endpoint is
     *                               blank
     */
    private static Session validated(Session session) {
        if (session == null || isBlank(session.sessionId()) || isBlank(session.serviceEndpoint())) {
            throw new IllegalStateException("Salesforce SessionSource returned no session");
        }
        return session;
    }

    /**
     * Builds a connection from a fresh {@link ConnectorConfig}. Login mode first checks the three login keys
     * ({@link #requireLoginCredentials()}), so no credential is placed on the configuration, and no {@code login}
     * request is sent, while one of them is missing.
     *
     * @param session in session mode, the validated session to connect with; {@code null} selects login mode
     * @throws IllegalStateException in login mode, if {@code sfdc.username}, {@code sfdc.password} or
     *                               {@code sfdc.securityToken} is missing or blank
     * @throws Exception             the vendor's exception, unwrapped
     */
    private PartnerConnection createConnection(Session session) throws Exception {
        ConnectorConfig config = new ConnectorConfig();
        // Always set explicitly: when it is unset, Connector.newConnection falls back to Connector.END_POINT, which
        // in force-partner-api 65.0.0 is an internal Salesforce test host rather than login.salesforce.com.
        config.setAuthEndpoint(properties.getAuthEndpoint());
        config.setConnectionTimeout(millis(properties.getConnectTimeout(), CONNECT_TIMEOUT_KEY));
        config.setReadTimeout(millis(properties.getReadTimeout(), READ_TIMEOUT_KEY));
        // Departs from WSC's default (buffered POST): a buffered HttpURLConnection POST is silently re-sent once by
        // the JDK when the connection drops before the response (sun.net.http.retryPost), which could submit a
        // create twice. A streamed (chunked) request body is never re-sent, so connectivity failures reach the
        // executor after exactly one request and are never retried.
        config.setUseChunkedPost(true);
        String mode;
        if (session != null) {
            config.setSessionId(session.sessionId());
            config.setServiceEndpoint(session.serviceEndpoint());
            config.setManualLogin(true);
            mode = "session";
        } else {
            requireLoginCredentials();
            // WSC's login sends username and password; the Partner API expects the security token appended to
            // the password. The service endpoint stays unset, so WSC adopts the serverUrl the login returns.
            config.setUsername(properties.getUsername());
            config.setPassword(properties.getPassword() + properties.getSecurityToken());
            mode = "login";
        }
        PartnerConnection connection = Connector.newConnection(config);
        LOG.info("Salesforce connection established ({} mode) at {}", mode, config.getServiceEndpoint());
        return connection;
    }

    /**
     * Checks that the login-mode credentials are configured, before anything is built or sent from them. Each of
     * {@code sfdc.username}, {@code sfdc.password} and {@code sfdc.securityToken} must be non-blank; they are read
     * afresh on every login, so a call after the configuration is completed logs in normally.
     *
     * @throws IllegalStateException if any of the three is {@code null} or blank. The message names every missing key
     *                               in that order, for example {@code sfdc.password is not configured} or
     *                               {@code sfdc.username, sfdc.password and sfdc.securityToken are not configured},
     *                               and never a configured value
     */
    private void requireLoginCredentials() {
        List<String> missing = new ArrayList<>(3);
        if (isBlank(properties.getUsername())) {
            missing.add(USERNAME_KEY);
        }
        if (isBlank(properties.getPassword())) {
            missing.add(PASSWORD_KEY);
        }
        if (isBlank(properties.getSecurityToken())) {
            missing.add(SECURITY_TOKEN_KEY);
        }
        if (missing.isEmpty()) {
            return;
        }
        int last = missing.size() - 1;
        String keys = last == 0
                ? missing.get(0)
                : String.join(", ", missing.subList(0, last)) + " and " + missing.get(last);
        throw new IllegalStateException(keys + (last == 0 ? " is" : " are") + " not configured");
    }

    /**
     * Converts a timeout to the {@code int} milliseconds WSC takes, capped at {@link Integer#MAX_VALUE}; a
     * sub-millisecond remainder is dropped.
     *
     * @param timeout the configured timeout; {@code null} gives the 10 s default
     * @param key     the configuration key the timeout is bound from, named in the rejection message
     * @return the timeout in milliseconds, at least 1
     * @throws IllegalArgumentException if {@code timeout} is shorter than 1 ms, zero and negative durations included,
     *                                  because WSC would then set no timeout or an invalid one
     */
    private static int millis(Duration timeout, String key) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0) {
            throw new IllegalArgumentException(key + " must be at least 1 ms: " + timeout);
        }
        if (timeout.compareTo(MAX_TIMEOUT) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) timeout.toMillis();
    }

    /**
     * Collects the records of a query result and of every following page. Each {@code queryMore} runs through the
     * executor on its own. Paging stops when Salesforce reports the result done or sends no locator, so a result
     * without a locator can never loop.
     *
     * <p>Paging is also held to what a valid result allows, so an answer that never finishes fails instead of
     * requesting pages forever. The first page's {@code size} is the total Salesforce reports for the whole result;
     * every page before the last carries at least one record, so a valid result needs at most {@code size + 1}
     * pages. The checks run only on a page that asks for another, before that {@code queryMore} is sent, so a
     * result Salesforce reports done is returned as it came; they run outside the executor: the failure is not a
     * vendor failure, so it is never classified or retried and reaches the caller unchanged.
     *
     * @param first the {@code query} or {@code queryAll} result; {@code null} gives no records
     * @return the records of every page in the order Salesforce returned them; a mutable list
     * @throws IllegalStateException if a page that is not done brings the records collected so far beyond the
     *                               reported {@code size}, carries a query locator already requested, or is page
     *                               {@code size + 1}; the message names the reported size and the pages read, never a
     *                               locator or session ID
     * @throws Exception             an SC-9 upstream exception, or the original WSC exception, from a
     *                               {@code queryMore}
     */
    private List<Map<String, Object>> readAllPages(QueryResult first) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        int reportedSize = first == null ? 0 : first.getSize();
        long maxPages = (long) reportedSize + 1;
        Set<String> requestedLocators = new HashSet<>();
        long pagesRead = 0;
        QueryResult page = first;
        while (page != null) {
            pagesRead++;
            SObject[] pageRecords = page.getRecords();
            if (pageRecords != null) {
                for (SObject record : pageRecords) {
                    if (record != null) {
                        records.add(toMap(record));
                    }
                }
            }
            String locator = page.getQueryLocator();
            if (page.getDone() || isBlank(locator)) {
                break;
            }
            if (records.size() > reportedSize) {
                throw new IllegalStateException("Salesforce returned " + records.size() + " query records by page "
                        + pagesRead + ", more than the reported size of " + reportedSize);
            }
            if (!requestedLocators.add(locator)) {
                throw new IllegalStateException("Salesforce query paging did not advance: page " + pagesRead
                        + " repeats a query locator already requested, for a reported size of " + reportedSize);
            }
            if (pagesRead >= maxPages) {
                throw new IllegalStateException("Salesforce query is not done at page " + pagesRead
                        + ", the last page a reported size of " + reportedSize + " allows");
            }
            page = executor.call(UpstreamSystem.SALESFORCE, () -> connection().queryMore(locator),
                    this::reauthenticate);
        }
        return records;
    }

    /**
     * Builds the sObject for one record map, setting its fields in the map's own iteration order.
     */
    private static SObject toSObject(String sObjectType, Map<String, Object> record) {
        SObject sObject = new SObject();
        sObject.setType(sObjectType);
        if (record != null) {
            for (Map.Entry<String, Object> field : record.entrySet()) {
                if (field.getKey() == null) {
                    throw new IllegalArgumentException("A " + sObjectType + " record holds a null field name");
                }
                if (field.getValue() != null) {
                    sObject.setField(field.getKey(), field.getValue());
                }
            }
        }
        return sObject;
    }

    /**
     * Converts a Partner API record into a {@link java.util.HashMap}.
     *
     * <p>The runtime class is {@code HashMap} on purpose: the Mule connector handed records to the flows as
     * {@code java.util.HashMap}, and in {@code salesforce-data-retrieval} their {@code toString()} (iteration order
     * and {@code key=value} format) is the POST {@code /} response body, which the original test checks for lines
     * such as {@code Email=kicks+a@mulesoft.com}.
     *
     * <p>Keys are the SOAP local names exactly as Salesforce returns them ({@code type}, {@code Id}, {@code Name},
     * {@code Email}, ...):
     * <ul>
     *   <li>{@code type} holds the sObject type, when known;</li>
     *   <li>a name the Partner API repeats (it sends {@code Id} twice, the first possibly {@code xsi:nil}) keeps its
     *       first non-null value;</li>
     *   <li>a nested query result (a child with {@code records} children, or of XML type {@code QueryResult})
     *       becomes a list of maps, empty when the sub-query matched nothing;</li>
     *   <li>any other child with children (a relationship such as {@code Account}, or a compound field) becomes a
     *       nested map;</li>
     *   <li>a leaf keeps the value WSC parsed, normally a {@code String}; {@code xsi:nil} and empty text become
     *       {@code null}.</li>
     * </ul>
     *
     * @param record the record; may be {@code null}
     * @return a new {@code HashMap}, or {@code null} for a {@code null} record
     */
    public static Map<String, Object> toMap(XmlObject record) {
        if (record == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        String type = typeOf(record);
        if (type != null) {
            map.put(TYPE_KEY, type);
        }
        Iterator<XmlObject> children = record.getChildren();
        while (children != null && children.hasNext()) {
            XmlObject child = children.next();
            if (child == null || child.getName() == null) {
                continue;
            }
            String key = child.getName().getLocalPart();
            if (map.get(key) == null) {
                map.put(key, convert(child));
            }
        }
        return map;
    }

    private static String typeOf(XmlObject record) {
        Object type;
        if (record instanceof SObject sObject) {
            type = sObject.getType();
        } else {
            Iterator<XmlObject> typeChildren = record.getChildren(TYPE_KEY);
            type = typeChildren != null && typeChildren.hasNext() ? leafValue(typeChildren.next()) : null;
        }
        return type instanceof String text && !text.isEmpty() ? text : null;
    }

    private static Object convert(XmlObject child) {
        if (isSubQuery(child)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            Iterator<XmlObject> records = child.getChildren(RECORDS);
            while (records != null && records.hasNext()) {
                XmlObject row = records.next();
                if (row != null) {
                    rows.add(toMap(row));
                }
            }
            return rows;
        }
        if (child.hasChildren()) {
            return toMap(child);
        }
        return leafValue(child);
    }

    private static boolean isSubQuery(XmlObject child) {
        QName xmlType = child.getXmlType();
        if (xmlType != null && QUERY_RESULT_TYPE.equals(xmlType.getLocalPart())) {
            return true;
        }
        Iterator<XmlObject> records = child.getChildren(RECORDS);
        return records != null && records.hasNext();
    }

    private static Object leafValue(XmlObject leaf) {
        Object value = leaf.getValue();
        return value instanceof String text && text.isEmpty() ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
