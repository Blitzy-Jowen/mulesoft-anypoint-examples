/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.config;

import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Connection to Microsoft Dynamics 365: the Dataverse Web API v9.2, authenticated with a Microsoft Entra ID
 * client-credentials token.
 *
 * <p>Replaces the Mule global configuration
 * {@code <dynamicscrm:config authenticationType="CRMONLINE" organizationServiceUrl="${crm.service-url}"
 * username="${crm.username}" password="${crm.password}"/>}, which logged in to the CRM 2011 Organization SOAP
 * service with a user name and password. The conversion obtains a token from
 * {@code POST https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token} ({@code grant_type=client_credentials},
 * scope {@code <environment URL>/.default}; the app registration must be a Dataverse application user) and calls
 * {@code <environment URL>/api/data/v9.2/...} with {@code OData-MaxVersion: 4.0} and {@code OData-Version: 4.0}.
 * The keys map as follows:
 * <ul>
 *   <li>{@code crm.service-url} → {@code dynamics.service-url};</li>
 *   <li>{@code crm.username}, {@code crm.password} → {@code dynamics.oauth.tenant-id},
 *       {@code dynamics.oauth.client-id}, {@code dynamics.oauth.client-secret};</li>
 *   <li>{@code crm.auth-type} has no successor: the grant is always client credentials.</li>
 * </ul>
 *
 * <p>Beans: the client registration repository, the in-memory authorized-client service that caches the token
 * (and through which {@code client/DynamicsWebApiClient} evicts it), the client-credentials authorized-client
 * manager, and the only {@link WebClient} bean, which attaches the bearer token and the OData headers. Every call,
 * the token request included, uses a 10 s connect and read timeout, the Mule default response timeout, and makes
 * exactly one transport attempt.
 *
 * <p>Missing credentials fail late. The application context starts with every {@code dynamics.*} secret blank, and
 * only the first token or Dataverse call throws {@link IllegalStateException} naming the missing key, because the
 * client registration is built inside the repository's {@link Mono} rather than at start-up.
 *
 * <p>The token is obtained through Spring Security's reactive OAuth2 client components without
 * {@code @EnableWebFluxSecurity}: the application has no HTTP listener, so no security filter chain is wanted.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamicsOAuthConfig.DynamicsProperties.class)
public class DynamicsOAuthConfig {

    /** Registration ID of the Entra client-credentials registration, the filter's default registration. */
    public static final String REGISTRATION_ID = "dynamics";

    /**
     * Principal name under which the authorized-client service stores the Entra token.
     *
     * <p>{@link ServerOAuth2AuthorizedClientExchangeFilterFunction} resolves the principal from the
     * {@code ReactiveSecurityContextHolder}; with no security context, as in this listener-less application, Spring
     * Security 6.2.8 falls back to an {@code AnonymousAuthenticationToken} whose name is {@code anonymousUser}, and the
     * manager saves the authorized client under that name. {@code client/DynamicsWebApiClient} evicts the cached
     * token with {@code removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME)}.
     */
    public static final String PRINCIPAL_NAME = "anonymousUser";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** The shortest timeout Netty can apply: it sets both bounds in whole milliseconds. */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /** The longest connect timeout {@link ChannelOption#CONNECT_TIMEOUT_MILLIS}, an {@code int}, holds. */
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /** The longest response timeout whose {@link Duration#toMillis()}, which Reactor Netty calls, cannot overflow. */
    private static final Duration MAX_READ_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);

    private static final String ENTRA_HOST = "login.microsoftonline.com";

    private static final String DEFAULT_SCOPE_SUFFIX = "/.default";

    private static final String ODATA_VERSION = "4.0";

    /**
     * The {@code dynamics.*} settings, bound from {@code application.yml}.
     *
     * <p>The components hold the values exactly as bound; callers read the trimmed and validated forms through the
     * {@code require...} and {@code effective...} methods, which throw {@link IllegalStateException} naming the
     * missing key. Call them only when a token or Dataverse call is made, never at start-up.
     *
     * @param serviceUrl     {@code dynamics.service-url}, the Dataverse environment URL, for example
     *                       {@code https://<org>.crm.dynamics.com}
     * @param connectTimeout {@code dynamics.connect-timeout} of every token and Dataverse call (default 10 s)
     * @param readTimeout    {@code dynamics.read-timeout} (response timeout) of every token and Dataverse call
     *                       (default 10 s)
     * @param oauth          the {@code dynamics.oauth.*} Entra credentials; an instance with {@code null} components
     *                       when no such key is set
     */
    @ConfigurationProperties(prefix = "dynamics")
    public record DynamicsProperties(
            String serviceUrl,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue OAuth oauth) {

        /**
         * The {@code dynamics.oauth.*} Entra ID app registration credentials.
         *
         * @param tenantId     {@code dynamics.oauth.tenant-id}, the Entra tenant (directory) ID
         * @param clientId     {@code dynamics.oauth.client-id}, the application (client) ID
         * @param clientSecret {@code dynamics.oauth.client-secret}, the client secret; never shown by
         *                     {@link #toString()}
         * @param tokenUri     {@code dynamics.oauth.token-uri}, the token endpoint; tests point it at WireMock
         * @param scope        {@code dynamics.oauth.scope}, the client-credentials scope
         */
        public record OAuth(String tenantId, String clientId, String clientSecret, String tokenUri, String scope) {

            /**
             * @return the components, with the client secret masked as {@code ****} when it is set
             */
            @Override
            public String toString() {
                return "OAuth[tenantId=" + tenantId
                        + ", clientId=" + clientId
                        + ", clientSecret=" + (isBlank(clientSecret) ? String.valueOf(clientSecret) : "****")
                        + ", tokenUri=" + tokenUri
                        + ", scope=" + scope + "]";
            }
        }

        /**
         * Returns the Dataverse environment URL, trimmed and without trailing {@code /}
         * ({@code https://org.crm.dynamics.com/} → {@code https://org.crm.dynamics.com}).
         *
         * @return the environment URL every Web API URI is built from
         * @throws IllegalStateException when {@code dynamics.service-url} is blank or made only of slashes
         */
        public String requireServiceUrl() {
            String url = stripTrailingSlashes(serviceUrl);
            if (url.isEmpty()) {
                throw new IllegalStateException("dynamics.service-url is not configured");
            }
            return url;
        }

        /**
         * Returns the Entra token endpoint.
         *
         * <p>A blank {@code dynamics.oauth.token-uri}, or one on the host {@code login.microsoftonline.com}, is
         * derived from the tenant: {@code https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token}.
         * {@code application.yml} always supplies the token URI built from {@code ${dynamics.oauth.tenant-id}}, which
         * with a blank tenant renders as {@code https://login.microsoftonline.com//oauth2/v2.0/token}; deriving it
         * again reports the blank tenant instead of calling that URL. Any other token URI (a WireMock URL in tests),
         * including one that cannot be parsed, is returned as given.
         *
         * @return the token endpoint URI
         * @throws IllegalStateException when the URI has to be derived and {@code dynamics.oauth.tenant-id} is blank
         */
        public String effectiveTokenUri() {
            String tokenUri = oauth == null ? null : oauth.tokenUri();
            String uri = tokenUri == null ? "" : tokenUri.trim();
            if (!uri.isEmpty() && !isEntraHost(uri)) {
                return uri;
            }
            String tenantId = oauth == null ? null : oauth.tenantId();
            if (isBlank(tenantId)) {
                throw new IllegalStateException("dynamics.oauth.tenant-id is not configured");
            }
            return "https://" + ENTRA_HOST + "/" + tenantId.trim() + "/oauth2/v2.0/token";
        }

        /**
         * Returns the client-credentials scope.
         *
         * <p>A blank {@code dynamics.oauth.scope} is derived as {@code <service URL>/.default}. A scope ending in
         * {@code /.default} is normalised by stripping the slashes before that suffix
         * ({@code https://org.crm.dynamics.com//.default} → {@code https://org.crm.dynamics.com/.default}); when
         * nothing precedes the suffix, the {@code /.default} that {@code ${dynamics.service-url}/.default} renders
         * from a blank service URL, the scope is derived. Any other scope is returned trimmed.
         *
         * @return the scope requested with the token
         * @throws IllegalStateException when the scope has to be derived and {@code dynamics.service-url} is blank
         */
        public String effectiveScope() {
            String configured = oauth == null ? null : oauth.scope();
            String scope = configured == null ? "" : configured.trim();
            if (scope.isEmpty()) {
                return requireServiceUrl() + DEFAULT_SCOPE_SUFFIX;
            }
            if (scope.endsWith(DEFAULT_SCOPE_SUFFIX)) {
                String base = stripTrailingSlashes(scope.substring(0, scope.length() - DEFAULT_SCOPE_SUFFIX.length()));
                return base.isEmpty() ? requireServiceUrl() + DEFAULT_SCOPE_SUFFIX : base + DEFAULT_SCOPE_SUFFIX;
            }
            return scope;
        }

        /**
         * @return {@code dynamics.oauth.client-id}, trimmed
         * @throws IllegalStateException when {@code dynamics.oauth.client-id} is blank
         */
        public String requireClientId() {
            String clientId = oauth == null ? null : oauth.clientId();
            if (isBlank(clientId)) {
                throw new IllegalStateException("dynamics.oauth.client-id is not configured");
            }
            return clientId.trim();
        }

        /**
         * @return {@code dynamics.oauth.client-secret}, trimmed
         * @throws IllegalStateException when {@code dynamics.oauth.client-secret} is blank
         */
        public String requireClientSecret() {
            String clientSecret = oauth == null ? null : oauth.clientSecret();
            if (isBlank(clientSecret)) {
                throw new IllegalStateException("dynamics.oauth.client-secret is not configured");
            }
            return clientSecret.trim();
        }

        private static boolean isEntraHost(String uri) {
            try {
                return ENTRA_HOST.equalsIgnoreCase(URI.create(uri).getHost());
            } catch (IllegalArgumentException unparsable) {
                return false;
            }
        }
    }

    /**
     * Builds the reactor-netty connector shared by the token and Dataverse WebClients, over the client
     * {@link #httpClient(DynamicsProperties)} configures: both timeouts applied and one transport attempt per call.
     *
     * @param properties the bound {@code dynamics.*} settings
     * @return a connector with both timeouts applied and Reactor Netty's transport retry disabled
     * @throws IllegalStateException when a configured timeout is shorter than 1 ms, which would disable it
     */
    public static ReactorClientHttpConnector connector(DynamicsProperties properties) {
        return new ReactorClientHttpConnector(httpClient(properties));
    }

    /**
     * Builds the reactor-netty client of every token and Dataverse call: connect timeout
     * {@code dynamics.connect-timeout} and response timeout {@code dynamics.read-timeout}, each 10 s when unset. An
     * expired response timeout surfaces as a {@code WebClientRequestException} caused by Netty's
     * {@code ReadTimeoutException}, which the Dynamics failure classifier reports as unavailable.
     *
     * <p>Every call makes exactly one transport attempt. Reactor Netty by default sends a request again, on a new
     * connection, when its connection is reset before the request headers were written; that retry is disabled, so a
     * connectivity failure reaches the failure classifier the first time it occurs and is never retried, not even
     * silently inside the transport.
     *
     * <p>Netty applies both timeouts in whole milliseconds, which is why {@link #positiveTimeout} rejects anything
     * shorter than 1 ms. A connect timeout beyond {@link Integer#MAX_VALUE} ms, the most
     * {@link ChannelOption#CONNECT_TIMEOUT_MILLIS} holds, is capped there, and a response timeout beyond
     * {@link Long#MAX_VALUE} ms, the most Reactor Netty's per-request millisecond conversion holds, is capped there,
     * so no setting overflows.
     *
     * @param properties the bound {@code dynamics.*} settings
     * @return the configured client
     * @throws IllegalStateException when a configured timeout is shorter than 1 ms
     */
    static HttpClient httpClient(DynamicsProperties properties) {
        Duration connectTimeout = positiveTimeout(properties.connectTimeout(), "dynamics.connect-timeout");
        Duration readTimeout = positiveTimeout(properties.readTimeout(), "dynamics.read-timeout");
        int connectTimeoutMillis = connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) >= 0
                ? Integer.MAX_VALUE
                : (int) connectTimeout.toMillis();
        return HttpClient.create()
                .disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(readTimeout.compareTo(MAX_READ_TIMEOUT) > 0 ? MAX_READ_TIMEOUT : readTimeout);
    }

    /**
     * The repository of the single {@value #REGISTRATION_ID} registration: Entra client credentials, the client ID
     * and secret sent in the form body ({@code client_secret_post}), the effective token URI and scope.
     *
     * <p>The registration is built inside the returned {@link Mono} on every lookup, so blank credentials raise
     * their {@link IllegalStateException} at the first token or Dataverse call, never while the context starts.
     *
     * @param properties the bound {@code dynamics.*} settings
     * @return the repository; any other registration ID resolves to an empty {@link Mono}
     */
    @Bean
    public ReactiveClientRegistrationRepository dynamicsClientRegistrationRepository(DynamicsProperties properties) {
        return registrationId -> REGISTRATION_ID.equals(registrationId)
                ? Mono.fromCallable(() -> ClientRegistration.withRegistrationId(REGISTRATION_ID)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .clientId(properties.requireClientId())
                        .clientSecret(properties.requireClientSecret())
                        .tokenUri(properties.effectiveTokenUri())
                        .scope(properties.effectiveScope())
                        .build())
                : Mono.empty();
    }

    /**
     * The in-memory store of the Entra token, keyed by registration ID and {@link #PRINCIPAL_NAME}. Its presence
     * makes Spring Boot's default authorized-client service back off.
     *
     * @param repository the {@value #REGISTRATION_ID} registration repository
     * @return the authorized-client service {@code client/DynamicsWebApiClient} evicts the token through
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientService dynamicsAuthorizedClientService(
            ReactiveClientRegistrationRepository repository) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(repository);
    }

    /**
     * The client-credentials manager: it returns the cached token while it is valid (with the provider's default
     * 60 s clock skew) and otherwise POSTs {@code grant_type=client_credentials}, {@code scope}, {@code client_id}
     * and {@code client_secret} to the token endpoint and caches the result, so one token serves many Dataverse
     * calls.
     *
     * <p>The token request uses its own WebClient, built here rather than published as a bean so that the
     * Dataverse WebClient stays the only {@link WebClient} bean, and carrying no OAuth filter, which would recurse.
     * Its one filter, {@link #preserveTokenErrorStatus}, turns an Entra 401, 429 or 5xx response into a
     * {@link WebClientResponseException} carrying the status, the headers ({@code Retry-After} included) and the
     * body, because Spring Security reads every token response as an OAuth 2.0 body without checking the status
     * and would otherwise report it as an {@code OAuth2AuthorizationException} without status or headers. A
     * rejected token request ({@code 401}, for example Entra's {@code invalid_client}) is thereby classified as an
     * authentication failure whatever its body, even one Spring Security could not read; a throttled one as a rate
     * limit with Entra's {@code Retry-After}, never retried; an Entra 5xx is rethrown unchanged. Every other
     * response, Entra's 400 OAuth 2.0 errors such as {@code unauthorized_client} or {@code invalid_grant} included,
     * still reaches Spring Security, which raises the authorization exception the classifier reads as an
     * authentication failure.
     *
     * @param repository the {@value #REGISTRATION_ID} registration repository
     * @param service    the authorized-client service that caches the token
     * @param properties the bound {@code dynamics.*} settings, for the token call's timeouts
     * @return the authorized-client manager
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientManager dynamicsAuthorizedClientManager(
            ReactiveClientRegistrationRepository repository,
            ReactiveOAuth2AuthorizedClientService service,
            DynamicsProperties properties) {
        WebClientReactiveClientCredentialsTokenResponseClient tokenClient =
                new WebClientReactiveClientCredentialsTokenResponseClient();
        tokenClient.setWebClient(WebClient.builder()
                .clientConnector(connector(properties))
                .filter(ExchangeFilterFunction.ofResponseProcessor(DynamicsOAuthConfig::preserveTokenErrorStatus))
                .build());

        ReactiveOAuth2AuthorizedClientProvider provider = ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
                .build();

        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(repository, service);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * The Dataverse Web API client, the only {@link WebClient} bean: Spring Boot's builder (and so its Jackson
     * codecs), the 10 s connector, the {@value #REGISTRATION_ID} bearer token and the default headers
     * {@code OData-MaxVersion: 4.0}, {@code OData-Version: 4.0} and {@code Accept: application/json}.
     *
     * <p>No base URL is set: {@code dynamics.service-url} may be blank at start-up, so callers build absolute URIs
     * from {@link DynamicsProperties#requireServiceUrl()}.
     *
     * <p>A filter built from a manager does not remove the authorized client when the resource server answers 401
     * (its response handling is a no-op), so the cached token would be sent again on the retry.
     * {@code client/DynamicsWebApiClient} therefore evicts it explicitly with
     * {@code ReactiveOAuth2AuthorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME)} as the
     * re-authentication step before the single retry after a 401.
     *
     * @param builder    Spring Boot's prototype WebClient builder
     * @param manager    the client-credentials authorized-client manager
     * @param properties the bound {@code dynamics.*} settings, for the timeouts
     * @return the Dataverse WebClient
     */
    @Bean
    public WebClient dynamicsWebClient(
            WebClient.Builder builder,
            ReactiveOAuth2AuthorizedClientManager manager,
            DynamicsProperties properties) {
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth =
                new ServerOAuth2AuthorizedClientExchangeFilterFunction(manager);
        oauth.setDefaultClientRegistrationId(REGISTRATION_ID);
        return builder
                .clientConnector(connector(properties))
                .filter(oauth)
                .defaultHeader("OData-MaxVersion", ODATA_VERSION)
                .defaultHeader("OData-Version", ODATA_VERSION)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * The response processor of the token WebClient: fails an unauthorized ({@code 401}), throttled ({@code 429}) or
     * server-error ({@code 5xx}) Entra token response with the {@link WebClientResponseException}
     * {@link ClientResponse#createException()} builds from it, which carries the status, the headers and the body,
     * before Spring Security's OAuth 2.0 body extractor discards them.
     *
     * <p>A 401 is kept with its status because the extractor reads an empty or non-JSON 401 body, such as a proxy's
     * HTML page, as an unreadable token response rather than a rejection; with its status kept, every token 401 is
     * an authentication failure whatever its body says. A 400, Entra's status for OAuth 2.0 errors such as
     * {@code unauthorized_client} or {@code invalid_grant}, and every other status pass through to Spring Security.
     *
     * @param response the token endpoint's response
     * @return the response itself for every other status, otherwise an error with the response exception
     */
    private static Mono<ClientResponse> preserveTokenErrorStatus(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == HttpStatus.UNAUTHORIZED.value()
                || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                || status.is5xxServerError()) {
            return response.createException().flatMap(Mono::error);
        }
        return Mono.just(response);
    }

    /**
     * Validates one timeout of {@link #httpClient(DynamicsProperties)}.
     *
     * @param configured the bound timeout; {@code null} when the key is unset
     * @param key        the property key, named in the exception
     * @return the configured timeout, or the 10 s default when it is {@code null}
     * @throws IllegalStateException when the timeout is shorter than 1 ms: zero or negative, or a positive fraction
     *                               of a millisecond, which Netty would truncate to 0 and so disable
     */
    private static Duration positiveTimeout(Duration configured, String key) {
        if (configured == null) {
            return DEFAULT_TIMEOUT;
        }
        if (configured.compareTo(MIN_TIMEOUT) < 0) {
            throw new IllegalStateException(
                    key + " must be a positive duration of at least 1 ms, but was " + configured);
        }
        return configured;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Trims the value and removes every trailing {@code /}; {@code null} gives the empty string. */
    private static String stripTrailingSlashes(String value) {
        String trimmed = value == null ? "" : value.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }
}

