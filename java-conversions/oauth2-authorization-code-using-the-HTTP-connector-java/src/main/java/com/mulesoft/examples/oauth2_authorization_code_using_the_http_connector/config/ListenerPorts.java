package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import io.undertow.Undertow;
import io.undertow.server.handlers.DisallowedMethodsHandler;
import io.undertow.util.HttpString;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;

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
 * <p>HTTPS: an entry with {@code ssl} opens a TLS listener over its key store. When the entry also names a trust
 * store, the listener requires a client certificate that the trust store accepts, because a Mule listener TLS
 * context requires one whenever it has a trust store ({@code needClientAuth = isTrustStoreConfigured()} in Mule
 * 3.8's {@code GrizzlyServerManager}); without a trust store no client certificate is requested.
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
     * the classpath otherwise. Both stores use {@code keyStoreType}.
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
     * @throws IllegalStateException naming the entry when a port is invalid or repeated, or a TLS context cannot be
     *                               built
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> additionalListenersCustomizer(
            Properties properties, ResourceLoader resourceLoader) {
        List<ListenerSpec> specs = listenerSpecs(properties, resourceLoader);
        HttpString[] disallowed = disallowedMethods(properties);
        return factory -> {
            if (!specs.isEmpty()) {
                factory.addBuilderCustomizers(builder -> specs.forEach(spec -> addListener(builder, spec)));
            }
            if (disallowed.length > 0) {
                factory.addDeploymentInfoCustomizers(deploymentInfo -> deploymentInfo.addInitialHandlerChainWrapper(
                        next -> new DisallowedMethodsHandler(next, disallowed)));
            }
        };
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

    private static List<ListenerSpec> listenerSpecs(Properties properties, ResourceLoader resourceLoader) {
        List<Additional> entries = validatedAdditional(properties);
        List<ListenerSpec> specs = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            Additional entry = entries.get(index);
            String label = describe(index, entry);
            Ssl ssl = entry.ssl();
            SSLContext sslContext = ssl == null ? null : sslContext(label, ssl, resourceLoader);
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
        String position = "listeners.additional[" + index + "]";
        return StringUtils.hasText(entry.name()) ? entry.name().trim() + " (" + position + ")" : position;
    }

    private static HttpString[] disallowedMethods(Properties properties) {
        return properties.disallowedMethods().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(HttpString::tryFromString)
                .toArray(HttpString[]::new);
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

    private static SSLContext sslContext(String label, Ssl ssl, ResourceLoader resourceLoader) {
        if (!StringUtils.hasText(ssl.keyStore())) {
            throw new IllegalStateException(label + ": ssl.key-store is required for an HTTPS listener");
        }
        String type = StringUtils.hasText(ssl.keyStoreType()) ? ssl.keyStoreType().trim() : DEFAULT_KEY_STORE_TYPE;
        try {
            KeyStore keyStore = loadStore(label, "key-store", ssl.keyStore(), type, ssl.keyStorePassword(),
                    resourceLoader);
            String keyPassword = StringUtils.hasLength(ssl.keyPassword()) ? ssl.keyPassword() : ssl.keyStorePassword();
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, toChars(keyPassword));

            TrustManager[] trustManagers = null;
            if (StringUtils.hasText(ssl.trustStore())) {
                KeyStore trustStore = loadStore(label, "trust-store", ssl.trustStore(), type,
                        ssl.trustStorePassword(), resourceLoader);
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

    private static KeyStore loadStore(String label, String role, String location, String type, String password,
            ResourceLoader resourceLoader) throws GeneralSecurityException, IOException {
        Resource resource = resolve(location.trim(), resourceLoader);
        if (!resource.exists()) {
            throw new IllegalStateException(label + ": ssl." + role + " " + location + " does not exist");
        }
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = resource.getInputStream()) {
            store.load(in, toChars(password));
        }
        return store;
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
