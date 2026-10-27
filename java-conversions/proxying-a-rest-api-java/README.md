# proxying-a-rest-api-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`proxying-a-rest-api`](../../proxying-a-rest-api/) (category General). The original folder `proxying-a-rest-api/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

Applications send their requests to the proxy, which calls the real API and hands its answer back. The original uses the public Box REST API (`https://api.box.com:443`) as the stand-in for any REST API you might want to proxy. Its single flow `rest-api-proxy` (`proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:11-20`) receives every request on the `/*` listener, runs the `copy-headers` sub-flow, forwards the method, path and query with `http:request` (response left unparsed, `parseResponse="false"`), and runs `copy-headers` again on the response. The flow declares no logger and no exception strategy.

### Manual test against Box (the original's procedure)

These are the original README's steps, rewritten for the Java run command. They depend on Box's developer portal and OAuth endpoints as they were when the example was written, and were not re-verified for this conversion. The automated tests never contact Box.

1. Register an application in the Box developer portal, note its `client_id` and `client_secret`, and give it any `https` redirect URL (for example `https://www.google.com`).
2. Optionally create the client key store the original's `tls:context` loaded. Keep it outside the repository and never commit it:

   ```sh
   keytool -genkey -alias mule -keyalg RSA -keystore keystore.jks
   ```

3. Build and start the proxy from `java-conversions/proxying-a-rest-api-java/` (the `KEYSTORE_*` variables only when you created the key store):

   ```sh
   mvn -B clean install
   export KEYSTORE_PATH=/absolute/path/to/keystore.jks KEYSTORE_KEY='<store password>' KEYSTORE_PASSWORD='<key password>'
   java -jar target/proxying-a-rest-api-java-1.0.0-SNAPSHOT.jar
   ```

4. In a browser, open `http://localhost:8081/oauth2/authorize?response_type=code&client_id=<CLIENT_ID>` and log in to Box. Prepare the next step before you click **Grant access to Box**: the code Box issues expires after about 30 seconds.
5. Grant access. The browser is sent to your redirect URL with an extra `code=<CODE>` parameter. Exchange it through the proxy:

   ```sh
   curl -X POST http://localhost:8081/oauth2/token \
     -d grant_type=authorization_code -d code=<CODE> -d client_id=<CLIENT_ID> -d client_secret=<CLIENT_SECRET>
   ```

6. Copy `access_token` from the JSON answer (it lasts about an hour) and call Box through the proxy with it:

   ```sh
   curl -i -H 'Authorization: Bearer <access_token>' http://localhost:8081/2.0/folders/0
   ```

The original README also says the proxy passes the API's own error statuses back to the application. The flow does not do that: it sets no flag that disables the requester's success-status validator, so an upstream status of 400 or above fails the flow. This conversion follows the flow (next section).

## Endpoints and triggers

| Path | Methods | Host | Port | Forwarded to | Implemented by | Source |
|---|---|---|---|---|---|---|
| `/**`, every path (`/` and `/error` included) | every method: the listener declares no `allowedMethods`, so `HEAD`, `OPTIONS`, `TRACE`, `PATCH`, CORS pre-flights and extension methods such as `PROPFIND` are forwarded too | `0.0.0.0` | `http.port`, default `8081` | `${proxy.upstream-url}` (default `https://api.box.com:443`) + the same raw path + `?` and the same raw query when there is one | `controller/RestProxyController` → `service/RestProxyService` → `client/BoxProxyClient` | `proxying-a-rest-api.xml:3,12-18` |

The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Behaviour carried from the original

Request leg:

- The caller's method, path and query reach the upstream byte for byte, percent-encoding included (`a%20b`, `+` and `%2F` stay as sent). Characters RFC 3986 does not allow in a target, which the original listener accepted (a raw `|`, `{`, `"`, a `%` not followed by two hexadecimal digits and the like), are percent-encoded by `config/RequestTargetEscapingFilter`, so the upstream receives a valid URI (`/a%zz` arrives as `/a%25zz`). An absolute-form target (`http://host/x?q`) contributes only its path and query, whatever host it names.
- The body is forwarded unchanged and unparsed (form and multipart bodies included) for every method except `GET`, `HEAD` and `OPTIONS` (Mule `sendBodyMode` AUTO) and `TRACE`, `CONNECT` and `PRI`, whose payload the Mule requester's Grizzly client never sent; for those the caller's body bytes are dropped. A body up to 64 KiB is sent with its exact `Content-Length`; a larger one is streamed with the caller's declared length, or chunked when the caller declared none.
- Headers are copied by SC-13 `mapper/ProxyHeaderMapper.toUpstream`: every header except `Host`, `Content-Length`, `Connection`, `Transfer-Encoding` and every name starting with `http.` or `MULE_`, all matched ignoring case, as the `copy-headers` sub-flow removed them (`proxying-a-rest-api.xml:22-30`). `Expect` and `Upgrade` are forwarded like any other header.
- The caller's `Authorization` header reaches the upstream unchanged (AAP 0.4.1). The proxy adds no authentication of its own, and no credential of the caller is logged.

Upstream call (`client/BoxProxyClient`):

- `RestClient` over Apache HttpClient 5 (HTTP/1.1), one pooled client shared by every request, connections without a limit (Mule's default `maxConnections` -1).
- Redirects are followed, as the Mule 3.8 requester did by default. A redirect from `https` to `http` is not followed and is returned as received. Once a redirect leaves the original origin, `Authorization`, `Cookie`, `Origin`, `Referer` and `Host` are no longer sent. A redirect answering a streamed body (over 64 KiB) is returned unfollowed, so the caller receives 200 (Tier 2A verification point).
- `proxy.response-timeout-ms` (10 s, the Mule default response timeout) bounds the connect, the TLS handshake, every body write and every wait for response bytes. Nothing is ever retried, re-sent or re-authenticated.
- No content compression, cookie store or authentication cache: the response bytes, `Content-Encoding` included, come back as Box sent them, and no state passes from one caller to the next.

Response leg:

- The body bytes are returned unparsed. The upstream response headers are copied back by `ProxyHeaderMapper.toCaller` under the same removal rule, and `Content-Type` keeps the exact bytes the upstream sent.
- Status rewrite: the second `copy-headers` removes `http.status` with the other `http.*` properties, so every upstream status below 400 (a 201, a 304 or a 3xx the client did not follow included) is answered **200** (Tier 2A verification point).
- Upstream status 400 or above: Mule's default success-status validator fails the flow, which declares no exception strategy, so the platform default applies (SC-1 `exception/GlobalExceptionHandler`, AAP 0.4.7): `500`, `Content-Type: application/json`, body `{ "message": "Response code <status> mapped as failure. Message payload is of type: BufferInputStream" }` (`RestProxyService.VALIDATOR_FAILURE_MESSAGE`). No upstream header is copied on this path. Tier 2A verification point: a captured fixture governs the bytes.
- Upstream timeout or connection failure: `500`, `application/json`, `{ "message": "<exception message>" }`, never retried. The message is the client's, for example `I/O error on GET request for "<upstream URL>": Connect to <upstream> failed: Connection refused`.
- Request targets Undertow hands over without a leading `/` (the asterisk form with any method, such as `OPTIONS *`; `CONNECT host:port`; targets such as `@host/x` or `http:@host/x`) never matched the `/*` listener and get the listener's `404`, `text/plain`, `No listener for endpoint: <path>` (`config/OriginFormTargetGuard`); nothing is forwarded.

Known listener differences from the Mule (Grizzly) listener, each a Tier 2A verification point:

- The response is written with a fixed `Content-Length`, never chunked.
- A form body is forwarded as the bytes received. The Mule 3.8 listener read it as a parameter map that `http:request` encoded again, so the original may have re-encoded it.
- Multipart forwarding is byte for byte here; the original's handling is unconfirmed.
- A header value reaches the upstream without its leading and trailing SP and HTAB, and an obs-fold in it as one SP; every other byte arrives as sent, inner runs of SP and HTAB included, so a Digest `username="John  Smith"`, `a<HTAB>b` and `   Bearer   t1  ` (forwarded as `Bearer   t1`) reach it as the caller wrote them. Undertow 2.3.17's parser turns every such run, a single HTAB included, into one SP, with no option to turn that off; the request-framing guard records each request head's fields before that parser runs (`config/SentHeaderFields`), and `config/ForwardedRequestHeaders` forwards the recorded value wherever it normalises to Undertow's (outer whitespace dropped, every inner run one SP), Undertow's value otherwise.
- A request head holding a CR not followed by LF, an LF not preceded by CR, a control character other than HTAB in a header value, whitespace before a header colon, a continuation line before the first header, more than `server.max-http-request-header-size` bytes (8 KB), or ambiguous body framing (`Transfer-Encoding` together with `Content-Length`, either of them twice, a `Content-Length` that is not a decimal number) is answered with Undertow's empty `400 Bad Request` and `Connection: close` by the request-framing guard (`config/RequestFramingGuardConfig`) before Undertow parses it, so it is not forwarded; requests before it on the same connection are answered first. A chunked body that breaks the chunked syntax ends the request at the violation.
- Header bytes travel as ISO-8859-1 in both directions, one byte per character, so obs-text such as UTF-8 passes unchanged. Several `Cookie` headers travel upstream as one `Cookie` header joined with `; `.

## Configuration keys

All keys live in `src/main/resources/application.yml`. Secrets have no value: each is written `key: ${ENV_NAME:}` with a `# TODO` comment (AAP 0.4.6), so supply it through the named environment variable or a `--key=value` argument.

| Key | Default | Secret / TODO | Original name | Notes |
|---|---|---|---|---|
| `http.port` | `8081` | no | `http.port` (unchanged) | bound to `server.port` (`server.port: ${http.port:8081}`); the original `mule-app.properties` is empty, and its README uses port 8081. `HTTP_PORT` sets it from the environment |
| `proxy.upstream-url` | `https://api.box.com:443` | no | new key | the original fixed `host="api.box.com" port="443" protocol="HTTPS"` in `HTTP_Request_Configuration` (`proxying-a-rest-api.xml:5`). The caller's raw path and query are appended to it (trailing `/` characters ignored). It must be an absolute `http` or `https` URL with a host and without user information, or the application does not start |
| `proxy.response-timeout-ms` | `10000` | no | new key | the Mule default response timeout; must be greater than zero |
| `keystore.path` | empty (`${KEYSTORE_PATH:}`) | TODO: path of the key store created with `keytool` | new key | the original fixed `path="keystore.jks"` on the application classpath. Accepts `classpath:` and `file:` locations; a bare path is read as a file when one exists there, otherwise from the classpath. Empty: JVM default TLS context, no client key |
| `keystore.key` | empty (`${KEYSTORE_KEY:}`) | TODO secret | `keystore.key` (unchanged) | the **key store (store) password**: Mule `tls:key-store password="${keystore.key}"` |
| `keystore.password` | empty (`${KEYSTORE_PASSWORD:}`) | TODO secret | `keystore.password` (unchanged) | the **private-key password**: Mule `tls:key-store keyPassword="${keystore.password}"`; empty means the store password is used, which fits a PKCS12 store as `keytool` creates on Java 17 |

- The two passwords keep the original's swapped naming on purpose: `keystore.key` is the store password and `keystore.password` the key password (`proxying-a-rest-api.xml:7`). A key store that does not load with them stops the application at start-up with `Cannot load keystore from keystore.path=<path>`; no password is ever printed.
- Trust: the JVM default trust store, because the original declared no `tls:trust-store`.
- Never commit the key store or its passwords; keep them outside the repository and pass them as `KEYSTORE_PATH`, `KEYSTORE_KEY` and `KEYSTORE_PASSWORD`.
- Test values live in `src/test/resources/application-test.yml` (Spring profile `test`), in place of the uncommitted `mule.test.properties` the original test read: `keystore.path` blank, `keystore.key` `test-store-password`, `keystore.password` `test-key-password`, and the Box test-data keys `box.user` (`mule.tester@example.com`), `box.id` (`test-client-id`) and `box.secret` (`test-client-secret`). None is a real credential. `box.password` and `phantomjs.binary` are dropped, because WireMock replaces Box and no browser is driven. `proxy.upstream-url` is absent there: each test points it at its own WireMock server.
- The project has no `application-it.yml`: it is not a connector project.

Fixed settings in `application.yml` and `pom.xml`, none of them a configuration key of the original:

| Setting | Value | Why |
|---|---|---|
| `server.address` | `0.0.0.0` | `http:listener-config host="0.0.0.0"` (`proxying-a-rest-api.xml:3`) |
| `server.undertow.decode-url` | `false` | the raw target reaches the proxy undecoded, so the caller's percent-encoding is forwarded as sent |
| `server.undertow.options.server.ALLOW_UNESCAPED_CHARACTERS_IN_URL` | `true` | the original listener accepted targets with raw `\|`, `{`, `}`, `^` and the like; Undertow would answer them with 400 |
| `server.undertow.options.server.MAX_ENTITY_SIZE` | `-1` | no request body size limit, as on the original listener (Undertow 2.3.17 sets none by default; later releases cap a body at 2 MB) |
| `spring.web.resources.add-mappings` | `false` | no static-resource handler competes with the `/**` proxy mapping |
| `spring.mvc.dispatch-trace-request` | `true` | `TRACE` is forwarded like every other method instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `false` | `PUT`, `PATCH` and `DELETE` form bodies are forwarded, not consumed |
| `spring.servlet.multipart.enabled` | `false` | `multipart/form-data` is forwarded as raw bytes, never parsed |
| `spring.autoconfigure.exclude` | `ErrorMvcAutoConfiguration` | no Boot `/error` endpoint: `/error` is proxied like any other path |
| `logging.level` pins (`org.apache.hc`, `DispatcherServlet`, `ExceptionHandlerExceptionResolver`, `HttpEntityMethodProcessor`) | `INFO` | below `INFO` these loggers write the caller's `Authorization`, cookies, query and body, which the original never logged; `config/SensitiveLogGuard` also denies their `DEBUG` and `TRACE` events whatever level is configured |
| request-framing guard (`config/RequestFramingGuardConfig`, `RequestFramingWorker`, `RequestFramingConduit`, `RequestFramingValidator`, `SentHeaderFields`, `ForwardedRequestHeaders`) | on every accepted connection; TLS (`server.ssl`) and HTTP/2 (`server.http2.enabled`) are refused at start-up | Undertow 2.3.17.Final, the version the Boot 3.2.12 BOM resolves, ends a header line at a bare CR or LF, so `X-Test: a<CR>Authorization: Bearer x` would reach the upstream as a second `Authorization` header; no Undertow option changes that. The guard checks each request head and its body framing on the raw bytes before Undertow parses them and answers a violation with Undertow's own empty `400`. It also records every released head's header fields as sent and attaches them to the request's exchange through the outermost handler of the deployment's initial chain, so the proxy forwards each value with the inner SP and HTAB runs Undertow's parser collapses to one SP. It reads plain HTTP/1.1, hence the refusal of TLS and HTTP/2. Because Undertow sees a head only once it is complete, its 60 s no-request timeout covers the whole head |

## Mule-to-Java mapping

| Mule construct (`proxying-a-rest-api`) | Java construct (`com.mulesoft.examples.proxying_a_rest_api`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`proxying-a-rest-api.xml:3`), default threading profile | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port`, running on the worker `config/RequestFramingGuardConfig` creates, which checks every request head before Undertow parses it; `config/ListenerWorkerThreadingConfig` gives that worker's pool the profile's one idle thread and 60 s idle time-out |
| `http:listener path="/*"` without `allowedMethods` (`:12`) | `controller/RestProxyController`: `proxy`, `@RequestMapping("/**")` without a method restriction, and `options` for `OPTIONS`; `config/ForwardedRequestHeaders` (header values as sent), `config/PreflightForwardingConfig` (CORS pre-flights forwarded), `config/RequestTargetEscapingFilter`, `config/OriginFormTargetGuard` |
| `flow-ref copy-headers` before and after the call; sub-flow `copy-headers` (`:13,19,22-30`) | SC-13 `mapper/ProxyHeaderMapper` (`toUpstream`, `toCaller`): the reference implementation, copied into `proxying-a-soap-api-java` |
| `http:request` with the inbound method, path and query params, `parseResponse="false"` (`:14-18`) | `service/RestProxyService.forward` → `client/BoxProxyClient.exchange` |
| `http:request-config` `api.box.com:443` HTTPS with `tls:context` / `tls:key-store` (`:5-9`) | `client/BoxProxyClient`: `RestClient` over Apache HttpClient 5, HTTP/1.1, redirects followed; base URL from `proxy.upstream-url`, key store from `keystore.*` |
| The requester's default success-status validator | `RestProxyService`: an upstream status of 400 or above throws `IllegalStateException` with `VALIDATOR_FAILURE_MESSAGE` |
| Default exception strategy | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| No `logger` in the flow | one `DEBUG` line per proxied request, `BoxProxyClient`'s `Proxying <method> request through rest-api-proxy`: the flow name as the fixed operation identifier and the method only when it is a standard one (`GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `TRACE`), `extension-method` otherwise, never the path, query, headers or body; `config/SensitiveLogGuard` and `config/SensitiveLogGuardInstaller` (registered in `META-INF/spring.factories`) keep library debug logging from recording the caller's credentials and content |
| `mule-deploy.properties` (loads `proxying-a-rest-api.xml`) | `ProxyingARestApiApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the `${...}` placeholders of the flow | `src/main/resources/application.yml` |
| `ProxyingRestApiIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, PhantomJS) | `ProxyingRestApiTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, ST-1 `support/HttpTestClient`, WireMock in place of Box) |

## Behaviours stubbed or externalized

- Box: production calls go to `proxy.upstream-url`; every test points it at a local WireMock server, so no test needs a Box account or the network.
- The key store and its two passwords are externalized (`keystore.*`); the original README told users to paste the passwords into the flow.
- The original test's browser login (PhantomJS) is replaced by a proxied `GET` of a stubbed authorize page (see Tests).
- No behaviour of the flow is dropped; the known listener differences listed above are the only deviations. Studio metadata (`mule-project.xml`) and `log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/proxying-a-rest-api-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081, upstream https://api.box.com:443
java -jar target/proxying-a-rest-api-java-1.0.0-SNAPSHOT.jar
```

Pass the key store either through the `KEYSTORE_*` environment variables or as arguments, for example:

```sh
mvn spring-boot:run -Dspring-boot.run.arguments="--keystore.path=/absolute/path/to/keystore.jks --keystore.key=<store password> --keystore.password=<key password>"
java -jar target/proxying-a-rest-api-java-1.0.0-SNAPSHOT.jar --http.port=9090 --proxy.upstream-url=https://api.example.com:443
```

Expected exchange when the upstream answers 401 (the `Date` value varies):

```console
$ curl -i http://localhost:8081/2.0/folders/0
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 99
Date: <current date>

{ "message": "Response code 401 mapped as failure. Message payload is of type: BufferInputStream" }
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ProxyingRestApiTest` | the ported `ProxyingRestApiIT` (`proxying-a-rest-api/src/test/java/org/mule/examples/ProxyingRestApiIT.java:105-145`) against WireMock in place of Box: a proxied `GET /oauth2/authorize?response_type=code&client_id=<box.id>` returns the stubbed login page, whose consent redirect carries the code (replacing the PhantomJS login); the Box token form `POST /oauth2/token` reaches the upstream byte for byte; `GET /2.0/folders/0` with `Authorization: Bearer <token>` returns a non-empty body whose `type` is `folder` and whose `owned_by.login` equals `box.user`, and the header reached the upstream unchanged |
| `controller/RestProxyControllerTest` | on the wire through ST-1: response bytes and `Content-Type` unchanged; `POST`, `PUT`, `PATCH` and `DELETE`, multipart and form bodies forwarded byte for byte; `OPTIONS` and `HEAD` without a body; `/` and `/error` proxied; an upstream 201 answered 200; header removal both ways with `Authorization` forwarded; an upstream 404, an unreachable upstream and an upstream slower than the timeout each answered 500 `application/json`, the timeout without a retry |
| `service/RestProxyServiceTest` | the flow order with a mocked client and the real SC-13 mapper: the body stream passed unread for the methods that send one and dropped for the others; raw path and query untouched; every status below 400 answered 200 with the unparsed body; every status from 400 failing with the validator message; headers filtered both ways; a client failure propagated unchanged and never retried |
| `client/BoxProxyClientTest` | the `http:request` contract against WireMock: raw path and query sent verbatim, headers and body bytes unchanged with the exact `Content-Length`, no `Content-Type` added, upstream error statuses returned as values, redirects followed, a timeout failing once within the limit, the swapped key store passwords, a missing or wrongly unlocked key store failing construction with a message naming `keystore.path`, a blank `keystore.path` using the default TLS context, paths without a leading `/` and URLs with user information refused |
| `client/BoxProxyClientTransportTest` | the transport on loopback upstreams: stalled exchanges failing within the timeout without retry, `Expect` and `Upgrade` forwarded, header bytes and control-character substitution, body framing by method, `TRACE` body bytes never sent, the redirect header policy and the refused `https` → `http` redirect, pooled connections released on close, buffered and streamed bodies |
| `mapper/ProxyHeaderMapperTest` | SC-13: the six removal rules matched ignoring case in both directions, every other header (`Authorization`, `httpfoo`, `X-Mule-Id`) copied with its values in order, inputs never modified |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace |
| `ListenerRequestParsingTest` | on Undertow 2.3.17 with the request-framing guard: a bare CR, LF or other control character in a header value, a CR before the final LF, whitespace before a colon, a header line without a colon, a continuation line before the first header, a bare LF ending the request line, `Transfer-Encoding` with `Content-Length` and two `Content-Length` values each answered `HTTP/1.1 400 Bad Request` with an empty body and nothing forwarded; a malformed request pipelined behind a well-formed one (without a body, with a `Content-Length` body, with a chunked body carrying an extension and a trailer) answered 400 after the first is forwarded and answered 200; a head written in 3-byte fragments forwarded whole, or refused at its bare CR; an obs-fold line joined to its value with one SP; a request body larger than 2 MB forwarded, chunked and with `Content-Length` |
| `ListenerHeaderWhitespaceTest` | the header-value fidelity contract, byte for byte at a raw upstream: values with every mix of SP and HTAB (`a<HTAB>b`, `a<HTAB><HTAB>b`, `a <HTAB>b`, `a<HTAB> b`, `a    b`), Digest `username="John  Smith"` and `username="John<HTAB>Smith"` in `Authorization` and a single `Cookie` line arrive as sent; `   Bearer   t1  ` arrives as `Bearer   t1`; an obs-fold arrives as one SP; two pipelined requests on one connection each forward their own values; a request after `OPTIONS *`, which the listener answers with 404, on the same keep-alive connection forwards its own values; several `Cookie` lines arrive as one value joined with `; ` |
| `config/RequestTargetEscapingFilterTest` | disallowed target characters percent-encoded per component, an invalid `%` escape forwarded as `%25`, valid targets forwarded byte for byte |
| `config/OriginFormTargetGuardTest` | targets Undertow hands over without a leading `/` answered with the no-listener 404 and never forwarded; origin-form targets forwarded as sent, and absolute-form targets as their path and query only, to the configured upstream whatever host, user information or `Host` they name; the guard registered inside the handler that attaches each request's header fields as sent |
| `config/RequestFramingValidatorTest` | the guard's rules on byte streams: well-formed heads held until their last byte and released whole; every malformed head shape refused with nothing of it released, after well-formed messages too; `Content-Length`, chunked and identity framing releasing exactly the body; malformed chunked bodies stopped at the offending byte; the head limit; the same result for any fragmentation; each released head's header fields recorded as sent (inner SP and HTAB runs kept, outer ones dropped, an obs-fold as one SP), in stream order and for any fragmentation, never for a refused head or a trailer |
| `config/RequestFramingConduitTest` | the guard's conduit over an in-memory socket: heads held until complete, body and pipelined bytes, the 400 trigger in a read of its own, end of stream, scatter reads and file transfer through the guard, and readiness (`resumeReads`, `awaitReadable`, `isReadShutdown`) while bytes are buffered; released heads' fields queued in release order and each taken once |
| `config/RequestFramingGuardConfigTest` | the guarded worker set on the Undertow builder with Undertow's own worker options, the configured thread counts and the listener idle policy; the head limit from `server.max-http-request-header-size`; TLS and HTTP/2 refused; every created worker stopped on destroy; accepted connections carrying the guard; the server MXBean registration; each exchange of a guarded connection given the next released head's fields once, and none when the guard is not the connection's source conduit |
| `config/ForwardedRequestHeadersTest` | a recorded value replaces Undertow's only when it normalises to it as Undertow 2.3.17 normalises (outer SP and HTAB dropped, every inner run one SP), so joined `Cookie` lines and changed values keep Undertow's value; every header name and value position of the request kept, none added or dropped; a request without recorded fields copied as the container returns it |
| `config/PreflightForwardingConfigTest` | a CORS pre-flight forwarded as the `OPTIONS` request it is, the caller receiving only the upstream's `Access-Control-*` headers |
| `config/ListenerWorkerThreadingConfigTest` | one core worker thread and a 60 s keep-alive; concurrent requests still run in parallel; idle workers retired |
| `config/SensitiveLogGuardTest` | under root `TRACE` and under explicit levels for the guarded loggers, the caller's bearer token, cookie, query token and body reach the upstream but never the log; a path carrying an e-mail address and a token, sent as `GET` and as `PROPFIND`, reaches the upstream unchanged while `BoxProxyClient` logs only its fixed line, with `extension-method` in place of `PROPFIND` |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity

`FixtureParityTest` (ST-2, copied from `hello-world-java` with only its `package` line changed) replays every fixture `src/test/resources/fixtures/<scenario>.json` against this project, with WireMock serving the recorded Box exchanges, so replay never contacts Box. `src/test/resources/fixtures/SCENARIOS.md` lists the scenarios, the fixture format, the comparison rules and the capture procedure for the original. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-authorize-page-through-proxy` | `http` | `deterministic` | `GET /oauth2/authorize?response_type=code&client_id=<client_id>`, empty body | `UNVERIFIED-uncaptured` |
| `post-token-form-through-proxy` | `http` | `deterministic` | `POST /oauth2/token`, form body with `grant_type`, `code`, `client_id`, `client_secret` | `UNVERIFIED-uncaptured` |
| `get-folder-with-bearer-token` | `http` | `deterministic` | `GET /2.0/folders/0`, header `Authorization: Bearer <access_token>` | `UNVERIFIED-uncaptured` |
| `post-folder-upstream-created` | `http` | `deterministic` | `POST /2.0/folders`, JSON body, header `Authorization: Bearer <access_token>` | `UNVERIFIED-uncaptured` |
| `get-folder-without-token-upstream-401` | `http` | `deterministic` | `GET /2.0/folders/0`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`. The two OAuth scenarios carry live Box values (`client_id`, `client_secret`, `code`, issued tokens), so `SCENARIOS.md` keeps them uncaptured until the escalation of AAP 0.8.8 settles them.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| proxying-a-rest-api-java | proxying-a-rest-api | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 433 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 433 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 41 of 41 lines (`service/RestProxyService` 17, `mapper/ProxyHeaderMapper` 24). Total project LINE coverage: 95.8% (569 of 594 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as `DECISIONS.md` records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback:

- `ProxyingRestApiTest` carries the original committed assertions: a non-empty response to the bearer-token folder call, `type` = `folder` and `owned_by.login` = the configured user, reached through the proxied authorize and token calls.
- The controller, service, client and mapper tests cover every reachable branch of the flow.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the Mule 3.8 platform defaults, not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: an upstream status of 400 or above answered 500 (`get-folder-without-token-upstream-401`); the 200 status rewrite of a non-200 success (`post-folder-upstream-created`); header removal in both directions; body forwarding for methods other than `GET`; a timeout or connection failure answered 500.
- Unverified scenarios, until captured: `get-authorize-page-through-proxy`, `post-token-form-through-proxy`, `get-folder-with-bearer-token`, `post-folder-upstream-created`, `get-folder-without-token-upstream-401`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `proxying-a-rest-api/`.
