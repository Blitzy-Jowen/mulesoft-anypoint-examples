/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

/**
 * Signals that {@code GET /person/{personId}} asked for an id the person store does not hold.
 *
 * <p>It replaces the throwing {@code message-filter} of the original {@code retrievePersonFlow}
 * ({@code http-restful-resource.xml:33-35}), whose {@code throwOnUnaccepted="true"} raised Mule's
 * {@code FilterUnacceptedException} when {@code personDataStore.containsKey(personId)} was false.
 * {@code service/PersonService.retrievePersonJson} throws it after the ERROR log
 * {@code Person id is <id>}, and {@code exception/PersonExceptionHandler} maps it to the original
 * rollback branch: 404 with reason phrase {@code Not Found}.
 *
 * <p>{@link #MESSAGE} is the text Mule's filter rejection carried. It is logged only and never written
 * to the wire: the converted 404 has an empty body, as the original error-response builder set only a
 * status and a reason phrase (a Tier 2A verification point).
 */
public class PersonNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The message of Mule's rejected-filter exception for the original request's {@code NullPayload}. */
    public static final String MESSAGE = "Message has been rejected by filter. Message payload is of type: NullPayload";

    private final int personId;

    /**
     * @param personId the requested id that no stored person has
     */
    public PersonNotFoundException(int personId) {
        super(MESSAGE);
        this.personId = personId;
    }

    /**
     * @return the requested id that no stored person has
     */
    public int getPersonId() {
        return personId;
    }
}
