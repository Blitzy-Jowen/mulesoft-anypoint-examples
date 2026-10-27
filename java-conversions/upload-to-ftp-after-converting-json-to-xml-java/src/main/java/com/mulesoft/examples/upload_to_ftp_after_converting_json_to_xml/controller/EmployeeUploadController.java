/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.service.EmployeeUploadService;

/**
 * HTTP trigger of flow {@code main} (upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:4,6):
 * the {@code http:listener} on path {@code /} of the listener configuration bound to host {@code 0.0.0.0} and port
 * {@code ${http.port}}, which {@code application.yml} carries as {@code server.address} and {@code server.port}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every HTTP method is accepted on {@code /}. The match is
 * exact: any other path falls through to the no-listener 404 of {@code GlobalExceptionHandler}. No handler declares
 * {@code produces} or {@code consumes}, so a request's {@code Accept} or {@code Content-Type} never turns into a
 * 406 or 415 the Mule listener did not send; the body is handed to the flow as the JSON payload whatever its
 * declared type.
 *
 * <p>The JSON-to-XML transform, the FTP upload and the choice of the response payload all belong to
 * {@link EmployeeUploadService}; this class binds the body and returns the XML. The flow declares no exception
 * strategy, so failures are not caught here and reach {@code GlobalExceptionHandler}, which answers them with the
 * platform-default 500.
 */
@RestController
public class EmployeeUploadController {

    /**
     * Content type of the response: the transform's {@code %output application/xml encoding="UTF-8"}. The header's
     * exact spelling as the Mule listener wrote it is a verification point for captured fixtures.
     */
    private static final MediaType APPLICATION_XML_UTF8 = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private final EmployeeUploadService employeeUploadService;

    public EmployeeUploadController(EmployeeUploadService employeeUploadService) {
        this.employeeUploadService = employeeUploadService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}, which {@link #uploadOptions(byte[])} answers.
     *
     * <p>The body is bound as raw bytes, which Spring reads for every request {@code Content-Type} and for none, and
     * is passed on unchanged, {@code null} included when the request carries no body; the service rejects an
     * absent, empty or malformed payload.
     *
     * @param body the request body, or {@code null} when there is none
     * @return 200 with the XML the service uploaded, byte for byte, as {@code application/xml} in UTF-8
     */
    @RequestMapping("/")
    public ResponseEntity<byte[]> upload(@RequestBody(required = false) byte[] body) {
        byte[] xml = employeeUploadService.process(body);
        return ResponseEntity.status(HttpStatus.OK).contentType(APPLICATION_XML_UTF8).body(xml);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param body the request body, or {@code null} when there is none
     * @return the response of {@link #upload(byte[])}
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> uploadOptions(@RequestBody(required = false) byte[] body) {
        return upload(body);
    }
}
