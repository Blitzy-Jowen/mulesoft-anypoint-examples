/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Free-membership discount rule, ported unchanged from FreeMembershipDiscountFilter.java (filtering-a-message),
 * referenced by filtering.xml:15. A missing key or a non-integer months/purchases value throws, as the original did.
 */
@Component
public class FreeMembershipDiscountFilter {

	public boolean accept(Map<String, Object> payloadMap) {
		boolean isPremium = (boolean) payloadMap.get("membership").equals("premium");
		int months = Integer.parseInt(payloadMap.get("months").toString());
		int purchase = Integer.parseInt(payloadMap.get("purchases").toString());
		if (isPremium)
			return (months >= 6 && purchase >= 1000);
		else
			return (months <= 6 && purchase >= 2500
			|| (months > 6 && months < 12 && purchase >= 2000)
			|| (months >= 12 && purchase >= 1500));
	}

}
