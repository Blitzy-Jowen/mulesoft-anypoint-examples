/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.config;

import java.util.List;
import java.util.Map;

import com.mulesoft.examples.legacy_modernization.model.soap.PutShippingOrder;
import com.mulesoft.examples.legacy_modernization.model.soap.PutShippingOrderResponse;

import org.glassfish.jaxb.runtime.marshaller.NamespacePrefixMapper;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodArgumentResolver;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapMessageDispatcher;

/**
 * Spring WS wiring of the {@code IFulfillment} SOAP service, which the original exposed as a
 * {@code cxf:jaxws-service} behind {@code <http:listener path="OrderFulfillment" allowedMethods="POST"/>} on
 * {@code 0.0.0.0:1080} ({@code FufillmentWebService.xml:3-7}). Port and address come from {@code application.yml}.
 * <ul>
 *   <li>The single operation {@code putShippingOrder} is document/literal-wrapped in {@code http://ordermgmt.org/}
 *       with {@code soapAction=""}, so the {@code @PayloadRoot} endpoint is mapped by Spring Boot's annotation-driven
 *       endpoint mappings, which receive {@link SoapEnvelopeStyleInterceptor} from {@link #addInterceptors(List)} and
 *       so answer in CXF's envelope form; any other payload root reaches {@code SoapFaultConfig}'s catch-all
 *       {@code soap:Client} fault.</li>
 *   <li>{@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory, the listener boundary and the
 *       message receiver by their bean names, {@value #MESSAGE_FACTORY_BEAN_NAME},
 *       {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME} and {@value #MESSAGE_RECEIVER_BEAN_NAME}. The
 *       listener accepts POST only, so the boundary answers every other method, {@code GET ?wsdl} included, with the
 *       listener's 405: no WSDL or schema is published, and {@code wsdl/IFulfillmentService.wsdl} stays a reference
 *       document nothing reads. {@link #saajDefaultTransformerFactory()} makes SAAJ use the JDK's transformer
 *       factory directly rather than look one up for every message it parses or writes.</li>
 *   <li>The {@code PutShippingOrder} request and {@code PutShippingOrderResponse} reply are read and written by
 *       {@link #fulfillmentMarshaller()} through one {@link MarshallingPayloadMethodProcessor}. Verified on the
 *       running endpoint: it is the first resolver and the first handler that supports the operation's parameter and
 *       return type, ahead of the built-in {@code DomPayloadMethodProcessor}, {@code SourcePayloadMethodProcessor},
 *       {@code XmlRootElementPayloadMethodProcessor} and {@code JaxbElementPayloadMethodProcessor}, so it is the one
 *       that marshals the reply.</li>
 *   <li>The dispatcher servlet is mapped to exactly {@value #LISTENER_PATH}, as the Mule listener matched its path,
 *       so sub-paths fall through to the MVC dispatcher and its no-listener 404.</li>
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

    /** Name of the {@code MessageDispatcherServlet} registration bean of Boot's web-services auto-configuration. */
    public static final String MESSAGE_DISPATCHER_SERVLET_BEAN_NAME = "messageDispatcherServlet";

    /** Path of the original listener, {@code path="OrderFulfillment"} ({@code FufillmentWebService.xml:5}). */
    public static final String LISTENER_PATH = "/OrderFulfillment";

    /** The SOAP listener paths whose {@code http:listener} declares {@code allowedMethods="POST"}. */
    public static final List<String> POST_ONLY_PATHS = List.of(LISTENER_PATH);

    /** JVM system property under which SAAJ creates the JDK's transformer factory instead of looking one up. */
    public static final String SAAJ_DEFAULT_TRANSFORMER_FACTORY_PROPERTY = "saaj.use.default.transformer.factory";

    /** Namespace of the operation wrappers, the one JAX-WS derived from the original {@code org.ordermgmt}. */
    private static final String ORDERMGMT_NAMESPACE_URI = "http://ordermgmt.org/";

    /** Prefix CXF's JAXB databinding bound to {@link #ORDERMGMT_NAMESPACE_URI} on the payload root. */
    private static final String PAYLOAD_PREFIX = "ns2";

    /** JAXB RI marshaller property carrying a {@link NamespacePrefixMapper}. */
    private static final String NAMESPACE_PREFIX_MAPPER_PROPERTY = "org.glassfish.jaxb.namespacePrefixMapper";

    private final SoapEnvelopeStyleInterceptor styler;
    private final MarshallingPayloadMethodProcessor payloadProcessor;

    /**
     * @param styler                the CXF envelope styler applied to every endpoint response and fault
     * @param fulfillmentMarshaller the managed {@link #fulfillmentMarshaller()} bean; that bean method is static, so
     *                              injecting it here creates no cycle
     */
    public WebServiceConfig(SoapEnvelopeStyleInterceptor styler, Jaxb2Marshaller fulfillmentMarshaller) {
        this.styler = styler;
        this.payloadProcessor = new MarshallingPayloadMethodProcessor(fulfillmentMarshaller);
    }

    /**
     * Reaches Boot's annotation-driven endpoint mappings, so the {@code putShippingOrder} endpoint answers in CXF's
     * envelope form.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
    }

    /**
     * Puts the fulfillment marshaller ahead of Spring WS's built-in resolvers, which this list already holds, so the
     * {@code @RequestPayload PutShippingOrder} is read by the same JAXB context that writes the reply.
     */
    @Override
    public void addArgumentResolvers(List<MethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(0, payloadProcessor);
    }

    /**
     * Puts the fulfillment marshaller ahead of Spring WS's built-in handlers, which this list already holds: the
     * built-in {@code XmlRootElementPayloadMethodProcessor} would otherwise claim the {@code @XmlRootElement} reply and
     * write it without {@link #fulfillmentMarshaller()}'s {@code ns2} prefix.
     */
    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        returnValueHandlers.add(0, payloadProcessor);
    }

    /**
     * The SOAP 1.1 message factory shared by the dispatcher servlet and the listener boundary. {@code faultstring}
     * is written without {@code xml:lang}, as CXF wrote it. The container initializes it. It is SC-11's
     * {@link SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory}, so characters outside the Basic Multilingual Plane
     * are written as raw UTF-8, as CXF wrote them.
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
     * The listener boundary of {@value #LISTENER_PATH}: a non-POST request gets the listener's 405
     * ({@code Method not allowed for endpoint: /OrderFulfillment}), and a body that is not a SOAP 1.1 envelope gets a
     * {@code soap:Client} fault over HTTP 500.
     */
    @Bean(name = MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapListenerBoundaryAdapter messageReceiverHandlerAdapter(SaajSoapMessageFactory messageFactory) {
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
     * Binds the two {@code putShippingOrder} wrappers; {@code Address}, {@code Order}, {@code OrderItem},
     * {@code ShippingOrder} and {@code ShippingOrderConfirmation} are reached through them. Output stays
     * unformatted, so the reply is as compact as CXF's.
     * <p>
     * The reply is marshalled straight into the SAAJ body, where the JAXB reference implementation pre-declares the
     * envelope namespace in scope at that node and numbers the payload's prefix {@code ns3} (observed without the
     * mapper); the {@link CxfPayloadPrefixMapper} keeps CXF's {@code ns2}, so the reply reads
     * {@code <ns2:putShippingOrderResponse xmlns:ns2="http://ordermgmt.org/">} as the original's did.
     */
    @Bean
    public static Jaxb2Marshaller fulfillmentMarshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setClassesToBeBound(PutShippingOrder.class, PutShippingOrderResponse.class);
        marshaller.setMarshallerProperties(Map.of(NAMESPACE_PREFIX_MAPPER_PROPERTY, new CxfPayloadPrefixMapper()));
        return marshaller;
    }

    /**
     * Narrows Boot's {@code MessageDispatcherServlet} registration from {@code /OrderFulfillment/*}, the mapping
     * {@code spring.webservices.path} yields, to exactly {@value #LISTENER_PATH}. Static, so registering it does not
     * instantiate this configuration before the post-processors are in place.
     */
    @Bean
    public static BeanPostProcessor listenerPathServletMapping() {
        return new ListenerPathServletMapping();
    }

    /**
     * Prefers {@code ns2} for {@code http://ordermgmt.org/}, the prefix CXF's JAXB databinding wrote, and leaves every
     * other namespace to JAXB's own choice. Stateless, so concurrent marshallers share one instance.
     */
    static final class CxfPayloadPrefixMapper extends NamespacePrefixMapper {

        @Override
        public String getPreferredPrefix(String namespaceUri, String suggestion, boolean requirePrefix) {
            return ORDERMGMT_NAMESPACE_URI.equals(namespaceUri) ? PAYLOAD_PREFIX : suggestion;
        }
    }

    /**
     * Gives the {@value #MESSAGE_DISPATCHER_SERVLET_BEAN_NAME} registration the single exact mapping
     * {@value #LISTENER_PATH} before it is initialized; every other bean passes unchanged. A Mule listener matched its
     * path exactly, so {@code /OrderFulfillment/x} must reach the MVC dispatcher and SC-1's
     * {@code No listener for endpoint: <path>} rather than the SOAP stack.
     */
    static final class ListenerPathServletMapping implements BeanPostProcessor {

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (MESSAGE_DISPATCHER_SERVLET_BEAN_NAME.equals(beanName)
                    && bean instanceof ServletRegistrationBean<?> registration) {
                registration.setUrlMappings(List.of(LISTENER_PATH));
            }
            return bean;
        }
    }
}
