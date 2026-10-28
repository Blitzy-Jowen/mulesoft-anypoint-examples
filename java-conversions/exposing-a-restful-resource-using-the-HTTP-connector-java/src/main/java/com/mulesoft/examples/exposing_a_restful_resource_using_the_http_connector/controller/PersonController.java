/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
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
 * {@code Content-Type} and {@code Accept}. {@link #getPersons} presets its Content-Type, so Spring skips content
 * negotiation, and the other two flows write their bytes to the servlet response directly, so no message converter
 * runs; none answers 406 or 415. GET and POST in any letter case, which the Mule listener matched
 * ignoring case, reach these mappings through {@code config/ListenerMethodFilter}. Methods outside each listener's
 * {@code allowedMethods} reach SC-1's 405: PUT, DELETE, PATCH and other spellings such as {@code head} or
 * {@code options} through Spring's own method check, HEAD and OPTIONS through the rejecting mappings below, which
 * Spring would otherwise answer itself with 200. A CORS pre-flight is dispatched as the plain OPTIONS it is by
 * {@code config/PreflightDispatchConfig}, so it reaches the rejecting mappings below and gets the 405 too.
 *
 * <p>Content-Types follow the payload each flow ends with. {@code retrieveAllPersonsFlow} ends in an
 * {@code object-to-json-transformer} (line 13), so its reply is {@code application/json}. {@code storePersonFlow}
 * ends in the literal {@code set-payload} of line 24 and {@code retrievePersonFlow} in the {@code set-payload} of the
 * stored {@code Person} (line 36), with no transformer after it; neither declares a MIME type, and the Mule listener
 * sent no Content-Type for such a payload. Those two replies therefore carry their {@code Content-Length} and no
 * {@code Content-Type}, and they are written only after {@link PersonService} returns, so its exceptions still find
 * an uncommitted response for {@code PersonExceptionHandler} and SC-1.
 *
 * <p>Tier 2A verification point: the Content-Type of each of the three replies, or its absence. A captured fixture
 * of the original governs where it differs.
 */
@RestController
public class PersonController {

    /**
     * The {@code Location} prefix of {@code storePersonFlow} (line 18). The original writes host and port
     * literally rather than reading {@code http.port}, so the header names {@code 0.0.0.0:8081} whatever port the
     * application listens on; it is never derived from the configuration or the request.
     */
    public static final String LOCATION_PREFIX = "http://0.0.0.0:8081/person/";

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
     * <p>The reply is written as 201 with {@code Location} {@value #LOCATION_PREFIX}{@code <id>}, the UTF-8 bytes of
     * {@link PersonService#STORE_SUCCESS_RESPONSE} with their {@code Content-Length}, and no {@code Content-Type}:
     * the untyped {@code set-payload} of line 24. The JSON literal {@code null} is stored as {@code null} and gets
     * this reply too.
     *
     * @param body     the request body, {@code null} when empty
     * @param response the response the reply is written to once the person is stored
     * @throws IOException when the body is absent or empty, malformed JSON, a JSON array, string, number or boolean,
     *                     or an object with a property {@code Person} does not declare or a property value its
     *                     declared type cannot take, or when writing the reply fails
     */
    @PostMapping("/person")
    public void storePerson(@RequestBody(required = false) byte[] body, HttpServletResponse response)
            throws IOException {
        int id = personService.storePerson(body);
        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setHeader(HttpHeaders.LOCATION, LOCATION_PREFIX + id);
        writeUntypedPayload(response, PersonService.STORE_SUCCESS_RESPONSE);
    }

    /**
     * {@code retrievePersonFlow}: the person stored under {@code personId}.
     *
     * <p>The URI parameter stays a {@link String}: the service converts it with {@code Integer.valueOf} as the flow
     * did (line 31), so a non-numeric id reaches SC-1's 500 with the {@link NumberFormatException} message rather
     * than Spring's 400 type mismatch.
     *
     * <p>The reply is written as 200 with the UTF-8 bytes of the stored person's JSON, their {@code Content-Length}
     * and no {@code Content-Type}: the untyped {@code set-payload} of line 36, which no transformer follows.
     *
     * @param personId the {@code personId} URI parameter, unconverted
     * @param response the response the stored person is written to once it is found
     * @throws IOException when the stored person cannot be serialized or writing the reply fails
     */
    @GetMapping("/person/{personId}")
    public void getPerson(@PathVariable("personId") String personId, HttpServletResponse response)
            throws IOException {
        String person = personService.retrievePersonJson(personId);
        response.setStatus(HttpServletResponse.SC_OK);
        writeUntypedPayload(response, person);
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

    /**
     * Writes a payload whose flow declared no MIME type: its UTF-8 bytes and their {@code Content-Length}, and no
     * {@code Content-Type}, on a response whose status the caller has set. Writing to the servlet response directly
     * keeps every message converter, and the Content-Type it would add, out of the reply.
     *
     * @param response the response to write to
     * @param payload  the flow's payload
     * @throws IOException when writing the reply fails
     */
    private static void writeUntypedPayload(HttpServletResponse response, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(bytes);
        stream.flush();
    }
}
