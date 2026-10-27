/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.soap_webservice_security.config.SoapSecurityProperties;

/**
 * Spring Boot entry point of the converted Mule example {@code soap-webservice-security}.
 *
 * <p>Replaces the Mule application the original's {@code mule-deploy.properties} assembled from
 * {@code service-clients.xml} and {@code mule-config.xml}, and serves both of its listeners from one process:
 * <ul>
 *   <li>the SOAP {@code Greeter} service on 63081 under {@code /services/*}, at the six paths {@code unsecure},
 *       {@code username}, {@code signed}, {@code encrypted}, {@code saml} and {@code signedsaml}, each enforcing its
 *       original WS-Security action set;</li>
 *   <li>the HTTP client front on 63080 at {@code /client}, which calls those paths with the matching secured
 *       client.</li>
 * </ul>
 * Ports, paths and credentials come from {@code application.yml}; all wiring lives in the {@code config} package.
 *
 * <p>Spring WS is configured by the project's {@code WsConfigurer} alone; Spring WS's own enabling annotation is
 * deliberately absent, because it switches off Boot's web-services auto-configuration and with it the
 * {@code MessageDispatcherServlet} that serves the six Greeter paths. {@link SoapSecurityProperties} is registered
 * here by name rather than through a configuration-properties scan, which would register the listener properties
 * that {@code config/ListenerPorts} already enables a second time.
 */
@SpringBootApplication
@EnableConfigurationProperties(SoapSecurityProperties.class)
public class SoapWebserviceSecurityApplication {

    /**
     * Starts both listeners.
     *
     * @param args Spring Boot command-line arguments, for example {@code --server.port} and {@code --ports.client}
     */
    public static void main(String[] args) {
        SpringApplication.run(SoapWebserviceSecurityApplication.class, args);
    }
}
