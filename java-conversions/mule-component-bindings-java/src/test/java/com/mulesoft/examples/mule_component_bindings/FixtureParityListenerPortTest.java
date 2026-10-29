/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;

/**
 * The fixture replay (ST-2) on this project's SC-10 listener {@code stock-stats}. Every fixture of this project sets
 * {@code ports.stock-stats} to {@code ${freePort:stock-stats}} in {@code state.properties}, so the replay reserves
 * that port exclusively before the application context starts, while SC-10's {@code config.ListenerPorts} probes the
 * port of each additional listener, with a socket that sets {@code SO_REUSEADDR}, as soon as the context creates its
 * embedded web server. A replay context that reserves the port must therefore start, and its {@code stock-stats}
 * listener must accept connections on that port and serve its route there.
 *
 * <p>{@link ReplayApplication} runs that replay context in a JVM of its own, on this JVM's class path: the
 * application prepared by {@link FixtureParityTest.ReplayContextInitializer}, as {@code FixtureParityTest} prepares
 * it, with {@code ports.stock-stats} resolved through the replay's own placeholder resolution, the main listener on a
 * port the system assigns, and the three upstream base URLs on another {@code ${freePort:<name>}} port, which the
 * replay owns and nothing listens on. The replay's port registry belongs to its JVM and serves one context start, so
 * running the context in this JVM would share the registry with {@code FixtureParityTest}. This is an engine check,
 * not parity evidence: no fixture is read or written.
 */
class FixtureParityListenerPortTest {

    /** The prefix of each line {@link ReplayApplication} reports its outcome with. */
    private static final String RESULT = "fixture-parity-listener-port: ";

    /** The name {@link FixtureParityTest#resolve} gives in its diagnostics, as a fixture's name. */
    private static final String FIXTURE = "fixture-parity-listener-port";

    /** A stock-statistics request the listener answers by itself: validation rejects it before any upstream call. */
    private static final String MISSING_STOCK = "/api/stockStats?date=2012-11-28";

    private static final long CHILD_TIMEOUT_SECONDS = 180;

    private static final int LOG_TAIL_CHARS = 6_000;

    @TempDir
    Path tempDir;

    @Test
    void aReplayContextThatReservesTheStockStatsPortStartsAndServesThatListenerOnIt() throws Exception {
        Path log = tempDir.resolve("replay.log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                "-XX:TieredStopAtLevel=1", "-cp", System.getProperty("java.class.path"),
                ReplayApplication.class.getName()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            fail("the replay JVM did not finish within " + CHILD_TIMEOUT_SECONDS + " s:\n" + tail(log));
        }
        List<String> results = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (line.startsWith(RESULT)) {
                results.add(line.substring(RESULT.length()));
            }
        }

        assertThat(results).as("the outcome the replay JVM reported; its log:\n%s", tail(log)).containsExactly(
                "started",
                "the stock-stats port is the reserved one: true",
                "the stock-stats port is replay-owned: true",
                "HTTP/1.1 400 Bad Request",
                "stock is required");
        assertThat(process.exitValue()).as("the replay JVM's exit status; its log:\n%s", tail(log)).isZero();
    }

    /** The end of the replay JVM's log, where a failed start reports why. */
    private static String tail(Path log) throws IOException {
        String text = Files.readString(log, StandardCharsets.UTF_8);
        return text.length() <= LOG_TAIL_CHARS ? text : "..." + text.substring(text.length() - LOG_TAIL_CHARS);
    }

    /**
     * Starts this application as the fixture replay prepares it, with {@code ports.stock-stats} reserved through
     * {@code ${freePort:stock-stats}}, then sends {@link #MISSING_STOCK} through ST-1 to the {@code stock-stats}
     * listener on that port, prints each step's outcome as a line starting with {@link #RESULT} and exits with
     * status 0; a failure prints its cause chain on one such line and exits with status 1. A JVM of its own runs it,
     * as the replay's port registry serves one context start per JVM.
     */
    public static final class ReplayApplication {

        private static final String PROJECT_PACKAGE = MuleComponentBindingsApplication.class.getPackageName() + ".";

        private ReplayApplication() {
        }

        public static void main(String[] args) {
            int status = 1;
            try {
                String stockStatsPort = FixtureParityTest.resolve("${freePort:stock-stats}", FIXTURE);
                String upstream = FixtureParityTest.resolve("http://localhost:${freePort:upstream}", FIXTURE);
                try (ConfigurableApplicationContext context =
                             new SpringApplicationBuilder(MuleComponentBindingsApplication.class)
                                     .initializers(new FixtureParityTest.ReplayContextInitializer())
                                     .run("--server.port=0",
                                             "--ports.stock-stats=" + stockStatsPort,
                                             "--stockstats.stocklytics-base-url=" + upstream + "/",
                                             "--stockstats.sentiment140-base-url=" + upstream + "/api/",
                                             "--twitter.rest-base-url=" + upstream + "/1.1/")) {
                    report("started");
                    int port = context.getEnvironment().getRequiredProperty("ports.stock-stats", Integer.class);
                    report("the stock-stats port is the reserved one: " + stockStatsPort.equals(String.valueOf(port)));
                    report("the stock-stats port is replay-owned: " + FixtureParityTest.ownedPorts().contains(port));
                    HttpTestClient.Response response = HttpTestClient.localhost(port).get(MISSING_STOCK);
                    report(response.statusLine());
                    report(response.bodyAsString());
                }
                status = 0;
            } catch (Throwable failure) {
                StringBuilder chain = new StringBuilder("failed: ");
                Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
                    chain.append(cause == failure ? "" : " <- ").append(cause);
                    // The project frame that raised it, such as SC-10's port probe, tells which step failed.
                    for (StackTraceElement frame : cause.getStackTrace()) {
                        if (frame.getClassName().startsWith(PROJECT_PACKAGE)) {
                            chain.append(" (at ").append(frame).append(')');
                            break;
                        }
                    }
                }
                report(chain.toString().replaceAll("\\R", " "));
            }
            System.out.flush();
            System.exit(status);
        }

        private static void report(String outcome) {
            System.out.println(RESULT + outcome);
        }
    }
}
