/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service.WorkdayCustomerService;

/**
 * HTTP trigger of flow {@code add-customer-flow}
 * ({@code adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml}).
 *
 * <p>Source mapping:
 * <ul>
 *   <li>line 3, {@code http:listener-config host="0.0.0.0" port="${http.port}"}: {@code server.address} and
 *       {@code server.port} in {@code application.yml};</li>
 *   <li>line 8, {@code http:listener path="/" allowedMethods="POST"}: {@link #addCustomer}, with no
 *       {@code consumes} or {@code produces} condition, because the listener accepted any {@code Content-Type} and
 *       ignored {@code Accept}. The listener compared {@code allowedMethods} ignoring case, so
 *       {@code config.ListenerMethodFilter} hands {@code post}, {@code Post} or any other letter case of
 *       {@code POST} on this path to the mapping as {@code POST}.</li>
 * </ul>
 * Everything the flow does after the listener (lines 9-52) belongs to {@link WorkdayCustomerService}; this class
 * only binds the body and writes the service's payload.
 *
 * <p>The payload goes out as 200 with its {@code Content-Length} and no {@code Content-Type}, written to the servlet
 * response directly so that no message converter adds one: the flow's last processor, the template
 * {@code set-payload value="Added customer: #[payload]"} (line 52), declares no MIME type, and the Mule listener
 * sent no {@code Content-Type} for such a payload.
 *
 * <p>The flow declares no exception strategy, so nothing is caught here: the three upstream failures reach the
 * upstream advice (502, 429, 503) and every other exception, an unparseable or empty body included, the generic
 * 500. Every method other than {@code POST}, in any letter case, gets the generic 405 and every other path the
 * no-listener 404.
 */
@RestController
public class WorkdayCustomerController {

    private static final String PATH = "/";

    private static final String ALLOWED_METHOD = "POST";

    private final WorkdayCustomerService service;

    /**
     * @param service the orchestration of the flow: parse, request mapping, {@code Put_Customer}, descriptor and
     *                response payload
     */
    public WorkdayCustomerController(WorkdayCustomerService service) {
        this.service = service;
    }

    /**
     * Answers {@code POST /} with {@code Added customer: <descriptor>}: 200, the payload's UTF-8 bytes and their
     * {@code Content-Length}, and no {@code Content-Type}.
     *
     * <p>The payload is obtained before the response is touched, so a failure of the flow still finds an uncommitted
     * response for the upstream advice or the generic 500.
     *
     * @param body     the raw request body in any media type; absent when the request carries none
     * @param response the response the payload is written to
     * @throws Exception the parser, vendor or upstream failure unchanged, for the exception advices, or the
     *                   {@code IOException} of writing the response
     */
    @PostMapping(PATH)
    public void addCustomer(@RequestBody(required = false) byte[] body, HttpServletResponse response)
            throws Exception {
        byte[] bytes = body == null ? new byte[0] : body;
        byte[] payload = service.addCustomer(bytes).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(payload.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(payload);
        stream.flush();
    }

    /**
     * Rejects {@code OPTIONS /} with the listener's 405, which the generic handler writes as
     * {@code Method not allowed for endpoint: /}. Without this mapping Spring MVC would answer {@code OPTIONS} itself
     * with 200 and an {@code Allow} header. {@code config.PreflightDispatchConfig} dispatches a CORS pre-flight here
     * as the plain {@code OPTIONS} request it is, so it gets the same 405 and no {@code Access-Control-*} header, as
     * the original listener, which applied no CORS policy, answered it.
     *
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = PATH, method = RequestMethod.OPTIONS)
    public void options() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(ALLOWED_METHOD));
    }
}
