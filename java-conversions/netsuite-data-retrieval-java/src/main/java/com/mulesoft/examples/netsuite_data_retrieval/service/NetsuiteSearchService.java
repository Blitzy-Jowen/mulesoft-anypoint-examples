/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.CustomerSearchMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.ItemSupplyPlanMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.OpportunitySearchMapper;

/**
 * The processor chains of the three APIkit flows of {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml},
 * each run in its original order: search criteria, the search, {@code payload map $} as JSON, the success log.
 * <table>
 *   <caption>Flows and their methods</caption>
 *   <tr><th>Flow (lines)</th><th>Method</th><th>Original search</th></tr>
 *   <tr><td>{@code get:/customers:netsuite-api-config} (25-53)</td><td>{@link #getCustomers(String)}</td>
 *       <td>{@code netsuite:search searchRecord="CUSTOMER"}</td></tr>
 *   <tr><td>{@code get:/items:netsuite-api-config} (54-78)</td><td>{@link #getItems(int, String)}</td>
 *       <td>{@code netsuite:search searchRecord="ITEM_SUPPLY_PLAN_BASIC"}</td></tr>
 *   <tr><td>{@code get:/oportunities:netsuite-api-config} (79-106)</td><td>{@link #getOpportunities(String)}</td>
 *       <td>{@code netsuite:search searchRecord="OPPORTUNITY"}</td></tr>
 * </table>
 * The RAML path {@code /opportunities} governs the endpoint; only the log text keeps the flow's spelling.
 *
 * <p>This class owns the three {@code %dw 1.0} criteria scripts ({@link #customerCriteria(String)},
 * {@link #itemSupplyPlanCriteria(int, String)}, {@link #opportunityCriteria(String)}), and each search reads its
 * filter back from the criteria it built. The SuiteTalk search is replaced by the NetSuite REST API (SC-16):
 * <ul>
 *   <li>customers and opportunities: SuiteQL selects the matching IDs ({@link #customerIdQuery(String)},
 *       {@link #opportunityIdQuery(String)}), then each record is read with its sub-resources expanded, in ID
 *       order;</li>
 *   <li>item supply plans: every listed plan is read with its sub-resources expanded and kept when any line of its
 *       {@code order} sublist satisfies the quantity comparison, because quantity exists only on those lines and
 *       REST filters body fields only.</li>
 * </ul>
 * The records are passed unchanged to the owning mapper with the record type's metadata catalog and the source IDs
 * of the custom select fields they hold.
 *
 * <p>Callers: {@code CustomersController} passes the {@code name} query parameter, {@code ItemsController} the
 * {@code quantity} (RAML default 0) and {@code operator} (RAML default {@code GREATER_THAN}, enum checked by
 * SC-3 before this class is reached) query parameters, {@code OpportunitiesController} the {@code title} query
 * parameter; each answers the returned text with 200 {@code application/json} and declares
 * {@code throws Exception}.
 *
 * <p>The flows declare no exception strategy, so nothing is caught or retried here: the SC-9 upstream exceptions
 * reach {@code UpstreamExceptionHandler} (502/429/503) and every other exception, the record-shape
 * {@link IllegalStateException} for an unresolved select source included, reaches SC-1's 500 unchanged. Retry and
 * re-authentication exist only in {@code UpstreamCallExecutor}.
 *
 * <p>Stateless and thread-safe.
 */
@Service
public class NetsuiteSearchService {

    /** REST record type of customers. */
    public static final String CUSTOMER = "customer";

    /** REST record type of item supply plans. */
    public static final String ITEM_SUPPLY_PLAN = "itemsupplyplan";

    /** REST record type of opportunities. */
    public static final String OPPORTUNITY = "opportunity";

    /** The operator of the customer and opportunity criteria. */
    public static final String STARTS_WITH = "STARTS_WITH";

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteSearchService.class);

    private static final String CUSTOMERS_LOGGED = "Get customers completes successfully.";

    private static final String ITEMS_LOGGED = "Get items completes successfully.";

    // The original flow's spelling, kept verbatim.
    private static final String OPPORTUNITIES_LOGGED = "Get oportunities completes successfully.";

    private static final String BASIC = "basic";

    private static final String COMPANY_NAME = "companyName";

    private static final String TITLE = "title";

    private static final String QUANTITY = "quantity";

    private static final String OPERATOR = "operator";

    private static final String SEARCH_VALUE = "searchValue";

    private static final String ID = "id";

    private static final String ORDER = "order";

    private static final String ITEMS = "items";

    private final NetsuiteRestClient client;

    private final CustomerSearchMapper customerSearchMapper;

    private final ItemSupplyPlanMapper itemSupplyPlanMapper;

    private final OpportunitySearchMapper opportunitySearchMapper;

    private final NetsuiteRecordShapeMapper recordShapeMapper;

    /**
     * Creates the service.
     *
     * @param client                  the NetSuite REST client (SC-16)
     * @param customerSearchMapper    the customers {@code payload map $} script
     * @param itemSupplyPlanMapper    the items {@code payload map $} script
     * @param opportunitySearchMapper the opportunities {@code payload map $} script
     * @param recordShapeMapper       names the custom select fields whose source IDs the mappers need
     */
    public NetsuiteSearchService(NetsuiteRestClient client, CustomerSearchMapper customerSearchMapper,
            ItemSupplyPlanMapper itemSupplyPlanMapper, OpportunitySearchMapper opportunitySearchMapper,
            NetsuiteRecordShapeMapper recordShapeMapper) {
        this.client = client;
        this.customerSearchMapper = customerSearchMapper;
        this.itemSupplyPlanMapper = itemSupplyPlanMapper;
        this.opportunitySearchMapper = opportunitySearchMapper;
        this.recordShapeMapper = recordShapeMapper;
    }

    /**
     * Runs {@code get:/customers}: the customers whose company name starts with {@code name}, or every customer.
     *
     * @param name the {@code name} query parameter; {@code null} when absent, while an empty value still filters
     * @return the customers as DataWeave JSON, {@code []} when none matches
     * @throws Exception the client's and the mappers' exceptions, unchanged
     */
    public String getCustomers(String name) throws Exception {
        Map<String, Object> criteria = customerCriteria(name);
        List<Map<String, Object>> rows = client.suiteQl(customerIdQuery(startsWithValue(criteria, COMPANY_NAME)));
        String json = render(CUSTOMER, readRecords(CUSTOMER, rows), customerSearchMapper::toJson);
        LOG.info(CUSTOMERS_LOGGED);
        return json;
    }

    /**
     * Runs {@code get:/items}: the item supply plans with at least one order line whose quantity compares to
     * {@code quantity} by {@code operator}.
     *
     * @param quantity the {@code quantity} query parameter
     * @param operator the {@code operator} query parameter, one of {@code LESS_THAN}, {@code GREATER_THAN},
     *                 {@code EQUAL_TO}, {@code LESS_THAN_OR_EQUAL_TO}, {@code GREATER_THAN_OR_EQUAL_TO}
     * @return the kept plans as DataWeave JSON, {@code []} when none is kept
     * @throws IllegalArgumentException when {@code operator} is none of the five, before NetSuite is called
     * @throws Exception                the client's and the mapper's exceptions, unchanged
     */
    public String getItems(int quantity, String operator) throws Exception {
        Map<?, ?> condition = (Map<?, ?>) itemSupplyPlanCriteria(quantity, operator).get(QUANTITY);
        QuantityOperator comparison = QuantityOperator.of(condition.get(OPERATOR));
        BigDecimal target = decimal(condition.get(SEARCH_VALUE));
        List<Map<String, Object>> kept = new ArrayList<>();
        for (String id : client.listRecordIds(ITEM_SUPPLY_PLAN)) {
            Map<String, Object> plan = client.getRecord(ITEM_SUPPLY_PLAN, id);
            if (hasMatchingLine(plan, comparison, target)) {
                kept.add(plan);
            }
        }
        String json = render(ITEM_SUPPLY_PLAN, kept, itemSupplyPlanMapper::toJson);
        LOG.info(ITEMS_LOGGED);
        return json;
    }

    /**
     * Runs {@code get:/oportunities}: the opportunities whose title starts with {@code title}, or every
     * opportunity.
     *
     * @param title the {@code title} query parameter; {@code null} when absent, while an empty value still filters
     * @return the opportunities as DataWeave JSON, {@code []} when none matches
     * @throws Exception the client's and the mappers' exceptions, unchanged
     */
    public String getOpportunities(String title) throws Exception {
        Map<String, Object> criteria = opportunityCriteria(title);
        List<Map<String, Object>> rows = client.suiteQl(opportunityIdQuery(startsWithValue(criteria, TITLE)));
        String json = render(OPPORTUNITY, readRecords(OPPORTUNITY, rows), opportunitySearchMapper::toJson);
        LOG.info(OPPORTUNITIES_LOGGED);
        return json;
    }

    /**
     * The customer criteria script (lines 27-39): {@code {basic: {companyName: {operator: "STARTS_WITH",
     * searchValue: name}}}}, the {@code companyName} entry present only when {@code name != null}.
     *
     * @param name the company name prefix, or {@code null}
     * @return the criteria, every level a new {@link LinkedHashMap}
     */
    public Map<String, Object> customerCriteria(String name) {
        return basicStartsWith(COMPANY_NAME, name);
    }

    /**
     * The opportunity criteria script (lines 81-95): {@code {basic: {title: {operator: "STARTS_WITH",
     * searchValue: title}}}}, the {@code title} entry present only when {@code title != null}.
     *
     * @param title the title prefix, or {@code null}
     * @return the criteria, every level a new {@link LinkedHashMap}
     */
    public Map<String, Object> opportunityCriteria(String title) {
        return basicStartsWith(TITLE, title);
    }

    /**
     * The item supply plan criteria script (lines 56-66): {@code {quantity: {operator: operator,
     * searchValue: quantity}}}, the quantity already the number {@code as :number} produced.
     *
     * @param quantity the quantity to compare order lines with
     * @param operator the comparison operator name, kept as given
     * @return the criteria, every level a new {@link LinkedHashMap}
     */
    public Map<String, Object> itemSupplyPlanCriteria(int quantity, String operator) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put(QUANTITY, condition(operator, Integer.valueOf(quantity)));
        return criteria;
    }

    /**
     * The SuiteQL selecting the IDs of the customers whose company name starts with {@code prefix}.
     *
     * @param prefix the company name prefix, or {@code null} for every customer
     * @return the SuiteQL statement
     */
    public static String customerIdQuery(String prefix) {
        if (prefix == null) {
            return "SELECT id FROM customer ORDER BY id";
        }
        // Only the quote is escaped: % and _ in a prefix keep their LIKE wildcard meaning.
        return "SELECT id FROM customer WHERE companyname LIKE '" + NetsuiteRestClient.suiteQlLiteral(prefix)
                + "%' ORDER BY id";
    }

    /**
     * The SuiteQL selecting the IDs of the opportunities whose title starts with {@code prefix}.
     *
     * @param prefix the title prefix, or {@code null} for every opportunity
     * @return the SuiteQL statement
     */
    public static String opportunityIdQuery(String prefix) {
        if (prefix == null) {
            return "SELECT id FROM transaction WHERE type = 'Opprtnty' ORDER BY id";
        }
        // Only the quote is escaped: % and _ in a prefix keep their LIKE wildcard meaning.
        return "SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE '"
                + NetsuiteRestClient.suiteQlLiteral(prefix) + "%' ORDER BY id";
    }

    private static Map<String, Object> basicStartsWith(String field, String value) {
        Map<String, Object> basic = new LinkedHashMap<>();
        if (value != null) {
            basic.put(field, condition(STARTS_WITH, value));
        }
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put(BASIC, basic);
        return criteria;
    }

    private static Map<String, Object> condition(String operator, Object searchValue) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(OPERATOR, operator);
        condition.put(SEARCH_VALUE, searchValue);
        return condition;
    }

    /** The {@code searchValue} of a {@code basic} criterion, or {@code null} when the criteria hold none. */
    private static String startsWithValue(Map<String, Object> criteria, String field) {
        Map<?, ?> condition = (Map<?, ?>) ((Map<?, ?>) criteria.get(BASIC)).get(field);
        return condition == null ? null : (String) condition.get(SEARCH_VALUE);
    }

    /** Reads the record of each SuiteQL row, in row order. */
    private List<Map<String, Object>> readRecords(String type, List<Map<String, Object>> rows) {
        List<Map<String, Object>> records = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object id = row.get(ID);
            if (id == null) {
                throw new IllegalStateException("SuiteQL row without id");
            }
            records.add(client.getRecord(type, String.valueOf(id)));
        }
        return records;
    }

    /** Whether any line of the plan's {@code order} sublist has a quantity satisfying the comparison. */
    private static boolean hasMatchingLine(Map<String, Object> plan, QuantityOperator comparison,
            BigDecimal target) {
        if (!(plan.get(ORDER) instanceof Map<?, ?> order) || !(order.get(ITEMS) instanceof List<?> lines)) {
            return false;
        }
        for (Object line : lines) {
            if (line instanceof Map<?, ?> fields && fields.get(QUANTITY) != null
                    && comparison.accepts(decimal(fields.get(QUANTITY)).compareTo(target))) {
                return true;
            }
        }
        return false;
    }

    /** A JSON number or numeric text as a {@link BigDecimal}, so {@code 5} and {@code 5.0} compare equal. */
    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value instanceof Number number ? number.toString() : String.valueOf(value));
    }

    /**
     * Writes the records through their mapper with the type's metadata catalog, read on every request, and the
     * source IDs of the custom select fields the records hold, looked up only when there are any.
     */
    private String render(String type, List<Map<String, Object>> records, SearchWriter writer) throws Exception {
        Map<String, Object> catalog = client.metadataCatalog(type);
        Set<String> scriptIds = recordShapeMapper.selectCustomFieldScriptIds(records, catalog);
        Map<String, String> sourceIds = scriptIds == null || scriptIds.isEmpty()
                ? Collections.emptyMap()
                : client.customFieldSourceIds(scriptIds);
        return writer.write(records, catalog, sourceIds);
    }

    /** The {@code toJson} method of a search mapper. */
    @FunctionalInterface
    private interface SearchWriter {

        String write(List<Map<String, Object>> records, Map<String, Object> catalog, Map<String, String> sourceIds)
                throws Exception;
    }

    /** The five operators of the RAML {@code operator} enum, applied to {@code lineQuantity.compareTo(target)}. */
    private enum QuantityOperator {
        LESS_THAN(cmp -> cmp < 0),
        GREATER_THAN(cmp -> cmp > 0),
        EQUAL_TO(cmp -> cmp == 0),
        LESS_THAN_OR_EQUAL_TO(cmp -> cmp <= 0),
        GREATER_THAN_OR_EQUAL_TO(cmp -> cmp >= 0);

        private final IntPredicate test;

        QuantityOperator(IntPredicate test) {
            this.test = test;
        }

        boolean accepts(int cmp) {
            return test.test(cmp);
        }

        static QuantityOperator of(Object name) {
            for (QuantityOperator operator : values()) {
                if (operator.name().equals(name)) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("Unsupported operator: " + name);
        }
    }
}
