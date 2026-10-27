/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
 * declared type, together with the charset its {@code Content-Type} declares, which the Mule listener made the
 * message encoding the transform read the payload with.
 *
 * <p>The JSON-to-XML transform, the FTP upload and the choice of the response payload all belong to
 * {@link EmployeeUploadService}; this class binds the body and its declared charset and returns the XML. The flow
 * declares no exception strategy, so failures are not caught here and reach {@code GlobalExceptionHandler}, which
 * answers them with the platform-default 500.
 */
@RestController
public class EmployeeUploadController {

    /**
     * Content type of the response: the transform's {@code %output application/xml encoding="UTF-8"}. The header's
     * exact spelling as the Mule listener wrote it is a verification point for captured fixtures.
     */
    private static final MediaType APPLICATION_XML_UTF8 = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private static final String CHARSET_PARAMETER = "charset";

    private final EmployeeUploadService employeeUploadService;

    public EmployeeUploadController(EmployeeUploadService employeeUploadService) {
        this.employeeUploadService = employeeUploadService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}, which
     * {@link #uploadOptions(byte[], HttpServletRequest)} answers.
     *
     * <p>The body is bound as raw bytes, which Spring reads for every request {@code Content-Type} it can parse and
     * for none, and is passed on unchanged, {@code null} included when the request carries no body, with the charset
     * {@link #declaredCharset(HttpServletRequest)} reads from the {@code Content-Type}; the service decodes the body
     * with it and rejects an absent, empty, undecodable or malformed payload.
     *
     * <p>Spring parses the first {@code Content-Type} value to bind the body, before this method runs, and rejects a
     * value it cannot parse as a media type, a lower-case {@code charset} parameter naming an illegal or unsupported
     * charset included, through {@code GlobalExceptionHandler}'s 500
     * {@code { "message": "Invalid mime type \"<value>\": <reason>" }}; {@link #declaredCharset(HttpServletRequest)}
     * rejects the same charset names under any other spelling of the parameter with the same message. The service
     * is then not called and nothing is uploaded. That reply is a verification point for captured fixtures: the Mule
     * 3.8.0 listener rejected such a request before running the flow, with 400 {@code Bad Request} and the parser's
     * message ({@code DefaultHttpListener}).
     *
     * @param body the request body, or {@code null} when there is none
     * @param request the request, whose first {@code Content-Type} value is read
     * @return 200 with the XML the service uploaded, byte for byte, as {@code application/xml} in UTF-8
     * @throws IllegalArgumentException when the {@code Content-Type} declares an illegal or unsupported charset, or
     *                                  the service rejects the payload
     */
    @RequestMapping("/")
    public ResponseEntity<byte[]> upload(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        byte[] xml = employeeUploadService.process(body, declaredCharset(request));
        return ResponseEntity.status(HttpStatus.OK).contentType(APPLICATION_XML_UTF8).body(xml);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow for {@code OPTIONS} too.
     *
     * <p>A CORS pre-flight, an {@code OPTIONS} request carrying {@code Origin} and
     * {@code Access-Control-Request-Method}, reaches this method too, through
     * {@code config.PreflightDispatchConfig}, and runs the flow like any other {@code OPTIONS} request: the Mule
     * listener applied no CORS policy, so no origin is checked and no {@code Access-Control-*} or {@code Vary} header
     * is added.
     *
     * @param body the request body, or {@code null} when there is none
     * @param request the request, whose first {@code Content-Type} value is read
     * @return the response of {@link #upload(byte[], HttpServletRequest)}
     * @throws IllegalArgumentException as {@link #upload(byte[], HttpServletRequest)} does
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<byte[]> uploadOptions(@RequestBody(required = false) byte[] body,
                                                HttpServletRequest request) {
        return upload(body, request);
    }

    /**
     * Returns the charset the request's first {@code Content-Type} value declares, the value the Mule listener read,
     * or {@code null} when the header is absent or empty or the value has no {@code charset} parameter, so the
     * transform detects the encoding from the bytes.
     *
     * <p>The raw header is read because Spring Boot's {@code CharacterEncodingFilter} sets the request's character
     * encoding to UTF-8 on every request, which would hide a declared ISO-8859-1. The parameter name is matched
     * ignoring letter case, as HTTP defines it and the Mule listener's parser matched it, whereas
     * {@link MediaType#getCharset()} recognizes the lower-case name only; a value in quotes is unquoted as Spring
     * unquotes the lower-case parameter.
     *
     * @throws IllegalArgumentException when the parameter names an illegal or unsupported charset, with the message
     *                                  Spring gives the lower-case parameter
     */
    private static Charset declaredCharset(HttpServletRequest request) {
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (!StringUtils.hasLength(contentType)) {
            return null;
        }
        // Already parsed successfully to bind the body, so this parse cannot fail; its parameter map ignores case.
        String value = MediaType.parseMediaType(contentType).getParameter(CHARSET_PARAMETER);
        if (value == null) {
            return null;
        }
        String name = unquote(value);
        try {
            return Charset.forName(name);
        } catch (UnsupportedCharsetException e) {
            throw new IllegalArgumentException(
                    "Invalid mime type \"" + contentType + "\": unsupported charset '" + e.getCharsetName() + "'", e);
        } catch (IllegalCharsetNameException e) {
            throw new IllegalArgumentException("Invalid mime type \"" + contentType + "\": " + e.getMessage(), e);
        }
    }

    /** Strips one pair of enclosing double or single quotes, as Spring's media type parser does. */
    private static String unquote(String value) {
        boolean quoted = value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
