/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.ParseTemplateRenderer;

/**
 * Serves the currency ratios behind {@code GET /api/currencies}, replacing the flow
 * {@code get:/currencies:currency-config} of
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml} (l.37-39), whose only processor is
 * {@code <parse-template location="currency.json"/>}.
 *
 * <p>The template is rendered through the shared {@link ParseTemplateRenderer} (SC-14). {@code currency.json} holds
 * no {@code #[...]} expression, so the returned text is the classpath resource byte for byte, without a trailing
 * newline, exactly as the original returned it. A missing template raises the renderer's
 * {@link IllegalStateException}, which is left to the application's generic error handling as an unhandled
 * failure, since the original flow declares no exception branch.
 */
@Service
public class CurrencyService {

    /** Classpath location of the template, as the original {@code parse-template location} names it. */
    private static final String TEMPLATE = "currency.json";

    private final ParseTemplateRenderer parseTemplateRenderer;

    public CurrencyService(ParseTemplateRenderer parseTemplateRenderer) {
        this.parseTemplateRenderer = parseTemplateRenderer;
    }

    /**
     * Returns the rendered {@code currency.json} template unchanged.
     *
     * @return the currency document, identical to the classpath resource
     * @throws IllegalStateException when the template is not on the classpath
     */
    public String getCurrencies() {
        return parseTemplateRenderer.render(TEMPLATE);
    }
}
