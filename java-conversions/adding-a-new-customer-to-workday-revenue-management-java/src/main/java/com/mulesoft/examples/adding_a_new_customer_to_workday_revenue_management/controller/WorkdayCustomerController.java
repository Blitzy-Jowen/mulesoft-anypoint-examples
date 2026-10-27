/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 *       ignored {@code Accept}.</li>
 * </ul>
 * Everything the flow does after the listener (lines 9-52) belongs to {@link WorkdayCustomerService}; this class
 * only binds the body and returns the service's payload.
 *
 * <p>The flow declares no exception strategy, so nothing is caught here: the three upstream failures reach the
 * upstream advice (502, 429, 503) and every other exception, an unparseable or empty body included, the generic
 * 500. Every method other than {@code POST} gets the generic 405 and every other path the no-listener 404.
 */
@RestController
public class WorkdayCustomerController {

    private static final String PATH = "/";

    private static final String ALLOWED_METHOD = "POST";

    /**
     * Content-Type of the {@code Added customer: ...} payload. The original string payload declares no MIME type,
     * so the value the Mule listener sent is a verification point for captured fixtures.
     */
    private static final MediaType RESPONSE_TYPE = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final WorkdayCustomerService service;

    /**
     * @param service the orchestration of the flow: parse, request mapping, {@code Put_Customer}, descriptor and
     *                response payload
     */
    public WorkdayCustomerController(WorkdayCustomerService service) {
        this.service = service;
    }

    /**
     * Answers {@code POST /} with {@code Added customer: <descriptor>}.
     *
     * @param body the raw request body in any media type; absent when the request carries none
     * @return 200 {@code text/plain;charset=UTF-8} with the service's payload
     * @throws Exception the parser, vendor or upstream failure unchanged, for the exception advices
     */
    @PostMapping(PATH)
    public ResponseEntity<String> addCustomer(@RequestBody(required = false) byte[] body) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body;
        String result = service.addCustomer(bytes);
        return ResponseEntity.ok().contentType(RESPONSE_TYPE).body(result);
    }

    /**
     * Rejects {@code OPTIONS /} with the listener's 405, which the generic handler writes as
     * {@code Method not allowed for endpoint: /}. Without this mapping Spring MVC would answer {@code OPTIONS} itself
     * with 200 and an {@code Allow} header.
     *
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = PATH, method = RequestMethod.OPTIONS)
    public void options() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(ALLOWED_METHOD));
    }
}
