package com.mulesoft.examples.soap_webservice_security.config;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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

import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;
import org.springframework.oxm.XmlMappingException;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.oxm.mime.MimeContainer;
import org.springframework.util.ObjectUtils;
import org.springframework.util.xml.StaxUtils;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.EndpointInvocationChain;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapEndpointInvocationChain;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Spring WS wiring of the six {@code Greeter} services, which the original exposed as {@code cxf:jaxws-service}
 * flows behind one {@code http:listener-config} on 63081 with {@code basePath="services"}: {@code /services/unsecure},
 * {@code /services/username}, {@code /services/signed}, {@code /services/encrypted}, {@code /services/saml} and
 * {@code /services/signedsaml}, none of them declaring {@code allowedMethods}.
 * <ul>
 *   <li>Every request is checked against its path's WS-Security action set by {@link PathScopedSecurityInterceptor}
 *       once, before any endpoint is selected, and only then dispatched, on the payload that check leaves: the
 *       decrypted body on {@code /services/encrypted}. A request whose body root no endpoint maps is therefore
 *       checked too (see {@link #messageReceiver()}). Every response and fault, the security faults included, is
 *       restyled into CXF's envelope form by {@link SoapEnvelopeStyleInterceptor}.</li>
 *   <li>The two {@code model.soap} wrappers are written by {@link #greeterMarshaller()}, both in endpoint responses
 *       and in the outbound requests of the secured clients, so every payload root reads
 *       {@code <ns2:greetResponse xmlns:ns2="http://security.example.mule.mulesoft.com/">} as CXF wrote it.</li>
 *   <li>{@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory, the listener boundary and the
 *       message receiver by their bean names, {@value #MESSAGE_FACTORY_BEAN_NAME},
 *       {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME} and {@value #MESSAGE_RECEIVER_BEAN_NAME}.
 *       {@link #saajDefaultTransformerFactory()} makes SAAJ use the JDK's transformer factory directly rather than
 *       look one up for every message it parses or writes.</li>
 *   <li>{@code GET <listener path>?wsdl} serves {@value #GREETER_WSDL} on each of the six paths, as CXF published
 *       the Greeter WSDL on each, through {@link WsdlQueryPublisher}; no {@code WsdlDefinition} or {@code XsdSchema}
 *       bean exists, so the servlet never publishes a second copy. The publisher is a filter that answers before the
 *       servlet, so metadata stays anonymous on the secured paths.</li>
 * </ul>
 * No {@code WebServiceTemplate} bean is defined: each secured client type needs its own securing interceptor, so the
 * client builds one template per type over {@link #greeterMarshaller()}.
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

    /** Name of the marshaller of the {@code greet} and {@code greetResponse} wrappers. */
    public static final String GREETER_MARSHALLER_BEAN_NAME = "greeterMarshaller";

    /** Classpath location of the WSDL every Greeter path publishes. */
    public static final String GREETER_WSDL = "wsdl/GreeterService.wsdl";

    /**
     * The six listener paths, each the original {@code basePath} {@code services} followed by its listener
     * {@code path}; the same keys the WS-Security policy table uses.
     */
    public static final List<String> GREETER_PATHS = List.of(WsSecurityConfig.UNSECURE_PATH,
            WsSecurityConfig.USERNAME_PATH, WsSecurityConfig.SIGNED_PATH, WsSecurityConfig.ENCRYPTED_PATH,
            WsSecurityConfig.SAML_PATH, WsSecurityConfig.SIGNED_SAML_PATH);

    /** JVM system property under which SAAJ creates the JDK's transformer factory instead of looking one up. */
    public static final String SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY = "saaj.use.default.transformer.factory";

    private final SoapEnvelopeStyleInterceptor styler;
    private final PathScopedSecurityInterceptor securityInterceptor;
    private final Jaxb2Marshaller greeterMarshaller;

    /**
     * @param styler              the CXF envelope styler applied to every endpoint response and fault
     * @param securityInterceptor the inbound WS-Security enforcement of the six paths, defined by
     *                            {@link WsSecurityConfig} and run by {@link #messageReceiver()}
     * @param greeterMarshaller   the managed {@link #greeterMarshaller()} bean; that bean method is static, so
     *                            injecting it here creates no cycle
     */
    public WebServiceConfig(SoapEnvelopeStyleInterceptor styler, PathScopedSecurityInterceptor securityInterceptor,
            @Qualifier(GREETER_MARSHALLER_BEAN_NAME) Jaxb2Marshaller greeterMarshaller) {
        this.styler = styler;
        this.securityInterceptor = securityInterceptor;
        this.greeterMarshaller = greeterMarshaller;
    }

    /**
     * Registers the envelope styler on Boot's annotation-driven endpoint mappings, its only registration there: it is
     * not a {@code SmartEndpointInterceptor}, and a second registration would restyle a message twice. The
     * unknown-operation fallback mapping carries the styler itself.
     * <p>
     * The security interceptor is deliberately absent from every mapping. A mapping's interceptors run only after
     * that mapping has selected an endpoint from the body as it arrived, so they could neither decrypt a body before
     * its operation is chosen nor check a request no endpoint maps; {@link #messageReceiver()} runs it once per
     * request, before endpoint selection, instead.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
    }

    /**
     * Puts the Greeter marshaller ahead of Spring WS's built-in handlers, which this list already holds, so a
     * {@code @ResponsePayload} {@code GreetResponse} is written by it rather than by the built-in
     * {@code XmlRootElementPayloadMethodProcessor}: that processor marshals straight into the SOAP body and so
     * writes {@code ns3} where CXF wrote {@code ns2}. Request payloads keep the built-in binding, whose result is the
     * same object either way.
     */
    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        returnValueHandlers.add(0, new MarshallingPayloadMethodProcessor(greeterMarshaller));
    }

    /**
     * The SOAP 1.1 message factory shared by the dispatcher servlet, the listener boundary and the secured clients.
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
     * The listener boundary for every SOAP request. It declares no POST-only path, because the original listener
     * declares no {@code allowedMethods}: each path accepted every method, and a non-POST request without a metadata
     * query is read as a message.
     */
    @Bean(name = MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapListenerBoundaryAdapter messageReceiverHandlerAdapter(SaajSoapMessageFactory messageFactory) {
        return new SoapListenerBoundaryAdapter(messageFactory, List.of(), styler);
    }

    /**
     * The receiver the servlet hands every SOAP request to once the listener boundary has read it: the path's
     * WS-Security policy first, then Spring WS dispatch with CXF's MustUnderstand fault (see
     * {@link SecurityFirstMessageDispatcher}). Like the servlet's default receiver, it collects every endpoint
     * mapping, endpoint adapter and exception resolver bean of the context when the context is set on it.
     */
    @Bean(name = MESSAGE_RECEIVER_BEAN_NAME)
    public SoapMessageDispatcher messageReceiver() {
        return new SecurityFirstMessageDispatcher(securityInterceptor, styler);
    }

    /**
     * Binds the two document/literal-wrapped operation elements and writes them as CXF did (see
     * {@link CxfStyleJaxb2Marshaller}); output stays unformatted, so marshalled payloads are as compact as CXF's.
     */
    @Bean(name = GREETER_MARSHALLER_BEAN_NAME)
    public static Jaxb2Marshaller greeterMarshaller() {
        Jaxb2Marshaller marshaller = new CxfStyleJaxb2Marshaller();
        marshaller.setClassesToBeBound(Greet.class, GreetResponse.class);
        return marshaller;
    }

    /**
     * Installs the WSDL publisher right after {@code PortRoutingFilter}, for request dispatches only. The six paths
     * are registered exactly, as each original {@code http:listener path} matched only itself; {@code ?wsdl} is a
     * query, so it reaches the publisher on the listener path itself. A path the routing filter forwards never passes
     * through this filter, and none of the six is forwarded, because the container already maps them to
     * {@code MessageDispatcherServlet}.
     *
     * @throws IllegalStateException when the WSDL cannot be read or parsed, which fails start-up
     */
    @Bean
    public FilterRegistrationBean<WsdlQueryPublisher> wsdlQueryPublisherRegistration() {
        WsdlQueryPublisher publisher = new WsdlQueryPublisher();
        publisher.register(new ClassPathResource(GREETER_WSDL), GREETER_PATHS.toArray(String[]::new));

        FilterRegistrationBean<WsdlQueryPublisher> registration = new FilterRegistrationBean<>(publisher);
        registration.setName("wsdlQueryPublisher");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    /**
     * SC-11's {@link SoapFaultConfig.CxfStyleSoapMessageDispatcher}, enforcing the request path's WS-Security policy
     * before any endpoint mapping is consulted, as each original {@code cxf:ws-security} interceptor ran before CXF
     * bound the message to an operation.
     * <p>
     * Spring WS selects the endpoint from the body root as it arrived and runs only the selected mapping's
     * interceptors, and its dispatch step cannot be overridden. An encrypted body, whose root is
     * {@code xenc:EncryptedData}, could therefore never select {@code greet}, and a body root no endpoint maps would
     * reach the unknown-operation fallback, which carries no security, unchecked. {@link #receive} instead runs the
     * security interceptor on the message itself first:
     * <ul>
     *   <li>a rejected request already carries the fault the interceptor wrote; it is restyled into CXF's envelope
     *       form and answered without dispatch, so no endpoint mapping, adapter or exception resolver sees it;</li>
     *   <li>an accepted request is dispatched on the processed message, whose body WSS4J has decrypted in place, so
     *       the operation is selected on the payload the endpoint reads.</li>
     * </ul>
     * The security interceptor is on no mapping, so no request is validated twice. The {@code mustUnderstand} check
     * still asks it whether it understands each header, for every mapped chain, the unknown-operation fallback
     * included: the {@code wsse:Security} header WSS4J-based clients mark {@code mustUnderstand="1"} by default is
     * understood on exactly the secured paths, and on an unsecured path it gets CXF's MustUnderstand fault from the
     * inherited check.
     * <p>
     * Spring WS's dispatcher constructor loads the default strategies of the runtime class from
     * {@code WebServiceConfig.SecurityFirstMessageDispatcher.properties} beside it, which therefore carries the
     * entries of Spring's {@code SoapMessageDispatcher.properties}. Holds no per-request state, so the one instance
     * serves concurrent requests.
     */
    static final class SecurityFirstMessageDispatcher extends SoapFaultConfig.CxfStyleSoapMessageDispatcher {

        private final PathScopedSecurityInterceptor securityInterceptor;
        private final SoapEnvelopeStyleInterceptor styler;

        /**
         * @param securityInterceptor the path-scoped enforcement run before endpoint selection
         * @param styler              the CXF envelope styler applied to a security fault answered without dispatch
         *                            and, by the inherited check, to a MustUnderstand fault
         * @throws NullPointerException if either argument is {@code null}
         */
        SecurityFirstMessageDispatcher(PathScopedSecurityInterceptor securityInterceptor,
                SoapEnvelopeStyleInterceptor styler) {
            super(Objects.requireNonNull(styler, "styler"));
            this.securityInterceptor = Objects.requireNonNull(securityInterceptor, "securityInterceptor");
            this.styler = styler;
        }

        /**
         * Enforces the path's policy, then dispatches an accepted request; a rejected one is answered with the fault
         * the security interceptor wrote. No endpoint is selected at this point, so both interceptors are called
         * without one, and the security interceptor's completion callback follows on either outcome, as dispatch
         * calls it for an interceptor that ran.
         */
        @Override
        public void receive(MessageContext messageContext) throws Exception {
            boolean accepted = securityInterceptor.handleRequest(messageContext, null);
            try {
                if (accepted) {
                    super.receive(messageContext);
                }
                else {
                    styler.handleFault(messageContext, null);
                }
            }
            catch (Exception ex) {
                securityInterceptor.afterCompletion(messageContext, null, ex);
                throw ex;
            }
            securityInterceptor.afterCompletion(messageContext, null, null);
        }

        /**
         * The inherited {@code mustUnderstand} check, run against a copy of the mapped chain that also holds the
         * security interceptor, so the check asks it whether it understands each header. The copy keeps the chain's
         * SOAP actors or roles and its ultimate-receiver flag, and serves the check alone: dispatch runs the mapping's
         * own interceptors only.
         */
        @Override
        protected boolean handleRequest(EndpointInvocationChain mappedEndpoint, MessageContext messageContext) {
            EndpointInterceptor[] mapped = mappedEndpoint.getInterceptors();
            EndpointInterceptor[] checked;
            if (mapped == null) {
                checked = new EndpointInterceptor[] { securityInterceptor };
            }
            else {
                checked = Arrays.copyOf(mapped, mapped.length + 1);
                checked[mapped.length] = securityInterceptor;
            }
            EndpointInvocationChain headerCheckChain;
            if (mappedEndpoint instanceof SoapEndpointInvocationChain soapChain) {
                headerCheckChain = new SoapEndpointInvocationChain(soapChain.getEndpoint(), checked,
                        soapChain.getActorsOrRoles(), soapChain.isUltimateReceiver());
            }
            else {
                headerCheckChain = new EndpointInvocationChain(mappedEndpoint.getEndpoint(), checked);
            }
            return super.handleRequest(headerCheckChain, messageContext);
        }
    }

    /**
     * Writes a bound root element the way CXF's JAXB databinding wrote it into the envelope:
     * {@code <ns2:greetResponse xmlns:ns2="http://security.example.mule.mulesoft.com/">}, declaring the element's own
     * namespace and nothing else.
     * <p>
     * A plain {@link Jaxb2Marshaller} marshalling straight into the SOAP body cannot do that: the JAXB RI
     * pre-declares the namespaces in scope at a target DOM node, so the envelope's namespace takes the {@code ns2}
     * slot and the payload gets {@code ns3}. This marshaller therefore writes each bound root with a context of that
     * root's class alone, whose only namespaces are the element's and the empty one of its unqualified children; into
     * a DOM node it writes a document of its own first and copies the result under the node. Unmarshalling and
     * {@code supports} keep the inherited behaviour over both classes, as does marshalling any other object, or with
     * MTOM enabled, whose attachments only the inherited path handles.
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
