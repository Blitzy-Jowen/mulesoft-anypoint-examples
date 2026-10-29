# foreach-processing-and-choice-routing-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`foreach-processing-and-choice-routing`](../../foreach-processing-and-choice-routing/) (category General). The original folder `foreach-processing-and-choice-routing/` stays unchanged beside this project and is the Tier 2A capture source. Every listener runs on Undertow. The SOAP services and clients use Spring WS 4.1.4 over SAAJ and Jakarta JAXB. No JAX-WS or CXF artefact is on the classpath: the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if one arrives, even transitively (AAP 0.3.2).

## Original example

The original is the synchronous Mule loan broker `loanbroker-simple`, modelled on the composed-messaging sample of *Enterprise Integration Patterns*. Its single flow file, `foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml`, holds the main flow `loan-broker-sync` (lines 20-65), four sub-flows (lines 67-134) and six mock SOAP services (lines 136-195). A quote request arrives on HTTP port 11081 with the borrower's name, SSN, loan amount and term as query parameters. The flow looks up the borrower's credit profile from a SOAP credit agency (`getCreditProfile`) and chooses the banks to ask by loan amount with a choice router. It then asks each chosen bank for a quote (`getLoanQuote`) in a `foreach`, keeps the quote with the lowest interest rate and returns it as text, `<bankName>, rate: <interestRate>`. The credit agency and the five banks are mock flows of the same application (`TheCreditAgencyService`, `Bank1Flow` to `Bank5Flow`). Each is a `cxf:jaxws-service` in front of a singleton component that answers random values.

## Endpoints and triggers

All seven listeners run in one process, as the seven `http:listener-config` elements of the original did (`loanbroker-simple.xml:4-12`). Each binds host `0.0.0.0`, and none restricts methods: no listener declares `allowedMethods`. The project has no non-HTTP trigger.

| Port | Path | Methods | Implemented by | Behaviour |
|---|---|---|---|---|
| `11081` (`server.port`) | `/*`: every path, `/`, `/error` and `/mule/**` included | every method token, `HEAD`, `OPTIONS`, `TRACE` and extension methods such as `PROPFIND` included; a CORS pre-flight runs the flow like a plain `OPTIONS` request | `controller/LoanBrokerController` → `service/LoanBrokerService` | Reads the query parameters `name`, `ssn`, `amount` and `term` from the query string. Any of them missing → `Error: incomplete request`. `ssn`, `amount` and `term` are parsed with `Integer.parseInt`; a non-integer value → `Error processing loan request` (the original `catch-exception-strategy`, `exception/LoanBrokerExceptionHandler`), still HTTP 200. Success → the lowest quote, for example `Bank #1, rate: 3.2135…`. Every reply is `200 OK` with a `Content-Length` and no `Content-Type`. |
| `18080` (`ports.credit-agency`) | `/mule/TheCreditAgencyService` and every sub-path | every method | `controller/CreditAgencyEndpoint` over `service/DefaultCreditAgency` | SOAP 1.1 operation `getCreditProfile` in namespace `http://creditagency.loanbroker.example.mule.org/`: request `getCreditProfile` with the customer as `arg0`, response `getCreditProfileResponse` with the profile as `return`. `GET <path>?wsdl` → `wsdl/CreditAgencyService.wsdl` (service `DefaultCreditAgencyService`, port `DefaultCreditAgencyPort`, portType `CreditAgencyService`). |
| `10080`, `20080`, `30080`, `40080`, `50080` (`ports.bank1` to `ports.bank5`) | `/mule/TheBank1` to `/mule/TheBank5` respectively, each with every sub-path | every method | `controller/BankEndpoint` over `service/Bank` | SOAP 1.1 operation `getLoanQuote` in namespace `http://bank.loanbroker.example.mule.org/`: request `getLoanQuote` with the `LoanBrokerQuoteRequest` as `arg0`, response `getLoanQuoteResponse` with the `LoanQuote` as `return`. The bank is chosen by the port that received the request (table below). `GET <path>?wsdl` → `wsdl/BankService.wsdl` (service `BankService`, port `BankPort`, portType `BankService`). |

| Bank | Port key | Default port | Path | `bankName` |
|---|---|---|---|---|
| Bank 1 | `ports.bank1` | `10080` | `/mule/TheBank1` | `Bank #1` |
| Bank 2 | `ports.bank2` | `20080` | `/mule/TheBank2` | `Bank #2` |
| Bank 3 | `ports.bank3` | `30080` | `/mule/TheBank3` | `Bank #3` |
| Bank 4 | `ports.bank4` | `40080` | `/mule/TheBank4` | `Bank #4` |
| Bank 5 | `ports.bank5` | `50080` | `/mule/TheBank5` | `Bank #5` |

- Every `?wsdl` reply is the authored WSDL, `Content-Type: text/xml;charset=UTF-8`, with its `soap:address` `location` rewritten to the request URL, for example `http://localhost:10080/mule/TheBank1` (SC-11 `config/WsdlQueryPublisher`). One `BankService.wsdl` serves all five banks.
- A SOAP response has CXF's envelope form: `soap:` envelope prefix, no empty `Header`, the JAXB `ns2:` payload prefix, no XML declaration and no whitespace between elements (SC-11 `config/SoapEnvelopeStyleInterceptor`):

  ```xml
  <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:getLoanQuoteResponse xmlns:ns2="http://bank.loanbroker.example.mule.org/"><return><bankName>Bank #5</bankName><interestRate>7.112060927375144</interestRate></return></ns2:getLoanQuoteResponse></soap:Body></soap:Envelope>
  ```

- The loan broker on 11081 serves every path, so `/mule/TheBank1?wsdl` sent to 11081 is a loan request without parameters and answers `Error: incomplete request`.

### Listener defaults and faults (AAP 0.4.7)

| Case | Response | Enforced by |
|---|---|---|
| A path requested on a port that does not serve it, for example `GET /mule/TheCreditAgencyService` on 10080 | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` | SC-10 `config/PortRoutingFilter` |
| An exception inside a SOAP endpoint | HTTP 500, SOAP 1.1 fault, `faultcode` `soap:Server`, `faultstring` the exception message (CXF's `Fault occurred while processing.` when it has none). A `NullPointerException` the JVM raised counts as having none, so a `getLoanQuote` whose `arg0` carries no `creditProfile`, or a `getCreditProfile` without `arg0`, answers `Fault occurred while processing.` | SC-11 `config/SoapFaultConfig` |
| A `mustUnderstand="1"` header block that nothing on the service understands, for example `<x:Sec xmlns:x="urn:x" soap:mustUnderstand="1"/>` | HTTP 500, `soap:MustUnderstand`, `faultstring` `MustUnderstand headers: [{<namespace>}<localName>] are not understood.`, for example `MustUnderstand headers: [{urn:x}Sec] are not understood.`; no endpoint runs | SC-11 `config/SoapFaultConfig` (`CxfStyleSoapMessageDispatcher`) |
| A body root the receiving port's service does not have, for example `getCreditProfile` sent to a bank port or `getLoanQuote` sent to 18080 | HTTP 500, `soap:Client`, `faultstring` `Message part {<namespace>}<localName> was not recognized.  (Does it exist in service WSDL?)` (two spaces before the parenthesis) | SC-11 `config/SoapFaultConfig`, with the port check of `CreditAgencyEndpoint` and `BankEndpoint` |
| A malformed, empty or non-envelope body, a body nested deeper than 100 elements (CXF's default `maxElementDepth`), or a non-POST request without `?wsdl` (read as a message, so its empty body fails to parse) | HTTP 500, `soap:Client`, `faultstring` the parser's message, for example `Premature end of file.` for an empty body | SC-11 `config/SoapListenerBoundaryAdapter` |

- Tier 2A verification points, where a captured fixture of the original governs and the code is changed to match: the fault texts and bytes above, the absent `Content-Type` and the other headers of the loan-broker reply, the SOAP response and fault headers, which carry neither the `Accept` nor the `SOAPAction` request header SAAJ keeps on every message (dropped by SC-11 `config/SoapListenerBoundaryAdapter`, as CXF sent neither), characters outside the Basic Multilingual Plane written as raw UTF-8 rather than SAAJ's numeric character references (SC-11 `SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory`, as CXF wrote them; `DECISIONS.md` section 9, verification points) and the published WSDLs.
- No listener restricts methods, so the method 405 of AAP 0.4.7 cannot occur: `LoanBrokerController` maps `/**` without a method, plus an explicit `OPTIONS` mapping because Spring MVC would otherwise answer `OPTIONS` itself with an `Allow` header, so `PROPFIND` and every other extension method run the loan broker too.
- No listener applied a CORS policy, so a CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered exactly like a plain `OPTIONS` request to the same path, with no `Access-Control-*` or `Vary` header: on 11081 it runs the loan broker (`config/PreflightDispatchConfig`); on a SOAP port it is read as a message on the service path and gets the no-listener 404 elsewhere.

### Sample exchanges

With the application on its default ports (the rate and `Date` values vary):

```console
$ curl -i 'http://localhost:11081/?name=Muley&amount=20000&term=48&ssn=1234'
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 32
Date: <current date>

Bank #2, rate: 5.919885798960137

$ curl -i 'http://localhost:11081/?name=Muley&amount=20000&term=48'
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 25
Date: <current date>

Error: incomplete request

$ curl -i 'http://localhost:10080/mule/TheCreditAgencyService'
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 54
Date: <current date>

No listener for endpoint: /mule/TheCreditAgencyService
```

## Configuration keys

Every key of `src/main/resources/application.yml`. The original reads no placeholder and its `mule-app.properties` defines no key, so every value is a fixed default taken from the flow's literals (AAP 0.6.6). There are no secrets, credentials or TODO placeholders, so the project has no `application-test.yml` and no `application-it.yml`.

The Type column gives the Java type each value binds to, taken from the code or the Spring Boot property that reads it.

| Key | Type | Default | Source in the original | Purpose |
|---|---|---|---|---|
| `server.port` | `Integer` | `11081` | `http:listener-config` port, `loanbroker-simple.xml:4` | the loan broker's main listener |
| `server.address` | `InetAddress` | `0.0.0.0` | `host="0.0.0.0"`, `:4` | bind host of the main listener |
| `ports.credit-agency` | `int` | `18080` | listener and request config port, `:5,7` | the credit agency's listener and the port the loan broker calls |
| `ports.bank1`, `ports.bank2`, `ports.bank3`, `ports.bank4`, `ports.bank5` | `int` | `10080`, `20080`, `30080`, `40080`, `50080`, in that order | listener ports `:8-12` and bank URIs `:83-89` | each bank's listener and the port in the bank URI the loan broker calls |
| `loanbroker.outbound-host` | `String` | `0.0.0.0` | host of the credit-agency request config and of every bank URI, `:5,83-89` | host of every outbound SOAP call, so log lines such as `Banks to contact: [http://0.0.0.0:10080/mule/TheBank1, http://0.0.0.0:20080/mule/TheBank2]` stay identical |
| `loanbroker.response-timeout` | `long` (milliseconds) | `10000` (milliseconds) | `responseTimeout="10000"`, `:73,108` | connect and read timeout of the shared outbound `WebServiceTemplate` |
| `logging.level.org.springframework.ws.client.MessageTracing` | `LogLevel` | `INFO` | none: the flow's loggers never write the applicant | its `.sent`/`.received` `TRACE` write every SOAP envelope: `getCreditProfile` carries the name and ssn |
| `logging.level.org.springframework.ws.server.MessageTracing` | `LogLevel` | `INFO` | none | the same envelopes as the credit agency and the banks receive and answer them |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | its `DEBUG` and `TRACE` write the request line with the query (`name`, `ssn`). `WARN`, not `INFO`: its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | its `TRACE` writes handler arguments, the `NumberFormatException` quoting a malformed ssn or amount among them |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | its `DEBUG` writes the handled exception's message, which quotes a malformed ssn or amount |
| `spring.webservices.path` | `String` | `/mule` | the `/mule/...` base paths, `:7-12` | maps Spring WS's `MessageDispatcherServlet` at `/mule/*`; `dispatcherServlet` stays at `/` |
| `spring.webservices.servlet.init` | `Map<String, String>` (servlet init-parameter name to value) | `"[dispatchOptionsRequest]": "true"`, `"[dispatchTraceRequest]": "true"` | listeners accept every method | `OPTIONS` and `TRACE` reach the SOAP listener boundary like any other method |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | listener accepts every method | `TRACE` on 11081 reaches `LoanBrokerController` |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | no static-resource handler takes a path from the loan broker or from SC-1 |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | `path="/*"` covers `/error` | no Boot `/error` endpoint; `/error` on 11081 belongs to the loan broker |
| `spring.application.name` | `String` | `foreach-processing-and-choice-routing-java` | none | application name in log lines |
| `listeners.host` | `String` | `0.0.0.0` | `host="0.0.0.0"`, `:7-12` | bind host of the six additional listeners (SC-10) |
| `listeners.disallowed-methods` | `List<String>` (HTTP method names) | `[]` | no `allowedMethods` | no method is rejected on any listener |
| `listeners.main.routes[0].servlet` | `String` | `dispatcherServlet` | `path="/*"` without `basePath`, `:4,21` | the servlet behind the one route of `listeners.main.routes`, a `List<ListenerPorts.Route>` |
| `listeners.main.routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `["/**"]` | `path="/*"` without `basePath`, `:4,21` | every path on 11081 belongs to the loan broker |
| `listeners.additional[i].name` | `String` | `[0]` `credit-agency`, `[1]` `bank1`, `[2]` `bank2`, `[3]` `bank3`, `[4]` `bank4`, `[5]` `bank5` | none: a label for `HTTP_Listener_Configuration_2` to `_7`, `:7-12` | label of each entry of `listeners.additional`, a `List<ListenerPorts.Additional>` of the extra Undertow listeners (SC-10), in log lines and start-up errors |
| `listeners.additional[i].port` | `int` | `[0]` `${ports.credit-agency}`, `[1]` `${ports.bank1}`, `[2]` `${ports.bank2}`, `[3]` `${ports.bank3}`, `[4]` `${ports.bank4}`, `[5]` `${ports.bank5}` | listener ports, `:7-12` | the port each extra listener binds, read from its `ports.*` key |
| `listeners.additional[i].routes[0].servlet` | `String` | `messageDispatcherServlet` in `[0]`, `[1]`, `[2]`, `[3]`, `[4]` and `[5]` | `basePath` + `path="/*"`, `:7-12` and the mock flows' listeners | the servlet behind each extra listener's one route |
| `listeners.additional[i].routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `[0]` `["/mule/TheCreditAgencyService", "/mule/TheCreditAgencyService/**"]`, `[1]` `["/mule/TheBank1", "/mule/TheBank1/**"]`, `[2]` `["/mule/TheBank2", "/mule/TheBank2/**"]`, `[3]` `["/mule/TheBank3", "/mule/TheBank3/**"]`, `[4]` `["/mule/TheBank4", "/mule/TheBank4/**"]`, `[5]` `["/mule/TheBank5", "/mule/TheBank5/**"]` | `basePath` + `path="/*"`, `:7-12` and the mock flows' listeners | the paths each extra listener serves (SC-10) |

- A port key moves both the listener and the URI the loan broker calls, because both read it. Additional listener ports must be concrete, unique and between 1 and 65535, and the five bank ports must differ from one another (`BankEndpoint` fails start-up otherwise).
- The five `logging.level` keys keep the applicant's name and ssn out of the log at every root level, under `--debug` and `--trace` too, while the four sub-flow loggers and the banks' `Returning Rate is:` line still log at INFO. The original's loggers never wrote the applicant. The nested `UnderRootTrace` and `UnderRootDebug` runs of `controller/LoanBrokerControllerTest` assert this.
- Override a port on the command line or through the environment (relaxed binding maps `PORTS_BANK1` to `ports.bank1`):

  ```sh
  java -jar target/foreach-processing-and-choice-routing-java-1.0.0-SNAPSHOT.jar --ports.bank1=11080
  mvn spring-boot:run -Dspring-boot.run.arguments=--ports.bank1=11080
  ```

## Mule-to-Java mapping

| Mule construct (`loanbroker-simple.xml`) | Java construct (`com.mulesoft.examples.foreach_processing_and_choice_routing`) |
|---|---|
| `http:listener-config` 11081 and `http:listener path="/*"` of `loan-broker-sync` (`:4,21`) | `controller/LoanBrokerController`, `@RequestMapping("/**")` without a method plus `@RequestMapping(value = "/**", method = OPTIONS)`, so every method token runs the flow, on Undertow (`spring-boot-starter-undertow`, Tomcat excluded); `config/PreflightDispatchConfig` dispatches a CORS pre-flight as a plain `OPTIONS` request, as the listener applied no CORS policy |
| `expression-component` copying the query parameters (`:23-26`), the required-field `choice` (`:28-60`), the `Create customer request` expression, both `enricher`s, `set-variable quotes`, `foreach` (`:47-51`), `object-to-string-transformer` | `service/LoanBrokerService.quote`, the processors as statements in their original order |
| sub-flows `lookupCustomerCreditProfile`, `lookupBanks` (bank `choice`), `lookupLoanQuote`, `findLowestLoanQuote` (`:67-134`) and their four `logger`s | `LoanBrokerService.lookupCustomerCreditProfile`, `lookupBanks`, `lookupLoanQuote`, `findLowestLoanQuote`, each logging the original message at INFO |
| `cxf:jaxws-client` + `http:request method="POST" path="/*" responseTimeout="10000"` (`:71-74,106-109`) | `client/CreditAgencyClient`, `client/BankClient`: Spring WS `WebServiceTemplate` with Jakarta JAXB, posting to `<basePath>/*` as the original did, each request sent once (`config/WebServiceConfig`) |
| `cxf:jaxws-service` + `singleton-object` components `DefaultCreditAgency` and `Bank` (`:138-195`) | `controller/CreditAgencyEndpoint` and `controller/BankEndpoint` (`@Endpoint`, `@PayloadRoot`) over the ported `service/DefaultCreditAgency` and `service/Bank`; each bank created once in `BankEndpoint` with its `bankName` |
| `catch-exception-strategy` with `set-payload "Error processing loan request"` (`:62-64`) | `exception/LoanBrokerExceptionHandler`, `@ControllerAdvice(assignableTypes = LoanBrokerController.class)` at `@Order(0)` |
| seven `http:listener-config` ports (`:4,7-12`) | SC-10 `config/ListenerPorts` (extra Undertow listeners from `listeners.additional`) + `config/PortRoutingFilter` (per-port routes and the no-listener 404) |
| CXF envelope style, SOAP faults and `?wsdl` publication | SC-11 `config/SoapEnvelopeStyleInterceptor`, `config/SoapFaultConfig`, `config/WsdlQueryPublisher`, `config/SoapListenerBoundaryAdapter`, wired by `config/WebServiceConfig` (a `WsConfigurer` without `@EnableWs`) |
| `javax.jws` interfaces `BankService` and `CreditAgencyService` | plain interfaces `service/BankService`, `service/CreditAgencyService`; the wire shape is carried by the `model/soap/` wrappers `GetLoanQuote`, `GetLoanQuoteResponse`, `GetCreditProfile`, `GetCreditProfileResponse` (AAP 0.3.2) |
| `org.mule.example.loanbroker.model.*`, `message.*` | `model/CreditProfile`, `Customer`, `LoanQuote`, `CustomerQuoteRequest`, `LoanBrokerQuoteRequest`, ported unannotated (JAXB's default binding and element order) |
| `src/main/resources/messages/*.properties` (Mule `i18n` bundles) | copied unchanged; `config/LoanBrokerMessages` (`ResourceBundle`) carries their lookup, read by no flow |
| Platform default responses for Spring MVC | SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse`, ranked after `LoanBrokerExceptionHandler` |
| `mule-deploy.properties` (`config.resources=loanbroker-simple.xml`) | `ForeachProcessingAndChoiceRoutingApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (no keys) | `src/main/resources/application.yml` |
| `ForEachProcessingAndChoiceRoutingIT` (`FunctionalTestCase`, `MuleClient`) | `ForEachProcessingAndChoiceRoutingTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`) |

No JAX-WS or CXF type is used anywhere; the enforcer ban covers `javax.xml.ws`, `jakarta.xml.ws`, `javax.jws`, `jakarta.jws`, `javax.xml:jaxws-api`, `org.apache.cxf`, `com.sun.xml.ws` and `org.glassfish.metro`. `spring-boot-starter-web-services` declares `jakarta.xml.ws:jakarta.xml.ws-api`, which the pom excludes. `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11 while Spring Framework stays at 6.1.15 (AAP 0.6.2, `DECISIONS.md` section 2); the endpoint and client tests are the gate for that combination.

## Behaviours kept, plan-chosen and externalized

- Random values, as in the original: `DefaultCreditAgency` draws a credit score of 300 to 899 and a credit history of 1 to 19 per call, and each `Bank` draws three sorted rates in [0, 10) when it is created at start-up and keeps them for the application's lifetime. A credit score below 500 takes the basic (highest) rate, below 700 the gold rate, otherwise the platinum (lowest) rate. Loan-broker and SOAP responses therefore vary from run to run.
- Bank choice, copied verbatim (`loanbroker-simple.xml:81-91`): `amount >= 20000` → Banks 1 and 2; else `amount >= 10000 || amount <= 19999` → Banks 3 and 4, a condition that is always true; else Bank 5. The Bank 5 branch is **unreachable**: any amount below 10000 satisfies `<= 19999` (AAP 0.8.2). It is kept exactly as declared, and `LoanBrokerServiceTest` asserts that the boundary amounts −1, 0, 9999, 10000, 19999 and 20000 never reach Bank 5. The original README says an amount of 5000 reaches Bank 5 (`foreach-processing-and-choice-routing/README.md:53,140`); the flow actually sends it to Banks 3 and 4, and the conversion keeps the flow's behaviour (`DECISIONS.md` section 6.7). The Bank 5 service on 50080 still answers direct SOAP calls.
- The banks are asked one after another in list order. The lowest rate wins, and on equal rates the first bank asked wins, because a later quote replaces the current one only when its rate is strictly lower. Each SOAP call is sent once and never retried; any failure of the credit agency or of a bank ends the request with `Error processing loan request`.
- Logging: the four sub-flow loggers keep their original texts at INFO (`Credit profile: <profile>`, `Banks to contact: [<uris>]`, `LoanQuote from <uri>: <quote>`, `Lowest loan quote: <quote>`). The catch branch logs one ERROR line naming only the exception's type, throw site and cause types, never the request's values. The framework loggers that would write the applicant's name or ssn below INFO are pinned ([Configuration keys](#configuration-keys)).
- Published WSDLs: `wsdl/BankService.wsdl` and `wsdl/CreditAgencyService.wsdl` are authored from the JAX-WS default names CXF derived from each `serviceClass` and from JAXB's default (alphabetical) element order, so `CreditProfile` marshals `creditHistory` before `creditScore` (AAP 0.3.2, `DECISIONS.md` section 7). A Tier 2A capture of the original `?wsdl` replaces them where they differ, recorded in `DECISIONS.md` section 7, "Captured WSDL replacements".
- Set in code rather than in keys: `config/WebServiceConfig` sets the JVM system property `saaj.use.default.transformer.factory` to `true` at start-up unless it is already set, so SAAJ uses the JDK's transformer factory directly instead of running a service lookup over every jar for each message it parses or writes (the lookup finds the same factory, so no byte changes), and `-Dsaaj.use.default.transformer.factory=false` keeps the lookup.
- Plan defaults pending Tier 2A (the captured fixture governs each):
  - the loan-broker reply carries no `Content-Type` header, because the Mule listener set none for a `*/*` string payload;
  - only the query string is read, never a form body; a repeated query parameter uses its last value, as Mule's `http.query.params` did, and a parameter without `=` counts as missing;
  - the SOAP fault string for an unknown operation follows CXF's `Message part {ns}x was not recognized.  (Does it exist in service WSDL?)`.
- Externalized or stubbed: nothing. The original reaches no external system and holds no credential; the credit agency and the banks are the original's own mock services, carried in the same process.
- Not carried (AAP 0.2.2): `mule-project.xml` and `src/test/resources/log4j2-test.xml`.

## Build, run and test

Run from `java-conversions/foreach-processing-and-choice-routing-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listeners on 0.0.0.0:11081, 18080 and 10080 to 50080
java -jar target/foreach-processing-and-choice-routing-java-1.0.0-SNAPSHOT.jar
```

Then, in a browser or with curl:

```sh
curl 'http://localhost:11081/?name=Muley&amount=20000&term=48&ssn=1234'    # Banks 1 and 2
curl 'http://localhost:11081/?name=Muley&amount=5000&term=48&ssn=1234'     # Banks 3 and 4
curl 'http://localhost:10080/mule/TheBank1?wsdl'
curl 'http://localhost:18080/mule/TheCreditAgencyService?wsdl'
```

- Running the application needs ports 11081, 18080 and 10080 to 50080 free; override them as shown under Configuration keys. A port in use stops start-up with `Web server failed to start. Port <n> was already in use.`
- `mvn test` does not: the Spring tests run the main listener on a random port and draw distinct free ports for the six SOAP listeners in `@DynamicPropertySource` on every context load, so no test opens a fixed port. When another process takes a drawn port first, `support/PortInUseRetryingContextLoader` loads the context again on newly drawn ports, up to 5 attempts.
- Surefire ends a test JVM still running after 600 seconds (`forkedProcessTimeoutInSeconds`), so a hung test fails the build instead of never finishing.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it asserts |
|---|---|
| `ForEachProcessingAndChoiceRoutingTest` | the ported `ForEachProcessingAndChoiceRoutingIT` (`foreach-processing-and-choice-routing/src/test/java/org/mule/examples/ForEachProcessingAndChoiceRoutingIT.java:33-47`), end to end through the in-process SOAP services: `GET /?name=Muley&amount=20000&term=48&ssn=1234` with an empty body returns a non-empty payload containing `Bank` and `rate` |
| `controller/LoanBrokerControllerTest` | on the wire through ST-1, with both SOAP clients mocked: the lowest quote of Banks 1 and 2 and of Banks 3 and 4 (Bank 5 never asked), `Error: incomplete request`, the catch branch for a non-numeric parameter and for each client failure, every HTTP method (extension tokens `PROPFIND` and `MKCOL` included), CORS pre-flights answered exactly as plain `OPTIONS` requests with no `Access-Control-*` or `Vary` header (the flow on 11081, the no-listener 404 on each SOAP port), every path of 11081 including `/mule/**` and `/error`, query-string-only binding, no `Content-Type` on any reply, and the loan broker's absence from the SOAP listeners; nested `UnderRootTrace` and `UnderRootDebug`, unmocked under `logging.level.root=TRACE` and `DEBUG`: a quote, which writes the original logger lines, the catch branch for a malformed ssn, which writes its ERROR line, and the required-field `otherwise` each answer as the flow does, while neither the applicant's name nor ssn reaches the console |
| `controller/CreditAgencyEndpointTest` | on the wire: the `getCreditProfile` binding (`arg0`/`return`) in CXF's compact `soap:`/`ns2:` envelope, the published WSDL with its JAX-WS identities and the request URL as `soap:address`, the no-listener 404 for another listener's path, and the `soap:Client` and `soap:Server` faults for unreadable, unknown and failing requests |
| `controller/BankEndpointTest` | on the wire: the exact `getLoanQuote` response on each of the five banks (the random rate matched by pattern), each port answering as its own bank on its base path and on `/*`, the credit-score rate bands, `BankService.wsdl`, the no-listener 404 for another bank's path, and the faults for unreadable requests, unknown operations and a failing bank |
| `service/LoanBrokerServiceTest` | every reachable branch with mocked clients: the required-field choice and its `otherwise`, `Integer.parseInt` failures, both reachable bank `when`s with the original bank URIs, the boundary amounts that never select Bank 5, the lowest-quote rule, failure propagation, and the four original logger messages |
| `service/BankTest`, `service/DefaultCreditAgencyTest` | the ported mock services: rate ranges, ordering and score bands; credit score and history ranges, generator wiring and the ported `toString` |
| `client/CreditAgencyClientTest`, `client/BankClientTest` | the outbound wire contract over a mocked transport (target `<basePath>/*`, `arg0` shape, `return` parsing, faults surfacing unwrapped); an empty SOAP Body failing in the client with a `NullPointerException` and a response wrapper without `return` yielding `null`, over the mocked transport and over real HTTP against WireMock, with the loan flow's outcome for each through `LoanBrokerController` (the catch branch's `Error processing loan request`, or the lowest remaining quote); and the `loanbroker.response-timeout` read timeout against a peer that never answers |
| `config/WebServiceConfigTest` | the shared `WebServiceTemplate`: each request sent exactly once on a reset, end of stream or timeout, `Content-Length` framing, `SOAPAction: ""`, and replies, faults and HTTP errors read as expected |
| `config/WebServiceConfigSaajSwitchTest` | `saaj.use.default.transformer.factory` set to `true` when absent, at context refresh as well, and left unchanged when already `true` or `false`; the factory it selects is the one the JAXP service lookup finds |
| `exception/LoanBrokerExceptionHandlerTest` | the catch branch's precedence over SC-1 and its scoping to the loan broker, the 200 `Error processing loan request` reply without `Content-Type`, and the single ERROR line |
| `config/PortRoutingFilterTest` | SC-10: per-port routing, the byte-exact no-listener 404, forwarding to the named servlet, the additional listener registration, an empty TLS password named in the start-up failure; an additional port another socket holds failing server creation with `PortInUseException` before any XNIO worker exists; an unbindable host (`192.0.2.1`, a `.invalid` name) named with its port in an `IllegalStateException`; a server on port -1 neither probed nor given a worker; a port taken after the probe failing the start within 30 s instead of hanging it (`PortInUseException` with one additional listener, a `WebServerException` caused by a `BindException` with two); `destroy()` stopping the context's started server before the worker; the worker's I/O and worker threads from `server.undertow.threads.io` and `server.undertow.threads.worker` or Undertow's defaults |
| `support/PortInUseRetryingContextLoaderTest` | SC-10 test support: a context whose additional port another socket holds on the first attempt loads on a newly drawn port, with one WARN line per lost attempt, and that listener serves on its port; only a port in use on the cause chain (`PortInUseException`, a `BindException` saying `Address already in use`) loads again; a port in use is attempted 5 times and any other failure once, then rethrown |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest`, `config/WsdlQueryPublisherTest` | SC-11: CXF envelope bytes, `soap:Server`, `soap:Client` and `soap:MustUnderstand` faults inside dispatch, the boundary faults and 405, and `?wsdl`/`?xsd=` publication with rewritten addresses |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, body bytes and the bounded ERROR log line |
| `FixtureParityTest` | ST-2 fixture replay (section below); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed, registered in the root [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4). This project holds the reference implementations of SC-10 and SC-11: a change to them is made here first and then copied to every consumer.

| ID | Files in this project | Reference implementation | Other consumers |
|---|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`; test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` | every project with a Spring MVC controller |
| SC-10 | `config/ListenerPorts`, `config/PortRoutingFilter`; test `config/PortRoutingFilterTest`; test support `support/PortInUseRetryingContextLoader` with `support/PortInUseRetryingContextLoaderTest` | **this project** | `http-oauth-provider`, `oauth2-authorization-code-using-the-HTTP-connector`, `soap-webservice-security`, `service-orchestration-and-choice-routing`, `mule-component-bindings` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`; tests `config/SoapEnvelopeStyleInterceptorTest`, `config/WsdlQueryPublisherTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest` | **this project** | `soap-webservice-security`, `xml-only-soap-webservice`, `legacy-modernization`, `service-orchestration-and-choice-routing`; `proxying-a-soap-api` (`WsdlQueryPublisher` only) |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` | every project with an HTTP listener |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` | the 51 non-RAML projects |
| — | `config/PreflightDispatchConfig`; test `config/PreflightDispatchConfigTest` | `hello-world-java` | the 36 other HTTP projects that carry it, this one included; 37 projects carry it in all (`DECISIONS.md` section 9, verification points) |

This project passes no POST-only path to `SoapListenerBoundaryAdapter`, because every original listener accepts every method; `legacy-modernization` and `service-orchestration-and-choice-routing` pass theirs.

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds its scenario rows, the request envelopes `B1` to `B3`, the `state` every fixture seeds (the six `ports.*` keys pinned to 18080 and 10080 to 50080, so replaying captured fixtures needs those ports free) and the capture procedure. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-amount-20000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=20000&term=48&ssn=1234` | `UNVERIFIED-uncaptured` |
| `get-root-amount-15000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=15000&term=48&ssn=1234` | `UNVERIFIED-uncaptured` |
| `get-root-amount-5000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=5000&term=48&ssn=1234` | `UNVERIFIED-uncaptured` |
| `get-root-bank5-otherwise` | `http` | `unreachable` | none (no request invented) | `UNREACHABLE` |
| `get-root-incomplete-request` | `http` | `deterministic` | port `11081`: `GET /?name=Muley&amount=20000&term=48` | `UNVERIFIED-uncaptured` |
| `get-root-non-numeric-amount` | `http` | `deterministic` | port `11081`: `GET /?name=Muley&amount=abc&term=48&ssn=1234` | `UNVERIFIED-uncaptured` |
| `post-credit-agency-get-credit-profile` | `soap` | `nondeterministic` | port `18080`: `POST /mule/TheCreditAgencyService/*`, envelope `B1` | `UNVERIFIED-uncaptured` |
| `post-bank1-get-loan-quote` | `soap` | `nondeterministic` | port `10080`: `POST /mule/TheBank1/*`, envelope `B2` | `UNVERIFIED-uncaptured` |
| `post-bank2-get-loan-quote` | `soap` | `nondeterministic` | port `20080`: `POST /mule/TheBank2/*`, envelope `B2` | `UNVERIFIED-uncaptured` |
| `post-bank3-get-loan-quote` | `soap` | `nondeterministic` | port `30080`: `POST /mule/TheBank3/*`, envelope `B2` | `UNVERIFIED-uncaptured` |
| `post-bank4-get-loan-quote` | `soap` | `nondeterministic` | port `40080`: `POST /mule/TheBank4/*`, envelope `B2` | `UNVERIFIED-uncaptured` |
| `post-bank5-get-loan-quote` | `soap` | `nondeterministic` | port `50080`: `POST /mule/TheBank5/*`, envelope `B2` | `UNVERIFIED-uncaptured` |
| `get-credit-agency-wsdl` | `soap` | `deterministic` | port `18080`: `GET /mule/TheCreditAgencyService?wsdl` | `UNVERIFIED-uncaptured` |
| `get-bank1-wsdl` | `soap` | `deterministic` | port `10080`: `GET /mule/TheBank1?wsdl` | `UNVERIFIED-uncaptured` |
| `post-bank1-malformed-envelope` | `soap` | `deterministic` | port `10080`: `POST /mule/TheBank1/*`, envelope `B3` (truncated) | `UNVERIFIED-uncaptured` |
| `get-credit-agency-path-on-bank1-port` | `http` | `deterministic` | port `10080`: `GET /mule/TheCreditAgencyService` | `UNVERIFIED-uncaptured` |

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| foreach-processing-and-choice-routing-java | foreach-processing-and-choice-routing | General | PASS | PASS | 98.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 536 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 536 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 77 of 78 lines: `service/LoanBrokerService` 48 of 49, `service/Bank` 20 of 20, `service/DefaultCreditAgency` 9 of 9 (the project has no `mapper` package). Total project LINE coverage: 88.5% (1459 of 1648 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=15`, `failed=0`; `get-root-bank5-otherwise` is `UNREACHABLE` and counted in none.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback, and the label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7):

- `ForEachProcessingAndChoiceRoutingTest` carries the original committed assertion with its substring semantics: the response to `name=Muley&amount=20000&term=48&ssn=1234` contains `Bank` and `rate`.
- The controller, endpoint and service tests cover every reachable branch of AAP 0.7.5 on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the flow and of the platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: the incomplete request (`get-root-incomplete-request`), the catch branch (`get-root-non-numeric-amount`), the Banks 3 and 4 route (`get-root-amount-15000`, `get-root-amount-5000`), the SOAP endpoints called directly (`post-credit-agency-get-credit-profile`, `post-bank1-get-loan-quote` to `post-bank5-get-loan-quote`), `?wsdl` (`get-credit-agency-wsdl`, `get-bank1-wsdl`), the listener 404 (`get-credit-agency-path-on-bank1-port`) and the SOAP faults (`post-bank1-malformed-envelope`).
- Unverified scenarios, by name from `src/test/resources/fixtures/SCENARIOS.md`:
  - `UNVERIFIED — nondeterministic output`, because the original's credit profiles and bank rates are random: `get-root-amount-20000`, `get-root-amount-15000`, `get-root-amount-5000`, `post-credit-agency-get-credit-profile`, `post-bank1-get-loan-quote`, `post-bank2-get-loan-quote`, `post-bank3-get-loan-quote`, `post-bank4-get-loan-quote`, `post-bank5-get-loan-quote`. Once captured they are compared for structure only. The three `get-root-amount-*` fixtures also omit `upstream`, because the original calls its own mock services at a hard-coded host and hard-coded ports that no runtime property redirects to a recording proxy, so they report `UNVERIFIED-no-upstream`. They are escalated through `DECISIONS.md` as AAP 0.8.8 sets out.
  - Unverified until captured, then compared byte for byte: `get-root-incomplete-request`, `get-root-non-numeric-amount`, `get-credit-agency-wsdl`, `get-bank1-wsdl`, `post-bank1-malformed-envelope`, `get-credit-agency-path-on-bank1-port`.
  - `get-root-bank5-otherwise` is `unreachable`, not unverified. Proof: the second `when` of `lookupBanks` holds for every amount, because any amount below 10000 satisfies `amount <= 19999` and any other satisfies `amount >= 10000`, so no amount reaches the `otherwise`. It has no fixture and is excluded from capture and from the branch-coverage claim (AAP 0.8.2).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `foreach-processing-and-choice-routing/`.
