# Using transactional scope in JMS to Database — Spring Boot conversion

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8.0 example [`using-transactional-scope-in-jms-to-database`](../../using-transactional-scope-in-jms-to-database/) (category JMS/messaging-based flow). The original folder stays unchanged beside this project and is the Tier 2A capture source.

## Original example

An order arriving as XML on JMS queue `in` is inserted into the MySQL table `orders` inside a transactional scope. A Java component then throws, so the insert is rolled back with the message, the broker redelivers it until the connector's redelivery limit is exceeded, and no row remains. The example shows that a failure inside the scope undoes the database work and skips the scope's remaining processors.

The application is the single flow `transactionsFlow1` of `using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:6-22`, the only `config.resources` entry of `mule-deploy.properties`. The flow declares no exception strategy, although the original README speaks of a rollback strategy: the rollback and redelivery come from the platform's default handling of a transacted JMS receive.

## Endpoints and triggers

The project has no HTTP endpoint: `application.yml` sets `spring.main.web-application-type: none`, and no web server is on the classpath.

| Trigger | Destination | Component | Transaction | Source |
|---|---|---|---|---|
| JMS queue listener | queue `in` (`jms.inbound-queue`), broker `spring.artemis.broker-url` | `trigger/OrderQueueListener`, `@JmsListener` on container factory `jmsListenerContainerFactory` (one consumer) | locally transacted JMS session (`config/TransactionConfig`) | `transactions.xml:3,7` |

Steps of one delivery, in the flow's order:

| Step | Java | Mule origin |
|---|---|---|
| 1. Redelivery check: redelivery count = `JMSXDeliveryCount - 1`, with `JMSXDeliveryCount` taken as 1 when absent; above `jms.max-redelivery`, the delivery is logged at ERROR and consumed unprocessed | `trigger/OrderQueueListener.onMessage` | connector `Active_MQ maxRedelivery="2"` (`transactions.xml:3`) |
| 2. Message text (a `TextMessage`, or a `BytesMessage` of at most 1 MiB decoded as UTF-8) → `model/Order` | `mapper/OrderXmlMapper.toOrder` (Jakarta JAXB, root element `order`) | `mulexml:xml-to-object-transformer` with alias `order` (`transactions.xml:8-10`) |
| 3. INFO log `XXXXXXX order: Order [item_id=…, item_units=…, customer_id=…]` | `trigger/OrderQueueListener` | `logger message="XXXXXXX order: #[payload]"` (`transactions.xml:11`) |
| 4. Transactional scope: `@Transactional(propagation = REQUIRES_NEW, rollbackFor = Exception.class)` over the `DataSourceTransactionManager`; `rollbackFor` is needed because `MyException` is checked | `service/OrderTransactionService.process` | `ee:multi-transactional action="ALWAYS_BEGIN"` (`transactions.xml:12`) |
| 4a. Insert `insert into orders(item_id,item_units,customer_id) values (?, ?, ?)` with bind parameters | `client/OrderRepository.insert` (`JdbcTemplate`) | `db:insert` parameterized query (`transactions.xml:13-16`) |
| 4b. Always throws `MyException("exception customized")` | `service/TestComponent.onCall` (ported) | `component class="org.mule.examples.TestComponent"` (`transactions.xml:17`) |
| 4c. Send the component's result to queue `out` (`jms.outbound-queue`) on the listener's transacted session; never reached | `JmsTemplate` of `config/TransactionConfig` | `jms:outbound-endpoint queue="out"`, `JOIN_IF_POSSIBLE` (`transactions.xml:18-20`) |

Outcome for every order:

- The database transaction rolls back, the exception leaves the listener, and the container rolls the JMS session back, so the broker redelivers. The container's error handler logs each rolled-back delivery at ERROR on one line, without a stack trace:

  ```text
  JMS listener failed: MyException: exception customized at com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service.TestComponent.onCall:30
  ```

- Deliveries 1 to 3 repeat the cycle, each logging the INFO line of step 3. On delivery 4 the redelivery count 3 exceeds `jms.max-redelivery` 2, and the listener logs the original Mule text at ERROR and consumes the message without processing it:

  ```text
  Message with id "<JMS message ID>" has been redelivered 3 times on endpoint "jms://in", which exceeds the maxRedelivery setting of 2 on the connector "Active_MQ". Message payload is of type: ActiveMQTextMessage
  ```

- The `orders` table stays empty and nothing reaches queue `out`.

A document the mapper cannot convert (not well-formed, another root element, an unknown child, a non-integer value, or an entity reference other than the predefined XML ones) fails before step 3 and the transactional scope, with `IllegalArgumentException: Failed to convert XML to Order: <reason>`. It is rolled back and redelivered the same way and consumed on delivery 4, with nothing logged at INFO, inserted or sent.

Sample message, the original README's and the original test's `src/test/resources/message.xml`:

    <order>
        <itemId>1</itemId>
        <itemUnits>2</itemUnits>
        <customerId>1</customerId>
    </order>

## Configuration keys

Every key of `src/main/resources/application.yml`, plus the unset Spring keys a deployment may need.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it holds a credential; `fixed` — a framework setting that reproduces the original, changing it changes behaviour; `unset` — not set in `application.yml`, Spring's default applies.

| Key | Default | Original name | Status | Description |
|---|---|---|---|---|
| `jdbc.url` | `${JDBC_URL:}`, empty | `jdbc.url` (unchanged), `db:generic-config url="${jdbc.url}"` (`transactions.xml:4`) | TODO (secret) | MySQL JDBC URL, with the user and password as URL parameters, bound to `spring.datasource.url`. The comment in `application.yml` reads `# TODO: supply the MySQL JDBC URL, e.g. jdbc:mysql://<host>:3306/<database>?user=<user>&password=<password>`. Boot infers the driver `com.mysql.cj.jdbc.Driver` (Connector/J 8.3.0) from the URL, replacing the original's `com.mysql.jdbc.Driver` (Connector/J 5.1.31) |
| `spring.datasource.url` | `${jdbc.url}` | none | fixed | set `jdbc.url` rather than this key |
| `jms.max-redelivery` | `2` | `maxRedelivery="2"` of connector `Active_MQ` (`transactions.xml:3`) | default | redeliveries processed before a delivery is logged and consumed |
| `jms.inbound-queue` | `in` | `jms:inbound-endpoint queue="in"` (`transactions.xml:7`) | default | queue the listener consumes |
| `jms.outbound-queue` | `out` | `jms:outbound-endpoint queue="out"` (`transactions.xml:18`) | default | queue of the send that follows the component |
| `spring.artemis.mode` | `native` | none | fixed | connect to an external broker; the test profile switches to `embedded` |
| `spring.artemis.broker-url` | `tcp://localhost:61616` | none (the connector declared no `brokerURL`) | default | the broker address the original README has the user start, on Artemis's default acceptor |
| `spring.artemis.user`, `spring.artemis.password` | none | none (the connector declared no credentials) | unset | Spring's standard broker credentials, for a broker that requires them; supply them through `SPRING_ARTEMIS_USER` and `SPRING_ARTEMIS_PASSWORD` |
| `spring.jms.cache.enabled` | `false` | none | fixed | the listener container and the `JmsTemplate` must share the unwrapped connection factory, so the send to `out` joins the listener's session |
| `spring.sql.init.mode` | `never` | none | fixed | the application creates no schema; the table comes from `order.sql` (Setup) |
| `spring.main.web-application-type` | `none` | none | fixed | the original has no HTTP listener |
| `spring.application.name` | `using-transactional-scope-in-jms-to-database-java` | none | fixed | application name in log lines |

- While `jdbc.url` is blank the application still starts: `config/DataSourceConfig` logs one WARN, `jdbc.url (environment variable JDBC_URL) is not configured: the application starts without a database connection, and every database call fails until it is supplied`, and supplies `config/UnconfiguredDataSource`. Every delivery is then converted and logged, fails when the transactional scope opens its database transaction (before the insert), is logged as `JMS listener failed: SQLNonTransientConnectionException: MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured at …`, and is consumed on delivery 4 like any other failure.
- The original test properties `database.url`, `database.user` and `database.password`, which `MySQLDbCreator` read from the never-committed `mule.test.properties`, are dropped. The tests use the in-memory H2 database and the embedded Artemis broker of `src/test/resources/application-test.yml` (profile `test`), which holds no credential or host.
- The original `mule-app.properties` is empty, so no other key exists. The project has no `application-it.yml`: it is not a connector project.

## Mule-to-Java mapping

| Mule construct (`using-transactional-scope-in-jms-to-database`) | Java construct (`com.mulesoft.examples.using_transactional_scope_in_jms_to_database`) |
|---|---|
| `jms:activemq-connector name="Active_MQ" maxRedelivery="2"` (`transactions.xml:3`) | Boot's Artemis `ConnectionFactory` (`spring.artemis.*`); the redelivery limit is enforced by `trigger/OrderQueueListener` from `jms.max-redelivery` |
| `db:generic-config` (`transactions.xml:4`) | Boot's pooled `DataSource` from `spring.datasource.url`, or `config/UnconfiguredDataSource` while it is blank (`config/DataSourceConfig`) |
| flow `transactionsFlow1`, `processingStrategy="synchronous"`, `jms:inbound-endpoint queue="in"` (`transactions.xml:6-7`) | `trigger/OrderQueueListener` on `jmsListenerContainerFactory`: one consumer, session-transacted, every step on the container thread |
| `mulexml:xml-to-object-transformer`, alias `order`, `returnClass="org.mule.examples.Order"` (`transactions.xml:8-10`) | `mapper/OrderXmlMapper` into `model/Order` (ported, Jakarta JAXB `@XmlRootElement`) |
| `logger level="INFO"` (`transactions.xml:11`) | SLF4J INFO in `trigger/OrderQueueListener` |
| `ee:multi-transactional action="ALWAYS_BEGIN"` (`transactions.xml:12`) | `service/OrderTransactionService.process`, `@Transactional(REQUIRES_NEW, rollbackFor = Exception.class)` over the `DataSourceTransactionManager` of `config/TransactionConfig` |
| `db:insert` (`transactions.xml:13-16`) | `client/OrderRepository` (`JdbcTemplate`, bind parameters) |
| `component class="org.mule.examples.TestComponent"` (`transactions.xml:17`), `org.exceptions.MyException` | `service/TestComponent`, `exception/MyException` (ported; Mule `Callable` removed) |
| `jms:outbound-endpoint queue="out"`, `JOIN_IF_POSSIBLE` (`transactions.xml:18-20`) | session-transacted `JmsTemplate` of `config/TransactionConfig`, sending on the listener's session |
| platform handling of a failed transacted receive | the container rolls the session back; its error handler logs `JMS listener failed: …` at ERROR |
| `mule-deploy.properties` (`config.resources=transactions.xml`) | `UsingTransactionalScopeInJmsToDatabaseApplication` (`@SpringBootApplication`) |
| `UsingTransactionalScopeInJMStoDatabaseIT`, `MySQLDbCreator`, `testflows/test-flows.xml` | `UsingTransactionalScopeInJMStoDatabaseTest` on H2 in MySQL mode and embedded Artemis |

## Behaviours stubbed or externalized

- Broker. The original connector declared no `brokerURL`, so Mule used its ActiveMQ default, the in-VM broker `vm://localhost?broker.persistent=false&broker.useJmx=false` inside the runtime, while the original README told users to start a standalone ActiveMQ and send through its web console (`http://localhost:8161/admin/send.jsp`). The Java project connects to an external ActiveMQ Artemis broker at `spring.artemis.broker-url`, default `tcp://localhost:61616`. The broker's `max-delivery-attempts` for `in` must be at least 4 (Artemis default 10) so that the application's own `maxRedelivery` guard handles delivery 4; with a lower value Artemis moves the message to its dead-letter address first and the redelivery-exceeded ERROR is never logged. The original flow configures no redelivery delay, and Artemis redelivers at once by default (`redelivery-delay` 0); redelivery timing is not part of the compared behaviour. The tests start an embedded Artemis broker instead.
- Rollback scope. The listener's session is transacted, so the JMS receive rolls back together with the insert. Failures before the transactional scope, such as a malformed document, are rolled back and redelivered the same way, bounded by `jms.max-redelivery`. Both, and the consumption of a message once the limit is exceeded, are Tier 2A verification points.
- XML binding. Jakarta JAXB replaces Mule's XStream-based transformer. An absent child stays `0`, as XStream left the Java default, and an unknown element fails the conversion, as it did there. Integer text is converted as XStream's `IntConverter` did, with `Long.decode` and its range check, so leading-zero (octal), `0x` and `#` (hexadecimal) forms decode as before and a value above `Integer.MAX_VALUE` up to `0xFFFFFFFF` becomes its 32-bit two's complement. One difference remains: a plain decimal beyond that range is wrapped into the `int` range, where XStream failed the conversion, so such a message is logged and inserted before the component's rollback instead of failing before the scope. Either way no row is kept and the message is consumed on delivery 4. These forms are a Tier 2A verification point; the committed sample uses plain decimals. DTDs and external entities are not processed (`DECISIONS.md`).
- Insert. The values are passed as bind parameters (AAP 0.4.10), as the original's parameterized query already did, so legitimate input yields the same rows.
- Logging. The flow's INFO line and the redelivery-exceeded text are reproduced exactly. Mule logged each failed delivery as a multi-line exception report, the redelivery-exceeded one as a `MessageRedeliveredException`; the Java project logs each failure on one ERROR line without a stack trace, escaped and cut at 256 characters, and the redelivery-exceeded text without the exception class.
- Not carried (AAP 0.2.2): `src/test/resources/log4j2-test.xml`, `mule-project.xml`, `catalog/**`, `src/main/app/mule-deploy.properties` and the empty `mule-app.properties`; `src/test/resources/testflows/test-flows.xml` and `MySQLDbCreator.java`, replaced in the ported test by the query `select count(*) as "count(*)" from orders` on the test profile's H2 database.

Nothing is stubbed at run time: the application talks to the real broker and database it is configured for.

## Setup

Run from `java-conversions/using-transactional-scope-in-jms-to-database-java/`:

1. Create table `orders` in a MySQL database with `src/main/resources/order.sql`, copied unchanged (the original README calls it `import.sql`):

   ```sh
   mysql -u <user> -p <database> < src/main/resources/order.sql
   ```

2. Start an ActiveMQ Artemis broker reachable at `spring.artemis.broker-url` (default `tcp://localhost:61616`), with `max-delivery-attempts` for `in` at least 4 (see above).
3. Supply the database URL:

   ```sh
   export JDBC_URL='jdbc:mysql://<host>:3306/<database>?user=<user>&password=<password>'
   ```

4. Start the application (next section).
5. Send the sample message to queue `in`, with the Artemis CLI of the broker instance or any JMS client:

   ```sh
   <artemis-instance>/bin/artemis producer --url tcp://localhost:61616 --destination queue://in --message-count 1 \
       --message '<order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>'
   ```

6. The log shows three `XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]` lines, three `JMS listener failed: MyException: exception customized at …` lines and then the redelivery-exceeded ERROR. `select count(*) from orders` returns `0`.

## Build, run and test

Run from `java-conversions/using-transactional-scope-in-jms-to-database-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # reads JDBC_URL; add -Dspring-boot.run.arguments=--spring.artemis.broker-url=tcp://<host>:<port> for another broker
java -jar target/using-transactional-scope-in-jms-to-database-java-1.0.0-SNAPSHOT.jar --spring.artemis.broker-url=tcp://<host>:<port>
```

- The tests need no external server, credential or licence: surefire sets `spring.profiles.active=test`, whose `application-test.yml` uses an in-memory H2 database in MySQL mode created from `order.sql` (one database per Spring test context) and an embedded, non-persistent Artemis broker with queues `in` and `out`.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `UsingTransactionalScopeInJMStoDatabaseTest` | the ported `UsingTransactionalScopeInJMStoDatabaseIT` (`using-transactional-scope-in-jms-to-database/src/test/java/org/mule/examples/UsingTransactionalScopeInJMStoDatabaseIT.java:41-50`): `message.xml` sent to `in`; once the redelivery-exceeded line is logged, `select count(*) as "count(*)" from orders` renders exactly `[{count(*)=0}]`, and `out` is empty |
| `trigger/OrderQueueListenerTest` | on the embedded broker: the original path makes 3 inserts, 3 component calls, 3 INFO lines and 3 `JMS listener failed: MyException: exception customized at` lines, then exactly one redelivery-exceeded ERROR with the Artemis `ID:` message ID and `ActiveMQTextMessage`, with no row and nothing on `out`; rejected documents roll back before the logger and the database without logging the rejected value; insert failures roll back before the component, logged escaped onto one line and cut at 256 characters; a succeeding component commits the row and exactly one message on `out` (`JOIN_IF_POSSIBLE`). Without a broker: the guard's boundary (delivery count 3 processed, 4 consumed), a message without `JMSXDeliveryCount`, bytes bodies up to 1 MiB, an unsupported message type, a bytes length outside the bound, an incomplete read and an empty bytes body |
| `trigger/OrderQueueListenerUnconfiguredDatabaseTest` | with `jdbc.url` blank: the context starts with the WARN naming the key; each delivery is converted and logged, then fails naming `jdbc.url` and `JDBC_URL`; the 4th is consumed, and nothing reaches the insert, the component or `out` |
| `service/OrderTransactionServiceTest` | on H2 and the embedded broker: a throwing component rolls back the row and the send and propagates its exception; a succeeding component commits the row and sends its result; a failing insert skips the component; the calls run insert, component, send, in that order |
| `service/TestComponentTest` | `onCall` throws `MyException("exception customized")` for an order and for `null`; `getError()` of both `MyException` constructors, `test` by default |
| `client/OrderRepositoryTest` | `@JdbcTest` on H2 in MySQL mode from the unchanged `order.sql`: one row with the bound values, boundary values round-trip, and the SQL keeps the original column list |
| `mapper/OrderXmlMapperTest` | `message.xml` converts to `Order [item_id=1, item_units=2, customer_id=1]`; an absent child is `0`; empty, malformed, wrong-root and unknown-element documents fail with `Failed to convert XML to Order: `; an external entity fails without leaking file content; integer forms convert as XStream did and an out-of-range plain decimal wraps |
| `config/DataSourceConfigTest` | a blank URL starts on `UnconfiguredDataSource` with one WARN and fails on first use; a configured URL keeps Boot's pooled `DataSource`; every connection request is refused naming the key, SQL state `08001` |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped (`no Tier 2A fixtures captured`) while no fixture exists |

## Fixture parity (ST-2)

`FixtureParityTest` is the reference test of `java-conversions/hello-world-java/` with only its `package` line changed, and `src/test/resources/fixtures/SCENARIOS.md` holds this project's scenario rows, fixture contract and capture procedure (AAP 0.8.4). Its `jms` driver sends each captured message to queue `in` of the embedded Artemis broker and compares the `orders` rows, the browse of `in` and `out`, and the events of the flow's logger category. Fixtures are captured from the running original, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code.

| Scenario | Trigger | State | Request | Status |
|---|---|---|---|---|
| `jms-in-order-rolled-back` | `jms` | `deterministic` | `message.xml` → queue `in` | `UNVERIFIED-uncaptured` |
| `jms-in-malformed-order-xml` | `jms` | `deterministic` | `<order><itemId>1</itemId>` → queue `in` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`. The send to `out` is not a scenario of its own: the component throws on every call, so both scenarios end before it.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| using-transactional-scope-in-jms-to-database-java | using-transactional-scope-in-jms-to-database | JMS/messaging-based flow | PASS | PASS | 96.6% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 97 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 97 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 115 of 119 lines, of which `service` 12 of 12 (`OrderTransactionService`, `TestComponent`) and `mapper` 103 of 107 (`OrderXmlMapper` with its `IntegerTextReader`). Total project LINE coverage: 96.6% (229 of 237 lines).
- A run against an Artemis broker and MySQL, with the sample message sent through the Artemis CLI, logged the sequence of Setup step 6 and left `orders` empty.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`, as recorded in the root `DECISIONS.md`. No fixture has been captured, so parity rests on the fallback:

- `UsingTransactionalScopeInJMStoDatabaseTest` carries the original committed assertion, the exact string `[{count(*)=0}]`.
- The trigger, service, client and mapper tests cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the platform behaviour, not that its rows, messages and log events equal what the original runtime produced.
- Branches no original assertion covers, covered only by the new tests: the consumption of a message on delivery 4 once `maxRedelivery` is exceeded (the original asserted only the row count), the malformed-XML path (`jms-in-malformed-order-xml`), and the send to `out`, which the original never reaches and which only tests with a succeeding component exercise.
- Unverified scenarios, until captured: `jms-in-order-rolled-back`, `jms-in-malformed-order-xml`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `using-transactional-scope-in-jms-to-database/`.
