# Implementing a Choice Exception Strategy — Spring Boot conversion

`implementing-a-choice-exception-strategy-java` converts the Mule EE 3.8 example [`implementing-a-choice-exception-strategy`](../../implementing-a-choice-exception-strategy/) (category General) to Java 17 / Spring Boot 3.2.12 / Maven, package `com.mulesoft.examples.implementing_a_choice_exception_strategy`. The original folder `implementing-a-choice-exception-strategy/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

A client sends JSON sales data (an order with `email`, `item name`, `item units`, `item price per unit` and `membership`) to an HTTP endpoint, and the order is validated. Missing data raises a `NullPointerException` and invalid data an `IllegalArgumentException`. A choice exception strategy routes each exception type to its own catch branch. Each branch sets the flow variables `statusCode` and `reason`, which the listener's response builder puts on the HTTP status line, and replies with the branch's message followed by the order. The application loads one flow file, `src/main/app/choice-error-handling.xml` (`config.resources` of its `mule-deploy.properties`), holding the single flow `choice-error-handlingFlow1` (`choice-error-handling.xml:4-48`). The flow declares no logger and calls no other system.

## Endpoints and triggers

One listener: path `/` on `0.0.0.0:${http.port}` (default `8081`), every HTTP method, because the original listener declares no `allowedMethods` (`choice-error-handling.xml:3-7`). A `GET`, `PUT` or any other method carrying a body runs the flow exactly as `POST` does. Every path other than exactly `/` answers SC-1's no-listener 404.

| Case | Request (example) | Response | Source |
|---|---|---|---|
| valid order | `POST /`, `Content-Type: application/json`, body `src/test/resources/message.json`: the original README's valid order (`email` `aaa@aaa.aa`, `item name` `aa`, `item units` 10, `item price per unit` 1, `membership` `free`) | `HTTP/1.1 200 OK`, no `Content-Type` header, `Content-Length: 29`, body `Input data validation passed.` | `choice-error-handling.xml:8-14` |
| no `email` key, or a missing or `null` `item units` / `item price per unit` → `NullPointerException` | `POST /`, `Content-Type: application/json`, body `src/test/resources/message_wrong.json` | `HTTP/1.1 400 Missing input data`, `Content-Type: text/plain;charset=UTF-8`, body `Missing input data: {item price per unit=1, membership=free, item name=aa, item units=10}` | "Catch Missing Data", `choice-error-handling.xml:31-45` |
| `item units` < 1, `item price per unit` < 0, or either not parseable as a Java `int` (`1.5`, `"ten"`: the `NumberFormatException` is an `IllegalArgumentException`) | `POST /`, `Content-Type: application/json`, the valid order with `item price per unit` `-1` | `HTTP/1.1 400 Invalid input data`, `Content-Type: text/plain;charset=UTF-8`, body `Invalid input data: {item price per unit=-1, membership=free, item name=aa, item units=10, email=aaa@aaa.aa}` | "Catch Invalid Data", `choice-error-handling.xml:16-30` |
| `email` rejected by the `regex-filter` (Mule `RegExFilter`: `find()`, case-sensitive; a `null` email is tested as the text `{NullPayload}`) | `POST /`, `Content-Type: application/json`, the valid order with `email` `not-an-email` | `HTTP/1.1 200 OK`, no `Content-Type` header, `Content-Length: 0`, empty body: a filter rejection without `throwOnUnaccepted` (AAP 0.4.7) | `choice-error-handling.xml:12-13` |
| body empty, not a JSON object (array, string, number, boolean), or the JSON literal `null` | `GET /` without a body | `HTTP/1.1 500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "JSON payload is not an object" }` (SC-1, AAP 0.4.7) | `choice-error-handling.xml:8`; no catch branch matches |
| body malformed or truncated JSON | `POST /`, `Content-Type: application/json`, body `{"a":` | `HTTP/1.1 500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "Payload is not valid JSON" }` (SC-1, AAP 0.4.7) | `choice-error-handling.xml:8`; no catch branch matches |
| any path other than `/` (`/error` included) | `GET /other` | `HTTP/1.1 404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: /other` (SC-1, AAP 0.4.7) | listener platform default |

- Error bodies end with `java.util.HashMap.toString()` of the parsed payload, as the original's `#[payload]` rendered the `HashMap` its `json-to-object-transformer returnClass="java.util.HashMap"` produced. The original README prints the maps in Java 7 `HashMap` order (`{item name=aa, membership=free, item price per unit=1, item units=10}`). On JDK 8 and later, the JDK a Mule 3.8 runtime runs on and the JDK 17 this project runs on, a `HashMap` holding these keys iterates `item price per unit`, `membership`, `item name`, `item units`, `email`, the order the table shows. The ported original test asserts by substring, so both orders satisfy it. Replacing the map with a `LinkedHashMap` would change the bodies.
- The validator checks in the original order and stops at the first failure: `email` key present, then `item units`, then `item price per unit`. An order without `email` therefore answers "Missing input data" whatever its item values. A numeric string such as `"10"` is accepted, because the validator parses each value's `toString()`.
- Methods: `HEAD` gets the headers of the reply without its body; `OPTIONS` and `TRACE` run the flow like any other method instead of Spring's `Allow` reply and the servlet's `message/http` echo. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur. A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- No request is answered with 406 or 415: the mapping declares neither `produces` nor `consumes`, and every reply presets its `Content-Type` or writes none.
- Send the JSON with `Content-Type: application/json`. `curl --data-binary` without `-H` sends `application/x-www-form-urlencoded`; the Mule 3.8 listener decoded such a body into a parameter map instead of passing the bytes to the JSON transformer (`src/test/resources/fixtures/SCENARIOS.md`, notes), and here the body Spring rebuilds from the form parameters is not JSON, so the reply is the 500 `{ "message": "Payload is not valid JSON" }`.
- Each catch branch logs one ERROR line naming the branch and the exception's type, throw site and cause types, for example `Catch Invalid Data: java.lang.NumberFormatException at java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)`. No exception message, stack trace or payload value is logged, because the validator's messages quote the caller's order values. The 500 path logs SC-1's one ERROR line, naming the method, the matched route and the exception types.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Tier 2A verification points

The source does not settle these platform behaviours. When a captured fixture shows different bytes, the fixture governs and the code is changed to match (AAP 0.4.7, 0.8.4):

1. `Content-Type` of the string replies. The success and filter-rejection replies send none, because the flow's `set-payload` declares no MIME type and the Mule 3.8 listener derived none for such a payload. The two catch branches send `text/plain;charset=UTF-8` (`ValidationController.RESPONSE_CONTENT_TYPE`), although their `set-payload` is equally untyped.
2. The success reason phrase, `OK` here: the response builder's `reasonPhrase="#[flowVars['reason']]"` evaluates to `null` when no catch branch ran, and Undertow then sends the standard phrase of the status code.
3. The 500 for unparseable input: its status, its JSON body (the plan default of AAP 0.4.7) and its message. The messages are fixed texts (`JSON payload is not an object`, `Payload is not valid JSON`) instead of the parser's diagnostic, which can quote request bytes and name internal types (`service/InputValidationService`).
4. The 404 text `No listener for endpoint: <path>` and its `text/plain` type.
5. The `HashMap` key order of the error bodies, should the original runtime have run on JDK 7.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original reads it at `choice-error-handling.xml:3`; its `mule-app.properties` is empty (0 bytes), so the value came from the deployment, and its README tells users to set a port such as 8081. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`choice-error-handling.xml:3`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`choice-error-handling.xml:3`) |
| `spring.application.name` | `String` | `implementing-a-choice-exception-strategy-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `ValidationController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation` | `LogLevel` | `INFO` | none | fixed | the flow has no logger, so no caller data is logged: at DEBUG or TRACE this category writes request and response bodies and the handled exception's message, so a root DEBUG or TRACE override must not reach it |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | at TRACE this category writes handler arguments, the handled exception and its message included |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Original-to-new key map: `http.port` → `http.port` (unchanged). The original has no other placeholder.
- Override the port on the command line, as a system property, or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  java -Dhttp.port=9090 -jar target/implementing-a-choice-exception-strategy-java-1.0.0-SNAPSHOT.jar
  HTTP_PORT=9090 java -jar target/implementing-a-choice-exception-strategy-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`implementing-a-choice-exception-strategy`) | Java construct (`com.mulesoft.examples.implementing_a_choice_exception_strategy`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`choice-error-handling.xml:3`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods`, with `http:response-builder statusCode="#[flowVars['statusCode']]" reasonPhrase="#[flowVars['reason']]"` (`choice-error-handling.xml:5-7`) | `controller/ValidationController`: `handle` (`@RequestMapping("/")`, every method but `OPTIONS`) and `handleOptions` (`OPTIONS`), which only bind the raw body and write the service result; SC-2 `config/ReasonPhrase`, which writes the branch's reason phrase on the Undertow exchange (Tomcat 10.1 would drop it, AAP 0.4.9) |
| `json:json-to-object-transformer returnClass="java.util.HashMap"` (`choice-error-handling.xml:8`) | `service/InputValidationService.toPayload`, Jackson into a real `java.util.HashMap` |
| `custom-filter class="org.mule.examples.InputDataValidator"` (`choice-error-handling.xml:9`) | `service/InputDataValidator`, ported: the Mule `Filter` interface is removed and `accept(MuleMessage)` becomes `accept(Map<String, Object>)` with the same body |
| `set-variable variableName="order"`, `set-payload #[payload['email']]`, `regex-filter`, `set-payload "Input data validation passed."` (`choice-error-handling.xml:10-14`) | `service/InputValidationService.validate`, in statement order; the `order` variable is kept although nothing reads it, as in the original |
| `choice-exception-strategy` with the branches "Catch Invalid Data" (`exception.causedBy(java.lang.IllegalArgumentException)`) and "Catch Missing Data" (`exception.causedBy(java.lang.NullPointerException)`), each with its two constant DataWeave scripts (`"400"`, `"Invalid input data"`; `"400"`, `"Missing input data"`) and its `set-payload "<reason>: #[payload]"` (`choice-error-handling.xml:15-47`) | `exception/ChoiceExceptionHandler`: one `@ExceptionHandler` per branch in declaration order (`handleInvalidInputData`, `handleMissingInputData`), the four scripts as the constants `INVALID_DATA_STATUS_CODE`, `INVALID_DATA_REASON`, `MISSING_DATA_STATUS_CODE`, `MISSING_DATA_REASON` (AAP 0.5.8), scoped to `ValidationController` and ordered at `@Order(0)`, ahead of SC-1. The payload the branches render is the map `ValidationController` stores under `ChoiceExceptionHandler.PAYLOAD_ATTRIBUTE` before validating |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` (`@Order(Ordered.LOWEST_PRECEDENCE)`) and `exception/ErrorResponse` |
| Listener semantics: no CORS policy | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `choice-error-handling.xml`) | `ImplementingAChoiceExceptionStrategyApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `ImplementingChoiceExceptionStrategyIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `ImplementingChoiceExceptionStrategyTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

`config/ReasonPhrase` is the SC-2 reference implementation (AAP 0.4.4). `exposing-a-restful-resource-using-the-HTTP-connector-java` and `http-multipart-request-java` carry copies of it and of `config/ReasonPhraseTest` that differ only in the `package` line; the register is in the root `DECISIONS.md`, section 4. The class depends on the JDK, SLF4J and Undertow alone, keeps the phrase in attachments of the current exchange, and does nothing outside an Undertow servlet request (MockMvc, a plain unit call).

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. Studio metadata (`catalog/`, `mule-project.xml`) and the original's `src/test/resources/log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/implementing-a-choice-exception-strategy-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/implementing-a-choice-exception-strategy-java-1.0.0-SNAPSHOT.jar
```

Expected exchanges (the `Date` values vary):

```console
$ curl -i -X POST -H 'Content-Type: application/json' --data-binary @src/test/resources/message.json http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 29
Date: <current date>

Input data validation passed.

$ curl -i -X POST -H 'Content-Type: application/json' --data-binary @src/test/resources/message_wrong.json http://localhost:8081/
HTTP/1.1 400 Missing input data
Connection: keep-alive
Content-Type: text/plain;charset=UTF-8
Content-Length: 89
Date: <current date>

Missing input data: {item price per unit=1, membership=free, item name=aa, item units=10}

$ curl -i -X POST -H 'Content-Type: application/json' --data-binary '{"email": "aaa@aaa.aa", "item name": "aa", "item units": 10, "item price per unit": -1, "membership": "free"}' http://localhost:8081/
HTTP/1.1 400 Invalid input data
Connection: keep-alive
Content-Type: text/plain;charset=UTF-8
Content-Length: 108
Date: <current date>

Invalid input data: {item price per unit=-1, membership=free, item name=aa, item units=10, email=aaa@aaa.aa}
```

- `--data-binary @<file>` sends the file's bytes unchanged; `-d @<file>` would strip its line breaks. The `Content-Type: application/json` header is required (see [Endpoints and triggers](#endpoints-and-triggers)).
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project and calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ImplementingChoiceExceptionStrategyTest` | the ported `ImplementingChoiceExceptionStrategyIT` (`implementing-a-choice-exception-strategy/src/test/java/org/mule/examples/ImplementingChoiceExceptionStrategyIT.java:58-75`), with its comparison semantics: `POST /` with `message.json` returns a non-empty body equal to `Input data validation passed.`; `POST /` with `message_wrong.json` returns status `400` and a body containing `Missing input data:`, `item price per unit=1`, `membership=free`, `item name=aa` and `item units=10` |
| `controller/ValidationControllerTest` | on the wire through ST-1 `support/HttpTestClient`, which reads the raw status line: the success reply (`HTTP/1.1 200 OK`, no `Content-Type`, `Content-Length: 29`) for `POST`, `GET` and `PUT`; `HTTP/1.1 400 Missing input data` for `message_wrong.json` and a missing `item units`; `HTTP/1.1 400 Invalid input data` for a negative price, zero units and decimal units, each with `text/plain;charset=UTF-8` and the exact body; the empty 200 for a rejected and a `null` email; the JSON 500 for an empty body and a JSON array; the 404 for `/unknown` |
| `service/InputValidationServiceTest` | `toPayload`: `message.json` becomes a `HashMap` equal to the order; unreadable bodies raise `UncheckedIOException` with the fixed messages, quoting no request bytes or type names, and JSON `null` a `ClassCastException`; `validate`: accepted and rejected emails, validator exceptions propagating unwrapped, the validator receiving the same payload instance |
| `service/InputDataValidatorTest` | the ported validator's contract: `email` checked first, `NullPointerException` for a missing `email`, `item units` or `item price per unit`, `IllegalArgumentException` for zero units or a negative price, `NumberFormatException` for decimal or non-numeric values, numeric strings and the boundary values 1 unit and price 0 accepted |
| `exception/ChoiceExceptionHandlerTest` | through MockMvc with a mocked service: each branch's status, `Content-Type` and body bytes for `IllegalArgumentException`, `NumberFormatException` and `NullPointerException`; an exception neither branch names reaching the SC-1 500; the four constants equal the original scripts; the ERROR line's exact form, free of messages, payload values and throwables; `application.yml` holding the payload-writing Spring MVC categories at INFO |
| `config/ReasonPhraseTest` | SC-2 on Undertow: a custom phrase on the raw status line, with an error and with a success status; no leak into the next exchange; `null` restoring the standard phrase; a no-op outside a request |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR line per unhandled exception that names the matched route, never the request path, and no exception message except a JDBC driver's bounded diagnostic; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight carrying `message.json` gets the plain `OPTIONS` reply (`200 Input data validation passed.`, no `Content-Type`), one carrying `message_wrong.json` the `400 Missing input data` branch, neither with `Access-Control-*` or `Vary` headers |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes; it and `FixtureParityTest` are copied from `hello-world-java` with only the `package` line changed.

Coverage gate: JaCoCo fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80% (report in `target/site/jacoco`). The project has no `mapper` package; the include is kept so the gate reads the same in every converted project.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/implementing_a_choice_exception_strategy/FixtureParityTest.java` is the shared replay engine. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds the fixture contract, the comparison rules and the Tier 2A capture procedure; the root `DECISIONS.md`, section 9, records the Tier 2A state and its escalation. Fixtures are captured from the running original, never hand-written, and never edited to match the Java output: a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenarios, exactly as `SCENARIOS.md` names them, all `deterministic` (every fixture's `state` is `{}` and `upstream` `[]`, because the flow reaches no store and makes no outbound call):

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-valid` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `message.json` | `UNVERIFIED-uncaptured` |
| `get-root-valid` | `http` | `deterministic` | `GET /`, header `Content-Type: application/json`, body `message.json` | `UNVERIFIED-uncaptured` |
| `post-root-missing-email` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `message_wrong.json` | `UNVERIFIED-uncaptured` |
| `post-root-invalid-item-units` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, the valid order with `item units` `0` | `UNVERIFIED-uncaptured` |
| `post-root-negative-price` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, the valid order with `item price per unit` `-1` | `UNVERIFIED-uncaptured` |
| `post-root-invalid-email` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, the valid order with `email` `not-an-email` | `UNVERIFIED-uncaptured` |
| `post-root-empty-body` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| implementing-a-choice-exception-strategy-java | implementing-a-choice-exception-strategy | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 180 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 180 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 34 of 34 lines of `service/InputValidationService` (with its `TypeReference`) and `service/InputDataValidator` (the project has no `mapper` package). Total project LINE coverage: 97.8% (306 of 313 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=8`, `failed=0`.

Tier 2A is blocked: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `ImplementingChoiceExceptionStrategyTest` carries the original committed assertions: the exact success reply for `message.json`, and for `message_wrong.json` the status `400` and five substrings of the "Missing input data" body.
- `ValidationControllerTest`, `InputValidationServiceTest`, `InputDataValidatorTest` and `ChoiceExceptionHandlerTest` cover every reachable branch, on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the flow and the platform defaults, but not that its bytes equal what the original runtime sent.
- No original assertion covers: the "Invalid input data" branch (`post-root-invalid-item-units`, `post-root-negative-price`), the email `regex-filter` rejection (`post-root-invalid-email`), any-method acceptance (`get-root-valid`), the 500 for unparseable input (`post-root-empty-body`), the no-listener 404 (`get-unknown-path`), and, on the branch it does cover, the exact reason phrases, `Content-Type` and full error bodies: the original test checks only `http.status` `400` and substrings. These are covered only by the new tests.
- Unverified scenarios, until captured: `post-root-valid`, `get-root-valid`, `post-root-missing-email`, `post-root-invalid-item-units`, `post-root-negative-price`, `post-root-invalid-email`, `post-root-empty-body`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `implementing-a-choice-exception-strategy/`.
