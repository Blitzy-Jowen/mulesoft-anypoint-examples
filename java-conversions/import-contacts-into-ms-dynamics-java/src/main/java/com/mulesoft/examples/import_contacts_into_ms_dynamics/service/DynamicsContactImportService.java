/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper.DwCsvReader;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper.DynamicsContactMapper;

/**
 * Body of the Mule flow {@code crm-create-account} of {@code import-contacts-into-ms-dynamics.xml} (lines 6-31):
 * every processor after the {@code file:inbound-endpoint} of line 7, which {@code trigger/ContactsFileTrigger}
 * replaces. {@link #importContacts(byte[])} runs them in the flow's order: the {@code application/csv} input of the
 * {@code CSV To Map} transform (lines 8-19), its {@code %dw 1.0} script, then the {@code For Each} scope (lines 21-30)
 * with one {@code dynamicscrm:create logicalName="contact"} per record, so each record is one
 * {@link DynamicsWebApiClient#createContact(Map)} call. Field renaming belongs to {@link DynamicsContactMapper} alone;
 * this class only sequences the calls.
 *
 * <p>The flow declares no exception strategy, no transaction and no logger, so nothing is caught, wrapped, retried
 * or logged above DEBUG here. A failure of the reader, the mapper or the client propagates as the same instance and
 * ends the loop, leaving the remaining records unsent, as a failure inside Mule's {@code foreach} ended the flow:
 * <ul>
 *   <li>the SC-9 upstream exceptions for an authentication, rate-limit or unavailable failure, already classified
 *       and logged at ERROR with upstream {@code Microsoft Dynamics 365} by the client's executor, which also made the
 *       one re-authenticated retry after a 401 and never retries a timeout or connectivity failure;</li>
 *   <li>the vendor's {@code WebClientResponseException}, for example a Dataverse 400, 403 or 5xx, rethrown unchanged
 *       by that executor;</li>
 *   <li>the {@link IllegalStateException} naming a blank {@code dynamics.*} setting, raised before any request.</li>
 * </ul>
 * Contacts created before the failure are not rolled back, and the file is never retried: the file poller logs the
 * failure at ERROR and still moves the file to its {@code moveToDirectory}, so no completed Dataverse write is
 * repeated.
 *
 * <p>Stateless apart from its collaborators, and therefore thread-safe.
 */
@Service
public class DynamicsContactImportService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicsContactImportService.class);

    private final DwCsvReader reader;
    private final DynamicsContactMapper mapper;
    private final DynamicsWebApiClient client;

    /**
     * Creates the service.
     *
     * @param reader the DataWeave 1.0 CSV reader, the {@code application/csv} input of the {@code CSV To Map}
     *               transform
     * @param mapper the owner of the flow's {@code CSV To Map} script and of the {@code Create Contact} attributes
     * @param client the only path to Dataverse, the {@code dynamicscrm:config} of the flow
     */
    public DynamicsContactImportService(DwCsvReader reader, DynamicsContactMapper mapper,
            DynamicsWebApiClient client) {
        this.reader = reader;
        this.mapper = mapper;
        this.client = client;
    }

    /**
     * Imports one polled CSV file as Microsoft Dynamics 365 contacts, one create per record in file order.
     *
     * <p>Records are neither filtered, sorted nor de-duplicated. A file with a header row and no data records makes
     * no Dataverse call and returns {@code 0}. The created contact ids are not inspected, because the original flow
     * ignored the operation's result.
     *
     * @param csv the bytes of the polled file, UTF-8 encoded CSV with a header row
     * @return the number of {@code createContact} calls that completed
     * @throws Exception any failure of reading, mapping or creating, unchanged and as the same instance: an SC-9
     *                   upstream exception, the vendor's exception, or the {@link IllegalStateException} of a blank
     *                   {@code dynamics.*} setting; the records after the failing one are not sent
     */
    public int importContacts(byte[] csv) throws Exception {
        List<Map<String, String>> rows = reader.read(csv);
        List<Map<String, Object>> records = mapper.map(rows);
        LOG.debug("Creating {} Dataverse contact record(s), one request each", records.size());
        int created = 0;
        for (Map<String, Object> record : records) {
            LOG.debug("Creating Dataverse contact for record {} of {}", created + 1, records.size());
            String contactId = client.createContact(mapper.toContactAttributes(record));
            created++;
            LOG.debug("Created Dataverse contact for record {}: id {}", created, contactId);
        }
        return created;
    }
}
