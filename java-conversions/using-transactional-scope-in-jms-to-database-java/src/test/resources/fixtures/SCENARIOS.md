# Parity scenarios — using-transactional-scope-in-jms-to-database-java

Scenarios for the fixture replay (ST-2) of `using-transactional-scope-in-jms-to-database-java`. The original example is `using-transactional-scope-in-jms-to-database` (folder `using-transactional-scope-in-jms-to-database/`). Its single flow `transactionsFlow1` consumes queue `in` through connector `Active_MQ` (`maxRedelivery="2"`), converts the XML message to an `Order`, logs it, and then, in a transactional scope it begins (`ALWAYS_BEGIN`), inserts the order into table `orders`, calls `TestComponent`, which always throws `MyException("exception customized")`, and would send to queue `out` (`JOIN_IF_POSSIBLE`). The exception rolls the insert back, `out` is never reached, and the message is redelivered until the connector's `maxRedelivery` is exceeded. The branch listed for the example in AAP 0.7.3 is the first scenario below; the second is the flow's other reachable path, a message the transformer cannot convert, which fails before the transactional scope. Each scenario's captured fixture is `fixtures/<scenario>.json`. No fixture exists until Tier 2A capture, so the project's label is `PARITY: UNVERIFIED — Mule runtime unavailable`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.3.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `jms-in-order-rolled-back` | `jms` | `deterministic` | JMS `TextMessage` → queue `in`, body = the bytes of `message.xml` (`src/test/resources`, 90 bytes: a tab-indented `order` element with `itemId` 1, `itemUnits` 2 and `customerId` 1, no trailing newline), no application properties; sent the way the original test sent it (Capture step 5); `trigger/OrderQueueListener` consumes it, so no single-run method is invoked | table `orders` empty, as `order.sql` creates it; queues `in` and `out` empty | the order is converted and logged by the flow's `logger` element; the transactional scope inserts it into `orders`, then `TestComponent` throws `MyException("exception customized")`, so the insert rolls back and the send to `out` is never reached; the message is redelivered until its redelivery count exceeds the `maxRedelivery` 2 of connector `Active_MQ`, which ends it with the redelivery-exceeded ERROR on endpoint `jms://in`. Compared: the `orders` rows, the browse of `in` and `out` and the INFO events of the flow's `logger` element | `using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:3`, `using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:7-21`, `using-transactional-scope-in-jms-to-database/src/test/java/org/mule/examples/UsingTransactionalScopeInJMStoDatabaseIT.java:41-50`, `using-transactional-scope-in-jms-to-database/README.md:28-32` |
| `jms-in-malformed-order-xml` | `jms` | `deterministic` | JMS `TextMessage` → queue `in`, body `<order><itemId>1</itemId>` (25 bytes: an `order` element left without its end tag), no application properties; sent the way the original test sent it (Capture step 5); `trigger/OrderQueueListener` consumes it, so no single-run method is invoked | table `orders` empty, as `order.sql` creates it; queues `in` and `out` empty | the document is not well-formed, so `xml-to-object-transformer` fails before the flow's `logger` element and the transactional scope: no order is logged or inserted and nothing is sent to `out`; how connector `Active_MQ` then handles the failed delivery against its `maxRedelivery` 2 is recorded by the capture, and the captured outcome governs over this description. Compared: the `orders` rows, the browse of `in` and `out` and the INFO events of the flow's `logger` element | `using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:8-10`, `using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:3` |

No scenario of this project is `unreachable` or `nondeterministic`: the flow embeds no time or random value and has no choice whose branch no input selects. The send to `out` (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:18-20`) is not a branch of its own: `TestComponent` throws on every call (`using-transactional-scope-in-jms-to-database/src/main/java/org/mule/examples/TestComponent.java:18-22`), so both scenarios end before it.

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
- Every row of this file is captured by sending its message to queue `in` of the in-VM ActiveMQ broker of connector `Active_MQ`, from inside the JVM of the Mule runtime that runs the original (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `jms` and `mq`, the trigger of both rows of this project:

- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
  - For this project: `destination` `in`, `pubSub` `false`, the text of the `TextMessage` as `body`, and as `properties` the application properties of the sent message other than the runtime-attached ones the Capture section leaves out; the messages of Capture step 5 set none, so this is `{}`.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
  - For this project every fixture keeps the default. It bounds how long the replay polls for the `tables` and `logs` effects and is not a compared value.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
  - Not used here: `trigger/OrderQueueListener` consumes the sent message itself.

`request`, for the other drivers, which no row of this project uses: `method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `port`, `portProperty` and `tls` for `http` and `soap`; `file` for `file` and `scheduler`; `mail` for `mail`; each as the reference format defines it.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource}`, the classpath Qpid Broker-J configuration that declares the broker objects and messages.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For this project, both fixtures carry the same `state`, `{"properties": {"spring.artemis.broker-url": "vm://0"}, "tables": {"orders": []}}`:
  - `tables.orders` is the seed of Capture step 2: table `orders` exists and holds no row. The replay empties it before the run and inserts nothing. It may seed and read the table because the test profile's `jdbc.url` (`src/test/resources/application-test.yml`) is an in-memory H2 database in MySQL mode, created from the same `order.sql`, which the table-state rule below accepts.
  - `spring.artemis.broker-url` needs the in-memory override. The test profile runs Artemis embedded, so the application never connects to that URL, but the replay boundary below checks every configured key, and `tcp://localhost:61616` from `src/main/resources/application.yml` names a port no replay server owns. `vm://0` is an in-JVM address the replay owns. The key is overridden rather than listed in `notContacted`, because it names the broker the flow consumes from, and the original did connect to its own broker.
  - The queue and redelivery keys need no `state.properties` override, because the converted defaults (`jms.inbound-queue` `in`, `jms.outbound-queue` `out`, `jms.max-redelivery` 2) equal the original's values (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:3,7,18`).
  - The empty queues of `Seeded state` need no key: the test profile declares `in` and `out` on a non-persistent embedded broker, a consumer the replay opens for a queue that `response.messages` names drains that queue before the send, and the reset that ends every scenario drains `in`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project: its flow makes no HTTP or SOAP call, and its insert into `orders` and its JMS endpoints are store and broker effects recorded under `response.tables` and `response.messages`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. No fixture of this project omits it, because its flow makes no exchange to record.

`response`, the captured result:

- `status`, `reason`, `headers` and `body` or `bodyBase64` hold the HTTP response of `http` and `soap` rows, as the reference format defines them. The `jms` rows of this project have no HTTP response and carry none of them.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, in arrival order; for `amqp`, `destination` is the queue. Only the listed properties are compared, and no further message may follow.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the sent or received mails as raw MIME.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.
- For this project, `response` holds `tables`, `messages` and `logs`:
  - `tables`: `orders`, the rows of table `orders` after the run as Capture step 6 reads them with `SELECT * FROM orders ORDER BY id`, each row with its columns `item_id`, `item_units` and `customer_id`; the `id` column is not captured (see Capture). The replay reads the table the same way, every column ordered by the first, `id`, and compares the captured columns only.
  - `messages`: the browse of queue `in` and then of queue `out` (Capture step 6), one entry per message still on the queue, in browse order, each with `destination` the queue name, `pubSub` `false`, the message text as `body` for a `TextMessage` or its bytes as `bodyBase64` for a `BytesMessage`, and its captured application properties as `properties`. A queue that held no message contributes no entry, and the replay checks a destination only through its entries, so an empty `in` or `out` rests on the capture evidence.
  - `logs`: the INFO events of the flow's `logger` element (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:11`, message `XXXXXXX order: #[payload]`, where the payload is the converted `Order`, whose `toString()` renders `Order [item_id=…, item_units=…, customer_id=…]`), in order, one per delivery that reached it, each with the category it was logged under as `logger`, its level and its message text. The number of deliveries the redelivery limit let through is compared through these events.
  - The redelivery-exceeded ERROR (connector `Active_MQ`, endpoint `jms://in`, `maxRedelivery` 2; `using-transactional-scope-in-jms-to-database/README.md:28-30`) and the runtime's exception reports are kept in the evidence `mule.log` (Capture step 6) and are not `logs` entries: the ERROR's text embeds the broker message ID, which is never captured (see Capture), and a `deterministic` row's `logs` message is compared whole and exactly.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a free port, the same one for every use of the same name.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`.
- `${mongo.uri}` and `${ldap.port}`.

Any other placeholder fails the class with a message naming the placeholder and the fixture. No fixture of this project uses a placeholder.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written.
- For this project the replay's broker is the in-VM Artemis server of `artemis-jakarta-server` (test scope in the project `pom.xml`), which the test profile starts embedded with queues `in` and `out` and the JMS driver reaches through the application's connection factory. Its DataSource is the in-memory H2 database of `jdbc.url` in `src/test/resources/application-test.yml`, which the table-state rule accepts. With the `state` override above the boundary finds no other target, because the project has no HTTP upstream, mail, MongoDB or LDAP client.

Skeleton of a fixture of this project; every `request` and `response` value marked `<captured>` comes from the capture, and `state` is written as the sections above define:

```json
{
  "request":  { "message": { "destination": "in", "pubSub": false,
                             "body": "<captured>", "properties": {} } },
  "state":    { "properties": { "spring.artemis.broker-url": "vm://0" },
                "tables": { "orders": [] } },
  "upstream": [],
  "response": { "tables": { "orders": ["<captured>"] },
                "messages": ["<captured>"],
                "logs": [{ "logger": "<captured>", "level": "<captured>", "message": "<captured>" }] }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by key names in order, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name, with digit runs in unmatched names replaced by `#`; logs compare logger and level in order and only whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.
- Both rows of this project are `deterministic`: the captured columns of the `orders` rows, the bodies and listed properties of the browsed messages and every `logs` entry are compared exactly, with no normalization. Neither row has an HTTP response, and this project has no `nondeterministic` row.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `jms-in-order-rolled-back` and `jms-in-malformed-order-xml` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The current label of this project is `PARITY: UNVERIFIED — Mule runtime unavailable`, the label its README and the root `SUMMARY.md` carry.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `orders.json` holds the rows step 6 read from `orders`, with every column; `in-browse.json` and `out-browse.json` the messages the browses of step 6 found on `in` and `out`, with their bodies and all their properties; and `mule.log` the runtime log of the deployment from the send to the end of the run, which the INFO events are read from and which keeps the redelivery-exceeded ERROR. Working files that can hold live values, the database credentials among them, stay under `target/fixture-capture/` and are never committed.

1. Build the unmodified original `using-transactional-scope-in-jms-to-database` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. Create a fresh, empty database on a local MySQL server and run the unchanged `using-transactional-scope-in-jms-to-database/src/main/resources/order.sql` in it, which creates table `orders` with no row (the original README calls the script `import.sql`, `using-transactional-scope-in-jms-to-database/README.md:16`). The database user and password are supplied at capture time and are never written into this file, a fixture or the evidence.
3. Run the archive on a licensed Mule 3.8 EE runtime and pass `jdbc.url`, the JDBC URL of that database, as a runtime property (`-M-Djdbc.url=<url>`), because `using-transactional-scope-in-jms-to-database/src/main/app/mule-app.properties` defines no value and the flow's database configuration reads `${jdbc.url}` (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:4`). No broker is started: connector `Active_MQ` declares no `brokerURL` (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:3`), so the original uses Mule's ActiveMQ default, an in-VM broker inside the runtime's JVM. The admin-console send of the original README (`using-transactional-scope-in-jms-to-database/README.md:20`) reaches no queue of that broker, so every step below that touches `in` or `out` runs inside the runtime's JVM, through a capture harness that holds the application's `MuleContext` as the original `FunctionalTestCase` did (`using-transactional-scope-in-jms-to-database/src/test/java/org/mule/examples/UsingTransactionalScopeInJMStoDatabaseIT.java:26,35-39`). The harness lives under `target/fixture-capture/` and is not committed.
4. Before each scenario, confirm that `orders` holds no row and that a browse of `in` and of `out` finds no message; a scenario that does not start from that state is captured again.
5. Send each scenario's message the way the original test did, with a `MuleClient` of the application's context: `client.send("jms://in", <body>, null)` (`using-transactional-scope-in-jms-to-database/src/test/java/org/mule/examples/UsingTransactionalScopeInJMStoDatabaseIT.java:44-45`). For `jms-in-order-rolled-back` the body is the content of the original `using-transactional-scope-in-jms-to-database/src/test/resources/message.xml`, read unchanged as the original test read it (`using-transactional-scope-in-jms-to-database/src/test/java/org/mule/examples/UsingTransactionalScopeInJMStoDatabaseIT.java:55`), of which the project's `src/test/resources/message.xml` is a byte-for-byte copy; for `jms-in-malformed-order-xml` it is the `Request` cell's inline body. The harness sets no application property.
6. After the flow has settled, which is when the runtime logs no further delivery of the message (for `jms-in-order-rolled-back`, once the redelivery-exceeded ERROR is logged), record into the evidence, raw and unedited:
   - the rows of `SELECT * FROM orders ORDER BY id`, read from the MySQL database of step 2;
   - a browse of queue `in` and then of queue `out` on the same in-VM broker, through a JMS `QueueBrowser` on the connection factory of connector `Active_MQ` (browse, not consume);
   - the runtime log, from which `response.logs` takes the INFO events of the flow's `logger` element (`using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:11`), in order, as level and message text, each with the logger category it was logged under. Only these events are compared.

Broker message IDs (the one inside the redelivery-exceeded ERROR text included), JMS timestamps and the other header fields the broker assigns, log timestamps, and the `id` that `AUTO_INCREMENT` assigns in `orders`, which the flow never reads, are not captured (AAP 0.8.4): they stay in the evidence and out of the fixture, so `response.tables.orders` rows carry `item_id`, `item_units` and `customer_id` only and no `logs` entry holds a broker message ID. Per-run identifiers that the Mule runtime rather than the flow attaches to a message, such as `MULE_ROOT_MESSAGE_ID`, `MULE_CORRELATION_ID` or a serialized `MULE_SESSION`, and the `JMSReplyTo` that `MuleClient.send` sets, are treated the same way. Every other column and property is captured exactly as it appears; a mismatch that results is a Tier 2A finding handled under the integrity rule.

Write `fixtures/<scenario>.json` from `request` (the message of step 5), `state` (the override of the Fixture contract and the empty `orders` of step 2), `upstream` (`[]`) and `response` (step 6, without the values the previous paragraph leaves out), every value as produced. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for the database user, its password and the host of `jdbc.url`: a match in a fixture means that scenario gets no fixture until it is captured again without it, and a match in an evidence file keeps that file out of the commit. Captured values are never adjusted afterwards, to match the Java output or for any other reason.

Replay: `FixtureParityTest`'s `jms` driver sends the message to queue `in` of the embedded Artemis broker (`artemis-jakarta-server`, test scope in the project `pom.xml`) that the test profile starts, and reads `orders` from the in-memory H2 database of `src/test/resources/application-test.yml`, seeded from `state`. It never contacts a live broker or database.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for both scenarios that means `trigger/OrderQueueListener`, `mapper/OrderXmlMapper`, `service/OrderTransactionService`, `service/TestComponent`, `client/OrderRepository` and `config/TransactionConfig`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
