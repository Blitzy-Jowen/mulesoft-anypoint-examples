/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.config.CmisProperties;

/**
 * Spring Boot entry point of the converted Mule example {@code document-integration-using-the-cmis-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor, which loaded
 * {@code cmis-document-integration.xml} and its single flow {@code CMIS} into the Mule runtime. This class starts the
 * application as a standalone process on Undertow; the package scan wires the flow's replacements:
 * {@code controller.CmisController} for the HTTP listener on {@code /cmis}, {@code service.CmisDocumentService},
 * which uploads {@code mulesoft-logo.jpg} to the configured CMIS repository and renders the new document's ID as
 * JSON, {@code client.CmisAtomPubClient} for {@code cmis:create-document-by-path-from-content}, and
 * {@code exception.GlobalExceptionHandler} for the platform-default errors. The listener port and address and the
 * repository settings ({@code cmis.*}) come from {@code application.yml}.
 *
 * <p>{@link CmisProperties} carries no stereotype annotation, so it is registered here, once. Startup contacts no
 * repository and accepts unset credentials; each upload checks them before its first request.
 */
@SpringBootApplication
@EnableConfigurationProperties(CmisProperties.class)
public class DocumentIntegrationUsingTheCmisConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentIntegrationUsingTheCmisConnectorApplication.class, args);
    }
}
