package com.mulesoft.examples.netsuite_data_retrieval.support;

import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 *       record reference is {@code {"id": ...}} and carries no type.</li>
 *   <li>{@link #createCustomerInNetsuite(NetsuiteRestClient, Environment)} replaces {@code netsuite:add-record
 *       recordType="CUSTOMER"} (l.64-66) and {@link #deleteCustomerInNetsuite(NetsuiteRestClient, String)}
 *       replaces {@code netsuite:delete-record} (l.69-72), each logging the original INFO line.</li>
 * </ul>
 *
 * <p>The client's exceptions, the SC-9 upstream exceptions and NetSuite's {@code WebClient*Exception}s among them,
 * pass through unchanged. Only the record ID is logged; no property value, credential or token is.
 */
public final class NetsuiteTestRecords {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteTestRecords.class);

    /** The REST record type of the original {@code recordType="CUSTOMER"}. */
    public static final String CUSTOMER_RECORD_TYPE = "customer";

    /** The company name of the test customer, under the original MUnit property name. */
    public static final String COMPANY_NAME_KEY = "nets.customer.companyName";

    /** The subsidiary internal ID of the test customer, under the original MUnit property name. */
    public static final String SUBSIDIARY_INTERNAL_ID_KEY = "nets.customer.subsidiary.internalId";

    /** The SuiteTalk record-reference type the original script writes on the subsidiary. */
    private static final String SUBSIDIARY_TYPE = "SUBSIDIARY";

    private static final String COMPANY_NAME = "companyName";

    private static final String SUBSIDIARY = "subsidiary";

    private static final String INTERNAL_ID = "internalId";

    private static final String TYPE = "type";

    private static final String REST_ID = "id";

    private NetsuiteTestRecords() {
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
     * The {@code createCustomerInNetsuite} sub-flow: creates the test customer through the REST record API and logs
     * {@code Test customer created with ID: <id>} at INFO.
     *
     * @param client the NetSuite REST client
     * @param env    the environment holding the {@code nets.customer.*} test data
     * @return the internal ID of the created customer, never {@code null} or blank
     * @throws NullPointerException  when {@code client} or {@code env} is {@code null}
     * @throws IllegalStateException when NetSuite returned no ID, or as {@link #customer(Environment)} and
     *         {@link NetsuiteRestClient#createRecord(String, Map)} throw; the client's other exceptions pass
     *         unchanged
     */
    public static String createCustomerInNetsuite(NetsuiteRestClient client, Environment env) {
        Objects.requireNonNull(client, "client");
        String id = client.createRecord(CUSTOMER_RECORD_TYPE, restCreateBody(customer(env)));
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("NetSuite returned no id for the created test customer");
        }
        LOG.info("Test customer created with ID: {}", id);
        return id;
    }

    /**
     * The {@code deleteCustomerInNetsuite} sub-flow: deletes the test customer through the REST record API and logs
     * {@code Test customer with ID <id> was deleted.} at INFO.
     *
     * @param client the NetSuite REST client
     * @param id     the internal ID {@link #createCustomerInNetsuite(NetsuiteRestClient, Environment)} returned
     * @throws NullPointerException when {@code client} or {@code id} is {@code null}
     * @throws RuntimeException     as {@link NetsuiteRestClient#deleteRecord(String, String)} throws, unchanged
     */
    public static void deleteCustomerInNetsuite(NetsuiteRestClient client, String id) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(id, "id");
        client.deleteRecord(CUSTOMER_RECORD_TYPE, id);
        LOG.info("Test customer with ID {} was deleted.", id);
    }

    private static Object required(Object value, String key) {
        if (value == null || (value instanceof CharSequence text && text.toString().isBlank())) {
            throw new IllegalArgumentException("customer " + key + " is missing");
        }
        return value;
    }
}
