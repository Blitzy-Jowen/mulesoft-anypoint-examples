/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import java.util.Optional;

import org.springframework.core.env.PropertyResolver;
import org.springframework.util.StringUtils;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;

/**
 * The MongoDB settings of the original {@code mongo:config} (database, user, password) as the environment resolves
 * them, together with the two Spring Boot keys that change how they apply, {@code spring.data.mongodb.uri} and
 * {@code spring.data.mongodb.authentication-database}.
 *
 * <p>{@link MongoConfig} and its startup condition read the settings through this class alone, so the decision
 * whether the application has a MongoDB database and the credential its client receives always agree:
 * <ul>
 *   <li>The database name is {@code spring.data.mongodb.database}, which {@code application.yml} binds from
 *       {@code database.name}, when it has text; otherwise, as in Spring Boot, the database of
 *       {@code spring.data.mongodb.uri} when the URI is set.</li>
 *   <li>Without a URI, {@code database.user} and {@code database.password} are supplied together, giving the
 *       client's credential, or not at all, for a server without access control. With a URI they are not used: the
 *       URI alone gives the server and credentials, as in Spring Boot.</li>
 *   <li>The credential authenticates against {@code spring.data.mongodb.authentication-database} when it has text,
 *       otherwise against the database name, the source Spring Boot uses for a user it is given.</li>
 * </ul>
 * A value that is absent, empty or whitespace only counts as not supplied. The password is read only to build the
 * credential; the class has no {@code toString()} of its own and never logs.
 */
final class MongoDatabaseSettings {

    /** Spring Boot's database key, bound in {@code application.yml} from {@code database.name}. */
    static final String DATABASE_KEY = "spring.data.mongodb.database";

    /** Spring Boot's connection string key; once set, Spring Boot ignores host, port and credentials. */
    static final String URI_KEY = "spring.data.mongodb.uri";

    /** Spring Boot's key naming the database a user authenticates against. */
    static final String AUTHENTICATION_DATABASE_KEY = "spring.data.mongodb.authentication-database";

    /**
     * A setting without which the application has no usable MongoDB database, named by its original key and the
     * environment variable of {@code application.yml} that supplies it.
     */
    enum MissingSetting {

        /** No database name: neither {@code database.name} nor the database of a connection string. */
        DATABASE_NAME("database.name", "DATABASE_NAME", ""),

        /** A password without the user it belongs to. */
        DATABASE_USER("database.user", "DATABASE_USER", " while database.password is"),

        /** A user without a password. */
        DATABASE_PASSWORD("database.password", "DATABASE_PASSWORD", " while database.user is");

        private final String key;
        private final String environmentVariable;
        private final String counterpart;

        MissingSetting(String key, String environmentVariable, String counterpart) {
            this.key = key;
            this.environmentVariable = environmentVariable;
            this.counterpart = counterpart;
        }

        /**
         * Returns the original key of the setting.
         *
         * @return the key, for example {@code database.name}
         */
        String key() {
            return key;
        }

        /**
         * Returns the message of every failed database access while the setting is missing.
         *
         * @return {@code "<key> is not configured"}
         */
        String notConfiguredMessage() {
            return key + " is not configured";
        }

        /**
         * Returns the single startup warning, which names the key and its environment variable and no value.
         *
         * @return the warning text
         */
        String startupWarning() {
            return key + " (environment variable " + environmentVariable + ") is not configured" + counterpart
                    + ": the application starts without a MongoDB connection, and every import fails until it is "
                    + "supplied";
        }
    }

    private final String databaseName;
    private final boolean uriSet;
    private final boolean uriUnreadable;
    private final String user;
    private final String password;
    private final String authenticationDatabase;

    private MongoDatabaseSettings(String databaseName, boolean uriSet, boolean uriUnreadable, String user,
            String password, String authenticationDatabase) {
        this.databaseName = databaseName;
        this.uriSet = uriSet;
        this.uriUnreadable = uriUnreadable;
        this.user = user;
        this.password = password;
        this.authenticationDatabase = authenticationDatabase;
    }

    /**
     * Reads the settings from {@code environment}.
     *
     * <p>A connection string the MongoDB driver cannot parse yields no database name here and is not reported as a
     * missing setting: Spring Boot rejects the same string at startup with the driver's own message.
     *
     * @param environment the environment, or a condition's view of it
     * @return the settings as resolved now
     */
    static MongoDatabaseSettings from(PropertyResolver environment) {
        String uri = environment.getProperty(URI_KEY);
        boolean uriSet = StringUtils.hasText(uri);
        String databaseName = environment.getProperty(DATABASE_KEY);
        boolean uriUnreadable = false;
        if (!StringUtils.hasText(databaseName) && uriSet) {
            try {
                databaseName = new ConnectionString(uri).getDatabase();
            } catch (IllegalArgumentException malformed) {
                databaseName = null;
                uriUnreadable = true;
            }
        }
        return new MongoDatabaseSettings(StringUtils.hasText(databaseName) ? databaseName : null, uriSet,
                uriUnreadable, environment.getProperty("database.user"), environment.getProperty("database.password"),
                environment.getProperty(AUTHENTICATION_DATABASE_KEY));
    }

    /**
     * Returns the setting the application lacks, checked in the order database name, then the credential pair.
     *
     * @return the first missing setting, or empty when the application has a usable MongoDB database
     */
    Optional<MissingSetting> missingSetting() {
        if (databaseName == null && !uriUnreadable) {
            return Optional.of(MissingSetting.DATABASE_NAME);
        }
        if (!uriSet) {
            boolean hasUser = StringUtils.hasText(user);
            boolean hasPassword = StringUtils.hasText(password);
            if (hasUser && !hasPassword) {
                return Optional.of(MissingSetting.DATABASE_PASSWORD);
            }
            if (!hasUser && hasPassword) {
                return Optional.of(MissingSetting.DATABASE_USER);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the credential the MongoDB client authenticates with.
     *
     * @return the credential of {@code database.user} and {@code database.password}, authenticating against the
     *         authentication database or else the database name; empty when a connection string is set, when either
     *         value is not supplied, or when no database is named to authenticate against
     */
    Optional<MongoCredential> credential() {
        if (uriSet || !StringUtils.hasText(user) || !StringUtils.hasText(password)) {
            return Optional.empty();
        }
        String source = StringUtils.hasText(authenticationDatabase) ? authenticationDatabase : databaseName;
        if (source == null) {
            return Optional.empty();
        }
        return Optional.of(MongoCredential.createCredential(user, source, password.toCharArray()));
    }
}
