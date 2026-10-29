/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.salesforce_data_retrieval.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.QueryResultFormatter;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.SObjectOptionsMapper;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;

/**
 * Orchestrates the two flows of {@code salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml}:
 * <ul>
 *   <li>{@link #showForm()} — {@code showFormFlow} (lines 7-24), GET {@code /}: {@code describe-global}, the
 *       {@code div/option} script, {@code object-to-string} and {@code parse-template} of
 *       {@code retrieval/index.html};</li>
 *   <li>{@link #query(Map)} — {@code salesforceDataRetrievalFlow} (lines 25-40), POST {@code /}: the MEL-built
 *       {@code query-all}, the record list rendered as text and the {@code Result: <payload>} INFO log.</li>
 * </ul>
 *
 * <p>Query hardening: the original concatenated the caller's form values into SOQL. Before any query runs, the
 * object name must be one {@code describeGlobal} lists, and {@code searchKey} and every {@code field} entry must be
 * a field {@code describeSObject} lists for that object, or a relationship path ({@code Owner.Name},
 * {@code Account.Owner.Name}) of at most five relationships whose every segment Salesforce describes: each
 * relationship is followed to the {@code describeSObject} fields of the types its reference field refers to, and the
 * last segment must be a field of one of them. {@code searchValue} is escaped inside its string literal. Legitimate
 * input yields the same SOQL and rows as the original; hostile input fails with an
 * {@link IllegalArgumentException} before Salesforce is queried. Describe results are read on every request and
 * never cached across requests; within one request each type is described at most once.
 *
 * <p>Nothing here catches or wraps an exception. SC-9 upstream exceptions raised inside the client reach
 * {@code UpstreamExceptionHandler}; every other failure of {@link #query(Map)} reaches the project handler that
 * answers {@code Invalid Salesforce query.} (the original {@code catch-exception-strategy}, lines 37-39); failures
 * of {@link #showForm()} reach the generic 500, as the GET flow declared no exception strategy.
 *
 * <p>Stateless and thread-safe.
 */
@Service
public class SalesforceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(SalesforceRetrievalService.class);

    /** Classpath copy of the original {@code ./src/main/resources/retrieval/index.html} (xml:23). */
    private static final String TEMPLATE = "retrieval/index.html";

    /** The expression the template carries where the option list goes (template line 17). */
    private static final String PAYLOAD_EXPRESSION = "#[message.payload]";

    /** A segment after the relationship name of a dotted path such as {@code Owner.Name}. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** SOQL's limit on child-to-parent relationships in one path, such as the two of {@code Account.Owner.Name}. */
    private static final int MAX_RELATIONSHIP_HOPS = 5;

    private static final Field[] NO_FIELDS = new Field[0];

    private final SalesforcePartnerClient client;

    private final SObjectOptionsMapper optionsMapper;

    private final QueryResultFormatter formatter;

    private final ParseTemplateRenderer renderer;

    /**
     * Creates the service.
     *
     * @param client        the SC-15 Salesforce Partner API client, the only Salesforce access path
     * @param optionsMapper the owner of the {@code div/option} DataWeave script
     * @param formatter     the renderer of the query records as the original response text
     * @param renderer      the SC-14 {@code parse-template} replacement
     */
    public SalesforceRetrievalService(SalesforcePartnerClient client, SObjectOptionsMapper optionsMapper,
            QueryResultFormatter formatter, ParseTemplateRenderer renderer) {
        this.client = client;
        this.optionsMapper = optionsMapper;
        this.formatter = formatter;
        this.renderer = renderer;
    }

    /**
     * Renders the query form with one {@code option} per Salesforce object ({@code showFormFlow}, xml:7-24).
     *
     * <p>The mapper's whole XML document, declaration included, replaces {@code #[message.payload]} inside the
     * template's {@code <select>}, as the original's {@code object-to-string} and {@code parse-template} did.
     *
     * @return the HTML page
     * @throws Exception an SC-9 upstream exception, or the vendor's or renderer's exception, unchanged
     */
    public String showForm() throws Exception {
        DescribeGlobalResult result = client.describeGlobal();
        String options = optionsMapper.toOptionsXml(result);
        return renderer.render(TEMPLATE, Map.of(PAYLOAD_EXPRESSION, options));
    }

    /**
     * Runs the form's query ({@code salesforceDataRetrievalFlow}, xml:25-40) and returns the records as text.
     *
     * <p>The SOQL is the original MEL concatenation of line 27, for example
     * {@code SELECT id, name ,email from user where name like '%mule%'}; an empty {@code field} is left out of the
     * select list. Absent {@code object}, {@code searchKey} or {@code searchValue} values render as {@code null},
     * as MEL string concatenation rendered them.
     *
     * @param form the posted form values {@code object}, {@code field}, {@code searchKey} and {@code searchValue};
     *             {@code null} is read as an empty form
     * @return the records in the order Salesforce returned them, rendered as the original list of maps
     * @throws IllegalArgumentException when {@code field} is absent (the original's {@code payload.field.isEmpty()}
     *                                  failed before Salesforce was called), or when the object, the search key or
     *                                  a field entry is not one Salesforce describes; no query is run
     * @throws Exception                an SC-9 upstream exception, or the vendor's exception, unchanged
     */
    public String query(Map<String, String> form) throws Exception {
        Map<String, String> input = form == null ? Map.of() : form;
        String field = input.get("field");
        String object = String.valueOf(input.get("object"));
        String searchKey = String.valueOf(input.get("searchKey"));
        String searchValue = String.valueOf(input.get("searchValue"));
        if (field == null) {
            throw new IllegalArgumentException("Form field 'field' is missing");
        }

        if (!isKnownObject(object, client.describeGlobal())) {
            throw new IllegalArgumentException("Unknown Salesforce object: " + object);
        }
        DescribeSObjectResult describe = client.describeSObject(object);
        Field[] fields = describe == null || describe.getFields() == null ? NO_FIELDS : describe.getFields();
        // This request's describe results by lower-cased type name, so each type a relationship path reaches is
        // described at most once per request; a later request describes afresh.
        Map<String, DescribeSObjectResult> describes = new HashMap<>();
        describes.put(object.toLowerCase(Locale.ROOT), describe);
        if (!isValidIdentifier(searchKey, fields, describes)) {
            throw new IllegalArgumentException("Unknown field: " + searchKey);
        }
        if (!field.isEmpty()) {
            // The original concatenated "field" verbatim after "SELECT id, name ,", so a comma-separated list such as
            // "email, phone" was legitimate; each entry is checked, and the raw text still reaches the SOQL.
            for (String segment : field.split(",", -1)) {
                String name = segment.trim();
                if (name.isEmpty() || !isValidIdentifier(name, fields, describes)) {
                    throw new IllegalArgumentException("Invalid field list: " + field);
                }
            }
        }

        String soql = "SELECT id, name " + (field.isEmpty() ? "" : "," + field)
                + " from " + object + " where " + searchKey
                + " like '%" + escapeLiteral(searchValue) + "%'";
        List<Map<String, Object>> records = client.queryAll(soql);
        String text = formatter.format(records);
        log.info("Result: {}", text);
        return text;
    }

    /** Whether {@code describeGlobal} lists an object of this name, compared case-insensitively as SOQL does. */
    private static boolean isKnownObject(String object, DescribeGlobalResult global) {
        DescribeGlobalSObjectResult[] sobjects = global == null ? null : global.getSobjects();
        if (sobjects != null) {
            for (DescribeGlobalSObjectResult sobject : sobjects) {
                if (sobject != null && object.equalsIgnoreCase(sobject.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code name} is a field of the described object, or a relationship path every segment of which
     * Salesforce describes ({@code Owner.Name}, {@code Account.Owner.Name}).
     *
     * <p>A path is resolved hop by hop, starting at the queried object's fields. Each segment before the last must be
     * the relationship name of a reference field at the current level, and the next level is the union of the
     * {@code describeSObject} fields of every type that field refers to; the last segment must be a field name at the
     * final level. Names are compared case-insensitively, as SOQL compares them. A polymorphic relationship, one
     * referring to several types (such as {@code Owner} referring to {@code Group} and {@code User}), is resolved
     * deliberately as that union: the path is accepted when at least one target type resolves it, so no legitimate
     * path is rejected, and a name no target type describes is rejected. A type whose describe or field list is
     * absent resolves nothing, and a reference field without targets leads nowhere.
     *
     * <p>A path of more than {@value #MAX_RELATIONSHIP_HOPS} relationships, or one whose later segments are not plain
     * identifiers, is rejected before any {@code describeSObject} call.
     *
     * @param name      the caller's identifier, a {@code searchKey} or one {@code field} entry
     * @param fields    the queried object's fields
     * @param describes this request's describe results by lower-cased type name, the queried object's included; each
     *                  type described here is added
     * @return whether {@code name} resolves to a field Salesforce describes
     * @throws Exception an SC-9 upstream exception, or the vendor's exception, of a {@code describeSObject} call,
     *                   unchanged
     */
    private boolean isValidIdentifier(String name, Field[] fields, Map<String, DescribeSObjectResult> describes)
            throws Exception {
        if (name.indexOf('.') < 0) {
            return isFieldName(name, fields);
        }
        String[] segments = name.split("\\.", -1);
        if (segments.length - 1 > MAX_RELATIONSHIP_HOPS) {
            return false;
        }
        for (int i = 1; i < segments.length; i++) {
            if (!PATH_SEGMENT.matcher(segments[i]).matches()) {
                return false;
            }
        }
        List<Field[]> level = Collections.singletonList(fields);
        for (int i = 0; i < segments.length - 1; i++) {
            level = referencedFields(segments[i], level, describes);
            if (level.isEmpty()) {
                return false;
            }
        }
        String terminal = segments[segments.length - 1];
        for (Field[] typeFields : level) {
            if (isFieldName(terminal, typeFields)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one of {@code fields} is named {@code name}, compared case-insensitively as SOQL does.
     *
     * @param name   the identifier or path segment
     * @param fields the fields of one described type
     * @return whether a field of that name exists
     */
    private static boolean isFieldName(String name, Field[] fields) {
        for (Field candidate : fields) {
            if (candidate != null && name.equalsIgnoreCase(candidate.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The next level of a relationship path: the fields of every type that a reference field of {@code level} whose
     * relationship name is {@code relationship} refers to, one array per type that could be described.
     *
     * @param relationship the path segment read as a relationship name
     * @param level        the fields of each type the path has reached so far
     * @param describes    this request's describe results by lower-cased type name
     * @return the referenced types' fields; empty when no reference field of {@code level} carries that relationship
     *         name, it refers to no type, or no type it refers to has a describe with fields
     * @throws Exception an SC-9 upstream exception, or the vendor's exception, of a {@code describeSObject} call,
     *                   unchanged
     */
    private List<Field[]> referencedFields(String relationship, List<Field[]> level,
            Map<String, DescribeSObjectResult> describes) throws Exception {
        Set<String> types = new LinkedHashSet<>();
        for (Field[] typeFields : level) {
            for (Field candidate : typeFields) {
                if (candidate != null && relationship.equalsIgnoreCase(candidate.getRelationshipName())
                        && candidate.getReferenceTo() != null) {
                    for (String type : candidate.getReferenceTo()) {
                        if (type != null && !type.isEmpty()) {
                            types.add(type);
                        }
                    }
                }
            }
        }
        List<Field[]> next = new ArrayList<>(types.size());
        for (String type : types) {
            DescribeSObjectResult describe = describe(type, describes);
            if (describe != null && describe.getFields() != null) {
                next.add(describe.getFields());
            }
        }
        return next;
    }

    /**
     * Describes {@code type} once per request: a type already in {@code describes} (an absent result included) is
     * not described again.
     *
     * @param type      the sObject type name, as a reference field's {@code referenceTo} lists it
     * @param describes this request's describe results by lower-cased type name
     * @return the {@code describeSObject} result, possibly {@code null}
     * @throws Exception an SC-9 upstream exception, or the vendor's exception, unchanged
     */
    private DescribeSObjectResult describe(String type, Map<String, DescribeSObjectResult> describes)
            throws Exception {
        String key = type.toLowerCase(Locale.ROOT);
        if (describes.containsKey(key)) {
            return describes.get(key);
        }
        DescribeSObjectResult result = client.describeSObject(type);
        describes.put(key, result);
        return result;
    }

    /**
     * Escapes a value for a SOQL single-quoted literal: backslash first, then quote, so an inserted escape is never
     * escaped again. {@code %} and {@code _} stay LIKE wildcards, as in the original.
     */
    private static String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
