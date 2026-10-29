# Filtering a Message — Spring Boot conversion (filtering-a-message-java)

Java 17 / Spring Boot 3.2.12 conversion of the Mule EE 3.8 example [`filtering-a-message`](../../filtering-a-message/) (category General). The original folder `filtering-a-message/` stays unchanged beside this project and is the Tier 2A capture source. As its README describes (`filtering-a-message/README.md:11-13`), the example receives an order as JSON and passes it through an And filter that admits only a request carrying a payload and using the HTTP method POST. It converts the body to a `java.util.HashMap` and screens that map with the custom `FreeMembershipDiscountFilter`. An order that passes every filter is answered with `the discount was granted.`.

## Original example

The single flow `filteringFlow1` of `filtering-a-message/src/main/app/filtering.xml:4-19` serves one HTTP listener, `POST /` on host `0.0.0.0` and port `${http.port}`. Its processors run in this order: the `and-filter` (a `payload-type-filter` on the listener's request stream and a `message-property-filter` `http.method=post`, lines 9-12), the `json:json-to-object-transformer` with `returnClass="java.util.HashMap"` (line 14), the `custom-filter` `FreeMembershipDiscountFilter` (line 15), the `set-payload` `the discount was granted.` (line 16) and an INFO `logger` of the payload (line 17). Neither filter declares `throwOnUnaccepted`, the flow declares no exception strategy, and it calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Served by | Source |
|---|---|---|---|---|---|
| `/` | `POST` only, compared ignoring letter case (`post` and `Post` are accepted), as `allowedMethods="POST"` was | `0.0.0.0` | `http.port`, default `8081` | `controller/DiscountController` | `filtering.xml:3,5` |

The request `Content-Type` is not checked, as in the original: a JSON body sent as `application/json`, as `text/plain` or with no `Content-Type` is converted alike. `Accept` is ignored.

Outcomes, as the code produces them:

| Case | Status and reason | `Content-Type` | Body |
|---|---|---|---|
| Discount granted | `200 OK` | none | `the discount was granted.` (25 bytes, `Content-Length: 25`) |
| Discount rule not met | `200 OK` | none | empty (`Content-Length: 0`) |
| Empty request body (zero bytes): the And filter's payload filter rejects it | `200 OK` | none | empty |
| `multipart/*` or `application/x-www-form-urlencoded` body: the Mule listener moved parts to attachments and decoded forms to a parameter map, which the payload filter rejects | `200 OK` | none | empty |
| Form-urlencoded body with a malformed percent escape: the Mule listener's own 400, before the flow runs | `400 Bad Request` | none | `Cannot decode x-www-form-urlencoded payload` |
| Any method other than POST on `/`, including `GET`, `HEAD`, `OPTIONS` and `TRACE` | `405 Method Not Allowed` | `text/plain` | `Method not allowed for endpoint: /` (SC-1); no `Allow` header |
| Any other path, `/error` included | `404 Not Found` | `text/plain` | `No listener for endpoint: <path>` (SC-1) |
| Body that is not readable JSON (syntax error, truncated) | `500 Internal Server Error` | `application/json` | `{ "message": "Payload is not valid JSON" }` (SC-1) |
| Whitespace-only body, or a JSON value other than an object (array, string, number, boolean, `null`) | `500 Internal Server Error` | `application/json` | `{ "message": "JSON payload is not an object" }` (SC-1) |
| Missing or `null` `membership`, `months` or `purchases`, or a non-integer `months` or `purchases` | `500 Internal Server Error` | `application/json` | `{ "message": "JSON payload does not hold the membership, months and purchases values the discount filter requires" }` (SC-1) |
| A `Content-Type` header that cannot be parsed, an unknown charset included | `500 Internal Server Error` | `application/json` | `{ "message": "<Spring's media-type parser message>" }` (SC-1) |

Discount rules, ported unchanged from `filtering-a-message/src/main/java/org/mule/examples/filters/FreeMembershipDiscountFilter.java:21-29`:

- `membership` equal to `premium` (case-sensitive, so `PREMIUM` takes the other path): months ≥ 6 and purchases ≥ 1000.
- Any other membership, when any one of these holds: months ≤ 6 and purchases ≥ 2500; 6 < months < 12 and purchases ≥ 2000; months ≥ 12 and purchases ≥ 1500.

`months` and `purchases` are read with `Integer.parseInt` of their text, so the numeric string `"12"` is accepted and `12.5` fails with the 500 above.

Further behaviour:

- When the discount is granted, `service/DiscountService` logs `the discount was granted.` at INFO, in place of the original `logger` (`filtering.xml:17`).
- The body is decoded with the charset the `Content-Type` declares, UTF-8 when it declares none.
- The 500 bodies carry fixed messages, never Jackson's or the JDK's diagnostic text, which can quote request bytes or name internal types. SC-1 logs each 500 on one ERROR line that names the route and the exception classes, never the request path or message.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) gets the same 405 as a plain `OPTIONS` request, with no `Access-Control-*` or `Vary` header and without running the filters. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A malformed percent escape in the query string is rejected by Undertow with `400 Bad Request` and an empty body before the controller runs, where the Mule listener sent its own message (root `DECISIONS.md`, section 9).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`. Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | the `http:listener-config` port placeholder (`filtering.xml:3`), bound to `server.port` through `server.port: ${http.port:8081}`; the original README sends requests to port 8081 |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`filtering.xml:3`) |
| `spring.application.name` | `String` | `filtering-a-message-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path reaches SC-1's no-listener 404 instead of the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE /` reaches the controller and gets the 405, instead of the servlet's `TRACE` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | the listener checked the method before reading the body, so a form body on `PUT`, `PATCH` or `DELETE` reaches the controller and gets the 405 unread, instead of being read whole by Boot's form-content filter before dispatch |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder, and the project has no `application-test.yml` or `application-it.yml`.
- The original `mule-app.properties` is empty (0 bytes). No original key was renamed (AAP 0.4.6).
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  HTTP_PORT=9090 java -jar target/filtering-a-message-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`filtering-a-message`) | Java construct (`com.mulesoft.examples.filtering_a_message`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`filtering.xml:3`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/" allowedMethods="POST"` and `response-builder statusCode="200"` (`filtering.xml:5-7`) | `controller/DiscountController`: `grantDiscount` (`@RequestMapping("/")`, method checked ignoring case, payload built from the raw body as the Mule listener built it) and `rejectOptions` (`OPTIONS`, answered with the 405) |
| `and-filter` of `payload-type-filter` and `message-property-filter http.method=post` (`filtering.xml:9-12`) | guard in `service/DiscountService.applyDiscountFilters`: only a non-empty body text and the method `post` in any case pass; a rejection is an empty 200 (AAP 0.4.7) |
| `json:json-to-object-transformer returnClass="java.util.HashMap"` (`filtering.xml:14`) | Jackson `readValue` into `java.util.HashMap<String, Object>` in `DiscountService`, the concrete type the original produced (AAP 0.4.9) |
| `custom-filter` `org.mule.examples.filters.FreeMembershipDiscountFilter` (`filtering.xml:15`) | `service/FreeMembershipDiscountFilter`, ported: the Mule `Filter` interface is removed and `accept(Map<String, Object>)` keeps the original body (AAP 0.5.7) |
| `set-payload value="the discount was granted."` (`filtering.xml:16`) | constant `DiscountService.GRANTED_MESSAGE` |
| `logger level="INFO" message="#[payload]"` (`filtering.xml:17`) | SLF4J INFO in `DiscountService` |
| No exception strategy | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` platform defaults |
| Grizzly listener semantics (form decoding, no CORS policy) | `DiscountController` (form and multipart payloads, the form-decoding 400), `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `filtering.xml`) | `FilteringAMessageApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `FilteringMessageIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `FilteringMessageTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

The stray uncompiled copy `filtering-a-message/src/FreeMembershipDiscountFilter.java` was not ported, because no build or flow reaches it (AAP 0.4.5); the compiled class under `src/main/java/org/mule/examples/filters/` is the one ported.

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. Studio metadata (`mule-project.xml`, `catalog/`) is not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/filtering-a-message-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/filtering-a-message-java-1.0.0-SNAPSHOT.jar
```

Expected exchanges (the `Date` value varies):

```console
$ curl -i -H 'Content-Type: application/json' --data-binary @src/test/resources/message.json http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 25
Date: <current date>

the discount was granted.
$ curl -i -H 'Content-Type: application/json' --data-binary @src/test/resources/message1.json http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 0
Date: <current date>

```

Send the `Content-Type` header: without it, curl labels `--data-binary` bodies `application/x-www-form-urlencoded`, which the listener decodes to a parameter map, so even `message.json` is answered with the empty 200.

- `mvn verify` runs the same surefire tests and JaCoCo gate as `mvn package` and adds nothing: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `FilteringMessageTest` | the ported `FilteringMessageIT` (`filtering-a-message/src/test/java/org/mule/examples/FilteringMessageIT.java:54-68`): `POST /` with `message.json` returns a body equal to `the discount was granted.`; with `message1.json` the body is empty |
| `controller/DiscountControllerTest` | on the wire through ST-1: the granted reply (`200 OK`, no `Content-Type`, `Content-Length: 25`) for `application/json`, `text/plain` and the methods `post` and `Post`; the empty 200 for `message1.json`, an empty body, a form-urlencoded and a multipart body; the form-decoding 400; the 405 for `GET`, `PUT`, `DELETE` and `OPTIONS`, and the whole 405 for a form declaring 300000 bytes sent with `PUT`, `PATCH` or `DELETE` while only its first 1024 bytes have been written, with no `FormContentFilter` bean in the context; the 404 for `/other`; the 500 bodies for malformed JSON, a JSON array, JSON `null`, a missing `membership` and a non-integer `months` |
| `service/DiscountServiceTest` | the And filter (empty or absent body, methods other than `post`, form parameter maps) rejecting without parsing; the `HashMap` handed to the filter; the custom-filter rejection; the fixed 500 messages, with the original failure kept as cause and no request bytes or internal names exposed |
| `service/FreeMembershipDiscountFilterTest` | every rule boundary for `premium` and the other memberships, the case-sensitive `premium` comparison, numeric strings, a missing key (`NullPointerException`), a non-integer value (`NumberFormatException`), and the two original payloads |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, and carries no stack trace |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` gets exactly the plain `OPTIONS` 405 whatever method it asks for, and the filters never run; a cross-origin `POST` still reaches the controller; a pre-flight to `/unknown` gets the no-listener 404 |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

Coverage gate: JaCoCo fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80% (AAP 0.8.7). The project has no `mapper` package; the include is kept so the gate reads the same in every converted project.

## Fixture parity (ST-2)

`FixtureParityTest` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed. The fixture format and replay rules are documented in [`java-conversions/hello-world-java/README.md`](../hello-world-java/README.md) (section "Fixture parity (ST-2 reference implementation)"). This project's `src/test/resources/fixtures/SCENARIOS.md` lists its scenarios and holds the fixture contract and the exact capture commands. Every scenario has `state` `{}` and `upstream` `[]`, because the original reaches no store and makes no outbound call. Fixtures are captured from the running original and never hand-written or edited to match the Java output.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-free-long-tenure-granted` | `http` | `deterministic` | `POST /`, body of `message.json` | `UNVERIFIED-uncaptured` |
| `post-root-free-rejected` | `http` | `deterministic` | `POST /`, body of `message1.json` | `UNVERIFIED-uncaptured` |
| `post-root-premium-granted` | `http` | `deterministic` | `POST /`, body `{"purchases": 1000, "months": 6, "membership": "premium"}` | `UNVERIFIED-uncaptured` |
| `post-root-premium-rejected` | `http` | `deterministic` | `POST /`, body `{"purchases": 999, "months": 6, "membership": "premium"}` | `UNVERIFIED-uncaptured` |
| `post-root-free-short-tenure-granted` | `http` | `deterministic` | `POST /`, body `{"purchases": 2500, "months": 6, "membership": "free"}` | `UNVERIFIED-uncaptured` |
| `post-root-free-mid-tenure-granted` | `http` | `deterministic` | `POST /`, body `{"purchases": 2000, "months": 9, "membership": "free"}` | `UNVERIFIED-uncaptured` |
| `post-root-empty-body` | `http` | `deterministic` | `POST /`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-method-not-allowed` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-unknown-path` | `http` | `deterministic` | `POST /other`, body of `message.json` | `UNVERIFIED-uncaptured` |
| `post-root-malformed-json` | `http` | `deterministic` | `POST /`, body `{"purchases": 2000,` | `UNVERIFIED-uncaptured` |
| `post-root-missing-membership` | `http` | `deterministic` | `POST /`, body `{"purchases": 2000, "months": 12}` | `UNVERIFIED-uncaptured` |

Every `POST` row sends `Content-Type: application/json`; `SCENARIOS.md` gives each request byte for byte. No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| filtering-a-message-java | filtering-a-message | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`, which runs every phase of `clean package`: `BUILD SUCCESS`; 176 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 176 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 36 of 36 lines of `service/DiscountService` and `service/FreeMembershipDiscountFilter` (the project has no `mapper` package). Total project LINE coverage: 96.7% (265 of 274 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=11`, `failed=0`.

Tier 2A is blocked: the Mule EE licence and the EE Maven repository credentials are unconfirmed (root `DECISIONS.md`, section 9). Tier 2B is `N/A`, because the project calls no vendor system.

### Tier 2A verification points

Where the original runtime's bytes are not settled by the source, the captured fixture governs once it exists, and a mismatch is fixed in the Java code:

1. The success `Content-Type`. The flow sets none: its `response-builder` declares only `statusCode="200"` (`filtering.xml:6`) and `set-payload` declares no MIME type, so the reply carries no `Content-Type`, although the original README's build steps configure `text/plain` (`filtering-a-message/README.md:62`).
2. The 500 body for unhandled failures. Mule reported its own exception text, which differs from the fixed messages above.
3. Body-present detection. The original payload filter tested for the Grizzly `org.glassfish.grizzly.utils.BufferInputStream` request stream (`filtering.xml:10`); the conversion treats any non-empty body that is neither multipart nor form-urlencoded as present.
4. The 404 and 405 texts, which are platform defaults (AAP 0.4.7).

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `FilteringMessageTest` carries the original committed assertions: `message.json` is granted with `the discount was granted.`, and `message1.json` gets an empty body.
- `DiscountControllerTest`, `DiscountServiceTest` and `FreeMembershipDiscountFilterTest` cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: the premium rule (`post-root-premium-granted`, `post-root-premium-rejected`); the short-tenure (≤ 6 months) and mid-tenure (7–11 months) free rules (`post-root-free-short-tenure-granted`, `post-root-free-mid-tenure-granted`); the empty-body And-filter rejection (`post-root-empty-body`); the 405 on non-POST (`get-root-method-not-allowed`); the 404 on other paths (`post-unknown-path`); the 500 on malformed or incomplete JSON (`post-root-malformed-json`, `post-root-missing-membership`).
- Unverified scenarios, until captured: `post-root-free-long-tenure-granted`, `post-root-free-rejected`, `post-root-premium-granted`, `post-root-premium-rejected`, `post-root-free-short-tenure-granted`, `post-root-free-mid-tenure-granted`, `post-root-empty-body`, `get-root-method-not-allowed`, `post-unknown-path`, `post-root-malformed-json`, `post-root-missing-membership`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `filtering-a-message/`.
