/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteRestClient;
import com.mulesoft.examples.get_customer_list_from_netsuite.client.UpstreamSystem;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.CustomerHtmlMapper;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.DwXmlWriter;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.ParseTemplateRenderer;

/**
 * Unit test of {@link NetsuiteCustomerService}, the processor chain of {@code get-customer-list-from-netsuiteFlow}
 * ({@code get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml}, lines 9-35):
 * expression filter, query, "Customer List to XML" script, object-to-string and parse-template.
 *
 * <p>Only NetSuite is mocked: the mapper, the SC-4 writer and the SC-14 renderer are the real classes, so the page
 * is asserted byte for byte against the copied {@code customer/index.html}. The four customers are those of the
 * original {@code src/test/resources/example.xml}, in its order; that file's two-space indent and windows-1250
 * declaration are not expected, because the SC-4 writer indents with tabs and the template expression drops the
 * declaration line. Every expected value is written out by hand, never computed through the code under test.
 */
@ExtendWith(MockitoExtension.class)
class NetsuiteCustomerServiceTest {

    private static final String QUERY_A = "SELECT email, firstname, lastname FROM customer"
            + " WHERE LOWER(lastname) LIKE LOWER('a%') ORDER BY lastname ASC";

    private static final String QUERY_O_BRIEN = "SELECT email, firstname, lastname FROM customer"
            + " WHERE LOWER(lastname) LIKE LOWER('O''Brien%') ORDER BY lastname ASC";

    private static final String QUERY_ANY = "SELECT email, firstname, lastname FROM customer"
            + " WHERE LOWER(lastname) LIKE LOWER('%') ORDER BY lastname ASC";

    /** The assertion of the original {@code GetCustomersListFromNetsuiteIT}. */
    private static final String HEADING = "<h1>Netsuite Customer List</h1>";

    /** The script's XML for the four example customers, without the declaration line the template drops. */
    private static final String EXPECTED_ROWS_BODY = "<div>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Abbott</td>\n"
            + "\t\t\t<td>Byron</td>\n"
            + "\t\t\t<td>ab@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Norman</td>\n"
            + "\t\t\t<td>Gustaffson</td>\n"
            + "\t\t\t<td>gustaff@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>Analytics</td>\n"
            + "\t\t\t<td>Angels</td>\n"
            + "\t\t\t<td>aa@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "\t<line>\n"
            + "\t\t<tr>\n"
            + "\t\t\t<td>McGill</td>\n"
            + "\t\t\t<td>Stuart</td>\n"
            + "\t\t\t<td>mcgill@example.com</td>\n"
            + "\t\t</tr>\n"
            + "\t</line>\n"
            + "</div>";

    @Mock
    private NetsuiteRestClient client;

    private NetsuiteCustomerService service;

    @BeforeEach
    void setUp() {
        service = new NetsuiteCustomerService(client, new CustomerHtmlMapper(new DwXmlWriter()),
                new ParseTemplateRenderer());
    }

    @Test
    void requestWithoutLastNameIsRejectedWithoutCallingNetsuite() throws Exception {
        assertThat(service.customerListPage(null)).isEmpty();

        verifyNoInteractions(client);
    }

    @Test
    void emptyLastNamePassesTheFilterAndMatchesEveryCustomer() throws Exception {
        when(client.suiteQl(anyString())).thenReturn(List.of());

        assertThat(service.customerListPage("")).isPresent();

        verify(client).suiteQl(QUERY_ANY);
        verifyNoMoreInteractions(client);
    }

    @Test
    void customerRowsAreRenderedIntoTheTemplateInQueryOrder() throws Exception {
        when(client.suiteQl(anyString())).thenReturn(List.of(
                row("Abbott", "Byron", "ab@example.com"),
                row("Norman", "Gustaffson", "gustaff@example.com"),
                row("Analytics", "Angels", "aa@example.com"),
                row("McGill", "Stuart", "mcgill@example.com")));

        Optional<String> page = service.customerListPage("a");

        String template = template();
        assertThat(template).containsOnlyOnce(NetsuiteCustomerService.TEMPLATE_EXPRESSION);
        assertThat(page).isPresent();
        assertThat(page.get())
                .isEqualTo(template.replace(NetsuiteCustomerService.TEMPLATE_EXPRESSION, EXPECTED_ROWS_BODY))
                .contains(HEADING)
                .contains("\t\t<tbody><div>\n\t<line>")
                .doesNotContain("<?xml")
                .doesNotContain("#[")
                .endsWith("</div></tbody>\n\t</table>\n</body>\n</html>")
                .doesNotEndWith("\n");
        verify(client).suiteQl(QUERY_A);
        verifyNoMoreInteractions(client);
    }

    @Test
    void emptyResultRendersTheNoCustomersRow() throws Exception {
        when(client.suiteQl(anyString())).thenReturn(List.of());

        Optional<String> page = service.customerListPage("a");

        assertThat(page).isPresent();
        assertThat(page.get())
                .contains("<td>No customers found</td>")
                .contains(HEADING)
                .doesNotContain("<?xml")
                .doesNotContain("#[");
    }

    @Test
    void customerQueryIsTheSuiteQlEquivalentOfTheFlowsDsql() {
        assertThat(NetsuiteCustomerService.customerQuery("a")).isEqualTo(QUERY_A);
        assertThatThrownBy(() -> NetsuiteCustomerService.customerQuery(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void quoteInTheLastNameIsEscapedInTheQuerySentToNetsuite() throws Exception {
        assertThat(NetsuiteCustomerService.customerQuery("O'Brien"))
                .contains("'O''Brien%'")
                .isEqualTo(QUERY_O_BRIEN);
        when(client.suiteQl(anyString())).thenReturn(List.of());

        service.customerListPage("O'Brien");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(client).suiteQl(query.capture());
        assertThat(query.getValue()).isEqualTo(QUERY_O_BRIEN);
    }

    static Stream<Arguments> payloadsWithLinesAfterTheFirst() {
        return Stream.of(
                Arguments.of("<?xml version='1.0' encoding='UTF-8'?>\n<div>\n\t<line/>\n</div>",
                        "<div>\n\t<line/>\n</div>"),
                Arguments.of("decl\n\n<div>\n\n</div>\n", "<div>\n</div>"),
                Arguments.of("\ndecl\n<div>", "<div>"));
    }

    @ParameterizedTest
    @MethodSource("payloadsWithLinesAfterTheFirst")
    void tableBodyDropsTheFirstTokenAndJoinsTheRestWithLineFeeds(String payload, String expected) {
        assertThat(NetsuiteCustomerService.tableBody(payload)).isEqualTo(expected);
    }

    /*
     * Expects the failure rather than an empty string: Groovy's list[1..-1] on a list of fewer than two elements
     * throws IndexOutOfBoundsException, and the service reproduces the template expression as Groovy evaluated it.
     * The page path never reaches this, because the script's XML always has lines after its declaration.
     */
    @ParameterizedTest
    @ValueSource(strings = {"<?xml version='1.0' encoding='UTF-8'?>", "", "\n\n"})
    void tableBodyFailsLikeGroovyWhenNoLineFollowsTheFirst(String payload) {
        assertThatThrownBy(() -> NetsuiteCustomerService.tableBody(payload))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    static Stream<RuntimeException> upstreamFailures() {
        return Stream.of(
                new RuntimeException("boom"),
                new UpstreamUnavailableException(UpstreamSystem.NETSUITE, "NetSuite unavailable",
                        new ConnectException("Connection refused")));
    }

    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void upstreamFailurePropagatesUnchanged(RuntimeException failure) throws Exception {
        when(client.suiteQl(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> service.customerListPage("a")).isSameAs(failure);
    }

    @Test
    void templateConstantsNameTheOriginalTemplateAndItsExpression() throws Exception {
        assertThat(NetsuiteCustomerService.TEMPLATE_LOCATION).isEqualTo("customer/index.html");
        assertThat(NetsuiteCustomerService.TEMPLATE_EXPRESSION)
                .isEqualTo("#[groovy: payload.tokenize('\\n')[1..-1].join('\\n')]")
                .doesNotContain("\n");
        assertThat(template()).contains("\t\t<tbody>" + NetsuiteCustomerService.TEMPLATE_EXPRESSION + "</tbody>\n");
    }

    private static Map<String, Object> row(String lastName, String firstName, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lastname", lastName);
        row.put("firstname", firstName);
        row.put("email", email);
        return row;
    }

    private static String template() throws IOException {
        InputStream stream = NetsuiteCustomerServiceTest.class.getClassLoader()
                .getResourceAsStream("customer/index.html");
        assertNotNull(stream, "classpath template customer/index.html");
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
