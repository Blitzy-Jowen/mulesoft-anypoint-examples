/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception.UndecodableFormException;

/**
 * Reproduces the {@code byte-array-to-string-transformer}, {@code jms:outbound-endpoint} and {@code logger}
 * processors of flow {@code json-to-jmsFlow} (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:8-10).
 *
 * <p>The transformer is {@link #send(byte[], String)}: it decodes the raw body bytes with the charset the request's
 * {@code Content-Type} names, the JVM's default charset when the header names none, or UTF-8, the application
 * encoding of {@code mule-deploy.properties}, when there is no header, it cannot be parsed, or it names a charset
 * that cannot be used, and encodes the flow's result back with the same charset for the response. The endpoint
 * and the logger are {@link #send(String)}, which runs in the processors' order.
 * {@link #responseContentType(String)} gives the response the {@code Content-Type} the listener derived from that
 * message, naming the same charset.
 *
 * <p>An {@code application/x-www-form-urlencoded} entity, a non-empty body or a chunked one, recognised by
 * {@link #isFormUrlEncoded(String)}, takes {@link #sendForm(byte[], String)} instead: the Mule 3.8 listener had
 * already decoded such a body into a parameter map, so the transformer received that map, not bytes, and turned it
 * into its {@code toString} rendering, such as <code>{a=1, b=2}</code>. That rendering is the text queued, logged
 * and echoed. Before decoding such a body the listener parsed its whole {@code Content-Type} with Guava 18.0's
 * {@code MediaType} and resolved its charset; a header it could not parse, a charset it could not use, two
 * different charsets, and a body it could not decode each fail with {@link UndecodableFormException}, carrying the
 * listener's status and message, before anything is sent or logged.
 *
 * <p>The endpoint is one-way: the payload is sent to the queue synchronously on the caller's thread, with no
 * reply-to and no receive, so a failed send fails the request before anything is logged.
 *
 * <p>The logger declares no message, so it logs a description of the message in the layout of Mule's
 * {@code DefaultMuleMessage.toString()}: the payload's class name and the message metadata, never the request
 * body.
 */
@Service
public class SalesQueueService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesQueueService.class);

    /** Class name Mule's message rendering opens with. */
    private static final String MULE_MESSAGE_CLASS = "org.mule.DefaultMuleMessage";

    /** Payload class Mule's message holds when there is no payload; its payload is never {@code null}. */
    private static final String NULL_PAYLOAD_CLASS = "org.mule.transport.NullPayload";

    /** Mule's rendering of an unset correlation ID or exception payload. */
    private static final String NOT_SET = "<not set>";

    /** Mule's value for an unset correlation group size or sequence. */
    private static final int NO_CORRELATION = -1;

    /** The application's default encoding, which Mule's message reports when the payload sets none. */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /** The {@code Content-Type} parameter that names the body's charset. */
    private static final String CHARSET_PARAMETER = "charset";

    /** The text whose presence in {@code Content-Type} made the Mule listener parse the body as multipart. */
    private static final String MULTIPART = "multipart";

    /**
     * The separators of Guava 18.0's {@code MediaType.TOKEN_MATCHER}: none of them may appear in a type, subtype,
     * attribute or unquoted value.
     */
    private static final String TOKEN_SEPARATORS = "()<>@,;:\\\"/[]?=";

    /** Guava 18.0's {@code MediaType.LINEAR_WHITE_SPACE}, the only whitespace its grammar skips, after a {@code ;}. */
    private static final String LINEAR_WHITE_SPACE = " \t\r\n";

    /** Characters of the ASCII range, the only ones Guava 18.0's {@code MediaType} grammar accepts. */
    private static final int ASCII_LIMIT = 0x80;

    /** Separator of the name/value pairs of a form-urlencoded body. */
    private static final String PAIR_SEPARATOR = "&";

    /** Separator of a pair's name from its value; only the first one in a pair separates. */
    private static final String NAME_VALUE_SEPARATOR = "=";

    /** The property name Mule 3.8's {@code PropertiesUtils} registers for masking when it renders a map. */
    private static final String MASKED_PARAMETER = "password";

    /** The text Mule 3.8's {@code PropertiesUtils} writes in place of a masked value. */
    private static final String MASKED_VALUE = "*****";

    /** Mule's rendering of an absent map entry value, which {@code StringBuilder} writes for a {@code null}. */
    private static final String NULL_VALUE = "null";

    /**
     * The property scopes Mule's message rendering lists, in its order; Mule skips the application scope there,
     * because a message does not hold that scope.
     */
    private static final String[] PROPERTY_SCOPES = {"INVOCATION", "INBOUND", "OUTBOUND", "SESSION"};

    private final JmsTemplate jmsTemplate;

    private final String queue;

    /**
     * @param jmsTemplate the template Boot auto-configures against the project's broker
     * @param queue       destination queue name, {@code jms.queue}; defaults to the original's fixed {@code sales}
     */
    public SalesQueueService(JmsTemplate jmsTemplate, @Value("${jms.queue:sales}") String queue) {
        this.jmsTemplate = jmsTemplate;
        this.queue = queue;
    }

    /**
     * Runs the flow from the raw request body to the response body: decodes {@code body} as the
     * {@code byte-array-to-string-transformer} did, sends and logs the text through {@link #send(String)}, and
     * encodes the payload the flow ends with in the charset it was decoded with.
     *
     * <p>The charset is the one {@code contentType} names; the JVM's default charset when the header names none,
     * as the original listener set the message encoding; or UTF-8 when the header is absent or blank, cannot be
     * parsed, or names a charset that is malformed or unsupported. Encoding the result with that same charset
     * gives back the request bytes for every body valid in its charset, apart from a byte-order mark the
     * charset's encoder writes its own way, as {@code UTF-16} writes big-endian. The body is never parsed or
     * validated, because the original forwards bodies that are not valid JSON.
     *
     * @param body        the raw request body; an empty array is sent as the empty string
     * @param contentType the request's raw {@code Content-Type} header, or {@code null} when it has none
     * @return the queued payload encoded with the charset it was decoded with
     * @throws NullPointerException when {@code body} is {@code null}; nothing is sent or logged
     * @throws org.springframework.jms.JmsException when the send fails; it is deliberately left to propagate
     */
    public byte[] send(byte[] body, String contentType) {
        Objects.requireNonNull(body, "body");
        Charset charset = resolveCharset(contentType);
        String result = send(new String(body, charset));
        return result.getBytes(charset);
    }

    /**
     * Runs the flow from a form-urlencoded request body to the response body, as the Mule 3.8 listener and the
     * {@code byte-array-to-string-transformer} did for a form entity: decodes {@code body} into the
     * listener's parameter map as Mule's {@code HttpParser.decodeString} did, renders that map as the transformer's
     * {@code ObjectToString} did, sends and logs the rendering through {@link #send(String)}, and encodes it for the
     * response.
     *
     * <p>Before the body is read, the whole {@code Content-Type} is checked as the listener checked it, by Guava
     * 18.0's {@code MediaType.parse} and {@code MediaType.charset()}, which {@link #listenerCharset(String)}
     * reproduces: a header the grammar rejects fails with status 400 and {@code Could not parse '<header>'}; a
     * single charset the JVM cannot use fails with status 400 and the lower-cased charset name; two or more
     * different charsets fail with status 500 and {@value UndecodableFormException#SERVER_PROBLEM}.
     *
     * <p>The listener read the form text with the JVM's default charset, and URL-decoded the names and values with
     * the charset that check resolved: the one the header names, or the JVM's default charset when it names none.
     * The rendering is encoded for the response with the charset {@link #responseContentType(String)} names; both
     * resolve it through {@link #resolveCharset(String)}, so the header and the body always agree. For every header
     * both parsers accept, that is the URL-decoding charset; only the response encoding and header fall back to
     * UTF-8 for the rare header Guava's grammar accepts and Spring's parser rejects, one with a brace in a token or
     * a backslash escape in a quoted value, while its URL-decoding still follows the listener.
     *
     * @param body        the raw request body; a blank body decodes to the empty map, rendered <code>{}</code>
     * @param contentType the request's raw {@code Content-Type} header, a form-urlencoded type
     * @return the queued rendering encoded with the charset {@link #responseContentType(String)} names
     * @throws UndecodableFormException when the listener rejected the {@code Content-Type}, or when a name or value
     *         holds a malformed percent escape; its status and message are the listener's response; nothing is sent
     *         or logged
     * @throws NullPointerException when {@code body} or {@code contentType} is {@code null}; nothing is sent or
     *         logged
     * @throws org.springframework.jms.JmsException when the send fails; it is deliberately left to propagate
     */
    public byte[] sendForm(byte[] body, String contentType) throws UndecodableFormException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(contentType, "contentType");
        Charset listenerCharset = listenerCharset(contentType);
        Map<String, String> parameters = decodeParameters(new String(body, Charset.defaultCharset()), listenerCharset);
        String result = send(renderParameterMap(parameters));
        return result.getBytes(resolveCharset(contentType));
    }

    /**
     * Sends {@code payload} to the queue, logs a description of the message at INFO, and returns the payload
     * unchanged as the flow's response payload.
     *
     * <p>The template's default {@code SimpleMessageConverter} sends the string as a {@code TextMessage} whose
     * text is the payload exactly; the payload is never parsed, because the original forwards bodies that are not
     * valid JSON. The log line names the payload's class and the message metadata only; the request body is
     * never logged.
     *
     * @param payload the decoded request body, the payload the transformer produced
     * @return the same {@code payload} instance
     * @throws org.springframework.jms.JmsException when the send fails; it is deliberately left to propagate
     */
    public String send(String payload) {
        jmsTemplate.convertAndSend(queue, payload);
        // The original logger declares no message, so Mule 3.8's LoggerMessageProcessor logged the MuleMessage,
        // whose toString renders the payload's class name and the message metadata but never the body; this
        // reproduces that description, checking the level first as Mule did, and keeps the request body out of
        // the log event.
        if (LOG.isInfoEnabled()) {
            LOG.info("{}", describeMessage(payload));
        }
        return payload;
    }

    /**
     * Builds the {@code Content-Type} of the success response the way the Mule 3.8 listener built it from the
     * message's data type, or returns {@code null} when the original sent none.
     *
     * <p>The value is the request's lower-cased {@code type/subtype}, then {@code "; charset="} and the canonical
     * name of the charset {@link #send(byte[], String)} decodes and encodes the body with, for example
     * {@code application/json; charset=UTF-8} for {@code APPLICATION/JSON; foo=bar}. Other parameters are dropped,
     * as the original dropped them. The header and the body therefore always agree on the charset.
     *
     * <p>{@code null}, meaning no {@code Content-Type} header, when the request has none or a blank one, or when
     * its {@code type/subtype} is <code>&#42;/&#42;</code>: the original typed a request without
     * {@code Content-Type} as <code>&#42;/&#42;</code> and wrote no header for that type. It is also {@code null}
     * when the {@code type/subtype} cannot be parsed, the plan default under which the body is decoded as UTF-8.
     * The {@code type/subtype} is the text before the first {@code ;}, parsed on its own, so a header whose
     * charset is unusable still yields its type, with UTF-8, the charset the body is decoded with.
     *
     * @param rawContentType the request's raw {@code Content-Type} header, or {@code null} when it has none
     * @return the response {@code Content-Type}, or {@code null} when the response must carry none
     */
    public String responseContentType(String rawContentType) {
        if (isBlank(rawContentType)) {
            return null;
        }
        int parameters = rawContentType.indexOf(';');
        MediaType type;
        try {
            type = MediaType.parseMediaType(parameters < 0 ? rawContentType : rawContentType.substring(0, parameters));
        } catch (IllegalArgumentException unparseable) {
            return null;
        }
        if (type.isWildcardType() && type.isWildcardSubtype()) {
            return null;
        }
        return type.getType() + '/' + type.getSubtype() + "; charset=" + resolveCharset(rawContentType).name();
    }

    /**
     * Tells whether the Mule 3.8 listener, Mule's {@code HttpRequestToMuleEvent}, made a request entity with this
     * {@code Content-Type} a parameter map: the header names {@code application/x-www-form-urlencoded}, ignoring
     * letter case and parameters, and does not contain {@code multipart}, which made the listener parse the body
     * as multipart parts instead.
     *
     * <p>The {@code type/subtype} is the text before the first {@code ;}, parsed on its own, so a header whose
     * parameters or charset the listener rejected is still a form, which {@link #sendForm(byte[], String)} rejects
     * as the listener did. A header that is absent, blank or whose {@code type/subtype} cannot be parsed is not a
     * form: such a body stays on the byte path, the plan default under which it is decoded as UTF-8.
     *
     * @param contentType the request's raw {@code Content-Type} header, or {@code null} when it has none
     * @return {@code true} when the body is to be decoded as a form
     */
    public boolean isFormUrlEncoded(String contentType) {
        if (isBlank(contentType) || contentType.contains(MULTIPART)) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(
                    MediaType.parseMediaType(parameters < 0 ? contentType : contentType.substring(0, parameters)));
        } catch (IllegalArgumentException unparseable) {
            return false;
        }
    }

    /**
     * Renders the message the way Mule's {@code DefaultMuleMessage.toString()} renders it, followed by its
     * {@code Message properties:} section, each line ending with the platform line separator.
     *
     * <p>The request body is deliberately kept out of the rendering: only its class name appears, so caller
     * data, credentials and line breaks in the body never reach the log. The {@code id} is a fresh random UUID,
     * as each Mule message carried its own unique ID. The {@code encoding} line shows the application default,
     * {@code UTF-8}, because the rendering is built from the decoded {@code String} alone, which carries no
     * encoding. No property entries are listed under the scope headers, because this service carries no Mule message
     * properties: the HTTP inbound properties, which include caller headers such as {@code Authorization}, and
     * Mule's {@code MULE_*} transport properties have no counterpart here.
     *
     * @param payload the request body, or {@code null}; only its class name is rendered
     * @return the multi-line message description
     */
    private static String describeMessage(String payload) {
        String ls = System.lineSeparator();
        StringBuilder buf = new StringBuilder(400);
        buf.append(ls).append(MULE_MESSAGE_CLASS).append(ls)
                .append('{').append(ls)
                .append("  id=").append(UUID.randomUUID()).append(ls)
                .append("  payload=").append(payload == null ? NULL_PAYLOAD_CLASS : payload.getClass().getName())
                .append(ls)
                .append("  correlationId=").append(NOT_SET).append(ls)
                .append("  correlationGroup=").append(NO_CORRELATION).append(ls)
                .append("  correlationSeq=").append(NO_CORRELATION).append(ls)
                .append("  encoding=").append(DEFAULT_ENCODING).append(ls)
                .append("  exceptionPayload=").append(NOT_SET).append(ls)
                .append(ls).append("Message properties:").append(ls);
        for (String scope : PROPERTY_SCOPES) {
            buf.append("  ").append(scope).append(" scoped properties:").append(ls);
        }
        return buf.append('}').toString();
    }

    /**
     * Resolves a form entity's charset from its {@code Content-Type} as Mule 3.8's {@code HttpRequestToMuleEvent}
     * did before it decoded the body: {@code MediaType.parse(header)} and then {@code mediaType.charset()}, both
     * Guava 18.0, whose {@link IllegalArgumentException} {@code DefaultHttpListener} answered with 400 and the
     * exception's message, and whose {@link IllegalStateException} it answered with 500 and
     * {@value UndecodableFormException#SERVER_PROBLEM}. The listener URL-decoded the body's names and values with the
     * charset returned here.
     *
     * <p>{@code charset()} takes the distinct values of the {@code charset} parameters {@link #listenerCharsets}
     * returns: none gives the JVM's default charset, as the listener's {@code Charset.defaultCharset()} did; one
     * gives the charset {@link Charset#forName(String)} resolves it to, and otherwise fails with that method's
     * {@link java.nio.charset.IllegalCharsetNameException} or {@link java.nio.charset.UnsupportedCharsetException},
     * whose message is the lower-cased name, empty for {@code charset=""}; two or more fail as ambiguous.
     *
     * @param header the request's raw {@code Content-Type} header
     * @return the charset the listener URL-decoded the form with
     * @throws UndecodableFormException when the listener rejected the header, with its status and message
     */
    private static Charset listenerCharset(String header) throws UndecodableFormException {
        Set<String> charsets = listenerCharsets(header);
        if (charsets.size() > 1) {
            throw UndecodableFormException.ambiguousCharset(charsets);
        }
        if (charsets.isEmpty()) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(charsets.iterator().next());
        } catch (IllegalArgumentException unusable) {
            // IllegalCharsetNameException and UnsupportedCharsetException, both IllegalArgumentExceptions; the
            // listener's 400 carries their message.
            throw UndecodableFormException.unusableContentType(unusable.getMessage(), unusable);
        }
    }

    /**
     * Parses {@code header} by the grammar of Guava 18.0's {@code MediaType.parse} and returns the distinct values of
     * its {@code charset} parameters, the values {@code MediaType.charset()} chose from:
     * <ul>
     *   <li>a type token, {@code /}, a subtype token; then, while input remains: {@code ;}, an optional run of
     *       linear whitespace (space, tab, CR, LF), an attribute token, {@code =}, and a value that is either a
     *       quoted string, {@code "} up to the next unescaped {@code "} where {@code \} escapes any one ASCII
     *       character and every other character is ASCII but {@code "}, {@code \} and CR, or a token;</li>
     *   <li>a token is one or more ASCII characters that are neither ISO control characters, nor the space, nor
     *       one of the separators {@link #TOKEN_SEPARATORS}, Guava's {@code TOKEN_MATCHER};</li>
     *   <li>no other whitespace is allowed: not before a {@code ;}, not around {@code =}, not trailing; a trailing
     *       {@code ;} and an empty value fail;</li>
     *   <li>attribute names, and the values of {@code charset}, are lower-cased as Guava's {@code normalizeToken}
     *       and {@code normalizeParameterValue} lower-cased them; every character is ASCII, so
     *       {@link Locale#ROOT} lower-cases exactly the letters {@code Ascii.toLowerCase} does.</li>
     * </ul>
     * Guava's further check that a wildcard type has a wildcard subtype is not repeated: only form types reach this
     * parser.
     *
     * @param header the request's raw {@code Content-Type} header
     * @return the distinct lower-cased {@code charset} values, in first-occurrence order
     * @throws UndecodableFormException with status 400 and {@code Could not parse '<header>'}, Guava's message, when
     *         the grammar rejects the header
     */
    private static Set<String> listenerCharsets(String header) throws UndecodableFormException {
        ListenerMediaTypeTokenizer tokenizer = new ListenerMediaTypeTokenizer(header);
        Set<String> charsets = new LinkedHashSet<>();
        try {
            tokenizer.consumeToken(SalesQueueService::isTokenCharacter);
            tokenizer.consumeCharacter('/');
            tokenizer.consumeToken(SalesQueueService::isTokenCharacter);
            while (tokenizer.hasMore()) {
                tokenizer.consumeCharacter(';');
                tokenizer.consumeTokenIfPresent(SalesQueueService::isLinearWhiteSpace);
                String attribute = tokenizer.consumeToken(SalesQueueService::isTokenCharacter);
                tokenizer.consumeCharacter('=');
                String value;
                if (tokenizer.previewChar() == '"') {
                    tokenizer.consumeCharacter('"');
                    StringBuilder quoted = new StringBuilder();
                    while (tokenizer.previewChar() != '"') {
                        if (tokenizer.previewChar() == '\\') {
                            tokenizer.consumeCharacter('\\');
                            quoted.append(tokenizer.consumeCharacter(SalesQueueService::isAscii));
                        } else {
                            quoted.append(tokenizer.consumeToken(SalesQueueService::isQuotedTextCharacter));
                        }
                    }
                    value = quoted.toString();
                    tokenizer.consumeCharacter('"');
                } else {
                    value = tokenizer.consumeToken(SalesQueueService::isTokenCharacter);
                }
                if (CHARSET_PARAMETER.equals(attribute.toLowerCase(Locale.ROOT))) {
                    charsets.add(value.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IllegalStateException unparseable) {
            throw UndecodableFormException.unusableContentType("Could not parse '" + header + "'", unparseable);
        }
        return charsets;
    }

    /** Guava 18.0's {@code MediaType.TOKEN_MATCHER}. */
    private static boolean isTokenCharacter(int c) {
        return isAscii(c) && !Character.isISOControl(c) && c != ' ' && TOKEN_SEPARATORS.indexOf(c) < 0;
    }

    /** Guava 18.0's {@code MediaType.QUOTED_TEXT_MATCHER}. */
    private static boolean isQuotedTextCharacter(int c) {
        return isAscii(c) && c != '"' && c != '\\' && c != '\r';
    }

    /** Guava 18.0's {@code MediaType.LINEAR_WHITE_SPACE}. */
    private static boolean isLinearWhiteSpace(int c) {
        return LINEAR_WHITE_SPACE.indexOf(c) >= 0;
    }

    /** Guava 18.0's {@code CharMatcher.ASCII}. */
    private static boolean isAscii(int c) {
        return c < ASCII_LIMIT;
    }

    /**
     * Decodes a form-urlencoded body as Mule 3.8's {@code HttpParser.decodeString} filled the listener's
     * {@code ParameterMap}: a blank text gives an empty map; otherwise the text is split on {@code &}
     * ({@link String#split} semantics, so a leading empty pair is kept as the name {@code ""} and trailing ones are
     * dropped) and each pair on its first {@code =}; name and value are URL-decoded with {@code charset}, a pair
     * without {@code =} gets a {@code null} value, and a repeated name keeps its first position and takes its last
     * value, the value the map's entries carry for that name.
     *
     * <p>A malformed percent escape fails as the {@code HttpRequestParsingException} that Mule's
     * {@code HttpRequestToMuleEvent} raised before the flow ran.
     *
     * @param text    the form text, read with the JVM's default charset
     * @param charset the charset the names and values are URL-decoded with
     * @return the decoded parameters in first-occurrence order
     * @throws UndecodableFormException when a name or value holds a malformed percent escape
     */
    private static Map<String, String> decodeParameters(String text, Charset charset) throws UndecodableFormException {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (text.isBlank()) {
            return parameters;
        }
        try {
            for (String pair : text.split(PAIR_SEPARATOR)) {
                int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
                if (separator < 0) {
                    parameters.put(URLDecoder.decode(pair, charset), null);
                } else {
                    parameters.put(URLDecoder.decode(pair.substring(0, separator), charset),
                            URLDecoder.decode(pair.substring(separator + 1), charset));
                }
            }
        } catch (IllegalArgumentException malformed) {
            throw UndecodableFormException.undecodableBody(malformed);
        }
        return parameters;
    }

    /**
     * Renders a parameter map as Mule 3.8's {@code ObjectToString}, the implementation of
     * {@code byte-array-to-string-transformer}, rendered the listener's {@code ParameterMap}, through
     * {@code StringMessageUtils.toString} and {@code MapUtils.toString(map, false)}:
     * <ul>
     *   <li>an empty map is <code>{}</code>;</li>
     *   <li>otherwise the entries are visited in the order of the {@link HashSet} of
     *       {@link AbstractMap.SimpleEntry} that {@code ParameterMap.entrySet()} built by adding one entry per name,
     *       in the map's name order, so the order is the set's hash order rather than the body's;</li>
     *   <li>each entry is written {@code name=value}, with the value masked as
     *       {@link #maskedValue(Map.Entry)} describes;</li>
     *   <li>entries are separated by {@code ", "} and the whole is enclosed in braces.</li>
     * </ul>
     *
     * @param parameters the decoded parameters in first-occurrence order
     * @return the rendering, the payload the transformer produced
     */
    private static String renderParameterMap(Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return "{}";
        }
        Set<Map.Entry<String, String>> entries = new HashSet<>();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(parameter.getKey(), parameter.getValue()));
        }
        StringBuilder rendering = new StringBuilder("{");
        String separator = "";
        for (Map.Entry<String, String> entry : entries) {
            rendering.append(separator).append(entry.getKey()).append('=').append(maskedValue(entry));
            separator = ", ";
        }
        return rendering.append('}').toString();
    }

    /**
     * Mule 3.8's {@code PropertiesUtils.maskedPropertyValue}: the text {@code null} for an absent value, whatever
     * the name; {@code *****} for the masked name {@code password}; otherwise the value.
     */
    private static String maskedValue(Map.Entry<String, String> entry) {
        if (entry.getKey() == null || entry.getValue() == null) {
            return NULL_VALUE;
        }
        if (MASKED_PARAMETER.equals(entry.getKey())) {
            return MASKED_VALUE;
        }
        return entry.getValue();
    }

    /**
     * Returns the charset {@code rawContentType} names in its {@code charset} parameter, whatever the letter case
     * of the parameter name; the JVM's default charset, the encoding the original listener gave a request whose
     * {@code Content-Type} names none; or UTF-8, the application encoding of the original, when the header is
     * absent or blank, cannot be parsed, or names a charset that is malformed or unsupported.
     */
    private static Charset resolveCharset(String rawContentType) {
        if (isBlank(rawContentType)) {
            return StandardCharsets.UTF_8;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(rawContentType);
            if (mediaType.getCharset() != null) {
                return mediaType.getCharset();
            }
            // The original's parser read parameter names ignoring case, while Spring resolves the charset of a
            // parameter spelled "charset" only; getParameter ignores case, so "Charset" or "CHARSET" is found here
            // and resolved under the lower-case name.
            String charsetName = mediaType.getParameter(CHARSET_PARAMETER);
            if (charsetName == null) {
                return Charset.defaultCharset();
            }
            return new MediaType(mediaType.getType(), mediaType.getSubtype(), Map.of(CHARSET_PARAMETER, charsetName))
                    .getCharset();
        } catch (IllegalArgumentException unusable) {
            // One catch covers every failure: InvalidMediaTypeException (an unparseable header, or a charset the
            // parser cannot resolve) and Charset.forName's IllegalCharsetNameException and
            // UnsupportedCharsetException all extend IllegalArgumentException, so a multi-catch of them would not
            // compile.
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Guava 18.0's {@code MediaType.Tokenizer}: a cursor over the header on which every unmet expectation, reaching
     * the end of the input included, throws the {@link IllegalStateException} Guava's {@code checkState} threw, so
     * that {@link #listenerCharsets(String)} reports the whole header as unparseable.
     */
    private static final class ListenerMediaTypeTokenizer {

        private final String input;

        private int position;

        ListenerMediaTypeTokenizer(String input) {
            this.input = input;
        }

        /** Consumes the run, possibly empty, of characters {@code matcher} accepts; fails at the end of the input. */
        String consumeTokenIfPresent(IntPredicate matcher) {
            checkState(hasMore());
            int start = position;
            while (position < input.length() && matcher.test(input.charAt(position))) {
                position++;
            }
            return input.substring(start, position);
        }

        /** Consumes the run of characters {@code matcher} accepts, which must hold at least one. */
        String consumeToken(IntPredicate matcher) {
            int start = position;
            String token = consumeTokenIfPresent(matcher);
            checkState(position != start);
            return token;
        }

        /** Consumes the next character, which {@code matcher} must accept. */
        char consumeCharacter(IntPredicate matcher) {
            char c = previewChar();
            checkState(matcher.test(c));
            position++;
            return c;
        }

        /** Consumes the next character, which must be {@code expected}. */
        void consumeCharacter(char expected) {
            checkState(previewChar() == expected);
            position++;
        }

        /** The next character, which must exist. */
        char previewChar() {
            checkState(hasMore());
            return input.charAt(position);
        }

        boolean hasMore() {
            return position < input.length();
        }

        private static void checkState(boolean expectation) {
            if (!expectation) {
                throw new IllegalStateException();
            }
        }
    }
}
