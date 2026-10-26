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
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
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
 * connector URL ({@code ldap://} only) supplies host, port and base DN, and the configured DN and password, both
 * required, the simple bind. Connecting, the bind and each search response are bounded by the Mule default
 * response timeout of 10 s. The pool opens one connection initially and grows past its size instead of blocking,
 * as the original pooling profile ({@code INITIALISE_ONE}, {@code WHEN_EXHAUSTED_GROW}) did.
 *
 * <p>The pool is created on the first search, never at start-up, so the application starts without a reachable
 * directory, as the Mule application did; missing configuration likewise fails the first search, not the start-up.
 * A failed creation caches nothing: the next search tries again.
 *
 * <p>Directory failures ({@link LDAPException}) propagate unchanged to the caller; a failed or timed-out
 * operation is never retried. Instances are thread-safe.
 */
@Component
public class LdapDirectoryClient {

    private static final int INITIAL_CONNECTIONS = 1;

    private static final int MAX_POOLED_CONNECTIONS = 5;

    /** The Mule default response timeout, bounding the connect, the bind and each search response. */
    private static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    /** The only {@code ldap.url} scheme served: the server set opens plain LDAP connections. */
    private static final String SUPPORTED_SCHEME = "ldap";

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
     * @throws LDAPException when {@code ldap.url} is missing, invalid or not an {@code ldap://} URL, when
     *         {@code ldap.dn} or {@code ldap.password} is not configured (result code {@code PARAM_ERROR}), when the
     *         directory cannot be reached, the bind fails, the search base does not exist or the search fails, or
     *         when connecting, the bind or the search response takes longer than 10 s (an unanswered bind or
     *         search has result code {@code TIMEOUT})
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
        if (!SUPPORTED_SCHEME.equals(url.getScheme())) {
            // The server set below opens plain LDAP sockets, so a secure-looking ldaps:// (or an ldapi://) URL
            // is refused instead of being silently served over plaintext.
            throw new LDAPException(ResultCode.PARAM_ERROR,
                    "ldap.url scheme '" + url.getScheme() + "' is not supported; use ldap://");
        }
        if (!url.hostProvided()) {
            // The server set requires a host; the SDK would otherwise raise an unchecked usage error.
            throw new LDAPException(ResultCode.PARAM_ERROR, "ldap.url has no host");
        }

        // The bind identity is the configured service account and nothing else: an unset DN or password is a
        // configuration error, never a reason to fall back to an anonymous bind with anonymous read access.
        String dn = properties.getDn();
        if (dn == null || dn.isBlank()) {
            throw new LDAPException(ResultCode.PARAM_ERROR, "ldap.dn is not configured");
        }
        String password = properties.getPassword();
        if (password == null || password.isBlank()) {
            throw new LDAPException(ResultCode.PARAM_ERROR, "ldap.password is not configured");
        }
        SimpleBindRequest bindRequest = new SimpleBindRequest(dn, password);

        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(DEFAULT_TIMEOUT_MILLIS);
        options.setResponseTimeoutMillis(DEFAULT_TIMEOUT_MILLIS);

        LDAPConnectionPool created = new LDAPConnectionPool(
                new SingleServerSet(url.getHost(), url.getPort(), options),
                bindRequest, INITIAL_CONNECTIONS, MAX_POOLED_CONNECTIONS);
        created.setCreateIfNecessary(true);
        // A timed-out or broken operation fails to the caller; the pool replaces the connection but never reissues
        // the operation on another one.
        created.setRetryFailedOperationsDueToInvalidConnections(false);

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
