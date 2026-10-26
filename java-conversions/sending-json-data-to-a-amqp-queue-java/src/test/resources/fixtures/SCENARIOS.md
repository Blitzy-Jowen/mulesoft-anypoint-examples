# Parity scenarios — sending-json-data-to-a-amqp-queue-java

Scenarios for the fixture replay (ST-2) of `sending-json-data-to-a-amqp-queue-java`. The original example is `sending-json-data-to-a-amqp-queue` (folder `sending-json-data-to-a-amqp-queue/`). Its single flow `json-to-rabbitmqFlow` accepts `POST` on the `/` listener, logs the payload and publishes it request-response to exchange `sales_exchange`, queue `sales_queue`, waiting up to 10000 ms for a reply. Both branches listed for the example in AAP 0.7.5 (a reply arrives, or none arrives within the timeout) are one scenario each below, as are the two listener-level defaults that its `allowedMethods` and its path select; each scenario's captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `post-root-no-reply` | `http` | `deterministic` | `POST /`, header `Content-Type: text/plain`, body `test` | topic exchange `sales_exchange` and durable queue `sales_queue` bound with `#`, as the original test declares them; the queue is empty and has no consumer | `/` listener → logger → request-response publish to `sales_exchange`/`sales_queue` → no reply within `responseTimeout` 10000 ms; the published message on `sales_queue` is a captured effect; the no-reply result is a Tier 2A verification point (AAP 0.7.5) where the captured bytes govern | `sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:6-9`, `sending-json-data-to-a-amqp-queue/src/test/java/org/mule/examples/SendingJsonDataToAmqpQueueIT.java:71-75` |
| `post-root-reply` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{"ITEM_ID"= 001, "ITEM_NAME" = "Shirt", "QTY" = 1, "PRICE" = 20}` (the README sample, byte for byte) | as `post-root-no-reply`, plus a responder that takes the request off `sales_queue` and publishes the message `reply` to its `replyTo` | `/` listener → logger → request-response publish → the responder's reply arrives within `responseTimeout` and the flow returns it | `sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:9`, `sending-json-data-to-a-amqp-queue/README.md:32` |
| `get-root-method-not-allowed` | `http` | `deterministic` | `GET /`, empty body | none | listener `allowedMethods="POST"` rejects the method → platform 405 default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:6` |
| `post-unknown-path` | `http` | `deterministic` | `POST /sales`, empty body | none | no listener serves `/sales` → platform 404 default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:6` |

No scenario of this project is `unreachable` or `nondeterministic`.

Both `POST /` rows send their `Content-Type` explicitly. Without it, curl sends `--data-binary` as `application/x-www-form-urlencoded`, which the Mule 3.8 HTTP listener decodes into a parameter map instead of passing the bytes on, so the flow would not take the branch the row names.

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
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`).
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource}`, the classpath Qpid Broker-J configuration that declares the broker objects and messages.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For this project, the two `POST /` fixtures carry the same `state`, and the two listener-default fixtures carry `{}` because their requests never reach the flow:
  - `amqp.initialConfigurationResource` names a Qpid Broker-J 9.2.1 initial configuration that declares exactly what Capture step 4 seeds: the non-durable topic exchange `sales_exchange`, the durable queue `sales_queue` bound with `#`, and no message. It reuses the `guest` user and the `${qpid.amqp_port}` port of the project's `src/test/resources/config.json`. It is committed in `fixtures/broker/`, a subdirectory, because `classpath*:fixtures/*.json` would load a file directly in `fixtures/` as a fixture without a row and fail the class.
  - `properties` is `{"spring.rabbitmq.port": "${freePort:amqp}"}`, the port the replay's broker listens on. Host and user need no override: `spring.rabbitmq.host` defaults to `localhost` through `amqp.host`, and Spring's `guest`/`guest` matches the broker user.
  - The responder of `post-root-reply` is seeded state that none of the keys above expresses. Replaying that fixture needs an equivalent responder on the embedded broker, which the replay engine must provide before the scenario can be `VERIFIED`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project: its flow makes no HTTP or SOAP call, and its AMQP publish is a broker effect recorded under `response.messages`.
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, in arrival order; for `amqp`, `destination` is the queue. Only the listed properties are compared, and no further message may follow.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the sent or received mails as raw MIME.
  - `logs`: `[{logger, level, message}]`, matched as an ordered subsequence of the logged events.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`.
- `${freePort:<name>}`: a free port, the same one for every use of the same name.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`.
- `${mongo.uri}` and `${ldap.port}`.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Skeleton of a `POST /` fixture of this project; every `request` and `response` value comes from the capture, and `state` is written as Capture step 8 defines:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>" },
  "state":    { "properties": { "spring.rabbitmq.port": "${freePort:amqp}" },
                "amqp": { "initialConfigurationResource": "fixtures/broker/<seeded broker>.json" } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>",
                "messages": [{ "destination": "sales_queue", "pubSub": false,
                               "bodyBase64": "<captured>", "properties": { "<captured>": "<captured>" } }] }
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists `post-root-no-reply`, `post-root-reply`, `get-root-method-not-allowed` and `post-unknown-path` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. For this project the evidence also holds `sales_queue.json`, the messages browsed from `sales_queue` after the scenario, and, for `post-root-reply`, `request-message.json`, the request the responder took off the queue. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/sending-json-data-to-a-amqp-queue-java/`).

1. Build the unmodified original `sending-json-data-to-a-amqp-queue` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for sending-json-data-to-a-amqp-queue, whose flow makes no HTTP or SOAP outbound call: its AMQP publish goes to the broker of step 3 and is captured as a broker effect (steps 4 and 6). Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
3. Start a local RabbitMQ broker with its management plugin, as the original README assumes (`sending-json-data-to-a-amqp-queue/README.md:8`), listening on `localhost:5672` with the default `guest`/`guest` user and virtual host `/`. The original `amqp:connector` sets no host, port, user or virtual host (`sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:3`), so it connects to those Mule AMQP defaults; `amqp.host` is only the original test's property for its own client (`sending-json-data-to-a-amqp-queue/src/test/java/org/mule/examples/SendingJsonDataToAmqpQueueIT.java:131`). A Qpid Broker-J broker serves the same AMQP 0-9-1 role but needs its own equivalents of the management commands of step 4. Then deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.port=8081` as a runtime property (for example `-M-Dhttp.port=8081`), because `sending-json-data-to-a-amqp-queue/src/main/app/mule-app.properties` defines no value, together with the upstream properties of step 2 for an original that has upstreams.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   mq() { local method=$1 path=$2; shift 2; curl -sS -u guest:guest -H 'Content-Type: application/json' -X "$method" "http://localhost:15672/api$path" "$@"; }
   seed() {
     mq DELETE /exchanges/%2F/sales_exchange -o /dev/null; mq DELETE /queues/%2F/sales_queue -o /dev/null
     mq PUT /exchanges/%2F/sales_exchange -f -d '{"type": "topic", "durable": false}'
     mq PUT /queues/%2F/sales_queue -f -d '{"durable": true}'
     mq POST /bindings/%2F/e/sales_exchange/q/sales_queue -f -d '{"routing_key": "#"}'
   }
   browse() { mq POST /queues/%2F/sales_queue/get -f -d '{"count": 1000, "ackmode": "ack_requeue_true", "encoding": "base64"}' > "src/test/capture/$1/sales_queue.json"; }
   respond() {
     local m=
     for _ in $(seq 100); do
       m=$(mq POST /queues/%2F/sales_queue/get -f -d '{"count": 1, "ackmode": "ack_requeue_false", "encoding": "base64"}') && [ "$m" != "[]" ] && break
       m=; sleep 0.1
     done
     [ -n "$m" ] || { echo 'no request reached sales_queue' >&2; return 1; }
     mkdir -p src/test/capture/post-root-reply; printf '%s\n' "$m" > src/test/capture/post-root-reply/request-message.json
     jq -c '.[0].properties | {routing_key: .reply_to, payload: "reply", payload_encoding: "string", properties: ({correlation_id} | with_entries(select(.value != null)))}' <<< "$m" \
       | mq POST /exchanges/%2F/amq.default/publish -f -d @-
   }
   seed; capture post-root-no-reply -X POST -H 'Content-Type: text/plain' --data-binary 'test' http://localhost:8081/; browse post-root-no-reply
   seed; respond & R=$!
   capture post-root-reply -X POST -H 'Content-Type: application/json' --data-binary '{"ITEM_ID"= 001, "ITEM_NAME" = "Shirt", "QTY" = 1, "PRICE" = 20}' http://localhost:8081/
   wait "$R"; browse post-root-reply
   capture get-root-method-not-allowed http://localhost:8081/
   capture post-unknown-path -X POST http://localhost:8081/sales
   ```

   `seed` recreates, before each `POST /` scenario, the objects the original test declares (`sending-json-data-to-a-amqp-queue/src/test/java/org/mule/examples/SendingJsonDataToAmqpQueueIT.java:79-84`): a non-durable topic exchange `sales_exchange`, a durable queue `sales_queue` and their binding `#`, with no consumer. `respond`, started in the background before the `post-root-reply` request, polls `sales_queue` every 100 ms for up to 10 s, takes the flow's request off it, keeps it as evidence and publishes `reply` through the default exchange to the request's `replyTo`, copying its `correlation_id` when it has one. `browse` runs after curl has returned, so after the flow has answered, and reads every message left on `sales_queue` with `ack_requeue_true`, so the queue is browsed, not consumed. The two listener-default requests are answered before the flow runs, so they are sent without seeding.

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
   python3 - src/test/capture/post-root-no-reply/response.http POST <<'PY'
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

   For the two `POST /` scenarios, `response.messages` comes from `sales_queue.json`: one entry per browsed message in queue order, with `destination` `sales_queue`, `pubSub` `false`, the message bytes as `bodyBase64` and its AMQP application headers (`properties.headers`) as `properties`. Only the application headers are recorded; message IDs, timestamps and the other standard AMQP properties are not captured (AAP 0.8.4). A queue left empty gives `"messages": []`, which the replay reads as no message on `sales_queue`. This command prints the array:

   ```sh
   jq '[.[] | {destination: "sales_queue", pubSub: false, bodyBase64: .payload, properties: (.properties.headers // {})}]' src/test/capture/post-root-no-reply/sales_queue.json
   ```

7. `upstream` is `[]` when the original made no HTTP or SOAP outbound call, as in every scenario of this project, whose only outbound exchange is the AMQP publish of step 6. Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (`{}` for the two listener-default scenarios; for the two `POST /` scenarios, the `state` the Fixture contract defines, with the broker configuration written once into `fixtures/broker/` to declare what `seed` created), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `get-root-method-not-allowed` and `post-unknown-path`, that means SC-1 `GlobalExceptionHandler`, and for the two `POST /` scenarios `controller/SalesController`, `service/SalesPublisherService` and `config/AmqpConfig`. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- What the original answers when no reply arrives within `responseTimeout` is a Tier 2A verification point (AAP 0.7.5): its status, reason, headers and body come only from the `post-root-no-reply` capture, and this file states none of them.
- If a captured response or message carries a value the original generates per run (a Mule message or correlation ID, a reply-queue name, a time), the row's State becomes `nondeterministic` and its `Branch` names that value, as AAP 0.8.4 defines; the captured value itself is never removed or edited.
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
