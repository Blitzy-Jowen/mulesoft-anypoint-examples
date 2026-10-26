/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders;

import java.io.ByteArrayOutputStream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

/**
 * Converts an item to XML.
 * 
 * @author Derek
 */
public class ItemAsXml {

	/**
	 * Returns the item marshalled as formatted XML.
	 * 
	 * @param item
	 *            the order item to marshal
	 * @return the item's XML document
	 */
	public String transform(OrderItem item) {
		try {
			JAXBContext context = JAXBContext.newInstance(OrderItem.class);
			Marshaller marshaller = context.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT,
					Boolean.TRUE);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(item, output);
			return new String(output.toByteArray());
		} catch (JAXBException e) {
			throw new IllegalStateException("Unable to marshal OrderItem.", e);
		}
	}
}
