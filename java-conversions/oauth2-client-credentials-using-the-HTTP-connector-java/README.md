# OAuth2 Client Credentials using the HTTP Connector — Spring Boot conversion

`oauth2-client-credentials-using-the-HTTP-connector-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`oauth2-client-credentials-using-the-HTTP-connector`](../../oauth2-client-credentials-using-the-HTTP-connector/) (category General). The original's only flow file is `src/main/app/http-client-credentials.xml`, the single entry of `config.resources` in its `mule-deploy.properties`; its `mule-app.properties` is empty. The original folder stays unchanged beside this project and is the Tier 2A capture source.

The example has two flows on one listener (`0.0.0.0:8081`):

- `httpclientcredentialsFlow` (`/oauth`) obtains a Box access token with the OAuth 2.0 client-credentials grant and calls Box's `/api/oauth2/authorize/` with it.
- `http-client-credentialsFlow` (`/FIM/*`) forwards the request method and raw URI to the IBM FIM demo server `tfim01.demos.ibm.com`.

## Original example and set-up

The original `README.md` describes the authorization-code example instead: `http://localhost:8081/web/{user-id}`, `/web/loginDone`, `http-authorization-code-web.xml` and the redirect URL `https://localhost:8082/redirectUrl`. None of these exist in this example's flow, and none is served here; they belong to [`oauth2-authorization-code-using-the-HTTP-connector`](../../oauth2-authorization-code-using-the-HTTP-connector/). Only two of its set-up steps apply to this example.

**Register a Box application.** In the Box developer portal, create a Box application (Content API), open **Configure Application** and copy its `client_id` and `client_secret`. Supply them as `oauth.client.id` and `oauth.client.secret` (see [Configuration keys](#configuration-keys)). `client.id` is the per-user identifier the `/FIM/**` proxy sends to IBM FIM.

**Create a key store and a trust store.** The Box API requires HTTPS. The original creates both stores with the JDK `keytool`:

```sh
keytool -genkey -alias replserver -keyalg RSA -keystore keystore.jks
keytool -export -alias replserver -file client.cer -keystore keystore.jks
keytool -import -v -trustcacerts -alias replserver -file client.cer -keystore trust-store
```

The first command asks for a store password and a key password (`keystore.password`, `keystore.key.password`); the third asks for the trust-store password (`truststore.password`). The original's copies of both files are carried unchanged in `src/main/resources/`, which `keystore.path: keystore.jks` and `truststore.path: trust-store` resolve on the classpath.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/oauth` (exact match) | any, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | Box answers below 400: `200 OK` with Box's body bytes and Box's `Content-Type`, no other Box header | `http-client-credentials.xml:9,12-34` |
| `/oauth`, when any step fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | `http-client-credentials.xml:35-37` |
| `/FIM/**` (`/FIM` and `/FIM/` included) | any, `OPTIONS` and `TRACE` included | `0.0.0.0` | `http.port` | upstream answers below 400: `200 OK` with the upstream body bytes and `Content-Type`, no other upstream header | `http-client-credentials.xml:29,39-46` |
| `/FIM/**`, when the call fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | no exception strategy in the flow |
| any other path (`/oauth/`, `/oauth/extra`, `/FIMX` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because both listeners accept every method. The project has no non-HTTP trigger.

### `/oauth`: Box client-credentials call

`controller/OAuthController` → `service/BoxClientCredentialsService` → `client/BoxOAuthClient`, flow `httpclientcredentialsFlow` (`http-client-credentials.xml:31-38`):

1. The request body is logged at INFO as text, or as `{NullPayload}` when the request has none, which is how Mule 3 renders an empty payload (line 33).
2. `oauth.client.id` and `oauth.client.secret` are checked. A blank value fails the call with `oauth.client.id is not configured` or `oauth.client.secret is not configured` before anything is sent.
3. The HTTPS client for Box is built from the `TLS_Context` stores (`config/TlsProperties`, lines 25-28) on the first call and reused afterwards. While it cannot be built, a blank store password or a store that cannot be found or loaded fails each call with a `TLS configuration ...` message naming the key at fault, before any client secret is sent.
4. Without a cached token, the client-credentials grant runs: `POST https://app.box.com/api/oauth2/token` (`box.oauth.token-url`, line 16) with the form `grant_type=client_credentials` and the client credentials as an HTTP Basic `Authorization` header (the Mule 3.8.0 default, `encodeClientCredentialsInBody` false). `access_token` is extracted, together with the custom parameter `token_type` (line 18), and the token is cached in memory. The token request declares no TLS context, so it uses the JVM's default trust. It fails with `Token request to <token URL> failed with HTTP status <code>` on a non-2xx answer, or with `Token response from <token URL> (HTTP status <code>) contains no access_token`.
5. `GET https://app.box.com:443/api/oauth2/authorize/` is sent with `Authorization: Bearer <access token>` and the `TLS_Context` key store and trust store (line 34). The trailing slash comes from Mule joining basePath `/api/oauth2/authorize` with path `/`. Redirects are followed.
6. When Box answers 401 or 403, the token is requested again and the GET is retried once, as Mule's default `refreshTokenWhen` does. This is the original's own behaviour, not one of the AAP 0.4.8 connector additions, which do not apply to this project. The retry's answer is final.
7. A final Box status below 400 → `200 OK` with Box's body and `Content-Type`. A final status of 400 or above fails with `Response code <code> mapped as failure.` (Mule 3.8's response-validator message).
8. Any failure (Box status, token, TLS, connectivity or timeout) runs the `rollback-exception-strategy` branch, which logs the exception at INFO (lines 35-37). The branch builds no response, so the exception reaches SC-1, which logs one ERROR line with the exception types only and answers the platform 500 `{ "message": "<exception message>" }` (AAP 0.4.7, 0.5.6). The flow declares no transaction, so nothing is rolled back.

### `/FIM/**`: IBM FIM proxy

`controller/FimProxyController` → `service/FimProxyService` → `client/FimProxyClient`, flow `http-client-credentialsFlow` (`http-client-credentials.xml:39-47`):

- The inbound method and raw request URI (path and query exactly as received, with no decoding or re-encoding) are sent to `https://tfim01.demos.ibm.com:443` (`fim.base-url`, line 29). That request configuration has no TLS context, so the JVM's default trust applies.
- `client_id` is an `http:uri-param` (line 43). It replaces each literal `{client_id}` in the forwarded path with `client.id`, percent-encoded once; it is never added as a query parameter. While `client.id` is blank, a URI holding `{client_id}` fails with `client.id is not configured` before the FIM server is called, and a URI without it is forwarded unchanged.
- The request body and its `Content-Type` are forwarded for every method except `GET`, `HEAD` and `OPTIONS`, which is Mule's `sendBodyMode` AUTO. No other caller header is copied, because the flow maps none.
- Upstream below 400 → `200 OK` with the upstream body and `Content-Type`. Upstream 400 or above (`Response code <code> mapped as failure.`) or a connectivity failure has no handling branch → SC-1 500.
- The flow has no logger, so the proxy logs nothing itself.

### Shared listener behaviour

- Connect and response timeouts are 10 s on every outbound call, the Mule requester's default response timeout. The response timeout bounds the whole response, headers and body, and a timed-out call is never retried.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- Tier 2A verification points: a captured fixture showing different bytes governs, and the code is changed to match.
  - The bytes of the no-listener 404 and of the unhandled-exception 500 (AAP 0.4.7).
  - When the upstream sends no `Content-Type`, Spring MVC's content negotiation for the `byte[]` body adds one (`application/octet-stream` for `Accept: */*`), where Mule sent none.
  - `/FIM/**` also matches the bare `/FIM` and `/FIM/`; how Mule's `/FIM/*` answered a bare `/FIM` is unverified.
  - Literal `{` and `}` in the request target are accepted (`ALLOW_UNESCAPED_CHARACTERS_IN_URL`), as Mule's Grizzly listener accepted them.
  - A form-encoded `POST` without a query string has its body rebuilt by Spring from the request parameters, so the logged or forwarded body may not be byte-identical. Other methods' form bodies arrive unchanged.
  - `TRACE` runs the flow like every other method; whether the Mule listener ran it is unverified.
  - A `Content-Type` that cannot be parsed ends in the SC-1 500 before the flow's logger or proxy call runs.
  - An empty query (`/FIM/x?`) is forwarded without its `?`, because the servlet API reports an empty query string as absent.
  - The log message texts (`{NullPayload}`, the exception rendering) and the exception message texts.

## Configuration keys

Values as in `src/main/resources/application.yml`. Every secret is written `${ENV_NAME:}` with a `# TODO: supply ...` comment, so it has no value until it is supplied through that environment variable or as a command-line property (AAP 0.4.6).

| Key | Original name | Default | Purpose | Status |
|---|---|---|---|---|
| `http.port` | none: the original hard-codes port `8081` (`http-client-credentials.xml:9`) | `8081` | listener port, bound by `server.port: ${http.port:8081}` | default |
| `oauth.client.id` | `oauth.client.id` (line 14) | none | the Box application's `client_id` | TODO, environment `OAUTH_CLIENT_ID` |
| `oauth.client.secret` | `oauth.client.secret` (line 14) | none | the Box application's `client_secret` | TODO, environment `OAUTH_CLIENT_SECRET` |
| `client.id` | `client.id` (line 43) | none | the per-user `client_id` URI parameter of the FIM proxy | TODO, environment `CLIENT_ID` |
| `keystore.path` | `keystore.path` (line 27) | `keystore.jks` | JKS key store of the Box HTTPS call, looked up on the classpath first, then as a file | default, the carried store |
| `keystore.password` | `keystore.password` (line 27) | none | store password of the key store | TODO, environment `KEYSTORE_PASSWORD` |
| `keystore.key.password` | `keystore.key.password` (line 27) | none | password of the private key in the key store | TODO, environment `KEYSTORE_KEY_PASSWORD` |
| `truststore.path` | `truststore.path` (line 26) | `trust-store` | JKS trust store of the Box HTTPS call, classpath first, then file | default, the carried store |
| `truststore.password` | `truststore.password` (line 26) | none | store password of the trust store | TODO, environment `TRUSTSTORE_PASSWORD` |
| `box.oauth.token-url` | none: hard-coded `tokenUrl` (line 16) | `https://app.box.com/api/oauth2/token` | client-credentials token endpoint | default |
| `box.api.base-url` | none: hard-coded host, port and protocol (lines 12-13) | `https://app.box.com:443` | scheme, host and port of the Box GET | default |
| `box.api.base-path` | none: hard-coded `basePath` (line 12) | `/api/oauth2/authorize` | path prefix of the Box GET, to which `/` is appended | default |
| `fim.base-url` | none: hard-coded `HTTP_Request_Configuration1` (line 29) | `https://tfim01.demos.ibm.com:443` | scheme, host and port of the FIM proxy | default |

- No credential is hard-coded. The passwords of the carried demo stores are not in the repository; the original read them from properties too. Missing values do not stop the application from starting: they fail only the call that needs them, a `/oauth` call or a `/FIM/**` URI holding `{client_id}` (see [Differences from the original](#differences-from-the-original)).
- The `TLS_Context` replaces the JVM's trust for the Box GET, so the trust store must hold the CA that signs the certificate Box presents. The carried demo `trust-store` holds a single self-signed certificate (alias `client`, `CN=Unknown`), which is not that CA, so a live Box call needs a trust store of your own, named by `truststore.path`.
- `src/test/resources/application-test.yml` (Spring profile `test`) supplies placeholder values for the TODO keys, none of them a real credential, and points every upstream URL at `http://127.0.0.1:9`. The tests override the URLs with WireMock, so they need no real credentials and contact no external host. The project has no `application-it.yml`.

Fixed settings in `src/main/resources/application.yml`, none of them a configuration key of the original:

| Setting | Value | Why |
|---|---|---|
| `spring.application.name` | `oauth2-client-credentials-using-the-HTTP-connector-java` | application name in log lines |
| `server.address` | `0.0.0.0` | `http:listener-config host="0.0.0.0"` (`http-client-credentials.xml:9`) |
| `spring.web.resources.add-mappings` | `false` | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `true` | `TRACE` reaches both controllers like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `false` | a form body on `PUT`, `PATCH` or `DELETE` reaches the controllers unparsed, so `/FIM/**` forwards it unchanged |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `server.undertow.decode-url` | `false` | the raw `http.request.uri` (line 41) reaches `FimProxyController` with its percent-encoding intact |
| `server.undertow.options.server.ALLOW_UNESCAPED_CHARACTERS_IN_URL` | `true` | a request target holding a literal `{client_id}` (lines 41-43) is accepted instead of answered 400; a Tier 2A verification point |

## Mule-to-Java mapping

| Mule construct (`oauth2-client-credentials-using-the-HTTP-connector`) | Java construct (`com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `8081` (line 9) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) on `server.port` and `server.address 0.0.0.0` |
| `http:listener path="oauth"` without `allowedMethods` (line 32) | `controller/OAuthController`, `@RequestMapping("/oauth")`: `oauth` (every method but `OPTIONS`) and `oauthOptions` (`OPTIONS`) |
| `logger message="#[payload]" level="INFO"` (line 33) | SLF4J INFO in `service/BoxClientCredentialsService` |
| `http:request-config` + `oauth2:client-credentials-grant-type` + `tls:context` (lines 12-28) | `client/BoxOAuthClient` + `config/TlsProperties` |
| `http:request` GET `path="/"` (line 34) | `BoxOAuthClient.authorize()` |
| `rollback-exception-strategy` with `logger message="#[exception]"` (lines 35-37) | try/catch in `BoxClientCredentialsService`: INFO log, then rethrow |
| `oauth2:token-manager-config` (line 10) | the in-memory token cache of `BoxOAuthClient` |
| `http:listener path="/FIM/*"` without `allowedMethods` (line 40) | `controller/FimProxyController`, `@RequestMapping("/FIM/**")`: `proxy` and `proxyOptions` |
| `http:request` with `uri-param client_id` on `HTTP_Request_Configuration1` (lines 29, 41-46) | `service/FimProxyService` (`{client_id}` substitution, body rules) + `client/FimProxyClient` |
| Default exception strategy and listener defaults (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `http-client-credentials.xml`) | `Oauth2ClientCredentialsUsingTheHTTPConnectorApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the flow's `${...}` placeholders | `src/main/resources/application.yml` |

## Differences from the original

- **Token timing.** The original requested the Box token when the application was deployed, and a failed token request failed the deployment (Mule 3.8.0 `ClientCredentialsGrantType.start()`). The conversion requests it on the first `/oauth` call, so the application and the `/FIM/**` proxy start without Box credentials, and a failed token request is answered with the `/oauth` rollback branch's 500. Neither the original nor the conversion refreshes the token proactively on `expires_in`; only a 401 or 403 from Box triggers a new token.
- **TLS loading.** The `TLS_Context` stores are read on the first HTTPS call to Box, not at start-up, for the same reason: the application starts with blank store passwords.
- **Externalized values.** The four hard-coded URLs became the default values of the `box.*` and `fim.base-url` keys. The credentials and store passwords stay externalized, as in the original.
- **Not carried.** Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).
- **External services in tests.** Box and IBM FIM are replaced by WireMock in every test, and no test contacts them. The public FIM demo host is unreachable from the build host, and no Box credentials were supplied, so neither live service has been exercised.

## Build, run and test

Run from `java-conversions/oauth2-client-credentials-using-the-HTTP-connector-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
```

Run with the TODO keys supplied as environment variables, each replaced by your own value:

```sh
export OAUTH_CLIENT_ID="<Box client_id>" OAUTH_CLIENT_SECRET="<Box client_secret>" CLIENT_ID="<FIM client id>"
export KEYSTORE_PASSWORD="<store password>" KEYSTORE_KEY_PASSWORD="<key password>" TRUSTSTORE_PASSWORD="<trust-store password>"
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/oauth2-client-credentials-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar --http.port=9090
```

- There is no `mvn verify` live tier and no `application-it.yml`: this is not a connector project, so the pom has no failsafe plugin and the project no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `controller/OAuthControllerTest` | `/oauth` on the wire through ST-1, with WireMock as Box: the happy path for `GET` and `POST` (status line, body bytes, Box `Content-Type`, no other Box header, Bearer and Basic credentials, `grant_type=client_credentials`); a Box 500 → 500 `{ "message": "Response code 500 mapped as failure." }` with no retry; a rejected token request → 500; a 401 → one token refresh and one retry; an unserved path → the no-listener 404 |
| `controller/FimProxyControllerTest` | `/FIM/**` on the wire through ST-1, with WireMock as IBM FIM: raw path and query forwarded; `POST`, `PUT` and `DELETE` bodies and `Content-Type` forwarded; no other upstream header returned; an upstream 404 → 500 `{ "message": "Response code 404 mapped as failure." }`; a connection reset → 500; an unserved path → the no-listener 404 with nothing forwarded |
| `service/BoxClientCredentialsServiceTest` | the INFO payload log (UTF-8 text, or `{NullPayload}` for an absent or empty body) before the single Box call; 200 for every Box success status, with Box's body and only Box's `Content-Type`, verbatim; no `Content-Type` and an empty body when Box sends none; a failure logged at INFO after the payload and rethrown as the same instance; a checked failure wrapped in `IllegalStateException` |
| `service/FimProxyServiceTest` | `{client_id}` substitution, percent-encoded once, with the rest of the URI unchanged; a blank `client.id` refused before any call only when the URI needs it; no body or `Content-Type` for `GET`, `HEAD` and `OPTIONS`, both forwarded for other methods; only the upstream `Content-Type` returned; failures propagated after a single call |
| `client/BoxOAuthClientTest` | WireMock: the client-credentials token request (Basic header, form), token reuse, refresh and one retry on 401 or 403, no further retry on a second rejection, no retry on other failure statuses, token-endpoint errors and token responses without `access_token`, Bearer whatever the `token_type`, redirects followed, no retry after a timeout, an HTTPS base URL with blank store passwords failing before any request |
| `client/FimProxyClientTest` | WireMock: raw URI and method forwarded verbatim, body and `Content-Type` forwarded, response status, headers and body returned, `Response code <code> mapped as failure.` for statuses of 400 or above, redirects followed, a refused connection surfacing `ConnectException`, a body stalled after the headers timing out once within the timeout |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception, with no message or stack trace |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/FIM/x` gets exactly the plain `OPTIONS` reply, forwarded once as `OPTIONS`, with no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404 |
| `FixtureParityTest` | ST-2 fixture replay of `src/test/resources/fixtures/SCENARIOS.md`; skipped while no fixture is captured |

Shared components, each copied with only its `package` line changed (AAP 0.4.4):

- SC-1 `exception/ErrorResponse`, `exception/GlobalExceptionHandler` and `exception/GlobalExceptionHandlerTest`, from `rest-api-with-apikit-java`.
- ST-1 `support/HttpTestClient`, from `rest-api-with-apikit-java`: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.
- ST-2 `FixtureParityTest`, from `hello-world-java`.
- `config/PreflightDispatchConfig` and its two tests, from `hello-world-java`.

The original has no tests of any kind (AAP 0.2.2, 0.7.5): its `src/` holds only `main`. Every test here is new, there are no original assertions to port, and no branch is covered by an original assertion.

## Fixture parity (ST-2)

`src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure; the reference implementation and its format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). No fixture is captured yet, so the replay reports every scenario `UNVERIFIED-uncaptured`.

| Scenario | Trigger | State | Request |
|---|---|---|---|
| `get-oauth-box-authorize` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-oauth-box-token-rejected` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-oauth-box-failure` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-fim-proxy` | `http` | `deterministic` | `GET /FIM/a/b?x=1&y=2`, empty body |
| `post-fim-proxy` | `http` | `deterministic` | `POST /FIM/a/b`, header `Content-Type: application/json`, body `{"name":"mule"}` |
| `get-fim-proxy-upstream-error` | `http` | `deterministic` | `GET /FIM/does-not-exist`, empty body |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body |

No scenario of this project is `unreachable` or `nondeterministic`.

- Capture must start the WireMock recording proxy for Box before the original is deployed, because the original's token exchange happens at deployment; each `/oauth` scenario redeploys the original, so its recording starts with that exchange.
- Box issues the access token into each `/oauth` recording, and it is a live value, so the three `/oauth` scenarios are captured without `upstream` and reported `UNVERIFIED-no-upstream` until the AAP 0.8.8 escalation settles them (`SCENARIOS.md`, Capture step 2).

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| oauth2-client-credentials-using-the-HTTP-connector-java | oauth2-client-credentials-using-the-HTTP-connector | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The root `SUMMARY.md` carries the same row and labels (AAP 0.8.7).

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 170 tests, 0 failures, 0 errors, 0 skipped; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 170 tests, 0 failures, 0 errors, 0 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 40 of 40 lines (`service/BoxClientCredentialsService` 19 of 19, `service/FimProxyService` 21 of 21; the project has no `mapper` package). Total project LINE coverage: 80.0% (368 of 460 lines).
- The run covered the seven test classes then in the tree, every class in the table above except `controller/OAuthControllerTest`, `controller/FimProxyControllerTest` and `FixtureParityTest`. The boundary integration run on the combined tree re-measures this row.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md`. No fixture can be captured from the original, so parity rests on the fallback:

- The original has no tests, so no original assertion is carried and none of the seven scenarios is covered by an original assertion.
- The new controller, service and client tests cover every reachable branch: the Box success, refresh-and-retry and failure paths of `/oauth`, the forwarding and failure paths of `/FIM/**`, and the no-listener 404.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the AAP's reading of the flow and of the platform defaults, but not that its bytes equal what the original runtime sent.
- Unverified scenarios, until captured: `get-oauth-box-authorize`, `get-oauth-box-token-rejected`, `get-oauth-box-failure`, `get-fim-proxy`, `post-fim-proxy`, `get-fim-proxy-upstream-error`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `oauth2-client-credentials-using-the-HTTP-connector/`.
