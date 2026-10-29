/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import java.io.IOException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.FimProxyService;

/**
 * HTTP trigger of flow {@code http-client-credentialsFlow}
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:39-47).
 *
 * <ul>
 *   <li>Line 40, {@code http:listener path="/FIM/*"} on {@code HTTP_Listener_Configuration}, {@code 0.0.0.0:8081}
 *       (line 9): the listener declares no {@code allowedMethods}, so every method is accepted, and no mapping
 *       here declares {@code produces}, {@code consumes}, parameters or headers.</li>
 *   <li>Line 41, {@code http:request} with path {@code http.request.uri} and method {@code http.method}: the
 *       request URI with its query string, raw as sent on the request line (no percent-decoding, normalization
 *       or re-encoding), and the request method are passed to {@link FimProxyService} unchanged, together with
 *       the request's Content-Type and body. The Undertow settings of {@code application.yml} accept literal
 *       {@code {client_id}} braces in the request target and leave its percent-encoding intact, so
 *       {@code getRequestURI()} and {@code getQueryString()} carry the bytes Mule's {@code http.request.uri}
 *       carried.</li>
 *   <li>The body is read from the servlet input stream, not through {@code @RequestBody}, whose
 *       {@code ServletServerHttpRequest} rebuilds a form-urlencoded POST body without a query string from the
 *       parsed request parameters, re-sorted and re-encoded. Every body, a form body of any method included,
 *       therefore reaches {@link FimProxyService} as the caller's bytes. Nothing consumes the request stream
 *       before this class: {@code application.yml} disables Boot's {@code FormContentFilter}, which would read
 *       PUT, PATCH and DELETE form bodies before dispatch and leave the stream empty here.</li>
 *   <li>Line 43, {@code http:uri-param client_id=${client.id}}: applied by {@link FimProxyService}, which also
 *       owns the body rules and the upstream call. This class holds no transformation or routing logic and
 *       writes the service's response as it is.</li>
 * </ul>
 * The flow declares no exception strategy, so every failure reaches the generic 500
 * {@code { "message": "<exception message>" }} of {@code GlobalExceptionHandler}.
 *
 * <p>The service's response is written to the servlet response directly, never through a message converter: its
 * status, its headers, which hold the upstream Content-Type as received and nothing else, and its body with a
 * {@code Content-Length}. When the upstream sent no Content-Type the reply carries none, where converter
 * negotiation would add {@code application/octet-stream}. Every response is complete when the handler returns:
 * a body is written to exactly its declared length, and an empty one is declared with length 0 and flushed.
 * {@code FrameworkServlet} afterwards still runs the servlet's own TRACE echo, which repeats the request headers,
 * {@code Authorization} included, and after an OPTIONS handler still adds an {@code Allow} header; a complete
 * response takes neither. A HEAD response carries the headers without the body. The service's result is obtained
 * before the response is touched, so a failure still finds an uncommitted response for the 500.
 *
 * <p>Tier 2A verification points, where the Spring MVC request handling may differ from the Mule listener:
 * <ol>
 *   <li>{@code /FIM/**} also matches the bare {@code /FIM} and {@code /FIM/}; how Mule's {@code /FIM/*} answered
 *       a bare {@code /FIM} is unverified.</li>
 *   <li>An upstream reply without a Content-Type is answered without one, because the Mule message's MIME type
 *       stayed <code>*&#47;*</code>, for which the Mule 3.8 listener sends no Content-Type.</li>
 *   <li>Form-encoded bodies of every method, POST included, are forwarded byte for byte. The Mule listener
 *       parsed a form-urlencoded body into a {@code ParameterMap}, which its requester re-encoded, so the bytes
 *       Mule forwarded may differ in order or encoding until a fixture settles them.</li>
 *   <li>TRACE is forwarded like every other method, because {@code application.yml} sets
 *       {@code spring.mvc.dispatch-trace-request}; whether the Mule listener ran the flow for TRACE is
 *       unverified.</li>
 *   <li>A Content-Type that cannot be parsed ends in the 500 of {@code GlobalExceptionHandler} before the body is
 *       read and the FIM server is called.</li>
 *   <li>An empty query ({@code /FIM/x?}) is forwarded without its {@code ?}, because the servlet API reports an
 *       empty query string as absent.</li>
 *   <li>A path holding a {@code %} not followed by two hexadecimal digits ({@code /FIM/x%zz}) is answered 400 with
 *       an empty body by {@code config/InvalidPercentEncodingFilter} before this class runs; how the Mule listener
 *       answered such a path is unverified.</li>
 * </ol>
 */
@RestController
public class FimProxyController {

    private final FimProxyService fimProxyService;

    public FimProxyController(FimProxyService fimProxyService) {
        this.fimProxyService = fimProxyService;
    }

    /**
     * Forwards a {@code /FIM/**} request of any method but OPTIONS through {@link FimProxyService}.
     *
     * @param request  the inbound request, whose method, raw URI, Content-Type and body are forwarded
     * @param response the response the service's result is written to
     * @throws org.springframework.http.InvalidMediaTypeException when the Content-Type cannot be parsed; the body is
     *         not read and the FIM server is not called
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping("/FIM/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String query = request.getQueryString();
        String rawUri = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
        String contentTypeHeader = request.getContentType();
        MediaType contentType = StringUtils.hasLength(contentTypeHeader)
                ? MediaType.parseMediaType(contentTypeHeader) : null;
        byte[] body = readBody(request);
        write(fimProxyService.forward(request.getMethod(), rawUri, contentType, body), response);
    }

    /**
     * Forwards {@code OPTIONS /FIM/**} like every other method. A mapping without methods deliberately does not
     * match OPTIONS, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule
     * listener ran the flow for OPTIONS too.
     *
     * @param request  the inbound request
     * @param response the response the service's result is written to
     * @throws IOException when the request body cannot be read or the response cannot be written
     */
    @RequestMapping(path = "/FIM/**", method = RequestMethod.OPTIONS)
    public void proxyOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy(request, response);
    }

    /**
     * Reads the request body as the caller sent it.
     *
     * @return the body bytes, or {@code null} when the request carried none, as {@code @RequestBody(required =
     *         false)} binds an absent body
     */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        return body.length == 0 ? null : body;
    }

    /**
     * Writes {@code result} to {@code response} and completes it (see the class comment): the status, every header
     * of {@code result} as given, and the body with its {@code Content-Length}, an absent body as an empty one.
     */
    private static void write(ResponseEntity<byte[]> result, HttpServletResponse response) throws IOException {
        response.setStatus(result.getStatusCode().value());
        result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        byte[] body = result.getBody() == null ? new byte[0] : result.getBody();
        response.setContentLength(body.length);
        if (body.length == 0) {
            response.flushBuffer();
            return;
        }
        ServletOutputStream stream = response.getOutputStream();
        stream.write(body);
        stream.flush();
    }
}
