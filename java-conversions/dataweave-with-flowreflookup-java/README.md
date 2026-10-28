# dataweave-with-flowreflookup-java

`dataweave-with-flowreflookup-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`dataweave-with-flowreflookup`](../../dataweave-with-flowreflookup/) (category Third-party connector, Salesforce; file-triggered). The package root is `com.mulesoft.examples.dataweave_with_flowreflookup` and the main class is `DataweaveWithFlowreflookupApplication`. The original folder `dataweave-with-flowreflookup/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A CSV file of new accounts (company name, billing address) is read, each account's sales region is derived from its billing state, and the accounts, region included, are created in Salesforce (`dataweave-with-flowreflookup/README.md:19-21`, "Example Use Case"). The region comes from the original's "flow-ref lookup": the DataWeave script calls the second flow, `LookUpSalesRegionFlow`, once per record through `lookup("LookUpSalesRegionFlow", $)`.

`config.resources=dataweave-with-flowref.xml` loads the only flow file, `dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml`, which holds two flows:

- `CreateNewSalesforceAccountFlow` (xml:6-24): a file inbound endpoint, the DataWeave transformation `CSV to Maps` and `sfdc:create` of type `Account`.
- `LookUpSalesRegionFlow` (xml:26-54, `processingStrategy="synchronous"`): a Groovy component that maps the state to a region, and an INFO logger.

Neither flow declares an exception strategy or a transaction. `mule-app.properties` holds comments only.

The bundled `src/main/resources/companies.csv` (196 bytes, LF line ends, no trailing line end) holds two accounts: Universal Exports (Miami, state `fl`, lower case in the file) and Best Widgets (El Dorado Hills, state `CA`). Its `has_given_contact_permission` column is empty in both rows and is never mapped.

### Salesforce prerequisite

The target org's Account object must have the custom field the original README has users create (`dataweave-with-flowreflookup/README.md:29-41`): type **Text**, Field Label `Region`, Length `50`, Field Name `Region`, which Salesforce exposes as the API name `Region__c`, added to the **Account Layout**. Without it Salesforce rejects the `create` with `INVALID_FIELD`. That error is none of the three approved failure modes, so `FileInboundPoller` logs it at ERROR and moves the file, as after any other failed import.

## Endpoints and triggers

The project has no HTTP listener: `spring.main.web-application-type: none`, so no port is bound and no web server starts. Its one trigger is the file poller that replaces the flow's `file:inbound-endpoint`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| file poll | `trigger/CompaniesFileTrigger` over SC-5 `trigger/FileInboundPoller` | input `companies.input-dir` (default `src/main/resources/input`), move directory `companies.move-to-dir` (default `src/main/resources/output`), every `companies.polling-frequency` ms (default `10000`) | each file's bytes go unchanged to `service/AccountImportService#importAccounts(byte[])`; the file is then moved to the move directory under its own name | `file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"`, `dataweave-with-flowref.xml:7` |

File disposition follows Mule 3.8's default file connector (SC-5), because the original declares no `file:connector`:

- `poll()` is `@Scheduled(fixedDelayString = "${companies.polling-frequency:10000}")`: the delay runs from the end of one poll to the start of the next, and the first poll runs at startup. `pollOnce()` runs one poll on demand for tests and fixture replay; production invocation stays scheduled.
- A poll creates the input and move directories when missing, then takes the regular files of the input directory in directory-listing order, unsorted and unfiltered (no file-name pattern, as in the original). Each file is marked in process, so the scheduled poll and `pollOnce()` never hand the same file over twice.
- After the import returns, successfully or with an exception, the file is moved to `companies.move-to-dir` under its own name, replacing a file of that name. A rename that fails falls back to copy-and-delete; a move that still fails is logged at WARN.
- An exception ends the import the way Mule's default exception strategy ended the flow: one ERROR line `Exception processing file <name>: <exception class and throw site>`, then the move above. The line never carries an exception message or file content.
- A file that cannot be opened is logged at WARN and left in place, unmarked, for the next poll.
- The flows declare no transaction or rollback, so no file is retried and no partly completed Salesforce write is repeated.
- Relative directories resolve against the working directory, exactly as the original resolved them against its application directory: the project directory under `mvn spring-boot:run`.
- The endpoint's `responseTimeout="10000"` is not carried: a one-way file endpoint sends no reply.

### Flow `CreateNewSalesforceAccountFlow`

`service/AccountImportService#importAccounts(byte[])` runs the processors after the file endpoint in the flow's order:

1. **Read the CSV.** SC-4 `mapper/DwCsvReader#read` reads the file as DataWeave 1.0's `application/csv` reader did: header row, comma separator, `"` quoting, every value a string, never trimmed or coerced (`33126` stays the string `"33126"`). The `has_given_contact_permission` column is read but not used.
2. **Map each record.** `mapper/AccountMapper#map`, the owner of the `%dw 1.0` script whose header is `dataweave-with-flowref.xml:9`, builds one `LinkedHashMap` per record, in file order and in this key order:

   | Account field | Value | Script line |
   |---|---|---|
   | `Name` | `company_name` | xml:13 |
   | `BillingStreet` | `company_address` | xml:14 |
   | `BillingCity` | `company_city` | xml:15 |
   | `BillingState` | `company_state`, upper-cased (`upper`); a missing state stays `null` | xml:16 |
   | `BillingPostalCode` | `company_zip` | xml:17 |
   | `Region__c` | the `region` entry `service/SalesRegionLookupService#lookUpSalesRegion` returns for the record | xml:18 |

   The lookup runs once per record while that record is mapped and receives the record as read, so its console line and log line interleave record by record, as in the original.
3. **Create the Accounts.** One SC-15 `SalesforcePartnerClient#create("Account", accounts)` call carries every record of the file, as `sfdc:create type="Account"` received `#[payload]` (xml:21-23). The records are neither chunked, filtered nor de-duplicated. A field whose value is `null` is not sent. The save results are returned unchanged and discarded by the trigger: as in the original, a per-record error in them (a duplicate rule or a rejected value, for example) is neither raised nor logged.

#### Region lookup (`LookUpSalesRegionFlow`)

`service/SalesRegionLookupService` reimplements the Groovy component "Get Region by State" (xml:27-52) in plain Java, without a script engine, followed by the logger "Log the Region" (xml:53). `processingStrategy="synchronous"` becomes a direct method call on the mapping thread.

- The record's `company_state` is upper-cased when present (`toUpperCase()` under the JVM default locale, as Groovy's is), not trimmed; a missing state stays `null`.
- `State to lookup is: <state>` is printed to standard output with `System.out.println`, where the Groovy `println` wrote it (xml:33); a missing state prints `null`.
- The state is matched against the script's five lists in their order; the first list holding it wins:

  | Region | States |
  |---|---|
  | `North East` | CT ME MA NH VT RI NY NJ DE DC MD (the script lists `NH` twice) |
  | `South East` | AL AR FL GA LA SC NC TN TX |
  | `Mid West` | ID IL IA KS MT WY ND SD OH |
  | `South West` | AZ CO OK NM NV |
  | `West Coast` | CA HI WA OR AK |
  | `UNKNOWN` | anything else, an empty or missing state included (the script's initial value, xml:28) |

- The INFO line `Region is : <region>` is logged under the category `com.mulesoft.examples.dataweave_with_flowreflookup.service.SalesRegionLookupService`, and `{region=<region>}` is returned to the mapper.

For the shipped `companies.csv` the console shows, per record, the standard-output line followed by the INFO line, as the original README shows (`dataweave-with-flowreflookup/README.md:65-70`): Universal Exports → `FL` / `South East`, Best Widgets → `CA` / `West Coast`. Measured on this build (timestamps and thread prefixes removed):

```console
State to lookup is: FL
INFO c.m.e.d.s.SalesRegionLookupService       : Region is : South East
State to lookup is: CA
INFO c.m.e.d.s.SalesRegionLookupService       : Region is : West Coast
```

### Connector failure modes (AAP 0.4.8)

Every Salesforce call, the lazy SOAP `login` included, runs through SC-9 `client/UpstreamCallExecutor` and is classified by `client/SalesforceFailureClassifier`. The project is file-triggered, so no caller receives a 502, 429 or 503: the classification is logged at ERROR with the upstream name `Salesforce`, and the file is then moved as after any handled failure (SC-5). These three responses are the only behaviour the conversion adds.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or session expiry: an `ApiFault`/`LoginFault`, or a SOAP fault without detail, with code `INVALID_SESSION_ID` | WARN `Upstream Salesforce rejected the session [AUTHENTICATION]; re-authenticating and retrying once`, one new `login`, then a single retry; a second rejection ends the attempt | ERROR `Upstream Salesforce call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, file moved |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | ERROR `Upstream Salesforce call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException` without `Retry-After` (Salesforce sends none), file moved |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried, even when the chain also holds `INVALID_SESSION_ID`, so no Account is ever created twice | ERROR `Upstream Salesforce call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, file moved |
| Anything else (`INVALID_LOGIN`, `INVALID_FIELD`, `INVALID_TYPE`, …) | rethrown unchanged | handled like Mule's default exception strategy: ERROR `Exception processing file <name>: …` from `FileInboundPoller`, file moved |

- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- The client sends its SOAP requests with a chunked body, which the JDK never re-sends after a dropped connection, so a connectivity failure reaches the executor after exactly one request.
- The ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.

Measured on this build with placeholder values for the three credential keys, which the client requires before it sends a `login`, and `sfdc.auth-endpoint` pointed at a closed local port (timestamps and thread prefixes removed). The two records are mapped, and their regions looked up, before the lazy `login` fails:

```console
$ CLOSED_PORT=1   # any loopback port nothing listens on
$ java -jar target/dataweave-with-flowreflookup-java-1.0.0-SNAPSHOT.jar --companies.input-dir=target/run/in --companies.move-to-dir=target/run/out --companies.polling-frequency=2000 "--sfdc.auth-endpoint=http://127.0.0.1:${CLOSED_PORT}/services/Soap/u/65.0" --sfdc.username=placeholder --sfdc.password=placeholder --sfdc.securityToken=placeholder
$ cp src/main/resources/companies.csv target/run/companies.csv.part && mv target/run/companies.csv.part target/run/in/companies.csv   # in a second terminal, while the application runs
State to lookup is: FL
INFO  c.m.e.d.s.SalesRegionLookupService       : Region is : South East
State to lookup is: CA
INFO  c.m.e.d.s.SalesRegionLookupService       : Region is : West Coast
ERROR c.m.e.d.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
ERROR c.m.e.d.trigger.FileInboundPoller        : Exception processing file companies.csv: com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException at com.mulesoft.examples.dataweave_with_flowreflookup.client.UpstreamCallExecutor.translate(UpstreamCallExecutor.java:276) <- com.sforce.ws.ConnectionException <- java.net.ConnectException
```

The first poll at startup had created `target/run/in/` and `target/run/out/`. Afterwards `target/run/in/` was empty and `target/run/out/companies.csv` was byte-identical to the dropped file.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2), not an OAuth grant, so no connected app is needed; the root `DECISIONS.md` records why no OAuth flow is built. The client logs in lazily on the first Salesforce call, so the application starts with every secret empty.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `spring.application.name` | `String` | `dataweave-with-flowreflookup-java` | none | fixed | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | the original has no HTTP listener, so no web server starts and no port is bound |
| `companies.input-dir` | `String` (a path) | `src/main/resources/input` | none; the original hard-coded `path` on the file endpoint (xml:7) | default | directory polled for CSV files |
| `companies.move-to-dir` | `String` (a path) | `src/main/resources/output` | none; the original hard-coded `moveToDirectory` (xml:7) | default | directory each polled file is moved to under its own name |
| `companies.polling-frequency` | `long` (ms) | `10000` | none; the original hard-coded `pollingFrequency` (xml:7) | default | fixed delay between the end of one poll and the start of the next; a zero, negative or unreadable value stops start-up with an error naming the key and the value |
| `sfdc.username` | `String` | none, `${SFDC_USERNAME:}` | **`sfdc.user`** (xml:3) | TODO | the Salesforce user the `login` call authenticates; renamed because the request's key names govern (AAP 0.4.6) |
| `sfdc.password` | `String` | none, `${SFDC_PASSWORD:}` | `sfdc.password` (unchanged, xml:3) | TODO (secret) | that user's password, without the security token |
| `sfdc.securityToken` | `String` | none, `${SFDC_SECURITYTOKEN:}` | `sfdc.securityToken` (unchanged, xml:3) | TODO (secret) | appended to the password at login; `sfdc.security-token` binds as well |
| `sfdc.auth-endpoint` | `String` | `https://login.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL | default | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (AAP 0.4.8); a bare number binds as milliseconds; a value under 1 ms stops the application at startup |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call (AAP 0.4.8), with the same rules |

- Original-to-new key map: `sfdc.user` → `sfdc.username` (the request's name governs); `sfdc.password` and `sfdc.securityToken` keep their names; the file endpoint's hard-coded attributes `path`, `moveToDirectory` and `pollingFrequency` → `companies.input-dir`, `companies.move-to-dir` and `companies.polling-frequency`, with the original values as defaults. The live test reads the three `sfdc.*` keys from `src/test/resources/application-it.yml`, where the original test read `sfdc.user`, `sfdc.password` and `sfdc.securityToken` from `mule.test.properties`.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and the token.
- The project has no `src/test/resources/application-test.yml`: the unit tests mock Salesforce or stub it with WireMock and need no credential. Surefire and failsafe set `companies.input-dir` and `companies.move-to-dir` as system properties under `target/test-companies/` and `target/it-companies/`, and `companies.polling-frequency` to `3600000`; `DataWeaveWithFlowRefTest` and `DataWeaveWithFlowRefIT` override the directories with their own temporary ones. No test polls or moves files in `src/main/resources/input` or `src/main/resources/output`.
- Supply the credentials through the environment, never in a committed file:

  ```sh
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
  # a sandbox org, with the directories under target/
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' java -jar target/dataweave-with-flowreflookup-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=https://test.salesforce.com/services/Soap/u/65.0 --companies.input-dir=target/run/in --companies.move-to-dir=target/run/out
  ```

  An external `application.yml` (for example `'--spring.config.additional-location=<file>'`) outside the repository works as well.

## Mule-to-Java mapping

| Mule construct (`dataweave-with-flowreflookup`) | Java construct (`com.mulesoft.examples.dataweave_with_flowreflookup`) |
|---|---|
| `sfdc:config` with `${sfdc.user}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:3) | SC-15 `client/SalesforcePartnerClient` in login mode, configured by SC-15 `config/SalesforceProperties` (`sfdc.username`, `sfdc.password`, `sfdc.securityToken`); one `PartnerConnection`, created lazily on the first call and re-created on re-authentication |
| flow `CreateNewSalesforceAccountFlow` (xml:6) | `trigger/CompaniesFileTrigger` → `service/AccountImportService#importAccounts(byte[])` |
| `file:inbound-endpoint` (xml:7) | `trigger/CompaniesFileTrigger` (`poll()`, `pollOnce()`) over SC-5 `trigger/FileInboundPoller` |
| `dw:transform-message` "CSV to Maps": the polled CSV read by DataWeave 1.0 | SC-4 `mapper/DwCsvReader#read` |
| `dw:set-payload` `%dw 1.0` / `%output application/java` / `payload map { … }` (header line 9) | `mapper/AccountMapper#map`: one `LinkedHashMap` per row, keys `Name`, `BillingStreet`, `BillingCity`, `BillingState`, `BillingPostalCode`, `Region__c` |
| `lookup("LookUpSalesRegionFlow", $).region` (xml:18), flow `LookUpSalesRegionFlow` (xml:26), its Groovy `scripting:component` (xml:27-52) and `logger` (xml:53) | `service/SalesRegionLookupService#lookUpSalesRegion(Map)`, called by `AccountMapper` for each row |
| `sfdc:create type="Account"` with `#[payload]` (xml:21-23) | `SalesforcePartnerClient#create("Account", records)`: one SOAP Partner API `create` call carrying every record of the file, through SC-9 `client/UpstreamCallExecutor` and `client/SalesforceFailureClassifier` |
| Mule default exception strategy (none declared) | `FileInboundPoller`: ERROR log, then the move to `companies.move-to-dir` |
| `mule-deploy.properties` (`config.resources=dataweave-with-flowref.xml`) | `DataweaveWithFlowreflookupApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (comments only) | `src/main/resources/application.yml` |
| `DataWeaveWithFlowRefIT` (`FunctionalTestCase`, `MuleClient`, live org) | `DataWeaveWithFlowRefTest` (surefire, mocked client) and `DataWeaveWithFlowRefIT` (failsafe, live org) |
| test sub-flows `selectAccountFromSalesforce` (`sfdc:query-single`) and `deleteAccountFromSalesforce` (`sfdc:delete`) of `src/test/resources/testflows/test-flows.xml` | `SalesforcePartnerClient#query` and `SalesforcePartnerClient#delete` inside `DataWeaveWithFlowRefIT` |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (register in the root [`DECISIONS.md`](../../DECISIONS.md)). The project carries copies, each with only its `package` line changed and with its tests:

- SC-4 `mapper/DwCsvReader` (test `mapper/DwCsvReaderTest`) from `import-contacts-into-salesforce-java`;
- SC-5 `trigger/FileInboundPoller` (test `trigger/FileInboundPollerTest`) from `processing-orders-with-dataweave-and-APIkit-java`;
- SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException` (test `client/UpstreamCallExecutorTest`) from `salesforce-data-retrieval-java`, without `UpstreamExceptionHandler`: there is no HTTP caller to answer;
- SC-15 `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier` and `config/SalesforceProperties` (tests `client/SalesforcePartnerClientTest`, `client/SalesforceFailureClassifierTest`) from `salesforce-data-retrieval-java`;
- ST-2 `FixtureParityTest` and the `SCENARIOS.md` format from `hello-world-java`.

It carries no SC-1 and no ST-1, because it has no HTTP listener.

## Behaviours stubbed or externalized

This section also lists the behaviours the conversion changed.

- Externalized: the Salesforce credentials, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; `sfdc.user` is renamed `sfdc.username`. The file endpoint's path, move directory and polling frequency, hard-coded in the original, are configurable keys with the original values as defaults; the login URL and timeouts, defaults of the original connector, are configurable keys.
- Changed: the Groovy script of `LookUpSalesRegionFlow` and DataWeave's `lookup` are plain Java method calls; no script engine and no DataWeave runtime is involved. `BillingState` is upper-cased locale-independently, as DataWeave's `upper`; the lookup upper-cases under the JVM default locale, as the script's `toUpperCase()` did. A line terminator inside a state value is escaped in the printed `State to lookup is:` copy only, so each lookup writes one console line.
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` (`DataWeaveWithFlowRefTest`, service and trigger tests) or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`). At run time nothing is stubbed and no behaviour was dropped.
- Test harness: the test sub-flows `selectAccountFromSalesforce` and `deleteAccountFromSalesforce` are replaced by `SalesforcePartnerClient#query` and `#delete` inside `DataWeaveWithFlowRefIT`, and the original's fixed 15000 ms sleep by a direct `pollOnce()` plus bounded Awaitility waits.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/main/app/mule-deploy.properties`, `src/test/resources/log4j2-test.xml` and the test flows file (AAP 0.2.2).

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- DataWeave `upper` of a missing `company_state`: the plan keeps `null`, so `BillingState` is `null` and `SalesforcePartnerClient#create` sends no `BillingState` field; the lookup still answers `UNKNOWN`. DataWeave 1.0 may instead have failed the transformation, or produced another value.
- Console `println` placement: `State to lookup is: <state>` goes to standard output, not through a logger, immediately before the INFO line of the same record, as the original README shows. The original runtime wrote it to its own console (the wrapper log of a standalone runtime); the capture keeps it as `console.txt`, never as a log event (`SCENARIOS.md`, "Notes for this project").
- More than 200 records in one file: the Java code sends them in one `create` call, unchunked, as `sfdc:create` received `#[payload]`. Salesforce accepts at most 200 records per `create`, so such a file fails as a whole (an `OTHER` failure: ERROR log, file moved). Whether the Mule 3 Salesforce connector (`mule-module-sfdc` 7.1.0) split such a list is decided only by a capture.
- File disposition after an upstream failure: the file is moved to `companies.move-to-dir`, as after a success.
- An empty or header-only file: the service calls `SalesforcePartnerClient#create` with an empty list, which returns without contacting Salesforce, and the file is moved.
- The exact SOAP `create` request: its envelope, field order (`Name`, `BillingStreet`, `BillingCity`, `BillingState`, `BillingPostalCode`, `Region__c`) and Partner API version 65.0, where the original connector spoke an older API version.
- CSV edge cases that no original input exercises (ragged rows, a backslash, a byte order mark), listed in `mapper/DwCsvReader`.

## Build, run and test

Run from `java-conversions/dataweave-with-flowreflookup-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network
mvn -B verify           # surefire tests, then the failsafe DataWeaveWithFlowRefIT against a live org (Tier 2B)
mvn spring-boot:run     # polls src/main/resources/input every 10000 ms; needs sfdc.username, sfdc.password and sfdc.securityToken
java -jar target/dataweave-with-flowreflookup-java-1.0.0-SNAPSHOT.jar
```

Run the application from the project directory, so the relative `src/main/resources/input` and `src/main/resources/output` are the directories the original used. They need not be created by hand, as the original README asked (`dataweave-with-flowreflookup/README.md:56`): the first poll, at startup, creates both. To import the sample, copy the bundled file into the input directory while the application runs:

```sh
cp src/main/resources/companies.csv src/main/resources/input/
```

Within one polling interval the console shows the two lookups of [Region lookup](#region-lookup-lookupsalesregionflow), the file moves to `src/main/resources/output/companies.csv`, and Salesforce lists Universal Exports (`Region__c` `South East`) and Best Widgets (`Region__c` `West Coast`) under **Accounts**. Delete both Accounts afterwards, and the `Region` field if you created it only for the example. For a large file, copy it next to the input directory first and then move it in, so a poll never reads a half-written file.

- `mvn test` needs no licence, credential or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub.
- Without the three `sfdc.*` values the application still starts, but every import fails before any Salesforce request: the lazy `login` first checks that `sfdc.username`, `sfdc.password` and `sfdc.securityToken` are non-blank and throws an `IllegalStateException` naming each missing key, never a value. That, like a rejected login (`INVALID_LOGIN`), is none of the three failure modes, so it is logged by `FileInboundPoller` at ERROR and the file is moved.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

### Live test (Tier 2B)

`DataWeaveWithFlowRefIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials of the original example; no new sandbox is procured.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Make sure the org's Account object has `Region__c` ([Salesforce prerequisite](#salesforce-prerequisite)).
4. Run `mvn -B verify`. The test starts the application without a web server under the Spring profile `it`, which loads `application-it.yml`.

The IT writes data, so use a sandbox org, never production:

- Accounts named Universal Exports or Best Widgets that the org already holds are recorded first; they are neither asserted on nor deleted, so only an Account this import created satisfies the assertions.
- It drops `companies.csv` into its own temporary input directory and runs `CompaniesFileTrigger.pollOnce()`, which creates both Accounts in the org.
- It queries each Account by name with the original test sub-flow's SOQL `SELECT Id,Region__c FROM Account WHERE Name = '<name>'`, waiting up to 60 s, and asserts `Region__c` `South East` for Universal Exports and an `Id` for Best Widgets, as the original asserted and fetched (`DataWeaveWithFlowRefIT.java:94-96`). It also checks that the file moved, unchanged, to its output directory.
- `@AfterEach` deletes every Account this import created. A failed delete is logged, never thrown, as the original only printed it.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption (`src/test/resources/application-it.yml not supplied (copy application-it.yml.example); live Salesforce test skipped`): reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `DataWeaveWithFlowRefTest` | the ported `DataWeaveWithFlowRefIT` (`dataweave-with-flowreflookup/src/test/java/org/mule/examples/DataWeaveWithFlowRefIT.java:79-97`) with the whole application context against a mocked `SalesforcePartnerClient`: `companies.csv` dropped into a temporary input directory and `pollOnce()` lead to exactly one `create` of type `Account` carrying Universal Exports and Best Widgets in file order; `Region__c` equals `South East` for Universal Exports and an Account named Best Widgets is present; also the file moved unchanged to the output directory, and no other Salesforce call |
| `DataWeaveWithFlowRefIT` | the same assertions against a live org (failsafe, previous section) |
| `trigger/CompaniesFileTriggerTest` | `companies.csv` handed byte for byte to the import once and moved to the output directory; a Salesforce connect timeout and any other import failure logged at ERROR, not retried, file still moved; an empty input directory hands nothing over; `poll()` scheduled with the original 10000 ms fixed delay |
| `trigger/FileInboundPollerTest` | SC-5: move after success and after a handled failure, delete without a move directory, the interrupt flag kept, the in-process mark against re-entrant and concurrent polls, an unopenable file left in place and retried, a file too large to read (over the read limit, or one whose allocation fails with `OutOfMemoryError`) left in place while the other files are consumed, missing directories created, subdirectories ignored, a same-named file in the move directory replaced |
| `service/AccountImportServiceTest` | every record of `companies.csv` sent in one `create` call of type `Account`; a header-only file still calls `create` once with no records; vendor failures propagated unchanged without retry; a reader failure stops before `create` |
| `service/SalesRegionLookupServiceTest` | every state of the five lists resolves to its region; lower- and mixed-case states (`fl`, `ca`, `Ny`) are upper-cased first; states outside every list and a missing state give `UNKNOWN`; one `State to lookup is: <state>` line on standard output and one INFO `Region is : <region>` per call; upper-casing under the JVM default locale; line terminators escaped on the console line only |
| `mapper/AccountMapperTest` | the six keys in script order for both shipped rows; Universal Exports → `South East`; `upper` applied to `BillingState` without modifying the input row; values passed through without coercion or trimming (a postal code keeps its leading zero); a missing state gives a `null` `BillingState` and `UNKNOWN`; unmapped columns ignored; one lookup per row, in row order, with the raw row; one state per region through the real lookup; empty input, `null` input and `null` rows |
| `mapper/DwCsvReaderTest` | SC-4: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed string values |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, one lazily created connection reused, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, session mode; one re-login and retry after `INVALID_SESSION_ID`, no retry after `REQUEST_LIMIT_EXCEEDED`, a read timeout that never re-sends the `create`, `INVALID_LOGIN` rethrown unchanged; the production login endpoint and 10 s default timeouts; concurrent callers sharing the one login, re-login or session refresh in flight, its timeout included; paging that fails, with no further `queryMore`, on a repeated query locator, records beyond the reported size or pages beyond what that size allows |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, the unchanged rethrow of every other failure, ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `file` trigger the replay places the captured input file, calls `CompaniesFileTrigger.pollOnce()` and compares the produced files, the SC-5 disposition, the INFO events of the region logger and the recorded Salesforce SOAP exchanges, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Input | Branch | Status in the last run |
|---|---|---|---|---|---|
| `file-companies-csv-imported` | `file` | `deterministic` | `companies.csv`, then `CompaniesFileTrigger.pollOnce()` | the original IT's input: `fl` → South East, `CA` → West Coast | `UNVERIFIED-uncaptured` |
| `file-companies-region-north-east` | `file` | `deterministic` | `companies-north-east.csv` (state `ma`) | North East | `UNVERIFIED-uncaptured` |
| `file-companies-region-mid-west` | `file` | `deterministic` | `companies-mid-west.csv` (state `OH`) | Mid West | `UNVERIFIED-uncaptured` |
| `file-companies-region-south-west` | `file` | `deterministic` | `companies-south-west.csv` (state `AZ`) | South West | `UNVERIFIED-uncaptured` |
| `file-companies-region-unknown` | `file` | `deterministic` | `companies-unknown.csv` (state `PR`, and an empty state) | `UNKNOWN` | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`: the flows read no clock, random value or generated name. Every outcome of the Groovy `switch`, the flows' only branching, is selected by at least one row.
- The recorded Salesforce `login` carries live credentials and returns the org's live instance `serverUrl`, which the replay would follow. The capture therefore omits `upstream`, so every scenario stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, "Notes for this project"). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 5 unverified`.
- The approved failure modes have no original branch and no fixture row; their Tier 1 evidence is the classifier, executor, poller and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| dataweave-with-flowreflookup-java | dataweave-with-flowreflookup | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

The root `SUMMARY.md` carries the same row for this project (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 789 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`DataWeaveWithFlowRefIT`: `src/test/resources/application-it.yml not supplied`).
- `mvn -B clean package`: `BUILD SUCCESS`; 789 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 789 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 128 of 128 lines (100.0%) of `service/AccountImportService`, `service/SalesRegionLookupService`, `mapper/AccountMapper` and `mapper/DwCsvReader`. Total project LINE coverage: 92.1% (733 of 796 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `DataWeaveWithFlowRefIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md) ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `DataWeaveWithFlowRefTest` carries the original committed assertions with their comparison semantics: after `companies.csv` is imported, `Region__c` equals `South East` for the Account named Universal Exports, and an Account named Best Widgets exists.
- The trigger, poller, service, mapper and client tests cover every outcome of the region lookup and the approved failure handling.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the platform defaults, but not that its SOAP requests, console output and file handling equal what the original runtime did. The Salesforce side of these tests is mocked or stubbed, not recorded from the original's org.
- Branches and behaviours no original assertion covers, tested only by the new tests: the North East, Mid West, South West and West Coast region values (the original fetched Best Widgets, the West Coast record, without asserting a field); the `UNKNOWN` default; the upstream failure handling; the file disposition to `output`; every Account field other than Universal Exports' `Region__c`; empty and header-only files; quoted CSV fields.
- Unverified scenarios, until captured: `file-companies-csv-imported`, `file-companies-region-north-east`, `file-companies-region-mid-west`, `file-companies-region-south-west`, `file-companies-region-unknown`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `dataweave-with-flowreflookup/`.
