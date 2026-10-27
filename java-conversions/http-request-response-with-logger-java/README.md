# HTTP Request-Response with Logger — Spring Boot conversion

`http-request-response-with-logger-java` converts the Mule EE 3.8 example [`http-request-response-with-logger`](../../http-request-response-with-logger/) (category General) to Java 17 / Spring Boot 3.2.12 / Maven, package `com.mulesoft.examples.http_request_response_with_logger`. The original folder `http-request-response-with-logger/` stays unchanged at the repository root beside this project, as the reference and the Tier 2A capture source.

## Original example

A browser request echoes its own path. Browsing to `http://localhost:8084/echo` returns `/echo`, and `http://localhost:8084/moon` returns `/moon` (`http-request-response-with-logger/README.md:15,31-34`). Port 8084 is only the example value the original README asks the user to set on the HTTP listener in Studio (`README.md:29`). Here the default port is `8081`, configurable through `http.port`.

The single flow `EchoFlow` (`http-request-response-with-logger/src/main/app/echo.xml:4-8`) has three processors:

1. `http:listener` with path `/*` and no `allowedMethods`, on the `http:listener-config` bound to `0.0.0.0:${http.port}` (`echo.xml:3,5`).
2. `set-payload value="#[message.inboundProperties['http.request.path']]"`: the request path becomes the payload (`echo.xml:6`).
3. `logger level="INFO" message="About to echo #[message.payload]"` (`echo.xml:7`).

The listener then returns the payload as the response. The flow declares no exception strategy and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/**`: every path, the root `/`, nested paths such as `/a/b` and `/error` included | every method, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body = the request path as UTF-8 bytes, `Content-Length`, no `Content-Type` header; a `HEAD` reply carries the headers without the body | `echo.xml:3-7` |
| any path, when processing throws | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, one ERROR log line (SC-1 `GlobalExceptionHandler`, AAP 0.4.7) | platform default for an unhandled exception; the flow has no exception strategy |

- Path rule: the body is the raw request-target path without the query string, `HttpServletRequest.getRequestURI()`, the value of Mule's `http.request.path`. `/echo?x=1` echoes `/echo`, and percent-encoding is kept as sent: `/hello%20world` echoes `/hello%20world`.
- Logging: each request logs one SLF4J INFO line `About to echo <path>` under the logger `com.mulesoft.examples.http_request_response_with_logger.service.EchoService`. `service/EchoService` logs it after the payload is set and before the payload is returned, the order of the original's processors.
- Every path and every method is served, so SC-1's no-listener 404 (`No listener for endpoint: <path>`) and method 405 (`Method not allowed for endpoint: <path>`) cannot occur. The 500 row is reached only by `EchoControllerTest`, through a mocked failing service.
- The request's query, headers and body are never read, so any `Accept`, `Content-Type` or body is ignored and never answered with 406, 415 or 400.
- The reply carries no `Content-Type`, because the flow's `set-payload` declares no MIME type and the Mule listener sent none for such a payload. `EchoController` writes the bytes straight to the servlet response, so no message converter adds one.
- `OPTIONS` runs the flow through `EchoController.echoOptions`, as the Mule listener did, instead of Spring MVC's own reply with an `Allow` header and no body. A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) gets the same reply as any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A request body the flow leaves unread is drained after the response, so requests pipelined on one HTTP/1.1 connection are all answered. The Mule listener (Grizzly) read the whole request before the flow ran (`config/RequestBodyDrainFilter`).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Tier 2A verification points

The flow does not state these platform defaults. When a captured fixture shows different bytes, the fixture governs and the code is changed to match (AAP 0.4.7, 0.8.4):

- the response `Content-Type` (plan default: none, because the flow's `set-payload` declares no MIME type);
- whether `http.request.path` keeps percent-encoding (plan default: kept as sent);
- the response for `/` itself (plan default: served, body `/`);
- how `http.request.path` treats a query string (plan default: dropped);
- the bytes of SC-1's `500` reply.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged), the placeholder `${http.port}` of `echo.xml:3` | default | bound to `server.port`. The original's `mule-app.properties` holds two comment lines only, so `http.port` had no value there; its README uses `8084` as an example. Not a secret. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`echo.xml:3`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`echo.xml:3`) |
| `spring.application.name` | `String` | `http-request-response-with-logger-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | no static-resource handler, so every request reaches `EchoController` or SC-1 |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `EchoController` and is echoed, instead of getting the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` is not parsed before dispatch, so it is echoed as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` is echoed like any other path |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8084
  HTTP_PORT=8084 java -jar target/http-request-response-with-logger-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`http-request-response-with-logger`) | Java construct (`com.mulesoft.examples.http_request_response_with_logger`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`echo.xml:3`) | `application.yml` (`server.address`, `server.port`), on Undertow (`spring-boot-starter-undertow`, Tomcat excluded) |
| `http:listener path="/*"` without `allowedMethods` (`echo.xml:5`) | `controller/EchoController`, `@RequestMapping("/**")`: `echo` (every method but `OPTIONS`) and `echoOptions` (`OPTIONS`), both writing the same reply |
| `set-payload #[message.inboundProperties['http.request.path']]` (`echo.xml:6`) | `EchoService.echo(path)`, given `HttpServletRequest.getRequestURI()` by the controller |
| `logger level="INFO" message="About to echo #[message.payload]"` (`echo.xml:7`) | SLF4J INFO `About to echo {}` in `EchoService` |
| Platform default responses (unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (whole request read before the flow, no CORS policy) | `config/RequestBodyDrainFilter`, `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `echo.xml`) | `HttpRequestResponseWithLoggerApplication` (`@SpringBootApplication`) |
| `mule-app.properties` | `src/main/resources/application.yml` |
| `HttpRequestResponseWithLoggerTest` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `HttpRequestResponseWithLoggerTest` (JUnit 5, `@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. Studio metadata (`mule-project.xml`) and the test logging configuration `src/test/resources/log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/http-request-response-with-logger-java/`:

```sh
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn spring-boot:run     # listener on 0.0.0.0:8081
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8084   # the original README's URLs
java -jar target/http-request-response-with-logger-java-1.0.0-SNAPSHOT.jar
```

Expected exchange (the `Date` value varies), followed by the log line `About to echo /echo`:

```console
$ curl -i http://localhost:8081/echo
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 5
Date: <current date>

/echo
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).
- Coverage gate: JaCoCo fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80%. The project has no `mapper` package; the include is kept so the gate reads the same in every converted project.

## Tests

| Test class | What it asserts |
|---|---|
| `HttpRequestResponseWithLoggerTest` | the ported original assertion (`http-request-response-with-logger/src/test/java/org/mule/example/echo/HttpRequestResponseWithLoggerTest.java:41-51`): `GET /message` with an empty body returns a non-null response whose body is non-empty and equals `/message` |
| `controller/EchoControllerTest` | on the wire through ST-1 `support/HttpTestClient`: `GET /message`, `/echo`, `/moon`, `/`, `/a/b/c`, `/echo?x=1&y=2` (echoes `/echo`), `/hello%20world` (kept encoded) and `/error` → `HTTP/1.1 200 OK`, no `Content-Type`, `Content-Length`, body = the path; the same reply with `Accept: application/json`, for `POST`, `PUT`, `DELETE`, `PATCH` and `TRACE`, with empty, text, JSON and malformed form bodies, and for `OPTIONS` without an `Allow` header; `HEAD` → the headers without a body; a failing service → 500 `application/json` `{ "message": "boom" }`, or `{ "message": null }` without a message |
| `service/EchoServiceTest` | `echo(path)` returns the path unchanged and logs exactly one INFO event `About to echo <path>` under the logger named after `EchoService` |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight runs the flow exactly as a plain `OPTIONS` request to its path does (200, the path as body, no `Content-Type`, the `About to echo <path>` log line), with no `Access-Control-*` or `Vary` header |
| `config/RequestBodyDrainFilterTest` | pipelined requests behind a body-less request are all answered; draining never changes the chain's outcome and leaves reader-read and asynchronous bodies alone |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped until Tier 2A fixtures exist |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/http_request_response_with_logger/FixtureParityTest.java` is ST-2, copied from the reference in [`hello-world-java`](../hello-world-java/) with only its `package` line changed; the fixture format and replay drivers are described in the [hello-world README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md) lists this project's scenarios and holds the fixture contract, comparison rules and capture procedure (AAP 0.8.4).

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, with the sections `request`, `state`, `upstream` and `response`. Every scenario here has `"state": {}` and `"upstream": []`, because the original reaches no store and makes no outbound call.
- Capture deploys the unmodified original with `http.port=8084` (`-M-Dhttp.port=8084`), the port its README browses; replay runs this application on a random port.
- `deterministic` scenarios are compared byte for byte: status, reason phrase, `Content-Type`, `Location` and body.
- Each run writes `target/fixture-parity/summary.properties` with the counts `verified`, `unverified` and `failed` and one `scenario.<name>=<STATUS>` line per row.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-message` | `http` | `deterministic` | `GET /message`, empty body | `UNVERIFIED-uncaptured` |
| `get-echo` | `http` | `deterministic` | `GET /echo`, empty body | `UNVERIFIED-uncaptured` |
| `get-moon` | `http` | `deterministic` | `GET /moon`, empty body | `UNVERIFIED-uncaptured` |
| `post-message` | `http` | `deterministic` | `POST /message`, empty body | `UNVERIFIED-uncaptured` |
| `get-nested-path` | `http` | `deterministic` | `GET /a/b/c`, empty body | `UNVERIFIED-uncaptured` |
| `get-path-with-query` | `http` | `deterministic` | `GET /echo?x=1`, empty body | `UNVERIFIED-uncaptured` |
| `get-root` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |

All seven scenarios are `deterministic`; none is `unreachable` or `nondeterministic`, because the flow has one branch and its output depends on the request alone.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| http-request-response-with-logger-java | http-request-response-with-logger | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 175 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 175 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 5 of 5 lines of `service/EchoService` (the project has no `mapper` package). Total project LINE coverage: 96.8% (215 of 222 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=7`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in [`DECISIONS.md`](../../DECISIONS.md). No fixture can be captured from the original, so parity rests on the fallback:

- `HttpRequestResponseWithLoggerTest` carries the original committed assertion: the body of `GET /message` equals `/message`.
- `EchoControllerTest` covers every reachable request shape on the wire, and `EchoServiceTest` covers the payload and the log line.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- Four branches have no original assertion and are covered only by the new `EchoControllerTest`: non-`GET` methods (`post-message`), nested paths (`get-nested-path`), query-string stripping (`get-path-with-query`) and the root path (`get-root`). `get-echo` and `get-moon` follow the original README, which no test of the original asserts.
- Unverified scenarios, until captured: `get-message`, `get-echo`, `get-moon`, `post-message`, `get-nested-path`, `get-path-with-query`, `get-root`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `http-request-response-with-logger/`.
