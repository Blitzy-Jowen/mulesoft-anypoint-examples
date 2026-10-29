package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Renders the records of the POST {@code /} query as the response text of the original
 * {@code salesforceDataRetrievalFlow} ({@code salesforce-id-retrieval.xml:28-35}): the flow appended every
 * {@code java.util.HashMap} record of {@code sfdc:query-all} to an empty list and returned that list as a string.
 * <p>
 * The output is therefore exactly {@code List.toString()} over {@code HashMap.toString()} records:
 * {@code [{Email=kicks+a@mulesoft.com, Id=00520000003LtYCAA0, Name=Mule orgA, type=User}, {...}]}. It is built
 * explicitly, so the bytes do not depend on the list or map implementations a caller passes in, while each map keeps
 * its own iteration order. Stateless and thread-safe.
 */
@Component
public class QueryResultFormatter {

    private static final String EMPTY_LIST = "[]";
    private static final String SEPARATOR = ", ";

    // The JDK's renderings of a map or collection that contains itself; using them keeps the output equal to
    // toString() and stops a self-referencing value from recursing without end.
    private static final String SELF_MAP = "(this Map)";
    private static final String SELF_COLLECTION = "(this Collection)";

    /**
     * Formats the query records in list order.
     *
     * @param records the records as the Salesforce client returns them; may be {@code null}
     * @return {@code []} for no records, otherwise {@code [record, record, ...]} with each record rendered as
     *         {@code {key=value, ...}}
     */
    public String format(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return EMPTY_LIST;
        }
        StringBuilder text = new StringBuilder();
        appendCollection(text, records);
        return text.toString();
    }

    private static void appendValue(StringBuilder text, Object value) {
        if (value instanceof Map<?, ?> map) {
            appendMap(text, map);
        } else if (value instanceof Collection<?> collection) {
            appendCollection(text, collection);
        } else {
            text.append(String.valueOf(value));
        }
    }

    /** Mirrors {@code java.util.AbstractMap.toString()}. */
    private static void appendMap(StringBuilder text, Map<?, ?> map) {
        text.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                text.append(SEPARATOR);
            }
            first = false;
            Object key = entry.getKey();
            Object value = entry.getValue();
            text.append(key == map ? SELF_MAP : String.valueOf(key));
            text.append('=');
            if (value == map) {
                text.append(SELF_MAP);
            } else {
                appendValue(text, value);
            }
        }
        text.append('}');
    }

    /** Mirrors {@code java.util.AbstractCollection.toString()}. */
    private static void appendCollection(StringBuilder text, Collection<?> collection) {
        text.append('[');
        boolean first = true;
        for (Object element : collection) {
            if (!first) {
                text.append(SEPARATOR);
            }
            first = false;
            if (element == collection) {
                text.append(SELF_COLLECTION);
            } else {
                appendValue(text, element);
            }
        }
        text.append(']');
    }
}
