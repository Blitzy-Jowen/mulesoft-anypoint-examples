/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Lets the application start while a MySQL connection setting of the original {@code db:mysql-config} is missing:
 * {@code mysql.host}, {@code mysql.user} or {@code mysql.password} (environment variables {@code MYSQL_HOST},
 * {@code MYSQL_USER}, {@code MYSQL_PASSWORD}).
 *
 * <p>{@code application.yml} assembles {@code spring.datasource.url} as
 * {@code jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}} and binds {@code spring.datasource.username}
 * and {@code spring.datasource.password} to {@code mysql.user} and {@code mysql.password}, which have no default
 * value. Left empty, they would reach Boot's Hikari pool unchecked: an empty host turns the URL into
 * {@code jdbc:mysql://:3306/company}, which Connector/J answers by connecting to its default host, and an empty
 * account fails inside the driver without naming the application's key.
 *
 * <p>{@link IncompleteMySqlSettingsCondition} therefore reads the resolved Boot keys, so every override is honoured
 * (the {@code mysql.*} keys, their environment variables and a direct {@code spring.datasource.*} setting). When the
 * URL is a {@code jdbc:mysql:} URL with every setting present, or any other URL, such as the in-memory H2 database
 * of the tests, this class contributes no bean, and Boot's {@code DataSourceAutoConfiguration} builds its pooled
 * {@code DataSource} as it would without this class. When the URL is blank, or is a {@code jdbc:mysql:} URL without
 * a host, user or password, {@link #dataSource(Environment)} logs one warning naming every missing key and supplies
 * an {@link UnconfiguredDataSource}; the auto-configuration backs off because a {@code DataSource} bean exists, no
 * MySQL connection is ever attempted, and every database call fails with the same text.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** Opens the text naming the missing settings, in the startup warning and in every connection failure. */
    static final String NOT_CONFIGURED_PREFIX = "MySQL connection settings are not configured: ";

    /** Follows that text in the startup warning. */
    static final String STARTUP_CONSEQUENCE = "; the application starts without a database connection, and every "
            + "database call fails until they are supplied";

    /** The Connector/J URL scheme, compared ignoring case; variants like {@code jdbc:mysql:loadbalance:} share it. */
    private static final String MYSQL_URL_PREFIX = "jdbc:mysql:";

    /**
     * The authority of a Connector/J URL: the scheme, then {@code //} and everything up to the path, query or
     * fragment, as Connector/J's own URL parser splits it. A URL without {@code //} has no authority, so the driver
     * falls back to its default host.
     */
    private static final Pattern MYSQL_AUTHORITY =
            Pattern.compile("jdbc:mysql:[\\w+:%]*\\s*//(?<authority>[^/?#]*)", Pattern.CASE_INSENSITIVE);

    /**
     * The {@code DataSource} used while a MySQL connection setting is missing. It opens no connection when created,
     * so the application starts; its first use fails instead.
     *
     * @param environment the environment whose resolved {@code spring.datasource.*} keys name the missing settings
     * @return a {@code DataSource} whose every connection request fails, naming each missing key and its
     *         environment variable
     */
    @Bean
    @Conditional(DataSourceConfig.IncompleteMySqlSettingsCondition.class)
    public DataSource dataSource(Environment environment) {
        String message = notConfiguredMessage(missingSettings(environment));
        log.warn(message + STARTUP_CONSEQUENCE);
        return new UnconfiguredDataSource(message);
    }

    /**
     * The MySQL settings of the original {@code db:mysql-config} that have no default value, each with the
     * environment variable {@code application.yml} reads it from.
     */
    enum MySqlSetting {

        HOST("mysql.host", "MYSQL_HOST"),
        USER("mysql.user", "MYSQL_USER"),
        PASSWORD("mysql.password", "MYSQL_PASSWORD");

        private final String key;

        private final String environmentVariable;

        MySqlSetting(String key, String environmentVariable) {
            this.key = key;
            this.environmentVariable = environmentVariable;
        }

        /**
         * @return the key and its environment variable, never a value, for example
         *         {@code mysql.host (environment variable MYSQL_HOST)}
         */
        String describe() {
            return key + " (environment variable " + environmentVariable + ")";
        }
    }

    /**
     * The MySQL settings missing from the resolved {@code spring.datasource.*} keys, in declaration order.
     *
     * <p>A blank {@code spring.datasource.url} lacks the host it is assembled from. A URL that has text and does not
     * start with {@code jdbc:mysql:} (ignoring case) is not a MySQL URL, so nothing is missing whatever its account.
     * A {@code jdbc:mysql:} URL lacks {@link MySqlSetting#HOST} when its authority holds no host, and either kind
     * lacks {@link MySqlSetting#USER} or {@link MySqlSetting#PASSWORD} when {@code spring.datasource.username} or
     * {@code spring.datasource.password} is absent, empty or whitespace only.
     *
     * @param environment the resolver of the Boot keys, placeholders included
     * @return the missing settings; empty when Boot's own {@code DataSource} applies
     */
    static List<MySqlSetting> missingSettings(PropertyResolver environment) {
        String url = environment.getProperty("spring.datasource.url");
        boolean blankUrl = !StringUtils.hasText(url);
        if (!blankUrl && !isMySqlUrl(url)) {
            return List.of();
        }
        List<MySqlSetting> missing = new ArrayList<>(MySqlSetting.values().length);
        if (blankUrl || !hasHost(url)) {
            missing.add(MySqlSetting.HOST);
        }
        if (!StringUtils.hasText(environment.getProperty("spring.datasource.username"))) {
            missing.add(MySqlSetting.USER);
        }
        if (!StringUtils.hasText(environment.getProperty("spring.datasource.password"))) {
            missing.add(MySqlSetting.PASSWORD);
        }
        return List.copyOf(missing);
    }

    /**
     * The text naming {@code missing}, for example {@code MySQL connection settings are not configured: mysql.host
     * (environment variable MYSQL_HOST), mysql.user (environment variable MYSQL_USER)}.
     *
     * @param missing the missing settings, at least one
     * @return the text, holding key and environment-variable names only
     */
    static String notConfiguredMessage(List<MySqlSetting> missing) {
        if (missing.isEmpty()) {
            throw new IllegalArgumentException("at least one missing MySQL setting is required");
        }
        return missing.stream().map(MySqlSetting::describe)
                .collect(Collectors.joining(", ", NOT_CONFIGURED_PREFIX, ""));
    }

    private static boolean isMySqlUrl(String url) {
        return url.strip().toLowerCase(Locale.ROOT).startsWith(MYSQL_URL_PREFIX);
    }

    /**
     * Whether the authority of the MySQL URL {@code url} names a host: user information before the last {@code @}
     * is skipped, and what remains must neither be blank nor start with the port separator {@code :}, as in
     * {@code jdbc:mysql://:3306/company}.
     */
    private static boolean hasHost(String url) {
        Matcher matcher = MYSQL_AUTHORITY.matcher(url.strip());
        if (!matcher.lookingAt()) {
            return false;
        }
        String authority = matcher.group("authority");
        String hostInfo = authority.substring(authority.lastIndexOf('@') + 1).strip();
        return !hostInfo.isEmpty() && hostInfo.charAt(0) != ':';
    }

    /**
     * Matches while {@link #missingSettings} finds a missing MySQL setting in the environment's resolved
     * {@code spring.datasource.*} keys.
     */
    static final class IncompleteMySqlSettingsCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !missingSettings(context.getEnvironment()).isEmpty();
        }
    }
}
