/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.model;

import java.util.List;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class Order {

	@XmlElement(name="orderItem")
	public List<OrderItem> orderItemList;
	
	public Order(){
		//default no-argument constructor
	}
	
	public Order(List<OrderItem> orderItemList){
		setOrderItemList(orderItemList);
	}

	@XmlTransient	
	public List<OrderItem> getOrderItemList(){
		return this.orderItemList;
	}
	
	public void setOrderItemList(List<OrderItem> orderItemList){
		this.orderItemList = orderItemList;
	}
	
}
