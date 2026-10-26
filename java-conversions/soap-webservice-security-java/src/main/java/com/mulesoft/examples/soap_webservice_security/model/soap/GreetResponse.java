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
 * The document/literal-wrapped response element of the original
 * {@code com.mulesoft.mule.example.security.Greeter.greet(String)} operation, formerly derived by CXF from the
 * {@code @WebService} interface: {@code greetResponse} in the namespace CXF derived from the original security
 * package, holding the greeting as the unqualified child {@code name}, the name the original
 * {@code @WebResult(name="name")} gives the result.
 *
 * <p>The namespace is stated here rather than derived from this class's package, which no longer matches the
 * original one. The child carries no namespace, as {@code elementFormDefault="unqualified"} in
 * {@code wsdl/GreeterService.wsdl} requires; for that reason this package has no {@code package-info.java}, whose
 * {@code @XmlSchema} would qualify it. A null greeting is omitted, matching the optional {@code name} the WSDL
 * declares.
 */
@XmlRootElement(name = "greetResponse", namespace = GreetResponse.NAMESPACE)
@XmlType(name = "greetResponse", namespace = GreetResponse.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class GreetResponse {

    /** Target namespace of the original Greeter service. */
    public static final String NAMESPACE = "http://security.example.mule.mulesoft.com/";

    @XmlElement(name = "name")
    private String name;

    public GreetResponse() {
        super();
    }

    public GreetResponse(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
