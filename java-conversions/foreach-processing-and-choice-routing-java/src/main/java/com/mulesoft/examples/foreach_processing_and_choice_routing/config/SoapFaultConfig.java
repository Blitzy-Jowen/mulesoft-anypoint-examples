package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

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
import org.springframework.ws.server.EndpointInvocationChain;
import org.springframework.ws.server.endpoint.MessageEndpoint;
import org.springframework.ws.server.endpoint.adapter.MessageEndpointAdapter;
import org.springframework.ws.server.endpoint.mapping.AbstractEndpointMapping;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.server.SoapEndpointInterceptor;
import org.springframework.ws.soap.server.SoapEndpointInvocationChain;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.ws.soap.soap11.Soap11Header;
import org.springframework.xml.transform.TransformerFactoryUtils;

/**
 * Answers failures inside Spring WS endpoint dispatch with the SOAP 1.1 faults the Apache CXF stack produced:
 * <ul>
 *   <li>an exception escaping an endpoint becomes {@code soap:Server} with the exception message as
 *       {@code faultstring}, or {@link #DEFAULT_FAULT_STRING} when it has none. A {@code NullPointerException} the
 *       JVM raised counts as having none: the original ran on Java 8, which gave such exceptions no message, and the
 *       text a later JVM computes for them names internal classes and members that must not reach callers;</li>
 *   <li>a request whose body root no endpoint maps becomes {@code soap:Client} with CXF's unknown-part text
 *       ({@link UnknownOperationException}), instead of the bare HTTP 404 Spring WS sends when no endpoint is
 *       found;</li>
 *   <li>a request carrying a {@code mustUnderstand} header block that no interceptor of its endpoint understands
 *       becomes {@code soap:MustUnderstand} with CXF's {@code MustUnderstand headers: [...] are not understood.}
 *       ({@link CxfStyleSoapMessageDispatcher}, which the project's web-service configuration registers as the
 *       servlet's message receiver), unless that receiver is built for a proxy service, which checks no header
 *       block.</li>
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

        /**
         * The grammar of the message the JVM computes for a {@code NullPointerException} it raises itself (JEP 358,
         * Java 14 and later): the failed action, optionally followed by {@code because <what> is null}.
         */
        private static final Pattern JVM_NULL_POINTER_MESSAGE = Pattern.compile(
                "Cannot (?:(?:invoke|read field|assign field) \".+\""
                        + "|(?:load from|store to) (?:int|float|object|byte/boolean|char|short|long|double) array"
                        + "|read the array length|throw exception|(?:enter|exit) synchronized block)"
                        + "(?: because .+ is null)?",
                Pattern.DOTALL);

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
         * <p>
         * A {@code NullPointerException} the JVM raised is answered with {@link #DEFAULT_FAULT_STRING}, as an exception
         * without a message is. The original's CXF stack ran on Java 8, where such an exception had no message, while
         * a later JVM computes one naming the internal classes, methods and variables involved, which must not reach
         * callers. An exception whose own code gave it a message keeps that message, because the JVM computes text
         * only for the exceptions it raises.
         */
        @Override
        protected SoapFaultDefinition getFaultDefinition(Object endpoint, Exception ex) {
            String message = ex.getMessage();
            SoapFaultDefinition definition = new SoapFaultDefinition();
            definition.setFaultCode(isClientFault(ex) ? SoapFaultDefinition.CLIENT : SoapFaultDefinition.SERVER);
            definition.setFaultStringOrReason(StringUtils.hasLength(message) && !isJvmNullPointerMessage(ex, message)
                    ? message : DEFAULT_FAULT_STRING);
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
         * Whether {@code message} is the text the JVM computed for a {@code NullPointerException} it raised: the
         * exception is exactly {@link NullPointerException}, the only type the JVM raises for a null dereference, and
         * the message follows {@link #JVM_NULL_POINTER_MESSAGE}.
         */
        private static boolean isJvmNullPointerMessage(Exception ex, String message) {
            return ex.getClass() == NullPointerException.class && JVM_NULL_POINTER_MESSAGE.matcher(message).matches();
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
     * Spring WS's {@link SoapMessageDispatcher} with the {@code mustUnderstand} handling of the original's CXF service
     * construct, in one of two modes fixed when it is built.
     * <p>
     * Checked, the mode of a {@code cxf:jaxws-service}: CXF 2.7's answer to a SOAP 1.1 request carrying a
     * {@code mustUnderstand="1"} header block that no interceptor of the mapped endpoint understands. CXF's
     * {@code MustUnderstandInterceptor} answered it with {@code soap:MustUnderstand} and the {@code faultstring}
     * {@code MustUnderstand headers: [<names>] are not understood.}, the names being the blocks' qualified names
     * collected in a {@link HashSet} and rendered by its {@code toString}: a repeated block is listed once, several
     * blocks appear in hash order, and an unqualified block reads as its local name. The fault carries no
     * {@code faultactor}, and the response gets no header.
     * <p>
     * The check selects the blocks Spring selects (those addressed to no actor, to the {@code next} actor or to one of
     * the mapped chain's actors) and asks every {@link SoapEndpointInterceptor} of the chain whether it understands
     * each, but it does not delegate to Spring, whose fault text is fixed and which logs the caller's header names at
     * WARN. The one WARN line logged here carries only the number of blocks not understood, because a caller-chosen
     * namespace can carry any text, line breaks included. Dispatch returns without running any endpoint interceptor
     * once this check fails, so the fault is restyled into CXF's envelope form here. A request without a header, and a
     * SOAP 1.2 request, keep Spring's handling.
     * <p>
     * Unchecked, the mode of a {@code cxf:proxy-service}: no header block is checked, neither here nor by Spring, so
     * every request goes on to its endpoint whatever its header holds, and nothing is logged. When it configures a
     * proxy service, Mule 3.8's {@code ProxyServiceMessageProcessorBuilder.configureServer} removes CXF's
     * {@code MustUnderstandInterceptor} from its binding's inbound interceptors, whatever its payload mode, so a proxy
     * service never answered with a MustUnderstand fault.
     * <p>
     * Spring WS's dispatcher constructor loads the default strategies of the runtime class from a file named after its
     * short class name beside it, so {@code SoapFaultConfig.CxfStyleSoapMessageDispatcher.properties} carries the
     * entries of Spring's {@code SoapMessageDispatcher.properties}; a subclass needs a file of its own. Like the
     * servlet's default receiver, it collects every endpoint mapping, endpoint adapter and exception resolver bean of
     * the context when the context is set on it. Holds no per-request state, so one instance serves concurrent
     * requests.
     */
    public static class CxfStyleSoapMessageDispatcher extends SoapMessageDispatcher {

        private static final Logger log = LoggerFactory.getLogger(CxfStyleSoapMessageDispatcher.class);

        private final SoapEnvelopeStyleInterceptor styler;

        private final boolean mustUnderstandChecked;

        /**
         * Builds the checked receiver of a {@code cxf:jaxws-service}.
         *
         * @param styler the CXF envelope styler applied to a MustUnderstand fault, which no endpoint interceptor sees
         */
        public CxfStyleSoapMessageDispatcher(SoapEnvelopeStyleInterceptor styler) {
            this(styler, true);
        }

        /**
         * @param styler                the CXF envelope styler applied to a MustUnderstand fault, which no endpoint
         *                              interceptor sees; required in both modes
         * @param mustUnderstandChecked {@code true} for the checked receiver of a {@code cxf:jaxws-service},
         *                              {@code false} for the unchecked receiver of a {@code cxf:proxy-service}
         */
        public CxfStyleSoapMessageDispatcher(SoapEnvelopeStyleInterceptor styler, boolean mustUnderstandChecked) {
            Assert.notNull(styler, "SoapEnvelopeStyleInterceptor must not be null");
            this.styler = styler;
            this.mustUnderstandChecked = mustUnderstandChecked;
        }

        /**
         * Checked, answers a SOAP 1.1 request with CXF's MustUnderstand fault, and stops its dispatch, when a
         * {@code mustUnderstand} header block addressed to this receiver is understood by no interceptor of the
         * mapped chain, and lets every other request through; a request that is not a SOAP 1.1 message with a header
         * is left to Spring's check. Unchecked, lets every request through without examining its header.
         */
        @Override
        protected boolean handleRequest(EndpointInvocationChain mappedEndpoint, MessageContext messageContext) {
            if (!mustUnderstandChecked) {
                return true;
            }
            if (!(messageContext.getRequest() instanceof SoapMessage request)
                    || !(request.getSoapHeader() instanceof Soap11Header header)) {
                return super.handleRequest(mappedEndpoint, messageContext);
            }
            String[] actors = mappedEndpoint instanceof SoapEndpointInvocationChain soapChain
                    ? soapChain.getActorsOrRoles() : null;
            // CXF collected the names in a HashSet, which sets their order and lists a repeated block once.
            Set<QName> notUnderstood = new HashSet<>();
            Iterator<SoapHeaderElement> headerElements = header.examineHeaderElementsToProcess(actors);
            while (headerElements.hasNext()) {
                SoapHeaderElement headerElement = headerElements.next();
                if (headerElement.getMustUnderstand() && !isUnderstood(mappedEndpoint, headerElement)) {
                    notUnderstood.add(headerElement.getName());
                }
            }
            if (notUnderstood.isEmpty()) {
                return true;
            }
            SoapMessage response = (SoapMessage) messageContext.getResponse();
            // CXF's MUST_UNDERSTAND message, "MustUnderstand headers: {0} are not understood.", with the set as {0}.
            // No locale, so faultstring carries no xml:lang.
            response.getSoapBody().addMustUnderstandFault(
                    "MustUnderstand headers: " + notUnderstood + " are not understood.", null);
            styler.restyle(response);
            log.warn("Answering a request with a SOAP MustUnderstand fault: {} mandatory header block(s) not "
                    + "understood", notUnderstood.size());
            return false;
        }

        /** Whether any {@link SoapEndpointInterceptor} of the chain understands the header block, as Spring asks. */
        private static boolean isUnderstood(EndpointInvocationChain mappedEndpoint, SoapHeaderElement headerElement) {
            EndpointInterceptor[] interceptors = mappedEndpoint.getInterceptors();
            if (interceptors == null) {
                return false;
            }
            for (EndpointInterceptor interceptor : interceptors) {
                if (interceptor instanceof SoapEndpointInterceptor soapInterceptor
                        && soapInterceptor.understands(headerElement)) {
                    return true;
                }
            }
            return false;
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
