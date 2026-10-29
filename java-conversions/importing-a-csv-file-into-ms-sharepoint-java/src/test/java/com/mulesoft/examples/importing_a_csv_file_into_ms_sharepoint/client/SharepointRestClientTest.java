/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchesXPathWithSubMatcher;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client.SharepointRestClient.SharepointSession;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link SharepointRestClient} against WireMock standing in for both the SharePoint site and the security
 * token service, so the tests need no credentials and no tenant.
 *
 * <p>The client replaces the Microsoft SharePoint 2013 connector of the original flow
 * {@code importing-a-csv-file-into-ms-sharepoint.xml}: the online connection (line 4) is the claims sign-in of
 * {@link SharepointRestClient#signIn()}, {@code folder-create} of {@code /Shared Documents/${csvImportFolder}}
 * (line 10) is {@link SharepointRestClient#createFolder(SharepointSession, String)}, and {@code file-add} of
 * {@code /Shared Documents/${csvImportFolder}/contacts.csv} with {@code overwrite="true"} (line 12) is
 * {@link SharepointRestClient#addFile(SharepointSession, String, byte[], boolean)}. The original integration test
 * compares the uploaded content with the local {@code contacts.csv}, so the upload tests assert the request body
 * byte for byte.
 *
 * <p>As a client test of AAP 0.8.2 it runs under surefire with WireMock stubs only. The failure tests assert the
 * exact exception type each failure surfaces as, and that a connectivity failure is sent once and never retried
 * (AAP 0.4.8). The log test checks that no credential, token, cookie or form digest reaches a log message.
 */
class SharepointRestClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String USERNAME = "tester@example.com";
    /** Holds both XML metacharacters the security token service envelope must escape. */
    private static final String PASSWORD = "p<&ss";
    private static final String ESCAPED_PASSWORD = "p&lt;&amp;ss";
    private static final String TOKEN = "t=FAKE-TOKEN-123";
    private static final String RTFA = "RTFA123";
    private static final String FEDAUTH = "FEDAUTH456";
    private static final String DIGEST = "0xDIGEST,01 Jan 2024";
    private static final String COOKIE_HEADER = "rtFa=" + RTFA + "; FedAuth=" + FEDAUTH;
    private static final String CSV_IMPORT_FOLDER = "test-folder";
    private static final String FOLDER = "/Shared Documents/" + CSV_IMPORT_FOLDER;

    private static final String STS_PATH = "/extSTS.srf";
    private static final String WSIGNIN_URL = "/_forms/default.aspx?wa=wsignin1.0";
    private static final String LANDING_URL = "/_layouts/15/landing.aspx";
    private static final String CONTEXTINFO_URL = "/_api/contextinfo";
    private static final String FOLDERS_URL = "/_api/web/folders";
    private static final String FILES_ADD_URL =
            "/_api/web/GetFolderByServerRelativeUrl('/Shared%20Documents/test-folder')"
                    + "/Files/add(url='contacts.csv',overwrite=true)";

    private static final String WSA_NS = "http://www.w3.org/2005/08/addressing";
    private static final String WSP_NS = "http://schemas.xmlsoap.org/ws/2004/09/policy";
    private static final String WST_NS = "http://schemas.xmlsoap.org/ws/2005/02/trust";

    private static final String SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8";
    private static final String ODATA_CONTENT_TYPE = "application/json;odata=verbose;charset=utf-8";

    /** The values no log message may carry: the raw and the XML-escaped password, the token, cookies and digest. */
    private static final String[] SECRETS = {PASSWORD, ESCAPED_PASSWORD, "FAKE-TOKEN-123", RTFA, FEDAUTH, "0xDIGEST"};

    private SharepointRestClient client;

    @BeforeEach
    void setUp() {
        SharepointProperties props = new SharepointProperties(
                USERNAME, PASSWORD, wm.baseUrl(), CSV_IMPORT_FOLDER, wm.baseUrl() + STS_PATH);
        client = new SharepointRestClient(props, Duration.ofSeconds(2));
    }

    @Test
    void signInSendsEscapedCredentialsToStsAndBuildsSession() {
        stubSignInSuccess();

        SharepointSession session = client.signIn();

        wm.verify(1, postRequestedFor(urlEqualTo(STS_PATH))
                .withHeader("Content-Type", matching("application/soap\\+xml;\\s*charset=(?i)utf-8"))
                .withRequestBody(containing(ESCAPED_PASSWORD))
                .withRequestBody(notContaining(PASSWORD))
                .withRequestBody(containing(USERNAME))
                // The site address the token is requested for, which the STS URL in a:To also contains.
                .withRequestBody(matchesXPathWithSubMatcher(
                                "//wsp:AppliesTo/wsa:EndpointReference/wsa:Address/text()", equalTo(wm.baseUrl()))
                        .withXPathNamespace("wsp", WSP_NS)
                        .withXPathNamespace("wsa", WSA_NS)));
        wm.verify(1, postRequestedFor(urlEqualTo(WSIGNIN_URL)).withRequestBody(equalTo(TOKEN)));
        wm.verify(1, postRequestedFor(urlEqualTo(CONTEXTINFO_URL)).withHeader("Cookie", equalTo(COOKIE_HEADER)));
        // The wsignin1.0 redirect is never followed: its Set-Cookie headers are the sign-in result.
        wm.verify(0, anyRequestedFor(urlEqualTo(LANDING_URL)));

        assertThat(session.cookieHeader()).isEqualTo(COOKIE_HEADER);
        assertThat(session.formDigest()).isEqualTo(DIGEST);
        assertThat(session.toString()).doesNotContain(RTFA, FEDAUTH, "0xDIGEST");
    }

    @Test
    void createFolderPostsSpFolderMetadataWithCookieAndDigest() {
        stubFolders(aResponse().withStatus(201)
                .withHeader("Content-Type", ODATA_CONTENT_TYPE)
                .withBody("{\"d\":{\"Name\":\"test-folder\","
                        + "\"ServerRelativeUrl\":\"/Shared Documents/test-folder\"}}"));

        client.createFolder(session(), FOLDER);

        wm.verify(1, postRequestedFor(urlEqualTo(FOLDERS_URL))
                .withHeader("Cookie", equalTo(COOKIE_HEADER))
                .withHeader("X-RequestDigest", equalTo(DIGEST))
                .withRequestBody(equalToJson("{\"__metadata\":{\"type\":\"SP.Folder\"},"
                        + "\"ServerRelativeUrl\":\"/Shared Documents/test-folder\"}")));
    }

    @Test
    void addFileUploadsClasspathContactsCsvBytesUnchanged() throws IOException {
        byte[] bytes = classpathContactsCsv();
        assertThat(new String(bytes, UTF_8)).startsWith("firstname;surname;phone;email");
        stubFilesAdd(FILES_ADD_URL);

        client.addFile(session(), FOLDER + "/contacts.csv", bytes, true);

        wm.verify(1, postRequestedFor(urlEqualTo(FILES_ADD_URL))
                .withHeader("Content-Type", equalTo("application/octet-stream"))
                .withHeader("Cookie", equalTo(COOKIE_HEADER))
                .withHeader("X-RequestDigest", equalTo(DIGEST))
                .withRequestBody(binaryEqualTo(bytes)));
    }

    @Test
    void addFileKeepsNonAsciiBytesUnchanged() {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes("José;Müller;€\n".getBytes(UTF_8));
        // Bytes that are not valid UTF-8, so any charset decoding or re-encoding would change them.
        content.writeBytes(new byte[] {(byte) 0xFF, 0x00, (byte) 0xC3});
        byte[] bytes = content.toByteArray();
        stubFilesAdd(FILES_ADD_URL);

        client.addFile(session(), FOLDER + "/contacts.csv", bytes, true);

        wm.verify(1, postRequestedFor(urlEqualTo(FILES_ADD_URL)).withRequestBody(binaryEqualTo(bytes)));
    }

    @Test
    void addFileDoublesSingleQuoteInFolderLiteral() {
        String url = "/_api/web/GetFolderByServerRelativeUrl('/Shared%20Documents/O''Brien')"
                + "/Files/add(url='contacts.csv',overwrite=true)";
        stubFilesAdd(url);

        client.addFile(session(), "/Shared Documents/O'Brien/contacts.csv", "a;b\n".getBytes(UTF_8), true);

        wm.verify(1, postRequestedFor(urlEqualTo(url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contacts.csv", "/contacts.csv", "/Shared Documents/test-folder/"})
    void addFileRejectsUrlWithoutFolderAndNameBeforeAnyRequest(String fileServerRelativeUrl) {
        assertThatThrownBy(() -> client.addFile(session(), fileServerRelativeUrl, new byte[] {1}, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<folder>/<name>");

        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void signInFailsWithStsFaultText() {
        stubSts(aResponse().withStatus(200)
                .withHeader("Content-Type", SOAP_CONTENT_TYPE)
                .withBody(stsFaultBody()));

        assertThatThrownBy(client::signIn)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication Failure")
                .hasMessageNotContaining(PASSWORD);

        wm.verify(0, postRequestedFor(urlEqualTo(WSIGNIN_URL)));
    }

    @Test
    void signInFailsWhenFedAuthCookieMissing() {
        stubStsToken();
        stubWsignin("rtFa=" + RTFA + "; path=/");
        stubLanding();
        stubContextInfoDigest();

        assertThatThrownBy(client::signIn)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FedAuth");

        wm.verify(0, postRequestedFor(urlEqualTo(CONTEXTINFO_URL)));
    }

    @Test
    void signInPropagatesUnauthorizedFromContextInfo() {
        stubStsToken();
        stubWsignin("rtFa=" + RTFA + "; path=/", "FedAuth=" + FEDAUTH + "; path=/");
        stubLanding();
        wm.stubFor(post(urlEqualTo(CONTEXTINFO_URL)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(client::signIn).isInstanceOf(HttpClientErrorException.Unauthorized.class);

        wm.verify(1, postRequestedFor(urlEqualTo(CONTEXTINFO_URL)));
    }

    @Test
    void createFolderPropagatesInternalServerError() {
        stubFolders(aResponse().withStatus(500));

        assertThatThrownBy(() -> client.createFolder(session(), FOLDER))
                .isInstanceOf(HttpServerErrorException.InternalServerError.class);

        wm.verify(1, postRequestedFor(urlEqualTo(FOLDERS_URL)));
    }

    @Test
    void signInRaisesResourceAccessOnConnectionRefused() throws IOException {
        try (Socket reserved = reserveRefusingPort()) {
            String closedBase = "http://127.0.0.1:" + reserved.getLocalPort();
            SharepointRestClient unreachable = new SharepointRestClient(
                    new SharepointProperties(USERNAME, PASSWORD, closedBase, CSV_IMPORT_FOLDER, closedBase + STS_PATH),
                    Duration.ofSeconds(2));

            assertThatThrownBy(unreachable::signIn)
                    .isInstanceOf(ResourceAccessException.class)
                    .satisfies(failure -> assertThat(Stream.iterate(failure, Objects::nonNull, Throwable::getCause))
                            .anyMatch(ConnectException.class::isInstance));
        }
    }

    @Test
    void signInRaisesResourceAccessOnConnectionResetWithoutRetry() {
        stubSts(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        assertThatThrownBy(client::signIn).isInstanceOf(ResourceAccessException.class);

        wm.verify(1, postRequestedFor(urlEqualTo(STS_PATH)));
        wm.verify(0, postRequestedFor(urlEqualTo(WSIGNIN_URL)));
    }

    @Test
    void happyPathLogsNoSecrets() throws IOException {
        stubSignInSuccess();
        stubFolders(aResponse().withStatus(201));
        stubFilesAdd(FILES_ADD_URL);
        byte[] bytes = classpathContactsCsv();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger clientLogger = context.getLogger(SharepointRestClient.class);
        Level rootLevel = root.getLevel();
        Level clientLevel = clientLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        // ROOT is pinned to INFO, the Spring Boot default, whatever level an earlier test in the same fork left it at:
        // at DEBUG, Spring's own client logging would make the outcome depend on the test order.
        root.setLevel(Level.INFO);
        clientLogger.setLevel(Level.DEBUG);
        root.addAppender(appender);
        List<ILoggingEvent> events;
        try {
            SharepointSession session = client.signIn();
            client.createFolder(session, FOLDER);
            client.addFile(session, FOLDER + "/contacts.csv", bytes, true);
        } finally {
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(rootLevel);
            clientLogger.setLevel(clientLevel);
            events = List.copyOf(appender.list);
        }

        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : events) {
            messages.add(event.getFormattedMessage());
            for (IThrowableProxy cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                messages.add(cause.getMessage());
            }
        }
        assertThat(messages).filteredOn(Objects::nonNull)
                .allSatisfy(message -> assertThat(message).doesNotContain(SECRETS));
    }

    private static SharepointSession session() {
        return new SharepointSession(COOKIE_HEADER, DIGEST);
    }

    private static byte[] classpathContactsCsv() throws IOException {
        try (InputStream in = SharepointRestClientTest.class.getResourceAsStream("/contacts.csv")) {
            assertThat(in).as("contacts.csv on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    /** Stubs the three sign-in calls, and the redirect target a followed redirect would reach. */
    private static void stubSignInSuccess() {
        stubStsToken();
        stubWsignin("rtFa=" + RTFA + "; path=/", "FedAuth=" + FEDAUTH + "; path=/");
        stubLanding();
        stubContextInfoDigest();
    }

    private static void stubSts(ResponseDefinitionBuilder response) {
        wm.stubFor(post(urlEqualTo(STS_PATH)).willReturn(response));
    }

    private static void stubStsToken() {
        stubSts(aResponse().withStatus(200)
                .withHeader("Content-Type", SOAP_CONTENT_TYPE)
                .withBody(stsTokenBody(wm.baseUrl())));
    }

    /** The site's answer to the token post: a redirect to its landing page carrying the given cookies. */
    private static void stubWsignin(String... setCookies) {
        wm.stubFor(post(urlEqualTo(WSIGNIN_URL)).willReturn(aResponse().withStatus(302)
                .withHeader("Set-Cookie", setCookies)
                .withHeader("Location", LANDING_URL)));
    }

    private static void stubLanding() {
        wm.stubFor(any(urlEqualTo(LANDING_URL)).willReturn(ok()));
    }

    private static void stubContextInfoDigest() {
        wm.stubFor(post(urlEqualTo(CONTEXTINFO_URL)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", ODATA_CONTENT_TYPE)
                .withBody("{\"d\":{\"GetContextWebInformation\":{\"FormDigestValue\":\"" + DIGEST + "\"}}}")));
    }

    private static void stubFolders(ResponseDefinitionBuilder response) {
        wm.stubFor(post(urlEqualTo(FOLDERS_URL)).willReturn(response));
    }

    private static void stubFilesAdd(String url) {
        wm.stubFor(post(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", ODATA_CONTENT_TYPE)
                .withBody("{\"d\":{\"Name\":\"contacts.csv\"}}")));
    }

    /** A WS-Trust RequestSecurityTokenResponse in the shape the SharePoint Online security token service returns. */
    private static String stsTokenBody(String appliesTo) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <S:Envelope xmlns:S="http://www.w3.org/2003/05/soap-envelope" \
                xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" \
                xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd" \
                xmlns:wsa="%s" xmlns:wst="%s">
                  <S:Body>
                    <wst:RequestSecurityTokenResponse xmlns:wsp="%s">
                      <wst:TokenType>urn:passport:compact</wst:TokenType>
                      <wsp:AppliesTo>
                        <wsa:EndpointReference>
                          <wsa:Address>%s</wsa:Address>
                        </wsa:EndpointReference>
                      </wsp:AppliesTo>
                      <wst:Lifetime>
                        <wsu:Created>2024-01-01T00:00:00Z</wsu:Created>
                        <wsu:Expires>2024-01-02T00:00:00Z</wsu:Expires>
                      </wst:Lifetime>
                      <wst:RequestedSecurityToken>
                        <wsse:BinarySecurityToken Id="Compact0">%s</wsse:BinarySecurityToken>
                      </wst:RequestedSecurityToken>
                    </wst:RequestSecurityTokenResponse>
                  </S:Body>
                </S:Envelope>
                """.formatted(WSA_NS, WST_NS, WSP_NS, appliesTo, TOKEN);
    }

    /** The SOAP 1.2 fault the security token service answers rejected credentials with, under HTTP 200. */
    private static String stsFaultBody() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <S:Envelope xmlns:S="http://www.w3.org/2003/05/soap-envelope" xmlns:wst="%s" \
                xmlns:psf="http://schemas.microsoft.com/Passport/SoapServices/SOAPFault">
                  <S:Body>
                    <S:Fault>
                      <S:Code>
                        <S:Value>S:Sender</S:Value>
                        <S:Subcode>
                          <S:Value>wst:FailedAuthentication</S:Value>
                        </S:Subcode>
                      </S:Code>
                      <S:Reason>
                        <S:Text xml:lang="en-US">Authentication Failure</S:Text>
                      </S:Reason>
                      <S:Detail>
                        <psf:error>
                          <psf:value>0x80048821</psf:value>
                          <psf:internalerror>
                            <psf:code>0x80041012</psf:code>
                            <psf:text>Authentication Failure: the entered and stored passwords do not match.</psf:text>
                          </psf:internalerror>
                        </psf:error>
                      </S:Detail>
                    </S:Fault>
                  </S:Body>
                </S:Envelope>
                """.formatted(WST_NS);
    }
}
