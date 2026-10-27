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
 * <p>One handler, {@link #uploadFile}, admits both listeners' methods ignoring letter case, as Mule 3.8's
 * {@code MethodRequestMatcher} lower-cased the configured and the incoming method before comparing them: {@code get}
 * and {@code Get} render the form, {@code post} and {@code Post} store the upload. Every other method on
 * {@code /uploadFile}, {@code HEAD}, {@code OPTIONS}, {@code TRACE} and lower-case {@code put} included, gets the 405
 * {@code Method not allowed for endpoint: /uploadFile} of {@code GlobalExceptionHandler}, and neither flow runs.
 *
 * <p>When a request carries several parts named {@code file}, the last one is uploaded: Mule 3.8's
 * {@code HttpPartDataSource} put the parts into a {@code HashMap} keyed by part name, so each later {@code file} part
 * replaced the earlier one in {@code inboundAttachments}, whereas Undertow's {@code getPart("file")} returns the
 * first.
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

    /** {@code allowedMethods} of the {@code httpRenderFlow} listener. */
    private static final String FORM_METHOD = "GET";

    /** {@code allowedMethods} of the {@code httpMultipartRequestFlow} listener. */
    private static final String UPLOAD_METHOD = "POST";

    /** The methods the two {@code /uploadFile} listeners accept together. */
    private static final List<String> UPLOAD_METHODS = List.of(FORM_METHOD, UPLOAD_METHOD);

    /** Name of the multipart part the upload flow reads, {@code inboundAttachments['file']} of the original. */
    private static final String FILE_PART = "file";

    private final UploadService uploadService;

    /**
     * @param uploadService the processor logic of the two flows
     */
    public UploadController(UploadService uploadService) {
        this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    }

    /**
     * The two {@code /uploadFile} listeners: runs {@link #uploadForm} for {@code GET} and {@link #upload} for
     * {@code POST}, each compared with the request's method ignoring letter case, and rejects every other method with
     * the listeners' 405 before either flow runs.
     *
     * <p>The mapping declares no method, so that the method can be compared with {@code allowedMethods} ignoring
     * case; it matches every method but {@code OPTIONS}, which {@link #rejectUnsupportedMethod} takes, and the check
     * here rejects {@code HEAD}, which Spring MVC would otherwise answer through a {@code GET} mapping, and
     * {@code TRACE}, which reaches the dispatcher because {@code application.yml} sets
     * {@code spring.mvc.dispatch-trace-request: true}.
     *
     * <p>The parts named {@code file} are bound by Spring's multipart resolution in arrival order, and {@link #upload}
     * receives the last of them, the one Mule 3.8 kept under that name. The binding is optional on purpose: Spring
     * resolves it to {@code null} both when the request has no {@code file} part and when the body is not multipart,
     * and the service then fails with the 500 the original's failing expression produced, instead of the 400 a
     * required part would raise before the flow runs.
     *
     * @param fileParts the multipart parts named {@code file} in arrival order, or {@code null} when the request
     *                  carries none
     * @param request   the request, whose method selects the listener
     * @param response  the response the form or the uploaded content is written to
     * @throws HttpRequestMethodNotSupportedException when the method is neither {@code GET} nor {@code POST} in any
     *                                                letter case
     * @throws IOException                            if the part cannot be read, the file cannot be stored or the
     *                                                response cannot be written
     */
    @RequestMapping(UPLOAD_PATH)
    public void uploadFile(@RequestPart(name = FILE_PART, required = false) List<Part> fileParts,
                           HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        String method = request.getMethod();
        if (FORM_METHOD.equalsIgnoreCase(method)) {
            uploadForm(response);
        } else if (UPLOAD_METHOD.equalsIgnoreCase(method)) {
            upload(lastPart(fileParts), response);
        } else {
            throw new HttpRequestMethodNotSupportedException(method, UPLOAD_METHODS);
        }
    }

    /**
     * Rejects {@code OPTIONS} on {@code /uploadFile}, which neither original listener accepted, with the 405 of
     * {@code GlobalExceptionHandler}.
     *
     * <p>A mapping without methods does not match {@code OPTIONS}, so without this one Spring MVC would answer it
     * itself with 200 and an {@code Allow} header; the explicit match keeps the handler mapping from reaching its
     * default {@code OPTIONS} handler. {@code PreflightDispatchConfig} dispatches a CORS pre-flight here as the plain
     * {@code OPTIONS} request it is. This match is exact, so {@code options} in any other letter case reaches
     * {@link #uploadFile}, which rejects it as it rejects every method other than {@code GET} and {@code POST}.
     *
     * @param request the rejected request
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = UPLOAD_PATH, method = RequestMethod.OPTIONS)
    public void rejectUnsupportedMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), UPLOAD_METHODS);
    }

    /**
     * {@code httpRenderFlow}: 200 with {@code uploadFile.html} byte for byte and no Content-Type, because the flow's
     * {@code parse-template} sets none.
     *
     * @param response the response the form is written to
     * @throws IOException if the form cannot be written
     */
    private void uploadForm(HttpServletResponse response) throws IOException {
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
     * <p>The servlet {@link Part} is passed rather than a {@code MultipartFile}, so the service reads the raw
     * {@code Content-Disposition} and {@code Content-Type} part headers the flow's expressions read (xml:19-23).
     *
     * @param file     the multipart part {@code file}, or {@code null} when the request carries none
     * @param response the response the uploaded content is written to
     * @throws IOException if the part cannot be read, the file cannot be stored or the response cannot be written
     */
    private void upload(Part file, HttpServletResponse response) throws IOException {
        byte[] body = uploadService.upload(file);
        response.setStatus(HttpServletResponse.SC_OK);
        writeBody(response, body);
    }

    /**
     * The part the upload flow reads among those named {@code file}: the last, since Mule 3.8's {@code HashMap} of
     * parts by name kept the last one put under a name.
     *
     * @param parts the parts named {@code file} in arrival order, or {@code null}
     * @return the last part, or {@code null} when {@code parts} is {@code null} or empty
     */
    private static Part lastPart(List<Part> parts) {
        return parts == null || parts.isEmpty() ? null : parts.get(parts.size() - 1);
    }

    /** Writes {@code bytes} with their {@code Content-Length} to the output stream, never to the writer. */
    private static void writeBody(HttpServletResponse response, byte[] bytes) throws IOException {
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
