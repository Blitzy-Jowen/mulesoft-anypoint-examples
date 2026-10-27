/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.model.soap;

import com.mulesoft.examples.legacy_modernization.model.Address;
import com.mulesoft.examples.legacy_modernization.model.Order;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped request element of the original
 * {@code IFulfillment.putShippingOrder(shippingId, billingAddress, shippingAddress, order)} operation, which CXF's
 * JAX-WS runtime derived from the interface's {@code @WebParam} names: {@code putShippingOrder} with the children
 * {@code shippingId}, {@code billingAddress}, {@code shippingAddress} and {@code order}, in that order.
 *
 * <p>The namespace is the one JAX-WS derived from the original {@code org.ordermgmt} package; it is declared here
 * because this class's own package would yield a different one. The children are unqualified
 * ({@code elementFormDefault="unqualified"}), so this package has no {@code package-info.java} and no child carries
 * a namespace. The nested content is bound by the ported {@link Address} and {@link Order} annotations.
 */
@XmlRootElement(name = "putShippingOrder", namespace = "http://ordermgmt.org/")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "putShippingOrder", namespace = "http://ordermgmt.org/", propOrder = {"shippingId", "billingAddress",
		"shippingAddress", "order"})
public class PutShippingOrder {

	@XmlElement(name = "shippingId")
	private String shippingId;

	@XmlElement(name = "billingAddress")
	private Address billingAddress;

	@XmlElement(name = "shippingAddress")
	private Address shippingAddress;

	@XmlElement(name = "order")
	private Order order;

	public PutShippingOrder() {
		// no-argument constructor required by JAXB
	}

	public PutShippingOrder(String shippingId, Address billingAddress, Address shippingAddress, Order order) {
		this.shippingId = shippingId;
		this.billingAddress = billingAddress;
		this.shippingAddress = shippingAddress;
		this.order = order;
	}

	/**
	 * @return the shipping ID, or {@code null} when the request carries no {@code shippingId} element
	 */
	public String getShippingId() {
		return shippingId;
	}

	public void setShippingId(String shippingId) {
		this.shippingId = shippingId;
	}

	/**
	 * @return the billing address, or {@code null} when the request carries no {@code billingAddress} element
	 */
	public Address getBillingAddress() {
		return billingAddress;
	}

	public void setBillingAddress(Address billingAddress) {
		this.billingAddress = billingAddress;
	}

	/**
	 * @return the shipping address, or {@code null} when the request carries no {@code shippingAddress} element
	 */
	public Address getShippingAddress() {
		return shippingAddress;
	}

	public void setShippingAddress(Address shippingAddress) {
		this.shippingAddress = shippingAddress;
	}

	/**
	 * @return the order, or {@code null} when the request carries no {@code order} element
	 */
	public Order getOrder() {
		return order;
	}

	public void setOrder(Order order) {
		this.order = order;
	}
}
