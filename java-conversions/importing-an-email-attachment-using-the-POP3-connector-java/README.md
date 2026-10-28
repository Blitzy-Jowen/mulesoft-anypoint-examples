# importing-an-email-attachment-using-the-POP3-connector-java

Java 17 / Spring Boot 3.2.12 conversion of the Mule EE 3.8 example "Importing an attached csv file using the POP3 connector" ([`importing-an-email-attachment-using-the-POP3-connector`](../../importing-an-email-attachment-using-the-POP3-connector/), category General). The original folder stays unchanged beside this project and is the reference and the Tier 2A capture source.

- Package `com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector`; main class `ImportingAnEmailAttachmentUsingThePOP3ConnectorApplication` (`@SpringBootApplication`, `@EnableScheduling`).
- No web server (`spring.main.web-application-type: none`): the original has no HTTP listener.
- Not a connector project (AAP 0.2.1): it has no live-sandbox tier, no failsafe plugin, no `*IT` class and no `application-it.yml`.

## Original example

A CSV file of sample sales data arrives as an email attachment. The application reads the mail over POP3, converts the CSV to XML with DataWeave, logs the XML and saves it as an XML file (original `README.md:12`, `:53`).

The single flow `pop-to-xmlFlow1` (`importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:5-29`), the only flow `config.resources=pop-to-xml.xml` loads, runs these processors:

| Line | Mule processor | What it does |
|---|---|---|
| 3 | `pop3s:connector checkFrequency="100" validateConnections="true"` | polls the mailbox every 100 ms |
| 6 | `pop3s:inbound-endpoint host="${pop3.host}" port="995" user="${pop3.user}" password="${pop3.password}" responseTimeout="10000"` | POP3 over SSL; each message read becomes one flow event |
| 7-9 | `expression-transformer` with `attachments-list` `*` | payload = the message's attachments, as a list |
| 10 | `set-payload value="#[payload[0].getContent()]" mimeType="application/csv"` | payload = the content of the first attachment, read as CSV |
| 11-26 | `dw:transform-message`, the `%dw 1.0` script at line 13 | `orders: { (payload map order: { orderId, name, units, pricePerUnit }) }` as XML |
| 27 | `logger level="INFO" message="Received: #[message.payloadAs(java.lang.String)]"` | logs the XML |
| 28 | `file:outbound-endpoint path="src/test/resources/output" outputPattern="orders.xml"` | writes the XML to `orders.xml` |

The flow declares no `choice`, filter, transaction or exception strategy, and calls no other system.

## Endpoints and triggers

There is no HTTP endpoint. The only trigger is a POP3S mail poller:

| Trigger | Component | Mailbox | Polling | Disposition | Source |
|---|---|---|---|---|---|
| POP3S inbound endpoint | `trigger/Pop3MailPoller`, `@Scheduled(fixedDelayString = "${pop3.check-frequency:100}", initialDelayString = "${pop3.check-frequency:100}")` on `poll()` | Jakarta Mail (Angus Mail) `pop3s` store on `pop3.host`:`pop3.port` (default `995`), login `pop3.user` / `pop3.password`, folder `pop3.folder` (`INBOX`); `pop3.response-timeout` (`10000` ms) is the connect and read timeout | every `pop3.check-frequency` ms (default `100`), as a fixed delay between the end of one poll and the start of the next, so polls never overlap | every message handed to the flow is flagged deleted and removed when the folder closes (Mule's POP3 default `deleteReadMessages=true`) | `pop-to-xml.xml:3`, `:6` |

`Pop3MailPoller.pollOnce()` runs one poll on demand and returns the number of messages consumed. Tests and the fixture replay call it. It is `synchronized`, so an on-demand poll and a scheduled poll never read the mailbox at the same time.

Each poll, as `Pop3MailPoller` implements it after Mule 3.8's `RetrieveMessageReceiver`:

1. A null or blank `pop3.host`, `pop3.user` or `pop3.password` skips the poll without opening a session, so no connection is attempted. The first skipped poll logs one WARN naming every missing key with its environment variable, `pop3.host (POP3_HOST) is not configured; POP3S polling is skipped` when only the host is missing and, for example, `pop3.host (POP3_HOST), pop3.user (POP3_USER), pop3.password (POP3_PASSWORD) are not configured; POP3S polling is skipped` when all three are; every skipped poll logs a DEBUG line. Neither carries a configured value. The original could not deploy with an unresolved `${pop3.host}`.
2. The store connects and opens the folder read-write. A mailbox that cannot be reached, authenticated or trusted is logged at ERROR, `Cannot poll POP3S mailbox <host>:<port>: <exception types>`; nothing is read, and the next poll retries.
3. Messages are processed one by one, in mailbox order. Each message is copied out of the folder and its attachment list is built. Then the original is flagged deleted, and only then does the flow body run.
4. The folder is closed with expunge, so on POP3 the flagged messages are removed at `QUIT`.

The attachment list is built as Mule 3.8 built it:

- Every leaf part of a multipart mail is an attachment, body text included. Only `multipart/mixed` parts are descended into. A mail that is not multipart has no attachments.
- Each part is named by its description, else its decoded file name, else its disposition, else a running index. A repeated name becomes the first free `<n>_<name>`.
- The parts are collected in a `TreeMap` and then added to a `HashMap`, Mule's inbound-attachment map. `payload[0]` is the first entry in that `HashMap`'s iteration order, which neither MIME order nor name order predicts. A mail with a text body has more than one part, and the first in that order need not be the CSV. Send the CSV as the mail's only part, as the original test does, so that it is the part converted.
- No part content is read while the list is built, so a later part that cannot be decoded never fails the mail.

### Flow steps

`service/AttachmentImportService.process(List<DataHandler>)` runs the flow body for each consumed message, in the original order:

1. `set-payload` (l.10): the content of the first attachment, and of no other, is read through `DataHandler.getContent()`. It is text where a content handler decodes the part (such as `text/plain`, with its declared charset), and otherwise a stream or bytes read as UTF-8 (such as the original test's `application/octet-stream`). It is parsed by SC-4 `mapper/DwCsvReader`, the DataWeave 1.0 `application/csv` reader defaults: header row, `,` separator, `"` quoting, backslash escape, and every value kept as its text.
2. `mapper/OrdersXmlMapper.toXml` reimplements the `%dw 1.0` script (l.13). The root is `orders`, with one `order` per record holding exactly `orderId`, `name`, `units` and `pricePerUnit`, in that order. Values are never trimmed or coerced. Extra columns are dropped, and a missing column is written as an empty element. The document is rendered by SC-4 `mapper/DwXmlWriter`.
3. The service logs INFO `Received: <xml>` (l.27).
4. SC-6 `service/FileOutboundWriter` writes the XML as UTF-8 to `<orders.output-dir>/<orders.output-file-name>`, default `src/test/resources/output/orders.xml` (l.28). It creates the directory when missing and replaces an existing file as a whole. Every mail therefore overwrites the same `orders.xml`.

Output for the bundled `src/main/resources/input.csv` (57 bytes, `orderId,name,units,pricePerUnit` and two records): 289 bytes, with the declaration `<?xml version='1.0' encoding='UTF-8'?>`, one tab per indentation level, LF line ends and nothing after the root end tag. Values stay text (`units` `2.0` and `4.15`, `pricePerUnit` `10` and `5`):

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

The sample log in the original README (`README.md:32-51`) shows a hand-edited rendering: `recieved:`, a double-quoted declaration and a repeated `<orders>` root. The bytes above come from the SC-4 writer, whose layout is taken from DataWeave-produced files in the repository (AAP 0.3.3, 0.4.4). They are a Tier 2A verification point.

### Failure behaviour

The flow declares no exception strategy, so a failure in the flow body gets Mule's default handling: it is logged at ERROR and the message stays consumed. The first five rows are such failures. The last three happen in the poller, before the flow body runs.

| Case | Behaviour |
|---|---|
| mail without attachment (not multipart) | `AttachmentImportService` throws `IllegalArgumentException` `Message has no attachment`; the poller logs ERROR `Error processing POP3 message <n>: java.lang.IllegalArgumentException`; nothing is logged at INFO or written; the mail is deleted |
| first attachment's content is neither text, a stream nor bytes (for example a nested `multipart/alternative` part) | `IllegalStateException` `Unsupported attachment content: <class>`; ERROR log; nothing written; mail deleted |
| first attachment declares a charset the JVM does not know | `UnsupportedEncodingException`; ERROR log; nothing written; mail deleted |
| CSV value with a character XML 1.0 forbids | `DwXmlWriter` refuses the document with an `IllegalStateException`, for example `Invalid XML 1.0 character U+0001 in the text of element <name>`; ERROR log; no INFO line, no file; mail deleted |
| output file cannot be written | `IOException` `Streaming failed. Could not get output stream` (Mule's message), the cause attached; the INFO line is already logged; ERROR log; mail deleted |
| mail whose MIME structure cannot be parsed | ERROR `Cannot read POP3 message <n>; it stays in the mailbox: <exception types>`; the mail is not flagged and is read again by the next poll; the mails after it are still processed |
| deletion flag cannot be set | WARN `Cannot mark POP3 message <n> for deletion; it is not processed: <exception types>` |
| mailbox unreachable, login rejected, server certificate untrusted | ERROR `Cannot poll POP3S mailbox <host>:<port>: <exception types>`; the mailbox is left untouched; the next poll retries |

- Mule 3.8.0 abandoned the rest of a poll after a message that could not be read. Here each message is handled on its own, so one unreadable mail never holds back the mails behind it.
- Diagnostics carry a fixed text, the message's ordinal in the poll and the class names of the exception and its causes. They never carry the Subject, an exception message or a stack trace, because those hold sender- or server-controlled text. The only line with mail content is the flow's own `Received:` line. Mule's default strategy logged its own error report instead; the failure-path log text is not reproduced.
- Tier 2A verification points: the handling of a mail without attachment or with unprocessable content (ERROR log, no file, message consumed), and of a mailbox connection failure (logged, retried on the next poll). The original's start-up behaviour with an unreachable mailbox (`validateConnections="true"`) is not reproduced: the application always starts. When a captured fixture shows other behaviour, the fixture governs and the code is changed to match.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. The original's `mule-app.properties` is empty; its flow reads the three placeholders `pop3.host`, `pop3.user` and `pop3.password` and hard-codes every other value.

Status values:

- `default`: a non-secret default, overridable with `--key=value`; where the original fixed the value, the default is the original's.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). A secret is marked `(secret)`.
- `fixed`: a framework setting that reproduces the original runtime, not a key of the original.

| Key | Type | Default | Environment variable | Original name | Status | Description |
|---|---|---|---|---|---|---|
| `pop3.host` | `String` | none | `POP3_HOST` | `pop3.host` (unchanged) | TODO | POP3S server host; the original README used `pop.gmail.com`. Blank skips every poll (WARN once) |
| `pop3.user` | `String` | none | `POP3_USER` | `pop3.user` (unchanged) | TODO | mailbox login in plain form, for example `receiver@gmail.com`. Blank skips every poll (WARN once) |
| `pop3.password` | `String` | none | `POP3_PASSWORD` | `pop3.password` (unchanged) | TODO (secret) | mailbox password; never logged. Blank skips every poll (WARN once) |
| `pop3.port` | `int` | `995` | — | none: `port="995"` (l.6); the original README listed `pop3.port=995` | default | POP3S port |
| `pop3.folder` | `String` | `INBOX` | — | none | default | folder opened read-write; POP3 serves `INBOX` only |
| `pop3.check-frequency` | `long` (ms) | `100` | — | none: `checkFrequency="100"` (l.3) | default | fixed delay between polls, and the delay before the first poll; a zero, negative or unreadable value stops start-up with an error naming the key and value |
| `pop3.response-timeout` | `int` (ms) | `10000` | — | none: `responseTimeout="10000"` (l.6) | default | connect and read timeout of the POP3S session (`mail.pop3s.connectiontimeout`, `mail.pop3s.timeout`) |
| `pop3.ssl-trust` | `String` | `""` | — | none | default | test-only trust override. Empty keeps default TLS verification against the JVM trust store. A value such as `*` sets `mail.pop3s.ssl.trust` and turns off the server-identity check; the tests use it for GreenMail's self-signed certificate. Leave it empty in production |
| `orders.output-dir` | `String` (a directory path) | `src/test/resources/output` | — | none: `path` (l.28) | default | output directory, relative to the working directory as the original's `path` was |
| `orders.output-file-name` | `String` | `orders.xml` | — | none: `outputPattern` (l.28) | default | output file name |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | — | none | fixed | no HTTP listener, as in the original |
| `logging.level.com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector` | `LogLevel` | `INFO` | — | none | fixed | the project's log level; keeps the `Received:` INFO line of the flow's `logger level="INFO"` (l.27) and the poller's WARN and ERROR lines visible |

Original-to-new key map:

- Unchanged: `pop3.host`, `pop3.user` and `pop3.password` keep their original names.
- `pop3.user` is now the plain mailbox address (`receiver@gmail.com`). The Mule endpoint built a URI from it, so the original needed the URL-encoded form `receiveremailaddress%40gmail.com` (original `README.md:22`), and its test passed `pop3.user_enc` as `pop3.user` (`ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:84`).
- Added: `pop3.port` (the original README's `pop3.port=995`, which the flow hard-coded), `pop3.folder`, `pop3.check-frequency`, `pop3.response-timeout`, `pop3.ssl-trust`, `orders.output-dir` and `orders.output-file-name`.
- Dropped: the original test's `src/test/resources/mule.test.properties` keys `pop3.user_enc`, `smtp.host`, `smtp.port`, `smtp.user`, `smtp.password` and `imap.host`. They served the Gmail sender account and the IMAP cleanup, and GreenMail replaces Gmail in the tests.

Test configuration: `src/test/resources/application-test.yml` (profile `test`) holds GreenMail demo values that are not credentials: `pop3.host` `localhost`, `pop3.user` `receiver@localhost`, a demo password, `pop3.ssl-trust` `*`, `pop3.check-frequency` `3600000` and `orders.output-dir` `target/test-output/orders`. GreenMail runs on dynamic ports, so each polling test supplies `pop3.port` itself. Surefire also sets `orders.output-dir=${project.build.directory}/test-output/orders` and `pop3.check-frequency=3600000` as system properties, so no test writes into `src/test/resources/output` and the background poll stays idle unless a test turns it on.

Gmail: reading `pop.gmail.com` needs POP enabled in the account's settings and an app password for this password sign-in. Gmail applies its own "When messages are accessed with POP" setting to the messages the poller deletes. Supply the three TODO keys through their environment variables or `--key=value` arguments; never commit a real value.

## Mule-to-Java mapping

| Mule construct (`importing-an-email-attachment-using-the-POP3-connector`) | Java construct (`com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector`) |
|---|---|
| `pop3s:connector checkFrequency="100"` (l.3) + `pop3s:inbound-endpoint` (l.6) | `trigger/Pop3MailPoller` (`@Scheduled` `poll()`, single-run `pollOnce()`) |
| `expression-transformer` `attachments-list` (l.7-9) | `Pop3MailPoller`'s attachment list, in Mule's attachment-map order |
| `set-payload #[payload[0].getContent()]` as `application/csv` (l.10) | `service/AttachmentImportService.process`: the first attachment's content, parsed by SC-4 `mapper/DwCsvReader` |
| `dw:transform-message`, `%dw 1.0` script (l.13) | `mapper/OrdersXmlMapper`, rendered by SC-4 `mapper/DwXmlWriter` |
| `logger level="INFO"` `Received: …` (l.27) | SLF4J INFO `Received: <xml>` in `AttachmentImportService` |
| `file:outbound-endpoint path outputPattern` (l.28) | SC-6 `service/FileOutboundWriter` |
| default exception strategy (none declared) | ERROR and WARN lines in `Pop3MailPoller`; the message stays consumed |
| `mule-deploy.properties` (`config.resources=pop-to-xml.xml`) | `ImportingAnEmailAttachmentUsingThePOP3ConnectorApplication` |
| `mule-app.properties` and the `${…}` placeholders | `src/main/resources/application.yml` |
| `ImportingAnEmailAttachmentUsingThePOP3ConnectorIT` (`FunctionalTestCase`, Gmail SMTP, IMAP cleanup) | `ImportingAnEmailAttachmentUsingThePOP3ConnectorTest` (`@SpringBootTest`, GreenMail SMTP and POP3S) |

Shared components, each copied from its reference project with only the `package` line changed, its unit test copied with it (register: [`DECISIONS.md` §4](../../DECISIONS.md#4-shared-component-register)):

| ID | Classes | Copied from | Test copied |
|---|---|---|---|
| SC-4 | `mapper/DwCsvReader` | `import-contacts-into-salesforce-java` | `mapper/DwCsvReaderTest` |
| SC-4 | `mapper/DwXmlWriter` | `web-service-consumer-java` | `mapper/DwXmlWriterTest` |
| SC-6 | `service/FileOutboundWriter` | `processing-orders-with-dataweave-and-APIkit-java` | `service/FileOutboundWriterTest` |
| ST-2 | `FixtureParityTest`, the format of `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` | — (it is the test) |

The project has no HTTP listener, so it carries neither SC-1 nor ST-1.

## Behaviours stubbed, externalized or changed

- The Mule POP3S transport is replaced by a Jakarta Mail `pop3s` store from `spring-boot-starter-mail` (Angus Mail). No connector library is used.
- The mailbox host, user and password are externalized under their original names as TODO placeholders. The port, polling frequency, timeout and output location become keys with the original values as defaults.
- `pop3.user` takes the plain address instead of the URL-encoded form.
- Each mail is handled on its own within a poll (see Failure behaviour), and diagnostics name exception types only.
- `validateConnections="true"` is not reproduced: a mailbox that cannot be reached is logged per poll, and the application still starts.
- The original test's IMAP deletion of the sent mail from the real mailbox has no counterpart: GreenMail keeps the mailbox in memory.
- Not carried (AAP 0.2.2): `catalog/**`, `mule-project.xml`, `src/main/app/mule-deploy.properties`, the empty `src/main/app/mule-app.properties` and `src/test/resources/log4j2-test.xml`. `src/main/resources/input.csv` and `src/test/resources/orders.xsd` are carried unchanged.

## Build, run and test

Run from `java-conversions/importing-an-email-attachment-using-the-POP3-connector-java/`. No credential or external server is needed: GreenMail is embedded in the tests.

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # the scheduled poller, with the TODO keys supplied (below)
java -jar target/importing-an-email-attachment-using-the-POP3-connector-java-1.0.0-SNAPSHOT.jar
```

Supply the three TODO keys when running, for example through their environment variables:

```sh
POP3_HOST=pop.gmail.com POP3_USER=… POP3_PASSWORD=… mvn spring-boot:run
```

Without them the application starts, logs the `pop3.host (POP3_HOST), pop3.user (POP3_USER), pop3.password (POP3_PASSWORD) are not configured; POP3S polling is skipped` WARN once and does not poll.

The output directory is relative to the working directory. Both commands above run from the project directory, so they write `src/test/resources/output/orders.xml`, as the original did. To keep run-time files out of the source tree, pass `--orders.output-dir=target/run/output` (with `mvn spring-boot:run`, through `-Dspring-boot.run.arguments=--orders.output-dir=target/run/output`).

Usage, following the original README steps 3 to 6:

1. Start the application with the keys supplied.
2. Mail `src/main/resources/input.csv` as an attachment to the `pop3.user` mailbox, ideally as the mail's only part (see Endpoints and triggers).
3. On the next poll the log shows `Received: <?xml version='1.0' encoding='UTF-8'?>` followed by the document, `orders.xml` appears in the output directory, and the mail is deleted from the POP3 mailbox.

- `mvn verify` adds nothing beyond `package`'s JaCoCo gate: there is no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ImportingAnEmailAttachmentUsingThePOP3ConnectorTest` | The ported `ImportingAnEmailAttachmentUsingThePOP3ConnectorIT` (`importing-an-email-attachment-using-the-POP3-connector/src/test/java/org/mule/examples/ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:109-140`). The whole application runs with its scheduled poller at the original 100 ms, against GreenMail SMTP and POP3S on dynamic ports. The test sends the original mail: subject `This is the POP Subject Line!`, one `application/octet-stream` part with the bytes of `input.csv` and file name `report`. It waits up to 10 s, replacing the original's fixed 10 s sleep, then asserts the original assertion that the output directory holds a file (`IT.java:140`). It also asserts exactly one file, named `orders.xml`, holding the 289 bytes above, and that the mailbox is empty afterwards (`deleteReadMessages`). |
| `trigger/Pop3MailPollerTest` | Against GreenMail POP3S, with a Mockito `AttachmentImportService`: the original mail's single attachment reaches the flow byte for byte and the mail is deleted, so a second poll finds nothing; a mail without attachment is handed over as an empty list and deleted; a processing failure is logged at ERROR by type, the mail is deleted and the next mail is processed; a rejected login, an unreachable server and an untrusted certificate are logged at ERROR by exception type and return 0 without reaching the flow; after a rejected login or an untrusted certificate the mail stays in the mailbox, and after the rejected login the next poll with the right password processes it; a null, empty or blank `pop3.host`, `pop3.user` or `pop3.password` (each alone, and all three empty) makes every poll return 0 without connecting to the local listener it points at, logs one WARN naming the missing keys with their environment variables plus a DEBUG line per poll, and logs no configured value; attachments come in Mule 3.8's attachment-map order; no part content is read while the list is built; a mail whose MIME cannot be parsed stays in the mailbox while the next one is processed; no Subject or exception text reaches the log. |
| `service/AttachmentImportServiceTest` | Over the real SC-4 reader and writer, the real mapper and a spy of the real SC-6 writer: `orders.xml` gets the 289 bytes (SHA-256 checked) and one INFO `Received:` line; attachments after the first are never read; `text/plain` is decoded with its declared charset; text, stream and byte content forms are read; unsupported content, an unknown charset, a malformed CSV and a character XML 1.0 forbids fail with nothing logged at INFO and nothing written; an existing `orders.xml` is replaced; a message without attachment fails with `Message has no attachment`; a write failure propagates as `Streaming failed. Could not get output stream`, after the INFO line. |
| `mapper/OrdersXmlMapperTest` | `input.csv` maps to the exact 289-byte literal (also through `toXmlBytes`, with its SHA-256 and a non-ASCII value); children follow script order whatever the record's key order; values pass through verbatim; extra columns are ignored; a missing column and a `null` record give empty elements; no records give `<orders/>`; a one-order document validates against the carried `orders.xsd`, whose single `order` element rejects the two-order output. |
| `mapper/DwCsvReaderTest` | SC-4, copied with its class: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed string values. |
| `mapper/DwXmlWriterTest` | SC-4, copied with its class: the single-quoted declaration, tab layout, LF line ends and nothing after the root end tag. |
| `service/FileOutboundWriterTest` | SC-6, copied with its class: directory creation, whole-file replacement, bytes written exactly and text as UTF-8, concurrent writers, symbolic links, permissions, and every failed write reported as `Streaming failed. Could not get output stream`. |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured. |

Coverage: the JaCoCo `check` at `prepare-package` fails the build below 80% LINE coverage over `service/**` and `mapper/**`.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/importing_an_email_attachment_using_the_pop3_connector/FixtureParityTest.java` is ST-2, copied from `hello-world-java` with only its `package` line changed. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenario and holds its fixture contract and capture procedure. The reference implementation and its fixture format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation).

- Replay: the `mail` driver starts GreenMail, registers the captured mailbox, applies the fixture's properties (`pop3.host`, `pop3.port`, `pop3.user`, `pop3.password`, `pop3.ssl-trust` and a temporary `orders.output-dir`), delivers the captured MIME and calls `Pop3MailPoller.pollOnce()`. The scheduled poll is suppressed. The produced `orders.xml` and the `Received:` log event are compared byte for byte, and the mailbox's contents after the poll are compared message by message.
- Capture (Tier 2A): the unmodified original runs on a licensed Mule 3.8 EE runtime against a local GreenMail POP3S server on port 995 in place of Gmail, and receives the mail the original test sends. The flow makes no outbound HTTP call, so the fixture's `upstream` is `[]`.
- With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

`SCENARIOS.md` scenarios:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `pop3-mail-csv-attachment-to-orders-xml` | `mail` | `deterministic` | the original test's mail with `input.csv` as its only part, delivered to the `pop3.user` mailbox; one poll through `Pop3MailPoller.pollOnce()` | `UNVERIFIED-uncaptured` |

No scenario is `unreachable` or `nondeterministic`: the flow has one path and reads no clock or random value.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| importing-an-email-attachment-using-the-POP3-connector-java | importing-an-email-attachment-using-the-POP3-connector | General | PASS | PASS | 94.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 218 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 218 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 374 of 397 lines. The `mapper` package is fully covered (256 of 256 lines); `service` has 118 of 141 (`AttachmentImportService` 24 of 24, `FileOutboundWriter` 94 of 117). Total project LINE coverage: 93.7% (507 of 541 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=1`, `failed=0`.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked until the Mule EE runtime licence and the EE Maven repository credentials are confirmed ([`DECISIONS.md` §9](../../DECISIONS.md#9-verification-tiers-and-tier-2a-escalation-state)). No fixture can be captured from the original, so parity rests on the fallback of AAP 0.8.4:

- `ImportingAnEmailAttachmentUsingThePOP3ConnectorTest` carries the original committed assertion: after the mail with the CSV attachment is delivered and polled, the output directory is not empty.
- `Pop3MailPollerTest`, `AttachmentImportServiceTest` and `OrdersXmlMapperTest` cover the trigger, the flow steps and the transform.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the POP3 transport, the DataWeave writer and the file endpoint, not that its bytes and log lines equal what the original runtime produced.
- Effects and branches no original assertion covers, tested only by the new tests: the content of `orders.xml` (the original only counted files), the INFO `Received:` line, the deletion of read messages, and the failure branches (no attachment, unprocessable content, unreadable MIME, unreachable or rejected mailbox).
- Unverified scenarios, until captured: `pop3-mail-csv-attachment-to-orders-xml` (`deterministic`).

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenario is captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `importing-an-email-attachment-using-the-POP3-connector/`.

