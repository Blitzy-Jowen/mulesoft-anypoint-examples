# track-a-custom-business-event-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`track-a-custom-business-event`](../../track-a-custom-business-event/) (category General). The original folder `track-a-custom-business-event/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

Order data in JSON is posted to an HTTP endpoint. A Ruby transformer applies the discount offered for the product type, and a custom business event records the item name, the number of units and the price per unit. The single flow `custom-business-eventsFlow1` of `track-a-custom-business-event/src/main/app/custom-business-events.xml:4-25` declares no exception strategy and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/customBusinessEvents` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body the discounted unit price as text (`8.5` for `message.json`), with its `Content-Length`; `Content-Type` as described below; a `HEAD` reply carries the headers without the body | `custom-business-events.xml:3-5` |
| any other path (`/`, `/customBusinessEvents/x`, `/error` and `/customBusinessEvents` with `;` segment parameters, such as `/customBusinessEvents;x=1`, included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/customBusinessEvents`, when the flow fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception |

- Only the exact path `/customBusinessEvents` is served. A path carrying `;` segment parameters (`/customBusinessEvents;x=1`, `/customBusinessEvents;jsessionid=ABC`) is not that path: it gets the no-listener 404 naming it with its parameters, and the flow does not run, so no business event is tracked. The Mule listener matched the raw path, query removed, literally, where Spring MVC ignores segment parameters (`config/ListenerPathConfig`). SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method: a `GET` or `PUT` carrying the JSON body is processed exactly as a `POST`.
- The request's `Content-Type` decides the encoding and the reply's `Content-Type`, as the Mule message carried the inbound media type and encoding through transformers that declare none of their own (`controller/BusinessEventController` Javadoc). The body is decoded with the request's `charset`, or UTF-8 when it declares none, and the result is encoded with it. The reply's `Content-Type` is the request's type and subtype in lower case followed by `; charset=<canonical charset name>`: `application/json; charset=UTF-8` for `Content-Type: application/json`, `application/json; charset=ISO-8859-1` for `charset=latin1`. A request without `Content-Type`, or with `*/*`, is answered without one. `Accept` is never consulted, so the endpoint never answers 406 or 415.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow like any other `OPTIONS` request and gets no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Request and response

The original test input `src/test/resources/message.json` (126 bytes, byte-identical to `track-a-custom-business-event/src/test/resources/message.json`):

```json
{ 
	"email": "aaa@abc.sk",  
	"item name": "shoes",  
	"item units": 2,  
	"item price per unit": 10, 
	"membership": "free"
}
```

Posted with `Content-Type: application/json`, it is answered `200 OK`, `Content-Type: application/json; charset=UTF-8`, `Content-Length: 3`, body `8.5`: shoes at 10 less 15 %.

### Processing

In the original processor order (`custom-business-events.xml:6-24`), all in `service/PriceDiscountService.processPriceRequest`:

1. The JSON body becomes a `java.util.HashMap` (`json-to-object-transformer returnClass="java.util.HashMap"`, line 6). Integers stay `Integer`, `Long` or `BigInteger` by size and decimals become `Double`, as Mule's transformer produced them.
2. The custom business event `Price` is recorded with the metadata `item name`, `item units` and `item price per unit`, each the payload value as text, `null` when absent (`tracking:custom-event`, lines 7-11).
3. The discount is selected by exact, case-sensitive item name: `shoes` 0.15, `jeans` 0.2, `jackets` 0.3 (JRuby `case`, lines 13-20). The result is `price × (1 − discount)` in `double` arithmetic (line 21), rendered by `Double.toString`, so it always carries a fraction: `8.5`, `8.0`, `7.0` for shoes, jeans and jackets at 10 (`object-to-string-transformer`, line 23).
4. The transaction id is set to `custom-business-event-example-transaction` (`tracking:transaction`, line 24).

### Business-event logging

`service/BusinessEventTracker` replaces the Mule EE tracking elements with structured SLF4J entries:

- Each request opens a tracking transaction with a random UUID in the MDC key `transactionId`, the analogue of the Mule message id that `tracking:enable-default-events="true"` (line 4) tracks.
- The `Price` event is logged at INFO under that generated id, with the MDC key `businessEvent` set to `Price` for that line only. It is logged before the discount is computed, so it appears even when the script fails.
- Once the flow has succeeded, the id is replaced by `custom-business-event-example-transaction`, as `tracking:transaction` does at the end of the flow, and the replacement is logged. The MDC is cleared after every request.
- `logging.pattern.correlation` renders both keys on every log line:

  ```text
  … INFO … [transactionId=9a3db063-86b0-4dcc-82ac-645b70d26154 businessEvent=Price] c.m.e.t.service.BusinessEventTracker : Business event Price {item name=shoes, item units=2, item price per unit=10}
  … INFO … [transactionId=custom-business-event-example-transaction businessEvent=] c.m.e.t.service.BusinessEventTracker : Transaction id set to custom-business-event-example-transaction
  ```

- Line terminators in caller-supplied values are escaped, so each event stays one physical log line.
- Not carried (AAP 0.4.5): the Mule EE tracking UI, CloudHub Insight and the MMC agents. Step 8 of the original README (the Insight tab) therefore has no equivalent; the business event is visible in the application log.

### Error behaviour

The flow declares no exception strategy, so every failure reaches the SC-1 platform defaults of AAP 0.4.7. The texts of the script failures are JRuby's.

| Request to `/customBusinessEvents` | Response |
|---|---|
| item name outside the table (`hats`, `Shoes`), missing or not a string | `500`, `application/json`, `{ "message": "nil can't be coerced into Fixnum" }`; the `Price` event is logged first |
| known item, price missing or `null` | `500`, `application/json`, `` { "message": "undefined method `*' for nil:NilClass" } `` |
| known item, price an object, array or boolean | `500`, `application/json`, `` { "message": "undefined method `*' for <value>" } ``, the value as `toString()` renders it (`[10]`, `{amount=10}`, `true`) |
| price a JSON string (`"10"`) | `200`, empty body, `Content-Length: 0`: Ruby `String#*` truncates the factor to 0 |
| malformed or truncated JSON, or a form-encoded body (curl's default for `--data` without `-H 'Content-Type: …'`) | `500`, `application/json`, `{ "message": "Payload is not valid JSON" }` |
| empty body, or a JSON array, string, number or `null` | `500`, `application/json`, `{ "message": "JSON payload is not an object" }` |
| `Content-Type` whose charset cannot be parsed (`charset=bad[charset]`) | `500`, `application/json`, `{ "message": "Invalid mime type \"…\": …" }`, Spring's message (`config/RequestCharsetFilter` keeps it from becoming a container page) |

Any other path is answered `404`, `text/plain`, `No listener for endpoint: <path>`. The messages for an unreadable body are fixed texts, never Jackson's diagnostic, so they quote no request bytes.

### Tier 2A verification points

When a captured fixture shows different bytes for any of these, the fixture governs and the code changes to match (AAP 0.4.7, 0.8.4):

- the 500 body text: the JRuby messages and the platform's `{ "message": … }` shape;
- the `Content-Type` of the 200 reply, echoed from the request with its charset, and absent for a request without one;
- the string-price result, `200` with an empty body;
- the unknown-item result. The original JRuby `$discount` is a Ruby global that only a matching `when` assigns. The conversion treats it as not surviving between requests (JRuby JSR-223 global-variable behaviour), so an unknown item always fails, whatever was requested before (`PriceDiscountServiceTest.eachRequestStartsWithoutADiscountWhateverWasRequestedBefore`). `post-unknown-item` is therefore captured first on a freshly deployed original. If a captured fixture shows the previous request's discount reused, the fixture governs and `service/PriceDiscountService` changes;
- the `404` text, the `404` for a path carrying `;` segment parameters, the reply to a form-encoded body and to an `OPTIONS` request without a body.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Status legend as in [`hello-world-java`](../hello-world-java/README.md#configuration-keys): `default` is a non-secret value the original fixed, overridable; `fixed` is a framework setting that reproduces the original listener, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port`. The original's listener reads `${http.port}` (`custom-business-events.xml:3`) and its `mule-app.properties` is empty; its README tells users to set port 8081 |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port, resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`custom-business-events.xml:3`) |
| `server.servlet.encoding.enabled` | `boolean` | `false` | none | fixed | the listener took the request charset from its `Content-Type` alone; Boot's forced UTF-8 request encoding would reject a wildcard `Content-Type` before the controller |
| `spring.application.name` | `String` | `track-a-custom-business-event-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `BusinessEventController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `logging.level.org.springframework.web.method.HandlerMethod`, `logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor`, `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver`, `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | below INFO these loggers write the caller's headers, `Content-Type` and body bytes; their own level outranks a root `DEBUG` or `TRACE` (`config/RequestLoggingLevelsTest`) |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | as above, for the request line with its query string |
| `logging.pattern.correlation` | `String` | `[transactionId=%X{transactionId:-} businessEvent=%X{businessEvent:-}] ` | none | fixed | renders the tracking MDC on every log line |

- Original-to-new key map: `http.port` → `http.port`. No other key existed.
- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8082
  HTTP_PORT=8082 java -jar target/track-a-custom-business-event-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`track-a-custom-business-event`) | Java construct (`com.mulesoft.examples.track_a_custom_business_event`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (line 3) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="customBusinessEvents"` without `allowedMethods` (line 5) | `controller/BusinessEventController`, `@RequestMapping("/customBusinessEvents")`: `handle` (every method but `OPTIONS`) and `handleOptions` (`OPTIONS`), both writing the same reply |
| `tracking:enable-default-events="true"` (line 4) | `service/BusinessEventTracker.startTransaction` and `endTransaction` (per-request MDC `transactionId`) |
| `json-to-object-transformer returnClass="java.util.HashMap"` (line 6) | Jackson into `HashMap`, in `service/PriceDiscountService` |
| `tracking:custom-event event-name="Price"` with three `tracking:meta-data` (lines 7-11) | `service/BusinessEventTracker.trackCustomEvent` |
| JRuby `scripting:transformer` (lines 12-22) | `PriceDiscountService` discount table (`SHOES_DISCOUNT`, `JEANS_DISCOUNT`, `JACKETS_DISCOUNT`), no script engine |
| `object-to-string-transformer` (line 23) | `String.valueOf` |
| `tracking:transaction id="custom-business-event-example-transaction"` (line 24) | `BusinessEventTracker.setTransactionId` with `PriceDiscountService.TRANSACTION_ID` |
| default exception strategy | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| listener semantics (no CORS policy, request charset from `Content-Type` alone, the raw path, query removed, matched literally with its `;` segment parameters) | `config/PreflightDispatchConfig`, `config/RequestCharsetFilter`, `config/ListenerPathConfig` |
| `mule-deploy.properties` (loads `custom-business-events.xml`) | `TrackACustomBusinessEventApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `TrackACustomBusinessEventIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `TrackACustomBusinessEventTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

Shared components (AAP 0.4.4), each copied with only the `package` line changed:

- SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse` and `exception/GlobalExceptionHandlerTest`, from `rest-api-with-apikit-java`.
- ST-1 `support/HttpTestClient`, from `rest-api-with-apikit-java`.
- ST-2 `FixtureParityTest`, from `hello-world-java`; `src/test/resources/fixtures/SCENARIOS.md` copies that project's format with this project's rows.
- `config/PreflightDispatchConfig` and `config/PreflightDispatchConfigTest` are the classes `hello-world-java` carries.
- `config/ListenerPathConfig` is the class `hello-world-java` carries.

## Behaviours stubbed or externalized

- The Mule EE tracking UI, CloudHub Insight and MMC are not carried (AAP 0.4.5); business events go to the application log (Business-event logging above). The original README's CloudHub steps (deploy, enable Metadata and Replay, the Insight tab) have no equivalent: the application runs locally with `mvn spring-boot:run` or `java -jar`.
- The JRuby script and the Groovy metadata expressions are reimplemented in Java; no script engine is used.
- The original reaches no external system and holds no credential, so nothing is mocked at runtime or moved to configuration. Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/track-a-custom-business-event-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8082
java -jar target/track-a-custom-business-event-java-1.0.0-SNAPSHOT.jar
```

Expected exchange (the `Date` value varies). `--data-binary` sends `message.json` byte for byte, as the fixture capture does; `--data` strips its line ends, which gives the same reply:

```console
$ curl -i -X POST -H 'Content-Type: application/json' --data-binary @src/test/resources/message.json http://localhost:8081/customBusinessEvents
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json; charset=UTF-8
Content-Length: 3
Date: <current date>

8.5
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `TrackACustomBusinessEventTest` | the ported `TrackACustomBusinessEventIT` (`track-a-custom-business-event/src/test/java/org/mule/examples/TrackACustomBusinessEventIT.java:52-62`): `POST /customBusinessEvents` with `message.json` returns a non-empty body equal to `8.5` |
| `controller/BusinessEventControllerTest` | on the wire through ST-1: jeans → `8.0` and jackets → `7.0` (shoes → `8.5` in every other happy path), each `HTTP/1.1 200 OK` with its `Content-Length`; the reply's `Content-Type` for each request media type and charset (`latin1` answered as `ISO-8859-1`), and none for an absent or `*/*` `Content-Type`; the same reply for `PUT`, `GET`, `DELETE`, `PATCH` and `OPTIONS`, with no `Allow` header; `HEAD` with headers only; 500 `application/json` for an unknown item, a price nested 20000 deep, malformed JSON and five unparseable charsets; 404 `text/plain` `No listener for endpoint: <path>` for `/`, `/customBusinessEvents/x`, `/customBusinessEventsX`, `/unknown`, `/customBusinessEvents;x=1` and `/customBusinessEvents;jsessionid=ABC`, whatever charset the request declares; no business event or transaction id logged, in the captured console output, for a path with `;` segment parameters, while the same request to `/customBusinessEvents` logs its `Price` event |
| `service/PriceDiscountServiceTest` | Mockito with a mocked tracker: every discount (`8.5`, `8.0`, `7.0`, decimal, long and big-integer prices, the string price → empty text), charset decoding, deep nesting and long values; every script failure with its JRuby text and the event metadata logged before it; every unreadable body (`Payload is not valid JSON`, `JSON payload is not an object`) raised before the event, with messages that quote no request bytes; the order and count of tracker calls; no discount carried from one request to the next |
| `service/BusinessEventTrackerTest` | the MDC keys `transactionId` and `businessEvent`; a random UUID per transaction; the `Price` INFO line `Business event Price {item name=shoes, item units=2, item price per unit=10}` in its transaction context; `setTransactionId` replacing and logging the id; `endTransaction` clearing it; line terminators escaped in every logged value |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight carrying `message.json` gets exactly the plain `OPTIONS` reply (`200 8.5`, no `Access-Control-*` or `Vary`); a pre-flight to `/unknown` or `/customBusinessEvents;x=1` gets the no-listener 404 |
| `config/RequestCharsetFilterTest` | a request whose charset is illegal, unsupported or unreadable continues as a view that declares none, its `Content-Type` untouched; every other request continues unchanged |
| `config/RequestLoggingLevelsTest` | under `logging.level.root=TRACE`, no header, credential, query string, `Content-Type` or body a caller sends reaches the log, while the `Price` event is still logged at INFO with its tracking MDC |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped until Tier 2A fixtures exist |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` and the format of `src/test/resources/fixtures/SCENARIOS.md` are copied from `hello-world-java`. Its README ([Fixture parity](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation)) documents the fixture format, the replay drivers, the comparison rules and `target/fixture-parity/summary.properties`; this project's `SCENARIOS.md` repeats the full contract with its own rows.

This project's scenarios, exactly as `SCENARIOS.md` names them, all `http`:

| Scenario | State | Request | Status in the last run |
|---|---|---|---|
| `post-shoes-discount` | `deterministic` | `POST /customBusinessEvents`, `Content-Type: application/json`, body `message.json` | `UNVERIFIED-uncaptured` |
| `post-jeans-discount` | `deterministic` | as above, `"shoes"` replaced by `"jeans"` | `UNVERIFIED-uncaptured` |
| `post-jackets-discount` | `deterministic` | as above, `"shoes"` replaced by `"jackets"` | `UNVERIFIED-uncaptured` |
| `put-shoes-discount` | `deterministic` | `PUT /customBusinessEvents`, `Content-Type: application/json`, body `message.json` | `UNVERIFIED-uncaptured` |
| `post-unknown-item` | `deterministic` | as `post-shoes-discount`, `"shoes"` replaced by `"hats"`; captured first | `UNVERIFIED-uncaptured` |
| `post-missing-price` | `deterministic` | as `post-shoes-discount`, without the `"item price per unit"` line | `UNVERIFIED-uncaptured` |
| `post-string-price` | `deterministic` | as `post-shoes-discount`, price `"10"` | `UNVERIFIED-uncaptured` |
| `post-malformed-json` | `deterministic` | `POST /customBusinessEvents`, `Content-Type: application/json`, body `{ "item name": ` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `deterministic` | `GET /other`, empty body | `UNVERIFIED-uncaptured` |

- None has a captured fixture yet. No scenario is `unreachable` or `nondeterministic`: the flow makes no outbound call and reads no clock or random value, and the transaction id is logged, never returned. Every fixture's `state` is `{}` and its `upstream` is `[]`.
- Tier 2A capture (`SCENARIOS.md`, section Capture): build the unmodified original with `mvn -B package` and the EE repository credentials, deploy it to a licensed Mule 3.8 EE runtime with `-M-Dhttp.port=8081`, then send each scenario's request with `curl --raw -i` against `http://localhost:8081`, `post-unknown-item` first on the freshly deployed original, and write the raw output unedited into the fixture. Fixtures are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| track-a-custom-business-event-java | track-a-custom-business-event | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 237 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 237 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 129 of 129 lines of `service/PriceDiscountService` (with its nested `OpenContainer`) and `service/BusinessEventTracker` (the project has no `mapper` package). Total project LINE coverage: 98.3% (354 of 360 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=9`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". No fixture can be captured from the original, so parity rests on the fallback:

- `TrackACustomBusinessEventTest` carries the original committed assertion: the reply to `message.json` equals `8.5`.
- `BusinessEventControllerTest` and `PriceDiscountServiceTest` cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the JRuby semantics and platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: jeans, jackets, unknown item, missing price, string price, malformed JSON and unknown path, as well as any-method acceptance (`put-shoes-discount`).
- Unverified scenarios, until captured: `post-shoes-discount`, `post-jeans-discount`, `post-jackets-discount`, `put-shoes-discount`, `post-unknown-item`, `post-missing-price`, `post-string-price`, `post-malformed-json`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `track-a-custom-business-event/`.

