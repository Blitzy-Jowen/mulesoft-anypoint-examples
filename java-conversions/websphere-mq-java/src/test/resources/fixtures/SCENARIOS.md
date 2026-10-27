# Parity scenarios — websphere-mq-java

Scenarios for the fixture replay (ST-2) of `websphere-mq-java`. The original example is `websphere-mq` (folder `websphere-mq/`), which loads `websphere-mq.xml` (`config.resources` of `mule-deploy.properties`). Its Ajax connector `ajaxServer` serves the docroot page under `http://0.0.0.0:8086/services/wmqExample` and answers Bayeux at `/ajax/cometd` (SC-12), and its three flows carry a text through IBM MQ: `Input` puts each publication on the Ajax channel `/services/wmqExample/enqueue` on queue `in`; `MessageProcessor` takes it from `in` in a transaction it begins (`ALWAYS_BEGIN`), waits 15000 ms, appends ` - processed` and puts the result on queue `out` in the same transaction (`JOIN_IF_POSSIBLE`); and `Output` takes it from `out` and publishes it on the Ajax channel `/services/wmqExample/dequeue`. The page and the three flows listed for the example in AAP 0.7.5 are exactly one scenario each below; the example has no choice, exception strategy or unreachable branch. Each scenario's captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-services-wmqexample-page` | `http` | `deterministic` | port `8086`: `GET /services/wmqExample/`, empty body | none | the Ajax connector's `resourceBase` docroot serves its welcome file `index.html` at the server path; no flow runs | `websphere-mq/src/main/app/websphere-mq.xml:7`, `websphere-mq/src/main/app/docroot/index.html` |
| `ajax-enqueue-to-in-queue` | `http` | `nondeterministic` | port `8086`: `POST /ajax/cometd`, header `Content-Type: application/x-www-form-urlencoded`, body = form field `message` holding the last message of Bayeux sequence `B1` (Request notes), the `mule.rpc` publication of the text `hello` on `/services/wmqExample/enqueue` | queue `in` empty; the earlier messages of `B1` as prior requests | flow `Input`: Ajax inbound endpoint `/services/wmqExample/enqueue` → `delete-message-property MULE_REPLYTO` → WMQ outbound endpoint puts the text `hello` on queue `in`; no RPC reply is published on `replyTo` (`disableReplyTo="true"`); the capture stops flow `MessageProcessor`, so `in` keeps the message (Capture step 4); varies: the Bayeux `clientId` the handshake generates, which every later message of `B1` carries and which names the `replyTo` channel | `websphere-mq/src/main/app/websphere-mq.xml:7-14`, `websphere-mq/src/main/app/docroot/index.html:52-60` |
| `mq-in-processed-to-out` | `mq` | `deterministic` | text message `hello` → queue `in`, no application properties; `trigger/InQueueListener` consumes it, so no single-run method is invoked | queues `in` and `out` empty | flow `MessageProcessor`: WMQ inbound endpoint on `in` under `ALWAYS_BEGIN` → `test:component` waits 15000 ms and appends ` - processed` → WMQ outbound endpoint puts `hello - processed` on `out` in the same transaction (`JOIN_IF_POSSIBLE`), whose commit leaves `in` empty; the capture stops flow `Output`, so `out` keeps the message (Capture step 4) | `websphere-mq/src/main/app/websphere-mq.xml:15-23` |
| `mq-out-to-ajax-dequeue` | `mq` | `nondeterministic` | text message `hello - processed` → queue `out`, no application properties; `trigger/OutQueueListener` consumes it, so no single-run method is invoked; then Bayeux sequence `B2` (Request notes) on port `8086` | queue `out` empty; no Bayeux client subscribed to `/services/wmqExample/dequeue` | flow `Output`: WMQ inbound endpoint on `out`, without a transaction → Ajax outbound endpoint publishes the text on `/services/wmqExample/dequeue` and holds it while no client is subscribed (`cacheMessages="true"`), so the subscriber of `B2` receives it on its next `/meta/connect`; varies: the Bayeux `clientId` the handshake generates, which every later message of `B2` carries | `websphere-mq/src/main/app/websphere-mq.xml:24-27`, `websphere-mq/src/main/app/docroot/index.html:23-26` |

Two scenarios are `deterministic`: `get-services-wmqexample-page` and `mq-in-processed-to-out`. Two are `nondeterministic`: `ajax-enqueue-to-in-queue` and `mq-out-to-ajax-dequeue`. None is `unreachable`. Two rows are `http` and two are `mq`.

Request notes:

- `<clientId>` stands for the client ID the handshake answer returns. It is read from that answer during capture (Capture step 4) and is never written as a literal here.
- `B1` is the Bayeux 1.0 message sequence behind `mule.rpc(channel, data)` of the copied `mule-resource/js/_mule.js` (SC-12), which the page's `rpcEnqueue` calls with the typed text (`websphere-mq/src/main/app/docroot/index.html:52-60`). Each step is one `POST /ajax/cometd` on port 8086, whose form field `message` holds a JSON array with one message:
  1. `[{"channel":"/meta/handshake","version":"1.0","supportedConnectionTypes":["long-polling"]}]`;
  2. `[{"channel":"/meta/connect","clientId":"<clientId>","connectionType":"long-polling"}]`;
  3. `[{"channel":"/meta/subscribe","clientId":"<clientId>","subscription":"/services/wmqExample/enqueue#<clientId>"}]`, the `replyTo` channel `mule.rpc` subscribes to;
  4. `[{"channel":"/services/wmqExample/enqueue","clientId":"<clientId>","data":{"data":"hello","replyTo":"/services/wmqExample/enqueue#<clientId>"}}]`, the publication `mule.rpc` builds: its `data` wraps the text and the `replyTo` channel. This last message is the scenario's own `request`; steps 1 to 3 are its prior requests (`state.requests`).
- `B2` is the sequence the page's `init` runs to receive the dequeue channel (`mule.subscribe("/services/wmqExample/dequeue", ...)`, `websphere-mq/src/main/app/docroot/index.html:23-26`), sent in the same form once the message is on `out`:
  1. the handshake of `B1` step 1;
  2. the `/meta/connect` of `B1` step 2;
  3. `[{"channel":"/meta/subscribe","clientId":"<clientId>","subscription":"/services/wmqExample/dequeue"}]`;
  4. the `/meta/connect` of `B1` step 2 again, which the server answers with what it holds for the client, the held dequeue message included.

Notes for this project:

- Facts behind the rows, read from the original source:
  - Flow `Input` deletes `MULE_REPLYTO` before the WMQ send, and the connector sets `disableReplyTo="true"` (`websphere-mq/src/main/app/websphere-mq.xml:7,10-12`), so an enqueue publication is never answered on its `replyTo` channel.
  - The `test:component` sets `appendString=" - processed"`, `waitTime="15000"` and `logMessageDetails="true"` (`websphere-mq/src/main/app/websphere-mq.xml:19`). No flow declares a `logger` element or an exception strategy.
  - The `wmq:connector` reads `wmq.host`, `wmq.port`, `wmq.channel`, `wmq.queue` (its `queueManager`), `wmq.username` and `wmq.password` and sets `validateConnections="true"` (`websphere-mq/src/main/app/websphere-mq.xml:4`), so the application does not start without a reachable queue manager. `websphere-mq/src/main/app/mule-app.properties` defines none of the keys.
  - The original `pom.xml` declares the IBM MQ client JARs in test scope (`websphere-mq/pom.xml:107-124`), so the deployable archive does not carry them; the original README asks for them on the runtime (`websphere-mq/README.md:26,31`).
- State rules:
  - `ajax-enqueue-to-in-queue` and `mq-out-to-ajax-dequeue` are `nondeterministic` because their Bayeux exchange depends on the `clientId` the server generates at the handshake.
  - `get-services-wmqexample-page` (the page bytes) and `mq-in-processed-to-out` (the queue messages) are `deterministic`.
- Capture prerequisite: every scenario stays `UNVERIFIED-uncaptured` until both a local IBM MQ queue manager with queues `in` and `out` and the Tier 2A prerequisites exist (AAP 0.8.8). Because of `validateConnections="true"`, even `get-services-wmqexample-page` cannot be captured without a queue manager: a scenario whose server cannot be provided locally stays uncaptured and counts as unverified (AAP 0.8.4).
- Replay setting (replay notes, not expected values):
  - Replay runs without IBM MQ. `wmq.host` is empty by default, so `config/IbmMqConfig` defines no IBM MQ factory and Boot's embedded Artemis (test-scope `artemis-jakarta-server` in the project `pom.xml`) carries queues `in` and `out`. The replay boundary still rejects the empty host key, so every fixture carries the `state.properties` override of the Fixture contract, which keeps the IBM MQ factory off.
  - The page and the Bayeux endpoint `/ajax/cometd` are replayed on the random test port through ST-1 `support/HttpTestClient`; `request.port` records 8086.
  - `ajax.disable-reply-to: true` in `src/main/resources/application.yml` (SC-12) reproduces the absent RPC reply, and the queue keys `wmq.queues.in` and `wmq.queues.out` default to the original's `in` and `out`, so neither needs an override.
  - The `out` message of `mq-in-processed-to-out` appears no sooner than the 15000 ms processing wait (`wmq.processing.wait-ms`) after the put, so its fixture sets `awaitMillis` to `30000`.
- Replay gaps: three limits of the copied ST-2 driver keep three rows from a faithful replay, and none can be worked around without editing the original or the copied test:
  - Bayeux client ID: the replay sends the earlier messages of `B1` verbatim from `state.requests` and discards their replies, so they carry the client ID the original issued, which the Java broker does not know: `service/AjaxChannelBroker` issues its own random client IDs, and the driver cannot carry a server-issued value from one reply into later requests. This is the gap `service-orchestration-and-choice-routing-java` reports for its Ajax rows; it affects `ajax-enqueue-to-in-queue` and `mq-out-to-ajax-dequeue`.
  - Stopped consumer: the capture stops flow `MessageProcessor` or flow `Output` so that the queue it browses keeps the message (Capture step 4). The replay runs the whole application context, whose `trigger/InQueueListener` and `trigger/OutQueueListener` start with it and which no configuration key stops, so they take the message off `in` or `out` before the browse. This affects `ajax-enqueue-to-in-queue` and `mq-in-processed-to-out`.
  - Requests after a message input: `mq-out-to-ajax-dequeue` shows its effect in the answers to `B2`, sent after the message is on `out`. The fixture contract sends HTTP requests only before the input (`state.requests`) and gives an `mq` row no HTTP response, so the dequeue delivery cannot be compared on replay.
  - While a row's gap stands, the row keeps its capture evidence in `src/test/capture/<scenario>/` but gets no fixture, so it reports `UNVERIFIED-uncaptured` rather than a `FAILED` that would come from the replay, not from the conversion. The gaps are reported against the ST-2 reference, `java-conversions/hello-world-java/src/test/java/com/mulesoft/examples/hello_world/FixtureParityTest.java`; the copied test is not changed. `get-services-wmqexample-page` is the one row that can be captured into a fixture and become `VERIFIED`.
- Unverified rows: the two `nondeterministic` rows are compared for structure only whenever they are replayed, are reported `UNVERIFIED — nondeterministic output`, and are never presented as byte-for-byte evidence. They and the rows a gap leaves without a fixture are escalated as AAP 0.8.8 sets out:
  - Through `DECISIONS.md`, the person who receives the Tier 2A notification receives their list with the reasons above: nondeterministic output, no local queue manager, or a replay gap.
  - A row's status changes only on that person's input. A reproducibility input, such as a driver that carries the Bayeux client ID, stops a listener or sends requests after a message input, leads to a new capture compared byte for byte. A recorded acceptance is shown in the README as `ACCEPTED — structural comparison` and still counts as unverified.
- Parity label:
  - Until fixtures are captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7), never PASS. It is the label of `java-conversions/websphere-mq-java/README.md` and of this project's row in the root `SUMMARY.md`, and both list the four scenarios as unverified.
  - After capture the label is at most `PARITY: PARTIAL — Tier 2A fixtures: 1 verified, 3 unverified` while the gaps stand, or `PARITY: FAIL — Tier 2A fixtures` when the captured page differs.
  - The fallback evidence is `WebsphereMQTest`, which ports the three original `runFlow("Input")`, `runFlow("MessageProcessor")` and `runFlow("Output")` assertions, each returning payload `test` with the MQ and Ajax sides mocked (`websphere-mq/src/test/java/org/mule/examples/WebsphereMQIT.java:40-54`), and `WebsphereMqBrokerTest`, the end-to-end embedded-Artemis scenario: enqueue, then the processed text on the dequeue channel, and no RPC reply on the publisher's `replyTo`. This evidence is weaker than captured fixtures: the original test mocks every outbound endpoint, so no original assertion covers the real queue send and receive, the 15000 ms wait, the ` - processed` text, the absent reply or the held dequeue message.
- This file holds no expected response value, no message body beyond the inputs listed, and no credential, user name, password, host name or queue-manager name. The `wmq.*` keys are named only.

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
- Every row of this file is captured against the unmodified original deployed with its `wmq.*` placeholders pointed at a local IBM MQ queue manager: the `http` rows by sending their request to the Ajax connector on port 8086, the `mq` rows by putting their message on the named queue (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8086` in this project). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.
- `readTimeoutMillis`: optional; the replay's socket read budget for this request, a positive whole number of milliseconds, applied through ST-1's `HttpTestClient.withReadTimeout` to every read of the reply, the TLS handshake's included; default 10000, ST-1's `TIMEOUT_MILLIS`, which also stays the connect timeout. Like `awaitMillis`, it is a replay setting, not a captured value: a fixture sets it when the endpoint itself waits about 10000 ms or longer before it answers, so that the replay reads the captured answer instead of timing out before the comparison.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.
- For this project:
  - The two `http` rows record `"port": 8086`, the port of the Ajax connector's `serverUrl` (`websphere-mq/src/main/app/websphere-mq.xml:7`), and leave `portProperty` at its default `local.server.port`, the converted project's only listener.
  - The two `mq` rows hold `message` with `destination` `in` or `out`, `pubSub` `false`, the text of Capture step 4 as `body` and `properties` `{}`, since the message `amqsput` puts carries no message property. No row uses `invoke`: `trigger/InQueueListener` and `trigger/OutQueueListener` consume their queue themselves.
  - `mq-in-processed-to-out` sets `awaitMillis` to `30000`: its `out` message appears no sooner than the 15000 ms processing wait (`wmq.processing.wait-ms`, the original `waitTime`, `websphere-mq/src/main/app/websphere-mq.xml:19`) after the put, so the default would end the wait first. It and the `state` override below are the only values of these fixtures that the capture does not produce.

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
- For this project, every fixture carries `"properties": {"wmq.enabled": "false", "wmq.host": "localhost", "wmq.port": "${freePort:wmq}"}` in `state`, and `ajax-enqueue-to-in-queue` also carries steps 1 to 3 of `B1` as `state.requests`:
  - `wmq.host` is empty in `src/main/resources/application.yml`, so `config/IbmMqConfig` defines no IBM MQ factory and Boot's embedded Artemis carries queues `in` and `out`. The replay boundary below nevertheless rejects an empty host key, because a client would fall back to a default host. The override names a loopback host on a replay-owned port, and `wmq.enabled` `false` keeps the IBM MQ factory off once `wmq.host` has text, so the replay still runs on embedded Artemis and never opens an IBM MQ connection. The keys are not listed in `notContacted`, because the original did connect to its queue manager.
  - `wmq.channel`, `wmq.queue`, `wmq.username` and `wmq.password` stay empty: the boundary checks none of them, and the replay never reaches the IBM MQ client.
  - The empty queues of `Seeded state` need no key: before the input the replay drains every queue of `response.destinations`, which the reset that ends every scenario drains again.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7 of the reference format): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project: its flows make no HTTP or SOAP outbound call, and their queue sends and Ajax publications are effects recorded under `response`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. No fixture of this project omits it, because its flows make no exchange to record.

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
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match and compares it again 500 ms later, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.
- For this project, `response` holds:
  - for the two `http` rows, `status`, `reason`, `headers` and `body` or `bodyBase64` of the scenario's own request (Capture step 6);
  - for `ajax-enqueue-to-in-queue` and both `mq` rows, `messages` and `destinations` from the queue browse of Capture step 4: `destinations` is `[{"destination": "in", "pubSub": false}]` for `ajax-enqueue-to-in-queue`, `in` and then `out` for `mq-in-processed-to-out`, and `out` for `mq-out-to-ajax-dequeue`, whatever they held;
  - no `logs`: the flows declare no `logger` element, and the `test:component` detail log (`logMessageDetails="true"`) is written under the Mule test framework's own logger category, under which no converted class logs.

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
- For this project the replay's broker is the in-VM Artemis server of `artemis-jakarta-server` (test scope in the project `pom.xml`), which the `mq` driver, and the queue browse of the `http` row that lists `destinations`, reach through the application's connection factory. With the `state` override above the boundary finds no other target in `src/main/resources/application.yml`: the project has no HTTP upstream, DataSource, mail, MongoDB or LDAP client, and `server.address` `0.0.0.0` is a loopback host.

Skeletons of an `http` fixture and of an `mq` fixture of this project; every `request` and `response` value marked `<captured>` comes from the capture, and `state` and `awaitMillis` are written as the sections above define:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>", "port": 8086 },
  "state":    { "properties": { "wmq.enabled": "false", "wmq.host": "localhost",
                                "wmq.port": "${freePort:wmq}" } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

```json
{
  "request":  { "message": { "destination": "in", "pubSub": false,
                             "body": "<captured>", "properties": {} },
                "awaitMillis": 30000 },
  "state":    { "properties": { "wmq.enabled": "false", "wmq.host": "localhost",
                                "wmq.port": "${freePort:wmq}" } },
  "upstream": [],
  "response": { "messages": [{ "destination": "out", "pubSub": false,
                               "body": "<captured>", "properties": { "<captured>": "<captured>" } }],
                "destinations": [{ "destination": "in", "pubSub": false },
                                 { "destination": "out", "pubSub": false }] }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.
- In this project `get-services-wmqexample-page` and `mq-in-processed-to-out` are `deterministic`: the page's status, reason, `Content-Type` and bytes, and the text and listed properties of every browsed message, are compared exactly. `ajax-enqueue-to-in-queue` and `mq-out-to-ajax-dequeue` are `nondeterministic`, because their Bayeux exchange depends on the `clientId` the server generates: their bodies and messages are compared for structure only, and they are reported `UNVERIFIED — nondeterministic output`.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `get-services-wmqexample-page`, `ajax-enqueue-to-in-queue`, `mq-in-processed-to-out` and `mq-out-to-ajax-dequeue` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The current label of this project is `PARITY: UNVERIFIED — Mule runtime unavailable` (Notes for this project).

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `prior-<n>-curl-trace.txt`, for a scenario with prior requests (step 4), the trace of its `n`-th prior request. In this project the evidence also holds `body.txt` and `prior-<n>-body.txt`, the form bodies curl sent; `put.txt`, the line `amqsput` put on a queue; `depths-before.txt`, the queue depths read before the input; `in-browse.txt` and `out-browse.txt`, the browse of each queue read after the run; and, for `mq-out-to-ajax-dequeue`, the requests of `B2` and their answers as `after-<n>-body.txt`, `after-<n>-curl-trace.txt` and `after-<n>-response.http`. Working files stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/websphere-mq-java/`).

1. Build the unmodified original `websphere-mq` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams are skipped for websphere-mq: its flows make no HTTP or SOAP outbound call, and their sends go to the queue manager below and to Ajax subscribers, captured as effects (steps 4 and 6). Provide a local IBM MQ queue manager with a server-connection channel and local queues `in` and `out`, the objects the original README requires (`websphere-mq/README.md:25,32`). `QMGR` below holds its name. The commands use the IBM MQ sample programs `amqsput`, `amqsget` and `amqsbcg` and the `runmqsc` command on the queue manager's host, or their client forms (`amqsputc`, `amqsgetc`, `amqsbcgc`) with `MQSERVER` set. The queue manager's name, host, port and channel and the account the runtime connects with are local values: they are passed at deployment only (step 3), never written into this file, a fixture or the evidence.
3. Deploy the archive to a licensed Mule 3.8 EE runtime with the IBM MQ client JARs on its classpath, for example in `$MULE_HOME/lib/user`, because the archive does not carry them (Notes for this project). Point the original's placeholders at the queue manager of step 2 through runtime properties only, for example `-M-Dwmq.host=<value>`: `wmq.host`, `wmq.port`, `wmq.channel`, `wmq.queue` (the queue manager name), `wmq.username` and `wmq.password`. The runtime supplies `app.home` for the docroot (`websphere-mq/src/main/app/websphere-mq.xml:7`). Port 8086 must be free on the capture host, and the deployment must have started, which `validateConnections="true"` allows only once the queue manager is reachable, before the first row, the page row included. The runtime's management or JMX agent must be reachable from the capture host, so step 4 can stop a flow and start it again.
4. Run the scenarios in table order with the helpers below. `-sS -v` sends each trace and any error to its trace file, and a non-zero exit status means the capture failed. `--http1.1` keeps every request on HTTP/1.1, whose status line carries the reason phrase.
   - Before each `mq` row and before `ajax-enqueue-to-in-queue`, `drain` empties the queues the row's `Seeded state` names, discarding what it reads, and `depths` keeps the depths of `in` and `out` as `depths-before.txt`. A row whose `depths-before.txt` shows a message is captured again after a new drain.
   - Each Bayeux request body is written first, as `--data-urlencode` would encode it, and sent with `--data-binary`, so the evidence holds the exact bytes. `form` writes `message=` and the URL-encoded message array; the bodies of `B1` and `B2` are built from the handshake answer, which is kept under `target/fixture-capture/` only.
   - `put` puts one text message: `amqsput` sends each line it reads as one message, without the line ending, and sets no message property.
   - `browse` keeps each message `amqsbcg` browses, without consuming it, as `<queue>-browse.txt`, leaving out each message-descriptor block. That block carries the message ID, the correlation ID, the put date and time, the queue manager name and the putting user, none of which is captured.
   - The original flows consume `in` and `out` all the time. While `ajax-enqueue-to-in-queue` is captured, flow `MessageProcessor` is stopped, and while `mq-in-processed-to-out` is captured, flow `Output` is stopped, each through the runtime's management or JMX agent, so the message stays on its queue for the browse. The row then drains that queue and starts the flow again, so the message reaches no later row.

   ```sh
   F=target/fixture-capture; mkdir -p "$F"
   cometd=http://localhost:8086/ajax/cometd; CT='Content-Type: application/x-www-form-urlencoded'
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   prior() { d="src/test/capture/$1"; n=$2; shift 2; mkdir -p "$d"; curl -sS -v --http1.1 -o /dev/null "$@" 2> "$d/prior-$n-curl-trace.txt"; }
   drain() { for q in "$@"; do amqsget "$q" "$QMGR" > /dev/null; done; }
   depths() { mkdir -p "src/test/capture/$1"; printf 'DISPLAY QLOCAL(in) CURDEPTH\nDISPLAY QLOCAL(out) CURDEPTH\n' | runmqsc "$QMGR" | grep -E 'QUEUE\(|CURDEPTH\(' > "src/test/capture/$1/depths-before.txt"; }
   put() { printf '%s\n' "$3" | tee "src/test/capture/$1/put.txt" | amqsput "$2" "$QMGR" > /dev/null; }
   browse() { amqsbcg "$2" "$QMGR" | awk '/\*\*\*\*Message descriptor\*\*\*\*/ {skip = 1; next} /\*\*\*\*/ {skip = 0} !skip' > "src/test/capture/$1/$2-browse.txt"; }
   form() { mkdir -p "$(dirname "$1")"; jq -rjn --arg v "$2" '"message=" + ($v | @uri)' > "$1"; }
   handshake() { form "$2" '[{"channel":"/meta/handshake","version":"1.0","supportedConnectionTypes":["long-polling"]}]'; curl -sS -v --http1.1 -o "$F/$1-handshake.json" -H "$CT" --data-binary "@$2" "$cometd" 2> "$3"; CID=$(jq -er '.[0].clientId' "$F/$1-handshake.json"); }
   connect() { jq -nc --arg c "$CID" '[{channel: "/meta/connect", clientId: $c, connectionType: "long-polling"}]'; }
   subscribe() { jq -nc --arg c "$CID" --arg s "$1" '[{channel: "/meta/subscribe", clientId: $c, subscription: $s}]'; }
   after() { form "$d/after-$1-body.txt" "$2"; curl --raw -i -sS -v --http1.1 -H "$CT" --data-binary "@$d/after-$1-body.txt" "$cometd" > "$d/after-$1-response.http" 2> "$d/after-$1-curl-trace.txt"; }

   capture get-services-wmqexample-page http://localhost:8086/services/wmqExample/

   # Flow MessageProcessor is stopped before these lines and started again after them.
   S=ajax-enqueue-to-in-queue; d="src/test/capture/$S"; drain in; depths "$S"
   handshake "$S" "$d/prior-1-body.txt" "$d/prior-1-curl-trace.txt"; R="/services/wmqExample/enqueue#$CID"
   form "$d/prior-2-body.txt" "$(connect)"; prior "$S" 2 -H "$CT" --data-binary "@$d/prior-2-body.txt" "$cometd"
   form "$d/prior-3-body.txt" "$(subscribe "$R")"; prior "$S" 3 -H "$CT" --data-binary "@$d/prior-3-body.txt" "$cometd"
   form "$d/body.txt" "$(jq -nc --arg c "$CID" --arg r "$R" '[{channel: "/services/wmqExample/enqueue", clientId: $c, data: {data: "hello", replyTo: $r}}]')"
   capture "$S" -H "$CT" --data-binary "@$d/body.txt" "$cometd"
   sleep 10; browse "$S" in; drain in

   # Flow Output is stopped before these lines and started again after them.
   S=mq-in-processed-to-out; drain in out; depths "$S"
   put "$S" in 'hello'; sleep 30; browse "$S" in; browse "$S" out; drain out

   S=mq-out-to-ajax-dequeue; d="src/test/capture/$S"; drain out; depths "$S"
   put "$S" out 'hello - processed'; sleep 2
   handshake "$S" "$d/after-1-body.txt" "$d/after-1-curl-trace.txt"
   after 2 "$(connect)"; after 3 "$(subscribe /services/wmqExample/dequeue)"; after 4 "$(connect)"
   browse "$S" out
   ```

   `ajax-enqueue-to-in-queue` browses `in` 10000 ms after the publication, the enqueue endpoint's `responseTimeout` (`websphere-mq/src/main/app/websphere-mq.xml:9`). `mq-in-processed-to-out` browses 30000 ms after the put, twice the processing wait. `mq-out-to-ajax-dequeue` subscribes 2 s after the put, once flow `Output` has taken the message and holds it for a subscriber; its handshake answer is a working file, and its `after-2` to `after-4` answers are the evidence of the delivery.

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the content of the `@file` of `--data-binary`, here `body.txt`); and `"port": 8086`. `state.requests` comes the same way from the `prior-<n>-curl-trace.txt` and `prior-<n>-body.txt` files, one object per `n` in its order, without `port`. For the `mq` rows, `request.message` comes from `put.txt`: `destination` the queue, `pubSub` `false`, the line without its line ending as `body` and `properties` `{}`.
6. `response` of the `http` rows comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-services-wmqexample-page/response.http GET <<'PY'
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

   `response.messages` and `response.destinations` come from the browse files of step 4, for `ajax-enqueue-to-in-queue` and both `mq` rows: one `messages` entry per browsed message, in browse order, with `destination` the queue, `pubSub` `false`, the message bytes the browse prints as `body` when they are valid UTF-8 and as `bodyBase64` otherwise, and as `properties` the message properties the browse lists (the `usr` folder of an MQRFH2 header), `{}` when it lists none; `destinations` lists the browsed queues in browse order, those found empty included: `in` for `ajax-enqueue-to-in-queue`, `in` and then `out` for `mq-in-processed-to-out`, and `out` for `mq-out-to-ajax-dequeue`. Properties that the Mule runtime rather than the flow attaches to a message, such as `MULE_ROOT_MESSAGE_ID` or a serialized `MULE_SESSION`, are per-run identifiers and are not captured, like the message ID, correlation ID and put date and time of step 4 (AAP 0.8.4). Every other property is captured exactly as it appears; a mismatch that results is a Tier 2A finding handled under the integrity rule. No `logs` entry is captured (Fixture contract).
7. `upstream` is `[]` for every scenario of this project, whose flows make no HTTP or SOAP outbound call.
8. Write `fixtures/<scenario>.json` from `request` (step 5, with `awaitMillis` `30000` for `mq-in-processed-to-out`), `state` (the override of the Fixture contract, with `state.requests` for `ajax-enqueue-to-in-queue`), `upstream` (step 7) and `response` (step 6), every value as produced, for each row whose replay gap does not stand (Notes for this project); a row whose gap stands keeps only its evidence. Before committing, `grep -rF -e "<local value>" src/test/resources/fixtures src/test/capture` runs for every value step 3 passed for `wmq.host`, `wmq.channel`, `wmq.queue`, `wmq.username` and `wmq.password`: a match means that file is kept under `target/fixture-capture/` only and its scenario gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code: for `get-services-wmqexample-page`, SC-12 `config/AjaxResourceConfig` and the copied page; for `ajax-enqueue-to-in-queue`, `client/MqQueueSender` and the SC-12 `controller/BayeuxLongPollingController` and `service/AjaxChannelBroker`; for `mq-in-processed-to-out`, `trigger/InQueueListener`, `service/MqProcessingService` and `client/MqQueueSender`; for `mq-out-to-ajax-dequeue`, `trigger/OutQueueListener` and `service/AjaxChannelBroker`. An SC-12 class is changed in its first consumer, `service-orchestration-and-choice-routing-java`, and copied here with only the `package` line changed (AAP 0.4.4). No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
