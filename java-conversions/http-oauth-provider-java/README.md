# HTTP OAuth2 Provider Example — Spring Boot conversion

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.6 example [`http-oauth-provider`](../../http-oauth-provider/README.md) (category General). The original folder stays unchanged beside this project and is the Tier 2A capture source.

The provider is hand-written over Spring MVC: no Spring Authorization Server and no Spring Security, so the original paths `/authorize` and `/token` stay as they were (AAP 0.6.4). Clients, authorization codes and access tokens live in in-memory stores and are lost on restart, as the provider module's default stores were (AAP 0.4.3). The application reaches no database, broker, file store or upstream service.

## Original example

The original configures an Anypoint Enterprise Security OAuth 2.0 provider (`oauth2-provider:config`, provider name `SampleAPI`) and two flows in `http-oauth-provider/src/main/app/http-oauth-provider.xml`: `protectedAuthcodeFlow`, which serves a payroll resource only to a valid bearer token, and `redirectFlow`, a demonstration helper that completes the OAuth dance in a browser. A user opens the provider's authorization URL, the resource owner `mule` logs in, the provider issues an authorization code and then an access token, and that token grants access to the payroll resource, as the [original README](../../http-oauth-provider/README.md) walks through.

## Endpoints and triggers

Two Undertow listeners, both bound to `localhost`, as the original's `http:listener-config host="localhost"` and provider module were (`http-oauth-provider.xml:26,43`; AAP 0.4.9):

- the provider listener, port `http.provider.port` (default `8081`), the SC-10 additional listener `provider`, serving `/authorize` and `/token`;
- the resource listener, port `http.listener.port` (default `8082`), the main `server.port`, serving `/resources` and `/redirect`.

| Port | Path | Methods | Behaviour | Response |
|---|---|---|---|---|
| provider | `/authorize?response_type=code&client_id=..&redirect_uri=..&scope=..[&state=..]` | `GET` (and `HEAD`) | a valid authorization request: `response_type=code` for a client authorized for `AUTHORIZATION_CODE`, the configured client ID, a redirect URI matching `http://localhost*` (`*` matches any characters), and only configured scopes (`READ_RESOURCE`, `POST_RESOURCE`) | `200 OK`, `text/html;charset=UTF-8`, the login page `templates/login.html`: title and heading `SampleAPI`, the line `Mule Bookstore (Mule-powered On-line Bookstore) is requesting access to your account.`, fields `username` and `password`, button `Login and Authorize`, and the five request parameters as hidden fields |
| provider | `/authorize` | `GET`, `POST` | an invalid authorization request, checked in this order: `response_type` not `code`, or the client not authorized for `AUTHORIZATION_CODE` → `unsupported_response_type` (`Unsupported response_type`); unknown client → `invalid_client` (`Invalid client id`); missing redirect URI or one outside `http://localhost*` → `invalid_request` (`Invalid redirect_uri`); a scope that is not configured → `invalid_scope` (`Invalid scope`) | `400 Bad Request`, `application/json`, `{"error":"<code>","error_description":"<text>"}`; the error is answered to the caller and never redirected to an unvalidated URI |
| provider | `/authorize` (form: the hidden fields plus `username`, `password`) | `POST` | the request is validated again, then the resource owner is checked; accepted: a single-use code is issued | `302 Found`, `Location: <redirect_uri>?code=<code>[&state=<state>]` (`&code=` when the redirect URI already has a query; `state` form-encoded, only when non-empty), empty body |
| provider | `/authorize` (form) | `POST` | the owner's username or password is wrong, missing or blank | `401 Unauthorized`, `text/html;charset=UTF-8`, the login page again with `Invalid username or password` and the hidden fields kept |
| provider | `/token` (parameters in the query or an `application/x-www-form-urlencoded` body) | `GET` (and `HEAD`), `POST` | `grant_type=authorization_code`, `code`, `redirect_uri`; the client authenticates with `client_id` and `client_secret` parameters (query or body) or with HTTP Basic, the three locations the original README lists. `GET` is served because the browser follows the `/redirect` hop with a GET; the empty parameter between `&&` is never bound | `200 OK`, `application/json`, `Cache-Control: no-store`, `Pragma: no-cache`, `{"access_token":"<token>","token_type":"bearer","expires_in":86400,"scope":"<granted scopes, space-separated>"}` |
| provider | `/token` | `GET`, `POST` | a rejected exchange, checked in this order: no `grant_type` → `invalid_request` (`Missing parameter: grant_type`); another grant type → `unsupported_grant_type` (`Unsupported grant_type`); a Basic header together with a `client_secret` parameter → `invalid_request` (`Multiple client authentication methods`); client authentication fails → `invalid_client` (`Invalid client credentials`); no `code` → `invalid_request` (`Missing parameter: code`); code unknown, expired, already used or issued to another client → `invalid_grant` (`Invalid authorization code`); `redirect_uri` missing or different from the code's → `invalid_grant` (`redirect_uri mismatch`) | `400 Bad Request` (`401 Unauthorized` for `invalid_client`, with `WWW-Authenticate: Basic realm="SampleAPI"` when the client used Basic), `application/json`, `{"error":"<code>","error_description":"<text>"}`. A code that reaches the code check is consumed whatever the outcome, so it can never be exchanged twice |
| listener | `/resources` | any (`HEAD` without a body; `OPTIONS` and `TRACE` run the flow too) | `Authorization: Bearer <token>` holding `READ_RESOURCE`; the token is not consumed and opens the resource until it expires | `200 OK`, `application/json`, `{"name":"payroll","uri":"http://localhost:8082/resources/payroll"}` (the port is `http.listener.port`) |
| listener | `/resources` | any | no `Authorization` header, another scheme, or a blank token | `401 Unauthorized`, `application/json`, `WWW-Authenticate: Bearer realm="SampleAPI"`, `{ "message": "Access token missing" }` |
| listener | `/resources` | any | a token never issued or expired | `401 Unauthorized`, `application/json`, `WWW-Authenticate: Bearer realm="SampleAPI", error="invalid_token"`, `{ "message": "Invalid or expired access token" }` |
| listener | `/resources` | any | a valid token without `READ_RESOURCE` (for example one granted `POST_RESOURCE` only) | `403 Forbidden`, `application/json`, `WWW-Authenticate: Bearer realm="SampleAPI", error="insufficient_scope", scope="READ_RESOURCE"`, `{ "message": "Insufficient scope: READ_RESOURCE required" }` |
| listener | `/redirect[?code=<code>]` | any | the demonstration hop of `redirectFlow`, kept as a demo-only helper as in the original | `302 Found`, `Location: http://localhost:8081/token?grant_type=authorization_code&&client_id=<client-id>&client_secret=<client-secret>&code=<code>&redirect_uri=http://localhost:8082/redirect` byte for byte, the double `&&` and the unencoded values included (ports from `http.provider.port` and `http.listener.port`); a missing `code` is written `code=null`; `Content-Length: 0`, no `Content-Type`, empty body |
| either | a path the receiving port does not serve: `/unknown`, `/resources/payroll`, `/token` on the listener port, `/resources` on the provider port | any | no listener serves it (SC-10 `PortRoutingFilter`; SC-1 for a path no handler serves) | `404 Not Found`, `text/plain`, `No listener for endpoint: <path>` |
| provider | `/authorize`, `/token` | any other (`PUT`, `DELETE`, `PATCH`, `OPTIONS`, `TRACE`) | the provider endpoints accept `GET`, `HEAD` and `POST` only | `405 Method Not Allowed`, `text/plain`, `Method not allowed for endpoint: <path>`, no `Allow` header (SC-1) |
| either | any served path | any | any other unhandled exception | `500 Internal Server Error`, `application/json`, `{ "message": "<exception message>" }`, one ERROR log line naming the route, never the request path (SC-1) |

Notes:

- The bearer token is read from the `Authorization` header only (scheme matched ignoring case). An `access_token` query parameter is not accepted: what the original's `oauth2-provider:validate` did with one is unverified, so it is a Tier 2A verification point.
- An absent or blank `scope` on `/authorize` requests no scope; the token it yields carries none and gets the 403 on `/resources`.
- Codes are single-use and valid for 600 s; tokens are valid for 86400 s. Both are 32 random bytes from `SecureRandom`, URL-safe Base64 without padding.
- Listener paths match exactly, as the Mule listener paths did: the payroll `uri` the resource advertises is not itself served.
- A malformed percent escape in a query string is answered by Undertow with `400 Bad Request` and an empty body before any controller runs (root `DECISIONS.md`, section 9, verification points).
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request: the flow on `/resources` and `/redirect`, the 405 on `/authorize` and `/token`. No `Access-Control-*` or `Vary` header is added (`config/PreflightDispatchConfig`); the original applied no CORS policy.
- `TRACE` is dispatched to the controllers (`spring.mvc.dispatch-trace-request: true`), so it runs the flow or gets the 405, never the servlet's echo of the request and its `Authorization` header.
- A request body the application leaves unread is drained after the response, so requests pipelined on one HTTP/1.1 connection are all answered, as the Mule listener (Grizzly) answered them (`config/RequestBodyDrainFilter`, registered ahead of `PortRoutingFilter` by `config/RequestBodyDrainRegistration`).

### Walkthrough

The original README's steps, on the default ports. Start the application with the demo values (Build, run and test below), then:

1. Open in a browser: `http://localhost:8081/authorize?response_type=code&client_id=<client-id>&scope=READ_RESOURCE&redirect_uri=http://localhost:8082/redirect` (`<client-id>` is `myclientid` under profile `test`).
2. The `SampleAPI` login page appears. Log in as the configured resource owner (`mule`/`mule` under profile `test`) and click `Login and Authorize`.
3. The browser follows `302` to `/redirect?code=<code>`, then `302` to `/token`, and shows the token JSON. Copy the value of `access_token`.
4. `curl -H "Authorization: Bearer <token>" http://localhost:8082/resources` answers `{"name":"payroll","uri":"http://localhost:8082/resources/payroll"}`.

The same dance with curl alone, under profile `test` (the original demo client `myclientid`/`myclientsecret` and owner `mule`/`mule`); codes and tokens vary, and only the status line, `Location`, cache, `Content-Type` and `Content-Length` headers are shown:

```console
$ curl -i -d 'response_type=code&client_id=myclientid&redirect_uri=http://localhost:8082/redirect&scope=READ_RESOURCE&username=mule&password=mule' http://localhost:8081/authorize
HTTP/1.1 302 Found
Location: http://localhost:8082/redirect?code=<code>
Content-Length: 0

$ curl -i 'http://localhost:8082/redirect?code=<code>'
HTTP/1.1 302 Found
Location: http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret&code=<code>&redirect_uri=http://localhost:8082/redirect
Content-Length: 0

$ curl -i 'http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret&code=<code>&redirect_uri=http://localhost:8082/redirect'
HTTP/1.1 200 OK
Cache-Control: no-store
Pragma: no-cache
Content-Type: application/json

{"access_token":"<token>","token_type":"bearer","expires_in":86400,"scope":"READ_RESOURCE"}

$ curl -i -H 'Authorization: Bearer <token>' http://localhost:8082/resources
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 67

{"name":"payroll","uri":"http://localhost:8082/resources/payroll"}
```

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to: the `oauth.provider.*` keys bind to `config/OAuthProviderProperties` (its nested `Owner` record for `owner.*`), the `listeners.*` keys to `config/ListenerPorts`, the two original ports to `int` arguments of `service/OAuthProviderService`, and `server.*`, `spring.*` and `logging.level.*` to Spring Boot's own properties. Secrets have no value: each is `${ENV_NAME:}` with a `# TODO: supply …` comment (AAP 0.4.6).

`Original name` is the original's property, or the literal the key replaces, with its line in `mule-app.properties` or `http-oauth-provider.xml` (`xml:`); `none` marks a key new to the conversion. Status: `default` — a non-secret value, overridable; `TODO (secret)` — no value until supplied through the named environment variable or a `--key=value` argument; `fixed` — a setting that reproduces the original listeners, not a key of the original, so changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.provider.port` | `int` | `8081` | `http.provider.port` (unchanged), `8081` at `mule-app.properties:1`; read by `oauth2-provider:config port` (`xml:26`) and the `/redirect` `Location` (`xml:56`) | default | provider listener (`/authorize`, `/token`), opened through `listeners.additional[0].port`; also the port in the `/redirect` `Location` |
| `http.listener.port` | `int` | `8082` | `http.listener.port` (unchanged), `8082` at `mule-app.properties:2`; read by `http:listener-config port` (`xml:43`), the payroll `uri` (`xml:47`) and the `/redirect` `Location` (`xml:56`) | default | resource listener (`/resources`, `/redirect`), opened through `server.port`; also the port in the payroll `uri` and the `/redirect` `Location` |
| `server.port` | `Integer` | `${http.listener.port}`, so `8082` | none | fixed | the resource listener is the main Undertow port; set `http.listener.port`, not this key |
| `server.address` | `InetAddress` | `localhost` | none | fixed | bind host of the resource listener, `http:listener-config host="localhost"` (`xml:43`) |
| `listeners.host` | `String` | `localhost` | none | fixed | default bind host of the additional listeners (SC-10); an entry's own `host` takes precedence, and with neither set the listener binds `0.0.0.0` |
| `listeners.disallowed-methods` | `List<String>` | `[]` | none | fixed | methods Undertow would reject with `405` on every listener; empty, so neither listener restricts methods at the Undertow level, as neither original `http:listener` set `allowedMethods` (`xml:45,54`) |
| `listeners.main.routes[0].patterns` | `List<String>` | `/resources`, `/redirect` | none | fixed | the only paths the resource listener serves (SC-10 `PortRoutingFilter`), the original listener paths (`xml:45,54`); the route names no `servlet`, so `dispatcherServlet` serves them |
| `listeners.additional[0].name` | `String` | `provider` | none | fixed | label of the provider listener in log lines and start-up errors |
| `listeners.additional[0].port` | `int` | `${http.provider.port}`, so `8081` | none | fixed | port of the provider listener, the SC-10 additional Undertow listener, as `oauth2-provider:config port` (`xml:26`); set `http.provider.port`, not this key. A port outside 1–65535 stops start-up |
| `listeners.additional[0].host` | `String` | `localhost` | none | fixed | bind host of the provider listener; the original provider served `localhost` |
| `listeners.additional[0].routes[0].patterns` | `List<String>` | `/authorize`, `/token` | none | fixed | the only paths the provider listener serves: the provider module's authorization and token endpoints, at `/authorize` and `/token` as the original README shows them; the route names no `servlet`, so `dispatcherServlet` serves them |
| `oauth.provider.provider-name` | `String` | `SampleAPI` | literal `providerName="SampleAPI"` (`xml:26`) | default | login page title and the `WWW-Authenticate` realm |
| `oauth.provider.client-id` | `String` | none: `${OAUTH_PROVIDER_CLIENT_ID:}` | literal `clientId="myclientid"` (`xml:28`), repeated in the `/redirect` `Location` (`xml:56`) | TODO (secret), `OAUTH_PROVIDER_CLIENT_ID` | the registered client's ID |
| `oauth.provider.client-secret` | `String` | none: `${OAUTH_PROVIDER_CLIENT_SECRET:}` | literal `secret="myclientsecret"` (`xml:28`), repeated in the `/redirect` `Location` (`xml:56`) | TODO (secret), `OAUTH_PROVIDER_CLIENT_SECRET` | the registered client's secret |
| `oauth.provider.client-name` | `String` | `Mule Bookstore` | literal `clientName="Mule Bookstore"` (`xml:28`) | default | shown on the login page |
| `oauth.provider.client-description` | `String` | `Mule-powered On-line Bookstore` | literal `description="Mule-powered On-line Bookstore"` (`xml:28`) | default | shown on the login page |
| `oauth.provider.client-type` | `String` | `CONFIDENTIAL` | literal `type="CONFIDENTIAL"` (`xml:28`) | default | `PUBLIC` (compared ignoring case) would authenticate the client by its ID alone at `/token`; any other value requires the client secret |
| `oauth.provider.redirect-uris` | `List<String>` | `http://localhost*` | literal `oauth2-provider:redirect-uri` `http://localhost*` (`xml:30`) | default | redirect-URI patterns; `*` matches any characters, a pattern without `*` matches exactly |
| `oauth.provider.authorized-grant-types` | `List<String>` | `AUTHORIZATION_CODE` | literal `oauth2-provider:authorized-grant-type` `AUTHORIZATION_CODE` (`xml:33`) | default | grant types the client may use; without `AUTHORIZATION_CODE` (compared ignoring case) every authorization request gets `unsupported_response_type` |
| `oauth.provider.scopes` | `List<String>` | `READ_RESOURCE`, `POST_RESOURCE` | literals `scopes="READ_RESOURCE POST_RESOURCE"` of `oauth2-provider:config` (`xml:26`) and the client's two `oauth2-provider:scope` elements (`xml:36-37`), the same set | default | scopes the provider and the client declare; an authorization request for any other scope gets `invalid_scope` |
| `oauth.provider.resource-scope` | `String` | `READ_RESOURCE` | literal `oauth2-provider:validate scopes="READ_RESOURCE"` (`xml:46`) | default | scope a token must carry for `/resources` |
| `oauth.provider.token-ttl-seconds` | `long` | `86400` | none: the original sets no lifetime; the value is the conversion's reading of the provider module's default, a Tier 2A verification point | default | access-token lifetime in seconds, also `expires_in` |
| `oauth.provider.authorization-code-ttl-seconds` | `long` | `600` | none: the original sets no lifetime; the value is the conversion's reading of the provider module's default, a Tier 2A verification point | default | authorization-code lifetime in seconds |
| `oauth.provider.owner.username` | `String` | none: `${OAUTH_PROVIDER_OWNER_USERNAME:}` | literal `name="mule"` of the Spring Security `ss:user` (`xml:16`) | TODO (secret), `OAUTH_PROVIDER_OWNER_USERNAME` | the resource owner's username |
| `oauth.provider.owner.password` | `String` | none: `${OAUTH_PROVIDER_OWNER_PASSWORD:}` | literal `password="mule"` of the Spring Security `ss:user` (`xml:16`) | TODO (secret), `OAUTH_PROVIDER_OWNER_PASSWORD` | the resource owner's password |
| `oauth.provider.owner.authorities` | `List<String>` | `RESOURCE_OWNER` | literal `authorities="RESOURCE_OWNER"` of the Spring Security `ss:user` (`xml:16`) | default | the owner's authorities |

Fixed framework settings that reproduce the original listeners (not keys of the original; changing them changes behaviour):

| Key | Type | Value | Why |
|---|---|---|---|
| `spring.application.name` | `String` | `http-oauth-provider-java` | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | an unserved path reaches the no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | `TRACE` runs the flows of `/resources` and `/redirect` and gets the 405 on `/authorize` and `/token`, instead of the servlet's echo |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | below `INFO` it writes request lines with their query (`client_secret`, `code`, `access_token`), the redirect view's URL with its code and, with `spring.mvc.log-request-details`, the parameters and headers (`Authorization`, `Location`); `WARN`, not `INFO`, because its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation` | `LogLevel` | `INFO` | below `INFO` it writes response bodies, the token JSON among them, and resolved exception messages |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | below `INFO` it writes handler arguments: the owner's password, the client secret, the code and the `Authorization` header |
| `logging.level.io.undertow.request.io` | `LogLevel` | `INFO` | below `INFO` it writes request-parse failures quoting a query value that does not decode |

The four `logging.level` keys keep the client secret, codes, tokens and the owner's password, which the original never logged, out of the log: a logger's own level outranks `logging.level.root`, `--debug` and `--trace`.

- The original demo values (`myclientid`/`myclientsecret`, owner `mule`/`mule`) live only in `src/test/resources/application-test.yml`, Spring profile `test`, which surefire activates in every test JVM.
- Fail closed: while `oauth.provider.client-id` is blank every authorization request is rejected (`invalid_client` once its `response_type` passes); while the client ID or (for a `CONFIDENTIAL` client) the secret is blank every token request gets `invalid_client`; while the owner username or password is blank every login is rejected. At startup one WARN line names each blank key, never a value: `OAuth provider credentials are not configured (<keys>): authorization and token requests are rejected until they are supplied` (`config/OAuthProviderConfigurationCheck`).
- There is no `application-it.yml`: this is not a connector project, so it has no live-sandbox tier.

## Mule-to-Java mapping

| Mule element (`http-oauth-provider.xml`) | Java (`com.mulesoft.examples.http_oauth_provider`) |
|---|---|
| `oauth2-provider:config` `oauth2Provider`: provider, client store, code and token issuance (`xml:26-41`) | `config/OAuthProviderProperties` (`oauth.provider.*`), `service/OAuthProviderService`, `controller/AuthorizationController` (`/authorize`, login page `templates/login.html` rendered by Thymeleaf), `controller/TokenController` (`/token`) |
| `ss:authentication-manager` with its one `ss:user`, and the `mule-ss:security-manager` delegating to it (`xml:13-24`) | `service/ResourceOwnerAuthenticator` |
| `protectedAuthcodeFlow`: `http:listener` `/resources`, `oauth2-provider:validate scopes="READ_RESOURCE" throwExceptionOnUnaccepted="true"`, `set-payload`, `json:object-to-json-transformer` (`xml:44-49`) | `controller/ResourcesController` (`/resources`) over `OAuthProviderService.validateAccessToken` and `protectedResource` |
| `redirectFlow`: `http:listener` `/redirect`, `set-property` `http.status` 302 and `Location` (`xml:53-57`) | `controller/ResourcesController` (`/redirect`) over `OAuthProviderService.redirectLocation` |
| two ports: the provider module's `port="${http.provider.port}"` and `http:listener-config` `${http.listener.port}`, both `localhost` (`xml:26,43`) | SC-10 `config/ListenerPorts` (additional Undertow listener `provider`) and `config/PortRoutingFilter` (each port serves only its own paths) |
| listener platform defaults (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (whole request read before the flow, no CORS policy) | `config/RequestBodyDrainFilter`, `config/RequestBodyDrainRegistration`, `config/PreflightDispatchConfig` |
| `mule-deploy.properties`, `mule-app.properties` | `HttpOauthProviderApplication` (`@SpringBootApplication`), `src/main/resources/application.yml` |
| `HttpOauthProviderIT`: PhantomJS browser, `MuleClient`, `DynamicPort` | `HttpOauthProviderTest`: HtmlUnit for the login form and redirect chain, ST-1 `HttpTestClient` for the bearer request, two free loopback ports reserved in `@DynamicPropertySource` |

Shared components, copied with only the `package` line (and, in tests, the package-relative imports) changed, and registered in root [`DECISIONS.md`](../../DECISIONS.md), section 4:

| ID | Classes | Copied from |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`; test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-10 | `config/ListenerPorts`, `config/PortRoutingFilter`; test `config/PortRoutingFilterTest` | `foreach-processing-and-choice-routing-java` |
| ST-1 | test `support/HttpTestClient` | `rest-api-with-apikit-java` |
| ST-2 | test `FixtureParityTest`; the format of `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |
| — | `config/PreflightDispatchConfig`, `config/RequestBodyDrainFilter`; test `config/PreflightDispatchConfigTest` | `hello-world-java` |

`config/RequestBodyDrainRegistration`, `config/OAuthProviderConfigurationCheck` and the wire tests `config/PreflightDispatchListenerTest`, `config/RequestBodyDrainFilterTest` and `config/SensitiveLogLevelsTest` are this project's own.

## Behaviours stubbed or externalized

- Kept: the owner, client and scope checks of the original and nothing more (AAP 0.4.1): no authentication layer is added. The resource map is a `java.util.HashMap`, as the MEL map literal was, so the JSON keeps the key order `name`, `uri`. The `/redirect` `Location` is the original template verbatim, `&&` and unencoded values included.
- Externalized: the four credentials (Configuration keys above). Nothing is stubbed: the original calls no other system.
- Plan-chosen platform defaults. The original provider module generated these responses and the repository records none of them, so each is the conversion's reading and a Tier 2A verification point: a captured fixture governs, the code is changed to match, and the change is recorded in root `DECISIONS.md` (AAP 0.4.7, 0.8.4):
  1. The token JSON: its keys and their order, `expires_in` as a number, no refresh token, and the `Cache-Control: no-store` and `Pragma: no-cache` headers.
  2. The code lifetime (600 s) and single use, and the token lifetime (86400 s).
  3. The `/authorize` and `/token` error statuses, codes, descriptions and bodies.
  4. The rejected-login answer: `401` with the login page and `Invalid username or password`.
  5. The `/resources` rejections: statuses, `WWW-Authenticate` values and `{ "message": ... }` bodies.
  6. The bearer token read from the header only.
  7. The login page markup. The platform-generated original page is not in the repository; the authored `templates/login.html` keeps the field names `username` and `password` that the original test fills (AAP 0.3.5).

## Build, run and test

Prerequisites: JDK 17 and Maven 3.9. `mvn test` needs no Mule licence, credential or external server. Run from `java-conversions/http-oauth-provider-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check
mvn -B test             # surefire tests only

# run with your own client and owner credentials: replace each <placeholder> with its value, keeping the quotes
export OAUTH_PROVIDER_CLIENT_ID='<client-id>' OAUTH_PROVIDER_CLIENT_SECRET='<client-secret>'
export OAUTH_PROVIDER_OWNER_USERNAME='<username>' OAUTH_PROVIDER_OWNER_PASSWORD='<password>'
mvn spring-boot:run     # provider on localhost:8081, resources on localhost:8082
java -jar target/http-oauth-provider-java-1.0.0-SNAPSHOT.jar

# run with the original demo values of profile test (src/test/resources/application-test.yml)
mvn spring-boot:test-run -Dspring-boot.run.profiles=test
```

- The demo values are on the test classpath only, so they need `spring-boot:test-run`: `mvn spring-boot:run -Dspring-boot.run.profiles=test` activates the profile without finding `application-test.yml`, and the application starts with blank credentials and the startup WARN.
- Ports: `--http.provider.port=<p> --http.listener.port=<l>` (or `-Dspring-boot.run.arguments="..."`) moves both listeners; the `/redirect` `Location` and the payroll `uri` follow them, and the `redirect_uri` sent to `/authorize` must name the moved listener port.
- There is no `mvn verify` live tier: this is not a connector project, so it has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- JaCoCo gate: LINE covered ratio of at least 0.80 over `service/**` and `mapper/**` (this project has no `mapper` package), checked at `prepare-package`; a lower ratio fails `mvn clean package` and `mvn clean install`. The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate`.
- Reports: `target/surefire-reports/`, `target/site/jacoco/` (`index.html`, `jacoco.csv`) and `target/fixture-parity/summary.properties`.
- Tests never use the default ports: the Spring tests reserve free loopback ports for both listeners (`DEFINED_PORT`), because the SC-10 provider listener needs a concrete port and the `/redirect` `Location` and payroll `uri` embed both. `FixtureParityTest` starts no application context while no fixture exists. Once fixtures are captured, it replays them with the provider listener pinned to the capture port `8081` by each fixture's `state.properties` (the `/redirect` `Location` names that port and the replay substitutes only the targeted listener's port); a `-Dhttp.provider.port` setting does not override it, so port 8081 must then be free (`SCENARIOS.md`, Notes for this project).

## Tests

| Test class | What it asserts |
|---|---|
| `HttpOauthProviderTest` | port of `HttpOauthProviderIT.oauthTest`: HtmlUnit opens the original's authorization URL, fills `username` and `password`, submits, follows `/redirect` to `/token`, reads `access_token` from the first `{` to the first `}` of the page as the original did, then `GET /resources` with the bearer token: `name` equals `payroll` and `uri` equals `http://localhost:<listener port>/resources/payroll` |
| `controller/AuthorizationControllerTest` | on the wire through ST-1: the login page, `state` escaping, the four authorization-request errors, the valid-owner 302 with a code, the 405 for every other method, `/authorize` on the listener port → 404 |
| `controller/TokenControllerTest` | the code exchange over `GET`, form `POST` and HTTP Basic, the `/redirect` `Location` (with its `&&`) followed to a token, a reused code, a wrong client secret, a password grant, a missing code and a mismatched redirect URI, the 405 for every other method |
| `controller/ResourcesControllerTest` | `GET` and `POST /resources` with a `READ_RESOURCE` token → payroll; `/redirect` with and without `code`; `/resources` and `/redirect` on the provider port and their sub-paths → 404 |
| `service/OAuthProviderServiceTest` | the grant end to end on a mutable clock and a seeded generator: every rejection, redirect-URI wildcard matching, scopes, code and token lifetimes on their exact boundaries, single use, Basic and parameter client authentication, `PUBLIC` clients, bearer header parsing, fail-closed blank credentials, the token JSON, the payroll `HashMap`, the `/redirect` template |
| `service/ResourceOwnerAuthenticatorTest` | the accepted owner, every rejected variant (case, truncation, padding, `null`, blank), the fail-closed blank configuration, the authorities |
| `config/OAuthProviderConfigurationCheckTest` | the startup WARN names exactly the blank keys and no value; nothing is logged when every credential is configured; the application starts with blank credentials |
| `config/SensitiveLogLevelsTest` | the whole flow under root `TRACE` and `spring.mvc.log-request-details`: the client secret, codes, tokens and owner password never reach the log |
| `config/OAuthProviderPropertiesTest` | the `OAuthProviderProperties` and `Owner` renderings show `clientSecret` and the owner `password` as `****` when set (blank included) and empty when unset (`null` or empty), never the value; every other component keeps the generated record form in declaration order, the owner masked inside the enclosing record |
| `config/PortRoutingFilterTest` | SC-10: per-port routing, the byte-exact no-listener 404, the additional listener registration, an empty TLS password named in the start-up failure |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type`, absent `Allow` header and bodies; `ErrorResponse` escaping; the one-line, escaped ERROR log |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight is dispatched as a plain `OPTIONS` request, first at the handler mapping, then on the wire: the token check and payroll on `/resources`, the 302 on `/redirect`, the 405 on `/authorize` and `/token`, the 404 on an unserved path, no CORS headers |
| `config/RequestBodyDrainFilterTest` | pipelined requests behind a body-less request are all answered on both listeners, including the routing filter's own 404s; the drain filter is registered ahead of `PortRoutingFilter`; draining never changes the chain's outcome |
| `FixtureParityTest` | ST-2 fixture replay; skipped (`no Tier 2A fixtures captured`) until fixtures exist |

`support/HttpTestClient` (ST-1) is an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

The scenarios, the fixture contract and the capture procedure are in [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md). Every scenario has `"upstream": []`, because the original makes no outbound call. Status in the last run (`target/fixture-parity/summary.properties`: `verified=0`, `unverified=19`, `failed=0`):

| Scenario | State | Status |
|---|---|---|
| `get-authorize-login-page` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-authorize-unknown-client` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-authorize-invalid-redirect-uri` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-authorize-valid-owner` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `post-authorize-wrong-password` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-token-authorization-code` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `post-token-authorization-code` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `get-token-invalid-code` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-token-wrong-client-secret` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-resources-valid-token` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `post-resources-valid-token` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `get-resources-missing-token` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-resources-invalid-token` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-resources-insufficient-scope` | `nondeterministic` | `UNVERIFIED-uncaptured`; `UNVERIFIED — nondeterministic output` |
| `get-redirect-with-code` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-redirect-without-code` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-unknown-path-listener-port` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-resources-on-provider-port` | `deterministic` | `UNVERIFIED-uncaptured` |
| `delete-token-method-not-allowed` | `deterministic` | `UNVERIFIED-uncaptured` |

- The six `nondeterministic` scenarios, `post-authorize-valid-owner`, `get-token-authorization-code`, `post-token-authorization-code`, `get-resources-valid-token`, `post-resources-valid-token` and `get-resources-insufficient-scope`, carry an authorization code or access token the original generated at runtime. They are reported `UNVERIFIED — nondeterministic output`: their capture output is kept as evidence in `src/test/capture/<scenario>/` without a fixture, and they stay unverified after capture, so the best label this project can reach is `PARITY: PARTIAL — Tier 2A fixtures: 13 verified, 6 unverified`.
- No scenario is `unreachable`.
- A fixture is never edited to match the Java output; a mismatch is fixed in the Java code.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| http-oauth-provider-java | http-oauth-provider | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The root `SUMMARY.md` carries the same row for this project. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`, which runs every phase of `clean package`: `BUILD SUCCESS`; enforcer `BannedDependencies` passed; 473 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 473 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 206 of 206 lines (`service/OAuthProviderService` 182 of 182, `service/ResourceOwnerAuthenticator` 24 of 24; no `mapper` package). Total project LINE coverage: 96.6% (744 of 770 lines).

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed (root [`DECISIONS.md`](../../DECISIONS.md), section 9). No fixture can be captured from the original, so parity rests on the fallback:

- `HttpOauthProviderTest` carries the original committed assertions: after the browser login and the `/redirect` hop, `/resources` answers `name` `payroll` and `uri` `http://localhost:<listener port>/resources/payroll`.
- The controller and service tests cover every branch on the wire and in the service.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the provider module and the listener defaults, not that its bytes equal what the Mule runtime sent.
- No original assertion covers these branches, which only the new tests cover: every error path of `/authorize` (the four invalid-request errors, the wrong-password login), of `/token` (every rejected exchange, the 405 on other methods) and of `/resources` (missing, invalid and insufficient-scope tokens), the `/redirect` response itself, and the no-listener 404 on either port.
- Unverified scenarios: all nineteen of the table above until captured; the six `nondeterministic` ones remain unverified after capture.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `http-oauth-provider/`.
