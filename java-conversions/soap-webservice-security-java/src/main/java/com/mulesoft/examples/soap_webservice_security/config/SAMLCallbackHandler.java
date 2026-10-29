/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.apache.wss4j.common.saml.bean.AuthenticationStatementBean;
import org.apache.wss4j.common.saml.bean.SubjectBean;
import org.apache.wss4j.common.saml.bean.Version;
import org.apache.wss4j.common.saml.builder.SAML2Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * Callback handler that populates a SAML 2.0 assertion based on the SAML properties file.
 *
 * <p>Used by the {@code samlToken} ({@code SAMLTokenUnsigned Timestamp}) and {@code samlTokenSigned}
 * ({@code SAMLTokenSigned}) Greeter clients. Every assertion is SAML 2.0 with the subject
 * {@code AllowGreetingServices} (qualifier {@code www.example.com}), the sender-vouches confirmation method and one
 * {@code Password} authentication statement, which is what {@code SAMLCustomValidator} accepts on
 * {@code /services/saml} and {@code /services/signedsaml}.
 *
 * <p>Under WSS4J 1.6 the CXF client also read the {@code samlPropFile} ({@code saml.properties}), from which
 * {@code SAMLIssuerImpl} took the issuer and the issuer key. WSS4J 4 has no such option, so this handler reads the
 * same file and puts that data on the callback itself: the issuer ({@code self}, or {@code www.example.com} in
 * {@code wrong-saml.properties}, which the validator rejects), the issuer key name ({@code joe}), the issuer key
 * password and the issuer crypto. The password is not read from the file: it is externalized to configuration and
 * passed to the constructor, so the carried files hold no secret.
 *
 * <p>Not a Spring component: {@code WsSecurityConfig} defines the {@code samlCallbackHandler} bean for the configured
 * SAML properties file, and tests construct one per properties file. All state is immutable, so one instance serves
 * concurrent client calls; the crypto supplier must itself be safe to call concurrently.
 */
public class SAMLCallbackHandler implements CallbackHandler {

    /** Issuer key of the carried properties files; the WSS4J 1.6 key name is kept so the files stay unchanged. */
    static final String ISSUER_PROPERTY = "org.apache.ws.security.saml.issuer";

    /** Issuer key-name key of the carried properties files, likewise the WSS4J 1.6 name. */
    static final String ISSUER_KEY_NAME_PROPERTY = "org.apache.ws.security.saml.issuer.key.name";

    private static final Logger LOG = LoggerFactory.getLogger(SAMLCallbackHandler.class);

    /** Most characters one class, method or file name may occupy in a log line once escaped. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Most causes named in a log line; a longer chain ends with {@code ...}. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private final String subjectName;
    private final String subjectQualifier;
    private final String confirmationMethod;

    private final String issuer;
    private final String issuerKeyName;
    private final String issuerKeyPassword;
    private final Supplier<Crypto> issuerCrypto;

    /**
     * @param samlProperties    the SAML properties file ({@code saml.properties} or {@code wrong-saml.properties})
     *                          supplying the issuer and the issuer key name
     * @param issuerKeyPassword password of the issuer's private key; {@code null} or empty when not configured,
     *                          in which case no password is set on the callback
     * @param issuerCrypto      supplier of the crypto holding the issuer key, resolved on every callback so an
     *                          unconfigured keystore password fails only the signed client; may be {@code null}
     * @throws IllegalStateException when the properties file cannot be read
     */
    public SAMLCallbackHandler(Resource samlProperties, String issuerKeyPassword, Supplier<Crypto> issuerCrypto) {
        subjectName = "AllowGreetingServices";
        subjectQualifier = "www.example.com";
        confirmationMethod = SAML2Constants.CONF_SENDER_VOUCHES;

        Properties properties = load(Objects.requireNonNull(samlProperties, "samlProperties"));
        this.issuer = properties.getProperty(ISSUER_PROPERTY);
        this.issuerKeyName = properties.getProperty(ISSUER_KEY_NAME_PROPERTY);
        this.issuerKeyPassword = issuerKeyPassword == null ? "" : issuerKeyPassword;
        this.issuerCrypto = issuerCrypto;
    }

    /** @return the assertion issuer read from the SAML properties file */
    public String getIssuer() {
        return issuer;
    }

    /** @return the alias of the issuer key read from the SAML properties file */
    public String getIssuerKeyName() {
        return issuerKeyName;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] instanceof SAMLCallback) {
                SAMLCallback callback = (SAMLCallback) callbacks[i];
                // WSS4J 4 deprecates the OpenSAML SAMLVersion overload; this one stores SAMLVersion.VERSION_20.
                callback.setSamlVersion(Version.SAML_20);
                SubjectBean subjectBean =
                        new SubjectBean(
                                subjectName, subjectQualifier, confirmationMethod
                        );
                callback.setSubject(subjectBean);
                createAndSetStatement(null, callback);
                setIssuerData(callback);
            } else {
                throw new UnsupportedCallbackException(callbacks[i], "Unrecognized Callback");
            }
        }
    }

    private void createAndSetStatement(SubjectBean subjectBean, SAMLCallback callback) {
        AuthenticationStatementBean authBean = new AuthenticationStatementBean();
        if (subjectBean != null) {
            authBean.setSubject(subjectBean);
        }
        authBean.setAuthenticationMethod("Password");
        callback.setAuthenticationStatementData(Collections.singletonList(authBean));
    }

    private void setIssuerData(SAMLCallback callback) {
        callback.setIssuer(issuer);
        // With sender-vouches, SAMLTokenSigned signs the message with the issuer key (a DirectReference
        // BinarySecurityToken) and leaves the assertion itself unsigned, as the WSS4J 1.6 client did. A signed
        // assertion would be reported as SAMLTokenSigned, which /services/signedsaml (SAMLTokenUnsigned Signature)
        // does not accept.
        callback.setSignAssertion(false);
        callback.setIssuerKeyName(issuerKeyName);
        if (!issuerKeyPassword.isEmpty()) {
            callback.setIssuerKeyPassword(issuerKeyPassword);
        }
        Crypto crypto = resolveIssuerCrypto();
        if (crypto != null) {
            callback.setIssuerCrypto(crypto);
        }
    }

    /**
     * Resolves the issuer crypto for one callback. A crypto that cannot be loaded (typically an unconfigured
     * keystore password) leaves the issuer crypto unset: the unsigned SAML client never uses it, and the signed
     * client then fails inside WSS4J, which the client front reports through its catch branch.
     *
     * <p>The failure is logged at DEBUG by type and throw site only, without the issuer, the exception message
     * or the throwable, because the message and the causes of a keystore failure can carry keystore or
     * credential diagnostics.
     */
    private Crypto resolveIssuerCrypto() {
        if (issuerCrypto == null) {
            return null;
        }
        try {
            return issuerCrypto.get();
        } catch (RuntimeException ex) {
            LOG.debug("SAML issuer crypto is unavailable; the assertion carries no issuer crypto: {}", describe(ex));
            return null;
        }
    }

    /**
     * Returns the type identifiers of {@code ex} without any of its text: its class name and throw-site frame,
     * then the class name of each cause, separated by {@code " <- "}. The walk stops at a cause already named,
     * marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(sanitize(ex.getClass().getName()));
        appendThrowSite(out, ex);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(ex);
        int causes = 0;
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            out.append(" <- ");
            if (causes == CAUSE_LOG_LIMIT) {
                return out.append("...").toString();
            }
            if (!named.add(cause)) {
                return out.append("[circular]").toString();
            }
            out.append(sanitize(cause.getClass().getName()));
            causes++;
        }
        return out.toString();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code ex}, or nothing when
     * the exception has no stack trace.
     */
    private static void appendThrowSite(StringBuilder out, Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return;
        }
        StackTraceElement frame = trace[0];
        out.append(" at ").append(sanitize(frame.getClassName()))
                .append('.').append(sanitize(frame.getMethodName())).append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(sanitize(frame.getFileName()));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /**
     * Makes a name safe to log on one line: ISO control characters, line and paragraph separators, format
     * characters, unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four
     * lower-case hex digits; a value longer than {@link #NAME_LOG_LIMIT} characters once escaped is cut before
     * the character or escape that would exceed it, never inside a surrogate pair, and {@code ...[truncated]} is
     * appended. {@code null} is written as {@code (null)}.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "(null)";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), NAME_LOG_LIMIT));
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean escape = codePoint == '\\' || Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.FORMAT || type == Character.SURROGATE;
            if (out.length() + (escape ? 6 * units : units) > NAME_LOG_LIMIT) {
                return out.append("...[truncated]").toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                if (escape) {
                    out.append(String.format("\\u%04x", (int) value.charAt(unit)));
                } else {
                    out.append(value.charAt(unit));
                }
            }
            i += units;
        }
        return out.toString();
    }

    private static Properties load(Resource samlProperties) {
        Properties properties = new Properties();
        try (InputStream in = samlProperties.getInputStream()) {
            properties.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the SAML properties " + samlProperties.getDescription(), ex);
        }
        return properties;
    }
}
