package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import com.mulesoft.examples.get_customer_list_from_netsuite.config.NetsuiteProperties;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

/**
 * The NetSuite REST client of this project (shared component SC-16, together with {@link NetsuiteTokenProvider},
 * {@code NetsuiteFailureClassifier} and {@link NetsuiteProperties}), through which the conversion reaches the NetSuite
 * REST API and SuiteQL in place of the Mule flow's SuiteTalk connector. It provides the two pieces every NetSuite call
 * shares: the HTTP transport and the quoting of values placed in SuiteQL string literals.
 *
 * <p>{@link #connector(NetsuiteProperties)} builds the Reactor Netty transport of every NetSuite {@code WebClient}, the
 * token request of {@link NetsuiteTokenProvider} included, with the connect timeout {@code netsuite.connect-timeout}
 * and the response timeout {@code netsuite.read-timeout} (10 s each, the Mule default response timeout). The two
 * timeouts surface as follows, each wrapped by {@code WebClient} in a {@code WebClientRequestException}:
 * <ul>
 *   <li>no connection within the connect timeout raises Netty's {@code io.netty.channel.ConnectTimeoutException},
 *       a {@code java.net.ConnectException};</li>
 *   <li>no response within the response timeout raises Netty's
 *       {@code io.netty.handler.timeout.ReadTimeoutException}.</li>
 * </ul>
 * Both causes are in the unavailable list of the SC-9 classification, so the cause walk of
 * {@code NetsuiteFailureClassifier} classifies either failure as {@code UNAVAILABLE} and {@code UpstreamCallExecutor}
 * answers it with no retry.
 *
 * <p>{@link #suiteQlLiteral(String)} escapes a caller-supplied value for use inside a SuiteQL string literal. SuiteQL
 * follows Oracle literal rules, under which a single quote is the only character that can end a literal and is
 * written doubled. Backslash has no escaping role there, so it is left as it is, and so are {@code %} and {@code _},
 * which keep their wildcard meaning where the literal is a {@code LIKE} pattern. Doubling the quote is therefore all
 * that keeps a value from closing the literal, and the literal then denotes exactly the value given.
 */
public class NetsuiteRestClient {

    /** Connect and response timeout used when a setting is absent: the Mule default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Netty takes the connect timeout as an {@code int} number of milliseconds. */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** Reactor Netty converts the response timeout to a {@code long} number of milliseconds. */
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);

    private static final String CONNECT_TIMEOUT_KEY = "netsuite.connect-timeout";

    private static final String READ_TIMEOUT_KEY = "netsuite.read-timeout";

    /**
     * Builds the HTTP transport of a NetSuite {@code WebClient}: a Reactor Netty client whose connect timeout is
     * {@code netsuite.connect-timeout} and whose response timeout is {@code netsuite.read-timeout}, each 10 s when
     * the setting returns {@code null}. A zero duration disables that timeout, as it does in Netty. The settings are
     * read once, when the transport is built.
     *
     * <p>A connect timeout that expires raises {@code io.netty.channel.ConnectTimeoutException} (a
     * {@code java.net.ConnectException}); a response timeout that expires raises
     * {@code io.netty.handler.timeout.ReadTimeoutException}. {@code WebClient} wraps either in a
     * {@code WebClientRequestException}, which the SC-9 cause walk classifies as {@code UNAVAILABLE}.
     *
     * @param props the {@code netsuite.*} settings
     * @return a new {@link ReactorClientHttpConnector} for {@code WebClient.Builder#clientConnector}
     * @throws NullPointerException  when {@code props} is {@code null}
     * @throws IllegalStateException when a timeout is negative, or when the connect timeout exceeds
     *         {@link Integer#MAX_VALUE} milliseconds or the read timeout {@link Long#MAX_VALUE} milliseconds; the
     *         message names the key
     */
    public static ClientHttpConnector connector(NetsuiteProperties props) {
        Objects.requireNonNull(props, "props");
        Duration connect = timeout(props.getConnectTimeout(), CONNECT_TIMEOUT_KEY, MAX_CONNECT_TIMEOUT);
        Duration read = timeout(props.getReadTimeout(), READ_TIMEOUT_KEY, MAX_READ_TIMEOUT);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connect.toMillis())
                .responseTimeout(read);
        return new ReactorClientHttpConnector(httpClient);
    }

    /**
     * Escapes a value for use inside a SuiteQL string literal by doubling every single quote, so
     * {@code O'Brien} becomes {@code O''Brien}. The result carries no surrounding quotes; the caller writes them, for
     * example {@code "... WHERE lastname LIKE '" + suiteQlLiteral(lastName) + "%'"}. Backslash, {@code %} and
     * {@code _} are returned unchanged, because under SuiteQL's Oracle literal rules none of them can end the literal.
     *
     * @param value the value to place inside the literal
     * @return the value with each {@code '} written as {@code ''}
     * @throws IllegalArgumentException when {@code value} is {@code null}
     */
    public static String suiteQlLiteral(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SuiteQL literal value must not be null");
        }
        return value.replace("'", "''");
    }

    private static Duration timeout(Duration configured, String key, Duration max) {
        Duration timeout = configured != null ? configured : DEFAULT_TIMEOUT;
        if (timeout.isNegative()) {
            throw new IllegalStateException(key + " must not be negative");
        }
        if (timeout.compareTo(max) > 0) {
            throw new IllegalStateException(key + " must not exceed " + max.toMillis() + " ms");
        }
        return timeout;
    }
}
