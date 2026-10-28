# import-contacts-into-salesforce-java

`import-contacts-into-salesforce-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`import-contacts-into-salesforce`](../../import-contacts-into-salesforce/) (category Third-party connector, Salesforce; file-triggered). The package root is `com.mulesoft.examples.import_contacts_into_salesforce` and the main class is `ImportContactsIntoSalesforceApplication`. The original folder `import-contacts-into-salesforce/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A CSV file of contacts (first name, surname, phone, email) is picked up from an input folder, and each row becomes a new Salesforce `Contact` (`import-contacts-into-salesforce/README.md`, "Example Use Case"). The single flow `Contacts_to_SFDC` of `import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml:8-27`, the only flow `config.resources=contacts-to-SFDC.xml` loads, has three elements: a file inbound endpoint, a DataWeave transformation and `sfdc:create` of type `Contact`. It declares no logger, no exception strategy and no transaction.

The bundled `src/main/resources/contacts.csv` (113 bytes, LF line ends, no trailing line end) holds two contacts, John Doe (`john.doe@texasComp.com`) and Jane Doe (`jane.doe@texasComp.com`). The original README tells users to look for "three new entries" but lists, and the file contains, only these two.

## Endpoints and triggers

The project has no HTTP listener: `spring.main.web-application-type: none`, so no port is bound and no web server starts. Its one trigger is the file poller that replaces the flow's `file:inbound-endpoint`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| file poll | `trigger/ContactsFileTrigger` over SC-5 `trigger/FileInboundPoller` | input `contacts.input-dir` (default `src/main/resources/input`), move directory `contacts.move-to-dir` (default `src/main/resources/output`), every `contacts.polling-frequency` ms (default `10000`) | each file's bytes go unchanged to `service/ContactImportService#importContacts(byte[])`; the file is then moved to the move directory under its own name | `file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"`, `contacts-to-SFDC.xml:9` |

File disposition follows Mule 3.8's default file connector (SC-5), because the original declares no `file:connector`:

- `poll()` is `@Scheduled(fixedDelayString = "${contacts.polling-frequency:10000}")`: the delay runs from the end of one poll to the start of the next, and the first poll runs at startup. `pollOnce()` runs one poll on demand for tests and fixture replay; production invocation stays scheduled.
- A poll creates the input and move directories when missing, then takes the regular files of the input directory in directory-listing order, unsorted and unfiltered (no file-name pattern, as in the original). Each file is marked in process, so the scheduled poll and `pollOnce()` never hand the same file over twice.
- After the import returns, successfully or with an exception, the file is moved to `contacts.move-to-dir` under its own name, replacing a file of that name. A rename that fails falls back to copy-and-delete; a move that still fails is logged at WARN.
- An exception ends the import the way Mule's default exception strategy ended the flow: one ERROR line `Exception processing file <name>: <exception class and throw site>`, then the move above. The line never carries an exception message or file content.
- A file that cannot be opened is logged at WARN and left in place, unmarked, for the next poll.
- The flow declares no transaction or rollback, so no file is retried and no partly completed Salesforce write is repeated.
- Relative directories resolve against the working directory: the project directory under `mvn spring-boot:run`.
- The endpoint's `responseTimeout="10000"` is not carried: a one-way file endpoint sends no reply.

## Mule-to-Java mapping

| Mule construct (`import-contacts-into-salesforce`) | Java construct (`com.mulesoft.examples.import_contacts_into_salesforce`) |
|---|---|
| `sfdc:config` with `${sfdc.user}`, `${sfdc.password}`, `${sfdc.securityToken}` (xml:4) | SC-15 `client/SalesforcePartnerClient` in login mode, configured by `config/SalesforceProperties` (`sfdc.username`, `sfdc.password`, `sfdc.securityToken`) |
| `sfdc:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` (xml:5) | one cached `PartnerConnection`, created lazily on the first call and re-created on re-authentication |
| flow `Contacts_to_SFDC` (xml:8) | `trigger/ContactsFileTrigger` → `service/ContactImportService#importContacts(byte[])` |
| `file:inbound-endpoint` (xml:9) | `trigger/ContactsFileTrigger` (`poll()`, `pollOnce()`) over SC-5 `trigger/FileInboundPoller` |
| `dw:input-payload`: the polled CSV read by DataWeave 1.0 | SC-4 `mapper/DwCsvReader#read`: header row, comma separator, `"` quoting, string values |
| `dw:set-payload` `%dw 1.0` / `%output application/java` / `payload map { FirstName: $.firstname, LastName : $.surname, Email : $.email, Phone : $.phone }` (header line 12) | `mapper/ContactMapper#map`: one `LinkedHashMap` per row, keys `FirstName` ← `firstname`, `LastName` ← `surname`, `Email` ← `email`, `Phone` ← `phone` |
| `sfdc:create type="Contact"` with `#[payload]` (xml:24-26) | `SalesforcePartnerClient#create("Contact", records)`: one SOAP Partner API `create` call carrying every record of the file, as the connector made, through SC-9 `client/UpstreamCallExecutor` and `client/SalesforceFailureClassifier` |
| Mule default exception strategy (none declared) | `FileInboundPoller`: ERROR log, then the move to `contacts.move-to-dir` |
| `mule-deploy.properties` (`config.resources=contacts-to-SFDC.xml`) | `ImportContactsIntoSalesforceApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (comments only) | `src/main/resources/application.yml` |
| `ImportContactsToSfdcIT` (`FunctionalTestCase`, `MuleClient`, live org) | `ImportContactsToSfdcTest` (surefire, mocked client) and `ImportContactsToSfdcIT` (failsafe, live org) |
| test flows `selectContactFromSalesforce` (`sfdc:query-single`) and `deleteContactFromSalesforce` (`sfdc:delete`) of `src/test/resources/testflows/test-flows.xml` | `SalesforcePartnerClient#query` and `SalesforcePartnerClient#delete` inside `ImportContactsToSfdcIT` |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (register in the root [`DECISIONS.md`](../../DECISIONS.md)):

- This project holds the reference implementation of **SC-4 `mapper/DwCsvReader`** and its test `mapper/DwCsvReaderTest`. The other CSV-reading projects copy both with only the `package` line changed: `import-leads-into-salesforce`, `dataweave-with-flowreflookup`, `import-contacts-into-ms-dynamics`, `importing-a-CSV-file-into-Mongo-DB`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector` and `sending-a-csv-file-through-email-using-smtp`.
- It carries copies, each with only its `package` line changed and with its tests:
  - SC-5 `trigger/FileInboundPoller` from `processing-orders-with-dataweave-and-APIkit-java`;
  - SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException` from `salesforce-data-retrieval-java`, without `UpstreamExceptionHandler`: there is no HTTP caller to answer;
  - SC-15 `client/SalesforcePartnerClient`, `client/SalesforceFailureClassifier` and `config/SalesforceProperties` from `salesforce-data-retrieval-java`;
  - ST-2 `FixtureParityTest` and the `SCENARIOS.md` format from `hello-world-java`.
- It carries no SC-1 and no ST-1, because it has no HTTP listener.

## Connector failure modes (AAP 0.4.8)

Every Salesforce call, the lazy SOAP `login` included, runs through SC-9 `client/UpstreamCallExecutor` and is classified by `client/SalesforceFailureClassifier`. The project is file-triggered, so no caller receives a 502, 429 or 503: the classification is logged at ERROR with the upstream name `Salesforce`, and the file is then moved as after any handled failure.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or session expiry: an `ApiFault`/`LoginFault`, or a SOAP fault without detail, with code `INVALID_SESSION_ID` | WARN `Upstream Salesforce rejected the session [AUTHENTICATION]; re-authenticating and retrying once`, a new `login`, then one retry; a second rejection ends the attempt | ERROR `Upstream Salesforce call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, file moved |
| Throttling: code `REQUEST_LIMIT_EXCEEDED` | no retry | ERROR `Upstream Salesforce call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException` without `Retry-After` (Salesforce sends none), file moved |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`) | never retried, even when the chain also holds `INVALID_SESSION_ID`; this protects the `create` write | ERROR `Upstream Salesforce call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, file moved |
| Anything else (`INVALID_LOGIN`, `INVALID_FIELD`, `INVALID_TYPE`, …) | rethrown unchanged | handled like Mule's default exception strategy: ERROR `Exception processing file <name>: …`, file moved |

- The connect and read timeouts are 10 s each (`sfdc.connect-timeout`, `sfdc.read-timeout`), the Mule default response timeout.
- The client sends its SOAP requests with a chunked body, which the JDK never re-sends after a dropped connection, so a connectivity failure reaches the executor after exactly one request.
- Per-record errors in the `create` save results (a rejected field value or a duplicate rule, for example) are not raised or logged, as in the original flow: the service returns the save results unchanged and the trigger discards them.
- The ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.

Measured on this build with placeholder values for the three credential keys, which the client requires before it sends a `login`, and `sfdc.auth-endpoint` pointed at a closed local port (timestamps and thread prefixes removed):

```console
$ java -jar target/import-contacts-into-salesforce-java-1.0.0-SNAPSHOT.jar --contacts.input-dir=target/run/in --contacts.move-to-dir=target/run/out --contacts.polling-frequency=2000 --sfdc.auth-endpoint=http://127.0.0.1:<closed port>/services/Soap/u/65.0 --sfdc.username=placeholder --sfdc.password=placeholder --sfdc.securityToken=placeholder
$ cp src/main/resources/contacts.csv target/run/in/
ERROR c.m.e.i.client.UpstreamCallExecutor      : Upstream Salesforce call failed [UNAVAILABLE]: com.sforce.ws.ConnectionException at com.sforce.ws.transport.SoapConnection.send(SoapConnection.java:128) <- java.net.ConnectException
ERROR c.m.e.i.trigger.FileInboundPoller        : Exception processing file contacts.csv: com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamUnavailableException at com.mulesoft.examples.import_contacts_into_salesforce.client.UpstreamCallExecutor.translate(UpstreamCallExecutor.java:276)
```

Afterwards `target/run/in/` was empty and `target/run/out/contacts.csv` was byte-identical to the dropped file.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Salesforce authentication is the original's: the SOAP Partner API `login` with `sfdc.username` and `sfdc.password` immediately followed by `sfdc.securityToken` (SC-15, AAP 0.10.2), not an OAuth grant, so no connected app is needed. The client logs in lazily on the first Salesforce call, so the application starts with every secret empty.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `spring.application.name` | `String` | `import-contacts-into-salesforce-java` | none | fixed | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | the original has no HTTP listener, so no web server starts and no port is bound |
| `contacts.input-dir` | `String` (a path) | `src/main/resources/input` | none; the original hard-coded `path` on the file endpoint (xml:9) | default | directory polled for CSV files |
| `contacts.move-to-dir` | `String` (a path) | `src/main/resources/output` | none; the original hard-coded `moveToDirectory` (xml:9) | default | directory each polled file is moved to under its own name |
| `contacts.polling-frequency` | `long` (ms) | `10000` | none; the original hard-coded `pollingFrequency` (xml:9) | default | fixed delay between the end of one poll and the start of the next; a zero, negative or unreadable value stops start-up with an error naming the key and the value |
| `sfdc.username` | `String` | none, `${SFDC_USERNAME:}` | **`sfdc.user`** (xml:4) | TODO | the Salesforce user the `login` call authenticates; renamed because the request's key names govern (AAP 0.4.6) |
| `sfdc.password` | `String` | none, `${SFDC_PASSWORD:}` | `sfdc.password` (unchanged, xml:4) | TODO (secret) | that user's password, without the security token |
| `sfdc.securityToken` | `String` | none, `${SFDC_SECURITYTOKEN:}` | `sfdc.securityToken` (unchanged, xml:4) | TODO (secret) | appended to the password at login; `sfdc.security-token` binds as well |
| `sfdc.auth-endpoint` | `String` | `https://login.salesforce.com/services/Soap/u/65.0` | none; the original `sfdc:config` sets no URL, so its connector logged in at its default login URL | default | Partner SOAP login endpoint of API 65.0 (`force-partner-api` 65.0.0); a sandbox org uses `https://test.salesforce.com/services/Soap/u/65.0` |
| `sfdc.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every Salesforce call (AAP 0.4.8); a bare number binds as milliseconds; a value under 1 ms stops the application at startup |
| `sfdc.read-timeout` | `Duration` | `10s` | none | default | read timeout of every Salesforce call (AAP 0.4.8), with the same rules |

- Original-to-new key map: `sfdc.user` → `sfdc.username`; `sfdc.password` and `sfdc.securityToken` keep their names. The live test reads the same three keys from `src/test/resources/application-it.yml`, where the original test read `sfdc.user`, `sfdc.password` and `sfdc.securityToken` from `mule.test.properties`.
- `config/SalesforceProperties` binds the `sfdc.*` keys; its `toString()` masks the password and the token.
- The project has no `src/test/resources/application-test.yml`: the unit tests mock Salesforce or stub it with WireMock and need no credential. Surefire and failsafe set `contacts.input-dir`, `contacts.move-to-dir` and `contacts.polling-frequency` (`3600000`) as system properties pointing under `target/`, so no test polls or moves files in `src/main/resources/input` or `src/main/resources/output`.
- Supply the credentials through the environment, never in a committed file:

  ```sh
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' mvn spring-boot:run
  # a sandbox org, with the directories under target/
  SFDC_USERNAME='<user>' SFDC_PASSWORD='<password>' SFDC_SECURITYTOKEN='<token>' java -jar target/import-contacts-into-salesforce-java-1.0.0-SNAPSHOT.jar --sfdc.auth-endpoint=https://test.salesforce.com/services/Soap/u/65.0 --contacts.input-dir=target/run/in --contacts.move-to-dir=target/run/out
  ```

  An external `application.yml` (for example `--spring.config.additional-location=<file>`) outside the repository works as well.

## Behaviours stubbed, externalized or changed

- Externalized: the Salesforce credentials, which the original also read from placeholders, are `TODO` keys with environment-variable fallbacks; `sfdc.user` is renamed `sfdc.username`. The file endpoint's path, move directory and polling frequency, hard-coded in the original, are configurable keys with the original values as defaults; the login URL and timeouts, defaults of the original connector, are configurable keys.
- Changed: Mule's connection pooling (`WHEN_EXHAUSTED_GROW`, `INITIALISE_ONE`) is replaced by one cached `PartnerConnection`, re-created when Salesforce rejects the session.
- Stubbed in tests only: the unit tier replaces Salesforce with a Mockito-mocked `SalesforcePartnerClient` (`ImportContactsToSfdcTest`, service and trigger tests) or with WireMock Partner SOAP stubs (`client/SalesforcePartnerClientTest`). At run time nothing is stubbed and no behaviour was dropped.
- Test harness: the test flows `selectContactFromSalesforce` and `deleteContactFromSalesforce` are replaced by `SalesforcePartnerClient#query` and `#delete` inside `ImportContactsToSfdcIT`, and the original's fixed 8000 ms sleep by a direct `pollOnce()` plus bounded Awaitility waits.
- Not carried: Studio metadata (`catalog/**`, `mule-project.xml`), `src/main/app/mule-deploy.properties`, `src/test/resources/log4j2-test.xml` and the test flows file (AAP 0.2.2).

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- File disposition after an upstream failure: the file is moved to `contacts.move-to-dir`, as after a success.
- Null cells in the `create` request: a column missing from a short row maps to `null`, and `SalesforcePartnerClient#create` sends no field for a `null` value; an empty cell is sent as an empty string.
- The exact SOAP `create` request: its envelope, field order (`FirstName`, `LastName`, `Email`, `Phone`) and Partner API version 65.0, where the original connector spoke an older API version.
- An empty or header-only file: the service calls `SalesforcePartnerClient#create` with an empty list, which returns without contacting Salesforce, and the file is moved.
- A file of more than 200 records is sent in one `create` call, unchunked, as `sfdc:create` received `#[payload]`. Salesforce accepts at most 200 records per `create` call, so such a file fails as a whole (an `OTHER` failure: ERROR log, file moved).
- CSV edge cases that no original input exercises (ragged rows, a backslash, a byte order mark), listed in `mapper/DwCsvReader`.

## Build, run and test

Run from `java-conversions/import-contacts-into-salesforce-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network
mvn -B verify           # surefire tests, then the failsafe ImportContactsToSfdcIT against a live org (Tier 2B)
mvn spring-boot:run     # polls src/main/resources/input every 10000 ms; needs sfdc.username, sfdc.password and sfdc.securityToken
java -jar target/import-contacts-into-salesforce-java-1.0.0-SNAPSHOT.jar
```

To import the sample, as the original README's steps did, copy the bundled file into the input directory while the application runs:

```sh
mkdir -p src/main/resources/input
cp src/main/resources/contacts.csv src/main/resources/input/
```

Within one polling interval the file moves to `src/main/resources/output/contacts.csv`, and Salesforce lists John Doe and Jane Doe under **Contacts**. Delete both Contacts afterwards. For a large file, copy it next to the input directory first and then move it in, so a poll never reads a half-written file.

- `mvn test` needs no licence, credential or network: Salesforce is a Mockito-mocked `SalesforcePartnerClient` or a WireMock Partner SOAP stub.
- Without the three `sfdc.*` values the application still starts, but every import fails before any Salesforce request: the lazy `login` first checks that `sfdc.username`, `sfdc.password` and `sfdc.securityToken` are non-blank and throws an `IllegalStateException` naming each missing key, never a value. That, like a rejected login (`INVALID_LOGIN`), is none of the three failure modes, so it is logged by `FileInboundPoller` at ERROR and the file is moved.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The Salesforce Partner API runs on WSC's own XML stack.

## Live test (Tier 2B)

`ImportContactsToSfdcIT` runs under failsafe (`mvn -B verify`) against a real org, reusing the existing Salesforce credentials; no new sandbox is needed.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `sfdc.username`, `sfdc.password` (without the token) and `sfdc.securityToken`. For a sandbox org, also set `sfdc.auth-endpoint: https://test.salesforce.com/services/Soap/u/65.0`.
3. Run `mvn -B verify`. The test starts the application without a web server under the Spring profile `it`, which loads `application-it.yml`.

The IT writes data, so use a sandbox org, never production:

- It drops `contacts.csv` into its own temporary input directory and runs `ContactsFileTrigger.pollOnce()`, which creates John Doe and Jane Doe in the org.
- It queries each Contact by e-mail with the original test flow's SOQL `SELECT FirstName,Id,LastName FROM Contact WHERE Email = '<email>'`, waiting up to 60 s, and asserts `FirstName` `John` for `john.doe@texasComp.com` and `LastName` `Doe` for `jane.doe@texasComp.com`, as the original asserted (`ImportContactsToSfdcIT.java:101,105`).
- Contacts with those two e-mails that an earlier run left behind are recorded first, so only a Contact this import created satisfies the assertions.
- `@AfterEach` deletes every Contact read back for the two e-mails, the earlier ones included. A failed delete is logged, never thrown, as the original only printed it.
- The wait polls on the test thread, so a query still running at the timeout has recorded the IDs it returned before the wait returns and `@AfterEach` reads them.
- A failed cleanup query or delete is logged at WARN with the fixture e-mail or the record IDs and the class names of the exception and its causes only, and a failed delete result with its status codes only: no vendor message, cause or suppressed message, error field or stack trace.

While `application-it.yml` or one of the three keys is missing, blank or still `TODO`, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption: reported skipped, never failed, and no application starts. A skipped run is not Tier 2B evidence.

## Tests

| Test class | What it asserts |
|---|---|
| `ImportContactsToSfdcTest` | the ported `ImportContactsToSfdcIT` (`import-contacts-into-salesforce/src/test/java/org/mule/examples/ImportContactsToSfdcIT.java:86-105`) with the whole application context against a mocked `SalesforcePartnerClient`: `contacts.csv` dropped into a temporary input directory and `pollOnce()` lead to exactly one `create` of type `Contact` with two records, `FirstName` `John` for `john.doe@texasComp.com` and `LastName` `Doe` for `jane.doe@texasComp.com`; also every field of both records (phones keep their leading zero), the file moved unchanged to the output directory, and no other Salesforce call |
| `ImportContactsToSfdcIT` | the same two assertions against a live org (failsafe, previous section) |
| `ImportContactsToSfdcITCleanupTest` | the wait and `@AfterEach` of `ImportContactsToSfdcIT` without credentials or a Spring context, against a mocked `SalesforcePartnerClient`: a query that answers after the wait's timeout runs on the test thread, its Contact ID is recorded before the wait returns and is then deleted; cleanup queries and deletes failing with exception, cause and suppressed messages that carry personal fields and secrets are logged at WARN by class names only (circular chains marked, chains cut after five names), failed delete results by status codes only, no throwable attached, every delete still attempted |
| `trigger/ContactsFileTriggerTest` | `contacts.csv` handed byte for byte to the import once and moved to the output directory; a Salesforce read timeout and any other import failure logged at ERROR, not retried, file still moved; an empty input directory hands nothing over; `poll()` scheduled with the original 10000 ms fixed delay |
| `trigger/FileInboundPollerTest` | SC-5: move after success and after a handled failure, delete without a move directory, the interrupt flag kept, the in-process mark against re-entrant and concurrent polls, an unopenable file left in place and retried, a file too large to read (over the read limit, or one whose allocation fails with `OutOfMemoryError`) left in place while the other files are consumed, missing directories created, subdirectories ignored, a same-named file in the move directory replaced |
| `service/ContactImportServiceTest` | every record of `contacts.csv` sent in one `create` call; a header-only file still calls `create` once with no records; every client failure propagated unchanged |
| `mapper/ContactMapperTest` | the four keys in script order; John Doe and Jane Doe in input order; a missing column gives `null`, an empty or `null` row four `null` values; values passed through without coercion or trimming; extra columns ignored; the input left unmodified |
| `mapper/DwCsvReaderTest` | SC-4 reference test: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed string values |
| `client/SalesforcePartnerClientTest` | SC-15 against WireMock Partner SOAP stubs: login with password + token, one lazily created connection reused, `query`, `queryAll` paging, `create`, `delete`, `describeGlobal`, `describeSObject`, session mode; one re-login and retry after `INVALID_SESSION_ID`, no retry after `REQUEST_LIMIT_EXCEEDED`, a read timeout that never re-sends the `create`, `INVALID_LOGIN` rethrown unchanged; the production login endpoint and 10 s default timeouts |
| `client/SalesforceFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Salesforce classification: re-login and one retry on `INVALID_SESSION_ID`, no retry on `REQUEST_LIMIT_EXCEEDED` (no `Retry-After`) or on any timeout or connectivity cause, the unchanged rethrow of every other failure, ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `file` trigger the replay places the captured input file, calls `ContactsFileTrigger.pollOnce()` and compares the produced files, the SC-5 disposition and the recorded Salesforce SOAP exchanges, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `file-contacts-csv-imported` | `file` | `deterministic` | drop `contacts.csv` into the input directory, then `ContactsFileTrigger.pollOnce()` | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`; the flow has one branch.
- The recorded Salesforce `login` carries live credentials and returns the org's live instance `serverUrl`, which the replay would follow. The capture therefore omits `upstream`, so the scenario stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, "Notes for this project"). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 1 unverified`.
- The approved failure modes have no original branch and no fixture row; their Tier 1 evidence is the classifier, executor, poller and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| import-contacts-into-salesforce-java | import-contacts-into-salesforce | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

The root `SUMMARY.md` carries the same row for this project (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 692 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`ImportContactsToSfdcIT`: `src/test/resources/application-it.yml not supplied`).
- `mvn -B clean package`: `BUILD SUCCESS`; 692 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 692 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 92 of 92 lines (100.0%) of `service/ContactImportService`, `mapper/ContactMapper` and `mapper/DwCsvReader`. Total project LINE coverage: 91.8% (659 of 718 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=1`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `ImportContactsToSfdcIT` was skipped; the cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md) ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `ImportContactsToSfdcTest` carries the original committed assertions with their comparison semantics: `FirstName` equals `John` for `john.doe@texasComp.com` and `LastName` equals `Doe` for `jane.doe@texasComp.com`, after `contacts.csv` is imported.
- The trigger, poller, service, mapper and client tests cover the flow's one branch and the approved failure handling.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the platform defaults, but not that its SOAP requests and file handling equal what the original runtime did. The Salesforce side of these tests is mocked or stubbed, not recorded from the original's org.
- Behaviours no original assertion covers, tested only by the new tests: the file disposition to `output`; the `Phone` and `Email` mapping of Jane's row (and every field other than John's `FirstName` and Jane's `LastName`); the failure-mode paths; empty and header-only files; quoted CSV fields.
- Unverified scenarios, until captured: `file-contacts-csv-imported`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenario is captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `import-contacts-into-salesforce/`.
