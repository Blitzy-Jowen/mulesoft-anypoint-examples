/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.client;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.config.DynamicsOAuthConfig;

/**
 * The Microsoft Dynamics 365 adapter: the Dataverse Web API v9.2 {@code contact} operations the example and its live
 * test use, in place of the Mule {@code dynamicscrm} connector, which reached the CRM Online Organization service
 * with a user name and password.
 * <ul>
 *   <li>{@link #createContact(Map)} replaces {@code <dynamicscrm:create logicalName="contact">} of
 *       {@code import-contacts-into-ms-dynamics.xml} (lines 22-29), run once per CSV record:
 *       {@code POST <service URL>/api/data/v9.2/contacts}, answered {@code 204 No Content} with the new record's URL
 *       in {@code OData-EntityId};</li>
 *   <li>{@link #findContactsByEmail(String)} replaces the test flow {@code selectContactFromDynamics}
 *       ({@code dsql:SELECT contactid,firstname,lastname FROM contact where emailaddress1 = '#[payload]'}):
 *       {@code GET contacts?$select=contactid,firstname,lastname&$filter=emailaddress1 eq '<e-mail>'};</li>
 *   <li>{@link #deleteContact(String)} replaces the test flow {@code deleteContactFromDynamics}
 *       ({@code <dynamicscrm:delete logicalName="contact"/>}): {@code DELETE contacts(<guid>)}, answered {@code 204}.</li>
 * </ul>
 *
 * <p>Every request goes through the {@link WebClient} bean of {@link DynamicsOAuthConfig}, which attaches the
 * Microsoft Entra client-credentials token and applies the 10 s connect and read timeouts with a single transport
 * attempt, and carries {@code OData-MaxVersion: 4.0} and {@code OData-Version: 4.0}. The environment URL is read from
 * {@link DynamicsOAuthConfig.DynamicsProperties#requireServiceUrl()} when a request is made, never at construction,
 * so the application starts with every {@code dynamics.*} key blank and the first call fails with the
 * {@link IllegalStateException} naming {@code dynamics.service-url}, before any request is sent.
 *
 * <p>Each Dataverse request is one {@link UpstreamCallExecutor} call for
 * {@link UpstreamSystem#MICROSOFT_DYNAMICS_365} (shared component SC-9), with {@link #evictToken()} as the
 * re-authentication step. A 401 evicts the cached token and the request is sent once more with a new one; a 429 is a
 * rate limit carrying Dataverse's {@code Retry-After}; a timeout or connectivity failure is never retried, so no
 * contact is ever created twice. Vendor exceptions ({@code WebClientResponseException} for a non-2xx answer,
 * {@code WebClientRequestException} for a transport failure) reach the executor unwrapped, and every other failure,
 * a Dataverse 400, 403, 404 or 5xx included, is rethrown to the caller unchanged.
 *
 * <p>Each request waits with a plain {@code block()} and catches nothing. No Reactor retry or timeout operator and no
 * bounded wait is applied: each would retry silently, or replace the transport's timeout with an exception the failure
 * classifier does not report as unavailable. The connector of {@link DynamicsOAuthConfig} bounds every request.
 *
 * <p>Logs name only the HTTP method and path, never a header, the token, the query or a body.
 *
 * <p>The class and its public methods are not final, so tests can mock it. It holds no mutable state, and one
 * instance serves concurrent callers.
 */
@Component
public class DynamicsWebApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicsWebApiClient.class);

    /*
     * Error locations of a failed parse never quote the body, which carries contact names and e-mail addresses.
     * Writing keeps Jackson's defaults, so a null attribute is sent as JSON null.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    /** A Dataverse primary key: a GUID in its 8-4-4-4-12 hexadecimal form. */
    private static final Pattern GUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * The key inside the last parenthesised segment of an {@code OData-EntityId} URL, for example
     * {@code 00000000-0000-0000-0000-000000000001} of {@code .../api/data/v9.2/contacts(00000000-...-000000000001)}.
     */
    private static final Pattern ENTITY_KEY = Pattern.compile("\\(([^()]*)\\)[^()]*$");

    private static final String WEB_API_PATH = "/api/data/v9.2/";

    private static final String CONTACTS = "contacts";

    private static final String ODATA_MAX_VERSION = "OData-MaxVersion";

    private static final String ODATA_VERSION = "OData-Version";

    private static final String ODATA_VERSION_VALUE = "4.0";

    private static final String ODATA_ENTITY_ID = "OData-EntityId";

    /** The columns the original test query selected. */
    private static final String SELECTED_COLUMNS = "contactid,firstname,lastname";

    /** The member of an OData collection response holding the records. */
    private static final String VALUE = "value";

    /** Selects the Entra registration explicitly; the same registration is also the filter's default. */
    private static final Consumer<Map<String, Object>> REGISTRATION =
            ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(DynamicsOAuthConfig.REGISTRATION_ID);

    private final WebClient webClient;

    private final DynamicsOAuthConfig.DynamicsProperties properties;

    private final UpstreamCallExecutor executor;

    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;

    /**
     * Creates the adapter. Nothing is read from the {@code dynamics.*} settings and no request is made here.
     *
     * @param webClient               the Dataverse {@link WebClient} of {@link DynamicsOAuthConfig}
     * @param properties              the {@code dynamics.*} settings
     * @param executor                applies the SC-9 failure handling to every request
     * @param authorizedClientService the store of the cached Entra token, through which {@link #evictToken()}
     *                                drops it
     * @throws NullPointerException when an argument is {@code null}
     */
    public DynamicsWebApiClient(WebClient webClient, DynamicsOAuthConfig.DynamicsProperties properties,
            UpstreamCallExecutor executor, ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.authorizedClientService = Objects.requireNonNull(authorizedClientService, "authorizedClientService");
    }

    /**
     * Creates one contact: {@code POST contacts} with the attributes as a JSON object, in their iteration order and
     * with {@code null} values written as JSON {@code null}, as the connector sent every attribute it was given.
     * Dataverse answers {@code 204 No Content}; any 2xx is accepted.
     *
     * @param attributes the contact columns, for example {@code firstname}, {@code lastname},
     *                   {@code emailaddress1} and {@code telephone1}
     * @return the new contact's GUID, read from the last parenthesised segment of {@code OData-EntityId}, or
     *         {@code null} when that header is absent or holds no GUID there
     * @throws IllegalArgumentException when {@code attributes} is {@code null}; no request is made
     * @throws IllegalStateException    when {@code dynamics.service-url} or an Entra credential is blank; no
     *                                  Dataverse request is made
     * @throws Exception                the SC-9 upstream exceptions, or the vendor's exception unchanged, for
     *                                  example a {@code WebClientResponseException} for a 400
     */
    public String createContact(Map<String, Object> attributes) throws Exception {
        if (attributes == null) {
            throw new IllegalArgumentException("contact attributes must not be null");
        }
        String body = JSON.writeValueAsString(new LinkedHashMap<>(attributes));
        ResponseEntity<Void> response = executor.call(UpstreamSystem.MICROSOFT_DYNAMICS_365, () -> {
            URI uri = URI.create(base() + CONTACTS);
            LOG.debug("Dataverse POST {}", uri.getRawPath());
            return webClient.post()
                    .uri(uri)
                    .attributes(REGISTRATION)
                    .headers(DynamicsWebApiClient::odataHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        }, this::evictToken);
        return entityKey(response);
    }

    /**
     * Finds the contacts whose {@code emailaddress1} equals the given address:
     * {@code GET contacts?$select=contactid,firstname,lastname&$filter=emailaddress1 eq '<e-mail>'}.
     *
     * <p>Every single quote of the address is doubled, the OData string-literal escape, so the address cannot end
     * the literal and every legitimate address matches exactly the rows it names. The filter is percent-encoded
     * once, strictly, as a URI variable (a space as {@code %20}, {@code '} as {@code %27}, {@code +} as
     * {@code %2B}, {@code @} as {@code %40}).
     *
     * @param email the e-mail address to match
     * @return the matching records in Dataverse's order, each a {@link LinkedHashMap} keyed as Dataverse wrote it
     *         ({@code @odata.etag}, {@code contactid}, {@code firstname}, {@code lastname}); an empty list when the
     *         body or its {@code value} member is absent
     * @throws IllegalArgumentException when {@code email} is {@code null}; no request is made
     * @throws IllegalStateException    when {@code dynamics.service-url} or an Entra credential is blank, or when
     *                                  {@code value} is not an array of objects
     * @throws IOException              when the body is not a JSON object
     * @throws Exception                the SC-9 upstream exceptions, or the vendor's exception unchanged
     */
    public List<Map<String, Object>> findContactsByEmail(String email) throws Exception {
        if (email == null) {
            throw new IllegalArgumentException("e-mail address must not be null");
        }
        String filter = "emailaddress1 eq '" + email.replace("'", "''") + "'";
        String json = executor.call(UpstreamSystem.MICROSOFT_DYNAMICS_365, () -> {
            // A URI, not a String, goes to the WebClient: a String would be expanded as a template a second time.
            URI uri = UriComponentsBuilder.fromUriString(base() + CONTACTS)
                    .queryParam("$select", SELECTED_COLUMNS)
                    .queryParam("$filter", "{filter}")
                    .encode()
                    .buildAndExpand(filter)
                    .toUri();
            LOG.debug("Dataverse GET {}", uri.getRawPath());
            return webClient.get()
                    .uri(uri)
                    .attributes(REGISTRATION)
                    .headers(DynamicsWebApiClient::odataHeaders)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        }, this::evictToken);
        return records(json);
    }

    /**
     * Deletes one contact: {@code DELETE contacts(<guid>)}, which Dataverse answers {@code 204 No Content}.
     *
     * @param contactId the contact's GUID, surrounding whitespace ignored
     * @throws IllegalArgumentException when {@code contactId} is {@code null} or not a GUID; no request is made, so
     *                                  no caller-supplied text ever reaches the request path
     * @throws IllegalStateException    when {@code dynamics.service-url} or an Entra credential is blank
     * @throws Exception                the SC-9 upstream exceptions, or the vendor's exception unchanged, for
     *                                  example a {@code WebClientResponseException} for a 404
     */
    public void deleteContact(String contactId) throws Exception {
        String id = contactId == null ? null : contactId.trim();
        if (id == null || !GUID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a Dataverse contact id: " + contactId);
        }
        executor.run(UpstreamSystem.MICROSOFT_DYNAMICS_365, () -> {
            URI uri = URI.create(base() + CONTACTS + "(" + id + ")");
            LOG.debug("Dataverse DELETE {}", uri.getRawPath());
            webClient.delete()
                    .uri(uri)
                    .attributes(REGISTRATION)
                    .headers(DynamicsWebApiClient::odataHeaders)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        }, this::evictToken);
    }

    /**
     * Drops the cached Entra token, the re-authentication step the executor runs before its single retry after a
     * 401; the next request makes the client-credentials manager obtain a new token.
     *
     * <p>The eviction is explicit because the exchange filter of {@link DynamicsOAuthConfig}, built from an
     * authorized-client manager, does not remove the authorized client when Dataverse answers 401, and would send
     * the rejected token again.
     */
    public void evictToken() {
        authorizedClientService
                .removeAuthorizedClient(DynamicsOAuthConfig.REGISTRATION_ID, DynamicsOAuthConfig.PRINCIPAL_NAME)
                .block();
        LOG.info("Evicted the cached Microsoft Entra token of registration '{}'; the next Dataverse request obtains "
                + "a new one", DynamicsOAuthConfig.REGISTRATION_ID);
    }

    /**
     * @return the Web API root, {@code <service URL>/api/data/v9.2/}; {@code requireServiceUrl()} has already trimmed
     *         the URL and removed its trailing slashes
     * @throws IllegalStateException when {@code dynamics.service-url} is blank
     */
    private String base() {
        return properties.requireServiceUrl() + WEB_API_PATH;
    }

    /** Sets the OData version headers; they replace the bean's defaults of the same name rather than repeat them. */
    private static void odataHeaders(HttpHeaders headers) {
        headers.set(ODATA_MAX_VERSION, ODATA_VERSION_VALUE);
        headers.set(ODATA_VERSION, ODATA_VERSION_VALUE);
    }

    /**
     * @param response the answer to a create
     * @return the GUID inside the last parenthesised segment of {@code OData-EntityId}, or {@code null} when the
     *         header is absent or that segment holds no GUID
     */
    private static String entityKey(ResponseEntity<?> response) {
        String entityId = response == null ? null : response.getHeaders().getFirst(ODATA_ENTITY_ID);
        if (entityId == null) {
            return null;
        }
        Matcher key = ENTITY_KEY.matcher(entityId);
        if (!key.find()) {
            return null;
        }
        String id = key.group(1).trim();
        return GUID.matcher(id).matches() ? id : null;
    }

    /**
     * @param json the body of an OData collection response
     * @return the records of its {@code value} array, or a new empty list when the body is absent, blank or JSON
     *         {@code null}, or has no {@code value}
     * @throws IOException           when the body is not a JSON object
     * @throws IllegalStateException when {@code value} is not an array of objects
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        Map<String, Object> root = JSON.readValue(json, JSON_OBJECT);
        Object value = root == null ? null : root.get(VALUE);
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> elements)) {
            throw new IllegalStateException("Unexpected Dataverse contacts response: value is not an array");
        }
        List<Map<String, Object>> records = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (!(element instanceof Map<?, ?>)) {
                throw new IllegalStateException("Unexpected Dataverse contacts response: value holds a non-object");
            }
            records.add((Map<String, Object>) element);
        }
        return records;
    }
}
