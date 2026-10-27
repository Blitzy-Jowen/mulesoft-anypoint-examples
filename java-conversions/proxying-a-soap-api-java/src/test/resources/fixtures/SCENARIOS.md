# Parity scenarios — proxying-a-soap-api-java

Scenarios for the fixture replay (ST-2) of `proxying-a-soap-api-java`. The original example is `proxying-a-soap-api` (folder `proxying-a-soap-api/`). Its single flow `main` answers every HTTP request on the `/` listener: `cxf:proxy-service` takes the caller's SOAP envelope as it arrived and publishes the upstream ShopService WSDL at `?wsdl`, the `copy-headers` sub-flow copies the caller's headers, `cxf:proxy-client` and `http:request` POST the envelope to `shop/ShopService` on `www.predic8.com:8080`, and `copy-headers` copies the response headers back. The proxied call and the `?wsdl` publication that AAP 0.7.5 lists, and three further outcomes of the listener (an upstream status of 400 or above failing the default success-status validator, a request without an envelope, and a path no listener serves), have one scenario each; each scenario's captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `post-root-create-cart` | `http` | `deterministic` | `POST /`, header `Content-Type: text/xml; charset=UTF-8`, body = the bytes of `message.xml` (`src/test/resources`) | `state.properties` sets `proxy.upstream-url` to `${wiremock.baseUrl}/shop/ShopService` and `proxy.wsdl-location` to `classpath:upstream/recorded/ShopService.wsdl` (notes below) | `/` listener → `cxf:proxy-service` takes the `create` envelope as sent → `copy-headers` copies the caller's headers → `cxf:proxy-client` → `http:request` POSTs the envelope to `shop/ShopService` → upstream status below 400 → `copy-headers` copies the response headers back → 200 with the upstream bytes; the recorded `upstream` holds the `create` call | `proxying-a-soap-api/src/main/app/soap-api-proxy.xml:6-11`, `proxying-a-soap-api/src/test/java/org/mule/examples/ProxyingSoapApiIT.java:52-64` |
| `post-root-upstream-fault` | `http` | `deterministic` | `POST /`, header `Content-Type: text/xml; charset=UTF-8`, body `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://predic8.com/wsdl/shop/1/"><soapenv:Header/><soapenv:Body><ns:unknownOperation/></soapenv:Body></soapenv:Envelope>` | `state.properties` sets `proxy.upstream-url` to `${wiremock.baseUrl}/shop/ShopService` and `proxy.wsdl-location` to `classpath:upstream/recorded/ShopService.wsdl` (notes below) | `/` listener → `copy-headers` → `http:request` POSTs an envelope whose body element names no ShopService operation, which the upstream answers with a status of 400 or above → the default success-status validator fails the flow, which declares no exception strategy → platform-default 500 (AAP 0.4.7), with no response header copied; a Tier 2A verification point where the captured bytes govern, and when the capture shows another outcome, such as CXF answering before the envelope is forwarded, only this Branch text is corrected | `proxying-a-soap-api/src/main/app/soap-api-proxy.xml:10` |
| `get-root-without-wsdl-query` | `http` | `deterministic` | `GET /`, empty body | `state.properties` sets `proxy.upstream-url` to `${wiremock.baseUrl}/shop/ShopService` and `proxy.wsdl-location` to `classpath:upstream/recorded/ShopService.wsdl` (notes below) | the `/` listener accepts every method because it declares no `allowedMethods` (AAP 0.4.9), so a `GET` without a `?wsdl` query enters the proxy path → `copy-headers` → `http:request` sends the one `POST` its `method` fixes, with no body, to `shop/ShopService`; a Tier 2A verification point where the captured bytes and the recorded `upstream` govern | `proxying-a-soap-api/src/main/app/soap-api-proxy.xml:6,10` |
| `get-root-wsdl` | `http` | `nondeterministic` | `GET /?wsdl`, empty body | `state.properties` sets `proxy.upstream-url` to `${wiremock.baseUrl}/shop/ShopService` and `proxy.wsdl-location` to `classpath:upstream/recorded/ShopService.wsdl` (notes below) | `cxf:proxy-service` publishes the upstream WSDL it read from `wsdlLocation` at deployment, with `soap:address` rewritten to the request URL and schema references at `?xsd=`; in the Java project SC-11 `WsdlQueryPublisher` publishes the WSDL read from `proxy.wsdl-location` at start-up the same way; neither application calls the upstream while answering; varies: the `soap:address` location carries the host and port the request names | `proxying-a-soap-api/src/main/app/soap-api-proxy.xml:7` |
| `get-unknown-path` | `http` | `deterministic` | `GET /other`, empty body | `state.properties` sets `proxy.upstream-url` to `${wiremock.baseUrl}/shop/ShopService` and `proxy.wsdl-location` to `classpath:upstream/recorded/ShopService.wsdl` (notes below) | no listener serves `/other`, because path `/` matches only the root → no-listener 404 platform default (AAP 0.4.7); a Tier 2A verification point where the captured bytes govern | `proxying-a-soap-api/src/main/app/soap-api-proxy.xml:6` |

No scenario of this project is `unreachable`. `get-root-wsdl` is its one `nondeterministic` scenario, because the WSDL it returns carries the request's host and port in `soap:address`: it is compared for status, reason and headers exactly and for body structure only, reported `UNVERIFIED — nondeterministic output` (summary status `UNVERIFIED-nondeterministic`), and escalated as AAP 0.8.8 sets out. The other four scenarios depend only on their request and the recorded ShopService response, which replay serves unchanged.

Notes for this project:

- Upstream redirection: the original hard-codes its upstream in three values, the `HTTP_Request_Configuration` host `www.predic8.com` and port `8080` (`proxying-a-soap-api/src/main/app/soap-api-proxy.xml:4`) and the `cxf:proxy-service` `wsdlLocation` `http://www.predic8.com:8080/shop/ShopService?wsdl` (`proxying-a-soap-api/src/main/app/soap-api-proxy.xml:7`). No runtime property reaches them, which under Capture step 2 alone would leave every scenario without `upstream`. The capture therefore sets all three to the WireMock recording proxy, host `localhost`, port `W` and `wsdlLocation` `http://localhost:<W>/shop/ShopService?wsdl`, in the deployed copy of the original only: the archive built in step 1 is copied under `target/fixture-capture/` and its `soap-api-proxy.xml` edited there. The original folder in the repository is never modified (AAP 0.2.4). WireMock's one proxy mapping forwards to `http://www.predic8.com:8080`.
- WSDL read at start-up: the original's `cxf:proxy-service` reads `wsdlLocation`, and the schemas it references, once when the application deploys, before any scenario runs, and answers `?wsdl` from that copy. The Java project does the same: `ProxyingASoapApiApplication` registers SC-11 `WsdlQueryPublisher` with the document `proxy.wsdl-location` names, read through `client/ShopServiceClient.wsdlResource()` when the context starts, and a WSDL it cannot read fails start-up. Replay starts that context before any scenario's `upstream` stubs are installed, so `proxy.wsdl-location` cannot name WireMock: every fixture seeds it with `classpath:upstream/recorded/ShopService.wsdl`, the WSDL the original read at deployment. The capture writes that copy from the deployment journal (Capture step 4): the recorded body bytes of the WSDL `GET`, unedited, as `src/test/resources/upstream/recorded/ShopService.wsdl`, and those of each schema `GET` the original made for a relative reference under that reference, resolved against the same directory. A reference with its own scheme and host is published as written and needs no file. The deployment journal is committed as `src/test/capture/get-root-wsdl/deployment-journal.json`. These files stand in for the upstream and are never edited to match the Java output.
- Recorded `upstream` of `get-root-wsdl`: it holds only the calls made while the scenario runs, as for every other row, so the deployment reads are never part of it. A call the original makes there, such as a schema fetched on request, is one the Java project does not make, and the scenario fails; the mismatch is fixed in the Java code.
- Unrecorded WSDL: when the deployment journal holds no WSDL `GET`, the `GET` carries a `Content-Encoding`, or a schema the WSDL references by a relative location was not recorded, `get-root-wsdl` gets no fixture and stays `UNVERIFIED-uncaptured`, and the other fixtures seed `proxy.wsdl-location` with the authored offline stand-in `classpath:upstream/ShopService.wsdl`, which none of their scenarios reads.
- Offline test default: the surefire configuration of `pom.xml` sets the system property `proxy.wsdl-location=classpath:upstream/ShopService.wsdl`, so ordinary test contexts start offline. That stand-in is authored for the unit tests and is not predic8's document. During replay the fixture's `state.properties`, registered through `@DynamicPropertySource`, take precedence over that system property and over `src/main/resources/application.yml`.
- Header rules: the SC-13 rules of `copy-headers` (`proxying-a-soap-api/src/main/app/soap-api-proxy.xml:13-21`) apply to every row in both directions: `Host`, `Content-Length`, `http.*`, `MULE_*`, `Connection` and `Transfer-Encoding` are removed, and every other header, a caller's `Authorization` and `SOAPAction` included, is copied unchanged.
- Live values: the original carries no credential and predic8's ShopService takes none, so no marker is needed; the `grep` of Capture step 8 still runs for any value supplied at capture time.
- Tier 2A verification points, where the captured fixture governs and the Java code is changed to match: the 500 body of `post-root-upstream-fault`, the forwarding of `get-root-without-wsdl-query`, and the 404 text of `get-unknown-path`.
- Parity label: until fixtures are captured, this project and each of its five scenarios carry `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7), the label of `java-conversions/proxying-a-soap-api-java/README.md`, which names `get-root-wsdl` and every uncaptured scenario as unverified, and of this project's row in the root `SUMMARY.md`.

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
- For proxying-a-soap-api, `state` is `{"properties": {"proxy.upstream-url": "${wiremock.baseUrl}/shop/ShopService", "proxy.wsdl-location": "classpath:upstream/recorded/ShopService.wsdl"}}` in every fixture: the original reaches no store, and `client/ShopServiceClient` binds both keys (defaults `http://www.predic8.com:8080/shop/ShopService` and `http://www.predic8.com:8080/shop/ShopService?wsdl` in `src/main/resources/application.yml`). The first points the proxied `POST` at the WireMock server that serves `upstream`; the second points the WSDL read at start-up at the copy recorded at deployment, as the notes under Scenarios explain.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. For proxying-a-soap-api no call is made for `get-unknown-path`, which no listener serves, or for `get-root-wsdl`, which both applications answer from the WSDL read at start-up; each other scenario's recorded `upstream` holds the proxied `POST` with ShopService's response.
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

Skeleton of a fixture of this project; every `request`, `upstream` and `response` value comes from the capture, and `state` is written as Capture step 8 defines:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>" },
  "state":    { "properties": { "proxy.upstream-url": "${wiremock.baseUrl}/shop/ShopService",
                                "proxy.wsdl-location": "classpath:upstream/recorded/ShopService.wsdl" } },
  "upstream": [{ "request":  { "method": "<captured>", "url": "<captured>",
                               "headers": [["<captured>", "<captured>"]], "bodyBase64": "<captured>" },
                 "response": { "status": "<captured>", "headers": [["<captured>", "<captured>"]],
                               "bodyBase64": "<captured>" } }],
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For proxying-a-soap-api the summary then lists `post-root-create-cart`, `post-root-upstream-fault`, `get-root-without-wsdl-query`, `get-root-wsdl` and `get-unknown-path` as `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/proxying-a-soap-api-java/`).

1. Build the unmodified original `proxying-a-soap-api` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; for proxying-a-soap-api, the one upstream is predic8's ShopService at `http://www.predic8.com:8080`, hard-coded in the original and redirected to WireMock in the deployed copy as the notes under Scenarios describe, with the proxy mapping of step 4. Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
3. Deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.port=8081` as a runtime property (for example `-M-Dhttp.port=8081`), because `proxying-a-soap-api/src/main/app/mule-app.properties` defines no value. The archive deployed is the copy whose upstream values the notes under Scenarios point at WireMock; the original has no upstream property to set (step 2).
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   capture post-root-create-cart -X POST -H 'Content-Type: text/xml; charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:8081/
   capture post-root-upstream-fault -X POST -H 'Content-Type: text/xml; charset=UTF-8' --data-binary '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://predic8.com/wsdl/shop/1/"><soapenv:Header/><soapenv:Body><ns:unknownOperation/></soapenv:Body></soapenv:Envelope>' http://localhost:8081/
   capture get-root-without-wsdl-query http://localhost:8081/
   capture get-root-wsdl 'http://localhost:8081/?wsdl'
   capture get-unknown-path http://localhost:8081/other
   ```

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

   For proxying-a-soap-api every `capture` line runs inside this journal block. WireMock gets its one proxy mapping before the deployed copy of the notes under Scenarios starts, because the original reads its WSDL while it deploys:

   ```sh
   curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "http://www.predic8.com:8080"}}'
   ```

   Once the original has deployed, and before the first scenario clears the journal, the deployment journal is saved, its evidence written as step 7 writes `upstream-journal.json`, and the recorded WSDL copied out of it; the command fails, and the notes' fallback applies, when the WSDL `GET` is missing or encoded:

   ```sh
   D=target/fixture-capture/deployment-journal.json; mkdir -p src/test/capture/get-root-wsdl src/test/resources/upstream/recorded
   curl -sS "http://localhost:$W/__admin/requests" > "$D"
   jq '[.requests | reverse[] | {request, response}]' "$D" > src/test/capture/get-root-wsdl/deployment-journal.json
   B64=$(jq -r '[.requests | reverse[] | select(.request.method == "GET" and .request.url == "/shop/ShopService?wsdl")]
     | if length == 0 then error("WSDL GET not recorded")
       elif .[0].response.headers // {} | keys | map(ascii_downcase) | index("content-encoding") then error("encoded WSDL response")
       else .[0].response.bodyAsBase64 end' "$D") && printf '%s' "$B64" | base64 -d > src/test/resources/upstream/recorded/ShopService.wsdl
   ```

   Each schema `GET` of that journal made for a relative reference of the WSDL is written the same way, under the reference as written, inside `src/test/resources/upstream/recorded/`.

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); and `"port": 8081`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/post-root-create-cart/response.http POST <<'PY'
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

7. `upstream` is `[]` when the original made no outbound call while the scenario ran, as for a path no listener serves (`get-unknown-path`) and a `?wsdl` answered from the WSDL read at deployment (`get-root-wsdl`). Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (`{"properties": {"proxy.upstream-url": "${wiremock.baseUrl}/shop/ShopService", "proxy.wsdl-location": "classpath:upstream/recorded/ShopService.wsdl"}}` for proxying-a-soap-api, with `classpath:upstream/ShopService.wsdl` as the `proxy.wsdl-location` value when the deployment WSDL was not recorded), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code; for `get-unknown-path`, that means SC-1 `GlobalExceptionHandler`, for `post-root-upstream-fault` `service/SoapProxyService` and SC-1 `GlobalExceptionHandler`, for `get-root-wsdl` `ProxyingASoapApiApplication`, `client/ShopServiceClient` and SC-11 `config/WsdlQueryPublisher`, and for the other two scenarios `controller/SoapProxyController`, `service/SoapProxyService`, `client/ShopServiceClient` and SC-13 `mapper/ProxyHeaderMapper`. The recorded WSDL copy under `src/test/resources/upstream/recorded/` is never edited either. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
