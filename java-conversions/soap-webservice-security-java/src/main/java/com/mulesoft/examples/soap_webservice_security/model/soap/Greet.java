/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.model.soap;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped request element of the original
 * {@code com.mulesoft.mule.example.security.Greeter.greet(String)} operation, whose wire shape CXF derived at
 * runtime from the JAX-WS interface.
 *
 * <p>The namespace is the one JAX-WS derived from the original package {@code com.mulesoft.mule.example.security}
 * and is stated explicitly, never derived from this class's package, so the contract published in
 * {@code wsdl/GreeterService.wsdl} survives the move. The single child keeps the original
 * {@code @WebParam(name = "name")} and carries no namespace, matching {@code elementFormDefault="unqualified"};
 * a null name is omitted rather than written as {@code xsi:nil}. No {@code package-info.java} may set a package
 * namespace here, because it would qualify that child.
 */
@XmlRootElement(name = "greet", namespace = Greet.NAMESPACE)
@XmlType(name = "greet", namespace = Greet.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class Greet {

    /**
     * Target namespace of the Greeter service.
     */
    public static final String NAMESPACE = "http://security.example.mule.mulesoft.com/";

    @XmlElement(name = "name")
    private String name;

    public Greet() {
        super();
    }

    public Greet(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
