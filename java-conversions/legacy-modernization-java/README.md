# Legacy Modernization — Spring Boot conversion

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`legacy-modernization`](../../legacy-modernization/) (category General), whose single flow `Fulfillment_LegacySystemModernization` lives in `legacy-modernization/src/main/app/FufillmentWebService.xml`. The original folder stays unchanged beside this project and is the reference and the Tier 2A capture source. The listener runs on Undertow, and the SOAP service uses Spring WS 4.1.4 over SAAJ and Jakarta JAXB. No JAX-WS or CXF artefact is on the classpath: the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if one arrives, even transitively (AAP 0.3.2).

## Original example

An organization runs an old fulfillment system that accepts orders only as flat files, and it wants to take orders through a SOAP web service instead. The application front-ends the legacy system. It exposes a SOAP web service that accepts new shipping orders and confirms each order to the caller at once. Asynchronously, it maps the order to a CSV flat file and writes it to `src/main/resources/Output`, which stands in for the legacy system's drop directory.

In the original, a CXF `jaxws-service` (`serviceClass="org.ordermgmt.IFulfillment"`) exposes the service, and the Java component `org.ordermgmt.FulfillmentImpl` builds the confirmation. A DataWeave 1.0 script inside an `async` scope then maps the returned `ShippingOrderConfirmation` POJO to CSV for a `file:outbound-endpoint`. The original README's Anypoint Studio and soapUI click-through steps are replaced by [Build, run and test](#build-run-and-test). The soapUI project is still carried.

## Endpoints and triggers

The project has one listener, as the original's single `http:listener-config` did (`FufillmentWebService.xml:3`). It binds `0.0.0.0:1080`, the host and port the flow fixes without a placeholder. The project has no non-HTTP trigger.

| Port | Path | Methods | Implemented by | Behaviour |
|---|---|---|---|---|
| `1080` (`server.port`, bound to `0.0.0.0` by `server.address`) | `/OrderFulfillment`, exactly | `POST` only, in any letter case (`allowedMethods="POST"`, `:5`) | `controller/FulfillmentEndpoint` → `service/FulfillmentService` → `service/ShippingOrderExportService` | SOAP 1.1 document/literal-wrapped operation `putShippingOrder` in namespace `http://ordermgmt.org/`, with `soapAction=""`. The request `putShippingOrder` has the unqualified children `shippingId`, `billingAddress`, `shippingAddress` and `order` (`orderItem*`, each with `merchantSKU` and `quantity`). Each address carries `name`, `line1`, `line2`, `city`, `stateOrProvinceCode`, `countryCode` and `postalCode`. The reply is `200 OK`, `Content-Type: text/xml;charset=utf-8`, holding `putShippingOrderResponse` with `ShippingOrderConfirmation`: the echoed `shippingOrder` and `orderReceivedStatus` `true`. Every request that reaches the fulfillment component also starts the [asynchronous CSV export](#asynchronous-csv-export). |

- A SOAP response has CXF's envelope form: `soap:` envelope prefix, no empty `Header`, the JAXB `ns2:` payload prefix, no XML declaration and no whitespace between elements (SC-11 `config/SoapEnvelopeStyleInterceptor`, with the `ns2` prefix kept by `config/WebServiceConfig`'s marshaller). For `src/test/resources/message.xml` the reply equals `src/test/resources/reply.xml` byte for byte:

  ```xml
  <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:putShippingOrderResponse xmlns:ns2="http://ordermgmt.org/"><ShippingOrderConfirmation><shippingOrder><shippingId>1234</shippingId><billingAddress>…</billingAddress><shippingAddress>…</shippingAddress><order><orderItem><merchantSKU>1234</merchantSKU><quantity>500</quantity></orderItem>…</order></shippingOrder><orderReceivedStatus>true</orderReceivedStatus></ShippingOrderConfirmation></ns2:putShippingOrderResponse></soap:Body></soap:Envelope>
  ```

- No `?wsdl` is published. The original's listener accepts POST only, so it never served a WSDL, and `GET /OrderFulfillment?wsdl` gets the method 405 below (AAP 0.4.5). `src/main/resources/wsdl/IFulfillmentService.wsdl` (service `IFulfillmentService`, binding `IFulfillmentServiceSoapBinding`, portType `IFulfillment`) is a reference copy extracted from the soapUI project and checked against `reply.xml`; no code reads it. SC-11's `config/WsdlQueryPublisher` is carried with its row but registered for no path.
- `src/main/resources/LegacyModernizationExample-soapui-project.xml` is carried unchanged for soapUI users. Its endpoint `http://localhost:1080/OrderFulfillment` reaches this application when it runs on its default port.

### Listener defaults and faults (AAP 0.4.7)

| Case | Response | Enforced by |
|---|---|---|
| Any method other than `POST` on `/OrderFulfillment` (`POST` is matched in any letter case, as Mule 3.8 matched methods, so `post` is served): `GET` (with or without `?wsdl`), `HEAD`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`, `TRACE` | `405 Method Not Allowed`, `Content-Type: text/plain` (no charset), body `Method not allowed for endpoint: /OrderFulfillment`, no `Allow` header | SC-11 `config/SoapListenerBoundaryAdapter`, given the POST-only path by `config/WebServiceConfig`; `spring.webservices.servlet.init` lets `OPTIONS` and `TRACE` reach it |
| Any other path, sub-paths such as `/OrderFulfillment/extra` included | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` | SC-1 `exception/GlobalExceptionHandler`; `WebServiceConfig` maps the `MessageDispatcherServlet` to exactly `/OrderFulfillment`, so every other path reaches the MVC dispatcher |
| A body that cannot be read as a SOAP 1.1 envelope (malformed XML, an empty body, a body nested deeper than 100 elements (CXF's default `maxElementDepth`), a root other than `soap:Envelope`), or a `Content-Type` the SAAJ message factory rejects | HTTP 500, SOAP 1.1 fault, `faultcode` `soap:Client`, `faultstring` the parser's message, for example `XML document structures must start and end within the same entity.` for a truncated envelope | SC-11 `config/SoapListenerBoundaryAdapter` |
| A valid envelope whose body root is not `{http://ordermgmt.org/}putShippingOrder` | HTTP 500, `soap:Client`, `faultstring` `Message part {http://ordermgmt.org/}unknownOperation was not recognized.  (Does it exist in service WSDL?)` for an `ord:unknownOperation` root, with two spaces before the parenthesis; `Message part null was not recognized.  (Does it exist in service WSDL?)` for an empty `Body` | SC-11 `config/SoapFaultConfig` |
| A `mustUnderstand="1"` header block that nothing on the service understands, for example `<x:Sec xmlns:x="urn:x" soapenv:mustUnderstand="1"/>` | HTTP 500, `soap:MustUnderstand`, `faultstring` `MustUnderstand headers: [{<namespace>}<localName>] are not understood.`, for example `MustUnderstand headers: [{urn:x}Sec] are not understood.`; no export is started | SC-11 `config/SoapFaultConfig` (`CxfStyleSoapMessageDispatcher`) |
| An exception escaping the endpoint | HTTP 500, `soap:Server`, `faultstring` the exception message (CXF's `Fault occurred while processing.` when it has none; a `NullPointerException` the JVM raised counts as having none); no export is started | SC-11 `config/SoapFaultConfig` |

Every fault uses the envelope form above. Tier 2A verification points, where a captured fixture of the original governs and the code is changed to match: the 405 and 404 bytes, the fault texts, the SOAP response and fault headers (SC-11 `config/SoapListenerBoundaryAdapter` drops the `Accept` and `SOAPAction: ""` request headers SAAJ keeps on every message, which CXF did not send), characters outside the Basic Multilingual Plane written as raw UTF-8 rather than SAAJ's numeric character references (SC-11 `SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory`, as CXF wrote them), and the `soap:Client` answer to a non-`text/xml` `Content-Type` (`DECISIONS.md` section 9, "Verification points reported during conversion").

### Asynchronous CSV export

After the fulfillment component returns the confirmation, and before the endpoint returns the response, `FulfillmentEndpoint` hands the confirmation to `ShippingOrderExportService.export`. That method is `@Async` and runs on Spring Boot's `applicationTaskExecutor`, whose threads are named `legacy-fulfillment-async-`, so the response never waits for the file. It keeps the `async` scope's processor order (`FufillmentWebService.xml:9-36`):

1. `mapper/ShippingOrderCsvMapper`, the hand-written port of the "Map Fullfilment order to CSV" script (`%output application/csv header=true`, `:12-32`), rendered by SC-4 `mapper/DwCsvWriter`. It writes a header row and one row per `shippingOrder.order.orderItemList` element, in list order, with comma separators and an LF after every line, the last included. The columns are the script's, in its order: `MSKU`, `QTY`, `BillingAddressName`, `BillingAddressStreet`, `BillingAddrCity`, `BillingAddrState`, `BillingAddrCountry`, `BillingAddrZipCode`, `ShippingAddrName`, `ShippingAddrStreet`, `ShippingAddrCity`, `ShippingAddrState`, `ShippingAddrCountry`, `ShippingAddrZipCode`, `ShippingId`. Each street is `line1 + ' ' + line2`, and the address and `ShippingId` columns repeat the order-level values on every row.
2. The `byte-array-to-string-transformer` (`:34`) needs no step: the mapper already returns text.
3. SC-6 `service/FileOutboundWriter` (`file:outbound-endpoint`, `:35`) writes the text as UTF-8 to `<fulfillment.output-dir>/ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt`. The timestamp is server-local time, read from the system clock in the default time zone when the file is written, as Mule's `server.dateTime` was. The writer creates the directory when it is missing and overwrites a same-named file the process may write; one it may not write is refused, and the export fails. Wherever the directory admits the new entry and the rename, it replaces or creates a regular file as a whole: it fills a hidden `.<uuid>.tmp` file beside the target and renames it over the target. Where the file system supports an atomic rename, the usual case on local file systems, the rename is atomic, so a reader sees either the old or the new whole file, never an empty or partial one, and a failed write leaves the existing file untouched and removes the temporary file. On a file system that offers no atomic rename, the writer falls back to a replacing move, which is not atomic: while it runs a reader can see the target missing or incomplete, and a move that fails can leave the target incomplete or remove the existing file; the writer then removes the temporary file and, as after a refused rename, overwrites in place a regular file left at the target; with none left, the export fails. Where the directory refuses the temporary file, typically because the process may not write it, or refuses the rename over an existing file, as a sticky directory such as `/tmp` does for a file another user owns, an existing file the process may write is overwritten in place instead, as Mule overwrote it: it keeps its inode, owner and mode, but the write is not atomic, so its readers can see it empty or partly written, and a write that fails part-way leaves it so. A new file in a directory that refuses its temporary file is not created, and the export fails. An existing target that is neither a regular file nor a directory, such as a named pipe or a device, is written in place, as Mule wrote it, with no temporary file.

A failed export is logged at ERROR (`Exception while exporting shipping order to the legacy fulfillment system`) and never affects the SOAP response, as Mule's default exception strategy handled a failure inside an `async` scope. A successful export logs nothing, because the original scope holds no `logger`.

For `message.xml` the export writes:

```text
MSKU,QTY,BillingAddressName,BillingAddressStreet,BillingAddrCity,BillingAddrState,BillingAddrCountry,BillingAddrZipCode,ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,ShippingAddrCountry,ShippingAddrZipCode,ShippingId
1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234
6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234
9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234
```

`src/main/resources/LegacySystemFulfillmentOrder_Example.csv` and `src/main/resources/FulfillmentShippingOrder_Input_Example.xml` are the original's sample files, carried unchanged and read by no code. The sample CSV is a header row whose column order (`ShippingId` first, `MSKU` and `QTY` last, `BillingAddressCity` rather than `BillingAddrCity`) differs from what the flow writes; the flow's DataWeave script governs.

### Sample exchanges

With the application on its default port (`Date` varies):

```console
$ curl -i -X POST -H 'Content-Type: text/xml;charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:1080/OrderFulfillment
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/xml;charset=utf-8
Content-Length: 1085
Date: <current date>

<the bytes of src/test/resources/reply.xml>

$ curl -i 'http://localhost:1080/OrderFulfillment?wsdl'
HTTP/1.1 405 Method Not Allowed
Connection: keep-alive
Content-Type: text/plain
Content-Length: 50
Date: <current date>

Method not allowed for endpoint: /OrderFulfillment

$ curl -i -X POST -H 'Content-Type: text/xml;charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:1080/unknown
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 34
Date: <current date>

No listener for endpoint: /unknown
```

## Configuration keys

Every key of `src/main/resources/application.yml`. The original's `mule-app.properties` holds no keys and its flow reads no placeholder, so every value is a fixed default taken from the flow's literals (AAP 0.6.6). There are no secrets, credentials or TODO placeholders, so the project has no `application-test.yml`, and it has no `application-it.yml` because it is not a connector project.

The Type column gives the Java type each value binds to, taken from the code or the Spring Boot property that reads it.

| Key | Type | Default | Source in the original | Purpose |
|---|---|---|---|---|
| `server.port` | `Integer` | `1080` | `http:listener-config port="1080"`, `FufillmentWebService.xml:3` | the listener port |
| `server.address` | `InetAddress` | `0.0.0.0` | `host="0.0.0.0"`, `:3` | the listener's bind host |
| `fulfillment.output-dir` | `String` (a directory path) | `src/main/resources/Output` | `file:outbound-endpoint path`, `:35`, hard-coded in the original; a new key | directory of the exported `ShippingOrder-*.txt` files, absolute or relative to the working directory |
| `spring.webservices.path` | `String` | `/OrderFulfillment` | listener `path="OrderFulfillment"`, `:5` | registers Spring WS's `MessageDispatcherServlet`; `config/WebServiceConfig` narrows its mapping to exactly `/OrderFulfillment`, and `dispatcherServlet` stays at `/` |
| `spring.webservices.servlet.init` | `Map<String, String>` (servlet init-parameter name to value) | `"[dispatchOptionsRequest]": "true"`, `"[dispatchTraceRequest]": "true"` | `allowedMethods="POST"`, `:5` | `OPTIONS` and `TRACE` reach the listener boundary's 405 like every other non-POST method |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | `TRACE` on any other path reaches SC-1's no-listener 404, not the servlet's echo |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | no static-resource handler takes a path from SC-1's no-listener 404 |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | no Boot `/error` page rewrites the SC-1 and SC-11 response bytes |
| `spring.task.execution.thread-name-prefix` | `String` | `legacy-fulfillment-async-` | `async` scope, `:9-36` | names the `applicationTaskExecutor` threads behind the `@Async` export; pool sizes stay at Boot's defaults |
| `spring.application.name` | `String` | `legacy-modernization-java` | none | application name in log lines |
| `logging.level.org.springframework.ws.server.MessageTracing` | `LogLevel` | `INFO` | none: `FufillmentWebService.xml` declares no logger | its `.received`/`.sent` `TRACE` write every `putShippingOrder` envelope and its reply: the billing and shipping addresses |

- The `logging.level` key keeps the order's billing and shipping addresses out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`. The original had no logger, so it logged neither. The application sends no SOAP request, so no client `MessageTracing` logger is pinned. The nested `UnderRootTrace` run of `controller/FulfillmentEndpointTest` asserts this.

Override a key on the command line or through the environment (relaxed binding maps `FULFILLMENT_OUTPUTDIR` to `fulfillment.output-dir`):

```sh
java -jar target/legacy-modernization-java-1.0.0-SNAPSHOT.jar --server.port=18080 --fulfillment.output-dir=target/Output
mvn spring-boot:run -Dspring-boot.run.arguments=--fulfillment.output-dir=target/Output
```

## Mule-to-Java mapping

| Mule construct (`FufillmentWebService.xml`) | Java construct (`com.mulesoft.examples.legacy_modernization`) |
|---|---|
| `http:listener-config host="0.0.0.0" port="1080"` + `http:listener path="OrderFulfillment" allowedMethods="POST"` (`:3,5`) | `server.address`, `server.port` and `spring.webservices.path` on Undertow (`spring-boot-starter-undertow`, Tomcat excluded); `config/WebServiceConfig` maps the `MessageDispatcherServlet` to exactly `/OrderFulfillment` and passes it as the POST-only path to SC-11 `config/SoapListenerBoundaryAdapter` |
| `cxf:jaxws-service serviceClass="org.ordermgmt.IFulfillment"` (`:7`) | `controller/FulfillmentEndpoint` (`@Endpoint`, `@PayloadRoot(namespace = "http://ordermgmt.org/", localPart = "putShippingOrder")`) over the `model/soap/PutShippingOrder` and `model/soap/PutShippingOrderResponse` wrappers (AAP 0.3.2), marshalled by `config/WebServiceConfig`'s `Jaxb2Marshaller`; CXF's envelope and fault style from SC-11 `config/SoapEnvelopeStyleInterceptor`, `config/SoapFaultConfig` and `config/SoapListenerBoundaryAdapter`; `WebServiceConfig` is a `WsConfigurer` without `@EnableWs` |
| `component class="org.ordermgmt.FulfillmentImpl"` (`:8`) | `service/FulfillmentService`, the ported `FulfillmentImpl`, implementing the plain interface `service/IFulfillment` (the `javax.jws` annotations removed); prototype-scoped and fetched per request, as Mule created a component instance per call |
| `org.ordermgmt` models `Address`, `Order`, `OrderItem`, `ShippingOrder`, `ShippingOrderConfirmation` | `model/Address`, `model/Order`, `model/OrderItem`, `model/ShippingOrder`, `model/ShippingOrderConfirmation`, ported with their JAXB annotations and access types unchanged and `jakarta.xml.bind` imports |
| `async` "Async - Legacy Fulfillment Service" (`:9-36`) | `service/ShippingOrderExportService.export`, `@Async` on `applicationTaskExecutor` (`@EnableAsync` on `LegacyModernizationApplication`) |
| `dw:transform-message` "Map Fullfilment order to CSV" (`%dw 1.0` at line 12) | `mapper/ShippingOrderCsvMapper` over SC-4 `mapper/DwCsvWriter` (AAP 0.5.8) |
| `byte-array-to-string-transformer` + `file:outbound-endpoint path="src/main/resources/Output" outputPattern="ShippingOrder-#[server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')].txt"` (`:34-35`) | SC-6 `service/FileOutboundWriter`, called by `ShippingOrderExportService` with `fulfillment.output-dir` and the `dd-MM-yy_HH-mm-ss.SSS` file name |
| Platform default responses for Spring MVC (no-listener 404) | SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse` |
| `mule-deploy.properties` (`config.resources=FufillmentWebService.xml`) | `LegacyModernizationApplication` (`@SpringBootApplication`, `@EnableAsync`) |
| `mule-app.properties` (no keys) | `src/main/resources/application.yml` |
| `LegacyModernizationIT` (`FunctionalTestCase`, `MuleClient`) | `LegacyModernizationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`) |

No JAX-WS or CXF type is used anywhere. The enforcer ban covers `javax.xml.ws`, `jakarta.xml.ws`, `javax.jws`, `jakarta.jws`, `javax.xml:jaxws-api`, `org.apache.cxf`, `com.sun.xml.ws` and `org.glassfish.metro`, and the pom excludes the `jakarta.xml.ws:jakarta.xml.ws-api` that `spring-boot-starter-web-services` declares. `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11 while Spring Framework stays at 6.1.15 (AAP 0.6.2, `DECISIONS.md` section 2); the endpoint tests are the gate for that combination.

## Behaviours kept, plan-chosen and externalized

- Fulfillment logic, ported unchanged: `FulfillmentService.putShippingOrder` builds the `ShippingOrder` and a `ShippingOrderConfirmation` with `orderReceivedStatus` `true` in its public fields and returns the confirmation. The original created a new `FulfillmentImpl` per call through `<component class>`, so the service is prototype-scoped: `FulfillmentEndpoint` fetches a fresh instance from an `ObjectProvider` for every request, and concurrent requests never share those fields. The original's `catch` printed the stack trace; the port logs one ERROR line naming only the exception type and its throw site, because the exception text can carry request values. It then returns the `null` confirmation, which CXF wrote as an empty `putShippingOrderResponse`, and the export of that `null` fails and is logged.
- Export failures follow the script, which navigates `shippingOrder.order.orderItemList` and joins the street lines with `++`. DataWeave 1.0's `++` rejects a `null` operand, so a request whose address, `line1` or `line2` is missing fails the export with an `IllegalArgumentException` naming the missing part. No file is written, the failure is logged at ERROR, and the SOAP reply is unaffected. A missing `order`, or an `order` without `orderItem` elements (which unmarshals to no item list), fails the same way. The exact DataWeave error and whether the original wrote a partial file are Tier 2A verification points. Other `null` values, such as an address `name`, become empty CSV fields.
- Output directory: the default `src/main/resources/Output` is relative, so it resolves against the working directory, as the original's `file:outbound-endpoint path` did. Run the application from `java-conversions/legacy-modernization-java/` to write where the original wrote, or set `fulfillment.output-dir`. Files written there at run time are output, not sources, and are never committed. Tests never write there (see [Build, run and test](#build-run-and-test)).
- File names have millisecond resolution, so two exports in the same millisecond write the same name and the later overwrites the earlier, as Mule's file endpoint overwrote by default.
- Externalized or stubbed: nothing. The original reaches no external system and holds no credential; the legacy system is the output directory, as in the original.
- Not carried (AAP 0.2.2): `mule-project.xml`, `catalog/**`, `src/main/resources/log4j2.xml`, `src/test/resources/log4j2-test.xml`, and the Studio preview sample data.

## Build, run and test

Run from `java-conversions/legacy-modernization-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:1080; exports to ./src/main/resources/Output
java -jar target/legacy-modernization-java-1.0.0-SNAPSHOT.jar
```

Then submit the original test's order, as soapUI's "Request 1" did:

```sh
curl -i -X POST -H 'Content-Type: text/xml;charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:1080/OrderFulfillment
ls src/main/resources/Output    # ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt
```

- Send the envelope as `text/xml`: the SOAP 1.1 message factory answers other content types with a `soap:Client` fault.
- Running the application needs port 1080 free; override it as shown under [Configuration keys](#configuration-keys). `mvn test` does not: the Spring tests run the listener on a random port.
- Tests never write into `src/main/resources/Output`. Surefire sets `fulfillment.output-dir` to `target/test-output/Output` for every test, and `LegacyModernizationTest` and `FulfillmentEndpointTest` override it with a JUnit temporary directory.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it asserts |
|---|---|
| `LegacyModernizationTest` | the ported `LegacyModernizationIT` (`legacy-modernization/src/test/java/org/mule/examples/LegacyModernizationIT.java:52-64`), end to end with nothing mocked: `POST /OrderFulfillment` with `message.xml` returns a non-empty payload equal to `reply.xml` exactly; then, within the original's 5000 ms sleep, exactly one `ShippingOrder-*.txt` is exported |
| `controller/FulfillmentEndpointTest` | on the wire through ST-1: the exact reply and the exact CSV, written on a `legacy-fulfillment-async-` thread; the 405 for `GET /OrderFulfillment` and `GET /OrderFulfillment?wsdl`; the `soap:Client` fault for a truncated envelope, an empty body and an unknown operation; the no-listener 404 for `/OrderFulfillment/extra` and `/unknown`; and, with a failing `IFulfillment`, the `soap:Server` fault carrying the exception message and no export; nested `UnderRootTrace`, under `logging.level.root=TRACE`: the original `message.xml` order answered with `reply.xml` and exported while none of its billing or shipping address values reaches the console |
| `service/FulfillmentServiceTest` | the ported component: the confirmation echoes its inputs with `orderReceivedStatus` `true`, the public fields hold the returned instances, the plain `IFulfillment` interface, the prototype scope, and the swallowed failure logged with type and throw site only |
| `service/ShippingOrderExportServiceTest` | the timestamped file name and CSV content on a fixed clock, overwrite at the same instant, mapping failures swallowed and logged at ERROR with no file written, and the default-zone clock of the public constructor |
| `mapper/ShippingOrderCsvMapperTest` | the script's CSV for a three-item confirmation and for the JAXB-unmarshalled original test message, one ordered row per item, billing and shipping columns from their own addresses, `null` names as empty fields, each `null` navigated part rejected, and no rows for an empty item list |
| `mapper/DwCsvWriterTest`, `service/FileOutboundWriterTest` | SC-4 and SC-6, copied with their classes: DataWeave CSV bytes, quoting and empty fields; directory creation, overwrite, UTF-8 encoding, whole-file replacement, the in-place write of a named pipe and, run by a user a read-only directory refuses (not root), the in-place overwrite of an existing file in it and the refusal of a new one, and failure reporting |
| `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest`, `config/WsdlQueryPublisherTest` | SC-11: CXF envelope bytes, `soap:Server`, `soap:Client` and `soap:MustUnderstand` faults inside dispatch, the boundary faults and the POST-only 405, and `?wsdl`/`?xsd=` publication as a component (registered for no path here) |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes |
| `FixtureParityTest` | ST-2 fixture replay (section below); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed, registered in the root [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4). A change to one is made in its reference project first and then copied here.

| ID | Files in this project | Reference implementation |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`; test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-4 | `mapper/DwCsvWriter`; test `mapper/DwCsvWriterTest` | `processing-orders-with-dataweave-and-APIkit-java` |
| SC-6 | `service/FileOutboundWriter`; test `service/FileOutboundWriterTest` | `processing-orders-with-dataweave-and-APIkit-java` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`; tests `config/SoapEnvelopeStyleInterceptorTest`, `config/WsdlQueryPublisherTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest` | `foreach-processing-and-choice-routing-java` |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |

This project uses SC-11 for envelopes and faults only. It passes the POST-only path `/OrderFulfillment` to `SoapListenerBoundaryAdapter` and registers no WSDL with `WsdlQueryPublisher` (AAP 0.4.4, 0.4.5).

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds its scenario rows, the `state` every fixture seeds (`fulfillment.output-dir` pointed at a replay directory) and the capture procedure. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-order-fulfillment` | `soap` | `deterministic` | `POST /OrderFulfillment`, body `message.xml` | `UNVERIFIED-uncaptured` |
| `post-order-fulfillment-csv-export` | `soap` | `nondeterministic` | `POST /OrderFulfillment`, body `message.xml`; the exported file is compared | `UNVERIFIED-uncaptured` |
| `get-order-fulfillment-method-not-allowed` | `http` | `deterministic` | `GET /OrderFulfillment` | `UNVERIFIED-uncaptured` |
| `post-order-fulfillment-malformed-envelope` | `soap` | `deterministic` | `POST /OrderFulfillment`, a truncated envelope | `UNVERIFIED-uncaptured` |
| `post-order-fulfillment-unknown-operation` | `soap` | `deterministic` | `POST /OrderFulfillment`, an envelope with body root `ord:unknownOperation` | `UNVERIFIED-uncaptured` |
| `post-unknown-path` | `http` | `deterministic` | `POST /unknown`, body `message.xml` | `UNVERIFIED-uncaptured` |

No scenario is `unreachable`: `FulfillmentImpl.putShippingOrder` has a single path.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| legacy-modernization-java | legacy-modernization | General | PASS | PASS | 88.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 456 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); enforcer ban passed; JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 456 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 473 of 533 lines. `service/ShippingOrderExportService` 18 of 18, `service/FulfillmentService` 10 of 10, `service/FileOutboundWriter` 343 of 403 (its nested classes included), `mapper/ShippingOrderCsvMapper` 47 of 47, `mapper/DwCsvWriter` 55 of 55. Total project LINE coverage: 88.4% (1403 of 1587 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=6`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback, and the label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7):

- `LegacyModernizationTest` carries the original committed assertion with its exact-equality semantics: the reply to `message.xml` equals `reply.xml`.
- The endpoint, service and mapper tests cover every reachable branch of AAP 0.7.5 and the listener cases of AAP 0.4.7, on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the plan's reading of the flow and of the platform defaults, but not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: the CSV export's content and file name (`post-order-fulfillment-csv-export`; the original test only slept 5000 ms), the 405 on a non-POST request (`get-order-fulfillment-method-not-allowed`), the 404 on another path (`post-unknown-path`), and the malformed-envelope and unknown-operation faults (`post-order-fulfillment-malformed-envelope`, `post-order-fulfillment-unknown-operation`).
- Unverified scenarios, by name from `src/test/resources/fixtures/SCENARIOS.md`; none is captured yet:
  - `UNVERIFIED — nondeterministic output`, because the exported file name embeds the export timestamp `dd-MM-yy_HH-mm-ss.SSS`: `post-order-fulfillment-csv-export`. Once captured it is compared for structure only, and it is escalated through `DECISIONS.md` (section 9, "Unverified-scenario register") as AAP 0.8.8 sets out.
  - Unverified until captured, then compared byte for byte: `post-order-fulfillment`, `get-order-fulfillment-method-not-allowed`, `post-order-fulfillment-malformed-envelope`, `post-order-fulfillment-unknown-operation`, `post-unknown-path`.
- After capture the label is at best `PARITY: PARTIAL — Tier 2A fixtures: 5 verified, 1 unverified`, because the nondeterministic export counts as unverified, or `PARITY: FAIL — Tier 2A fixtures` when any captured deterministic scenario differs.

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `legacy-modernization/`.
