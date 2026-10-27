/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.xml_only_soap_webservice.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.server.endpoint.mapping.AbstractEndpointMapping;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Element;

/**
 * Spring WS wiring of the hospital admission example, which the original served from one
 * {@code http:listener-config} on {@code 0.0.0.0:${http.port}} ({@code Hospital_Admissions_SOA.xml:16}) through three
 * {@code cxf:proxy-service} listeners: {@value #ADMISSION_PATH} ({@code Hospital_Admissions_SOA.xml:20-21}),
 * {@value #PATIENT_PATH} ({@code mocks.xml:5-6}) and {@value #EHR_PATH} ({@code mocks.xml:46-47}). None declares
 * {@code allowedMethods}, so each accepts every method.
 * <ul>
 *   <li>Requests are dispatched by listener path, not by payload root: a CXF proxy service accepted any body root,
 *       and the mocks' {@code otherwise} branches answer any operation name, so a {@code @PayloadRoot} mapping would
 *       reject requests the original answered. {@link ListenerPathEndpointMapping} hands each path to the
 *       {@code handle(Element)} method of the endpoint bean named {@value #ADMISSION_ENDPOINT},
 *       {@value #PATIENT_ENDPOINT} or {@value #EHR_ENDPOINT}. Looking the endpoints up by name keeps this package free
 *       of any dependency on the endpoint classes.</li>
 *   <li>{@code MessageDispatcherServlet} resolves the SOAP 1.1 message factory and the listener boundary by their bean
 *       names, {@value #MESSAGE_FACTORY_BEAN_NAME} and {@value #MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME}. The
 *       boundary declares no POST-only path, so a non-POST request without a metadata query is read as a message and
 *       an unreadable body is answered with a {@code soap:Client} fault.</li>
 *   <li>The servlet is mapped to exactly the three listener paths, and {@link ListenerPathFilter} answers every other
 *       path with the listener's {@code No listener for endpoint: <path>} 404 before anything else runs.</li>
 *   <li>{@code GET <listener path>?wsdl} serves the listener's carried WSDL and {@code ?xsd=<location>} its schema
 *       closure through {@link WsdlQueryPublisher}, as CXF published the {@code wsdlLocation} of each proxy
 *       service. No {@code WsdlDefinition} or {@code XsdSchema} bean exists, so the servlet publishes no second copy.
 *   </li>
 *   <li>One {@link WebServiceTemplate} carries the calls to {@value #PATIENT_PATH} and {@value #EHR_PATH} that the
 *       original made through {@code cxf:proxy-client} and {@code http:request method="POST"}
 *       ({@code Hospital_Admissions_SOA.xml:66-68,85-87}); callers pass the target URI per call.</li>
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

    /** Name of the {@code MessageDispatcherServlet} registration bean of Boot's web-services auto-configuration. */
    public static final String MESSAGE_DISPATCHER_SERVLET_BEAN_NAME = "messageDispatcherServlet";

    /** Path of the AdmissionService listener, {@code path="AdmissionService"}. */
    public static final String ADMISSION_PATH = "/AdmissionService";

    /** Path of the PatientService mock listener, {@code path="PatientService"}. */
    public static final String PATIENT_PATH = "/PatientService";

    /** Path of the EHRService mock listener, {@code path="EHRService"}. */
    public static final String EHR_PATH = "/EHRService";

    /** Every listener path of the original application; no other path is served. */
    public static final List<String> LISTENER_PATHS = List.of(ADMISSION_PATH, PATIENT_PATH, EHR_PATH);

    /** Bean name of the endpoint that answers {@value #ADMISSION_PATH}. */
    public static final String ADMISSION_ENDPOINT = "admissionServiceEndpoint";

    /** Bean name of the endpoint that answers {@value #PATIENT_PATH}. */
    public static final String PATIENT_ENDPOINT = "patientServiceMockEndpoint";

    /** Bean name of the endpoint that answers {@value #EHR_PATH}. */
    public static final String EHR_ENDPOINT = "ehrServiceMockEndpoint";

    /**
     * Name of the public method every endpoint exposes as
     * {@code @ResponsePayload Element handle(@RequestPayload Element request)}.
     */
    public static final String ENDPOINT_METHOD = "handle";

    /** Classpath location of the WSDL the AdmissionService proxy published. */
    public static final String ADMISSION_WSDL = "service/AdmissionService.wsdl";

    /** Classpath location of the WSDL the PatientService proxy published. */
    public static final String PATIENT_WSDL = "service/PatientService.wsdl";

    /** Classpath location of the WSDL the EHRService proxy published. */
    public static final String EHR_WSDL = "service/EHRService.wsdl";

    /** Listener path to the name of the endpoint bean that answers it. */
    static final Map<String, String> ENDPOINT_BEANS =
            Map.of(ADMISSION_PATH, ADMISSION_ENDPOINT, PATIENT_PATH, PATIENT_ENDPOINT, EHR_PATH, EHR_ENDPOINT);

    private final SoapEnvelopeStyleInterceptor styler;

    /**
     * @param styler the CXF envelope styler applied to every endpoint response and fault
     */
    public WebServiceConfig(SoapEnvelopeStyleInterceptor styler) {
        this.styler = styler;
    }

    /**
     * Reaches Boot's annotation-driven endpoint mappings. {@link ListenerPathEndpointMapping} carries the styler
     * itself, and restyling an already restyled message changes nothing.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(styler);
    }

    /**
     * The SOAP 1.1 message factory shared by the dispatcher servlet, the listener boundary and the outbound template.
     * {@code faultstring} is written without {@code xml:lang}, as CXF wrote it. The container initializes it.
     *
     * @throws SOAPException when no SAAJ SOAP 1.1 implementation is available
     */
    @Bean(name = MESSAGE_FACTORY_BEAN_NAME)
    public SaajSoapMessageFactory messageFactory() throws SOAPException {
        SaajSoapMessageFactory messageFactory =
                new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        messageFactory.setLangAttributeOnSoap11FaultString(false);
        return messageFactory;
    }

    /**
     * The listener boundary for every SOAP request. Its POST-only list is empty because no hospital listener declares
     * {@code allowedMethods}: a {@code GET}, {@code PUT}, {@code OPTIONS} or {@code TRACE} without a metadata query is
     * read as a message, and an empty or unreadable body gets HTTP 500 with a {@code soap:Client} fault.
     */
    @Bean(name = MESSAGE_RECEIVER_HANDLER_ADAPTER_BEAN_NAME)
    public SoapListenerBoundaryAdapter messageReceiverHandlerAdapter(WebServiceMessageFactory messageFactory) {
        return new SoapListenerBoundaryAdapter(messageFactory, List.of(), styler);
    }

    /**
     * Dispatches each listener path to its endpoint ahead of every other mapping. A path it does not know falls
     * through to {@code SoapFaultConfig}'s catch-all {@code soap:Client} mapping.
     */
    @Bean
    public ListenerPathEndpointMapping listenerPathEndpointMapping() {
        ListenerPathEndpointMapping mapping = new ListenerPathEndpointMapping(ENDPOINT_BEANS);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // Set here, before the container calls initApplicationContext; interceptors contributed through
        // addInterceptors reach only the annotation-driven mappings.
        mapping.setInterceptors(new EndpointInterceptor[] { styler });
        return mapping;
    }

    /**
     * The outbound SOAP client of the PatientService and EHRService calls. Connecting and reading are each bounded by
     * {@code http.request.response-timeout}, Mule's default response timeout of 10000 ms, because the original
     * {@code http:request} declares none. No default URI is set: every call names its target. A SOAP fault answer
     * surfaces as {@code SoapFaultClientException} and any other HTTP error as {@code WebServiceTransportException},
     * because both connection checks keep their defaults. Every request is sent exactly once
     * ({@link SingleAttemptMessageSender}).
     *
     * @param responseTimeoutMillis the response timeout in milliseconds; must be positive, because zero would mean
     *                              waiting forever
     * @throws IllegalArgumentException when the timeout is not positive
     */
    @Bean
    public WebServiceTemplate webServiceTemplate(WebServiceMessageFactory messageFactory,
            @Value("${http.request.response-timeout:10000}") long responseTimeoutMillis) {
        Assert.isTrue(responseTimeoutMillis > 0,
                () -> "http.request.response-timeout must be a positive number of milliseconds, but was "
                        + responseTimeoutMillis);
        Duration responseTimeout = Duration.ofMillis(responseTimeoutMillis);
        HttpUrlConnectionMessageSender messageSender = new SingleAttemptMessageSender();
        messageSender.setConnectionTimeout(responseTimeout);
        messageSender.setReadTimeout(responseTimeout);

        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMessageSender(messageSender);
        return template;
    }

    /**
     * Publishes each listener's carried WSDL on that exact path, right after {@link ListenerPathFilter}, for request
     * dispatches only. The AdmissionService WSDL imports {@code service/xsd/SOA-Message-1.0.xsd}, which resolves
     * only from the classpath root; the two mock WSDLs import {@code xsd/SOA-Message-1.0.xsd} relative to
     * themselves. Each closure also holds {@code SOA-Model-1.0.xsd}, which the message schema imports.
     *
     * @throws IllegalStateException when a WSDL or schema cannot be read or parsed, which fails start-up
     */
    @Bean
    public FilterRegistrationBean<WsdlQueryPublisher> wsdlQueryPublisherRegistration() {
        WsdlQueryPublisher publisher = new WsdlQueryPublisher()
                .register(new ClassPathResource(ADMISSION_WSDL), ADMISSION_PATH)
                .register(new ClassPathResource(PATIENT_WSDL), PATIENT_PATH)
                .register(new ClassPathResource(EHR_WSDL), EHR_PATH);

        FilterRegistrationBean<WsdlQueryPublisher> registration = new FilterRegistrationBean<>(publisher);
        registration.setName("wsdlQueryPublisher");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    /**
     * Installs the no-listener 404 ahead of every other filter, for request dispatches only. This application has no
     * MVC controller and no error page, so this filter is the only owner of that answer.
     */
    @Bean
    public FilterRegistrationBean<ListenerPathFilter> listenerPathFilterRegistration() {
        FilterRegistrationBean<ListenerPathFilter> registration =
                new FilterRegistrationBean<>(new ListenerPathFilter(LISTENER_PATHS));
        registration.setName("listenerPathFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setUrlPatterns(List.of("/*"));
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    /**
     * Narrows Boot's {@code MessageDispatcherServlet} registration, whatever mapping {@code spring.webservices.path}
     * gave it, to exactly the three listener paths. Static, so registering it does not instantiate this configuration
     * before the post-processors are in place.
     */
    @Bean
    public static BeanPostProcessor messageDispatcherServletUrlMappings() {
        return new ListenerPathServletMapping();
    }

    /** The request URI without the context path, undecoded and unnormalized, as the listener matched it. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Maps a SOAP request to the endpoint of the listener path it arrived on, whatever its body root.
     * <p>
     * The path is the HTTP request URI without the context path, raw: no decoding and no trailing-slash
     * normalization, because a Mule listener matched its path exactly. A request without an HTTP servlet transport,
     * or on a path the map does not name, yields {@code null}, so the next mapping decides. The endpoint is the
     * public {@code handle(Element)} method of the named bean, resolved on first use and cached; a bean without that
     * method fails that request with an {@link IllegalStateException} naming it. The map is immutable and the cache
     * concurrent, so one instance serves all requests concurrently.
     */
    public static class ListenerPathEndpointMapping extends AbstractEndpointMapping {

        private final Map<String, String> pathToBeanName;
        private final ConcurrentMap<String, MethodEndpoint> endpoints = new ConcurrentHashMap<>();

        /**
         * @param pathToBeanName listener path within the application to the name of the endpoint bean answering it
         */
        public ListenerPathEndpointMapping(Map<String, String> pathToBeanName) {
            Assert.notNull(pathToBeanName, "pathToBeanName must not be null");
            this.pathToBeanName = Map.copyOf(pathToBeanName);
        }

        @Override
        protected Object getEndpointInternal(MessageContext messageContext) {
            TransportContext transportContext = TransportContextHolder.getTransportContext();
            if (transportContext == null
                    || !(transportContext.getConnection() instanceof HttpServletConnection connection)) {
                return null;
            }
            String beanName = pathToBeanName.get(pathWithinApplication(connection.getHttpServletRequest()));
            if (beanName == null) {
                return null;
            }
            return endpoints.computeIfAbsent(beanName, this::createEndpoint);
        }

        private MethodEndpoint createEndpoint(String beanName) {
            Object bean = obtainApplicationContext().getBean(beanName);
            try {
                return new MethodEndpoint(bean, ENDPOINT_METHOD, Element.class);
            }
            catch (NoSuchMethodException ex) {
                throw new IllegalStateException("SOAP endpoint bean '" + beanName + "' (" + bean.getClass().getName()
                        + ") has no public method " + ENDPOINT_METHOD + "(org.w3c.dom.Element)", ex);
            }
        }
    }

    /**
     * Answers every request whose path is not a listener path, whatever its method, as the Mule HTTP listener did:
     * status 404, Content-Type {@code text/plain;charset=UTF-8} and body {@code No listener for endpoint: <path>} in
     * UTF-8, the path being the request URI without context path or query. The chain does not continue for such a
     * request. A sub-path ({@code /AdmissionService/x}) or a trailing slash ({@code /AdmissionService/}) is another
     * path. Listener paths pass through unchanged. The path set is immutable, so one instance serves all requests
     * concurrently.
     */
    public static class ListenerPathFilter extends OncePerRequestFilter {

        private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";
        private static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

        private final Set<String> listenerPaths;

        /**
         * @param listenerPaths the paths within the application a listener serves
         */
        public ListenerPathFilter(Collection<String> listenerPaths) {
            Assert.notNull(listenerPaths, "listenerPaths must not be null");
            this.listenerPaths = Set.copyOf(listenerPaths);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = pathWithinApplication(request);
            if (listenerPaths.contains(path)) {
                chain.doFilter(request, response);
                return;
            }
            byte[] body = (NO_LISTENER_PREFIX + path).getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(CONTENT_TYPE);
            response.setContentLength(body.length);
            ServletOutputStream out = response.getOutputStream();
            out.write(body);
            out.flush();
        }
    }

    /**
     * Gives the {@value #MESSAGE_DISPATCHER_SERVLET_BEAN_NAME} registration exactly the {@link #LISTENER_PATHS} as its
     * mappings before it is initialized; every other bean passes unchanged.
     */
    static final class ListenerPathServletMapping implements BeanPostProcessor {

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (MESSAGE_DISPATCHER_SERVLET_BEAN_NAME.equals(beanName)
                    && bean instanceof ServletRegistrationBean<?> registration) {
                registration.setUrlMappings(LISTENER_PATHS);
            }
            return bean;
        }
    }

    /**
     * {@link HttpUrlConnectionMessageSender} whose connections send every request exactly once.
     * <p>
     * A default {@link HttpURLConnection} buffers the POST body and, when the connection fails while the response is
     * read (a reset, an end of stream before any response byte), silently sends the whole request a second time
     * ({@code sun.net.http.retryPost}, on by default). The original {@code invokePatientService} and
     * {@code invokeEHRService} sub-flows declare no retry, and a timeout or connectivity failure is never retried.
     * In fixed-length streaming mode the JDK never re-sends, so each connection buffers the envelope Spring WS writes
     * and then streams it with its exact length: {@code Content-Length} and the bytes on the wire are unchanged.
     * A JVM-wide system property is not used, because it would change every other HTTP client in the process.
     */
    static final class SingleAttemptMessageSender extends HttpUrlConnectionMessageSender {

        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            // The parent opens and prepares the HttpURLConnection (method, timeouts) without connecting it.
            HttpUrlConnection prepared = (HttpUrlConnection) super.createConnection(uri);
            return new FixedLengthHttpUrlConnection(prepared.getConnection());
        }
    }

    /** Collects the request body, then sends it in fixed-length streaming mode ({@link SingleAttemptMessageSender}). */
    static final class FixedLengthHttpUrlConnection extends HttpUrlConnection {

        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        FixedLengthHttpUrlConnection(HttpURLConnection connection) {
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

