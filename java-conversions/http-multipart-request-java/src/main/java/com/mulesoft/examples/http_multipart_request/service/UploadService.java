/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.service;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.Part;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.http_multipart_request.config.ReasonPhrase;
import com.mulesoft.examples.http_multipart_request.mapper.ParseTemplateRenderer;

/**
 * The two flows of {@code http-multipart-request.xml}: {@code httpRenderFlow} serves the upload form and
 * {@code httpMultipartRequestFlow} stores the uploaded part {@code file} and echoes its bytes.
 *
 * <p>The original declares no exception strategy, so nothing here is caught: every failure reaches the
 * generic handler and is answered with 500 {@code { "message": "<exception message>" }}.
 */
@Service
public class UploadService {

    private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);

    /** Reason phrase of the upload response, {@code http:response-builder reasonPhrase} of the original. */
    public static final String REASON_PHRASE = "File was uploaded.";

    /** Classpath template the form page is rendered from, {@code parse-template location} of the original. */
    private static final String UPLOAD_FORM_TEMPLATE = "uploadFile.html";

    private static final String FILENAME_PARAMETER = "filename=";

    private final ParseTemplateRenderer renderer;
    private final FileOutboundWriter fileOutboundWriter;
    private final String uploadDirectory;

    /**
     * @param renderer           renders the upload form template
     * @param fileOutboundWriter writes the uploaded content
     * @param uploadDirectory    directory the uploaded file is stored in, {@code /tmp} as in the original
     */
    public UploadService(ParseTemplateRenderer renderer,
                         FileOutboundWriter fileOutboundWriter,
                         @Value("${upload.directory:/tmp}") String uploadDirectory) {
        this.renderer = renderer;
        this.fileOutboundWriter = fileOutboundWriter;
        this.uploadDirectory = uploadDirectory;
    }

    /**
     * {@code httpRenderFlow}: the upload form, {@code uploadFile.html} unchanged, since it holds no expression.
     *
     * @return the form page
     * @throws IllegalStateException when the template is not on the classpath
     */
    public String uploadForm() {
        return renderer.render(UPLOAD_FORM_TEMPLATE);
    }

    /**
     * {@code httpMultipartRequestFlow}: takes the file name from the part's {@code Content-Disposition}, logs its
     * {@code Content-Type} at ERROR, stores its content as {@code <upload.directory>/<file name>}, replacing any
     * file of that name, and sets the reason phrase {@value #REASON_PHRASE} once the file is written.
     *
     * @param file the multipart part {@code file}, or {@code null} when the request carries none
     * @return the uploaded bytes, which form the response body
     * @throws IllegalStateException           when the part is missing
     * @throws NullPointerException            when the part has no {@code Content-Disposition} header
     * @throws StringIndexOutOfBoundsException when the header is too short for the file name range
     * @throws IOException                     when the part cannot be read or the file cannot be written
     */
    public byte[] upload(Part file) throws IOException {
        if (file == null) {
            throw new IllegalStateException("Multipart part 'file' is not present in the request");
        }
        String fileName = extractFileName(file.getHeader("Content-Disposition"));
        // The header value is logged as is, with no escaping: the original logger's message text is kept, and
        // the multipart parser ends every header value at CRLF, so it cannot start a forged log line.
        LOG.error("{}", file.getHeader("Content-Type"));
        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        }
        fileOutboundWriter.write(uploadDirectory, fileName, content);
        // Set only after the write, so a failure before it keeps the standard phrase of its 500.
        ReasonPhrase.set(REASON_PHRASE);
        return content;
    }

    /**
     * The original expression component, verbatim: the trimmed header from one character past {@code filename=},
     * the opening quote, up to, not including, its last character, the closing quote.
     *
     * <p>The name is neither unquoted, decoded nor sanitised, and {@code filename*} is not read: the original
     * stored the file under exactly this text, and the file writer resolves it against the upload directory as
     * given, so a name with path segments reaches a path outside that directory as it did in Mule. Without
     * {@code filename=} the range starts at index 9, as the expression computed it.
     *
     * @param contentDisposition the part's {@code Content-Disposition} header value
     * @return the file name, for example {@code test.txt} for {@code form-data; name="file"; filename="test.txt"}
     * @throws NullPointerException            when the header is absent
     * @throws StringIndexOutOfBoundsException when the trimmed header is too short for that range
     */
    static String extractFileName(String contentDisposition) {
        String value = contentDisposition.trim();
        return value.substring(value.indexOf(FILENAME_PARAMETER) + FILENAME_PARAMETER.length() + 1,
                value.length() - 1);
    }
}
