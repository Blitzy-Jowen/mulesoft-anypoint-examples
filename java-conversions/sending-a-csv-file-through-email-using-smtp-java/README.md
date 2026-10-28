# Sending a CSV file through email using SMTP — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 / Maven conversion of the Mule EE 3.8.0 example [`sending-a-csv-file-through-email-using-smtp`](../../sending-a-csv-file-through-email-using-smtp/) (category General; flow `csv-to-smtpFlow` in `src/main/app/csv-to-smtp.xml`, the only file in `config.resources` of its `mule-deploy.properties`). It behaves as the original does: same input directory, same mapping, same mail. The original folder `sending-a-csv-file-through-email-using-smtp/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

A file trigger reads a CSV of sample sales data from a local directory. DataWeave converts it to a collection of maps and computes each order's total price as the number of units multiplied by the unit price. The collection is then emailed over SMTP. The original was preconfigured for Google's Gmail (`sending-a-csv-file-through-email-using-smtp/README.md:12`).

The single flow `csv-to-smtpFlow` (`csv-to-smtp.xml:4-21`) runs these processors in order:

- `file:inbound-endpoint path="src/main/resources/input" responseTimeout="10000"` (line 5). It declares no `pollingFrequency` and no `moveToDirectory`, and the application declares no `file:connector`;
- `dw:transform-message` "Transform CSV to Map" (lines 6-18; `%dw 1.0` header at line 8, `%output application/java`);
- `logger level="INFO" message="Processing objects: #[payload]"` (line 19);
- `smtp:outbound-endpoint` (line 20) through the `smtp:gmail-connector` of line 3 (`validateConnections="true"`). `host`, `user` and `password` come from `smtp.*` placeholders and `from`, `to` and `subject` from `mail.*`; the port is fixed at `port="587"`, with `responseTimeout="10000"`.

The flow declares no exception strategy and no transaction. `mule-app.properties` is empty; the original README sets the input path and the SMTP settings by hand (`README.md:18-32`). Below, `README.md:<lines>` cites that original README, and `csv-to-smtp.xml` and `SendingACsvFileThroughEmailUsingSmtpIT.java` cite the original's flow and test.

## Endpoints and triggers

There is no HTTP listener: `spring.main.web-application-type: none`, the only runtime starter is `spring-boot-starter-mail`, and the process opens no port. The file trigger is the only entry point.

| Trigger | Component | Input | Frequency | Disposition | Source |
|---|---|---|---|---|---|
| file poller | `trigger/CsvFileTrigger` over SC-5 `trigger/FileInboundPoller` | the regular files of `csv-email.input-dir`, default `src/main/resources/input`. As in the original `path`, a relative path resolves against the working directory | `@Scheduled(fixedDelayString = "${csv-email.polling-frequency:1000}")`: 1000 ms between the end of one poll and the start of the next. That is Mule's default `pollingFrequency`, because the endpoint declares none | each file is deleted once the flow returns, whether it completed or failed, because the endpoint declares no `moveToDirectory` and no transaction | `csv-to-smtp.xml:5` |

- Each poll creates the input directory when it is missing. It lists the directory's regular files in directory-listing order, unsorted and unfiltered, ignoring subdirectories. Each file is marked in process, so an overlapping poll never takes it twice, and its bytes go to `service/CsvEmailService.process`. A file that cannot be opened is left in place, unmarked, with a WARN line, and tried again on the next poll.
- `CsvFileTrigger.pollOnce()` runs one poll on demand through the same poller and returns the number of files it handed over, failed ones included. Tests and the fixture replay call it. Production polling stays scheduled (`poll()`).
- The endpoint's `responseTimeout="10000"` has no effect on a one-way file source and is not carried.

### Processing, in the original order

1. SC-4 `mapper/DwCsvReader` reads the file as UTF-8 CSV with the DataWeave 1.0 reader defaults: a header row, comma separator, `"` quoting, and every value an untrimmed string.
2. `mapper/OrderLinesMapper` owns the `%dw 1.0` script (AAP 0.5.8). It maps each row, in input order, to a map whose keys follow the script's order (`name`, `orderId`, `pricePerUnit`, `units`, `totalPrice`), not the CSV column order:
   - `name` and `orderId` are the CSV text, unchanged;
   - `pricePerUnit` and `units` (`as :number`) are exact `BigDecimal`s. A value equal to an integer is written with scale 0: `2.0` becomes `2` and `10` stays `10`, never `1E+1` (AAP 0.4.9). Any other value keeps the scale it was written with (`4.15`);
   - `totalPrice` is `units × pricePerUnit`, computed exactly and rendered by the same rule (`20`, `20.75`).
3. `service/CsvEmailService` logs the list at INFO as `Processing objects: <list>`, under the logger `com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.service.CsvEmailService`.
4. `client/CsvMailSender` sends one mail per file through Spring's `JavaMailSender` (Angus Mail):
   - from `mail.from` to `mail.to`, with subject `mail.subject`. A comma-separated `mail.to` gives one recipient per address;
   - the body is a single `text/plain; charset=UTF-8` part holding the list's `toString()`, which is how the Mule SMTP transport rendered a non-string payload;
   - there is no CC, BCC, attachment or extra header.
5. SC-5 deletes the file.

With the carried `src/main/resources/input.csv` (header `orderId,name,units,pricePerUnit`, rows `1,aaa,2.0,10` and `2,bbb,4.15,5`), the mail body is exactly step 5 of the original README (`README.md:36-38`) and `src/test/resources/email.txt`:

```text
[{name=aaa, orderId=1, pricePerUnit=10, units=2, totalPrice=20}, {name=bbb, orderId=2, pricePerUnit=5, units=4.15, totalPrice=20.75}]
```

### Failure behaviour

The original declares no exception strategy, so every failure ends the flow in Mule's default strategy, which logs it and lets the file connector consume the file. Here nothing is caught, logged or retried inside the flow. The exception propagates to SC-5, which logs one ERROR line and still deletes the file. The line reads `Exception processing file <name>: <exception class> at <throw site> <- <cause classes>`. No mail is sent for that file, and nothing is retried.

| Case | Example | Result |
|---|---|---|
| A `units` or `pricePerUnit` value that is not a number (values are never trimmed, so ` 2` is not a number either) | row `1,aaa,abc,10` | `IllegalArgumentException` (`units is not a number: abc`) before any mail; ERROR line; file deleted |
| A missing `units` or `pricePerUnit` column | header `orderId,name,units` | `IllegalArgumentException` (`totalPrice cannot be computed: …`); ERROR line; file deleted. Plan default, Tier 2A verification point: how DataWeave 1.0 multiplied a missing operand is unconfirmed |
| SMTP failure: server unreachable, connection dropped, account refused, or no answer within 10000 ms | — | a `MailException` (`MailSendException`, or `MailAuthenticationException` when the account is refused) after exactly one attempt; ERROR line; file deleted |
| Blank or malformed `mail.from`, malformed `mail.to` (blank `mail.from` is the state with the TODO keys unset) | — | `MailPreparationException` before any connection; ERROR line; file deleted |
| Blank `mail.to` | — | `MailSendException` (no recipient); ERROR line; file deleted |
| Header-only or empty file | `orderId,name,units,pricePerUnit` | one mail whose body is `[]`; file deleted |
| File that cannot be opened | — | WARN line, file left in place and tried on the next poll |

The ERROR and WARN lines name the file and the exception's class, throw site and cause classes. They never carry an exception message, file content or a stack trace; only a WARN about the poller's own file operations adds the operating system's reason.

## Configuration keys

Every key of `src/main/resources/application.yml` that the original's placeholders or fixed values map to (AAP 0.4.6, 0.6.6). A key with no default has no value until it is supplied. It is written `${ENV_NAME:}` with a `# TODO: supply …` comment, and is set through the named environment variable or a `--key=value` argument.

| Key | Default | Env variable | Original source | Notes |
|---|---|---|---|---|
| `smtp.host` | none (`${SMTP_HOST:}`) | `SMTP_HOST` | `host="${smtp.host}"` (`csv-to-smtp.xml:20`) | TODO. The SMTP server, bound to `spring.mail.host`. The original README uses Gmail's `smtp.gmail.com` (`README.md:25`) |
| `smtp.port` | `587` | `SMTP_PORT` (relaxed binding) | hard-coded `port="587"` on the endpoint (`csv-to-smtp.xml:20`; `README.md:26`) | default. Configurable now, with the same value; bound to `spring.mail.port` |
| `smtp.user` | none (`${SMTP_USER:}`) | `SMTP_USER` | `user="${smtp.user}"` (`csv-to-smtp.xml:20`) | TODO. The SMTP login, bound to `spring.mail.username`. Supply the plain address (`senderemailid@gmail.com`). The original README required the URL-encoded `senderemailid%40gmail.com` (`README.md:27`), and its test passed a separate `smtp.user_enc` as `smtp.user` (`SendingACsvFileThroughEmailUsingSmtpIT.java:93,96`), because Mule built an endpoint URI from it. `JavaMailSender` uses the value as is |
| `smtp.password` | none (`${SMTP_PASSWORD:}`) | `SMTP_PASSWORD` | `password="${smtp.password}"` (`csv-to-smtp.xml:20`) | TODO (secret). The SMTP password or app password, bound to `spring.mail.password` |
| `mail.from` | none (`${MAIL_FROM:}`) | `MAIL_FROM` | `from="${mail.from}"` (`csv-to-smtp.xml:20`) | TODO. The sender address, read by `client/CsvMailSender` |
| `mail.to` | none (`${MAIL_TO:}`) | `MAIL_TO` | `to="${mail.to}"` (`csv-to-smtp.xml:20`) | TODO. The recipient address. A comma-separated list is allowed, as the endpoint's `to` attribute allowed one |
| `mail.subject` | none (`${MAIL_SUBJECT:}`) | `MAIL_SUBJECT` | `subject="${mail.subject}"` (`csv-to-smtp.xml:20`) | TODO. The subject. The original README's example is `Export from Excel` (`README.md:32`); its test used `Mule flow completed!` (`SendingACsvFileThroughEmailUsingSmtpIT.java:100`) |
| `csv-email.input-dir` | `src/main/resources/input` | `CSV_EMAIL_INPUT_DIR` | the hard-coded `path` of the `file:inbound-endpoint` (`csv-to-smtp.xml:5`; `README.md:18-20`) | new key. Relative paths resolve against the working directory |
| `csv-email.polling-frequency` | `1000` | `CSV_EMAIL_POLLING_FREQUENCY` | none: Mule's default `pollingFrequency`, because the endpoint declares none | new key. Milliseconds between the end of one poll and the start of the next |

Spring settings that reproduce the original. They are fixed in `application.yml`, and the keys above are what to change:

| Setting | Value | Reproduces |
|---|---|---|
| `spring.main.web-application-type` | `none` | no HTTP listener in the original |
| `spring.application.name` | `sending-a-csv-file-through-email-using-smtp-java` | application name in log lines |
| `spring.mail.host`, `spring.mail.port`, `spring.mail.username`, `spring.mail.password` | `${smtp.host}`, `${smtp.port}`, `${smtp.user}`, `${smtp.password}` | the endpoint's `host`, `port`, `user` and `password` (`csv-to-smtp.xml:20`) |
| `spring.mail.protocol` | `smtp` | the SMTP transport |
| `spring.mail.default-encoding` | `UTF-8` | `encoding=UTF-8` of `mule-deploy.properties`; `CsvMailSender` also names UTF-8 for the subject and body |
| `spring.mail.properties[mail.smtp.auth]`, `spring.mail.properties[mail.smtp.starttls.enable]` | `true`, `true` | the `smtp:gmail-connector` preset of authenticated STARTTLS (`csv-to-smtp.xml:3`) |
| `spring.mail.properties[mail.smtp.connectiontimeout]`, `[mail.smtp.timeout]`, `[mail.smtp.writetimeout]` | `10000` each | `responseTimeout="10000"` of the endpoint: connect, read and write timeouts in milliseconds |

- Original-to-new key map. Every flow key keeps its name: `smtp.host`, `smtp.user`, `smtp.password`, `mail.from`, `mail.to` and `mail.subject`. `smtp.port`, `csv-email.input-dir` and `csv-email.polling-frequency` are new keys for values the original fixed. The original test read the never-committed `mule.test.properties` (`SendingACsvFileThroughEmailUsingSmtpIT.java:44,91-95`); its test-only keys `smtp.user_enc` and `imap.host` are dropped, because the unit tier uses GreenMail instead of a Gmail account read back over IMAPS. `mule-app.properties` was empty. No credential, host or address is hard-coded anywhere.
- `validateConnections="true"` of the Gmail connector is not reproduced. Start-up never contacts the SMTP server, so the application starts with every TODO key unset. Each polled file is then mapped and logged, fails at the send with `MailPreparationException` (blank `mail.from`), and is deleted. Supply the keys before dropping files.
- Test profile. `src/test/resources/application-test.yml` (Spring profile `test`, activated with `@ActiveProfiles("test")`) points `smtp.host` at `localhost` with `smtp.port` `3025`, which the tests that load it replace with their GreenMail server's dynamic port. `smtp.user` and `smtp.password` are the demo GreenMail user `test@localhost` and its demo password, which the tests register. `mail.from` and `mail.to` are that user's address, because the original test sent to and from its own account (`SendingACsvFileThroughEmailUsingSmtpIT.java:98-99`). `mail.subject` is the original test's `Mule flow completed!`. The profile turns STARTTLS off, because GreenMail's SMTP is plain text. It sets no `csv-email.*` key. The surefire system property `csv-email.input-dir=${project.build.directory}/test-csv-email/input` (`pom.xml`) and each test's `@DynamicPropertySource` own the input directory, so no test ever polls or deletes `src/main/resources/input`. The profile holds no real host, account or credential. The project has no `application-it.yml`, because it calls no vendor system.

## Mule-to-Java mapping

| Mule construct (`sending-a-csv-file-through-email-using-smtp`) | Java construct (`com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp`) |
|---|---|
| `file:inbound-endpoint path="src/main/resources/input"` (`csv-to-smtp.xml:5`) | `trigger/CsvFileTrigger` (`poll()` scheduled, `pollOnce()` on demand) over SC-5 `trigger/FileInboundPoller`, without a move directory |
| `dw:transform-message` "Transform CSV to Map" (`csv-to-smtp.xml:6-18`, script header line 8) | SC-4 `mapper/DwCsvReader` (the `application/csv` input) and `mapper/OrderLinesMapper.map` (the script) |
| `logger level="INFO" message="Processing objects: #[payload]"` (`csv-to-smtp.xml:19`) | INFO SLF4J statement `Processing objects: {}` in `service/CsvEmailService.process`, at the same point |
| `smtp:outbound-endpoint port="587"` (`csv-to-smtp.xml:20`) | `client/CsvMailSender.send` over `JavaMailSender` (Angus Mail) |
| `smtp:gmail-connector` (`csv-to-smtp.xml:3`) | `spring.mail.*` in `application.yml` (Boot's mail auto-configuration) |
| the flow's processor order | `service/CsvEmailService.process`: read, map, log, mail |
| Mule's default exception strategy | no catch in the flow. SC-5 logs the failure at ERROR and consumes the file |
| `mule-deploy.properties` (loads `csv-to-smtp.xml`, `encoding=UTF-8`) | `SendingACsvFileThroughEmailUsingSmtpApplication` (`@SpringBootApplication`, `@EnableScheduling`); `spring.mail.default-encoding: UTF-8` |
| `mule-app.properties` (empty) and the `${...}` placeholders of `csv-to-smtp.xml` | `src/main/resources/application.yml` |
| `SendingACsvFileThroughEmailUsingSmtpIT` (`FunctionalTestCase`, `MuleClient.dispatch`, `mule.test.properties`, Gmail over IMAPS) | `SendingACsvFileThroughEmailUsingSmtpTest` (`@SpringBootTest(webEnvironment = NONE)`, profile `test`, GreenMail SMTP) |

## Behaviours stubbed, externalized or changed

- Externalized: the SMTP host, account and password, and the sender, recipients and subject, which the original README told users to type into Studio (`README.md:23-32`). They are the TODO keys above. The port, input directory and polling frequency are configurable, with the original's values as defaults.
- Not reproduced: the Gmail connector's `validateConnections` start-up check (Configuration keys) and the file endpoint's `responseTimeout`, which has no effect on a one-way file source.
- Changed: the SMTP login is the plain address, not the `%40`-encoded form the Mule endpoint URI needed. The ERROR line for a failed file names the exception's class, throw site and cause classes, where Mule's default strategy logged its full exception report.
- Logging: the original `logger` is an INFO SLF4J statement at the same point, after the mapping and before the send. Its message is the list's `toString()`, the same text as the mail body.
- Replaced in tests (AAP 0.8.3):
  - the Gmail account the original sent through over SMTP and read back over IMAPS → GreenMail 2.1.14, an in-process SMTP server whose received messages are read directly;
  - the never-committed `mule.test.properties` → `application-test.yml`;
  - `FunctionalTestCase` and `MuleClient.dispatch` to the file endpoint (`SendingACsvFileThroughEmailUsingSmtpIT.java:112-119`) → `@SpringBootTest`, a file moved atomically into the test's input directory, and `CsvFileTrigger.pollOnce()`;
  - the fixed `Thread.sleep(12000)` (line 121) → a wait of at most 12 s for the delivery.
- `email.txt` moved from `src/main/resources` to `src/test/resources`, because only tests read it. The original test read it from `./src/main/resources/email.txt` (`SendingACsvFileThroughEmailUsingSmtpIT.java:85`). The file is byte-identical (133 bytes).
- Nothing is stubbed at runtime. Studio metadata (`mule-project.xml`, `catalog/`), `src/test/resources/log4j2-test.xml` and the Studio sample data (`src/test/resources/sample_data/list_csv.csv`) are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/sending-a-csv-file-through-email-using-smtp-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; no SMTP server, credentials or network needed
mvn spring-boot:run     # file poller on src/main/resources/input; no HTTP listener
java -jar target/sending-a-csv-file-through-email-using-smtp-java-1.0.0-SNAPSHOT.jar
```

Running the example, as the original README's steps do (`README.md:18-38`):

1. Supply the SMTP account and the mail fields through the environment, or through `--key=value` arguments or a local `application.yml` override, and start the application:

   ```sh
   SMTP_HOST='<smtp host>' SMTP_USER='<sender address>' SMTP_PASSWORD='<password>' \
   MAIL_FROM='<sender address>' MAIL_TO='<receiver address>' MAIL_SUBJECT='Export from Excel' \
   mvn spring-boot:run
   ```

2. Create the input directory `src/main/resources/input` under the project directory (the original README's step 2). The first poll creates it anyway.
3. Copy the sample file into it: `cp src/main/resources/input.csv src/main/resources/input/`. The poller reads a file as soon as it is listed, so write a larger file elsewhere first and move it in.
4. The log shows `Processing objects: [...]`, the file disappears from `input/`, and the receiver's mailbox gets one mail whose body is the text shown under Processing, in the original order.

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `SendingACsvFileThroughEmailUsingSmtpTest` | the ported `SendingACsvFileThroughEmailUsingSmtpIT` (`sending-a-csv-file-through-email-using-smtp/src/test/java/org/mule/examples/SendingACsvFileThroughEmailUsingSmtpIT.java:112-129`). Profile `test` runs against GreenMail SMTP on a dynamic port, standing in for Gmail. `input.csv` is moved into the test's input directory and `pollOnce()` is called. Within 12 s exactly one mail arrives, and its body, trimmed, equals `email.txt`. The test also asserts the subject `Mule flow completed!`, the `text/plain` content type and the deletion of the polled file |
| `trigger/CsvFileTriggerTest` | the real reader, mapper and service over a `@TempDir`, with a mocked mail sender. `input.csv` gives one send whose payload renders exactly as `email.txt`, and the file is deleted. A non-numeric `units`: the poll does not throw, returns 1, sends nothing, deletes the file. An SMTP failure: the file is deleted, the next poll finds nothing, one send attempt in all. An empty directory returns 0. `poll()` runs the same processing and is scheduled with `${csv-email.polling-frequency:1000}` |
| `trigger/FileInboundPollerTest` | SC-5, copied with `FileInboundPoller`. Move versus delete after success; consumption after a handler failure, with and without a move directory; the interrupt status kept; re-entrant and concurrent polls never hand over a file twice; an unopenable file left in place and retried; missing input and move directories created; subdirectories ignored; a move replacing a same-named file; handled-file counts |
| `service/CsvEmailServiceTest` | mocked reader, mapper and sender. Read → map → log → send, in that order; the INFO line `Processing objects: <list>`; a reader, mapper or sender failure propagating unchanged; nothing sent after a mapping failure and no retry after a failed send; an empty CSV still sending an empty list |
| `mapper/OrderLinesMapperTest` | the key order `name`, `orderId`, `pricePerUnit`, `units`, `totalPrice`; an `ArrayList` of `LinkedHashMap`s in input order; `name` and `orderId` as unchanged strings; `BigDecimal`s with integral scale 0 and no exponent notation, zero and negative values included; a non-numeric value → `IllegalArgumentException` naming the field; a missing numeric column failing on the product; an empty input → an empty list; null arguments rejected; `input.csv` mapped to exactly the `email.txt` text |
| `mapper/DwCsvReaderTest` | SC-4, copied with `DwCsvReader`. LF, CRLF and lone-CR records; a last record with or without a line end; quoted separators and line ends, doubled quotes and backslash escapes; empty cells and empty lines; empty and header-only input; untrimmed and numeric-looking values kept as written; header order; short and long rows; the charset; null arguments |
| `client/CsvMailSenderTest` | against GreenMail on a dynamic port, without a Spring context. One single-part `text/plain; charset=UTF-8` mail with the configured sender, recipient and subject and no CC or BCC; the `email.txt` body; `String.valueOf` of a non-string payload, `null` included; a comma-separated `mail.to` reaching each recipient; an unreachable server and a dropped connection → `MailException` after exactly one attempt; a malformed or blank `mail.from` → `MailPreparationException`, a blank `mail.to` → `MailSendException`, nothing delivered in either case |
| `FixtureParityTest` | ST-2 fixture replay (Fixture parity below); skipped while no fixture is captured |

`src/test/resources/application-test.yml` is profile `test` (Configuration keys). `src/test/resources/email.txt` is the original's expected mail body, copied unchanged.

## Shared components

Copied with their unit tests, with only the `package` line changed, and registered in the shared-component register of the root `DECISIONS.md` (AAP 0.4.4):

| ID | Files in this project | Copied from |
|---|---|---|
| SC-4 | `mapper/DwCsvReader`, test `mapper/DwCsvReaderTest` | `import-contacts-into-salesforce-java` |
| SC-5 | `trigger/FileInboundPoller`, test `trigger/FileInboundPollerTest` | `processing-orders-with-dataweave-and-APIkit-java` |
| ST-2 | `FixtureParityTest`, and the format of `src/test/resources/fixtures/SCENARIOS.md` (headings, table header and prose sections; the row and project notes are this project's own) | `hello-world-java` |

ST-1 `support/HttpTestClient` and SC-1 `GlobalExceptionHandler` are not carried, because the project has no HTTP listener.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/sending_a_csv_file_through_email_using_smtp/FixtureParityTest.java` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed. Its fixture format, replay drivers and comparison rules are documented once in the [`hello-world-java` README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenario and holds the fixture contract, the capture procedure and the replay notes specific to this project.

- The replay uses the `file` driver. It writes the captured input file into `csv-email.input-dir` and calls `CsvFileTrigger.pollOnce()`; scheduling is suppressed, so the scheduled poll never takes the file first.
- It runs without profile `test`. The fixture's `state.properties` therefore supplies the replay's input directory, `smtp.host` `localhost` with `smtp.port` at the replay's GreenMail port, the demo GreenMail user, the `mail.*` values of `application-test.yml`, and STARTTLS off. `state.mail` registers that user.
- `upstream` is `[]`: the original makes no outbound HTTP or SOAP call. Each mail its SMTP server received is captured into `response.mails` as raw MIME.
- The scenario is `deterministic`, so these are compared byte for byte, with no normalization:
  - the final file set of the input directory;
  - each mail's `From`, `To`, `Cc` and `Subject`;
  - each leaf part's content type, without any `boundary` parameter, and its file name;
  - each part's decoded content.

  `Date`, `Message-ID` and `Received` are neither captured nor compared.
- Fixtures are never hand-written, and never edited to match the Java output. A mismatch is fixed in the Java code: `client/CsvMailSender` for the mail's sender, recipient, subject, content type and body encoding, `mapper/OrderLinesMapper` or SC-4 `mapper/DwCsvReader` for the rendered order lines, and SC-5 `trigger/FileInboundPoller` for the file disposition.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenario, exactly as `SCENARIOS.md` names it:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `file-input-csv-emailed` | `file` | `deterministic` | file `input.csv` holding the bytes of `src/main/resources/input.csv` (57 bytes, LF line ends, no trailing newline), placed in `csv-email.input-dir`; the replay invokes `trigger.CsvFileTrigger.pollOnce()` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`: the flow has no `choice`, filter or exception strategy, so it has one path, and its mail is a function of the input file alone. The failure paths are platform handling, not branches of the flow, and are covered by `CsvFileTriggerTest`, `CsvEmailServiceTest` and `FileInboundPollerTest`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| sending-a-csv-file-through-email-using-smtp-java | sending-a-csv-file-through-email-using-smtp | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 83 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 83 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 107 of 107 lines. That is `service/CsvEmailService` 11 of 11, `mapper/OrderLinesMapper` 28 of 28, and `mapper/DwCsvReader` 68 of 68 (17, plus 51 in its `RecordParser`).
- Total project LINE coverage: 81.9% (236 of 288 lines). The 52 uncovered lines lie outside the gate's service and mapper scope: 49 in SC-5 `trigger/FileInboundPoller`, 1 in its `InboundFile.toString`, and 2 in the application's `main` method.
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=1`, `failed=0`, `scenario.file-input-csv-emailed=UNVERIFIED-uncaptured`.

Tier 2A is blocked: the Mule EE licence and the EE Maven repository credentials are unconfirmed, as the root `DECISIONS.md` records (`Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`). Tier 2B is `N/A`, because the project calls no vendor system. Tier 1 evidence is not parity evidence.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `SendingACsvFileThroughEmailUsingSmtpTest` carries the original committed assertion with its comparison semantics: the received mail body, trimmed, equals `email.txt`.
- `trigger/CsvFileTriggerTest`, `service/CsvEmailServiceTest`, `mapper/OrderLinesMapperTest` and `client/CsvMailSenderTest` cover the flow's path and every failure path, through the real mapping and through GreenMail.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the Mule file and SMTP transports. It does not show that its bytes equal what the original runtime mailed.
- Behaviours no original assertion covers, covered only by the new assertions:
  - the deletion of the polled file;
  - the non-numeric value, missing-column and SMTP-failure paths;
  - the mail's subject, `From` and `To` headers and its `text/plain` content type;
  - the INFO line `Processing objects: <list>`;
  - the polling frequency.
- Tier 2A verification points, fixed by the capture; a difference is fixed in the Java code:
  - the exact MIME `Content-Type` and charset the Mule SMTP transport sent, whether its body ended with a line break (the original compared it trimmed), and how it rendered `From` and `To`;
  - the rendering of a non-integral number with trailing zeros: `4.150` keeps its written scale here, and DataWeave may have written `4.15`;
  - the behaviour for a missing column, which fails the file here;
  - SC-4's plan defaults for short and long rows, backslash escapes and unclosed quotes.
- Unverified scenario, until captured: `file-input-csv-emailed`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenario is captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `sending-a-csv-file-through-email-using-smtp/`.
