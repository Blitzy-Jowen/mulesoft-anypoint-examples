# Import Leads into Salesforce — Spring Boot conversion

`import-leads-into-salesforce-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`import-leads-into-salesforce`](../../import-leads-into-salesforce/) (DataWeave 1.0, Salesforce connector 7.1.0; category Third-party connector, Salesforce; file-triggered). Salesforce is reached through the SOAP Partner API of `force-partner-api` 65.0.0. The package root is `com.mulesoft.examples.import_leads_into_salesforce` and the main class is `ImportLeadsIntoSalesforceApplication`. The original folder `import-leads-into-salesforce/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A CSV file of leads (company, first name, last name, birthday, email) is uploaded to a Salesforce account, each row becoming a new Salesforce `Lead` unless a Lead with the same e-mail address already exists (`import-leads-into-salesforce/README.md`, "Example Use Case" and "How it Works"). The File connector polls an input folder every ten seconds, and each processed file is moved to an output folder. The original README describes the mapping as a DataMapper transformer; the flow itself uses a DataWeave 1.0 transformation, which this project reimplements.

The only flow file, `import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml` (`config.resources=import-leads-into-salesforce.xml`), holds one batch job, `CreateLeadsBatch` (lines 8-46). Its input phase polls the file and maps each CSV row to a Lead map; three steps then look each lead up by e-mail (`LeadExistsStep`), create the leads not found in commit groups of 200 (`LeadInsertStep`), and log every record that failed earlier (`LogFailuresStep`); `batch:on-complete` logs the record counts. The job declares no exception strategy and no transaction.

The bundled `src/main/resources/leads.csv` (176 bytes, CR record separators, no trailing terminator, byte-identical to the original) holds two leads: Ishmael Alexander of Aliquam Tincidunt Nunc LLC (`faucibus@egetmetus.org`) and Cole Burks of Eu Odio PC (`sem.egestas@mollis.org`).

## Endpoints and triggers

The project has no HTTP listener: `spring.main.web-application-type: none`, so no port is bound and no web server starts. Its one trigger is the file poller that replaces the `file:inbound-endpoint` of the job's `batch:input`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| file poll | `trigger/LeadsFileTrigger` over SC-5 `trigger/FileInboundPoller` | input `leads.input-dir` (default `src/main/resources/input`), move directory `leads.move-to-dir` (default `src/main/resources/output`), every `leads.polling-frequency` ms (default `10000`) | each file's bytes go unchanged to `service/LeadImportBatchService#importLeads(byte[])`, which runs one instance of `CreateLeadsBatch`; the file is then moved to the move directory under its own name | `file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"`, `import-leads-into-salesforce.xml:11` |

File disposition follows Mule 3.8's default file connector (SC-5), because the original declares no `file:connector`:

- `poll()` is `@Scheduled(fixedDelayString = "${leads.polling-frequency:10000}")`: the delay runs from the end of one poll to the start of the next, and the first poll runs at startup. `pollOnce()` runs one poll on demand for tests and fixture replay; production invocation stays scheduled.
- A poll creates the input and move directories when missing, then takes the regular files of the input directory in directory-listing order, unsorted and unfiltered. Each file is marked in process, so the scheduled poll and `pollOnce()` never hand the same file over twice.
- After the batch job returns, the file is moved to `leads.move-to-dir` under its own name, replacing a file of that name. A rename that fails falls back to copy-and-delete; a move that still fails is logged at WARN.
- A Salesforce failure inside a step fails records in the job, not the poll: the job completes, and the file is moved as after a success.
- A failure of the input phase (reading or mapping the CSV) ends the run before any record exists, so no step and no on-complete runs, the way Mule's default exception strategy ended the flow: one ERROR line `Exception processing file <name>: <exception class and throw site>`, then the move above. The line never carries an exception message or file content.
- A file that cannot be opened is logged at WARN (`Could not open file <path>, leaving it for the next poll: …`) and left in place, unmarked, for the next poll.
- The job declares no transaction or rollback, so no file is retried and no completed Salesforce write is repeated.
- The batch job runs synchronously inside the poll, so the file is moved after the whole job completes and the next poll waits for it. In Mule the job ran asynchronously once its input phase had loaded the records, and the file was consumed at that point.
- Relative directories resolve against the working directory: the project directory under `mvn spring-boot:run`.
- The endpoint's `responseTimeout="10000"` is not carried: a one-way file endpoint sends no reply.

### Behaviours kept from the original

The batch semantics come from SC-7 `service/BatchJobRunner`; the job definition from `service/LeadImportBatchService`.

- Steps run in declaration order, each step taking every record before the next step starts; records keep their file order.
- A lead whose e-mail already exists in Salesforce (`exists` true) is not accepted by `LeadInsertStep`: it is neither logged `Got Record` nor created, and it counts as successful.
- A record whose `Find Lead` query fails fails in `LeadExistsStep`. It is skipped by `LeadInsertStep` (default accept policy `NO_FAILURES`) and logged `Got Failure` by `LogFailuresStep`; the other records continue.
- A `create` that throws fails every record of its commit group, and each of them reaches `LogFailuresStep`.
- A Salesforce `SaveResult` that reports an error does not fail its record: the save results are not inspected, as the Mule connector returned them without raising.
- The job stops processing once more than 1000 records have failed (`max-failed-records`), with status `FAILED_PROCESS_RECORDS`: no further step runs, so `LogFailuresStep` is not reached, and on-complete still logs the counts. After on-complete, `BatchJobRunner` logs the final result at WARN, as it does for every job that did not end `SUCCESSFUL` (stopped, or failed in on-complete), while a `SUCCESSFUL` one is logged at DEBUG: `Finished job instance '<id>' of job 'CreateLeadsBatch' with status FAILED_PROCESS_RECORDS: loaded <n>, processed <n>, successful <n>, failed <n>, unprocessed <n>`. A record counts as processed once it has failed or finished `LogFailuresStep`; a record the stop left before that step is neither processed nor successful.
- Each record prints as its `LinkedHashMap`, for example `{Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}`, so the `Got Record` and `Got Failure` lines read as the original's `#[payload]`.
- Values are the strings the CSV reader produced, untrimmed and uncoerced; a column missing from a row maps to `null`.

### Connector failure modes (AAP 0.4.8)

Every Salesforce call, the lazy SOAP `login` included, runs through SC-9 `client/UpstreamCallExecutor` and is classified by `client/SalesforceFailureClassifier`. The project is file-triggered, so no caller receives a 502, 429 or 503: the classification is logged at ERROR with the upstream name `Salesforce`, and the batch job records the affected record (a failed lookup) or commit group (a failed `create`) as failed. The file is then moved as after any run.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or session expiry: an `ApiFault`/`LoginFault`, or a SOAP fault without detail, with code `INVALID_SESSION_ID` | WARN `Upstream Salesforce rejected the session [AUTHENTICATION]; re-authenticating and retrying once`, a new `login`, then one retry; a second rejection ends the attempt | ERROR `Upstream Salesforce call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, record or group failed |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | ERROR `Upstream Salesforce call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException` without `Retry-After` (Salesforce sends none), record or group failed |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried, even when the chain also holds `INVALID_SESSION_ID`; this protects the `Lead` create from being submitted twice | ERROR `Upstream Salesforce call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, record or group failed |
| Anything else (`INVALID_LOGIN`, `INVALID_FIELD`, `MALFORMED_QUERY`, …) | rethrown unchanged | the original branch: the record or commit group fails in the batch job and reaches `LogFailuresStep` |

- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- The ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.
- No failure is retried by the batch job or the poller; the only retry is the single one after a rejected session.

Measured on this build with placeholder values for the three credential keys, which the client requires before it sends a `login`, and `sfdc.auth-endpoint` pointed at a closed local port (timestamps, process ID, application name and thread removed; the job instance ID replaced by `<uuid>`):

```console
$ CLOSED_PORT=1   # any loopback port nothing listens on
$ java -jar target/import-leads-into-salesforce-java-1.0.0-SNAPSHOT.jar --leads.input-dir=target/run/in --leads.move-to-dir=target/run/out --leads.polling-frequency=2000 "--sfdc.auth-endpoint=http://127.0.0.1:${CLOSED_PORT}/services/Soap/u/65.0" --sfdc.username=placeholder --sfdc.password=placeholder --sfdc.securityToken=placeholder
$ cp src/main/resources/leads.csv target/run/in/   # in a second terminal, while the application runs
ERROR c.m.e.i.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
ERROR c.m.e.i.service.BatchJobRunner           : Found exception processing record on step 'LeadExistsStep' for job instance '<uuid>' of job 'CreateLeadsBatch': com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException at com.mulesoft.examples.import_leads_into_salesforce.client.UpstreamCallExecutor.translate(UpstreamCallExecutor.java:276) <- com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException at sun.nio.ch.Net.pollConnect(Net.java)
ERROR c.m.e.i.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
 INFO c.m.e.i.service.LeadImportBatchService   : Got Failure {Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, FirstName=Ishmael, LastName=Alexander}
 INFO c.m.e.i.service.LeadImportBatchService   : Got Failure {Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}
 INFO c.m.e.i.service.LeadImportBatchService   : 2 Loaded Records 2 Failed Records
```

Each record's lookup attempted the lazy `login` once, which failed without a retry; `BatchJobRunner` logged the first failure of the step and exception type. Afterwards `target/run/in/` was empty and `target/run/out/leads.csv` was byte-identical to the dropped file.

### Query hardening (AAP 0.4.10)

The original interpolated the e-mail address into the SOQL string literal unescaped (`import-leads-into-salesforce.xml:28`). `LeadImportBatchService` escapes it first: each `\` becomes `\\`, then each `'` becomes `\'`, so an inserted escape is never escaped again. The query of every legitimate address is unchanged and returns the same rows; a hostile address can no longer end the literal. This is the only intended difference for hostile input, recorded in the root `DECISIONS.md`, section [8. Query hardening](../../DECISIONS.md#8-query-hardening).

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2), not an OAuth grant, so no connected app is needed. The client logs in lazily on the first Salesforce call, so the application starts with every secret empty.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `spring.application.name` | `String` | `import-leads-into-salesforce-java` | none | fixed | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | the original has no HTTP listener, so no web server starts and no port is bound |
| `leads.input-dir` | `String` (a path) | `src/main/resources/input` | none; the original hard-coded `path` on the file endpoint (xml:11) | default | directory polled for CSV files |
| `leads.move-to-dir` | `String` (a path) | `src/main/resources/output` | none; the original hard-coded `moveToDirectory` (xml:11) | default | directory each polled file is moved to under its own name |
| `leads.polling-frequency` | `long` (ms) | `10000` | none; the original hard-coded `pollingFrequency` (xml:11) | default | fixed delay between the end of one poll and the start of the next; a zero, negative or unreadable value stops start-up with an error naming the key and the value |
| `sfdc.username` | `String` | none, `${SFDC_USERNAME:}` | **`sfdc.user`** (xml:4) | TODO | the Salesforce user the `login` call authenticates; renamed because the request's key names govern (AAP 0.4.6) |
| `sfdc.password` | `String` | none, `${SFDC_PASSWORD:}` | `sfdc.password` (unchanged, xml:4) | TODO (secret) | that user's password, without the security token |
| `sfdc.securityToken` | `String` | none, `${SFDC_SECURITYTOKEN:}` | `sfdc.securityToken` (unchanged, xml:4) | TODO (secret) | appended to the password at login; `sfdc.security-token` binds as well |
| `sfdc.auth-endpoint` | `String` | `https://login.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL | default | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (AAP 0.4.8), the Mule default response timeout; a bare number binds as milliseconds |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call (AAP 0.4.8), with the same rules |

- Original-to-new key map: `sfdc.user` → `sfdc.username`; `sfdc.password` and `sfdc.securityToken` keep their names. The live test reads the same three keys from `src/test/resources/application-it.yml`, where the original test read `sfdc.user`, `sfdc.password` and `sfdc.securityToken` from `mule.test.properties`.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and the token.
- The project has no `src/test/resources/application-test.yml`: the unit tests mock Salesforce or stub it with WireMock and need no credential. Surefire and failsafe set `leads.input-dir`, `leads.move-to-dir` (under `target/test-leads/` and `target/it-leads/`) and `leads.polling-frequency` (`3600000`) as system properties, so no test polls or moves files in `src/main/resources/input` or `src/main/resources/output`.
- No credential appears in any committed file. Supply the credentials through the environment variables `SFDC_USERNAME`, `SFDC_PASSWORD` and `SFDC_SECURITYTOKEN`:

  ```sh
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
  # a sandbox org, with the directories under target/
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' java -jar target/import-leads-into-salesforce-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=https://test.salesforce.com/services/Soap/u/65.0 --leads.input-dir=target/run/in --leads.move-to-dir=target/run/out
  ```

  An external `application.yml` (for example `'--spring.config.additional-location=<file>'`) outside the repository works as well.

## Mule-to-Java mapping

| Mule construct (`import-leads-into-salesforce`) | Java construct (`com.mulesoft.examples.import_leads_into_salesforce`) |
|---|---|
| `sfdc:config` with `${sfdc.user}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:4) | SC-15 `client/SalesforcePartnerClient` in login mode (SOAP Partner `login` with the username and the password followed by the security token, the original's protocol; AAP 0.10.2), configured by `config/SalesforceProperties` (`sfdc.username`, `sfdc.password`, `sfdc.securityToken`); every call runs through SC-9 `client/UpstreamCallExecutor` |
| `sfdc:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` (xml:5) | one cached `PartnerConnection`, created lazily on the first call and re-created on re-authentication |
| `batch:job name="CreateLeadsBatch" max-failed-records="1000"` (xml:8) | `service/LeadImportBatchService` (`JOB_NAME`, `MAX_FAILED_RECORDS`) on SC-7 `service/BatchJobRunner`; each run returns a `model/BatchJobResult` |
| `batch:threading-profile poolExhaustedAction="WAIT"` (xml:9) | not reproduced: `BatchJobRunner` processes the records sequentially on the polling thread, in file order |
| `file:inbound-endpoint` (xml:11) | `trigger/LeadsFileTrigger` (`poll()`, `pollOnce()`) over SC-5 `trigger/FileInboundPoller` |
| `dw:transform-message` `%dw 1.0` / `%output application/java` / `payload map { Company: $.Company, Email: $.Email, FirstName: $.FirstName, LastName: $.LastName }` (xml:12-22, header line 13) | SC-4 `mapper/DwCsvReader#read` (header row, comma separator, `"` quoting, string values), then `mapper/LeadMapper#map`: one `LinkedHashMap` per row with the keys `Company`, `Email`, `FirstName`, `LastName` in that order; the CSV's `Birthday` column is dropped, as in the original |
| `batch:step name="LeadExistsStep"`: `enricher source="#[payload.size() > 0]" target="#[recordVars['exists']]"` over `sfdc:query` `SELECT Id FROM Lead WHERE Email = '#[payload["Email"]]'` (xml:26-30) | step `LEAD_EXISTS_STEP`: `SalesforcePartnerClient#query` with the record's `Email` escaped inside the SOQL literal; the record variable `exists` (`EXISTS_VAR`) is `true` when Salesforce returned a row |
| `batch:step name="LeadInsertStep" accept-expression="#[!recordVars['exists']]"` (xml:31) | step `LEAD_INSERT_STEP` with the accept expression "`exists` is not `true`" |
| `logger level="INFO" message="Got Record #[payload], it exists #[recordVars['exists']]"` (xml:32) | INFO `Got Record <record>, it exists <exists>` |
| `batch:commit size="200"` with `sfdc:create type="Lead"` and `#[payload]` (xml:33-37) | commit of `COMMIT_SIZE` (200): one `SalesforcePartnerClient#create("Lead", group)` per group of up to 200 records, the last group smaller |
| `batch:step name="LogFailuresStep" accept-policy="ONLY_FAILURES"` with `logger level="INFO" message="Got Failure #[payload]"` (xml:39-41) | step `LOG_FAILURES_STEP` with `AcceptPolicy.ONLY_FAILURES`: INFO `Got Failure <record>` |
| `batch:on-complete` with `logger level="INFO" message="#[payload.loadedRecords] Loaded Records #[payload.failedRecords] Failed Records"` (xml:43-45) | on-complete callback: INFO `<loaded> Loaded Records <failed> Failed Records` from the `BatchJobResult` |
| Mule batch record-failure log | `BatchJobRunner`: ERROR `Found exception processing record on step '<step>' for job instance '<id>' of job 'CreateLeadsBatch': <exception classes>`, once per step and exception type |
| Mule default exception strategy (none declared) | `FileInboundPoller`, for a failure of the input phase: ERROR log, then the move to `leads.move-to-dir` |
| `mule-deploy.properties` (`config.resources=import-leads-into-salesforce.xml`) | `ImportLeadsIntoSalesforceApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (comments only) | `src/main/resources/application.yml` |
| `ImportLeadsToSfdcIT` (`FunctionalTestCase`, `MuleClient`, live org) | `ImportLeadsToSfdcTest` (surefire, mocked client) and `ImportLeadsToSfdcIT` (failsafe, live org) |
| test flows `selectLeadFromSalesforce` (`sfdc:query-single`) and `deleteLeadFromSalesforce` (`sfdc:delete`) of `src/test/resources/testflows/test-flows.xml` | `SalesforcePartnerClient#query` and `SalesforcePartnerClient#delete` inside `ImportLeadsToSfdcIT` |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (register in the root [`DECISIONS.md`](../../DECISIONS.md)):

- This project holds the reference implementation of **SC-7**: `service/BatchJobRunner`, `model/BatchJobResult` and their test `service/BatchJobRunnerTest`. `salesforce-data-synchronization-using-watermarking-and-batch-processing-java` and `salesforce-to-MySQL-DB-using-Batch-Processing-java` copy all three with only the package name changed (the `package` line and the `model.BatchJobResult` import). Nothing in them is specific to this job.
- It carries copies, each with only its `package` line changed and with its tests:
  - SC-4 `mapper/DwCsvReader` (test `mapper/DwCsvReaderTest`) from `import-contacts-into-salesforce-java`;
  - SC-5 `trigger/FileInboundPoller` (tests `trigger/FileInboundPollerTest`, `trigger/FileInboundPollerLogSafetyTest`) from `processing-orders-with-dataweave-and-APIkit-java`;
  - SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException` (test `client/UpstreamCallExecutorTest`) from `salesforce-data-retrieval-java`, without `UpstreamExceptionHandler`: there is no HTTP caller to answer;
  - SC-15 `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier` and `config/SalesforceProperties` (tests `client/SalesforcePartnerClientTest`, `client/SalesforceFailureClassifierTest`) from `salesforce-data-retrieval-java`;
  - ST-2 `FixtureParityTest` and the `SCENARIOS.md` format from `hello-world-java`.
- It carries no SC-1 and no ST-1, because it has no HTTP listener.

## Behaviours stubbed or externalized

This section also lists the behaviours the conversion changed.

- Externalized: the Salesforce credentials, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; `sfdc.user` is renamed `sfdc.username`. The file endpoint's path, move directory and polling frequency, hard-coded in the original, are configurable keys with the original values as defaults; the login URL and timeouts, defaults of the original connector, are configurable keys.
- Changed:
  - Mule's connection pooling (`WHEN_EXHAUSTED_GROW`, `INITIALISE_ONE`) is replaced by one cached `PartnerConnection`, re-created when Salesforce rejects the session.
  - The batch threading profile is not reproduced: records run sequentially in file order on the polling thread, and the job completes before the file is moved (Endpoints and triggers).
  - The e-mail address is escaped inside the SOQL literal (Query hardening).
  - The approved failure handling of AAP 0.4.8 is added (Connector failure modes).
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` (`ImportLeadsToSfdcTest`, service and trigger tests) or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`). At run time nothing is stubbed and no behaviour was dropped.
- Test harness: the test flows `selectLeadFromSalesforce` and `deleteLeadFromSalesforce` are replaced by `SalesforcePartnerClient#query` and `#delete` inside `ImportLeadsToSfdcIT`, and the original's fixed 8000 ms sleep by a direct `pollOnce()` plus bounded Awaitility waits.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/main/app/mule-deploy.properties`, `src/test/resources/log4j2-test.xml` and the test flows file (AAP 0.2.2).

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- The text of the `Got Record`, `Got Failure` and on-complete log lines, including how `#[payload]` and `recordVars['exists']` render, and their logger category: the original loggers declare no `category`, while the Java code logs under `service.LeadImportBatchService`.
- A record without an `Email` value: the address renders as `null` inside the SOQL literal (`… WHERE Email = 'null'`), as MEL string interpolation did.
- An empty or header-only CSV: the job loads no record, calls no Salesforce operation, and on-complete logs `0 Loaded Records 0 Failed Records` with status `SUCCESSFUL`.
- Record order across the steps and commit groups: the original ran records on a thread pool, so the order of the per-record queries, of the log lines and of the records inside each commit group was not fixed; SC-7 keeps file order.
- When the file is moved: after the whole job here, after the input phase in the original.
- The number and timing of Salesforce `login` exchanges (the pool's `INITIALISE_ONE` may log in at deployment), and the exact SOAP envelopes of API 65.0, where the original connector spoke an older API version.

## Build, run and test

Run from `java-conversions/import-leads-into-salesforce-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network
mvn -B verify           # surefire tests, then the failsafe ImportLeadsToSfdcIT against a live org (Tier 2B)
mvn spring-boot:run     # polls src/main/resources/input every 10000 ms until stopped; needs sfdc.username, sfdc.password and sfdc.securityToken
java -jar target/import-leads-into-salesforce-java-1.0.0-SNAPSHOT.jar
```

To import the sample, as the original README's steps did, copy the bundled file into the input directory while the application runs:

```sh
mkdir -p src/main/resources/input
cp src/main/resources/leads.csv src/main/resources/input/
```

Within one polling interval the file moves to `src/main/resources/output/leads.csv`, and Salesforce lists Ishmael Alexander and Cole Burks under **Leads** (**All Open Leads**). Dropping the file again creates nothing, because `LeadExistsStep` finds both e-mail addresses. Delete both Leads afterwards. The default input and output directories lie inside the source tree and are not git-ignored; remove them after a run, or pass `--leads.input-dir` and `--leads.move-to-dir` under `target/`. For a large file, copy it next to the input directory first and then move it in, so a poll never reads a half-written file.

- `mvn test` needs no licence, credential or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub.
- Without the three `sfdc.*` values the application still starts, but every lookup fails before any Salesforce request: the lazy `login` first checks that `sfdc.username`, `sfdc.password` and `sfdc.securityToken` are non-blank and throws an `IllegalStateException` naming each missing key, never a value. That, like a rejected login (`INVALID_LOGIN`), is none of the three failure modes, so it fails each record in `LeadExistsStep`; the records are logged `Got Failure`, the counts are logged, and the file is moved.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

### Live test (Tier 2B)

`ImportLeadsToSfdcIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials; no new sandbox is procured.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Run `mvn -B verify`. The test starts the application without a web server under the Spring profile `it`, which loads `application-it.yml`.

The IT writes data, so use a sandbox org, never production:

- It drops `leads.csv` into its own temporary input directory and runs `LeadsFileTrigger.pollOnce()`, which creates Ishmael Alexander and Cole Burks in the org unless Leads with their e-mail addresses already exist. It waits up to 90 s for the processed file to reach the move directory.
- It reads each Lead back by e-mail with the original test flow's SOQL `SELECT FirstName,Id,LastName FROM Lead WHERE Email = '<email>'`, repeating the query for up to 90 s, and asserts `FirstName` `Ishmael` for `faucibus@egetmetus.org` and `LastName` `Burks` for `sem.egestas@mollis.org`, as the original asserted (`ImportLeadsToSfdcIT.java:86,90`).
- `@AfterEach` queries both e-mails once more and deletes every Lead read back for them, one an earlier failed run left behind included. A failed query or delete is logged at WARN, never thrown, as the original only printed it: the line names the test e-mail or the Lead IDs and only the failure's class names or the Salesforce status codes, never a message or error detail, which can carry record fields, SOQL values or tokens.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption: reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence and is never counted as a pass.


## Tests

| Test class | What it asserts |
|---|---|
| `ImportLeadsToSfdcTest` | the ported `ImportLeadsToSfdcIT` (`import-leads-into-salesforce/src/test/java/org/mule/examples/ImportLeadsToSfdcIT.java:71-90`) with the whole application context against a mocked `SalesforcePartnerClient` that reports neither address as existing: `leads.csv` dropped into a temporary input directory and `pollOnce()` lead to exactly one `create` of type `Lead` with two records, `FirstName` `Ishmael` for `faucibus@egetmetus.org` and `LastName` `Burks` for `sem.egestas@mollis.org`; also every field of both records, one `Find Lead` query per address, the file moved unchanged to the output directory, and no other Salesforce call |
| `ImportLeadsToSfdcIT` | the same two assertions against a live org (failsafe, previous section) |
| `ImportLeadsToSfdcITCleanupTest` | the live test's `@AfterEach` without credentials or a Spring context, against a mocked `SalesforcePartnerClient`: a failed query or delete, and a failed delete result, whose messages, causes, suppressed exceptions and error details carry a token, a password and Lead fields, logged at WARN by class names (at most five, then `...`; `[circular]` on a repeated cause) or Salesforce status codes only, with no throwable attached; every Lead still deleted; a Lead query answered only after the live wait's timeout answered on the thread running the wait, before the wait ends, and its Lead deleted by the cleanup |
| `trigger/LeadsFileTriggerTest` | `leads.csv` handed byte for byte to the import once and moved to the output directory; an unavailable Salesforce and any other import failure logged at ERROR, not retried, file still moved; an empty input directory hands nothing over; `poll()` scheduled with the configured fixed delay and processing a dropped file like `pollOnce()` |
| `service/LeadImportBatchServiceTest` | both leads of `leads.csv` new and created in one commit; a lead whose e-mail exists not created again; a failed lookup failing only its record, logged `Got Failure`, not retried; a failed `create` failing every record of its group; more than 200 new leads created in groups of 200 and a smaller last group; the e-mail escaped inside the SOQL literal; a header-only file loading no record and never calling Salesforce; 1000 failed records completing and 1001 stopping the job; the original job name, limits and step order |
| `mapper/LeadMapperTest` | the four keys in script order; one lead per row in input order; extra columns such as `Birthday` dropped; a missing column gives `null`, an empty or `null` row four `null` values; values passed through untouched; the carried `leads.csv` mapped to Ishmael Alexander and Cole Burks |
| `service/BatchJobRunnerTest` | SC-7 reference test: accept policies (`NO_FAILURES` default, `ONLY_FAILURES`) checked before the accept expression; record variables and replaced payloads reaching later steps; failures kept under their step's name; a failing commit failing its group; `max-failed-records` of 0 (default), -1 and above the failed count; on-complete run once, a failing on-complete giving `FAILED_ON_COMPLETE`; an input-phase exception propagated with nothing else run; empty input completing with zero counts; a stop in the last step counting as processed only the failed record and the records that completed that step, without a commit, with a commit group pending and after a committed group; the final result logged once after on-complete, at WARN with status and loaded, processed, successful, failed and unprocessed counts for a job that did not end `SUCCESSFUL` (stopped, or failed in on-complete), and at DEBUG, with no WARN, for a `SUCCESSFUL` one; one ERROR line per exception class and step |
| `mapper/DwCsvReaderTest` | SC-4: header row, `,` separator, `"` quoting with its escapes, LF, CRLF and lone-CR record ends (the carried `leads.csv` uses CR), skipped empty lines, untrimmed string values |
| `trigger/FileInboundPollerTest`, `trigger/FileInboundPollerLogSafetyTest` | SC-5: move after success and after a handled failure, delete without a move directory, the interrupt flag kept, the in-process mark against re-entrant and concurrent polls, an unopenable file left in place and retried, a file too large to read (over the read limit, or one whose allocation fails with `OutOfMemoryError`) left in place while the other files are consumed, missing directories created, subdirectories ignored, a same-named file replaced; every diagnostic one line, without exception messages or file content, an `OutOfMemoryError` read failure included |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, one lazily created connection reused, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, session mode; one re-login and retry after `INVALID_SESSION_ID`, no retry after `REQUEST_LIMIT_EXCEEDED`, a read timeout that never re-sends the `create`, `INVALID_LOGIN` rethrown unchanged; the production login endpoint and 10 s default timeouts; concurrent callers sharing the one login, re-login or session refresh in flight, its timeout included; paging that fails, with no further `queryMore`, on a repeated query locator, records beyond the reported size or pages beyond what that size allows |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, the unchanged rethrow of every other failure, ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `file` trigger the replay places the captured input file, calls `LeadsFileTrigger.pollOnce()` and compares the produced files, the SC-5 disposition, the job's log lines and the recorded Salesforce SOAP exchanges, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Branch | Status in the last run |
|---|---|---|---|---|
| `file-leads-csv-all-new` | `file` | `deterministic` | both leads new and created in one commit group | `UNVERIFIED-uncaptured` |
| `file-leads-csv-one-existing` | `file` | `deterministic` | `faucibus@egetmetus.org` exists, so `LeadInsertStep` skips it | `UNVERIFIED-uncaptured` |
| `file-leads-csv-find-lead-fails` | `file` | `deterministic` | one `Find Lead` query fails, the record reaches `LogFailuresStep` | `UNVERIFIED-uncaptured` |
| `file-leads-csv-insert-fails` | `file` | `deterministic` | the `create` fails, every record of the group reaches `LogFailuresStep` | `UNVERIFIED-uncaptured` |
| `file-leads-csv-two-commit-groups` | `file` | `deterministic` | 201 new leads, created in groups of 200 and 1 | `UNVERIFIED-uncaptured` |

- Every scenario drops its CSV into the input directory and runs `LeadsFileTrigger.pollOnce()`. No scenario of this project is `unreachable` or `nondeterministic`.
- The recorded Salesforce `login` carries live credentials and returns the org's live instance `serverUrl`, which the replay would follow. The capture therefore omits `upstream`, so each scenario stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, "Notes for this project"). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 5 unverified`.
- The approved failure modes have no original branch and no fixture row; their Tier 1 evidence is the classifier, executor, service and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| import-leads-into-salesforce-java | import-leads-into-salesforce | Third-party connector | PASS | PASS | 98.6% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

The root `SUMMARY.md` carries the same row for this project (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 800 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`ImportLeadsToSfdcIT`: `src/test/resources/application-it.yml not supplied (copy application-it.yml.example); live Salesforce test skipped`).
- `mvn -B clean package`: `BUILD SUCCESS`; 800 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 800 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 341 of 346 lines (98.6%) of `service/LeadImportBatchService`, `service/BatchJobRunner` with its nested classes, `mapper/LeadMapper` and `mapper/DwCsvReader`. Total project LINE coverage: 96.4% (995 of 1032 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `ImportLeadsToSfdcIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md) ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `ImportLeadsToSfdcTest` carries the original committed assertions with their comparison semantics: `FirstName` equals `Ishmael` for `faucibus@egetmetus.org` and `LastName` equals `Burks` for `sem.egestas@mollis.org`, after `leads.csv` is imported.
- The trigger, poller, service, mapper, runner and client tests cover every branch of the job and the approved failure handling.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the flow and of the platform defaults, but not that its SOAP requests, log lines and file handling equal what the original runtime did. The Salesforce side of these tests is mocked or stubbed, not recorded from the original's org.
- Branches no original assertion covers, tested only by the new tests: the existing-lead skip of `LeadInsertStep`; `LogFailuresStep`; the commit-group failure; multiple commit groups; `max-failed-records`. The file disposition to `output`, the failure-mode paths, and empty and header-only files are likewise covered only by new tests.
- Unverified scenarios, until captured: `file-leads-csv-all-new`, `file-leads-csv-one-existing`, `file-leads-csv-find-lead-fails`, `file-leads-csv-insert-fails`, `file-leads-csv-two-commit-groups`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `import-leads-into-salesforce/`.
