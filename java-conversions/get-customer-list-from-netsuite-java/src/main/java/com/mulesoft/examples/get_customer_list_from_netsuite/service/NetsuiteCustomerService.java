/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.StringTokenizer;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteRestClient;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.CustomerHtmlMapper;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.ParseTemplateRenderer;

/**
 * The processor chain of {@code get-customer-list-from-netsuiteFlow}
 * ({@code get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml}, lines 9-35), run in
 * its original order:
 * <ol>
 *   <li>{@code expression-filter} on the {@code lastName} query parameter, without {@code throwOnUnaccepted}: a
 *       request without it ends the flow with no payload ({@link Optional#empty()});</li>
 *   <li>{@code netsuite:query-records}: the SuiteQL equivalent of the DSQL query, through
 *       {@link NetsuiteRestClient#suiteQl(String)};</li>
 *   <li>the {@code %dw 1.0} "Customer List to XML" script, owned by {@link CustomerHtmlMapper};</li>
 *   <li>{@code object-to-string-transformer mimeType="text/html"}: the XML already is a {@code String}; the
 *       controller writes the {@code text/html} content type;</li>
 *   <li>{@code parse-template} of {@code customer/index.html}, whose one Groovy expression {@link #tableBody(String)}
 *       computes.</li>
 * </ol>
 * The flow declares no exception strategy, so every exception, the SC-9 upstream exceptions included, propagates
 * unchanged to the project's exception handlers. Stateless and thread-safe.
 */
@Service
public class NetsuiteCustomerService {

    /** Classpath location of the {@code parse-template} page. */
    public static final String TEMPLATE_LOCATION = "customer/index.html";

    /**
     * The template's only expression, byte for byte as it stands between {@code <tbody>} and {@code </tbody>}: the
     * template holds a backslash followed by {@code n}, not a line feed.
     */
    public static final String TEMPLATE_EXPRESSION = "#[groovy: payload.tokenize('\\n')[1..-1].join('\\n')]";

    private static final String LINE_FEED = "\n";

    private final NetsuiteRestClient client;

    private final CustomerHtmlMapper mapper;

    private final ParseTemplateRenderer renderer;

    /**
     * Creates the service.
     *
     * @param client   runs the customer query against NetSuite
     * @param mapper   the "Customer List to XML" script
     * @param renderer renders {@link #TEMPLATE_LOCATION}
     */
    public NetsuiteCustomerService(NetsuiteRestClient client, CustomerHtmlMapper mapper,
            ParseTemplateRenderer renderer) {
        this.client = client;
        this.mapper = mapper;
        this.renderer = renderer;
    }

    /**
     * Produces the customer list page for the customers whose last name starts with {@code lastName}.
     *
     * @param lastName the {@code lastName} query parameter; {@code null} when the request carries none, while an
     *                 empty value is accepted and matches every customer, as the original's {@code like '%'} did
     * @return the rendered page, or {@link Optional#empty()} when the filter rejects the request (no
     *         {@code lastName}), in which case NetSuite is not called
     */
    public Optional<String> customerListPage(String lastName) {
        if (lastName == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = client.suiteQl(customerQuery(lastName));
        String xml = mapper.toXml(rows);
        String body = tableBody(xml);
        return Optional.of(renderer.render(TEMPLATE_LOCATION, Map.of(TEMPLATE_EXPRESSION, body)));
    }

    /**
     * Builds the SuiteQL equivalent of the flow's DSQL
     * {@code SELECT email,firstName,lastName FROM CUSTOMER WHERE lastName like '<lastName>%' ORDER BY lastName ASC}.
     *
     * <p>{@code LOWER} on both sides reproduces the case-insensitive SuiteTalk {@code startsWith} search the
     * connector compiled the DSQL {@code like} into. The prefix is escaped with
     * {@link NetsuiteRestClient#suiteQlLiteral(String)}, so a quote cannot end the literal; {@code %} and {@code _}
     * keep their wildcard meaning, so every legitimate prefix selects the rows the original selected.
     *
     * @param prefix the last-name prefix, not {@code null}
     * @return the SuiteQL statement
     * @throws NullPointerException when {@code prefix} is {@code null}
     */
    public static String customerQuery(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return "SELECT email, firstname, lastname FROM customer WHERE LOWER(lastname) LIKE LOWER('"
                + NetsuiteRestClient.suiteQlLiteral(prefix) + "%') ORDER BY lastname ASC";
    }

    /**
     * Computes the template expression {@code payload.tokenize('\n')[1..-1].join('\n')} in Java, with no script
     * engine: the payload is split on line feeds with Groovy {@code tokenize} semantics, which drop empty tokens,
     * the first token (the XML declaration) is dropped, and the rest are joined with line feeds, with none after
     * the last.
     *
     * @param xml the payload, the XML document of {@link CustomerHtmlMapper#toXml(List)}
     * @return the document without its first line
     * @throws IndexOutOfBoundsException when the payload has fewer than two non-empty lines, where Groovy's
     *         {@code [1..-1]} fails as well
     */
    public static String tableBody(String xml) {
        StringTokenizer tokens = new StringTokenizer(xml, LINE_FEED);
        int count = tokens.countTokens();
        if (count < 2) {
            throw new IndexOutOfBoundsException(
                    "The payload had fewer than two lines (" + count + "), so it has no lines after the first");
        }
        tokens.nextToken();
        StringJoiner body = new StringJoiner(LINE_FEED);
        while (tokens.hasMoreTokens()) {
            body.add(tokens.nextToken());
        }
        return body.toString();
    }
}
