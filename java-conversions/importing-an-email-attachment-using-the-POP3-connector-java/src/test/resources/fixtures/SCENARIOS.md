# Parity scenarios — importing-an-email-attachment-using-the-POP3-connector-java

Scenarios for the fixture replay (ST-2) of `importing-an-email-attachment-using-the-POP3-connector-java` (package `com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector`). The original example is `importing-an-email-attachment-using-the-POP3-connector` (folder `importing-an-email-attachment-using-the-POP3-connector/`), a General example that is mail-triggered and has no HTTP listener (`spring.main.web-application-type: none`). Its single flow `pop-to-xmlFlow1` (`pop-to-xml.xml:5-29`), the only flow `config.resources=pop-to-xml.xml` selects, polls a POP3S inbound endpoint on port 995 every 100 ms (`checkFrequency`), takes the first attachment of each mail as CSV, maps it to an orders XML document, logs that document at INFO and writes it to `orders.xml`. Every reachable branch listed for the example in AAP 0.7.5 is exactly one scenario below, and each scenario's captured fixture is `fixtures/<scenario>.json`. Until that fixture is captured, the project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.4 fallback, 0.8.7).

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its row comes from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `pop3-mail-csv-attachment-to-orders-xml` | `mail` | `deterministic` | mail sent over SMTP to the `pop3.user` mailbox, subject `This is the POP Subject Line!`, `multipart/mixed` with one part holding the bytes of `input.csv` (`src/main/resources`), part header `Content-Type: application/octet-stream`, part header `Content-Disposition: attachment; filename=report` (as `ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:109-129` sends it); the replay invokes `trigger.Pop3MailPoller.pollOnce()` | empty `orders.output-dir`; the `pop3.user` mailbox holding only that mail | first attachment → CSV → `orders/order(orderId, name, units, pricePerUnit)` XML → `orders.xml` written, INFO `Received: <xml>` logged, message deleted | `importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:3-29`, `importing-an-email-attachment-using-the-POP3-connector/src/test/java/org/mule/examples/ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:109-129,140` |

This is the flow's only branch: `pop-to-xml.xml` has no `choice`, filter or exception strategy, so there is no failure-branch or `unreachable` row, and the row is `deterministic` because its output depends on its mail alone.

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
- `port`: the listener port the capture targeted (this project has no HTTP listener, so its fixture has no `port`). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.
- For this project, `request` carries the mail input: `mail.user` is the plain address of the `pop3.user` mailbox (`@`, not `%40`), `mail.mimeBase64` the raw MIME as sent (Capture step 4), Base64-encoded, and `invoke` names type `trigger.Pop3MailPoller` and method `pollOnce`, the poller's single-run method.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For this project, `state` seeds the mailbox and points the poller and the file writer at replay-owned targets: `mail.users` holds the receiving mailbox with the email, login and password the capture used; `properties` sets `pop3.host` to `localhost`, `pop3.port` to `${greenmail.pop3s.port}`, `pop3.user` and `pop3.password` to that mailbox's login (its plain email address) and password, `pop3.ssl-trust` to `*` because GreenMail presents a self-signed certificate, and `orders.output-dir` to `${dir:output}`; `directories` seeds `output` empty.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of this project's scenario: its flow makes no outbound HTTP or SOAP call, so `upstream` is an empty list, and its file, log and mailbox effects are recorded under `response`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8 of the reference format).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 of the reference format defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and again 500 ms later.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.
- For this project, `response` holds no HTTP status, reason or headers, only the effects of the poll: `directories` the produced `orders.xml` bytes under `output`; `logs` the INFO event `Received: <xml>` of the `Log the converted XML` logger (`pop-to-xml.xml:27`), with its logger category, level and message as captured, all three of which the replay compares; and `mailboxes` the `pop3.user` mailbox after the poll, under its plain email address, one raw MIME entry per message still there, so the array length is the message count. It has no `mails`, because the flow sends no mail.

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
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

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

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by key names in order, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name, with digit runs in unmatched names replaced by `#`; logs compare logger and level in order and only whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then holds `verified=0`, `unverified=1` and `failed=0`, followed by `scenario.pop3-mail-csv-attachment-to-orders-xml=UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of the scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `mail.eml` holds the raw MIME as sent, `output/` the files the flow wrote, `mule.log` the application log of the run, and `mailbox-after/` each message left in the mailbox after the poll, one raw MIME file per message. Working files stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/importing-an-email-attachment-using-the-POP3-connector-java/`). The trigger is a mailbox, not an HTTP listener, so the reference format's `curl` capture is replaced by the mail procedure below (AAP 0.8.4); the flow makes no HTTP or SOAP outbound call, so no WireMock recording proxy is started. Placeholders in angle brackets are values chosen for the capture.

1. Build the unmodified original `importing-an-email-attachment-using-the-POP3-connector` with `mvn -B package`, with the EE repository credentials in `settings.xml`, and deploy the archive to a licensed Mule 3.8 EE runtime with the runtime properties of step 2 (`-M-D<key>=<value>`). Never edit the original folder.
2. Point the original's `pop3.host`, `pop3.user` and `pop3.password` at a local GreenMail POP3S server that holds that user. GreenMail standalone 2.1.14 (`com.icegreen:greenmail-standalone`, the release of the `greenmail-junit5` the project pins) serves SMTP for step 4 and POP3S for the flow, with the user's full email address as its login:

   ```sh
   mkdir -p target/fixture-capture
   java -Dgreenmail.smtp.hostname=127.0.0.1 -Dgreenmail.smtp.port=3025 \
        -Dgreenmail.pop3s.hostname=127.0.0.1 -Dgreenmail.pop3s.port=995 \
        -Dgreenmail.users='<local-part>:<password>@<domain>' -Dgreenmail.users.login=email \
        -jar greenmail-standalone-2.1.14.jar > target/fixture-capture/greenmail.log 2>&1 &
   GM=$!
   ```

   `kill "$GM"` stops GreenMail after step 5.
   - The flow hard-codes `port="995"` (`pop-to-xml.xml:6`), so GreenMail POP3S must be reachable on 995: bind it there, which needs the privilege to open a port below 1024, or bind it to a free port and redirect 995 to that port.
   - The Mule JVM must trust GreenMail's self-signed certificate. Export the certificate into a trust store:

     ```sh
     openssl s_client -connect 127.0.0.1:995 < /dev/null 2> /dev/null | openssl x509 -out target/fixture-capture/greenmail.pem
     keytool -importcert -noprompt -alias greenmail -file target/fixture-capture/greenmail.pem -storetype PKCS12 -keystore target/fixture-capture/greenmail-trust.p12 -storepass '<store password>'
     ```

     Then deploy with `-M-Dpop3.host=localhost -M-Dpop3.user=<local-part>%40<domain> -M-Dpop3.password=<password>` and `-M-Djavax.net.ssl.trustStore=<absolute path of greenmail-trust.p12> -M-Djavax.net.ssl.trustStorePassword=<store password> -M-Djavax.net.ssl.trustStoreType=PKCS12`. Before capturing, confirm in the Mule log that the `POP3` connector polls without a TLS or authentication error.
   - The Mule endpoint needs the user URL-encoded, `@` written `%40`, because its address is a URI built from `user`: the original IT passes `pop3.user_enc` as `pop3.user` (`ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:82-84`), and the original README shows `receiveremailaddress%40gmail.com` (`README.md:22`). The mail itself is addressed to the plain user, and the fixture records the plain form (`request.mail.user`, `state.mail.users[].email`).
3. Seed the state: an empty output directory `src/test/resources/output`, relative to the runtime's working directory (`pop-to-xml.xml:28`), and a mailbox holding only the scenario mail. A freshly started GreenMail holds no mail, so it is restarted before a repeated capture.
4. Compose the scenario mail as the original IT composes it (`ImportingAnEmailAttachmentUsingThePOP3ConnectorIT.java:109-129`): subject `This is the POP Subject Line!`, a `multipart/mixed` body with one part whose data source is `importing-an-email-attachment-using-the-POP3-connector/src/main/resources/input.csv` (byte-identical to this project's copy), content type `application/octet-stream` and file name `report`. Write it once to `src/test/capture/pop3-mail-csv-attachment-to-orders-xml/mail.eml` with `MimeMessage.writeTo`, then deliver exactly those bytes through GreenMail SMTP to the plain `pop3.user` address, and wait for the 100 ms poll (`pop-to-xml.xml:3`) to write `orders.xml`:

   ```sh
   S=pop3-mail-csv-attachment-to-orders-xml
   curl -sS --url smtp://127.0.0.1:3025 --mail-from '<sender address>' --mail-rcpt '<local-part>@<domain>' --upload-file "src/test/capture/$S/mail.eml"
   ```

5. Read back, once `orders.xml` exists:
   - the produced files: every file of the output directory, copied unedited to `src/test/capture/$S/output/`;
   - the INFO event `Received: <xml>` of the `Log the converted XML` logger (`pop-to-xml.xml:27`): the run's application log is kept as `src/test/capture/$S/mule.log`, and the event's logger category, level and complete message, which spans several lines, are taken from it unaltered;
   - the mailbox afterwards: undeploy the application, then retrieve every message still in the mailbox over POP3S with the same login into `src/test/capture/$S/mailbox-after/`, message `n` as `<n>.eml` (`curl -sS -k -u '<local-part>@<domain>:<password>' pop3s://127.0.0.1:995/` lists them, and `pop3s://127.0.0.1:995/<n>` retrieves message `n`). Whatever the listing shows is kept, an empty mailbox included, so the fixture records the original's disposition of the polled message.
6. Write `fixtures/pop3-mail-csv-attachment-to-orders-xml.json` raw, without editing: `request` from step 4 (`mail.user` the plain address, `mail.mimeBase64` the Base64 of `mail.eml`, and the `invoke` the Fixture contract names); `state` as the Fixture contract defines it, with the mailbox's email, login and password; `"upstream": []`; and `response` from step 5 (`directories.output`, one entry per file of `output/` with its name and its bytes, as `body` when they are valid UTF-8 and `bodyBase64` otherwise; `logs`, the event; `mailboxes`, the one key `<local-part>@<domain>`, the plain `pop3.user` address, holding one `mimeBase64` per `<n>.eml` of `mailbox-after/` in message-number order, `[]` when the listing shows none).
   - Identifiers the server assigns are not captured: a `Message-ID` it adds, `Received` headers, the GreenMail port.
   - The fixture names no live host: GreenMail and its user are local, and the replay supplies its own ports.
   - Where the captured MIME differs from the row's `Request` description, the captured MIME governs.
   - Captured values and file bytes are never adjusted afterwards, to match the Java output or for any other reason.

Replay of this fixture by `FixtureParityTest`'s `mail` driver contacts no live mail server. It starts GreenMail with each protocol on its `${greenmail.<proto>.port}`, registers the mailbox of `state.mail.users`, applies `state.properties` (`pop3.host`, `pop3.port`, `pop3.user`, `pop3.password`, `pop3.ssl-trust`, and `orders.output-dir` as `${dir:output}`, a directory under the replay's JUnit `@TempDir` root), delivers `request.mail` straight into that mailbox, and calls `Pop3MailPoller.pollOnce()` through `request.invoke` rather than waiting for the schedule, which replay suppresses. The row is `deterministic`, so the produced file and the log event are compared byte for byte, and `response.mailboxes` is compared with what the `pop3.user` mailbox holds after the poll: the message count first, the delivered input mail counted until the poller removes it, then each message.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `pop3-mail-csv-attachment-to-orders-xml`, that means `trigger/Pop3MailPoller`, `service/AttachmentImportService`, `mapper/OrdersXmlMapper`, the SC-4 `DwCsvReader` and `DwXmlWriter`, or the SC-6 `FileOutboundWriter`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
