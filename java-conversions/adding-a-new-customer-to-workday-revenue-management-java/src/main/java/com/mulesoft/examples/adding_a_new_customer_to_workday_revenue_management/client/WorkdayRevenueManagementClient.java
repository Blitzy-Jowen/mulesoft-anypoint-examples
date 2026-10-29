/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.Objects;

import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMResult;

import org.apache.wss4j.dom.WSConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.http.HttpTransportException;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Document;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBIntrospector;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;

/**
 * SOAP 1.1 client of the Workday {@code Revenue_Management} web service, version v25.0, the one outbound system of the
 * example.
 *
 * <p>Replaces the Mule Workday connector of {@code add_a_new_customer.xml}:
 * <ul>
 *   <li>line 5, {@code <wd-connector:config hostName="${wday.hostname}" tenantName="${wday.tenant}"
 *       username="${wday.user}" password="${wday.password}"/>}: the endpoint is {@code wday.endpoint}, which
 *       {@code application.yml} derives from host and tenant, and every request carries a WS-Security UsernameToken
 *       named {@code wday.user@wday.tenant} with a {@code PasswordText} password, as the connector sent it;</li>
 *   <li>line 42, {@code <wd-connector:invoke type="Revenue_Management||Put_Customer"/>}: {@link #putCustomer};</li>
 *   <li>the MUnit suite's {@code Revenue_Management||Get_Customers} and inactivating {@code Put_Customer} invocations
 *       ({@code integration-test.xml} lines 80 and 119): {@link #sendRaw}, used by the live test's request helper.</li>
 * </ul>
 *
 * <p>Payloads are bound by Jakarta JAXB classes that XJC generates from {@code wsdl/Revenue_Management_v25.0.wsdl}.
 * The WSDL's binding declares no {@code soapAction} for any operation, so no action is set and the SOAP 1.1 default
 * {@code SOAPAction: ""} goes on the wire. Both connect and read timeouts come from {@code wday.connect-timeout} and
 * {@code wday.read-timeout} (10 s, the Mule default response timeout). Each request goes out in fixed-length
 * streaming mode, with its {@code Content-Length} header, so the JDK never silently re-sends a {@code Put_Customer}
 * POST after a connection reset or an early end of stream while the response is awaited: Workday receives each
 * attempt once, and the failure surfaces instead.
 *
 * <p>Each call first checks its {@code wday.*} values with {@link WorkdayProperties#requireConfigured()}: a missing
 * or invalid one throws {@link IllegalStateException} naming the key, so nothing is logged or sent, no
 * re-authentication runs and nothing is retried; the flow's caller receives SC-1's 500 with that message. The
 * constructor checks nothing, so the application starts with every value empty.
 *
 * <p>Every call then runs through {@link UpstreamCallExecutor} for {@link UpstreamSystem#WORKDAY}, with
 * {@link #reauthenticate()} as the single re-authentication step before its one retry. This class therefore catches,
 * wraps and retries nothing: a SOAP fault escapes as Spring WS's {@code SoapFaultClientException}, an HTTP error
 * without a fault as {@link HttpStatusException} carrying the status and {@code Retry-After}, and a timeout or
 * connection failure as {@code WebServiceIOException} with the socket exception as cause, so
 * {@code WorkdayFailureClassifier} sees each one unchanged.
 *
 * <p>Neither the password nor the UsernameToken's {@code user@tenant} principal is ever logged, and neither is any
 * request envelope, whose security header carries both.
 *
 * <p>Thread-safe: the template, its message factory, marshaller and sender are configured once and are safe for
 * concurrent use; {@link #reauthenticate()} replaces the security interceptor under the instance lock.
 */
@Component
public class WorkdayRevenueManagementClient {

    private static final Logger log = LoggerFactory.getLogger(WorkdayRevenueManagementClient.class);

    /** Package XJC generates the Revenue_Management v25.0 classes into ({@code workday.generated.package}). */
    static final String GENERATED_PACKAGE =
            "com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated";

    /** WSS4J action that adds a {@code wsse:UsernameToken} to every request. */
    private static final String USERNAME_TOKEN_ACTION = "UsernameToken";

    private final WorkdayProperties properties;

    private final UpstreamCallExecutor executor;

    private final WebServiceTemplate template;

    /**
     * Builds the SOAP 1.1 template once and installs the UsernameToken of the current credentials.
     *
     * @param properties the {@code wday.*} settings: endpoint, credentials and timeouts
     * @param executor   the SC-9 executor every Workday call runs through
     * @throws IllegalStateException if the SAAJ message factory, the JAXB marshaller or the security interceptor
     *                               cannot be configured
     */
    public WorkdayRevenueManagementClient(WorkdayProperties properties, UpstreamCallExecutor executor) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.template = createTemplate(properties);
        this.template.setInterceptors(new ClientInterceptor[] {securityInterceptor()});
    }

    /**
     * Adds or updates a customer with the {@code Put_Customer} operation.
     *
     * @param request the {@code wd:Put_Customer_Request} element built by {@code PutCustomerRequestMapper}, marshalled
     *                as given
     * @return the unwrapped {@code Put_Customer_Response}, or {@code null} when Workday answers with an empty body
     * @throws IllegalStateException a required {@code wday.*} value is missing or invalid, before anything is sent
     *                               (see {@link WorkdayProperties#requireConfigured()}), or the response body is
     *                               not a {@code Put_Customer_Response}
     * @throws Exception             the vendor failure unchanged, or one of the three upstream exceptions of
     *                               {@link UpstreamCallExecutor}
     */
    public PutCustomerResponseType putCustomer(JAXBElement<PutCustomerRequestType> request) throws Exception {
        Objects.requireNonNull(request, "request");
        properties.requireConfigured();
        log.info("Calling Workday Put_Customer at {}", properties.getEndpoint());
        return executor.call(UpstreamSystem.WORKDAY,
                () -> unwrapPutCustomerResponse(template.marshalSendAndReceive(properties.getEndpoint(), request)),
                this::reauthenticate);
    }

    /**
     * Sends an already-built Revenue_Management request body, with the same UsernameToken, timeouts and failure
     * handling as {@link #putCustomer}. The live test's request helper sends the MUnit suite's
     * {@code Get_Customers_Request} and inactivating {@code Put_Customer_Request} through it.
     *
     * <p>The body is read once per attempt, and the executor retries once after an authentication failure, so the
     * source must be re-readable: pass a {@link javax.xml.transform.dom.DOMSource}, never a stream source.
     *
     * @param body the request payload, placed as the child of the SOAP Body
     * @return the response payload (the SOAP Body's child) as its own {@link Document}, or {@code null} when Workday
     *         answers with an empty body
     * @throws IllegalStateException a required {@code wday.*} value is missing or invalid, before anything is sent
     *                               (see {@link WorkdayProperties#requireConfigured()})
     * @throws Exception             the vendor failure unchanged, or one of the three upstream exceptions of
     *                               {@link UpstreamCallExecutor}
     */
    public Document sendRaw(Source body) throws Exception {
        Objects.requireNonNull(body, "body");
        properties.requireConfigured();
        log.info("Calling Workday Revenue_Management at {}", properties.getEndpoint());
        return executor.call(UpstreamSystem.WORKDAY, () -> {
            DOMResult result = new DOMResult();
            template.sendSourceAndReceiveToResult(properties.getEndpoint(), body, result);
            return (Document) result.getNode();
        }, this::reauthenticate);
    }

    /**
     * Rebuilds the UsernameToken interceptor from the current {@code wday.*} credentials and installs it in place of
     * the previous one: the single re-authentication step {@link UpstreamCallExecutor} runs before its one retry.
     * Workday's UsernameToken is stateless, so there is no session to renew and no network call is made.
     *
     * <p>The INFO line is fixed text: it names neither the token's {@code user@tenant} principal nor the password.
     *
     * @throws IllegalStateException if the interceptor cannot be configured
     */
    public synchronized void reauthenticate() {
        log.info("Re-authenticating Workday UsernameToken");
        template.setInterceptors(new ClientInterceptor[] {securityInterceptor()});
    }

    /**
     * Builds a new WS-Security interceptor that adds a {@code PasswordText} UsernameToken without nonce or creation
     * time, the token the Workday connector sent. Responses are not validated: Workday returns no security header.
     */
    private Wss4jSecurityInterceptor securityInterceptor() {
        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        interceptor.setSecurementActions(USERNAME_TOKEN_ACTION);
        interceptor.setSecurementUsername(properties.username());
        interceptor.setSecurementPassword(properties.getPassword());
        // Spring's default is PasswordDigest; Workday accepts only the clear-text password type.
        interceptor.setSecurementPasswordType(WSConstants.PW_TEXT);
        interceptor.setSecurementUsernameTokenNonce(false);
        interceptor.setSecurementUsernameTokenCreated(false);
        interceptor.setValidateRequest(false);
        interceptor.setValidateResponse(false);
        try {
            interceptor.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure the Workday WS-Security interceptor", e);
        }
        return interceptor;
    }

    /**
     * Returns the {@code Put_Customer_Response} carried by the unmarshalled body: the generated classes bind global
     * elements as {@link JAXBElement}s, so the value is taken out of its wrapper.
     */
    private static PutCustomerResponseType unwrapPutCustomerResponse(Object result) {
        if (result == null) {
            return null;
        }
        Object value = JAXBIntrospector.getValue(result);
        if (value instanceof PutCustomerResponseType response) {
            return response;
        }
        throw new IllegalStateException("Unexpected Workday Put_Customer response: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Configures the SOAP 1.1 template: SAAJ messages, the generated JAXB classes, and an
     * {@code HttpURLConnection} sender with the configured timeouts that streams every request with a fixed length
     * ({@link FixedLengthStreamingMessageSender}), so the JDK never re-sends a request on its own. No default URI is
     * set, because every call passes {@code wday.endpoint} as read at call time.
     */
    private static WebServiceTemplate createTemplate(WorkdayProperties properties) {
        SaajSoapMessageFactory messageFactory;
        try {
            messageFactory = new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        } catch (SOAPException e) {
            throw new IllegalStateException("Could not create the SOAP 1.1 message factory", e);
        }
        messageFactory.afterPropertiesSet();

        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(GENERATED_PACKAGE);
        // The Revenue_Management schema binds ~900 classes; creating the JAXBContext on first use keeps start-up fast.
        marshaller.setLazyInit(true);
        try {
            marshaller.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Could not configure the Workday JAXB marshaller", e);
        }

        HttpUrlConnectionMessageSender messageSender = new FixedLengthStreamingMessageSender();
        messageSender.setConnectionTimeout(properties.getConnectTimeout());
        messageSender.setReadTimeout(properties.getReadTimeout());

        WorkdayWebServiceTemplate template = new WorkdayWebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(messageSender);
        return template;
    }

    /**
     * {@code HttpURLConnection} sender whose connections stream each request with a fixed length.
     *
     * <p>The JDK re-sends a buffered {@code HttpURLConnection} request once, POST included, when the connection is
     * reset or the stream ends while the response is awaited, which is after the server may already have processed
     * it. It never re-sends a streamed request. The connection is prepared exactly as the plain sender prepares it:
     * POST, no caches, {@code Accept-Encoding: gzip} and the configured connect and read timeouts.
     */
    private static final class FixedLengthStreamingMessageSender extends HttpUrlConnectionMessageSender {

        /**
         * Opens and prepares the connection as the plain sender does, and wraps it in a
         * {@link FixedLengthStreamingConnection}.
         *
         * @throws HttpTransportException if the URI does not open an HTTP connection
         */
        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            URLConnection connection = uri.toURL().openConnection();
            if (!(connection instanceof HttpURLConnection httpConnection)) {
                throw new HttpTransportException("URI [" + uri + "] is not an HTTP URL");
            }
            prepareConnection(httpConnection);
            return new FixedLengthStreamingConnection(httpConnection);
        }
    }

    /**
     * Connection that buffers the serialized envelope, then streams it in fixed-length mode, so the request keeps its
     * {@code Content-Length} header and is never re-sent by the JDK. Request headers are all added before the
     * envelope is written, and nothing reaches the socket until the envelope is complete.
     */
    private static final class FixedLengthStreamingConnection extends HttpUrlConnection {

        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        FixedLengthStreamingConnection(HttpURLConnection connection) {
            super(connection);
        }

        /** Returns the in-memory buffer the envelope is serialized into. */
        @Override
        protected OutputStream getRequestOutputStream() {
            return requestBody;
        }

        /**
         * Fixes the streamed length to the size of the buffered envelope, writes the envelope, and completes the
         * request. A failure while writing escapes as the socket's own {@link IOException}.
         */
        @Override
        protected void onSendAfterWrite(WebServiceMessage message) throws IOException {
            HttpURLConnection connection = getConnection();
            connection.setFixedLengthStreamingMode((long) requestBody.size());
            try (OutputStream out = connection.getOutputStream()) {
                requestBody.writeTo(out);
            }
            super.onSendAfterWrite(message);
        }
    }

    /**
     * Template whose HTTP error handling keeps the status code and {@code Retry-After} header that
     * {@code WorkdayFailureClassifier} needs. SOAP faults (HTTP 500 with an XML body) never reach
     * {@link #handleError}: the default {@code SoapFaultMessageResolver} turns them into
     * {@code SoapFaultClientException}.
     */
    private static final class WorkdayWebServiceTemplate extends WebServiceTemplate {

        WorkdayWebServiceTemplate(SaajSoapMessageFactory messageFactory) {
            super(messageFactory);
        }

        /**
         * Raises an {@link HttpStatusException} for an HTTP error that carries no SOAP fault. It is unchecked and no
         * {@code TransportException}, so the template passes it to the caller unwrapped.
         */
        @Override
        protected Object handleError(WebServiceConnection connection, WebServiceMessage request) throws IOException {
            if (connection instanceof HttpUrlConnection httpConnection) {
                HttpURLConnection urlConnection = httpConnection.getConnection();
                int status = urlConnection.getResponseCode();
                String retryAfter = urlConnection.getHeaderField("Retry-After");
                String message = connection.getErrorMessage();
                if (message == null || message.isBlank()) {
                    message = "HTTP " + status;
                }
                throw new HttpStatusException(message, status, retryAfter);
            }
            return super.handleError(connection, request);
        }
    }

    /**
     * An HTTP error response from Workday that carries no SOAP fault, with the status code and {@code Retry-After}
     * value {@code WorkdayFailureClassifier} classifies: 401 as an authentication failure, 429 as a rate limit.
     */
    public static class HttpStatusException extends WebServiceTransportException {

        private static final long serialVersionUID = 1L;

        private final int statusCode;

        private final String retryAfter;

        /**
         * @param message    the connection's error message, reason phrase and status such as
         *                   {@code "Unauthorized [401]"}, or {@code "HTTP <status>"} when it is blank
         * @param statusCode the HTTP status code
         * @param retryAfter the {@code Retry-After} header value exactly as received, or {@code null}
         */
        public HttpStatusException(String message, int statusCode, String retryAfter) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfter = retryAfter;
        }

        /** @return the HTTP status code of the response */
        public int statusCode() {
            return statusCode;
        }

        /** @return the {@code Retry-After} header value exactly as received, or {@code null} when absent */
        public String retryAfter() {
            return retryAfter;
        }
    }
}
