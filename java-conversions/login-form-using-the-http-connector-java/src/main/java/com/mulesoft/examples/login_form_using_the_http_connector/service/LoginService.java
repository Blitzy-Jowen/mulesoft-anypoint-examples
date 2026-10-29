/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.service;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.login_form_using_the_http_connector.client.LoginRequesterClient;
import com.mulesoft.examples.login_form_using_the_http_connector.config.LoginProperties;
import com.mulesoft.examples.login_form_using_the_http_connector.mapper.ParseTemplateRenderer;

/**
 * Processor logic of the three flows of {@code login-form-using-the-http-connector.xml}.
 *
 * <ul>
 *   <li>{@code GetLoginPageFlow} (xml:7-12): {@link #loginPage()} renders {@code login/index.html}.</li>
 *   <li>{@code DoLoginFlow} (xml:14-31): {@link #login(Map)} logs the username, applies the credential
 *       filter (warning when the configured credentials are blank) and renders
 *       {@code login/loginSuccessful.html}; every failure of those steps surfaces as
 *       {@link LoginFailedException}, which the flow's catch strategy answered with 403 and
 *       {@code loginFailure.html}.</li>
 *   <li>{@code CallLoginFlowUsingRequester} (xml:33-43): {@link #requesterLogin()} posts the configured
 *       credentials as a form to this application's own {@code POST /login}.</li>
 * </ul>
 *
 * <p>The original compared against, and posted, the literals {@code mule}/{@code mule} (xml:21, 39-40); both
 * now come from {@code login.username} and {@code login.password}. HTTP status, Content-Type and the 403
 * page are written by the controller and the exception handler, not here.
 */
@Service
public class LoginService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

    /** Lower-case hex digits of the {@code \}{@code u} escapes in the username's log line. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** Template of {@code GetLoginPageFlow} (xml:11); it holds no expression. */
    static final String LOGIN_PAGE = "login/index.html";

    /** Template rendered when the credentials are accepted (xml:24). */
    static final String LOGIN_SUCCESSFUL_PAGE = "login/loginSuccessful.html";

    /** The only expression of {@code loginSuccessful.html} (line 13), replaced by the posted username. */
    static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** Form field holding the username. */
    static final String USERNAME = "username";

    /** Form field holding the password. */
    static final String PASSWORD = "password";

    /** Property holding the accepted username; a blank value is named, never shown, in the rejection warning. */
    static final String USERNAME_KEY = "login.username";

    /** Property holding the accepted password; a blank value is named, never shown, in the rejection warning. */
    static final String PASSWORD_KEY = "login.password";

    private final LoginProperties properties;
    private final ParseTemplateRenderer renderer;
    private final LoginRequesterClient client;

    /**
     * @param properties source of the accepted (and requester-posted) {@code login.username}/{@code login.password}
     * @param renderer   SC-14 template renderer
     * @param client     adapter of the {@code http:request} to {@code POST /login}
     */
    public LoginService(LoginProperties properties, ParseTemplateRenderer renderer, LoginRequesterClient client) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * {@code GetLoginPageFlow}: the login page, byte-identical to {@code login/index.html}.
     *
     * @return the page text
     * @throws IllegalStateException if the template cannot be found
     */
    public String loginPage() {
        return renderer.render(LOGIN_PAGE);
    }

    /**
     * {@code DoLoginFlow}: logs the attempt, accepts only the configured credentials and renders the success
     * page for the accepted username.
     *
     * @param form the posted form fields; {@code null} is treated as an empty form
     * @return the rendered {@code loginSuccessful.html}
     * @throws LoginFailedException when the credentials are rejected or any step of the flow fails, carrying
     *                              the posted form and, for a failed step, its exception as the cause
     */
    public String login(Map<String, String> form) {
        Map<String, String> submitted = form == null ? Map.of() : form;
        // The flow's catch-exception-strategy (xml:27-30) caught every exception the flow raised, so a
        // rendering failure must reach the 403 branch as well as a filter rejection.
        try {
            String username = submitted.get(USERNAME);
            LOG.info("User {} is trying to login", escapeForLog(username));
            if (!credentialsMatch(username, submitted.get(PASSWORD))) {
                throw new LoginFailedException(submitted);
            }
            return renderer.render(LOGIN_SUCCESSFUL_PAGE, Map.of(USERNAME_EXPRESSION, username));
        } catch (LoginFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LoginFailedException(submitted, e);
        }
    }

    /**
     * The {@code expression-filter} of xml:21 with the configured values in place of {@code 'mule'}. An unset
     * or blank configured credential rejects every form, so a missing key can never admit an empty form; each
     * such rejection logs one WARN naming the blank keys, without any configured or submitted value.
     *
     * <p>Otherwise the submitted username and password are each compared with their configured value in constant
     * time, accepting exactly what {@link String#equals} accepts; both comparisons always run and are combined
     * without short-circuiting, so the response time reveals neither which value was wrong nor how much of it
     * matched.
     */
    private boolean credentialsMatch(String user, String pass) {
        String configuredUser = properties.username();
        String configuredPassword = properties.password();
        boolean userUnset = configuredUser == null || configuredUser.isBlank();
        boolean passwordUnset = configuredPassword == null || configuredPassword.isBlank();
        if (userUnset && passwordUnset) {
            LOG.warn("Login rejected because {} and {} are not configured", USERNAME_KEY, PASSWORD_KEY);
            return false;
        }
        if (userUnset || passwordUnset) {
            LOG.warn("Login rejected because {} is not configured", userUnset ? USERNAME_KEY : PASSWORD_KEY);
            return false;
        }
        // The non-short-circuit '&' runs both comparisons whatever the first one decides.
        return constantTimeEquals(user, configuredUser) & constantTimeEquals(pass, configuredPassword);
    }

    /**
     * Whether {@code supplied} equals {@code configured}, exactly as {@link String#equals} decides, in time that
     * does not depend on where the two values first differ.
     *
     * <p>Both values are compared as their UTF-16 code units, two bytes each, a lossless form: encoding them as
     * UTF-8 would write every unpaired surrogate as the same {@code ?} byte a literal {@code ?} has, and so accept
     * values {@link String#equals} rejects. The supplied value is the first argument of
     * {@link MessageDigest#isEqual}, whose loop runs over the length of its first argument, so the time does not
     * reveal the configured value's length either.
     *
     * @param supplied   the submitted value; {@code null} never matches, and is still compared so that it takes
     *                   the time of an empty value
     * @param configured the accepted value, neither {@code null} nor blank
     * @return {@code true} only when both values hold the same characters in the same order
     */
    private static boolean constantTimeEquals(String supplied, String configured) {
        boolean sameUnits = MessageDigest.isEqual(utf16Units(supplied), utf16Units(configured));
        return supplied != null & sameUnits;
    }

    /**
     * @param value the value to compare; {@code null} gives no bytes
     * @return the value's UTF-16 code units, high byte first, one pair of bytes per {@code char}
     */
    private static byte[] utf16Units(String value) {
        if (value == null) {
            return new byte[0];
        }
        byte[] bytes = new byte[value.length() * 2];
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            bytes[2 * i] = (byte) (c >> 8);
            bytes[2 * i + 1] = (byte) c;
        }
        return bytes;
    }

    /**
     * The submitted username as the attempt's INFO line writes it, kept on one line.
     *
     * <p>ISO control characters (C0, DEL and C1, so CR, LF, TAB, NUL, ESC and NEL included), line and paragraph
     * separators, format characters (bidirectional overrides and isolates, zero-width characters, the byte order
     * mark) and unpaired surrogates are each written as {@code \}{@code u} and four lower-case hex digits per UTF-16
     * unit, so CR becomes {@code \}{@code u000d} and LF {@code \}{@code u000a}. Every other code point, the reverse
     * solidus and supplementary characters such as emoji included, is copied unchanged: the original logger wrote
     * this text verbatim, so an ordinary username must log exactly as it did, and the escapes only keep a submitted
     * value from starting or forging another log line. Only the log line uses this text; the credential check and
     * the rendered pages use the submitted value itself.
     *
     * @param value the submitted username
     * @return the escaped text, unchanged when nothing needs escaping; {@code null} for a {@code null} value
     */
    private static String escapeForLog(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            boolean escape = mustEscape(codePoint);
            for (int unit = i; unit < i + units; unit++) {
                if (escape) {
                    appendUnicodeEscape(out, value.charAt(unit));
                } else {
                    out.append(value.charAt(unit));
                }
            }
            i += units;
        }
        return out.toString();
    }

    /**
     * @param codePoint a code point of the submitted username, or one of its unpaired surrogates
     * @return whether the log line writes {@code codePoint} as escapes rather than as itself
     */
    private static boolean mustEscape(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        // An unpaired surrogate reaches here as its own code point, of type SURROGATE; a valid pair never does.
        return type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT
                || type == Character.SURROGATE;
    }

    /**
     * Appends {@code c} as {@code \}{@code u} and four lower-case hex digits.
     *
     * @param out the log text being built
     * @param c   one UTF-16 unit of a code point the log line escapes
     */
    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }

    /**
     * {@code CallLoginFlowUsingRequester}: posts the configured credentials to {@code POST /login}. The form is
     * a {@link HashMap}, as the original's {@code new HashMap()} (xml:38), because the client encodes the body
     * in the map's iteration order. Client failures, including a response status of 400 or more, propagate.
     *
     * @return the relayed response of {@code POST /login}, unchanged
     */
    public LoginRequesterClient.LoginResponse requesterLogin() {
        Map<String, String> form = new HashMap<>();
        form.put(USERNAME, properties.username());
        form.put(PASSWORD, properties.password());
        return client.postLogin(form);
    }

    /**
     * A rejected or failed login, answered with 403 and {@code loginFailure.html}. It carries the posted form
     * so the failure page can show {@code #[payload.username]}, which was still the form map in the original
     * catch strategy.
     */
    public static final class LoginFailedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Unmodifiable copy of the posted form; not serialized, so it is {@code null} after deserialization. */
        private final transient Map<String, String> form;

        /**
         * A login rejected by the credential filter.
         *
         * @param form the posted form; {@code null} is recorded as an empty form
         */
        public LoginFailedException(Map<String, String> form) {
            super("Message has been rejected by filter.");
            this.form = copyOf(form);
        }

        /**
         * A login whose processing failed.
         *
         * @param form  the posted form; {@code null} is recorded as an empty form
         * @param cause the failure
         */
        public LoginFailedException(Map<String, String> form, Throwable cause) {
            super(cause == null ? "Login failed" : "Login failed: " + cause.getMessage(), cause);
            this.form = copyOf(form);
        }

        // HashMap, not Map.copyOf: a decoded form may hold null values, which Map.copyOf rejects.
        private static Map<String, String> copyOf(Map<String, String> form) {
            return Collections.unmodifiableMap(new HashMap<>(form == null ? Map.of() : form));
        }

        /** @return the posted form fields as an unmodifiable map; {@code null} after deserialization */
        public Map<String, String> form() {
            return form;
        }
    }
}
