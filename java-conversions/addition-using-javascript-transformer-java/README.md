# Addition using JavaScript Transformer — Spring Boot conversion

`addition-using-javascript-transformer-java` converts the Mule EE 3.8 example [`addition-using-javascript-transformer`](../../addition-using-javascript-transformer/) (category General) to Java 17 / Spring Boot 3.2.12 / Maven, package `com.mulesoft.examples.addition_using_javascript_transformer`. It reproduces the original's behaviour, with the one intentional difference described under [Intentional difference](#intentional-difference). The original folder `addition-using-javascript-transformer/` stays unchanged beside this project as the reference and the Tier 2A capture source.

A client POSTs a JSON message holding numbers to `/`. The flow converts the body to a string, a JavaScript transformer adds up every value of the parsed object, the outcome is logged, and the response is `Sum is: <sum>.`. With the original README's example body `{ "a": 3, "b": 4 }` the reply is `Sum is: 7.0.` and the log line is `Sum is: 7.0` (`addition-using-javascript-transformer/README.md:22-30`). The single flow `javascript-calculatorFlow1` (`addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:4-16`) declares no exception strategy and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/` | `POST`, in any letter case (`post` and `Post` too) | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body `Sum is: <sum>.` as UTF-8 bytes, `Content-Length`, no `Content-Type` header | `javascript-calculator.xml:3-15` |
| `/` | `POST` with an `application/x-www-form-urlencoded` body holding a malformed percent escape, such as `a=%zz` | `0.0.0.0` | `http.port` | `400 Bad Request`, body `Cannot decode x-www-form-urlencoded payload`, no `Content-Type`; the flow does not run | the Mule 3.8 listener's own rejection before the flow |
| `/` | every other method: `GET`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS` and `TRACE`, whatever the body | `0.0.0.0` | `http.port` | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /`, no `Allow` header; a `HEAD` reply has no body (SC-1, AAP 0.4.7) | `javascript-calculator.xml:5` (`allowedMethods="POST"`) |
| any other path (`/other`, `/error` and `/favicon.ico` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/`, when the body cannot be summed: malformed, empty, or JavaScript code (see [Intentional difference](#intentional-difference)) | `POST` | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<failure message>" }`, one ERROR log line (SC-1, AAP 0.4.7) | platform default for an unhandled exception; the flow has no exception strategy |

- The listener accepted any `Content-Type` and ignored `Accept`, so no request is answered with 415 or 406. The body is decoded with the charset its `Content-Type` declares, and with UTF-8, the application encoding of `mule-deploy.properties`, when it declares none or an unknown one.
- The `200` reply carries no `Content-Type`, because the flow's `set-payload` declares no MIME type and the Mule listener sent none for such a payload. `CalculatorController` writes the bytes straight to the servlet response, so no message converter adds one.
- Form bodies: the Mule 3.8 listener turned a non-empty `application/x-www-form-urlencoded` body into a parameter map, and `byte-array-to-string-transformer` rendered that map as `{name=value, ...}`, which the script cannot evaluate. The conversion does the same. `curl -d` sends that type when no `Content-Type` is given, so `{ "a" : 1, "b": 2 }` posted that way is rendered `{{ "a" : 1, "b": 2 }=null}` and answered with the 500 `SyntaxError: invalid property id (at offset 1)`. Send JSON with `Content-Type: application/json` or `text/plain`. A form body made only of separators, such as `&`, renders as `{}` and returns `Sum is: 0.0.`.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, with the 405 and no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Mule-to-Java mapping

| Mule construct (`javascript-calculator.xml`) | Java construct (`com.mulesoft.examples.addition_using_javascript_transformer`) |
|---|---|
| `http:listener-config host="0.0.0.0" port="${http.port}"` (line 3) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded), `server.address` and `server.port` from `application.yml` |
| `http:listener path="/" allowedMethods="POST"` (line 5) | `controller/CalculatorController`: `calculate`, mapped to `/` for every method, admits `POST` in any letter case and rejects the rest with the 405; `options` rejects `OPTIONS`, which Spring would otherwise answer itself |
| `byte-array-to-string-transformer` (line 6) | the controller reads the raw body and its declared charset and calls `service/AdditionService.handle(byte[], Charset)`; a non-empty form body goes to `AdditionService.handle(Map)` as the listener's parameter map |
| `scripting:transformer` with the JavaScript `var list = eval("(" + payload + ")"); var sum = 0; for (var i in list) sum += list[i]; payload = sum;` (lines 7-13) | `AdditionService.sum(String)`, a plain-Java reimplementation; no script engine runs and the body is never evaluated |
| `logger level="INFO" message="Sum is: #[payload]"` (line 14) | SLF4J INFO `Sum is: <sum>` on the `AdditionService` logger, after the sum and before the reply |
| `set-payload value="Sum is: #[payload]."` (line 15) | the string `AdditionService.handle` returns, written by the controller with status 200 |
| No exception strategy; listener default responses | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| No CORS policy on the listener | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (`config.resources=javascript-calculator.xml`, `encoding=UTF-8`) | `AdditionUsingJavascriptTransformerApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `AdditionUsingJavascriptTransformerIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `AdditionUsingJavascriptTransformerTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

## Reproduced JavaScript semantics

The reference is the engine Mule 3.8.0 ran the script on: Rhino 1.7R4 behind the JSR-223 bridge `javax.script:js-engine:1.1`. `AdditionService` reads the body by Rhino's literal rules and reimplements the `for-in` loop and the `+` operator with Rhino's conversions:

- Numbers are summed as a `Double`, and MEL rendered the result with `Double.toString`: `Sum is: 3.0.`, `Sum is: 0.30000000000000004.`, `Sum is: 1.0E21.`, `Sum is: Infinity.`, `Sum is: NaN.`.
- `for-in` visits an object's own properties in insertion order, then those its `__proto__` adds, and an array's present elements in ascending order, so `[1,2,3]` returns `Sum is: 6.0.`. A duplicate key keeps its first position and its last value: `{"a":1,"a":5,"b":2}` returns `Sum is: 7.0.`.
- A root that is a string such as `"ab"` (Rhino 1.7R4 enumerates no string indices), a number, a boolean, `null` or `undefined` visits nothing, and neither does `{}` or `[]`: each returns `Sum is: 0.0.`.
- `+` converts `true` to 1, `false` and `null` to 0, and `undefined` to `NaN`.
- Once a string is added, the sum becomes JavaScript string concatenation: `{ "a": 1, "b": "x" }` returns `Sum is: 1x.` and `{ "a": "x", "b": 1 }` returns `Sum is: 0x1.`. An array or object value is converted with ToPrimitive (`valueOf`, then `toString`): `{a:[1,,2]}` returns `Sum is: 01,,2.`, and `{"a":{"toString":1}}` fails with `TypeError: Cannot find default value for object.`, as the original did.
- Accepted literal syntax is JSON plus the literal forms JavaScript allows. These are unquoted, keyword and number keys; single-quoted strings with Rhino's escapes; hexadecimal (`0x10`) and legacy octal (`010`) numbers; `NaN`, `Infinity` and `undefined`; leading `+` and `-` signs; array holes and trailing commas; and `//`, `/* */`, `<!--` and line-initial `-->` comments. For example, `{a: 0x10, 'b': 010, c: true, d: null,}` returns `Sum is: 25.0.`.
- A body Rhino rejects fails with a `SyntaxError:` message, for example `{ "a": ` → `SyntaxError: syntax error (at offset 7)`. An empty or blank body fails with `No content to map due to end-of-input`. Arrays and objects nested more than 1000 levels deep fail, and so does a string longer than a Java string can hold.
- The expected values in `service/AdditionServiceLiteralParityTest` were produced by running the original script on Rhino 1.7R4 with `javax.script:js-engine:1.1`, the versions Mule 3.8.0 pins.

## Intentional difference

The original runs `eval("(" + payload + ")")`, which executes whatever JavaScript the caller sends. The conversion runs no script engine and never evaluates the request body (AAP 0.3.2: scripts are reimplemented in Java). It reads the body as literal data instead.

- Every body of literal data, which includes every JSON body, gets the original's reply. A body Rhino rejects fails as it did there.
- A body that only works when evaluated as code gets the 500 where the original computed a result. Such code includes identifier references other than `undefined`, `NaN` and `Infinity`; function, getter and setter literals; regular expressions; E4X; grouping; operators other than leading signs; member access; calls; the comma operator; and further statements. For example, `{ "a": 1 + 2 }` is answered with `{ "message": "Unsupported input at offset 9: the operator '+' is JavaScript code, and the request body is read as literal data, never evaluated" }`.

The root `DECISIONS.md` records this difference in section 8, beside query hardening (AAP 0.4.10), as an intentional difference for hostile input.

## Tier 2A verification points

The source does not settle these platform behaviours. When a captured fixture shows different bytes, the fixture governs and the code is changed to match:

- the 500 body for a failed evaluation, whose message is `AdditionService`'s rather than Rhino's or the Mule default exception strategy's;
- the absent `Content-Type` of the `200` reply;
- `Sum is: 0.0.` for an empty object, an empty array and a primitive root;
- the concatenation results for string values, such as `Sum is: 1x.`;
- the 404 and 405 texts and their `text/plain` type;
- the form-body handling: the rendering of the parameter map and the 400 for an undecodable body;
- the 405 for `OPTIONS` and for a CORS pre-flight.

## Configuration keys

| Key | Default | Original name | Notes |
|---|---|---|---|
| `http.port` | `8081` | `http.port` (unchanged) | bound to `server.port` (`server.port: ${http.port:8081}`). The original reads it at `javascript-calculator.xml:3`; its `mule-app.properties` is empty, so the value came from the deployment, and its README tells users to set port 8081. |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder and nothing is renamed. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  HTTP_PORT=9090 java -jar target/addition-using-javascript-transformer-java-1.0.0-SNAPSHOT.jar
  ```

Fixed settings in `src/main/resources/application.yml`, none of them a configuration key of the original:

| Setting | Value | Why |
|---|---|---|
| `server.address` | `0.0.0.0` | `http:listener-config host="0.0.0.0"` (`javascript-calculator.xml:3`) |
| `spring.application.name` | `addition-using-javascript-transformer-java` | names the application in its log lines |
| `spring.web.resources.add-mappings` | `false` | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `true` | `TRACE` reaches `CalculatorController` and gets the 405, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `false` | the listener checked the method before reading the body, so a form body on `PUT`, `PATCH` or `DELETE`, even one that cannot be decoded, reaches the controller and gets the 405 |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

## Behaviours stubbed or externalized

Nothing is stubbed or externalized. The original reaches no external system and holds no credential, so nothing is mocked at runtime or moved to configuration. The JavaScript engine is replaced by Java code (see [Intentional difference](#intentional-difference)). Studio metadata (`mule-project.xml`) and the original's `src/test/resources/log4j2-test.xml` are not carried (AAP 0.2.2); logging uses Spring Boot's default Logback setup.

## Build, run and test

Run from `java-conversions/addition-using-javascript-transformer-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8081
java -jar target/addition-using-javascript-transformer-java-1.0.0-SNAPSHOT.jar
```

Expected exchange (the `Date` value varies):

```console
$ curl -i -X POST -H 'Content-Type: application/json' -d '{ "a" : 1, "b": 2 }' http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 12
Date: <current date>

Sum is: 3.0.
```

The same request with the original README's body `{ "a": 3, "b": 4 }` returns `Sum is: 7.0.`, and the application logs `Sum is: 7.0` at INFO. Leave out `-H 'Content-Type: application/json'` and curl sends the body as a form, which is answered with the 500 (see [Endpoints and triggers](#endpoints-and-triggers)).

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `AdditionUsingJavascriptTransformerTest` | the ported `AdditionUsingJavascriptTransformerIT` (`addition-using-javascript-transformer/src/test/java/org/mule/examples/AdditionUsingJavascriptTransformerIT.java:29,47-50`): `POST /` with `{ "a" : 1, "b": 2 }` returns a response with a non-empty body equal to `Sum is: 3.0.` |
| `controller/CalculatorControllerTest` | on the wire through ST-1, each scenario of `SCENARIOS.md` with its request: `200 OK`, no `Content-Type`, exact `Content-Length` and body for `Sum is: 3.0.`, `7.0.`, `0.0.` and `1x.`, sent as `application/json` and as `text/plain` (`post-root-sum` also without a `Content-Type`); `post` and `Post` accepted; the declared charset (ISO-8859-1, none, unknown); `Accept` ignored; form bodies (`&` → `Sum is: 0.0.`, `a=%zz` → the listener's 400, a JSON body sent as a form → 500); the 405 for `GET`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`, `TRACE` and `HEAD`, and for `GET`, `PUT`, `PATCH` and `DELETE` carrying a JSON or an undecodable form body; the 404 for `/other`, `/error` and `/favicon.ico`; the 500 shape for a malformed, an empty and a missing body |
| `service/AdditionServiceTest` | the result types of `sum` (`Double` or `String`), numeric sums and their rendering, concatenation, the `render` and `jsToString` conversions, the parameter-map rendering of form bodies, malformed bodies, and the INFO log line `Sum is: <sum>` |
| `service/AdditionServiceLiteralParityTest` | the corpus of literal forms and failures, each expected reply or error text produced by running the original script on Rhino 1.7R4; bodies of code, which the original evaluated, rejected; the nesting and string-length bounds; charset decoding and empty bodies |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception, with no message or stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` gets exactly the plain `OPTIONS` reply (the 405 `Method not allowed for endpoint: /`, no `Access-Control-*` or `Vary`), whatever method it asks for, and the flow is never called |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, with an HTTPS mode for TLS listeners.

Coverage gate: JaCoCo fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80%. The project has no `mapper` package; the include is kept so the gate reads the same in every converted project.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/addition_using_javascript_transformer/FixtureParityTest.java` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds the fixture contract and the capture procedure.

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, each a JSON object with the sections `request`, `state`, `upstream` and `response` (AAP 0.8.4). `state` is `{}` and `upstream` is `[]` for every scenario of this project, because the original reaches no store and makes no outbound call.
- Tier 2A capture: build the unmodified original with `mvn -B package` and the EE repository credentials, deploy it to a licensed Mule 3.8 EE runtime with `http.port=8081`, and send each scenario's request with `curl --raw -i`. The raw output is committed unedited as evidence under `src/test/capture/<scenario>/`, and the fixture is written from it. The exact commands are in `SCENARIOS.md`, section Capture.
- Fixtures are never hand-written, and never edited to match the Java output. A mismatch is fixed in the Java code: in SC-1 `GlobalExceptionHandler` for the platform defaults, and in `CalculatorController` or `AdditionService` for the flow's replies.
- Replay sends each captured request through ST-1 to the application on a random port and compares status, reason phrase, `Content-Type`, `Location` and body byte for byte. WireMock runs with no stubs, so any outbound call fails the scenario.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed. Each run writes `target/fixture-parity/summary.properties`.

This project's scenarios, exactly as `SCENARIOS.md` names them, all `deterministic`:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-sum` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{ "a" : 1, "b": 2 }` | `UNVERIFIED-uncaptured` |
| `post-root-readme-example` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{ "a": 3, "b": 4 }` | `UNVERIFIED-uncaptured` |
| `post-root-empty-object` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{}` | `UNVERIFIED-uncaptured` |
| `post-root-string-value` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{ "a": 1, "b": "x" }` | `UNVERIFIED-uncaptured` |
| `post-root-malformed-body` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{ "a": ` (ending with one space) | `UNVERIFIED-uncaptured` |
| `get-root-method-not-allowed` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-unknown-path` | `http` | `deterministic` | `POST /other`, body `{ "a" : 1, "b": 2 }` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| addition-using-javascript-transformer-java | addition-using-javascript-transformer | General | PASS | PASS | 99.9% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 635 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 635 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 893 of 894 lines of `service/AdditionService` and its nested classes (the project has no `mapper` package). Total project LINE coverage: 99.1% (1102 of 1112 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=7`, `failed=0`.

Tier 2A is blocked: the Mule EE licence and the EE Maven repository credentials are unconfirmed (root `DECISIONS.md`, section 9). Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `AdditionUsingJavascriptTransformerTest` carries the original committed assertion: `POST /` with `{ "a" : 1, "b": 2 }` returns `Sum is: 3.0.`.
- `CalculatorControllerTest` covers every reachable branch on the wire, and `AdditionServiceTest` and `AdditionServiceLiteralParityTest` cover the script's semantics.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and reproduces the semantics the script has on Rhino 1.7R4, but not that its bytes equal what the original runtime sent.
- The only branch an original assertion covers is the numeric sum (`post-root-sum`; `post-root-readme-example` follows the original README). Five branches are covered only by new tests: the empty object (`post-root-empty-object`), string concatenation (`post-root-string-value`), the malformed body (`post-root-malformed-body`), the 405 (`get-root-method-not-allowed`) and the 404 (`post-unknown-path`).
- Unverified scenarios, until captured: `post-root-sum`, `post-root-readme-example`, `post-root-empty-object`, `post-root-string-value`, `post-root-malformed-body`, `get-root-method-not-allowed`, `post-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `addition-using-javascript-transformer/`.
