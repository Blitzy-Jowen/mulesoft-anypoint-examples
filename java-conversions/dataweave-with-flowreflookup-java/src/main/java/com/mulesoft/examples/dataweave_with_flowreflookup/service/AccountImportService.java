/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.SalesforcePartnerClient;
import com.mulesoft.examples.dataweave_with_flowreflookup.mapper.AccountMapper;
import com.mulesoft.examples.dataweave_with_flowreflookup.mapper.DwCsvReader;
import com.sforce.soap.partner.SaveResult;

/**
 * Body of the Mule flow {@code CreateNewSalesforceAccountFlow} of {@code dataweave-with-flowref.xml}: every processor
 * after the {@code file:inbound-endpoint}, which {@code trigger/CompaniesFileTrigger} replaces.
 * {@link #importAccounts(byte[])} runs them in the flow's order (lines 8 to 23): the CSV input of {@code CSV to Maps},
 * its {@code %dw 1.0} script with the per-record {@code LookUpSalesRegionFlow} lookup, then
 * {@code sfdc:create type="Account"} with the whole mapped list.
 *
 * <p>The flow declares no logger and no exception strategy, so nothing is caught, wrapped or logged here: every
 * failure reaches the file poller, which logs it at ERROR and still moves the file, as Mule's default strategy and the
 * streaming file connector did. The approved Salesforce failure handling (re-login and one retry on a rejected
 * session, no retry on a rate limit, timeout or connectivity failure) already ran inside the client's SC-9 executor,
 * so this class retries nothing itself and no Account write is ever repeated by it.
 *
 * <p>Stateless apart from its collaborators, and therefore thread-safe.
 */
@Service
public class AccountImportService {

    /** The sObject type {@code sfdc:create} created. */
    public static final String ACCOUNT_TYPE = "Account";

    private final DwCsvReader dwCsvReader;
    private final AccountMapper accountMapper;
    private final SalesforcePartnerClient salesforcePartnerClient;

    /**
     * Creates the service.
     *
     * @param dwCsvReader the DataWeave 1.0 CSV reader, the {@code application/csv} input of the flow's transform
     * @param accountMapper the owner of the flow's {@code CSV to Maps} script
     * @param salesforcePartnerClient the only path to Salesforce, the {@code sfdc:config} of the flow
     */
    public AccountImportService(DwCsvReader dwCsvReader, AccountMapper accountMapper,
            SalesforcePartnerClient salesforcePartnerClient) {
        this.dwCsvReader = dwCsvReader;
        this.accountMapper = accountMapper;
        this.salesforcePartnerClient = salesforcePartnerClient;
    }

    /**
     * Imports one polled CSV file as Salesforce Accounts.
     *
     * <p>The rows are mapped in file order, each one's region looked up while it is mapped, so the lookup's console
     * and log lines interleave record by record as in the original. All records then go to Salesforce in one
     * {@code create} call, as {@code sfdc:create} sent {@code #[payload]}: they are neither chunked, filtered nor
     * de-duplicated. A file without data records still makes that call, with an empty list. The save results are
     * returned as the client produced them and are not inspected, because the original flow ignored them.
     *
     * @param csv the bytes of the polled file, UTF-8 encoded CSV with a header row
     * @return the client's save results, unchanged
     * @throws Exception any failure of reading, mapping or creating, unchanged: an SC-9 upstream exception or the
     *                   original WSC exception
     */
    public List<SaveResult> importAccounts(byte[] csv) throws Exception {
        List<Map<String, String>> rows = dwCsvReader.read(csv);
        List<Map<String, Object>> accounts = accountMapper.map(rows);
        return salesforcePartnerClient.create(ACCOUNT_TYPE, accounts);
    }
}
