/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.service.PersonService;

/**
 * HTTP triggers of {@code exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml}:
 * the three {@code http:listener}s on {@code HttpListenerConfiguration} (host {@code 0.0.0.0}, port
 * {@code ${http.port}}, line 4).
 * <ul>
 *   <li>{@code retrieveAllPersonsFlow}: {@code GET /person}, {@code allowedMethods="GET"} (line 11);</li>
 *   <li>{@code storePersonFlow}: {@code POST /person}, {@code allowedMethods="POST"}, whose
 *       {@code response-builder} sets status 201 and {@code Location: http://0.0.0.0:8081/person/#[personId]}
 *       (lines 16-20);</li>
 *   <li>{@code retrievePersonFlow}: {@code GET /person/{personId}}, {@code allowedMethods="GET"} (line 28).</li>
 * </ul>
 *
 * <p>This class only binds the request and writes the response. Every flow step (JSON conversion, the id counter,
 * the store, {@code Integer.valueOf} of the URI parameter, the ERROR logger and the existence filter) runs in
 * {@link PersonService}. Exceptions propagate: {@code PersonNotFoundException} reaches the 404 {@code Not Found}
 * of {@code exception/PersonExceptionHandler}, every other one the 500 of SC-1 {@code GlobalExceptionHandler}.
 *
 * <p>No mapping restricts the request or response media type, because the Mule listener ignored the request's
 * {@code Content-Type} and {@code Accept}; each response presets its Content-Type instead, so Spring skips content
 * negotiation and never answers 406 or 415. Methods outside each listener's {@code allowedMethods} reach SC-1's
 * 405: PUT, DELETE and PATCH through Spring's own method check, HEAD and OPTIONS through the rejecting mappings
 * below, which Spring would otherwise answer itself with 200.
 *
 * <p>Tier 2A verification point: the Content-Types the Mule listener emitted, {@code application/json} for the two
 * GET flows (their {@code object-to-json-transformer}) and {@code text/plain;charset=UTF-8} for the
 * {@code set-payload} of {@code storePersonFlow}. A captured fixture of the original governs where they differ.
 */
@RestController
public class PersonController {

    /**
     * The {@code Location} prefix of {@code storePersonFlow} (line 18). The original writes host and port
     * literally rather than reading {@code http.port}, so the header names {@code 0.0.0.0:8081} whatever port the
     * application listens on; it is never derived from the configuration or the request.
     */
    public static final String LOCATION_PREFIX = "http://0.0.0.0:8081/person/";

    /** Content-Type of the {@code storePersonFlow} success response, the string payload of line 24. */
    public static final MediaType STORE_RESPONSE_CONTENT_TYPE = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final PersonService personService;

    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    /**
     * {@code retrieveAllPersonsFlow}: every stored person.
     *
     * @return 200 {@code application/json}, the JSON array of the stored persons
     * @throws IOException when a stored person cannot be serialized
     */
    @GetMapping("/person")
    public ResponseEntity<String> getPersons() throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(personService.retrieveAllPersonsJson());
    }

    /**
     * {@code storePersonFlow}: stores the posted person under the next id.
     *
     * <p>The body is bound as bytes whatever its {@code Content-Type}, or none, and passed on unchanged; an empty
     * body arrives as {@code null} and the service rejects it. The one exception is
     * {@code application/x-www-form-urlencoded} (curl's default for {@code --data}), whose bytes Spring rebuilds
     * from the container's parsed form parameters, so a JSON body sent that way fails to parse and reaches SC-1's
     * 500. That is deliberate rather than worked around: the Mule 3.8 listener decoded such a body into a parameter
     * map, which {@code json-to-object-transformer} (line 21) rejected, so the original failed it too (Tier 2A
     * verification point).
     *
     * @param body the request body, {@code null} when empty
     * @return 201 with {@code Location} {@value #LOCATION_PREFIX}{@code <id>}, {@code text/plain;charset=UTF-8} and
     *         {@link PersonService#STORE_SUCCESS_RESPONSE}
     * @throws IOException when the body is empty, malformed or not a person
     */
    @PostMapping("/person")
    public ResponseEntity<String> storePerson(@RequestBody(required = false) byte[] body) throws IOException {
        int id = personService.storePerson(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, LOCATION_PREFIX + id)
                .contentType(STORE_RESPONSE_CONTENT_TYPE)
                .body(PersonService.STORE_SUCCESS_RESPONSE);
    }

    /**
     * {@code retrievePersonFlow}: the person stored under {@code personId}.
     *
     * <p>The URI parameter stays a {@link String}: the service converts it with {@code Integer.valueOf} as the flow
     * did (line 31), so a non-numeric id reaches SC-1's 500 with the {@link NumberFormatException} message rather
     * than Spring's 400 type mismatch.
     *
     * @param personId the {@code personId} URI parameter, unconverted
     * @return 200 {@code application/json}, the stored person
     * @throws IOException when the stored person cannot be serialized
     */
    @GetMapping("/person/{personId}")
    public ResponseEntity<String> getPerson(@PathVariable("personId") String personId) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(personService.retrievePersonJson(personId));
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /person}, outside both listeners' {@code allowedMethods}. The explicit HEAD
     * mapping is preferred over the implicit HEAD of {@link #getPersons}, and the explicit OPTIONS mapping keeps
     * Spring from answering OPTIONS itself.
     *
     * @param request the rejected request, whose method is reported
     * @throws HttpRequestMethodNotSupportedException always, answered with SC-1's 405
     */
    @RequestMapping(value = "/person", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void rejectPersonsMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of("GET", "POST"));
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /person/{personId}}, outside the listener's {@code allowedMethods}, as
     * {@link #rejectPersonsMethod} does for {@code /person}.
     *
     * @param request the rejected request, whose method is reported
     * @throws HttpRequestMethodNotSupportedException always, answered with SC-1's 405
     */
    @RequestMapping(value = "/person/{personId}", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void rejectPersonMethod(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of("GET"));
    }
}
