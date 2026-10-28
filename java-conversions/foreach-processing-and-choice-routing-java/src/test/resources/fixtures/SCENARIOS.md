# Parity scenarios — foreach-processing-and-choice-routing-java

Scenarios for the fixture replay (ST-2) of `foreach-processing-and-choice-routing-java`. The original example is `foreach-processing-and-choice-routing` (folder `foreach-processing-and-choice-routing/`), the loan broker `loanbroker-simple`. Its main flow `loan-broker-sync` answers every HTTP request on the `/*` listener of port 11081: it requires the query parameters `name`, `ssn`, `amount` and `term`, builds the customer request, takes the credit profile from the credit agency's `getCreditProfile` on port 18080, chooses the banks by amount, asks each chosen bank for `getLoanQuote` in a `foreach` and returns the lowest quote as text. Six mock flows serve those SOAP calls through `cxf:jaxws-service`: `TheCreditAgencyService` on 18080 and `Bank1Flow` to `Bank5Flow` on 10080 to 50080. Every reachable branch listed for the example in AAP 0.7.5, each mock service's operation, their CXF `?wsdl` metadata and the listener-boundary cases of AAP 0.4.7 are exactly one scenario each below; the unreachable `otherwise` of the bank choice is listed with its proof; and each captured scenario's fixture is `fixtures/<scenario>.json`.

This file follows the reference format kept in `java-conversions/hello-world-java/src/test/resources/fixtures/SCENARIOS.md`: its headings, table header, cell notation and prose sections are copied from there, and its rows come from AAP 0.7.5.

## Scenarios

| Scenario | Trigger | State | Request | Seeded state | Branch | Source |
|---|---|---|---|---|---|---|
| `get-root-amount-20000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=20000&term=48&ssn=1234`, empty body | none | `/*` listener → required-field `when` → `getCreditProfile` on 18080 → `lookupBanks` first `when` (`getLoanAmount() >= 20000`) → Banks 1, 2 → `foreach` `getLoanQuote` → `findLowestLoanQuote` → `object-to-string-transformer`; varies: the random credit profile (`DefaultCreditAgency.java:31,40`) and bank rates (`Bank.java:47`) | `loanbroker-simple.xml:82-83`, `ForEachProcessingAndChoiceRoutingIT.java:33-47` |
| `get-root-amount-15000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=15000&term=48&ssn=1234`, empty body | none | `/*` listener → required-field `when` → `getCreditProfile` on 18080 → `lookupBanks` second `when`, here selected by its `getLoanAmount() >= 10000` operand → Banks 3, 4 → `foreach` `getLoanQuote` → `findLowestLoanQuote` → `object-to-string-transformer`; varies: the random credit profile (`DefaultCreditAgency.java:31,40`) and bank rates (`Bank.java:47`) | `loanbroker-simple.xml:85-86` |
| `get-root-amount-5000` | `http` | `nondeterministic` | port `11081`: `GET /?name=Muley&amount=5000&term=48&ssn=1234`, empty body | none | `/*` listener → required-field `when` → `getCreditProfile` on 18080 → `lookupBanks` second `when`, which always holds and is here selected by its `getLoanAmount() <= 19999` operand → Banks 3, 4 → `foreach` `getLoanQuote` → `findLowestLoanQuote` → `object-to-string-transformer`; the amount of the README's second step; varies: the random credit profile (`DefaultCreditAgency.java:31,40`) and bank rates (`Bank.java:47`) | `loanbroker-simple.xml:85-86`, `README.md:53` |
| `get-root-bank5-otherwise` | `http` | `unreachable` | `—` (no request invented) | none | `lookupBanks` `otherwise` → Bank 5; proof: the second `when` holds for every amount, because any amount below 10000 satisfies `getLoanAmount() <= 19999` and any other satisfies `getLoanAmount() >= 10000`, so no amount reaches the `otherwise` (AAP 0.8.2) | `loanbroker-simple.xml:88-89` |
| `get-root-incomplete-request` | `http` | `deterministic` | port `11081`: `GET /?name=Muley&amount=20000&term=48`, empty body (`ssn` absent) | none | `/*` listener → required-field `otherwise` (a parameter is `null`) → `expression-component` `payload="Error: incomplete request"` | `loanbroker-simple.xml:28-29,57-58` |
| `get-root-non-numeric-amount` | `http` | `deterministic` | port `11081`: `GET /?name=Muley&amount=abc&term=48&ssn=1234`, empty body | none | `/*` listener → required-field `when` → `Integer.parseInt` of `amount` throws in `Create customer request` → `catch-exception-strategy` → `set-payload Error processing loan request` | `loanbroker-simple.xml:33-36,62-64` |
| `post-credit-agency-get-credit-profile` | `soap` | `nondeterministic` | port `18080`: `POST /mule/TheCreditAgencyService/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B1` (Request notes: `getCreditProfile`, `arg0` with `name` Muley and `ssn` 1234) | none | `TheCreditAgencyService` flow → `cxf:jaxws-service` → `DefaultCreditAgency.getCreditProfile`; varies: the random `creditHistory` and `creditScore` (`DefaultCreditAgency.java:31,40`) | `loanbroker-simple.xml:7,138-144` |
| `post-bank1-get-loan-quote` | `soap` | `nondeterministic` | port `10080`: `POST /mule/TheBank1/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B2` (Request notes: `getLoanQuote`, `arg0` with `creditProfile` only) | none | `Bank1Flow` → `cxf:jaxws-service` → `Bank.getLoanQuote` of the component whose `bankName` is `Bank #1` → rate tier chosen by `creditScore` 600 (`Bank.java:51-80`); varies: the rates drawn when the component is created (`Bank.java:47`) | `loanbroker-simple.xml:8,146-154` |
| `post-bank2-get-loan-quote` | `soap` | `nondeterministic` | port `20080`: `POST /mule/TheBank2/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B2` (Request notes: `getLoanQuote`, `arg0` with `creditProfile` only) | none | `Bank2Flow` → `cxf:jaxws-service` → `Bank.getLoanQuote` of the component whose `bankName` is `Bank #2` → rate tier chosen by `creditScore` 600 (`Bank.java:51-80`); varies: the rates drawn when the component is created (`Bank.java:47`) | `loanbroker-simple.xml:9,156-165` |
| `post-bank3-get-loan-quote` | `soap` | `nondeterministic` | port `30080`: `POST /mule/TheBank3/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B2` (Request notes: `getLoanQuote`, `arg0` with `creditProfile` only) | none | `Bank3Flow` → `cxf:jaxws-service` → `Bank.getLoanQuote` of the component whose `bankName` is `Bank #3` → rate tier chosen by `creditScore` 600 (`Bank.java:51-80`); varies: the rates drawn when the component is created (`Bank.java:47`) | `loanbroker-simple.xml:10,167-175` |
| `post-bank4-get-loan-quote` | `soap` | `nondeterministic` | port `40080`: `POST /mule/TheBank4/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B2` (Request notes: `getLoanQuote`, `arg0` with `creditProfile` only) | none | `Bank4Flow` → `cxf:jaxws-service` → `Bank.getLoanQuote` of the component whose `bankName` is `Bank #4` → rate tier chosen by `creditScore` 600 (`Bank.java:51-80`); varies: the rates drawn when the component is created (`Bank.java:47`) | `loanbroker-simple.xml:11,177-185` |
| `post-bank5-get-loan-quote` | `soap` | `nondeterministic` | port `50080`: `POST /mule/TheBank5/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B2` (Request notes: `getLoanQuote`, `arg0` with `creditProfile` only) | none | `Bank5Flow` → `cxf:jaxws-service` → `Bank.getLoanQuote` of the component whose `bankName` is `Bank #5` → rate tier chosen by `creditScore` 600 (`Bank.java:51-80`); varies: the rates drawn when the component is created (`Bank.java:47`) | `loanbroker-simple.xml:12,187-195` |
| `get-credit-agency-wsdl` | `soap` | `deterministic` | port `18080`: `GET /mule/TheCreditAgencyService?wsdl`, empty body | none | `TheCreditAgencyService` listener → CXF `?wsdl` metadata of the `DefaultCreditAgency` service (SC-11 `WsdlQueryPublisher` over the authored `wsdl/CreditAgencyService.wsdl`) | `loanbroker-simple.xml:7,138-144` |
| `get-bank1-wsdl` | `soap` | `deterministic` | port `10080`: `GET /mule/TheBank1?wsdl`, empty body | none | `Bank1Flow` listener → CXF `?wsdl` metadata of the `Bank` service (SC-11 `WsdlQueryPublisher` over the authored `wsdl/BankService.wsdl`) | `loanbroker-simple.xml:8,146-154` |
| `post-bank1-malformed-envelope` | `soap` | `deterministic` | port `10080`: `POST /mule/TheBank1/*`, header `Content-Type: text/xml; charset=UTF-8`, header `SOAPAction: ""`, body = envelope `B3` (Request notes: a truncated envelope) | none | `Bank1Flow` → `cxf:jaxws-service` cannot read the body as a SOAP 1.1 envelope → client fault before any operation is dispatched (AAP 0.4.7, SC-11 `SoapListenerBoundaryAdapter`); a Tier 2A verification point where the captured bytes govern | `loanbroker-simple.xml:8,146-154` |
| `get-credit-agency-path-on-bank1-port` | `http` | `deterministic` | port `10080`: `GET /mule/TheCreditAgencyService`, empty body | none | port 10080 has only the `/mule/TheBank1` listener, so no listener serves the credit agency's path there → no-listener platform default (AAP 0.4.7, SC-10 `PortRoutingFilter`); a Tier 2A verification point where the captured bytes govern | `loanbroker-simple.xml:7-8` |

Nine scenarios are `nondeterministic`: `get-root-amount-20000`, `get-root-amount-15000`, `get-root-amount-5000`, `post-credit-agency-get-credit-profile` and `post-bank1-get-loan-quote` to `post-bank5-get-loan-quote`. Six are `deterministic`: `get-root-incomplete-request`, `get-root-non-numeric-amount`, `get-credit-agency-wsdl`, `get-bank1-wsdl`, `post-bank1-malformed-envelope` and `get-credit-agency-path-on-bank1-port`. One, `get-root-bank5-otherwise`, is `unreachable`.

Request notes:

- The path suffix `/*` is the literal path the original's own client sends: each `http:request` posts to `path="/*"` below the target listener's base path (`loanbroker-simple.xml:71-73,106-108`). Capture and replay send it verbatim, `*` included.
- Each `soap` body is one of the envelopes below: a SOAP 1.1 document/literal-wrapped request whose child elements carry no namespace (AAP 0.3.2). A body is exactly the bytes of its block's single line, without a line ending; Capture step 4 writes it to the scenario's evidence directory and sends that file.
  - `B1`: `getCreditProfile` in namespace `http://creditagency.loanbroker.example.mule.org/`; `arg0` is a `Customer` with `name` Muley and `ssn` 1234, the customer of the original test (`ForEachProcessingAndChoiceRoutingIT.java:47`).

    ```xml
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cre="http://creditagency.loanbroker.example.mule.org/"><soapenv:Body><cre:getCreditProfile><arg0><name>Muley</name><ssn>1234</ssn></arg0></cre:getCreditProfile></soapenv:Body></soapenv:Envelope>
    ```

  - `B2`: `getLoanQuote` in namespace `http://bank.loanbroker.example.mule.org/`; `arg0` is a `LoanBrokerQuoteRequest` that carries only `creditProfile`, as the loan broker builds it (`loanbroker-simple.xml:100-104`). All five bank rows send the same fixed profile, `creditHistory` 10 and `creditScore` 600: `Bank.getLoanQuote` reads only `creditScore`, which picks its rate tier (`Bank.java:51-80`), so one fixed input keeps the five rows comparable. These are inputs, not expected values.

    ```xml
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:bank="http://bank.loanbroker.example.mule.org/"><soapenv:Body><bank:getLoanQuote><arg0><creditProfile><creditHistory>10</creditHistory><creditScore>600</creditScore></creditProfile></arg0></bank:getLoanQuote></soapenv:Body></soapenv:Envelope>
    ```

  - `B3`: an envelope truncated after the opening `soapenv:Body` tag, so the body is not well-formed XML.

    ```xml
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
    ```

Notes for this project:

- Source cells name the original files by base name: `loanbroker-simple.xml` is `foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml`, `ForEachProcessingAndChoiceRoutingIT.java` is `foreach-processing-and-choice-routing/src/test/java/org/mule/examples/ForEachProcessingAndChoiceRoutingIT.java`, `README.md` is `foreach-processing-and-choice-routing/README.md`, `Bank.java` is `foreach-processing-and-choice-routing/src/main/java/org/mule/example/loanbroker/bank/Bank.java` and `DefaultCreditAgency.java` is `foreach-processing-and-choice-routing/src/main/java/org/mule/example/loanbroker/creditagency/DefaultCreditAgency.java`.
- Ports: every `Request` starts with the listener port the capture targets. The five `11081` rows reach the main listener (`loanbroker-simple.xml:4`), which replays on the random `server.port`, so their fixtures leave `request.portProperty` at its default `local.server.port`; they are the only rows that use the replay port, and, as in AAP 0.8.4, the port inside `Location` is the only substitution. Every other row targets one of the six extra listeners of SC-10, which `src/main/resources/application.yml` binds to `ports.credit-agency` (`18080`) and `ports.bank1` to `ports.bank5` (`10080`, `20080`, `30080`, `40080`, `50080`); its fixture names that key in `request.portProperty` and the captured port in `request.port`.
- Pinned listener ports: every fixture of this project carries the same `state.properties`, which pin those six keys to the captured ports with plain values, the form the reference allows besides its placeholders: `{"ports.credit-agency": 18080, "ports.bank1": 10080, "ports.bank2": 20080, "ports.bank3": 30080, "ports.bank4": 40080, "ports.bank5": 50080}`. A `${freePort:<name>}` port is not used, because the `?wsdl` bodies carry the listener's own address in `soap:address` and replay substitutes no port outside `Location`; on the captured port, the `Host` the replay writes and the address the Java listener publishes name the same port the capture used. Identical values in every fixture keep the one replay context consistent, and the loan broker's own calls reach the extra listeners on the same ports through `loanbroker.outbound-host` (`0.0.0.0`). Replaying captured fixtures therefore needs ports 18080 and 10080 to 50080 free on the host; a port in use stops the application context and fails the class. These properties configure listeners and seed no data, so every row's `Seeded state` is `none`.
- Upstream capture: `get-root-incomplete-request` and `get-root-non-numeric-amount` make no outbound call, and neither does any row sent straight to a mock listener, so all of them carry `"upstream": []`. The three complete-request rows call `getCreditProfile` on 18080 and then `getLoanQuote` on two banks; those calls go to the original's own mock flows at the host `0.0.0.0` and the ports the flow hard-codes (`loanbroker-simple.xml:5-6,83-89`). No placeholder redirects them, and the original folder is never modified, so their fixtures omit `upstream` (Capture step 2). A fixture captured without an `upstream` recording is reported `UNVERIFIED-no-upstream`.
- Unreachable branch: the second `when` of `lookupBanks` (`getLoanAmount() >= 10000` or `getLoanAmount() <= 19999`) holds for every amount, because any amount below 10000 satisfies `<= 19999`, so the `otherwise` (Bank 5) is never selected (`loanbroker-simple.xml:81-91`). The Java code keeps it exactly as declared; `get-root-bank5-otherwise` is excluded from capture and from the branch-coverage claim, has no fixture and reports `UNREACHABLE`, and `LoanBrokerServiceTest` asserts that the boundary amounts −1, 0, 9999, 10000, 19999 and 20000 never route to Bank 5 (AAP 0.8.2).
- Nondeterministic rows: the outputs of the nine `nondeterministic` rows come from random credit profiles (`DefaultCreditAgency.java:31,40`) and from bank rates drawn when each bank component is created (`Bank.java:47`). They are compared for structure only and are never presented as byte-for-byte evidence: the six mock-service rows replay with `"upstream": []` and report `UNVERIFIED-nondeterministic`, and the three complete-request rows, whose fixtures omit `upstream`, report `UNVERIFIED-no-upstream`. They are escalated as AAP 0.8.8 sets out: through `DECISIONS.md`, the person who receives the Tier 2A notification receives their list with this reason, and a row's status changes only on that person's reproducibility input (a fixed random seed or clock for the original) or on a recorded acceptance, which the README shows as `ACCEPTED — structural comparison` and which still counts as unverified.
- Authored WSDLs: `get-credit-agency-wsdl` and `get-bank1-wsdl` are `deterministic`. A captured WSDL replaces the authored `src/main/resources/wsdl/CreditAgencyService.wsdl` or `src/main/resources/wsdl/BankService.wsdl` where they differ, and the replacement is recorded in the root `DECISIONS.md` (AAP 0.3.2). One `BankService.wsdl` serves all five banks, so only Bank 1's `?wsdl` is listed.
- Parity label: until fixtures are captured, this project carries `PARITY: UNVERIFIED — Mule runtime unavailable` (AAP 0.8.7), the label of `java-conversions/foreach-processing-and-choice-routing-java/README.md` and of this project's row in the root `SUMMARY.md`. After capture it is at most `PARITY: PARTIAL — Tier 2A fixtures: 6 verified, 9 unverified`, or `PARITY: FAIL — Tier 2A fixtures` when any captured deterministic row differs; `PARITY: VERIFIED — Tier 2A fixtures` is impossible while the nine `nondeterministic` rows stay unverified. The README lists as unverified the nine `nondeterministic` scenarios, and the six `deterministic` ones until each is captured and `VERIFIED`.

Table rules, for every project that copies this format:

- The header is exactly the seven columns above, with these names and in this order, followed by a separator row of `-` cells. `FixtureParityTest` fails the class on any other header, a missing separator row or an empty table.
- Cells are split on unescaped `|`, so a `|` inside a cell is written `\|`. Each cell is trimmed and unescaped, and its surrounding backticks are stripped, so code spans are allowed. The table ends at the first line that does not start with `|`.
- `Scenario` is kebab-case (`[a-z0-9]+(-[a-z0-9]+)*`), unique in the file, formed from the entry point and the branch (AAP 0.8.4), and equal to the fixture file name without `.json`.
- `Trigger` is one of `http`, `soap`, `file`, `jms`, `amqp`, `mq`, `mail`, `scheduler`. It selects the replay driver.
- `State` is one of `deterministic`, `nondeterministic`, `unreachable`. A `nondeterministic` row names in `Branch` what varies (the current time, random values, a generated name). An `unreachable` row states in `Branch` the proof that no input selects it (AAP 0.8.2).
- `Request` is the exact input the capture sends, written as: `METHOD /path` (with `?query` when there is one); then each request header as header `Name: value`, in sending order; then the body as `empty body`, as an inline code span written byte for byte (body `ping`), or as body = the bytes of `<file>` (`src/test/resources`) for a file body, which is never inlined. Non-HTTP rows name the input file, message or mail, and the single-run method the replay invokes.
- `Seeded state` is `none`, or a short description of what the fixture's `state` seeds (rows, files, messages, mailboxes, a watermark).
- `Branch` names the flow path the request selects. Outcome words written there name the branch. They are never captured values; the captured fixture alone holds the response.
- `Source` cites the original by file and line.
- Every row of this file is captured by sending its request to the unmodified original, deployed on the ports its flow fixes (see Capture).

## Fixture contract

Fixtures are `fixtures/<scenario>.json` beside this file (`src/test/resources/fixtures/`), loaded from the classpath as `classpath*:fixtures/*.json`. With `-Dfixtures.dir=<dir>` set, the test reads `<dir>/SCENARIOS.md` when it exists and loads only `<dir>/*.json`; this override is for local engine checks. There is one fixture per row, and a fixture without a matching row fails the class. Each fixture is one JSON object with the four top-level sections of AAP 0.8.4: `request` and `response` are required, `state` and `upstream` are read when present, and any other top-level key is ignored. Every value is written unedited from the capture.

`request`, for `http` and `soap`:

- `method`: the request method as sent.
- `path`: the request path, without the query.
- `query`: the raw query string without `?`, appended as `?<query>` when non-empty; `""` when there is none.
- `headers`: an ordered array of `[name, value]` pairs, in the order they were sent. `Host` is recorded as sent. Only transport fields are regenerated: the replay drops the recorded `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` and writes its own `Host` for the replay port, and ST-1 writes `Content-Length` for the body and `Connection: close`. Every other recorded pair is sent as its own header line, in recorded order, through ST-1's `HttpTestClient.sendHeaderPairs`; a repeated name, such as two `Cookie` pairs, stays on separate lines and is never joined.
- `body` (UTF-8 text) or `bodyBase64` (binary); an empty body is `""` or omitted.
- `port`: the listener port the capture targeted (`11081`, `18080` or one of `10080` to `50080` in this project, as the row's `Request` names it). It is the port replaced inside `Location` on replay.
- `portProperty`: optional; the Spring property that holds the replay port of the targeted listener, default `local.server.port`. Multi-port projects (SC-10) name the extra listener's port key here.
- `tls`: optional object `{trustStore, type, password, keyStore, keyStoreType, keyStorePassword}` for HTTPS listeners, replayed through ST-1's TLS mode; each member is a string, and only `trustStore` is required. `type` is the store type (default `JKS`) and `password` the trust store's password. `keyStore` is a client key store, for a listener that requires a client certificate: with it the replay sends through ST-1's key-store overload `HttpTestClient.https(host, port, trustStorePath, trustStoreType, trustStorePassword, keyStorePath, keyStoreType, keyStorePassword)`, which presents the store's client key; `keyStoreType` defaults to `type`, and `keyStorePassword` is the password of the key store and of its key entries. Without `keyStore` no client certificate is presented. `keyStoreType` or `keyStorePassword` without `keyStore` fails the scenario, and a project whose ST-1 lacks the key-store overload reports a scenario with `keyStore` `UNVERIFIED-missing-driver`. `trustStore` and `keyStore` resolve from the classpath first, then as a file.
- `readTimeoutMillis`: optional; the replay's socket read budget for this request, a positive whole number of milliseconds, applied through ST-1's `HttpTestClient.withReadTimeout` to every read of the reply, the TLS handshake's included; default 10000, ST-1's `TIMEOUT_MILLIS`, which also stays the connect timeout. Like `awaitMillis`, it is a replay setting, not a captured value: a fixture sets it when the endpoint itself waits about 10000 ms or longer before it answers, so that the replay reads the captured answer instead of timing out before the comparison.

`request`, for the other drivers:

- `file`: `{directory, name, body}` or `{directory, name, bodyBase64}`; the input file placed in the trigger's input directory before the run.
- `invoke`: `{bean, method}` or `{type, method}`; the single-run method the replay calls. `bean` is a bean name; `type` is a class name, fully qualified or relative to the test package (for example `{"type": "trigger.OrderFileTrigger", "method": "pollOnce"}`). It is required for the `file`, `scheduler` and `mail` triggers: scheduled triggers do not run during replay (see the replay boundary below), so such a fixture without `invoke` fails its scenario.
- `awaitMillis`: how long the replay waits for asynchronous effects; default 10000.
- `message`: for `jms` and `mq`, `{destination, pubSub, body or bodyBase64, properties}`; for `amqp`, `{exchange, routingKey, body or bodyBase64, properties}`. `body` is sent as a text message and `bodyBase64` as bytes.
- `mail`: `{user, mimeBase64}`; the raw MIME message to deliver, Base64-encoded, and the email address of the receiving mailbox.

`state`, seeded before the run:

- `properties`: Spring property overrides `{key: value}`, registered before the application context starts. Values may use the placeholders below. The same key resolving to different values in two fixtures fails the class.
- `notContacted`: an array of property keys, each a non-blank string, that the original never connected to, such as an OAuth authorization URL it only returned in a redirect, or a CORS origin. The replay boundary below does not check them; the keys of every fixture together apply to the one application context.
- `directories`: `{<directory>: [{name, body or bodyBase64}]}`, the files seeded per named directory. A scheduler watermark is seeded here as the SC-8 store file.
- `tables`: `{<table>: [{<column>: <value>}]}`, the rows inserted after the table is emptied. Table and column names match `[A-Za-z0-9_]+`. Each table must already exist, created when the application starts or by an earlier scenario in table order: the replay creates no table, so a seeded table that does not exist fails the scenario before any row is written.
- `collections`: `{<collection>: [<document>]}`, MongoDB documents as Extended JSON.
- `ldap`: `{baseDn, bindDn, bindPassword, ldif}`, where `ldif` is a classpath resource or inline LDIF text.
- `amqp`: `{initialConfigurationResource, responders}`. `initialConfigurationResource` is the classpath Qpid Broker-J configuration that declares the broker objects and messages. `responders`, present when the capture ran a responder, is `[{queue, reply: {body or bodyBase64, properties}}]`, one entry per responder: it took one request off `queue` and published `reply`, with `properties` as its application headers and the request's correlation id, to the request's reply-to address. The replay starts each responder through the project's `RabbitTemplate` after the drain and before the input is sent, and joins it before comparing the effects; no request on `queue` within `awaitMillis`, a request without a reply-to address or a reply that cannot be sent fails the scenario.
- `jms`: `{denySend: [{destination, pubSub}]}`, the destinations whose producer sends the capture's broker refused; `pubSub` is `true` for a topic and `false`, the default, for a queue. From before the input is sent until the reset, the application's embedded Artemis server refuses every send to them, the application's own included, as Artemis refuses a send its security settings deny: the send fails with a security exception, and a transacted session's transaction is marked rollback-only, so its commit fails. A project whose JMS connection factory does not reach an embedded Artemis server in-VM reports the scenario `UNVERIFIED-missing-driver`.
- `mail`: `{users: [{email, login, password}]}`, the mailboxes.
- `clearCaches`: optional, `true` or `false`, the default. `true` stands for the restart the capture made before the scenario, after which the original's in-memory stores were empty: once the `state` is seeded, and before the prior requests and the input, the replay empties every cache of every `org.springframework.cache.CacheManager` bean of the application context. A project without such a bean reports the scenario `UNVERIFIED-missing-driver`, since the replay cannot re-create the empty stores the capture started from. Without `clearCaches`, the application's caches and its other in-memory state carry over from the scenarios before, in table order.
- `requests`: optional; the prior requests the capture sent before the scenario's own (Capture step 4), in the order sent, each an object of the `request` shape for `http` and `soap` above (`method`, `path`, `query`, `headers`, `body` or `bodyBase64`, `portProperty`, `tls`, `readTimeoutMillis`). The replay sends them through ST-1 after the `state` is seeded, its caches cleared and the `upstream` stubs installed, and before the input. Their replies are discarded, as the capture discarded them, whatever their status; one that cannot be sent, or whose reply cannot be read, fails the scenario. Everything else they cause belongs to the scenario: their outbound calls are recorded in `upstream`, in call order, and the effects read back after the run (files, messages, rows, documents, mails) include theirs. Only their log events are left out: the replay drops the events logged before the input, as `response.logs` holds the events from the input on.
- For this project, `state` holds only `properties`, the same six entries in every fixture: `ports.credit-agency` `18080` and `ports.bank1` to `ports.bank5` `10080`, `20080`, `30080`, `40080` and `50080` (see the notes under Scenarios). The original reaches no store.

`upstream`, the recorded outbound calls:

- An ordered array of `{request, response}` pairs, one per outbound call the original made, in call order. `request` holds `method`, `url`, `headers` and `body` or `bodyBase64`; `response` holds `status`, `headers` and `body` or `bodyBase64`. Headers are either `[[name, value]]` pairs or a `{name: value}` object. Each pair is converted from the request journal of the WireMock recording proxy (Capture steps 2 and 7): `url` is the path and query relative to the WireMock base, so no live host is named, and bodies are the entity bytes exchanged, de-chunked, with a response `Content-Encoding` left encoded.
- On replay WireMock serves the recording in order. Every recorded call must be made, in order, with the same method, URL, body and headers, and no other call may be made. `Host`, `Content-Length`, `Connection` and `Transfer-Encoding` are ignored; `User-Agent` and `Accept-Encoding` are compared only when the inbound `request.headers` carried the same header with the same value.
- `"upstream": []` means the scenario was recorded and made no outbound calls. This is true of every scenario of this project except `get-root-amount-20000`, `get-root-amount-15000` and `get-root-amount-5000`, whose fixtures omit `upstream` (next item).
- A missing `upstream` key means the recording is incomplete: the scenario is skipped and reported `UNVERIFIED-no-upstream`. Capture leaves it out where an exchange cannot be recorded locally or without live values (steps 2, 7 and 8).

`response`, the captured result:

- `status` and `reason`: the status code, as a JSON number, and the reason phrase of the final raw status line.
- `headers`: `{Content-Type, Location}`, each only when the original sent it, with its value as received. An absent or null value means the Java response must not send that header. Other headers are not recorded.
- `body` (UTF-8 text) or `bodyBase64` (binary): the entity bytes with only the transport framing removed (chunked coding de-chunked, a `Content-Length` body taken exactly) and any `Content-Encoding` left encoded, extracted from the raw capture as Capture step 6 defines.
- Non-HTTP effects:
  - `directories`: the exact final file set of each named directory, so a consumed input directory is `[]` (SC-5 disposition).
  - `messages`: `[{destination, pubSub, body or bodyBase64, properties}]`, the messages the capture found on the destinations of `destinations`, each destination's in the order the capture received or browsed them; on an AMQP broker, `destination` is the queue. Bodies and the listed properties are compared, and every destination of `destinations` must hold exactly its entries, so one without entries must stay empty.
  - `destinations`: `[{destination, pubSub}]`, every destination the capture subscribed to or browsed, in capture order, those it found empty included; `pubSub` is `true` for a topic and `false`, the default, for a queue. It is present exactly when `messages` is, lists each destination once and lists the destination of every `messages` entry; on an AMQP broker every entry is a queue. A fixture that breaks one of these rules fails its scenario. Before the input is sent, the replay subscribes to each topic and drains each queue, a JMS queue through a consumer it closes again at once, so the replay never takes a message the application consumes. After every other effect has matched, it compares each destination with its `messages` entries: a topic's and an AMQP queue's messages are received in order, each within `awaitMillis`, and no further one may arrive within 500 ms; a JMS queue is browsed, not consumed, until it holds exactly its entries within `awaitMillis`, and then every 100 ms until it has held exactly them throughout a 500 ms quiet window.
  - `tables`: the rows of every table the flow writes.
  - `collections`: the stored documents, without `_id`.
  - `mails`: `[{mimeBase64}]`, the mails the flow sent during the run, as raw MIME. The replay compares them with every mail GreenMail holds after the run, in any mailbox, except the input mail the replay delivered itself, which this comparison leaves out whether or not the flow removed it.
  - `mailboxes`: `{<email>: [{mimeBase64}]}`, the final contents of each named mailbox after the run, keyed by an email of `state.mail.users`: one raw MIME entry per message still in the mailbox, in mailbox order, the delivered input mail included when the flow did not remove it, and `[]` for a mailbox the capture found empty. The replay compares the message count first, then each message; a message flagged deleted but not expunged still counts.
  - `logs`: `[{logger, level, message}]`, every event the original's log shows, from the input to the end of the run, under each logger category an entry names, in logged order and whatever its level. The replay compares the events logged under those categories, and no others, with the entries one for one: the same number of events, each with its entry's `logger` and `level` and, in a `deterministic` scenario, exactly its `message` (an absent or null `message` reads as empty), so an extra, missing or reordered event fails the scenario. It waits up to `awaitMillis` for that stream to match, then compares it every 100 ms until it has matched throughout a 500 ms quiet window, so a further event arriving late fails too. `"logs": []` names no logger and constrains nothing.

Placeholders, allowed in `state.properties` values, in `request.file.directory` and in directory keys, and resolved at replay:

- `${wiremock.baseUrl}` (`http://localhost:<port>` of the WireMock server that serves `upstream`) and `${wiremock.port}`.
- `${dir:<name>}`: a temporary directory `<name>`, created on first use. A directory key or value without `${` is read as `${dir:<value>}`. Every directory key, `request.file.directory` and directory-spec `state.properties` value (one that begins with `${dir:`) must resolve inside the replay's temporary root; `..` traversal, an absolute path outside it or a symbolic link out of it fails the fixture. Each directory a key or `request.file.directory` resolves to is created when used and emptied after every scenario; a `state.properties` value is checked but not created, since it may name a file.
- `${freePort:<name>}`: a port the replay owns, the same one for every use of the same name and distinct from the port of every other name. The embedded servers bind a port themselves and publish it under their names, `amqp` (Qpid Broker-J), `ldap`, `greenmail.<proto>` and `mongo`, so `${freePort:amqp}` is the broker's AMQP port. Any other name, or one of these whose server's library the project lacks, gets a port the replay reserves until the class ends with an unconnected socket on the wildcard address. Until the application context starts its listeners, that socket is bound without `SO_REUSEADDR`, so no other process and no other name binds the port, with `SO_REUSEADDR` or without it, and a connection to it is refused. Just before the embedded web server binds its listeners, the replay swaps it for a socket bound with `SO_REUSEADDR` on the same port, so the application's listeners, which set `SO_REUSEADDR` as Undertow does, bind beside it. Only between this handover and the end of the context's refresh can another socket bind the port as well: one that sets `SO_REUSEADDR` throughout, and any socket in the instant each swap takes. Once the context has refreshed, a port nothing listens on is held without `SO_REUSEADDR` again, so it stays a refused connection and a listener that binds only later cannot bind it, and a port a listener bound is left to that listener, which keeps every other socket off it; a name first used after that is held without `SO_REUSEADDR` at once. A host whose sockets do not let such a listener bind beside a shared socket gets each reservation released, not shared, at the handover, with a warning, so another process can take a port until the application's listener has bound it; once the context has refreshed, each port nothing listens on is held without `SO_REUSEADDR` again, and the ports stay distinct there too.
- `${greenmail.<proto>.port}`, where `<proto>` is `smtp`, `smtps`, `imap`, `imaps`, `pop3` or `pop3s`: the port GreenMail's server for that protocol bound.
- `${mongo.uri}` and `${ldap.port}`: the address of the embedded MongoDB server and the port the embedded LDAP server bound.

Any other placeholder fails the class with a message naming the placeholder and the fixture.

Replay boundary, applied to the application context before any of its beans exists, so replay never contacts a live service:

- Scheduling is suppressed: Spring's `@Scheduled` processor is removed, so no `@Scheduled` method or `SchedulingConfigurer` task runs by itself. The only trigger executions are the single-run invocations the fixtures name in `request.invoke`; outside replay the application keeps its schedules.
- Every key of the context's property sources, except the host's environment variables and system properties, is checked with its effective value. The Spring Boot connection keys Boot binds from those two sources as well are read through them whenever they resolve, under their relaxed names such as `SPRING_DATASOURCE_URL`: `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `spring.r2dbc.url`, `spring.mail.host`, `spring.mail.port`, `spring.data.mongodb.uri`, `spring.data.mongodb.host`, `spring.data.mongodb.port`, `spring.ldap.urls`, and the Spring AMQP and Artemis keys whose defaults are checked below. A target the replay owns is a loopback host (`localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`) on the WireMock port, a `${freePort:<name>}` port or an embedded server's port, or an in-JVM address (`vm:`, `file:`, `classpath:`, `jar:`). The checks cover every `scheme://host[:port]` in a value, each host of a multi-host or failover list included, with the host read after any user information and the scheme's default port when none is given; a value starting with `jdbc:`, in any case, whose location before its `;` or `?` parameters has no `://`: without an `@` there, an `h2`, `derby`, `hsqldb` or `sqlite` URL such as `jdbc:h2:mem:<name>` is an embedded database and names no network target (the table-state rule below decides whether the replay may write to it), and any other subprotocol fails closed, since it names no address the replay can place on a replay-owned target; with an `@`, as in `jdbc:oracle:thin:@host:1521:sid`, `jdbc:oracle:thin:@//host:1521/service` or `jdbc:oracle:oci:@alias`, the `host[:port]` after the last `@`, read without a leading `//`, a `/service` or a `:sid`, must be a loopback host on a replay-owned port; a `host(port)` list; a key whose last segment ends with `host` or is `hostname`, read with its sibling `port`; a key ending with `url`, `uri` or `endpoint` whose value is empty, from which a client derives a default; and, when the client library is present, the Spring Boot defaults of Spring AMQP (`localhost:5672`), Artemis (`tcp://localhost:61616` unless it runs embedded), the MongoDB driver (`localhost:27017`) and Spring LDAP (`ldap://localhost:389`). A host is checked whatever characters it holds, so a pattern or template such as `http://localhost*` is a target the replay does not own; only port `0` on a loopback host, the application's own random listener, is skipped.
- Any other target fails the class before the application context starts, with a message naming each key, its value, the target's `host:port` and the remedy: override the key through `state.properties` with a replay-owned value (`${wiremock.baseUrl}`, `${freePort:<name>}`, an in-memory URL), or list it in `state.notContacted`.
- WireMock runs whenever a fixture is loaded, so an outbound HTTP call the recording lacks fails its scenario, `"upstream": []` included.
- Table state is seeded, emptied and read only when the application's DataSource is connected to a database the replay owns: an in-memory database (`jdbc:h2:mem:`, `jdbc:derby:memory:`, `jdbc:hsqldb:mem:`); an embedded file database whose path lies inside the replay's temporary root (H2 `jdbc:h2:file:<path>` or `jdbc:h2:<path>`, Derby `jdbc:derby:<name or path>` or `jdbc:derby:directory:<path>`, HSQLDB `jdbc:hsqldb:file:<path>`), read without its `;` or `?` parameters, with `~` as `user.home`, a relative path against `h2.baseDir` or `derby.system.home` when set and `user.dir` otherwise, and no symbolic link leading out of the root; or a server whose every host is a loopback address on a replay-owned port. Any other URL, an unknown embedded form included, fails the scenario before any statement runs.
- Collection state is dropped, seeded and read only when every `com.mongodb.client.MongoClient` bean of the context, the client the application's `MongoTemplate` works through, is configured solely for servers whose host is a loopback address on a replay-owned port, such as the embedded MongoDB server behind `${mongo.uri}`. Any other server address, a DNS seed list (`mongodb+srv://`), or a `MongoTemplate` without that client type or without a client bean fails the scenario before any collection is dropped, seeded or read.
- Diagnostics mask credentials: a value whose key's last segment names a secret (a word such as `password`, `secret`, `token`, `key` or `auth`) is shown as `***`; otherwise URI user information (`scheme://***@host`), the user part before `@` of a JDBC URL (`jdbc:oracle:thin:***@host`) or an address-list entry, and parameters with such names (`password=***`) are masked. Keys, hosts and ports stay readable.
- Each scenario starts from a reset (WireMock, the registered directories, the fixture's tables, the captured log events and the mailboxes) and gets its `upstream` stubs before its `state` and input are written. The reset empties each table of `state.tables` and `response.tables` that exists in the replay database; a table that does not exist yet, such as one the scenario's own request creates, is left absent and is never created, and no table is dropped. The reset clears no application cache and no other in-memory state of the application, so scenarios that build on one another in table order keep doing so; only a fixture's `state.clearCaches` empties the application's caches, once its `state` is seeded. Its `state.requests` are sent after that, and its input last. The reset after a scenario also stops its responders, drains the JMS queue it sent its input to and every queue of its `destinations`, closes its topic subscriptions and removes its send refusals, so the next scenario's broker holds none of them.

Skeleton of a fixture of this project, here for a bank row; every `request` and `response` value comes from the capture, and `state` is written as Capture step 8 defines:

```json
{
  "request":  { "method": "<captured>", "path": "<captured>", "query": "<captured>",
                "headers": [["<captured>", "<captured>"]], "body": "<captured>",
                "port": "<captured>", "portProperty": "ports.bank1" },
  "state":    { "properties": { "ports.credit-agency": 18080, "ports.bank1": 10080, "ports.bank2": 20080,
                                "ports.bank3": 30080, "ports.bank4": 40080, "ports.bank5": 50080 } },
  "upstream": [],
  "response": { "status": "<captured>", "reason": "<captured>",
                "headers": { "Content-Type": "<captured>" }, "body": "<captured>" }
}
```

## Comparison rules

- `deterministic`: status, reason, `Content-Type`, `Location` and body bytes are compared byte for byte, with no trimming or normalization; produced files and message bodies are compared the same way. The only substitution is the replay port inside `Location` (`:<request.port>` becomes `:<replay port>`).
- `nondeterministic`: status, reason and the checked headers are compared exactly. Bodies, files and messages are compared for structure only: JSON by each object's member names in order, every occurrence of a repeated name kept, value kinds and array lengths; XML by child element names (namespace and local name) in order and attribute names as a set, with namespace declarations excluded and text ignored; any other body by empty versus non-empty. Produced files pair up by name: same-named files pair first, and every other name pairs with one of the same shape, which reads each canonical UUID (8-4-4-4-12 hexadecimal digits of either case joined by hyphens, not run together with another letter or digit) as `<uuid>` and then each remaining digit run as `#`, and keeps every other character, a literal `#` or `<` included, so prefixes, fixed segments and suffixes must match exactly, both sides must hold each shape equally often, and the names of one shape pair in sorted order; logs compare the same complete stream entry for entry, by logger and level and only by whether the message is empty; upstream request bodies are not matched. The scenario is reported `UNVERIFIED — nondeterministic output`.
- `unreachable`: no fixture may exist; the proof belongs in the row (AAP 0.8.2). A fixture for an unreachable row fails the class, and the scenario is `FAILED`.

Each run writes `target/fixture-parity/summary.properties` in the project directory: first the counts `verified=<n>`, `unverified=<n>` and `failed=<n>`, then one `scenario.<name>=<STATUS>` line per row, in row order. The statuses:

- `VERIFIED`: a captured deterministic fixture matched.
- `UNVERIFIED-nondeterministic`: a nondeterministic fixture matched in structure only.
- `UNVERIFIED-no-upstream`: the fixture has no `upstream` key.
- `UNVERIFIED-missing-driver`: the project has no replay driver (library, bean or port) for the row's trigger.
- `UNVERIFIED-uncaptured`: the row has no fixture file.
- `UNREACHABLE`: an unreachable row with no fixture.
- `FAILED`: a mismatch, a fixture for an unreachable row, or a fixture with no matching row.

`VERIFIED` counts as verified, every `UNVERIFIED-*` status as unverified and `FAILED` as failed; `UNREACHABLE` is counted in none.

With no fixtures the test is reported skipped (`Assumptions.assumeTrue`, reason `no Tier 2A fixtures captured`), never failed. For this project the summary then lists the fifteen reachable scenarios as `UNVERIFIED-uncaptured` and `get-root-bank5-otherwise` as `UNREACHABLE`, with `verified=0`, `unverified=15` and `failed=0`.

These statuses feed the parity label of the project README and of the root `SUMMARY.md` (AAP 0.8.7):

- `PARITY: UNVERIFIED — Mule runtime unavailable` until fixtures are captured.
- `PARITY: VERIFIED — Tier 2A fixtures` only when every scenario other than an `unreachable` one is `VERIFIED`.
- `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` when every captured deterministic scenario matches but some scenarios remain unverified.
- `PARITY: FAIL — Tier 2A fixtures` when any scenario is `FAILED`.

The fallback never reports PASS. The README lists the unverified scenarios by name, and a scenario marked `ACCEPTED — structural comparison` (AAP 0.8.8) still counts as unverified.

## Capture (Tier 2A)

The capture output of each scenario is kept unedited as the evidence for its fixture and is committed with it in `src/test/capture/<scenario>/`, outside `fixtures/` and off the test classpath: `response.http` holds the `curl --raw -i` output, `curl-trace.txt` curl's `-v` trace with the request as sent, and `upstream-journal.json`, for an original with upstream calls, the recorded exchanges. Working files that can hold live values stay under `target/fixture-capture/` and are never committed. Every command after step 1 runs in the project directory (`java-conversions/foreach-processing-and-choice-routing-java/`).

1. Build the unmodified original `foreach-processing-and-choice-routing` with `mvn -B package`, with the EE repository credentials in `settings.xml`. Never edit the original folder.
2. HTTP and SOAP upstreams; skipped for foreach-processing-and-choice-routing. Its only outbound calls are the `getCreditProfile` and `getLoanQuote` requests of the three complete-request scenarios, sent to the original's own mock listeners at the host `0.0.0.0` and the ports `18080` and `10080` to `50080` the flow hard-codes (`loanbroker-simple.xml:5-6,83-89`). No placeholder or runtime property redirects them, the mock listeners themselves hold those ports, and the original folder is never edited, so `get-root-amount-20000`, `get-root-amount-15000` and `get-root-amount-5000` omit `upstream`; no other scenario makes an outbound call. Every exchange is recorded through WireMock standalone 3.13.2 (`org.wiremock:wiremock-standalone`, the version the projects pin) running as a recording proxy in front of the real services, and its request journal is the recording. WireMock's `--record-mappings` files are not used, because they store a gzip-encoded body decoded and without its `Content-Encoding`.
   - Start WireMock on a free port `W` with an empty root directory. `--disable-gzip` passes a proxied response's `Content-Encoding` and bytes to the original unchanged, and `--preserve-user-agent-proxy-header` forwards the original's `User-Agent` on the proxied leg. Then add one proxy mapping per real upstream, whose `proxyBaseUrl` is that upstream's scheme, host and port:

     ```sh
     W=8089; rm -rf target/fixture-capture/wiremock; mkdir -p target/fixture-capture/wiremock
     java -jar "$HOME/.m2/repository/org/wiremock/wiremock-standalone/3.13.2/wiremock-standalone-3.13.2.jar" --port "$W" --root-dir target/fixture-capture/wiremock --disable-gzip --preserve-user-agent-proxy-header > target/fixture-capture/wiremock.log 2>&1 &
     WM=$!; until curl -sf "http://localhost:$W/__admin/health" > /dev/null; do sleep 1; done
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com"}}'
     ```

     With several upstreams, each mapping matches only its own upstream's paths (`"urlPathPattern": "/<prefix>(/.*)?"`); upstreams whose paths overlap cannot be told apart through one WireMock, so those scenarios omit `upstream`. `kill "$WM"` stops WireMock after the last scenario.
   - The original is pointed at WireMock by runtime properties only (`-M-D<key>=<value>` at deployment, step 3): each upstream placeholder or host setting becomes `http://localhost:<W>`, or host `localhost` and port `W`, keeping its path. An original that cannot reach WireMock this way (a hard-coded host, or a TLS setting that rejects WireMock) cannot have its calls recorded, so its scenarios omit `upstream`.
   - Live credentials (values supplied for the capture that the original folder does not already contain) are never committed. The original runs with a fixed marker in place of each, such as `capture-marker`, which the fixture records and `state.properties` gives the Java app under the same key (step 8), so the replay compares the same values. A credential sent in a request header is replaced by the live value on the proxied leg only, in that upstream's mapping, here for an `X-Api-Key` header whose live value is in the environment variable `LIVE_API_KEY`, never in a file:

     ```sh
     curl -sS -X POST "http://localhost:$W/__admin/mappings" -d '{"request": {"urlPattern": ".*"}, "response": {"proxyBaseUrl": "https://upstream.example.com", "removeProxyRequestHeaders": ["x-api-key"], "additionalProxyRequestHeaders": {"X-Api-Key": "'"$LIVE_API_KEY"'"}}}'
     ```

     WireMock 3.13.2 removes a header only when `removeProxyRequestHeaders` lists its name in lower case. A credential WireMock cannot replace (in a body, a query or a signature), a token or session the live service issues into a recorded exchange, and a response that sends the original to a host it does not reach through WireMock cannot be recorded locally without live values: those scenarios are captured with the live values in place, omit `upstream`, and keep their journal out of the evidence.
3. Deploy the archive to a licensed Mule 3.8 EE runtime without runtime properties: the flow fixes the host `0.0.0.0` and the port of every listener (`loanbroker-simple.xml:4,7-12`), reads no placeholder, and `foreach-processing-and-choice-routing/src/main/app/mule-app.properties` defines no key. Ports 11081, 18080 and 10080 to 50080 must be free on the capture host.
4. Send each scenario's request with `curl --raw -i`, writing its output unedited into the evidence files. `-sS -v` sends the trace and any error to `curl-trace.txt`, and a non-zero exit status means the capture failed. `--http1.1` keeps an HTTPS listener (captured with `-k` added) on HTTP/1.1, whose status line carries the reason phrase.

   ```sh
   capture() { d="src/test/capture/$1"; shift; mkdir -p "$d"; curl --raw -i -sS -v --http1.1 "$@" > "$d/response.http" 2> "$d/curl-trace.txt"; }
   soap() { d="src/test/capture/$1"; mkdir -p "$d"; printf '%s' "$3" > "$d/request.xml"; capture "$1" -X POST -H 'Content-Type: text/xml; charset=UTF-8' -H 'SOAPAction: ""' --data-binary "@$d/request.xml" "$2"; }
   B1='<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cre="http://creditagency.loanbroker.example.mule.org/"><soapenv:Body><cre:getCreditProfile><arg0><name>Muley</name><ssn>1234</ssn></arg0></cre:getCreditProfile></soapenv:Body></soapenv:Envelope>'
   B2='<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:bank="http://bank.loanbroker.example.mule.org/"><soapenv:Body><bank:getLoanQuote><arg0><creditProfile><creditHistory>10</creditHistory><creditScore>600</creditScore></creditProfile></arg0></bank:getLoanQuote></soapenv:Body></soapenv:Envelope>'
   B3='<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>'
   capture get-root-amount-20000 'http://localhost:11081/?name=Muley&amount=20000&term=48&ssn=1234'
   capture get-root-amount-15000 'http://localhost:11081/?name=Muley&amount=15000&term=48&ssn=1234'
   capture get-root-amount-5000 'http://localhost:11081/?name=Muley&amount=5000&term=48&ssn=1234'
   capture get-root-incomplete-request 'http://localhost:11081/?name=Muley&amount=20000&term=48'
   capture get-root-non-numeric-amount 'http://localhost:11081/?name=Muley&amount=abc&term=48&ssn=1234'
   soap post-credit-agency-get-credit-profile 'http://localhost:18080/mule/TheCreditAgencyService/*' "$B1"
   soap post-bank1-get-loan-quote 'http://localhost:10080/mule/TheBank1/*' "$B2"
   soap post-bank2-get-loan-quote 'http://localhost:20080/mule/TheBank2/*' "$B2"
   soap post-bank3-get-loan-quote 'http://localhost:30080/mule/TheBank3/*' "$B2"
   soap post-bank4-get-loan-quote 'http://localhost:40080/mule/TheBank4/*' "$B2"
   soap post-bank5-get-loan-quote 'http://localhost:50080/mule/TheBank5/*' "$B2"
   capture get-credit-agency-wsdl 'http://localhost:18080/mule/TheCreditAgencyService?wsdl'
   capture get-bank1-wsdl 'http://localhost:10080/mule/TheBank1?wsdl'
   soap post-bank1-malformed-envelope 'http://localhost:10080/mule/TheBank1/*' "$B3"
   capture get-credit-agency-path-on-bank1-port 'http://localhost:10080/mule/TheCreditAgencyService'
   ```

   `soap` keeps each request body in the evidence as `request.xml`, the exact bytes curl sends. `get-root-bank5-otherwise` has no command: it is `unreachable`, so it is never captured.

   With upstreams, the journal is cleared before each scenario and saved once the flow has made its last outbound call:

   ```sh
   curl -sS -X DELETE "http://localhost:$W/__admin/requests"
   capture <scenario> <request arguments>
   curl -sS "http://localhost:$W/__admin/requests" > "target/fixture-capture/<scenario>-journal.json"
   ```

5. `request` comes from the `> ` lines of `curl-trace.txt`, each read without its line ending: `method`, `path` and `query` (the raw query string without `?`, `""` when there is none) from the request line; then each `> Name: value` line is one `[name, value]` pair of `headers`, in the order sent, so a repeated name stays in separate pairs and is never joined; `body` is the bytes curl sent (the `--data-binary` argument, or the content of its `@file`); `"port"`, the port of the URL the scenario's command targets; and, for every port other than `11081`, `"portProperty"`, the key that binds that listener (`ports.credit-agency` for `18080`, `ports.bank1` to `ports.bank5` for `10080` to `50080`).
6. `response` comes from `response.http` with only the HTTP/1.1 message framing removed, under the framing rules ST-1 applies on replay:
   - `status` and `reason`: the code and the reason phrase of the final status line, skipping any interim `1xx` response curl wrote before it.
   - `headers`: the `Content-Type` and `Location` values as received.
   - `body` or `bodyBase64`: the exact entity bytes. Under a `Transfer-Encoding` whose last coding is `chunked`, they are the chunk data concatenated, without the chunk-size lines, chunk extensions, CRLFs, last chunk or trailer fields; under `Content-Length`, exactly that many bytes; under neither, every byte to the end of the output; none for a `HEAD` request or a `1xx`, `204` or `304` status. No `Content-Encoding` is decoded, and nothing is trimmed or normalized (no line-ending or charset conversion). The bytes go into `body` when they are valid UTF-8, otherwise into `bodyBase64`.

   This command prints the `response` object from the evidence file and the request method:

   ```sh
   python3 - src/test/capture/get-root-incomplete-request/response.http GET <<'PY'
   import base64, io, json, pathlib, re, sys
   raw, method = io.BytesIO(pathlib.Path(sys.argv[1]).read_bytes()), sys.argv[2]
   MAX_BODY_LENGTH = 2**31 - 1 - 8

   def line():
       data = raw.readline()
       if not data.endswith(b'\n'):
           sys.exit('capture ends inside a line')
       return data[:-2] if data.endswith(b'\r\n') else data[:-1]

   while True:
       status_line = line().decode('latin-1')
       version, _, rest = status_line.partition(' ')
       code, _, reason = rest.partition(' ')
       digits = code.lstrip('0') or '0'
       if not version or not re.fullmatch('[0-9]+', code) or len(digits) > 10 or int(digits) > 2**31 - 1:
           sys.exit('malformed status line: ' + repr(status_line))
       status = int(digits)
       headers = []
       while field := line():
           if field[:1] in (b' ', b'\t'):
               sys.exit('folded header line')
           name, colon, value = field.decode('latin-1').partition(':')
           if not colon:
               sys.exit('malformed header line: ' + repr(name))
           headers.append((name.lower(), value.strip(' \t')))
       if status // 100 != 1 or status == 101:
           break

   def values(name):
       return [v for n, v in headers if n == name]

   def number(text, base, what, limit=MAX_BODY_LENGTH):
       text = text.strip(' \t')
       if not re.fullmatch('[0-9A-Fa-f]+' if base == 16 else '[0-9]+', text):
           sys.exit('malformed ' + what + ': ' + repr(text))
       digits = text.lstrip('0') or '0'
       if len(digits) > 10 or int(digits, base) > limit:
           sys.exit(what + ' too large for an in-memory body: ' + text)
       return int(digits, base)

   coding = ','.join(values('transfer-encoding')).split(',')[-1].split(';')[0].strip(' \t').lower()
   if method == 'HEAD' or status // 100 == 1 or status in (204, 304):
       body = b''
   elif coding == 'chunked':
       body = b''
       while size := number(line().decode('latin-1').split(';')[0], 16, 'chunk size',
                            MAX_BODY_LENGTH - len(body)):
           chunk = raw.read(size)
           if len(chunk) != size or line():
               sys.exit('malformed chunk')
           body += chunk
       while line():
           pass
   elif coding or not values('content-length'):
       body = raw.read()
   else:
       lengths = {number(n, 10, 'Content-Length') for n in ','.join(values('content-length')).split(',')}
       if len(lengths) > 1:
           sys.exit('conflicting Content-Length values: ' + ', '.join(values('content-length')))
       length = lengths.pop()
       body = raw.read(length)
       if len(body) != length:
           sys.exit('capture ends inside the body')
   if raw.read():
       sys.exit('bytes after the message')
   response = {'status': status, 'reason': reason, 'headers': {}}
   for name in ('Content-Type', 'Location'):
       found = values(name.lower())
       if len(found) > 1:
           sys.exit('repeated ' + name)
       if found:
           response['headers'][name] = found[0]
   try:
       response['body'] = body.decode('utf-8')
   except UnicodeDecodeError:
       response['bodyBase64'] = base64.b64encode(body).decode('ascii')
   print(json.dumps(response, indent=2))
   PY
   ```

7. `upstream` is `[]` when the original made no outbound call, as in every scenario of this project other than `get-root-amount-20000`, `get-root-amount-15000` and `get-root-amount-5000`, which omit it (step 2). For an original whose outbound calls were recorded, this command writes the committed evidence `upstream-journal.json`, which keeps only the journal's `request` and `response` objects because its stub mappings name the real hosts and hold the live values, and prints the `upstream` array:

   ```sh
   S=<scenario>; J="target/fixture-capture/$S-journal.json"
   jq '[.requests | reverse[] | {request, response}]' "$J" > "src/test/capture/$S/upstream-journal.json"
   jq 'def pairs: [to_entries[] | .key as $k | (.value | if type == "array" then .[] else . end) | [$k, .]];
     [.requests | reverse[]
      | if .responseDefinition.proxyBaseUrl == null then error("not proxied: \(.request.method) \(.request.url)")
        elif (.response.body // "") | startswith("Network failure trying to make a proxied request") then error("upstream not reached: \(.request.url)")
        elif .request.headers // {} | keys | map(ascii_downcase) | index("content-encoding") then error("encoded request body: \(.request.url)")
        else {request: {method: .request.method, url: .request.url, headers: (.request.headers // {} | pairs), bodyBase64: .request.bodyAsBase64},
              response: {status: .response.status, headers: (.response.headers // {} | pairs), bodyBase64: .response.bodyAsBase64}}
        end]' "$J"
   ```

   The journal lists the newest call first, so its entries are reversed into call order. `url` is the path and query relative to the WireMock base, so the fixture names no live host. A repeated header name becomes consecutive pairs at the position of its first occurrence, the order the journal keeps. Bodies are `bodyBase64`, the entity bytes as recorded: a chunked body in either direction is de-chunked with its trailer fields dropped, and a gzip-encoded response body keeps its encoded bytes and its `Content-Encoding` header, as step 6 defines for `response`. The command fails on a call no mapping proxied or that did not reach its upstream, which is captured again once the mapping is fixed, and on a request body with a `Content-Encoding`, which WireMock stores decoded and re-encodes on the proxied leg, so that scenario omits `upstream`.
8. Write `fixtures/<scenario>.json` from `request` (step 5), `state` (the six `ports.*` properties of the notes under Scenarios, the same in every fixture of this project), `upstream` (step 7) and `response` (step 6), every value as produced. For an original with upstreams, `state.properties` points each upstream key of the Java project at `${wiremock.baseUrl}`, or host `localhost` and port `${wiremock.port}`, with the path the capture used, and gives each credential key its marker. Before committing, `grep -rF -e "<live value>" src/test/resources/fixtures src/test/capture` runs for every live value: a match in `upstream` or a journal means that scenario omits `upstream` and drops its journal, and a match in `request` or `response` means it gets no fixture. Captured values and entity bytes are never adjusted afterwards, to match the Java output or for any other reason.

## Integrity rule

- A fixture is never edited to match the Java output. A mismatch is fixed in the Java code: for `get-credit-agency-path-on-bank1-port`, that means SC-10 `PortRoutingFilter`; for `post-bank1-malformed-envelope`, SC-11 `SoapListenerBoundaryAdapter`; for `get-credit-agency-wsdl` and `get-bank1-wsdl`, replacing the authored WSDL with the captured one, recorded in `DECISIONS.md` (AAP 0.3.2). No expected value is written by hand, in this file or in a fixture (AAP 0.8.4).
- `DECISIONS.md` records the Tier 2A blocked state ("Mule EE licence and EE Maven repository credentials unconfirmed") and the escalation of AAP 0.8.8: once both are confirmed, a human is notified to begin capture and receives the list of scenarios left unverified.
