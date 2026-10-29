package com.mulesoft.examples.soap_webservice_security.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.soap_webservice_security.config.ListenerPorts;

/**
 * Tests {@link PortInUseRetryingContextLoader}: the context of this class has one additional listener whose port,
 * on the first load attempt, another socket holds, so that attempt fails and the loader loads the context again on a
 * newly drawn port; a port drawn for a later attempt can in turn be lost to another process on a shared host, and is
 * then drawn again. Then the loader's classification of failures and its bound on attempts, without a server.
 *
 * <p>The context is {@link ListenerConfig}: Spring Boot's servlet web server, {@code config.ListenerPorts} and one
 * servlet. It names {@code spring.config.name} after no file, so the project's {@code application.yml}, whose
 * listeners hold the project's own ports, adds nothing, and every listener binds IPv4 loopback, where the held
 * socket is. Every project with additional listeners carries this class unchanged apart from its {@code package}
 * line and the package-relative {@code config.ListenerPorts} import; it reaches ST-1 {@link HttpTestClient} in its
 * own package and names no other project type.
 */
@SpringBootTest(
        classes = PortInUseRetryingContextLoaderTest.ListenerConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                PortInUseRetryingContextLoaderTest.NO_CONFIG_FILE,
                "server.address=" + PortInUseRetryingContextLoaderTest.LOOPBACK,
                "listeners.host=" + PortInUseRetryingContextLoaderTest.LOOPBACK})
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ExtendWith(OutputCaptureExtension.class)
class PortInUseRetryingContextLoaderTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}. */
    static final String LOOPBACK = "127.0.0.1";

    /** A configuration name no file carries, so no {@code application.yml} or {@code application.properties} loads. */
    static final String NO_CONFIG_FILE = "spring.config.name=port-in-use-retrying-context-loader-test";

    /** A port number for the failures built without a socket. */
    private static final int SYNTHETIC_PORT = 18003;

    /** Invocations of {@link #additionalListener}, one per load attempt. */
    private static final AtomicInteger PORT_DRAWS = new AtomicInteger();

    /** The socket holding the port drawn for the first attempt, open until every test of the class has run. */
    private static ServerSocket heldSocket;

    @Value("${listeners.additional[0].port}")
    int extraPort;

    /**
     * Configures the additional listener: on the first invocation on a port a socket of this class holds, so that
     * attempt fails with a port in use, and on every later one on a newly drawn free port. A drawn port is free when
     * it is drawn, not when the listener binds it, so another process can take it in between; that attempt then fails
     * with a port in use too, and the next one draws again.
     */
    @DynamicPropertySource
    static void additionalListener(DynamicPropertyRegistry registry) {
        int port;
        if (PORT_DRAWS.incrementAndGet() == 1) {
            try {
                heldSocket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot hold a loopback port", e);
            }
            port = heldSocket.getLocalPort();
        } else {
            port = TestSocketUtils.findAvailableTcpPort();
        }
        registry.add("listeners.additional[0].name", () -> "extra");
        registry.add("listeners.additional[0].port", () -> port);
    }

    @AfterAll
    static void releaseHeldPort() throws IOException {
        if (heldSocket != null) {
            heldSocket.close();
        }
    }

    /**
     * The first attempt fails on the held port, and the context loads on a newly drawn one while the held socket stays
     * open. A port drawn for a later attempt can in turn be lost to another process and is then drawn again, so the
     * context loads on the second attempt or a later one, within {@link PortInUseRetryingContextLoader#MAX_ATTEMPTS}:
     * every attempt before the one that loaded logged its WARN line, and the one that loaded logged none.
     *
     * <p>The WARN lines are read from the console output captured since the class started, which also holds the lines
     * {@link #failureIsRethrownAtOnceOrAfterTheLastAttempt} logs for {@link FailingConfig}; naming this class
     * followed by {@code " lost"} keeps those apart.
     */
    @Test
    void portHeldOnTheFirstAttemptIsDrawnAgain(CapturedOutput output) {
        int heldPort = heldSocket.getLocalPort();
        String attemptsOfThisClass = " of " + PortInUseRetryingContextLoader.MAX_ATTEMPTS + " to load the context of "
                + PortInUseRetryingContextLoaderTest.class.getName() + " lost a port to another process";
        int draws = PORT_DRAWS.get();
        String log = output.getAll();

        assertThat(draws).as("load attempts").isBetween(2, PortInUseRetryingContextLoader.MAX_ATTEMPTS);
        assertThat(extraPort).as("additional listener port of the loaded context").isNotEqualTo(heldPort);
        assertThat(heldSocket.isClosed()).as("held socket closed").isFalse();
        assertThat(log)
                .contains("Attempt 1" + attemptsOfThisClass + " (Port " + heldPort + " is already in use); loading"
                        + " again with newly drawn ports");
        for (int attempt = 1; attempt < draws; attempt++) {
            assertThat(log).as("WARN line of lost attempt %d", attempt)
                    .contains("Attempt " + attempt + attemptsOfThisClass);
        }
        assertThat(log).as("WARN line of attempt %d, which loaded the context", draws)
                .doesNotContain("Attempt " + draws + attemptsOfThisClass);
    }

    @Test
    void additionalListenerOfTheLoadedContextServesOnItsPort() {
        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, extraPort).get("/probe");

        assertThat(response.status()).as("%s", response).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.bodyAsString()).isEqualTo("ok:" + extraPort);
    }

    static Stream<Arguments> loadFailures() {
        PortInUseException portInUse = new PortInUseException(SYNTHETIC_PORT);
        PortInUseException webServerPortInUse = new PortInUseException(SYNTHETIC_PORT);
        BindException addressInUse = new BindException("Address already in use");
        return Stream.of(
                Arguments.of(Named.of("Spring Boot's PortInUseException", portInUse), portInUse),
                Arguments.of(Named.of("PortInUseException inside the web server start-up failure",
                        new ApplicationContextException("Unable to start web server", webServerPortInUse)),
                        webServerPortInUse),
                Arguments.of(Named.of("BindException 'Address already in use' inside a RuntimeException",
                        new RuntimeException(addressInUse)), addressInUse),
                Arguments.of(Named.of("BindException 'Cannot assign requested address'",
                        new BindException("Cannot assign requested address")), null),
                Arguments.of(Named.of("IllegalStateException", new IllegalStateException("listener misconfigured")),
                        null));
    }

    @ParameterizedTest(name = "{0} -> port in use shown by: {1}")
    @MethodSource("loadFailures")
    void onlyAPortInUseOnTheCauseChainRepeatsTheLoad(Exception failure, Exception expectedCause) {
        assertThat(PortInUseRetryingContextLoader.portInUseCause(failure)).isSameAs(expectedCause);
    }

    static Stream<Arguments> failingLoads() {
        return Stream.of(
                Arguments.of(Named.of("a port in use",
                        (Supplier<Exception>) () -> new PortInUseException(SYNTHETIC_PORT)),
                        PortInUseRetryingContextLoader.MAX_ATTEMPTS),
                Arguments.of(Named.of("any other failure",
                        (Supplier<Exception>) () -> new IllegalStateException("listener misconfigured")), 1));
    }

    /**
     * Loads {@link FailingConfig}, whose only bean throws {@code failure} on every attempt, straight through the
     * loader: a port in use is attempted {@link PortInUseRetryingContextLoader#MAX_ATTEMPTS} times, anything else
     * once, and the failure thrown is the last attempt's, carrying the bean's exception.
     */
    @ParameterizedTest(name = "{0}: {1} attempt(s), then rethrown")
    @MethodSource("failingLoads")
    void failureIsRethrownAtOnceOrAfterTheLastAttempt(Supplier<Exception> failure, int attempts) {
        Exception thrown = failure.get();
        FailingConfig.ATTEMPTS.set(0);
        FailingConfig.failure = thrown;
        PortInUseRetryingContextLoader loader = new PortInUseRetryingContextLoader();
        MergedContextConfiguration failing = new MergedContextConfiguration(FailingConfig.class, new String[0],
                new Class<?>[] {FailingConfig.class}, Set.of(), new String[0], List.of(),
                new String[] {NO_CONFIG_FILE}, Set.of(), loader, null, null);

        assertThatThrownBy(() -> loader.loadContext(failing)).rootCause().isSameAs(thrown);
        assertThat(FailingConfig.ATTEMPTS).as("load attempts").hasValue(attempts);
    }

    /**
     * The whole context of this class's tests: Spring Boot's servlet web server, the additional listeners and
     * {@link PortEchoServlet}. It is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(ListenerPorts.class)
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class})
    static class ListenerConfig {

        @Bean
        ServletRegistrationBean<PortEchoServlet> portEchoServlet() {
            return new ServletRegistrationBean<>(new PortEchoServlet(), "/*");
        }
    }

    /** A context whose only bean throws {@link #failure} on every attempt, counting the attempts. */
    @Configuration(proxyBeanMethods = false)
    static class FailingConfig {

        static final AtomicInteger ATTEMPTS = new AtomicInteger();

        static volatile Exception failure;

        @Bean
        Object failingBean() throws Exception {
            ATTEMPTS.incrementAndGet();
            throw failure;
        }
    }

    /** Answers every request with {@code ok:<local port>}, so a response proves the listener that received it. */
    static final class PortEchoServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/plain");
            response.getWriter().write("ok:" + request.getLocalPort());
        }
    }
}
