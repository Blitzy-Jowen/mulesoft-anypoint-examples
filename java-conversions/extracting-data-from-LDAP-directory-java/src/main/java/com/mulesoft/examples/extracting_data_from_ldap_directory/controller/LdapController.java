/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.extracting_data_from_ldap_directory.controller;

import com.mulesoft.examples.extracting_data_from_ldap_directory.service.LdapSearchService;
import com.unboundid.ldap.sdk.LDAPException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
public class LdapController {

    /**
     * Preset on every reply, so Spring writes the text without consulting {@code Accept} and never answers 406.
     */
    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final LdapSearchService ldapSearchService;

    public LdapController(LdapSearchService ldapSearchService) {
        this.ldapSearchService = ldapSearchService;
    }

    /**
     * Answers {@code /} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers
     * without the body.
     *
     * @return 200 {@code text/plain;charset=UTF-8} with the entries under {@code ou=people} as
     *         {@code [<ldif>, <ldif>, ...]}, or an empty body when the search finds none
     * @throws LDAPException unchanged from the search, for the 500 of {@code GlobalExceptionHandler}
     */
    @RequestMapping("/")
    public ResponseEntity<String> search() throws LDAPException {
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .body(ldapSearchService.searchPeople());
    }

    /**
     * Answers {@code OPTIONS /} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body;
     * the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @return the response {@link #search()} gives
     * @throws LDAPException unchanged from the search, for the 500 of {@code GlobalExceptionHandler}
     */
    @RequestMapping(value = "/", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> searchOptions() throws LDAPException {
        return search();
    }
}
