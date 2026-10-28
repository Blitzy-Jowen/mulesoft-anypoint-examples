# document-integration-using-the-cmis-connector-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`document-integration-using-the-cmis-connector`](../../document-integration-using-the-cmis-connector/) (category General). The original folder `document-integration-using-the-cmis-connector/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

An HTTP request to `/cmis` loads a locally stored file, `mulesoft-logo.jpg`, and uploads it as a new document to the predefined folder `/okm:root/` of a CMIS repository, the public OpenKM demo. The ID of the uploaded document is returned as the HTTP response. The single flow `CMIS` of `document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:5-15` runs four elements in order, an HTTP listener, a Groovy component, the CMIS connector operation and an object-to-JSON transformer, and declares no logger and no exception strategy.

The original README checks the result in OpenKM. The converted project keeps that check:

1. Start the application with the CMIS account set (see [Configuration keys](#configuration-keys)) and browse to `http://localhost:8081/cmis`. Note the `id` in the response.
2. Navigate to `http://demo.openkm.com/OpenKM/login` and log in with the account you set as `cmis.username` and `cmis.password`.
3. Click the `okm:root` node in the left panel and verify that a document with the name in the `id` was uploaded.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/cmis` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, `Content-Type: application/json`, body `{"id":"<objectId>"}` (compact JSON, `/` unescaped); OpenKM object IDs are paths, for example `{"id":"/okm:root/pic1700000000000.jpg"}`; a `HEAD` reply carries the headers without the body, although the document is still created | `cmis-document-integration.xml:4-6,13-14` |
| any other path (`/cmis/`, `/cmis/x` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/cmis`, when the upload fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception: the flow declares no exception strategy |

- Each request uploads the 29,322 bytes of `mulesoft-logo.jpg`, read afresh for that request, as one new document: name `pic<System.currentTimeMillis()>.jpg`, folder path `/okm:root/`, MIME type `image/jpg`, object type `cmis:document`, versioning state `NONE` (`cmis-document-integration.xml:13`). The millisecond value is taken after the file is read, as Mule evaluated the `filename` expression in the connector after the Groovy component.
- The request's query, headers and body are never read. The original test sent `POST` with an empty body and the original README a browser `GET`; both are answered alike, and no `Accept` or `Content-Type` turns the request into a 406 or 415.
- Every method creates a document, `OPTIONS` included: `CmisController` maps `OPTIONS` explicitly, so Spring's own `Allow` reply never answers it. A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow like any other `OPTIONS` request and gets no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- The 500 body carries the failure's message. The AtomPub client uses fixed messages that name the failing step (`service document`, `folder entry` or `create-document`) and never copy the upstream body, reason phrase, header text or request URL:

  | Failure | Message in the 500 body |
  |---|---|
  | `cmis.username` or `cmis.password` blank | `CMIS service-account user name (cmis.username) is not configured`, or `CMIS service-account password (cmis.password) is not configured`; no request is made |
  | `cmis.base-url` blank | `CMIS service document URL (cmis.base-url) is not configured` |
  | Error status from the repository, for example a 401 for a rejected account | `CMIS <step> request failed with HTTP status <code>`, for example `CMIS service document request failed with HTTP status 401` |
  | Unreachable server, timeout | `CMIS <step> request failed with an I/O error`, `CMIS <step> request timed out` |
  | Missing workspace or link | `CMIS service document has no workspace for repository '<cmis.repository-id>'`, `CMIS workspace '<cmis.repository-id>' has no 'objectbypath' URI template`, `CMIS folder entry for path '/okm:root/' has no children link (rel=down, type=application/atom+xml;type=feed)`, `CMIS create-document response has no cmis:objectId` |
  | Unreadable response | `CMIS <step> response has no body`, `CMIS <step> response is not well-formed XML`, `CMIS <step> response has an invalid Content-Type` |

- SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- Tier 2A verification points (AAP 0.4.7): the bytes of the `404` and `500` defaults, the `500` message text, which the original took from the OpenCMIS exception, and the `Content-Type` of the `200` reply. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

Manual check, with the account set and the repository reachable (the `Date` value and the generated name vary):

```console
$ curl -i http://localhost:8081/cmis
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 39
Date: <current date>

{"id":"/okm:root/pic1793133982163.jpg"}
```

Without the account the same call answers, before any request leaves the host:

```console
$ curl -i http://localhost:8081/cmis
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 81
Date: <current date>

{ "message": "CMIS service-account user name (cmis.username) is not configured" }
```

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README tells users to set port 8081 and browse to `http://localhost:8081/cmis`. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`cmis-document-integration.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`cmis-document-integration.xml:4`) |
| `spring.application.name` | `String` | `document-integration-using-the-cmis-connector-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `CmisController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `cmis.base-url` | `String` | `http://demo.openkm.com/OpenKM/cmis/atom` | `cmis:config baseUrl` (`cmis-document-integration.xml:3`) | default | CMIS 1.1 AtomPub service-document URL of the repository |
| `cmis.repository-id` | `String` | `default` | `cmis:config repositoryId` (line 3) | default | repository whose workspace the upload uses |
| `cmis.username` | `String` | none (`${CMIS_USERNAME:}`) | `cmis:config username` (line 3) | TODO, environment `CMIS_USERNAME` (`# TODO: supply the OpenKM (CMIS) user name`) | the OpenKM service account, sent with HTTP Basic |
| `cmis.password` | `String` | none (`${CMIS_PASSWORD:}`) | `cmis:config password` (line 3) | TODO (secret), environment `CMIS_PASSWORD` (`# TODO: supply the OpenKM (CMIS) password`) | the OpenKM service account's password |
| `cmis.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every repository request, the Mule default response timeout |
| `cmis.read-timeout` | `Duration` | `10s` | none | default | read timeout of every repository request, the Mule default response timeout |
| `logging.level.org.apache.hc.client5.http.headers` | `LogLevel` | `INFO` | none | fixed | below `INFO` the httpclient5 transport under `CmisAtomPubClient` logs every request's and response's headers, the service account's `Authorization: Basic` value included; a named level wins over `logging.level.root`, so a root `DEBUG` or `TRACE` does not reach it |
| `logging.level.org.apache.hc.client5.http.wire` | `LogLevel` | `INFO` | none | fixed | below `INFO` the same transport logs every byte sent and received, the `Authorization` header among them; pinned for the same reason |

- `config/CmisProperties` binds the `cmis.*` keys and carries the same defaults for `cmis.base-url`, `cmis.repository-id` and both timeouts, so such a key left out of `application.yml` keeps its value. Its `toString()` masks the password.
- Original-to-new map: the `baseUrl`, `repositoryId`, `username` and `password` attributes hard-coded in `cmis:config` (`cmis-document-integration.xml:3`) become `cmis.base-url`, `cmis.repository-id`, `cmis.username` and `cmis.password`. The original's credential literals, `user3` and `pass3` (the public OpenKM demo login), are in no committed main configuration (AAP 0.4.6). Spring-context tests take them from `src/test/resources/application-test.yml` (Spring profile `test`); `CmisAtomPubClientTest`, which builds its client without a Spring context, reads them from the same file through `support/TestProfileValues`.
- Unset credentials do not stop the application from starting, because startup contacts no repository. They fail each `/cmis` request instead: the client checks `cmis.username`, then `cmis.password`, before its first request and answers the 500 above. No request is ever sent without an `Authorization` header.
- The project has no `application-it.yml`: it is not one of the connector projects.
- Override any key on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  export CMIS_USERNAME='<OpenKM user>' CMIS_PASSWORD='<OpenKM password>'
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  HTTP_PORT=9090 java -jar target/document-integration-using-the-cmis-connector-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`document-integration-using-the-cmis-connector`) | Java construct (`com.mulesoft.examples.document_integration_using_the_cmis_connector`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`cmis-document-integration.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="cmis"` without `allowedMethods` (line 6) | `controller/CmisController`, `@RequestMapping("/cmis")`: `createDocument` (every method but `OPTIONS`) and `createDocumentOnOptions` (`OPTIONS`), both returning the service's JSON as `application/json` |
| Groovy `scripting:component` `new FileInputStream("./src/main/resources/mulesoft-logo.jpg")` (lines 9-12) | `service/CmisDocumentService.createDocument()`, which reads the classpath resource `mulesoft-logo.jpg` (copied unchanged to `src/main/resources/`) for each request; no script engine |
| `cmis:config name="openkm"` (line 3) | `config/CmisProperties` (`cmis.*` keys) |
| `cmis:create-document-by-path-from-content` (line 13) | `client/CmisAtomPubClient.createDocumentByPath`, called by the service with the constants `FOLDER_PATH` (`/okm:root/`), `MIME_TYPE` (`image/jpg`), `OBJECT_TYPE` (`cmis:document`) and `VERSIONING_STATE` (`NONE`) and the name `pic<millis>.jpg` |
| `json:object-to-json-transformer` (line 14) | `CmisDocumentService` writes the connector's object ID as compact JSON `{"id":"<objectId>"}` with a private Jackson `ObjectMapper`, so no `spring.jackson.*` setting changes the bytes |
| Default exception strategy (no exception strategy declared) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `cmis-document-integration.xml`) | `DocumentIntegrationUsingTheCmisConnectorApplication` (`@SpringBootApplication`, `@EnableConfigurationProperties(CmisProperties.class)`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `DocumentIntegrationUsingTheCmisConnectorIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `DocumentIntegrationUsingTheCmisConnectorTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient` and a WireMock repository) |

## Behaviours stubbed or externalized

- CMIS transport. The original's Mule CMIS connector (`mule-module-cmis` 2.0.0) ran Apache Chemistry OpenCMIS over the AtomPub binding. `client/CmisAtomPubClient` is a hand-written CMIS 1.1 AtomPub client on Spring `RestClient`. OpenCMIS is not used because its client bindings declare a compile dependency on `org.apache.cxf:cxf-rt-frontend-jaxws`, which the `maven-enforcer-plugin` JAX-WS/CXF ban of every project rejects (AAP 0.3.3, 0.6.4; `DECISIONS.md` section 2). The client makes the same wire exchange as OpenCMIS: `GET` of the service document (`cmis.base-url`, `Accept: application/atomsvc+xml`) for the workspace of `cmis.repository-id` and its `objectbypath` URI template; `GET` of the folder entry for `/okm:root/` through that template for its children feed (`rel="down"`, `application/atom+xml;type=feed`); `POST` to that feed, with `?versioningState=none` appended, of an Atom entry carrying the logo as `cmisra:base64` content with media type `image/jpg` and the properties `cmis:objectTypeId` and `cmis:name`. It returns the created entry's `cmis:objectId` unchanged. Every request authenticates with HTTP Basic as `cmis.username` and `cmis.password`; responses are parsed with DOCTYPE declarations rejected and external entities and XInclude disabled.
- CMIS HTTP transport. Each request is sent once, and no call is retried after a timeout, a refused, reset or early-closed connection or an error status. `RestClient` sends through Apache httpclient5 (`org.apache.httpcomponents.client5:httpclient5`, version from the Spring Boot BOM) with its automatic retries switched off, because the JDK `HttpURLConnection` under Spring's default request factory re-sends a GET once when its connection is reset or closes before any response, and has no setting to stop it. In every other respect the transport behaves as `HttpURLConnection` did. `cmis.connect-timeout` bounds the connect, and `cmis.read-timeout` bounds each wait for response bytes, not the response as a whole. A GET follows a redirect with status 300, 301, 302, 303 or 307 that keeps its scheme, at most 20 requests in all, and a hop to another host or port carries no `Authorization` header; the POST follows no redirect. HTTP proxies come from the JVM's default proxy selector, and `https` uses the JSSE defaults. No `Accept-Encoding` or cookie is sent, and no authentication challenge is answered.
- CMIS session. The original connector opened its OpenCMIS session on the first request and kept it, so later requests could reuse its cached repository links and folder object. The Java client keeps no session state and makes all three calls on every request. Tier 2A captures each `/cmis` scenario on a freshly deployed runtime for that reason (`src/test/resources/fixtures/SCENARIOS.md`, Capture).
- Logo location. The Groovy component opened `./src/main/resources/mulesoft-logo.jpg` relative to the runtime's working directory (`cmis-document-integration.xml:11`). The service reads the same file, copied byte for byte, from the classpath, so the same 29,322 bytes are uploaded wherever the jar runs.
- Credentials. The account hard-coded in `cmis:config` is externalized to `cmis.username` and `cmis.password` with TODO values (AAP 0.4.6); see [Configuration keys](#configuration-keys).
- Tests replace the OpenKM demo server with WireMock stubs of its AtomPub surface (AAP 0.8.3), so `mvn test` needs no credential and contacts no external host. Live OpenKM behaviour is evidenced only by Tier 2A capture of the original. The public demo may no longer be online: on 2026-10-27 the default `cmis.base-url` answered `301 Moved Permanently` with an HTML page pointing to `https://demo.openkm.com/OpenKM/cmis/atom`, which itself redirects to an OpenKM contact page. The client does not follow a redirect that changes protocol, so with the default URL and an account set, `/cmis` then answers 500 `{ "message": "CMIS service document response is not well-formed XML" }`.
- Not a connector project (AAP 0.2.1): no failsafe plugin, no `*IT` live test, no `application-it.yml`, Tier 2B `N/A`.
- Studio metadata (`mule-project.xml`) and the original's `log4j2-test.xml` are not carried (AAP 0.2.2). No behaviour was dropped.

## Build, run and test

Run from `java-conversions/document-integration-using-the-cmis-connector-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
export CMIS_USERNAME='<OpenKM user>' CMIS_PASSWORD='<OpenKM password>'
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/document-integration-using-the-cmis-connector-java-1.0.0-SNAPSHOT.jar
```

- Point the application at another AtomPub repository with `--cmis.base-url=<service-document URL>` and `--cmis.repository-id=<id>`.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `DocumentIntegrationUsingTheCmisConnectorTest` | the ported `DocumentIntegrationUsingTheCmisConnectorIT` (`document-integration-using-the-cmis-connector/src/test/java/org/mule/examples/DocumentIntegrationUsingTheCmisConnectorIT.java:40-51`): `POST /cmis` with an empty body returns a response whose body is non-empty and starts with `{"id":"/okm:root/pic`. WireMock plays the OpenKM AtomPub surface and echoes the posted `atom:title` into the `cmis:objectId`, so the generated `pic<millis>.jpg` name is what the prefix check reads; the call is synchronous, so the original's five-second sleep is dropped |
| `controller/CmisControllerTest` | on the wire through ST-1: `GET`, `POST` with an empty body, `PUT` and `OPTIONS` on `/cmis` → `HTTP/1.1 200 OK`, `application/json`, `{"id":"<objectId>"}`, one document creation each; `/unknown` → 404 `text/plain` `No listener for endpoint: /unknown` with no repository call; a failing client → 500 `application/json` `{ "message": "boom" }`; a WireMock repository answering the service-document `GET` with 401 → 500 `{ "message": "CMIS service document request failed with HTTP status 401" }` after that one upstream call (`post-cmis-upstream-unauthorized`) |
| `service/CmisDocumentServiceTest` | with a mocked client: compact `{"id":"<objectId>"}`; one client call with `/okm:root/`, `pic<millis>.jpg`, `image/jpg`, `cmis:document`, `NONE` and the 29,322 logo bytes; the public constructor's current time and classpath logo; client failures propagate unchanged; a missing or failing logo raises `UncheckedIOException` without calling the client |
| `client/CmisAtomPubClientTest` | against WireMock, without a Spring context: the three AtomPub calls, their `Accept`, `Content-Type`, HTTP Basic header, `versioningState=none` and the posted entry; fixed messages without upstream body or URL for error statuses (401 included), read and connect timeouts, connection faults (every WireMock fault on either GET) and refusals, and an unparseable `Content-Type`, each request sent exactly once; redirects as `HttpURLConnection` followed them: a GET redirect that keeps its scheme followed with the service account, a hop to another host or port and every later hop without it, an `http`→`https` redirect and a 308 returned as received, the POST never redirected, a redirect loop ended after 20 requests; `IllegalStateException` for an unknown repository, a missing `objectbypath` template, a missing children link and a created entry without `cmis:objectId`; blank credentials rejected before any request |
| `CmisCredentialLoggingTest` | the whole application at `logging.level.root=TRACE`: the service account crosses the transport on all three requests but no `Authorization` header, Base64 token or credential reaches the log |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/cmis` runs the flow and gets exactly the plain `OPTIONS` reply (200 with the document ID as JSON, no `Access-Control-*` or `Vary`); a pre-flight to `/unknown` gets the no-listener 404 |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes. `support/TestProfileValues` reads a key of `src/test/resources/application-test.yml` from the test classpath without a Spring context, and fails with an `IllegalStateException` when the file is not on the classpath or holds no value, or an empty one, for the key. `client/CmisAtomPubClientTest` takes the demo login `cmis.username` and `cmis.password` from it, so no Java source repeats the values.

## Fixture parity (ST-2)

`FixtureParityTest` and the table contract of `src/test/resources/fixtures/SCENARIOS.md` are copied from the reference implementation in `hello-world-java`, whose README documents the fixture format, the replay drivers, the comparison rules and the summary statuses: [Fixture parity (ST-2 reference implementation)](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's [`SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md) adds what is specific to it:

- `state` holds only `properties`: every fixture points `cmis.base-url` at `${wiremock.baseUrl}/OpenKM/cmis/atom`, and the three `/cmis` fixtures also set `cmis.username` and `cmis.password` to the account of the original's `cmis:config`, whose HTTP Basic credentials the recorded requests carry.
- `upstream` of a `/cmis` scenario is the original's AtomPub exchange: the service-document `GET`, the `objectbypath` `GET` and the children-feed `POST`; `post-cmis-upstream-unauthorized` records the service-document `GET` alone, and `get-unknown-path` records `"upstream": []`.
- CMIS capture notes (section Capture): the original hard-codes its repository, so its traffic is recorded through WireMock as a JVM HTTP proxy; each `/cmis` scenario is captured on a freshly deployed runtime; a recording whose bodies name the OpenKM host, or whose requests carry a header only the proxy hop adds, omits `upstream` and is reported `UNVERIFIED-no-upstream`; if `demo.openkm.com` is unreachable, both create scenarios stay `UNVERIFIED-uncaptured`.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-cmis-create-document` | `http` | `nondeterministic` | `POST /cmis`, empty body | `UNVERIFIED-uncaptured` |
| `get-cmis-create-document` | `http` | `nondeterministic` | `GET /cmis`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |
| `post-cmis-upstream-unauthorized` | `http` | `deterministic` | `POST /cmis`, empty body; WireMock answers the service-document `GET` with 401 | `UNVERIFIED-uncaptured` |

- `post-cmis-create-document` and `get-cmis-create-document` are `nondeterministic`: the generated name `pic<millis>.jpg` and the object ID the repository assigns change per call. Once captured they are compared exactly on status, reason and headers and by structure on the body, and are reported `UNVERIFIED — nondeterministic output`, never as byte-for-byte evidence. `DECISIONS.md` lists them in its unverified-scenario register.
- `get-unknown-path` and `post-cmis-upstream-unauthorized` are `deterministic` and will be compared byte for byte.
- No scenario of this project is `unreachable`.
- With no fixtures `FixtureParityTest` reports skipped (`no Tier 2A fixtures captured`), never failed.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| document-integration-using-the-cmis-connector-java | document-integration-using-the-cmis-connector | General | PASS | PASS | 88.9% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The same row and labels belong in the root `SUMMARY.md` (AAP 0.8.7).

Evidence of the run behind this row, 2026-10-28 (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 172 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`, skipped by assumption while no fixture is captured); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 172 tests, 0 failures, 0 errors, 1 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 16 of 18 lines of `service/CmisDocumentService` (the project has no `mapper` package); the two missed lines are the `JsonProcessingException` branch, which serializing a one-entry map of strings cannot reach. Total project LINE coverage: 95.4% (481 of 504 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=4`, `failed=0`.
- A runtime check of the jar against a local WireMock repository returned `200 OK` `{"id":"/okm:root/pic<millis>.jpg"}` after the three AtomPub calls, and without credentials the 500 shown under [Endpoints and triggers](#endpoints-and-triggers). The boundary integration run on the combined tree re-measures this row.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`, as recorded in `DECISIONS.md` (section 9). No fixture can be captured from the original, so parity rests on the fallback:

- `DocumentIntegrationUsingTheCmisConnectorTest` carries the original committed assertion: the response to an empty `POST /cmis` is non-empty and starts with `{"id":"/okm:root/pic`.
- `CmisControllerTest`, `CmisDocumentServiceTest` and `CmisAtomPubClientTest` cover every reachable branch: the create-document path for every method, the no-listener 404 and the upstream failures that end in the 500.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent, nor that its AtomPub requests equal the ones OpenCMIS sent.
- Two branches have no original assertion and are covered only by the new tests: the no-listener 404 (`get-unknown-path`) and the upstream-failure 500 (`post-cmis-upstream-unauthorized`).
- Unverified scenarios, until captured: `post-cmis-create-document`, `get-cmis-create-document`, `get-unknown-path`, `post-cmis-upstream-unauthorized`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `document-integration-using-the-cmis-connector/` (`NOTICE`: "This product includes software developed at MuleSoft, Inc. (http://www.mulesoft.com/).").
