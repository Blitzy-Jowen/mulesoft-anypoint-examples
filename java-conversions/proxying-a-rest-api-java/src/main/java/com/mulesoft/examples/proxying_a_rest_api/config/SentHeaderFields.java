/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.util.ArrayList;
import java.util.List;

import io.undertow.util.AttachmentKey;

/**
 * The header fields of one request head as the client sent them, in the order it sent them, recorded by
 * {@link RequestFramingValidator} before Undertow's parser normalises their values.
 *
 * <p>A name is kept as sent, case included. A value is the bytes after the colon without leading and trailing SP and
 * HTAB, each obs-fold (the whitespace ending a line, its CRLF and the whitespace starting the continuation line)
 * replaced by one SP; every other byte, inner runs of SP and HTAB included, is kept. Each byte is one ISO-8859-1
 * character, as Undertow decodes it.
 *
 * <p>All names and values share one string, so a head costs about one character per byte it holds plus two
 * {@code int} per field, whatever its number of fields. Immutable and safe to share between threads.
 */
final class SentHeaderFields {

    /** The fields of the request an Undertow exchange carries, attached by {@link RequestFramingGuardConfig}. */
    static final AttachmentKey<SentHeaderFields> ATTACHMENT = AttachmentKey.create(SentHeaderFields.class);

    /** A head without header fields. */
    static final SentHeaderFields NONE = new SentHeaderFields("", new int[] {0});

    /** Names and values one after another: name 0, value 0, name 1, value 1, ... */
    private final String text;

    /**
     * Where each name and value ends in {@link #text}, after a leading {@code 0}: name {@code i} spans
     * {@code bounds[2i]} to {@code bounds[2i + 1]}, its value {@code bounds[2i + 1]} to {@code bounds[2i + 2]}.
     */
    private final int[] bounds;

    /**
     * @param text   the names and values, one after another
     * @param bounds {@code 0}, then the end of each name and of each value in {@link #text}, in order
     */
    SentHeaderFields(String text, int[] bounds) {
        this.text = text;
        this.bounds = bounds;
    }

    /** @return the number of header fields */
    int size() {
        return bounds.length / 2;
    }

    /** @return the name of field {@code index}, as sent */
    String name(int index) {
        return text.substring(bounds[2 * index], bounds[2 * index + 1]);
    }

    /** @return the value of field {@code index}, as sent apart from its outer whitespace and obs-folds */
    String value(int index) {
        return text.substring(bounds[2 * index + 1], bounds[2 * index + 2]);
    }

    /**
     * @param name a header name, matched ignoring ASCII case as HTTP field names are
     * @return the values of every field with that name, in the order they were sent; empty when there is none
     */
    List<String> values(String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            if (nameEquals(i, name)) {
                values.add(value(i));
            }
        }
        return values;
    }

    /** Compares the name of field {@code index} with {@code name}, ignoring the case of ASCII letters only. */
    private boolean nameEquals(int index, String name) {
        int start = bounds[2 * index];
        if (bounds[2 * index + 1] - start != name.length()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (lowerAscii(text.charAt(start + i)) != lowerAscii(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char lowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
}
