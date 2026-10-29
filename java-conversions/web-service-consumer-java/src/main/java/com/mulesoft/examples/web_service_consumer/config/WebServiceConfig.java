/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SAAJ runtime settings of the {@code TshirtService} client.
 *
 * <p>{@code client/TshirtServiceClient} builds its {@code WebServiceTemplate} over a SAAJ
 * {@code SaajSoapMessageFactory}, so SAAJ writes every {@code OrderTshirt} and {@code ListInventory} request and
 * parses every response. The original's {@code ws:consumer} ran on Mule's CXF/StAX stack, which looked up no JAXP
 * factory per message.
 */
@Configuration(proxyBeanMethods = false)
public class WebServiceConfig {

    /** JVM system property under which SAAJ creates the JDK's transformer factory instead of looking one up. */
    public static final String SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY = "saaj.use.default.transformer.factory";

    /**
     * Makes SAAJ create the JDK's built-in transformer factory directly. Unless
     * {@value #SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY} is {@code true}, SAAJ looks a factory up through the JAXP
     * service mechanism for every transformer it builds, that is for every envelope {@code TshirtServiceClient}'s
     * template writes and every envelope it parses, and each lookup scans every jar of the application's class path
     * under one lock, so concurrent SOAP calls queue behind one another; the original CXF/StAX stack made no lookup
     * per message. No dependency registers a {@code javax.xml.transform.TransformerFactory} service, so that lookup
     * ends at the same JDK XSLTC factory this switch selects, and every message is read and written exactly as
     * before.
     * <p>
     * A bean factory post-processor runs before any regular bean is instantiated, and so before any SAAJ message is
     * created; it is static so that this configuration class is not instantiated that early. A value already set,
     * such as an operator's {@code -Dsaaj.use.default.transformer.factory=false}, is left unchanged.
     *
     * @return the post-processor that sets {@value #SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY} to {@code true}
     */
    @Bean
    public static BeanFactoryPostProcessor saajDefaultTransformerFactory() {
        return beanFactory -> {
            if (System.getProperty(SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY) != null) {
                return;
            }
            System.setProperty(SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY, Boolean.TRUE.toString());
        };
    }
}
