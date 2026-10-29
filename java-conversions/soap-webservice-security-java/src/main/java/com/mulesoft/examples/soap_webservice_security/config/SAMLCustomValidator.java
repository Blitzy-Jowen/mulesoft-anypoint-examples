/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.OpenSAMLUtil;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.SamlAssertionValidator;

/**
 * SAML 2.0 token validator of the {@code /services/saml} and {@code /services/signedsaml} Greeter
 * endpoints, registered for {@code WSConstants.SAML_TOKEN} and {@code WSConstants.SAML2_TOKEN} by
 * {@code PathScopedSecurityInterceptor} and exposed as the bean {@code samlCustomValidator} by
 * {@code WsSecurityConfig}.
 *
 * <p>After WSS4J's own assertion validation, an assertion is accepted only when it is issued by
 * {@code self}, is SAML 2.0, has the sender-vouches subject confirmation method and names the subject
 * {@code AllowGreetingServices}. Every rejection is a {@link WSSecurityException} with
 * {@link WSSecurityException.ErrorCode#FAILURE} and message ID {@code invalidSAMLsecurity}; the
 * caller-facing fault text is chosen by {@code PathScopedSecurityInterceptor}, not here.
 */
public class SAMLCustomValidator extends SamlAssertionValidator
{
    @Override
    public Credential validate(Credential credential, RequestData data) throws WSSecurityException
    {
        Credential returnedCredential = super.validate(credential, data);
        SamlAssertionWrapper assertion = credential.getSamlAssertion();
        if (!"self".equals(assertion.getIssuerString()))
        {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (assertion.getSaml2() == null)
        {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        // Only the first confirmation method is inspected and no emptiness guard is added: the original
        // validator did the same, and the interceptor turns any runtime failure here into a fault.
        String confirmationMethod = assertion.getConfirmationMethods().get(0);
        if (confirmationMethod == null)
        {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        if (!OpenSAMLUtil.isMethodSenderVouches(confirmationMethod))
        {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (!"AllowGreetingServices".equals(assertion.getSaml2().getSubject().getNameID().getValue()))
        {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        return returnedCredential;
    }

}
