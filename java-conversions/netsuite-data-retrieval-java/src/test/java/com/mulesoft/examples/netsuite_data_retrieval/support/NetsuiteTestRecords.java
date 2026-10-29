package com.mulesoft.examples.netsuite_data_retrieval.support;

import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The test customer of the original MUnit suite
 * {@code netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml}: its sub-flows
 * {@code createCustomerInNetsuite} (l.51-67) and {@code deleteCustomerInNetsuite} (l.68-73), which bracket the
 * {@code GET /api/customers?name=<companyName>} assertion, written by hand in Java. This class is the single owner of
 * the suite's one DataWeave script (header l.53, the customer record built from {@code nets.customer.*}).
 *
 * <ul>
 *   <li>{@link #customer(Environment)} is the l.53-62 script with its {@code application/java} output: an ordered map
 *       {@code {companyName, subsidiary: {internalId, type: SUBSIDIARY}}} whose values are the property strings.</li>
 *   <li>{@link #restCreateBody(Map)} turns that SuiteTalk-shaped record into the body of the REST record API, where a
 *       record reference is {@code {"id": ...}} and carries no type; {@link #restCreateBody(Map, String)} adds the
 *       run's {@code externalId}.</li>
 *   <li>{@link #createCustomerInNetsuite(NetsuiteRestClient, Environment, String)} replaces {@code netsuite:add-record
 *       recordType="CUSTOMER"} (l.64-66) and {@link #deleteCustomerInNetsuite(NetsuiteRestClient, String)}
 *       replaces {@code netsuite:delete-record} (l.69-72), each logging the original INFO line.</li>
 *   <li>{@link #withTestCustomer(NetsuiteRestClient, Environment, String, Runnable)} is the bracket of the MUnit
 *       customers test (l.18-30): create, assert, and delete in {@code finally}, the create inside the protected
 *       block.</li>
 * </ul>
 *
 * <p>Each run marks its customer with a NetSuite external ID of its own ({@link #newExternalId()}), sent as the
 * {@code externalId} body field of the create; {@code companyName} and the subsidiary stay the configured values the
 * MUnit assertion reads. When the create's outcome is unknown, because NetSuite may have committed the customer while
 * its answer was lost or carried no record ID, the cleanup finds the customer by that external ID with SuiteQL and
 * deletes it, so it removes this run's customer and no other. This class sends the create once and never again; only
 * {@link NetsuiteRestClient#createRecord} re-sends it, exactly once after an HTTP 401 with the cached token evicted
 * first, and never after a timeout, connectivity failure or 429.
 *
 * <p>The client's exceptions, the SC-9 upstream exceptions and NetSuite's {@code WebClient*Exception}s among them,
 * pass through unchanged. Only record IDs and the generated external ID are logged; no property value, credential,
 * token or exception text is.
 */
public final class NetsuiteTestRecords {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteTestRecords.class);

    /** The REST record type of the original {@code recordType="CUSTOMER"}. */
    public static final String CUSTOMER_RECORD_TYPE = "customer";

    /** The company name of the test customer, under the original MUnit property name. */
    public static final String COMPANY_NAME_KEY = "nets.customer.companyName";

    /** The subsidiary internal ID of the test customer, under the original MUnit property name. */
    public static final String SUBSIDIARY_INTERNAL_ID_KEY = "nets.customer.subsidiary.internalId";

    /** The prefix of every external ID {@link #newExternalId()} generates, which marks a customer as a test's own. */
    public static final String EXTERNAL_ID_PREFIX = "mule-it-";

    /** The SuiteTalk record-reference type the original script writes on the subsidiary. */
    private static final String SUBSIDIARY_TYPE = "SUBSIDIARY";

    /** The REST record body field that holds a record's external ID. */
    private static final String EXTERNAL_ID = "externalId";

    /** The SuiteQL {@code customer} column returned by {@link #externalIdQuery(String)}. */
    private static final String ID_COLUMN = "id";

    private static final String COMPANY_NAME = "companyName";

    private static final String SUBSIDIARY = "subsidiary";

    private static final String INTERNAL_ID = "internalId";

    private static final String TYPE = "type";

    private static final String REST_ID = "id";

    private NetsuiteTestRecords() {
    }

    /**
     * Generates the external ID of one run's test customer: {@value #EXTERNAL_ID_PREFIX} followed by a random
     * (version 4) UUID, so the ID belongs to that run alone.
     *
     * @return a new external ID such as {@code mule-it-3f2b8c9e-0d4a-4b7e-9a51-6c2d8e1f7a90}
     */
    public static String newExternalId() {
        return EXTERNAL_ID_PREFIX + UUID.randomUUID();
    }

    /**
     * Builds the test customer as the original script did:
     * {@code {companyName: p('nets.customer.companyName'), subsidiary: {internalId:
     * p('nets.customer.subsidiary.internalId'), type: 'SUBSIDIARY'}}}, keys in that order.
     *
     * @param env the environment holding the {@code nets.customer.*} test data
     * @return a new mutable {@link LinkedHashMap} with {@code String} values
     * @throws NullPointerException  when {@code env} is {@code null}
     * @throws IllegalStateException when either property is absent (from {@link Environment#getRequiredProperty})
     */
    public static Map<String, Object> customer(Environment env) {
        Objects.requireNonNull(env, "env");
        Map<String, Object> subsidiary = new LinkedHashMap<>();
        subsidiary.put(INTERNAL_ID, env.getRequiredProperty(SUBSIDIARY_INTERNAL_ID_KEY));
        subsidiary.put(TYPE, SUBSIDIARY_TYPE);
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put(COMPANY_NAME, env.getRequiredProperty(COMPANY_NAME_KEY));
        customer.put(SUBSIDIARY, subsidiary);
        return customer;
    }

    /**
     * Translates a customer built by {@link #customer(Environment)} into the REST create body
     * {@code {"companyName": <companyName>, "subsidiary": {"id": <subsidiary.internalId>}}}. The SuiteTalk
     * {@code type} is dropped, because a REST record reference has no type field. The input is not modified.
     *
     * @param customer the SuiteTalk-shaped customer record
     * @return a new {@link LinkedHashMap}
     * @throws NullPointerException     when {@code customer} is {@code null}
     * @throws IllegalArgumentException when {@code companyName}, {@code subsidiary} or {@code subsidiary.internalId}
     *         is missing, {@code null} or blank, or {@code subsidiary} is not a map; the message names the key
     */
    public static Map<String, Object> restCreateBody(Map<String, Object> customer) {
        Objects.requireNonNull(customer, "customer");
        Object companyName = required(customer.get(COMPANY_NAME), COMPANY_NAME);
        Object subsidiaryValue = required(customer.get(SUBSIDIARY), SUBSIDIARY);
        if (!(subsidiaryValue instanceof Map<?, ?> subsidiary)) {
            throw new IllegalArgumentException("customer " + SUBSIDIARY + " must be a map");
        }
        Object internalId = required(subsidiary.get(INTERNAL_ID), SUBSIDIARY + "." + INTERNAL_ID);
        Map<String, Object> subsidiaryRef = new LinkedHashMap<>();
        subsidiaryRef.put(REST_ID, internalId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(COMPANY_NAME, companyName);
        body.put(SUBSIDIARY, subsidiaryRef);
        return body;
    }

    /**
     * As {@link #restCreateBody(Map)}, followed by the run's marker:
     * {@code {"companyName": ..., "subsidiary": {"id": ...}, "externalId": <externalId>}}. The external ID is what
     * {@link #withTestCustomer(NetsuiteRestClient, Environment, String, Runnable)} finds the customer by when the
     * create's answer is lost; {@code companyName} and the subsidiary are left as configured.
     *
     * @param customer   the SuiteTalk-shaped customer record
     * @param externalId the run's external ID, normally from {@link #newExternalId()}
     * @return a new {@link LinkedHashMap}
     * @throws NullPointerException     when {@code customer} is {@code null}
     * @throws IllegalArgumentException when {@code externalId} is {@code null} or blank, or as
     *         {@link #restCreateBody(Map)} throws
     */
    public static Map<String, Object> restCreateBody(Map<String, Object> customer, String externalId) {
        String marker = requireExternalId(externalId);
        Map<String, Object> body = restCreateBody(customer);
        body.put(EXTERNAL_ID, marker);
        return body;
    }

    /**
     * The {@code createCustomerInNetsuite} sub-flow: creates the test customer, marked with {@code externalId},
     * through the REST record API with one request, and logs {@code Test customer created with ID: <id>} at INFO.
     *
     * @param client     the NetSuite REST client
     * @param env        the environment holding the {@code nets.customer.*} test data
     * @param externalId the run's external ID, normally from {@link #newExternalId()}
     * @return the internal ID of the created customer, never {@code null} or blank
     * @throws NullPointerException     when {@code client} or {@code env} is {@code null}
     * @throws IllegalArgumentException when {@code externalId} is {@code null} or blank
     * @throws IllegalStateException    when NetSuite returned no ID, or as {@link #customer(Environment)} and
     *         {@link NetsuiteRestClient#createRecord(String, Map)} throw; the client's other exceptions pass
     *         unchanged
     */
    public static String createCustomerInNetsuite(NetsuiteRestClient client, Environment env, String externalId) {
        Objects.requireNonNull(client, "client");
        String id = client.createRecord(CUSTOMER_RECORD_TYPE, restCreateBody(customer(env), externalId));
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("NetSuite returned no id for the created test customer");
        }
        LOG.info("Test customer created with ID: {}", id);
        return id;
    }

    /**
     * The MUnit customers test's bracket: {@code createCustomerInNetsuite}, then {@code assertion}, then
     * {@code deleteCustomerInNetsuite} in {@code finally}. The create runs inside the protected block, so the cleanup
     * runs whatever the create's outcome:
     * <ul>
     *   <li>the create returned the customer's ID: that customer is deleted;</li>
     *   <li>the create failed, its answer lost or carrying no record ID, so NetSuite may hold the customer without
     *       this run knowing its ID: every customer whose external ID is {@code externalId} ({@link
     *       #externalIdQuery(String)}, at most one, because NetSuite keeps external IDs unique per record type) is
     *       deleted, and none is when the lookup finds nothing.</li>
     * </ul>
     * The bracket sends the create once and never again, the cleanup included; only
     * {@link NetsuiteRestClient#createRecord} re-sends it, exactly once after an HTTP 401 with the cached token evicted
     * first, and never after a timeout, connectivity failure or 429.
     *
     * <p>A cleanup failure is thrown when the create and the assertion succeeded, because the customer may be left in
     * the account; when either already failed, the cleanup failure is added to that failure as suppressed and the
     * original failure is thrown.
     *
     * @param client     the NetSuite REST client
     * @param env        the environment holding the {@code nets.customer.*} test data
     * @param externalId the run's external ID, normally from {@link #newExternalId()}
     * @param assertion  the test's requests and assertions, run while the customer exists
     * @throws NullPointerException     when {@code client}, {@code env} or {@code assertion} is {@code null}; nothing
     *         is sent then
     * @throws IllegalArgumentException when {@code externalId} is {@code null} or blank; nothing is sent then
     * @throws RuntimeException         the create's or the assertion's failure, or the cleanup's when they succeeded
     * @throws Error                    the assertion's {@link AssertionError}, unchanged
     */
    public static void withTestCustomer(NetsuiteRestClient client, Environment env, String externalId,
            Runnable assertion) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(assertion, "assertion");
        String marker = requireExternalId(externalId);
        String id = null;
        Throwable failure = null;
        try {
            id = createCustomerInNetsuite(client, env, marker);
            assertion.run();
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            deleteTestCustomer(client, id, marker, failure);
        }
    }

    /**
     * The {@code deleteCustomerInNetsuite} sub-flow: deletes the test customer through the REST record API and logs
     * {@code Test customer with ID <id> was deleted.} at INFO.
     *
     * @param client the NetSuite REST client
     * @param id     the internal ID {@link #createCustomerInNetsuite(NetsuiteRestClient, Environment, String)}
     *               returned, or one the external-ID lookup found
     * @throws NullPointerException when {@code client} or {@code id} is {@code null}
     * @throws RuntimeException     as {@link NetsuiteRestClient#deleteRecord(String, String)} throws, unchanged
     */
    public static void deleteCustomerInNetsuite(NetsuiteRestClient client, String id) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(id, "id");
        client.deleteRecord(CUSTOMER_RECORD_TYPE, id);
        LOG.info("Test customer with ID {} was deleted.", id);
    }

    /**
     * The SuiteQL query that finds the customers with an external ID:
     * {@code SELECT id FROM customer WHERE externalid = '<externalId>'}, the value escaped with
     * {@link NetsuiteRestClient#suiteQlLiteral(String)}.
     *
     * @param externalId the external ID to match exactly
     * @return the query text
     * @throws IllegalArgumentException when {@code externalId} is {@code null} or blank
     */
    public static String externalIdQuery(String externalId) {
        return "SELECT id FROM customer WHERE externalid = '"
                + NetsuiteRestClient.suiteQlLiteral(requireExternalId(externalId)) + "'";
    }

    /**
     * The cleanup of {@link #withTestCustomer(NetsuiteRestClient, Environment, String, Runnable)}: deletes the
     * customer {@code id}, or, when {@code id} is {@code null}, every customer {@link #externalIdQuery(String)} finds.
     * A {@link RuntimeException} of the lookup or a delete is thrown when {@code failure} is {@code null} and is
     * otherwise added to {@code failure} as suppressed. The log lines name the record IDs and the external ID only.
     */
    private static void deleteTestCustomer(NetsuiteRestClient client, String id, String externalId,
            Throwable failure) {
        try {
            List<String> ids;
            if (id != null) {
                ids = List.of(id);
            } else {
                LOG.warn("The test customer's ID is unknown; deleting the customer with external ID {}",
                        externalId);
                ids = customerIdsWithExternalId(client, externalId);
                if (ids.isEmpty()) {
                    LOG.warn("No NetSuite customer has external ID {}; there is no test customer to delete",
                            externalId);
                }
            }
            for (String each : ids) {
                deleteCustomerInNetsuite(client, each);
            }
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                throw cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static List<String> customerIdsWithExternalId(NetsuiteRestClient client, String externalId) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : client.suiteQl(externalIdQuery(externalId))) {
            Object value = row.get(ID_COLUMN);
            if (value != null && !String.valueOf(value).isBlank()) {
                ids.add(String.valueOf(value));
            }
        }
        return Collections.unmodifiableList(ids);
    }

    private static String requireExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("test customer externalId is missing");
        }
        return externalId;
    }

    private static Object required(Object value, String key) {
        if (value == null || (value instanceof CharSequence text && text.toString().isBlank())) {
            throw new IllegalArgumentException("customer " + key + " is missing");
        }
        return value;
    }
}
