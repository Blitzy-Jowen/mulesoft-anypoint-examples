# upload-to-ftp-after-converting-json-to-xml-java

Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`upload-to-ftp-after-converting-json-to-xml`](../../upload-to-ftp-after-converting-json-to-xml/) (category General). The original folder `upload-to-ftp-after-converting-json-to-xml/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

JSON employee data is sent to the application through an HTTP endpoint, converted to XML (originally by the DataWeave transformer), and the XML is uploaded to a folder of an FTP server (`upload-to-ftp-after-converting-json-to-xml/README.md:11-12`). The single flow `main` of `upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:5-29` runs three elements in order: the HTTP listener on `/` (line 6), the "JSON to XML" `dw:transform-message` (lines 7-27, its `%dw 1.0` script at line 9) and the "Upload to FTP" `ftp:outbound-endpoint` (line 28). The XML is also the HTTP response. The flow declares no logger and no exception strategy.

The original README checks the result on the FTP server (`README.md:60-66`). The converted project keeps that check:

1. Start the application with the FTP server set (see [Configuration keys](#configuration-keys)) and post the sample body (see [Build, run and test](#build-run-and-test)).
2. Log in to that FTP server with the account you set as `ftp.user` and `ftp.password`.
3. Open the directory set as `ftp.path` (default `/Upload/`) and verify that `muleExample.xml` holds the XML the response returned.

## Endpoints and triggers

| Path | Methods | Host | Port | Response | Source |
|---|---|---|---|---|---|
| `/` | any, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | `200 OK`, `Content-Type: application/xml;charset=UTF-8`, body = the converted XML, the same bytes that were uploaded (637 bytes for the sample body) | `upload-to-ftp.xml:4,6-28` |
| any other path (`/other`, `/error` included) | any | `0.0.0.0` | `http.port` | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7); nothing is uploaded | listener platform default |
| `/`, when the transform or the upload fails | any | `0.0.0.0` | `http.port` | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "<exception message>" }`, logged at ERROR on one line (SC-1, AAP 0.4.7); no XML is returned | platform default for an unhandled exception: the flow declares no exception strategy |

Request: a JSON body of the shape `{"employees": {"employee": [ {"name": …, "lastName": …, "addresses": {"address": [ {…} ]}} ]}}`. The original README's sample body (`README.md:21-58`) is carried as `src/main/resources/employees.json`, copied unchanged; the original test's `message.json` is the same document with less whitespace.

Behaviour, in the flow's statement order (`service/EmployeeUploadService.process`):

1. `mapper/EmployeesXmlMapper`, the hand-written owner of the `%dw 1.0` script at `upload-to-ftp.xml:9` (AAP 0.5.8), builds `employees/employee(name, lastName, addresses/address)`. Each address object is written as it is, with its keys in input order (`address: $`). JSON objects keep their members in input order, repeated keys included, and decimals keep their scale. The bytes are written by SC-4 `mapper/DwXmlWriter`: the declaration `<?xml version='1.0' encoding='UTF-8'?>`, a line feed, tab indentation, LF line ends, nothing after the root end tag, UTF-8.
2. `client/FtpUploadClient` (Apache Commons Net 3.13.0) connects to `ftp.host:ftp.port`, requires a positive greeting, logs in, switches to binary file type and local passive mode (the Mule FTP connector defaults), changes to `ftp.path` without creating it, and `STOR`s `muleExample.xml`, overwriting an existing file. Connect, control-reply and data timeouts are 10 s, the endpoint's `responseTimeout="10000"`. Each request opens its own connection and closes it; nothing is retried.
3. The response is `200 OK` with the same XML bytes. The controller sends `Content-Type: application/xml;charset=UTF-8`, from the script's `%output application/xml encoding="UTF-8"`; the header's exact spelling as the Mule listener wrote it is a Tier 2A verification point.

- The upload is synchronous, as in the original, where a one-way outbound endpoint runs inside the request-response flow: a failed upload fails the request, and no XML is returned. A transform failure stops the flow before any upload.
- The body is read with the charset its `Content-Type` declares, the message encoding the Mule listener set from it; without one, UTF-8, UTF-16 or UTF-32 is detected from the bytes. The XML is UTF-8 either way. `Accept` and `Content-Type` never turn the request into a 406 or 415.
- Every method runs the flow. `EmployeeUploadController` maps `OPTIONS` explicitly (`uploadOptions`), so Spring's own `Allow` reply never answers it, and `spring.mvc.dispatch-trace-request` lets `TRACE` through. A request without a body, such as a plain `GET`, reaches the transform and fails with the 500 below. A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`) runs the flow like any other `OPTIONS` request and gets no `Access-Control-*` or `Vary` header, because the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- The 500 body carries the failure's message. FTP messages never contain the password:

  | Failure | Message in the 500 body |
  |---|---|
  | No body, or whitespace only | `JSON payload is empty` |
  | Malformed JSON, for example a truncated document | the JSON parser's message, for example `Unexpected end-of-input: expected close marker for Array (…)`; content after the root value gives `Trailing token (of type <token>) found after the JSON value` |
  | Root value not an object | `Expected a JSON object at the root but found <array\|string\|number\|boolean\|null>` |
  | Bytes not valid in the declared charset | `Malformed <charset> input at byte offset <n> of the JSON payload` (or `Unmappable …`) |
  | A value holding a character XML 1.0 does not allow, such as the JSON escape `\u0001` | `Invalid XML 1.0 character U+0001 in <location>`; nothing is uploaded |
  | `Content-Type` not a media type, or naming an illegal or unsupported charset | `Invalid mime type "<value>": <reason>`; the flow does not run |
  | `ftp.host` or `ftp.user` blank, `ftp.password` empty | `FTP host is not configured (ftp.host)`, `FTP user is not configured (ftp.user)`, `FTP password is not configured (ftp.password)`; no connection is made |
  | Server rejects the greeting, login, binary type, directory change or store | `FTP connect to <host>:<port> failed: <reply>`, `FTP login to <host>:<port> failed: <reply>`, `FTP set binary file type on <host>:<port> failed: <reply>`, `FTP change working directory to <ftp.path> on <host>:<port> failed: <reply>`, `FTP store muleExample.xml in <ftp.path> on <host>:<port> failed: <reply>` |
  | Connection refused, unknown host, timeout, reset | `FTP upload to <host>:<port> failed: <I/O message>`, for example `FTP upload to localhost:2121 failed: Connection refused` |

- Tier 2A verification points (AAP 0.4.7, `src/test/resources/fixtures/SCENARIOS.md`, Project notes): the `Content-Type` of the `200` reply; how the original treats an `employee` value that is not an array; the decoding of a body whose `Content-Type` declares a charset, inferred from the Mule 3.8.0 listener source rather than captured; the reply to an invalid `Content-Type`, which the conversion answers with the 500 above where the Mule 3.8.0 listener source answers `400 Bad Request`; and the bytes of the `404` and `500` defaults. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6), marked `(secret)` when it is one; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README tells users to set port 8081 and post to `http://localhost:8081`. |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | the `http:listener-config` port `${http.port}` (`upload-to-ftp.xml:4`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (`upload-to-ftp.xml:4`) |
| `spring.application.name` | `String` | `upload-to-ftp-after-converting-json-to-xml-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches `EmployeeUploadController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `ftp.host` | `String` | none (`${FTP_HOST:}`) | `ftp:outbound-endpoint host` (`upload-to-ftp.xml:28`); `ftp.host` in the original test | TODO, environment `FTP_HOST` (`# TODO: supply the FTP server host (original hard-coded ftp-server.demo.solarwinds.com)`) | FTP server host name or address |
| `ftp.port` | `int` | `21` | `ftp:outbound-endpoint port="21"` (line 28); `ftp.port` in the original test | default | FTP control port |
| `ftp.path` | `String` | `/Upload/` | `ftp:outbound-endpoint path="/Upload/"` (line 28); `ftp.path` in the original test | default | remote directory the file is stored in; it must exist, it is never created |
| `ftp.user` | `String` | none (`${FTP_USER:}`) | `ftp:outbound-endpoint user` (line 28); `ftp.user` in the original test | TODO, environment `FTP_USER` (`# TODO: supply the FTP user (original hard-coded the login in the flow)`) | the FTP service account |
| `ftp.password` | `String` | none (`${FTP_PASSWORD:}`) | `ftp:outbound-endpoint password` (line 28); `ftp.password` in the original test | TODO (secret), environment `FTP_PASSWORD` (`# TODO: supply the FTP password (original hard-coded the password in the flow)`) | the FTP service account's password |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write the posted employees JSON, as the decimal values of its bytes |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` and `TRACE` write the response body: the employees XML bytes, and the `500` body carrying a parser message that quotes the payload |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `LogLevel` | `INFO` | none | fixed | its `DEBUG` writes the handled exception's message, which quotes the rejected payload |
| `logging.level.org.springframework.web.method.HandlerMethod` | `LogLevel` | `INFO` | none | fixed | its `TRACE` writes handler arguments, the handled exception and its message included |
| `logging.level.org.springframework.web.HttpLogging` | `LogLevel` | `INFO` | none | fixed | the HTTP message converters write through this category wherever their own is held at `INFO` |

- The five `logging.level` keys keep the employees' names and addresses out of the log under a root `DEBUG` or `TRACE` level and under `--debug` or `--trace`: a logger's own level outranks them. The original has no logger, so it logged neither the posted JSON nor the uploaded XML. `config/SensitiveLogLevelsTest` asserts this.
- `config/FtpProperties` binds the `ftp.*` keys and carries the same defaults for `ftp.port` and `ftp.path`, so either key left out of `application.yml` keeps its value. Its `toString()` masks the password.
- Original-to-new map: the original flow hard-codes `host="ftp-server.demo.solarwinds.com"`, `port="21"`, `path="/Upload/"`, `user="demo"` and `password="demo"` on its `ftp:outbound-endpoint` (`upload-to-ftp.xml:28`). They move to `ftp.host`, `ftp.port`, `ftp.path`, `ftp.user` and `ftp.password`, the names the original test already read from `mule.test.properties` (`upload-to-ftp-after-converting-json-to-xml/src/test/java/org/mule/examples/UploadToFtpAfterConvertingJsonToXmlIT.java:69-74`). The test's `ftp.home`, a local data directory for an external server (line 72), is dropped, because MockFtpServer keeps an in-memory file system. `http.port` is unchanged.
- The public demo host `ftp-server.demo.solarwinds.com` and the `demo`/`demo` login are documented here only as the original's values. They are not defaults: no committed main configuration holds them (AAP 0.4.6). To reproduce the original target, supply them through the environment or `--key=value` arguments.
- Not configurable, as in the original: the file name `muleExample.xml` (the endpoint's `outputPattern`, `FtpUploadClient.OUTPUT_PATTERN`), the 10 s timeouts, binary type and passive mode.
- Unset values do not stop the application from starting, because startup contacts no FTP server. They fail each request instead: the client checks `ftp.host`, then `ftp.user`, then `ftp.password`, before it connects, and answers the 500 above.
- Unit tests read `src/test/resources/application-test.yml` (Spring profile `test`): host `localhost`, user `demo`, password `demo`, path `/Upload/`, the original demo login. Each Spring-context test registers its in-process MockFtpServer's port as `ftp.port` through `@DynamicPropertySource`. `FtpUploadClientTest`, which starts no Spring context, sets the same values on `FtpProperties` directly.
- The project has no `application-it.yml`: it is not one of the connector projects.
- Override any key on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port`):

  ```sh
  export FTP_HOST=<FTP host> FTP_USER=<FTP user> FTP_PASSWORD=<FTP password>
  mvn spring-boot:run -Dspring-boot.run.arguments="--http.port=9090 --ftp.port=2121"
  HTTP_PORT=9090 java -jar target/upload-to-ftp-after-converting-json-to-xml-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`upload-to-ftp-after-converting-json-to-xml`) | Java construct (`com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`upload-to-ftp.xml:4`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (line 6) | `controller/EmployeeUploadController`, `@RequestMapping("/")`: `upload` (every method but `OPTIONS`) and `uploadOptions` (`OPTIONS`), both binding the raw body and its declared charset and returning the XML as `application/xml` in UTF-8 |
| flow `main` (lines 5-29) | `service/EmployeeUploadService.process`: transform, then upload, then the XML as the response payload |
| `dw:transform-message` "JSON to XML", `%dw 1.0` script (lines 7-27, script at line 9) | `mapper/EmployeesXmlMapper`, writing through SC-4 `mapper/DwXmlWriter`; no DataWeave runtime |
| `ftp:connector name="FTP"` (line 3) and `ftp:outbound-endpoint` "Upload to FTP" (line 28) | `client/FtpUploadClient` (Apache Commons Net `FTPClient`) and `config/FtpProperties` (`ftp.*` keys) |
| Default exception strategy (no exception strategy declared) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `upload-to-ftp.xml`) | `UploadToFtpAfterConvertingJsonToXmlApplication` (`@SpringBootApplication`, `@EnableConfigurationProperties(FtpProperties.class)`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `UploadToFtpAfterConvertingJsonToXmlIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, `mule.test.properties`) | `UploadToFtpAfterConvertingJsonToXmlTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, profile `test`, ST-1 `support/HttpTestClient` and an in-process MockFtpServer) |

Shared components, each copied from its reference implementation with only the `package` line changed (AAP 0.4.4; register in `DECISIONS.md` section 4):

| Component | Files in this project | Copied from |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`, test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-4 | `mapper/DwXmlWriter`, test `mapper/DwXmlWriterTest` (the only SC-4 class this project uses) | `web-service-consumer-java` |
| ST-1 | test `support/HttpTestClient` | `rest-api-with-apikit-java` |
| ST-2 | test `FixtureParityTest`; the table contract of `src/test/resources/fixtures/SCENARIOS.md`, with this project's rows | `hello-world-java` |
| CORS pre-flight dispatch | `config/PreflightDispatchConfig`, tests `config/PreflightDispatchConfigTest` and, written for this listener, `config/PreflightDispatchListenerTest` | `hello-world-java` |

Not carried from the original folder (reference only, AAP 0.2.2, 0.4.5): `catalog/**`, `mule-project.xml`, `src/main/app/mule-deploy.properties`, `src/test/resources/sample_data/**`, `src/test/resources/log4j2-test.xml`, and the test XSDs `src/test/resources/customers.xsd` and `employees.xsd`, which no test reads.

## Behaviours stubbed or externalized

- FTP target. The host, port, directory and login the original hard-codes are externalized to the `ftp.*` keys, with TODO values for the host and the credentials (AAP 0.4.6); see [Configuration keys](#configuration-keys).
- FTP transport. The Mule FTP transport's connector defaults (binary, passive) and the endpoint's `responseTimeout` are applied explicitly by `FtpUploadClient`. It holds no connection pool: the Mule connector could reuse a pooled connection between requests, while each converted request opens and closes its own. The bytes stored and the outcome of each request are unaffected.
- Tests replace the public FTP server with MockFtpServer 3.2.0 `FakeFtpServer` on a loopback port (AAP 0.8.3), so `mvn test` needs no credential and contacts no external host. The original public demo server `ftp-server.demo.solarwinds.com` is unreachable from the build host (`DECISIONS.md` section 6) and may no longer exist. Live behaviour is verified only through Tier 2A capture of the original.
- Not a connector project (AAP 0.2.1): no failsafe plugin, no `*IT` live test, no `application-it.yml`, Tier 2B `N/A`.
- No behaviour was dropped.

## Build, run and test

Run from `java-conversions/upload-to-ftp-after-converting-json-to-xml-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
FTP_HOST=<FTP host> FTP_USER=<FTP user> FTP_PASSWORD=<FTP password> mvn spring-boot:run    # listener on 0.0.0.0:8081
java -jar target/upload-to-ftp-after-converting-json-to-xml-java-1.0.0-SNAPSHOT.jar
```

Expected exchange with an FTP server set and reachable (the `Date` value varies; the uploaded `/Upload/muleExample.xml` holds the same 637 bytes as the body):

```console
$ curl -i -X POST -H 'Content-Type: application/json' --data @src/main/resources/employees.json http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/xml;charset=UTF-8
Content-Length: 637
Date: <current date>

<?xml version='1.0' encoding='UTF-8'?>
<employees>
	<employee>
		<name>John</name>
		<lastName>Doe</lastName>
		<addresses>
			<address>
				<street>123 Main Street</street>
				<zipCode>111</zipCode>
			</address>
			<address>
				<street>987 Cypress Avenue</street>
				<zipCode>222</zipCode>
			</address>
		</addresses>
	</employee>
	<employee>
		<name>Jane</name>
		<lastName>Doe</lastName>
		<addresses>
			<address>
				<street>345 Main Street</street>
				<zipCode>111</zipCode>
			</address>
			<address>
				<street>654 Sunset Boulevard</street>
				<zipCode>333</zipCode>
			</address>
		</addresses>
	</employee>
</employees>
```

Without the FTP settings the same call answers, before any connection is attempted:

```console
$ curl -i -X POST -H 'Content-Type: application/json' --data @src/main/resources/employees.json http://localhost:8081/
HTTP/1.1 500 Internal Server Error
Connection: keep-alive
Content-Type: application/json
Content-Length: 54
Date: <current date>

{ "message": "FTP host is not configured (ftp.host)" }
```

- `curl --data` strips the line breaks of the file; `--data-binary` sends it byte for byte. Both bodies give the same XML.
- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `UploadToFtpAfterConvertingJsonToXmlTest` | the ported `UploadToFtpAfterConvertingJsonToXmlIT` (`upload-to-ftp-after-converting-json-to-xml/src/test/java/org/mule/examples/UploadToFtpAfterConvertingJsonToXmlIT.java:90-98`): `POST /` with `message.json` returns 200, and the body equals `reply.xml` once all whitespace is removed from both, the original's comparison. The committed `reply.xml` lacks the spaces inside the street names (`123MainStreet`), so only that whitespace-free comparison holds. An in-process `FakeFtpServer` with `/Upload` and the `demo` account takes the place of the external server |
| `controller/EmployeeUploadControllerTest` | on the wire through ST-1, with every bean real and a `FakeFtpServer`: `POST /` → `HTTP/1.1 200 OK`, `application/xml` in UTF-8, the exact 637-byte document, which is also the only file in `/Upload/` (`muleExample.xml`); the same for `PUT`, `PATCH` and `DELETE`; malformed JSON, `GET` without a body, a rejected FTP login and a missing `/Upload/` → 500 `application/json` `{ "message": … }` with nothing uploaded and no directory created; `/other` → 404 `text/plain` `No listener for endpoint: /other` |
| `controller/EmployeeUploadCharsetBindingTest` | the charset of the first `Content-Type` value (parameter name in any letter case, quoted or not) reaches the service with the body unchanged, and none gives `null`; a `Content-Type` that is not a media type or names an illegal or unsupported charset → 500 `Invalid mime type …` before the service runs |
| `service/EmployeeUploadServiceTest` | the transform runs before the upload; the upload is named `muleExample.xml` and its bytes are the returned bytes; an upload failure propagates unchanged after one attempt; malformed or undecodable input fails before any upload |
| `mapper/EmployeesXmlMapperTest` | `message.json` and `employees.json` give the exact document, which equals `reply.xml` without whitespace; address objects copied in key order; every documented input decision (missing, `null` or non-array `employee` and `address`, repeated keys, numbers and booleans, array-valued names); invalid input and declared-charset decoding |
| `mapper/DwXmlWriterTest` | SC-4: the single-quoted declaration, tab layout, LF line ends and no line feed after the root end tag; escaping, namespaces, attributes and value trees; characters XML 1.0 does not allow are rejected |
| `client/FtpUploadClientTest` | against a `FakeFtpServer`, without a Spring context: the file is written byte for byte and overwrites an existing one; a wrong password, a missing directory and a refused connection fail without writing, without retry and without the password in the message; binary type, passive mode and the 10 s timeouts; one connection, closed on every outcome; an unset host, user or password fails before connecting |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception that names the matched route, never the request path, carries no exception message except a JDBC driver's escaped, bounded diagnostic, and no stack trace; an `Error` reported with its own message |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/` runs the flow and gets exactly the plain `OPTIONS` reply (200 with the XML as `application/xml;charset=UTF-8`, no `Access-Control-*` or `Vary`); a pre-flight to `/unknown` gets the no-listener 404 |
| `config/SensitiveLogLevelsTest` | the five `logging.level` keys bound from `application.yml` as Spring Boot binds them; with the root at `TRACE` and those levels applied through Spring Boot's logging system, uploading a sentinel employee to a `FakeFtpServer` with a sentinel login and rejecting a malformed payload whose parser message quotes a sentinel answer as usual and write no sentinel, as text or as logged body bytes, through any logger outside `com.mulesoft.examples` and the stand-in server; a control with the levels cleared shows exactly the two body processors, `ExceptionHandlerExceptionResolver` and `HandlerMethod` writing them |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity (ST-2)

`FixtureParityTest` and the table contract of `src/test/resources/fixtures/SCENARIOS.md` are copied from the reference implementation in `hello-world-java`, whose README documents the fixture format, the replay drivers, the comparison rules and the summary statuses: [Fixture parity (ST-2 reference implementation)](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). This project's [`SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md) adds what is specific to it:

- `state` holds only `properties`: every fixture sets `ftp.host` `localhost`, `ftp.port` `${freePort:ftp}`, `ftp.user` and `ftp.password` `demo`, and `ftp.path` `/Upload/`, so the Java client gets a replay-owned target.
- `upstream` is `[]` for every scenario whose original uploaded no file, since the flow makes no HTTP or SOAP call. ST-2 has no FTP driver, so a scenario whose original uploaded a file (`post-root-employees`) omits `upstream` and is reported `UNVERIFIED-no-upstream`: its uploaded bytes are kept as capture evidence, but the replay cannot check them, so it counts as unverified until ST-2 has an FTP driver.
- Capture caveat: the original hard-codes the FTP host `ftp-server.demo.solarwinds.com` (`upload-to-ftp.xml:28`) and its folder stays unchanged, so no property can repoint it. A Tier 2A capture must resolve that host name to a local FTP server (hosts-file entry or DNS override on the Mule runtime host) listening on port 21 with the `demo`/`demo` account and an empty `/Upload/`, or reach the live public server if it still exists. Otherwise the FTP-side effect stays uncaptured and counts as unverified.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `post-root-employees` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body = `message.json`; FTP server with `demo`/`demo` and an empty `/Upload/` | `UNVERIFIED-uncaptured` |
| `post-root-malformed-json` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body `{"employees": {"employee": [` | `UNVERIFIED-uncaptured` |
| `get-root-no-body` | `http` | `deterministic` | `GET /`, empty body | `UNVERIFIED-uncaptured` |
| `post-root-ftp-unavailable` | `http` | `deterministic` | `POST /`, header `Content-Type: application/json`, body = `message.json`; no FTP server listening | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /other`, empty body | `UNVERIFIED-uncaptured` |

- Once captured, `post-root-malformed-json`, `get-root-no-body`, `post-root-ftp-unavailable` and `get-unknown-path` are compared byte for byte on status, reason, `Content-Type` and body. `post-root-employees` stays unverified for the missing FTP driver, as above.
- No scenario of this project is `unreachable` or `nondeterministic`.
- With no fixtures `FixtureParityTest` reports skipped (`no Tier 2A fixtures captured`), never failed.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| upload-to-ftp-after-converting-json-to-xml-java | upload-to-ftp-after-converting-json-to-xml | General | PASS | PASS | 99.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

The same row and labels belong in the root `SUMMARY.md` (AAP 0.8.7).

Evidence of the run behind this row, 2026-10-27 (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 303 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`, skipped by assumption while no fixture is captured); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 303 tests, 0 failures, 0 errors, 1 skipped.
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 291 of 294 lines: every line of `service/EmployeeUploadService` (7), of `mapper/DwXmlWriter` with its nested `Element` (170) and of the nested classes of `mapper/EmployeesXmlMapper` (10), and 104 of the 107 lines of `EmployeesXmlMapper` itself. Total project LINE coverage: 91.7% (552 of 602 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=5`, `failed=0`.
- A runtime check of the jar against a local MockFtpServer returned the `200 OK` exchange shown under [Build, run and test](#build-run-and-test), with the uploaded `muleExample.xml` byte-equal to the body; with the server stopped it returned `500` `{ "message": "FTP upload to localhost:<port> failed: Connection refused" }`, and without the FTP settings the `500` shown there. The boundary integration run on the combined tree re-measures this row.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`, as recorded in `DECISIONS.md` (section 9). No fixture can be captured from the original, so parity rests on the fallback:

- `UploadToFtpAfterConvertingJsonToXmlTest` carries the original committed assertion: the response to `POST /` with `message.json` equals `reply.xml` with all whitespace removed.
- `EmployeeUploadControllerTest`, `EmployeeUploadServiceTest`, `EmployeesXmlMapperTest` and `FtpUploadClientTest` cover every reachable branch: the upload path for every method, the transform failures, the FTP failures and the no-listener 404.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertion and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent, nor that its FTP session equals the one the Mule FTP transport ran.
- Branches no original assertion covers, verified only by the new tests: malformed or empty JSON → 500 (`post-root-malformed-json`, `get-root-no-body`), FTP failure → 500 (`post-root-ftp-unavailable`), unknown path → 404 (`get-unknown-path`), and methods other than `POST`. The original test made no FTP-side assertion either; the uploaded bytes are checked only by `EmployeeUploadControllerTest` and `FtpUploadClientTest`.
- Unverified scenarios, until captured: `post-root-employees`, `post-root-malformed-json`, `get-root-no-body`, `post-root-ftp-unavailable`, `get-unknown-path`. After capture, `post-root-employees` stays unverified until ST-2 has an FTP driver.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `upload-to-ftp-after-converting-json-to-xml/` (`NOTICE`: "This product includes software developed at MuleSoft, Inc. (http://www.mulesoft.com/).").
