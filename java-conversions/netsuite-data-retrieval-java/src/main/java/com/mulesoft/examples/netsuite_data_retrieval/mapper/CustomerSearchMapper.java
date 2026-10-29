/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Reimplements the response transform of the original {@code get:/customers} flow, the script whose
 * {@code %dw 1.0} header is line 44 of {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}:
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/json
 * ---
 * payload map $
 * }</pre>
 *
 * <p>The script maps every customer the {@code netsuite:search searchRecord="CUSTOMER"} operation returned to
 * itself, keeping the search order, and writes the list as DataWeave JSON. This class is that script's only owner
 * (AAP 0.5.8). It holds no field rules: {@link NetsuiteRecordShapeMapper} rebuilds each SuiteTalk customer from
 * its NetSuite REST record (AAP 0.7.4) and {@link DwJsonWriter} produces the bytes, so the committed example
 * {@code src/main/resources/api/customers-response.json} is reproduced exactly. The search criteria of the same
 * flow belong to {@code NetsuiteSearchService.customerCriteria}.
 *
 * <p>The instance is stateless beyond its two thread-safe collaborators and is safe to share.
 */
@Component
public class CustomerSearchMapper {

    private final NetsuiteRecordShapeMapper shapeMapper;

    private final DwJsonWriter jsonWriter;

    /**
     * Creates the mapper.
     *
     * @param shapeMapper rebuilds the SuiteTalk customer of each REST record
     * @param jsonWriter  writes the result in DataWeave 1.0 JSON layout
     */
    public CustomerSearchMapper(NetsuiteRecordShapeMapper shapeMapper, DwJsonWriter jsonWriter) {
        this.shapeMapper = shapeMapper;
        this.jsonWriter = jsonWriter;
    }

    /**
     * Applies {@code payload map $} to the customers the search returned.
     *
     * <p>Each returned map is added as {@link NetsuiteRecordShapeMapper#toSuiteTalk} produced it, never copied,
     * because its {@code HashMap} iteration order is the committed key order. Failures of the shape mapper, such
     * as a custom select field without a resolved source ID, propagate unchanged.
     *
     * @param records   the REST customer records, in search order; {@code null} reads as empty
     * @param catalog   the customer metadata catalog, passed to the shape mapper unchanged
     * @param sourceIds custom select script ID → select source ID, passed to the shape mapper unchanged
     * @return a new list holding one SuiteTalk-shaped customer per record, in input order
     * @throws IllegalStateException when the shape mapper cannot rebuild a record
     */
    public List<Map<String, Object>> map(List<Map<String, Object>> records, Map<String, Object> catalog,
                                         Map<String, String> sourceIds) {
        List<Map<String, Object>> customers = new ArrayList<>();
        if (records == null) {
            return customers;
        }
        for (Map<String, Object> record : records) {
            customers.add(shapeMapper.toSuiteTalk(NetsuiteRecordShapeMapper.RecordKind.CUSTOMER, record, catalog,
                    sourceIds));
        }
        return customers;
    }

    /**
     * Applies {@code payload map $} and writes the result as the original {@code %output application/json} did.
     *
     * @param records   the REST customer records, in search order; {@code null} reads as empty
     * @param catalog   the customer metadata catalog
     * @param sourceIds custom select script ID → select source ID
     * @return the JSON array of SuiteTalk-shaped customers without a trailing line feed; {@code []} when there are
     *         none
     * @throws IllegalStateException when the shape mapper cannot rebuild a record
     */
    public String toJson(List<Map<String, Object>> records, Map<String, Object> catalog,
                         Map<String, String> sourceIds) {
        return jsonWriter.write(map(records, catalog, sourceIds));
    }
}
