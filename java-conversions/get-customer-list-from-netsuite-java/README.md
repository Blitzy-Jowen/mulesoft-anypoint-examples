# Get Customer List from NetSuite (Spring Boot)

`get-customer-list-from-netsuite-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`get-customer-list-from-netsuite`](../../get-customer-list-from-netsuite/) (category Third-party connector, NetSuite, HTTP-triggered). It lists the NetSuite customers whose last name starts with a given prefix and renders them in an HTML table. The package root is `com.mulesoft.examples.get_customer_list_from_netsuite` and the main class is `GetCustomerListFromNetsuiteApplication`. The original folder `get-customer-list-from-netsuite/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

The original connects to NetSuite and retrieves the customers whose last name starts with the value of the `lastName` query parameter, for example every customer whose last name starts with `a`. It formats them as table rows and returns them in an HTML page (`get-customer-list-from-netsuite/README.md`, Example Use Case).

The single flow `get-customer-list-from-netsuiteFlow` of `get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml` (lines 7-36) runs these steps:

1. `http:listener` on `/customers` (xml:8).
2. An `expression-filter` that drops a request whose `lastName` query parameter is `null` (xml:9).
3. `netsuite:query-records` with the DSQL `SELECT email,firstName,lastName FROM CUSTOMER WHERE lastName like '<lastName>%' ORDER BY lastName ASC` (xml:10).
4. A `%dw 1.0` script that writes one `line/tr/td` row per customer, or the single row `No customers found` (xml:11-33).
5. `object-to-string-transformer mimeType="text/html"` (xml:34).
6. `parse-template` of `src/main/resources/customer/index.html`, whose Groovy expression drops the XML declaration line (xml:35).

The connector logged in to SuiteTalk SOAP with `netsuite.email`, `netsuite.password`, `netsuite.account` and `netsuite.roleId` (xml:4). The flow declares no logger and no exception strategy.

## Endpoints and triggers

The listener binds to `0.0.0.0` on port `http.port`, default `8081` (`get-customer-list-from-netsuite.xml:5`).

| Method | Path | Input | Response | Source |
|---|---|---|---|---|
| any | `/customers` | URL query `lastName=<prefix>`; an empty value (`lastName=`) is accepted and matches every customer, as the original's `like '%'` did | `200 OK`, `Content-Type: text/html;charset=UTF-8`: `customer/index.html` (heading `Netsuite Customer List`, columns Last Name / First Name / Email) with one row per customer whose last name starts with the prefix, compared case-insensitively, ordered by last name | xml:8-35 |
| any | `/customers` | `lastName=<prefix>` that no customer matches | `200 OK`, `Content-Type: text/html;charset=UTF-8`, the same page with the single row `No customers found` | xml:26-31 |
| any | `/customers` | no `lastName` in the URL query, or `lastName` without `=` (`?lastName`) | `200 OK`, empty body, `Content-Length: 0`, no `Content-Type`; NetSuite is not called | expression filter, xml:9; filter-rejection platform default (AAP 0.4.7) |
| any | `/customers` | a request whose NetSuite call fails through an approved connector failure mode | `502`, `429` or `503`, `Content-Type: application/json` (Connector failure modes below) | AAP 0.4.8 addition |
| any | `/customers` | any other failure: a missing or invalid `netsuite.*` setting, a query NetSuite rejects | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1); for a NetSuite error answer the message is its status code and reason phrase, for example `{ "message": "400 Bad Request" }`, never the request method or URL | platform default; the flow has no exception strategy |
| any | any other path (`/customers/`, `/error` included) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1) | the listener serves `/customers` only |
| any | any path | a URL query with a malformed percent escape (`?lastName=%ZZ`) | `400 Bad Request`, empty body, `Connection: close`; Undertow rejects the request before the application runs | Undertow default |

- Every method is accepted, because the original listener declares no `allowedMethods`: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS` and `TRACE` get the same answer, and a `HEAD` reply carries the headers without the body. The request body, `Accept` and `Content-Type` are ignored and never answered with 405, 406, 415 or 400.
- Only the URL query string is read, as Mule's `http.query.params` held it (`controller/NetsuiteCustomerController#queryParameter`). Pairs are split on `&` and on their first `=`, names and values are URL-decoded as UTF-8 (`+` as a space), and names compare case-sensitively. A repeated `lastName` keeps its last value, and a bare `lastName` is `null`. A form field `lastName=a` in a `POST` body is not a query parameter, so the filter rejects that request.
- The rows come from SuiteQL in query order. Each row holds three cells: last name, first name, email. A missing value is written as `<td/>`, and `&`, `<` and `>` are escaped. The block is SC-4 `DwXmlWriter` output (tab indentation, LF line ends) without its declaration line, placed between `<tbody>` and `</tbody>` of the byte-identical template copy `src/main/resources/customer/index.html`. One customer renders as:

  ```html
  		<tbody><div>
  	<line>
  		<tr>
  			<td>Abbott</td>
  			<td>Byron</td>
  			<td>ab@example.com</td>
  		</tr>
  	</line>
  </div></tbody>
  ```

- SuiteQL results are read 1000 rows per page until NetSuite reports `hasMore` `false`; each page is its own NetSuite call. Paging is bounded: a page that still reports `hasMore` `true` although the next offset reaches its `totalResults`, or a 1000th page that still reports it (1,000,000 rows, ten times SuiteQL's 100,000-result ceiling), fails the request with SC-1's 500 `{ "message": "Unexpected NetSuite response: ..." }`, as a malformed page does, instead of paging on.
- A CORS pre-flight to `/customers` (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request, whatever method it asks for: it runs the flow once and gets the page, the empty 200 of the filter rejection, or the 502, 429, 503 or 500 of a failed call, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`). A pre-flight to any other path gets the no-listener 404.
- Nothing logs NetSuite data: the flow has no logger, and `application.yml` holds the Spring codec and response-body loggers and Undertow's request-parse logger at `INFO` and the `DispatcherServlet` at `WARN`, so a root `DEBUG` or `TRACE` level cannot write the access token, customer data or the `lastName` searched for.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

The original README's request, which needs the NetSuite keys of the configuration section:

```sh
curl -i "http://localhost:8081/customers?lastName=a"
```

Exchanges measured on this build, run without credentials and shown here on the default port:

```console
$ curl -i http://localhost:8081/customers
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 0
Date: <current date>

$ curl -i "http://localhost:8081/customers?lastName=a"
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 51
Date: <current date>

{ "message": "netsuite.account is not configured" }
$ curl -i http://localhost:8081/customers/
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 37
Date: <current date>

No listener for endpoint: /customers/
```

With dummy OAuth values, a throwaway key and `--netsuite.base-url=http://127.0.0.1:1` (a closed port), the same `lastName=a` request is answered `503 Service Unavailable` with `{ "message": "NetSuite is unreachable", "upstream": "NetSuite" }`.

### Connector failure modes (AAP 0.4.8)

Every NetSuite call, the token request included, runs through SC-9 `client/UpstreamCallExecutor` and is classified by SC-16 `client/NetsuiteFailureClassifier`. These three responses are the only behaviour the original did not have.

| Failure | Behaviour | Response |
|---|---|---|
| Authentication or token expiry: HTTP 401 from the token endpoint or from SuiteQL | the cached token is evicted (`NetsuiteTokenProvider.evict()`), a new token is requested and the call is retried once; a second 401 ends the attempt | `502 Bad Gateway`, `{ "message": "<upstream message>", "upstream": "NetSuite" }` |
| Throttling: HTTP 429 | no retry | `429 Too Many Requests`, same body, with NetSuite's `Retry-After` header copied when it sent one |
| Timeout or connectivity failure anywhere in the cause chain (Netty `ReadTimeoutException`, `SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried | `503 Service Unavailable`, same body |
| Anything else (a 400 for an invalid query, 403, 404, every 5xx, a missing setting) | rethrown unchanged | SC-1's 500; for a NetSuite error answer its message is the status code and reason phrase (for example `400 Bad Request`), never the request method or URL |

- Every body has `Content-Type: application/json`, with APIkit's spacing. `<upstream message>` is NetSuite's own error text from the response body (`o:errorDetails[0].detail`, else `title`, else the OAuth `error_description`, else `error`). Without such text it is `NetSuite answered HTTP <status> <reason phrase>`, and for a request that got no response `NetSuite did not respond in time` or `NetSuite is unreachable`. It never carries the request URL, a header or the token.
- `exception/UpstreamExceptionHandler` runs at the highest `@Order`, ahead of SC-1.
- The connect and read timeouts are 10 s each (`netsuite.connect-timeout`, `netsuite.read-timeout`), the Mule default response timeout.

### Query hardening (AAP 0.4.10)

The original concatenated `lastName` into its DSQL (xml:10). `service/NetsuiteCustomerService#customerQuery` builds the SuiteQL equivalent:

```sql
SELECT email, firstname, lastname FROM customer WHERE LOWER(lastname) LIKE LOWER('<prefix>%') ORDER BY lastname ASC
```

- The prefix is placed in the SuiteQL string literal through `NetsuiteRestClient.suiteQlLiteral`, which doubles each `'` (`O'Brien` → `O''Brien`). A backslash is an ordinary character in a SuiteQL literal and is left unchanged; `%` and `_` keep their `LIKE` meaning. A hostile `lastName` therefore cannot alter the query, and every legitimate prefix selects the rows the original selected. This is the only intentional difference for hostile input, recorded in the root `DECISIONS.md` (section 8).
- `LOWER(...)` on both sides makes the prefix match case-insensitive, reproducing the SuiteTalk `startsWith` search the connector compiled the DSQL `like` into, because SuiteQL `LIKE` is case-sensitive.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. NetSuite authentication changes from the original's SuiteTalk SOAP login (email, password, account, role) to REST web services and SuiteQL with OAuth 2.0 machine-to-machine (M2M) authentication: the client credentials grant with a JWT client assertion signed PS256, header `kid` = certificate ID, claims `iss` = client ID, `scope` = `rest_webservices`, `aud` = the token URL. The token is cached until 60 s before the expiry NetSuite states. Settings are read on the first NetSuite call, so the application starts with every `TODO` key empty, and that call fails with SC-1's 500 naming the missing key.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6); `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour; `unset` — read by the application's code but not set in `application.yml`, so the stated default applies.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port`. The original's `mule-app.properties` leaves it empty; its README sets port 8081 and browses to `http://localhost:8081/customers?lastName=a`. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (xml:5), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (xml:5) |
| `spring.application.name` | `String` | `get-customer-list-from-netsuite-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE /customers` reaches the controller like every other method, instead of the servlet's `TRACE` echo |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `netsuite.account` | `String` | none, `${NETSUITE_ACCOUNT:}` | `netsuite.account` (unchanged, xml:4) | TODO | the NetSuite account ID (Setup > Company > Company Information), for example `1234567` or `1234567_SB1` |
| `netsuite.oauth.client-id` | `String` | none, `${NETSUITE_OAUTH_CLIENT_ID:}` | none; with the other two `netsuite.oauth.*` keys it replaces `netsuite.email` and `netsuite.password` | TODO | client ID of the integration record; the assertion's `iss` claim |
| `netsuite.oauth.certificate-id` | `String` | none, `${NETSUITE_OAUTH_CERTIFICATE_ID:}` | as `netsuite.oauth.client-id` | TODO | ID NetSuite assigned to the uploaded M2M certificate; the assertion's `kid` header |
| `netsuite.oauth.private-key-path` | `String` | none, `${NETSUITE_OAUTH_PRIVATE_KEY_PATH:}` | as `netsuite.oauth.client-id` | TODO | file path or `classpath:` location of the certificate's private key, the secret of this integration, kept outside the repository: unencrypted PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`), RSA of at least 2048 bits. A PKCS#1 key (`-----BEGIN RSA PRIVATE KEY-----`) is rejected; convert it with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem` |
| `netsuite.base-url` | `String` | none, `${NETSUITE_BASE_URL:}` | none | TODO (optional) | scheme and host of the NetSuite REST services. Empty → `https://<account>.suitetalk.api.netsuite.com`, with the account ID lower-cased and `_` replaced by `-` (`1234567_SB1` → `1234567-sb1`). A set value must be an absolute `http` or `https` URL without user info, query or fragment; tests point it at WireMock |
| `netsuite.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every NetSuite call (AAP 0.4.8); a value under 1 ms stops the application at startup |
| `netsuite.read-timeout` | `Duration` | `10s` | none | default | response timeout of every NetSuite call, with the same rules |
| `netsuite.time-zone` | `ZoneId` | `America/Los_Angeles` | none | default | part of the SC-16 configuration; `netsuite-data-retrieval-java` renders record dates with it, this flow reads no date |
| `netsuite.custom-field-type-ids` | `Map<String, String>` | empty | none | unset | part of the SC-16 configuration; only `netsuite-data-retrieval-java` reads it (select-field `typeId` overrides) |
| `logging.level.org.springframework.core.codec`, `logging.level.org.springframework.http.codec`, `logging.level.org.springframework.web.HttpLogging`, `logging.level.org.springframework.web.servlet.mvc.method.annotation` | `LogLevel` | `INFO` | none | fixed | below `INFO` these categories write the token response, the SuiteQL request and response bodies and the rendered page; held at `INFO` so no NetSuite data is logged, as the original logged none |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | below it the servlet writes the request line with its query (the `lastName` value) and, with `spring.mvc.log-request-details`, the parameters and headers. `WARN`, not `INFO`: its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.io.undertow.request.io` | `LogLevel` | `INFO` | none | fixed | below `INFO` Undertow writes the request-parse failures, which quote a query value that does not decode |

Original-to-new key map (also in the root `DECISIONS.md`):

| Original key (`mule-app.properties`, xml:4) | New key |
|---|---|
| `netsuite.account` | `netsuite.account` (unchanged) |
| `netsuite.email`, `netsuite.password` | `netsuite.oauth.client-id`, `netsuite.oauth.certificate-id`, `netsuite.oauth.private-key-path` |
| `netsuite.roleId` | none: the role is bound to the certificate mapping in NetSuite |
| `http.port` | `http.port` (unchanged) |

The SuiteTalk SOAP login is replaced following Oracle's schedule: REST + OAuth 2.0 for new integrations from release 2026.1, no new SOAP integrations from 2027.1, SOAP disabled in 2028.2.

- `config/NetsuiteProperties` binds the `netsuite.*` keys. A missing or invalid account, OAuth or base-URL value (an unreadable or PKCS#1 private key included) fails on first use with an `IllegalStateException` naming the key, never repeating the configured value. The timeouts and the time zone are checked at start-up instead: a value that does not convert fails binding, and a timeout shorter than 1 ms, or longer than 2147483647 ms (connect) or 9223372036854775807 ms (read), stops start-up when the NetSuite clients are created, with an `IllegalStateException` naming the key. The time zone is bound, and so checked, even though this flow reads no date.
- The unit tests use the Spring profile `test` (`src/test/resources/application-test.yml`): dummy account and OAuth identifiers, a 2 s read timeout, and `netsuite.base-url` and the private key supplied by each test (WireMock and a key generated for the run). None authenticates anywhere.
- Supply the values through the environment, never in a committed file:

  ```sh
  NETSUITE_ACCOUNT='<account ID>' NETSUITE_OAUTH_CLIENT_ID='<client ID>' NETSUITE_OAUTH_CERTIFICATE_ID='<certificate ID>' NETSUITE_OAUTH_PRIVATE_KEY_PATH='/path/to/key-pkcs8.pem' mvn spring-boot:run
  ```

### NetSuite setup (requester)

The requester registers the credentials inside the existing NetSuite account; this project does not create them and no new sandbox is procured:

1. An integration record with the OAuth 2.0 **Client Credentials (Machine to Machine) Grant** and the **REST Web Services** scope. Its client ID is `netsuite.oauth.client-id`.
2. A certificate (RSA 3072 or 4096 bits) uploaded under **Setup > Integration > OAuth 2.0 Client Credentials (M2M) Setup**, mapped to the integration record, an entity and a role. The role needs **REST Web Services**, **Log in using OAuth 2.0 Access Tokens** and **Customers** (Full, for the live test's create and delete). The certificate ID NetSuite shows is `netsuite.oauth.certificate-id`, and the certificate's private key, in PKCS#8 PEM form, is the file `netsuite.oauth.private-key-path` names.

## Mule-to-Java mapping

| Mule construct (`get-customer-list-from-netsuite`) | Java construct (`com.mulesoft.examples.get_customer_list_from_netsuite`) |
|---|---|
| `netsuite:config` with `${netsuite.account}`, `${netsuite.email}`, `${netsuite.password}`, `${netsuite.roleId}` (xml:4) | SC-16 OAuth 2.0 M2M: `client/NetsuiteTokenProvider` with `config/NetsuiteProperties`; every call through `client/UpstreamCallExecutor` and `client/NetsuiteFailureClassifier` |
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (xml:5) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/customers"` without `allowedMethods` (xml:8) | `controller/NetsuiteCustomerController`, `@RequestMapping("/customers")`: `customers` (every method but `OPTIONS`) and `customersOptions` (`OPTIONS`), both giving the same answer |
| Listener semantics: no CORS policy, so a pre-flight is an ordinary `OPTIONS` request | `config/PreflightDispatchConfig` |
| `expression-filter` `http.query.params["lastName"] != null` (xml:9) | `NetsuiteCustomerController#queryParameter` reads the value; the guard in `service/NetsuiteCustomerService#customerListPage` returns no page, and the controller commits the empty 200 |
| `netsuite:query-records` with the `dsql:` query (xml:10) | `NetsuiteCustomerService#customerQuery` (SuiteQL, AAP 0.4.10) → SC-16 `client/NetsuiteRestClient#suiteQl` |
| `dw:transform-message` "Customer List to XML" (`%dw 1.0`, header line 13) | `mapper/CustomerHtmlMapper#toXml` over SC-4 `mapper/DwXmlWriter` |
| `object-to-string-transformer mimeType="text/html"` (xml:34) | the mapper's `String`; the controller sets `Content-Type: text/html;charset=UTF-8` |
| `parse-template location="./src/main/resources/customer/index.html"` (xml:35) with `#[groovy: payload.tokenize('\n')[1..-1].join('\n')]` | `NetsuiteCustomerService#tableBody` (the Groovy expression in Java, no script engine) + SC-14 `mapper/ParseTemplateRenderer` over the classpath copy `src/main/resources/customer/index.html` |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| (approved addition, no original construct) | SC-9 `exception/UpstreamExceptionHandler` and the three upstream exceptions |
| `mule-deploy.properties` (loads `get-customer-list-from-netsuite.xml`) | `GetCustomerListFromNetsuiteApplication` (`@SpringBootApplication`) |
| `mule-app.properties` | `src/main/resources/application.yml` |
| `GetCustomersListFromNetsuiteIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, live account) | `GetCustomersListFromNetsuiteTest` (surefire, WireMock NetSuite) and `GetCustomersListFromNetsuiteIT` (failsafe, live account) |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components:

- This project holds the reference implementation of **SC-16**, the NetSuite REST client: `client/NetsuiteTokenProvider`, `client/NetsuiteRestClient`, `client/NetsuiteFailureClassifier`, `config/NetsuiteProperties` and their tests `client/NetsuiteTokenProviderTest`, `client/NetsuiteRestClientTest`, `client/NetsuiteFailureClassifierTest`. `netsuite-data-retrieval-java` copies them unchanged apart from the `package` line. This project calls only `NetsuiteRestClient#suiteQl`; the record API, metadata catalog and custom-field source lookup serve that copy.
- This project carries copies of SC-1 (`exception/GlobalExceptionHandler`, `exception/ErrorResponse`) and ST-1 (`support/HttpTestClient`) from `rest-api-with-apikit-java`, SC-4 `mapper/DwXmlWriter` from `web-service-consumer-java`, SC-9 including `exception/UpstreamExceptionHandler` from `salesforce-data-retrieval-java`, SC-14 `mapper/ParseTemplateRenderer` from `login-form-using-the-http-connector-java`, and ST-2 (`FixtureParityTest`, the `SCENARIOS.md` format) from `hello-world-java`, each with only its `package` line changed and its tests copied with it (register in the root `DECISIONS.md`).
- `config/PreflightDispatchConfig` and its `config/PreflightDispatchConfigTest` are the listener-semantics classes the other HTTP projects carry, copied from `rest-api-with-apikit-java` with only the `package` line changed; the wire test `config/PreflightDispatchListenerTest` is written for this project's listener.

## Behaviours stubbed or externalized

- Replaced: the SuiteTalk SOAP connector and its email/password/role login become NetSuite REST + SuiteQL with OAuth 2.0 M2M (SC-16); `netsuite.roleId` has no key.
- Externalized: the NetSuite account and OAuth credentials are `TODO` keys with environment-variable fallbacks; the base URL and timeouts, which the original connector fixed, are configurable keys.
- Stubbed in tests only: the unit tier replaces NetSuite with an in-process WireMock server behind `netsuite.base-url` and a private key generated for the run. At run time nothing is stubbed and no behaviour was dropped.
- Carried: the template `customer/index.html` (byte-identical) and the test input `src/test/resources/example.xml`, the original's sample page block, which `mapper/CustomerHtmlMapperTest` compares structurally.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/test/resources/log4j2-test.xml` and the Studio sample `src/test/resources/sample_data/list_Customer.dwl` (AAP 0.2.2).

Tier 2A verification points: the plan chose these values where the original platform, not the flow, fixed the bytes. When a captured fixture of the original shows different bytes, the fixture governs and the Java code is changed to match.

- The `Content-Type` of the page (`text/html;charset=UTF-8`) and the absent `Content-Type` of the empty filter-rejection reply.
- The `div` root of the no-match branch: the script's `div:` key binds the whole `unless ... otherwise` expression, so both branches are written under `div`.
- A customer without a first name or email: its cell is written `<td/>`.
- Result ordering: SuiteQL `ORDER BY lastname ASC` in place of the SuiteTalk search order, and the case-insensitive prefix match.
- `lastName` given twice (the last value is used) or without a value (rejected by the filter).
- The tab indentation of the row block (SC-4 `DwXmlWriter`), the bytes of the 404 and 500 defaults, and Undertow's 400 for a malformed percent escape in the query.

The capture of the three NetSuite-backed scenarios needs more than the original. The original called SuiteTalk SOAP, while this project calls REST, so a recording of the original's upstream calls cannot drive the replay. Those scenarios also need a REST recording taken against the same tenant state (`netsuite.base-url` pointed at a WireMock recording proxy). Until both exist they are skipped and counted unverified (`src/test/resources/fixtures/SCENARIOS.md`, notes under Scenarios).

## Build, run and test

Run from `java-conversions/get-customer-list-from-netsuite-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; offline, NetSuite is WireMock
mvn -B verify           # surefire tests, then the failsafe GetCustomersListFromNetsuiteIT against a live account (Tier 2B)
mvn spring-boot:run     # listener on 0.0.0.0:8081; supply the TODO keys as environment variables
java -jar target/get-customer-list-from-netsuite-java-1.0.0-SNAPSHOT.jar
```

- `mvn test` needs no licence, credential or network.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

### Live tests (Tier 2B)

`GetCustomersListFromNetsuiteIT` runs under failsafe (`mvn -B verify`) against a real NetSuite account.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `netsuite.account`, `netsuite.oauth.client-id`, `netsuite.oauth.certificate-id`, `netsuite.oauth.private-key-path` (absolute path of the PKCS#8 PEM key) and `nets.customer.subsidiary.internalId` (the subsidiary of the test customer).
3. Run `mvn -B verify`. The test starts the application on a random port with the Spring profile `it`, which loads `application-it.yml`.

`createdCustomerIsListed` brings its own data, where the original relied on the account's customers:

- It creates a customer with a unique last name (`Amuleit<millis>`) through the NetSuite REST record API.
- It waits up to 60 s until `GET /customers?lastName=<that name>` answers 200 with the heading `<h1>Netsuite Customer List</h1>` and the customer's row.
- It re-runs the original assertion: `GET /customers?lastName=a` contains `<h1>Netsuite Customer List</h1>`.
- It deletes the customer in `finally`. The create goes through `NetsuiteRestClient#createRecord` and SC-9, so it is retried exactly once after an HTTP 401: the cached token is evicted and the create is sent again, which is safe because NetSuite rejected the first request before processing it. A timeout or connectivity failure is never retried, nor is a 429 rate limit, and the test adds no retry of its own.

While `application-it.yml` or one of the five keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` condition, never failed, and NetSuite is not called. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `GetCustomersListFromNetsuiteTest` | the ported `GetCustomersListFromNetsuiteIT` (`get-customer-list-from-netsuite/src/test/java/org/mule/examples/GetCustomersListFromNetsuiteIT.java:33-34,64-66`) over HTTP against WireMock NetSuite: `GET /customers?lastName=a` returns 200 and contains `<h1>Netsuite Customer List</h1>`, with one token request and one SuiteQL request shaped as the clients send them |
| `GetCustomersListFromNetsuiteIT` | the live-account test of Live tests above (failsafe) |
| `controller/NetsuiteCustomerControllerTest` | on the wire through ST-1: the exact page bytes and `text/html;charset=UTF-8`; the same answer for `POST`, `PUT`, `PATCH`, `DELETE` and `OPTIONS`; the empty 200 without `Content-Type` for no `lastName`, a bare `?lastName` and a form-body `lastName`, with no NetSuite call; an empty `lastName` querying every customer; the `No customers found` row; a quote escaped inside the SuiteQL literal; `/unknown` → 404; 502 after one re-authentication, 429 with `Retry-After`, 503 on a timeout; another NetSuite failure (a 400, a 500, a 503 without a body) → SC-1's 500 `{ "message": "<code> <reason phrase>" }` naming no NetSuite URL, without retry |
| `service/NetsuiteCustomerServiceTest` | the filter guard; the SuiteQL text and its escaping; rows rendered into the template in query order; the no-match row; `tableBody` with Groovy `tokenize` semantics, and its failure when no line follows the first; upstream failures propagated unchanged |
| `mapper/CustomerHtmlMapperTest` | the exact DataWeave document for four customers, structurally equal to the original's `example.xml`; the single `No customers found` cell under `div`; `<td/>` for null or absent values; escaping; cell and row order |
| `mapper/DwXmlWriterTest`, `mapper/ParseTemplateRendererTest` | SC-4 XML bytes; SC-14 template substitution with the template kept byte-identical |
| `client/NetsuiteRestClientTest` | SC-16 against WireMock: SuiteQL paging and `Prefer: transient`, a SuiteQL or record-list paged read failing once a page reports `hasMore` at or past its `totalResults` or after 1000 pages that still report it, each after its exact request count, and read in full when its pages carry a consistent `totalResults`, literal escaping, record read, create and delete, the metadata catalog and the `customfield` source lookup with their caches, every failure mode with its call counts, an unclassified error answer to each request kind (the token request included) and an unreadable 2xx body raised with NetSuite's status, headers and body but never the request method or URL, the 10 s default timeouts |
| `client/NetsuiteTokenProviderTest` | the client credentials request and the PS256 client assertion with its claims; token caching, the 60 s expiry margin and `evict()`; concurrent callers on an empty cache sharing one token request and its token, or its failure (a delayed 401 or a read timeout) as the same unwrapped exception within about one request's time, the failure not cached, and `evict()` not waiting for a request in flight; PKCS#1 keys rejected with the `openssl` hint; missing settings named; the account-derived and explicit base URLs |
| `client/NetsuiteFailureClassifierTest`, `client/UpstreamCallExecutorTest`, `exception/UpstreamExceptionHandlerTest` | SC-9 and the NetSuite classification: one eviction and retry on 401, no retry on 429 or on any timeout or connectivity cause, NetSuite's error text as the message, 502/429/503 bodies, precedence over a catch-all advice |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes |
| `config/ApplicationLogLevelsTest` | the six `logging.level` categories bound from `application.yml`, each at its level; under root `TRACE`, the access-token, customer-e-mail and rendered-body sentinels of the three NetSuite calls and a rendered response stay out of the log, with no-caps and codec-categories-only controls proving the leak is seen; the real `DispatcherServlet` and Undertow listener on a random port under root `DEBUG` and `TRACE` with `spring.mvc.log-request-details`: no logger writes the `lastName` value for a served search, an upstream 500 or an undecodable query (Undertow's 400), while the no-caps control names the dispatcher, the SuiteQL encoder at `TRACE` and `io.undertow.request.io` as the writers |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire, with the flow mocked: a CORS pre-flight to `/customers` with `lastName` present, empty, absent or bare runs the flow once and gets exactly the plain `OPTIONS` reply (the page as `text/html;charset=UTF-8`, or the empty 200 without `Content-Type`), whatever method it asks for; a failing flow's 502, 429 with `Retry-After`, 503 and 500 likewise; never Spring's 403, and no `Access-Control-*` or `Vary` header; a pre-flight to `/customers/` or `/unknown` gets the no-listener 404 and runs no flow |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of `src/test/resources/fixtures/SCENARIOS.md`, which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-customers-lastname-match` | `http` | `deterministic` | `GET /customers?lastName=a`, empty body | `UNVERIFIED-uncaptured` |
| `post-customers-lastname-match` | `http` | `deterministic` | `POST /customers?lastName=a`, header `Content-Type: application/x-www-form-urlencoded`, empty body | `UNVERIFIED-uncaptured` |
| `get-customers-no-match` | `http` | `deterministic` | `GET /customers?lastName=zzqxNoMatch`, empty body | `UNVERIFIED-uncaptured` |
| `get-customers-missing-lastname` | `http` | `deterministic` | `GET /customers`, empty body | `UNVERIFIED-uncaptured` |
| `get-customers-lastname-without-value` | `http` | `deterministic` | `GET /customers?lastName`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |
| `get-customers-reauthentication-failed` | `http` | `deterministic` | `GET /customers?lastName=a`; SuiteQL answers 401 again after the re-authentication | `UNVERIFIED-uncaptured` |
| `get-customers-rate-limited` | `http` | `deterministic` | `GET /customers?lastName=a`; SuiteQL answers 429 with `Retry-After` | `UNVERIFIED-uncaptured` |
| `get-customers-upstream-timeout` | `http` | `deterministic` | `GET /customers?lastName=a`; no SuiteQL answer within `netsuite.read-timeout` | `UNVERIFIED-uncaptured` |
| `get-customers-upstream-unreachable` | `http` | `deterministic` | `GET /customers?lastName=a`; `netsuite.base-url` refuses the connection | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`.
- The last four rows are the approved connector failure modes (AAP 0.4.8). The original has no such branch, so they are never captured; their Tier 1 evidence is the client, classifier, executor, handler and controller tests above.
- The three NetSuite-backed rows (`get-customers-lastname-match`, `post-customers-lastname-match`, `get-customers-no-match`) omit `upstream` after capture, because the original's SuiteTalk SOAP calls cannot drive a REST replay and the REST token exchange carries a signed assertion and a live token that never replay byte for byte. They stay `UNVERIFIED-no-upstream`.
- Capture can raise the label at most to `PARITY: PARTIAL — Tier 2A fixtures: 3 verified, 7 unverified`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| get-customer-list-from-netsuite-java | get-customer-list-from-netsuite | Third-party connector | PASS | PASS | 98.7% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

This row equals the project's row in the root `SUMMARY.md`. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9); the test counts and coverage figures below were re-measured on 2026-10-28 with `mvn -B clean verify`, which runs the same surefire tests, JaCoCo check and failsafe run, on the tree whose SC-4 `DwXmlWriter` refuses element, prefix and attribute names that are not XML names:

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 901 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`GetCustomersListFromNetsuiteIT`, no `application-it.yml`).
- `mvn -B test`: `BUILD SUCCESS`; 901 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 313 of 317 lines (98.7%) of `service/NetsuiteCustomerService`, `mapper/CustomerHtmlMapper`, `mapper/DwXmlWriter` and `mapper/ParseTemplateRenderer`. Total project LINE coverage: 95.7% (1358 of 1419 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=10`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `GetCustomersListFromNetsuiteIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `GetCustomersListFromNetsuiteTest` carries the original committed assertion with its comparison semantics: `GET /customers?lastName=a` contains `<h1>Netsuite Customer List</h1>`.
- `controller/NetsuiteCustomerControllerTest`, `service/NetsuiteCustomerServiceTest` and `mapper/CustomerHtmlMapperTest` cover every branch on the wire or against the service and mapper.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the platform defaults, but not that its bytes equal what the original runtime sent. The NetSuite data in these tests is stubbed, not recorded from the original's account.
- Branches no original assertion covers, tested only by the new tests: the filter rejection (`get-customers-missing-lastname`), `lastName` without a value (`get-customers-lastname-without-value`), the no-match row (`get-customers-no-match`), `POST` and the other methods (`post-customers-lastname-match`), the unknown-path 404 (`get-unknown-path`), the unhandled 500, and the 502, 429 and 503 failure modes (`get-customers-reauthentication-failed`, `get-customers-rate-limited`, `get-customers-upstream-timeout`, `get-customers-upstream-unreachable`).
- Unverified scenarios, until captured: `get-customers-lastname-match`, `post-customers-lastname-match`, `get-customers-no-match`, `get-customers-missing-lastname`, `get-customers-lastname-without-value`, `get-unknown-path`, `get-customers-reauthentication-failed`, `get-customers-rate-limited`, `get-customers-upstream-timeout`, `get-customers-upstream-unreachable`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `get-customer-list-from-netsuite/`.
