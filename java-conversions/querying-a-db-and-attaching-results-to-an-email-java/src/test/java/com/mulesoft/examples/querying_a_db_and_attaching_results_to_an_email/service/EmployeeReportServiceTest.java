/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.xml.sax.SAXParseException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.DwCsvWriter;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper;

/**
 * Unit test of {@link EmployeeReportService}, flow {@code attachmentsFlow1} of the original {@code attachments.xml},
 * with Mockito-mocked {@link EmployeeRepository} and {@link ReportMailSender} and the real {@link EmployeeCsvMapper}
 * over {@link DwCsvWriter}.
 *
 * <p>It covers the flow's processors in their original order: the {@code //employee} split in document order, the
 * per-employee lookup by the first two parts of {@code getText().split(" ")}, the aggregate logged at INFO, the CSV
 * written from it and mailed, and the CSV returned; the splitter's no-result warning, after which nothing is looked up
 * or mailed; and the failures the flow declares no exception strategy for (a body that is not well-formed XML, an
 * employee text without a space, a failed lookup, an undatable value, a failed send), each reaching the caller
 * unchanged and leaving every later step unexecuted. No Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeReportServiceTest {

    /** The original test request (src/test/resources/message.xml). */
    private static final String MESSAGE_XML = "/message.xml";

    private static final String HEADER = "first_name,last_name,gender,dob,hire_date\n";

    private static final String CHAVA_LINE = "Chava,Puckett,F,1985-09-02,2008-10-12\n";

    private static final String QUENTIN_LINE = "Quentin,Puckett,F,1971-10-21,2008-09-15\n";

    private static final String MONA_LINE = "Mona,Sosa,M,1950-09-26,2007-11-27\n";

    /** The CSV the flow mails for the original test request against the seeded {@code employees} rows. */
    private static final String CSV = HEADER + CHAVA_LINE + QUENTIN_LINE + MONA_LINE;

    /** The two rows the original test expected in the mail (QueryingDbAndAttachingResultsToAnEmailIT:52-53). */
    private static final String REPLY_1 = "Chava,Puckett,F,1985-09-02,2008-10-12";

    private static final String REPLY_2 = "Mona,Sosa,M,1950-09-26,2007-11-27";

    /** Seeded {@code employees} rows (import_orders.sql), as {@link EmployeeRepository} returns them. */
    private static final Map<String, Object> CHAVA =
            row("Chava", "Puckett", "F", LocalDate.of(1985, 9, 2), LocalDate.of(2008, 10, 12));

    private static final Map<String, Object> QUENTIN =
            row("Quentin", "Puckett", "F", LocalDate.of(1971, 10, 21), LocalDate.of(2008, 9, 15));

    private static final Map<String, Object> MONA =
            row("Mona", "Sosa", "M", LocalDate.of(1950, 9, 26), LocalDate.of(2007, 11, 27));

    @Mock
    EmployeeRepository repository;

    @Mock
    ReportMailSender mailSender;

    EmployeeReportService service;

    Logger serviceLogger;

    Level previousLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createServiceAndCaptureItsLog() {
        service = new EmployeeReportService(repository, new EmployeeCsvMapper(new DwCsvWriter()), mailSender);
        serviceLogger = (Logger) LoggerFactory.getLogger(EmployeeReportService.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    // 1
    @Test
    void originalRequestLooksUpEachEmployeeInOrderLogsTheAggregateAndMailsTheCsv() throws Exception {
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(CHAVA));
        when(repository.findByFirstNameAndLastName("Quentin", "Puckett")).thenReturn(List.of(QUENTIN));
        when(repository.findByFirstNameAndLastName("Mona", "Sosa")).thenReturn(List.of(MONA));

        Optional<String> result = service.generateAndSend(classpath(MESSAGE_XML));

        assertThat(result).hasValue(CSV);
        assertThat(result.get()).contains(REPLY_1, REPLY_2);
        InOrder order = inOrder(repository, mailSender);
        order.verify(repository).findByFirstNameAndLastName("Chava", "Puckett");
        order.verify(repository).findByFirstNameAndLastName("Quentin", "Puckett");
        order.verify(repository).findByFirstNameAndLastName("Mona", "Sosa");
        order.verify(mailSender).send(CSV);
        verifyNoMoreInteractions(repository, mailSender);
        assertThat(logged(Level.INFO))
                .containsExactly(String.valueOf(List.of(List.of(CHAVA), List.of(QUENTIN), List.of(MONA))));
        assertThat(logged(Level.WARN)).isEmpty();
    }

    // 2
    @Test
    void employeesAreLookedUpInDocumentOrderAtAnyDepthAndEachReadsItsOwnTextOnly() throws Exception {
        when(repository.findByFirstNameAndLastName("Mona", "Sosa")).thenReturn(List.of(MONA));
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(CHAVA));
        when(repository.findByFirstNameAndLastName("Quentin", "Puckett")).thenReturn(List.of(QUENTIN));
        String xml = "<root><employee>Mona Sosa</employee>"
                + "<dept><employee>Chava Puckett<employee>Quentin Puckett</employee></employee></dept></root>";

        Optional<String> result = service.generateAndSend(xml.getBytes(UTF_8));

        String csv = HEADER + MONA_LINE + CHAVA_LINE + QUENTIN_LINE;
        assertThat(result).hasValue(csv);
        InOrder order = inOrder(repository, mailSender);
        order.verify(repository).findByFirstNameAndLastName("Mona", "Sosa");
        order.verify(repository).findByFirstNameAndLastName("Chava", "Puckett");
        order.verify(repository).findByFirstNameAndLastName("Quentin", "Puckett");
        order.verify(mailSender).send(csv);
        verifyNoMoreInteractions(repository, mailSender);
    }

    /** Bodies in which {@code //employee} selects nothing, under namespace-aware parsing. */
    static Stream<Arguments> bodiesWithoutAnEmployee() {
        return Stream.of(
                Arguments.of("root element only", "<root/>"),
                Arguments.of("empty employees list", "<root><employees/></root>"),
                Arguments.of("element names are case-sensitive", "<root><Employee>Chava Puckett</Employee></root>"),
                Arguments.of("an attribute is not an element", "<root employee=\"Chava Puckett\"/>"),
                Arguments.of("employee in a default namespace",
                        "<root xmlns=\"urn:example\"><employee>Chava Puckett</employee></root>"),
                Arguments.of("employee in a prefixed namespace",
                        "<e:root xmlns:e=\"urn:example\"><e:employee>Chava Puckett</e:employee></e:root>"));
    }

    // 3
    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesWithoutAnEmployee")
    void bodyWithoutAnEmployeeEndsTheFlowWithTheSplitterWarning(String scenario, String xml) throws Exception {
        Optional<String> result = service.generateAndSend(xml.getBytes(UTF_8));

        assertThat(result).isEmpty();
        verifyNoInteractions(repository, mailSender);
        assertThat(logged(Level.WARN)).containsExactly(EmployeeReportService.NO_RESULTS_WARNING);
        assertThat(logged(Level.INFO)).isEmpty();
    }

    /**
     * Employee element contents and the names the original {@code payload.getText().split(" ")[0]} and {@code [1]}
     * take from them: dom4j's {@code getText()} (direct text and CDATA, untrimmed) split with Java semantics.
     */
    static Stream<Arguments> employeeTextsAndTheirNames() {
        return Stream.of(
                Arguments.of("first and last name", "Chava Puckett", "Chava", "Puckett"),
                Arguments.of("parts after the second are ignored", "Chava Puckett Jr", "Chava", "Puckett"),
                Arguments.of("consecutive spaces keep an empty part", "Chava  Puckett", "Chava", ""),
                Arguments.of("a leading space gives an empty first name", " Chava Puckett", "", "Chava"),
                Arguments.of("a trailing space is dropped", "Chava Puckett ", "Chava", "Puckett"),
                Arguments.of("other whitespace is kept", "\nChava Puckett\n", "\nChava", "Puckett\n"),
                Arguments.of("a tab does not separate", "Chava\tPuckett Sosa", "Chava\tPuckett", "Sosa"),
                Arguments.of("CDATA is part of the text", "<![CDATA[Chava]]> Puckett", "Chava", "Puckett"),
                Arguments.of("comments and nested elements contribute nothing",
                        "Chava<!-- note --> <b>ignored</b>Puckett", "Chava", "Puckett"),
                Arguments.of("character references are resolved", "Chava&#x20;Puckett", "Chava", "Puckett"),
                Arguments.of("entity references are resolved", "Chava &amp; Co", "Chava", "&"));
    }

    // 4
    @ParameterizedTest(name = "{0}")
    @MethodSource("employeeTextsAndTheirNames")
    void employeeTextIsSplitOnASingleSpaceAsTheOriginalDid(String scenario, String content, String firstName,
                                                           String lastName) throws Exception {
        when(repository.findByFirstNameAndLastName(firstName, lastName)).thenReturn(List.of(CHAVA));

        Optional<String> result =
                service.generateAndSend(("<root><employee>" + content + "</employee></root>").getBytes(UTF_8));

        assertThat(result).hasValue(HEADER + CHAVA_LINE);
        verify(repository).findByFirstNameAndLastName(firstName, lastName);
        verify(mailSender).send(HEADER + CHAVA_LINE);
        verifyNoMoreInteractions(repository, mailSender);
    }

    /** Lookup results for Quentin Puckett other than one row, and the CSV line each yields. */
    static Stream<Arguments> lookupResults() {
        return Stream.of(
                Arguments.of("no matching row gives five empty fields", List.of(), ",,,,\n"),
                Arguments.of("of several rows only the first is written", List.of(QUENTIN, CHAVA), QUENTIN_LINE));
    }

    // 5
    @ParameterizedTest(name = "{0}")
    @MethodSource("lookupResults")
    void everyLookupResultIsAggregatedAsReturnedAndStillMailed(String scenario, List<Map<String, Object>> quentinRows,
                                                               String quentinLine) throws Exception {
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(CHAVA));
        when(repository.findByFirstNameAndLastName("Quentin", "Puckett")).thenReturn(quentinRows);
        when(repository.findByFirstNameAndLastName("Mona", "Sosa")).thenReturn(List.of(MONA));

        Optional<String> result = service.generateAndSend(classpath(MESSAGE_XML));

        String csv = HEADER + CHAVA_LINE + quentinLine + MONA_LINE;
        assertThat(result).hasValue(csv);
        verify(mailSender).send(csv);
        assertThat(logged(Level.INFO))
                .containsExactly(String.valueOf(List.of(List.of(CHAVA), quentinRows, List.of(MONA))));
    }

    /** Bodies the parser rejects, with the exception it raises; {@code null} is read as an empty body. */
    static Stream<Arguments> unparsableBodies() {
        return Stream.of(
                Arguments.of("absent body", null, SAXParseException.class),
                Arguments.of("empty body", new byte[0], SAXParseException.class),
                Arguments.of("plain text", "Chava Puckett".getBytes(UTF_8), SAXParseException.class),
                Arguments.of("unclosed root", "<root><employee>Chava Puckett</employee>".getBytes(UTF_8),
                        SAXParseException.class),
                Arguments.of("mismatched end tag", "<root><employee>Chava Puckett</employees></root>".getBytes(UTF_8),
                        SAXParseException.class),
                Arguments.of("two root elements", "<root/><root/>".getBytes(UTF_8), SAXParseException.class),
                Arguments.of("unbound prefix", "<e:employee>Chava Puckett</e:employee>".getBytes(UTF_8),
                        SAXParseException.class),
                Arguments.of("undeclared entity", "<root><employee>&chava;</employee></root>".getBytes(UTF_8),
                        SAXParseException.class),
                Arguments.of("unsupported declared encoding",
                        "<?xml version=\"1.0\" encoding=\"bogus-enc\"?><root/>".getBytes(UTF_8),
                        UnsupportedEncodingException.class));
    }

    // 6
    @ParameterizedTest(name = "{0}")
    @MethodSource("unparsableBodies")
    void bodyThatCannotBeParsedFailsBeforeAnyLookup(String scenario, byte[] xml, Class<? extends Exception> expected) {
        assertThatThrownBy(() -> service.generateAndSend(xml)).isInstanceOf(expected);

        verifyNoInteractions(repository, mailSender);
        assertThat(appender.list).isEmpty();
    }

    // 7
    @ParameterizedTest(name = "employee content \"{0}\"")
    @ValueSource(strings = {"Chava", "", "Chava ", " ", "<b>Chava Puckett</b>"})
    void employeeTextWithoutTwoPartsFailsAsTheOriginalSplitDidAndNothingIsMailed(String content) throws Exception {
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(CHAVA));
        String xml = "<root><employee>Chava Puckett</employee><employee>" + content + "</employee></root>";

        assertThatThrownBy(() -> service.generateAndSend(xml.getBytes(UTF_8)))
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);

        verify(repository).findByFirstNameAndLastName("Chava", "Puckett");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(mailSender);
        assertThat(logged(Level.INFO)).isEmpty();
    }

    // 8
    @Test
    void lookupFailurePropagatesUnchangedAndStopsTheFlow() throws Exception {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Communications link failure");
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.generateAndSend(classpath(MESSAGE_XML)));

        assertThat(thrown).isSameAs(failure);
        verify(repository).findByFirstNameAndLastName("Chava", "Puckett");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(mailSender);
        assertThat(logged(Level.INFO)).isEmpty();
    }

    // 9
    @Test
    void undatableValueFailsInTheMapperAfterTheAggregateIsLoggedAndNothingIsMailed() throws Exception {
        Map<String, Object> undatable = row("Chava", "Puckett", "F", "02/09/1985", LocalDate.of(2008, 10, 12));
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(undatable));

        assertThatThrownBy(() -> service.generateAndSend(
                "<root><employee>Chava Puckett</employee></root>".getBytes(UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dob");

        verifyNoInteractions(mailSender);
        assertThat(logged(Level.INFO)).containsExactly(String.valueOf(List.of(List.of(undatable))));
    }

    // 10
    @Test
    void mailFailurePropagatesUnchangedWithoutRetry() throws Exception {
        when(repository.findByFirstNameAndLastName("Chava", "Puckett")).thenReturn(List.of(CHAVA));
        when(repository.findByFirstNameAndLastName("Quentin", "Puckett")).thenReturn(List.of(QUENTIN));
        when(repository.findByFirstNameAndLastName("Mona", "Sosa")).thenReturn(List.of(MONA));
        MailSendException failure = new MailSendException("Mail server connection failed");
        doThrow(failure).when(mailSender).send(CSV);

        Throwable thrown = catchThrowable(() -> service.generateAndSend(classpath(MESSAGE_XML)));

        assertThat(thrown).isSameAs(failure);
        verify(mailSender, times(1)).send(CSV);
        verifyNoMoreInteractions(mailSender);
    }

    private List<String> logged(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static byte[] classpath(String resource) throws Exception {
        try (InputStream in = EmployeeReportServiceTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        }
    }

    /** A row keyed in the select list's order, as {@link EmployeeRepository} returns it. */
    private static Map<String, Object> row(String firstName, String lastName, String gender, Object dob,
                                           Object hireDate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(EmployeeRepository.FIRST_NAME, firstName);
        row.put(EmployeeRepository.LAST_NAME, lastName);
        row.put(EmployeeRepository.GENDER, gender);
        row.put(EmployeeRepository.DOB, dob);
        row.put(EmployeeRepository.HIRE_DATE, hireDate);
        return Collections.unmodifiableMap(row);
    }
}
