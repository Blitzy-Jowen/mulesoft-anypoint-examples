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
 * Reimplements the {@code %dw 1.0} script of flow {@code get:/items:netsuite-api-config}
 * ({@code netsuite-data-retrieval/src/main/app/netsuite-api.xml} l.70, {@code %output application/json},
 * body {@code payload map $}), of which this class is the only owner (AAP 0.5.8). The original wrote each
 * {@code ITEM_SUPPLY_PLAN_BASIC} search result unchanged as DataWeave JSON; here each REST item supply plan is
 * rebuilt into that SuiteTalk record by {@link NetsuiteRecordShapeMapper} ({@code internalId} and the
 * references {@code item}, {@code location}, {@code units} only when set, {@code subsidiary}; the {@code order}
 * lines omitted, AAP 0.7.4) and the list is written by {@link DwJsonWriter}. The parity target is
 * {@code src/main/resources/api/items-response.json}.
 *
 * <p>The input holds only the plans {@code NetsuiteSearchService} kept after its order-line quantity
 * comparison; this class filters nothing. It is stateless and thread-safe.
 */
@Component
public class ItemSupplyPlanMapper {

    private final NetsuiteRecordShapeMapper shapeMapper;

    private final DwJsonWriter jsonWriter;

    /**
     * Creates the mapper.
     *
     * @param shapeMapper rebuilds each REST plan into its SuiteTalk shape
     * @param jsonWriter  writes the result in DataWeave 1.0 JSON layout
     */
    public ItemSupplyPlanMapper(NetsuiteRecordShapeMapper shapeMapper, DwJsonWriter jsonWriter) {
        this.shapeMapper = shapeMapper;
        this.jsonWriter = jsonWriter;
    }

    /**
     * Maps each REST item supply plan to its SuiteTalk record, keeping the input order as {@code map} does.
     * The returned records are the shape mapper's own maps, neither copied nor reordered, because their
     * {@code HashMap} iteration order is the committed key order.
     *
     * @param records   the REST item supply plans read with {@code expandSubResources=true}; {@code null} reads
     *                  as empty
     * @param catalog   the metadata catalog, passed through unchanged; the item supply plan shape does not read
     *                  it, so {@code null} is accepted
     * @param sourceIds custom select script ID → select source ID, passed through unchanged; {@code null} is
     *                  accepted
     * @return a new list with one SuiteTalk record per input plan; empty when there are none
     */
    public List<Map<String, Object>> map(List<Map<String, Object>> records, Map<String, Object> catalog,
                                         Map<String, String> sourceIds) {
        List<Map<String, Object>> plans = new ArrayList<>();
        if (records == null) {
            return plans;
        }
        for (Map<String, Object> record : records) {
            plans.add(shapeMapper.toSuiteTalk(NetsuiteRecordShapeMapper.RecordKind.ITEM_SUPPLY_PLAN, record,
                    catalog, sourceIds));
        }
        return plans;
    }

    /**
     * Renders the {@code GET /api/items} response body: {@link #map} written as DataWeave 1.0 JSON, so an empty
     * input gives {@code []}.
     *
     * @param records   the REST item supply plans; {@code null} reads as empty
     * @param catalog   the metadata catalog, passed through unchanged
     * @param sourceIds custom select script ID → select source ID, passed through unchanged
     * @return the JSON text, without a trailing line feed
     */
    public String toJson(List<Map<String, Object>> records, Map<String, Object> catalog,
                         Map<String, String> sourceIds) {
        return jsonWriter.write(map(records, catalog, sourceIds));
    }
}
