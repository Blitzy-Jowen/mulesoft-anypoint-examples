package com.mulesoft.examples.get_customer_list_from_netsuite.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NetSuite connection settings, bound from the {@code netsuite.*} keys of {@code application.yml}.
 *
 * <p>Shared component SC-16 (configuration part), together with {@code NetsuiteTokenProvider},
 * {@code NetsuiteRestClient} and {@code NetsuiteFailureClassifier}. This project holds the reference copy;
 * {@code netsuite-data-retrieval-java} carries an identical copy in which only the {@code package} line differs, so
 * the class holds every setting both projects read and depends on nothing but the JDK and Spring.
 *
 * <p>Replaces the Mule global configuration
 * {@code <netsuite:config account="${netsuite.account}" email="${netsuite.email}" password="${netsuite.password}"
 * roleId="${netsuite.roleId}"/>}, which logged in to SuiteTalk SOAP. The conversion calls the NetSuite REST API and
 * SuiteQL with OAuth 2.0 machine-to-machine authentication (client credentials grant with a PS256 JWT client
 * assertion), so the keys map as follows:
 * <ul>
 *   <li>{@code netsuite.account} is kept;</li>
 *   <li>{@code netsuite.email}, {@code netsuite.password} and {@code netsuite.roleId} are replaced by
 *       {@code netsuite.oauth.client-id}, {@code netsuite.oauth.certificate-id} and
 *       {@code netsuite.oauth.private-key-path};</li>
 *   <li>{@code netsuite.time-zone} (default {@code America/Los_Angeles}) and {@code netsuite.custom-field-type-ids}
 *       (empty by default) are added; only {@code netsuite-data-retrieval} reads them, for record-shape dates and
 *       the select {@code typeId} override.</li>
 * </ul>
 *
 * <p>A missing or invalid account, OAuth or base-URL value fails late: the application context starts with every
 * credential placeholder empty, and only a call that needs a value ({@link #accountHost()},
 * {@link #resolvedBaseUrl()}, {@link #tokenUrl()}, the {@code require...} methods, and {@code NetsuiteTokenProvider}
 * for a private key it cannot load) throws {@link IllegalStateException} naming the missing or invalid key. An
 * account that is not a single DNS label and a base URL that is not an absolute {@code http}/{@code https} URL with a
 * host and no user info, query or fragment are rejected there, before any URL is built from them, so neither can
 * redirect the token request or the API calls; the message never repeats the configured value.
 *
 * <p>The timeouts and the time zone are checked at start-up instead. A value that does not convert to a
 * {@link Duration} or a {@link ZoneId} fails binding, which stops start-up. A timeout shorter than 1 ms, or longer
 * than {@link Integer#MAX_VALUE} milliseconds (connect) or {@link Long#MAX_VALUE} milliseconds (read), stops start-up
 * with an {@link IllegalStateException} naming the key when {@code NetsuiteTokenProvider} and
 * {@code NetsuiteRestClient} are created, because both build their transport with
 * {@code NetsuiteRestClient.connector(NetsuiteProperties)}, which checks the range. The class itself carries no
 * validation annotation and no start-up check: the account, OAuth and base-URL checks wait for first use, and the
 * timeout range check runs in the clients.
 */
@Component
@ConfigurationProperties(prefix = "netsuite")
public class NetsuiteProperties {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("America/Los_Angeles");

    private static final String ACCOUNT_DOMAIN_SUFFIX = ".suitetalk.api.netsuite.com";

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    /** One DNS label: 1 to 63 lower-case letters, digits or {@code -}, neither first nor last a {@code -}. */
    private static final Pattern ACCOUNT_HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private String account;

    private String baseUrl;

    private Duration connectTimeout = DEFAULT_TIMEOUT;

    private Duration readTimeout = DEFAULT_TIMEOUT;

    private ZoneId timeZone = DEFAULT_TIME_ZONE;

    private Map<String, String> customFieldTypeIds = new LinkedHashMap<>();

    private OAuth oauth = new OAuth();

    /**
     * @return {@code netsuite.account}, the NetSuite account ID (for example {@code 1234567} or
     *         {@code 1234567_SB1}) exactly as configured, or {@code null} when not set
     */
    public String getAccount() {
        return account;
    }

    /**
     * @param account the NetSuite account ID, stored as given
     */
    public void setAccount(String account) {
        this.account = account;
    }

    /**
     * @return {@code netsuite.base-url} exactly as configured; blank means the URL is derived from the account
     * @see #resolvedBaseUrl()
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * @param baseUrl scheme and host of the NetSuite REST services, stored as given; tests point it at WireMock
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * @return {@code netsuite.connect-timeout}, the connect timeout of every NetSuite call (default 10 s, the Mule
     *         default response timeout)
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @param connectTimeout the connect timeout; {@code null} restores the 10 s default
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_TIMEOUT;
    }

    /**
     * @return {@code netsuite.read-timeout}, the read (response) timeout of every NetSuite call (default 10 s, the
     *         Mule default response timeout)
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * @param readTimeout the read timeout; {@code null} restores the 10 s default
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout != null ? readTimeout : DEFAULT_TIMEOUT;
    }

    /**
     * @return {@code netsuite.time-zone}, the account time zone used to render NetSuite dates and date-times
     *         (default {@code America/Los_Angeles})
     */
    public ZoneId getTimeZone() {
        return timeZone;
    }

    /**
     * @param timeZone the account time zone; {@code null} restores the {@code America/Los_Angeles} default
     */
    public void setTimeZone(ZoneId timeZone) {
        this.timeZone = timeZone != null ? timeZone : DEFAULT_TIME_ZONE;
    }

    /**
     * Returns {@code netsuite.custom-field-type-ids}: custom select field script ID → internal ID of the field's
     * select source (custom list or record type), for example {@code custentity8} → {@code 16}. A non-blank entry
     * takes precedence over the SuiteQL {@code customfield} lookup that otherwise supplies the {@code typeId} of a
     * select value; see {@link #customFieldTypeIdOverride(String)}.
     *
     * <p>Spring Boot strips every map-key character other than alphanumerics, {@code -} and {@code .} unless the key
     * is bracketed, so a script ID containing {@code _} must be written in bracket notation, for example
     * {@code netsuite.custom-field-type-ids: { "[custentity_fmt_cur_sales_order]": "-30" }}.
     *
     * @return the bound map in configuration order, never {@code null}
     */
    public Map<String, String> getCustomFieldTypeIds() {
        return customFieldTypeIds;
    }

    /**
     * @param customFieldTypeIds script ID → select source ID entries, copied; {@code null} leaves an empty map
     */
    public void setCustomFieldTypeIds(Map<String, String> customFieldTypeIds) {
        this.customFieldTypeIds = customFieldTypeIds != null
                ? new LinkedHashMap<>(customFieldTypeIds)
                : new LinkedHashMap<>();
    }

    /**
     * @return the {@code netsuite.oauth.*} credentials of the OAuth 2.0 M2M integration, never {@code null}
     */
    public OAuth getOauth() {
        return oauth;
    }

    /**
     * @param oauth the OAuth 2.0 M2M credentials; {@code null} leaves an empty instance
     */
    public void setOauth(OAuth oauth) {
        this.oauth = oauth != null ? oauth : new OAuth();
    }

    /**
     * Returns the account in NetSuite's account-specific domain form: trimmed, lower-cased and with {@code _}
     * replaced by {@code -} ({@code 1234567_SB1} → {@code 1234567-sb1}). The result must be a single DNS label (1 to
     * 63 letters, digits or {@code -}, starting and ending with a letter or digit), so that it can only name a
     * subdomain of {@code suitetalk.api.netsuite.com}; a value carrying {@code .}, {@code /}, {@code @}, {@code :},
     * whitespace or any other character is rejected.
     *
     * @return the account host label
     * @throws IllegalStateException when {@code netsuite.account} is not configured, or when it does not form a
     *         single DNS label; the message names the key and never repeats the configured value
     */
    public String accountHost() {
        String value = account == null ? "" : account.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("netsuite.account is not configured");
        }
        String host = value.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!ACCOUNT_HOST_LABEL.matcher(host).matches()) {
            throw new IllegalStateException("netsuite.account is not a valid NetSuite account ID: it must form a"
                    + " single DNS label of at most 63 letters, digits, '-' or '_', starting and ending with a letter"
                    + " or digit");
        }
        return host;
    }

    /**
     * Returns the base URL every NetSuite call is resolved against: {@code netsuite.base-url} trimmed and without
     * trailing {@code /} when it is set, otherwise {@code https://<accountHost>.suitetalk.api.netsuite.com}. A value
     * made only of slashes is treated as not set. A set value must be an absolute {@code http} or {@code https} URL
     * with a host and no user info, query or fragment; a path is allowed, and {@code http} is accepted for any host
     * so that tests can point it at a local WireMock server.
     *
     * @return the base URL, without a trailing {@code /}
     * @throws IllegalStateException when {@code netsuite.base-url} is set but is not such a URL, or when the URL has
     *         to be derived and {@code netsuite.account} is not configured or invalid (see {@link #accountHost()});
     *         the message names the key and never repeats the configured value
     */
    public String resolvedBaseUrl() {
        if (baseUrl != null) {
            String url = baseUrl.trim();
            int end = url.length();
            while (end > 0 && url.charAt(end - 1) == '/') {
                end--;
            }
            if (end > 0) {
                String stripped = url.substring(0, end);
                if (!isHttpBaseUrl(stripped)) {
                    throw new IllegalStateException("netsuite.base-url is not a valid base URL: it must be an"
                            + " absolute http or https URL with a host and no user info, query or fragment");
                }
                return stripped;
            }
        }
        return "https://" + accountHost() + ACCOUNT_DOMAIN_SUFFIX;
    }

    /**
     * Returns the OAuth 2.0 token URL, {@code <resolvedBaseUrl>/services/rest/auth/oauth2/v1/token}. It is both the
     * endpoint the client credentials grant is POSTed to and the {@code aud} claim of the JWT client assertion.
     *
     * @return the token URL
     * @throws IllegalStateException when {@code netsuite.base-url} is set but invalid, or when the base URL has to be
     *         derived and {@code netsuite.account} is not configured or invalid; the message names the key and never
     *         repeats the configured value
     */
    public String tokenUrl() {
        return resolvedBaseUrl() + TOKEN_PATH;
    }

    /**
     * @return the trimmed {@code netsuite.oauth.client-id}, the {@code iss} claim of the client assertion
     * @throws IllegalStateException when it is not configured
     */
    public String requireClientId() {
        return required(oauth.getClientId(), "netsuite.oauth.client-id");
    }

    /**
     * @return the trimmed {@code netsuite.oauth.certificate-id}, the {@code kid} header of the client assertion
     * @throws IllegalStateException when it is not configured
     */
    public String requireCertificateId() {
        return required(oauth.getCertificateId(), "netsuite.oauth.certificate-id");
    }

    /**
     * @return the trimmed {@code netsuite.oauth.private-key-path}, the location of the PS256 signing key
     * @throws IllegalStateException when it is not configured
     */
    public String requirePrivateKeyPath() {
        return required(oauth.getPrivateKeyPath(), "netsuite.oauth.private-key-path");
    }

    /**
     * Returns the configured select source ID for a custom field, the entry of {@link #getCustomFieldTypeIds()}
     * that wins over the SuiteQL {@code customfield} lookup. The script ID is matched exactly first and then
     * case-insensitively, because SuiteQL returns {@code customfield.scriptid} upper-case while REST record field
     * names are lower-case. Blank entries count as absent.
     *
     * @param scriptId the custom field script ID, for example {@code custentity8}
     * @return the trimmed source ID, or empty for a {@code null} or blank script ID, a missing entry or a blank value
     */
    public Optional<String> customFieldTypeIdOverride(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        String key = scriptId.trim();
        Optional<String> exact = nonBlank(customFieldTypeIds.get(key));
        if (exact.isPresent()) {
            return exact;
        }
        String folded = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : customFieldTypeIds.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(folded)) {
                Optional<String> value = nonBlank(entry.getValue());
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        return Optional.empty();
    }

    private static String required(String value, String key) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException(key + " is not configured");
        }
        return trimmed;
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * Whether a value parses as an absolute {@code http} or {@code https} URI (scheme compared case-insensitively)
     * with a host and without user info, query or fragment. A {@link URISyntaxException} counts as invalid and is
     * not propagated, because its message repeats the input.
     *
     * @param value the trimmed base URL without trailing {@code /}
     * @return {@code true} when the value is such a URL
     */
    private static boolean isHttpBaseUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        return uri.isAbsolute()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }

    /**
     * The {@code netsuite.oauth.*} credentials of the NetSuite OAuth 2.0 client credentials (M2M) integration. None
     * has a default; each comes only from configuration.
     */
    public static class OAuth {

        private String clientId;

        private String certificateId;

        private String privateKeyPath;

        /**
         * @return {@code netsuite.oauth.client-id}, the client ID of the integration record, or {@code null}
         */
        public String getClientId() {
            return clientId;
        }

        /**
         * @param clientId the client ID of the integration record
         */
        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        /**
         * @return {@code netsuite.oauth.certificate-id}, the ID NetSuite assigned to the uploaded certificate, or
         *         {@code null}
         */
        public String getCertificateId() {
            return certificateId;
        }

        /**
         * @param certificateId the ID NetSuite assigned to the uploaded certificate
         */
        public void setCertificateId(String certificateId) {
            this.certificateId = certificateId;
        }

        /**
         * @return {@code netsuite.oauth.private-key-path}, the file or {@code classpath:} location of the PKCS#8 PEM
         *         private key of that certificate, or {@code null}
         */
        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        /**
         * @param privateKeyPath the file or {@code classpath:} location of the PKCS#8 PEM private key
         */
        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }
    }
}
