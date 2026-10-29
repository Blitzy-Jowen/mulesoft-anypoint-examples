/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.service;

import org.springframework.stereotype.Service;

/**
 * The greeting behind all six secured and unsecured Greeter endpoints, ported from the original {@code Greeter}
 * contract and its {@code GreeterService} implementation as one class.
 *
 * <p>The SOAP wire shape the original JAX-WS annotations derived belongs to {@code model/soap/Greet},
 * {@code model/soap/GreetResponse} and {@code wsdl/GreeterService.wsdl}; this class knows nothing of it.
 */
@Service
public class GreeterService {

    /**
     * Greets {@code name} exactly as the original did: plain string concatenation, so {@code null} yields
     * {@code "Hello null"} and an empty name yields {@code "Hello "}.
     *
     * @param name the name carried by the {@code greet} request
     * @return {@code "Hello "} followed by the name
     */
    public String greet(String name) {
        return "Hello " + name;
    }
}
