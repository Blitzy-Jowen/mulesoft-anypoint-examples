/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.service.EmployeeReportService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
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
 *
 * <p>Every reply is complete when the handler returns: it declares its {@code Content-Length}, has written exactly
 * that many bytes and is closed. {@code application.yml} dispatches {@code TRACE} to this controller, as the
 * listener ran the flow for it, yet {@code FrameworkServlet} afterwards still runs the servlet's own {@code TRACE}
 * echo, and after an {@code OPTIONS} handler that set no {@code Allow} it still adds one. Undertow does not commit a
 * response on a flush, so a reply left open would take the echo of the request, its {@code Authorization} header
 * included, in place of the empty reply or after the multipart entity, or the {@code Allow} header; a complete one
 * takes neither, so every method is answered alike.
 */
@RestController
@RequestMapping("/")
public class EmployeeReportController {

    /** Stateless once built, so one instance serves every request; its default charset is UTF-8. */
    private static final FormHttpMessageConverter FORM_CONVERTER = new FormHttpMessageConverter();

    private final EmployeeReportService employeeReportService;

    public EmployeeReportController(EmployeeReportService employeeReportService) {
        this.employeeReportService = employeeReportService;
    }

    /**
     * Runs the flow for {@code /} with every method except {@code OPTIONS}, {@code TRACE} included; a {@code HEAD}
     * response carries the headers without the body.
     *
     * <p>The reply mirrors the Mule listener answering with the flow's final message, which carried the CSV as its
     * outbound attachment {@value ReportMailSender#ATTACHMENT_NAME} (attachments.xml:35): a
     * {@code multipart/form-data} entity holding that attachment as its single part, named
     * {@value ReportMailSender#ATTACHMENT_NAME} and typed {@value ReportMailSender#ATTACHMENT_CONTENT_TYPE}. This
     * shape is a platform default rather than flow output, so a captured fixture of the original overrides it.
     *
     * <p>That reply holds the employees' records, so it also carries {@code Cache-Control: no-store}, keeping
     * clients and caches from retaining it, and {@code X-Content-Type-Options: nosniff}, keeping browsers from
     * treating it as a type other than the one it declares. Neither changes its status, {@code Content-Type} or
     * bytes. The empty reply holds no record and carries neither, nor do the error replies of the global exception
     * handler.
     *
     * <p>The flow runs and the entity is serialized before the response is touched, so every failure still finds an
     * uncommitted response for the global exception handler's 500. Either reply is then written complete (see the
     * class comment), with its {@code Content-Length}; the entity's {@code Content-Type} is preset, so the request's
     * {@code Accept} never turns the reply into a 406.
     *
     * @param body     the raw request body, {@code null} when the request has none; passed to the flow unchanged
     * @param response the response written with 200 {@code multipart/form-data;boundary=...}, the two headers above
     *                 and the mailed CSV as the {@code employees.csv} part, or with an empty 200 without
     *                 {@code Content-Type} or either header when the XML holds no {@code employee} element, where
     *                 Mule's splitter ended the flow without querying or mailing
     * @throws Exception any failure of the flow, unchanged, for the global exception handler's 500
     */
    @RequestMapping
    public void handle(@RequestBody(required = false) byte[] body, HttpServletResponse response) throws Exception {
        Optional<String> csv = employeeReportService.generateAndSend(body);
        if (csv.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLength(0);
            response.getOutputStream().close();
            return;
        }

        BufferedOutputMessage entity = multipartEntity(csv.get());
        String contentType = Objects.requireNonNull(entity.getHeaders().getContentType(),
                "the form converter set no multipart Content-Type").toString();
        byte[] bytes = entity.toByteArray();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.close();
    }

    /**
     * Runs the flow for {@code OPTIONS /} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body;
     * the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param body     the raw request body, {@code null} when the request has none
     * @param response the response written as by {@link #handle(byte[], HttpServletResponse)}
     * @throws Exception any failure of the flow, unchanged
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public void handleOptions(@RequestBody(required = false) byte[] body, HttpServletResponse response)
            throws Exception {
        handle(body, response);
    }

    /**
     * Serializes the reply entity in memory with Spring's {@link FormHttpMessageConverter}, whose multipart writer
     * and byte-array part writer Spring MVC's own form converter inherits, so the bytes are those Spring MVC would
     * stream and their length is known before anything is written: {@code multipart/form-data} with a generated
     * {@code boundary} and one part named {@value ReportMailSender#ATTACHMENT_NAME}, whose headers are
     * {@code Content-Disposition}, {@code Content-Type: }{@value ReportMailSender#ATTACHMENT_CONTENT_TYPE} and
     * {@code Content-Length}, and whose content is {@code csv} as UTF-8.
     *
     * @param csv the mailed CSV report
     * @return the serialized entity, its {@code Content-Type} with the boundary in its headers
     * @throws IOException if the converter fails to write the entity
     */
    private static BufferedOutputMessage multipartEntity(String csv) throws IOException {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(ReportMailSender.ATTACHMENT_CONTENT_TYPE));
        MultiValueMap<String, HttpEntity<byte[]>> parts = new LinkedMultiValueMap<>();
        parts.add(ReportMailSender.ATTACHMENT_NAME,
                new HttpEntity<>(csv.getBytes(StandardCharsets.UTF_8), partHeaders));

        BufferedOutputMessage entity = new BufferedOutputMessage();
        FORM_CONVERTER.write(parts, MediaType.MULTIPART_FORM_DATA, entity);
        return entity;
    }

    /**
     * An {@link HttpOutputMessage} held in memory. It is not a {@code StreamingHttpOutputMessage}, so the form
     * converter writes the whole entity to {@link #getBody()} before returning.
     */
    private static final class BufferedOutputMessage implements HttpOutputMessage {

        private final HttpHeaders headers = new HttpHeaders();

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public OutputStream getBody() {
            return body;
        }

        /** The bytes written to {@link #getBody()} so far. */
        byte[] toByteArray() {
            return body.toByteArray();
        }
    }
}
