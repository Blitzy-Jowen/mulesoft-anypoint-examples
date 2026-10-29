/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.extracting_data_from_ldap_directory.support.HttpTestClient;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;

/**
 * Port of the original example's {@code ExtractingDataFromLdapDirectoryIT} functional test, keeping its directory
 * set-up, its request and its assertions.
 *
 * <p>The directory is the UnboundID in-memory server the original test embedded, created with the base DN and the
 * additional bind credentials of {@code application-test.yml} (the values the original read from its uncommitted
 * test properties file) and loaded from the carried {@code ldap-add.ldif}. It listens on a free port, which
 * reaches the application as {@code ldap.port}; {@code ldap.url}, {@code ldap.dn} and {@code ldap.password} then
 * resolve from {@code application.yml} and profile {@code test}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExtractingDataFromLdapDirectoryTest {

    private static final String LISTENER = "LDAP";

    private static final String LDIF = "/ldap-add.ldif";

    private static final String TEST_PROPERTIES = "application-test.yml";

    private static InMemoryDirectoryServer server;

    @LocalServerPort
    private int port;

    /**
     * Starts the directory and publishes its port, replacing the original {@code @BeforeClass} property set-up and
     * {@code @Before} server start. Spring calls this while it builds the context, so the directory is listening
     * before the application's lazily created connection pool first connects.
     */
    @DynamicPropertySource
    static void ldap(DynamicPropertyRegistry registry) throws Exception {
        if (server == null) {
            server = startDirectory();
        }
        // Captured by value, so the supplier never reads the static field after stopDirectory() clears it.
        final int ldapPort = server.getListenPort(LISTENER);
        registry.add("ldap.port", () -> ldapPort);
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.shutDown(true);
            server = null;
        }
    }

    @Test
    void connectAndExtract() throws Exception {
        HttpTestClient.Response result = HttpTestClient.localhost(port).send("GET", "/", Map.of(), new byte[0]);
        assertNotNull(result);
        assertEquals(200, result.status(), result::toString);
        String payload = result.bodyAsString();
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(payload.isEmpty(), result::toString);
        assertTrue(payload.contains("dn: cn=mmc,ou=people"), () -> payload);
        assertTrue(payload.contains("dn: cn=testuser1,ou=people"), () -> payload);
        assertTrue(payload.contains("dn: cn=admin,ou=people"), () -> payload);
    }

    /**
     * Builds, loads and starts the in-memory directory as the original {@code setUp()} did, on an ephemeral port.
     * The instance is returned only once it listens, so a failed start leaves nothing half-initialised behind.
     */
    private static InMemoryDirectoryServer startDirectory() throws Exception {
        Properties testProperties = loadTestProperties();
        String admin = required(testProperties, "ldap.admin");
        String password = required(testProperties, "ldap.password");
        String root = required(testProperties, "ldap.root");

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(root);
        config.addAdditionalBindCredentials(admin, password);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig(LISTENER, 0));

        InMemoryDirectoryServer directory = new InMemoryDirectoryServer(config);
        try (InputStream in = ExtractingDataFromLdapDirectoryTest.class.getResourceAsStream(LDIF)) {
            if (in == null) {
                throw new IllegalStateException("Test resource " + LDIF + " is not on the classpath");
            }
            directory.importFromLDIF(true, new LDIFReader(in));
        }
        directory.startListening(LISTENER);
        return directory;
    }

    private static Properties loadTestProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(TEST_PROPERTIES));
        Properties properties = yaml.getObject();
        if (properties == null) {
            throw new IllegalStateException("Test resource " + TEST_PROPERTIES + " could not be read");
        }
        return properties;
    }

    /**
     * Reads a raw value of {@code application-test.yml}. {@code ldap.dn} is deliberately never read here: its raw
     * value is the unresolved {@code ${ldap.admin}} placeholder, which only the Spring environment resolves.
     */
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not set in " + TEST_PROPERTIES);
        }
        return value;
    }
}
