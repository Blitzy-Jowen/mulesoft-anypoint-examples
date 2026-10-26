/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.ext.WSPasswordCallback;

/**
 * Supplies the wrong password for the user "joe".
 *
 * <p>The negative test {@code testUsernameTokenWrongPasswordError} installs this handler in the UsernameToken client,
 * so the {@code /services/username} endpoint rejects the token with a {@code wsse:FailedAuthentication} fault. The
 * literal {@code hacker} is that deliberately wrong password, not a credential, which is why it stays in code while
 * every real password is externalized to configuration.
 *
 * <p>Deliberately not a Spring component: as a bean it could be injected wherever a {@link CallbackHandler} is wanted,
 * in place of {@code PasswordCallback}. Tests construct it and hand it to the secured client themselves.
 */
public class WrongPasswordCallback implements CallbackHandler {

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        WSPasswordCallback pc = (WSPasswordCallback) callbacks[0];

        if ("joe".equals(pc.getIdentifier())) {
            pc.setPassword("hacker");
        }
    }
}
