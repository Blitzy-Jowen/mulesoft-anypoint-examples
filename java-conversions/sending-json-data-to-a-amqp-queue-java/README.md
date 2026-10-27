# Sending JSON data to an AMQP queue — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion, on Undertow with Spring AMQP (`RabbitTemplate`), of the Mule EE 3.8 example [`sending-json-data-to-a-amqp-queue`](../../sending-json-data-to-a-amqp-queue/) (category General). Its single flow `json-to-rabbitmqFlow` in `src/main/app/json-to-rabbitmq.xml` receives a JSON message with sample sales data over HTTP and sends it over AMQP to the RabbitMQ queue `sales_queue`, where it can be viewed in the RabbitMQ web console (original `README.md:11-12`). The original folder `sending-json-data-to-a-amqp-queue/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Endpoint and behaviour

| Path | Methods | Host | Port | Source |
|---|---|---|---|---|
| `/` | `POST` only (`allowedMethods="POST"`; the method is compared ignoring case, as Mule 3.8 did) | `0.0.0.0` | `http.port`, default `8081` | `json-to-rabbitmq.xml:4,6` |

The request body is not parsed as JSON: its bytes are forwarded to the broker unchanged. `controller/SalesController` hands them to `service/SalesPublisherService`, which runs the flow's logger and AMQP endpoint in this order:

1. INFO log `payload to be sent: <body decoded as UTF-8>`, written verbatim (`json-to-rabbitmq.xml:8`).
2. A request-response publish to exchange `sales_exchange` with routing key `""` (empty), through `RabbitTemplate.sendAndReceive` (`json-to-rabbitmq.xml:9`). Mule's AMQP transport uses the empty key for a non-default exchange without `routingKey`, so `sales_queue` receives the message only if a binding on the broker routes the empty key to it. The message is persistent and carries no content type.
3. The reply is awaited on a temporary, server-named, exclusive, auto-delete reply queue set as `replyTo`, for at most 10000 ms (`responseTimeout="10000"`, `json-to-rabbitmq.xml:9`).

Before the publish the service checks `sales_exchange` and `sales_queue` passively and never creates the exchange, the queue or a binding, as the original Mule AMQP endpoint did: it declared no `exchangeType` or queue attributes. Nothing is retried.

Responses:

| Case | Response |
|---|---|
| A reply arrives within 10 s | `200 OK`, body = the reply body bytes, with `Content-Length` |
| No reply within 10 s | `200 OK` with an empty body (`Content-Length: 0`) once the 10 s have passed. Plan default: Mule's AMQP dispatcher returns no message on timeout rather than raising an exception. A Tier 2A verification point (AAP 0.7.5, 0.8.9); a captured fixture governs |
| A method other than `POST` on `/` (`OPTIONS`, `TRACE` and CORS pre-flights included) | `405 Method Not Allowed`, `text/plain`, `Method not allowed for endpoint: /` (SC-1 `exception/GlobalExceptionHandler`, AAP 0.4.7) |
| Any other path | `404 Not Found`, `text/plain`, `No listener for endpoint: <path>` (SC-1) |
| An unhandled failure: broker unreachable, missing exchange or queue, channel error, unparseable `Content-Type` | `500 Internal Server Error`, `application/json`, `{ "message": "<exception message>" }` (SC-1), for example `{ "message": "java.net.ConnectException: Connection refused" }` |

- No `Content-Type` header is set on either `200` response, because the original returned the AMQP reply without a declared type. This is also a plan default and a Tier 2A verification point.
- A failed passive check is logged at ERROR with the broker's reason before the 500, for example `Passive check of exchange 'sales_exchange' failed: the broker closed the channel with reply-code=404, reply-text=NOT_FOUND - no exchange 'sales_exchange' in vhost '/'`. The 500 body itself then reads `{ "message": "java.io.IOException" }`.
- A reply is returned only when it carries the request's `correlation_id` (`config/CorrelatedReplyRabbitTemplate`). A responder must copy it onto its reply; any other message on the reply queue is discarded with a WARN line, and the wait continues until the timeout.
- Send the body as JSON or plain text. An `application/x-www-form-urlencoded` body, which curl's `--data` sends without `-H`, is rebuilt from the parsed form parameters (`test` becomes `test=`), and the Mule listener turned such a body into a parameter map, so neither forwards those raw bytes.

### Broker prerequisites

Exchange `sales_exchange` and queue `sales_queue` must already exist on the broker, with the queue bound to the exchange. Adapted from the original README's RabbitMQ setup steps:

1. With RabbitMQ and its management plugin running, log in to the web admin console at `http://localhost:15672`.
2. On the **Exchanges** tab, create the exchange `sales_exchange`; the other fields may keep their defaults.
3. On the **Queues** tab, create the queue `sales_queue`; the other fields may keep their defaults.
4. Open `sales_exchange` and, under **Add binding from this exchange**, bind it to the queue `sales_queue` with an empty routing key. With a topic exchange, the binding key `#` also works; that is what the original test declared (`SendingJsonDataToAmqpQueueIT.java:81-83`).

After a request, the number of messages on `sales_queue` goes up, and the queued message can be viewed by opening `sales_queue` and using **Get messages**.

### Sample request

The original README's sample (`README.md:32`), forwarded as-is because the flow does not parse JSON (it is not valid JSON):

```sh
curl -i -X POST -H 'Content-Type: application/json' --data '{"ITEM_ID"= 001, "ITEM_NAME" = "Shirt", "QTY" = 1, "PRICE" = 20}' http://localhost:8081/
```

With no service answering on `sales_queue`, the call returns after the 10 s reply wait (the `Date` value varies):

```console
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 0
Date: <current date>

```

The application logs `payload to be sent: {"ITEM_ID"= 001, "ITEM_NAME" = "Shirt", "QTY" = 1, "PRICE" = 20}`, and the message waits on `sales_queue`.

## Configuration

`src/main/resources/application.yml` keys, read by `config/AmqpConfig` and Spring Boot:

| Key | Default | Source |
|---|---|---|
| `http.port` | `8081` | original placeholder `${http.port}` (`json-to-rabbitmq.xml:4`); `server.port: ${http.port:8081}` |
| `server.address` | `0.0.0.0` | original listener host (`json-to-rabbitmq.xml:4`) |
| `amqp.host` | `localhost` | original test property `amqp.host` (`SendingJsonDataToAmqpQueueIT.java:131`); read through `spring.rabbitmq.host: ${amqp.host:localhost}` |
| `amqp.exchange-name` | `sales_exchange` | `exchangeName` attribute (`json-to-rabbitmq.xml:9`) |
| `amqp.queue-name` | `sales_queue` | `queueName` attribute (`json-to-rabbitmq.xml:9`) |
| `amqp.response-timeout` | `10000` (ms) | `responseTimeout` attribute (`json-to-rabbitmq.xml:9`); must be positive, or the application refuses to start |

- Broker connection: Spring Boot's defaults (port `5672`, username `guest`, password `guest`, virtual host `/`) equal the Mule AMQP connector defaults the original relied on; its `amqp:connector` sets none of them (`json-to-rabbitmq.xml:3`). No credential is written in `application.yml` (AAP 0.4.6). To use other values, set `spring.rabbitmq.username` and `spring.rabbitmq.password` (environment `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`), `spring.rabbitmq.port` or `spring.rabbitmq.virtual-host`.
- Transport timeouts, separate from the reply wait: `spring.rabbitmq.connection-timeout: 10s` (TCP connect) and `spring.rabbitmq.channel-rpc-timeout: 10s` (each channel RPC). `0` is rejected at start-up.
- Fixed listener settings: `spring.web.resources.add-mappings: false` and the excluded `ErrorMvcAutoConfiguration` send every unserved path, `/error` included, to SC-1's 404; `spring.mvc.dispatch-trace-request: true` sends `TRACE` to the 405.
- `src/test/resources/application-test.yml` holds the embedded-broker values for profile `test`: `amqp.host` and `spring.rabbitmq.host` `localhost`, and the embedded broker's demo user `guest`/`guest`. The tests register the broker's free port through `@DynamicPropertySource`.

Original-to-new key map:

| Original | Converted |
|---|---|
| `http.port` | `http.port`, unchanged |
| `amqp.host` (test-only, from the uncommitted `mule.test.properties`) | `amqp.host`, which now feeds `spring.rabbitmq.host` |
| `qpid.work_dir` (test-only) | replaced by a JUnit `@TempDir` |
| inline `exchangeName`, `queueName`, `responseTimeout` | the defaults of `amqp.exchange-name`, `amqp.queue-name`, `amqp.response-timeout` |

Override on the command line, for example `java -jar target/sending-json-data-to-a-amqp-queue-java-1.0.0-SNAPSHOT.jar --http.port=9090 --amqp.host=rabbit.example.com`.

## Mule-to-Java mapping

| Mule construct | Java |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` | Undertow (Tomcat excluded) with `server.address` and `server.port` |
| `http:listener` POST `/` | `controller/SalesController` |
| `logger` | SLF4J INFO in `service/SalesPublisherService` |
| `amqp:outbound-endpoint` request-response | `RabbitTemplate.sendAndReceive` in `service/SalesPublisherService`, configured by `config/AmqpConfig`: exchange, queue, routing key `""`, temporary reply queues, reply timeout 10000 ms; the template is `config/CorrelatedReplyRabbitTemplate` |
| `amqp:connector` defaults | Spring Boot `spring.rabbitmq.*` defaults |
| listener defaults (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` + `exception/ErrorResponse` |
| Mule listener semantics (no CORS policy; threading profile with 1 core thread and a 60 s idle timeout) | `config/PreflightDispatchConfig`, `config/IdleThreadRetirementConfig` |
| `mule-deploy.properties` (loads `json-to-rabbitmq.xml`) | `com.mulesoft.examples.sending_json_data_to_a_amqp_queue.SendingJsonDataToAAmqpQueueApplication` |
| `SendingJsonDataToAmqpQueueIT` (`FunctionalTestCase`, embedded Qpid 0.24) | `SendingJsonDataToAmqpQueueTest` (`@SpringBootTest`, embedded Qpid Broker-J 9.2.1) |

## Externalized or changed behaviours

- `validateConnections="true"` (`json-to-rabbitmq.xml:3`): Mule checked broker connectivity when the connector started. The Spring application connects on the first publish, so a missing broker surfaces as the 500 of the first request rather than a deployment failure.
- Mule-internal `MULE_*` message headers the Mule transport may have added to the AMQP message are not reproduced. Tier 2A capture of the queued message settles which, if any, existed.
- Each request carries a random `correlation_id`, and only a reply carrying it is returned (security hardening against forged replies on the reply queue). How the original matched replies is settled by the `post-root-reply` capture.
- The response `Content-Type` and the empty-body result on timeout are plan defaults and Tier 2A verification points.
- The tests replace Qpid 0.24 with embedded Qpid Broker-J 9.2.1. `src/test/resources/config.json` is rewritten for the 9.x model: memory virtual-host node, Plain provider with `guest`/`guest`, a single AMQP port, no HTTP/JMX/RMI ports, no Derby store.

Studio metadata (`mule-project.xml`, `catalog/`) and `log4j2.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/sending-json-data-to-a-amqp-queue-java/`:

```sh
mvn -B clean install   # build, surefire tests, JaCoCo 80% LINE gate on service/** and mapper/**, enforcer JAX-WS/CXF ban
mvn -B test            # surefire tests only; no broker needed
mvn spring-boot:run    # listener on 0.0.0.0:8081; needs a reachable AMQP 0-9-1 broker with the prerequisites above
```

There is no `mvn verify` live tier: this is not a connector project, so it has no failsafe plugin and no `*IT` class. Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/`, and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it covers |
|---|---|
| `SendingJsonDataToAmqpQueueTest` | the ported original assertion: the body queued on `sales_queue` equals the sent body `test` (`SendingJsonDataToAmqpQueueIT.java:71-75`), against embedded Qpid Broker-J; also a responder's reply returned as the 200 body, and a missing exchange answered with the JSON 500 and left uncreated |
| `controller/SalesControllerTest` | on the wire through ST-1: reply bytes with `Content-Length` and no `Content-Type`; empty 200 without a reply; an empty body published as an empty payload; `GET /` and `OPTIONS /` → 405; `/sales` → 404; a broker failure → JSON 500 |
| `service/SalesPublisherServiceTest` | INFO log line; passive checks before the send, nothing declared or bound; unchanged persistent body; reply body or `null` on timeout; failures propagated unchanged after one attempt, with the broker's reason logged |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: 404, 405 and 500 status lines, `Content-Type` headers and body bytes; `ErrorResponse` JSON escaping; one escaped ERROR log line per unhandled exception |
| `config/AmqpConfigTest` | the defaults keep the original endpoint settings; the context starts without contacting a broker; a non-positive reply or transport timeout fails start-up; a broker withholding a channel RPC reply fails the call after the 10 s budget (stub AMQP peer) |
| `config/CorrelatedReplyRabbitTemplateTest` | on embedded Qpid Broker-J: only the reply with the request's `correlation_id` is returned; forged or uncorrelated messages are discarded |
| `config/IdleThreadRetirementConfigTest` | Undertow worker and AMQP consumer threads retire after 60 s idle without serialising concurrent requests |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight is dispatched as a plain `OPTIONS` request: 405 on `/`, 404 elsewhere, no `Access-Control-*` headers |
| `FixtureParityTest` | ST-2 fixture replay of `src/test/resources/fixtures/SCENARIOS.md`; skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Parity

Label: `PARITY: UNVERIFIED — Mule runtime unavailable`.

Tier 2A is blocked until the Mule EE runtime licence and the EE Maven repository credentials are confirmed (see the root `DECISIONS.md`). No fixture can be captured from the original, so parity rests on the fallback of AAP 0.8.4: `SendingJsonDataToAmqpQueueTest` carries the original committed assertion, and the branch tests above cover every reachable branch. This evidence is weaker than captured fixtures: it shows the conversion meets the original's own assertion and the plan's reading of the platform defaults, not that its bytes equal what the original runtime sent.

Scenarios no original assertion covers, tested only by the new tests: reply received; the HTTP response after the 10 s no-reply wait; `GET /` → 405; unknown path → 404; broker failure → 500.

`SCENARIOS.md` scenarios, all `http` and `deterministic`, every one unverified until captured:

| Scenario | Request | Status in the last run |
|---|---|---|
| `post-root-no-reply` | `POST /`, `Content-Type: text/plain`, body `test` | `UNVERIFIED-uncaptured` |
| `post-root-reply` | `POST /`, `Content-Type: application/json`, the README sample body | `UNVERIFIED-uncaptured` |
| `get-root-method-not-allowed` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-unknown-path` | `POST /sales`, empty body | `UNVERIFIED-uncaptured` |

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| sending-json-data-to-a-amqp-queue-java | sending-json-data-to-a-amqp-queue | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 165 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 165 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 42 of 42 lines of `service/SalesPublisherService` (the project has no `mapper` package). Total project LINE coverage: 97.1% (367 of 378 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=4`, `failed=0`.

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `sending-json-data-to-a-amqp-queue/`.
