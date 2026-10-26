/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.scatter_gather_flow_control.service.ContactMerge;

/**
 * Tests of contact merge {@link com.mulesoft.examples.scatter_gather_flow_control.service.ContactMerge}
 * @author Vladimir Andoga
 *
 */
public class ContactsMergeTest {

	@Test
	public void testMerge() {
		List<Map<String, String>> contactsA = createContactLists("A", 0, 1);
		List<Map<String, String>> contactsB = createContactLists("B", 1, 2);

		ContactMerge contactMerge = new ContactMerge();
		List<Map<String, String>> mergedList = contactMerge.mergeList(contactsA, contactsB);

		System.out.println(mergedList);
		Assertions.assertEquals(createExpectedList(), mergedList, "The merged list obtained is not as expected");

	}

	/**
	 * Creates expected list for assertion.
	 * @return the list of contacts.
	 */
	private List<Map<String, String>> createExpectedList() {
		Map<String, String> contact0 = new HashMap<>();
		contact0.put("IDInA", "0");
		contact0.put("IDInB", "");
		contact0.put("Email", "some.email.0@fakemail.com");
		contact0.put("Name", "SomeName_0");

		Map<String, String> contact1 = new HashMap<>();
		contact1.put("IDInA", "1");
		contact1.put("IDInB", "1");
		contact1.put("Email", "some.email.1@fakemail.com");
		contact1.put("Name", "SomeName_1");

		Map<String, String> contact2 = new HashMap<>();
		contact2.put("IDInA", "");
		contact2.put("IDInB", "2");
		contact2.put("Email", "some.email.2@fakemail.com");
		contact2.put("Name", "SomeName_2");

		List<Map<String, String>> contactList = new ArrayList<>();
		contactList.add(contact0);
		contactList.add(contact1);
		contactList.add(contact2);

		return contactList;

	}

	/**
	 * Creates contacts as a list of maps.
	 * @param orgId the organization ID
	 * @param start of sequence for contact creation 
	 * @param end of sequence for contact creation(end of sequence)
	 * @return the list of maps
	 */
	private List<Map<String, String>> createContactLists(String orgId, int start, int end) {
		List<Map<String, String>> contactList = new ArrayList<>();
		for (int i = start; i <= end; i++) {
			contactList.add(createContact(orgId, i));
		}
		return contactList;
	}

	/**
	 * Creates contact for the specified id and sequence.
	 * @param orgId the organization ID
	 * @param sequence the specified sequence
	 * @return created contact as Map
	 */
	private Map<String, String> createContact(String orgId, int sequence) {
		Map<String, String> contact = new HashMap<>();
		contact.put("Id", String.valueOf(sequence));
		contact.put("Name", "SomeName_" + sequence);
		contact.put("Email", "some.email." + sequence + "@fakemail.com");
		return contact;
	}
}
