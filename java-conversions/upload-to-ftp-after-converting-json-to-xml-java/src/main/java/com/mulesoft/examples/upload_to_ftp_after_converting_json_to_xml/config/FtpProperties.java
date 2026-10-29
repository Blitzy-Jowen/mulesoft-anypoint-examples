/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings of the FTP server the converted XML is uploaded to, bound from the {@code ftp.*}
 * keys of {@code application.yml}.
 *
 * <p>Replaces the literal {@code host}, {@code port}, {@code path}, {@code user} and {@code password}
 * attributes of the {@code ftp:outbound-endpoint} in the original flow {@code upload-to-ftp.xml} (line 28)
 * with the keys {@code ftp.host}, {@code ftp.port}, {@code ftp.path}, {@code ftp.user} and
 * {@code ftp.password}, the names the original test already read. Only the non-secret values the original
 * fixed have defaults here: port {@code 21} and remote directory {@code /Upload/}. The host and the
 * credentials have none and stay unset until supplied.
 *
 * <p>Nothing is validated at bind time, so the application starts with blank values; the FTP client
 * rejects a blank host or user, or an empty password, when an upload is attempted. The endpoint's output
 * file name and response timeout are constants of that client, not settings of this class.
 *
 * <p>Registered by {@code @EnableConfigurationProperties(FtpProperties.class)} on the application class. It
 * carries no stereotype annotation, because one would register a second bean of this type.
 */
@ConfigurationProperties(prefix = "ftp")
public class FtpProperties {

    /** Original fixed port of the FTP outbound endpoint. */
    private static final int DEFAULT_PORT = 21;

    /** Original fixed remote directory of the FTP outbound endpoint. */
    private static final String DEFAULT_PATH = "/Upload/";

    /** FTP server host name or address; no default. */
    private String host;

    /** FTP server control port. */
    private int port = DEFAULT_PORT;

    /** Remote directory the file is stored in. */
    private String path = DEFAULT_PATH;

    /** Service-account login name; no default. */
    private String user;

    /** Service-account password; no default. */
    private String password;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** Renders every setting except the password, which appears only as a mask when it is set. */
    @Override
    public String toString() {
        String maskedPassword = (password == null || password.isEmpty()) ? password : "****";
        return "FtpProperties{host=" + host + ", port=" + port + ", path=" + path + ", user=" + user
                + ", password=" + maskedPassword + '}';
    }
}
