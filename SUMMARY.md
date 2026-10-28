# Conversion summary

This table reports the delivery evidence, tier by tier, for every example folder in this checkout. The 56 original Mule examples stay unchanged at the repository root, beside `java-conversions/`, which holds the 55 delivered Spring Boot projects; `sap-data-retrieval` is excluded pending `sapjco3.jar`.
The request counted 57 folders: [DECISIONS.md](DECISIONS.md#1-scope-and-counts) reconciles the count (56 found, 55 delivered, 51 non-RAML parity projects) and reads the criterion "56 of 57 compile" as "every non-SAP folder present compiles".
The four RAML-backed rows rest on the static diff in [RAML-CONTROLLER-DIFF.md](RAML-CONTROLLER-DIFF.md). Each project README repeats its row under `## Tier status`.

Measured on 2026-10-28 against the tree at commit `9a4c9484`, with Temurin JDK 17.0.21 and Apache Maven 3.9.9: `mvn -B clean package` and then `mvn -B test` in each of the 55 projects, and `mvn -B verify` in the 11 connector projects.

## Status

| Project | Source folder | Category | Tier 1 build (mvn clean package) | Tier 1 tests (mvn test) | Coverage (service + mapper) | Parity evidence | Tier 2B live sandbox |
|---|---|---|---|---|---|---|---|
| [rest-api-with-apikit-java](java-conversions/rest-api-with-apikit-java/README.md) | [rest-api-with-apikit](rest-api-with-apikit/) | RAML/APIkit-driven REST API | PASS | PASS | 100.0% | RAML DIFF: 1:1 (Tier 1 static) | N/A |
| [testing-apikit-with-munit-java](java-conversions/testing-apikit-with-munit-java/README.md) | [testing-apikit-with-munit](testing-apikit-with-munit/) | RAML/APIkit-driven REST API | PASS | PASS | 100.0% | RAML DIFF: 1:1 (Tier 1 static) | N/A |
| [processing-orders-with-dataweave-and-APIkit-java](java-conversions/processing-orders-with-dataweave-and-APIkit-java/README.md) | [processing-orders-with-dataweave-and-APIkit](processing-orders-with-dataweave-and-APIkit/) | RAML/APIkit-driven REST API | PASS | PASS | 91.3% | RAML DIFF: 1:1 (Tier 1 static) | N/A |
| [web-service-consumer-java](java-conversions/web-service-consumer-java/README.md) | [web-service-consumer](web-service-consumer/) | SOAP/WSDL-based service | PASS | PASS | 97.8% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [xml-only-soap-webservice-java](java-conversions/xml-only-soap-webservice-java/README.md) | [xml-only-soap-webservice](xml-only-soap-webservice/) | SOAP/WSDL-based service | PASS | PASS | 98.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [service-orchestration-and-choice-routing-java](java-conversions/service-orchestration-and-choice-routing-java/README.md) | [service-orchestration-and-choice-routing](service-orchestration-and-choice-routing/) | SOAP/WSDL-based service | PASS | PASS | 99.3% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [jms-message-rollback-and-redelivery-java](java-conversions/jms-message-rollback-and-redelivery-java/README.md) | [jms-message-rollback-and-redelivery](jms-message-rollback-and-redelivery/) | JMS/messaging-based flow | PASS | PASS | 98.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [sending-json-data-to-a-jms-queue-java](java-conversions/sending-json-data-to-a-jms-queue-java/README.md) | [sending-json-data-to-a-jms-queue](sending-json-data-to-a-jms-queue/) | JMS/messaging-based flow | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [using-transactional-scope-in-jms-to-database-java](java-conversions/using-transactional-scope-in-jms-to-database-java/README.md) | [using-transactional-scope-in-jms-to-database](using-transactional-scope-in-jms-to-database/) | JMS/messaging-based flow | PASS | PASS | 96.6% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [salesforce-data-retrieval-java](java-conversions/salesforce-data-retrieval-java/README.md) | [salesforce-data-retrieval](salesforce-data-retrieval/) | Third-party connector | PASS | PASS | 98.9% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [import-contacts-into-salesforce-java](java-conversions/import-contacts-into-salesforce-java/README.md) | [import-contacts-into-salesforce](import-contacts-into-salesforce/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [import-leads-into-salesforce-java](java-conversions/import-leads-into-salesforce-java/README.md) | [import-leads-into-salesforce](import-leads-into-salesforce/) | Third-party connector | PASS | PASS | 98.5% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [authenticating-salesforce-using-oauth2-java](java-conversions/authenticating-salesforce-using-oauth2-java/README.md) | [authenticating-salesforce-using-oauth2](authenticating-salesforce-using-oauth2/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [get-customer-list-from-netsuite-java](java-conversions/get-customer-list-from-netsuite-java/README.md) | [get-customer-list-from-netsuite](get-customer-list-from-netsuite/) | Third-party connector | PASS | PASS | 98.5% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [netsuite-data-retrieval-java](java-conversions/netsuite-data-retrieval-java/README.md) | [netsuite-data-retrieval](netsuite-data-retrieval/) | Third-party connector (RAML-backed) | PASS | PASS | 99.3% | RAML DIFF: 1:1 (Tier 1 static) | NOT RUN — sandbox credentials not supplied |
| [salesforce-data-synchronization-using-watermarking-and-batch-processing-java](java-conversions/salesforce-data-synchronization-using-watermarking-and-batch-processing-java/README.md) | [salesforce-data-synchronization-using-watermarking-and-batch-processing](salesforce-data-synchronization-using-watermarking-and-batch-processing/) | Third-party connector | PASS | PASS | 96.7% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [salesforce-to-MySQL-DB-using-Batch-Processing-java](java-conversions/salesforce-to-MySQL-DB-using-Batch-Processing-java/README.md) | [salesforce-to-MySQL-DB-using-Batch-Processing](salesforce-to-MySQL-DB-using-Batch-Processing/) | Third-party connector | PASS | PASS | 97.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| sap-data-retrieval-java (follow-on) | [sap-data-retrieval](sap-data-retrieval/) | Third-party connector (RAML-backed) | EXCLUDED — pending sapjco3.jar | EXCLUDED — pending sapjco3.jar | EXCLUDED — pending sapjco3.jar | EXCLUDED — pending sapjco3.jar | EXCLUDED — pending sapjco3.jar |
| [adding-a-new-customer-to-workday-revenue-management-java](java-conversions/adding-a-new-customer-to-workday-revenue-management-java/README.md) | [adding-a-new-customer-to-workday-revenue-management](adding-a-new-customer-to-workday-revenue-management/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [import-contacts-into-ms-dynamics-java](java-conversions/import-contacts-into-ms-dynamics-java/README.md) | [import-contacts-into-ms-dynamics](import-contacts-into-ms-dynamics/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [dataweave-with-flowreflookup-java](java-conversions/dataweave-with-flowreflookup-java/README.md) | [dataweave-with-flowreflookup](dataweave-with-flowreflookup/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [addition-using-javascript-transformer-java](java-conversions/addition-using-javascript-transformer-java/README.md) | [addition-using-javascript-transformer](addition-using-javascript-transformer/) | General | PASS | PASS | 99.9% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [cache-scope-with-fibonacci-java](java-conversions/cache-scope-with-fibonacci-java/README.md) | [cache-scope-with-fibonacci](cache-scope-with-fibonacci/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [content-based-routing-java](java-conversions/content-based-routing-java/README.md) | [content-based-routing](content-based-routing/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [document-integration-using-the-cmis-connector-java](java-conversions/document-integration-using-the-cmis-connector-java/README.md) | [document-integration-using-the-cmis-connector](document-integration-using-the-cmis-connector/) | General | PASS | PASS | 88.9% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [exposing-a-restful-resource-using-the-HTTP-connector-java](java-conversions/exposing-a-restful-resource-using-the-HTTP-connector-java/README.md) | [exposing-a-restful-resource-using-the-HTTP-connector](exposing-a-restful-resource-using-the-HTTP-connector/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [extracting-data-from-LDAP-directory-java](java-conversions/extracting-data-from-LDAP-directory-java/README.md) | [extracting-data-from-LDAP-directory](extracting-data-from-LDAP-directory/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [filtering-a-message-java](java-conversions/filtering-a-message-java/README.md) | [filtering-a-message](filtering-a-message/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [foreach-processing-and-choice-routing-java](java-conversions/foreach-processing-and-choice-routing-java/README.md) | [foreach-processing-and-choice-routing](foreach-processing-and-choice-routing/) | General | PASS | PASS | 98.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [hello-world-java](java-conversions/hello-world-java/README.md) | [hello-world](hello-world/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [http-multipart-request-java](java-conversions/http-multipart-request-java/README.md) | [http-multipart-request](http-multipart-request/) | General | PASS | PASS | 85.4% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [http-oauth-provider-java](java-conversions/http-oauth-provider-java/README.md) | [http-oauth-provider](http-oauth-provider/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [http-request-response-with-logger-java](java-conversions/http-request-response-with-logger-java/README.md) | [http-request-response-with-logger](http-request-response-with-logger/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [implementing-a-choice-exception-strategy-java](java-conversions/implementing-a-choice-exception-strategy-java/README.md) | [implementing-a-choice-exception-strategy](implementing-a-choice-exception-strategy/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [importing-a-CSV-file-into-Mongo-DB-java](java-conversions/importing-a-CSV-file-into-Mongo-DB-java/README.md) | [importing-a-CSV-file-into-Mongo-DB](importing-a-CSV-file-into-Mongo-DB/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [importing-a-csv-file-into-ms-sharepoint-java](java-conversions/importing-a-csv-file-into-ms-sharepoint-java/README.md) | [importing-a-csv-file-into-ms-sharepoint](importing-a-csv-file-into-ms-sharepoint/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [importing-an-email-attachment-using-the-IMAP-connector-java](java-conversions/importing-an-email-attachment-using-the-IMAP-connector-java/README.md) | [importing-an-email-attachment-using-the-IMAP-connector](importing-an-email-attachment-using-the-IMAP-connector/) | General | PASS | PASS | 94.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [importing-an-email-attachment-using-the-POP3-connector-java](java-conversions/importing-an-email-attachment-using-the-POP3-connector-java/README.md) | [importing-an-email-attachment-using-the-POP3-connector](importing-an-email-attachment-using-the-POP3-connector/) | General | PASS | PASS | 94.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [legacy-modernization-java](java-conversions/legacy-modernization-java/README.md) | [legacy-modernization](legacy-modernization/) | General | PASS | PASS | 90.7% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [login-form-using-the-http-connector-java](java-conversions/login-form-using-the-http-connector-java/README.md) | [login-form-using-the-http-connector](login-form-using-the-http-connector/) | General | PASS | PASS | 95.5% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [mule-component-bindings-java](java-conversions/mule-component-bindings-java/README.md) | [mule-component-bindings](mule-component-bindings/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [mule-expression-language-basics-java](java-conversions/mule-expression-language-basics-java/README.md) | [mule-expression-language-basics](mule-expression-language-basics/) | General | PASS | PASS | 91.4% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [munit-short-tutorial-java](java-conversions/munit-short-tutorial-java/README.md) | [munit-short-tutorial](munit-short-tutorial/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [oauth2-authorization-code-using-the-HTTP-connector-java](java-conversions/oauth2-authorization-code-using-the-HTTP-connector-java/README.md) | [oauth2-authorization-code-using-the-HTTP-connector](oauth2-authorization-code-using-the-HTTP-connector/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [oauth2-client-credentials-using-the-HTTP-connector-java](java-conversions/oauth2-client-credentials-using-the-HTTP-connector-java/README.md) | [oauth2-client-credentials-using-the-HTTP-connector](oauth2-client-credentials-using-the-HTTP-connector/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [proxying-a-rest-api-java](java-conversions/proxying-a-rest-api-java/README.md) | [proxying-a-rest-api](proxying-a-rest-api/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [proxying-a-soap-api-java](java-conversions/proxying-a-soap-api-java/README.md) | [proxying-a-soap-api](proxying-a-soap-api/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [querying-a-db-and-attaching-results-to-an-email-java](java-conversions/querying-a-db-and-attaching-results-to-an-email-java/README.md) | [querying-a-db-and-attaching-results-to-an-email](querying-a-db-and-attaching-results-to-an-email/) | General | PASS | PASS | 98.6% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [querying-a-mysql-database-java](java-conversions/querying-a-mysql-database-java/README.md) | [querying-a-mysql-database](querying-a-mysql-database/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [scatter-gather-flow-control-java](java-conversions/scatter-gather-flow-control-java/README.md) | [scatter-gather-flow-control](scatter-gather-flow-control/) | General | PASS | PASS | 98.2% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [sending-a-csv-file-through-email-using-smtp-java](java-conversions/sending-a-csv-file-through-email-using-smtp-java/README.md) | [sending-a-csv-file-through-email-using-smtp](sending-a-csv-file-through-email-using-smtp/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [sending-json-data-to-a-amqp-queue-java](java-conversions/sending-json-data-to-a-amqp-queue-java/README.md) | [sending-json-data-to-a-amqp-queue](sending-json-data-to-a-amqp-queue/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [soap-webservice-security-java](java-conversions/soap-webservice-security-java/README.md) | [soap-webservice-security](soap-webservice-security/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [track-a-custom-business-event-java](java-conversions/track-a-custom-business-event-java/README.md) | [track-a-custom-business-event](track-a-custom-business-event/) | General | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [upload-to-ftp-after-converting-json-to-xml-java](java-conversions/upload-to-ftp-after-converting-json-to-xml-java/README.md) | [upload-to-ftp-after-converting-json-to-xml](upload-to-ftp-after-converting-json-to-xml/) | General | PASS | PASS | 99.0% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |
| [websphere-mq-java](java-conversions/websphere-mq-java/README.md) | [websphere-mq](websphere-mq/) | General | PASS | PASS | 99.3% | PARITY: UNVERIFIED — Mule runtime unavailable | N/A |

## Legend

| Label | Column | Meaning |
|---|---|---|
| `PASS` | Tier 1 build | `mvn -B clean package` ended `BUILD SUCCESS`: compilation, the enforcer JAX-WS/CXF ban, surefire (0 failures, 0 errors) and the JaCoCo `check` (LINE covered ratio ≥ 0.80 over `service` and `mapper`) all passed |
| `FAIL` | Tier 1 build | `mvn -B clean package` did not end `BUILD SUCCESS`, or could not be run (footnoted with the reason). No row carries it |
| `PASS` | Tier 1 tests | `mvn -B test` ended with 0 failures and 0 errors. Skipped tests (`FixtureParityTest` while no fixture exists) are expected and never counted as passes |
| `FAIL` | Tier 1 tests | `mvn -B test` reported a failure or an error, or could not be run (footnoted). No row carries it |
| `nn.n%` | Coverage (service + mapper) | LINE covered ratio from `target/site/jacoco/jacoco.csv` over the packages `<package root>.service` and `<package root>.mapper` and their subpackages, generated JAXB classes excluded; a project without a `mapper` package is measured over `service` alone |
| `—` | Coverage (service + mapper) | No JaCoCo report was produced (footnoted). No row carries it |
| `RAML DIFF: 1:1 (Tier 1 static)` | Parity evidence | The static RAML-to-controller diff maps every RAML resource and method 1:1 to a controller method. Tier 1 static evidence of the contract, not runtime parity |
| `PARITY: UNVERIFIED — Mule runtime unavailable` | Parity evidence | Tier 2A is blocked, so no fixture has been captured from the original. Parity rests on the fallback: the ported original assertions and the branch tests. This evidence is weaker than captured fixtures and is never reported as a pass |
| `PARITY: VERIFIED — Tier 2A fixtures` | Parity evidence | Every scenario of the project's `src/test/resources/fixtures/SCENARIOS.md` other than `unreachable` ones has a captured fixture that matches byte for byte. No row carries it yet |
| `PARITY: PARTIAL — Tier 2A fixtures: <v> verified, <u> unverified` | Parity evidence | Every captured deterministic scenario matches, and `<u>` scenarios remain unverified (nondeterministic, uncaptured, or without an `upstream` recording). No row carries it yet |
| `PARITY: FAIL — Tier 2A fixtures` | Parity evidence | A captured deterministic scenario differs. No row carries it yet |
| `NOT RUN — sandbox credentials not supplied` | Tier 2B live sandbox | Connector project whose failsafe `*IT` classes were skipped because `src/test/resources/application-it.yml` is absent. A skipped `*IT` is not a pass |
| `PASS` / `FAIL` | Tier 2B live sandbox | Failsafe ran the project's `*IT` classes against the live sandbox (not skipped) and they passed / failed. No row carries either yet |
| `N/A` | Tier 2B live sandbox | Not a connector project: no failsafe tier and no `*IT` class |
| `EXCLUDED — pending sapjco3.jar` | Every status column | `sap-data-retrieval` is not converted in this phase; the follow-on is recorded in [DECISIONS.md](DECISIONS.md#10-sap-follow-on-sap-data-retrieval). No other label is used for it |

## Tiers and prerequisites

| Tier | Prerequisite | State | Evidence | Columns |
|---|---|---|---|---|
| Tier 1 — build-verified | JDK 17, Maven and Maven Central; no licence or credential | Run for all 55 delivered projects | Build logs, `target/surefire-reports`, `target/site/jacoco`; `RAML-CONTROLLER-DIFF.md` for the 4 RAML-backed projects | Tier 1 build, Tier 1 tests, Coverage; Parity evidence of the RAML-backed rows |
| Tier 2A — fixture-verified | Mule EE runtime licence and EE Maven repository credentials, both unconfirmed | `Tier 2A blocked: Mule EE licence and EE Maven repository credentials unconfirmed` ([DECISIONS.md](DECISIONS.md#9-verification-tiers-and-tier-2a-escalation-state)) | Committed `src/test/resources/fixtures/*.json` and surefire reports showing `FixtureParityTest` with zero skipped | Parity evidence of the 51 non-RAML rows |
| Tier 2B — live-sandbox-verified | Vendor sandbox credentials in each connector project's git-ignored `src/test/resources/application-it.yml` (keys listed in the committed `application-it.yml.example`) | Not run: no credentials were supplied | `target/failsafe-reports` with the `*IT` classes run, not skipped | Tier 2B live sandbox of the 11 connector rows |

No tier's status is reported from another tier's evidence: Tier 1 never reports parity, the blocked Tier 2A does not block Tier 2B, and a skipped `*IT` or `FixtureParityTest` never counts as a pass.

## Commands

Run in `java-conversions/{folder}-java/`, or from the repository root with `mvn -B -f java-conversions/{folder}-java/pom.xml <goals>`. Never run Maven against the root `pom.xml`: it is the original Mule EE reactor and needs the EE repository credentials.

```bash
mvn -B clean package   # compile, enforcer JAX-WS/CXF ban, surefire, JaCoCo check at prepare-package (LINE >= 0.80 over service and mapper), repackage
mvn -B test            # surefire only: every *Test class; *IT classes are excluded
mvn -B clean install   # clean package, then failsafe in the connector projects, then install into the local repository
mvn -B verify          # connector projects: package, then failsafe runs the *IT live-sandbox tests (Tier 2B); they skip without application-it.yml
mvn spring-boot:run    # start the application; supply the TODO placeholders of application.yml first (see the project README)
```

## Totals

- Folders: 56 found (3 RAML/APIkit-driven REST API, 3 SOAP/WSDL-based service, 3 JMS/messaging-based flow, 12 third-party connector, 35 General); 55 delivered; 1 excluded (`sap-data-retrieval`).
- Tier 1 build: 55 `PASS`, 0 `FAIL`.
- Tier 1 tests: 55 `PASS`, 0 `FAIL`; 20,925 tests run, 0 failures, 0 errors, 51 skipped (`FixtureParityTest` in each of the 51 non-RAML projects).
- Coverage (service + mapper): every delivered project at or above the 80% gate; lowest 85.4%, highest 100.0%.
- Parity evidence: 51 `PARITY: UNVERIFIED — Mule runtime unavailable`, 4 `RAML DIFF: 1:1 (Tier 1 static)`, 0 `PARITY: VERIFIED — Tier 2A fixtures`, 0 `PARITY: PARTIAL — Tier 2A fixtures`, 0 `PARITY: FAIL — Tier 2A fixtures`, 1 `EXCLUDED — pending sapjco3.jar`.
- Tier 2B live sandbox: 11 `NOT RUN — sandbox credentials not supplied` (14 `*IT` tests, 14 skipped), 0 `PASS`, 0 `FAIL`, 44 `N/A`, 1 `EXCLUDED — pending sapjco3.jar`.

## Evidence per project

The counts behind the measured cells. Tests are those of the `mvn -B test` run, which matched the surefire run of `mvn -B clean package` in every project. Total LINE coverage is reported beside the gated service + mapper figure; it is not gated.

| Project | mvn -B test: run / failures / errors / skipped | Service + mapper lines covered | Total LINE coverage (generated classes excluded) | Failsafe *IT (mvn -B verify) |
|---|---|---|---|---|
| rest-api-with-apikit-java | 416 / 0 / 0 / 0 | 131 of 131 | 88.7% (1072 of 1208) | — |
| testing-apikit-with-munit-java | 267 / 0 / 0 / 0 | 13 of 13 | 80.9% (559 of 691) | — |
| processing-orders-with-dataweave-and-APIkit-java | 567 / 0 / 0 / 0 | 421 of 461 | 88.5% (1167 of 1319) | — |
| web-service-consumer-java | 500 / 0 / 0 / 1 | 449 of 459 | 97.4% (791 of 812) | — |
| xml-only-soap-webservice-java | 359 / 0 / 0 / 1 | 644 of 657 | 92.2% (1290 of 1399) | — |
| service-orchestration-and-choice-routing-java | 815 / 0 / 0 / 1 | 1149 of 1157 | 92.0% (2616 of 2842) | — |
| jms-message-rollback-and-redelivery-java | 63 / 0 / 0 / 1 | 77 of 78 | 98.5% (201 of 204) | — |
| sending-json-data-to-a-jms-queue-java | 272 / 0 / 0 / 1 | 163 of 163 | 98.1% (411 of 419) | — |
| using-transactional-scope-in-jms-to-database-java | 97 / 0 / 0 / 1 | 115 of 119 | 96.6% (229 of 237) | — |
| salesforce-data-retrieval-java | 929 / 0 / 0 / 1 | 357 of 361 | 95.8% (993 of 1036) | 2 run, 2 skipped, 0 failed, 0 errors |
| import-contacts-into-salesforce-java | 655 / 0 / 0 / 1 | 92 of 92 | 90.6% (625 of 690) | 1 run, 1 skipped, 0 failed, 0 errors |
| import-leads-into-salesforce-java | 740 / 0 / 0 / 1 | 330 of 335 | 96.5% (918 of 951) | 1 run, 1 skipped, 0 failed, 0 errors |
| authenticating-salesforce-using-oauth2-java | 780 / 0 / 0 / 1 | 93 of 93 | 95.8% (806 of 841) | 1 run, 1 skipped, 0 failed, 0 errors |
| get-customer-list-from-netsuite-java | 600 / 0 / 0 / 1 | 257 of 261 | 95.7% (1206 of 1260) | 1 run, 1 skipped, 0 failed, 0 errors |
| netsuite-data-retrieval-java | 1040 / 0 / 0 / 0 | 438 of 441 | 92.3% (1806 of 1956) | 3 run, 3 skipped, 0 failed, 0 errors |
| salesforce-data-synchronization-using-watermarking-and-batch-processing-java | 687 / 0 / 0 / 1 | 327 of 338 | 96.6% (776 of 803) | 1 run, 1 skipped, 0 failed, 0 errors |
| salesforce-to-MySQL-DB-using-Batch-Processing-java | 713 / 0 / 0 / 1 | 351 of 362 | 96.8% (806 of 833) | 1 run, 1 skipped, 0 failed, 0 errors |
| adding-a-new-customer-to-workday-revenue-management-java | 445 / 0 / 0 / 1 | 73 of 73 | 96.0% (582 of 606) | 1 run, 1 skipped, 0 failed, 0 errors |
| import-contacts-into-ms-dynamics-java | 401 / 0 / 0 / 1 | 106 of 106 | 90.6% (686 of 757) | 1 run, 1 skipped, 0 failed, 0 errors |
| dataweave-with-flowreflookup-java | 736 / 0 / 0 / 1 | 128 of 128 | 91.0% (661 of 726) | 1 run, 1 skipped, 0 failed, 0 errors |
| addition-using-javascript-transformer-java | 652 / 0 / 0 / 1 | 893 of 894 | 99.2% (1124 of 1133) | — |
| cache-scope-with-fibonacci-java | 186 / 0 / 0 / 1 | 65 of 65 | 97.5% (312 of 320) | — |
| content-based-routing-java | 216 / 0 / 0 / 1 | 17 of 17 | 97.6% (287 of 294) | — |
| document-integration-using-the-cmis-connector-java | 143 / 0 / 0 / 1 | 16 of 18 | 94.5% (410 of 434) | — |
| exposing-a-restful-resource-using-the-HTTP-connector-java | 219 / 0 / 0 / 1 | 23 of 23 | 97.9% (275 of 281) | — |
| extracting-data-from-LDAP-directory-java | 132 / 0 / 0 / 1 | 17 of 17 | 95.4% (332 of 348) | — |
| filtering-a-message-java | 172 / 0 / 0 / 1 | 36 of 36 | 96.7% (265 of 274) | — |
| foreach-processing-and-choice-routing-java | 387 / 0 / 0 / 1 | 77 of 78 | 86.2% (1139 of 1322) | — |
| hello-world-java | 442 / 0 / 0 / 1 | 2 of 2 | 96.8% (212 of 219) | — |
| http-multipart-request-java | 302 / 0 / 0 / 1 | 158 of 185 | 92.1% (385 of 418) | — |
| http-oauth-provider-java | 457 / 0 / 0 / 1 | 206 of 206 | 93.1% (685 of 736) | — |
| http-request-response-with-logger-java | 175 / 0 / 0 / 1 | 5 of 5 | 96.8% (215 of 222) | — |
| implementing-a-choice-exception-strategy-java | 180 / 0 / 0 / 1 | 34 of 34 | 97.8% (306 of 313) | — |
| importing-a-CSV-file-into-Mongo-DB-java | 63 / 0 / 0 / 1 | 96 of 96 | 77.2% (200 of 259) | — |
| importing-a-csv-file-into-ms-sharepoint-java | 73 / 0 / 0 / 1 | 69 of 69 | 76.9% (413 of 537) | — |
| importing-an-email-attachment-using-the-IMAP-connector-java | 256 / 0 / 0 / 1 | 372 of 395 | 94.8% (509 of 537) | — |
| importing-an-email-attachment-using-the-POP3-connector-java | 200 / 0 / 0 / 1 | 374 of 397 | 93.4% (479 of 513) | — |
| legacy-modernization-java | 297 / 0 / 0 / 1 | 224 of 247 | 88.8% (985 of 1109) | — |
| login-form-using-the-http-connector-java | 279 / 0 / 0 / 1 | 85 of 89 | 94.3% (433 of 459) | — |
| mule-component-bindings-java | 452 / 0 / 0 / 1 | 104 of 104 | 85.3% (931 of 1091) | — |
| mule-expression-language-basics-java | 600 / 0 / 0 / 1 | 266 of 291 | 94.1% (495 of 526) | — |
| munit-short-tutorial-java | 164 / 0 / 0 / 1 | 16 of 16 | 97.1% (236 of 243) | — |
| oauth2-authorization-code-using-the-HTTP-connector-java | 394 / 0 / 0 / 1 | 166 of 166 | 95.2% (844 of 887) | — |
| oauth2-client-credentials-using-the-HTTP-connector-java | 432 / 0 / 0 / 1 | 40 of 40 | 90.7% (909 of 1002) | — |
| proxying-a-rest-api-java | 641 / 0 / 0 / 1 | 41 of 41 | 94.3% (1213 of 1287) | — |
| proxying-a-soap-api-java | 306 / 0 / 0 / 1 | 54 of 54 | 92.8% (823 of 887) | — |
| querying-a-db-and-attaching-results-to-an-email-java | 269 / 0 / 0 / 1 | 137 of 139 | 98.1% (403 of 411) | — |
| querying-a-mysql-database-java | 135 / 0 / 0 / 1 | 9 of 9 | 96.4% (214 of 222) | — |
| scatter-gather-flow-control-java | 202 / 0 / 0 / 1 | 160 of 163 | 97.0% (384 of 396) | — |
| sending-a-csv-file-through-email-using-smtp-java | 83 / 0 / 0 / 1 | 107 of 107 | 81.9% (236 of 288) | — |
| sending-json-data-to-a-amqp-queue-java | 185 / 0 / 0 / 1 | 42 of 42 | 97.5% (394 of 404) | — |
| soap-webservice-security-java | 386 / 0 / 0 / 1 | 2 of 2 | 86.2% (1382 of 1604) | — |
| track-a-custom-business-event-java | 231 / 0 / 0 / 1 | 129 of 129 | 98.3% (345 of 351) | — |
| upload-to-ftp-after-converting-json-to-xml-java | 296 / 0 / 0 / 1 | 291 of 294 | 91.7% (552 of 602) | — |
| websphere-mq-java | 236 / 0 / 0 / 1 | 406 of 409 | 94.5% (895 of 947) | — |
