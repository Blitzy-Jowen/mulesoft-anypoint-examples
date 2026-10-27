# Parity scenarios — jms-message-rollback-and-redelivery-java

Scenarios for the fixture replay (ST-2) of `jms-message-rollback-and-redelivery-java`. The original example is `jms-message-rollback-and-redelivery` (folder `jms-message-rollback-and-redelivery/`). Its single flow `JMSRedeliver` consumes queue `in` in a transaction it begins (`ALWAYS_BEGIN`); every delivery whose `JMSXDeliveryCount` is not 5 throws `MyException`, which its rollback exception strategy rolls back for redelivery, and the fifth delivery publishes the message to topic `topic1` in the same transaction (`ALWAYS_JOIN`); a failure not caused by `MyException` reaches its catch exception strategy, which commits. The three branches listed for the example in AAP 0.7.3 are covered by the two scenarios below: one message passes through the rollback branch and then the `JMSXDeliveryCount == 5` branch, and the catch branch needs the publish of the fifth delivery to fail. Each scenario's captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.3.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `jms-in-redelivered-until-fifth-delivery` | `jms` | `deterministic` | JMS `TextMessage` `Message123` → queue `in`, no application properties; `trigger/InQueueListener` consumes it, so no single-run method is invoked | subscriber on `topic1` created before the send; `in` empty | deliveries 1–4 take the rollback branch: `MyException` is thrown and the INFO log reads `Entered rollback exception strategy. The message rolls back to its original state for reprocessing.`; redelivery comes 5000 ms after the first failure, then 2000 ms apart; delivery 5 (`JMSXDeliveryCount == 5`) logs `Transaction without errors`, publishes to `topic1` in the same transaction and commits. Compared: the `topic1` message, the log events and the `JMSXDeliveryCount` sequence | `jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:21-48`, `jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:6-12`, `jms-message-rollback-and-redelivery/src/test/java/org/mule/examples/JmsMessageRollbackAndRedeliveryIT.java:21,40-43` |
| `jms-in-publish-failure-commits` | `jms` | `deterministic` | JMS `TextMessage` `Message123` → queue `in`, no application properties; `trigger/InQueueListener` consumes it, so no single-run method is invoked | the broker refuses producer sends to `topic1`, for example an ActiveMQ `authorizationPlugin` denying write on `topic1` while `in` stays readable; subscriber on `topic1` created before the send; `in` empty | deliveries 1–4 roll back as in `jms-in-redelivered-until-fifth-delivery`; on delivery 5 the `ALWAYS_JOIN` publish fails with an exception not caused by `MyException`, and the catch branch logs `Entered catch exception strategy. The transaction is commited.` (the original's spelling) and commits, so nothing reaches `topic1` and `in` ends empty; if no broker configured this way can be provided, the scenario stays `UNVERIFIED-uncaptured`, and the captured outcome governs over this description. Compared: the `topic1` destination, the browse of `in`, the log events and the `JMSXDeliveryCount` sequence | `jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:35-42` |

No scenario of this project is `unreachable` or `nondeterministic`: the flow has no unreachable branch, and README step 8 (removing `maxRedelivery`, `jms-message-rollback-and-redelivery/README.md:43`) is a configuration variant of the original rather than a scenario of its shipped configuration (`maxRedelivery="5"`, `jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:19`), so it has no row.

The delays in `Branch` are the original's redelivery policy (`jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:6-12`), named to describe the branch; no fixture records or compares delivery timing, which `JmsMessageRollbackAndRedeliveryTest` measures (AAP 0.7.3).

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
- Every row of this file is captured by sending its message to queue `in` of the local ActiveMQ broker the deployed original consumes from (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `jms` and `mq`, the trigger of both rows of this project:

- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
  - For this project: `destination` `in`, `pubSub` `false`, the text of the `TextMessage` as `body`, and as `properties` the application properties the sender set, `{}` for the message of Capture step 5.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
  - For this project every fixture sets `awaitMillis` to `30000`, the original test's receive timeout (`jms-message-rollback-and-redelivery/src/test/java/org/mule/examples/JmsMessageRollbackAndRedeliveryIT.java:41`): the five deliveries of `jms-in-redelivered-until-fifth-delivery` span at least 11000 ms (5000 + 3 × 2000), so the default would end the wait first. It and the `state` override below are the only values of these fixtures that the capture does not produce.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
  - Not used here: `trigger/InQueueListener` consumes the sent message itself.

`request`, for the other drivers, which no row of this project uses: `method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `port`, `portProperty` and `tls` for `http` and `soap`; `file` for `file` and `scheduler`; `mail` for `mail`; each as the reference format defines it.

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
- For this project, both fixtures carry `"properties": {"spring.artemis.broker-url": "vm://0"}` in `state`, and `jms-in-publish-failure-commits` also carries `"jms": {"denySend": [{"destination": "topic1", "pubSub": true}]}`, the refusal Capture seeds for it:
  - The queue, topic and redelivery keys need no `state.properties` override, because the converted defaults (`jms.inbound-queue` `in`, `jms.outbound-topic` `topic1`, `redelivery.*` 5000/2000/5) equal the original's values (`jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:6-12,22,35`).
  - `spring.artemis.broker-url` needs the in-memory override all the same. With `artemis-jakarta-server` on the test classpath and no `spring.artemis.mode` set, Artemis runs embedded and the application never connects to that URL, but the replay boundary below checks every configured key, and `tcp://localhost:61616` from `src/main/resources/application.yml` names a port no replay server owns. `vm://0` is an in-JVM address the replay owns. The key is not listed in `notContacted`, because the original did connect to that broker.
  - The `topic1` subscriber and the empty `in` of `Seeded state` need no key: before the send the replay subscribes to every topic of `response.destinations`, so the `topic1` subscriber exists before the message is sent, as in Capture step 4, and drains every queue listed there, `in` included, which the reset that ends every scenario drains again.
  - The refusal of sends to `topic1` in `jms-in-publish-failure-commits` is `state.jms.denySend`: from before the send until the reset, the embedded Artemis server refuses every send to `topic1`, the publish of delivery 5 included, while `in` stays readable, and the `topic1` subscriber exists because `response.destinations` lists `topic1`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project: its flow makes no HTTP or SOAP call, and its publish to `topic1` is a broker effect recorded under `response.messages`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. No fixture of this project omits it, because its flow makes no exchange to record.

`response`, the captured result:

- `status`, `reason`, `headers` and `body` or `bodyBase64` hold the HTTP response of `http` and `soap` rows, as the reference format defines them. The `jms` rows of this project have no HTTP response and carry none of them.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and again 500 ms later.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.
- For this project, `response` holds `messages`, `destinations` and `logs`:
  - `messages`: first the `topic1` messages the subscriber of Capture step 4 received, each with `destination` `topic1`, `pubSub` `true`, the message text as `body` for a `TextMessage` or its bytes as `bodyBase64` for a `BytesMessage`, and its application properties as `properties`; then the browse of queue `in` (Capture step 6), one entry with `destination` `in` and `pubSub` `false` per message still on `in`, in browse order. A destination that held no message contributes no entry, and the replay checks that it stayed empty.
  - `destinations`: `[{"destination": "topic1", "pubSub": true}, {"destination": "in", "pubSub": false}]` in every fixture, the topic the subscriber of Capture step 4 listened on and the queue Capture step 6 browsed, whatever they held.
  - `logs`: the INFO events of the `logger` elements of flow `JMSRedeliver`, in order, each with the category it was logged under as `logger`, its level and its message text. The `JMSXDeliveryCount` sequence has no key of its own: it is the order of these per-delivery events (Capture step 7).

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
- For this project the replay's broker is the in-VM Artemis server of `artemis-jakarta-server` (test scope in the project `pom.xml`), which the JMS driver reaches through the application's connection factory; with the `state` override above the boundary finds no other target, because the project has no HTTP upstream, DataSource, mail, MongoDB or LDAP client.

Skeleton of a fixture of this project; every `request` and `response` value marked `<captured>` comes from the capture, and `state` and `awaitMillis` are written as the sections above define:

```json
{
  "request":  { "message": { "destination": "in", "pubSub": false,
                             "body": "<captured>", "properties": {} },
                "awaitMillis": 30000 },
  "state":    { "properties": { "spring.artemis.broker-url": "vm://0" } },
  "upstream": [],
  "response": { "messages": [{ "destination": "topic1", "pubSub": true,
                               "body": "<captured>", "properties": { "<captured>": "<captured>" } }],
                "destinations": [{ "destination": "topic1", "pubSub": true },
                                 { "destination": "in", "pubSub": false }],
                "logs": [{ "logger": "<captured>", "level": "<captured>", "message": "<captured>" }] }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by key names in order, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name, with digit runs in unmatched names replaced by `#`; logs compare logger and level in order and only whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.
- Both rows of this project are `deterministic`: the `topic1` message bodies and listed properties and every `logs` entry are compared exactly. Neither row has an HTTP response.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `jms-in-redelivered-until-fifth-delivery` and `jms-in-publish-failure-commits` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The current label of this project is `PARITY: UNVERIFIED — Mule runtime unavailable`, the label its README and the root `SUMMARY.md` carry.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `topic1.json` holds every message the subscriber of step 4 received, with its body and all its properties; `in-browse.json` the messages the browse of step 6 found on `in`; and `mule.log` the runtime log of the deployment from the send to the end of the run, which the INFO events are read from. Working files stay under `target/fixture-capture/` and are never committed.

1. Build the unmodified original `jms-message-rollback-and-redelivery` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. Start a local ActiveMQ 5.x broker, such as the 5.10.0 the original README names (`jms-message-rollback-and-redelivery/README.md:27-31`), on `tcp://localhost:61616`. This is the address the original reaches through `failover:(tcp://localhost:61616)` (`jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:13-16`), with the `RedeliveryPolicy` of `jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:6-12`: `maximumRedeliveries` 5, `initialRedeliveryDelay` 5000, `redeliveryDelay` 2000, no exponential back-off. That policy belongs to the original's connection factory, so the broker needs no redelivery setting of its own.
3. Deploy the archive to a licensed Mule 3.8 EE runtime. No runtime property is passed: the flow reads no placeholder, and `jms-message-rollback-and-redelivery/src/main/app/mule-app.properties` is empty.
4. Create a subscriber on topic `topic1` before sending, because the topic is non-durable: a message published while no subscriber exists is not kept for a later one.
5. Send one `TextMessage` with body `Message123` and no application property to queue `in`. This is the original test's message (`jms-message-rollback-and-redelivery/src/test/java/org/mule/examples/JmsMessageRollbackAndRedeliveryIT.java:21,40`).
6. After the run, which ends when the subscriber of step 4 has received a message or 30000 ms after the send, the original test's receive timeout (`jms-message-rollback-and-redelivery/src/test/java/org/mule/examples/JmsMessageRollbackAndRedeliveryIT.java:41`), record into `response`, raw and unedited:
   - the `topic1` message: body and application properties;
   - a browse of queue `in` (browse, not consume);
   - the destinations of steps 4 and 6 as `destinations`, `topic1` with `pubSub` `true` and then `in` with `pubSub` `false`, whether or not they held a message;
   - the INFO events of the `logger` elements of flow `JMSRedeliver`, in order, as level and message text, each with the logger category it was logged under. Only these events are compared.
7. Read the `JMSXDeliveryCount` sequence from the order of the per-delivery log events.

Broker message IDs, JMS timestamps and log timestamps are not captured (AAP 0.8.4). Per-run identifiers that the Mule runtime rather than the flow attaches to a message, such as `MULE_ROOT_MESSAGE_ID` or a serialized `MULE_SESSION`, are treated the same way. Every other property is captured exactly as it appears; a mismatch that results is a Tier 2A finding handled under the integrity rule.

For `jms-in-publish-failure-commits`, seed the refusal before step 5, for example an ActiveMQ `authorizationPlugin` that denies write on `topic1` while `in` stays readable. The rule set must still let the capture send to `in`, the original consume from it and the subscriber of step 4 read `topic1`. The fixture records that refusal as `state.jms`, `{"denySend": [{"destination": "topic1", "pubSub": true}]}`.

Write `fixtures/<scenario>.json` from `request` (the message of step 5, with `awaitMillis`), `state` (the override of the Fixture contract, with the refusal above for `jms-in-publish-failure-commits`), `upstream` (`[]`) and `response` (steps 6 and 7), every value as produced. Captured values are never adjusted afterwards, to match the Java output or for any other reason.

Replay: `FixtureParityTest`'s `jms` driver runs against the embedded Artemis broker (`artemis-jakarta-server`, test scope in the project `pom.xml`), seeded from `state`. It never contacts a live broker.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for both scenarios that means `trigger/InQueueListener`, `trigger/RedeliveryDelay`, `service/RedeliveryService`, `client/TopicPublisher` and `config/JmsConfig`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
