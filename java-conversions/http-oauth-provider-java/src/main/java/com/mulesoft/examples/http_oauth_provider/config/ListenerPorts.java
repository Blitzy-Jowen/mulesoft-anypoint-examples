package com.mulesoft.examples.http_oauth_provider.config;

import io.undertow.Undertow;
import io.undertow.server.handlers.DisallowedMethodsHandler;
import io.undertow.util.HttpString;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

/**
 * Serves several HTTP listeners from one application, each answering only for its own routes, as a Mule application
 * with several {@code http:listener-config} elements did.
 *
 * <p>The main listener is the one Spring Boot opens from {@code server.port} and {@code server.address}. Every entry
 * of {@code listeners.additional} adds one more Undertow listener to the same server, so all listeners share the
 * servlet deployment. {@link PortRoutingFilter}, registered here and never as a component, then scopes each request
 * to the routes of the listener whose local port received it: a path outside those routes gets the no-listener 404,
 * and a route naming another servlet is forwarded to that servlet. Requests on any port that is not an additional
 * listener are judged against {@code listeners.main.routes}, so tests on a random main port need no extra settings.
 *
 * <pre>
 * listeners:
 *   host: 0.0.0.0                   # default host of the additional listeners
 *   disallowed-methods: []          # empty: every method is accepted
 *   main:
 *     routes:
 *       - servlet: dispatcherServlet
 *         patterns: ["/**"]
 *   additional:
 *     - name: admin
 *       port: 9443
 *       ssl:
 *         key-store: classpath:keystore.jks
 *         key-store-password: ${KEYSTORE_PASSWORD:}
 *       routes:
 *         - patterns: ["/admin/**"]
 * </pre>
 *
 * <p>Additional ports must be concrete, between 1 and 65535 and unique: {@link PortRoutingFilter} identifies the
 * listener by {@code ServletRequest.getLocalPort()}, which a listener bound to port 0 would not reproduce.
 *
 * <p>Start-up failure: an additional listener that cannot be bound ends the start-up and never hangs it. Before the
 * listeners are added, a probe socket binds each one's host and port and releases it, so a port another socket holds
 * stops the application with Spring Boot's {@link PortInUseException}, reported as "Web server failed to start. Port
 * N was already in use.", and any other bind failure with an {@link IllegalStateException} naming the entry, host
 * and port. A port taken between the probe and Undertow's own bind fails Undertow's start, and that failure reaches
 * the caller too, because the server runs on an XNIO worker created here rather than by Undertow: when a bind fails
 * on the worker Undertow created itself, Undertow shuts that worker down at once, and Spring Boot's clean-up then
 * closes the listeners already bound on its dead I/O threads, which waits forever. Undertow never shuts down a worker
 * it was given, so this one outlives a failed start, and it is stopped when the application context closes, after
 * the web server. It has the threads and options of the worker Undertow would create.
 *
 * <p>HTTPS: an entry with {@code ssl} opens a TLS listener over its key store. When the entry also names a trust
 * store, the listener requires a client certificate that the trust store accepts, because a Mule listener TLS
 * context requires one whenever it has a trust store ({@code needClientAuth = isTrustStoreConfigured()} in Mule
 * 3.8's {@code GrizzlyServerManager}); without a trust store no client certificate is requested. A store or key that
 * cannot be opened stops the application at start-up; when the password it was opened with is empty, the message
 * names the setting to supply rather than the JDK's reason (see {@link #additionalListenersCustomizer}).
 *
 * <p>Method restriction: Undertow 2.3's {@code DeploymentInfo} offers no disallowed-request-methods setting, and the
 * embedded server rejects no method by default, TRACE included. A Mule listener without {@code allowedMethods}
 * accepts every method, so nothing is restricted unless {@code listeners.disallowed-methods} lists methods. Those are
 * then rejected for every listener by Undertow's {@link DisallowedMethodsHandler}, installed as an initial handler
 * chain wrapper: it answers 405 with an empty body before any servlet or filter runs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ListenerPorts.Properties.class)
public class ListenerPorts {

    private static final Logger LOG = LoggerFactory.getLogger(ListenerPorts.class);

    /** Host of an additional listener when neither the entry nor {@code listeners.host} names one. */
    public static final String DEFAULT_HOST = "0.0.0.0";

    /** Store type used when {@code key-store-type} is not set. */
    public static final String DEFAULT_KEY_STORE_TYPE = "JKS";

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** Settings Spring Boot reads for the I/O and worker threads of the embedded Undertow server. */
    private static final String IO_THREADS_PROPERTY = "server.undertow.threads.io";
    private static final String WORKER_THREADS_PROPERTY = "server.undertow.threads.worker";

    /** Undertow's defaults: as many I/O threads as processors, at least two, and eight worker threads per I/O thread. */
    private static final int MIN_IO_THREADS = 2;
    private static final int WORKER_THREADS_PER_IO_THREAD = 8;

    /** Connection high and low water of the worker Undertow creates for itself. */
    private static final int CONNECTION_WATER_MARK = 1_000_000;

    /** {@code ssl} settings holding the key password and the store password it falls back to. */
    private static final String KEY_PASSWORD = "key-password";
    private static final String KEY_STORE_PASSWORD = "key-store-password";

    /**
     * A configured value that is one placeholder and nothing else, {@code ${name}} or {@code ${name:default}}; group 1
     * is the key it reads. Compound values and nested placeholders do not match.
     */
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("\\$\\{([^${}:\\s]+)(?::[^${}]*)?\\}");

    /**
     * The {@code listeners} configuration block. Every component is optional: an absent block means no additional
     * listener, pass-through routing on the main listener and no disallowed method.
     *
     * @param host              default host of the additional listeners
     * @param main              routes of the main listener
     * @param additional        listeners opened next to the main one
     * @param disallowedMethods HTTP methods rejected with 405 on every listener; empty accepts every method
     */
    @ConfigurationProperties("listeners")
    public record Properties(String host, Main main, List<Additional> additional, List<String> disallowedMethods) {

        public Properties {
            main = main == null ? new Main(null) : main;
            additional = immutable(additional);
            disallowedMethods = immutable(disallowedMethods);
        }
    }

    /**
     * The main listener, opened by Spring Boot from {@code server.port} and {@code server.address}.
     *
     * @param routes routes it serves; empty serves every path
     */
    public record Main(List<Route> routes) {

        public Main {
            routes = immutable(routes);
        }
    }

    /**
     * One listener opened next to the main one.
     *
     * @param name   label used in log lines and error messages
     * @param port   concrete port, 1 to 65535, unique among the additional listeners
     * @param host   host to bind; see {@link ListenerPorts#effectiveHost(Additional, Properties)}
     * @param ssl    TLS settings, or {@code null} for plain HTTP
     * @param routes routes it serves; empty serves every path
     */
    public record Additional(String name, int port, String host, Ssl ssl, List<Route> routes) {

        public Additional {
            routes = immutable(routes);
        }
    }

    /**
     * Paths a listener serves and the servlet that serves them.
     *
     * @param servlet  servlet registration name, such as {@code dispatcherServlet} or {@code messageDispatcherServlet};
     *                 {@code null} or blank keeps the servlet the container mapped
     * @param patterns {@code AntPathMatcher} patterns over the path within the application
     */
    public record Route(String servlet, List<String> patterns) {

        public Route {
            patterns = immutable(patterns);
        }
    }

    /**
     * TLS settings of an HTTPS listener. Store locations are Spring resource locations ({@code classpath:},
     * {@code file:}); a location without a prefix is read from the file system when such a file exists and from
     * the classpath otherwise. Both stores use {@code keyStoreType}. No password appears in {@link #toString()} or in
     * a start-up failure message.
     *
     * @param keyStore           location of the store holding the server key; required
     * @param keyStorePassword   password of the key store
     * @param keyPassword        password of the key entry; empty falls back to {@code keyStorePassword}
     * @param keyStoreType       store type; empty means {@value ListenerPorts#DEFAULT_KEY_STORE_TYPE}
     * @param trustStore         location of the store of accepted client certificates; when set, a client
     *                           certificate is required
     * @param trustStorePassword password of the trust store
     */
    public record Ssl(String keyStore, String keyStorePassword, String keyPassword, String keyStoreType,
            String trustStore, String trustStorePassword) {

        /** Leaves every password out, so the settings can be logged. */
        @Override
        public String toString() {
            return "Ssl[keyStore=" + keyStore + ", keyStoreType=" + keyStoreType + ", trustStore=" + trustStore
                    + ", passwords=<hidden>]";
        }
    }

    /** An additional listener resolved and ready to be added to the Undertow builder. */
    private record ListenerSpec(String label, int port, String host, SSLContext sslContext,
            boolean clientAuthRequired) {
    }

    /**
     * Host an additional listener binds: its own {@code host}, else {@code listeners.host}, else
     * {@value #DEFAULT_HOST}.
     */
    public static String effectiveHost(Additional entry, Properties properties) {
        if (entry != null && StringUtils.hasText(entry.host())) {
            return entry.host().trim();
        }
        if (properties != null && StringUtils.hasText(properties.host())) {
            return properties.host().trim();
        }
        return DEFAULT_HOST;
    }

    /**
     * Adds the additional listeners to the Undertow server and, when {@code listeners.disallowed-methods} is not
     * empty, the handler that rejects those methods. Key and trust stores are read here, so a listener whose TLS
     * context cannot be built stops the application at start-up.
     *
     * <p>The failure message gives the JDK's reason, except when the step that failed used an empty password: opening
     * the key store with {@code key-store-password}, the key with {@code key-password} (or the
     * {@code key-store-password} it falls back to, both then named) or the trust store with
     * {@code trust-store-password}. The message then names each setting to supply as
     * {@code listeners.additional[<index>].ssl.<setting>}, preceded by the key it reads when its configured value in
     * {@code environment} is a single {@code ${key}} or {@code ${key:default}} placeholder. A store or key that opens
     * with an empty password is used as before, and no password or configured value is put in the message.
     *
     * <p>When the server is created, and unless its port is negative (Spring Boot then never starts it), each
     * additional listener's host and port is probed with a socket bound and released before the listeners are added,
     * and the server is given an XNIO worker created here, with the I/O and worker threads of
     * {@code server.undertow.threads.io} and {@code server.undertow.threads.worker} or Undertow's defaults. A port
     * that is in use then ends the start-up with {@link PortInUseException}, and any other failure to bind a
     * listener, in the probe or in Undertow's own start, ends it instead of hanging it (see the class description).
     * The returned customizer is a {@link DisposableBean}: when the context closes it stops the context's web server,
     * if one is still running, and then every worker it created.
     *
     * @return the customizer that adds the additional listeners and the method restriction to the Undertow server
     * @throws IllegalStateException naming the entry when a port is invalid or repeated, or a TLS context cannot be
     *                               built
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> additionalListenersCustomizer(
            Properties properties, ResourceLoader resourceLoader, Environment environment) {
        List<ListenerSpec> specs = listenerSpecs(properties, resourceLoader, environment);
        HttpString[] disallowed = disallowedMethods(properties);
        return new AdditionalListenersCustomizer(specs, disallowed, environment);
    }

    /**
     * Registers {@link PortRoutingFilter} ahead of every other filter, for request dispatches only, so a forward it
     * issues is not routed a second time.
     *
     * @throws IllegalStateException naming the entry when a port is invalid or repeated
     */
    @Bean
    public FilterRegistrationBean<PortRoutingFilter> portRoutingFilterRegistration(Properties properties) {
        Map<Integer, List<Route>> additionalRoutesByPort = new LinkedHashMap<>();
        for (Additional entry : validatedAdditional(properties)) {
            additionalRoutesByPort.put(entry.port(), entry.routes());
        }
        FilterRegistrationBean<PortRoutingFilter> registration = new FilterRegistrationBean<>(
                new PortRoutingFilter(properties.main().routes(), Collections.unmodifiableMap(additionalRoutesByPort)));
        registration.setName("portRoutingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    /**
     * Adds the additional listeners and the method restriction to every Undertow server it customizes, probes the
     * listeners' ports and gives each server that will start an XNIO worker of its own, and stops those workers when
     * destroyed.
     */
    private static final class AdditionalListenersCustomizer
            implements WebServerFactoryCustomizer<UndertowServletWebServerFactory>, ApplicationContextAware,
            DisposableBean {

        private final List<ListenerSpec> specs;

        private final HttpString[] disallowed;

        private final int ioThreads;

        private final int workerThreads;

        private final List<XnioWorker> workers = new CopyOnWriteArrayList<>();

        private ApplicationContext applicationContext;

        AdditionalListenersCustomizer(List<ListenerSpec> specs, HttpString[] disallowed, Environment environment) {
            this.specs = specs;
            this.disallowed = disallowed;
            int defaultIoThreads = Math.max(Runtime.getRuntime().availableProcessors(), MIN_IO_THREADS);
            Integer io = environment.getProperty(IO_THREADS_PROPERTY, Integer.class);
            Integer worker = environment.getProperty(WORKER_THREADS_PROPERTY, Integer.class);
            this.ioThreads = io != null ? io : defaultIoThreads;
            this.workerThreads = worker != null ? worker : defaultIoThreads * WORKER_THREADS_PER_IO_THREAD;
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        /**
         * Registers the builder customizer for the additional listeners and the deployment customizer for the method
         * restriction. The builder customizer runs when the server is created and reads the port it is created with:
         * Spring Boot never starts a server whose port is negative, so such a server only gets the listeners, with no
         * probe and no worker.
         *
         * @throws PortInUseException    when the server is created, if another socket holds an additional port
         * @throws IllegalStateException when the server is created, if an additional listener cannot be bound for
         *                               another reason, naming the entry, host and port
         * @throws UncheckedIOException  when the server is created, if XNIO cannot create the worker
         */
        @Override
        public void customize(UndertowServletWebServerFactory factory) {
            if (!specs.isEmpty()) {
                factory.addBuilderCustomizers(builder -> {
                    boolean willStart = factory.getPort() >= 0;
                    if (willStart) {
                        specs.forEach(ListenerPorts::requireBindable);
                    }
                    specs.forEach(spec -> addListener(builder, spec));
                    if (willStart) {
                        builder.setWorker(newWorker());
                    }
                });
            }
            if (disallowed.length > 0) {
                factory.addDeploymentInfoCustomizers(deploymentInfo -> deploymentInfo.addInitialHandlerChainWrapper(
                        next -> new DisallowedMethodsHandler(next, disallowed)));
            }
        }

        /**
         * Stops the context's web server, then every worker created here, the way Undertow stops its own worker under
         * the zero shutdown timeout Spring Boot sets: shut down, and shut down at once unless already terminated.
         *
         * <p>The web server has normally stopped by the time the context destroys its beans, and stopping it again does
         * nothing. A refresh that fails after the server started destroys the beans first and stops the server only
         * afterwards; stopping it here keeps a listener from being closed on a worker that is no longer running, which
         * would wait forever. The workers are stopped even when stopping the server fails, whose failure then
         * propagates, so no worker thread outlives the context.
         */
        @Override
        public void destroy() {
            try {
                if (applicationContext instanceof WebServerApplicationContext webContext) {
                    WebServer webServer = webContext.getWebServer();
                    if (webServer != null) {
                        webServer.stop();
                    }
                }
            } finally {
                for (XnioWorker worker : workers) {
                    worker.shutdown();
                    try {
                        if (!worker.awaitTermination(0, TimeUnit.MILLISECONDS)) {
                            worker.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        worker.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
                workers.clear();
            }
        }

        /**
         * A worker with the option map Undertow gives the worker it would otherwise create in {@code start()}.
         *
         * @throws UncheckedIOException if XNIO cannot create the worker
         */
        private XnioWorker newWorker() {
            OptionMap options = OptionMap.builder()
                    .set(Options.WORKER_IO_THREADS, ioThreads)
                    .set(Options.CONNECTION_HIGH_WATER, CONNECTION_WATER_MARK)
                    .set(Options.CONNECTION_LOW_WATER, CONNECTION_WATER_MARK)
                    .set(Options.WORKER_TASK_CORE_THREADS, workerThreads)
                    .set(Options.WORKER_TASK_MAX_THREADS, workerThreads)
                    .set(Options.TCP_NODELAY, true)
                    .set(Options.CORK, true)
                    .getMap();
            XnioWorker worker;
            try {
                worker = Xnio.getInstance(Undertow.class.getClassLoader()).createWorker(options);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create the XNIO worker of the additional listeners", e);
            }
            workers.add(worker);
            return worker;
        }
    }

    private static List<ListenerSpec> listenerSpecs(Properties properties, ResourceLoader resourceLoader,
            Environment environment) {
        List<Additional> entries = validatedAdditional(properties);
        List<ListenerSpec> specs = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            Additional entry = entries.get(index);
            String label = describe(index, entry);
            Ssl ssl = entry.ssl();
            SSLContext sslContext = ssl == null ? null
                    : sslContext(label, position(index) + ".ssl.", ssl, resourceLoader, environment);
            // No client-auth setting exists: a trust store means a required client certificate, the Mule listener rule.
            boolean clientAuthRequired = ssl != null && StringUtils.hasText(ssl.trustStore());
            specs.add(new ListenerSpec(label, entry.port(), effectiveHost(entry, properties), sslContext,
                    clientAuthRequired));
        }
        return List.copyOf(specs);
    }

    /** The additional entries after checking that every port is in range and used once. */
    private static List<Additional> validatedAdditional(Properties properties) {
        List<Additional> entries = properties.additional();
        Map<Integer, String> ownerByPort = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Additional entry = entries.get(index);
            String label = describe(index, entry);
            if (entry.port() < MIN_PORT || entry.port() > MAX_PORT) {
                throw new IllegalStateException(label + ": port " + entry.port() + " is outside " + MIN_PORT + ".."
                        + MAX_PORT + "; additional listeners need a concrete port");
            }
            String owner = ownerByPort.putIfAbsent(entry.port(), label);
            if (owner != null) {
                throw new IllegalStateException(label + ": port " + entry.port() + " is already used by " + owner);
            }
        }
        return entries;
    }

    private static String describe(int index, Additional entry) {
        String position = position(index);
        return StringUtils.hasText(entry.name()) ? entry.name().trim() + " (" + position + ")" : position;
    }

    /** Property path of the entry at {@code index}. */
    private static String position(int index) {
        return "listeners.additional[" + index + "]";
    }

    private static HttpString[] disallowedMethods(Properties properties) {
        return properties.disallowedMethods().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(HttpString::tryFromString)
                .toArray(HttpString[]::new);
    }

    /**
     * Binds a probe socket to the listener's host and port, the address Undertow binds, with the address reuse
     * Undertow's listeners use, and releases it again.
     *
     * @throws PortInUseException    if another socket holds the port
     * @throws IllegalStateException if the address cannot be bound for another reason, such as a host that does not
     *                               resolve or is not local, naming the entry, host and port
     */
    private static void requireBindable(ListenerSpec spec) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getByName(spec.host()), spec.port()), 1);
        } catch (IOException e) {
            PortInUseException.throwIfPortBindingException(e, spec::port);
            throw new IllegalStateException(spec.label() + ": cannot bind " + spec.host() + ":" + spec.port() + ": "
                    + e.getMessage(), e);
        }
    }

    private static void addListener(Undertow.Builder builder, ListenerSpec spec) {
        if (spec.sslContext() == null) {
            builder.addHttpListener(spec.port(), spec.host());
        } else if (spec.clientAuthRequired()) {
            builder.addListener(new Undertow.ListenerBuilder()
                    .setType(Undertow.ListenerType.HTTPS)
                    .setPort(spec.port())
                    .setHost(spec.host())
                    .setSslContext(spec.sslContext())
                    .setOverrideSocketOptions(
                            OptionMap.create(Options.SSL_CLIENT_AUTH_MODE, SslClientAuthMode.REQUIRED)));
        } else {
            builder.addHttpsListener(spec.port(), spec.host(), spec.sslContext());
        }
        LOG.info("Additional listener {} on {}:{} ({})", spec.label(), spec.host(), spec.port(),
                spec.sslContext() == null ? "http" : "https");
    }

    /**
     * Builds the TLS context of one entry; {@code settingPrefix} is its {@code listeners.additional[<index>].ssl.}
     * path, which a failure caused by an empty password names.
     */
    private static SSLContext sslContext(String label, String settingPrefix, Ssl ssl, ResourceLoader resourceLoader,
            Environment environment) {
        if (!StringUtils.hasText(ssl.keyStore())) {
            throw new IllegalStateException(label + ": ssl.key-store is required for an HTTPS listener");
        }
        String type = StringUtils.hasText(ssl.keyStoreType()) ? ssl.keyStoreType().trim() : DEFAULT_KEY_STORE_TYPE;
        try {
            KeyStore keyStore = loadStore(label, settingPrefix, "key-store", ssl.keyStore(), type,
                    ssl.keyStorePassword(), resourceLoader, environment);
            String keyPassword = StringUtils.hasLength(ssl.keyPassword()) ? ssl.keyPassword() : ssl.keyStorePassword();
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            try {
                keyManagerFactory.init(keyStore, toChars(keyPassword));
            } catch (GeneralSecurityException e) {
                if (StringUtils.hasLength(keyPassword)) {
                    throw e;
                }
                // Empty only when key-password and the key-store-password it falls back to are both empty.
                throw emptyPasswordFailure(label, "key", e, environment, settingPrefix + KEY_PASSWORD,
                        settingPrefix + KEY_STORE_PASSWORD);
            }

            TrustManager[] trustManagers = null;
            if (StringUtils.hasText(ssl.trustStore())) {
                KeyStore trustStore = loadStore(label, settingPrefix, "trust-store", ssl.trustStore(), type,
                        ssl.trustStorePassword(), resourceLoader, environment);
                TrustManagerFactory trustManagerFactory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(trustStore);
                trustManagers = trustManagerFactory.getTrustManagers();
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), trustManagers, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(label + ": cannot build the TLS context: " + e.getMessage(), e);
        }
    }

    /**
     * Opens the store of {@code role} ({@code key-store} or {@code trust-store}) with its password, read from the
     * setting {@code <role>-password}.
     */
    private static KeyStore loadStore(String label, String settingPrefix, String role, String location, String type,
            String password, ResourceLoader resourceLoader, Environment environment)
            throws GeneralSecurityException, IOException {
        Resource resource = resolve(location.trim(), resourceLoader);
        if (!resource.exists()) {
            throw new IllegalStateException(label + ": ssl." + role + " " + location + " does not exist");
        }
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = resource.getInputStream()) {
            try {
                store.load(in, toChars(password));
            } catch (GeneralSecurityException | IOException e) {
                if (StringUtils.hasLength(password)) {
                    throw e;
                }
                throw emptyPasswordFailure(label, role.replace('-', ' '), e, environment,
                        settingPrefix + role + "-password");
            }
        }
        return store;
    }

    /**
     * The start-up failure of a store or key that did not open while its password was empty. It names each setting
     * the password may come from, preceded by the key that setting reads when it is a single placeholder, and never
     * a password or a configured value.
     *
     * @param label       the entry, as the other start-up failures name it
     * @param subject     what did not open: {@code key store}, {@code key} or {@code trust store}
     * @param cause       the JDK failure, kept as the cause
     * @param environment where the configured values of the settings are looked up
     * @param settings    full property names of the settings, in the order the message offers them
     */
    private static IllegalStateException emptyPasswordFailure(String label, String subject, Exception cause,
            Environment environment, String... settings) {
        List<String> names = new ArrayList<>(settings.length);
        for (String setting : settings) {
            String key = placeholderKey(environment, setting);
            names.add(key == null ? setting : key + " (" + setting + ")");
        }
        return new IllegalStateException(label + ": cannot build the TLS context: the " + subject
                + " password is empty; supply " + String.join(" or ", names), cause);
    }

    /**
     * The key that {@code property}'s configured value reads when that value is a single placeholder, unresolved
     * from the first property source holding the property; {@code null} for any other value, an absent property or
     * an environment whose property sources cannot be listed.
     */
    private static String placeholderKey(Environment environment, String property) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return null;
        }
        ConfigurationPropertyName name = ConfigurationPropertyName.of(property);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty configured = source.getConfigurationProperty(name);
            if (configured != null) {
                Matcher placeholder = SINGLE_PLACEHOLDER.matcher(String.valueOf(configured.getValue()));
                return placeholder.matches() ? placeholder.group(1) : null;
            }
        }
        return null;
    }

    /**
     * Resolves a store location. A location without a prefix is not handed to the loader as is, because a web
     * application context resolves such a path against the servlet context once one exists and against the
     * classpath before; it is read from the file system when such a file exists and from the classpath otherwise.
     */
    private static Resource resolve(String location, ResourceLoader resourceLoader) {
        if (ResourceUtils.isUrl(location)) {
            return resourceLoader.getResource(location);
        }
        Resource file = resourceLoader.getResource(ResourceUtils.FILE_URL_PREFIX + location);
        return file.exists() ? file : resourceLoader.getResource(ResourceLoader.CLASSPATH_URL_PREFIX + location);
    }

    private static char[] toChars(String password) {
        return password == null ? null : password.toCharArray();
    }

    /** Null-tolerant unmodifiable copy: a missing list is empty and missing elements are dropped. */
    private static <T> List<T> immutable(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
