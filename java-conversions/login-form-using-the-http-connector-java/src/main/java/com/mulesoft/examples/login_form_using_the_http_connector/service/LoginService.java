/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.service;

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
            LOG.info("User {} is trying to login", username);
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
        return configuredUser.equals(user) && configuredPassword.equals(pass);
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

        /** Returns the posted form fields as an unmodifiable map. */
        public Map<String, String> form() {
            return form;
        }
    }
}
