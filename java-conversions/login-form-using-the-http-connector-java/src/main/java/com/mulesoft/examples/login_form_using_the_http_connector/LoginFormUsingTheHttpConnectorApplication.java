/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot application of the Mule EE 3.8 example {@code login-form-using-the-http-connector}.
 *
 * <p>It replaces {@code mule-deploy.properties}, whose only {@code config.resources} entry is
 * {@code login-form-using-the-http-connector.xml}. The three flows of that file ({@code GetLoginPageFlow},
 * {@code DoLoginFlow}, {@code CallLoginFlowUsingRequester}) are implemented by the components of this package's
 * subpackages, which component scanning registers; {@link ConfigurationPropertiesScan} binds the {@code login.*}
 * configuration. The listener's host and port come from {@code application.yml}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LoginFormUsingTheHttpConnectorApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed to Spring Boot unchanged
     */
    public static void main(String[] args) {
        SpringApplication.run(LoginFormUsingTheHttpConnectorApplication.class, args);
    }
}
