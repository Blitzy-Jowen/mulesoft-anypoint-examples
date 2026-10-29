/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;

import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.ClientSession;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config.MongoDatabaseSettings.MissingSetting;

/**
 * Tests {@link UnconfiguredMongoDatabaseFactory}: every database and session access fails with an
 * {@link IllegalStateException} whose message is {@code "<key> is not configured"} for each missing setting, thrown
 * in the method called, so the file poller's ERROR line, which names the throw site and never the message, points
 * at that method. The translator and codec registry the {@code MongoTemplate} needs are supplied without any
 * database access.
 */
class UnconfiguredMongoDatabaseFactoryTest {

    /** Each access of the factory that needs a MongoDB client: its signature, the method that must throw, the call. */
    private static Stream<Arguments> accesses() {
        return Stream.of(
                Arguments.of("getMongoDatabase()", "getMongoDatabase",
                        (Consumer<UnconfiguredMongoDatabaseFactory>) UnconfiguredMongoDatabaseFactory::getMongoDatabase),
                Arguments.of("getMongoDatabase(String)", "getMongoDatabase",
                        (Consumer<UnconfiguredMongoDatabaseFactory>) factory -> factory.getMongoDatabase("customers")),
                Arguments.of("getSession(ClientSessionOptions)", "getSession",
                        (Consumer<UnconfiguredMongoDatabaseFactory>) factory -> factory.getSession(
                                ClientSessionOptions.builder().build())),
                Arguments.of("withSession(ClientSession)", "withSession",
                        (Consumer<UnconfiguredMongoDatabaseFactory>) factory -> factory.withSession(
                                Mockito.mock(ClientSession.class))));
    }

    static Stream<Arguments> missingSettingsAndAccesses() {
        return Arrays.stream(MissingSetting.values()).flatMap(setting -> accesses().map(access -> {
            Object[] parts = access.get();
            return Arguments.of(setting, parts[0], parts[1], parts[2]);
        }));
    }

    /**
     * @param setting   the missing setting the factory reports
     * @param signature the access, naming the case in the report
     * @param method    the factory method that must throw
     * @param access    the call of that method
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("missingSettingsAndAccesses")
    void everyAccessFailsNamingTheMissingKeyAtItsOwnThrowSite(MissingSetting setting, String signature,
            String method, Consumer<UnconfiguredMongoDatabaseFactory> access) {
        UnconfiguredMongoDatabaseFactory factory = new UnconfiguredMongoDatabaseFactory(setting);

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> access.accept(factory), signature);

        assertEquals(setting.key() + " is not configured", failure.getMessage());
        assertNull(failure.getCause());
        StackTraceElement throwSite = failure.getStackTrace()[0];
        assertEquals(UnconfiguredMongoDatabaseFactory.class.getName(), throwSite.getClassName());
        assertEquals(method, throwSite.getMethodName());
    }

    @Test
    void suppliesTheTemplatesTranslatorAndTheDriversDefaultCodecsWithoutDatabaseAccess() {
        UnconfiguredMongoDatabaseFactory factory = new UnconfiguredMongoDatabaseFactory(MissingSetting.DATABASE_NAME);

        assertInstanceOf(MongoExceptionTranslator.class, factory.getExceptionTranslator());
        assertSame(factory.getExceptionTranslator(), factory.getExceptionTranslator());
        assertSame(MongoClientSettings.getDefaultCodecRegistry(), factory.getCodecRegistry());
    }

    @Test
    void refusesToBeCreatedWithoutAMissingSetting() {
        assertThrows(IllegalArgumentException.class, () -> new UnconfiguredMongoDatabaseFactory(null));
    }
}
