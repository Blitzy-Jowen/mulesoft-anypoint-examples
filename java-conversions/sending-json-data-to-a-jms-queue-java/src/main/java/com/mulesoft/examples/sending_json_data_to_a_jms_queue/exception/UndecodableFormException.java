/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception;

import java.util.Objects;
import java.util.Set;

/**
 * The Mule 3.8 HTTP listener's refusal to build the payload of an {@code application/x-www-form-urlencoded}
 * entity, raised in {@code HttpRequestToMuleEvent} before the flow ran and answered by {@code DefaultHttpListener}
 * with the status {@link #getStatus()} and the message {@link #getMessage()} as the whole body:
 * <ul>
 *   <li>{@link #undecodableBody(IllegalArgumentException)}: {@code URLDecoder} rejected a malformed percent escape
 *       while the listener bound the body as a parameter map, the {@code HttpRequestParsingException} the listener
 *       answered with 400 and {@link #MESSAGE};</li>
 *   <li>{@link #unusableContentType(String, Throwable)}: Guava's {@code MediaType.parse} could not parse the
 *       {@code Content-Type}, or {@code MediaType.charset()} could not resolve the one charset it names, an
 *       {@link IllegalArgumentException} the listener answered with 400 and that exception's message;</li>
 *   <li>{@link #ambiguousCharset(Set)}: {@code MediaType.charset()} found two different charsets, an
 *       {@link IllegalStateException} the listener answered, as every other runtime failure, with 500 and
 *       {@link #SERVER_PROBLEM}.</li>
 * </ul>
 *
 * <p>The exception is checked, so that no other {@link IllegalArgumentException} can be mistaken for it: every
 * other failure still reaches the generic 500 of {@link GlobalExceptionHandler}.
 */
public class UndecodableFormException extends Exception {

    /** The message of the Mule listener's failure to decode a form body, which its 400 response carries as the body. */
    public static final String MESSAGE = "Cannot decode x-www-form-urlencoded payload";

    /** The body of the Mule listener's 500 for a runtime failure while it built the payload. */
    public static final String SERVER_PROBLEM = "Server encountered a problem";

    /** The listener's status for a request it could not parse. */
    private static final int BAD_REQUEST = 400;

    /** The listener's status for any other runtime failure while it built the payload. */
    private static final int INTERNAL_SERVER_ERROR = 500;

    /** Version of the serialized form, which holds the message, the cause and {@link #status}. */
    private static final long serialVersionUID = 1L;

    /** The HTTP status the listener answered the failure with. */
    private final int status;

    private UndecodableFormException(int status, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.status = status;
    }

    /**
     * The listener's failure to URL-decode a form body: status 400, message {@link #MESSAGE}.
     *
     * @param cause the {@link java.net.URLDecoder} failure on a malformed percent escape
     * @return the failure
     */
    public static UndecodableFormException undecodableBody(IllegalArgumentException cause) {
        return new UndecodableFormException(BAD_REQUEST, MESSAGE, cause);
    }

    /**
     * The listener's rejection of a form entity's {@code Content-Type}: status 400 with {@code message}, the
     * message of the {@link IllegalArgumentException} the listener caught, which may be empty.
     *
     * @param message {@code Could not parse '<header>'} for a header the parser rejected, or the lower-cased
     *                charset name for a charset the JVM cannot use
     * @param cause   the parser's or {@link java.nio.charset.Charset#forName(String)}'s failure
     * @return the failure
     * @throws NullPointerException when {@code message} is {@code null}
     */
    public static UndecodableFormException unusableContentType(String message, Throwable cause) {
        return new UndecodableFormException(BAD_REQUEST, message, cause);
    }

    /**
     * The listener's failure on a form entity's {@code Content-Type} that names two or more different charsets:
     * status 500 with {@link #SERVER_PROBLEM}. The cause carries the {@link IllegalStateException} Guava's
     * {@code MediaType.charset()} raised, with its message naming the charsets.
     *
     * @param charsets the distinct lower-cased charset values the header names
     * @return the failure
     */
    public static UndecodableFormException ambiguousCharset(Set<String> charsets) {
        return new UndecodableFormException(INTERNAL_SERVER_ERROR, SERVER_PROBLEM,
                new IllegalStateException("Multiple charset values defined: " + charsets));
    }

    /**
     * @return the HTTP status the listener answered the failure with: 400, or 500 for an ambiguous charset
     */
    public int getStatus() {
        return status;
    }
}
