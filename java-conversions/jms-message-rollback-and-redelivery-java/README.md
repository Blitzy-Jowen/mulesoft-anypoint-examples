# JMS Rollback and Redelivery — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion, with the Apache Artemis Jakarta JMS client, of the Mule EE 3.8.0 example [`jms-message-rollback-and-redelivery`](../../jms-message-rollback-and-redelivery/) (category JMS/messaging-based flow).

## Original example

The original has one flow file, `src/main/app/jms-redelivery.xml`, the only `config.resources` entry of its `mule-deploy.properties`, holding the single flow `JMSRedeliver`, and one Java class, the custom exception `org.exceptions.MyException`. Its `mule-app.properties` is empty. The original folder `jms-message-rollback-and-redelivery/` stays unchanged beside this project as the reference and the Tier 2A capture source.

Use case, restated from the original README (`README.md:9-10`, `README.md:39`): a JMS message received in a transaction fails with `MyException` on its first four deliveries. The rollback exception strategy rolls it back each time and the broker redelivers it; on the fifth delivery the message is published to topic `topic1` and the transaction commits.

## Endpoints and triggers

There is no HTTP endpoint: `spring.main.web-application-type: none`.

| Role | Destination | Key | Component | Source |
|---|---|---|---|---|
| Trigger | queue `in`, every receive in a locally transacted session (`ALWAYS_BEGIN`) | `jms.inbound-queue` | `trigger/InQueueListener`, a `@JmsListener` on the session-transacted container factory `inQueueListenerContainerFactory` of `config/JmsConfig` | `jms-redelivery.xml:22-24` |
| Output | topic `topic1`, sent inside the listener's transaction (`ALWAYS_JOIN`), persistent delivery | `jms.outbound-topic` | `client/TopicPublisher` | `jms-redelivery.xml:19,35-37` |

- Concurrency: `jms.concurrent-consumers` consumers, default `4`, used as both minimum and maximum. The original `jms:activemq-connector` declares no `numberOfConsumers` (`jms-redelivery.xml:19`), so the Mule 3.8 default of 4 concurrent transacted receivers applied.
- Joining the transaction: `client/TopicPublisher` sends through its own transacted pub-sub `JmsTemplate`, built on the same Boot `ConnectionFactory` bean as the listener container. No transaction manager is configured, so the container exposes its session under that factory and the template sends on it: the publish commits with delivery 5 and is discarded with the rollback of deliveries 1–4.
- Payload: the body of a text, bytes, map or object message, an object body being deserialized only through the filter described in the next item; the values of a stream message, as a list; the message itself for a body-less message. These are the rules of Mule's JMS transformers. A `String` is published as a `TextMessage` and a list as a `StreamMessage`; any other payload goes through Spring's `SimpleMessageConverter`.
- Object message bodies: `trigger/InQueueListener` never lets the JMS provider deserialize them. It reads the serialized form of an Artemis object message itself and deserializes it only through a Java deserialization filter. The filter permits these classes, each by its exact name:
  - `String`, `Boolean`, `Character`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `BigInteger` and `BigDecimal`;
  - `java.util.Date`, whose deserialization reads one `long`;
  - `java.util.ArrayList`, whose deserialization checks its declared size against the limits before allocating its storage and then reads its elements in order without hashing or comparing them. Each element passes through the same filter, so a list holds only permitted values, other `ArrayList`s included;
  - `java.lang.Object`, because `ArrayList` submits its `Object[]` storage to the filter. No `Object` instance can be deserialized, so this admits only `Object` arrays, whose elements the filter still judges;
  - arrays of the permitted classes or of primitives.

  Every other class is rejected. This includes subclasses of the permitted classes, such as `java.sql.Timestamp` and `java.sql.Date`, and other lists and collections, such as `LinkedList`, `Vector`, `Arrays.asList` and `List.of` lists. It also includes sets, maps and other hashing collections, because nested sets and maps make deserialization hash its way into exponential work (SerialDOS), and application and other library classes, because their `readObject` would run on bytes the sender chose. The filter also limits nesting to 8 levels, which admits for example strings in lists nested eight deep or integers in lists nested six deep. It limits references, array and list lengths and bytes read to the length of the serialized form. All of this is checked before any object is created. An object body of a permitted type reaches the flow unchanged. On the fifth delivery a `Date` is published as an `ObjectMessage`, and a list of primitive wrappers, strings, `null`s and `byte[]`s as a `StreamMessage`. A list holding any other value, which a stream message cannot hold, fails that publish in `client/TopicPublisher` and takes the catch branch. A body the filter rejects, a malformed body, or an object message not created by the Artemis client fails with a `MessageFormatException` and takes the catch branch below. This is a deliberate hardening against hostile input: the original's ActiveMQ 5.10.0 client deserialized any class on the classpath.

### Branches

From `service/RedeliveryService` (`jms-redelivery.xml:26-47`), in the order the choice-exception strategy evaluates them:

| Delivery | Branch | Log | Outcome |
|---|---|---|---|
| `JMSXDeliveryCount == 5` | `when` | INFO `Transaction without errors` | published to `topic1`; the session commits |
| any other delivery count | `otherwise` throws `MyException`; rollback branch, `exception.causedBy(org.exceptions.MyException)` | INFO `Entered rollback exception strategy. The message rolls back to its original state for reprocessing.` | `trigger/InQueueListener` pauses (below), then rethrows the same `MyException`; the session rolls back and the broker redelivers |
| any other exception, for example a failed publish to `topic1`, an unreadable message body or an object message the deserialization filter rejects | catch branch, `!exception.causedBy(org.exceptions.MyException)` | ERROR `Catch Exception Strategy: <exception class> at <throwing frame> <- <cause classes>`, then INFO `Entered catch exception strategy. The transaction is commited.` (original spelling kept) | returns normally; the session commits, so the message is consumed and nothing is published |

- The catch branch's ERROR line names exception types and the throwing frame only, never an exception message, because a message can quote the delivered payload.
- Each rollback is also logged at WARN by the listener container, logger `JmsConfig$ConsumerClosingListenerContainer`, as `Execution of JMS message listener failed, and no ErrorHandler has been set.` with the stack trace of the `ListenerExecutionFailedException` that wraps the `MyException`. Spring logs this for every listener exception when no `ErrorHandler` is set, and none is configured.

### Redelivery timing

The original ActiveMQ `RedeliveryPolicy` is `initialRedeliveryDelay` 5000 ms, `redeliveryDelay` 2000 ms, `maximumRedeliveries` 5, no exponential back-off (`jms-redelivery.xml:6-12`). Its `backOffMultiplier` 2 is inert and has no counterpart. Artemis has no separate initial redelivery delay, so the policy is reproduced in two parts:

- Broker side, address settings for `in`: `redelivery-delay` 0 (the Artemis default) and `max-delivery-attempts` 6, which is `maximumRedeliveries` + 1.
- Client side: `trigger/RedeliveryDelay` pauses inside the failing transaction, before the rollback is signalled. The pause is `redelivery.initial-redelivery-delay` (5000 ms) when `JMSXDeliveryCount` is 1 and `redelivery.redelivery-delay` (2000 ms) otherwise. It is skipped when the delivery count exceeds `redelivery.maximum-redeliveries`, because no redelivery follows, and when the configured delay is not positive.

So delivery 2 starts at least 5000 ms after the first failure, and each later delivery at least 2000 ms after the previous one; one message takes at least 11 s (5000 + 3 × 2000 ms) to reach `topic1`. As with ActiveMQ's default blocking redelivery, the consumer serving a paused delivery takes no other message during the pause, while the other consumers keep running. `config/JmsConfig` ensures this in code, for any `spring.artemis.broker-url`: it sets the Artemis `consumerWindowSize` to 0 and closes each receive's consumer before the listener runs.

For an external Artemis broker, add this to `<address-settings>` in `broker.xml`:

```xml
<address-setting match="in"><redelivery-delay>0</redelivery-delay><max-delivery-attempts>6</max-delivery-attempts></address-setting>
```

The `broker.properties` form is `addressSettings.in.redeliveryDelay=0` and `addressSettings.in.maxDeliveryAttempts=6`. Without either, Artemis uses its default `max-delivery-attempts` of 10, which still lets delivery 5 succeed.

## Configuration keys

`src/main/resources/application.yml` keys, read by `config/RedeliveryProperties`, `config/JmsConfig`, `trigger/InQueueListener`, `client/TopicPublisher` and Spring Boot, with the Java type each value binds to:

| Key | Type | Default | Original source | Notes |
|---|---|---|---|---|
| `spring.application.name` | `String` | `jms-message-rollback-and-redelivery-java` | none | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | the original has no HTTP listener | the application opens no port |
| `spring.artemis.broker-url` | `String` | `tcp://localhost:61616` | `failover:(tcp://localhost:61616)` on the `ActiveMQConnectionFactory` bean (`jms-redelivery.xml:13-16`) | Reconnection after a broker loss is handled by the listener container's recovery: the application starts without a broker and retries every 5000 ms, logging ERROR `Could not refresh JMS Connection for destination 'in' - retrying …`. Appending `?reconnectAttempts=-1` gives Artemis-client failover semantics closer to ActiveMQ's `failover:` |
| `jms.inbound-queue` | `String` | `in` | `queue="in"` (`jms-redelivery.xml:22`) | queue `trigger/InQueueListener` consumes |
| `jms.outbound-topic` | `String` | `topic1` | `topic="topic1"` (`jms-redelivery.xml:35`) | topic `client/TopicPublisher` publishes to |
| `jms.concurrent-consumers` | `int` | `4` | Mule 3.8 JMS connector default; `jms-redelivery.xml:19` sets none | fixed consumer count of the listener container |
| `redelivery.initial-redelivery-delay` | `long` (milliseconds) | `5000` | `initialRedeliveryDelay` (`jms-redelivery.xml:8`) | milliseconds; pause before the rollback of delivery 1 |
| `redelivery.redelivery-delay` | `long` (milliseconds) | `2000` | `redeliveryDelay` (`jms-redelivery.xml:9`) | milliseconds; pause before the rollback of every later delivery |
| `redelivery.maximum-redeliveries` | `int` | `5` | `maximumRedeliveries` (`jms-redelivery.xml:7`) | no pause above this count; the broker enforces the limit through `max-delivery-attempts` |

- There are no secrets and no TODO placeholders, because the original connects to the broker anonymously and reads no placeholder. Every key above is a default taken from a literal of the flow, so there is no original-to-new key map. If a broker needs credentials, supply Boot's `spring.artemis.user` and `spring.artemis.password` (both `String`) through the environment variables `SPRING_ARTEMIS_USER` and `SPRING_ARTEMIS_PASSWORD`, and never commit them.
- `spring.artemis.mode` (`ArtemisMode`) is intentionally unset. In production `artemis-jakarta-server` is absent, because the `pom.xml` scopes it to tests, so Boot selects `NATIVE` and connects to `spring.artemis.broker-url`. In tests, where it is on the classpath, Boot selects the embedded in-VM broker.

Override on the command line, for example `java -jar target/jms-message-rollback-and-redelivery-java-1.0.0-SNAPSHOT.jar --spring.artemis.broker-url=tcp://broker.example.com:61616`.

## Mule-to-Java mapping

| Mule construct (`jms-redelivery.xml`) | Java |
|---|---|
| `RedeliveryPolicy` bean (lines 6-12) | `config/RedeliveryProperties` (`redelivery.*`), `trigger/RedeliveryDelay` and the broker address settings above |
| `ActiveMQConnectionFactory` bean (lines 13-16) | Boot's Artemis auto-configuration from `spring.artemis.broker-url` |
| `jms:activemq-connector` (line 19) | `config/JmsConfig`: session-transacted listener container factory, fixed concurrency, zero consumer window |
| `jms:inbound-endpoint queue="in"`, `ALWAYS_BEGIN` (lines 22-24) | `trigger/InQueueListener` |
| `choice`, `logger`, `test:component` throwing `MyException` (lines 26-33) | `service/RedeliveryService.process` |
| `jms:outbound-endpoint topic="topic1"`, `ALWAYS_JOIN` (lines 35-37) | `client/TopicPublisher` |
| `choice-exception-strategy` with its catch and rollback branches (lines 39-47) | `service/RedeliveryService.handleFailure` |
| `org.exceptions.MyException` | `exception/MyException`, unchanged apart from its package |
| `mule-deploy.properties` (loads `jms-redelivery.xml`) | `com.mulesoft.examples.jms_message_rollback_and_redelivery.JmsMessageRollbackAndRedeliveryApplication` |
| `JmsMessageRollbackAndRedeliveryIT` (`FunctionalTestCase`, embedded ActiveMQ `BrokerService`) | `JmsMessageRollbackAndRedeliveryTest` (`@SpringBootTest`, embedded Artemis) |

## Behaviours stubbed or externalized

Externalized behaviour, and behaviour of the original that is not reproduced:

- The ActiveMQ 5 client (`activemq-all` 5.10.0) is replaced by the Artemis Jakarta client. Both the original IT's embedded `BrokerService` and an external ActiveMQ are replaced by Artemis: embedded in tests, external at runtime.
- The Mule `jms:activemq-connector maxRedelivery="5"` check is not reproduced. Mule raises its redelivery-exceeded error only when the redelivery count exceeds 5, which never happens here because delivery 5 succeeds. The original README's step 8 (removing `maxRedelivery`, `README.md:43`) describes a Mule-connector-only variation with no counterpart; the broker-side limit is `max-delivery-attempts`. A message reaches that limit only when deliveries are lost to an application crash during a pause; the broker then moves it to the dead-letter address unpublished.
- The ActiveMQ admin console steps of the original README (`README.md:37,41`) are replaced by sending a text message to `in` with any JMS client. With the Artemis CLI, use two terminals, because the consumer blocks until its message arrives.

  Terminal 1 — subscribe to `topic1` first (non-durable topic: only a subscription made before the publish receives the message) and leave it waiting; it prints `Consumer ActiveMQTopic[topic1], thread=0 wait until 1 messages are consumed`:

  ```sh
  artemis consumer --verbose --destination topic://topic1 --message-count 1 --url tcp://localhost:61616
  ```

  Terminal 2 — once terminal 1 shows it is waiting for messages, send:

  ```sh
  artemis producer --destination queue://in --message-count 1 --message Message123 --url tcp://localhost:61616
  ```

  The application logs the rollback line four times, 5000 ms and then 2000 ms apart, then `Transaction without errors`, and the consumer receives `Message123`. Terminal 1 then prints `Consumer ActiveMQTopic[topic1], thread=0 Received Message123` (`--verbose` makes it print the received text) and exits.

Studio metadata (`mule-project.xml`, `catalog/`) and `log4j2.xml` are not carried (AAP 0.2.2).

## Build, run and test

Prerequisites: JDK 17 and Maven 3.9.x; for `mvn spring-boot:run` only, an Artemis broker on `localhost:61616` with the address settings above. Run from `java-conversions/jms-message-rollback-and-redelivery-java/`:

```sh
mvn -B clean package   # build, surefire tests, JaCoCo 80% LINE gate on service/** and mapper/**, enforcer JAX-WS/CXF ban
mvn -B test            # surefire tests only; no broker needed
mvn -B clean install   # as package, then installs the jar into the local Maven repository
mvn spring-boot:run    # listener on queue "in" of the broker at spring.artemis.broker-url
```

There is no `mvn verify` live tier: this is not a connector project, so it has no failsafe plugin and no `*IT` class. Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/`, and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it covers |
|---|---|
| `JmsMessageRollbackAndRedeliveryTest` | the ported original assertion: `Message123` sent to `in` is received from `topic1` within 30000 ms (`JmsMessageRollbackAndRedeliveryIT.java:40-43`); also delivery counts 1–5 and gaps of at least 5000, 2000, 2000 and 2000 ms. Runs on embedded Artemis with the `in` address settings above; the redelivery delays alone take about 11 s |
| `trigger/InQueueListenerTest` | payload and delivery count handed to the service; on the rollback branch the pause, then the same `MyException` rethrown; no pause on a normal return; bytes, body-less and stream messages; read failures routed to the choice-exception strategy; object bodies, never deserialized by the provider: every permitted scalar type, `Date`, `ArrayList`s of strings, of stream-message values with `null` and `byte[]`, of a `Date`, a `BigDecimal` and a list, and empty, an `Object[]` of permitted values, arrays, and the depth limit (8-dimensional arrays, lists nested eight deep around a string and six deep around an integer) reach the flow unchanged, and an empty body is `null`. These reach the strategy as a `MessageFormatException` without the sentinel running: a `readObject` sentinel alone, in an `Object[]` or in an `ArrayList`; an `ArrayList` holding a `HashSet`; `HashMap` and the SerialDOS set graph; `Date` and `ArrayList` subclasses; other lists; an enum; nesting one level past the limit; an array or list declaring 2^31 − 1 elements; an unknown class; a body naming `java.lang.Object` as a serializable class; a malformed body; and a non-Artemis object message |
| `trigger/InQueueListenerObjectBodyTest` | embedded Artemis: object messages holding the `readObject` sentinel, alone or in an `ArrayList`, or the SerialDOS set graph are consumed by the catch branch on delivery 1, without the sentinel running and without a publication. Permitted object bodies reach `topic1` after five deliveries (delays scaled to 250/100 ms): `Integer` 42 and `Date(0)` as `ObjectMessage`s; an `ArrayList` of `a` and `b` and an `ArrayList` of primitive wrappers, a string, `null` and a `byte[]` as `StreamMessage`s; and a 300 000-character `String`, sent as a large message, as a `TextMessage` |
| `trigger/RedeliveryDelayTest` | requested pauses through an injected sleeper: 5000 ms for delivery 1, 2000 ms for deliveries 2–5, none for 6; custom and non-positive delays; an interrupt ends the pause without an exception |
| `trigger/InQueueListenerBrokerTest` | embedded Artemis reached over TCP in native mode: bursts of 3, 4 and 6 messages over the 4 consumers; every message keeps counts 1–5 and the redelivery gaps (delays scaled to 1250/500 ms) and is published once |
| `service/RedeliveryServiceTest` | every branch with a mocked `TopicPublisher`; the three log lines verbatim; the catch branch's ERROR line holds no exception text |
| `client/TopicPublisherTest` | send to the configured topic; a `JmsException` propagated unwrapped; transacted pub-sub template; a list sent as a `StreamMessage`, other payloads through `SimpleMessageConverter` |
| `config/JmsConfigTest` | zero consumer window on the Artemis factory, bare or behind Boot's caching factory, other beans untouched; the consumer-closing container: consumer closed after a received message, left open on a timed-out receive or an unreadable Artemis large-message body |
| `FixtureParityTest` | ST-2 fixture replay of `src/test/resources/fixtures/SCENARIOS.md`; reported skipped until Tier 2A fixtures exist |

Coverage: JaCoCo LINE ≥ 80 % over `service/**` and `mapper/**`, enforced at `prepare-package`; the project has no `mapper` package. The report is in `target/site/jacoco`.

## Fixture parity (ST-2)

`FixtureParityTest` is ST-2, copied from `hello-world-java` with only its `package` line changed; `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure (see [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation)). Both scenarios are `jms`, `deterministic` and unverified until captured:

| Scenario | Input | Status in the last run |
|---|---|---|
| `jms-in-redelivered-until-fifth-delivery` | `TextMessage` `Message123` to queue `in` | `UNVERIFIED-uncaptured` |
| `jms-in-publish-failure-commits` | the same message, with a broker that refuses sends to `topic1`; without such a broker the scenario stays `UNVERIFIED-uncaptured` | `UNVERIFIED-uncaptured` |

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| jms-message-rollback-and-redelivery-java | jms-message-rollback-and-redelivery | JMS/messaging-based flow | PASS | PASS | 98.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 123 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); enforcer ban passed; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 123 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 77 of 78 lines of `service/RedeliveryService`. Total project LINE coverage: 98.7% (226 of 229 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=2`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed (see the root `DECISIONS.md`). No fixture can be captured from the original, so parity rests on the fallback:

- `JmsMessageRollbackAndRedeliveryTest` carries the original committed assertion, and the branch tests above cover every reachable branch.
- This evidence is weaker than captured fixtures: it shows the conversion meets the original's own assertion and the plan's reading of the flow, not that its messages and log events equal what the original runtime produced.
- The branch no original assertion covers is the catch branch, which handles any failure not caused by `MyException`, such as a failed publish to `topic1`. The original test asserts only the successful fifth delivery.
- Unverified scenarios, until captured: `jms-in-redelivered-until-fifth-delivery`, `jms-in-publish-failure-commits`.
- A fixture is never edited to match the Java output; a mismatch is fixed in the Java code.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `jms-message-rollback-and-redelivery/`.
