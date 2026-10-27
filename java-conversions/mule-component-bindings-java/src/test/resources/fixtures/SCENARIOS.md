# Parity scenarios — mule-component-bindings-java

Scenarios for the fixture replay (ST-2) of `mule-component-bindings-java`. The original example is `mule-component-bindings` (folder `mule-component-bindings/`). Its flow `mule-component-bindingsFlow1` answers every HTTP request on the `/` listener of `localhost:8081` with a fixed Twitter search (`AAPL`, 2014-10-26 to 2014-11-04) whose result `TwitterResponseProcessor` maps. Its flow `StockServiceFlow` serves the Jersey resource `StockStatsResource` on the `/api/*` listener of `localhost:8180`: `StockStatsResourceImpl.search` validates the query parameters `stock` and `date`, reads the day's prices from Stocklytics, pages through the tweets about `$<stock>` that the bound `TwitterService` fetches over VM `twitterFlowRequest` from flow `TwitterFlow`, classifies them with Sentiment140 and returns the stock statistics. Every reachable branch listed for the example in AAP 0.7.5, the resource's validation, invalid-symbol and failure branches, and the listener-boundary cases of AAP 0.4.7 are exactly one scenario each below, and each captured scenario's fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-root-twitter-search` | `http` | `deterministic` | port `8081`: `GET /`, empty body | none; outbound: Twitter API v1.1 `GET search/tweets.json` with `q` `AAPL`, `since` 2014-10-26 and `until` 2014-11-04, as the connector encodes them (notes: Twitter) | `/` listener → `twitter:search` with the fixed `query`, `since` and `until` → `TwitterResponseProcessor` → its payload, as the listener writes it | `mule-component-bindings/src/main/app/mule-component-bindings.xml:31-36` |
| `post-root-twitter-search` | `http` | `deterministic` | port `8081`: `POST /`, empty body | none; outbound: the Twitter exchange of `get-root-twitter-search` | any method reaches the same flow because the listener declares no `allowedMethods` (AAP 0.4.9) → fixed Twitter search → `TwitterResponseProcessor` | `mule-component-bindings/src/main/app/mule-component-bindings.xml:31-35` |
| `get-root-unknown-path` | `http` | `deterministic` | port `8081`: `GET /favicon.ico`, empty body | none | the only listener of port 8081 has path `/`, so no listener serves the path → no-listener platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `mule-component-bindings/src/main/app/mule-component-bindings.xml:31,33` |
| `get-stock-stats-wrong-port` | `http` | `deterministic` | port `8081`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | none | port 8081 has only the `/` listener, so no listener serves the stock-statistics path there → no-listener platform default (AAP 0.4.7, SC-10 `PortRoutingFilter`); a Tier 2A verification point where the captured bytes govern | `mule-component-bindings/src/main/app/mule-component-bindings.xml:31,33,40,42` |
| `get-stock-stats` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body (the URL of `mule-component-bindings/README.md:29`) | none; outbound, in call order: Stocklytics `GET /historicalPrices/1.0` with headers `api_key`, `format` `JSON`, `stock`, and `start` and `end` set to the date, answered 200 with the day's prices; Twitter `GET search/tweets.json` with `q` `$AAPL`, `since` the date and `until` the date + 10 days, answered with an empty page; Sentiment140 `POST /api/bulkClassifyJson` with header `appid`, made for an empty tweet list too (notes: Twitter, Stocklytics and Sentiment140) | `/api/*` listener → Jersey `StockStatsResource` `@GET` `search` → validation passes → Stocklytics `getHistoricalPrices` → `TwitterService.search` binding over VM `twitterFlowRequest` → `TwitterFlow` → an empty page ends the paging loop → Sentiment140 `classify` → the stock statistics | `mule-component-bindings/src/main/app/mule-component-bindings.xml:40-51,56-61`, `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:35-96`, `mule-component-bindings/README.md:29` |
| `get-stock-stats-more-tweets` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=<D>`, empty body (`<D>`: Request notes) | none; outbound, in call order: Stocklytics as in `get-stock-stats`; Twitter `GET search/tweets.json`, answered with a non-empty page on every call (notes: paging); Sentiment140 `POST /api/bulkClassifyJson` | paging loop: every page is non-empty, so it repeats until more than 1000 tweets have loaded → `setMoreTweets(true)` → the list is trimmed to 1000 → Sentiment140 `classify` | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:65-88`, `mule-component-bindings/src/main/java/stockstats/impl/mule/TwitterRequestProcessor.java:41-42`, `mule-component-bindings/src/main/app/mule-component-bindings.xml:56-61` |
| `get-stock-stats-missing-stock` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?date=2012-11-28`, empty body | none | `StringUtils.isBlank(stock)` → `WebApplicationException` 400 `stock is required`; no outbound call | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:38-39,97-98` |
| `get-stock-stats-missing-date` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL`, empty body | none | `StringUtils.isBlank(dateStr)` → `WebApplicationException` 400 `date is required`; no outbound call | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:41-42,97-98` |
| `get-stock-stats-bad-date-length` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-1-26`, empty body | none | length check: the date is not as long as `yyyy-MM-dd` → `WebApplicationException` 400 `date format must be YYYY-MM-DD`; no outbound call | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:44-45,97-98` |
| `get-stock-stats-unparseable-date` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=abcd-ef-gh`, empty body | none | the length check passes and `SimpleDateFormat.parse` throws `ParseException` → `WebApplicationException` 400 with the same text `date format must be YYYY-MM-DD`; no outbound call | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:48-54,97-98` |
| `get-stock-stats-invalid-symbol` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=XYZ&date=2012-11-28`, empty body | none; outbound: Stocklytics `GET /historicalPrices/1.0` with header `stock` `XYZ`, answered 200 with a body containing `Error: Stocklytics doesn't have data for that stock.` (notes: Stocklytics and Sentiment140) | `InvalidStockSymbol` → `WebApplicationException` 400 `Invalid stock symbol XYZ`; neither Twitter nor Sentiment140 is called | `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:58-62`, `mule-component-bindings/src/main/java/stockstats/impl/stocklytics/StocklyticsStockService.java:66-69` |
| `get-stock-stats-upstream-failure` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | none; outbound: Stocklytics `GET /historicalPrices/1.0`, answered with a status other than 200 (notes: Stocklytics and Sentiment140) | `RuntimeException` `Stockylitics returned status <n>` (the original spelling) → catch-all → `WebApplicationException` 500 with the exception message; neither Twitter nor Sentiment140 is called | `mule-component-bindings/src/main/java/stockstats/impl/stocklytics/StocklyticsStockService.java:60-62`, `mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java:99-101` |
| `post-stock-stats-method-not-allowed` | `http` | `deterministic` | port `8180`: `POST /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | none | Jersey matches `@Path("/stockStats")`, whose only method is `@GET` → JAX-RS method not allowed (405); `search` is not invoked | `mule-component-bindings/src/main/java/stockstats/StockStatsResource.java:23,28`, `mule-component-bindings/src/main/app/mule-component-bindings.xml:42-51` |
| `get-api-unknown-resource` | `http` | `deterministic` | port `8180`: `GET /api/unknown`, empty body | none | `/api/*` listener → Jersey has no resource for `/unknown` → JAX-RS not found (404) | `mule-component-bindings/src/main/app/mule-component-bindings.xml:42-51`, `mule-component-bindings/src/main/java/stockstats/StockStatsResource.java:23` |

All fourteen scenarios are `deterministic`: each output is a function of the request and of the upstream responses the original received. No scenario of this project is `unreachable` or `nondeterministic`.

Request notes:

- Every request is sent with curl's default headers only and an empty body, so no `Request` cell lists a header.
- `<D>` in `get-stock-stats-more-tweets` is a `YYYY-MM-DD` date chosen when the row is captured: a trading day for which Stocklytics returns prices and the Twitter search for `$AAPL` from that date returns at least one tweet. The capture writes the date it sent into this row's `Request` and its fixture records it; it is an input, not an expected value.
- `get-stock-stats` and `get-stock-stats-upstream-failure` send the same request. Only the Stocklytics response the original received selects between them, so each is captured only with the Stocklytics response its `Seeded state` names.

Notes for this project:

- Seeded state: no scenario seeds data, so every `Seeded state` cell starts with `none`. Where the branch depends on outbound calls, the cell then names them in call order, with the condition each response must meet to select the branch. They are the exchanges a complete `upstream` recording holds (Capture steps 2 and 7; the notes below say which rows can have one), never seeded `state` and never expected values.
- Ports: every `Request` starts with the listener port the capture targets. The four `8081` rows reach the main listener (`mule-component-bindings.xml:31,33`), which `src/main/resources/application.yml` binds as `server.port: ${ports.twitter-search}` (`8081`) and which replays on the random `server.port`, so their fixtures leave `request.portProperty` at its default `local.server.port`; they are the only rows that use the replay port, and, as in AAP 0.8.4, the port inside `Location` is the only substitution. The ten `8180` rows target the extra listener of SC-10, which `application.yml` binds to `ports.stock-stats` (`8180`); its fixture names that key in `request.portProperty` and the captured port in `request.port`. Both listeners bind `localhost`, as both `http:listener-config` elements declare (`mule-component-bindings.xml:31,40`). Port 8081 serves `/` only and port 8180 `/api/**`; a path requested on the wrong port gets the no-listener 404 from SC-10 `PortRoutingFilter`.
- Replay listener port: every fixture of this project sets `ports.stock-stats` to `${freePort:stock-stats}` in `state.properties`, the same value in every fixture, so the extra listener replays on one free port that the replay owns. The captured port `8180` is not pinned as a plain value: `application.yml` declares the listener as `listeners.additional[0]` with `host: localhost` and `port: ${ports.stock-stats}`, a host key the replay boundary reads with its sibling `port` and accepts only on a replay-owned port, so a plain `8180` would stop the application context and fail the class. The `Host` the replay writes names the free port, and replay substitutes no port outside `Location`.
- Upstreams and base URLs: the original calls Stocklytics at `http://api.stocklytics.com/` and Sentiment140 at `http://www.sentiment140.com/api/`, compiled constants of the original classes (`StocklyticsStockService.java:36`, `Sentiment140SentimentService.java:38`) rather than placeholders, and Twitter through the twitter4j REST base `https://api.twitter.com/1.1/`. The Java project reads them as `stockstats.stocklytics-base-url`, `stockstats.sentiment140-base-url` and `twitter.rest-base-url`; every fixture's `state.properties` points them at `${wiremock.baseUrl}/`, `${wiremock.baseUrl}/api/` and `${wiremock.baseUrl}/1.1/`, the only placeholder they use, so the recorded paths `/historicalPrices/1.0`, `/api/bulkClassifyJson` and `/1.1/search/tweets.json` are requested as recorded. The same `state.properties` give `stockstats.stocklyticsApiKey` and `stockstats.sentiment140ApiKey` the marker `capture-marker` that the original sent in its `api_key` and `appid` headers (Capture steps 2 and 8). The Twitter keys `twitter.accessKey`, `twitter.accessSecret`, `twitter.apiKey` and `twitter.apiSecret` get no marker, because no recorded exchange carries them (next item).
- Twitter: the original signs every Twitter call with OAuth 1.0a. Its `Authorization` header carries a per-request nonce, a timestamp and a signature computed over the request URL with the live secrets, a credential WireMock cannot replace on the proxied leg and that no replay reproduces. The four rows that reach Twitter, `get-root-twitter-search`, `post-root-twitter-search`, `get-stock-stats` and `get-stock-stats-more-tweets`, are therefore captured with the live Twitter credentials in place, omit `upstream` and keep their journal out of the evidence (Capture step 2), and report `UNVERIFIED-no-upstream` once captured. Pointing twitter4j at WireMock through its `twitter4j.restBaseURL` system property would not change this: the signature would cover WireMock's address instead of Twitter's. The Twitter API v1.1 search may no longer be available to the capture credentials; a row whose Twitter call fails at capture time does not select its branch, gets no fixture and reports `UNVERIFIED-uncaptured`.
- Stocklytics and Sentiment140: both services were offline when this file was written (`api.stocklytics.com` no longer resolves and `www.sentiment140.com` does not answer). `get-stock-stats`, `get-stock-stats-more-tweets`, `get-stock-stats-invalid-symbol` and `get-stock-stats-upstream-failure` select their branch only through a Stocklytics response, so they cannot be captured until the service answers again, and they report `UNVERIFIED-uncaptured` until then. A WireMock `Network failure trying to make a proxied request` reply is not a Stocklytics response: Capture step 7 rejects it, so it never counts as the non-200 of `get-stock-stats-upstream-failure`. Once recorded, the `api_key` and `appid` headers carry the marker in the fixture and the live value only on the proxied leg (Capture step 2).
- Credential-bearing headers: a recorded Stocklytics, Sentiment140 or Twitter request carries the `api_key` header, the `appid` header or an OAuth `Authorization` header. A fixture or journal that would commit a live value of any of them is not committed: under Capture step 8 such a scenario omits `upstream`, or gets no fixture and stays `UNVERIFIED-uncaptured`. A fixture is never edited to remove the value (Integrity rule), and no credential is written into this project (AAP 0.4.6).
- Paging: `TwitterRequestProcessor` sets the invocation properties `rpp` and `page` (`TwitterRequestProcessor.java:41-42`), but the `twitter:search` of `TwitterFlow` passes only `query`, `since` and `until` (`mule-component-bindings.xml:56-61`), so every page call of `StockStatsResourceImpl.search` repeats the same search. An empty page ends the loop after one call, as in `get-stock-stats`; a non-empty page repeats until more than 1000 tweets have loaded, as in `get-stock-stats-more-tweets`. The capture records every exchange in call order.
- Null statistics: the converted `StockStatsController` answers 204 when the stock service returns no statistics. The original `StocklyticsStockService` always returns a `StockStats`, so no request selects that defensive branch; it has no scenario and is covered only by `StockStatsServiceTest` and `StockStatsControllerTest`.
- Source cells cite the original files by their full path; a base name in these notes, such as `mule-component-bindings.xml` or `StocklyticsStockService.java`, names the same file as the table.
- Parity label: all fourteen scenarios are uncaptured, so this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7), the label of `java-conversions/mule-component-bindings-java/README.md` and of this project's row in the root `SUMMARY.md`. After capture, while Stocklytics and Sentiment140 stay offline, it is at most `PARITY: PARTIAL — Tier 2A fixtures: 8 verified, 6 unverified`, or `PARITY: FAIL — Tier 2A fixtures` when any captured row differs: the eight rows without an outbound call can become `VERIFIED`, the four rows that reach Twitter stay unverified, and so do `get-stock-stats-invalid-symbol` and `get-stock-stats-upstream-failure` until Stocklytics answers. `PARITY: VERIFIED — Tier 2A fixtures` is impossible while the Twitter rows omit `upstream`. The README lists as unverified every scenario that is not `VERIFIED`.

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
- Every row of this file is captured by sending its request to the unmodified original, deployed on the ports its flow fixes (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8081` or `8180` in this project, as the row's `Request` names it). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.

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
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For this project, `state` holds only `properties`, the same six entries in every fixture: `ports.stock-stats` at `${freePort:stock-stats}`, the three upstream base URLs at `${wiremock.baseUrl}` and the two credential markers (see the notes under Scenarios). The original reaches no store.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of the eight scenarios of this project that make no outbound call: `get-root-unknown-path`, `get-stock-stats-wrong-port`, `get-stock-stats-missing-stock`, `get-stock-stats-missing-date`, `get-stock-stats-bad-date-length`, `get-stock-stats-unparseable-date`, `post-stock-stats-method-not-allowed` and `get-api-unknown-resource`. The four scenarios that reach Twitter omit `upstream` (next item).
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and again 500 ms later.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a free port, the same one for every use of the same name.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`.
- `${mongo.uri}` and `${ldap.port}`.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

Skeleton of a fixture of this project, here for a validation row on port 8180; every `request` and `response` value comes from the capture, and `state` is written as Capture step 8 defines:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>",
                "port": "<captured>", "portProperty": "ports.stock-stats" },
  "state":    { "properties": { "ports.stock-stats": "${freePort:stock-stats}",
                                "twitter.rest-base-url": "${wiremock.baseUrl}/1.1/",
                                "stockstats.stocklytics-base-url": "${wiremock.baseUrl}/",
                                "stockstats.sentiment140-base-url": "${wiremock.baseUrl}/api/",
                                "stockstats.stocklyticsApiKey": "capture-marker",
                                "stockstats.sentiment140ApiKey": "capture-marker" } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by key names in order, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name, with digit runs in unmatched names replaced by `#`; logs compare logger and level in order and only whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists all fourteen scenarios as `UNVERIFIED-uncaptured`, with `verified=0`, `unverified=14` and `failed=0`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/mule-component-bindings-java/`).

1. Build the unmodified original `mule-component-bindings` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams. The original calls Stocklytics and Sentiment140 over plain `http` at hosts its classes hard-code, and Twitter over `https` through the Mule Twitter connector (see the notes under Scenarios). Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
   - For mule-component-bindings, two mappings proxy the recordable upstreams, each replacing its credential header on the proxied leg, with the live values in the environment variables `LIVE_STOCKLYTICS_API_KEY` and `LIVE_SENTIMENT140_APPID`:

     ```sh
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPathPattern": "/historicalPrices(/.*)?"}, "response": {"proxyBaseUrl": "http://api.stocklytics.com", "removeProxyRequestHeaders": ["api_key"], "additionalProxyRequestHeaders": {"api_key": "'"$LIVE_STOCKLYTICS_API_KEY"'"}}}'
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPathPattern": "/api(/.*)?"}, "response": {"proxyBaseUrl": "http://www.sentiment140.com", "removeProxyRequestHeaders": ["appid"], "additionalProxyRequestHeaders": {"appid": "'"$LIVE_SENTIMENT140_APPID"'"}}}'
     ```

     No placeholder redirects those two hosts, so the original's JVM is pointed at WireMock as its HTTP proxy, for example with the runtime properties `http.proxyHost=localhost` and `http.proxyPort=<W>` (step 3): the plain-`http` calls then reach WireMock with their paths unchanged, and the mappings above forward them. While both services are offline, a proxied call does not reach its upstream and step 7 rejects the recording. Twitter is not proxied: its calls go to Twitter directly with the live credentials, and the four rows that reach it omit `upstream`.
3. Deploy the archive to a licensed Mule 3.8 EE runtime. The flow fixes both listeners, `localhost:8081` and `localhost:8180` (`mule-component-bindings/src/main/app/mule-component-bindings.xml:31,40`), and `mule-component-bindings/src/main/app/mule-app.properties` defines no key, so the runtime properties are those of step 2 and the credentials, which the flow's `PropertyPlaceholderConfigurer` resolves from system properties (`mule-component-bindings.xml:13-15`): `-M-Dhttp.proxyHost=localhost -M-Dhttp.proxyPort=<W>`, the markers `-M-Dstockstats.stocklyticsApiKey=capture-marker -M-Dstockstats.sentiment140ApiKey=capture-marker`, and the live Twitter values `-M-Dtwitter.accessKey="$LIVE_TWITTER_ACCESS_KEY" -M-Dtwitter.accessSecret="$LIVE_TWITTER_ACCESS_SECRET" -M-Dtwitter.apiKey="$LIVE_TWITTER_API_KEY" -M-Dtwitter.apiSecret="$LIVE_TWITTER_API_SECRET"` from the environment, never from a file. Ports 8081 and 8180 must be free on the capture host.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   D=<the date chosen for get-stock-stats-more-tweets>
   capture get-root-twitter-search 'http://localhost:8081/'
   capture post-root-twitter-search -X POST 'http://localhost:8081/'
   capture get-root-unknown-path 'http://localhost:8081/favicon.ico'
   capture get-stock-stats-wrong-port 'http://localhost:8081/api/stockStats?stock=AAPL&date=2012-11-28'
   capture get-stock-stats 'http://localhost:8180/api/stockStats?stock=AAPL&date=2012-11-28'
   capture get-stock-stats-more-tweets "http://localhost:8180/api/stockStats?stock=AAPL&date=$D"
   capture get-stock-stats-missing-stock 'http://localhost:8180/api/stockStats?date=2012-11-28'
   capture get-stock-stats-missing-date 'http://localhost:8180/api/stockStats?stock=AAPL'
   capture get-stock-stats-bad-date-length 'http://localhost:8180/api/stockStats?stock=AAPL&date=2012-1-26'
   capture get-stock-stats-unparseable-date 'http://localhost:8180/api/stockStats?stock=AAPL&date=abcd-ef-gh'
   capture get-stock-stats-invalid-symbol 'http://localhost:8180/api/stockStats?stock=XYZ&date=2012-11-28'
   capture get-stock-stats-upstream-failure 'http://localhost:8180/api/stockStats?stock=AAPL&date=2012-11-28'
   capture post-stock-stats-method-not-allowed -X POST 'http://localhost:8180/api/stockStats?stock=AAPL&date=2012-11-28'
   capture get-api-unknown-resource 'http://localhost:8180/api/unknown'
   ```

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

   In this project the journal is saved for `get-stock-stats-invalid-symbol` and `get-stock-stats-upstream-failure`, whose only outbound call is the Stocklytics request; it is not kept for the four rows that reach Twitter (step 2).

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); `"port"`, the port of the URL the scenario's command targets; and, for `8180`, `"portProperty": "ports.stock-stats"`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-stock-stats-missing-stock/response.http GET <<'PY'
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

7. `upstream` is `[]` when the original made no outbound call, as in the eight scenarios listed under `upstream` in the fixture contract; the four rows that reach Twitter omit it (step 2). For an original whose outbound calls were recorded, here `get-stock-stats-invalid-symbol` and `get-stock-stats-upstream-failure`, this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (the six properties of the notes under Scenarios, the same in every fixture of this project), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `get-root-unknown-path`, `get-api-unknown-resource` and `post-stock-stats-method-not-allowed`, that means SC-1 `GlobalExceptionHandler`; for `get-stock-stats-wrong-port`, SC-10 `PortRoutingFilter`; for the other `8180` rows, `StockStatsService` and `StockStatsController`; and for the Twitter rows of `8081`, `TwitterSearchService` and `TwitterResponseMapper`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
