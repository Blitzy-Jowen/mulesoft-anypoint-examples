# import-contacts-into-ms-dynamics-java

`import-contacts-into-ms-dynamics-java` is the Spring Boot 3.2.12 / Java 17 conversion of the Mule EE 3.8 example [`import-contacts-into-ms-dynamics`](../../import-contacts-into-ms-dynamics/), "Import contacts into Microsoft Dynamics CRM" (category Third-party connector, Microsoft Dynamics 365; file-triggered). The package root is `com.mulesoft.examples.import_contacts_into_ms_dynamics` and the main class is `ImportContactsIntoMsDynamicsApplication`. The original folder `import-contacts-into-ms-dynamics/` stays unchanged beside this project as the reference and the Tier 2A capture source.

## Original example

The application polls a local folder for a CSV file of contacts and creates one Microsoft Dynamics contact per row (`import-contacts-into-ms-dynamics/README.md`, "Example Use Case"). The file's columns are `firstname`, `surname`, `phone` and `email`.

- The single flow is `crm-create-account` of `import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml:6-31`, the only flow `config.resources=import-contacts-into-ms-dynamics.xml` loads.
- It has three processors: a file inbound endpoint, the DataWeave transformation `CSV To Map`, and a `foreach` around `dynamicscrm:create` with logical name `contact`.
- It declares no logger, no exception strategy and no transaction, and `mule-app.properties` is empty.

The bundled `src/main/resources/contacts.csv` is 114 bytes, with LF line ends and a trailing LF. It holds the header and two contacts:

| firstname | surname | phone | email |
|---|---|---|---|
| John | Doe | `096548763` | `john.doe@texasComp.com` |
| Jane | Doe | `091558780` | `jane.doe@texasComp.com` |

## Endpoints and triggers

The project has no HTTP listener. With `spring.main.web-application-type: none` no port is bound and no server starts; `spring-boot-starter-webflux` is on the classpath only for `WebClient`. The one trigger is the file poller that replaces the flow's `file:inbound-endpoint`.

| Trigger | Component | Configuration | Behaviour | Source |
|---|---|---|---|---|
| file poll | `trigger/ContactsFileTrigger` over SC-5 `trigger/FileInboundPoller` | input `contacts.input-dir` (default `src/main/resources/input`), move directory `contacts.move-to-dir` (default `src/main/resources/output`), every `contacts.polling-frequency` ms (default `10000`) | each file's bytes go unchanged to `service/DynamicsContactImportService#importContacts(byte[])`; the file is then moved to the move directory under its own name | `file:inbound-endpoint path="src/main/resources/input" moveToDirectory="src/main/resources/output" pollingFrequency="10000"`, `import-contacts-into-ms-dynamics.xml:7` |

File disposition follows Mule 3.8's default file connector (SC-5), because the original declares no `file:connector`:

- `poll()` is `@Scheduled(fixedDelayString = "${contacts.polling-frequency:10000}")`. The delay runs from the end of one poll to the start of the next, and the first poll runs at startup. `pollOnce()` runs one poll on demand for tests and fixture replay; production invocation stays scheduled.
- A poll creates the input and move directories when they are missing. It takes the regular files of the input directory in directory-listing order, unsorted and unfiltered. Each file is marked in process, so the scheduled poll and `pollOnce()` never hand the same file over twice.
- After the import returns, successfully or with an exception, the file is moved to `contacts.move-to-dir` under its own name, replacing a file of that name. A rename that fails falls back to copy-and-delete; a move that still fails is logged at WARN.
- An exception ends the import the way Mule's default exception strategy ended the flow: one ERROR line `Exception processing file <name>: <exception class and throw site>`, then the move above. The line never carries an exception message or file content.
- A file that cannot be opened is logged at WARN and left in place, unmarked, for the next poll.
- The flow declares no transaction or rollback, so no file is retried and no completed Dynamics create is repeated.
- Relative directories resolve against the working directory: the project directory under `mvn spring-boot:run`.
- The endpoint's `mimeType="application/csv"` and `responseTimeout="10000"` are not carried. The content is always read as CSV, and a one-way file endpoint sends no reply.

## Mule-to-Java mapping

| Mule construct (`import-contacts-into-ms-dynamics`) | Java construct (`com.mulesoft.examples.import_contacts_into_ms_dynamics`) |
|---|---|
| `dynamicscrm:config authenticationType="CRMONLINE"` with `${crm.username}`, `${crm.password}` and `organizationServiceUrl="${crm.service-url}"` (xml:3) | `config/DynamicsOAuthConfig`: the Microsoft Entra client-credentials token and the Dataverse `WebClient`, with the `dynamics.*` settings bound to `DynamicsOAuthConfig.DynamicsProperties`; `client/DynamicsWebApiClient` makes every call |
| flow `crm-create-account` (xml:6) | `trigger/ContactsFileTrigger` → `service/DynamicsContactImportService#importContacts(byte[])` |
| `file:inbound-endpoint` (xml:7) | `trigger/ContactsFileTrigger` (`poll()`, `pollOnce()`) over SC-5 `trigger/FileInboundPoller` |
| the polled CSV read by DataWeave 1.0 as `application/csv` | SC-4 `mapper/DwCsvReader#read`: header row, comma separator, `"` quoting, string values |
| `dw:set-payload` `%dw 1.0` / `%output application/java` / `payload map { firstname: $.firstname, lastname: $.surname, businessphone: $.phone, email: $.email }` (header line 9) | `mapper/DynamicsContactMapper#map`, the script's owner: one `LinkedHashMap` per row, with `firstname` ← `firstname`, `lastname` ← `surname`, `businessphone` ← `phone` and `email` ← `email` |
| `foreach` (xml:21-30) | the loop of `DynamicsContactImportService#importContacts`, one record at a time in file order. A failing record ends the loop and later records are not sent, as a failure inside Mule's `foreach` ended the flow. Contacts created before it stay |
| `dynamicscrm:create logicalName="contact"` with the attributes `firstname`, `lastname`, `emailaddress1` = `#[payload.email]` and `telephone1` = `#[payload.businessphone]` (xml:22-29) | `DynamicsContactMapper#toContactAttributes` builds those four attributes in that order. `DynamicsWebApiClient#createContact(Map)` makes one Dataverse `POST contacts` per record through SC-9 `client/UpstreamCallExecutor` and `client/DynamicsFailureClassifier` |
| Mule default exception strategy (none declared) | `FileInboundPoller`: ERROR log, then the move to `contacts.move-to-dir` |
| `mule-deploy.properties` (`config.resources=import-contacts-into-ms-dynamics.xml`) | `ImportContactsIntoMsDynamicsApplication` (`@SpringBootApplication`, `@EnableScheduling`) |
| `mule-app.properties` (empty) | `src/main/resources/application.yml` |
| `ImportContactsIntoMSDynamicsIT` (`FunctionalTestCase`, `MuleClient`, live CRM Online organization) | `ImportContactsIntoMSDynamicsTest` (surefire, WireMock Entra and Dataverse) and `ImportContactsIntoMSDynamicsIT` (failsafe, live Dataverse environment) |
| test flows `selectContactFromDynamics` (`dsql:SELECT contactid,firstname,lastname FROM contact where emailaddress1 = '#[payload]'`) and `deleteContactFromDynamics` (`dynamicscrm:delete logicalName="contact"`) of `src/test/resources/testflows/test-flows.xml` | `DynamicsWebApiClient#findContactsByEmail(String)` and `DynamicsWebApiClient#deleteContact(String)`, used only by the tests |
| `src/test/resources/mule.test.properties` (never committed) | `src/test/resources/application-it.yml` (git-ignored; template `application-it.yml.example`) |

Shared components (register in the root [`DECISIONS.md`](../../DECISIONS.md), section 4). Each copy has only its package names changed and comes with its tests:

- SC-4 `mapper/DwCsvReader`, from `import-contacts-into-salesforce-java`;
- SC-5 `trigger/FileInboundPoller`, from `processing-orders-with-dataweave-and-APIkit-java`;
- SC-9 `client/UpstreamCallExecutor`, `client/UpstreamCall`, `client/UpstreamAction`, `client/UpstreamSystem`, `client/UpstreamFailureClassifier`, `client/UpstreamFailureKind`, `exception/UpstreamAuthenticationException`, `exception/UpstreamRateLimitException` and `exception/UpstreamUnavailableException`, from `salesforce-data-retrieval-java`. `UpstreamExceptionHandler` is not copied, because there is no HTTP caller to answer;
- ST-2 `FixtureParityTest` and the `SCENARIOS.md` format, from `hello-world-java`.

The project carries no SC-1 and no ST-1, because it has no HTTP listener. `client/DynamicsFailureClassifier` is this project's own, the Dynamics row of the SC-9 classification.

## Dataverse Web API contract

`client/DynamicsWebApiClient` and `config/DynamicsOAuthConfig` send exactly these requests:

| Purpose | Request | Expected answer |
|---|---|---|
| Entra token (client credentials) | `POST https://login.microsoftonline.com/<dynamics.oauth.tenant-id>/oauth2/v2.0/token` with `grant_type=client_credentials`, `scope` = `<dynamics.service-url>/.default`, `client_id`, `client_secret` | `200` with an access token. The token is cached while valid, with the provider's 60 s clock skew, so one token serves many Dataverse calls |
| create one contact (production) | `POST <dynamics.service-url>/api/data/v9.2/contacts`, headers `Authorization: Bearer <token>`, `OData-MaxVersion: 4.0`, `OData-Version: 4.0`, `Accept: application/json`, `Content-Type: application/json`; body for John: `{"firstname":"John","lastname":"Doe","emailaddress1":"john.doe@texasComp.com","telephone1":"096548763"}` | `204 No Content` with the new record's URL in `OData-EntityId`; any 2xx is accepted |
| read back by e-mail (tests only) | `GET <dynamics.service-url>/api/data/v9.2/contacts?$select=contactid,firstname,lastname&$filter=emailaddress1 eq '<e-mail>'`, percent-encoded, with single quotes in the address doubled | `200` with the records in `value` |
| delete (tests only) | `DELETE <dynamics.service-url>/api/data/v9.2/contacts(<guid>)`, the GUID validated before any request | `204 No Content` |

- Attributes are sent in the mapper's order, and a `null` value is sent as JSON `null`.
- No base URL is fixed at startup, and every credential is checked only when a call is made. The application therefore starts with every `dynamics.*` secret blank. The first import then fails with an `IllegalStateException` naming the missing key, for example `dynamics.service-url is not configured`, before any request is sent.
- Logs name only the HTTP method and path, never a header, the token, the query or a body.

## Connector failure modes (AAP 0.4.8)

Every Dataverse call and the token request it needs run through SC-9 `client/UpstreamCallExecutor` and are classified by `client/DynamicsFailureClassifier`. The project is file-triggered, so no caller receives a 502, 429 or 503. Instead the classification is logged at ERROR with the upstream name `Microsoft Dynamics 365`, the import ends, and the file is moved as after any handled failure.

| Failure | Behaviour | Result |
|---|---|---|
| Authentication or token expiry: HTTP 401 from Dataverse or from the Entra token endpoint, or Entra rejecting the token request with an OAuth 2.0 error such as `invalid_client`, `unauthorized_client`, `invalid_grant` or `invalid_scope` | WARN `Upstream Microsoft Dynamics 365 rejected the session [AUTHENTICATION]; re-authenticating and retrying once`. The cached token is evicted (`DynamicsWebApiClient#evictToken`), then the request is sent once more with a new token. A second rejection ends the attempt | ERROR `Upstream Microsoft Dynamics 365 call failed [AUTHENTICATION]: …`, `UpstreamAuthenticationException`, file moved |
| Throttling: HTTP 429 from Dataverse's service protection or from Entra | no retry | ERROR `Upstream Microsoft Dynamics 365 call failed [RATE_LIMIT]: …`, `UpstreamRateLimitException`, file moved. The exception carries the vendor's `Retry-After` exactly as sent (`null` when absent). The value is not logged, because there is no caller to pass it to |
| Timeout or connectivity failure anywhere in the cause chain (`SocketTimeoutException`, `HttpTimeoutException`, Netty `ReadTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, other `SocketException`, or a connection the transport found closed or reset) | never retried, even when the chain also holds a 401; this protects the contact create from double submission | ERROR `Upstream Microsoft Dynamics 365 call failed [UNAVAILABLE]: …`, `UpstreamUnavailableException`, file moved |
| Anything else: a Dataverse 400, 403, 404 or 5xx, an Entra 5xx, an unreadable token response, or a blank `dynamics.*` key | rethrown unchanged | handled like Mule's default exception strategy: ERROR `Exception processing file <name>: …`, file moved |

- The connect and read timeouts are 10 s each (`dynamics.connect-timeout`, `dynamics.read-timeout`), the Mule default response timeout. Each request makes exactly one transport attempt, because Reactor Netty's transport retry is disabled.
- A failure ends the file's import at the failing record. Contacts created before it stay, and later records are not sent. The file is moved and never retried, so no contact is ever created twice.
- These are the only behaviours the conversion adds to the original.
- The ERROR lines name the upstream, the category and exception classes only, never vendor messages, credentials or record data.

Measured on this build. Both runs used target directories, `--contacts.input-dir=target/run/in --contacts.move-to-dir=target/run/out --contacts.polling-frequency=2000`, and `contacts.csv` was dropped into `target/run/in/` (timestamps and thread prefixes removed):

```console
$ java -jar target/import-contacts-into-ms-dynamics-java-1.0.0-SNAPSHOT.jar <directories above>
ERROR c.m.e.i.trigger.FileInboundPoller        : Exception processing file contacts.csv: java.lang.IllegalStateException at com.mulesoft.examples.import_contacts_into_ms_dynamics.config.DynamicsOAuthConfig$DynamicsProperties.requireServiceUrl(DynamicsOAuthConfig.java:167)

$ java -jar target/import-contacts-into-ms-dynamics-java-1.0.0-SNAPSHOT.jar <directories above> --dynamics.service-url=http://127.0.0.1:<closed port> --dynamics.oauth.token-uri=http://127.0.0.1:<closed port>/test-tenant/oauth2/v2.0/token --dynamics.oauth.tenant-id=test-tenant --dynamics.oauth.client-id=test-client-id --dynamics.oauth.client-secret=<any non-blank value>
ERROR c.m.e.i.client.UpstreamCallExecutor      : Upstream Microsoft Dynamics 365 call failed [UNAVAILABLE]: org.springframework.web.reactive.function.client.WebClientRequestException at org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction.lambda$wrapException$9(ExchangeFunctions.java:136) <- io.netty.channel.AbstractChannel$AnnotatedConnectException <- java.net.ConnectException
ERROR c.m.e.i.trigger.FileInboundPoller        : Exception processing file contacts.csv: com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException at com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamCallExecutor.translate(UpstreamCallExecutor.java:276) <- org.springframework.web.reactive.function.client.WebClientRequestException <- io.netty.channel.AbstractChannel$AnnotatedConnectException <- java.net.ConnectException
```

After each run `target/run/in/` was empty and `target/run/out/contacts.csv` was byte-identical to the dropped file.

## Configuration keys

Every key of `src/main/resources/application.yml`, with the Java type its value binds to. `config/DynamicsOAuthConfig.DynamicsProperties` binds the `dynamics.*` keys, and its `toString()` masks the client secret. The token client is built from these keys, not from `spring.security.oauth2.client.*`, so Spring Boot's OAuth2 client auto-configuration stays inactive.

Status:

- `default`: a non-secret value the original fixed, overridable.
- `TODO`: no value (`${ENV_NAME:}` with a `# TODO: supply …` comment) until supplied through the named environment variable or a `--key=value` argument (AAP 0.4.6). Marked `(secret)` when it is one.
- `fixed`: a framework setting that reproduces the original, not a key of the original.

| Key | Type | Default | Original name | Status | Description |
|---|---|---|---|---|---|
| `spring.application.name` | `String` | `import-contacts-into-ms-dynamics-java` | none | fixed | application name in log lines |
| `spring.main.web-application-type` | `WebApplicationType` | `none` | none | fixed | the original has no HTTP listener, so no web server starts and no port is bound |
| `contacts.input-dir` | `String` (a path) | `src/main/resources/input` | none; the original hard-coded `path` on the file endpoint (xml:7) | default | directory polled for CSV files |
| `contacts.move-to-dir` | `String` (a path) | `src/main/resources/output` | none; the original hard-coded `moveToDirectory` (xml:7) | default | directory each polled file is moved to under its own name |
| `contacts.polling-frequency` | `long` (ms) | `10000` | none; the original hard-coded `pollingFrequency` (xml:7) | default | fixed delay between the end of one poll and the start of the next |
| `dynamics.service-url` | `String` | none, `${DYNAMICS_SERVICE_URL:}` | **`crm.service-url`** (xml:3) | TODO | the Dataverse environment URL without a trailing slash, for example `https://<org>.crm.dynamics.com`, with the region's `crmN` host where the environment has one. The Web API path `/api/data/v9.2/` and the default token scope are built from it. The original's Organization service URL (`https://<org>.api.crm4.dynamics.com/XRMServices/2011/Organization.svc` in the original README) is not usable as it stands |
| `dynamics.connect-timeout` | `Duration` | `10s` | none | default | connect timeout of the token request and every Dataverse call (AAP 0.4.8); a value under 1 ms is rejected, naming its key |
| `dynamics.read-timeout` | `Duration` | `10s` | none | default | response timeout of the token request and every Dataverse call (AAP 0.4.8), with the same rule |
| `dynamics.oauth.tenant-id` | `String` | none, `${DYNAMICS_OAUTH_TENANT_ID:}` | none; replaces the account sign-in of **`crm.username`** / **`crm.password`** | TODO | the Microsoft Entra tenant (directory) ID of the environment |
| `dynamics.oauth.client-id` | `String` | none, `${DYNAMICS_OAUTH_CLIENT_ID:}` | as above | TODO | the application (client) ID of the Entra app registration that is the Dataverse application user |
| `dynamics.oauth.client-secret` | `String` | none, `${DYNAMICS_OAUTH_CLIENT_SECRET:}` | as above | TODO (secret) | that app registration's client secret |
| `dynamics.oauth.token-uri` | `String` | `https://login.microsoftonline.com/${dynamics.oauth.tenant-id}/oauth2/v2.0/token` | none | default | the Entra v2.0 token endpoint. A blank value, or any value on `login.microsoftonline.com`, is derived again from `dynamics.oauth.tenant-id`, so a blank tenant fails with `dynamics.oauth.tenant-id is not configured`. Tests point it at WireMock |
| `dynamics.oauth.scope` | `String` | `${dynamics.service-url}/.default` | none | default | the client-credentials scope; derived from `dynamics.service-url` when blank |

- Original-to-new key map (AAP 0.4.6, root `DECISIONS.md` section 6.3): `crm.service-url` → `dynamics.service-url`; `crm.username` and `crm.password` → `dynamics.oauth.tenant-id`, `dynamics.oauth.client-id`, `dynamics.oauth.client-secret`; `crm.auth-type`, which the original test read and whose connector value was `CRMONLINE`, is dropped, because authentication is always Entra client credentials.
- No secret has a value in the repository. Supply the four `TODO` keys through the environment or a local profile file outside version control, never in a committed file:

  ```sh
  DYNAMICS_SERVICE_URL='https://<org>.crm.dynamics.com' DYNAMICS_OAUTH_TENANT_ID='<tenant id>' DYNAMICS_OAUTH_CLIENT_ID='<client id>' DYNAMICS_OAUTH_CLIENT_SECRET='<client secret>' mvn spring-boot:run
  ```

  An external `application.yml` outside the repository (`--spring.config.additional-location=<file>`) works as well.
- `src/test/resources/application-test.yml` (Spring profile `test`) fills the blank keys with obvious dummies (`test-tenant`, `test-client-id`, `test-client-secret`, `http://localhost:1`) that authenticate nowhere; each test replaces the URLs with its WireMock server's. Surefire and failsafe set `contacts.input-dir`, `contacts.move-to-dir` and `contacts.polling-frequency` (`3600000`) as system properties pointing under `target/`, so no test polls or moves files in `src/main/resources/input` or `src/main/resources/output`.

## Behaviours stubbed, externalized or changed

- Changed protocol: the original `dynamicscrm` connector (`mule-module-ms-dynamics-crm` 2.4.1) signed in to the CRM 2011 Organization SOAP service (`…/XRMServices/2011/Organization.svc`) with a username and password. The conversion calls the Dataverse Web API v9.2 with a Microsoft Entra OAuth 2.0 client-credentials token, as the plan's vendor research fixed (AAP 0.3.3, 0.4.6; root `DECISIONS.md` section 2, "Vendor API choices"). The created record is the same: entity `contact` with the four attributes `firstname`, `lastname`, `emailaddress1` and `telephone1`, one create per CSV row in file order.
- Prerequisites the requester completes inside the existing tenant (AAP 0.2.4): an Entra app registration with a client secret, and a Dataverse application user for that app with a security role that allows create, read and delete on the `contact` table. No new tenant or sandbox is procured.
- The original README's note on installing the Java Cryptography Extensions no longer applies: Java 17 ships the unlimited-strength cryptography policy by default.
- New behaviour: only the approved connector failure modes above.
- Externalized: the Dynamics credentials and environment URL are `TODO` keys with environment-variable fallbacks. The file endpoint's path, move directory and polling frequency, hard-coded in the original, are configurable keys with the original values as defaults. The timeouts, connector defaults in the original, are configurable keys.
- Stubbed in tests only: the unit tier replaces Entra and Dataverse with WireMock (`ImportContactsIntoMSDynamicsTest`, `client/DynamicsWebApiClientTest`, `config/DynamicsOAuthConfigTest`) or with Mockito mocks (service and trigger tests). At run time nothing is stubbed and no behaviour was dropped.
- Test harness: the test flows `selectContactFromDynamics` and `deleteContactFromDynamics` are replaced by `DynamicsWebApiClient#findContactsByEmail` and `#deleteContact`. The original's fixed 40000 ms sleep is replaced by a direct `pollOnce()` plus bounded Awaitility waits.
- Not carried (AAP 0.2.2): Studio metadata (`catalog/**`, `mule-project.xml`), `src/main/app/mule-deploy.properties`, `src/test/resources/log4j2-test.xml`, the test flows file, and the `./mappings` application home the original test set.

Tier 2A verification points: the plan chose these behaviours where the original runtime, not the flow, decided them. When a captured fixture of the original shows a different result, the fixture governs and the Java code is changed to match.

- The upstream protocol differs. A captured fixture is compared on each created contact's attribute set and on the order of the creates (John, then Jane), not on upstream request bytes. The scenario stays unverified until such a recording exists (`SCENARIOS.md`, "Notes for this project").
- File disposition after an upstream failure: the file is moved to `contacts.move-to-dir`, as after a success.
- A failing record ends the import; earlier contacts are not rolled back and later records are not sent.
- Null cells: a column missing from a short row maps to `null` and is sent as JSON `null`; an empty cell is sent as an empty string.
- An empty or header-only file makes no token request and no Dataverse call, and the file is moved.
- CSV edge cases that no original input exercises (ragged rows, a backslash, a byte order mark), listed in `mapper/DwCsvReader`.

## Build, run and test

Run from `java-conversions/import-contacts-into-ms-dynamics-java/`:

```sh
mvn -B clean install    # build, surefire tests, JaCoCo check, failsafe live test (skipped without credentials), jar installed locally
mvn -B clean package    # build, surefire tests, JaCoCo report and check (LINE >= 0.80 over service/** and mapper/**)
mvn -B test             # surefire tests only; credential-free, no network (WireMock Entra and Dataverse, @TempDir trigger directories)
mvn -B verify           # surefire tests, then the failsafe ImportContactsIntoMSDynamicsIT against a live environment (Tier 2B)
mvn spring-boot:run     # polls src/main/resources/input every 10000 ms; needs the four TODO keys
java -jar target/import-contacts-into-ms-dynamics-java-1.0.0-SNAPSHOT.jar
```

To import the sample, as the original README's steps did, copy the bundled file into the input directory while the application runs:

```sh
mkdir -p src/main/resources/input
cp src/main/resources/contacts.csv src/main/resources/input/
```

Within one polling interval the file moves to `src/main/resources/output/contacts.csv`, and Dynamics 365 lists John Doe and Jane Doe under **Contacts**. Delete both contacts afterwards. For a large file, copy it next to the input directory first and then move it in, so a poll never reads a half-written file.

- `mvn test` needs no licence, credential or network.
- Without the four `TODO` values the application still starts, but every import fails with the `IllegalStateException` naming the first missing key. That failure is none of the three failure modes, so `FileInboundPoller` logs it at ERROR and moves the file.
- Reports land under `target/`: `surefire-reports/`, `failsafe-reports/`, JaCoCo in `site/jacoco/` (`index.html`, `jacoco.csv`), and the fixture-replay summary `fixture-parity/summary.properties`.
- The build also runs the `maven-enforcer-plugin` ban on JAX-WS and CXF at `validate` (AAP 0.3.2).


## Live test (Tier 2B)

`ImportContactsIntoMSDynamicsIT` runs under failsafe (`mvn -B verify`) against a real Dataverse environment of the existing tenant; no new tenant is needed.

1. Copy `src/test/resources/application-it.yml.example` to `src/test/resources/application-it.yml`. The copy is git-ignored by this project's `.gitignore` and by the root `.gitignore` (`**/src/test/resources/application-it.yml`); never commit it.
2. Fill in `dynamics.service-url`, `dynamics.oauth.tenant-id`, `dynamics.oauth.client-id` and `dynamics.oauth.client-secret`. The token endpoint and scope are derived from them by `application.yml`.
3. Run `mvn -B verify`. The test starts the application without a web server under the Spring profile `it`, which loads `application-it.yml`.

The IT writes data, so use a sandbox or test environment, never production:

- It records any contacts with the two e-mails that an earlier run left behind, so only a contact this import created satisfies the assertions.
- It drops `contacts.csv` into its own temporary input directory and runs `ContactsFileTrigger.pollOnce()`, which creates John Doe and Jane Doe.
- It reads each contact back with `$filter=emailaddress1 eq '<e-mail>'` (`DynamicsWebApiClient#findContactsByEmail`), waiting up to 40 s, the original's wait. It asserts `firstname` `John` for `john.doe@texasComp.com` and `lastname` `Doe` for `jane.doe@texasComp.com`, as the original asserted (`ImportContactsIntoMSDynamicsIT.java:105,111`).
- `@AfterEach` deletes every contact read back for the two e-mails with `DELETE contacts(<guid>)`, the earlier ones included. A failed delete is logged, never thrown, as the original only printed it.

While `application-it.yml` or one of the four keys is missing, blank, still `TODO` or an unresolvable placeholder, the class is skipped by its `LiveCredentialsGate` and a `@BeforeAll` assumption. It is reported skipped, never failed, and neither Entra nor Dataverse is called. A skipped run is not Tier 2B evidence.

## Tests

The project has no controller tests, because it has no HTTP listener; the trigger tests stand in for them (AAP 0.8.2).

| Test class | What it asserts |
|---|---|
| `ImportContactsIntoMSDynamicsTest` | the ported `ImportContactsIntoMSDynamicsIT` (`import-contacts-into-ms-dynamics/src/test/java/org/mule/examples/ImportContactsIntoMSDynamicsIT.java:84-125`), with the whole application context against one WireMock server standing in for Entra and Dataverse. `contacts.csv` is dropped into a temporary input directory and `pollOnce()` runs. The test asserts `firstname` `John` for `john.doe@texasComp.com` and `lastname` `Doe` for `jane.doe@texasComp.com`, read back by e-mail. It also asserts the file moved unchanged, exactly two creates in file order with their exact JSON bodies and bearer token, one token request for every call, and both deletes |
| `ImportContactsIntoMSDynamicsIT` | the same two assertions against a live environment (failsafe, previous section) |
| `trigger/ContactsFileTriggerTest` | `contacts.csv` handed byte for byte to the import once and moved to the output directory; a Dataverse connectivity failure and any other import failure logged at ERROR, not retried, file still moved; an empty input directory hands nothing over; `poll()` scheduled with the original 10000 ms fixed delay |
| `trigger/FileInboundPollerTest` | SC-5: move after success and after a handled failure, delete without a move directory, the interrupt flag kept, the in-process mark against re-entrant and concurrent polls, an unopenable file left in place and retried, missing directories created, subdirectories ignored, a same-named file in the move directory replaced |
| `service/DynamicsContactImportServiceTest` | one `createContact` per record of `contacts.csv` in file order, with the four attributes and string phone numbers; a header-only file creates nothing; an unavailable upstream propagates without retry; a vendor error propagates as the same instance |
| `mapper/DynamicsContactMapperTest` | the four record keys in script order; John Doe and Jane Doe in input order; a missing column gives `null`, a `null` row four `null` values; values passed through unchanged, leading zeros kept; extra columns ignored; the input left unmodified; the create attributes `firstname`, `lastname`, `emailaddress1`, `telephone1`, `null` values kept |
| `mapper/DwCsvReaderTest` | SC-4: header row, `,` separator, `"` quoting with the `""` and backslash escapes, LF, CRLF and lone-CR record ends, skipped empty lines, untrimmed string values |
| `client/DynamicsWebApiClientTest` | against WireMock: the create's request and the GUID read from `OData-EntityId`; the cached token reused; a 401 evicting the token and retried once; a second 401 ending the attempt; a 429 with `Retry-After`, a timeout and a refused connection never retried; Dataverse 4xx and 5xx rethrown unchanged; a rejected token request posts no contact; the read-back filter encoding with doubled quotes; the delete by GUID and the rejection of a non-GUID; each blank setting failing the first call by name |
| `config/DynamicsOAuthConfigTest` | against a WireMock Entra: the granted token sent with the OData headers; a throttled token request as a rate limit with its `Retry-After`; Entra 5xx and unreadable token responses rethrown unchanged; client and grant rejections re-authenticated and retried once; a reset connection attempted once; the beans starting with blank credentials; both timeouts applied with one transport attempt; a timeout under 1 ms rejected by name |
| `client/DynamicsFailureClassifierTest`, `client/UpstreamCallExecutorTest` | SC-9 and the Dynamics classification: 401 and Entra rejections as authentication with one re-authenticated retry; 429 as a rate limit carrying `Retry-After` or `null`; every timeout and connectivity cause as unavailable, ahead of a 401 in the same chain, and never retried; everything else rethrown unchanged; the caller-facing message taken from the Dataverse or Entra error text; ERROR lines without vendor text |
| `FixtureParityTest` | ST-2 fixture replay (next section); skipped while no fixture is captured |

## Fixture parity (ST-2)

`FixtureParityTest` replays the Tier 2A fixtures `src/test/resources/fixtures/<scenario>.json`, one per row of [`src/test/resources/fixtures/SCENARIOS.md`](src/test/resources/fixtures/SCENARIOS.md), which also holds the fixture contract, the comparison rules and the capture procedure for this project (AAP 0.8.4). For the `file` trigger the replay places the captured input file, calls `ContactsFileTrigger.pollOnce()`, and compares the produced files, the SC-5 disposition and the recorded upstream calls, which WireMock serves, so replay never contacts a live service. Fixtures are captured from the running original and are never hand-written or edited to match the Java output; a mismatch is fixed in the Java code. With no fixtures the test reports skipped (`no Tier 2A fixtures captured`), never failed.

| Scenario | Trigger | State | Request | Status in the last run |
|---|---|---|---|---|
| `file-contacts-csv-imported` | `file` | `deterministic` | drop `contacts.csv` into the input directory, then `ContactsFileTrigger.pollOnce()` | `UNVERIFIED-uncaptured` |

- No scenario of this project is `unreachable` or `nondeterministic`; the flow has one branch.
- The original's upstream is the Organization SOAP service, which the Java client does not speak, and its recorded sign-in is account sign-in material. The capture therefore omits `upstream`, and the scenario stays `UNVERIFIED-no-upstream` after capture (`SCENARIOS.md`, "Notes for this project"). The project can never be labelled `PARITY: VERIFIED — Tier 2A fixtures`; capture can raise its label at most to `PARITY: PARTIAL — Tier 2A fixtures: 0 verified, 1 unverified`.
- The approved failure modes have no original branch and no fixture row. Their Tier 1 evidence is the classifier, executor, client, configuration, poller and trigger tests above.

## Tier status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| import-contacts-into-ms-dynamics-java | import-contacts-into-ms-dynamics | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |

The root `SUMMARY.md` carries the same row for this project (AAP 0.8.7). Evidence of the run behind it (Temurin JDK 17.0.21, Apache Maven 3.9.9):

- `mvn -B clean install`: `BUILD SUCCESS`. Surefire ran 401 tests: 0 failures, 0 errors, 1 skipped (`FixtureParityTest`). JaCoCo reported `All coverage checks have been met.`. Failsafe ran 1 test, skipped (`ImportContactsIntoMSDynamicsIT`: `src/test/resources/application-it.yml not present`).
- `mvn -B clean package`: `BUILD SUCCESS`; 401 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`); JaCoCo `All coverage checks have been met.`.
- `mvn -B test`: `BUILD SUCCESS`; 401 tests, 0 failures, 0 errors, 1 skipped (`FixtureParityTest`).
- Coverage (service + mapper), from `target/site/jacoco/jacoco.csv`: 106 of 106 lines (100.0%) of `service/DynamicsContactImportService`, `mapper/DynamicsContactMapper` and `mapper/DwCsvReader`. Total project LINE coverage: 90.6% (686 of 757 lines).
- `target/fixture-parity/summary.properties`: `verified=0`, `unverified=1`, `failed=0`.
- Tier 2B: no sandbox credentials were supplied, so `ImportContactsIntoMSDynamicsIT` was skipped. The cell becomes `PASS` or `FAIL` only after a run with `application-it.yml` present.

Tier 1 evidence shows that the project builds and passes its own tests. It is not parity evidence.

## Fallback evidence (AAP 0.8.4)

Tier 2A is blocked: the Mule EE runtime licence and the EE Maven repository credentials are unconfirmed, as recorded in the root [`DECISIONS.md`](../../DECISIONS.md), section 9 ("Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed"). No fixture can be captured from the original, so parity rests on the fallback:

- `ImportContactsIntoMSDynamicsTest` carries the original committed assertions with their comparison semantics: after `contacts.csv` is imported, `firstname` equals `John` for `john.doe@texasComp.com` and `lastname` equals `Doe` for `jane.doe@texasComp.com`.
- The trigger, poller, service, mapper, client and configuration tests cover the flow's one branch and the approved failure handling.
- This evidence is weaker than captured fixtures. It shows that the conversion meets the original's own assertions and the plan's reading of the platform defaults, but not that its requests and file handling equal what the original runtime did. The Dynamics side of these tests is a WireMock stub, not a recording of the original's organization, and it speaks the Web API, not the original's Organization service.
- Behaviours no original assertion covers, tested only by the new tests: the file disposition to `output`; every attribute other than John's `firstname` and Jane's `lastname`; the order of the creates; the default failure branch, a Dynamics rejection logged and the file moved; the failure-mode paths; empty and header-only files; quoted CSV fields.
- Unverified scenarios, until captured: `file-contacts-csv-imported`.

The parity label stays `PARITY: UNVERIFIED — Mule runtime unavailable`, never PASS, until the scenario is captured and replayed (AAP 0.8.7).

## Licence

Apache License 2.0. `LICENSE` and `NOTICE` are copied unchanged from `import-contacts-into-ms-dynamics/`.

