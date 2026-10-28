# Authenticating Salesforce using OAuth2 (Spring Boot conversion)

`authenticating-salesforce-using-oauth2-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`authenticating-salesforce-using-oauth2`](../../authenticating-salesforce-using-oauth2/) (category Third-party connector, Salesforce, HTTP-triggered). The package root is `com.mulesoft.examples.authenticating_salesforce_using_oauth2` and the main class is `AuthenticatingSalesforceUsingOauth2Application`. The original folder `authenticating-salesforce-using-oauth2/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A browser request authorizes the application at Salesforce with OAuth 2.0 before any data is read. The flow `salesforce-oauthFlow1` of `authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:9-18` uses the Salesforce (OAuth) connector:

1. `sfdc:authorize` with display `PAGE` (xml:12) sends a browser without a token to the Salesforce login and consent page. The connector's callback (`sfdc:oauth-callback-config`, xml:4) exchanges the returned code for a token.
2. `sfdc:query` runs `SELECT id,lastname,lastmodifieddate from contact limit 10` (xml:13).
3. A `foreach` logs each contact at INFO as `contact: #[payload]` (xml:14-16).
4. `set-payload` answers `Salesforce query returned N contacts.` (xml:17).

The flow declares no exception strategy and calls no system other than Salesforce (`authenticating-salesforce-using-oauth2/README.md`, "Example Use Case" and "How it Works").

## Endpoints and triggers

One Undertow listener on `0.0.0.0`, port `http.port` (default `8081`), serves both paths. The project has no non-HTTP trigger.

| Path | Methods | Host and port | Response | Source |
|---|---|---|---|---|
| `/` | any: the listener declares no `allowedMethods` | `0.0.0.0:${http.port}`, default `8081` | no token held: `302 Found`, empty body, `Content-Length: 0`, no `Content-Type`, `Location: https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=<sfdc.key>&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth2callback&display=page`. Token held: `200 OK`, no `Content-Type`, `Content-Length` of the body, body `Salesforce query returned N contacts.` | `salesforce-oauth.xml:6,10,12-17` |
| `/oauth2callback` (`sfdc.oauth.callback.path`) | any | same listener | with `code`: the code exchange, then the same `200` text as an authorized `/`. Without `code`, or with a rejected exchange: `500`, `Content-Type: application/json`, `{ "message": "<message>" }` (SC-1) | `salesforce-oauth.xml:4,12` |
| `/`, `/oauth2callback` | any | same listener | a Salesforce call that fails through an approved failure mode: `502`, `429` or `503`, `Content-Type: application/json` (next section) | AAP 0.4.8 addition |
| any other path (`/error` included) | any | same listener | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

- **Authorize redirect.** `service/SalesforceOAuthService#authorizationUrl` appends to `sfdc.oauth.authorization-url` the parameters `response_type=code`, `client_id`, `redirect_uri` and `display=page`, in that order, each value form-encoded in UTF-8 (`URLEncoder`). `display=page` is the original `display="PAGE"`. An unset `sfdc.key` is sent as an empty `client_id`, so Salesforce answers with its own error page. `controller/SalesforceOAuthController` commits the 302 itself with `Content-Length: 0`, so `OPTIONS` and `TRACE` get the same redirect as every other method. The service logs `No Salesforce token stored; redirecting the browser to Salesforce authorization` at INFO.
- **Authorized query.** `service/ContactQueryService#queryContacts` runs the original SOQL byte for byte (`CONTACT_QUERY`) with the Partner API `query` call, logs each record at INFO as `contact: <record>` in the order Salesforce returned them, and answers `Salesforce query returned N contacts.`. Each record is logged as the `java.util.HashMap` the connector handed to the `foreach`, for example `contact: {LastModifiedDate=2014-08-25T13:21:00.000Z, Id=0032000001INNfoAAH, LastName=Pickwick, type=Contact}`, as in the original README.
- **Text reply.** The flow's `set-payload` (xml:17) names no MIME type, and the Mule listener sent no `Content-Type` for such a payload. `controller/SalesforceOAuthController` therefore writes the `200` text itself as UTF-8 bytes with their `Content-Length` and no `Content-Type`, on `/` and on the callback alike, and commits it, so `OPTIONS` and `TRACE` get the same text as every other method and `HEAD` the same headers without the body.
- **Callback.** `SalesforceOAuthService#completeAuthorization` exchanges `code` at `sfdc.oauth.access-token-url` through `client/SalesforceTokenClient`, which posts the form `grant_type=authorization_code`, `code`, `client_id` (`sfdc.key`), `client_secret` (`sfdc.secret`) and `redirect_uri`, in that order. It stores `access_token`, `refresh_token` and `instance_url`, then continues exactly as `/` does when authorized: query, log, `200 Salesforce query returned N contacts.`. The original DevKit callback resumed the flow after `sfdc:authorize`, which is why the final page of the original IT contains the message.
- **Callback failures** (SC-1 500, platform defaults and Tier 2A verification points). Nothing is stored in either case.
  - No `code`: `{ "message": "Salesforce authorization failed: <error>[: <error_description>]" }`, or `...: no authorization code` when Salesforce sent no `error`. For example, `?error=access_denied&error_description=end-user+denied+authorization` gives `{ "message": "Salesforce authorization failed: access_denied: end-user denied authorization" }`.
  - Rejected exchange, for example `invalid_grant`: `{ "message": "<error>: <error_description>" }` (`SalesforceTokenClient.TokenEndpointException`). A token response without `access_token` or `instance_url` also gets SC-1's 500.
- **Identity** (AAP 0.4.1). The browser user is identified only through Salesforce's authorization-code flow, and the application calls Salesforce with the access token issued to that user. No authentication or authorization layer is added.
- **Token storage** (AAP 0.4.3). The token lives in one in-memory slot (an `AtomicReference` in `SalesforceOAuthService`). This reproduces DevKit's default `accessTokenId`, the config name `Salesforce__OAuth_` (xml:3): once any browser user has authorized, `/` queries with that token for every caller, as in the original. The token is lost on restart. No token, authorization code or consumer key ever reaches a log line.
- **Partner API session.** The Partner API calls run over SC-15 `client/SalesforcePartnerClient` in session mode, with `SalesforceOAuthService` as its `SessionSource`: session ID = the access token, service endpoint = `<instance_url>/services/Soap/u/65.0` (`SOAP_ENDPOINT_PATH`, the API version of `force-partner-api` 65.0.0). The client never calls the SOAP `login`.
- **Accept and content negotiation.** No mapping declares a media-type condition and the `200` is written without a message converter, so no `Accept` or `Content-Type` a caller sends turns into a 406 or 415.
- **CORS pre-flight.** A pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) to `/` or `/oauth2callback` is answered like any other `OPTIONS` request, never with Spring's `403 Invalid CORS request`: it runs the flow, so it gets the same 302 while no token is held, or the same `200` text once authorized or with a callback `code`, and no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).

Exchanges measured on this build, run without a consumer secret, with `--sfdc.key=example-key`, and shown here on the default port. None of them contacts Salesforce: the redirect is built locally and the callback fails before any exchange.

```console
$ curl -i http://localhost:8081/
HTTP/1.1 302 Found
Connection: keep-alive
Location: https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=example-key&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth2callback&display=page
Content-Length: 0
Date: <current date>

$ curl -i 'http://localhost:8081/oauth2callback?error=access_denied&error_description=end-user+denied+authorization'
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 94
Date: <current date>

{ "message": "Salesforce authorization failed: access_denied: end-user denied authorization" }
$ curl -i http://localhost:8081/unknown
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 34
Date: <current date>

No listener for endpoint: /unknown
```

Tier 2A verification points: the plan chose these values where the original platform, not the flow, fixed the bytes. When a captured fixture of the original shows different bytes, the fixture governs and the Java code is changed to match.

- The body of the 302 and the exact `Location` parameters. If the capture shows a per-request DevKit `state` parameter, `get-root-unauthorized` and `post-root-unauthorized` become `nondeterministic` (`SCENARIOS.md`).
- The absent `Content-Type` of the `200` text, because the flow's `set-payload` names no MIME type and the Mule listener sent none for such a payload.
- The callback-error 500 body, and the 404 default.

### Connector failure modes (AAP 0.4.8)

Every Salesforce call (the code exchange, the refresh grant and the contact query) runs through SC-9 `client/UpstreamCallExecutor` with `upstream` `Salesforce`. `client/SalesforceFailureClassifier` classifies the Partner API failures. These responses are the only behaviour the original did not have.

| Failure | Behaviour | Response |
|---|---|---|
| Session rejected: `ApiFault` code `INVALID_SESSION_ID` on the contact query | re-authenticate with the `refresh_token` grant (`SalesforceOAuthService#refresh`), then retry the query once. No refresh token held, or Salesforce refuses the grant: the token is cleared, so the next `/` redirects to Salesforce again, and the attempt ends. A second `INVALID_SESSION_ID` on the retry also ends the attempt | `502 Bad Gateway`, `{ "message": "<upstream message>", "upstream": "Salesforce" }` |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry and no refresh; the token stays stored | `429 Too Many Requests`, same body, no `Retry-After` header because Salesforce sends none |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`), on the query, the code exchange or the refresh, an error body the token endpoint starts and then stalls or resets included | never retried; a failed exchange stores nothing, a failed query or refresh keeps the token | `503 Service Unavailable`, same body |
| Anything else (a rejected code exchange, `INVALID_FIELD`, ...) | rethrown unchanged | SC-1's `500`, `{ "message": "<exception message>" }` |

- Every body has `Content-Type: application/json`, with APIkit's spacing.
- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout, for both the token client and the Partner API client.
- `exception/UpstreamExceptionHandler` runs at the highest `@Order` and so precedes `exception/GlobalExceptionHandler` (lowest `@Order`, AAP 0.4.7). The project has no flow-specific handler, because the original declares no exception strategy.
- With `sfdc.oauth.access-token-url` pointed at a closed port, a callback with a code answers `503` with `{ "message": "Connection refused", "upstream": "Salesforce" }` (measured on this build). The message is the socket's own: a token endpoint that sends an error status and then stalls its body past the read timeout gives `"Read timed out"`, and one that resets the connection gives `"Connection reset"`, on the callback and on the refresh alike.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to, plus the SC-15 keys the code reads but the file does not set. Salesforce authentication is the original's OAuth 2.0 authorization-code flow (AAP 0.10.2), not the SOAP `login` the other Salesforce projects use.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour; `unset` — read by the application's code but not set in `application.yml`, so the stated default applies.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged, xml:6) | default | bound to `server.port` and `sfdc.oauth.callback.local-port`. The original's `mule-app.properties` is empty, so `http.port` had no value there. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (xml:6); set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (xml:6) |
| `spring.application.name` | `String` | `authenticating-salesforce-using-oauth2-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `SalesforceOAuthController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `sfdc.key` | `String` | none, `${SFDC_KEY:}` | `sfdc.key` (unchanged, xml:3 `consumerKey`) | TODO | the connected app's consumer key, sent as `client_id` |
| `sfdc.secret` | `String` | none, `${SFDC_SECRET:}` | `sfdc.secret` (unchanged, xml:3 `consumerSecret`) | TODO (secret) | the connected app's consumer secret, sent as `client_secret` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (SC-15, AAP 0.4.8); a bare number binds as milliseconds |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call, with the same rules |
| `sfdc.oauth.authorization-url` | `String` | `https://login.salesforce.com/services/oauth2/authorize` | `authorizationUrl` of `sfdc:authorize` (xml:12) | default | where the browser is redirected to log in and grant access |
| `sfdc.oauth.access-token-url` | `String` | `https://login.salesforce.com/services/oauth2/token` | `accessTokenUrl` of `sfdc:authorize` (xml:12) | default | where the code and refresh-token grants are posted |
| `sfdc.oauth.callback.domain` | `String` | `localhost` | `domain` of `sfdc:oauth-callback-config` (xml:4) | default | host of the `redirect_uri` |
| `sfdc.oauth.callback.local-port` | `int` | `${http.port:8081}`, so `8081` | `localPort` of `sfdc:oauth-callback-config` (xml:4) | default | port of the `redirect_uri`; follows `http.port` |
| `sfdc.oauth.callback.path` | `String` | `oauth2callback` | `path` of `sfdc:oauth-callback-config` (xml:4) | default | path of the `redirect_uri` and of the callback mapping, with or without a leading `/` |
| `sfdc.username`, `sfdc.password` | `String` | none | `sfdc.user` and `sfdc.password` of the original IT's `mule.test.properties` | unset | bound by SC-15 `config/SalesforceProperties`; used only by the live test's browser login, never by the application |
| `sfdc.securityToken`, `sfdc.auth-endpoint` | `String` | none; `https://login.salesforce.com/services/Soap/u/65.0` | none | unset | bound by SC-15 for its SOAP `login` mode, which session mode never calls |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | below `INFO` it logs the callback's request line with its query, the authorization code included; `WARN`, not `INFO`, because its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.method.HandlerMethod`, `logging.level.org.springframework.web.client.DefaultRestClient` | `LogLevel` | `INFO` | none | fixed | below `INFO` these log the callback's handler arguments (the code) and each token request form (the code or the refresh token, with the consumer secret), none of which the original logged. A named level wins over `logging.level.root`, `--debug` and `--trace`, so a root `DEBUG` or `TRACE` does not reach these three loggers; the application's own loggers are not pinned |

- **Callback port.** The original served its callback on a separate DevKit listener fixed at `localhost:8081`, while `/` listened on `${http.port}`. The Java project has one listener, so the callback port defaults to `http.port`, and `redirect_uri` is `http://<sfdc.oauth.callback.domain>:<sfdc.oauth.callback.local-port>/<sfdc.oauth.callback.path>`, `http://localhost:8081/oauth2callback` with the defaults. If you change `http.port`, register the matching callback URL (`http://localhost:<http.port>/oauth2callback`) on the connected app. Do not set `sfdc.oauth.callback.local-port` alone, which would point `redirect_uri` at a port the application does not listen on.
- `config/SalesforceOAuthProperties` binds `sfdc.key`, `sfdc.secret` and `sfdc.oauth.*`; `config/SalesforceProperties` (SC-15) binds the timeouts. Both `toString()` methods mask the secrets.
- The project has no `src/test/resources/application-test.yml`: the unit tests stub Salesforce with WireMock or mock it and need no credential.
- Supply the consumer key and secret through the environment, never in a committed file:

  ```sh
  SFDC_KEY='<consumer key>' SFDC_SECRET='<consumer secret>' mvn spring-boot:run
  # another port; register http://localhost:9090/oauth2callback on the connected app first
  SFDC_KEY='<consumer key>' SFDC_SECRET='<consumer secret>' java -jar target/authenticating-salesforce-using-oauth2-java-1.0.0-SNAPSHOT.jar --http.port=9090
  ```

Original-to-new key map:

| Original key | Converted key | Where |
|---|---|---|
| `sfdc.key`, `sfdc.secret` | unchanged | `application.yml`, `application-it.yml` |
| `http.port` | unchanged | `application.yml` |
| `sfdc.user` (IT) | `sfdc.username` (the request's name governs, AAP 0.4.6) | `application-it.yml` |
| `sfdc.password` (IT) | unchanged | `application-it.yml` |
| `phantomjs.binary` (IT, failsafe system property) | dropped: HtmlUnit replaces PhantomJS and needs no browser binary | — |
| `src/test/resources/mule.test.properties` | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) | — |

### Connected app

The original README's setup steps, applied to the converted project:

1. Log in to the existing Salesforce org; no new sandbox or tenant is needed (AAP 0.2.4). Open **Setup**, then **Build** → **Create** → **Apps**, and under **Connected Apps** click **New**.
2. Fill in the required fields and check **Enable OAuth Settings**.
3. Set the callback URL to `http://localhost:8081/oauth2callback`, or `http://localhost:<http.port>/oauth2callback` for another port. The original README named port 8082, but the flow's callback configuration fixes 8081 (xml:4).
4. Move **Full Access (full)** to the selected OAuth scopes. Also select **Perform requests at any time (refresh_token, offline_access)** so that Salesforce issues a refresh token; it is needed only for the re-authentication path of the 502 row above. Save.
5. Put the **Consumer Key** in `SFDC_KEY` and the **Consumer Secret** in `SFDC_SECRET`.
6. Run the application and browse to `http://localhost:8081/`. Salesforce asks for the user's credentials and approval, redirects to `/oauth2callback`, and the page shows `Salesforce query returned N contacts.`, with one `contact: ...` INFO line per contact in the log.

## Mule-to-Java mapping

| Mule construct (`authenticating-salesforce-using-oauth2`) | Java construct (`com.mulesoft.examples.authenticating_salesforce_using_oauth2`) |
|---|---|
| `sfdc:config-with-oauth` `Salesforce__OAuth_` with `${sfdc.key}`, `${sfdc.secret}` (xml:3) | `config/SalesforceOAuthProperties` (`sfdc.key`, `sfdc.secret`) and the single token slot of `service/SalesforceOAuthService` |
| `sfdc:oauth-callback-config domain="localhost" localPort="8081" path="oauth2callback"` (xml:4) | `sfdc.oauth.callback.*`; `SalesforceOAuthController#callback` on the application's own listener |
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (xml:6) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (xml:10) | `SalesforceOAuthController#root` (every method but `OPTIONS`) and `#rootOptions` (`OPTIONS`) |
| `sfdc:authorize` with `authorizationUrl`, `accessTokenUrl`, `display="PAGE"` (xml:12) | `SalesforceOAuthService#authorize` (302 to `#authorizationUrl`) and `#completeAuthorization` (code exchange through `client/SalesforceTokenClient`, token stored) |
| the connector's token refresh after an expired session | `SalesforceOAuthService#refresh` (`refresh_token` grant), run by SC-9 as the re-authentication step |
| `sfdc:query` `SELECT id,lastname,lastmodifieddate from contact limit 10` (xml:13) | `service/ContactQueryService#queryContacts` → SC-15 `client/SalesforcePartnerClient#query` in session mode |
| `foreach` with `logger level="INFO" message="contact: #[payload]"` (xml:14-16) | SLF4J INFO `contact: {}` per record in `ContactQueryService#queryContacts` |
| `set-payload value="Salesforce query returned #[payload.size()] contacts."` (xml:17) | the return value of `ContactQueryService#queryContacts`, written by the controller with its `Content-Length` and no `Content-Type` |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener without a CORS policy | `config/PreflightDispatchConfig` |
| (approved addition, no original construct) | SC-9 `client/UpstreamCallExecutor`, `exception/UpstreamExceptionHandler` and the three upstream exceptions; `client/SalesforceFailureClassifier` (SC-15) |
| `mule-deploy.properties` (`config.resources=salesforce-oauth.xml`) | `AuthenticatingSalesforceUsingOauth2Application` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `SalesforceOauthIT` (`FunctionalTestCase`, `DynamicPort`, PhantomJS, live org) | `SalesforceOauthTest` (surefire, WireMock and a mocked partner client) and `SalesforceOauthIT` (failsafe, HtmlUnit, live org) |

Shared components, each copied with only its `package` line changed (AAP 0.4.4; register and version alignments in the root `DECISIONS.md`):

- SC-1 (`exception/GlobalExceptionHandler`, `exception/ErrorResponse`) and ST-1 (`support/HttpTestClient`) from `rest-api-with-apikit-java`.
- `config/PreflightDispatchConfig` and its test `config/PreflightDispatchConfigTest` from `rest-api-with-apikit-java`. The wire test `config/PreflightDispatchListenerTest` is this project's own.
- ST-2 (`FixtureParityTest`, the `SCENARIOS.md` format) from `hello-world-java`.
- SC-9 (`client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException`, `exception/UpstreamUnavailableException`, `exception/UpstreamExceptionHandler`) and SC-15 (`client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier`, `config/SalesforceProperties`) from `salesforce-data-retrieval-java`.
- `DECISIONS.md` also records the Jackson alignment: `force-wsc` 65.0.0 declares Jackson 2.18.4, the Spring Boot BOM resolves 2.15.4, and the Partner API calls use WSC's own XML stack.

## Behaviours stubbed or externalized

- Externalized: the consumer key and secret, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; the OAuth URLs, the callback and the timeouts, which the original fixed or left at connector defaults, are configurable keys.
- Stubbed in tests only: the unit tier replaces Salesforce with WireMock authorize, token and Partner SOAP stubs or a Mockito-mocked `SalesforcePartnerClient`. At run time nothing is stubbed and no behaviour was dropped.
- Not carried: Studio metadata (`mule-project.xml`), `src/test/resources/log4j2-test.xml` (AAP 0.2.2), and the PhantomJS installation of the original `pom.xml` (`phantomjs-maven-plugin`). The original README's `org.mule.examples.LoggerIterator` component does not exist in the original folder; its flow logs through `foreach` and `logger`, which is what the conversion reproduces.

## Build, run and test

Run from `java-conversions/authenticating-salesforce-using-oauth2-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free
mvn -B verify           # surefire tests, then the failsafe SalesforceOauthIT against a live org (Tier 2B)
mvn spring-boot:run     # listener on 0.0.0.0:8081; needs sfdc.key and sfdc.secret, then browse http://localhost:8081/
java -jar target/authenticating-salesforce-using-oauth2-java-1.0.0-SNAPSHOT.jar
```

- `mvn test` needs no licence, credential or network: Salesforce is a WireMock stub or a Mockito-mocked `SalesforcePartnerClient`.
- Without `sfdc.key` and `sfdc.secret` the application still starts and redirects `/` to Salesforce with an empty `client_id`; Salesforce then shows its own error page, and a callback exchange is rejected (SC-1's 500).
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

### Live test (Tier 2B)

`SalesforceOauthIT` runs under failsafe (`mvn -B verify`) against the existing Salesforce org and connected app, reusing the existing credentials; no new sandbox is needed.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `sfdc.key`, `sfdc.secret`, `sfdc.username` and `sfdc.password`. The authorization-code flow needs no security token.
3. Run `mvn -B verify`.

- The test starts the application with the Spring profile `it`, which loads `application-it.yml`, on the fixed port `http.port` rather than a random one, so that `redirect_uri` matches the callback URL registered on the connected app. That port is `8081` unless `HTTP_PORT` in the environment or `http.port` in `application-it.yml` moves it; the listener and `redirect_uri` then move together, and `http://localhost:<port>/oauth2callback` must be registered on the connected app first. The test asserts that `sfdc.oauth.callback.local-port` equals the listener port.
- HtmlUnit logs in at `https://login.salesforce.com/` as `sfdc.username`, as the original's PhantomJS did, then requests `GET /`. The application completes the authorization-code flow and its query, and the test asserts that the final page contains `Salesforce query returned`.
- The test is read-only: login, query, count text. It creates and deletes no record.
- The user must have approved the connected app once, as the original IT assumed; the test clicks **Allow** (`oaapprove`) if Salesforce shows the approval page. Salesforce may also challenge a login from an unrecognised browser with an identity verification page, which the test cannot answer, so the org must trust the network the test runs from.
- While `application-it.yml` or one of the four keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate`, never failed, and no application starts. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `SalesforceOauthTest` | the ported `SalesforceOauthIT` assertion (`authenticating-salesforce-using-oauth2/src/test/java/org/mule/examples/SalesforceOauthIT.java:66-75`): after the login, `GET /` answers a page that contains `Salesforce query returned`. WireMock stands in for the authorize and token endpoints, the redirect chain is walked hop by hop through ST-1, and a mocked `SalesforcePartnerClient` returns three contacts for the original SOQL; both `200` replies (callback and `/`) carry no `Content-Type` and the body's `Content-Length` |
| `SalesforceOauthIT` | the same assertion against a live org (failsafe, previous section) |
| `controller/SalesforceOAuthControllerTest` | on the wire through ST-1, with the service mocked: the unauthorized 302 (exact `Location`, `Content-Length: 0`, no `Content-Type`, empty body); the `200` text with no `Content-Type` and its `Content-Length`, on an authorized `/` and on the callback with a code, for `GET`, `HEAD` (headers only), `POST`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`, `TRACE`, `PROPFIND` and lower-case `get`; the access-denied callback → 500 `application/json`; 502, 429 and 503 on both endpoints, without `Retry-After`, ahead of SC-1's 500; `/unknown` → 404 `No listener for endpoint: /unknown` |
| `service/SalesforceOAuthServiceTest` | the authorization URL and `redirect_uri` from the original connector settings, its encoding and parameter order; the redirect without a token; the callback exchange, token storage and query; refresh renewal, a refresh without a refresh token or refused by Salesforce (token cleared, next `/` redirects), other refresh failures (token kept); a rejected session whose refresh error body stalls or is reset, through the real token and Partner API clients → unavailable, token kept, nothing retried; callbacks with an error, without a code, with a failed exchange or an incomplete token response (nothing stored); masked tokens |
| `service/ContactQueryServiceTest` | the original SOQL run exactly once; three contacts counted and each logged at INFO in record order; zero contacts; upstream and Partner API failures reaching the caller as the same instance |
| `client/SalesforceTokenClientTest` | against a WireMock token endpoint: the authorization-code and refresh-token forms in order; every token field parsed; error bodies, an absent or unreadable one included → `TokenEndpointException`; a read timeout, a connection reset and a closed port → unavailable with the socket's own message, never retried. Against a raw loopback endpoint (`support/LoopbackHttpStub`), for both grants: an error status whose body then stalls past the read timeout or is reset → unavailable with the socket's own message, one request |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login mode, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, `HashMap` records; session mode without login, one refresh and retry after `INVALID_SESSION_ID`, rebuild on a changed session; the failure modes per operation; the 10 s default timeouts; concurrent callers sharing the one login, re-login or session refresh in flight, its timeout included; paging that fails, with no further `queryMore`, on a repeated query locator, records beyond the reported size or pages beyond what that size allows |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest`, `exception/UpstreamExceptionHandlerTest` | SC-9 and the Salesforce classification: one re-authentication and retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, a failed re-authentication ending the attempt, the 502/429/503 bodies and precedence over a catch-all advice |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire, with the service mocked: a CORS pre-flight to `/` (no token: the 302 with its exact `Location`; token held: the `200` text) and to `/oauth2callback?code=abc` (the `200` text) gets exactly the plain `OPTIONS` reply, with `Access-Control-Request-Method` `GET`, `POST`, `PUT`, `DELETE`, `PATCH` or `OPTIONS` and with `Access-Control-Request-Headers`, never Spring's 403, with no `Access-Control-*` or `Vary` header; a pre-flight to `/unknown` gets the no-listener 404; a cross-origin `GET` or `POST` that is not a pre-flight still runs the flow |
| `config/SensitiveLogLevelsTest` | the three `logging.level` pins as bound from `application.yml`, and nothing else pinned; then, with the root and the loggers `--trace` raises at TRACE, the callback with a sentinel code on the Undertow listener, the code exchange with a sentinel consumer secret, an `INVALID_SESSION_ID` rejection of the sentinel access token and the renewal with the sentinel refresh token, all against WireMock: with the pins no log event holds a sentinel; without them `DispatcherServlet` and `HandlerMethod` write the code and `DefaultRestClient` the code, the consumer secret and the refresh token, and with every pin but one exactly that logger writes; the original `contact: #[payload]` line is still written at INFO |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, and never follows a redirect.

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of `src/test/resources/fixtures/SCENARIOS.md`, which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-unauthorized` | `http` | `deterministic` | `GET /`, empty body; no token held | `UNVERIFIED-uncaptured` |
| `post-root-unauthorized` | `http` | `deterministic` | `POST /`, empty body; no token held | `UNVERIFIED-uncaptured` |
| `get-oauth2callback-code` | `http` | `deterministic` | `GET /oauth2callback?code=<the code Salesforce issues>` | `UNVERIFIED-uncaptured` |
| `get-root-authorized` | `http` | `deterministic` | `GET /`, empty body; token stored by the preceding callback | `UNVERIFIED-uncaptured` |
| `get-root-session-expired-refresh` | `http` | `deterministic` | `GET /`, empty body; session rejected with `INVALID_SESSION_ID`, then refreshed | `UNVERIFIED-uncaptured` |
| `get-oauth2callback-access-denied` | `http` | `deterministic` | `GET /oauth2callback?error=access_denied&error_description=end-user+denied+authorization` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-session-refresh-refused` | `http` | `deterministic` | `GET /`; `INVALID_SESSION_ID`, refresh refused with `invalid_grant` | `UNVERIFIED-uncaptured` |
| `get-root-query-rate-limited` | `http` | `deterministic` | `GET /`; `REQUEST_LIMIT_EXCEEDED` | `UNVERIFIED-uncaptured` |
| `get-root-query-timeout` | `http` | `deterministic` | `GET /`; no answer within `sfdc.read-timeout` | `UNVERIFIED-uncaptured` |
| `get-oauth2callback-token-endpoint-unreachable` | `http` | `deterministic` | `GET /oauth2callback?code=capture-marker`; token endpoint refuses the connection | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable`, and none is planned `nondeterministic`.
- The last four rows are the approved connector failure modes (AAP 0.4.8). The original has no such branch, so they are never captured; their Tier 1 evidence is the service, client, classifier, executor and handler tests above.
- The three rows that reach Salesforce (`get-oauth2callback-code`, `get-root-authorized`, `get-root-session-expired-refresh`) carry the live consumer secret, tokens and instance URL in their upstream exchanges. They therefore omit `upstream` and stay `UNVERIFIED-no-upstream` after capture.
- Capture can raise the label at most to `PARITY: PARTIAL — Tier 2A fixtures: 4 verified, 7 unverified`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| authenticating-salesforce-using-oauth2-java | authenticating-salesforce-using-oauth2 | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

This row equals the project's row in the root `SUMMARY.md`. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 898 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`SalesforceOauthIT`, no `application-it.yml`).
- `mvn -B test`: `BUILD SUCCESS`; 898 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 93 of 93 lines (100.0%) of `service/SalesforceOAuthService` (with its nested records) and `service/ContactQueryService`; the project has no `mapper` package. Total project LINE coverage: 96.2% (920 of 956 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=11`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `SalesforceOauthIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `SalesforceOauthTest` carries the original committed assertion with its comparison semantics: the page `GET /` returns after the login contains `Salesforce query returned`.
- `controller/SalesforceOAuthControllerTest` and the service and client tests cover every reachable branch on the wire or against the service.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the platform defaults, but not that its bytes equal what the original runtime sent. The Salesforce data in these tests is stubbed, not recorded from the original's org.
- Branches no original assertion covers, tested only by the new tests: the unauthorized redirect (`get-root-unauthorized`, `post-root-unauthorized`), the callback error (`get-oauth2callback-access-denied`), the refresh re-authentication (`get-root-session-expired-refresh`), the 502, 429 and 503 failure modes (`get-root-session-refresh-refused`, `get-root-query-rate-limited`, `get-root-query-timeout`, `get-oauth2callback-token-endpoint-unreachable`), and the unknown-path 404 (`get-unknown-path`).
- Unverified scenarios, until captured: `get-root-unauthorized`, `post-root-unauthorized`, `get-oauth2callback-code`, `get-root-authorized`, `get-root-session-expired-refresh`, `get-oauth2callback-access-denied`, `get-unknown-path`, `get-root-session-refresh-refused`, `get-root-query-rate-limited`, `get-root-query-timeout`, `get-oauth2callback-token-endpoint-unreachable`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `authenticating-salesforce-using-oauth2/`.
