/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.http_multipart_request.service.UploadService;

/**
 * HTTP triggers of the two flows of {@code http-multipart-request.xml}, both on the {@code HTTP_Listener_Configuration}
 * listener ({@code 0.0.0.0:${http.port}}, xml:4), whose host and port come from {@code application.yml}:
 * <ul>
 *   <li>{@code httpRenderFlow}, {@code allowedMethods="GET" path="/uploadFile"} (xml:6-11): {@link #uploadForm};</li>
 *   <li>{@code httpMultipartRequestFlow}, {@code allowedMethods="POST" path="/uploadFile"} with a response builder of
 *       status {@code 200} and reason phrase {@code File was uploaded.} (xml:13-29): {@link #upload}.</li>
 * </ul>
 *
 * <p>The class only binds the request and writes the service's result. The form template, the file name taken from
 * the part's {@code Content-Disposition}, the ERROR log of its {@code Content-Type}, the stored file and the reason
 * phrase belong to {@link UploadService}. Neither flow declares an exception strategy, so nothing is caught here:
 * every failure, a missing {@code file} part included, propagates to the generic advice of
 * {@code GlobalExceptionHandler} (500 {@code { "message": "<exception message>" }}). Each handler obtains the
 * service's result before it touches the response, so the advice always finds the response uncommitted.
 *
 * <p>Bodies are written to the servlet output stream with a {@code Content-Length}, never through a message converter
 * or the servlet writer, either of which would add a Content-Type: the template the GET flow renders and the file
 * content the POST flow returns both went out with none.
 */
@RestController
public class UploadController {

    private static final String UPLOAD_PATH = "/uploadFile";

    /** The methods the two {@code /uploadFile} listeners accept together. */
    private static final List<String> UPLOAD_METHODS = List.of("GET", "POST");

    private final UploadService uploadService;

    /**
     * @param uploadService the processor logic of the two flows
     */
    public UploadController(UploadService uploadService) {
        this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    }

    /**
     * {@code httpRenderFlow}: 200 with {@code uploadFile.html} byte for byte and no Content-Type, because the flow's
     * {@code parse-template} sets none.
     *
     * @param response the response the form is written to
     * @throws IOException if the form cannot be written
     */
    @GetMapping(UPLOAD_PATH)
    public void uploadForm(HttpServletResponse response) throws IOException {
        byte[] body = uploadService.uploadForm().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        writeBody(response, body);
    }

    /**
     * {@code httpMultipartRequestFlow}: hands the multipart part {@code file} to {@link UploadService#upload} and
     * answers 200 with the uploaded bytes and no Content-Type, the file content being the flow's final payload. The
     * service sets the reason phrase {@code File was uploaded.} once the file is stored; it is applied again when the
     * response commits, so the status set here keeps it.
     *
     * <p>The servlet {@link Part} is bound rather than a {@code MultipartFile}, so the service reads the raw
     * {@code Content-Disposition} and {@code Content-Type} part headers the flow's expressions read (xml:19-23). The
     * part is optional on purpose: Spring resolves it to {@code null} both when the request has no {@code file} part
     * and when the body is not multipart, and the service then fails with the 500 the original's failing expression
     * produced, instead of the 400 a required part would raise before the flow runs.
     *
     * @param file     the multipart part {@code file}, or {@code null} when the request carries none
     * @param response the response the uploaded content is written to
     * @throws IOException if the part cannot be read, the file cannot be stored or the response cannot be written
     */
    @PostMapping(UPLOAD_PATH)
    public void upload(@RequestPart(name = "file", required = false) Part file, HttpServletResponse response)
            throws IOException {
        byte[] body = uploadService.upload(file);
        response.setStatus(HttpServletResponse.SC_OK);
        writeBody(response, body);
    }

    /**
     * Rejects {@code HEAD} and {@code OPTIONS} on {@code /uploadFile}, which neither original listener accepted, with
     * the 405 of {@code GlobalExceptionHandler}. Without this mapping Spring MVC would answer {@code HEAD} through
     * {@link #uploadForm} and {@code OPTIONS} itself, both with 200; an explicit {@code HEAD} mapping takes precedence
     * over the implicit match of the GET mapping. Every other method outside GET and POST is rejected by the handler
     * mapping itself.
     *
     * @param request the rejected request
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = UPLOAD_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void rejectUnsupportedMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), UPLOAD_METHODS);
    }

    /** Writes {@code bytes} with their {@code Content-Length} to the output stream, never to the writer. */
    private static void writeBody(HttpServletResponse response, byte[] bytes) throws IOException {
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
