/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SharePoint Online connection settings and the target import folder.
 *
 * <p>Replaces two parts of the original flow {@code importing-a-csv-file-into-ms-sharepoint.xml}: the global
 * element {@code <sharepoint:online-connection-config ... password="${Sharepoint.Password}"
 * siteUrl="${Sharepoint.SiteUrl}" username="${Sharepoint.Username}"/>} (line 4), and the
 * {@code ${csvImportFolder}} placeholder of {@code folder-create} (line 10) and {@code file-add} (line 12).
 *
 * <p>The original key names are kept unchanged, as the original test sets them. {@code application.yml} maps
 * each one to an environment variable and supplies no value:
 * <ul>
 *   <li>{@code Sharepoint.Username} &larr; {@code SHAREPOINT_USERNAME}</li>
 *   <li>{@code Sharepoint.Password} &larr; {@code SHAREPOINT_PASSWORD}</li>
 *   <li>{@code Sharepoint.SiteUrl} &larr; {@code SHAREPOINT_SITEURL}</li>
 *   <li>{@code csvImportFolder} &larr; {@code CSVIMPORTFOLDER}</li>
 * </ul>
 * {@code sharepoint-online.sts-url} is the Microsoft security token service of SharePoint Online's claims
 * sign-in; it defaults to the public endpoint and is overridable so tests can point it at a stub.
 *
 * <p>The values are bound with {@code @Value} rather than {@code @ConfigurationProperties}, because the
 * original keys are dotted and mixed-case ({@code Sharepoint.SiteUrl}) and must be read under exactly those
 * names.
 *
 * <p>Startup fails fast, as the original application failed to deploy on an unresolved placeholder: when any of
 * the four keys above is blank, the constructor throws one {@link IllegalStateException} naming every blank key
 * with its environment variable. The message never carries a configured value.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code client/SharepointRestClient} reads {@link #username()}, {@link #password()},
 *       {@link #siteUrl()} and {@link #stsUrl()} to sign in and to address the site's REST API.</li>
 *   <li>{@code service/SharepointImportService} reads {@link #csvImportFolder()} to build
 *       {@code /Shared Documents/<csvImportFolder>} and {@code /Shared Documents/<csvImportFolder>/contacts.csv}.</li>
 * </ul>
 */
@Component
public final class SharepointProperties {

    private static final String USERNAME_KEY = "Sharepoint.Username (SHAREPOINT_USERNAME)";
    private static final String PASSWORD_KEY = "Sharepoint.Password (SHAREPOINT_PASSWORD)";
    private static final String SITE_URL_KEY = "Sharepoint.SiteUrl (SHAREPOINT_SITEURL)";
    private static final String CSV_IMPORT_FOLDER_KEY = "csvImportFolder (CSVIMPORTFOLDER)";

    private final String username;
    private final String password;
    private final String siteUrl;
    private final String csvImportFolder;
    private final String stsUrl;

    /**
     * Validates and stores the settings.
     *
     * @param username        {@code Sharepoint.Username}, the SharePoint Online account
     * @param password        {@code Sharepoint.Password}, stored exactly as supplied
     * @param siteUrl         {@code Sharepoint.SiteUrl}, stored with every trailing {@code /} removed
     * @param csvImportFolder {@code csvImportFolder}, the folder under {@code Shared Documents}
     * @param stsUrl          {@code sharepoint-online.sts-url}, stored as given and not validated
     * @throws IllegalStateException when any of the first four values is {@code null} or blank
     */
    public SharepointProperties(
            @Value("${Sharepoint.Username:}") String username,
            @Value("${Sharepoint.Password:}") String password,
            @Value("${Sharepoint.SiteUrl:}") String siteUrl,
            @Value("${csvImportFolder:}") String csvImportFolder,
            @Value("${sharepoint-online.sts-url:https://login.microsoftonline.com/extSTS.srf}") String stsUrl) {
        List<String> missing = new ArrayList<>();
        if (isBlank(username)) {
            missing.add(USERNAME_KEY);
        }
        if (isBlank(password)) {
            missing.add(PASSWORD_KEY);
        }
        if (isBlank(siteUrl)) {
            missing.add(SITE_URL_KEY);
        }
        if (isBlank(csvImportFolder)) {
            missing.add(CSV_IMPORT_FOLDER_KEY);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required SharePoint configuration: " + String.join(", ", missing)
                    + ". # TODO: supply them in application.yml or as environment variables.");
        }
        this.username = username;
        this.password = password;
        this.siteUrl = stripTrailingSlashes(siteUrl);
        this.csvImportFolder = csvImportFolder;
        this.stsUrl = stsUrl;
    }

    /** The SharePoint Online account used for the claims sign-in. */
    public String username() {
        return username;
    }

    /** The account password, exactly as configured. */
    public String password() {
        return password;
    }

    /** The site URL without a trailing {@code /}, so {@code siteUrl() + "/_api/..."} never doubles the slash. */
    public String siteUrl() {
        return siteUrl;
    }

    /** The folder under {@code Shared Documents} that receives {@code contacts.csv}. */
    public String csvImportFolder() {
        return csvImportFolder;
    }

    /** The security token service endpoint of the claims sign-in. */
    public String stsUrl() {
        return stsUrl;
    }

    /**
     * Describes the settings with the password masked. The mask is written whether or not a password is set, so
     * the output reveals neither the secret nor its length.
     */
    @Override
    public String toString() {
        return "SharepointProperties[username=" + username + ", password=****, siteUrl=" + siteUrl
                + ", csvImportFolder=" + csvImportFolder + ", stsUrl=" + stsUrl + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
