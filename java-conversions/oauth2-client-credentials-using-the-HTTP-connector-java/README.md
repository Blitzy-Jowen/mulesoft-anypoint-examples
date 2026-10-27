# OAuth2 Client Credentials using the HTTP Connector — Spring Boot conversion

`oauth2-client-credentials-using-the-HTTP-connector-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`oauth2-client-credentials-using-the-HTTP-connector`](../../oauth2-client-credentials-using-the-HTTP-connector/) (category General). The original's only flow file is `src/main/app/http-client-credentials.xml`, the single entry of `config.resources` in its `mule-deploy.properties`; its `mule-app.properties` is empty. The original folder stays unchanged beside this project and is the Tier 2A capture source.

## Original example

The example has two flows on one listener (`0.0.0.0:8081`):

- `httpclientcredentialsFlow` (`/oauth`) obtains a Box access token with the OAuth 2.0 client-credentials grant and calls Box's `/api/oauth2/authorize/` with it.
- `http-client-credentialsFlow` (`/FIM/*`) forwards the request method and raw URI to the IBM FIM demo server `tfim01.demos.ibm.com`.

The original `README.md` describes the authorization-code example instead: `http://localhost:8081/web/{user-id}`, `/web/loginDone`, `http-authorization-code-web.xml` and the redirect URL `https://localhost:8082/redirectUrl`. None of these exist in this example's flow, and none is served here; they belong to [`oauth2-authorization-code-using-the-HTTP-connector`](../../oauth2-authorization-code-using-the-HTTP-connector/). Only two of its set-up steps apply to this example.

### Set-up

**Register a Box application.** In the Box developer portal, create a Box application (Content API), open **Configure Application** and copy its `client_id` and `client_secret`. Supply them as `oauth.client.id` and `oauth.client.secret` (see [Configuration keys](#configuration-keys)). `client.id` is the per-user identifier the `/FIM/**` proxy sends to IBM FIM.

**Create a key store and a trust store.** The Box API requires HTTPS. The original creates both stores with the JDK `keytool`. The commands below add `-storetype JKS`, which the original's commands lacked because its JDK wrote JKS by default:

```sh
keytool -genkey -storetype JKS -alias replserver -keyalg RSA -keystore keystore.jks
keytool -export -alias replserver -file client.cer -keystore keystore.jks
keytool -import -v -trustcacerts -storetype JKS -alias replserver -file client.cer -keystore trust-store
```

- The first command asks for the store password twice (`keystore.password`), then for the certificate's name fields, then `Enter key password for <replserver> (RETURN if same as keystore password)` (`keystore.key.password`).
- The second asks for the store password and writes the certificate to `client.cer`.
- The third asks for the trust-store password twice (`truststore.password`), then `Trust this certificate? [no]`, which `yes` answers.
- Without `-storetype JKS`, Java 17 writes a PKCS12 store whatever the file name (`keystore.type=pkcs12` in the JDK's `conf/security/java.security`), and `-genkey` asks for one password only, which protects both the store and the key. `TlsProperties` loads such a store too, because its JKS loader also reads PKCS12 (`keystore.type.compat=true`); `keystore.key.password` must then equal `keystore.password`.

The original's copies of both files are carried unchanged in `src/main/resources/`, which `keystore.path: keystore.jks` and `truststore.path: trust-store` resolve on the classpath. Both are JKS stores.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/oauth` (exact match) | any, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | Box answers below 400: `200 OK` with Box's body bytes and Box's `Content-Type`, no other Box header | `http-client-credentials.xml:9,12-34` |
| `/oauth`, when any step fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | `http-client-credentials.xml:35-37` |
| `/FIM/**` (`/FIM` and `/FIM/` included) | any, `OPTIONS` and `TRACE` included | `0.0.0.0` | `http.port` | upstream answers below 400: `200 OK` with the upstream body bytes and `Content-Type`, no other upstream header | `http-client-credentials.xml:29,39-46` |
| `/FIM/**`, when the call fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | no exception strategy in the flow |
| any path holding a `%` not followed by two hexadecimal digits (`/FIM/x%zz`, `/FIM/x%`, `/oauth%zz`, `/zz%zz`) | any | `0.0.0.0` | `http.port` | `400 Bad Request`, `Content-Length: 0`, no `Content-Type`, empty body, written by `config/InvalidPercentEncodingFilter` before dispatch | listener behaviour with `server.undertow.decode-url: false`; a Tier 2A verification point |
| any other path (`/oauth/`, `/oauth/extra`, `/FIMX` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because both listeners accept every method. The project has no non-HTTP trigger.

### `/oauth`: Box client-credentials call

`controller/OAuthController` → `service/BoxClientCredentialsService` → `client/BoxOAuthClient`, flow `httpclientcredentialsFlow` (`http-client-credentials.xml:31-38`):

1. The request body is logged at INFO as text, or as `{NullPayload}` when the request has none, which is how Mule 3 renders an empty payload (line 33).
2. `oauth.client.id` and `oauth.client.secret` are checked. A blank value fails the call with `oauth.client.id is not configured` or `oauth.client.secret is not configured` before anything is sent.
3. The HTTPS client for Box is built from the `TLS_Context` stores (`config/TlsProperties`, lines 25-28) on the first call and reused afterwards. While it cannot be built, a blank store password or a store that cannot be found or loaded fails each call with a `TLS configuration ...` message naming the key at fault, before any client secret is sent. The message names configuration keys only, never a configured path or password; a store that cannot be found or loaded is also logged at WARN by `config/TlsProperties` with its configured path, on the server only.
4. Without a cached token, the client-credentials grant runs: `POST https://app.box.com/api/oauth2/token` (`box.oauth.token-url`, line 16) with the form `grant_type=client_credentials` and the client credentials as an HTTP Basic `Authorization` header (the Mule 3.8.0 default, `encodeClientCredentialsInBody` false). `access_token` is extracted, together with the custom parameter `token_type` (line 18), and the token is cached in memory. The token request declares no TLS context, so it uses the JVM's default trust. It fails with `Token request to <token URL> failed with HTTP status <code>` on a non-2xx answer, or with `Token response from <token URL> (HTTP status <code>) contains no access_token`.
5. `GET https://app.box.com:443/api/oauth2/authorize/` is sent with `Authorization: Bearer <access token>` and the `TLS_Context` key store and trust store (line 34). The trailing slash comes from Mule joining basePath `/api/oauth2/authorize` with path `/`. Redirects are followed.
6. When Box answers 401 or 403, the token is requested again and the GET is retried once, as Mule's default `refreshTokenWhen` does. This is the original's own behaviour, not one of the AAP 0.4.8 connector additions, which do not apply to this project. The retry's answer is final.
7. A final Box status below 400 → `200 OK` with Box's body and `Content-Type`. A final status of 400 or above fails with `Response code <code> mapped as failure.` (Mule 3.8's response-validator message).
8. Any failure (Box status, token, TLS, connectivity or timeout) runs the `rollback-exception-strategy` branch, which logs the exception at INFO (lines 35-37). The branch builds no response, so the exception reaches SC-1, which logs one ERROR line with the exception types only and answers the platform 500 `{ "message": "<exception message>" }` (AAP 0.4.7, 0.5.6). The flow declares no transaction, so nothing is rolled back.

### `/FIM/**`: IBM FIM proxy

`controller/FimProxyController` → `service/FimProxyService` → `client/FimProxyClient`, flow `http-client-credentialsFlow` (`http-client-credentials.xml:39-47`):

- The inbound method and raw request URI (path and query as received, without decoding) are sent to `https://tfim01.demos.ibm.com:443` (`fim.base-url`, line 29), except that the characters `java.net.URI` cannot carry are percent-encoded, as the next bullet describes. That request configuration has no TLS context, so the JVM's default trust applies.
- Characters `java.net.URI` cannot carry in the path or the query (`\`, `|`, `^`, `"`, `<`, `>`, `` ` ``, `{`, `}`, a space, a control character or a non-ASCII byte), and a `%` in the query not followed by two hexadecimal digits, are percent-encoded before the call (`\` → `%5C`, `%zz` → `%25zz`, the byte `0xE9` → `%E9`), as Mule's requester encoded them, and the request is sent. Every valid escape, in its letter case, and every other character are sent as received, so an already valid URI reaches the FIM server byte for byte. A URL that is still not valid fails with `The request URI cannot be forwarded: it is not a valid URI` (SC-1 500), which names no URI, so neither `fim.base-url` nor the substituted `client.id` reaches the caller. A fragment after a raw `#` is never sent.
- A path holding a `%` not followed by two hexadecimal digits (`/FIM/x%zz`, `/FIM/x%`, `/FIM/{client_id}%zz`) is rejected before dispatch with `400 Bad Request`, `Content-Length: 0`, no `Content-Type` and no body, the rejection Undertow gives such a target when it decodes the URL itself (`config/InvalidPercentEncodingFilter`). It applies to every path and method, `/oauth%zz` and unserved paths included, and the FIM server is not called.
- `client_id` is an `http:uri-param` (line 43). It replaces each literal `{client_id}` in the forwarded path with `client.id`, percent-encoded once; it is never added as a query parameter. While `client.id` is blank, a URI holding `{client_id}` fails with `client.id is not configured` before the FIM server is called, and a URI without it is forwarded unchanged.
- The request body and its `Content-Type` are forwarded for every method except `GET`, `HEAD` and `OPTIONS`, which is Mule's `sendBodyMode` AUTO. No other caller header is copied, because the flow maps none.
- Upstream below 400 → `200 OK` with the upstream body and `Content-Type`. Upstream 400 or above (`Response code <code> mapped as failure.`) or a connectivity failure has no handling branch → SC-1 500.
- The flow has no logger, so the proxy logs nothing itself.

### Shared listener behaviour

- Every outbound call (the Box token request, the Box GET and the FIM call) goes through Apache HttpClient 5 with automatic retries disabled, so each request is sent exactly once: nothing is re-sent after a refused connect, a connection reset, a connection closed before the response or a timeout, whatever the method. The one repeat is the token refresh and GET retry after a Box 401 or 403 (step 6 of `/oauth`).
- Connect and response timeouts are 10 s on every outbound call, the Mule requester's default response timeout. The response timeout is one deadline from the start of each request over its whole response, headers and body: when it passes, the request is aborted and the call fails with `Response not received within 10000 ms`.
- A TLS failure on any outbound call (a certificate the trust in use does not hold, one issued for another host, or a refused handshake) fails the call with `I/O error on <method> request for "<URL>": TLS handshake failed`, without the JDK's own text, which names internal classes. The JDK's exception stays the cause, so SC-1's ERROR line still names its class.
- Redirects are followed up to four in a row, never from `https` to `http` (a fifth redirect, and one to `http`, is returned as received), and `Authorization`, `Cookie`, `Origin`, `Referer` and `Host` are not carried to another origin. A redirect without `Location` fails with `Invalid redirection`. A 307 or 308 re-sends the method and body; a 301 or 302 answering a `POST`, and a 303 answering any method, `HEAD` included, continue as a `GET` without the body. On every outbound call (the Box token request, the Box GET and the FIM call), each later redirect of that exchange, a 307 or 308 included, is followed as a `GET` without the body too, as `java.net.http` and Mule's requester follow them, so a token `POST` redirected first by a 301, 302 or 303 never re-sends the form.
- The transport adds `Host`, `Connection: keep-alive` and its own `User-Agent` (`Apache-HttpClient/<version> (Java/<version>)`), and sends `Content-Length: 0` for a bodyless `POST`, `PUT`, `PATCH`, `DELETE` or extension method and no `Content-Length` for a bodyless `GET`, `HEAD`, `OPTIONS` or `TRACE`; whether Mule's requester sent the same headers is a Tier 2A verification point.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- Tier 2A verification points: a captured fixture showing different bytes governs, and the code is changed to match.
  - The bytes of the no-listener 404 and of the unhandled-exception 500 (AAP 0.4.7).
  - When Box or the FIM server sends no `Content-Type`, the response carries none: both controllers write the body to the servlet response with its `Content-Length` and no message converter, so nothing such as `application/octet-stream` is added. This follows from the Mule message's MIME type staying `*/*` after an `http:request` whose response has none, for which the Mule 3.8 listener sends no `Content-Type`.
  - `/FIM/**` also matches the bare `/FIM` and `/FIM/`; how Mule's `/FIM/*` answered a bare `/FIM` is unverified.
  - Literal `{` and `}` in the request target are accepted (`ALLOW_UNESCAPED_CHARACTERS_IN_URL`), as Mule's Grizzly listener accepted them.
  - The `/FIM/**` call percent-encodes the characters `java.net.URI` cannot carry, and a malformed `%` escape in the query as `%25`, each byte up to `0xFF` as that one byte; the exact bytes Mule's requester sent for such a target are unverified.
  - A path holding a malformed `%` escape is answered `400 Bad Request` with an empty body before dispatch; how Mule's Grizzly listener answered such a path is unverified.
  - Form-encoded bodies of every method, `POST` included, are read from the request stream, so `/oauth` logs and `/FIM/**` forwards the caller's bytes unchanged. The Mule listener parsed a form-urlencoded body into a `ParameterMap`, which its logger rendered and its requester re-encoded, so the text Mule logged and the bytes it forwarded may differ in order or encoding.
  - `TRACE` runs the flow like every other method; whether the Mule listener ran it is unverified.
  - A `Content-Type` that cannot be parsed ends in the SC-1 500 before the flow's logger or proxy call runs.
  - An empty query (`/FIM/x?`) is forwarded without its `?`, because the servlet API reports an empty query string as absent.
  - The log message texts (`{NullPayload}`, the exception rendering) and the exception message texts.

## Configuration keys

Values as in `src/main/resources/application.yml`. Every secret is written `${ENV_NAME:}` with a `# TODO: supply ...` comment, so it has no value until it is supplied through that environment variable or as a command-line property (AAP 0.4.6).

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour; `unset` — read by the application's code but not set in `application.yml`, so the stated default applies.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | none: the original hard-codes port `8081` (`http-client-credentials.xml:9`) | default | listener port, bound by `server.port: ${http.port:8081}` |
| `oauth.client.id` | `String` | none (`${OAUTH_CLIENT_ID:}`) | `oauth.client.id` (line 14) | TODO, environment `OAUTH_CLIENT_ID` | the Box application's `client_id` |
| `oauth.client.secret` | `String` | none (`${OAUTH_CLIENT_SECRET:}`) | `oauth.client.secret` (line 14) | TODO (secret), environment `OAUTH_CLIENT_SECRET` | the Box application's `client_secret` |
| `client.id` | `String` | none (`${CLIENT_ID:}`) | `client.id` (line 43) | TODO, environment `CLIENT_ID` | the per-user `client_id` URI parameter of the FIM proxy |
| `keystore.path` | `String` | `keystore.jks` | `keystore.path` (line 27) | default, the carried store | JKS key store of the Box HTTPS call, looked up on the classpath first, then as a file; a PKCS12 store loads too (see [Set-up](#set-up)) |
| `keystore.password` | `String` | none (`${KEYSTORE_PASSWORD:}`) | `keystore.password` (line 27) | TODO (secret), environment `KEYSTORE_PASSWORD` | store password of the key store |
| `keystore.key.password` | `String` | none (`${KEYSTORE_KEY_PASSWORD:}`) | `keystore.key.password` (line 27) | TODO (secret), environment `KEYSTORE_KEY_PASSWORD` | password of the private key in the key store; for a PKCS12 key store, the same value as `keystore.password` |
| `truststore.path` | `String` | `trust-store` | `truststore.path` (line 26) | default, the carried store | JKS trust store of the Box HTTPS call, classpath first, then file; a PKCS12 store loads too |
| `truststore.password` | `String` | none (`${TRUSTSTORE_PASSWORD:}`) | `truststore.password` (line 26) | TODO (secret), environment `TRUSTSTORE_PASSWORD` | store password of the trust store |
| `box.oauth.token-url` | `String` | `https://app.box.com/api/oauth2/token` | none: hard-coded `tokenUrl` (line 16) | default | client-credentials token endpoint |
| `box.api.base-url` | `String` | `https://app.box.com:443` | none: hard-coded host, port and protocol (lines 12-13) | default | scheme, host and port of the Box GET |
| `box.api.base-path` | `String` | `/api/oauth2/authorize` | none: hard-coded `basePath` (line 12) | default | path prefix of the Box GET, to which `/` is appended |
| `fim.base-url` | `String` | `https://tfim01.demos.ibm.com:443` | none: hard-coded `HTTP_Request_Configuration1` (line 29) | default | scheme, host and port of the FIM proxy |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port (`http-client-credentials.xml:9`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`http-client-credentials.xml:9`) |
| `server.undertow.decode-url` | `Boolean` | `false` | none | fixed | the raw `http.request.uri` (line 41) reaches `FimProxyController` with its percent-encoding intact; a path whose `%` is not followed by two hexadecimal digits, which Undertow then no longer rejects and Spring MVC's path parsing would fail on outside any handler (Undertow's HTML 500), is answered `400` with an empty body by `config/InvalidPercentEncodingFilter` before dispatch |
| `server.undertow.options.server.ALLOW_UNESCAPED_CHARACTERS_IN_URL` | `Boolean` (Undertow `Option<Boolean>`) | `true` | none | fixed | a request target holding a literal `{client_id}` (lines 41-43) is accepted instead of answered 400; a Tier 2A verification point |
| `spring.application.name` | `String` | `oauth2-client-credentials-using-the-HTTP-connector-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches both controllers like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controllers unparsed, so `/FIM/**` forwards it and `/oauth` logs it unchanged |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `logging.level.org.apache.hc` | `LogLevel` | `INFO` | none | fixed | below `INFO` the httpclient5 transport logs the `Authorization: Basic` value of `oauth.client.id`:`oauth.client.secret`, both `Bearer` headers, the token response with its access and refresh tokens, and the FIM request line with the substituted `client.id`, none of which the original logged; a named level wins over `logging.level.root`, so a root `DEBUG` or `TRACE` does not reach it |
| `logging.level.org.springframework.web.servlet.DispatcherServlet`, `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver`, `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor`, `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | below `INFO` these write an exception's message (an unresolved failure, the resolved exception, the 500 body carrying it, the exception handler's arguments), and a FIM failure's message names the outbound URI with the substituted `client.id` |

- The application's code reads the `oauth.*`, `client.id`, `keystore.*`, `truststore.*`, `box.*` and `fim.base-url` keys with defaults equal to the values above, so such a key left out of `application.yml` keeps its value: `TlsProperties.java:82-86`, `BoxOAuthClient.java:271-275`, `FimProxyClient.java:207` and `FimProxyService.java:70`.
- No credential is hard-coded. The passwords of the carried demo stores are not in the repository; the original read them from properties too. Missing values do not stop the application from starting: they fail only the call that needs them, a `/oauth` call or a `/FIM/**` URI holding `{client_id}` (see [Behaviours stubbed or externalized](#behaviours-stubbed-or-externalized)).
- The three URL keys `box.oauth.token-url`, `box.api.base-url` and `fim.base-url` are checked at startup, unlike the missing values above. A value that embeds user information (`http://user:password@host`), or that is not an absolute `http` or `https` URL with a host, stops the application from starting with a message that names the key and never the value. The original's host, port and protocol attributes could hold no credentials, and a URL holding them would fail every call with an error naming the URL, credentials included.
- The `TLS_Context` replaces the JVM's trust for the Box GET, so the trust store must hold the CA that signs the certificate Box presents. The carried demo `trust-store` holds a single self-signed certificate (alias `client`, `CN=Unknown`), which is not that CA, so a live Box call needs a trust store of your own, named by `truststore.path`.
- `src/test/resources/application-test.yml` (Spring profile `test`) supplies placeholder values for the TODO keys, none of them a real credential, and points every upstream URL at `http://127.0.0.1:9`. The tests override the URLs with WireMock, so they need no real credentials and contact no external host. The project has no `application-it.yml`.

## Mule-to-Java mapping

| Mule construct (`oauth2-client-credentials-using-the-HTTP-connector`) | Java construct (`com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `8081` (line 9) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) on `server.port` and `server.address 0.0.0.0` |
| `http:listener path="oauth"` without `allowedMethods` (line 32) | `controller/OAuthController`, `@RequestMapping("/oauth")`: `oauth` (every method but `OPTIONS`) and `oauthOptions` (`OPTIONS`) |
| `logger message="#[payload]" level="INFO"` (line 33) | SLF4J INFO in `service/BoxClientCredentialsService` |
| `http:request-config` + `oauth2:client-credentials-grant-type` + `tls:context` (lines 12-28) | `client/BoxOAuthClient` + `config/TlsProperties` |
| `http:request` GET `path="/"` (line 34) | `BoxOAuthClient.authorize()` |
| `rollback-exception-strategy` with `logger message="#[exception]"` (lines 35-37) | try/catch in `BoxClientCredentialsService`: INFO log, then rethrow |
| `oauth2:token-manager-config` (line 10) | the in-memory token cache of `BoxOAuthClient` |
| `http:listener path="/FIM/*"` without `allowedMethods` (line 40) | `controller/FimProxyController`, `@RequestMapping("/FIM/**")`: `proxy` and `proxyOptions` |
| `http:request` with `uri-param client_id` on `HTTP_Request_Configuration1` (lines 29, 41-46) | `service/FimProxyService` (`{client_id}` substitution, body rules) + `client/FimProxyClient` |
| Default exception strategy and listener defaults (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| Grizzly listener's rejection of a malformed request target (a `%` in the path not followed by two hexadecimal digits) | `config/InvalidPercentEncodingFilter`: 400 with an empty body, before dispatch |
| `mule-deploy.properties` (loads `http-client-credentials.xml`) | `Oauth2ClientCredentialsUsingTheHTTPConnectorApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the flow's `${...}` placeholders | `src/main/resources/application.yml` |

## Behaviours stubbed or externalized

- **Token timing.** The original requested the Box token when the application was deployed, and a failed token request failed the deployment (Mule 3.8.0 `ClientCredentialsGrantType.start()`). The conversion requests it on the first `/oauth` call, so the application and the `/FIM/**` proxy start without Box credentials, and a failed token request is answered with the `/oauth` rollback branch's 500. Concurrent `/oauth` calls that need a token, because none is cached yet or because Box rejected the same one, share one token request instead of queuing for their own: all of them receive its token, or its one failure at once, so a stalled token endpoint holds each call for one 10 s timeout at most. A failed token request is not retried; the next call sends a new one. Neither the original nor the conversion refreshes the token proactively on `expires_in`; only a 401 or 403 from Box triggers a new token.
- **TLS loading.** The `TLS_Context` stores are read on the first HTTPS call to Box, not at start-up, for the same reason: the application starts with blank store passwords.
- **Externalized values.** The four hard-coded URLs became the default values of the `box.*` and `fim.base-url` keys. The credentials and store passwords stay externalized, as in the original.
- **Not carried.** Studio metadata (`mule-project.xml`) is not carried (AAP 0.2.2).
- **External services in tests.** Box and IBM FIM are replaced by WireMock in every test, and no test contacts them. The public FIM demo host is unreachable from the build host, and no Box credentials were supplied, so neither live service has been exercised.

## Build, run and test

Run from `java-conversions/oauth2-client-credentials-using-the-HTTP-connector-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
```

Run with the TODO keys supplied as environment variables, each replaced by your own value:

```sh
export OAUTH_CLIENT_ID="<Box client_id>" OAUTH_CLIENT_SECRET="<Box client_secret>" CLIENT_ID="<FIM client id>"
export KEYSTORE_PASSWORD="<store password>" KEYSTORE_KEY_PASSWORD="<key password>" TRUSTSTORE_PASSWORD="<trust-store password>"
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/oauth2-client-credentials-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar --http.port=9090
```

- There is no `mvn verify` live tier and no `application-it.yml`: this is not a connector project, so the pom has no failsafe plugin and the project no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `controller/OAuthControllerTest` | `/oauth` on the wire through ST-1, with WireMock as Box: the happy path for `GET` and `POST` (status line, body bytes, Box `Content-Type`, no other Box header, Bearer and Basic credentials, `grant_type=client_credentials`); a Box 500 → 500 `{ "message": "Response code 500 mapped as failure." }` with no retry; a rejected token request → 500; a 401 → one token refresh and one retry; a form-encoded body logged as the caller's text; a reply without a Box `Content-Type` answered without one; `TRACE` and `OPTIONS` replies complete when the controller returns (no echo, no `Allow`); an unparseable `Content-Type` → 500 before the flow runs; an unserved path → the no-listener 404; a path holding a malformed `%` escape (`/oauth%zz`, `/zz%zz`) → `400 Bad Request`, `Content-Length: 0`, no `Content-Type` and an empty body, without the flow's log line or any Box call |
| `controller/FimProxyControllerTest` | `/FIM/**` on the wire through ST-1, with WireMock as IBM FIM: raw path and query forwarded; targets `java.net.URI` refuses (`\`, `\|` or `^` after `{client_id}`, `?id={client_id}&z=\`, `?q=%zz`, a non-ASCII `é`) forwarded percent-encoded, with the substituted `client.id`, and answered with the upstream reply; `POST`, `PUT` and `DELETE` bodies and `Content-Type` forwarded; no other upstream header returned; an upstream 404 → 500 `{ "message": "Response code 404 mapped as failure." }`; a connection reset → 500; form-encoded bodies of every method forwarded as the caller's bytes; a reply without an upstream `Content-Type` answered without one; a `HEAD` answered by a 303 followed with one `GET`, answered with that representation's `Content-Length` and no body; `TRACE` and `OPTIONS` replies complete when the controller returns (no echo, no `Allow`); an unparseable `Content-Type` → 500 with nothing forwarded; an unserved path → the no-listener 404 with nothing forwarded; a path holding a malformed `%` escape (`/FIM/x%zz`, `/FIM/x%`, `/FIM/{client_id}%zz`, a `POST` with a body, an `OPTIONS`) → `400 Bad Request`, `Content-Length: 0`, no `Content-Type` and an empty body, with nothing forwarded |
| `controller/TraceLogExcludesCredentialsTest` | the `logging.level` pins under `logging.level.root=TRACE`, on the wire through ST-1 with WireMock as Box and IBM FIM: a `POST /oauth` (the client-credentials grant and its Bearer GET), a `GET /oauth` (a Box 401, a second token and the retry), a `/FIM/{client_id}/…` call answered and one failed by a connection reset; no line of the captured console output holds the Box client id, the client secret, their Basic value, either access token, the refresh token, the FIM `client.id` or `Authorization:`, while WireMock confirms every one of those values crossed the wire, TRACE request-mapping lines for both controllers prove the level was in effect, and the flow's INFO payload line is still written |
| `service/BoxClientCredentialsServiceTest` | the INFO payload log (UTF-8 text, or `{NullPayload}` for an absent or empty body) before the single Box call; 200 for every Box success status, with Box's body and only Box's `Content-Type`, verbatim; no `Content-Type` and an empty body when Box sends none; a failure logged at INFO after the payload and rethrown as the same instance; a checked failure wrapped in `IllegalStateException` |
| `service/FimProxyServiceTest` | `{client_id}` substitution, percent-encoded once, with the rest of the URI unchanged; a blank `client.id` refused before any call only when the URI needs it; no body or `Content-Type` for `GET`, `HEAD` and `OPTIONS`, both forwarded for other methods; only the upstream `Content-Type` returned; failures propagated after a single call |
| `client/BoxOAuthClientTest` | WireMock: `box.oauth.token-url` or `box.api.base-url` carrying user information, plain or percent-encoded, or not an absolute `http` or `https` URL with a host, refused at construction with a message naming the key and never the value; the client-credentials token request (Basic header, form), token reuse, refresh and one retry on 401 or 403, no further retry on a second rejection, no retry on other failure statuses, token-endpoint errors and token responses without `access_token`, Bearer whatever the `token_type`, redirects followed with the access token kept only within the origin, a 307 or 308 of the token request re-sending the form, a 307 or 308 after a 301, 302 or 303 of the token request followed as a bodyless `GET` with the token read from the final response, exactly one token `POST` and one `GET` after a connection reset or a connection closed before the response, a refused connection surfacing `ConnectException` after one attempt, a TLS failure of the token `POST` (JVM default trust) or of the HTTPS Box GET (an empty trust store) reported as `TLS handshake failed` without the JDK's text, the JDK's exception kept as the cause and nothing reaching the upstream, a response head or body not complete within the timeout failing at the deadline without a retry, single-flight token acquisition (two and ten concurrent first calls against a stalled token endpoint share its one request and receive its one timeout at its deadline, none queued behind another, and the next call sends a new request; ten concurrent first calls against a slow token endpoint share its one request and its token; ten concurrent first calls share one token request and their GETs are not queued; ten concurrent rejections of one token share one refresh, and each call retries once with the new token), an HTTPS base URL with blank store passwords failing before any request, a key store or trust store that cannot be found or cannot be loaded with its password failing before any request with a message naming the configuration key, never its path or a password, and the path, but no password, logged on one WARN line |
| `client/FimProxyClientTest` | WireMock and raw sockets: `fim.base-url` carrying user information, plain or percent-encoded, or not an absolute `http` or `https` URL with a host, refused at construction with a message naming the key and never the value; raw URI and method forwarded verbatim except the characters `java.net.URI` cannot carry, which are percent-encoded (valid escapes kept in their letter case, a malformed `%` sent as `%25`, a character up to `U+00FF` as its one byte and any other as UTF-8, the fragment not sent); an outbound URL still invalid once escaped failing with `The request URI cannot be forwarded: it is not a valid URI`, which holds no URI text and has no cause; body and `Content-Type` forwarded for every method that has a body, `TRACE` and `PROPFIND` included, response status, headers and body returned, `Response code <code> mapped as failure.` for statuses of 400 or above, redirects followed (307 and 308 re-sending the body, 302 and 303 continuing as a `GET`, a 303 answering a `HEAD` continuing as one `GET` while a 301, 302, 307 or 308 keeps the `HEAD`, every redirect after one that continued as a `GET` followed as a bodyless `GET`, a redirect from `https` to `http` and the fifth redirect in a row returned as received, a redirect without `Location` failing), exactly one request for every method after a connection reset or a connection closed before the response, a refused connection surfacing `ConnectException` after one attempt, a TLS failure (JVM default trust) reported as `TLS handshake failed` without the JDK's text, the JDK's exception kept as the cause and nothing reaching the upstream, a response head never sent or dribbled and a body stalled or dribbled failing once at the deadline, ten concurrent calls not queued |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/FIM/x` gets exactly the plain `OPTIONS` reply, forwarded once as `OPTIONS`, with no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404 |
| `config/InvalidPercentEncodingFilterTest` | the filter on mock requests: a path holding a `%` not followed by two ASCII hexadecimal digits anywhere (`/FIM/x%zz`, `/FIM/x%`, `/FIM/x%2`, `/FIM/%g0`, `/FIM/%0g`, `/FIM/a%20b%zz`, `/FIM/%25%`, `/oauth%zz`, `/zz%zz`, `/%`, a `%` before two Arabic-Indic digits) → status 400 set without `sendError`, `Content-Length: 0`, no `Content-Type`, an empty body and the response committed, with the chain not run; a path without one (valid escapes of either letter case included), a malformed escape in the query string only, and an empty or absent query → the same request and response passed to the chain, uncommitted |
| `FixtureParityTest` | ST-2 fixture replay of `src/test/resources/fixtures/SCENARIOS.md`; skipped while no fixture is captured |

Shared components, each copied with only its `package` line changed (AAP 0.4.4):

- SC-1 `exception/ErrorResponse`, `exception/GlobalExceptionHandler` and `exception/GlobalExceptionHandlerTest`, from `rest-api-with-apikit-java`.
- ST-1 `support/HttpTestClient`, from `rest-api-with-apikit-java`: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.
- ST-2 `FixtureParityTest`, from `hello-world-java`.
- `config/PreflightDispatchConfig` and its two tests, from `hello-world-java`.

The original has no tests of any kind (AAP 0.2.2, 0.7.5): its `src/` holds only `main`. Every test here is new, there are no original assertions to port, and no branch is covered by an original assertion.

## Fixture parity (ST-2)

`src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure; the reference implementation and its format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). No fixture is captured yet, so the replay reports every scenario `UNVERIFIED-uncaptured`.

| Scenario | Trigger | State | Request |
|---|---|---|---|
| `get-oauth-box-authorize` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-oauth-box-token-rejected` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-oauth-box-failure` | `http` | `deterministic` | `GET /oauth`, empty body |
| `get-fim-proxy` | `http` | `deterministic` | `GET /FIM/a/b?x=1&y=2`, empty body |
| `post-fim-proxy` | `http` | `deterministic` | `POST /FIM/a/b`, header `Content-Type: application/json`, body `{"name":"mule"}` |
| `get-fim-proxy-upstream-error` | `http` | `deterministic` | `GET /FIM/does-not-exist`, empty body |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body |

No scenario of this project is `unreachable` or `nondeterministic`.

- Capture must start the WireMock recording proxy for Box before the original is deployed, because the original's token exchange happens at deployment; each `/oauth` scenario redeploys the original, so its recording starts with that exchange.
- Box issues the access token into each `/oauth` recording, and it is a live value, so the three `/oauth` scenarios are captured without `upstream` and reported `UNVERIFIED-no-upstream` until the AAP 0.8.8 escalation settles them (`SCENARIOS.md`, Capture step 2).

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| oauth2-client-credentials-using-the-HTTP-connector-java | oauth2-client-credentials-using-the-HTTP-connector | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The same row and labels belong in the root `SUMMARY.md` (AAP 0.8.7).

Evidence of the run behind this row, on the tree that adds the oauth2-client-credentials security and token fixes, 2026-10-27 (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean verify`: `BUILD SUCCESS`; 432 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`, skipped by assumption while no fixture is captured); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 432 tests, 0 failures, 0 errors, 1 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 40 of 40 lines (`service/BoxClientCredentialsService` 19 of 19, `service/FimProxyService` 21 of 21; the project has no `mapper` package). Total project LINE coverage: 90.7% (909 of 1002 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=7`, `failed=0`.
- The run covered every test class in the table above. The boundary integration run on the combined tree re-measures this row.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md`. No fixture can be captured from the original, so parity rests on the fallback:

- The original has no tests, so no original assertion is carried and none of the seven scenarios is covered by an original assertion.
- The new controller, service and client tests cover every reachable branch: the Box success, refresh-and-retry and failure paths of `/oauth`, the forwarding and failure paths of `/FIM/**`, and the no-listener 404.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the AAP's reading of the flow and of the platform defaults, but not that its bytes equal what the original runtime sent.
- Unverified scenarios, until captured: `get-oauth-box-authorize`, `get-oauth-box-token-rejected`, `get-oauth-box-failure`, `get-fim-proxy`, `post-fim-proxy`, `get-fim-proxy-upstream-error`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `oauth2-client-credentials-using-the-HTTP-connector/`.
