/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.service.EmployeeReportService;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code attachmentsFlow1}: replaces the {@code http:listener-config} and the
 * {@code http:listener} on {@code /} (querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:6,8).
 * The listener's host and port live in {@code application.yml}; splitting the posted XML, the database lookups and
 * the logged aggregate belong to {@link EmployeeReportService}, the CSV to {@code EmployeeCsvMapper} and the mail to
 * {@link ReportMailSender}. This class only hands the raw request body to the flow and turns its result into the
 * response.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method runs the flow and is answered alike. The match
 * is exact: any other path, a trailing slash form included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}. No mapping restricts the request or reply media type, so the caller's
 * {@code Content-Type} and {@code Accept} never turn a request into a 415 or 406.
 *
 * <p>The flow declares no exception strategy, so nothing here catches: a malformed body, a one-word employee, a
 * failed lookup, an unreadable date or a failed send reaches the platform-default 500
 * {@code { "message": "<exception message>" }} of {@code GlobalExceptionHandler} unchanged.
 */
@RestController
@RequestMapping("/")
public class EmployeeReportController {

    private final EmployeeReportService employeeReportService;

    public EmployeeReportController(EmployeeReportService employeeReportService) {
        this.employeeReportService = employeeReportService;
    }

    /**
     * Runs the flow for {@code /} with every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body.
     *
     * <p>The reply mirrors the Mule listener answering with the flow's final message, which carried the CSV as its
     * outbound attachment {@value ReportMailSender#ATTACHMENT_NAME} (attachments.xml:35): a
     * {@code multipart/form-data} entity holding that attachment as its single part, named
     * {@value ReportMailSender#ATTACHMENT_NAME} and typed {@value ReportMailSender#ATTACHMENT_CONTENT_TYPE}. This
     * shape is a platform default rather than flow output, so a captured fixture of the original overrides it.
     *
     * @param body the raw request body, {@code null} when the request has none; passed to the flow unchanged
     * @return 200 {@code multipart/form-data;boundary=...} with the mailed CSV as the {@code employees.csv} part, or
     *         200 with an empty body when the XML holds no {@code employee} element, where Mule's splitter ended
     *         the flow without querying or mailing
     * @throws Exception any failure of the flow, unchanged, for the global exception handler's 500
     */
    @RequestMapping
    public ResponseEntity<?> handle(@RequestBody(required = false) byte[] body) throws Exception {
        Optional<String> csv = employeeReportService.generateAndSend(body);
        if (csv.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(ReportMailSender.ATTACHMENT_CONTENT_TYPE));
        MultiValueMap<String, HttpEntity<byte[]>> parts = new LinkedMultiValueMap<>();
        parts.add(ReportMailSender.ATTACHMENT_NAME,
                new HttpEntity<>(csv.get().getBytes(StandardCharsets.UTF_8), partHeaders));

        // Preset Content-Type: Spring's form converter writes the parts with a generated boundary without
        // consulting Accept, so the reply is never a 406.
        return ResponseEntity.ok().contentType(MediaType.MULTIPART_FORM_DATA).body(parts);
    }

    /**
     * Runs the flow for {@code OPTIONS /} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body;
     * the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param body the raw request body, {@code null} when the request has none
     * @return the response {@link #handle(byte[])} gives
     * @throws Exception any failure of the flow, unchanged
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<?> handleOptions(@RequestBody(required = false) byte[] body) throws Exception {
        return handle(body);
    }
}
