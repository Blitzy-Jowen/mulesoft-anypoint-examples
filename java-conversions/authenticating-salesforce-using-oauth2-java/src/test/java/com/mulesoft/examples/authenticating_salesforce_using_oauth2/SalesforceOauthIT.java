/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebClientOptions;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;

/**
 * Tier 2B live-sandbox test, run by failsafe ({@code mvn -B verify}): the original {@code SalesforceOauthIT}. A
 * headless browser logs in to Salesforce as {@code sfdc.username}, then requests {@code GET /} of the running
 * application, which completes the OAuth 2.0 authorization-code flow against the connected app {@code sfdc.key} /
 * {@code sfdc.secret}, runs its contact query and answers {@code Salesforce query returned <n> contacts.}. HtmlUnit
 * replaces the PhantomJS login of the original, so no browser binary is configured.
 *
 * <p>Read-only, as the original: the browser login and the application's contact query create, update and delete
 * nothing. The profile {@code it} makes Spring Boot load the git-ignored {@code src/test/resources/application-it.yml}
 * (template {@code application-it.yml.example}) over the empty {@code sfdc.*} defaults of {@code application.yml};
 * the original read the same four values from its test properties file, the user under the former name
 * {@code sfdc.user}.
 *
 * <p>Salesforce redirects the browser only to a callback URL registered on the connected app, so the application
 * listens on a fixed port: {@code http.port}, which also sets the port of the {@code redirect_uri}
 * ({@code sfdc.oauth.callback.local-port}). Salesforce may also challenge a login from an unrecognised browser with an
 * identity verification page, which the test cannot answer; the org must trust the network the test runs from.
 *
 * <p>The class is skipped, never failed, while {@code application-it.yml} or one of {@code sfdc.key},
 * {@code sfdc.secret}, {@code sfdc.username}, {@code sfdc.password} is missing, blank, still {@code TODO} or an
 * unresolvable placeholder ({@link LiveCredentialsGate}). A skipped run is not Tier 2B evidence.
 */
@ExtendWith(SalesforceOauthIT.LiveCredentialsGate.class)
// No inline http.port: a test property would outrank both the http.port of application-it.yml and HTTP_PORT, through
// which application-it.yml.example has the operator move the listener and the registered redirect_uri together onto a
// free port; without either, http.port keeps the 8081 of application.yml, the original callback port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("it")
class SalesforceOauthIT {

    private static final String MESSAGE = "Salesforce query returned";

    private static final String LOGIN_URL = "https://login.salesforce.com/";

    private static final String CREDENTIALS_FILE = "application-it.yml";

    private static final List<String> REQUIRED_KEYS =
            List.of("sfdc.key", "sfdc.secret", "sfdc.username", "sfdc.password");

    /** How long the login form may take to appear, the 30 s {@code WebDriverWait} of the original. */
    private static final Duration LOGIN_FORM_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration LOGIN_FORM_POLL_INTERVAL = Duration.ofMillis(500);

    /** Time given to page scripts after each navigation, the original's 5000 ms sleep after the login. */
    private static final long SETTLE_MILLIS = 5_000;

    private static final int BROWSER_TIMEOUT_MILLIS = 30_000;

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private SalesforceOAuthProperties oauthProperties;

    @Test
    void testAuthentication() throws Exception {
        Assertions.assertEquals(oauthProperties.getOauth().getCallback().getLocalPort(), port,
                "the redirect_uri port must be the listener port; set http.port, not sfdc.oauth.callback.local-port");

        try (WebClient browser = newBrowser()) {
            loginToSalesforce(browser);
            // wait till the login process is complete
            browser.waitForBackgroundJavaScript(SETTLE_MILLIS);

            Page result = approveAccessIfAsked(browser.getPage("http://localhost:" + port + "/"));
            browser.waitForBackgroundJavaScript(SETTLE_MILLIS);

            // A page script may have navigated after the last load; the window holds the page finally shown.
            Page shown = browser.getCurrentWindow().getEnclosedPage();
            WebResponse response = (shown == null ? result : shown).getWebResponse();
            String content = response.getContentAsString();
            Assertions.assertTrue(content != null && content.contains(MESSAGE),
                    () -> "expected '" + MESSAGE + "' after the Salesforce login; got HTTP " + response.getStatusCode()
                            + " " + response.getContentType() + " at " + withoutQuery(response.getWebRequest().getUrl()));
        }
    }

    private static WebClient newBrowser() {
        WebClient browser = new WebClient(BrowserVersion.CHROME);
        WebClientOptions options = browser.getOptions();
        options.setJavaScriptEnabled(true);
        options.setThrowExceptionOnScriptError(false);
        options.setThrowExceptionOnFailingStatusCode(false);
        options.setCssEnabled(false);
        options.setRedirectEnabled(true);
        options.setTimeout(BROWSER_TIMEOUT_MILLIS);
        // The pom excludes HtmlUnit's Jetty 9.4 WebSocket client, which cannot run beside Boot's Jetty 12 libraries.
        options.setWebSocketEnabled(false);
        browser.setAjaxController(new NicelyResynchronizingAjaxController());
        return browser;
    }

    private void loginToSalesforce(WebClient browser) throws IOException {
        browser.getPage(LOGIN_URL);
        await("Salesforce login form (element id username)")
                .atMost(LOGIN_FORM_TIMEOUT)
                .pollInterval(LOGIN_FORM_POLL_INTERVAL)
                .pollInSameThread()
                .until(() -> {
                    HtmlPage page = currentHtmlPage(browser);
                    return page != null && page.getElementById("username") != null;
                });

        HtmlPage loginPage = currentHtmlPage(browser);
        HtmlInput username = input(loginPage, "username");
        HtmlInput password = input(loginPage, "password");
        username.type(environment.getRequiredProperty("sfdc.username"));
        password.type(environment.getRequiredProperty("sfdc.password"));

        DomElement login = loginPage.getElementById("Login");
        if (login != null) {
            login.click();
        } else {
            // The original's username.submit(): the enclosing form is sent as is. Pressing Enter would not do it, as
            // implicit submission does nothing in a form with two text fields and no submit button.
            browser.getPage(username.getEnclosingFormOrDie().getWebRequest(null));
        }
    }

    /**
     * Grants the connected app access when Salesforce asks: a user who has not yet approved it is shown the approval
     * page, whose Allow button is {@code oaapprove}, before Salesforce redirects to the callback.
     */
    private static Page approveAccessIfAsked(Page page) throws IOException {
        if (page instanceof HtmlPage html) {
            List<DomElement> approve = html.getElementsByIdAndOrName("oaapprove");
            if (!approve.isEmpty()) {
                return approve.get(0).click();
            }
        }
        return page;
    }

    private static HtmlPage currentHtmlPage(WebClient browser) {
        return browser.getCurrentWindow().getEnclosedPage() instanceof HtmlPage page ? page : null;
    }

    private static HtmlInput input(HtmlPage page, String id) {
        return Assertions.assertInstanceOf(HtmlInput.class, page.getElementById(id),
                () -> "Salesforce login form has no input with id " + id + " at " + withoutQuery(page.getUrl()));
    }

    /** The URL without its query, which may carry the authorization code. */
    private static String withoutQuery(URL url) {
        String external = url.toExternalForm();
        int query = external.indexOf('?');
        return query < 0 ? external : external.substring(0, query);
    }

    /**
     * Why the class cannot run live, or {@code null} when {@code application-it.yml} supplies every required key with
     * a value that is neither blank nor {@code TODO}.
     */
    static String missingCredential() {
        ClassPathResource credentials = new ClassPathResource(CREDENTIALS_FILE);
        if (!credentials.exists()) {
            return "src/test/resources/" + CREDENTIALS_FILE
                    + " not supplied (copy application-it.yml.example); live Salesforce OAuth test skipped";
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(credentials);
        Properties properties = yaml.getObject();
        StandardEnvironment resolver = new StandardEnvironment();
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            String value = resolve(resolver, properties == null ? null : properties.getProperty(key));
            if (isUnset(value)) {
                missing.add(key);
            }
        }
        return missing.isEmpty()
                ? null
                : String.join(", ", missing) + " missing in " + CREDENTIALS_FILE + "; live Salesforce OAuth test skipped";
    }

    private static boolean isUnset(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.equalsIgnoreCase("TODO") || trimmed.startsWith("TODO");
    }

    /**
     * Resolves {@code ${...}} placeholders against system properties and environment variables, as Spring Boot will
     * when it loads the file; a placeholder without a value or default yields {@code null}, because the context
     * would fail to start on it.
     */
    private static String resolve(StandardEnvironment resolver, String value) {
        if (value == null) {
            return null;
        }
        try {
            return resolver.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    /**
     * Skips the class unless every live credential is supplied ({@link #missingCredential()}), deciding at class
     * level, before the test instance exists. The Spring context is loaded when the instance is prepared, so a
     * disabled class starts no application, binds no port and attempts no Salesforce login.
     *
     * <p>A class condition rather than an assumption in {@code @BeforeAll}: failsafe 3.1.2 counts a class whose
     * {@code @BeforeAll} aborts as neither run nor skipped, while each test of a class disabled here is reported
     * skipped with the reason, so the report shows that the live tier was skipped.
     */
    static final class LiveCredentialsGate implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (context.getTestMethod().isPresent()) {
                return ConditionEvaluationResult.enabled("decided for the class");
            }
            String missing = missingCredential();
            return missing == null
                    ? ConditionEvaluationResult.enabled("live Salesforce credentials supplied")
                    : ConditionEvaluationResult.disabled(missing);
        }
    }
}
