# Parity scenarios — service-orchestration-and-choice-routing-java

Scenarios for the fixture replay (ST-2) of `service-orchestration-and-choice-routing-java`. The original example is `service-orchestration-and-choice-routing` (folder `service-orchestration-and-choice-routing/`), which loads `fulfillment.xml` and `mule-config.xml` (`config.resources` of `mule-deploy.properties`). Its entry points are the SOAP listener `orderService` on `POST 1080 /orders` (`cxf:jaxws-service IProcessOrder`), the Samsung mock `samsungService` on `POST 9090 /samsung/orders`, the Jersey price service `priceService` on `POST 9999 /api`, the database initialisation `databaseInitialisation` on `GET 8091 /populate`, and the three Ajax inbound channels `/orders/request`, `/orders/soap` and `/orders/manufacturers` of the Ajax connector on port 8090, reached over Bayeux at `/ajax/cometd` (SC-12). `orderService` routes each order item to `samsungOrder` (VM request-response) or `inhouseOrder` (JMS request-response, XA) and dispatches the order to `auditService` (VM one-way, XA). Every reachable branch listed for the example in AAP 0.7.2 and the listener-boundary cases of AAP 0.4.7 are exactly one scenario each below; the unreachable `samsungOrder` catch branch is listed with its proof; and each captured scenario's fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.2.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `post-orders-samsung-item` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = the bytes of `message.xml` (`src/test/resources`): order `12`, one Samsung item `AX02`, quantity `1` | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091` | `/orders` listener → `cxf:jaxws-service IProcessOrder` → session `totalValue` `"0"` → `foreach` → `choice` `manufacturer == 'Samsung'` → VM `samsungOrder` → DataWeave `OrderRequest` → `purchase` on the 9090 listener → 200 filter → `totalValue` += price → DataWeave `PurchaseReceipt` (the committed reply shows ACCEPTED, `totalPrice` 2550.0) → one-way VM `audit` → `order_audits` row with `order_id` 12 and `total_value` 2550 | `fulfillment.xml:28-93,130-136`, IT `orderTest` (`ServiceOrchestrationAndChoiceRoutingIT.java:83-93`) |
| `post-orders-samsung-quantity-5` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = envelope `E1` (Request notes: `message.xml` with quantity `5`) | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091` | as `post-orders-samsung-item`; `purchase` prices `2550 × 5`, so the receipt carries `totalPrice` 12750.0 as the original README shows, and session `totalValue` grows by string concatenation to `"012750"` | `fulfillment.xml:49-78`, `SamsungServiceImpl.java:17-24`, `README.md:55-112` |
| `post-orders-samsung-rejected` | `soap` | `unreachable` | `—` (no request invented) | none | `samsungOrder` `catch-exception-strategy` → Groovy receipt `REJECTED`, `totalPrice` 0; proof: the catch runs only when `samsungOrder` throws, and no order input makes it throw. `OrderItem.quantity` and `OrderRequest.quantity` are primitive `int`, so an absent `quantity` unmarshals as 0, never null, and a value JAXB cannot read as an `int` fails or reads as 0 in `orderService`'s CXF unmarshalling, before `samsungOrder` runs; `name` is a plain string. The Samsung service is this application's own `samsungService` flow, whose `SamsungServiceImpl.purchase` computes `2550 * getQuantity()` on an `int` and returns ACCEPTED for every request, so its listener answers 200 and the status filter passes (AAP 0.8.2) | `fulfillment.xml:62-64,79-92`, `SamsungServiceImpl.java:17-24`, `OrderItem.java:36`, `OrderRequest.java:44-45` |
| `post-orders-inhouse-item` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = envelope `E2` (Request notes: `message.xml` with manufacturer `Philips`) | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091` | `choice` `otherwise` → JMS request-response `inhouseOrder` → `inhouseOrder` flow in an XA transaction (`ALWAYS_BEGIN`) → cached price lookup `GET api/prices/AX02` on the 9999 listener, which accepts POST only → when the lookup succeeds: insert into `orders`, receipt ACCEPTED with `totalPrice` = price × quantity; when it fails: the rollback branch; which one the original takes is a Tier 2A verification point | `fulfillment.xml:38-40,98-129,145` |
| `post-orders-mixed-items` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = envelope `E3` (Request notes: `message.xml` with a second item after the Samsung one: manufacturer `Philips`, name `P3123`, productId `23`, quantity `10`) | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091` | `foreach` over both items in order: the Samsung item through `samsungOrder`, the Philips item through `inhouseOrder`; each receipt enriches its own item (`rootMessage.payload.orderItems[counter - 1]`), so the receipts follow item order | `fulfillment.xml:32-43` |
| `get-orders-method-not-allowed` | `http` | `deterministic` | port `1080`: `GET /orders`, empty body | none | `allowedMethods="POST"` rejects GET (platform 405, `Method not allowed for endpoint: <path>`, AAP 0.4.7; SC-11 `SoapListenerBoundaryAdapter`); a Tier 2A verification point where the captured bytes govern | `fulfillment.xml:29` |
| `post-orders-malformed-envelope` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>` (a truncated envelope) | none | `cxf:jaxws-service` cannot parse the request → HTTP 500 `soap:Client` fault before any operation is dispatched (AAP 0.4.7, SC-11 `SoapListenerBoundaryAdapter`); the fault text is a Tier 2A verification point | `fulfillment.xml:29-30`, AAP 0.4.7 |
| `post-samsung-orders-purchase` | `soap` | `deterministic` | port `9090`: `POST /samsung/orders`, header `Content-Type: text/xml`, body = envelope `E4` (Request notes: `purchase` in `http://samsung.se.mulesoft.com/`, `orderRequest` with name `s-1` and quantity `1`) | none | `samsungService` flow → logger `aaaa` → `cxf:jaxws-service SamsungService` → `SamsungServiceImpl.purchase` → `purchaseResponse` with id `1`, ACCEPTED and price `2550 × quantity` | `fulfillment.xml:150-155`, `SamsungServiceImpl.java:17-24` |
| `get-samsung-orders-method-not-allowed` | `http` | `deterministic` | port `9090`: `GET /samsung/orders`, empty body | none | `allowedMethods="POST"` rejects GET (platform 405, AAP 0.4.7; SC-11 `SoapListenerBoundaryAdapter`); a Tier 2A verification point where the captured bytes govern | `fulfillment.xml:151` |
| `post-api-prices` | `http` | `deterministic` | port `9999`: `POST /api/prices/AX02`, empty body | none | `priceService` listener (POST only) → `jersey:resources` `ProductPrice`, whose `getProductPrice` is `@GET /prices/{productId}` producing `text/plain` `159`; what the `@GET`-only resource answers to a POST behind the POST-only listener is a Tier 2A verification point | `fulfillment.xml:144-149`, `ProductPrice.java:17-25` |
| `get-api-prices-method-not-allowed` | `http` | `deterministic` | port `9999`: `GET /api/prices/AX02`, empty body | none | `allowedMethods="POST"` rejects GET before Jersey (platform 405, AAP 0.4.7); the method and path the in-house price lookup sends (`fulfillment.xml:105`); a Tier 2A verification point where the captured bytes govern | `fulfillment.xml:145` |
| `get-populate-created` | `http` | `deterministic` | port `8091`: `GET /populate`, empty body | empty database: neither `orders` nor `order_audits` exists | `databaseInitialisation` → DDL `CREATE TABLE orders` → DDL `CREATE TABLE order_audits` → `set-payload db populated` | `fulfillment.xml:159-168`, IT `populateDatabase` (`ServiceOrchestrationAndChoiceRoutingIT.java:95-105`) |
| `get-populate-already-populated` | `http` | `deterministic` | port `8091`: `GET /populate`, empty body | both tables exist: the prior request `GET /populate` on port `8091` creates them when they are absent | the first DDL fails → `catch-exception-strategy` → `set-payload table already populated` | `fulfillment.xml:169-171` |
| `ajax-orders-request` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, header `Content-Type: application/x-www-form-urlencoded`, body = form field `message` holding the last message of Bayeux sequence `S1` (Request notes), which publishes the order `A1` on `/orders/request` with `replyTo` `/orders/request#<clientId>` | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091`; the earlier messages of `S1` as prior requests | `orderRequest` flow: DataWeave JSON → order XML → `XmlToSoapEnvelope.xsl` → `POST orders` on 1080 → `SoapEnvelopeToBodyChild.xslt` → DataWeave summary JSON → reply on `replyTo`; varies: the Bayeux `clientId` the handshake generates, which names the `replyTo` channel and appears in every later message | `mule-config.xml:5-37`, docroot `index.html:119` |
| `ajax-orders-soap` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, header `Content-Type: application/x-www-form-urlencoded`, body = form field `message` holding the last message of Bayeux sequence `S1`, which publishes the bytes of `message.xml` (`src/test/resources`) as data on `/orders/soap` | tables `orders` and `order_audits` created by the prior request `GET /populate` on port `8091`; the earlier messages of `S1` as prior requests | `orderProxy` flow: the envelope passes unchanged to `POST orders` on 1080 → the response as a string on `replyTo`; varies: the Bayeux `clientId` | `mule-config.xml:38-42`, docroot `index.html:160` |
| `ajax-orders-manufacturers` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, header `Content-Type: application/x-www-form-urlencoded`, body = form field `message` holding the last message of Bayeux sequence `S1`, which publishes the empty string as data on `/orders/manufacturers` | the earlier messages of `S1` as prior requests | `manufacturers` flow: `expression-transformer` `["Samsung","Philips","Sony"]` → reply on `replyTo`; varies: the Bayeux `clientId` | `mule-config.xml:43-46`, docroot `index.html:166` |
| `audit-over-5000-rollback` | `soap` | `deterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = envelope `E5` (Request notes: `message.xml` with quantity `3`, so `totalValue` reads `"07650"`) | `orders` exists, `order_audits` does not | Samsung path as in `post-orders-samsung-item`; the one-way `auditService` insert into the missing `order_audits` fails → `choice-exception-strategy` → `rollback-exception-strategy` with `when` `#[sessionVars.totalValue] > 5000`; the SOAP reply does not wait for the audit; no `order_audits` row; which branch that `when` attribute selects, and the redelivery a rollback causes, are Tier 2A verification points | `fulfillment.xml:137-138` |
| `audit-failure-logged` | `soap` | `nondeterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = the bytes of `message.xml` (`src/test/resources`), so `totalValue` reads `"02550"` | `orders` exists, `order_audits` does not | the audit insert fails with `totalValue` not above 5000 → `catch-exception-strategy` → `defaultErrorHandler` INFO `Error occurred: #[payload]`; varies: the logged payload, an `Object.toString()` with an identity hash | `fulfillment.xml:139-141,156-158` |
| `inhouse-redelivery-exhausted` | `soap` | `nondeterministic` | port `1080`: `POST /orders`, header `Content-Type: text/xml`, body = envelope `E2` (Request notes: `message.xml` with manufacturer `Philips`) | `order_audits` exists, `orders` does not, so the in-house insert fails on every delivery | `inhouseOrder` fails → `rollback-exception-strategy` logs `#[payload]` at INFO and the delivery rolls back; after `maxRedeliveryAttempts` 3, `on-redelivery-attempts-exceeded` → `defaultErrorHandler`; no `orders` row and no reply to the JMS request-response; varies: the logged payloads, `Object.toString()` renderings with an identity hash | `fulfillment.xml:123-128` |
| `wrong-port-no-listener` | `http` | `deterministic` | port `8091`: `GET /orders`, empty body | none | port 8091 has only the `/populate` listener, so no listener serves `/orders` there → no-listener platform default (404, `No listener for endpoint: <path>`, AAP 0.4.7; SC-10 `PortRoutingFilter`); a Tier 2A verification point where the captured bytes govern | `fulfillment.xml:24,160`, AAP 0.4.7 |

Fourteen scenarios are `deterministic`: `post-orders-samsung-item`, `post-orders-samsung-quantity-5`, `post-orders-inhouse-item`, `post-orders-mixed-items`, `get-orders-method-not-allowed`, `post-orders-malformed-envelope`, `post-samsung-orders-purchase`, `get-samsung-orders-method-not-allowed`, `post-api-prices`, `get-api-prices-method-not-allowed`, `get-populate-created`, `get-populate-already-populated`, `audit-over-5000-rollback` and `wrong-port-no-listener`. Five are `nondeterministic`: `ajax-orders-request`, `ajax-orders-soap`, `ajax-orders-manufacturers`, `audit-failure-logged` and `inhouse-redelivery-exhausted`. One, `post-orders-samsung-rejected`, is `unreachable`. Ten rows are `soap` and ten are `http`.

Request notes:

- Source cells name the original files by base name. `fulfillment.xml` and `mule-config.xml` are in `service-orchestration-and-choice-routing/src/main/app/`, and docroot `index.html` is `service-orchestration-and-choice-routing/src/main/app/docroot/index.html`. IT `ServiceOrchestrationAndChoiceRoutingIT.java` is `service-orchestration-and-choice-routing/src/test/java/org/mule/examples/ServiceOrchestrationAndChoiceRoutingIT.java`, and `README.md` is `service-orchestration-and-choice-routing/README.md`. `ProductPrice.java` and `OrderItem.java` are in `service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/orders/`, and `SamsungServiceImpl.java` and `OrderRequest.java` are in `service-orchestration-and-choice-routing/src/main/java/com/mulesoft/se/samsung/`.
- `message.xml` is `src/test/resources/message.xml` of this project, a byte copy of `service-orchestration-and-choice-routing/src/test/resources/message.xml`, the envelope the original test posts (order `12`, customer John Doe, one item: manufacturer `Samsung`, name `s-1`, productId `AX02`, quantity `1`). It is sent from the original folder's copy (Capture step 4) and never inlined. The inline body of `post-orders-malformed-envelope` is written byte for byte as its code span shows, without a line ending.
- The derived envelopes are `message.xml` with exactly one edit, made by the `sed` expression shown; each pattern occurs once in the file, and every other byte, the final line ending included, is kept. Capture step 4 writes each result into the scenario's evidence directory as `request.xml` and sends that file.
  - `E1`: quantity 5: `s|<quantity>1</quantity>|<quantity>5</quantity>|`.
  - `E2`: manufacturer Philips, which sends the item down the in-house path: `s|<manufacturer>Samsung</manufacturer>|<manufacturer>Philips</manufacturer>|`.
  - `E3`: a second item, inserted directly after the Samsung item's closing tag: `s|</item>|</item><item><manufacturer>Philips</manufacturer><name>P3123</name><productId>23</productId><quantity>10</quantity></item>|`. The second item is the Philips item of the original's Studio sample `src/test/resources/sample_data/json_1.json`.
  - `E5`: quantity 3, which makes `2550 × 3` exceed 5000: `s|<quantity>1</quantity>|<quantity>3</quantity>|`.
- `E4` is a SOAP 1.1 document/literal-wrapped `purchase` request. Its wrapper is qualified by the `samsung` package namespace and its child elements carry none, as the generated JAXB classes bind them (AAP 0.3.2). The body is exactly the bytes of this single line, without a line ending:

  ```xml
  <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:sam="http://samsung.se.mulesoft.com/"><soapenv:Body><sam:purchase><orderRequest><name>s-1</name><quantity>1</quantity></orderRequest></sam:purchase></soapenv:Body></soapenv:Envelope>
  ```

- `A1` is the order of the original's Studio sample `src/test/resources/sample_data/json_1.json`. That folder is not carried, so the order is quoted here as one line. The copied page sends it as a string, `JSON.stringify(order)` (docroot `index.html:118-120`). The data string is exactly these bytes, without a line ending:

  ```json
  {"orderId":"123","customer":{"firstName":"Nial","lastName":"Darbey","address":"Buenos Aires"},"orderItems":[{"item":{"manufacturer":"Samsung","name":"Galaxy","productId":"i9000","quantity":"30"}},{"item":{"manufacturer":"Philips","name":"P3123","productId":"23","quantity":"10"}}]}
  ```

- `S1` is the Bayeux 1.0 message sequence behind `mule.rpc(channel, data, callback)` of the copied `mule-resource/js/_mule.js` (SC-12). Each step is one `POST /ajax/cometd` on port 8090, whose form field `message` holds a JSON array with one message, and `<clientId>` is the client ID the handshake answer returns:
  1. `/meta/handshake` with `version` `1.0` and `supportedConnectionTypes` `["long-polling"]`;
  2. `/meta/connect` for `<clientId>` with `connectionType` `long-polling`;
  3. `/meta/subscribe` for `<clientId>` to `<channel>#<clientId>`, the `replyTo` channel `mule.rpc` subscribes to;
  4. the publication on `<channel>` for `<clientId>`, whose `data` is `{"data": <data string>, "replyTo": "<channel>#<clientId>"}`, as `mule.rpc` builds it;
  5. `/meta/connect` for `<clientId>`, held until the server delivers what it holds for the client, the reply on `replyTo` included, or until its poll timeout ends. This last message is the scenario's own `request`; steps 1 to 4 are its prior requests (`state.requests`).

Notes for this project:

- Facts behind the rows, read from the original source:
  - `totalValue` starts as the session string `"0"` (`fulfillment.xml:31`) and grows by MEL string concatenation with the Samsung price string (`fulfillment.xml:65`), hence `"02550"`, `"07650"` and `"012750"`. The audit's `choice-exception-strategy` compares it in the `when` attribute `#[sessionVars.totalValue] > 5000` (`fulfillment.xml:138`).
  - `SamsungServiceImpl.purchase` returns id `"1"`, result `ACCEPTED` and price `2550 × quantity` as a string (`SamsungServiceImpl.java:17-24`).
  - `ProductPrice` is `@Path("/prices")` with `@GET @Path("/{productId}")` returning `"159"` as `text/plain` (`ProductPrice.java:17-25`). It is mounted under the `api` listener on 9999, which declares `allowedMethods="POST"` (`fulfillment.xml:144-149`), while the in-house flow calls it with GET (`fulfillment.xml:105`). These declarations are Tier 2A verification points: the converted code preserves them, and capture settles what the original returned.
  - The original's own outbound HTTP calls go to its own listeners. The `HTTP_Request_Configuration` host is `0.0.0.0` (`mule-config.xml:4`), and the ports are written into each `http:request`: 9090 for the Samsung call (`fulfillment.xml:96`), 9999 for the price lookup (`fulfillment.xml:105`), and 1080 for the two Ajax flows (`mule-config.xml:21,40`). No placeholder sets any of them.
- State rules:
  - The three `ajax-*` rows are `nondeterministic` because the Bayeux `clientId`, and with it the `replyTo` channel, is generated by the server.
  - `audit-failure-logged` and `inhouse-redelivery-exhausted` are `nondeterministic` because the logged payload carries an `Object.toString()` identity hash.
  - Every other reachable row is `deterministic`.
  - `post-orders-samsung-rejected` is `unreachable`, with the proof in its row. The Java code keeps the branch exactly as the original declares it. The row is excluded from capture and from the branch-coverage claim, has no fixture and reports `UNREACHABLE` (AAP 0.8.2). AAP 0.8.2 names the loan broker's Bank 5 `otherwise` as the only unreachable branch; this row is a second one, reported for `DECISIONS.md`.
  - `inhouse-redelivery-exhausted` seeds `orders` absent because otherwise it would share its request and its state with `post-orders-inhouse-item`. A missing `orders` table makes its rollback branch certain, however the 9999 price lookup behaves.
- Not captured: the identity column `i` of `orders` and `order_audits`, which Derby assigns and no flow reads, and broker message IDs and timestamps (AAP 0.8.4).
- Ports: every `Request` starts with the listener port the capture targets, which is the port the original fixes (`fulfillment.xml:24-27`, `mule-config.xml:3`):
  - 1080 for `/orders`;
  - 8090 for the Ajax docroot `/orders/` and the Bayeux endpoint `/ajax/cometd`;
  - 8091 for `/populate`;
  - 9090 for `/samsung/orders`;
  - 9999 for `/api`.

  In the Java project, 1080 is `server.port`. The other four are the SC-10 `listeners.additional` entries `ajax`, `populate`, `samsung` and `prices` of `src/main/resources/application.yml`, whose ports are set by the keys `ajax.port`, `ports.populate`, `ports.samsung` and `ports.prices`. Fixtures of the `1080` rows leave `request.portProperty` at its default `local.server.port`. Every other request names its listener's key in `portProperty`, prior requests included (`ports.populate` for the prior `GET /populate`, `ajax.port` for the earlier messages of `S1`), and records the captured port in `request.port`. The copied ST-2 driver reaches each additional listener through that key, so no row lacks a port driver.
- Shared `state.properties`: every fixture of this project carries the same seven entries.

  ```json
  {"server.port": "${freePort:orders}", "ajax.port": "${freePort:ajax}", "ports.populate": "${freePort:populate}", "ports.samsung": "${freePort:samsung}", "ports.prices": "${freePort:prices}", "orders.soap.url": "http://localhost:${freePort:orders}/orders", "spring.datasource.xa.properties.databaseName": "memory:muleEmbeddedDB"}
  ```

  - The application calls its own listeners through `orders.samsung.url` (port `ports.samsung`), `orders.price.url` (port `ports.prices`) and `orders.soap.url` (`http://localhost:1080/orders`). The replay boundary admits such targets only on replay-owned ports, so the five listener ports are free ports and `orders.soap.url` follows the main one. No body and no `Location` this project captures carries a listener port, so free ports change no compared byte.
  - `memory:muleEmbeddedDB` gives the replay context a new in-memory Derby database, `jdbc:derby:memory:muleEmbeddedDB`, which the replay owns. The default database lives under the `derby.system.home` that the surefire configuration of `pom.xml` sets (`target/derby-test`). That location is outside the replay root, where table effects are never read.
  - These properties configure the application and seed no data.
- Upstream capture: some rows cannot have their outbound HTTP calls recorded, because those calls go to the original's own listeners. Their hosts and ports are written into the flows (see the facts above), no runtime property redirects them through WireMock, and the original folder is never modified.
  - These rows omit `upstream` and report `UNVERIFIED-no-upstream` (Capture step 2), as the calls `foreach-processing-and-choice-routing` makes to its own mock listeners do: `post-orders-samsung-item`, `post-orders-samsung-quantity-5`, `post-orders-inhouse-item`, `post-orders-mixed-items`, `ajax-orders-request`, `ajax-orders-soap`, `audit-over-5000-rollback`, `audit-failure-logged` and `inhouse-redelivery-exhausted`.
  - Every other reachable row makes no outbound HTTP call and carries `"upstream": []`.
  - The in-house JMS request-response, the VM dispatches and the Derby statements are not HTTP calls. Their effects are read back into `response` (Capture step 8).
- Table state: the replay seeds rows into existing tables (`state.tables`) but cannot create or drop a table, so each row's tables come from the application itself.
  - A prior `GET /populate` creates both tables.
  - `get-populate-created` relies on the replay's new in-memory database being empty when it runs. That holds because no replayed row before it reaches the database. The rows before it that call the application's own listeners omit `upstream`, so they are skipped before their prior requests are sent. The others touch no table.
  - No request can produce the partial states of `audit-over-5000-rollback`, `audit-failure-logged` and `inhouse-redelivery-exhausted`, where one table is present and the other absent. Those rows omit `upstream`, so they are never replayed, and their captured tables are kept as evidence.
- Bayeux replay gap:
  - The replay sends the earlier messages of `S1` verbatim from `state.requests` and discards their replies. Steps 2 to 5 therefore carry the client ID the original issued, which the Java broker does not know: it issues its own random client IDs (`service/AjaxChannelBroker`). The copied ST-2 driver cannot carry a server-issued value from one reply into later requests.
  - `ajax-orders-manufacturers` is the one Ajax row with `"upstream": []`. While the gap stands it keeps its capture evidence in `src/test/capture/ajax-orders-manufacturers/` but gets no fixture, so it reports `UNVERIFIED-uncaptured` rather than a `FAILED` that would come from the replay, not from the conversion.
  - The gap is reported against the ST-2 reference, `java-conversions/hello-world-java/src/test/java/com/mulesoft/examples/hello_world/FixtureParityTest.java`. The copied test is not changed.
  - `ajax-orders-request` and `ajax-orders-soap` omit `upstream`, so they are skipped before their prior requests are sent, and the gap does not change their status.
- Unverified rows: the five `nondeterministic` rows are compared for structure only and are never presented as byte-for-byte evidence. They and the other unverified rows are escalated as AAP 0.8.8 sets out:
  - Through `DECISIONS.md`, the person who receives the Tier 2A notification receives their list with the reasons above: nondeterministic output, no upstream recording, or the Bayeux client-ID gap.
  - A row's status changes only on that person's input. A reproducibility input, such as a means to record the self-calls or a driver that carries the Bayeux client ID, leads to a new capture compared byte for byte. A recorded acceptance is shown in the README as `ACCEPTED — structural comparison` and still counts as unverified.
- Parity label:
  - Until fixtures are captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7). It is the label of `java-conversions/service-orchestration-and-choice-routing-java/README.md` and of this project's row in the root `SUMMARY.md`, and both list the nineteen reachable scenarios as unverified.
  - After capture the label is at most `PARITY: PARTIAL — Tier 2A fixtures: 9 verified, 10 unverified`, or `PARITY: FAIL — Tier 2A fixtures` when any captured deterministic row differs. `PARITY: VERIFIED — Tier 2A fixtures` is impossible while the ten rows above stay unverified.
  - The nine rows that can be `VERIFIED` are `get-orders-method-not-allowed`, `post-orders-malformed-envelope`, `post-samsung-orders-purchase`, `get-samsung-orders-method-not-allowed`, `post-api-prices`, `get-api-prices-method-not-allowed`, `get-populate-created`, `get-populate-already-populated` and `wrong-port-no-listener`. The README lists each as unverified until it is captured and `VERIFIED`.

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
- Every row of this file is captured by sending its request to the unmodified original, deployed on the ports its flows fix (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`1080`, `8090`, `8091`, `9090` or `9999` in this project, as the row's `Request` names it). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password}` for HTTPS listeners, replayed through ST-1's TLS mode. `trustStore` resolves from the classpath first, then as a file.
- `readTimeoutMillis`: optional; the replay's socket read budget for this request, a positive whole number of milliseconds, applied through ST-1's `HttpTestClient.withReadTimeout` to every read of the reply, the TLS handshake's included; default 10000, ST-1's `TIMEOUT_MILLIS`, which also stays the connect timeout. Like `awaitMillis`, it is a replay setting, not a captured value: a fixture sets it when the endpoint itself waits about 10000 ms or longer before it answers, so that the replay reads the captured answer instead of timing out before the comparison.

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
- `clearCaches`: optional, `true` or `false`, the default. `true` stands for the restart the capture made before the scenario, after which the original's in-memory stores were empty: once the `state` is seeded, and before the prior requests and the input, the replay empties every cache of every `org.springframework.cache.CacheManager` bean of the application context. A project without such a bean reports the scenario `UNVERIFIED-missing-driver`, since the replay cannot re-create the empty stores the capture started from. Without `clearCaches`, the application's caches and its other in-memory state carry over from the scenarios before, in table order.
- `requests`: optional; the prior requests the capture sent before the scenario's own (Capture step 4), in the order sent, each an object of the `request` shape for `http` and `soap` above (`method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `portProperty`, `tls`, `readTimeoutMillis`). The replay sends them through ST-1 after the `state` is seeded, its caches cleared and the `upstream` stubs installed, and before the input. Their replies are discarded, as the capture discarded them, whatever their status; one that cannot be sent, or whose reply cannot be read, fails the scenario. Everything else they cause belongs to the scenario: their outbound calls are recorded in `upstream`, in call order, and the effects read back after the run (files, messages, rows, documents, mails) include theirs. Only their log events are left out: the replay drops the events logged before the input, as `response.logs` holds the events from the input on.
- For this project, `state` holds the same seven `properties` in every fixture (see the notes under Scenarios); `requests`, holding the prior `GET /populate` of the rows seeded with both tables and the earlier messages of `S1` of the Ajax rows; and `clearCaches` `true` for a row captured right after a restart of the original (Capture step 4). It holds no `tables`: no row seeds table rows, and a table's absence cannot be seeded.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every reachable scenario of this project except the nine whose flow path calls the original's own listeners over HTTP, whose fixtures omit `upstream` (next item and the notes under Scenarios).
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
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match and compares it again 500 ms later, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.

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

Skeleton of a fixture of this project, here for `get-populate-already-populated`; every `request`, `state.requests` and `response` value comes from the capture, and `state.properties` is the shared set of the notes under Scenarios:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>",
                "port": "<captured>", "portProperty": "ports.populate" },
  "state":    { "properties": { "server.port": "${freePort:orders}", "ajax.port": "${freePort:ajax}",
                                "ports.populate": "${freePort:populate}", "ports.samsung": "${freePort:samsung}",
                                "ports.prices": "${freePort:prices}",
                                "orders.soap.url": "http://localhost:${freePort:orders}/orders",
                                "spring.datasource.xa.properties.databaseName": "memory:muleEmbeddedDB" },
                "requests": [ { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                                "headers": [["<captured>", "<captured>"]], "body": "<captured>",
                                "portProperty": "ports.populate" } ] },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
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

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists the nineteen reachable scenarios as `UNVERIFIED-uncaptured` and `post-orders-samsung-rejected` as `UNREACHABLE`, with `verified=0`, `unverified=19` and `failed=0`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, `prior-<n>-curl-trace.txt`, for a scenario with prior requests (step 4), the trace of its `n`-th prior request, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. In this project the evidence also holds `request.xml`, the SOAP body curl sent; `data.txt`, the data string of an Ajax publication; `tables.txt`, the Derby rows read back after the run; `inhouseOrder-browse.txt`, the browse of the in-house queue; and `mule.log`, the runtime log from the input to the end of the run. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/service-orchestration-and-choice-routing-java/`).

1. Build the unmodified original `service-orchestration-and-choice-routing` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for service-orchestration-and-choice-routing. Its only outbound HTTP calls go to its own listeners: the Samsung `purchase` on 9090, the price lookup on 9999 and the two Ajax flows' `POST orders` on 1080. They are sent to the host `0.0.0.0` of `HTTP_Request_Configuration` on ports the flows hard-code (`mule-config.xml:4,21,40`, `fulfillment.xml:96,105`). No placeholder or runtime property redirects them, the listeners themselves hold those ports, and the original folder is never edited. So the nine rows of the notes under Scenarios omit `upstream`, and no other scenario makes an outbound HTTP call. Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
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
3. Deploy the archive to a licensed Mule 3.8 EE runtime. The application needs no property: the flows fix the host `0.0.0.0` and the port of every listener (`fulfillment.xml:24-27`, `mule-config.xml:3`), the runtime supplies `app.home` for the Ajax docroot (`mule-config.xml:3`), and `service-orchestration-and-choice-routing/src/main/app/mule-app.properties` defines no key. Ports 1080, 8090, 8091, 9090 and 9999 must be free on the capture host.
   - Expose the runtime's JVM over JMX, reachable from the capture host only, for example with `-M-Dcom.sun.management.jmxremote.port=<J>` and `-M-Djava.rmi.server.hostname=localhost`. The in-VM ActiveMQ broker of `vm://localhost` (`fulfillment.xml:4`) registers its MBeans there, so step 4 can browse it. `MULE_OPTS` below holds these start options, so every restart of step 4 starts the runtime the same way.
   - Derby creates `muleEmbeddedDB` (`fulfillment.xml:9`) in the runtime's working directory, or under `derby.system.home` when that is set; `DB` below names its path, and `LOG` names the application's log file under `$MULE_HOME/logs/`. Before the first scenario, stop the runtime and delete `DB`, as the original test deletes its database before it runs (`ServiceOrchestrationAndChoiceRoutingIT.java:61-68`), then start it again, so the database starts empty.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps every request on HTTP/1.1, whose status line carries the reason phrase.

   A scenario whose original must first receive other requests (a cache they fill, a counter they advance) sends those prior requests before its own, in order, each through `prior` below, which keeps the request's trace as the evidence `prior-<n>-curl-trace.txt`, `n` counting from 1, and discards the response, which the scenario's `response` never holds; a non-zero exit status means the capture failed. A capture that restarts the original before a scenario, so that its in-memory stores start empty, records that restart as `state.clearCaches` (step 8). In this project the prior requests are the `GET /populate` that creates both tables and the first four messages of `S1`. A restart comes before every row that needs another table set, because embedded Derby admits one JVM: the tables are changed and read with Derby's `ij` while the runtime is stopped.

   The helpers below are used by the commands that follow:
   - `soap` keeps a request body in the evidence as `request.xml`, the exact bytes curl sends.
   - `ajax` sends the Bayeux sequence `S1` and keeps its data string as `data.txt`.
   - `restart` stops the runtime, runs one command and starts the runtime again.
   - `tables` drops both tables and creates the named ones with the original DDL (`fulfillment.xml:162,166`).
   - `readback` writes the rows of both tables, `i` excluded, ordered by `i`, to `tables.txt`.
   - `mark` and `post` keep the runtime log from the input on as `mule.log`, wait for the one-way audit (its `responseTimeout` is 10000 ms, `fulfillment.xml:44,131`) and the in-house redeliveries, and read the tables back.

   `ij` comes from Derby 10.9.1.0, the version of the original's `pom.xml`, so the database is never upgraded. `logged` waits until the run's part of the log shows the `defaultErrorHandler` event `Error occurred:` (`fulfillment.xml:157`), so the logged run is complete before `post`.

   ```sh
   O=../../service-orchestration-and-choice-routing/src/test/resources/message.xml
   F=target/fixture-capture; mkdir -p "$F"
   DERBY="$HOME/.m2/repository/org/apache/derby/derby/10.9.1.0/derby-10.9.1.0.jar:$HOME/.m2/repository/org/apache/derby/derbytools/10.9.1.0/derbytools-10.9.1.0.jar"
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   prior() { d="src/test/capture/$1"; n=$2; shift 2; mkdir -p "$d"; curl -sS -v --http1.1 -o /dev/null "$@" 2> "$d/prior-$n-curl-trace.txt"; }
   soap() { d="src/test/capture/$1"; mkdir -p "$d"; cat > "$d/request.xml"; capture "$1" -X POST -H 'Content-Type: text/xml' --data-binary "@$d/request.xml" "$2"; }
   populate() { prior "$1" 1 http://localhost:8091/populate; }
   restart() { "$MULE_HOME/bin/mule" stop; "$@"; "$MULE_HOME/bin/mule" start $MULE_OPTS; until curl -s -o /dev/null http://localhost:8091/; do sleep 2; done; }
   ij() { java -cp "$DERBY" org.apache.derby.tools.ij; }
   tables() {
     { echo "connect 'jdbc:derby:$DB';"; echo 'DROP TABLE orders;'; echo 'DROP TABLE order_audits;'
       for t in "$@"; do case $t in
         orders) echo 'CREATE TABLE orders (i int generated always as identity, product_id varchar(256), name varchar(256), manufacturer varchar(256), quantity integer, price integer);';;
         order_audits) echo 'CREATE TABLE order_audits (i int generated always as identity, order_id varchar(256), total_value integer);';;
       esac; done; } | ij; }
   readback() {
     { echo "connect 'jdbc:derby:$DB';"; echo 'SELECT product_id, name, manufacturer, quantity, price FROM orders ORDER BY i;'
       echo 'SELECT order_id, total_value FROM order_audits ORDER BY i;'; } | ij > "src/test/capture/$1/tables.txt"; }
   mark() { M=$(wc -c < "$LOG"); }
   browse() { eval "$JMX_BROWSE" > "src/test/capture/$1/inhouseOrder-browse.txt"; }
   logged() { until tail -c +$((M + 1)) "$LOG" | grep -q 'Error occurred:'; do sleep 1; done; }
   post() { sleep 15; tail -c +$((M + 1)) "$LOG" > "src/test/capture/$1/mule.log"; restart readback "$1"; }
   ajax() {
     S=$1; C=$2; d="src/test/capture/$S"; mkdir -p "$d"; cp "$3" "$d/data.txt"
     next() { echo $(( $(ls "$d" | grep -c '^prior-.*-curl-trace\.txt$') + 1 )); }
     step() { curl -sS -v --http1.1 "$@" --data-urlencode "message=$B" http://localhost:8090/ajax/cometd 2> "$d/prior-$(next)-curl-trace.txt"; }
     B='[{"channel":"/meta/handshake","version":"1.0","supportedConnectionTypes":["long-polling"]}]'; step -o "$F/$S-handshake.json"
     CID=$(jq -er '.[0].clientId' "$F/$S-handshake.json"); R="$C#$CID"
     B=$(jq -nc --arg c "$CID" '[{channel: "/meta/connect", clientId: $c, connectionType: "long-polling"}]'); step -o /dev/null
     B=$(jq -nc --arg c "$CID" --arg r "$R" '[{channel: "/meta/subscribe", clientId: $c, subscription: $r}]'); step -o /dev/null
     B=$(jq -nc --arg ch "$C" --arg c "$CID" --arg r "$R" --rawfile v "$d/data.txt" '[{channel: $ch, clientId: $c, data: {data: $v, replyTo: $r}}]'); step -o /dev/null
     B=$(jq -nc --arg c "$CID" '[{channel: "/meta/connect", clientId: $c, connectionType: "long-polling"}]')
     capture "$S" --data-urlencode "message=$B" http://localhost:8090/ajax/cometd; }
   E4='<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:sam="http://samsung.se.mulesoft.com/"><soapenv:Body><sam:purchase><orderRequest><name>s-1</name><quantity>1</quantity></orderRequest></sam:purchase></soapenv:Body></soapenv:Envelope>'
   printf '%s' '{"orderId":"123","customer":{"firstName":"Nial","lastName":"Darbey","address":"Buenos Aires"},"orderItems":[{"item":{"manufacturer":"Samsung","name":"Galaxy","productId":"i9000","quantity":"30"}},{"item":{"manufacturer":"Philips","name":"P3123","productId":"23","quantity":"10"}}]}' > "$F/A1.txt"
   printf '' > "$F/empty.txt"

   # The empty database of step 3, then the rows that touch no table.
   capture get-populate-created http://localhost:8091/populate
   populate get-populate-already-populated; capture get-populate-already-populated http://localhost:8091/populate
   capture get-orders-method-not-allowed http://localhost:1080/orders
   printf '%s' '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>' | soap post-orders-malformed-envelope http://localhost:1080/orders
   printf '%s' "$E4" | soap post-samsung-orders-purchase http://localhost:9090/samsung/orders
   capture get-samsung-orders-method-not-allowed http://localhost:9090/samsung/orders
   capture post-api-prices -X POST http://localhost:9999/api/prices/AX02
   capture get-api-prices-method-not-allowed http://localhost:9999/api/prices/AX02
   capture wrong-port-no-listener http://localhost:8091/orders
   ajax ajax-orders-manufacturers /orders/manufacturers "$F/empty.txt"

   # Rows whose tables the prior GET /populate creates: each starts from a restart that drops both tables.
   S=post-orders-samsung-item; restart tables; populate $S; mark; soap $S http://localhost:1080/orders < "$O"; post $S
   S=post-orders-samsung-quantity-5; restart tables; populate $S; mark
   sed 's|<quantity>1</quantity>|<quantity>5</quantity>|' "$O" | soap $S http://localhost:1080/orders; post $S
   S=post-orders-inhouse-item; restart tables; populate $S; mark
   sed 's|<manufacturer>Samsung</manufacturer>|<manufacturer>Philips</manufacturer>|' "$O" | soap $S http://localhost:1080/orders; browse $S; post $S
   S=post-orders-mixed-items; restart tables; populate $S; mark
   sed 's|</item>|</item><item><manufacturer>Philips</manufacturer><name>P3123</name><productId>23</productId><quantity>10</quantity></item>|' "$O" | soap $S http://localhost:1080/orders; browse $S; post $S
   S=ajax-orders-request; restart tables; populate $S; mark; ajax $S /orders/request "$F/A1.txt"; browse $S; post $S
   S=ajax-orders-soap; restart tables; populate $S; mark; ajax $S /orders/soap "$O"; post $S

   # Rows with one table present and the other absent.
   S=audit-over-5000-rollback; restart tables orders; mark
   sed 's|<quantity>1</quantity>|<quantity>3</quantity>|' "$O" | soap $S http://localhost:1080/orders; post $S
   S=audit-failure-logged; restart tables orders; mark; soap $S http://localhost:1080/orders < "$O"; logged; post $S
   S=inhouse-redelivery-exhausted; restart tables order_audits; mark
   sed 's|<manufacturer>Samsung</manufacturer>|<manufacturer>Philips</manufacturer>|' "$O" | soap $S http://localhost:1080/orders; logged; browse $S; post $S
   ```

   `post-orders-samsung-rejected` has no command: it is `unreachable`, so it is never captured. `browse` runs for the four rows whose path sends a JMS request to `inhouseOrder`, before `post` stops the runtime, and keeps the queue's messages as `inhouseOrder-browse.txt` without consuming them. `JMX_BROWSE` is the operator's JMX client command that calls `browse()` on the queue MBean `org.apache.activemq:type=Broker,brokerName=localhost,destinationType=Queue,destinationName=inhouseOrder`. When the JMX endpoint cannot be reached, the broker state of these rows cannot be read back, and they stay `UNVERIFIED-uncaptured`.
5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, the content of its `@file`, or the URL-encoded `--data-urlencode` field); `"port"`, the port of the URL the scenario's command targets; and, for every port other than `1080`, `"portProperty"`, the key that binds that listener in `src/main/resources/application.yml` (`ajax.port`, `ports.populate`, `ports.samsung` or `ports.prices`). `state.requests` comes the same way from the `prior-<n>-curl-trace.txt` files, one object per file in the order of `n`, without `port` and with the `portProperty` of its own port (`ports.populate` for the prior `GET /populate`, `ajax.port` for the messages of `S1`).
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-populate-created/response.http GET <<'PY'
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

7. `upstream` is `[]` when the original made no outbound HTTP call, as in every reachable scenario of this project other than the nine that omit it (step 2). For an original whose outbound calls were recorded, this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

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
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state`, `upstream` (step 7) and `response` (step 6 and the effects below), every value as produced.
   - `state` holds the seven shared `properties` of the notes under Scenarios, the same in every fixture of this project, and the prior requests of step 5 as `state.requests`. `get-populate-created`, the first scenario after the start of step 3, and every scenario sent after a `restart` carry `"clearCaches": true`.
   - The effects come unedited from the evidence of step 4:
     - `tables`, from `tables.txt`: every table the run wrote, with all its rows as `{<column>: <value>}` objects in `i` order, `i` left out.
     - `messages` and `destinations`, from `inhouseOrder-browse.txt`: `inhouseOrder` as a queue (`pubSub` `false`) with the messages the browse found.
     - `logs`, from `mule.log`, for `audit-failure-logged` and `inhouse-redelivery-exhausted`: every event under the logger category of the flows' `logger` elements, in logged order, each with its category as `logger`, its level and its message text.
   - `ajax-orders-manufacturers` gets no fixture while the Bayeux replay gap of the notes under Scenarios stands; its evidence stays in `src/test/capture/ajax-orders-manufacturers/`.
   - The original uses no credential and calls no live service, so no live value can reach the evidence. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code: for `get-orders-method-not-allowed`, `get-samsung-orders-method-not-allowed` and `post-orders-malformed-envelope`, that means SC-11 `SoapListenerBoundaryAdapter`; for `wrong-port-no-listener`, SC-10 `PortRoutingFilter`; for `post-samsung-orders-purchase`, the Samsung endpoint and the ported `SamsungServiceImpl`; for `post-api-prices` and `get-api-prices-method-not-allowed`, `controller/ProductPriceController`; for the two populate rows, the `/populate` controller. No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
