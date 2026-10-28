# scatter-gather-flow-control-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`scatter-gather-flow-control`](../../scatter-gather-flow-control/) ("Scatter Gather flow control to email"), package `com.mulesoft.examples.scatter_gather_flow_control`, category General: the flow file declares the `sfdc` namespace (`scatter-gather-flow-control/src/main/app/scatter-gather.xml:2`) but uses no Salesforce element (AAP 0.2.1). The original folder `scatter-gather-flow-control/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

An HTTP request to `/scatterGather` runs flow `scatter-gatherFlow` (`scatter-gather.xml:6-60`). A scatter-gather runs two routes in parallel, each an `expression-component` that builds a fixed contact list; the custom aggregation strategy merges the two lists; DataWeave renders the merged contacts as CSV; and the sub-flow `outboundFlow` (`scatter-gather.xml:61-66`) attaches that CSV as `aggregation-report` to a mail it sends through the `Gmail` SMTP connector. The HTTP caller receives the mail's body text. The flows declare no logger and no exception strategy, and reach no system other than the SMTP server.

The routes' data, as the original README describes it (`scatter-gather-flow-control/README.md:6-21`), each contact a `java.util.HashMap` of strings:

| Route | `Id` | `Name` | `Email` |
|---|---|---|---|
| `sourceA` (`scatter-gather.xml:10-26`) | `1` | `vlado` | `vlado@email.com` |
| `sourceA` | `2` | `michal` | `michal@email.com` |
| `sourceB` (`scatter-gather.xml:27-43`) | `1` | `peter` | `peter@email.com` |
| `sourceB` | `2` | `vlado` | `vlado@email.com` |

Contacts are matched by `Email`: a record in both sources with the same `Email` is the same contact. The aggregation yields a list of maps with the keys `Name`, `Email`, `IDInA` and `IDInB`: every contact of `sourceA` in its order, then each contact of `sourceB` not yet present. `IDInA` or `IDInB` is the empty string when the contact is absent from that source. The expected result of the original README (`scatter-gather-flow-control/README.md:24-32`):

| `Email` | `Name` | `IDInA` | `IDInB` |
|---|---|---|---|
| `vlado@email.com` | `vlado` | `1` | `2` |
| `michal@email.com` | `michal` | `2` | `""` |
| `peter@email.com` | `peter` | `""` | `1` |

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/scatterGather` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` (AAP 0.4.9) | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, body `Please find attached your Aggregation Report` (44 bytes, UTF-8, no trailing newline), `Content-Length: 44`, no `Content-Type` header; a `HEAD` reply carries the headers without the body. Every request, `HEAD` and `OPTIONS` included, sends one report mail | `scatter-gather.xml:4,7,59,63` |
| any other path (`/scatterGather/extra` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7); the flow does not run | listener platform default |
| `/scatterGather`, when processing throws | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7) | platform default for an unhandled exception; the flows have no exception strategy |

- Response body: `outboundFlow` sets the payload to `Please find attached your Aggregation Report` (`scatter-gather.xml:63`) before the one-way `smtp:outbound-endpoint`, which passes the event through; `remove-attachment` and the final `set-payload value="#[payload]"` (`scatter-gather.xml:59,65`) keep it. The HTTP reply is that text, not the CSV.
- The reply carries no `Content-Type`: the final `set-payload` declares no MIME type, and the plan default (AAP 0.4.7) is that the Mule listener then sent none. `ScatterGatherController` writes the bytes straight to the servlet response, so no message converter adds one. This is a Tier 2A verification point.
- The flow reads no query, header or body, so any `Accept`, `Content-Type` or body is ignored and never answered with 406, 415 or 400. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered like any other `OPTIONS` request: the flow runs and the reply carries no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Processing sequence

The steps run in the original's order (AAP 0.3.2 rows `scatter-gather`, `dw:transform-message`, `set-attachment` and `smtp:outbound-endpoint`):

1. Scatter-gather (`scatter-gather.xml:8-44`): `service/ScatterGatherService.scatter()` runs `sourceA()` and `sourceB()` concurrently with `CompletableFuture.supplyAsync` and waits for both with `CompletableFuture.allOf`, without a time limit, because the original declares no `timeout`. It collects one `model/BranchResult` per route in route order, route A first. A failing route is recorded as a failed result instead of being thrown, as Mule's `AggregationContext` recorded it.
2. Custom aggregation (`scatter-gather.xml:9`): `service/ContactMergeAggregationStrategy.aggregate(List<BranchResult>)` requires exactly two successful results, otherwise it throws `IllegalStateException("Data from at least one source was not able to be obtained correctly.")`; it then returns `new ContactMerge().mergeList(A, B)`, the merge described above.
3. CSV transform (`scatter-gather.xml:45-57`, DataWeave script at line 47): `mapper/ContactsCsvMapper.toCsv` keeps `Id`, `Name` and `Email` of each merged contact, in that order, and writes them through SC-4 `mapper/DwCsvWriter`: comma separator, header row, LF line ends including a trailing LF. The merged maps carry no `Id`, so DataWeave reads `null` and the column is blank. The result is already a `String`, which is all `object-to-string-transformer` contributed:

   ```text
   Id,Name,Email
   ,vlado,vlado@email.com
   ,michal,michal@email.com
   ,peter,peter@email.com
   ```

4. `flow-ref outboundFlow` (`scatter-gather.xml:58,61-66`): `client/AggregationReportMailer.send(csv)` sends one mail through the `JavaMailSender` that `spring.mail.*` configures, then returns the body text, which becomes the HTTP payload. The message follows the layout of Mule 3.8's `ObjectToMimeMessage` for a payload with one outbound attachment:
   - `From` `mail.from`, `To` `mail.to`, `Subject` `mail.subject`, by default `Scatter gather example - aggregation` (the flow's literal), `Date` the send time;
   - `multipart/mixed` with two parts: a `text/plain; charset=UTF-8` part holding `Please find attached your Aggregation Report`, `Content-Description: Payload`; and an attachment part named `aggregation-report` (file name and description), `text/plain`, holding the CSV bytes.

   On the wire, Angus Mail encodes both ASCII parts `7bit`; the attachment part carries `Content-Type: text/plain; charset=us-ascii; name=aggregation-report` and `Content-Disposition: attachment; filename=aggregation-report`. The attachment lives only in that message, which is what `remove-attachment` achieved.

The routes run on Spring Boot's `applicationTaskExecutor`, bounded by `spring.task.execution.pool` as Mule 3.8's default threading profile bounded the scatter-gather pool: at most 16 threads and no queue. A route the saturated executor rejects runs on the calling thread, as it did when the original's pool was exhausted, so both routes always run.

### Errors

The flows declare no exception strategy, so the conversion has no custom exception handler: every failure propagates to SC-1 `exception/GlobalExceptionHandler`, which answers `500`, `application/json`, `{ "message": "<exception message>" }` (AAP 0.4.7):

- SMTP connection, authentication or send failure: the `MailException` of the `JavaMailSender`, with its message.
- A required SMTP setting left blank: `smtp.host`, `mail.from` or `mail.to`, or `smtp.user` or `smtp.password` while `mail.smtp.auth` is `true`, as it is in `application.yml`. `AggregationReportMailer` refuses the send before building the message or opening a connection with `MailPreparationException`, message `SMTP mail settings are not configured: ` followed by each missing key with its environment variable, in the order `smtp.host`, `smtp.user`, `smtp.password`, `mail.from`, `mail.to`, never a value. With none of the TODO keys supplied, every request is answered `{ "message": "SMTP mail settings are not configured: smtp.host (SMTP_HOST), smtp.user (SMTP_USER), smtp.password (SMTP_PASSWORD), mail.from (MAIL_FROM), mail.to (MAIL_TO)" }`. `mail.subject` is never required, and an empty subject is sent as given.
- A `mail.from` or `mail.to` value that is not an address: `MailPreparationException`, message `Could not build the aggregation report mail: <reason>`; nothing is sent.
- Aggregation failure: `{ "message": "Data from at least one source was not able to be obtained correctly." }`; no CSV is rendered and no mail is sent.

An unserved path gets the no-listener `404`, `text/plain`, `No listener for endpoint: <path>`.

### Unreachable branch

The aggregation-failure branch (`scatter-gather-flow-control/src/main/java/org/mule/examples/transformers/ContactMergeAggregationStrategy.java:32-33`) cannot be selected by any request (AAP 0.8.2): both routes are constant `expression-component`s that read no input and cannot fail, so every request yields exactly two successful results. The branch is kept exactly as the original declares it and is covered by `service/ContactMergeAggregationStrategyTest`, `service/ScatterGatherServiceTest` and `controller/ScatterGatherControllerTest`, which inject a failing route or hand the strategy failed results. `src/test/resources/fixtures/SCENARIOS.md` lists it as `aggregation-missing-route-result`, state `unreachable`, with the proof; it has no fixture and is excluded from capture and from the branch-coverage claim.

### Tier 2A verification points

The source does not settle these platform behaviours. When a captured fixture shows different bytes, the fixture governs and the code is changed to match (AAP 0.4.7):

- the absent `Content-Type` of the `200` reply (plan default: none);
- the CSV rendering of the missing `Id` (plan: an empty field);
- the report mail's MIME part headers: part content types and charsets, `Content-Description`, the attachment's file name and the transfer encodings;
- the `404` text and its `text/plain` type, and the `500` body shape and message for an SMTP failure (`smtp-unavailable`);
- the flow running for `OPTIONS` and for a CORS pre-flight.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener or connector, not a key of the original, changing it changes behaviour.

The keys of the original keep their names (AAP 0.4.6 names no rename for this example):

| Key | Type | Default | Original name | Status | Environment variable | Description |
|---|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | `HTTP_PORT` (relaxed binding) | `${http.port}` of `http:listener-config` (`scatter-gather.xml:4`), bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so the value came from the deployment; its README tells users to set port 8081 and browse to `http://localhost:8081/scatterGather` (`scatter-gather-flow-control/README.md:46,66`) |
| `smtp.host` | `String` | none (`${SMTP_HOST:}`) | `smtp.host` (unchanged) | TODO | `SMTP_HOST` | `smtp:outbound-endpoint host` (`scatter-gather.xml:64`), bound to `spring.mail.host`. The original README suggests `smtp.gmail.com` |
| `smtp.port` | `int` | `587` (`${SMTP_PORT:587}`) | `smtp.port` (unchanged) | default | `SMTP_PORT` | `smtp:outbound-endpoint port`, bound to `spring.mail.port`; 587 is the submission port of the original README and of Gmail (AAP 0.6.6) |
| `smtp.user` | `String` | none (`${SMTP_USER:}`) | `smtp.user` (unchanged) | TODO | `SMTP_USER` | `smtp:outbound-endpoint user`, bound to `spring.mail.username`; the SMTP account as a plain address (see below) |
| `smtp.password` | `String` | none (`${SMTP_PASSWORD:}`) | `smtp.password` (unchanged) | TODO (secret) | `SMTP_PASSWORD` | `smtp:outbound-endpoint password`, bound to `spring.mail.password` |
| `mail.from` | `String` | none (`${MAIL_FROM:}`) | `mail.from` (unchanged) | TODO | `MAIL_FROM` | `smtp:outbound-endpoint from`; the sender address, read by `AggregationReportMailer` |
| `mail.to` | `String` | none (`${MAIL_TO:}`) | `mail.to` (unchanged) | TODO | `MAIL_TO` | `smtp:outbound-endpoint to`; the recipient address, or a comma-separated list, read by `AggregationReportMailer` |
| `mail.subject` | `String` | `Scatter gather example - aggregation` (`${MAIL_SUBJECT:Scatter gather example - aggregation}`) | `mail.subject` (unchanged) | default | `MAIL_SUBJECT` | `smtp:outbound-endpoint subject` (`scatter-gather.xml:64`), read by `AggregationReportMailer`; the default is the flow's literal, also `AggregationReportMailer.DEFAULT_SUBJECT`. An empty value is sent as an empty subject |

The framework settings that reproduce the original listener, connector and scatter-gather pool:

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the listener port, resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`scatter-gather.xml:4`) |
| `spring.application.name` | `String` | `scatter-gather-flow-control-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `ScatterGatherController` like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `spring.mail.host`, `spring.mail.port`, `spring.mail.username`, `spring.mail.password` | `String`, `Integer`, `String`, `String` | `${smtp.host}`, `${smtp.port}`, `${smtp.user}`, `${smtp.password}` | none | fixed | the `JavaMailSender`, fed from the original `smtp.*` keys; set those rather than these |
| `spring.mail.default-encoding` | `Charset` | `UTF-8` | none | fixed | the application encoding of the original's `mule-deploy.properties` |
| `spring.mail.properties` `[mail.smtp.starttls.enable]`, `[mail.smtp.auth]`, `[mail.smtps.starttls.enable]`, `[mail.smtps.auth]` | `Map<String, String>` entries | `true` each | none | fixed | the JavaMail properties of `smtp:gmail-connector` (`scatter-gather.xml:3`): STARTTLS and authentication for `smtp` and `smtps` |
| `spring.mail.properties` `[mail.smtp.connectiontimeout]`, `[mail.smtp.timeout]`, `[mail.smtp.writetimeout]` | `Map<String, String>` entries | `10000` each (ms) | none | fixed | `responseTimeout="10000"` of `smtp:outbound-endpoint` (`scatter-gather.xml:64`) |
| `spring.task.execution.pool.core-size`, `max-size`, `queue-capacity`, `keep-alive`, `allow-core-thread-timeout` | `int`, `int`, `int`, `Duration`, `boolean` | `1`, `16`, `0`, `60s`, `false` | none | fixed | the scatter-gather route executor, bounded like Mule 3.8's default threading profile (`maxThreadsIdle` 1, `maxThreadsActive` 16, `maxBufferSize` 0, `threadTTL` 60000 ms) |
| `logging.level.org.eclipse.angus.mail` | `LogLevel` | `INFO` | none | fixed | Angus Mail's `.smtp` `DEBUG` writes the SMTP account and the recipient addresses, its `.smtp.protocol` `TRACE` the whole SMTP dialogue: `MAIL FROM`, `RCPT TO`, the headers and the CSV report of contact names and e-mail addresses. Angus Mail logs through `java.util.logging`, which Spring Boot bridges and holds at the same level |

- The `logging.level` key keeps the aggregated contacts and the report mail out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`: a logger's own level outranks them. The original has no logger, so it logged neither. The `/scatterGather` response needs no such key: it is the constant `Please find attached your Aggregation Report`, written past the Spring MVC body writers. `config/SensitiveLogLevelsTest` asserts this.
- Secrets and hosts have no committed value. The application starts without them; a request then fails at the mail step and is answered with the `500` of [Errors](#errors), and no mail is sent.
- `smtp.user` holds the plain address. The original README and test pass it URL-encoded (`senderemailid%40gmail.com`, `scatter-gather-flow-control/README.md:54`; test key `smtp.user_enc`, `scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:88,93`) only because Mule built an SMTP endpoint URI from it; `spring.mail.username` takes the address as is, so `smtp.user_enc` is dropped.
- `mail.subject` is carried as a key (AAP 0.6.6). The original flow fixes the subject as the literal `Scatter gather example - aggregation` (`scatter-gather.xml:64`), which is the key's default, so an unset `MAIL_SUBJECT` sends the original subject; the original test also sets `mail.subject` (`ScatterGatherFlowControlIT.java:97`).
- Unit tests run under profile `test` with `src/test/resources/application-test.yml`, which points the `smtp.*`, `mail.from` and `mail.to` keys at an in-process GreenMail server (sender `scatter@localhost`, recipient `receiver@localhost`) and turns STARTTLS off, because GreenMail's plain SMTP listener offers none. It sets no `mail.subject`, so tests receive the default subject. Those are GreenMail test values, not credentials. GreenMail binds a free port, which each test registers as `smtp.port` through `@DynamicPropertySource`.
- The project calls no vendor system, so it has no `src/test/resources/application-it.yml` and no `application-it.yml.example`.
- Supply the keys through the environment or on the command line. Each value is single-quoted, inside the `spring-boot.run.arguments` string as well, so a value with spaces stays one argument. The shell still interprets `$`, `` ` ``, `\` and `"` inside that argument string's outer double quotes, so pass a password containing any of them, or a single quote, through its environment variable:

  ```sh
  SMTP_HOST='<smtp host>' SMTP_USER='<account>' SMTP_PASSWORD='<password>' MAIL_FROM='<sender>' MAIL_TO='<recipient>' \
    java -jar target/scatter-gather-flow-control-java-1.0.0-SNAPSHOT.jar
  mvn spring-boot:run -Dspring-boot.run.arguments="--smtp.host='<smtp host>' --smtp.user='<account>' --smtp.password='<password>' --mail.from='<sender>' --mail.to='<recipient>'"
  ```

## Mule-to-Java mapping

| Mule construct (`scatter-gather-flow-control`) | Java construct (`com.mulesoft.examples.scatter_gather_flow_control`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`scatter-gather.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/scatterGather"` without `allowedMethods` (`scatter-gather.xml:7`) | `controller/ScatterGatherController`, `@RequestMapping("/scatterGather")`: `handle` (every method but `OPTIONS`) and `handleOptions` (`OPTIONS`), both writing the same reply |
| `scatter-gather` with `custom-aggregation-strategy` `org.mule.examples.transformers.ContactMergeAggregationStrategy` (`scatter-gather.xml:8-9`) | `service/ScatterGatherService.scatter()` (`CompletableFuture.allOf` over one route per future) feeding `service/ContactMergeAggregationStrategy.aggregate`, ported with Mule's `AggregationStrategy`, `AggregationContext` and `MuleEvent` replaced by `List<BranchResult>` (`model/BranchResult`: a route's value or exception) |
| The two `expression-component` routes `sourceA` and `sourceB` (`scatter-gather.xml:10-43`) | `ScatterGatherService.sourceA()` and `sourceB()`, building the same `ArrayList` of `HashMap`s |
| `org.mule.examples.transformers.ContactMerge` | `service/ContactMerge`, ported unchanged apart from commons-lang → commons-lang3 |
| `dw:transform-message` CSV script (`scatter-gather.xml:45-55`) | `mapper/ContactsCsvMapper` over SC-4 `mapper/DwCsvWriter` |
| `object-to-string-transformer` (`scatter-gather.xml:57`) | none needed: `ContactsCsvMapper.toCsv` returns a `String` |
| `flow-ref outboundFlow` + `set-attachment` + `set-payload` + `smtp:outbound-endpoint` + `remove-attachment` (`scatter-gather.xml:58,61-66`) | `client/AggregationReportMailer.send`, over Spring's `JavaMailSender` (Angus Mail) |
| `smtp:gmail-connector` (`scatter-gather.xml:3`) | `spring.mail.*` in `application.yml` (STARTTLS, authentication, 10000 ms timeouts) |
| `set-payload value="#[payload]"` (`scatter-gather.xml:59`) | `ScatterGatherService.process()` returning the mailer's result to the controller |
| Mule 3.8 default threading profile of the scatter-gather pool | Spring Boot's `applicationTaskExecutor`, bounded by `spring.task.execution.pool`; a rejected route runs on the calling thread |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `scatter-gather.xml`) | `ScatterGatherFlowControlApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the `${...}` placeholders of `scatter-gather.xml` | `src/main/resources/application.yml` |
| `ScatterGatherFlowControlIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, Gmail over IMAPS) | `ScatterGatherFlowControlTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`, GreenMail) |
| `ContactsMergeTest` (JUnit 4) | `ContactsMergeTest` (JUnit 5) |

Shared components, each copied from its reference project with only the `package` line changed, together with its tests (AAP 0.4.4, `DECISIONS.md` section 4):

| ID | Files | Copied from | Tests copied with it |
|---|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler` | `rest-api-with-apikit-java` | `exception/GlobalExceptionHandlerTest` |
| SC-4 | `mapper/DwCsvWriter` (the only SC-4 class this project uses) | `processing-orders-with-dataweave-and-APIkit-java` | `mapper/DwCsvWriterTest` |
| ST-1 | `src/test/java/.../support/HttpTestClient` | `rest-api-with-apikit-java` | — |
| ST-2 | `src/test/java/.../FixtureParityTest`, the format of `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` | — (it is the test) |

`config/PreflightDispatchConfig` and its `config/PreflightDispatchConfigTest` are the same classes as in `hello-world-java`; `config/PreflightDispatchListenerTest` is this project's own wire test of them.

Not carried (AAP 0.2.2): `catalog/**` (Studio DataSense metadata), `mule-project.xml`, `src/test/resources/log4j2-test.xml`, and `src/test/resources/sample_data/list_listOfMaps.dwl`, the Studio preview input of the DataWeave script. The original test's `src/test/resources/mule.test.properties` was never committed.

## Behaviours stubbed or externalized

- Externalized: the SMTP server, account, password, sender and recipient, which the original README has users type into the flow's SMTP element (`scatter-gather-flow-control/README.md:48-60`), are the TODO keys above. The subject is the key `mail.subject`, defaulting to the flow's literal.
- Replaced in tests: GreenMail stands in for the Gmail account. The original test read and deleted the received mail over IMAPS after a fixed 10 s sleep; the ported test waits up to 10 s for GreenMail's received-message store.
- Changed for an unconfigured deployment only: a blank required SMTP setting is refused before any connection with a message naming the missing keys ([Errors](#errors)), where the original failed in the SMTP connector with its own text.
- Nothing is stubbed at runtime and no behaviour is dropped.

## Build, run and test

Run from `java-conversions/scatter-gather-flow-control-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
SMTP_HOST='<smtp host>' SMTP_USER='<account>' SMTP_PASSWORD='<password>' MAIL_FROM='<sender>' MAIL_TO='<recipient>' \
  mvn spring-boot:run   # listener on 0.0.0.0:8081; the SMTP keys may also be passed as --smtp.host=... arguments
```

Expected exchange with a reachable SMTP server (the `Date` value varies); the report mail arrives at `mail.to`:

```console
$ curl -i http://localhost:8081/scatterGather
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 44
Date: <current date>

Please find attached your Aggregation Report
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ScatterGatherFlowControlTest` | the ported `ScatterGatherFlowControlIT` (`scatter-gather-flow-control/src/test/java/org/mule/examples/ScatterGatherFlowControlIT.java:101-114`), GreenMail in place of Gmail: a `POST /scatterGather` without a body returns a response with a non-empty payload and status 200, and one mail with content is received |
| `ContactsMergeTest` | the ported `ContactsMergeTest` (`scatter-gather-flow-control/src/test/java/org/mule/examples/ContactsMergeTest.java:31-39`), on JUnit 5: `mergeList` of two generated contact lists equals the expected merged list |
| `controller/ScatterGatherControllerTest` | on the wire through ST-1, GreenMail as SMTP server: `GET`, `POST`, `PUT`, `DELETE`, `PATCH` and `OPTIONS /scatterGather` → `HTTP/1.1 200 OK`, no `Content-Type`, `Content-Length: 44`, body `Please find attached your Aggregation Report`, one mail with the original subject, the flow run once; a failing mail send → 500 `application/json` `{ "message": "<send failure>" }` and no mail; a failing route → 500 with the aggregation message, both routes run, no mail; `/unknown` and `/scatterGather/extra` → 404 `text/plain` `No listener for endpoint: <path>` without running the flow |
| `service/ScatterGatherServiceTest` | each route builds the original contacts in new mutable collections; `scatter` returns one success per route in route order, runs both routes concurrently on the executor and keeps the order when route B finishes first; a failing route is recorded with its unwrapped exception while the other succeeds; `process` mails the original CSV report and returns the mailer's payload, throws `IllegalStateException` and sends no mail when a route fails, and propagates a mail failure unchanged; a rejected route runs on the calling thread; `application.yml` bounds `applicationTaskExecutor` like Mule 3.8's default threading profile |
| `service/ContactMergeAggregationStrategyTest` | two successes are merged in result order, a failed result among them skipped; a success count other than two (route A failed, both failed, no results, three successes) → `IllegalStateException` with the original message; the route payloads stay unchanged |
| `mapper/ContactsCsvMapperTest` | the demo routes' merge → the CSV report above byte for byte; merged contacts are written with a blank `Id` and without `IDInA` and `IDInB`; an `Id` present is written; column order `Id,Name,Email` whatever the input map's order |
| `client/AggregationReportMailerTest` | against GreenMail: the send returns the body text and delivers one message with the original subject, sender and recipient; the message is `multipart/mixed` with the `text/plain; charset=UTF-8` `Payload` part and the `aggregation-report` `text/plain` attachment holding the CSV; comma-separated recipients each receive it; in a Spring context over `application.yml` and profile `test`, the subject is the default `Scatter gather example - aggregation` without `mail.subject`, the configured value with it, and empty when it is empty; a blank `smtp.host`, `smtp.user`, `smtp.password`, `mail.from` or `mail.to`, alone or together, raises `MailPreparationException` with the exact `SMTP mail settings are not configured: …` message naming no value, before any message is built or connection opened; with `mail.smtp.auth` `false` or absent, a blank account is accepted and the mail delivered; a closed port raises `MailException` after one attempt; an invalid sender raises `MailException` |
| `mapper/DwCsvWriterTest` | SC-4: the committed `report.csv` bytes, LF line ends with a trailing LF, header handling, empty and null fields, scalar rendering, quoting and escaping, null-argument rejection, concurrent use |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/scatterGather` gets exactly the plain `OPTIONS` reply (`200`, the flow's payload, no `Content-Type`, no `Access-Control-*` or `Vary`); a pre-flight to an unserved path gets the no-listener 404; cross-origin requests run the flow without CORS headers |
| `config/SensitiveLogLevelsTest` | the `logging.level` key bound from `application.yml` as Spring Boot binds it; with the root at `TRACE` and that level applied through Spring Boot's logging system, `POST /scatterGather` answers as usual and mails the report through GreenMail with a sentinel SMTP account, sender and recipient, and neither those values nor the contacts' e-mail addresses are written through any logger outside `com.mulesoft.examples` and the stand-in server; a control with the level cleared shows exactly Angus Mail's `.smtp` and `.smtp.protocol` loggers writing them |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/scatter_gather_flow_control/FixtureParityTest.java` is the `hello-world-java` reference copy, and `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios in the reference table format, with the fixture contract, the comparison rules and the capture procedure for this original (AAP 0.8.4).

- Fixtures are `src/test/resources/fixtures/<scenario>.json`, one per `SCENARIOS.md` row, each with the sections `request`, `state`, `upstream` and `response`. Every fixture carries the same SMTP `state` (a GreenMail server with the sender and recipient mailboxes) and `"upstream": []`, because the flow makes no HTTP or SOAP call.
- `smtp-unavailable` needs the SMTP keys on a closed port, so its fixture lives in `fixtures/isolated/` and is replayed on its own: `mvn -B test -Dtest=FixtureParityTest -Dfixtures.dir=src/test/resources/fixtures/isolated`.
- `deterministic` scenarios are compared byte for byte: status, reason phrase, `Content-Type`, `Location` and body. `nondeterministic` scenarios compare status, reason and headers exactly and bodies and mails by structure only, and are reported `UNVERIFIED — nondeterministic output`.
- Each run writes `target/fixture-parity/summary.properties`. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.
- Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-scatter-gather` | `http` | `deterministic` | `POST /scatterGather`, empty body | `UNVERIFIED-uncaptured` |
| `get-scatter-gather` | `http` | `deterministic` | `GET /scatterGather`, empty body | `UNVERIFIED-uncaptured` |
| `scatter-gather-report-mail` | `http` | `nondeterministic` | `POST /scatterGather`, empty body; the compared effect is the report mail, whose `Date`, `Message-ID` and multipart boundary differ per send | `UNVERIFIED-uncaptured` |
| `smtp-unavailable` | `http` | `nondeterministic` | `POST /scatterGather`, empty body, SMTP keys on a closed port; the failure text embeds the environment-dependent SMTP host and port | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |
| `aggregation-missing-route-result` | `http` | `unreachable` | — (proof under [Unreachable branch](#unreachable-branch)) | `UNREACHABLE` |

Once captured, `scatter-gather-report-mail` and `smtp-unavailable` stay `UNVERIFIED — nondeterministic output`, so the strongest label this project's captures can earn is `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| scatter-gather-flow-control-java | scatter-gather-flow-control | General | PASS | PASS | 98.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 220 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 220 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 160 of 163 lines (`service/ScatterGatherService` 50 of 52, `service/ContactMerge` 28 of 29, `service/ContactMergeAggregationStrategy` 15 of 15, `mapper/ContactsCsvMapper` 12 of 12, `mapper/DwCsvWriter` 55 of 55). Total project LINE coverage: 97.1% (409 of 421 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`, with `aggregation-missing-route-result` reported `UNREACHABLE`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md` ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `ScatterGatherFlowControlTest` carries the original committed assertions of `ScatterGatherFlowControlIT`: status 200, a non-empty payload, and a received report mail. `ContactsMergeTest` carries the original merge assertion.
- `controller/ScatterGatherControllerTest`, `service/ScatterGatherServiceTest`, `service/ContactMergeAggregationStrategyTest`, `mapper/ContactsCsvMapperTest` and `client/AggregationReportMailerTest` cover every reachable branch, and the unreachable one with an injected failing route.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- No original assertion covers these, which only the new tests cover: the exact response body and its absent `Content-Type`, the CSV bytes of the report, the MIME shape of the report mail, acceptance of every method (`get-scatter-gather`), the `404` and `500` defaults (`get-unknown-path`, `smtp-unavailable`), and the aggregation failure.
- Unverified scenarios, until captured: `post-scatter-gather`, `get-scatter-gather`, `scatter-gather-report-mail`, `smtp-unavailable`, `get-unknown-path`. `scatter-gather-report-mail` and `smtp-unavailable` are `nondeterministic` and stay `UNVERIFIED — nondeterministic output` after capture. `aggregation-missing-route-result` is `unreachable`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `scatter-gather-flow-control/`.
