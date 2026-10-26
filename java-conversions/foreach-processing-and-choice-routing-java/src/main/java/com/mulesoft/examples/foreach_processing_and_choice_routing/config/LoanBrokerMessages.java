/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The loan broker example's message catalogue: the numbered messages of the copied
 * {@code messages/loanbroker-example-messages*.properties} bundles (root English, {@code _de}, {@code _es}),
 * formatted with {@link MessageFormat} as Mule 3's {@code MessageFactory.getString} formatted them.
 *
 * <p>The example's flows and Java classes never read this catalogue, so no log or response text comes from it;
 * it carries the bundles' lookup semantics for any caller that asks for a message by its code.
 */
@Component
public class LoanBrokerMessages
{
    public static final String BUNDLE = "messages/loanbroker-example-messages";

    /**
     * Resolves only the requested locale's chain down to the root bundle. The default control would fall back to
     * {@link Locale#getDefault()} first, so an English request on a German JVM would read the {@code _de} bundle.
     */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static final Logger logger = LoggerFactory.getLogger(LoanBrokerMessages.class);

    /**
     * Returns message {@code code} in the JVM's default locale.
     *
     * @param code the message number, the key in the bundle
     * @param args the values of the message's {@code {n}} placeholders
     * @return the formatted message, or an empty string when the bundle has no such code
     */
    public String get(int code, Object... args)
    {
        return get(Locale.getDefault(), code, args);
    }

    /**
     * Returns message {@code code} in {@code locale}: {@code de} and {@code es} read their bundles, every other
     * locale the root (English) bundle; a key missing from a language bundle is taken from the root bundle.
     *
     * @param locale the requested locale; {@code null} means {@link Locale#ROOT}
     * @param code the message number, the key in the bundle
     * @param args the values of the message's {@code {n}} placeholders
     * @return the formatted message, or an empty string when the bundle has no such code
     */
    public String get(Locale locale, int code, Object... args)
    {
        Locale effectiveLocale = locale == null ? Locale.ROOT : locale;
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, effectiveLocale, NO_FALLBACK);
        String pattern;
        try
        {
            pattern = bundle.getString(String.valueOf(code));
        }
        catch (MissingResourceException e)
        {
            // Mule 3 MessageFactory.getString answers an unknown code this way rather than failing the caller.
            logger.error("Failed to find message for id {} in resource bundle {}", code, BUNDLE);
            return "";
        }
        return new MessageFormat(pattern, effectiveLocale).format(args == null ? new Object[0] : args);
    }
}
