# hello-world-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`hello-world`](../../hello-world/) (category General). The original folder `hello-world/` stays unchanged beside this project and is the Tier 2A capture source.

The example answers an HTTP request to `/helloWorld` with the text `Hello World`. The single flow `HelloWorldFlow1` of `hello-world/src/main/app/HelloWorld.xml:4-7` receives the request and sets a constant payload. It declares no logger and no exception strategy, and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/helloWorld` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body `Hello World` (11 bytes, UTF-8, no trailing newline), `Content-Length: 11`, no `Content-Type` header; a `HEAD` reply carries the headers without the body | `hello-world/src/main/app/HelloWorld.xml:3-6` |
| any other path (`/helloWorld/`, `/helloWorld/extra` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/helloWorld`, when processing throws | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception |

- No input can fail this flow: the request's query, headers and body are never read, so any `Accept`, `Content-Type` or body is ignored and never answered with 406, 415 or 400. The 500 row is reached only by `HelloWorldControllerTest`, through a mocked failing service. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- The reply carries no `Content-Type`, because the flow's `set-payload` declares no MIME type and the Mule listener sent none for such a payload (`HelloWorldController` Javadoc). `HelloWorldController` writes the bytes straight to the servlet response, so no message converter adds one.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, with `200 Hello World` and no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A request body the flow leaves unread is drained after the response, so requests pipelined on one HTTP/1.1 connection are all answered. The Mule listener (Grizzly) read the whole request before the flow ran (`config/RequestBodyDrainFilter`).
- Tier 2A verification points (AAP 0.4.7): the absent `Content-Type` of the `200` reply and the bytes of the `404` and `500` defaults. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

| Key | Default | Original name | Notes |
|---|---|---|---|
| `http.port` | `8081` | `http.port` (unchanged) | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` holds comments only, so `http.port` had no value there; its README tells users to set port 8081 and browse to `http://localhost:8081/helloWorld`. |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  HTTP_PORT=9090 java -jar target/hello-world-java-1.0.0-SNAPSHOT.jar
  ```

Fixed settings in `src/main/resources/application.yml`, none of them a configuration key of the original:

| Setting | Value | Why |
|---|---|---|
| `server.address` | `0.0.0.0` | `http:listener-config host="0.0.0.0"` (`HelloWorld.xml:3`) |
| `spring.web.resources.add-mappings` | `false` | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `true` | `TRACE` reaches `HelloWorldController` like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `false` | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

## Mule-to-Java mapping

| Mule construct (`hello-world`) | Java construct (`com.mulesoft.examples.hello_world`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`HelloWorld.xml:3`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="helloWorld"` without `allowedMethods` (`HelloWorld.xml:5`) | `controller/HelloWorldController`, `@RequestMapping("/helloWorld")`: `helloWorld` (every method but `OPTIONS`) and `helloWorldOptions` (`OPTIONS`), both writing the same reply |
| `set-payload value="Hello World"` (`HelloWorld.xml:6`) | `service/HelloWorldService.hello()`, returning the constant `HELLO_WORLD` |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (whole request read before the flow, no CORS policy) | `config/RequestBodyDrainFilter`, `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `HelloWorld.xml`) | `HelloWorldApplication` (`@SpringBootApplication`) |
| `mule-app.properties` | `src/main/resources/application.yml` |
| `HelloWorldIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `HelloWorldTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/hello-world-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/hello-world-java-1.0.0-SNAPSHOT.jar
```

Expected exchange (the `Date` value varies):

```console
$ curl -i http://localhost:8081/helloWorld
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 11
Date: <current date>

Hello World
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `HelloWorldTest` | the ported `HelloWorldIT` (`hello-world/src/test/java/org/mule/examples/HelloWorldIT.java:44-46`): `GET /helloWorld` with an empty body returns a response with a non-empty body equal to `Hello World` |
| `controller/HelloWorldControllerTest` | on the wire through ST-1: `GET /helloWorld` → `HTTP/1.1 200 OK`, no `Content-Type`, `Content-Length: 11`, body `Hello World`; the same reply for `POST`, `PUT`, `DELETE` and `PATCH` with a `text/plain` body, for a malformed form body, for `OPTIONS` and for `Accept: application/json`; `/unknown` and `/helloWorld/extra` → 404 `text/plain` `No listener for endpoint: <path>`; a failing service → 500 `application/json` `{ "message": "boom" }` |
| `service/HelloWorldServiceTest` | `hello()` returns `Hello World`, non-null and non-empty, on every call; `HELLO_WORLD` equals the original payload |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/helloWorld` gets exactly the plain `OPTIONS` reply (`200 Hello World`, no `Content-Type`, no `Access-Control-*` or `Vary`); a pre-flight to `/unknown` gets the no-listener 404 |
| `config/RequestBodyDrainFilterTest` | pipelined requests behind a body-less request are all answered, on `/helloWorld` and on an unserved path; draining never changes the chain's outcome and leaves reader-read and asynchronous bodies alone |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |
| `FixtureParityBoundaryTest` | the replay's isolation: scheduled triggers suppressed, configured targets outside the replay refused before the context starts, table and collection state only in replay-owned stores, credentials masked in diagnostics |
| `FixtureParityDirectoryTest` | the replay's directory containment: every seeded or named directory resolves inside the replay's temporary root |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, with an HTTPS mode for TLS listeners.

## Fixture parity (ST-2 reference implementation)

This project holds the reference `src/test/java/com/mulesoft/examples/hello_world/FixtureParityTest.java` and the reference table contract `src/test/resources/fixtures/SCENARIOS.md`. The other 50 non-RAML projects copy the test with only its `package` line changed, and copy the headings, table header and prose sections of `SCENARIOS.md`, writing their own rows (AAP 0.4.4).

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, each a JSON object with the sections `request`, `state`, `upstream` and `response` (AAP 0.8.4). `request` and `response` are required. A fixture without a matching row fails the class.
- A fixture without an `upstream` key has an incomplete recording: its scenario is skipped and reported unverified (`UNVERIFIED-no-upstream`). `"upstream": []` means the original made no outbound call, which is true of every hello-world scenario.
- `-Dfixtures.dir=<path>` replaces the classpath fixtures with `<path>/*.json` (and `<path>/SCENARIOS.md` when present). It is for local engine checks only.
- The row's trigger selects the replay driver:
  - `http` and `soap`: the captured request is sent through ST-1 (its TLS mode for HTTPS listeners) to the application on a random port.
  - `file` and `scheduler`: the input file and the seeded directories (a scheduler's SC-8 watermark store file among them) are written, then the single-run method named in `request.invoke` is called.
  - `jms` and `mq`: the captured message is sent through the project's JMS connection factory to the embedded broker.
  - `amqp`: the captured message is published through the project's `RabbitTemplate` to an embedded Qpid Broker-J.
  - `mail`: the captured MIME message is delivered to a GreenMail mailbox, then the poller's single-run method is called.

  WireMock serves the recorded upstream calls, so replay never contacts a live service.
- `deterministic` scenarios are compared byte for byte: status, reason phrase, `Content-Type`, `Location` and body. The only substitution is the replay port inside `Location`. `nondeterministic` scenarios compare status, reason and headers exactly and bodies by structure only, and are reported `UNVERIFIED — nondeterministic output`.
- Each run writes `target/fixture-parity/summary.properties`: the counts `verified`, `unverified` and `failed`, then one `scenario.<name>=<STATUS>` line per row.
- Fixtures are captured from the running original by the AAP 0.8.4 capture procedure, written out in `SCENARIOS.md` (section Capture), and are never hand-written or edited to match the Java output. A mismatch is fixed in the Java code.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-hello-world` | `http` | `deterministic` | `GET /helloWorld`, header `Accept: */*`, empty body | `UNVERIFIED-uncaptured` |
| `post-hello-world` | `http` | `deterministic` | `POST /helloWorld`, header `Content-Type: text/plain`, body `ping` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| hello-world-java | hello-world | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 267 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 267 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 2 of 2 lines of `service/HelloWorldService` (the project has no `mapper` package). Total project LINE coverage: 96.0% (190 of 198 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=3`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md`. No fixture can be captured from the original, so parity rests on the fallback:

- `HelloWorldTest` carries the original committed assertion: the body of `GET /helloWorld` equals `Hello World`.
- `HelloWorldControllerTest` covers every reachable branch on the wire.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- Two branches have no original assertion and are covered only by the new `HelloWorldControllerTest`: any-method acceptance (`post-hello-world`) and the no-listener 404 (`get-unknown-path`).
- Unverified scenarios, until captured: `get-hello-world`, `post-hello-world`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `hello-world/`.
