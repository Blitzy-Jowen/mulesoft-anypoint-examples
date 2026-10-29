# REST API with APIkit — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 (Undertow) / Maven conversion of the Mule EE 3.8 example [`rest-api-with-apikit`](../../rest-api-with-apikit/) ([original README](../../rest-api-with-apikit/README.md)), category RAML/APIkit-driven REST API. The original folder stays unchanged beside this project as the reference. The API adds, removes and retrieves information about the teams of a league tournament (La Liga), with the league table (positions) and the match fixture. The backend is in memory. Package root `com.mulesoft.examples.rest_api_with_apikit`, main class `RestApiWithApikitApplication`.

This project holds the reference implementation of four shared components, registered in the root [`DECISIONS.md`](../../DECISIONS.md) (section 4) and copied, with only package names changed, into the other RAML-backed or HTTP projects:

| ID | Classes | Provides |
|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` | listener and platform defaults: no-listener 404, method 405, unhandled-exception 500 |
| SC-3 | `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` | the five APIkit error mappings, the type thrown where the original threw APIkit's `NotFoundException`, draft-03 validation of request bodies |
| SC-17 | `controller/ApiKitConsoleController`, `src/main/resources/apikit-console/**` | the APIkit 3.8.0 console and the RAML descriptor |
| ST-1 | `src/test/java/.../support/HttpTestClient` | raw-socket HTTP/1.1 test client that returns the status line with its reason phrase |

## Original example

`rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml` has one listener, `0.0.0.0:${http.port}` path `/api/*` with `http.port=8080` (`mule-app.properties`). It hands every request to an `apikit:router` over `leagues.raml` (RAML 0.8, `baseUri: http://localhost:8080/api`, console enabled at `console`). The router dispatches to nine backend flows, one per RAML resource and method. Each flow calls the Spring bean `org.mule.examples.leagues.LeagueAPI`, and an `apikit:mapping-exception-strategy` answers failures with five fixed JSON bodies. The original README's Studio procedure is replaced by the Maven and Spring Boot commands of [Build, run and test](#build-run-and-test).

## Endpoints and triggers

Base URL `http://localhost:8080/api`, the RAML `baseUri`. Every API request and response body is `application/json`. The RAML contract is compared resource by resource in the root [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) (section 1: 9 of 9 rows match).

| Method | Path | Request | Success | Errors | Controller method |
|---|---|---|---|---|---|
| GET | `/api/teams` | optional query `city`, matched exactly against each team's home city | 200, `{"teams":[…]}` | 406, 405 | `TeamsController.getTeams` |
| POST | `/api/teams` | body validated against `schemas/teams-schema-output.json` (`id` exactly 3 characters) | 201, `Location: http://localhost:<http.port>/api/teams/<id>`, empty body | 409 when the id exists; 400, 415, 405 | `TeamsController.addTeam` |
| GET | `/api/teams/{teamId}` | `teamId` exactly 3 characters | 200, single team with its `matches` played | 404; 400, 406, 405 | `TeamsController.getTeam` |
| PUT | `/api/teams/{teamId}` | body validated against `schemas/teamid-schema-input.json` (`name`, `homeCity`, `stadium`, each optional) | 204, empty body | 404; 400, 415, 405 | `TeamsController.updateTeam` |
| DELETE | `/api/teams/{teamId}` | — | 204, empty body; the team's matches are removed and their results reverted in the standings | 404; 400, 405 | `TeamsController.deleteTeam` |
| GET | `/api/positions` | — | 200, `{"positions":[…]}` in standing order | 406, 405 | `PositionsController.getPositions` |
| GET | `/api/fixture` | — | 200, `{"fixture":[…]}` | 406, 405 | `FixtureController.getFixture` |
| GET | `/api/fixture/{homeTeamId}/{awayTeamId}` | each id exactly 3 characters | 200, single match | 404; 400, 406, 405 | `FixtureController.getMatch` |
| PUT | `/api/fixture/{homeTeamId}/{awayTeamId}` | body validated against `schemas/match-schema-input.json` (`homeTeamScore`, `awayTeamScore` integers) | 204, empty body | 404; 409 when the match date is still in the future; 400, 415, 405 | `FixtureController.updateScore` |

- `city` is read from the raw query string, as the Mule listener built `http.query.params`: absent or written without `=` (`?city`) means no filter, `?city=` filters on the empty string (no seeded team matches), and the last of repeated values wins. Query parameters the RAML does not declare are ignored.
- The POST `Location` is computed from the body's `id` before the team is added, in the original flow's order.
- No RAML resource declares HEAD, OPTIONS, PATCH or TRACE. Every undeclared method gets the APIkit 405, HEAD and OPTIONS included (each controller maps them explicitly so Spring does not answer them itself). A CORS pre-flight is answered like any other OPTIONS request, with no `Access-Control-*` header (`config/PreflightDispatchConfig`), because the original listener applied no CORS policy.
- Under `/api`, a method token in lower or mixed case (`get`, `Post`) is routed as its upper-case form (`config/ApiKitMethodCaseFilter`), as the APIkit router lower-cased the method before routing.
- A `;` in the request path is content of the segment that carries it (`config/ApiKitPathParameterConfig`), as the APIkit router matched it, where Spring MVC would match the segment without its `;` parameters. A literal segment carrying `;`, such as `/api/teams;x=1` or `/api/teams;`, names no resource: every method, a CORS pre-flight included, gets the APIkit 404 and nothing is created. A URI parameter is bound whole and decoded: `/api/teams/BAR;x=1` binds `teamId` `BAR;x=1`, so GET, PUT and DELETE get the APIkit 400 and HEAD and OPTIONS the 405, while `/api/teams/B;x` gets the 404 for an unknown team. `/api;x=1/teams` gets the no-listener 404 naming the path as sent. A `;` in the query string is unaffected. These answers are Tier 2A verification points.
- Under `/api`, the `Accept` header of each GET is negotiated as the APIkit router negotiated it (`config/ApiKitAcceptNegotiator`, a port of `RestContentTypeParser.bestMatch`). The negotiation runs in each RAML action after its URI and query parameter validation and before the flow, as APIkit did. A header that gives `application/json` quality 0, such as `application/json;q=0` or `text/xml, application/json;q=0`, gets the APIkit 406, an unknown team or match included; with a `teamId`, `homeTeamId` or `awayTeamId` that is not 3 characters long it gets the APIkit 400 instead. `*/*;q=0` is still accepted, because APIkit weighed a `*/*` range with the declared type's own quality.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### APIkit console and RAML descriptor

The original enabled the console (`apikit:config consoleEnabled="true" consolePath="console"`). The conversion carries it as a platform surface (AAP 0.4.5, SC-17). It is not a RAML resource, so the 1:1 diff leaves it out.

| Request | Response |
|---|---|
| any method `/api/console` | 301, `Location: http://<host>/api/console/` (the request's scheme and `Host` header, followed by its query string), empty body |
| any method `/api/console/` or `/api/console/index.html` | the APIkit 3.8.0 console page (`text/html`), its RAML source pointed at `./?raml` and `console-attributes-placeholder` replaced by `disable-raml-client-generator="" disable-theme-switcher=""` |
| `/api/console/<file>` | the console's scripts, styles, fonts and images from `src/main/resources/apikit-console/` (`text/css`, `application/x-javascript`, `image/png`, `image/gif`, `image/svg+xml`, otherwise `application/octet-stream`) |
| GET `/api/console/?raml`, GET `/api/console?raml`, and GET `/api/` with `Accept: application/raml+yaml` | `leagues.raml` as `application/raml+yaml` with `Access-Control-Allow-Origin: *` and its `baseUri` rewritten to the listener URL |
| GET `/api/<include>` and GET `/api/console/<include>` | each file the RAML `!include`s (`schemas/*.json`, `examples/*.json`), served relative to the RAML |

A miss under the console path is answered with the APIkit 404, and so is every method but GET and HEAD on a RAML include (OPTIONS and a CORS pre-flight included). The class Javadoc of `ApiKitConsoleController` lists where these bytes are plan decisions rather than recorded original output (for example, the jar's `console/` tree is served rather than `console2/`, and the RAML is served as its raw file rather than re-dumped). Each is a Tier 2A verification point.

### Error responses

| Status | Content-Type | Body (exact bytes) | When | Handler |
|---|---|---|---|---|
| 404 | `application/json` | `{ "message": "Resource not found" }` | unknown team or match (`Team <id> does not exist`, `There is no match between team <h> and team <a>`), or a path under `/api` that no resource names | `ApiKitExceptionHandler` (SC-3) |
| 405 | `application/json` | `{ "message": "Method not allowed" }` | a method the resource does not declare | `ApiKitExceptionHandler` |
| 415 | `application/json` | `{ "message": "Unsupported media type" }` | a POST or PUT body that is not `application/json` | `ApiKitExceptionHandler` |
| 406 | `application/json` | `{ "message": "Not acceptable" }` | an `Accept` header that excludes `application/json`, or gives it quality 0 under APIkit's negotiation (`application/json;q=0`, raised by `config/ApiKitAcceptNegotiator`) | `ApiKitExceptionHandler` |
| 400 | `application/json` | `{ "message": "Bad request" }` | a URI parameter that is not exactly 3 characters, or a missing, malformed or schema-violating body | `ApiKitExceptionHandler` |
| 409 | `application/json` | `{ "message": "<exception message>" }` | `There is already a team with id <id>` (POST `/api/teams`); `The match between team <h> and team <a> has not been played yet` (PUT `/api/fixture/{h}/{a}`) | `LeagueExceptionHandler` |
| 404 | `text/plain` | `No listener for endpoint: <path>` | any path outside `/api` | `ApiKitExceptionHandler`, writing SC-1's listener default |
| 500 | `application/json` | `{ "message": "<exception message>" }` | any other unhandled exception | `GlobalExceptionHandler` (SC-1) |

- The first five rows are the original `apikit:mapping-exception-strategy` (`rest-api-with-apikit.xml:18-44`), with its spacing. As in APIkit, the mapping never carries the exception message.
- **409 decision.** The RAML declares 409 for POST `/teams` and PUT `/fixture/{homeTeamId}/{awayTeamId}`, and the original README describes a 409 exception strategy. The original flow, however, maps only the five codes above. The RAML contract governs, so the ported `ConflictException` is answered with 409. This is recorded in [`DECISIONS.md`](../../DECISIONS.md) section 6.1 and shown as a RAML-governed row in [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md).
- The 409 body, the no-listener 404 and the 500 are plan defaults rather than recorded original output (AAP 0.4.7). Each is a Tier 2A verification point: a captured fixture that shows different bytes governs.
- Advice precedence: `LeagueExceptionHandler` (`@Order(0)`), then `ApiKitExceptionHandler`, then `GlobalExceptionHandler` last.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to:

| Key | Type | Default | Original | Notes |
|---|---|---|---|---|
| `http.port` | `int` | `8080` | `http.port=8080` in `mule-app.properties` (name unchanged) | listener port, bound by `server.port`; the POST `Location` header uses the running server's port and falls back to this value |
| `server.port` | `Integer` | `${http.port:8080}` | `http:listener-config port="${http.port}"` | set `http.port` rather than this key |
| `apikit.base-path` | `String` | `/api` | listener path `/api/*` | API base path for the APIkit mappings, the console and the method-case filter |
| `apikit.raml` | `String` | `leagues.raml` | `apikit:config raml="leagues.raml"` | classpath RAML read by the console and the schema validator |
| `apikit.console-path` | `String` | `console` | `apikit:config consolePath="console"` | console path below the base path |
| `spring.web.resources.add-mappings` | `boolean` | `false` | — | no static resource handler, so unknown paths reach the APIkit 404 or the no-listener 404 |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | — | TRACE reaches the APIkit 405, as the listener declares no `allowedMethods` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | — | no Boot `/error` endpoint; `/error` gets the no-listener 404 |

- The original uses no secret, host, account or credential, so the configuration has no TODO placeholder, and the project has no `application-test.yml` or `application-it.yml`. No original key was renamed.
- The host is not configured: Spring Boot binds all interfaces, as the original `host="0.0.0.0"` did.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`): `--http.port=9090` or `HTTP_PORT=9090`.

## Mule-to-Java mapping

| Mule construct (`rest-api-with-apikit`) | Java construct |
|---|---|
| `http:listener` `/api/*` + `apikit:router` + `apikit:config` (`rest-api-with-apikit.xml:3-13,17,45-49`) | `controller/TeamsController`, `PositionsController`, `FixtureController` on Undertow (one controller per RAML resource); RAML URI-parameter constraints as `@Size(min = 3, max = 3)`; body schemas through `config/JsonSchemaValidator` |
| Nine backend flows (`get:/teams`, `post:/teams`, `get\|put\|delete:/teams/{teamId}`, `get:/positions`, `get:/fixture`, `get\|put:/fixture/{homeTeamId}/{awayTeamId}`) | one controller method each, calling `service/LeagueAPI` |
| `spring:bean` `org.mule.examples.leagues.LeagueAPI` with `init-method="initialize"` | `service/LeagueAPI` (`@Service`, `initialize` as `@PostConstruct`), logic unchanged; it throws `ApiKitNotFoundException` where the original threw APIkit's `NotFoundException`, and its public methods are `synchronized` because concurrent requests share the league's `HashMap`s |
| `model/*`, `request/*`, `response/*` Java classes | `model/League`, `Match`, `Team`; `model/request/Score`, `Team`, `UpdateTeam`; `model/response/Fixture`, `Match`, `Position`, `Positions`, `Team`, `Teams` (Mule `@Transformer` methods removed) |
| Mule auto object-to-JSON (codehaus Jackson) and the `toJson` `@Transformer`s; `serializer/JsonDateSerializer` | `mapper/LeagueJsonMapper`; `mapper/JsonDateSerializer` (fasterxml) |
| APIkit `PayloadNormalizerTransformer` on POST and PUT bodies | `LeagueJsonMapper.readTeam`, `readUpdateTeam`, `readScore` |
| `exceptions/ConflictException` (extends APIkit `MuleRestException`); APIkit `NotFoundException` | `exception/ConflictException` (extends `RuntimeException`); `exception/ApiKitNotFoundException` |
| `apikit:mapping-exception-strategy` (`rest-api-with-apikit.xml:18-44`) | `exception/ApiKitExceptionHandler`; 409 in `exception/LeagueExceptionHandler` |
| Console and RAML descriptor (`consoleEnabled="true"`) | `controller/ApiKitConsoleController` and `src/main/resources/apikit-console/**` |
| `src/main/resources/leagues.raml`, `schemas/*.json`, `examples/*.json`, `teams.json` | copied byte for byte to the same relative paths in `src/main/resources/` |

## Behaviours stubbed or externalized

- **League data.** At start-up `LeagueAPI` loads the 20 teams of `teams.json` and builds a double round robin of 380 matches. Each pairing has a first-round leg dated 7 or 6 days before start-up, with random scores of 0 to 4 goals, and an unplayed second-round leg dated 1 day before or 1 day after start-up. Every match is at 18:00 local time. State lives in memory and is lost on restart, as in the original Spring bean. Scores and standings therefore differ from run to run.
- **JSON renderings** (`mapper/LeagueJsonMapper`), both compact, with properties in field-declaration order:
  - Single team and single match, as the original's automatic object-to-JSON transformer wrote them: null fields are omitted, and the match `date` is a string in `yyyy-MM-dd'T'HH:mm:ssZ` (JVM default time zone) from `JsonDateSerializer`.
  - The `teams`, `positions` and `fixture` collections, as the wrappers' own `toJson` transformers wrote them with a plain Jackson mapper: nulls are kept (for example `"matches":null` on every listed team and `null` scores of unplayed matches), and dates are epoch milliseconds.
- **Not carried:** the `toXml` transformers of `Teams`, `Positions` and `Fixture`, because the RAML declares only `application/json`.
- **Not applicable:** the Studio and Anypoint content of the original README (Exchange import, Studio console, documentation export).
- **Stubbed or externalized:** nothing. The original reaches no external system and holds no credential.
- **Tier 2A verification points** beyond the error defaults above: a body with a property its schema does not list passes validation but is rejected by the reader with the generic 500 (`LeagueJsonMapper` Javadoc), and the console variations listed in `ApiKitConsoleController`.

## Build, run and test

Prerequisites: JDK 17, Maven 3.9.x and access to Maven Central. No Mule runtime, licence or credential is needed. Run from `java-conversions/rest-api-with-apikit-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, enforcer ban, jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on port 8080
java -jar target/rest-api-with-apikit-java-1.0.0-SNAPSHOT.jar --http.port=9090
```

- `mvn verify` runs no live tests: this is not a connector project, so it has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports: `target/surefire-reports/`; JaCoCo in `target/site/jacoco/` (`index.html`, `jacoco.csv`).
- The `maven-enforcer-plugin` bans JAX-WS and CXF artefacts at `validate` (AAP 0.3.2).

Example calls:

```sh
curl -i 'http://localhost:8080/api/teams?city=Madrid'
curl -i http://localhost:8080/api/teams/BAR
curl -i -X POST -H 'Content-Type: application/json' \
     -d '{"id":"NEW","name":"New Team","homeCity":"Valencia","stadium":"New Stadium"}' \
     http://localhost:8080/api/teams
curl -i -H 'Accept: application/raml+yaml' http://localhost:8080/api/
```

Open the console at `http://localhost:8080/api/console/`.

## Tests

| Test class | What it asserts |
|---|---|
| `RestApiWithApiKitTest` | the ported original test (`rest-api-with-apikit/src/test/java/org/mule/examples/RestApiWithApiKitTest.java:81-101`): GET `/api/teams/BAR` equals `src/test/resources/reply.json` as a JSON tree, then DELETE `/api/teams/BAR` answers 204 |
| `controller/TeamsControllerTest`, `PositionsControllerTest`, `FixtureControllerTest` | on the wire through ST-1, so status code, reason phrase, `Content-Type`, `Location` and body bytes are asserted: every RAML method's 2xx, the APIkit 404, 405, 415, 406 and 400 bodies as exact strings, the 409s, both renderings of a match, the no-listener 404 outside `/api`, and concurrent POSTs of one id creating exactly one team |
| `controller/ApiKitConsoleControllerTest` | SC-17: the redirect, console page and files, RAML descriptor with rewritten `baseUri`, includes, and path-traversal probes answered with the 404; at `<api base>/<include>`, each RAML include served to GET with its bytes and to HEAD with an empty 200, and every other method (OPTIONS, POST, PUT, DELETE, PATCH, PROPFIND and TRACE are sent) and CORS pre-flights asking for GET or POST answered with the APIkit 404 and no `Allow`, `Access-Control-*` or `Vary` header |
| `service/LeagueAPITest` | every `LeagueAPI` operation, both not-found and both conflict messages, the 20-team, 380-match fixture and standings computed from it |
| `mapper/LeagueJsonMapperTest`, `mapper/JsonDateSerializerTest` | both renderings (JSONAssert `STRICT`), the three body readers, the date pattern and thread safety |
| `exception/ApiKitExceptionHandlerTest`, `LeagueExceptionHandlerTest`, `GlobalExceptionHandlerTest` | SC-3 mappings and precedence, the 409, SC-1 defaults and log safety |
| `config/JsonSchemaValidatorTest` | SC-3 draft-03 validation: body faults give 400, schema faults give 500 |
| `config/ApiKitMethodCaseFilterTest`, `ApiKitMethodCaseListenerTest` | lower and mixed-case method tokens under `/api` answered as their upper-case form |
| `config/ApiKitPathParameterConfigTest`, `ApiKitPathParameterListenerTest` | `;` in the path as segment content: the parsed path the handler mapping leaves and the URI variables the RAML templates bind from it; on the wire, the APIkit 404 for every method, header combination and CORS pre-flight on `/api/teams;x=1`, `/api/teams;`, `/api/positions;x=1`, `/api/fixture;x=1` and `/api/console;x=1`, the APIkit 400 for URI parameters bound with their `;` (`BAR;x=1`, `BAR;`, `;x=1`, `BAR;a`), the 405 for HEAD, OPTIONS and PATCH on them, the no-listener 404 for `/api;x=1/teams`, the resource's answer for a `;` in the query, and no team created, changed or deleted |
| `config/ApiKitAcceptNegotiatorTest`, `ApiKitAcceptNegotiationListenerTest` | APIkit's `Accept` negotiation: the type `RestContentTypeParser.bestMatch` chose for each header, quirks included, and `negotiate` on the handler method Spring selected (method or class `produces`, negated types skipped, the rejection carrying the declared types, a request without a handler method refused); the APIkit 406 on the five GETs for every form that gives JSON quality 0; the APIkit 400, exactly as without `Accept`, for those forms with a URI parameter that is not 3 characters long (`/api/teams/BA`, `/api/teams/TOOLONG`, `/api/fixture/BA/MAL`, `/api/fixture/VIL/TOOLONG`); the 200 for the forms APIkit accepted (`*/*;q=0`, `application/json;q=0.5`); and unchanged answers with `application/json;q=0` for the writes, undeclared methods, unserved paths and the console |
| `config/PreflightDispatchConfigTest`, `PreflightDispatchListenerTest` | CORS pre-flights answered as plain OPTIONS requests, never running a write handler |

`support/HttpTestClient` is ST-1, an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, because `java.net.http.HttpClient` hides the reason phrase.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| rest-api-with-apikit-java | rest-api-with-apikit | RAML/APIkit-driven REST API | PASS | PASS | 100.0% | RAML DIFF: 1:1 (Tier 1 static) | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 764 tests, 0 failures, 0 errors, 0 skipped; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 764 tests, 0 failures, 0 errors, 0 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 131 of 131 lines (`service/LeagueAPI`, `mapper/LeagueJsonMapper`, `mapper/JsonDateSerializer`). Total project LINE coverage: 89.8% (1220 of 1358 lines).

No Tier 2A fixture set applies, because this is a RAML-backed project. Its evidence is the static RAML-to-controller diff in [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) plus the ported original assertions of `RestApiWithApiKitTest`. That is Tier 1 evidence, not runtime parity with the original. The same row appears in the root [`SUMMARY.md`](../../SUMMARY.md).

## Licence

Apache License 2.0. `LICENSE` is copied unchanged from `rest-api-with-apikit/`. `NOTICE` is the original's, extended to credit the APIkit 3.8.0 console files (`org.mule.modules:mule-module-apikit:3.8.0`) carried under `src/main/resources/apikit-console/`.
