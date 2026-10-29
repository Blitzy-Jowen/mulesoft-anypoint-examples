/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.OrderOrchestrationService;

/**
 * Database initialisation on port 8091, replacing the inbound listener of the {@code databaseInitialisation} flow
 * ({@code fulfillment.xml:159-172}): {@code http:listener allowedMethods="GET" path="populate"} on
 * {@code HTTP_Listener_Configuration}, {@code 0.0.0.0:8091} (line 24).
 *
 * <p>The class only binds the request and writes the flow's payload. Both {@code db:execute-ddl} statements, the
 * {@code set-payload db populated} and the catch branch's {@code table already populated} belong to
 * {@link OrderOrchestrationService#populateDatabase()}, which never throws. The listener read neither the query nor
 * the body, so nothing is bound from either, and no handler declares {@code produces} or {@code consumes}: any
 * {@code Accept} or {@code Content-Type} the caller sends is ignored rather than rejected with 406 or 415. Confining
 * {@code /populate} to port 8091 is the listener routing's job ({@code listeners.additional} entry {@code populate},
 * enforced by {@code PortRoutingFilter}), not this class's.
 *
 * <p>The listener accepted {@code GET} alone, compared ignoring case as Mule 3.8's {@code MethodRequestMatcher} did,
 * so {@code get} runs the flow too. Every other method, {@code HEAD}, {@code OPTIONS} and {@code TRACE} included,
 * gets the 405 {@code Method not allowed for endpoint: /populate} of {@code exception.GlobalExceptionHandler}, and no
 * DDL runs. A {@code @GetMapping} would not do: Spring MVC answers {@code HEAD} through it and matches {@code GET} in
 * upper case only, while a listener with {@code allowedMethods} accepted those methods alone.
 *
 * <p>The payload goes out as 200 with its {@code Content-Length} and no {@code Content-Type}, written to the servlet
 * response directly so that no message converter adds one: the flow's {@code set-payload} declares no MIME type, and
 * the Mule listener sent no Content-Type for such a payload, as for every {@code set-payload} reply of the other
 * converted examples. Tier 2A verification points, where a captured fixture of the original governs: the absent
 * Content-Type, and the 405 the original answered to {@code HEAD}.
 */
@RestController
public class PopulateController {

    /** The listener's {@code path}. */
    private static final String POPULATE_PATH = "/populate";

    /** The listener's {@code allowedMethods}. */
    private static final String ALLOWED_METHOD = "GET";

    private final OrderOrchestrationService orderOrchestrationService;

    /**
     * @param orderOrchestrationService runs the {@code databaseInitialisation} flow after its listener
     */
    public PopulateController(OrderOrchestrationService orderOrchestrationService) {
        this.orderOrchestrationService = orderOrchestrationService;
    }

    /**
     * Runs the {@code databaseInitialisation} flow for {@code GET /populate}, in any letter case. The match is exact:
     * {@code /populate/} and {@code /populate/x} are unserved paths.
     *
     * <p>The mapping declares no method, so that the method can be compared with {@code allowedMethods} ignoring case;
     * it matches every method but {@code OPTIONS}, which {@link #rejectOptions} takes, and the check here rejects the
     * rest. The payload is obtained before the response is touched, so a failure would still find an uncommitted
     * response for the generic 500.
     *
     * @param request  the request, whose method is checked
     * @param response the response the payload is written to, as 200 with its UTF-8 bytes
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code GET} in some letter case
     * @throws IOException                            when writing the response fails
     */
    @RequestMapping(POPULATE_PATH)
    public void populate(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        if (!ALLOWED_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
        }
        byte[] bytes = orderOrchestrationService.populateDatabase().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }

    /**
     * Rejects {@code OPTIONS} on {@code /populate} with the listener's 405, which the generic handler of the exception
     * package writes as {@code Method not allowed for endpoint: /populate}, without an {@code Allow} header.
     *
     * <p>A mapping without methods does not match {@code OPTIONS}, so without this one Spring MVC would answer it
     * itself with 200 and an {@code Allow} header, which the GET-only listener refused. {@code PreflightDispatchConfig}
     * dispatches a CORS pre-flight here as the plain {@code OPTIONS} request it is. This match is exact, so
     * {@code options} in any other letter case reaches {@link #populate}, which rejects it. No DDL runs.
     *
     * @param request the rejected request, whose method is reported
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = POPULATE_PATH, method = RequestMethod.OPTIONS)
    public void rejectOptions(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
    }
}
