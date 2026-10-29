# HTTP Multipart Request — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion, on Undertow, of the Mule EE 3.8 example [`http-multipart-request`](../../http-multipart-request/) (category General). The original folder `http-multipart-request/` stays unchanged beside this project and is the Tier 2A capture source.

## Original example

A browser submits a file through an HTML form. The application takes the file name from the `Content-Disposition` header of the uploaded part, logs the part's content type, stores the content in a directory of the file system and returns the content, which the browser shows or offers to save.

The flow file `http-multipart-request/src/main/app/http-multipart-request.xml`, the only entry of `config.resources`, holds one listener configuration (host `0.0.0.0`, port `${http.port}`, xml:4) and two flows on `/uploadFile`:

- `httpRenderFlow` (`allowedMethods="GET"`, xml:6-11) renders the form `uploadFile.html` through `parse-template`.
- `httpMultipartRequestFlow` (`allowedMethods="POST"`, xml:13-29) answers through a response builder with status `200` and reason phrase `File was uploaded.`. An expression component extracts the file name, a logger at level `ERROR` writes the part's `Content-Type`, `set-payload` takes the part content, and a `file:outbound-endpoint` writes it to `/tmp/<file name>`.

Neither flow declares an exception strategy or calls another system.

## Endpoints and triggers

All rows are served on host `0.0.0.0`, port `http.port` (default `8081`).

| Path | Methods | Behaviour | Response | Source |
|---|---|---|---|---|
| `/uploadFile` | `GET`, in any letter case (`get`, `Get`) | `httpRenderFlow`: renders `uploadFile.html`, which holds no expression | `HTTP/1.1 200 OK`, body = the 348 bytes of `uploadFile.html` unchanged, `Content-Length: 348`, no `Content-Type` header (the original set no MIME type for the payload; Tier 2A verification point) | xml:6-11 |
| `/uploadFile` | `POST`, in any letter case (`post`, `Post`) | `httpMultipartRequestFlow`: reads the multipart part `file` (the last one when several are sent); file name = the trimmed `Content-Disposition` part header from `indexOf("filename=") + 10` to `length() - 1`, the original MEL formula reproduced literally, which strips the surrounding quotes; the part's `Content-Type` header logged at `ERROR`; content written to `<upload.directory>/<file name>`, the directory created when missing and an existing file replaced | `HTTP/1.1 200 File was uploaded.`, body = the uploaded bytes, no `Content-Type` header (Tier 2A verification point) | xml:13-29 |
| `/uploadFile` | `POST` without a `file` part, or with a body that is not multipart | the file-name step fails; no exception strategy applies | `500 Internal Server Error`, `Content-Type: application/json`, body `{ "message": "Multipart part 'file' is not present in the request" }` (SC-1, AAP 0.4.7; Tier 2A verification point); no file stored | xml:19-20 |
| `/uploadFile` | `POST` whose write fails (file name `.`, `..`, empty, or longer than the file system allows) | the `file:outbound-endpoint` write fails; no exception strategy applies | `500`, `application/json`, `{ "message": "Streaming failed. Could not get output stream" }`, Mule's streaming-failure text, which names no path (SC-1, SC-6); no file stored | xml:27 |
| `/uploadFile` | any other method: `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE`, in any letter case | rejected before either flow runs | `405 Method Not Allowed`, `Content-Type: text/plain`, body `Method not allowed for endpoint: /uploadFile`, no `Allow` header; a `HEAD` reply carries no body (SC-1, AAP 0.4.7) | xml:8,15 |
| any other path (`/error` and `/uploadFile` with `;` segment parameters, such as `/uploadFile;x=1`, included) | any | no listener serves it; neither flow runs and no file is stored | `404 Not Found`, `Content-Type: text/plain`, body `No listener for endpoint: <path>` (SC-1, AAP 0.4.7) | listener platform default |

- File names are stored exactly as the formula yields them: neither unquoted, decoded nor sanitised, and `filename*` is not read. Part headers are decoded as ISO-8859-1, one character per byte, as Mule 3.8's JavaMail parser read them (`config/MultipartHeaderCharsetConfig`): a browser's windows-1252 `café-latin1.txt` is stored under that name and the UTF-8 bytes of `naïve.txt` as `naÃ¯ve.txt`.
- The name is joined to the upload directory as Mule's file connector joined them: a leading `/` keeps the file under the directory (`/qa/absolute.txt` → `<upload.directory>/qa/absolute.txt`), missing subdirectories are created, and `..` segments are kept as given, so such a name reaches a path outside the directory, as it did in the original.
- A part without a `Content-Disposition` header, or one too short for the formula's range, fails the request with `500 { "message": "<exception message>" }` (SC-1).
- Uploads have no size limit (`spring.servlet.multipart.max-file-size` and `max-request-size` are `-1`), as the Mule listener imposed none.
- A CORS pre-flight is answered like any other `OPTIONS` request, with the 405 above and no `Access-Control-*` or `Vary` header; the original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- A path carrying `;` segment parameters (`/uploadFile;x=1`, `/uploadFile;jsessionid=ABC`, `/uploadFile;`) is not `/uploadFile`: every method gets the no-listener 404 naming the path with its parameters, `No listener for endpoint: /uploadFile;x=1`, neither flow runs and an upload to it stores no file. The Mule listener matched the raw path, query removed, literally, where Spring MVC ignores segment parameters (`config/ListenerPathConfig`). Spring's multipart resolution parses such an upload before the path is checked, into Undertow's temporary storage, never into `upload.directory`. A `;` in the query string is not part of the path.
- The reason phrase `File was uploaded.` is confined to the upload's own exchange; the next response on the connection carries its standard phrase.
- The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to.

Status: `default` — a non-secret value the original fixed, overridable; `fixed` — a framework setting that reproduces the original listener, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `http.port` (unchanged) | default | port of the listener, bound to `server.port`. The original read `${http.port}` (`http:listener-config`, xml:4), while its `mule-app.properties` is empty (0 bytes); its README tells users to set port 8081 and browse to `http://localhost:8081/uploadFile` |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | none | fixed | resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | none | fixed | `http:listener-config host="0.0.0.0"` (xml:4) |
| `upload.directory` | `String` | `/tmp` | none (new key) | default | directory the uploaded file is written to; the original hard-coded `file:outbound-endpoint path="/tmp"` (xml:27). On a host without `/tmp`, such as Windows, set it to a writable path. A missing directory is created on the first upload, and a relative path resolves against the working directory |
| `spring.application.name` | `String` | `http-multipart-request-java` | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | none | fixed | an unserved path reaches SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | none | fixed | `TRACE` reaches the controller and gets the listener's 405, instead of the servlet's `200` echo |
| `spring.mvc.formcontent.filter.enabled` | `boolean` | `false` | none | fixed | the listeners checked the method before reading the body, so a form body on `PUT`, `PATCH` or `DELETE` reaches the controller and gets the listener's 405 unread, instead of being read whole by Boot's form-content filter before dispatch |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `spring.servlet.multipart.enabled` | `boolean` | `true` | none | fixed | multipart parsing of the upload request |
| `spring.servlet.multipart.max-file-size` | `DataSize` | `-1` | none | fixed | no limit on a part's size, as in the original |
| `spring.servlet.multipart.max-request-size` | `DataSize` | `-1` | none | fixed | no limit on the request's size, as in the original |

- There are no secrets, credentials, hosts or tenant identifiers, so the configuration has no TODO placeholder. The project has no `src/test/resources/application-test.yml` and no `application-it.yml`.
- Tests never write to `/tmp`: every test that stores a file writes under a JUnit `@TempDir`, and the two Spring Boot tests that upload (`HttpMultpartRequestTest`, `UploadControllerTest`) point `upload.directory` at it through `@DynamicPropertySource`; `FixtureParityTest` seeds it with its own replay directory.
- Override keys on the command line or through the environment (relaxed binding maps `HTTP_PORT` to `http.port` and `UPLOAD_DIRECTORY` to `upload.directory`):

  ```sh
  mvn spring-boot:run -Dspring-boot.run.arguments="--http.port=9090 --upload.directory=/some/dir"
  UPLOAD_DIRECTORY=/some/dir java -jar target/http-multipart-request-java-1.0.0-SNAPSHOT.jar
  ```

## Mule-to-Java mapping

| Mule construct (`http-multipart-request`) | Java construct (`com.mulesoft.examples.http_multipart_request`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (xml:4) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener allowedMethods="GET" path="/uploadFile"` (xml:8) | `controller/UploadController#uploadFile` (`@RequestMapping("/uploadFile")`), which compares the method with `GET` ignoring case and runs its `uploadForm` step |
| `parse-template location="uploadFile.html"` (xml:10) | `service/UploadService#uploadForm()` → SC-14 `mapper/ParseTemplateRenderer#render("uploadFile.html")` |
| `http:listener allowedMethods="POST" path="/uploadFile"` with `http:response-builder statusCode="200" reasonPhrase="File was uploaded."` (xml:15-17) | `UploadController#uploadFile`, which compares the method with `POST` ignoring case and runs its `upload` step → `service/UploadService#upload(Part)`; status `200` set by the controller, reason phrase `UploadService.REASON_PHRASE` written by SC-2 `config/ReasonPhrase.set` after the file is stored |
| `allowedMethods` rejection of every other method | `UploadController#uploadFile` and `#rejectUnsupportedMethod` (`OPTIONS`) throw `HttpRequestMethodNotSupportedException`, answered by SC-1 |
| Listener multipart parsing (JavaMail, part headers read as ISO-8859-1) | Spring multipart resolution with `config/MultipartHeaderCharsetConfig` |
| `expression-component` extracting `attachmentFileName` (xml:19-21) | `UploadService.extractFileName`, the formula verbatim |
| `logger level="ERROR"` of the part's `Content-Type` (xml:23) | SLF4J `LOG.error("{}", file.getHeader("Content-Type"))` in `UploadService#upload`, the header value unchanged |
| `set-payload` of the part content (xml:25) | `UploadService#upload` reads the part bytes and returns them as the response body |
| `file:outbound-endpoint path="/tmp" outputPattern="#[attachmentFileName]"` (xml:27) | SC-6 `service/FileOutboundWriter#write(upload.directory, fileName, content)` |
| Platform default responses (no listener, method not allowed, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Listener semantics (no CORS policy; the raw path, query removed, matched literally, `;` segment parameters included) | `config/PreflightDispatchConfig`, `config/ListenerPathConfig` |
| `mule-deploy.properties` (loads `http-multipart-request.xml`) | `HttpMultipartRequestApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `HttpMultpartRequestIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, `AttachmentRequest`) | `HttpMultpartRequestTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`) |

Shared components, copied from their reference projects with only the `package` line changed and registered in section 4 of the root `DECISIONS.md` (AAP 0.4.4):

| ID | Files in this project | Copied from |
|---|---|---|
| SC-1 | `exception/GlobalExceptionHandler`, `exception/ErrorResponse`, test `exception/GlobalExceptionHandlerTest` | `rest-api-with-apikit-java` |
| SC-2 | `config/ReasonPhrase`, test `config/ReasonPhraseTest` | `implementing-a-choice-exception-strategy-java` |
| SC-6 | `service/FileOutboundWriter`, test `service/FileOutboundWriterTest` | `processing-orders-with-dataweave-and-APIkit-java` |
| SC-14 | `mapper/ParseTemplateRenderer`, test `mapper/ParseTemplateRendererTest` | `login-form-using-the-http-connector-java` |
| ST-1 | test `support/HttpTestClient` | `rest-api-with-apikit-java` |
| ST-2 | test `FixtureParityTest`, the table format of `src/test/resources/fixtures/SCENARIOS.md` | `hello-world-java` |

`config/PreflightDispatchConfig`, its test `config/PreflightDispatchConfigTest` and `config/ListenerPathConfig` are the same classes `hello-world-java` carries, apart from the `package` line.

## Behaviours stubbed or externalized

- Externalized: the upload directory, hard-coded `/tmp` in the original, is the key `upload.directory` with default `/tmp`.
- Stubbed: none. The original reaches no external system and holds no credential, so nothing is mocked at runtime and no behaviour was dropped.
- Not carried (AAP 0.2.2): Studio metadata (`mule-project.xml`) and `src/test/resources/log4j2-test.xml`.

## Build, run and test

Run from `java-conversions/http-multipart-request-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only
mvn spring-boot:run     # listener on 0.0.0.0:8081; browse to http://localhost:8081/uploadFile
mvn spring-boot:run -Dspring-boot.run.arguments=--upload.directory=/some/dir
java -jar target/http-multipart-request-java-1.0.0-SNAPSHOT.jar --upload.directory=/some/dir
```

Expected exchange (the `Date` value varies):

```console
$ curl -i -F "file=@src/test/resources/test.txt;type=text/plain" http://localhost:8081/uploadFile
HTTP/1.1 200 File was uploaded.
Connection: keep-alive
Content-Length: 13
Date: <current date>

file uploaded
```

The file is then at `<upload.directory>/test.txt`, and the application log holds an `ERROR` line from `UploadService` whose message is `text/plain`.

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it calls no vendor system (Tier 2B `N/A`).
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `HttpMultpartRequestTest` | the ported `HttpMultpartRequestIT` (`http-multipart-request/src/test/java/org/mule/examples/HttpMultpartRequestIT.java:57-73`): the body of `GET /uploadFile` contains `uploadFile.html`; a multipart upload of `test.txt` leaves `<upload.directory>/file` |
| `controller/UploadControllerTest` | on the wire through ST-1: `GET` in four letter cases → `HTTP/1.1 200 OK`, no `Content-Type`, `Content-Length: 348`, the form byte for byte; `POST` in four letter cases and body A of `SCENARIOS.md` (`test.txt`) → `HTTP/1.1 200 File was uploaded.`, no `Content-Type`, the uploaded bytes, the file stored; a second upload under a stored name replaces it; non-ASCII names decoded as ISO-8859-1; the last of several `file` parts stored; absolute, double-slash and nested names stored under the upload directory; `PUT`, `DELETE`, `PATCH`, `TRACE`, `OPTIONS` in several letter cases → 405 `text/plain` `Method not allowed for endpoint: /uploadFile`, `HEAD` the same without a body; a form declaring 300000 bytes sent with `PUT`, `PATCH` or `DELETE` → the whole 405 while only its first 1024 bytes have been written, with no file stored, `UploadService` never called and no `FormContentFilter` bean in the context; a missing `file` part (body C, a text field only, a `text/plain` body) → 500 `{ "message": "Multipart part 'file' is not present in the request" }`; failing writes → 500 `{ "message": "Streaming failed. Could not get output stream" }`; `/unknown` → 404 `text/plain` `No listener for endpoint: /unknown`, and so do `/uploadFile;x=1`, `/uploadFile;jsessionid=ABC` and `/uploadFile;` for `GET`, `POST` with a `file` part, `PUT`, `HEAD` (without a body) and `OPTIONS`, with no file stored and `UploadService` never called; the upload's reason phrase does not reach the next response |
| `service/UploadServiceTest` | `extractFileName` applies the original formula verbatim and fails as it did; `upload` logs the `Content-Type` at `ERROR`, stores and returns the content, replaces an existing file, and fails before logging or writing without a part or a usable `Content-Disposition`; read and write failures reach the caller unchanged; `REASON_PHRASE`; `uploadForm()` is the original page byte for byte and fails when the template is missing |
| `config/MultipartHeaderCharsetConfigTest` | the filter sets ISO-8859-1 on every multipart request that is not a `GET` and leaves other requests unchanged; it is mapped to `/uploadFile` directly after Spring Boot's character-encoding filter |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `config/PreflightDispatchListenerTest` | on the wire: a pre-flight to `/uploadFile` gets exactly the plain `OPTIONS` reply (405, no `Access-Control-*` or `Vary`) and runs neither flow; cross-origin `GET` and `POST` still work; a pre-flight to `/unknown` or `/uploadFile;x=1` gets the no-listener 404 |
| `exception/GlobalExceptionHandlerTest` | SC-1: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; the single escaped, length-bounded ERROR log line per unhandled exception |
| `config/ReasonPhraseTest` | SC-2: the custom phrase reaches the raw status line of the current exchange only |
| `service/FileOutboundWriterTest` | SC-6: directory creation, whole-file replacement, Mule's joining of names to the directory, and every write failure reported as `Streaming failed. Could not get output stream` |
| `mapper/ParseTemplateRendererTest` | SC-14: templates loaded byte-identical, expression strings replaced, a missing template rejected |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

Notes on the ported test:

- The original test called `executeMultiPartRequest(..., testFile, "file", "")` (`HttpMultpartRequestIT.java:71`), and `AttachmentRequest` passes that name to `new FileBody(file, ContentType.APPLICATION_OCTET_STREAM, fileName)` (`AttachmentRequest.java:93-101`), so the part carried the file name `file` and the original asserted that `/tmp/file` exists, not `/tmp/test.txt`. `HttpMultpartRequestTest` keeps that request, byte for byte as body B of `SCENARIOS.md`, and asserts `<upload.directory>/file`.
- `UploadControllerTest` separately covers the file name `test.txt`, the name a browser sends for that file.

`support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.

## Fixture parity

`FixtureParityTest` (ST-2) replays `src/test/resources/fixtures/<scenario>.json`, one per row of `src/test/resources/fixtures/SCENARIOS.md`, against the application on a random port through ST-1, with `upload.directory` pointed at a temporary replay directory, never `/tmp`. `deterministic` scenarios are compared byte for byte: status, reason phrase, `Content-Type`, `Location`, body and the files stored in the upload directory. No upstream exists, so every fixture's `upstream` is `[]`. `SCENARIOS.md` also holds the seven multipart request bodies and the Tier 2A capture procedure. With no fixtures the test reports skipped, never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `get-upload-file` | `http` | `deterministic` | `GET /uploadFile`, empty body | `UNVERIFIED-uncaptured` |
| `post-upload-file` | `http` | `deterministic` | `POST /uploadFile`, body A (`test.txt`, `text/plain`) | `UNVERIFIED-uncaptured` |
| `post-upload-file-attachment-request` | `http` | `deterministic` | `POST /uploadFile`, body B (file name `file`, `application/octet-stream`, as the original test sent it) | `UNVERIFIED-uncaptured` |
| `post-upload-file-missing-part` | `http` | `deterministic` | `POST /uploadFile`, body C (no `file` part) | `UNVERIFIED-uncaptured` |
| `post-upload-file-latin1-filename` | `http` | `deterministic` | `POST /uploadFile`, body D (file name byte `E9`) | `UNVERIFIED-uncaptured` |
| `post-upload-file-absolute-filename` | `http` | `deterministic` | `POST /uploadFile`, body E (file name `/qa/absolute.txt`) | `UNVERIFIED-uncaptured` |
| `post-upload-file-nested-filename` | `http` | `deterministic` | `POST /uploadFile`, body F (file name `qa-sub/qa-nested.txt`) | `UNVERIFIED-uncaptured` |
| `post-upload-file-write-failure` | `http` | `deterministic` | `POST /uploadFile`, body G (file name `.`) | `UNVERIFIED-uncaptured` |
| `put-upload-file-method-not-allowed` | `http` | `deterministic` | `PUT /uploadFile`, header `Content-Length: 0`, empty body | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable` or `nondeterministic`: every branch is selected by its request, and the flows read no clock, random value or generated name.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| http-multipart-request-java | http-multipart-request | General | PASS | PASS | 86.4% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`, which runs every phase of `clean package`: `BUILD SUCCESS`; 426 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 426 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 407 of 471 lines (`service/UploadService` 19 of 19, `service/FileOutboundWriter` 343 of 403, `mapper/ParseTemplateRenderer` 45 of 49). Total project LINE coverage: 90.2% (643 of 713 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=10`, `failed=0`.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed`, as recorded in section 9 of the root `DECISIONS.md`. No fixture can be captured from the original, so parity rests on the fallback:

- `HttpMultpartRequestTest` carries the original's two committed assertions with their comparison semantics: the form body contains `uploadFile.html`, and the upload leaves the file `file` in the upload directory.
- `UploadControllerTest`, `UploadServiceTest` and the shared-component tests cover every reachable branch.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the platform defaults, but not that its bytes equal what the original runtime sent.
- Behaviours no original assertion covers, tested only by the new tests: the POST response's status line, reason phrase and body bytes; the `ERROR` log of the part's content type; the absent `Content-Type` of the GET and POST replies; the 405 for other methods; the 404 on other paths; the 500 for a missing `file` part and for a failed write; the replacement of an existing file; the ISO-8859-1 decoding of file names and the joining of absolute and nested names to the upload directory.
- Tier 2A verification points, where a captured fixture governs and the code is changed to match: the absent `Content-Type` of the GET and POST replies; the 500 bodies for a missing part and a failed write; the exact bytes of the platform 404 and 405; the 404 for `/uploadFile` with `;` segment parameters; the names and paths stored for the non-ASCII, absolute and nested file names.
- Unverified scenarios, until captured: all ten of the table above.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `http-multipart-request/`.
