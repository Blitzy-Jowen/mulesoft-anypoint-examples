# OAuth2 Authorization Code using the HTTP Connector — Java (Spring Boot)

`oauth2-authorization-code-using-the-HTTP-connector-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`oauth2-authorization-code-using-the-HTTP-connector`](../../oauth2-authorization-code-using-the-HTTP-connector/) (category General). The original's only flow file is `src/main/app/http-authorization-code-web.xml`, the single entry of `config.resources` in its `mule-deploy.properties`; its `mule-app.properties` is empty. The original folder stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

A browser user opens an HTTP endpoint and is sent through Box's OAuth 2.0 authorization-code flow: Box asks for the user's Box login, then for consent to grant the application access. Once access is granted, the application searches the user's Box files for `mule` with the access token Box issued, and shows the result.

- Flow `boxUserLoginFlow` (`/web`) redirects the browser to the local authorization URL `https://localhost:8082/authorization`, asking to come back to `/web/loginDone` afterwards.
- The two endpoints of the HTTPS listener on 8082 are not declared by any flow. The Mule OAuth module generated them from `oauth2:authorization-code-grant-type`: `/authorization` redirects to Box's authorization page, and `/redirectUrl` receives Box's callback, exchanges the code for tokens and stores them.
- Flow `userLoginDoneFlow` (`/web/loginDone`) calls Box's search API with the stored token and wraps the result in a fixed sentence.

Identity: the browser user is identified only through Box's authorization-code flow, and Box is called with the token issued for that user (AAP 0.4.1). The application adds no authentication layer of its own. The HTTPS listener's client-certificate requirement (see [Mutual TLS](#mutual-tls)) comes from the original TLS context, not from a login check.

The original README tells users to browse to `http://localhost:8081/web/{user-id}`. The listener path is the exact path `web`, which matches `/web` only, so `/web/<id>` gets the no-listener 404 here. Use `http://localhost:8081/web`.

### Set-up

**Register a Box application.** In the Box developer portal, create a Box application (Box Content API), open its configuration and copy the `client_id` and `client_secret`. Supply them as `box.id` and `box.secret` (see [Configuration keys](#configuration-keys)). Set the application's redirect URL to `https://localhost:8082/redirectUrl`.

**Create a key store and a trust store.** The original creates both stores with the JDK `keytool`:

```sh
keytool -genkey -alias replserver -keyalg RSA -keystore keystore.jks
keytool -export -alias replserver -file client.cer -keystore keystore.jks
keytool -import -v -trustcacerts -alias replserver -file client.cer -keystore trust-store
```

- The first command asks for the store password (`keystore.password`) and the key password (`keystore.keyPassword`); the third asks for the trust-store password (`truststore.password`).
- On Java 17, add `-storetype JKS` to the first and third commands. Without it `keytool` writes PKCS12, which asks for one password only, so `keystore.keyPassword` must then equal `keystore.password`. The listener's `ssl` entry declares `key-store-type: JKS`.
- Place both files in `src/main/resources/`. The original kept its copies in `src/test/resources/`, and its README asked users to move them into `src/main/resources/`. This project carries the original's two files unchanged in `src/main/resources/`.
- `keystore.path` and `truststore.path` are read from the file system when such a file exists, relative to the working directory, and from the classpath otherwise, as Mule resolved TLS store paths.

The committed stores are demo stores. Both are JKS with the demo password `mule123` for store and key. `keystore.jks` holds the private key `replserver`, and `trust-store` holds the matching certificate under alias `client`. The certificate is self-signed, `CN=Unknown`, and was valid only from 2015-02-04 to 2015-05-05. Replace both stores for any real use.

## Endpoints and triggers

Neither listener declares `allowedMethods`, so every path below accepts every HTTP method, `OPTIONS` and `TRACE` included. A CORS pre-flight is answered like any other `OPTIONS` request, with no `Access-Control-*` or `Vary` header (`config/PreflightDispatchConfig`). Paths match exactly. The project has no non-HTTP trigger.

| Listener | Path | Methods | Response | Source |
|---|---|---|---|---|
| HTTP `localhost:8081` (`server.address`, `server.port`) | `/web` | any | `302 Found`, `Location` = `box.login-redirect-location`, default `https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone`; body = the request body, `Content-Type` = the request's when a body is echoed | `http-authorization-code-web.xml:27-35` |
| HTTP `localhost:8081` | `/web/loginDone` | any | Box search below 400: `200 OK`, **no `Content-Type` header**, body `The user successfully authorized access to the resource. Searching for files containing "mule": <Box response body>`; otherwise `500` from SC-1 (below) | `http-authorization-code-web.xml:37-48` |
| HTTPS `localhost:8082` (`ports.box-oauth`), client certificate required | `/authorization` | any | `302 Found` to Box's authorization URL (below), no body, no `Content-Type` | platform OAuth handler of `oauth2:authorization-code-grant-type` (`http-authorization-code-web.xml:5-19`) |
| HTTPS `localhost:8082`, client certificate required | `/redirectUrl` | any | `302 Found` to `onCompleteRedirectTo` with `authorizationStatus`, or `200`/`400`/`500` with a platform text (below) | platform OAuth handler of `oauth2:authorization-code-grant-type` |
| either | any other path, and a path of one listener requested on the other's port | any | `404 Not Found`, `Content-Type: text/plain;charset=UTF-8`, body `No listener for endpoint: <path>` (SC-1 for an unserved path; SC-10 `PortRoutingFilter` for the wrong port) | listener platform default (AAP 0.4.7) |
| either | a served path, when processing throws | any | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1) | platform default for an unhandled exception (AAP 0.4.7) |

SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because no listener restricts methods.

### `/web`: redirect to the login

`controller/WebController.web` → `service/BoxOAuthService.webLogin`. The flow's `http:response-builder statusCode="302"` sets the fixed `Location` header, and the flow then logs at INFO `Redirecting user to Box login page`. The flow never replaces the payload, so the response body echoes the request body: empty for a plain `GET`, the posted bytes otherwise, with the request's `Content-Type`.

### `/web/loginDone`: Box search

`controller/WebController.loginDone` → `service/BoxOAuthService.loginDone` → `client/BoxApiClient.search`.

1. The access token of resource owner `default` is read from the in-memory token store. Without one, the request fails with SC-1's 500 `{ "message": "No access token for the default user. Verify that you have authenticated the user before trying to execute an operation to the API." }`.
2. `GET https://api.box.com:443/2.0/search?query=mule` (`box.api-base-url` + `/search?query=mule`) is sent with `Authorization: Bearer <access token>` and no body. The request's own body and query are not read.
3. On a Box `401` or `403`, which is Mule's default `refreshTokenWhen`, the stored refresh token is exchanged once at `box.token-url`, the new tokens are stored, and the search is repeated once. Concurrent rejected searches for the same owner redeem the refresh token only once. A token response without a new refresh token keeps the old one. A failed refresh ends in SC-1's 500, with `Failure calling token url <box.token-url>` (token endpoint status 400 or more), `Failed getting access token or refresh token from token URL response. See logs for details.` (no access token in the response), or the transport exception's message.
4. A final Box status of 400 or more ends in SC-1's 500 `{ "message": "Response code <n> mapped as failure." }`. A connection failure or a timeout ends in SC-1's 500 with the transport exception's message. Neither is retried.
5. Otherwise the Box body is turned into text (`object-to-string-transformer`), decoded in the charset of Box's `Content-Type`, or UTF-8 when it names none. The response is `200 OK` with the `set-payload` text in UTF-8 and no `Content-Type`, because `set-payload` declares no MIME type.

### `/authorization`: redirect to Box

`controller/BoxAuthorizationController.authorization` → `service/BoxOAuthService.authorization`. The response is a `302 Found` with this `Location`, its parameters in this order:

```text
https://www.box.com/api/oauth2/authorize?response_type=code&client_id=<box.id>[&state=<url-encoded ":onCompleteRedirectTo=<value>">]&box_device_id=1&box_device_name=device1&redirect_uri=https%3A%2F%2Flocalhost%3A8082%2FredirectUrl
```

- `client_id` is appended unencoded, as Mule's `AuthorizationRequestUrlBuilder` did. No `scope` is sent, because the original configures none.
- `state` is present only when the query carries a non-empty `onCompleteRedirectTo`. For the default `/web` redirect it is `%3AonCompleteRedirectTo%3Dhttp%3A%2F%2Flocalhost%3A8081%2Fweb%2FloginDone`.
- `box_device_id=1` and `box_device_name=device1` are the flow's custom parameters (`http-authorization-code-web.xml:8-11`).
- Query parameters are read from the raw query string only, never from a form body. A repeated name yields its last value. A malformed `%` escape anywhere in the query fails the request with SC-1's 500.

### `/redirectUrl`: Box callback

`controller/BoxAuthorizationController.redirectUrl` → `service/BoxOAuthService.redirectCallback` → `client/BoxApiClient.requestToken`.

1. `state` is decoded as Mule's `StateDecoder` did: the value after `:onCompleteRedirectTo=`, and the resource owner after `:resourceOwnerId=`, default `default`.
2. `code` is exchanged with a `POST` of `application/x-www-form-urlencoded` to `https://www.box.com/api/oauth2/token` (`box.token-url`), with the fields `code`, `grant_type=authorization_code`, `client_secret`, `redirect_uri`, `client_id`.
3. `access_token`, `refresh_token` and `expires_in` are extracted with Mule 3.8's default single-line extractors, and `token_type` with the custom extractor `#[json:token_type]` (`http-authorization-code-web.xml:15`). All four are stored in memory for the owner. They are lost on restart, as with the original's default token manager.
4. The outcome is an `authorizationStatus` marker:

| Marker | Meaning | Response with `onCompleteRedirectTo` in the state | Response without it |
|---|---|---|---|
| `0` | tokens stored | `302 Found`, `Location: <onCompleteRedirectTo>?authorizationStatus=0` (`&` instead of `?` when the URL has a query), no body | `200 OK`, `Successfully retrieved access token` |
| `100` | no `code`, or an empty one | as above with `100` | `400 Bad Request`, `Failure retrieving access token.` + line feed + ` OAuth Server uri from callback: <request path and raw query>` |
| `200` | the token call threw, or answered 400 or more | as above with `200` | `500 Internal Server Error`, `Failure calling token url <box.token-url>. Exception message is null` |
| `201` | no access token or no refresh token in the token response (a multi-line JSON body included) | as above with `201` | `500 Internal Server Error`, `Failed getting access token or refresh token from token URL response. See logs for details.` |
| `300` | any other failure | as above with `300` | `500 Internal Server Error`, `Failed processing redirect URL request done from OAuth provider. See logs for details.` |

Every text is UTF-8 with no `Content-Type`. A failure marker is logged at ERROR with its reason and, when an exception caused it, the exception types only. Token response bodies are never logged.

### Mutual TLS

- **Inbound.** The 8082 listener presents the `keystore.jks` key `replserver` and **requires a client certificate** that `trust-store` accepts. A handshake without one is refused. This matches Mule 3.8, whose HTTPS listener demanded client authentication whenever its TLS context had a trust store (`needClientAuth = isTrustStoreConfigured()`). SC-10 `config/ListenerPorts` sets it for every listener whose `ssl` entry names a trust store. A browser completing the flow therefore needs the client certificate installed and must accept the self-signed server certificate, as with the original. For the demo stores, export the certificate and key of `replserver` from `keystore.jks` to a PKCS12 file with `keytool -importkeystore` and import that into the browser. The file holds a private key, so keep it out of the repository.
- **Outbound.** `client/BoxApiClient` makes every HTTPS call (token, refresh, search) with the same `TLS_Context`, as the original's `tlsContext-ref="TLS_Context"` did on the request configuration and the grant. It trusts `trust-store` only, never the JDK `cacerts`, checks the server's host name, and offers the `keystore.jks` key when Box asks for a client certificate. For live use, `trust-store` must therefore trust Box's CA chain for `www.box.com` and `api.box.com`. The committed `trust-store` holds only the demo certificate, so live Box calls fail the TLS handshake until the CA certificate is imported, for example with `keytool -importcert -alias box-ca -file <Box CA certificate> -keystore trust-store`.
- The stores are loaded when the HTTPS listener opens at start-up, so the application does not start without the three store passwords (`cannot build the TLS context: Keystore was tampered with, or password was incorrect`), as the original did not deploy without them.

### Tier 2A verification points

These details were generated by the Mule platform rather than declared by the flow. The conversion reproduces Mule 3.8.0's implementation, and a captured fixture of the original governs where it differs (AAP 0.4.7):

- the Box authorization URL form, its parameter order and the unencoded `client_id`;
- the `state` encoding and decoding;
- the `authorizationStatus` values and the five `/redirectUrl` texts with their statuses;
- the `200` reply of `/web/loginDone` without `Content-Type`;
- the echoed body of the `/web` 302 and its `Content-Type`;
- the reason phrase `Found` of every 302;
- the 404 and 500 bodies, including the missing-token, refresh-failure and `Response code <n> mapped as failure.` messages.

## Configuration keys

Every key of `src/main/resources/application.yml` (AAP 0.4.6). Line numbers refer to `http-authorization-code-web.xml`.

Status: `default` is a non-secret value the original fixed, overridable. `TODO` has no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until it is supplied through the named environment variable or a `--key=value` argument; `(secret)` marks a secret. `fixed` is a framework or SC-10 setting that reproduces the original listeners; changing it changes behaviour.

| Key | Default | Status | Original name | Notes |
|---|---|---|---|---|
| `box.id` | `${BOX_ID:}` | TODO (secret) | `box.id` (unchanged) | Box OAuth client ID (`clientId`, line 6): `client_id` of the authorization URL and of both token forms |
| `box.secret` | `${BOX_SECRET:}` | TODO (secret) | `box.secret` (unchanged) | Box OAuth client secret (`clientSecret`, line 6): `client_secret` of both token forms |
| `keystore.password` | `${KEYSTORE_PASSWORD:}` | TODO (secret) | `keystore.password` (unchanged) | store password of `keystore.jks` (line 23) |
| `keystore.keyPassword` | `${KEYSTORE_KEYPASSWORD:}` | TODO (secret) | `keystore.keyPassword` (unchanged) | password of the private key in `keystore.jks` (line 23) |
| `truststore.password` | `${TRUSTSTORE_PASSWORD:}` | TODO (secret) | `truststore.password` (unchanged) | store password of `trust-store` (line 22) |
| `keystore.path` | `keystore.jks` | default | none (literal, line 23) | file system first, then classpath |
| `truststore.path` | `trust-store` | default | none (literal, line 22) | file system first, then classpath |
| `box.authorization-url` | `https://www.box.com/api/oauth2/authorize` | default | none (`authorizationUrl`, line 7) | Box authorization page, the base of the `/authorization` redirect |
| `box.token-url` | `https://www.box.com/api/oauth2/token` | default | none (`tokenUrl`, line 13) | code exchange and refresh |
| `box.api-base-url` | `https://api.box.com:443/2.0` | default | none (`http:request-config` protocol, host, port and `basePath`, line 5) | base of the `/search?query=mule` call |
| `box.redirect-url` | `https://localhost:${ports.box-oauth}/redirectUrl` | default | none (`redirectionUrl`, line 6) | `redirect_uri` of the authorization URL and of both token forms; must equal the redirect URL registered with Box |
| `box.local-authorization-url` | `https://localhost:${ports.box-oauth}/authorization` | default | none (`localAuthorizationUrl`, line 7) | bound by `config/BoxProperties` as the record of `localAuthorizationUrl`; no request reads it, because the listener is opened by `listeners.additional[0]` |
| `box.login-redirect-location` | `https://localhost:${ports.box-oauth}/authorization?onCompleteRedirectTo=http://localhost:${server.port}/web/loginDone` | default | none (response-builder `Location`, line 30) | `Location` of the `/web` 302 |
| `server.address` | `localhost` | fixed | none (`host="localhost"`, line 26) | bind address of the main listener |
| `server.port` | `8081` | default | none (`port="8081"`, line 26) | main listener; `box.login-redirect-location` follows it |
| `ports.box-oauth` | `8082` | default | none (the port of `localAuthorizationUrl` and `redirectionUrl`, lines 6-7) | HTTPS listener; the three local URLs above follow it. Override this key, not `listeners.additional[0].port`: overriding one list element replaces the whole `listeners.additional` list and drops its `ssl` and routes. Changing it also changes the redirect URL Box must have registered. |
| `listeners.host` | `localhost` | fixed | none | SC-10 default bind host of the additional listeners |
| `listeners.main.routes` | patterns `/web`, `/web/loginDone` | fixed | none (listener paths, lines 28 and 39) | SC-10: the paths the main listener serves |
| `listeners.additional[0]` | name `box-oauth`, port `${ports.box-oauth}`, host `localhost`; `ssl`: `key-store: ${keystore.path}`, `key-store-password: ${keystore.password}`, `key-password: ${keystore.keyPassword}`, `key-store-type: JKS`, `trust-store: ${truststore.path}`, `trust-store-password: ${truststore.password}`; routes `/authorization`, `/redirectUrl` | fixed | none (`TLS_Context`, lines 21-24) | SC-10 HTTPS listener; its trust store makes it require a client certificate. No `disallowed-methods`, so every method is accepted. |
| `spring.application.name` | `oauth2-authorization-code-using-the-HTTP-connector-java` | fixed | none | application name in log lines |
| `spring.web.resources.add-mappings` | `false` | fixed | none | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `true` | fixed | none | `TRACE` reaches the controllers like every other method |

The HTTPS listener cannot start without the three store passwords, so the application does not start until they are supplied. Supply the secrets through the environment, or as arguments:

```sh
export BOX_ID=<Box client_id> BOX_SECRET=<Box client_secret>
export KEYSTORE_PASSWORD=<key store password> KEYSTORE_KEYPASSWORD=<key password> TRUSTSTORE_PASSWORD=<trust store password>
mvn spring-boot:run
# other ports; the Box application must then register https://localhost:9082/redirectUrl
java -jar target/oauth2-authorization-code-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar --server.port=9081 --ports.box-oauth=9082
```

### Original-to-new key map

| Original key | Where the original read it | This project |
|---|---|---|
| `box.id`, `box.secret` | `http-authorization-code-web.xml:6`; `HttpAuthorizationCodeWebIT` from `src/test/resources/mule.test.properties` | same names, `application.yml` |
| `keystore.password`, `keystore.keyPassword`, `truststore.password` | `http-authorization-code-web.xml:22-23`; `HttpAuthorizationCodeWebIT` from `mule.test.properties` | same names, `application.yml` |
| `box.user`, `box.password` | `HttpAuthorizationCodeWebIT` (the Box login typed into the browser) | dropped: WireMock replaces Box, so no Box login is typed |
| `phantomjs.binary` | `HttpAuthorizationCodeWebIT` (system property, PhantomJS path) | dropped: no browser is driven |

The original placeholders keep their names, so nothing is renamed. Hosts, ports and URLs the original hard-coded became the new keys above, defaulting to the original literals.

### Test profile

`src/test/resources/application-test.yml` (Spring profile `test`) supplies demo values only: `keystore.password`, `keystore.keyPassword` and `truststore.password` are `mule123`, the demo password of the committed stores, and `box.id` and `box.secret` are the placeholders `test-box-client-id` and `test-box-client-secret`. Surefire activates the profile in every test JVM (`spring.profiles.active=test` in `systemPropertyVariables` of `pom.xml`), so `FixtureParityTest`, which declares no `@ActiveProfiles`, gets it too. No live credential exists in the repository, and the project has no `application-it.yml`.

## Mule-to-Java mapping

Classes are relative to `src/main/java/com/mulesoft/examples/oauth2_authorization_code_using_the_http_connector/`.

| Mule construct (`http-authorization-code-web.xml`) | Java construct |
|---|---|
| `http:listener-config HTTP_Listener_Configuration`, `localhost:8081` (line 26) | Undertow main listener (`spring-boot-starter-undertow`, Tomcat excluded): `server.address`, `server.port`; paths scoped by SC-10 `listeners.main.routes` |
| the OAuth module's local HTTPS listener behind `localAuthorizationUrl` and `redirectionUrl`, `localhost:8082` (lines 6-7) | SC-10 `config/ListenerPorts`: additional Undertow HTTPS listener `box-oauth` (`listeners.additional[0]`, port `ports.box-oauth`), client authentication required; `config/PortRoutingFilter` scopes both listeners' paths |
| flow `boxUserLoginFlow`: listener `web`, `http:response-builder statusCode="302"` with `Location`, `logger` (lines 27-35) | `controller/WebController.web` (and `webOptions` for `OPTIONS`) → `service/BoxOAuthService.webLogin` |
| flow `userLoginDoneFlow`: listener `web/loginDone`, `http:request GET /search` with `query=mule`, `object-to-string-transformer`, `set-payload` (lines 37-48) | `controller/WebController.loginDone` (and `loginDoneOptions`) → `service/BoxOAuthService.loginDone` → `client/BoxApiClient.search` |
| `oauth2:authorization-code-grant-type` with `oauth2:authorization-request` and its custom parameters (lines 6-12): the generated `/authorization` handler | `controller/BoxAuthorizationController.authorization` → `service/BoxOAuthService.authorization` |
| `oauth2:token-request` and the `token_type` extractor of `oauth2:token-response` (lines 13-17): the generated `/redirectUrl` handler and the grant's refresh | `controller/BoxAuthorizationController.redirectUrl` → `service/BoxOAuthService.redirectCallback` → `client/BoxApiClient.requestToken`; refresh through `client/BoxApiClient.refreshToken` |
| `oauth2:token-manager-config Token_Manager_Config` (line 4) | the in-memory per-resource-owner token store of `service/BoxOAuthService` (`storeTokens`, `tokens`) |
| `tls:context TLS_Context` (lines 21-24) | `config/BoxProperties` (store paths and passwords, `sslContext()` for outbound calls) plus the `ssl` block of the SC-10 listener |
| `http:request-config HTTP_Request_Configuration_HTTPS` (line 5) | `client/BoxApiClient`: `RestClient` over Apache httpclient5, 10 s connect and read timeouts (the Mule default response timeout), redirects followed (at most 4, never from `https` to `http`, the bearer token dropped once a redirect leaves the origin), no automatic retries |
| `logger level="INFO"` (line 34) | SLF4J INFO in `service/BoxOAuthService` |
| listener and runtime defaults (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse`; SC-10 `config/PortRoutingFilter` for the wrong port |
| Grizzly listener without a CORS policy | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `http-authorization-code-web.xml`) | `Oauth2AuthorizationCodeUsingTheHTTPConnectorApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the flow's `${...}` placeholders | `src/main/resources/application.yml` |
| `HttpAuthorizationCodeWebIT` (`FunctionalTestCase`, PhantomJS) | `HttpAuthorizationCodeWebTest` (`@SpringBootTest`, WireMock Box, ST-1) |

Shared components, each copied from its first consumer with only the `package` line changed and registered in `DECISIONS.md` (section 4):

- SC-1 `exception/GlobalExceptionHandler`, `exception/ErrorResponse`: the 404 and 500 platform defaults.
- SC-10 `config/ListenerPorts`, `config/PortRoutingFilter`: the second listener, HTTPS with required client certificate, and the per-port path scoping.
- ST-1 `src/test/java/.../support/HttpTestClient`: raw HTTP/1.1 client that returns the status line with its reason phrase. Its HTTPS mode `HttpTestClient.https(host, port, trustStorePath, trustStoreType, trustStorePassword, keyStorePath, keyStoreType, keyStorePassword)` presents the carried client certificate.
- ST-2 `src/test/java/.../FixtureParityTest`, `src/test/resources/fixtures/SCENARIOS.md`: fixture replay.

## Behaviours stubbed or externalized

- Externalized: the Box client ID and secret and the three store passwords, under their original placeholder names with no value (AAP 0.4.6). The hosts, ports and URLs the original hard-coded are keys with the original literals as defaults.
- Moved: `keystore.jks` and `trust-store`, carried byte-identical from the original's `src/test/resources/` to `src/main/resources/`, where the original README asked users to put them.
- Stubbed in tests only: Box's authorize, token and search endpoints (WireMock). At run time the application calls the real Box.
- Kept as in the original: the in-memory token store, lost on restart; the single refresh-and-retry on a Box 401 or 403, which is Mule's default `refreshTokenWhen` and not an AAP 0.4.8 connector addition. This is a General project, so no connector failure mode is added.
- Not carried: Studio metadata (`mule-project.xml`) and `log4j2-test.xml` (AAP 0.2.2). No behaviour was dropped.

## Build, run and test

Run from `java-conversions/oauth2-authorization-code-using-the-HTTP-connector-java/`:

```sh
mvn -B clean install    # enforcer JAX-WS/CXF ban, build, surefire tests, JaCoCo check, jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # needs the five TODO keys (see Configuration keys); HTTP localhost:8081, HTTPS localhost:8082
```

Expected exchange (the `Date` value varies):

```console
$ curl -i http://localhost:8081/web
HTTP/1.1 302 Found
Connection: keep-alive
Location: https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone
Content-Length: 0
Date: <current date>
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not one of the AAP's connector projects (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- Tests never use ports 8081 or 8082. The main listener runs on a random or reserved free port, and the HTTPS listener on a free port set through `ports.box-oauth`.

## Tests

Test classes are relative to `src/test/java/com/mulesoft/examples/oauth2_authorization_code_using_the_http_connector/`. The counts are from the last `mvn -B test` run. Every test runs offline: Box is a WireMock stub, and no test binds 8081 or 8082.

| Test class | Tests | What it asserts |
|---|---|---|
| `HttpAuthorizationCodeWebTest` | 1 | the ported `HttpAuthorizationCodeWebIT` (`oauth2-authorization-code-using-the-HTTP-connector/src/test/java/org/mule/examples/HttpAuthorizationCodeWebIT.java:83-98`). The original browser flow is driven through every redirect by ST-1, over HTTPS with the client certificate on the `box-oauth` listener: `/web` → `/authorization` → WireMock authorize, which grants consent at once → `/redirectUrl` → `/web/loginDone?authorizationStatus=0`. The final page contains `The user successfully authorized`, the original assertion with its contains semantics, after exactly one token exchange and a search with the issued bearer token. |
| `controller/WebControllerTest` | 10 | on the wire: `/web` → `302 Found` with the exact `Location`, no body; `POST /web` echoes body and `Content-Type`; `/web/loginDone` without a token → the missing-token 500; with a token → `200 OK`, no `Content-Type`, the `set-payload` text and one `GET /2.0/search?query=mule`; Box 404 → `Response code 404 mapped as failure.`; connection failure → 500; Box 401 → one refresh, then a successful retry; `OPTIONS /web` reaches the controller; `/unknown`, `/authorization` and `/redirectUrl` on the main port → the no-listener 404 |
| `controller/BoxAuthorizationControllerTest` | 17 | over HTTPS with the client certificate: the Box authorization URL with and without `state`; Mule's query-parameter rules; every method accepted; `/redirectUrl` markers `0` (tokens stored), `100`, `200` (error status and connection failure) and `201` (missing token and pretty-printed JSON) with `onCompleteRedirectTo`, and the 200, 400 and 500 texts without it; a handshake without a client certificate refused; `/web`, `/web/loginDone` and `/unknown` on the HTTPS port and `/authorization` and `/redirectUrl` on the main port → the no-listener 404 |
| `service/BoxOAuthServiceTest` | 105 | the service alone, with a strict mock of `BoxApiClient`: every reply of the four endpoints, every `authorizationStatus` marker and text, the `state` content, the `token_type` extractor, the per-owner token store, refresh and retry (a failed refresh, a refresh without an access token, a retry still rejected, concurrent rejections, a callback during a refresh), token values never printed, and failed token calls logged by exception types only |
| `client/BoxApiClientTest` | 65 | against WireMock and local HTTPS stand-ins: token forms in Mule's field order and encoding, the bearer search, every status returned rather than thrown, one attempt only on any transport failure, a stalled response timed out once within the read timeout, redirect limits and the bearer token kept to its origin, `TLS_Context` trust only, lazy loading of the stores, unusable configured URLs rejected by key, and DEBUG logs showing only scheme, host, port and path |
| `config/PortRoutingFilterTest` | 52 | SC-10: per-port path scoping, the byte-exact no-listener 404, named-servlet forwarding, routing logs without the request path, the filter registered ahead of every other filter, an invalid additional port refused, and an additional listener serving the same deployment as the main one |
| `config/PreflightDispatchConfigTest` | 25 | the pre-flight handler mapping: a pre-flight without CORS configuration selects the plain `OPTIONS` handler; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | 54 | on both listeners: a CORS pre-flight is answered exactly as a plain `OPTIONS` request, never with Spring's 403, and with no `Access-Control-*` or `Vary` header |
| `exception/GlobalExceptionHandlerTest` | 64 | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers and body bytes, `ErrorResponse` JSON escaping, and bounded ERROR log lines without exception messages |
| `FixtureParityTest` | 1 (skipped) | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes. Its TLS mode presents the carried client certificate to the `box-oauth` listener.

The original IT logged in to a real Box account in PhantomJS and clicked the consent button. That live login cannot be reproduced without Box credentials and a browser, so `HttpAuthorizationCodeWebTest` drives the same redirects against WireMock instead. This evidence is weaker than captured fixtures (AAP 0.8.4 fallback).

## Fixture parity

`src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds its fixture contract and capture procedure. The ST-2 reference implementation and its format are described in [`hello-world-java`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). No fixture is captured yet, so the replay reports every scenario `UNVERIFIED-uncaptured`.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-web-redirect` | `http` | `deterministic` | HTTP 8081 `GET /web` | `UNVERIFIED-uncaptured` |
| `post-web-redirect-echo-body` | `http` | `deterministic` | HTTP 8081 `POST /web`, `Content-Type: text/plain`, body `hello` | `UNVERIFIED-uncaptured` |
| `get-login-done-no-token` | `http` | `deterministic` | HTTP 8081 `GET /web/loginDone`, empty token store | `UNVERIFIED-uncaptured` |
| `get-login-done-authorized` | `http` | `nondeterministic` | HTTP 8081 `GET /web/loginDone` after an interactive Box login | `UNVERIFIED-uncaptured` |
| `get-authorization-with-on-complete-redirect` | `http` | `deterministic` | HTTPS 8082 `GET /authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone` | `UNVERIFIED-uncaptured` |
| `get-authorization-without-on-complete-redirect` | `http` | `deterministic` | HTTPS 8082 `GET /authorization` | `UNVERIFIED-uncaptured` |
| `get-redirect-url-success-with-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl?code=test-code&state=…` | `UNVERIFIED-uncaptured` |
| `get-redirect-url-no-code-with-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl?state=…` | `UNVERIFIED-uncaptured` |
| `get-redirect-url-token-call-failed-with-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl?code=test-code&state=…`, token endpoint 500 | `UNVERIFIED-uncaptured` |
| `get-redirect-url-token-not-found-with-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl?code=test-code&state=…`, token response without `access_token` | `UNVERIFIED-uncaptured` |
| `get-redirect-url-success-without-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl?code=test-code` | `UNVERIFIED-uncaptured` |
| `get-redirect-url-no-code-without-state` | `http` | `deterministic` | HTTPS 8082 `GET /redirectUrl` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | HTTP 8081 `GET /unknown` | `UNVERIFIED-uncaptured` |
| `get-authorization-on-http-port` | `http` | `deterministic` | HTTP 8081 `GET /authorization` | `UNVERIFIED-uncaptured` |
| `get-web-on-https-port` | `http` | `deterministic` | HTTPS 8082 `GET /web` | `UNVERIFIED-uncaptured` |

`SCENARIOS.md` gives each request in full, the `state` value included. No scenario is `unreachable`. Every scenario is unverified until Tier 2A capture runs, and two groups stay unverified after it:

- `get-login-done-authorized` is `nondeterministic`: its body is live Box search data, and its token comes from an interactive Box login that neither the fixture's `state` nor earlier requests can seed. Its fixture omits `upstream`, so it is reported `UNVERIFIED-no-upstream` and at best compared for structure, never as byte-for-byte evidence.
- The nine HTTPS rows need a client certificate on replay, which the copied ST-2 driver does not yet pass to ST-1. Their capture evidence is kept in `src/test/capture/<scenario>/` without a fixture, and they stay `UNVERIFIED-uncaptured` until ST-2 provides that mode.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| oauth2-authorization-code-using-the-HTTP-connector-java | oauth2-authorization-code-using-the-HTTP-connector | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install` (which includes `clean package`): `BUILD SUCCESS`; enforcer `BannedDependencies passed`; 394 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 394 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 166 of 166 lines of `service/BoxOAuthService` and its nested records. The project has no `mapper` package. Total project LINE coverage: 95.2% (844 of 887 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=15`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in `DECISIONS.md` (section 9). No fixture can be captured from the original, so parity rests on the fallback:

- `HttpAuthorizationCodeWebTest` carries the original's only committed assertion: after the authorization flow, the `/web/loginDone` page contains `The user successfully authorized`.
- `WebControllerTest`, `BoxAuthorizationControllerTest` and `BoxOAuthServiceTest` cover every other branch of the two flows and the two generated handlers on the wire or in the service.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the Mule 3.8 platform behaviour, but not that its bytes equal what the original runtime sent.
- Every branch except the final success text has no original assertion and is covered only by the new tests:
  - the `/web` 302 and its `Location` (the original IT only followed it) and the echoed body;
  - the missing-token, Box error-status and connection-failure 500s, and the refresh-and-retry on 401 or 403;
  - `/authorization` with and without `state`;
  - every `/redirectUrl` marker and text;
  - the no-listener 404s on both ports;
  - the refused handshake without a client certificate.
- Unverified scenarios, until captured: all fifteen listed above. `get-login-done-authorized` stays unverified after capture as well (nondeterministic output), and the nine HTTPS rows until ST-2 can replay with a client certificate.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `oauth2-authorization-code-using-the-HTTP-connector/`.
