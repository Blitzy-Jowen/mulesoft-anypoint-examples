# Parity scenarios — exposing-a-restful-resource-using-the-HTTP-connector-java

Scenarios for the fixture replay (ST-2) of `exposing-a-restful-resource-using-the-HTTP-connector-java` (package `com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector`). The original example is `exposing-a-restful-resource-using-the-HTTP-connector` (folder `exposing-a-restful-resource-using-the-HTTP-connector/`). Its flow file `src/main/app/http-restful-resource.xml` keeps persons in two singleton beans, the `java.util.HashMap` `personDataStore` and the `java.util.concurrent.atomic.AtomicInteger` `personId`, and serves them through three flows on one listener configuration: `retrieveAllPersonsFlow` (`allowedMethods="GET"`, path `/person`) renders every stored person as JSON; `storePersonFlow` (`allowedMethods="POST"`, path `/person`) converts the body to a `Person`, stores it under the next counter value and sets a constant success payload, with a response builder that sets the status and a `Location` header; `retrievePersonFlow` (`allowedMethods="GET"`, path `/person/{personId}`) parses the id, logs it, fails a `throwOnUnaccepted` filter when no person is stored under it, and otherwise returns that person, with a choice exception strategy whose single rollback branch sets the variables its error response builder reads. Every reachable branch listed for the example in AAP 0.7.5, and each listener-level case AAP 0.8.2 adds, is exactly one scenario below, and each scenario's captured fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied, and its rows are written from AAP 0.7.5 for this project.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-persons-empty` | `http` | `deterministic` | `GET /person`, empty body | freshly started application; no prior request (empty store, counter 0) | `retrieveAllPersonsFlow` over the empty store: the store's `values()` rendered by `object-to-json-transformer` | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:7-8,11,12-13` |
| `post-person` | `http` | `deterministic` | `POST /person`, header `Content-Type: application/json`, body = the bytes of `person.json` (`src/test/resources`) | freshly started application; after `get-persons-empty` (empty store, counter 0) | `storePersonFlow` success: `json-to-object-transformer` into `Person`, `incrementAndGet` of the counter into flow variable `personId`, `put` into the store, constant success `set-payload`; the `response-builder` `statusCode` attribute and its `Location` header expression shape the response | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:16-20,21,22,23,24` (`Location` at 18) |
| `get-person-found` | `http` | `deterministic` | `GET /person/1`, empty body | freshly started application; after `get-persons-empty`, `post-person` (store holds person 1, counter 1) | `retrievePersonFlow` found: `Integer.valueOf` of the URI parameter, the `level="ERROR"` logger runs, the `containsKey` filter accepts, the lookup `set-payload` returns the stored person | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:28,31,32,33-35,36` |
| `get-persons` | `http` | `deterministic` | `GET /person`, empty body | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found` (store holds person 1, counter 1) | `retrieveAllPersonsFlow` over the store holding the person stored by `post-person` | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:11-13` |
| `get-person-not-found` | `http` | `deterministic` | `GET /person/999`, empty body | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons` (store holds person 1, counter 1) | the `containsKey` filter rejects and `throwOnUnaccepted="true"` raises `FilterUnacceptedException`; the `choice-exception-strategy` selects its rollback branch, which sets `errorStatusCode` and `errorReasonPhrase` for the listener's `error-response-builder` | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:28-29,31,32,33-35,37-42` (rollback 38-41, variables 39-40) |
| `get-person-invalid-id` | `http` | `deterministic` | `GET /person/abc`, empty body | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found` (store holds person 1, counter 1) | `Integer.valueOf` of the URI parameter throws before the logger runs; the `choice-exception-strategy` has no branch for that exception, so the platform default for an unhandled exception applies (AAP 0.4.7) | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:28,31,37-42` (no matching branch) |
| `post-person-malformed` | `http` | `deterministic` | `POST /person`, header `Content-Type: application/json`, body `{` | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id` (store holds person 1, counter 1) | `json-to-object-transformer` fails on the truncated body; `storePersonFlow` declares no exception strategy, so the platform default for an unhandled exception applies (AAP 0.4.7); the counter increment is never reached, so no id is consumed | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:16-20,21` |
| `put-person-method-not-allowed` | `http` | `deterministic` | `PUT /person`, empty body | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id`, `post-person-malformed` (store holds person 1, counter 1) | the method is outside the `allowedMethods` of both `/person` listeners; platform method-not-allowed default (AAP 0.4.7) | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:11,16` |
| `post-person-id-method-not-allowed` | `http` | `deterministic` | `POST /person/1`, header `Content-Type: application/json`, body = the bytes of `person.json` (`src/test/resources`) | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id`, `post-person-malformed`, `put-person-method-not-allowed` (store holds person 1, counter 1) | the method is outside the `allowedMethods` of the `/person/{personId}` listener; platform method-not-allowed default (AAP 0.4.7) | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:28` |
| `get-unknown-path` | `http` | `deterministic` | `GET /people`, empty body | freshly started application; after `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id`, `post-person-malformed`, `put-person-method-not-allowed`, `post-person-id-method-not-allowed` (store holds person 1, counter 1) | no listener serves the path; platform no-listener default (AAP 0.4.7) | `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:4,11,16,28` (no `/people` path) |

The body of `post-person-malformed` is the single character `{`, with no line ending. The file body of `post-person` and `post-person-id-method-not-allowed` is `src/test/resources/person.json` of this project, a byte copy of the original's `exposing-a-restful-resource-using-the-HTTP-connector/src/test/resources/person.json` (one line, no line ending).

The `Request` cells name only the headers the capture sets explicitly. The headers curl adds by default are recorded unedited in each fixture's `request.headers` from curl's trace (Capture step 5).

The responses of `get-person-invalid-id`, `post-person-malformed`, `put-person-method-not-allowed`, `post-person-id-method-not-allowed` and `get-unknown-path` are Mule platform defaults (AAP 0.4.7), and the response of `get-person-not-found` comes from the original's rollback branch through the error response builder. All six are Tier 2A verification points where the captured bytes govern.

No scenario of this project is `unreachable` or `nondeterministic`: every branch is selected by its request and the state the earlier rows leave, and no flow reads a clock, a random value or a generated name.

Sequential state. The person store and the id counter are application state that lives in memory, so the rows form one sequence, and each `Seeded state` cell names the scenarios that run before it rather than anything the fixture's `state` seeds:

- Tier 2A capture runs all ten rows against a freshly started original, in table order, with no other request in between, so the store and the counter are as each `Seeded state` cell says. `get-person-invalid-id` and `post-person-malformed` consume no id, so the counter stays at 1 from `post-person` to the last row.
- Each fixture's `state` is `{}`: it carries no tables, files, messages or `state.properties`. The in-memory state is reached only through the preceding scenarios.
- Replay depends on the same order. `FixtureParityTest` builds one dynamic test per row in table order and runs them all in one application context of its own, whose store starts empty, and its per-scenario reset (WireMock, directories, tables, log events, mailboxes) leaves the application's store untouched, so the replay sees the state the capture saw. The rows are never reordered or renamed. A row without a fixture is skipped, which would leave every later row on a different store, so the ten fixtures are captured together in one run, and a re-capture replaces all ten.

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
- Every row of this file is captured by sending its request to the original deployed with `http.port=8085` (see Capture). The `Location` header expression of `storePersonFlow` names host and port `0.0.0.0:8081` literally rather than reading `http.port` (`exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:18`). Capturing on 8085 keeps the replay's single `Location` substitution (`:<request.port>` to `:<replay port>`, Comparison rules) off that literal port; a capture on 8081 would have the replay rewrite it to the replay port, which the original never wrote there.

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`8085` in this project). It is the port replaced inside `Location` on replay.
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
- `amqp`: `{initialConfigurationResource}`, the classpath Qpid Broker-J configuration that declares the broker objects and messages.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- For exposing-a-restful-resource-using-the-HTTP-connector, `state` is `{}`: the original reaches no external store, its person store and id counter live in the application's memory and are reached only through the preceding scenarios (see Sequential state), and no `state.properties` entry or placeholder is needed, because the replay port comes from `local.server.port`, the default `portProperty`.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every exposing-a-restful-resource-using-the-HTTP-connector scenario: no flow makes an outbound call (the unused `http:request-config` at `http-restful-resource.xml:5` is referenced by no flow and is dropped, AAP 0.4.5), so replay registers no WireMock stub, starts no embedded server besides WireMock, and fails the scenario on any outbound call.
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For exposing-a-restful-resource-using-the-HTTP-connector the summary then holds `verified=0`, `unverified=10` and `failed=0`, followed by one `scenario.<name>=UNVERIFIED-uncaptured` line for each of `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id`, `post-person-malformed`, `put-person-method-not-allowed`, `post-person-id-method-not-allowed` and `get-unknown-path`, in that order. Until Tier 2A capture, every row of this file is `UNVERIFIED-uncaptured`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

This project is labelled `PARITY: UNVERIFIED — Mule runtime unavailable` in its README and in the root `SUMMARY.md` until every scenario other than an `unreachable` one is captured and matches byte for byte; only then does it become `PARITY: VERIFIED — Tier 2A fixtures`.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/exposing-a-restful-resource-using-the-HTTP-connector-java/`).

1. Build the unmodified original `exposing-a-restful-resource-using-the-HTTP-connector` with `mvn -B package` (`cd exposing-a-restful-resource-using-the-HTTP-connector && mvn -B package`), with the EE repository credentials in `~/.m2/settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for exposing-a-restful-resource-using-the-HTTP-connector, whose flows make no outbound call. Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
3. Deploy the archive to a licensed Mule 3.8 EE runtime and pass `http.port=8085` as a runtime property (for a Mule 3 standalone runtime `mule -M-Dhttp.port=8085`, the launcher form of `-Dhttp.port=8085`), because `exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/mule-app.properties` defines no value, together with the upstream properties of step 2 for an original that has upstreams (this one has none); port 8085 rather than 8081 for the reason given under Table rules. The runtime is freshly started for the capture, and no request reaches the application before the first row of step 4 (see Sequential state).
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   capture get-persons-empty http://localhost:8085/person
   capture post-person -X POST -H 'Content-Type: application/json' --data-binary @src/test/resources/person.json http://localhost:8085/person
   capture get-person-found http://localhost:8085/person/1
   capture get-persons http://localhost:8085/person
   capture get-person-not-found http://localhost:8085/person/999
   capture get-person-invalid-id http://localhost:8085/person/abc
   capture post-person-malformed -X POST -H 'Content-Type: application/json' --data-binary '{' http://localhost:8085/person
   capture put-person-method-not-allowed -X PUT http://localhost:8085/person
   capture post-person-id-method-not-allowed -X POST -H 'Content-Type: application/json' --data-binary @src/test/resources/person.json http://localhost:8085/person/1
   capture get-unknown-path http://localhost:8085/people
   ```

   The first argument of each `capture` line is the scenario name, and the rest are its `curl` request arguments. The lines run once each, in this order, which is the table order, with no other request in between. `--data-binary` sends its argument, or the content of its `@file`, with the bytes unchanged (no line endings stripped, no encoding). `@src/test/resources/person.json` resolves in the project directory to this project's copy, which is byte-identical to the original folder's `src/test/resources/person.json`.

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); and `"port": 8085`.
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-persons-empty/response.http GET <<'PY'
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

7. `upstream` is `[]` when the original made no outbound call, as in every exposing-a-restful-resource-using-the-HTTP-connector scenario. Otherwise this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (`{}` for exposing-a-restful-resource-using-the-HTTP-connector), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code, in the owner of the scenario's response. The six Tier 2A verification points, whose plan defaults are documented in AAP 0.4.7 and the project README, are owned as follows, and a captured fixture that differs from the plan default governs:
  - `get-person-invalid-id` and `post-person-malformed`, the unhandled-exception default: SC-1 `exception/GlobalExceptionHandler`;
  - `put-person-method-not-allowed` and `post-person-id-method-not-allowed`, the method-not-allowed default: SC-1 `exception/GlobalExceptionHandler`;
  - `get-unknown-path`, the no-listener default: SC-1 `exception/GlobalExceptionHandler`;
  - `get-person-not-found`, the rollback branch: `exception/PersonExceptionHandler` over SC-2 `config/ReasonPhrase`.

  The other four scenarios (`get-persons-empty`, `post-person`, `get-person-found`, `get-persons`) are owned by `controller/PersonController` and `service/PersonService`.
- No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
