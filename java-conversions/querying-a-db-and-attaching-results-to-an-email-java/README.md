# Querying a DB and attaching results to an Email — Spring Boot conversion

Java 17 / Spring Boot 3.2.12 conversion of the Mule EE 3.8 example [`querying-a-db-and-attaching-results-to-an-email`](../../querying-a-db-and-attaching-results-to-an-email/) (category General; flow `attachmentsFlow1` in `src/main/app/attachments.xml`). The original folder `querying-a-db-and-attaching-results-to-an-email/` stays unchanged beside this project and is the reference and the Tier 2A capture source.

## Original example

Employee names arrive as XML over HTTP. The flow splits the list on `//employee` and queries MySQL for each employee individually. It aggregates the rows into a list in arrival order, converts the list to CSV, attaches the CSV to an email and sends the email over SMTP (`querying-a-db-and-attaching-results-to-an-email/README.md:3-6`).

The single flow `attachmentsFlow1` of `attachments.xml:7-37` runs these processors in order:

- `http:listener path="/"` (line 8) on the `http:listener-config` of line 6 (host `0.0.0.0`, port `${http.port}`);
- `splitter expression="#[xpath('//employee')]"` (line 10);
- `db:select` with a `db:dynamic-query` (lines 11-15) that writes `payload.getText().split(" ")[0]` and `[1]` into double-quoted literals compared with `first_name` and `last_name` (line 12), against the `db:generic-config` of line 5 (`com.mysql.jdbc.Driver`, `url="${jdbc.url}"`);
- `collection-aggregator` (line 16), then `logger level="INFO" message="#[payload]"` (line 17);
- the DataWeave CSV script (lines 18-31, script lines 20-30) and `object-to-string-transformer mimeType="text/csv"` (line 33);
- `set-attachment attachmentName="employees.csv" contentType="text/plain"` (line 35);
- `smtp:outbound-endpoint` (line 36) through the `smtp:gmail-connector` of line 4, with `from`, `to`, `subject`, `host`, `port`, `user` and `password` from placeholders and `responseTimeout="10000"`.

The flow declares no exception strategy. `mule-app.properties` is empty; the original README sets the port, the SMTP settings and the database URL by hand (`querying-a-db-and-attaching-results-to-an-email/README.md:12-32`). Below, `README.md:<lines>` cites that original README, and `attachments.xml` and `QueryingDbAndAttachingResultsToAnEmailIT.java` the original's flow and test.

## Endpoints and triggers

| Path | Methods | Host | Port | Request | Source |
|---|---|---|---|---|---|
| `/` exactly | any, `HEAD`, `OPTIONS` and `TRACE` included: the listener declares no `allowedMethods` | `0.0.0.0` | `http.port`, default `8081` | an XML body such as `src/test/resources/message.xml`: `<root><employees><employee>Chava Puckett</employee>…</employees></root>` | `attachments.xml:6-8` |
| any other path (`/unknown` and `/error` included) | any | `0.0.0.0` | `http.port` | — | listener platform default |

The project has no non-HTTP trigger: no file poller, scheduler, queue listener or mail poller.

### Processing, in the original order

1. `service/EmployeeReportService` parses the body and selects `//employee` in document order: every `employee` element without a namespace, at any depth.
2. Each element's direct text (its text and CDATA children, untrimmed, as dom4j's `getText()` returned it) is split on a single space with Java's `split(" ")`. Part `[0]` is the first name and part `[1]` the last name; further parts are ignored.
3. `client/EmployeeRepository` runs `select first_name,last_name,gender,dob,hire_date from employees where first_name = ? and last_name = ?` with both names as bind parameters. Rows come back in database order, compared under the column collation, which is case-insensitive in MySQL's default.
4. The row lists are aggregated into one list of per-employee row lists, in arrival order, which is document order.
5. The aggregate is logged at INFO by `service.EmployeeReportService`, for example `[[{first_name=Chava, last_name=Puckett, gender=F, dob=1985-09-02, hire_date=2008-10-12}], …]`.
6. `mapper/EmployeeCsvMapper` takes the first row of each employee and writes the header `first_name,last_name,gender,dob,hire_date`, dates as ISO `yyyy-MM-dd`, through SC-4 `mapper/DwCsvWriter`: comma separator, LF line ends with a trailing LF (`attachments.xml:20-30`). An employee without a row becomes five empty fields (`,,,,`).
7. `client/ReportMailSender` mails the CSV from `mail.from` to `mail.to` with subject `mail.subject`, once and never retried (`attachments.xml:35-36`). The mail is `multipart/mixed`:
   - the first part is the CSV as `text/plain; charset=UTF-8`, described `Payload`, with no file name and no disposition;
   - the second part is the attachment `employees.csv` with `Content-Disposition: attachment; filename=employees.csv`, typed `text/plain`. JavaMail adds `charset=us-ascii` for an ASCII report; any other report is labelled `charset=UTF-8`.

   The part order matters: the original test reads the first part that is neither an attachment nor named, and expects the CSV rows in it.

The body is parsed without loading external entities, external DTDs or XInclude, as Mule 3.8 parsed it, and entity expansion stays within the JDK's secure-processing limits. A DOCTYPE and internal entities are still accepted.

Example output for `message.xml` against the data of `import_orders.sql`:

```
first_name,last_name,gender,dob,hire_date
Chava,Puckett,F,1985-09-02,2008-10-12
Quentin,Puckett,F,1971-10-21,2008-09-15
Mona,Sosa,M,1950-09-26,2007-11-27
```

### HTTP response

**Plan default — Tier 2A verification point; a captured fixture governs** (AAP 0.4.7). The one-way SMTP endpoint lets the flow continue with the message it received, and that message still carries the outbound attachment. Mule 3.8's listener therefore answers 200 with a `multipart/form-data` body built from the outbound attachments: one part named `employees.csv`, `Content-Type: text/plain`, holding the CSV, and a boundary generated per response.

`controller/EmployeeReportController` returns exactly that, after the mail has been sent:

- `200 OK`, `Content-Type: multipart/form-data;boundary=<boundary>`, where Spring's `FormHttpMessageConverter` generates a boundary of 30 to 40 characters per response, so `Content-Length` varies with it;
- one part with the headers `Content-Disposition: form-data; name="employees.csv"`, `Content-Type: text/plain` and `Content-Length`, holding the CSV as UTF-8;
- the entity is built in memory and written complete with its `Content-Length`, never chunked;
- a `HEAD` reply carries the same headers without the body, and the flow still runs and sends the mail.

Expected exchange (the `Date`, the boundary and the entity length vary):

```console
$ curl -i -X POST -H 'Content-Type: application/xml' --data-binary @src/test/resources/message.xml http://localhost:8081/
HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: multipart/form-data;boundary=<boundary>
Content-Length: <entity length>
Date: <current date>

--<boundary>
Content-Disposition: form-data; name="employees.csv"
Content-Type: text/plain
Content-Length: 154

first_name,last_name,gender,dob,hire_date
Chava,Puckett,F,1985-09-02,2008-10-12
Quentin,Puckett,F,1971-10-21,2008-09-15
Mona,Sosa,M,1950-09-26,2007-11-27

--<boundary>--
```

### Edge and error cases

| Case | Example request body | Response | Mail |
|---|---|---|---|
| No `employee` element | `<root><employees/></root>` | `200 OK`, empty body, `Content-Length: 0`, no `Content-Type`; WARN `Splitter returned no results. If this is not expected, please check your split expression`; no query (plan default; verification point) | none |
| Employee without a matching row | `<root><employees><employee>No Body</employee></employees></root>` | the 200 multipart reply above, whose CSV line for that employee has five empty cells (`,,,,`) (plan default; verification point) | sent, with the same CSV |
| Employee text without a space | `<root><employees><employee>Chava</employee></employees></root>` | `500`, `application/json`, `{ "message": "Index 1 out of bounds for length 1" }` (SC-1); nothing is queried | none |
| Malformed or empty XML body | `<root>`, or no body | `500`, `application/json`, `{ "message": "<parser message>" }` (SC-1), the JDK parser's text: `XML document structures must start and end within the same entity.` and `Premature end of file.` | none |
| Database failure | the original request | `500`, `application/json`, `{ "message": "<JDBC driver message>" }` (SC-1); with `jdbc.url` blank, `{ "message": "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured" }`; the flow stops at the failing lookup | none |
| SMTP failure | the original request | `500`, `application/json`: `{ "message": "Could not send the employees report mail" }` when the server cannot be reached or rejects the message, `{ "message": "Authentication failed" }` when it rejects the account (SC-1); never retried | none |
| SMTP settings not configured: `smtp.host`, `mail.from` or `mail.to` blank, or `smtp.user` or `smtp.password` blank while `mail.smtp.auth` is `true`, as `application.yml` sets it | the original request | `500`, `application/json` (SC-1): `{ "message": "SMTP mail settings are not configured: <key> (<ENV>), …" }`, naming every missing key with its environment variable in the order `smtp.host`, `smtp.user`, `smtp.password`, `mail.from`, `mail.to`, never a value; with every TODO key unset, `{ "message": "SMTP mail settings are not configured: smtp.host (SMTP_HOST), smtp.user (SMTP_USER), smtp.password (SMTP_PASSWORD), mail.from (MAIL_FROM), mail.to (MAIL_TO)" }`. `client/ReportMailSender` refuses the send before building the message or opening a connection; `mail.subject` is never required, and an empty subject is sent as given | none |
| Unknown path | `GET /unknown` | `404`, `text/plain`, `No listener for endpoint: /unknown` (SC-1) | none |

- SC-1 logs each `500` at ERROR on one line. No mapping restricts the request or reply media type, so the caller's `Content-Type` and `Accept` never cause a 415 or 406. SC-1's 405 (`Method not allowed for endpoint: <path>`) cannot occur, because the listener accepts every method.
- A CORS pre-flight (`OPTIONS` with `Origin` and `Access-Control-Request-Method`), `OPTIONS` and `TRACE` run the flow and get exactly the reply of `POST`, with no `Allow`, `Access-Control-*` or `Vary` header and no `TRACE` echo. The original listener applied no CORS policy (`config/PreflightDispatchConfig`).
- Tier 2A verification points (AAP 0.4.7): the plan defaults marked above (the multipart reply, the empty reply and the empty-cell line) and the bytes of SC-1's platform defaults, the no-listener `404` and the unhandled-exception `500`. When a captured fixture shows different bytes, the fixture governs and the code is changed to match.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to (AAP 0.4.6, 0.6.6).

Status: `default` — a non-secret value the original fixed, overridable; `TODO` — no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument, marked `(secret)` when it carries one; `fixed` — a framework setting that reproduces the original listener, database or mail configuration, not a key of the original, changing it changes behaviour.

| Key | Type | Default | Environment variable | Original name | Status | Description |
|---|---|---|---|---|---|---|
| `http.port` | `int` | `8081` | `HTTP_PORT` (relaxed binding) | `http.port` (unchanged) | default | bound to `server.port` (`server.port: ${http.port:8081}`). The original's `mule-app.properties` is empty, so `http.port` had no value there; its README uses port 8081 |
| `jdbc.url` | `String` | none (`${JDBC_URL:}`) | `JDBC_URL` | `jdbc.url` (unchanged) | TODO (secret) | the MySQL JDBC URL, bound to `spring.datasource.url`. As in the original README's form `jdbc:mysql://localhost:3306/company?user=<user>&password=<password>` (`README.md:30-32`), the credentials travel inside the URL, so the key has no value. While it is blank the application still starts, logging one WARN that names `jdbc.url` and `JDBC_URL`, and every request that reaches a lookup fails with `500` (`config/DataSourceConfig`, `config/UnconfiguredDataSource`) |
| `smtp.host` | `String` | none (`${SMTP_HOST:}`) | `SMTP_HOST` | `smtp.host` (unchanged) | TODO | the SMTP server, bound to `spring.mail.host`. The original README uses Gmail, `smtp.gmail.com` (`README.md:21`) |
| `smtp.port` | `int` | `587` | `SMTP_PORT` (relaxed binding) | `smtp.port` (unchanged) | default | the STARTTLS submission port of the original README (`README.md:22`), bound to `spring.mail.port` |
| `smtp.user` | `String` | none (`${SMTP_USER:}`) | `SMTP_USER` | `smtp.user` (unchanged) | TODO | the SMTP account as a plain address, bound to `spring.mail.username`. The original needed the URL-encoded form (`senderemailid%40gmail.com`, `README.md:23`; test key `smtp.user_enc`, `QueryingDbAndAttachingResultsToAnEmailIT.java:116`) because Mule built an endpoint URI from it; `JavaMailSender` does not |
| `smtp.password` | `String` | none (`${SMTP_PASSWORD:}`) | `SMTP_PASSWORD` | `smtp.password` (unchanged) | TODO (secret) | the SMTP password, bound to `spring.mail.password` |
| `mail.from` | `String` | none (`${MAIL_FROM:}`) | `MAIL_FROM` | `mail.from` (unchanged) | TODO | the sender address, read by `client/ReportMailSender` |
| `mail.to` | `String` | none (`${MAIL_TO:}`) | `MAIL_TO` | `mail.to` (unchanged) | TODO | the recipient address; a comma-separated list is accepted, as the endpoint's `to` attribute accepted one |
| `mail.subject` | `String` | none (`${MAIL_SUBJECT:}`) | `MAIL_SUBJECT` | `mail.subject` (unchanged) | TODO | the subject. The original README's example is `Export from Excel` (`README.md:28`); its test used `Mule flow completed!` (`QueryingDbAndAttachingResultsToAnEmailIT.java:121`) |
| `server.port` | `Integer` | `${http.port:8081}`, so `8081` | — | none | fixed | the `http:listener-config` port `${http.port}` (`attachments.xml:6`), resolved from `http.port`; set `http.port` rather than this key |
| `server.address` | `InetAddress` | `0.0.0.0` | — | none | fixed | `http:listener-config host="0.0.0.0"` (`attachments.xml:6`) |
| `spring.application.name` | `String` | `querying-a-db-and-attaching-results-to-an-email-java` | — | none | fixed | application name in log lines |
| `spring.web.resources.add-mappings` | `boolean` | `false` | — | none | fixed | an unserved path must reach SC-1's no-listener 404, not the static resource handler |
| `spring.mvc.dispatch-trace-request` | `boolean` | `true` | — | none | fixed | `TRACE` reaches `EmployeeReportController` like every other method, instead of the servlet's `message/http` echo |
| `spring.autoconfigure.exclude` | `String[]` (fully qualified class names) | `org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration` | — | none | fixed | no Boot `/error` endpoint; `/error` gets the no-listener 404 like any unserved path |
| `spring.datasource.url` | `String` | `${jdbc.url}` | — | none | fixed | the `db:generic-config url` (`attachments.xml:5`); set `jdbc.url` rather than this key |
| `spring.sql.init.mode` | `DatabaseInitializationMode` | `never` | — | none | fixed | the application never runs `import_orders.sql` or any other schema or data script against the configured database |
| `spring.mail.host` | `String` | `${smtp.host}` | — | none | fixed | `smtp:outbound-endpoint host` (`attachments.xml:36`); set `smtp.host` rather than this key |
| `spring.mail.port` | `Integer` | `${smtp.port}` | — | none | fixed | `smtp:outbound-endpoint port`; set `smtp.port` rather than this key |
| `spring.mail.username` | `String` | `${smtp.user}` | — | none | fixed | `smtp:outbound-endpoint user`; set `smtp.user` rather than this key |
| `spring.mail.password` | `String` | `${smtp.password}` | — | none | fixed | `smtp:outbound-endpoint password`; set `smtp.password` rather than this key |
| `spring.mail.default-encoding` | `Charset` | `UTF-8` | — | none | fixed | default encoding of the messages `JavaMailSender` creates, Mule's default output encoding; `ReportMailSender` also names UTF-8 explicitly for the subject and the CSV |
| `spring.mail.properties[mail.smtp.auth]` | `String` | `true` | — | none | fixed | `smtp:gmail-connector` (`attachments.xml:4`): SMTP authentication with the configured account |
| `spring.mail.properties[mail.smtp.starttls.enable]` | `String` | `true` | — | none | fixed | `smtp:gmail-connector`: STARTTLS whenever the server offers it |
| `spring.mail.properties[mail.smtp.connectiontimeout]`, `[mail.smtp.timeout]`, `[mail.smtp.writetimeout]` | `String` | `10000` each | — | none | fixed | connect, read and write timeouts in milliseconds, from `responseTimeout="10000"` (`attachments.xml:36`) |

- Every key keeps its original name, so the original-to-new key map is the identity: no key is renamed. The original test-only keys `smtp.user_enc` and `database.url`, `database.user`, `database.password` of the never-committed `mule.test.properties` (`QueryingDbAndAttachingResultsToAnEmailIT.java:48,114-121`, `MySQLDbCreator.java:35-37`) are dropped, because H2 and GreenMail replace the servers they addressed.
- Mail transport: `smtp:gmail-connector` becomes the `JavaMailSender` (Angus Mail) of `spring.mail.*`, with `mail.smtp.auth=true`, `mail.smtp.starttls.enable=true` and the 10000 ms timeouts above. Its `validateConnections="true"` is not reproduced, so start-up never depends on a reachable SMTP server; the connection is opened when a report is sent, and only once every required SMTP setting has a value (Edge and error cases).
- Driver: `com.mysql:mysql-connector-j` 8.3.0 (`com.mysql.cj.jdbc.Driver`, detected from the `jdbc:mysql:` URL) replaces `com.mysql.jdbc.Driver`. With a URL set, Boot builds its HikariCP pool from it.
- Supplying the values, run from `java-conversions/querying-a-db-and-attaching-results-to-an-email-java/`:

  ```sh
  JDBC_URL='jdbc:mysql://localhost:3306/company?user=<user>&password=<password>' \
  SMTP_HOST=smtp.gmail.com SMTP_USER='<sender address>' SMTP_PASSWORD='<password>' \
  MAIL_FROM='<sender address>' MAIL_TO='<recipient address>' MAIL_SUBJECT='Export from Excel' \
  java -jar target/querying-a-db-and-attaching-results-to-an-email-java-1.0.0-SNAPSHOT.jar
  ```

- Test profile: `src/test/resources/application-test.yml` (Spring profile `test`, activated with `@ActiveProfiles("test")`) points `jdbc.url` at H2 in MySQL mode (`jdbc:h2:mem:company-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1`, one in-memory database per test context), `smtp.host` at `localhost` with `smtp.port` `3025` (each test replaces it with its GreenMail server's dynamic port), `smtp.user` and `smtp.password` at the demo GreenMail user the tests register, `mail.from` and `mail.to` at that user's address, as the original test sent to and from its own account (`QueryingDbAndAttachingResultsToAnEmailIT.java:118-119`), and `mail.subject` at the original test's `Mule flow completed!`. It turns STARTTLS off, because GreenMail's SMTP is plain text. It holds no real host, account or credential, and the project has no `application-it.yml`, because it calls no vendor system.

### Database setup

`src/main/resources/import_orders.sql` is the original script copied unchanged. It creates database `company` and a password-protected application user granted access to it, then tables `employees` (102 rows) and `roles` (100 rows). Spring never runs it (`spring.sql.init.mode: never`).

1. Start the MySQL server and run the script as the original README instructs (`README.md:13-17`), for example `mysql -u root -p < src/main/resources/import_orders.sql`. MySQL 8 rejects the `GRANT … IDENTIFIED BY` form of line 5, and the `mysql` client stops there; on MySQL 8, run the script with that line replaced by `GRANT ALL PRIVILEGES ON company.* TO` the user line 3 creates.
2. Set `JDBC_URL` to `jdbc:mysql://<host>:3306/company?user=<user>&password=<password>` for that user and start the application.

Several hire dates are two-digit-year literals such as `'07-11-27'`, which MySQL stores as `2007-11-27`. The unit tier runs the same script on H2 through `support/ImportOrdersSeeder` (next sections).

## Mule-to-Java mapping

| Mule construct (`querying-a-db-and-attaching-results-to-an-email`) | Java construct (`com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email`) |
|---|---|
| `http:listener-config` host `0.0.0.0`, port `${http.port}` (`attachments.xml:6`) | Undertow (`spring-boot-starter-undertow`, Tomcat excluded) with `server.address` and `server.port` from `application.yml` |
| `http:listener path="/"` without `allowedMethods` (`attachments.xml:8`) | `controller/EmployeeReportController`, `@RequestMapping("/")`: `handle` (every method but `OPTIONS`) and `handleOptions` (`OPTIONS`), both giving the same reply |
| `splitter`, `collection-aggregator` and the flow's processor order (`attachments.xml:10,16`) | `service/EmployeeReportService.generateAndSend` |
| `db:generic-config` (`com.mysql.jdbc.Driver`, `url="${jdbc.url}"`, `attachments.xml:5`) | `spring.datasource.url: ${jdbc.url}`, Boot's HikariCP `DataSource` and `JdbcTemplate` over `mysql-connector-j`; `config/DataSourceConfig` and `config/UnconfiguredDataSource` while the URL is blank |
| `db:select` with `db:dynamic-query` (`attachments.xml:11-15`) | `client/EmployeeRepository.findByFirstNameAndLastName` (`JdbcTemplate`, `SELECT_BY_FIRST_AND_LAST_NAME` with two bind parameters) |
| `logger level="INFO" message="#[payload]"` (`attachments.xml:17`) | INFO SLF4J statement of `service/EmployeeReportService` at the same point |
| `dw:transform-message` "Map Collection to CSV" (`attachments.xml:18-31`) | `mapper/EmployeeCsvMapper.toCsv` over SC-4 `mapper/DwCsvWriter` |
| `object-to-string-transformer`, `set-attachment`, `smtp:outbound-endpoint` (`attachments.xml:33-36`) | `client/ReportMailSender.send` (`JavaMailSender`); `ATTACHMENT_NAME` `employees.csv`, `ATTACHMENT_CONTENT_TYPE` `text/plain` |
| `smtp:gmail-connector` (`attachments.xml:4`) | `spring.mail.*` in `application.yml` |
| Mule default exception strategy and listener default responses (no listener, unhandled exception) | SC-1 `exception/GlobalExceptionHandler` and `exception/ErrorResponse` |
| Grizzly listener semantics (no CORS policy) | `config/PreflightDispatchConfig` |
| `mule-deploy.properties` (loads `attachments.xml`) | `QueryingADbAndAttachingResultsToAnEmailApplication` (`@SpringBootApplication`) |
| `mule-app.properties` (empty) and the `${...}` placeholders of `attachments.xml` | `src/main/resources/application.yml` |
| `QueryingDbAndAttachingResultsToAnEmailIT` (`FunctionalTestCase`, `MuleClient`, `DynamicPort`, `mule.test.properties`, Gmail over IMAPS) | `QueryingDbAndAttachingResultsToAnEmailTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` with ST-1 `support/HttpTestClient`, profile `test` over H2 and GreenMail) |
| `MySQLDbCreator` | `support/ImportOrdersSeeder` |

## Behaviours stubbed, externalized or changed

- Externalized: the JDBC URL with the credentials it carries, the SMTP host, account and password, and the sender, recipients and subject, which the original README told users to type into Studio (`README.md:19-32`), are the TODO keys above. Nothing else is configurable beyond `http.port` and `smtp.port`.
- Changed, the only intentional difference for hostile input (AAP 0.4.10, root `DECISIONS.md` section 8): the original wrote both words of each employee into the double-quoted literals of a string-built `db:dynamic-query` (`attachments.xml:12`); `client/EmployeeRepository` binds them as JDBC parameters. Every legitimate input returns the same rows. A name containing a double quote or an SQL fragment, which broke or changed the original's query, now matches literally, usually no row, giving an empty-cell CSV line.
- Not reproduced: the Gmail connector's `validateConnections` start-up check (Configuration keys).
- Changed for an unconfigured deployment only: a blank required SMTP setting is refused before any connection with a message naming the missing keys (Edge and error cases), where the original failed in the SMTP connector with its own text.
- Logging: the original `logger` is an INFO SLF4J statement at the same point, after the aggregation and before the CSV transform; its message is the aggregate's `toString()`.
- Replaced in tests (AAP 0.8.3):
  - the live MySQL server → H2 2.2.224 in MySQL mode;
  - the Gmail account, sent through over SMTP and read back over IMAPS → GreenMail 2.1.14, an in-process SMTP server whose received messages are read directly;
  - `MySQLDbCreator` → `support/ImportOrdersSeeder`;
  - the never-committed `mule.test.properties` → `application-test.yml`;
  - Mule `FunctionalTestCase` and `MuleClient` → `@SpringBootTest` with ST-1 `support/HttpTestClient`;
  - the original's fixed 15 s sleep (`QueryingDbAndAttachingResultsToAnEmailIT.java:78`) → a wait of at most 15 s for the delivery.
- `support/ImportOrdersSeeder` runs the copied `import_orders.sql` statement by statement, leaving the file unchanged:
  - it skips the MySQL account and database statements (`CREATE DATABASE`, `CREATE USER`, `GRANT`, `FLUSH PRIVILEGES`, `USE`);
  - it rewrites two-digit-year `'YY-MM-DD'` literals by MySQL's rule (00–69 → 20YY, 70–99 → 19YY), because H2 would store `'07-11-27'` as year 0007 while MySQL stores 2007-11-27;
  - it runs every other statement unchanged, after dropping both tables, so each test starts from exactly the script's 102 employees and 100 roles.
- Nothing is stubbed at runtime. Studio metadata (`mule-project.xml`, `catalog/`), `log4j2.xml` and the Studio sample data are not carried (AAP 0.2.2).

## Build, run and test

Run from `java-conversions/querying-a-db-and-attaching-results-to-an-email-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, repackaged jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; no MySQL, SMTP server or credentials needed
JDBC_URL='jdbc:mysql://localhost:3306/company?user=<user>&password=<password>' SMTP_HOST=smtp.gmail.com \
SMTP_USER='<sender address>' SMTP_PASSWORD='<password>' MAIL_FROM='<sender address>' MAIL_TO='<recipient address>' \
MAIL_SUBJECT='Export from Excel' mvn spring-boot:run    # listener on 0.0.0.0:8081
```

With the application running, post the original test's request:

```sh
curl -i -X POST -H 'Content-Type: application/xml' --data-binary @src/test/resources/message.xml http://localhost:8081/
```

- There is no `mvn verify` live tier: the project has no failsafe plugin and no `*IT` class, because it is not a connector project (Tier 2B `N/A`).
- No test needs MySQL, an SMTP server, credentials or the network.
- Reports land under `target/`: `surefire-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).

## Tests

| Test class | What it asserts |
|---|---|
| `QueryingDbAndAttachingResultsToAnEmailTest` | the ported `QueryingDbAndAttachingResultsToAnEmailIT` (`querying-a-db-and-attaching-results-to-an-email/src/test/java/org/mule/examples/QueryingDbAndAttachingResultsToAnEmailIT.java:70-84`), over H2 and GreenMail: posting `message.xml` delivers a mail whose first unnamed, non-attachment part is non-null and contains `Chava,Puckett,F,1985-09-02,2008-10-12` and `Mona,Sosa,M,1950-09-26,2007-11-27`; it also asserts the HTTP reply, a 200 `multipart/form-data` entity whose single part `employees.csv` is `text/plain` and holds the complete four-line CSV |
| `controller/EmployeeReportControllerTest` | on the wire through ST-1, over H2 and GreenMail with a spy mail sender: `POST /` with `message.xml` → `HTTP/1.1 200 OK`, the multipart reply holding the complete CSV, one mail; the same reply and one mail each for `GET`, `PUT`, `TRACE`, `OPTIONS` and CORS pre-flights, with a complete `Content-Length`, no `Transfer-Encoding`, no `TRACE` echo, no `Allow`, `Access-Control-*` or `Vary` header; `HEAD` → the headers only and one mail; no employee → empty 200 with `Content-Length: 0`, no `Content-Type` and no mail; a one-word employee → 500 `{ "message": "Index 1 out of bounds for length 1" }`; malformed XML → 500 `{ "message": … }`; a stubbed failing send → 500 `{ "message": "SMTP unavailable" }`; a real send to a refusing port and one with a rejected password → 500 with `Could not send the employees report mail` and `Authentication failed`, naming no class, host or port; no mail in any failure case; `/other` and a pre-flight to it → 404 `text/plain` `No listener for endpoint: /other` |
| `service/EmployeeReportServiceTest` | with a mocked repository and mail sender and the real mapper: the original request looked up in document order, the aggregate logged at INFO, the CSV mailed and returned; employees at any depth, each with its own text; no-namespace elements only, case-sensitive names, attributes ignored, each with the splitter WARN and no lookup or mail; Java `split(" ")` semantics (extra parts ignored, consecutive spaces keep an empty part, a tab does not separate, CDATA and entity references included); a no-row entry (`,,,,`) and multiple rows (first written); unparseable bodies (absent, empty, plain text, unclosed, mismatched, two roots, unbound prefix, undeclared entity, unsupported encoding, excessive entity expansion) failing before any lookup; a one-word text, a failed lookup, an undatable value and a failed send propagating unchanged with every later step skipped; external entities and DTDs, by file or over HTTP, never read |
| `client/EmployeeRepositoryTest` | `@JdbcTest` over H2 in MySQL mode seeded by `ImportOrdersSeeder`: the `Chava Puckett` row keyed `first_name`, `last_name`, `gender`, `dob`, `hire_date` in that order; the two-digit-year hire dates of Quentin Puckett (`2008-09-15`) and Mona Sosa (`2007-11-27`); 102 employees and 100 roles; an unknown name → no rows; an injection string bound as data → no rows, with the bind-parameter SQL text; a case-insensitive match as in MySQL |
| `client/ReportMailSenderTest` | against GreenMail: one mail with the configured sender, recipient and subject; `multipart/mixed` with the CSV body part first and the `employees.csv` attachment second; a non-ASCII report arriving intact and labelled UTF-8; a comma-separated `mail.to` reaching each recipient; an unreachable server → `Could not send the employees report mail`; rejected credentials → `Authentication failed`; an invalid sender address failing before sending; a blank `smtp.host`, `smtp.user`, `smtp.password`, `mail.from` or `mail.to`, alone or together → `MailPreparationException` with the exact `SMTP mail settings are not configured: …` message naming no value, before any message is built or connection opened; with `mail.smtp.auth` `false` or absent, a blank account accepted and the report delivered; an empty subject sent as given; a null report rejected; the attachment constants equal to `attachments.xml:35` |
| `client/UnconfiguredSmtpListenerTest` | the application started from the main `application.yml` alone with every SMTP and address key empty and `jdbc.url` at a seeded H2 database: the context starts with Boot's `JavaMailSenderImpl` and its empty host, and `POST` and `GET` with `message.xml` → 500 `application/json` `{ "message": "SMTP mail settings are not configured: smtp.host (SMTP_HOST), smtp.user (SMTP_USER), smtp.password (SMTP_PASSWORD), mail.from (MAIL_FROM), mail.to (MAIL_TO)" }`, with no message built or sent by the `JavaMailSender` |
| `mapper/EmployeeCsvMapperTest` | the exact CSV for the original request; columns in the script's order whatever the row map's order; every JDBC and text date form written as `yyyy-MM-dd`; an entry without values → `,,,,`; only the first row of an entry written; an empty aggregate → no bytes; unparsable date text and non-date types rejected; null arguments rejected |
| `mapper/DwCsvWriterTest` | SC-4, copied with `DwCsvWriter`: the bytes of the DataWeave-produced `report.csv` of `processing-orders-with-dataweave-and-APIkit`, and the writer's column, number, quoting and null rules |
| `exception/GlobalExceptionHandlerTest` | SC-1, with MockMvc and on Undertow: the 404, 405 and 500 status lines, `Content-Type` headers, absent `Allow` header and body bytes; `ErrorResponse` JSON escaping; one escaped, length-bounded ERROR log line per unhandled exception; an `Error` reported with its own message; a database failure answered with the driver's message rather than the SQL statement |
| `config/DataSourceConfigTest` | a blank `spring.datasource.url` (absent, empty or whitespace) starts the context with `UnconfiguredDataSource`, logs one WARN naming `jdbc.url` and `JDBC_URL` without any value, and fails the first query; a configured URL keeps Boot's Hikari pool and logs nothing |
| `config/UnconfiguredDataSourceTest` | both `getConnection` overloads fail with `SQLNonTransientConnectionException`, SQL state `08001`, message naming `jdbc.url` and `JDBC_URL` |
| `config/UnconfiguredDataSourceListenerTest` | the application started from the main `application.yml` alone, `jdbc.url` empty: the context starts, and `POST` and `GET` with `message.xml` → 500 `application/json` `{ "message": "MySQL JDBC URL (jdbc.url, environment variable JDBC_URL) is not configured" }`, one ERROR line carrying SQL state `08001`, and no mail attempted |
| `config/PreflightDispatchConfigTest` | the pre-flight handler mapping: a pre-flight without CORS configuration selects the handler of a plain `OPTIONS` request and gets no CORS processing; `@CrossOrigin` handlers keep Spring's CORS handling |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

- `support/HttpTestClient` is ST-1: an HTTP/1.1 client over a raw socket that returns the status line with its reason phrase, the headers in arrival order and the body bytes.
- `support/ImportOrdersSeeder` is the test helper described under Behaviours stubbed, externalized or changed.
- `src/test/resources/application-test.yml` is profile `test`: the H2 URL, the GreenMail host and port, the demo mail user and addresses and the subject `Mule flow completed!` (Configuration keys). `src/test/resources/message.xml` is the original test's request body, copied unchanged.

## Fixture parity (ST-2)

`src/test/java/com/mulesoft/examples/querying_a_db_and_attaching_results_to_an_email/FixtureParityTest.java` is the shared replay engine, copied from `hello-world-java` with only its `package` line changed; its fixture format, replay drivers and comparison rules are documented once in the [`hello-world-java` README](../hello-world-java/README.md#fixture-parity-st-2-reference-implementation). `src/test/resources/fixtures/SCENARIOS.md` lists this project's scenarios and holds the fixture contract, the capture procedure and the database and mail notes specific to this project.

- `FixtureParityTest` runs without profile `test`, so every fixture's `state.properties` points `jdbc.url` at an H2 database in MySQL mode seeded from `import_orders.sql` and the `smtp.*` and `mail.*` keys at the replay's GreenMail server. Every fixture also carries `state.tables`, the `employees` and `roles` rows read back from the MySQL server the original ran against, and `state.mail`, the demo GreenMail user of `application-test.yml`.
- `upstream` is `[]` for every scenario: the original makes no outbound HTTP call. Its database is seeded state, and each mail its SMTP server received is captured into `response.mails` as raw MIME.
- The three mail-sending scenarios are `nondeterministic`: the mail carries a send `Date`, a `Message-ID` and generated MIME boundaries, and the HTTP reply carries a `multipart/form-data` boundary generated per response. Their status, reason and checked headers are compared exactly and their bodies and mails by structure only, and each is reported `UNVERIFIED — nondeterministic output`. Because the replay compares the response `Content-Type` exactly, `boundary` included, a captured reply that also carries a generated boundary would report these rows `FAILED` on that header; `SCENARIOS.md` (Project notes) records this as a gap of the shared engine, resolved there and never by editing a fixture.
- Fixtures are never hand-written, and never edited to match the Java output. A mismatch is fixed in the Java code: SC-1 `GlobalExceptionHandler` for `get-unknown-path`, and `controller/EmployeeReportController`, `service/EmployeeReportService`, `client/EmployeeRepository`, `mapper/EmployeeCsvMapper`, `client/ReportMailSender` or SC-1 for the other scenarios.
- Hostile input is not a parity scenario: it falls under the intentional difference above.

This project's scenarios, exactly as `SCENARIOS.md` names them:

| Scenario | Trigger | State | Request | Reported once captured | Status in the last run |
|---|---|---|---|---|---|
| `post-root-employees-report` | `http` | `nondeterministic` | `POST /`, header `Content-Type: application/xml`, body = the bytes of `message.xml` | `UNVERIFIED — nondeterministic output` | `UNVERIFIED-uncaptured` |
| `get-root-employees-report` | `http` | `nondeterministic` | `GET /`, header `Content-Type: application/xml`, body = the bytes of `message.xml` | `UNVERIFIED — nondeterministic output` | `UNVERIFIED-uncaptured` |
| `post-root-employee-without-match` | `http` | `nondeterministic` | `POST /`, header `Content-Type: application/xml`, body `<root><employees><employee>No Body</employee></employees></root>` | `UNVERIFIED — nondeterministic output` | `UNVERIFIED-uncaptured` |
| `post-root-no-employees` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body `<root><employees/></root>` | byte-for-byte comparison | `UNVERIFIED-uncaptured` |
| `post-root-single-word-employee` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body `<root><employees><employee>Chava</employee></employees></root>` | byte-for-byte comparison | `UNVERIFIED-uncaptured` |
| `post-root-malformed-xml` | `http` | `deterministic` | `POST /`, header `Content-Type: application/xml`, body `<root>` | byte-for-byte comparison | `UNVERIFIED-uncaptured` |
| `get-unknown-path` | `http` | `deterministic` | `GET /unknown`, empty body | byte-for-byte comparison | `UNVERIFIED-uncaptured` |

No scenario of this project is `unreachable`.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| querying-a-db-and-attaching-results-to-an-email-java | querying-a-db-and-attaching-results-to-an-email | General | PASS | PASS | 98.6% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

Evidence of the run behind this row (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean package`: `BUILD SUCCESS`; 285 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`
- `mvn -B test`: `BUILD SUCCESS`; 285 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 137 of 139 lines. `service/EmployeeReportService` 39 of 39 and its parse-error handler 2 of 4 (its `warning` and `error` callbacks, which no test input triggers: the non-validating parser reports every malformed body through `fatalError`), `mapper/EmployeeCsvMapper` 41 of 41, `mapper/DwCsvWriter` 55 of 55. Total project LINE coverage: 98.2% (427 of 435 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=7`, `failed=0`.

Tier 2A is blocked: the Mule EE licence and the EE Maven repository credentials are unconfirmed (root `DECISIONS.md`, section 9). Tier 2B is `N/A`, because the project calls no vendor system.

## Fallback evidence (AAP 0.8.4)

No fixture can be captured from the original while Tier 2A is blocked, so parity rests on the fallback:

- `QueryingDbAndAttachingResultsToAnEmailTest` carries the original committed assertions with their containment semantics: the mailed body part is non-null and contains `Chava,Puckett,F,1985-09-02,2008-10-12` and `Mona,Sosa,M,1950-09-26,2007-11-27`.
- `controller/EmployeeReportControllerTest`, `service/EmployeeReportServiceTest`, `client/EmployeeRepositoryTest`, `client/ReportMailSenderTest` and `mapper/EmployeeCsvMapperTest` cover every reachable branch, on the wire, against the seeded tables and through GreenMail.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the AAP's reading of the Mule listener and platform defaults, but not that its bytes equal what the original runtime sent or mailed.
- Branches no original assertion covers, covered only by the new assertions:
  - the HTTP response (the multipart reply, `post-root-employees-report`);
  - the CSV header row, the Quentin row and the `employees.csv` attachment part of the mail;
  - any-method acceptance (`get-root-employees-report`);
  - zero employees (`post-root-no-employees`);
  - a non-matching employee (`post-root-employee-without-match`);
  - a one-word employee (`post-root-single-word-employee`);
  - malformed XML (`post-root-malformed-xml`);
  - an unknown path (`get-unknown-path`);
  - SMTP or database failure (no scenario: their error texts come from the mail and database stacks, which differ between the original and the conversion).
- Unverified scenarios, until captured: `post-root-employees-report`, `get-root-employees-report`, `post-root-employee-without-match`, `post-root-no-employees`, `post-root-single-word-employee`, `post-root-malformed-xml`, `get-unknown-path`. The first three stay `UNVERIFIED — nondeterministic output` even once captured.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenarios are captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `querying-a-db-and-attaching-results-to-an-email/`.
