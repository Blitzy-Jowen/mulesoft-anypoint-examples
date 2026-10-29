/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.scatter_gather_flow_control.model.BranchResult;

/**
 * This transformer will take to list as input and create a third one that will be the merge of the previous two. The identity of an element of the list is defined by its email.
 */
@Component
public class ContactMergeAggregationStrategy {

	/**
	 * Merges the contact lists produced by the two scatter-gather routes.
	 *
	 * @param results
	 *            one result per route, in route order (route A first, route B second)
	 * @return the merged contact list; returned as a list rather than the original payload iterator because the CSV
	 *         mapper that consumes it takes a {@code List}
	 * @throws IllegalStateException
	 *             if the number of routes that completed without an exception is not exactly two
	 */
	@SuppressWarnings("unchecked")
	public List<Map<String, String>> aggregate(List<BranchResult> results) {
		List<BranchResult> resultsWithoutException = new ArrayList<BranchResult>();
		for (BranchResult result : results) {
			if (result.isSuccess()) {
				resultsWithoutException.add(result);
			}
		}
		int resultsWithoutExceptionCount = resultsWithoutException.size();
		
		// data should be loaded from both sources (A and B)
		if (resultsWithoutExceptionCount != 2) {
			throw new IllegalStateException("Data from at least one source was not able to be obtained correctly.");
		}
		
		// both routes succeeded and the filter keeps route order, so index 0 is route A and index 1 is route B
		List<Map<String, String>> listA = getContactsList(resultsWithoutException, 0);
		List<Map<String, String>> listB = getContactsList(resultsWithoutException, 1);

		ContactMerge contactMerge = new ContactMerge();
		List<Map<String, String>> mergedContactList = contactMerge.mergeList(listA, listB);

		return mergedContactList;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, String>> getContactsList(List<BranchResult> results, int index) {
		return (List<Map<String, String>>) results.get(index).value();
	}

}
