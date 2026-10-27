/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.login_form_using_the_http_connector.support.HttpTestClient;

/**
 * Port of the original example's {@code HttpLoginHtmlFormIT} functional test, keeping its three requests and
 * their comparison semantics: the login page must equal {@code index.html} exactly, and both login replies must
 * contain {@link #REPLY}. Status lines and headers are asserted by the controller and exception-handler tests.
 *
 * <p>The {@code test} profile supplies the original demo credentials {@code mule}/{@code mule} as
 * {@code login.username} and {@code login.password}; without it both stay empty and every login is rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpLoginHtmlFormTest {

    private static final String REPLY = "User mule has been logged successfully!";

    private static String HTML;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void init() throws IOException {
        // The original's file-system path, relative to the project directory surefire runs in. A missing page
        // fails the class here instead of leaving HTML null as the original's printStackTrace did.
        HTML = Files.readString(Path.of("./src/main/resources/login/index.html"), StandardCharsets.UTF_8);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void testLogin() {
        HttpTestClient.Response response =
                client().post("/login", "application/x-www-form-urlencoded", "username=mule&password=mule");
        assertNotNull(response);
        assertTrue(response.bodyAsString().contains(REPLY), () -> "Unexpected response:\n" + response);
    }

    @Test
    void testLoginPage() {
        HttpTestClient.Response response = client().get("/login");
        assertNotNull(response);
        assertEquals(HTML, response.bodyAsString());
    }

    @Test
    void testRequester() {
        HttpTestClient.Response response = client().get("/requesterLogin");
        assertNotNull(response);
        assertTrue(response.bodyAsString().contains(REPLY), () -> "Unexpected response:\n" + response);
    }
}
