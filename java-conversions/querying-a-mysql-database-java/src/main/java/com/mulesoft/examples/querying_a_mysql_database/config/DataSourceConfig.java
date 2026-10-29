/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Lets the application start while {@code jdbc.url} (environment variable {@code JDBC_URL}) is blank.
 *
 * <p>{@code application.yml} binds {@code spring.datasource.url} to {@code jdbc.url}, which has no default value.
 * When the resolved {@code spring.datasource.url} has text, this class contributes no bean, and Boot's
 * {@code DataSourceAutoConfiguration} builds its pooled {@code DataSource} from that URL as it would without this
 * class. When it is blank, that auto-configuration would stop the application at startup with an error naming only
 * {@code url}. Instead, {@link #dataSource()} logs one warning naming the key and supplies an
 * {@link UnconfiguredDataSource}, the auto-configuration backs off because a {@code DataSource} bean exists, and
 * every database call fails with an error naming {@code jdbc.url} and {@code JDBC_URL}.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /**
     * The {@code DataSource} used while no database URL is configured. It opens no connection when created, so the
     * application starts; its first use fails instead.
     *
     * @return a {@code DataSource} whose every connection request fails, naming {@code jdbc.url} and
     *         {@code JDBC_URL}
     */
    @Bean
    @Conditional(DataSourceConfig.BlankDataSourceUrlCondition.class)
    public DataSource dataSource() {
        log.warn("jdbc.url (environment variable JDBC_URL) is not configured: the application starts without a "
                + "database connection, and every database call fails until it is supplied");
        return new UnconfiguredDataSource();
    }

    /**
     * Matches when {@code spring.datasource.url}, as the environment resolves it, is absent, empty or whitespace
     * only. Reading the resolved Boot key rather than {@code jdbc.url} honours every source that sets the URL:
     * {@code jdbc.url}, {@code JDBC_URL} and a direct {@code spring.datasource.url} override.
     */
    static final class BlankDataSourceUrlCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !StringUtils.hasText(context.getEnvironment().getProperty("spring.datasource.url"));
        }
    }
}
