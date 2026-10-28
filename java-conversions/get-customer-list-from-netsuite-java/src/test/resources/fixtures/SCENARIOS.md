# Parity scenarios — get-customer-list-from-netsuite-java

Scenarios for the fixture replay (ST-2) of `get-customer-list-from-netsuite-java`. The original example is `get-customer-list-from-netsuite` (folder `get-customer-list-from-netsuite/`). Its single flow `get-customer-list-from-netsuiteFlow` answers every HTTP request on the `/customers` listener: an expression filter drops a request without a `lastName` query parameter, `netsuite:query-records` selects the customers whose last name starts with that value, a DataWeave script renders one `line/tr/td` row per customer or the single row `No customers found`, and `parse-template` injects the rows into `customer/index.html`. Each branch listed for the example in AAP 0.7.4 (customers found, none found, the filter rejection) is one scenario below, as are the second request the filter rejects (a `lastName` without a value), a second method on the listener and the listener-level default an unserved path selects; each scenario's captured fixture is `fixtures/<scenario>.json`. The last four rows are the approved connector failure modes of AAP 0.4.8 (a re-authentication rejected again, throttling, a timeout, a refused connection), which are never captured (notes below).

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.4, the four approved connector failure-mode rows from AAP 0.4.8.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-customers-lastname-match` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | tenant customers, at least one whose last name starts with `a`; tenant data that only an `upstream` recording carries (notes below) | `/customers` listener → expression filter accepts (`lastName` present) → customer query for last names starting with the value, ordered by last name → one `line/tr/td` row per customer (last name, first name, email) → `object-to-string` `text/html` → `customer/index.html` template without the XML declaration line | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:8-35`, `get-customer-list-from-netsuite/src/test/java/org/mule/examples/GetCustomersListFromNetsuiteIT.java:33-34,64-66` |
| `post-customers-lastname-match` | `http` | `deterministic` | `POST /customers?lastName=a`, header `Content-Type: application/x-www-form-urlencoded` (curl's default for `--data-binary`), empty body | as `get-customers-lastname-match` | any method is accepted because the listener declares no `allowedMethods` (AAP 0.4.9), so the POST takes the path of `get-customers-lastname-match`: filter accepts → customer query → one row per customer → template | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:8,9-35` |
| `get-customers-no-match` | `http` | `deterministic` | `GET /customers?lastName=zzqxNoMatch`, empty body | tenant customers, none whose last name starts with `zzqxNoMatch`; tenant data that only an `upstream` recording carries (notes below) | filter accepts → customer query returns no record → DataWeave `otherwise` row `No customers found` → template | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:26-31` |
| `get-customers-missing-lastname` | `http` | `deterministic` | `GET /customers`, empty body | none | expression filter rejects the request (no `lastName`) and the flow ends without a payload → filter-rejection platform default, 200 with an empty body under AAP 0.4.7; a Tier 2A verification point where the captured bytes govern | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:9` |
| `get-customers-lastname-without-value` | `http` | `deterministic` | `GET /customers?lastName`, empty body | none | Mule 3.8's query parser (`HttpParser.decodeUrlEncodedBody`) stores a pair without `=` with a null value, so the expression filter rejects the request as in `get-customers-missing-lastname` → filter-rejection platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:9` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | none | no-listener platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:5,8` |
| `get-customers-reauthentication-failed` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | a WireMock stub at `netsuite.base-url` that answers the token `POST /services/rest/auth/oauth2/v1/token` with an access token, and answers the SuiteQL `POST /services/rest/query/v1/suiteql` with HTTP 401 on the first call and again on the retry | approved connector failure mode (AAP 0.4.8), not an original-parity comparison: filter accepts → token request → SuiteQL rejected with 401 → `NetsuiteFailureClassifier` authentication → re-authentication step `NetsuiteTokenProvider.evict()` drops the cached token → one retry, which requests one new token and is rejected with 401 again → the attempt ends → SC-9 `UpstreamExceptionHandler` 502 with `upstream` `NetSuite` | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4,10` |
| `get-customers-rate-limited` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | a WireMock stub at `netsuite.base-url` that answers the token request with an access token, and answers the SuiteQL request with HTTP 429 and a `Retry-After` header | approved connector failure mode (AAP 0.4.8), not an original-parity comparison: filter accepts → token request → SuiteQL answered 429 → `NetsuiteFailureClassifier` rate limit → no retry → SC-9 `UpstreamExceptionHandler` 429 with NetSuite's `Retry-After` copied and `upstream` `NetSuite` | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:10` |
| `get-customers-upstream-timeout` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | a WireMock stub at `netsuite.base-url` that answers the token request with an access token, and answers the SuiteQL request later than `netsuite.read-timeout` (10 s) | approved connector failure mode (AAP 0.4.8), not an original-parity comparison: filter accepts → token request → no SuiteQL response within `netsuite.read-timeout` (Netty `ReadTimeoutException`) → `NetsuiteFailureClassifier` unavailable → never retried → SC-9 `UpstreamExceptionHandler` 503 with `upstream` `NetSuite` | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:10` |
| `get-customers-upstream-unreachable` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | `netsuite.base-url` = `http://127.0.0.1:1`, a loopback port with no listener | approved connector failure mode (AAP 0.4.8), not an original-parity comparison: filter accepts → the token request, the first NetSuite request, is refused (`ConnectException`) → `NetsuiteFailureClassifier` unavailable → never retried, and no SuiteQL request is sent → SC-9 `UpstreamExceptionHandler` 503 with `upstream` `NetSuite` | `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4,10` |

No scenario of this project is `unreachable` or `nondeterministic`: every branch is selected by its request and the tenant's customers, or, in the four approved connector failure-mode rows, by its request and the controlled upstream setup, and the flow reads no clock, random value or generated name.

Notes for this project:

- Upstream: the original queries NetSuite through SuiteTalk SOAP (`netsuite:query-records`, logging in with email, password and role, `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4,10`) at the connector's built-in endpoint, which no runtime property points at WireMock (Capture step 2). This project calls NetSuite REST with OAuth 2.0 M2M instead (SC-16): the token `POST /services/rest/auth/oauth2/v1/token`, then SuiteQL `POST /services/rest/query/v1/suiteql` with `Prefer: transient`, one call per page. A SOAP recording therefore cannot drive replay, and the three NetSuite-backed rows (`get-customers-lastname-match`, `post-customers-lastname-match`, `get-customers-no-match`) would need a REST recording taken against the same tenant state, by running this project with `netsuite.base-url` pointed at a WireMock recording proxy in front of the tenant and covering the token POST and every SuiteQL page; their `request` and `response` still come from the original's capture.
- That recording cannot become a fixture's `upstream`. The token POST body carries the client assertion, a PS256-signed JWT whose `iat` and signature change on every request and whose `aud` names the token URL, so it never matches a recorded body; its response carries an access token the live tenant issues (a client-credentials token valid 60 minutes, with no refresh token); and a deterministic row's replay matches every recorded upstream body byte for byte and every non-transport header, the `Authorization` header included (Fixture contract, `upstream`). Under Capture steps 2 and 8 these three rows therefore omit `upstream`: each is skipped and reported `UNVERIFIED-no-upstream`, never `VERIFIED` and never failed, and is listed among the unverified scenarios of the AAP 0.8.8 escalation. No live credential appears in any fixture.
- Replay properties: every fixture's `state.properties` sets `netsuite.base-url` to `${wiremock.baseUrl}`, the same value in every fixture. The key is empty in `src/main/resources/application.yml`, where the client derives the live account host from `netsuite.account`, so the replay boundary fails the class without the override. A fixture that needs any other `netsuite.*` value gives it a non-secret dummy, as `../application-test.yml` does (`netsuite.account` `TSTDRV1234567`, `netsuite.oauth.client-id` `test-client-id`, `netsuite.oauth.certificate-id` `test-certificate-id`); `netsuite.oauth.private-key-path`, when given, names a throwaway test-only RSA key, never the tenant's key, since replay does not verify the assertion's signature. These keys are defined in `src/main/resources/application.yml` and bound by `config/NetsuiteProperties` (prefix `netsuite`).
- Query hardening is not a scenario: a `lastName` holding a quote or a backslash exercises the one intentional difference for hostile input (AAP 0.4.10), the SuiteQL literal escaping that replaces the original's raw concatenation of the value into its query. `DECISIONS.md` records it, and no row covers it.
- Approved connector failure modes: `get-customers-reauthentication-failed`, `get-customers-rate-limited`, `get-customers-upstream-timeout` and `get-customers-upstream-unreachable` are approved additions (AAP 0.4.8), the only exception to behaviour parity (AAP 0.10.1), not original-parity comparisons. The original has no such branch: its flow declares no exception strategy (`get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:7-36`), so a connector failure got the Mule default, the platform default of AAP 0.4.7. A capture of the original would therefore record the answer the addition replaces by design, its replay would be `FAILED`, and the Integrity rule would then demand undoing an approved behaviour. These four rows are never captured and no fixture is ever written for them: `FixtureParityTest` reports each `UNVERIFIED-uncaptured`, each counts as unverified in the `PARITY` label, and the README lists them among the unverified scenarios; none is ever `VERIFIED` or PASS. Each row's `Seeded state` is the controlled setup of its Tier 1 evidence: a WireMock stub at `netsuite.base-url`, or, for `get-customers-upstream-unreachable`, the closed loopback address `http://127.0.0.1:1`, fixed so that every run is refused the same way; the 503 body carries only `NetSuite is unreachable` and never names the refused address. That evidence is `client/NetsuiteRestClientTest` (`unauthorizedRetryThatIsRejectedAgainEndsTheAttempt`, with two token requests and two SuiteQL calls; `rateLimitIsNotRetriedAndCarriesRetryAfter`; `readTimeoutIsUnavailableAndIsNeverRetried`; `closedPortIsUnavailableWithAConnectExceptionAndIsNeverRetried`, in which the token request meets the refusal and no request reaches NetSuite), `client/NetsuiteFailureClassifierTest` (`unauthorizedOnEveryAttemptEvictsTheTokenOnceAndFailsAfterOneRetry`, `tooManyRequestsIsNeverRetried`, `timeoutAndConnectivityFailuresAreUnavailableAndNeverRetried`), `client/UpstreamCallExecutorTest` (`classifiedFailureEndsTheAttemptAtItsStage`, `timeoutOrConnectivityFailureIsNeverRetried`), `service/NetsuiteCustomerServiceTest` (`upstreamFailurePropagatesUnchanged`) and `exception/UpstreamExceptionHandlerTest` (`authenticationIs502`, `rateLimitIs429WithRetryAfter`, `unavailableIs503`). At request level, `controller/NetsuiteCustomerControllerTest` drives the first three modes through the running application against a WireMock NetSuite and asserts the status, reason phrase, body and upstream call counts of each (`tokenRejectedTwiceIsReauthenticatedOnceAndAnswered502`, two token requests and two SuiteQL calls; `rateLimitIsNotRetriedAndCopiesRetryAfter`; `upstreamTimeoutIsNeverRetriedAndAnswered503`, one SuiteQL call each).
- Integrity: no fixture JSON is committed before Tier 2A capture, and no expected value is written by hand from the flow. A captured fixture is never edited to match the Java output; a mismatch is fixed in the Java code (AAP 0.8.4, Integrity rule).
- Parity label: until fixtures are captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7) in `java-conversions/get-customer-list-from-netsuite-java/README.md` and in its row of the root `SUMMARY.md`. Meanwhile `GetCustomersListFromNetsuiteTest` carries the original contains-assertion `<h1>Netsuite Customer List</h1>` for `lastName=a` (`get-customer-list-from-netsuite/src/test/java/org/mule/examples/GetCustomersListFromNetsuiteIT.java:33-34,66`) against WireMock NetSuite, and `controller/NetsuiteCustomerControllerTest` covers the branch of every original-parity row; this evidence is weaker than captured fixtures. While the three NetSuite-backed rows stay `UNVERIFIED-no-upstream` and the four approved connector failure-mode rows stay `UNVERIFIED-uncaptured`, the best label Tier 2A can give this project is `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified`.

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
- Every row of this file is captured by sending its request to the original deployed with `http.port=8081` (see Capture), except the four approved connector failure-mode rows: the original has no such branch, so they are never captured and get no fixture, their `Seeded state` names the controlled upstream setup rather than a fixture's `state`, and their `Branch` names the path of the Java application (notes above).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8081` in this project). It is the port replaced inside `Location` on replay.
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
- For get-customer-list-from-netsuite, `state` is `{"properties": {"netsuite.base-url": "${wiremock.baseUrl}"}}` in every fixture: the original reaches no store, and this key, which `config/NetsuiteProperties` binds (empty in `src/main/resources/application.yml`, where the account-derived host applies), points every NetSuite call of the Java app at the WireMock server that serves `upstream`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of `get-customers-missing-lastname`, `get-customers-lastname-without-value` and `get-unknown-path`, which end at the filter before the NetSuite query or reach no flow; the three NetSuite-backed rows omit `upstream`, and the four approved connector failure-mode rows have no fixture at all (notes under Scenarios).
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
- `${freePort:<name>}`: a port the replay owns, the same one for every use of the same name and distinct from the port of every other name. The embedded servers bind a port themselves and publish it under their names, `amqp` (Qpid Broker-J), `ldap`, `greenmail.<proto>` and `mongo`, so `${freePort:amqp}` is the broker's AMQP port. Any other name, or one of these whose server's library the project lacks, gets a port the replay reserves until the class ends with an unconnected socket on the wildcard address. Until the application context creates its embedded web server, or, in a context without one, until just before the web server start phase, that socket is bound without `SO_REUSEADDR`, so no other process and no other name binds the port, with `SO_REUSEADDR` or without it, and a connection to it is refused. At that handover, before any listener of the server is probed or bound, the replay swaps it for a socket bound with `SO_REUSEADDR` on the same port and keeps that shared socket until the context's refresh ends, so the application's listeners, which set `SO_REUSEADDR` as Undertow does, bind beside it, and so does a start-up check that binds a listener's port to see that it is free. Only between this handover and the end of the context's refresh can another socket bind the port as well: one that sets `SO_REUSEADDR` throughout, and any socket in the instant each swap takes. Once the context has refreshed, a port nothing listens on is held without `SO_REUSEADDR` again, so it stays a refused connection and a listener that binds only later cannot bind it, and a port a listener bound is left to that listener, which keeps every other socket off it; a name first used after that is held without `SO_REUSEADDR` at once. A host whose sockets do not let such a listener bind beside a shared socket gets each reservation released, not shared, at the handover, with a warning, so another process can take a port until the application's listener has bound it; once the context has refreshed, each port nothing listens on is held without `SO_REUSEADDR` again, and the ports stay distinct there too.
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

Skeleton of a fixture of this project that makes no outbound call; every `request` and `response` value comes from the capture, and `state` is written as Capture step 8 defines. A NetSuite-backed row's fixture has the same shape without the `upstream` key:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>" },
  "state":    { "properties": { "netsuite.base-url": "${wiremock.baseUrl}" } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For get-customer-list-from-netsuite the summary then holds `verified=0`, `unverified=10` and `failed=0`, followed by one `scenario.<name>=UNVERIFIED-uncaptured` line for each of `get-customers-lastname-match`, `post-customers-lastname-match`, `get-customers-no-match`, `get-customers-missing-lastname`, `get-customers-lastname-without-value`, `get-unknown-path`, `get-customers-reauthentication-failed`, `get-customers-rate-limited`, `get-customers-upstream-timeout` and `get-customers-upstream-unreachable`, in that order. The last four keep that status after capture, because they are never captured (notes under Scenarios).

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/get-customer-list-from-netsuite-java/`).

1. Build the unmodified original `get-customer-list-from-netsuite` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; for get-customer-list-from-netsuite, the one upstream is NetSuite SuiteTalk SOAP at the connector's built-in endpoint, which no runtime property points at WireMock, so the original's calls are not recorded and the three NetSuite-backed rows omit `upstream` (notes under Scenarios). Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
3. Deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.port=8081` as a runtime property (for example `-M-Dhttp.port=8081`), because `get-customer-list-from-netsuite/src/main/app/mule-app.properties` defines no value, together with `netsuite.account`, `netsuite.email`, `netsuite.password` and `netsuite.roleId`, which it also leaves empty: the tenant's SuiteTalk login, passed from environment variables and never written to a file. The original has no upstream property to point at WireMock (step 2).
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   capture get-customers-lastname-match 'http://localhost:8081/customers?lastName=a'
   capture post-customers-lastname-match --data-binary '' 'http://localhost:8081/customers?lastName=a'
   capture get-customers-no-match 'http://localhost:8081/customers?lastName=zzqxNoMatch'
   capture get-customers-missing-lastname 'http://localhost:8081/customers'
   capture get-customers-lastname-without-value 'http://localhost:8081/customers?lastName'
   capture get-unknown-path 'http://localhost:8081/unknown'
   ```

   `--data-binary ''` makes curl send `POST` with `Content-Length: 0` and `Content-Type: application/x-www-form-urlencoded`; the `post-customers-lastname-match` fixture's `request.headers` records the headers curl actually sent (step 5), so replay sends the same request. Before the three NetSuite-backed rows the tenant holds at least one customer whose last name starts with `a` and none whose last name starts with `zzqxNoMatch`. The four approved connector failure-mode rows have no `capture` line: they are never captured (notes under Scenarios).

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

   For get-customer-list-from-netsuite no `capture` line runs inside this journal block, because the original's NetSuite calls do not pass through WireMock (step 2).

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); and `"port": 8081`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-customers-lastname-match/response.http GET <<'PY'
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

7. `upstream` is `[]` when the original made no outbound call, as in `get-customers-missing-lastname`, `get-customers-lastname-without-value` and `get-unknown-path`; the three NetSuite-backed rows omit it (step 2). Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (`{"properties": {"netsuite.base-url": "${wiremock.baseUrl}"}}` for get-customer-list-from-netsuite), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `get-unknown-path`, that means SC-1 `GlobalExceptionHandler`; for the two filter-rejection rows, the guard clause of `service/NetsuiteCustomerService`; and for the three NetSuite-backed rows, `controller/NetsuiteCustomerController`, `service/NetsuiteCustomerService`, `mapper/CustomerHtmlMapper`, SC-14 `mapper/ParseTemplateRenderer` and SC-16 `client/NetsuiteRestClient`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
