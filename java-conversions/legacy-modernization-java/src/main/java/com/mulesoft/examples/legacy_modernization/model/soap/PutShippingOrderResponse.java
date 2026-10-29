/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.model.soap;

import com.mulesoft.examples.legacy_modernization.model.ShippingOrderConfirmation;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped response element of the original {@code IFulfillment.putShippingOrder} operation,
 * which CXF's JAX-WS runtime derived from the interface's {@code @WebResult(name="ShippingOrderConfirmation")}:
 * {@code putShippingOrderResponse} with the single child {@code ShippingOrderConfirmation} (capital {@code S}, as
 * the committed reply carries it).
 *
 * <p>The namespace is the one JAX-WS derived from the original {@code org.ordermgmt} package; it is declared here
 * because this class's own package would yield a different one. The child is unqualified
 * ({@code elementFormDefault="unqualified"}), so this package has no {@code package-info.java}, the child carries no
 * namespace, and JAXB binds {@code http://ordermgmt.org/} to the {@code ns2} prefix on the root, as CXF's JAXB
 * databinding did. The nested content is bound by the ported {@link ShippingOrderConfirmation} annotations.
 *
 * <p>The child is optional ({@code minOccurs="0"}): a {@code null} confirmation, which the original
 * {@code FulfillmentImpl} returned when building it failed, marshals as an empty {@code putShippingOrderResponse}.
 */
@XmlRootElement(name = "putShippingOrderResponse", namespace = "http://ordermgmt.org/")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "putShippingOrderResponse", namespace = "http://ordermgmt.org/")
public class PutShippingOrderResponse {

	@XmlElement(name = "ShippingOrderConfirmation")
	private ShippingOrderConfirmation shippingOrderConfirmation;

	public PutShippingOrderResponse() {
		// no-argument constructor required by JAXB
	}

	public PutShippingOrderResponse(ShippingOrderConfirmation shippingOrderConfirmation) {
		this.shippingOrderConfirmation = shippingOrderConfirmation;
	}

	/**
	 * @return the confirmation, or {@code null} when the response carries no {@code ShippingOrderConfirmation} element
	 */
	public ShippingOrderConfirmation getShippingOrderConfirmation() {
		return shippingOrderConfirmation;
	}

	public void setShippingOrderConfirmation(ShippingOrderConfirmation shippingOrderConfirmation) {
		this.shippingOrderConfirmation = shippingOrderConfirmation;
	}
}
