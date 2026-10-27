/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient;
import com.mulesoft.examples.import_contacts_into_salesforce.mapper.ContactMapper;
import com.mulesoft.examples.import_contacts_into_salesforce.mapper.DwCsvReader;
import com.sforce.soap.partner.SaveResult;

/**
 * Body of the Mule flow {@code Contacts_to_SFDC} of {@code contacts-to-SFDC.xml}: every processor after the
 * {@code file:inbound-endpoint}, which {@code trigger/ContactsFileTrigger} replaces. {@link #importContacts(byte[])}
 * runs them in the flow's order: the CSV input of {@code Map CSV to Contacts}, its {@code %dw 1.0} script, then
 * {@code sfdc:create type="Contact"} with the whole mapped list.
 *
 * <p>The flow declares no exception strategy, so nothing is caught, wrapped or logged here: every failure reaches the
 * file poller, which logs it at ERROR and still moves the file, as Mule's default strategy and the streaming file
 * connector did. The approved Salesforce failure handling (re-login and one retry on a rejected session, no retry on a
 * rate limit, timeout or connectivity failure) already ran inside the client's SC-9 executor, so this class retries
 * nothing itself.
 *
 * <p>Stateless apart from its collaborators, and therefore thread-safe.
 */
@Service
public class ContactImportService {

    /** The sObject type {@code sfdc:create} created. */
    public static final String CONTACT_TYPE = "Contact";

    private static final Logger log = LoggerFactory.getLogger(ContactImportService.class);

    private final DwCsvReader dwCsvReader;
    private final ContactMapper contactMapper;
    private final SalesforcePartnerClient salesforcePartnerClient;

    /**
     * Creates the service.
     *
     * @param dwCsvReader the DataWeave 1.0 CSV reader, the {@code dw:input-payload} of the flow
     * @param contactMapper the owner of the flow's {@code Map CSV to Contacts} script
     * @param salesforcePartnerClient the only path to Salesforce, the {@code sfdc:config} of the flow
     */
    public ContactImportService(DwCsvReader dwCsvReader, ContactMapper contactMapper,
            SalesforcePartnerClient salesforcePartnerClient) {
        this.dwCsvReader = dwCsvReader;
        this.contactMapper = contactMapper;
        this.salesforcePartnerClient = salesforcePartnerClient;
    }

    /**
     * Imports one polled CSV file as Salesforce Contacts.
     *
     * <p>All records go to Salesforce in one {@code create} call, as {@code sfdc:create} sent {@code #[payload]}: they
     * are neither chunked, filtered nor de-duplicated. A file without data records still makes that call, with an
     * empty list. The save results are returned as the client produced them and are not inspected, because the
     * original flow ignored them.
     *
     * @param csv the bytes of the polled file, UTF-8 encoded CSV with a header row
     * @return the client's save results, unchanged
     * @throws Exception any failure of reading, mapping or creating, unchanged: an SC-9 upstream exception or the
     *                   original WSC exception
     */
    public List<SaveResult> importContacts(byte[] csv) throws Exception {
        List<Map<String, String>> rows = dwCsvReader.read(csv);
        List<Map<String, Object>> contacts = contactMapper.map(rows);
        log.debug("Creating {} {} record(s) in Salesforce", contacts.size(), CONTACT_TYPE);
        return salesforcePartnerClient.create(CONTACT_TYPE, contacts);
    }
}
