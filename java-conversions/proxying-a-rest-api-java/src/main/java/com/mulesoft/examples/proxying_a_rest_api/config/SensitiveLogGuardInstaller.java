/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Installs {@link SensitiveLogGuard} right after Spring Boot has initialized the logging system, before any
 * request can be served. Spring Boot's {@link LoggingApplicationListener} configures Logback on the same event,
 * resetting the context and with it every turbo filter, and then applies the {@code logging.level.*} properties;
 * this listener runs next, so the guard holds under Spring Boot's default configuration, a
 * {@code logging.config} file and any configured level alike.
 *
 * <p>It is registered in {@code META-INF/spring.factories}, so every {@code SpringApplication} of this project
 * gets it, whether started by {@code main} or by a test. Registering the filter from a {@code logback-spring.xml}
 * instead would replace Spring Boot's default configuration with that file: its {@code base.xml} always adds a file
 * appender, under {@code java.io.tmpdir} when no {@code logging.file.*} is set, and a {@code logging.config} file
 * would drop the filter.
 */
public final class SensitiveLogGuardInstaller
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /** Immediately after {@link LoggingApplicationListener}. */
    static final int ORDER = LoggingApplicationListener.DEFAULT_ORDER + 1;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        SensitiveLogGuard.install();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
