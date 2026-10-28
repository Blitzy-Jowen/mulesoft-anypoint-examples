/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Scheduled;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client.CsvMailSender;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.DwCsvReader;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.OrderLinesMapper;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service.CsvEmailService;

/**
 * Trigger test of {@link CsvFileTrigger}, the replacement of the {@code file:inbound-endpoint} of flow
 * {@code csv-to-smtpFlow} ({@code csv-to-smtp.xml}, l.5: {@code path="src/main/resources/input"
 * responseTimeout="10000"}, no {@code moveToDirectory}, no {@code pollingFrequency}). The original has no HTTP
 * trigger, so this test stands in for its endpoint test (AAP 0.8.2): a CSV file dropped into the input directory is
 * picked up by one poll, read and mapped by the real {@link CsvEmailService}, mailed, and deleted.
 *
 * <p>The flow declares no exception strategy, so a failed mapping or SMTP send ends the flow through Mule's default
 * strategy and SC-5 {@link FileInboundPoller} still consumes the file: the poll never throws, and the file is never
 * retried.
 *
 * <p>No Spring context is started; the end-to-end port of the original IT, over GreenMail, is
 * {@code SendingACsvFileThroughEmailUsingSmtpTest}. Only {@link CsvMailSender} is mocked, so the CSV reader, the
 * {@code %dw 1.0} mapper and the service run for real and no mail leaves the JVM. The input directory is a
 * {@link TempDir}, so {@code src/main/resources/input} is never polled or deleted, and polls run through the
 * single-run method, never by waiting.
 */
@ExtendWith(MockitoExtension.class)
class CsvFileTriggerTest {

    private static final String INPUT_CSV = "input.csv";

    /** The {@code csv-email.polling-frequency} {@code application.yml} sets: Mule's default of 1000 ms. */
    private static final String POLLING_FREQUENCY = "1000";

    @TempDir
    Path inputDir;

    @Mock
    CsvMailSender sender;

    private CsvFileTrigger trigger;

    @BeforeEach
    void createTriggerOverTheRealFlow() {
        CsvEmailService service = new CsvEmailService(new DwCsvReader(), new OrderLinesMapper(), sender);
        trigger = new CsvFileTrigger(service, inputDir.toString(), POLLING_FREQUENCY);
    }

    @Test
    @DisplayName("input.csv -> one mail whose body equals the original email.txt, input deleted")
    void pollOnceEmailsMappedOrderLinesAndDeletesFile() throws IOException {
        Path input = dropOriginalInput();

        assertThat(trigger.pollOnce()).isEqualTo(1);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(1)).send(payload.capture());
        // The original IT compares the received body with email.txt exactly; integral BigDecimals render without a
        // fraction (units 2.0 -> 2, 2.0 x 10 -> 20), so the list's toString() matches byte for byte.
        assertThat(String.valueOf(payload.getValue())).isEqualTo(expectedBody());
        // No moveToDirectory: SC-5 deletes the processed file.
        assertThat(input).doesNotExist();
        assertThat(regularFiles(inputDir)).isEmpty();
    }

    @Test
    @DisplayName("Non-numeric units -> the poll does not throw, no mail is sent, the file is consumed")
    void malformedCsvIsConsumedWithoutSending() throws IOException {
        Path input = Files.write(inputDir.resolve("bad.csv"),
                "orderId,name,units,pricePerUnit\n1,aaa,abc,10".getBytes(StandardCharsets.UTF_8));
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(trigger.pollOnce())).doesNotThrowAnyException();

        // FileInboundPoller.pollOnce() documents its result as the number of files handed to the handler, counting
        // a file whose handler threw.
        assertThat(handed.get()).isEqualTo(1);
        assertThat(input).doesNotExist();
        assertThat(regularFiles(inputDir)).isEmpty();
        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("SMTP send fails -> the poll does not throw, the file is consumed and never retried")
    void smtpFailureIsConsumedWithoutRetry() throws IOException {
        doThrow(new MailSendException("smtp down")).when(sender).send(any());
        Path input = dropOriginalInput();
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(trigger.pollOnce())).doesNotThrowAnyException();

        assertThat(handed.get()).isEqualTo(1);
        assertThat(input).doesNotExist();
        assertThat(regularFiles(inputDir)).isEmpty();

        // The next poll finds nothing: the failed file is not redelivered, so exactly one send was ever attempted.
        assertThat(trigger.pollOnce()).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    @DisplayName("Empty input directory -> nothing handed over, no mail")
    void emptyDirectoryReturnsZero() {
        assertThat(trigger.pollOnce()).isZero();

        assertThat(trigger.inputDirectory()).isEqualTo(inputDir);
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("poll(), the scheduled entry point, runs the same single poll")
    void pollDelegatesToSameProcessing() throws IOException {
        Path input = dropOriginalInput();

        trigger.poll();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender, times(1)).send(payload.capture());
        assertThat(String.valueOf(payload.getValue())).isEqualTo(expectedBody());
        assertThat(input).doesNotExist();
    }

    @Test
    @DisplayName("poll() is scheduled with the Mule default file pollingFrequency of 1000 ms")
    void pollIsScheduledWithMuleDefaultFrequency() throws NoSuchMethodException {
        Method poll = CsvFileTrigger.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${csv-email.polling-frequency:1000}");
    }

    @ParameterizedTest(name = "csv-email.polling-frequency \"{0}\" -> start-up fails naming the key and the value")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void unschedulableFrequencyFailsNamingKeyAndValue(String frequency) {
        CsvEmailService service = new CsvEmailService(new DwCsvReader(), new OrderLinesMapper(), sender);

        assertThatThrownBy(() -> new CsvFileTrigger(service, inputDir.toString(), frequency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("csv-email.polling-frequency")
                .hasMessageContaining("\"" + frequency + "\"");
    }

    @ParameterizedTest(name = "csv-email.polling-frequency \"{0}\" -> the trigger is created")
    @ValueSource(strings = {"1", "1000", "PT10S", "pt0.5s"})
    void schedulableFrequencyCreatesTheTrigger(String frequency) {
        CsvEmailService service = new CsvEmailService(new DwCsvReader(), new OrderLinesMapper(), sender);
        AtomicReference<CsvFileTrigger> created = new AtomicReference<>();

        assertThatCode(() -> created.set(new CsvFileTrigger(service, inputDir.toString(), frequency)))
                .doesNotThrowAnyException();

        assertThat(created.get().inputDirectory()).isEqualTo(inputDir);
        verifyNoInteractions(sender);
    }

    /** Places the original {@code input.csv} in the input directory, as the original IT dispatched it there. */
    private Path dropOriginalInput() throws IOException {
        byte[] csv = resource("/" + INPUT_CSV);
        assertThat(csv).hasSize(57);
        return Files.write(inputDir.resolve(INPUT_CSV), csv);
    }

    /** The original {@code email.txt}: the mail body the original IT expected, without a trailing newline. */
    private String expectedBody() throws IOException {
        return new String(resource("/email.txt"), StandardCharsets.UTF_8);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertThat(in).as("classpath resource %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static List<Path> regularFiles(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }
}
