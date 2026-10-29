/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.scatter_gather_flow_control.service.ScatterGatherService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code scatter-gatherFlow} (scatter-gather-flow-control/src/main/app/scatter-gather.xml:4,7,59):
 * the listener on {@code /scatterGather}, whose response payload is produced by {@link ScatterGatherService}.
 *
 * <p>The listener (line 7) declares no {@code allowedMethods} and the flow reads no query, header or body, so every
 * method runs the flow and gets the same reply, and no handler declares {@code produces}, {@code consumes},
 * parameters or a request body: whatever the caller sends is ignored rather than rejected. The listener's host and
 * port (line 4) come from {@code server.address} and {@code server.port} in {@code application.yml}. The match is
 * exact; any other path falls through to the no-listener 404 of {@code GlobalExceptionHandler}.
 *
 * <p>The reply carries a {@code Content-Length} and no {@code Content-Type}: the flow's final {@code set-payload}
 * (line 59) declares no MIME type, and the Mule listener sent no Content-Type for such a payload. The bytes are
 * written to the servlet response directly, so no message converter adds one.
 *
 * <p>Nothing is caught here: the flow has no exception strategy, so a failure of the scatter-gather aggregation or
 * of the report mail reaches the JSON 500 of {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/scatterGather")
public class ScatterGatherController {

    private final ScatterGatherService scatterGatherService;

    public ScatterGatherController(ScatterGatherService scatterGatherService) {
        this.scatterGatherService = scatterGatherService;
    }

    /**
     * Answers {@code /scatterGather} for every method except {@code OPTIONS}; a {@code HEAD} response carries the
     * headers without the body.
     *
     * <p>The flow runs before the response is touched, so a failure still finds an uncommitted response for the
     * 500 of {@code GlobalExceptionHandler}.
     *
     * @param response the response the flow's payload is written to as UTF-8 bytes with status 200
     * @throws IOException when writing the response fails
     */
    @RequestMapping
    public void handle(HttpServletResponse response) throws IOException {
        String body = scatterGatherService.process();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    /**
     * Answers {@code OPTIONS /scatterGather} like every other method. A mapping without methods deliberately does
     * not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no
     * body; the Mule listener ran the flow for {@code OPTIONS} too. A CORS pre-flight reaches this handler as well:
     * {@code config.PreflightDispatchConfig} dispatches it as the plain {@code OPTIONS} request it is, because the
     * listener applies no CORS policy.
     *
     * @param response the response the flow's payload is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public void handleOptions(HttpServletResponse response) throws IOException {
        handle(response);
    }
}
