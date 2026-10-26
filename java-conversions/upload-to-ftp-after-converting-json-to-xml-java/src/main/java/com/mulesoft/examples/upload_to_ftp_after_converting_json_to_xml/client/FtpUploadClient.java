/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;

/**
 * FTP outbound adapter that stores the converted XML on the configured FTP server, replacing the
 * {@code ftp:connector} (line 3) and {@code ftp:outbound-endpoint} (line 28) of the original flow
 * {@code upload-to-ftp.xml}. The transfer is a plain FTP {@code STOR} over Apache Commons Net, the library
 * AAP 0.6.4 names for this example.
 *
 * <p>The sequence mirrors Mule 3.8's default FTP connector ({@code binary} and {@code passive} both true):
 * connect and require a positive-completion greeting, log in, switch to binary file type, apply the
 * endpoint's response timeout to the data connection, enter local passive mode, change to the configured
 * working directory, and store the file under the given name, overwriting any existing file. The directory
 * is never created: as in Mule, a missing directory fails the upload.
 *
 * <p>Each call opens a new connection and closes it before returning; no client is pooled or shared, and
 * nothing is retried. Host, port, directory and credentials are read from the {@code ftp.*} keys bound by
 * {@link FtpProperties} at call time, so the application starts with them unset; this class holds no literal
 * host or credential. Failures propagate to the caller with messages that never contain the password, since
 * the platform-default error handler exposes them in the response body.
 */
@Component
public class FtpUploadClient {

    /** Name the file is stored under: the original endpoint's {@code outputPattern="muleExample.xml"}. */
    public static final String OUTPUT_PATTERN = "muleExample.xml";

    /**
     * Connect, control-reply and data-transfer timeout: the original endpoint's
     * {@code responseTimeout="10000"}.
     */
    private static final int TIMEOUT_MILLIS = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(FtpUploadClient.class);

    private final FtpProperties properties;

    public FtpUploadClient(FtpProperties properties) {
        this.properties = properties;
    }

    /**
     * Stores {@code content} as {@code fileName} in the configured remote directory.
     *
     * @param fileName name of the remote file, normally {@link #OUTPUT_PATTERN}
     * @param content  bytes to store, transferred unchanged in binary mode
     * @throws IllegalStateException if the host is not configured or the server rejects the greeting, the
     *                               login, the file type, the directory change or the store
     * @throws UncheckedIOException  if the connection or transfer fails (refused, unknown host, timeout,
     *                               reset)
     */
    public void upload(String fileName, byte[] content) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(content, "content");

        String host = properties.getHost();
        int port = properties.getPort();
        String path = properties.getPath();
        String user = properties.getUser();
        String password = properties.getPassword();

        if (host == null || host.isBlank()) {
            throw new IllegalStateException("FTP host is not configured (ftp.host)");
        }
        String target = host + ":" + port;

        FTPClient ftp = createClient();
        boolean loggedIn = false;
        try {
            ftp.setConnectTimeout(TIMEOUT_MILLIS);
            ftp.setDefaultTimeout(TIMEOUT_MILLIS);
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IllegalStateException("FTP connect to " + target + " failed: " + reply(ftp));
            }
            ftp.setSoTimeout(TIMEOUT_MILLIS);
            ftp.setDataTimeout(Duration.ofMillis(TIMEOUT_MILLIS));

            if (!ftp.login(user, password)) {
                throw new IllegalStateException("FTP login to " + target + " failed: " + reply(ftp));
            }
            loggedIn = true;

            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IllegalStateException(
                        "FTP set binary file type on " + target + " failed: " + reply(ftp));
            }
            ftp.enterLocalPassiveMode();

            if (!ftp.changeWorkingDirectory(path)) {
                throw new IllegalStateException("FTP change working directory to " + path + " on " + target
                        + " failed: " + reply(ftp));
            }
            if (!ftp.storeFile(fileName, new ByteArrayInputStream(content))) {
                throw new IllegalStateException("FTP store " + fileName + " in " + path + " on " + target
                        + " failed: " + reply(ftp));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("FTP upload to " + target + " failed: " + e.getMessage(), e);
        } finally {
            // Cleanup failures are logged only: they must neither mask the upload's own exception nor turn
            // a completed store into a failure.
            if (loggedIn) {
                try {
                    ftp.logout();
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Ignoring FTP logout failure on {}: {}", target, e.toString());
                }
            }
            if (ftp.isConnected()) {
                try {
                    ftp.disconnect();
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Ignoring FTP disconnect failure on {}: {}", target, e.toString());
                }
            }
        }
    }

    /** Creates the client for one upload; tests override it to observe the protocol calls. */
    protected FTPClient createClient() {
        return new FTPClient();
    }

    private static String reply(FTPClient ftp) {
        String replyString = ftp.getReplyString();
        return replyString == null ? "" : replyString.trim();
    }
}
