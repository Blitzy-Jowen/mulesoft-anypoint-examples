/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;

import jakarta.servlet.http.HttpServletRequest;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.Order;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.OrderItem;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.orders.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.samsung.ObjectFactory;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.soap.ProcessOrder;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.soap.ProcessOrderResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.XmlMappingException;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.oxm.mime.MimeContainer;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodArgumentResolver;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.springframework.xml.transform.TransformerHelper;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Spring WS wiring of the two SOAP services the original exposed as {@code cxf:jaxws-service} flows behind POST-only
 * HTTP listeners ({@code fulfillment.xml:28-30,150-154}), replacing its {@code cxf:configuration}:
 * <ul>
 *   <li>{@code IProcessOrder.processOrder} on 1080 {@code /orders}, operation element
 *       {@code {http://orders.se.mulesoft.com/}processOrder};</li>
 *   <li>{@code SamsungService.purchase} on 9090 {@code /samsung/orders}, operation element
 *       {@code {http://samsung.se.mulesoft.com/}purchase}.</li>
 * </ul>
 * The {@code @PayloadRoot} endpoints are found by Spring Boot's annotation-driven endpoint mappings, which receive
 * this class's interceptors: {@link SoapEnvelopeStyleInterceptor} restyles every response and fault into CXF's
 * envelope form, and {@link ListenerPathGuard} keeps each operation on the listener path that served it. The ports
 * themselves are SC-10's: {@code PortRoutingFilter} forwards 1080 {@code /orders} and 9090 {@code /samsung/orders} to
 * {@code messageDispatcherServlet}, whose Boot registration this class leaves in place.
 * <p>
 * {@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory, the listener boundary and the message
 * receiver by their bean names, {@value #MESSAGE_FACTORY_BEAN_NAME},
 * {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME} and {@value #MESSAGE_RECEIVER_BEAN_NAME}. Both listeners
 * accept POST only, so the boundary answers any other method with the listener's 405, {@code GET <path>?wsdl}
 * included: no WSDL or schema is published, and {@code orders.wsdl}/{@code samsung.wsdl} stay reference documents
 * that nothing reads, since the active JAXB bindings are the contract. {@link #saajDefaultTransformerFactory()} makes
 * SAAJ use the JDK's transformer factory directly rather than look one up for every message it parses or writes.
 * <p>
 * Each service has its own marshaller, as CXF held one JAXB context per service: the JAXB reference implementation
 * declares every namespace its context knows on the marshalled root, so a context spanning both services would add
 * the other service's namespace to every payload root.
 * <p>
 * Spring WS's {@code EnableWs} annotation is deliberately absent: it would switch off Spring Boot's web-services
 * auto-configuration and with it the {@code MessageDispatcherServlet}. This class contributes through
 * {@link WsConfigurer} instead, which Boot's own web-services configuration picks up.
 */
@Configuration(proxyBeanMethods = false)
public class WebServiceConfig implements WsConfigurer {

    /** Name under which {@code MessageDispatcherServlet} looks up its message factory. */
    public static final String MESSAGE_FACTORY_BEAN_NAME = "messageFactory";

    /** Name under which {@code MessageDispatcherServlet} looks up the adapter that receives every SOAP request. */
    public static final String MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME = "messageReceiverHandlerAdapter";

    /** Name under which {@code MessageDispatcherServlet} looks up the receiver it hands every SOAP request to. */
    public static final String MESSAGE_RECEIVER_BEAN_NAME = "messageReceiver";

    /** Bean name of the marshaller of the {@code IProcessOrder} service. */
    public static final String ORDERS_MARSHALLER_BEAN_NAME = "ordersMarshaller";

    /** Bean name of the marshaller of the {@code SamsungService} service and its client. */
    public static final String SAMSUNG_MARSHALLER_BEAN_NAME = "samsungMarshaller";

    /** Listener path of {@code IProcessOrder} on 1080, {@code path="orders"} ({@code fulfillment.xml:29}). */
    public static final String ORDERS_PATH = "/orders";

    /** Listener path of {@code SamsungService} on 9090, {@code path="samsung/orders"} ({@code fulfillment.xml:151}). */
    public static final String SAMSUNG_PATH = "/samsung/orders";

    /** Target namespace of {@code SamsungService}, that of the generated {@code model.samsung} package. */
    public static final String SAMSUNG_NAMESPACE_URI = "http://samsung.se.mulesoft.com/";

    /** Local name of the {@code purchase} request wrapper; {@link ObjectFactory} keeps its QName private. */
    public static final String PURCHASE_LOCAL_PART = "purchase";

    /** The SOAP listener paths whose {@code http:listener} declares {@code allowedMethods="POST"}. */
    public static final List<String> POST_ONLY_PATHS = List.of(ORDERS_PATH, SAMSUNG_PATH);

    /** JVM system property under which SAAJ creates the JDK's transformer factory instead of looking one up. */
    public static final String SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY = "saaj.use.default.transformer.factory";

    private final SoapEnvelopeStyleInterceptor styler;
    private final Jaxb2Marshaller ordersMarshaller;
    private final Jaxb2Marshaller samsungMarshaller;

    /**
     * @param styler            the CXF envelope styler applied to every endpoint response and fault
     * @param ordersMarshaller  the managed {@link #ordersMarshaller()} bean
     * @param samsungMarshaller the managed {@link #samsungMarshaller()} bean; both bean methods are static, so
     *                          injecting their beans here creates no cycle
     */
    public WebServiceConfig(SoapEnvelopeStyleInterceptor styler,
            @Qualifier(ORDERS_MARSHALLER_BEAN_NAME) Jaxb2Marshaller ordersMarshaller,
            @Qualifier(SAMSUNG_MARSHALLER_BEAN_NAME) Jaxb2Marshaller samsungMarshaller) {
        this.styler = styler;
        this.ordersMarshaller = ordersMarshaller;
        this.samsungMarshaller = samsungMarshaller;
    }

    /**
     * The SOAP 1.1 message factory shared by the dispatcher servlet, the listener boundary and
     * {@code client/SamsungServiceClient}. {@code faultstring} is written without {@code xml:lang}, as CXF wrote it.
     * The container initializes it. It is SC-11's {@link SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory}, so
     * characters outside the Basic Multilingual Plane are written as raw UTF-8, as CXF wrote them.
     */
    @Bean(name = MESSAGE_FACTORY_BEAN_NAME)
    public SaajSoapMessageFactory messageFactory() {
        SaajSoapMessageFactory messageFactory = new SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        messageFactory.setLangAttributeOnSoap11FaultString(false);
        return messageFactory;
    }

    /**
     * Makes SAAJ create the JDK's built-in transformer factory directly. Unless
     * {@value #SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY} is {@code true}, SAAJ looks a factory up through the JAXP
     * service mechanism for every transformer it builds, that is for every envelope it parses and every envelope it
     * writes, and each lookup scans every jar of the application's class path under one lock, so concurrent SOAP
     * requests queue behind one another; the original CXF/StAX stack made no lookup per message. No dependency
     * registers a {@code javax.xml.transform.TransformerFactory} service, so that lookup ends at the same JDK XSLTC
     * factory this switch selects, and every message is read and written exactly as before. The raw UTF-8 write of
     * SC-11's {@link SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory} messages honours the same switch.
     * <p>
     * A bean factory post-processor runs before any regular bean is instantiated, and so before any SAAJ message is
     * created; it is static so that this configuration class is not instantiated that early. A value already set,
     * such as an operator's {@code -Dsaaj.use.default.transformer.factory=false}, is left unchanged.
     *
     * @return the post-processor that sets {@value #SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY} to {@code true}
     */
    @Bean
    public static BeanFactoryPostProcessor saajDefaultTransformerFactory() {
        return beanFactory -> {
            if (System.getProperty(SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY) != null) {
                return;
            }
            System.setProperty(SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY, Boolean.TRUE.toString());
        };
    }

    /**
     * Binds the {@code processOrder} wrappers and the order model they carry, {@code OrderPerson} and {@code Status}
     * through {@link Order}. No {@code model.samsung} class belongs here: its namespace would then be declared on
     * {@code processOrderResponse} beside the orders namespace, and the reply would no longer read
     * {@code <ns2:processOrderResponse xmlns:ns2="http://orders.se.mulesoft.com/">} as the original's did.
     */
    @Bean(name = ORDERS_MARSHALLER_BEAN_NAME)
    public static Jaxb2Marshaller ordersMarshaller() {
        Jaxb2Marshaller marshaller = new CxfStyleJaxb2Marshaller();
        marshaller.setClassesToBeBound(ProcessOrder.class, ProcessOrderResponse.class, Order.class, OrderItem.class,
                PurchaseReceipt.class);
        return marshaller;
    }

    /**
     * Binds the generated {@code model.samsung} package through its {@link ObjectFactory}, whose element
     * declarations supply the {@code purchase} and {@code purchaseResponse} roots and whose {@code package-info}
     * supplies the namespace. A context path rather than bound classes, because {@code supports} accepts a
     * {@code JAXBElement<Purchase>} only when the type argument's package is the context path or the type argument
     * itself is bound.
     */
    @Bean(name = SAMSUNG_MARSHALLER_BEAN_NAME)
    public static Jaxb2Marshaller samsungMarshaller() {
        Jaxb2Marshaller marshaller = new CxfStyleJaxb2Marshaller();
        marshaller.setContextPath(ObjectFactory.class.getPackageName());
        return marshaller;
    }

    /**
     * The listener boundary of both SOAP listeners: a non-POST request to either path gets the listener's 405
     * ({@code Method not allowed for endpoint: <path>}), and a body that is not a SOAP 1.1 envelope gets a
     * {@code soap:Client} fault over HTTP 500.
     */
    @Bean(name = MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapListenerBoundaryAdapter messageReceiverHandlerAdapter(WebServiceMessageFactory messageFactory) {
        return new SoapListenerBoundaryAdapter(messageFactory, POST_ONLY_PATHS, styler);
    }

    /**
     * The receiver the servlet hands every SOAP request to once the listener boundary has read it: Spring WS dispatch
     * with CXF's MustUnderstand fault (see {@link SoapFaultConfig.CxfStyleSoapMessageDispatcher}). Like the servlet's
     * default receiver, it collects every endpoint mapping, endpoint adapter and exception resolver bean of the
     * context when the context is set on it.
     */
    @Bean(name = MESSAGE_RECEIVER_BEAN_NAME)
    public SoapMessageDispatcher messageReceiver() {
        return new SoapFaultConfig.CxfStyleSoapMessageDispatcher(styler);
    }

    /**
     * Puts the two service marshallers ahead of Spring WS's built-in JAXB processors, which this list already holds
     * and which would otherwise claim both operations' payloads. Request payloads read the same either way.
     */
    @Override
    public void addArgumentResolvers(List<MethodArgumentResolver> argumentResolvers) {
        argumentResolvers.addAll(0, List.of(new MarshallingPayloadMethodProcessor(ordersMarshaller),
                new MarshallingPayloadMethodProcessor(samsungMarshaller)));
    }

    /**
     * Puts the two service marshallers ahead of Spring WS's built-in JAXB processors, which this list already holds.
     * Those processors marshal straight into the SOAP body, where the JAXB reference implementation counts the
     * envelope's in-scope namespace and writes {@code ns3} where CXF wrote {@code ns2}; see
     * {@link CxfStyleJaxb2Marshaller}. The two marshallers' supported types are disjoint: {@code ProcessOrder} and
     * {@code ProcessOrderResponse} reach only the first, {@code JAXBElement<Purchase>} and
     * {@code JAXBElement<PurchaseResponse>} only the second.
     */
    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        returnValueHandlers.addAll(0, List.of(new MarshallingPayloadMethodProcessor(ordersMarshaller),
                new MarshallingPayloadMethodProcessor(samsungMarshaller)));
    }

    /**
     * Reaches Boot's annotation-driven endpoint mappings. The styler comes first, so it runs last on the way out and
     * its restyling is the final change to every response and fault.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
        interceptors.add(new ListenerPathGuard(ListenerPathGuard.OPERATION_LISTENER_PATHS));
    }

    /**
     * Confines each operation to the listener path of the service that declares it. Spring WS maps an endpoint by
     * the request's payload root alone, and one {@code messageDispatcherServlet} serves both listeners, so without
     * this guard a {@code purchase} posted to 1080 {@code /orders} would reach the Samsung service. The original
     * listeners each fronted one CXF service, which answered a message part outside its WSDL with a
     * {@code soap:Client} fault; this guard throws {@link SoapFaultConfig.UnknownOperationException}, which
     * {@code SoapFaultConfig} turns into that fault with CXF's {@code Message part ... was not recognized.} text.
     * <p>
     * The path compared is the request URI without the context path ({@code /} when nothing is left), the form the
     * SC-10 forwards preserve. A payload root the map does not name is let through: no endpoint is mapped to it, so
     * the catch-all mapping answers it. A request without an HTTP servlet connection, such as one dispatched by
     * {@code MockWebServiceClient}, has no listener path and is let through as well. The guard holds no per-request
     * state, so one instance serves all requests concurrently.
     */
    public static class ListenerPathGuard implements EndpointInterceptor {

        /**
         * Each operation element and the listener path of its service; one entry per {@code @PayloadRoot} of
         * {@code controller/OrderProcessingEndpoint} and {@code controller/SamsungServiceEndpoint}.
         */
        public static final Map<QName, String> OPERATION_LISTENER_PATHS = Map.of(
                new QName(ProcessOrder.NAMESPACE_URI, ProcessOrder.LOCAL_PART), ORDERS_PATH,
                new QName(SAMSUNG_NAMESPACE_URI, PURCHASE_LOCAL_PART), SAMSUNG_PATH);

        private static final Logger log = LoggerFactory.getLogger(ListenerPathGuard.class);

        private static final String ROOT_PATH = "/";

        private final Map<QName, String> listenerPaths;

        /**
         * @param listenerPaths each guarded operation element's qualified name and the only request path, within the
         *                      application, on which it is served; copied, and neither keys nor values may be
         *                      {@code null}
         */
        public ListenerPathGuard(Map<QName, String> listenerPaths) {
            Assert.notNull(listenerPaths, "listenerPaths must not be null");
            this.listenerPaths = Map.copyOf(listenerPaths);
        }

        /**
         * @throws SoapFaultConfig.UnknownOperationException when the request's payload root is a guarded operation
         *                                                   received on a path other than its listener's
         * @throws javax.xml.transform.TransformerException  when the payload root cannot be read
         */
        @Override
        public boolean handleRequest(MessageContext messageContext, Object endpoint) throws Exception {
            String path = requestPath();
            if (path == null) {
                return true;
            }
            // A helper per request: it creates a TransformerFactory only for a payload that is not already a DOM,
            // and a factory is not guaranteed to be thread-safe.
            QName payloadRoot = PayloadRootUtils.getPayloadRootQName(messageContext.getRequest().getPayloadSource(),
                    new TransformerHelper());
            String listenerPath = payloadRoot == null ? null : listenerPaths.get(payloadRoot);
            if (listenerPath != null && !listenerPath.equals(path)) {
                log.debug("Operation {} is served on {} only; answering soap:Client", payloadRoot, listenerPath);
                throw new SoapFaultConfig.UnknownOperationException(payloadRoot);
            }
            return true;
        }

        @Override
        public boolean handleResponse(MessageContext messageContext, Object endpoint) {
            return true;
        }

        @Override
        public boolean handleFault(MessageContext messageContext, Object endpoint) {
            return true;
        }

        @Override
        public void afterCompletion(MessageContext messageContext, Object endpoint, Exception ex) {
            // The guard decides in handleRequest; it holds nothing to release.
        }

        /**
         * The current request's URI without the context path, {@code /} when nothing is left, or {@code null} when
         * the request did not arrive over an HTTP servlet connection.
         */
        private static String requestPath() {
            TransportContext transportContext = TransportContextHolder.getTransportContext();
            WebServiceConnection connection = transportContext == null ? null : transportContext.getConnection();
            if (!(connection instanceof HttpServletConnection servletConnection)) {
                return null;
            }
            HttpServletRequest request = servletConnection.getHttpServletRequest();
            String uri = request.getRequestURI();
            if (uri == null) {
                uri = "";
            }
            String contextPath = request.getContextPath();
            if (StringUtils.hasLength(contextPath) && uri.startsWith(contextPath)) {
                uri = uri.substring(contextPath.length());
            }
            return uri.isEmpty() ? ROOT_PATH : uri;
        }
    }

    /**
     * Writes a payload root the way CXF's JAXB databinding wrote it into the envelope, for example
     * {@code <ns2:processOrderResponse xmlns:ns2="http://orders.se.mulesoft.com/">}.
     * <p>
     * Marshalling straight into a DOM node, as the SOAP body's payload result asks, makes the JAXB reference
     * implementation pre-declare the namespaces in scope at that node: the envelope's namespace takes a prefix slot
     * and the payload is written as {@code ns3}. Into a DOM node this marshaller therefore writes a document of its
     * own first, where only the payload's namespaces are in scope, and places a copy of its root under the node.
     * Every other result, unmarshalling and {@code supports} keep the inherited behaviour. The JAXB context and
     * settings are the inherited ones and a JAXB marshaller is created per call, so one instance serves concurrent
     * callers.
     */
    static final class CxfStyleJaxb2Marshaller extends Jaxb2Marshaller {

        /** {@code mimeContainer} may be {@code null}, as the inherited contract allows. */
        @Override
        public void marshal(Object graph, Result result, MimeContainer mimeContainer)
                throws XmlMappingException {
            if (result instanceof DOMResult target && target.getNode() != null) {
                DOMResult detached = new DOMResult();
                super.marshal(graph, detached, mimeContainer);
                appendCopy(((Document) detached.getNode()).getDocumentElement(), target);
                return;
            }
            super.marshal(graph, result, mimeContainer);
        }

        /** Places a copy of {@code element} under the target node, before its next sibling when one is set. */
        private static void appendCopy(Node element, DOMResult target) {
            Node parent = target.getNode();
            Document owner = parent instanceof Document document ? document : parent.getOwnerDocument();
            Node copy = owner.importNode(element, true);
            Node nextSibling = target.getNextSibling();
            if (nextSibling != null) {
                parent.insertBefore(copy, nextSibling);
            }
            else {
                parent.appendChild(copy);
            }
        }
    }
}
