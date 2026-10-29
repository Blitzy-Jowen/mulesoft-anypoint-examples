# soap-webservice-security-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`soap-webservice-security`](../../soap-webservice-security/) (category General), functionally identical to it. The original folder `soap-webservice-security/` stays unchanged beside this project and is the Tier 2A capture source. Package `com.mulesoft.examples.soap_webservice_security`, main class `SoapWebserviceSecurityApplication`. Both listeners run on Undertow; the SOAP side uses Spring WS 4.1.4 over SAAJ and Jakarta JAXB, with WS-Security from WSS4J 4.0.1. No JAX-WS or CXF artefact is on the classpath: the `maven-enforcer-plugin` `bannedDependencies` rule fails the build at `validate` if one arrives, even transitively (AAP 0.3.2).

## Original example

A salutation service exposed six times, once unsecured and five times under increasing WS-Security: `greet("John")` answers `Hello John` on every variant, and only the security the caller must present differs. `mule-config.xml` declares the six `cxf:jaxws-service` flows on port 63081; `service-clients.xml` adds a browser-facing `/client` front on port 63080 whose `clientType` query parameter picks one of six matching secured clients. `mule-deploy.properties` loads both files. See the [original README](../../soap-webservice-security/README.md) for the background on each security profile.

## Endpoints and triggers

The project has no non-HTTP trigger. Both listeners bind `0.0.0.0` and run in one process, and neither declares `allowedMethods`, so every method token is accepted, extension methods such as `PROPFIND` included (`mule-config.xml:6`, `service-clients.xml:6`). Neither applied a CORS policy, so a CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) is answered exactly like a plain `OPTIONS` request to the same path, with no `Access-Control-*` or `Vary` header: on `/client` it runs the flow (`config/PreflightDispatchConfig`), on a SOAP path it is read as a message.

### SOAP listener — port 63081, base path `/services`

Spring WS's `MessageDispatcherServlet` is mapped at `spring.webservices.path: /services`; SC-10 routes only the six exact paths below to it. `controller/GreeterEndpoint` serves all six, and `config/PathScopedSecurityInterceptor` enforces each path's inbound action set before any endpoint is selected (`config/WebServiceConfig`, `SecurityFirstMessageDispatcher`), so an encrypted body is decrypted before its operation is chosen.

| Path | Original flow (`mule-config.xml`) | Required actions | Validation material |
|---|---|---|---|
| `/services/unsecure` | `UnsecureServiceFlow` (`:8-13`) | none | — |
| `/services/username` | `UsernameTokenServiceFlow` (`:15-26`) | `UsernameToken Timestamp` | `config/PasswordCallback` (`soap-security.users`) |
| `/services/signed` | `UsernameTokenSignedServiceFlow` (`:28-40`) | `UsernameToken Signature Timestamp` | `PasswordCallback`; signature crypto `wssecurity.properties` |
| `/services/encrypted` | `UsernameTokenEncryptedServiceFlow` (`:42-54`) | `UsernameToken Timestamp Encrypt` | `PasswordCallback`; decryption crypto `wssecurity.properties` |
| `/services/saml` | `SamlTokenServiceFlow` (`:56-69`) | `SAMLTokenUnsigned Timestamp` | `config/SAMLCustomValidator` |
| `/services/signedsaml` | `SignedSamlTokenServiceFlow` (`:71-85`) | `SAMLTokenUnsigned Signature` | `SAMLCustomValidator`; signature crypto `wssecurity.properties` |

The operation is the document/literal-wrapped `greet` of `http://security.example.mule.mulesoft.com/` (`model/soap/Greet`, `model/soap/GreetResponse`): request child `name`, response `greetResponse/name` = `Hello <name>` (`service/GreeterService`; a request without `name` gets `Hello null`, as the original did). The original README's exchange, unchanged:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
    <soap:Body>
        <ns2:greet xmlns:ns2="http://security.example.mule.mulesoft.com/">
            <name>John</name>
        </ns2:greet>
    </soap:Body>
</soap:Envelope>
```

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
    <soap:Body>
        <ns2:greetResponse xmlns:ns2="http://security.example.mule.mulesoft.com/">
            <name>Hello John</name>
        </ns2:greetResponse>
    </soap:Body>
</soap:Envelope>
```

On the wire the response has CXF's compact form: `soap:` prefix, no empty `Header`, JAXB `ns2:` prefix, no XML declaration, no whitespace between elements (SC-11 `config/SoapEnvelopeStyleInterceptor`), `Content-Type: text/xml;charset=utf-8`.

How a secured path checks a request (AAP 0.3.2):

- WSS4J's `WSSecurityEngine.processSecurityHeader` processes the `wsse:Security` header with the path's callback, crypto and SAML validator. The results are then matched "in any order" by WSS4J's own `WSHandler.checkReceiverResultsAnyOrder`, the check CXF 2.7's `WSS4JInInterceptor` applied.
- That check ignores signature-confirmation results, `BinarySecurityToken` results and encryption results that reference no data, so a certificate token never fails a request: a signed SAML request carrying a `DirectReference` certificate is accepted on `/services/signedsaml`, and a `/services/signed` request's signing token is ignored the same way.
- It fails closed: a missing or empty `wsse:Security` header, a required action without a result, or an action the path does not require is rejected. On `/services/unsecure` a `mustUnderstand` security header is not understood and gets CXF's MustUnderstand fault (table below), as CXF answered a service without WS-Security.
- Replays are detected per path, as CXF 2.7.18's recipient caches detected them: a path whose action set holds `UsernameToken` keeps a nonce cache, one holding `Timestamp` a timestamp cache, one holding a SAML token action a SAML one-time-use cache. A second presentation of a UsernameToken nonce on `/services/username`, `/services/signed` or `/services/encrypted`, or of a signature with its Timestamp on `/services/signed`, is rejected with `wsse:InvalidSecurity`; of concurrent identical copies exactly one is accepted. Repeated `/services/saml` and `/services/signedsaml` requests are accepted again, as the original accepted them: neither carries a nonce or a signature with a Timestamp, and the client's assertions have no `OneTimeUse` condition. WSS4J's freshness limits are unchanged. The only differences from the original, all for hostile input: the first sighting is reserved atomically, an identifier is kept for at least 360 seconds, and a cache holding 50,000 identifiers refuses new ones until entries expire (`PathScopedSecurityInterceptor.BoundedReplayCache`).
- Spring's `Wss4jSecurityInterceptor` is not used inbound, because its action-mismatch fault text differs from the original's.

### Faults and listener boundary (AAP 0.4.7)

Every fault is SOAP 1.1, sent with HTTP 500, in the envelope style above.

| Case | `faultcode` | `faultstring` | Enforced by |
|---|---|---|---|
| UsernameToken not authenticated, for example a wrong password | `wsse:FailedAuthentication` | `The security token could not be authenticated or authorized` | `PathScopedSecurityInterceptor` |
| Any other WS-Security failure: missing signature, wrong SAML issuer (rejected by `SAMLCustomValidator`), missing `wsse:Security` header, unexpected action, a replayed UsernameToken nonce or signature with its Timestamp | `wsse:InvalidSecurity` | `An error was discovered processing the <wsse:Security> header` | `PathScopedSecurityInterceptor` |
| A `mustUnderstand="1"` header block that nothing on the path understands, for example `wsse:Security` on `/services/unsecure` | `soap:MustUnderstand` | `MustUnderstand headers: [{<namespace>}<localName>] are not understood.`, for example `MustUnderstand headers: [{http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd}Security] are not understood.` | SC-11 `config/SoapFaultConfig` (`CxfStyleSoapMessageDispatcher`, extended by `WebServiceConfig.SecurityFirstMessageDispatcher`) |
| An exception escaping the endpoint | `soap:Server` | the exception message (CXF's `Fault occurred while processing.` when it has none; a `NullPointerException` the JVM raised counts as having none) | SC-11 `config/SoapFaultConfig` |
| A body root no endpoint maps, on a path whose policy it passes | `soap:Client` | `Message part {<namespace>}<localName> was not recognized.  (Does it exist in service WSDL?)` | SC-11 `config/SoapFaultConfig` |
| Malformed XML, an empty body, a body nested deeper than 100 elements (CXF's default `maxElementDepth`), a root other than `soap:Envelope`, or a non-POST request without `?wsdl` (read as a message, so its empty body fails to parse) | `soap:Client` | the parser's message, for example `Premature end of file.` | SC-11 `config/SoapListenerBoundaryAdapter` |

The original negative tests match these texts by substring (`security token`, `<wsse:Security> header`). The exact fault bytes are a Tier 2A verification point: when a captured fixture differs, the fixture governs and the code is changed to match.

### WSDL publication

`GET <path>?wsdl` on each of the six paths serves the authored `src/main/resources/wsdl/GreeterService.wsdl` (portType `Greeter`, binding `GreeterServiceSoapBinding`, service `GreeterService`, port `GreeterPort`) as `text/xml;charset=UTF-8`, with `soap:address` rewritten to the request URL, for example `http://localhost:63081/services/signed` (SC-11 `config/WsdlQueryPublisher`). The publisher answers before the servlet, so metadata stays anonymous on the secured paths. The WSDL is derived from the JAX-WS defaults CXF used for `serviceClass="com.mulesoft.mule.example.security.Greeter"` (`mule-config.xml:11`), not copied from a recorded original; once Tier 2A captures the original `?wsdl`, the captured file replaces it where they differ ([`DECISIONS.md`](../../DECISIONS.md) section 7).

### HTTP client front — port 63080, path `/client`

`controller/GreeterClientController` reads `name` and `clientType` from the raw query string (URL-decoded; a repeated name keeps its last value, as Mule's `http.query.params` did) and hands them to `client/SecureGreeterClient`, which reproduces the `SecurityClients` choice (`service-clients.xml:12-34`). Each type calls its path on the SOAP listener (`http://<soap-security.client.host>:<SOAP port>/services/<path>`, the original `HTTP_Request_Configuration`, `service-clients.xml:7`) through Spring WS with one securing `Wss4jSecurityInterceptor` holding the original outbound action set, and sends `SOAPAction: ""` as the CXF clients did.

| `clientType` (case-sensitive) | Calls | Outbound actions |
|---|---|---|
| `unsecure` | `/services/unsecure` | none |
| `usernameToken` | `/services/username` | `UsernameToken Timestamp`; user `soap-security.client.user` (`joe` in the original), password from `PasswordCallback` |
| `usernameTokenSigned` | `/services/signed` | `UsernameToken Signature Timestamp`; the same user, signature crypto `wssecurity.properties` |
| `usernameTokenEncrypted` | `/services/encrypted` | `UsernameToken Timestamp Encrypt`; the same user, encryption user `joe` (`soap-security.client.encryption-user`), encryption crypto `wssecurity.properties` |
| `samlToken` | `/services/saml` | `SAMLTokenUnsigned Timestamp`; `saml.properties` through `config/SAMLCallbackHandler` |
| `samlTokenSigned` | `/services/signedsaml` | `SAMLTokenSigned`; key identifier `DirectReference`, the same user, `saml.properties` |
| any other value, or none | no call | — |

| Outcome | Response |
|---|---|
| a listed type | `200 OK`, `Content-Type: text/plain`, body `Hello <name>` |
| any other or missing `clientType` | `200 OK`, `Content-Type: text/plain`, body `Client type is not supported` (`service-clients.xml:31-32`) |
| any failure: a SOAP fault, an unreachable service, missing client configuration | `200 OK`, `Content-Type: text/plain`, body `There has been an Error processing the request` (the `catch-exception-strategy`, `service-clients.xml:36-39`; `exception/GreeterClientExceptionHandler`, one ERROR line naming exception types only) |

Example: `http://localhost:63080/client?clientType=unsecure&name=John` → `Hello John`.

Every other WS-Security setting keeps its WSS4J default, as the CXF clients did: `PasswordDigest`, the Timestamp TTL, key identifiers, algorithms and body-content encryption. Responses are not validated, because the original clients had out-interceptors only.

### Listener defaults (SC-1, SC-10)

| Case | Response |
|---|---|
| A path the receiving port does not serve, for example `/client` on 63081, `/services/unsecure` on 63080, or `/services/other`, a CORS pre-flight included | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-10 `config/PortRoutingFilter`) |

Neither listener restricts methods: `GreeterClientController` maps `/client` without a method, plus an explicit `OPTIONS` mapping because Spring MVC would otherwise answer `OPTIONS` itself with an `Allow` header, so every method token runs the flow, `PROPFIND` and other extension methods included; the SOAP paths read any method as a message. SC-1's 405 is therefore reached by no request, and its 500 JSON default by none either, because `GreeterClientExceptionHandler` answers every `/client` failure. Tier 2A verification points: the bytes of these defaults, the `text/plain` header of the `/client` replies, the `utf-8` casing SAAJ writes on SOAP responses, the SOAP response and fault headers, which carry neither the `Accept` nor the `SOAPAction` request header SAAJ keeps on every message (dropped by SC-11 `config/SoapListenerBoundaryAdapter`, as CXF sent neither), characters outside the Basic Multilingual Plane written as raw UTF-8 rather than SAAJ's numeric character references (SC-11 `SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory`, as CXF wrote them; `DECISIONS.md` section 9, verification points), and Undertow's bare 400 for a malformed percent escape in the `/client` query, which it sends before dispatch.

### Sample exchanges

With the application on its default ports and the demo values shown under Build, run and test (`Date` lines omitted):

```console
$ curl -i 'http://localhost:63080/client?clientType=usernameTokenSigned&name=John'
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: text/plain
Content-Length: 10

Hello John

$ curl -i -X POST -H 'Content-Type: text/xml;charset=UTF-8' -H 'SOAPAction: ""' \
    --data-binary '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><ns2:greet xmlns:ns2="http://security.example.mule.mulesoft.com/"><name>John</name></ns2:greet></soap:Body></soap:Envelope>' \
    http://localhost:63081/services/username
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: text/xml;charset=utf-8
Content-Length: 366

<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault><faultcode xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">wsse:InvalidSecurity</faultcode><faultstring>An error was discovered processing the &lt;wsse:Security&gt; header</faultstring></soap:Fault></soap:Body></soap:Envelope>
```

## Configuration keys

Every key of `src/main/resources/application.yml`. The original reads no placeholder and its `mule-app.properties` defines no key; each value below either replaces a literal of the original, named in the Source column, or is a framework setting. Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6); `fixed` — a framework setting that reproduces the original listener, changing it changes behaviour.

The Type column gives the Java type each value binds to, taken from the code or the Spring Boot property that reads it.

| Key | Type | Default | Status | Environment variable | Source in the original | Purpose |
|---|---|---|---|---|---|---|
| `server.port` | `Integer` | `63081` | default | `SERVER_PORT` | `HTTP_Listener_Configuration1` port, `mule-config.xml:6` | the SOAP listener |
| `server.address` | `InetAddress` | `0.0.0.0` | default | — | `host="0.0.0.0"`, `mule-config.xml:6` | bind host of the SOAP listener |
| `ports.client` | `int` | `63080` | default | `PORTS_CLIENT` | `HTTP_Listener_Configuration` port, `service-clients.xml:6` | the `/client` listener; a scalar, so overriding it keeps the `listeners.additional` entry intact |
| `spring.webservices.path` | `String` | `/services` | fixed | — | `basePath="services"`, `mule-config.xml:6` | maps `MessageDispatcherServlet` at `/services/*` |
| `spring.webservices.servlet.init` | `Map<String, String>` (servlet init-parameter name to value) | `"[dispatchOptionsRequest]": "true"`, `"[dispatchTraceRequest]": "true"` | fixed | — | no `allowedMethods` | `OPTIONS` and `TRACE` on a SOAP path are read as messages, like every other method |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | fixed | — | no `allowedMethods`, `service-clients.xml:9` | `TRACE` on 63080 reaches `GreeterClientController` |
| `spring.web.resources.add-mappings` | `boolean` | `false` | fixed | — | none | an unserved path gets the no-listener 404, not a static-resource lookup |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | fixed | — | none | no Boot `/error` endpoint |
| `spring.application.name` | `String` | `soap-webservice-security-java` | fixed | — | none | application name in log lines |
| `listeners.host` | `String` | `0.0.0.0` | default | — | `host="0.0.0.0"` | bind host of additional listeners (SC-10) |
| `listeners.disallowed-methods` | `List<String>` (HTTP method names) | `[]` | fixed | — | no `allowedMethods` | no method is rejected by SC-10 |
| `listeners.main.routes[0].servlet` | `String` | `messageDispatcherServlet` | fixed | — | listener paths, `mule-config.xml:9,16,29,43,57,72` | the servlet behind the one route of `listeners.main.routes`, a `List<ListenerPorts.Route>` |
| `listeners.main.routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `["/services/unsecure", "/services/username", "/services/signed", "/services/encrypted", "/services/saml", "/services/signedsaml"]` | fixed | — | listener paths, `mule-config.xml:9,16,29,43,57,72` | the only paths 63081 serves; `?wsdl` is a query, so it matches too |
| `listeners.additional[0].name` | `String` | `client` | fixed | — | none: a label for `HTTP_Listener_Configuration`, `service-clients.xml:6` | label of the one entry of `listeners.additional`, a `List<ListenerPorts.Additional>` of extra Undertow listeners (SC-10), in log lines and start-up errors |
| `listeners.additional[0].port` | `int` | `${ports.client}` | fixed | — | `service-clients.xml:6` | the `/client` listener's port; set `ports.client` rather than this key |
| `listeners.additional[0].host` | `String` | `0.0.0.0` | fixed | — | `service-clients.xml:6` | bind host of the `/client` listener |
| `listeners.additional[0].routes[0].servlet` | `String` | `dispatcherServlet` | fixed | — | `service-clients.xml:9` | the servlet behind the `/client` listener's one route |
| `listeners.additional[0].routes[0].patterns` | `List<String>` (`AntPathMatcher` patterns) | `["/client"]` | fixed | — | `service-clients.xml:9` | the extra Undertow listener's only path (SC-10) |
| `soap-security.users.joe` | `String` (an entry of the `Map<String, String>` `users`) | none | TODO (secret) | `SOAP_SECURITY_USERS_JOE` | password hard-coded in `PasswordCallback.java:25-28` | expected UsernameToken password of `joe`; also the private-key password of keystore alias `joe` (signing, decryption) |
| `soap-security.users.stan` | `String` (an entry of the `Map<String, String>` `users`) | none | TODO (secret) | `SOAP_SECURITY_USERS_STAN` | password hard-coded in `PasswordCallback.java:29-32` | expected UsernameToken password of `stan` |
| `soap-security.keystore.properties` | `Resource` (a Spring resource location) | `classpath:wssecurity.properties` | default | — | `signaturePropFile`, `decryptionPropFile`, `encryptionPropFile` (`mule-config.xml:34,48,77`, `service-clients.xml:62,75`) | Merlin crypto settings; the carried file keeps the WSS4J 1.6 keys, which WSS4J 4 reads, without its store-password line |
| `soap-security.keystore.password` | `String` | none | TODO (secret) | `SOAP_SECURITY_KEYSTORE_PASSWORD` | `org.apache.ws.security.crypto.merlin.keystore.password` in `wssecurity.properties` | store password of `keystore.jks` |
| `soap-security.saml.properties` | `Resource` (a Spring resource location) | `classpath:saml.properties` | default | — | `samlPropFile`, `service-clients.xml:89,101` | SAML issuer settings read by `SAMLCallbackHandler`, without the issuer key-password line |
| `soap-security.saml.issuer-key-password` | `String` | none | TODO (secret) | `SOAP_SECURITY_SAML_ISSUER_KEY_PASSWORD` | `org.apache.ws.security.saml.issuer.key.password` in `saml.properties` and `wrong-saml.properties` | password of the issuer key `joe`, which signs a `SAMLTokenSigned` assertion; it equals `joe`'s key password, not the store password |
| `soap-security.client.user` | `String` | none | TODO | `SOAP_SECURITY_CLIENT_USER` | `user` property `joe`, `service-clients.xml:50,63,76,103` | outbound UsernameToken and signature user |
| `soap-security.client.encryption-user` | `String` | `joe` | default | — | `encryptionUser`, `service-clients.xml:77` | certificate alias of the committed demo keystore that `usernameTokenEncrypted` encrypts for |
| `soap-security.client.host` | `String` | `0.0.0.0` | default | — | `HTTP_Request_Configuration` host, `service-clients.xml:7` | host of the outbound Greeter calls |
| `soap-security.client.base-url` | `String` | `""` | default | — | none | empty: the client derives `http://<host>:<actual SOAP listener port>/services`; set to call another Greeter base URL |
| `soap-security.client.response-timeout` | `int` (milliseconds) | `10000` (milliseconds) | default | — | Mule's default response timeout | connect and read timeout of each outbound call, which is sent exactly once and never retried; zero (an indefinite wait) and negative values are rejected at start-up |
| `logging.level.org.springframework.ws.client.MessageTracing` | `LogLevel` | `INFO` | fixed | — | none: `service-clients.xml` declares no logger | its `.sent` `TRACE` writes each request after WSS4J secured it, the whole `wsse:Security` header included, and `.received` the reply |
| `logging.level.org.springframework.ws.server.MessageTracing` | `LogLevel` | `INFO` | fixed | — | none: `mule-config.xml` declares no logger | the same envelopes as the six Greeter paths receive and answer them |
| `logging.level.org.apache.wss4j` | `LogLevel` | `INFO` | fixed | — | none | its `DEBUG` writes the SAML assertion each `SAMLTokenProcessor` reads and the UsernameToken user name |
| `logging.level.org.apache.jcp.xml.dsig.internal.DigesterOutputStream` | `LogLevel` | `INFO` | fixed | — | none | its `DEBUG` `Pre-digested input` writes every signed part: the Body and, through the STR-Transform, the SAML assertion |
| `logging.level.org.apache.jcp.xml.dsig.internal.dom.DOMReference` | `LogLevel` | `ERROR` | fixed | — | none | silences the `WARN` `The input bytes to the digest operation are null` that WSS4J's STR-Transform raises on every signed SAML request, once when the client signs and once when the server validates; that digest still covers the assertion (`GreeterEndpointTest#signedSamlSignatureCoversTheAssertionThroughItsTokenReference`). `ERROR`, not `WARN`: its only other `WARN`, a dereferenced reference it cannot cache, ends in WSS4J's `FAILED_CHECK` rejection, which `PathScopedSecurityInterceptor` logs |
| `logging.level.org.apache.xml.security.encryption` | `LogLevel` | `INFO` | fixed | — | none | its `DEBUG` `Serialized octets`/`Decrypted octets` write the plaintext of every encrypted part, a UsernameToken a caller encrypts included |

- The `soap-security.users` map accepts further users the same way (`soap-security.users.<name>`).
- The application starts with every TODO empty. Nothing that needs a secret is built until it is used, so `/services/unsecure`, `?wsdl`, and the `unsecure` and `samlToken` client types work without them, while a path or client type that needs a missing value fails: a secured path faults, and `/client` answers `There has been an Error processing the request`.
- Unit tests supply the original example's public demo values through `src/test/resources/application-test.yml` (Spring profile `test`), because the committed demo `keystore.jks` and the original assertions depend on them: users `joe`/`secret` and `stan`/`elephant`, store password `keyStorePassword`, issuer key password `secret`, client user `joe`. They are test-only and never belong in `application.yml` (AAP 0.4.6).
- `config/WrongPasswordCallback` keeps the original's deliberately wrong password for `joe` in code. It is not a credential: only the wrong-password negative test uses it.
- The six `logging.level` keys keep the WS-Security headers (UsernameToken passwords, SAML assertions, signatures), the configured secrets and the plaintext that signing and encryption cover out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`. The original had no logger, so it logged none of them. The nested `UnderRootTrace` runs of `client/SecureGreeterClientTest` and `controller/GreeterEndpointTest` assert this.

## Mule-to-Java mapping

| Mule construct (`soap-webservice-security`) | Java construct (`com.mulesoft.examples.soap_webservice_security`) |
|---|---|
| `http:listener-config` 63081 `basePath="services"` (`mule-config.xml:6`) and 63080 (`service-clients.xml:6`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded): `server.port` with Spring WS `MessageDispatcherServlet` at `/services/*`, plus the SC-10 additional listener `client` (`config/ListenerPorts`, `config/PortRoutingFilter`) |
| `cxf:jaxws-service serviceClass="...Greeter"` + `cxf:ws-security` (`ws-config` actions, `passwordCallbackClass`, `signaturePropFile`/`decryptionPropFile`, `saml2-token-validator`) | `controller/GreeterEndpoint` (`@Endpoint`, `@PayloadRoot` `greet`) + `config/PathScopedSecurityInterceptor`, one policy per path built by `config/WsSecurityConfig`; Spring's `Wss4jSecurityInterceptor` is deliberately not used inbound |
| `component class="...GreeterService"` | `service/GreeterService` |
| `javax.jws` interface `Greeter` and its JAX-WS-derived wire shape | `model/soap/Greet`, `model/soap/GreetResponse` (Jakarta JAXB, explicit namespace) and `wsdl/GreeterService.wsdl` |
| CXF envelope style, faults and `?wsdl` | SC-11 `config/SoapEnvelopeStyleInterceptor`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`, `config/WsdlQueryPublisher`, wired by `config/WebServiceConfig` (a `WsConfigurer` without `@EnableWs`; its `SecurityFirstMessageDispatcher` runs the security check before endpoint selection) |
| `SecurityClients` flow: `set-payload`/`set-variable` from `http.query.params`, `set-property Content-Type text/plain` | `controller/GreeterClientController` |
| `http:listener` `client` without `allowedMethods` or a CORS policy (`service-clients.xml:9`) | `GreeterClientController` mapped without a method plus an explicit `OPTIONS` mapping; `config/PreflightDispatchConfig` dispatches a CORS pre-flight as a plain `OPTIONS` request |
| `choice` over `clientType` and its `otherwise` | `client/SecureGreeterClient.invoke` |
| six `cxf:jaxws-client` + `http:request` sub-flows (`service-clients.xml:41-110`) | `client/SecureGreeterClient`: one `WebServiceTemplate` per client type, each with its own securing `Wss4jSecurityInterceptor` |
| `catch-exception-strategy` (`service-clients.xml:36-39`) | `exception/GreeterClientExceptionHandler` (`@Order(0)`, scoped to `GreeterClientController`, ahead of SC-1) |
| WSS4J 1.6 `PasswordCallback`, `WrongPasswordCallback`, `SAMLCallbackHandler`, `SAMLCustomValidator` | WSS4J 4.0.1 ports in `config/`, same checks; `config/SoapSecurityProperties` binds `soap-security.*` |
| `SecureClient` console client (menu of nine requests) | not carried as a console; its request profiles are `SecureGreeterClient.getUsernameTokenProps`, `getSamlTokenProps`, `getSignedSamlTokenProps` and `greet(URI, …)`, used by the ported tests |
| `mule-deploy.properties` (`config.resources=service-clients.xml,mule-config.xml`) | `SoapWebserviceSecurityApplication` (`@SpringBootApplication`) |
| `SoapWebserviceSecurityIT` (`FunctionalTestCase`, CXF clients) | `SoapWebserviceSecurityTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, profile `test`) |

`spring-boot-starter-web-services` declares `jakarta.xml.ws:jakarta.xml.ws-api`, which the pom excludes; Spring WS needs only SAAJ (`saaj-impl`). `<spring-ws.version>4.1.4</spring-ws.version>` overrides the Boot 3.2.12 BOM's 4.0.11 while Spring Framework stays at 6.1.15 (AAP 0.6.2); the endpoint, interceptor and client tests are the gate for that combination. WSS4J 4.0.1 declares OpenSAML 5.1.6; the pom's `<dependencyManagement>` moves the OpenSAML and Shibboleth support modules to 5.2.3 and 9.2.3, Bouncy Castle to 1.86 and Guava to 33.7.2-jre, fixed releases of their published advisories (root `DECISIONS.md` section 11.3), and leaves Spring WS 4.1.4, WSS4J 4.0.1 and xmlsec 4.0.4 unchanged. OpenSAML is not on Maven Central, so `pom.xml` declares the Shibboleth releases repository `https://build.shibboleth.net/maven/releases/` (`DECISIONS.md` section 2).

## Behaviours kept, plan-chosen and externalized

- WSS4J 4 has no `samlPropFile`. `SAMLCallbackHandler` reads the issuer (`self` in `saml.properties`, `www.example.com` in `wrong-saml.properties`), the issuer crypto (`wssecurity.properties`) and the key name `joe` from the properties file, and builds the original assertion: SAML 2.0, sender-vouches, subject `AllowGreetingServices`. `SAMLCustomValidator` keeps every original check: issuer `self`, SAML 2.0, sender-vouches confirmation, subject `AllowGreetingServices`, failure code `invalidSAMLsecurity`.
- `/services/saml` sets WSS4J's `validateSamlSubjectConfirmation` to `false`. With WSS4J 4's default, the original unsigned sender-vouches request would be rejected ("Assertion fails sender-vouches requirements"), a check WSS4J 1.6 under CXF 2.7 did not make. Every `SAMLCustomValidator` check still runs, and `/services/signedsaml` keeps the default.
- The demo certificate `joe` (self-signed, MD5withRSA) is trusted because the keystore holds it, as WSS4J 1.6 trusted it; no revocation or certificate-path check is configured.
- The security check runs once per request before endpoint selection, so a secured path rejects a request without `wsse:Security` with `wsse:InvalidSecurity` whatever its body root, and an encrypted body reaches `greet` after decryption.
- Externalized: every credential the original hard-coded (Configuration keys). Stubbed: nothing; the original reaches no external system, and the `/client` front calls the application's own SOAP listener.
- Not carried (AAP 0.2.2): `mule-project.xml`, `src/test/resources/log4j2-test.xml`, and the `SecureClient` console menu.

## Build, run and test

Run from `java-conversions/soap-webservice-security-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listeners on 0.0.0.0:63081 (/services/*) and 0.0.0.0:63080 (/client)
java -jar target/soap-webservice-security-java-1.0.0-SNAPSHOT.jar
```

- Supply the TODO keys through their environment variables (`SOAP_SECURITY_USERS_JOE`, `SOAP_SECURITY_USERS_STAN`, `SOAP_SECURITY_KEYSTORE_PASSWORD`, `SOAP_SECURITY_SAML_ISSUER_KEY_PASSWORD`, `SOAP_SECURITY_CLIENT_USER`) or as `--key=value` arguments. For a local demo with the original's public demo values, activate profile `test` and add the test resources to the config location; `application-test.yml` lives under `src/test/resources`, so profile `test` alone does not find it in the jar:

  ```sh
  java -jar target/soap-webservice-security-java-1.0.0-SNAPSHOT.jar \
      --spring.profiles.active=test --spring.config.additional-location=optional:file:src/test/resources/
  curl 'http://localhost:63080/client?clientType=samlTokenSigned&name=John'    # Hello John
  curl 'http://localhost:63081/services/unsecure?wsdl'
  ```

- Running the application needs ports 63080 and 63081 free; override them with `--server.port=<port>` and `--ports.client=<port>`. A port in use stops start-up with `Web server failed to start. Port <n> was already in use.` The client front follows `server.port`, because it calls the SOAP listener's actual port.
- `mvn test` opens no fixed port: the Spring tests run the SOAP listener on a random port and give the client listener a free port drawn as `ports.client` in `@DynamicPropertySource` on every context load. When another process takes the drawn port first, `support/PortInUseRetryingContextLoader` loads the context again on a newly drawn port, up to 5 attempts.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.

## Tests

| Test class | What it asserts |
|---|---|
| `SoapWebserviceSecurityTest` | the ported `SoapWebserviceSecurityIT` (`soap-webservice-security/src/test/java/com/mulesoft/mule/example/security/SoapWebserviceSecurityIT.java:32-134`), one method per original test, each fetching the path's `?wsdl` first as the original's CXF client did: `testUnsecure`, `testUsernameToken`, `testUsernameTokenSigned`, `testUsernameTokenEncrypted`, `testSamlToken` and `testSignedSamlToken` answer exactly `Hello Mule`; `testUsernameTokenWrongPasswordError` (through `WrongPasswordCallback`) faults with a message containing `security token`; `testUsernameTokenUnsignedError` (`/services/signed` without a signature) and `testSamlTokenWrongIssuer` (`wrong-saml.properties`) fault with a message containing `<wsse:Security> header` |
| `controller/GreeterEndpointTest` | AAP 0.5.6: each path's required actions on the wire, including every original negative and the `samlTokenSigned` client path (`SAMLTokenSigned`, `DirectReference`, user `joe`) to `/services/signedsaml`; `signedSamlSignatureCoversTheAssertionThroughItsTokenReference`: a value of the signed assertion changed after signing is rejected with `FAILED_CHECK` while the unchanged request is greeted, so the STR-Transform digest covers the assertion; replay detection on the wire: a greeted `/services/username`, `/services/signed` or `/services/encrypted` request posted again unchanged is answered with `wsse:InvalidSecurity`, of six identical copies posted at once exactly one is greeted, and a repeated `/services/saml` or `/services/signedsaml` request is greeted again; nested `UnderRootTrace`, under `logging.level.root=TRACE`: a request to each secured path, its UsernameTokens sent as a sentinel user and password, a wrong-password request answered with the security fault, and `samlTokenSigned` without the `DOMReference` `WARN`, each answered as its path answers it while no security-header element, secret or greeted name reaches the console |
| `controller/GreeterClientControllerTest` | AAP 0.5.6: the `/client` routing on the wire, its `Client type is not supported` default, the error branch (including the real unsecure client's failure on a WireMock reply without a `greetResponse`: no entity, an empty SOAP Body, an unbound element, a `greet` element) and the port routing; every method token running the flow, `PROPFIND` and `MKCOL` included; CORS pre-flights answered exactly as plain `OPTIONS` requests (the flow on `/client`, the no-listener 404 on an unserved path), with no `Access-Control-*` or `Vary` header |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PathScopedSecurityInterceptorTest` | the production policies of `WsSecurityConfig` without a server: every original success case, the three original negatives, the `samlTokenSigned` client path whose `BinarySecurityToken` result is ignored, the any-order match, the fail-closed rules (missing header, missing Timestamp, unexpected action, empty `wsse:Username`, encoded spellings of a secured path), and replay detection: a replayed UsernameToken request rejected while a freshly secured one is accepted, exactly one of eight concurrent identical copies accepted, repeated SAML requests accepted again, the replay caches emptied when the context closes, and `BoundedReplayCache`'s reservation, retentions, bound, `close()` and null or empty identifiers |
| `config/WebServiceConfigTest` | through the real `MessageDispatcherServlet`: the policy enforced once per request before endpoint selection, an encrypted body reaching `greet`, `wsse:InvalidSecurity` for an unknown body root without a security header, `mustUnderstand` handling, anonymous `?wsdl` |
| `client/SecureGreeterClientTest` | against a WireMock Greeter stub: each client type's target path and WS-Security header (digest UsernameToken, body signature, body encryption for the encryption user, unsigned and signed SAML), `SOAPAction: ""`, the unsupported type answering without a request, the ported negative profiles (no signature, wrong issuer, wrong password), faults propagating unwrapped, an unreachable or slow service raising `WebServiceIOException`, a connection closed or reset before the response raising `WebServiceIOException` after exactly one request for every client type, a 200 reply without a `greetResponse` (no entity, an empty SOAP Body, a `greet` element) raising `IllegalStateException` and an element the Greeter marshaller binds to no class raising `UnmarshallingFailureException`, each after exactly one request for every client type, both `Encrypt` and `Encryption` encrypting the body, and a zero or negative `soap-security.client.response-timeout` failing start-up; nested `UnderRootTrace`, under `logging.level.root=TRACE`: the `usernameToken`, `usernameTokenEncrypted` and `samlToken` client types, the first two as a sentinel UsernameToken user and password, the signed UsernameToken and `samlTokenSigned` profiles of `joe`, and a `usernameToken` call the stub answers with a `wsse:FailedAuthentication` fault, each sending its `wsse:Security` header while no security-header element, sentinel, keystore password or greeted name reaches the console and `DOMReference` writes no `WARN` |
| `config/PasswordCallbackTest` | the ported callbacks: configured passwords for `joe` and `stan` on every usage, unknown or unconfigured users getting none, `WrongPasswordCallback` affecting only `joe` |
| `config/SAMLCustomValidatorTest` | `SAMLCustomValidator` accepting the `saml.properties` assertion and rejecting each failing check (issuer from `wrong-saml.properties`, SAML 1.1, bearer confirmation, another subject); `SAMLCallbackHandler`'s issuer-crypto diagnostics |
| `exception/GreeterClientExceptionHandlerTest` | the catch branch's precedence over SC-1 and its scoping to `GreeterClientController`, the 200 `text/plain` reply, one ERROR line naming exception types without the message |
| `service/GreeterServiceTest` | `greet` returns `Hello <name>`, `Hello null` and `Hello ` exactly as the original |
| `config/PortRoutingFilterTest` | SC-10: per-port routing, the byte-exact no-listener 404, additional listener registration, an empty TLS password named in the start-up failure; an additional port another socket holds failing server creation with `PortInUseException` before any XNIO worker exists; an unbindable host (`192.0.2.1`, a `.invalid` name) named with its port in an `IllegalStateException`; a server on port -1 neither probed nor given a worker; a port taken after the probe failing the start within 30 s instead of hanging it (`PortInUseException` with one additional listener, a `WebServerException` caused by a `BindException` with two); `destroy()` stopping the context's started server before the worker; the worker's I/O and worker threads from `server.undertow.threads.io` and `server.undertow.threads.worker` or Undertow's defaults |
| `support/PortInUseRetryingContextLoaderTest` | SC-10 test support: a context whose additional port another socket holds on the first attempt loads on a newly drawn port, with one WARN line per lost attempt, and that listener serves on its port; only a port in use on the cause chain (`PortInUseException`, a `BindException` saying `Address already in use`) loads again; a port in use is attempted 5 times and any other failure once, then rethrown |
| `config/SoapEnvelopeStyleInterceptorTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest`, `config/WsdlQueryPublisherTest` | SC-11: CXF envelope bytes, `soap:Server`, `soap:Client` and `soap:MustUnderstand` faults inside dispatch, the boundary faults, and `?wsdl`/`?xsd=` publication with rewritten addresses |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, body bytes and the bounded ERROR log line |
| `FixtureParityTest` | ST-2 fixture replay (section below); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Shared components

Each shared component is a copy of its reference implementation with only the `package` line changed (and, in `PortRoutingFilterTest`, its package-relative `support.HttpTestClient` import; in `support/PortInUseRetryingContextLoaderTest`, its `config.ListenerPorts` import), registered in [`DECISIONS.md`](../../DECISIONS.md) section 4 (AAP 0.4.4). A change is made in the reference first and then copied here.

| ID | Files in this project | Reference implementation |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`; test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-10 | `config/ListenerPorts`, `config/PortRoutingFilter`; test `config/PortRoutingFilterTest`; test support `support/PortInUseRetryingContextLoader` with `support/PortInUseRetryingContextLoaderTest` | `foreach-processing-and-choice-routing-java` |
| SC-11 | `config/SoapEnvelopeStyleInterceptor`, `config/WsdlQueryPublisher`, `config/SoapFaultConfig`, `config/SoapListenerBoundaryAdapter`; tests `config/SoapEnvelopeStyleInterceptorTest`, `config/WsdlQueryPublisherTest`, `config/SoapFaultConfigTest`, `config/SoapListenerBoundaryAdapterTest` | `foreach-processing-and-choice-routing-java` |
| ST-1 | `support/HttpTestClient` (test) | `rest-api-with-apikit-java` |
| ST-2 | `FixtureParityTest` (test), `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |
| — | `config/PreflightDispatchConfig`; test `config/PreflightDispatchConfigTest` | `hello-world-java` |

`config/WebServiceConfig` registers `wsdl/GreeterService.wsdl` for each of the six paths and passes no POST-only path to `SoapListenerBoundaryAdapter`, because the original listener accepts every method.

## Fixture parity (ST-2)

`FixtureParityTest` replays each fixture `src/test/resources/fixtures/<scenario>.json` against this project. The fixture format, replay drivers and comparison rules are those of the ST-2 reference, described in [`hello-world-java/README.md`, section "Fixture parity (ST-2 reference implementation)"](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's `src/test/resources/fixtures/SCENARIOS.md` holds its 29 scenario rows, the `state` every fixture carries (the demo credentials by reference to `application-test.yml`, `server.port` pinned to the captured `63081` so the `?wsdl` bodies compare byte for byte, which needs port 63081 free on the replay host) and the capture procedure. Every fixture records `"upstream": []`, because the `/client` calls go to the application's own SOAP listener. Fixtures are captured from the running original only, never written by hand or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Status in the last run |
|---|---|---|---|
| `get-client-unsecure` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-username-token` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-username-token-signed` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-username-token-encrypted` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-saml-token` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-saml-token-signed` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-unsupported-type` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-client-error` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-unsecure-greet` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-unsecure-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-username-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-signed-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-encrypted-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-saml-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-signedsaml-wsdl` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `get-services-unsecure-no-query` | `http` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-username-no-security-header` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-signed-no-security-header` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-encrypted-no-security-header` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-saml-no-security-header` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-signedsaml-no-security-header` | `soap` | `deterministic` | `UNVERIFIED-uncaptured` |
| `post-services-username-username-token` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-signed-username-token-signed` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-encrypted-username-token-encrypted` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-saml-saml-token` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-signedsaml-saml-token-signed` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-username-wrong-password` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-signed-missing-signature` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |
| `post-services-saml-wrong-issuer` | `soap` | `nondeterministic` | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| soap-webservice-security-java | soap-webservice-security | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

This project's row in the root [`SUMMARY.md`](../../SUMMARY.md) carries the same values. Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 597 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 597 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 2 of 2 lines of `service/GreeterService` (the project has no `mapper` package). Total project LINE coverage: 88.9% (1815 of 2041 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=29`, `failed=0`.
- The run includes `controller/GreeterEndpointTest` and `controller/GreeterClientControllerTest` (AAP 0.5.6), 52 and 46 tests respectively.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked, as [`DECISIONS.md`](../../DECISIONS.md) section 9 records: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`. No fixture can be captured from the original, so parity rests on the fallback, and the label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7):

- `SoapWebserviceSecurityTest` carries all nine committed assertions of `SoapWebserviceSecurityIT` with their original semantics: exact equality for the six positive calls, substring for the three faults.
- The interceptor, web-service, client, handler and controller tests cover every reachable branch of AAP 0.7.5 on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the flows and of the platform defaults, not that its bytes equal what the original runtime sent.
- Branches no original assertion covers, tested only by the new tests: the `/client` routing for all six types (`get-client-unsecure` to `get-client-saml-token-signed`), its `Client type is not supported` default (`get-client-unsupported-type`) and its error branch (`get-client-error`); the content of `?wsdl` (`get-services-*-wsdl`), which the original tests only consumed to build their CXF clients; a secured path receiving no or the wrong actions (`post-services-*-no-security-header`); and the listener boundary (`get-services-unsecure-no-query`).
- Unverified scenarios, by name from `src/test/resources/fixtures/SCENARIOS.md`:
  - All 29 rows above until Tier 2A runs.
  - Unverified until captured, then compared byte for byte: `get-client-unsecure`, `get-client-username-token`, `get-client-username-token-signed`, `get-client-username-token-encrypted`, `get-client-saml-token`, `get-client-saml-token-signed`, `get-client-unsupported-type`, `post-services-unsecure-greet`, the six `get-services-*-wsdl` rows, `get-services-unsecure-no-query` and the five `post-services-*-no-security-header` rows.
  - Unverified even after capture, `UNVERIFIED — nondeterministic output`: `post-services-username-username-token`, `post-services-signed-username-token-signed`, `post-services-encrypted-username-token-encrypted`, `post-services-saml-saml-token`, `post-services-signedsaml-saml-token-signed`, `post-services-username-wrong-password`, `post-services-signed-missing-signature` and `post-services-saml-wrong-issuer`. Each directly secured request carries per-request values (timestamps, nonces, digests, IDs, signatures, cipher values), so no curl command can send it; and the seven whose action set includes `Timestamp` could not be replayed either, because WSS4J rejects a `wsu:Timestamp` older than its 300 s time-to-live, so a replay would take a rejection branch the capture did not. They get no fixture and are escalated through `DECISIONS.md` as AAP 0.8.8 sets out.
  - `get-client-error` is captured from a redeployment without `mule-config.xml` and kept out of `fixtures/`, because the replay builds one application context for every fixture and its unreachable-service setting would break the other `/client` rows. It is replayed on its own and stays unverified in the default run.
- After capture the best label is therefore `PARITY: PARTIAL — Tier 2A fixtures: 20 verified, 9 unverified`; `PARITY: VERIFIED — Tier 2A fixtures` is out of reach while those nine stay unverified.
- `DECISIONS.md` also holds the shared-component register (section 4) and the authored `GreeterService.wsdl` with its "Captured WSDL replacements" record (section 7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `soap-webservice-security/`.
