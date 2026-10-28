package com.mulesoft.examples.hello_world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

import com.mulesoft.examples.hello_world.FixtureParityTest.PortRegistry;
import com.mulesoft.examples.hello_world.FixtureParityTest.PortRegistry.Hold;
import com.mulesoft.examples.hello_world.FixtureParityTest.PortRegistry.Phase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * The fixture replay's port registry (ST-2): {@link PortRegistry} gives each name of {@code ${freePort:<name>}}, and
 * each embedded server's published port, one port that no other name shares. It holds each port it allocates
 * exclusively until the application context starts its listeners, shares it with those listeners from the handover
 * until the context has refreshed, then holds it exclusively again where nothing listens and leaves it to the
 * listener that bound it otherwise. Each test builds its own registry, so the registry {@link FixtureParityTest} keeps
 * for its own run is never touched.
 *
 * <p>The scripted cases hand out port numbers that are never bound or connected to, so the host chooses every port
 * a socket in this class uses. The competing-process cases bind in a JVM of their own, as another program would.
 */
class FixtureParityPortTest {

    private static final int FIRST = 40001;
    private static final int SECOND = 40002;
    private static final int THIRD = 40003;
    private static final int PUBLISHED = 40020;
    private static final int ALLOCATED = 40030;
    /** A scripted request answered with an {@link IOException}, as a host that cannot bind a socket answers. */
    private static final int REFUSED = Integer.MIN_VALUE;
    private static final int NAMES = 25;
    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;
    private static final long CHILD_TIMEOUT_SECONDS = 60;

    @Test
    void aNameKeepsItsPortAndAnotherNameGetsAnother() {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request);
        PortRegistry registry = new PortRegistry(source, true);

        assertEquals(FIRST, registry.port("orders"));
        assertEquals(FIRST, registry.port("orders"), "every use of a name reads the same port");
        assertEquals(1, source.opened(), "the second use opens no reservation");
        assertEquals(SECOND, registry.port("ajax"));
        assertEquals(Set.of(FIRST, SECOND), registry.ports());
        assertEquals(List.of(FIRST + " exclusive", SECOND + " exclusive"), source.opens(),
                "every allocation opens an exclusive reservation");
        assertEquals(Map.of("ajax", Hold.EXCLUSIVE, "orders", Hold.EXCLUSIVE), registry.holds());
        assertEquals(Phase.RESERVING, registry.phase());
    }

    @ParameterizedTest(name = "allocated in phase {0}")
    @EnumSource(value = Phase.class, names = {"RESERVING", "SECURED"})
    void aNumberAnotherNameHoldsStaysReservedWhileTheRegistryAsksAgainAndIsReleasedAfterwards(Phase phase) {
        ScriptedSource source = new ScriptedSource(request -> request < 3 ? FIRST : SECOND);
        PortRegistry registry = new PortRegistry(source, true);
        advance(registry, phase);

        assertEquals(FIRST, registry.port("orders"));
        assertEquals(SECOND, registry.port("ajax"), "the second name gets the number no other name holds");

        assertEquals(4, source.opened(), "the reservation of orders, two duplicates of it, then the one of ajax");
        assertEquals(Set.of(0, 1, 2), source.openWhenRequested(3),
                "both duplicates are still open when the registry asks again");
        assertEquals(List.of(0, 1, 1, 0), source.releases(),
                "each duplicate released once; the reservations handed out held until releaseAll");
        assertEquals(Map.of("ajax", Hold.EXCLUSIVE, "orders", Hold.EXCLUSIVE), registry.holds(),
                "before the handover and after securing, a new name is held exclusively at once");
        assertEquals(Set.of(FIRST, SECOND), registry.ports());

        registry.releaseAll();

        assertEquals(List.of(1, 1, 1, 1), source.releases(), "every reservation released exactly once");
        assertEquals(Set.of(), registry.ports(), "every name forgotten");
        assertEquals(Map.of(), registry.holds());
        assertEquals(Phase.RESERVING, registry.phase(), "a released registry starts afresh");
    }

    @Test
    void givesUpWithAFailureNamingTheNameOnceEveryAttemptLandsOnAHeldNumber() {
        ScriptedSource source = new ScriptedSource(request -> FIRST);
        PortRegistry registry = new PortRegistry(source, true);
        registry.port("orders");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> registry.port("ajax"));

        assertTrue(failure.getMessage().contains("ajax") && failure.getMessage().contains(
                PortRegistry.MAX_ATTEMPTS + " reservations"), failure.getMessage());
        assertEquals(1 + PortRegistry.MAX_ATTEMPTS, source.opened(), "the attempts are bounded");
        assertEquals(Set.of(0), source.stillOpen(), "every duplicate released; orders keeps its reservation");
        assertEquals(Set.of(FIRST), registry.ports(), "ajax holds no port");
    }

    @Test
    void aReservationTheHostCannotOpenFailsTheNameAndReleasesTheDuplicatesOpenedForIt() {
        ScriptedSource source = new ScriptedSource(request -> request < 2 ? FIRST : REFUSED);
        PortRegistry registry = new PortRegistry(source, true);
        registry.port("orders");

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> registry.port("ajax"));

        assertTrue(failure.getMessage().contains("ajax"), failure.getMessage());
        assertEquals(Set.of(0), source.stillOpen(), "the duplicate opened for ajax is released");
        assertEquals(Set.of(FIRST), registry.ports());
    }

    @Test
    void aPublishedPortIsItsNamesPortAndNeverAnotherNames() {
        ScriptedSource source = new ScriptedSource(request -> request == 0 ? PUBLISHED : ALLOCATED);
        PortRegistry registry = new PortRegistry(source, true);

        registry.publish("amqp", PUBLISHED);
        registry.publish("amqp", PUBLISHED);

        assertEquals(PUBLISHED, registry.port("amqp"), "publishing the same pair again changes nothing");
        assertEquals(0, source.opened(), "a published name needs no reservation");
        assertEquals(ALLOCATED, registry.port("orders"), "the published number is never allocated to another name");
        assertEquals(Set.of(1), source.stillOpen(), "the duplicate of the published number is released");
        assertEquals(Set.of(PUBLISHED, ALLOCATED), registry.ports());
        assertEquals(Map.of("amqp", Hold.PUBLISHED, "orders", Hold.EXCLUSIVE), registry.holds(),
                "the registry holds nothing for a published port");
    }

    @ParameterizedTest(name = "publishing {0} on {1} fails: {2}")
    @CsvSource({
            "ldap,   40021, ldap on port 40021: it holds port 40020",
            "amqp,   40020, amqp on port 40020: ldap holds it",
            "amqp,   40030, amqp on port 40030: orders holds it",
            "orders, 40031, orders on port 40031: it holds port 40030",
    })
    void publishingFailsWhenTheNameHoldsAnotherPortOrAnotherNameHoldsThePort(String name, int port,
            String reason) {
        PortRegistry registry = new PortRegistry(new ScriptedSource(request -> ALLOCATED), true);
        registry.publish("ldap", PUBLISHED);
        registry.port("orders");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> registry.publish(name, port));

        assertTrue(failure.getMessage().endsWith(reason), failure.getMessage());
        assertEquals(Set.of(PUBLISHED, ALLOCATED), registry.ports(), "a refused publication changes nothing");
        assertEquals(PUBLISHED, registry.port("ldap"));
        assertEquals(ALLOCATED, registry.port("orders"));
    }

    @ParameterizedTest(name = "port {0}")
    @ValueSource(ints = {0, -1, 65536})
    void aPortOutsideOneTo65535IsNeitherPublishedNorReserved(int port) {
        PortRegistry registry = new PortRegistry(new ScriptedSource(request -> port), true);

        assertThrows(IllegalArgumentException.class, () -> registry.publish("ldap", port));
        assertThrows(IllegalArgumentException.class, () -> registry.port("orders"));
        assertEquals(Set.of(), registry.ports());
    }

    @ParameterizedTest(name = "sharing at the handover: {0}")
    @ValueSource(booleans = {true, false})
    void theHandoverSwapsEachExclusiveReservationForASharedOneOnItsPortOrReleasesItOnce(boolean share) {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request);
        PortRegistry registry = new PortRegistry(source, share);
        registry.publish("amqp", PUBLISHED);
        registry.port("orders");
        registry.port("ajax");

        registry.handOver();

        Hold handedOver = share ? Hold.SHARED : Hold.RELEASED;
        assertEquals(Phase.HANDED_OVER, registry.phase());
        assertEquals(Map.of("ajax", handedOver, "amqp", Hold.PUBLISHED, "orders", handedOver), registry.holds());
        assertEquals(share
                        ? List.of(FIRST + " exclusive", SECOND + " exclusive", FIRST + " shared", SECOND + " shared")
                        : List.of(FIRST + " exclusive", SECOND + " exclusive"), source.opens(),
                "each port is reopened shared at once, or left unheld; the published one is untouched");
        assertEquals(share ? Set.of(2, 3) : Set.of(), source.stillOpen(), "every exclusive reservation is released");

        registry.handOver();

        assertEquals(share ? 4 : 2, source.opened(), "a second handover changes nothing");
        assertEquals(THIRD, registry.port("late"));
        assertEquals(handedOver, registry.holds().get("late"), "a name allocated after the handover is handed over");
        assertEquals(share ? Set.of(2, 3, 5) : Set.of(), source.stillOpen());
        assertEquals(Set.of(FIRST, SECOND, THIRD, PUBLISHED), registry.ports(), "every name keeps its port");
    }

    @ParameterizedTest(name = "sharing at the handover: {0}")
    @ValueSource(booleans = {true, false})
    void securingHoldsEachPortNothingListensOnExclusivelyAgainAndLeavesTheOthersToTheirListenersOnce(boolean share) {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request).listenedOn(FIRST);
        PortRegistry registry = new PortRegistry(source, share);
        registry.publish("amqp", PUBLISHED);
        registry.port("orders");
        registry.port("closed");
        registry.handOver();
        int handedOver = source.opened();

        registry.secure();

        assertEquals(Phase.SECURED, registry.phase());
        assertEquals(Map.of("amqp", Hold.PUBLISHED, "closed", Hold.EXCLUSIVE, "orders", Hold.LISTENER_OWNED),
                registry.holds());
        assertEquals(List.of(SECOND + " exclusive"), source.opens().subList(handedOver, source.opened()),
                "only the port nothing listens on is bound again, exclusively");
        assertEquals(Set.of(handedOver), source.stillOpen(),
                "nothing is held for the listened port, and the other is held by its new exclusive reservation");

        registry.secure();
        registry.handOver();

        assertEquals(handedOver + 1, source.opened(),
                "neither a second securing nor a later handover changes anything");
        assertEquals(THIRD, registry.port("late"));
        assertEquals(Hold.EXCLUSIVE, registry.holds().get("late"),
                "a name allocated after securing is held exclusively");
        assertEquals(THIRD + " exclusive", source.opens().get(source.opened() - 1));

        registry.releaseAll();

        assertEquals(Set.of(), source.stillOpen(), "release frees every reservation");
        assertTrue(source.releases().stream().allMatch(count -> count == 1),
                "every reservation released exactly once: " + source.releases());
        assertEquals(Map.of(), registry.holds());
        assertEquals(Phase.RESERVING, registry.phase());
    }

    @Test
    void securingWithoutAHandoverKeepsEveryExclusiveReservation() {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request);
        PortRegistry registry = new PortRegistry(source, true);
        registry.port("orders");

        registry.secure();
        registry.handOver();

        assertEquals(Phase.SECURED, registry.phase());
        assertEquals(Map.of("orders", Hold.EXCLUSIVE), registry.holds());
        assertEquals(1, source.opened(), "no reservation is reopened, and a handover after securing changes nothing");
        assertEquals(Set.of(0), source.stillOpen());
        registry.releaseAll();
    }

    @Test
    void aPortAnotherSocketBindsDuringTheHandoverIsLeftUnheldAndThenToThatSocket() {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request).takenOn(SECOND);
        PortRegistry registry = new PortRegistry(source, true);
        registry.port("orders");
        registry.port("ajax");

        registry.handOver();

        assertEquals(Map.of("ajax", Hold.RELEASED, "orders", Hold.SHARED), registry.holds(),
                "the port another socket took is left unheld, the other one shared");

        registry.secure();

        assertEquals(Map.of("ajax", Hold.LISTENER_OWNED, "orders", Hold.EXCLUSIVE), registry.holds(),
                "the socket that took the port keeps it");
        assertEquals(Set.of(3), source.stillOpen(), "only the exclusive reservation of orders is held");
        registry.releaseAll();
    }

    @Test
    void aReservationThatCannotBeReopenedFailsItsTransitionAfterEveryOtherNameIsHandled() {
        ScriptedSource source = new ScriptedSource(request -> FIRST + request).brokenOn(FIRST);
        PortRegistry registry = new PortRegistry(source, true);
        registry.port("orders");
        registry.port("ajax");

        UncheckedIOException handover = assertThrows(UncheckedIOException.class, registry::handOver);

        assertTrue(handover.getMessage().contains("port " + FIRST + " of orders"), handover.getMessage());
        assertEquals(Map.of("ajax", Hold.SHARED, "orders", Hold.RELEASED), registry.holds(),
                "the other name is handed over all the same");
        assertEquals(Phase.HANDED_OVER, registry.phase());

        UncheckedIOException securing = assertThrows(UncheckedIOException.class, registry::secure);

        assertTrue(securing.getMessage().contains("port " + FIRST + " of orders"), securing.getMessage());
        assertEquals(Map.of("ajax", Hold.EXCLUSIVE, "orders", Hold.RELEASED), registry.holds(),
                "the other name is secured all the same");
        assertEquals(Phase.SECURED, registry.phase());
        registry.secure();
        registry.releaseAll();
        assertEquals(Set.of(), source.stillOpen(), "release frees every reservation");
    }

    @ParameterizedTest(name = "allocated in phase {0}")
    @EnumSource(Phase.class)
    void everyNameGetsItsOwnPortFromTheHost(Phase phase) {
        PortRegistry registry = new PortRegistry(PortRegistry::openSocket, true);
        try {
            advance(registry, phase);
            Set<Integer> ports = new TreeSet<>();
            for (int i = 0; i < NAMES; i++) {
                ports.add(registry.port("name-" + i));
            }

            assertEquals(NAMES, ports.size(), "no two names share a port");
            assertEquals(ports, registry.ports());
        } finally {
            registry.releaseAll();
        }
    }

    @ParameterizedTest(name = "{0}: a competitor bound with SO_REUSEADDR {1} and listening {2} is refused")
    @CsvSource({
            "RESERVING, true,  true",
            "RESERVING, true,  false",
            "RESERVING, false, true",
            "RESERVING, false, false",
            "SECURED,   true,  true",
            "SECURED,   true,  false",
            "SECURED,   false, true",
            "SECURED,   false, false",
    })
    void anExclusiveReservationRefusesConnectionsAndEveryOtherBind(Phase phase, boolean reuse, boolean listen)
            throws IOException {
        PortRegistry registry = new PortRegistry(PortRegistry::openSocket, true);
        int port = registry.port("closed");
        try {
            advance(registry, phase);

            assertEquals(Hold.EXCLUSIVE, registry.holds().get("closed"),
                    "held exclusively before the handover, and again after securing since nothing listens there");
            assertThrows(BindException.class, () -> bind(port, reuse, listen).close(),
                    "no other socket binds the port");
            assertThrows(ConnectException.class, () -> connect(port).close(),
                    "nothing listens there, so a connection is refused");
        } finally {
            registry.releaseAll();
        }

        bind(port, false, true).close();
    }

    @Test
    void afterTheHandoverAListenerBindsAndAcceptsBesideTheReservationAndSecuringLeavesThePortToIt()
            throws IOException {
        Assumptions.assumeTrue(PortRegistry.listenerRefusalBesideReservation() == null,
                "this host's sockets do not let a listener bind beside a shared reservation");
        PortRegistry registry = PortRegistry.forThisHost();
        int port = registry.port("listener");
        try {
            assertThrows(BindException.class, () -> listen(port).close(),
                    "before the handover the application's listener cannot bind either");

            registry.handOver();

            assertEquals(Hold.SHARED, registry.holds().get("listener"));
            assertThrows(ConnectException.class, () -> connect(port).close(),
                    "the shared reservation refuses connections while nothing listens");
            try (ServerSocket listener = listen(port)) {
                listener.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                try (Socket client = connect(port); Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    client.getOutputStream().write('x');
                    client.getOutputStream().flush();

                    assertEquals('x', accepted.getInputStream().read(), "the listener beside it serves connections");
                    // The client finishes first, so the TIME_WAIT of the connection stays on the client's port.
                    client.shutdownOutput();
                    assertEquals(-1, accepted.getInputStream().read(), "the client's end reached the listener");
                }

                registry.secure();

                assertEquals(Hold.LISTENER_OWNED, registry.holds().get("listener"));
                assertThrows(BindException.class, () -> listen(port).close(),
                        "the listener keeps a competitor that sets SO_REUSEADDR off its port");
            }
            // Nothing is held for a listened port: once its listener has closed, an exclusive bind succeeds at once.
            bind(port, false, false).close();
            assertEquals(port, registry.port("listener"), "the name keeps its port");
        } finally {
            registry.releaseAll();
        }
    }

    @Test
    void aCompetingProcessTakesNeitherAReservedPortNorOneItsListenerBoundAfterTheHandover() throws Exception {
        Assumptions.assumeTrue(PortRegistry.listenerRefusalBesideReservation() == null,
                "this host's sockets do not let a listener bind beside a shared reservation");
        PortRegistry registry = PortRegistry.forThisHost();
        int listened = registry.port("listener");
        int closed = registry.port("closed");
        try {
            assertEquals(Map.of(listened, "refused", closed, "refused"), competingListeners(listened, closed),
                    "another process's SO_REUSEADDR listener binds neither exclusive reservation");

            registry.handOver();
            try (ServerSocket listener = listen(listened)) {
                registry.secure();

                assertEquals(listened, listener.getLocalPort(),
                        "the intended listener bound its port after the handover");
                assertEquals(Map.of("closed", Hold.EXCLUSIVE, "listener", Hold.LISTENER_OWNED), registry.holds());
                assertEquals(Map.of(listened, "refused", closed, "refused"), competingListeners(listened, closed),
                        "nor the port the intended listener bound, nor the one held exclusively again");
            }
        } finally {
            registry.releaseAll();
        }

        assertEquals(Map.of(listened, "bound", closed, "bound"), competingListeners(listened, closed),
                "release frees every port");
    }

    @Test
    void anUndertowContextBindsItsListenersThroughTheHandoverAndThePortNothingListensOnIsSecured() throws IOException {
        PortRegistry registry = PortRegistry.forThisHost();
        int main = registry.port("orders");
        int additional = registry.port("ajax");
        int closed = registry.port("closed");
        UndertowServletWebServerFactory factory = new UndertowServletWebServerFactory(main);
        factory.addBuilderCustomizers(builder -> builder.addHttpListener(additional, "0.0.0.0"));
        try (AnnotationConfigServletWebServerApplicationContext context =
                     new AnnotationConfigServletWebServerApplicationContext()) {
            context.registerBean("webServerFactory", UndertowServletWebServerFactory.class, () -> factory);
            context.addBeanFactoryPostProcessor(beanFactory -> beanFactory.registerSingleton(
                    PortRegistry.Handover.BEAN_NAME, new PortRegistry.Handover(() -> registry)));
            context.addApplicationListener(new PortRegistry.Securing(context, () -> registry));

            context.refresh();

            assertTrue(PortRegistry.Handover.PHASE
                            < context.getBean("webServerStartStop", SmartLifecycle.class).getPhase(),
                    "the handover starts before the lifecycle that starts the web server");
            assertEquals(Phase.SECURED, registry.phase(), "securing followed the context's refresh");
            assertEquals(Map.of("ajax", Hold.LISTENER_OWNED, "closed", Hold.EXCLUSIVE, "orders", Hold.LISTENER_OWNED),
                    registry.holds(),
                    "Undertow bound its main and its additional listener beside the handed-over ports");
            connect(main).close();
            connect(additional).close();
            assertThrows(ConnectException.class, () -> connect(closed).close(), "nothing listens on the third port");
            assertThrows(BindException.class, () -> listen(closed).close(), "and it is held exclusively again");
        } finally {
            registry.releaseAll();
        }
    }

    /** Moves a registry with no handed-over names to {@code phase}, as the application context's start would. */
    private static void advance(PortRegistry registry, Phase phase) {
        if (phase != Phase.RESERVING) {
            registry.handOver();
        }
        if (phase == Phase.SECURED) {
            registry.secure();
        }
    }

    /**
     * Runs {@link CompetingListener} on {@code ports} in a JVM of its own, on this JVM's class path, and returns
     * what it reported for each port: {@code bound} or {@code refused}.
     */
    private static Map<Integer, String> competingListeners(int... ports) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m",
                "-XX:TieredStopAtLevel=1", "-cp", System.getProperty("java.class.path"),
                CompetingListener.class.getName()));
        for (int port : ports) {
            command.add(Integer.toString(port));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("the competing process did not finish within " + CHILD_TIMEOUT_SECONDS + " s");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "the competing process failed: " + output);
        Map<Integer, String> results = new TreeMap<>();
        for (String line : output.strip().split("\\R")) {
            String[] fields = line.split(" ", 2);
            if (fields.length != 2 || !(fields[1].equals("bound") || fields[1].equals("refused"))) {
                fail("the competing process reported " + output);
            }
            results.put(Integer.valueOf(fields[0]), fields[1]);
        }
        return results;
    }

    /** A listener bound with {@code SO_REUSEADDR}, as the application's listeners bind. */
    private static ServerSocket listen(int port) throws IOException {
        return (ServerSocket) bind(port, true, true);
    }

    /** A server socket when {@code listen}, else an unconnected socket, bound to the wildcard address on the port. */
    private static Closeable bind(int port, boolean reuse, boolean listen) throws IOException {
        if (listen) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(reuse);
                socket.bind(new InetSocketAddress(port));
                return socket;
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(reuse);
            socket.bind(new InetSocketAddress(port));
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), SOCKET_TIMEOUT_MILLIS);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Binds a listener with {@code SO_REUSEADDR} on each port its arguments name, closes it at once and prints
     * {@code <port> bound}, or {@code <port> refused} when the bind fails: a competing program, run in a JVM of its
     * own.
     */
    public static final class CompetingListener {

        private CompetingListener() {
        }

        public static void main(String[] args) throws IOException {
            for (String argument : args) {
                int port = Integer.parseInt(argument);
                try (ServerSocket listener = new ServerSocket()) {
                    listener.setReuseAddress(true);
                    listener.bind(new InetSocketAddress(port));
                    System.out.println(port + " bound");
                } catch (BindException refused) {
                    System.out.println(port + " refused");
                }
            }
        }
    }

    /**
     * Hands out reservations whose ports a function of the allocation request's index chooses, and reopens any port
     * it is asked for, recording each open and release and, for each allocation request, which earlier reservations
     * were still open when it was made. Reopening a port it was told a listener holds fails without
     * {@code SO_REUSEADDR}, and one it was told another socket took fails either way, both with a
     * {@link BindException}; reopening a broken port fails with a plain {@link IOException}.
     */
    private static final class ScriptedSource implements PortRegistry.ReservationSource {

        private final IntUnaryOperator portForRequest;
        private final Set<Integer> listened = new HashSet<>();
        private final Set<Integer> taken = new HashSet<>();
        private final Set<Integer> broken = new HashSet<>();
        private final List<String> opens = new ArrayList<>();
        private final List<Integer> releases = new ArrayList<>();
        private final List<Set<Integer>> openWhenRequested = new ArrayList<>();

        ScriptedSource(IntUnaryOperator portForRequest) {
            this.portForRequest = portForRequest;
        }

        ScriptedSource listenedOn(int port) {
            listened.add(port);
            return this;
        }

        ScriptedSource takenOn(int port) {
            taken.add(port);
            return this;
        }

        ScriptedSource brokenOn(int port) {
            broken.add(port);
            return this;
        }

        @Override
        public PortRegistry.Reservation open(int port, boolean shared) throws IOException {
            int chosen = port;
            if (port == 0) {
                int request = openWhenRequested.size();
                openWhenRequested.add(stillOpen());
                chosen = portForRequest.applyAsInt(request);
                if (chosen == REFUSED) {
                    throw new IOException("scripted refusal of request " + request);
                }
            } else if (taken.contains(port) || (!shared && listened.contains(port))) {
                throw new BindException("scripted: another socket is bound to port " + port);
            } else if (broken.contains(port)) {
                throw new IOException("scripted failure to bind port " + port);
            }
            int index = releases.size();
            PortRegistry.Reservation reservation =
                    new PortRegistry.Reservation(chosen, () -> releases.set(index, releases.get(index) + 1));
            releases.add(0);
            opens.add(chosen + (shared ? " shared" : " exclusive"));
            return reservation;
        }

        /** Reservations opened, in order; a refused or failed request opens none. */
        int opened() {
            return releases.size();
        }

        /** Each reservation opened, as {@code <port> shared} or {@code <port> exclusive}, in order. */
        List<String> opens() {
            return List.copyOf(opens);
        }

        /** How often each reservation was released, by the order it was opened in. */
        List<Integer> releases() {
            return List.copyOf(releases);
        }

        /** The reservations never released, by the order they were opened in. */
        Set<Integer> stillOpen() {
            Set<Integer> open = new TreeSet<>();
            for (int i = 0; i < releases.size(); i++) {
                if (releases.get(i) == 0) {
                    open.add(i);
                }
            }
            return open;
        }

        Set<Integer> openWhenRequested(int request) {
            return openWhenRequested.get(request);
        }
    }
}
