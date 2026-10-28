# Service Orchestration and Choice Routing — Spring Boot conversion

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8.0 example [`service-orchestration-and-choice-routing`](../../service-orchestration-and-choice-routing/) (category SOAP/WSDL-based service). The original loads the flow files `fulfillment.xml` and `mule-config.xml` (`config.resources` of `service-orchestration-and-choice-routing/src/main/app/mule-deploy.properties`). It stays unchanged beside this project and is the Tier 2A capture source. The Java package is `com.mulesoft.examples.service_orchestration_and_choice_routing` and the main class is `ServiceOrchestrationAndChoiceRoutingApplication`.

Every listener runs on Undertow in one process. The SOAP services and the Samsung client use Spring WS 4.1.4 over SAAJ and Jakarta JAXB. No JAX-WS or CXF artefact is on the classpath: the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if one arrives, even transitively (AAP 0.3.2).

## Original example

The example simulates the back end of an electronics store. An order arrives over SOAP, either from the store's Ajax web pages or as a raw envelope. Each item is routed by manufacturer:

- A Samsung item goes to Samsung's SOAP web service, mocked inside the same application.
- Every other item goes to an in-house processor behind a JMS queue. It prices the item from an in-house REST price service, caches the price by product ID and stores the item in the database under an XA transaction.

The order is then audited asynchronously, and the caller receives one SOAP summary carrying a purchase receipt per item.

## Endpoints and triggers

The original declares five listener ports: four `http:listener-config` elements and the Ajax connector (`fulfillment.xml:24-27`, `mule-config.xml:3`). All five run in one Undertow process (SC-10), and each binds host `0.0.0.0`, as in the original. Each port answers only for the paths its original listener served.

| Port (key) | Path | Methods | Protocol | Behaviour | Owning class |
|---|---|---|---|---|---|
| `1080` (`server.port`) | `/orders` | `POST` only, in any letter case (`allowedMethods="POST"`, `fulfillment.xml:29`) | SOAP 1.1, document/literal wrapped | `IProcessOrder.processOrder` in namespace `http://orders.se.mulesoft.com/`: request `processOrder` with one unqualified child `order`, response `processOrderResponse` with one unqualified child `summary`, the processed order with a `purchaseReceipt` on each answered item ([How it works](#how-it-works)). Reply `200`, `Content-Type: text/xml;charset=utf-8`, CXF's envelope form. No `?wsdl` is published, because the listener accepts POST only (AAP 0.4.5). | `controller/OrderProcessingEndpoint` → `service/OrderOrchestrationService` |
| `9090` (`ports.samsung`) | `/samsung/orders` | `POST` only, in any letter case (`fulfillment.xml:151`) | SOAP 1.1, document/literal wrapped | The mock Samsung vendor, `SamsungService.purchase` in namespace `http://samsung.se.mulesoft.com/`: request `purchase` with `orderRequest` (`name`, `quantity`), response `purchaseResponse` with `orderResponse`: `id` `1`, `result` `ACCEPTED`, `price` = 2550 × `quantity` in `int` arithmetic, as text. Logs `aaaa` at INFO for each `purchase` that reaches the endpoint. No `?wsdl`. | `controller/SamsungServiceEndpoint` → `service/SamsungServiceImpl` |
| `9999` (`ports.prices`) | `/api/prices/{productId}` | `POST` only, in any letter case (the listener's `allowedMethods`, `fulfillment.xml:145`); `HEAD`, `OPTIONS` and `TRACE` get `405` | HTTP | The price service ported from the Jersey resource `ProductPrice`: `200`, `Content-Type: text/plain` (no charset), body `159` for every product ID. `GET` and every other method get `405` (next table). | `controller/ProductPriceController` → `service/ProductPriceService` |
| `9999` (`ports.prices`) | `/api` | `POST` only, in any letter case (`fulfillment.xml:145`); `HEAD`, `OPTIONS` and `TRACE` get `405` | HTTP | The listener's own path, on which no JAX-RS resource sits. A `POST` reaches Jersey, which answers a request no resource matches with `404 Not Found`, `Content-Length: 0`, no `Content-Type` and no body. Every other method gets `405` (next table). | `controller/ProductPriceController` |
| `8091` (`ports.populate`) | `/populate` | `GET` only, in any letter case (`fulfillment.xml:160`); `HEAD`, `OPTIONS` and `TRACE` get `405` | HTTP | Creates the tables `orders` and `order_audits` in the embedded Derby database `muleEmbeddedDB` and answers `db populated`. When a table already exists, it answers `table already populated`. Either way the reply is `200` with a `Content-Length` and no `Content-Type`. | `controller/PopulateController` → `OrderOrchestrationService.populateDatabase` |
| `8090` (`ajax.port`) | `/orders`, `/orders/`, `/orders/**` | `GET`, `HEAD`; `OPTIONS` answers `200` with `Allow: GET,HEAD,OPTIONS` and no body; other methods get `405` | HTTP | The store pages, served from `src/main/resources/static/orders/`: `index.html`, `demo.html`, `test.html` and their assets, all 123 files copied byte for byte from the original `src/main/app/docroot/`. `/orders` → `302` with the absolute `Location` `http://<host>:8090/orders/`. `/orders/` → `index.html`. Other files are served by name, with the content type derived from the file name. The Mule Ajax JavaScript is served at `/orders/mule-resource/js/**` (SC-12). | `config/AjaxResourceConfig` |
| `8090` (`ajax.port`) | `/ajax/cometd` | `GET`, `POST` | Bayeux 1.0 long polling | The Mule Ajax transport (SC-12). The form parameter `message` holds a JSON array of Bayeux messages. The reply is a plain JSON array as `text/json;charset=UTF-8`, or `text/javascript;charset=UTF-8` with the `jsonp` parameter. The inbound channels are `/orders/request`, `/orders/soap` and `/orders/manufacturers` ([Ajax channels](#ajax-channels)). Replies go to the publication's `replyTo` channel (`ajax.disable-reply-to: false`). | `controller/BayeuxLongPollingController`, `service/AjaxChannelBroker`, `service/AjaxOrderService` |
| JMS (in-VM) | queue `inhouseOrder` on the embedded Artemis broker | request-response | JMS `ObjectMessage` | Each non-Samsung item: an `OrderItem` body with the String property `totalValue`, received in a new JTA transaction (`xa-transaction action="ALWAYS_BEGIN"`, `fulfillment.xml:99-101`). The reply goes back on the same transacted session. | `trigger/InHouseOrderListener` → `service/InHouseOrderService` |
| none (asynchronous) | — | one-way | method call | The audit of each processed order, replacing the one-way VM endpoint `audit` (`fulfillment.xml:44,131`). It inserts into `order_audits` on Boot's task executor, in a JTA transaction of its own. | `service/AuditService` |

### Listener defaults and faults (AAP 0.4.7)

| Case | Response | Enforced by |
|---|---|---|
| A path requested on a port whose original listener did not serve it, for example `GET /orders` on 8091 or `/ajax/cometd` on 1080 | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` | SC-10 `config/PortRoutingFilter` |
| An unknown path inside a served prefix, for example `/api/other` or `/api/` on 9999 (`/api` itself is served, see above) or a missing file under `/orders/` on 8090 | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` | SC-1 `exception/GlobalExceptionHandler` |
| A method other than `POST` on `/orders` or `/samsung/orders`, `GET <path>?wsdl` included (`POST` is matched in any letter case, as Mule 3.8 matched methods, so `post` is served) | `405 Method Not Allowed`, `Content-Type: text/plain` (no charset), body `Method not allowed for endpoint: <path>`, no `Allow` header | SC-11 `config/SoapListenerBoundaryAdapter` |
| A method the listener does not accept on `/api/prices/{productId}`, `/api` or `/populate`, compared ignoring letter case as Mule 3.8 did; a CORS pre-flight gets the same answer as a plain `OPTIONS` request | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: <path>`, no `Allow` header | SC-1 `exception/GlobalExceptionHandler` |
| A method other than `GET`, `HEAD` or `OPTIONS` on `/orders` or `/orders/` of 8090 | `405 Method Not Allowed`, `Allow: GET, HEAD`, `Content-Type: application/json`, Spring Boot's default error body `{"timestamp":"<now>","status":405,"error":"Method Not Allowed","path":"<path>"}`, because `@ControllerAdvice` does not reach view controllers | `config/AjaxResourceConfig`, Boot's error controller |
| A method other than `GET`, `HEAD` or `OPTIONS` on a file under `/orders/` of 8090, `/orders/mule-resource/js/**` included | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: <path>`, no `Allow` header | SC-1 `exception/GlobalExceptionHandler` |
| A body that cannot be read as a SOAP 1.1 envelope (malformed XML, an empty body, a body nested deeper than 100 elements (CXF's default `maxElementDepth`), a root other than `soap:Envelope`) | HTTP `500`, SOAP 1.1 fault, `faultcode` `soap:Client`, `faultstring` the parser's message, for example `XML document structures must start and end within the same entity.` for a truncated envelope | SC-11 `config/SoapListenerBoundaryAdapter` |
| An envelope sent with a `Content-Type` other than `text/xml` | HTTP `500`, `soap:Client`, `faultstring` from SAAJ (`Invalid Content-Type:<type>. Is this an error message instead of a SOAP response?`) | SAAJ's SOAP 1.1 message factory, through SC-11 |
| `processOrder` posted to `/samsung/orders`, or `purchase` posted to `/orders` | HTTP `500`, `soap:Client`, `faultstring` `Message part {<namespace>}<localName> was not recognized.  (Does it exist in service WSDL?)` (two spaces before the parenthesis) | `WebServiceConfig.ListenerPathGuard`, SC-11 `config/SoapFaultConfig` |
| A `mustUnderstand="1"` header block that nothing on the service understands, for example `<x:Sec xmlns:x="urn:x" soapenv:mustUnderstand="1"/>` on `/orders` | HTTP `500`, `soap:MustUnderstand`, `faultstring` `MustUnderstand headers: [{<namespace>}<localName>] are not understood.`, for example `MustUnderstand headers: [{urn:x}Sec] are not understood.`; the order is not processed | SC-11 `config/SoapFaultConfig` (`CxfStyleSoapMessageDispatcher`) |
| An exception escaping the order orchestration or the Samsung service | HTTP `500`, `soap:Server`, `faultstring` the exception message, or CXF's `Fault occurred while processing.` when it has none; a `NullPointerException` the JVM raised counts as having none | SC-11 `config/SoapFaultConfig` |

- Every SOAP response and fault has CXF's envelope form: the `soap:` envelope prefix, no empty `Header`, the JAXB `ns2:` payload prefix, no XML declaration and no whitespace between elements (SC-11 `config/SoapEnvelopeStyleInterceptor`). The response headers carry neither `Accept` nor `SOAPAction`, request headers SAAJ keeps on every message, as CXF sent neither: SC-11 `config/SoapListenerBoundaryAdapter` drops both. Characters outside the Basic Multilingual Plane are written as raw UTF-8, as CXF wrote them, in the SOAP replies and in the Samsung `purchase` request (SC-11 `SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory`), where SAAJ alone writes numeric character references such as `&#128512;` (`DECISIONS.md` section 9).
- A CORS pre-flight to a controller or SOAP path is answered exactly like a plain `OPTIONS` request to the same path, for example with the listener's 405 or the no-listener 404, and gets no `Access-Control-*` or `Vary` header (`config/PreflightDispatchConfig`). The original listeners applied no CORS policy. `PreflightDispatchConfig` covers the annotated request mappings only, so a pre-flight to the 8090 docroot (`/orders`, `/orders/`, `/orders/**`) still gets Spring's `403 Forbidden`, body `Invalid CORS request`, with three `Vary` headers, where a plain `OPTIONS` there gets `200`. What the original's Jetty docroot answered is a Tier 2A verification point.

### How it works

The branches below are those of AAP 0.7.2, described as this code implements them.

#### Order flow (`orderService`, `fulfillment.xml:28-48`)

`OrderOrchestrationService.processOrder` runs the flow's processors in their original order:

1. The session variable `totalValue` starts as the string `"0"` (`set-session-variable value="0"`).
2. The items are processed one after another, in list order. An item whose `manufacturer` equals `Samsung` (exact and case-sensitive, like MEL's `==`) goes to the Samsung branch. Every other item goes to the in-house branch.
3. The receipt returned for an item becomes that item's `purchaseReceipt`. An in-house item whose reply never arrives keeps no receipt.
4. The order is handed to the audit, one-way, and the caller receives the order as `summary`.
5. Any exception: the flow's `catch-exception-strategy` runs `defaultErrorHandler`, which logs `Error occurred: <payload>` at INFO, then the exception is rethrown and answered with a `soap:Server` fault. No audit is dispatched for a failed order.

##### Samsung branch (`samsungOrder`, `fulfillment.xml:49-97`)

`service/SamsungOrderService` runs these steps in order:

1. `OrderRequestMapper` builds the `OrderRequest` from the item's `name` and `quantity`.
2. `client/SamsungServiceClient` sends `purchase` with `WebServiceTemplate` to `orders.samsung.url`, the application's own 9090 listener. The call is sent once, with 10 s connect and read timeouts.
3. A reply whose HTTP status is not 200 is rejected, as the original `message-filter` over `http.status=200` rejected it.
4. `totalValue` += the Samsung `price`.
5. `PurchaseReceiptMapper` maps the reply: `id`, `status` = `result`, `totalPrice` = `price`.

Any failure of these steps (a SOAP fault, a transport error, a timeout, a non-200 status, a reply without `orderResponse`, a mapping error) yields the receipt of the catch branch: status `REJECTED`, `totalPrice` 0 and no `id`. Nothing is retried. The Samsung service is this application's own mock, which accepts every request, so no order input reaches that branch: `SCENARIOS.md` lists it as `post-orders-samsung-rejected` (`unreachable`, with the proof), and `SamsungOrderServiceTest` covers it with a mocked client.

##### In-house branch (`inhouseOrder`, `fulfillment.xml:98-129`)

The item travels as a JMS request-response over queue `inhouseOrder` (`OrderOrchestrationService.orderInHouse`). It is an `ObjectMessage` with the String property `totalValue`, on a non-transacted session with a temporary reply queue. The caller waits `orders.inhouse.reply-timeout-ms` (10000 ms) for the reply. `trigger/InHouseOrderListener` receives each delivery in a new Narayana JTA transaction, which the XA Artemis session and the XA Derby connection both join, and `service/InHouseOrderService` processes it:

1. The price comes from `PriceCacheService`, the Caffeine cache `prices` keyed by `productId`. A miss calls `client/ProductPriceClient`: `GET orders.price.url`, sent once, with 10 s timeouts. Failed lookups are not cached.
2. The item is inserted into `orders (product_id, name, manufacturer, quantity, price)` in the same transaction (`ALWAYS_JOIN`).
3. `totalPrice` = price × quantity.
4. `totalValue` += `totalPrice`.
5. The receipt is built: status `ACCEPTED`, `totalPrice` as a `float`, no `id`.

The reply is sent on the same session, so the row, the consumed request and the reply commit or roll back together. The listener container keeps one broker connection open (`CACHE_CONNECTION`), as the original XA connector did, and creates each session inside its delivery's JTA transaction, where Narayana enlists it.

- Failure: the item is logged at INFO (`logger message="#[payload]"`, the item's `Object.toString()`), the transaction rolls back, and the broker redelivers the request after 1000 ms, ActiveMQ's default delay, which the original XA connector used. The broker itself never dead-letters the request. `rollback-exception-strategy maxRedeliveryAttempts="3"` is applied by the service: on the fifth delivery (`JMSXDeliveryCount - 1` > 3) `defaultErrorHandler` logs `Error occurred: <item>` and the request is consumed without a reply.
- As implemented, every in-house item ends in that failure path. The price listener on 9999 accepts `POST` only, the original's `allowedMethods`, while the flow requests the price with `GET` (`fulfillment.xml:105`), so every lookup is answered with `405`. Four deliveries roll back one second apart, the fifth is consumed, and the caller's 10 s wait ends without a reply: the SOAP response arrives about 10 s after the request, the item carries no `purchaseReceipt`, `totalValue` is unchanged and no `orders` row is written. This mismatch is kept on purpose and is a Tier 2A verification point ([Price service](#price-service-and-the-method-mismatch)). The accepted path above is exercised by `InHouseOrderServiceTest` and `OrderOrchestrationServiceTest` with the price lookup mocked.

##### Audit (`auditService`, `fulfillment.xml:130-143`)

`service/AuditService.audit` is `@Async` and `@Transactional(REQUIRES_NEW)`. It returns to the caller at once and runs on Boot's `applicationTaskExecutor` in its own JTA transaction, so the audit row commits independently of the order. It inserts `insert into order_audits values(default, <orderId>, <totalValue>)`. When the insert fails, the exception's type identifiers are logged at ERROR and the `choice-exception-strategy` branches are evaluated in declaration order:

- `totalValue` is a number greater than `orders.audit.rollback-threshold` (5000): the failure is rethrown and the transaction rolls back. The original's VM redelivery after a rollback has no counterpart.
- Otherwise, including a `totalValue` that is not a number: `defaultErrorHandler` logs `Error occurred: <order>` and the transaction commits.

##### How `totalValue` is typed and combined

`totalValue` is a `String` throughout, as the original session variable was. The original set it to the string `"0"`, and MEL's `+` on a string concatenates. The conversion reproduces that:

- The Samsung branch appends the reply's `price` text: `"0"` + `"2550"` → `"02550"`. Quantity 3 gives `"07650"`, quantity 5 gives `"012750"`, and two Samsung items of quantity 1 give `"025502550"`.
- The in-house branch appends `totalPrice` in plain notation, for example `"0"` + `"318"` → `"0318"`. The value travels with the JMS request and reply as the String property `totalValue`.
- The audit binds the string to the `integer` column `total_value`, and Derby converts it, so `"02550"` is stored as `2550`. The rollback condition `#[sessionVars.totalValue] > 5000` reads it as a decimal number.

The stored audit value and the branch the `> 5000` condition selects are Tier 2A verification points: capture of the original settles both.

#### Price service and the method mismatch

The original `priceService` flow is an `http:listener` with `path="api"` and `allowedMethods="POST"` in front of the Jersey resource `ProductPrice`, whose only method is `@GET @Path("/prices/{productId}")` producing `text/plain` `159`. The in-house flow calls it with `GET`. Two original declarations therefore conflict:

- the listener restricts methods to `POST`, while the resource and its only caller use `GET`;
- the listener path is `api` without a wildcard, so Mule 3.8's path matching may have answered every `/api/prices/...` request with the no-listener 404 instead.

The conversion keeps the listener's restriction and the caller's `GET`:

- `POST /api/prices/<id>`, in any letter case (`post` included, as Mule 3.8 compared methods ignoring case) → `200`, `Content-Type: text/plain`, `159`;
- `GET /api/prices/<id>` (and every other method) → `405`, `Content-Type: text/plain`, `Method not allowed for endpoint: /api/prices/<id>`;
- `POST /api`, in any letter case → `404 Not Found`, `Content-Length: 0`, no `Content-Type`, no body: the request passes the listener and reaches Jersey, where no resource serves the root;
- any other method on `/api` → `405`, `Content-Type: text/plain`, `Method not allowed for endpoint: /api`;
- another path under `/api` → SC-1's no-listener 404.

What the original answered to each request is a Tier 2A verification point (`post-api-prices`, `get-api-prices-method-not-allowed`). Switching the client to `POST` would change the behaviour being reproduced.

#### Ajax channels

The copied pages call the flows with `mule.rpc(channel, data, callback)` from `/orders/mule-resource/js/_mule.js`. `AjaxOrderService` registers one handler per inbound channel on `AjaxChannelBroker`, which publishes each handler's result to the caller's `replyTo` channel `<channel>#<clientId>`.

| Channel | Original flow | Handler steps | Reply |
|---|---|---|---|
| `/orders/request` | `orderRequest` (`mule-config.xml:5-37`) | `AjaxOrderXmlMapper` (DataWeave "JSON to XML": `order` with `orderId`, `customer` and `orderItems: payload.orderItems.item`), `XmlToSoapEnvelope.xsl`, `POST` to `orders.soap.url` (timeout `orders.soap.request-timeout-ms`, 10000000 ms), `SoapEnvelopeToBodyChild.xslt`, `OrderSummaryJsonMapper` (DataWeave "XML to JSON": `orderId`, `customer`, `orderItems`) | the summary JSON, written in DataWeave 1.0's two-space form |
| `/orders/soap` | `orderProxy` (`mule-config.xml:38-42`) | the published envelope posted unchanged to `orders.soap.url` (timeout `orders.soap.proxy-timeout-ms`, 10000 ms) | the service's reply text |
| `/orders/manufacturers` | `manufacturers` (`mule-config.xml:43-46`) | none | `["Samsung","Philips","Sony"]` |

- As implemented, the `/orders/request` reply carries `"orderItems": null`. The DataWeave-faithful order XML writes one `<orderItems>` element per item, without the `<item>` wrapper that the SOAP `Order` binding reads, so the 1080 service receives an order without items and only audits it. The mapper keeps the original script's output on purpose. Which shape the original produced is a Tier 2A verification point.
- No flow declares an exception strategy. A failing step, a SOAP fault answered by the 1080 service included, ends the call: the broker logs the exception's type and publishes no reply. A handler that has not returned within `ajax.response-timeout-ms` (10000 ms) is cancelled, and no reply is published. What the original replied in these cases is a Tier 2A verification point.
- Bayeux protocol, as `BayeuxLongPollingController` speaks it:
  - Meta channels:
    - `/meta/handshake` answers a `clientId` with `supportedConnectionTypes` `["long-polling","callback-polling"]` and the advice `reconnect: retry`, `interval: 0`, `timeout: 250000`;
    - the first `/meta/connect` after a handshake is answered at once, and every later one is held until a delivery is queued or `ajax.connect-timeout-ms` (250000 ms) passes;
    - `/meta/subscribe`, `/meta/unsubscribe` and `/meta/disconnect` act on the broker.
  - Errors:
    - a message from an unknown client is answered in-band with `402::Unknown client` and the advice to handshake again;
    - a request whose `message` is missing, not JSON, or followed by further input after its single JSON value gets HTTP `400` with the body `[]`.
  - Clients:
    - a client that stops connecting is removed after `ajax.max-interval-ms` (30000 ms);
    - a client holds at most 500 deliveries between two connects, and the delivery that would exceed that removes the client, as one that stopped connecting;
    - at most 16 handlers run at once, and at most 500 publications wait for one.
- The Content-Type `text/json;charset=UTF-8` and the HTTP `400` framing are Tier 2A verification points, like the Jetty MIME table, redirect and method handling behind the docroot.

#### Example: a raw SOAP order

The original README's raw-SOAP example (order 12, John Doe, one Samsung item `s-1` / `AX02`, quantity 5), sent to the running application:

```sh
curl -X POST -H 'Content-Type: text/xml; charset=UTF-8' --data-binary @- http://localhost:1080/orders <<'EOF'
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ord="http://orders.se.mulesoft.com/">
   <soapenv:Header />
   <soapenv:Body>
      <ord:processOrder>
         <order>
            <orderId>12</orderId>
            <customer>
               <address>Main Street 123</address>
               <firstName>John</firstName>
               <lastName>Doe</lastName>
            </customer>
            <orderItems>
               <item>
                  <manufacturer>Samsung</manufacturer>
                  <name>s-1</name>
                  <productId>AX02</productId>
                  <quantity>5</quantity>
               </item>
            </orderItems>
         </order>
      </ord:processOrder>
   </soapenv:Body>
</soapenv:Envelope>
EOF
```

The reply is a single line, here wrapped for reading, with `totalPrice` 12750.0 (2550 × 5) as in the original README:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
<ns2:processOrderResponse xmlns:ns2="http://orders.se.mulesoft.com/"><summary><orderId>12</orderId>
<customer><address>Main Street 123</address><firstName>John</firstName><lastName>Doe</lastName></customer>
<orderItems><item><manufacturer>Samsung</manufacturer><name>s-1</name><productId>AX02</productId>
<purchaseReceipt><id>1</id><status>ACCEPTED</status><totalPrice>12750.0</totalPrice></purchaseReceipt>
<quantity>5</quantity></item></orderItems></summary></ns2:processOrderResponse></soap:Body></soap:Envelope>
```

The ported test posts the committed `src/test/resources/message.xml` (the same order with quantity 1) and requires a response body equal to `src/test/resources/reply.xml`. Those are these exact 591 bytes, on one line with no trailing line feed:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:processOrderResponse xmlns:ns2="http://orders.se.mulesoft.com/"><summary><orderId>12</orderId><customer><address>Main Street 123</address><firstName>John</firstName><lastName>Doe</lastName></customer><orderItems><item><manufacturer>Samsung</manufacturer><name>s-1</name><productId>AX02</productId><purchaseReceipt><id>1</id><status>ACCEPTED</status><totalPrice>2550.0</totalPrice></purchaseReceipt><quantity>1</quantity></item></orderItems></summary></ns2:processOrderResponse></soap:Body></soap:Envelope>
```

The full exchange, with the `Date` value varying:

```console
$ curl -i -X POST -H 'Content-Type: text/xml; charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:1080/orders
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/xml;charset=utf-8
Content-Length: 591
Date: <current date>

<the 591 bytes of reply.xml>

$ curl -i http://localhost:8091/populate
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 12
Date: <current date>

db populated

$ curl -i http://localhost:1080/orders
HTTP/1.1 405 Method Not Allowed
Connection: keep-alive
Content-Type: text/plain
Content-Length: 40
Date: <current date>

Method not allowed for endpoint: /orders
```


## Configuration keys

Every key of `src/main/resources/application.yml`. The original reads only one placeholder, `${app.home}`, and its `mule-app.properties` holds comment lines only. Every value is therefore a fixed default taken from the flows' literals (AAP 0.6.6). The original holds no secret, host credential or tenant value, so the configuration has no TODO placeholder, and the project has no `application-test.yml` and no `application-it.yml` (AAP 0.4.6).

The Type column gives the Java type each value binds to, taken from the code or the Spring Boot property that reads it.

| Key | Type | Default | Source in the original | Purpose |
|---|---|---|---|---|
| `server.port` | `Integer` | `1080` | `HTTP_Listener_Configuration3`, `fulfillment.xml:27` | the main listener, which serves only SOAP `/orders` |
| `server.address` | `InetAddress` | `0.0.0.0` | `host="0.0.0.0"`, `fulfillment.xml:24-27` | bind host of the main listener |
| `ajax.port` | `int` | `8090` | `ajax:connector serverUrl="http://0.0.0.0:8090/orders"`, `mule-config.xml:3` | the Ajax listener: docroot, Mule Ajax JavaScript and `/ajax/cometd` |
| `ports.populate` | `int` | `8091` | `HTTP_Listener_Configuration`, `fulfillment.xml:24` | the `/populate` listener |
| `ports.samsung` | `int` | `9090` | `HTTP_Listener_Configuration1`, `fulfillment.xml:25` | the Samsung listener; also the port of `orders.samsung.url` |
| `ports.prices` | `int` | `9999` | `HTTP_Listener_Configuration2`, `fulfillment.xml:26` | the price listener; also the port of `orders.price.url` |
| `listeners.host` | `String` | `0.0.0.0` | `host="0.0.0.0"` | bind host of the four additional listeners (SC-10) |
| `listeners.main.routes[0].servlet` | `String` | `messageDispatcherServlet` | `http:listener path="orders"`, `fulfillment.xml:29` | the servlet behind the one route of `listeners.main.routes`, a `List<ListenerPorts.Route>` |
| `listeners.main.routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `["/orders"]` | `http:listener path="orders"`, `fulfillment.xml:29` | the only path the 1080 listener serves |
| `listeners.additional[i].name` | `String` | `[0]` `ajax`, `[1]` `populate`, `[2]` `samsung`, `[3]` `prices` | none: labels for the Ajax connector (`mule-config.xml:3`) and the listener configurations of `fulfillment.xml:24-26` | label of each entry of `listeners.additional`, a `List<ListenerPorts.Additional>` of the extra Undertow listeners (SC-10), in log lines and start-up errors |
| `listeners.additional[i].port` | `int` | `[0]` `${ajax.port}`, `[1]` `${ports.populate}`, `[2]` `${ports.samsung}`, `[3]` `${ports.prices}` | the Ajax connector's `serverUrl` port and the listener ports of `fulfillment.xml:24-26` | the port each extra listener binds, read from its port key |
| `listeners.additional[i].routes[0].servlet` | `String` | `[0]` `dispatcherServlet`, `[1]` `dispatcherServlet`, `[2]` `messageDispatcherServlet`, `[3]` `dispatcherServlet` | the listeners of `fulfillment.xml:145,151,160` and the Ajax connector | the servlet behind each extra listener's one route |
| `listeners.additional[i].routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `[0]` `["/orders", "/orders/**", "/ajax/**"]`, `[1]` `["/populate"]`, `[2]` `["/samsung/orders"]`, `[3]` `["/api/**"]` | the listeners of `fulfillment.xml:145,151,160` and the Ajax connector | the path-to-port routing (SC-10); a path outside a port's routes gets the no-listener 404 |
| `ajax.server-path` | `String` | `/orders` | path of the connector's `serverUrl` | docroot at `/orders/`, JavaScript at `/orders/mule-resource/js/**`, channels `/orders/request`, `/orders/soap`, `/orders/manufacturers` |
| `ajax.disable-reply-to` | `Boolean` | `false` | the connector sets no `disableReplyTo` | handler results are published on the caller's `replyTo` channel |
| `ajax.cache-messages-channels` | `List<String>` (channel names) | `[]` | no outbound channel sets `cacheMessages` | outbound channels whose messages are held for the first subscriber |
| `ajax.response-timeout-ms` | `Long` (milliseconds) | `10000` | `responseTimeout="10000"`, `mule-config.xml:6,39,44` | a handler that has not returned by then is cancelled and no reply is published |
| `ajax.connect-timeout-ms` | `long` (milliseconds) | `250000` | Mule 3.8.0 `AjaxConnector` default server poll timeout | how long a `/meta/connect` is held; the `advice.timeout` of every handshake and connect |
| `ajax.max-interval-ms` | `Long` (milliseconds) | `30000` | Mule 3.8.0 `AjaxConnector` default `maxInterval` | a client not heard from for this long is removed |
| `orders.samsung.url` | `String` | `http://localhost:${ports.samsung}/samsung/orders` | `http:request method="POST" path="samsung/orders" port="9090"`, `fulfillment.xml:96` | target of `client/SamsungServiceClient` |
| `orders.samsung.timeout-ms` | `long` (milliseconds) | `10000` | Mule's default response timeout | connect and read timeout of the Samsung call; a value below 1 or above 2147483647 stops start-up |
| `orders.price.url` | `String` (a URI template) | `http://localhost:${ports.prices}/api/prices/{productId}` | `http:request method="GET" path="api/prices/#[payload.productId]" port="9999"`, `fulfillment.xml:105` | target of `client/ProductPriceClient`; `{productId}` is a URI-template variable |
| `orders.price.timeout-ms` | `int` (milliseconds) | `10000` | Mule's default response timeout | connect and read timeout of the price lookup; a value below 1 stops start-up |
| `orders.soap.url` | `String` | `http://localhost:1080/orders` | `http:request method="POST" path="orders" port="1080"`, `mule-config.xml:21,40` | target of `client/OrderProcessingSoapClient`; a literal port, so override it together with `server.port` |
| `orders.soap.request-timeout-ms` | `int` (milliseconds) | `10000000` | `responseTimeout="10000000"`, `mule-config.xml:21` | timeout of the `/orders/request` call; a value below 1 stops start-up |
| `orders.soap.proxy-timeout-ms` | `int` (milliseconds) | `10000` | no `responseTimeout` on `mule-config.xml:40`, so Mule's default | timeout of the `/orders/soap` call; a value below 1 stops start-up |
| `orders.inhouse.queue` | `String` | `inhouseOrder` | `queue="inhouseOrder"`, `fulfillment.xml:39,99` | the in-house request queue |
| `orders.inhouse.reply-timeout-ms` | `long` (milliseconds) | `10000` | Mule's default response timeout of the request-response endpoint | how long the order flow waits for the in-house reply; a value below 1 stops start-up |
| `orders.inhouse.max-redelivery-attempts` | `int` | `3` | `rollback-exception-strategy maxRedeliveryAttempts="3"`, `fulfillment.xml:123` | redeliveries before `defaultErrorHandler` consumes the request |
| `orders.audit.rollback-threshold` | `BigDecimal` | `5000` | `when="#[sessionVars.totalValue] > 5000"`, `fulfillment.xml:138` | above it a failed audit insert rolls back instead of being logged |
| `spring.artemis.mode` | `ArtemisMode` | `embedded` | `jms:activemq-xa-connector brokerURL="vm://localhost"`, `fulfillment.xml:4` | the in-VM broker |
| `spring.artemis.embedded.queues` | `String[]` (queue names) | `inhouseOrder` | `fulfillment.xml:39,99` | queue created at start-up |
| `spring.artemis.embedded.persistent` | `boolean` | `false` | the in-VM broker | no journal on disk |
| `spring.datasource.xa.data-source-class-name` | `String` (a fully qualified class name) | `org.apache.derby.jdbc.EmbeddedXADataSource` | enhydra `StandardXADataSource` over `EmbeddedDriver`, `fulfillment.xml:7-10` | the XA data source that Narayana enlists |
| `spring.datasource.xa.properties.databaseName` | `String` (an entry of the `Map<String, String>` `spring.datasource.xa.properties`) | `muleEmbeddedDB` | `jdbc:derby:muleEmbeddedDB;create=true`, `fulfillment.xml:9` | database name under the Derby system home |
| `spring.datasource.xa.properties.createDatabase` | `String` (an entry of the same map) | `create` | `;create=true` | the database is created on first use |
| `spring.datasource.xa.properties.user` | `String` (an entry of the same map) | `APP` | the original connected without a user | keeps the tables in Derby's default schema `APP`; Derby runs without authentication, so this is a schema name, not a credential |
| `derby.system-home` | `String` (a directory path) | `target/derby` | the original created `muleEmbeddedDB` in the runtime's working directory | set as `derby.system.home` before Derby boots, unless that system property is already set |
| `narayana.log-dir` | `String` (a directory path) | `target/narayana` | JBossTS object store | Narayana's object store |
| `narayana.default-timeout` | `int` (seconds) | `600` (seconds) | `com.arjuna.ats.arjuna.coordinator.defaultTimeout`, `fulfillment.xml:13` | JTA transaction timeout; `txReaperTimeout` 1000000 (`fulfillment.xml:14`) has no Narayana starter key |
| `spring.cache.type` | `CacheType` | `caffeine` | `ee:object-store-caching-strategy` over an `in-memory-store`, `fulfillment.xml:16-18` | the price cache |
| `spring.cache.cache-names` | `List<String>` (cache names) | `prices` | `Caching_Strategy` | one cache, with no expiry or size bound, as the in-memory store configured none; entries live until the application stops |
| `spring.webservices.servlet.init` | `Map<String, String>` (servlet init-parameter name to value) | `"[dispatchOptionsRequest]": "true"`, `"[dispatchTraceRequest]": "true"` | the SOAP listeners' `allowedMethods="POST"` | `OPTIONS` and `TRACE` on `/orders` and `/samsung/orders` reach the listener boundary's 405 |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | every listener declares `allowedMethods` | `TRACE` reaches the MVC mappings and gets the 405 instead of the servlet's echo |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | no default static mapping; unknown paths reach SC-1's 404, and `AjaxResourceConfig` registers the docroot itself |
| `spring.application.name` | `String` | `service-orchestration-and-choice-routing-java` | none | application name in log lines |
| `logging.level.org.springframework.ws.server.MessageTracing` | `LogLevel` | `INFO` | none: the flows' loggers never write the customer | its `.received`/`.sent` `TRACE` write every `processOrder` envelope and its `OrderSummary` reply, the customer included |
| `logging.level.org.springframework.ws.client.MessageTracing` | `LogLevel` | `INFO` | none | the same for every envelope `client/SamsungServiceClient` sends and receives |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | its `DEBUG` and `TRACE` write the request line with its query: a `GET` to `/ajax/cometd` carries the published order in its `message` parameter. `WARN`, not `INFO`: its only `INFO` lines are the servlet start-up pair, which a root `WARN` must silence |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | its `TRACE` writes handler arguments: the Bayeux message holding the published order |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter` | `LogLevel` | `INFO` | none | its `TRACE` writes the async result a held `/meta/connect` resumes with: the order summary reply |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | its `TRACE` writes the response body: the same Bayeux reply |
| `logging.level.org.apache.activemq.audit` | `LogLevel` | `WARN` | none: the embedded broker's own loggers, for which `fulfillment.xml` sets no level | its `.base`, `.resource` and `.message` `INFO` lines record every connection and session the in-house listener opens and closes, and each in-house message sent and consumed, its `totalValue` property included |

- The seven `logging.level` keys keep the order's customer (names and address) out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`, and silence the embedded broker's `INFO` audit stream. The original flows' loggers still log at INFO, `Error occurred: <payload>` among them. The nested `UnderRootTrace` run of `controller/OrderProcessingEndpointTest` asserts this.
- `spring.webservices.path` keeps Boot's default `/services`. `messageDispatcherServlet` is reached only through the SC-10 named-servlet forwards of `/orders` and `/samsung/orders`, so a request to `/services/**` matches no route and gets the no-listener 404.
- Set in code rather than in keys:
  - `config/XaTransactionConfig` gives queue `inhouseOrder` a redelivery delay of 1000 ms and unlimited delivery attempts, and gives its listener container one consumer in JTA transactions;
  - `AjaxChannelBroker` bounds its queues (500 deliveries per client, 500 held messages per cached channel, 16 running handlers, 500 waiting publications);
  - `BayeuxLongPollingController` holds at most 512 connects at once.
- Override a port on the command line or through the environment (relaxed binding maps `PORTS_SAMSUNG` to `ports.samsung`). The Samsung and price URLs follow their port keys. `orders.soap.url` names port 1080 literally, so set it as well when `server.port` changes:

  ```sh
  java -jar target/service-orchestration-and-choice-routing-java-1.0.0-SNAPSHOT.jar \
    --server.port=11080 --orders.soap.url=http://localhost:11080/orders \
    --ajax.port=18090 --ports.populate=18091 --ports.samsung=19090 --ports.prices=19999
  ```

### Original-to-new key map

| Original | Converted | Reason |
|---|---|---|
| `${app.home}` in `ajax:connector resourceBase="${app.home}/docroot"` (`mule-config.xml:3`) | dropped | the docroot is packaged and served from the classpath `static/orders/` (`config/AjaxResourceConfig`), so no application home directory is needed |

The original has no other placeholder: every port, path, queue, URL and timeout is a literal of the flows, and each became the default of the key named above.

## Mule-to-Java mapping

| Mule construct | Java construct (`com.mulesoft.examples.service_orchestration_and_choice_routing`) |
|---|---|
| `http:listener-config` × 4 and `ajax:connector` (ports 1080, 8090, 8091, 9090, 9999) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with SC-10 `config/ListenerPorts` (extra listeners) and `config/PortRoutingFilter` (per-port routes, the no-listener 404) |
| `cxf:jaxws-service serviceClass="com.mulesoft.se.orders.IProcessOrder"` (`fulfillment.xml:30`) | Spring WS `@Endpoint` `controller/OrderProcessingEndpoint` with `@PayloadRoot`, `Jaxb2Marshaller` (`ordersMarshaller`) over `model/soap` (`ProcessOrder`, `ProcessOrderResponse`) and the ported `model/orders` |
| `cxf:jaxws-service serviceClass="com.mulesoft.se.samsung.SamsungService"` + component `SamsungServiceImpl` (`fulfillment.xml:153-154`) | `controller/SamsungServiceEndpoint` with `Jaxb2Marshaller` (`samsungMarshaller`) over the ported `model/samsung`, and the ported `service/SamsungServiceImpl` |
| `cxf:configuration`, CXF envelope style, faults and listener boundary | `config/WebServiceConfig` (a `WsConfigurer` without `@EnableWs`), SC-11 `config/SoapEnvelopeStyleInterceptor`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter` |
| `cxf:jaxws-client clientClass="SamsungServiceService"` + `http:request` to 9090 (`fulfillment.xml:94-97`) | `client/SamsungServiceClient`: `WebServiceTemplate` over a single-attempt sender |
| `vm:outbound-endpoint path="samsungOrder"`, request-response (`fulfillment.xml:36,50`) | direct call of `service/SamsungOrderService.order` |
| `vm:outbound-endpoint path="audit"`, one-way (`fulfillment.xml:44,131`) | `@Async` `service/AuditService.audit` (`@EnableAsync` on the application class) |
| `jms:activemq-xa-connector brokerURL="vm://localhost"` and the `inhouseOrder` endpoints (`fulfillment.xml:4,39,99`) | embedded Artemis with its XA connection factory; `trigger/InHouseOrderListener` (`@JmsListener`) and the `inHouseRequestJmsTemplate` of `config/XaTransactionConfig` |
| `jbossts:transaction-manager` (`fulfillment.xml:12-15`) and `xa-transaction action="ALWAYS_BEGIN"` | Narayana JTA through `dev.snowdrop:narayana-spring-boot-starter` 3.2.0; the JTA-transacted listener container of `config/XaTransactionConfig`; `@Transactional(REQUIRES_NEW)` on the audit |
| enhydra `StandardXADataSource` over Derby (`fulfillment.xml:7-10`) and `db:generic-config` | Derby `EmbeddedXADataSource` through Boot's XA data source wrapper, enlisted by Narayana; `JdbcTemplate` of `config/XaTransactionConfig` |
| `db:insert` with `db:parameterized-query` (`fulfillment.xml:110-112,134-136`) | `JdbcTemplate.update` with bind parameters in `InHouseOrderService` and `AuditService` |
| `db:execute-ddl` × 2 (`fulfillment.xml:161-167`) | `OrderOrchestrationService.populateDatabase`, each statement auto-committed |
| `ee:cache` with `ee:object-store-caching-strategy` keyed by `#[payload.productId]` (`fulfillment.xml:16-18,104-107`) | `service/PriceCacheService` (`@Cacheable("prices")`, key `productId`), Caffeine through `spring.cache.*`, `@EnableCaching` |
| `jersey:resources` over `com.mulesoft.se.orders.ProductPrice` (`fulfillment.xml:146-148`) | Spring MVC `controller/ProductPriceController` over `service/ProductPriceService` |
| `foreach`, `choice`, `enricher`, `set-session-variable`, `set-variable` | statements of `OrderOrchestrationService.processOrder`, `SamsungOrderService.order` and `InHouseOrderService.process`, in the original order |
| `message-filter` over `http.status=200` with `throwOnUnaccepted="true"` (`fulfillment.xml:62-64`) | the status check in `SamsungOrderService.order` |
| `catch-exception-strategy`, `rollback-exception-strategy`, `choice-exception-strategy`, sub-flow `defaultErrorHandler` | `try`/`catch` in `OrderOrchestrationService`, `SamsungOrderService`, `InHouseOrderService` and `AuditService`; `OrderOrchestrationService.logError` |
| Groovy `scripting:transformer`s building the `REJECTED` and `ACCEPTED` receipts (`fulfillment.xml:80-91,116-122`) | Java in `SamsungOrderService` and `InHouseOrderService` |
| `%dw 1.0` scripts (AAP 0.5.8): "OrderItem to OrderRequest" (`fulfillment.xml:53`), "OrderResponse to PurchaseReceipt" (`:68`), "JSON to XML" (`mule-config.xml:9`), "XML to JSON" (`:27`) | `mapper/OrderRequestMapper`, `mapper/PurchaseReceiptMapper`, `mapper/AjaxOrderXmlMapper` (over SC-4 `DwXmlWriter`), `mapper/OrderSummaryJsonMapper` (over SC-4 `DwXmlToJson` and `DwJsonWriter`) |
| `mulexml:xslt-transformer` with `XmlToSoapEnvelope.xsl` and `SoapEnvelopeToBodyChild.xslt` (`mule-config.xml:19,23`) | `mapper/SoapEnvelopeTransformer`: the JDK's `javax.xml.transform` over the copied stylesheets |
| `http:request` to 1080 in `orderRequest` and `orderProxy` (`mule-config.xml:21,40`) | `client/OrderProcessingSoapClient` (`RestClient`) |
| `http:request` to 9999 (`fulfillment.xml:105`) | `client/ProductPriceClient` (`RestClient` over httpclient5 with retries disabled) |
| `ajax:connector` (Jetty cometd) and `ajax:inbound-endpoint`s (`mule-config.xml:3-46`) | SC-12 `controller/BayeuxLongPollingController`, `service/AjaxChannelBroker`, `config/AjaxResourceConfig`, the Mule 3.8.0 JavaScript under `mule-resource/js/`; the three flows in `service/AjaxOrderService` |
| `expression-transformer` of `manufacturers` (`mule-config.xml:45`) | the constant `AjaxOrderService.MANUFACTURERS` |
| `logger`s (`fulfillment.xml:124,152,157`) | SLF4J at INFO with the original messages (`aaaa`, `#[payload]`, `Error occurred: #[payload]`) |
| `javax.jws` interfaces `IProcessOrder` and `SamsungService` | plain interfaces `model/orders/IProcessOrder` and `model/samsung/SamsungService`; the wire shape is carried by the JAXB wrappers (AAP 0.3.2) |
| `mule-deploy.properties` (`config.resources=fulfillment.xml,mule-config.xml`) | `ServiceOrchestrationAndChoiceRoutingApplication` (`@SpringBootApplication`, `@EnableAsync`, `@EnableCaching`) |
| `mule-app.properties` (no keys) | `src/main/resources/application.yml` |
| `ServiceOrchestrationAndChoiceRoutingIT` (`FunctionalTestCase`, `MuleClient`) | `ServiceOrchestrationAndChoiceRoutingTest` (`@SpringBootTest` on free ports, ST-1 `support/HttpTestClient`) |

No JAX-WS or CXF type is used anywhere. `spring-boot-starter-web-services` declares `jakarta.xml.ws:jakarta.xml.ws-api`, which the pom excludes. `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11 while Spring Framework stays at 6.1.15 (AAP 0.6.2, `DECISIONS.md` section 2). The endpoint and client tests are the gate for that combination.

## Behaviours stubbed or externalized

- Not ported, under the reachability rule of AAP 0.4.5 (`DECISIONS.md` section 5):
  - `com.mulesoft.se.samsung.SamsungServiceService`, a JAX-WS client stub, replaced by `client/SamsungServiceClient`;
  - `com.mulesoft.se.samsung.OrderItemToOrderRequest`, a transformer no flow references; the `samsungOrder` request follows the active DataWeave script (`fulfillment.xml:51-59`).
- Reference documents: `src/main/resources/orders.wsdl` and `samsung.wsdl` are copied unchanged, and no code reads or publishes them. `orders.wsdl` describes an unwrapped `item`, a `receipt` element and status values `OK`/`FAIL`. The active JAXB bindings and the committed reply produce `orderItems/item`, `purchaseReceipt` and `ACCEPTED`/`REJECTED`, and they govern (AAP 0.3.2, `DECISIONS.md` section 7). `src/test/resources/test-soapui-project.xml` is carried as the original's test resource.
- `com.mulesoft.se.samsung.SamsungProduct` is ported as a plain class without its JAX-RS and JAX-WS annotations, so it adds no endpoint.
- Externalized or stubbed: nothing. The original calls only its own listeners and holds no credential. The Samsung "vendor" is the original's own mock service, carried in the same process.
- Not carried (AAP 0.2.2, 0.4.5): `catalog/**`, `mule-project.xml`, `src/test/resources/log4j2-test.xml`, `src/test/resources/sample_data/**`, and the Studio metadata inputs `src/test/resources/order.xsd`, `orderSummary.xsd`, `sample-request.json`, `sample-response.json` and `sample.xml`, which only `catalog/**` references.

### Tier 2A verification points

Each of these is a plan default or a kept declaration that the source alone does not settle. A captured fixture of the original governs it, and the Java code is changed to match (AAP 0.4.7, 0.7.2):

- The bytes of the listener defaults: the 405 bodies and their Content-Type (`text/plain` without a charset, both at the SOAP boundary and from SC-1), the no-listener 404, and the `soap:Client` and `soap:Server` fault strings.
- The price service: `GET` against the POST-only listener, `POST` against the `@GET` resource, the listener path `api` without a wildcard, the reply's `text/plain` without a charset, and the empty `404` that `POST /api` gets from Jersey, without a `Content-Type` or a body.
- The in-house outcome that follows from the price lookup failing on every delivery.
- `totalValue`: its string concatenation, the value stored in `order_audits.total_value`, and the branch `#[sessionVars.totalValue] > 5000` selects.
- The `/orders/request` reply shape that the DataWeave semantics produce (`"orderItems": null`), and the replies of the Ajax flows when a step fails or times out.
- Response Content-Types: none on `/populate`, `text/plain` on the price, `text/xml;charset=utf-8` on SOAP replies, and `text/json;charset=UTF-8` on `/ajax/cometd`.
- The SOAP response headers, which carry neither the `Accept` nor the `SOAPAction` request header SAAJ keeps on every message, the raw UTF-8 of characters outside the Basic Multilingual Plane, and the `soap:Client` fault for an envelope sent with a Content-Type other than `text/xml`.
- The 405 answered to `HEAD /populate`, and the Jetty MIME table, redirect, method handling and CORS pre-flight answer behind the docroot.
- The `aaaa` log line: the original logged it before CXF parsed the body, while here it is logged only for a `purchase` that reaches the endpoint.
- The audit rollback: the original's VM redelivery after a rolled-back audit is not reproduced.


## Build, run and test

Run from `java-conversions/service-orchestration-and-choice-routing-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listeners on 0.0.0.0:1080, 8090, 8091, 9090 and 9999
java -jar target/service-orchestration-and-choice-routing-java-1.0.0-SNAPSHOT.jar
```

After start-up:

1. Open `http://localhost:8091/populate` once, which answers `db populated`, to create the tables. A second call answers `table already populated`.
2. Open `http://localhost:8090/orders/`, the store pages, as the original README describes: enter an order, add it, submit it, and read the **Confirmation** tab, or paste a raw envelope into the **Soap** tab.
3. Or post a SOAP order to `http://localhost:1080/orders`, as in [Example: a raw SOAP order](#example-a-raw-soap-order).

Notes:

- Running the application needs ports 1080, 8090, 8091, 9090 and 9999 free. Override them as shown under [Configuration keys](#configuration-keys).
- The Derby database `muleEmbeddedDB` is created under `target/derby` and the Narayana object store under `target/narayana`, relative to the working directory, so nothing lands in the project root.
- `mvn test` needs no fixed port. Each Spring test binds the listeners to free ports through `@DynamicPropertySource` and uses its own in-memory Derby database and its own embedded Artemis server. Surefire points `derby.system.home` and `narayana.log-dir` at `target/derby-test` and `target/narayana-test`.
- There is no `mvn verify` live tier: the project is not a connector project, so it has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ServiceOrchestrationAndChoiceRoutingTest` | the ported `ServiceOrchestrationAndChoiceRoutingIT` (`service-orchestration-and-choice-routing/src/test/java/org/mule/examples/ServiceOrchestrationAndChoiceRoutingIT.java:83-105`), the whole application on free ports through ST-1: `POST` `message.xml` to `/orders` → status `200` and a body equal to `reply.xml`, with no captured log line carrying the reply's customer values (the Samsung flow's INFO `aaaa` line is the capture control); `GET /populate` → a non-empty payload containing `db populated` |
| `controller/OrderProcessingEndpointTest` | on the wire: the exact `reply.xml` bytes, `GET /orders` → 405, an unreadable envelope → `soap:Client`, the Samsung operation on the orders listener → `soap:Client`, `/orders` on the populate port → no-listener 404, a failing orchestration → `soap:Server` with the exception message; nested `UnderRootTrace`, unmocked under `logging.level.root=TRACE`: the original Samsung order (answered with `reply.xml`), an in-house order and an order published on the Ajax channel `/orders/request` with a `GET` each answer while the original `Error occurred:` line is still logged, no event of the `org.apache.activemq.audit` loggers is written and no customer name or address reaches the console |
| `controller/SamsungServiceEndpointTest` | on the wire: the `purchaseResponse` shape (namespace, unqualified children, price 2550 × quantity), the INFO `aaaa` line, 405, the `soap:Client` faults, the wrong-path and wrong-port answers |
| `controller/ProductPriceControllerTest` | `POST` in any letter case → `200 text/plain 159` for every product; every other method (`GET`, `HEAD`, `OPTIONS`, `TRACE`, `get` among them) → 405 on the price path and on `/api`; `POST` and `post` on `/api` → empty 404 without `Content-Type`; a CORS pre-flight on either path → the plain `OPTIONS` answer; both paths on the main and populate ports → no-listener 404; no rejected request reaches the price service |
| `controller/PopulateControllerTest` | `db populated` then `table already populated`, with no `Content-Type` and `Content-Length` 12; `POST` → 405; `/populate` on the main port → no-listener 404 |
| `controller/BayeuxLongPollingControllerTest` | SC-12 on standalone MockMvc: handshake, connect (first one immediate, later ones held and released by a delivery or the timeout), subscribe, unsubscribe, disconnect, publish, rpc replies on `replyTo`, `jsonp`, the `400 []` answers, unknown-client advice, U+2028/U+2029 escaping and the held-connect races |
| `service/AjaxChannelBrokerTest` | SC-12: subscriptions, deliveries, `replyTo` replies with `ajax.disable-reply-to` `false` and none with `true` while outbound messages still reach subscribers, cached channels, handler timeouts and limits, idle expiry, log hygiene |
| `service/OrderOrchestrationServiceTest` | every branch of the order flow with mocked collaborators: Samsung versus in-house routing (exact manufacturer match), receipts per item in order, the `ObjectMessage` request with `totalValue`, reply and timeout handling, concatenated totals reaching the audit, the catch branch's logs and rethrow, a later item's failure stopping the foreach (the earlier item's receipt kept, later items and the audit skipped), `populateDatabase`; the reply-timeout bounds of the request-reply template (`config/XaTransactionConfig`) |
| `service/SamsungOrderServiceTest` | the Samsung branch: receipt mapping, the concatenated `totalValue`, and the `REJECTED` receipt for a non-200 status, a missing `orderResponse`, a client failure and a `null` item |
| `service/InHouseOrderServiceTest` | the in-house branch: insert and `ACCEPTED` receipt with `totalPrice`, concatenation onto an existing total, price, insert and number failures rethrown after the INFO log, and the redelivery limit with `defaultErrorHandler` |
| `service/AjaxOrderServiceTest` | the three Ajax flows with the real mappers, SC-4 classes and stylesheets and a mocked broker and 1080 client: the three channel bindings and server-path normalization, `/orders/request` turning the Studio sample `json_1.json` and the `reply.xml` reply into the DataWeave 1.0 summary JSON, non-string and unserializable publications, `/orders/soap` returning the reply text unchanged, the constant manufacturers list, client failures and malformed order JSON |
| `trigger/InHouseOrderListenerTest` | the whole application with the embedded Artemis broker, Narayana and Derby, requests sent as `orderInHouse` sends them: the `ACCEPTED` reply with receipt, `totalValue` and correlation; a failing delivery rolled back and redelivered; the insert of a failed delivery rolled back before the redelivery is accepted; exhausted redelivery logging `Error occurred: <item>`, consumed with no reply and no row; a body that is not an order item, handed to `defaultErrorHandler` once redelivery is exhausted, with no reply; `idleListenerReceivesOnTheOneConnectionItKeepsOpen`: over three idle receives the listener opens no further broker connection |
| `service/AuditServiceTest` | the insert's values, the rollback branch above the threshold (and at the boundary), the catch branch at or below it, the asynchronous route and its failure logging, and a real Derby commit |
| `service/PriceCacheServiceTest`, `service/ProductPriceServiceTest`, `service/SamsungServiceImplTest` | caching by product ID with failures not cached; the constant `159`; `id` `1`, `ACCEPTED`, `2550 × quantity` in `int` arithmetic |
| `client/SamsungServiceClientTest`, `client/ProductPriceClientTest`, `client/OrderProcessingSoapClientTest` | WireMock: request shape and bytes, status handling, timeouts and connection failures each sending exactly one request, empty bodies and charsets, the rejection of a timeout below 1 ms |
| `mapper/OrderRequestMapperTest`, `mapper/PurchaseReceiptMapperTest`, `mapper/AjaxOrderXmlMapperTest`, `mapper/OrderSummaryJsonMapperTest`, `mapper/SoapEnvelopeTransformerTest` | the four DataWeave scripts and the two XSLT steps, including the Studio sample `json_1.json` and the summary of `reply.xml` |
| `mapper/DwJsonWriterTest`, `mapper/DwXmlWriterTest`, `mapper/DwXmlToJsonTest` | SC-4 DataWeave 1.0 writer and reader bytes |
| `config/PortRoutingFilterTest` | SC-10: per-port routing, the byte-exact no-listener 404, forwarding to the named servlet, an empty TLS password named in the start-up failure |
| `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest`, `config/WsdlQueryPublisherTest` | SC-11: CXF envelope bytes, `soap:Server`, `soap:Client` and `soap:MustUnderstand` faults, the boundary 405 and faults; this project registers no WSDL, which the publisher test accepts |
| `config/PreflightDispatchConfigTest`, `config/PreflightDispatchListenerTest` | a CORS pre-flight dispatched as a plain `OPTIONS` request |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, Content-Types and bodies |
| `FixtureParityTest` | ST-2 fixture replay ([Fixture parity](#fixture-parity-st-2)); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed, registered in [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4).

| ID | Files in this project | Reference implementation | Other consumers |
|---|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`; test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` | every project with a Spring MVC controller |
| SC-4 | `mapper/DwJsonWriter`, `mapper/DwXmlWriter`, `mapper/DwXmlToJson`; their tests | `processing-orders-with-dataweave-and-APIkit-java` (`DwJsonWriter`), `web-service-consumer-java` (`DwXmlWriter`, `DwXmlToJson`) | the projects owning DataWeave mappers |
| SC-10 | `config/ListenerPorts`, `config/PortRoutingFilter`; test `config/PortRoutingFilterTest` | `foreach-processing-and-choice-routing-java` | `http-oauth-provider`, `oauth2-authorization-code-using-the-HTTP-connector`, `soap-webservice-security`, `mule-component-bindings` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`; their four tests | `foreach-processing-and-choice-routing-java` | `soap-webservice-security`, `xml-only-soap-webservice`, `legacy-modernization`; `proxying-a-soap-api` (`WsdlQueryPublisher` only) |
| SC-12 | `controller/BayeuxLongPollingController`, `service/AjaxChannelBroker`, `config/AjaxResourceConfig`, `src/main/resources/mule-resource/js/**`; tests `controller/BayeuxLongPollingControllerTest`, `service/AjaxChannelBrokerTest` | **this project** | `websphere-mq` (8086, server path `/services/wmqExample`, `ajax.disable-reply-to: true`) |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` | every project with an HTTP listener |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` | the 51 non-RAML projects |

- SC-11: `config/WebServiceConfig` passes the POST-only paths `/orders` and `/samsung/orders` to `SoapListenerBoundaryAdapter`. It registers no `WsdlQueryPublisher`, because both listeners accept `POST` only and the original therefore published no `?wsdl` (AAP 0.4.5).
- SC-12: the JavaScript under `mule-resource/js/` is the Mule 3.8.0 `transports/ajax/src/main/resources/js/` tree, copied unchanged with its licence headers: `mule.js`, `_mule.js`, `dojo/dojo.js`, `dojo/dojo.js.uncompressed.js`, `dojox/cometd.js`, `dojox/cometd.js.uncompressed.js`, `dojox/cometd/timestamp.js`, `dojox/cometd/timesync.js` and `dojox/uuid/generateRandomUuid.js`.
- This project holds the reference implementation of shared component SC-12, the Mule Ajax transport: `controller/BayeuxLongPollingController`, `service/AjaxChannelBroker`, `config/AjaxResourceConfig` and `src/main/resources/mule-resource/js/**`, with their tests `controller/BayeuxLongPollingControllerTest` and `service/AjaxChannelBrokerTest`. `websphere-mq-java` copies them with only the `package` line changed (the controller and its test also change the package root of their `AjaxChannelBroker` import). A change is made here first and then copied. The register is [`DECISIONS.md`](../../DECISIONS.md) section 4.

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds:

- the scenario rows and the derived envelopes `E1` to `E5`;
- the Ajax order `A1` and the Bayeux sequence `S1`;
- the seven `state.properties` every fixture seeds, which put the five listeners on free ports and give the replay an in-memory Derby database;
- the capture procedure.

Fixtures are captured from the running original only, never written by hand or edited to match the Java output. A mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-orders-samsung-item` | `soap` | `deterministic` | port `1080`: `POST /orders`, body `message.xml` | `UNVERIFIED-uncaptured` |
| `post-orders-samsung-quantity-5` | `soap` | `deterministic` | port `1080`: `POST /orders`, envelope `E1` (quantity 5) | `UNVERIFIED-uncaptured` |
| `post-orders-samsung-rejected` | `soap` | `unreachable` | none (no request invented) | `UNREACHABLE` |
| `post-orders-inhouse-item` | `soap` | `deterministic` | port `1080`: `POST /orders`, envelope `E2` (manufacturer Philips) | `UNVERIFIED-uncaptured` |
| `post-orders-mixed-items` | `soap` | `deterministic` | port `1080`: `POST /orders`, envelope `E3` (Samsung and Philips items) | `UNVERIFIED-uncaptured` |
| `get-orders-method-not-allowed` | `http` | `deterministic` | port `1080`: `GET /orders` | `UNVERIFIED-uncaptured` |
| `post-orders-malformed-envelope` | `soap` | `deterministic` | port `1080`: `POST /orders`, a truncated envelope | `UNVERIFIED-uncaptured` |
| `post-samsung-orders-purchase` | `soap` | `deterministic` | port `9090`: `POST /samsung/orders`, envelope `E4` | `UNVERIFIED-uncaptured` |
| `get-samsung-orders-method-not-allowed` | `http` | `deterministic` | port `9090`: `GET /samsung/orders` | `UNVERIFIED-uncaptured` |
| `post-api-prices` | `http` | `deterministic` | port `9999`: `POST /api/prices/AX02` | `UNVERIFIED-uncaptured` |
| `get-api-prices-method-not-allowed` | `http` | `deterministic` | port `9999`: `GET /api/prices/AX02` | `UNVERIFIED-uncaptured` |
| `get-populate-created` | `http` | `deterministic` | port `8091`: `GET /populate`, empty database | `UNVERIFIED-uncaptured` |
| `get-populate-already-populated` | `http` | `deterministic` | port `8091`: `GET /populate`, both tables present | `UNVERIFIED-uncaptured` |
| `ajax-orders-request` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, last message of `S1` publishing `A1` on `/orders/request` | `UNVERIFIED-uncaptured` |
| `ajax-orders-soap` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, last message of `S1` publishing `message.xml` on `/orders/soap` | `UNVERIFIED-uncaptured` |
| `ajax-orders-manufacturers` | `http` | `nondeterministic` | port `8090`: `POST /ajax/cometd`, last message of `S1` publishing on `/orders/manufacturers` | `UNVERIFIED-uncaptured` |
| `audit-over-5000-rollback` | `soap` | `deterministic` | port `1080`: `POST /orders`, envelope `E5` (quantity 3), `order_audits` absent | `UNVERIFIED-uncaptured` |
| `audit-failure-logged` | `soap` | `nondeterministic` | port `1080`: `POST /orders`, body `message.xml`, `order_audits` absent | `UNVERIFIED-uncaptured` |
| `inhouse-redelivery-exhausted` | `soap` | `nondeterministic` | port `1080`: `POST /orders`, envelope `E2`, `orders` absent | `UNVERIFIED-uncaptured` |
| `wrong-port-no-listener` | `http` | `deterministic` | port `8091`: `GET /orders` | `UNVERIFIED-uncaptured` |

Fourteen scenarios are `deterministic`, five `nondeterministic` and one `unreachable`.

- Nine scenarios call the original's own listeners over HTTP, at hosts and ports the flows hard-code, which no runtime property can redirect through a recording proxy. Their fixtures will omit `upstream` and report `UNVERIFIED-no-upstream`: `post-orders-samsung-item`, `post-orders-samsung-quantity-5`, `post-orders-inhouse-item`, `post-orders-mixed-items`, `ajax-orders-request`, `ajax-orders-soap`, `audit-over-5000-rollback`, `audit-failure-logged` and `inhouse-redelivery-exhausted`.
- `ajax-orders-manufacturers` gets no fixture while the replay cannot carry the server-issued Bayeux `clientId` into later requests, so it stays `UNVERIFIED-uncaptured`.
- After capture, the label can therefore reach at most `PARITY: PARTIAL — Tier 2A fixtures: 9 verified, 10 unverified`. It becomes `PARITY: FAIL — Tier 2A fixtures` when a captured deterministic scenario differs. The nine scenarios that can become `VERIFIED` are `get-orders-method-not-allowed`, `post-orders-malformed-envelope`, `post-samsung-orders-purchase`, `get-samsung-orders-method-not-allowed`, `post-api-prices`, `get-api-prices-method-not-allowed`, `get-populate-created`, `get-populate-already-populated` and `wrong-port-no-listener`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| service-orchestration-and-choice-routing-java | service-orchestration-and-choice-routing | SOAP/WSDL-based service | PASS | PASS | 99.3% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

This is the project's row of the root [`SUMMARY.md`](../../SUMMARY.md) (AAP 0.8.7). The fallback evidence and the scenario statuses are under [Fallback evidence](#fallback-evidence-aap-084).

- Tier 2A is blocked. [`DECISIONS.md`](../../DECISIONS.md) section 9 records `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`, so no fixture has been captured from the original. Parity rests on the fallback, which is weaker than captured fixtures. The label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed.
- Tier 2B is `N/A`: this is not a connector project, so it has no failsafe plugin and no `*IT` class.

The run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 941 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 941 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`, nested classes counted with their outer class: 99.3%, 1149 of 1157 lines.
  - `service`: `AjaxChannelBroker` 389 of 392, `AjaxOrderService` 35 of 35, `AuditService` 86 of 87, `InHouseOrderService` 26 of 26, `OrderOrchestrationService` 65 of 65, `PriceCacheService` 4 of 4, `ProductPriceService` 2 of 2, `SamsungOrderService` 35 of 35, `SamsungServiceImpl` 7 of 7.
  - `mapper`: `AjaxOrderXmlMapper` 73 of 73, `DwJsonWriter` 86 of 86, `DwXmlToJson` 94 of 98, `DwXmlWriter` 170 of 170, `OrderRequestMapper` 6 of 6, `OrderSummaryJsonMapper` 12 of 12, `PurchaseReceiptMapper` 6 of 6, `SoapEnvelopeTransformer` 53 of 53.
  - Total project LINE coverage: 92.7% (2859 of 3084 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=19`, `failed=0`. `post-orders-samsung-rejected` is `UNREACHABLE` and counted in none.
- Runtime check of the packaged jar, on free ports: `/populate`, the `reply.xml` exchange, the quantity-5 order, the Samsung `purchase`, the price `POST` and `GET`, the 405, 404 and fault answers, the in-house path and the Bayeux `/orders/manufacturers` and `/orders/request` replies answered as this README states.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as [`DECISIONS.md`](../../DECISIONS.md) section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback:

- `ServiceOrchestrationAndChoiceRoutingTest` carries both committed original assertions with their comparison semantics: the SOAP reply to `message.xml` has status 200 and equals `reply.xml` exactly (`post-orders-samsung-item`), and `/populate` answers a payload containing `db populated` (`get-populate-created`).
- The controller, endpoint, trigger, service, client and mapper tests cover every reachable branch of AAP 0.7.2 and the listener boundary cases of AAP 0.4.7.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the flows and of the platform defaults. It does not show that its bytes equal what the original runtime sent.
- Scenarios no original assertion covers, tested only by the new tests: `post-orders-samsung-quantity-5`, `post-orders-inhouse-item`, `post-orders-mixed-items`, `get-orders-method-not-allowed`, `post-orders-malformed-envelope`, `post-samsung-orders-purchase`, `get-samsung-orders-method-not-allowed`, `post-api-prices`, `get-api-prices-method-not-allowed`, `get-populate-already-populated`, `ajax-orders-request`, `ajax-orders-soap`, `ajax-orders-manufacturers`, `audit-over-5000-rollback`, `audit-failure-logged`, `inhouse-redelivery-exhausted` and `wrong-port-no-listener`.
- Unverified scenarios, by name from `src/test/resources/fixtures/SCENARIOS.md`:
  - `UNVERIFIED — nondeterministic output`, compared for structure only once captured and escalated through `DECISIONS.md` as AAP 0.8.8 sets out:
    - `ajax-orders-request`, `ajax-orders-soap` and `ajax-orders-manufacturers`: the server-generated Bayeux `clientId` names the `replyTo` channel;
    - `audit-failure-logged` and `inhouse-redelivery-exhausted`: the logged payload is an `Object.toString()` with an identity hash.
  - Unverified until captured, then compared byte for byte: `post-orders-samsung-item`, `post-orders-samsung-quantity-5`, `post-orders-inhouse-item`, `post-orders-mixed-items`, `get-orders-method-not-allowed`, `post-orders-malformed-envelope`, `post-samsung-orders-purchase`, `get-samsung-orders-method-not-allowed`, `post-api-prices`, `get-api-prices-method-not-allowed`, `get-populate-created`, `get-populate-already-populated`, `audit-over-5000-rollback` and `wrong-port-no-listener`. Of these, the five that call the original's own listeners will report `UNVERIFIED-no-upstream` ([Fixture parity](#fixture-parity-st-2)).
  - `post-orders-samsung-rejected` is `unreachable`, not unverified. Proof: the Samsung catch branch runs only when `samsungOrder` throws. The Samsung service is this application's own mock, which answers 200 and `ACCEPTED` for every request, and the item's `quantity` is a primitive `int`, so no order input makes the branch throw. The branch is kept exactly as declared, has no fixture, and is excluded from capture and from the branch-coverage claim (AAP 0.8.2).

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` is copied unchanged from `service-orchestration-and-choice-routing/`. `NOTICE` is the original's notice followed by the credits for the Mule Ajax JavaScript under `src/main/resources/mule-resource/js/`: `mule.js` and `_mule.js` under the Common Public Attribution License 1.0 (CPAL 1.0), and the Dojo files under the Academic Free License 2.1 or the modified BSD licence. The docroot under `src/main/resources/static/orders/` carries its own `MIT-LICENSE` and `LGPL-LICENSE`, copied with it.
