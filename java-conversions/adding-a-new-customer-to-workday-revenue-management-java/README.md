# Adding a new customer to Workday Revenue Management — Spring Boot conversion

`adding-a-new-customer-to-workday-revenue-management-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`adding-a-new-customer-to-workday-revenue-management`](../../adding-a-new-customer-to-workday-revenue-management/) (category Third-party connector (HTTP-triggered), Workday). The package root is `com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management` and the main class is `AddingANewCustomerToWorkdayRevenueManagementApplication`. The original folder `adding-a-new-customer-to-workday-revenue-management/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The application accepts an XML document with the information of one customer: its name, its business entity name, and a category and a status reference. It converts that document into the XML of a Workday `Put_Customer_Request` and creates the customer in a Workday Revenue Management tenant through the Workday Web Services operation `Put_Customer` (`adding-a-new-customer-to-workday-revenue-management/README.md:13`, `:51`).

The flow file `adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml` holds one flow, `add-customer-flow` (lines 7-54). It receives the HTTP request, parses the body to DOM, maps it to `Put_Customer_Request` with DataWeave, calls Workday, extracts the name Workday returns, and answers `Added customer: <name>`. The flow declares no logger and no exception strategy, so every failure gets the platform's default handling.

The original README asked for four Workday values (`adding-a-new-customer-to-workday-revenue-management/README.md:21-26`). They are the four `wday.*` keys of this project (Configuration keys below):

| Workday value | Original guidance | Key |
|---|---|---|
| user | the integration user | `wday.user` |
| tenant | the tenant ID; "It usually ends with _pt1" | `wday.tenant` |
| password | that user's password | `wday.password` |
| hostname | the host name of one of the Workday cloud servers, "for example impl-cc.workday.com" | `wday.hostname` |

After a successful call, the original README verifies the result in Workday itself: open the View Customer Report and search for the customer's name (`adding-a-new-customer-to-workday-revenue-management/README.md:45`).

## Endpoints and triggers

The listener binds to `0.0.0.0` on port `http.port`, default `8081` (`add_a_new_customer.xml:3`). The original README's port 9090 was only a suggestion for the Studio HTTP Listener global element (`adding-a-new-customer-to-workday-revenue-management/README.md:20,30`). The original's `mule-app.properties` is empty, so `http.port` had no value there; the converted default is 8081 (AAP 0.4.9).

| Method | Path | Input | Response | Source |
|---|---|---|---|---|
| `POST` | `/` | an XML body per `src/main/resources/root.xsd`: `root/Account` with `CustomerName`, `BusinessEntityName`, `Customer_Category_Reference_Type`, `Customer_Category_Reference_Value`, `Customer_Status_Reference_Type`, `Customer_Status_Reference_Value` | `200 OK`, `Content-Type: text/plain;charset=UTF-8`, body `Added customer: <Customer_Reference@Descriptor returned by Workday>` | `add-customer-flow`, `add_a_new_customer.xml:8-52` |
| `POST` | `/` | a body that is not well-formed XML: empty, malformed, or declaring a DOCTYPE | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<parser message>" }`, for example `{ "message": "Content is not allowed in prolog." }`; Workday is never called | `xml-to-dom-transformer` (xml:9) and no exception strategy → platform default (SC-1, AAP 0.4.7) |
| `POST` | `/` | a request Workday answers with a SOAP fault that is not a WS-Security rejection (`Validation_Fault`, `Processing_Fault`), with any other HTTP error, or that fails otherwise | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, for a fault its `faultstring` | `wd-connector:invoke` (xml:42) and no exception strategy → platform default (SC-1, AAP 0.4.7) |
| `POST` | `/` | a request whose Workday call fails through an approved connector failure mode | `502`, `429` or `503`, `Content-Type: application/json` (next section) | AAP 0.4.8 addition |
| `GET`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE` | `/` | any | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /` (SC-1) | `allowedMethods="POST"` (xml:8) |
| any | any other path (`/other`, `/services/x`, `/error` included) | any | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1) | the one listener serves only `/` |

- The body is read as raw bytes, whatever its `Content-Type`, and parsed in the encoding its XML declaration names. Parsing is namespace-aware and refuses a DOCTYPE, external entities and XInclude, as Mule 3.8's secure XML processing did (`service/WorkdayCustomerService`). The document is not validated against `root.xsd`, which the original never did either.
- An absent value element yields an empty Workday element and an absent type element an `ID` without `wd:type` (`mapper/PutCustomerRequestMapper`); Workday then decides whether to accept the request.
- When Workday's response carries no `Customer_Reference` or no `Descriptor`, the reply is `Added customer: null`.
- The response always carries its own `Content-Type`, so `Accept` is never consulted and no 406 or 415 arises.
- The Mule listener compared `allowedMethods` case-insensitively, so a lower-case `post` reached the flow; here it gets the 405.
- A CORS pre-flight to `/` (an `OPTIONS` request with `Origin` and `Access-Control-Request-Method`) is answered by Spring MVC with `403 Invalid CORS request` before any handler runs, where the Mule listener answered it as any `OPTIONS` request (recorded in the root `DECISIONS.md`).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

The sample request of the original README, unchanged:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<root>
	<Account>
		<CustomerName>John Doe</CustomerName>
		<BusinessEntityName>John Doe</BusinessEntityName>
		<Customer_Category_Reference_Type>Customer_Category_ID</Customer_Category_Reference_Type>
		<Customer_Category_Reference_Value>CUSTOMER_CATEGORY-5</Customer_Category_Reference_Value>
		<Customer_Status_Reference_Type>Business_Entity_Status_Value_ID</Customer_Status_Reference_Type>
		<Customer_Status_Reference_Value>ACTIVE</Customer_Status_Reference_Value>
	</Account>
</root>
```

`src/test/resources/customer.xml` is the original test resource of the same shape, with `NAME` as the customer and business entity name. With the four `wday.*` values supplied, this request adds that customer:

```sh
curl -i -X POST -H 'Content-Type: application/xml' --data-binary @src/test/resources/customer.xml http://localhost:8081/
```

The reply is `HTTP/1.1 200 OK` with the body `Added customer: <descriptor>`, where `<descriptor>` is the `Descriptor` attribute of the `Customer_Reference` that Workday returns.

Exchanges measured on this build, run without Workday values and shown here on the default port. The first three requests never reach Workday. The fourth was sent with `--wday.endpoint` pointed at a closed local port.

```console
$ curl -i http://localhost:8081/
HTTP/1.1 405 Method Not Allowed
Connection: keep-alive
Content-Type: text/plain
Content-Length: 34
Date: <current date>

Method not allowed for endpoint: /
$ curl -i -X POST http://localhost:8081/other
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 32
Date: <current date>

No listener for endpoint: /other
$ curl -i -X POST -H 'Content-Type: application/xml' --data-binary 'not-xml' http://localhost:8081/
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 50
Date: <current date>

{ "message": "Content is not allowed in prolog." }
$ curl -i -X POST -H 'Content-Type: application/xml' --data-binary @src/test/resources/customer.xml http://localhost:8081/
HTTP/1.1 503 Service Unavailable
Connection: keep-alive
Content-Type: application/json
Content-Length: 69
Date: <current date>

{ "message": "I/O error: Connection refused", "upstream": "Workday" }
```

## Connector failure modes (AAP 0.4.8)

Every Workday call runs through SC-9 `client/UpstreamCallExecutor` and is classified by `client/WorkdayFailureClassifier`. These three responses are the only behaviour the original did not have. `exception/UpstreamExceptionHandler` answers them at the highest `@Order`, ahead of SC-1.

| Failure | Behaviour | Response |
|---|---|---|
| Authentication: HTTP 401, or a SOAP fault whose code is `wsse:FailedAuthentication` or `wsse:InvalidSecurity` | re-authenticate once, then retry the call once; a second authentication failure ends the attempt | `502 Bad Gateway`, `{ "message": "<upstream message>", "upstream": "Workday" }` |
| Throttling: HTTP 429 | no retry | `429 Too Many Requests`, same body; `Retry-After` copied when Workday sends it |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `HttpTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried | `503 Service Unavailable`, same body |
| Anything else (`Validation_Fault`, `Processing_Fault`, any other HTTP status, an unexpected response) | rethrown unchanged | the SC-1 500 of the previous section |

- Every body has `Content-Type: application/json`, with APIkit's spacing. The upstream message is the fault string, or the HTTP reason phrase with its status.
- Re-authentication rebuilds the WS-Security UsernameToken from the current `wday.user`, `wday.tenant` and `wday.password`. Workday's UsernameToken is stateless, so this makes no network call.
- The retry after an authentication rejection is safe because Workday refused the request before processing it. A timeout or connectivity failure is never retried, so a `Put_Customer` that Workday may already have processed is never submitted twice. The client also streams each request with a fixed length, so the JDK never re-sends the POST on its own after a connection reset.
- The connect and read timeouts are 10 s each (`wday.connect-timeout`, `wday.read-timeout`), the Mule default response timeout.
- With `wday.endpoint` pointed at a closed port, `POST /` answers `503` with `{ "message": "I/O error: Connection refused", "upstream": "Workday" }` (measured on this build, previous section).

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. `config/WorkdayProperties` binds the `wday.*` keys; its `toString()` masks the password.

`TODO?` values: `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `no — default` — a non-secret value the original fixed or derived, overridable; `no — fixed` — a framework setting that reproduces the original listener, not a key of the original, and changing it changes behaviour.

| Key | Type | Default | TODO? | Original name | Purpose |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | no — default | `http.port` (unchanged, xml:3) | bound to `server.port`. The original's `mule-app.properties` is empty, so `http.port` had no value there; its README suggested 9090 |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | no — fixed | none | the `http:listener-config` port `${http.port}` (xml:3), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | no — fixed | none | `http:listener-config host="0.0.0.0"` (xml:3) |
| `spring.application.name` | `String` | `adding-a-new-customer-to-workday-revenue-management-java` | no — fixed | none | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | no — fixed | none | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | no — fixed | none | `TRACE /` reaches the dispatcher and gets SC-1's 405 instead of the servlet's `TRACE` echo |
| `spring.autoconfigure.exclude` | `String[]` | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | no — fixed | none | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `wday.hostname` | `String` | none, `${WDAY_HOSTNAME:}` | TODO | `wday.hostname` (unchanged, xml:5, the connector's `hostName`) | the Workday web-services host, a Workday cloud server such as the original README's example `impl-cc.workday.com` |
| `wday.tenant` | `String` | none, `${WDAY_TENANT:}` | TODO | `wday.tenant` (unchanged, xml:5, `tenantName`) | the tenant ID, which usually ends with `_pt1` |
| `wday.user` | `String` | none, `${WDAY_USER:}` | TODO | `wday.user` (unchanged, xml:5, `username`) | the integration user, without `@tenant`; the UsernameToken carries `wday.user@wday.tenant` |
| `wday.password` | `String` | none, `${WDAY_PASSWORD:}` | TODO (secret) | `wday.password` (unchanged, xml:5, `password`) | that user's password, sent as a `PasswordText` UsernameToken password |
| `wday.endpoint` | `String` | `https://${wday.hostname}/ccx/service/${wday.tenant}/Revenue_Management/v25.0` | no — default | none; the connector derived this URL from `hostName` and `tenantName` | the Revenue_Management v25.0 service URL every call goes to. Override it only for a tenant that uses another service URL; the unit tests point it at WireMock |
| `wday.connect-timeout` | `Duration` | `10s` | no — default | none | connect timeout of every Workday call, the Mule default response timeout (AAP 0.4.8); a bare number binds as milliseconds |
| `wday.read-timeout` | `Duration` | `10s` | no — default | none | read timeout of every Workday call, with the same rules |

- The four connector keys keep the original placeholder names (AAP 0.4.6), so the original-to-new key map is the identity: `wday.hostname`, `wday.tenant`, `wday.user` and `wday.password` mean what they meant in `wd-connector:config`.
- Two keys of the original MUnit suite are read only by the live test, never by the application: `wday.status.id` and `wday.reason.id` (`src/test/munit/integration-test.xml:107,110`), the WIDs of the inactive customer status and of the reason for the status change. They live in `src/test/resources/application-it.yml` (Live tests below).
- `src/test/resources/application-test.yml` (Spring profile `test`) fills the four connector keys and the two test-data keys with fake values for the unit tests: host `localhost`, tenant `test_pt1`, user `testuser`, a fake password, and the WIDs `TEST-STATUS-WID` and `TEST-REASON-WID`. None is a Workday value, and none authenticates anywhere.
- The application starts with every `wday.*` value empty, because nothing is validated at bind time. Every `POST /` then fails: with no host, the derived endpoint `https:///ccx/service//Revenue_Management/v25.0` names no server, and the reply is the SC-1 500 `{ "message": "I/O error: Failed to select a proxy" }` (measured on this build).
- Supply the values through the environment, never in a committed file. `WDAY_HOSTNAME`, `WDAY_TENANT`, `WDAY_USER` and `WDAY_PASSWORD` are named by the placeholders of `application.yml`; every other key can be set through Spring Boot's relaxed binding (`HTTP_PORT` for `http.port`, `WDAY_ENDPOINT` for `wday.endpoint`, `WDAY_READTIMEOUT` for `wday.read-timeout`) or a `--key=value` argument:

  ```sh
  WDAY_HOSTNAME='<host>' WDAY_TENANT='<tenant>' WDAY_USER='<user>' WDAY_PASSWORD='<password>' mvn spring-boot:run
  # another port
  WDAY_HOSTNAME='<host>' WDAY_TENANT='<tenant>' WDAY_USER='<user>' WDAY_PASSWORD='<password>' java -jar target/adding-a-new-customer-to-workday-revenue-management-java-1.0.0-SNAPSHOT.jar --http.port=9090
  ```

## Mule-to-Java mapping

| Mule construct (`adding-a-new-customer-to-workday-revenue-management`) | Java construct (`com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (xml:3) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `wd-connector:config` with `${wday.hostname}`, `${wday.tenant}`, `${wday.user}`, `${wday.password}` (xml:5) | `config/WorkdayProperties` (`@ConfigurationProperties(prefix = "wday")`) and the endpoint and UsernameToken of `client/WorkdayRevenueManagementClient` |
| `http:listener allowedMethods="POST" path="/"` (xml:8) | `controller/WorkdayCustomerController#addCustomer` (`@PostMapping("/")`, the body as raw bytes); `#options` answers `OPTIONS /` with the 405 |
| flow `add-customer-flow`, `processingStrategy="synchronous"` (xml:7-54) | `service/WorkdayCustomerService#addCustomer`, one statement per processor in the original order, on the request thread |
| `mulexml:xml-to-dom-transformer` (xml:9) | the namespace-aware, hardened DOM parse inside `WorkdayCustomerService` |
| `dw:transform-message` "XML to Workday PutCustomerRequest XML" (`%dw 1.0` at xml:12) | `mapper/PutCustomerRequestMapper#map`, which builds the generated `Put_Customer_Request` element |
| `wd-connector:invoke type="Revenue_Management\|\|Put_Customer"` (xml:42) | `client/WorkdayRevenueManagementClient#putCustomer`: a SOAP 1.1 `WebServiceTemplate` with a `Jaxb2Marshaller` over the XJC-generated package `com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated`, and a `Wss4jSecurityInterceptor` adding a UsernameToken with a `PasswordText` password, without nonce or creation time, for the user `wday.user@wday.tenant`; 10 s connect and read timeouts |
| `dw:transform-message` "Extract Customer Name" (`%dw 1.0` at xml:44) | `mapper/PutCustomerResponseMapper#descriptor`: `Put_Customer_Response/Customer_Reference/@Descriptor`, `null` when absent |
| `set-payload value="Added customer: #[payload]"` (xml:52) | `WorkdayCustomerService.RESPONSE_PREFIX` followed by the descriptor; the controller sends it as `text/plain;charset=UTF-8` |
| (no original construct) failure classification | `client/WorkdayFailureClassifier`, the Workday implementation of SC-9's `client/UpstreamFailureClassifier` |
| (approved addition, no original construct) | SC-9 `client/UpstreamCallExecutor` and `exception/UpstreamExceptionHandler` with the three upstream exceptions |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| `mule-deploy.properties` (`config.resources=add_a_new_customer.xml`) | `AddingANewCustomerToWorkdayRevenueManagementApplication` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`). It excludes `WebServicesAutoConfiguration`: the project only consumes SOAP, and that auto-configuration would otherwise map Spring WS's `MessageDispatcherServlet` at `/services/*`, a path the original never served |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| MUnit suite `src/test/munit/integration-test.xml` (live tenant) | `AddingANewCustomerToWorkdayTest` (surefire, WireMock Workday) and `AddingANewCustomerToWorkdayIT` (failsafe, live tenant) |
| the suite's three DataWeave scripts (`%dw 1.0` at lines 37, 64 and 87) and its `Get_Customers` and inactivating `Put_Customer` invocations (lines 80, 119) | `support/WorkdayTestRequests`, sent through `WorkdayRevenueManagementClient#sendRaw` with the same UsernameToken, timeouts and failure handling |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (AAP 0.4.4), each copied with its tests and with only the package root changed (the `package` line and the imports of the project's own types); the register is in the root `DECISIONS.md`:

- **SC-1** `exception/GlobalExceptionHandler` and `exception/ErrorResponse`, with `exception/GlobalExceptionHandlerTest`, from `rest-api-with-apikit-java`.
- **SC-9** `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException`, `exception/UpstreamUnavailableException` and, because this project is HTTP-triggered, `exception/UpstreamExceptionHandler`, with `client/UpstreamCallExecutorTest` and `exception/UpstreamExceptionHandlerTest`, from `salesforce-data-retrieval-java`.
- **ST-1** `support/HttpTestClient`, from `rest-api-with-apikit-java`.
- **ST-2** `FixtureParityTest` and the `SCENARIOS.md` format, from `hello-world-java`.

## Workday WSDL and generated classes

- `src/main/resources/wsdl/Revenue_Management_v25.0.wsdl` is Workday's public Revenue_Management v25.0 WSDL, downloaded unchanged from `https://community.workday.com/sites/default/files/file-hosting/productionapi/Revenue_Management/v25.0/Revenue_Management.wsdl` (1,524,923 bytes).
- Version v25.0 is kept because the original targets it (`catalog/Workday__md__io__.xml`). `Put_Customer` is replaced by `Submit_Customer` from v31.1, so a later WSDL would change the operation (AAP 0.3.3, 0.10.2). The v25.0 WSDL has no operation named `Submit_Customer`; its `Submit_Customer_*` text matches are sibling operations such as `Submit_Customer_Invoice`.
- Every call goes to `https://{wday.hostname}/ccx/service/{wday.tenant}/Revenue_Management/v25.0`, the key `wday.endpoint`. The WSDL's own `soap:address` is relative, so it is never used. The binding declares no `soapAction`, so every request carries the SOAP 1.1 default `SOAPAction: ""`.
- `jaxb2-maven-plugin` 4.2.0 (goal `xjc`, `sourceType` `wsdl`, phase `generate-sources`) generates the Jakarta JAXB classes of the embedded schema into `target/generated-sources/jaxb`, package `com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated`. In WSDL mode XJC binds the schema only and emits no JAX-WS service stubs. The classes are rebuilt on every build, never committed, and excluded from the JaCoCo report and check (`**/generated/**`).
- This build generated 897 classes with no extra XJC argument; `-XautoNameResolution` was not needed, and the `pom.xml` passes none.
- The `maven-enforcer-plugin` execution `require-workday-wsdl` fails `generate-sources` when the WSDL is missing, because XJC's staleness check would otherwise skip generation and hide it.
- The marshaller creates its `JAXBContext` lazily on the first call, so start-up stays fast despite the size of the schema.
- No JAX-WS or CXF is used. The enforcer execution `ban-jaxws-cxf` fails the build at `validate` if `javax.xml.ws`, `jakarta.xml.ws`, `javax.jws`, `jakarta.jws`, `javax.xml:jaxws-api`, `org.apache.cxf`, `com.sun.xml.ws` or `org.glassfish.metro` artefacts enter, transitively included (AAP 0.3.2). The project depends on the client side of Spring WS alone (`spring-ws-core`, `spring-ws-security`, `spring-oxm`, `jaxb-runtime`, `saaj-impl`) and not on `spring-boot-starter-web-services`, because it hosts no SOAP endpoint.

## Behaviours stubbed or externalized

- Externalized: the Workday host, tenant, user and password, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks. The service URL and the timeouts, which the original connector derived or defaulted, are configurable keys.
- Stubbed in tests only: the unit tier replaces Workday with an in-process WireMock SOAP stub (`AddingANewCustomerToWorkdayTest`, the controller and client tests) or a Mockito-mocked client (`service/WorkdayCustomerServiceTest`). At run time nothing is stubbed and no behaviour was dropped.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/test/resources/log4j2-test.xml` and the Studio samples `src/test/resources/sample_data/root.xml` and `root_1.xml` (AAP 0.2.2).

Tier 2A verification points: the plan chose these values where the original platform, not the flow, fixed the bytes. When a captured fixture of the original shows different bytes, the fixture governs and the Java code is changed to match.

- The body of the platform-default 500, for malformed XML and for a Workday fault (SC-1's `{ "message": "<exception message>" }`), and the bytes of the 404 and 405 defaults.
- The `Content-Type` of the success reply, `text/plain;charset=UTF-8`, because the flow's string payload names no MIME type.
- The rendering of a missing `Descriptor`: `Added customer: null` here; how Mule rendered a null DataWeave result inside `#[payload]` is not settled by the sources.
- The `Put_Customer_Request` the mapper writes for absent input elements (empty elements, an `ID` without `wd:type`) and its element order, which follows the v25.0 schema sequence (`Customer_Category_Reference` before `Business_Entity_Data`) where the script lists them the other way round.
- A lower-case `post`, which the Mule listener accepted and which gets the 405 here; and a CORS pre-flight, which the Mule listener answered as a plain `OPTIONS` request.

## Build, run and test

Run from `java-conversions/adding-a-new-customer-to-workday-revenue-management-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, WireMock stands in for Workday
mvn -B verify           # surefire tests, then the failsafe AddingANewCustomerToWorkdayIT against a live tenant (Tier 2B)
mvn spring-boot:run     # listener on 0.0.0.0:8081; needs wday.hostname, wday.tenant, wday.user and wday.password
java -jar target/adding-a-new-customer-to-workday-revenue-management-java-1.0.0-SNAPSHOT.jar
```

- `mvn test` needs no licence, credential or network access to Workday: Workday is a WireMock SOAP stub on `localhost`, or a Mockito mock.
- The build needs the Shibboleth releases repository `https://build.shibboleth.net/maven/releases/`, declared in `pom.xml` as `shibboleth-releases`. `spring-ws-security` 4.1.4 brings WSS4J 4.0.1, which depends on OpenSAML 5, and OpenSAML 5 is not published to Maven Central. Every other artefact comes from Maven Central.
- `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's Spring WS 4.0.11; Spring Framework stays at the BOM's 6.1.15 (root `DECISIONS.md`).
- The build runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` and the WSDL presence check and XJC at `generate-sources` (previous section).
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.

## Live tests (Tier 2B)

`AddingANewCustomerToWorkdayIT` runs under failsafe (`mvn -B verify`) against a real Workday tenant. It reuses the existing Workday tenant and integration user; no new tenant is procured (AAP 0.2.4).

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `wday.hostname`, `wday.tenant`, `wday.user` (without `@tenant`), `wday.password`, `wday.status.id` (the WID of an inactive business entity status value) and `wday.reason.id` (the WID of a reason for customer status change). Set `wday.endpoint` only if the tenant uses another service URL.
3. Run `mvn -B verify`. The test starts the application on a random port with the Spring profile `it`, which loads `application-it.yml`.

The IT runs the original MUnit test (`src/test/munit/integration-test.xml`) step by step:

- It names the customer `John<millis>` (`"John" + System.currentTimeMillis()`) and posts the `root/Account` XML to the running application's `POST /`, which must answer 200 with `Added customer: <descriptor>`.
- It reads the customer back with `Get_Customers` by its `Customer_Reference_ID` and asserts `Total_Results` 1.
- In `@AfterEach` it inactivates the customer with a `Put_Customer` carrying `wday.status.id`, `wday.reason.id` and the retrieved `Customer_Category_ID`. Workday exposes no customer delete, so the customer is inactivated, not deleted, as in the original. The inactivation also runs when an assertion fails, and when the post's outcome is unknown but `Get_Customers` finds the customer.

While `application-it.yml` or one of the six keys is missing, blank, still `TODO` or an unresolved `${...}` placeholder, the class is disabled by its `LiveCredentialsGate` condition: the test is reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `AddingANewCustomerToWorkdayTest` | the ported MUnit suite (`integration-test.xml:32-122`), run offline against a WireMock Workday: `POST /` with the `John<millis>` customer answers `Added customer: <descriptor>`, the descriptor taken from Workday's response rather than echoed from the input; `Get_Customers` by `Customer_Reference_ID` returns `Total_Results` `1`; the inactivating `Put_Customer` carries both WIDs and the `Customer_Category_ID` chosen by its `type`, not the first `ID`; each of the three Workday calls is sent once |
| `AddingANewCustomerToWorkdayIT` | the same steps against a live tenant (failsafe, previous section) |
| `controller/WorkdayCustomerControllerTest` | on the wire through ST-1, the whole application against WireMock: `customer.xml` → 200 `text/plain;charset=UTF-8` `Added customer: NAME`, with one `Put_Customer` carrying the mapped values and the `PasswordText` UsernameToken `testuser@test_pt1`; `GET /` and `OPTIONS /` → 405; `POST /other` → 404; malformed XML → 500 with no Workday call; a Workday validation fault → 500 `{ "message": "<faultstring>" }`; HTTP 401 → two attempts, then 502; HTTP 429 → 429 with `Retry-After: 30`; a read timeout → 503 after one attempt |
| `service/WorkdayCustomerServiceTest` | the flow's order (parse, request mapping, `Put_Customer`, descriptor, `Added customer: `); `customer.xml` reaching Workday as the mapper builds it; the encoding of the XML declaration honoured; malformed XML and a DOCTYPE failing before Workday is called; every upstream and vendor failure passed through unchanged with no retry; a `null` descriptor concatenated verbatim |
| `mapper/PutCustomerRequestMapperTest` | the l12 script: every `customer.xml` field in its `Customer_Data` element, `wd:`-qualified `type` attributes, no `Customer_Reference`, `wd:Add_Only` or `wd:version`, the v25.0 schema order, elements matched by local name, empty and absent input |
| `mapper/PutCustomerResponseMapperTest` | the l44 script: the `Customer_Reference` descriptor returned unchanged, the reference IDs ignored, `null` text when it is absent, read from a response unmarshalled from Workday's wire form |
| `client/WorkdayRevenueManagementClientTest` | WireMock SOAP: SOAP 1.1 with no SOAP action and a `PasswordText` UsernameToken `user@tenant` without nonce or creation time; one re-authentication and one retry after HTTP 401 or a `wsse:FailedAuthentication` fault, and failure after a second 401; no retry after HTTP 429, a generic fault, a read timeout or a refused connection, each attempt counted; `sendRaw` returning the `Get_Customers` document |
| `client/WorkdayRevenueManagementClientTransportTest` | exactly one request on the wire after a connection reset, an end of stream or a read timeout (fixed-length streaming, so the JDK never re-sends the POST); the `Content-Length` header; the exception each outcome raises |
| `client/WorkdayRevenueManagementClientLoggingTest` | re-authentication logs fixed text and never the `user@tenant` principal or the password |
| `client/WorkdayFailureClassifierTest` | the Workday classification: 401 and the two WS-Security fault codes → authentication, 429 → rate limit with its `Retry-After`, every timeout and connectivity cause → unavailable and never retried, validation faults → other; how often a `Put_Customer` is sent in each case, through the real executor and end to end against WireMock |
| `client/UpstreamCallExecutorTest`, `exception/UpstreamExceptionHandlerTest` | SC-9 (copied): one re-authentication and one retry, no retry on rate limit or unavailability, the 502, 429 and 503 status lines and bodies, `Retry-After` copied, precedence over other advices |
| `exception/GlobalExceptionHandlerTest` | SC-1 (copied): the 404, 405 and 500 status lines, `Content-Type` headers and body bytes, and the one ERROR log line per unhandled exception |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no Tier 2A fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes. `support/WorkdayTestRequests` holds the MUnit suite's three request scripts and its `Total_Results` read.

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of `src/test/resources/fixtures/SCENARIOS.md`, which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-add-customer` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body = the bytes of `customer.xml` | `UNVERIFIED-uncaptured` |
| `get-root-method-not-allowed` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-unknown-path-not-found` | `http` | `deterministic` | `POST /other`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-malformed-xml` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body `not-xml` | `UNVERIFIED-uncaptured` |
| `post-root-workday-fault` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body = the bytes of `customer.xml`; Workday answers with a fault that rejects the customer data | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`.
- The approved connector failure modes (AAP 0.4.8) have no original counterpart, so they have no row and are never captured; their Tier 1 evidence is the client, classifier, executor, handler and controller tests above.
- The two rows that reach Workday (`post-root-add-customer`, `post-root-workday-fault`) carry the live UsernameToken inside the recorded SOAP request body, which no committed file may hold. They therefore omit `upstream` and stay `UNVERIFIED-no-upstream` after capture.
- Capture can raise the label at most to `PARITY: PARTIAL — Tier 2A fixtures: 3 verified, 2 unverified`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| adding-a-new-customer-to-workday-revenue-management-java | adding-a-new-customer-to-workday-revenue-management | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

This row equals the project's row in the root `SUMMARY.md`. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`. Surefire ran 445 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`.
- `mvn -B clean install`: `BUILD SUCCESS`; the same 445 surefire tests; failsafe ran 1 test, skipped (`AddingANewCustomerToWorkdayIT`, no `application-it.yml`).
- `mvn -B test`: `BUILD SUCCESS`; 445 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 73 of 73 lines (100.0%) of `service/WorkdayCustomerService` (with its nested `RethrowingErrorHandler`), `mapper/PutCustomerRequestMapper` and `mapper/PutCustomerResponseMapper`. Total project LINE coverage, the generated classes excluded: 96.0% (582 of 606 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `AddingANewCustomerToWorkdayIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback. That block does not affect Tier 2B, which needs only the Workday credentials of the previous sections (AAP 0.8.1, 0.8.8).

- `AddingANewCustomerToWorkdayTest` carries the original MUnit test with its comparison semantics: the add call, then `Total_Results` equal to `1` from `Get_Customers`, then the inactivation.
- `controller/WorkdayCustomerControllerTest` and the service, mapper and client tests cover every reachable branch on the wire or against the class.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the platform defaults, but not that its bytes equal what the original runtime sent. The Workday responses in these tests are hand-built stubs, not recordings from a tenant.
- Branches no original assertion covers, tested only by the new tests: the 405 for another method (`get-root-method-not-allowed`), the 404 for another path (`post-unknown-path-not-found`), the 500 for malformed XML (`post-root-malformed-xml`), the 500 for a Workday fault (`post-root-workday-fault`), and the three approved connector failure modes (502, 429, 503).
- Unverified scenarios, until captured: `post-root-add-customer`, `get-root-method-not-allowed`, `post-unknown-path-not-found`, `post-root-malformed-xml`, `post-root-workday-fault`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `adding-a-new-customer-to-workday-revenue-management/`.
