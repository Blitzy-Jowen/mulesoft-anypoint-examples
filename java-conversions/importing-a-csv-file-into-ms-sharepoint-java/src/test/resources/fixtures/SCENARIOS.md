# Parity scenarios — importing-a-csv-file-into-ms-sharepoint-java

Scenarios for the fixture replay (ST-2) of `importing-a-csv-file-into-ms-sharepoint-java`. The original example is `importing-a-csv-file-into-ms-sharepoint` (folder `importing-a-csv-file-into-ms-sharepoint/`). Its single flow `importing-a-csv-file-into-ms-sharepointFlow` polls `src/main/resources/input` every 10000 ms, keeps each polled file's content in the flow variable `fileContent`, creates the folder `/Shared Documents/${csvImportFolder}` in SharePoint Online, adds the content there as `contacts.csv` with overwrite, and logs at INFO; the file endpoint then moves the file to `src/main/resources/output`. The one branch listed for the example in AAP 0.7.5 is the one scenario below, and its captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there and adapted to this project's file trigger, and its row comes from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `file-contacts-csv-imported` | `file` | `deterministic` | drop `contacts.csv` into the input dir, run one poll through `CsvFileTrigger.pollOnce()` | input dir holding the file, empty output dir, `csvImportFolder` fixed for capture in `state.properties` | folder create → file add `contacts.csv` overwrite → INFO log; file moved to output | `importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml:7-14`, `importing-a-csv-file-into-ms-sharepoint/src/test/java/org/mule/examples/ImportingCsvFileIntoMSSharepointIT.java:91-105` |

Capture note: the recorded sign-in carries the account's credentials, because the body of the RST/Issue request sent to the security token service (STS) holds the username and password, and the STS token, the `rtFa` and `FedAuth` cookies and the form digest recorded after it are session credentials of the same account. The scenario is therefore captured only with a dedicated sandbox account, which is revoked immediately after capture. Otherwise it is left uncaptured, reported `UNVERIFIED-uncaptured` and escalated per AAP 0.8.8; it is also left uncaptured when the proxied sign-in cannot be recorded without naming the real tenant host. The `csvImportFolder` fixed for the capture is recorded in the fixture's `state.properties`, so replay requests the same folder URL.

No scenario of this project is `unreachable` or `nondeterministic`: the flow has one path, reads no clock or random value, and takes its folder and remote file name from configuration alone.

Notes for this project:

- Remote file name: the SharePoint target name is always `contacts.csv`, because `fileServerRelativeUrl` is fixed at flow line 12 (`/Shared Documents/${csvImportFolder}/contacts.csv`), whatever the input file's name. Only the move to `output` keeps the input file's own name.
- Source facts behind the row: line 7 is `file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"`; line 8 stores the content in `fileContent`; line 10 is `sharepoint:folder-create url="/Shared Documents/${csvImportFolder}"`; line 12 is `sharepoint:file-add` with `overwrite="true"`; line 14 is the INFO logger. The flow declares no exception strategy and no transaction, so under SC-5 (`trigger/FileInboundPoller`) the file is moved to `output` after any non-rollback outcome. The original IT dispatches the CSV to the input directory, waits 20000 ms, reads the SharePoint copy back and asserts that it equals the local file (lines 91-105), then deletes the folder in its teardown (`deleteFolderInSharepointFlow`, `importing-a-csv-file-into-ms-sharepoint/src/test/resources/testflows/test-flows.xml:10-12`). That assertion is ported in `ImportingCsvFileIntoMSSharepointTest`, not here.
- Replay inputs: the fixture's `request.invoke` names the single-run method `{"type": "trigger.CsvFileTrigger", "method": "pollOnce"}`, which polls through the same SC-5 poller as the schedule. Surefire runs the tests with profile `test`, whose `application-test.yml` points `Sharepoint.SiteUrl` and `sharepoint-online.sts-url` at a loopback port the replay does not own, so the fixture's `state.properties` overrides both (Capture step 8); otherwise the replay boundary fails the class.
- Addresses inside the sign-in: an RST/Issue body names addresses; the one `client/SharepointRestClient` sends carries its configured STS URL (`a:To`) and site URL (`wsp:AppliesTo`). A `deterministic` replay matches every upstream request body byte for byte, against a WireMock whose port is chosen at replay. A recorded RST/Issue body that names the capture's WireMock port, or a real host, therefore cannot be reproduced through the placeholders this format allows: that capture omits `upstream` and keeps its journal out of the evidence, so the scenario is reported `UNVERIFIED-no-upstream` and escalated per AAP 0.8.8. The recording is never edited to make it replayable.
- Parity label: until a fixture is captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7) in its README and in its row of the root `SUMMARY.md`.

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
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.
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
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- `clearCaches`: optional, `true` or `false`, the default. `true` stands for the restart the capture made before the scenario, after which the original's in-memory stores were empty: once the `state` is seeded, and before the prior requests and the input, the replay empties every cache of every `org.springframework.cache.CacheManager` bean of the application context. A project without such a bean reports the scenario `UNVERIFIED-missing-driver`, since the replay cannot re-create the empty stores the capture started from. Without `clearCaches`, the application's caches and its other in-memory state carry over from the scenarios before, in table order.
- `requests`: optional; the prior requests the capture sent before the scenario's own (Capture step 4), in the order sent, each an object of the `request` shape for `http` and `soap` above (`method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `portProperty`, `tls`, `readTimeoutMillis`). The replay sends them through ST-1 after the `state` is seeded, its caches cleared and the `upstream` stubs installed, and before the input. Their replies are discarded, as the capture discarded them, whatever their status; one that cannot be sent, or whose reply cannot be read, fails the scenario. Everything else they cause belongs to the scenario: their outbound calls are recorded in `upstream`, in call order, and the effects read back after the run (files, messages, rows, documents, mails) include theirs. Only their log events are left out: the replay drops the events logged before the input, as `response.logs` holds the events from the input on.
- For this project, `state` holds `properties` (Capture step 8) and `directories` with an empty `output`; the input file itself is placed by `request.file`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 5): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. No scenario of this project records `[]`: its branch always signs in, creates the folder and adds the file.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 5 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 of the reference format defines. This project's fixture records no HTTP response.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and again 500 ms later.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match and compares it again 500 ms later, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.

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
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset clears no application cache and no other in-memory state of the application, so scenarios that build on one another in table order keep doing so; only a fixture's `state.clearCaches` empties the application's caches, once its `state` is seeded. Its `state.requests` are sent after that, and its input last. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

Skeleton of this project's fixture. Every `<captured>` value comes from the capture; the `request.invoke` target and the placeholder values of `state.properties` are those Capture step 8 sets:

```json
{
  "request":  { "file": { "directory": "input", "name": "<captured>", "body": "<captured>" },
                "invoke": { "type": "trigger.CsvFileTrigger", "method": "pollOnce" } },
  "state":    { "properties": { "csv-import.input-dir": "${dir:input}",
                                "csv-import.move-to-dir": "${dir:output}",
                                "Sharepoint.SiteUrl": "${wiremock.baseUrl}",
                                "sharepoint-online.sts-url": "${wiremock.baseUrl}<captured STS path>",
                                "Sharepoint.Username": "<captured>", "Sharepoint.Password": "<captured>",
                                "csvImportFolder": "<captured>" },
                "directories": { "output": [] } },
  "upstream": [ { "request": "<captured>", "response": "<captured>" } ],
  "response": { "directories": { "input": "<captured>", "output": "<captured>" },
                "logs": [ { "logger": "<captured>", "level": "<captured>", "message": "<captured>" } ] }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `file` trigger, the trigger of this project: the produced files (the exact final file set of `input` and `output`, names and bytes), the upstream calls (every recorded exchange, in order) and the SC-5 file disposition (the input consumed and moved to `output` under its own name) are compared, under the rule of the row's state; the log event is matched as `response.logs` defines.
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `file-contacts-csv-imported` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of the scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/file-contacts-csv-imported/`, outside `fixtures/` and off the test classpath: `input/` holds the byte copy of the file dropped, `output/` the files found in the output directory afterwards, `input-after.txt` the listing of the input directory afterwards, `log-event.txt` the runtime's log line for the flow's logger, and `upstream-journal.json` the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Live values (the tenant URL, and the sandbox account's username and password until it is revoked) are read from environment variables at capture time, never from a file. Every command after step 1 runs in the project directory (`java-conversions/importing-a-csv-file-into-ms-sharepoint-java/`).

1. Build the unmodified original `importing-a-csv-file-into-ms-sharepoint` with `mvn -B package`, with the EE repository credentials in `settings.xml`, and deploy the archive to a licensed Mule 3.8 EE runtime. Never edit the original folder. The original has no HTTP listener and reads no `http.port`. Its placeholders and JVM settings are passed as runtime properties at deployment (`-M-D<key>=<value>`): `Sharepoint.SiteUrl` and the proxy and trust-store settings of step 2, `csvImportFolder` of step 3, and `Sharepoint.Username` and `Sharepoint.Password` of the dedicated sandbox account (see the capture note under the table).
2. Both upstreams are recorded through one WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real tenant, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
   - Start WireMock on a free port `W` with an empty root directory. `--disable-gzip` passes a proxied response's `Content-Encoding` and bytes to the original unchanged, `--preserve-user-agent-proxy-header` forwards the original's `User-Agent` on the proxied leg, and `--enable-browser-proxying` lets the runtime use WireMock as its HTTPS proxy for the STS call below. The SharePoint site URL `https://<tenant>.sharepoint.com` is read from the environment variable `SP_SITE_URL`:

     ```sh
     W=8089; rm -rf target/fixture-capture/wiremock; mkdir -p target/fixture-capture/wiremock
     java -jar "$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar" --port "$W" --root-dir target/fixture-capture/wiremock --disable-gzip --preserve-user-agent-proxy-header --enable-browser-proxying > target/fixture-capture/wiremock.log 2>&1 &
     WM=$!; until curl -sf "http://localhost:$W/__admin/health" > /dev/null; do sleep 1; done
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"host": {"equalTo": "localhost"}, "urlPattern": ".*"}, "response": {"proxyBaseUrl": "'"$SP_SITE_URL"'"}}'
     ```

   - `Sharepoint.SiteUrl` points at WireMock directly: `-M-DSharepoint.SiteUrl=http://localhost:$W`. The mapping above forwards every request addressed to `localhost`, whatever its path, to the tenant.
   - The STS sign-in: when the connector offers a setting for its STS endpoint, that setting points at `http://localhost:<W>` with its path kept, the site mapping matches only the site's paths, and a second mapping forwards the STS path to the STS (`"urlPathPattern": "/<prefix>(/.*)?"`, one mapping per upstream). When it offers none, the runtime's outbound HTTPS is routed through WireMock's browser-proxy mode: `-M-Dhttps.proxyHost=localhost -M-Dhttps.proxyPort=$W`, with WireMock's CA certificate (served at `http://localhost:$W/__admin/certs/wiremock-ca.crt`) imported with `keytool` into a trust store under `target/fixture-capture/` that the runtime is given as `-M-Djavax.net.ssl.trustStore=<file>`. The STS is the Microsoft STS endpoint that `sharepoint-online.sts-url` defaults to in `application.yml`; its host is not `localhost`, so no mapping matches it and WireMock proxies it to its own host and journals it. If the connector's HTTP client ignores the JVM proxy settings or rejects WireMock's certificate, the STS exchange cannot be recorded locally and the scenario omits `upstream`.
   - `kill "$WM"` stops WireMock in step 7.
3. `csvImportFolder` is fixed to a known value chosen at capture time, one that does not already exist in the tenant's Shared Documents library, and passed as `-M-DcsvImportFolder=<folder>`. Step 8 records it in the fixture's `state.properties`, so replay requests the same `/Shared Documents/<csvImportFolder>` URLs: replay binds `csvImportFolder` from there, and the SharePoint and STS URLs from `${wiremock.baseUrl}`.
4. The input is a byte copy of `importing-a-csv-file-into-ms-sharepoint/src/main/resources/contacts.csv`, the file the original README tells users to drop (`importing-a-csv-file-into-ms-sharepoint/README.md:34-36`). The original's input directory is `path="src/main/resources/input"`, resolved against the runtime's working directory `R`, and its `output` directory is empty before the drop. The journal is cleared, and the file is written beside the input directory and then moved in, so the poll never reads a partly written file. The flow polls every 10000 ms, so the capture waits at least one polling interval plus processing time; the original IT waits 20000 ms:

   ```sh
   S=file-contacts-csv-imported; D="src/test/capture/$S"; R=<runtime working directory>
   mkdir -p "$D/input" "$D/output" target/fixture-capture
   cp ../../importing-a-csv-file-into-ms-sharepoint/src/main/resources/contacts.csv "$D/input/contacts.csv"
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   cp "$D/input/contacts.csv" "$R/src/main/resources/contacts.csv.part"
   mv "$R/src/main/resources/contacts.csv.part" "$R/src/main/resources/input/contacts.csv"
   sleep 20
   ```

5. `upstream` records, in call order, every exchange the original makes. The sequence `client/SharepointRestClient` sends is the STS RST/Issue sign-in, `_forms/default.aspx?wa=wsignin1.0`, `_api/contextinfo`, the folder create and the `Files/add` upload. The recording holds what the original sent, without editing; if its call sequence or paths differ from those of `client/SharepointRestClient`, the fixture governs and the Java client is changed (AAP 0.8.4). Once the flow's log line of step 6 has appeared, the journal is saved, and this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts, and prints the `upstream` array:

   ```sh
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/$S-journal.json"
   J="target/fixture-capture/$S-journal.json"
   jq '[.requests | reverse[] | {request, response}]' "$J" > "src/test/capture/$S/upstream-journal.json"
   jq 'def pairs: [to_entries[] | .key as $k | (.value | if type == "array" then .[] else . end) | [$k, .]];
     [.requests | reverse[]
      | if .responseDefinition.proxyBaseUrl == null and (.request.browserProxyRequest | not) then error("not proxied: \(.request.method) \(.request.url)")
        elif (.response.body // "") | startswith("Network failure trying to make a proxied request") then error("upstream not reached: \(.request.url)")
        elif .request.headers // {} | keys | map(ascii_downcase) | index("content-encoding") then error("encoded request body: \(.request.url)")
        else {request: {method: .request.method, url: .request.url, headers: (.request.headers // {} | pairs), bodyBase64: .request.bodyAsBase64},
              response: {status: .response.status, headers: (.response.headers // {} | pairs), bodyBase64: .response.bodyAsBase64}}
        end]' "$J"
   ```

   The journal lists the newest call first, so its entries are reversed into call order. WireMock 3.13.2 journals a browser-proxied call, such as the STS sign-in, without a `proxyBaseUrl`, so the command recognises it by `request.browserProxyRequest`; that condition is its only change from the reference command. `url` is the path and query relative to the WireMock base, for the browser-proxied STS call too, so the fixture names no live host through it. A repeated header name becomes consecutive pairs at the position of its first occurrence, the order the journal keeps. Bodies are `bodyBase64`, the entity bytes as recorded: a chunked body in either direction is de-chunked with its trailer fields dropped, and a gzip-encoded response body keeps its encoded bytes and its `Content-Encoding` header. The command fails on a call no mapping proxied or that did not reach its upstream, which is captured again once the mapping is fixed, and on a request body with a `Content-Encoding`, which WireMock stores decoded and re-encodes on the proxied leg, so that scenario omits `upstream`.
6. `response` records, as captured bytes: `directories.output`, the file moved to `output`, with its name and bytes (`body` when the bytes are valid UTF-8, otherwise `bodyBase64`); `directories.input`, the final file set of the now-empty `input` directory; and `logs`, every event logged under the category of the logger at flow line 14, in logged order and whatever its level (the run logs its one INFO event there), each with the logger category, level and message the runtime logged, taken from the application's log under the runtime's `logs/` directory, since the replay compares every event of that category one for one:

   ```sh
   cp -pR "$R/src/main/resources/output/." "$D/output/"
   ls -A "$R/src/main/resources/input" > "$D/input-after.txt"
   ```

   The log line is copied unedited into `log-event.txt`.
7. Recording stops before cleanup: the journal is saved (step 5), the original is undeployed and WireMock is stopped (`kill "$WM"`). Only then is the capture folder `/Shared Documents/<csvImportFolder>` deleted from the tenant, as the original IT's `deleteFolderInSharepointFlow` teardown does (`importing-a-csv-file-into-ms-sharepoint/src/test/resources/testflows/test-flows.xml:10-12`), through a client that does not pass through WireMock, so the cleanup call never appears in `upstream`. The sandbox account is revoked immediately afterwards.
8. Write `fixtures/file-contacts-csv-imported.json` from `request`, `state`, `upstream` (step 5) and `response` (step 6), every captured value as produced:
   - `request.file` is `{"directory": "input", "name": <the dropped file's name>, "body" or "bodyBase64": <the bytes of evidence input/contacts.csv>}`, and `request.invoke` is `{"type": "trigger.CsvFileTrigger", "method": "pollOnce"}`.
   - `state.properties` sets `csv-import.input-dir` to `${dir:input}`, `csv-import.move-to-dir` to `${dir:output}`, `Sharepoint.SiteUrl` to `${wiremock.baseUrl}`, `sharepoint-online.sts-url` to `${wiremock.baseUrl}` followed by the path and query of the recorded STS call's `url`, `csvImportFolder` to the value of step 3, and `Sharepoint.Username` and `Sharepoint.Password` to the values the recorded RST/Issue body carries, so the replayed sign-in sends the same bytes. `state.directories` is `{"output": []}`.
   - The sandbox account's username and password, and the token, cookies and form digest issued to it, stop being live values once its revocation (step 7) is confirmed; that is the only reason its sign-in may stay in `upstream` (see the capture note under the table).
   - Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every value that is still live and for the tenant host, and the same search runs over the decoded upstream bodies, which the fixture holds as Base64:

     ```sh
     jq -r '.upstream[] | (.request, .response) | .bodyBase64 // empty' "src/test/resources/fixtures/$S.json" | while read -r b; do printf '%s' "$b" | base64 -d; echo; done | grep -aF -e "<live value>"
     ```

     A match of the tenant host in the sign-in exchanges leaves the scenario uncaptured (capture note under the table). Any other match in `upstream` or a journal means the scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `file-contacts-csv-imported`, that means `client/SharepointRestClient` for the upstream exchanges, `service/SharepointImportService` for the log event, or SC-5 `trigger/FileInboundPoller` for the file disposition. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
