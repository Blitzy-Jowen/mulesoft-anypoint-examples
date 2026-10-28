# Conversion summary

This table reports the delivery evidence, tier by tier, for every example folder in this checkout. The 56 original Mule examples stay unchanged at the repository root, beside `java-conversions/`, which holds the 55 delivered Spring Boot projects; `sap-data-retrieval` is excluded pending `sapjco3.jar`.
The request counted 57 folders: [DECISIONS.md](DECISIONS.md#1-scope-and-counts) reconciles the count (56 found, 55 delivered, 51 non-RAML parity projects) and reads the criterion "56 of 57 compile" as "every non-SAP folder present compiles".
The four RAML-backed rows rest on the static diff in [RAML-CONTROLLER-DIFF.md](RAML-CONTROLLER-DIFF.md). Each project README repeats its row under `## Tier status`.

Measured on 2026-10-28 against the source tree of the commit that last updated these figures, with Temurin JDK 17.0.21 and Apache Maven 3.9.9: `mvn -B clean package` and then `mvn -B test` in each of the 55 projects, and `mvn -B verify` in the 11 connector projects. The `websphere-mq-java` figures were re-measured the same day with `mvn -B clean verify`, after its start-up connection check, `wmq.host` diagnostic, Ajax server-path normalization, empty-payload listener binding and IBM MQ client-log location changes, and again after its broker rollback and listener-wiring tests were added. Seven projects were measured again the same way on the tree that carries their change: `adding-a-new-customer-to-workday-revenue-management-java`, `authenticating-salesforce-using-oauth2-java`, `exposing-a-restful-resource-using-the-HTTP-connector-java`, `get-customer-list-from-netsuite-java`, `netsuite-data-retrieval-java`, `salesforce-data-retrieval-java` and `service-orchestration-and-choice-routing-java`.
The evidence rows of `document-integration-using-the-cmis-connector-java`, `mule-component-bindings-java` and `oauth2-authorization-code-using-the-HTTP-connector-java`, and the Tier 1 tests total under Totals, were re-measured on 2026-10-28 with `mvn -B clean package` on the later tree that adds their first-use configuration checks and the CMIS and Stocklytics no-retry transport.
The evidence rows of `importing-an-email-attachment-using-the-IMAP-connector-java`, `importing-an-email-attachment-using-the-POP3-connector-java`, `proxying-a-soap-api-java`, `salesforce-data-synchronization-using-watermarking-and-batch-processing-java`, `salesforce-to-MySQL-DB-using-Batch-Processing-java` and `web-service-consumer-java` were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that carries their latest changes, and the Tier 1 tests total under Totals was updated to match.
The rows of the six SC-10 projects (`foreach-processing-and-choice-routing-java`, `http-oauth-provider-java`, `mule-component-bindings-java`, `oauth2-authorization-code-using-the-HTTP-connector-java`, `service-orchestration-and-choice-routing-java`, `soap-webservice-security-java`) and of `xml-only-soap-webservice-java`, and the Tier 1 tests total, were re-measured on 2026-10-28 with `mvn -B clean install` on the later tree that sends every no-listener 404 as `text/plain` and names an empty TLS store or key password.
The rows of `foreach-processing-and-choice-routing-java`, `legacy-modernization-java`, `service-orchestration-and-choice-routing-java`, `soap-webservice-security-java` and `xml-only-soap-webservice-java` were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that adds their framework-logging caps, and the Tier 1 tests total under Totals was updated to match.
The evidence row of `querying-a-mysql-database-java`, and the Tier 1 tests total under Totals, were re-measured on 2026-10-28 with `mvn -B test` on the tree that adds its fail-fast HikariCP timeouts and their tests.
The row of `import-leads-into-salesforce-java` and the Tier 1 tests total under Totals were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that adds the late-query cleanup test of its live test.
The rows of the seven SC-15 Salesforce projects (`salesforce-data-retrieval-java`, `import-contacts-into-salesforce-java`, `import-leads-into-salesforce-java`, `authenticating-salesforce-using-oauth2-java`, `salesforce-data-synchronization-using-watermarking-and-batch-processing-java`, `salesforce-to-MySQL-DB-using-Batch-Processing-java`, `dataweave-with-flowreflookup-java`) and of the four SC-17 RAML projects (`rest-api-with-apikit-java`, `testing-apikit-with-munit-java`, `processing-orders-with-dataweave-and-APIkit-java`, `netsuite-data-retrieval-java`), and the Tier 1 tests total under Totals, were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that adds the single-flight Salesforce login, the bounded `queryMore` paging and the APIkit 404 for every method but GET and HEAD on a RAML include alias.
The rows of `service-orchestration-and-choice-routing-java` and `web-service-consumer-java` were re-measured on 2026-10-28 with `mvn -B clean install` on the tree whose SC-4 `DwXmlToJson` reads an element without text as the empty string, and the Tier 1 tests total under Totals was updated to match.
The rows of `service-orchestration-and-choice-routing-java` and `websphere-mq-java` were re-measured on 2026-10-28 with `mvn -B clean install` and `mvn -B test` on the tree whose SC-12 held connects claim their deliveries when their request arrives and take them only while that claim is current, the orchestration row again once its `WebServiceConfig` SAAJ transformer-factory setting and test were merged, and the Tier 1 tests total under Totals was updated to match.
The rows of `foreach-processing-and-choice-routing-java`, `get-customer-list-from-netsuite-java`, `importing-a-CSV-file-into-Mongo-DB-java`, `netsuite-data-retrieval-java` and `soap-webservice-security-java`, and the Tier 1 tests total under Totals, were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that adds the SOAP reply edge-case and malformed-CSV tests and the NetSuite single-flight token request, bounded paging and request-free error messages.
The row of `service-orchestration-and-choice-routing-java` and the Tier 1 tests total under Totals were re-measured on 2026-10-28 with `mvn -B clean install` on the tree that registers Narayana's connection modifier for the embedded Derby driver and adds its concurrent-audit test.

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
| [import-leads-into-salesforce-java](java-conversions/import-leads-into-salesforce-java/README.md) | [import-leads-into-salesforce](import-leads-into-salesforce/) | Third-party connector | PASS | PASS | 98.6% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [authenticating-salesforce-using-oauth2-java](java-conversions/authenticating-salesforce-using-oauth2-java/README.md) | [authenticating-salesforce-using-oauth2](authenticating-salesforce-using-oauth2/) | Third-party connector | PASS | PASS | 100.0% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [get-customer-list-from-netsuite-java](java-conversions/get-customer-list-from-netsuite-java/README.md) | [get-customer-list-from-netsuite](get-customer-list-from-netsuite/) | Third-party connector | PASS | PASS | 98.5% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [netsuite-data-retrieval-java](java-conversions/netsuite-data-retrieval-java/README.md) | [netsuite-data-retrieval](netsuite-data-retrieval/) | Third-party connector (RAML-backed) | PASS | PASS | 99.3% | RAML DIFF: 1:1 (Tier 1 static) | NOT RUN — sandbox credentials not supplied |
| [salesforce-data-synchronization-using-watermarking-and-batch-processing-java](java-conversions/salesforce-data-synchronization-using-watermarking-and-batch-processing-java/README.md) | [salesforce-data-synchronization-using-watermarking-and-batch-processing](salesforce-data-synchronization-using-watermarking-and-batch-processing/) | Third-party connector | PASS | PASS | 96.9% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
| [salesforce-to-MySQL-DB-using-Batch-Processing-java](java-conversions/salesforce-to-MySQL-DB-using-Batch-Processing-java/README.md) | [salesforce-to-MySQL-DB-using-Batch-Processing](salesforce-to-MySQL-DB-using-Batch-Processing/) | Third-party connector | PASS | PASS | 97.1% | PARITY: UNVERIFIED — Mule runtime unavailable | NOT RUN — sandbox credentials not supplied |
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
- Tier 1 tests: 55 `PASS`, 0 `FAIL`; 23,897 tests run, 0 failures, 0 errors, 51 skipped (`FixtureParityTest` in each of the 51 non-RAML projects).
- Coverage (service + mapper): every delivered project at or above the 80% gate; lowest 85.4%, highest 100.0%.
- Parity evidence: 51 `PARITY: UNVERIFIED — Mule runtime unavailable`, 4 `RAML DIFF: 1:1 (Tier 1 static)`, 0 `PARITY: VERIFIED — Tier 2A fixtures`, 0 `PARITY: PARTIAL — Tier 2A fixtures`, 0 `PARITY: FAIL — Tier 2A fixtures`, 1 `EXCLUDED — pending sapjco3.jar`.
- Tier 2B live sandbox: 11 `NOT RUN — sandbox credentials not supplied` (14 `*IT` tests, 14 skipped), 0 `PASS`, 0 `FAIL`, 44 `N/A`, 1 `EXCLUDED — pending sapjco3.jar`.

## Evidence per project

The counts behind the measured cells. Tests are those of the `mvn -B test` run, which matched the surefire run of `mvn -B clean package` in every project. Total LINE coverage is reported beside the gated service + mapper figure; it is not gated.

| Project | mvn -B test: run / failures / errors / skipped | Service + mapper lines covered | Total LINE coverage (generated classes excluded) | Failsafe *IT (mvn -B verify) |
|---|---|---|---|---|
| rest-api-with-apikit-java | 663 / 0 / 0 / 0 | 131 of 131 | 89.7% (1204 of 1342) | — |
| testing-apikit-with-munit-java | 443 / 0 / 0 / 0 | 13 of 13 | 83.3% (686 of 824) | — |
| processing-orders-with-dataweave-and-APIkit-java | 715 / 0 / 0 / 0 | 421 of 461 | 89.5% (1309 of 1463) | — |
| web-service-consumer-java | 520 / 0 / 0 / 1 | 447 of 457 | 97.4% (797 of 818) | — |
| xml-only-soap-webservice-java | 419 / 0 / 0 / 1 | 644 of 657 | 91.6% (1457 of 1591) | — |
| service-orchestration-and-choice-routing-java | 957 / 0 / 0 / 1 | 1173 of 1181 | 92.8% (2895 of 3120) | — |
| jms-message-rollback-and-redelivery-java | 63 / 0 / 0 / 1 | 77 of 78 | 98.5% (201 of 204) | — |
| sending-json-data-to-a-jms-queue-java | 272 / 0 / 0 / 1 | 163 of 163 | 98.1% (411 of 419) | — |
| using-transactional-scope-in-jms-to-database-java | 97 / 0 / 0 / 1 | 115 of 119 | 96.6% (229 of 237) | — |
| salesforce-data-retrieval-java | 1059 / 0 / 0 / 1 | 357 of 361 | 95.8% (1107 of 1155) | 2 run, 2 skipped, 0 failed, 0 errors |
| import-contacts-into-salesforce-java | 701 / 0 / 0 / 1 | 92 of 92 | 91.7% (697 of 760) | 1 run, 1 skipped, 0 failed, 0 errors |
| import-leads-into-salesforce-java | 800 / 0 / 0 / 1 | 341 of 346 | 96.4% (995 of 1032) | 1 run, 1 skipped, 0 failed, 0 errors |
| authenticating-salesforce-using-oauth2-java | 898 / 0 / 0 / 1 | 93 of 93 | 96.2% (920 of 956) | 1 run, 1 skipped, 0 failed, 0 errors |
| get-customer-list-from-netsuite-java | 708 / 0 / 0 / 1 | 257 of 261 | 95.5% (1302 of 1363) | 1 run, 1 skipped, 0 failed, 0 errors |
| netsuite-data-retrieval-java | 1524 / 0 / 0 / 0 | 438 of 441 | 93.2% (2043 of 2191) | 3 run, 3 skipped, 0 failed, 0 errors |
| salesforce-data-synchronization-using-watermarking-and-batch-processing-java | 766 / 0 / 0 / 1 | 339 of 350 | 96.6% (932 of 965) | 1 run, 1 skipped, 0 failed, 0 errors |
| salesforce-to-MySQL-DB-using-Batch-Processing-java | 818 / 0 / 0 / 1 | 363 of 374 | 96.7% (1007 of 1041) | 1 run, 1 skipped, 0 failed, 0 errors |
| adding-a-new-customer-to-workday-revenue-management-java | 577 / 0 / 0 / 1 | 73 of 73 | 96.4% (669 of 694) | 1 run, 1 skipped, 0 failed, 0 errors |
| import-contacts-into-ms-dynamics-java | 452 / 0 / 0 / 1 | 106 of 106 | 91.6% (706 of 771) | 1 run, 1 skipped, 0 failed, 0 errors |
| dataweave-with-flowreflookup-java | 789 / 0 / 0 / 1 | 128 of 128 | 92.1% (733 of 796) | 1 run, 1 skipped, 0 failed, 0 errors |
| addition-using-javascript-transformer-java | 652 / 0 / 0 / 1 | 893 of 894 | 99.2% (1124 of 1133) | — |
| cache-scope-with-fibonacci-java | 186 / 0 / 0 / 1 | 65 of 65 | 97.5% (312 of 320) | — |
| content-based-routing-java | 216 / 0 / 0 / 1 | 17 of 17 | 97.6% (287 of 294) | — |
| document-integration-using-the-cmis-connector-java | 172 / 0 / 0 / 1 | 16 of 18 | 95.4% (481 of 504) | — |
| exposing-a-restful-resource-using-the-HTTP-connector-java | 229 / 0 / 0 / 1 | 23 of 23 | 97.9% (281 of 287) | — |
| extracting-data-from-LDAP-directory-java | 132 / 0 / 0 / 1 | 17 of 17 | 95.4% (332 of 348) | — |
| filtering-a-message-java | 172 / 0 / 0 / 1 | 36 of 36 | 96.7% (265 of 274) | — |
| foreach-processing-and-choice-routing-java | 511 / 0 / 0 / 1 | 77 of 78 | 88.3% (1400 of 1585) | — |
| hello-world-java | 452 / 0 / 0 / 1 | 2 of 2 | 96.9% (221 of 228) | — |
| http-multipart-request-java | 302 / 0 / 0 / 1 | 158 of 185 | 92.1% (385 of 418) | — |
| http-oauth-provider-java | 473 / 0 / 0 / 1 | 206 of 206 | 96.6% (744 of 770) | — |
| http-request-response-with-logger-java | 175 / 0 / 0 / 1 | 5 of 5 | 96.8% (215 of 222) | — |
| implementing-a-choice-exception-strategy-java | 180 / 0 / 0 / 1 | 34 of 34 | 97.8% (306 of 313) | — |
| importing-a-CSV-file-into-Mongo-DB-java | 108 / 0 / 0 / 1 | 96 of 96 | 85.0% (300 of 353) | — |
| importing-a-csv-file-into-ms-sharepoint-java | 87 / 0 / 0 / 1 | 69 of 69 | 78.6% (433 of 551) | — |
| importing-an-email-attachment-using-the-IMAP-connector-java | 280 / 0 / 0 / 1 | 372 of 395 | 95.1% (543 of 571) | — |
| importing-an-email-attachment-using-the-POP3-connector-java | 218 / 0 / 0 / 1 | 374 of 397 | 93.7% (507 of 541) | — |
| legacy-modernization-java | 352 / 0 / 0 / 1 | 224 of 247 | 88.7% (1154 of 1301) | — |
| login-form-using-the-http-connector-java | 287 / 0 / 0 / 1 | 85 of 89 | 94.6% (434 of 459) | — |
| mule-component-bindings-java | 502 / 0 / 0 / 1 | 104 of 104 | 88.3% (1045 of 1183) | — |
| mule-expression-language-basics-java | 609 / 0 / 0 / 1 | 266 of 291 | 94.2% (504 of 535) | — |
| munit-short-tutorial-java | 164 / 0 / 0 / 1 | 16 of 16 | 97.1% (236 of 243) | — |
| oauth2-authorization-code-using-the-HTTP-connector-java | 455 / 0 / 0 / 1 | 176 of 176 | 95.6% (905 of 947) | — |
| oauth2-client-credentials-using-the-HTTP-connector-java | 432 / 0 / 0 / 1 | 40 of 40 | 90.7% (909 of 1002) | — |
| proxying-a-rest-api-java | 641 / 0 / 0 / 1 | 41 of 41 | 94.3% (1213 of 1287) | — |
| proxying-a-soap-api-java | 326 / 0 / 0 / 1 | 54 of 54 | 92.8% (837 of 902) | — |
| querying-a-db-and-attaching-results-to-an-email-java | 285 / 0 / 0 / 1 | 137 of 139 | 98.2% (427 of 435) | — |
| querying-a-mysql-database-java | 147 / 0 / 0 / 1 | 9 of 9 | 96.4% (214 of 222) | — |
| scatter-gather-flow-control-java | 220 / 0 / 0 / 1 | 160 of 163 | 97.1% (409 of 421) | — |
| sending-a-csv-file-through-email-using-smtp-java | 108 / 0 / 0 / 1 | 107 of 107 | 86.0% (283 of 329) | — |
| sending-json-data-to-a-amqp-queue-java | 185 / 0 / 0 / 1 | 42 of 42 | 97.5% (394 of 404) | — |
| soap-webservice-security-java | 547 / 0 / 0 / 1 | 2 of 2 | 88.4% (1661 of 1879) | — |
| track-a-custom-business-event-java | 237 / 0 / 0 / 1 | 129 of 129 | 98.3% (354 of 360) | — |
| upload-to-ftp-after-converting-json-to-xml-java | 303 / 0 / 0 / 1 | 291 of 294 | 91.7% (552 of 602) | — |
| websphere-mq-java | 311 / 0 / 0 / 1 | 432 of 435 | 96.0% (1000 of 1042) | — |
