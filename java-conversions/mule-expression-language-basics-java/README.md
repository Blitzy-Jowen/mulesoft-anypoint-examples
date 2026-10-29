# mule-expression-language-basics-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`mule-expression-language-basics`](../../mule-expression-language-basics/) (category General). The original folder `mule-expression-language-basics/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The example's six small flows introduce basic Mule Expression Language (MEL) use: reading inbound properties, routing with a choice router, setting flow variables, building a map literal and evaluating a condition in DataWeave, querying XML with XPath, and reading Java objects parsed from JSON. All six live in `mule-expression-language-basics/src/main/app/greeting.xml`, the only flow file the application loads (`config.resources=greeting.xml` in `mule-deploy.properties`). Each flow is triggered by its own HTTP listener, `/greet1` to `/greet6`, and four of them write a file to the directory `Path_of_your_choice`. No flow declares a logger or an exception strategy, and none calls another system.

Every MEL expression and the example's one DataWeave script (`greeting.xml:47`) are reimplemented by hand in plain Java. No MEL/MVEL engine and no DataWeave runtime is used.

## Endpoints and triggers

All six listeners share `HTTP_Listener_Configuration` (`greeting.xml:4`): host `0.0.0.0`, port `http.port`, default `8081`. No listener declares `allowedMethods`, so every path accepts every method.

| Path | Flow | Input | Response body (`200 OK`) | File written to `greeting.output-dir` | Source |
|---|---|---|---|---|---|
| `/greet1` | `greetingFlow1` | query `username` | `Hello <username>`; `Hello null` when `username` is absent | none | `greeting.xml:7-11` |
| `/greet2` | `greetingFlow2` | query `username` | `No username provided` when `username` is absent or empty (`?username=`); otherwise `Hello <username>`, a whitespace-only value included, because MEL `empty` does not trim | none | `greeting.xml:15-26` |
| `/greet3` | `docs-greetingFlow3` | query `username`, `age` | `Hello <username>` | `<username>, <age>, <age > 18>` with no line end, e.g. `Mule, 1, false` | `greeting.xml:30-37` |
| `/greet4` | `docs-greetingFlow4` | query `username`, `age` | `Hello <username>` | the header-less DataWeave CSV line `username,age,age_of` followed by one LF, e.g. `Mule,1,false` + LF | `greeting.xml:41-60` |
| `/greet5` | `docs-greetingFlow5` | XML body `<user><username>…</username><age>…</age></user>` | `Hello <username>` | `<username>, <age>, <age > 18>` read by XPath `/user/username` and `/user/age`, with no line end | `greeting.xml:64-71` |
| `/greet6` | `greetingFlow6` | JSON object body `{"username": …, "age": …}` | `Hello <username>` | `<username>, <age>, <age > 18>` read from the parsed object, with no line end | `greeting.xml:75-83` |

- Replies: `200 OK` with the reply's UTF-8 bytes and their `Content-Length`, and no `Content-Type` header. Each flow ends in a `set-payload` that declares no MIME type, and the Mule listener sent no `Content-Type` for such a payload. `controller/GreetingController` writes the bytes straight to the servlet response, so no message converter adds one and `Accept` is never consulted.
- Methods: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS` and `TRACE` all run the flow. A `HEAD` reply carries the headers without the body. `OPTIONS` is mapped explicitly, so Spring MVC does not answer it itself. `TRACE` reaches the controller because `spring.mvc.dispatch-trace-request` is `true`. A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, flow and file write included, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- File writes: each successful call to `/greet3` … `/greet6` creates exactly one new file in `greeting.output-dir`, creating the directory first when needed. The file is named `<uuid>.dat`, Mule's default name (`#[function:uuid].dat`) when a `file:outbound-endpoint` sets no `outputPattern`. Content is UTF-8. The file content is computed in full before the write, so a failed call writes no file.
- Request bodies: `/greet5` and `/greet6` read the body as bytes whatever its `Content-Type`, the header absent included; the XML encoding is detected from the document. An empty body, or a body sent as `application/x-www-form-urlencoded` (which the Mule listener turned into a form-parameter map the flows cannot read), fails the flow with a 500 and writes no file. `curl --data` sends `application/x-www-form-urlencoded` unless told otherwise, so pass `-H 'Content-Type: …'` as in the examples below. The other four flows never read the body.
- Unknown path: any path other than the six (`/greet1/`, `/greet7`, `/` and `/error` included) gets `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7). SC-1's 405 cannot occur, because every listener accepts every method.
- A listener path carrying `;` segment parameters (`/greet3;x=1?username=Mule&age=1`, `/greet3;jsessionid=ABC`) is not that listener's path: it gets the no-listener 404 naming the path with its parameters and without the query, `No listener for endpoint: /greet3;x=1`, no flow runs and no file is written. The Mule listener matched the raw path, query removed, literally, where Spring MVC ignores segment parameters (`config/ListenerPathConfig`). A `;` in the query string stays part of the value: `/greet1?username=a;b` returns `Hello a;b`.
- Unhandled failure: `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7), and no file is written. The failures are:

  | Request | Body of the 500 |
  |---|---|
  | `/greet3` with a non-numeric `age` (`age=abc`) or an empty one (`age=`) | `{ "message": "uncomparable values <<abc>> and <<18>>" }`, `<<>>` for the empty value |
  | `/greet5` with malformed XML | the XML parser's message, e.g. `{ "message": "XML document structures must start and end within the same entity." }` |
  | `/greet5` whose `/user/username` or `/user/age` selects no element or several | `{ "message": "xpath('/user/username') selected 0 nodes" }` |
  | `/greet5` with an empty or form-urlencoded body | `{ "message": "greet5 requires an XML body" }` |
  | `/greet6` with malformed JSON | Jackson's message, e.g. `Unexpected end-of-input within/between Object entries …` |
  | `/greet6` whose body is valid JSON but not an object (`[1]`) | `{ "message": "greet6 requires a JSON object" }` |
  | `/greet6` with an empty or form-urlencoded body | `{ "message": "greet6 requires a JSON body" }` |

- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

Examples, adapted from the original README (`mule-expression-language-basics/README.md:19-48`), with the default port:

```sh
curl "http://localhost:8081/greet1?username=yourName"                 # Hello yourName
curl "http://localhost:8081/greet2"                                   # No username provided
curl "http://localhost:8081/greet3?username=yourName&age=22"          # Hello yourName; file: yourName, 22, true
curl "http://localhost:8081/greet4?username=yourName&age=22"          # Hello yourName; file: yourName,22,true + LF
curl -X POST -H 'Content-Type: application/xml' --data '<user><username>test</username><age>21</age></user>' http://localhost:8081/greet5
                                                                      # Hello test; file: test, 21, true
curl -X POST -H 'Content-Type: application/json' --data '{"username":"test","age":21}' http://localhost:8081/greet6
                                                                      # Hello test; file: test, 21, true
```

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original, and changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `${http.port}` of `http:listener-config` (`greeting.xml:4`) had no value there; its README tells users to set port 8081 (`mule-expression-language-basics/README.md:15`). |
| `greeting.output-dir` | `String` (a path, absolute or relative to the working directory) | `Path_of_your_choice` | none: the original hard-codes `path="Path_of_your_choice"` on its four `file:outbound-endpoint`s (`greeting.xml:35,57,69,81`) | default | directory of the `/greet3` … `/greet6` files, read by `service/GreetingService` |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the listener port, resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`greeting.xml:4`) |
| `spring.application.name` | `String` | `mule-expression-language-basics-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` runs the flow like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | its `DEBUG` and `TRACE` write the request line with its `?username=<name>&age=<age>` query string. `WARN`, not `INFO`: its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | its `TRACE` writes handler arguments, among them the failure SC-1 handles, whose message quotes a non-numeric age or the text the XML or JSON parser quotes from a posted body |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` writes the handled exception's message: the non-numeric age, or the posted body text the parser quotes |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write the response body: SC-1's `500` JSON carrying that message |
| `logging.level.io.undertow.request.io` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` writes request-parse failures quoting a query value that does not decode: the caller's name or age |

- The five `logging.level` keys keep the callers' names, ages and posted bodies out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`: a logger's own level outranks them. The original declares no logger, so it logged none of them. `config/SensitiveLogLevelsTest` asserts this.
- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- A relative `greeting.output-dir` resolves against the process's working directory, as Mule resolved the relative `path`. Run from this directory with the default, the files land in `java-conversions/mule-expression-language-basics-java/Path_of_your_choice/`, which git does not ignore; point the key under `target/` or elsewhere to keep the checkout clean.
- Override either key on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port` and `GREETING_OUTPUT_DIR` to `greeting.output-dir`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments="--http.port=9090 --greeting.output-dir=target/greetings"
  HTTP_PORT=9090 GREETING_OUTPUT_DIR=/var/tmp/greetings java -jar target/mule-expression-language-basics-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`mule-expression-language-basics`) | Java construct (`com.mulesoft.examples.mule_expression_language_basics`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`greeting.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| six `http:listener`s `greet1` … `greet6` without `allowedMethods` (`greeting.xml:8,16,31,42,65,76`) | `controller/GreetingController`: `@RequestMapping("/greetN")` methods `greet1` … `greet6` (every method but `OPTIONS`) and `greet1Options` … `greet6Options` (`OPTIONS`), each pair writing the same reply |
| flows `greetingFlow1`, `greetingFlow2`, `docs-greetingFlow3`, `docs-greetingFlow4`, `docs-greetingFlow5`, `greetingFlow6` | `service/GreetingService` methods `greet1(String)`, `greet2(String)`, `greet3(String, String)`, `greet4(String, String)`, `greet5(byte[])`, `greet6(byte[])`, one per flow |
| `message.inboundProperties['http.query.params']` | `GreetingController.queryParams`, rebuilt from the raw query string |
| `set-payload` MEL templates and `expression-component` `flowVars.username = …` | plain Java in `GreetingService`: string concatenation and a local variable |
| `choice` / `when #[… == empty]` / `otherwise` (`greeting.xml:18-25`) | `if` / `else` in `GreetingService.greet2` |
| MEL `age > 18` (`greeting.xml:34,68,80`) | `GreetingService.melGreaterThan18` |
| map literal `['username' : …, 'age' : …]` (`greeting.xml:45`) | a `LinkedHashMap` built in `GreetingService.greet4` |
| `dw:transform-message` "Map to CSV" (`greeting.xml:46-55`, `%dw 1.0` at line 47, `%output application/csv header=false`) | `mapper/GreetingCsvMapper` (record `username`, `age`, `age_of`) writing through SC-4 `mapper/DwCsvWriter` with `header=false` |
| `byte-array-to-string-transformer` (`greeting.xml:58`) | nothing: `GreetingCsvMapper.toCsv` already returns a `String` |
| `file:outbound-endpoint path="Path_of_your_choice"` ×4 (`greeting.xml:35,57,69,81`) | SC-6 `service/FileOutboundWriter`, named by `FileOutboundWriter.defaultFileName()` (`<uuid>.dat`), directory `greeting.output-dir` |
| MEL `xpath('/user/…').text` (`greeting.xml:67-68`) | JDK DOM parser and `javax.xml.xpath` in `GreetingService.greet5` |
| `json:json-to-object-transformer returnClass="java.lang.Object"` (`greeting.xml:78`) | Jackson `ObjectMapper.readValue(bytes, Object.class)` in `GreetingService.greet6`, an object becoming a `LinkedHashMap` |
| default exception strategy (platform 404/500) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| listener semantics (no CORS policy; `TRACE` and form bodies passed to the flow; the raw path, query removed, matched literally with its `;` segment parameters) | `config/PreflightDispatchConfig`; `spring.mvc.dispatch-trace-request`, `spring.mvc.formcontent.filter.enabled`; `config/ListenerPathConfig` |
| `mule-deploy.properties` (loads `greeting.xml`) | `MuleExpressionLanguageBasicsApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `MuleExpressionLanguageBasicsIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `MuleExpressionLanguageBasicsTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

Shared components are copies, identical apart from the `package` line, of their first consumer (AAP 0.4.4), each with its unit test:

| Component | Copied from |
|---|---|
| SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse` (`exception/GlobalExceptionHandlerTest`) | `rest-api-with-apikit-java` |
| ST-1 `support/HttpTestClient` (test) | `rest-api-with-apikit-java` |
| SC-4 `mapper/DwCsvWriter` (`mapper/DwCsvWriterTest`) | `processing-orders-with-dataweave-and-APIkit-java` |
| SC-6 `service/FileOutboundWriter` (`service/FileOutboundWriterTest`) | `processing-orders-with-dataweave-and-APIkit-java` |
| ST-2 `FixtureParityTest` (test) | `hello-world-java` |
| `config/PreflightDispatchConfig` (`config/PreflightDispatchConfigTest`) | `hello-world-java` |
| `config/ListenerPathConfig` (no unit test of its own; asserted on the wire by `controller/GreetingControllerTest` and `config/PreflightDispatchListenerTest`) | `hello-world-java` |

## Preserved MEL and DataWeave semantics

- Query parameters follow the Mule 3.8 `http.query.params` map: the raw query string is split on `&` and each pair on its first `=`; names and values are URL-decoded as UTF-8 (`J%C3%BCrgen+M` → `Jürgen M`); a key without `=` is `null`; a repeated name keeps its last value (`?username=a&username=Mule` greets `Mule`). Form fields of a request body never count as query parameters.
- MEL `== empty` (`/greet2`) is true for `null` and the empty string only. It does not trim, so `?username=%20` returns `Hello` followed by two spaces, and `?username=0` returns `Hello 0`.
- A `null` inside a MEL template renders as `null`: `/greet1` without `username` returns `Hello null`, and `/greet3?username=Mule` writes `Mule, null, false`.
- MEL `> 18` (`/greet3`, `/greet5`, `/greet6`): `null` is not greater; a number is compared numerically; a string that reads as a plain decimal number (optional `-`, digits with at most one `.`) is compared by its value, so `22`, `18.5` and `0022` are greater and `1`, `18` and `18.0` are not; any other string (`abc`, the empty `age=`, ` 22`, `1e3`) fails the expression, which reaches the 500. In `/greet5` the values are the XPath text, in `/greet6` the JSON types (`21` an `Integer`, `"21"` a `String`).
- DataWeave `age > 18` (`/greet4`): `null` is never over 18 (`/greet4?username=Mule` writes `Mule,,false` + LF, a `null` field being empty); a number is compared with 18 numerically; a string, which every query value is, is compared with `"18"` as text, so `age=5` and `age=abc` give `true` while `age=1`, `age=18` and `age=100` give `false`. No input fails this flow.
- XPath (`/greet5`): the body is parsed namespace-aware, so `<user xmlns="urn:x">` selects nothing; `.text` is the element's own text and CDATA without its child elements' text; each expression must select exactly one element.
- JSON (`/greet6`): a missing key reads as `null`; a nested object or array is written with its `toString()`, e.g. `{x=1}`.

## Tier 2A verification points

These follow the AAP's reading of the original where its source does not settle the bytes. A captured fixture governs each, and the code changes to match (AAP 0.4.7, 0.8.4).

1. `/greet4`'s DataWeave comparison of a string `age` with 18, implemented with DataWeave 1.0's coercion of the right operand to the left one's type (text against `"18"`): `age=5` writes `true` and `age=100` writes `false`, where `/greet3`'s MEL comparison gives `false` and `true` (scenarios `get-greet4-age-5`, `get-greet4-age-100`, `get-greet3-age-5`). `mapper/GreetingCsvMapper` owns the rule.
2. `/greet4` without `age` writes `false` as `age_of` (no `SCENARIOS.md` row; asserted by `GreetingCsvMapperTest` and `GreetingServiceTest`).
3. The absent `Content-Type` of every `200` reply, including whether `/greet4`'s DataWeave step left `application/csv` on the message. Only `GreetingController.ok` changes if a capture shows a value.
4. The exact text of each 500 body: Mule's expression and transformer failures carried their own messages, which differ from the Java exception messages above (`get-greet3-non-numeric-age`, `post-greet5-malformed-xml`, `post-greet6-malformed-json`), as does the Mule failure for a form-urlencoded body to `/greet5` or `/greet6`. The 404 text of `get-unknown-path` is a platform default as well, as is the 404 for a listener path carrying `;` segment parameters.
5. `Hello null` for a missing `username` (`get-greet1-no-username`).

## Behaviours stubbed or externalized

- Nothing is stubbed or mocked at runtime, and no flow behaviour was dropped: the original reaches no external system.
- Externalized: the hard-coded output directory `Path_of_your_choice` became the key `greeting.output-dir`, with the original value as its default.
- One deliberate difference, recorded in `DECISIONS.md` (section 8, implementation safety controls): `/greet5` never loads external entities, external parameter entities, the external DTD subset or XInclude, and secure processing caps entity expansion. Mule 3.8.0's reader resolved external entities. A document without external references, every example and test input included, parses exactly as before; a `DOCTYPE` and internal entities stay allowed.
- Studio metadata (`catalog/**`, `mule-project.xml`) and `log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/mule-expression-language-basics-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081, files in ./Path_of_your_choice
java -jar target/mule-expression-language-basics-java-1.0.0-SNAPSHOT.jar
```

Expected exchanges (the `Date` value varies):

```console
$ curl -i "http://localhost:8081/greet1?username=yourName"
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 14
Date: <current date>

Hello yourName

$ curl -i http://localhost:8081/greet7
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 33
Date: <current date>

No listener for endpoint: /greet7
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Surefire sets the system property `greeting.output-dir` to `target/test-greetings`, so a test context that does not bind its own JUnit `@TempDir` never writes into `Path_of_your_choice/`.
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `MuleExpressionLanguageBasicsTest` | the ported `MuleExpressionLanguageBasicsIT` (`mule-expression-language-basics/src/test/java/org/mule/examples/MuleExpressionLanguageBasicsIT.java:67-118`), every request a `POST` with `Content-Type: text/plain` as the original's `MuleClient` sent it: `greet1` and `greet2` with `username=Mule&age=1` return exactly `Hello Mule`, `greet2` without a query `No username provided`; `greet3` to `greet6` (query `username=Mule&age=1`, the copied `message.xml` and `message.json`) return `Hello Mule` and leave exactly one entry in the output directory whose trimmed content equals `Mule, 1, false` (`Mule,1,false` for `greet4`). The directory lives under a JUnit `@TempDir` |
| `controller/GreetingControllerTest` | on the wire through ST-1: each listener's happy path for `GET`, `POST` and `OPTIONS` with the original test's inputs, `200 Hello Mule` and, for `greet3` … `greet6`, exactly one file with the expected line; the `greet2` empty-username branch; the `http.query.params` semantics (last pair wins, UTF-8 and `+` decoding, `/greet1?username=a;b` → `Hello a;b`, a pair without `=` → `Hello null`); the no-listener 404 and no file for the paths next to every listener, for `/greet1;x=1` … `/greet6;x=1` and `/greet3;jsessionid=ABC`, and for `/greet7`; Undertow's empty 400 for a malformed query escape; the JSON 500 and no file for a non-numeric or empty `greet3` age and for bodies `greet5` and `greet6` cannot read or parse |
| `controller/GreetingListenerBoundaryTest` | on the wire through ST-1: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` and `TRACE` on each listener run the flow and get `200` with its `Content-Length` and no `Content-Type`, and a `TRACE` carrying `Authorization` is never echoed; `HEAD` gets the headers without the body and still writes the file; `greet5` and `greet6` accept their documents as `text/plain`, as their own media type and under an unparseable `Content-Type`; a form-urlencoded body never fails the query-driven flows and makes `greet5` and `greet6` answer every method with the same JSON 500 and no file; the file count of every call |
| `service/GreetingServiceTest` | every branch of the six flows with real collaborators and exact, untrimmed file bytes: the `greet2` choice, each operand type of the MEL `> 18` comparison and its failures, the XPath selections and encodings of `greet5`, the payload shapes of `greet6`, external references never loaded, one new file per call, and failures that write no file |
| `mapper/GreetingCsvMapperTest` | the `docs-greetingFlow4` script: `Mule,1,false` + LF for the original input, column order `username`, `age`, `age_of`, no header, `null` values as empty fields, and the DataWeave `age > 18` coercion |
| `mapper/DwCsvWriterTest` | SC-4: comma separator, optional header row, LF line ends with a trailing LF, number rendering, quoting and escaping |
| `service/FileOutboundWriterTest` | SC-6: directory creation, whole-file replacement, permissions, locking, symbolic links, failures, and the `<uuid>.dat` default name |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes; `ErrorResponse` JSON escaping; escaped, length-bounded ERROR log lines |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to each of `/greet1` … `/greet6` runs the flow exactly as a plain `OPTIONS` request does (`200 Hello Mule`, no `Content-Type`, no `Access-Control-*` or `Vary`, one file for `greet3` … `greet6`); a pre-flight to an unserved path, `/greet7` or `/greet3;x=1?username=Mule&age=1`, gets the no-listener 404 and writes no file |
| `config/SensitiveLogLevelsTest` | the five `logging.level` keys bound from `application.yml` as Spring Boot binds them; with the root at `TRACE` and those levels applied through Spring Boot's logging system, `GET` `/greet1` … `/greet4` with a marker `username` and `age`, `/greet3` with a non-numeric marker `age` (the JSON `500`), and `POST` `/greet5` and `/greet6` with well-formed bodies, a truncated XML document, XML naming the marker as an undeclared entity and truncated JSON answer with their documented status line, `Content-Type`, body bytes and file, and write no marker, as text or as logged body bytes, through any logger outside `com.mulesoft.examples`, in a message, an argument or a throwable's chain; a control with the levels cleared shows exactly `DispatcherServlet` writing the query, and `HandlerMethod`, `ExceptionHandlerExceptionResolver` and `HttpEntityMethodProcessor` writing each failure's message, and no logger writing the well-formed bodies or the truncated XML, whose parser message quotes no text. A marker `username` with an undecodable `%ZZ` escape, sent raw, is answered `400 Bad Request` with an empty body, writes no file and is written by no such logger; the control shows exactly `io.undertow.request.io` writing it |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` and the format of `src/test/resources/fixtures/SCENARIOS.md` are copies of the ST-2 reference implementation; the fixture contract, replay drivers, comparison rules and status values are described once, in [hello-world-java's README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation), and in full in [`SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds this project's capture commands (section Capture). Every scenario here is an `http` scenario against the listener on port `8081`; none makes an outbound call (`"upstream": []`), and each seeds an empty `greeting.output-dir` and records the files it produced.

The 22 scenarios, exactly as `SCENARIOS.md` names them, all `UNVERIFIED-uncaptured` in the last run:

| Scenario | State | Request |
|---|---|---|
| `post-greet1-username` | `deterministic` | `POST /greet1?username=Mule&age=1`, `Content-Type: text/plain`, empty body |
| `get-greet1-username` | `deterministic` | `GET /greet1?username=Mule` |
| `get-greet1-no-username` | `deterministic` | `GET /greet1` |
| `post-greet2-username` | `deterministic` | `POST /greet2?username=Mule&age=1`, `Content-Type: text/plain`, empty body |
| `post-greet2-no-username` | `deterministic` | `POST /greet2`, `Content-Type: text/plain`, empty body |
| `get-greet2-blank-username` | `deterministic` | `GET /greet2?username=%20` |
| `post-greet3-age-under-18` | `nondeterministic` | `POST /greet3?username=Mule&age=1`, `Content-Type: text/plain`, empty body |
| `get-greet3-age-over-18` | `nondeterministic` | `GET /greet3?username=yourName&age=22` |
| `get-greet3-age-5` | `nondeterministic` | `GET /greet3?username=Mule&age=5` |
| `get-greet3-no-age` | `nondeterministic` | `GET /greet3?username=Mule` |
| `get-greet3-non-numeric-age` | `deterministic` | `GET /greet3?username=Mule&age=abc` |
| `post-greet4-age-under-18` | `nondeterministic` | `POST /greet4?username=Mule&age=1`, `Content-Type: text/plain`, empty body |
| `get-greet4-age-over-18` | `nondeterministic` | `GET /greet4?username=yourName&age=22` |
| `get-greet4-age-5` | `nondeterministic` | `GET /greet4?username=Mule&age=5` |
| `get-greet4-age-100` | `nondeterministic` | `GET /greet4?username=Mule&age=100` |
| `post-greet5-xml` | `nondeterministic` | `POST /greet5`, `Content-Type: text/plain`, body `message.xml` |
| `post-greet5-readme-xml` | `nondeterministic` | `POST /greet5`, `Content-Type: text/plain`, body `<user><username>test</username><age>21</age></user>` |
| `post-greet5-malformed-xml` | `deterministic` | `POST /greet5`, `Content-Type: text/plain`, body `<user><username>Mule</username>` |
| `post-greet6-json` | `nondeterministic` | `POST /greet6`, `Content-Type: text/plain`, body `message.json` |
| `post-greet6-readme-json` | `nondeterministic` | `POST /greet6`, `Content-Type: text/plain`, body `{"username":"test","age":21}` |
| `post-greet6-malformed-json` | `deterministic` | `POST /greet6`, `Content-Type: text/plain`, body `{"username": "Mule",` |
| `get-unknown-path` | `deterministic` | `GET /greet7` |

The twelve `nondeterministic` rows are the requests that write a file: each produced file is named `<uuid>.dat`, a name the platform generates, so a capture cannot be reproduced byte for byte. Their replies and files are compared for structure only and report `UNVERIFIED — nondeterministic output`, so this project reaches at most `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` once captured (AAP 0.8.4, 0.8.7). No scenario is `unreachable`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| mule-expression-language-basics-java | mule-expression-language-basics | General | PASS | PASS | 91.4% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`, which runs every phase of `clean package`: `BUILD SUCCESS`; 636 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 636 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 266 of 291 lines of `service/GreetingService`, `service/FileOutboundWriter`, `mapper/GreetingCsvMapper` and `mapper/DwCsvWriter`. Total project LINE coverage: 94.2% (504 of 535 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=22`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". No fixture can be captured from the original, so parity rests on the fallback:

- `MuleExpressionLanguageBasicsTest` carries every committed assertion of the original `MuleExpressionLanguageBasicsIT` with its comparison semantics: exact replies, one file per file-writing call, trimmed file content.
- `GreetingServiceTest`, `GreetingCsvMapperTest`, `GreetingListenerBoundaryTest` and `PreflightDispatchListenerTest` cover every reachable branch, in the service and on the wire.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of MEL, DataWeave and the platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: any method other than `POST` (`get-greet1-username` and every other `get-*` row), a missing username (`get-greet1-no-username`), a whitespace-only username (`get-greet2-blank-username`), `/greet3` with an age over 18, a single-digit, a missing or a non-numeric age (`get-greet3-age-over-18`, `get-greet3-age-5`, `get-greet3-no-age`, `get-greet3-non-numeric-age`), `/greet4` with the ages on which MEL and DataWeave diverge (`get-greet4-age-over-18`, `get-greet4-age-5`, `get-greet4-age-100`), the README sample bodies and malformed bodies of `/greet5` and `/greet6` (`post-greet5-readme-xml`, `post-greet5-malformed-xml`, `post-greet6-readme-json`, `post-greet6-malformed-json`), and the unknown path (`get-unknown-path`).
- Unverified scenarios, until captured: all 22 rows of the table above. After capture, the twelve `nondeterministic` rows stay unverified (structural comparison only) unless a reproducibility input or an `ACCEPTED — structural comparison` decision is recorded in `DECISIONS.md` (AAP 0.8.8); an accepted row still counts as unverified.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `mule-expression-language-basics/`.
