/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Hand-written owner of the {@code transform Collection of Maps to CSV Format} script, and of the
 * {@code object-to-string-transformer} after it, in
 * {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml} (lines 45-57):
 * <pre>{@code
 * %dw 1.0
 * %output application/csv
 * ---
 * payload map {
 * 	Id      : $.Id,
 * 	Name    : $.Name,
 * 	Email   : $.Email
 * }
 * }</pre>
 *
 * <p>Each contact becomes one record holding {@code Id}, {@code Name} and {@code Email}, in that order; every other
 * key ({@code IDInA}, {@code IDInB}) is dropped. The merged contacts built by {@code ContactMerge} carry no
 * {@code Id} key, and DataWeave reads a missing key as {@code null}, which the CSV writer renders as an empty field,
 * so the demo routes produce:
 * {@code Id,Name,Email\n,vlado,vlado@email.com\n,michal,michal@email.com\n,peter,peter@email.com\n}.
 *
 * <p>The column order comes from the record built here, never from the input map, whose {@code HashMap} key order
 * differs from the script's. The CSV bytes are those of SC-4 {@link DwCsvWriter}, and the result is already a
 * {@link String}, which is all the {@code object-to-string-transformer} contributed.
 *
 * <p>The class is stateless and thread-safe.
 */
@Component
public class ContactsCsvMapper {

    private static final String ID = "Id";
    private static final String NAME = "Name";
    private static final String EMAIL = "Email";

    private final DwCsvWriter dwCsvWriter;

    /**
     * Creates the mapper.
     *
     * @param dwCsvWriter the SC-4 writer producing DataWeave 1.0 CSV bytes
     */
    public ContactsCsvMapper(DwCsvWriter dwCsvWriter) {
        this.dwCsvWriter = Objects.requireNonNull(dwCsvWriter, "dwCsvWriter");
    }

    /**
     * Applies the script to the merged contacts and writes the result as DataWeave 1.0 CSV, with a header row.
     *
     * @param contacts the merged contact list, the script's {@code payload}, in the order the records are written
     * @return the CSV text, every line ending with a line feed; whatever {@link DwCsvWriter#write(List)} writes for no
     *         records when {@code contacts} is empty
     * @throws NullPointerException when {@code contacts} or one of its elements is {@code null}
     */
    public String toCsv(List<Map<String, String>> contacts) {
        List<Map<String, String>> rows = new ArrayList<>(contacts.size());
        for (Map<String, String> contact : contacts) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put(ID, contact.get(ID));
            row.put(NAME, contact.get(NAME));
            row.put(EMAIL, contact.get(EMAIL));
            rows.add(row);
        }
        return dwCsvWriter.write(rows);
    }
}
