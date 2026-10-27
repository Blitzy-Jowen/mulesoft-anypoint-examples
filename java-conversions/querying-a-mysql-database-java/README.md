# querying-a-mysql-database-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`querying-a-mysql-database`](../../querying-a-mysql-database/) (category General). The original listens for HTTP requests on `/`, runs `select first_name from employees where last_name = "<lastname>"` against MySQL through the Database connector, and returns the selected rows as JSON (`database-to-json.xml`, flow `database-to-jsonFlow`). The original folder `querying-a-mysql-database/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The single flow `database-to-jsonFlow` of `querying-a-mysql-database/src/main/app/database-to-json.xml:5-13` receives the request on the `/` listener (line 6), runs a `db:select` whose dynamic query writes `#[message.inboundProperties['http.query.params'].lastname]` into a double-quoted literal compared with `employees.last_name` (lines 7-11), and serializes the rows with `json:object-to-json-transformer` (line 12). The database is the `db:generic-config` of line 3 (`com.mysql.jdbc.Driver`, `url="${jdbc.url}"`), the listener the `http:listener-config` of line 4 (host `0.0.0.0`, port `${http.port}`). The flow declares no logger and no exception strategy and calls no other system. `mule-app.properties` is empty; the original README sets the port to 8081 and the URL by hand.

## Endpoints and triggers

| Path | Methods | Host | Port | Query parameter | Response | Source |
|---|---|---|---|---|---|---|
| `/` exactly | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `lastname`, read from the URL query string only | `200 OK`, `Content-Type: application/json`, a compact JSON array with one object per selected row, keyed by the column label `first_name`, rows in database order; a `HEAD` reply carries the headers without the body | `database-to-json.xml:4-12` |
| any other path (`/unknown`, `//` and `/error` included) | any | `0.0.0.0` | `http.port` | — | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/`, when the query or any other processing fails | any | `0.0.0.0` | `http.port` | — | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, where a database failure reports the JDBC driver's own message; logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception; the flow has no exception strategy |

Example, the original README's own request (`querying-a-mysql-database/README.md:44-50`):

```console
$ curl -i 'http://localhost:8081/?lastname=Puckett'
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 49
Date: <current date>

[{"first_name":"Chava"},{"first_name":"Quentin"}]
```

- How `lastname` reaches the query (`controller/EmployeeQueryController`): the raw query string is split on `&`, and each pair splits at its first `=` and is URL-decoded as UTF-8 (`+` is a space). The name matches case-sensitively. When `lastname` occurs more than once, the last occurrence is used, as Mule's `http.query.params` (`ParameterMap`) returned it; this is a Tier 2A verification point. A form-encoded request body is never read, because the original query read `http.query.params` only.
- No match → `200` with `[]`.
- `lastname` absent (or given without `=`) → the original rendered the missing value as the text `null` inside the query literal, so `service/EmployeeQueryService` binds the string `null` (`MISSING_VALUE`), which matches no row of the shipped data: `200` with `[]`. `lastname=` binds the empty string.
- Values are compared with `last_name` unchanged (no trimming or case change); case and accent sensitivity follow the column's collation on the configured server.
- The request's `Accept`, `Content-Type` and body are ignored, so none of them causes a 406, 415 or 400. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow and is answered like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A query string with a malformed percent escape (for example `?lastname=%zz` or `?lastname=abc%`) is rejected by Undertow before the controller runs: `400 Bad Request`, empty body, `Connection: close`.
- While `jdbc.url` is blank the application still starts, logging one WARN that names `jdbc.url` and `JDBC_URL`; every request to `/` then fails with `500` `{ "message": "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured" }` (`config/DataSourceConfig`, `config/UnconfiguredDataSource`).
- Tier 2A verification points (AAP 0.4.7): the last-occurrence rule above and the bytes of the `404`, `500` and malformed-escape `400` replies. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it carries one; `fixed` — a framework setting that reproduces the original listener or database configuration, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README uses port 8081 |
| `jdbc.url` | `String` | none (`${JDBC_URL:}`) | `jdbc.url` (unchanged) | TODO (secret) | the MySQL JDBC URL, bound to `spring.datasource.url`. As in the original, the credentials travel inside the URL: `jdbc:mysql://<host>:3306/company?user=<user>&password=<password>`. Supply it through the `JDBC_URL` environment variable, `--jdbc.url=…` or an external configuration file; never commit a value |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`database-to-json.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`database-to-json.xml:4`) |
| `spring.application.name` | `String` | `querying-a-mysql-database-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `EmployeeQueryController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `spring.datasource.url` | `String` | `${jdbc.url}` | none | fixed | the `db:generic-config url` (`database-to-json.xml:3`); set `jdbc.url` rather than this key |
| `spring.sql.init.mode` | `DatabaseInitializationMode` | `never` | none | fixed | the application never runs `import.sql` or any other schema or data script against the configured database |

- Driver: `com.mysql:mysql-connector-j` 8.3.0 (`com.mysql.cj.jdbc.Driver`, detected from the `jdbc:mysql:` URL) replaces `mysql:mysql-connector-java` 5.1.31 (`com.mysql.jdbc.Driver`). With a URL set, Boot builds its HikariCP pool from it.
- Supplying the URL, run from `java-conversions/querying-a-mysql-database-java/`:

  ```sh
  JDBC_URL='jdbc:mysql://localhost:3306/company?user=<user>&password=<password>' java -jar target/querying-a-mysql-database-java-1.0.0-SNAPSHOT.jar
  java -jar target/querying-a-mysql-database-java-1.0.0-SNAPSHOT.jar --http.port=9090 '--jdbc.url=jdbc:mysql://localhost:3306/company?user=<user>&password=<password>'
  ```

- Test profile: `src/test/resources/application-test.yml` (Spring profile `test`, activated with `@ActiveProfiles("test")`) points `jdbc.url` at H2 2.2.224 in MySQL mode (`jdbc:h2:mem:company-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1`, one in-memory database per test context) and seeds it from `import.sql` (`spring.sql.init.mode: always`, `continue-on-error: true`). It holds no credentials, and no test needs any. The project has no `application-it.yml`, because it calls no vendor system.

## Database setup

`src/main/resources/import.sql` is the original script copied unchanged. It creates database `company` and a password-protected application user granted access to it, then tables `employees` (102 rows) and `roles` (100 rows).

1. Run it against a local MySQL server as the original README instructs (`querying-a-mysql-database/README.md:22-29`), for example `mysql -u root -p < src/main/resources/import.sql`. MySQL 8 rejects the `GRANT … IDENTIFIED BY` form of line 5, and the `mysql` client stops there; on MySQL 8, run the script with that line replaced by `GRANT ALL PRIVILEGES ON company.* TO` the user line 3 creates.
2. Set `JDBC_URL` to `jdbc:mysql://<host>:3306/company?user=<user>&password=<password>` for that user and start the application.

The unit tier executes the same script on H2. Lines 1-9 are MySQL-only administration statements (`CREATE DATABASE`, `CREATE USER`, `GRANT`, `FLUSH PRIVILEGES`, `USE`) that fail on H2 and are skipped, as `application-test.yml` describes; every `CREATE TABLE` and `INSERT` succeeds, which `client/EmployeeRepositoryTest`'s row counts guard.

## Mule-to-Java mapping

| Mule construct (`querying-a-mysql-database`) | Java construct (`com.mulesoft.examples.querying_a_mysql_database`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`database-to-json.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (`database-to-json.xml:6`) | `controller/EmployeeQueryController`, `@RequestMapping("/")`: `query` (every method but `OPTIONS`) and `options` (`OPTIONS`), both giving the same reply; `lastname` parsed from the raw query string |
| MEL `#[message.inboundProperties['http.query.params'].lastname]` (`database-to-json.xml:8`) | `service/EmployeeQueryService.renderQueryValue`: a present value unchanged, a missing one as `null` (`MISSING_VALUE`) |
| `db:generic-config` (`com.mysql.jdbc.Driver`, `url="${jdbc.url}"`, `database-to-json.xml:3`) | `spring.datasource.url: ${jdbc.url}`, Boot's HikariCP `DataSource` and `JdbcTemplate` over `mysql-connector-j`; `config/DataSourceConfig` and `config/UnconfiguredDataSource` while the URL is blank |
| `db:select` with `db:dynamic-query` (`database-to-json.xml:7-11`) | `client/EmployeeRepository.findFirstNamesByLastName`: `JdbcTemplate.queryForList("select first_name from employees where last_name = ?", lastName)` |
| `json:object-to-json-transformer` (`database-to-json.xml:12`) | `service/EmployeeQueryService.findFirstNamesAsJson`: Jackson `ObjectMapper.writeValueAsString`, compact |
| Mule default exception strategy and listener default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `database-to-json.xml`) | `QueryingAMysqlDatabaseApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `QueryingMysqlDatabaseIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, `MySQLDbCreator`, `mule.test.properties`) | `QueryingMysqlDatabaseTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`, profile `test` over H2) |

## Behaviours stubbed, externalized or changed

- Externalized: the JDBC URL, and the credentials it carries, which the original README told users to write into `database-to-json.xml`, is the `jdbc.url` TODO key (`JDBC_URL`). Nothing else is configurable beyond `http.port`.
- Changed, the only intentional difference (AAP 0.4.10, root `DECISIONS.md` section 8): the original concatenated the query parameter into the SQL text; `client/EmployeeRepository` binds it as a JDBC parameter. Every legitimate value returns the same rows. A value containing a double quote or an SQL fragment, which made the original fail with an SQL error (500) or changed its query (for example `Puckett" or "1"="1` returned all 102 employees), now matches literally, usually `[]`.
- Replaced in tests: the live MySQL server, `MySQLDbCreator` and the never-committed `mule.test.properties` → H2 in MySQL mode seeded from `import.sql`; Mule `FunctionalTestCase` + `MuleClient` → `@SpringBootTest` + ST-1 `support/HttpTestClient`; the original 3 s start-up sleep is dropped, because the Spring test context is ready when the test runs.
- Nothing is stubbed at runtime. Studio metadata (`mule-project.xml`, `catalog/`) and `log4j2.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/querying-a-mysql-database-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
JDBC_URL='jdbc:mysql://localhost:3306/company?user=<user>&password=<password>' mvn spring-boot:run    # listener on 0.0.0.0:8081
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- No test needs MySQL, credentials or an external server.
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`. The JaCoCo gate covers `service/**`; the project has no `mapper` package.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `QueryingMysqlDatabaseTest` | the ported `QueryingMysqlDatabaseIT` (`querying-a-mysql-database/src/test/java/org/mule/examples/QueryingMysqlDatabaseIT.java:62-72`): `GET /?lastname=Puckett` with an empty body returns a non-null `200` response with a non-empty body exactly equal to `[{"first_name":"Chava"},{"first_name":"Quentin"}]`, over H2 seeded from `import.sql` |
| `controller/EmployeeQueryControllerTest` | on the wire through ST-1, over H2 with a spy repository: `GET /?lastname=Puckett` → `HTTP/1.1 200 OK`, `application/json`, the original reply; the same reply for `POST`, `PUT`, `DELETE` and `OPTIONS`; `lastname=Nobody` → `[]`; no `lastname` → `[]` with the string `null` bound; `lastname=Sosa&lastname=Puckett` → the Puckett rows, `Sosa` never queried; a form body `lastname=Puckett` is not read; `Puckett" or "1"="1` bound as data → `[]`; `/unknown` → 404 `text/plain` `No listener for endpoint: /unknown`; a failing repository → 500 `application/json` `{ "message": … }` |
| `service/EmployeeQueryServiceTest` | with a mocked repository: the original reply bytes for `Puckett`; `[]` for no rows; `null` rendered as `MISSING_VALUE` `null`; empty and quote-bearing values passed through unchanged; a `DataAccessException` propagates unchanged; a serialization failure becomes `UncheckedIOException` |
| `client/EmployeeRepositoryTest` | `@JdbcTest` over H2 in MySQL mode: `import.sql` seeds 102 employees and 100 roles; `Puckett` → `Chava`, `Quentin` in table order, each row keyed `first_name` only; `Nobody` and `null` → no rows; `Puckett" or "1"="1` → no rows; `puckett` matches case-insensitively; the bind-parameter SQL text |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message; a database failure answered with the driver's message rather than the SQL statement |
| `config/DataSourceConfigTest` | a blank `spring.datasource.url` (absent, empty or whitespace) starts the context with `UnconfiguredDataSource`, logs one WARN naming `jdbc.url` and `JDBC_URL` without any value, and fails the first query; a configured URL keeps Boot's Hikari pool and logs nothing |
| `config/UnconfiguredDataSourceTest` | both `getConnection` overloads fail with `SQLNonTransientConnectionException`, SQL state `08001`, message naming `jdbc.url` and `JDBC_URL` |
| `config/UnconfiguredDataSourceListenerTest` | the application started with `jdbc.url` empty: `GET /?lastname=Puckett` → 500 `application/json` `{ "message": "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured" }` and one ERROR line carrying SQL state `08001` |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/?lastname=Puckett` runs the flow and gets the plain `OPTIONS` reply (200 JSON, no `Access-Control-*` or `Vary`); a pre-flight to an unserved path gets the no-listener 404 |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/querying_a_mysql_database/FixtureParityTest.java` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed; its fixture format, replay drivers and comparison rules are documented once in the [`hello-world-java` README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds the fixture contract, the capture procedure and the database notes specific to this project.

- `FixtureParityTest` runs without profile `test`, so every fixture's `state.properties` points `jdbc.url` at an H2 database in MySQL mode seeded from `import.sql`. Every fixture except `get-unknown-path`, whose request reaches no store, also carries `state.tables`: the `employees` and `roles` rows read back from the MySQL server the original ran against. `upstream` is `[]` for every scenario: the original makes no outbound call, and its database is seeded state, not an upstream.
- Fixtures are never hand-written, and never edited to match the Java output. A mismatch is fixed in the Java code: SC-1 `GlobalExceptionHandler` for `get-unknown-path`, and `controller/EmployeeQueryController`, `service/EmployeeQueryService` or `client/EmployeeRepository` for the other scenarios.
- Neither hostile input nor the database-failure 500 is a parity scenario: the first falls under the intentional difference above, and the second carries the JDBC driver's text, which differs between the original's MySQL driver and the replay's H2.

This project's scenarios, exactly as `SCENARIOS.md` names them, all `deterministic`:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-lastname-puckett` | `http` | `deterministic` | `GET /?lastname=Puckett`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-lastname-puckett` | `http` | `deterministic` | `POST /?lastname=Puckett`, header `Content-Type: text/plain`, body `ignored` | `UNVERIFIED-uncaptured` |
| `get-root-lastname-unknown` | `http` | `deterministic` | `GET /?lastname=Nobody`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-missing-lastname` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-lastname-repeated` | `http` | `deterministic` | `GET /?lastname=Sosa&lastname=Puckett`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| querying-a-mysql-database-java | querying-a-mysql-database | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 135 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 135 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 9 of 9 lines of `service/EmployeeQueryService` (the project has no `mapper` package). Total project LINE coverage: 96.4% (214 of 222 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=6`, `failed=0`.

Tier 2A is blocked: the Mule EE licence and the EE Maven repository credentials are unconfirmed (root `DECISIONS.md`, section 9). Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `QueryingMysqlDatabaseTest` carries the original committed assertion: `GET /?lastname=Puckett` returns exactly `[{"first_name":"Chava"},{"first_name":"Quentin"}]`.
- `controller/EmployeeQueryControllerTest`, `service/EmployeeQueryServiceTest` and `client/EmployeeRepositoryTest` cover every reachable branch, on the wire and against the seeded tables.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the Mule listener and platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, covered only by the new tests: the no-match `[]` (`get-root-lastname-unknown`), the missing `lastname` (`get-root-missing-lastname`), a non-GET method (`post-root-lastname-puckett`), the repeated `lastname` (`get-root-lastname-repeated`), the unserved-path 404 (`get-unknown-path`) and the database-failure 500 (no scenario).
- Unverified scenarios, until captured: `get-root-lastname-puckett`, `post-root-lastname-puckett`, `get-root-lastname-unknown`, `get-root-missing-lastname`, `get-root-lastname-repeated`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `querying-a-mysql-database/`.
