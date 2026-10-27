# Parity scenarios — importing-an-email-attachment-using-the-IMAP-connector-java

Scenarios for the fixture replay (ST-2) of `importing-an-email-attachment-using-the-IMAP-connector-java` (package `com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector`). The original example is `importing-an-email-attachment-using-the-IMAP-connector` (folder `importing-an-email-attachment-using-the-IMAP-connector/`), a General example that is mail-triggered and has no HTTP listener (`spring.main.web-application-type: none`). Its single flow `imap-to-csvFlow1` (`imap-to-xml.xml:9-33`), the only flow `config.resources=imap-to-xml.xml` selects, polls an IMAPS inbound endpoint on port 993 every 100 ms (`checkFrequency`, `imap-to-xml.xml:5`), takes the first attachment of each mail as CSV, maps it to an orders XML document, logs that document at INFO and writes it to `orders.xml`. The replay trigger is `mail`: the IMAPS poller `trigger/ImapMailPoller`, run once per scenario through its single-run method `pollOnce()`. The branch AAP 0.7.5 lists for the example and the path Mule's default exception strategy takes for a mail without an attachment are one scenario each below, and each scenario's captured fixture is `fixtures/<scenario>.json`.

No fixture JSON exists yet: fixtures are written only by the Tier 2A capture against the running original (see Capture). Until then the project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable`, the same label its `README.md` and the root `SUMMARY.md` carry (AAP 0.8.4 fallback, 0.8.7), never PASS. Its Tier 2B label is `N/A`, because it is not a connector project.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5 and the flow's error path.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `imap-mail-csv-attachment` | `mail` | `deterministic` | mail from `sender@localhost` to `receiver@localhost`, subject `This is the IMAP Subject Line!`, `multipart/mixed` with one body part holding the bytes of `input.csv` (`src/main/resources`), content type `application/octet-stream`, file name `report` (as `ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:94-116` builds it); delivered to the INBOX of `receiver@localhost`; the replay invokes `trigger.ImapMailPoller.pollOnce()` | the INBOX of `receiver@localhost` holds only this mail; the output directory (`orders.output-dir`) is empty | the poller copies the message and flags it DELETED (Mule's `deleteReadMessages` default) → the first attachment, in attachment-list order, is read as CSV by SC-4 `DwCsvReader` → `mapper/OrdersXmlMapper` builds `orders/order(orderId, name, units, pricePerUnit)` XML through SC-4 `DwXmlWriter` → INFO `Received: <xml>` is logged → SC-6 `FileOutboundWriter` writes `orders.xml` to the output directory → the message is expunged when the folder closes | `importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:10-32`; `importing-an-email-attachment-using-the-IMAP-connector/src/test/java/org/mule/examples/ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:94-122` |
| `imap-mail-without-attachment` | `mail` | `deterministic` | mail from `sender@localhost` to `receiver@localhost`, subject `This is the IMAP Subject Line!`, not multipart: a single `text/plain` body `This mail has no attachment.` and no attachment; delivered to the INBOX of `receiver@localhost`; the replay invokes `trigger.ImapMailPoller.pollOnce()` | the INBOX of `receiver@localhost` holds only this mail; the output directory (`orders.output-dir`) is empty | the message is flagged DELETED before routing → the attachment list is empty, so `payload[0]` fails → Mule's default exception strategy handles the failure: an ERROR log event, no `orders.xml` written, and the message is still expunged; a Tier 2A verification point: the capture fixes the exact ERROR text and the mailbox disposition, and a difference is fixed in the Java code | `importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:10-17` |

No scenario of this project is `unreachable` or `nondeterministic`: each row's output depends on its mail alone. `imap-to-xml.xml` declares no `choice`, filter or exception strategy, so `imap-mail-without-attachment` is the flow's only failure path. Its source range covers the endpoint at line 10, whose connector keeps the `deleteReadMessages` default (`imap-to-xml.xml:5`); the `attachments-list` evaluator at lines 11-13; the `payload[0]` at line 15 that fails; and the header of the script at line 17, which is never reached.

Table rules, for every project that copies this format:

- The header is exactly the seven columns above, with these names and in this order, followed by a separator row of `-` cells. `FixtureParityTest` fails the class on any other header, a missing separator row or an empty table.
- Cells are split on unescaped `|`, so a `|` inside a cell is written `\|`. Each cell is trimmed and unescaped, and its surrounding backticks are stripped, so code spans are allowed. The table ends at the first line that does not start with `|`.
- `Scenario` is kebab-case (`[a-z0-9]+(-[a-z0-9]+)*`), unique in the file, formed from the entry point and the branch (AAP 0.8.4), and equal to the fixture file name without `.json`.
- `Trigger` is one of `http`, `soap`, `file`, `jms`, `amqp`, `mq`, `mail`, `scheduler`. It selects the replay driver.
- `State` is one of `deterministic`, `nondeterministic`, `unreachable`. A `nondeterministic` row names in `Branch` what varies (the current time, random values, a generated name). An `unreachable` row states in `Branch` the proof that no input selects it (AAP 0.8.2).
- `Request` is the exact input the capture sends, written as: `METHOD /path` (with `?query` when there is one); then each request header as header `Name: value`, in sending order; then the body as `empty body`, as an inline code span written byte for byte (body `ping`), or as body = the bytes of `<file>` (`src/test/resources`) for a file body, which is never inlined. Non-HTTP rows name the input file, message or mail, and the single-run method the replay invokes.
- `Seeded state` is `none`, or a short description of what the fixture's `state` seeds (rows, files, messages, mailboxes, a watermark).
- `Branch` names the flow path the request selects. Outcome words written there name the branch. They are never captured values; the captured fixture alone holds the response.
- `Source` cites the original by file and line.
- Every row of this file is captured by delivering its mail to the GreenMail mailbox the deployed original polls (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (this project has no HTTP listener, so its fixtures have no `port`). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.
- For this project, `request` carries the mail input: `mail.user` is `receiver@localhost`, the plain address of the receiving mailbox (`@`, not `%40`); `mail.mimeBase64` is the raw MIME of the scenario mail (Capture step 4), Base64-encoded; and `invoke` names type `trigger.ImapMailPoller` and method `pollOnce`, the poller's single-run method. The driver delivers the mail straight into that mailbox's `INBOX`, the folder `imap.folder` names, so no SMTP leg is replayed. `awaitMillis` keeps its default of 10000 ms, the wait of the original test (`ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:121`).

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource}`, the classpath Qpid Broker-J configuration that declares the broker objects and messages.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For this project, `state` seeds the mailbox and points the poller and the file writer at replay-owned targets:
  - `mail.users` holds the one mailbox: email and login `receiver@localhost`, with the test-only value `application-test.yml` gives `imap.password`. The poller therefore logs in with the test profile's `imap.user` and `imap.password` unchanged. That value is a GreenMail test literal, never a live credential, and this file names it by key only; no live credential appears in any fixture.
  - `properties` sets `imap.port` to `${greenmail.imaps.port}` and `orders.output-dir` to `${dir:output}`, and nothing else. `imap.host` (`127.0.0.1`), `imap.user` and the TLS trust settings of `imap.properties` come from `application-test.yml` (profile `test`, which surefire activates), never from the fixture. That file sets no port, because the tests' GreenMail ports are allocated at run time, so without the override `imap.port` stays at the `993` of `application.yml` and the replay boundary fails the class.
  - `directories` seeds `output` empty (`"output": []`).

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of both scenarios of this project: its flow makes no outbound HTTP or SOAP call, so capture records an empty list, and the file, log and mailbox effects are recorded under `response`. The key is always written, because a missing key would leave the scenario `UNVERIFIED-no-upstream` although nothing went unrecorded.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8 of the reference format).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 of the reference format defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, in arrival order; for `amqp`, `destination` is the queue. Only the listed properties are compared, and no further message may follow.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the sent or received mails as raw MIME.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.
- For this project, `response` holds no HTTP status, reason or headers, only the effects of the poll:
  - `directories`: the final file set of `output`, each file with its name and its bytes. For `imap-mail-csv-attachment` that is the produced `orders.xml`; for `imap-mail-without-attachment` the captured set, which the row expects to be empty.
  - `logs`: the captured log events, each reduced to its logger category, level and message text, with timestamp and thread name stripped. For `imap-mail-csv-attachment` they are the INFO event `Received: <xml>` of the `Log the converted XML` logger (`imap-to-xml.xml:31`); for `imap-mail-without-attachment`, the ERROR event of Mule's default exception strategy. The replay compares all three fields.
  - `mails`: the messages left in the INBOX after the poll, one raw MIME entry each, so the array length is the message count. The driver compares it with what GreenMail holds after the poll without counting the delivered input mail, so whether the input mail was expunged is not compared on replay. That disposition stays a Tier 2A verification point, kept with the INBOX flags in the capture evidence `mailbox-after/` (Capture step 5).

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a free port, the same one for every use of the same name.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`.
- `${mongo.uri}` and `${ldap.port}`.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written.

Skeleton of an HTTP fixture; every value comes from the capture:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>" },
  "state":    {},
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

Skeleton of a fixture of this project; every `<captured>` value comes from the capture, and the rest is fixed by the contract above:

```json
{
  "request":  { "mail": { "user": "receiver@localhost", "mimeBase64": "<captured>" },
                "invoke": { "type": "trigger.ImapMailPoller", "method": "pollOnce" } },
  "state":    { "mail": { "users": [ { "email": "receiver@localhost", "login": "receiver@localhost",
                                       "password": "<the test-profile value of imap.password>" } ] },
                "properties": { "imap.port": "${greenmail.imaps.port}", "orders.output-dir": "${dir:output}" },
                "directories": { "output": [] } },
  "upstream": [],
  "response": { "directories": { "output": "<captured>" },
                "logs": "<captured>",
                "mails": "<captured>" }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by key names in order, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name, with digit runs in unmatched names replaced by `#`; logs compare logger and level in order and only whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.

Both rows of this project are `deterministic`, so the produced files, the log messages and the mails are compared byte for byte, with no normalization.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then holds `verified=0`, `unverified=2` and `failed=0`, followed by `scenario.imap-mail-csv-attachment=UNVERIFIED-uncaptured` and `scenario.imap-mail-without-attachment=UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured. This is the current label of this project.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `mail.eml` holds the raw MIME as sent, `output/` the files the flow wrote, `mule.log` the application log of the run, and `mailbox-after/` the INBOX after the poll (`search.txt` and `flags.txt` with the server's answers, and one raw MIME file per message still there). Working files stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/importing-an-email-attachment-using-the-IMAP-connector-java/`). The trigger is a mailbox, not an HTTP listener, so the reference format's `curl` capture is replaced by the mail procedure below (AAP 0.8.4); the flow makes no HTTP or SOAP outbound call, so no WireMock recording proxy is started. Placeholders in angle brackets are values chosen for the capture, and `<imap.password of application-test.yml>` stands for the test-only value that file gives the key, so the captured mailbox is the one the replay registers.

1. Build the unmodified original `importing-an-email-attachment-using-the-IMAP-connector` with `mvn -B package`, with the EE repository credentials in `settings.xml`, and deploy the archive to a licensed Mule 3.8 EE runtime with the runtime properties of step 2 (`-M-D<key>=<value>`). Never edit the original folder.
2. Point the original at a local GreenMail IMAPS server that stands in for the public mail provider. GreenMail standalone 2.1.14 (`com.icegreen:greenmail-standalone`, the release of the `greenmail-junit5` the project pins) serves SMTP for step 4 and IMAPS for the flow, with the user's full email address as its login:

   ```sh
   mkdir -p target/fixture-capture
   java -Dgreenmail.smtp.hostname=127.0.0.1 -Dgreenmail.smtp.port=3025 \
        -Dgreenmail.imaps.hostname=127.0.0.1 -Dgreenmail.imaps.port=993 \
        -Dgreenmail.users='receiver:<imap.password of application-test.yml>@localhost' -Dgreenmail.users.login=email \
        -jar greenmail-standalone-2.1.14.jar > target/fixture-capture/greenmail.log 2>&1 &
   GM=$!
   ```

   `kill "$GM"` stops GreenMail after step 5.
   - The flow hard-codes `port="993"` (`imap-to-xml.xml:10`), so GreenMail IMAPS must be reachable on 993: bind it there, which needs the privilege to open a port below 1024, or bind it to a free port and redirect 993 to that port.
   - The Mule JVM must trust GreenMail's self-signed certificate. Export the certificate into a trust store:

     ```sh
     openssl s_client -connect 127.0.0.1:993 < /dev/null 2> /dev/null | openssl x509 -out target/fixture-capture/greenmail.pem
     keytool -importcert -noprompt -alias greenmail -file target/fixture-capture/greenmail.pem -storetype PKCS12 -keystore target/fixture-capture/greenmail-trust.p12 -storepass '<store secret>'
     ```

     Pass the trust store as JVM options of the runtime, never as application properties: `-M-Djavax.net.ssl.trustStore=<absolute path of greenmail-trust.p12> -M-Djavax.net.ssl.trustStorePassword=<store secret> -M-Djavax.net.ssl.trustStoreType=PKCS12` on the `mule` command line, or the same `-D` options as `wrapper.java.additional.<n>` entries of `conf/wrapper.conf`.
   - Set the three `imap.*` placeholders of the endpoint (`imap-to-xml.xml:10`) as runtime system properties: `-M-Dimap.host=127.0.0.1 -M-Dimap.user=receiver%40localhost -M-Dimap.password=<imap.password of application-test.yml>`. The original test set the same keys with `System.setProperty` (`ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:72-74`). The Mule endpoint needs the user URL-encoded, `@` written `%40`, because its address is a URI built from `user`: the test passed `imap.user_enc` as `imap.user` (line 74), and the original README shows the encoded form (`importing-an-email-attachment-using-the-IMAP-connector/README.md:22`). The mail itself is addressed to the plain user, and the fixture records the plain form (`request.mail.user`, `state.mail.users[].email`).
   - Before capturing, confirm in the Mule log that the `IMAP` connector polls without a TLS or authentication error.
3. Seed the state before each scenario: restart GreenMail, so the INBOX of `receiver@localhost` starts empty, and empty the output directory `src/test/resources/output`, relative to the runtime's working directory (`imap-to-xml.xml:32`). Record that state as the Fixture contract defines it: the mailbox in `state.mail.users` and `directories.output` as `[]`.
4. Compose the scenario mail with Jakarta Mail `MimeMessage`, from `sender@localhost` to `receiver@localhost`, subject `This is the IMAP Subject Line!`:
   - `imap-mail-csv-attachment`: as the original test composes it (`ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:94-116`), a `multipart/mixed` body with one part whose data source is `importing-an-email-attachment-using-the-IMAP-connector/src/main/resources/input.csv` (byte-identical to this project's copy), content type `application/octet-stream` and file name `report`.
   - `imap-mail-without-attachment`: `setText("This mail has no attachment.")`, a single `text/plain` body and no attachment.

   Call `saveChanges()`, remove the generated `Message-ID` header, and write the message once to `src/test/capture/<scenario>/mail.eml` with `writeTo`. Deliver exactly those bytes through GreenMail SMTP to the plain address, then wait at least 10000 ms, the original test's wait (`ImportingAnEmailAttachmentUsingTheIMAPConnectorIT.java:121`), for the 100 ms poll (`imap-to-xml.xml:5`) to take the mail:

   ```sh
   S=<scenario>
   curl -sS --url smtp://127.0.0.1:3025 --mail-from 'sender@localhost' --mail-rcpt 'receiver@localhost' --upload-file "src/test/capture/$S/mail.eml"
   ```

   GreenMail stores the message with the `Received` and `Return-Path` headers it adds; `mail.eml` is that stored message without them, and it is the `request` MIME.
5. Read back, once the wait has passed:
   - the produced files: every file of the output directory, copied unedited to `src/test/capture/$S/output/`; for `imap-mail-without-attachment` the directory as found, whatever it holds;
   - the log events: the run's application log is kept as `src/test/capture/$S/mule.log`, and each event of the `Log the converted XML` logger (`imap-to-xml.xml:31`) and of the default exception strategy is taken from it with its logger category, level and complete message text, which may span several lines, its timestamp and thread name stripped and nothing else altered;
   - the mailbox afterwards: undeploy the application, then read the INBOX over IMAPS with the same login, the flags before the bodies, because fetching a body sets `\Seen`:

     ```sh
     U='receiver@localhost:<imap.password of application-test.yml>'; A="src/test/capture/$S/mailbox-after"; mkdir -p "$A"
     curl -sS -k -u "$U" 'imaps://127.0.0.1:993/INBOX' -X 'SEARCH ALL' > "$A/search.txt"
     # for each message number <n> that search.txt lists:
     curl -sS -k -u "$U" 'imaps://127.0.0.1:993/INBOX' -X 'FETCH <n> (FLAGS)' >> "$A/flags.txt"
     curl -sS -k -u "$U" 'imaps://127.0.0.1:993/INBOX/;MAILINDEX=<n>' > "$A/<n>.eml"
     ```

     A message the flow flagged but did not expunge is listed with `\Deleted` among its flags.
6. Write `fixtures/<scenario>.json` raw, without editing: `request` from step 4 (`mail.user` the plain address, `mail.mimeBase64` the Base64 of `mail.eml`, and the `invoke` the Fixture contract names); `state` as the Fixture contract defines it; `"upstream": []`; and `response` from step 5 (`directories.output`, one entry per file of `output/` with its name and its bytes, as `body` when they are valid UTF-8 and `bodyBase64` otherwise; `logs`, the events in logged order; `mails`, one `mimeBase64` per `<n>.eml` of `mailbox-after/`).
   - Server-assigned and generated values are not captured: the `Received` and `Return-Path` headers GreenMail adds, the generated `Message-ID`, the GreenMail port, and the timestamp and thread name of each log event.
   - The fixture names no live host: GreenMail and its user are local, and the replay supplies its own ports.
   - Where the captured MIME differs from the row's `Request` description, the captured MIME governs.
   - A scenario that cannot be captured, for example because no IMAPS server can be made reachable on port 993, gets no fixture and stays `UNVERIFIED-uncaptured`.
   - Captured values and file bytes are never adjusted afterwards, to match the Java output or for any other reason.

Replay of these fixtures by `FixtureParityTest`'s `mail` driver (AAP 0.8.4, Mail) contacts no live mail server:

1. It starts GreenMail on `127.0.0.1` with each protocol on its `${greenmail.<proto>.port}` and registers the mailbox of `state.mail.users`, the user and login the poller takes from `application-test.yml`.
2. It applies `state.properties`: `imap.port` becomes the GreenMail IMAPS port, and `orders.output-dir` becomes `${dir:output}`, a directory under the replay's JUnit `@TempDir` root, seeded empty from `state.directories`.
3. It delivers `request.mail` straight into the INBOX of `receiver@localhost`.
4. It calls `ImapMailPoller.pollOnce()` through `request.invoke` rather than waiting for the schedule, which replay suppresses (the test profile's `imap.initial-delay` holds it back as well).
5. It compares the produced files (names and bytes), the log events and `response.mails`.
6. Both rows are `deterministic`, so every comparison is byte for byte, with no normalization.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for these scenarios, that means `trigger/ImapMailPoller`, `service/AttachmentImportService`, `mapper/OrdersXmlMapper`, the SC-4 `DwCsvReader` and `DwXmlWriter`, or the SC-6 `FileOutboundWriter`, and the captured logger categories and messages govern the Java log statements as the captured bytes govern the file. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
