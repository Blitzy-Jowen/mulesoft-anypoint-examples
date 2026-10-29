/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import java.net.URI;
import java.net.URISyntaxException;
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
 * <p>Binding never fails: the constructor stores each value as supplied, so the application starts with the blank
 * placeholders {@code application.yml} ships. {@link #requireValid()} checks the values, and
 * {@code client/SharepointRestClient} calls it before its first SharePoint request: when any of the four keys above
 * is blank, or {@code Sharepoint.SiteUrl} is set but, with its trailing {@code /} removed, is not an absolute
 * {@code http} or {@code https} URL with a host and without user info, query or fragment, it throws one
 * {@link InvalidConfigurationException}, an {@link IllegalStateException}, naming every blank key and the invalid
 * site URL key with its environment variable, and no request is sent. Plain {@code http} is accepted for any host, so
 * tests can address a local stub. The message never carries a configured value.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code client/SharepointRestClient} calls {@link #requireValid()} first in each operation, then reads
 *       {@link #username()}, {@link #password()}, {@link #siteUrl()} and {@link #stsUrl()} to sign in and to
 *       address the site's REST API.</li>
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
    /** {@code Sharepoint.SiteUrl} as bound, so {@link #requireValid()} tells a blank key from an invalid URL. */
    private final String configuredSiteUrl;
    private final String siteUrl;
    private final String csvImportFolder;
    private final String stsUrl;

    /**
     * Stores the settings as bound and never throws, so a context whose placeholders are still blank starts;
     * {@link #requireValid()} checks them before the first SharePoint request.
     *
     * @param username        {@code Sharepoint.Username}, the SharePoint Online account, stored as supplied
     * @param password        {@code Sharepoint.Password}, stored exactly as supplied
     * @param siteUrl         {@code Sharepoint.SiteUrl}, stored with every trailing {@code /} removed; {@code null}
     *                        stays {@code null}
     * @param csvImportFolder {@code csvImportFolder}, the folder under {@code Shared Documents}, stored as supplied
     * @param stsUrl          {@code sharepoint-online.sts-url}, stored as given and not validated
     */
    public SharepointProperties(
            @Value("${Sharepoint.Username:}") String username,
            @Value("${Sharepoint.Password:}") String password,
            @Value("${Sharepoint.SiteUrl:}") String siteUrl,
            @Value("${csvImportFolder:}") String csvImportFolder,
            @Value("${sharepoint-online.sts-url:https://login.microsoftonline.com/extSTS.srf}") String stsUrl) {
        this.username = username;
        this.password = password;
        this.configuredSiteUrl = siteUrl;
        this.siteUrl = siteUrl == null ? null : stripTrailingSlashes(siteUrl);
        this.csvImportFolder = csvImportFolder;
        this.stsUrl = stsUrl;
    }

    /**
     * Checks the settings before SharePoint is called. {@code client/SharepointRestClient} runs it first in each
     * operation, so blank placeholders or an unusable site URL fail there, naming their keys, before any request.
     *
     * @throws InvalidConfigurationException when {@code Sharepoint.Username}, {@code Sharepoint.Password},
     *                                       {@code Sharepoint.SiteUrl} or {@code csvImportFolder} is {@code null} or
     *                                       blank, or when {@code Sharepoint.SiteUrl} is set but, with its trailing
     *                                       {@code /} removed, is not an absolute {@code http} or {@code https} URL
     *                                       with a host and without user info, query or fragment; one exception names
     *                                       every such key with its environment variable and none of the configured
     *                                       values
     */
    public void requireValid() {
        List<String> missing = new ArrayList<>();
        if (isBlank(username)) {
            missing.add(USERNAME_KEY);
        }
        if (isBlank(password)) {
            missing.add(PASSWORD_KEY);
        }
        if (isBlank(configuredSiteUrl)) {
            missing.add(SITE_URL_KEY);
        }
        if (isBlank(csvImportFolder)) {
            missing.add(CSV_IMPORT_FOLDER_KEY);
        }
        boolean invalidSiteUrl = !isBlank(configuredSiteUrl) && !isAbsoluteHttpUrl(siteUrl);
        if (!missing.isEmpty() || invalidSiteUrl) {
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("Missing required SharePoint configuration: " + String.join(", ", missing) + ".");
            }
            if (invalidSiteUrl) {
                problems.add("Invalid SharePoint configuration: " + SITE_URL_KEY + " must be an absolute http or https"
                        + " URL with a host and without user info, query or fragment.");
            }
            throw new InvalidConfigurationException(String.join(" ", problems)
                    + " # TODO: supply them in application.yml or as environment variables.");
        }
    }

    /**
     * The failure of {@link #requireValid()}. Its message is built only from the constant key and environment
     * variable names above, never from a configured value, so it is the one exception message
     * {@code service/SharepointImportService} writes to its ERROR line. It stays an {@link IllegalStateException}, the
     * type callers of {@link #requireValid()} expect.
     */
    public static final class InvalidConfigurationException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private InvalidConfigurationException(String message) {
            super(message);
        }
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

    /**
     * Checks the site URL after its trailing {@code /} characters were removed.
     *
     * @param url the normalized site URL, never {@code null}
     * @return whether {@code url} is an absolute {@code http} or {@code https} URL (scheme in any case) with a host
     *         and without user info, query or fragment; a path is allowed, and plain {@code http} is accepted for
     *         any host
     */
    private static boolean isAbsoluteHttpUrl(String url) {
        if (url.isEmpty()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // Not rethrown or chained: its message repeats the configured value, which the caller's error omits.
            return false;
        }
        String scheme = uri.getScheme();
        return uri.isAbsolute()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }
}
