# importing-a-CSV-file-into-Mongo-DB-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example "Importing a CSV file into mongoDB", [`importing-a-CSV-file-into-Mongo-DB`](../../importing-a-CSV-file-into-Mongo-DB/) (category General). The original folder `importing-a-CSV-file-into-Mongo-DB/` stays unchanged beside this project as the reference and the Tier 2A capture source.

- Package `com.mulesoft.examples.importing_a_csv_file_into_mongo_db`; main class `ImportingACSVFileIntoMongoDBApplication` (`@SpringBootApplication`, `@EnableScheduling`).
- No web server (`spring.main.web-application-type: none`). The original has no HTTP listener, so no port, path, listener 404/405 default or SC-1 error handling applies.

## Original example

The example imports CSV data from a local directory into MongoDB with the MongoDB connector (`mule-module-mongo` 3.6.1). Its single flow `csv-to-mongodbFlow1` (`importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml`, loaded through `config.resources=csv-to-mongodb.xml`) polls `src/main/resources/input` with a file endpoint, and a DataWeave script turns each CSV record into a map with `firstname`, `surname`, `phone` and `email`. A Message Enricher stores in `flowVars['existsCollection']` whether the collection `customers` exists. A choice creates the collection when it is absent and otherwise logs that it exists. A For Each then saves each map to `customers`. The flow declares no exception strategy and no transaction, and its `mule-app.properties` is empty.

## Endpoints and triggers

There is no HTTP endpoint. The only trigger is a file poller:

| Trigger | Component | Input directory | Polling | Disposition | Source |
|---|---|---|---|---|---|
| file inbound endpoint | `trigger/CsvFileTrigger`, `@Scheduled(fixedDelayString = "${csv-import.polling-frequency:1000}")` on `poll()`, over SC-5 `trigger/FileInboundPoller` | `csv-import.input-dir`, default `src/main/resources/input`, resolved against the working directory as the original's relative `path` was | every `csv-import.polling-frequency` ms, default `1000` (the Mule file connector default, because the endpoint declares no `pollingFrequency`), as a fixed delay between the end of one poll and the start of the next | deleted once the import returns or fails, because the endpoint declares no `moveToDirectory` | `csv-to-mongodb.xml:9` (`path="src/main/resources/input" responseTimeout="10000"`) |

`CsvFileTrigger.pollOnce()` runs one poll on demand and returns the number of files handed to the import, counting a file whose import failed. Tests and the fixture replay call it. It shares the scheduled poll's poller, so neither run picks up a file the other is processing.

SC-5 semantics, as `FileInboundPoller` implements Mule 3.8's default file connector (AAP 0.4.4):

- A relative input directory resolves against the working directory, and the directory is created when missing.
- Regular files are taken in directory-listing order, unsorted and unfiltered. Subdirectories are ignored.
- A file in process is marked, so an overlapping poll never hands it over twice.
- When the import returns, or throws an exception, the file is deleted. The poller logs the exception at ERROR (`Exception processing file <name>: <exception class, throw site and cause classes>`, never its message) and does not rethrow it, as Mule's default exception strategy ended the flow. No file is retried, so no document is saved twice by a retry. A failed deletion is logged at WARN.
- A file that cannot be opened is logged at WARN and left in place, unmarked, for the next poll.

### Flow steps

`service/CustomerImportService.importCsv(byte[])` runs the flow body for each polled file, in the original processor order:

1. SC-4 `mapper/DwCsvReader` reads the bytes as UTF-8 CSV with a header row. `mapper/CustomerDocumentMapper` applies the `CSV to Map` script (l.10-21) and returns an `ArrayList` holding one `LinkedHashMap` per record, with the keys `firstname`, `surname`, `phone` and `email` in that order.
2. INFO `saving objects: <records>` (l.23), the list rendered by `List`/`Map` `toString()`. For `input.csv` the line reads `saving objects: [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]`.
3. `MongoTemplate.collectionExists("customers")` (the enricher around `mongo:exists-collection`, l.24-26). The result is kept in the local variable `existsCollection`; the records are left unchanged.
4. When the collection is absent, `MongoTemplate.createCollection("customers")` (l.28-29). Otherwise, INFO `Customer Collection already exists.` (l.31-32).
5. One `MongoTemplate.save(new Document(record), "customers")` per record, in record order (the For Each around `mongo:save-object-from-map`, l.35-39).

- Steps 3 and 4 run even when the CSV holds a header and no data record, as in the original, whose check and choice do not depend on the records. Nothing is saved then. An empty file reads as no record and takes the same path.
- Reading never fails on malformed CSV: SC-4 applies DataWeave 1.0's lenient reader rules (see the `DwCsvReader` Javadoc). A failed collection check, creation or save propagates unchanged out of the service, and no later record is saved. The poller then logs it and deletes the file. Documents saved before the failure stay, because the flow declares no transaction.

Data notes:

- CSV values stay the strings written, never trimmed or coerced: `096548763` keeps its leading zero and is stored as a string.
- Each document carries the keys `firstname`, `surname`, `phone` and `email` in that order, plus the `_id` MongoDB assigns. No `_class` key is written.
- A column the script names but a record lacks is stored with a `null` value, as DataWeave's `$.x` yields `null`. A column the script does not name is not stored.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. The original's `mule-app.properties` holds no keys; its `mongo:config` (l.4) reads the three `database.*` placeholders.

Status values:

- `default`: a non-secret value the original fixed or defaulted, overridable.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). A secret is marked `(secret)`.
- `fixed`: a framework setting that wires the original's configuration, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `database.name` | `String` | none (`${DATABASE_NAME:}`) | `database.name` (unchanged) | TODO | database that holds the collection `customers`; bound to `spring.data.mongodb.database`. With no `spring.data.mongodb.authentication-database` set, the user authenticates against this database, as the Mule connector did against its `database`. |
| `database.user` | `String` | none (`${DATABASE_USER:}`) | `database.user` (unchanged) | TODO | MongoDB user; bound to `spring.data.mongodb.username` |
| `database.password` | `String` | none (`${DATABASE_PASSWORD:}`) | `database.password` (unchanged) | TODO (secret) | the user's password; bound to `spring.data.mongodb.password` |
| `spring.data.mongodb.host` | `String` | `localhost` | none: Mule MongoDB connector default | default | `mongo:config` names no host, so the connector's default applied |
| `spring.data.mongodb.port` | `Integer` | `27017` | none: Mule MongoDB connector default | default | `mongo:config` names no port, so the connector's default applied |
| `spring.data.mongodb.database` | `String` | `${database.name}` | none | fixed | the `mongo:config database` attribute |
| `spring.data.mongodb.username` | `String` | `${database.user}` | none | fixed | the `mongo:config username` attribute |
| `spring.data.mongodb.password` | `char[]` | `${database.password}` | none | fixed | the `mongo:config password` attribute |
| `csv-import.input-dir` | `String` (a `Path`) | `src/main/resources/input` | none: `file:inbound-endpoint path` (l.9) | default | directory polled for CSV files |
| `csv-import.polling-frequency` | `long` (ms) | `1000` | none: the file connector's default `pollingFrequency` | default | delay between polls |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | no HTTP listener, as in the original |

All three `database.*` keys must be supplied before `mvn spring-boot:run` or `java -jar`; the application does not start while one is empty. An empty `database.password` cannot bind to `spring.data.mongodb.password`, and an empty `database.user` makes the MongoDB driver reject the connection string Boot builds. The original could not deploy with an unresolved `${…}` placeholder either. Supply the keys through the environment variables above, `--key=value` arguments or a local, uncommitted override file, and never commit a real value.

A user created in another database than `database.name`, for example in `admin`, needs Boot's `spring.data.mongodb.authentication-database=<that database>` as well.

Original-to-new key map:

- Unchanged: `database.name`, `database.user` and `database.password` keep the names of the original's `mongo:config` placeholders. The original IT set the same three keys as system properties. No key is renamed.
- Added: `csv-import.input-dir` and `csv-import.polling-frequency` carry the values the original hard-coded (`path`) or left to the file connector's default (`pollingFrequency`). `spring.data.mongodb.host` and `spring.data.mongodb.port` state the connector default the original relied on.

`src/test/resources/application-test.yml` (profile `test`, which surefire activates through `spring.profiles.active=test`) supplies `database.name: customers`, `database.user: test_user` and `database.password: test_password`, the values of the original IT, so the placeholders resolve in tests. Tests that start the application point `spring.data.mongodb.uri` at an in-memory `mongo-java-server` through `@DynamicPropertySource`. Once the URI is set, Boot ignores `spring.data.mongodb.host`, `port` and the credentials, while `database.name` still selects the database. Surefire also points `csv-import.input-dir` at `target/test-csv-import/input`, so a test context's scheduled poll never touches `src/main/resources/input`. The project is not a connector project, so it has no credential file, no `application-it.yml` and no live tier.

## Mule-to-Java mapping

| Mule construct (`importing-a-CSV-file-into-Mongo-DB`) | Java construct (`com.mulesoft.examples.importing_a_csv_file_into_mongo_db`) |
|---|---|
| `mongo:config database username password` (l.4) | `spring.data.mongodb.*` bound from `database.*`; Boot's auto-configured `MongoClient` and `MongoTemplate` (`spring-boot-starter-data-mongodb`, MongoDB Java driver 4.11.5) |
| `mongo:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` (l.5) | the MongoDB driver's own connection pool, default settings |
| `file:inbound-endpoint path="src/main/resources/input" responseTimeout="10000"` (l.9) | `trigger/CsvFileTrigger` over SC-5 `trigger/FileInboundPoller` |
| `dw:transform-message` `CSV to Map`, `payload map { firstname, surname, phone, email }` (l.10-21) | SC-4 `mapper/DwCsvReader` and `mapper/CustomerDocumentMapper`, the script's owner (AAP 0.5.8) |
| `logger level="INFO" message="saving objects: #[payload]"` (l.23) | SLF4J INFO `saving objects: {}` in `service/CustomerImportService` |
| `enricher` with `mongo:exists-collection collection="customers"`, target `flowVars['existsCollection']` (l.24-26) | `MongoTemplate.collectionExists("customers")` into the local `existsCollection` in `service/CustomerImportService` |
| `choice`: `when !flowVars['existsCollection']` → `mongo:create-collection collection="customers"`; `otherwise` → `logger` (l.27-34) | `if (!existsCollection) createCollection("customers") else` INFO `Customer Collection already exists.` |
| `foreach` with `mongo:save-object-from-map collection="customers"` (l.35-39) | one `MongoTemplate.save(new Document(record), "customers")` per record, in record order |
| default exception strategy (none declared) | the ERROR log of `FileInboundPoller`, then the SC-5 deletion |
| `mule-deploy.properties` (`config.resources=csv-to-mongodb.xml`) | `ImportingACSVFileIntoMongoDBApplication` |
| `mule-app.properties` (empty) and the `${…}` placeholders | `src/main/resources/application.yml` |
| `ImportCSVfileIntoMongoDBIT` (`FunctionalTestCase`, `MuleClient`, Flapdoodle MongoDB) | `ImportCSVfileIntoMongoDBTest` (`@SpringBootTest(webEnvironment = NONE)`, `mongo-java-server`) |

## Behaviours stubbed, externalized or changed

- Credentials are externalized. The original tutorial's demo user `mule`/`mule` (original README step 3) and the IT's `test_user`/`test_password` are no longer in code or main configuration. The three `database.*` keys are TODO placeholders under their original names, and the IT's values survive only in `src/test/resources/application-test.yml`, for tests.
- `mongo:connection-pooling-profile exhaustedAction="WHEN_EXHAUSTED_GROW" initialisationPolicy="INITIALISE_ONE"` becomes the MongoDB Java driver's own connection pool. No equivalent setting is needed: one sequential flow uses one connection at a time.
- `responseTimeout="10000"` on the one-way file endpoint has no Java counterpart, because nothing waits for a response.
- Original README steps 5 and 7 (set the connector's user, password and database in Studio; edit the File connector's path) become the `database.*` keys and `csv-import.input-dir`.
- The original IT's Flapdoodle MongoDB 2.0.5 (`de.flapdoodle.embed.mongo` 1.27) becomes `de.bwaldvogel:mongo-java-server` 1.45.0, an in-memory MongoDB wire-protocol server bound to a free loopback port (AAP 0.6.4, 0.8.3). It does not authenticate, so tests connect without credentials, and the IT's `db.addUser` and `dropUser` are not ported.
- Tier 2A verification points: the INFO text `saving objects: ...` renders the record list through `java.util.List`/`Map` `toString()` (for `input.csv`, `[{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]`). `CustomerImportService` logs both lines under its own class name, while the flow's `logger` elements declared no category. A captured log event governs where it differs, and `service/CustomerImportService` is changed to match.
- The original README's sample document shows a `state` field and placeholder values from an older CSV. The carried `input.csv` has an `email` column, so each document holds `firstname`, `surname`, `phone` and `email`.
- Not carried (AAP 0.2.2): `catalog/**`, `mule-project.xml`, `src/main/app/mule-deploy.properties`, the empty `src/main/app/mule-app.properties` and `src/test/resources/log4j2-test.xml`. `src/main/resources/input.csv` is carried unchanged.

## Build, run and test

Building and testing need JDK 17 and Maven 3.9 only: no MongoDB server and no credentials. Running the application needs a MongoDB server on `localhost:27017` with a database `customers` and a user, as in the original tutorial steps 1 to 3. In the mongo shell, with a user name and password of your own choosing:

```js
use customers
db.createUser({ user: "<user>", pwd: "<password>", roles: [ { role: "readWrite", db: "customers" } ] })
```

The original tutorial granted the role `userAdmin` on `admin`. On a server with access control enabled the saves need `readWrite` on `customers`, as above.

Run from `java-conversions/importing-a-CSV-file-into-Mongo-DB-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
DATABASE_NAME=customers DATABASE_USER='<user>' DATABASE_PASSWORD='<password>' mvn spring-boot:run
DATABASE_NAME=customers DATABASE_USER='<user>' DATABASE_PASSWORD='<password>' \
  java -jar target/importing-a-CSV-file-into-Mongo-DB-java-1.0.0-SNAPSHOT.jar
```

The input directory is relative to the working directory. Both run commands above start from the project directory, so they poll `src/main/resources/input`, as the original did. To keep run-time files out of the source tree, pass `--csv-import.input-dir=target/run/input` (with `mvn spring-boot:run`, through `-Dspring-boot.run.arguments=...`).

Usage, following the original README steps 6 to 9:

1. Start the application with the three keys supplied.
2. Copy the sample file into the input directory:

   ```sh
   mkdir -p src/main/resources/input && cp src/main/resources/input.csv src/main/resources/input/
   ```

3. Within one polling interval (1 s) the file is deleted from `src/main/resources/input`, and the log shows `saving objects: [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]`.
4. In the mongo shell, `db.customers.find()` in database `customers` shows one document with `firstname` `John`, `surname` `Doe`, `phone` `"096548763"` and `email` `john.doe@texasComp.com`, plus its `_id`.
5. Copying the file again takes the `otherwise` branch: the log shows `Customer Collection already exists.` and a second document is saved, as in the original, which never checks for duplicates.

- `mvn verify` adds nothing here: the project is not a connector project, so it has no failsafe plugin, no `*IT` class and no `application-it.yml` (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

All tests run under surefire with no external server and no credentials.

| Test class | What it asserts |
|---|---|
| `ImportCSVfileIntoMongoDBTest` | The ported `ImportCSVfileIntoMongoDBIT` (`importing-a-CSV-file-into-Mongo-DB/src/test/java/org/mule/examples/ImportCSVfileIntoMongoDBIT.java:94-110`): after `input.csv` is moved into the input directory and `CsvFileTrigger.pollOnce()` runs, collection `customers` of database `customers` holds exactly one document within the original's 5 seconds, on a `mongo-java-server`. It also asserts that the polled file is deleted and that the document holds `John`, `Doe`, the string `096548763` and `john.doe@texasComp.com` under the four keys in script order, with an `_id` and no `_class`. |
| `trigger/CsvFileTriggerTest` | With a mocked `CustomerImportService` and a temporary input directory: `input.csv` is handed over once with its exact bytes and deleted; a failing import (an unreachable MongoDB, or an `IllegalArgumentException` standing for a malformed CSV) neither escapes the poll nor leaves the file for a retry; an empty directory hands nothing over; `poll()` runs the same single poll as `pollOnce()` and is scheduled at the 1000 ms default. |
| `trigger/FileInboundPollerTest` | SC-5, copied with only its `package` line changed. A file is moved after success, or deleted without a move directory, and is consumed after a handler failure or interrupt. Neither a re-entrant nor a concurrent poll redelivers it. An unopenable file is left in place and retried. Missing directories are created and subdirectories ignored. A move replaces an existing file of the same name. The returned count is checked. |
| `service/CustomerImportServiceTest` | With a Mockito `MongoTemplate` and the real reader and mapper: an absent collection is created and the record saved; the `saving objects:` line is logged, as a parameterized message over the record list, before the collection check; an existing collection logs `Customer Collection already exists.` and still saves; several records are saved once each, in input order; a header without records still runs the check and the choice and saves nothing; a failed check or save propagates unchanged and no later record is saved. |
| `mapper/CustomerDocumentMapperTest` | The `CSV to Map` script: one `LinkedHashMap` per row inside an `ArrayList`, in input order; keys in script order whatever the column order; values kept as the reader's strings; a missing column kept as a `null` value; unnamed columns dropped; new collections on every call, independent of the input. |
| `mapper/DwCsvReaderTest` | SC-4, copied with only its `package` line changed: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed and uncoerced string values, header order kept. |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/importing_a_csv_file_into_mongo_db/FixtureParityTest.java` is ST-2, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds their fixture contract and capture procedure. The reference implementation and its fixture format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation).

Both scenarios use the `file` trigger. The replay starts the application context non-web, seeds `mongo-java-server` with the fixture's `state`: collection `customers` dropped for the first row, holding the document the first row stored for the second. It places the captured file in a temporary input directory and calls `trigger.CsvFileTrigger.pollOnce()`. It then compares the final file set of the input directory (the SC-5 deletion), the documents of `customers` without the server-assigned `_id`, and the events of the flow's two loggers. The original makes no outbound call, so each fixture's `upstream` is `[]`. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

`SCENARIOS.md` scenarios:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `file-input-csv-collection-absent` | `file` | `deterministic` | drop `input.csv` into the input directory with no collection `customers`, run one poll through `CsvFileTrigger.pollOnce()` | `UNVERIFIED-uncaptured` |
| `file-input-csv-collection-exists` | `file` | `deterministic` | the same input, with `customers` holding the document of the first row | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`: both branches of the choice are reachable, and the flow reads no clock or random value.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| importing-a-CSV-file-into-Mongo-DB-java | importing-a-CSV-file-into-Mongo-DB | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 63 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 63 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 96 of 96 lines (`service/CustomerImportService` 17, `mapper/CustomerDocumentMapper` 11, `mapper/DwCsvReader` 68). Total project LINE coverage: 77.2% (200 of 259 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=2`, `failed=0`.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback of AAP 0.8.4:

- `ImportCSVfileIntoMongoDBTest` carries the original committed assertion: after `input.csv` is processed, collection `customers` of database `customers` holds one document.
- `CustomerImportServiceTest`, `CustomerDocumentMapperTest`, `DwCsvReaderTest`, `CsvFileTriggerTest` and `FileInboundPollerTest` cover both branches of the choice, the mapping, the CSV reading and the file disposition.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the connector, DataWeave and file-endpoint behaviour, not that its stored documents and log lines equal what the original runtime produced.
- The original IT asserted only a document count of 1. No original assertion covers, and only the new tests check:
  - the `collection-exists` branch and its log line `Customer Collection already exists.` (`file-input-csv-collection-exists`; `CustomerImportServiceTest`);
  - the stored field values and their key order (`ImportCSVfileIntoMongoDBTest`, `CustomerDocumentMapperTest`);
  - the deletion of the file after processing (`ImportCSVfileIntoMongoDBTest`, `CsvFileTriggerTest`, `FileInboundPollerTest`);
  - the text of the `saving objects:` log line (`CustomerImportServiceTest`).
- Unverified scenarios, until captured: `file-input-csv-collection-absent` and `file-input-csv-collection-exists` (both `deterministic`).

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until both scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `importing-a-CSV-file-into-Mongo-DB/`.

