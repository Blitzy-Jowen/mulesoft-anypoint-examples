/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.util.StringUtils;

import com.mongodb.MongoClientSettings;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config.MongoDatabaseSettings.MissingSetting;

/**
 * Wires the original {@code mongo:config} placeholders {@code database.name}, {@code database.user} and
 * {@code database.password} into Spring Boot's MongoDB auto-configuration, and lets the application start while any
 * of them is empty, as an unset environment variable leaves it.
 *
 * <p>{@code application.yml} binds only the database name to a Spring Boot key. The user and password reach the
 * client through {@link #databaseUserCredentialCustomizer}, never through {@code spring.data.mongodb.username} and
 * {@code password}: an empty password cannot bind to Boot's {@code char[]} password, and an empty user makes the
 * connection string Boot builds unparseable, so either would stop the application at startup.
 *
 * <p>When {@link MongoDatabaseSettings} finds every setting it needs, this class contributes no database factory and
 * Spring Boot builds its {@code MongoClient}, {@code SimpleMongoClientDatabaseFactory} and {@code MongoTemplate} as it
 * would without this class. When a setting is missing, {@link #mongoDatabaseFactory} logs one warning naming its key
 * and environment variable and supplies an {@link UnconfiguredMongoDatabaseFactory}; Spring Boot's MongoDB client
 * configuration and database factory then back off because a {@code MongoDatabaseFactory} bean exists, so no client
 * is created and no connection is attempted, and every import fails at its first database call with an
 * {@link IllegalStateException} naming the key, which the file poller logs at ERROR.
 */
@Configuration(proxyBeanMethods = false)
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    /**
     * Gives the MongoDB client the credential of {@code database.user} and {@code database.password} when both are
     * supplied and {@code spring.data.mongodb.uri} is not set; see {@link MongoDatabaseSettings#credential()}.
     *
     * @param environment the application environment
     * @return the customizer, which runs after Spring Boot's own
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer databaseUserCredentialCustomizer(Environment environment) {
        return new DatabaseUserCredentialCustomizer(MongoDatabaseSettings.from(environment));
    }

    /**
     * The {@code MongoDatabaseFactory} used while a MongoDB setting is missing. It opens no connection when created,
     * so the application starts; its first database access fails instead.
     *
     * @param environment the application environment
     * @return a factory whose every database access fails, naming the missing key
     */
    @Bean
    @Conditional(MongoConfig.MongoDatabaseNotConfiguredCondition.class)
    public MongoDatabaseFactory mongoDatabaseFactory(Environment environment) {
        MissingSetting missingSetting = MongoDatabaseSettings.from(environment).missingSetting()
                .orElseThrow(() -> new IllegalStateException(
                        "The MongoDB settings changed between the startup condition and the factory bean"));
        log.warn(missingSetting.startupWarning());
        return new UnconfiguredMongoDatabaseFactory(missingSetting);
    }

    /**
     * Treats a blank database name as no name, as Spring Boot treats an unset one; see
     * {@link BlankDatabaseNameRemover}.
     *
     * @return the post-processor; static, so it is registered before any configuration bean is created
     */
    @Bean
    static BeanPostProcessor blankMongoDatabaseNameRemover() {
        return new BlankDatabaseNameRemover();
    }

    /**
     * Applies the credential of {@link MongoDatabaseSettings#credential()}, after Spring Boot's
     * {@code StandardMongoClientSettingsBuilderCustomizer} (order 0) has applied the connection string built from
     * host, port and database, which carries no credential.
     */
    static final class DatabaseUserCredentialCustomizer implements MongoClientSettingsBuilderCustomizer, Ordered {

        /** Directly after Spring Boot's standard customizer, whose order is 0. */
        static final int ORDER = 1;

        private final MongoDatabaseSettings settings;

        DatabaseUserCredentialCustomizer(MongoDatabaseSettings settings) {
            this.settings = settings;
        }

        @Override
        public void customize(MongoClientSettings.Builder settingsBuilder) {
            settings.credential().ifPresent(settingsBuilder::credential);
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }

    /**
     * Sets a blank {@code database} of the bound {@link MongoProperties} to {@code null}. {@code application.yml}
     * binds it from {@code database.name}, so an empty {@code database.name} binds an empty name, which Spring Boot's
     * database factory would pass on, failing startup with "Database name must not be empty", rather than take the
     * database of {@code spring.data.mongodb.uri} as it does for an unset name. Runs after the properties are bound;
     * a name with text is left unchanged.
     */
    static final class BlankDatabaseNameRemover implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof MongoProperties properties && !StringUtils.hasText(properties.getDatabase())) {
                properties.setDatabase(null);
            }
            return bean;
        }
    }

    /**
     * Matches when {@link MongoDatabaseSettings#missingSetting()} names a missing setting, as the environment
     * resolves the keys at startup.
     */
    static final class MongoDatabaseNotConfiguredCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return MongoDatabaseSettings.from(context.getEnvironment()).missingSetting().isPresent();
        }
    }
}
