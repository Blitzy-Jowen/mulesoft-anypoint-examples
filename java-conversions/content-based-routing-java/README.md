# content-based-routing-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`content-based-routing`](../../content-based-routing/) (category General). It routes an HTTP request on its `language` query parameter with the logic of the original Choice router and answers with a greeting in Spanish, French or, by default, English. The original folder `content-based-routing/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The example shows routing on message content. The application receives an HTTP request and filters out browser `favicon.ico` requests. It copies the `language` query parameter into a flow variable, routes the message on that variable with a Choice router, and returns the payload the chosen branch sets as the HTTP response (`content-based-routing/README.md:12`).

- Flow file: `content-based-routing/src/main/app/content-based-routing.xml`, with the flow `content-based-routingFlow` (lines 4-21) and the sub-flow `replyInDefaultLanguage` (lines 22-26).
- Test: `content-based-routing/src/test/java/org/mule/examples/ContentBasedRoutingIT.java`, which asserts the Spanish and French replies (lines 48-56).
- The flow declares no exception strategy and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/` exactly | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body `Hola!`, `Bonjour!` or `Hello!` (UTF-8, no trailing newline) with its `Content-Length`, no `Content-Type` header; a `HEAD` reply carries the headers without the body | `content-based-routing/src/main/app/content-based-routing.xml:3-26` |
| `/`, payload rejected by the favicon filter | any | `0.0.0.0` | `http.port` | `200 OK`, `Content-Length: 0`, empty body, no `Content-Type` (AAP 0.4.7); nothing is logged | `content-based-routing.xml:7` |
| `/`, `application/x-www-form-urlencoded` body with a malformed percent escape | any | `0.0.0.0` | `http.port` | `400 Bad Request`, no `Content-Type`, body `Cannot decode x-www-form-urlencoded payload` (43 bytes); the flow does not run | Mule 3.8 listener, reproduced by `GreetingController` |
| any other path (`/favicon.ico`, `/unknown` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/`, when processing throws | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception |

Routing (`service/LanguageRoutingService.reply`), with the original conditions copied verbatim and compared exactly and case-sensitively:

| `language` query parameter | Branch | Reply | Flow variable `language` |
|---|---|---|---|
| `Spanish` | `when #[flowVars['language'] == 'Spanish']` (`content-based-routing.xml:10-12`) | `Hola!` | `Spanish` |
| `French` | `when #[flowVars['language'] == 'French']` (`content-based-routing.xml:13-15`) | `Bonjour!` | `French` |
| anything else: no `language` parameter, an empty value (`language=`), a bare `language`, `spanish`, `FRENCH`, `German`, `English` | `otherwise` → sub-flow `replyInDefaultLanguage` (`content-based-routing.xml:16-18,22-26`) | `Hello!` | set to `English` |

- `language` is read from the query string only, as the Mule listener exposed it in `http.query.params`. `GreetingController` splits the raw query string on `&` and each pair on its first `=`, and URL-decodes name and value as UTF-8. When the name repeats, the last pair wins, because Mule 3.8's `ParameterMap.get` returned `values.getLast()`: `/?language=French&language=Spanish` answers `Hola!`. A bare `language` has no value. A form field named `language` in the request body is never read: `POST /` with the form body `language=Spanish` answers `Hello!`.
- The flow's payload is built as the Mule 3.8 listener built it: no value for an empty or a `multipart` body, the decoded parameter map for an `application/x-www-form-urlencoded` body, and otherwise the body text in the charset its `Content-Type` declares (UTF-8 when it declares none). Routing never depends on the payload.
- Favicon filter (`expression-filter #[payload != '/favicon.ico']`, `content-based-routing.xml:7`): a text payload equal to `/favicon.ico`, in whatever charset it is declared, is rejected before the language is routed. The filter declares no `throwOnUnaccepted`, so the rejection is not an error and is answered `200` with an empty body (AAP 0.4.7). A form or multipart body holding the same text is a parameter map or no value, so it passes the filter and is routed. A browser's `GET /favicon.ico` never reaches the flow, because the listener path `/` is matched exactly; it gets the listener-default 404.
- Log lines, SLF4J INFO from `LanguageRoutingService` with the original loggers' text (`content-based-routing.xml:20,23`): `The reply "Hola!" means "hello" in Spanish.` or `The reply "Bonjour!" means "hello" in French.`; the default branch first logs `No language specified. Using English as a default. ` (trailing space kept), then `The reply "Hello!" means "hello" in English.` As in the original, the default line is logged for every value that takes the default branch, not only for a missing one.
- The reply is written without content negotiation, so no `Accept` value turns it into a 406. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- Input rejected before the flow runs: a query string with a malformed percent escape (`/?language=%ZZ`) is rejected by Undertow before any controller runs, with `400 Bad Request`, an empty body and `Connection: close`, where Mule sent 400 with its own message (recorded in `DECISIONS.md`). A `Content-Type` that cannot be parsed reaches SC-1's 500, for example `{ "message": "Invalid mime type \"bogus\": does not contain '/'" }`.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, with the routed reply and no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A request body the flow leaves unread is drained after the response, so requests pipelined on one HTTP/1.1 connection are all answered. The Mule listener (Grizzly) read the whole request before the flow ran (`config/RequestBodyDrainFilter`).
- Tier 2A verification points (AAP 0.4.7), plan defaults that a captured fixture may overturn:
  - the absent `Content-Type` of the `200` replies;
  - the bytes of the `404` default (`get-favicon`) and of the `500` default;
  - whether the original filter ever rejected a body: the Mule 3.8 listener handed the flow a POST body as a stream, so the MEL comparison with the string `'/favicon.ico'` may never have matched (`post-root-favicon-payload`);
  - the headers of the empty-body rejection.

  When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README tells users to set port 8081 and browse to `http://localhost:8081/?language=Spanish`, and its test reserves the port dynamically (`ContentBasedRoutingIT.java:33`). |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`content-based-routing.xml:3`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`content-based-routing.xml:3`) |
| `spring.application.name` | `String` | `content-based-routing-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path, `/favicon.ico` included, must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `GreetingController` like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

- Original-to-new key map: `http.port` → `http.port` (unchanged). The original reads no other placeholder.
- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no live-test credentials file (`application-it.yml`).
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  java -jar target/content-based-routing-java-1.0.0-SNAPSHOT.jar --http.port=9090
  HTTP_PORT=9090 java -jar target/content-based-routing-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`content-based-routing`) | Java construct (`com.mulesoft.examples.content_based_routing`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`content-based-routing.xml:3`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port: ${http.port:8081}` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (`content-based-routing.xml:5`) | `controller/GreetingController`, `@RequestMapping("/")`: `greeting` (every method but `OPTIONS`) and `greetingOptions` (`OPTIONS`). It only binds the `language` query parameter and the body and writes the reply; it holds no routing logic |
| Mule 3.8 listener input handling: the payload built from the body, `http.query.params`, the 400 for an undecodable form body | `GreetingController` private methods `listenerPayload`, `formParameters`, `languageFrom` and `rejectUndecodableForm` |
| `expression-filter #[payload != '/favicon.ico']` (`content-based-routing.xml:7`) | guard clause at the start of `service/LanguageRoutingService.reply(Object payload, String language)`, which returns `Optional.empty()` on rejection |
| `set-variable language` from `http.query.params` (`content-based-routing.xml:8`) | the `language` argument of `reply`, kept in a local variable |
| `choice` with two `when` branches and `otherwise` (`content-based-routing.xml:9-19`) | `if / else if / else` in `reply`, with the constants `SPANISH`, `FRENCH`, `SPANISH_REPLY`, `FRENCH_REPLY` |
| `flow-ref` to sub-flow `replyInDefaultLanguage` and its `set-variable` and `set-payload` (`content-based-routing.xml:17,22-26`) | private method `replyInDefaultLanguage`, returning `DEFAULT_REPLY` and `DEFAULT_LANGUAGE` |
| both `logger`s (`content-based-routing.xml:20,23`) | SLF4J INFO statements in `LanguageRoutingService`, same text, same order |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse`, copied from `rest-api-with-apikit-java` with only the `package` line changed |
| Grizzly listener semantics (whole request read before the flow, no CORS policy) | `config/RequestBodyDrainFilter`, `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `content-based-routing.xml`) | `ContentBasedRoutingApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `ContentBasedRoutingIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `ContentBasedRoutingTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`): the ported Spanish and French assertions, plus the default branch |

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/content-based-routing-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/content-based-routing-java-1.0.0-SNAPSHOT.jar
```

Expected exchanges (the `Date` value varies):

```console
$ curl -i "http://localhost:8081/?language=Spanish"
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 5
Date: <current date>

Hola!
$ curl -i "http://localhost:8081/?language=French"
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 8
Date: <current date>

Bonjour!
$ curl -i http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 6
Date: <current date>

Hello!
```

The matching log lines:

```text
INFO … c.m.e.c.service.LanguageRoutingService   : The reply "Hola!" means "hello" in Spanish.
INFO … c.m.e.c.service.LanguageRoutingService   : The reply "Bonjour!" means "hello" in French.
INFO … c.m.e.c.service.LanguageRoutingService   : No language specified. Using English as a default. 
INFO … c.m.e.c.service.LanguageRoutingService   : The reply "Hello!" means "hello" in English.
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project and calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).
- Coverage gate: JaCoCo 0.8.15 checks a LINE covered ratio of at least 0.80 over `service/**` and `mapper/**` at `prepare-package`, so `mvn clean package` and `mvn clean install` fail below it. The project has no `mapper` package, so the gate measures `service/LanguageRoutingService`.

## Tests

| Test class | What it asserts |
|---|---|
| `ContentBasedRoutingTest` | the ported `ContentBasedRoutingIT` (`content-based-routing/src/test/java/org/mule/examples/ContentBasedRoutingIT.java:48-56`): `GET /?language=Spanish` and then `GET /?language=French`, each with an empty body, return a non-empty body equal to `Hola!` and `Bonjour!`; plus `GET /`, which the original leaves untested, returning `Hello!` |
| `controller/GreetingControllerTest` | on the wire through ST-1, with the real service: `200 OK`, no `Content-Type`, the exact `Content-Length` and body for the Spanish, French and default branches; the default branch for a missing, empty, bare, unknown or differently cased `language`; every method with and without a text body, `HEAD` without a body, `OPTIONS` and `TRACE`; `Accept` ignored; URL decoding and last-pair-wins of repeated `language` pairs; a form field never selecting the language; the favicon filter rejecting only the favicon text (text in two charsets, not a form or multipart body) with the empty 200, before routing; `/favicon.ico` and other paths → 404 `text/plain` `No listener for endpoint: <path>`; an undecodable form body → the listener's 400; an unparseable `Content-Type` and a failing service → 500 `application/json` |
| `service/LanguageRoutingServiceTest` | `reply` for each branch with the original reply literals and the INFO log lines in order (the default line before the reply line); the favicon text filtered with no reply and no log; other texts, `null` and parameter-map payloads passing the filter; the constants equal to the original flow's values |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` is answered exactly as a plain `OPTIONS` request, `200` with the routed reply (`Bonjour!`, `Hola!` or `Hello!`), no `Content-Type` and no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404 |
| `config/RequestBodyDrainFilterTest` | pipelined requests behind a body-less request are all answered, on `/` and on an unserved path; draining never changes the chain's outcome and leaves reader-read and asynchronous bodies alone |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, with an HTTPS mode for TLS listeners and a per-client read budget (`withReadTimeout`, default `TIMEOUT_MILLIS`).

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/content_based_routing/FixtureParityTest.java` is the shared replay engine, copied from `java-conversions/hello-world-java/` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` holds this project's scenario rows, the fixture contract and the capture procedure (AAP 0.4.4, 0.8.4).

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, each a JSON object with the sections `request`, `state`, `upstream` and `response`. For this project `state` is `{}` and `upstream` is `[]`: the original reaches no store and makes no outbound call.
- Each `http` scenario is replayed through ST-1 against the application on a random port and compared byte for byte: status, reason phrase, `Content-Type`, `Location` and body.
- Fixtures are captured from the running original by the capture procedure of `SCENARIOS.md` (section Capture), with the original deployed on `http.port=8081`, and are never hand-written or edited to match the Java output. A mismatch is fixed in the Java code.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed. Each run writes `target/fixture-parity/summary.properties`.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-spanish` | `http` | `deterministic` | `GET /?language=Spanish`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-french` | `http` | `deterministic` | `GET /?language=French`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-default` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `get-favicon` | `http` | `deterministic` | `GET /favicon.ico`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-favicon-payload` | `http` | `deterministic` | `POST /`, header `Content-Type: text/plain`, body `/favicon.ico` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| content-based-routing-java | content-based-routing | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 216 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 216 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 17 of 17 lines of `service/LanguageRoutingService` and its nested record `Routed` (the project has no `mapper` package). Total project LINE coverage: 97.6% (287 of 294 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md`. No fixture can be captured from the original, so parity rests on the fallback:

- `ContentBasedRoutingTest` carries the original committed assertions: the bodies of `GET /?language=Spanish` and `GET /?language=French` equal `Hola!` and `Bonjour!`.
- `GreetingControllerTest` and `LanguageRoutingServiceTest` cover every reachable branch, on the wire and at the service.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- Three branches have no original assertion and are covered only by the new tests: the default English branch (`get-root-default`), the favicon filter rejection (`post-root-favicon-payload`) and the listener-default 404 for `/favicon.ico` (`get-favicon`).
- Unverified scenarios, until captured: `get-root-spanish`, `get-root-french`, `get-root-default`, `get-favicon`, `post-root-favicon-payload`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7). Once the Mule EE licence and the EE repository credentials are both confirmed, a human is notified to begin capture and receives this list of unverified scenarios (AAP 0.8.8).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `content-based-routing/`.
