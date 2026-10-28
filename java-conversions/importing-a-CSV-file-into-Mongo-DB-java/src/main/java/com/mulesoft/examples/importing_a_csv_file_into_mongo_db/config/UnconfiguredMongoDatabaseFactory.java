/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import org.bson.codecs.configuration.CodecRegistry;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.util.Assert;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config.MongoDatabaseSettings.MissingSetting;

/**
 * The {@code MongoDatabaseFactory} {@link MongoConfig} supplies while a setting of the original {@code mongo:config}
 * is missing: {@code database.name}, or one of {@code database.user} and {@code database.password} without the
 * other.
 *
 * <p>Without it, Spring Boot stops the application at startup: an empty password cannot bind to its
 * {@code char[]} password, an empty user makes the connection string it builds unparseable, and an empty database
 * name fails its database factory. With it, Spring Boot's MongoDB client configuration backs off, so no
 * {@code MongoClient} exists and no connection is opened, while the {@code MongoTemplate} built over this factory
 * starts normally. Every database access then fails with an {@link IllegalStateException} whose message is
 * {@code "<key> is not configured"}, thrown in the method called, so the file poller's ERROR line names this class
 * and that method as the throw site.
 *
 * <p>The exception translator and the driver's default codec registry are supplied as a configured factory supplies
 * them, because building the template and converting documents need them and neither opens a connection. The class
 * never logs: the startup warning belongs to {@link MongoConfig}, and each failed import is logged by the poller.
 */
final class UnconfiguredMongoDatabaseFactory implements MongoDatabaseFactory {

    private final MissingSetting missingSetting;

    private final PersistenceExceptionTranslator exceptionTranslator = new MongoExceptionTranslator();

    /**
     * Creates the factory.
     *
     * @param missingSetting the setting every database access reports as not configured
     */
    UnconfiguredMongoDatabaseFactory(MissingSetting missingSetting) {
        Assert.notNull(missingSetting, "missingSetting must not be null");
        this.missingSetting = missingSetting;
    }

    /**
     * Refuses the configured database, since the MongoDB connection is not configured.
     *
     * @return never
     * @throws IllegalStateException always, with {@code "<key> is not configured"}
     */
    @Override
    public MongoDatabase getMongoDatabase() {
        throw new IllegalStateException(missingSetting.notConfiguredMessage());
    }

    /**
     * Refuses the named database, since the MongoDB connection is not configured.
     *
     * @param dbName ignored
     * @return never
     * @throws IllegalStateException always, with {@code "<key> is not configured"}
     */
    @Override
    public MongoDatabase getMongoDatabase(String dbName) {
        throw new IllegalStateException(missingSetting.notConfiguredMessage());
    }

    /**
     * Returns the translator {@code MongoTemplate} takes at construction, the one a configured factory supplies.
     *
     * @return a {@link MongoExceptionTranslator}, the same one on every call
     */
    @Override
    public PersistenceExceptionTranslator getExceptionTranslator() {
        return exceptionTranslator;
    }

    /**
     * Returns the codecs a client built with the driver's default settings uses, without asking for a database.
     *
     * @return {@link MongoClientSettings#getDefaultCodecRegistry()}
     */
    @Override
    public CodecRegistry getCodecRegistry() {
        return MongoClientSettings.getDefaultCodecRegistry();
    }

    /**
     * Refuses the session, since no MongoDB client exists to start one.
     *
     * @param options ignored
     * @return never
     * @throws IllegalStateException always, with {@code "<key> is not configured"}
     */
    @Override
    public ClientSession getSession(ClientSessionOptions options) {
        throw new IllegalStateException(missingSetting.notConfiguredMessage());
    }

    /**
     * Refuses to bind a session, since no MongoDB client exists that could have started it.
     *
     * @param session ignored
     * @return never
     * @throws IllegalStateException always, with {@code "<key> is not configured"}
     */
    @Override
    public MongoDatabaseFactory withSession(ClientSession session) {
        throw new IllegalStateException(missingSetting.notConfiguredMessage());
    }
}
