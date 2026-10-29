/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.ChoiceExceptionHandler;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.InputValidationService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code choice-error-handlingFlow1}
 * ({@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:3,5-7}): the listener on
 * {@code /} of the {@code 0.0.0.0:${http.port}} listener configuration, whose host and port come from
 * {@code application.yml}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method is accepted and runs the flow; no mapping
 * declares {@code produces} or {@code consumes}, so no {@code Accept} or {@code Content-Type} is rejected with 406
 * or 415. The match is exact: any other path falls through to the no-listener 404 of {@code GlobalExceptionHandler}.
 *
 * <p>The class only binds the request and the service result. The flow's processors run in
 * {@link InputValidationService}; the status and reason phrase of the two error branches, which the original
 * response builder read from {@code flowVars['statusCode']} and {@code flowVars['reason']}, belong to
 * {@link ChoiceExceptionHandler}, which renders the parsed payload stored under
 * {@link ChoiceExceptionHandler#PAYLOAD_ATTRIBUTE}. The validator's {@link IllegalArgumentException} and
 * {@link NullPointerException} therefore propagate unwrapped, and every other failure reaches the platform-default
 * 500.
 *
 * <p>The success reply and the filter-rejection reply are written to the servlet response directly, with a
 * {@code Content-Length} and no {@code Content-Type}: the flow's {@code set-payload} declares no MIME type, and the
 * Mule listener sent no Content-Type for such a payload.
 */
@RestController
public class ValidationController {

    /**
     * Content-Type of the two error-branch bodies, which {@link ChoiceExceptionHandler} presets on each entity so
     * Spring writes it without consulting {@code Accept}; the success body carries none. A Tier 2A verification
     * point of the conversion plan.
     */
    public static final MediaType RESPONSE_CONTENT_TYPE = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final InputValidationService inputValidationService;

    public ValidationController(InputValidationService inputValidationService) {
        this.inputValidationService = inputValidationService;
    }

    /**
     * Runs the flow for every method except {@code OPTIONS}, {@code TRACE} included; a {@code HEAD} response carries
     * the headers without the body.
     *
     * @param body     the raw request body, {@code null} when the request has none
     * @param request  the current request, which carries the payload to the error branches
     * @param response the current response, to which 200 {@code Input data validation passed.} is written, or an
     *                 empty 200 when a filter rejects the message
     * @throws IOException if the reply cannot be written
     */
    @RequestMapping("/")
    public void handle(@RequestBody(required = false) byte[] body, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        respond(body, request, response);
    }

    /**
     * Runs the flow for {@code OPTIONS} like every other method. A mapping without methods deliberately does not
     * match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header; the Mule
     * listener ran the flow for {@code OPTIONS} too.
     *
     * @param body     the raw request body, {@code null} when the request has none
     * @param request  the current request, which carries the payload to the error branches
     * @param response the current response, written as by {@link #handle}
     * @throws IOException if the reply cannot be written
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void handleOptions(@RequestBody(required = false) byte[] body, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        respond(body, request, response);
    }

    /**
     * Runs the flow and writes its result. Nothing is written before validation returns, so the validator's
     * exceptions still find an uncommitted response for {@link ChoiceExceptionHandler} and every other failure for
     * the 500 of {@code GlobalExceptionHandler}.
     */
    private void respond(byte[] body, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Spring binds an absent body as null; an empty array makes it fail as unreadable JSON (the 500) rather than
        // as a null argument, which would be an IllegalArgumentException and select the "Invalid input data" branch.
        byte[] input = body == null ? new byte[0] : body;
        HashMap<String, Object> payload = inputValidationService.toPayload(input);
        // Stored before validation: the error branches render "#[payload]" from it.
        request.setAttribute(ChoiceExceptionHandler.PAYLOAD_ATTRIBUTE, payload);
        Optional<String> result = inputValidationService.validate(payload);
        response.setStatus(HttpServletResponse.SC_OK);
        if (result.isPresent()) {
            byte[] bytes = result.get().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            ServletOutputStream stream = response.getOutputStream();
            stream.write(bytes);
            stream.flush();
            return;
        }
        // Filter rejection: an empty 200 with no Content-Type, committed here. After the handler FrameworkServlet
        // completes an uncommitted TRACE reply with HttpServlet's message/http echo and an uncommitted OPTIONS reply
        // with an Allow header; committing the empty reply keeps the reply of every method identical.
        response.setContentLength(0);
        response.flushBuffer();
    }
}
