package com.mulesoft.examples.soap_webservice_security.config;

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

import com.mulesoft.examples.soap_webservice_security.model.soap.Greet;
import com.mulesoft.examples.soap_webservice_security.model.soap.GreetResponse;

import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Spring WS wiring of the six {@code Greeter} services, which the original exposed as {@code cxf:jaxws-service}
 * flows behind one {@code http:listener-config} on 63081 with {@code basePath="services"}: {@code /services/unsecure},
 * {@code /services/username}, {@code /services/signed}, {@code /services/encrypted}, {@code /services/saml} and
 * {@code /services/signedsaml}, none of them declaring {@code allowedMethods}.
 * <ul>
 *   <li>Every response and fault of the annotation-mapped endpoints is restyled into CXF's envelope form by
 *       {@link SoapEnvelopeStyleInterceptor}, and every request is checked against its path's WS-Security action set
 *       by {@link PathScopedSecurityInterceptor} (see {@link #addInterceptors(List)} for their order).</li>
 *   <li>The two {@code model.soap} wrappers are written by {@link #greeterMarshaller()}, both in endpoint responses
 *       and in the outbound requests of the secured clients, so every payload root reads
 *       {@code <ns2:greetResponse xmlns:ns2="http://security.example.mule.mulesoft.com/">} as CXF wrote it.</li>
 *   <li>{@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory and the listener boundary by their
 *       bean names, {@value #MESSAGE_FACTORY_BEAN_NAME} and {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME}.</li>
 *   <li>{@code GET <listener path>?wsdl} serves {@value #GREETER_WSDL} on each of the six paths, as CXF published
 *       the Greeter WSDL on each, through {@link WsdlQueryPublisher}; no {@code WsdlDefinition} or {@code XsdSchema}
 *       bean exists, so the servlet never publishes a second copy.</li>
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

    private final SoapEnvelopeStyleInterceptor styler;
    private final PathScopedSecurityInterceptor securityInterceptor;
    private final Jaxb2Marshaller greeterMarshaller;

    /**
     * @param styler              the CXF envelope styler applied to every endpoint response and fault
     * @param securityInterceptor the inbound WS-Security enforcement of the six paths, defined by
     *                            {@link WsSecurityConfig}
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
     * Reaches Boot's annotation-driven endpoint mappings, the only place either interceptor is registered: neither is
     * a {@code SmartEndpointInterceptor}, and a second registration would validate or restyle a message twice. The
     * mappings also ask the security interceptor whether it understands a {@code mustUnderstand} header, so a
     * {@code wsse:Security} header is accepted on exactly the secured paths.
     * <p>
     * The styler comes first. When the security interceptor rejects a request it writes the fault and returns
     * {@code false}, and the dispatcher then runs {@code handleFault} in reverse order from that interceptor down to
     * the first one, so the styler restyles the security fault last, after which nothing changes the response.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
        interceptors.add(securityInterceptor);
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
     * {@code faultstring} is written without {@code xml:lang}, as CXF wrote it.
     *
     * @throws SOAPException when no SAAJ SOAP 1.1 implementation is available
     */
    @Bean(name = MESSAGE_FACTORY_BEAN_NAME)
    public SaajSoapMessageFactory messageFactory() throws SOAPException {
        SaajSoapMessageFactory messageFactory =
                new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        messageFactory.setLangAttributeOnSoap11FaultString(false);
        messageFactory.afterPropertiesSet();
        return messageFactory;
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
