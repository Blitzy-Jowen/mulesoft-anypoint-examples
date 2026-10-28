package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;

import jakarta.servlet.DispatcherType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetCreditProfileResponse;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap.GetLoanQuoteResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.oxm.XmlMappingException;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.oxm.mime.MimeContainer;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.xml.StaxUtils;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.client.support.interceptor.ClientInterceptorAdapter;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Spring WS wiring of the loan broker's SOAP services, which the original exposed as {@code cxf:jaxws-service}
 * flows (the credit agency on 18080 {@code /mule/TheCreditAgencyService}, Banks 1 to 5 on 10080 to 50080
 * {@code /mule/TheBank1} to {@code /mule/TheBank5}) and called through {@code cxf:jaxws-client} with an
 * {@code http:request} POST and {@code responseTimeout="10000"}.
 * <ul>
 *   <li>Every response and fault of the annotation-mapped endpoints is restyled into CXF's envelope form by
 *       {@link SoapEnvelopeStyleInterceptor}.</li>
 *   <li>The four {@code model.soap} wrappers are written by {@link #loanBrokerMarshaller()}, both in endpoint
 *       responses and in outbound requests, so every payload root reads
 *       {@code <ns2:getLoanQuoteResponse xmlns:ns2="http://bank.loanbroker.example.mule.org/">} as CXF wrote it.</li>
 *   <li>{@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory, the listener boundary and the
 *       message receiver by their bean names, {@value #MESSAGE_FACTORY_BEAN_NAME},
 *       {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME} and {@value #MESSAGE_RECEIVER_BEAN_NAME}.</li>
 *   <li>One {@link WebServiceTemplate} carries every outbound credit-agency and bank call; callers pass each URI per
 *       call, because a bank URI is chosen per quote. It sends each request exactly once
 *       ({@link SingleAttemptMessageSender}), so a failed call is never silently repeated.</li>
 *   <li>{@code GET <listener path>?wsdl} serves {@code wsdl/CreditAgencyService.wsdl} on the credit agency's path
 *       and {@code wsdl/BankService.wsdl} on each bank's path, as CXF published them, through
 *       {@link WsdlQueryPublisher}; no {@code WsdlDefinition} or {@code XsdSchema} bean exists, so the servlet never
 *       publishes a second copy.</li>
 * </ul>
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

    /** Listener path of the credit agency on its own port, the original listener's {@code basePath}. */
    public static final String CREDIT_AGENCY_PATH = "/mule/TheCreditAgencyService";

    /** Listener path of Bank {@code n} on its own port is this prefix followed by {@code n}. */
    public static final String BANK_PATH_PREFIX = "/mule/TheBank";

    /** Number of mock banks the original declares, Bank 1 to Bank 5. */
    public static final int BANK_COUNT = 5;

    /** Classpath location of the credit agency's published WSDL. */
    public static final String CREDIT_AGENCY_WSDL = "wsdl/CreditAgencyService.wsdl";

    /** Classpath location of the WSDL every bank publishes. */
    public static final String BANK_WSDL = "wsdl/BankService.wsdl";

    /** Matches every path below a listener path, so {@code <listener path>/<anything>?wsdl} is answered as well. */
    private static final String SUB_PATHS = "/**";

    private final SoapEnvelopeStyleInterceptor styler;
    private final Jaxb2Marshaller loanBrokerMarshaller;

    /**
     * @param styler               the CXF envelope styler applied to every endpoint response and fault
     * @param loanBrokerMarshaller the managed {@link #loanBrokerMarshaller()} bean; that bean method is static, so
     *                             injecting it here creates no cycle
     */
    public WebServiceConfig(SoapEnvelopeStyleInterceptor styler, Jaxb2Marshaller loanBrokerMarshaller) {
        this.styler = styler;
        this.loanBrokerMarshaller = loanBrokerMarshaller;
    }

    /**
     * Reaches Boot's annotation-driven endpoint mappings, so the {@code @PayloadRoot} bank and credit-agency
     * endpoints answer in CXF's envelope form.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
    }

    /**
     * Puts the loan-broker marshaller ahead of Spring WS's built-in handlers, which this list already holds, so a
     * {@code @ResponsePayload} wrapper is written by it rather than by the built-in
     * {@code XmlRootElementPayloadMethodProcessor}: that processor marshals straight into the SOAP body and so
     * writes {@code ns3} where CXF wrote {@code ns2}. Request payloads keep the built-in binding, whose result is the
     * same object either way.
     */
    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        returnValueHandlers.add(0, new MarshallingPayloadMethodProcessor(loanBrokerMarshaller));
    }

    /**
     * The SOAP 1.1 message factory shared by the dispatcher servlet, the listener boundary and the outbound template.
     * {@code faultstring} is written without {@code xml:lang}, as CXF wrote it. It is SC-11's
     * {@link SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory}, so characters outside the Basic Multilingual Plane
     * are written as raw UTF-8, as CXF wrote them.
     *
     * @throws SOAPException when no SAAJ SOAP 1.1 implementation is available
     */
    @Bean(name = MESSAGE_FACTORY_BEAN_NAME)
    public SaajSoapMessageFactory messageFactory() throws SOAPException {
        SaajSoapMessageFactory messageFactory = new SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory(
                MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        messageFactory.setLangAttributeOnSoap11FaultString(false);
        messageFactory.afterPropertiesSet();
        return messageFactory;
    }

    /**
     * The listener boundary for every SOAP request. It declares no POST-only path, because none of the original SOAP
     * listeners declares {@code allowedMethods}: each one accepted every method, and a non-POST request without a
     * metadata query is read as a message.
     */
    @Bean(name = MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapListenerBoundaryAdapter messageReceiverHandlerAdapter(SaajSoapMessageFactory messageFactory) {
        return new SoapListenerBoundaryAdapter(messageFactory, List.of(), styler);
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
     * Binds the four document/literal-wrapped operation elements and writes them as CXF did (see
     * {@link CxfStyleJaxb2Marshaller}); output stays unformatted, so marshalled payloads are as compact as CXF's.
     */
    @Bean
    public static Jaxb2Marshaller loanBrokerMarshaller() {
        Jaxb2Marshaller marshaller = new CxfStyleJaxb2Marshaller();
        marshaller.setClassesToBeBound(GetLoanQuote.class, GetLoanQuoteResponse.class, GetCreditProfile.class,
                GetCreditProfileResponse.class);
        return marshaller;
    }

    /**
     * The outbound SOAP client of the credit-agency lookup and every bank quote. Connecting and reading are each
     * bounded by the original {@code responseTimeout}. Each request is sent exactly once, through
     * {@link SingleAttemptMessageSender}: a connection reset, an unexpected end of stream or a timeout after the
     * request was written reaches the caller as {@code WebServiceIOException} instead of re-sending the request, as
     * the original sent every call once and answered any failure with the loan flow's catch branch. A SOAP fault
     * answer surfaces as {@code SoapFaultClientException} and any other HTTP error as
     * {@code WebServiceTransportException}, because both connection checks keep their default {@code true}. No
     * default URI is set: every call names its target.
     * Every request carries the {@code SOAPAction} header the CXF client sent, through
     * {@link SoapActionHeaderInterceptor}, so callers pass no message callback for it.
     *
     * @param responseTimeoutMillis {@code loanbroker.response-timeout}, in milliseconds; must be positive, because
     *                              zero would mean waiting forever
     * @throws IllegalArgumentException when the timeout is not positive
     */
    @Bean
    public WebServiceTemplate webServiceTemplate(SaajSoapMessageFactory messageFactory, Jaxb2Marshaller marshaller,
            @Value("${loanbroker.response-timeout}") long responseTimeoutMillis) {
        Assert.isTrue(responseTimeoutMillis > 0,
                () -> "loanbroker.response-timeout must be a positive number of milliseconds, but was "
                        + responseTimeoutMillis);
        Duration responseTimeout = Duration.ofMillis(responseTimeoutMillis);
        HttpUrlConnectionMessageSender messageSender = new SingleAttemptMessageSender();
        messageSender.setConnectionTimeout(responseTimeout);
        messageSender.setReadTimeout(responseTimeout);

        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(messageSender);
        template.setInterceptors(new ClientInterceptor[] {new SoapActionHeaderInterceptor()});
        return template;
    }

    /**
     * Sends a SOAP 1.1 request's {@code SOAPAction} as an explicit HTTP header. CXF's JAX-WS client sent
     * {@code SOAPAction: ""} on every SOAP 1.1 request whose operation declares no action, as WS-I Basic Profile
     * R2744 requires, whereas Spring WS 4.1's SAAJ message writes the header only once an action is set and otherwise
     * merely reports {@code ""} from {@link SoapMessage#getSoapAction()}. Setting the message's own action again
     * therefore writes {@code ""} when none was set and keeps any action a caller did set. Stateless, so the shared
     * template's concurrent calls use one instance.
     */
    static final class SoapActionHeaderInterceptor extends ClientInterceptorAdapter {

        @Override
        public boolean handleRequest(MessageContext messageContext) {
            if (messageContext.getRequest() instanceof SoapMessage request
                    && request.getVersion() == SoapVersion.SOAP_11) {
                request.setSoapAction(request.getSoapAction());
            }
            return true;
        }
    }

    /**
     * {@link HttpUrlConnectionMessageSender} whose connections send every request exactly once.
     * <p>
     * A default {@link HttpURLConnection} buffers the POST body and, when the connection fails while the response is
     * read (a reset, an unexpected end of stream), silently re-sends the whole request once
     * ({@code sun.net.http.retryPost}, on by default), so a credit agency or bank that had already received the
     * request would receive it twice. In fixed-length streaming mode the JDK never re-sends, so each connection
     * buffers the envelope Spring WS writes and then streams it with that exact length: the wire form,
     * {@code Content-Length} included, is unchanged. Timeouts, {@code Accept-Encoding} and every other connection
     * setting stay the inherited ones. Stateless, so the shared template's concurrent calls use one instance.
     */
    static final class SingleAttemptMessageSender extends HttpUrlConnectionMessageSender {

        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            // The parent opens and prepares the HttpURLConnection without connecting it.
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
            HttpURLConnection connection = getConnection();
            connection.setFixedLengthStreamingMode(requestBody.size());
            try (OutputStream out = connection.getOutputStream()) {
                requestBody.writeTo(out);
            }
            super.onSendAfterWrite(message);
        }
    }

    /**
     * Installs the WSDL publisher right after {@code PortRoutingFilter}, for request dispatches only. On a credit
     * agency or bank port a listener path reaches it unchanged; on 11081 the routing filter forwards {@code /mule/**}
     * to the loan broker, and a forward never passes through this filter, so {@code ?wsdl} there reaches the loan
     * broker, as the original's {@code /*} listener received it.
     *
     * @throws IllegalStateException when a WSDL cannot be read or parsed, which fails start-up
     */
    @Bean
    public FilterRegistrationBean<WsdlQueryPublisher> wsdlQueryPublisherRegistration() {
        WsdlQueryPublisher publisher = new WsdlQueryPublisher();
        publisher.register(new ClassPathResource(CREDIT_AGENCY_WSDL), CREDIT_AGENCY_PATH,
                CREDIT_AGENCY_PATH + SUB_PATHS);
        for (int bank = 1; bank <= BANK_COUNT; bank++) {
            String bankPath = BANK_PATH_PREFIX + bank;
            publisher.register(new ClassPathResource(BANK_WSDL), bankPath, bankPath + SUB_PATHS);
        }

        FilterRegistrationBean<WsdlQueryPublisher> registration = new FilterRegistrationBean<>(publisher);
        registration.setName("wsdlQueryPublisher");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    /**
     * Writes a bound root element the way CXF's JAXB databinding wrote it into the envelope:
     * {@code <ns2:getLoanQuote xmlns:ns2="http://bank.loanbroker.example.mule.org/">}, declaring the element's own
     * namespace and nothing else.
     * <p>
     * Two JAXB RI behaviours stand in the way of a plain {@link Jaxb2Marshaller} marshalling straight into the SOAP
     * body: JAXB declares every namespace of its context on the root element, so a context of all four wrappers adds
     * the other service's namespace and gives the credit agency's elements {@code ns3}; and it pre-declares the
     * namespaces in scope at a target DOM node, so the envelope's namespace takes the {@code ns2} slot and the
     * payload gets {@code ns3}. CXF held one context per service. This marshaller therefore writes each bound root
     * with a context of that root's class alone, whose only namespaces are the element's and the empty one of its
     * unqualified children; into a DOM node it writes a document of its own first and copies the result under the
     * node. Unmarshalling and {@code supports} keep the inherited behaviour over all four classes, as does marshalling
     * any other object, or with MTOM enabled, whose attachments only the inherited path handles.
     * <p>
     * The per-class contexts are created once and shared; a JAXB {@link Marshaller} is created per call, so one
     * instance serves concurrent callers.
     */
    static final class CxfStyleJaxb2Marshaller extends Jaxb2Marshaller {

        private final ConcurrentMap<Class<?>, JAXBContext> rootContexts = new ConcurrentHashMap<>();
        private volatile boolean mtomEnabled;

        @Override
        public void setMtomEnabled(boolean mtomEnabled) {
            super.setMtomEnabled(mtomEnabled);
            this.mtomEnabled = mtomEnabled;
        }

        /** Also creates the context of every bound root class, so a class JAXB cannot bind fails start-up. */
        @Override
        public void afterPropertiesSet() throws Exception {
            super.afterPropertiesSet();
            Class<?>[] boundClasses = getClassesToBeBound();
            if (boundClasses != null) {
                for (Class<?> boundClass : boundClasses) {
                    rootContext(boundClass);
                }
            }
        }

        @Override
        public void marshal(Object graph, Result result, @Nullable MimeContainer mimeContainer)
                throws XmlMappingException {
            if (mtomEnabled || graph == null
                    || !ObjectUtils.containsElement(getClassesToBeBound(), graph.getClass())) {
                super.marshal(graph, result, mimeContainer);
                return;
            }
            try {
                Marshaller marshaller = rootContext(graph.getClass()).createMarshaller();
                initJaxbMarshaller(marshaller);
                if (result instanceof DOMResult target && target.getNode() != null) {
                    DOMResult detached = new DOMResult();
                    marshaller.marshal(graph, detached);
                    appendCopy(((Document) detached.getNode()).getDocumentElement(), target);
                }
                else if (StaxUtils.isStaxResult(result)) {
                    marshalStax(marshaller, graph, result);
                }
                else {
                    marshaller.marshal(graph, result);
                }
            }
            catch (JAXBException ex) {
                throw convertJaxbException(ex);
            }
        }

        /** Writes to the result's StAX writer, as the inherited path does for StAX results. */
        private static void marshalStax(Marshaller marshaller, Object graph, Result result) throws JAXBException {
            XMLStreamWriter streamWriter = StaxUtils.getXMLStreamWriter(result);
            if (streamWriter != null) {
                marshaller.marshal(graph, streamWriter);
                return;
            }
            XMLEventWriter eventWriter = StaxUtils.getXMLEventWriter(result);
            if (eventWriter == null) {
                throw new IllegalArgumentException("StAX Result contains neither XMLStreamWriter nor XMLEventWriter");
            }
            marshaller.marshal(graph, eventWriter);
        }

        private JAXBContext rootContext(Class<?> rootClass) throws JAXBException {
            JAXBContext context = rootContexts.get(rootClass);
            if (context == null) {
                JAXBContext created = JAXBContext.newInstance(rootClass);
                context = rootContexts.putIfAbsent(rootClass, created);
                if (context == null) {
                    context = created;
                }
            }
            return context;
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
