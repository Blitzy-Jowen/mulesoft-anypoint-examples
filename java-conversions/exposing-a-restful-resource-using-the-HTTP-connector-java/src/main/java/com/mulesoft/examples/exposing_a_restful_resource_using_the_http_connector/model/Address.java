/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;


@JsonAutoDetect
// Pins the declaration order the original Jackson 1.x auto-transformer wrote for the nested "address"
// object of person.json; Jackson 2 does not guarantee field order from reflection.
@JsonPropertyOrder({"streetAddress", "city", "state", "zipCode"})
public class Address {

	private String streetAddress;
	private String city;
	private String state;
	private String zipCode;
	
	public String getStreetAddress() {
		return streetAddress;
	}
	public void setStreetAddress(String streetAddress) {
		this.streetAddress = streetAddress;
	}
	public String getCity() {
		return city;
	}
	public void setCity(String city) {
		this.city = city;
	}
	public String getState() {
		return state;
	}
	public void setState(String state) {
		this.state = state;
	}
	public String getZipCode() {
		return zipCode;
	}
	public void setZipCode(String zipCode) {
		this.zipCode = zipCode;
	}
	
}
