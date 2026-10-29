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
import java.util.Objects;

import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteRecordShapeMapper.RecordKind;

import org.springframework.stereotype.Component;

/**
 * Reimplements the opportunities output script of {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}
 * (header at l.99), the sole owner of that script (AAP 0.5.8):
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/json
 * ---
 * payload map $
 * }</pre>
 *
 * <p>The script runs in the flow {@code get:/oportunities:netsuite-api-config}; the misspelling is the original
 * flow name, while the RAML path {@code /opportunities} governs the endpoint (AAP 0.7.1). It wrote each record of
 * the {@code netsuite:search searchRecord="OPPORTUNITY"} result unchanged as a JSON array element. The conversion
 * reads REST records instead (AAP 0.7.4), so each element is first rebuilt as the SuiteTalk opportunity the
 * connector returned by {@link NetsuiteRecordShapeMapper} ({@link RecordKind#OPPORTUNITY}: {@code status} kept as
 * text, the 15-key addresses with nulls kept, references, numbers, booleans and account-zone dates), and the array
 * is written by {@link DwJsonWriter}. This class composes the two and adds no shape rule of its own.
 *
 * <p>The parity target is {@code src/main/resources/api/opportunities-response.json}. Its two {@code addrText}
 * values hold raw line feeds, which {@link DwJsonWriter} escapes; that difference is the writer's, not handled here.
 *
 * <p>Stateless apart from its two thread-safe collaborators, so one instance serves every request.
 */
@Component
public class OpportunitySearchMapper {

    private final NetsuiteRecordShapeMapper shapeMapper;

    private final DwJsonWriter jsonWriter;

    /**
     * Creates the mapper over the shared record-shape rules and the DataWeave JSON writer.
     *
     * @param shapeMapper the REST → SuiteTalk record shape rules
     * @param jsonWriter  the DataWeave 1.0 JSON writer (SC-4)
     * @throws NullPointerException when either collaborator is {@code null}
     */
    public OpportunitySearchMapper(NetsuiteRecordShapeMapper shapeMapper, DwJsonWriter jsonWriter) {
        this.shapeMapper = Objects.requireNonNull(shapeMapper, "shapeMapper");
        this.jsonWriter = Objects.requireNonNull(jsonWriter, "jsonWriter");
    }

    /**
     * Applies {@code payload map $}: one SuiteTalk-shaped opportunity per REST record, in input order.
     *
     * @param records   the opportunity REST records, each read with {@code expandSubResources=true}; {@code null}
     *                  reads as empty
     * @param catalog   the opportunity metadata catalog ({@code metadataCatalog("opportunity")}), passed through
     * @param sourceIds custom select script ID → select source ID for any {@code custbody*} select field, passed
     *                  through
     * @return a new list of the shape mapper's results, each kept as returned: its {@code HashMap} iteration order
     *         is the committed key order
     * @throws IllegalStateException when the shape mapper cannot resolve a custom select {@code typeId} or an
     *         address country code; it propagates unchanged so the request fails with the SC-1 500
     */
    public List<Map<String, Object>> map(List<Map<String, Object>> records, Map<String, Object> catalog,
                                         Map<String, String> sourceIds) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> opportunities = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            opportunities.add(shapeMapper.toSuiteTalk(RecordKind.OPPORTUNITY, record, catalog, sourceIds));
        }
        return opportunities;
    }

    /**
     * Applies {@code payload map $} and writes the result as {@code %output application/json} did.
     *
     * @param records   the opportunity REST records; {@code null} reads as empty
     * @param catalog   the opportunity metadata catalog, passed through
     * @param sourceIds custom select script ID → select source ID, passed through
     * @return the JSON array text, {@code []} when there are no records
     * @throws IllegalStateException as {@link #map(List, Map, Map)} does
     */
    public String toJson(List<Map<String, Object>> records, Map<String, Object> catalog,
                         Map<String, String> sourceIds) {
        return jsonWriter.write(map(records, catalog, sourceIds));
    }
}
