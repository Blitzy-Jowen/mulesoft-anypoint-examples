/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Writes a {@link Date} as a JSON string in the pattern {@value #PATTERN}, for example
 * {@code 2024-05-01T18:00:00+0200}. Ported from the Mule rest-api-with-apikit example's
 * {@code examples.leagues.serializer.JsonDateSerializer}, with the Jackson 1 types replaced by their
 * fasterxml equivalents; the original package is not spelled out so that this file names no Mule or
 * Jackson 1 package.
 * <p>
 * The formatter is {@code new SimpleDateFormat(PATTERN)}, exactly as in the original, so the date is
 * rendered in the JVM default time zone (the {@code Z} offset follows it) with the default
 * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
 * <p>
 * A new formatter is created per call rather than held in a shared static field as the original did:
 * {@link SimpleDateFormat} is not thread-safe, and controllers serialize concurrently. A per-call
 * formatter also reflects the current default time zone instead of the one at class initialisation.
 * <p>
 * It is referenced from {@code model.response.Match#date} through {@code @JsonSerialize(using = ...)} and
 * therefore applies only in the annotation-honouring single-resource rendering of {@code LeagueJsonMapper};
 * the collection rendering disables annotations and writes epoch milliseconds, as the original did.
 */
public class JsonDateSerializer extends JsonSerializer<Date>
{
    static final String PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"; // ISO 8601

    @Override
    public void serialize(Date date, JsonGenerator gen, SerializerProvider provider)
            throws IOException {

        String formattedDate = new SimpleDateFormat(PATTERN).format(date);
        gen.writeString(formattedDate);
    }
}
