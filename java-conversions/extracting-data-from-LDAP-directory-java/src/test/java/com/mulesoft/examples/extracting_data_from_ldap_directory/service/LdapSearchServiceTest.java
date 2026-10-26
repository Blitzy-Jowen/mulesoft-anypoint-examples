/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mulesoft.examples.extracting_data_from_ldap_directory.client.LdapDirectoryClient;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;

/**
 * Unit test of {@link LdapSearchService}, the flow {@code ldapFlow1} of the original {@code ldap.xml}
 * ({@code ldap:search baseDn="ou=people" filter="(objectClass=*)"}, split, aggregate, {@code object-to-string}),
 * with a mocked {@link LdapDirectoryClient}: no Spring context and no directory server.
 *
 * <p>Expected texts are built from the same {@link Entry} objects the mock returns, through
 * {@link Entry#toLDIFString()}, so each assertion checks the aggregation and its {@code List.toString()} form
 * rather than a hand-typed copy of the LDIF writer's output.
 */
@ExtendWith(MockitoExtension.class)
class LdapSearchServiceTest {

    /** Search base the original {@code ldap:search} names. */
    private static final String SEARCH_BASE = "ou=people";

    /** Search filter the original {@code ldap:search} names. */
    private static final String SEARCH_FILTER = "(objectClass=*)";

    @Mock
    private LdapDirectoryClient client;

    private LdapSearchService service;

    @BeforeEach
    void setUp() {
        service = new LdapSearchService(client);
    }

    @Test
    void searchPeopleRendersEveryEntryAsLdifInClientOrder() throws LDAPException {
        Entry mmc = person("mmc");
        Entry testuser1 = person("testuser1");
        Entry admin = person("admin");
        when(client.search(SEARCH_BASE, SEARCH_FILTER)).thenReturn(List.of(mmc, testuser1, admin));

        String result = service.searchPeople();

        assertThat(result).isEqualTo("[" + mmc.toLDIFString() + ", " + testuser1.toLDIFString() + ", "
                + admin.toLDIFString() + "]");
        // The original test's assertions: each person's DN is in the response.
        assertThat(result).contains("dn: cn=mmc,ou=people", "dn: cn=testuser1,ou=people", "dn: cn=admin,ou=people");
        assertThat(result.indexOf("dn: cn=mmc,ou=people"))
                .isLessThan(result.indexOf("dn: cn=testuser1,ou=people"));
        assertThat(result.indexOf("dn: cn=testuser1,ou=people"))
                .isLessThan(result.indexOf("dn: cn=admin,ou=people"));
    }

    @Test
    void searchPeopleRendersASingleEntryInsideBrackets() throws LDAPException {
        Entry mmc = person("mmc");
        when(client.search(SEARCH_BASE, SEARCH_FILTER)).thenReturn(List.of(mmc));

        assertThat(service.searchPeople()).isEqualTo("[" + mmc.toLDIFString() + "]");
    }

    @Test
    void searchPeopleReturnsAnEmptyStringWhenNothingIsFound() throws LDAPException {
        when(client.search(SEARCH_BASE, SEARCH_FILTER)).thenReturn(List.of());

        assertThat(service.searchPeople()).isEqualTo("");
    }

    @Test
    void searchPeopleSearchesThePeopleBaseExactlyOnce() throws LDAPException {
        when(client.search(SEARCH_BASE, SEARCH_FILTER)).thenReturn(List.of(person("mmc")));

        service.searchPeople();

        verify(client, times(1)).search(SEARCH_BASE, SEARCH_FILTER);
        verifyNoMoreInteractions(client);
    }

    @Test
    void searchConstantsAreThoseOfTheOriginalSearch() {
        assertThat(LdapSearchService.BASE_DN).isEqualTo("ou=people");
        assertThat(LdapSearchService.FILTER).isEqualTo("(objectClass=*)");
    }

    @Test
    void searchPeoplePropagatesTheDirectoryFailureUnchanged() throws LDAPException {
        LDAPException failure = new LDAPException(ResultCode.CONNECT_ERROR, "connection refused");
        when(client.search(SEARCH_BASE, SEARCH_FILTER)).thenThrow(failure);

        LDAPException thrown = assertThrows(LDAPException.class, service::searchPeople);

        assertSame(failure, thrown);
    }

    /**
     * A person entry with the object classes and naming attributes of those in the carried {@code ldap-add.ldif},
     * named relative to the connector URL's base DN as the client returns it.
     */
    private static Entry person(String name) {
        return new Entry("cn=" + name + ",ou=people",
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("cn", name),
                new Attribute("sn", name),
                new Attribute("uid", name));
    }
}
