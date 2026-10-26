/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test of {@link ContactRowMapper}, the owner of the {@code %dw 1.0} script of batch step
 * {@code queryExistingContactInDbStep} ({@code salesforce-to-database.xml:15-23}), whose {@code application/java}
 * output is the map {@code email}, {@code first_name}, {@code last_name}, {@code last_modified} read from the
 * Salesforce fields {@code Email}, {@code FirstName}, {@code LastName}, {@code LastModifiedDate}.
 *
 * <p>The sample record carries the values of the original Studio sample {@code sample_data/SfdcContactMap.dwl}, held
 * in a {@link HashMap} as the Salesforce client delivers each queried record.
 */
class ContactRowMapperTest {

    private static final String EMAIL_VALUE = "test@test.com";
    private static final String FIRST_NAME_VALUE = "TestFirstName";
    private static final String LAST_NAME_VALUE = "TestLastName";
    private static final String LAST_MODIFIED_VALUE = "10-10-2015";

    private ContactRowMapper mapper;

    @BeforeEach
    void createMapper() {
        mapper = new ContactRowMapper();
    }

    @Test
    void mapsSalesforceFieldsToDbColumnsInScriptOrder() {
        Map<String, Object> input = sampleContact();

        Map<String, Object> out = mapper.map(input);

        assertThat(out).isInstanceOf(LinkedHashMap.class).hasSize(4);
        assertThat(out.keySet()).containsExactly(ContactRowMapper.EMAIL, ContactRowMapper.FIRST_NAME,
                ContactRowMapper.LAST_NAME, ContactRowMapper.LAST_MODIFIED);
        assertThat(out)
                .containsEntry(ContactRowMapper.EMAIL, EMAIL_VALUE)
                .containsEntry(ContactRowMapper.FIRST_NAME, FIRST_NAME_VALUE)
                .containsEntry(ContactRowMapper.LAST_NAME, LAST_NAME_VALUE)
                .containsEntry(ContactRowMapper.LAST_MODIFIED, LAST_MODIFIED_VALUE);
        assertThat(out.get(ContactRowMapper.EMAIL)).isSameAs(input.get("Email"));
        assertThat(out.get(ContactRowMapper.FIRST_NAME)).isSameAs(input.get("FirstName"));
        assertThat(out.get(ContactRowMapper.LAST_NAME)).isSameAs(input.get("LastName"));
        assertThat(out.get(ContactRowMapper.LAST_MODIFIED)).isSameAs(input.get("LastModifiedDate"));
    }

    @Test
    void missingOrNullSourceFieldsBecomeNullValuedKeys() {
        Map<String, Object> input = sampleContact();
        input.remove("FirstName");
        input.put("LastModifiedDate", null);

        Map<String, Object> out = mapper.map(input);

        assertThat(out).hasSize(4)
                .containsKey(ContactRowMapper.FIRST_NAME)
                .containsEntry(ContactRowMapper.FIRST_NAME, null)
                .containsEntry(ContactRowMapper.LAST_MODIFIED, null)
                .containsEntry(ContactRowMapper.EMAIL, EMAIL_VALUE)
                .containsEntry(ContactRowMapper.LAST_NAME, LAST_NAME_VALUE);

        Map<String, Object> fromEmpty = mapper.map(new HashMap<>());

        assertThat(fromEmpty.keySet()).containsExactly(ContactRowMapper.EMAIL, ContactRowMapper.FIRST_NAME,
                ContactRowMapper.LAST_NAME, ContactRowMapper.LAST_MODIFIED);
        assertThat(fromEmpty.values()).containsOnlyNulls();
    }

    @Test
    void doesNotMutateInput() {
        Map<String, Object> input = sampleContact();
        Map<String, Object> before = new HashMap<>(input);

        Map<String, Object> out = mapper.map(input);

        assertThat(input).isEqualTo(before);
        assertThat(out).isNotSameAs(input);
    }

    @Test
    void ignoresExtraInputKeys() {
        Map<String, Object> input = sampleContact();
        input.put("Id", "003xx000004TmiQ");
        input.put("type", "Contact");
        input.put("Phone", "555-0100");

        Map<String, Object> out = mapper.map(input);

        assertThat(out).hasSize(4).doesNotContainKeys("Id", "type", "Phone");
    }

    @Test
    void nullInputThrowsNullPointerException() {
        assertThatThrownBy(() -> mapper.map(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constantsMatchColumnNames() {
        assertThat(ContactRowMapper.EMAIL).isEqualTo("email");
        assertThat(ContactRowMapper.FIRST_NAME).isEqualTo("first_name");
        assertThat(ContactRowMapper.LAST_NAME).isEqualTo("last_name");
        assertThat(ContactRowMapper.LAST_MODIFIED).isEqualTo("last_modified");
    }

    private static Map<String, Object> sampleContact() {
        Map<String, Object> contact = new HashMap<>();
        contact.put("Email", EMAIL_VALUE);
        contact.put("FirstName", FIRST_NAME_VALUE);
        contact.put("LastName", LAST_NAME_VALUE);
        contact.put("LastModifiedDate", LAST_MODIFIED_VALUE);
        return contact;
    }
}
