/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService;

/**
 * HTTP trigger of flow {@code CMIS} ({@code cmis-document-integration.xml:5-6}): the listener on path {@code cmis},
 * which declares no {@code allowedMethods} and so runs the flow for every HTTP method. Loading the logo, creating the
 * document in the CMIS repository and rendering its ID as JSON all belong to {@link CmisDocumentService}.
 *
 * <p>The flow reads no query, header or body, so no handler declares parameters, a request body, {@code consumes} or
 * {@code produces}: the original test's empty POST and the README's browser GET are answered alike, and no
 * {@code Accept} or {@code Content-Type} the caller sends turns the request into a 406 or 415. Each request creates
 * exactly one document. The match is exact; {@code /cmis/}, {@code /cmis/x} and every other path fall through to the
 * no-listener 404 of {@code GlobalExceptionHandler}. The flow has no exception strategy, so every failure propagates
 * unchanged to that handler's 500.
 */
@RestController
@RequestMapping("/cmis")
public class CmisController {

    private final CmisDocumentService cmisDocumentService;

    public CmisController(CmisDocumentService cmisDocumentService) {
        this.cmisDocumentService = cmisDocumentService;
    }

    /**
     * Answers {@code /cmis} for every method except {@code OPTIONS}; a {@code HEAD} response carries the headers
     * without the body, although the document is still created.
     *
     * @return 200 {@code application/json} {@code {"id":"<objectId>"}}
     */
    @RequestMapping
    public ResponseEntity<String> createDocument() {
        return respond();
    }

    /**
     * Answers {@code OPTIONS /cmis} like every other method. A mapping without methods deliberately does not match
     * {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and no body; the
     * Mule listener ran the flow, and so created a document, for {@code OPTIONS} too.
     *
     * @return 200 {@code application/json} {@code {"id":"<objectId>"}}
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<String> createDocumentOnOptions() {
        return respond();
    }

    /**
     * Returns the service's JSON unchanged. The Content-Type is preset on the entity, so Spring writes the body
     * without consulting {@code Accept} and can never answer 406.
     */
    private ResponseEntity<String> respond() {
        String json = cmisDocumentService.createDocument();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
