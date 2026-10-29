/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
 *   <li>line 5, {@code http:listener path="/" allowedMethods="POST"}: this class, mapped to {@code /} with no
 *       method, {@code consumes}, {@code produces}, parameter or header condition, because the Mule listener
 *       compared the method with {@code allowedMethods} ignoring case, accepted any {@code Content-Type} and
 *       ignored {@code Accept}, so neither 415 nor 406 can occur; {@link #calculate} admits {@code POST} in any
 *       letter case;</li>
 *   <li>line 6, {@code byte-array-to-string-transformer}: the listener's payload. For a non-empty
 *       {@code application/x-www-form-urlencoded} body that is the decoded parameter map, handed to
 *       {@link AdditionService#handle(Map)}; for any other request the raw body bytes and the charset the
 *       request's {@code Content-Type} declares, UTF-8 when it declares none or an invalid one (the application
 *       encoding of {@code mule-deploy.properties}), handed to {@link AdditionService#handle(byte[], Charset)}.
 *       The service owns the rendering, the decoding, the JavaScript sum, the logger and the payload of lines
 *       6-15.</li>
 * </ul>
 *
 * <p>The response is 200 with the payload's UTF-8 bytes, their {@code Content-Length} and no {@code Content-Type}:
 * the flow's {@code set-payload} declares no MIME type, and the Mule listener sent no Content-Type for such a
 * payload. It is written to the servlet response directly, so no message converter adds one.
 *
 * <p>Every method other than {@code POST} is answered with the listener's 405, every other path ({@code /other}
 * included) with the no-listener 404, and any failure of the flow, an unparseable body included, with the platform
 * 500: the flow declares no exception strategy, so all three are written by {@code GlobalExceptionHandler}. The one
 * failure answered here is the listener's own: a form-urlencoded body it cannot decode gets its 400 before the flow
 * runs (see {@link #rejectUndecodableForm}).
 */
@RestController
public class CalculatorController {

    private static final String PATH = "/";

    /**
     * The listener's {@code allowedMethods}. Mule 3.8's {@code MethodRequestMatcher} lowercased both the request
     * method and this value, so {@code post} and {@code Post} are admitted too.
     */
    private static final String ALLOWED_METHOD = "POST";

    private static final String PAIR_SEPARATOR = "&";

    private static final String NAME_VALUE_SEPARATOR = "=";

    /** The text whose presence in {@code Content-Type} made the Mule listener parse the body as multipart. */
    private static final String MULTIPART = "multipart";

    /** Message of the Mule listener's failure to decode a form-urlencoded body. */
    private static final String FORM_DECODING_FAILURE = "Cannot decode x-www-form-urlencoded payload";

    private final AdditionService additionService;

    /**
     * @param additionService decodes the body, sums its values as the original script did, logs the outcome and
     *                        builds the response payload
     */
    public CalculatorController(AdditionService additionService) {
        this.additionService = additionService;
    }

    /**
     * Answers {@code POST /}, in any letter case, with {@code Sum is: <sum>.}.
     *
     * <p>The method check comes first, and the body is read only after it, so a rejected method is answered with the
     * 405 whatever its body. The body is read from the servlet stream rather than through {@code @RequestBody} or a
     * request parameter: for an {@code application/x-www-form-urlencoded} body, which curl's {@code -d} sends by
     * default, Spring would rebuild the body from the parsed parameter map and Undertow would consume the stream on
     * the first parameter access, so the service would not see the parameters the Mule listener decoded from it. An
     * empty body is passed on as a zero-length array. A form-urlencoded body the listener cannot decode is answered
     * with the committed 400 of {@link #rejectUndecodableForm}, and the service is not called. Otherwise nothing is
     * written before the service returns, so a failure still finds an uncommitted response for the generic 500
     * handler.
     *
     * @param request  the request, whose method, headers and body are read
     * @param response the response the service's payload is written to
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code POST} in some letter case; it
     *         reaches the generic 405 handler
     * @throws IOException when reading the request body or writing the response fails; it reaches the generic 500
     *         handler
     * @throws IllegalArgumentException when the service rejects the body; it reaches the generic 500 handler
     */
    @RequestMapping(PATH)
    public void calculate(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        if (!ALLOWED_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
        }
        byte[] body = request.getInputStream().readAllBytes();
        Charset charset = requestCharset(request);
        String result;
        if (body.length > 0 && isFormUrlEncoded(request)) {
            Map<String, String> parameters;
            try {
                parameters = formParameters(new String(body, StandardCharsets.UTF_8), charset);
            } catch (UndecodableFormException e) {
                rejectUndecodableForm(response, e);
                return;
            }
            result = additionService.handle(parameters);
        } else {
            result = additionService.handle(body, charset);
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Answers a form-urlencoded body the listener could not decode as the Mule 3.8 listener did before running the
     * flow: {@code DefaultHttpListener} sent {@code BAD_REQUEST} with the failure's message as a byte entity, so the
     * reply is a 400 with Undertow's standard reason phrase {@code Bad Request}, the message's bytes with their
     * {@code Content-Length}, and no {@code Content-Type}. It is written to the servlet response and committed here,
     * so no message converter adds a Content-Type and {@code FrameworkServlet}'s {@code TRACE} and {@code OPTIONS}
     * fallbacks add nothing.
     *
     * @param response the response the 400 is written to
     * @param failure  the decoding failure, whose message is the body
     * @throws IOException when writing the reply fails
     */
    private static void rejectUndecodableForm(HttpServletResponse response, UndecodableFormException failure)
            throws IOException {
        byte[] bytes = failure.getMessage().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Rejects {@code OPTIONS /} with the listener's 405, which the generic handler writes as
     * {@code Method not allowed for endpoint: /}.
     *
     * <p>A mapping without methods does not match {@code OPTIONS}, so without this one Spring MVC would answer it
     * itself with 200 and an {@code Allow} header. Every other method reaches {@link #calculate}, which rejects it;
     * {@code TRACE} does because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request: true}.
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

    /**
     * Tells whether the Mule 3.8 listener made the body a parameter map: the raw {@code Content-Type} names
     * {@code application/x-www-form-urlencoded}, ignoring parameters and letter case, and does not contain
     * {@code multipart}, which made the listener parse the body as multipart parts instead. A header that is absent,
     * blank or cannot be parsed keeps the body on the byte path, as {@link #requestCharset} keeps it on UTF-8.
     */
    private static boolean isFormUrlEncoded(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (!StringUtils.hasText(header) || header.contains(MULTIPART)) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(MediaType.parseMediaType(header));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }

    /**
     * Decodes a form-urlencoded body as Mule 3.8's {@code org.mule.module.http.internal.HttpParser#decodeString}
     * filled its {@code ParameterMap}: a blank body gives an empty map; otherwise the body is split on {@code &}
     * ({@link String#split} semantics, so a leading empty pair is kept as the name {@code ""} and trailing ones are
     * dropped) and each pair on its first {@code =}, name and value are URL-decoded with {@code charset}, a pair
     * without {@code =} gets a {@code null} value, and a repeated name keeps its first position and its last value,
     * the value the map returned for that name. The listener read the form text with the JVM default charset, here
     * the application encoding UTF-8, and applied the declared charset only when URL-decoding it.
     *
     * <p>A malformed percent escape fails with the listener's own message, {@value #FORM_DECODING_FAILURE}, as the
     * {@code HttpRequestParsingException} that Mule's {@code HttpRequestToMuleEvent} raised before the flow ran;
     * {@link #calculate} answers it with the listener's 400.
     *
     * @param body    the form text
     * @param charset the charset the {@code Content-Type} declares, UTF-8 when it declares none
     * @return the decoded parameters in first-occurrence order
     * @throws UndecodableFormException when a name or value holds a malformed percent escape
     */
    private static Map<String, String> formParameters(String body, Charset charset) throws UndecodableFormException {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (body.isBlank()) {
            return parameters;
        }
        try {
            for (String pair : body.split(PAIR_SEPARATOR)) {
                int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
                if (separator < 0) {
                    parameters.put(URLDecoder.decode(pair, charset), null);
                } else {
                    parameters.put(URLDecoder.decode(pair.substring(0, separator), charset),
                            URLDecoder.decode(pair.substring(separator + 1), charset));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new UndecodableFormException(e);
        }
        return parameters;
    }

    /**
     * The Mule 3.8 listener's {@code HttpRequestParsingException} for a form-urlencoded body it cannot decode, raised
     * while the payload is bound and answered by {@link #rejectUndecodableForm}. It is checked and private so that
     * no other {@link IllegalArgumentException}, such as the service's rejection of a body its script cannot
     * evaluate, can be mistaken for it: those still reach the 500 of {@code GlobalExceptionHandler}.
     */
    private static final class UndecodableFormException extends Exception {

        /** The exception carries no state beyond its message and cause. */
        private static final long serialVersionUID = 1L;

        /**
         * @param cause the {@link URLDecoder} failure on a malformed percent escape
         */
        UndecodableFormException(IllegalArgumentException cause) {
            super(FORM_DECODING_FAILURE, cause);
        }
    }
}
