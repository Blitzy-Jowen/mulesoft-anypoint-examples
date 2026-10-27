/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.mulesoft.examples.extracting_data_from_ldap_directory.exception.ErrorResponse;
import com.mulesoft.examples.extracting_data_from_ldap_directory.service.LdapSearchService;
import com.mulesoft.examples.extracting_data_from_ldap_directory.support.HttpTestClient;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link LdapController}, the HTTP trigger of flow {@code ldapFlow1}
 * (extracting-data-from-LDAP-directory/src/main/app/ldap.xml:6-8), on the application's own Undertow listener.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so the reason phrase, the {@code Content-Type} and {@code Content-Length} headers and the body bytes are
 * asserted exactly as the listener writes them. The happy-path cases cover every method, because the original
 * listener declares no {@code allowedMethods}; the flow has no exception strategy, so the error cases are the
 * listener-level platform defaults of SC-1: the no-listener 404 for paths the flow does not serve and the JSON 500
 * for a directory failure.
 *
 * <p>{@link LdapSearchService} is mocked, so the test needs no directory server, credential or profile and
 * connects to nothing but its own listener on {@code localhost}. The assertions of the original
 * {@code ExtractingDataFromLdapDirectoryIT} against a real directory are ported separately in
 * {@code ExtractingDataFromLdapDirectoryTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapControllerTest {

    /** A two-entry search result in the service's {@code [<ldif>, <ldif>]} rendering, LF line ends included. */
    static final String LDIF =
            "[dn: cn=mmc,ou=people\nobjectClass: top\ncn: mmc\n, dn: cn=admin,ou=people\ncn: admin\n]";

    /** Request headers for the methods sent with a body, which the flow ignores. */
    static final Map<String, String> TEXT = Map.of("Content-Type", "text/plain");

    @MockBean
    private LdapSearchService searchService;

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void getRootReturnsServiceTextWithoutContentType() throws LDAPException {
        when(searchService.searchPeople()).thenReturn(LDIF);

        HttpTestClient.Response response = client().get("/");

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertUntypedWithLength(response, LDIF.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(LDIF, response.bodyAsString(), response::toString);
        assertArrayEquals(LDIF.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        verify(searchService, times(1)).searchPeople();
    }

    /**
     * The original listener declares no {@code allowedMethods}, so no method is answered with a 405: each runs the
     * search and gets the same text, its request body ignored. {@code OPTIONS} reaches the controller too instead
     * of Spring MVC's own {@code Allow} reply, and {@code TRACE} instead of the servlet's {@code message/http} echo
     * of the request.
     *
     * @param method the request method, sent with the same text body each time
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE"})
    void everyOtherMethodIsAccepted(String method) throws LDAPException {
        when(searchService.searchPeople()).thenReturn(LDIF);

        HttpTestClient.Response response =
                client().send(method, "/", TEXT, "ignored".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertUntypedWithLength(response, LDIF.getBytes(StandardCharsets.UTF_8).length);
        assertArrayEquals(LDIF.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        verify(searchService, times(1)).searchPeople();
    }

    /**
     * {@code HEAD} runs the search like {@code GET} and gets its status line and headers, the
     * {@code Content-Length} of the text included, without the body. ST-1 reads no body bytes for a {@code HEAD}
     * request whatever its {@code Content-Length}.
     */
    @Test
    void headRunsTheSearchAndIsAnsweredWithoutBody() throws LDAPException {
        when(searchService.searchPeople()).thenReturn(LDIF);

        HttpTestClient.Response response = client().send("HEAD", "/", null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertUntypedWithLength(response, LDIF.getBytes(StandardCharsets.UTF_8).length);
        assertArrayEquals(new byte[0], response.body(), response::toString);
        verify(searchService, times(1)).searchPeople();
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client().get("/unknown");

        assertEquals(404, response.status(), response::toString);
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: /unknown", response.bodyAsString(), response::toString);
        verifyNoInteractions(searchService);
    }

    /**
     * A directory failure propagates unchanged from the service, and the flow declares no exception strategy, so
     * SC-1's catch-all answers it. The expected body is built from the thrown instance because UnboundID may
     * decorate the message it was given.
     */
    @Test
    void ldapFailureReturns500Json() throws LDAPException {
        LDAPException ex = new LDAPException(ResultCode.CONNECT_ERROR, "Unable to connect to the LDAP server");
        when(searchService.searchPeople()).thenThrow(ex);

        HttpTestClient.Response response = client().get("/");

        assertEquals(500, response.status(), response::toString);
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"" + ex.getMessage() + "\" }", response.bodyAsString(), response::toString);
        assertEquals(ErrorResponse.json(ex.getMessage()), response.bodyAsString(), response::toString);
        verify(searchService, times(1)).searchPeople();
    }

    /** A search that finds nobody is still a 200 reply, with no body bytes and {@code Content-Length: 0}. */
    @Test
    void emptyResultReturns200WithEmptyBody() throws LDAPException {
        when(searchService.searchPeople()).thenReturn("");

        HttpTestClient.Response response = client().get("/");

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertUntypedWithLength(response, 0);
        assertEquals(0, response.body().length, response::toString);
        verify(searchService, times(1)).searchPeople();
    }

    /**
     * Compares the whole value: each error reply presets its media type on the response entity, so the listener
     * writes exactly one {@code Content-Type} header carrying that value.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }

    /**
     * The flow ends with {@code object-to-string-transformer} over an untyped list, so the Mule listener sent no
     * {@code Content-Type}: the reply carries none, only exactly one {@code Content-Length} of its body bytes.
     */
    private static void assertUntypedWithLength(HttpTestClient.Response response, int bodyBytes) {
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(Integer.toString(bodyBytes)), response.headers("Content-Length"), response::toString);
    }
}
