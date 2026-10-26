package com.mulesoft.examples.legacy_modernization.config;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.MessageEndpoint;
import org.springframework.ws.server.endpoint.adapter.MessageEndpointAdapter;
import org.springframework.ws.server.endpoint.mapping.AbstractEndpointMapping;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.xml.transform.TransformerFactoryUtils;

/**
 * Answers failures inside Spring WS endpoint dispatch with the SOAP 1.1 faults the Apache CXF stack produced:
 * <ul>
 *   <li>an exception escaping an endpoint becomes {@code soap:Server} with the exception message as
 *       {@code faultstring}, or {@link #DEFAULT_FAULT_STRING} when it has none;</li>
 *   <li>a request whose body root no endpoint maps becomes {@code soap:Client} with CXF's unknown-part text
 *       ({@link UnknownOperationException}), instead of the bare HTTP 404 Spring WS sends when no endpoint is
 *       found.</li>
 * </ul>
 * The servlet connection sends every SOAP 1.1 fault with HTTP 500, and {@link SoapEnvelopeStyleInterceptor} gives the
 * envelope CXF's byte form. Requests that fail before dispatch (wrong method, unreadable body) are answered by the
 * listener boundary, not here.
 * <p>
 * The {@link MessageEndpointAdapter} bean is required by the catch-all mapping: once any adapter bean exists, the
 * message dispatcher stops adding its default adapters. Every bean holds no per-request state, so one instance serves
 * all requests concurrently. Spring WS's {@code EnableWs} annotation is deliberately absent: it would switch off
 * Spring Boot's web-services auto-configuration and with it the {@code MessageDispatcherServlet}.
 */
@Configuration(proxyBeanMethods = false)
public class SoapFaultConfig {

    /** CXF's {@code faultstring} for an exception that carries no message. */
    public static final String DEFAULT_FAULT_STRING = "Fault occurred while processing.";

    @Bean
    public CxfStyleSoapFaultResolver cxfStyleSoapFaultResolver() {
        return new CxfStyleSoapFaultResolver();
    }

    @Bean
    public UnknownOperationEndpointMapping unknownOperationEndpointMapping(SoapEnvelopeStyleInterceptor styler) {
        return new UnknownOperationEndpointMapping(styler);
    }

    @Bean
    public MessageEndpointAdapter messageEndpointAdapter() {
        return new MessageEndpointAdapter();
    }

    /**
     * Thrown for a request whose body root element no endpoint is mapped to; it is answered as a {@code soap:Client}
     * fault, as CXF answered a message part it could not bind to an operation.
     */
    public static class UnknownOperationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final QName payloadRoot;

        /**
         * @param payloadRoot the qualified name of the request's body root element, or {@code null} when the body is
         *                    empty; {@link QName#toString()} renders it as {@code {namespace}localPart}
         */
        public UnknownOperationException(QName payloadRoot) {
            // CXF's message text, including the two spaces before the parenthesis.
            super("Message part " + payloadRoot + " was not recognized.  (Does it exist in service WSDL?)");
            this.payloadRoot = payloadRoot;
        }

        /** @return the unmapped body root element's name, or {@code null} when the body was empty */
        public QName getPayloadRoot() {
            return payloadRoot;
        }
    }

    /**
     * Turns every endpoint exception into a CXF-style SOAP fault. It runs after the {@code @SoapFault} annotation
     * resolver, so annotated exceptions keep their declared faults, and before Spring's simple SOAP resolver, which
     * would otherwise answer with its own fault string and {@code xml:lang}.
     */
    public static class CxfStyleSoapFaultResolver extends SoapFaultMappingExceptionResolver {

        private static final Logger log = LoggerFactory.getLogger(CxfStyleSoapFaultResolver.class);

        /** The throw site logged for an exception whose stack trace is empty. */
        private static final String UNKNOWN_THROW_SITE = "an unknown location";

        public CxfStyleSoapFaultResolver() {
            setOrder(Ordered.LOWEST_PRECEDENCE - 1);
            // Backs a subclass whose definition lookup yields nothing; the fault string then falls back to the
            // exception's own message.
            SoapFaultDefinition defaultFault = new SoapFaultDefinition();
            defaultFault.setFaultCode(SoapFaultDefinition.SERVER);
            defaultFault.setLocale(null);
            setDefaultFault(defaultFault);
        }

        /**
         * Returns a new definition for every exception: {@code CLIENT} for {@link UnknownOperationException},
         * {@code SERVER} otherwise, with the exception message (or {@link #DEFAULT_FAULT_STRING}) as fault string and
         * no locale, so {@code faultstring} carries no {@code xml:lang}.
         */
        @Override
        protected SoapFaultDefinition getFaultDefinition(Object endpoint, Exception ex) {
            String message = ex.getMessage();
            SoapFaultDefinition definition = new SoapFaultDefinition();
            definition.setFaultCode(isClientFault(ex) ? SoapFaultDefinition.CLIENT : SoapFaultDefinition.SERVER);
            definition.setFaultStringOrReason(StringUtils.hasLength(message) ? message : DEFAULT_FAULT_STRING);
            definition.setLocale(null);
            return definition;
        }

        /**
         * Logs one WARN line naming the fault kind, the exception's type and its throw site only. The exception's
         * message, its causes and its stack trace stay out of the log, as does everything in the message context: an
         * unknown operation's message carries the caller's body root name and namespace, and an endpoint exception's
         * message can carry request or upstream data. The fault answering the request still carries the message.
         */
        @Override
        protected void logException(Exception ex, MessageContext messageContext) {
            log.warn("Answering an endpoint exception with a SOAP {} fault: {} thrown at {}",
                    isClientFault(ex) ? "Client" : "Server", ex.getClass().getName(), throwSite(ex));
        }

        private static boolean isClientFault(Exception ex) {
            return ex instanceof UnknownOperationException;
        }

        /**
         * The exception's first stack frame as {@code className.methodName:lineNumber}, or {@link #UNKNOWN_THROW_SITE}
         * when its stack trace is empty.
         */
        private static String throwSite(Throwable ex) {
            StackTraceElement[] frames = ex.getStackTrace();
            if (frames.length == 0) {
                return UNKNOWN_THROW_SITE;
            }
            StackTraceElement frame = frames[0];
            return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
    }

    /**
     * Catch-all endpoint mapping, consulted after every other mapping: it maps any request no other mapping accepted
     * to an endpoint that throws {@link UnknownOperationException} naming the request's body root. The mapping carries
     * the envelope styler itself, because interceptors contributed through {@code WsConfigurer.addInterceptors} reach
     * only the annotation-driven mappings.
     * <p>
     * Its order, {@link Ordered#LOWEST_PRECEDENCE}, is also the default order of every endpoint mapping. Spring Boot's
     * annotation mappings carry orders 0 to 2 and so always run first; a mapping created by hand must be given an
     * explicit order below {@link Ordered#LOWEST_PRECEDENCE}, otherwise it ties with this one, bean registration order
     * decides, and this mapping may answer requests the other would have mapped.
     */
    public static class UnknownOperationEndpointMapping extends AbstractEndpointMapping {

        private static final MessageEndpoint UNKNOWN_OPERATION_ENDPOINT = new UnknownOperationEndpoint();

        public UnknownOperationEndpointMapping(SoapEnvelopeStyleInterceptor styler) {
            Assert.notNull(styler, "SoapEnvelopeStyleInterceptor must not be null");
            setOrder(Ordered.LOWEST_PRECEDENCE);
            setInterceptors(new EndpointInterceptor[] { styler });
        }

        @Override
        protected Object getEndpointInternal(MessageContext messageContext) {
            return UNKNOWN_OPERATION_ENDPOINT;
        }

        private static final class UnknownOperationEndpoint implements MessageEndpoint {

            @Override
            public void invoke(MessageContext messageContext) throws Exception {
                Source payload = messageContext.getRequest().getPayloadSource();
                // A fresh factory per call: TransformerFactory is not guaranteed to be thread-safe, and this path
                // only runs for requests that are about to fail.
                QName payloadRoot = PayloadRootUtils.getPayloadRootQName(payload,
                        TransformerFactoryUtils.newInstance());
                throw new UnknownOperationException(payloadRoot);
            }

            @Override
            public String toString() {
                return "UnknownOperationEndpoint";
            }
        }
    }
}
