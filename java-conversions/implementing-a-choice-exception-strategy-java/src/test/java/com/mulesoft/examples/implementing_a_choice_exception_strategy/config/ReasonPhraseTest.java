/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wire-level test of {@link ReasonPhrase} (shared component SC-2): the phrase must appear verbatim on the raw
 * HTTP/1.1 status line Undertow writes, and must not outlive the exchange it was set on.
 *
 * <p>Every project that carries {@code ReasonPhrase} carries this test with only the {@code package} line
 * changed, so it depends on {@code ReasonPhrase.set(String)}, Spring Boot, JUnit 5, AssertJ and the JDK alone.
 * The context holds the probe controller and auto-configuration only: no project advice can reshape the probe
 * responses. {@link ProbeConfig} is deliberately not a {@code @SpringBootConfiguration}, so the project's other
 * {@code @SpringBootTest} classes still find exactly one when they search for the application class.
 *
 * <p>The status line is read from a plain socket because the JDK and Spring HTTP clients do not expose the reason
 * phrase as sent.
 */
@SpringBootTest(
        classes = ReasonPhraseTest.ProbeConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to IPv4 loopback whatever server.address the project's application.yml sets.
        properties = "server.address=127.0.0.1")
class ReasonPhraseTest {

    private static final String INVALID_INPUT_DATA = "Invalid input data";
    private static final String MISSING_INPUT_DATA = "Missing input data";

    private static final int TIMEOUT_MILLIS = 10_000;

    @LocalServerPort
    int port;

    @Test
    @DisplayName("custom phrase on 400 -> 'HTTP/1.1 400 Invalid input data' and 'HTTP/1.1 400 Missing input data'")
    void customReasonPhraseIsSentOnStatusLine() throws IOException {
        assertThat(statusLine(withPhrase("/reason-phrase-probe/status/400", INVALID_INPUT_DATA)))
                .isEqualTo("HTTP/1.1 400 Invalid input data");
        assertThat(statusLine(withPhrase("/reason-phrase-probe/status/400", MISSING_INPUT_DATA)))
                .isEqualTo("HTTP/1.1 400 Missing input data");
    }

    @Test
    @DisplayName("phrase is per exchange: 'HTTP/1.1 400 Invalid input data' then 'HTTP/1.1 400 Bad Request', five times")
    void reasonPhraseDoesNotLeakIntoNextExchange() throws IOException {
        for (int round = 1; round <= 5; round++) {
            assertThat(statusLine(withPhrase("/reason-phrase-probe/status/400", INVALID_INPUT_DATA)))
                    .as("custom phrase, round %d", round)
                    .isEqualTo("HTTP/1.1 400 Invalid input data");
            assertThat(statusLine("/reason-phrase-probe/status/400"))
                    .as("standard phrase after a custom one, round %d", round)
                    .isEqualTo("HTTP/1.1 400 Bad Request");
        }
    }

    @Test
    @DisplayName("custom phrase on 200 with a body -> 'HTTP/1.1 200 Custom Phrase'; without one -> 'HTTP/1.1 200 OK'")
    void customReasonPhraseWithSuccessStatusAndBody() throws IOException {
        assertThat(statusLine(withPhrase("/reason-phrase-probe/body/200", "Custom Phrase")))
                .isEqualTo("HTTP/1.1 200 Custom Phrase");
        assertThat(statusLine("/reason-phrase-probe/status/200"))
                .isEqualTo("HTTP/1.1 200 OK");
    }

    @Test
    @DisplayName("set(phrase) then set(null) -> 'HTTP/1.1 400 Bad Request'")
    void nullRestoresStandardReasonPhrase() throws IOException {
        assertThat(statusLine(withPhrase("/reason-phrase-probe/reset/400", "Temp")))
                .isEqualTo("HTTP/1.1 400 Bad Request");
    }

    @Test
    @DisplayName("set outside an Undertow request writes no status line and does not throw")
    void setOutsideRequestIsNoOp() {
        assertDoesNotThrow(() -> ReasonPhrase.set("x"));
        assertDoesNotThrow(() -> ReasonPhrase.set(null));
    }

    private static String withPhrase(String path, String phrase) {
        return path + "?phrase=" + URLEncoder.encode(phrase, StandardCharsets.UTF_8);
    }

    /**
     * Sends {@code GET pathAndQuery} on a fresh connection and returns the response's status line without its
     * line terminator, for example {@code HTTP/1.1 400 Invalid input data}.
     */
    private String statusLine(String pathAndQuery) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);

            OutputStream out = socket.getOutputStream();
            out.write(("GET " + pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                line.write(b);
            }
            if (b == -1) {
                throw new EOFException("Connection closed before a complete status line was received for GET "
                        + pathAndQuery + "; bytes read: '" + line.toString(StandardCharsets.ISO_8859_1) + "'");
            }
            // Drain the rest so the server finishes the exchange before the next request opens a connection.
            in.transferTo(OutputStream.nullOutputStream());

            String statusLine = line.toString(StandardCharsets.ISO_8859_1);
            return statusLine.endsWith("\r") ? statusLine.substring(0, statusLine.length() - 1) : statusLine;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class ProbeConfig {
    }

    /** Sets the requested phrase the way SC-2 consumers do, then returns the status through a ResponseEntity. */
    @RestController
    @RequestMapping("/reason-phrase-probe")
    static class ProbeController {

        @GetMapping("/status/{status}")
        ResponseEntity<Void> status(@PathVariable("status") int status,
                                    @RequestParam(name = "phrase", required = false) String phrase) {
            if (phrase != null) {
                ReasonPhrase.set(phrase);
            }
            return ResponseEntity.status(status).build();
        }

        @GetMapping("/body/{status}")
        ResponseEntity<String> body(@PathVariable("status") int status,
                                    @RequestParam(name = "phrase", required = false) String phrase) {
            if (phrase != null) {
                ReasonPhrase.set(phrase);
            }
            return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body("probe");
        }

        @GetMapping("/reset/{status}")
        ResponseEntity<Void> reset(@PathVariable("status") int status,
                                   @RequestParam(name = "phrase", defaultValue = "Temp") String phrase) {
            ReasonPhrase.set(phrase);
            ReasonPhrase.set(null);
            return ResponseEntity.status(status).build();
        }
    }
}
