/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client.CsvMailSender;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.DwCsvReader;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.OrderLinesMapper;

/**
 * Service test of {@link CsvEmailService}, the processor chain of the original {@code csv-to-smtpFlow}
 * (csv-to-smtp.xml:6-20): CSV read and DataWeave mapping, the INFO logger, then the SMTP outbound endpoint. The
 * reader, mapper and mail sender are Mockito mocks and no Spring context is started.
 *
 * <p>The flow has a single route and no exception strategy, so the cases are the one happy path, the logged payload
 * rendering, an empty file, and a failure at each of the three collaborators propagating unchanged.
 */
@ExtendWith(MockitoExtension.class)
class CsvEmailServiceTest {

    /** The original sample {@code src/main/resources/input.csv}. */
    private static final String INPUT_CSV = "orderId,name,units,pricePerUnit\n1,aaa,2.0,10\n2,bbb,4.15,5";

    /** The committed {@code email.txt}: the mail body, and payload rendering, the original test expected. */
    private static final String EMAIL_BODY = "[{name=aaa, orderId=1, pricePerUnit=10, units=2, totalPrice=20}, "
            + "{name=bbb, orderId=2, pricePerUnit=5, units=4.15, totalPrice=20.75}]";

    /** Fixed text of {@code logger message="Processing objects: #[payload]"} (csv-to-smtp.xml:19). */
    private static final String LOG_PREFIX = "Processing objects: ";

    @Mock
    private DwCsvReader reader;

    @Mock
    private OrderLinesMapper mapper;

    @Mock
    private CsvMailSender sender;

    private CsvEmailService service;

    private Logger serviceLogger;

    private Level savedLevel;

    private ListAppender<ILoggingEvent> appender;

    private byte[] csv;

    private List<Map<String, String>> rows;

    private List<Map<String, Object>> lines;

    @BeforeEach
    void setUp() {
        service = new CsvEmailService(reader, mapper, sender);

        serviceLogger = (Logger) LoggerFactory.getLogger(CsvEmailService.class);
        savedLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        csv = INPUT_CSV.getBytes(UTF_8);
        rows = List.of(row("1", "aaa", "2.0", "10"), row("2", "bbb", "4.15", "5"));
        lines = new ArrayList<>(List.of(
                line("aaa", "1", "10", "2", "20"),
                line("bbb", "2", "5", "4.15", "20.75")));
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(savedLevel);
    }

    @Test
    void processReadsMapsLogsAndSendsInOrder() {
        when(reader.read(same(csv))).thenReturn(rows);
        when(mapper.map(same(rows))).thenReturn(lines);

        List<Map<String, Object>> result = service.process(csv);

        InOrder inOrder = inOrder(reader, mapper, sender);
        inOrder.verify(reader).read(same(csv));
        inOrder.verify(mapper).map(same(rows));
        inOrder.verify(sender).send(same(lines));
        assertThat(result).isSameAs(lines);
        // Also proves neither the read(byte[], Charset) nor the read(String) overload is used.
        verifyNoMoreInteractions(reader, mapper, sender);
    }

    @Test
    void processLogsMappedObjectsAtInfo() {
        when(reader.read(same(csv))).thenReturn(rows);
        when(mapper.map(same(rows))).thenReturn(lines);
        AtomicInteger eventsAtSend = new AtomicInteger(-1);
        doAnswer(invocation -> {
            eventsAtSend.set(appender.list.size());
            return null;
        }).when(sender).send(same(lines));

        service.process(csv);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getLoggerName()).isEqualTo(CsvEmailService.class.getName());
        assertThat(event.getFormattedMessage())
                .isEqualTo(LOG_PREFIX + lines)
                .isEqualTo(LOG_PREFIX + EMAIL_BODY);
        assertThat(eventsAtSend.get())
                .as("the logger runs before the SMTP send, as in the original flow")
                .isEqualTo(1);
    }

    @Test
    void mapperFailurePropagatesUnchangedAndNothingIsSent() {
        IllegalArgumentException failure = new IllegalArgumentException("units is not a number: abc");
        when(reader.read(same(csv))).thenReturn(rows);
        when(mapper.map(same(rows))).thenThrow(failure);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.process(csv));

        assertThat(thrown).isSameAs(failure);
        verify(sender, never()).send(any());
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().startsWith(LOG_PREFIX));
    }

    @Test
    void readerFailurePropagatesUnchanged() {
        RuntimeException failure = new RuntimeException("unreadable csv");
        when(reader.read(same(csv))).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.process(csv));

        assertThat(thrown).isSameAs(failure);
        verifyNoInteractions(mapper, sender);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void senderFailurePropagatesWithoutRetry() {
        MailSendException failure = new MailSendException("smtp down");
        when(reader.read(same(csv))).thenReturn(rows);
        when(mapper.map(same(rows))).thenReturn(lines);
        doThrow(failure).when(sender).send(same(lines));

        MailSendException thrown = assertThrows(MailSendException.class, () -> service.process(csv));

        assertThat(thrown).isSameAs(failure);
        verify(sender, times(1)).send(any());
        assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).isEqualTo(LOG_PREFIX + EMAIL_BODY);
                });
    }

    @Test
    void emptyCsvStillSendsEmptyList() {
        byte[] empty = new byte[0];
        List<Map<String, String>> noRows = List.of();
        List<Map<String, Object>> noLines = new ArrayList<>();
        when(reader.read(same(empty))).thenReturn(noRows);
        when(mapper.map(same(noRows))).thenReturn(noLines);

        List<Map<String, Object>> result = service.process(empty);

        assertThat(result).isSameAs(noLines).isEmpty();
        verify(sender, times(1)).send(same(noLines));
        verifyNoMoreInteractions(sender);
        assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).isEqualTo(LOG_PREFIX + "[]");
                });
    }

    /** A {@link DwCsvReader} record of the original header {@code orderId,name,units,pricePerUnit}. */
    private static Map<String, String> row(String orderId, String name, String units, String pricePerUnit) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("orderId", orderId);
        row.put("name", name);
        row.put("units", units);
        row.put("pricePerUnit", pricePerUnit);
        return row;
    }

    /** An {@link OrderLinesMapper} line, keyed in the DataWeave script's order with {@link BigDecimal} numbers. */
    private static Map<String, Object> line(String name, String orderId, String pricePerUnit, String units,
                                            String totalPrice) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("orderId", orderId);
        line.put("pricePerUnit", new BigDecimal(pricePerUnit));
        line.put("units", new BigDecimal(units));
        line.put("totalPrice", new BigDecimal(totalPrice));
        return line;
    }
}
