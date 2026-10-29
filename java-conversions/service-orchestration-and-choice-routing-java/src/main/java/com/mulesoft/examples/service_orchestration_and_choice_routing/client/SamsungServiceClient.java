/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBIntrospector;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.ObjectFactory;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderRequest;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.OrderResponse;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.Purchase;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.PurchaseResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceMessageExtractor;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.support.MarshallingUtils;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

/**
 * Outbound adapter of the Samsung {@code purchase} SOAP operation, replacing the sub-flow
 * {@code samsungWebServiceClient} ({@code fulfillment.xml:94-97}): its
 * {@code cxf:jaxws-client clientClass="com.mulesoft.se.samsung.SamsungServiceService" operation="purchase"
 * port="SamsungServicePort"} and the {@code http:request method="POST" path="samsung/orders" port="9090"} after it,
 * sent through {@code HTTP_Request_Configuration} ({@code mule-config.xml:4}, host {@code 0.0.0.0}), which sets no
 * {@code responseTimeout} and so uses Mule's default of 10000 ms. The generated JAX-WS {@code SamsungServiceService}
 * stub, which loaded {@code samsung.wsdl} from the classpath, is not ported: this class takes its place and reads no
 * WSDL.
 *
 * <p>The request is the document/literal wrapped {@code purchase} message of the original {@code SamsungService}
 * interface: root {@code {http://samsung.se.mulesoft.com/}purchase} from {@link ObjectFactory}, with the unqualified
 * {@code orderRequest} child carrying {@code name} and {@code quantity}, and {@code SOAPAction: ""} as
 * {@code samsung.wsdl} binds the operation. It goes to {@code orders.samsung.url}, this application's own 9090
 * {@code /samsung/orders} listener.
 *
 * <p>Status contract: {@link #purchase(OrderRequest)} returns the real HTTP status of the reply with the
 * {@code orderResponse} it carried, because the caller flow {@code samsungOrder} filters on
 * {@code http.status=200} right after this sub-flow ({@code fulfillment.xml:62-64}) and that decision belongs to the
 * caller. Every failure, a SOAP fault, a non-2xx reply, a refused connection or a timeout, propagates unchanged to
 * the caller's catch branch, which builds the {@code REJECTED} receipt. The adapter neither logs, as the original
 * sub-flow had no logger, nor retries, and its transport re-sends nothing (see {@link SingleAttemptMessageSender}).
 * The template and the marshaller are thread-safe and every call keeps its state in local variables, so one instance
 * serves concurrent orders.
 */
@Component
public class SamsungServiceClient {

    /** {@code soap:operation soapAction=""} of {@code purchase} in {@code samsung.wsdl}; written as {@code ""}. */
    private static final String PURCHASE_SOAP_ACTION = "";

    private final Jaxb2Marshaller samsungMarshaller;

    private final ObjectFactory objectFactory = new ObjectFactory();

    private final String url;

    private final WebServiceTemplate template;

    /**
     * Builds the template over a single-attempt HTTP transport. The template keeps its defaults: connection errors
     * and faults are checked, a SOAP fault raises
     * {@link org.springframework.ws.soap.client.SoapFaultClientException}, and no interceptor is added.
     *
     * @param messageFactory    the project's SOAP 1.1 message factory, the bean {@code messageFactory}
     * @param samsungMarshaller the marshaller of the {@code model.samsung} package, the bean {@code samsungMarshaller}
     * @param url               endpoint of the Samsung service, from {@code orders.samsung.url}; the default is the
     *                          value {@code application.yml} resolves with {@code ports.samsung} 9090
     * @param timeoutMs         connect and read timeout in milliseconds, from {@code orders.samsung.timeout-ms}; the
     *                          default 10000 is Mule's default response timeout
     * @throws NullPointerException     when {@code messageFactory} or {@code samsungMarshaller} is {@code null}
     * @throws IllegalArgumentException when {@code url} is not an absolute http or https URL with a host, or
     *                                  {@code timeoutMs} is not between 1 and {@link Integer#MAX_VALUE}
     */
    public SamsungServiceClient(
            @Qualifier("messageFactory") WebServiceMessageFactory messageFactory,
            @Qualifier("samsungMarshaller") Jaxb2Marshaller samsungMarshaller,
            @Value("${orders.samsung.url:http://localhost:9090/samsung/orders}") String url,
            @Value("${orders.samsung.timeout-ms:10000}") long timeoutMs) {
        Objects.requireNonNull(messageFactory, "messageFactory");
        this.samsungMarshaller = Objects.requireNonNull(samsungMarshaller, "samsungMarshaller");
        this.url = validUrl(url);
        if (timeoutMs < 1 || timeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "orders.samsung.timeout-ms must be between 1 and " + Integer.MAX_VALUE + ", was " + timeoutMs);
        }
        SingleAttemptMessageSender sender = new SingleAttemptMessageSender();
        sender.setConnectionTimeout(Duration.ofMillis(timeoutMs));
        sender.setReadTimeout(Duration.ofMillis(timeoutMs));
        // Neither CXF nor the Mule requester advertised gzip. Spring WS's default Accept-Encoding: gzip would also let
        // a compressing server turn an empty 200 into an InvalidXmlException instead of the bodiless reply below,
        // because the response-presence check reads the still-compressed bytes.
        sender.setAcceptGzipEncoding(false);
        this.template = new WebServiceTemplate(messageFactory);
        this.template.setMarshaller(samsungMarshaller);
        this.template.setUnmarshaller(samsungMarshaller);
        this.template.setDefaultUri(this.url);
        this.template.setMessageSender(sender);
    }

    /**
     * Calls {@code purchase} with the given order request.
     *
     * @param request the {@code orderRequest} to send; {@code name} and {@code quantity} as the caller mapped them
     * @return the reply's HTTP status with the {@code orderResponse} of its {@code purchaseResponse}, which may be
     *         {@code null} when the response wrapper holds none; or the reply's status with no body when a 2xx reply
     *         carries no SOAP message, as a 202 or 204 does
     * @throws NullPointerException     when {@code request} is {@code null}; nothing is sent
     * @throws IllegalStateException    when the reply's payload is not a {@code purchaseResponse}
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the service answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceTransportException  when the service answers with a non-2xx
     *                                  status that is not a SOAP fault, such as the listener's 405
     * @throws WebServiceIOException    when the connection is refused, fails or times out
     */
    public ResponseEntity<OrderResponse> purchase(OrderRequest request) {
        Objects.requireNonNull(request, "orderRequest");
        Purchase purchase = objectFactory.createPurchase();
        purchase.setOrderRequest(request);
        JAXBElement<Purchase> body = objectFactory.createPurchase(purchase);

        AtomicReference<HttpURLConnection> sentOver = new AtomicReference<>();
        WebServiceMessageCallback requestCallback = message -> {
            MarshallingUtils.marshal(samsungMarshaller, body, message);
            ((SoapMessage) message).setSoapAction(PURCHASE_SOAP_ACTION);
            sentOver.set(currentHttpConnection());
        };
        WebServiceMessageExtractor<ResponseEntity<OrderResponse>> responseExtractor = message -> {
            int status = currentHttpConnection().getResponseCode();
            Object value = JAXBIntrospector.getValue(MarshallingUtils.unmarshal(samsungMarshaller, message));
            if (!(value instanceof PurchaseResponse purchaseResponse)) {
                String type = value == null ? "none" : value.getClass().getName();
                throw new IllegalStateException("Unexpected Samsung purchase response payload: " + type);
            }
            return ResponseEntity.status(HttpStatusCode.valueOf(status)).body(purchaseResponse.getOrderResponse());
        };

        ResponseEntity<OrderResponse> reply = template.sendAndReceive(url, requestCallback, responseExtractor);
        if (reply != null) {
            return reply;
        }
        return bodilessReply(sentOver.get());
    }

    /**
     * The status of a 2xx reply without a SOAP message. The template has closed the connection by now, but the JDK
     * keeps the response code its error check already read, so no I/O takes place here.
     */
    private static ResponseEntity<OrderResponse> bodilessReply(HttpURLConnection connection) {
        if (connection == null) {
            throw new IllegalStateException("Samsung purchase reply has no HTTP connection to read its status from");
        }
        try {
            return ResponseEntity.status(HttpStatusCode.valueOf(connection.getResponseCode())).build();
        }
        catch (IOException ex) {
            throw new WebServiceIOException("I/O error: " + ex.getMessage(), ex);
        }
    }

    /** The {@link HttpURLConnection} of the exchange the template is running on this thread. */
    private static HttpURLConnection currentHttpConnection() {
        TransportContext context = TransportContextHolder.getTransportContext();
        WebServiceConnection connection = context == null ? null : context.getConnection();
        if (!(connection instanceof HttpUrlConnection httpUrlConnection)) {
            throw new IllegalStateException("Samsung purchase is not exchanged over an HTTP URL connection");
        }
        return httpUrlConnection.getConnection();
    }

    /** Rejects anything but an absolute http or https URL with a host; the message names the key, not the value. */
    private static String validUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("orders.samsung.url must not be blank");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException("orders.samsung.url is not a valid URI", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("orders.samsung.url must be an absolute http or https URL with a host");
        }
        return uri.toString();
    }

    /**
     * {@link HttpUrlConnectionMessageSender} whose connections send every request exactly once.
     *
     * <p>A default {@link HttpURLConnection} buffers the POST body and, when the connection fails while the response
     * is read (a reset, an unexpected end of stream), silently re-sends the whole request once
     * ({@code sun.net.http.retryPost}, on by default). That would place a Samsung purchase twice after the service
     * had already received it, and the original declares no retry. In fixed-length streaming mode the JDK never
     * re-sends, so each connection collects the envelope Spring WS writes and then streams it with its exact length:
     * the request keeps its {@code Content-Length} and is not chunked. Timeouts and the template's handling of the
     * reply are unchanged.
     */
    private static final class SingleAttemptMessageSender extends HttpUrlConnectionMessageSender {

        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            // The parent opens and prepares the HttpURLConnection (POST, timeouts, headers) without connecting it.
            HttpUrlConnection prepared = (HttpUrlConnection) super.createConnection(uri);
            return new FixedLengthHttpUrlConnection(prepared.getConnection());
        }
    }

    /** Collects the request body, then sends it in fixed-length streaming mode ({@link SingleAttemptMessageSender}). */
    private static final class FixedLengthHttpUrlConnection extends HttpUrlConnection {

        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        private FixedLengthHttpUrlConnection(HttpURLConnection connection) {
            super(connection);
        }

        @Override
        protected OutputStream getRequestOutputStream() {
            return requestBody;
        }

        @Override
        protected void onSendAfterWrite(WebServiceMessage message) throws IOException {
            byte[] body = requestBody.toByteArray();
            HttpURLConnection connection = getConnection();
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            super.onSendAfterWrite(message);
        }
    }
}
