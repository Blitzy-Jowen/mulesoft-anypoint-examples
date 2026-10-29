# sending-json-data-to-a-jms-queue-java

Java 17 / Spring Boot 3.2.12 conversion, on Undertow with Spring JMS (`JmsTemplate`) and an embedded ActiveMQ Artemis broker, of the Mule EE 3.8 example [`sending-json-data-to-a-jms-queue`](../../sending-json-data-to-a-jms-queue/) (category JMS/messaging-based flow). The original folder stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

Flow `json-to-jmsFlow` in `src/main/app/json-to-jms.xml` receives JSON sales data over HTTP as a byte array, converts it to a string, sends it to the JMS queue `sales` of an ActiveMQ connector and logs the message (original `README.md:9`). The connector declares no broker URL (`json-to-jms.xml:3`), so Mule used ActiveMQ's in-VM broker; the original README tells users to point it at `tcp://localhost:61616` and to view the queued message in the ActiveMQ web console (`README.md:25,34-36`).

## Endpoints and triggers

| Method | Path | Host | Port | Request body | Response | Source |
|---|---|---|---|---|---|---|
| `POST` only (`allowedMethods="POST"`; the method is matched ignoring case, so `post` works as Mule 3.8 matched it) | `/sales` | `0.0.0.0` | `http.port`, default `8081` | any bytes, never parsed or validated | `200 OK`, body = the flow's payload | `json-to-jms.xml:4,6` |

`controller/SalesController` reads the raw body and hands it to `service/SalesQueueService`, which runs the transformer, the queue send and the logger in the flow's order. The body is never parsed as JSON: the example's own `message.json` (`{"ITEM_ID"= 001, ...}`) is not valid JSON and passes through unchanged.

- Body decoding (`byte-array-to-string-transformer`, `json-to-jms.xml:8`): with the charset the `Content-Type` names; with the JVM default charset when the header names none, as the Mule listener did; with UTF-8, the original application encoding, when the header is absent, blank, unparseable or names an unusable charset. The response body is the payload encoded back with the same charset, so it equals the request bytes for every body valid in its charset. An empty body is queued as the empty string and answered with an empty `200`.
- Response `Content-Type`: the request's lower-cased `type/subtype` plus `; charset=<the charset above>`, for example `application/json; charset=UTF-8`; no header when the request has no `Content-Type`, `*/*` or an unparseable type. Plan default, derived from Mule 3.8 carrying the inbound MIME type through the transformer; a Tier 2A verification point where a captured fixture governs.
- Form bodies: Mule 3.8's listener decoded an `application/x-www-form-urlencoded` entity (what `curl -d` sends) into a parameter map before the flow ran, so the transformer queued that map's text, for example `{QTY=1, ITEM_ID=001, PRICE=20, ITEM_NAME=Shirt}` (Mule's hash order; a `password` value is written `*****`). `SalesQueueService.sendForm` reproduces it. Send JSON with an explicit `Content-Type`, as in the sample below.
- A path carrying `;` segment parameters (`/sales;x=1`, `/sales;jsessionid=ABC`, `/sales;`) is not `/sales`: every method, `POST` in any letter case included, gets the no-listener 404 naming the path with its parameters, `No listener for endpoint: /sales;x=1`, and nothing is queued. The Mule listener matched the raw path, query removed, literally, and before the method, where Spring MVC ignores segment parameters (`config/ListenerPathConfig`). `controller/SalesController` maps every method on `/sales`, so the path is checked before any method is rejected with the 405. A `;` in the query string is not part of the path.

Responses:

| Case | Response |
|---|---|
| `POST /sales` | `200 OK`, the payload with its `Content-Length` and the `Content-Type` above |
| A method other than `POST` on `/sales` (`OPTIONS`, `TRACE` and CORS pre-flights included) | `405 Method Not Allowed`, `text/plain`, `Method not allowed for endpoint: /sales` (SC-1 `exception/GlobalExceptionHandler`, AAP 0.4.7) |
| Any other path (`/sales/` included, and `/sales` with `;` segment parameters, such as `/sales;x=1`, for every method) | `404 Not Found`, `text/plain`, `No listener for endpoint: <path>` (SC-1), nothing queued |
| An unhandled failure, such as an unreachable broker | `500 Internal Server Error`, `application/json`, `{ "message": "<exception message>" }` (SC-1), logged at ERROR with the route and exception types only |
| A form entity the Mule listener rejected before the flow ran (`exception/UndecodableFormException`) | no `Content-Type`, nothing queued: `400 Bad Request` `Cannot decode x-www-form-urlencoded payload` for a malformed percent escape; `400` `Could not parse '<header>'` for an unparseable `Content-Type`; `400` with the lower-cased charset name for an unusable charset; `500` `Server encountered a problem` for two different charsets |

The 404, 405 and 500 bytes are platform defaults and Tier 2A verification points, and so is the 404 for a path carrying `;` segment parameters. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.

### Sample request

The original README's request (`README.md:28-32`), sent with the project's byte-for-byte copy of the original `message.json`, from this directory:

```sh
curl -i -X POST http://localhost:8081/sales -H 'Content-Type: application/json' --data-binary @src/test/resources/message.json
```

```console
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json; charset=UTF-8
Content-Length: 73
Date: <current date>

<the 73 bytes of message.json, unchanged>
```

The same 73 bytes are queued on `sales` as the message text (the charset shown is the JVM default, UTF-8 on the build host).

### Messaging

| Property | Value |
|---|---|
| Destination | queue `sales` (point-to-point, `spring.jms.pub-sub-domain: false`; key `jms.queue`) |
| Message | JMS `TextMessage`, text = the decoded body (or the form rendering), sent by `JmsTemplate.convertAndSend` |
| Delivery mode | `NON_PERSISTENT`, priority 4, no expiry: Mule 3.8's `JmsConnector.persistentDelivery` defaults to `false` |
| Exchange pattern | one-way, synchronous on the request thread; no transaction, no reply-to, no receive |
| Broker wait | each blocking Artemis client call waits at most `jms.call-timeout` (10000 ms, `config/ArtemisClientConfig`); a timeout fails the request with the 500 and is never retried |

A failed send fails the request before anything is logged. After a successful send, the INFO line reproduces what the original `<logger level="INFO"/>` (`json-to-jms.xml:10`) printed without a message: a `org.mule.DefaultMuleMessage` description with a fresh `id`, `payload=java.lang.String`, the correlation and encoding fields and empty property scopes. The request body is never logged.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type each value binds to, read by `service/SalesQueueService`, `config/ArtemisClientConfig` and Spring Boot. Status: `default` — a value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener or broker, not a key of the original.

| Key | Type | Default | Original name | Status | Meaning |
|---|---|---|---|---|---|
| `http.port` | `Integer`, the type of `server.port`, which it feeds | `8081` | `http.port`, the `${http.port}` placeholder (`json-to-jms.xml:4`), supplied at run time; original `README.md:17` uses 8081 | default | listener port |
| `server.port` | `Integer` | `${http.port:8081}` | none | fixed | the Undertow port, resolved from `http.port`; set `http.port` instead |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | listener host (`json-to-jms.xml:4`) |
| `spring.application.name` | `String` | `sending-json-data-to-a-jms-queue-java` | none | fixed | name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | unserved paths reach SC-1's 404 |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` gets the 405, not the servlet's 200 echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the 404 |
| `spring.artemis.mode` | `ArtemisMode` (`embedded` or `native`) | `embedded` | none | fixed | in-VM broker in place of Mule's in-VM ActiveMQ |
| `spring.artemis.embedded.enabled` | `boolean` | `true` | none | fixed | starts the in-VM broker; `false` with `mode: native` |
| `spring.artemis.embedded.persistent` | `boolean` | `false` | none | fixed | non-persistent broker, as ActiveMQ's in-VM default |
| `spring.artemis.embedded.queues` | `String[]` (queue names) | `sales` | none | fixed | pre-creates queue `sales` |
| `spring.jms.pub-sub-domain` | `boolean` | `false` | none | fixed | destinations are queues |
| `spring.jms.template.delivery-mode` | `DeliveryMode`, the enum of Boot's `JmsProperties` (`persistent` or `non-persistent`) | `non-persistent` | none | fixed | Mule's non-persistent default delivery |
| `jms.queue` | `String` | `sales` | none: inline `queue="sales"` (`json-to-jms.xml:9`) | default | destination queue, read by `service/SalesQueueService` |
| `jms.call-timeout` | `long` (milliseconds) | `10000` (ms) | none | default | bound on each blocking broker call; must be positive, or the application refuses to start |

The example has no secrets, so `application.yml` holds no active TODO placeholder and the project has no `application-test.yml`; the tests run against the embedded broker.

Original-to-new key map:

| Original | Converted |
|---|---|
| `http.port` (`mule-app.properties` is empty) | `http.port`, default `8081` |
| Studio global element "Active MQ › Broker URL" (`README.md:25`; no property, blank means in-VM) | `spring.artemis.mode: native`, `spring.artemis.embedded.enabled: false` and `spring.artemis.broker-url` (`String`), for example `tcp://localhost:61616` |
| inline `queue="sales"` | `jms.queue`, default `sales` |

To use an external broker, pass `--spring.artemis.mode=native --spring.artemis.embedded.enabled=false --spring.artemis.broker-url=tcp://localhost:61616` (environment `SPRING_ARTEMIS_MODE`, `SPRING_ARTEMIS_EMBEDDED_ENABLED`, `SPRING_ARTEMIS_BROKER_URL`). Broker credentials, when the broker needs them, come only from the environment, never from a committed file: `SPRING_ARTEMIS_USER` and `SPRING_ARTEMIS_PASSWORD`, which Spring Boot binds to `spring.artemis.user` and `spring.artemis.password`, both `String`. The broker must speak the Artemis core protocol, which means an ActiveMQ Artemis broker; an ActiveMQ Classic 5.x broker does not accept it.

## Mule-to-Java mapping

| Mule construct | Java |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` | Undertow (Tomcat excluded) with `server.address`, `server.port` |
| `http:listener` `POST` `sales` | `controller/SalesController`; `config/ListenerMethodFilter` (method ignoring case); `config/PreflightDispatchConfig` (no CORS policy, a pre-flight is a plain `OPTIONS`); `config/ListenerPathConfig` (the raw path, query removed, matched literally, `;` segment parameters included) |
| listener form decoding and its rejections | `SalesQueueService.sendForm`, `exception/UndecodableFormException` |
| `byte-array-to-string-transformer` | charset decoding of the raw body in `SalesQueueService.send(byte[], String)` |
| `jms:outbound-endpoint queue="sales"` (one-way) | `JmsTemplate.convertAndSend` in `SalesQueueService.send(String)` |
| `logger level="INFO"` | SLF4J INFO in `SalesQueueService`, after the send |
| `jms:activemq-connector` (in-VM ActiveMQ 5.10) | Spring Boot Artemis auto-configuration, embedded non-persistent Artemis 2.31.2; `config/ArtemisClientConfig` |
| listener and runtime defaults (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` + `exception/ErrorResponse` |
| `mule-deploy.properties` (loads `json-to-jms.xml`) | `com.mulesoft.examples.sending_json_data_to_a_jms_queue.SendingJsonDataToAJmsQueueApplication` |
| `SendingJsonDataToJMSQueueIT` (`FunctionalTestCase`, `MuleClient`) | `SendingJsonDataToJMSQueueTest` (`@SpringBootTest`, ST-1 client, embedded broker) |

## Behaviours stubbed or externalized

Behaviours the conversion replaces or externalizes, together with those it keeps and those it does not reproduce:

- Broker: the in-VM ActiveMQ 5.10 broker becomes an embedded, non-persistent Artemis 2.31.2 broker with queue `sales` pre-created; an external Artemis broker is configuration only (above).
- Web console: the ActiveMQ console of the original README (`http://localhost:8161/admin/queues.jsp`, step 7) has no embedded equivalent. Inspect `sales` with a JMS client, or run against an external Artemis broker and use its console.
- Mule transport properties: the `MULE_*` JMS properties Mule set (such as `MULE_SESSION`, `MULE_ROOT_MESSAGE_ID`, `MULE_ENDPOINT`, `MULE_ENCODING`) are not set; the message body is the contract. A Tier 2A verification point.
- Logger: the description is rebuilt in Mule's layout without the per-message metadata Mule held (inbound HTTP properties, `MULE_*` values), and without the body.
- Response `Content-Type` derivation and the empty-body result: plan defaults, Tier 2A verification points.
- `validateConnections="true"` (`json-to-jms.xml:3`) has no Spring equivalent; with an external broker, a missing broker surfaces as the 500 of the first request rather than a deployment failure.

Studio metadata (`mule-project.xml`, `catalog/`) and `log4j2.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/sending-json-data-to-a-jms-queue-java/`:

```sh
mvn -B clean install   # build, surefire tests, JaCoCo 80% LINE gate on service/** and mapper/**, enforcer JAX-WS/CXF ban
mvn -B test            # surefire tests only; the embedded broker needs no external service
mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=8081
java -jar target/sending-json-data-to-a-jms-queue-java-1.0.0-SNAPSHOT.jar
```

`mvn verify` adds nothing here: this is not a connector project, so it has no failsafe plugin and no live-sandbox `*IT` class. Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/`, and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it asserts |
|---|---|
| `SendingJsonDataToJMSQueueTest` | the ported original assertions (`SendingJsonDataToJMSQueueIT.java:49-55`): `POST /sales` with `message.json` answers, with a non-empty payload and status `200`, and a message with a payload is received from `sales` within 1500 ms |
| `controller/SalesControllerTest` | on the wire through ST-1: the echoed body equals the sent bytes and the queued `TextMessage` (`NON_PERSISTENT`) equals the sent text; ISO-8859-1 round trip; empty body; no `Content-Type`; form rendering and every listener rejection; `GET`, `OPTIONS`, `PUT`, `DELETE`, `PATCH`, `HEAD` and an unknown token → 405; `/unknown` → 404; `/sales;x=1`, `/sales;jsessionid=ABC` and `/sales;` → 404 `No listener for endpoint: <path>` with nothing queued for `POST` (and `post`) with a JSON body, `GET`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE` and an unknown token; broker failure → JSON 500 |
| `service/SalesQueueServiceTest` | decoding and encoding per charset, the response `Content-Type`, form detection, decoding, rendering and rejections, one send to the configured queue, the INFO description logged after the send and never the body, failures propagated with nothing logged |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: 404, 405 and 500 status lines, `Content-Type` and body bytes; `ErrorResponse` escaping; one escaped ERROR line per unhandled exception |
| `config/ListenerMethodFilterTest` | `post`, `Post` and other spellings of `POST` run the flow; every other method keeps the 405 |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight is dispatched as a plain `OPTIONS`: 405 on `/sales`, 404 elsewhere (`/unknown` and `/sales;x=1`), no `Access-Control-*` headers |
| `config/ArtemisClientConfigTest` | `jms.call-timeout` bounds the Artemis client in both modes, a broker that never answers fails within it, a non-positive value stops start-up, native mode starts no in-VM broker when it is disabled |
| `FixtureParityTest` | ST-2 fixture replay of `src/test/resources/fixtures/SCENARIOS.md`; skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` is ST-2, copied from `hello-world-java` with only its `package` line changed (see [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation)). `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios with their exact requests and holds the fixture contract and the capture procedure against a licensed Mule 3.8 runtime and a local ActiveMQ 5.10 broker. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Request | Status in the last run |
|---|---|---|
| `post-sales-json` | `POST /sales`, `Content-Type: application/json`, `message.json` | `UNVERIFIED-uncaptured` |
| `get-sales-method-not-allowed` | `GET /sales` | `UNVERIFIED-uncaptured` |
| `post-unknown-path` | `POST /unknown`, `message.json` | `UNVERIFIED-uncaptured` |
| `post-sales-empty-body` | `POST /sales`, `Content-Type: application/json`, empty body | `UNVERIFIED-uncaptured` |
| `post-sales-form` | `POST /sales`, form body `ITEM_ID=001&ITEM_NAME=Shirt&QTY=1&PRICE=20` | `UNVERIFIED-uncaptured` |
| `post-sales-form-undecodable` | `POST /sales`, form body `a=%zz` | `UNVERIFIED-uncaptured` |
| `post-sales-form-unsupported-charset` | `POST /sales`, `charset=bogus` form | `UNVERIFIED-uncaptured` |

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| sending-json-data-to-a-jms-queue-java | sending-json-data-to-a-jms-queue | JMS/messaging-based flow | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 308 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 308 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 163 of 163 lines of `service/SalesQueueService` and its nested tokenizer (the project has no `mapper` package). Total project LINE coverage: 98.1% (421 of 429 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=7`, `failed=0`.

Tier 2A is blocked: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed` (root `DECISIONS.md`, section 9). Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `SendingJsonDataToJMSQueueTest` carries the original committed assertions, and the controller and service tests cover every reachable branch.
- This evidence is weaker than captured fixtures: it shows the conversion meets the original's own assertions and the plan's reading of the Mule 3.8 listener and platform defaults, not that its bytes equal what the original runtime sent.
- Behaviours no original assertion covers, tested only by the new tests: the echoed body equalling the sent bytes; the queued text equalling the sent text; the 405 for non-`POST` methods; the 404 for unserved paths, `/sales` with `;` segment parameters included; the 500 on broker failure; the response `Content-Type`; the empty body; the form rendering and the listener's form rejections.
- Unverified scenarios, until captured: `post-sales-json`, `get-sales-method-not-allowed`, `post-unknown-path`, `post-sales-empty-body`, `post-sales-form`, `post-sales-form-undecodable`, `post-sales-form-unsupported-charset`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `sending-json-data-to-a-jms-queue/`.
