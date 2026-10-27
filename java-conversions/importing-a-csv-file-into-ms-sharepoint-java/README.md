# importing-a-csv-file-into-ms-sharepoint-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.6 example [`importing-a-csv-file-into-ms-sharepoint`](../../importing-a-csv-file-into-ms-sharepoint/) (category General). The original takes a CSV file of contacts and uploads it to a SharePoint Online site, into a folder under *Shared Documents* that it creates first. Its single flow `importing-a-csv-file-into-ms-sharepointFlow` (`importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml`) uses the Microsoft SharePoint 2013 connector `mule-module-sharepoint` 2.1.6. The original folder `importing-a-csv-file-into-ms-sharepoint/` stays unchanged beside this project and is the Tier 2A capture source.

- Package `com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint`; main class `ImportingACsvFileIntoMsSharepointApplication` (`@SpringBootApplication`, `@EnableScheduling`).
- No web server (`spring.main.web-application-type: none`): the original has no HTTP listener.

## Original example

A file connector polls `src/main/resources/input` every ten seconds. Each file's content is kept in the flow variable `fileContent` (l.8). The SharePoint connector creates the folder `/Shared Documents/${csvImportFolder}` (l.10), adds the content there as `contacts.csv` with overwrite (l.12) and logs at INFO (l.14). The file endpoint then moves the polled file to `src/main/resources/output`. The flow declares no exception strategy and no transaction. The root reactor `pom.xml:59` comments this module out.

## Endpoints and triggers

There is no HTTP endpoint. The only trigger is a file poller:

| Trigger | Component | Input directory | Polling | Disposition | Source |
|---|---|---|---|---|---|
| file inbound endpoint | `trigger/CsvFileTrigger`, `@Scheduled(fixedDelayString = "${csv-import.polling-frequency:10000}")` on `poll()`, over SC-5 `trigger/FileInboundPoller` | `csv-import.input-dir`, default `src/main/resources/input` | every `csv-import.polling-frequency` ms, default `10000`, as a fixed delay between the end of one poll and the start of the next | moved to `csv-import.move-to-dir`, default `src/main/resources/output`, under its own name, after success and after a handled failure | `importing-a-csv-file-into-ms-sharepoint.xml:7` (`path`, `moveToDirectory`, `pollingFrequency="10000"`) |

`CsvFileTrigger.pollOnce()` runs one poll on demand and returns the number of files handed to the import, counting a file whose import failed. Tests and the fixture replay call it. It shares the scheduled poll's poller, so neither run picks up a file the other is processing.

SC-5 semantics, as `FileInboundPoller` implements Mule 3.8's default file connector (AAP 0.4.4):

- Relative directories resolve against the working directory, and both directories are created when missing.
- Regular files are taken in directory-listing order, unsorted and unfiltered. Subdirectories are ignored.
- A file in process is marked, so an overlapping poll never hands it over twice.
- When the import returns, or throws an exception that the poller logs at ERROR and does not rethrow, the file is moved under its own name. A file of that name in the output directory is replaced, and a copy-and-delete is the fallback when a rename fails. A failed move is logged at WARN. No file is retried and no SharePoint write is repeated.
- A file that cannot be opened is logged at WARN and left in place, unmarked, for the next poll.

### Flow steps

`service/SharepointImportService.importFile(byte[])` runs the flow body for each polled file, in the original order:

1. The file's bytes are taken as read and never parsed (`set-variable fileContent`, l.8).
2. `SharepointRestClient.signIn()` opens a new SharePoint Online session (the connector's online connection, l.4).
3. `createFolder(session, "/Shared Documents/<csvImportFolder>")` creates the folder (`sharepoint:folder-create`, l.10).
4. `addFile(session, "/Shared Documents/<csvImportFolder>/contacts.csv", content, true)` adds the content with overwrite (`sharepoint:file-add overwrite="true"`, l.12). The remote name is always `contacts.csv`, whatever the input file is called, as in the original. Only the move to the output directory keeps the input file's own name.
5. The service logs INFO `Input file stored in the specified folder under the 'Shared Documents' folder` (l.14).

A failure at any step ends the import, and no later SharePoint call is made. The service logs one ERROR line, `SharePoint step '<sign-in|folder create|file add>' failed[ for <target>]: <reason>`, and rethrows the same exception. The reason is `HTTP <code> <reason phrase>[: <SharePoint OData error message>]` for an error status, `<Type>: <message>` for the client's own failures, and the class name for anything else. The line carries no response body, cookie, form digest or password. `FileInboundPoller` then logs ERROR `Exception processing file <name>: <exception class, throw site and cause classes>` and moves the file (SC-5).

### SharePoint Online requests

`client/SharepointRestClient` reproduces the 2.1.6 connector's Online connection: a claims-based sign-in with the configured username and password, then the two SharePoint REST calls. `<site>` is `Sharepoint.SiteUrl` with any trailing `/` removed. Requests 3 to 5 carry `Cookie: rtFa=<value>; FedAuth=<value>` from request 2's `Set-Cookie` headers.

| # | Request | Headers and body | Result taken |
|---|---|---|---|
| 1 | `POST <sharepoint-online.sts-url>` (WS-Trust `RST/Issue`) | `Content-Type: application/soap+xml;charset=utf-8`. A SOAP 1.2 envelope with WS-Addressing `Action` `http://schemas.xmlsoap.org/ws/2005/02/trust/RST/Issue`, an anonymous `ReplyTo` and `To` set to the STS URL. Its WS-Security `UsernameToken` holds `Sharepoint.Username` and `Sharepoint.Password`, XML-escaped. Its `RequestSecurityToken` has `AppliesTo` `<site>`, `KeyType` `http://schemas.xmlsoap.org/ws/2005/05/identity/NoProofKey`, `RequestType` `http://schemas.xmlsoap.org/ws/2005/02/trust/Issue` and `TokenType` `urn:oasis:names:tc:SAML:1.0:assertion`. | the text of `wsse:BinarySecurityToken` |
| 2 | `POST <site>/_forms/default.aspx?wa=wsignin1.0` | `Content-Type: application/x-www-form-urlencoded`; the body is the token. The redirect it answers with counts as success and is not followed. | the `rtFa` and `FedAuth` values of its `Set-Cookie` headers |
| 3 | `POST <site>/_api/contextinfo` | `Cookie`, `Accept: application/json;odata=verbose`, no body | `d.GetContextWebInformation.FormDigestValue` |
| 4 | `POST <site>/_api/web/folders` (folder create) | `Cookie`, `X-RequestDigest: <form digest>`, `Accept` and `Content-Type` `application/json;odata=verbose`. The body is `{"__metadata":{"type":"SP.Folder"},"ServerRelativeUrl":"/Shared Documents/<csvImportFolder>"}`. | the status only. The client takes SharePoint's success for a folder that already exists |
| 5 | `POST <site>/_api/web/GetFolderByServerRelativeUrl('/Shared%20Documents/<csvImportFolder>')/Files/add(url='contacts.csv',overwrite=true)` (file add) | `Cookie`, `X-RequestDigest`, `Accept: application/json;odata=verbose`, `Content-Type: application/octet-stream`; the body is the file's bytes, unchanged. Single quotes in OData literals are doubled, and the path is percent-encoded once. | the status only |

- Sign-in runs once per processed file, because SharePoint Online cookies and form digests expire between polls.
- Each request is sent exactly once and never retried, redirects are never followed, and no cookie store is kept.
- Requests use HTTP/1.1. The connect timeout is 10 s, and each whole response, headers and body, must arrive within 10 s (the Mule default response timeout). A response body is held up to 1 MiB.
- An error status surfaces as Spring's `HttpClientErrorException` or `HttpServerErrorException`; the STS's error body is withheld. A timeout or connectivity failure surfaces as `ResourceAccessException`.
- An STS answer with HTTP 200 and no token fails as `IllegalStateException` `SharePoint Online sign-in failed: <SOAP fault reason>`. A missing `rtFa` or `FedAuth` cookie fails as `IllegalStateException` `SharePoint Online sign-in failed: missing cookie(s) <names>`. A `contextinfo` response without a form digest fails as `IllegalStateException` `SharePoint contextinfo response carries no FormDigestValue`.
- The STS response is parsed with DOCTYPE declarations refused and external entities and XInclude disabled. `DECISIONS.md` lists this under its implementation safety controls.
- The password, token, cookie values and form digest are never logged and never placed in an exception message.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. The original's `mule-app.properties` holds no keys; its flow reads the four placeholders below.

Status values:

- `default`: a non-secret value the original fixed, overridable.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). A secret is marked `(secret)`.
- `fixed`: a framework setting that reproduces the original runtime, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `Sharepoint.Username` | `String` | none (`${SHAREPOINT_USERNAME:}`) | `Sharepoint.Username` (unchanged) | TODO | SharePoint Online account, format `<USERNAME>@<COMPANY_NAME>.onmicrosoft.com` (original README step 4); sent in the STS `UsernameToken` |
| `Sharepoint.Password` | `String` | none (`${SHAREPOINT_PASSWORD:}`) | `Sharepoint.Password` (unchanged) | TODO (secret) | the account's password, sent only in the STS `UsernameToken` |
| `Sharepoint.SiteUrl` | `String` | none (`${SHAREPOINT_SITEURL:}`) | `Sharepoint.SiteUrl` (unchanged) | TODO | site URL, for example `https://<COMPANY_NAME>.sharepoint.com`; trailing `/` removed; the STS `AppliesTo` address and the base of every SharePoint request |
| `csvImportFolder` | `String` | none (`${CSVIMPORTFOLDER:}`) | `csvImportFolder` (unchanged) | TODO | folder created under *Shared Documents*, for example `my_folder`. The original README told users to replace `${csvImportFolder}` in the flow (step 7) |
| `csv-import.input-dir` | `String` (a `Path`) | `src/main/resources/input` | none: `file:inbound-endpoint path` (l.7) | default | directory polled for files |
| `csv-import.move-to-dir` | `String` (a `Path`) | `src/main/resources/output` | none: `moveToDirectory` (l.7) | default | directory each processed file is moved to |
| `csv-import.polling-frequency` | `long` (ms) | `10000` | none: `pollingFrequency` (l.7) | default | delay between polls |
| `sharepoint-online.sts-url` | `String` | `https://login.microsoftonline.com/extSTS.srf` | none: fixed inside the connector | default | Microsoft security token service of the claims sign-in, a public service URL; tests point it at WireMock |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | no HTTP listener, as in the original |

Original-to-new key map:

- Unchanged: `Sharepoint.Username`, `Sharepoint.Password`, `Sharepoint.SiteUrl` and `csvImportFolder` keep their original names. The original IT set the same four keys as system properties.
- Added: `csv-import.input-dir`, `csv-import.move-to-dir` and `csv-import.polling-frequency` carry values the original hard-coded in `file:inbound-endpoint`. `sharepoint-online.sts-url` carries the STS address the connector fixed internally.

Configuration check:

- The application starts with the four TODO keys blank. Before sending the first request of `signIn()`, `createFolder` or `addFile`, `SharepointRestClient` runs `SharepointProperties.requireValid()`.
- The check fails when any of the four keys is blank. It also fails when `Sharepoint.SiteUrl` is not an absolute `http` or `https` URL with a host and without user info, query or fragment.
- A failed check throws one `IllegalStateException` that names every offending key with its environment variable and none of the values, for example `Missing required SharePoint configuration: Sharepoint.Username (SHAREPOINT_USERNAME), Sharepoint.Password (SHAREPOINT_PASSWORD), Sharepoint.SiteUrl (SHAREPOINT_SITEURL), csvImportFolder (CSVIMPORTFOLDER). # TODO: supply them in application.yml or as environment variables.`
- No request is sent. Each polled file then fails at the `sign-in` step, is logged at ERROR and is still moved.
- The original instead could not deploy with an unresolved `${…}` placeholder.
- Supply the keys through the environment variables above, `--key=value` arguments or a local, uncommitted override file. Never commit a real value.

`src/test/resources/application-test.yml` (profile `test`, activated by surefire) supplies the four TODO keys with placeholder values and points `Sharepoint.SiteUrl` and `sharepoint-online.sts-url` at a loopback port nothing listens on. Every test that sends SharePoint requests points both at its own WireMock or, for the connection-refused case, at a closed local port; the others mock the client. Surefire also points `csv-import.input-dir` and `csv-import.move-to-dir` at `target/test-csv-import/`, so tests never touch `src/main/resources`. The project is not a connector project, so it has no `application-it.yml` and no live tier.

## Mule-to-Java mapping

| Mule construct (`importing-a-csv-file-into-ms-sharepoint`) | Java construct (`com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint`) |
|---|---|
| `sharepoint:online-connection-config username password siteUrl` (l.4) | `config/SharepointProperties` (the same keys) and `client/SharepointRestClient.signIn()` (claims sign-in) |
| `file:inbound-endpoint path moveToDirectory pollingFrequency` (l.7) | `trigger/CsvFileTrigger` over SC-5 `trigger/FileInboundPoller` |
| `set-variable fileContent = #[payload]` (l.8) | the `byte[]` handed to `service/SharepointImportService.importFile` |
| `sharepoint:folder-create url="/Shared Documents/${csvImportFolder}"` (l.10) | `SharepointRestClient.createFolder` |
| `sharepoint:file-add fileServerRelativeUrl=".../contacts.csv" overwrite="true"` (l.12) | `SharepointRestClient.addFile(..., true)` |
| `logger level="INFO"` (l.14) | SLF4J INFO in `SharepointImportService` |
| default exception strategy (none declared) | ERROR logs in `SharepointImportService` and `FileInboundPoller`, then the SC-5 move |
| `mule-deploy.properties` (loads the flow file) | `ImportingACsvFileIntoMsSharepointApplication` |
| `mule-app.properties` and the `${…}` placeholders | `src/main/resources/application.yml` |
| `ImportingCsvFileIntoMSSharepointIT` with `testflows/test-flows.xml` | `ImportingCsvFileIntoMSSharepointTest` against WireMock |

## Behaviours stubbed, externalized or changed

- `mule-module-sharepoint` 2.1.6 is replaced by hand-written SharePoint REST calls through Spring `RestClient` over the JDK HTTP client. No SharePoint SDK is used (AAP 0.6.4).
- Credentials, the site URL and the folder are externalized under their original names as TODO placeholders. The input and output directories, the polling frequency and the STS URL become keys with the original values as defaults.
- Risk (AAP 0.8.9): SharePoint Online's legacy username/password (claims/STS) sign-in may be unavailable on today's tenants, for example where legacy authentication is disabled. The unit tier uses WireMock only. Live behaviour is verified only through Tier 2A capture of the original.
- The REST request shapes above are a Tier 2A verification point, including the folder create answered for a folder that already exists. A captured fixture governs, and `client/SharepointRestClient` is changed to match.
- The original test read live SharePoint content back through `getCsvFromSharepointFlow` and deleted the folder through `deleteFolderInSharepointFlow` (`src/test/resources/testflows/test-flows.xml`). It read the credentials from `mule.test.properties`. None of these is ported: `ImportingCsvFileIntoMSSharepointTest` verifies the folder-create and file-add requests against WireMock instead.
- The original logged a failing processor through Mule's default exception strategy. The conversion logs the failing step with a secret-free reason, then the poller's ERROR line.
- Not carried (AAP 0.2.2): `mule-project.xml`, `src/main/app/mule-deploy.properties`, the key-less `src/main/app/mule-app.properties` and `src/test/resources/log4j2-test.xml`. The original has no `catalog/` folder.

## Build, run and test

Run from `java-conversions/importing-a-csv-file-into-ms-sharepoint-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # the scheduled poller, with the TODO keys supplied (below)
java -jar target/importing-a-csv-file-into-ms-sharepoint-java-1.0.0-SNAPSHOT.jar
```

Supply the four TODO keys when running, for example through their environment variables:

```sh
SHAREPOINT_USERNAME='<USERNAME>@<COMPANY_NAME>.onmicrosoft.com' \
SHAREPOINT_PASSWORD='<PASSWORD>' \
SHAREPOINT_SITEURL='https://<COMPANY_NAME>.sharepoint.com' \
CSVIMPORTFOLDER='my_folder' \
mvn spring-boot:run
```

The input and output directories are relative to the working directory. Both `mvn spring-boot:run` and the `java -jar` line above run from the project directory, so they use `src/main/resources/input` and `src/main/resources/output`, as the original did. To keep run-time files out of the source tree, pass `--csv-import.input-dir=target/run/input --csv-import.move-to-dir=target/run/output` (with `mvn spring-boot:run`, through `-Dspring-boot.run.arguments=...`).

Usage, following the original README steps 9 to 15:

1. Start the application with the keys supplied.
2. Copy the sample file into the input directory:

   ```sh
   mkdir -p src/main/resources/input
   cp src/main/resources/contacts.csv src/main/resources/input/
   ```

3. Within one polling interval (10 s) the file is moved to `src/main/resources/output/contacts.csv`, and the log shows `Input file stored in the specified folder under the 'Shared Documents' folder`.
4. In SharePoint Online, the file is under *Documents › <csvImportFolder> › contacts.csv*.
5. Stop the application and delete the folder from SharePoint.

- There is no `mvn verify` live tier: the project is not a connector project, so it has no failsafe plugin, no `*IT` class and no `application-it.yml` (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ImportingCsvFileIntoMSSharepointTest` | The ported `ImportingCsvFileIntoMSSharepointIT` (`importing-a-csv-file-into-ms-sharepoint/src/test/java/org/mule/examples/ImportingCsvFileIntoMSSharepointIT.java:91-105`), against WireMock standing in for the site and the STS. The test drops `contacts.csv` into the input directory and runs `CsvFileTrigger.pollOnce()`. The body of the recorded `Files/add` request, read as UTF-8, equals the local file under the original message "Local file and Sharepoint file should be same". The test also asserts that the polled file is moved unchanged to the output directory, and that one folder create for `/Shared Documents/<folder>` and one `Files/add(url='contacts.csv',overwrite=true)` were sent with the session cookie and form digest. |
| `service/SharepointImportServiceTest` | With a Mockito `SharepointRestClient`, the step order is sign-in → folder create → file add with overwrite, with the exact URLs. The INFO line is logged once. The folder name is used verbatim. For each step, a failure is rethrown as the same instance, skips every later step and suppresses the INFO line. Parameterized over steps and reasons, a failure produces exactly one ERROR line naming the step, target and reason, without a throwable, cookie, form digest or password. A folder name with CR, LF or TAB is logged on one line. |
| `client/SharepointRestClientTest` | Against WireMock: the sign-in request sequence, with XML-escaped credentials, builds the session. The folder-create body and headers are checked. `contacts.csv` and non-ASCII bytes are uploaded unchanged. Single quotes are doubled in the folder literal. A malformed file URL is rejected before any request. An STS fault surfaces as its reason, and a missing `FedAuth` cookie fails the sign-in. 401 from `contextinfo` and 500 from folder create propagate. A refused connection and a reset connection raise `ResourceAccessException`, the reset after one attempt with no retry. The happy path logs no secret. |
| `trigger/FileInboundPollerTest` | SC-5, copied with only its `package` line changed. A file is moved after success, or deleted without a move directory, and is consumed after a handler failure or interrupt. Neither a re-entrant nor a concurrent poll redelivers it. An unopenable file is left in place and retried. Missing directories are created and subdirectories ignored. A move replaces an existing file of the same name. The returned count is checked. |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/importing_a_csv_file_into_ms_sharepoint/FixtureParityTest.java` is ST-2, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenario and holds its fixture contract and capture procedure. The reference implementation and its format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). The scenario's `file` trigger is replayed by placing the captured input file in a temporary input directory and calling `trigger.CsvFileTrigger.pollOnce()`, with WireMock serving the recorded SharePoint and STS exchanges. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

`SCENARIOS.md` scenarios:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `file-contacts-csv-imported` | `file` | `deterministic` | drop `contacts.csv` into the input dir, run one poll through `CsvFileTrigger.pollOnce()` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`: the flow has one path and reads no clock or random value. The capture note in `SCENARIOS.md` adds a condition: the recorded sign-in carries the account's credentials, so the scenario is captured only with a dedicated sandbox account that is revoked right after capture. Otherwise the scenario stays uncaptured and is escalated per AAP 0.8.8.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| importing-a-csv-file-into-ms-sharepoint-java | importing-a-csv-file-into-ms-sharepoint | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 64 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 64 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 69 of 69 lines of `service/SharepointImportService` (the project has no `mapper` package). Total project LINE coverage: 76.9% (413 of 537 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=1`, `failed=0`.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked until the Mule EE runtime licence and the EE Maven repository credentials are confirmed (see the root `DECISIONS.md`). No fixture can be captured from the original, so parity rests on the fallback of AAP 0.8.4:

- `ImportingCsvFileIntoMSSharepointTest` carries the original committed assertion: the content that reaches SharePoint equals the local `contacts.csv`.
- `SharepointImportServiceTest`, `SharepointRestClientTest` and `FileInboundPollerTest` cover the flow's steps, the requests and the file disposition.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the connector and file-endpoint behaviour, not that its requests and log bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests:
  - the handled failure path: sign-in or folder create rejected → ERROR log, no file add, file still moved. `SharepointImportServiceTest` asserts the log and the skipped steps, and `FileInboundPollerTest` asserts that a file is moved after a handler failure.
  - the overwrite of an existing remote `contacts.csv`. Only the `overwrite=true` flag of the `Files/add` request is asserted, because replacing an existing file needs a tenant.
- Unverified scenarios, until captured: `file-contacts-csv-imported` (`deterministic`).

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenario is captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `importing-a-csv-file-into-ms-sharepoint/`.
