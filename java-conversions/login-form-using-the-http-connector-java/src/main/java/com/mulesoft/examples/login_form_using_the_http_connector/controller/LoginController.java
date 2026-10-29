/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.login_form_using_the_http_connector.client.LoginRequesterClient.LoginResponse;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;

/**
 * HTTP triggers of the three flows of {@code login-form-using-the-http-connector.xml}, all on the
 * {@code HttpListenerConfig} listener ({@code 0.0.0.0:${http.port}}, xml:4), whose host and port come from
 * {@code application.yml}:
 * <ul>
 *   <li>{@code GetLoginPageFlow}, {@code allowedMethods="GET" path="/login"} (xml:9), and {@code DoLoginFlow},
 *       {@code allowedMethods="POST" path="/login"} (xml:16): {@link #login}, which renders the page
 *       ({@link #loginPage}) for {@code GET} and logs in ({@link #doLogin}) for {@code POST} in any letter case,
 *       because Mule 3.8's {@code MethodRequestMatcher} compared the request method with {@code allowedMethods}
 *       ignoring case, and {@link #loginUnsupportedMethod}, which rejects {@code OPTIONS};</li>
 *   <li>{@code CallLoginFlowUsingRequester}, {@code path="/requesterLogin"} with no {@code allowedMethods}
 *       (xml:35): {@link #requesterLogin} and {@link #requesterLoginOptions}, so every method runs the flow.</li>
 * </ul>
 *
 * <p>The class only binds the request and writes the service's result. The credential check, the templates and the
 * username log line belong to {@link LoginService}; a rejected login propagates as
 * {@link LoginService.LoginFailedException} to {@code LoginExceptionHandler} (403 and {@code loginFailure.html}), and
 * every other failure to the generic advice of {@code GlobalExceptionHandler}. Each handler obtains the service's
 * result before it touches the response, so an advice always finds the response uncommitted.
 *
 * <p>Bodies are written to the servlet output stream with a {@code Content-Length}, never through a message
 * converter or the servlet writer, either of which would add or rewrite a Content-Type. The login page goes out
 * with no Content-Type, because {@code GetLoginPageFlow} sets none; the success page carries exactly
 * {@link #HTML_CONTENT_TYPE}.
 */
@RestController
public class LoginController {

    /**
     * Content-Type of the success page, the exact value of {@code set-property propertyName="Content-Type"}
     * (xml:25), space included.
     */
    static final String HTML_CONTENT_TYPE = "text/html; charset=UTF-8";

    /**
     * Body of the 400 the Mule 3.8 listener sent, before any flow ran, for a form body it could not URL-decode
     * ({@code HttpRequestToMuleEvent} raised {@code HttpRequestParsingException} with this message, and
     * {@code DefaultHttpListener} answered {@code BAD_REQUEST} with the message as the body).
     */
    static final String UNDECODABLE_FORM_MESSAGE = "Cannot decode x-www-form-urlencoded payload";

    private static final Logger LOG = LoggerFactory.getLogger(LoginController.class);

    /** The methods the two {@code /login} listeners accept together. */
    private static final List<String> LOGIN_METHODS = List.of("GET", "POST");

    /** Separates the pairs of a form body. */
    private static final String PAIR_SEPARATOR = "&";

    /** Separates a pair's name from its value, at its first occurrence. */
    private static final char NAME_VALUE_SEPARATOR = '=';

    private final LoginService loginService;

    /**
     * @param loginService the processor logic of the three flows
     */
    public LoginController(LoginService loginService) {
        this.loginService = Objects.requireNonNull(loginService, "loginService");
    }

    /**
     * The two {@code /login} listeners for every method but {@code OPTIONS}: {@code GET} in any letter case runs
     * {@code GetLoginPageFlow} ({@link #loginPage}), {@code POST} in any letter case runs {@code DoLoginFlow}
     * ({@link #doLogin}), and any other method gets the 405 of {@code GlobalExceptionHandler}, as the Mule 3.8
     * listener, which compared the request method with {@code allowedMethods} ignoring case, answered it.
     *
     * <p>The mapping declares no method because Spring MVC compares declared methods case-sensitively and would
     * reject {@code get} or {@code Post} with the 405 before any handler ran. Without a declared method it also matches
     * {@code HEAD}, which this method rejects instead of rendering the page, and {@code TRACE}, which reaches it
     * because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request: true}. The method is checked
     * before the body is read, so a rejected request gets the 405 whatever its body.
     *
     * @param request  the request, whose method selects the flow and whose body is the posted form
     * @param response the response the page is written to
     * @throws HttpRequestMethodNotSupportedException when the method is neither {@code GET} nor {@code POST} in any
     *         letter case; it reaches the generic 405 handler
     * @throws IOException if the body cannot be read or the page cannot be written
     */
    @RequestMapping("/login")
    public void login(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)) {
            loginPage(response);
        } else if ("POST".equalsIgnoreCase(method)) {
            doLogin(request, response);
        } else {
            throw new HttpRequestMethodNotSupportedException(method, LOGIN_METHODS);
        }
    }

    /**
     * Rejects {@code OPTIONS} on {@code /login}, which neither original listener accepted, with the 405 of
     * {@code GlobalExceptionHandler}. A mapping without methods deliberately does not match {@code OPTIONS} spelled
     * in upper case, so without this mapping Spring MVC would answer it itself with 200 and an {@code Allow} header.
     * Every other method outside GET and POST, {@code HEAD} and {@code OPTIONS} in other letter cases included,
     * reaches {@link #login}, which rejects it.
     *
     * @param request the rejected request
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = "/login", method = RequestMethod.OPTIONS)
    public void loginUnsupportedMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), LOGIN_METHODS);
    }

    /**
     * {@code CallLoginFlowUsingRequester} for every method except {@code OPTIONS} spelled in upper case: relays the
     * response of {@code POST /login} called with the configured credentials. The inbound body is ignored, because
     * the flow replaced the payload with the credential map (xml:38-40).
     *
     * @param response the response the relayed body is written to
     * @throws IOException if the relayed body cannot be written
     */
    @RequestMapping("/requesterLogin")
    public void requesterLogin(HttpServletResponse response) throws IOException {
        relayRequesterLogin(response);
    }

    /**
     * {@code CallLoginFlowUsingRequester} for {@code OPTIONS}. A mapping without methods deliberately does not match
     * {@code OPTIONS} spelled in upper case, which Spring MVC would otherwise answer itself; the Mule listener ran the
     * flow for it too, as it did for {@code options} in any other letter case, which reaches {@link #requesterLogin}.
     *
     * @param response the response the relayed body is written to
     * @throws IOException if the relayed body cannot be written
     */
    @RequestMapping(path = "/requesterLogin", method = RequestMethod.OPTIONS)
    public void requesterLoginOptions(HttpServletResponse response) throws IOException {
        relayRequesterLogin(response);
    }

    /**
     * Writes 200, the relayed Content-Type byte for byte when there is one, and the relayed body. The status is 200
     * whatever {@code POST /login} answered below 400, because the Mule listener does not relay the requester's
     * status; a status of 400 or more, and any connection failure, leave {@link LoginService#requesterLogin} as an
     * exception for the generic 500.
     */
    private void relayRequesterLogin(HttpServletResponse response) throws IOException {
        LoginResponse relayed = loginService.requesterLogin();
        byte[] body = relayed.body() == null ? new byte[0] : relayed.body();
        response.setStatus(HttpServletResponse.SC_OK);
        if (relayed.contentType() != null) {
            writeContentTypeVerbatim(response, relayed.contentType());
        }
        writeBody(response, body);
    }

    /** {@code GetLoginPageFlow}: 200 with {@code login/index.html} byte for byte and no Content-Type. */
    private void loginPage(HttpServletResponse response) throws IOException {
        byte[] page = loginService.loginPage().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        writeBody(response, page);
    }

    /**
     * {@code DoLoginFlow}: hands the posted form to {@link LoginService#login} and answers 200 with the success page
     * and {@link #HTML_CONTENT_TYPE}. A rejected login leaves this method as {@link LoginService.LoginFailedException}
     * ({@code message-filter throwOnUnaccepted="true"} and the catch strategy, xml:20-22, 27-30).
     *
     * <p>The form is the request body alone, as the Mule payload was; query parameters never count (see
     * {@link #readForm}). A form body that cannot be URL-decoded is answered as the listener answered it, with 400
     * {@link #UNDECODABLE_FORM_MESSAGE}, and the flow does not run.
     */
    private void doLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<Map<String, String>> form = readForm(request);
        if (form.isEmpty()) {
            rejectUndecodableForm(response);
            return;
        }
        byte[] page = loginService.login(form.get()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        writeContentTypeVerbatim(response, HTML_CONTENT_TYPE);
        writeBody(response, page);
    }

    /**
     * Reads the posted form from the request body only. Servlet parameters are never consulted, because they merge
     * the query string into the form.
     *
     * <p>The body is a form only when the request's media type is exactly {@code application/x-www-form-urlencoded}
     * (any case, any parameters); otherwise, and when the Content-Type is missing or unparseable, the form is empty,
     * which the service rejects. A form body is read as text in the Content-Type's charset, or UTF-8 when it names
     * none, and decoded as the Mule 3.8 HTTP module's {@code HttpParser#decodeString} filled its {@code ParameterMap}:
     * <ul>
     *   <li>a blank text gives the empty form;</li>
     *   <li>otherwise the text is split on {@code &} with {@link String#split} semantics, so a leading or interior
     *       empty pair is kept as the name {@code ""} with the value {@code null} and trailing empty pairs are
     *       dropped, and each pair is split at its first {@code =};</li>
     *   <li>name and value are URL-decoded with the same charset ({@code +} becomes a space), and a pair without
     *       {@code =} maps its decoded name to {@code null};</li>
     *   <li>pairs keep their order, and a repeated name keeps its first position and its last value, because Mule
     *       3.8's {@code ParameterMap#get} returned {@code values.getLast()}, so {@code password=mule&password} gives
     *       {@code password} the value {@code null}.</li>
     * </ul>
     * Pairs are not trimmed: literal whitespace and percent-encoded or {@code +} whitespace both survive into the
     * names and values, so {@code password=mule } reaches the exact credential check as {@code "mule "} and is
     * rejected, as the original rejected it.
     *
     * @return the form, or {@link Optional#empty()} when a form body cannot be URL-decoded
     * @throws IOException if the body cannot be read
     */
    private static Optional<Map<String, String>> readForm(HttpServletRequest request) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        String contentType = request.getContentType();
        if (contentType == null) {
            return Optional.of(form);
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return Optional.of(form);
        }
        // Exact type and subtype, not isCompatibleWith: the Mule 3.8 listener decoded a body as a form only when the
        // parsed type/subtype equalled application/x-www-form-urlencoded, so */* or application/* stays a stream,
        // which the credential filter rejects.
        if (!MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(mediaType)) {
            return Optional.of(form);
        }
        Charset charset = mediaType.getCharset() != null ? mediaType.getCharset() : StandardCharsets.UTF_8;
        String body = StreamUtils.copyToString(request.getInputStream(), charset);
        if (body.isBlank()) {
            return Optional.of(form);
        }
        try {
            for (String pair : body.split(PAIR_SEPARATOR)) {
                int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
                if (separator < 0) {
                    form.put(URLDecoder.decode(pair, charset), null);
                } else {
                    form.put(URLDecoder.decode(pair.substring(0, separator), charset),
                            URLDecoder.decode(pair.substring(separator + 1), charset));
                }
            }
        } catch (IllegalArgumentException e) {
            // An invalid percent-encoding. An I/O failure stays an IOException for the generic 500.
            return Optional.empty();
        }
        return Optional.of(form);
    }

    /**
     * Answers an undecodable form body as {@code DefaultHttpListener} did: a WARN log line, then 400 with the
     * standard reason phrase, no Content-Type and {@link #UNDECODABLE_FORM_MESSAGE} as the body.
     */
    private static void rejectUndecodableForm(HttpServletResponse response) throws IOException {
        LOG.warn("Exception occurred parsing request: {}", UNDECODABLE_FORM_MESSAGE);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        writeBody(response, UNDECODABLE_FORM_MESSAGE.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Sets {@code Content-Type} to exactly {@code value}. Undertow re-serialises a value passed to
     * {@code setContentType} or {@code setHeader}, turning {@code text/html; charset=UTF-8} into
     * {@code text/html;charset=UTF-8}, so on Undertow the header is put on the exchange directly. Without an Undertow
     * request on the calling thread (MockMvc, another container) the servlet response is used. Called before any
     * body byte is written.
     */
    private static void writeContentTypeVerbatim(HttpServletResponse response, String value) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().getResponseHeaders().put(Headers.CONTENT_TYPE, value);
        } else {
            response.setContentType(value);
        }
    }

    /** Writes {@code bytes} with their {@code Content-Length} to the output stream, never to the writer. */
    private static void writeBody(HttpServletResponse response, byte[] bytes) throws IOException {
        response.setContentLength(bytes.length);
        OutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
