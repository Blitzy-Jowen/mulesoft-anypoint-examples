# Exposing a RESTful resource using the HTTP connector — Spring Boot conversion

`exposing-a-restful-resource-using-the-HTTP-connector-java` converts the Mule EE 3.8 example [`exposing-a-restful-resource-using-the-HTTP-connector`](../../exposing-a-restful-resource-using-the-HTTP-connector/) (category General) to Java 17 / Spring Boot 3.2.12 / Maven, package `com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector`. The original folder `exposing-a-restful-resource-using-the-HTTP-connector/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

The example exposes an in-memory "person" resource over HTTP with JSON: `POST /person` stores a person, `GET /person/{personId}` returns one, and `GET /person` returns all of them. The application loads one flow file, `src/main/app/http-restful-resource.xml` (`config.resources` of its `mule-deploy.properties`). Two singleton Spring beans hold the data: the `java.util.HashMap` `personDataStore` and the `java.util.concurrent.atomic.AtomicInteger` `personId` (`http-restful-resource.xml:6-9`). Three flows share one listener configuration:

- `retrieveAllPersonsFlow` (`http-restful-resource.xml:10-14`): renders the store's values as JSON.
- `storePersonFlow` (`http-restful-resource.xml:15-25`): converts the body to a `Person`, stores it under the next counter value and answers 201 with a `Location` header and a constant success text.
- `retrievePersonFlow` (`http-restful-resource.xml:27-43`): parses the id, logs it at ERROR, fails a throwing filter when no person is stored under it, and otherwise returns that person. A choice exception strategy turns the filter rejection into `404 Not Found`.

No flow calls another system. The file also declares an `http:request-config` (`HttpRequestConfig`, host `0.0.0.0`, port `8081`, `http-restful-resource.xml:5`) that no flow references; it is not carried (AAP 0.4.5).

## Endpoints and triggers

Three listeners on `0.0.0.0:${http.port}` (default `8081`), served by Undertow, each accepting only its `allowedMethods`:

| Path | Methods | Original flow | Response | Source |
|---|---|---|---|---|
| `/person` | `GET` | `retrieveAllPersonsFlow` | `200 OK`, `Content-Type: application/json`, body the compact JSON array of every stored person, in `java.util.HashMap` value order; `[]` when the store is empty | `http-restful-resource.xml:11-13` |
| `/person` | `POST` | `storePersonFlow` | `201 Created`, `Location: http://0.0.0.0:8081/person/<id>`, no `Content-Type`, `Content-Length: 74`, body exactly `{ "status": "success", "statusDescription": "person created successfully"}` (74 bytes); `<id>` is the counter after `incrementAndGet`, `1` for the first person | `http-restful-resource.xml:16-24` (`Location` at 18) |
| `/person/{personId}` | `GET` | `retrievePersonFlow`, person found | `200 OK`, no `Content-Type`, `Content-Length` of the body, body the stored person, for example `{"firstname":"Tito","lastname":"Lamela","address":{"streetAddress":"Lincoln St.","city":"San Francisco","state":"CA","zipCode":"90401"},"age":12}` (145 bytes) | `http-restful-resource.xml:28-36` |
| `/person/{personId}` | `GET` | `retrievePersonFlow`, no person under the id | `HTTP/1.1 404 Not Found`, no `Content-Type`, `Content-Length: 0`, empty body: the rollback branch's `errorStatusCode` `404` and `errorReasonPhrase` `Not Found` on the status line (`exception/PersonExceptionHandler` over SC-2 `config/ReasonPhrase.set("Not Found")`) | `http-restful-resource.xml:29,33-35,37-42` |
| `/person/{personId}` | `GET` | `retrievePersonFlow`, id not an integer (`/person/abc`) | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, here `{ "message": "For input string: \"abc\"" }`: `Integer.valueOf` fails and no exception branch matches (SC-1, AAP 0.4.7) | `http-restful-resource.xml:31,37-42` |
| `/person` | `POST` | `storePersonFlow`, body absent or empty, malformed (`{`), a JSON array, string, number or boolean (`[]`, `"x"`, `42`, `true`), an object carrying a property `Person` does not declare, or an object with a property value its declared type cannot take (`{"age": "abc"}`, `{"address": 5}`, `{"address": {"zip": "1"}}`) | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<Jackson's parse message>" }` (SC-1, AAP 0.4.7); no id is consumed and nothing is stored | `http-restful-resource.xml:21` |
| `/person` | `POST` | `storePersonFlow`, body the four bytes of the JSON literal `null` | the same `201 Created` as for a person: `Location: http://0.0.0.0:8081/person/<id>`, no `Content-Type`, `Content-Length: 74` and the success body. Jackson reads the literal as a Java `null` without failing, so the id is consumed and `null` is stored under it: `GET /person/<id>` then answers `200 OK`, no `Content-Type`, `Content-Length: 4`, body `null`, and `GET /person` lists `null` among the stored values | `http-restful-resource.xml:21-24` |
| `/person` | `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE`, any other | none | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /person` (SC-1, AAP 0.4.7) | `allowedMethods` at `http-restful-resource.xml:11,16` |
| `/person/{personId}` | every method but `GET` (`POST` included) | none | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: <path>` (SC-1) | `allowedMethods` at `http-restful-resource.xml:28` |
| any other path (`/people`, `/person/1/x` and `/error` included) | any | none | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

- `GET /person/{personId}` logs `Person id is <id>` at ERROR before the existence check, as the original `logger level="ERROR"` did (`http-restful-resource.xml:32`); a non-integer id fails before the log line. The 404 adds the ERROR line `Rollback exception strategy: person <id> not found: Message has been rejected by filter. Message payload is of type: NullPayload`, and each 500 adds SC-1's one-line ERROR entry naming the method, the matched route and the exception types.
- The `Location` header keeps the original's literal host and port `0.0.0.0:8081` (`PersonController.LOCATION_PREFIX`), whatever `http.port` the application listens on.
- Each success `Content-Type` follows the payload its flow ends with. `retrieveAllPersonsFlow` ends in an `object-to-json-transformer` (`http-restful-resource.xml:13`), so `GET /person` answers `application/json`. `storePersonFlow` ends in a literal `set-payload` (line 24) and `retrievePersonFlow` in a `set-payload` of the stored person (line 36) with no transformer after it; neither declares a MIME type, and the Mule listener sent no `Content-Type` for such a payload. `PersonController` writes those two replies straight to the servlet response with their `Content-Length`, after `PersonService` returns, so no message converter adds a `Content-Type` and a failure still reaches `PersonExceptionHandler` or SC-1 on an uncommitted response.
- The person JSON keeps the original Jackson 1.x behaviour: compact output, properties in the order `firstname`, `lastname`, `address`, `age`, `null` values written, unknown properties rejected, and a numeric string coerced for `age` (`"age": "12"` is stored as `12`).
- No request is answered with 406 or 415: no mapping declares `produces` or `consumes`, so any request `Content-Type` and `Accept` is accepted. A JSON body sent as `application/x-www-form-urlencoded` (curl's default for `--data-binary` without `-H`) is rebuilt by Spring from the parsed form parameters and fails with the 500; the Mule 3.8 listener also decoded such a body into a parameter map that its JSON transformer rejected.
- Method names are matched ignoring case, as Mule's `MethodRequestMatcher` did: `get` and `post` run the flows exactly as `GET` and `POST` (`config/ListenerMethodFilter`). `HEAD` and `OPTIONS` get the 405 through explicit rejecting mappings (`PersonController.rejectPersonsMethod`, `rejectPersonMethod`) instead of Spring's own replies, and `TRACE` through `spring.mvc.dispatch-trace-request`. A CORS pre-flight is answered like any other `OPTIONS` request, with the 405 and no `Access-Control-*` or `Vary` header, because the original listeners applied no CORS policy (`config/PreflightDispatchConfig`).
- Data lives in `service/PersonService` (a Spring singleton) and is lost on restart, as with the original singleton beans (AAP 0.4.3). Access to the store is synchronized.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Tier 2A verification points

The source does not settle these behaviours. When a captured fixture shows different bytes, the fixture governs and the owner named in `src/test/resources/fixtures/SCENARIOS.md` (section Integrity rule) is changed to match (AAP 0.4.7, 0.8.4):

1. The 404 body of `get-person-not-found`. The plan chooses an empty body, because the `http:error-response-builder` sets only a status and a reason phrase and a GET carries no payload (AAP 0.7.5). The original README states that the filter rejection returns `Message has been rejected by filter. Message payload is of type: NullPayload`. Should the fixture show that text, `exception/PersonExceptionHandler` is changed to write `PersonNotFoundException.MESSAGE`, the single source of it, which it now only logs.
2. The `Content-Type` of each success reply: `application/json` for `retrieveAllPersonsFlow` (its `object-to-json-transformer`), and none for the untyped `set-payload` replies of `storePersonFlow` and `retrievePersonFlow` (`PersonController`).
3. The 500 for a non-integer id (`get-person-invalid-id`) or a malformed body (`post-person-malformed`, and the form-urlencoded case above): its status, its JSON body (the plan default of AAP 0.4.7) and its message text.
4. The platform texts `No listener for endpoint: <path>` (404) and `Method not allowed for endpoint: <path>` (405), and their `text/plain` type.
5. The `Location` port. It keeps the literal `8081`, so the capture deploys the original with `http.port=8085`, which tells the literal from the listener port and keeps the replay's single `Location` port substitution off it.

`SCENARIOS.md` lists every scenario with its exact request and holds the capture commands.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `${http.port}` had no value there; its README tells users to set port 8081. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`http-restful-resource.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`http-restful-resource.xml:4`) |
| `spring.application.name` | `String` | `exposing-a-restful-resource-using-the-HTTP-connector-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches the dispatcher and gets SC-1's 405 instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | its `TRACE` writes handler arguments, among them the handled exception whose message quotes a rejected person body |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write the posted person's body bytes |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` writes the handled exception's message, which quotes a rejected person body |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write response bodies: the person JSON of both `GET` flows and the `500` body |

- The four `logging.level` keys keep person data (names, address, zip code, age) out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`: a logger's own level outranks them. The original logs only `Person id is <id>` at ERROR, which stays. `config/SensitiveLogLevelsTest` asserts this.
- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project therefore has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Original-to-new key map: `http.port` → `http.port` (unchanged). The original has no other placeholder, and no key was renamed.
- Override the port on the command line, as a system property, or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments=--http.port=9090
  java -Dhttp.port=9090 -jar target/exposing-a-restful-resource-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar
  HTTP_PORT=9090 java -jar target/exposing-a-restful-resource-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`exposing-a-restful-resource-using-the-HTTP-connector`) | Java construct (`com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`http-restful-resource.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml`; Undertow sends the custom reason phrase, which Tomcat 10.1 would drop (AAP 0.4.9) |
| `http:request-config HttpRequestConfig` (`http-restful-resource.xml:5`) | not carried: no flow references it (AAP 0.4.5) |
| `spring:bean` `java.util.HashMap` `personDataStore` and `java.util.concurrent.atomic.AtomicInteger` `personId`, both singletons (`http-restful-resource.xml:6-9`) | the fields `personDataStore` (`HashMap<Integer, Person>`) and `personId` (`AtomicInteger`) of the singleton `service/PersonService`, the same types, so the store's values iterate in the original order |
| three `http:listener`s with `allowedMethods` `GET` `/person`, `POST` `/person`, `GET` `/person/{personId}` (`http-restful-resource.xml:11,16,28`) | `controller/PersonController`: `getPersons` (`@GetMapping("/person")`), `storePerson` (`@PostMapping("/person")`), `getPerson` (`@GetMapping("/person/{personId}")`), which only bind the request and write the response; `rejectPersonsMethod` and `rejectPersonMethod` answer `HEAD` and `OPTIONS` with the 405; `config/ListenerMethodFilter` matches `GET` and `POST` ignoring case |
| `set-payload #[app.registry.'personDataStore'.values()]` and `json:object-to-json-transformer` (`http-restful-resource.xml:12-13`) | `service/PersonService.retrieveAllPersonsJson` |
| `http:response-builder statusCode="201"` with `Location: http://0.0.0.0:8081/person/#[personId]` (`http-restful-resource.xml:17-19`) | `PersonController.storePerson`, status `CREATED` and `LOCATION_PREFIX + id` |
| `json:json-to-object-transformer returnClass="org.mule.examples.Person"`, `set-variable personId #[app.registry.'personId'.incrementAndGet()]`, `expression-component personDataStore.put(personId, payload)` (`http-restful-resource.xml:21-23`) | `service/PersonService.storePerson`, in that order, so a body that fails to parse consumes no id |
| `set-payload` success text (`http-restful-resource.xml:24`) | `PersonService.STORE_SUCCESS_RESPONSE` |
| `set-variable personId #[Integer.valueOf(message.inboundProperties.'http.uri.params'.personId)]`, `logger level="ERROR" message="Person id is #[personId]"`, `set-payload #[app.registry.'personDataStore'.get(personId)]` (`http-restful-resource.xml:31,32,36`) | `service/PersonService.retrievePersonJson`: `Integer.valueOf`, SLF4J `LOGGER.error("Person id is {}", id)`, lookup |
| `json-to-object-transformer`, `object-to-json-transformer` and the codehaus Jackson auto-transformer of the found person | `PersonService`'s own plain Jackson `ObjectMapper` (not Boot's, which ignores unknown properties): compact output, unknown properties rejected |
| `message-filter throwOnUnaccepted="true"` over `expression-filter #[app.registry.'personDataStore'.containsKey(personId)]` (`http-restful-resource.xml:33-35`) | `exception/PersonNotFoundException`, thrown by `PersonService.retrievePersonJson` after the ERROR log when `containsKey` is false |
| `choice-exception-strategy` with its `rollback-exception-strategy` (`exception.causedBy(org.mule.api.routing.filter.FilterUnacceptedException)`) setting `errorStatusCode` `404` and `errorReasonPhrase` `Not Found`, read by `http:error-response-builder` (`http-restful-resource.xml:29,37-42`) | `exception/PersonExceptionHandler.handlePersonNotFound` (`@RestControllerAdvice`, `@Order(0)`, ahead of SC-1): the constants `ERROR_STATUS_CODE` and `ERROR_REASON_PHRASE`, the phrase written through SC-2 `config/ReasonPhrase.set`, an empty 404 built through `ResponseEntity`. It declares only `PersonNotFoundException`, the type the branch names |
| `org.mule.examples.Person`, `org.mule.examples.Address` with codehaus `@JsonAutoDetect` | `model/Person`, `model/Address`, ported with fasterxml `@JsonAutoDetect`; `Person` adds `@JsonPropertyOrder({"firstname", "lastname", "address", "age"})` to pin the order the original wrote |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` (`@Order(Ordered.LOWEST_PRECEDENCE)`) and `exception/ErrorResponse` |
| Listener semantics: no CORS policy | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `http-restful-resource.xml`) | `ExposingARestfulResourceUsingTheHTTPConnectorApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `HttpRestfulResourceIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`) | `HttpRestfulResourceTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

Shared components, each copied with only its `package` line changed (register in the root `DECISIONS.md`):

| ID | Files in this project | Copied from |
|---|---|---|
| SC-1 | `exception/ErrorResponse`, `exception/GlobalExceptionHandler`, test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-2 | `config/ReasonPhrase`, test `config/ReasonPhraseTest` | `implementing-a-choice-exception-strategy-java` |
| ST-1 | `src/test/java/.../support/HttpTestClient` | `rest-api-with-apikit-java` |
| ST-2 | `src/test/java/.../FixtureParityTest`; the headings, table header and prose sections of `src/test/resources/fixtures/SCENARIOS.md` (the rows are this project's) | `hello-world-java` |

`config/PreflightDispatchConfig` and its `config/PreflightDispatchConfigTest` are the same listener-semantics classes the other HTTP projects carry, byte for byte apart from the `package` line; `config/ListenerMethodFilter` and the wire tests `config/ListenerMethodFilterTest` and `config/PreflightDispatchListenerTest` are written for this project's three listeners.

## Behaviours stubbed or externalized

None. The original reaches no external system and holds no credential, so nothing is stubbed, mocked at runtime or moved to configuration, and no behaviour was dropped. The unused `http:request-config` is not carried (AAP 0.4.5). Studio metadata (`mule-project.xml`) and the original's `src/test/resources/log4j2-test.xml` are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/exposing-a-restful-resource-using-the-HTTP-connector-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081
java -jar target/exposing-a-restful-resource-using-the-HTTP-connector-java-1.0.0-SNAPSHOT.jar
```

Expected exchanges, the original README's steps 4 to 8 against a freshly started application (the `Date` values vary):

```console
$ curl -i -X POST -H 'Content-Type: application/json' --data-binary '{"firstname":"John","lastname":"Doe","age": "12","address":{"streetAddress":"Lincoln St.","city":"San Francisco","state":"CA","zipCode":"90401"}}' http://localhost:8081/person
HTTP/1.1 201 Created
Connection: keep-alive
Location: http://0.0.0.0:8081/person/1
Content-Length: 74
Date: <current date>

{ "status": "success", "statusDescription": "person created successfully"}

$ curl -i http://localhost:8081/person/1
HTTP/1.1 200 OK
Connection: keep-alive
Content-Length: 142
Date: <current date>

{"firstname":"John","lastname":"Doe","address":{"streetAddress":"Lincoln St.","city":"San Francisco","state":"CA","zipCode":"90401"},"age":12}

$ curl -i http://localhost:8081/person
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 144
Date: <current date>

[{"firstname":"John","lastname":"Doe","address":{"streetAddress":"Lincoln St.","city":"San Francisco","state":"CA","zipCode":"90401"},"age":12}]

$ curl -i http://localhost:8081/person/999
HTTP/1.1 404 Not Found
Connection: keep-alive
Content-Length: 0
Date: <current date>

```

- The `Content-Type: application/json` header is required on the POST: without `-H`, curl sends `application/x-www-form-urlencoded` and the reply is the 500 (see [Endpoints and triggers](#endpoints-and-triggers)). `--data-binary @<file>` sends a file's bytes unchanged, for example `--data-binary @src/test/resources/person.json`.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project and calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `HttpRestfulResourceTest` | the ported `HttpRestfulResourceIT` (`exposing-a-restful-resource-using-the-HTTP-connector/src/test/java/org/mule/examples/HttpRestfulResourceIT.java:55-87`), with its setup and comparison semantics on a fresh application context per test: before each test `POST /person` stores `person.json`; `getPerson`: `GET /person/1` equals `person.json` exactly; `postPerson`: a second `POST /person` returns exactly `{ "status": "success", "statusDescription": "person created successfully"}`; `getPersons`: `GET /person` contains `person.json`. It adds `getPersonNotFound`: `GET /person/999` → `HTTP/1.1 404 Not Found` with an empty body |
| `controller/PersonControllerTest` | on the wire through ST-1 `support/HttpTestClient`, which reads the raw status line: `GET /person` → `[]` on an empty store and the stored person after a POST; `POST /person` → `201 Created`, `Location: http://0.0.0.0:8081/person/1` (then `/2`) although the listener runs on a random port, no `Content-Type`, `Content-Length: 74` and the exact body, also with a `text/plain` request and, with no `Content-Type` either, with `Accept: application/xml`; a malformed body and an unknown property → the JSON 500, consuming no id and storing nothing; `GET /person/1` → `200 OK`, no `Content-Type`, `Content-Length: 145` and the exact `person.json` bytes; `GET /person/999` → `HTTP/1.1 404 Not Found`, no body, no `Content-Type`; `GET /person/abc` → 500 `{ "message": "For input string: \"abc\"" }`; `PUT`, `HEAD`, `OPTIONS` on `/person` and `POST`, `HEAD` on `/person/1` → 405; `/people` → the no-listener 404 |
| `service/PersonServiceTest` | without Spring: sequential ids; the success constant; a body that fails to parse (truncated `{`, empty, and a Java `null` byte array, which the service reads as empty) or carries an unknown property is rejected and consumes no id; `"age": "12"` coerced to `12`; a stored person round-trips to the exact `person.json` bytes; `[]`, a single person and `HashMap` value order for the collection; a missing id raises `PersonNotFoundException` after the ERROR line `Person id is <id>`; a non-integer id raises `NumberFormatException` and logs nothing |
| `exception/PersonExceptionHandlerTest` | on Undertow, `GET /person/999` → `HTTP/1.1 404 Not Found`, no body, no `Content-Type`; with standalone MockMvc registering SC-1 first, `@Order` still lets this handler win, while another exception reaches SC-1's 500; the constants `404` and `Not Found`, the single declared exception type, the ERROR log line, and the exception's id and filter message |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR line per unhandled exception that names the matched route, never the request path, and no exception message except a JDBC driver's bounded diagnostic; an `Error` reported with its own message |
| `config/ReasonPhraseTest` | SC-2 on Undertow: a custom phrase on the raw status line, with an error and with a success status; no leak into the next exchange; `null` restoring the standard phrase; a no-op outside a request |
| `config/ListenerMethodFilterTest` | the filter alone, then on the wire: `get`, `Get`, `post`, `pOsT` and other letter cases run the three flows and their failure branches byte for byte as `GET` and `POST`; other method spellings (`head`, `options`, `put`, `trace`, unknown tokens) keep the 405 |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/SensitiveLogLevelsTest` | the four `logging.level` keys bound from `application.yml` as Spring Boot binds them, each `INFO` and held so by the running context; with the root at `TRACE`, storing a person carrying a dummy sentinel in every field, reading it alone and in the collection, and posting a rejected person write no sentinel, as text or as logged body bytes, through any logger outside `com.mulesoft.examples`, while `Person id is <id>` is still logged at ERROR; controls with all four levels, or any one, removed show exactly those loggers writing the sentinels |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/person` or `/person/{personId}` gets exactly the plain `OPTIONS` reply (the 405), runs no flow and carries no `Access-Control-*` or `Vary` header; a pre-flight to an unserved path gets the no-listener 404; cross-origin requests run the three flows without CORS headers |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

Coverage gate: JaCoCo fails `mvn clean package` and `mvn clean install` when LINE coverage over `service/**` and `mapper/**` is below 80% (report in `target/site/jacoco`). The project has no `mapper` package; the include is kept so the gate reads the same in every converted project.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/exposing_a_restful_resource_using_the_http_connector/FixtureParityTest.java` is the shared replay engine. `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds the fixture contract, the comparison rules, the Tier 2A capture procedure and the owner of each scenario's response; the root `DECISIONS.md` records the Tier 2A state and its escalation. Fixtures are captured from the running original, never hand-written, and never edited to match the Java output: a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

This project's scenarios, exactly as `SCENARIOS.md` names them, all `deterministic`. Every fixture's `state` is `{}` and `upstream` `[]`, because the flows reach no external store and make no outbound call. The person store and the id counter live in memory, so the rows form one sequence: they are captured against a freshly started original and replayed in one application context, in this order.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-persons-empty` | `http` | `deterministic` | `GET /person`, empty body | `UNVERIFIED-uncaptured` |
| `post-person` | `http` | `deterministic` | `POST /person`, header `Content-Type: application/json`, body = the bytes of `person.json` | `UNVERIFIED-uncaptured` |
| `get-person-found` | `http` | `deterministic` | `GET /person/1`, empty body | `UNVERIFIED-uncaptured` |
| `get-persons` | `http` | `deterministic` | `GET /person`, empty body | `UNVERIFIED-uncaptured` |
| `get-person-not-found` | `http` | `deterministic` | `GET /person/999`, empty body | `UNVERIFIED-uncaptured` |
| `get-person-invalid-id` | `http` | `deterministic` | `GET /person/abc`, empty body | `UNVERIFIED-uncaptured` |
| `post-person-malformed` | `http` | `deterministic` | `POST /person`, header `Content-Type: application/json`, body `{` | `UNVERIFIED-uncaptured` |
| `put-person-method-not-allowed` | `http` | `deterministic` | `PUT /person`, empty body | `UNVERIFIED-uncaptured` |
| `post-person-id-method-not-allowed` | `http` | `deterministic` | `POST /person/1`, header `Content-Type: application/json`, body = the bytes of `person.json` | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /people`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`: no flow reads a clock, a random value or a generated name.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| exposing-a-restful-resource-using-the-HTTP-connector-java | exposing-a-restful-resource-using-the-HTTP-connector | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 229 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 229 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 23 of 23 lines of `service/PersonService` (the project has no `mapper` package). Total project LINE coverage: 97.9% (281 of 287 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=10`, `failed=0`.

Tier 2A is blocked: `DECISIONS.md` records "Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed". Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `HttpRestfulResourceTest` carries the original committed assertions with their comparison semantics: `GET /person/1` equals `person.json`, `POST /person` equals the success text, and `GET /person` contains `person.json`.
- `PersonControllerTest`, `PersonServiceTest`, `PersonExceptionHandlerTest` and `ListenerMethodFilterTest` cover every reachable branch, on the wire and in isolation.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the flows and the platform defaults, but not that its bytes equal what the original runtime sent.
- No original assertion covers: the rollback branch's 404 `Not Found` (`get-person-not-found`), the 500 for a non-integer id (`get-person-invalid-id`) and for a malformed POST body (`post-person-malformed`), the method-not-allowed 405 (`put-person-method-not-allowed`, `post-person-id-method-not-allowed`), the no-listener 404 (`get-unknown-path`), the empty collection (`get-persons-empty`), and, on the branches it does cover, the status codes, `Location` header and `Content-Type`: the original test compares payloads only. These are covered only by the new tests.
- Unverified scenarios, until captured: `get-persons-empty`, `post-person`, `get-person-found`, `get-persons`, `get-person-not-found`, `get-person-invalid-id`, `post-person-malformed`, `put-person-method-not-allowed`, `post-person-id-method-not-allowed`, `get-unknown-path`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `exposing-a-restful-resource-using-the-HTTP-connector/`.
