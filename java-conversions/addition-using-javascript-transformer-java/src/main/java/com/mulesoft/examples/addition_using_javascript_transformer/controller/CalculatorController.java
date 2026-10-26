/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.controller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.addition_using_javascript_transformer.service.AdditionService;

/**
 * HTTP trigger of flow {@code javascript-calculatorFlow1}
 * (addition-using-javascript-transformer/src/main/app/javascript-calculator.xml).
 *
 * <p>Source mapping:
 * <ul>
 *   <li>line 3, {@code http:listener-config host="0.0.0.0" port="${http.port}"}: {@code server.address} and
 *       {@code server.port} in {@code application.yml};</li>
 *   <li>line 5, {@code http:listener path="/" allowedMethods="POST"}: this class, mapped to {@code POST /} with no
 *       {@code consumes}, {@code produces}, parameter or header condition, because the Mule listener accepted any
 *       {@code Content-Type} and ignored {@code Accept}, so neither 415 nor 406 can occur;</li>
 *   <li>line 6, {@code byte-array-to-string-transformer}: the raw body bytes and the charset the request's
 *       {@code Content-Type} declares, UTF-8 when it declares none or an invalid one (the application encoding of
 *       {@code mule-deploy.properties}), handed to {@link AdditionService#handle(byte[], Charset)}, which owns the
 *       decoding, the JavaScript sum, the logger and the payload of lines 7-15.</li>
 * </ul>
 *
 * <p>Every method other than {@code POST} is answered with the listener's 405, every other path ({@code /other}
 * included) with the no-listener 404, and any failure, an unparseable body included, with the platform 500: the
 * flow declares no exception strategy, so all three are written by {@code GlobalExceptionHandler}.
 */
@RestController
public class CalculatorController {

    private static final String PATH = "/";

    private static final String ALLOWED_METHOD = "POST";

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final AdditionService additionService;

    /**
     * @param additionService decodes the body, sums its values as the original script did, logs the outcome and
     *                        builds the response payload
     */
    public CalculatorController(AdditionService additionService) {
        this.additionService = additionService;
    }

    /**
     * Answers {@code POST /} with {@code Sum is: <sum>.}.
     *
     * <p>The body is read from the servlet stream rather than through {@code @RequestBody} or a request parameter:
     * for an {@code application/x-www-form-urlencoded} body, which curl's {@code -d} sends by default, Spring would
     * rebuild the body from the parsed parameter map and Undertow would consume the stream on the first parameter
     * access, so a JSON body sent with the form type would no longer reach the service byte for byte. An empty
     * body is passed on as a zero-length array.
     *
     * @param request the {@code POST /} request
     * @return 200 {@code text/plain;charset=UTF-8} with the service's payload; the preset Content-Type makes Spring
     *         write it without consulting {@code Accept}
     * @throws IOException when reading the request body fails; it reaches the generic 500 handler
     * @throws IllegalArgumentException when the service rejects the body; it reaches the generic 500 handler
     */
    @RequestMapping(path = PATH, method = RequestMethod.POST)
    public ResponseEntity<String> calculate(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        Charset charset = requestCharset(request);
        String result = additionService.handle(body, charset);
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(result);
    }

    /**
     * Rejects {@code OPTIONS /} with the listener's 405, which the generic handler writes as
     * {@code Method not allowed for endpoint: /}.
     *
     * <p>Without this mapping Spring MVC would answer {@code OPTIONS} itself with 200 and an {@code Allow} header.
     * Every other method is already rejected by the handler mapping; {@code TRACE} reaches it because
     * {@code application.yml} sets {@code spring.mvc.dispatch-trace-request: true}.
     *
     * @return never returns normally
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = PATH, method = RequestMethod.OPTIONS)
    public ResponseEntity<String> options() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(ALLOWED_METHOD));
    }

    /**
     * Returns the charset the raw {@code Content-Type} header declares, or UTF-8 when the header is absent, blank,
     * names no charset, or cannot be parsed (an unknown or illegal charset name included).
     *
     * <p>{@link HttpServletRequest#getCharacterEncoding()} is deliberately not used: Boot's
     * {@code CharacterEncodingFilter} sets it to UTF-8 on every request, which would hide a declared charset such
     * as ISO-8859-1.
     */
    private static Charset requestCharset(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (StringUtils.hasText(header)) {
            try {
                Charset declared = MediaType.parseMediaType(header).getCharset();
                if (declared != null) {
                    return declared;
                }
            } catch (InvalidMediaTypeException | IllegalCharsetNameException | UnsupportedCharsetException ex) {
                // An unusable Content-Type is not an error of the listener: decode with the application encoding.
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }
}
