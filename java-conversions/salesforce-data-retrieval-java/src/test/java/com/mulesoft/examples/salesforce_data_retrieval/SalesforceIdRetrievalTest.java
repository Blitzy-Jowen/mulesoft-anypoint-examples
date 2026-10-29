/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.salesforce_data_retrieval.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_retrieval.support.HttpTestClient;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;

/**
 * Port of the original example's {@code SalesforceIdRetrievalIT} functional test, keeping its two requests and its
 * assertions with their original semantics: the form page {@code GET /} must contain at least one {@code option}
 * tag ({@link Matcher#find()} of {@link #OPTION_REGEX}), and the query {@code POST /} of {@code object=user},
 * {@code field=email}, {@code searchKey=name}, {@code searchValue=mule} must contain every line of
 * {@code reply.txt}.
 *
 * <p>The original ran {@code salesforce-id-retrieval.xml} in an embedded Mule runtime against a live organization
 * whose credentials came from the never-committed {@code mule.test.properties}. Here the whole application runs on a
 * random port and ST-1 {@link HttpTestClient} sends the requests, while a Mockito {@link SalesforcePartnerClient}
 * stands in for Salesforce with the organization the original queried: the objects {@code User}, {@code Account}
 * and {@code Contact}, and the two users {@code reply.txt} records, as {@code HashMap}s carrying the sObject
 * {@code type} like the client's own record conversion. Everything between the HTTP request and the client (the
 * controller, the identifier checks, the SOQL, the option and record rendering, the template) is the production
 * code. No credential is needed and nothing leaves the JVM.
 *
 * <p>The same assertions run against a live sandbox in the failsafe {@code SalesforceIdRetrievalIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesforceIdRetrievalTest {

    private static final String OPTION_REGEX = "<option\\svalue=\\\".*\\\">.*<\\/option>";

    /** Lines of {@code reply.txt}, the original's expected query result. */
    private static final int REPLY_LINES = 6;

    /** The original MEL concatenation (xml:27) for the posted form. */
    private static final String EXPECTED_SOQL = "SELECT id, name ,email from user where name like '%mule%'";

    private static List<String> REPLY = List.of();

    @MockBean
    private SalesforcePartnerClient client;

    @LocalServerPort
    private int port;

    private HttpTestClient http;

    @BeforeAll
    static void init() throws IOException {
        REPLY = new ClassPathResource("reply.txt").getContentAsString(StandardCharsets.UTF_8).lines().toList();
        // Guards the contains loop of testGetData against passing vacuously on a truncated resource.
        assertEquals(REPLY_LINES, REPLY.size(), () -> "reply.txt lines: " + REPLY);
    }

    @BeforeEach
    void setUp() throws Exception {
        http = HttpTestClient.localhost(port);

        DescribeGlobalResult global = new DescribeGlobalResult();
        global.setSobjects(new DescribeGlobalSObjectResult[] {
            sObject("User", "User"), sObject("Account", "Account"), sObject("Contact", "Contact")});
        given(client.describeGlobal()).willReturn(global);

        DescribeSObjectResult user = new DescribeSObjectResult();
        user.setName("User");
        user.setFields(new Field[] {field("Id"), field("Name"), field("Email")});
        given(client.describeSObject(argThat(name -> "user".equalsIgnoreCase(name)))).willReturn(user);

        given(client.queryAll(anyString())).willReturn(List.of(
                userRecord("kicks+a@mulesoft.com", "00520000003LtYCAA0", "Mule orgA"),
                userRecord("kicks+a-integration@mulesoft.com", "00520000003LtvGAAS", "Mule orgA Integration")));
    }

    @Test
    void testDisplayData() {
        HttpTestClient.Response result = http.get("/");

        assertEquals(200, result.status(), () -> "unexpected response: " + result);
        Pattern p = Pattern.compile(OPTION_REGEX);
        Matcher m = p.matcher(result.bodyAsString());
        assertTrue(m.find(), "Payload should contain at least one option tag");
    }

    @Test
    void testGetData() throws Exception {
        HttpTestClient.Response result = http.post("/", "application/x-www-form-urlencoded",
                "object=user&field=email&searchKey=name&searchValue=mule");

        assertEquals(200, result.status(), () -> "unexpected response: " + result);
        String payload = result.bodyAsString();
        for (String line : REPLY) {
            assertTrue(payload.contains(line), () -> "missing reply line '" + line + "' in: " + payload);
        }
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(client).queryAll(soql.capture());
        assertEquals(EXPECTED_SOQL, soql.getValue());
    }

    private static DescribeGlobalSObjectResult sObject(String name, String label) {
        DescribeGlobalSObjectResult sObject = new DescribeGlobalSObjectResult();
        sObject.setName(name);
        sObject.setLabel(label);
        return sObject;
    }

    private static Field field(String name) {
        Field field = new Field();
        field.setName(name);
        return field;
    }

    /** A {@code queryAll} record of {@code SELECT id, name ,email from user} as the client converts it. */
    private static Map<String, Object> userRecord(String email, String id, String name) {
        Map<String, Object> record = new HashMap<>();
        record.put("type", "User");
        record.put("Id", id);
        record.put("Name", name);
        record.put("Email", email);
        return record;
    }
}
