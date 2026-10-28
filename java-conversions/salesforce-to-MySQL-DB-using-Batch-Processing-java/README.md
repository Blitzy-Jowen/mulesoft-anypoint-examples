# Salesforce to MySQL DB using Batch Processing — Spring Boot conversion

`salesforce-to-MySQL-DB-using-Batch-Processing-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`salesforce-to-MySQL-DB-using-Batch-Processing`](../../salesforce-to-MySQL-DB-using-Batch-Processing/) (Salesforce connector 7.1.0 and the Database connector with `mysql-connector-java` 5.1.31; category Third-party connector, Salesforce + MySQL; poll-triggered, no HTTP listener: `spring.main.web-application-type: none`). Salesforce is reached through the SOAP Partner API of `force-partner-api` 65.0.0, MySQL through Spring's `JdbcTemplate` and `mysql-connector-j` 8.3.0. The package root is `com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing` and the main class is `SalesforceToMySQLDBUsingBatchProcessingApplication` (`@SpringBootApplication`, `@EnableScheduling`). The original folder `salesforce-to-MySQL-DB-using-Batch-Processing/` stays unchanged beside this project; it is the behavioural reference and the Tier 2A capture source.

## Original example

The example synchronizes Salesforce Contacts into a MySQL database with batch processing (`salesforce-to-MySQL-DB-using-Batch-Processing/README.md`, lines 1-27). At a regular interval it queries Salesforce for new or updated Contacts and processes the returned records one at a time: it checks whether each Contact already exists in the `contact` table, updates the row if it does and inserts one if it does not, and logs a completion message once the whole batch is done. Batch processing makes the synchronization more reliable than treating the list as a whole, because an error in one record stays with that record.

It demonstrates three Mule concepts:

- Batch: the payload is split into records that the job's steps process individually.
- Database Connector: the select, insert and update against MySQL.
- Message Enricher: the database lookup adds record variables (`exists`, `dbRecord`) without replacing the Salesforce payload.

The only flow file, `salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml` (`config.resources=salesforce-to-database.xml`; `mule-app.properties` holds no keys), declares the global `sfdc:config` (line 4) and `db:mysql-config` (line 7), the batch job `salesforce-to-database-Batch` (lines 8-59) and the flow `triggerFlow` (lines 60-68), whose watermarked `poll` queries Salesforce and hands the records to the job through `batch:execute`. The flow declares no exception strategy and no transaction.

## Endpoints and triggers

The project has no HTTP listener: no port is bound and no web server starts, so there are no endpoints and no 404 or 405 surface. Its one trigger is the scheduler that replaces the flow's `poll`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| poll | `trigger/ContactPollScheduler` | every `poll.frequency` ms (default `60000`), first run after `poll.initial-delay` ms (default `0`) | each run is one `service/ContactToDatabaseBatchService#synchronize()`: resolve the watermark, query Salesforce, log the watermark, run the batch job, move the watermark | `poll` with `fixed-frequency-scheduler frequency="60" timeUnit="SECONDS"`, `salesforce-to-database.xml:61-65` |

- `poll()` is `@Scheduled(fixedRateString = "${poll.frequency:60000}", initialDelayString = "${poll.initial-delay:0}")`. A fixed rate keeps 60 s between poll starts, as the `fixed-frequency-scheduler` did, and the first poll runs at start-up, because the original scheduler declares no start delay.
- `poll()` logs a failed poll at ERROR (`Poll of triggerFlow failed: <message>`, with the stack trace) and swallows it, as Mule's default exception strategy handled a failed poll, so the next scheduled poll still runs.
- `pollOnce()` runs one poll on demand and returns its `model/BatchJobResult`; a failed poll is rethrown unchanged. It replaces the original test's `runSchedulersOnce("triggerFlow")`; the tests and the ST-2 fixture replay call it. Production invocation stays scheduled.
- Polls never overlap: Spring's default scheduler is single-threaded, and `synchronize()` is `synchronized`, so a `pollOnce()` never runs beside a scheduled poll. The flow's `processingStrategy="synchronous"` (line 60) gave the same guarantee.
- The batch job runs to the end inside the poll. In Mule, `batch:execute` queued the job and the flow ended once the records were loaded.

## Watermark, query and batch job

### Watermark (`salesforce-to-database.xml:63`)

- The watermark is the variable `timestamp` (`ContactToDatabaseBatchService.WATERMARK_KEY`). While no value is stored it resolves to the original Groovy `default-expression`, `new Date(System.currentTimeMillis() - 10000)` formatted `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in time zone UTC (`WATERMARK_PATTERN`): now minus 10 s. The default is computed afresh on every poll that finds no stored value and is never written to the store.
- The selector is `MAX` over `#[payload.LastModifiedDate]` (`SELECTOR_FIELD`). After the batch job run returns, the stored value becomes the latest `LastModifiedDate` among the polled records, compared as instants among that poll's values only, whatever the records' outcomes: a poll whose job failed a record still moves the watermark. A `LastModifiedDate` that arrives as a date-time object rather than text is stored in Salesforce's UTC form `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`; a record without one selects nothing.
- An empty poll leaves the watermark unchanged (the store file is neither created nor rewritten), and so does a failed query, which ends the poll before the update.
- The value is kept by SC-8 `service/WatermarkStore` in the UTF-8 `java.util.Properties` file at `watermark.store-path`, default `.mule/objectstore/watermark.properties` relative to the working directory. The root `.gitignore` entry `**/.mule/` keeps that file out of git. Like Mule's persistent object store, the file survives a restart; it is read afresh on every access and replaced atomically through a temporary file. Delete it to start again from the default.

### Query (`salesforce-to-database.xml:64`)

```sql
SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > <timestamp>
```

- The original's DSQL text (`dsql:SELECT …`) is valid SOQL and is sent as SOQL through the SOAP Partner `query`: `ContactToDatabaseBatchService.buildQuery(timestamp)` appends the watermark to `QUERY_PREFIX`, and SC-15 `client/SalesforcePartnerClient#query` runs it and reads every result page (`queryMore`). Each record becomes the `java.util.HashMap` SC-15 builds, keyed by Salesforce field name.
- The watermark is appended unquoted, as a SOQL date-time literal, as in the original. Its value comes only from the default expression or from a `LastModifiedDate` Salesforce returned, never from a caller, so no escaping applies.
- Right after the query, and before the batch job, the flow's logger `#[flowVars.timestamp]` (line 66) becomes the INFO line `<timestamp>` under `service.ContactToDatabaseBatchService`: the watermark the query used.

### Batch job `salesforce-to-database-Batch` (`salesforce-to-database.xml:8-59`, `:67`)

`batch:execute name="salesforce-to-database-Batch"` runs the job (`JOB_NAME`) on SC-7 `service/BatchJobRunner`, with the query's records as its input phase. The job declares no `max-failed-records`, accept expression or accept policy, so the Mule defaults apply: `max-failed-records` 0, so the job stops at the first failed record with status `FAILED_PROCESS_RECORDS`, and step accept policy `NO_FAILURES`, so a record that failed in one step is not offered to the next.

1. Step `queryExistingContactInDbStep` (`QUERY_STEP`, lines 12-35):
   - `mapper/ContactRowMapper`, the owner of the step's DataWeave script (lines 15-23), maps `Email`, `FirstName`, `LastName` and `LastModifiedDate` to `email`, `first_name`, `last_name` and `last_modified`; a missing field yields its key with a `null` value.
   - The enricher's lookup (line 28) runs `SELECT first_name,last_name,email FROM contact WHERE email=?` through `client/ContactRepository#findByEmail` and sets the record variables `exists` (`EXISTS_VAR`, row count > 0, line 32) and `dbRecord` (`DB_RECORD_VAR`, the rows, line 33).
2. Step `upsertContactInDbStep` (`UPSERT_STEP`, lines 36-54), the original `choice` in declaration order:
   - `exists == false` (line 38) → `INSERT INTO contact (first_name, last_name, email) VALUES (?,?,?)` (`ContactRepository#insert`, line 40);
   - `exists == true` and `dbRecord != null` (line 44) → `UPDATE contact SET first_name=?,last_name=? WHERE email = ?` (`ContactRepository#update`, line 46);
   - otherwise (lines 50-52) → INFO `Error with <email> contact`.
3. On-complete (lines 56-58): INFO `Batch sf->db has finished` (`ON_COMPLETE_MESSAGE`). It runs after every job run, including one that stopped on a failed record and one over an empty query.

`pollOnce()` returns the job's `model/BatchJobResult`: job name, a random job instance ID, status and the loaded, processed, successful and failed record counts. Every SQL value is a bind parameter, as the original's `db:parameterized-query` statements already were (root [`DECISIONS.md`](../../DECISIONS.md), section 8).

**Unreachable branch.** The `otherwise` branch of `upsertContactInDbStep` (lines 50-52) cannot be selected by any input. Step 1 always sets `exists` to a Boolean (`payload.size() > 0`) and `dbRecord` to the select's result list, which is never null, even when empty. A record that fails step 1 never reaches step 2 under `NO_FAILURES`. Every record step 2 receives therefore matches one of the two `when` conditions. The branch is kept exactly as declared, in the package-visible `ContactToDatabaseBatchService#upsert`. It is listed as `unreachable` in [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md) (`scheduler-poll-otherwise-branch`) and covered by a direct unit invocation in `service/ContactToDatabaseBatchServiceTest`. Following the AAP 0.8.2 rule, it is excluded from fixture capture and from the branch-coverage claim.

**Record order.** Records are processed sequentially on the polling thread, in the order the query returned them; each step takes every record before the next step starts. The original ran them in blocks on its batch thread pool (`batch:threading-profile poolExhaustedAction="WAIT"`, line 9). The only observable difference is for duplicate emails inside one poll, which is a Tier 2A verification point. Two Contacts whose emails are equal under the case-insensitive collation both find no row in step 1. Step 2 inserts the first; the second fails on the duplicate primary key, and the job stops.

## Mule-to-Java mapping

| Mule construct (`salesforce-to-MySQL-DB-using-Batch-Processing`) | Java construct (`com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing`) |
|---|---|
| `poll` + `fixed-frequency-scheduler frequency="60" timeUnit="SECONDS"` (xml:61-62) | `trigger/ContactPollScheduler`: `@Scheduled(fixedRateString = "${poll.frequency:60000}")` on `poll()`, single-run method `pollOnce()` |
| `watermark variable="timestamp" selector="MAX" selector-expression="#[payload.LastModifiedDate]"` with the Groovy `default-expression` (xml:63) | SC-8 `service/WatermarkStore` (`retrieve`, `updateMax`), driven by `ContactToDatabaseBatchService` (`WATERMARK_KEY`, `WATERMARK_PATTERN`, `SELECTOR_FIELD`) |
| `sfdc:query` `dsql:SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > #[timestamp]` (xml:64) | SC-15 `client/SalesforcePartnerClient.query` with `ContactToDatabaseBatchService.buildQuery(timestamp)`, through SC-9 `client/UpstreamCallExecutor` |
| `batch:execute` / `batch:job name="salesforce-to-database-Batch"` (xml:8-59, 67) | SC-7 `service/BatchJobRunner` driven by `service/ContactToDatabaseBatchService` (`JOB_NAME`, `QUERY_STEP`, `UPSERT_STEP`); each run returns a `model/BatchJobResult` |
| `batch:threading-profile poolExhaustedAction="WAIT"` (xml:9) | not reproduced: `BatchJobRunner` processes the records sequentially on the polling thread, in query order |
| `dw:transform-message` of `queryExistingContactInDbStep` (xml:13-24) | `mapper/ContactRowMapper` (`EMAIL`, `FIRST_NAME`, `LAST_NAME`, `LAST_MODIFIED`) |
| `db:select` / `db:insert` / `db:update` with `db:parameterized-query` (xml:27-31, 39-42, 45-48) | `client/ContactRepository` (`SELECT_BY_EMAIL`, `INSERT_CONTACT`, `UPDATE_CONTACT`) over `JdbcTemplate`, every value a bind parameter |
| `enricher` with `recordVars['exists']` and `recordVars['dbRecord']` (xml:26-34) | the record variables `exists` and `dbRecord` of the SC-7 `BatchJobRunner.BatchRecord`, set in step 1 (`EXISTS_VAR`, `DB_RECORD_VAR`) |
| `choice` / `when` / `when` / `otherwise` of `upsertContactInDbStep` (xml:37-53) | `if / else if / else` in the step-2 processor `ContactToDatabaseBatchService#upsert` |
| `logger` elements (xml:51, 57, 66) | SLF4J INFO lines with the original texts (`Error with <email> contact`, `Batch sf->db has finished`, the watermark) under `service.ContactToDatabaseBatchService` |
| `sfdc:config` with `${sfdc.user}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:4) | `config/SalesforceProperties` (`sfdc.username`, `sfdc.password`, `sfdc.securityToken`) and SC-15 `SalesforcePartnerClient` in login mode: the SOAP Partner `login` with the username and the password followed by the security token, the original's protocol (AAP 0.10.2) |
| `sfdc:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` (xml:5) | one cached `PartnerConnection`, created lazily on the first call and re-created on re-authentication |
| `db:mysql-config database="company" host="${mysql.host}" port="3306" user="${mysql.user}" password="${mysql.password}"` (xml:7) | `spring.datasource.*` in `application.yml`, assembled from `mysql.host`, `mysql.port`, `mysql.database`, `mysql.user` and `mysql.password`; Boot's auto-configured Hikari `DataSource` and `JdbcTemplate` |
| `flow name="triggerFlow" processingStrategy="synchronous"` (xml:60) | Spring's single-threaded scheduler and the `synchronized` `ContactToDatabaseBatchService#synchronize()` |
| Mule default exception strategy (none declared) | `ContactPollScheduler#poll()`: ERROR log of the failed poll, next poll still scheduled |
| `mule-deploy.properties` (`config.resources=salesforce-to-database.xml`) | `SalesforceToMySQLDBUsingBatchProcessingApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (no keys) | `src/main/resources/application.yml` |
| `SalesforceToMySQLDBUsingBatchProcessingIT` with `AbstractTemplateTestCase` (`FunctionalTestCase`, live org and MySQL) | `SalesforceToMySQLDBUsingBatchProcessingTest` (surefire, mocked Salesforce, H2) and `SalesforceToMySQLDBUsingBatchProcessingIT` (failsafe, live org, H2) |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

### Shared components (AAP 0.4.4)

Each component below is copied from its reference project with only the `package` line changed, plus the same-project imports that follow the package; its tests are copied the same way. The register is in the root [`DECISIONS.md`](../../DECISIONS.md), section 4.

- SC-7 `service/BatchJobRunner` and `model/BatchJobResult` (test `service/BatchJobRunnerTest`), from `import-leads-into-salesforce-java`.
- SC-8 `service/WatermarkStore` (test `service/WatermarkStoreTest`), from `salesforce-data-synchronization-using-watermarking-and-batch-processing-java`.
- SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException` (test `client/UpstreamCallExecutorTest`), from `salesforce-data-retrieval-java`. `UpstreamExceptionHandler` is not part of this project: there is no HTTP caller to answer.
- SC-15 `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier` and `config/SalesforceProperties` (tests `client/SalesforcePartnerClientTest`, `client/SalesforceFailureClassifierTest`), from `salesforce-data-retrieval-java`.
- ST-2 `FixtureParityTest` and the `fixtures/SCENARIOS.md` format, from `hello-world-java`.

The project carries no SC-1 and no ST-1 `HttpTestClient`, because it has no HTTP listener.

## Connector failure modes (AAP 0.4.8)

The only vendor call is the poll's Salesforce query, with its lazy SOAP `login` and its `queryMore` pages. Each runs through SC-9 `client/UpstreamCallExecutor` with the upstream `Salesforce` and is classified by `client/SalesforceFailureClassifier`. The project is poll-triggered, so no caller receives an HTTP status: each classified failure is logged at ERROR with the upstream name.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or session expiry: `ApiFault`/`LoginFault` code `INVALID_SESSION_ID` | WARN `Upstream Salesforce rejected the session [AUTHENTICATION]; re-authenticating and retrying once`, one fresh SOAP `login`, then one retry; a second rejection ends the attempt | ERROR `Upstream Salesforce call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, the poll fails |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | ERROR `Upstream Salesforce call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException` without `Retry-After` (Salesforce sends none), the poll fails |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | **never retried**, even when the chain also holds `INVALID_SESSION_ID` | ERROR `Upstream Salesforce call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, the poll fails |
| Anything else (`INVALID_LOGIN`, `MALFORMED_QUERY`, `INVALID_FIELD`, …) | rethrown unchanged | the original WSC exception, the poll fails |

- A failed query ends the poll before `batch:execute`: no job instance runs, the watermark stays unchanged, and the next scheduled poll runs normally. `poll()` logs ERROR `Poll of triggerFlow failed: <message>`; `pollOnce()` rethrows the failure unchanged instead.
- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- The executor's ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.
- The poll only reads Salesforce, so no retry can repeat a vendor write; the only retry is the single one after a rejected session.
- Database failures inside a step are record failures, captured by SC-7 with one ERROR line per step and exception type: `Found exception processing record on step '<step>' for job instance '<id>' of job 'salesforce-to-database-Batch': <exception classes>`. An example is a Contact without `Email`, whose insert violates `email NOT NULL`. Because `max-failed-records` is 0, the job then stops with status `FAILED_PROCESS_RECORDS`; on-complete still logs `Batch sf->db has finished`, and the watermark still moves.
- No other behaviour is added.

Measured on this build without credentials, with `sfdc.auth-endpoint` pointed at a closed local port and polls every 2 s (timestamps, process ID, application name and thread removed; stack trace frames left out):

```console
$ java -jar target/salesforce-to-MySQL-DB-using-Batch-Processing-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=http://127.0.0.1:<closed port>/services/Soap/u/65.0 --poll.frequency=2000 --watermark.store-path=target/run/watermark.properties
ERROR c.m.e.s.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
ERROR c.m.e.s.trigger.ContactPollScheduler     : Poll of triggerFlow failed: Failed to send request to http://127.0.0.1:<closed port>/services/Soap/u/65.0

com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException: Failed to send request to http://127.0.0.1:<closed port>/services/Soap/u/65.0
Caused by: com.sforce.ws.ConnectionException: Failed to send request to http://127.0.0.1:<closed port>/services/Soap/u/65.0
Caused by: java.net.ConnectException: Connection refused
```

The first poll ran at start-up, and the same pair of lines followed every 2 s. Each lazy `login` attempt failed once, without a retry, and no watermark file was created.

A second run used a WireMock Partner SOAP stub (one `login`, one `query` answering one Contact) and a MySQL 8 server holding the `contact` table of `contact.sql`. The first poll logged the default watermark, 10 s before the poll, then `Batch sf->db has finished`, and inserted the row. The stored watermark became the Contact's `LastModifiedDate`. Later polls queried from that stored value and updated the row's `first_name` after the stub changed it. A Contact without `Email` logged the `DataIntegrityViolationException` record failure above for `upsertContactInDbStep`, then the on-complete line.

## Configuration keys (AAP 0.4.6, 0.6.6)

Every key of `src/main/resources/application.yml`. A `TODO` key has no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2; root `DECISIONS.md`, section 6.2), not an OAuth flow, so no connected app is needed. Nothing connects at start-up: the Salesforce client logs in lazily on the first poll, and Hikari opens its first MySQL connection on the first lookup.

| Key | Default | Purpose | Original name |
|---|---|---|---|
| `sfdc.username` | none — TODO, `${SFDC_USERNAME:}` | the Salesforce user the `login` call authenticates | **`sfdc.user`** (xml:4); renamed because the request's key names govern |
| `sfdc.password` | none — TODO (secret), `${SFDC_PASSWORD:}` | that user's password, without the security token | `sfdc.password` (unchanged, xml:4) |
| `sfdc.securityToken` | none — TODO (secret), `${SFDC_SECURITYTOKEN:}` | appended to the password at login; `sfdc.security-token` binds as well | `sfdc.securityToken` (unchanged, xml:4) |
| `sfdc.auth-endpoint` | `https://login.salesforce.com/services/Soap/u/65.0` | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL |
| `sfdc.connect-timeout` | `10s` | connect timeout of every Salesforce call (AAP 0.4.8), the Mule default response timeout; a bare number binds as milliseconds | none |
| `sfdc.read-timeout` | `10s` | read timeout of every Salesforce call (AAP 0.4.8), with the same rules | none |
| `mysql.host` | none — TODO, `${MYSQL_HOST:}` | MySQL server host, feeding `spring.datasource.url` | `mysql.host` (unchanged, xml:7) |
| `mysql.port` | `3306` | MySQL server port, feeding `spring.datasource.url` | none; the original hard-coded `port="3306"` (xml:7) |
| `mysql.database` | `company` | database (schema) holding the `contact` table, feeding `spring.datasource.url` | none; the original hard-coded `database="company"` (xml:7) |
| `mysql.user` | none — TODO, `${MYSQL_USER:}` | MySQL user with read and write access to `company.contact`, bound to `spring.datasource.username` | `mysql.user` (unchanged, xml:7) |
| `mysql.password` | none — TODO (secret), `${MYSQL_PASSWORD:}` | that user's password, bound to `spring.datasource.password` | `mysql.password` (unchanged, xml:7) |
| `poll.frequency` | `60000` (ms) | fixed rate between poll starts | none; the original hard-coded `frequency="60" timeUnit="SECONDS"` (xml:62) |
| `poll.initial-delay` | `0` (ms) | delay before the first poll | none; the original scheduler declares no start delay |
| `watermark.store-path` | `.mule/objectstore/watermark.properties` | SC-8 store file, relative to the working directory; git-ignored through the root `**/.mule/` entry | none; Mule kept the watermark in the application's persistent object store |
| `spring.datasource.url` | `jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}` | JDBC URL of the original `db:mysql-config` | none |
| `spring.datasource.username` / `spring.datasource.password` | `${mysql.user}` / `${mysql.password}` | the database account | none |
| `spring.sql.init.mode` | `never` | the application never creates the schema, as the original did not | none |
| `spring.main.web-application-type` | `none` | the original has no HTTP listener, so no web server starts and no port is bound | none |
| `spring.application.name` | `salesforce-to-MySQL-DB-using-Batch-Processing-java` | application name in log lines | none |

- Original-to-new key map: `sfdc.user` → `sfdc.username`. Every other placeholder keeps its original name (`sfdc.password`, `sfdc.securityToken`, `mysql.host`, `mysql.user`, `mysql.password`).
- The original test read `sfdc.user`, `sfdc.password` and `sfdc.securityToken` from `src/test/resources/mule.test.properties`, together with `database.host`, `database.user`, `database.password` and `database.url` for its `MySQLDbCreator`. The live test reads the three Salesforce keys, under their new names, from `application-it.yml`. The `database.*` keys have no equivalent: H2 in MySQL mode replaces the live MySQL server in every test.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and the token.
- `src/test/resources/application-test.yml` (Spring profile `test`) holds no credential. It points the datasource at an in-memory H2 database in MySQL mode, one per Spring test context, initialised from `classpath:contact.sql` (`spring.sql.init.mode: always`), and it holds the scheduled poll back for an hour (`poll.initial-delay: 3600000`). Surefire and failsafe also set `watermark.store-path` (under `target/test-watermark/` and `target/it-watermark/`) and `poll.initial-delay` (`3600000`) as system properties, so no test writes the default store file or polls on its own schedule.
- No credential appears in any committed file. Supply the values at run time through the environment variables `MYSQL_HOST`, `MYSQL_USER`, `MYSQL_PASSWORD`, `SFDC_USERNAME`, `SFDC_PASSWORD` and `SFDC_SECURITYTOKEN`, `--key=value` arguments, or an external `application.yml` outside the repository (`--spring.config.additional-location=<file>`).

### Database setup

The project expects the MySQL schema `company` with the table `contact`, created from `src/main/resources/contact.sql`, which is copied unchanged from the original (`first_name`, `last_name`, and `email` NOT NULL as primary key, all `varchar(45)`, collation `utf8_slovak_ci`). The application never creates the schema itself (`spring.sql.init.mode: never`), as the original did not. Run from this project directory, with an account that may create the schema:

```sh
mysql -u '<admin user>' -p -e 'CREATE DATABASE company'
mysql -u '<admin user>' -p company < src/main/resources/contact.sql
```

Then grant the account configured as `mysql.user` read and write access to `company.contact`. The original README's inline SQL (`salesforce-to-MySQL-DB-using-Batch-Processing/README.md`, lines 57-65) differs from `contact.sql`. It adds an `ID INT(11) NOT NULL AUTO_INCREMENT` column and uses `varchar(255)` columns. It also opens with `USE comapany;`, a typo, and inserts its sample row into `contacts`, not `contact`. The carried `contact.sql` governs, because it is the script the original's own test ran (`MySQLDbCreator` over `src/main/resources/contact.sql`). It inserts no row; a starting row such as the README's sample may be inserted by hand.

### Credentials file for the live test

Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml` and fill in `sfdc.username`, `sfdc.password` and `sfdc.securityToken` (and `sfdc.auth-endpoint` for a sandbox org). The copy is git-ignored by this project's `.gitignore` (`src/test/resources/application-it.yml`) and by the root `.gitignore` (`**/src/test/resources/application-it.yml`). Never commit it. No MySQL key is needed there: the live test uses the H2 database of profile `test`.

## Behaviours stubbed, externalized or changed

- Externalized: the Salesforce and MySQL credentials and the MySQL host, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; `sfdc.user` is renamed `sfdc.username`. The MySQL port and database name, the polling frequency and the default watermark lag were hard-coded in the original. The first three are configurable keys with the original values as defaults; the 10 s lag stays in code. The store location, the login URL and the timeouts, defaults of the original runtime and connector, are configurable keys.
- Changed:
  - Mule batch processed a job's records in blocks on a worker pool (`batch:threading-profile`, line 9). Here they are processed sequentially in query order on the polling thread, and the job completes inside the poll.
  - Mule's internal batch-engine log lines (job instance created, steps started, instance finished) are not reproduced. `BatchJobRunner` logs its own lifecycle at DEBUG only, and record failures at ERROR.
  - DSQL runs as plain SOQL: the query text is the original's without the `dsql:` prefix.
  - The persistent object store is replaced by a properties file (`watermark.store-path`).
  - Mule's connection pooling (`WHEN_EXHAUSTED_GROW`, `INITIALISE_ONE`) is replaced by one cached `PartnerConnection`, re-created when Salesforce rejects the session.
  - The approved failure handling of AAP 0.4.8 is added (Connector failure modes).
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`), and MySQL with H2 in MySQL mode seeded from `contact.sql`. At run time nothing is stubbed and no behaviour was dropped.
- Not carried: `mule-project.xml`, `src/main/app/mule-deploy.properties`, `src/main/resources/log4j2.xml`, `src/test/resources/log4j2-test.xml` and `src/test/resources/sample_data/` (AAP 0.2.2), and the original test helpers listed under Tests.

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- Empty poll: whether Mule's batch engine runs a job instance and its on-complete logger for an empty record set (`scheduler-poll-no-records`). The Java code runs a zero-record job with status `SUCCESSFUL` and logs `Batch sf->db has finished`.
- Record order: the original's worker pool processed records in parallel blocks, while SC-7 runs them sequentially in query order. This matters only for duplicate emails inside one poll (Record order, above).
- Watermark-update timing: in Mule, `batch:execute` queued the job asynchronously and the poll's flow, with its watermark update, could end before the job had processed the records. The Java code moves the watermark after the job run returns. Whether the original moves the watermark after a poll whose job failed a record is part of this point; the Java code moves it whatever the job's status.
- String comparison: H2 in the tests compares the `email` key case-insensitively (`IGNORECASE=TRUE`) to approximate MySQL's `utf8_slovak_ci` collation, but does not reproduce that collation's accent and letter rules. A MySQL 8 server reports the collation as `utf8mb3_slovak_ci`.
- The number and timing of Salesforce `login` exchanges (the pool's `INITIALISE_ONE` may log in at deployment), and the exact SOAP envelopes of API 65.0, where the original connector spoke an older API version.
- The logger category of the flow's `logger` lines, which declare no `category` in the original, while the Java code logs under `service.ContactToDatabaseBatchService`; and the text, level and category of a failed poll's log events.
- Whether the original deploys at all while its Salesforce login endpoint refuses connections (`scheduler-poll-upstream-unavailable`).

## Build, run and test

Run from `java-conversions/salesforce-to-MySQL-DB-using-Batch-Processing-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network, H2 instead of MySQL
mvn -B verify           # surefire tests, then the failsafe SalesforceToMySQLDBUsingBatchProcessingIT against a live org (Tier 2B)
MYSQL_HOST='<host>' MYSQL_USER='<user>' MYSQL_PASSWORD='<password>' SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
java -jar target/salesforce-to-MySQL-DB-using-Batch-Processing-java-1.0.0-SNAPSHOT.jar --watermark.store-path=target/run/watermark.properties
```

- `mvn test` needs no licence, credential, MySQL server or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub, and the `contact` table lives in H2 in MySQL mode, created from the unchanged `contact.sql`.
- `mvn spring-boot:run` and `java -jar` poll Salesforce every 60000 ms until stopped and need a reachable MySQL server holding the `company` schema (Database setup) and the Salesforce credentials. The application starts without either. Without the Salesforce credentials every poll fails at the lazy `login`, is logged at ERROR and stores no watermark. Without a reachable MySQL server the first polled record fails in step 1, so the job stops, and the watermark still moves (Tier 2A verification points).
- To watch the synchronization, as the original README describes it, create or edit a Contact in Salesforce while the application runs. Within one polling interval its row appears in, or is updated in, `company.contact`, and the stored watermark moves to its `LastModifiedDate`.
- Under `mvn spring-boot:run` the default store file lands in `.mule/objectstore/watermark.properties` of the project directory; pass `-Dspring-boot.run.arguments=--watermark.store-path=target/run/watermark.properties` to keep it under `target/`.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

## Live test (Tier 2B)

`SalesforceToMySQLDBUsingBatchProcessingIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials of the original example; no new sandbox is procured. MySQL is replaced by the in-memory H2 database of profile `test`.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml` and never commit the copy.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Run `mvn -B verify`. The test starts the application without a web server under the Spring profiles `test` and `it`; profile `it` loads `application-it.yml`.

The IT writes data, so use a sandbox org, never production:

- It creates one Contact with a unique `LastName` (`Contact Test Name<millis>`) and `Email` (`Contact<millis>@test.com`) through `SalesforcePartnerClient.create`, as the original built it. It then runs one poll through `ContactPollScheduler.pollOnce()` and waits for it up to 120 s, checking every 500 ms, the original's job-termination bounds.
- It asserts what the original asserted (`SalesforceToMySQLDBUsingBatchProcessingIT.java:113,127-128`): the batch job was successful, with no failed record, and exactly one `contact` row has the Contact's `last_name`.
- `@AfterEach` deletes the Contact through `SalesforcePartnerClient.delete` and its H2 row by `last_name`.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption: reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence and is never counted as a pass.

## Tests

Every surefire test is credential-free and contacts no external system.

| Test class | What it proves |
|---|---|
| `SalesforceToMySQLDBUsingBatchProcessingTest` | the ported `testMainFlow` of the original IT (`salesforce-to-MySQL-DB-using-Batch-Processing/src/test/java/org/mule/examples/integration/SalesforceToMySQLDBUsingBatchProcessingIT.java:105-129`), with the whole application context on profile `test` (H2 in MySQL mode seeded from `contact.sql`) and a mocked `SalesforcePartnerClient` answering the query with one Contact: one `pollOnce()`, awaited for up to 120 s every 500 ms, gives a successful job with one loaded record, and exactly one `contact` row is synced with the Contact's `last_name`, the original's assertions. It also checks that exactly one query was sent, from a default watermark earlier than the Contact's `LastModifiedDate`, and that the watermark moved to that `LastModifiedDate` |
| `SalesforceToMySQLDBUsingBatchProcessingIT` | the same original assertions against a live sandbox Contact and H2; the Contact and its row are deleted in `@AfterEach` (failsafe, previous section) |
| `trigger/ContactPollSchedulerTest` | `pollOnce()` returns the job result and propagates a failure unchanged; `poll()` logs failures at ERROR without rethrowing and keeps polling; `poll()` is the only `@Scheduled` method, at a fixed rate of `${poll.frequency:60000}` with `${poll.initial-delay:0}`, and `application.yml` keeps 60000 and 0. Against H2 in the real context, one poll inserts the polled Contact and stores its `LastModifiedDate`. A Contact without `Email` is a failed record (`FAILED_PROCESS_RECORDS`, nothing inserted), and an upstream failure leaves the watermark and the table unchanged |
| `service/ContactToDatabaseBatchServiceTest` | the query from the default watermark (now − 10 s, UTC) or from a stored one, appended unquoted; an empty poll leaves the store as it was; a Contact without a row is inserted, one with a row is updated; the `otherwise` branch, invoked directly, logs `Error with <email> contact` without touching the database; the watermark moves to the latest `LastModifiedDate` of the poll, text kept, date-time objects stored in Salesforce's UTC form, null values skipped; a failed query propagates unchanged after one call and keeps the watermark; an insert failure fails the record, stops the job, and the watermark still moves; a lookup failure fails the record in step 1 with no insert or update; the watermark line then the on-complete line are logged; the constants carry the original names and texts; without a `Clock` bean the system clock is used |
| `mapper/ContactRowMapperTest` | the DataWeave mapping: `Email`, `FirstName`, `LastName`, `LastModifiedDate` become `email`, `first_name`, `last_name`, `last_modified` in script order; missing or null fields become null-valued keys; extra fields are dropped; the input is not modified; a null input is rejected |
| `client/ContactRepositoryTest` | `@JdbcTest` on H2 in MySQL mode: `contact` created from `contact.sql`; the select returns the original columns and nothing for an unknown email, and matches the email case-insensitively; insert and update affect only the given email, an update of an unknown email inserts nothing; a null email propagates as a `DataIntegrityViolationException` and a duplicate key as a `DuplicateKeyException`, with nothing written; quotes and hostile values are bound literally; every statement uses bind parameters for every value |
| `service/BatchJobRunnerTest` | SC-7: accept policies (`NO_FAILURES` default, `ONLY_FAILURES`) before the accept expression; record variables and replaced payloads reaching later steps; failures kept under their step's name; commit grouping; `max-failed-records` of 0 (default), -1 and above the failed count; on-complete run once; an input-phase exception propagated with nothing else run; empty input completing with zero counts |
| `service/WatermarkStoreTest` | SC-8: default while nothing is stored, never persisted and evaluated on every call; `MAX` by instant over the current poll's candidates only; no candidate stores nothing and creates no file; values survive a restart and are read afresh; atomic UTF-8 writes; unusable paths fail visibly |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, one lazily created connection reused, `query` and `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, session mode; one re-login and retry after `INVALID_SESSION_ID`, no retry after `REQUEST_LIMIT_EXCEEDED`, a read timeout that never re-sends the call, `INVALID_LOGIN` rethrown unchanged; the production login endpoint and the 10 s default timeouts |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, the unchanged rethrow of every other failure, ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/SfdcObjectBuilder` is the original test helper `builders/SfdcObjectBuilder`, ported to the test package. The other original test helpers are not ported:

- `db/MySQLDbCreator` and the `database.*` keys are replaced by H2 in MySQL mode (profile `test`).
- `integration/AbstractTemplateTestCase` is replaced by `@SpringBootTest`: its `stopFlowSchedulers` becomes a scheduled first poll held back for an hour, and its `runSchedulersOnce` becomes `pollOnce()`.
- `test/utils/ListenerProbe`, `test/utils/PipelineSynchronizeListener` and `BatchTestHelper`'s job-termination wait are replaced by Awaitility.
- The sub-flows of `src/test/resources/testflows/test-flows.xml` are replaced too. `selectContactFromDB` and `deleteContactFromDB` become `JdbcTemplate` select and delete by `last_name`, with a bind parameter where the original concatenated the value. `insertSalesforceContactSubFlow` (an `sfdc:upsert` of `LastName` and `Email`) and `deleteContactFromSalesforce` become `SalesforcePartnerClient.create` and `delete` in the live IT, and a stubbed query result in the surefire test.

Coverage: the JaCoCo `check` goal at `prepare-package` fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80%. The report in `target/site/jacoco/` also records total project coverage.

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md). That file also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `scheduler` trigger the replay seeds the `contact` rows and the SC-8 watermark store file, points the datasource at H2 in MySQL mode, calls `ContactPollScheduler.pollOnce()`, and compares the `contact` rows, the flow's log lines and the recorded Salesforce SOAP exchanges, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Branch | Status in the last run |
|---|---|---|---|---|
| `scheduler-poll-default-watermark` | `scheduler` | `nondeterministic` | no stored watermark: query from now − 10 s (UTC), which varies with the clock | `UNVERIFIED-uncaptured` |
| `scheduler-poll-new-contact-inserted` | `scheduler` | `deterministic` | stored watermark; the Contact has no row (`exists == false`) → `INSERT`; watermark advanced | `UNVERIFIED-uncaptured` |
| `scheduler-poll-existing-contact-updated` | `scheduler` | `deterministic` | stored watermark; the Contact's row exists (`exists == true`, `dbRecord` set) → `UPDATE`; watermark advanced | `UNVERIFIED-uncaptured` |
| `scheduler-poll-no-records` | `scheduler` | `deterministic` | the query selects nothing; job, table and watermark effects captured, not assumed | `UNVERIFIED-uncaptured` |
| `scheduler-poll-record-failure` | `scheduler` | `deterministic` | a Contact without `Email` fails its insert on `email NOT NULL`; the job stops (`max-failed-records` 0) | `UNVERIFIED-uncaptured` |
| `scheduler-poll-upstream-unavailable` | `scheduler` | `deterministic` | the Salesforce connection is refused; the poll fails before `batch:execute`, the watermark stays | `UNVERIFIED-uncaptured` |
| `scheduler-poll-otherwise-branch` | `scheduler` | `unreachable` | the `otherwise` branch of `upsertContactInDbStep`; proof under Unreachable branch above | `UNREACHABLE` |

- `scheduler-poll-default-watermark` is `nondeterministic`: its query and its watermark log line embed the clock-derived default. Once captured, it is reported `UNVERIFIED — nondeterministic output` and escalated as AAP 0.8.8 sets out.
- `scheduler-poll-otherwise-branch` is `unreachable`. No fixture may exist for it, and it is excluded from capture and from the branch-coverage claim.
- The recorded Salesforce `login` carries live credentials and returns the org's live instance `serverUrl`, which the replay would follow, and every later exchange carries the live session ID. The capture therefore omits `upstream`, so each of the six reachable scenarios stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, Project notes). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 6 unverified`.
- The approved failure modes have no original branch beyond the refused connection; their Tier 1 evidence is the classifier, executor, client, service and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| salesforce-to-MySQL-DB-using-Batch-Processing-java | salesforce-to-MySQL-DB-using-Batch-Processing | Third-party connector | PASS | PASS | 97.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

This row is the project's row of the root `SUMMARY.md` (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 713 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`SalesforceToMySQLDBUsingBatchProcessingIT`: `src/test/resources/application-it.yml not supplied (copy application-it.yml.example); Tier 2B live Salesforce test skipped`).
- `mvn -B clean package`: `BUILD SUCCESS`; 713 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 713 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- `mvn -B verify`: `BUILD SUCCESS`; the same 713 surefire tests, then the failsafe IT skipped as above.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 351 of 362 lines (97.0%) of `service/ContactToDatabaseBatchService`, `service/WatermarkStore`, `service/BatchJobRunner` with its nested classes, and `mapper/ContactRowMapper`. Total project LINE coverage: 96.8% (806 of 833 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=6`, `failed=0`; the six reachable scenarios `UNVERIFIED-uncaptured`, `scheduler-poll-otherwise-branch` `UNREACHABLE`.
- Tier 2B: no sandbox credentials were supplied, so `SalesforceToMySQLDBUsingBatchProcessingIT` was skipped. The cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked until the Mule EE runtime licence and the EE Maven repository credentials are confirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md), section 9 ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `SalesforceToMySQLDBUsingBatchProcessingTest` carries the original committed assertions with their comparison semantics: after one poll the batch job was successful (`helper.assertJobWasSuccessful()`), and exactly one `contact` row has the Contact's `last_name`.
- The trigger, service, mapper, repository, runner, store and client tests cover every reachable branch of the flow and the approved failure handling; the unreachable `otherwise` branch is invoked directly.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the flow and of the platform defaults. It does not show that its rows, log lines and SOAP requests equal what the original runtime produced. The Salesforce side of these tests is mocked or stubbed, not recorded from the original's org, and the database is H2, not MySQL.
- No original assertion covers the UPDATE path (`scheduler-poll-existing-contact-updated`), the empty poll (`scheduler-poll-no-records`), the record failure (`scheduler-poll-record-failure`), the refused connection (`scheduler-poll-upstream-unavailable`), or the query sent from a stored watermark. Nor does any cover the log lines or the watermark stored after a poll. These are covered only by the new tests.
- Unverified scenarios, until captured: `scheduler-poll-default-watermark` (`nondeterministic`), `scheduler-poll-new-contact-inserted`, `scheduler-poll-existing-contact-updated`, `scheduler-poll-no-records`, `scheduler-poll-record-failure`, `scheduler-poll-upstream-unavailable`. After capture all six stay unverified (`UNVERIFIED-no-upstream`, Fixture parity). `scheduler-poll-otherwise-branch` is `unreachable`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `salesforce-to-MySQL-DB-using-Batch-Processing/`.

