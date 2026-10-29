# Salesforce data retrieval — Java conversion

`salesforce-data-retrieval-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`salesforce-data-retrieval`](../../salesforce-data-retrieval/) (category Third-party connector, Salesforce). The package root is `com.mulesoft.examples.salesforce_data_retrieval` and the main class is `SalesforceDataRetrievalApplication`. The original folder `salesforce-data-retrieval/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A browser form lists the Salesforce objects of the configured org and runs a dynamic SOQL search over one of them. `GET /` asks Salesforce for every object type (`describeGlobal`) and returns an HTML form whose **Object** drop-down holds one option per object. Submitting the form (`POST /`) sends **Object**, **Field**, **Search Key** and **Search Value**. The flow builds a SOQL `queryAll` that selects `id`, `name` and the optional field of every record whose search key contains the search value, and returns the records as text. Invalid input is answered with `Invalid Salesforce query.` (`salesforce-data-retrieval/README.md:25-33`).

The flow file `salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml` holds two flows on one listener: `showFormFlow` (lines 7-24) and `salesforceDataRetrievalFlow` (lines 25-40). The original README's form rules still hold:

- **Field** may be empty. `Id` and `Name` are always selected, so entering either as **Field** duplicates a column; Salesforce rejects that query and the reply is `Invalid Salesforce query.`.
- **Field** may hold a comma-separated list (`email, phone`). Each entry is checked (Query hardening below), and the text reaches the SOQL unchanged.
- To look up the `sfdc.integration.user.id` that the Anypoint Salesforce templates need, submit Object `User`, Field empty, Search Key `UserName` and the value of `sfdc.username` as Search Value. The `Id` of the returned record is that user's ID (`salesforce-data-retrieval/README.md:37-46`).

## Endpoints and triggers

The listener binds to `0.0.0.0` on port `http.port`, default `8081` (`salesforce-id-retrieval.xml:5`).

| Method | Path | Input | Response | Source |
|---|---|---|---|---|
| `GET`, in any letter case | `/` | none; the body, query string and `Accept` are ignored | `200 OK`, `Content-Type: text/html;charset=UTF-8`: `retrieval/index.html` with `#[message.payload]` replaced by `<?xml version='1.0' encoding='UTF-8'?>` and a `<div>` holding one `<option value="<name>"><label></option>` per `describeGlobal` object, in Salesforce order | `showFormFlow`, `salesforce-id-retrieval.xml:7-24` |
| `POST`, in any letter case | `/` | form body (`Content-Type: application/x-www-form-urlencoded`) with `object`, `field` (may be empty), `searchKey`, `searchValue` | `200 OK`, no `Content-Type`, `Content-Length` of the UTF-8 bytes, the records of `queryAll` of `SELECT id, name [,<field>] from <object> where <searchKey> like '%<searchValue>%'` as text, for example `[{Email=..., Id=..., Name=..., type=User}, ...]`, `[]` when nothing matches | `salesforceDataRetrievalFlow`, `salesforce-id-retrieval.xml:25-36` |
| `POST` | `/` | any form whose query fails other than through the three upstream failure modes: an absent `field` parameter, an object or field Salesforce does not describe, a query Salesforce rejects, a body that is not a form | `200 OK`, `Content-Type: text/plain;charset=UTF-8`, body `Invalid Salesforce query.` (25 bytes, no line break) | `catch-exception-strategy`, `salesforce-id-retrieval.xml:37-39` |
| `POST` | `/` | a form body with a malformed percent escape (`object=%ZZ`) | `400 Bad Request`, no `Content-Type`, body `Cannot decode x-www-form-urlencoded payload`; the flow never runs | Mule 3.8 listener default |
| `GET`, `POST` | `/` | a request whose Salesforce call fails through an approved connector failure mode | `502`, `429` or `503`, `Content-Type: application/json` (next section) | AAP 0.4.8 addition |
| `GET` | `/` | a request whose `describeGlobal` fails otherwise (`showFormFlow` declares no exception strategy) | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | platform default |
| `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS` (a CORS pre-flight included), `TRACE`, and every other spelling that is not a letter case of `GET` or `POST` (`head`, `options`, `put`, ...) | `/` | any | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /` (SC-1) | `allowedMethods="GET"` (xml:8) and `allowedMethods="POST"` (xml:26) |
| any | any other path (`/favicon.ico`, `/error` included) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1) | both listeners serve only `/` |

- The POST form is read from the request body alone, as the Mule payload was: `controller/SalesforceRetrievalController#parseForm` decodes it and never consults the query string, so `POST /?object=user` without a form body is answered `Invalid Salesforce query.`. A repeated name keeps its last value; a name without `=` maps to `null`.
- A body with another `Content-Type` (JSON, multipart, none) yields an empty form, which fails on its absent `field` with `Invalid Salesforce query.`.
- The POST record text renders every record as the `java.util.HashMap` the connector returned (`client/SalesforcePartnerClient#toMap`, which adds the sObject `type` key), joined in a list as Mule's `List.toString()` joined them (`mapper/QueryResultFormatter`). The service logs the same text at INFO as `Result: <text>` (xml:36).
- `Accept` is never consulted and no 406 or 415 arises: every response but the record text presets its `Content-Type`, and the record text is written to the servlet response without one. The flow ends that payload in `set-payload value="#[response]"` and a `byte-array-to-string-transformer` (xml:34-35), neither of which declares a MIME type, and the Mule 3.8 listener sent no `Content-Type` for such an untyped payload.
- Method names are matched ignoring case, as Mule's `MethodRequestMatcher` did: on `/`, `get` and `post` in any letter case run the flows exactly as `GET` and `POST` (`config/ListenerMethodFilter`). Every other spelling (`head`, `options`, `put`, `trace`, unknown tokens) keeps the 405, and a lower-case `options` never becomes a CORS pre-flight.
- A CORS pre-flight to `/` (an `OPTIONS` request with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, with the 405 and no `Access-Control-*` or `Vary` header, because the original listeners applied no CORS policy (`config/PreflightDispatchConfig`). A pre-flight to an unserved path gets the no-listener 404.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

Exchanges measured on this build, run without credentials and shown here on the default port. Neither request contacts Salesforce: the first form lacks `field`, and the second path is not served.

```console
$ curl -i -X POST -H 'Content-Type: application/x-www-form-urlencoded' --data-binary 'object=User&searchKey=Name&searchValue=mule' http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/plain;charset=UTF-8
Content-Length: 25
Date: <current date>

Invalid Salesforce query.
$ curl -i http://localhost:8081/favicon.ico
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 38
Date: <current date>

No listener for endpoint: /favicon.ico
```

The first exchange is the catch branch, which keeps `Content-Type: text/plain;charset=UTF-8`. A query that succeeds is answered `200 OK` with its `Content-Length` and no `Content-Type`.

### Connector failure modes (AAP 0.4.8)

Every Salesforce call, the lazy SOAP `login` included, runs through SC-9 `client/UpstreamCallExecutor` and is classified by `client/SalesforceFailureClassifier`. These three responses are the only behaviour the original did not have. They take precedence over the `Invalid Salesforce query.` catch-all, because `exception/UpstreamExceptionHandler` runs at the highest `@Order`; the catch-all still answers every other failure.

| Failure | Behaviour | Response |
|---|---|---|
| Authentication or session expiry: an `ApiFault`/`LoginFault`, or a SOAP fault without detail, with code `INVALID_SESSION_ID` | log in again, then retry the call once; a second `INVALID_SESSION_ID` ends the attempt | `502 Bad Gateway`, `{ "message": "<upstream message>", "upstream": "Salesforce" }` |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | `429 Too Many Requests`, same body; no `Retry-After` header, because Salesforce sends none |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried | `503 Service Unavailable`, same body |
| Anything else (`INVALID_LOGIN`, `INVALID_TYPE`, `INVALID_FIELD`, `MALFORMED_QUERY`, ...) | rethrown unchanged | POST: `Invalid Salesforce query.`; GET: the SC-1 500 |

- Every body has `Content-Type: application/json`, with APIkit's spacing.
- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- With `sfdc.auth-endpoint` pointed at a closed port, both `GET /` and the POST above answer `503` with `{ "message": "Failed to send request to <endpoint>", "upstream": "Salesforce" }` (measured on this build).

### Query hardening (AAP 0.4.10)

The original concatenated the form values into SOQL (`salesforce-id-retrieval.xml:27`). `service/SalesforceRetrievalService#query` builds the same SOQL text, but checks the input first:

- `field` must be present, as `payload.field.isEmpty()` required. When it is absent, the request fails before any Salesforce call.
- `object` must be a name that `describeGlobal` lists.
- `searchKey` and each comma-separated `field` entry must be a field that `describeSObject(<object>)` lists, or a relationship path such as `Owner.Name` with at most five relationships. A path is resolved through the `describeSObject` of each type its reference fields refer to, and every segment must resolve. Names compare case-insensitively, as SOQL compares them.
- `searchValue` is escaped inside its string literal: backslash first, then single quote. `%` and `_` keep their `LIKE` meaning.

A name that does not match fails before `queryAll` and is answered `Invalid Salesforce query.`. Legitimate input yields the original SOQL and the same records. This is the only intentional difference for hostile input, recorded in the root `DECISIONS.md`.

A POST therefore makes calls the original did not make: `describeGlobal`, `describeSObject(<object>)` and one `describeSObject` per further type a relationship path reaches, each type at most once per request. Describe results are never cached across requests.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2), not an OAuth grant. The client logs in lazily on the first Salesforce call, so the application starts with every secret empty.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port`. The original's `mule-app.properties` is empty, so `http.port` had no value there; its README sets port 8081 (`salesforce-data-retrieval/README.md:22,25`). |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (xml:5), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (xml:5) |
| `spring.application.name` | `String` | `salesforce-data-retrieval-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE /` reaches the dispatcher and gets SC-1's 405 instead of the servlet's `TRACE` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | the `/` listeners accept `GET` and `POST` only and `POST /` reads the form off the body itself, so a form body on `PUT`, `PATCH` or `DELETE` gets SC-1's 405 unread, instead of being read whole by Boot's form-content filter before dispatch |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `sfdc.username` | `String` | none, `${SFDC_USERNAME:}` | `sfdc.username` (unchanged, xml:3) | TODO | the Salesforce user the `login` call authenticates |
| `sfdc.password` | `String` | none, `${SFDC_PASSWORD:}` | `sfdc.password` (unchanged, xml:3) | TODO (secret) | that user's password, without the security token |
| `sfdc.securityToken` | `String` | none, `${SFDC_SECURITYTOKEN:}` | `sfdc.securityToken` (unchanged, xml:3) | TODO (secret) | appended to the password at login |
| `sfdc.auth-endpoint` | `String` | `https://login.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL | default | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (AAP 0.4.8); a bare number binds as milliseconds; a value under 1 ms stops the application at startup |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call (AAP 0.4.8), with the same rules |

- The three credential keys keep the original placeholder names, so the original-to-new key map is the identity. The live tests read the same keys from `src/test/resources/application-it.yml`, where the original test read them from `mule.test.properties`.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and token.
- The project has no `src/test/resources/application-test.yml`: the unit tests mock Salesforce or stub it with WireMock and need no credential.
- Supply the credentials through the environment, never in a committed file:

  ```sh
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
  # a sandbox org on another port
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' java -jar target/salesforce-data-retrieval-java-1.0.0-SNAPSHOT.jar --http.port=9090 --sfdc.auth-endpoint=https://test.salesforce.com/services/Soap/u/65.0
  ```

## Mule-to-Java mapping

| Mule construct (`salesforce-data-retrieval`) | Java construct (`com.mulesoft.examples.salesforce_data_retrieval`) |
|---|---|
| `sfdc:config` with `${sfdc.username}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:3) | `client/SalesforcePartnerClient` in login mode, configured by `config/SalesforceProperties`; every call through `client/UpstreamCallExecutor` and `client/SalesforceFailureClassifier` |
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (xml:5) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `showFormFlow` listener `GET /` (xml:7-8) | `controller/SalesforceRetrievalController#showForm` → `service/SalesforceRetrievalService#showForm` |
| `sfdc:describe-global` (xml:9) | `SalesforcePartnerClient#describeGlobal` |
| `dw:transform-message` `div`/`option @(value: $.name): $.label` (xml:10-20) | `mapper/SObjectOptionsMapper#toOptionsXml` over SC-4 `mapper/DwXmlWriter` |
| `object-to-string-transformer mimeType="text/html"` (xml:21) | the mapper's `String`; the controller sets `Content-Type: text/html;charset=UTF-8` |
| `parse-template location="./src/main/resources/retrieval/index.html"` (xml:23) | SC-14 `mapper/ParseTemplateRenderer` over the classpath copy `src/main/resources/retrieval/index.html`, byte-identical to the original |
| `salesforceDataRetrievalFlow` listener `POST /` (xml:25-26) | `controller/SalesforceRetrievalController#query`, with `parseForm` building the Mule form payload |
| `sfdc:query-all` with the MEL-built SOQL (xml:27) | `SalesforceRetrievalService#query` (identifier checks and literal escaping, AAP 0.4.10) → `SalesforcePartnerClient#queryAll` |
| `set-variable` `response`, `foreach` appending each record, `set-payload`, `byte-array-to-string-transformer` (xml:28-35) | `mapper/QueryResultFormatter#format`; the controller writes the untyped text as UTF-8 bytes with `Content-Length` and no `Content-Type` |
| `logger level="INFO" message="Result: #[payload]"` (xml:36) | SLF4J INFO `Result: {}` in `SalesforceRetrievalService#query` |
| `catch-exception-strategy` setting `Invalid Salesforce query.` (xml:37-39) | `exception/SalesforceRetrievalExceptionHandler` (`@Order(0)`, scoped to the controller): POST failures → 200 `Invalid Salesforce query.`, GET failures → SC-1's 500 |
| Mule listener's 400 for an undecodable form body | `SalesforceRetrievalController#rejectUndecodableForm` |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse`; `SalesforceRetrievalController.MethodNotAllowedGuard` rejects `HEAD` and `OPTIONS` |
| Listener semantics: `allowedMethods` compared ignoring case (`MethodRequestMatcher`) | `config/ListenerMethodFilter`, scoped to `/`, canonicalizing only letter cases of `GET` and `POST` |
| Listener semantics: no CORS policy | `config/PreflightDispatchConfig` |
| (approved addition, no original construct) | SC-9 `exception/UpstreamExceptionHandler` and the three upstream exceptions |
| `mule-deploy.properties` (`config.resources=salesforce-id-retrieval.xml`) | `SalesforceDataRetrievalApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `SalesforceIdRetrievalIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, live org) | `SalesforceIdRetrievalTest` (surefire, mocked client) and `SalesforceIdRetrievalIT` (failsafe, live org) |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components:

- This project holds the reference implementations of **SC-9** (`client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException`, `exception/UpstreamUnavailableException`, `exception/UpstreamExceptionHandler`) and **SC-15** (`client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier`, `config/SalesforceProperties`).
- The other connector projects copy them with only the `package` line changed: the six other Salesforce projects copy SC-15, and every connector project (Salesforce, NetSuite, Workday, Dynamics) copies SC-9, `UpstreamExceptionHandler` only where HTTP-triggered. Each component's tests are copied with it.
- This project carries copies of SC-1 (`exception/GlobalExceptionHandler`, `exception/ErrorResponse`), ST-1 (`support/HttpTestClient`) and the listener-semantics class `config/PreflightDispatchConfig` with its `config/PreflightDispatchConfigTest` from `rest-api-with-apikit-java`, SC-4 `mapper/DwXmlWriter` from `web-service-consumer-java`, SC-14 `mapper/ParseTemplateRenderer` from `login-form-using-the-http-connector-java`, and ST-2 (`FixtureParityTest`, the `SCENARIOS.md` format) from `hello-world-java`, each with only its `package` line changed (register in the root `DECISIONS.md`). `config/ListenerMethodFilter` with its `config/ListenerMethodFilterTest`, the wire test `config/PreflightDispatchListenerTest` and the letter-case cases of `controller/SalesforceRetrievalControllerTest` are written for this project's two `/` listeners.

## Behaviours stubbed or externalized

- Externalized: the Salesforce credentials, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; the login URL and timeouts, defaults of the original connector, are configurable keys.
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` (`SalesforceIdRetrievalTest`, controller and service tests) or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`). At run time nothing is stubbed and no behaviour was dropped.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/test/resources/log4j2-test.xml` and the Studio sample `src/test/resources/sample_data/DescribeGlobalResult.dwl` (AAP 0.2.2).

Tier 2A verification points: the plan chose these values where the original platform, not the flow, fixed the bytes. When a captured fixture of the original shows different bytes, the fixture governs and the Java code is changed to match.

- The `Content-Type` of the `Invalid Salesforce query.` reply, `text/plain;charset=UTF-8`, because the catch branch's `set-payload` names no MIME type; and the absent `Content-Type` of the record reply, whose flow payload is untyped.
- The exact text of the POST records: `[` + each record's `HashMap` text `{K=V, …}` joined by `, ` + `]`, following Mule's `ObjectToString` and the MEL list concatenation of xml:31. The records carry the sObject `type` key, and their key order is `HashMap` iteration order.
- The XML indentation of the option block on `GET /`. SC-4 `DwXmlWriter` indents with tabs, as the DataWeave 1.0 output committed elsewhere in the repository does, while the captured page `src/test/resources/index.html` shows two-space indentation.
- The body of a failed `GET /` (SC-1's `{ "message": "<exception message>" }`), the 404 and 405 defaults, and the listener's 400 for an undecodable form.

## Build, run and test

Run from `java-conversions/salesforce-data-retrieval-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live tests (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free
mvn -B verify           # surefire tests, then the failsafe SalesforceIdRetrievalIT against a live org (Tier 2B)
mvn spring-boot:run     # listener on 0.0.0.0:8081; needs sfdc.username, sfdc.password and sfdc.securityToken
java -jar target/salesforce-data-retrieval-java-1.0.0-SNAPSHOT.jar
```

- `mvn test` needs no licence, credential or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub.
- Without the three `sfdc.*` values the application still starts, but every request that needs Salesforce fails before any Salesforce request: the lazy `login` first checks that `sfdc.username`, `sfdc.password` and `sfdc.securityToken` are non-blank and throws an `IllegalStateException` naming each missing key, never a value (for example `sfdc.username, sfdc.password and sfdc.securityToken are not configured`). That, like a rejected login (`INVALID_LOGIN`), is none of the three failure modes, so `GET /` answers the SC-1 500 with that message and the POST `Invalid Salesforce query.`.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

### Live tests (Tier 2B)

`SalesforceIdRetrievalIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials; no new sandbox is needed.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Run `mvn -B verify`. The test starts the application on a random port with the Spring profile `it`, which loads `application-it.yml`.

The IT is read-only, as the original was: it creates and deletes no record.

- `testDisplayData`: `GET /` answers 200 and contains at least one `<option value="...">...</option>`.
- `testGetData`: `POST /` with `object=user`, `field=email`, `searchKey=name`, `searchValue=mule` answers 200 and contains every line of `src/test/resources/reply.txt`. Those lines are `User` records of the original example's own org, so in another org the assertion holds only where equivalent users exist.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is disabled by its `LiveCredentialsGate` condition: both tests are reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `SalesforceIdRetrievalTest` | the ported `SalesforceIdRetrievalIT` (`salesforce-data-retrieval/src/test/java/org/mule/examples/SalesforceIdRetrievalIT.java:61-87`) over HTTP against a mocked `SalesforcePartnerClient`: `GET /` contains an option tag (the original regex), and the original `POST /` contains every line of `reply.txt` |
| `SalesforceIdRetrievalIT` | the same two assertions against a live org (failsafe, previous section) |
| `controller/SalesforceRetrievalControllerTest` | on the wire through ST-1: the form page bytes and `text/html;charset=UTF-8`, also with `Accept: application/json`; a failing `describeGlobal` → 500 `{ "message": "boom" }`; the original POST and its exact SOQL, its record text with `Content-Length` and no `Content-Type` (UTF-8 byte length for non-ASCII records, also with `Accept: application/json`); an empty `field` → `SELECT id, name  from user where name like '%mule%'`; an unknown object, a JSON body, a query-string-only form and a form without `field` → 200 `Invalid Salesforce query.`; `PUT`, `PATCH`, `DELETE`, `OPTIONS` → 405, and the whole 405 for a form declaring 300000 bytes sent with `PUT`, `PATCH` or `DELETE` while only its first 1024 bytes have been written, Salesforce never called, with no `FormContentFilter` bean in the context; `get`, `Get`, `gEt` → the form page and `post`, `Post`, `pOsT` → the record text, byte for byte as `GET` and `POST`; `options` (with and without pre-flight headers), `Options`, `put`, `delete`, `trace`, `postx` and `head` → 405; `/favicon.ico` → 404; 502, 429 and 503 ahead of both flows' own handling |
| `service/SalesforceRetrievalServiceTest`, `service/SalesforceRetrievalServicePathValidationTest` | the original SOQL; rejection of every identifier Salesforce does not describe before any query; relationship paths, polymorphic targets and one describe per type per request; describes read afresh per request; an absent `field` fails before Salesforce is called; vendor failures propagate unchanged; the `Result:` INFO log; the template rendering |
| `mapper/SObjectOptionsMapperTest`, `mapper/QueryResultFormatterTest` | the option block in Salesforce order with escaped names and labels, the captured page's options reproduced in order; the record text equals `List`/`HashMap` `toString()`, every `reply.txt` line contained |
| `mapper/DwXmlWriterTest`, `mapper/ParseTemplateRendererTest` | SC-4 XML bytes; SC-14 template substitution with the template kept byte-identical |
| `exception/SalesforceRetrievalExceptionHandlerTest` | POST failures → the catch branch's 200 `Invalid Salesforce query.`; a GET failure → SC-1's 500; upstream exceptions win on both; an unmapped path, a wrong method and the `HEAD`/`OPTIONS` guard keep SC-1's 404 and 405; the advice's scope and `@Order`; a successful POST left untouched, with its `Content-Length` and no `Content-Type` |
| `exception/UpstreamExceptionHandlerTest`, `client/UpstreamCallExecutorTest`, `client/SalesforceFailureClassifierTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, 502/429/503 bodies, precedence over a catch-all advice |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, `HashMap` records, session mode, the failure modes per operation, the 10 s default timeouts; concurrent callers sharing the one login, re-login or session refresh in flight, its timeout included; paging that fails, with no further `queryMore`, on a repeated query locator, records beyond the reported size or pages beyond what that size allows |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes |
| `config/ListenerMethodFilterTest` | the filter alone: letter cases of `GET` and `POST` on `/` (also with a query string or under a context path) reach the chain as `GET` and `POST` with everything else unchanged; other method tokens on `/`, letter cases on any other path, and a request without a method or URI pass unchanged |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` asking for any method, from any origin, gets exactly the plain `OPTIONS` reply (the 405), runs no flow and carries no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404; cross-origin `GET` and `POST` run both flows without CORS headers |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of `src/test/resources/fixtures/SCENARIOS.md`, which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-form` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-query-user-email` | `http` | `deterministic` | `POST /`, form `object=user&field=email&searchKey=name&searchValue=mule` | `UNVERIFIED-uncaptured` |
| `post-root-query-without-field` | `http` | `deterministic` | `POST /`, form `object=User&field=&searchKey=Name&searchValue=mule` | `UNVERIFIED-uncaptured` |
| `post-root-invalid-object` | `http` | `deterministic` | `POST /`, form `object=NoSuchObject&field=&searchKey=Name&searchValue=x` | `UNVERIFIED-uncaptured` |
| `post-root-missing-field-parameter` | `http` | `deterministic` | `POST /`, form `object=User&searchKey=Name&searchValue=mule` | `UNVERIFIED-uncaptured` |
| `put-root-method-not-allowed` | `http` | `deterministic` | `PUT /`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /favicon.ico`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-reauthentication-failed` | `http` | `deterministic` | `POST /`, original form; session rejected again after the re-login | `UNVERIFIED-uncaptured` |
| `post-root-rate-limited` | `http` | `deterministic` | `POST /`, original form; `REQUEST_LIMIT_EXCEEDED` | `UNVERIFIED-uncaptured` |
| `post-root-upstream-timeout` | `http` | `deterministic` | `POST /`, original form; no answer within `sfdc.read-timeout` | `UNVERIFIED-uncaptured` |
| `post-root-upstream-unreachable` | `http` | `deterministic` | `POST /`, original form; refused connection | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`.
- The last four rows are the approved connector failure modes (AAP 0.4.8). The original has no such branch, so they are never captured; their Tier 1 evidence is the client, classifier, executor, handler and controller tests above.
- The four rows that reach Salesforce (`get-root-form`, `post-root-query-user-email`, `post-root-query-without-field`, `post-root-invalid-object`) record live credentials and session values in their upstream exchanges, and the Java POST makes describe calls the original did not make. They therefore omit `upstream` and stay `UNVERIFIED-no-upstream` after capture.
- Capture can raise the label at most to `PARITY: PARTIAL — Tier 2A fixtures: 3 verified, 8 unverified`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| salesforce-data-retrieval-java | salesforce-data-retrieval | Third-party connector | PASS | PASS | 99.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

This row equals the project's row in the root `SUMMARY.md`. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9); the test counts and coverage figures below were re-measured on 2026-10-29 with `mvn -B clean install` and `mvn -B test`, on the tree whose SC-4 `DwXmlWriter` refuses element, prefix and attribute names that are not XML names and whose `application.yml` switches Boot's form-content filter off:

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 1256 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 2 tests, both skipped (`SalesforceIdRetrievalIT`, no `application-it.yml`).
- `mvn -B test`: `BUILD SUCCESS`; 1256 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 413 of 417 lines (99.0%) of `service/SalesforceRetrievalService`, `mapper/SObjectOptionsMapper`, `mapper/QueryResultFormatter`, `mapper/DwXmlWriter` and `mapper/ParseTemplateRenderer`. Total project LINE coverage: 96.0% (1163 of 1211 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=11`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `SalesforceIdRetrievalIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `SalesforceIdRetrievalTest` carries the original committed assertions with their comparison semantics: the option regex on `GET /`, and every line of `reply.txt` contained in the original `POST /` response.
- `controller/SalesforceRetrievalControllerTest` and the service tests cover every reachable branch on the wire or against the service.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the platform defaults, but not that its bytes equal what the original runtime sent. The Salesforce data in these tests is mocked, not recorded from the original's org.
- Branches no original assertion covers, tested only by the new tests: the POST catch branch `Invalid Salesforce query.` (`post-root-invalid-object`, `post-root-missing-field-parameter`), the empty-field query (`post-root-query-without-field`), the 405 and 404 defaults (`put-root-method-not-allowed`, `get-unknown-path`), the 500 of a failed `GET /`, the 400 for an undecodable form, and the four approved connector failure modes.
- Unverified scenarios, until captured: `get-root-form`, `post-root-query-user-email`, `post-root-query-without-field`, `post-root-invalid-object`, `post-root-missing-field-parameter`, `put-root-method-not-allowed`, `get-unknown-path`, `post-root-reauthentication-failed`, `post-root-rate-limited`, `post-root-upstream-timeout`, `post-root-upstream-unreachable`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `salesforce-data-retrieval/`.
