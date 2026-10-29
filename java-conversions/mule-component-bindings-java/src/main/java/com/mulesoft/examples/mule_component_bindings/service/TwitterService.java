/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import java.util.Date;
import java.util.List;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

/**
 * Defines an interface for classes that can search Twitter.
 */
public interface TwitterService {

	List<Tweet> search(String stock, Date date, int pageSize, int page);

}
