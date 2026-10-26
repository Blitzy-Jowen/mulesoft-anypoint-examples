/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.dataweave_with_flowreflookup.config.SalesforceProperties;
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
 *       ID. This is not an OAuth grant.</li>
 *   <li><b>session mode</b> (a {@link SessionSource} bean exists): the session ID and SOAP service endpoint the source
 *       supplies are used as they are and no {@code login} request is made.</li>
 * </ul>
 * The connection is created lazily on the first call and cached, so the application starts with every
 * {@code sfdc.*} secret empty and nothing connects at startup.
 *
 * <p>Every vendor call, the lazy login included, runs inside
 * {@link UpstreamCallExecutor#call(UpstreamSystem, UpstreamCall, UpstreamAction)} with {@link #reauthenticate()} as
 * the re-authentication step. The executor alone decides retries: an {@code INVALID_SESSION_ID} rejection is
 * followed by one re-login and one retry, a rate limit and every timeout or connectivity failure are never retried,
 * and any other failure ({@code INVALID_LOGIN}, {@code INVALID_TYPE}, {@code MALFORMED_QUERY}, ...) reaches the
 * caller as the original WSC exception. This class therefore neither catches, wraps, retries nor waits.
 *
 * <p>Thread-safe: the cached connection is replaced under a lock, and {@link #toMap(XmlObject)} is stateless.
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

    /** Map key of the sObject type, and local name of the Partner API element that carries it. */
    private static final String TYPE_KEY = "type";

    /** Local name of the elements that hold the rows of a query result. */
    private static final String RECORDS = "records";

    /** Local part of the XML type of a nested (sub-query) result. */
    private static final String QUERY_RESULT_TYPE = "QueryResult";

    private final SalesforceProperties properties;

    private final UpstreamCallExecutor executor;

    private final ObjectProvider<SessionSource> sessionSourceProvider;

    private final AtomicReference<PartnerConnection> connectionRef = new AtomicReference<>();

    private final Object lock = new Object();

    /**
     * Creates the client; no connection is made until the first call.
     *
     * @param properties    the {@code sfdc.*} settings: credentials, login endpoint and timeouts
     * @param executor      the SC-9 executor every vendor call runs through
     * @param sessionSource the provider of an optional {@link SessionSource} bean, resolved at each connection; a
     *                      {@code null} provider, or one without a bean, selects login mode
     * @throws NullPointerException if {@code properties} or {@code executor} is {@code null}
     */
    public SalesforcePartnerClient(SalesforceProperties properties, UpstreamCallExecutor executor,
            ObjectProvider<SalesforcePartnerClient.SessionSource> sessionSource) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sessionSourceProvider = sessionSource;
    }

    /**
     * Supplies an already authenticated Salesforce session, which switches the client to session mode.
     *
     * <p>Implemented by {@code authenticating-salesforce-using-oauth2-java}: the session ID is the access token of
     * the authorization-code flow and the service endpoint is the SOAP Partner URL on the user's instance.
     */
    public interface SessionSource {

        /**
         * @return the session to use for a new connection
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
     * Runs a SOQL {@code query} and reads every page.
     *
     * @param soql the SOQL statement
     * @return the records in the order Salesforce returned them, each converted by {@link #toMap(XmlObject)}; a
     *         mutable list
     * @throws Exception an SC-9 upstream exception, or the original WSC exception for any other failure
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
     * @throws Exception an SC-9 upstream exception, or the original WSC exception for any other failure
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
     * @throws Exception the vendor's or the session source's exception, unwrapped
     */
    public void reauthenticate() throws Exception {
        synchronized (lock) {
            connectionRef.set(null);
            connectionRef.set(createConnection(true));
        }
    }

    /**
     * Returns the cached connection, creating it on first use. Called only inside the lambdas handed to the
     * executor, so a failed lazy login is classified like any other vendor failure.
     *
     * @return the connection
     * @throws Exception the vendor's or the session source's exception, unwrapped
     */
    PartnerConnection connection() throws Exception {
        PartnerConnection current = connectionRef.get();
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            current = connectionRef.get();
            if (current == null) {
                current = createConnection(false);
                connectionRef.set(current);
            }
            return current;
        }
    }

    /**
     * Builds a connection from a fresh {@link ConnectorConfig}.
     *
     * @param refresh in session mode, whether to renew the session rather than read the current one
     */
    private PartnerConnection createConnection(boolean refresh) throws Exception {
        SessionSource source = sessionSourceProvider == null ? null : sessionSourceProvider.getIfAvailable();
        ConnectorConfig config = new ConnectorConfig();
        // Always set explicitly: when it is unset, Connector.newConnection falls back to Connector.END_POINT, which
        // in force-partner-api 65.0.0 is an internal Salesforce test host rather than login.salesforce.com.
        config.setAuthEndpoint(properties.getAuthEndpoint());
        config.setConnectionTimeout(millis(properties.getConnectTimeout()));
        config.setReadTimeout(millis(properties.getReadTimeout()));
        // Departs from WSC's default (buffered POST): a buffered HttpURLConnection POST is silently re-sent once by
        // the JDK when the connection drops before the response (sun.net.http.retryPost), which could submit a
        // create twice. A streamed (chunked) request body is never re-sent, so connectivity failures reach the
        // executor after exactly one request and are never retried.
        config.setUseChunkedPost(true);
        String mode;
        if (source != null) {
            Session session = refresh ? source.refresh() : source.current();
            if (session == null || isBlank(session.sessionId()) || isBlank(session.serviceEndpoint())) {
                throw new IllegalStateException("Salesforce SessionSource returned no session");
            }
            config.setSessionId(session.sessionId());
            config.setServiceEndpoint(session.serviceEndpoint());
            config.setManualLogin(true);
            mode = "session";
        } else {
            // WSC's login sends username and password; the Partner API expects the security token appended to
            // the password. The service endpoint stays unset, so WSC adopts the serverUrl the login returns.
            config.setUsername(properties.getUsername());
            config.setPassword(Objects.toString(properties.getPassword(), "")
                    + Objects.toString(properties.getSecurityToken(), ""));
            mode = "login";
        }
        PartnerConnection connection = Connector.newConnection(config);
        LOG.info("Salesforce connection established ({} mode) at {}", mode, config.getServiceEndpoint());
        return connection;
    }

    /**
     * Converts a timeout to the {@code int} milliseconds WSC takes, capped at {@link Integer#MAX_VALUE}.
     *
     * @param timeout the configured timeout; {@code null} gives the 10 s default
     */
    private static int millis(Duration timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    /**
     * Collects the records of a query result and of every following page. Each {@code queryMore} runs through the
     * executor on its own. Paging stops when Salesforce reports the result done or sends no locator, so a result
     * without a locator can never loop.
     */
    private List<Map<String, Object>> readAllPages(QueryResult first) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        QueryResult page = first;
        while (page != null) {
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
