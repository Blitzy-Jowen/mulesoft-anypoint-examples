# Parity scenarios — http-oauth-provider-java

Scenarios for the fixture replay (ST-2) of `http-oauth-provider-java`. The original example is `http-oauth-provider` (folder `http-oauth-provider/`). Its `oauth2-provider:config` serves the OAuth 2.0 provider endpoints `/authorize` and `/token` on the provider port and authenticates the resource owner through `resourceOwnerSecurityProvider`; its two flows run on the resource listener: `protectedAuthcodeFlow` answers `/resources` once `oauth2-provider:validate` accepts the bearer token, and `redirectFlow` answers `/redirect` with the demonstration redirect to `/token`. Every reachable branch listed for the example in AAP 0.7.5, the provider endpoints' outcomes and the listener-boundary cases of AAP 0.4.7 are exactly one scenario each below, and each captured scenario's fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5. Its consumer is `src/test/java/com/mulesoft/examples/http_oauth_provider/FixtureParityTest.java`, the project's copy of ST-2, which reads this file from the classpath as `fixtures/SCENARIOS.md`, or as `<dir>/SCENARIOS.md` under `-Dfixtures.dir=<dir>`. The project `README.md` lists these scenario names and the unverified ones.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-authorize-login-page` | `http` | `deterministic` | port `<provider>`: `GET /authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE&redirect_uri=http://localhost:<listener>/redirect`, empty body (the URL the original test opens) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | valid authorization request (registered client, `response_type=code`, redirect URI within `http://localhost*`, a client scope) → resource-owner login page | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41`, `http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:85-89` |
| `get-authorize-unknown-client` | `http` | `deterministic` | port `<provider>`: `GET /authorize?response_type=code&client_id=unknownclient&scope=READ_RESOURCE&redirect_uri=http://localhost:<listener>/redirect`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | client lookup fails (only `myclientid` is registered) → `invalid_client` branch | `http-oauth-provider/src/main/app/http-oauth-provider.xml:27-40` |
| `get-authorize-invalid-redirect-uri` | `http` | `deterministic` | port `<provider>`: `GET /authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE&redirect_uri=http://evil.example/cb`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | redirect URI outside `http://localhost*` → `invalid_request` branch | `http-oauth-provider/src/main/app/http-oauth-provider.xml:29-31` |
| `post-authorize-valid-owner` | `http` | `nondeterministic` | port `<provider>`: `POST /authorize`, header `Content-Type: application/x-www-form-urlencoded`, body `response_type=code&client_id=myclientid&redirect_uri=http://localhost:<listener>/redirect&scope=READ_RESOURCE&username=mule&password=mule` | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | owner authenticated by `resourceOwnerSecurityProvider` → authorization code generated, redirect to `redirect_uri` with `code`; varies: the generated authorization code | `http-oauth-provider/src/main/app/http-oauth-provider.xml:13-24,26-41`, `http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:91-96` |
| `post-authorize-wrong-password` | `http` | `deterministic` | port `<provider>`: `POST /authorize`, header `Content-Type: application/x-www-form-urlencoded`, body `response_type=code&client_id=myclientid&redirect_uri=http://localhost:<listener>/redirect&scope=READ_RESOURCE&username=mule&password=wrongpassword` | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | owner authentication fails → login page re-rendered with error | `http-oauth-provider/src/main/app/http-oauth-provider.xml:13-24` |
| `get-token-authorization-code` | `http` | `nondeterministic` | port `<provider>`: `GET /token?grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret&code=<code>&redirect_uri=http://localhost:<listener>/redirect`, empty body (the `/redirect` `Location` form, `&&` kept) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream; code/token obtained during the scenario (see Nondeterministic rows) | authorization-code grant over GET → access token issued; varies: the code the request carries and the token generated for it | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41,53-57`, `http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:98` |
| `post-token-authorization-code` | `http` | `nondeterministic` | port `<provider>`: `POST /token`, header `Content-Type: application/x-www-form-urlencoded`, body `grant_type=authorization_code&client_id=myclientid&client_secret=myclientsecret&code=<code>&redirect_uri=http://localhost:<listener>/redirect` | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream; code/token obtained during the scenario (see Nondeterministic rows) | authorization-code grant over a POST form → access token issued; varies: the code the request carries and the token generated for it | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41` |
| `get-token-invalid-code` | `http` | `deterministic` | port `<provider>`: `GET /token?grant_type=authorization_code&client_id=myclientid&client_secret=myclientsecret&code=invalidcode&redirect_uri=http://localhost:<listener>/redirect`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | unknown code → `invalid_grant` branch | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41` |
| `get-token-wrong-client-secret` | `http` | `deterministic` | port `<provider>`: `GET /token?grant_type=authorization_code&client_id=myclientid&client_secret=wrongsecret&code=invalidcode&redirect_uri=http://localhost:<listener>/redirect`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | client authentication fails → `invalid_client` branch | `http-oauth-provider/src/main/app/http-oauth-provider.xml:28` |
| `get-resources-valid-token` | `http` | `nondeterministic` | port `<listener>`: `GET /resources`, header `Authorization: Bearer <token>`, empty body (`<token>` issued during the scenario for scope `READ_RESOURCE`) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream; code/token obtained during the scenario (see Nondeterministic rows) | `oauth2-provider:validate` accepts a token with `READ_RESOURCE` → payroll resource map → `object-to-json-transformer`; varies: the token the request carries | `http-oauth-provider/src/main/app/http-oauth-provider.xml:44-49` (validate 46, payload 47-48), `http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:100-107` |
| `post-resources-valid-token` | `http` | `nondeterministic` | port `<listener>`: `POST /resources`, header `Authorization: Bearer <token>`, empty body (`<token>` issued during the scenario for scope `READ_RESOURCE`) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream; code/token obtained during the scenario (see Nondeterministic rows) | same flow over POST, because the listener declares no `allowedMethods` (AAP 0.4.9) → payroll resource; varies: the token the request carries | `http-oauth-provider/src/main/app/http-oauth-provider.xml:44-49` |
| `get-resources-missing-token` | `http` | `deterministic` | port `<listener>`: `GET /resources`, empty body (no `Authorization` header) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | `oauth2-provider:validate` rejects (`throwExceptionOnUnaccepted`): no bearer token | `http-oauth-provider/src/main/app/http-oauth-provider.xml:46` |
| `get-resources-invalid-token` | `http` | `deterministic` | port `<listener>`: `GET /resources`, header `Authorization: Bearer invalidtoken`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | `oauth2-provider:validate` rejects: unknown token (`invalid_token` branch) | `http-oauth-provider/src/main/app/http-oauth-provider.xml:46` |
| `get-resources-insufficient-scope` | `http` | `nondeterministic` | port `<listener>`: `GET /resources`, header `Authorization: Bearer <token>`, empty body (`<token>` issued during the scenario for scope `POST_RESOURCE` only) | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream; code/token obtained during the scenario (see Nondeterministic rows) | `oauth2-provider:validate` rejects: the token lacks `READ_RESOURCE`; varies: the token the request carries | `http-oauth-provider/src/main/app/http-oauth-provider.xml:46` |
| `get-redirect-with-code` | `http` | `deterministic` | port `<listener>`: `GET /redirect?code=abc`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | `redirectFlow` builds the `/token` `Location` with the given `code` | `http-oauth-provider/src/main/app/http-oauth-provider.xml:53-57` |
| `get-redirect-without-code` | `http` | `deterministic` | port `<listener>`: `GET /redirect`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | `code` absent → `redirectFlow` builds the `Location` from the null result of the `code` lookup | `http-oauth-provider/src/main/app/http-oauth-provider.xml:56` |
| `get-unknown-path-listener-port` | `http` | `deterministic` | port `<listener>`: `GET /unknown`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | no listener serves the path on this port → no-listener platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `http-oauth-provider/src/main/app/http-oauth-provider.xml:43,45,54` (no `/unknown` listener) |
| `get-resources-on-provider-port` | `http` | `deterministic` | port `<provider>`: `GET /resources`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | `/resources` requested on the provider port, which does not serve it → no-listener platform default (AAP 0.4.7, SC-10 `PortRoutingFilter`); a Tier 2A verification point where the captured bytes govern | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26,43` |
| `delete-token-method-not-allowed` | `http` | `deterministic` | port `<provider>`: `DELETE /token`, empty body | demo client `myclientid`/`myclientsecret` and owner `mule`/`mule` from profile `test`; no store, no upstream | a method the token endpoint does not accept → method-not-allowed platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41` |

Thirteen scenarios are `deterministic`: `get-authorize-login-page`, `get-authorize-unknown-client`, `get-authorize-invalid-redirect-uri`, `post-authorize-wrong-password`, `get-token-invalid-code`, `get-token-wrong-client-secret`, `get-resources-missing-token`, `get-resources-invalid-token`, `get-redirect-with-code`, `get-redirect-without-code`, `get-unknown-path-listener-port`, `get-resources-on-provider-port` and `delete-token-method-not-allowed`. Six are `nondeterministic`: `post-authorize-valid-owner`, `get-token-authorization-code`, `post-token-authorization-code`, `get-resources-valid-token`, `post-resources-valid-token` and `get-resources-insufficient-scope`. No row is `unreachable`.

Request notes:

- `<provider>` and `<listener>` name the two listeners of Ports below: `<provider>` the provider port `http.provider.port`, `<listener>` the resource listener `http.listener.port`. Inside a query or a form body, `http://localhost:<listener>/redirect` is the redirect URI the original test sends, written with the resource listener's capture port; capture and replay send it verbatim.
- `<code>` and `<token>` are the authorization code and the access token the original issues earlier in the same scenario (see Nondeterministic rows); every other value is sent as written.
- Form bodies are `application/x-www-form-urlencoded` and are sent byte for byte, without a line ending. No `state` parameter is sent, as in the original test.
- The `Request` cells name only the headers the capture sets explicitly. The headers curl adds by default are recorded unedited in each fixture's `request.headers` from curl's trace (Capture step 5).

Notes for this project:

- Ports: the original has two listeners, both bound to `localhost` (`http-oauth-provider.xml:26,43`; AAP 0.4.9), and the conversion keeps both (SC-10, AAP 0.4.4).
  - `<provider>`, the provider listener: key `http.provider.port`, default `8081` (`http-oauth-provider/src/main/app/mule-app.properties:1`, `http-oauth-provider/src/main/app/http-oauth-provider.xml:26`). It serves `/authorize` and `/token`. In `src/main/resources/application.yml` it is the SC-10 additional listener `provider` (`listeners.additional[0]`).
  - `<listener>`, the resource listener: key `http.listener.port`, default `8082` (`http-oauth-provider/src/main/app/mule-app.properties:2`, `http-oauth-provider/src/main/app/http-oauth-provider.xml:43`). It serves `/resources` and `/redirect`, and is the main listener, `server.port`.
  - The capture deploys the original on those two defaults (Capture step 3), so `request.port` is `8081` in every `<provider>` row and `8082` in every `<listener>` row. A `<provider>` fixture names `http.provider.port` in `request.portProperty`; a `<listener>` fixture leaves it at its default `local.server.port`.
- Replay state: every fixture of this project carries the same `state`, written as Capture step 8 defines:

  ```json
  "state": {
    "properties": {
      "server.port": "${freePort:listener}", "http.listener.port": "${freePort:listener}", "http.provider.port": 8081
    },
    "notContacted": ["oauth.provider.redirect-uris[0]", "listeners.additional[0].host"]
  }
  ```

  - The resource listener replays on one free port: `server.port` and `http.listener.port` name the same `${freePort:listener}`, so the application listens on the port it also writes into the `redirect_uri` of the `/redirect` `Location` and into the payroll `uri`. The single `Location` substitution of AAP 0.8.4 replaces the captured `:8082` by that replay port in every `<listener>` row.
  - The provider listener is pinned to its capture port `8081` with a plain value, the form the reference allows besides its placeholders and the one the foreach-processing-and-choice-routing project uses where a response names a port the single substitution does not cover: the `/redirect` `Location` of `get-redirect-with-code` and `get-redirect-without-code` also names the provider port, and the replay substitutes only the port of the listener a row targets. A `${freePort:<name>}` provider port would leave those two rows unable to match. Replaying captured fixtures therefore needs port 8081 free on the host; a port in use stops the application context and fails the class.
  - `listeners.additional[0].host` is listed in `notContacted` because the replay boundary reads that bind host together with its sibling port `8081`, which no replay server owns; it is the provider listener's own bind address, which nothing connects to. `oauth.provider.redirect-uris[0]` (`http://localhost*`) is listed because the boundary reads every `scheme://` value as a target, while the original only matches redirect URIs against that pattern (`http-oauth-provider.xml:29-31`) and never connects to it.
  - The demo client and owner come from profile `test`, which the project's surefire configuration activates in every test JVM (`pom.xml`, `systemPropertyVariables` `spring.profiles.active`), so replay reads them from `src/test/resources/application-test.yml`, the original's literals (`http-oauth-provider.xml:16,28`); no fixture repeats them. The `state` above configures listeners and seeds no data, and the original reaches no database, broker or file store.
  - Nothing else is substituted. The `redirect_uri` inside a request keeps the captured `8082`: the provider only matches it against `http://localhost*` and hands it back, so it need not name the replay port. The payroll `uri` is part of a body, which no substitution touches; only `get-resources-valid-token` and `post-resources-valid-token` return it, and they have no fixture (next items).
- Upstream: the original makes no outbound call. The provider module answers `/authorize` and `/token` itself, and `redirectFlow` only returns a `Location` for the browser to follow. Every fixture of this project carries `"upstream": []`, the complete recording of a scenario that made no outbound call, not a missing one, so no row is reported `UNVERIFIED-no-upstream`.
- Nondeterministic rows: `post-authorize-valid-owner`, `get-token-authorization-code`, `post-token-authorization-code`, `get-resources-valid-token`, `post-resources-valid-token` and `get-resources-insufficient-scope`.
  - Why: authorization codes and access tokens are generated at runtime. The token rows need a code, and the resource rows a token, obtained earlier in the same scenario: a login `POST /authorize` yields the `code` in its redirect `Location`; a token request for that code yields the `access_token`; for `get-resources-insufficient-scope` the login `POST /authorize` uses `scope=POST_RESOURCE`. Capture step 4 runs each chain.
  - Comparison: a `nondeterministic` fixture is replayed with its status, reason, `Content-Type` and `Location` compared exactly and its body compared for structure only, key or element names and order (AAP 0.8.4). These six rows cannot be replayed even that way, because the replay sends one recorded request per row: their recorded `<code>` or `<token>` is the value the original issued during the capture, which the Java provider never issued, and the `Location` of `post-authorize-valid-owner` carries the code the original generated. A fixture for any of them would report `FAILED` against a correct conversion. Their capture output is therefore committed as evidence in `src/test/capture/<scenario>/` without a `fixtures/<scenario>.json`, and `FixtureParityTest` reports them `UNVERIFIED-uncaptured`, never `VERIFIED`. The chain itself is exercised by the unit tier (`HttpOauthProviderTest`, AAP 0.5.6), which is not parity evidence.
  - Reporting: each is reported `UNVERIFIED — nondeterministic output` in the README and counted as unverified in the root `SUMMARY.md` (AAP 0.8.4, 0.8.7), and none is ever presented as byte-for-byte evidence. They are escalated as AAP 0.8.8 sets out: through `DECISIONS.md`, the person who receives the Tier 2A notification receives their list with this reason, and a row's status changes only on that person's reproducibility input or on a recorded acceptance, which the README shows as `ACCEPTED — structural comparison` and which still counts as unverified.
  - No row is `unreachable`.
- Replay order: `FixtureParityTest` replays the rows in table order in one application context. No `deterministic` row depends on state an earlier row leaves: none of them is granted a code or a token.
- Parity label: until fixtures are captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7), the label of `java-conversions/http-oauth-provider-java/README.md` and of this project's row in the root `SUMMARY.md`. Six of its nineteen scenarios stay unverified after capture, so the label it can reach is `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified`, at most `13 verified, 6 unverified`, or `PARITY: FAIL — Tier 2A fixtures` when any captured deterministic row differs; `PARITY: VERIFIED — Tier 2A fixtures` is impossible while the six `nondeterministic` rows stay unverified. The README lists as unverified the six `nondeterministic` scenarios, and the thirteen `deterministic` ones until each is captured and `VERIFIED`.

Table rules, for every project that copies this format:

- The header is exactly the seven columns above, with these names and in this order, followed by a separator row of `-` cells. `FixtureParityTest` fails the class on any other header, a missing separator row or an empty table.
- Cells are split on unescaped `|`, so a `|` inside a cell is written `\|`. Each cell is trimmed and unescaped, and its surrounding backticks are stripped, so code spans are allowed. The table ends at the first line that does not start with `|`.
- `Scenario` is kebab-case (`[a-z0-9]+(-[a-z0-9]+)*`), unique in the file, formed from the entry point and the branch (AAP 0.8.4), and equal to the fixture file name without `.json`.
- `Trigger` is one of `http`, `soap`, `file`, `jms`, `amqp`, `mq`, `mail`, `scheduler`. It selects the replay driver.
- `State` is one of `deterministic`, `nondeterministic`, `unreachable`. A `nondeterministic` row names in `Branch` what varies (the current time, random values, a generated name). An `unreachable` row states in `Branch` the proof that no input selects it (AAP 0.8.2).
- `Request` is the exact input the capture sends, written as: `METHOD /path` (with `?query` when there is one); then each request header as header `Name: value`, in sending order; then the body as `empty body`, as an inline code span written byte for byte (body `ping`), or as body = the bytes of `<file>` (`src/test/resources`) for a file body, which is never inlined. Non-HTTP rows name the input file, message or mail, and the single-run method the replay invokes.
- `Seeded state` is `none`, or a short description of what the fixture's `state` seeds (rows, files, messages, mailboxes, a watermark).
- `Branch` names the flow path the request selects. Outcome words written there name the branch. They are never captured values; the captured fixture alone holds the response.
- `Source` cites the original by file and line.
- Every row of this file is captured by sending its request to the unmodified original, deployed with `http.provider.port=8081` and `http.listener.port=8082` (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8081` for a `<provider>` row and `8082` for a `<listener>` row in this project, as the row's `Request` names it). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password, keyStore, keyStoreType, keyStorePassword}` for HTTPS listeners, replayed through ST-1's TLS mode; each member is a string, and only `trustStore` is required. `type` is the store type (default `JKS`) and `password` the trust store's password. `keyStore` is a client key store, for a listener that requires a client certificate: with it the replay sends through ST-1's key-store overload `HttpTestClient.https(host, port, trustStorePath, trustStoreType, trustStorePassword, keyStorePath, keyStoreType, keyStorePassword)`, which presents the store's client key; `keyStoreType` defaults to `type`, and `keyStorePassword` is the password of the key store and of its key entries. Without `keyStore` no client certificate is presented. `keyStoreType` or `keyStorePassword` without `keyStore` fails the scenario, and a project whose ST-1 lacks the key-store overload reports a scenario with `keyStore` `UNVERIFIED-missing-driver`. `trustStore` and `keyStore` resolve from the classpath first, then as a file.
- `readTimeoutMillis`: optional; the replay's socket read budget for this request, a positive whole number of milliseconds, applied through ST-1's `HttpTestClient.withReadTimeout` to every read of the reply, the TLS handshake's included; default 10000, ST-1's `TIMEOUT_MILLIS`, which also stays the connect timeout. Like `awaitMillis`, it is a replay setting, not a captured value: a fixture sets it when the endpoint itself waits about 10000 ms or longer before it answers, so that the replay reads the captured answer instead of timing out before the comparison.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`. Each table must already exist, created when the application starts or by an earlier scenario in table order: the replay creates no table, so a seeded table that does not exist fails the scenario before any row is written.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- `clearCaches`: optional, `true` or `false`, the default. `true` stands for the restart the capture made before the scenario, after which the original's in-memory stores were empty: once the `state` is seeded, and before the prior requests and the input, the replay empties every cache of every `org.springframework.cache.CacheManager` bean of the application context. A project without such a bean reports the scenario `UNVERIFIED-missing-driver`, since the replay cannot re-create the empty stores the capture started from. Without `clearCaches`, the application's caches and its other in-memory state carry over from the scenarios before, in table order.
- `requests`: optional; the prior requests the capture sent before the scenario's own (Capture step 4), in the order sent, each an object of the `request` shape for `http` and `soap` above (`method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `portProperty`, `tls`, `readTimeoutMillis`). The replay sends them through ST-1 after the `state` is seeded, its caches cleared and the `upstream` stubs installed, and before the input. Their replies are discarded, as the capture discarded them, whatever their status; one that cannot be sent, or whose reply cannot be read, fails the scenario. Everything else they cause belongs to the scenario: their outbound calls are recorded in `upstream`, in call order, and the effects read back after the run (files, messages, rows, documents, mails) include theirs. Only their log events are left out: the replay drops the events logged before the input, as `response.logs` holds the events from the input on.
- For this project, `state` holds only `properties` and `notContacted`, the same in every fixture (Replay state, in the notes under Scenarios). The original reaches no store.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every captured scenario of this project: the empty array is a complete recording, not a missing one.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and then every 100 ms until it has held exactly them throughout a 500 ms quiet window.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match, then compares it every 100 ms until it has matched throughout a 500 ms quiet window, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a port the replay owns, the same one for every use of the same name and distinct from the port of every other name. The embedded servers bind a port themselves and publish it under their names, `amqp` (Qpid Broker-J), `ldap`, `greenmail.<proto>` and `mongo`, so `${freePort:amqp}` is the broker's AMQP port. Any other name, or one of these whose server's library the project lacks, gets a port the replay reserves until the class ends with an unconnected socket on the wildcard address. Until the application context starts its listeners, that socket is bound without `SO_REUSEADDR`, so no other process and no other name binds the port, with `SO_REUSEADDR` or without it, and a connection to it is refused. Just before the embedded web server binds its listeners, the replay swaps it for a socket bound with `SO_REUSEADDR` on the same port, so the application's listeners, which set `SO_REUSEADDR` as Undertow does, bind beside it. Only between this handover and the end of the context's refresh can another socket bind the port as well: one that sets `SO_REUSEADDR` throughout, and any socket in the instant each swap takes. Once the context has refreshed, a port nothing listens on is held without `SO_REUSEADDR` again, so it stays a refused connection and a listener that binds only later cannot bind it, and a port a listener bound is left to that listener, which keeps every other socket off it; a name first used after that is held without `SO_REUSEADDR` at once. A host whose sockets do not let such a listener bind beside a shared socket gets each reservation released, not shared, at the handover, with a warning, so another process can take a port until the application's listener has bound it; once the context has refreshed, each port nothing listens on is held without `SO_REUSEADDR` again, and the ports stay distinct there too.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`: the port GreenMail's server for that protocol bound.
- `${mongo.uri}` and `${ldap.port}`: the address of the embedded MongoDB server and the port the embedded LDAP server bound.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset empties each table of `state.tables` and `response.tables` that exists in the replay database; a table that does not exist yet, such as one the scenario's own request creates, is left absent and is never created, and no table is dropped. The reset clears no application cache and no other in-memory state of the application, so scenarios that build on one another in table order keep doing so; only a fixture's `state.clearCaches` empties the application's caches, once its `state` is seeded. Its `state.requests` are sent after that, and its input last. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

Skeleton of a fixture of this project, here for a `<provider>` row; every `request` and `response` value comes from the capture, and `state` is the one of the notes under Scenarios:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>",
                "port": "<captured>", "portProperty": "http.provider.port" },
  "state":    { "properties": { "server.port": "${freePort:listener}", "http.listener.port": "${freePort:listener}",
                                "http.provider.port": 8081 },
                "notContacted": ["oauth.provider.redirect-uris[0]", "listeners.additional[0].host"] },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

A `<listener>` fixture has the same shape without `portProperty`.

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists all nineteen scenarios as `UNVERIFIED-uncaptured`, with `verified=0`, `unverified=19` and `failed=0`. After capture the six `nondeterministic` rows keep that status, because they get no fixture (see the notes under Scenarios).

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/http-oauth-provider-java/`).

1. Build the unmodified original `http-oauth-provider` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for http-oauth-provider, whose flows and provider endpoints make no outbound call. Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
   - Start WireMock on a free port `W` with an empty root directory. `--disable-gzip` passes a proxied response's `Content-Encoding` and bytes to the original unchanged, and `--preserve-user-agent-proxy-header` forwards the original's `User-Agent` on the proxied leg. Then add one proxy mapping per real upstream, whose `proxyBaseUrl` is that upstream's scheme, host and port:

     ```sh
     W=8089; rm -rf target/fixture-capture/wiremock; mkdir -p target/fixture-capture/wiremock
     java -jar "$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar" --port "$W" --root-dir target/fixture-capture/wiremock --disable-gzip --preserve-user-agent-proxy-header > target/fixture-capture/wiremock.log 2>&1 &
     WM=$!; until curl -sf "http://localhost:$W/__admin/health" > /dev/null; do sleep 1; done
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com"}}'
     ```

     With several upstreams, each mapping matches only its own upstream's paths (`"urlPathPattern": "/<prefix>(/.*)?"`); upstreams whose paths overlap cannot be told apart through one WireMock, so those scenarios omit `upstream`. `kill "$WM"` stops WireMock after the last scenario.
   - The original is pointed at WireMock by runtime properties only (`-M-D<key>=<value>` at deployment, step 3): each upstream placeholder or host setting becomes `http://localhost:<W>`, or host `localhost` and port `W`, keeping its path. An original that cannot reach WireMock this way (a hard-coded host, or a TLS setting that rejects WireMock) cannot have its calls recorded, so its scenarios omit `upstream`.
   - Live credentials (values supplied for the capture that the original folder does not already contain) are never committed. The original runs with a fixed marker in place of each, such as `capture-marker`, which the fixture records and `state.properties` gives the Java app under the same key (step 8), so the replay compares the same values. A credential sent in a request header is replaced by the live value on the proxied leg only, in that upstream's mapping, here for an `X-Api-Key` header whose live value is in the environment variable `LIVE_API_KEY`, never in a file:

     ```sh
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com", "removeProxyRequestHeaders": ["x-api-key"], "additionalProxyRequestHeaders": {"X-Api-Key": "'"$LIVE_API_KEY"'"}}}'
     ```

     WireMock 3.13.2 removes a header only when `removeProxyRequestHeaders` lists its name in lower case. A credential WireMock cannot replace (in a body, a query or a signature), a token or session the live service issues into a recorded exchange, and a response that sends the original to a host it does not reach through WireMock cannot be recorded locally without live values: those scenarios are captured with the live values in place, omit `upstream`, and keep their journal out of the evidence.
3. Deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.provider.port=8081` and `http.listener.port=8082` as runtime properties (`-M-Dhttp.provider.port=8081 -M-Dhttp.listener.port=8082`), the values `http-oauth-provider/src/main/app/mule-app.properties` defines, so that the capture ports are explicit; the original has no upstream properties. Ports 8081 and 8082 must be free on the capture host. Capture from a freshly deployed original, so that no code or token of an earlier run exists.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   P=8081; L=8082; R="http://localhost:$L/redirect"; FORM='Content-Type: application/x-www-form-urlencoded'
   mkdir -p target/fixture-capture
   # A fresh authorization code, read from the Location of a login POST; a code is single-use, so each chain logs in anew.
   code_for() {  # $1 scope, $2 working-file name
     curl -sS -i --http1.1 -X POST -H "$FORM" --data-binary "response_type=code&client_id=myclientid&redirect_uri=$R&scope=$1&username=mule&password=mule" "http://localhost:$P/authorize" > "target/fixture-capture/$2.http" || return 1
     c=$(tr -d '\r' < "target/fixture-capture/$2.http" | grep -i '^location:' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
     [ -n "$c" ] || { echo "no code in the Location of target/fixture-capture/$2.http" >&2; return 1; }
     printf '%s' "$c"
   }
   # An access token issued for a fresh code, read from the token response body.
   token_for() {  # $1 scope, $2 working-file name
     c=$(code_for "$1" "$2-code") || return 1
     curl -sS --http1.1 -X POST -H "$FORM" --data-binary "grant_type=authorization_code&client_id=myclientid&client_secret=myclientsecret&code=$c&redirect_uri=$R" "http://localhost:$P/token" > "target/fixture-capture/$2-token.json" || return 1
     jq -er .access_token "target/fixture-capture/$2-token.json" || { echo "no access_token in target/fixture-capture/$2-token.json" >&2; return 1; }
   }
   capture get-authorize-login-page "http://localhost:$P/authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE&redirect_uri=$R"
   capture get-authorize-unknown-client "http://localhost:$P/authorize?response_type=code&client_id=unknownclient&scope=READ_RESOURCE&redirect_uri=$R"
   capture get-authorize-invalid-redirect-uri "http://localhost:$P/authorize?response_type=code&client_id=myclientid&scope=READ_RESOURCE&redirect_uri=http://evil.example/cb"
   capture post-authorize-valid-owner -X POST -H "$FORM" --data-binary "response_type=code&client_id=myclientid&redirect_uri=$R&scope=READ_RESOURCE&username=mule&password=mule" "http://localhost:$P/authorize"
   capture post-authorize-wrong-password -X POST -H "$FORM" --data-binary "response_type=code&client_id=myclientid&redirect_uri=$R&scope=READ_RESOURCE&username=mule&password=wrongpassword" "http://localhost:$P/authorize"
   C=$(code_for READ_RESOURCE get-token-authorization-code) && capture get-token-authorization-code "http://localhost:$P/token?grant_type=authorization_code&&client_id=myclientid&client_secret=myclientsecret&code=$C&redirect_uri=$R"
   C=$(code_for READ_RESOURCE post-token-authorization-code) && capture post-token-authorization-code -X POST -H "$FORM" --data-binary "grant_type=authorization_code&client_id=myclientid&client_secret=myclientsecret&code=$C&redirect_uri=$R" "http://localhost:$P/token"
   capture get-token-invalid-code "http://localhost:$P/token?grant_type=authorization_code&client_id=myclientid&client_secret=myclientsecret&code=invalidcode&redirect_uri=$R"
   capture get-token-wrong-client-secret "http://localhost:$P/token?grant_type=authorization_code&client_id=myclientid&client_secret=wrongsecret&code=invalidcode&redirect_uri=$R"
   T=$(token_for READ_RESOURCE get-resources-valid-token) && capture get-resources-valid-token -H "Authorization: Bearer $T" "http://localhost:$L/resources"
   T=$(token_for READ_RESOURCE post-resources-valid-token) && capture post-resources-valid-token -X POST -H "Authorization: Bearer $T" "http://localhost:$L/resources"
   capture get-resources-missing-token "http://localhost:$L/resources"
   capture get-resources-invalid-token -H 'Authorization: Bearer invalidtoken' "http://localhost:$L/resources"
   T=$(token_for POST_RESOURCE get-resources-insufficient-scope) && capture get-resources-insufficient-scope -H "Authorization: Bearer $T" "http://localhost:$L/resources"
   capture get-redirect-with-code "http://localhost:$L/redirect?code=abc"
   capture get-redirect-without-code "http://localhost:$L/redirect"
   capture get-unknown-path-listener-port "http://localhost:$L/unknown"
   capture get-resources-on-provider-port "http://localhost:$P/resources"
   capture delete-token-method-not-allowed -X DELETE "http://localhost:$P/token"
   ```

   The commands run in table order. Each chained row first runs its helper: `code_for` logs in with `POST /authorize` and reads the `code` from the redirect `Location`, and `token_for` also exchanges that code at `/token` and reads `access_token` from the response body; the scenario's own request then carries the value. A helper that finds no code or token stops its row, which is captured again. The helpers' responses stay under `target/fixture-capture/`, never committed. This capture supplies no live value: the client and owner credentials are the original's own literals (`http-oauth-provider.xml:16,28`).

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); `"port"`, the port of the URL the scenario's command targets (`$P` is `8081`, `$L` is `8082`); and, for `8081`, `"portProperty": "http.provider.port"`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-redirect-with-code/response.http GET <<'PY'
   import base64, io, json, pathlib, re, sys
   raw, method = io.BytesIO(pathlib.Path(sys.argv[1]).read_bytes()), sys.argv[2]
   MAX_BODY_LENGTH = 2**31 - 1 - 8

   def line():
       data = raw.readline()
       if not data.endswith(b'\n'):
           sys.exit('capture ends inside a line')
       return data[:-2] if data.endswith(b'\r\n') else data[:-1]

   while True:
       status_line = line().decode('latin-1')
       version, _, rest = status_line.partition(' ')
       code, _, reason = rest.partition(' ')
       digits = code.lstrip('0') or '0'
       if not version or not re.fullmatch('[0-9]+', code) or len(digits) > 10 or int(digits) > 2**31 - 1:
           sys.exit('malformed status line: ' + repr(status_line))
       status = int(digits)
       headers = []
       while field := line():
           if field[:1] in (b' ', b'\t'):
               sys.exit('folded header line')
           name, colon, value = field.decode('latin-1').partition(':')
           if not colon:
               sys.exit('malformed header line: ' + repr(name))
           headers.append((name.lower(), value.strip(' \t')))
       if status // 100 != 1 or status == 101:
           break

   def values(name):
       return [v for n, v in headers if n == name]

   def number(text, base, what, limit=MAX_BODY_LENGTH):
       text = text.strip(' \t')
       if not re.fullmatch('[0-9A-Fa-f]+' if base == 16 else '[0-9]+', text):
           sys.exit('malformed ' + what + ': ' + repr(text))
       digits = text.lstrip('0') or '0'
       if len(digits) > 10 or int(digits, base) > limit:
           sys.exit(what + ' too large for an in-memory body: ' + text)
       return int(digits, base)

   coding = ','.join(values('transfer-encoding')).split(',')[-1].split(';')[0].strip(' \t').lower()
   if method == 'HEAD' or status // 100 == 1 or status in (204, 304):
       body = b''
   elif coding == 'chunked':
       body = b''
       while size := number(line().decode('latin-1').split(';')[0], 16, 'chunk size',
                            MAX_BODY_LENGTH - len(body)):
           chunk = raw.read(size)
           if len(chunk) != size or line():
               sys.exit('malformed chunk')
           body += chunk
       while line():
           pass
   elif coding or not values('content-length'):
       body = raw.read()
   else:
       lengths = {number(n, 10, 'Content-Length') for n in ','.join(values('content-length')).split(',')}
       if len(lengths) > 1:
           sys.exit('conflicting Content-Length values: ' + ', '.join(values('content-length')))
       length = lengths.pop()
       body = raw.read(length)
       if len(body) != length:
           sys.exit('capture ends inside the body')
   if raw.read():
       sys.exit('bytes after the message')
   response = {'status': status, 'reason': reason, 'headers': {}}
   for name in ('Content-Type', 'Location'):
       found = values(name.lower())
       if len(found) > 1:
           sys.exit('repeated ' + name)
       if found:
           response['headers'][name] = found[0]
   try:
       response['body'] = body.decode('utf-8')
   except UnicodeDecodeError:
       response['bodyBase64'] = base64.b64encode(body).decode('ascii')
   print(json.dumps(response, indent=2))
   PY
   ```

7. `upstream` is `[]` when the original made no outbound call, as in every scenario of this project. Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

   ```sh
   S=<scenario>; J="target/fixture-capture/$S-journal.json"
   jq '[.requests | reverse[] | {request, response}]' "$J" > "src/test/capture/$S/upstream-journal.json"
   jq 'def pairs: [to_entries[] | .key as $k | (.value | if type == "array" then .[] else . end) | [$k, .]];
     [.requests | reverse[]
      | if .responseDefinition.proxyBaseUrl == null then error("not proxied: \(.request.method) \(.request.url)")
        elif (.response.body // "") | startswith("Network failure trying to make a proxied request") then error("upstream not reached: \(.request.url)")
        elif .request.headers // {} | keys | map(ascii_downcase) | index("content-encoding") then error("encoded request body: \(.request.url)")
        else {request: {method: .request.method, url: .request.url, headers: (.request.headers // {} | pairs), bodyBase64: .request.bodyAsBase64},
              response: {status: .response.status, headers: (.response.headers // {} | pairs), bodyBase64: .response.bodyAsBase64}}
        end]' "$J"
   ```

   The journal lists the newest call first, so its entries are reversed into call order. `url` is the path and query relative to the WireMock base, so the fixture names no live host. A repeated header name becomes consecutive pairs at the position of its first occurrence, the order the journal keeps. Bodies are `bodyBase64`, the entity bytes as recorded: a chunked body in either direction is de-chunked with its trailer fields dropped, and a gzip-encoded response body keeps its encoded bytes and its `Content-Encoding` header, as step 6 defines for `response`. The command fails on a call no mapping proxied or that did not reach its upstream, which is captured again once the mapping is fixed, and on a request body with a `Content-Encoding`, which WireMock stores decoded and re-encodes on the proxied leg, so that scenario omits `upstream`.
8. Write `fixtures/<scenario>.json` for each of the thirteen `deterministic` rows from `request` (step 5), `state` (the replay state of the notes under Scenarios, the same in every fixture of this project), `upstream` (step 7) and `response` (step 6), every value as produced. The six `nondeterministic` rows keep their committed evidence in `src/test/capture/<scenario>/` and get no fixture (Nondeterministic rows, in the notes under Scenarios). For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code: for `get-unknown-path-listener-port` and `get-resources-on-provider-port`, that means SC-10 `PortRoutingFilter` and SC-1 `GlobalExceptionHandler`; for `delete-token-method-not-allowed`, SC-1 `GlobalExceptionHandler`; for the provider and resource rows, `service/OAuthProviderService`, `service/ResourceOwnerAuthenticator` and the controllers. No fixture is written for a `nondeterministic` row of this project. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
