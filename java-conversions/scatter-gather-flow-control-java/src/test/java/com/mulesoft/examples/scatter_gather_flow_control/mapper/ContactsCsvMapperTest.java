/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.scatter_gather_flow_control.service.ContactMerge;

/**
 * Unit test of {@link ContactsCsvMapper}, the owner of the original {@code transform Collection of Maps to CSV Format}
 * script ({@code payload map { Id: $.Id, Name: $.Name, Email: $.Email }}, {@code %output application/csv}) of
 * {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml}.
 *
 * <p>The contacts are the original's two routes (A: 1 vlado, 2 michal; B: 1 peter, 2 vlado) built as the original's
 * expression components build them, as {@link HashMap}s. The merged contacts carry no {@code Id}, so the report holds
 * a blank {@code Id} column, and their {@code IDInA} and {@code IDInB} keys never reach the CSV.
 */
class ContactsCsvMapperTest {

    private static final String DEMO_CSV = "Id,Name,Email\n"
            + ",vlado,vlado@email.com\n"
            + ",michal,michal@email.com\n"
            + ",peter,peter@email.com\n";

    private final ContactsCsvMapper mapper = new ContactsCsvMapper(new DwCsvWriter());

    @Test
    @DisplayName("demo routes merged by ContactMerge -> the original aggregation report")
    void demoMergeProducesOriginalAggregationReport() {
        List<Map<String, String>> merged = new ContactMerge().mergeList(sourceA(), sourceB());

        assertThat(mapper.toCsv(merged)).isEqualTo(DEMO_CSV);
    }

    @Test
    @DisplayName("merged contacts: blank Id, Name and Email kept, IDInA and IDInB dropped, LF line ends")
    void literalMergedContactsWriteBlankIdAndDropMergeIds() {
        List<Map<String, String>> merged = List.of(
                merged("vlado", "vlado@email.com", "1", "2"),
                merged("michal", "michal@email.com", "2", ""),
                merged("peter", "peter@email.com", "", "1"));

        String csv = mapper.toCsv(merged);

        assertThat(csv).isEqualTo(DEMO_CSV)
                .startsWith("Id,Name,Email\n")
                .endsWith("\n")
                .doesNotContain("\r")
                .doesNotContain("IDInA")
                .doesNotContain("IDInB")
                .doesNotContain(",1,")
                .doesNotContain(",2\n");
        assertThat(csv.split("\n", -1)).hasSize(5);
    }

    @Test
    @DisplayName("contact with an Id -> the Id is written in the first column")
    void contactWithIdWritesIdColumn() {
        String csv = mapper.toCsv(List.of(contact("1", "vlado", "vlado@email.com")));

        assertThat(csv).isEqualTo("Id,Name,Email\n1,vlado,vlado@email.com\n");
    }

    @Test
    @DisplayName("columns are Id, Name, Email whatever the input map's key order")
    void columnOrderIsIdNameEmailRegardlessOfInputMapOrder() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("Email", "vlado@email.com");
        reversed.put("Name", "vlado");
        reversed.put("Id", "1");

        assertThat(mapper.toCsv(List.of(reversed))).isEqualTo("Id,Name,Email\n1,vlado,vlado@email.com\n");
    }

    // Route A of the original scatter-gather: 1 vlado, 2 michal, in an ArrayList as its expression component builds it.
    private static List<Map<String, String>> sourceA() {
        return new ArrayList<>(List.of(contact("1", "vlado", "vlado@email.com"),
                contact("2", "michal", "michal@email.com")));
    }

    // Route B of the original scatter-gather: 1 peter, 2 vlado, in an ArrayList as its expression component builds it.
    private static List<Map<String, String>> sourceB() {
        return new ArrayList<>(List.of(contact("1", "peter", "peter@email.com"),
                contact("2", "vlado", "vlado@email.com")));
    }

    private static Map<String, String> contact(String id, String name, String email) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Id", id);
        contact.put("Name", name);
        contact.put("Email", email);
        return contact;
    }

    // A contact as ContactMerge builds it: no Id key.
    private static Map<String, String> merged(String name, String email, String idInA, String idInB) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Name", name);
        contact.put("Email", email);
        contact.put("IDInA", idInA);
        contact.put("IDInB", idInB);
        return contact;
    }
}
