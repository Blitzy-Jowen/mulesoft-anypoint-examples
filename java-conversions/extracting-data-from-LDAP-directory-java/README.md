# extracting-data-from-LDAP-directory-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`extracting-data-from-LDAP-directory`](../../extracting-data-from-LDAP-directory/) ("Importing data from an LDAP Directory", category General). The original folder `extracting-data-from-LDAP-directory/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

The example connects to an LDAP directory, retrieves the users stored under `ou=people`, logs each one and returns the aggregated list. The single flow `ldapFlow1` of `extracting-data-from-LDAP-directory/src/main/app/ldap.xml:7-16` runs on every HTTP request to `/`. It searches `ou=people` under `dc=my-domain,dc=com` with filter `(objectClass=*)`, splits the result, logs each entry, aggregates the entries back into one list, logs it and returns it as text. It declares no exception strategy, writes nothing and calls no other system.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/` | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK` with the direct children of `ou=people` rendered as `[<entry 1>, <entry 2>, …]`, as UTF-8 bytes with `Content-Length` and no `Content-Type` header. Each entry is the LDIF text UnboundID `Entry.toLDIFString()` writes, with its DN relative to the URL base DN (`dn: cn=mmc,ou=people`) and its attributes in the order the server returned them. A `HEAD` reply carries the headers without the body. | `ldap.xml:6-15` |
| `/`, when `ou=people` has no children | any | `0.0.0.0` | `http.port` | `200 OK`, empty body, `Content-Length: 0`; WARN `No LDAP entries found under ou=people` (the splitter yields nothing to aggregate) | `ldap.xml:9-15` |
| `/`, when the directory fails: unreachable, bind rejected, search base missing, no answer within 10 s, or `ldap.dn`/`ldap.password` unset | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }` (SC-1, AAP 0.4.7) | platform default: the flow has no exception strategy |
| any other path (`/unknown` and `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

- The request's query, headers and body are never read, so any `Accept`, `Content-Type` or body is ignored and never answered with 406, 415 or 400. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the search like any other `OPTIONS` request and gets no `Access-Control-*` or `Vary` header. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A directory failure is logged on two ERROR lines: `LdapDirectoryClient` names the result code, the server and the full search base (for example `LDAP search failed with result code 91 (connect error) on server localhost:389 for base 'ou=people,dc=my-domain,dc=com': …`), and SC-1 names the request method, the route and the exception chain. The bind password is never logged.
- The application starts without a reachable directory: the connection pool is created on the first request, and a failed creation is retried by the next one.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.
- The absent `Content-Type` of the `200` reply, the empty-result reply and the bytes of the `404` and `500` defaults are Tier 2A verification points (see [Behaviours stubbed or externalized](#behaviours-stubbed-or-externalized)).

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. Status: `default` — a non-secret value the original fixed or documented, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original.

| Key | Type | Default | Environment variable | Original name | Status | Description |
|---|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `HTTP_PORT` | `http.port` (unchanged) | default | bound to `server.port`. The original's `mule-app.properties` is empty, so `http.port` had no value there; its README sets port 8081 and browses to `http://localhost:8081/`. |
| `ldap.port` | `int` | `389` | `LDAP_PORT` | `ldap.port` (unchanged) | default | directory port, embedded in `ldap.url`. `389` is the OpenLDAP port of the original README. |
| `ldap.url` | `String` | `ldap://localhost:${ldap.port}/dc=my-domain,dc=com` | `LDAP_URL` | none: the literal `url` of `ldap:config` (`ldap.xml:3`) | default | connector URL carrying the host, port and base DN. Externalized because AAP 0.4.6 and 0.10.1 forbid hard-coded connection strings; only `ldap://` is accepted. |
| `ldap.dn` | `String` | none (`${LDAP_DN:}`, `# TODO: supply the bind DN`) | `LDAP_DN` | `ldap.dn` (unchanged; the `authDn` of `ldap:config`) | TODO | bind DN of the service account. The original README suggests `cn=Manager,dc=my-domain,dc=com`. |
| `ldap.password` | `String` | none (`${LDAP_PASSWORD:}`, `# TODO: supply the bind password`) | `LDAP_PASSWORD` | `ldap.password` (unchanged; the `authPassword` of `ldap:config`) | TODO (secret) | bind password of the service account |
| `server.port` | `Integer` | `${http.port:8081}` | — | none | fixed | the `http:listener-config` port (`ldap.xml:6`); set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | — | none | fixed | `http:listener-config host="0.0.0.0"` (`ldap.xml:6`) |
| `spring.application.name` | `String` | `extracting-data-from-LDAP-directory-java` | — | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | — | none | fixed | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | — | none | fixed | `TRACE` reaches `LdapController` like every other method, instead of the servlet's `message/http` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | — | none | fixed | a form body on `PUT`, `PATCH` or `DELETE` reaches the controller unparsed, as on `POST` |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | — | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |

- The bind identity is a configured service account and comes only from configuration: no part of a request can supply or override it (AAP 0.4.1). An unset `ldap.dn` or `ldap.password` never falls back to an anonymous bind; the first request fails with the 500 above (`{ "message": "ldap.dn is not configured" }`), while the application still starts.
- Relaxed binding maps each environment variable to its key, so `LDAP_PORT` also reaches `ldap.url` through its `${ldap.port}` placeholder.

### Test configuration

`src/test/resources/application-test.yml` (Spring profile `test`) supplies the original demo values, for tests only:

| Key | Test value | Purpose |
|---|---|---|
| `ldap.admin` | `cn=Manager,dc=my-domain,dc=com` | bind DN accepted by the in-memory directory (original `mule.test.properties` key) |
| `ldap.password` | the test bind password set in `src/test/resources/application-test.yml` | bind password (original `mule.test.properties` key) |
| `ldap.root` | `dc=my-domain,dc=com` | base DN the in-memory directory is created with (original `mule.test.properties` key) |
| `ldap.dn` | `${ldap.admin}` | the application's bind DN, as the original test set `ldap.dn` from `ldap.admin` (`ExtractingDataFromLdapDirectoryIT.java:73`) |

- These are the keys the original test read from the uncommitted `src/test/resources/mule.test.properties` (`ExtractingDataFromLdapDirectoryIT.java:69-72`), which is no longer needed. `ldap.port` is absent on purpose.
- The tests start an UnboundID in-memory directory server on a random port, loaded from `src/test/resources/ldap-add.ldif`, and register `ldap.port` through `@DynamicPropertySource`; `ldap.url` then resolves through it.
- `src/test/resources/ldap-delete.ldif` is carried unchanged as the original's cleanup LDIF; no test reads it.
- No test needs an external server, a credential or a licence, and the project has no `application-it.yml`.

### Preparing a directory

A real directory for running the application is prepared as the original README describes, condensed:

1. Install and start an LDAP server such as OpenLDAP on port 389, with the root DN `cn=Manager,dc=my-domain,dc=com` and a root password of your choice (`<password>`); the tests use the original README's demo password, set in `src/test/resources/application-test.yml`.
2. Import `src/main/resources/ldap.ldif`, byte-identical to the original's and to `ldap-add.ldif`: in Apache Directory Studio through **File > Import... > LDIF into LDAP**, or with `ldapadd -x -H ldap://localhost:389 -D cn=Manager,dc=my-domain,dc=com -W -f src/main/resources/ldap.ldif`.
3. Supply `LDAP_DN` and `LDAP_PASSWORD` (and `LDAP_PORT` or `LDAP_URL` when the server differs from the defaults), then run the application as shown below.

`ou=people` then holds `cn=admin`, `cn=testuser1` and `cn=mmc`. The LDIF also creates `cn=Manager` and `ou=groups` with three groups, which the search of `ou=people` does not reach.

## Mule-to-Java mapping

| Mule construct (`extracting-data-from-LDAP-directory`) | Java construct (`com.mulesoft.examples.extracting_data_from_ldap_directory`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`ldap.xml:6`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (`ldap.xml:8`) | `controller/LdapController`, `@RequestMapping("/")`: `search` (every method but `OPTIONS`) and `searchOptions` (`OPTIONS`), both writing the same reply |
| `ldap:config` with `url`, `authDn`, `authPassword` and `ldap:connection-pooling-profile` `INITIALISE_ONE` / `WHEN_EXHAUSTED_GROW` (`ldap.xml:3-5`) | `client/LdapDirectoryClient`: an UnboundID `LDAPConnectionPool` with a simple bind, created on the first request with one connection and growing past its 5 pooled connections on demand instead of blocking; connect, bind and search responses bounded by 10 s, the Mule default response timeout; only `ldap://` URLs accepted; no failed operation retried |
| `ldap:search baseDn="ou=people" filter="(objectClass=*)"` and `byte-array-to-object-transformer` (`ldap.xml:9-10`) | `LdapDirectoryClient.search(...)` with `LdapSearchService.BASE_DN` and `FILTER`: a one-level search (the connector's default scope) of `ou=people,dc=my-domain,dc=com` for all user attributes, returning UnboundID `Entry` objects |
| `collection-splitter` and logger `LDAP user: #[payload]` (`ldap.xml:11-12`) | the loop in `service/LdapSearchService.searchPeople()`, logging INFO `LDAP user: <entry>` per entry |
| `collection-aggregator` and logger `all users: #[payload]` (`ldap.xml:13-14`) | the entries collected in arrival order, logged INFO `all users: <list>` |
| `object-to-string-transformer` (`ldap.xml:15`) | `"[" + String.join(", ", texts) + "]"`, the `java.util.List.toString()` form of the entries' LDIF texts |
| `${ldap.dn}`, `${ldap.password}`, `${ldap.port}`, `${http.port}` placeholders | `config/LdapProperties` (`@ConfigurationProperties(prefix = "ldap")`) and `src/main/resources/application.yml` |
| Default exception strategy (none declared) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Mule listener semantics for CORS pre-flights (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `ldap.xml`) | `ExtractingDataFromLDAPDirectoryApplication` (`@SpringBootApplication`, `@EnableConfigurationProperties(LdapProperties.class)`) |
| `ExtractingDataFromLdapDirectoryIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `ExtractingDataFromLdapDirectoryTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

Logging: each entry is logged at INFO as `LDAP user: <entry>`, then the whole list as `all users: <list>`, as the two loggers of `ldap.xml:12,14` did.

## Behaviours stubbed or externalized

Nothing is stubbed, mocked at runtime or dropped. The directory is external at runtime and an UnboundID in-memory directory server in the tests.

- Externalized: the connection URL, literal in `ldap.xml:3`, is `ldap.url`; the bind DN and password, which the original README had users type into Studio's LDAP global element, come only from `ldap.dn` and `ldap.password` (AAP 0.4.6).
- Not carried: the Studio metadata `mule-project.xml` and `src/test/resources/log4j2-test.xml` (AAP 0.2.2). `mule.test.properties` is replaced by `application-test.yml`.

Tier 2A verification points (AAP 0.4.7 and the "LDAP list rendering" risk of AAP 0.8.9). Each is settled by captured fixtures of the original, never by hand-tuning: when a fixture differs, the Java code changes to match.

- Entry rendering. The original rendered entries through the Mule LDAP connector (JNDI). The original README's sample lists each entry's attributes in JNDI hash order (`uid`, `sn`, `userPassword`, `cn`, `objectClass`) and writes `userPassword` base64-encoded (`userPassword:: …`). UnboundID `Entry.toLDIFString()` writes the attributes in the order the server returned them and a printable value as plain text, so attribute order and the `userPassword` encoding may differ. Entry order and any operational attributes returned for this search are the server's: an UnboundID in-memory directory server, for one, also returns `entryDN`, `entryUUID`, `subschemaSubentry` and the creator and modifier timestamps.
- The absent `Content-Type` of the `200` reply.
- The empty-result outcome: `200` with an empty body. The original's reply to an empty `ou=people` is unrecorded (`get-root-no-people`).
- The `500` body text: the message is the UnboundID SDK's, not the JNDI or Mule exception message, and the original's reply to an unreachable directory, or its failed deployment, is unrecorded (`get-root-ldap-unavailable`).
- The `404` text `No listener for endpoint: <path>` (`get-unknown-path`).
- Very large lists: Mule's `object-to-string-transformer` renders a collection through its string utilities, which may cap the number of elements written; the Java rendering joins every entry.

## Build, run and test

Run from `java-conversions/extracting-data-from-LDAP-directory-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; no directory, credential or licence needed
```

Run against a directory (defaults: `ldap://localhost:389/dc=my-domain,dc=com`, listener on `0.0.0.0:8081`):

```sh
export LDAP_DN='cn=Manager,dc=my-domain,dc=com'
read -rs LDAP_PASSWORD && export LDAP_PASSWORD   # typed without echo, kept out of the shell history
# export LDAP_PORT=10389                         # only when the directory listens elsewhere; or set LDAP_URL
mvn spring-boot:run
java -jar target/extracting-data-from-LDAP-directory-java-1.0.0-SNAPSHOT.jar   # after mvn -B clean package
```

Expected exchanges with the directory loaded from `ldap.ldif`. The `200` body is abbreviated with `…`; its length, attribute lines and entry order depend on the server.

```console
$ curl -i http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: <body length>
Date: <current date>

[dn: cn=admin,ou=people
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
cn: admin
sn: admin
uid: admin
…
, dn: cn=mmc,ou=people
…
, dn: cn=testuser1,ou=people
…
]

$ curl -i http://localhost:8081/unknown
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Type: text/plain
Content-Length: 34
Date: <current date>

No listener for endpoint: /unknown
```

- There is no `mvn verify` live tier: the project has no failsafe plugin, no `*IT` class and no `application-it.yml`, because it is not a connector project (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `ExtractingDataFromLdapDirectoryTest` | the ported `ExtractingDataFromLdapDirectoryIT` (`extracting-data-from-LDAP-directory/src/test/java/org/mule/examples/ExtractingDataFromLdapDirectoryIT.java:111-115`), with profile `test` against an UnboundID in-memory directory loaded from `ldap-add.ldif`: `GET /` returns 200 and a non-empty body containing `dn: cn=mmc,ou=people`, `dn: cn=testuser1,ou=people` and `dn: cn=admin,ou=people` |
| `controller/LdapControllerTest` | on the wire through ST-1, with a mocked `LdapSearchService`: `GET /` → `HTTP/1.1 200 OK`, no `Content-Type`, exact `Content-Length` and body; the same reply for `POST`, `PUT`, `DELETE`, `PATCH`, `OPTIONS` and `TRACE` with a text body; `HEAD` without body; an empty result → 200 with `Content-Length: 0`; `/unknown` → 404 `text/plain` `No listener for endpoint: /unknown`; a directory failure → 500 `application/json` `{ "message": "<exception message>" }` |
| `service/LdapSearchServiceTest` | the `[<ldif>, <ldif>]` rendering of several entries in client order and of a single entry; an empty result → empty string; exactly one search of `ou=people` with `(objectClass=*)`; a directory failure propagated unchanged |
| `client/LdapDirectoryClientTest` | against an in-memory directory: the one-level search returns exactly `cn=mmc`, `cn=testuser1` and `cn=admin` with DNs relative to the URL base, and their attributes; a wrong password → `INVALID_CREDENTIALS`; every failure (wrong password, missing base, nothing listening, `ldap.dn`, `ldap.password` or `ldap.url` unset, invalid URL, URL without host) logged once at ERROR with result code, server and base, then rethrown unchanged; escaped, bounded log values; a message containing the bind password withheld; a failed first connection cached nothing, so the next search succeeds |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, and carries no stack trace |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` gets exactly the plain `OPTIONS` reply with no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404 and one whose search fails the SC-1 500 |
| `FixtureParityTest` | ST-2 fixture replay of the scenarios in `src/test/resources/fixtures/SCENARIOS.md` ([Fixture parity (ST-2)](#fixture-parity-st-2)); skipped (`no Tier 2A fixtures captured`) while no fixture exists |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed. The fixture format, the replay drivers and the comparison rules are described in [`java-conversions/hello-world-java/README.md`](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation); this project's fixture contract, directory state and capture commands are in `src/test/resources/fixtures/SCENARIOS.md`. Every scenario seeds the directory through `state.ldap` and makes no outbound call (`"upstream": []`).

Every scenario of `SCENARIOS.md` is unverified until Tier 2A capture:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-root-list-people` | `http` | `deterministic` | `GET /`, empty body, directory loaded from `ldap-add.ldif` | `UNVERIFIED-uncaptured` |
| `post-root-list-people` | `http` | `deterministic` | `POST /`, empty body, directory as above | `UNVERIFIED-uncaptured` |
| `get-root-no-people` | `http` | `deterministic` | `GET /`, empty body, directory holding only `dc=my-domain,dc=com` and `ou=people` | `UNVERIFIED-uncaptured` |
| `get-root-ldap-unavailable` | `http` | `deterministic` | `GET /`, empty body, nothing listening on `ldap.port` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`.
- `get-root-ldap-unavailable` stays unverified after capture as well: the replay runs one application context against one directory, so it cannot reproduce an absent directory. Once captured, its evidence is committed under `src/test/capture/get-root-ldap-unavailable/` without a fixture (`SCENARIOS.md`, Notes for this project).

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| extracting-data-from-LDAP-directory-java | extracting-data-from-LDAP-directory | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 132 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 132 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 17 of 17 lines of `service/LdapSearchService` (the project has no `mapper` package). Total project LINE coverage: 95.4% (332 of 348 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `DECISIONS.md` (section 9) records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". No fixture can be captured from the original, so parity rests on the fallback:

- `ExtractingDataFromLdapDirectoryTest` carries the original committed assertions with their contains-semantics: a non-empty body containing `dn: cn=mmc,ou=people`, `dn: cn=testuser1,ou=people` and `dn: cn=admin,ou=people`.
- `controller/LdapControllerTest`, `service/LdapSearchServiceTest`, `client/LdapDirectoryClientTest` and SC-1's `exception/GlobalExceptionHandlerTest` cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- No original assertion covers `post-root-list-people`, `get-root-no-people`, `get-root-ldap-unavailable` or `get-unknown-path`; they are covered only by the new tests above.
- Unverified scenarios, until captured (see [Fixture parity (ST-2)](#fixture-parity-st-2)): `get-root-list-people`, `post-root-list-people`, `get-root-no-people`, `get-root-ldap-unavailable`, `get-unknown-path`; `get-root-ldap-unavailable` stays unverified after capture as well.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. The original folder has no `LICENSE`, so `LICENSE` is copied from the repository root (AAP 0.2.3); `NOTICE` is copied unchanged from `extracting-data-from-LDAP-directory/`.
