/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point of the converted Mule example {@code authenticating-salesforce-using-oauth2} (Apache License
 * 2.0, see the project's {@code LICENSE} and {@code NOTICE}).
 *
 * <p>Replaces {@code authenticating-salesforce-using-oauth2/src/main/app/mule-deploy.properties}
 * ({@code config.resources=salesforce-oauth.xml}), which loaded the flow {@code salesforce-oauthFlow1} into the Mule
 * runtime; this class starts the application as a standalone process on Undertow. The flow's listener {@code /} and
 * the connector's OAuth callback {@code /oauth2callback} are both served on {@code 0.0.0.0:${http.port}} (default
 * 8081, every method accepted) by {@code controller.SalesforceOAuthController}, with the {@code sfdc.*} consumer key,
 * consumer secret and OAuth settings taken from {@code application.yml}. Salesforce is contacted only when a browser
 * request arrives, so the application starts with the consumer key and secret unset.
 */
@SpringBootApplication
public class AuthenticatingSalesforceUsingOauth2Application {

    public static void main(String[] args) {
        SpringApplication.run(AuthenticatingSalesforceUsingOauth2Application.class, args);
    }
}
