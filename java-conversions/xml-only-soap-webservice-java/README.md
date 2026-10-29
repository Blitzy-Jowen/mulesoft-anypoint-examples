# xml-only-soap-webservice-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example **XML-only SOAP Web Service**, [`xml-only-soap-webservice`](../../xml-only-soap-webservice/) (category SOAP/WSDL-based service). The original folder `xml-only-soap-webservice/` stays unchanged beside this project and is the Tier 2A capture source. The listeners run on Undertow. The three SOAP services and the two internal SOAP calls use Spring WS 4.1.4 over SAAJ with DOM payloads. No JAX-WS, CXF or DataWeave artefact is on the classpath, and the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if a JAX-WS or CXF artefact arrives, even transitively (AAP 0.3.2).

## Original example

A hospital exposes the SOAP Web service `AdmissionService` so that a patient's family doctor can pre-admit the patient. For a new patient the service creates an electronic health record (EHR) and an Episode on it. For an existing patient it locates the EHR and creates a new Episode (`xml-only-soap-webservice/README.md`). The flow `admitPatientService` (`xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:19-48`) orchestrates two internal SOAP services over HTTP: `PatientService` upserts the patient and `EHRService` creates the Episode. The flow then answers with the Episode and a bill estimate. Both services are mock flows of the same application (`xml-only-soap-webservice/src/main/app/mocks.xml`). Each chooses its answer by the local name of the SOAP Body's root element, which is the example's content-based routing. `mule-deploy.properties` loads both flow files (`config.resources=mocks.xml,Hospital_Admissions_SOA.xml`), so all three services run in one process, as they do here.

The original performs all of this "using only XML – no Java" (`xml-only-soap-webservice/README.md:4`): three `cxf:proxy-service` listeners, `flow-ref`s, two `choice` routers and seven `%dw 1.0` scripts. The conversion reimplements the flows in `service/` and the seven scripts by hand in `mapper/`. It has no DataWeave runtime and no script translator.

## Endpoints and triggers

The three listeners share one listener configuration, as in the original (`Hospital_Admissions_SOA.xml:16`): host `0.0.0.0`, port `http.port`, default `8081`. No `http:listener` declares `allowedMethods`, so each listener accepts every method, `HEAD`, `OPTIONS` and `TRACE` included. Requests are dispatched by listener path, not by the Body's root element (`config/WebServiceConfig`). A CXF proxy service accepted any Body root, and the mocks' `otherwise` branches answer any operation. The project has no non-HTTP trigger.

| Path | Original flow and listener | Implemented by | Behaviour |
|---|---|---|---|
| `/AdmissionService` | flow `admitPatientService`: `http:listener path="AdmissionService"` and `cxf:proxy-service` service `AdmissionService`, port `AdmissionPort`, namespace `http://www.mule-health.com/SOA/service/admission/1.0`, `payload="envelope"` (`Hospital_Admissions_SOA.xml:19-21`) | `controller/AdmissionServiceEndpoint` → `service/AdmissionOrchestrationService` | 1. `upsertPatient`, carrying the request's `Subject`, is POSTed to `/PatientService` (`client/PatientServiceClient`). 2. `createEpisode`, carrying the returned `PatientId`, is POSTed to `/EHRService` (`client/EhrServiceClient`). 3. `admitSubjectResponse` is built from the returned Episode. `episodeId`, `PatientId`, `admission` and `care` are copied. `startDate` is the upstream `startDate` as a date, written only when present. `endDate` is the upstream `endDate` as a date plus 5 days, written only when present. A fixed `Bill` follows: `costPerNight` `100`, `initialStateEstimate` `5`, `runningTotal` `500`, `status` `ADMITTED`. |
| `/PatientService` | mock flow `PatientService`: `http:listener path="PatientService"` and `cxf:proxy-service` service `PatientService`, port `PatientPort`, namespace `http://www.mule-health.com/SOA/service/patient/1.0`, `payload="body"` (`mocks.xml:4-6`) | `controller/PatientServiceMockEndpoint` → `service/PatientMockService` | A Body root with local name `upsertPatient` → `upsertPatientResponse` with a new `PatientId`, the current time. Any other operation, an unknown root or an empty Body → `getPatientResponse`: `Patient` Donald Duck, `dateOfBirth` `1930-01-01`, `gender` `Male`, `nationality` `USA`, `address1` `DisneyLand`, with `patientId` and `nationalId` the current time. Neither branch reads the request further. |
| `/EHRService` | mock flow `EHRService`: `http:listener path="EHRService"` and `cxf:proxy-service` service `EHRService`, port `EHRPort`, namespace `http://www.mule-health.com/SOA/service/ehr/1.0`, `payload="body"` (`mocks.xml:45-47`) | `controller/EhrServiceMockEndpoint` → `service/EhrMockService` | A Body root with local name `createEpisode` → `createEpisodeResponse`: an `Episode` with `episodeId` the current time, the request's `PatientId`, `admission` `Elective`, `startDate` and `endDate` the current time, `care` `Private`. Any other operation (`findEpisodes` among them), an unknown root or an empty Body → `findEpisodesResponse`, whose `Episode` echoes the request's `PatientId`. |

- Every SOAP answer is a SOAP 1.1 envelope, `Content-Type: text/xml;charset=utf-8`, in CXF's form: `soap:` envelope prefix, no empty `Header`, no XML declaration (SC-11 `config/SoapEnvelopeStyleInterceptor`). The Body content keeps the DataWeave XML writer's layout of SC-4 `mapper/DwXmlWriter`: tab indentation and LF line ends, with `ns0` for `http://www.mule-health.com/SOA/message/1.0` and `ns1` for `http://www.mule-health.com/SOA/model/1.0`.
- The internal calls go to `http://<http.request.host>:<http.request.port>/PatientService` and `/EHRService`, which is this same application, as the original's `HTTP_Request_Configuration` targeted its own listener (`Hospital_Admissions_SOA.xml:17`). Each call is sent once. A timeout, refused connection or reset is never retried, and nothing is caught on the way, because the original sub-flows declare no exception strategy.

### Metadata (`?wsdl` and `?xsd=`)

Each listener publishes the WSDL its proxy service names as `wsdlLocation`, as CXF did, through SC-11 `config/WsdlQueryPublisher`, registered by `config/WebServiceConfig`. A reply is `200 OK` with `Content-Type: text/xml;charset=UTF-8`. Its `soap:address` `location` is rewritten to the request URL, and every `schemaLocation` is rewritten, transitively, to a `?xsd=` URL on the same listener.

| Request | Serves |
|---|---|
| `GET /AdmissionService?wsdl` | `service/AdmissionService.wsdl` |
| `GET /AdmissionService?xsd=service/xsd/SOA-Message-1.0.xsd`, `GET /AdmissionService?xsd=SOA-Model-1.0.xsd` | `service/xsd/SOA-Message-1.0.xsd`, `service/xsd/SOA-Model-1.0.xsd` |
| `GET /PatientService?wsdl`, `GET /EHRService?wsdl` | `service/PatientService.wsdl`, `service/EHRService.wsdl` |
| `GET /PatientService?xsd=xsd/SOA-Message-1.0.xsd`, `GET /PatientService?xsd=SOA-Model-1.0.xsd`, and the same two queries on `/EHRService` | `service/xsd/SOA-Message-1.0.xsd`, `service/xsd/SOA-Model-1.0.xsd` |

The `?xsd=` value is the `schemaLocation` the referring file writes. `AdmissionService.wsdl` imports `service/xsd/SOA-Message-1.0.xsd`, which resolves only from the classpath root. The two mock WSDLs import `xsd/SOA-Message-1.0.xsd` relative to themselves, and the message schema imports `SOA-Model-1.0.xsd` beside it.

### Listener defaults and faults (AAP 0.4.7)

| Case | Response | Enforced by |
|---|---|---|
| A path no listener serves, with any method. A sub-path (`/AdmissionService/x`) and a trailing slash (`/AdmissionService/`) are other paths. | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` | `config/WebServiceConfig` `ListenerPathFilter` |
| A body that cannot be read as a SOAP 1.1 envelope: malformed XML, an empty body, a body nested deeper than 100 elements (CXF's default `maxElementDepth`), a root other than `soap:Envelope`, or a body declaring a DOCTYPE. A non-POST request without a metadata query is read as a message, so a plain `GET /AdmissionService` lands here with its empty body. | HTTP 500, SOAP 1.1 fault, `faultcode` `soap:Client`, `faultstring` the parser's message, for example `Premature end of file.` for an empty body | SC-11 `config/SoapListenerBoundaryAdapter` |
| A request whose `Content-Type` SAAJ rejects for SOAP 1.1 (anything other than `text/xml`) | HTTP 500, `soap:Client`, for example `Invalid Content-Type:application/json. Is this an error message instead of a SOAP response?` | SC-11 `config/SoapListenerBoundaryAdapter` |
| An exception during processing, for example a mock that cannot be reached or answers with a fault, or an upstream episode date that cannot be read as a date | HTTP 500, `soap:Server`, `faultstring` the exception message, for example `I/O error: Connection refused`; CXF's `Fault occurred while processing.` when the exception carries none, as a `NullPointerException` the JVM raised counts | SC-11 `config/SoapFaultConfig` |
| A `mustUnderstand="1"` header block that nothing on the listener understands, for example `<x:Sec xmlns:x="urn:x" soapenv:mustUnderstand="1"/>` | Not checked: the request is processed and answered as it would be without the block, never with a `soap:MustUnderstand` fault. Every listener was a `cxf:proxy-service`, and Mule 3.8's `ProxyServiceMessageProcessorBuilder.configureServer` removes CXF's `MustUnderstandInterceptor` from every proxy service it builds | SC-11 `config/SoapFaultConfig` (`CxfStyleSoapMessageDispatcher` in its unchecked mode, the `messageReceiver` bean of `config/WebServiceConfig`) |

- No listener restricts methods, so the method 405 of AAP 0.4.7 cannot occur. `SoapListenerBoundaryAdapter` receives an empty POST-only path list.
- Each fault uses the CXF envelope form of the success responses.

### Sample exchanges

`src/test/resources/message.xml` is a byte copy of the original test's request, the `admitSubject` of soapUI's "Request 3". With the application on its default port, run from this folder (the `now` values, the dates and `Date` vary; this run used a JVM default zone of UTC):

```console
$ curl -i -X POST -H 'Content-Type: text/xml;charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:8081/AdmissionService
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/xml;charset=utf-8
Content-Length: 760
Date: <current date>

<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns0:admitSubjectResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
	<ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
		<episodeId>2026-10-28T06:29:32.800Z</episodeId>
		<ns1:PatientId>2026-10-28T06:29:32.774Z</ns1:PatientId>
		<admission>Elective</admission>
		<startDate>2026-10-28</startDate>
		<endDate>2026-11-02</endDate>
		<care>Private</care>
	</ns1:Episode>
	<ns1:Bill xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
		<costPerNight>100</costPerNight>
		<initialStateEstimate>5</initialStateEstimate>
		<runningTotal>500</runningTotal>
		<status>ADMITTED</status>
	</ns1:Bill>
</ns0:admitSubjectResponse></soap:Body></soap:Envelope>

$ curl -i http://localhost:8081/unknown
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 34
Date: <current date>

No listener for endpoint: /unknown

$ curl -i http://localhost:8081/AdmissionService
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: text/xml;charset=utf-8
Content-Length: 217
Date: <current date>

<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault><faultcode>soap:Client</faultcode><faultstring>Premature end of file.</faultstring></soap:Fault></soap:Body></soap:Envelope>
```

The carried `src/main/resources/Hospital-Admission-Example-soapui-project.xml`, a byte copy of the original, imports into soapUI as steps 5 to 8 of the original README describe: File > Import Project, expand the Hospital Admission Example project, open Request 3 and submit it. Its endpoint is `http://localhost:8081/AdmissionService` and its interface definition `http://localhost:8081/AdmissionService?wsdl`.

## Configuration keys

Every key of `src/main/resources/application.yml`. The original reads one placeholder, `http.port`, and its `mule-app.properties` defines no key.

The Type column gives the Java type each value binds to, taken from the code or the Spring Boot property that reads it.

| Key | Type | Default | Source in the original | Purpose |
|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port=8081` in `xml-only-soap-webservice/src/main/resources/config.dev.properties:1`, read by both `HTTP_Listener_Configuration` and `HTTP_Request_Configuration` (`Hospital_Admissions_SOA.xml:16-17`) | the listener port; `server.port` and, until the server is bound, `http.request.port` resolve from it |
| `http.request.host` | `String` | `localhost` | `HTTP_Request_Configuration host="0.0.0.0"` (`Hospital_Admissions_SOA.xml:17`) | host of the two internal SOAP calls. As a destination, `0.0.0.0` means "this host", but not every OS accepts it as one; `localhost` reaches the same local listener everywhere. |
| `http.request.port` | `String` (a port number, read with `Environment.getRequiredProperty` on every call) | `${local.server.port:${http.port}}` | `HTTP_Request_Configuration port="${http.port}"` (`Hospital_Admissions_SOA.xml:17`) | port of the two internal calls, read at call time: the port Undertow bound once it has started, so a `RANDOM_PORT` test calls its own listener, and `http.port` before that. The client tests point `http.request.host` and `http.request.port` at WireMock. |
| `http.request.response-timeout` | `long` (milliseconds) | `10000` (milliseconds) | none declared, so Mule's default `http:request` response timeout | connect and read timeout of the shared `WebServiceTemplate`; it must be positive, because zero would wait forever |
| `server.port` | `Integer` | `${http.port:8081}` | `HTTP_Listener_Configuration port="${http.port}"` (`Hospital_Admissions_SOA.xml:16`) | set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | `HTTP_Listener_Configuration host="0.0.0.0"` (`Hospital_Admissions_SOA.xml:16`) | bind host of the listener |
| `spring.application.name` | `String` | `xml-only-soap-webservice-java` | none | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | no static-resource handler answers any path |
| `spring.webservices.servlet.init` | `Map<String, String>` (servlet init-parameter name to value) | `dispatchOptionsRequest: "true"`, `dispatchTraceRequest: "true"` (servlet init-params, so strings) | no listener declares `allowedMethods` (`Hospital_Admissions_SOA.xml:20`, `mocks.xml:5,46`) | `OPTIONS` and `TRACE` reach `SoapListenerBoundaryAdapter` like every other method |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | no Boot `/error` endpoint; `WebServiceConfig.ListenerPathFilter` alone owns the no-listener 404, `/error` included |
| `logging.level.org.springframework.ws.client.MessageTracing` | `LogLevel` | `INFO` | none: `Hospital_Admissions_SOA.xml` and `mocks.xml` declare no logger | its `.sent`/`.received` `TRACE` write every SOAP envelope the `PatientService` and `EHRService` clients exchange: `upsertPatient` carries the `Subject` |
| `logging.level.org.springframework.ws.server.MessageTracing` | `LogLevel` | `INFO` | none | the same envelopes as `AdmissionService` and the two mocks receive and answer them |

- `spring.webservices.path` is not set and stays at Boot's default: `WebServicesProperties` rejects `/` ("Path must have length greater than 1"), so the application would not start. `WebServiceConfig` maps `messageDispatcherServlet` to exactly `/AdmissionService`, `/PatientService` and `/EHRService`, whatever its initial mapping.
- The two `logging.level` keys keep the patient's `Subject` (`nationalId`, names, address, `dateOfBirth`) out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`. The original had no logger, so it logged none of it. The nested `UnderRootTrace` run of `controller/AdmissionServiceEndpointTest` asserts this.
- There are no secrets, credentials, hosts of external systems or tenant identifiers, so the configuration has no TODO placeholder. The project has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Original-to-new key map: `config.dev.properties` `http.port` → `application.yml` `http.port`, the same name. The original loaded `config.dev.properties` through a Spring `PropertyPlaceholderConfigurer` (`Hospital_Admissions_SOA.xml:4-8`); its one key is folded into `application.yml`, so the file is not carried. `mule-app.properties` holds comments only. `mule-deploy.properties` decided which flow files were converted: both.
- Override the port on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`). The internal calls follow the bound port.

  ```sh
  java -jar target/xml-only-soap-webservice-java-1.0.0-SNAPSHOT.jar --http.port=9090
  HTTP_PORT=9090 mvn spring-boot:run
  ```

## Mule-to-Java mapping

| Mule construct (`Hospital_Admissions_SOA.xml`, `mocks.xml`) | Java construct (`com.mulesoft.examples.xml_only_soap_webservice`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`Hospital_Admissions_SOA.xml:16`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| three `http:listener`s, each in front of a `cxf:proxy-service` (`Hospital_Admissions_SOA.xml:20-21`, `mocks.xml:5-6,46-47`) | Spring WS `MessageDispatcherServlet` mapped to exactly the three listener paths. `WebServiceConfig.ListenerPathEndpointMapping` hands each path to the `handle(Element)` method of its DOM `@Endpoint`: `controller/AdmissionServiceEndpoint`, `controller/PatientServiceMockEndpoint`, `controller/EhrServiceMockEndpoint`. There is no `@PayloadRoot` mapping, because a CXF proxy accepted any Body root and the mocks' `otherwise` branches accept any operation. |
| `flow-ref upsertPatient`, `flow-ref createEpisode` and the sub-flows `upsertPatient`, `createEpisode` (`Hospital_Admissions_SOA.xml:23-24,50-64,71-83`) | `AdmissionOrchestrationService.admit` and its methods `upsertPatient` and `createEpisode`, run in the original order |
| sub-flows `invokePatientService` and `invokeEHRService`: `cxf:proxy-client payload="body"` + `http:request method="POST"` (`Hospital_Admissions_SOA.xml:66-70,85-88`) | `client/PatientServiceClient` and `client/EhrServiceClient` over the shared `WebServiceTemplate` of `WebServiceConfig`. The payload becomes the only Body child, and the Body child of the answer is returned. |
| `set-variable operation = #[xpath('fn:local-name(/*)')]` + `choice` (`mocks.xml:7-43,48-83`) | `PatientMockService.handle` and `EhrMockService.handle`: the local name of the Body root, compared case-sensitively with `upsertPatient` or `createEpisode` in an `if`/`else` |
| seven `%dw 1.0` scripts (AAP 0.5.8) | hand-written mappers writing XML through SC-4 `mapper/DwXmlWriter`, one owner per script (table below) |
| DataWeave `now` | the `java.time.Clock` bean of `config/ClockConfig`, `Clock.systemDefaultZone()`; tests substitute a fixed clock |
| `spring:bean` `PropertyPlaceholderConfigurer` over `classpath:config.dev.properties` (`Hospital_Admissions_SOA.xml:4-8`) | `src/main/resources/application.yml` |
| `mulexml:namespace-manager` (`Hospital_Admissions_SOA.xml:10-14`) | not carried: the only XPath, `fn:local-name(/*)`, uses no prefix, and the mappers name the two namespaces themselves |
| CXF envelope style, SOAP faults and `?wsdl` publication | SC-11 `config/SoapEnvelopeStyleInterceptor`, `config/SoapFaultConfig`, `config/WsdlQueryPublisher`, `config/SoapListenerBoundaryAdapter`, wired by `config/WebServiceConfig`, a `WsConfigurer` without `@EnableWs` |
| `mule-deploy.properties` (`config.resources=mocks.xml,Hospital_Admissions_SOA.xml`) | `XmlOnlySoapWebserviceApplication` (`@SpringBootApplication`) |
| `XMLWithSoapIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `XMLWithSoapTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

| Script (line of its `%dw 1.0` header) | Output | Owner |
|---|---|---|
| `Hospital_Admissions_SOA.xml:26` | `admitSubjectResponse` | `mapper/AdmitSubjectResponseMapper` |
| `Hospital_Admissions_SOA.xml:53` | `upsertPatient` | `mapper/UpsertPatientRequestMapper` |
| `Hospital_Admissions_SOA.xml:73` | `createEpisode` | `mapper/CreateEpisodeRequestMapper` |
| `mocks.xml:12`, `mocks.xml:24` | `upsertPatientResponse`, `getPatientResponse` | `mapper/PatientMockResponseMapper` |
| `mocks.xml:52`, `mocks.xml:71` | `createEpisodeResponse`, `findEpisodesResponse` | `mapper/EhrMockResponseMapper` |

`spring-boot-starter-web-services` declares `jakarta.xml.ws:jakarta.xml.ws-api`, which the pom excludes, so the enforcer ban on `javax.xml.ws`, `jakarta.xml.ws`, `javax.jws`, `jakarta.jws`, `javax.xml:jaxws-api`, `org.apache.cxf`, `com.sun.xml.ws` and `org.glassfish.metro` holds. `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11 while Spring Framework stays at 6.1.15 (AAP 0.6.2, `DECISIONS.md` section 2). The endpoint and client tests are the gate for that combination.

## Behaviours kept, plan-chosen and externalized

The points below keep the original's behaviour; none changes it. Each point that ends in "unverified" is a Tier 2A verification point: where a captured fixture of the original shows different bytes, the fixture governs and the code is changed to match.

- `now` values (`PatientId`, `patientId`, `nationalId`, `episodeId`, `startDate`, `endDate`) are written as ISO-8601 date-times with millisecond precision and the JVM default zone's offset, `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` (`Z` for UTC). Each response reads the clock once, so `patientId` and `nationalId` carry the same instant, as one evaluation of the script did. The exact text DataWeave 1.0 wrote for `now` and `now as :string` is unverified.
- The EHR mock writes `startDate` and `endDate` as date-times, and the admission script coerces them `as :date` (`Hospital_Admissions_SOA.xml:36-37`). The conversion takes the calendar date of the value in its own offset or zone, accepting an offset, zoned or local date-time or a plain ISO date, and writes `yyyy-MM-dd`. `endDate` is the upstream `endDate` plus 5 days, never derived from `startDate`. A value none of these forms reads fails with a `soap:Server` fault. The original test passed, so DataWeave accepted the date-time; its exact coercion is unverified.
- The script keys the bill element `"Bill "`, with a trailing space (`Hospital_Admissions_SOA.xml:40`). XML treats whitespace before `>` as insignificant, so the caller receives `ns1:Bill`, as the original's `reply.xml` fragment shows. The exact bytes of that start tag are unverified.
- The script writes `initialStateEstimate` and `status` `ADMITTED`, although the `billType` of `SOA-Model-1.0.xsd` declares `initialStayEstimate` and the status values `Pending` and `Complete`. The script governs: neither the original nor the conversion validates against the schema. `src/test/resources/reply.xml` is a stale fragment, which no original or converted test asserts, carried as-is.
- The original README says the AdmissionService proxy works on the SOAP body. Its flow actually declares `payload="envelope"` and selects `payload.Envelope.Body.admitSubject` (`Hospital_Admissions_SOA.xml:21,59`), which is the node `AdmissionServiceEndpoint` receives as its payload.
- `?wsdl` serves the carried files with addresses and schema locations rewritten, re-serialized with an XML declaration. CXF re-serialized them in its own form: the soapUI project's definition cache records it, with the keys `?xsd=xsd/SOA-Message-1.0.xsd` and `?xsd=SOA-Model-1.0.xsd` on `/AdmissionService`, where this project publishes `?xsd=service/xsd/SOA-Message-1.0.xsd`, the location the carried `AdmissionService.wsdl` writes. Byte equality with the original's `?wsdl` is unverified.
- Requests must carry an XML content type (`text/xml`) for SAAJ; any other is answered with the `soap:Client` fault above. Whether the original CXF proxy accepted other content types is unverified (`DECISIONS.md` section 9, verification points).
- SOAP responses and faults carry neither the `Accept` nor the `SOAPAction` request header SAAJ keeps on every message, as CXF sent neither: SC-11 `SoapListenerBoundaryAdapter` drops both. Characters outside the Basic Multilingual Plane are written as raw UTF-8, as CXF wrote them, in the responses and in the internal `upsertPatient` and `createEpisode` calls (SC-11 `SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory`), where SAAJ alone writes numeric character references such as `&#128512;` (`DECISIONS.md` section 9, verification points).
- Set in code rather than in keys: `config/WebServiceConfig` sets the JVM system property `saaj.use.default.transformer.factory` to `true` at start-up unless it is already set, so SAAJ uses the JDK's transformer factory directly instead of running a service lookup over every jar for each message it parses or writes (the lookup finds the same factory, so no byte changes), and `-Dsaaj.use.default.transformer.factory=false` keeps the lookup. The property is JVM-wide, so it covers the three listeners and the internal `upsertPatient` and `createEpisode` calls alike.
- The internal calls carry no `SOAPAction` beyond the `WebServiceTemplate` default and no extra header, as the original sent none; the WSDLs' malformed `http://http://...` actions are never sent.
- Externalized or stubbed: nothing. The original reaches no external system and holds no credential. `PatientService` and `EHRService` are the original's own mock services, carried in the same process.
- Not carried (AAP 0.2.2): `config.dev.properties` (folded into `application.yml`), `mule-project.xml`, `catalog/**`, `src/test/resources/log4j2-test.xml` and `src/test/resources/sample_data/**`. Carried byte for byte: `src/main/resources/service/**` (the three WSDLs and two schemas), `Hospital-Admission-Example-soapui-project.xml`, and the test inputs `message.xml` and `reply.xml`.

## Build, run and test

Run from `java-conversions/xml-only-soap-webservice-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listeners /AdmissionService, /PatientService and /EHRService on 0.0.0.0:8081
java -jar target/xml-only-soap-webservice-java-1.0.0-SNAPSHOT.jar
```

- Running the application needs port 8081 free; override it as shown under Configuration keys. `mvn test` does not: the Spring tests run on a random port and every client test's WireMock server on a dynamic port.
- There is no `mvn verify` live tier: the project has no failsafe plugin, no `*IT` class and no `application-it.yml`, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `XMLWithSoapTest` | the ported `XMLWithSoapIT` (`xml-only-soap-webservice/src/test/java/org/mule/examples/XMLWithSoapIT.java:54-65`), end to end through the in-process mocks with nothing mocked: `POST /AdmissionService` with `message.xml` returns a non-empty reply containing `<runningTotal>500</runningTotal>` and `<status>ADMITTED</status>` |
| `controller/AdmissionServiceEndpointTest` | on the wire through ST-1, the whole application on a random port with only the clock fixed: the exact `admitSubjectResponse` with its Episode and Bill in order, the same reply for a request carrying an unchecked `mustUnderstand="1"` header block, the `soap:Client` fault for unreadable requests, the no-listener 404 for unserved paths, and `?wsdl` with its rewritten address and schema closure; `beanNameIsTheExplicitComponentName`: the bean name `WebServiceConfig.ADMISSION_ENDPOINT` from an explicit `@Component`, with an empty `@Endpoint` value; nested `UnderRootTrace`, under `logging.level.root=TRACE` with the real clock: the original `message.xml` admission, through both mocks over HTTP, answers `admitSubjectResponse` while none of its `Subject` values reaches the console |
| `controller/PatientServiceMockEndpointTest` | on the wire: `upsertPatientResponse` with `PatientId` = now, the Donald Duck `getPatientResponse` for every other root and for an empty Body, the `soap:Client` fault for unreadable bodies and for a plain `GET`, the 404 for sub-paths, and `?wsdl`; `beanNameIsTheExplicitComponentName`: the bean name `WebServiceConfig.PATIENT_ENDPOINT` from an explicit `@Component`, with an empty `@Endpoint` value |
| `controller/EhrServiceMockEndpointTest` | on the wire: `createEpisodeResponse` echoing `PatientId` with the now dates, `findEpisodesResponse` for every other root, the `soap:Client` fault for requests that are not SOAP envelopes, the 404 for sub-paths, and `?wsdl` with its schema closure; `beanNameIsTheExplicitComponentName`: the bean name `WebServiceConfig.EHR_ENDPOINT` from an explicit `@Component`, with an empty `@Endpoint` value |
| `service/AdmissionOrchestrationServiceTest` | the flow's sequence with every collaborator mocked: the `upsertPatient` script, the PatientService call, the `createEpisode` script, the EHRService call and the response script, in that order and each once; empty and null payloads passed through; every failure propagated unchanged after one attempt, stopping the chain |
| `service/PatientMockServiceTest`, `service/EhrMockServiceTest` | each `choice`: the `when` branch for the exact local name only (case-sensitive, prefix ignored) and the `otherwise` branch for every other name and an empty Body, then one real-mapper answer per branch on a fixed clock; the EHR mapper receives the request unchanged |
| `mapper/AdmitSubjectResponseMapperTest` | the `admitSubjectResponse` script: `startDate` and `endDate` taken from differing upstream values, `endDate` = upstream `endDate` + 5 days, each date absent in turn, every accepted date form, the `Bill` values, and the failure for an unreadable date |
| `mapper/UpsertPatientRequestMapperTest`, `mapper/CreateEpisodeRequestMapperTest` | the `upsertPatient` and `createEpisode` scripts on `message.xml` and on small documents: selections by namespace and local name, copies and the null form, compared as exact documents |
| `mapper/PatientMockResponseMapperTest`, `mapper/EhrMockResponseMapperTest` | the four mock scripts on a fixed clock, compared as exact documents, including the `now` format and the echoed `PatientId` |
| `mapper/DwXmlWriterTest` | SC-4: the DataWeave 1.0 XML writer's bytes (declaration, tab layout, LF line ends, nothing after the root end tag) |
| `client/PatientServiceClientTest`, `client/EhrServiceClientTest` | WireMock in place of the mock listener: the Body root returned, `null` for an empty Body, a SOAP fault, an HTTP error, a refused connection and a reset each reaching the caller as its Spring WS exception after exactly one request |
| `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest`, `config/WsdlQueryPublisherTest` | SC-11: CXF envelope bytes, `soap:Server` and `soap:Client` faults inside dispatch, the component's checked `soap:MustUnderstand` fault and the unchecked proxy-service receiver this project registers, which lets a `mustUnderstand` block through to the endpoint, the boundary faults and 405, and `?wsdl`/`?xsd=` publication with rewritten addresses, the last also over this project's own three registrations |
| `config/WebServiceConfigTest` | `saaj.use.default.transformer.factory` set to `true` when absent, at context refresh as well, and left unchanged when already `true` or `false`; the factory it selects is the one the JAXP service lookup finds |
| `FixtureParityTest` | ST-2 fixture replay (section below); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed, registered in the root [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4). A change to one is made in the reference implementation first and then copied here.

| ID | Files in this project | Reference implementation |
|---|---|---|
| SC-4 | `mapper/DwXmlWriter`; test `mapper/DwXmlWriterTest` | `web-service-consumer-java` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`; tests `config/SoapEnvelopeStyleInterceptorTest`, `config/WsdlQueryPublisherTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest` | `foreach-processing-and-choice-routing-java` |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |

SC-1 is not carried: the project has no Spring MVC controller, only Spring WS endpoints, and `WebServiceConfig.ListenerPathFilter` answers the no-listener 404 itself. `config/WebServiceConfig` registers the three carried WSDLs with `WsdlQueryPublisher` and passes `SoapListenerBoundaryAdapter` no POST-only path, because every original listener accepts every method.

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds its scenario rows, the exact request bodies, the `state` every fixture seeds (`{"http.port": 0}`) and the capture procedure. Every fixture carries `"upstream": []`: the only outbound calls of the original reach its own `/PatientService` and `/EHRService` listeners, which the replay runs in-process. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

Every POST sends `Content-Type: text/xml;charset=UTF-8`.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-admission-service-admit-subject` | `soap` | `nondeterministic` | `POST /AdmissionService`, body = the bytes of `message.xml` | `UNVERIFIED-uncaptured` |
| `post-patient-service-upsert-patient` | `soap` | `nondeterministic` | `POST /PatientService`, `upsertPatient` envelope with the `Subject` of `message.xml` | `UNVERIFIED-uncaptured` |
| `post-patient-service-get-patient` | `soap` | `nondeterministic` | `POST /PatientService`, `getPatient` envelope, `PatientId` `1234` | `UNVERIFIED-uncaptured` |
| `post-ehr-service-create-episode` | `soap` | `nondeterministic` | `POST /EHRService`, `createEpisode` envelope, `PatientId` `1234` | `UNVERIFIED-uncaptured` |
| `post-ehr-service-find-episodes` | `soap` | `deterministic` | `POST /EHRService`, `findEpisodes` envelope, `PatientId` `1234` | `UNVERIFIED-uncaptured` |
| `get-admission-service-wsdl` | `soap` | `nondeterministic` | `GET /AdmissionService?wsdl` | `UNVERIFIED-uncaptured` |
| `get-patient-service-wsdl` | `soap` | `nondeterministic` | `GET /PatientService?wsdl` | `UNVERIFIED-uncaptured` |
| `get-ehr-service-wsdl` | `soap` | `nondeterministic` | `GET /EHRService?wsdl` | `UNVERIFIED-uncaptured` |
| `post-admission-service-malformed-envelope` | `soap` | `deterministic` | `POST /AdmissionService`, envelope truncated after the opening `soapenv:Body` tag | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| xml-only-soap-webservice-java | xml-only-soap-webservice | SOAP/WSDL-based service | PASS | PASS | 98.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9); the test counts and coverage figures below were re-measured on 2026-10-29 with `mvn -B clean install`, which runs the same surefire tests and JaCoCo check, on the tree whose `config/WebServiceConfig` sets SAAJ's `saaj.use.default.transformer.factory` switch:

- `mvn -B clean package`: `BUILD SUCCESS`; 620 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 620 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 700 of 713 lines. `service` 44 of 44: `AdmissionOrchestrationService` 14 of 14, `PatientMockService` 15 of 15, `EhrMockService` 15 of 15. `mapper` 656 of 669: `AdmitSubjectResponseMapper` 123 of 123, `DwXmlWriter` 226 of 226, `EhrMockResponseMapper` 102 of 104, `CreateEpisodeRequestMapper` 90 of 95, `UpsertPatientRequestMapper` 77 of 79, `PatientMockResponseMapper` 38 of 42 (nested and anonymous classes counted with their outer class). Total project LINE coverage: 91.9% (1519 of 1652 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=10`, `failed=0`, every scenario `UNVERIFIED-uncaptured`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback, and the label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7):

- `XMLWithSoapTest` carries the original committed assertions with their substring semantics: the reply to `message.xml` is non-empty and contains `<runningTotal>500</runningTotal>` and `<status>ADMITTED</status>`.
- The endpoint, service and mapper tests cover every reachable branch of AAP 0.7.2, on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the flows and of the platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: both mock `otherwise` branches (`post-patient-service-get-patient`, `post-ehr-service-find-episodes`), the mocks called directly (`post-patient-service-upsert-patient`, `post-ehr-service-create-episode`), the `startDate` and `endDate` absence cases (mapper tests only; no scenario, because the EHR mock always writes both dates), `?wsdl` (`get-admission-service-wsdl`, `get-patient-service-wsdl`, `get-ehr-service-wsdl`), the listener 404 (`get-unknown-path`) and the faults: `soap:Client` (`post-admission-service-malformed-envelope`) and `soap:Server` (no scenario, because no request makes the original's chain fail while its own mocks are up).
- Unverified scenarios, by name from `src/test/resources/fixtures/SCENARIOS.md`:
  - `UNVERIFIED — nondeterministic output`, because the mocks stamp the current time or the `?wsdl` bodies embed the listener URL: `post-admission-service-admit-subject`, `post-patient-service-upsert-patient`, `post-patient-service-get-patient`, `post-ehr-service-create-episode`, `get-admission-service-wsdl`, `get-patient-service-wsdl`, `get-ehr-service-wsdl`. Once captured they are compared for structure only, and they are escalated through `DECISIONS.md` as AAP 0.8.8 sets out. `config/ClockConfig` makes the unit tests deterministic, not the original's captures.
  - Unverified until captured, then compared byte for byte: `post-ehr-service-find-episodes`, `post-admission-service-malformed-envelope`, `get-unknown-path`.
- Without a reproducibility input of AAP 0.8.8, a complete capture can at most reach `PARITY: PARTIAL — Tier 2A fixtures: 3 verified, 7 unverified`, or `PARITY: FAIL — Tier 2A fixtures` when a captured deterministic scenario differs.

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `xml-only-soap-webservice/`.
