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
 * Document/literal-wrapped response element of the {@code IProcessOrder.processOrder} operation,
 * {@code {http://orders.se.mulesoft.com/}processOrderResponse} with one unqualified {@code summary} child
 * holding the processed order.
 * <p>
 * The JAX-WS runtime used to derive this wrapper from {@code @WebResult(name = "summary")} on the service
 * interface; it is declared here so the wire shape no longer depends on that runtime. As for
 * {@link ProcessOrder}, the namespace is stated explicitly and this package has no {@code package-info.java},
 * so {@code summary} and everything below it stay unqualified.
 * <p>
 * The JAXB RI declares every namespace URI its {@code JAXBContext} knows on the root element. A context over
 * {@code model/soap} and {@code model/orders} knows only {@code ""} and the orders namespace, and marshals this
 * element as {@code <ns2:processOrderResponse xmlns:ns2="http://orders.se.mulesoft.com/">}, the bytes of the
 * original reply. A context that also binds {@code model/samsung} adds an {@code xmlns:ns3} declaration or
 * renumbers the prefixes, so the marshaller of the orders endpoint must stay limited to those two packages.
 */
@XmlRootElement(name = ProcessOrderResponse.LOCAL_PART, namespace = ProcessOrder.NAMESPACE_URI)
@XmlType(name = ProcessOrderResponse.LOCAL_PART, namespace = ProcessOrder.NAMESPACE_URI, propOrder = {"summary"})
@XmlAccessorType(XmlAccessType.FIELD)
public class ProcessOrderResponse {

	/** Local name of the response wrapper element. */
	public static final String LOCAL_PART = "processOrderResponse";

	/** The processed order, the operation's {@code summary} result. */
	@XmlElement(name = "summary")
	private Order summary;

	public ProcessOrderResponse() {
	}

	public ProcessOrderResponse(Order summary) {
		this.summary = summary;
	}

	public Order getSummary() {
		return summary;
	}

	public void setSummary(Order summary) {
		this.summary = summary;
	}
}
