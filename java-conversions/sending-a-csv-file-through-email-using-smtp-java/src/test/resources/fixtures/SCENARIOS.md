# Parity scenarios — sending-a-csv-file-through-email-using-smtp-java

Scenarios for the fixture replay (ST-2) of `sending-a-csv-file-through-email-using-smtp-java` (package `com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp`). The original example is `sending-a-csv-file-through-email-using-smtp` (folder `sending-a-csv-file-through-email-using-smtp/`), a General example that is file-triggered and has no HTTP listener (`spring.main.web-application-type: none`). Its single flow `csv-to-smtpFlow` (`src/main/app/csv-to-smtp.xml:4-21`) polls `src/main/resources/input` at Mule's default frequency of 1000 ms, reads each polled file as CSV, maps its rows to a list of maps with a `%dw 1.0` script, logs that list at INFO and sends its rendering as the body of an email through the SMTP outbound endpoint and the original's Gmail connector; the endpoint declares no `moveToDirectory`, so the file is deleted afterwards. The replay trigger is `file`: `trigger/CsvFileTrigger` over SC-5 `trigger/FileInboundPoller`, run once per scenario through its single-run method `pollOnce()`. The one branch listed for the example in AAP 0.7.5 is the one scenario below, and its captured fixture is `fixtures/<scenario>.json`.

No fixture JSON exists yet: fixtures are written only by the Tier 2A capture against the running original (see Capture). Until then the project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable`, the same label its `README.md` and the root `SUMMARY.md` carry (AAP 0.8.4 fallback, 0.8.7), never PASS. Its Tier 2B label is `N/A`, because it is not a connector project.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there and adapted to this project's file trigger, and its row comes from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `file-input-csv-emailed` | `file` | `deterministic` | file `input.csv` holding the bytes of `input.csv` (`src/main/resources`, byte-identical to the original's copy: header `orderId,name,units,pricePerUnit`, rows `1,aaa,2.0,10` and `2,bbb,4.15,5`, LF line ends, no trailing newline, 57 bytes), placed in the input directory (`csv-email.input-dir`) under the name `input.csv`, the `outputPattern` the original test dispatched it with; the replay invokes `trigger.CsvFileTrigger.pollOnce()` | empty input directory; GreenMail SMTP with the user `test@localhost`, whose mailbox is empty | SC-4 `DwCsvReader` reads the CSV → `mapper/OrderLinesMapper` applies the `%dw 1.0` script (`name`, `orderId`, `pricePerUnit` and `units` `as :number`, `totalPrice` = `units` × `pricePerUnit` `as :number`) → INFO `Processing objects: #[payload]` → `client/CsvMailSender` sends the list's rendering as the mail body to `mail.to` → SC-5 deletes the input file (no `moveToDirectory`) | `sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:4-21`, `sending-a-csv-file-through-email-using-smtp/src/test/java/org/mule/examples/SendingACsvFileThroughEmailUsingSmtpIT.java:112-129` |

No scenario of this project is `unreachable` or `nondeterministic`: `csv-to-smtp.xml` declares no `choice`, filter or exception strategy, so the flow has one path, and the mail it sends is a function of the input file alone. The only values that vary between runs are the `Date` and `Message-ID` header fields the mail client sets and the `Received` field the server adds; the capture leaves them out (AAP 0.8.4) and the replay does not compare them. The message is a single `text/plain` part, so no MIME boundary varies either.

Notes for this project:

- Source facts behind the row: line 3 is the original's Gmail connector (`validateConnections="true"`); line 5 is `file:inbound-endpoint path="src/main/resources/input" responseTimeout="10000"`, which declares no `pollingFrequency` and no `moveToDirectory` and uses no `file:connector`, so under SC-5 Mule's default connector polls every 1000 ms and deletes each file after any non-rollback outcome; lines 8-17 are the `%dw 1.0` script with `%output application/java`; line 19 is the INFO logger; line 20 is the `smtp:outbound-endpoint` with the fixed `port="587"`, the `smtp.*` placeholders for host, user and password, `from`, `to` and `subject` from `mail.*`, and `responseTimeout="10000"`. The original test dispatched the classpath `input.csv` to the input directory under the name `input.csv`, waited 12000 ms, read the newest INBOX message back and asserted that its content, trimmed, equals `email.txt` (`SendingACsvFileThroughEmailUsingSmtpIT.java:112-129`). That assertion is ported in `SendingACsvFileThroughEmailUsingSmtpTest` (AAP 0.5.6), not here; it is this project's fallback evidence while the scenario is uncaptured (AAP 0.8.4).
- Failure paths: a CSV whose `units` or `pricePerUnit` is not a number, or a send the server refuses, ends in Mule's default exception strategy. That is platform handling, not a branch AAP 0.7.5 lists for the example, so it is not a parity scenario; `service/CsvEmailServiceTest` (the failure propagates and no mail is sent) and SC-5 `trigger/FileInboundPollerTest` (the file is consumed after a handled failure) cover it.
- Replay context: `FixtureParityTest` runs without profile `test`, so `application-test.yml` does not apply, and `application.yml` leaves `smtp.host`, `smtp.user`, `smtp.password`, `mail.from`, `mail.to` and `mail.subject` empty, keeps `smtp.port` at the endpoint's 587 and keeps STARTTLS on and required; with an empty SMTP host the replay boundary fails the class before the application context starts. The fixture's `state.properties` therefore supplies what `application-test.yml` supplies. Only the placeholders of the Fixture contract are used; where it offers none, the literal value of `application-test.yml` is written:
  - `csv-email.input-dir`: `${dir:input}`, the replay's temporary `input` directory, the one `request.file.directory` names. It outranks the surefire system property `csv-email.input-dir` of `pom.xml` (`target/test-csv-email/input`), because the fixture properties are registered through `@DynamicPropertySource`.
  - `smtp.host`: `localhost` and `smtp.port`: `${greenmail.smtp.port}`, the replay's GreenMail SMTP server, which listens on `127.0.0.1`; `spring.mail.host` and `spring.mail.port` follow them through `application.yml`.
  - `smtp.user`: `test@localhost` and `smtp.password`: `secret`, the GreenMail user and demo password of `application-test.yml`, not a real credential. `spring.mail.properties[mail.smtp.auth]` stays `true`, so `client/CsvMailSender` authenticates as that user, whom the fixture's `state.mail` registers: `{"users": [{"email": "test@localhost", "login": "test@localhost", "password": "secret"}]}`. The address is plain: the `%40` encoding the original's endpoint URI needed (Capture step 3) is not used by `JavaMailSender`.
  - `mail.from`: `test@localhost`, `mail.to`: `test@localhost` and `mail.subject`: `Mule flow completed!`, the values of `application-test.yml` and of the original test (`SendingACsvFileThroughEmailUsingSmtpIT.java:98-100`). The capture gives the original the same three values, so the sender, recipient and subject the replay compares come from one configuration.
  - `spring.mail.properties[mail.smtp.starttls.enable]`: `false` and `spring.mail.properties[mail.smtp.starttls.required]`: `false`, the keys `application-test.yml` overrides, written in the bracket form that its YAML keys `"[mail.smtp.starttls.enable]"` and `"[mail.smtp.starttls.required]"` under `spring.mail.properties` flatten to, so they replace the `true` of `application.yml` under the same names. GreenMail's SMTP is plain text and offers no STARTTLS, so without the `required` override the send fails before authentication and the scenario's mail is never sent.
- Replay inputs: the fixture's `request.invoke` names the single-run method `{"type": "trigger.CsvFileTrigger", "method": "pollOnce"}`, which polls through the same SC-5 poller as the schedule. Replay suppresses scheduling (Replay boundary), so the scheduled `CsvFileTrigger.poll()` (`csv-email.polling-frequency`, 1000 ms) never runs and never takes the file before the invocation. `pollOnce()` reads, maps, logs, sends and deletes before it returns; the replay compares those effects, not its return count.
- Tier 2A verification points: the exact mail the original's SMTP transport sent is known only from the capture. The original test compared the content trimmed, so whether the original body ends with a line break, its `Content-Type` (the Java side sends one `text/plain; charset=UTF-8` part through `client/CsvMailSender`) and the rendering of its `From` and `To` are fixed by the fixture, and a difference is fixed in the Java code. The replay compares the part's decoded content, so a different `Content-Transfer-Encoding` fails the scenario only through a difference it brings to the content type or the decoded bytes.
- Parity label: until a fixture is captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7) in its README and in its row of the root `SUMMARY.md`. Once captured, it is `PARITY: VERIFIED — Tier 2A fixtures` when `file-input-csv-emailed` is `VERIFIED` and `PARITY: FAIL — Tier 2A fixtures` when it is `FAILED`; a scenario the capture cannot produce (Capture step 3) stays `UNVERIFIED-uncaptured` and is escalated per AAP 0.8.8, so the project is never labelled `PARITY: VERIFIED — Tier 2A fixtures` without it.

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
- Every row of this file is captured by dropping its input file into the input directory of the original deployed as Capture sets out; the original has no HTTP listener, so no `http.port` is passed.

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted. It is the port replaced inside `Location` on replay. This project has no HTTP listener, so its fixture carries no `port`.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password, keyStore, keyStoreType, keyStorePassword}` for HTTPS listeners, replayed through ST-1's TLS mode; each member is a string, and only `trustStore` is required. `type` is the store type (default `JKS`) and `password` the trust store's password. `keyStore` is a client key store, for a listener that requires a client certificate: with it the replay sends through ST-1's key-store overload `HttpTestClient.https(host, port, trustStorePath, trustStoreType, trustStorePassword, keyStorePath, keyStoreType, keyStorePassword)`, which presents the store's client key; `keyStoreType` defaults to `type`, and `keyStorePassword` is the password of the key store and of its key entries. Without `keyStore` no client certificate is presented. `keyStoreType` or `keyStorePassword` without `keyStore` fails the scenario, and a project whose ST-1 lacks the key-store overload reports a scenario with `keyStore` `UNVERIFIED-missing-driver`. `trustStore` and `keyStore` resolve from the classpath first, then as a file.
- `readTimeoutMillis`: optional; the replay's socket read budget for this request, a positive whole number of milliseconds, applied through ST-1's `HttpTestClient.withReadTimeout` to every read of the reply, the TLS handshake's included; default 10000, ST-1's `TIMEOUT_MILLIS`, which also stays the connect timeout. Like `awaitMillis`, it is a replay setting, not a captured value: a fixture sets it when the endpoint itself waits about 10000 ms or longer before it answers, so that the replay reads the captured answer instead of timing out before the comparison.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`. Each table must already exist, created when the application starts or by an earlier scenario in table order: the replay creates no table, so a seeded table that does not exist fails the scenario before any row is written.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- `clearCaches`: optional, `true` or `false`, the default. `true` stands for the restart the capture made before the scenario, after which the original's in-memory stores were empty: once the `state` is seeded, and before the prior requests and the input, the replay empties every cache of every `org.springframework.cache.CacheManager` bean of the application context. A project without such a bean reports the scenario `UNVERIFIED-missing-driver`, since the replay cannot re-create the empty stores the capture started from. Without `clearCaches`, the application's caches and its other in-memory state carry over from the scenarios before, in table order.
- `requests`: optional; the prior requests the capture sent before the scenario's own (Capture step 4 of the reference format), in the order sent, each an object of the `request` shape for `http` and `soap` above (`method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `portProperty`, `tls`, `readTimeoutMillis`). The replay sends them through ST-1 after the `state` is seeded, its caches cleared and the `upstream` stubs installed, and before the input. Their replies are discarded, as the capture discarded them, whatever their status; one that cannot be sent, or whose reply cannot be read, fails the scenario. Everything else they cause belongs to the scenario: their outbound calls are recorded in `upstream`, in call order, and the effects read back after the run (files, messages, rows, documents, mails) include theirs. Only their log events are left out: the replay drops the events logged before the input, as `response.logs` holds the events from the input on.
- For this project, `state` holds `properties` and `mail`, the one GreenMail user `test@localhost`, as Notes for this project set them out; the input file itself is placed by `request.file` into an input directory the reset leaves empty, so the fixture needs no `state.directories`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format; this project starts no recording proxy): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of the one scenario of this project: the flow makes no HTTP or SOAP call, and its SMTP server is the GreenMail mailbox read back into `response.mails`, not an upstream. Its fixture with `"upstream": []` is therefore complete, never `UNVERIFIED-no-upstream`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 of the reference format defines. This project's fixture records no HTTP response.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and then every 100 ms until it has held exactly them throughout a 500 ms quiet window.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match, then compares it every 100 ms until it has matched throughout a 500 ms quiet window, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a port the replay owns, the same one for every use of the same name and distinct from the port of every other name. The embedded servers bind a port themselves and publish it under their names, `amqp` (Qpid Broker-J), `ldap`, `greenmail.<proto>` and `mongo`, so `${freePort:amqp}` is the broker's AMQP port. Any other name, or one of these whose server's library the project lacks, gets a port the replay reserves until the class ends with an unconnected socket on the wildcard address. Until the application context creates its embedded web server, or, in a context without one, until just before the web server start phase, that socket is bound without `SO_REUSEADDR`, so no other process and no other name binds the port, with `SO_REUSEADDR` or without it, and a connection to it is refused. At that handover, before any listener of the server is probed or bound, the replay swaps it for a socket bound with `SO_REUSEADDR` on the same port and keeps that shared socket until the context's refresh ends, so the application's listeners, which set `SO_REUSEADDR` as Undertow does, bind beside it, and so does a start-up check that binds a listener's port to see that it is free. Only between this handover and the end of the context's refresh can another socket bind the port as well: one that sets `SO_REUSEADDR` throughout, and any socket in the instant each swap takes. Once the context has refreshed, a port nothing listens on is held without `SO_REUSEADDR` again, so it stays a refused connection and a listener that binds only later cannot bind it, and a port a listener bound is left to that listener, which keeps every other socket off it; a name first used after that is held without `SO_REUSEADDR` at once. A host whose sockets do not let such a listener bind beside a shared socket gets each reservation released, not shared, at the handover, with a warning, so another process can take a port until the application's listener has bound it; once the context has refreshed, each port nothing listens on is held without `SO_REUSEADDR` again, and the ports stay distinct there too.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`: the port GreenMail's server for that protocol bound.
- `${mongo.uri}` and `${ldap.port}`: the address of the embedded MongoDB server and the port the embedded LDAP server bound.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset empties each table of `state.tables` and `response.tables` that exists in the replay database; a table that does not exist yet, such as one the scenario's own request creates, is left absent and is never created, and no table is dropped. The reset clears no application cache and no other in-memory state of the application, so scenarios that build on one another in table order keep doing so; only a fixture's `state.clearCaches` empties the application's caches, once its `state` is seeded. Its `state.requests` are sent after that, and its input last. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

Skeleton of this project's fixture. Every `<captured>` value comes from the capture; the `request.invoke` target and the `state` values are those Capture step 8 sets:

```json
{
  "request":  { "file": { "directory": "input", "name": "input.csv", "body": "<captured>" },
                "invoke": { "type": "trigger.CsvFileTrigger", "method": "pollOnce" } },
  "state":    { "properties": { "csv-email.input-dir": "${dir:input}",
                                "smtp.host": "localhost", "smtp.port": "${greenmail.smtp.port}",
                                "smtp.user": "test@localhost", "smtp.password": "secret",
                                "mail.from": "test@localhost", "mail.to": "test@localhost",
                                "mail.subject": "Mule flow completed!",
                                "spring.mail.properties[mail.smtp.starttls.enable]": "false",
                                "spring.mail.properties[mail.smtp.starttls.required]": "false" },
                "mail": { "users": [ { "email": "test@localhost", "login": "test@localhost",
                                       "password": "secret" } ] } },
  "upstream": [],
  "response": { "directories": { "input": "<captured>" },
                "mails": [ { "mimeBase64": "<captured>" } ] }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `file` trigger, the trigger of this project: the SC-5 file disposition (the exact final file set of `input`, so a deleted input is `[]`) and the mails GreenMail received during the run (`response.mails`) are compared, under the rule of the row's state. A mail is compared by its `From`, `To`, `Cc` and `Subject`, by each leaf part's content type, without any `boundary` parameter, and file name, and then by each part's decoded content bytes; `Date`, `Message-ID` and `Received` are not compared.
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.

The one row of this project is `deterministic`, so the input directory, the mail's fields above and its decoded body are compared byte for byte, with no normalization.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then holds `verified=0`, `unverified=1` and `failed=0`, followed by `scenario.file-input-csv-emailed=UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured. This is the current label of this project.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of the scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/file-input-csv-emailed/`, outside `fixtures/` and off the test classpath: `input/input.csv` holds the byte copy of the file dropped, `input-after.txt` the listing of the input directory afterwards, `mail-<n>.eml` every mail the local SMTP server received during the run, as fetched, and `mule.log` the application log of the run. Working files stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/sending-a-csv-file-through-email-using-smtp-java/`). The trigger is a directory, not an HTTP listener, so the reference format's `curl` capture is replaced by the file and mail procedure below (AAP 0.8.4); the flow makes no HTTP or SOAP outbound call, so no WireMock recording proxy is started.

1. Build the unmodified original `sending-a-csv-file-through-email-using-smtp` with `mvn -B package`, with the EE repository credentials in `settings.xml`, and deploy the archive to a licensed Mule 3.8 EE runtime with the runtime properties of step 3 (`-M-D<key>=<value>`). Never edit the original folder. The original has no HTTP listener and reads no `http.port`.
2. Point the original at a local GreenMail server that stands in for Gmail, with the user `test@localhost`: GreenMail standalone 2.1.14 (`com.icegreen:greenmail-standalone`, the release of the `greenmail-junit5` the project pins) on `127.0.0.1`, serving SMTP for the flow and IMAP for the readback of step 6:

   ```sh
   mkdir -p target/fixture-capture
   mvn -B -q dependency:get -Dartifact=com.icegreen:greenmail-standalone:2.1.14
   java -Dgreenmail.hostname=127.0.0.1 -Dgreenmail.smtp.port=587 -Dgreenmail.imap.port=3143 -Dgreenmail.api.port=3080 -Dgreenmail.users=test:secret@localhost -Dgreenmail.users.login=email -jar "$HOME/.m2/repository/com/icegreen/greenmail-standalone/2.1.14/greenmail-standalone-2.1.14.jar" > target/fixture-capture/greenmail.log 2>&1 &
   GM=$!
   ```

   `kill "$GM"` stops GreenMail after step 6.
   - The endpoint hard-codes `port="587"` (`csv-to-smtp.xml:20`), so GreenMail SMTP must be reachable on 587: bind it there, which needs the privilege to open a port below 1024, or bind it to a free port and redirect local port 587 to that port.
   - `-Dgreenmail.users.login=email` makes the full address the login, as `state.mail` registers it, and `-Dgreenmail.api.port` moves GreenMail's REST API off its default port 8080.
3. Set the placeholders of the endpoint (`csv-to-smtp.xml:20`) as runtime system properties, as the original test set them with `System.setProperty` (`SendingACsvFileThroughEmailUsingSmtpIT.java:95-100`), each passed as `-M-D<key>=<value>`, with the subject quoted for the shell:
   - `smtp.host`: `localhost`;
   - `smtp.user`: `test%40localhost`, URL-encoded, `@` written `%40`, because the Mule SMTP endpoint builds its address URI from `user`: the test passed `smtp.user_enc` as `smtp.user` (line 96), and the original README shows the encoded form (`sending-a-csv-file-through-email-using-smtp/README.md:27`);
   - `smtp.password`: `secret`, the GreenMail demo value of step 2;
   - `mail.from` and `mail.to`: `test@localhost`;
   - `mail.subject`: `Mule flow completed!`.

   The mail is addressed to the plain user, and the fixture records the plain form (`state.properties`, `state.mail.users[].email`).
   - TLS: the original's Gmail connector (`csv-to-smtp.xml:3`) turns on STARTTLS and SMTP authentication, and GreenMail 2.1.14's SMTP offers no STARTTLS. Before capturing, confirm in the Mule log that GreenMail accepts the send without a TLS or authentication error. If the connector insists on STARTTLS, use a local SMTP server that offers it instead, with its certificate trusted through the runtime's JVM trust-store options (`-M-Djavax.net.ssl.trustStore=<file>` and its type and secret, never application properties), record which server in `DECISIONS.md`, and read its mailbox back as step 6 does. If no local server completes the send, the scenario gets no fixture, stays `UNVERIFIED-uncaptured` and is escalated per AAP 0.8.8.
4. Seed the state before the scenario: restart GreenMail, so the mailbox of `test@localhost` starts empty, and make sure the input directory `src/main/resources/input` (`csv-to-smtp.xml:5`), relative to the runtime's working directory `R`, exists and is empty. Record that state as the Fixture contract defines it: the mailbox in `state.mail.users`; the input file itself goes into `request.file`.
5. Drop the input: a byte copy of `sending-a-csv-file-through-email-using-smtp/src/main/resources/input.csv` (byte-identical to this project's `src/main/resources/input.csv`), the file the original test dispatched under the name `input.csv` (`SendingACsvFileThroughEmailUsingSmtpIT.java:112-119`) and the original README tells users to paste into the input folder (`sending-a-csv-file-through-email-using-smtp/README.md:18`). It is written beside the input directory and then moved in, so the poll never reads a partly written file. The flow polls every 1000 ms, so the capture waits at least one polling interval plus the send; the original test waits 12000 ms (line 121):

   ```sh
   S=file-input-csv-emailed; D="src/test/capture/$S"; R=<runtime working directory>
   mkdir -p "$D/input" target/fixture-capture
   cp ../../sending-a-csv-file-through-email-using-smtp/src/main/resources/input.csv "$D/input/input.csv"
   cp "$D/input/input.csv" "$R/src/main/resources/input.csv.part"
   mv "$R/src/main/resources/input.csv.part" "$R/src/main/resources/input/input.csv"
   sleep 12
   ```

6. Read back, once the wait has passed:
   - the input directory: `ls -A "$R/src/main/resources/input" > "$D/input-after.txt"`, the final file set SC-5 must reproduce;
   - the log: the run's application log is kept unedited as `$D/mule.log`. Its events are evidence only: the fixture carries no `response.logs`, so the replay does not compare the INFO event of line 19, which `service/CsvEmailServiceTest` covers;
   - the mails: this command writes each mail GreenMail received unedited as the evidence `$D/mail-<n>.eml`, prints the `response.mails` array with the `Received`, `Message-ID` and `Date` header fields (and their continuation lines) left out and every other byte kept, and deletes what it read:

     ```sh
     python3 - "$S" > "target/fixture-capture/$S-mails.json" <<'PY'
     import base64, imaplib, json, pathlib, sys
     evidence = pathlib.Path('src/test/capture', sys.argv[1])
     evidence.mkdir(parents=True, exist_ok=True)
     NOT_CAPTURED = {b'received', b'message-id', b'date'}
     imap = imaplib.IMAP4('127.0.0.1', 3143)
     imap.login('test@localhost', 'secret')
     imap.select('INBOX')
     mails = []
     for n, number in enumerate(imap.search(None, 'ALL')[1][0].split(), 1):
         raw = imap.fetch(number, '(BODY.PEEK[])')[1][0][1]
         (evidence / f'mail-{n}.eml').write_bytes(raw)
         head_end = raw.index(b'\r\n\r\n') + 2
         kept, dropped = [], False
         for field_line in raw[:head_end].splitlines(keepends=True):
             if field_line[:1] not in (b' ', b'\t'):
                 dropped = field_line.split(b':', 1)[0].strip().lower() in NOT_CAPTURED
             if not dropped:
                 kept.append(field_line)
         mails.append({'mimeBase64': base64.b64encode(b''.join(kept) + raw[head_end:]).decode('ascii')})
         imap.store(number, '+FLAGS', '\\Deleted')
     imap.expunge()
     imap.logout()
     print(json.dumps(mails, indent=2))
     PY
     ```

     The original test read the mailbox of its own account, the one it sent from and to; the readback above reads the same mailbox, `test@localhost`.
7. `upstream` is `[]`: the original made no outbound HTTP or SOAP call, and its SMTP server is the local GreenMail server read back in step 6, not an upstream.
8. Write `fixtures/file-input-csv-emailed.json` raw, without editing:
   - `request.file` is `{"directory": "input", "name": "input.csv", "body" or "bodyBase64": <the bytes of evidence input/input.csv>}`, and `request.invoke` is `{"type": "trigger.CsvFileTrigger", "method": "pollOnce"}`.
   - `state` is `{"properties": {<the replay-context entries of Notes for this project>}, "mail": {"users": [{"email": "test@localhost", "login": "test@localhost", "password": "secret"}]}}`.
   - `"upstream": []`.
   - `response.directories.input` holds one `{name, body or bodyBase64}` entry per file `input-after.txt` lists, with its bytes as found, and is `[]` when it lists none; `response.mails` is the array of `target/fixture-capture/$S-mails.json` (step 6).
   - Server-assigned and generated values are not captured: the `Received`, `Message-ID` and `Date` header fields, the GreenMail ports and the timestamps of the log.
   - The fixture names no live host: GreenMail and its user are local, and the replay supplies its own ports.
   - Where the captured MIME differs from what the row or the notes above describe, the captured MIME governs.
   - Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

Replay of this fixture by `FixtureParityTest`'s `file` driver (AAP 0.8.4, File) contacts no live mail server:

1. It registers `state.properties` before the application context starts, which runs without profile `test` and without a web server (`spring.main.web-application-type: none`), with scheduling suppressed.
2. It starts GreenMail on `127.0.0.1` with each protocol on its `${greenmail.<proto>.port}` and registers the user of `state.mail.users`, the login `client/CsvMailSender` authenticates with.
3. It writes `request.file` into `${dir:input}`, the directory `csv-email.input-dir` names, and calls `CsvFileTrigger.pollOnce()` through `request.invoke`.
4. It compares `response.directories` with the input directory and `response.mails` with every mail GreenMail received, waiting up to `awaitMillis` for each to match.
5. The row is `deterministic`, so every comparison is byte for byte, with no normalization.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `file-input-csv-emailed`, that means `client/CsvMailSender` for the mail's sender, recipient, subject, content type and body encoding, `mapper/OrderLinesMapper` or SC-4 `mapper/DwCsvReader` for the rendered order lines, and SC-5 `trigger/FileInboundPoller` for the file disposition. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
