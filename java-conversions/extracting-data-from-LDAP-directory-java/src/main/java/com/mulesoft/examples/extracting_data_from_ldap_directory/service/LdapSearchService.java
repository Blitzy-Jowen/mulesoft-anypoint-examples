/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.extracting_data_from_ldap_directory.client.LdapDirectoryClient;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * The flow {@code ldapFlow1} of the original {@code ldap.xml}: search {@code ou=people}, log each user, log the
 * aggregated list and return it as text.
 *
 * <p>The original's processors run here as statements in the same order: {@code ldap:search} and
 * {@code byte-array-to-object-transformer} are the client call, {@code collection-splitter} with its
 * {@code LDAP user: } logger is the loop, {@code collection-aggregator} is the list collected in arrival order,
 * followed by the {@code all users: } logger and {@code object-to-string-transformer}.
 *
 * <p>The flow declares no exception strategy, so directory failures are not handled here. Stateless and
 * thread-safe.
 */
@Service
public class LdapSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(LdapSearchService.class);

    /** Search base of the original {@code ldap:search}, relative to the connector URL's base DN. */
    public static final String BASE_DN = "ou=people";

    /** Search filter of the original {@code ldap:search}. */
    public static final String FILTER = "(objectClass=*)";

    private final LdapDirectoryClient client;

    public LdapSearchService(LdapDirectoryClient client) {
        this.client = client;
    }

    /**
     * Searches the people of the directory and renders them as the original flow's response.
     *
     * @return the entries' LDIF texts in {@code java.util.List#toString()} form ({@code [<ldif>, <ldif>]}), each
     *         exactly as {@link Entry#toLDIFString()} writes it; an empty string when the search finds nothing
     * @throws LDAPException unchanged from the directory client when the search fails
     */
    public String searchPeople() throws LDAPException {
        List<Entry> entries = client.search(BASE_DN, FILTER);

        // An empty search leaves the original splitter nothing to split, so neither INFO logger fires. The body
        // Mule returned in that case is unrecorded; the empty string stands until a captured fixture shows it.
        if (entries == null || entries.isEmpty()) {
            LOG.warn("No LDAP entries found under {}", BASE_DN);
            return "";
        }

        List<String> texts = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            String text = entry.toLDIFString();
            LOG.info("LDAP user: {}", text);
            texts.add(text);
        }

        // List.toString() of the aggregated LDIF texts: what the "all users" logger printed and
        // object-to-string-transformer returned.
        String rendering = "[" + String.join(", ", texts) + "]";
        LOG.info("all users: {}", rendering);
        return rendering;
    }
}
