# NetSuite Data Retrieval (Spring Boot)

`netsuite-data-retrieval-java` is the Spring Boot 3.2.12 / Java 17 conversion, served by Undertow, of the Mule EE 3.8 example [`netsuite-data-retrieval`](../../netsuite-data-retrieval/) ([original README](../../netsuite-data-retrieval/README.md)). Its category is Third-party connector (RAML-backed). It is an HTTP API, described by `netsuite-api.raml`, that searches a NetSuite account for customers, item supply plans and opportunities and returns them as JSON. The package root is `com.mulesoft.examples.netsuite_data_retrieval` and the main class is `NetsuiteDataRetrievalApplication`. The original folder `netsuite-data-retrieval/` stays unchanged beside this project as the reference.

## Original example

The original's use case: an APIkit router sends each HTTP request to the flow for its resource and method. That flow builds a NetSuite search criteria request from the HTTP query parameters, runs the search through the NetSuite connector, and returns the result as JSON. When no record matches, the response is an empty list. When the parameters are left blank, every record of the type is listed (`netsuite-data-retrieval/README.md`, Example Use Case and Set Up and Run the Example).

`netsuite-data-retrieval/src/main/app/netsuite-api.xml` declares:

- `http:listener-config` on host `0.0.0.0`, literal port `8081`, `connectionIdleTimeout="3000000"` (xml:12).
- `apikit:config` over `netsuite-api.raml` (RAML 0.8, `baseUri: http://localhost:8081/api`) with `consoleEnabled="true" consolePath="console"`, and three flow mappings (xml:13-17).
- `netsuite:config-login-authentication`, NetSuite connector 7.1.0, logging in to SuiteTalk SOAP with `${nets.email}`, `${nets.password}`, `${nets.account}`, `${nets.roleId}` and `${nets.applicationId}` (xml:18).
- The main flow `netsuite-api-main`: `http:listener` on `/api/*`, then `apikit:router`, with the exception strategy `netsuite-api-apiKitGlobalExceptionMapping` (xml:20-24).
- Three backend flows, `get:/customers:netsuite-api-config` (xml:25-53), `get:/items:netsuite-api-config` (xml:54-78) and `get:/oportunities:netsuite-api-config` (xml:79-106). Each runs a `%dw 1.0` criteria script, `netsuite:search`, the `%dw 1.0` script `payload map $` writing JSON, and an INFO logger.
- `apikit:mapping-exception-strategy` with five fixed JSON error bodies (xml:107-133).

`mule-app.properties` is empty. The MUnit suite `src/test/munit/netsuite-api-test-suite.xml` ran the three resources against a live account (section [Tests](#tests)). The original README's Anypoint Studio procedure is replaced by the Maven and Spring Boot commands of [Build, run and test](#build-run-and-test).

## Endpoints and triggers

The listener binds to `0.0.0.0` on port `8081`, a literal in the original listener and in `application.yml` (`server.port`). The base URL is `http://localhost:8081/api`, the RAML `baseUri`. The three RAML resources are compared one by one in the root [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) (section 4: 3 of 3 rows match).

| Method | Path | Query parameters (RAML) | Success | Errors | Controller method → service |
|---|---|---|---|---|---|
| GET | `/api/customers` | `name`, optional string: the prefix the company name starts with. Absent: every customer | `200`, `Content-Type: application/json`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, a JSON array of SuiteTalk-shaped Customer records (example `api/customers-response.json`); `[]` when none matches | 400, 405, 406; 502, 429, 503; 500 | `CustomersController.getCustomers` → `NetsuiteSearchService.getCustomers(name)` |
| GET | `/api/items` | `quantity`, integer, default `0`; `operator`, one of `LESS_THAN`, `GREATER_THAN`, `EQUAL_TO`, `LESS_THAN_OR_EQUAL_TO`, `GREATER_THAN_OR_EQUAL_TO`, default `GREATER_THAN` | `200`, `application/json`, the item supply plans with at least one order line whose quantity satisfies `<line quantity> <operator> <quantity>` (example `api/items-response.json`); `[]` when none matches | 400, 405, 406; 502, 429, 503; 500 | `ItemsController.getItems` → `NetsuiteSearchService.getItems(quantity, operator)` |
| GET | `/api/opportunities` | `title`, optional string: the prefix the opportunity title starts with. Absent: every opportunity | `200`, `application/json`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, a JSON array of Opportunity records (example `api/opportunities-response.json`); `[]` when none matches | 400, 405, 406; 502, 429, 503; 500 | `OpportunitiesController.getOpportunities` → `NetsuiteSearchService.getOpportunities(title)` |

- The original routes `/opportunities` to a flow whose name is misspelt, `get:/oportunities:netsuite-api-config` (xml:16,79). The RAML path `/opportunities` governs the endpoint; only the success log keeps the flow's spelling.
- A parameter that is sent empty is not the same as one that is absent:
  - `name=` and `title=` are passed on as an empty prefix (`LIKE '%'`), because the original's `!= null` test let an empty value through.
  - `quantity=` and `operator=` are answered with 400, because APIkit validated an empty value against the RAML type instead of applying the default.
- APIkit's RAML validation is reproduced. Each of these is answered with the APIkit 400:
  - a `quantity` that is not a decimal `int` (`abc`, `1.5`, `0x10`, or a number with a space in or around it);
  - an `operator` outside the enum, which is matched case-sensitively (`FOO`, `greater_than`);
  - any of the four parameters repeated (`?name=a&name=b`).

  The RAML declares no minimum, so `quantity=-3` is accepted. Query parameters the RAML does not declare are ignored.
- Each resource declares GET only. Every other method is answered with the APIkit 405, HEAD and OPTIONS included: each controller maps them explicitly, so Spring does not answer them itself. Under `/api`, a method token in lower or mixed case (`get`, `Get`) is routed as its upper-case form by `config/ApiKitMethodCaseFilter`, because the APIkit router lower-cased the method before routing.
- An `Accept` header that excludes `application/json` is answered with the APIkit 406, and so is one that gives it quality 0 under the APIkit router's negotiation, such as `application/json;q=0` (`config/ApiKitAcceptNegotiator`, a port of `RestContentTypeParser.bestMatch`). That negotiation runs in each RAML action after its URI and query parameter validation and before the flow, as APIkit did, so no search runs, and a query parameter the RAML rejects (`quantity=abc`, `operator=INVALID`, a repeated `name`) still gets the APIkit 400 with such a header. `*/*;q=0` is still accepted, because APIkit weighed a `*/*` range with the declared type's own quality. No resource declares a request body, so the 415 mapping is carried but none of the three resources can raise it.
- The response body is the service's JSON text, unchanged. `Content-Type` is the bare `application/json`, without a charset parameter, and the body is UTF-8 (Tier 2A verification point).
- The 200 replies of `/api/customers` and `/api/opportunities` also carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff` (section [Response header hardening](#response-header-hardening)).
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request to the same path (`config/PreflightDispatchConfig`), because the original listener applied no CORS policy. Whatever method it asks for, the three resources answer it with the APIkit 405, `/api/` and every path under `/api` that neither a resource nor the console serves with the APIkit 404, and the console paths and RAML includes with the console's own `OPTIONS` response (next section). Nothing checks the origin or adds an `Access-Control-*` or `Vary` header; the only such header a pre-flight can receive is the `Access-Control-Allow-Origin: *` the console sends with its page and files to every request. Under `/api` a lower or mixed-case `options` token is upper-cased by `config/ApiKitMethodCaseFilter` first, so with those headers it is a pre-flight and gets the same answer.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### APIkit console and RAML descriptor (SC-17)

The original enabled the console (`apikit:config consoleEnabled="true" consolePath="console"`, xml:13). The conversion carries it as a platform surface (AAP 0.4.5). It is not a RAML resource, so the 1:1 diff leaves it out.

| Request | Response |
|---|---|
| any method `/api/console` | `301`, `Location: http://<host>/api/console/` (the request's scheme and `Host` header, followed by its query string), empty body |
| any method `/api/console/` or `/api/console/index.html` | the APIkit 3.8.0 console page (`text/html`), its RAML source pointed at `./?raml` and `console-attributes-placeholder` replaced by `disable-raml-client-generator="" disable-theme-switcher=""` |
| `/api/console/<file>` | the console's scripts, styles, fonts and images from `src/main/resources/apikit-console/` (`text/css`, `application/x-javascript`, `image/png`, `image/gif`, `image/svg+xml`, otherwise `application/octet-stream`) |
| GET `/api/console?raml`, GET `/api/console/?raml`, and GET `/api/` with `Accept: application/raml+yaml` | `api/netsuite-api.raml` as `application/raml+yaml`, with `Access-Control-Allow-Origin: *` and its `baseUri` rewritten to the listener URL |
| GET `/api/<include>` and GET `/api/console/<include>` | each file the RAML `!include`s (`customers-response.json`, `items-response.json`, `opportunities-response.json`), served relative to the RAML |

A miss under the console path is answered with the APIkit 404, and so is every method but GET and HEAD on a RAML include (OPTIONS and a CORS pre-flight included). The Javadoc of `controller/ApiKitConsoleController` lists where these bytes are plan decisions rather than recorded original output. For example, the jar's `console/` tree is served rather than `console2/`, and the RAML is served as its raw file rather than re-dumped. Each is a Tier 2A verification point.

### Error responses

| Status | Content-Type | Body (exact bytes) | When | Handler |
|---|---|---|---|---|
| 404 | `application/json` | `{ "message": "Resource not found" }` | a path under `/api` that no resource names (`/api/foo`), or a miss under `/api/console/` | `exception/ApiKitExceptionHandler` (SC-3) |
| 405 | `application/json` | `{ "message": "Method not allowed" }` | any method but GET on the three resources, HEAD and OPTIONS included | `ApiKitExceptionHandler` |
| 415 | `application/json` | `{ "message": "Unsupported media type" }` | carried mapping; no resource consumes a body, so it is not raised | `ApiKitExceptionHandler` |
| 406 | `application/json` | `{ "message": "Not acceptable" }` | an `Accept` header that excludes `application/json`, or gives it quality 0 under APIkit's negotiation (`application/json;q=0`, raised by `config/ApiKitAcceptNegotiator`) | `ApiKitExceptionHandler` |
| 400 | `application/json` | `{ "message": "Bad request" }` | `quantity=abc`, `quantity=1.5`, `operator=FOO`, an empty `quantity` or `operator`, a repeated `name`, `title`, `quantity` or `operator` | `ApiKitExceptionHandler` |
| 502 | `application/json` | `{ "message": "<upstream message>", "upstream": "NetSuite" }` | NetSuite still rejected the authentication after the token was evicted and the call retried once | `exception/UpstreamExceptionHandler` (SC-9) |
| 429 | `application/json` | `{ "message": "<upstream message>", "upstream": "NetSuite" }`, with NetSuite's `Retry-After` header copied when sent | NetSuite throttled the call | `UpstreamExceptionHandler` |
| 503 | `application/json` | `{ "message": "<upstream message>", "upstream": "NetSuite" }` | a timeout or connectivity failure, never retried | `UpstreamExceptionHandler` |
| 500 | `application/json` | `{ "message": "<exception message>" }` | any other failure. Examples: a missing or invalid account, OAuth or base-URL setting (`netsuite.account is not configured`); a NetSuite error other than 401 and 429, whose message is its status code and reason phrase (for example `400 Bad Request`), never the request method or URL; a select custom field whose `typeId` cannot be resolved (`No select source (typeId) resolved for NetSuite custom field <scriptId>`) | `exception/GlobalExceptionHandler` (SC-1) |
| 404 | `text/plain` | `No listener for endpoint: <path>` | any path outside `/api` (`/`, `/other`, `/error`) | `ApiKitExceptionHandler`, writing SC-1's listener default |

- The first five rows are the original `apikit:mapping-exception-strategy` (xml:107-133), with its spacing. As in APIkit, the body never carries the exception message.
- The original README lists the same five codes for `netsuite-api-apiKitGlobalExceptionMapping`: 404 Resource not found, 405 Method not allowed, 415 Unsupported media type, 406 Not acceptable, 400 Bad request.
- The 502, 429 and 503 rows are the approved connector failure modes (AAP 0.4.8), the only responses the original did not have (next section). No error response carries the two headers of [Response header hardening](#response-header-hardening).
- The 500 body and the no-listener 404 are platform defaults rather than recorded original output (AAP 0.4.7). Each is a Tier 2A verification point: a captured fixture that shows different bytes governs.
- Advice precedence by `@Order`: `UpstreamExceptionHandler` first (`HIGHEST_PRECEDENCE`), then `ApiKitExceptionHandler` (`LOWEST_PRECEDENCE - 1`), then `GlobalExceptionHandler` last (`LOWEST_PRECEDENCE`).

### Connector failure modes (AAP 0.4.8)

Every NetSuite call, including the token request, runs through SC-9 `client/UpstreamCallExecutor` and is classified by SC-16 `client/NetsuiteFailureClassifier`.

| Failure | Behaviour | Response |
|---|---|---|
| Authentication or token expiry: HTTP 401 from the token endpoint, SuiteQL or the record API | the cached token is evicted (`NetsuiteTokenProvider.evict()`), a new token is requested and the call is retried once; a second 401 ends the attempt | `502 Bad Gateway` |
| Throttling: HTTP 429 | no retry | `429 Too Many Requests`, with NetSuite's `Retry-After` header |
| Timeout or connectivity failure anywhere in the cause chain (Netty `ReadTimeoutException`, `SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried | `503 Service Unavailable` |
| Anything else (a 400 for an invalid query, 403, 404, every 5xx, a missing setting) | rethrown unchanged | SC-1's 500; for a NetSuite error answer its message is the status code and reason phrase (for example `400 Bad Request`), never the request method or URL |

- Every body is `{ "message": "<upstream message>", "upstream": "NetSuite" }`, `Content-Type: application/json`, with APIkit's spacing.
- `<upstream message>` is NetSuite's own error text from the response body: `o:errorDetails[0].detail`, else `title`, else the OAuth `error_description`, else `error`.
  - Without such text, it is `NetSuite answered HTTP <status> <reason phrase>`.
  - For a request that got no response, it is `NetSuite did not respond in time` or `NetSuite is unreachable`.
  - It never carries the request URL, a header or the token.
- The connect and read timeouts are 10 s each (`netsuite.connect-timeout`, `netsuite.read-timeout`), the Mule default response timeout.
- The three searches only read. The only writes are the live test's customer create and delete. A create is never retried after a timeout or connectivity failure; only a 401, which NetSuite answers before processing the request, is retried.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

NetSuite authentication changes from the original's SuiteTalk SOAP login (email, password, account, role, application ID) to REST web services and SuiteQL with OAuth 2.0 machine-to-machine (M2M) authentication. This is the client credentials grant with a JWT client assertion:

- signed PS256, header `kid` = certificate ID;
- claims `iss` = client ID, `scope` = `rest_webservices`, `aud` = the token URL `/services/rest/auth/oauth2/v1/token`, `iat`, and an `exp` under NetSuite's 60-minute limit.

The token is cached until 60 s before the expiry NetSuite states. The account, OAuth and base-URL settings are read on the first NetSuite call, so start-up needs none of the `TODO` keys and the application starts with every one empty; that call then fails with SC-1's 500 naming the missing key. The timeouts and the time zone are bound and checked at start-up, as their rows below state.

Status values:

- `default`: a non-secret value the original fixed, which can be overridden.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6).
- `fixed`: a framework setting that reproduces the original listener. It is not a key of the original, and changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `server.port` | `Integer` | `8081` | none: `http:listener-config port="8081"` (xml:12) is a literal | default | listener port. The original reads no `${http.port}`, so this project has no `http.port` key; override with `--server.port=<port>` or `SERVER_PORT` |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (xml:12) |
| `server.undertow.no-request-timeout` | `Duration` | `3000000ms` | none | fixed | `connectionIdleTimeout="3000000"` (xml:12): an idle keep-alive connection is closed after 3000 s |
| `spring.application.name` | `String` | `netsuite-data-retrieval-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | no static resource handler, so an unserved path reaches the APIkit 404 under `/api` and the no-listener 404 elsewhere |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches the APIkit 405 instead of the servlet's `TRACE` echo |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 |
| `apikit.base-path` | `String` | `/api` | listener path `/api/*` (xml:21) | fixed | API base path of the controllers, the APIkit mappings, the console and the method-case filter |
| `apikit.raml` | `String` | `api/netsuite-api.raml` | `apikit:config raml="netsuite-api.raml"` (xml:13) | fixed | classpath RAML served by the console |
| `apikit.console-path` | `String` | `console` | `apikit:config consolePath="console"` (xml:13) | fixed | console path below the base path |
| `netsuite.account` | `String` | none, `${NETSUITE_ACCOUNT:}` | `nets.account` | TODO | the NetSuite account ID (Setup > Company > Company Information), for example `1234567` or `1234567_SB1` |
| `netsuite.oauth.client-id` | `String` | none, `${NETSUITE_OAUTH_CLIENT_ID:}` | none; with the other two `netsuite.oauth.*` keys it replaces `nets.email`, `nets.password`, `nets.roleId` and `nets.applicationId` | TODO | client ID of the M2M integration record; the assertion's `iss` claim |
| `netsuite.oauth.certificate-id` | `String` | none, `${NETSUITE_OAUTH_CERTIFICATE_ID:}` | as `netsuite.oauth.client-id` | TODO | ID NetSuite assigned to the uploaded M2M certificate; the assertion's `kid` header |
| `netsuite.oauth.private-key-path` | `String` | none, `${NETSUITE_OAUTH_PRIVATE_KEY_PATH:}` | as `netsuite.oauth.client-id` | TODO | file path or `classpath:` location of the certificate's private key, the secret of this integration, kept outside the repository. Format: unencrypted PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`), RSA of at least 2048 bits. A PKCS#1 key (`-----BEGIN RSA PRIVATE KEY-----`) is rejected; convert it with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem` |
| `netsuite.base-url` | `String` | none, `${NETSUITE_BASE_URL:}` | none | TODO (optional) | scheme and host of the NetSuite REST services. Empty → `https://<account>.suitetalk.api.netsuite.com`, with the account ID lower-cased and `_` replaced by `-` (`1234567_SB1` → `1234567-sb1`). A set value must be an absolute `http` or `https` URL without user info, query or fragment. Tests point it at WireMock |
| `netsuite.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every NetSuite call (AAP 0.4.8). Written in Spring Boot duration syntax: `10s`, `500ms`, ISO-8601 `PT10S`, or a bare number read as milliseconds; a value that is not a duration fails binding at start-up. Accepted range: at least 1 ms, at most 2147483647 ms, checked at start-up when the NetSuite clients are created. A zero, negative or sub-millisecond value stops start-up with the `IllegalStateException` `netsuite.connect-timeout must be a positive duration of at least 1 ms`, a larger one with `netsuite.connect-timeout must not exceed 2147483647 ms` |
| `netsuite.read-timeout` | `Duration` | `10s` | none | default | response timeout of every NetSuite call. Same syntax as `netsuite.connect-timeout`; a value that is not a duration fails binding at start-up. Accepted range: at least 1 ms, at most 9223372036854775807 ms, checked at start-up when the NetSuite clients are created. A zero, negative or sub-millisecond value stops start-up with the `IllegalStateException` `netsuite.read-timeout must be a positive duration of at least 1 ms`, a larger one with `netsuite.read-timeout must not exceed 9223372036854775807 ms` |
| `netsuite.time-zone` | `ZoneId` | `America/Los_Angeles` | none | default | account time zone of the record-shape dates and date-times. It gives the `-07:00` and `-08:00` offsets of the committed examples. An unknown zone ID fails binding at start-up |
| `netsuite.custom-field-type-ids` | `Map<String, String>` | `{}` | none | default | custom select field script ID → select source ID. A non-empty entry overrides the `customfield` lookup, for a tenant whose catalog exposes the source differently. A script ID containing `_` must be bracketed: `"[custentity_fmt_cur_sales_order]": "-30"` |
| `logging.level.org.springframework.core.codec`, `logging.level.org.springframework.http.codec`, `logging.level.org.springframework.web.HttpLogging`, `logging.level.org.springframework.web.servlet.mvc.method.annotation` | `LogLevel` | `INFO` | none | fixed | below `INFO` these categories write the access token, the SuiteQL and record bodies and the `/api` responses. They are held at `INFO` so no NetSuite data is logged, as the original logged only fixed success messages |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | below it the servlet writes the request line with its query (the `name` and `title` values) and, with `spring.mvc.log-request-details`, the parameters and headers. `WARN`, not `INFO`: its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | below `INFO` it writes the handler arguments: the `name` and `title` query values |
| `logging.level.io.undertow.request.io` | `LogLevel` | `INFO` | none | fixed | below `INFO` Undertow writes the request-parse failures, which quote a query value that does not decode |

- `config/NetsuiteProperties` (SC-16) binds the `netsuite.*` keys. A missing or invalid account, OAuth or base-URL value (an unreadable or PKCS#1 private key included) fails on first use with an `IllegalStateException` naming the key, and the message never repeats the configured value. The timeouts and the time zone are checked at start-up instead: a value that does not convert fails binding, and a timeout outside its range stops start-up when the NetSuite clients are created, with an `IllegalStateException` naming the key.
- Supply the values through the environment, never in a committed file:

  ```sh
  NETSUITE_ACCOUNT='<account ID>' NETSUITE_OAUTH_CLIENT_ID='<client ID>' NETSUITE_OAUTH_CERTIFICATE_ID='<certificate ID>' NETSUITE_OAUTH_PRIVATE_KEY_PATH='/path/to/key-pkcs8.pem' mvn spring-boot:run
  ```

- The Spring profile `test` (`src/test/resources/application-test.yml`) holds dummy account and OAuth identifiers that authenticate nowhere, a 2 s read timeout, and the original MUnit test data under their original names. How each unit test configures NetSuite:
  - The tests that exercise the real SC-16 client point `netsuite.base-url` at an in-process WireMock server and use an RSA private key generated for the run. `client/NetsuiteRestClientTest`, `client/NetsuiteTokenProviderTest`, `mapper/NetsuiteRecordShapeMapperTest` and `support/NetsuiteTestRecordsTest` set `NetsuiteProperties` directly, without a Spring context; `config/ApplicationLogLevelsTest` runs under the profile `test` and registers both through `@DynamicPropertySource`.
  - `NetsuiteApiTest` (`@MockBean NetsuiteRestClient`) and the tests that mock `NetsuiteSearchService` with `@MockBean` (`controller/CustomersControllerTest`, `ItemsControllerTest`, `OpportunitiesControllerTest`, `config/ApiKitAcceptNegotiationListenerTest`, `config/PreflightDispatchListenerTest`) run under the profile `test` and need neither.
  - The remaining unit tests, the other mapper and service tests among them, use no Spring profile and need neither.

### Original-to-new key map

The map is also in the root `DECISIONS.md` (section 6.3).

| Original key (`netsuite-api.xml:18`, MUnit `mule.test.properties`) | New key |
|---|---|
| `nets.account` | `netsuite.account` |
| `nets.email`, `nets.password`, `nets.roleId`, `nets.applicationId` | `netsuite.oauth.client-id`, `netsuite.oauth.certificate-id`, `netsuite.oauth.private-key-path`. The role is bound to the certificate mapping of the integration record, and the integration record's client ID takes the place of the application ID |
| `nets.customer.companyName`, `nets.customer.subsidiary.internalId`, `nets.opportunity.title`, `nets.item.quantity` (MUnit test data) | unchanged, in `src/test/resources/application-it.yml` |

The SuiteTalk SOAP login is replaced following Oracle's schedule: REST + OAuth 2.0 for new integrations from release 2026.1, no new SOAP integrations from 2027.1, SOAP disabled in 2028.2.

### NetSuite setup (requester)

The requester registers the credentials inside the existing NetSuite account. This project does not create them, and no new sandbox or tenant is procured (AAP 0.2.4).

1. Create an integration record with the OAuth 2.0 **Client Credentials (Machine to Machine) Grant** and the **REST Web Services** scope. Its client ID is `netsuite.oauth.client-id`.
2. Upload a certificate (RSA 3072 or 4096 bits) under **Setup > Integration > OAuth 2.0 Client Credentials (M2M) Setup**, and map it to the integration record, an entity and a role.
   - The certificate ID NetSuite shows is `netsuite.oauth.certificate-id`.
   - The certificate's private key, in PKCS#8 PEM form, is the file `netsuite.oauth.private-key-path` names.
3. Give the role:
   - **REST Web Services** and **Log in using OAuth 2.0 Access Tokens**;
   - view access to customers, opportunities, item supply plans and the custom field definitions that the `customfield` lookup reads;
   - **Customers** (Full), for the live test's create and delete.
4. Item supply plans exist only when their feature is enabled in the account. Without it, NetSuite rejects the item supply plan list and `/api/items` is answered with SC-1's 500.

## Mule-to-Java mapping

| Mule construct (`netsuite-data-retrieval`) | Java construct (`com.mulesoft.examples.netsuite_data_retrieval`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `8081`, `connectionIdleTimeout` (xml:12) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address`, `server.port` and `server.undertow.no-request-timeout` |
| `http:listener path="/api/*"` + `apikit:router` + `apikit:config` with three flow mappings (xml:13-17,20-22) | `controller/CustomersController`, `ItemsController`, `OpportunitiesController`, one per RAML resource, under `@RequestMapping("${apikit.base-path:/api}")`; RAML query constraints as `@RequestParam` defaults, `@Pattern` and the controllers' raw-value checks; `config/ApiKitMethodCaseFilter` for method tokens in lower or mixed case; `config/ApiKitAcceptNegotiator` for the router's output negotiation; `config/PreflightDispatchConfig` for CORS pre-flights, dispatched as plain `OPTIONS` requests |
| `netsuite:config-login-authentication` (xml:18) | SC-16 OAuth 2.0 M2M: `client/NetsuiteTokenProvider` with `config/NetsuiteProperties`; every call through `client/UpstreamCallExecutor` and `client/NetsuiteFailureClassifier` |
| Criteria scripts `%dw 1.0` (xml:27, 56, 81) | `service/NetsuiteSearchService#customerCriteria`, `#itemSupplyPlanCriteria`, `#opportunityCriteria` |
| `netsuite:search` `CUSTOMER`, `ITEM_SUPPLY_PLAN_BASIC`, `OPPORTUNITY` (xml:41, 68, 97) | `NetsuiteSearchService#getCustomers`, `#getItems`, `#getOpportunities` over `client/NetsuiteRestClient` (`suiteQl`, `listRecordIds`, `getRecord`, `metadataCatalog`, `customFieldSourceIds`) |
| `payload map $` scripts `%dw 1.0` (xml:44, 70, 99) | `mapper/CustomerSearchMapper`, `ItemSupplyPlanMapper`, `OpportunitySearchMapper`, through `mapper/NetsuiteRecordShapeMapper` and SC-4 `mapper/DwJsonWriter` |
| `logger` INFO (xml:50, 76, 105) | SLF4J INFO in `NetsuiteSearchService`, the same three texts |
| `apikit:mapping-exception-strategy` (xml:107-133) | SC-3 `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` |
| Console and RAML descriptor (`consoleEnabled="true"`) | SC-17 `controller/ApiKitConsoleController` and `src/main/resources/apikit-console/**` |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| (approved addition, no original construct) | SC-9 `exception/UpstreamExceptionHandler` and the three upstream exceptions |
| `src/main/api/netsuite-api.raml`, `customers-response.json`, `items-response.json`, `opportunities-response.json` | copied byte for byte to `src/main/resources/api/` |
| `mule-deploy.properties` (loads `netsuite-api.xml`) | `NetsuiteDataRetrievalApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| MUnit `netsuite-api-test-suite.xml` | `NetsuiteApiTest` (surefire, mocked `NetsuiteRestClient`) and `NetsuiteApiIT` (failsafe, live account), with `support/NetsuiteTestRecords` for the `createCustomerInNetsuite` and `deleteCustomerInNetsuite` sub-flows |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

### Shared components

Each component is copied with only its `package` line changed (and, for SC-3 and SC-17, the package root of one import), and its tests are copied with it. The register is in the root `DECISIONS.md` (section 4).

| ID | Classes in this project | Reference copy |
|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` | `rest-api-with-apikit-java` |
| SC-3 | `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` | `rest-api-with-apikit-java` |
| SC-4 | `mapper/DwJsonWriter` | `processing-orders-with-dataweave-and-APIkit-java` |
| SC-9 | `client/UpstreamCallExecutor`, `UpstreamCall`, `UpstreamAction`, `UpstreamSystem`, `UpstreamFailureClassifier`, `UpstreamFailureKind`; `exception/UpstreamAuthenticationException`, `UpstreamRateLimitException`, `UpstreamUnavailableException`, `UpstreamExceptionHandler` | `salesforce-data-retrieval-java` |
| SC-16 | `client/NetsuiteTokenProvider`, `client/NetsuiteRestClient`, `client/NetsuiteFailureClassifier`, `config/NetsuiteProperties` | `get-customer-list-from-netsuite-java` |
| SC-17 | `controller/ApiKitConsoleController`, `src/main/resources/apikit-console/**` | `rest-api-with-apikit-java` |
| ST-1 | `src/test/java/.../support/HttpTestClient` | `rest-api-with-apikit-java` |

`config/ApiKitMethodCaseFilter` and `config/ApiKitAcceptNegotiator` are carried by the RAML-backed projects in the same way, and so is `config/PreflightDispatchConfig` with its `config/PreflightDispatchConfigTest`, both copied from `rest-api-with-apikit-java`. This project uses the record API, metadata catalog and `customfield` lookup of SC-16, which `get-customer-list-from-netsuite-java` carries but does not call. ST-2 does not apply, because this is a RAML-backed project.

### How it works

The original has four flows and one exception strategy. The main flow `netsuite-api-main` and its `apikit:router` become the three controllers, one per RAML resource. Each controller only binds and validates the query parameters, calls `service/NetsuiteSearchService`, and returns the service's JSON text with 200; the customers and opportunities replies add the two headers of [Response header hardening](#response-header-hardening). The service runs each backend flow's processors in their original order: the criteria script, the NetSuite search, `payload map $` as JSON, the success log.

The SuiteTalk SOAP search of the NetSuite connector is replaced by the NetSuite REST web services and SuiteQL, called through SC-16 `client/NetsuiteRestClient`. Each search reads its filter back from the criteria map its criteria method built, so the three `%dw 1.0` criteria scripts keep one owner each.

#### `get:/customers:netsuite-api-config` → `CustomersController.getCustomers`

1. **Criteria** (xml:27-39): `NetsuiteSearchService.customerCriteria(name)` builds `{basic: {companyName: {operator: "STARTS_WITH", searchValue: name}}}`. The `companyName` entry is present only when `name != null`, as in the script's `when` guard.
2. **Search** (xml:41, `netsuite:search searchRecord="CUSTOMER"`): SuiteQL selects the matching IDs, ordered by `id`. Then each customer is read with `GET /services/rest/record/v1/customer/{id}?expandSubResources=true`, in that order.

   ```sql
   SELECT id FROM customer WHERE companyname LIKE '<name>%' ORDER BY id   -- name present
   SELECT id FROM customer ORDER BY id                                   -- name absent
   ```

3. **`payload map $`** (xml:44-47): `mapper/CustomerSearchMapper` writes each record in the SuiteTalk Customer shape through `mapper/NetsuiteRecordShapeMapper` (below) and SC-4 `mapper/DwJsonWriter`.
4. **Log** (xml:50): INFO `Get customers completes successfully.`

#### `get:/items:netsuite-api-config` → `ItemsController.getItems`

1. **Criteria** (xml:56-66): `NetsuiteSearchService.itemSupplyPlanCriteria(quantity, operator)` builds `{quantity: {operator: operator, searchValue: quantity}}`. The quantity is already the number that `as :number` produced.
2. **Search** (xml:68, `netsuite:search searchRecord="ITEM_SUPPLY_PLAN_BASIC"`): `GET /services/rest/record/v1/itemsupplyplan` lists every plan, page by page. Each plan is read with `expandSubResources=true`.
   - A plan is kept when any line of its `order` sublist has a quantity that satisfies the comparison. Quantities are compared as decimals, so `5` equals `5.0`.
   - Quantity exists only on those lines, and the REST `q` filter applies to body fields only, so the comparison is made here.
   - SuiteQL is not used for item supply plans, because whether SuiteQL exposes them depends on the account.

   | `operator` | Comparison |
   |---|---|
   | `LESS_THAN` | line quantity `<` `quantity` |
   | `GREATER_THAN` (default) | line quantity `>` `quantity` |
   | `EQUAL_TO` | line quantity `==` `quantity` |
   | `LESS_THAN_OR_EQUAL_TO` | line quantity `<=` `quantity` |
   | `GREATER_THAN_OR_EQUAL_TO` | line quantity `>=` `quantity` |

3. **`payload map $`** (xml:70-73): `mapper/ItemSupplyPlanMapper`, through `NetsuiteRecordShapeMapper` and `DwJsonWriter`.
4. **Log** (xml:76): INFO `Get items completes successfully.`

#### `get:/oportunities:netsuite-api-config` → `OpportunitiesController.getOpportunities`

1. **Criteria** (xml:81-95): `NetsuiteSearchService.opportunityCriteria(title)` builds `{basic: {title: {operator: "STARTS_WITH", searchValue: title}}}`, the `title` entry present only when `title != null`.
2. **Search** (xml:97, `netsuite:search searchRecord="OPPORTUNITY"`): SuiteQL selects the matching IDs, ordered by `id`. Then each opportunity is read with `GET /services/rest/record/v1/opportunity/{id}?expandSubResources=true`.

   ```sql
   SELECT id FROM transaction WHERE type = 'Opprtnty' AND title LIKE '<title>%' ORDER BY id   -- title present
   SELECT id FROM transaction WHERE type = 'Opprtnty' ORDER BY id                            -- title absent
   ```

3. **`payload map $`** (xml:99-103): `mapper/OpportunitySearchMapper`, through `NetsuiteRecordShapeMapper` and `DwJsonWriter`.
4. **Log** (xml:105): INFO `Get oportunities completes successfully.`, with the original's spelling.

#### Record shape (`mapper/NetsuiteRecordShapeMapper`)

The connector returned SuiteTalk record beans, which DataWeave wrote as JSON. A REST record has a different shape, so `NetsuiteRecordShapeMapper` rebuilds the SuiteTalk record. The parity targets are the committed RAML examples `api/customers-response.json`, `api/items-response.json` and `api/opportunities-response.json`.

- **Field names.** REST `id` becomes `internalId`. For customers and opportunities, each other root field is written only when the record type's metadata catalog declares it. The catalog is read with `GET /services/rest/record/v1/metadata-catalog/<type>`, `Accept: application/schema+json`, once per type per application run.
- **Values.**
  - Numbers keep their integer or decimal form (`100.0` → `100`, `-32.3`).
  - Booleans stay native.
  - Unset fields produce no key.
  - Root `links` and `refName` are dropped.
- **References.** `{id, refName}` becomes `{ "externalId": null, "type": null, "internalId": <id>, "name": <refName> }`, in that key order.
- **Dates.** Dates and date-times are written in ISO-8601 with the account offset of `netsuite.time-zone`, for example `2013-07-22T00:00:00-07:00` and `2015-01-22T20:10:20-08:00`.
- **Enumerations.**
  - Enumerations are written as SuiteTalk constant names (`stage` `CUSTOMER`, `creditHoldOverride` `AUTO`).
  - Address `country` codes are translated through `src/main/resources/netsuite/country-enum.properties` (`US` → `UNITED_STATES`). A code the table lacks fails the request with SC-1's 500 naming the code.
  - The opportunity `status` keeps its text (`Issued Quote`).
- **Opportunity addresses.** `billingAddress` and `shippingAddress` become the fixed 15-key SuiteTalk `Address` object, with nulls kept.
- **Item supply plans.** Only `internalId` and the references `item`, `location`, `subsidiary` and `units` are kept. `units` is written only when the plan sets it. The `order` lines and other sublists are omitted, as the SuiteTalk search returned body fields only.
- **Custom fields.** Root `custentity*` and `custbody*` fields become a one-element `customFieldList.customField` list. The element is an object keyed `<Type>CustomFieldRef__<scriptId>`, its type taken from the metadata catalog (`Boolean`, `Long`, `Double`, `String`, `Date`, `Select`, `MultiSelect`).
  - Scalars are written as text (`"false"`, `"50"`).
  - Selects are written as `{ "internalId", "name", "externalId": null, "typeId": <source id> }`.
- **Select `typeId`.** `typeId` is the internal ID of the select field's source list or record type, which a REST select value does not carry. `NetsuiteRestClient.customFieldSourceIds` resolves it from the first of these that has it:
  1. a non-blank `netsuite.custom-field-type-ids` entry;
  2. an earlier lookup of this application run;
  3. one SuiteQL query `SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE UPPER(scriptid) IN (...)`, taking the raw column value.

  A select whose source neither the override nor the lookup supplies fails the request with SC-1's 500 naming the script ID. `typeId` is never written null or guessed.
- **Key order.** Every object other than a reference is a default-constructed `java.util.HashMap`. Filled in the committed key order, it iterates in the key order of all three examples.
- **Bytes.** SC-4 `DwJsonWriter` writes the DataWeave 1.0 JSON: two-space indentation, `"key": value`, one array element per line.
- **Plan defaults.** No committed example shows a `Double`, `String`, `Date` or `MultiSelect` custom field, so their rendering is a plan default awaiting Tier 2B evidence (`NetsuiteRecordShapeMapper` Javadoc).

#### `netsuite-api-apiKitGlobalExceptionMapping` → `ApiKitExceptionHandler`

The APIkit mapping exception strategy becomes SC-3 `exception/ApiKitExceptionHandler`, which writes the five literal bodies of [Error responses](#error-responses). SC-3 `config/JsonSchemaValidator` is carried with it. The NetSuite RAML declares no body schema, so it validates no request in this project.

### Query hardening (AAP 0.4.10)

The original placed `name` and `title` into SuiteTalk search fields. The conversion places them into SuiteQL string literals, so `NetsuiteRestClient.suiteQlLiteral` escapes each value before it is concatenated into the `LIKE` literal:

- Each `'` is doubled (`O'Brien` → `O''Brien`), so no value can close the literal. A backslash is an ordinary character in a SuiteQL literal and is left unchanged. `%` and `_` keep their `LIKE` meaning, as in a prefix search.
- A hostile `name` or `title` therefore cannot alter the query, and every legitimate prefix selects the records the original's `STARTS_WITH` criteria selected. This is the only intentional difference for hostile input, recorded in the root `DECISIONS.md` (section 8).
- `quantity` is bound as an `int` and `operator` is checked against the enum before the service runs, so neither reaches a query as text.

### Response header hardening

The 200 replies of `CustomersController.getCustomers` and `OpportunitiesController.getOpportunities` carry customer and opportunity records. Each adds `Cache-Control: no-store`, so that neither the client nor an intermediary keeps a copy, and `X-Content-Type-Options: nosniff`, so that no browser reads the JSON as another content type.

- Status, reason phrase, `Content-Type` and body bytes are unchanged. `/api/items`, the console and RAML descriptor, and every error response keep their headers.
- No `Content-Security-Policy` (the APIkit console assets keep their headers), no `Strict-Transport-Security` (the listener is plain HTTP, as in the original) and no authentication is added.
- The original declared neither header, and the headers its listener generated are unverified (verification point below). The decision is recorded in the root `DECISIONS.md` (section 8).

## Behaviours stubbed or externalized

- **Replaced.** The SuiteTalk SOAP connector (`netsuite:search` with an email/password/role login) becomes NetSuite REST web services and SuiteQL with OAuth 2.0 M2M (SC-16). Oracle accepts no new SOAP integrations from release 2027.1 and disables SOAP in 2028.2 (AAP 0.3.3).
- **Acquisition.**
  - Customers and opportunities are found by a SuiteQL ID query, then read one record at a time.
  - Item supply plans are all listed and read one at a time, then filtered on their `order` lines.
  - Every SuiteQL and list page (1000 rows) is read until NetSuite reports `hasMore` `false`, and each page and each record read is its own NetSuite call.
  - Paging is bounded: a page that still reports `hasMore` `true` although the next offset reaches its `totalResults`, or a 1000th page that still reports it (1,000,000 rows, ten times SuiteQL's 100,000-result ceiling), fails the request with SC-1's 500 `{ "message": "Unexpected NetSuite response: ..." }`, as a malformed page does, instead of paging on.
- **Escaped.** SuiteQL literals are escaped (section [Query hardening](#query-hardening-aap-0410)); this is the only hostile-input difference, recorded in the root `DECISIONS.md`.
- **Account-dependent.** Item supply plans need their feature enabled in the account. Select `typeId`s come from the `customfield` lookup (`fieldvaluetyperecord`) or from the `netsuite.custom-field-type-ids` override. An unresolved one fails the request with SC-1's 500 rather than being written null.
- **Externalized.** The NetSuite account and the OAuth credentials are `TODO` keys with environment-variable fallbacks. The base URL, the timeouts, the account time zone and the select-source overrides, which the original connector fixed or did not need, are configurable keys.
- **Stubbed in tests only.** The unit tier replaces NetSuite with an in-process WireMock server behind `netsuite.base-url`, where a test runs the real SC-16 client with a private key generated for the run, or with a Mockito mock of `NetsuiteRestClient` or `NetsuiteSearchService`, which needs neither (section [Configuration keys](#configuration-keys)). At run time nothing is stubbed and no behaviour was dropped.
- **Carried.** The RAML and its three example files, byte for byte, as the parity targets of the record shape. `NOTICE` credits the APIkit console files.
- **Not carried** (AAP 0.2.2): Studio metadata (`catalog/**`, `mule-project.xml`), `src/main/resources/log4j2.xml` and `src/test/resources/log4j2-test.xml`.
- **Logging.** Nothing logs NetSuite data. The flows log only their three fixed success texts, and `application.yml` holds the Spring codec, response-body and handler-argument loggers and Undertow's request-parse logger at `INFO` and the `DispatcherServlet` at `WARN`, so a root `DEBUG` or `TRACE` level cannot write the access token, record data or the `name` and `title` searched for.

Tier 2A and Tier 2B verification points. Here the plan, not a recording of the original, fixed the bytes or the selection. A captured fixture or a sandbox run that shows otherwise governs, and the Java code is changed to match.

- The `Content-Type` of the 200 responses (`application/json`, no charset parameter), the 500 body and the no-listener 404 text.
- The `Cache-Control: no-store` and `X-Content-Type-Options: nosniff` of the customers and opportunities 200 replies, which the original did not declare. Where a capture shows a header contract that one of them breaks, the captured contract is kept for that reply and the conflict is recorded in `DECISIONS.md` (section 8).
- **Result order.** Ascending internal ID for customers and opportunities, and the list order for item supply plans, where the original returned the SuiteTalk search order.
- **Prefix match.** `companyname LIKE '<name>%'` and `title LIKE '<title>%'` compare as SuiteQL `LIKE` compares. Whether that matches the case handling of the SuiteTalk `STARTS_WITH` search the original ran is a Tier 2B comparison.
- **Empty prefix.** `name=` and `title=` select through `LIKE '%'`, which excludes records whose company name or title is empty.
- **Record shape.**
  - The renderings no committed example shows: `Double`, `String`, `Date` and `MultiSelect` custom fields, and the enumeration fallbacks named in the `NetsuiteRecordShapeMapper` Javadoc.
  - The two `addrText` values: the committed `api/opportunities-response.json` holds raw line feeds inside them, which is not strict JSON, while `DwJsonWriter` escapes a line feed as `\n`. `mapper/OpportunitySearchMapperTest` therefore compares that example as a JSON tree, key order included, after normalizing the whitespace of both `addrText` values.
- The console bytes listed in the `ApiKitConsoleController` Javadoc, and a CORS pre-flight answered as the plain `OPTIONS` request to the same path.

## Build, run and test

Prerequisites: JDK 17, Maven 3.9.x and access to Maven Central. No Mule runtime, licence or NetSuite credential is needed for the build and the unit tests. Run from `java-conversions/netsuite-data-retrieval-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; offline, NetSuite is WireMock or a Mockito mock
mvn -B verify           # surefire tests, then the failsafe NetsuiteApiIT against a live account (Tier 2B)
mvn spring-boot:run     # listener on 0.0.0.0:8081; supply the TODO keys as environment variables
java -jar target/netsuite-data-retrieval-java-1.0.0-SNAPSHOT.jar --server.port=9090
```

- `mvn test` needs no licence, credential or network.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, and JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`).
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

The original README's requests, which need the NetSuite keys of [Configuration keys](#configuration-keys):

```sh
curl -i "http://localhost:8081/api/customers?name=Ramsey"
curl -i "http://localhost:8081/api/items?quantity=5&operator=LESS_THAN"
curl -i "http://localhost:8081/api/opportunities?title=Short"
curl -i -H 'Accept: application/raml+yaml' http://localhost:8081/api/
```

Open the console at `http://localhost:8081/api/console/`.

These exchanges were measured on this build, run without credentials, and are shown here on the default port:

```console
$ curl -i http://localhost:8081/api/customers
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 51
Date: <current date>

{ "message": "netsuite.account is not configured" }
$ curl -i "http://localhost:8081/api/items?quantity=abc"
HTTP/1.1 400 Bad Request
Connection: keep-alive
Content-Type: application/json
Content-Length: 28
Date: <current date>

{ "message": "Bad request" }
$ curl -i -X POST http://localhost:8081/api/customers
HTTP/1.1 405 Method Not Allowed
Connection: keep-alive
Content-Type: application/json
Content-Length: 35
Date: <current date>

{ "message": "Method not allowed" }
$ curl -i http://localhost:8081/api/foo
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: application/json
Content-Length: 35
Date: <current date>

{ "message": "Resource not found" }
$ curl -i http://localhost:8081/other
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 32
Date: <current date>

No listener for endpoint: /other
```

With dummy OAuth values, a throwaway key and `--netsuite.base-url=http://127.0.0.1:1` (a closed port), `GET /api/items?quantity=5&operator=LESS_THAN` is answered with `503 Service Unavailable` and `{ "message": "NetSuite is unreachable", "upstream": "NetSuite" }`.

## Tests

| Test class | What it asserts |
|---|---|
| `NetsuiteApiTest` | the ported MUnit suite (`netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml:26-49`): its three tests under the MUnit names, with the same requests, assertions and comparison semantics (status 200, payload not null, and for customers the substring `"companyName": "<name>"`). The whole application runs on a random port behind ST-1, with SC-16 `NetsuiteRestClient` a `@MockBean` answering from the REST fixtures in `src/test/resources/netsuite/`. The mocked account holds the committed customer only while the test has created it, as the original depended on `createCustomerInNetsuite` |
| `NetsuiteApiIT` | the live-account run of the same suite (failsafe; [Live tests (Tier 2B)](#live-tests-tier-2b)) |
| `support/NetsuiteTestRecordsTest` | the bracket `NetsuiteApiIT` runs, against WireMock behind the real SC-16 client: a create whose answer is dropped (connection reset, empty response) or carries no record ID is sent once, never again, and the customer the `externalid` SuiteQL lookup finds is deleted; a create answered with an ID is deleted by that ID without the lookup, whether the assertion passes or fails; a cleanup failure fails a passing test and is suppressed under a failing one; the log lines carry record IDs and the external ID only |
| `controller/CustomersControllerTest`, `controller/ItemsControllerTest`, `controller/OpportunitiesControllerTest` | on the wire through ST-1, with `NetsuiteSearchService` mocked, so status code, reason phrase, `Content-Type` and body bytes are asserted; the three share one application context. For customers and opportunities, every 200 carries exactly `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`, and every APIkit, failure-mode and 500 reply carries neither. Each parameter reaches the service as bound (`name` and `title` unchanged, `null` when absent and `""` when empty; `quantity` and `operator` with the RAML defaults `0` and `GREATER_THAN`), and the service's text is returned byte for byte with 200. The APIkit 400 (a non-integer `quantity`, an `operator` outside the enum, an empty `quantity` or `operator`, any repeated parameter), 405, 406 and 404 are answered without calling the service. Each failure mode gets 502, 429 or 503, and any other failure gets SC-1's 500. `CustomersControllerTest` also asserts the no-listener 404 outside `/api` |
| `controller/ApiKitConsoleControllerTest` | SC-17: the redirect, the console page and files, the RAML descriptor with its rewritten `baseUri`, the includes, and path-traversal probes answered with the 404; at `<api base>/<include>`, each RAML include served to GET with its bytes and to HEAD with an empty 200, and every other method (OPTIONS, POST, PUT, DELETE, PATCH, PROPFIND and TRACE are sent) and CORS pre-flights asking for GET or POST answered with the APIkit 404 and no `Allow`, `Access-Control-*` or `Vary` header |
| `service/NetsuiteSearchServiceTest` | the three criteria scripts; the SuiteQL text and its escaping (`O'Brien`, an injection attempt, an empty prefix); record acquisition in ID order; item supply plans kept by order-line quantity for each operator; the committed responses rebuilt from the REST fixtures; the three success log lines; client failures propagated unchanged |
| `mapper/CustomerSearchMapperTest`, `ItemSupplyPlanMapperTest`, `OpportunitySearchMapperTest` | each `payload map $` owner against its committed RAML example: customers and items byte for byte; opportunities as a JSON tree with key order, because of the raw line feeds in the committed `addrText` values |
| `mapper/NetsuiteRecordShapeMapperTest` | the decisive record-shape cases over the REST fixtures: the four select `typeId`s `16`, `17`, `-30` and `15` of the committed `customFieldList`, through the real SC-16 `customfield` lookup against WireMock; an override entry winning over the lookup; an unresolved script ID failing with no `typeId` written; an item supply plan with and without `units` |
| `mapper/NetsuiteRecordShapeMapperFieldRulesTest` | the field rules on in-memory records: names from the metadata catalog, numbers, booleans, references, dates in the account offset, enumerations and country constants, the 15-key addresses, custom field keys and types, `HashMap` key order |
| `mapper/DwJsonWriterTest` | SC-4 JSON bytes |
| `client/NetsuiteRestClientTest` | SC-16 against WireMock: SuiteQL paging and `Prefer: transient`, a SuiteQL or record-list paged read failing once a page reports `hasMore` at or past its `totalResults` or after 1000 pages that still report it, each after its exact request count, and read in full when its pages carry a consistent `totalResults`, literal escaping, record read, list, create and delete, the metadata catalog and the `customfield` source lookup with their caches, every failure mode with its call counts, an unclassified error answer to each request kind (the token request included) and an unreadable 2xx body raised with NetSuite's status, headers and body but never the request method or URL, the 10 s default timeouts |
| `client/NetsuiteTokenProviderTest` | the client credentials request and the PS256 client assertion with its claims; token caching, the 60 s expiry margin and `evict()`; concurrent callers on an empty cache sharing one token request and its token, or its failure (a delayed 401 or a read timeout) as the same unwrapped exception within about one request's time, the failure not cached, and `evict()` not waiting for a request in flight; PKCS#1 keys rejected; missing settings named; the account-derived and explicit base URLs; unloadable keys failing with a key-only message and one ERROR line per call that holds neither the configured value nor any cause text, the key text itself configured as the path included |
| `client/NetsuiteFailureClassifierTest`, `client/UpstreamCallExecutorTest`, `exception/UpstreamExceptionHandlerTest` | SC-9 and the NetSuite classification: one eviction and retry on 401, no retry on 429 or on any timeout or connectivity cause, NetSuite's error text as the message, the 502/429/503 bodies, precedence over the other advices |
| `exception/ApiKitExceptionHandlerTest`, `exception/GlobalExceptionHandlerTest` | SC-3 mappings and precedence; SC-1's 404, 405 and 500 status lines, `Content-Type` headers, body bytes and log safety |
| `config/JsonSchemaValidatorTest` | SC-3 draft-03 body validation |
| `config/ApiKitMethodCaseFilterTest`, `config/ApiKitMethodCaseListenerTest` | lower and mixed-case method tokens under `/api` answered as their upper-case form, and requests outside `/api` left alone |
| `config/ApiKitAcceptNegotiatorTest`, `config/ApiKitAcceptNegotiationListenerTest` | APIkit's `Accept` negotiation: the type `RestContentTypeParser.bestMatch` chose for each header, quirks included, and `negotiate` on the handler method Spring selected (method or class `produces`, negated types skipped, the rejection carrying the declared types, a request without a handler method refused); with `NetsuiteSearchService` mocked, the APIkit 406 on the three GETs for every form that gives JSON quality 0 without calling the service; the APIkit 400, exactly as without `Accept` and without calling the service, for those forms with a query parameter the RAML rejects (`quantity=abc`, `quantity=`, `quantity=0x10`, `quantity=1&quantity=2`, `operator=INVALID`, `operator=`, `name=a&name=b`, `title=a&title=b`); the service's JSON with 200 after one search for the forms APIkit accepted (`*/*;q=0`, `application/json;q=0.5`); and unchanged answers with `application/json;q=0`, and no search, for undeclared methods, unserved paths and the console |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | CORS pre-flights answered as plain `OPTIONS` requests, never running a search handler. The handler mapping selects the handler a plain `OPTIONS` request to the path selects and adds no CORS processing, while a handler with a CORS configuration is still answered by Spring. On the wire, a pre-flight asking for any method to each resource (with and without a query), to `/api/` (with and without `Accept: application/raml+yaml`), to unknown paths under `/api`, to the console paths, to the RAML includes and outside `/api`, and one with a lower or mixed-case `options` token, gets the status line, headers and body bytes of the plain `OPTIONS` request to the same path, with no `Vary` header and, outside the console, no `Access-Control-*` header; `NetsuiteSearchService` is never called, while a cross-origin `GET` still reaches it |
| `config/ApplicationLogLevelsTest` | the seven `logging.level` categories bound from `application.yml`, each at its level; under root `TRACE`, the access-token, customer-e-mail and rendered-body sentinels of the three NetSuite calls and a rendered response stay out of the log, with no-caps and codec-categories-only controls proving the leak is seen; the real `DispatcherServlet`, handler invocation and Undertow listener on a random port under root `DEBUG` and `TRACE` with `spring.mvc.log-request-details`: no logger writes the value searched for by `/api/customers?name=` or `/api/opportunities?title=` on success, an upstream 500 or an undecodable query (Undertow's 400), while the no-caps control names the dispatcher, the SuiteQL encoder, `HandlerMethod` at `TRACE` and `io.undertow.request.io` as the writers; the three `… completes successfully.` `INFO` lines are still written, once each, at root `DEBUG` and `TRACE`; an upstream 500 to `/api/customers` or `/api/opportunities` is answered `{ "message": "500 Internal Server Error" }`, naming no NetSuite URL |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes, because `java.net.http.HttpClient` hides the reason phrase. `support/NetsuiteTestRecords` holds the MUnit `createCustomerInNetsuite` script (`companyName` and subsidiary from `nets.customer.*`), the matching delete, and the create → assert → delete bracket that `NetsuiteApiIT` and `NetsuiteApiTest` run.

### Live tests (Tier 2B)

`NetsuiteApiIT` runs the original MUnit suite under failsafe (`mvn -B verify`) against a real NetSuite account, with one test method per MUnit test and the MUnit test names as display names.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in the eight keys:
   - `netsuite.account`;
   - `netsuite.oauth.client-id`, `netsuite.oauth.certificate-id`, and `netsuite.oauth.private-key-path` (file path of the PKCS#8 PEM key);
   - `nets.customer.companyName` and `nets.customer.subsidiary.internalId` (the test customer the IT creates and deletes);
   - `nets.opportunity.title` (title prefix of the read-only opportunity search);
   - `nets.item.quantity` (integer quantity of the read-only item search).
3. Run `mvn -B verify`. The test starts the application on a random port with the Spring profile `it`, which loads `application-it.yml`.

What the three tests assert:

- **Customers.** `support/NetsuiteTestRecords.withTestCustomer` brackets the test. Inside its protected block, `createCustomerInNetsuite` creates the test customer (`companyName`, subsidiary) through the REST record API, as the MUnit `createCustomerInNetsuite` sub-flow did, marked with an `externalId` generated for the run (`mule-it-<UUID>`). Then:
  - `GET /api/customers?name=<companyName>` must answer 200 with a non-null payload that contains `"companyName": "<companyName>"`. The request is repeated until the new customer appears, because SuiteQL may list a record shortly after it is created.
  - `deleteCustomerInNetsuite` deletes the customer in `finally`. When the create's answer is lost or carries no record ID, the customer is first found with `SELECT id FROM customer WHERE externalid = '<externalId>'`, so only this run's customer is deleted. The create goes through `NetsuiteRestClient#createRecord` and SC-9, so it is retried exactly once after an HTTP 401: the cached token is evicted and the create is sent again, which is safe because NetSuite rejected the first request before processing it. A timeout or connectivity failure is never retried, nor is a 429 rate limit, and the test adds no retry of its own.
- **Opportunities and items.** `GET /api/opportunities?title=<nets.opportunity.title>` and `GET /api/items?quantity=<nets.item.quantity>` must answer 200 with a non-null payload. Both are read-only.
- **Select `typeId`s.** Every select custom field a response carries (`SelectCustomFieldRef__*`, `MultiSelectCustomFieldRef__*`) must hold a `typeId` that is a textual NetSuite internal ID (`-?\d+`).

While `application-it.yml` or one of the eight keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` condition, never failed, and NetSuite is not called. A skipped run is not Tier 2B evidence.

#### Select `typeId` evidence (Tier 2B)

Oracle's public documentation does not state that `customfield.fieldvaluetyperecord` equals the SuiteTalk `typeId` for every custom-list source. `NetsuiteApiIT` asserts a resolved numeric `typeId` on every select field it reads. When Tier 2B runs, the sandbox values are compared here with those of the committed `api/customers-response.json`:

| Custom field | Committed `typeId` | Sandbox `typeId` |
|---|---|---|
| `custentity8` | `16` | not recorded — Tier 2B not run |
| `custentity7` | `17` | not recorded — Tier 2B not run |
| `custentity6` | `15` | not recorded — Tier 2B not run |
| `custentity_fmt_cur_sales_order` | `-30` | not recorded — Tier 2B not run |

A tenant that lacks the item supply plan feature or the `customfield` lookup is recorded here with the reason, and in the Tier 2B cell of this project's row in the root `SUMMARY.md` (root `DECISIONS.md`, section 6.8).

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| netsuite-data-retrieval-java | netsuite-data-retrieval | Third-party connector (RAML-backed) | PASS | PASS | 99.3% | RAML DIFF: 1:1 (Tier 1 static) | NOT RUN — sandbox credentials not supplied |

This row is the project's row in the root `SUMMARY.md`. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`. Surefire ran 1525 tests: 0 failures, 0 errors, 0 skipped. The enforcer rule `BannedDependencies` passed, and JaCoCo reported `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 1525 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -B verify`: `BUILD SUCCESS`. Failsafe ran the 3 tests of `NetsuiteApiIT`, all 3 skipped (`src/test/resources/application-it.yml not found; copy application-it.yml.example and fill it in`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 438 of 441 lines (99.3%) of `service/NetsuiteSearchService`, `mapper/NetsuiteRecordShapeMapper`, `mapper/CustomerSearchMapper`, `mapper/ItemSupplyPlanMapper`, `mapper/OpportunitySearchMapper` and `mapper/DwJsonWriter`. Total project LINE coverage: 93.3% (2053 of 2201 lines).
- Parity: this is a RAML-backed project, so no Tier 2A fixture set applies (no ST-2). Its evidence is the static RAML-to-controller diff in [`RAML-CONTROLLER-DIFF.md`](../../RAML-CONTROLLER-DIFF.md) (section 4, 3 of 3 rows match), plus the ported MUnit assertions of `NetsuiteApiTest` and the byte comparisons with the committed RAML examples. That is Tier 1 evidence, not runtime parity with the original.
- Tier 2B: no sandbox credentials were supplied, so `NetsuiteApiIT` was skipped. The cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

## Licence

Apache License 2.0. `LICENSE` is copied unchanged from `netsuite-data-retrieval/`. `NOTICE` is the original's, extended to credit the APIkit 3.8.0 console files (`org.mule.modules:mule-module-apikit:3.8.0`) carried under `src/main/resources/apikit-console/`.
