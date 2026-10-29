/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.TestProfileValues;

/**
 * The client-certificate mode of the fixture replay's HTTPS driver (ST-2): the TLS client that
 * {@code FixtureParityTest.Replay.httpsClient} builds from a {@code request.tls} that
 * {@code FixtureParityTest.Replay.tls} accepted, the client every HTTPS fixture of this project is replayed through.
 *
 * <p>The listener is {@link ClientAuthServer}, a loopback TLS server on a port the system assigns, built from the
 * carried {@code TLS_Context} stores {@code keystore.jks} and {@code trust-store} and requiring a client certificate
 * that the trust store accepts, as the SC-10 listener {@code box-oauth} does. A {@code tls} that names
 * {@code keyStore} must present the key of {@code keystore.jks} and complete the request; a trust-store-only
 * {@code tls} must be refused in the handshake. The store passwords are the demo values of
 * {@code application-test.yml}, read through {@link TestProfileValues}. No fixture is read or written: this proves
 * the driver, not parity.
 */
class FixtureParityTlsDriverTest {

    private static final String WHAT = "scenario: request";
    private static final String TRUST_STORE = "trust-store";
    private static final String KEY_STORE = "keystore.jks";
    private static final String STORE_TYPE = "JKS";
    private static final ObjectMapper JSON = new ObjectMapper();

    static Stream<Arguments> tlsWithKeyStore() {
        return Stream.of(
                arguments("the type of both stores defaulted", tls()
                        .put("keyStore", KEY_STORE)
                        .put("keyStorePassword", TestProfileValues.required("keystore.password"))),
                arguments("the type and the key store type named", tls()
                        .put("type", STORE_TYPE)
                        .put("keyStore", KEY_STORE)
                        .put("keyStoreType", STORE_TYPE)
                        .put("keyStorePassword", TestProfileValues.required("keystore.password"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tlsWithKeyStore")
    void aTlsNamingTheKeyStorePresentsItsClientKeyAndCompletesTheRequest(String description, ObjectNode tls)
            throws Exception {
        try (ClientAuthServer server = new ClientAuthServer()) {
            HttpTestClient.Response reply = client(tls, server.port()).get("/authorization");

            assertThat(reply.statusLine()).as(reply::toString).isEqualTo("HTTP/1.1 200 OK");
            assertThat(reply.bodyAsString()).as(reply::toString).isEqualTo(ClientAuthServer.BODY);
            assertThat(server.nextHandshake()).as("the certificate the client presented")
                    .isEqualTo(clientKeyCertificate());
        }
    }

    static Stream<Arguments> tlsWithoutKeyStore() {
        return Stream.of(
                arguments("the trust store alone", tls()),
                arguments("the trust store with its type", tls().put("type", STORE_TYPE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tlsWithoutKeyStore")
    void aTlsNamingNoKeyStoreIsRefusedInTheHandshake(String description, ObjectNode tls) throws Exception {
        try (ClientAuthServer server = new ClientAuthServer()) {
            HttpTestClient client = client(tls, server.port());

            // Under TLS 1.3 the server's certificate_required alert may surface on the first read rather than in the
            // handshake, and a reset may arrive instead of the alert; ST-1 wraps each of them.
            assertThatThrownBy(() -> client.get("/authorization"))
                    .isInstanceOf(UncheckedIOException.class)
                    .cause()
                    .isInstanceOfAny(SSLException.class, SocketException.class);
            assertThat(server.nextHandshake()).as("the server's handshake outcome")
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }

    // ----- helpers --------------------------------------------------------------------------------------------

    /** A {@code tls} that names the carried trust store and its password, as an HTTPS fixture of this project does. */
    private static ObjectNode tls() {
        return JSON.createObjectNode()
                .put("trustStore", TRUST_STORE)
                .put("password", TestProfileValues.required("truststore.password"));
    }

    /** The replay's client for {@code tls}, after the replay's own check of the request that carries it. */
    private static HttpTestClient client(ObjectNode tls, int port) throws Exception {
        ObjectNode request = JSON.createObjectNode().set("tls", tls);
        JsonNode checked = FixtureParityTest.Replay.tls(request, WHAT);
        return (HttpTestClient) FixtureParityTest.Replay.httpsClient(HttpTestClient.class, port, checked,
                WHAT + ".tls");
    }

    /** The certificate of the one key entry of {@code keystore.jks}, the client key a key-store {@code tls} names. */
    private static Certificate clientKeyCertificate() throws IOException, GeneralSecurityException {
        KeyStore keyStore = load(KEY_STORE, TestProfileValues.required("keystore.password"));
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)) {
                return keyStore.getCertificate(alias);
            }
        }
        throw new IllegalStateException(KEY_STORE + " holds no key entry");
    }

    private static KeyStore load(String resource, String password) throws IOException, GeneralSecurityException {
        try (InputStream in = FixtureParityTlsDriverTest.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("Carried store " + resource + " is not on the test classpath");
            }
            KeyStore store = KeyStore.getInstance(STORE_TYPE);
            store.load(in, password.toCharArray());
            return store;
        }
    }

    /**
     * A loopback TLS server on a system-assigned port that presents the key of {@code keystore.jks} and, like the
     * listener {@code box-oauth}, requires a client certificate that {@code trust-store} accepts. It records each
     * connection's handshake outcome, the certificate the client presented or the server's handshake failure, and
     * answers a completed handshake's request with {@code 200 OK} and the body {@link #BODY}.
     */
    private static final class ClientAuthServer implements AutoCloseable {

        static final String BODY = "ok";

        private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};
        private static final byte[] REPLY = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                + BODY.length() + "\r\n\r\n" + BODY).getBytes(StandardCharsets.US_ASCII);

        private final SSLServerSocket socket;
        private final BlockingQueue<Object> handshakes = new LinkedBlockingQueue<>();
        private final Thread thread;

        ClientAuthServer() throws IOException, GeneralSecurityException {
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(load(KEY_STORE, TestProfileValues.required("keystore.password")),
                    TestProfileValues.required("keystore.keyPassword").toCharArray());
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(load(TRUST_STORE, TestProfileValues.required("truststore.password")));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
            // ST-1 connects to the address "localhost" resolves to first, so the server binds exactly that one.
            socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 50, InetAddress.getByName("localhost"));
            socket.setNeedClientAuth(true);
            thread = new Thread(this::serve, "client-auth-tls-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        /** The next connection's handshake outcome, waiting at most ST-1's own timeout for it. */
        Object nextHandshake() throws InterruptedException {
            Object outcome = handshakes.poll(HttpTestClient.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            assertThat(outcome).as("a handshake reached the server").isNotNull();
            return outcome;
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (SSLSocket connection = (SSLSocket) socket.accept()) {
                    connection.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
                    try {
                        connection.startHandshake();
                    } catch (IOException refused) {
                        handshakes.add(refused);
                        continue;
                    }
                    handshakes.add(connection.getSession().getPeerCertificates()[0]);
                    if (readHead(connection.getInputStream())) {
                        OutputStream out = connection.getOutputStream();
                        out.write(REPLY);
                        out.flush();
                    }
                } catch (IOException e) {
                    // The client closed the connection first, or close() closed the socket; the loop condition
                    // decides whether another connection is accepted.
                }
            }
        }

        /** Reads through the empty line ending the request head; {@code false} when the connection ends first. */
        private static boolean readHead(InputStream in) throws IOException {
            int matched = 0;
            int b;
            while ((b = in.read()) != -1) {
                matched = b == HEAD_END[matched] ? matched + 1 : b == HEAD_END[0] ? 1 : 0;
                if (matched == HEAD_END.length) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                thread.join(HttpTestClient.TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
