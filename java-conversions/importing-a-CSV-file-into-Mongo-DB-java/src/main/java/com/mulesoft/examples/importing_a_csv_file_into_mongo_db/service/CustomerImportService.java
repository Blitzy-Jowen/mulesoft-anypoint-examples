/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerDocumentMapper;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.DwCsvReader;

/**
 * Body of the Mule flow {@code csv-to-mongodbFlow1} of {@code csv-to-mongodb.xml}: every processor after the
 * {@code file:inbound-endpoint}, which {@code trigger/CsvFileTrigger} replaces. The statements of
 * {@link #importCsv(byte[])} run in the flow's processor order: CSV to map, log, collection check, create-or-log
 * choice, then one save per record.
 *
 * <p>The original flow declares no exception strategy and no transaction, so nothing is caught here: any failure
 * reaches the file poller, which logs it and still consumes the file, as Mule's default strategy did.
 */
@Service
public class CustomerImportService {

    /** The MongoDB collection the flow checks, creates and saves into. */
    public static final String COLLECTION = "customers";

    private static final Logger log = LoggerFactory.getLogger(CustomerImportService.class);

    private final MongoTemplate mongoTemplate;
    private final DwCsvReader dwCsvReader;
    private final CustomerDocumentMapper customerDocumentMapper;

    /**
     * Creates the service over the auto-configured template, whose connection comes from the original
     * {@code database.*} placeholders.
     *
     * @param mongoTemplate the MongoDB access path of this project
     * @param dwCsvReader the DataWeave 1.0 CSV reader
     * @param customerDocumentMapper the owner of the flow's {@code CSV to Map} script
     */
    public CustomerImportService(MongoTemplate mongoTemplate, DwCsvReader dwCsvReader,
            CustomerDocumentMapper customerDocumentMapper) {
        this.mongoTemplate = mongoTemplate;
        this.dwCsvReader = dwCsvReader;
        this.customerDocumentMapper = customerDocumentMapper;
    }

    /**
     * Imports one polled CSV file into the {@value #COLLECTION} collection.
     *
     * <p>The collection check and the choice run even when the file holds no data record, as in the flow.
     *
     * @param content the bytes of the polled file, UTF-8 encoded CSV with a header row
     */
    public void importCsv(byte[] content) {
        List<Map<String, String>> rows = dwCsvReader.read(content);
        List<Map<String, Object>> records = customerDocumentMapper.map(rows);

        // The {} argument renders the list exactly as MEL's #[payload] did: List.toString() over LinkedHashMaps.
        log.info("saving objects: {}", records);

        // The enricher stored the check in flowVars['existsCollection'] and left the payload untouched.
        boolean existsCollection = mongoTemplate.collectionExists(COLLECTION);

        if (!existsCollection) {
            mongoTemplate.createCollection(COLLECTION);
        } else {
            log.info("Customer Collection already exists.");
        }

        for (Map<String, Object> record : records) {
            // A fresh Document per save: the save writes the generated _id into the object it is given, and the
            // logged records stay unmodified. A Document is a Mongo simple type, so no _class key is written.
            mongoTemplate.save(new Document(record), COLLECTION);
        }
    }
}
