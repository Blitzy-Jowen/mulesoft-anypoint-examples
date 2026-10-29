/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client.SharepointRestClient;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;

/**
 * The body of the original flow {@code importing-a-csv-file-into-ms-sharepointFlow}
 * ({@code importing-a-csv-file-into-ms-sharepoint.xml}, lines 8 to 14), run for each file the trigger polls.
 *
 * <p>The processors run in the flow's order: the polled bytes are kept as they are (l.8), the folder
 * {@code /Shared Documents/<csvImportFolder>} is created (l.10), the bytes are added to it as {@code contacts.csv},
 * overwriting any existing file (l.12), and the success line is logged (l.14). SharePoint is reached only through
 * {@link SharepointRestClient}.
 *
 * <p>The original declares no exception strategy, so no failure is handled here. A failing step is logged once at
 * ERROR, as Mule's default exception strategy logged the failing processor, with the step ({@code sign-in},
 * {@code folder create} or {@code file add}), the HTTP status code of an error response and the class names of the
 * exception and its causes. The same exception is then rethrown unchanged to the file trigger, whose default handling
 * logs it again and still consumes the file, and every later step is skipped.
 *
 * <p>The ERROR line carries no text SharePoint, the security token service or an exception supplies: no reason
 * phrase, response body, OData error message, SOAP fault reason, request URL, folder or file path, cookie, form digest
 * or password, and no exception message other than that of the configuration check
 * ({@link SharepointProperties.InvalidConfigurationException}), which names only configuration keys and their
 * environment variables. The exception is never handed to the logger, so no stack trace or message reaches the log
 * through it either, and the line stays single and bounded.
 */
@Service
public class SharepointImportService {

    private static final Logger log = LoggerFactory.getLogger(SharepointImportService.class);

    /** The most causes named after the failure itself; further causes are marked {@link #MORE_CAUSES_MARKER}. */
    private static final int CAUSE_LOG_LIMIT = 4;

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final String UNAVAILABLE_MARKER = "[unavailable]";

    /** The library prefix of both server-relative URLs (l.10 and l.12). */
    public static final String SHARED_DOCUMENTS = "/Shared Documents/";

    /** The remote file name, fixed whatever the polled file is called (l.12). */
    public static final String TARGET_FILE_NAME = "contacts.csv";

    private final SharepointRestClient client;

    private final SharepointProperties props;

    /**
     * Creates the service.
     *
     * @param client the project's only SharePoint access path
     * @param props  the configuration holding {@code csvImportFolder}
     */
    public SharepointImportService(SharepointRestClient client, SharepointProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * Uploads one polled file to {@code /Shared Documents/<csvImportFolder>/contacts.csv}.
     *
     * <p>A new session is signed in for every file, as SharePoint Online cookies and form digests expire between
     * polls. {@code content} is the flow variable {@code fileContent} of l.8 and is sent as is, so SharePoint receives
     * the local file byte for byte.
     *
     * <p>When a step fails, one ERROR line {@code SharePoint step '<step>' failed: <reason>} names the step
     * ({@code sign-in}, {@code folder create} or {@code file add}) and a reason made of the HTTP status code of an
     * error response and the class names of the exception and its causes, plus the message of the configuration check
     * alone, as the class description sets out; no folder or file path is logged. The exception is then rethrown as
     * the same instance, so the later steps and the success line are skipped and the file trigger still logs the
     * failure and consumes the file.
     *
     * @param content the bytes read from the polled file
     * @throws RuntimeException the failure of the first step that failed, unchanged
     */
    public void importFile(byte[] content) {
        String folder = SHARED_DOCUMENTS + props.csvImportFolder();
        String file = folder + "/" + TARGET_FILE_NAME;
        // The flow declares no exception strategy. Each catch below departs from that only to log the failing step,
        // then rethrows the same exception: nothing is handled or retried here, so the failure still ends the import
        // and reaches the file trigger unchanged.
        SharepointRestClient.SharepointSession session;
        try {
            session = client.signIn();
        } catch (RuntimeException e) {
            log.error("SharePoint step 'sign-in' failed: {}", describe(e));
            throw e;
        }
        try {
            client.createFolder(session, folder);
        } catch (RuntimeException e) {
            log.error("SharePoint step 'folder create' failed: {}", describe(e));
            throw e;
        }
        try {
            client.addFile(session, file, content, true);
        } catch (RuntimeException e) {
            log.error("SharePoint step 'file add' failed: {}", describe(e));
            throw e;
        }
        log.info("Input file stored in the specified folder under the 'Shared Documents' folder");
    }

    /**
     * The loggable reason of a failed step, built from the step's exception without reading any text it carries other
     * than the configuration check's message.
     *
     * <ul>
     *   <li>An HTTP error status ({@link RestClientResponseException}): {@code HTTP <code> (<types>)}, for example
     *       {@code HTTP 400 (org.springframework.web.client.HttpClientErrorException$BadRequest)}. The numeric status
     *       code is the only part of the response used; its reason phrase, headers and body, which Spring also quotes
     *       in the exception's message, are left out.</li>
     *   <li>The configuration check ({@link SharepointProperties.InvalidConfigurationException}):
     *       {@code <types>: <message>}, because only {@link SharepointProperties#requireValid()} creates it, with a
     *       message naming the offending keys and their environment variables and no configured value.</li>
     *   <li>Any other exception, the client's own sign-in, timeout and connectivity failures included: {@code <types>}
     *       alone, because its message can quote a SOAP fault reason, a request URL or other upstream text.</li>
     * </ul>
     *
     * <p>{@code <types>} is the exception's fully qualified class name followed by that of each cause, separated by
     * {@code " <- "}, for example {@code org.springframework.web.client.ResourceAccessException <-
     * java.net.ConnectException}. The walk stops at a cause already named, marked {@code [circular]}, and after
     * {@value #CAUSE_LOG_LIMIT} causes, marked {@code ...}; a status code or cause that cannot be read is written
     * {@code [unavailable]}. Every part is fixed text, a status number or a class name, so the line stays single and
     * bounded. Describing a failure never throws, so the step's own exception is always the one rethrown.
     */
    private static String describe(RuntimeException failure) {
        String types = typeNames(failure);
        if (failure instanceof RestClientResponseException response) {
            return "HTTP " + statusCode(response) + " (" + types + ")";
        }
        if (failure instanceof SharepointProperties.InvalidConfigurationException invalidConfiguration) {
            return types + ": " + invalidConfiguration.getMessage();
        }
        return types;
    }

    /** The numeric status code of an error response, or {@code [unavailable]} when it cannot be read. */
    private static String statusCode(RestClientResponseException failure) {
        try {
            return Integer.toString(failure.getStatusCode().value());
        } catch (RuntimeException unreadable) {
            // An overridden accessor threw or returned null; the code is left out rather than guessed.
            return UNAVAILABLE_MARKER;
        }
    }

    /**
     * The class name of {@code failure}, then the class name of each cause, separated by {@link #CAUSE_SEPARATOR}.
     * Only {@link Throwable#getCause()} is called on any exception, and each exception is tracked by identity, so an
     * overridden {@code equals} or {@code hashCode} is never run either.
     */
    private static String typeNames(Throwable failure) {
        StringBuilder out = new StringBuilder(failure.getClass().getName());
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(failure);
        Throwable current = failure;
        for (int causes = 0; ; causes++) {
            Throwable cause;
            try {
                cause = current.getCause();
            } catch (RuntimeException unreadable) {
                return out.append(CAUSE_SEPARATOR).append(UNAVAILABLE_MARKER).toString();
            }
            if (cause == null) {
                return out.toString();
            }
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                return out.append(MORE_CAUSES_MARKER).toString();
            }
            if (!named.add(cause)) {
                return out.append(CIRCULAR_CAUSE_MARKER).toString();
            }
            out.append(cause.getClass().getName());
            current = cause;
        }
    }
}
