/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import org.springframework.stereotype.Component;

import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "Tranform to HTML set of option values"
 * ({@code salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml}, flow {@code showFormFlow},
 * lines 12-19), of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/xml
 * ---
 * div: {
 *     (payload.sobjects map {
 *         option @(value: $.name): $.label
 *     })
 * }
 * </pre>
 * The {@code sfdc:describe-global} result becomes a {@code div} holding one {@code option} per Salesforce object,
 * in the order Salesforce returned them: attribute {@code value} is the object's API name, the text its label.
 * The caller inserts the whole document, declaration included, into the {@code <select>} of
 * {@code retrieval/index.html}, as the original's {@code object-to-string} and {@code parse-template} did.
 *
 * <p>Bytes, layout and escaping are {@link DwXmlWriter}'s:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <div>
 *     <option value="Account">Account</option>
 *     <option value="DandBCompany">D&amp;B Company</option>
 * </div>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs, and nothing follows the root end tag). A result without
 * objects gives the writer's empty {@code div}, as the script's empty object under {@code div} did.
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class SObjectOptionsMapper {

    private static final String ROOT = "div";
    private static final String OPTION = "option";
    private static final String VALUE = "value";

    private final DwXmlWriter writer;

    /**
     * Creates the mapper.
     *
     * @param writer the DataWeave XML writer that renders the element tree
     */
    public SObjectOptionsMapper(DwXmlWriter writer) {
        this.writer = writer;
    }

    /**
     * Converts the describe-global result to the {@code div/option} XML document the script produced.
     *
     * @param result the {@code describeGlobal} result; {@code null}, or one without objects, gives an empty
     *        {@code div}
     * @return the DataWeave XML document, declaration included, without a trailing line feed; {@code null}
     *         entries of {@code getSobjects()} are skipped, and a {@code null} name or label is left to the
     *         writer (no attribute, no text)
     */
    public String toOptionsXml(DescribeGlobalResult result) {
        DwXmlWriter.Element root = DwXmlWriter.element(ROOT);
        DescribeGlobalSObjectResult[] sobjects = result == null ? null : result.getSobjects();
        if (sobjects != null) {
            for (DescribeGlobalSObjectResult sobject : sobjects) {
                if (sobject != null) {
                    root.child(DwXmlWriter.element(OPTION)
                            .attribute(VALUE, sobject.getName())
                            .text(sobject.getLabel()));
                }
            }
        }
        return writer.write(root);
    }
}
