/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

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
}
