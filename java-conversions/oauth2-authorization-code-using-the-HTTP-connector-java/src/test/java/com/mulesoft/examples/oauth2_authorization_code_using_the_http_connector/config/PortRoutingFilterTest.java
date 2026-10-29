package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.MappingMatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockRequestDispatcher;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.TestSocketUtils;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests SC-10: {@link PortRoutingFilter} scoping each request to the routes of the listener that received it, and the
 * {@link ListenerPorts} beans that register the filter and open the additional Undertow listeners. Every SC-10 consumer
 * carries this class unchanged apart from its package line and the package-relative {@code support.HttpTestClient}
 * (ST-1) import, which is why every route, port and path below is synthetic and names no project type.
 *
 * <p>The no-listener 404 is compared byte for byte (status, {@code text/plain}, body), because it is a
 * platform-default row whose captured fixture would govern.
 *
 * <p>The server cases run Undertow on a random main port of {@code localhost} beside additional listeners on drawn
 * ports. A drawn port is free when drawn, not when bound, so a case whose drawn port another process takes first
 * draws again, up to {@link #PORT_DRAWS} times. They also cover the start-up failures of a listener that cannot be
 * bound, which must end within {@link #FAIL_FAST}, and the XNIO worker {@link ListenerPorts} gives the server: its
 * threads, and its shutdown when the customizer is destroyed. A worker is found through the XNIO threads that
 * appeared during the case, which is exact because the test classes of a module run one at a time.
 */
class PortRoutingFilterTest {

    /** Local port of the main listener; any port that no additional listener owns behaves the same. */
    private static final int MAIN = 18001;
    private static final int EXTRA = 18002;
    /** A local port no listener of the fixture names, so the main routes judge it. */
    private static final int UNKNOWN = 9;

    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";
    private static final String APP_SERVLET = "appServlet";
    private static final String MAPPED_SERVLET = "mappedServlet";

    /** Personal and secret segments a caller can put in any path, which no routing log line may repeat. */
    private static final String PERSONAL_SEGMENT = "alice@example.com";
    private static final String SECRET_SEGMENT = "tok-SENTINEL";
    private static final String SENTINEL_SEGMENTS = "/" + PERSONAL_SEGMENT + "/" + SECRET_SEGMENT;

    /** Passwords and a placeholder default the TLS cases configure, which no start-up failure may repeat. */
    private static final String STORE_SECRET = "store-SENTINEL";
    private static final String KEY_SECRET = "key-SENTINEL";
    private static final String TRUST_SECRET = "trust-SENTINEL";
    private static final String WRONG_SECRET = "wrong-SENTINEL";
    private static final String DEFAULT_SECRET = "default-SENTINEL";
    /** The TLS entry is listed second, so a failure must name its own index. */
    private static final String TLS_LABEL = "secure (listeners.additional[1])";
    private static final String TLS_SETTING = "listeners.additional[1].ssl.";

    /** {@code /svc/**} stays with the servlet the container mapped; {@code /app/**} is forwarded to appServlet. */
    private static final List<ListenerPorts.Route> MAIN_ROUTES = List.of(
            new ListenerPorts.Route(null, List.of("/svc/**")),
            new ListenerPorts.Route(APP_SERVLET, List.of("/app/**")));

    private static final Map<Integer, List<ListenerPorts.Route>> ADDITIONAL_ROUTES =
            Map.of(EXTRA, List.of(new ListenerPorts.Route(null, List.of("/extra/**"))));

    /** Host of every server case's listeners. */
    private static final String LOCALHOST = "localhost";

    /** Draws of an extra port a server case makes before it fails. */
    private static final int PORT_DRAWS = 5;

    /** Bound on a start that must fail, and on a stop or shutdown, in the server cases. */
    private static final Duration FAIL_FAST = Duration.ofSeconds(30);

    /** Connect timeout of the checks whether a port accepts connections. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Undertow's default I/O threads: the available processors, at least 2. */
    private static final int DEFAULT_IO_THREADS = Math.max(Runtime.getRuntime().availableProcessors(), 2);

    /** Undertow's default worker threads: eight per default I/O thread. */
    private static final int DEFAULT_WORKER_THREADS = DEFAULT_IO_THREADS * 8;

    /** Name of an I/O thread of an XNIO worker; the worker's accept thread is named {@code XNIO-<n> Accept}. */
    private static final Pattern XNIO_IO_THREAD = Pattern.compile("XNIO-\\d+ I/O-\\d+");

    /** The deployment of every server case: {@link MethodEchoServlet} on every path. */
    private static final ServletContextInitializer METHOD_ECHO =
            context -> context.addServlet("simple", new MethodEchoServlet()).addMapping("/");

    private final MockServletContext servletContext = new MockServletContext();
    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private PortRoutingFilter filter;

    /** Where the TLS cases write their key and trust stores. */
    @TempDir
    Path storeDir;

    @BeforeEach
    void createFilter() throws ServletException {
        filter = initialised(new PortRoutingFilter(MAIN_ROUTES, ADDITIONAL_ROUTES));
    }

    static Stream<Arguments> unservedRequests() {
        return Stream.of(
                // The query string is no part of the endpoint the answer names.
                Arguments.of(MAIN, "GET", "", "/nowhere/here", "a=b&c=d", "No listener for endpoint: /nowhere/here"),
                // A main-only path on the extra listener: routes are per port.
                Arguments.of(EXTRA, "POST", "", "/svc/Thing", null, "No listener for endpoint: /svc/Thing"),
                // An unknown local port is judged by the main routes, which do not serve the extra listener's path.
                Arguments.of(UNKNOWN, "GET", "", "/extra/x", null, "No listener for endpoint: /extra/x"),
                // The raw URI is used, not decoded.
                Arguments.of(MAIN, "GET", "", "/no%20where", null, "No listener for endpoint: /no%20where"),
                // The path is the one within the application context, and "/" when nothing is left.
                Arguments.of(MAIN, "GET", "/ctx", "/ctx/nowhere", null, "No listener for endpoint: /nowhere"),
                Arguments.of(MAIN, "GET", "/ctx", "/ctx", null, "No listener for endpoint: /"));
    }

    @ParameterizedTest(name = "{1} {3} on port {0} -> {5}")
    @MethodSource("unservedRequests")
    void unservedPathGetsTheNoListener404AndEndsTheChain(int port, String method, String contextPath, String uri,
            String query, String expectedBody) throws Exception {
        MockHttpServletRequest request = request(port, method, uri, query);
        request.setContextPath(contextPath);

        filter.doFilter(request, response, chain);

        byte[] expected = expectedBody.getBytes(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.getContentType()).isEqualTo(NO_LISTENER_CONTENT_TYPE);
        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getContentLength()).isEqualTo(expected.length);
        assertThat(response.getErrorMessage()).as("written by the filter, never handed to sendError").isNull();
        assertThat(chain.getRequest()).as("chain invoked").isNull();
    }

    static Stream<Arguments> servedRequests() {
        return Stream.of(
                Arguments.of(MAIN, "", "/svc/Echo"),
                Arguments.of(EXTRA, "", "/extra/x"),
                Arguments.of(UNKNOWN, "", "/svc/Echo"),
                Arguments.of(MAIN, "/ctx", "/ctx/svc/Echo"));
    }

    @ParameterizedTest(name = "GET {2} on port {0} continues")
    @MethodSource("servedRequests")
    void pathServedWithTheMappedServletContinuesTheChainUntouched(int port, String contextPath, String uri)
            throws Exception {
        MockHttpServletRequest request = request(port, "GET", uri, null);
        request.setContextPath(contextPath);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(chain.getResponse()).isSameAs(response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void namedRouteIsForwardedToItsServletAsTheDefaultServlet() throws Exception {
        AtomicReference<ServletRequest> forwarded = new AtomicReference<>();
        servletContext.registerNamedDispatcher(APP_SERVLET, new RequestDispatcher() {
            @Override
            public void forward(ServletRequest forwardedRequest, ServletResponse forwardedResponse)
                    throws IOException {
                forwarded.set(forwardedRequest);
                forwardedResponse.getWriter().write("forwarded");
            }

            @Override
            public void include(ServletRequest includedRequest, ServletResponse includedResponse) {
                throw new AssertionError("the filter forwards, it never includes");
            }
        });
        MockHttpServletRequest request = request(MAIN, "GET", "/app/orders/1", null);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain invoked").isNull();
        assertThat(forwarded.get()).as("forwarded request").isInstanceOf(HttpServletRequest.class);
        HttpServletRequest target = (HttpServletRequest) forwarded.get();
        HttpServletMapping mapping = target.getHttpServletMapping();
        assertThat(mapping.getMappingMatch()).isEqualTo(MappingMatch.DEFAULT);
        assertThat(mapping.getServletName()).isEqualTo(APP_SERVLET);
        assertThat(mapping.getPattern()).isEqualTo("/");
        assertThat(mapping.getMatchValue()).isEmpty();
        assertThat(target.getServletPath()).isEqualTo("/app/orders/1");
        assertThat(target.getPathInfo()).isNull();
        assertThat(target.getPathTranslated()).isNull();
        assertThat(target.getRequestURI()).isEqualTo("/app/orders/1");
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsString()).isEqualTo("forwarded");
    }

    @Test
    void namedRouteWithoutRegisteredServletFailsTheRequest() {
        MockHttpServletRequest request = request(MAIN, "GET", "/app/orders/1", null);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + APP_SERVLET + "'")
                .hasMessageContaining("/app/orders/1")
                .hasMessageContaining("port " + MAIN);
        assertThat(chain.getRequest()).as("chain invoked").isNull();
    }

    static Stream<Arguments> routesKeepingTheMappedServlet() {
        return Stream.of(
                Arguments.of(Named.of("blank servlet name", "  "), null),
                Arguments.of(Named.of("the servlet the container mapped", APP_SERVLET), APP_SERVLET),
                Arguments.of(Named.of("the mapped servlet, padded", " " + APP_SERVLET + " "), APP_SERVLET));
    }

    @ParameterizedTest(name = "route naming {0} continues the chain")
    @MethodSource("routesKeepingTheMappedServlet")
    void routeKeepingTheMappedServletContinuesTheChain(String routeServlet, String mappedServlet) throws Exception {
        PortRoutingFilter keeping = initialised(new PortRoutingFilter(
                List.of(new ListenerPorts.Route(routeServlet, List.of("/app/**"))), Map.of()));
        MockHttpServletRequest request = request(MAIN, "GET", "/app/orders/1", null);
        if (mappedServlet != null) {
            request.setHttpServletMapping(new MockHttpServletMapping("", "/", mappedServlet, MappingMatch.DEFAULT));
        }

        // No named dispatcher is registered, so an attempted forward would fail with IllegalStateException.
        keeping.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    static Stream<Arguments> unroutedListenersOnEveryPortAndMethod() {
        List<Named<Supplier<PortRoutingFilter>>> unrouted = List.of(
                Named.of("empty route lists", () -> new PortRoutingFilter(List.of(), Map.of())),
                Named.of("null route lists", () -> new PortRoutingFilter(null, null)),
                Named.of("empty extra routes", () -> new PortRoutingFilter(List.of(), Map.of(EXTRA, List.of()))));
        return unrouted.stream().flatMap(construction -> Stream.of(MAIN, EXTRA, UNKNOWN).flatMap(port ->
                Stream.of("GET", "POST", "TRACE").map(method -> Arguments.of(construction, port, method))));
    }

    @ParameterizedTest(name = "{0}: {2} on port {1} continues")
    @MethodSource("unroutedListenersOnEveryPortAndMethod")
    void listenerWithoutRoutesServesEveryPathAndMethod(Supplier<PortRoutingFilter> construction, int port,
            String method) throws Exception {
        PortRoutingFilter unrouted = initialised(construction.get());
        MockHttpServletRequest request = request(port, method, "/anything", null);

        unrouted.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    static Stream<Arguments> routingDecisions() {
        Supplier<PortRoutingFilter> unrouted = () -> new PortRoutingFilter(List.of(), Map.of());
        Supplier<PortRoutingFilter> routed = () -> new PortRoutingFilter(MAIN_ROUTES, ADDITIONAL_ROUTES);
        return Stream.of(
                Arguments.of(Named.of("served unrouted", unrouted), "/users" + SENTINEL_SEGMENTS,
                        "Port " + MAIN + " serves every path; the request continues unrouted"),
                Arguments.of(Named.of("no-listener 404", routed), "/users" + SENTINEL_SEGMENTS,
                        "No route on port " + MAIN + " matches the request path; answering 404"),
                Arguments.of(Named.of("kept on the mapped servlet", routed), "/svc" + SENTINEL_SEGMENTS,
                        "Port " + MAIN + " serves the request through " + MAPPED_SERVLET
                                + " as mapped, by route [/svc/**]"),
                Arguments.of(Named.of("forwarded to its servlet", routed), "/app" + SENTINEL_SEGMENTS,
                        "Port " + MAIN + " forwards the request from " + MAPPED_SERVLET + " to " + APP_SERVLET
                                + ", by route [/app/**]"));
    }

    /**
     * The log oracle is a Logback {@link ListAppender} on the filter's logger, attached at DEBUG for the one request
     * and detached with the logger's level restored afterwards.
     */
    @ParameterizedTest(name = "{0}: logged with the port and route, never the path")
    @MethodSource("routingDecisions")
    void routingDecisionIsLoggedWithoutTheRequestPath(Supplier<PortRoutingFilter> construction, String uri,
            String expectedMessage) throws Exception {
        servletContext.registerNamedDispatcher(APP_SERVLET, new MockRequestDispatcher(APP_SERVLET));
        PortRoutingFilter routing = initialised(construction.get());
        MockHttpServletRequest request = request(MAIN, "GET", uri, null);
        request.setHttpServletMapping(new MockHttpServletMapping("", "/", MAPPED_SERVLET, MappingMatch.DEFAULT));
        Logger logger = (Logger) LoggerFactory.getLogger(PortRoutingFilter.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            routing.doFilter(request, response, chain);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .contains(tuple(Level.DEBUG, expectedMessage));
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(PERSONAL_SEGMENT, SECRET_SEGMENT);
            Object[] arguments = event.getArgumentArray();
            assertThat(arguments == null ? List.of() : Arrays.asList(arguments))
                    .allSatisfy(argument -> assertThat(String.valueOf(argument))
                            .doesNotContain(PERSONAL_SEGMENT, SECRET_SEGMENT));
        });
    }

    @Test
    void registrationWiresTheRoutesOfEachListenerAheadOfEveryFilterForRequestDispatchesOnly() throws Exception {
        ListenerPorts.Properties properties = new ListenerPorts.Properties(null, new ListenerPorts.Main(MAIN_ROUTES),
                List.of(new ListenerPorts.Additional("extra", EXTRA, null, null, ADDITIONAL_ROUTES.get(EXTRA))),
                null);

        FilterRegistrationBean<PortRoutingFilter> registration =
                new ListenerPorts().portRoutingFilterRegistration(properties);

        assertThat(registration.getFilterName()).isEqualTo("portRoutingFilter");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.determineDispatcherTypes()).containsExactly(DispatcherType.REQUEST);

        PortRoutingFilter registered = initialised(registration.getFilter());
        MockHttpServletRequest mainOnlyOnExtra = request(EXTRA, "GET", "/svc/Echo", null);
        registered.doFilter(mainOnlyOnExtra, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("No listener for endpoint: /svc/Echo");

        MockFilterChain mainChain = new MockFilterChain();
        MockHttpServletRequest onMain = request(MAIN, "GET", "/svc/Echo", null);
        registered.doFilter(onMain, new MockHttpServletResponse(), mainChain);
        assertThat(mainChain.getRequest()).isSameAs(onMain);
    }

    static Stream<Arguments> invalidAdditionalPorts() {
        return Stream.of(
                Arguments.of(List.of(additional("low", 0)),
                        "low (listeners.additional[0]): port 0 is outside 1..65535;"
                                + " additional listeners need a concrete port"),
                Arguments.of(List.of(additional("high", 65536)),
                        "high (listeners.additional[0]): port 65536 is outside 1..65535;"
                                + " additional listeners need a concrete port"),
                Arguments.of(List.of(additional("first", EXTRA), additional("second", EXTRA)),
                        "second (listeners.additional[1]): port " + EXTRA
                                + " is already used by first (listeners.additional[0])"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidAdditionalPorts")
    void invalidAdditionalPortStopsBothBeans(List<ListenerPorts.Additional> additional, String message) {
        ListenerPorts.Properties properties = new ListenerPorts.Properties(null, null, additional, null);
        ListenerPorts listenerPorts = new ListenerPorts();

        assertThatThrownBy(() -> listenerPorts.portRoutingFilterRegistration(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> listenerPorts.additionalListenersCustomizer(properties, new DefaultResourceLoader(),
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> tlsFailures() {
        String keyStorePassword = TLS_SETTING + "key-store-password";
        String keyPassword = TLS_SETTING + "key-password";
        String trustStorePassword = TLS_SETTING + "trust-store-password";
        return Stream.of(
                // JKS checks the store's integrity with any non-null password, so an empty one fails the load.
                Arguments.of(Named.of("empty key store password", "JKS"), "", null, null, Map.of(),
                        "the key store password is empty; supply " + keyStorePassword),
                Arguments.of(Named.of("empty key store password read through a placeholder", "JKS"), "", null, null,
                        Map.of(keyStorePassword, "${tls.store-password}"),
                        "the key store password is empty; supply tls.store-password (" + keyStorePassword + ")"),
                Arguments.of(Named.of("empty trust store password read through a placeholder with a default", "JKS"),
                        STORE_SECRET, null, "",
                        Map.of(trustStorePassword, "${tls.trust-password:" + DEFAULT_SECRET + "}"),
                        "the trust store password is empty; supply tls.trust-password (" + trustStorePassword + ")"),
                // A value that is more than one placeholder names no key.
                Arguments.of(Named.of("empty trust store password with a compound value", "JKS"), STORE_SECRET, null,
                        "", Map.of(trustStorePassword, "pre-${tls.trust-password}"),
                        "the trust store password is empty; supply " + trustStorePassword),
                // The PKCS12 store opens with an empty password; its key does not, and no other password is left.
                Arguments.of(Named.of("empty key password and key store password", "PKCS12"), "", "",
                        null, Map.of(keyPassword, "${tls.key-password}", keyStorePassword, ""),
                        "the key password is empty; supply tls.key-password (" + keyPassword + ") or "
                                + keyStorePassword),
                Arguments.of(Named.of("unset key password and key store password", "PKCS12"), null, null, null,
                        Map.of(), "the key password is empty; supply " + keyPassword + " or " + keyStorePassword),
                // A password that is set keeps the JDK's reason, however the setting is configured.
                Arguments.of(Named.of("wrong key store password", "JKS"), WRONG_SECRET, null, null,
                        Map.of(keyStorePassword, "${tls.store-password}"),
                        "Keystore was tampered with, or password was incorrect"));
    }

    /**
     * The TLS entry is the second of two, beside a plain one. Its key store is, for {@code JKS}, an empty store saved
     * with {@link #STORE_SECRET} and, for {@code PKCS12}, a store saved with an empty password holding one key
     * protected by {@link #KEY_SECRET}; a non-null {@code trustStorePassword} adds an empty JKS trust store saved with
     * {@link #TRUST_SECRET}. {@code configured} holds the unresolved values the environment reports.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tlsFailures")
    void tlsFailureNamesTheSettingOfAnEmptyPasswordOrKeepsTheJdkReason(String keyStoreType, String keyStorePassword,
            String keyPassword, String trustStorePassword, Map<String, String> configured, String reason)
            throws Exception {
        String keyStore = "PKCS12".equals(keyStoreType)
                ? writeStore("key.p12", keyStoreType, "", KEY_SECRET)
                : writeStore("key.jks", keyStoreType, STORE_SECRET, null);
        String trustStore = trustStorePassword == null ? null : writeStore("trust.jks", "JKS", TRUST_SECRET, null);
        ListenerPorts.Properties properties = tlsProperties(new ListenerPorts.Ssl(keyStore, keyStorePassword,
                keyPassword, keyStoreType, trustStore, trustStorePassword));
        MockEnvironment environment = new MockEnvironment();
        configured.forEach(environment::setProperty);

        assertThatThrownBy(() -> new ListenerPorts().additionalListenersCustomizer(properties,
                new DefaultResourceLoader(), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TLS_LABEL + ": cannot build the TLS context: " + reason)
                .satisfies(failure -> assertThat(failure.getCause())
                        .isInstanceOfAny(IOException.class, GeneralSecurityException.class))
                .message()
                .doesNotContain(STORE_SECRET, KEY_SECRET, TRUST_SECRET, WRONG_SECRET, DEFAULT_SECRET);
    }

    @Test
    void storeAndKeyThatOpenWithAnEmptyPasswordStillBuildTheTlsContext() throws Exception {
        String keyStore = writeStore("open.p12", "PKCS12", "", "");
        ListenerPorts.Properties properties =
                tlsProperties(new ListenerPorts.Ssl(keyStore, "", "", "PKCS12", null, null));

        assertThatCode(() -> new ListenerPorts().additionalListenersCustomizer(properties,
                new DefaultResourceLoader(), new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> methodRestrictions() {
        return Stream.of(
                // Nothing restricted, as on a listener without allowedMethods: TRACE reaches the servlet.
                Arguments.of(List.of(), HttpServletResponse.SC_OK, "ok:TRACE"),
                // Restricted: Undertow answers 405 with an empty body before any filter or servlet runs.
                Arguments.of(List.of("TRACE"), HttpServletResponse.SC_METHOD_NOT_ALLOWED, ""));
    }

    @ParameterizedTest(name = "disallowed {0}: TRACE -> {1}")
    @MethodSource("methodRestrictions")
    void additionalListenerServesTheSameDeploymentBesideTheMainOne(List<String> disallowedMethods, int traceStatus,
            String traceBody) throws Exception {
        ExtraListenerServer started = startedServer(disallowedMethods, new MockEnvironment());
        try {
            int extraPort = started.extraPort();
            int mainPort = started.server().getPort();
            assertThat(mainPort).as("main listener port").isPositive().isNotEqualTo(extraPort);

            for (int port : new int[] {extraPort, mainPort}) {
                HttpTestClient client = HttpTestClient.localhost(port);

                HttpTestClient.Response get = client.send("GET", "/anything", Map.of(), null);
                assertThat(get.status()).as("GET on port %d: %s", port, get).isEqualTo(HttpServletResponse.SC_OK);
                assertThat(get.bodyAsString()).as("GET body on port %d", port).isEqualTo("ok:GET");

                HttpTestClient.Response trace = client.send("TRACE", "/anything", Map.of(), null);
                assertThat(trace.status()).as("TRACE on port %d: %s", port, trace).isEqualTo(traceStatus);
                assertThat(trace.bodyAsString()).as("TRACE body on port %d", port).isEqualTo(traceBody);
            }
        } finally {
            stopAndDestroy(started);
        }
    }

    @Test
    void extraPortAnotherSocketHoldsFailsServerCreationWithPortInUseBeforeAnyWorker() throws Exception {
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getByName(LOCALHOST))) {
            int heldPort = holder.getLocalPort();
            WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer =
                    extraListenerCustomizer(LOCALHOST, List.of(heldPort), List.of(), new MockEnvironment());
            UndertowServletWebServerFactory factory = localhostFactory(0);
            customizer.customize(factory);
            Set<Thread> before = liveThreads();
            try {
                assertThatThrownBy(() -> factory.getWebServer(METHOD_ECHO))
                        .isInstanceOfSatisfying(PortInUseException.class,
                                failure -> assertThat(failure.getPort()).isEqualTo(heldPort))
                        .hasMessage("Port " + heldPort + " is already in use");
                assertThat(newWorkers(before)).as("workers created for the failed server").isEmpty();
            } finally {
                destroy(customizer);
            }
        }
    }

    static Stream<Arguments> unbindableHosts() {
        return Stream.of(
                // TEST-NET-1 (RFC 5737) is assigned to no host, so it is not a local address.
                Arguments.of("192.0.2.1", BindException.class),
                Arguments.of("no-such-listener-host.invalid", UnknownHostException.class));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("unbindableHosts")
    void extraListenerThatCannotBeBoundForAnotherReasonFailsServerCreationNamingIt(String host,
            Class<? extends IOException> cause) throws Exception {
        WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer =
                extraListenerCustomizer(host, List.of(EXTRA), List.of(), new MockEnvironment());
        UndertowServletWebServerFactory factory = localhostFactory(0);
        customizer.customize(factory);
        Set<Thread> before = liveThreads();
        try {
            assertThatThrownBy(() -> factory.getWebServer(METHOD_ECHO))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("extra-1 (listeners.additional[0]): cannot bind " + host + ":" + EXTRA
                            + ": ")
                    .hasCauseInstanceOf(cause);
            assertThat(newWorkers(before)).as("workers created for the failed server").isEmpty();
        } finally {
            destroy(customizer);
        }
    }

    /** A negative port is how Spring Boot creates a server it never starts, so no listener is probed or bound. */
    @Test
    void serverThatNeverStartsIsNeitherProbedNorGivenAWorker() throws Exception {
        try (ServerSocket holder = new ServerSocket(0, 1, InetAddress.getByName(LOCALHOST))) {
            WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer = extraListenerCustomizer(LOCALHOST,
                    List.of(holder.getLocalPort()), List.of(), new MockEnvironment());
            UndertowServletWebServerFactory factory = localhostFactory(-1);
            customizer.customize(factory);
            Set<Thread> before = liveThreads();
            try {
                WebServer server = factory.getWebServer(METHOD_ECHO);
                server.start();
                assertThat(newWorkers(before)).as("workers created for a server that never starts").isEmpty();
                server.stop();
            } finally {
                destroy(customizer);
            }
        }
    }

    static Stream<Arguments> startFailuresOfATakenFirstExtraPort() {
        return Stream.of(
                // The taken port is the one port left unbound, which Spring Boot reports as in use.
                Arguments.of(1, Named.of("PortInUseException naming the taken port",
                        (BiConsumer<Throwable, Integer>) (failure, takenPort) -> assertThat(failure)
                                .isInstanceOfSatisfying(PortInUseException.class,
                                        portInUse -> assertThat(portInUse.getPort()).isEqualTo(takenPort)))),
                // The listener after the taken one is left unbound too, so Spring Boot reports Undertow's failure.
                Arguments.of(2, Named.of("WebServerException caused by the BindException",
                        (BiConsumer<Throwable, Integer>) (failure, takenPort) -> assertThat(failure)
                                .isExactlyInstanceOf(WebServerException.class)
                                .rootCause()
                                .isInstanceOf(BindException.class)
                                .hasMessageContaining("in use"))));
    }

    /**
     * The first extra port is taken after the probe and before Undertow binds it, a race no probe closes. The main
     * listener is bound by then, and closing it on the worker Undertow had already shut down once hung the start
     * forever.
     */
    @ParameterizedTest(name = "{0} additional listener(s) -> {1}")
    @MethodSource("startFailuresOfATakenFirstExtraPort")
    void extraPortTakenAfterTheProbeFailsTheStartInsteadOfHangingIt(int listeners,
            BiConsumer<Throwable, Integer> startFailure) throws Exception {
        for (int draw = 1; draw <= PORT_DRAWS; draw++) {
            Set<Thread> before = liveThreads();
            ExtraListenerServer created = createdServer(listeners, List.of(), new MockEnvironment());
            ServerSocket taker = takeOrNull(created.extraPort());
            if (taker == null) {
                stopAndDestroy(created);
                continue;
            }
            try (taker) {
                List<XnioWorker> workers = newWorkers(before);
                assertThat(workers).as("workers of the created server").hasSize(1);

                assertTimeoutPreemptively(FAIL_FAST, () -> assertThatThrownBy(created.server()::start)
                        .satisfies(failure -> startFailure.accept(failure, created.extraPort())));

                assertTimeoutPreemptively(FAIL_FAST, () -> stopAndDestroy(created));
                assertThat(workers.get(0).awaitTermination(FAIL_FAST.toMillis(), TimeUnit.MILLISECONDS))
                        .as("worker terminated after destroy").isTrue();
            } finally {
                // Bounded: a start that hung still holds the server's lock, which stop() waits for.
                assertTimeoutPreemptively(FAIL_FAST, () -> stopAndDestroy(created));
            }
            return;
        }
        throw new AssertionError("Another process took every drawn extra port before this case could hold it");
    }

    /**
     * A refresh that fails after the server started destroys the beans before it stops the server, so the customizer
     * stops the context's server itself before the worker; stopping the worker first would leave the listeners to be
     * closed on a worker that no longer runs.
     */
    @Test
    void destroyStopsTheContextsStartedServerBeforeTheWorker() throws Exception {
        Set<Thread> before = liveThreads();
        ExtraListenerServer started = startedServer(List.of(), new MockEnvironment());
        try {
            int[] ports = {started.extraPort(), started.server().getPort()};
            List<XnioWorker> workers = newWorkers(before);
            assertThat(workers).as("workers of the started server").hasSize(1);
            for (int port : ports) {
                assertThat(accepts(port)).as("port %d accepts before destroy", port).isTrue();
            }
            WebServerApplicationContext context = mock(WebServerApplicationContext.class);
            when(context.getWebServer()).thenReturn(started.server());
            assertThat(started.customizer()).isInstanceOf(ApplicationContextAware.class);
            ((ApplicationContextAware) started.customizer()).setApplicationContext(context);

            assertTimeoutPreemptively(FAIL_FAST, () -> destroy(started.customizer()));

            for (int port : ports) {
                assertThat(accepts(port)).as("port %d accepts after destroy", port).isFalse();
            }
            assertThat(workers.get(0).awaitTermination(FAIL_FAST.toMillis(), TimeUnit.MILLISECONDS))
                    .as("worker terminated after destroy").isTrue();
        } finally {
            assertTimeoutPreemptively(FAIL_FAST, () -> stopAndDestroy(started));
        }
    }

    static Stream<Arguments> threadSettings() {
        String io = "server.undertow.threads.io";
        String worker = "server.undertow.threads.worker";
        return Stream.of(
                Arguments.of(Map.of(), DEFAULT_IO_THREADS, DEFAULT_WORKER_THREADS),
                Arguments.of(Map.of(io, "3"), 3, DEFAULT_WORKER_THREADS),
                Arguments.of(Map.of(worker, "5"), DEFAULT_IO_THREADS, 5),
                Arguments.of(Map.of(io, "3", worker, "5"), 3, 5));
    }

    /**
     * The started server runs on one worker, the one {@link ListenerPorts} created, with the I/O and worker threads
     * of Spring Boot's Undertow settings or Undertow's defaults; a second worker would be one Undertow created.
     */
    @ParameterizedTest(name = "{0} -> {1} I/O threads, {2} worker threads")
    @MethodSource("threadSettings")
    void serverRunsOnOneWorkerWithTheThreadsOfTheUndertowSettings(Map<String, String> settings, int ioThreads,
            int workerThreads) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        settings.forEach(environment::setProperty);
        Set<Thread> before = liveThreads();
        ExtraListenerServer started = startedServer(List.of(), environment);
        try {
            assertThat(newXnioThreads(before))
                    .filteredOn(thread -> XNIO_IO_THREAD.matcher(thread.getName()).matches())
                    .as("new I/O threads")
                    .hasSize(ioThreads);
            List<XnioWorker> workers = newWorkers(before);
            assertThat(workers).as("workers of the started server").hasSize(1);
            XnioWorker worker = workers.get(0);
            assertThat(worker.getIoThreadCount()).as("I/O threads").isEqualTo(ioThreads);
            assertThat(worker.getMXBean().getCoreWorkerPoolSize()).as("core worker threads").isEqualTo(workerThreads);
            assertThat(worker.getMXBean().getMaxWorkerPoolSize()).as("max worker threads").isEqualTo(workerThreads);
        } finally {
            stopAndDestroy(started);
        }
    }

    private PortRoutingFilter initialised(PortRoutingFilter routingFilter) throws ServletException {
        routingFilter.init(new MockFilterConfig(servletContext, "portRoutingFilter"));
        return routingFilter;
    }

    private MockHttpServletRequest request(int localPort, String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, method, path);
        request.setLocalPort(localPort);
        request.setServerPort(localPort);
        request.setServletPath(path);
        request.setPathInfo(null);
        request.setQueryString(query);
        return request;
    }

    private static ListenerPorts.Additional additional(String name, int port) {
        return new ListenerPorts.Additional(name, port, null, null, List.of());
    }

    /**
     * The customizer of one additional listener on {@code host} per port of {@code extraPorts}, in that order, named
     * {@code extra-1}, {@code extra-2} and so on.
     */
    private static WebServerFactoryCustomizer<UndertowServletWebServerFactory> extraListenerCustomizer(String host,
            List<Integer> extraPorts, List<String> disallowedMethods, Environment environment) {
        // The entries name no host, so they bind listeners.host.
        List<ListenerPorts.Additional> entries = IntStream.range(0, extraPorts.size())
                .mapToObj(index -> new ListenerPorts.Additional("extra-" + (index + 1), extraPorts.get(index), null,
                        null, List.of()))
                .toList();
        ListenerPorts.Properties properties = new ListenerPorts.Properties(host, new ListenerPorts.Main(List.of()),
                entries, disallowedMethods);
        return new ListenerPorts().additionalListenersCustomizer(properties, new DefaultResourceLoader(), environment);
    }

    /** {@code count} distinct ports, each free when drawn. */
    private static List<Integer> drawPorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) {
            ports.add(TestSocketUtils.findAvailableTcpPort());
        }
        return List.copyOf(ports);
    }

    /** A factory whose main listener binds {@code port} of {@code localhost}. */
    private static UndertowServletWebServerFactory localhostFactory(int port) throws UnknownHostException {
        UndertowServletWebServerFactory factory = new UndertowServletWebServerFactory(port);
        factory.setAddress(InetAddress.getByName(LOCALHOST));
        return factory;
    }

    /**
     * Creates, without starting it, a server on a random main port with {@code listeners} additional listeners on
     * drawn ports, drawing again when the probe finds a drawn port taken. A probe failure creates no worker.
     */
    private static ExtraListenerServer createdServer(int listeners, List<String> disallowedMethods,
            Environment environment) throws Exception {
        PortInUseException lost = null;
        for (int draw = 1; draw <= PORT_DRAWS; draw++) {
            List<Integer> extraPorts = drawPorts(listeners);
            WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer =
                    extraListenerCustomizer(LOCALHOST, extraPorts, disallowedMethods, environment);
            UndertowServletWebServerFactory factory = localhostFactory(0);
            customizer.customize(factory);
            try {
                return new ExtraListenerServer(customizer, factory.getWebServer(METHOD_ECHO), extraPorts);
            } catch (PortInUseException e) {
                destroy(customizer);
                lost = e;
            }
        }
        throw new AssertionError("Another process took a drawn extra port before the probe in every draw", lost);
    }

    /**
     * {@link #createdServer} with one additional listener, started, drawing again when another process takes the
     * extra port between the probe and Undertow's bind; the XNIO threads of a failed start have ended before the next
     * draw, so a case counting the threads that appeared sees only those of the server returned.
     */
    private static ExtraListenerServer startedServer(List<String> disallowedMethods, Environment environment)
            throws Exception {
        WebServerException lost = null;
        for (int draw = 1; draw <= PORT_DRAWS; draw++) {
            Set<Thread> beforeDraw = liveThreads();
            ExtraListenerServer created = createdServer(1, disallowedMethods, environment);
            try {
                created.server().start();
                return created;
            } catch (WebServerException e) {
                List<Thread> xnioThreads = newXnioThreads(beforeDraw);
                stopAndDestroy(created);
                awaitEnd(xnioThreads);
                if (!portInUse(e)) {
                    throw e;
                }
                lost = e;
            }
        }
        throw new AssertionError("Another process took the drawn extra port before Undertow bound it in every draw",
                lost);
    }

    /** Stops the server, then destroys the customizer, which stops every worker it created. */
    private static void stopAndDestroy(ExtraListenerServer created) throws Exception {
        try {
            created.server().stop();
        } finally {
            destroy(created.customizer());
        }
    }

    private static void destroy(WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer)
            throws Exception {
        assertThat(customizer).as("additional listeners customizer").isInstanceOf(DisposableBean.class);
        ((DisposableBean) customizer).destroy();
    }

    /** Whether {@code failure}'s cause chain shows a port in use, as Spring Boot recognises one. */
    private static boolean portInUse(Exception failure) {
        AtomicBoolean inUse = new AtomicBoolean();
        PortInUseException.ifCausedBy(failure, PortInUseException.class, portInUse -> inUse.set(true));
        PortInUseException.ifPortBindingException(failure, bindException -> inUse.set(true));
        return inUse.get();
    }

    /** Binds and holds {@code port} of {@code localhost}; {@code null} when another process took it first. */
    private static ServerSocket takeOrNull(int port) throws IOException {
        try {
            return new ServerSocket(port, 1, InetAddress.getByName(LOCALHOST));
        } catch (BindException e) {
            return null;
        }
    }

    /** Whether a listener on {@code localhost} accepts a connection on {@code port}. */
    private static boolean accepts(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(LOCALHOST), port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (ConnectException e) {
            return false;
        }
    }

    private static Set<Thread> liveThreads() {
        return Set.copyOf(Thread.getAllStackTraces().keySet());
    }

    /** The live threads of XNIO workers, I/O and accept threads, that are not in {@code before}. */
    private static List<Thread> newXnioThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread instanceof XnioIoThread && thread.isAlive() && !before.contains(thread))
                .toList();
    }

    /** The workers of {@link #newXnioThreads}, in no particular order. */
    private static List<XnioWorker> newWorkers(Set<Thread> before) {
        return newXnioThreads(before).stream()
                .map(thread -> ((XnioIoThread) thread).getWorker())
                .distinct()
                .toList();
    }

    /** Waits, within {@link #FAIL_FAST}, until every one of {@code threads} has ended. */
    private static void awaitEnd(List<Thread> threads) {
        assertTimeoutPreemptively(FAIL_FAST, () -> {
            for (Thread thread : threads) {
                thread.join();
            }
        }, () -> "threads still running: " + threads);
    }

    /** A plain entry followed by the TLS entry {@code secure} with {@code ssl}. */
    private static ListenerPorts.Properties tlsProperties(ListenerPorts.Ssl ssl) {
        return new ListenerPorts.Properties(null, null, List.of(additional("plain", EXTRA),
                new ListenerPorts.Additional("secure", EXTRA + 1, null, ssl, List.of())), null);
    }

    /**
     * Writes a store of {@code type} saved with {@code storePassword} into {@link #storeDir} and returns its location.
     * A non-null {@code keyPassword} adds one secret key protected by it, which the key manager opens with the key
     * password when the TLS context is built.
     */
    private String writeStore(String file, String type, String storePassword, String keyPassword)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        if (keyPassword != null) {
            store.setEntry("key", new KeyStore.SecretKeyEntry(new SecretKeySpec(new byte[16], "AES")),
                    new KeyStore.PasswordProtection(keyPassword.toCharArray()));
        }
        Path location = storeDir.resolve(file);
        try (OutputStream out = Files.newOutputStream(location)) {
            store.store(out, storePassword.toCharArray());
        }
        return location.toString();
    }

    /**
     * A server created with one additional listener per port of {@code extraPorts}, and the customizer that added them
     * and owns the server's worker.
     */
    private record ExtraListenerServer(WebServerFactoryCustomizer<UndertowServletWebServerFactory> customizer,
            WebServer server, List<Integer> extraPorts) {

        /** Port of the first additional listener, {@code extra-1}. */
        int extraPort() {
            return extraPorts.get(0);
        }
    }

    /** Answers every method with {@code ok:<method>}, so a response proves which method reached the servlet. */
    private static final class MethodEchoServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse servletResponse) throws IOException {
            servletResponse.setStatus(HttpServletResponse.SC_OK);
            servletResponse.setContentType("text/plain");
            servletResponse.getWriter().write("ok:" + request.getMethod());
        }
    }
}
