/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * <p>The original declares no exception strategy, so no failure is caught here: it propagates unchanged to the file
 * trigger, whose default handling logs it and still consumes the file, and every later step is skipped.
 */
@Service
public class SharepointImportService {

    private static final Logger log = LoggerFactory.getLogger(SharepointImportService.class);

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
     * @param content the bytes read from the polled file
     */
    public void importFile(byte[] content) {
        String folder = SHARED_DOCUMENTS + props.csvImportFolder();
        SharepointRestClient.SharepointSession session = client.signIn();
        client.createFolder(session, folder);
        client.addFile(session, folder + "/" + TARGET_FILE_NAME, content, true);
        log.info("Input file stored in the specified folder under the 'Shared Documents' folder");
    }
}
