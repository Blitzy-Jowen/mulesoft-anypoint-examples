package com.mulesoft.examples.foreach_processing_and_choice_routing.support;

import java.net.BindException;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Spring Boot's test context loader, loading the context again when an attempt fails because a port was in use.
 *
 * <p>The additional listeners of {@code config.ListenerPorts} need concrete ports, which the application reads from
 * its configuration before Undertow binds them, so a test cannot let the system pick them as it picks a random main
 * port. Such a test draws free ports inside its {@code @DynamicPropertySource} method and declares this loader:
 *
 * <pre>
 * &#64;SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
 * &#64;ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
 * class SomeEndpointTest {
 *
 *     &#64;DynamicPropertySource
 *     static void ports(DynamicPropertyRegistry registry) {
 *         int port = TestSocketUtils.findAvailableTcpPort();
 *         registry.add("ports.some-listener", () -&gt; port);
 *     }
 *
 *     &#64;Value("${ports.some-listener}")
 *     int someListenerPort;
 * }
 * </pre>
 *
 * <p>A drawn port is free when it is drawn, not when it is bound: on a shared host another process can take it in
 * between, and the start-up then fails with Spring Boot's {@link PortInUseException} or a {@link BindException}
 * saying the address is in use. This loader then loads the context again, up to {@link #MAX_ATTEMPTS} attempts in
 * all, logging a WARN line for each attempt lost. Spring invokes the {@code @DynamicPropertySource} methods again for
 * every attempt, so each attempt runs on newly drawn ports. Tests therefore read the ports of the loaded context from
 * the context itself ({@code @LocalServerPort}, {@code @Value}, the {@code Environment}), never from values drawn
 * outside it. Any other failure, and the failure of the last attempt, is rethrown unchanged.
 *
 * <p>Every project with additional listeners carries this class unchanged apart from its {@code package} line, so it
 * depends on the JDK, SLF4J, Spring and Spring Boot alone and names no project type.
 */
public class PortInUseRetryingContextLoader extends SpringBootContextLoader {

    /** Load attempts per context, the first included, before a port in use fails the test. */
    static final int MAX_ATTEMPTS = 5;

    private static final Logger LOG = LoggerFactory.getLogger(PortInUseRetryingContextLoader.class);

    /**
     * Loads the context, and loads it again while an attempt fails because a port was in use, up to
     * {@link #MAX_ATTEMPTS} attempts in all.
     *
     * @param mergedConfig the test's merged context configuration
     * @return the context of the first attempt that loads
     * @throws Exception the first failure that is not a port in use, or the failure of the last attempt, unchanged
     */
    @Override
    public ApplicationContext loadContext(MergedContextConfiguration mergedConfig) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return super.loadContext(mergedConfig);
            } catch (Exception e) {
                Exception portInUse = portInUseCause(e);
                if (portInUse == null || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                LOG.warn("Attempt {} of {} to load the context of {} lost a port to another process ({}); loading"
                        + " again with newly drawn ports", attempt, MAX_ATTEMPTS,
                        mergedConfig.getTestClass().getName(), portInUse.getMessage());
            }
        }
    }

    /**
     * The exception in {@code failure}'s cause chain, {@code failure} included, showing that a port was in use:
     * Spring Boot's {@link PortInUseException}, else a {@link BindException} whose message says the address is in
     * use, as Spring Boot recognises one.
     *
     * @param failure a failed load
     * @return that exception, or {@code null} when the chain holds neither, so the load is not repeated
     */
    static Exception portInUseCause(Exception failure) {
        AtomicReference<Exception> cause = new AtomicReference<>();
        PortInUseException.ifCausedBy(failure, PortInUseException.class, cause::set);
        if (cause.get() == null) {
            PortInUseException.ifPortBindingException(failure, cause::set);
        }
        return cause.get();
    }
}
