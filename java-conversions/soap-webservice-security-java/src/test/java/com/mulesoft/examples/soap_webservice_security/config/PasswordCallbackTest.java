/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of the ported WS-Security password callbacks: {@link PasswordCallback}, which supplies the configured
 * password of a known user, and {@link WrongPasswordCallback}, which supplies a deliberately wrong one for {@code joe}.
 *
 * <p>The users are the original example's demo users ({@code joe}/{@code secret}, {@code stan}/{@code elephant}),
 * passed as a plain map the way {@code WsSecurityConfig} passes {@code soap-security.users}; no Spring context is
 * involved.
 */
class PasswordCallbackTest {

    private static final Map<String, String> DEMO_USERS = Map.of("joe", "secret", "stan", "elephant");

    private static WSPasswordCallback callback(String identifier, int usage) {
        return new WSPasswordCallback(identifier, usage);
    }

    /** Hands the callback to the handler the way WSS4J does, as the single element of the array. */
    private static WSPasswordCallback handle(CallbackHandler handler, WSPasswordCallback callback) throws Exception {
        handler.handle(new Callback[] { callback });
        return callback;
    }

    /**
     * Joe's password serves the UsernameToken check and, as the key password of keystore alias {@code joe}, inbound
     * decryption and outbound signing.
     */
    static Stream<Arguments> knownUsers() {
        return Stream.of(
                Arguments.of("joe", WSPasswordCallback.USERNAME_TOKEN, "secret"),
                Arguments.of("joe", WSPasswordCallback.DECRYPT, "secret"),
                Arguments.of("joe", WSPasswordCallback.SIGNATURE, "secret"),
                Arguments.of("stan", WSPasswordCallback.USERNAME_TOKEN, "elephant"));
    }

    @ParameterizedTest(name = "{0} with usage {1} gets {2}")
    @MethodSource("knownUsers")
    void knownUserGetsConfiguredPassword(String identifier, int usage, String expectedPassword) throws Exception {
        WSPasswordCallback handled = handle(new PasswordCallback(DEMO_USERS), callback(identifier, usage));

        assertThat(handled.getPassword()).isEqualTo(expectedPassword);
    }

    @ParameterizedTest(name = "\"{0}\" gets no password")
    @ValueSource(strings = { "bob", "", "JOE" })
    void unknownIdentifierGetsNoPasswordAndNoException(String identifier) {
        WSPasswordCallback pc = callback(identifier, WSPasswordCallback.USERNAME_TOKEN);

        assertThatCode(() -> handle(new PasswordCallback(DEMO_USERS), pc)).doesNotThrowAnyException();
        assertThat(pc.getPassword()).isNull();
    }

    @ParameterizedTest(name = "configured password [{0}] counts as unknown")
    @NullAndEmptySource
    void unconfiguredPasswordCountsAsUnknown(String configuredPassword) throws Exception {
        Map<String, String> users = new HashMap<>();
        users.put("joe", configuredPassword);
        users.put("stan", "elephant");
        PasswordCallback handler = new PasswordCallback(users);

        assertThat(handle(handler, callback("joe", WSPasswordCallback.USERNAME_TOKEN)).getPassword()).isNull();
        assertThat(handle(handler, callback("stan", WSPasswordCallback.USERNAME_TOKEN)).getPassword())
                .isEqualTo("elephant");
    }

    @Test
    void nullIdentifierFailsAsTheOriginalDid() {
        PasswordCallback handler = new PasswordCallback(DEMO_USERS);

        assertThatNullPointerException()
                .isThrownBy(() -> handle(handler, callback(null, WSPasswordCallback.USERNAME_TOKEN)));
    }

    @Test
    void nullUserMapIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new PasswordCallback(null));
    }

    @Test
    void laterChangesToTheSuppliedUsersDoNotReachTheHandler() throws Exception {
        Map<String, String> users = new HashMap<>(DEMO_USERS);
        PasswordCallback handler = new PasswordCallback(users);

        users.put("joe", "changed");
        users.put("bob", "added");

        assertThat(handle(handler, callback("joe", WSPasswordCallback.USERNAME_TOKEN)).getPassword())
                .isEqualTo("secret");
        assertThat(handle(handler, callback("bob", WSPasswordCallback.USERNAME_TOKEN)).getPassword()).isNull();
    }

    @Test
    void wrongPasswordCallbackGivesHackerForJoe() throws Exception {
        WSPasswordCallback handled =
                handle(new WrongPasswordCallback(), callback("joe", WSPasswordCallback.USERNAME_TOKEN));

        assertThat(handled.getPassword()).isEqualTo("hacker");
    }

    @ParameterizedTest(name = "WrongPasswordCallback leaves \"{0}\" without a password")
    @ValueSource(strings = { "stan", "bob" })
    void wrongPasswordCallbackIgnoresEveryOtherUser(String identifier) {
        WSPasswordCallback pc = callback(identifier, WSPasswordCallback.USERNAME_TOKEN);

        assertThatCode(() -> handle(new WrongPasswordCallback(), pc)).doesNotThrowAnyException();
        assertThat(pc.getPassword()).isNull();
    }
}
