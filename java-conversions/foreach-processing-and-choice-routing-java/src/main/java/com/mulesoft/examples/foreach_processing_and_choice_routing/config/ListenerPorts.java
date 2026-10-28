package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
     * @throws IllegalStateException naming the entry when a port is invalid or repeated, or a TLS context cannot be
     *                               built
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> additionalListenersCustomizer(
            Properties properties, ResourceLoader resourceLoader, Environment environment) {
        List<ListenerSpec> specs = listenerSpecs(properties, resourceLoader, environment);
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
