/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.extracting_data_from_ldap_directory.service.LdapSearchService;
import com.unboundid.ldap.sdk.LDAPException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP trigger of flow {@code ldapFlow1}: replaces the {@code http:listener-config} and the {@code http:listener}
 * on {@code /} (extracting-data-from-LDAP-directory/src/main/app/ldap.xml:6-8). The listener's host and port live
 * in {@code application.yml}; the directory search, the per-user and aggregate logging and the text rendering
 * belong to {@link LdapSearchService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so {@code /} accepts every method and answers each alike
 * with the people of the directory as text. The search replaced the listener's payload, so the request body,
 * query string and headers are ignored: no handler declares parameters, {@code produces} or {@code consumes},
 * and the caller's {@code Accept}, {@code Content-Type} and body are never rejected. The match is exact: any
 * other path falls through to the no-listener 404 of {@code GlobalExceptionHandler}, which also answers every
 * directory failure with its 500, the flow having no exception strategy.
 *
 * <p>The reply carries a {@code Content-Length} and no {@code Content-Type}: the flow ends with
 * {@code object-to-string-transformer} over the untyped list of entries (ldap.xml:15), which declares no MIME type,
 * and the Mule listener sent no Content-Type for such a payload. It is written to the servlet response directly,
 * so no message converter adds one and the caller's {@code Accept} can never turn it into a 406.
 */
@RestController
public class LdapController {

    private final LdapSearchService ldapSearchService;

    public LdapController(LdapSearchService ldapSearchService) {
        this.ldapSearchService = ldapSearchService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers
     * without the body.
     *
     * @param response the response the entries under {@code ou=people} are written to: 200 with their
     *                 {@code [<ldif>, <ldif>, ...]} text as UTF-8 bytes, its {@code Content-Length} and no
     *                 {@code Content-Type}, or an empty body with {@code Content-Length: 0} when the search finds none
     * @throws LDAPException unchanged from the search, for the 500 of {@code GlobalExceptionHandler}
     * @throws IOException   when writing the response fails
     */
    @RequestMapping("/")
    public void search(HttpServletResponse response) throws LDAPException, IOException {
        searchResponse(response);
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body;
     * the Mule listener ran the flow for {@code OPTIONS} too. A CORS pre-flight reaches this method as well,
     * dispatched as the plain {@code OPTIONS} request it is by {@code PreflightDispatchConfig}.
     *
     * @param response the response written as {@link #search(HttpServletResponse)} writes it
     * @throws LDAPException unchanged from the search, for the 500 of {@code GlobalExceptionHandler}
     * @throws IOException   when writing the response fails
     */
    @RequestMapping(value = "/", method = RequestMethod.OPTIONS)
    public void searchOptions(HttpServletResponse response) throws LDAPException, IOException {
        searchResponse(response);
    }

    /**
     * Writes 200 with the search text's UTF-8 bytes and their {@code Content-Length}. The text is obtained before
     * the response is touched, so a directory failure still finds an uncommitted response for the 500 of
     * {@code GlobalExceptionHandler}.
     */
    private void searchResponse(HttpServletResponse response) throws LDAPException, IOException {
        byte[] bytes = ldapSearchService.searchPeople().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }
}
