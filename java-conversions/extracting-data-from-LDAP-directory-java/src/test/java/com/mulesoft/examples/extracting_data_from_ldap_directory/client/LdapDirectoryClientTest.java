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

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

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

    /** How many reserved-port handoffs the recovery test attempts before it fails. */
    private static final int HANDOFF_ATTEMPTS = 5;

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
        LDAPException portTaken = null;
        for (int attempt = 1; attempt <= HANDOFF_ATTEMPTS; attempt++) {
            InMemoryDirectoryServer second;
            try (Socket reserved = reserveRefusingPort()) {
                int reservedPort = reserved.getLocalPort();

                // Nothing listens on the reserved port: construction must not connect, the first search must fail.
                client = assertDoesNotThrow(() -> new LdapDirectoryClient(properties(reservedPort, BIND_PASSWORD)));
                assertThatThrownBy(() -> client.search(PEOPLE, ALL))
                        .isInstanceOfSatisfying(LDAPException.class, e -> {
                            assertThat(e.getResultCode()).isEqualTo(ResultCode.CONNECT_ERROR);
                            assertThat(causeChain(e)).anyMatch(ConnectException.class::isInstance);
                        });

                second = prepareServer(reservedPort);
            }
            // Handoff: the reserved socket was released just above and the prepared directory binds its port next.
            try {
                second.startListening(LISTENER);
            } catch (LDAPException e) {
                second.shutDown(true);
                if (causeChain(e).noneMatch(BindException.class::isInstance)) {
                    throw e;
                }
                // Another process bound the released port first: repeat the scenario on a newly reserved port.
                portTaken = e;
                client.close();
                client = null;
                continue;
            }

            // The same instance succeeds once a directory listens there, so the failed pool creation cached nothing.
            try {
                assertThat(client.search(PEOPLE, ALL)).hasSize(3);
            } finally {
                second.shutDown(true);
            }
            return;
        }
        throw new AssertionError("Another process bound the released port before the directory listened on it, in "
                + "each of " + HANDOFF_ATTEMPTS + " attempts", portTaken);
    }

    private static InMemoryDirectoryServer startServer(int listenPort) throws Exception {
        InMemoryDirectoryServer ds = prepareServer(listenPort);
        ds.startListening(LISTENER);
        return ds;
    }

    /** A directory loaded with {@code ldap-add.ldif} whose listener is configured on {@code listenPort} but idle. */
    private static InMemoryDirectoryServer prepareServer(int listenPort) throws Exception {
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
        return ds;
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    /** The failure followed by each of its causes. */
    private static Stream<Throwable> causeChain(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause);
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
