package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.get_customer_list_from_netsuite.config.NetsuiteProperties;
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
 *
 * <p>Response bodies are parsed into {@link LinkedHashMap}s in the order NetSuite wrote the keys, with integers as
 * {@code Integer}, {@code Long} or {@code BigInteger} and decimals as {@link BigDecimal} keeping their written scale
 * ({@code 50.0} stays {@code 50.0}). The class and its public methods are not final, so tests can mock it; one
 * instance serves concurrent requests, its two caches being concurrent maps that live for the application run.
 */
@Component
public class NetsuiteRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(NetsuiteRestClient.class);

    /** Connect and response timeout used when a setting is absent: the Mule default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

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

    /**
     * Runs a SuiteQL query and returns every result row: {@code POST /services/rest/query/v1/suiteql} with
     * {@code Prefer: transient} and the body {@code {"q": query}}, paged by {@code limit=1000&offset=<n>} until
     * NetSuite reports {@code hasMore} other than {@code true} or a page holds no item. Each page is its own
     * executor call, and {@code offset} advances by the page's {@code count}, or by its item count when NetSuite
     * states none.
     *
     * <p>Each row keeps NetSuite's column keys (SuiteQL writes them lower-case, for example {@code email},
     * {@code firstname}, {@code lastname}) in their order and its values as parsed; only the per-row {@code links}
     * key is removed. Values placed inside string literals of {@code query} must have been escaped with
     * {@link #suiteQlLiteral(String)}.
     *
     * @param query the SuiteQL statement
     * @return the rows in arrival order, unmodifiable
     * @throws IllegalArgumentException when {@code query} is {@code null}
     * @throws IllegalStateException    when a setting is missing (the message names the key) or a page is not the
     *         expected JSON
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
     * @throws IllegalStateException    when a setting is missing or the body is not a JSON object
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
     * @throws IllegalStateException    when a setting is missing or a page is not the expected JSON
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
     * successful read is cached for the application run, so each type is read once; a failure is not cached.
     *
     * @param type the REST record type, for example {@code customer}
     * @return the catalog, deeply unmodifiable, because the cached instance is shared by every caller
     * @throws IllegalArgumentException when {@code type} is {@code null} or blank
     * @throws IllegalStateException    when a setting is missing or the body is not a JSON object
     * @throws RuntimeException         the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged
     */
    public Map<String, Object> metadataCatalog(String type) {
        String recordType = requireText(type, "type");
        Map<String, Object> cached = metadataCache.get(recordType);
        if (cached != null) {
            return cached;
        }
        URI uri = expand(endpoint(METADATA_CATALOG_PATH), Map.of("type", recordType));
        Map<String, Object> catalog = immutableMap(parse(exchange(() -> get(uri, SCHEMA_JSON))));
        Map<String, Object> earlier = metadataCache.putIfAbsent(recordType, catalog);
        return earlier != null ? earlier : catalog;
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
     *   <li>one SuiteQL query for all remaining IDs (one per 1000, Oracle's {@code IN} list limit),
     *       {@code SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE UPPER(scriptid) IN ('CUSTENTITY8', ...)},
     *       taking the raw column value: text is trimmed, an integral number is written without a fraction
     *       ({@code 16}, and {@code -30} for a standard transaction source unchanged), any other number in plain
     *       notation.</li>
     * </ol>
     * An ID none of them resolves, the lookup returning no row or a null or blank source for it, is absent from the
     * result and is not cached, so the caller can fail naming it; no source ID is ever guessed.
     *
     * @param scriptIds custom field script IDs, for example {@code custentity8}; {@code null} and blank elements are
     *                  ignored
     * @return lower-cased script ID → source ID for every resolved ID, in first-seen input order, unmodifiable;
     *         empty, with no request made, for {@code null} or empty input
     * @throws IllegalStateException when a setting is missing or the lookup answer is not the expected JSON
     * @throws RuntimeException      the SC-9 upstream exceptions, or NetSuite's {@code WebClient*Exception}
     *         unchanged
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
        for (int from = 0; from < unresolved.size(); from += MAX_IN_LIST) {
            List<String> batch = unresolved.subList(from, Math.min(from + MAX_IN_LIST, unresolved.size()));
            Map<String, String> found = lookUpCustomFieldSources(batch);
            for (String id : batch) {
                String source = found.get(id);
                if (source != null) {
                    String earlier = customFieldSourceCache.putIfAbsent(id, source);
                    resolved.put(id, earlier != null ? earlier : source);
                }
            }
        }
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
     * Reads every page of a paged NetSuite resource, starting at offset 0. Paging ends when a page's
     * {@code hasMore} is not {@code true} or the page holds no item, the latter guarding against a server that
     * keeps reporting more without delivering it.
     *
     * @param pageCall the request of the page at a given offset
     * @return the items of every page, in arrival order
     */
    private List<Map<String, Object>> collectPages(LongFunction<UpstreamCall<String>> pageCall) {
        List<Map<String, Object>> collected = new ArrayList<>();
        long offset = 0;
        while (true) {
            Map<String, Object> page = parse(exchange(pageCall.apply(offset)));
            List<Map<String, Object>> items = items(page);
            collected.addAll(items);
            if (!Boolean.TRUE.equals(page.get(HAS_MORE)) || items.isEmpty()) {
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
            return List.of();
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

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = json.readValue(body, JSON_OBJECT);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalStateException("Unparsable NetSuite response", e);
        }
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
        if (timeout.isNegative()) {
            throw new IllegalStateException(key + " must not be negative");
        }
        if (timeout.compareTo(max) > 0) {
            throw new IllegalStateException(key + " must not exceed " + max.toMillis() + " ms");
        }
        return timeout;
    }
}

