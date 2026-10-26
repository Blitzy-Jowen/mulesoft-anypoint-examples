/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.extracting_data_from_ldap_directory.config.LdapProperties;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;

import jakarta.annotation.PreDestroy;

/**
 * The project's only LDAP access path, replacing the {@code ldap:config} connector and its {@code ldap:search}
 * operation of the original {@code ldap.xml}.
 *
 * <p>Connections come from an UnboundID {@link LDAPConnectionPool} built from {@link LdapProperties}: the
 * connector URL supplies host, port and base DN, and the configured DN and password the bind (anonymous when the
 * DN is empty). The pool opens one connection initially and grows past its size instead of blocking, as the
 * original pooling profile ({@code INITIALISE_ONE}, {@code WHEN_EXHAUSTED_GROW}) did.
 *
 * <p>The pool is created on the first search, never at start-up, so the application starts without a reachable
 * directory, as the Mule application did. A failed creation caches nothing: the next search tries again.
 *
 * <p>Directory failures ({@link LDAPException}) propagate unchanged to the caller. Instances are thread-safe.
 */
@Component
public class LdapDirectoryClient {

    private static final int INITIAL_CONNECTIONS = 1;

    private static final int MAX_POOLED_CONNECTIONS = 5;

    private final LdapProperties properties;

    private final Object lock = new Object();

    /** Written after {@link #baseDn}, so a thread that reads a non-null pool also sees the base DN. */
    private volatile LDAPConnectionPool pool;

    private volatile DN baseDn;

    public LdapDirectoryClient(LdapProperties properties) {
        this.properties = properties;
    }

    /**
     * Searches the direct children of a base DN given relative to the connector URL's base DN, as the original
     * {@code ldap:search baseDn="ou=people"} did.
     *
     * <p>The scope is one level (the Mule LDAP connector's default), so neither the search base itself nor any
     * deeper entry is returned. All user attributes are requested. Each entry's DN is returned relative to the
     * URL's base DN ({@code cn=mmc,ou=people}), the name the original connector reported; attributes keep the
     * order and values the server returned.
     *
     * @param relativeBaseDn search base relative to the URL's base DN; null or empty searches the URL's base DN
     * @param filter LDAP search filter, for example {@code (objectClass=*)}
     * @return the matching entries in server order, never {@code null}; unmodifiable
     * @throws LDAPException when the URL is missing or invalid, the directory cannot be reached, the bind fails,
     *         the search base does not exist, or the search fails
     */
    public List<Entry> search(String relativeBaseDn, String filter) throws LDAPException {
        LDAPConnectionPool connections = pool();
        DN urlBaseDn = baseDn;

        SearchResult result = connections.search(searchBase(relativeBaseDn, urlBaseDn), SearchScope.ONE, filter,
                SearchRequest.ALL_USER_ATTRIBUTES);

        List<SearchResultEntry> found = result.getSearchEntries();
        List<Entry> entries = new ArrayList<>(found.size());
        for (SearchResultEntry entry : found) {
            entries.add(new Entry(relativeTo(entry.getParsedDN(), urlBaseDn), entry.getAttributes()));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Closes the pool and its connections. Safe to call before the first search and more than once.
     */
    @PreDestroy
    public void close() {
        synchronized (lock) {
            LDAPConnectionPool current = pool;
            if (current != null) {
                pool = null;
                current.close();
            }
        }
    }

    private LDAPConnectionPool pool() throws LDAPException {
        LDAPConnectionPool current = pool;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            current = pool;
            if (current == null) {
                current = createPool();
            }
            return current;
        }
    }

    /** Builds the pool and publishes it with its base DN only when every step succeeded. */
    private LDAPConnectionPool createPool() throws LDAPException {
        String configuredUrl = properties.getUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            // The SDK rejects a null URL with an unchecked usage error; callers expect directory failures as
            // LDAPException.
            throw new LDAPException(ResultCode.PARAM_ERROR, "ldap.url is not configured");
        }
        LDAPURL url = new LDAPURL(configuredUrl);

        String dn = properties.getDn();
        String password = properties.getPassword();
        SimpleBindRequest bindRequest = dn != null && !dn.isEmpty()
                ? new SimpleBindRequest(dn, password == null ? "" : password)
                : new SimpleBindRequest();

        LDAPConnectionPool created = new LDAPConnectionPool(new SingleServerSet(url.getHost(), url.getPort()),
                bindRequest, INITIAL_CONNECTIONS, MAX_POOLED_CONNECTIONS);
        created.setCreateIfNecessary(true);

        baseDn = url.getBaseDN();
        pool = created;
        return created;
    }

    /**
     * Resolves the search base the way the JNDI-based connector resolved its {@code baseDn} against the URL
     * context: the relative base followed by the URL's base DN.
     */
    private static String searchBase(String relativeBaseDn, DN urlBaseDn) {
        if (urlBaseDn.isNullDN()) {
            return relativeBaseDn == null ? "" : relativeBaseDn;
        }
        if (relativeBaseDn == null || relativeBaseDn.isEmpty()) {
            return urlBaseDn.toString();
        }
        return relativeBaseDn + "," + urlBaseDn;
    }

    /** Strips the URL's base DN from a descendant's DN; any other DN is returned unchanged. */
    private static DN relativeTo(DN dn, DN urlBaseDn) {
        if (urlBaseDn.isNullDN() || !dn.isDescendantOf(urlBaseDn, false)) {
            return dn;
        }
        return new DN(Arrays.copyOf(dn.getRDNs(), dn.getRDNs().length - urlBaseDn.getRDNs().length));
    }
}
