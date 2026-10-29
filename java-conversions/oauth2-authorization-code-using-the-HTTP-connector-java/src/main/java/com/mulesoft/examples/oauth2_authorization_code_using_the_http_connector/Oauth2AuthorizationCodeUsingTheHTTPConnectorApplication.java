/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code oauth2-authorization-code-using-the-HTTP-connector}
 * (Apache License 2.0, see the project's {@code LICENSE} and {@code NOTICE}), a Box OAuth 2.0 authorization-code
 * client.
 *
 * <p>Replaces the original's {@code mule-deploy.properties} container descriptor
 * ({@code config.resources=http-authorization-code-web.xml}) and hosts what that flow file declares:
 * <ul>
 *   <li>the main listener {@code HTTP_Listener_Configuration} ({@code server.address}, {@code server.port}):
 *       {@code /web} (flow {@code boxUserLoginFlow}) and {@code /web/loginDone} (flow {@code userLoginDoneFlow}),
 *       served by {@code controller.WebController} → {@code service.BoxOAuthService} → {@code client.BoxApiClient};</li>
 *   <li>the HTTPS listener the OAuth module opened for {@code localAuthorizationUrl} and {@code redirectionUrl}
 *       ({@code ports.box-oauth}): {@code /authorization} and {@code /redirectUrl}, served by
 *       {@code controller.BoxAuthorizationController} → {@code service.BoxOAuthService}.</li>
 * </ul>
 *
 * <p>Every component is found by component scanning from this package, so the class declares no beans. Properties
 * classes are deliberately not scanned: {@code config.ListenerPorts} registers its own {@code listeners} properties,
 * which a scan would register a second time, and {@code config.BoxProperties} binds through {@code @Value}. Hosts,
 * ports, store locations and every credential come from {@code application.yml}.
 *
 * <p>Mutual TLS on the HTTPS listener needs no customizer here: SC-10 {@code config.ListenerPorts} sets
 * {@code Options.SSL_CLIENT_AUTH_MODE} to {@code REQUIRED} on every listener whose {@code ssl} entry names a trust
 * store, as the original {@code TLS_Context} required a client certificate.
 */
@SpringBootApplication
public class Oauth2AuthorizationCodeUsingTheHTTPConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(Oauth2AuthorizationCodeUsingTheHTTPConnectorApplication.class, args);
    }
}
