/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support;

import java.util.Properties;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The demo values of the Spring profile {@code test}, read from the classpath {@code application-test.yml} without a
 * Spring context, for unit tests that build their collaborators by hand. That file is the one place the demo values
 * live, so a test reads them from it rather than repeating them.
 */
public final class TestProfileValues {

    /** The configuration file of profile {@code test}, as Spring Boot finds it on the test classpath. */
    private static final String RESOURCE = "application-test.yml";

    private TestProfileValues() {
    }

    /**
     * Returns the value of a key of {@code application-test.yml}.
     *
     * @param key the dotted key as Spring Boot binds it, nested YAML maps joined with dots, for example
     *            {@code keystore.password}
     * @return the key's value as text
     * @throws IllegalStateException when the file is not on the classpath, or holds no value or an empty one for
     *                               {@code key}; the message names the file and the key
     */
    public static String required(String key) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException(RESOURCE + " is not on the test classpath");
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        Properties values = yaml.getObject();
        // A YAML scalar such as a number is kept as its own type, which Properties.getProperty would not return.
        Object value = values == null ? null : values.get(key);
        if (value == null || value.toString().isEmpty()) {
            throw new IllegalStateException(RESOURCE + " has no value for " + key);
        }
        return value.toString();
    }
}
