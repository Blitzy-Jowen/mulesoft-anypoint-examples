# RAML-to-Controller Diff

This file compares the RAML contract of each delivered RAML-backed example with the Spring Boot controllers that replace its APIkit router. For each project, every resource and method in the original RAML is compared with the controller method that serves it. The five APIkit error mappings of each original flow are compared with the converted `ApiKitExceptionHandler` (shared component SC-3).

- **Evidence tier:** Tier 1, static (AAP 0.8.1). Every row was read from three sources: the original RAML, the original flow XML and the converted Java sources. No row depends on a request to an original or a converted application. Tier 1 never reports runtime parity, so this file is not Tier 2A or Tier 2B evidence.
- **Expected result:** 1:1. Every RAML resource and method has exactly one controller method. That method has the same path, method, parameter constraints, defaults, enumerations, request media type and schema file, and it can produce every status the RAML declares.
- **Obtained result:** 1:1 in all four projects. There are 17 rows and 0 ✗.

## Result

| Project | Original folder | RAML resource/method pairs | ✓ | ✗ | Label |
|---|---|---|---|---|---|
| `rest-api-with-apikit-java` | `rest-api-with-apikit` | 9 | 9 | 0 | `RAML DIFF: 1:1 (Tier 1 static)` |
| `testing-apikit-with-munit-java` | `testing-apikit-with-munit` | 4 | 4 | 0 | `RAML DIFF: 1:1 (Tier 1 static)` |
| `processing-orders-with-dataweave-and-APIkit-java` | `processing-orders-with-dataweave-and-APIkit` | 1 | 1 | 0 | `RAML DIFF: 1:1 (Tier 1 static)` |
| `netsuite-data-retrieval-java` | `netsuite-data-retrieval` | 3 | 3 | 0 | `RAML DIFF: 1:1 (Tier 1 static)` |
| — | `sap-data-retrieval` | — | — | — | `EXCLUDED — pending sapjco3.jar` |

## How to read the tables

- **Left-hand columns** come from the original RAML and are cited as `<file>:<line>`. Each converted project carries a byte-identical copy of its RAML (checked with `cmp`), so the line numbers hold for both copies.
- **Controller class#method** is relative to the project's package root `src/main/java/com/mulesoft/examples/<package>/`. **Mapping annotation** shows the class-level and method-level annotations with their constants and placeholders resolved, so together they give the full path including `/api`.
- **Validation applied** is what the converted code enforces before the service runs. APIkit 3.8 validated requests, never responses. Neither side validates a response against its RAML schema or example.
- **Statuses the method and handlers can return** lists the success status first, then each error status labelled by its source:
  - *RAML*: declared for the method.
  - *APIkit mapping*: one of the five mappings in the closing table. APIkit returned these on every resource, whether or not the RAML lists them, so they are not mismatches.
  - *RAML-governed*: the `rest-api-with-apikit` 409 (section 1, note 3).
  - *connector*: the approved connector failure modes (AAP 0.4.8).
  - *platform*: the SC-1 `GlobalExceptionHandler` 500, `{ "message": "<exception message>" }`.
- **Common to every row, not repeated in the tables:**
  - No RAML declares HEAD or OPTIONS. Each resource has an explicit `@RequestMapping(method = {HEAD, OPTIONS})` handler, and the GET methods of the MUnit, currency and NetSuite controllers also guard against HEAD. Either way the request raises `HttpRequestMethodNotSupportedException` and gets the APIkit 405.
  - Any other undeclared method gets the same 405.
  - Query parameters the RAML does not declare are ignored, as APIkit ignored them.
  - Under `/api`, a path that no RAML resource names gets the APIkit 404.
- **Match**: ✓ means the row agrees on path, method, every parameter constraint, default and enumeration, the request media type and schema file, and every declared status. Each of these was read in the code. A ✗ would name the difference.

## 1. `rest-api-with-apikit` → `rest-api-with-apikit-java`

| Item | Value |
|---|---|
| RAML file | `rest-api-with-apikit/src/main/resources/leagues.raml` → `java-conversions/rest-api-with-apikit-java/src/main/resources/leagues.raml`. `cmp`: identical, as is every included `schemas/*.json` and `examples/*.json` |
| RAML version, title, version | `#%RAML 0.8`, `La Liga`, `1.0` |
| `baseUri` | `http://localhost:8080/api` |
| Original listener | `/api/*` on `${http.port}` = 8080, host `0.0.0.0` (`rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:17,46`, `rest-api-with-apikit/src/main/app/mule-app.properties:1`). APIkit config `leagues-config`, with 9 flow mappings and the console enabled (`rest-api-with-apikit.xml:3-13`) |
| Spring base path | Undertow on `server.port: ${http.port:8080}` with `http.port: 8080`. The resource paths are the literal constants `/api/teams`, `/api/teams/{teamId}`, `/api/positions`, `/api/fixture` and `/api/fixture/{homeTeamId}/{awayTeamId}`. `apikit.base-path: /api` and `apikit.raml: leagues.raml` (`src/main/resources/application.yml`) |
| Body validation | `config/JsonSchemaValidator` (SC-3): JSON Schema draft-03, with each schema resolved relative to the classpath directory of `leagues.raml` |
| Sources read | `controller/TeamsController.java`, `controller/PositionsController.java`, `controller/FixtureController.java`, `service/LeagueAPI.java`, `exception/ApiKitExceptionHandler.java`, `exception/LeagueExceptionHandler.java`, `config/JsonSchemaValidator.java` |

| RAML path | Method | URI parameters (type, constraints) | Query parameters (type, required, default, enum) | Request media type and schema file | Declared statuses and response media type/schema | Controller class#method | Mapping annotation (full path incl. `/api`) | Validation applied | Statuses the method and handlers can return | Match (✓ or ✗ with reason) |
|---|---|---|---|---|---|---|---|---|---|---|
| `/teams` | GET (`leagues.raml:14-28`) | — | `city`: `string`, `required: false`, no default (`:16-22`) | — | 200 `application/json`, schema `schemas/teams-schema-input.json` (`:24-28`) | `TeamsController#getTeams` → `LeagueAPI.getTeams(city)` (`TeamsController.java:126-133`) | `@GetMapping(value = "/api/teams", produces = "application/json")` | `city` is read from the raw query string, as the Mule listener built `http.query.params` (`null` when absent). The RAML sets no constraint to enforce | 200 (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/teams` | POST (`:29-45`) | — | — | `application/json`, schema `schemas/teams-schema-output.json` (`:31-34`) | 201 with `Location` header (`string`); 409 (`:35-45`) | `TeamsController#addTeam` → `LeagueAPI.addTeam(team)` (`TeamsController.java:146-155`) | `@PostMapping(value = "/api/teams", consumes = "application/json")` | `JsonSchemaValidator.validate("schemas/teams-schema-output.json", body)`. A missing or unreadable body gives 400. Another body media type gives 415 | 201, `Location: http://localhost:<port>/api/teams/<id>`, empty body (RAML; note 1); 409 (RAML-governed; note 3); 400, 415, 405 (APIkit mapping); 500 (platform) | ✓ (409 RAML-governed) |
| `/teams/{teamId}` | GET (`:58-68`) | `teamId`: `string`, `minLength: 3`, `maxLength: 3` (`:50-57`) | — | — | 200 `application/json`, schema `schemas/teamid-schema-output.json`; 404 (`:60-68`) | `TeamsController#getTeam` → `LeagueAPI.getTeam(teamId)` (`TeamsController.java:166-173`) | `@GetMapping(value = "/api/teams/{teamId}", produces = "application/json")` | `@PathVariable("teamId") @Size(min = 3, max = 3)`. A violation raises `HandlerMethodValidationException`, which gives 400 | 200 (RAML); 404 `ApiKitNotFoundException` "Team <id> does not exist" (RAML, APIkit body); 400, 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/teams/{teamId}` | PUT (`:69-82`) | `teamId` as above | — | `application/json`, schema `schemas/teamid-schema-input.json` (`:70-74`) | 204; 404 (`:76-82`) | `TeamsController#updateTeam` → `LeagueAPI.updateTeam(teamId, updateTeam)` (`TeamsController.java:182-188`) | `@PutMapping(value = "/api/teams/{teamId}", consumes = "application/json")` | `@Size(min = 3, max = 3)` on `teamId`, plus `JsonSchemaValidator.validate("schemas/teamid-schema-input.json", body)` | 204, empty body (RAML); 404 (RAML, APIkit body); 400, 415, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/teams/{teamId}` | DELETE (`:83-91`) | `teamId` as above | — | — | 204; 404 (`:85-91`) | `TeamsController#deleteTeam` → `LeagueAPI.deleteTeam(teamId)` (`TeamsController.java:196-200`) | `@DeleteMapping("/api/teams/{teamId}")` | `@Size(min = 3, max = 3)` on `teamId` | 204, empty body (RAML); 404 (RAML, APIkit body); 400, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/positions` | GET (`:94-101`) | — | — | — | 200 `application/json`, schema `schemas/positions-output.json` (`:96-101`) | `PositionsController#getPositions` → `LeagueAPI.getPositions()` (`PositionsController.java:71-78`) | `@GetMapping(value = "/api/positions", produces = "application/json")` | none declared | 200 (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/fixture` | GET (`:106-113`) | — | — | — | 200 `application/json`, schema `schemas/fixture-schema-output.json` (`:108-113`) | `FixtureController#getFixture` → `LeagueAPI.getFixture()` (`FixtureController.java:109-116`) | `@GetMapping(value = "/api/fixture", produces = "application/json")` | none declared | 200 (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/fixture/{homeTeamId}/{awayTeamId}` | GET (`:130-137`) | `homeTeamId`, `awayTeamId`: each `string`, `minLength: 3`, `maxLength: 3` (`:117-129`) | — | — | 200 `application/json`, schema `schemas/match-schema-output.json`. No other status is declared (`:132-137`) | `FixtureController#getMatch` → `LeagueAPI.getMatch(homeTeamId, awayTeamId)` (`FixtureController.java:129-138`) | `@GetMapping(value = "/api/fixture/{homeTeamId}/{awayTeamId}", produces = "application/json")` | `@Size(min = 3, max = 3)` on each `@PathVariable` | 200 (RAML); 404 "There is no match between team <h> and team <a>" (APIkit mapping; note 4); 400, 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/fixture/{homeTeamId}/{awayTeamId}` | PUT (`:138-158`) | as above | — | `application/json`, schema `schemas/match-schema-input.json` (`:140-147`) | 204; 404; 409 (`:149-158`) | `FixtureController#updateScore` → `LeagueAPI.updateScore(homeTeamId, awayTeamId, score)` (`FixtureController.java:148-156`) | `@PutMapping(value = "/api/fixture/{homeTeamId}/{awayTeamId}", consumes = "application/json")` | `@Size(min = 3, max = 3)` on each `@PathVariable`, plus `JsonSchemaValidator.validate("schemas/match-schema-input.json", body)` | 204, empty body (RAML); 404 (RAML, APIkit body); 409 (RAML-governed; note 3); 400, 415, 405 (APIkit mapping); 500 (platform) | ✓ (409 RAML-governed) |

Notes:

1. **POST `Location`:** The original set `http://localhost:${http.port}/api/teams/#[payload.id]` before it added the team (`rest-api-with-apikit.xml:78-83`). `addTeam` keeps that order. It writes the running server's port (`local.server.port`), and falls back to `http.port` (`TeamsController.java:150,241-243`).
2. **Schema file names:** Each schema file is recorded exactly as the RAML names it. The RAML's naming runs against the direction of the body. The GET `/teams` response names `teams-schema-input.json`, while the POST body names `teams-schema-output.json`. The GET `/teams/{teamId}` response names `teamid-schema-output.json`, while the PUT body names `teamid-schema-input.json`. The controllers validate bodies against these same files (`TeamsController.java:83,85`; `FixtureController`'s `SCORE_SCHEMA`).
3. **409, RAML-governed:** These two rows follow the RAML contract, not the original runtime.
   - The RAML declares 409 for POST `/teams` (`leagues.raml:43`) and for PUT `/fixture/{homeTeamId}/{awayTeamId}` (`leagues.raml:156`). The README describes a 409 exception strategy (`rest-api-with-apikit/README.md:56`).
   - The flow's `apikit:mapping-exception-strategy` maps only the five APIkit codes (`rest-api-with-apikit.xml:18-44`), so the original runtime did not produce this 409 body.
   - The RAML contract governs (AAP 0.7.1, 0.10.2, `DECISIONS.md` §6.1). `exception/LeagueExceptionHandler` (`@Order(0)`, `@ExceptionHandler(ConflictException.class)`) answers 409 `application/json` `{ "message": "<exception message>" }`. The two messages are "There is already a team with id <id>" (`LeagueAPI.java:152`) and "The match between team <h> and team <a> has not been played yet" (`LeagueAPI.java:175`).
   - The 409 body is a plan default and a Tier 2A verification point.
4. **404 on GET `/fixture/{homeTeamId}/{awayTeamId}`:** The RAML declares only 200 for this method. `LeagueAPI.getMatch` throws `ApiKitNotFoundException` (`LeagueAPI.java:78`) where the original threw APIkit's `NotFoundException`, so the APIkit 404 is the status the original also returned.
5. **Platform surfaces:** SC-17 `controller/ApiKitConsoleController` carries the APIkit console and RAML descriptor. These are `GET /api/console` (301 to `/api/console/`), `/api/console/`, `/api/console/?raml`, and `GET /api/` with `Accept: application/raml+yaml`. They are platform-generated surfaces of `consoleEnabled="true" consolePath="console"` (AAP 0.4.5), not RAML resources, and are outside the 1:1 comparison.

## 2. `testing-apikit-with-munit` → `testing-apikit-with-munit-java`

| Item | Value |
|---|---|
| RAML file | `testing-apikit-with-munit/src/main/api/api.raml` → `java-conversions/testing-apikit-with-munit-java/src/main/resources/api/api.raml`. `cmp`: identical. The original's test copy `src/test/resources/api.raml` is also identical |
| RAML version, title, version | `#%RAML 0.8`, `Sample API`, none |
| `baseUri` | none |
| Original listener | `api/*` on `${http.port}`, host `0.0.0.0` (`testing-apikit-with-munit/src/main/app/api.xml:9,39`). `mule-app.properties` is empty, so no port is fixed. APIkit config `api-config`, with 4 flow mappings and the console enabled (`api.xml:3-8`). The MUnit suite calls `/api/munit`: base path `api` plus path `/munit` (`testing-apikit-with-munit/src/test/munit/api-test-suite.xml:12,17`) |
| Spring base path | Undertow on `server.port: ${http.port:8081}`. Class-level `@RequestMapping("${apikit.base-path:/api}/munit")` with `apikit.base-path: /api` gives `/api/munit`. `apikit.raml: api/api.raml` |
| Sources read | `controller/MunitController.java`, `service/MunitService.java`, `exception/ApiKitExceptionHandler.java` |

| RAML path | Method | URI parameters (type, constraints) | Query parameters (type, required, default, enum) | Request media type and schema file | Declared statuses and response media type/schema | Controller class#method | Mapping annotation (full path incl. `/api`) | Validation applied | Statuses the method and handlers can return | Match (✓ or ✗ with reason) |
|---|---|---|---|---|---|---|---|---|---|---|
| `/munit` | GET (`api.raml:7-12`) | — | — | — | 200 `application/json`, no schema | `MunitController#get` → `MunitService.get()` (`MunitController.java:67-79`) | `@RequestMapping("/api/munit")` + `@GetMapping(produces = "application/json")` | none declared | 200, body `"GET RESPONSE"` (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/munit` | POST (`:13-18`) | — | — | none declared, so any body is accepted and ignored | 201 `application/json`, no schema | `MunitController#post` → `MunitService.post()` (`MunitController.java:89-95`) | `@RequestMapping("/api/munit")` + `@PostMapping(produces = "application/json")` | none declared | 201, body `"POST RESPONSE"` (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/munit` | PUT (`:19-24`) | — | — | none declared, so any body is accepted and ignored | 201 `application/json`, no schema | `MunitController#put` → `MunitService.put()` (`MunitController.java:105-111`) | `@RequestMapping("/api/munit")` + `@PutMapping(produces = "application/json")` | none declared | 201, body `"PUT RESPONSE"` (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |
| `/munit` | DELETE (`:25-30`) | — | — | — | 200 `application/json`, no schema | `MunitController#delete` → `MunitService.delete()` (`MunitController.java:121-127`) | `@RequestMapping("/api/munit")` + `@DeleteMapping(produces = "application/json")` | none declared | 200, body `"DELETE RESPONSE"` (RAML); 406, 405 (APIkit mapping); 500 (platform) | ✓ |

Notes:

1. **Listener path:** The original listener path `api/*` has no leading slash. The Mule 3.8 listener adds the slash, which is why the MUnit suite reaches `/api/munit`. The Spring path is therefore the same.
2. **Response bodies:** The flows set `GET RESPONSE`, `PUT RESPONSE`, `DELETE RESPONSE` and `POST RESPONSE` (`api.xml:45-60`). The MUnit suite asserts the statuses 200, 201, 201, 200 and the JSON-string payloads `"GET RESPONSE"`, `"POST RESPONSE"`, `"PUT RESPONSE"`, `"DELETE RESPONSE"` (`api-test-suite.xml:19-44`). `MunitService` returns those JSON strings, quotes included.
3. **Platform surfaces:** SC-17 `controller/ApiKitConsoleController` carries the APIkit console and RAML descriptor. These are `GET /api/console`, `/api/console/`, `/api/console/?raml`, and `GET /api/` with `Accept: application/raml+yaml`. They are platform-generated (AAP 0.4.5) and outside the 1:1 comparison.

## 3. `processing-orders-with-dataweave-and-APIkit` → `processing-orders-with-dataweave-and-APIkit-java`

| Item | Value |
|---|---|
| RAML file | `processing-orders-with-dataweave-and-APIkit/src/main/api/currency.raml` → `java-conversions/processing-orders-with-dataweave-and-APIkit-java/src/main/resources/api/currency.raml`. `cmp`: identical, as is the included `currency-schema.json` |
| RAML version, title, version | `#%RAML 0.8`, `Currency API`, none |
| `baseUri` | `http://localhost:8081/api/` |
| Original listener | `/api/*` on `${http.port}`, host `0.0.0.0` (`processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:3,33`). `mule-app.properties` is empty. APIkit config `currency-config` has the console enabled and no explicit flow mapping: APIkit routed by the flow name `get:/currencies:currency-config` (`currency.xml:4,37-39`) |
| Spring base path | Undertow on `server.port: ${http.port:8081}`. The method path `${apikit.base-path:/api}/currencies`, with `apikit.base-path: /api`, gives `/api/currencies`. `apikit.raml: api/currency.raml` |
| Sources read | `controller/CurrencyController.java`, `service/CurrencyService.java`, `exception/ApiKitExceptionHandler.java` |

| RAML path | Method | URI parameters (type, constraints) | Query parameters (type, required, default, enum) | Request media type and schema file | Declared statuses and response media type/schema | Controller class#method | Mapping annotation (full path incl. `/api`) | Validation applied | Statuses the method and handlers can return | Match (✓ or ✗ with reason) |
|---|---|---|---|---|---|---|---|---|---|---|
| `/currencies` | GET (`currency.raml:6-13`) | — | — | — | 200 `application/json`, schema `currency-schema.json` | `CurrencyController#getCurrencies` → `CurrencyService.getCurrencies()`, which renders `currency.json` through SC-14 `ParseTemplateRenderer` (`CurrencyController.java:73-86`) | `@GetMapping(path = "/api/currencies", produces = "application/json")` | none declared | 200, body `currency.json` byte for byte (RAML); 406, 405 (APIkit mapping); 500 if the template cannot be rendered (platform) | ✓ |

Notes:

1. **Response body:** The original flow is `<parse-template location="currency.json"/>` (`currency.xml:38`). `currency.json` holds no `#[...]` expression, so the rendered body is the classpath resource unchanged.
2. **Platform surfaces:** SC-17 `controller/ApiKitConsoleController` carries the APIkit console and RAML descriptor. These are `GET /api/console`, `/api/console/`, `/api/console/?raml`, and `GET /api/` with `Accept: application/raml+yaml`. They are platform-generated (AAP 0.4.5) and outside the 1:1 comparison.

## 4. `netsuite-data-retrieval` → `netsuite-data-retrieval-java`

| Item | Value |
|---|---|
| RAML file | `netsuite-data-retrieval/src/main/api/netsuite-api.raml` → `java-conversions/netsuite-data-retrieval-java/src/main/resources/api/netsuite-api.raml`. `cmp`: identical, as are the included `customers-response.json`, `items-response.json` and `opportunities-response.json` |
| RAML version, title, version | `#%RAML 0.8`, `Netsuite-Data-retrieval-Example`, `v1` |
| `baseUri` | `http://localhost:8081/api` |
| Original listener | `/api/*` on the literal port `8081`, host `0.0.0.0` (`netsuite-data-retrieval/src/main/app/netsuite-api.xml:12,21`). APIkit config `netsuite-api-config`, with 3 flow mappings and the console enabled (`netsuite-api.xml:13-17`) |
| Spring base path | Undertow on the literal `server.port: 8081`, `server.address: 0.0.0.0`. Class-level `@RequestMapping("${apikit.base-path:/api}")` with `apikit.base-path: /api`. `apikit.raml: api/netsuite-api.raml` |
| Sources read | `controller/CustomersController.java`, `controller/ItemsController.java`, `controller/OpportunitiesController.java`, `exception/ApiKitExceptionHandler.java`, `exception/UpstreamExceptionHandler.java`, `mapper/NetsuiteRecordShapeMapper.java` |

| RAML path | Method | URI parameters (type, constraints) | Query parameters (type, required, default, enum) | Request media type and schema file | Declared statuses and response media type/schema | Controller class#method | Mapping annotation (full path incl. `/api`) | Validation applied | Statuses the method and handlers can return | Match (✓ or ✗ with reason) |
|---|---|---|---|---|---|---|---|---|---|---|
| `/customers` | GET (`netsuite-api.raml:8-17`) | — | `name`: no type (string by the RAML 0.8 default), optional, no default (`:10-12`) | — | 200 `application/json`, example `customers-response.json`, no schema (`:13-17`) | `CustomersController#getCustomers` → `NetsuiteSearchService.getCustomers(name)` (`CustomersController.java:92-108`) | `@RequestMapping("/api")` + `@GetMapping(path = "/customers", produces = "application/json")` | `@RequestParam(name = "name", required = false) String`. A repeated `name` raises `ConstraintViolationException`, which gives 400 (APIkit rejected non-repeatable parameters) | 200 (RAML); 400, 406, 405 (APIkit mapping); 502, 429 with `Retry-After` when supplied, 503 (connector; note 2); 500 (platform, including an unresolved select custom-field `typeId`; note 3) | ✓ |
| `/items` | GET (`:19-36`) | — | `quantity`: `integer`, default `0` (`:22-26`); `operator`: `enum` [`LESS_THAN`, `GREATER_THAN`, `EQUAL_TO`, `LESS_THAN_OR_EQUAL_TO`, `GREATER_THAN_OR_EQUAL_TO`], default `GREATER_THAN` (`:27-31`) | — | 200 `application/json`, example `items-response.json`, no schema (`:32-36`) | `ItemsController#getItems` → `NetsuiteSearchService.getItems(quantity, operator)` (`ItemsController.java:109-130`) | `@RequestMapping("/api")` + `@GetMapping(path = "/items", produces = "application/json")` | `quantity` is `@RequestParam(name = "quantity", defaultValue = "0") Integer`; a non-integer raises `MethodArgumentTypeMismatchException` and a non-decimal form raises `ConstraintViolationException`, each giving 400. `operator` is `@RequestParam(name = "operator", defaultValue = "GREATER_THAN") @Pattern` over the five enum values, matched case-sensitively; a value outside them gives 400. An empty or repeated value of either parameter gives 400; a default applies only when the parameter is absent | 200 (RAML); 400, 406, 405 (APIkit mapping); 502, 429, 503 (connector; note 2); 500 (platform; note 3) | ✓ |
| `/opportunities` | GET (`:38-48`) | — | `title`: no type (string), optional, no default (`:40-43`) | — | 200 `application/json`, example `opportunities-response.json`, no schema (`:44-48`) | `OpportunitiesController#getOpportunities` → `NetsuiteSearchService.getOpportunities(title)` (`OpportunitiesController.java:89-104`) | `@RequestMapping("/api")` + `@GetMapping(path = "/opportunities", produces = "application/json")` | `@RequestParam(name = "title", required = false) String`. A repeated `title` gives 400 | 200 (RAML); 400, 406, 405 (APIkit mapping); 502, 429, 503 (connector; note 2); 500 (platform; note 3) | ✓ |

Notes:

1. **Flow name spelling:** The original routes RAML `/opportunities` to the flow `get:/oportunities:netsuite-api-config`, whose name is misspelt (`netsuite-api.xml:16,79`). The RAML path `/opportunities` governs (AAP 0.7.1), and the mapping uses it.
2. **Connector failure modes:** These are the approved connector failure modes (AAP 0.4.8), not RAML mismatches. `exception/UpstreamExceptionHandler` (SC-9, `@Order(HIGHEST_PRECEDENCE)`) answers each with `application/json` `{ "message": "<upstream message>", "upstream": "NetSuite" }`:
   - `UpstreamAuthenticationException` gives 502.
   - `UpstreamRateLimitException` gives 429, with the vendor's `Retry-After` copied when one was supplied.
   - `UpstreamUnavailableException` gives 503.
3. **Unresolved `typeId`:** If a select custom field's `typeId` cannot be resolved, `NetsuiteRecordShapeMapper.typeId` throws `IllegalStateException` "No select source (typeId) resolved for NetSuite custom field <scriptId>" (`NetsuiteRecordShapeMapper.java:485-492`). SC-1 answers it with 500 (AAP 0.7.4).
4. **Platform surfaces:** SC-17 `controller/ApiKitConsoleController` carries the APIkit console and RAML descriptor. These are `GET /api/console`, `/api/console/`, `/api/console/?raml`, and `GET /api/` with `Accept: application/raml+yaml`. They are platform-generated (AAP 0.4.5) and outside the 1:1 comparison.

## 5. APIkit error mappings

Each original flow declares the same five mappings in its `apikit:mapping-exception-strategy`:

- `rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:19-43`
- `testing-apikit-with-munit/src/main/app/api.xml:11-35`
- `processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:6-30`
- `netsuite-data-retrieval/src/main/app/netsuite-api.xml:108-132`

Each mapping sets `Content-Type` to `application/json` and the payload to a literal. The bodies below have `&quot;` decoded to `"`.

The Java side is `exception/ApiKitExceptionHandler.java` in each of the four projects. The four copies are identical apart from the package root, so the cited line numbers hold for every copy. Each response is written straight to the servlet response, with:

- `Content-Type: application/json`, with no charset parameter;
- `Content-Length` set;
- no `Allow` header;
- no content negotiation, so a request whose `Accept` header already failed still receives the JSON 406 body.

| Status | Original exception class | Original Content-Type and body | Java exception types handled | Java Content-Type and body | Match |
|---|---|---|---|---|---|
| 404 | `org.mule.module.apikit.exception.NotFoundException` | `application/json`, `{ "message": "Resource not found" }` | `ApiKitNotFoundException`, thrown where the original threw APIkit's `NotFoundException` (`handleApiKitNotFound`, `:127`); `NoHandlerFoundException`, `NoResourceFoundException` for an unknown path under `/api`, a path whose literal segment carries `;` (`/api/teams;x=1`) included, because `config/ApiKitPathParameterConfig` makes `;` segment content as APIkit's resource matching did (`handleNoHandler`, `:138`) | `application/json`, `RESOURCE_NOT_FOUND` = `{ "message": "Resource not found" }` (`:85`) | ✓ |
| 405 | `org.mule.module.apikit.exception.MethodNotAllowedException` | `application/json`, `{ "message": "Method not allowed" }` | `HttpRequestMethodNotSupportedException` under `/api`, including the HEAD/OPTIONS rejections of every controller (`handleMethodNotAllowed`, `:155`) | `application/json`, `METHOD_NOT_ALLOWED` = `{ "message": "Method not allowed" }` (`:88`) | ✓ |
| 415 | `org.mule.module.apikit.exception.UnsupportedMediaTypeException` | `application/json`, `{ "message": "Unsupported media type" }` | `HttpMediaTypeNotSupportedException`, raised for a body outside a method's `consumes` (`handleUnsupportedMediaType`, `:169`) | `application/json`, `UNSUPPORTED_MEDIA_TYPE` = `{ "message": "Unsupported media type" }` (`:91`) | ✓ |
| 406 | `org.mule.module.apikit.exception.NotAcceptableException` | `application/json`, `{ "message": "Not acceptable" }` | `HttpMediaTypeNotAcceptableException`, raised for an `Accept` header outside a method's `produces`, and by `config/ApiKitAcceptNegotiator.negotiate` for one under which APIkit's quality negotiation (`RestContentTypeParser.bestMatch`) chooses no declared type, such as `application/json;q=0`. Each RAML action with a success body calls `negotiate` after its URI and query parameter validation and before the flow, as APIkit did, so an invalid parameter still gets the 400 (`handleNotAcceptable`, `:180`) | `application/json`, `NOT_ACCEPTABLE` = `{ "message": "Not acceptable" }` (`:94`) | ✓ |
| 400 | `org.mule.module.apikit.exception.BadRequestException` | `application/json`, `{ "message": "Bad request" }` | `JsonSchemaValidator.SchemaValidationException`, `HttpMessageNotReadableException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `MethodArgumentNotValidException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException`. These cover schema failures, missing or unreadable bodies, URI or query parameters outside their constraints, enum or type mismatches, and missing required parameters (`handleBadRequest`, `:202-215`) | `application/json`, `BAD_REQUEST` = `{ "message": "Bad request" }` (`:97`) | ✓ |

Spring picks the first advice, in `@Order`, that declares a matching method. Each advice declares only the exception types it maps, so every other exception still reaches the next advice:

| `@Order` | Advice | Projects | Declares |
|---|---|---|---|
| `Ordered.HIGHEST_PRECEDENCE` | `UpstreamExceptionHandler` (SC-9) | `netsuite-data-retrieval-java` | `UpstreamAuthenticationException` gives 502; `UpstreamRateLimitException` gives 429; `UpstreamUnavailableException` gives 503 |
| `0` | `LeagueExceptionHandler` | `rest-api-with-apikit-java` | `ConflictException` gives 409 |
| `Ordered.LOWEST_PRECEDENCE - 1` | `ApiKitExceptionHandler` (SC-3) | all four | the five mappings above |
| `Ordered.LOWEST_PRECEDENCE` | `GlobalExceptionHandler` (SC-1) | all four | `Exception` gives 500 `{ "message": "<exception message>" }` |

Outside `/api`, where the original listener served nothing, `ApiKitExceptionHandler` writes the listener defaults of AAP 0.4.7. These are `text/plain`, with the bodies `No listener for endpoint: <path>` (404) and `Method not allowed for endpoint: <path>` (405). They are outside the RAML comparison.

## 6. `sap-data-retrieval`

EXCLUDED — pending sapjco3.jar

The diff for its RAML resources `/materials`, `/salesOrders` and `/customers` (`sap-data-retrieval/src/main/resources/sap-api.raml:7,30,103`) is produced with the follow-on conversion.

## Reproducing the static checks

Run these from the repository root.

```sh
# RAML copies are byte-identical to the originals
cmp rest-api-with-apikit/src/main/resources/leagues.raml java-conversions/rest-api-with-apikit-java/src/main/resources/leagues.raml
cmp testing-apikit-with-munit/src/main/api/api.raml java-conversions/testing-apikit-with-munit-java/src/main/resources/api/api.raml
cmp processing-orders-with-dataweave-and-APIkit/src/main/api/currency.raml java-conversions/processing-orders-with-dataweave-and-APIkit-java/src/main/resources/api/currency.raml
cmp netsuite-data-retrieval/src/main/api/netsuite-api.raml java-conversions/netsuite-data-retrieval-java/src/main/resources/api/netsuite-api.raml

# The ApiKitExceptionHandler copies differ only in the package root (repeat for the munit and currency projects)
diff <(sed 's/rest_api_with_apikit/X/g' java-conversions/rest-api-with-apikit-java/src/main/java/com/mulesoft/examples/rest_api_with_apikit/exception/ApiKitExceptionHandler.java) \
     <(sed 's/netsuite_data_retrieval/X/g' java-conversions/netsuite-data-retrieval-java/src/main/java/com/mulesoft/examples/netsuite_data_retrieval/exception/ApiKitExceptionHandler.java)

# Mapping annotations of every controller method named above
grep -n '@\(Get\|Post\|Put\|Delete\|Request\)Mapping' java-conversions/*/src/main/java/com/mulesoft/examples/*/controller/{Teams,Positions,Fixture,Munit,Currency,Customers,Items,Opportunities}Controller.java
```
