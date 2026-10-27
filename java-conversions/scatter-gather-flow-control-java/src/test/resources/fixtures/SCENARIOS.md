# Parity scenarios — scatter-gather-flow-control-java

Scenarios for the fixture replay (ST-2) of `scatter-gather-flow-control-java`. The original example is `scatter-gather-flow-control` (folder `scatter-gather-flow-control/`). Its flow `scatter-gatherFlow` answers every HTTP request on the `/scatterGather` listener: two constant routes run in a scatter-gather, `ContactMergeAggregationStrategy` merges their contact lists, DataWeave writes the merged list as CSV `Id,Name,Email`, and the sub-flow `outboundFlow` attaches that CSV as `aggregation-report` to a mail it sends through the `Gmail` SMTP connector before the flow returns its payload. Every reachable branch listed for the example in AAP 0.7.5 is exactly one scenario below, as are the method the listener leaves open, the SMTP failure the flow does not handle and the no-listener default; each scenario's captured fixture is `fixtures/<scenario>.json`, apart from `smtp-unavailable`, whose fixture is replayed on its own (see below the table).

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `post-scatter-gather` | `http` | `deterministic` | `POST /scatterGather`, empty body | a GreenMail SMTP server with the mailboxes `scatter@localhost` (sender and SMTP AUTH login) and `receiver@localhost` (recipient), which the `smtp.*` and `mail.*` keys point at (Fixture contract) | `/scatterGather` listener → routes sourceA and sourceB → `ContactMergeAggregationStrategy` → DataWeave CSV `Id,Name,Email` → `outboundFlow` sends the report mail → `set-payload` returns the flow payload | `scatter-gather-flow-control/src/main/app/scatter-gather.xml:6-66`, `scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:106-107` |
| `get-scatter-gather` | `http` | `deterministic` | `GET /scatterGather`, empty body | a GreenMail SMTP server with the mailboxes `scatter@localhost` (sender and SMTP AUTH login) and `receiver@localhost` (recipient), which the `smtp.*` and `mail.*` keys point at (Fixture contract) | the branch of `post-scatter-gather`, selected by `GET` because the listener declares no `allowedMethods` (AAP 0.4.9); the original README opens this URL in a browser | `scatter-gather-flow-control/src/main/app/scatter-gather.xml:7`, `scatter-gather-flow-control/README.md:66` |
| `scatter-gather-report-mail` | `http` | `nondeterministic` | `POST /scatterGather`, empty body | a GreenMail SMTP server with the mailboxes `scatter@localhost` (sender and SMTP AUTH login) and `receiver@localhost` (recipient), which the `smtp.*` and `mail.*` keys point at (Fixture contract) | the branch of `post-scatter-gather`; the compared effect is the mail `outboundFlow` sends (attachment `aggregation-report`, `text/plain`; subject `Scatter gather example - aggregation`), captured as raw MIME under `response.mails`; its `Date`, `Message-ID` and MIME boundary change with every send | `scatter-gather-flow-control/src/main/app/scatter-gather.xml:61-66`, `scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:112,116-148` |
| `smtp-unavailable` | `http` | `nondeterministic` | `POST /scatterGather`, empty body | `smtp.host` `127.0.0.1` and `smtp.port` `${freePort:smtp-closed}`, a loopback port where nothing listens (port `1` in the capture); no mailbox | `outboundFlow`'s SMTP send cannot connect, and the flow declares no exception strategy, so the unhandled-exception platform default applies (AAP 0.4.7), a Tier 2A verification point where the captured bytes govern; the failure text embeds the SMTP endpoint URI with its environment-dependent host and port, so the body is compared by structure | `scatter-gather-flow-control/src/main/app/scatter-gather.xml:64` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | none; the fixture still carries the project's SMTP `state` (Fixture contract), so the one replay context boots | no listener serves `/unknown` → no-listener platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `scatter-gather-flow-control/src/main/app/scatter-gather.xml:4,7` |
| `aggregation-missing-route-result` | `http` | `unreachable` | — | none | `ContactMergeAggregationStrategy` throws `IllegalStateException` when the count of successful route results is not 2; unreachable: both routes are constant `expression-component`s that read no input and cannot throw, so every request yields exactly two successful results (proof below the table) | `scatter-gather-flow-control/src/main/java/org/mule/examples/transformers/ContactMergeAggregationStrategy.java:32-33`, `scatter-gather-flow-control/src/main/app/scatter-gather.xml:10-43` |

`scatter-gather-report-mail` and `smtp-unavailable` are `nondeterministic`, and `aggregation-missing-route-result` is `unreachable`. The notes below give what varies, the proof and how the rows are replayed.

- Unreachable branch `aggregation-missing-route-result` (AAP 0.8.2). Both routes of the scatter-gather (`scatter-gather-flow-control/src/main/app/scatter-gather.xml:10-26` and `27-43`) are constant `expression-component`s that only build a fixed `ArrayList` of two `HashMap`s from string literals. They read no payload, header, query or variable and call nothing that can fail, so every request yields exactly two successful route results, and the `count != 2` check of `scatter-gather-flow-control/src/main/java/org/mule/examples/transformers/ContactMergeAggregationStrategy.java:32-33` never fires. The branch is kept in the Java code as the original declares it and is covered by `service/ContactMergeAggregationStrategyTest` and `service/ScatterGatherServiceTest`, which hand it failed route results directly. No request is invented for it: it has no fixture, is excluded from capture and from the branch-coverage claim, its status is `UNREACHABLE`, and the project `README.md` names it.
- Trigger and mail effect. Every row's `Trigger` is `http`, because the trigger is the scenario's input (AAP 0.8.4) and every input here is an HTTP request. The SMTP mail is an output of the flow: the `mail` driver delivers an inbound mail and calls a poller's single-run method, and this project has no mail poller. In `scatter-gather-report-mail` the sent mail is recorded under `response.mails`, the key the Fixture contract defines for sent mails, and compared by structure. `post-scatter-gather` and `get-scatter-gather` record only the HTTP status line, the listed headers and the body; the SMTP server must still be reachable while they run, so that the send succeeds and the flow returns its payload.
- Nondeterministic rows, counted as unverified for the AAP 0.8.7 label and escalated as AAP 0.8.8 sets out: `scatter-gather-report-mail`, whose raw MIME carries a per-send `Date`, `Message-ID` and multipart boundary; and `smtp-unavailable`, whose failure text embeds the SMTP endpoint URI and its environment-dependent host and port. The project `README.md` lists both as `UNVERIFIED — nondeterministic output`.
- Platform defaults. `get-unknown-path` (the no-listener default) and `smtp-unavailable` (the unhandled-exception default) are Tier 2A verification points of AAP 0.4.7: the captured fixture governs, and SC-1 `GlobalExceptionHandler` is changed to match it.
- Isolated replay of `smtp-unavailable`. One application context serves every fixture of a run, and a `state.properties` key that resolves to different values in two fixtures fails the class. `smtp-unavailable` needs `smtp.host` and `smtp.port` on a closed port while every other fixture points them at GreenMail, so its fixture is written to `fixtures/isolated/smtp-unavailable.json` instead of `fixtures/`. `classpath*:fixtures/*.json` does not reach that subdirectory, so the default run reports the row `UNVERIFIED-uncaptured`. The fixture is replayed alone with `mvn -B test -Dtest=FixtureParityTest -Dfixtures.dir=src/test/resources/fixtures/isolated`, which reads this `SCENARIOS.md` from the classpath, reports the other rows `UNVERIFIED-uncaptured` and overwrites `target/fixture-parity/summary.properties`; the parity label is taken from the default run, with the isolated result reported beside it. Being `nondeterministic`, the row counts as unverified in both runs.

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
- Every row of this file is captured by sending its request to the original deployed with `http.port=8081` (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8081` in this project). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.

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
- For this project, every fixture in `fixtures/` carries the same `state`, the SMTP block below, `get-unknown-path` included. One application context serves them all, and an empty `smtp.host` fails the replay boundary (`spring.mail.host` reads it), so any subset of the fixtures must still give the mail sender a replay-owned server:
  - `properties` is `{"smtp.host": "localhost", "smtp.port": "${greenmail.smtp.port}", "smtp.user": "scatter@localhost", "smtp.password": "secret", "mail.from": "scatter@localhost", "mail.to": "receiver@localhost"}`, the keys `src/main/resources/application.yml` binds to `spring.mail.*` and to the mail's sender and recipient. They are the local GreenMail test values of `src/test/resources/application-test.yml` and of Capture step 3, not credentials. `smtp.user` is the plain address: the original takes it URL-encoded because Mule places it in the SMTP endpoint URI and decodes it again for SMTP AUTH (`scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:93`, `scatter-gather-flow-control/README.md:54`), while `spring.mail.username` takes the address as is.
  - `mail` is `{"users": [{"email": "scatter@localhost", "login": "scatter@localhost", "password": "secret"}, {"email": "receiver@localhost", "login": "receiver@localhost", "password": "secret"}]}`: the sender, whose login the mail sender authenticates with, and the recipient mailbox.
  - `smtp-unavailable`, in `fixtures/isolated/`, carries `properties` with `"smtp.host": "127.0.0.1"` and `"smtp.port": "${freePort:smtp-closed}"`, the same `smtp.user`, `smtp.password`, `mail.from` and `mail.to`, and no `mail`: nothing listens on that port, so the send fails at connect, as in the capture.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project: its flow makes no HTTP or SOAP call, and its SMTP send goes to the local mail server, whose effect `scatter-gather-report-mail` records under `response.mails`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and again 500 ms later.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.

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

Skeleton of the `scatter-gather-report-mail` fixture of this project; every `request` and `response` value comes from the capture, and `state` is written as Capture step 8 defines. The other fixtures in `fixtures/` carry the same `state` and no `response.mails`:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>" },
  "state":    { "properties": { "smtp.host": "localhost", "smtp.port": "${greenmail.smtp.port}",
                                "smtp.user": "scatter@localhost", "smtp.password": "secret",
                                "mail.from": "scatter@localhost", "mail.to": "receiver@localhost" },
                "mail": { "users": [{ "email": "scatter@localhost", "login": "scatter@localhost", "password": "secret" },
                                    { "email": "receiver@localhost", "login": "receiver@localhost", "password": "secret" }] } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>",
                "mails": [{ "mimeBase64": "<captured>" }] }
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `post-scatter-gather`, `get-scatter-gather`, `scatter-gather-report-mail`, `smtp-unavailable` and `get-unknown-path` as `UNVERIFIED-uncaptured`, and `aggregation-missing-route-result` as `UNREACHABLE`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

This project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable` in its README and in the root `SUMMARY.md` until its fixtures are captured. Because `scatter-gather-report-mail` and `smtp-unavailable` are `nondeterministic` and stay unverified once captured, the strongest label its captures can earn is `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified`.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. For this project the evidence of `scatter-gather-report-mail` also holds `mail.eml`, the raw MIME of the report mail as the local mail server returns it. Every command after step 1 runs in the project directory (`java-conversions/scatter-gather-flow-control-java/`).

1. Build the unmodified original `scatter-gather-flow-control` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for scatter-gather-flow-control, whose flow makes no HTTP or SOAP outbound call: its SMTP send goes to the local mail server of step 3 and is captured as a mail effect (steps 4 and 6). Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
   - Start WireMock on a free port `W` with an empty root directory. `--disable-gzip` passes a proxied response's `Content-Encoding` and bytes to the original unchanged, and `--preserve-user-agent-proxy-header` forwards the original's `User-Agent` on the proxied leg. Then add one proxy mapping per real upstream, whose `proxyBaseUrl` is that upstream's scheme, host and port:

     ```sh
     W=8089; rm -rf target/fixture-capture/wiremock; mkdir -p target/fixture-capture/wiremock
     java -jar "$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar" --port "$W" --root-dir target/fixture-capture/wiremock --disable-gzip --preserve-user-agent-proxy-header > target/fixture-capture/wiremock.log 2>&1 &
     WM=$!; until curl -sf "http://localhost:$W/__admin/health" > /dev/null; do sleep 1; done
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com"}}'
     ```

     With several upstreams, each mapping matches only its own upstream's paths (`"urlPathPattern": "/<prefix>(/.*)?"`); upstreams whose paths overlap cannot be told apart through one WireMock, so those scenarios omit `upstream`. `kill "$WM"` stops WireMock after the last scenario.
   - The original is pointed at WireMock by runtime properties only (`-M-D<key>=<value>` at deployment, step 3): each upstream placeholder or host setting becomes `http://localhost:<W>`, or host `localhost` and port `W`, keeping its path. An original that cannot reach WireMock this way (a hard-coded host, or a TLS setting that rejects WireMock) cannot have its calls recorded, so its scenarios omit `upstream`.
   - Live credentials (values supplied for the capture that the original folder does not already contain) are never committed. The original runs with a fixed marker in place of each, such as `capture-marker`, which the fixture records and `state.properties` gives the Java app under the same key (step 8), so the replay compares the same values. A credential sent in a request header is replaced by the live value on the proxied leg only, in that upstream's mapping, here for an `X-Api-Key` header whose live value is in the environment variable `LIVE_API_KEY`, never in a file:

     ```sh
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com", "removeProxyRequestHeaders": ["x-api-key"], "additionalProxyRequestHeaders": {"X-Api-Key": "'"$LIVE_API_KEY"'"}}}'
     ```

     WireMock 3.13.2 removes a header only when `removeProxyRequestHeaders` lists its name in lower case. A credential WireMock cannot replace (in a body, a query or a signature), a token or session the live service issues into a recorded exchange, and a response that sends the original to a host it does not reach through WireMock cannot be recorded locally without live values: those scenarios are captured with the live values in place, omit `upstream`, and keep their journal out of the evidence.
3. Start a local SMTP and IMAP server in place of Gmail: GreenMail standalone 2.1.14 (`com.icegreen:greenmail-standalone`, the GreenMail release the project's tests use, fetched from Maven Central by the dependency plugin), with the sender and recipient mailboxes of the Fixture contract, SMTP on `localhost:3025` and IMAP on `localhost:3143`. It accepts SMTP AUTH, which the original's `Gmail` connector (`scatter-gather-flow-control/src/main/app/scatter-gather.xml:3`) enables together with STARTTLS; its plain SMTP listener offers no STARTTLS, so the connector goes on without it, as the Java mail sender does on replay. The server starts with empty mailboxes and runs until the last scenario, when `kill "$GM"` stops it; the REST API it also opens on `127.0.0.1:8080` is not used and does not collide with the original's listener on 8081:

   ```sh
   G=target/fixture-capture/greenmail-standalone-2.1.14.jar; mkdir -p target/fixture-capture
   [ -f "$G" ] || mvn -B -q dependency:copy -Dartifact=com.icegreen:greenmail-standalone:2.1.14 -DoutputDirectory=target/fixture-capture
   java -Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.users=scatter:secret@localhost,receiver:secret@localhost -Dgreenmail.users.login=email -jar "$G" > target/fixture-capture/greenmail.log 2>&1 &
   GM=$!; until curl -s -o /dev/null -u 'receiver@localhost:secret' 'imap://localhost:3143/' -X 'NOOP'; do sleep 1; done
   ```

   Then deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.port=8081` as a runtime property (for example `-M-Dhttp.port=8081`), because `scatter-gather-flow-control/src/main/app/mule-app.properties` defines no value, together with the SMTP properties the flow reads (`scatter-gather-flow-control/src/main/app/scatter-gather.xml:64`): `-M-Dsmtp.host=localhost -M-Dsmtp.port=3025 -M-Dsmtp.user=scatter%40localhost -M-Dsmtp.password=secret -M-Dmail.from=scatter@localhost -M-Dmail.to=receiver@localhost`. `smtp.user` is URL-encoded as the original test and README pass it (`scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:93`, `scatter-gather-flow-control/README.md:54`), because Mule places it in the SMTP endpoint URI; it matches the GreenMail login `scatter@localhost`. These are local test values only, never real credentials: `smtp-unavailable` captures a failure text that can echo the endpoint URI. `mail.subject`, which the original test also sets (`scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:97`), is read by no flow, because the subject is the literal of `scatter-gather-flow-control/src/main/app/scatter-gather.xml:64`, so the capture does not pass it.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   inbox() { curl -sS -u 'receiver@localhost:secret' "imap://localhost:3143/$1" "${@:2}"; }
   inbox '' -X 'STATUS INBOX (MESSAGES)'
   capture scatter-gather-report-mail -X POST http://localhost:8081/scatterGather
   for _ in $(seq 100); do inbox '' -X 'STATUS INBOX (MESSAGES)' | grep -q '(MESSAGES 1)' && break; sleep 0.1; done
   inbox 'INBOX;MAILINDEX=1' > src/test/capture/scatter-gather-report-mail/mail.eml
   capture post-scatter-gather -X POST http://localhost:8081/scatterGather
   capture get-scatter-gather http://localhost:8081/scatterGather
   capture get-unknown-path http://localhost:8081/unknown
   ```

   `scatter-gather-report-mail` is captured first, while the recipient mailbox is still empty (the first `STATUS` reply shows `MESSAGES 0`), so its report mail is message 1; the loop waits up to 10 s for it to arrive, as the original test waits 10 s before reading the inbox (`scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:108`), and curl's IMAP fetch writes it to `mail.eml` exactly as the server returns it. The mails of the next two scenarios stay in the mailbox and are not read. `smtp-unavailable` runs last: redeploy the original with `-M-Dsmtp.host=127.0.0.1 -M-Dsmtp.port=1`, a loopback port where nothing listens (`ss -ltn 'sport = :1'` lists no socket), the other properties unchanged, then:

   ```sh
   capture smtp-unavailable -X POST http://localhost:8081/scatterGather
   ```

   `aggregation-missing-route-result` is never captured: no request selects it.

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); and `"port": 8081`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/post-scatter-gather/response.http POST <<'PY'
   import base64, io, json, pathlib, re, sys
   raw, method = io.BytesIO(pathlib.Path(sys.argv[1]).read_bytes()), sys.argv[2]
   MAX_BODY_LENGTH = 2**31 - 1 - 8

   def line():
       data = raw.readline()
       if not data.endswith(b'\n'):
           sys.exit('capture ends inside a line')
       return data[:-2] if data.endswith(b'\r\n') else data[:-1]

   while True:
       status_line = line().decode('latin-1')
       version, _, rest = status_line.partition(' ')
       code, _, reason = rest.partition(' ')
       digits = code.lstrip('0') or '0'
       if not version or not re.fullmatch('[0-9]+', code) or len(digits) > 10 or int(digits) > 2**31 - 1:
           sys.exit('malformed status line: ' + repr(status_line))
       status = int(digits)
       headers = []
       while field := line():
           if field[:1] in (b' ', b'\t'):
               sys.exit('folded header line')
           name, colon, value = field.decode('latin-1').partition(':')
           if not colon:
               sys.exit('malformed header line: ' + repr(name))
           headers.append((name.lower(), value.strip(' \t')))
       if status // 100 != 1 or status == 101:
           break

   def values(name):
       return [v for n, v in headers if n == name]

   def number(text, base, what, limit=MAX_BODY_LENGTH):
       text = text.strip(' \t')
       if not re.fullmatch('[0-9A-Fa-f]+' if base == 16 else '[0-9]+', text):
           sys.exit('malformed ' + what + ': ' + repr(text))
       digits = text.lstrip('0') or '0'
       if len(digits) > 10 or int(digits, base) > limit:
           sys.exit(what + ' too large for an in-memory body: ' + text)
       return int(digits, base)

   coding = ','.join(values('transfer-encoding')).split(',')[-1].split(';')[0].strip(' \t').lower()
   if method == 'HEAD' or status // 100 == 1 or status in (204, 304):
       body = b''
   elif coding == 'chunked':
       body = b''
       while size := number(line().decode('latin-1').split(';')[0], 16, 'chunk size',
                            MAX_BODY_LENGTH - len(body)):
           chunk = raw.read(size)
           if len(chunk) != size or line():
               sys.exit('malformed chunk')
           body += chunk
       while line():
           pass
   elif coding or not values('content-length'):
       body = raw.read()
   else:
       lengths = {number(n, 10, 'Content-Length') for n in ','.join(values('content-length')).split(',')}
       if len(lengths) > 1:
           sys.exit('conflicting Content-Length values: ' + ', '.join(values('content-length')))
       length = lengths.pop()
       body = raw.read(length)
       if len(body) != length:
           sys.exit('capture ends inside the body')
   if raw.read():
       sys.exit('bytes after the message')
   response = {'status': status, 'reason': reason, 'headers': {}}
   for name in ('Content-Type', 'Location'):
       found = values(name.lower())
       if len(found) > 1:
           sys.exit('repeated ' + name)
       if found:
           response['headers'][name] = found[0]
   try:
       response['body'] = body.decode('utf-8')
   except UnicodeDecodeError:
       response['bodyBase64'] = base64.b64encode(body).decode('ascii')
   print(json.dumps(response, indent=2))
   PY
   ```

   For `scatter-gather-report-mail`, `response.mails` holds one entry, the received mail as `mail.eml` holds it, Base64-encoded. The comparison reads its From, To, Cc, Subject and leaf parts and ignores its `Date`, `Message-ID` and boundaries; the `Received` and `Return-Path` lines the server adds on delivery are server-assigned (AAP 0.8.4), are left as delivered and take no part in it. This command prints the array:

   ```sh
   jq -n --arg mime "$(base64 -w0 src/test/capture/scatter-gather-report-mail/mail.eml)" '[{mimeBase64: $mime}]'
   ```

7. `upstream` is `[]` when the original made no HTTP or SOAP outbound call, as in every scenario of this project, whose only outbound exchange is the SMTP send of steps 3 and 4. Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

   ```sh
   S=<scenario>; J="target/fixture-capture/$S-journal.json"
   jq '[.requests | reverse[] | {request, response}]' "$J" > "src/test/capture/$S/upstream-journal.json"
   jq 'def pairs: [to_entries[] | .key as $k | (.value | if type == "array" then .[] else . end) | [$k, .]];
     [.requests | reverse[]
      | if .responseDefinition.proxyBaseUrl == null then error("not proxied: \(.request.method) \(.request.url)")
        elif (.response.body // "") | startswith("Network failure trying to make a proxied request") then error("upstream not reached: \(.request.url)")
        elif .request.headers // {} | keys | map(ascii_downcase) | index("content-encoding") then error("encoded request body: \(.request.url)")
        else {request: {method: .request.method, url: .request.url, headers: (.request.headers // {} | pairs), bodyBase64: .request.bodyAsBase64},
              response: {status: .response.status, headers: (.response.headers // {} | pairs), bodyBase64: .response.bodyAsBase64}}
        end]' "$J"
   ```

   The journal lists the newest call first, so its entries are reversed into call order. `url` is the path and query relative to the WireMock base, so the fixture names no live host. A repeated header name becomes consecutive pairs at the position of its first occurrence, the order the journal keeps. Bodies are `bodyBase64`, the entity bytes as recorded: a chunked body in either direction is de-chunked with its trailer fields dropped, and a gzip-encoded response body keeps its encoded bytes and its `Content-Encoding` header, as step 6 defines for `response`. The command fails on a call no mapping proxied or that did not reach its upstream, which is captured again once the mapping is fixed, and on a request body with a `Content-Encoding`, which WireMock stores decoded and re-encodes on the proxied leg, so that scenario omits `upstream`.
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (the SMTP `state` the Fixture contract defines, the same in every fixture of `fixtures/`), `upstream` (step 7) and `response` (step 6), every value as produced. `smtp-unavailable` is written to `fixtures/isolated/smtp-unavailable.json` with its closed-port `state` (Fixture contract), and `aggregation-missing-route-result` gets no fixture. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `get-unknown-path` and `smtp-unavailable`, that means SC-1 `GlobalExceptionHandler`, and for the other three `/scatterGather` scenarios `controller/ScatterGatherController`, `service/ScatterGatherService`, `mapper/ContactsCsvMapper` and `client/AggregationReportMailer`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- What the original answers when no listener serves the path and when the SMTP send fails is a Tier 2A verification point (AAP 0.4.7): status, reason, headers and body come only from the `get-unknown-path` and `smtp-unavailable` captures, and this file states none of them.
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
