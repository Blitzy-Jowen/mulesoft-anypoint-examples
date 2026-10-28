# testing-apikit-with-munit-java — Spring Boot conversion of the Mule 3.8 example `testing-apikit-with-munit`

Java 17 / Spring Boot 3.2.12 (Undertow) / Maven conversion of the Mule EE 3.8 example [`testing-apikit-with-munit`](../../testing-apikit-with-munit/) ([original README](../../testing-apikit-with-munit/README.md)), category RAML/APIkit-driven REST API. The original folder stays unchanged beside this project as the reference. The example is an APIkit API over `api.raml` (`Sample API`, one resource `/munit`) whose four backend flows return dummy messages; its purpose is to show how an APIkit API is tested with MUnit. Here the API is served by a Spring MVC controller and the MUnit suite is ported to JUnit 5. Package root `com.mulesoft.examples.testing_apikit_with_munit`, main class `TestingApikitWithMunitApplication`.

The project carries these shared components, copied from their reference implementation in [`rest-api-with-apikit-java`](../rest-api-with-apikit-java/) with only the package changed and registered in the root [`DECISIONS.md`](../../DECISIONS.md) (section 4):

| ID | Classes | Provides |
|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` | listener and platform defaults: no-listener 404, method 405, unhandled-exception 500 |
| SC-3 | `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` | the five APIkit error mappings, the type thrown where the original threw APIkit's `NotFoundException`, draft-03 validation of request bodies |
| SC-17 | `controller/ApiKitConsoleController`, `src/main/resources/apikit-console/**` | the APIkit 3.8.0 console and the RAML descriptor |
| ST-1 | `src/test/java/.../support/HttpTestClient` | raw-socket HTTP/1.1 test client that returns the status line with its reason phrase |

`config/ApiKitMethodCaseFilter`, `config/ApiKitAcceptNegotiator` and `config/PreflightDispatchConfig`, the method-token, `Accept` negotiation and CORS pre-flight handling of the RAML-backed projects, are copied from the same project the same way.

## Original example

`testing-apikit-with-munit/src/main/app/api.xml`, the only flow file (`config.resources=api.xml`), has one listener, `0.0.0.0:${http.port}` path `api/*` (`api.xml:9,39`). `mule-app.properties` is empty, so the original fixes no port; its README asks the user to set one, for example 8080. The `api-main` flow hands every request to an `apikit:router` (`api.xml:38-43`) over `api.raml` (RAML 0.8, `title: Sample API`, no `baseUri`, console enabled at `console`). The router's `apikit:config` maps the four methods of `/munit` to four flows (`api.xml:3-8`), each of which only sets a string payload (`api.xml:45-60`). An `apikit:mapping-exception-strategy` answers failures with five fixed JSON bodies (`api.xml:10-36`).

The MUnit suite `src/test/munit/api-test-suite.xml` imports `api.xml`, mocks nothing (`mock-connectors="false" mock-inbounds="false"`) and calls the running API with an `http:request-config` on `localhost:${http.port}`, `basePath="api"`. Its four tests `api-test-get`, `api-test-post`, `api-test-put` and `api-test-delete` assert the status code and the payload of each method (`api-test-suite.xml:15-45`). The original README's Studio procedure (Exchange import, Studio console, MUnit view) is replaced by the Maven and Spring Boot commands of [Build and run](#build-and-run).

## Endpoints

Base URL `http://localhost:8081/api`. The RAML declares no `baseUri` and the original fixes no port, so `http.port` defaults to 8081; start with `--http.port=8080` to match the original README's example. The RAML contract is compared method by method in the root [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) (section 2: 4 of 4 rows match).

| Method | Path | Status | Content-Type | Body | Controller method |
|---|---|---|---|---|---|
| GET | `/api/munit` | 200 | `application/json` | `"GET RESPONSE"` | `MunitController.get` → `MunitService.get` |
| POST | `/api/munit` | 201 | `application/json` | `"POST RESPONSE"` | `MunitController.post` → `MunitService.post` |
| PUT | `/api/munit` | 201 | `application/json` | `"PUT RESPONSE"` | `MunitController.put` → `MunitService.put` |
| DELETE | `/api/munit` | 200 | `application/json` | `"DELETE RESPONSE"` | `MunitController.delete` → `MunitService.delete` |

- **Bodies.** Each body is the flow's literal as a JSON string, double quotes included. The flows set the Strings `GET RESPONSE`, `POST RESPONSE`, `PUT RESPONSE` and `DELETE RESPONSE`, and APIkit converted that String payload to the RAML-declared `application/json`. The committed MUnit suite therefore compares the payload with the literal `"GET RESPONSE"` (its `expectedValue` is `&quot;GET RESPONSE&quot;`, not a `#[...]` MEL expression). `MunitService` returns exactly those bytes, serialized once with Jackson.
- **Statuses.** They come from the RAML: APIkit writes the 2xx a method declares when the flow sets no status (AAP 0.3.3), so DELETE answers 200, not 204. Reason phrases are Undertow's standard `OK` and `Created`.
- **Requests.** The RAML declares no request body, media type, URI or query parameter for `/munit`, so any request body and `Content-Type` is accepted and ignored, as APIkit accepted it, and query parameters are ignored.
- **Undeclared methods.** No RAML method declares HEAD, OPTIONS, PATCH or TRACE. Each gets the APIkit 405; `MunitController.headOrOptions` maps HEAD and OPTIONS explicitly so Spring does not answer them itself. A CORS pre-flight is answered like any other OPTIONS request, with no `Access-Control-*` header (`config/PreflightDispatchConfig`), because the original listener applied no CORS policy.
- **Method case.** Under `/api`, a method token in lower or mixed case (`get`, `Post`) is routed as its upper-case form (`config/ApiKitMethodCaseFilter`), as the APIkit router lower-cased the method before routing.
- **Accept negotiation.** The `Accept` header of each of the four methods is negotiated as the APIkit router negotiated it (`config/ApiKitAcceptNegotiator`, a port of `RestContentTypeParser.bestMatch`). The negotiation runs in each RAML action after its URI and query parameter validation and before the flow, as APIkit did; `/munit` declares no parameter, so in GET it follows the HEAD rejection and elsewhere it is the first step. A header that gives `application/json` quality 0, such as `application/json;q=0`, gets the APIkit 406. `*/*;q=0` is still accepted, because APIkit weighed a `*/*` range with the declared type's own quality.

### APIkit console and RAML descriptor

The original enabled the console (`apikit:config consoleEnabled="true" consolePath="console"`, `api.xml:3`), and its README points users to `http://localhost:8080/api/console`. The conversion carries it as a platform surface (AAP 0.4.5, SC-17); it is not a RAML resource, so the 1:1 diff leaves it out.

| Request | Response |
|---|---|
| any method `/api/console` | 301, `Location: http://<host>/api/console/` (the request's scheme and `Host` header, followed by its query string), empty body |
| any method `/api/console/` or `/api/console/index.html` | the APIkit 3.8.0 console page (`text/html`), its RAML source pointed at `./?raml` and `console-attributes-placeholder` replaced by `disable-raml-client-generator="" disable-theme-switcher=""` |
| `/api/console/<file>` | the console's scripts, styles, fonts and images from `src/main/resources/apikit-console/` (`text/css`, `application/x-javascript`, `image/png`, `image/gif`, `image/svg+xml`, otherwise `application/octet-stream`) |
| GET `/api/console/?raml`, GET `/api/console?raml`, and GET `/api/` with `Accept: application/raml+yaml` | `api/api.raml` as `application/raml+yaml` with `Access-Control-Allow-Origin: *` and a `baseUri` set to the listener URL, for example `baseUri: http://localhost:8081/api` |

- The RAML file itself has no `baseUri`; APIkit added one when it served the descriptor. `ApiKitConsoleController` inserts the line `baseUri: <listener URL>` directly after the `title: Sample API` line and leaves every other byte of the file unchanged.
- The RAML has no `!include`, so no include files are served.
- A miss under the console path is answered with the APIkit 404. The class Javadoc of `ApiKitConsoleController` lists where these bytes are plan decisions rather than recorded original output (for example, the jar's `console/` tree is served rather than `console2/`, and the RAML is served as its raw file rather than re-dumped). Each is a Tier 2A verification point.

## Error responses

| Status | Content-Type | Body (exact bytes) | When | Handler |
|---|---|---|---|---|
| 404 | `application/json` | `{ "message": "Resource not found" }` | a path under `/api` that no resource names, such as `/api/unknown` or `/api/munit/extra`, and a miss under the console path | `ApiKitExceptionHandler` (SC-3) |
| 405 | `application/json` | `{ "message": "Method not allowed" }` | a method `/munit` does not declare: HEAD, OPTIONS, PATCH, TRACE or any other token | `ApiKitExceptionHandler` |
| 415 | `application/json` | `{ "message": "Unsupported media type" }` | a request body whose media type no method consumes | `ApiKitExceptionHandler` |
| 406 | `application/json` | `{ "message": "Not acceptable" }` | an `Accept` header that `application/json` cannot satisfy, such as `Accept: text/xml`, or that gives it quality 0 under APIkit's negotiation (`application/json;q=0`, raised by `config/ApiKitAcceptNegotiator`) | `ApiKitExceptionHandler` |
| 400 | `application/json` | `{ "message": "Bad request" }` | a body failing its schema, or a URI or query parameter outside its constraints | `ApiKitExceptionHandler` |
| 404 | `text/plain` | `No listener for endpoint: <path>` | any path outside `/api`, which the original listener never served | `ApiKitExceptionHandler`, writing SC-1's listener default |
| 500 | `application/json` | `{ "message": "<exception message>" }` | any other unhandled exception | `GlobalExceptionHandler` (SC-1) |

- The first five rows are the original `apikit:mapping-exception-strategy` (`api.xml:10-36`), with its spacing. As in APIkit, a mapping never carries the exception message.
- The RAML declares no request body, media type or parameter for `/munit`, so the 415 and 400 mappings cannot be reached through `/api/munit`. They are carried because the original declares them, and `exception/ApiKitExceptionHandlerTest` and `config/JsonSchemaValidatorTest` cover them.
- The four flows only set literals and cannot fail, so the 500 is reachable only through a platform fault. It and the no-listener 404 are plan defaults rather than recorded original output (AAP 0.4.7), and each is a Tier 2A verification point: a captured fixture that shows different bytes governs.
- Advice precedence: `ApiKitExceptionHandler` (`@Order(Ordered.LOWEST_PRECEDENCE - 1)`), then `GlobalExceptionHandler` last. The original has no exception branch of its own, so the project has no example-specific handler.

## Configuration

Every key of `src/main/resources/application.yml`:

| Key | Default | Original | Notes |
|---|---|---|---|
| `http.port` | `8081` | `http:listener-config port="${http.port}"`; `mule-app.properties` is empty (name unchanged) | listener port, bound by `server.port`; the original fixes none, so 8081 is the plan default (AAP 0.4.9) |
| `server.port` | `${http.port:8081}` | — | set `http.port` rather than this key |
| `apikit.base-path` | `/api` | listener path `api/*` | API base path for the controller, the APIkit mappings, the console and the method-case filter |
| `apikit.raml` | `api/api.raml` | `apikit:config raml="api.raml"` (file under `src/main/api/`) | classpath RAML served by the console and the RAML descriptor |
| `apikit.console-path` | `console` | `apikit:config consolePath="console"` | console path below the base path |
| `spring.application.name` | `testing-apikit-with-munit-java` | — | application name in the log |
| `spring.web.resources.add-mappings` | `false` | — | no static resource handler, so unknown paths reach the APIkit 404 or the no-listener 404 |
| `spring.mvc.dispatch-trace-request` | `true` | — | TRACE reaches the APIkit 405, as the listener declares no `allowedMethods` |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | — | no Boot `/error` endpoint; `/error` gets the no-listener 404 |

- The original uses no secret, host, account or credential, so the configuration has no TODO placeholder, and the project has no `application-test.yml`, `application-it.yml` or other credentials file. No original key was renamed.
- The host is not configured: Spring Boot binds all interfaces, as the original `host="0.0.0.0"` did.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`): `--http.port=8080` or `HTTP_PORT=8080`.

## Mule-to-Java mapping

| Mule construct (`testing-apikit-with-munit`) | Java construct |
|---|---|
| `mule-deploy.properties` (`config.resources=api.xml`) | `TestingApikitWithMunitApplication` (`@SpringBootApplication`) |
| `http:listener-config` `0.0.0.0:${http.port}` + `http:listener` `api/*` (`api.xml:9,39`) | Undertow on `server.port: ${http.port:8081}`, base path `apikit.base-path: /api` |
| `api-main` flow with `apikit:router` and the four `apikit:flow-mapping`s of `/munit` (`api.xml:3-8,38-43`) | `controller/MunitController`: class mapping `/api/munit`, one method per RAML method (`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, each `produces = application/json`), plus `headOrOptions` for the 405 |
| Flows `get:/munit:api-config`, `post:`, `put:`, `delete:` with `set-payload` (`api.xml:45-60`) | `service/MunitService.get`, `post`, `put`, `delete`, returning the constants `GET_RESPONSE`, `POST_RESPONSE`, `PUT_RESPONSE`, `DELETE_RESPONSE` as JSON strings |
| APIkit's conversion of the String payload to the RAML-declared `application/json` | the Jackson serialization in the `MunitService` constructor |
| `apikit:mapping-exception-strategy` and the flow's `exception-strategy ref` (`api.xml:10-36,42`) | `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` (SC-3) |
| HTTP listener defaults outside `/api` | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` (SC-1) |
| Console and RAML descriptor (`consoleEnabled="true" consolePath="console"`) | `controller/ApiKitConsoleController` and `src/main/resources/apikit-console/**` (SC-17) |
| APIkit's case-insensitive method routing; its output negotiation (`RestContentTypeParser.bestMatch`); the listener's absence of a CORS policy | `config/ApiKitMethodCaseFilter`; `config/ApiKitAcceptNegotiator`; `config/PreflightDispatchConfig` |
| `src/main/api/api.raml` | `src/main/resources/api/api.raml`, byte for byte |
| `src/test/munit/api-test-suite.xml` | `src/test/java/.../ApiTestSuiteTest` ([Tests](#tests)) |

## Behaviour kept, stubbed or externalized

- **Behaviour kept.** Every `/munit` method answers its constant payload with its RAML status. The application holds no state and reaches no external system.
- **Stubbed or externalized:** nothing beyond the platform surfaces reproduced above (console, RAML descriptor, APIkit error mappings). The original holds no credential.
- **Not carried:** `src/test/resources/api.raml`, a byte-identical copy of the main RAML, because the converted tests read the application's classpath copy `api/api.raml`; `log4j2.xml`, `log4j2-test.xml` and `mule-project.xml` (AAP 0.2.2).
- **Not applicable:** the Studio and Anypoint content of the original README (Exchange import, Studio console, MUnit view) and the MUnit "musts", which [Tests](#tests) maps to the Spring Boot test context.
- **Known deviations.** The main classes of SC-1, SC-3 and SC-17 are identical to the reference copies apart from the package; inserting `baseUri` into a RAML that has none, and serving no include for a RAML without `!include`, are the reference implementation's own generic behaviour. One test copy is adjusted: in `controller/ApiKitConsoleControllerTest`, `firstRamlIncludeIsServedRelativeToConsole` passes without a request when the RAML declares no `!include`, where the reference copy skips by assumption, because no test of this project may report skipped.

## Build and run

Prerequisites: JDK 17, Maven 3.9.x and access to Maven Central. No Mule runtime, licence or credential is needed. Run from `java-conversions/testing-apikit-with-munit-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, enforcer ban, jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on port 8081
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8080    # port of the original README's example
java -jar target/testing-apikit-with-munit-java-1.0.0-SNAPSHOT.jar --http.port=8080
```

- `mvn verify` runs no live tests: this is not a connector project, so it has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports: `target/surefire-reports/`; JaCoCo in `target/site/jacoco/` (`index.html`, `jacoco.csv`).
- The `maven-enforcer-plugin` bans JAX-WS and CXF artefacts at `validate` (AAP 0.3.2).

Example calls:

```sh
curl -i http://localhost:8081/api/munit
curl -i -X POST http://localhost:8081/api/munit
curl -i -X PUT http://localhost:8081/api/munit
curl -i -X DELETE http://localhost:8081/api/munit
curl -i -H 'Accept: application/raml+yaml' http://localhost:8081/api/
```

Open the console at `http://localhost:8081/api/console/`.

## Tests

`ApiTestSuiteTest` is the MUnit suite `src/test/munit/api-test-suite.xml`, ported one JUnit 5 test per `munit:test` (`api-test-get`, `api-test-post`, `api-test-put`, `api-test-delete`, kept as display names) with the same expected values and failure messages:

| MUnit element (`api-test-suite.xml`) | JUnit 5 equivalent |
|---|---|
| `munit:config mock-connectors="false" mock-inbounds="false"` and `spring:import resource="classpath:api.xml"` (lines 8-11) | `@SpringBootTest(webEnvironment = RANDOM_PORT)`: the Spring Boot test context starts the whole application with nothing mocked, so the MUnit "musts" need no separate equivalent |
| `http:request-config host="localhost" port="${http.port}" basePath="api"` (lines 12-13) | `@LocalServerPort` and ST-1 `HttpTestClient.localhost(port)`, requests to `/api/munit` |
| `munit:set payload="#['']"` and `http:request path="/munit" method="..."` | an empty body (`Content-Length: 0`) sent with the test's method, without `Content-Type` or `Accept` |
| `object-to-string-transformer` | the response body read as a UTF-8 String |
| `munit:assert-on-equals` on `http.status`, message `The HTTP Status code is not correct!` | `assertEquals(200 / 201 / 201 / 200, response.status(), "The HTTP Status code is not correct!")` |
| `munit:assert-payload-equals expectedValue="&quot;GET RESPONSE&quot;"`, message `The response payload is not correct!` | `assertEquals("\"GET RESPONSE\"", payload, "The response payload is not correct!")`, and the same for POST, PUT and DELETE |

| Test class | What it asserts |
|---|---|
| `ApiTestSuiteTest` | the four ported MUnit tests above, against the running application |
| `controller/MunitControllerTest` | on the wire through ST-1, so status code, reason phrase, `Content-Type` and body bytes are asserted: the four RAML 2xx with their JSON-string bodies, POST and PUT with `application/json` and `text/plain` bodies, the APIkit 405 for PATCH, OPTIONS and HEAD, the 406 for `Accept: text/xml`, the 404 under `/api` and the no-listener 404 outside it |
| `service/MunitServiceTest` | the constants equal the original flow literals, each method returns its literal as a JSON string, and a serialization failure surfaces as `IllegalStateException` |
| `controller/ApiKitConsoleControllerTest` | SC-17: the redirect and its `Host` handling, the console page and files, the RAML descriptor with its `baseUri`, and path-traversal probes answered with the 404 |
| `exception/ApiKitExceptionHandlerTest`, `exception/GlobalExceptionHandlerTest` | SC-3's five mappings with exact bodies and their precedence; SC-1's defaults and log safety |
| `config/JsonSchemaValidatorTest` | SC-3 draft-03 validation: body faults give 400, schema faults give 500 |
| `config/ApiKitMethodCaseFilterTest`, `config/ApiKitMethodCaseListenerTest` | lower and mixed-case method tokens under `/api` answered as their upper-case form |
| `config/ApiKitAcceptNegotiatorTest`, `config/ApiKitAcceptNegotiationListenerTest` | APIkit's `Accept` negotiation: the type `RestContentTypeParser.bestMatch` chose for each header, quirks included, and `negotiate` on the handler method Spring selected (method or class `produces`, negated types skipped, the rejection carrying the declared types, a request without a handler method refused); the APIkit 406 on GET, POST, PUT and DELETE for every form that gives JSON quality 0; the flow's status and literal for the forms APIkit accepted (`*/*;q=0`, `application/json;q=0.5`); and unchanged answers with `application/json;q=0` for undeclared methods, unserved paths and the console |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | CORS pre-flights answered as plain OPTIONS requests (the APIkit 405 on `/api/munit`), never running a handler |

`support/HttpTestClient` is ST-1, an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, because `java.net.http.HttpClient` hides the reason phrase.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| testing-apikit-with-munit-java | testing-apikit-with-munit | RAML/APIkit-driven REST API | PASS | PASS | 100.0% | RAML DIFF: 1:1 (Tier 1 static) | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 432 tests, 0 failures, 0 errors, 0 skipped; enforcer `BannedDependencies passed`; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 432 tests, 0 failures, 0 errors, 0 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 13 of 13 lines (`service/MunitService`; the project has no mapper package). Total project LINE coverage: 84.0% (686 of 817 lines).

No Tier 2A fixture set applies, because this is a RAML-backed project without `FixtureParityTest`. Its evidence is the static RAML-to-controller diff in [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) plus the ported MUnit assertions of `ApiTestSuiteTest`. That is Tier 1 evidence, not runtime parity with the original. The same row appears in the root [`SUMMARY.md`](../../SUMMARY.md).

## Licence

Apache License 2.0. `LICENSE` is copied unchanged from `testing-apikit-with-munit/`. `NOTICE` is the original's, extended to credit the APIkit 3.8.0 console files (`org.mule.modules:mule-module-apikit:3.8.0`) carried under `src/main/resources/apikit-console/`.
