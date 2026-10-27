/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.ext.WSPasswordCallback;

/**
 * Supplies the password of a known WS-Security user to WSS4J: the expected password of an inbound UsernameToken, the
 * password of a private key, and the password the outbound Greeter clients send for their configured user.
 *
 * <p>The users and their passwords come from the configured {@code soap-security.users} map instead of the literals
 * the original handler held. A user whose configured password is empty is not configured and counts as unknown. An
 * unknown user gets no password, as in the original, so WSS4J rejects the token with {@code FAILED_AUTHENTICATION}.
 *
 * <p>Joe's password is also the private-key password of keystore alias {@code joe} ({@code keystore.jks}), used for
 * inbound decryption on {@code /services/encrypted} and outbound signing ({@code usernameTokenSigned},
 * {@code samlTokenSigned}).
 *
 * <p>Deliberately not a Spring component: {@code WsSecurityConfig} creates the single {@code passwordCallback} bean
 * from {@link SoapSecurityProperties#getUsers()}, and tests construct one from a plain map. Immutable and thread-safe.
 */
public class PasswordCallback implements CallbackHandler {

    private final Map<String, String> users;

    /**
     * @param users WS-Security user name to password; entries with a {@code null} user name or a {@code null} or
     *              empty password are left out
     * @throws NullPointerException if {@code users} is {@code null}
     */
    public PasswordCallback(Map<String, String> users) {
        Objects.requireNonNull(users, "users");
        this.users = users.entrySet().stream()
                .filter(user -> user.getKey() != null && user.getValue() != null && !user.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Reads only {@code callbacks[0]}, which must be a {@link WSPasswordCallback}, and sets its password when its
     * identifier is a configured user; otherwise leaves it unset.
     */
    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        WSPasswordCallback pc = (WSPasswordCallback) callbacks[0];

        // A null identifier (an empty wsse:Username) fails with NullPointerException, as the original's
        // getIdentifier().equals(...) did, rather than being read as an unknown user; PathScopedSecurityInterceptor
        // answers it with a wsse:InvalidSecurity fault.
        String identifier = Objects.requireNonNull(pc.getIdentifier(), "WSPasswordCallback identifier");

        String password = users.get(identifier);
        if (password != null) {
            pc.setPassword(password);
        }
    }
}
