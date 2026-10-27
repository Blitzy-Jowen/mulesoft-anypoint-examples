/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code oauth2-client-credentials-using-the-HTTP-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor
 * ({@code config.resources=http-client-credentials.xml}) and hosts the two flows of
 * {@code http-client-credentials.xml}:
 * <ul>
 *   <li>{@code httpclientcredentialsFlow} (lines 31-38), listener path {@code /oauth}:
 *       {@code controller.OAuthController} → {@code service.BoxClientCredentialsService} →
 *       {@code client.BoxOAuthClient};</li>
 *   <li>{@code http-client-credentialsFlow} (lines 39-47), listener path {@code /FIM/*}:
 *       {@code controller.FimProxyController} → {@code service.FimProxyService} →
 *       {@code client.FimProxyClient}.</li>
 * </ul>
 *
 * <p>Both flows share {@code HTTP_Listener_Configuration} on {@code 0.0.0.0:8081} (line 9), reproduced by
 * {@code server.address} and {@code server.port: ${http.port:8081}} in {@code application.yml}. The server is
 * Undertow, so standard and custom HTTP reason phrases reach the wire.
 *
 * <p>Every component, the configuration classes under {@code config} and the SC-1
 * {@code exception.GlobalExceptionHandler} included, is found by component scanning from this package, so the class
 * declares no beans. Nothing at startup opens a connection or loads a key store, so the context starts with every
 * credential blank.
 */
@SpringBootApplication
public class Oauth2ClientCredentialsUsingTheHTTPConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(Oauth2ClientCredentialsUsingTheHTTPConnectorApplication.class, args);
    }
}
