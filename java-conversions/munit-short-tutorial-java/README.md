# munit-short-tutorial-java

Spring Boot 3.2.12 / Java 17 conversion, served by Undertow, of the Mule EE 3.8 example [`munit-short-tutorial`](../../munit-short-tutorial/) (category General). The original folder `munit-short-tutorial/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

The original is a tutorial on writing MUnit tests for a small flow built from Mule core components only, with no Anypoint Connector. Its production code does three things: it receives an HTTP request, extracts the `url_key` query parameter to route the message through a second flow and one of two sub-flows, and chooses the response from the flow variable that sub-flow set. The full tutorial text, with its walk-through of every MUnit test, is in [`munit-short-tutorial/README.md`](../../munit-short-tutorial/README.md) and is not repeated here.

The tutorial's code listing shows `port="9090"` and `version="EE-3.6.1"` (`munit-short-tutorial/README.md:25,32`). The deployed flow file `munit-short-tutorial/src/main/app/production-code.xml` (`version="EE-3.8.0"`, the only `config.resources` entry of `mule-deploy.properties`) uses `port="${http.port}"` instead (`production-code.xml:4`). The original's `mule-app.properties` is empty, and its MUnit run gave `http.port` a dynamic port (`munit-maven-plugin` `dynamicPort` in `munit-short-tutorial/pom.xml`). The conversion keeps the key `http.port`, with default `8081` (AAP 0.4.9).

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/` | `GET` only, compared ignoring case, so `get` runs the flow too: the listener declares `allowedMethods="GET"` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body `response_payload_1` or `response_payload_2` (18 bytes, UTF-8, no trailing newline), `Content-Length: 18`, no `Content-Type` header | `munit-short-tutorial/src/main/app/production-code.xml:4-21` |
| `/` | every other method: `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` (a CORS pre-flight included) and `TRACE` | `0.0.0.0` | `http.port` | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /`, no `Allow` header; the flow does not run, and a `HEAD` reply carries the headers without the body (SC-1, AAP 0.4.7) | `production-code.xml:7`; listener platform default |
| any other path (`/unknown`, `/index`, `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>`, the path without its query (SC-1, AAP 0.4.7) | listener platform default |
| `/`, when processing throws | `GET` | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception |

- Every query selects one of the two branches, so no request reaches the 500 row through the real service. `ExampleControllerTest` reaches it with a mocked failing service.
- Only the query string is read. The headers and the body are ignored, so no `Accept`, `Content-Type` or body is answered with 406, 415 or 400. The query map is parsed from the raw query string rather than from the servlet parameter map, so a form-urlencoded body cannot select a branch the original never selected (`ExampleController` Javadoc).
- The 200 reply carries no `Content-Type`, because the flow's `set-payload` processors declare no MIME type and the Mule listener sent none for such a payload. `ExampleController` writes the bytes straight to the servlet response.
- A query with a malformed percent escape (for example `/?url_key=%zz`) is answered by Undertow before dispatch with an empty `400 Bad Request` and `Connection: close`.
- A CORS pre-flight is answered like any other `OPTIONS` request, with the listener's 405 and no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A request body the flow leaves unread is drained after the response, so requests pipelined on one HTTP/1.1 connection are all answered, as on the Mule listener (Grizzly), which read the whole request before the flow ran (`config/RequestBodyDrainFilter`).
- Tier 2A verification points (AAP 0.4.7): the absent `Content-Type` of the 200 reply; the bytes of the 404, 405 and 500 defaults; the 405 for `HEAD`, `OPTIONS` and `TRACE`; a repeated `url_key` using its last value, as Mule 3's query parameter map returned for a single-valued lookup; and the 400 for a malformed percent escape. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Flow behaviour

`ExampleController` binds the query parameters and writes the payload; `service/ExampleFlowService` runs the flow graph of `production-code.xml`, one method per Mule element:

| Step | Mule element (`production-code.xml`) | Java method | Behaviour |
|---|---|---|---|
| 1 | `set-payload` `Set Original Payload`, `#[message.inboundProperties['http.query.params']['url_key']]` (`:8`) | `ExampleFlowService.setOriginalPayload(queryParams)` | payload = the `url_key` query value, `null` when absent |
| 2 | `flow-ref` `exampleFlow2` (`:10`); flow `exampleFlow2`, `choice` `#['payload_1'.equals(payload)]` (`:23-32`) | `exampleFlow2(payload, flowVars)` | `payload_1` → `subFlow1(flowVars)` (`exampleSub_Flow1`, `:34-36`, sets `my_variable` = `var_value_1`); any other payload, `null` included → `subFlow2(flowVars)` (`exampleSub_Flow2`, `:38-40`, sets `my_variable` = `var_value_2`) |
| 3 | `choice` of flow `exampleFlow`, `#[flowVars['my_variable'].equals('var_value_1')]` (`:13-20`) | `exampleFlow(queryParams)` | `var_value_1` → `response_payload_1` (`:15`), otherwise `response_payload_2` (`:18`) |

- Query parsing, as Mule 3.8 filled `http.query.params`: pairs split on `&` and at the first `=`, name and value decoded as UTF-8 (`+` becomes a space), a pair without `=` given a `null` value, and a repeated name keeping its last value.
- `'payload_1'.equals(payload)` is exact and case-sensitive: `PAYLOAD_1`, an empty value and a missing key all reach sub-flow 2.
- Flow variables live in a `HashMap<String, Object>` created per request in `exampleFlow` and passed down the graph, so the variable a sub-flow sets is visible to `exampleFlow` afterwards, as in Mule 3.
- The step 3 condition dereferences `my_variable` as the MEL expression did, so a missing variable, which only a stubbed `exampleFlow2` can produce, throws `NullPointerException` and reaches the 500 row. It is deliberately neither null-guarded nor reversed.

Exchanges (every 200 reply is `HTTP/1.1 200 OK`, `Content-Length: 18`, no `Content-Type`):

| Request | Status | Body |
|---|---|---|
| `GET /?url_key=payload_1` | `200 OK` | `response_payload_1` |
| `GET /?url_key=payload_2` | `200 OK` | `response_payload_2` |
| `GET /` (no `url_key`, so sub-flow 2) | `200 OK` | `response_payload_2` |
| `GET /?url_key=payload%5F1` (decoded before routing) | `200 OK` | `response_payload_1` |
| `GET /?url_key=payload_2&url_key=payload_1` (last value used) | `200 OK` | `response_payload_1` |
| `GET /?url_key=PAYLOAD_1`, `/?url_key=`, `/?url_key`, `/?other=payload_1` | `200 OK` | `response_payload_2` |
| `POST /` | `405 Method Not Allowed` | `Method not allowed for endpoint: /` |
| `GET /unknown` | `404 Not Found` | `No listener for endpoint: /unknown` |

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour; `unset` — read by the application's code but not set in `application.yml`, so the stated default applies.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; MUnit assigned it a dynamic port. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`production-code.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`production-code.xml:4`) |
| `spring.application.name` | `String` | `munit-short-tutorial-java` | none | fixed | log prefix only |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches the dispatcher and gets SC-1's 405, instead of the servlet's `200` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | the listener checked the method before reading the body, so a form body on `PUT`, `PATCH` or `DELETE` reaches the controller and gets SC-1's 405 unread, instead of being read whole by Boot's form-content filter before dispatch |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

- The original reads no secret, credential, host or tenant identifier, so the configuration has no TODO placeholder, and the project has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  HTTP_PORT=9090 java -jar target/munit-short-tutorial-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`munit-short-tutorial`) | Java construct (`com.mulesoft.examples.munit_short_tutorial`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`production-code.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/" allowedMethods="GET"` (`production-code.xml:7`) | `controller/ExampleController`: `exampleFlow` (`@RequestMapping("/")`, which rejects every method but `GET` with `HttpRequestMethodNotSupportedException`) and `rejectOptions` (`OPTIONS`, always rejected) |
| inbound property `http.query.params` | the `Map<String, String>` `ExampleController` builds from the raw query string |
| flow `exampleFlow` (`production-code.xml:6-21`) | `service/ExampleFlowService.exampleFlow` |
| `set-payload` `Set Original Payload` (`production-code.xml:8`) | `ExampleFlowService.setOriginalPayload` |
| flow `exampleFlow2` (`production-code.xml:23-32`) | `ExampleFlowService.exampleFlow2` |
| sub-flows `exampleSub_Flow1` / `exampleSub_Flow2` (`production-code.xml:34-40`) | `ExampleFlowService.subFlow1` / `subFlow2` |
| flow variables (`set-variable my_variable`, `flowVars['my_variable']`) | a per-request `HashMap<String, Object>` passed between the methods |
| MEL literals `url_key`, `payload_1`, `my_variable`, `var_value_1`, `var_value_2`, `response_payload_1`, `response_payload_2` | constants `URL_KEY`, `PAYLOAD_1`, `MY_VARIABLE`, `VAR_VALUE_1`, `VAR_VALUE_2`, `RESPONSE_PAYLOAD_1`, `RESPONSE_PAYLOAD_2` of `ExampleFlowService` |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (whole request read before the flow, no CORS policy) | `config/RequestBodyDrainFilter`, `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `production-code.xml`) | `MunitShortTutorialApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| MUnit suite `production-code-test-suite.xml` (`mock:when`, `mock:verify-call`, `munit:assert-payload-equals`) | `ProductionCodeTestSuiteTest` (JUnit 5, Mockito `@SpyBean` with `doReturn`, `doAnswer` and `verify`) |

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. The original's build dependencies are not carried: the Mule modules (`mule-transport-http`, `mule-module-http`, `mule-module-spring-config`, `mule-module-extensions-support`, `mule-module-extensions-spring-support`, `mule-module-apikit`), `json-simple` 1.1.1, which no flow uses, and MUnit (`mule-munit-support`, `munit-runner`, `munit-maven-plugin`), replaced by JUnit 5 and Mockito from `spring-boot-starter-test`. Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/munit-short-tutorial-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/munit-short-tutorial-java-1.0.0-SNAPSHOT.jar
```

Expected exchange (the `Date` value varies):

```console
$ curl -i "http://localhost:8081/?url_key=payload_1"
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 18
Date: <current date>

response_payload_1
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project and calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ProductionCodeTestSuiteTest` | the six MUnit tests of `munit-short-tutorial/src/test/munit/production-code-test-suite.xml`, one JUnit 5 method each, against the Spring context with `ExampleFlowService` as a Mockito `@SpyBean` (next section) |
| `controller/ExampleControllerTest` | on the wire through ST-1, the real service behind the Undertow listener: `GET /?url_key=payload_1` → `HTTP/1.1 200 OK` `response_payload_1` and `url_key=payload_2` → `response_payload_2`, both with `Content-Length: 18` and no `Content-Type`; a missing `url_key` → `response_payload_2`; a repeated `url_key` uses its last value; `payload%5F1` is decoded before routing; `/?url_key=`, `/?url_key`, `/?url_key=PAYLOAD_1` and `/?other=payload_1` → `response_payload_2`; lower-case `get` runs the flow; `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `TRACE`, `POST` with a `url_key` query, and `HEAD` → 405 `text/plain` `Method not allowed for endpoint: /`; a form declaring 300000 bytes sent with `PUT`, `PATCH` or `DELETE` → the whole 405 while only its first 1024 bytes have been written, with no `FormContentFilter` bean in the context; `/unknown`, `/other/path` and `/unknown?url_key=payload_1` → 404 `text/plain` `No listener for endpoint: <path>`; a failing service → 500 `application/json` `{ "message": "boom" }` |
| `service/ExampleFlowServiceTest` | every branch of both choices on a Mockito spy without a Spring context: `payload_1` reaches sub-flow 1 only; `payload_2`, `Payload_1`, an empty value, a missing key and non-`String` payloads reach sub-flow 2 only; each sub-flow sets only `my_variable`; `setOriginalPayload` returns the `url_key` value or `null`; a stubbed `exampleFlow2` that sets no variable, and a `null` query map, throw `NullPointerException`; the processors run in flow order on one flow-variable map; the constants hold the original literals |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` gets exactly the plain `OPTIONS` reply (405 `text/plain` `Method not allowed for endpoint: /`, no `Access-Control-*` or `Vary`) and the flow does not run; a cross-origin `GET` still reaches the controller; a pre-flight to an unserved path gets the no-listener 404 |
| `config/RequestBodyDrainFilterTest` | pipelined requests behind a body-less request are all answered, on `/` and on an unserved path; draining never changes the chain's outcome and leaves reader-read and asynchronous bodies alone |
| `FixtureParityTest` | ST-2 fixture replay (see [Fixture parity (ST-2)](#fixture-parity-st-2)); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, with an HTTPS mode for TLS listeners.

### How the MUnit suite was ported

The suite imported `production-code.xml` into a running Mule context and addressed its processors through `flow-ref`, `mock:when` and `mock:verify-call`. `ProductionCodeTestSuiteTest` runs the application's Spring context without a web server (`@SpringBootTest(webEnvironment = NONE)`) and replaces `ExampleFlowService` with a Mockito spy. The service calls its own flow, sub-flow and `Set Original Payload` methods through `this.`, so the spy records and can stub every step the suite mocked or verified.

| MUnit test (`production-code-test-suite.xml`) | JUnit method | Input | Mockito mechanism (replacing) | Assertion |
|---|---|---|---|---|
| `doc-test-exampleFlow2Test1` (`:19-27`) | `docTestExampleFlow2Test1` | `exampleFlow2("payload_1", new HashMap<>())` (the `munit:set` payload) | `verify(exampleFlowService, times(1)).subFlow1(anyMap())` and `verify(exampleFlowService, never()).subFlow2(anyMap())` (`mock:verify-call messageProcessor="mule:sub-flow"` `times="1"` on name `matchContains('exampleSub_Flow1')`) | sub-flow 1 called once, sub-flow 2 never |
| `doc-test-exampleFlow2Test2` (`:29-37`) | `docTestExampleFlow2Test2` | `exampleFlow2("payload_2", new HashMap<>())` | `verify(exampleFlowService, times(1)).subFlow2(anyMap())` and `never()` for `subFlow1` (`mock:verify-call` on `matchContains('exampleSub_Flow2')`) | sub-flow 2 called once, sub-flow 1 never |
| `doc-test-exampleFlow-unit-Test_1` (`:40-59`) | `docTestExampleFlowUnitTest1` | `exampleFlow(null)`: the test sent no inbound properties | `doReturn(null)` on `setOriginalPayload(any())` (`mock:when` `mule:set-payload` `Set Original Payload`, `then-return payload="#[]"`); `doAnswer` on `exampleFlow2(any(), anyMap())` putting `my_variable` = `var_value_1` into the caller's flow variables (`mock:when` `mule:flow` `exampleFlow2` with invocation property `my_variable`) | `assertEquals("response_payload_1", result, "oops, wrong payload!")` |
| `doc-test-exampleFlow-unit-Test_2` (`:61-80`) | `docTestExampleFlowUnitTest2` | `exampleFlow(null)` | as above, with `my_variable` = `var_value_2` | `assertEquals("response_payload_2", result, "oops, wrong payload!")` |
| `doc-test-exampleFlow-functionalTest_1` (`:83-91`) | `docTestExampleFlowFunctionalTest1` | `exampleFlow(Map.of("url_key", "payload_1"))` (inbound property `http.query.params` = `['url_key':'payload_1']`) | none: the real flow graph runs behind the spy | `assertEquals("response_payload_1", result, "oops, wrong payload!")` |
| `doc-test-exampleFlow-functionalTest_2` (`:93-101`) | `docTestExampleFlowFunctionalTest2` | `exampleFlow(Map.of("url_key", "payload_2"))` | none | `assertEquals("response_payload_2", result, "oops, wrong payload!")` |

- `munit:assert-payload-equals` becomes `assertEquals` with the suite's message `oops, wrong payload!`, and the expected values are the suite's literals rather than the service's constants, so the test pins the original values.
- The functional tests' `munit:set payload="#['']"` has no counterpart, because `Set Original Payload` overwrites that payload before anything reads it.
- `doc-test-exampleFlow-functionalTest_2` labels its `munit:set` `Set Message url_key:payload_1` (`:94`) while its value is `payload_2` (`:96`); the value is what the test sends, so `payload_2` is ported.
- The spy's stubs and recorded invocations are reset after each test, as MUnit reset its mocks between tests.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/munit_short_tutorial/FixtureParityTest.java` is ST-2, copied from the reference in [`hello-world-java`](../hello-world-java/) with only its `package` line changed; [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md) lists this project's scenarios and the fixture contract, comparison rules and capture procedure (AAP 0.8.4).

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, with the sections `request`, `state`, `upstream` and `response`. Every scenario here has `"state": {}` and `"upstream": []`, because the original reaches no store and makes no outbound call.
- `deterministic` scenarios are compared byte for byte: status, reason phrase, `Content-Type`, `Location` and body.
- Each run writes `target/fixture-parity/summary.properties` with the counts `verified`, `unverified` and `failed` and one `scenario.<name>=<STATUS>` line per row.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-url-key-payload-1` | `http` | `deterministic` | `GET /?url_key=payload_1`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-url-key-payload-2` | `http` | `deterministic` | `GET /?url_key=payload_2`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-missing-url-key` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-method-not-allowed` | `http` | `deterministic` | `POST /`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

All five scenarios are `deterministic`; none is `unreachable` or `nondeterministic`, because every branch is selected by some request and the flow reads no clock, random value or generated name.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| munit-short-tutorial-java | munit-short-tutorial | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`, which runs every phase of `clean package`: `BUILD SUCCESS`; 168 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 168 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 16 of 16 lines of `service/ExampleFlowService` (the project has no `mapper` package). Total project LINE coverage: 97.1% (236 of 243 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in [`DECISIONS.md`](../../DECISIONS.md). No fixture can be captured from the original, so parity rests on the fallback:

- `ProductionCodeTestSuiteTest` carries the original committed assertions: the six MUnit tests, with their verify-call counts, mocks and payload assertions.
- `ExampleControllerTest` and `ExampleFlowServiceTest` cover every reachable branch, on the wire and in the flow graph.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- The original suite calls the flows through `flow-ref` and never through the HTTP listener, so these branches have no original assertion and are covered only by the new `ExampleControllerTest`: `GET /` over HTTP end to end (`get-root-url-key-payload-1`, `get-root-url-key-payload-2`), a request without `url_key` (`get-root-missing-url-key`), a non-`GET` method answered with 405 (`post-root-method-not-allowed`) and an unserved path answered with 404 (`get-unknown-path`).
- Unverified scenarios, until captured: `get-root-url-key-payload-1`, `get-root-url-key-payload-2`, `get-root-missing-url-key`, `post-root-method-not-allowed`, `get-unknown-path`.

Capture once Tier 2A is unblocked, by the procedure in the Capture section of `SCENARIOS.md`: build the unmodified original with `mvn -B package` using the EE repository credentials, deploy it to a licensed Mule 3.8 EE runtime with `http.port=8081` (`-M-Dhttp.port=8081`, since `mule-app.properties` is empty), send each scenario's request with `curl --raw -i`, keep the raw output as evidence under `src/test/capture/<scenario>/`, write `src/test/resources/fixtures/<scenario>.json` from it, and re-run `mvn -B test` so `FixtureParityTest` replays the fixtures. A fixture is never edited to match the Java output: a mismatch is fixed in the Java code, which for `post-root-method-not-allowed` and `get-unknown-path` means SC-1 `GlobalExceptionHandler`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `munit-short-tutorial/`.

