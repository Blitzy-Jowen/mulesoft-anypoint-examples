# Web Service Consumer — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion of the Mule EE 3.8 / DataWeave 1.0 example [`web-service-consumer`](../../web-service-consumer/) (category **SOAP/WSDL-based service**). The original folder `web-service-consumer/` stays unchanged beside this project and is the reference and Tier 2A capture source. The HTTP listener runs on Undertow, and the SOAP calls go through a Spring WS 4.1.4 `WebServiceTemplate` over SAAJ. No JAX-WS or CXF artefact is on the classpath: the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if one arrives, even transitively (AAP 0.3.2).

## Original example

The example is a JSON-over-HTTP facade over a T-shirt retailer's SOAP service (`TshirtService`, port `TshirtServicePort` of `tshirt.wsdl`). It has two flows in `web-service-consumer/src/main/app/tshirt-service-consumer.xml`:

- `orderTshirt` (lines 6-33) places an order. It turns the posted JSON into an `OrderTshirt` request, adds an `AuthenticationHeader` SOAP header carrying an API key, calls the service and returns the response as JSON.
- `listInventory` (lines 34-47) lists the inventory. It calls `ListInventory` and returns the `ListInventoryResponse` content as JSON.

Neither flow declares a logger or an exception strategy, and the service is the only system they call. The WSDL also describes `TrackOrder`, which no flow calls, so it is not converted (AAP 0.4.5). The original README tells users to send the order body below to `http://localhost:8081/orders` with `Content-Type: application/json`.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/orders` | any, including `HEAD`, `OPTIONS` and `TRACE`: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, `Content-Type: application/json`, the DataWeave JSON of the `OrderTshirt` response | `tshirt-service-consumer.xml:4,7-8,10-32` |
| `/inventory` | any, as for `/orders` | `0.0.0.0` | `http.port` | `200 OK`, `Content-Type: application/json`, the DataWeave JSON of the `ListInventoryResponse` content | `tshirt-service-consumer.xml:4,35-46` |
| any other path (`/orders/`, `/orders/1`, `/services/x` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |
| `/orders` or `/inventory`, when processing fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | platform default: neither flow declares an exception strategy |

The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### `/orders` — flow `orderTshirt`

1. The body is read raw, whatever the method and `Content-Type`; a form-urlencoded body reaches the flow as sent. `mapper/OrderTshirtRequestMapper` (script at line 12, `ns0#OrderTshirt: payload`) writes it as `<ns0:OrderTshirt xmlns:ns0="http://mulesoft.org/tshirt-service">`, with one unqualified child element per JSON member in body order, not in the WSDL's `xsd:sequence` order. A repeated key or an array item is written once per occurrence, a JSON `null` member becomes an empty element `<key/>`, and decimals keep their scale.
2. `client/TshirtServiceClient` adds the SOAP header `<ns0:AuthenticationHeader xmlns:ns0="http://mulesoft.org/tshirt-service"><apiKey>…</apiKey></ns0:AuthenticationHeader>` with the value of `tshirt.api-key` (the `soap.header` script at lines 17-23). It sends one SOAP 1.1 `POST` to `tshirt.service-address`, `Content-Type: text/xml; charset=UTF-8`, `SOAPAction: "http://mulesoft.org/tshirt-service/order-tshirt"` (`tshirt.wsdl` line 177). The Body carries the element in DataWeave's tab layout, without the XML declaration.
3. `mapper/OrderTshirtResponseMapper` (script at line 28, `payload`) writes the whole response document as DataWeave JSON. Element local names become keys, namespace prefixes and attributes are dropped, and leaves stay strings:

   ```json
   {
     "OrderTshirtResponse": {
       "orderId": "1"
     }
   }
   ```

4. If the service answers with no response or an empty SOAP Body, the reply is `200` with an empty body.

### `/inventory` — flow `listInventory`

1. With no body, the client sends the operation's empty request `<ns0:ListInventory xmlns:ns0="http://mulesoft.org/tshirt-service"/>`. A non-empty body is parsed as XML and sent as the SOAP Body unchanged, as `ws:consumer` sent the payload. The `SOAPAction` is `"http://mulesoft.org/tshirt-service/list-inventory"` (`tshirt.wsdl` line 195). No SOAP header is added, because the WSDL binds none to this operation, so `/inventory` works without an API key.
2. `mapper/ListInventoryResponseMapper` (script at line 41, `payload.ns0#ListInventoryResponse`) selects `ListInventoryResponse` in the T-shirt namespace, whatever its prefix, and writes its content rather than the wrapper. Each item is its own repeated `"inventory"` key in document order, never an array, and every leaf stays a string although the WSDL types `count` as `xsd:integer`. A two-item response gives:

   ```json
   {
     "inventory": {
       "productCode": "4102",
       "size": "L",
       "description": "Prueba",
       "count": "2"
     },
     "inventory": {
       "productCode": "1412",
       "size": "L",
       "description": "Foo",
       "count": "9"
     }
   }
   ```

3. If the selector misses (no response, another root element, or the element outside the T-shirt namespace) or selects an empty element, the reply is `200` with the four characters `null`.

### Failures

Neither flow catches anything, so every failure reaches SC-1's `500` with `{ "message": "<exception message>" }` and is never retried (AAP 0.4.7):

- `/orders` body empty: `Request body is empty`. Malformed JSON: the parser's message, so `{"email":` is answered with the body `{ "message": "Unexpected end-of-input within/between Object entries\n at [Source: UNKNOWN; line: 1, column: 10]" }`. A top-level JSON array: `XML output requires a single root element`. In each case nothing is sent to the service.
- `/orders` with `tshirt.api-key` unset or blank: `T-shirt service API key (tshirt.api-key) is not configured`. The failure is raised before any request, so no empty `apiKey` reaches the service.
- `/inventory` body that is not well-formed XML or declares a DOCTYPE: the parser's message, before any call.
- SOAP fault: the fault string, for example `Invalid API key`. A fault without a readable fault string gets the fixed text `The T-shirt service returned a SOAP fault without a readable fault string`.
- HTTP error without a fault, a read timeout or a connection failure: the Spring WS transport message, for example `I/O error: Connection reset`. Each request is sent exactly once: `TshirtServiceClient` streams it with a fixed length, so the JDK's silent re-send of a failed `POST` cannot place an order twice.

Neither listener restricts methods or media types, so SC-1's `405` and Spring's `406` and `415` never occur. A CORS pre-flight is answered like a plain `OPTIONS` request, without `Access-Control-*` or `Vary` headers, because the Mule listener applied no CORS policy (`config/PreflightDispatchConfig`). `WebServicesAutoConfiguration` is excluded, so no `MessageDispatcherServlet` serves `/services/*`.

### Tier 2A verification points

The original declares no exception strategy, so the `404` and `500` above are platform defaults, read from AAP 0.4.7. These points and the request bytes the client sends are compared against the captured original, and a captured fixture governs where they differ (AAP 0.8.4):

- the response `Content-Type`, `application/json` with no charset parameter;
- the `500` body for unhandled failures and its message text (`post-orders-malformed-json`), and the `404` text (`get-unknown-path`);
- acceptance of methods other than the original test's `POST` (`put-orders`, `get-inventory`);
- XML writer edge cases (`DwXmlWriter`, which builds the `OrderTshirt` request): null and empty elements, escaping, and the trailing line feed it never writes;
- XML-to-JSON of empty elements (`null`), and the empty `/orders` reply and `null` `/inventory` reply when the service returns no payload;
- the outbound envelope as SAAJ writes it: the `SOAP-ENV` prefix, an empty `SOAP-ENV:Header` on `ListInventory`, and the `Accept`, `Cache-Control` and `Pragma` request headers of `HttpURLConnection`. Replay compares each recorded upstream request's method, URL, body and headers (`SCENARIOS.md`), and the original's CXF stack may have written them differently.

### Sample requests

The order body of the original README, saved as `order.json`:

```json
{
  "email":"info@mulesoft.com",
  "address1":"Corrientes 316",
  "address2":"EP",
  "city":"Buenos Aires",
  "country":"Argentina",
  "name":"MuleSoft Argentina",
  "postalCode":"C1043AAQ",
  "size":"L",
  "stateOrProvince":"CABA"
}
```

```sh
curl -i -X POST http://localhost:8081/orders -H 'Content-Type: application/json' -d @order.json
curl -i http://localhost:8081/inventory
```

Against a T-shirt service that answers `orderId` `1`, the order request returns:

```console
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 53
Date: <current date>

{
  "OrderTshirtResponse": {
    "orderId": "1"
  }
}
```

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to (AAP 0.4.6).

Status: `default` — a non-secret value, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument, marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener or keeps secrets out of the log, not a key of the original, so changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README tells users to set port 8081. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`tshirt-service-consumer.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`tshirt-service-consumer.xml:4`) |
| `spring.application.name` | `String` | `web-service-consumer-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `TshirtController` like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `tshirt.service-address` | `String` | `http://tshirt-service.cloudhub.io` | `serviceAddress` attribute of `ws:consumer-config` (`tshirt-service-consumer.xml:3`), hard-coded with no placeholder | default | SOAP endpoint of `TshirtServicePort`; a public service URL, so it stays the default |
| `tshirt.api-key` | `String` | none: `${TSHIRT_API_KEY:}`, `# TODO: supply the T-shirt service API key` | none: replaces the literal hard-coded in the `soap.header` script (`tshirt-service-consumer.xml:17-23`, value at line 22) | TODO (secret), `TSHIRT_API_KEY` | value of the `AuthenticationHeader/apiKey` SOAP header of every `OrderTshirt` request. Until it is set, `/orders` answers `500` before calling the service; `/inventory` never needs it. `TshirtServiceProperties.toString()` masks it. |
| `tshirt.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of every SOAP request, the Mule default response timeout |
| `tshirt.read-timeout` | `Duration` | `10s` | none | default | read timeout of every SOAP request, the Mule default response timeout; a timeout is answered with `500` and never retried |
| `logging.level.org.springframework.ws.client.MessageTracing` | `LogLevel` | `INFO` | none | fixed | its `TRACE` writes every SOAP envelope: the API key and the order's name, e-mail and address |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | its `TRACE` writes handler arguments and the handled exception's message |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` writes the handled exception's message, which can repeat the order or the fault text |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write response bodies, including the `500` body |

- The four `logging.level` keys keep the API key and the customer's order out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`. The original had no logger, so it logged neither. `SensitiveLogLevelsTest` asserts this.
- `src/test/resources/application-test.yml` (Spring profile `test`) sets `tshirt.api-key` to `abc12345`, the original's hard-coded demo literal, for the Spring-context tests only. Production configuration never carries it.
- There are no other credentials, hosts or tenant identifiers. This is not a connector project, so there is no `src/test/resources/application-it.yml` and no `application-it.yml.example`.
- Original-to-new key map: `http.port` → `http.port`; `ws:consumer-config serviceAddress` → `tshirt.service-address`; the `apiKey` literal of the `soap.header` script → `tshirt.api-key`. The original's `wsdlLocation="tshirt.wsdl"` needs no key: `src/main/resources/tshirt.wsdl` is copied unchanged as the contract reference. The client's two `soapAction` constants are copied from it, and nothing loads it at run time.
- Override on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  TSHIRT_API_KEY=<key> mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  TSHIRT_API_KEY=<key> java -jar target/web-service-consumer-java-1.0.0-SNAPSHOT.jar --tshirt.service-address=http://localhost:8089
  ```

## Mule-to-Java mapping

| Mule construct (`web-service-consumer`) | Java construct (`com.mulesoft.examples.web_service_consumer`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`tshirt-service-consumer.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/orders"` with `http:response-builder statusCode="200"` (lines 7-9) | `controller/TshirtController`, `orders` (every method but `OPTIONS`) and `ordersOptions` (`OPTIONS`), both answering `200` `application/json` |
| `http:listener path="/inventory"` with `http:response-builder statusCode="200"` (lines 35-37) | `controller/TshirtController`, `inventory` and `inventoryOptions` |
| flows `orderTshirt` (lines 6-33) and `listInventory` (lines 34-47) | `service/TshirtOrderService.orderTshirt` and `listInventory`, the processors in flow order |
| DataWeave script at line 12, `ns0#OrderTshirt: payload` | `mapper/OrderTshirtRequestMapper`, writing through `mapper/DwXmlWriter` |
| DataWeave script at line 17, `dw:set-property propertyName="soap.header"` (`ns0#AuthenticationHeader`) | `client/TshirtServiceClient`, which builds the header from `tshirt.api-key` |
| `ws:consumer operation="OrderTshirt"` (line 25) and `operation="ListInventory"` (line 38) | `client/TshirtServiceClient.orderTshirt` and `listInventory`: `WebServiceTemplate` over a SOAP 1.1 `SaajSoapMessageFactory` and `HttpUrlConnectionMessageSender` |
| DataWeave script at line 28, `payload` as JSON | `mapper/OrderTshirtResponseMapper`, reading through `mapper/DwXmlToJson` and writing through `mapper/DwJsonWriter` |
| DataWeave script at line 41, `payload.ns0#ListInventoryResponse` as JSON | `mapper/ListInventoryResponseMapper`, over the same reader and writer |
| `ws:consumer-config` (line 3): `serviceAddress`, `wsdlLocation`, `service`, `port` | `config/TshirtServiceProperties` (prefix `tshirt`), bound by `@EnableConfigurationProperties` on the application class; `src/main/resources/tshirt.wsdl` |
| Platform default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Mule listener without a CORS policy | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `tshirt-service-consumer.xml`) | `WebServiceConsumerApplication` (`@SpringBootApplication`, `WebServicesAutoConfiguration` excluded because no SOAP endpoint is hosted) |
| `mule-app.properties` | `src/main/resources/application.yml` |
| `WebServiceConsumerIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, the live service) | `WebServiceConsumerTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`, WireMock in place of the service) |

The four DataWeave scripts are each owned by exactly the class named above (AAP 0.5.8). They reproduce these DataWeave 1.0 formats through the SC-4 classes:

- XML output (`DwXmlWriter`): the declaration `<?xml version='1.0' encoding='UTF-8'?>`, a line feed, then one element per line, indented with one tab per depth, LF line ends, and nothing after the root end tag. An element with neither text nor children is `<q/>`, and one with empty text is `<q></q>`. Text escapes `&`, `<` and `>`, and attribute values also escape `"`. A character XML 1.0 forbids fails the write with its code point named.
- XML-to-JSON (`DwXmlToJson`): keys are element local names, and namespace prefixes, URIs and attributes are dropped. A repeated element is a repeated key, never an array. Leaves are untrimmed strings, and an element with no text is `null`. A DOCTYPE fails the parse.
- JSON output (`DwJsonWriter`): two-space indentation, `"key": value`, one entry per line, LF line ends, no trailing line feed; an empty object is `{}`.

## Behaviours stubbed or externalized

- The API key the original hard-coded is externalized to `tshirt.api-key`, with no value in production configuration (AAP 0.4.6). Without it, `/orders` answers `500` and does not call the service. The original always sent its literal, so it never met this case. `/inventory` is unaffected.
- The service address keeps its original value as an overridable default (`tshirt.service-address`).
- External dependency: `http://tshirt-service.cloudhub.io` is a public demo service that may no longer be reachable; from the build host it answers `502`, and `DECISIONS.md` section 6.9 records the risk. The converted code is verified against WireMock stubs that answer with the original example's sample responses. Live behaviour can be verified only through Tier 2A capture of the original.
- A DOCTYPE in an `/inventory` request body fails the parse (`500`) and nothing is sent. This XML hardening is registered in `DECISIONS.md` section 8.
- Not carried (AAP 0.2.2): the Studio metadata (`catalog/`, `mule-project.xml`), `log4j2-test.xml` and the Studio samples under `src/test/resources/sample_data/`. The samples' content is inlined in the tests that need it.
- No behaviour of the two flows was dropped. `TrackOrder` was never reachable from the original's flows.

## Build, run and test

Run from `java-conversions/web-service-consumer-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, WireMock stands in for the T-shirt service
TSHIRT_API_KEY=<key> mvn spring-boot:run    # listener on 0.0.0.0:8081
TSHIRT_API_KEY=<key> java -jar target/web-service-consumer-java-1.0.0-SNAPSHOT.jar
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system with sandbox credentials (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate`. `spring-boot-starter-web-services` declares `jakarta.xml.ws:jakarta.xml.ws-api`, which the pom excludes; `saaj-impl` supplies the SAAJ API that `SaajSoapMessageFactory` needs.
- `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11, while Spring Framework stays at the BOM's 6.1.15 (AAP 0.6.2, `DECISIONS.md` section 2). The client and controller tests are the gate for that combination.

## Tests

| Test class | What it asserts |
|---|---|
| `WebServiceConsumerTest` | the ported `WebServiceConsumerIT` (`web-service-consumer/src/test/java/org/mule/examples/WebServiceConsumerIT.java:71-90`), with its substring semantics. `POST /orders` with `Content-Type: application/json` and `message.json` (its `ID` replaced by the epoch seconds, as in the original) returns a non-empty body containing `orderId`. `POST /inventory` with the same `Content-Type` and no body returns a non-empty body containing `"productCode": "1412"`, `"description": "Prueba2"` and `"count": "5"`. WireMock answers with the original sample responses, and each operation is called exactly once. |
| `controller/TshirtControllerTest` | on the wire through ST-1, end to end against a WireMock `TshirtService`. Asserts the exact DataWeave JSON and `200 OK` `application/json` for both endpoints, and the same `/orders` JSON for an unprefixed upstream root. The `AuthenticationHeader` carries the configured key. `GET`, `PUT`, `PATCH`, `DELETE` and `OPTIONS` are accepted on both paths, and a body sent as `application/x-www-form-urlencoded` reaches the flow unchanged. The `500` cases: an unreadable JSON or XML body, with no SOAP call; a SOAP fault, with its fault string; a fault without a readable fault string, with the fixed message; and a connection reset, after exactly one attempt. `/order`, `/orders/1`, `/orders/`, `/inventory/x` and `/services/x` → `404` `text/plain` `No listener for endpoint: <path>`. |
| `service/TshirtOrderServiceTest` | both flows with only the SOAP client mocked. An absent or empty inventory body sends the empty `ListInventory` request, and an XML body is sent as it is. A body neither flow can read is rejected before the client is called. Every client failure reaches the caller as the same instance after exactly one call. The original contains-assertions are kept. |
| `client/TshirtServiceClientTest` | against WireMock: one SOAP 1.1 `POST` per operation with the WSDL's `soapAction`. `OrderTshirt` carries the `AuthenticationHeader` with the configured key and refuses to send without one; `ListInventory` sends no header. No response or an empty Body yields `null`. A SOAP fault, an HTTP error without a fault, a read timeout and a refused connection surface as the Spring WS exceptions, never retried, and a fault without a fault string as the fixed message. Characters outside the BMP are sent as UTF-8 bytes. |
| `mapper/OrderTshirtRequestMapperTest` | script 12: members in body order, a repeated key or array item once per occurrence, `null` as an empty element, decimals with their scale, a scalar or `null` body under the root; empty, malformed and top-level-array bodies rejected; exact bytes in `DwXmlWriter` layout for the original `message.json` |
| `mapper/OrderTshirtResponseMapperTest` | script 28: the whole response document as DataWeave JSON, local-name keys, string leaves, an element without text as `null`, no trailing line feed; a `null` document yields `null` |
| `mapper/ListInventoryResponseMapperTest` | script 41: the five-item sample (the original Studio `ListInventoryResponse.xml`, inlined) as repeated `"inventory"` keys in document order; a root that misses the selector, an empty root and a `null` document yield the JSON literal `null` |
| `mapper/DwXmlWriterTest`, `mapper/DwXmlToJsonTest`, `mapper/DwJsonWriterTest` | SC-4 byte contracts, copied with their classes: declaration, tab layout and no trailing line feed; DataWeave XML reading with repeated keys and a hardened parser; DataWeave JSON layout, values and escaping |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the `404`, `405` and `500` status lines, `Content-Type` headers and body bytes, `ErrorResponse` escaping and the bounded ERROR log line |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/inventory` or `/orders` is answered exactly as a plain `OPTIONS` request, with no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener `404` |
| `SensitiveLogLevelsTest` | under a root `TRACE` level and under `--trace`, neither the API key nor the order's personal data reaches the console, for every outcome: accepted order, inventory, SOAP fault, HTTP error, connection reset, unreadable order and missing key |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed, registered in the root [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4):

| ID | Files in this project | Reference implementation |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`, test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-4 (`DwXmlWriter`, `DwXmlToJson`) | `mapper/DwXmlWriter`, `mapper/DwXmlToJson`, tests `mapper/DwXmlWriterTest`, `mapper/DwXmlToJsonTest` | **this project**. The other XML-producing projects copy `DwXmlWriter` with its test: `xml-only-soap-webservice`, `service-orchestration-and-choice-routing`, `salesforce-data-retrieval`, `get-customer-list-from-netsuite`, `importing-an-email-attachment-using-the-IMAP-connector`, `importing-an-email-attachment-using-the-POP3-connector` and `upload-to-ftp-after-converting-json-to-xml`. `service-orchestration-and-choice-routing` also copies `DwXmlToJson`. |
| SC-4 (`DwJsonWriter`) | `mapper/DwJsonWriter`, test `mapper/DwJsonWriterTest` | `processing-orders-with-dataweave-and-APIkit-java` |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |

A change to `DwXmlWriter` or `DwXmlToJson` is made here first and then copied to every consumer. `config/PreflightDispatchConfig` is the CORS pre-flight class that 30 HTTP projects carry (`DECISIONS.md` section 9, verification points).

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. WireMock serves the recorded `TshirtService` exchanges, so replay never contacts `tshirt-service.cloudhub.io`. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds its scenario rows, the `state` every fixture seeds (`tshirt.api-key` and `tshirt.service-address` pointed at WireMock) and the capture procedure. That procedure includes the hosts-file redirection needed because the original hard-codes the service address. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-orders` | `http` | `deterministic` | `POST /orders`, header `Content-Type: application/json`, body = the bytes of `message.json` with its `ID` replaced by `1700000000` | `UNVERIFIED-uncaptured` |
| `put-orders` | `http` | `deterministic` | `PUT /orders`, same header and body as `post-orders` | `UNVERIFIED-uncaptured` |
| `post-orders-malformed-json` | `http` | `deterministic` | `POST /orders`, header `Content-Type: application/json`, body `{"email":` | `UNVERIFIED-uncaptured` |
| `post-inventory-empty-body` | `http` | `deterministic` | `POST /inventory`, header `Content-Type: application/json`, empty body | `UNVERIFIED-uncaptured` |
| `get-inventory` | `http` | `deterministic` | `GET /inventory`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| web-service-consumer-java | web-service-consumer | SOAP/WSDL-based service | PASS | PASS | 97.8% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 500 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 500 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 449 of 459 lines. `service/TshirtOrderService` covers 13 of 13. The `mapper` package covers 436 of 446: `DwXmlWriter` 170 of 170, `DwJsonWriter` 86 of 86, `DwXmlToJson` 94 of 98, `OrderTshirtRequestMapper` 74 of 80, `OrderTshirtResponseMapper` 7 of 7 and `ListInventoryResponseMapper` 5 of 5. Total project LINE coverage: 97.4% (791 of 812 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=6`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback:

- `WebServiceConsumerTest` carries the original committed assertions with their substring semantics: the order response contains `orderId`, and the inventory response contains `"productCode": "1412"`, `"description": "Prueba2"` and `"count": "5"`.
- The controller, service, client and mapper tests cover every reachable branch of both flows.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions, against WireMock stubs fed the original sample responses, and the AAP's reading of the Mule 3.8 platform defaults. It does not show that its bytes, or the envelope it sends upstream, equal what the original runtime produced.
- Branches no original assertion covers, tested only by the new tests:
  - acceptance of every method on both listeners (`put-orders`, `get-inventory`);
  - malformed or empty JSON on `/orders`, answered `500` before any call (`post-orders-malformed-json`);
  - an upstream SOAP fault, HTTP error, timeout or connection failure, answered `500` and never retried;
  - an unknown path, answered with the no-listener `404` (`get-unknown-path`);
  - a missing `tshirt.api-key`, which the original could not lack.
- Unverified scenarios, until captured: `post-orders`, `put-orders`, `post-orders-malformed-json`, `post-inventory-empty-body`, `get-inventory`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `web-service-consumer/`; `NOTICE` carries the MuleSoft attribution.
