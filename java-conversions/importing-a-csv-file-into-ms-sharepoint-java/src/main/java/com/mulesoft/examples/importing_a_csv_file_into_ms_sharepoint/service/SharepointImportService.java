/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * ERROR with the step ({@code sign-in}, {@code folder create} or {@code file add}), its target URL, the HTTP status
 * and SharePoint's or the client's secret-free reason, as Mule's default exception strategy logged the failing
 * processor and its message. The same exception is then rethrown unchanged to the file trigger, whose default
 * handling logs it again and still consumes the file, and every later step is skipped. The logged reason carries
 * no raw response body, cookie, form digest or password, the exception is never handed to the logger, and the line
 * stays single and bounded.
 */
@Service
public class SharepointImportService {

    private static final Logger log = LoggerFactory.getLogger(SharepointImportService.class);

    /** Longest logged reason or target, in characters, before {@link #TRUNCATION_MARKER} is appended. */
    private static final int LOG_TEXT_LIMIT = 1000;

    private static final String TRUNCATION_MARKER = "...[truncated]";

    private static final int LINE_SEPARATOR = 0x2028;

    private static final int PARAGRAPH_SEPARATOR = 0x2029;

    /** Reads SharePoint's OData error bodies; never used to log or rethrow what it parses. */
    private static final ObjectMapper ODATA_ERROR_READER = new ObjectMapper();

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
     * <p>When a step fails, one ERROR line names the step, its target ({@code /Shared Documents/<csvImportFolder>}
     * for {@code folder create}, {@code .../contacts.csv} for {@code file add}) and the reason: for an HTTP error
     * status, the status code, its reason phrase and SharePoint's OData error message; for the client's own
     * failures, their secret-free message; for any other exception, its class name alone. The exception is then
     * rethrown as the same instance, so the later steps and the success line are skipped and the file trigger
     * still logs the failure and consumes the file.
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
            log.error("SharePoint step 'folder create' failed for {}: {}", oneBoundedLine(folder), describe(e));
            throw e;
        }
        try {
            client.addFile(session, file, content, true);
        } catch (RuntimeException e) {
            log.error("SharePoint step 'file add' failed for {}: {}", oneBoundedLine(file), describe(e));
            throw e;
        }
        log.info("Input file stored in the specified folder under the 'Shared Documents' folder");
    }

    /**
     * The loggable reason of a failed step, on one bounded line.
     *
     * <ul>
     *   <li>An HTTP error status ({@link RestClientResponseException}): {@code HTTP <code> <reason phrase>}, followed
     *       by {@code : <text>} when the response body is SharePoint's OData error. Neither the exception's message
     *       nor the body is used otherwise, since Spring quotes the whole body in that message.</li>
     *   <li>{@link IllegalStateException}, {@link IllegalArgumentException} and any other {@link RestClientException},
     *       a timeout or connectivity failure included: {@code <type>: <message>}, or the type alone without a
     *       message. {@code SharepointRestClient} and {@code SharepointProperties} keep these messages free of
     *       secrets: they name the failing sign-in step or SOAP fault reason, the missing configuration keys, or the
     *       request URL without its query and the I/O failure.</li>
     *   <li>Any other exception: its fully qualified class name, as its message is not known to be free of
     *       secrets.</li>
     * </ul>
     *
     * <p>Describing a failure never throws, so the step's own exception is always the one rethrown.
     */
    private static String describe(RuntimeException failure) {
        String className = failure.getClass().getName();
        try {
            if (failure instanceof RestClientResponseException response) {
                return oneBoundedLine(httpReason(response));
            }
            if (failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                    || failure instanceof RestClientException) {
                String message = failure.getMessage();
                String type = typeName(failure);
                return oneBoundedLine(message == null ? type : type + ": " + message);
            }
            return oneBoundedLine(className);
        } catch (RuntimeException unreadable) {
            // An overridden accessor of the failure threw; the failure is then named by its class alone.
            return oneBoundedLine(className);
        }
    }

    /** {@code HTTP <code> [<reason phrase>][: <OData error message>]}. */
    private static String httpReason(RestClientResponseException failure) {
        StringBuilder reason = new StringBuilder("HTTP ").append(failure.getStatusCode().value());
        String statusText = failure.getStatusText();
        if (statusText != null && !statusText.isBlank()) {
            reason.append(' ').append(statusText.strip());
        }
        odataErrorMessage(failure.getResponseBodyAsByteArray()).ifPresent(text -> reason.append(": ").append(text));
        return reason.toString();
    }

    /**
     * The message of a SharePoint OData error body: {@code error.message.value} (verbose JSON),
     * {@code odata.error.message.value} (JSON light) or {@code error.message} (OData v4). Only a non-blank JSON string
     * counts. An empty body, a body that is not JSON or JSON without such a message yields nothing; the parser's own
     * message is dropped, as it can quote the body.
     */
    private static Optional<String> odataErrorMessage(byte[] body) {
        JsonNode root;
        try {
            // An empty or blank body reads as a missing node, never as null; a null body is rejected as an argument.
            root = ODATA_ERROR_READER.readTree(body);
        } catch (IOException | RuntimeException notJson) {
            return Optional.empty();
        }
        List<JsonNode> candidates = List.of(
                root.path("error").path("message").path("value"),
                root.path("odata.error").path("message").path("value"),
                root.path("error").path("message"));
        for (JsonNode candidate : candidates) {
            if (candidate.isTextual() && !candidate.textValue().isBlank()) {
                return Optional.of(candidate.textValue().strip());
            }
        }
        return Optional.empty();
    }

    /** The simple class name, or the full name for an anonymous class, which has no simple name. */
    private static String typeName(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * Writes every ISO control character (CR, LF, TAB and the rest) and the Unicode line and paragraph separators as a
     * space, so the text cannot break or forge a log line, and cuts it after {@value #LOG_TEXT_LIMIT} characters,
     * never inside a surrogate pair, with {@value #TRUNCATION_MARKER} appended.
     */
    private static String oneBoundedLine(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), LOG_TEXT_LIMIT) + TRUNCATION_MARKER.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int units = Character.charCount(codePoint);
            if (out.length() + units > LOG_TEXT_LIMIT) {
                return out.append(TRUNCATION_MARKER).toString();
            }
            if (Character.isISOControl(codePoint) || codePoint == LINE_SEPARATOR || codePoint == PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else {
                out.appendCodePoint(codePoint);
            }
            i += units;
        }
        return out.toString();
    }
}
