# Conversion decisions: Mule 3 examples → Spring Boot 3.2.12

This file records the decisions, version alignments and open states of converting the Mule EE 3.6–3.8 / DataWeave 1.0 examples in this repository into standalone Java 17 / Spring Boot 3.2.12 / Maven projects under `java-conversions/{folder}-java/`. It is written for the requester and for the person who later runs Tier 2A fixture capture. Evidence is cited as `path:line` against this checkout; "AAP n.n" refers to the section of the Agent Action Plan that settled the point. Values produced later (Tier 2A captures, unverified-scenario lists, Tier 2B results) are shown in their current state.

**Rules:** no user rules were provided for this project (`review_rules` returns "No user rules provided."; AAP 0.9), so none governs the conversion or this file.

Contents:

1. [Scope and counts](#1-scope-and-counts)
2. [Platform and version alignments](#2-platform-and-version-alignments)
3. [Conflict resolutions](#3-conflict-resolutions)
4. [Shared-component register](#4-shared-component-register)
5. [Carried platform-generated surfaces and legacy disposition](#5-carried-platform-generated-surfaces-and-legacy-disposition)
6. [Behaviour and contract decisions](#6-behaviour-and-contract-decisions)
7. [SOAP contracts authored where no WSDL exists](#7-soap-contracts-authored-where-no-wsdl-exists)
8. [Query hardening](#8-query-hardening)
9. [Verification tiers and Tier 2A escalation state](#9-verification-tiers-and-tier-2a-escalation-state)
10. [SAP follow-on (`sap-data-retrieval`)](#10-sap-follow-on-sap-data-retrieval)

---

## 1. Scope and counts

The conversion starts from HEAD `98ad548` ("Updated/Added CODEOWNERS with ECCN") of branch `3.8`. The checkout holds 56 top-level example folders; each has a `pom.xml` whose `artifactId` equals the folder name. The originals stay unchanged and remain the Tier 2A capture source.

| Count | Request | Found in checkout | Resolution |
|---|---|---|---|
| Total example folders | 57 | 56 | Every folder present is converted; no folder is invented |
| Delivered in this phase | 56 | 55 | All non-SAP folders, one project each under `java-conversions/{folder}-java/` |
| Third-party connector | 12 (11 delivered) | 12 (11 delivered) | Matches; `sap-data-retrieval` is the undelivered one |
| RAML-backed | 5 (4 delivered) | 5 (4 delivered) | Matches; `sap-data-retrieval` is the undelivered one |
| General | "all remaining" | 35 | 56 − 3 (RAML/APIkit) − 3 (SOAP) − 3 (JMS) − 12 (connector) = 35 |
| Non-RAML delivered (Tier 2A parity set) | 52 | 51 | 55 delivered − 4 RAML-backed delivered = 51 |

Category membership (AAP 0.2.1):

| Category | Folders |
|---|---|
| RAML/APIkit-driven REST API (3) | `rest-api-with-apikit`, `testing-apikit-with-munit`, `processing-orders-with-dataweave-and-APIkit` |
| SOAP/WSDL-based service (3) | `web-service-consumer`, `xml-only-soap-webservice`, `service-orchestration-and-choice-routing` |
| JMS/messaging-based flow (3) | `jms-message-rollback-and-redelivery`, `sending-json-data-to-a-jms-queue`, `using-transactional-scope-in-jms-to-database` |
| Third-party connector (12) | `salesforce-data-retrieval`, `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `authenticating-salesforce-using-oauth2`, `get-customer-list-from-netsuite`, `netsuite-data-retrieval` (RAML-backed), `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing`, `sap-data-retrieval` (RAML-backed, excluded), `adding-a-new-customer-to-workday-revenue-management`, `import-contacts-into-ms-dynamics`, `dataweave-with-flowreflookup` |
| General (35) | every other folder |

Evidence for the one-folder gap, which falls in the General bucket because every folder the request names is present:

- The root reactor `pom.xml:11-65` holds 53 `<module>` entries. Three are commented out: `mule-component-bindings` (`pom.xml:30`), `oauth2-client-credentials-using-the-HTTP-connector` (`pom.xml:54`) and `importing-a-csv-file-into-ms-sharepoint` (`pom.xml:59`).
- Three present folders are absent from the reactor: `netsuite-data-retrieval`, `http-multipart-request`, `http-oauth-provider`. The 53 entries plus these 3 account for all 56 folders, and no reactor entry names a missing example.
- Git history shows only removed or renamed folders, each superseded by a present folder:

| Former folder | Commits | Present folder |
|---|---|---|
| `datamapper-with-flowreflookup` | `5f21460` "renamed datamapper with flowref lookup"; `d8e7bf3` "SIT-1119 Updated dataweave-with-flowreflookup to actually use flow lookup"; `1a8cd97` "Deleted unnecessary files" removed the last leftovers (`src/main/app/mule-app.properties`, `src/main/app/mule-deploy.properties`) | `dataweave-with-flowreflookup` |
| `http-oauth-authorization-code-web` | `a69ac04` "renamed OAuth2 and Salesforce data retrieval examples" | `oauth2-authorization-code-using-the-HTTP-connector` |
| `munit_short_tutorial` | `953bae3` "Updated MUnit short Tutorial example dependencies." (folder renamed in that commit) | `munit-short-tutorial` |
| `upload-to-sftp-after-converting-json-to-xml` | `c1c5534` "renamed example upload-to-ftp-after-converting-json-to-xml" | `upload-to-ftp-after-converting-json-to-xml` |

Consequences:

- `SUMMARY.md` carries 56 rows, one per folder found; the `sap-data-retrieval` row reads `EXCLUDED — pending sapjco3.jar`.
- The success criterion "56 of 57 compile" is read as "every non-SAP folder present compiles": 55 projects.

Category notes:

- `dataweave-with-flowreflookup` is a connector example: its flow calls `sfdc:create` (`dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml`).
- `scatter-gather-flow-control` (`scatter-gather-flow-control/src/main/app/scatter-gather.xml:2`) and `legacy-modernization` (`legacy-modernization/src/main/app/FufillmentWebService.xml`) only declare Salesforce namespaces, with no connector element, so both are General.

---

## 2. Platform and version alignments

Builds and probes used Temurin JDK 17.0.21 and Apache Maven 3.9.9.

| Item | Decision | Reason and evidence |
|---|---|---|
| Parent and language | `org.springframework.boot:spring-boot-starter-parent` 3.2.12, `<java.version>17</java.version>` in all 55 projects | 3.2.12 is the final open-source 3.2.x patch (released 2024-11-21); the parent fixes every BOM-managed version |
| Version pinning | No version range, `LATEST` or `RELEASE`; every artefact outside the BOM and every plugin has an explicit `<version>` | Reproducible builds (AAP 0.6.1) |
| Plugins | `maven-compiler-plugin` 3.11.0; `maven-surefire-plugin` 3.1.2 (excludes `**/*IT.java`); `maven-failsafe-plugin` 3.1.2 (11 connector projects only); `jacoco-maven-plugin` 0.8.15 (`check` at `prepare-package`, LINE covered ratio ≥ 0.80 over `<package>/service/**` and `<package>/mapper/**`); `maven-enforcer-plugin` 3.4.1; `spring-boot-maven-plugin` 3.2.12; `org.codehaus.mojo:jaxb2-maven-plugin` 4.2.0 (Workday project only, `sourceType` WSDL) | AAP 0.6.3 |
| Spring WS | `<spring-ws.version>4.1.4</spring-ws.version>` overrides the BOM's 4.0.11 and moves `spring-ws-core`, `spring-ws-security`, `spring-ws-test` and `spring-xml` together (WSS4J 4.0.1). Set in the 7 Spring WS projects: `web-service-consumer`, `xml-only-soap-webservice`, `service-orchestration-and-choice-routing`, `foreach-processing-and-choice-routing`, `legacy-modernization`, `soap-webservice-security`, `adding-a-new-customer-to-workday-revenue-management` | The request pins 4.1.4 and its version wins. `proxying-a-soap-api` forwards raw envelopes over `RestClient` and uses no Spring WS |
| Spring Framework | Stays at the BOM's 6.1.15, although Spring WS 4.1.x is built against 6.2.x | The request's Boot 3.2.x line wins over a Framework upgrade. Probe: Boot 3.2.12 + Spring WS 4.1.4 on JDK 17.0.21 passed a `@PayloadRoot` endpoint behind a validating UsernameToken/Timestamp interceptor, called by a securing `WebServiceTemplate` (BUILD SUCCESS). Each SOAP project's endpoint and client tests are the gate |
| Spring WS bootstrapping | Projects configure Spring WS through a `WsConfigurer` bean and never annotate with `@EnableWs` | `@EnableWs` disables Boot's `WebServicesAutoConfiguration`, leaving no `MessageDispatcherServlet` (probe: 404) |
| `spring-boot-starter-web-services` | Declared with `jakarta.xml.ws:jakarta.xml.ws-api` excluded (e.g. `java-conversions/soap-webservice-security-java/pom.xml:78-93`) | The starter pulls in `jakarta.xml.ws-api` 4.0.2, which the JAX-WS ban rejects; Spring WS needs only SAAJ, and `saaj-impl` 3.0.4 supplies `jakarta.xml.soap-api` |
| OpenSAML repository | Projects using `spring-ws-security` declare the repository `shibboleth-releases` (`https://build.shibboleth.net/maven/releases/`): `soap-webservice-security`, `adding-a-new-customer-to-workday-revenue-management` | WSS4J 4.0.1 depends on OpenSAML 5.1.6, which is published only there |
| HTTP server | `spring-boot-starter-web` with `spring-boot-starter-tomcat` excluded and `spring-boot-starter-undertow` (Undertow 2.3.17.Final) added, in every HTTP-serving project | Tomcat 10.1 does not send custom reason phrases. Probe: setting the reason on the Undertow exchange from an `@ExceptionHandler` produced the raw status line `HTTP/1.1 400 Invalid input data` |
| Jackson | Boot's 2.15.4 is resolved in the 7 Salesforce projects although `force-wsc` 65.0.0 declares 2.18.4 (confirmed with `mvn dependency:tree`) | The BOM version wins; the Partner API calls use WSC's XML stack, and the Salesforce unit tests cover the paths used |
| `json-schema-validator` 2.2.14 | Kept with its transitive `com.sun.mail:mailapi` 1.6.2 and Guava 28.2-android in the four RAML projects | No conflict: none of the four uses mail; draft-03 support is required because the RAML schemas are draft-03 |
| Pins outside the BOM | `force-partner-api` 65.0.0 (request pin); `nimbus-jose-jwt` 9.37.3 (the version Spring Security 6.2.8 uses); `dev.snowdrop:narayana-spring-boot-starter` 3.2.0 (Narayana 7.0.2.Final; 3.2.0 targets Boot 3.2.8, 3.3.0 targets Boot 3.3.5); `com.ibm.mq:com.ibm.mq.jakarta.client` 9.3.5.0 (the client IBM's Boot 3.2-line starter 3.2.4 declares); `org.twitter4j:twitter4j-core` 4.0.7; `commons-net` 3.13.0; tests: `org.wiremock:wiremock-standalone` 3.13.2, `com.icegreen:greenmail-junit5` 2.1.14, `org.mockftpserver:MockFtpServer` 3.2.0, `de.bwaldvogel:mongo-java-server` 1.45.0, Qpid Broker-J 9.2.1, `net.sourceforge.htmlunit:htmlunit` 2.70.0 | `mongo-java-server` stays at 1.45.0 because 1.47.0 moves to Netty 4.2, which the Boot 3.2 BOM does not manage (AAP 0.6.1) |

Library choices dictated by the JAX-WS/CXF ban:

- Every `pom.xml` (all 55) carries a `maven-enforcer-plugin` `bannedDependencies` rule with transitive search on, excluding `javax.xml.ws:*`, `jakarta.xml.ws:*`, `javax.jws:*`, `jakarta.jws:*`, `javax.xml:jaxws-api`, `org.apache.cxf:*`, `com.sun.xml.ws:*` and `org.glassfish.metro:*`. Any of them entering transitively fails the build at `validate`.
- The originals' `javax.jws`/`javax.xml.ws` service interfaces are ported as plain Java interfaces; explicit Jakarta-JAXB wrapper classes carry their wire contract (section 7).
- Apache Chemistry OpenCMIS 1.1.0 client bindings declare a compile dependency on `org.apache.cxf:cxf-rt-frontend-jaxws`, so `document-integration-using-the-cmis-connector` speaks CMIS 1.1 AtomPub over `RestClient`.

Vendor API choices:

| Vendor | Original | Converted | Reason |
|---|---|---|---|
| NetSuite | SuiteTalk SOAP connector with email, password and role | REST web services + SuiteQL with OAuth 2.0 client credentials; the client assertion is a JWT signed PS256 with `kid` = certificate ID | Oracle's schedule: REST + OAuth 2.0 for new integrations from 2026.1, no new SOAP integrations from 2027.1, SOAP disabled in 2028.2 |
| Microsoft Dynamics 365 | `dynamicscrm` connector with username, password and service URL | Dataverse Web API v9.2: POST `contacts` with `firstname`, `lastname`, `emailaddress1`, `telephone1`, Entra ID client-credentials token (scope `<environment URL>/.default`) through Spring Security's OAuth2 client | The plan's vendor research (AAP 0.3.3) fixed the Dataverse Web API; it needs an Entra app registration and a Dataverse application user in the existing tenant, which the requester completes |
| Workday | `wd-connector` against `Revenue_Management` v25.0 (`adding-a-new-customer-to-workday-revenue-management/catalog/Workday__md__io__.xml`) | Spring WS `WebServiceTemplate` over classes generated by `jaxb2-maven-plugin` from the publicly downloadable v25.0 WSDL (1,524,923 bytes), operation `Put_Customer`; the live test also uses `Get_Customers` | v25.0 is the version the original targets; `Put_Customer` is replaced by `Submit_Customer` from v31.1. The v25.0 WSDL has no operation named `Submit_Customer`; its eight `Submit_Customer` text matches are the sibling `Submit_Customer_*` operations |
| Salesforce | `sfdc` connector | Salesforce WSC Partner API (`force-partner-api` 65.0.0) | Request pin; authentication in section 6.2 |

---

## 3. Conflict resolutions

Where the inputs disagreed, the plan resolved each conflict once (AAP 0.10.2). The approved connector failure modes (section 6.4) are the only exception to the request's "no new behaviour" directive.

| Conflict | Resolution | Where implemented |
|---|---|---|
| The request counts 57 folders / 56 delivered / 52 non-RAML; the checkout holds 56 / 55 / 51 | Convert every folder present; invent none | Section 1; `SUMMARY.md` (56 rows) |
| "One controller per RAML resource" versus `rest-api-with-apikit` mapping "directly to one `@RestController`" | The per-resource rule governs: three controllers (teams, positions, fixture); the other sentence is read as the pattern of one mapping method per flow mapping | `rest-api-with-apikit-java/.../controller/TeamsController`, `PositionsController`, `FixtureController` |
| "The four failure-mode responses" with three listed | The three listed; the re-authentication retry and its 502 count as one | SC-9 `UpstreamCallExecutor`, `UpstreamExceptionHandler` (section 6.4) |
| Fixtures captured "before writing the Java conversion" versus Tier 1 proceeding with the Mule prerequisites unconfirmed | Capture first for every project whose conversion starts after the prerequisites are available; otherwise convert on the fallback and capture afterwards, the fixtures then governing | Section 9; ST-2 `FixtureParityTest` |
| "Integration tests: one per REST endpoint" versus `*IT.java` reserved for live sandbox tests | Endpoint tests are the surefire `<Controller>Test` / `<Endpoint>Test` classes; `*IT` only for the 11 connector projects under failsafe | Every project's `src/test/java` |
| Credentials file "application-it.yml or it.properties" | `src/test/resources/application-it.yml`, git-ignored, with a committed `application-it.yml.example` | Root `.gitignore:65`; each connector project's `.gitignore` |
| Coverage "80% on service and mapper" versus "≥80% coverage on new code" | Enforced on `service` and `mapper` (JaCoCo `check`); total project coverage reported | Every `pom.xml` |
| Salesforce auth "username-password or JWT bearer … matching the original" versus `authenticating-salesforce-using-oauth2` using the authorization-code flow | The original mechanism governs: OAuth 2.0 authorization code there | `authenticating-salesforce-using-oauth2-java/.../service/SalesforceOAuthService` |
| Salesforce auth "OAuth2 username-password or JWT bearer" versus six originals that log in through the SOAP Partner API | The original protocol governs: SOAP `login` with reused credentials (section 6.2) | SC-15 `SalesforcePartnerClient` |
| Request key `sfdc.username` versus the originals' `sfdc.user` | The request's names govern; each README maps the original name | `application.yml` of the Salesforce projects (section 6.3) |
| `spring-ws-core` 4.1.4 described as compatible with Boot 3.2.x, while Spring WS 4.1 is built on Framework 6.2 | Keep Boot 3.2.12 / Framework 6.1.15 with the 4.1.4 override, verified by probe and endpoint tests | Section 2 |
| RAML and README declare 409 for `rest-api-with-apikit`; the flow maps no 409 | The RAML contract governs (section 6.1) | `rest-api-with-apikit-java/.../exception/LeagueExceptionHandler` |
| "Consistent error response shape" versus "same … error payload structure implied by the original flow" | Original-shaped errors are reproduced; the consistent `{ "message": ... }` shape is used only for platform defaults and the connector additions | SC-1, SC-3, SC-9 and the project handlers (section 6.5) |
| "JSON schemas under src/main/resources/schemas" versus RAML files under `src/main/api` in three examples | The actual per-example locations are read and carried | `rest-api-with-apikit-java/src/main/resources/schemas/**`; `src/main/resources/api/**` in the other RAML projects |
| Demo login and SOAP users hard-coded in originals versus "NEVER hardcode credentials" | Externalized to named keys with TODO values; unit tests supply the demo values through `application-test.yml` | Section 6.3 |
| "No original endpoint … missing" versus the APIkit console and RAML descriptor being platform-generated rather than RAML-declared | Carried, so no original endpoint is missing | SC-17 (section 5) |
| "MUST NOT alter business logic" versus injectable query concatenation | Bind parameters and literal escaping; same results for legitimate input | Section 8 |
| Approved connector additions versus `salesforce-data-retrieval`'s catch-all branch | The three additions take precedence; the catch-all `Invalid Salesforce query.` keeps every other failure | SC-9 `UpstreamExceptionHandler` at the highest `@Order`; `SalesforceRetrievalExceptionHandler` |
| Workday `Put_Customer` replaced by `Submit_Customer` in later WSDL versions | The v25.0 WSDL, as the original targets | `adding-a-new-customer-to-workday-revenue-management-java/src/main/resources/wsdl/Revenue_Management_v25.0.wsdl` |

---

## 4. Shared-component register

Projects share no runtime or build artefact. A behaviour that more than one project must carry out identically is implemented once, in its reference project, and copied into every other consumer with only the `package` line changed; its unit tests are copied the same way. Each component's dependencies are closed within its own row, so copying a row never needs a type the consumer lacks. A change to a shared component is made in the reference implementation and then copied to every consumer; a consumer never writes its own variant (AAP 0.4.4).

The reference implementation is the first consumer AAP 0.4.4 lists for the row; where a row names a group, it is the first member, in the folder order of AAP 0.2.1, that uses the class, which keeps the copy order acyclic. Paths are relative to `src/main/java/com/mulesoft/examples/<package>/` unless they start with `src/`; tests are relative to `src/test/java/com/mulesoft/examples/<package>/`. Project names omit the `-java` suffix.

| ID | Paths | Provides | Reference implementation | Consumers | Tests copied |
|---|---|---|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` | Generic Spring MVC error handling at the lowest `@Order`: no-listener 404, method 405, unhandled-exception 500 `{ "message": ... }`; references no project, connector or APIkit type | `rest-api-with-apikit-java` | The 41 projects with a Spring MVC listener: every HTTP-serving project except `xml-only-soap-webservice` (Spring WS endpoints only); `legacy-modernization` is included | `exception/GlobalExceptionHandlerTest` |
| SC-2 | `config/ReasonPhrase` | `ReasonPhrase.set(String)` writes a custom reason phrase on the current Undertow exchange | `implementing-a-choice-exception-strategy-java` | `implementing-a-choice-exception-strategy`, `exposing-a-restful-resource-using-the-HTTP-connector`, `http-multipart-request` | `config/ReasonPhraseTest` |
| SC-3 | `exception/ApiKitExceptionHandler`, `exception/ApiKitNotFoundException`, `config/JsonSchemaValidator` | The five APIkit mappings with exact bodies; the type thrown where the original threw APIkit's `NotFoundException`; draft-03 validation of request bodies against the copied RAML schemas; Bean Validation failures and missing required parameters → 400 | `rest-api-with-apikit-java` | `rest-api-with-apikit`, `testing-apikit-with-munit`, `processing-orders-with-dataweave-and-APIkit`, `netsuite-data-retrieval` | `exception/ApiKitExceptionHandlerTest`, `config/JsonSchemaValidatorTest` |
| SC-4 | `mapper/DwJsonWriter`, `DwCsvWriter` | DataWeave 1.0 JSON bytes (two-space indent, `"key": value`, `BigDecimal` with its scale); CSV with comma, header row and LF line ends including a trailing LF | `processing-orders-with-dataweave-and-APIkit-java` | `DwJsonWriter`: `processing-orders-with-dataweave-and-APIkit`, `web-service-consumer`, `service-orchestration-and-choice-routing`, `netsuite-data-retrieval`. `DwCsvWriter`: `processing-orders-with-dataweave-and-APIkit`, `legacy-modernization`, `mule-expression-language-basics`, `querying-a-db-and-attaching-results-to-an-email`, `scatter-gather-flow-control` | `mapper/DwJsonWriterTest`, `mapper/DwCsvWriterTest`, each with its class |
| SC-4 | `mapper/DwXmlWriter`, `DwXmlToJson` | XML with `<?xml version='1.0' encoding='UTF-8'?>` and tab indentation; XML-to-JSON with repeated elements as repeated keys | `web-service-consumer-java` | `DwXmlWriter`: `web-service-consumer`, `xml-only-soap-webservice`, `service-orchestration-and-choice-routing`, `salesforce-data-retrieval`, `get-customer-list-from-netsuite`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector`, `upload-to-ftp-after-converting-json-to-xml`. `DwXmlToJson`: `web-service-consumer`, `service-orchestration-and-choice-routing` | `mapper/DwXmlWriterTest`, `mapper/DwXmlToJsonTest`, each with its class |
| SC-4 | `mapper/DwCsvReader` | CSV reading with header, comma, `"` quoting and string values | `import-contacts-into-salesforce-java` | `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `dataweave-with-flowreflookup`, `import-contacts-into-ms-dynamics`, `importing-a-CSV-file-into-Mongo-DB`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector`, `sending-a-csv-file-through-email-using-smtp` | `mapper/DwCsvReaderTest` |
| SC-5 | `trigger/FileInboundPoller` | Mule 3.8 file-inbound semantics: poll at `pollingFrequency`, listing order, in-process mark, move to `moveToDirectory` or delete after any non-rollback outcome, unopenable file left for the next poll | `processing-orders-with-dataweave-and-APIkit-java` | `processing-orders-with-dataweave-and-APIkit`, `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `dataweave-with-flowreflookup`, `import-contacts-into-ms-dynamics`, `importing-a-CSV-file-into-Mongo-DB`, `importing-a-csv-file-into-ms-sharepoint`, `sending-a-csv-file-through-email-using-smtp` | `trigger/FileInboundPollerTest` |
| SC-6 | `service/FileOutboundWriter` | Writes bytes to `<dir>/<name>` with Mule's `outputPattern` naming, creating the directory and overwriting | `processing-orders-with-dataweave-and-APIkit-java` | `processing-orders-with-dataweave-and-APIkit`, `mule-expression-language-basics`, `legacy-modernization`, `http-multipart-request`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector` | `service/FileOutboundWriterTest` |
| SC-7 | `service/BatchJobRunner`, `model/BatchJobResult` | Mule batch semantics: input phase, ordered steps with accept expression and policy (`NO_FAILURES` default, `ONLY_FAILURES`), `max-failed-records` (default 0), commit grouping, on-complete counts | `import-leads-into-salesforce-java` | `import-leads-into-salesforce`, `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing` | `service/BatchJobRunnerTest` |
| SC-8 | `service/WatermarkStore` | File-backed watermark per key (`watermark.store-path`), default when absent, MAX update after the poll completes, unchanged on an empty poll | `salesforce-data-synchronization-using-watermarking-and-batch-processing-java` | `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing` | `service/WatermarkStoreTest` |
| SC-9 | `client/UpstreamCallExecutor`, `UpstreamCall`, `UpstreamAction`, `UpstreamSystem`, `UpstreamFailureClassifier`, `UpstreamFailureKind`; `exception/UpstreamAuthenticationException`, `UpstreamRateLimitException`, `UpstreamUnavailableException`, `UpstreamExceptionHandler` | The approved connector failure handling (section 6.4) and its highest-`@Order` advice mapping to 502/429/503 | `salesforce-data-retrieval-java` | The 11 connector projects: `salesforce-data-retrieval`, `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `authenticating-salesforce-using-oauth2`, `get-customer-list-from-netsuite`, `netsuite-data-retrieval`, `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing`, `adding-a-new-customer-to-workday-revenue-management`, `import-contacts-into-ms-dynamics`, `dataweave-with-flowreflookup`. `UpstreamExceptionHandler` only in the five HTTP-triggered ones: `salesforce-data-retrieval`, `authenticating-salesforce-using-oauth2`, `get-customer-list-from-netsuite`, `netsuite-data-retrieval`, `adding-a-new-customer-to-workday-revenue-management` | `client/UpstreamCallExecutorTest`; `exception/UpstreamExceptionHandlerTest` where the handler is copied |
| SC-10 | `config/ListenerPorts`, `config/PortRoutingFilter` | Extra Undertow listeners for every additional original port; the filter writes the no-listener 404 for a path requested on a port its original listener did not serve | `foreach-processing-and-choice-routing-java` | `foreach-processing-and-choice-routing` (11081 + 18080, 10080, 20080, 30080, 40080, 50080), `http-oauth-provider` (8082 + 8081), `oauth2-authorization-code-using-the-HTTP-connector` (8081 + HTTPS 8082), `soap-webservice-security` (63081 + 63080), `service-orchestration-and-choice-routing` (1080 + 8090, 8091, 9090, 9999), `mule-component-bindings` (8081 + 8180) | `config/PortRoutingFilterTest` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter` | CXF-compatible response envelopes; SOAP 1.1 faults inside and before endpoint dispatch; `GET <path>?wsdl` and `?xsd=<location>` with addresses and `schemaLocation`s rewritten, only on listeners whose original accepts GET | `foreach-processing-and-choice-routing-java` | `?wsdl` published: `foreach-processing-and-choice-routing` (`wsdl/BankService.wsdl` on the five bank endpoints, `wsdl/CreditAgencyService.wsdl` on the credit agency), `soap-webservice-security` (`wsdl/GreeterService.wsdl` on the six paths), `xml-only-soap-webservice` (the three carried WSDLs plus `SOA-Message-1.0.xsd` and `SOA-Model-1.0.xsd`). Envelopes and faults only: `legacy-modernization`, `service-orchestration-and-choice-routing`, whose SOAP listeners accept POST only and answer GET with 405; `WsdlQueryPublisher` is copied with the row but no WSDL is registered there. `proxying-a-soap-api` uses only `WsdlQueryPublisher`, serving the upstream WSDL | `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/WsdlQueryPublisherTest`, `config/SoapListenerBoundaryAdapterTest`, each with its class |
| SC-12 | `controller/BayeuxLongPollingController`, `service/AjaxChannelBroker`, `config/AjaxResourceConfig`, `src/main/resources/mule-resource/js/**` | The Mule Ajax transport: Bayeux 1.0 long polling at `/ajax/cometd`, channel dispatch with `replyTo` replies unless `ajax.disable-reply-to` is `true`, the original Mule 3.8.0 Ajax JavaScript served unchanged | `service-orchestration-and-choice-routing-java` | `service-orchestration-and-choice-routing` (8090, serverPath `/orders`, replies enabled), `websphere-mq` (8086, serverPath `/services/wmqExample`, `ajax.disable-reply-to: true`) | `controller/BayeuxLongPollingControllerTest`, `service/AjaxChannelBrokerTest` |
| SC-13 | `mapper/ProxyHeaderMapper` | Header copying both ways, removing `Host`, `Content-Length`, `http.*`, `MULE_*`, `Connection`, `Transfer-Encoding` | `proxying-a-rest-api-java` | `proxying-a-rest-api`, `proxying-a-soap-api` | `mapper/ProxyHeaderMapperTest` |
| SC-14 | `mapper/ParseTemplateRenderer` | Loads a classpath template and replaces each original `#[...]` expression string with a supplied value; templates stay byte-identical | `login-form-using-the-http-connector-java` | `login-form-using-the-http-connector`, `http-multipart-request`, `salesforce-data-retrieval`, `get-customer-list-from-netsuite`, `processing-orders-with-dataweave-and-APIkit` | `mapper/ParseTemplateRendererTest` |
| SC-15 | `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier`, `config/SalesforceProperties` | `PartnerConnection` from `ConnectorConfig` (SOAP `login`, or session ID + instance URL); `query`, `queryAll`, `create`, `delete`, `describeGlobal`, `describeSObject`, every call through SC-9 with re-login as re-authentication | `salesforce-data-retrieval-java` | The 7 Salesforce projects: `salesforce-data-retrieval`, `authenticating-salesforce-using-oauth2` (session mode), `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `dataweave-with-flowreflookup`, `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing` | `client/SalesforcePartnerClientTest`, `client/SalesforceFailureClassifierTest` |
| SC-16 | `client/NetsuiteTokenProvider`, `client/NetsuiteRestClient`, `client/NetsuiteFailureClassifier`, `config/NetsuiteProperties` | JWT client assertion and token caching; `WebClient` calls for SuiteQL (paging), record GET with `expandSubResources=true`, record create/delete and the cached `customfield` source lookup, every call through SC-9 | `get-customer-list-from-netsuite-java` | `get-customer-list-from-netsuite`, `netsuite-data-retrieval` | `client/NetsuiteTokenProviderTest`, `client/NetsuiteRestClientTest`, `client/NetsuiteFailureClassifierTest` |
| SC-17 | `controller/ApiKitConsoleController`, `src/main/resources/apikit-console/**` | The APIkit 3.8.0 console at `<api base>/console/` and the RAML descriptor (`?raml`, or `Accept: application/raml+yaml`) with `baseUri` rewritten | `rest-api-with-apikit-java` | `rest-api-with-apikit`, `testing-apikit-with-munit`, `processing-orders-with-dataweave-and-APIkit`, `netsuite-data-retrieval` | `controller/ApiKitConsoleControllerTest` |
| ST-1 | `src/test/java/.../support/HttpTestClient` | HTTP/1.1 socket client returning the raw status line (code and reason phrase), headers and body; HTTPS mode over the project's carried stores | `rest-api-with-apikit-java` | The 42 projects with an HTTP listener: all 55 except the 13 with `spring.main.web-application-type: none` (`jms-message-rollback-and-redelivery`, `using-transactional-scope-in-jms-to-database`, `import-contacts-into-salesforce`, `import-leads-into-salesforce`, `dataweave-with-flowreflookup`, `import-contacts-into-ms-dynamics`, `salesforce-data-synchronization-using-watermarking-and-batch-processing`, `salesforce-to-MySQL-DB-using-Batch-Processing`, `importing-a-CSV-file-into-Mongo-DB`, `importing-a-csv-file-into-ms-sharepoint`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector`, `sending-a-csv-file-through-email-using-smtp`). HTTPS mode is used by `oauth2-authorization-code-using-the-HTTP-connector` for HTTPS 8082 | — (test helper) |
| ST-2 | `src/test/java/.../FixtureParityTest`, `src/test/resources/fixtures/SCENARIOS.md` | Fixture replay by trigger type; skips by assumption while no fixture exists | `hello-world-java` | The 51 non-RAML projects (all 55 except `rest-api-with-apikit`, `testing-apikit-with-munit`, `processing-orders-with-dataweave-and-APIkit`, `netsuite-data-retrieval`) | — (it is the test) |

---

## 5. Carried platform-generated surfaces and legacy disposition

Rule (AAP 0.4.5): an element of an original is carried if and only if the original's runtime reaches it: it is declared in a flow file the application loads (`config.resources` in `mule-deploy.properties`, or every `src/main/app/*.xml` when absent), instantiated or called by such a flow, served by one of its listeners, read by its Java classes, or read by its tests or README steps. Anything else is reference only.

Surfaces the Mule platform generated beyond the RAML or the flows:

| Surface | Carried | Reason |
|---|---|---|
| CXF `?wsdl` and `?xsd=` publication | Yes, on every SOAP listener whose original accepts GET (SC-11). Not on the POST-only listeners of `legacy-modernization` (`legacy-modernization/src/main/app/FufillmentWebService.xml:5`) and `service-orchestration-and-choice-routing` (`service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:29,151`), which answer GET with 405 as their originals do | The originals' clients and tests read it, e.g. `unsecure?wsdl` (`soap-webservice-security/src/test/java/com/mulesoft/mule/example/security/SoapWebserviceSecurityIT.java:30-40`) |
| OAuth provider `/authorize` login page and `/token` | Yes; the login page is authored as `src/main/resources/templates/login.html` with the form field names `username` and `password` the original test fills | The example's own `/redirect` flow and its test depend on them |
| Mule Ajax transport (`/ajax/cometd` Bayeux endpoint, `mule-resource/js/**` client library) | Yes, the original protocol and JavaScript (SC-12) | The copied pages load the library and talk Bayeux through it (`service-orchestration-and-choice-routing/src/main/app/docroot/index.html:21`) |
| HTTP listener default 404 and 405 responses | Yes (section 6.5) | Observable on every listener |
| APIkit console (`consoleEnabled="true" consolePath="console"`) and RAML descriptor | Yes (SC-17) | Every RAML original enables them (`rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:3`) and the README sends users to `http://localhost:8080/api/console` (`rest-api-with-apikit/README.md:41`) |
| Mule EE tracking UI, MMC agents | No | Runtime management products outside the examples |

Not ported (reference only):

| Element | Reason |
|---|---|
| `filtering-a-message/src/FreeMembershipDiscountFilter.java` | Stray uncompiled copy; the compiled `src/main/java/org/mule/examples/filters/FreeMembershipDiscountFilter.java` is ported |
| `service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/samsung/SamsungServiceService.java` | JAX-WS client stub; replaced by `client/SamsungServiceClient` |
| `service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/samsung/OrderItemToOrderRequest.java` | No flow references it; the `samsungOrder` request mapping follows the active DataWeave script (`service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:51-59`) |
| The unused `http:request-config` of `exposing-a-restful-resource-using-the-HTTP-connector` | No flow element uses it |
| `{folder}/catalog/**`, `{folder}/mule-project.xml`, `{folder}/src/main/app/mule-deploy.properties`, `{folder}/src/main/resources/log4j2.xml`, `{folder}/src/test/resources/log4j2-test.xml`, `{folder}/src/test/resources/sample_data/**` | Studio metadata, deployment descriptors, logging configuration and Studio preview inputs that no runtime path reads; `mule-deploy.properties` is read only to decide which flows are converted |

Third-party assets copied into projects at conversion time:

| Asset | Source | Licence handling | Projects |
|---|---|---|---|
| APIkit 3.8.0 console (`index.html`, scripts, styles, fonts, images) at `src/main/resources/apikit-console/**` | The `console/` tree of `org.mule.modules:mule-module-apikit:3.8.0`, MuleSoft releases repository `https://repository.mulesoft.org/releases/` | The jar carries no licence file, so each project `NOTICE` names the jar as the source | The four RAML projects (SC-17) |
| Mule 3.8.0 Ajax JavaScript at `src/main/resources/mule-resource/js/**` (`mule.js`, `_mule.js`, `dojo/dojo.js`, `dojo/dojo.js.uncompressed.js`, `dojox/cometd.js`, `dojox/cometd.js.uncompressed.js`, `dojox/cometd/timestamp.js`, `dojox/cometd/timesync.js`, `dojox/uuid/generateRandomUuid.js`) | `github.com/mulesoft/mule`, tag `mule-3.8.0`, `transports/ajax/src/main/resources/js/`, copied unchanged with their licence headers | Mule code under CPAL 1.0; Dojo under AFL 2.1 or BSD; credited in each project `NOTICE` | `service-orchestration-and-choice-routing`, `websphere-mq` (SC-12) |

---

## 6. Behaviour and contract decisions

### 6.1 `rest-api-with-apikit` 409

The RAML declares 409 for POST `/teams` (`rest-api-with-apikit/src/main/resources/leagues.raml:43`) and for PUT `/fixture/{homeTeamId}/{awayTeamId}` (`leagues.raml:156`), and the README describes a 409 exception strategy (`rest-api-with-apikit/README.md:56`), while the flow maps only the five APIkit codes (`rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:18-44`). The RAML contract governs: the ported `ConflictException` ("There is already a team with id <id>", "The match between team <h> and team <a> has not been played yet") is answered by `exception/LeagueExceptionHandler` with 409 and `{ "message": "<exception message>" }`. The 409 body is a plan default and a Tier 2A verification point. `RAML-CONTROLLER-DIFF.md` shows it as a RAML-governed row.

### 6.2 Salesforce authentication

- Six originals log in through the SOAP Partner API with username, password and security token (for example `import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml:4`). SC-15 keeps that protocol: SOAP `login` through `PartnerConnection`/`ConnectorConfig` with `sfdc.username`, `sfdc.password` and `sfdc.securityToken`.
- SOAP `login` is not the OAuth 2.0 password grant. It was chosen because the request requires each conversion to match its original's mechanism and authorizes reusing the existing Salesforce credentials, and SOAP `login` needs nothing beyond them.
- The request forbids only new sandbox or tenant procurement, so an OAuth flow is not prohibited. It would need configuration this phase does not assume: a connected app registered in the existing tenant, whose consumer key and secret (password grant) or uploaded certificate and consumer key (JWT bearer) the requester would supply, plus a token exchange in SC-15. No such flow is built.
- `authenticating-salesforce-using-oauth2` keeps its original OAuth 2.0 authorization-code flow (`sfdc.key`, `sfdc.secret`) and uses SC-15 in session mode (session ID + instance URL from the issued token); the token refresh is its re-authentication step.

### 6.3 Configuration and credential keys

- `application.yml` keys keep the original placeholder names (dotted names map to YAML nesting), so a reader of the original finds every value under the same name.
- Non-secret values the original fixed (ports, paths, polling frequencies, queue names, public service URLs) are defaults. Secrets, hosts, accounts and tenant identifiers have no value: each is `key: ${ENV_NAME:}` with a `# TODO: supply <what>` comment, `ENV_NAME` being the key upper-cased with dots and hyphens as underscores.
- Where the request names keys, its names govern and each README maps the original name to the new one:

| Original keys | Converted keys | Projects |
|---|---|---|
| `sfdc.user` (and `sfdc.username`), `sfdc.password`, `sfdc.securityToken` | `sfdc.username`, `sfdc.password`, `sfdc.securityToken` | The 6 Salesforce projects that use SOAP `login` |
| `sfdc.key`, `sfdc.secret` | Unchanged; the live test's browser login uses `sfdc.username`, `sfdc.password` | `authenticating-salesforce-using-oauth2` |
| `netsuite.account`, `netsuite.email`, `netsuite.password`, `netsuite.roleId`; `nets.account`, `nets.email`, `nets.password`, `nets.roleId`, `nets.applicationId` | `netsuite.account`, `netsuite.oauth.client-id`, `netsuite.oauth.certificate-id`, `netsuite.oauth.private-key-path`, `netsuite.time-zone` (default `America/Los_Angeles`); in `netsuite-data-retrieval` also `netsuite.custom-field-type-ids` (empty by default) | `get-customer-list-from-netsuite`, `netsuite-data-retrieval` |
| `crm.username`, `crm.password`, `crm.service-url`, `crm.auth-type` | `dynamics.oauth.tenant-id`, `dynamics.oauth.client-id`, `dynamics.oauth.client-secret`, `dynamics.service-url` | `import-contacts-into-ms-dynamics` |
| `wday.hostname`, `wday.tenant`, `wday.user`, `wday.password` | Unchanged | `adding-a-new-customer-to-workday-revenue-management` |
| `sap.jco.ashost`, `sap.jco.sysnr`, `sap.jco.client`, `sap.jco.user`, `sap.jco.passwd`, `sap.jco.lang` | `jco.client.ashost`, `jco.client.sysnr`, `jco.client.client`, `jco.client.user`, `jco.client.passwd`, `jco.client.lang` | SAP follow-on (section 10) |

- Test-data keys that are not credentials keep their original names: `nets.customer.companyName`, `nets.customer.subsidiary.internalId`, `nets.opportunity.title`, `nets.item.quantity`, `wday.status.id`, `wday.reason.id`.
- Credential literals hard-coded in originals moved to named keys with TODO values: OpenKM `cmis.username`, `cmis.password`; FTP `ftp.host`, `ftp.port`, `ftp.path`, `ftp.user`, `ftp.password`; `tshirt.api-key`; OAuth provider `oauth.provider.client-id`, `oauth.provider.client-secret`, `oauth.provider.owner.username`, `oauth.provider.owner.password`; login check `login.username`, `login.password`; `soap-security.users`; the keystore and key passwords of `wssecurity.properties`, `saml.properties` and the TLS keystores. Unit tests supply the original demo values only through `src/test/resources/application-test.yml` (Spring profile `test`), because the committed demo keystores and expected outputs depend on them.
- Live connector credentials live only in `src/test/resources/application-it.yml`, which is git-ignored by the root entry under the "Test credentials to be use locally only" block (`.gitignore:61-65`) and by each connector project's `.gitignore`; `application-it.yml.example` lists the required keys with TODO values.

### 6.4 Approved connector failure modes

`UpstreamCallExecutor` (SC-9) wraps every vendor call in the 11 connector projects and is the only place these additions exist (AAP 0.4.8):

| Failure | Behaviour | Caller-facing result (HTTP-triggered projects) |
|---|---|---|
| Authentication or token expiry | Re-authenticate, then retry the call once; an authentication failure of the re-authentication or the retry ends the attempt | 502 `{ "message": "<upstream message>", "upstream": "<system>" }` |
| Rate limit / throttling | No retry | 429 with the vendor's `Retry-After` when present, same body |
| Timeout or connectivity failure (`SocketTimeoutException`, `HttpTimeoutException`, Netty `ReadTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`, anywhere in the cause chain) | Never retried | 503, same body |
| Anything else | Rethrown unchanged to the example's original exception branch | Original behaviour |

- `upstream` values: `Salesforce`, `NetSuite`, `Workday`, `Microsoft Dynamics 365`. Connect and read timeouts are 10 s for every vendor (the Mule default response timeout).
- Vendor classification: Salesforce `INVALID_SESSION_ID` → authentication, `REQUEST_LIMIT_EXCEEDED` → rate limit; NetSuite and Dynamics HTTP 401 → authentication (token cache evicted before the retry), 429 → rate limit; Workday HTTP 401 or SOAP fault `wsse:FailedAuthentication` / `wsse:InvalidSecurity` → authentication, 429 → rate limit.
- Retrying after an authentication rejection is safe for writes because the vendor refused the request before processing it; timeouts and connectivity failures are never retried, which protects every write (`sfdc:create` of Contact, Lead and Account, Workday `Put_Customer`, Dynamics contact create, the NetSuite customer create of the live tests).
- `UpstreamExceptionHandler` runs at the highest `@Order` in the five HTTP-triggered connector projects, so in `salesforce-data-retrieval` the three additions take precedence over the catch-all `Invalid Salesforce query.`, which still handles every other exception.
- File- and poll-triggered connector projects have no caller to answer: the classification is logged at ERROR with the upstream name, and batch jobs record the record as failed.
- No other new behaviour exists in any project.

### 6.5 Error responses

Every converted API returns the error its original returned (AAP 0.4.7). A plan-chosen shape is used only where the original platform produced a default:

| Case | Response | Status |
|---|---|---|
| Path not served by any listener on that port | 404, `text/plain`, `No listener for endpoint: <path>` | Plan default; Tier 2A verification point |
| Method outside a listener's `allowedMethods` | 405, `text/plain`, `Method not allowed for endpoint: <path>` | Plan default; Tier 2A verification point |
| Exception with no handling branch in an HTTP flow | 500, `application/json`, `{ "message": "<exception message>" }` | Plan default; Tier 2A verification point |
| Unreadable request at a SOAP listener (malformed XML, empty body, root other than `soap:Envelope`) | HTTP 500, SOAP 1.1 fault `soap:Client` with the parser's message as `faultstring` | Plan default; the fault text is a Tier 2A verification point |
| Filter rejection without `throwOnUnaccepted` or an exception branch | 200 with an empty body (`filtering-a-message/src/test/java/org/mule/examples/FilteringMessageIT.java:65-67`) | Original behaviour |
| APIkit mappings in the four RAML projects | 404 `Resource not found`, 405 `Method not allowed`, 415 `Unsupported media type`, 406 `Not acceptable`, 400 `Bad request`, each as `{ "message": "..." }`, `application/json` (`rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml:18-44`) | Original behaviour |

- A captured Tier 2A fixture that shows different bytes for a plan default governs, and the handler is changed to match.
- `@ControllerAdvice` precedence by `@Order`: SC-9 `UpstreamExceptionHandler` (highest, connector projects only), then the project handler (`ChoiceExceptionHandler`, `PersonExceptionHandler`, `LeagueExceptionHandler`, `LoanBrokerExceptionHandler`, `LoginExceptionHandler`, `SalesforceRetrievalExceptionHandler`, `GreeterClientExceptionHandler`), then SC-3 `ApiKitExceptionHandler`, then SC-1 `GlobalExceptionHandler` (lowest). Project handlers declare only the exception types their original branches name.

### 6.6 JMS redelivery timing (`jms-message-rollback-and-redelivery`)

The original ActiveMQ `RedeliveryPolicy` is `initialRedeliveryDelay` 5000 ms, `redeliveryDelay` 2000 ms, `maximumRedeliveries` 5, no back-off (`jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:5-11`). Artemis has no separate initial redelivery delay, so the conversion sets the address settings for `in` to `redelivery-delay` 0 and `max-delivery-attempts` 6 (embedded test broker; documented in the README for an external broker), and `trigger/RedeliveryDelay` pauses inside the failing transaction before the rollback: 5000 ms when `JMSXDeliveryCount` is 1, 2000 ms otherwise. Each next delivery therefore starts 5000 ms after the first failure and 2000 ms after each later one; as with ActiveMQ's default blocking redelivery, the consumer takes no other message during the pause. `JmsMessageRollbackAndRedeliveryTest` measures the delivery counts and gaps.

### 6.7 Unreachable branch

The bank choice of `foreach-processing-and-choice-routing` routes `amount >= 20000` to Banks 1 and 2, then `amount >= 10000 || amount <= 19999` to Banks 3 and 4, otherwise to Bank 5 (`foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:81-88`). The second condition holds for every amount (any amount below 10000 satisfies `<= 19999`), so Bank 5 is unreachable. It is kept exactly as declared, `LoanBrokerServiceTest` asserts that boundary amounts (−1, 0, 9999, 10000, 19999, 20000) never reach Bank 5, `SCENARIOS.md` lists it with state `unreachable`, and it is excluded from fixture capture and from the branch-coverage claim.

### 6.8 NetSuite select-field `typeId`

A REST select value carries no SuiteTalk `typeId`. `netsuite-data-retrieval` reads it from SuiteQL `SELECT scriptid, fieldvaluetyperecord FROM customfield WHERE scriptid IN (...)`, cached per script ID for the application run (SC-16); a non-empty `netsuite.custom-field-type-ids` entry takes precedence. A select field whose source neither supplies fails the request through SC-1's 500 with a message naming the script ID; `typeId` is never written null or guessed. Oracle's documentation does not state that `fieldvaluetyperecord` equals the SuiteTalk `typeId` for every custom-list source, so `NetsuiteApiIT` asserts a resolved numeric `typeId` on every select field it reads, and the sandbox comparison is Tier 2B evidence to record here and in the project README: not recorded yet — Tier 2B not run.

### 6.9 Platform risks carried

| Risk | Handling |
|---|---|
| SharePoint Online's legacy username/password sign-in and Twitter API v1.1 search may no longer be available | The unit tier uses WireMock; live behaviour is verified only through Tier 2A capture of the originals; the READMEs of `importing-a-csv-file-into-ms-sharepoint` and `mule-component-bindings` state the dependency |
| NetSuite record features differ by account (item supply plans need the feature enabled; `customfield.fieldvaluetyperecord` exposure is account-dependent) | Item supply plans are read through the REST record API, not SuiteQL; an unresolved `typeId` fails visibly (section 6.8); a tenant lacking either is recorded in the README and in the `SUMMARY.md` Tier 2B cell |
| Spring WS 4.1.4 runs off its Framework 6.2 baseline | Endpoint and client tests of every Spring WS project are the gate (section 2) |
| Inbound WS-Security on WSS4J 4.0.1 versus the original WSS4J 1.6 stack | `PathScopedSecurityInterceptor` applies WSS4J's any-order receiver check fail-closed per path; `GreeterEndpointTest` covers every original positive and negative case and the signed-SAML client path |
| Public demo upstreams the originals call are unreachable from the build host (`tshirt-service.cloudhub.io`, `www.predic8.com:8080` ShopService, `tfim01.demos.ibm.com`, `ftp-server.demo.solarwinds.com`) | Unit tiers use WireMock or MockFtpServer stubs fed the original inputs |

---

## 7. SOAP contracts authored where no WSDL exists

Porting the JAX-WS interfaces as plain Java interfaces removes the runtime that derived each operation's wire shape. Every exposed or called JAX-WS operation therefore has explicit Jakarta-JAXB request and response wrapper classes in `model/soap/` (the generated `samsung` classes already were wrappers), each keeping the original document/literal-wrapped shape (AAP 0.3.2):

| Operation (original interface) | Namespace | Wrappers (request / response) | Children, unqualified | Wrapper classes; published WSDL |
|---|---|---|---|---|
| `BankService.getLoanQuote` | `http://bank.loanbroker.example.mule.org/` | `getLoanQuote` / `getLoanQuoteResponse` | `arg0` (`LoanBrokerQuoteRequest`) / `return` (`LoanQuote`); the interface declares no `@WebParam`/`@WebResult` | `model/soap/GetLoanQuote`, `GetLoanQuoteResponse`; `wsdl/BankService.wsdl` |
| `CreditAgencyService.getCreditProfile` | `http://creditagency.loanbroker.example.mule.org/` | `getCreditProfile` / `getCreditProfileResponse` | `arg0` (`Customer`) / `return` (`CreditProfile`) | `model/soap/GetCreditProfile`, `GetCreditProfileResponse`; `wsdl/CreditAgencyService.wsdl` |
| `IFulfillment.putShippingOrder` | `http://ordermgmt.org/` | `putShippingOrder` / `putShippingOrderResponse` | `shippingId`, `billingAddress`, `shippingAddress`, `order` / `ShippingOrderConfirmation` | `model/soap/PutShippingOrder`, `PutShippingOrderResponse`; none published (POST-only listener) |
| `IProcessOrder.processOrder` | `http://orders.se.mulesoft.com/` | `processOrder` / `processOrderResponse` | `order` / `summary` | `model/soap/ProcessOrder`, `ProcessOrderResponse`; none published (POST-only listener) |
| `SamsungService.purchase` | `http://samsung.se.mulesoft.com/` | `purchase` / `purchaseResponse` | `orderRequest` / `orderResponse` | Ported generated `model/samsung/Purchase`, `PurchaseResponse`, `ObjectFactory`, `package-info`; none published (POST-only listener) |
| `Greeter.greet` | `http://security.example.mule.mulesoft.com/` | `greet` / `greetResponse` | `name` / `name` | `model/soap/Greet`, `GreetResponse`; `wsdl/GreeterService.wsdl` |

- Namespace sources: `http://ordermgmt.org/`, `http://orders.se.mulesoft.com/` and `http://samsung.se.mulesoft.com/` are confirmed by the soapUI-embedded WSDLs (`bindingName` `{http://ordermgmt.org/}IFulfillmentServiceSoapBinding` in `legacy-modernization/src/main/resources/LegacyModernizationExample-soapui-project.xml`; `IProcessOrderServiceSoapBinding` and `SamsungServiceServiceSoapBinding` in `service-orchestration-and-choice-routing/src/test/resources/test-soapui-project.xml`), whose schemas declare `elementFormDefault="unqualified"`. The bank, credit-agency and greeter namespaces are the JAX-WS defaults derived from the original Java packages.
- Because the classes move to `com.mulesoft.examples.<package>`, each wrapper declares its namespace explicitly in `@XmlRootElement(name = ..., namespace = ...)`; nothing derives a namespace from the new package. Children and nested elements carry no namespace.
- Ported models that already carried JAXB annotations keep them and their access strategy, apart from `javax.xml.bind` → `jakarta.xml.bind`. Bindings are added only in the `model/soap/` wrappers. The unannotated loan-broker models (`CreditProfile`, `Customer`, `LoanQuote`, `CustomerQuoteRequest`, `LoanBrokerQuoteRequest`) and orchestration classes (`OrderPerson`, `ItemAsXml`, `Status`) stay unannotated.
- The active bindings and the originals' committed assertions govern the wire shape; a WSDL in the repository is a reference document unless the original runtime reads it.

Authored, published WSDLs. Their identities are the JAX-WS defaults CXF derived from each `cxf:jaxws-service serviceClass` (`foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:140,148`, `soap-webservice-security/src/main/app/mule-config.xml:11`): service and port named after the service class, portType after the `@WebService` interface, binding `<service>SoapBinding`. Each is a complete WSDL 1.1 document (inline schema, messages, portType, document/literal SOAP 1.1 binding, one service with one port) whose `soap:address` SC-11 rewrites per request:

| File | portType | binding | service | port | Served at |
|---|---|---|---|---|---|
| `java-conversions/foreach-processing-and-choice-routing-java/src/main/resources/wsdl/BankService.wsdl` | `BankService` | `BankServiceSoapBinding` | `BankService` | `BankPort` | All five bank endpoints (10080–50080) |
| `java-conversions/foreach-processing-and-choice-routing-java/src/main/resources/wsdl/CreditAgencyService.wsdl` | `CreditAgencyService` | `DefaultCreditAgencyServiceSoapBinding` | `DefaultCreditAgencyService` | `DefaultCreditAgencyPort` | The credit agency (18080) |
| `java-conversions/soap-webservice-security-java/src/main/resources/wsdl/GreeterService.wsdl` | `Greeter` | `GreeterServiceSoapBinding` | `GreeterService` | `GreeterPort` | Each of the six security paths on 63081 |

Element order: the existing annotations and the committed replies where they exist (legacy and orchestration `reply.xml`); for the unannotated loan-broker models, the JAXB reference implementation's default, which sorts getter/setter properties by name. `CreditProfile` therefore marshals `creditHistory` before `creditScore`, and `LoanBrokerQuoteRequest` marshals `creditProfile`, `customerRequest`, `loanQuote` (verified by marshalling an unannotated copy of `CreditProfile` with `jaxb-runtime` 4.0.5). No `propOrder` is added, so the order stays the one CXF's JAXB databinding produced, and the authored WSDLs state it.

Reference-only WSDLs (copied, never read or published):

| File | Why it is not the contract |
|---|---|
| `service-orchestration-and-choice-routing/src/main/resources/orders.wsdl` (copied unchanged) | No flow reads it, and it disagrees with the annotated classes and the committed reply: it describes an unwrapped `item` (`orders.wsdl:18`), a `receipt` element (`orders.wsdl:30`) and status values `OK`/`FAIL` (`orders.wsdl:51-52`), while the classes and `service-orchestration-and-choice-routing/src/test/resources/reply.xml` produce `orderItems/item`, `purchaseReceipt` and `ACCEPTED` |
| `service-orchestration-and-choice-routing/src/main/resources/samsung.wsdl` (copied unchanged) | No flow reads it; the ported generated `samsung` classes govern |
| `java-conversions/legacy-modernization-java/src/main/resources/wsdl/IFulfillmentService.wsdl` | Extracted from the soapUI project as a reference copy and checked against `reply.xml`; not published, because the listener accepts POST only |

Tier 2A captures an original `?wsdl` only where that metadata surface was reachable: the loan-broker services and the greeter, whose listeners accept every method, and `xml-only-soap-webservice`, whose WSDLs are carried unchanged. The POST-only listeners of `legacy-modernization` and `service-orchestration-and-choice-routing` had no `?wsdl` to capture.

### Captured WSDL replacements

None yet — Tier 2A not run. When a captured original `?wsdl` (loan-broker services, greeter, `xml-only-soap-webservice`) differs from the authored or carried file, the captured file replaces it and the difference is recorded here.

---

## 8. Query hardening

Several originals build queries by concatenating request input. The conversion returns the same rows for every legitimate input while closing injection, and this is the only intentional difference for hostile input (AAP 0.4.10):

- SQL values are bound as parameters.
- Values inside SOQL string literals are escaped: backslash → `\\`, single quote → `\'` (`SalesforceRetrievalService.escapeLiteral` in `salesforce-data-retrieval-java`).
- Values inside SuiteQL string literals are escaped by doubling each single quote (`O'Brien` → `O''Brien`, SC-16 `NetsuiteRestClient.suiteQlLiteral`). SuiteQL follows Oracle literal rules, under which a backslash is an ordinary character that cannot end the literal, so it is left unchanged; escaping it would change the value matched for legitimate input.
- In `salesforce-data-retrieval`, the caller-supplied SOQL object, search key and field list are checked against `describeGlobal`/`describeSObject` (relationship paths resolved hop by hop, names compared case-insensitively as SOQL compares them). A non-matching identifier fails before Salesforce is queried and is answered with the original `Invalid Salesforce query.`.

| Project | Original construction | Converted construction |
|---|---|---|
| `querying-a-mysql-database` | `db:dynamic-query` concatenating `lastname` (`querying-a-mysql-database/src/main/app/database-to-json.xml:8`) | `client/EmployeeRepository`: `select first_name from employees where last_name = ?` |
| `querying-a-db-and-attaching-results-to-an-email` | `db:dynamic-query` concatenating first and last name (`querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:12`) | `client/EmployeeRepository`: `... where first_name = ? and last_name = ?` |
| `salesforce-data-retrieval` | MEL-built SOQL from form fields `object`, `field`, `searchKey`, `searchValue` (`salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27`) | `service/SalesforceRetrievalService`: identifiers checked as above, `searchValue` escaped as a SOQL literal |
| `import-leads-into-salesforce` | `SELECT Id FROM Lead WHERE Email = '#[payload["Email"]]'` (`import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:28`) | `service/LeadImportBatchService`: `Email` escaped as a SOQL literal |
| `get-customer-list-from-netsuite` | NetSuite `dsql` with `lastName like '<lastName>%'` (`get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:10`) | SuiteQL with `lastName` escaped by `NetsuiteRestClient.suiteQlLiteral` |
| `netsuite-data-retrieval` | DataWeave search criteria passed to the SuiteTalk connector (`netsuite-data-retrieval/src/main/app/netsuite-api.xml:25-106`) | `service/NetsuiteSearchService`: SuiteQL ID selection with `name` and `title` escaped by `NetsuiteRestClient.suiteQlLiteral` |
| `using-transactional-scope-in-jms-to-database` | Already a `db:parameterized-query` (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:14`) | `client/OrderRepository`: `insert into orders(item_id,item_units,customer_id) values (?, ?, ?)` |
| `salesforce-to-MySQL-DB-using-Batch-Processing` | Already `db:parameterized-query` (`salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:28,40,46`) | `client/ContactRepository`: bind parameters for the select by email, the insert and the update |
| `service-orchestration-and-choice-routing` | Already `db:parameterized-query` (`service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:111,135`) | Bind parameters for the `orders` insert and the `order_audits` insert (`service/AuditService`: `insert into order_audits values(default, ?, ?)`) |

Queries without caller input stay as the originals wrote them: the watermark query of `salesforce-data-synchronization-using-watermarking-and-batch-processing` (the timestamp comes from the watermark store) and the fixed contact query of `authenticating-salesforce-using-oauth2`.

---

## 9. Verification tiers and Tier 2A escalation state

| Tier | Prerequisite | Projects | Evidence | Current state |
|---|---|---|---|---|
| Tier 1 — build-verified | None (JDK 17, Maven, Maven Central) | All 55; plus the static RAML diff for the 4 RAML-backed projects | `mvn -B clean package`, `mvn -B test`, `mvn -B clean install` results; `target/surefire-reports`; `target/site/jacoco`; `RAML-CONTROLLER-DIFF.md` | Reported per project in `SUMMARY.md` |
| Tier 2A — fixture-verified | Mule EE runtime licence and EE Maven repository credentials | The 51 non-RAML projects | Committed `src/test/resources/fixtures/*.json` and surefire reports showing `FixtureParityTest` with zero skipped | `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed` |
| Tier 2B — live-sandbox-verified | Vendor sandbox credentials only (`src/test/resources/application-it.yml` per project) | The 11 connector projects | `mvn -B verify` failsafe reports with the `*IT` classes run, not skipped | `NOT RUN — sandbox credentials not supplied` |

No tier's status is reported from another tier's evidence: Tier 1 never reports parity, a blocked Tier 2A never marks Tier 2B blocked, and a skipped `*IT` or `FixtureParityTest` never counts as a pass.

Current state: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. Conversion, compilation and unit testing proceeded without waiting, on the fallback of AAP 0.8.4: the ported original tests carry the originals' committed assertions with their comparison semantics, and the controller, endpoint and trigger tests cover every reachable branch. Every non-RAML project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable` in its README and in `SUMMARY.md`, never PASS; the four RAML-backed projects carry `RAML DIFF: 1:1 (Tier 1 static)`. `FixtureParityTest` skips by assumption while no fixture exists, and every `*IT` skips by assumption while `application-it.yml` or a required key is absent.

Trigger: when both the Mule EE runtime licence and the EE Maven repository credentials are confirmed available, a human is notified to begin Tier 2A capture (procedure in AAP 0.8.4). Fixtures are never edited to match the Java output; a mismatch is fixed in the Java code.

What each missing prerequisite blocks:

| Missing prerequisite | Blocks | Does not block |
|---|---|---|
| Mule EE runtime licence | Starting the originals, hence Tier 2A fixture capture | Tier 1, Tier 2B |
| EE Maven repository credentials | Compiling the originals, hence Tier 2A fixture capture | Tier 1, Tier 2B |
| Vendor sandbox credentials | Tier 2B of the affected connector project only. Salesforce and Workday reuse existing credentials; NetSuite needs the M2M integration record (client ID, certificate) and Dynamics needs the Entra app registration plus a Dataverse application user, both registered by the requester in the existing tenants | Tier 1, Tier 2A, other projects' Tier 2B |
| `sapjco3.jar` with its native library | The `sap-data-retrieval` conversion only (section 10) | Tier 1 for the 55 delivered projects |

### Unverified-scenario register

| Project | Scenario kind | State | Reason |
|---|---|---|---|
| `xml-only-soap-webservice` | Mock responses embedding `now` (`PatientId`, `episodeId`, dates) | `nondeterministic` | The original stamps the current time; `ClockConfig` makes the unit tests deterministic, not the original's captures |
| `foreach-processing-and-choice-routing` | Loan quotes | `nondeterministic` | Random credit profiles and interest rates in the original |
| `document-integration-using-the-cmis-connector` | Created document | `nondeterministic` | Generated name `pic<millis>.jpg` |
| `legacy-modernization` | Exported shipping-order file | `nondeterministic` | Timestamped file name `ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt` |
| `foreach-processing-and-choice-routing` | Bank 5 branch | `unreachable` | Section 6.7; excluded from capture and from the branch-coverage claim |

Nondeterministic scenarios are replayed with exact status, reason and headers and a structural body comparison, and each counts as unverified for the `PARITY` label. Per-project lists of unverified scenarios with their reasons, and any `ACCEPTED — structural comparison` decision: none yet — Tier 2A not run. Once a project's capture is complete, its list is recorded here for the person who received the Tier 2A notification; a scenario changes state only on a reproducibility input (a fixed clock or seed, or the missing local server) followed by re-capture, or on an explicit acceptance recorded here.

### Verification points reported during conversion

Beyond the plan defaults of section 6.5, the conversion recorded these platform-level behaviours for comparison against the captured originals; the captured fixture governs each:

- SOAP listeners (SC-11): SAAJ's SOAP 1.1 message factory answers an envelope posted with a `Content-Type` other than `text/xml` (or none) with HTTP 500 and a `soap:Client` fault ("Invalid Content-Type…" or "Cannot create message: incorrect content-type for SOAP version…"), where CXF is believed to have accepted it.
- SOAP listeners (SC-11): SAAJ adds `Accept` and `SOAPAction: ""` headers to SOAP responses and faults, which CXF did not send.
- SOAP listeners (SC-11): a valid SOAP 1.1 envelope that matches no endpoint is answered with Spring WS's bare 404, where CXF answered an unknown wrapper with a `soap:Client` fault.
- HTTP listeners: requests that Undertow or Spring MVC answer before any controller runs, such as malformed percent-encoding in a query string or form body and CORS pre-flight requests, may differ from the Mule 3.8 listener's answers (for a malformed query string Undertow sends 400 with an empty body, where Mule sent 400 with its own message). Each affected controller's Javadoc names its handling.

---

## 10. SAP follow-on (`sap-data-retrieval`)

- Status label everywhere: `EXCLUDED — pending sapjco3.jar`. No `java-conversions/sap-data-retrieval-java/` exists in this phase. Obtaining the licensed SAP Support Portal account, `sapjco3.jar` and its native library (`libsapjco3.so` / `sapjco3.dll`) is the requester's responsibility.
- The original declares JCo as a system dependency `com.sap.conn.jco:sapjco` `${sap.jco.version}` (3.0.13, `sap-data-retrieval/pom.xml:16`) at `${project.basedir}/libraries/SAP.${sap.jco.version}/sapjco3.jar` (`sap-data-retrieval/pom.xml:100-107`, systemPath at line 105). The conversion will mirror that convention:

```xml
<dependency><groupId>com.sap.conn.jco</groupId><artifactId>sapjco3</artifactId><version>3.1.11</version>
  <scope>system</scope><systemPath>${project.basedir}/libraries/SAP.3.1.11/sapjco3.jar</systemPath></dependency>
```

- The native library sits in the same directory; surefire and failsafe set `-Djava.library.path=${project.basedir}/libraries/SAP.3.1.11`; `spring-boot-maven-plugin` sets `includeSystemScope`. If the supplied jar is another 3.1.x patch, the version, the directory name and this entry change to that exact patch.
- The root `.gitignore` already ignores `*.jar` (`.gitignore:7`), so the licensed jar is never committed. The original's `sapidoc3.jar` (`sap-data-retrieval/pom.xml:108-115`) is not needed, because the example uses no IDoc.
- Configuration keys, mapped from the original connector attributes (`sap-data-retrieval/src/main/app/sap-api.xml:4`): `sap.jco.ashost` → `jco.client.ashost`, `sap.jco.sysnr` → `jco.client.sysnr`, `sap.jco.client` → `jco.client.client`, `sap.jco.user` → `jco.client.user`, `sap.jco.passwd` → `jco.client.passwd`, `sap.jco.lang` → `jco.client.lang`.
- BAPIs behind the RAML resources of `sap-data-retrieval/src/main/resources/sap-api.raml`:

| RAML resource | BAPI | Flow |
|---|---|---|
| `/customers` (`sap-api.raml:103`) | `BAPI_CUSTOMER_FIND` | `get:/customers:sap-api-config` (`sap-api.xml:44`, call at `sap-api.xml:69`) |
| `/materials` (`sap-api.raml:7`) | `BAPI_MATERIAL_GETLIST` | `get:/materials:sap-api-config` (`sap-api.xml:113`, call at `sap-api.xml:137`) |
| `/salesOrders` (`sap-api.raml:30`) | `BAPI_SALESORDER_GETLIST` | `get:/salesOrders:sap-api-config` (`sap-api.xml:82`, call at `sap-api.xml:99`) |

- Open for the follow-on RAML diff: the APIkit flow mapping names the resource `/salesOrder` (`sap-api.xml:8`), while the RAML declares `/salesOrders` and the mapped flow is named `get:/salesOrders:sap-api-config`; the follow-on diff settles which governs.
- Pending: the `sap-data-retrieval` section of `RAML-CONTROLLER-DIFF.md` (now "EXCLUDED — pending sapjco3.jar") and the Tier 2B live test.

