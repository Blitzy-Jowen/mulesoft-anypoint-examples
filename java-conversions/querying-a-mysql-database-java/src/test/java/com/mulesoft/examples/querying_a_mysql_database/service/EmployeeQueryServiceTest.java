/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.querying_a_mysql_database.client.EmployeeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.LinkedCaseInsensitiveMap;

/**
 * Unit test of {@link EmployeeQueryService}, which replaces the {@code db:select} and
 * {@code json:object-to-json-transformer} of flow {@code database-to-jsonFlow}
 * (querying-a-mysql-database/src/main/app/database-to-json.xml:5-11), with a mocked {@link EmployeeRepository}:
 * no Spring context and no database.
 *
 * <p>Expected JSON is written as literals taken from the original {@code QueryingMysqlDatabaseIT}
 * (querying-a-mysql-database/src/test/java/org/mule/examples/QueryingMysqlDatabaseIT.java:70-72), so the tests pin
 * the original reply bytes rather than re-deriving them with Jackson.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeQueryServiceTest {

    @Mock
    private EmployeeRepository repository;

    private EmployeeQueryService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeQueryService(repository, new ObjectMapper());
    }

    @Test
    void returnsOriginalReplyForPuckett() {
        // JdbcTemplate.queryForList returns LinkedCaseInsensitiveMap rows; the second proves any Map serializes alike.
        Map<String, Object> row1 = new LinkedCaseInsensitiveMap<>();
        row1.put("first_name", "Chava");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("first_name", "Quentin");
        when(repository.findFirstNamesByLastName("Puckett")).thenReturn(List.of(row1, row2));

        assertEquals("[{\"first_name\":\"Chava\"},{\"first_name\":\"Quentin\"}]",
                service.findFirstNamesAsJson("Puckett"));
        verify(repository).findFirstNamesByLastName("Puckett");
    }

    @Test
    void returnsEmptyArrayWhenNoRows() {
        when(repository.findFirstNamesByLastName("Nobody")).thenReturn(List.of());

        assertEquals("[]", service.findFirstNamesAsJson("Nobody"));
    }

    @Test
    void nullLastnameIsRenderedAsNullString() {
        // The unstubbed repository answers an empty list, as the original's comparison with the literal null did.
        assertEquals("[]", service.findFirstNamesAsJson(null));

        verify(repository).findFirstNamesByLastName(EmployeeQueryService.MISSING_VALUE);
        assertEquals("null", EmployeeQueryService.MISSING_VALUE);
    }

    @Test
    void emptyLastnameIsPassedThrough() {
        assertEquals("[]", service.findFirstNamesAsJson(""));

        // Neither rejected nor rendered as the missing value: the empty string itself reaches the query.
        verify(repository).findFirstNamesByLastName("");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void quoteBearingLastnameIsPassedThroughUnchanged() {
        String hostile = "Puckett\" or \"1\"=\"1";

        assertEquals("[]", service.findFirstNamesAsJson(hostile));

        // The value is bound as-is; neutralising it is the bind parameter's job, not an escape in the service.
        verify(repository).findFirstNamesByLastName(hostile);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void dataAccessExceptionPropagatesUnchanged() {
        DataAccessResourceFailureException ex = new DataAccessResourceFailureException("db down");
        when(repository.findFirstNamesByLastName("Puckett")).thenThrow(ex);

        // The original flow declares no exception strategy, so the failure must reach the global handler as thrown.
        assertSame(ex, assertThrows(DataAccessException.class, () -> service.findFirstNamesAsJson("Puckett")));
    }

    @Test
    void jsonProcessingExceptionIsWrappedInUncheckedIOException() throws JsonProcessingException {
        ObjectMapper failing = mock(ObjectMapper.class);
        JsonProcessingException boom = new JsonProcessingException("boom") {
        };
        when(failing.writeValueAsString(any())).thenThrow(boom);
        EmployeeQueryService failingService = new EmployeeQueryService(repository, failing);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> failingService.findFirstNamesAsJson("Puckett"));

        assertSame(boom, thrown.getCause());
        verify(repository).findFirstNamesByLastName("Puckett");
    }
}
