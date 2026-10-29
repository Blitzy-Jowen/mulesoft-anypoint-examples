/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login credentials and requester host, bound from the {@code login.*} keys of {@code application.yml}.
 *
 * <p>The original flow hard-codes these values in {@code login-form-using-the-http-connector.xml}: the
 * credential check of {@code DoLoginFlow} (line 21), the form map {@code CallLoginFlowUsingRequester}
 * posts back to {@code /login} (lines 38-40) and the host of its {@code http:request-config} (line 5).
 * Credentials are never hard-coded here, so {@code login.username} and {@code login.password} have no
 * default and stay empty until supplied; the original demo values come only from the {@code test}
 * profile. Rejecting a blank configured credential is the login service's job, not this record's.
 *
 * @param username      {@code login.username}, the only username the login form accepts and the one
 *                      the requester posts
 * @param password      {@code login.password}, the matching password
 * @param requesterHost {@code login.requester-host}, the host the requester calls its own listener on;
 *                      {@code localhost} by default, because the original's {@code 0.0.0.0} is a bind
 *                      address rather than one a client can connect to
 */
@ConfigurationProperties("login")
public record LoginProperties(String username, String password, @DefaultValue("localhost") String requesterHost) {

    /**
     * Describes the bound values with the password masked. The mask is written whether or not a
     * password is set, so the output never reveals the secret or its presence.
     */
    @Override
    public String toString() {
        return "LoginProperties[username=" + username + ", password=****, requesterHost=" + requesterHost + "]";
    }
}
