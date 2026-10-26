/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

import java.util.List;


public class BulkClassifyRequest {
	private List<ClassifyRequest> data;

	public List<ClassifyRequest> getData() {
		return data;
	}

	public void setData(List<ClassifyRequest> data) {
		this.data = data;
	}

}
