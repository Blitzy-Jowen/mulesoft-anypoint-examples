# Importing an Email Attachment using the IMAP Connector — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion of the Mule EE 3.8 example "Importing an attached csv file using the IMAP connector" ([`importing-an-email-attachment-using-the-IMAP-connector`](../../importing-an-email-attachment-using-the-IMAP-connector/), category General). The original folder stays unchanged at the repository root and is the reference and the Tier 2A capture source.

- Project `importing-an-email-attachment-using-the-IMAP-connector-java`; package `com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector`; main class `ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication` (`@SpringBootApplication`, `@EnableScheduling`).
- No web server (`spring.main.web-application-type: none`): the original has no HTTP listener. The only entry point is the mail poller `trigger/ImapMailPoller`.
- Not a connector project (AAP 0.2.1): it has no live-sandbox tier, no failsafe plugin, no `*IT` class and no `application-it.yml`.

## Original example

A CSV file of sample sales data arrives as an email attachment in an IMAP inbox. The application reads the mail over IMAPS, converts the CSV to XML, logs the XML and saves it as `orders.xml` in the output folder (original `README.md:12`, `:50`).

The single flow `imap-to-csvFlow1` (`importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:9-33`), the only flow `config.resources=imap-to-xml.xml` loads, runs these processors:

| Line | Mule processor | What it does |
|---|---|---|
| 5 | `imaps:connector checkFrequency="100" validateConnections="true"` | polls the mailbox every 100 ms |
| 10 | `imaps:inbound-endpoint host="${imap.host}" port="993" user="${imap.user}" password="${imap.password}" responseTimeout="10000"` | IMAP over SSL; each new message becomes one flow event |
| 11-13 | `expression-transformer` with `attachments-list` `*` | payload = the message's attachments, as a list |
| 15 | `set-payload value="#[payload[0].getContent()]" mimeType="application/csv"` | payload = the content of the first attachment, read as CSV |
| 16-30 | `dw:transform-message`, the `%dw 1.0` script at line 17 | `orders: { (payload map order: { orderId, name, units, pricePerUnit }) }` as XML |
| 31 | `logger level="INFO" message="Received: #[message.payloadAs(java.lang.String)]"` | logs the XML |
| 32 | `file:outbound-endpoint path="src/test/resources/output" outputPattern="orders.xml"` | writes the XML to `orders.xml` |

The flow declares no `choice`, filter, transaction or exception strategy, and calls no other system. The original's `mule-app.properties` holds no key.

## Endpoints and triggers

There is no HTTP endpoint. The only trigger is an IMAPS mail poller:

| Trigger | Component | Mailbox | Polling | Disposition | Source |
|---|---|---|---|---|---|
| IMAPS inbound endpoint | `trigger/ImapMailPoller`, `@Scheduled(fixedDelayString = "${imap.check-frequency:100}", initialDelayString = "${imap.initial-delay:0}")` on `poll()` | Jakarta Mail (Angus Mail) `imaps` store on `imap.host`:`imap.port` (default `993`, implicit TLS), login `imap.user` / `imap.password`, folder `imap.folder` (default `INBOX`) opened read-write | every `imap.check-frequency` (default `100` ms), as a fixed delay between the end of one poll and the start of the next, so polls never overlap; the first poll after `imap.initial-delay` (default `0` ms) | every message handed to the flow is flagged `\Deleted` and expunged when the folder closes (Mule's email default `deleteReadMessages=true`) | `imap-to-xml.xml:5`, `:10` |

`ImapMailPoller.pollOnce()` runs one poll on demand and returns the number of messages handed to the flow, whether or not their import succeeded. Tests and the fixture replay call it. It is `synchronized`, so an on-demand poll and a scheduled poll never read the mailbox at the same time.

Each poll, as `ImapMailPoller` implements it after Mule 3.8's `RetrieveMessageReceiver`:

1. A null or blank `imap.host`, `imap.user` or `imap.password` skips the poll without opening a session, so no connection is attempted. The first skipped poll logs one WARN naming every missing key with its environment variable, for example `imap.host (IMAP_HOST), imap.user (IMAP_USER), imap.password (IMAP_PASSWORD) are not configured; IMAPS polling is skipped` (`imap.host (IMAP_HOST) is not configured; IMAPS polling is skipped` when only the host is missing), and every skipped poll logs a DEBUG line. Neither carries a configured value. The original could not deploy with an unresolved `${imap.host}`.
2. A new Jakarta Mail `Session` is opened for the poll, never the JVM-wide default one. Its connection has a connect, a read and a write deadline (`imap.connect-timeout`, `imap.read-timeout`, `imap.write-timeout`, 10000 ms each), so a server that stops answering during the TLS handshake, the greeting, `LOGIN`, `SELECT` or a `FETCH` ends that poll with a timeout instead of holding every later poll.
3. The store connects and opens the folder read-write. A mailbox that cannot be reached, authenticated or trusted is logged at ERROR, `Failed to connect to IMAP folder <folder> on <host>:<port> (<exception types>)`; nothing is read, and the next poll retries.
4. Messages are processed one by one, in folder order. A message already flagged `\Seen` or `\Deleted` is skipped. Every other message is copied out of the folder and its attachment map is built; then the original is flagged `\Deleted`, and only then does the flow body run.
5. The folder is closed with expunge, which removes the flagged messages, and the store is closed. A failed close is logged at WARN.

The attachment map is built as Mule 3.8's `MailMuleMessageFactory` and `MailUtils` built it:

- Every leaf part of a multipart mail is an attachment, body text included. Only `multipart/mixed` parts are descended into. A mail that is not multipart has no attachments.
- Each part is named by its description, else its decoded file name, else its disposition, else a running index local to its multipart level. A repeated name becomes the first free `<n>_<name>`.
- The parts are collected in a `TreeMap` and then added to a `HashMap`, Mule's inbound-attachment map. `payload[0]` is the first entry in that `HashMap`'s iteration order, which neither MIME order nor name order predicts. Send the CSV as the mail's only part, as the original test does, so that it is the part converted.

Diagnostics carry a fixed text, the folder, host, port or message number, and the class names along the failure's cause chain. They never carry an exception message, a stack trace, the user, the password or mail content. The only log line with mail content is the flow's own `Received:` line.

### Flow steps

`service/AttachmentImportService.importAttachments(Map<String, DataHandler>)` runs the flow body for each message handed over, in the original order:

1. `expression-transformer` and `set-payload` (l.11-15): the content of the first attachment in the map's iteration order, and of no other, is read through `DataHandler.getContent()`. It is text where a content handler decodes the part (such as `text/plain`), and otherwise a stream or bytes read as UTF-8 (such as the original test's `application/octet-stream`). It is parsed by SC-4 `mapper/DwCsvReader`, the DataWeave 1.0 `application/csv` reader defaults: header row, `,` separator, `"` quoting, backslash escape, and every value kept as its text, so `2.0` stays `2.0`.
2. `mapper/OrdersXmlMapper.toXml` reimplements the `%dw 1.0` script (l.17) and is its single owner (AAP 0.5.8). The root is `orders`, with one `order` per record holding exactly `orderId`, `name`, `units` and `pricePerUnit`, in that order. Values are never trimmed or coerced. Extra columns are dropped, a missing column is written as an empty element (`<units/>`), and no records give `<orders/>`. The document is rendered by SC-4 `mapper/DwXmlWriter`: the declaration `<?xml version='1.0' encoding='UTF-8'?>`, one tab per indentation level, LF line ends and nothing after the root end tag.
3. The service logs INFO `Received: <xml>` (l.31).
4. SC-6 `service/FileOutboundWriter` writes the XML as UTF-8 to `<orders.output-dir>/<orders.output-file-name>`, default `src/test/resources/output/orders.xml` (l.32). It creates the directory when missing and replaces an existing file as a whole, so every mail overwrites the same `orders.xml`.

Output for the bundled `src/main/resources/input.csv` (57 bytes: the header `orderId,name,units,pricePerUnit` and the records `1,aaa,2.0,10` and `2,bbb,4.15,5`), as `mapper/OrdersXmlMapperTest` and the ported test expect it: 289 bytes, SHA-256 `c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee`.

```xml
<?xml version='1.0' encoding='UTF-8'?>
<orders>
	<order>
		<orderId>1</orderId>
		<name>aaa</name>
		<units>2.0</units>
		<pricePerUnit>10</pricePerUnit>
	</order>
	<order>
		<orderId>2</orderId>
		<name>bbb</name>
		<units>4.15</units>
		<pricePerUnit>5</pricePerUnit>
	</order>
</orders>
```

The sample log in the original README (`README.md:31-48`) shows a hand-edited rendering: `recieved:`, a double-quoted declaration and a repeated `<orders>` root. The bytes above come from the SC-4 writer, whose layout is taken from DataWeave-produced files in the repository (AAP 0.3.3, 0.4.4). They are a Tier 2A verification point.

### Failure behaviour

The flow declares no exception strategy, so a failure in the flow body gets Mule's default handling: it is logged at ERROR and the message is lost, because it was flagged `\Deleted` before the flow body ran. The first four rows are such failures; the poller logs each as ERROR `Error processing IMAP message <n> (<exception types>)`, and polling continues with the next message. The last three happen in the poller, before the flow body runs.

| Case | Behaviour |
|---|---|
| mail without attachment (not multipart) | `AttachmentImportService` throws `IllegalStateException` `The received message has no attachments`; ERROR `Error processing IMAP message <n> (java.lang.IllegalStateException)`; nothing is logged at INFO or written; the mail is expunged |
| first attachment's content is neither text, a stream nor bytes | `IllegalStateException` `Unsupported attachment content: <class>`; ERROR log; nothing written; mail expunged |
| CSV value with a character XML 1.0 forbids | `DwXmlWriter` refuses the document with an `IllegalStateException`, for example `Invalid XML 1.0 character U+0001 in the text of element <name>`; ERROR log; no INFO line, no file; mail expunged |
| output file cannot be written | `IOException` `Streaming failed. Could not get output stream` (Mule's message), the cause attached; the INFO line is already logged; ERROR log; mail expunged |
| mail whose copy or attachment map cannot be built (unparseable MIME) | ERROR `Error processing IMAP message <n> (<exception types>)`; the mail is not flagged and does not reach the flow; the server's `\Seen` flag, set when its body was fetched, keeps the next poll from taking it again |
| message already flagged `\Seen` or `\Deleted` | skipped; not handed to the flow and not counted by `pollOnce()` |
| mailbox unreachable, login rejected, server certificate untrusted, server stalls past a deadline | ERROR `Failed to connect to IMAP folder <folder> on <host>:<port> (<exception types>)` (or `Failed to read the messages of IMAP folder …` when the listing fails); `pollOnce()` returns 0; the mailbox is left untouched; the next poll retries |

- With `imap.host`, `imap.user` or `imap.password` unset or blank, the application still starts and never connects: every poll is skipped, the first with the WARN of step 1 under Endpoints and triggers, and `pollOnce()` returns 0.
- A deadline that is not a positive whole number of milliseconds (`imap.connect-timeout`, `imap.read-timeout`, `imap.write-timeout`, or an `imap.properties` entry replacing one) stops start-up with an `IllegalArgumentException` naming the key, because 0 or a negative value would mean no deadline.
- Tier 2A verification points: the handling of a mail without attachment (ERROR log, no file, message expunged; scenario `imap-mail-without-attachment`) and of a mailbox connection failure (logged, retried on the next poll). The original's start-up behaviour with an unreachable mailbox (`validateConnections="true"`) is not reproduced: the application always starts and reports the problem per poll. Mule's default strategy logged its own error report; that text is not reproduced. When a captured fixture shows other behaviour, the fixture governs and the code is changed to match.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. The original's flow reads the three placeholders `imap.host`, `imap.user` and `imap.password` (`imap-to-xml.xml:10`) and hard-codes every other value.

Status values:

- `default`: a non-secret default, overridable with `--key=value`; where the original fixed the value, the default is the original's.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). A secret is marked `(secret)`.
- `fixed`: a framework setting that reproduces the original runtime, not a key of the original.

| Key | Type | Default | Environment variable | Original name | Status | Description |
|---|---|---|---|---|---|---|
| `imap.host` | `String` | none | `IMAP_HOST` | `imap.host` (unchanged) | TODO | IMAPS server host; the original README used `imap.googlemail.com`. Blank skips every poll (WARN once) |
| `imap.user` | `String` | none | `IMAP_USER` | `imap.user` (unchanged) | TODO | mailbox login in plain form, for example `receiver@gmail.com`. Blank skips every poll (WARN once) |
| `imap.password` | `String` | none | `IMAP_PASSWORD` | `imap.password` (unchanged) | TODO (secret) | mailbox password; never logged. Blank skips every poll (WARN once) |
| `imap.port` | `int` | `993` | — | none: `port="993"` (l.10); the original README listed `imap.port=993` | default | IMAPS port, implicit TLS |
| `imap.folder` | `String` | `INBOX` | — | none: Mule's default `mailboxFolder` | default | folder opened read-write |
| `imap.check-frequency` | `String` | `100` | — | none: `checkFrequency="100"` (l.5) | default | fixed delay between the end of one poll and the start of the next, read as `@Scheduled(fixedDelayString)` reads it: milliseconds as a positive whole number, or a positive ISO-8601 duration (for example `PT0.1S`; text whose first or second character is `P` or `p` is read as a duration). The value is not trimmed. A zero, negative or unreadable value stops start-up with an error naming the key and the value |
| `imap.initial-delay` | `String` | `0` | — | none | default | delay before the first scheduled poll, read as `@Scheduled(initialDelayString)` reads it: milliseconds as a whole number, or an ISO-8601 duration (for example `PT1S`). A negative value starts polling at once; unreadable text stops start-up with Spring's `Invalid initialDelayString value` error. The `test` profile raises it to hold the schedule back |
| `imap.connect-timeout` | `String`, checked into an `int` (ms) | `10000` | — | none: the endpoint's `responseTimeout="10000"` (l.10) | default | TCP connect deadline of each poll's connection (`mail.imaps.connectiontimeout`). Written as decimal digits only (no sign, blank or other notation), from 1 to 2147483647; any other text stops start-up with an error naming the key |
| `imap.read-timeout` | `String`, checked into an `int` (ms) | `10000` | — | as above | default | deadline of each socket read: TLS handshake, greeting and every command reply (`mail.imaps.timeout`). Same form and check as `imap.connect-timeout` |
| `imap.write-timeout` | `String`, checked into an `int` (ms) | `10000` | — | as above | default | deadline of each socket write (`mail.imaps.writetimeout`). Same form and check as `imap.connect-timeout` |
| `imap.properties` | `Map<String, String>` | `{}` | — | none | default | extra Jakarta Mail session properties, copied into each poll's `Session`. A key containing dots is written in bracket notation, for example `"[mail.imaps.ssl.trust]": "*"`. An entry for `mail.imaps.connectiontimeout`, `mail.imaps.timeout` or `mail.imaps.writetimeout` replaces the matching deadline and passes the same check as `imap.connect-timeout`. Leave it empty in production, so TLS is verified against the JVM trust store |
| `orders.output-dir` | `String` (a directory path) | `src/test/resources/output` | — | none: `path` (l.32) | default | output directory, relative to the working directory as the original's `path` was |
| `orders.output-file-name` | `String` | `orders.xml` | — | none: `outputPattern` (l.32) | default | output file name |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | — | none | fixed | no HTTP listener, as in the original |
| `logging.level.org.eclipse.angus.mail` | `LogLevel` | `INFO` | — | none | fixed | Angus Mail's `.imap` `DEBUG` writes the mailbox user, its `.imap.protocol` `TRACE` the whole IMAP dialogue: the envelope, every header (`Return-Path`, `From`, `To`, `Subject`) and the attachment's content. Angus Mail logs through `java.util.logging`, which Spring Boot bridges and holds at the same level |

The `logging.level` key keeps the mailbox account and the polled mail out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`: a logger's own level outranks them. The original logs only the orders XML built from the attachment (`Received: <xml>`), which stays. `config/SensitiveLogLevelsTest` asserts this.

Every key can also be overridden through its relaxed environment name (for example `IMAP_PORT`) or a `--key=value` argument.

Original-to-new key map:

- Unchanged: `imap.host`, `imap.user` and `imap.password` keep the names the flow reads (`imap-to-xml.xml:10`). The original README called the host `imap.server` (`README.md:20`); the flow never read that name, and it maps to `imap.host`.
- `imap.user` is now the plain mailbox address (`receiver@gmail.com`). The Mule endpoint built a URI from it, so the original needed the URL-encoded form `receiveremailaddress%40gmail.com` (`README.md:22`), and its test passed the URL-encoded `*.user_enc` property as `imap.user` (`ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:74`). That property is dropped (AAP 0.6.6).
- Hard-coded values that became keys: `port="993"` → `imap.port`; `checkFrequency="100"` → `imap.check-frequency`; `responseTimeout="10000"` → the default of `imap.connect-timeout`, `imap.read-timeout` and `imap.write-timeout`; `path` and `outputPattern` → `orders.output-dir` and `orders.output-file-name`.
- Added without an original counterpart: `imap.folder` (Mule's default folder), `imap.initial-delay` and `imap.properties`.
- Test-only: the original test read `smtp.host`, `smtp.port`, `smtp.user`, `smtp.password` and `imap.host`, `imap.user`, `imap.password` from its uncommitted `src/test/resources/mule.test.properties` (`IT.java:67-74`) to mail through a Gmail account. GreenMail replaces Gmail, so these become test values in `application-test.yml` (below); `smtp.port` and `imap.port` are the ports GreenMail allocates at run time.

Test configuration: `src/test/resources/application-test.yml` (profile `test`, activated for every test by surefire's `spring.profiles.active=test`) holds GreenMail values that are not credentials: `imap.host` `127.0.0.1`, `imap.user` `receiver@localhost`, a test-only password, `imap.initial-delay` `86400000` (the scheduled poll stays idle unless a test releases it), `imap.properties` `[mail.imaps.ssl.trust]` `*` and `[mail.imaps.ssl.checkserveridentity]` `false` for GreenMail's self-signed certificate, the sender mailbox `smtp.host`, `smtp.user`, `smtp.password`, and `orders.output-dir` `target/test-output`. It sets no port: every test registers the ports its GreenMail server bound through `@DynamicPropertySource`, and writes into a JUnit `@TempDir` rather than `src/test/resources/output`.

No credential appears in any committed file. Supply the three TODO keys through their environment variables, `--key=value` arguments or an external configuration file (`--spring.config.additional-location=<file>`); never commit a real value. A mail provider may require an app password for password sign-in over IMAP, as Gmail does.

## Mule-to-Java mapping

| Mule construct (`importing-an-email-attachment-using-the-IMAP-connector`) | Java construct (`com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector`) |
|---|---|
| `imaps:connector checkFrequency="100"` (l.5) + `imaps:inbound-endpoint` (l.10) | `trigger/ImapMailPoller` (`@Scheduled` `poll()`, single-run `pollOnce()`) |
| `expression-transformer` `attachments-list` (l.11-13) | `ImapMailPoller`'s attachment map, in Mule's attachment-map order |
| `set-payload #[payload[0].getContent()]` as `application/csv` (l.15) | `service/AttachmentImportService.importAttachments`: the first attachment's content, parsed by SC-4 `mapper/DwCsvReader` |
| `dw:transform-message`, `%dw 1.0` script (l.17) | `mapper/OrdersXmlMapper`, rendered by SC-4 `mapper/DwXmlWriter` |
| `logger level="INFO"` `Received: …` (l.31) | SLF4J INFO `Received: <xml>` in `AttachmentImportService` |
| `file:outbound-endpoint path outputPattern` (l.32) | SC-6 `service/FileOutboundWriter` |
| default exception strategy (none declared) | ERROR lines in `ImapMailPoller`; the message stays consumed |
| `mule-deploy.properties` (`config.resources=imap-to-xml.xml`) | `ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication` |
| `mule-app.properties` and the `${…}` placeholders | `src/main/resources/application.yml` |
| `ImportingAnEmailAttachmentUsingTheIMAPConnectorIT` (`FunctionalTestCase`, Gmail SMTP and IMAPS) | `ImportingAnEmailAttachmentUsingTheIMAPConnectorTest` (`@SpringBootTest`, GreenMail SMTP and IMAPS) |

Shared components, each copied from its reference project with only the `package` line changed, its unit test copied with it (register: [`DECISIONS.md` §4](../../DECISIONS.md#4-shared-component-register)):

| ID | Classes | Copied from | Test copied |
|---|---|---|---|
| SC-4 | `mapper/DwCsvReader` | `import-contacts-into-salesforce-java` | `mapper/DwCsvReaderTest` |
| SC-4 | `mapper/DwXmlWriter` | `web-service-consumer-java` | `mapper/DwXmlWriterTest` |
| SC-6 | `service/FileOutboundWriter` | `processing-orders-with-dataweave-and-APIkit-java` | `service/FileOutboundWriterTest` |
| ST-2 | `FixtureParityTest`, the format of `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` | — (it is the test) |

The project has no HTTP listener, so it carries neither SC-1 nor ST-1.

## Behaviours stubbed, externalized or changed

- The Mule IMAPS transport is replaced by a Jakarta Mail `imaps` store from `spring-boot-starter-mail` (Angus Mail). No connector library is used.
- No DataWeave runtime is used: the script is hand-written in `mapper/OrdersXmlMapper` (AAP 0.1.1).
- The mailbox host, user and password are externalized under their original names as TODO placeholders. The port, polling frequency, socket deadlines and output location become keys with the original values as defaults.
- `imap.user` takes the plain address instead of the URL-encoded form.
- `validateConnections="true"` is not reproduced: a mailbox that cannot be reached is logged per poll at ERROR, and the application still starts (a Tier 2A verification point).
- `responseTimeout="10000"` governed Mule's request-response waits, not mail retrieval, so it has no direct counterpart. Its 10 s value is the default of the poller's three socket deadlines, which keep a stalled server from blocking the poller.
- Each message is handled on its own within a poll (see Failure behaviour), and diagnostics name exception types only.
- In the tests, GreenMail 2.1.14 (SMTP and IMAPS, in process, dynamic ports) replaces the Gmail account and its authenticated STARTTLS SMTP sender (AAP 0.8.3). The tests deliver over plain SMTP, which GreenMail accepts without login.
- Not carried (AAP 0.2.2): `catalog/**`, `mule-project.xml`, `src/main/app/mule-deploy.properties`, the empty `src/main/app/mule-app.properties`, `src/test/resources/log4j2-test.xml`, and `src/main/resources/output.xml`, which no flow, class or test reads. `src/main/resources/input.csv` and `src/test/resources/orders.xsd` are carried unchanged.


## Build, run and test

Run from `java-conversions/importing-an-email-attachment-using-the-IMAP-connector-java/`. No credential or external server is needed: GreenMail is embedded in the tests.

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # the scheduled poller, with the TODO keys supplied (below)
java -jar target/importing-an-email-attachment-using-the-IMAP-connector-java-1.0.0-SNAPSHOT.jar
```

Supply the three TODO keys when running, for example through their environment variables:

```sh
IMAP_HOST=imap.googlemail.com IMAP_USER=… IMAP_PASSWORD=… mvn spring-boot:run
```

Without them the application starts, logs the `imap.host (IMAP_HOST), imap.user (IMAP_USER), imap.password (IMAP_PASSWORD) are not configured; IMAPS polling is skipped` WARN once and does not poll.

The output directory is relative to the working directory. Both commands above run from the project directory, so they write `src/test/resources/output/orders.xml`, as the original did. To keep run-time files out of the source tree, pass `--orders.output-dir=target/run/output` (with `mvn spring-boot:run`, through `-Dspring-boot.run.arguments=--orders.output-dir=target/run/output`).

Usage, following the original README steps 3 to 6:

1. Start the application with the keys supplied.
2. Mail `src/main/resources/input.csv` as an attachment to the `imap.user` mailbox, ideally as the mail's only part (see Endpoints and triggers).
3. On the next poll the log shows `Received: <?xml version='1.0' encoding='UTF-8'?>` followed by the document, `orders.xml` appears in the output directory, and the mail is flagged deleted and expunged from the polled folder. A provider may apply its own rule to expunged mail; Gmail does, through its IMAP settings.

- `mvn verify` adds nothing beyond `package`'s JaCoCo gate: there is no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ImportingAnEmailAttachmentUsingTheIMAPConnectorTest` | The ported `ImportingAnEmailAttachmentUsingTheIMAPConnectorIT` (`importing-an-email-attachment-using-the-IMAP-connector/src/test/java/org/mule/examples/ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:94-122`). The whole application runs with its scheduled poller at the original 100 ms, against GreenMail SMTP and IMAPS on dynamic ports. The test sends the original mail through GreenMail SMTP: subject `This is the IMAP Subject Line!`, one `application/octet-stream` part with the bytes of `input.csv` and file name `report`. It waits up to 10 s, replacing the original's fixed 10 s sleep, then asserts the original assertion that the output directory holds exactly one file (`IT.java:122`). It also asserts that the file is `orders.xml` with the 289 bytes above (text and SHA-256), that each of its two `order` elements is valid against the carried `orders.xsd`, and that the INBOX is empty afterwards (flagged `\Deleted` and expunged). `orders.xsd` admits exactly one `order` per document, so each order is validated in a document of its own. |
| `trigger/ImapMailPollerTest` | Against GreenMail IMAPS, with a Mockito `AttachmentImportService`: messages already flagged `\Seen` or `\Deleted` are skipped, and a `\Seen` one stays in the mailbox; a mail without attachments is handed over as an empty `HashMap` and expunged; an import failure is logged at ERROR as `Error processing IMAP message 1 (java.lang.IllegalStateException)`, the next message is still processed and both are expunged; an unreachable port and a rejected password are logged at ERROR by exception type and return 0 without reaching the service, and after the rejected password the mail stays in the mailbox; a null, empty or blank `imap.host`, `imap.user` or `imap.password` (each alone, and all three empty) makes every poll return 0 without connecting to the local listener it points at, logs one WARN naming the missing keys with their environment variables plus a DEBUG line per poll, and logs no configured value; attachment names follow Mule 3.8's `MailUtils` (description, file name, disposition, index; `multipart/mixed` flattened; a taken name gets the first free `<n>_` prefix). The deadlines default to 10 s, follow their keys and positive `imap.properties` entries, and refuse every non-positive or non-decimal value at construction; a stalled TLS handshake, a withheld `LOGIN` reply and a withheld `FETCH` reply each end the poll at the read deadline without blocking the next. `poll()` carries the original `checkFrequency`; `pollOnce()` is a public `synchronized int`. Every diagnostic names only the operation context and exception types, never the user, password or mail content. As wired by the application under the `test` profile, the original mail's `report` attachment reaches the service byte for byte, the mail is expunged and a second poll finds nothing. |
| `service/AttachmentImportServiceTest` | Over the real SC-4 reader and writer, the real mapper and the real SC-6 writer (a mock replaces it only in the write-failure and forbidden-character cases): `orders.xml` gets the 289 bytes (SHA-256 checked) and one INFO `Received:` line; text, stream and byte content forms are read alike; only the first attachment in the map's iteration order is imported; a message without attachments (empty or `null` map) fails with `The received message has no attachments`, unsupported content with `Unsupported attachment content: <class>` and a character XML 1.0 forbids with the writer's message, each with nothing logged or written; a write failure propagates unchanged after the INFO line. |
| `mapper/OrdersXmlMapperTest` | `input.csv` maps to the exact 289-byte literal (with its SHA-256), both from literal rows and from the resource read by `DwCsvReader`; each order is valid against `orders.xsd`; children follow script order whatever the column order; values pass through verbatim and escaped; a missing or `null` column gives an empty element; no records give `<orders/>`; extra columns are ignored; row order is kept; `null` rows and a missing writer are rejected. |
| `mapper/DwCsvReaderTest` | SC-4, copied with its class: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed string values. |
| `mapper/DwXmlWriterTest` | SC-4, copied with its class: the single-quoted declaration, tab layout, LF line ends and nothing after the root end tag. |
| `service/FileOutboundWriterTest` | SC-6, copied with its class: directory creation, whole-file replacement, bytes written exactly and text as UTF-8, concurrent writers, symbolic links, permissions, and every failed write reported as `Streaming failed. Could not get output stream`. |
| `config/SensitiveLogLevelsTest` | The `logging.level` key bound from `application.yml` as Spring Boot binds it. With the root at `TRACE` and that level applied through Spring Boot's logging system, one `pollOnce()` against GreenMail imports a mail whose mailbox, password, sender, subject and attachment order are sentinels: `orders.xml` holds the order and the original `Received:` line is logged at INFO with it, while no sentinel is written through any logger outside `com.mulesoft.examples` and the stand-in server. A control with the level cleared shows exactly Angus Mail's `.imap` and `.imap.protocol` loggers writing them. |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured. |

Coverage: the JaCoCo `check` at `prepare-package` fails the build below 80% LINE coverage over `service/**` and `mapper/**`; the report is in `target/site/jacoco`.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/importing_an_email_attachment_using_the_imap_connector/FixtureParityTest.java` is ST-2, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure. The reference implementation and its fixture format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation).

- Replay: the `mail` driver starts GreenMail, registers the `receiver@localhost` mailbox, applies the fixture's properties (`imap.port` set to the GreenMail IMAPS port and `orders.output-dir` to a temporary directory; host, user, password and TLS trust come from `application-test.yml`), delivers the captured MIME into the INBOX and calls `ImapMailPoller.pollOnce()`. The scheduled poll is suppressed. The produced files, the log events and the INBOX contents after the poll are compared byte for byte.
- Capture (Tier 2A): the unmodified original runs on a licensed Mule 3.8 EE runtime against a local GreenMail IMAPS server reachable on port 993, which the flow hard-codes, in place of Gmail, and receives the mail of each scenario. The flow makes no outbound HTTP call, so each fixture's `upstream` is `[]`.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

`SCENARIOS.md` scenarios:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `imap-mail-csv-attachment` | `mail` | `deterministic` | the original test's mail with `input.csv` as its only part (`application/octet-stream`, file name `report`), delivered to the INBOX of `receiver@localhost`; one poll through `ImapMailPoller.pollOnce()` | `UNVERIFIED-uncaptured` |
| `imap-mail-without-attachment` | `mail` | `deterministic` | a `text/plain` mail without attachment, delivered to the same INBOX; one poll through `ImapMailPoller.pollOnce()` | `UNVERIFIED-uncaptured` |

No scenario is `unreachable` or `nondeterministic`: the flow reads no clock or random value, and each output depends on its mail alone.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| importing-an-email-attachment-using-the-IMAP-connector-java | importing-an-email-attachment-using-the-IMAP-connector | General | PASS | PASS | 91.9% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9); the test counts and coverage figures below were re-measured on 2026-10-28 with `mvn -B clean verify`, which runs the same surefire tests and JaCoCo check, on the tree whose SC-4 `DwXmlWriter` refuses element, prefix and attribute names that are not XML names:

- `mvn -B clean package`: `BUILD SUCCESS`; 577 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 577 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 677 of 737 lines. The `mapper` package is fully covered (309 of 309 lines); `service` has 368 of 428 (`AttachmentImportService` 25 of 25, `FileOutboundWriter` with its nested classes 343 of 403). Total project LINE coverage: 92.9% (848 of 913 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=2`, `failed=0`.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked until the Mule EE runtime licence and the EE Maven repository credentials are confirmed: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed" ([`DECISIONS.md` §9](../../DECISIONS.md#9-verification-tiers-and-tier-2a-escalation-state)). No fixture can be captured from the original, so parity rests on the fallback of AAP 0.8.4:

- `ImportingAnEmailAttachmentUsingTheIMAPConnectorTest` carries the original committed assertion: after the mail with the CSV attachment is delivered and polled, the output directory holds exactly one file.
- `ImapMailPollerTest`, `AttachmentImportServiceTest` and `OrdersXmlMapperTest` cover the trigger, the flow steps and the transform, every branch included.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the IMAP transport, the DataWeave writer and the file endpoint, not that its bytes and log lines equal what the original runtime produced.
- Effects and branches no original assertion covers, tested only by the new tests: the content of `orders.xml` (the original only counted files), the INFO `Received:` line, the expunge of read messages, the skipping of `\Seen` and `\Deleted` messages, and the failure branches (the no-attachment error path, unsupported content, unreadable MIME, an unreachable, rejecting or stalled mailbox).
- Unverified scenarios, until captured: `imap-mail-csv-attachment` and `imap-mail-without-attachment` (both `deterministic`).

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `importing-an-email-attachment-using-the-IMAP-connector/`.

