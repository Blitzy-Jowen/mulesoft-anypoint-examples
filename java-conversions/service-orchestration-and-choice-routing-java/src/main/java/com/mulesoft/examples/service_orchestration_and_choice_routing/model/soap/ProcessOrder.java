/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.model.soap;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Document/literal-wrapped request element of the {@code IProcessOrder.processOrder} operation,
 * {@code {http://orders.se.mulesoft.com/}processOrder} with one unqualified {@code order} child.
 * <p>
 * The JAX-WS runtime used to derive this wrapper from the service interface; it is declared here so the
 * wire shape no longer depends on that runtime. The namespace is stated explicitly because it is the one
 * of the original {@code com.mulesoft.se.orders} package, not of this class's package. This package has
 * no {@code package-info.java}: an {@code @XmlSchema} there would qualify or re-prefix the children,
 * while the contract has {@code elementFormDefault="unqualified"}.
 */
@XmlRootElement(name = ProcessOrder.LOCAL_PART, namespace = ProcessOrder.NAMESPACE_URI)
@XmlType(name = ProcessOrder.LOCAL_PART, namespace = ProcessOrder.NAMESPACE_URI, propOrder = {"order"})
@XmlAccessorType(XmlAccessType.FIELD)
public class ProcessOrder {

	/** Target namespace of the {@code IProcessOrder} service. */
	public static final String NAMESPACE_URI = "http://orders.se.mulesoft.com/";

	/** Local name of the request wrapper element. */
	public static final String LOCAL_PART = "processOrder";

	/** The order to process, the operation's {@code order} parameter. */
	@XmlElement(name = "order")
	private Order order;

	public ProcessOrder() {
	}

	public ProcessOrder(Order order) {
		this.order = order;
	}

	public Order getOrder() {
		return order;
	}

	public void setOrder(Order order) {
		this.order = order;
	}
}
