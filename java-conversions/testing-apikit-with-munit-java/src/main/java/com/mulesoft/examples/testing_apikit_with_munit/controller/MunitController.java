package com.mulesoft.examples.testing_apikit_with_munit.controller;

import java.util.List;

import com.mulesoft.examples.testing_apikit_with_munit.config.ApiKitAcceptNegotiator;
import com.mulesoft.examples.testing_apikit_with_munit.service.MunitService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resource {@code /munit} of {@code api/api.raml}, served under the APIkit listener path
 * {@code /api/*} of the original {@code testing-apikit-with-munit/src/main/app/api.xml}. It replaces the
 * {@code api-main} flow's {@code apikit:router} and the four {@code apikit:flow-mapping}s that routed
 * {@code /munit} to the flows {@code get:}, {@code post:}, {@code put:} and {@code delete:/munit:api-config}.
 *
 * <p>Each method answers with the 2xx status its RAML method declares (GET 200, POST 201, PUT 201,
 * DELETE 200), {@code Content-Type: application/json} and the flow payload from {@link MunitService}
 * unchanged, which is already the JSON string the MUnit suite asserts. The RAML declares no request body,
 * parameter or schema, so no request input is bound and any request body is accepted and ignored, as
 * APIkit accepted it.
 *
 * <p>Error responses come from the advices: a method the resource does not declare raises
 * {@link HttpRequestMethodNotSupportedException}, an {@code Accept} header excluding JSON, or one under which
 * {@link ApiKitAcceptNegotiator} chooses no declared type, raises {@link HttpMediaTypeNotAcceptableException}, and
 * {@code ApiKitExceptionHandler} answers them with APIkit's 405 and 406 mappings. Each method negotiates
 * immediately before it calls {@link MunitService}, GET after its HEAD check, as APIkit negotiated after its method
 * check and before the flow ran.
 */
@RestController
@RequestMapping("${apikit.base-path:/api}/munit")
public class MunitController {

    /** The methods {@code /munit} declares in the RAML. */
    private static final List<String> SUPPORTED_METHODS = List.of("GET", "POST", "PUT", "DELETE");

    private final MunitService munitService;

    /**
     * @param munitService the flow logic of the four {@code /munit} methods
     */
    public MunitController(MunitService munitService) {
        this.munitService = munitService;
    }

    /**
     * Routes to flow {@code get:/munit:api-config}.
     *
     * @param request the current request, whose method is checked and whose {@code Accept} header is negotiated
     * @return 200 with the GET flow payload
     * @throws HttpRequestMethodNotSupportedException for a HEAD request, which the RAML does not declare
     * @throws HttpMediaTypeNotAcceptableException    when APIkit's negotiation chooses no declared type for the
     *                                                {@code Accept} header
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> get(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException, HttpMediaTypeNotAcceptableException {
        // Spring maps HEAD to GET handlers implicitly; this keeps get() from ever answering HEAD, whichever
        // mapping the dispatcher selects for it.
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
        }
        ApiKitAcceptNegotiator.negotiate(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(munitService.get());
    }

    /**
     * Routes to flow {@code post:/munit:api-config}.
     *
     * @param request the current request, whose {@code Accept} header is negotiated
     * @return 201 with the POST flow payload
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(munitService.post());
    }

    /**
     * Routes to flow {@code put:/munit:api-config}.
     *
     * @param request the current request, whose {@code Accept} header is negotiated
     * @return 201 with the PUT flow payload
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> put(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(munitService.put());
    }

    /**
     * Routes to flow {@code delete:/munit:api-config}.
     *
     * @param request the current request, whose {@code Accept} header is negotiated
     * @return 200 with the DELETE flow payload
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> delete(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(munitService.delete());
    }

    /**
     * Rejects HEAD and OPTIONS, which the RAML does not declare, so they reach APIkit's 405 mapping. Without
     * this mapping Spring would answer OPTIONS itself with 200 and an {@code Allow} header. No
     * {@code produces} is declared, so the mapping also claims HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<String> headOrOptions(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
    }
}
