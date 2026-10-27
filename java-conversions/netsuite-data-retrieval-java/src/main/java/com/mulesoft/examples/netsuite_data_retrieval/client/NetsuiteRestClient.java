package com.mulesoft.examples.netsuite_data_retrieval.client;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;

/**
 * The NetSuite REST client (shared component SC-16, together with {@link NetsuiteTokenProvider},
 * {@link NetsuiteFailureClassifier} and {@link NetsuiteProperties}), through which the conversion reaches the NetSuite
 * REST API and SuiteQL in place of the Mule flow's SuiteTalk connector: it replaces the
 * {@code <netsuite:query-records query="dsql:SELECT email,firstName,lastName FROM CUSTOMER ...">} operation and, with
 * the token provider, the SuiteTalk login of {@code <netsuite:config account email password roleId/>}.
 *
 * <p>This project holds the reference copy; {@code netsuite-data-retrieval-java} carries an identical copy in which
 * only the {@code package} line and the import prefix differ. This project calls only {@link #suiteQl(String)}; the
 * record API, the metadata catalog and the custom-field source lookup serve that copy's record-shape mapping.
 *
 * <p>Every NetSuite call:
 * <ul>
 *   <li>goes through {@link UpstreamCallExecutor} for {@link UpstreamSystem#NETSUITE}, one executor call per HTTP
 *       request (each page of a paged read included), with {@link NetsuiteTokenProvider#evict()} as the
 *       re-authentication step: a 401 evicts the cached token and the request is retried once, a 429 or a timeout or
 *       connectivity failure is never retried, so no write is ever submitted twice;</li>
 *   <li>fetches its bearer token inside the attempt, so the retry after a 401 carries a fresh token;</li>
 *   <li>lets {@code WebClientResponseException} (non-2xx answer) and {@code WebClientRequestException} (timeout,
 *       connection failure) reach the executor unwrapped, so {@link NetsuiteFailureClassifier} can classify them;</li>
 *   <li>resolves {@link NetsuiteProperties#resolvedBaseUrl()} when it is made, so the application starts with every
 *       placeholder empty and the first call fails with an {@link IllegalStateException} naming the missing key.</li>
 * </ul>
 * Logs name only the HTTP method and path, never a header, the token or a body.
 *
 * <p>{@link #connector(NetsuiteProperties)} builds the Reactor Netty transport of every NetSuite {@code WebClient}, the
 * token request of {@link NetsuiteTokenProvider} included, with the connect timeout {@code netsuite.connect-timeout}
 * and the response timeout {@code netsuite.read-timeout} (10 s each, the Mule default response timeout). A timeout
 * shorter than 1 ms, which Netty would apply as no timeout at all, fails start-up naming its key, so every call stays
 * bounded. The two timeouts surface as follows, each wrapped by {@code WebClient} in a
 * {@code WebClientRequestException}:
 * <ul>
 *   <li>no connection within the connect timeout raises Netty's {@code io.netty.channel.ConnectTimeoutException},
 *       a {@code java.net.ConnectException};</li>
 *   <li>no response within the response timeout raises Netty's
 *       {@code io.netty.handler.timeout.ReadTimeoutException}.</li>
 * </ul>
 * Both causes are in the unavailable list of the SC-9 classification, so the cause walk of
 * {@code NetsuiteFailureClassifier} classifies either failure as {@code UNAVAILABLE} and {@code UpstreamCallExecutor}
 * answers it with no retry. The transport adds none either: Reactor Netty's automatic retry of a request whose
 * connection is reset before the request is sent is disabled, so a connectivity failure, of a token request
 * included, surfaces from the single attempt.
 *
 * <p>{@link #suiteQlLiteral(String)} escapes a caller-supplied value for use inside a SuiteQL string literal. SuiteQL
 * follows Oracle literal rules, under which a single quote is the only character that can end a literal and is
 * written doubled. Backslash has no escaping role there, so it is left as it is, and so are {@code %} and {@code _},
 * which keep their wildcard meaning where the literal is a {@code LIKE} pattern. Doubling the quote is therefore all
 * that keeps a value from closing the literal, and the literal then denotes exactly the value given.
 *
 * <p>Response bodies are parsed into {@link LinkedHashMap}s in the order NetSuite wrote the keys, with integers as
 * {@code Integer}, {@code Long} or {@code BigInteger} and decimals as {@link BigDecimal} keeping their written scale
 * ({@code 50.0} stays {@code 50.0}). A read whose body is empty, is JSON {@code null} or is not an object fails with
 * an {@link IllegalStateException}, as does a page without its {@code items} array and boolean {@code hasMore} and a
 * metadata catalog without its {@code properties} object, so a malformed answer never reads as an empty result or
 * enters the cache; no such message carries any part of the body. The class and its public methods are not final,
 * so tests can mock it; one instance serves concurrent requests. Its two caches, the metadata catalogs and the
 * custom-field sources, live for the application run and hold successful reads only. Concurrent calls that miss the
 * same entry share one acquisition: the first registers its read and the others wait for that read's result, or
 * fail with its failure, which nothing caches, so a later call reads again. Entries are never acquired inside a map
 * computation, so a slow read of one key never holds up another.
 */
@Component
public class NetsuiteRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteRestClient.class);

    /** Connect and response timeout used when a setting is absent: the Mule default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The shortest accepted timeout: Netty and Reactor Netty apply both timeouts in whole milliseconds and read
     * {@code 0} as no timeout at all.
     */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /** Netty takes the connect timeout as an {@code int} number of milliseconds. */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** Reactor Netty converts the response timeout to a {@code long} number of milliseconds. */
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);

    private static final String CONNECT_TIMEOUT_KEY = "netsuite.connect-timeout";

    private static final String READ_TIMEOUT_KEY = "netsuite.read-timeout";

    private static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    private static final String RECORD_TYPE_PATH = "/services/rest/record/v1/{type}";

    private static final String RECORD_PATH = "/services/rest/record/v1/{type}/{id}";

    private static final String METADATA_CATALOG_PATH = "/services/rest/record/v1/metadata-catalog/{type}";

    /** The largest page NetSuite serves for SuiteQL results and record collections. */
    private static final int PAGE_LIMIT = 1000;

    /** Oracle's limit on the expressions of one {@code IN} list, which SuiteQL inherits. */
    private static final int MAX_IN_LIST = 1000;

    /** Response bodies are buffered whole; a 1000-row SuiteQL page or an expanded record stays far below this. */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    private static final String PREFER = "Prefer";

    /** Asks NetSuite not to keep the SuiteQL result set for later paging requests. */
    private static final String TRANSIENT = "transient";

    private static final MediaType SCHEMA_JSON = MediaType.parseMediaType("application/schema+json");

    private static final String ITEMS = "items";

    private static final String HAS_MORE = "hasMore";

    private static final String COUNT = "count";

    private static final String LINKS = "links";

    private static final String PROPERTIES = "properties";

    private static final String SCRIPT_ID_COLUMN = "scriptid";

    private static final String SOURCE_COLUMN = "fieldvaluetyperecord";

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final NetsuiteProperties props;

    private final NetsuiteTokenProvider tokenProvider;

    private final UpstreamCallExecutor executor;

    private final WebClient webClient;

    /*
     * Floats as BigDecimal keep NetSuite's written scale. Parse-failure locations never include the body, which
     * carries customer data.
     */
    private final ObjectMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    /** Record type → its metadata catalog (JSON schema), successful reads only. */
    private final ConcurrentHashMap<String, Map<String, Object>> metadataCache = new ConcurrentHashMap<>();

    /** Lower-cased custom field script ID → internal ID of its select source, resolved lookups only. */
    private final ConcurrentHashMap<String, String> customFieldSourceCache = new ConcurrentHashMap<>();

    /** Record type → its metadata catalog read in progress, which concurrent first reads of the type wait for. */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> metadataReads =
            new ConcurrentHashMap<>();

    /**
     * Lower-cased custom field script ID → its source lookup in progress, which concurrent calls needing the ID wait
     * for; an empty result means the lookup resolved no source.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<String>>> customFieldLookups =
            new ConcurrentHashMap<>();

    /**
     * Creates the client. Nothing is read from the {@code netsuite.*} settings here apart from the two timeouts, so
     * the application starts with every placeholder empty.
     *
     * @param props         the {@code netsuite.*} settings
     * @param tokenProvider supplies the bearer token of every request and evicts it after a 401
     * @param executor      applies the SC-9 failure handling to every request
     * @param builder       Spring Boot's auto-configured builder; it is cloned, never modified
     * @throws NullPointerException  when an argument is {@code null}
     * @throws IllegalStateException when a configured timeout is invalid (see {@link #connector(NetsuiteProperties)})
     */
    public NetsuiteRestClient(NetsuiteProperties props, NetsuiteTokenProvider tokenProvider,
            UpstreamCallExecutor executor, WebClient.Builder builder) {
        this.props = Objects.requireNonNull(props, "props");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        // No base URL: it is resolved from the properties on every request, so start-up needs no account.
        this.webClient = Objects.requireNonNull(builder, "builder").clone()
                .clientConnector(connector(props))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Builds the HTTP transport of a NetSuite {@code WebClient}: a Reactor Netty client whose connect timeout is
     * {@code netsuite.connect-timeout} and whose response timeout is {@code netsuite.read-timeout}, each 10 s when
     * the setting returns {@code null}, and which never retries a request on its own. The settings are read once,
     * when the transport is built.
     *
     * <p>Each timeout must be at least 1 ms. Netty applies the connect timeout and Reactor Netty the response
     * timeout in whole milliseconds, and both read {@code 0} as no timeout, so a zero, negative or sub-millisecond
     * setting would leave every call unbounded; it is rejected here, at start-up, instead.
     *
     * <p>Reactor Netty's automatic retry is disabled. Left enabled, it sends a request a second time, on a new
     * connection, when the first connection is reset before the request headers are written, and neither
     * {@link UpstreamCallExecutor} nor the caller would see the first attempt. Disabled, a connectivity failure
     * surfaces from the single attempt, the token request included, so the executor classifies it and never retries
     * it.
     *
     * <p>A connect timeout that expires raises {@code io.netty.channel.ConnectTimeoutException} (a
     * {@code java.net.ConnectException}); a response timeout that expires raises
     * {@code io.netty.handler.timeout.ReadTimeoutException}. {@code WebClient} wraps either in a
     * {@code WebClientRequestException}, which the SC-9 cause walk classifies as {@code UNAVAILABLE}.
     *
     * @param props the {@code netsuite.*} settings
     * @return a new {@link ReactorClientHttpConnector} for {@code WebClient.Builder#clientConnector}
     * @throws NullPointerException  when {@code props} is {@code null}
     * @throws IllegalStateException when a timeout is shorter than 1 ms (zero and negative included), or when the
     *         connect timeout exceeds {@link Integer#MAX_VALUE} milliseconds or the read timeout
     *         {@link Long#MAX_VALUE} milliseconds; the message names the key
     */
    public static ClientHttpConnector connector(NetsuiteProperties props) {
        return new ReactorClientHttpConnector(httpClient(props));
    }

    /**
     * Builds the Reactor Netty client that {@link #connector(NetsuiteProperties)} wraps, configured as that method
     * describes. It is package-private so that tests can read the configuration the transport runs with.
     *
     * @param props the {@code netsuite.*} settings
     * @return a new Reactor Netty client
     * @throws NullPointerException  when {@code props} is {@code null}
     * @throws IllegalStateException when a timeout is invalid, as for {@link #connector(NetsuiteProperties)}
     */
    static HttpClient httpClient(NetsuiteProperties props) {
        Objects.requireNonNull(props, "props");
        Duration connect = timeout(props.getConnectTimeout(), CONNECT_TIMEOUT_KEY, MAX_CONNECT_TIMEOUT);
        Duration read = timeout(props.getReadTimeout(), READ_TIMEOUT_KEY, MAX_READ_TIMEOUT);
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connect.toMillis())
                .responseTimeout(read)
                .disableRetry(true);
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

    /**
     * Runs a SuiteQL query and returns every result row: {@code POST /services/rest/query/v1/suiteql} with
     * {@code Prefer: transient} and the body {@code {"q": query}}, paged by {@code limit=1000&offset=<n>} until
     * NetSuite reports {@code hasMore} {@code false} or a page holds no item. Each page is its own executor call,
     * and {@code offset} advances by the page's {@code count}, or by its item count when NetSuite states none. Only
     * a page such as {@code {"items": [], "hasMore": false}} is an empty result; a page that is not a JSON object
     * with an {@code items} array and a boolean {@code hasMore} fails the read.
     *
     * <p>Each row keeps NetSuite's column keys (SuiteQL writes them lower-case, for example {@code email},
     * {@code firstname}, {@code lastname}) in their order and its values as parsed; only the per-row {@code links}
     * key is removed. Values placed inside string literals of {@code query} must have been escaped with
     * {@link #suiteQlLiteral(String)}.
     *
     * @param query the SuiteQL statement
     * @return the rows in arrival order, unmodifiable
     * @throws IllegalArgumentException when {@code query} is {@code null}
     * @throws IllegalStateException    when a setting is missing (the message names the key), or a page is empty,
     *         is not a JSON object, or lacks its {@code items} array of objects or its boolean {@code hasMore}
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged for failures the classifier reports as {@code OTHER} (for example a 400 for an invalid query)
     */
    public List<Map<String, Object>> suiteQl(String query) {
        if (query == null) {
            throw new IllegalArgumentException("SuiteQL query must not be null");
        }
        Map<String, String> body = Map.of("q", query);
        List<Map<String, Object>> items = collectPages(offset -> {
            URI uri = pageUri(SUITEQL_PATH, Map.of(), offset);
            return () -> {
                LOG.debug("NetSuite POST {}", uri.getRawPath());
                return webClient.post()
                        .uri(uri)
                        .headers(headers -> authorize(headers, MediaType.APPLICATION_JSON))
                        .header(PREFER, TRANSIENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            };
        });
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.remove(LINKS);
            rows.add(row);
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * Reads one record with its sublists and subrecords expanded:
     * {@code GET /services/rest/record/v1/{type}/{id}?expandSubResources=true}.
     *
     * @param type the REST record type, for example {@code customer}
     * @param id   the record's internal ID
     * @return the record exactly as NetSuite wrote it, {@code links} included
     * @throws IllegalArgumentException when {@code type} or {@code id} is {@code null} or blank
     * @throws IllegalStateException    when a setting is missing, or the body is empty, JSON {@code null} or not a
     *         JSON object
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged (for example a 404 for an unknown ID)
     */
    public Map<String, Object> getRecord(String type, String id) {
        Map<String, String> variables = Map.of("type", requireText(type, "type"), "id", requireText(id, "id"));
        URI uri = expand(endpoint(RECORD_PATH).queryParam("expandSubResources", "true"), variables);
        return parse(exchange(() -> get(uri, MediaType.APPLICATION_JSON)));
    }

    /**
     * Lists the internal IDs of every record of a type: {@code GET /services/rest/record/v1/{type}}, paged by
     * {@code limit=1000&offset=<n>} exactly as {@link #suiteQl(String)} pages, each page its own executor call.
     *
     * @param type the REST record type, for example {@code itemsupplyplan}
     * @return the {@code id} of each listed item that has one, as text, in arrival order, unmodifiable
     * @throws IllegalArgumentException when {@code type} is {@code null} or blank
     * @throws IllegalStateException    when a setting is missing, or a page is empty, is not a JSON object, or lacks
     *         its {@code items} array of objects or its boolean {@code hasMore}
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged
     */
    public List<String> listRecordIds(String type) {
        Map<String, String> variables = Map.of("type", requireText(type, "type"));
        List<Map<String, Object>> items = collectPages(offset -> {
            URI uri = pageUri(RECORD_TYPE_PATH, variables, offset);
            return () -> get(uri, MediaType.APPLICATION_JSON);
        });
        List<String> ids = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            Object id = item.get("id");
            if (id != null) {
                ids.add(String.valueOf(id));
            }
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * Returns the metadata catalog (JSON schema) of a record type,
     * {@code GET /services/rest/record/v1/metadata-catalog/{type}} with {@code Accept: application/schema+json}. A
     * successful read is cached for the application run, so each type is read once: concurrent first reads of a type
     * share one request, the calls arriving while it runs waiting for its result. A failure is shared by the calls
     * waiting on it and is not cached, so a later call reads the type again. A read succeeds only when the body is a
     * JSON object whose {@code properties}, the field definitions, is a JSON object; no other schema keyword is
     * required.
     *
     * @param type the REST record type, for example {@code customer}
     * @return the catalog, deeply unmodifiable, because the cached instance is shared by every caller
     * @throws IllegalArgumentException when {@code type} is {@code null} or blank
     * @throws IllegalStateException    when a setting is missing, the body is empty or not a JSON object, or its
     *         {@code properties} is missing or not a JSON object (the message names the record type); nothing is
     *         cached then
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged; a call that waited on a concurrent read throws that read's exception
     */
    public Map<String, Object> metadataCatalog(String type) {
        String recordType = requireText(type, "type");
        Map<String, Object> cached = metadataCache.get(recordType);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<Map<String, Object>> read = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> running = metadataReads.putIfAbsent(recordType, read);
        if (running != null) {
            return await(running);
        }
        try {
            // A read that ended between the cache miss above and this registration has cached the catalog.
            Map<String, Object> catalog = metadataCache.get(recordType);
            if (catalog == null) {
                catalog = readMetadataCatalog(recordType);
                // Cached before the read is deregistered, so a call registering after it finds it on that re-check.
                metadataCache.put(recordType, catalog);
            }
            read.complete(catalog);
            return catalog;
        } catch (Throwable failure) {
            read.completeExceptionally(failure);
            throw failure;
        } finally {
            metadataReads.remove(recordType, read);
        }
    }

    /**
     * Reads and checks the metadata catalog of a record type in one executor call.
     *
     * @return the catalog, deeply unmodifiable
     * @throws IllegalStateException when the catalog's {@code properties} is missing or not a JSON object, or as
     *         {@link #metadataCatalog(String)} describes
     */
    private Map<String, Object> readMetadataCatalog(String recordType) {
        URI uri = expand(endpoint(METADATA_CATALOG_PATH), Map.of("type", recordType));
        Map<String, Object> parsed = parse(exchange(() -> get(uri, SCHEMA_JSON)));
        if (!(parsed.get(PROPERTIES) instanceof Map<?, ?>)) {
            throw new IllegalStateException("Unexpected NetSuite metadata catalog for record type " + recordType
                    + ": properties is missing or not an object");
        }
        return immutableMap(parsed);
    }

    /**
     * Creates a record: {@code POST /services/rest/record/v1/{type}} with the body as JSON. NetSuite answers
     * {@code 204 No Content} with the new record's URL in {@code Location}; any 2xx is accepted. The request is
     * retried only after a 401, which NetSuite answers before processing it; a timeout or connectivity failure is
     * never retried, so the record is never created twice.
     *
     * @param type the REST record type, for example {@code customer}
     * @param body the record's fields, serialized as JSON
     * @return the new record's internal ID, the last path segment of {@code Location}
     * @throws IllegalArgumentException when {@code type} is {@code null} or blank, or {@code body} is {@code null}
     * @throws IllegalStateException    when a setting is missing, or the answer carries no {@code Location} or one
     *         without a path segment
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged (for example a 400 for an invalid field)
     */
    public String createRecord(String type, Map<String, ?> body) {
        String recordType = requireText(type, "type");
        if (body == null) {
            throw new IllegalArgumentException("record body must not be null");
        }
        URI uri = expand(endpoint(RECORD_TYPE_PATH), Map.of("type", recordType));
        ResponseEntity<Void> response = exchange(() -> {
            LOG.debug("NetSuite POST {}", uri.getRawPath());
            return webClient.post()
                    .uri(uri)
                    .headers(headers -> authorize(headers, MediaType.APPLICATION_JSON))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        });
        String location = response == null ? null : response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("NetSuite create " + recordType + " returned no Location header");
        }
        return lastPathSegment(location).orElseThrow(() -> new IllegalStateException(
                "NetSuite create " + recordType + " returned a Location header without a record ID"));
    }

    /**
     * Deletes a record: {@code DELETE /services/rest/record/v1/{type}/{id}}, which NetSuite answers with
     * {@code 204 No Content}.
     *
     * @param type the REST record type, for example {@code customer}
     * @param id   the record's internal ID
     * @throws IllegalArgumentException when {@code type} or {@code id} is {@code null} or blank
     * @throws IllegalStateException    when a setting is missing
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged (for example a 404 for an unknown ID)
     */
    public void deleteRecord(String type, String id) {
        Map<String, String> variables = Map.of("type", requireText(type, "type"), "id", requireText(id, "id"));
        URI uri = expand(endpoint(RECORD_PATH), variables);
        exchange(() -> {
            LOG.debug("NetSuite DELETE {}", uri.getRawPath());
            webClient.delete()
                    .uri(uri)
                    .headers(headers -> authorize(headers, MediaType.APPLICATION_JSON))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return null;
        });
    }


    /**
     * Resolves the select source of custom fields: for each script ID, the internal ID of the custom list or record
     * type its values come from, the {@code typeId} SuiteTalk wrote on a select value and a REST select value does not
     * carry. Script IDs are matched case-insensitively and reported lower-cased.
     *
     * <p>Each ID is resolved from the first of these sources that has it:
     * <ol>
     *   <li>a non-blank {@code netsuite.custom-field-type-ids} entry ({@link
     *       NetsuiteProperties#customFieldTypeIdOverride(String)}), for a tenant whose catalog exposes the source
     *       differently;</li>
     *   <li>an earlier lookup of this application run;</li>
     *   <li>one SuiteQL query for all remaining IDs that no concurrent call is already looking up (one per 1000,
     *       Oracle's {@code IN} list limit),
     *       {@code SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE UPPER(scriptid) IN ('CUSTENTITY8', ...)},
     *       taking the raw column value: text is trimmed, an integral number is written without a fraction
     *       ({@code 16}, and {@code -30} for a standard transaction source unchanged), any other number in plain
     *       notation.</li>
     * </ol>
     * An ID none of them resolves, the lookup returning no row or a null or blank source for it, is absent from the
     * result and is not cached, so the caller can fail naming it; no source ID is ever guessed.
     *
     * <p>Concurrent calls never look the same ID up twice at once. A call claims each ID of a batch that no other
     * call is looking up, right before it queries that batch, and queries only the IDs it claimed; an ID another call
     * is looking up is waited for once this call's own batches are done, and its result is used as if this call had
     * looked it up. A resolved ID is therefore read once per application run. A failed lookup fails the call that made
     * it and every call waiting on its IDs, and caches nothing, so a later call looks those IDs up again.
     *
     * @param scriptIds custom field script IDs, for example {@code custentity8}; {@code null} and blank elements are
     *                  ignored
     * @return lower-cased script ID → source ID for every resolved ID, in first-seen input order, unmodifiable;
     *         empty, with no request made, for {@code null} or empty input
     * @throws IllegalStateException when a setting is missing, or a lookup page is not the SuiteQL page
     *         {@link #suiteQl(String)} requires (a JSON object with an {@code items} array of objects and a boolean
     *         {@code hasMore})
     * @throws RuntimeException      the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged; a call that waited on a concurrent lookup throws that lookup's exception
     */
    public Map<String, String> customFieldSourceIds(Collection<String> scriptIds) {
        if (scriptIds == null || scriptIds.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String scriptId : scriptIds) {
            if (scriptId != null && !scriptId.isBlank()) {
                ids.add(scriptId.trim().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, String> resolved = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (String id : ids) {
            Optional<String> source = props.customFieldTypeIdOverride(id)
                    .or(() -> Optional.ofNullable(customFieldSourceCache.get(id)));
            if (source.isPresent()) {
                resolved.put(id, source.get());
            } else {
                unresolved.add(id);
            }
        }
        Map<String, CompletableFuture<Optional<String>>> othersLookups = new LinkedHashMap<>();
        for (int from = 0; from < unresolved.size(); from += MAX_IN_LIST) {
            List<String> batch = unresolved.subList(from, Math.min(from + MAX_IN_LIST, unresolved.size()));
            lookUpBatch(batch, resolved, othersLookups);
        }
        // Waited for only now: this call holds no claim any more, so no two calls can wait on each other.
        othersLookups.forEach((id, lookup) -> await(lookup).ifPresent(source -> resolved.put(id, source)));
        Map<String, String> result = new LinkedHashMap<>();
        for (String id : ids) {
            String source = resolved.get(id);
            if (source != null) {
                result.put(id, source);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Looks up one batch of uncached script IDs, claiming each one no other call is looking up and querying the
     * claimed ones in a single {@link #lookUpCustomFieldSources(List)} call; none is queried when every ID is
     * already claimed or cached. A resolved source is cached before its claim is released, so a call claiming the ID
     * afterwards finds it in the cache. On a failure every lookup this batch claimed completes with that failure,
     * which is rethrown, and nothing is cached. The claims are released in every case.
     *
     * @param batch         lower-cased script IDs, at most {@link #MAX_IN_LIST}
     * @param resolved      receives the source of each claimed ID that resolves
     * @param othersLookups receives the lookup of each ID another call has in progress, for the caller to wait for
     */
    private void lookUpBatch(List<String> batch, Map<String, String> resolved,
            Map<String, CompletableFuture<Optional<String>>> othersLookups) {
        Map<String, CompletableFuture<Optional<String>>> claimed = new LinkedHashMap<>();
        try {
            List<String> toQuery = new ArrayList<>();
            for (String id : batch) {
                CompletableFuture<Optional<String>> lookup = new CompletableFuture<>();
                CompletableFuture<Optional<String>> running = customFieldLookups.putIfAbsent(id, lookup);
                if (running != null) {
                    othersLookups.put(id, running);
                    continue;
                }
                claimed.put(id, lookup);
                // A lookup that ended between this call's cache miss and its claim has cached the source.
                String cached = customFieldSourceCache.get(id);
                if (cached != null) {
                    resolved.put(id, cached);
                    lookup.complete(Optional.of(cached));
                } else {
                    toQuery.add(id);
                }
            }
            Map<String, String> found = toQuery.isEmpty() ? Map.of() : lookUpCustomFieldSources(toQuery);
            for (String id : toQuery) {
                String source = found.get(id);
                if (source != null) {
                    customFieldSourceCache.put(id, source);
                    resolved.put(id, source);
                }
                claimed.get(id).complete(Optional.ofNullable(source));
            }
        } catch (Throwable failure) {
            claimed.values().forEach(lookup -> lookup.completeExceptionally(failure));
            throw failure;
        } finally {
            claimed.forEach(customFieldLookups::remove);
        }
    }

    /**
     * Waits for an acquisition a concurrent call started and returns its result, or throws its failure: the
     * exception that call's request raised, the SC-9 upstream exceptions and NetSuite's {@code WebClient*Exception}
     * included, so it reaches every waiting caller as it reaches that call. The wait is bounded by that call's own
     * connect and response timeouts.
     */
    private static <T> T await(CompletableFuture<T> acquisition) {
        try {
            return acquisition.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause == null) {
                throw e;
            }
            throw new IllegalStateException("NetSuite call failed", cause);
        }
    }

    /**
     * Queries {@code customfield} for the given lower-cased script IDs.
     *
     * @return lower-cased script ID → source ID for each row with a usable source
     */
    private Map<String, String> lookUpCustomFieldSources(List<String> ids) {
        String inList = ids.stream()
                .map(id -> suiteQlLiteral(id.toUpperCase(Locale.ROOT)))
                .collect(Collectors.joining("', '", "('", "')"));
        List<Map<String, Object>> rows = suiteQl("SELECT " + SCRIPT_ID_COLUMN + ", " + SOURCE_COLUMN
                + " FROM customfield WHERE UPPER(" + SCRIPT_ID_COLUMN + ") IN " + inList);
        Map<String, String> found = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object scriptId = column(row, SCRIPT_ID_COLUMN);
            String source = sourceId(column(row, SOURCE_COLUMN));
            if (scriptId != null && source != null) {
                found.putIfAbsent(String.valueOf(scriptId).trim().toLowerCase(Locale.ROOT), source);
            }
        }
        return found;
    }

    /** Reads a column by name, ignoring case, because SuiteQL's column-name case is not part of its contract. */
    private static Object column(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Renders a raw {@code fieldvaluetyperecord} value as a source ID: trimmed text, an integral number without a
     * fraction, any other finite number in plain notation; {@code null} for a null, blank or non-scalar value.
     */
    private static String sourceId(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        BigDecimal number;
        if (value instanceof BigDecimal decimal) {
            number = decimal;
        } else if (value instanceof BigInteger integer) {
            number = new BigDecimal(integer);
        } else if (value instanceof Double || value instanceof Float) {
            double floating = ((Number) value).doubleValue();
            if (!Double.isFinite(floating)) {
                return null;
            }
            number = BigDecimal.valueOf(floating);
        } else if (value instanceof Number integral) {
            number = BigDecimal.valueOf(integral.longValue());
        } else {
            return null;
        }
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : number.toPlainString();
    }

    /**
     * Reads every page of a paged NetSuite resource, starting at offset 0. Every page must be a JSON object with an
     * {@code items} array of objects and a boolean {@code hasMore}; {@code count}, {@code offset},
     * {@code totalResults} and {@code links} are optional. Paging ends when a page's {@code hasMore} is
     * {@code false} or the page holds no item, the latter guarding against a server that keeps reporting more
     * without delivering it.
     *
     * @param pageCall the request of the page at a given offset
     * @return the items of every page, in arrival order
     * @throws IllegalStateException when a page is empty, is not a JSON object, or lacks the {@code items} array of
     *         objects or the boolean {@code hasMore}, so a malformed page never reads as the end of the result
     */
    private List<Map<String, Object>> collectPages(LongFunction<UpstreamCall<String>> pageCall) {
        List<Map<String, Object>> collected = new ArrayList<>();
        long offset = 0;
        while (true) {
            Map<String, Object> page = parse(exchange(pageCall.apply(offset)));
            List<Map<String, Object>> items = items(page);
            boolean hasMore = hasMore(page);
            collected.addAll(items);
            if (!hasMore || items.isEmpty()) {
                return collected;
            }
            Object count = page.get(COUNT);
            long advance = count instanceof Number number ? number.longValue() : 0;
            offset += advance > 0 ? advance : items.size();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        Object items = page.get(ITEMS);
        if (items == null) {
            throw new IllegalStateException("Unexpected NetSuite response: items is missing");
        }
        if (!(items instanceof List<?> list)) {
            throw new IllegalStateException("Unexpected NetSuite response: items is not an array");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Unexpected NetSuite response: an item is not an object");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static boolean hasMore(Map<String, Object> page) {
        Object hasMore = page.get(HAS_MORE);
        if (hasMore == null) {
            throw new IllegalStateException("Unexpected NetSuite response: hasMore is missing");
        }
        if (!(hasMore instanceof Boolean more)) {
            throw new IllegalStateException("Unexpected NetSuite response: hasMore is not a boolean");
        }
        return more;
    }

    /**
     * Performs one NetSuite request through the SC-9 executor, with the token eviction as its re-authentication
     * step. Runtime exceptions, the {@code WebClient*Exception}s and the upstream exceptions among them, pass
     * unchanged; a checked exception, which no {@code WebClient} call raises, is wrapped so the public methods
     * declare none.
     */
    private <T> T exchange(UpstreamCall<T> call) {
        try {
            return executor.call(UpstreamSystem.NETSUITE, call, tokenProvider::evict);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("NetSuite call failed with " + e.getClass().getName(), e);
        }
    }

    private String get(URI uri, MediaType accept) {
        LOG.debug("NetSuite GET {}", uri.getRawPath());
        return webClient.get()
                .uri(uri)
                .headers(headers -> authorize(headers, accept))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** Sets the bearer token, fetched now so that each attempt, the retry after a 401 included, asks for one. */
    private void authorize(HttpHeaders headers, MediaType accept) {
        headers.setBearerAuth(tokenProvider.accessToken());
        headers.setAccept(List.of(accept));
    }

    private UriComponentsBuilder endpoint(String path) {
        return UriComponentsBuilder.fromUriString(props.resolvedBaseUrl()).path(path);
    }

    private URI pageUri(String path, Map<String, String> variables, long offset) {
        return expand(endpoint(path).queryParam("limit", PAGE_LIMIT).queryParam("offset", offset), variables);
    }

    /*
     * The template is encoded before expansion, so every reserved character of a record type or ID, '/' included,
     * is percent-encoded and a caller-supplied value can never change the path it is placed in.
     */
    private static URI expand(UriComponentsBuilder builder, Map<String, String> variables) {
        return builder.encode().buildAndExpand(variables).toUri();
    }

    /**
     * Parses a read's body as a JSON object. An absent or blank body and JSON {@code null} are failures, never an
     * empty object, so a malformed answer cannot pass for an empty result; the messages raised here carry no part of
     * the body.
     *
     * @param body the response body as NetSuite sent it, possibly {@code null}
     * @return the body's top-level JSON object, keys in arrival order
     * @throws IllegalStateException when the body is absent or blank, is JSON {@code null}, or is not valid JSON
     *         holding an object ({@code Unparsable NetSuite response}, with Jackson's exception as the cause)
     */
    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("NetSuite returned an empty response body");
        }
        Map<String, Object> parsed;
        try {
            parsed = json.readValue(body, JSON_OBJECT);
        } catch (IOException e) {
            throw new IllegalStateException("Unparsable NetSuite response", e);
        }
        if (parsed == null) {
            throw new IllegalStateException("NetSuite response is not a JSON object");
        }
        return parsed;
    }

    private static Optional<String> lastPathSegment(String location) {
        List<String> segments;
        try {
            segments = UriComponentsBuilder.fromUriString(location.trim()).build().getPathSegments();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return segments.isEmpty() ? Optional.empty() : Optional.of(segments.get(segments.size() - 1));
    }

    private static Map<String, Object> immutableMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(immutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value.trim();
    }

    private static Duration timeout(Duration configured, String key, Duration max) {
        Duration timeout = configured != null ? configured : DEFAULT_TIMEOUT;
        // Below 1 ms a timeout is truncated to 0 ms, which Netty reads as no timeout, or is negative, which no Netty
        // timeout accepts; compareTo, unlike toMillis(), cannot overflow.
        if (timeout.compareTo(MIN_TIMEOUT) < 0) {
            throw new IllegalStateException(
                    key + " must be a positive duration of at least " + MIN_TIMEOUT.toMillis() + " ms");
        }
        if (timeout.compareTo(max) > 0) {
            throw new IllegalStateException(key + " must not exceed " + max.toMillis() + " ms");
        }
        return timeout;
    }
}

