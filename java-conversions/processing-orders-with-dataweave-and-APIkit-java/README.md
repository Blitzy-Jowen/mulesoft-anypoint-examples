# processing-orders-with-dataweave-and-APIkit-java

Java 17 / Spring Boot 3.2.12 (Undertow) / Maven conversion of the Mule EE 3.8 / DataWeave 1.0 example [`processing-orders-with-dataweave-and-APIkit`](../../processing-orders-with-dataweave-and-APIkit/) ([original README](../../processing-orders-with-dataweave-and-APIkit/README.md)), category RAML/APIkit-driven REST API. The original folder stays unchanged beside this project as the reference. The example processes an orders file with DataWeave while looking up currency ratios from an API that the same application exposes with APIkit: a filter and transformations turn the orders into JSON, and a second transformation produces a CSV report. Package root `com.mulesoft.examples.processing_orders_with_dataweave_and_apikit`, main class `ProcessingOrdersWithDataweaveAndAPIkitApplication`.

This project holds the reference implementation of three shared components (four classes), registered in the root [`DECISIONS.md`](../../DECISIONS.md) (section 4) and copied, with only the `package` line changed, into the other projects that need them:

| ID | Classes | Provides |
|---|---|---|
| SC-4 | `mapper/DwJsonWriter`, `mapper/DwCsvWriter` | DataWeave 1.0 output bytes: JSON with two-space indentation, `"key": value`, `BigDecimal` written with its scale and no trailing newline; CSV with comma separator, header row and LF line ends including a trailing LF |
| SC-5 | `trigger/FileInboundPoller` | Mule 3.8 `file:inbound-endpoint` semantics on the default file connector |
| SC-6 | `service/FileOutboundWriter` | Mule 3.8 `file:outbound-endpoint` writes: the directory is created and an existing file is replaced as a whole |

Every other shared class is a copy, identical apart from the package, together with its unit tests:

- from `rest-api-with-apikit-java`: SC-1 (`exception/ErrorResponse`, `exception/GlobalExceptionHandler`), SC-3 (`exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator`), SC-17 (`controller/ApiKitConsoleController`, `src/main/resources/apikit-console/**`), ST-1 (`src/test/java/.../support/HttpTestClient`), and the RAML-project classes `config/ApiKitMethodCaseFilter` and `config/PreflightDispatchConfig`;
- from `login-form-using-the-http-connector-java`: SC-14 (`mapper/ParseTemplateRenderer`).

## Original example

`mule-deploy.properties` loads two flow files into one Mule application (`config.resources=currency.xml,books.xml`):

- **`currency.xml`, the Currency API.** One listener, `0.0.0.0:${http.port}` path `/api/*` (`currency.xml:3,33`), hands every request to an `apikit:router` over `currency.raml` (RAML 0.8, `baseUri: http://localhost:8081/api/`, console enabled at `console`, `currency.xml:4`). The router dispatches `GET /currencies` to the flow `get:/currencies:currency-config`, whose only processor is `<parse-template location="currency.json"/>` (`currency.xml:37-39`). An `apikit:mapping-exception-strategy` answers failures with five fixed JSON bodies (`currency.xml:5-31`).
- **`books.xml`, the order flow `OrderFlow`** (`books.xml:7-43`). A `file:inbound-endpoint` polls `src/main/resources/input` (`books.xml:8`). An `enricher` stores the response of `GET http://localhost:${http.port}/api/currencies` (request config `HTTP_Request_Configuration`, `books.xml:3-5`) in `flowVars.currencies` (`books.xml:9-11`). One `dw:transform-message` runs two DataWeave 1.0 scripts on the same input: `dw:set-payload` builds the orders JSON (`books.xml:14-29`) and `dw:set-variable variableName="report"` builds the CSV report (`books.xml:30-37`). Two `file:outbound-endpoint`s write `orders.json`, then `report.csv`, to `src/main/resources/output` (`books.xml:39-42`).

`mule-app.properties` is empty, so the original sets no `http.port`. The original README's Studio procedure is replaced by the Maven and Spring Boot commands of [Build and run](#build-and-run). That README also says the file endpoint "polls the input folder every ten seconds", which does not match the flow: the endpoint declares no `pollingFrequency`, so the Mule file connector's default of 1000 ms applied, and this project keeps it.

## Endpoints

### HTTP API

Base URL `http://localhost:8081/api`, the RAML `baseUri`. The RAML contract is compared resource by resource in the root [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) (section 3: 1 of 1 rows match).

| Method | Path | Request | Success | Errors | Controller method |
|---|---|---|---|---|---|
| GET | `/api/currencies` | no parameter and no body; query parameters the RAML does not declare are ignored | 200, `Content-Type: application/json`, body `src/main/resources/currency.json` byte for byte (134 bytes, no trailing newline) | 406, 405 | `CurrencyController.getCurrencies` → `CurrencyService.getCurrencies` |

- The RAML declares GET only. Every other method on `/api/currencies` gets the APIkit 405, HEAD and OPTIONS included (`CurrencyController.rejectUndeclaredMethod` maps them, so Spring does not answer them itself). A CORS pre-flight is answered like any other OPTIONS request, with the APIkit 405 and no `Access-Control-*` header (`config/PreflightDispatchConfig`), because the original listener applied no CORS policy.
- Under `/api`, a method token in lower or mixed case (`get`, `Get`) is routed as its upper-case form (`config/ApiKitMethodCaseFilter`), as the APIkit router lower-cased the method before routing.
- The response is not validated against `currency-schema.json`, because APIkit validated requests only. The served document would not pass it: its `currency` values are strings, and the schema types them `number`.
- The order flow below reads the currencies through this same endpoint, as the original flow called back on its own listener.

### APIkit console and RAML descriptor

The original enabled the console (`apikit:config consoleEnabled="true" consolePath="console"`). The conversion carries it as a platform surface (AAP 0.4.5, SC-17). It is not a RAML resource, so the 1:1 diff leaves it out.

| Request | Response |
|---|---|
| any method `/api/console` | 301, `Location: http://<host>/api/console/` (the request's scheme and `Host` header, followed by its query string), empty body |
| any method `/api/console/` or `/api/console/index.html` | the APIkit 3.8.0 console page (`text/html`), its RAML source pointed at `./?raml` and `console-attributes-placeholder` replaced by `disable-raml-client-generator="" disable-theme-switcher=""` |
| `/api/console/<file>` | the console's scripts, styles, fonts and images from `src/main/resources/apikit-console/` (`text/css`, `application/x-javascript`, `image/png`, `image/gif`, `image/svg+xml`, otherwise `application/octet-stream`) |
| GET `/api/console/?raml`, GET `/api/console?raml`, and GET `/api/` with `Accept: application/raml+yaml` | `api/currency.raml` as `application/raml+yaml` with `Access-Control-Allow-Origin: *` and its `baseUri` rewritten to the listener URL (`http://localhost:<port>/api`) |
| GET `/api/currency-schema.json` and GET `/api/console/currency-schema.json` | the schema the RAML `!include`s, served relative to the RAML (`application/octet-stream`, by the console's extension table) |

A miss under the console path is answered with the APIkit 404. The class Javadoc of `ApiKitConsoleController` lists where these bytes are plan decisions rather than recorded original output (for example, the jar's `console/` tree is served rather than `console2/`, and the RAML is served as its raw file rather than re-dumped). Each is a Tier 2A verification point.

### Order file trigger

`trigger/OrderFileTrigger` replaces the flow's `file:inbound-endpoint` (`books.xml:8`). Every `orders.polling-frequency` milliseconds (default 1000, counted from the end of one poll to the start of the next) it polls `orders.input-dir` (default `src/main/resources/input`, relative to the working directory) through SC-5 `FileInboundPoller`:

1. The input directory is created when missing. Its regular files are taken in directory-listing order, unsorted and unfiltered, and each is marked in process so that no overlapping poll takes it twice. A file that cannot be read stays in place for the next poll.
2. `service/OrderFlowService.process` runs the flow on the file's bytes, in the original processor order:
   1. `client/CurrencyClient` calls `GET http://localhost:<port>/api/currencies`, with `<port>` the running listener's port (then `http.port`, then 8081), `Accept: application/json`, 10 s connect and read timeouts and no retry. It parses the ratios as exact decimals, so `0.92` keeps scale 2. The result is the enricher's `flowVars.currencies`.
   2. The file is parsed once as XML, and both scripts read that document: `mapper/OrderMapper` (`dw:set-payload`) and `mapper/OrderReportMapper` (`dw:set-variable report`). Both results are computed before anything is written.
   3. `orders.json`, then `report.csv`, are written to `orders.output-dir` (default `src/main/resources/output`) through SC-6 `FileOutboundWriter`, which creates the directory when missing and replaces an earlier file of the same name as a whole.
3. Once the flow has returned or failed, the input file is deleted: the endpoint declares no `moveToDirectory`, and Mule's default exception strategy ended a failed flow normally. A failure is logged at ERROR by `FileInboundPoller`, naming the file and the exception's type and throw site only. The original flow declares no logger, so a successful file logs nothing.

Transformation rules (AAP 0.7.1):

| Output | Rule | Result for the bundled `orders.xml` |
|---|---|---|
| `orders.json` | the `item` children of the `orders` root whose `properties/year` is greater than 2004 become `{ "orders": [ { "title", "prices", "authors" } ] }`: `prices` holds `{ "price": ratio × price, "currency" }` for each `currencies.USD` entry, computed in exact `BigDecimal` arithmetic with its scale kept; `authors` holds `{ "author" }` once per distinct author, in order of first occurrence | 2 of the 4 items (`Everyday Italian`, `Harry Potter`); prices `27.60`, `262.80`, `19.80` and `27.5908`, `262.7124`, `19.7934` |
| `report.csv` | one record over every item, whatever its year: `totalNumberOfItems` (the item count), `totalAmount` (the sum of the prices, exact), `averageSellingPrice` (`totalAmount` divided by the count) | `totalNumberOfItems,totalAmount,averageSellingPrice` then `4,149.93,37.4825` |

Both outputs are written through SC-4 in DataWeave 1.0's byte format. For the bundled input they are byte-identical to the original's committed outputs, carried unchanged as `src/test/resources/expected/orders.json` (793 bytes) and `expected/report.csv` (68 bytes).

## Error responses

| Status | Content-Type | Body (exact bytes) | When | Handler |
|---|---|---|---|---|
| 404 | `application/json` | `{ "message": "Resource not found" }` | a path under `/api` that no resource names, or a miss under the console path | `ApiKitExceptionHandler` (SC-3) |
| 405 | `application/json` | `{ "message": "Method not allowed" }` | any method on `/api/currencies` other than GET | `ApiKitExceptionHandler` |
| 406 | `application/json` | `{ "message": "Not acceptable" }` | an `Accept` header that excludes `application/json` | `ApiKitExceptionHandler` |
| 415 | `application/json` | `{ "message": "Unsupported media type" }` | carried with SC-3; no request to this API raises it, because `/currencies` declares no request body | `ApiKitExceptionHandler` |
| 400 | `application/json` | `{ "message": "Bad request" }` | carried with SC-3; no request to this API raises it, because `/currencies` declares no parameter or body | `ApiKitExceptionHandler` |
| 404 | `text/plain` | `No listener for endpoint: <path>` | any path outside `/api` | `ApiKitExceptionHandler`, writing SC-1's listener default |
| 500 | `application/json` | `{ "message": "<exception message>" }` | any other unhandled exception, such as a failure to render `currency.json` | `GlobalExceptionHandler` (SC-1) |

- The five `application/json` mapping rows are the original `apikit:mapping-exception-strategy` (`currency.xml:5-31`), with its spacing. As in APIkit, a mapping never carries the exception message.
- The no-listener 404 and the 500 are plan defaults rather than recorded original output (AAP 0.4.7). Each is a Tier 2A verification point: a captured fixture that shows different bytes governs.
- Advice precedence: `ApiKitExceptionHandler` (`Ordered.LOWEST_PRECEDENCE - 1`), then `GlobalExceptionHandler` last.
- The order flow has no HTTP caller, so its failures never reach these handlers. They end as the ERROR log of the [order file trigger](#order-file-trigger).

## Configuration

Every key of `src/main/resources/application.yml`, and the `http.port` placeholder it reads:

| Key | Default | Original | Notes |
|---|---|---|---|
| `http.port` | not set, so `8081` applies | `${http.port}` in `currency.xml:3` (listener) and `books.xml:3` (request config); `mule-app.properties` sets no value (name unchanged) | listener port, bound by `server.port`; `CurrencyClient` calls the running listener's port and falls back to this value |
| `server.port` | `${http.port:8081}` | `http:listener-config port="${http.port}"` | set `http.port` rather than this key |
| `apikit.base-path` | `/api` | listener path `/api/*` | API base path for the controller, the APIkit mappings, the console and the method-case filter |
| `apikit.raml` | `api/currency.raml` | `apikit:config raml="currency.raml"` | classpath RAML served by the console and read by the schema validator |
| `apikit.console-path` | `console` | `apikit:config consolePath="console"` | console path below the base path |
| `orders.input-dir` | `src/main/resources/input` | `file:inbound-endpoint path` (`books.xml:8`) | directory the trigger polls; a relative path resolves against the working directory |
| `orders.output-dir` | `src/main/resources/output` | `file:outbound-endpoint path` (`books.xml:39,42`) | directory `orders.json` and `report.csv` are written to |
| `orders.polling-frequency` | `1000` | none declared, so the Mule file connector's default | milliseconds between the end of one poll and the start of the next |
| `spring.application.name` | `processing-orders-with-dataweave-and-APIkit-java` | — | application name in log lines |
| `spring.web.resources.add-mappings` | `false` | — | no static resource handler, so unknown paths reach the APIkit 404 or the no-listener 404 |
| `spring.mvc.dispatch-trace-request` | `true` | — | TRACE reaches the APIkit 405, as the listener declares no `allowedMethods` |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | — | no Boot `/error` endpoint; `/error` gets the no-listener 404 |

- The original uses no secret, host, account or credential, so the configuration has no TODO placeholder, and the project has no `application-test.yml` or `application-it.yml`.
- Original-to-new key map: `http.port` keeps its name. The literal paths of `books.xml` became `orders.input-dir` and `orders.output-dir`, the connector's default frequency became `orders.polling-frequency`, and the `apikit:config` attributes became the three `apikit.*` keys. No original key was renamed.
- The host is not configured: Spring Boot binds all interfaces, as the original `host="0.0.0.0"` did.
- Override on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`): `--http.port=9090`, `--orders.input-dir=/data/in`.
- During `mvn test`, surefire sets `orders.input-dir` and `orders.output-dir` to `target/test-orders/input` and `target/test-orders/output` (`systemPropertyVariables` in `pom.xml`), because every test context starts the scheduled trigger. Tests that need their own directories override both with `@DynamicPropertySource`, so no test touches `src/main/resources/input` or `src/main/resources/output`.

## Mule-to-Java mapping

| Mule construct (`processing-orders-with-dataweave-and-APIkit`) | Java construct |
|---|---|
| `mule-deploy.properties` (`config.resources=currency.xml,books.xml`) | `ProcessingOrdersWithDataweaveAndAPIkitApplication` (`@SpringBootApplication`, `@EnableScheduling`): both flows in one process |
| `http:listener` `/api/*` + `apikit:router` + `apikit:config` (`currency.xml:3-4,32-36`) | `controller/CurrencyController` on Undertow, one controller for the one RAML resource |
| flow `get:/currencies:currency-config` (`currency.xml:37-39`) | `service/CurrencyService` |
| `parse-template location="currency.json"` | SC-14 `mapper/ParseTemplateRenderer`; `currency.json` holds no `#[...]` expression, so it is returned unchanged |
| `apikit:mapping-exception-strategy` (`currency.xml:5-31`) | SC-3 `exception/ApiKitExceptionHandler`, with `exception/ApiKitNotFoundException` and `config/JsonSchemaValidator` |
| Console and RAML descriptor (`consoleEnabled="true"`) | SC-17 `controller/ApiKitConsoleController` and `src/main/resources/apikit-console/**` |
| APIkit router method handling: case-insensitive tokens, no CORS processing | `config/ApiKitMethodCaseFilter`, `config/PreflightDispatchConfig` |
| listener defaults outside `/api`, unhandled exceptions | SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse` |
| `file:inbound-endpoint path="src/main/resources/input"` (`books.xml:8`) | `trigger/OrderFileTrigger` (`@Scheduled`, single-run method `pollOnce()`) on SC-5 `trigger/FileInboundPoller` |
| `enricher target="flowVars.currencies"` around `http:request GET /currencies` on `HTTP_Request_Configuration` (`books.xml:3-5,9-11`) | `client/CurrencyClient`; the nested `http:raml-api-configuration` was design-time metadata and has no counterpart |
| `dw:set-payload` (`books.xml:14-29`) | `mapper/OrderMapper`, writing through SC-4 `mapper/DwJsonWriter` |
| `dw:set-variable variableName="report"` (`books.xml:30-37`) | `mapper/OrderReportMapper`, writing through SC-4 `mapper/DwCsvWriter` |
| `file:outbound-endpoint outputPattern="orders.json"`, `set-payload #[report]`, `file:outbound-endpoint outputPattern="report.csv"` (`books.xml:39-42`) | SC-6 `service/FileOutboundWriter`, called twice |
| processor order of flow `OrderFlow` (`books.xml:7-43`) | `service/OrderFlowService.process` |
| `src/main/api/currency.raml`, `currency-schema.json`; `src/main/resources/currency.json`, `orders.xml`, `orders.xsd` | `src/main/resources/api/currency.raml`, `api/currency-schema.json`, `currency.json`, `orders.xml`, `orders.xsd`, byte for byte |
| `src/main/resources/orders.json`, `report.csv` (the committed DataWeave outputs the original IT compares with) | `src/test/resources/expected/orders.json`, `expected/report.csv`, byte for byte |

## Behaviour kept, stubbed or externalized

- **Currency API.** `currency.json` is served exactly as the original rendered it, including its spacing and missing trailing newline.
- **Order flow failures.** The original flow declares no logger and no exception strategy, so `OrderFlowService` catches nothing: a failed currency call, an XML document that is not well formed, a price, ratio or year that is not a number, or a failed write ends the flow, `FileInboundPoller` logs it at ERROR and the input file is still deleted. A failure in either script writes no file, and a failed `orders.json` write leaves `report.csv` unattempted.
- **XML input.** The orders file is parsed with external general and parameter entities, external DTDs and schemas and XInclude disabled, and secure processing capping entity expansion, so a file over the JDK's entity-expansion limits fails the parse and is handled like any other parse failure; an inline DTD stays allowed, as DataWeave's reader accepted one, and its internal entities are expanded into the text and elements they stand for, as that reader expanded them (root [`DECISIONS.md`](../../DECISIONS.md), section 8).
- **Plan defaults** that no committed original output settles. Each is a Tier 2A verification point:
  - An orders document with no `item` (or a root other than `orders`) gives the order script `{"orders": []}`, but the report script fails because the average of no items is undefined, so neither file is written.
  - A currencies tree without `USD` gives `"prices": null`, and an item without `properties/authors` gives `"authors": null`.
  - A non-terminating average is rounded to `MathContext.DECIMAL128` (34 significant digits).
  - A CSV value holding a comma, a double quote, a CR or an LF is wrapped in double quotes, with each double quote and backslash inside it preceded by a backslash.
  - The console variations listed in `ApiKitConsoleController`.
- **Not applicable:** the Studio and Anypoint content of the original README (Exchange import, global-element editing, documentation export).
- **Stubbed or externalized:** nothing is stubbed. The original reaches no external system beyond its own Currency API and holds no credential; the input and output directories and the polling frequency are configurable.

## Build and run

Prerequisites: JDK 17, Maven 3.9.x and access to Maven Central. No Mule runtime, licence or credential is needed. Run from `java-conversions/processing-orders-with-dataweave-and-APIkit-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, enforcer ban, jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on port 8081; the trigger polls src/main/resources/input
java -jar target/processing-orders-with-dataweave-and-APIkit-java-1.0.0-SNAPSHOT.jar --http.port=9090
```

- `mvn verify` runs no live tests: this is not a connector project, so it has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports: `target/surefire-reports/`; JaCoCo in `target/site/jacoco/` (`index.html`, `jacoco.csv`).
- The `maven-enforcer-plugin` bans JAX-WS and CXF artefacts at `validate` (AAP 0.3.2).

Process the bundled orders while the application runs (the trigger creates `src/main/resources/input` on its first poll):

```sh
cp src/main/resources/orders.xml src/main/resources/input/
# about a second later orders.xml has been consumed and both outputs exist
cat src/main/resources/output/orders.json src/main/resources/output/report.csv
```

Neither directory is git-ignored: remove them after a local run, or point `orders.input-dir` and `orders.output-dir` under `target/`.

Example calls:

```sh
curl -i http://localhost:8081/api/currencies
curl -i -H 'Accept: application/raml+yaml' http://localhost:8081/api/
```

Open the console at `http://localhost:8081/api/console/`.

## Tests

| Test class | What it asserts |
|---|---|
| `ProcessingOrdersWithDataweaveAndApikitTest` | the ported original test (`processing-orders-with-dataweave-and-APIkit/src/test/java/org/mule/examples/ProcessingOrdersWithDataweaveAndApikitIT.java:52-78`): `orders.xml` dropped into the input directory of the running application, then `orders.json` and `report.csv` equal `src/test/resources/expected/orders.json` and `expected/report.csv` with all whitespace removed, within the original's 5 s budget; nothing is mocked, the currencies come from the application's own `GET /api/currencies`, and the input file is deleted |
| `controller/CurrencyControllerTest` | on the wire through ST-1, so status code, reason phrase, `Content-Type` and body bytes are asserted: GET 200 with `currency.json` byte for byte, the APIkit 405 for POST, PUT, DELETE, OPTIONS and HEAD, the 406, the APIkit 404 under `/api`, and the no-listener 404 outside it |
| `controller/ApiKitConsoleControllerTest` | SC-17: the redirect, console page and files, RAML descriptor with rewritten `baseUri`, the `currency-schema.json` include, and path-traversal probes answered with the 404 |
| `service/CurrencyServiceTest` | the rendered template equals the original `currency.json` (length and SHA-256) |
| `service/OrderFlowServiceTest` | the bundled input produces both expected files byte for byte; processor order (currency call, one parse read by both scripts, `orders.json`, then `report.csv`); every failure propagates with no file written, or with `report.csv` unattempted after a failed `orders.json` write; inline-DTD internal entities (text in a title and an author, a whole `item`, a price and a year, an entity inside an entity) give byte-identical outputs to their text written out, and `Pre &t; Post` and `&au;`, with `t` and `au` declared as `Inline` and `Ann Author`, give `"title": "Pre Inline Post"` and `"author": "Ann Author"`; an external general entity, an external parameter entity and an external DTD subset (`file:///etc/passwd`) are never loaded; a billion-laughs file fails with the parser's entity-expansion `SAXParseException` and writes nothing |
| `trigger/OrderFileTriggerTest` | the trigger test of AAP 0.8.2, with only the currency client mocked: one on-demand poll writes both expected files and deletes the input; a currency failure or a malformed XML file still consumes the input and writes nothing; `poll()` is scheduled at `${orders.polling-frequency:1000}` |
| `client/CurrencyClientTest` | against WireMock: the GET path and `Accept` header, exact decimals, port resolution, and the failures for an error status, a refused connection and a body that is not JSON |
| `mapper/OrderMapperTest`, `mapper/OrderReportMapperTest` | both scripts: the committed outputs byte for byte, the year filter, the `BigDecimal` scale, distinct authors, the report over every item, the `DECIMAL128` average, the plan defaults and the declared failures |
| `mapper/DwJsonWriterTest`, `mapper/DwCsvWriterTest` | SC-4 reference tests: layout, value, escaping and quoting rules |
| `trigger/FileInboundPollerTest`, `trigger/FileInboundPollerLogSafetyTest` | SC-5 reference tests: delete or move after success and after a handled failure, the in-process mark against re-entrant and concurrent polls, an unopenable file left for the next poll, a file too large to read (over the read limit, or one whose allocation fails with `OutOfMemoryError`) left in place while the other files are consumed, missing directories created, subdirectories ignored; one-line log records that disclose no file content or exception text, an `OutOfMemoryError` read failure included |
| `service/FileOutboundWriterTest` | SC-6 reference test: directory creation, overwrite, whole-file replacement, symbolic links, permissions and failed writes |
| `mapper/ParseTemplateRendererTest` | SC-14: template loading and expression substitution |
| `exception/ApiKitExceptionHandlerTest`, `exception/GlobalExceptionHandlerTest` | SC-3 mappings and precedence; SC-1 defaults and log safety |
| `config/JsonSchemaValidatorTest` | SC-3 draft-03 validation: body faults give 400, schema faults give 500 |
| `config/ApiKitMethodCaseFilterTest`, `config/ApiKitMethodCaseListenerTest` | lower and mixed-case method tokens under `/api` answered as their upper-case form, on `/api/currencies` and the RAML descriptor |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | CORS pre-flights to `/api/currencies` answered as plain OPTIONS requests with the APIkit 405, never running the currency flow |

`support/HttpTestClient` is ST-1, an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, because `java.net.http.HttpClient` hides the reason phrase.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| processing-orders-with-dataweave-and-APIkit-java | processing-orders-with-dataweave-and-APIkit | RAML/APIkit-driven REST API | PASS | PASS | 91.3% | RAML DIFF: 1:1 (Tier 1 static) | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 581 tests, 0 failures, 0 errors, 0 skipped; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 581 tests, 0 failures, 0 errors, 0 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 421 of 461 lines (`service/CurrencyService`, `OrderFlowService`, `FileOutboundWriter`; `mapper/OrderMapper`, `OrderReportMapper`, `DwJsonWriter`, `DwCsvWriter`, `ParseTemplateRenderer`). Total project LINE coverage: 88.5% (1170 of 1322 lines).

No Tier 2A fixture set applies, because this is a RAML-backed project: it has no `FixtureParityTest` and no `SCENARIOS.md`. Its evidence is the static RAML-to-controller diff in [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) plus the ported original assertions of `ProcessingOrdersWithDataweaveAndApikitTest`. That is Tier 1 evidence, not runtime parity with the original. The same row appears in the root [`SUMMARY.md`](../../SUMMARY.md).

## Licence

Apache License 2.0. `LICENSE` is copied unchanged from `processing-orders-with-dataweave-and-APIkit/`. `NOTICE` is the original's, extended to credit the APIkit 3.8.0 console files (`org.mule.modules:mule-module-apikit:3.8.0`) carried under `src/main/resources/apikit-console/`.

