# cache-scope-with-fibonacci-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`cache-scope-with-fibonacci`](../../cache-scope-with-fibonacci/) (category General). The original folder `cache-scope-with-fibonacci/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

The example demonstrates the Mule cache scope with the Fibonacci function F(n) = F(n-1) + F(n-2), with F(0) = 0 and F(1) = 1, for an `n` the HTTP caller supplies. Intermediate results are cached and reused by later requests. Every reply also reports the cost of the calculation: each calculation step adds 1, and a result taken from the cache costs 0 (`cache-scope-with-fibonacci/README.md`).

Flow `cache-exampleFlow1` (`cache-scope.xml:43-59`) receives the request, filters out a `/favicon.ico` payload and sends an `n` below 20 to flow `calculateFibonacci` (`cache-scope.xml:61-82`). That flow runs inside an `ee:cache` scope keyed by `n` and calls itself for `n-1` and `n-2` through `vm://fibonacci`, using the MEL global function `fibonacciRequest` (`cache-scope.xml:7-27`). `FibonacciResponseGenerator` sets the cost of a cached response to 0. The example calls no external system and declares no exception strategy.

## Endpoints and triggers

| Path | Methods | Host | Port | Inputs | Source |
|---|---|---|---|---|---|
| `/fibonacci` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `localhost` (`server.address`) | `http.port`, default `8081` | query parameters `n` and `nocache`, request header `nocache`, the request body (the payload the favicon filter tests) | `cache-scope.xml:40,44` |

Responses. Every `200` reply carries `Content-Length` and no `Content-Type`.

| Request | Status | Headers | Body | Branch |
|---|---|---|---|---|
| `n` a number below 20 | `200 OK` | `cost: <cost>` | `Fibonacci(<n as sent>) = <value>`, a line feed (LF), `COST: <cost>` | `when` → `calculateFibonacci` (`cache-scope.xml:51-53`) |
| `n` absent, or `20` and above | `200 OK` | no `cost` | `ERROR: n must be less than 20` | `otherwise` (`cache-scope.xml:55-57`) |
| body whose text payload is exactly `/favicon.ico` | `200 OK` | no `cost` | empty (`Content-Length: 0`) | favicon `message-filter` rejection (`cache-scope.xml:45-49`, AAP 0.4.7) |
| `n` present but not a number, the empty value included (`n=abc`, `n=`) | `500 Internal Server Error` | `Content-Type: application/json` | `{ "message": "Invalid value for n: <n>" }` | unhandled exception, SC-1 (AAP 0.4.7) |
| `n` a non-integer number of at least 2 and below 20 (`n=2.5`) | `500 Internal Server Error` | `Content-Type: application/json` | `{ "message": "Fibonacci key must be an integer: <n>" }` | unhandled exception, SC-1 |
| `Content-Type` that cannot be parsed (`Content-Type: bogus` with a body) | `500 Internal Server Error` | `Content-Type: application/json` | the parser's message, for example `{ "message": "Invalid mime type \"bogus\": does not contain '/'" }` | unhandled exception, SC-1 |
| `application/x-www-form-urlencoded` body with a malformed percent escape | `400 Bad Request` | no `Content-Type`, no `cost` | `Cannot decode x-www-form-urlencoded payload` | the Mule listener's own rejection, before the flow runs |
| any other path (`/favicon.ico`, `/`, `/fibonacci/10`, `/error` and `/fibonacci` with `;` segment parameters, such as `/fibonacci;x=1`, included) | `404 Not Found` | `Content-Type: text/plain` | `No listener for endpoint: <path>` | no listener, SC-1 (AAP 0.4.7) |

Every `500` is also logged by SC-1 as one `ERROR` line that names the method, the route and the exception classes.

How the request is read, as the Mule 3.8 listener read it (`controller/FibonacciController`):

- The favicon filter tests the message payload, not the request path. The payload is `null` for a request without a body or with a `multipart` body, the decoded parameter map for a form-urlencoded body, and the body text for any other body, decoded in the charset its `Content-Type` declares (UTF-8 when none is declared). Only a text payload can equal `/favicon.ico`, so the same text sent as a form or multipart body passes the filter and the flow continues. A browser's `GET /favicon.ico` is answered by the no-listener 404, because the listener serves only `/fibonacci`.
- Query parameters come from the raw query string. When a name repeats, the last occurrence wins, and a bare name without `=` reads as absent, as Mule's `ParameterMap.get` returned it: `?n=5&n=10` computes 10, `?n=5&n` takes the `otherwise` branch.
- `n` is compared with 20 as a decimal number, but the reply and the cache key use it exactly as sent: `n=05` answers `Fibonacci(05) = 5`.
- A path carrying `;` segment parameters (`/fibonacci;x=1`, `/fibonacci;jsessionid=ABC`, `/fibonacci;`) is not `/fibonacci`: every method, `HEAD` and `OPTIONS` included, gets the no-listener 404 naming the path with its parameters and without the query, `No listener for endpoint: /fibonacci;x=1` for `/fibonacci;x=1?n=10`, and the flow does not run, so nothing is calculated or cached and no `cost` header is sent. The Mule listener matched the raw path, query removed, literally, where Spring MVC ignores segment parameters (`config/ListenerPathConfig`). A `;` in the query string is not part of the path.
- A `HEAD` request to `/fibonacci` runs the flow (the cache included) and gets the headers without the body. A CORS pre-flight is answered like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).

Worked example, from the original README and `CacheScopeWithFibonacciIT` (`CacheScopeWithFibonacciIT.java:48-56`), on a freshly started application (the `Date` value varies):

```console
$ curl -i 'http://localhost:8081/fibonacci?n=10'
HTTP/1.1 200 OK
Connection: keep-alive
cost: 11
Content-Length: 27
Date: <current date>

Fibonacci(10) = 55
COST: 11
```

```console
$ curl -i 'http://localhost:8081/fibonacci?n=5'
HTTP/1.1 200 OK
Connection: keep-alive
cost: 0
Content-Length: 24
Date: <current date>

Fibonacci(5) = 5
COST: 0
```

The second request costs 0 because computing F(10) stored F(5) in the cache.

### Calculation and cache

- Spring Cache over Caffeine replaces the `ee:cache` scope and its `ee:object-store-caching-strategy` (`cache-scope.xml:35,63`). `service/FibonacciCacheService` reads and writes the single cache `fibonacci` directly. No `@Cacheable` annotation is used, because the calculation calls itself and a hit must report cost 0.
- Cache key: at the top level, the `n` string exactly as received, the original key expression `#[message.inboundProperties['http.query.params'].n]`. So `"10"`, `"010"` and `"10.0"` are three different keys. In the recursion, the keys are the decimal forms of `n-1` and `n-2` (`Integer.toString`), as `fibonacciRequest` built them.
- Value and cost: for `n < 2` the value is `n` as sent (`n=-3` answers `-3`, `n=0.5` answers `0.5`) at cost 1. Otherwise `n-1` is computed before `n-2`, the value is their sum and the cost is their costs plus 1. On an empty cache the `n-1` branch stores `n-2`, so the second call is a hit at cost 0 and a cold `n >= 2` costs `n + 1` (F(10) costs 11, F(19) = 4181 costs 20).
- A hit returns the stored value with cost 0, the ported `FibonacciResponseGenerator` (`model/FibonacciResult.cached()`). It runs no recursion and writes no log line.
- Access is synchronized per key: a request for a key another request is computing waits, then receives the stored result at cost 0.
- The cache is in memory and lost on restart. It holds at most 4000 entries and never expires or refreshes one (`spring.cache.caffeine.spec: maximumSize=4000`), the Mule 3.8.0 in-memory object-store defaults `maxEntries` 4000 and `entryTTL` -1. The bound stops caller-chosen spellings of `n` from growing the heap without limit. The decision and its evidence are in [`DECISIONS.md`](../../DECISIONS.md) section 6.10. Tune it through `spring.cache.caffeine.spec`.

### Original quirks kept

- Request header `nocache`, with any value and in any letter case: the top-level calculation bypasses the cache, with no lookup and no store. Its two sub-calculations are sent without the header and use the cache. After `n=10`, `curl -H 'nocache: true' 'http://localhost:8081/fibonacci?n=10'` answers `COST: 1`.
- Query parameter `nocache`, with any value, the empty value included (a bare `nocache` without `=` reads as absent): the two direct sub-calculations (`n-1`, `n-2`) bypass the cache, while their own sub-calculations, requested with `n` alone, use it again. The top-level result itself is still cached. On an empty cache `?n=10&nocache=true` costs 12. After `?n=8` it costs 3, and a following `?n=9` costs 1, because the bypassed `9` was never stored.
- Logger `com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciCacheService` writes `aaa: null` at `INFO` for every calculation step that is not a cache hit, bypassed steps included. This renders the original logger `message="aaa: #[]"` (`cache-scope.xml:64`), whose empty expression evaluates to null.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener or cache, not a key of the original, and changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README tells users to set port 8081. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`cache-scope.xml:40`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `localhost` | none | fixed | `http:listener-config host="localhost"` (`cache-scope.xml:40`) |
| `spring.application.name` | `String` | `cache-scope-with-fibonacci-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | every path but `/fibonacci`, `/favicon.ico` included, must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` runs the flow like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST`, so the listener payload is built the same way |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `spring.cache.type` | `CacheType` | `caffeine` | none | fixed | Spring Cache over Caffeine in place of the `ee:cache` scope |
| `spring.cache.cache-names` | `List<String>` | `fibonacci` | none | fixed | the one cache `service/FibonacciCacheService` uses, in place of the `Caching_Strategy` object store (`cache-scope.xml:35`) |
| `spring.cache.caffeine.spec` | `String` (Caffeine spec) | `maximumSize=4000` | none | fixed | the Mule 3.8.0 in-memory object-store defaults: 4000 entries, no expiry (`DECISIONS.md` section 6.10) |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder, and no key was renamed (`mule-app.properties` was empty). The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8082
  HTTP_PORT=8082 java -jar target/cache-scope-with-fibonacci-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`cache-scope-with-fibonacci`) | Java construct (`com.mulesoft.examples.cache_scope_with_fibonacci`) |
|---|---|
| `http:listener-config` host `localhost`, port `${http.port}` (`cache-scope.xml:40`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="fibonacci"` without `allowedMethods` (`cache-scope.xml:44`), and the transport's conversion of the result into the response | `controller/FibonacciController`, `@RequestMapping("/fibonacci")`: `fibonacci` (every method but `OPTIONS`) and `fibonacciOptions` (`OPTIONS`); builds the listener payload, writes the body and the `cost` header |
| `message-filter` / `not-filter` / `wildcard-filter pattern="/favicon.ico"` (`cache-scope.xml:45-49`) and the `choice` on `n < 20` with its two `expression-component` payloads (`cache-scope.xml:50-58`) | `service/FibonacciService.handle` |
| Flow `calculateFibonacci` with `ee:cache`, `ee:object-store-caching-strategy`, the VM request-response recursion and the MEL global function `fibonacciRequest` (`cache-scope.xml:7-27,35,61-82`) | `service/FibonacciCacheService.calculate`: direct recursive calls, the VM transport removed; per-key locking for the synchronized cache access |
| `FibonacciResponseGenerator` (Mule `DefaultResponseGenerator`, `PropertyScope.OUTBOUND` cost 0) | the cost-0 rule on a hit inside `FibonacciCacheService`, through `model/FibonacciResult.cached()` |
| flow payload and outbound property `cost` | `model/FibonacciResult` (value, cost) and `FibonacciService.FibonacciReply` (body, `cost` header) |
| `logger level="INFO" message="aaa: #[]"` (`cache-scope.xml:64`) | SLF4J `INFO` `aaa: null` in `FibonacciCacheService` |
| Default exception strategy and listener defaults (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy; the raw path, query removed, matched literally, `;` segment parameters included) | `config/PreflightDispatchConfig`, `config/ListenerPathConfig` |
| `mule-deploy.properties` (loads `cache-scope.xml`) | `CacheScopeWithFibonacciApplication` (`@SpringBootApplication`, `@EnableCaching`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `CacheScopeWithFibonacciIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `CacheScopeWithFibonacciTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. The VM transport the recursion used stays inside the application in the original too, so replacing it with direct calls changes no observable exchange. Studio metadata (`mule-project.xml`, `catalog/`) is not carried (AAP 0.2.2).

### Tier 2A verification points

Platform defaults the source does not settle. When a captured fixture shows different bytes or behaviour, the fixture governs and the code is changed to match (AAP 0.4.7):

- the absent `Content-Type` of every `200` reply;
- the `cost` response header, which the Mule listener sent from the outbound property `cost`;
- the empty `200` of the favicon filter rejection;
- the `500` status and body for a non-numeric `n`, and for a non-integer `n` of 2 or more (`n=2.5`), where MEL's coercion and `Integer.toString` failure are not reproducible from the source;
- the bytes of the no-listener `404`, and the `404` for `/fibonacci` with `;` segment parameters;
- the store limits of the caching strategy, which declares no object store: the 4000-entry bound, and Caffeine's eviction policy against the in-memory store's oldest-first trim (`DECISIONS.md` section 6.10);
- whether the caching strategy synchronized access by default, which the Mule 3.8 documentation does not state;
- the rendering of the `aaa: #[]` log line as `aaa: null`.

## Build, run and test

Run from `java-conversions/cache-scope-with-fibonacci-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on localhost:8081
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8082
java -jar target/cache-scope-with-fibonacci-java-1.0.0-SNAPSHOT.jar
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

Test classes under `src/test/java/com/mulesoft/examples/cache_scope_with_fibonacci/`:

| Test class | What it asserts |
|---|---|
| `CacheScopeWithFibonacciTest` | the ported `CacheScopeWithFibonacciIT` (`cache-scope-with-fibonacci/src/test/java/org/mule/examples/CacheScopeWithFibonacciIT.java:44-57`), with its exact-equality assertions: on an empty cache, `GET /fibonacci?n=10` returns `Fibonacci(10) = 55\nCOST: 11`, then `GET /fibonacci?n=5` returns `Fibonacci(5) = 5\nCOST: 0` |
| `controller/FibonacciControllerTest` | on the wire through ST-1: status line, `cost`, absent `Content-Type`, `Content-Length` and body bytes for cold calculations (`n` = 10, 19, 1, -3), every method, the cache hit, the raw-string key (`05`), the `nocache` header and query parameter, the `otherwise` branch (`n=20`, `n` absent), the favicon filter on text bodies and its pass-through for form and multipart bodies, the listener's 400 for an undecodable form body, the 500 for an unparseable `Content-Type` and a non-numeric `n`, the no-listener 404 for `/favicon.ico`, `/` and `/fibonacci/10`, and for `/fibonacci;x=1`, `/fibonacci;jsessionid=ABC` and `/fibonacci;` with `?n=10` for `GET`, `POST` with a `text/plain` body, `HEAD` (headers only) and `OPTIONS`, each without a `cost` header and with nothing cached, and the cache's 4000-entry bound without expiry |
| `controller/FibonacciControllerBindingTest` | `OPTIONS` runs the flow (calculation, `otherwise`, favicon filter and the 500) with no `Allow` header; a repeated query parameter takes its last occurrence and a bare name reads as absent, for `n` and for `nocache` |
| `service/FibonacciServiceTest` | `handle` against `cache-exampleFlow1` with a strict mock of the cache service: the favicon filter (case-sensitive, text payload only), the `n < 20` choice, the raw `n` passed to the cache service with both `nocache` flags, the reply texts, and a non-numeric `n` rejected without touching the cache |
| `service/FibonacciCacheServiceTest` | `calculate` against `calculateFibonacci` on a real Caffeine cache: 10 then 5, the base case, F(19) costs 20, both bypass modes, raw-string keys, negative and invalid keys, the `aaa: null` log count per computed step, the cost-0 copy of a hit, and per-key synchronization under concurrent requests |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/fibonacci?n=10` gets exactly the plain `OPTIONS` reply (`200`, `Fibonacci(10) = 55\nCOST: 11`, `cost: 11`), never Spring's 403, with no `Access-Control-*` or `Vary` header; a pre-flight to `/unknown` or `/fibonacci;x=1?n=10` gets the no-listener 404 and calculates nothing |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes. Every test that runs the real cache clears the `fibonacci` cache before each case, so expected costs never depend on test order.

## Fixture parity (ST-2)

`FixtureParityTest` and `src/test/resources/fixtures/SCENARIOS.md` are copies of the ST-2 reference in `java-conversions/hello-world-java`, whose [README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation) describes the fixture format, the replay drivers and the comparison rules. `SCENARIOS.md` lists this project's rows and its full capture procedure.

- Fixtures go in `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, captured from the running original (deployed with `http.port=8081`) and never written or edited by hand.
- Every scenario depends on the cache state. The capture restarts the original before each scenario and then sends the prior requests the row's `Seeded state` lists. Each fixture's `state` therefore holds `"clearCaches": true`, which empties the `fibonacci` cache before the replay, and, where the row lists prior requests, `requests`, which the replay sends before the scenario's own request and whose replies it discards.
- Every scenario's `upstream` is `[]`: the `vm://fibonacci` calls of the recursion stay inside the application.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), an assumption and never a failure.

This project's scenarios, exactly as `SCENARIOS.md` names them. All are `http` and `deterministic`; none is `unreachable` or `nondeterministic`.

| Scenario | Request | Seeded state | Status in the last run |
|---|---|---|---|
| `get-fibonacci-10-cold-cache` | `GET /fibonacci?n=10`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-5-after-10` | `GET /fibonacci?n=5`, empty body | cache cleared; then `GET /fibonacci?n=10` | `UNVERIFIED-uncaptured` |
| `get-fibonacci-1-base-case` | `GET /fibonacci?n=1`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-negative-n` | `GET /fibonacci?n=-3`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-leading-zero-key` | `GET /fibonacci?n=05`, empty body | cache cleared; then `GET /fibonacci?n=10` | `UNVERIFIED-uncaptured` |
| `post-fibonacci-10-any-method` | `POST /fibonacci?n=10`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-20-too-high` | `GET /fibonacci?n=20`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-missing-n` | `GET /fibonacci`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-non-numeric-n` | `GET /fibonacci?n=abc`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `post-fibonacci-favicon-body` | `POST /fibonacci`, header `Content-Type: text/plain`, body `/favicon.ico` | cache cleared | `UNVERIFIED-uncaptured` |
| `get-favicon-path-no-listener` | `GET /favicon.ico`, empty body | cache cleared | `UNVERIFIED-uncaptured` |
| `get-fibonacci-nocache-header` | `GET /fibonacci?n=10`, header `nocache: true`, empty body | cache cleared; then `GET /fibonacci?n=10` | `UNVERIFIED-uncaptured` |
| `get-fibonacci-nocache-query` | `GET /fibonacci?n=10&nocache=true`, empty body | cache cleared; then `GET /fibonacci?n=8` | `UNVERIFIED-uncaptured` |
| `get-fibonacci-9-after-nocache-query` | `GET /fibonacci?n=9`, empty body | cache cleared; then `GET /fibonacci?n=8`; then `GET /fibonacci?n=10&nocache=true` | `UNVERIFIED-uncaptured` |

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| cache-scope-with-fibonacci-java | cache-scope-with-fibonacci | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 199 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 199 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 65 of 65 lines of `service/FibonacciService` and `service/FibonacciCacheService` with their nested types (the project has no `mapper` package). Total project LINE coverage: 97.6% (321 of 329 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=14`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed", as recorded in [`DECISIONS.md`](../../DECISIONS.md). No fixture can be captured from the original, so parity rests on the fallback:

- `CacheScopeWithFibonacciTest` carries the original committed assertions, with their exact-equality semantics: `Fibonacci(10) = 55\nCOST: 11` on an empty cache, then `Fibonacci(5) = 5\nCOST: 0`.
- `FibonacciControllerTest`, `FibonacciControllerBindingTest`, `FibonacciServiceTest` and `FibonacciCacheServiceTest` cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the flow and the platform defaults, but not that its bytes equal what the original runtime sent.
- These branches have no original assertion and are covered only by the new tests: the `otherwise` branch (`n` of 20 and above, and `n` absent), the favicon-body filter, the 500 for a non-numeric `n`, the `nocache` header and query paths, and `n < 2` at the top level (`n=1`, `n=-3`).
- Unverified scenarios, until captured: every one of the fourteen in the table above.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `cache-scope-with-fibonacci/`.
