/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client.CsvMailSender;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.DwCsvReader;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper.OrderLinesMapper;

/**
 * Reproduces the processors of flow {@code csv-to-smtpFlow}
 * (sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml), in their original order:
 * <ol>
 *   <li>lines 6-18, {@code Transform CSV to Map}: the {@code application/csv} input is read by {@link DwCsvReader}
 *       and the {@code %dw 1.0} script (header at line 8) is applied by {@link OrderLinesMapper};</li>
 *   <li>line 19, {@code logger level="INFO" message="Processing objects: #[payload]"};</li>
 *   <li>line 20, {@code smtp:outbound-endpoint}: the order lines are mailed by {@link CsvMailSender}.</li>
 * </ol>
 *
 * <p>The file inbound endpoint of line 5 is not part of this class: {@code trigger/CsvFileTrigger} polls the input
 * directory and hands each file's bytes to {@link #process(byte[])}.
 *
 * <p>The original flow declares no exception strategy, so nothing is caught, logged or retried here: every failure
 * propagates to the trigger, whose file poller logs it at ERROR and still consumes the file.
 */
@Service
public class CsvEmailService {

    private static final Logger log = LoggerFactory.getLogger(CsvEmailService.class);

    private final DwCsvReader dwCsvReader;

    private final OrderLinesMapper orderLinesMapper;

    private final CsvMailSender csvMailSender;

    public CsvEmailService(DwCsvReader dwCsvReader, OrderLinesMapper orderLinesMapper, CsvMailSender csvMailSender) {
        this.dwCsvReader = dwCsvReader;
        this.orderLinesMapper = orderLinesMapper;
        this.csvMailSender = csvMailSender;
    }

    /**
     * Runs the flow for one polled CSV file: read, map, log, mail.
     *
     * <p>The mapper's list is logged, mailed and returned as the same instance, so its {@code toString()} is both the
     * logged payload and the mail body, as {@code #[payload]} and the SMTP transport rendered it in the original.
     *
     * @param csv the UTF-8 encoded CSV file content
     * @return the order lines exactly as {@link OrderLinesMapper#map(List)} produced them, after they were mailed
     * @throws NullPointerException when {@code csv} is {@code null}
     * @throws IllegalArgumentException when a {@code units} or {@code pricePerUnit} value is missing or not a number;
     *         no mail is sent
     * @throws org.springframework.mail.MailException when the message cannot be prepared or sent
     */
    public List<Map<String, Object>> process(byte[] csv) {
        List<Map<String, String>> rows = dwCsvReader.read(csv);
        List<Map<String, Object>> lines = orderLinesMapper.map(rows);
        log.info("Processing objects: {}", lines);
        csvMailSender.send(lines);
        return lines;
    }
}
