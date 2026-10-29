# Component Bindings (Spring Boot conversion of `mule-component-bindings`)

`mule-component-bindings-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE example [`mule-component-bindings`](../../mule-component-bindings/) (category General; flow schema `EE-3.6.0`, built against Mule 3.8.0 by the repository parent). The original folder `mule-component-bindings/` stays unchanged beside this project and is the Tier 2A capture source. The root reactor comments that module out (`pom.xml:30`).

- Package `com.mulesoft.examples.mule_component_bindings`; main class `MuleComponentBindingsApplication` (`@SpringBootApplication`).
- Two HTTP listeners in one Undertow server, as the original's two `http:listener-config` elements: `localhost:8081` and `localhost:8180`.

## Original example

The example combines two external services with a third one bound to a local Java interface. A REST resource returns the statistics of a stock on a given day: Stocklytics supplies the day's prices, a Twitter search finds the tweets about the stock, and Sentiment140 classifies them as positive, negative or neutral. The Twitter search is not called directly. The resource calls the local interface `stockstats.impl.TwitterService`, and a Mule component `binding` routes that call through the request-response VM endpoint `twitterFlowRequest` to the flow `TwitterFlow`, which runs the Mule Twitter connector (original `README.md:11-13`, `:32-34`).

The single flow file `mule-component-bindings/src/main/app/mule-component-bindings.xml` (the only entry of `config.resources`) holds three flows:

| Flow | Trigger | Steps | Source |
|---|---|---|---|
| `mule-component-bindingsFlow1` | `http:listener` path `/`, listener config `localhost:8081` | `twitter:search` with the fixed `query="AAPL"`, `since="2014-10-26"`, `until="2014-11-04"`, then the custom processor `TwitterResponseProcessor`, which maps the result to a `List<Tweet>` | `mule-component-bindings.xml:31-36` |
| `StockServiceFlow` | `http:listener` path `/api/*`, listener config `localhost:8180` | `jersey:resources` serving the Spring bean `stackStatsResource` (`stockstats.impl.StockStatsResourceImpl`), whose `twitterService` is bound to `TwitterService.search` over VM `twitterFlowRequest` | `mule-component-bindings.xml:40-52` |
| `TwitterFlow` | `vm:inbound-endpoint` `twitterFlowRequest`, request-response | `TwitterRequestProcessor` (query `$<stock>`, `since` the date, `until` the date + 10 days), `twitter:search`, `TwitterResponseProcessor` | `mule-component-bindings.xml:56-61` |

The bean `stackStatsResource` is wired with `StocklyticsStockService(${stockstats.stocklyticsApiKey})` and `Sentiment140SentimentService(${stockstats.sentiment140ApiKey})`; the Twitter connector config takes `${twitter.accessKey}`, `${twitter.accessSecret}`, `${twitter.apiKey}` and `${twitter.apiSecret}` (`mule-component-bindings.xml:10-29`). A `PropertyPlaceholderConfigurer` resolves them from system properties (`:13-15`). No flow declares an exception strategy or a logger.

## Endpoints and triggers

| Port | Host | Path | Methods | Response | Source flow |
|---|---|---|---|---|---|
| `ports.twitter-search`, default `8081` | `localhost` | `/` | any: the listener declares no `allowedMethods`, so `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` and `TRACE` all run the flow | Twitter search `AAPL` from `2014-10-26` until `2014-11-04`, mapped to a `List<Tweet>`. `200 OK`, body = Java serialization of the `java.util.ArrayList` (for no tweets, the 58-byte serialized empty list), `Content-Length`, no `Content-Type`; a `HEAD` reply carries the headers without the body. A non-empty result cannot be serialized (`Tweet` is not `Serializable`, as in the original): `500`, `application/json`, `{ "message": "<SerializationException message>" }`. A failed Twitter call: `500`, `application/json`, `{ "message": "<Twitter message>" }`. A Twitter credential without text (unset, empty or whitespace only) fails before any request is sent, naming the first such key in the order `twitter.apiKey`, `twitter.apiSecret`, `twitter.accessKey`, `twitter.accessSecret`: `500`, `application/json`, for example `{ "message": "Twitter API key (twitter.apiKey) is not configured" }` (Credentials). The `stockstats.*` keys are not needed here | `mule-component-bindingsFlow1` |
| `ports.twitter-search` | `localhost` | any other path (`/favicon.ico` and `/api/stockStats` included) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-10 `PortRoutingFilter`, AAP 0.4.7) | listener platform default |
| `ports.stock-stats`, default `8180` | `localhost` | `/api/stockStats?stock=<symbol>&date=<yyyy-MM-dd>` | `GET` and `HEAD` (Spring derives `HEAD` from the `GET` mapping, as JAX-RS did) | `200 OK`, `application/json`, the `StockStats` JSON; `204 No Content` without a body when the stock service returns no statistics; `400 Bad Request`, `application/json`, raw text body (not a JSON string): `stock is required`, `date is required`, `date format must be YYYY-MM-DD` (wrong length or unparseable), `Invalid stock symbol <stock>`; `500 Internal Server Error`, `application/json`, the failure's message as raw text, empty when it has none. Requests the JAX-RS method selection rejects (below): `400`, `415` or `406` with no `Content-Type` and an empty body | `StockServiceFlow`, resource `StockStatsResource.search` |
| `ports.stock-stats` | `localhost` | `/api/stockStats` | `OPTIONS` | `200 OK`, `Allow: GET,HEAD,OPTIONS`, empty body (Spring MVC's answer) | `StockServiceFlow` |
| `ports.stock-stats` | `localhost` | `/api/stockStats` | every other method (`POST`, `PUT`, `PATCH`, `DELETE`, `TRACE`) | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /api/stockStats`, no `Allow` header (SC-1 `GlobalExceptionHandler`, AAP 0.4.7) | resource platform default |
| `ports.stock-stats` | `localhost` | any other path under `/api` (`/api/unknown`) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1 `GlobalExceptionHandler`) | resource platform default |
| `ports.stock-stats` | `localhost` | any path outside `/api` (`/` included) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-10 `PortRoutingFilter`) | listener platform default |

Example `200` body, as `StockStatsControllerTest` asserts it for the original IT's stub (close 1, low 2, one unclassified tweet):

```json
{"closingDate":"2012-10-26","financialStats":{"open":0.0,"close":1.0,"high":0.0,"low":2.0,"volume":0},"socialStats":{"tweetsLoaded":1,"moreTweets":false,"negativeTweets":0,"neutralTweets":0,"positiveTweets":1}}
```

### Stock statistics request, step by step

`controller/StockStatsController` admits and binds the request; `service/StockStatsService` (the ported `StockStatsResourceImpl`) does the rest, in the original order:

1. Media checks of `controller/JaxRsMediaSelection`, the selection Jersey 2.11 applied for `@Consumes("application/json")` and `@Produces("application/json")`: no `Content-Type` passes (the plain `GET`); a repeated or unparseable `Content-Type` is `400`; one not compatible with `application/json` is `415`; an unparseable `Accept` is `400`; an `Accept` admitting no JSON (for example `application/xml`) is `406`. A rejected request calls no upstream.
2. `stock` and `date` are read from the raw query string as `@QueryParam` read them: split at `&` only, decoded as `application/x-www-form-urlencoded` UTF-8, names case-sensitive, the first value of a repeated parameter wins, a bare name is the empty value; form fields of a body never count.
3. Validation: a blank `stock` → `400 stock is required`; a blank `date` → `400 date is required`; a `date` not 10 characters long, or not parseable as `yyyy-MM-dd` → `400 date format must be YYYY-MM-DD`. The parse is lenient, as in the original: `2012-13-45` rolls over to `2013-02-14`.
4. Stocklytics (`client/StocklyticsStockService`): a `stockstats.stocklyticsApiKey` without text (unset, empty or whitespace only) fails first, before any upstream call: `500` with the body `Stocklytics API key (stockstats.stocklyticsApiKey) is not configured`, and neither Twitter nor Sentiment140 is called. With the key set: `GET <stockstats.stocklytics-base-url>historicalPrices/1.0` with `Accept: application/json` and the headers `api_key` (`stockstats.stocklyticsApiKey`), `format: JSON`, `stock`, `start` and `end` (both the date). A status other than 200 fails as `Stockylitics returned status <n>` (the original spelling) → `500`. A body containing `Error: Stocklytics doesn't have data for that stock.` → `400 Invalid stock symbol <stock>`, and neither Twitter nor Sentiment140 is called. Otherwise `open`, `close`, `high`, `low` and `volume` are read from the member named after the date; a missing field is 0.
5. Twitter (`service/TwitterSearchService` through `client/TwitterSearchClient`): pages of 100 are requested from page 1 until a page comes back empty or more than 1000 tweets have loaded; past 1000, `socialStats.moreTweets` becomes `true` and the list is trimmed to 1000. Each call searches `$<stock>` from the date until the date + 10 days. As in the original flow, the search does not receive the page number or size (`TwitterRequestProcessor` set them as invocation properties `rpp` and `page`, but `twitter:search` read only `query`, `since` and `until`), so a non-empty search repeats the same tweets until the 1000 cap. When the stock service returned no statistics, passing 1000 tweets fails the request with `500` and an empty body, as dereferencing the null statistics did in the original. A Twitter credential without text fails the first search before it is sent, and Sentiment140 is not called: `500` with the body naming the first such key in the order `twitter.apiKey`, `twitter.apiSecret`, `twitter.accessKey`, `twitter.accessSecret`, as `Twitter API key (twitter.apiKey) is not configured`, `Twitter API secret (twitter.apiSecret) is not configured`, `Twitter access token (twitter.accessKey) is not configured` or `Twitter access token secret (twitter.accessSecret) is not configured`.
6. Sentiment140 (`client/Sentiment140SentimentService`): one `POST <stockstats.sentiment140-base-url>bulkClassifyJson` with the header `appid` (`stockstats.sentiment140ApiKey`), `Content-Type: text/plain` and the JSON of `BulkClassifyRequest` (`data`: each tweet's `id` and `text`) as UTF-8, sent for an empty list too. Polarity `0` is negative, `2` neutral, any other value positive. A status other than 200 fails as `Sentiment140 returned status <n>`; a result id no tweet was sent with fails as `java.lang.NullPointerException` → `500`. A `stockstats.sentiment140ApiKey` without text fails before the post, for an empty list too: `500` with the body `Sentiment140 application ID (stockstats.sentiment140ApiKey) is not configured`.
7. Each tweet is counted into `socialStats` (an unclassified tweet counts as positive) and the statistics are returned: `200`, or `204` when the stock service returned none.
8. Any other failure becomes `500` with the failure's own message as the body. It is logged at ERROR as one line naming the exception types only, for example `Error searching stock stats: org.springframework.web.client.ResourceAccessException <- java.net.UnknownHostException`.

Every upstream call has a 10 s connect timeout and a 10 s read timeout, and no call is retried after a timeout or an error status. The Stocklytics GET and the Sentiment140 POST are each sent once whatever the failure. The Twitter search is sent once too, except that the JDK HTTP transport under twitter4j re-sends it once, unchanged, when the connection is reset or closes before any response, as the original connector's twitter4j did ("Timeouts and retries" under Behaviours stubbed, externalized or changed). The `Twitter` instance is built on the first search, so the application starts without credentials.

### Notes

- The `/` response body is the plan's reading of the Mule 3.8 platform default: the HTTP listener wrote any `Serializable` payload other than a stream, a byte array or a string by Java serialization (`SerializableToByteArray`) and sent no `Content-Type`. It is a Tier 2A verification point (`mapper/TwitterResponseMapper.toListenerPayload`).
- Tier 2A verification points, where a captured fixture of the original governs (AAP 0.4.7): the bytes of every `404` and `405` default (Jersey's own 404 and 405 bytes inside `/api/*` are not known from the repository); the status lines, headers and empty bodies of the `400`, `406` and `415` media rejections; `OPTIONS /api/stockStats`, for which the runtime generated a WADL description; the `Content-Type` of the `200`, always `application/json` here, where the runtime echoed the matching `Accept` element; and the `500` for a `Content-Type` whose `q` parameter is not a quality value, answered here with SC-1's `{ "message": ... }` body.
- A query with a malformed percent-escape (`stock=%ZZ`) is refused by Undertow before the application sees it: `400 Bad Request`, empty body, `Connection: close`. This too is a Tier 2A verification point.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request to the same path, with no `Access-Control-*` or `Vary` header: the original listeners applied no CORS policy (`config/PreflightDispatchConfig`).
- The VM endpoint `twitterFlowRequest` was internal to the application and is now a direct method call. The project has no other trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. The original's `mule-app.properties` holds no keys: its flow reads the six credential placeholders below, and fixes the ports, hosts and upstream URLs as literals.

Status values:

- `default`: a non-secret value the original fixed, overridable.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). A secret is marked `(secret)`.
- `fixed`: a framework setting that reproduces the original listeners or keeps the logs free of credentials, not a key of the original; changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `twitter.accessKey` | `String` | none (`${TWITTER_ACCESSKEY:}`) | `twitter.accessKey` (unchanged) | TODO (secret) | Twitter OAuth access token (`twitter:config accessKey`, `mule-component-bindings.xml:10`; "Access token" in original README step 4) |
| `twitter.accessSecret` | `String` | none (`${TWITTER_ACCESSSECRET:}`) | `twitter.accessSecret` (unchanged) | TODO (secret) | Twitter OAuth access token secret (`accessSecret`; "Access token secret") |
| `twitter.apiKey` | `String` | none (`${TWITTER_APIKEY:}`) | `twitter.apiKey` (unchanged) | TODO (secret) | Twitter OAuth consumer key (`consumerKey`; "API key") |
| `twitter.apiSecret` | `String` | none (`${TWITTER_APISECRET:}`) | `twitter.apiSecret` (unchanged) | TODO (secret) | Twitter OAuth consumer secret (`consumerSecret`; "API secret") |
| `stockstats.stocklyticsApiKey` | `String` | none (`${STOCKSTATS_STOCKLYTICSAPIKEY:}`) | `stockstats.stocklyticsApiKey` (unchanged) | TODO (secret) | Stocklytics API key (original README step 2), sent as header `api_key` (`mule-component-bindings.xml:20`) |
| `stockstats.sentiment140ApiKey` | `String` | none (`${STOCKSTATS_SENTIMENT140APIKEY:}`) | `stockstats.sentiment140ApiKey` (unchanged) | TODO (secret) | Sentiment140 application id, the email used for the registration (original README step 3), sent as header `appid` (`:25`) |
| `twitter.rest-base-url` | `String` | `https://api.twitter.com/1.1/` | none: twitter4j's default REST base | default | handed to twitter4j's `restBaseURL`; tests point it at WireMock |
| `stockstats.stocklytics-base-url` | `String` | `http://api.stocklytics.com/` | none: `BASE_URL` of `StocklyticsStockService.java:36` | default | the client appends `historicalPrices/1.0`; must end with `/` |
| `stockstats.sentiment140-base-url` | `String` | `http://www.sentiment140.com/api/` | none: `BASE_URL` of `Sentiment140SentimentService.java:38` | default | the client appends `bulkClassifyJson`; must end with `/` |
| `ports.twitter-search` | `int` | `8081` | none: `HTTP_Listener_Configuration port="8081"` (`:31`) | default | port of the main listener (`/`) |
| `ports.stock-stats` | `int` | `8180` | none: `HTTP_Listener_Configuration2 port="8180"` (`:40`) | default | port of the stock-statistics listener (`/api/**`) |
| `server.port` | `Integer` | `${ports.twitter-search}` | none | fixed | the main Undertow listener; set `ports.twitter-search` rather than this key |
| `server.address` | `InetAddress` | `localhost` | none: `host="localhost"` (`:31`) | fixed | bind address of the main listener |
| `listeners.host` | `String` | `localhost` | none: `host="localhost"` (`:40`) | fixed | default bind address of the additional listener (SC-10 `config/ListenerPorts`) |
| `listeners.disallowed-methods` | `List<String>` | `[]` | none | fixed | no method is refused at the server level: both original listeners accept every method, `TRACE` included |
| `listeners.main.routes` | `List<Route>` | one route, patterns `["/"]` | none: `path="/"` (`:33`) | fixed | the main listener serves `/` exactly; `PortRoutingFilter` answers any other path with the no-listener 404 |
| `listeners.additional[0]` | `Additional` | name `stock-stats`, port `${ports.stock-stats}`, host `localhost`, one route, patterns `["/api", "/api/**"]` | none: `path="/api/*"` (`:42`) | fixed | the second Undertow listener of SC-10 and its routes; `StockStatsController` maps `/api/stockStats` |
| `spring.application.name` | `String` | `mule-component-bindings-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE /` reaches `TwitterSearchController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; SC-1 answers every platform-default error |
| `logging.level.twitter4j` | `LogLevel` | `INFO` | none | fixed | below `INFO` twitter4j logs the consumer key and access token (OAuth base string, configuration dump); a logger's own level outranks `logging.level.root`, `--debug` and `--trace` |
| `logging.level.sun.net.www.protocol.http.HttpURLConnection` | `LogLevel` | `INFO` | none | fixed | below `INFO` the JDK HTTP client of Twitter and Sentiment140 dumps request headers, with `Authorization` and `appid` |
| `logging.level.org.apache.hc.client5.http.headers` | `LogLevel` | `INFO` | none | fixed | below `INFO` the httpclient5 transport of Stocklytics dumps request headers, with `api_key` |
| `logging.level.org.apache.hc.client5.http.wire` | `LogLevel` | `INFO` | none | fixed | below `INFO` the httpclient5 transport of Stocklytics dumps every byte sent and received, `api_key` among them |

Original-to-new key map:

- Unchanged: the six credential keys keep the names of the original placeholders (AAP 0.4.6, 0.6.6). The original IT read them from the uncommitted `src/test/resources/mule.test.properties` and set them as system properties.
- Added: `ports.twitter-search`, `ports.stock-stats` and the three base URLs carry values the original hard-coded, so the listeners can move and tests can point every client at a stub server.

Credentials:

- The application starts with every secret blank, and `config/StockStatsProperties` binds them as empty strings; only the calls that need them fail. Each client checks only the keys it sends, before it sends anything: a key without text (unset, empty or whitespace only) fails the call with an `IllegalStateException` whose message names the key and never a value. `client/TwitterSearchClient` checks `twitter.apiKey`, `twitter.apiSecret`, `twitter.accessKey` and `twitter.accessSecret` in that order and names the first missing one (`Twitter API key (twitter.apiKey) is not configured`, `Twitter API secret (twitter.apiSecret) …`, `Twitter access token (twitter.accessKey) …`, `Twitter access token secret (twitter.accessSecret) …`); `client/StocklyticsStockService` checks `stockstats.stocklyticsApiKey` (`Stocklytics API key (stockstats.stocklyticsApiKey) is not configured`); `client/Sentiment140SentimentService` checks `stockstats.sentiment140ApiKey` (`Sentiment140 application ID (stockstats.sentiment140ApiKey) is not configured`). So `/` needs only the four `twitter.*` keys and without one answers `500` with the message in `{ "message": ... }`; `/api/stockStats` needs all six and answers `500` with the message as its body for the first missing key in its call order Stocklytics, Twitter, Sentiment140. The original needed every placeholder resolved before it deployed, because its `PropertyPlaceholderConfigurer` rejects an unresolved `${…}`.
- No credential is hard-coded anywhere in the project. Supply them through the environment variables above, `--key=value` arguments or a local, uncommitted override file. `config/StockStatsProperties` declares no `toString()`, so no secret is rendered, and `SecretLoggingTest` asserts that none of the six reaches the log even at `TRACE`.
- `src/test/resources/application-test.yml` (Spring profile `test`, which the tests activate) supplies fake values such as `test-access-key` for the six keys, in place of the original's `mule.test.properties`. None is a real credential; every upstream call in the tests goes to WireMock or a Mockito mock.
- The project is not a connector project, so it has no `application-it.yml`, no `application-it.yml.example` and no live tier. `.gitignore` ignores `target/`.

## Mule-to-Java mapping

| Mule construct (`mule-component-bindings`) | Java construct (`com.mulesoft.examples.mule_component_bindings`) |
|---|---|
| `http:listener-config` `localhost:8081` and `http:listener path="/"` (`mule-component-bindings.xml:31-33`) | the main Undertow listener (`spring-boot-starter-undertow`, Tomcat excluded) from `server.address` and `server.port`; `controller/TwitterSearchController`: `@RequestMapping("/")` for every method and an explicit `OPTIONS` mapping, writing the payload bytes straight to the servlet response |
| `twitter:search query="AAPL" since="2014-10-26" until="2014-11-04"` (`:34`) | `service/TwitterSearchService.searchListenerFlow()` → `client/TwitterSearchClient.search` |
| `custom-processor TwitterResponseProcessor` (`:35`, `:60`) | `mapper/TwitterResponseMapper.toTweets`; the listener's rendering of that payload is `toListenerPayload` |
| `http:listener-config` `localhost:8180` and `http:listener path="/api/*"` (`:40-42`) | SC-10 `config/ListenerPorts` (additional Undertow listener `stock-stats`) and `config/PortRoutingFilter` (per-port routes, no-listener 404) |
| `jersey:resources` with the JAX-RS resource `StockStatsResource` (`@Path("/stockStats")`, `@GET`, `@QueryParam`, `@Consumes`/`@Produces("application/json")`) (`:43-51`) | Spring MVC `@GetMapping("/api/stockStats")` in `controller/StockStatsController`, with `controller/JaxRsMediaSelection` for the JAX-RS method selection |
| Spring bean `stackStatsResource` (`stockstats.impl.StockStatsResourceImpl`) (`:17-29`) | `@Service` `service/StockStatsService`, the ported implementation, with constructor injection; the getters and setters of its three collaborators are kept |
| `stockstats.impl.stocklytics.StocklyticsStockService` with its `${stockstats.stocklyticsApiKey}` constructor argument (`:19-21`) | `@Component` `client/StocklyticsStockService`, JAX-RS client replaced by Spring `RestClient` over Apache httpclient5 |
| `stockstats.impl.sentiment140.Sentiment140SentimentService` with `${stockstats.sentiment140ApiKey}` (`:24-26`) | `@Component` `client/Sentiment140SentimentService`, `RestClient` |
| component `binding interface="stockstats.impl.TwitterService" method="search"` + `vm:outbound-endpoint` `twitterFlowRequest` (`:47-49`), `TwitterFlow` with its `vm:inbound-endpoint` (`:56-57`) and `TwitterRequestProcessor` (`:58`) | direct injection of `service/TwitterSearchService`, which implements `service/TwitterService`, into `StockStatsService`; `search` computes the query `$<stock>` and the dates as `TwitterRequestProcessor` did |
| `twitter:search query since until` (`:59`) and `twitter:config` (`:10`) | `client/TwitterSearchClient` (twitter4j 4.0.7, in place of `mule-module-twitter` 3.2.0 and twitter4j 3.0.3), behind the port `TwitterSearchService.RawSearch`; credentials from `config/StockStatsProperties` |
| `PropertyPlaceholderConfigurer` (`:13-15`) and the `${…}` placeholders | `src/main/resources/application.yml`, bound by `config/StockStatsProperties` (`@Value`, original names) |
| `stockstats.StockStats`, `FinancialStats`, `SocialStats`, `Sentiment`, `stockstats.impl.Tweet`, `BulkClassifyRequest`, `ClassifyRequest` | `model/*` (ported; `Tweet` stays not `Serializable`) |
| `stockstats.impl.StockService`, `SentimentService`, `TwitterService`, `InvalidStockSymbol` | `service/StockService`, `service/SentimentService`, `service/TwitterService`, `exception/InvalidStockSymbol` |
| `WebApplicationException(Response.status(s).entity(message))` | `ResponseStatusException(s, message)`, answered by the controller-local `@ExceptionHandler` with the raw message as an `application/json` body |
| codehaus Jackson, commons-lang `StringUtils`, `e.printStackTrace()` | fasterxml Jackson, commons-lang3 `StringUtils`, one SLF4J ERROR line naming the exception types |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (`config.resources=mule-component-bindings.xml`) | `MuleComponentBindingsApplication` (`@SpringBootApplication`) |
| `MuleComponentBindingsIT` (`FunctionalTestCase`, `MuleClient`, Mockito mocks set on the bean) | `MuleComponentBindingsTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@MockBean`, ST-1 `support/HttpTestClient`) |

## Behaviours stubbed, externalized or changed

Known differences from the original:

- Upstream response and request bodies. The original JAX-RS clients read the body with `response.getEntity().toString()` and posted the bulk request with `Entity.text(bulkRequest)`. Under Jersey 2 the former yields the identity string of the entity stream, not the body, and the latter finds no text writer for the bean, so the original's `200` path could not parse an upstream answer. The conversion reads each body as a `String` (charset from the response `Content-Type`, UTF-8 by default) and posts the bulk request as its JSON text, as UTF-8 bytes with `Content-Type: text/plain`, the media type `Entity.text` declares. That is what the parsing code that follows expects, and what AAP 0.7.5 describes ("→ `StockStats` JSON"). It is an intentional difference of the kind the root `DECISIONS.md` records, and belongs in its list of conflict resolutions.
- Timeouts and retries. Every Stocklytics, Sentiment140 and Twitter call has a 10 s connect and a 10 s read timeout, the Mule default response timeout. The original Jersey clients had no timeout, and twitter4j's own defaults are 20 s and 120 s. A call that times out fails the request with `500` and the client's message, and no call is retried after a timeout or an error status. Per adapter:
  - Stocklytics (`client/StocklyticsStockService`) sends through Apache httpclient5 (`org.apache.httpcomponents.client5:httpclient5`, version from the Spring Boot BOM) with its automatic retries switched off. The JDK `HttpURLConnection` under Spring's default request factory re-sends a bodiless GET once when its connection is reset or closes before any response, and has no setting to stop it. The GET is therefore sent once, whatever the failure. Otherwise the transport behaves as `HttpURLConnection` did: a redirect with status 300, 301, 302, 303 or 307 that keeps the scheme is followed, at most 20 requests in all, and the JVM's default proxy selector and JSSE TLS defaults apply. One difference: a redirect is followed only on the origin of `stockstats.stocklytics-base-url` (the same scheme and host, compared case-insensitively, and the same port, 80 or 443 when none is written), and a relative `Location` is resolved before that check. A redirect to another host, port or scheme, at once or after a followed one, is returned as received, so the request answers `500` with `Stockylitics returned status <n>`, and nothing, neither the `api_key` header nor the request data, is sent to that location. `HttpURLConnection` followed a same-scheme redirect to any host with every header. To use Stocklytics on another host, point `stockstats.stocklytics-base-url` at it.
  - Sentiment140 (`client/Sentiment140SentimentService`) stays on `HttpURLConnection`, which never re-sends a request whose body it streams, as it streams this POST. The bulk classification is posted once, whatever the failure.
  - Twitter (`client/TwitterSearchClient`) runs twitter4j with its own retries off, so a search that times out or that Twitter rejects is sent once. twitter4j sends through the JDK `HttpURLConnection`, which re-sends the bodiless search GET once, unchanged, when the connection is reset or closes before any response. The original connector's twitter4j did the same over the same transport. twitter4j 4.0.7 chooses its HTTP implementation once per JVM, from the system property `twitter4j.http.httpClient`, so no transport without that re-send can be given to this client alone. A search is idempotent; when the second attempt fails too, the request fails with `500`.
- Error logging. The original's `e.printStackTrace()` becomes one SLF4J ERROR line, `Error searching stock stats: <exception class> <- <cause class> …`, naming exception types only, with no message and no stack trace, because those texts can quote upstream content. The `500` body still carries the message. Below `INFO`, twitter4j, the JDK HTTP client and httpclient5's header and wire loggers would log the credentials, so `application.yml` pins all four loggers at `INFO`.
- Null dereferences. Where the original failed by dereferencing a null (no statistics with more than 1000 tweets, or a Sentiment140 result id no tweet was sent with), the conversion throws a `NullPointerException` without a message, so the `500` body is the original's (empty, and `java.lang.NullPointerException` respectively) rather than the JVM's helpful message naming the Java code.
- Bean scope. The flow declares `stackStatsResource` with `scope="singleton"` (the build steps of the original README say `prototype`); `StockStatsService` is a singleton `@Service`, its collaborators replaceable through the kept setters.
- Start-up. The application starts with blank credentials and builds the `Twitter` instance on the first search (Configuration keys, Credentials). A credential without text fails only the calls that need it, locally and before any request, with a message naming the key (Credentials). The original did not deploy with an unresolved placeholder, but handed a placeholder resolved to an empty value to its vendor call unchanged.

External-service risks (AAP 0.8.9):

- Stocklytics and Sentiment140 are no longer operated: `api.stocklytics.com` does not resolve, so with `stockstats.stocklyticsApiKey` set every valid `/api/stockStats` request answers `500` with `I/O error on GET request for "http://api.stocklytics.com/historicalPrices/1.0": api.stocklytics.com`, and `www.sentiment140.com` does not answer.
- Twitter API v1.1 standard search may be unavailable to today's credentials, and it covers recent days only, so the fixed 2014 window of `/` returns no tweets even when the search is accepted.
- The unit tier therefore uses WireMock for Stocklytics, Sentiment140 and Twitter, and Mockito mocks for the Twitter client and the service ports. Live behaviour can be verified only through Tier 2A capture of the original, which these services block as well (Fallback evidence).

Not carried (AAP 0.2.2): `mule-project.xml`, `src/main/app/mule-deploy.properties`, the key-less `src/main/app/mule-app.properties` and `src/test/resources/log4j2-test.xml`. The original has no `catalog/` folder. `src/test/resources/reply.json` is copied unchanged.

## Build, run and test

Run from `java-conversions/mule-component-bindings-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listeners on localhost:8081 (/) and localhost:8180 (/api/stockStats)
java -jar target/mule-component-bindings-java-1.0.0-SNAPSHOT.jar
```

Set the credentials first, through their environment variables:

```sh
export TWITTER_ACCESSKEY='<access token>' TWITTER_ACCESSSECRET='<access token secret>'
export TWITTER_APIKEY='<API key>' TWITTER_APISECRET='<API secret>'
export STOCKSTATS_STOCKLYTICSAPIKEY='<Stocklytics API key>' STOCKSTATS_SENTIMENT140APIKEY='<registration email>'
mvn spring-boot:run
```

Move the listeners with `--ports.twitter-search=<port>` and `--ports.stock-stats=<port>` (with `mvn spring-boot:run`, through `-Dspring-boot.run.arguments=...`).

Usage, following the original README steps 6 and 7:

```sh
curl -i "http://localhost:8180/api/stockStats?stock=AAPL&date=2012-11-28"   # the stock statistics as JSON
curl -i http://localhost:8081/                                             # the fixed AAPL Twitter search
```

A validation answer needs no upstream and no credential (the `Date` value varies):

```console
$ curl -i "http://localhost:8180/api/stockStats?stock=AAPL"
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: application/json
Content-Length: 16
Date: <current date>

date is required
```

- There is no `mvn verify` live tier: the project is not a connector project, so it has no failsafe plugin, no `*IT` class and no `application-it.yml` (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `MuleComponentBindingsTest` | The ported `MuleComponentBindingsIT` (`mule-component-bindings/src/test/java/org/mule/examples/MuleComponentBindingsIT.java:96-115`): `GET /api/stockStats?stock=AAPL&date=2012-10-26` on the stock-statistics listener, with the three collaborators of the resource mocked as the original set them (`PortedBinding`), answers a non-empty payload that contains `reply.json`, the original's exact 131-byte prefix ending at `"tweetsLoaded"`. An added second case (`BindingThroughClients`) runs the real Stocklytics and Sentiment140 clients against WireMock, with the Twitter client mocked, and asserts the same prefix and the search `$AAPL` from `2012-10-26` until `2012-11-05`. Two more added cases run without mocks: with both `stockstats.*` keys blank (`WithoutStockStatsKeys`), `/` still answers `200` through the real Twitter client against WireMock, while `/api/stockStats` answers `500` `Stocklytics API key (stockstats.stocklyticsApiKey) is not configured` with no upstream request; with `twitter.apiKey` blank (`WithoutTwitterApiKey`), `/` answers `500` `{ "message": "Twitter API key (twitter.apiKey) is not configured" }` with no request, and `/api/stockStats` calls Stocklytics only and answers `500` with the same message |
| `controller/StockStatsControllerTest` | On the wire through ST-1: the `200` JSON bytes; `204` without a body; every `400` text; `Invalid stock symbol XYZ`; the `500` raw message, empty for a message-less failure; query binding (first value, `&` only, `+` and `%20`, case-sensitive names, bare names); the `400`, `406` and `415` media rejections without a body, `Content-Type` before `Accept`; `HEAD`; `POST` → `405`; `OPTIONS` → `Allow: GET,HEAD,OPTIONS`; `/api/unknown` and `/` → no-listener `404`; no upstream call on any rejection |
| `controller/TwitterSearchControllerTest` | On the wire: every method on `/` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `TRACE`) runs the fixed search once and returns `200` with the serialized empty list, its `Content-Length` and no `Content-Type`; `HEAD` sends the headers only; a search with tweets → `500` with the `SerializationException` message; a Twitter failure → `500 { "message": "boom" }`; `/favicon.ico` and `/api/stockStats` → no-listener `404` without a search |
| `service/StockStatsServiceTest` | With Mockito collaborators: each validation `400` before any call; lenient date rollover; `Invalid stock symbol`; the paging rule (empty page, `100 + 50`, one page of 1001, exactly 1000, 1001 over eleven pages) with `moreTweets` and the 1000 trim; sentiment counting; no statistics → `null` after classifying; no statistics with more than 1000 tweets → `500` without a message; any other collaborator failure → `500` with its message; the ERROR line names types only; getters and setters |
| `service/TwitterSearchServiceTest` | the query `$<stock>`, `since` the date and `until` 10 days later; page size and page not forwarded; the fixed `AAPL` listener search and its rendered payload; a `TwitterException` rethrown as `IllegalStateException` with its message and cause |
| `client/StocklyticsStockServiceTest` | Against WireMock: the request headers and the price parsing; missing fields as 0; numeric text coerced; a non-200 status → `Stockylitics returned status <n>`; the error body → `InvalidStockSymbol`; malformed JSON; a stalled upstream fails once with a `SocketTimeoutException` within the timeout; every WireMock connection fault fails with `ResourceAccessException` after exactly one GET; a same-scheme redirect with status 300, 301, 302, 303 or 307 followed with the original headers, an `http`→`https` redirect and a 308 not followed; against a second WireMock server as another origin, with a marker API key: each of those statuses to it, a network-path `Location`, another host on the configured port and the configured host without its port → `Stockylitics returned status <n>` after one request, with no request reaching the other origin; a followed same-origin redirect whose target redirects to the other origin stops there after two requests; an absolute same-origin `Location`, its scheme and host in any letter case, followed with `api_key` and every request-data header; timeout bounds; an API key that is null, empty or whitespace only → `Stocklytics API key (stockstats.stocklyticsApiKey) is not configured` with no request |
| `client/Sentiment140SentimentServiceTest` | Against WireMock: the request (`appid`, `text/plain`, the JSON body, non-ASCII text as UTF-8 bytes) and the polarity mapping; a tweet missing from the response keeps no sentiment; an empty list still posted; a non-200 status → `Sentiment140 returned status <n>` after one call; malformed bodies and unknown ids → `RuntimeException`; a stalled upstream times out once; a connection reset or closed before any response fails after exactly one POST; timeout bounds; an application id that is null, empty or whitespace only → `Sentiment140 application ID (stockstats.sentiment140ApiKey) is not configured` with no request, for no tweets too |
| `client/TwitterSearchClientTest` | Against WireMock as the Twitter REST base: the query and dates, OAuth-signed with the configured credentials; no `since` or `until` when null; the lazy `Twitter` instance; `401` and a missing `search_metadata` → `TwitterException`, never sent twice; Spring binds the `RawSearch` port to this client; the 10 s timeouts and no twitter4j retry hold over any external twitter4j configuration; a connection reset or closed before any response → the JDK transport's one re-send of the identical search (exactly two GETs, same OAuth signature), then `TwitterException`; no answer fails once after the 10 s read timeout; each credential null, empty or whitespace only, and several at once → `IllegalStateException` naming the first such key, on every search, with no request and no twitter4j configuration built |
| `mapper/TwitterResponseMapperTest` | every status of a result, in order, mapped to an `ArrayList` of tweets with id and text; an empty list serializes as an empty `java.util.ArrayList`; a non-empty list fails with `SerializationException`; `Tweet` is not `Serializable` |
| `SecretLoggingTest` | the whole application at `logging.level.root=TRACE` with each secret set to a sentinel: each upstream receives its secret and the Twitter search is signed with both secrets, while the log holds none of the six, on success, on an upstream error status and on an upstream timeout |
| `exception/GlobalExceptionHandlerTest` | SC-1, copied with only its `package` line changed: the `404`, `405` and `500` status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path |
| `config/PortRoutingFilterTest` | SC-10's shared test, copied with its component: each port serves only its routes, other paths get the no-listener `404` written by the filter, named routes are forwarded, the registration and port validation, an additional listener serving the same deployment, an empty TLS password named in the start-up failure; an additional port another socket holds failing server creation with `PortInUseException` before any XNIO worker exists; an unbindable host (`192.0.2.1`, a `.invalid` name) named with its port in an `IllegalStateException`; a server on port -1 neither probed nor given a worker; a port taken after the probe failing the start within 30 s instead of hanging it (`PortInUseException` with one additional listener, a `WebServerException` caused by a `BindException` with two); `destroy()` stopping the context's started server before the worker; the worker's I/O and worker threads from `server.undertow.threads.io` and `server.undertow.threads.worker` or Undertow's defaults |
| `support/PortInUseRetryingContextLoaderTest` | SC-10 test support, copied with its component: a context whose additional port another socket holds on the first attempt loads on a newly drawn port, with one WARN line per lost attempt, and that listener serves on its port; only a port in use on the cause chain (`PortInUseException`, a `BindException` saying `Address already in use`) loads again; a port in use is attempted 5 times and any other failure once, then rethrown |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight is dispatched as a plain `OPTIONS` request with no CORS processing: on `/` it runs the flow, on `/api/stockStats` it gets the `OPTIONS` answer, and a cross-origin `GET /` still reaches the flow |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |
| `FixtureParityListenerPortTest` | ST-2 on the SC-10 listener `stock-stats`, in a JVM of its own: the application prepared by the replay's context initializer, with `ports.stock-stats` reserved through `${freePort:stock-stats}` as every fixture of this project sets it, starts although SC-10 probes that port when the web server is created, and the `stock-stats` listener serves `GET /api/stockStats?date=2012-11-28` on the reserved port (`400 Bad Request`, `stock is required`); an engine check, not parity evidence |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

`support/PortInUseRetryingContextLoader` is SC-10's test support. `MuleComponentBindingsTest`, `SecretLoggingTest`, both controller tests and `config/PreflightDispatchListenerTest` run the main listener on a random port and draw a free port for `ports.stock-stats` in `@DynamicPropertySource` on every context load; when another process takes it first, the loader loads the context again on a newly drawn port, up to 5 attempts.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/mule_component_bindings/FixtureParityTest.java` is ST-2, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure. The reference implementation and its format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). Every scenario uses the `http` driver: the captured request is sent through ST-1 to the application, on the random main port for the `8081` rows and on the extra listener `ports.stock-stats` for the `8180` rows, with WireMock serving the recorded Stocklytics and Sentiment140 exchanges; the Twitter exchanges cannot be recorded for replay (Fallback evidence). With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

`SCENARIOS.md` scenarios:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-twitter-search` | `http` | `deterministic` | port `8081`: `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-twitter-search` | `http` | `deterministic` | port `8081`: `POST /`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-unknown-path` | `http` | `deterministic` | port `8081`: `GET /favicon.ico`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-wrong-port` | `http` | `deterministic` | port `8081`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-more-tweets` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=<D>`, empty body (`<D>` chosen at capture) | `UNVERIFIED-uncaptured` |
| `get-stock-stats-missing-stock` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?date=2012-11-28`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-missing-date` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-bad-date-length` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-1-26`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-unparseable-date` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=abcd-ef-gh`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-invalid-symbol` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=XYZ&date=2012-11-28`, empty body | `UNVERIFIED-uncaptured` |
| `get-stock-stats-upstream-failure` | `http` | `deterministic` | port `8180`: `GET /api/stockStats?stock=AAPL&date=2012-11-28`, empty body (the request of `get-stock-stats`; only Stocklytics answering a status other than 200 selects this branch) | `UNVERIFIED-uncaptured` |
| `post-stock-stats-method-not-allowed` | `http` | `deterministic` | port `8180`: `POST /api/stockStats?stock=AAPL&date=2012-11-28`, empty body | `UNVERIFIED-uncaptured` |
| `get-api-unknown-resource` | `http` | `deterministic` | port `8180`: `GET /api/unknown`, empty body | `UNVERIFIED-uncaptured` |

All fourteen scenarios are `deterministic`; none is `nondeterministic` or `unreachable`. The `204` branch for missing statistics has no scenario, because the original `StocklyticsStockService` always returns statistics; only `StockStatsServiceTest` and `StockStatsControllerTest` reach it.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| mule-component-bindings-java | mule-component-bindings | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 535 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 535 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 104 of 104 lines (`service/StockStatsService` 76, `service/TwitterSearchService` 17, `mapper/TwitterResponseMapper` 11). Total project LINE coverage: 88.4% (1109 of 1254 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=14`, `failed=0`.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". No fixture can be captured from the original, so parity rests on the fallback:

- `MuleComponentBindingsTest` carries the original committed assertion with its contains semantics: the stock-statistics payload contains `reply.json`.
- `StockStatsControllerTest`, `TwitterSearchControllerTest`, `StockStatsServiceTest`, `TwitterSearchServiceTest`, the three client tests and `TwitterResponseMapperTest` cover every reachable branch on the wire or against the upstream stubs.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the Jersey runtime and the platform defaults, not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: every `400` text (`stock is required`, `date is required`, `date format must be YYYY-MM-DD`, `Invalid stock symbol <stock>`); the `500` path with its raw message; the `204` path; the `moreTweets` cap at 1000 tweets; the whole `8081` `/` flow and its serialized payload; the no-listener `404` and the `405` defaults; the `400`, `406` and `415` media rejections and the `OPTIONS` answer.

Unverified scenarios, all fourteen until captured: `get-root-twitter-search`, `post-root-twitter-search`, `get-root-unknown-path`, `get-stock-stats-wrong-port`, `get-stock-stats`, `get-stock-stats-more-tweets`, `get-stock-stats-missing-stock`, `get-stock-stats-missing-date`, `get-stock-stats-bad-date-length`, `get-stock-stats-unparseable-date`, `get-stock-stats-invalid-symbol`, `get-stock-stats-upstream-failure`, `post-stock-stats-method-not-allowed`, `get-api-unknown-resource`.

What capture can change, per `SCENARIOS.md` (no scenario is `nondeterministic`):

- The eight scenarios without an outbound call can become `VERIFIED`: `get-root-unknown-path`, `get-stock-stats-wrong-port`, `get-stock-stats-missing-stock`, `get-stock-stats-missing-date`, `get-stock-stats-bad-date-length`, `get-stock-stats-unparseable-date`, `post-stock-stats-method-not-allowed`, `get-api-unknown-resource`.
- The four that reach Twitter, `get-root-twitter-search`, `post-root-twitter-search`, `get-stock-stats` and `get-stock-stats-more-tweets`, cannot be recorded for replay: each Twitter call carries an OAuth 1.0a signature over live secrets, so their fixtures omit `upstream` and stay `UNVERIFIED-no-upstream`, or `UNVERIFIED-uncaptured` if the Twitter v1.1 search fails at capture time.
- `get-stock-stats`, `get-stock-stats-more-tweets`, `get-stock-stats-invalid-symbol` and `get-stock-stats-upstream-failure` select their branch only through a Stocklytics response, so they are uncapturable while Stocklytics is offline.
- At best, while those services stay as they are, the label becomes `PARITY: PARTIAL — Tier 2A fixtures: 8 verified, 6 unverified`; `PARITY: VERIFIED — Tier 2A fixtures` is out of reach while the Twitter rows omit `upstream`. The remaining scenarios go to the human escalation of AAP 0.8.8.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `mule-component-bindings/`. The classes ported from the original keep their MuleSoft copyright header (`Copyright 2014 MuleSoft, Inc.`), which `NOTICE` attributes.
