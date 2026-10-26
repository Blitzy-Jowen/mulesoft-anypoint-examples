/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.client.CmisAtomPubClient;

/**
 * Reproduces the processors of flow {@code CMIS} ({@code cmis-document-integration.xml}) in their original order:
 * <ol>
 *   <li>lines 9-12, Groovy component {@code Load file}: the bytes of {@code mulesoft-logo.jpg}, read fresh for each
 *       request (from the classpath rather than the working directory, with no script engine);</li>
 *   <li>line 13, {@code cmis:create-document-by-path-from-content}: one document named
 *       {@code pic<System.currentTimeMillis()>.jpg} created in {@value #FOLDER_PATH} with media type
 *       {@value #MIME_TYPE}, object type {@value #OBJECT_TYPE} and versioning state {@value #VERSIONING_STATE};</li>
 *   <li>line 14, {@code json:object-to-json-transformer}: the connector's {@code ObjectId}, whose only bean property
 *       is {@code id}, as compact JSON {@code {"id":"<objectId>"}}.</li>
 * </ol>
 *
 * <p>The flow declares no logger and no exception strategy, so nothing is logged here and every failure propagates
 * unchanged to the project's generic MVC error handling.
 */
@Service
public class CmisDocumentService {

    /** {@code folderPath} of the original connector operation: the OpenKM repository root. */
    public static final String FOLDER_PATH = "/okm:root/";

    /** {@code mimeType} of the original connector operation, kept although {@code image/jpeg} is the registered type. */
    public static final String MIME_TYPE = "image/jpg";

    /** {@code objectType} of the original connector operation. */
    public static final String OBJECT_TYPE = "cmis:document";

    /** {@code versioningState} of the original connector operation. */
    public static final String VERSIONING_STATE = "NONE";

    private static final String NAME_PREFIX = "pic";

    private static final String NAME_SUFFIX = ".jpg";

    private static final String LOGO_RESOURCE = "mulesoft-logo.jpg";

    /**
     * A private mapper rather than Boot's, so no {@code spring.jackson.*} setting can change the response bytes:
     * Jackson's defaults write compact JSON and leave {@code /} unescaped, which the original IT's prefix check
     * <code>{"id":"/okm:root/pic</code> depends on.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CmisAtomPubClient client;

    private final LongSupplier currentTimeMillis;

    private final Resource content;

    /**
     * Creates the service with the system clock and the classpath logo, as the original flow used.
     *
     * @param client CMIS AtomPub adapter for the configured repository
     */
    @Autowired
    public CmisDocumentService(CmisAtomPubClient client) {
        this(client, System::currentTimeMillis, new ClassPathResource(LOGO_RESOURCE));
    }

    /**
     * Creates the service with an explicit clock and document content.
     *
     * @param client            CMIS AtomPub adapter for the configured repository
     * @param currentTimeMillis source of the millisecond value in the document name
     * @param content           document content, read once per {@link #createDocument()} call
     */
    CmisDocumentService(CmisAtomPubClient client, LongSupplier currentTimeMillis, Resource content) {
        this.client = Objects.requireNonNull(client, "client");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        this.content = Objects.requireNonNull(content, "content");
    }

    /**
     * Uploads the logo as a new document and returns its object ID as JSON.
     *
     * @return {@code {"id":"<objectId>"}}, for example {@code {"id":"/okm:root/pic1712345678901.jpg"}} from OpenKM
     * @throws UncheckedIOException when the document content cannot be read; the repository is then not called
     */
    public String createDocument() {
        byte[] bytes = readContent();
        // Named after the read: Mule evaluated the filename expression in the connector, after the Groovy component.
        String name = NAME_PREFIX + currentTimeMillis.getAsLong() + NAME_SUFFIX;
        String objectId = client.createDocumentByPath(FOLDER_PATH, name, MIME_TYPE, OBJECT_TYPE, VERSIONING_STATE,
                bytes);
        try {
            return JSON.writeValueAsString(Collections.singletonMap("id", objectId));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] readContent() {
        try (InputStream in = content.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read document content " + content.getDescription(), e);
        }
    }
}
