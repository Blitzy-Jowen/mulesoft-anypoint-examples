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
import java.util.Objects;
import java.util.Properties;
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
     */
    private Crypto resolveIssuerCrypto() {
        if (issuerCrypto == null) {
            return null;
        }
        try {
            return issuerCrypto.get();
        } catch (RuntimeException ex) {
            LOG.debug("SAML issuer crypto is unavailable; the assertion of issuer {} carries no issuer crypto",
                    issuer, ex);
            return null;
        }
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
