# Proxying a SOAP API — Spring Boot conversion

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8.0 example [`proxying-a-soap-api`](../../proxying-a-soap-api/) (category General). The original's one flow file is `proxying-a-soap-api/src/main/app/soap-api-proxy.xml`. The original folder `proxying-a-soap-api/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

The application is a minimal proxy in front of an existing SOAP API, predic8's public `ShopService` (`http://www.predic8.com:8080/shop/ShopService`). Applications post their SOAP requests to the proxy, which calls the real API and hands its answer back. Posting a `create` request returns a SOAP envelope with a `cartId` field: the customer now owns the shopping cart under that ID.

Its single flow `main` (`soap-api-proxy.xml:5-12`) receives every request on the `/` listener and runs these processors in order:

1. `cxf:proxy-service` takes the caller's envelope as it arrived (`payload="envelope"`) and publishes the upstream WSDL at `?wsdl`.
2. The `copy-headers` sub-flow copies the caller's headers.
3. `cxf:proxy-client` passes the envelope on.
4. `http:request` POSTs it to `shop/ShopService` on `www.predic8.com:8080`.
5. `copy-headers` runs again on the response.

The flow declares no logger and no exception strategy.

The sample request of the original README, as the original test sends it (`src/test/resources/message.xml`, tab-indented, no trailing newline):

```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://predic8.com/wsdl/shop/1/">
	<soapenv:Header/>
	<soapenv:Body>
	<ns:create>
	<customerId>001</customerId>
	</ns:create>
	</soapenv:Body>
</soapenv:Envelope>
```

### Manual test (the original's procedure)

These are the original README's steps, rewritten for the Java run command. They depend on predic8's public service as it was when the example was written, and were not re-verified for this conversion. The automated tests never contact predic8.

1. Build and start the proxy from `java-conversions/proxying-a-soap-api-java/` (see [Build, run and test](#build-run-and-test)). It listens on `http://localhost:8081`.
2. Send the sample request to the proxy URL:

   ```sh
   curl -i -H 'Content-Type: text/xml; charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:8081/
   ```

3. Or use [SoapUI](http://www.soapui.org/), which builds the message structure from the WSDL:
   1. In File > Preferences, uncheck the **Response compression** option. Otherwise the API returns a gzip payload, which needs extra steps to read.
   2. Create a new project and give it the WSDL URI `http://www.predic8.com:8080/shop/ShopService?wsdl`, or the proxy's own copy `http://localhost:8081/?wsdl`.
   3. Direct the requests to `http://localhost:8081`.

The answer is a SOAP envelope with a `cartId` field.

The proxy does not undo compression. With response compression on, SoapUI asks for gzip, the caller's `Accept-Encoding` reaches the upstream unchanged, and the gzip body comes back byte for byte with its `Content-Encoding` header copied. The proxy itself never asks for compression.

The original README's "How it works" also says the proxy verifies the request's structure against the WSDL. The flow does not do that: `validationEnabled` is not set on `cxf:proxy-service`, so no payload is validated. This conversion follows the flow and validates nothing either.

## Endpoints and triggers

| Path | Methods | Host | Port | Answered by | Implemented by | Source |
|---|---|---|---|---|---|---|
| `/` only (Mule `path="/"` matches the root alone) | every method: the listener declares no `allowedMethods` (AAP 0.4.9), so `GET`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, CORS pre-flights, `TRACE` and extension methods are forwarded too | `0.0.0.0` | `http.port`, default `8081` | one `POST` to `${proxy.upstream-url}` (default `http://www.predic8.com:8080/shop/ShopService`), the fixed URL with no inbound path or query appended | `controller/SoapProxyController` → `service/SoapProxyService` → `client/ShopServiceClient` | `soap-api-proxy.xml:3,4,6-11` |
| `GET /?wsdl` (query `wsdl` in any case) and `GET /?xsd=<location>` | `GET` | `0.0.0.0` | `http.port` | the upstream WSDL read from `proxy.wsdl-location` at start-up, and each schema it reaches; no upstream call | SC-11 `config/WsdlQueryPublisher`, registered on `/*` by `ProxyingASoapApiApplication.wsdlQueryPublisher` | `soap-api-proxy.xml:7` |
| any other path (`/other`, `/x/`, `/shop/ShopService`, …) | every method | `0.0.0.0` | `http.port` | `404`, `text/plain`, `No listener for endpoint: <path>`; nothing forwarded | SC-1 `exception/GlobalExceptionHandler` | `soap-api-proxy.xml:6` |

The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Behaviour carried from the original

Request leg:

- The envelope bytes are forwarded unchanged and unparsed. The original ran `cxf:proxy-service` and `cxf:proxy-client` with `payload="envelope"`, so the conversion forwards the raw envelope over `RestClient` and has no Spring WS endpoint (AAP 0.3.2). A WS-Security header inside the envelope reaches the upstream as sent.
- Headers are copied by SC-13 `mapper/ProxyHeaderMapper.toUpstream`. It copies every header except `Host`, `Content-Length`, `Connection`, `Transfer-Encoding` and every name starting with `http.` or `MULE_`, all matched ignoring case, as the `copy-headers` sub-flow removed them (`soap-api-proxy.xml:13-21`).
- The caller's `Authorization`, `SOAPAction`, `Cookie`, `Accept-Encoding`, `Expect` and `Upgrade` reach the upstream unchanged (AAP 0.4.1). The proxy adds no authentication of its own.
- The CXF proxy client's SOAP 1.1 outbound defaults are applied to the copied headers (`SoapProxyService`):
  - A caller `Content-Type` of `text/xml` or `application/soap+xml` is kept as written, parameters included.
  - Any other, absent or unparseable `Content-Type` is replaced by `text/xml; charset=UTF-8` (`SoapProxyService.UPSTREAM_SOAP11_CONTENT_TYPE`).
  - A SOAP 1.1 request without `SOAPAction` gets `SOAPAction: ""`. SOAP 1.2 carries its action in the `Content-Type`, so nothing is added for it.
- Whatever the caller's method, the upstream receives the one `POST` that `http:request method="POST"` fixes, carrying the caller's body bytes, which are empty for a `GET` without a body. The inbound query string is never forwarded.
- `OPTIONS` has its own mapping in the controller. A CORS pre-flight is forwarded like any other `OPTIONS` request (`config/PreflightForwardingConfig`). `TRACE` reaches the controller through `spring.mvc.dispatch-trace-request`.

Upstream call (`client/ShopServiceClient`):

- `RestClient` over Apache HttpClient 5 (HTTP/1.1), one pooled client shared by every request. Connections have no limit (Mule's default `maxConnections` -1) and are checked before each reuse.
- Exactly one request per call, never retried, re-sent or re-authenticated.
- Redirects are followed, as the Mule 3.8 requester did by default:
  - A 307 or 308 re-sends the `POST` with its body; a 301, 302 or 303 continues as a `GET` without the body.
  - A redirect from `https` to `http` is not followed and is returned as received.
  - Once a redirect leaves the original origin, `Authorization`, `Cookie`, `Origin`, `Referer` and `Host` are no longer sent.
- `proxy.response-timeout-ms` (10 s, the Mule default response timeout) bounds the connect, the TLS handshake, every body write, the wait for the status and headers, and every wait for body bytes.
- `Expect: 100-continue` keeps its meaning towards the upstream. Several `Cookie` headers travel as one `Cookie` header joined with `; `.
- No content compression is requested and no cookie store or authentication cache is kept, so no state passes from one caller to the next.

Response leg:

- Upstream status below 400: the answer is **200**, because the second `copy-headers` removes `http.status` with the other `http.*` properties. This holds for a 201, a 202 or a redirect the client did not follow too (Tier 2A verification point). The body bytes are returned unparsed.
- The upstream response headers are copied back by `ProxyHeaderMapper.toCaller` under the same removal rule. `Content-Type` keeps the exact bytes the upstream sent, and `Content-Encoding` travels with a compressed body.
- Upstream status 400 or above, a SOAP fault on HTTP 500 included: Mule's default success-status validator fails the flow, which declares no exception strategy, so the platform default applies (SC-1 `exception/GlobalExceptionHandler`, AAP 0.4.7):
  - Status `500`, `Content-Type: application/json`.
  - Body `{ "message": "Response code <status> mapped as failure. Message payload is of type: BufferInputStream" }` (`SoapProxyService.VALIDATOR_FAILURE_MESSAGE`).
  - The validator fails before the second `copy-headers`, so no upstream header is copied.
- Upstream timeout or connection failure: `500`, `application/json`, `{ "message": "I/O error: <reason>" }`, never retried. Examples are `I/O error: Read timed out` and `I/O error: Connection refused`. The message never names the upstream URL, host or address.

WSDL publication (SC-11 `config/WsdlQueryPublisher`, AAP 0.4.5):

- `GET /?wsdl` returns the WSDL named by `proxy.wsdl-location`, as `cxf:proxy-service wsdlLocation` published it:
  - Every `soap:address` and `soap12:address` `location` is rewritten to the request URL, for example `http://localhost:8081/`.
  - The document is served with `Content-Type: text/xml;charset=UTF-8` behind `<?xml version="1.0" encoding="UTF-8"?>`.
- Each schema the WSDL imports or includes, directly or transitively, is served at `GET /?xsd=<location as written>`, CXF's form. Every `schemaLocation` is rewritten to `<request URL>?xsd=<location>`.
- An absolute `http` or `https` schema location is published under the key `./<that URL>`, which `ShopServiceClient.wsdlResource()` resolves back to the URL. The whole schema closure is therefore served through the proxy.
- The WSDL and its schemas are read once at start-up, as CXF read them at deployment.
  - Each `http` or `https` read is one `GET` under `proxy.response-timeout-ms`, never sent twice.
  - A WSDL or schema that cannot be read or parsed fails start-up with an `IllegalStateException`. It fails at once on a refused connection, and once `proxy.response-timeout-ms` (10 s) expires on a host that does not answer.
  - Point `proxy.wsdl-location` at a reachable copy when predic8 is unreachable. `http:`, `https:`, `classpath:` and `file:` locations all work.
- Answering `?wsdl` and `?xsd=` makes no upstream call. Every other request, `POST /?wsdl` included, continues to the proxy.

### Tier 2A verification points

Each item is a plan default reproducing Mule 3.8 platform behaviour the flow does not spell out. A captured fixture of the original governs, and the Java code changes to match it.

1. Error responses: an upstream status of 400 or above, a timeout and a connection failure are answered with SC-1's `500` JSON shown above. CXF may instead have answered with a `soap:Server` fault envelope. Plan default: the JSON `500`.
2. Upstream SOAP 1.1 defaults of `cxf:proxy-client`. Plan default: `Content-Type: text/xml; charset=UTF-8` sent in place of any `Content-Type` that is not `text/xml` or `application/soap+xml`, and `SOAPAction: ""` added to a SOAP 1.1 request that carries none.
3. Bodies that are not SOAP envelopes (malformed XML, an empty body, another root element) are forwarded unchanged, where CXF parsed the envelope first and may have rejected it. Plan default: forward as received.
4. Every request that is not a `GET /?wsdl` or `GET /?xsd=<key>` is forwarded as the one `POST`: any non-`GET` method, a `GET` without the query, and `POST /?wsdl`. Plan default: forward (scenario `get-root-without-wsdl-query`).
5. The `?xsd=` key for an absolute schema location is `./<URL>`. CXF's key form for such a location is unconfirmed. Plan default: `./<URL>`.
6. Every upstream status below 400 is answered `200`. Plan default: the status rewrite above.
7. The response is written with a fixed `Content-Length`, never chunked. Plan default: fixed length.

No payload validation is performed, matching the original: `validationEnabled` is not set on `cxf:proxy-service` (`soap-api-proxy.xml:7`).

## Configuration keys

Every key of `src/main/resources/application.yml`, each with the Java type its value binds to.

Status values:

- `default`: a non-secret value the original fixed, overridable with `--key=value` or the environment.
- `fixed`: a framework setting that reproduces the original listener. It is not a key of the original, and changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | port of the `http:listener-config` (`${http.port}`, `soap-api-proxy.xml:3`), bound to `server.port`. The original `mule-app.properties` is empty; its README and test use port 8081 and a `DynamicPort`. `HTTP_PORT` sets it from the environment |
| `proxy.upstream-url` | `String` | `http://www.predic8.com:8080/shop/ShopService` | new key | default | the original hard-coded `http:request-config host="www.predic8.com" port="8080"` (`:4`) with `http:request path="shop/ShopService"` (`:10`). Every envelope is posted to this URL verbatim, no path or query appended. It must be an absolute `http` or `https` URL with a host, or the application does not start |
| `proxy.wsdl-location` | `String` | `http://www.predic8.com:8080/shop/ShopService?wsdl` | new key | default | the original `cxf:proxy-service wsdlLocation` (`:7`), read at start-up and published at `GET /?wsdl`. Accepts `http:`, `https:`, `classpath:` and `file:` locations and plain paths. An unreachable or unparseable WSDL stops start-up |
| `proxy.response-timeout-ms` | `long` (milliseconds) | `10000` | new key | default | the Mule default `http:request` response timeout; bounds each post and each WSDL or schema read. It must be greater than zero, or the application does not start |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`:3`) |
| `spring.application.name` | `String` | `proxying-a-soap-api-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | no static-resource handling: unserved paths get SC-1's `No listener for endpoint: <path>` |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` is forwarded like every other method instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | form bodies are forwarded as raw bytes, not consumed |
| `spring.servlet.multipart.enabled` | `boolean` | `false` | none | fixed | multipart bodies are forwarded as raw bytes, never parsed |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint or whitelabel page: SC-1 answers unserved paths and failures itself |
| `logging.level.org.apache.hc` | `LogLevel` | `INFO` | none | fixed | the httpclient5 transport of posts and WSDL reads; below `INFO` it writes request and response headers and wire bytes |
| `logging.level.org.springframework.web.servlet.DispatcherServlet` | `LogLevel` | `WARN` | none | fixed | below `INFO` it writes the request line with its query and the response headers, and with `spring.mvc.log-request-details` the request headers and parameters too, consuming a form body. `WARN` also silences its two start-up `INFO` lines under a root `WARN` |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | fixed | below `INFO` it writes handled exception messages, which can quote upstream content |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | below `INFO` it writes handler arguments, the exception handed to the 500 advice among them |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | below `INFO` it writes the 500 body carrying that message |

- The original holds no credential: the caller's headers and envelope, any security header included, travel to the upstream as data. The project therefore has no secret, no `TODO` placeholder and no `src/test/resources/application-test.yml`. It has no `application-it.yml` either, because it is not a connector project.
- Original-to-new name map: `http.port` was the original's only placeholder and keeps its name. `proxy.upstream-url`, `proxy.wsdl-location` and `proxy.response-timeout-ms` are new keys for values the original hard-coded or left to the Mule default.
- The five `logging.level.*` pins keep the caller's `Authorization`, token headers, cookies, query and envelope, and the upstream's answer, out of the log whatever level the root logger has. The original logged none of them. `SensitiveLoggerPinsTest` checks the pins under a root `TRACE`.
- Test default: the surefire configuration of `pom.xml` sets the system property `proxy.wsdl-location=classpath:upstream/ShopService.wsdl`. It points every test context at `src/test/resources/upstream/ShopService.wsdl` and its `ShopService.xsd`, so `mvn test` needs no network. Both files are authored test stand-ins, not copies of predic8's published documents. Tests that point at WireMock override the key through `@DynamicPropertySource`.

## Mule-to-Java mapping

| Mule construct (`proxying-a-soap-api`) | Java construct (`com.mulesoft.examples.proxying_a_soap_api`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`soap-api-proxy.xml:3`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` |
| `http:listener path="/"` without `allowedMethods` (`:6`) | `controller/SoapProxyController`: `proxy`, `@RequestMapping("/")` without a method restriction, and `options` for `OPTIONS`; `config/PreflightForwardingConfig` (CORS pre-flights forwarded) |
| `cxf:proxy-service payload="envelope"` with `wsdlLocation` (`:7`) | the raw envelope bytes, read by `SoapProxyController` and passed through `service/SoapProxyService` unvalidated; `?wsdl` publication by SC-11 `config/WsdlQueryPublisher`, registered by `ProxyingASoapApiApplication.wsdlQueryPublisher` with the WSDL resource from `ShopServiceClient.wsdlResource()` |
| `flow-ref copy-headers` before and after the call; sub-flow `copy-headers` (`:8,11,13-21`) | SC-13 `mapper/ProxyHeaderMapper` (`toUpstream`, `toCaller`), copied from `proxying-a-rest-api-java` |
| `cxf:proxy-client payload="envelope"` (`:9`) | `SoapProxyService`: the SOAP 1.1 outbound defaults (`UPSTREAM_SOAP11_CONTENT_TYPE`, empty `SOAPAction`); the envelope bytes pass unchanged |
| `http:request-config` `www.predic8.com:8080` (`:4`) and `http:request method="POST" path="shop/ShopService"` (`:10`) | `client/ShopServiceClient.post`: `RestClient` over Apache HttpClient 5, HTTP/1.1, URL `proxy.upstream-url`, timeout `proxy.response-timeout-ms` |
| The requester's default success-status validator | `SoapProxyService.forward`: an upstream status of 400 or above throws `IllegalStateException` with `VALIDATOR_FAILURE_MESSAGE` |
| Default exception strategy | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| No `logger` in the flow | no log line per proxied request; `WsdlQueryPublisher` logs a served `?wsdl` or `?xsd=` at `DEBUG` with its registration's identity only, never the request's query, path or URL |
| `mule-deploy.properties` (loads `soap-api-proxy.xml`) | `ProxyingASoapApiApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the `${...}` placeholder of the flow | `src/main/resources/application.yml` |
| `ProxyingSoapApiIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, live predic8) | `ProxyingSoapApiTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`, WireMock in place of predic8) |

## Behaviours stubbed or externalized

- predic8: production calls go to `proxy.upstream-url`, and the WSDL is read from `proxy.wsdl-location`. Every test points the URL at a local WireMock server and the WSDL at the authored stand-in `classpath:upstream/ShopService.wsdl`, so no test needs the network.
- No behaviour of the flow is dropped or stubbed. The Tier 2A verification points above are the only plan defaults. Studio metadata (`mule-project.xml`), `mule-deploy.properties` and `log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/proxying-a-soap-api-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081, upstream and WSDL at www.predic8.com:8080
java -jar target/proxying-a-soap-api-java-1.0.0-SNAPSHOT.jar
```

With the defaults the application reads the WSDL from `www.predic8.com:8080` at start-up and does not start when that host is unreachable. Override the keys as arguments, for example:

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--proxy.upstream-url=http://localhost:9090/shop/ShopService --proxy.wsdl-location=file:src/test/resources/upstream/ShopService.wsdl"
java -jar target/proxying-a-soap-api-java-1.0.0-SNAPSHOT.jar --http.port=9091 --proxy.wsdl-location=file:src/test/resources/upstream/ShopService.wsdl
```

To run without predic8, serve a stand-in upstream with WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the tests use) on port 9090 and start the proxy with the first command above:

```sh
java -jar wiremock-standalone-3.13.2.jar --port 9090 &
curl -s -X POST http://localhost:9090/__admin/mappings -d '{"request": {"method": "POST", "url": "/shop/ShopService"}, "response": {"status": 200, "headers": {"Content-Type": "text/xml;charset=UTF-8"}, "body": "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><ns2:createResponse xmlns:ns2=\"http://predic8.com/wsdl/shop/1/\"><cartId>1001</cartId></ns2:createResponse></soap:Body></soap:Envelope>"}}'
```

Expected exchanges against such a stand-in. The `Date` value varies, and `Matched-Stub-Id` is WireMock's own response header, copied back like every other upstream header:

```console
$ curl -i -H 'Content-Type: text/xml; charset=UTF-8' --data-binary @src/test/resources/message.xml http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/xml;charset=UTF-8
Content-Length: 215
Matched-Stub-Id: <WireMock stub id>
Date: <current date>

<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:createResponse xmlns:ns2="http://predic8.com/wsdl/shop/1/"><cartId>1001</cartId></ns2:createResponse></soap:Body></soap:Envelope>

$ curl -i http://localhost:8081/other
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 32
Date: <current date>

No listener for endpoint: /other
```

The upstream receives that envelope as `POST /shop/ShopService` with `Content-Type: text/xml; charset=UTF-8` and `SOAPAction: ""`. An upstream that answers 500, a SOAP fault for example, and an upstream that refuses the connection give:

```console
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 99
Date: <current date>

{ "message": "Response code 500 mapped as failure. Message payload is of type: BufferInputStream" }

HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 46
Date: <current date>

{ "message": "I/O error: Connection refused" }
```

`curl -i 'http://localhost:8081/?wsdl'` returns the WSDL with `<soap:address location="http://localhost:8081/"/>` and `schemaLocation="http://localhost:8081/?xsd=ShopService.xsd"`.

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2). The project uses neither Spring WS nor CXF, so it sets no `spring-ws.version`.

## Tests

| Test class | What it asserts |
|---|---|
| `ProxyingSoapApiTest` | the ported `ProxyingSoapApiIT` (`proxying-a-soap-api/src/test/java/org/mule/examples/ProxyingSoapApiIT.java:52-64`) against WireMock in place of predic8: `message.xml` POSTed to `/` travels through the whole proxy and the answer is not null, has a non-empty body (the original's `NullPayload` check) and contains `<cartId>` and `</cartId>` |
| `controller/SoapProxyControllerTest` | on the wire through ST-1, the whole application on Undertow in front of WireMock. Happy path: the original test's envelope forwarded byte for byte and the `<cartId>` envelope returned byte for byte. Request leg: SOAP `Content-Type` kept, any other replaced by `text/xml; charset=UTF-8`, `SOAPAction` defaulted to `""` or kept, `Authorization` and custom headers forwarded, `Host`, `Connection`, `MULE_*` and `http.*` not copied, the query dropped, `PUT`, `DELETE`, `PATCH`, `GET`, `OPTIONS` and `TRACE` each sent as the one `POST`, `Expect: 100-continue` honoured. Response leg: headers copied back filtered, upstream 201, 202 and 299 answered 200, a gzip body passed through undecoded with `Content-Encoding`. SC-11: `GET /?wsdl` and `GET /?xsd=ShopService.xsd` served with rewritten references and no upstream call. Errors: an upstream 500 SOAP fault and a 404 each answered 500 JSON without upstream headers; a slow upstream answered 500 `I/O error: Read timed out` once, without a retry; a refused connection answered 500 `I/O error: Connection refused`; `/other`, `/x/` and `/shop/ShopService` answered with the no-listener 404 |
| `service/SoapProxyServiceTest` | the flow order with a mocked client and a spy on the real SC-13 mapper: headers copied, one post, response headers copied, in that order; the body bytes unchanged; exactly one SOAP `Content-Type` upstream; exactly one `SOAPAction` for SOAP 1.1 and none added for SOAP 1.2; transport and Mule properties removed; the validator constants; upstream 200, 302 and 399 answered 200 with the unparsed body and the copied headers; every status from 400 failing with the validator message; an empty upstream body answered with empty bytes; a client failure propagated unchanged and never retried |
| `client/ShopServiceClientTest` | the `http:request` contract against WireMock and raw loopback upstreams: invalid `proxy.*` settings refused at construction without quoting the URL; one HTTP/1.1 `POST` with the envelope bytes and headers unchanged, `Expect` and `Upgrade` included; every upstream status returned as a value; redirects followed with the caller's credentials kept on the upstream's origin only; a timeout, a stalled body in either direction and a refused connection each failing once as `ResourceAccessException` without a retry or a location in its message; `wsdlResource` reading an `http` WSDL and its schemas under the same timeout, absolute schema locations replaced by keys that resolve back, and other locations through the `ResourceLoader` |
| `mapper/ProxyHeaderMapperTest` | SC-13: the six removal rules matched ignoring case in both directions, every other header (`Authorization`, `httpfoo`, `X-Mule-Id`) copied with its values in order, inputs never modified |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one bounded ERROR log line per unhandled exception, naming the matched route and never the request path |
| `config/PreflightForwardingConfigTest` | a CORS pre-flight to `/` reaches the upstream as the one `POST` with its `Origin` and `Access-Control-Request-*` headers, and the caller receives no `Access-Control-*` header the upstream did not send; a declared CORS configuration would still be processed by Spring |
| `SensitiveLoggerPinsTest` | under `logging.level.root=TRACE`, Spring Boot's `trace` mode and `spring.mvc.log-request-details`, the caller's `Authorization`, token header, cookie and WS-Security envelope reach the upstream unchanged, while no credential, token, query, envelope or upstream content reaches the log, the start-up WSDL read included |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of the reference implementation with only its `package` line changed, registered in the root [`DECISIONS.md`](../../DECISIONS.md) (AAP 0.4.4):

| ID | Files in this project | Reference copy |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`, test `exception/GlobalExceptionHandlerTest` | `hello-world-java` |
| SC-11 (`WsdlQueryPublisher` only) | `config/WsdlQueryPublisher` | `foreach-processing-and-choice-routing-java` |
| SC-13 | `mapper/ProxyHeaderMapper`, test `mapper/ProxyHeaderMapperTest` | `proxying-a-rest-api-java` |
| ST-1 | `support/HttpTestClient` (test) | `hello-world-java` |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |

The other SC-11 classes (`SoapEnvelopeStyleInterceptor`, `SoapFaultConfig`, `SoapListenerBoundaryAdapter`) are not carried, because the proxy has no Spring WS endpoint whose envelopes or faults they would shape. `config/PreflightForwardingConfig` is specific to the two proxy projects (`DECISIONS.md`).

## Fixture parity (ST-2)

`FixtureParityTest` replays every fixture `src/test/resources/fixtures/<scenario>.json` against this project, with WireMock serving the recorded ShopService exchanges, so replay never contacts predic8. `src/test/resources/fixtures/SCENARIOS.md` lists the scenarios, the fixture format, the comparison rules and the capture procedure for the original. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-create-cart` | `http` | `deterministic` | `POST /`, header `Content-Type: text/xml; charset=UTF-8`, body = the bytes of `message.xml` | `UNVERIFIED-uncaptured` |
| `post-root-upstream-fault` | `http` | `deterministic` | `POST /`, header `Content-Type: text/xml; charset=UTF-8`, an envelope whose body element `ns:unknownOperation` names no ShopService operation | `UNVERIFIED-uncaptured` |
| `get-root-without-wsdl-query` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `get-root-wsdl` | `http` | `nondeterministic` | `GET /?wsdl`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /other`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable`. `get-root-wsdl` is its one `nondeterministic` scenario, because the published WSDL carries the request's host and port in `soap:address`. Once captured it is compared for status, reason and headers exactly and for body structure only, and is reported `UNVERIFIED — nondeterministic output`, never as byte-for-byte evidence.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| proxying-a-soap-api-java | proxying-a-soap-api | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 306 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 306 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 54 of 54 lines (`service/SoapProxyService` 30, `mapper/ProxyHeaderMapper` 24). Total project LINE coverage: 92.8% (823 of 887 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback:

- `ProxyingSoapApiTest` carries the original committed assertions with their comparison semantics: a non-null, non-empty answer to the posted `message.xml` that contains `<cartId>` and `</cartId>`.
- The controller, service, client and mapper tests cover every reachable branch of the flow.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the Mule 3.8 platform defaults, not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests:
  - the WSDL publication at `?wsdl` and `?xsd=` (`get-root-wsdl`);
  - an upstream status of 400 or above answered 500 (`post-root-upstream-fault`);
  - a request without an envelope forwarded as the one `POST` (`get-root-without-wsdl-query`);
  - an unknown path answered with the no-listener 404 (`get-unknown-path`);
  - header stripping in both directions;
  - a timeout or connection failure answered 500.
- Unverified scenarios, until captured: `post-root-create-cart`, `post-root-upstream-fault`, `get-root-without-wsdl-query`, `get-root-wsdl` (`nondeterministic`, so it stays unverified even once captured, unless the escalation of AAP 0.8.8 settles it), `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `proxying-a-soap-api/`; `NOTICE` carries the MuleSoft attribution.
