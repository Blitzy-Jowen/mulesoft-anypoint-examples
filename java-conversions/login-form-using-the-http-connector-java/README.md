# login-form-using-the-http-connector-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8.0 example [`login-form-using-the-http-connector`](../../login-form-using-the-http-connector/README.md). The original folder stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The example is an HTML login form served over HTTP that checks the posted credentials, plus a flow that logs in through an outbound form-encoded HTTP request, as the [original README](../../login-form-using-the-http-connector/README.md) describes. Its three flows, `GetLoginPageFlow`, `DoLoginFlow` and `CallLoginFlowUsingRequester`, are in `login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml`.

## Endpoints and triggers

One listener, Undertow on `0.0.0.0`, port `http.port` (default `8081`). Methods are matched ignoring letter case, as the Mule 3.8 listener matched `allowedMethods`.

| Method | Path | Behaviour | Response |
|---|---|---|---|
| `GET` | `/login` | `GetLoginPageFlow` (`login-form-using-the-http-connector.xml:7-12`): the login page | `200 OK`, no `Content-Type` (the flow sets none), body `login/index.html` byte for byte |
| `POST` | `/login` | `DoLoginFlow` (`xml:14-31`): logs `User <username> is trying to login` at INFO, then accepts only `username` = `login.username` and `password` = `login.password` | `200 OK`, `Content-Type: text/html; charset=UTF-8`, `login/loginSuccessful.html` with the username: `User mule has been logged successfully!` |
| `POST` | `/login` | the catch branch (`xml:27-30`): wrong or missing credentials, a body that is not a form, or credentials not configured | `HTTP/1.1 403 ` (empty reason phrase: the branch sets `http.status` without a reason), `login/loginFailure.html`: `Wrong password for username <username>`. `Content-Type` is the request's media type with `; charset=<request charset, else UTF-8>` (`application/x-www-form-urlencoded; charset=UTF-8` for a form post), and none when the request sent none |
| `POST` | `/login` | a form body with a malformed percent escape (`username=%ZZ`); the flow does not run | `400 Bad Request`, no `Content-Type`, body `Cannot decode x-www-form-urlencoded payload` (the Mule listener's answer) |
| any other (`PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE`, …) | `/login` | neither original listener accepts it (`allowedMethods="GET"`, `"POST"`) | `405 Method Not Allowed`, `text/plain`, `Method not allowed for endpoint: /login`, no `Allow` header |
| any | `/requesterLogin` | `CallLoginFlowUsingRequester` (`xml:33-43`): ignores the inbound body and posts the configured credentials as a form to this application's own `POST /login` | `200 OK` with the relayed `Content-Type` and body: the success page when the credentials are configured |
| any | `/requesterLogin` | the self-call answers 400 or more (for example credentials not configured), or cannot connect | `500 Internal Server Error`, `application/json`, `{ "message": "Response code <status> mapped as failure." }` (Mule `http:request`'s default status validation, SC-1 shape), so `{ "message": "Response code 403 mapped as failure." }` with blank credentials; a connection failure reports its own message |
| any | any other path (`/`, `/login/`, `/error` included) | no listener | `404 Not Found`, `text/plain`, `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) |
| any | a served path, on a failure outside the login branch | unhandled exception | `500 Internal Server Error`, `application/json`, `{ "message": "<exception message>" }`, one ERROR log line (SC-1) |

How `POST /login` reads the form, as the Mule 3.8 listener built its payload map:

- Only the body counts, and only when its media type is exactly `application/x-www-form-urlencoded`. Credentials in the query string, or a body of any other media type, reach the check as an empty form and get the 403 page with `null` as the username.
- The body is decoded in the `Content-Type` charset, else UTF-8. Pairs are not trimmed (`password=mule ` is rejected), a repeated name keeps its last value, and extra fields such as the page's `Submit=Submit` are ignored.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request: 405 on `/login`, the flow on `/requesterLogin`. No `Access-Control-*` or `Vary` header is added (`config/PreflightDispatchConfig`); the original applied no CORS policy.

Examples, against a local test run started with the original example's public demo credentials `mule`/`mule` (`LOGIN_USERNAME=mule LOGIN_PASSWORD=mule mvn spring-boot:run`). Those values are for local testing only, never for a real deployment; a real run supplies its own credentials and posts them in place of `mule`/`mule`. Only the status line, `Content-Type`, `Content-Length` and the body's message line are shown; every page starts with the MuleSoft licence comment of its template:

```console
$ curl -i http://localhost:8081/login
HTTP/1.1 200 OK
Content-Length: 443
(body: login/index.html, the form posting username and password to /login)

$ curl -i -d 'username=mule&password=mule' http://localhost:8081/login
HTTP/1.1 200 OK
Content-Type: text/html; charset=UTF-8
Content-Length: 237
User mule has been logged successfully!

$ curl -i -d 'username=mule&password=wrong' http://localhost:8081/login
HTTP/1.1 403 
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
Content-Length: 230
Wrong password for username mule

$ curl -i http://localhost:8081/requesterLogin
HTTP/1.1 200 OK
Content-Type: text/html; charset=UTF-8
Content-Length: 237
User mule has been logged successfully!
```

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Secrets have no value: each is `${ENV_NAME:}` with a `# TODO: supply …` comment (AAP 0.4.6).

| Key | Type | Default | Purpose | Original |
|---|---|---|---|---|
| `http.port` | `int` | `8081` | listener port, bound through `server.port: ${http.port:8081}` | same name: `http:listener-config port="${http.port}"` (`xml:4`); `mule-app.properties` is empty |
| `login.username` | `String` | none — `${LOGIN_USERNAME:}`, TODO (secret) | the only accepted username, and the one `/requesterLogin` posts | literal `'mule'` in the `expression-filter` (`xml:21`) and the requester map (`xml:39`) |
| `login.password` | `String` | none — `${LOGIN_PASSWORD:}`, TODO (secret) | the only accepted password, and the one `/requesterLogin` posts | literal `'mule'` (`xml:21`, `xml:40`) |
| `login.requester-host` | `String` | `localhost` | host of the `/requesterLogin` self-call; the port is always this server's own port | `http:request-config host="0.0.0.0" port="${http.port}"` (`xml:5`); `0.0.0.0` is a bind address, not one a client connects to |

Fixed framework settings that reproduce the original listener (not keys of the original; changing them changes behaviour):

| Key | Type | Value | Why |
|---|---|---|---|
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | `http:listener-config port="${http.port}"` (`xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | `http:listener-config host="0.0.0.0"` (`xml:4`) |
| `spring.application.name` | `String` | `login-form-using-the-http-connector-java` | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | `TRACE` gets the 405 on `/login` and runs the flow on `/requesterLogin`, instead of the servlet's echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | no Boot `/error` endpoint; `/error` gets the no-listener 404 |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | below it the servlet writes the request line with its query and, with `spring.mvc.log-request-details`, every form parameter, the password included, which the original never logged; it also reads the form through the servlet parameters, consuming the body `LoginController` reads, so a correct login would get the 403. A logger's own level outranks `logging.level.root`, `--debug` and `--trace`; `WARN`, not `INFO`, so that a root `WARN` also silences its servlet start-up lines |
| `logging.level.io.undertow.request.io` | `LogLevel` | `INFO` | as above, for the listener's request-parse failures, which quote a query value that does not decode |

- The original hard-coded `mule`/`mule`. The conversion never hard-codes credentials, so supply them through the environment variables `LOGIN_USERNAME` and `LOGIN_PASSWORD`, or as `--login.username=… --login.password=…`.
- Unit tests supply the original demo values `mule`/`mule` through `src/test/resources/application-test.yml` (Spring profile `test`).
- Fail closed: with either key blank, no login succeeds (an empty form never matches an empty configuration), each rejected attempt logs one WARN naming the blank keys, for example `Login rejected because login.username and login.password are not configured`, and `/requesterLogin` returns the 500 above.

## Mule-to-Java mapping

| Mule element (`login-form-using-the-http-connector.xml`) | Java (`com.mulesoft.examples.login_form_using_the_http_connector`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded), `server.address` and `server.port` from `application.yml` |
| GET listener `/login` (`xml:9`), POST listener `/login` (`xml:16`), listener `/requesterLogin` without `allowedMethods` (`xml:35`) | `controller/LoginController`: binds the request (method, form body) and writes the bytes; no routing or rendering logic |
| `parse-template` (three uses, `xml:11`, `24`, `29`) | SC-14 `mapper/ParseTemplateRenderer`; the templates under `src/main/resources/login/` are byte-identical to the original's. This project holds the reference copy that `http-multipart-request-java`, `salesforce-data-retrieval-java`, `get-customer-list-from-netsuite-java` and `processing-orders-with-dataweave-and-APIkit-java` copy |
| `logger`, `message-filter throwOnUnaccepted="true"` with its `expression-filter`, success `parse-template` and `set-property Content-Type` (`xml:18-25`) | `service/LoginService.login()`; a rejection or any failure of these steps is `LoginService.LoginFailedException` |
| `catch-exception-strategy`: `http.status` 403 and `loginFailure.html` (`xml:27-30`) | `exception/LoginExceptionHandler` (`@Order(0)`), which claims `LoginFailedException` only |
| `expression-component` credential map (`xml:38-40`) and `http:request` POST `/login` (`xml:42`) | `service/LoginService.requesterLogin()` (a `HashMap`, as the original's `new HashMap()`) and `client/LoginRequesterClient` (`RestClient`, form-encoded without a charset parameter, 10 s connect and read timeouts, no retry, status of 400 or more mapped as failure) |
| Platform defaults (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Listener with no CORS policy | `config/PreflightDispatchConfig` |
| hard-coded `mule`/`mule` | `config/LoginProperties` (`login.*`) |
| `mule-deploy.properties` (loads the one flow file) | `LoginFormUsingTheHttpConnectorApplication` (`@SpringBootApplication`) |

## Behaviours stubbed or externalized

- Externalized: the credentials (above). Nothing is stubbed: the only outbound call targets the application itself.
- Not added: an authentication framework. The original's manual check is kept as it is; its README recommends Mule Security for real use.

### Tier 2A verification points

Each is the conversion's reading of the Mule 3.8 platform, and a captured fixture overrides it (AAP 0.4.7, 0.8.4):

1. The GET page carries no `Content-Type`; the 403 page carries the empty reason phrase and the request-derived `Content-Type`, because the catch branch sets neither.
2. A missing `username` renders as `null` in the log line and on the failure page, as Mule 3 rendered a null expression.
3. The `/requesterLogin` 500 body `{ "message": "Response code 403 mapped as failure." }`.
4. A POST whose body is not form-encoded is a form with no fields, so it gets the 403 page.
5. The 400 `Cannot decode x-www-form-urlencoded payload` for a malformed form body (root `DECISIONS.md`, section 9).

## Build, run and test

Prerequisites: JDK 17 and Maven 3.9; no Mule licence, credentials or external server. Run from `java-conversions/login-form-using-the-http-connector-java/`:

```sh
mvn -B clean package    # build, surefire tests, JaCoCo report and check
mvn -B test             # surefire tests only
mvn -B clean install    # package and install the repackaged jar locally
# run: replace <username> and <password> with the credentials the login form is to accept
LOGIN_USERNAME='<username>' LOGIN_PASSWORD='<password>' mvn spring-boot:run
LOGIN_USERNAME='<username>' LOGIN_PASSWORD='<password>' java -jar target/login-form-using-the-http-connector-java-1.0.0-SNAPSHOT.jar
# local testing only: the original example's public demo values mule/mule, never for a real deployment
LOGIN_USERNAME=mule LOGIN_PASSWORD=mule mvn spring-boot:run
```

- There is no `mvn verify` live tier: this is not a connector project, so it has no failsafe plugin and no `*IT` class.
- JaCoCo gate: LINE covered ratio of at least 0.80 over `service/**` and `mapper/**`, checked at `prepare-package`; a lower ratio fails `mvn clean package` and `mvn clean install`. The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate`.
- Reports: `target/surefire-reports/`, `target/site/jacoco/` (`index.html`, `jacoco.csv`) and `target/fixture-parity/summary.properties`.

## Tests

| Test class | What it asserts |
|---|---|
| `HttpLoginHtmlFormTest` | port of `HttpLoginHtmlFormIT`: `testLogin` (POST `mule`/`mule` contains `User mule has been logged successfully!`), `testLoginPage` (GET `/login` equals `index.html`), `testRequester` (`/requesterLogin` contains the same text) |
| `controller/LoginControllerTest` | on the wire through ST-1: status lines, `Content-Type` and body bytes of the GET page, success page and 403 page for every method letter case; query-string credentials ignored, repeated fields, untrimmed whitespace, non-form bodies, the malformed-form 400; the 405 for every other method, `HEAD` included; the 404; `/requesterLogin` for every method; with blank credentials, every form rejected, the WARN line and the requester 500 |
| `service/LoginServiceTest` | the credential check against configured and blank values, the rendered pages, the INFO and WARN lines (never a password), rendering failures reaching the catch branch, the requester's posted `HashMap` and its unretried failure |
| `client/LoginRequesterClientTest` | WireMock: the exact form request and body encoding, relayed status, `Content-Type` and bytes, `Response code <n> mapped as failure.` for 400, 403 and 500, no retry on connection failures, the base URL taken from the started server |
| `exception/LoginExceptionHandlerTest` | the 403 with empty reason phrase, failure page and request-derived `Content-Type` and charset; only `LoginFailedException` claimed, every other exception left to SC-1 |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` and bodies, `ErrorResponse` JSON escaping, the bounded one-line ERROR log |
| `mapper/ParseTemplateRendererTest` | SC-14: templates byte for byte, expression replacement, `null` rendering, missing templates and unresolved expressions |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight is dispatched as a plain `OPTIONS` request: 405 on `/login`, the flow on `/requesterLogin`, 404 elsewhere, no CORS headers |
| `config/SensitiveLogLevelsTest` | under root `TRACE`, the `web` group at `TRACE` and `spring.mvc.log-request-details`: no sentinel password reaches the log, whether configured, wrong, posted by `/requesterLogin`, undecodable in the form or sent in the query; each exchange still answers as the flow does (200, 403, the relayed 200, 400); both pinned levels hold; `User mule is trying to login` and the unpinned loggers' `TRACE` lines are still written |
| `FixtureParityTest` | ST-2 fixture replay; skipped (`no Tier 2A fixtures captured`) until fixtures exist |

`support/HttpTestClient` (ST-1) is an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, so the empty 403 reason phrase is asserted on the wire.

## Fixture parity (ST-2)

The scenarios are listed in [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract and the capture procedure. Each is seeded with `login.username=mule` and `login.password=mule`, the original literals.

| Scenario | Request | State | Status |
|---|---|---|---|
| `get-login-page` | `GET /login` | `deterministic` | unverified (uncaptured) |
| `post-login-success` | `POST /login`, form `username=mule&password=mule` | `deterministic` | unverified (uncaptured) |
| `post-login-failure` | `POST /login`, form `username=mule&password=wrong` | `deterministic` | unverified (uncaptured) |
| `requester-login` | `GET /requesterLogin` | `deterministic` | unverified (uncaptured) |

- Capture follows AAP 0.8.4 with the original deployed on `http.port=8081`. No WireMock recording is needed: the requester's only outbound call targets the application itself, so every fixture has `"upstream": []`.
- Once fixtures are captured, the label changes as AAP 0.8.7 sets out, here and in the matching `SUMMARY.md` row. A fixture is never edited to match the Java output.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| login-form-using-the-http-connector-java | login-form-using-the-http-connector | General | PASS | PASS | 95.5% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The root [`SUMMARY.md`](../../SUMMARY.md) carries the same row for this project. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 287 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 287 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 85 of 89 lines (`service/LoginService` 40 of 40, `mapper/ParseTemplateRenderer` 45 of 49). Total project LINE coverage: 94.6% (434 of 459 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=4`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed (root [`DECISIONS.md`](../../DECISIONS.md), section 9). Parity therefore rests on the fallback of AAP 0.8.4: the ported original assertions (`HttpLoginHtmlFormTest`) plus the branch tests of the controller, service and handlers. That evidence is weaker than captured fixtures: it shows the conversion meets the original's own assertions and the plan's reading of the platform, not that its bytes equal what the Mule runtime sent. The parity label stays `UNVERIFIED` and never reads PASS on this fallback. Tier 2B is `N/A`: the example calls no vendor system.

- No original assertion covers the 403 failure page, the 405 on other methods, the 404 on unknown paths, the malformed-form 400 or the requester 500: the original IT asserts only the success page, the exact login page and the requester success. Those branches are covered by the new controller, service and handler tests only.

## Licence

Apache License 2.0 (`LICENSE`), attribution in `NOTICE`, both copied unchanged from `login-form-using-the-http-connector/`.
