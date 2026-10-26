/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.extracting_data_from_ldap_directory.config.LdapProperties;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldif.LDIFReader;

/**
 * Tests {@link LdapDirectoryClient}, the replacement of the {@code ldap:config} connector and its
 * {@code ldap:search baseDn="ou=people" filter="(objectClass=*)"} operation of the original {@code ldap.xml}.
 *
 * <p>The directory is the UnboundID in-memory server loaded with the carried {@code ldap-add.ldif}, set up as the
 * original {@code ExtractingDataFromLdapDirectoryIT} set it up (base DN, additional bind credentials, LDIF import),
 * but listening on a free port instead of a configured one.
 */
class LdapDirectoryClientTest {

    private static final String BASE_DN = "dc=my-domain,dc=com";

    /** Test-only demo bind identity: {@code ldap.admin} and {@code ldap.password} of {@code application-test.yml}. */
    private static final String BIND_DN = "cn=Manager,dc=my-domain,dc=com";

    private static final String BIND_PASSWORD = "root";

    private static final String LISTENER = "LDAP";

    private static final String PEOPLE = "ou=people";

    private static final String ALL = "(objectClass=*)";

    private static final String MMC_DN = "cn=mmc,ou=people";

    private static InMemoryDirectoryServer server;

    private static int port;

    private LdapDirectoryClient client;

    @BeforeAll
    static void startDirectory() throws Exception {
        server = startServer(0);
        port = server.getListenPort(LISTENER);
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Test
    void searchIsOneLevelUnderPeople() throws Exception {
        client = new LdapDirectoryClient(properties(port, BIND_PASSWORD));

        List<Entry> entries = client.search(PEOPLE, ALL);

        // Neither ou=people itself nor any ou=groups entry may appear.
        assertThat(entries.stream().map(Entry::getDN).toList())
                .containsExactlyInAnyOrder("cn=mmc,ou=people", "cn=testuser1,ou=people", "cn=admin,ou=people");
    }

    @Test
    void returnedDnsAreRelativeToUrlBase() throws Exception {
        client = new LdapDirectoryClient(properties(port, BIND_PASSWORD));

        List<Entry> entries = client.search(PEOPLE, ALL);

        assertThat(entries.stream().map(Entry::getDN).toList())
                .isNotEmpty()
                .noneMatch(dn -> dn.toLowerCase(Locale.ROOT).endsWith(BASE_DN));
        // The text the original IT asserted in the flow's output.
        assertThat(entry(entries, MMC_DN).toLDIFString()).startsWith("dn: cn=mmc,ou=people");
    }

    @Test
    void mmcEntryCarriesUserAttributes() throws Exception {
        client = new LdapDirectoryClient(properties(port, BIND_PASSWORD));

        Entry mmc = entry(client.search(PEOPLE, ALL), MMC_DN);

        assertThat(mmc.getAttributeValue("uid")).isEqualTo("mmc");
        assertThat(mmc.getAttributeValue("sn")).isEqualTo("mmc");
        assertThat(mmc.getObjectClassValues()).contains("inetOrgPerson");
        // The LDIF holds the base64 value bW1jMTIz; the server stores it as given (no password encoder).
        assertThat(mmc.getAttributeValueBytes("userPassword")).isEqualTo("mmc123".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void wrongPasswordFailsWithInvalidCredentials() throws Exception {
        client = new LdapDirectoryClient(properties(port, "wrong-password"));

        assertThatThrownBy(() -> client.search(PEOPLE, ALL))
                .isInstanceOfSatisfying(LDAPException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.INVALID_CREDENTIALS));
    }

    @Test
    void lazyPoolRecoversAfterFailedFirstConnection() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        // Nothing listens on freePort yet: construction must not connect, the first search must fail.
        client = assertDoesNotThrow(() -> new LdapDirectoryClient(properties(freePort, BIND_PASSWORD)));
        assertThatThrownBy(() -> client.search(PEOPLE, ALL)).isInstanceOf(LDAPException.class);

        // The same instance succeeds once a directory listens there, so the failed pool creation cached nothing.
        InMemoryDirectoryServer second = startServer(freePort);
        try {
            assertThat(client.search(PEOPLE, ALL)).hasSize(3);
        } finally {
            second.shutDown(true);
        }
    }

    private static InMemoryDirectoryServer startServer(int listenPort) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig(LISTENER, listenPort));
        InMemoryDirectoryServer ds = new InMemoryDirectoryServer(config);
        try (InputStream in = Objects.requireNonNull(
                LdapDirectoryClientTest.class.getResourceAsStream("/ldap-add.ldif"),
                "ldap-add.ldif missing from test classpath");
                LDIFReader reader = new LDIFReader(in)) {
            int imported = ds.importFromLDIF(true, reader);
            assertThat(imported).isEqualTo(10);
        }
        ds.startListening(LISTENER);
        return ds;
    }

    private static LdapProperties properties(int ldapPort, String password) {
        LdapProperties properties = new LdapProperties();
        properties.setUrl("ldap://localhost:" + ldapPort + "/" + BASE_DN);
        properties.setDn(BIND_DN);
        properties.setPassword(password);
        properties.setPort(ldapPort);
        return properties;
    }

    private static Entry entry(List<Entry> entries, String dn) {
        return entries.stream()
                .filter(candidate -> dn.equals(candidate.getDN()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + dn + " in " + entries));
    }
}
