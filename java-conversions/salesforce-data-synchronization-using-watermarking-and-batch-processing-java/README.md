# Salesforce Data Synchronization Using Watermarking and Batch Processing — Spring Boot conversion

`salesforce-data-synchronization-using-watermarking-and-batch-processing-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`salesforce-data-synchronization-using-watermarking-and-batch-processing`](../../salesforce-data-synchronization-using-watermarking-and-batch-processing/) (Salesforce connector 7.1.0; category Third-party connector, Salesforce; poll-triggered, no HTTP listener: `spring.main.web-application-type: none`). Salesforce is reached through the SOAP Partner API of `force-partner-api` 65.0.0. The package root is `com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing` and the main class is `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication` (`@SpringBootApplication`, `@EnableScheduling`). The original folder `salesforce-data-synchronization-using-watermarking-and-batch-processing/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The example shows data synchronization by watermarking (`salesforce-data-synchronization-using-watermarking-and-batch-processing/README.md`, lines 3-17). A watermark is the high-water mark left by a flood: the application remembers the last change it has seen and, on every run, asks only for what changed after it. Every 10 seconds it queries Salesforce CRM for the Contacts modified after the watermark, writes each one to the log, and moves the watermark to the latest modification time it received.

The only flow file, `salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml` (`config.resources=watermarking.xml`), holds the flow `triggerFlow` (lines 15-22) and the batch job `watermarkingBatch` (lines 7-14). The flow's `poll` runs the watermarked `sfdc:query` and hands the records to the job through `batch:execute`; the job's single step logs each record. The flow declares no exception strategy and no transaction, and `mule-app.properties` holds no keys.

## Endpoints and triggers

The project has no HTTP listener: no port is bound and no web server starts, so there are no endpoints and no 404 or 405 surface. Its one trigger is the scheduler that replaces the flow's `poll`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| poll | `trigger/ContactPollScheduler` | every `poll.frequency` ms (default `10000`), first run after `poll.initial-delay` ms (default `0`) | each run is one `service/ContactSyncBatchService#synchronize()`: read the watermark, query Salesforce, run the batch job, move the watermark | `poll` with `fixed-frequency-scheduler frequency="10" timeUnit="SECONDS"`, `watermarking.xml:16-17` |

- `poll()` is `@Scheduled(fixedRateString = "${poll.frequency:10000}", initialDelayString = "${poll.initial-delay:0}")`. A fixed rate keeps 10 s between poll starts, as the `fixed-frequency-scheduler` did, and the first poll runs at start-up, as the original's did ("every 10 seconds right from the start", original README line 44).
- `pollOnce()` runs one poll on demand and returns its `model/BatchJobResult`; a failed poll is rethrown unchanged. It replaces the original test's `runSchedulersOnce("triggerFlow")`, and the tests and the ST-2 fixture replay call it. Production invocation stays scheduled.
- Polls never overlap: Spring's default scheduler is single-threaded, and `synchronize()` is `synchronized`, so a `pollOnce()` never runs beside a scheduled poll. The flow's `processingStrategy="synchronous"` (line 15) gave the same guarantee.
- The batch job runs to the end inside the poll. In Mule, `batch:execute` queued the job and the flow ended once the records were loaded.

## Watermark, query and batch job

### Watermark (`watermarking.xml:18`)

- The watermark is the variable `timestamp` (`ContactSyncBatchService.WATERMARK_KEY`). While no value is stored it resolves to its `default-expression`, the SOQL date literal `YESTERDAY`, configured as `watermark.default-value`. The original README (lines 49-55) allows a precise instant such as `2015-06-17T10:00:00.000Z` instead. The default is evaluated again on every poll that finds no stored value and is never written to the store.
- The selector is `MAX` over `#[payload.LastModifiedDate]` (`SELECTOR_FIELD`), an ISO-8601 UTC string such as `2014-07-04T06:16:54.000Z`. The candidates are compared as instants, among the values of the current poll only, and the latest is stored with its original text. A record without a `LastModifiedDate` selects nothing; a date-time object rather than text is rendered in Salesforce's form `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC).
- The value is kept by SC-8 `service/WatermarkStore` in the UTF-8 `java.util.Properties` file at `watermark.store-path`, default `.mule/objectstore/watermark.properties` relative to the working directory. The root `.gitignore` entry `**/.mule/` keeps that file out of git. The file is read afresh on every access and replaced atomically through a temporary file. Like Mule's persistent object store, it survives a restart; delete the file to start again from the default.
- The watermark is updated only after successful processing: after the batch job run returns with status `SUCCESSFUL` and no failed record. A job that stopped or failed a record leaves the stored watermark unchanged, so the next scheduled poll queries from the same value and selects the same Contacts again; nothing is retried within a poll.
- It stays unchanged when the query returns no records (the store file is neither created nor rewritten) and when the query fails. A `LastModifiedDate` that is not an ISO-8601 date-time with offset fails the poll after the step has logged the records, and the watermark is kept.

### Query (`watermarking.xml:19`)

```sql
SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > <timestamp>
```

- The original's DSQL text (`dsql:SELECT …`) is valid SOQL and is sent as SOQL: `ContactSyncBatchService.buildQuery(timestamp)` appends the watermark to `QUERY_PREFIX`, and SC-15 `client/SalesforcePartnerClient#query` runs it and reads every result page (`queryMore`).
- Each record becomes the `java.util.HashMap` that SC-15 `toMap` builds, with the keys `Id`, `Name`, `LastModifiedDate` and `type`, the record type the Mule connector produced.
- The watermark is inserted unquoted, as a SOQL date or date-time literal, as in the original. Its value comes only from `watermark.default-value` or from a `LastModifiedDate` Salesforce returned, never from a caller, so the escaping of AAP 0.4.10 does not apply; the root [`DECISIONS.md`](../../DECISIONS.md), section 8, lists this query among those kept as written.
- The query is logged at DEBUG (`Querying Salesforce: <soql>`).

### Batch job (`watermarking.xml:7-14`, `:21`)

- `batch:execute name="watermarkingBatch"` runs the job `watermarkingBatch` (`JOB_NAME`) on SC-7 `service/BatchJobRunner`, with the query's records as its input phase.
- Its single step `Batch_Step` (`STEP_NAME`) logs each record at INFO as `Contact recently modified: <record>` (`LOG_PREFIX`), the text of the step's `logger` (line 11). On JDK 17 a record prints as `Contact recently modified: {LastModifiedDate=2014-07-04T06:16:54.000Z, Id=0032000001DpkrEAAR, type=Contact, Name=Avi123 Green}`.
- The job declares no `max-failed-records`, accept expression or accept policy, so the SC-7 defaults apply: `max-failed-records` 0, policy `NO_FAILURES`, every record accepted. It has no `batch:commit` and no `batch:on-complete`, as in the original.
- A query that returns nothing runs a zero-record job, which completes with status `SUCCESSFUL`.
- `pollOnce()` returns the job's `model/BatchJobResult`: job name, a random job instance ID, status and the loaded, processed, successful and failed record counts.

## Mule-to-Java mapping

| Mule construct (`salesforce-data-synchronization-using-watermarking-and-batch-processing`) | Java construct (`com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing`) |
|---|---|
| `sfdc:config` with `${sfdc.user}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:3) | `config/SalesforceProperties` (`sfdc.username`, `sfdc.password`, `sfdc.securityToken`) and SC-15 `client/SalesforcePartnerClient` in login mode: the SOAP Partner `login` with the username and the password followed by the security token, the original's protocol (AAP 0.10.2); every call runs through SC-9 `client/UpstreamCallExecutor` |
| `sfdc:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` (xml:4) | one cached `PartnerConnection`, created lazily on the first call and re-created on re-authentication |
| `poll` / `fixed-frequency-scheduler frequency="10" timeUnit="SECONDS"` (xml:16-17) | `trigger/ContactPollScheduler` (`poll()`, `pollOnce()`) |
| `watermark variable="timestamp" default-expression="YESTERDAY" selector="MAX" selector-expression="#[payload.LastModifiedDate]"` (xml:18) | SC-8 `service/WatermarkStore` (`retrieve`, `updateMax`), driven by `ContactSyncBatchService` (`WATERMARK_KEY`, `SELECTOR_FIELD`, `watermark.default-value`) |
| `sfdc:query` `dsql:SELECT Id,Name,LastModifiedDate FROM Contact WHERE LastModifiedDate > #[flowVars['timestamp']]` (xml:19) | `SalesforcePartnerClient#query` with `ContactSyncBatchService.buildQuery(timestamp)` |
| `batch:execute` / `batch:job name="watermarkingBatch"` / `batch:step name="Batch_Step"` (xml:7-14, 21) | `service/ContactSyncBatchService` (`JOB_NAME`, `STEP_NAME`) over SC-7 `service/BatchJobRunner`; each run returns a `model/BatchJobResult` |
| `batch:threading-profile poolExhaustedAction="WAIT"` (xml:8) | not reproduced: `BatchJobRunner` processes the records sequentially on the polling thread, in query order |
| `logger level="INFO" message="Contact recently modified: #[payload]"` (xml:11) | SLF4J INFO `Contact recently modified: <record>` under `service.ContactSyncBatchService` |
| `flow name="triggerFlow" processingStrategy="synchronous"` (xml:15) | Spring's single-threaded scheduler and the `synchronized` `ContactSyncBatchService#synchronize()` |
| Mule default exception strategy (none declared) | `ContactPollScheduler#poll()`: ERROR log of the failed poll, next poll still scheduled |
| `mule-deploy.properties` (`config.resources=watermarking.xml`) | `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (no keys) | `src/main/resources/application.yml` |
| `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT` with `AbstractTemplateTestCase` (`FunctionalTestCase`, live org) | `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingTest` (surefire, mocked client) and `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT` (failsafe, live org) |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (register in the root [`DECISIONS.md`](../../DECISIONS.md), section 4):

- This project holds the reference implementation of **SC-8**: `service/WatermarkStore` and its test `service/WatermarkStoreTest`. `salesforce-to-MySQL-DB-using-Batch-Processing-java` copies both with only the `package` line changed. Nothing in them is specific to this flow.
- It carries copies, each with only its `package` line changed and with its tests:
  - SC-7 `service/BatchJobRunner` and `model/BatchJobResult` (test `service/BatchJobRunnerTest`) from `import-leads-into-salesforce-java`, the `model.BatchJobResult` import following the package;
  - SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException` (test `client/UpstreamCallExecutorTest`) from `salesforce-data-retrieval-java`, without `UpstreamExceptionHandler`: there is no HTTP caller to answer;
  - SC-15 `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier` and `config/SalesforceProperties` (tests `client/SalesforcePartnerClientTest`, `client/SalesforceFailureClassifierTest`) from `salesforce-data-retrieval-java`;
  - ST-2 `FixtureParityTest` and the `SCENARIOS.md` format from `hello-world-java`.
- It carries no SC-1 and no ST-1, because it has no HTTP listener.

## Connector failure modes (AAP 0.4.8)

Every Salesforce call, the lazy SOAP `login` and each `queryMore` included, runs through SC-9 `client/UpstreamCallExecutor` with the upstream `Salesforce` and is classified by `client/SalesforceFailureClassifier`. The project is poll-triggered, so no caller receives a 502, 429 or 503: each classified failure is logged at ERROR with the upstream name.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or session expiry: an `ApiFault`/`LoginFault`, or a SOAP fault without detail, with code `INVALID_SESSION_ID` | WARN `Upstream Salesforce rejected the session [AUTHENTICATION]; re-authenticating and retrying once`, a new `login`, then one retry; a second rejection ends the attempt | ERROR `Upstream Salesforce call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, the poll fails |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | ERROR `Upstream Salesforce call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException` without `Retry-After` (Salesforce sends none), the poll fails |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried, even when the chain also holds `INVALID_SESSION_ID` | ERROR `Upstream Salesforce call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, the poll fails |
| Anything else (`INVALID_LOGIN`, `MALFORMED_QUERY`, `INVALID_FIELD`, …) | rethrown unchanged | the original WSC exception, the poll fails |

- A failed query ends the poll before `batch:execute`, so no job instance runs. The scheduled `poll()` logs ERROR `Poll of triggerFlow failed: <failure>` and returns, where `<failure>` names the failure's type identifiers only: its class with its Salesforce fault code (for example `[MALFORMED_QUERY]`) or its upstream (`[upstream Salesforce]`), the frame that threw it and the classes of its causes, never its message or stack trace; the watermark stays as it was, and the next scheduled poll runs, as Mule's poll did under the default exception strategy. `pollOnce()` rethrows the failure unchanged instead.
- A failure inside `Batch_Step` is recorded by SC-7 as a failed record (ERROR `Found exception processing record on step 'Batch_Step' for job instance '<id>' of job 'watermarkingBatch': <exception classes>`, once per step and exception type). With `max-failed-records` 0 the job then stops with status `FAILED_PROCESS_RECORDS`: after on-complete, `BatchJobRunner` logs the final result of a job that did not end `SUCCESSFUL` (stopped, or failed in on-complete) at WARN, here `Finished job instance '<id>' of job 'watermarkingBatch' with status FAILED_PROCESS_RECORDS: loaded <n>, processed <n>, successful <n>, failed <n>, unprocessed <n>`, and the watermark stays unchanged. The step only logs, so no such failure is expected.
- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- The executor's ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.
- The poll only reads, so no retry can repeat a write; the only retry is the single one after a rejected session.

Measured on this build with placeholder values for the three credential keys, which the client requires before it sends a `login`, `sfdc.auth-endpoint` pointed at a closed local port and polls every 2 s (timestamps, process ID, application name and thread removed):

```console
$ java -jar target/salesforce-data-synchronization-using-watermarking-and-batch-processing-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=http://127.0.0.1:<closed port>/services/Soap/u/65.0 --poll.frequency=2000 --watermark.store-path=target/run/watermark.properties --sfdc.username=placeholder --sfdc.password=placeholder --sfdc.securityToken=placeholder
ERROR c.m.e.s.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
ERROR c.m.e.s.trigger.ContactPollScheduler     : Poll of triggerFlow failed: com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException [upstream Salesforce] at com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.UpstreamCallExecutor.translate(UpstreamCallExecutor.java:276) <- com.sforce.ws.ConnectionException <- java.net.ConnectException
```

The first poll ran at start-up and the same pair of lines followed every 2 s; each lazy `login` attempt failed once, without a retry. No watermark file was created.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2; root `DECISIONS.md`, section 6.2), not an OAuth flow, so no connected app is needed. The client logs in lazily on the first Salesforce call, so the application starts with every secret empty.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `spring.application.name` | `String` | `salesforce-data-synchronization-using-watermarking-and-batch-processing-java` | none | fixed | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | the original has no HTTP listener, so no web server starts and no port is bound |
| `poll.frequency` | `long` (ms) | `10000` | none; the original hard-coded `frequency="10" timeUnit="SECONDS"` (xml:17) | default | fixed rate between poll starts; a zero, negative or unreadable value stops start-up with an error naming the key and the value |
| `poll.initial-delay` | `long` (ms) | `0` | none; the original's scheduler declares no start delay | default | delay before the first poll |
| `watermark.store-path` | `Path` | `.mule/objectstore/watermark.properties` | none; Mule kept the watermark in the application's persistent object store | default | SC-8 store file, relative to the working directory; git-ignored through the root `**/.mule/` entry |
| `watermark.default-value` | `String` | `YESTERDAY` | the watermark's `default-expression` (xml:18) | default | used while no watermark is stored; a SOQL date literal or an ISO-8601 UTC date-time such as `2015-06-17T10:00:00.000Z`; must not be blank |
| `sfdc.username` | `String` | none, `${SFDC_USERNAME:}` | **`sfdc.user`** (xml:3) | TODO | the Salesforce user the `login` call authenticates; renamed because the request's key names govern (AAP 0.4.6) |
| `sfdc.password` | `String` | none, `${SFDC_PASSWORD:}` | `sfdc.password` (unchanged, xml:3) | TODO (secret) | that user's password, without the security token |
| `sfdc.securityToken` | `String` | none, `${SFDC_SECURITYTOKEN:}` | `sfdc.securityToken` (unchanged, xml:3) | TODO (secret) | appended to the password at login; `sfdc.security-token` binds as well |
| `sfdc.auth-endpoint` | `String` | `https://login.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL | default | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (AAP 0.4.8), the Mule default response timeout; a bare number binds as milliseconds |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call (AAP 0.4.8), with the same rules |

- Original-to-new key map: `sfdc.user` → `sfdc.username`; `sfdc.password` and `sfdc.securityToken` keep their names. The live test reads the same three keys from `src/test/resources/application-it.yml`, where the original test read `sfdc.user`, `sfdc.password` and `sfdc.securityToken` from `mule.test.properties`.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and the token.
- The project has no `src/test/resources/application-test.yml`: the unit tests mock Salesforce or stub it with WireMock and need no credential. Surefire and failsafe set `watermark.store-path` (under `target/test-watermark/` and `target/it-watermark/`) and `poll.initial-delay` (`3600000`) as system properties, so no test writes the default store file or polls on its own schedule.
- Live credentials go only in `src/test/resources/application-it.yml`, created by copying `src/test/resources/application-it.yml.example`. It is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
- No credential appears in any committed file. Supply the credentials at run time through the environment variables `SFDC_USERNAME`, `SFDC_PASSWORD` and `SFDC_SECURITYTOKEN`:

  ```sh
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
  # a sandbox org, with the watermark file under target/
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' java -jar target/salesforce-data-synchronization-using-watermarking-and-batch-processing-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=https://test.salesforce.com/services/Soap/u/65.0 --watermark.store-path=target/run/watermark.properties
  ```

  An external `application.yml` (for example `--spring.config.additional-location=<file>`) outside the repository works as well.

## Behaviours stubbed, externalized or changed

- Externalized: the Salesforce credentials, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; `sfdc.user` is renamed `sfdc.username`. The polling frequency and the default watermark, hard-coded in the original, are configurable keys with the original values as defaults; the store location, the login URL and the timeouts, defaults of the original runtime and connector, are configurable keys.
- Changed:
  - Mule batch processed a job's records in blocks on a worker pool (`batch:threading-profile`, line 8); here they are processed sequentially in query order on the polling thread, and the job completes inside the poll.
  - Mule's internal batch-engine log lines (job instance created, steps started, instance finished) are not reproduced; `BatchJobRunner` logs its own lifecycle at DEBUG, record failures at ERROR, and, after on-complete, the final result with the status and the loaded, processed, successful, failed and unprocessed counts: at WARN for a job that did not end `SUCCESSFUL` (stopped, or failed in on-complete), at DEBUG for a `SUCCESSFUL` one.
  - DSQL runs as plain SOQL: the query text is the original's without the `dsql:` prefix.
  - The persistent object store is replaced by a properties file (`watermark.store-path`).
  - The SOQL watermark is inserted verbatim, unquoted and unescaped, as the original did; it never comes from a caller.
  - Mule's connection pooling (`WHEN_EXHAUSTED_GROW`, `INITIALISE_ONE`) is replaced by one cached `PartnerConnection`, re-created when Salesforce rejects the session.
  - The approved failure handling of AAP 0.4.8 is added (Connector failure modes).
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` (`SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingTest`, service and trigger tests) or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`). At run time nothing is stubbed and no behaviour was dropped.
- Test harness: Awaitility replaces the original `ListenerProbe`, `PipelineSynchronizeListener` and `AbstractTemplateTestCase`, which are not ported, and `BatchTestHelper`'s job-termination wait; `stopFlowSchedulers` becomes a scheduled first poll held back for an hour.
- Not carried: `mule-project.xml`, `src/main/app/mule-deploy.properties` and `src/test/resources/log4j2-test.xml` (AAP 0.2.2), and the test utilities under `src/test/java/org/mule/examples/test/utils/`.

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- The `HashMap` key order in the step's log line: Java 17 renders a record `{LastModifiedDate=…, Id=…, type=Contact, Name=…}`, while the original README's sample (line 36) shows `{Name=…, LastModifiedDate=…, Id=…, type=Contact}`. The same sample writes `contact recently modified:` in lower case, where the flow's `logger` (line 11) has `Contact recently modified:`; the flow's text is used.
- Whether Mule runs an empty batch job instance when the poll returns nothing; the Java code runs a zero-record job with status `SUCCESSFUL`.
- The logger category of the step's line: the original `logger` declares no `category`, while the Java code logs under `service.ContactSyncBatchService`.
- The order of the step's log lines, which the original's worker pool did not fix; SC-7 keeps query order.
- The number and timing of Salesforce `login` exchanges (the pool's `INITIALISE_ONE` may log in at deployment), and the exact SOAP envelopes of API 65.0, where the original connector spoke an older API version.
- The text, level and category of a failed poll's log events.

## Build, run and test

Run from `java-conversions/salesforce-data-synchronization-using-watermarking-and-batch-processing-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network
mvn -B verify           # surefire tests, then the failsafe SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT against a live org (Tier 2B)
mvn spring-boot:run     # polls Salesforce every 10000 ms until stopped; needs real SFDC_USERNAME, SFDC_PASSWORD and SFDC_SECURITYTOKEN values
java -jar target/salesforce-data-synchronization-using-watermarking-and-batch-processing-java-1.0.0-SNAPSHOT.jar
```

To watch the synchronization, as the original README's steps did (lines 32-36), edit a Contact in Salesforce while the application runs: within one polling interval the log shows `Contact recently modified: {…}` for it, and the stored watermark moves to its `LastModifiedDate`.

- `mvn test` needs no licence, credential or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub.
- Without the three `sfdc.*` values the application still starts, but every poll fails before any Salesforce request, because the lazy `login` first checks that `sfdc.username`, `sfdc.password` and `sfdc.securityToken` are non-blank and throws an `IllegalStateException` naming each missing key, never a value; the failure is logged at ERROR and no watermark is stored.
- Under `mvn spring-boot:run` the default store file lands in `.mule/objectstore/watermark.properties` of the project directory; pass `--watermark.store-path=target/run/watermark.properties` (through `-Dspring-boot.run.arguments=…`) to keep it under `target/`.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

## Live test (Tier 2B)

`SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials of the original example; no new sandbox is procured.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml` and never commit the copy.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Run `mvn -B verify`. The test starts the application without a web server under the Spring profile `it`, which loads `application-it.yml`, with a fresh, empty watermark store.

The IT writes data, so use a sandbox org, never production:

- It creates one Contact (`FirstName` `Mule`, a unique `LastName` `WatermarkIT<millis>`), then runs one poll through `ContactPollScheduler.pollOnce()` and waits for it up to 120 s, checking every 500 ms, the original's job-termination bounds.
- It asserts that the batch job succeeded, as the original did (`helper.assertJobWasSuccessful()`, `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.java:67`), with no failed record; that `Batch_Step` logged the created Contact; and that the watermark advanced from `YESTERDAY` to a value no earlier than the Contact's `LastModifiedDate`.
- `@AfterEach` deletes the Contact; when the create call timed out, it first looks the Contact up by its unique `LastName`. A failed delete is logged, never thrown.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption: reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence and is never counted as a pass.


## Tests

Every surefire test is credential-free and contacts no external system.

| Test class | What it asserts |
|---|---|
| `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingTest` | the ported `testMainFlow` of the original IT (`salesforce-data-synchronization-using-watermarking-and-batch-processing/src/test/java/org/mule/examples/integration/SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.java:59-69`), "batch job completes successfully", with the whole application context against a mocked `SalesforcePartnerClient` that returns two Contacts as `HashMap` records: one `pollOnce()`, awaited for up to 120 s every 500 ms, gives a `SUCCESSFUL` `watermarkingBatch` job; also 2 loaded, processed and successful records and 0 failed, the watermark moved to the later `LastModifiedDate`, and exactly one query, built from `YESTERDAY` |
| `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT` | the same assertion against a live org, plus the logged Contact and the advanced watermark (failsafe, previous section) |
| `trigger/ContactPollSchedulerTest` | `pollOnce()` returns the batch result and propagates a failure unchanged; `poll()` logs an unavailable Salesforce and any runtime exception at ERROR without rethrowing, naming only type identifiers, Salesforce fault codes and the upstream, never a secret, e-mail address or forged line carried by a message, cause, suppressed exception or fault; the logged cause chain stops at a cycle and after 10 causes; a zero, negative or unreadable `poll.frequency` fails construction naming the key and the value; `poll()` scheduled at a fixed rate of 10000 ms by default; the schedule keeps polling after a failed poll |
| `service/ContactSyncBatchServiceTest` | the first poll queries from the default `YESTERDAY`, a stored watermark is appended verbatim; `Batch_Step` logs each Contact at INFO in record order; the watermark moves to the latest `LastModifiedDate` only after the job ran; an empty poll leaves the watermark and its file untouched; an unsuccessful job (stopped, successful with a failed record, or failed on-complete, from a mocked runner) keeps the stored or absent watermark, so the next poll queries from the same value; a failed query (unavailable, `MALFORMED_QUERY`) propagates unchanged, without retry, keeping the watermark; a custom default is used and never persisted; the next poll queries from the advanced watermark; date-time objects stored in Salesforce's UTC form; a `null` record and one without `LastModifiedDate` select nothing; an unparseable `LastModifiedDate` fails the poll after the step and keeps the watermark; the original names and query |
| `service/WatermarkStoreTest` | SC-8 reference test: default while nothing is stored, never persisted and evaluated on every call; stored values returned and written to the file; `MAX` by instant over the current poll's candidates only, null and blank candidates ignored, a non-timestamp candidate rejected with the file untouched; no candidate stores nothing and creates no file; keys independent; values survive a restart and are read afresh; missing directories created, no temporary file left behind, UTF-8 round trip; unusable paths and failed writes fail visibly |
| `service/BatchJobRunnerTest` | SC-7: accept policies (`NO_FAILURES` default, `ONLY_FAILURES`) checked before the accept expression; record variables and replaced payloads reaching later steps; failures kept under their step's name; a failing commit failing its group; `max-failed-records` of 0 (default), -1 and above the failed count; on-complete run once, a failing on-complete giving `FAILED_ON_COMPLETE`; an input-phase exception propagated with nothing else run; empty input completing with zero counts; a stop in the last step counting as processed only the failed record and the records that completed that step, without a commit, with a commit group pending and after a committed group; the final result logged once after on-complete, at WARN with status and loaded, processed, successful, failed and unprocessed counts for a job that did not end `SUCCESSFUL` (stopped, or failed in on-complete), and at DEBUG, with no WARN, for a `SUCCESSFUL` one; one ERROR line per exception class and step |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, one lazily created connection reused, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, session mode; one re-login and retry after `INVALID_SESSION_ID`, no retry after `REQUEST_LIMIT_EXCEEDED`, a read timeout that never re-sends the call, `INVALID_LOGIN` rethrown unchanged; the production login endpoint and 10 s default timeouts |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, the unchanged rethrow of every other failure, ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `scheduler` trigger the replay seeds the SC-8 watermark store file, calls `ContactPollScheduler.pollOnce()` and compares the step's log lines and the recorded Salesforce SOAP exchanges, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Branch | Status in the last run |
|---|---|---|---|---|
| `scheduler-poll-default-watermark` | `scheduler` | `deterministic` | no stored watermark: query from `YESTERDAY`, each Contact logged, watermark set to the MAX `LastModifiedDate` | `UNVERIFIED-uncaptured` |
| `scheduler-poll-stored-watermark` | `scheduler` | `deterministic` | stored watermark used verbatim, each Contact logged, watermark advanced | `UNVERIFIED-uncaptured` |
| `scheduler-poll-no-records` | `scheduler` | `deterministic` | the query selects nothing, nothing is logged, the watermark stays as stored | `UNVERIFIED-uncaptured` |
| `scheduler-poll-malformed-watermark` | `scheduler` | `deterministic` | Salesforce rejects the query (`MALFORMED_QUERY`), no job runs, the failure is logged at ERROR, the watermark stays as stored | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`.
- The recorded Salesforce `login` carries live credentials and returns the org's live instance `serverUrl`, which the replay would follow. The capture therefore omits `upstream`, so each scenario stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, "Notes for this project"). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 4 unverified`.
- The approved failure modes have no original branch and no fixture row; their Tier 1 evidence is the classifier, executor, client, service and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| salesforce-data-synchronization-using-watermarking-and-batch-processing-java | salesforce-data-synchronization-using-watermarking-and-batch-processing | Third-party connector | PASS | PASS | 96.9% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

The root `SUMMARY.md` carries the same row for this project (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 757 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT`: `src/test/resources/application-it.yml not supplied (copy application-it.yml.example); live Salesforce test skipped`).
- `mvn -B clean package`: `BUILD SUCCESS`; 757 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 757 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 339 of 350 lines (96.9%) of `service/ContactSyncBatchService`, `service/WatermarkStore` and `service/BatchJobRunner` with its nested classes; the project has no `mapper` package. Total project LINE coverage: 96.9% (894 of 923 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=4`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md), section 9 ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingTest` carries the original committed assertion with its comparison semantics: after one poll, the batch job was successful (`helper.assertJobWasSuccessful()`).
- The trigger, service, store, runner and client tests cover every branch of the flow and the approved failure handling.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the flow and of the platform defaults, but not that its SOAP requests and log lines equal what the original runtime produced. The Salesforce side of these tests is mocked or stubbed, not recorded from the original's org.
- The original asserted only that the batch job succeeded. The empty poll, the stored watermark, the malformed watermark and the failure branches, as well as the query sent, the records logged and the watermark stored, are covered only by the new tests.
- Unverified scenarios, until captured: `scheduler-poll-default-watermark`, `scheduler-poll-stored-watermark`, `scheduler-poll-no-records`, `scheduler-poll-malformed-watermark`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `salesforce-data-synchronization-using-watermarking-and-batch-processing/`.

