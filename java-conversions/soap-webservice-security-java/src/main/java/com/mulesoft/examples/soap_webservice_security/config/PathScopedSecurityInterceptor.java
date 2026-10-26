package com.mulesoft.examples.soap_webservice_security.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.wss4j.common.WSS4JConstants;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.ext.WSSecurityException.ErrorCode;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.engine.WSSecurityEngine;
import org.apache.wss4j.dom.engine.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandler;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.wss4j.dom.validate.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.server.SoapEndpointInterceptor;
import org.springframework.ws.soap.soap11.Soap11Body;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.w3c.dom.Document;

/**
 * Inbound WS-Security enforcement of the six Greeter paths on port 63081, one required action set per request path,
 * reproducing what each original {@code cxf:jaxws-service} with {@code cxf:ws-security} accepted and rejected.
 * <p>
 * For a secured path the request's {@code wsse:Security} header is processed by WSS4J's
 * {@link WSSecurityEngine#processSecurityHeader(Document, RequestData)} with the path's callback handler, crypto and
 * SAML validator, and the results are checked with WSS4J's own receiver check,
 * {@code WSHandler.checkReceiverResultsAnyOrder}: the "do they match, in any order?" check CXF 2.7's
 * {@code WSS4JInInterceptor} applied. That check skips signature-confirmation results, {@code BinarySecurityToken}
 * results and encryption results that reference no data, so the certificate token a signed request carries never
 * fails it; every other result must match one required action and every required action must be matched. The check
 * fails closed: a missing or empty security header, a required action without a result, an action the path does not
 * require and any unexpected runtime failure are all rejected.
 * <p>
 * A rejection is a SOAP 1.1 fault, sent with HTTP 500 by the servlet connection. {@code FAILED_AUTHENTICATION} (a
 * wrong UsernameToken password) is answered with {@code wsse:FailedAuthentication} and "The security token could not
 * be authenticated or authorized"; every other failure, including the {@code FAILURE} the SAML validator raises, with
 * {@code wsse:InvalidSecurity} and "An error was discovered processing the &lt;wsse:Security&gt; header". The original
 * clients' negative cases match those texts by substring. Spring's {@code Wss4jSecurityInterceptor} is not used
 * inbound because its action-mismatch fault text differs from the original's.
 * <p>
 * Paths absent from the policy table, and requests not received over HTTP, pass through unsecured; the listener's
 * routing filter answers paths the original did not serve. On an unsecured path {@link #understands} declines the
 * security header, so a {@code mustUnderstand} header there gets Spring WS's MustUnderstand fault, as CXF answered a
 * service without WS-Security.
 * <p>
 * Deliberately neither a {@code SmartEndpointInterceptor} nor a component: endpoint mappings pick up smart
 * interceptors and the web-service configuration registers this one after the envelope styler, so a second
 * registration would validate each request twice. Policies are immutable, and WSS4J state is created per request, so
 * one instance serves concurrent requests.
 * <p>
 * Compatibility with the original success cases is configured per path through {@link PathPolicy}, and exactly one
 * fallback is needed: {@code /services/saml} must have {@code validateSamlSubjectConfirmation} set to {@code false}.
 * With WSS4J's default of {@code true}, WSS4J 4 rejects the original unsigned sender-vouches request ("Assertion fails
 * sender-vouches requirements"), because no message signature covers the assertion and the body, a check WSS4J 1.6 under CXF 2.7
 * did not make; every check of {@code SAMLCustomValidator} still runs. {@code /services/signedsaml} passes with the
 * default, and no path needs BSP enforcement disabled.
 */
public class PathScopedSecurityInterceptor implements SoapEndpointInterceptor {

    /** Fault string of every rejection other than a failed authentication; WSS4J's {@code INVALID_SECURITY} text. */
    static final String INVALID_SECURITY_FAULT_STRING = "An error was discovered processing the <wsse:Security> header";

    /** Fault string of a failed authentication; WSS4J's {@code FAILED_AUTHENTICATION} text. */
    static final String FAILED_AUTHENTICATION_FAULT_STRING =
            "The security token could not be authenticated or authorized";

    static final QName INVALID_SECURITY_FAULT_CODE = new QName(WSS4JConstants.WSSE_NS, "InvalidSecurity", "wsse");

    static final QName FAILED_AUTHENTICATION_FAULT_CODE =
            new QName(WSS4JConstants.WSSE_NS, "FailedAuthentication", "wsse");

    private static final QName SECURITY_HEADER = new QName(WSS4JConstants.WSSE_NS, "Security");

    private static final Logger LOG = LoggerFactory.getLogger(PathScopedSecurityInterceptor.class);

    private static final ReceiverResultsCheck RECEIVER_RESULTS_CHECK = new ReceiverResultsCheck();

    static {
        WSSConfig.init();
    }

    private final Map<String, PathPolicy> policies;

    /**
     * @param policies the policy of each served path, keyed by the request path without the context path, for
     *                 example {@code /services/username}
     */
    public PathScopedSecurityInterceptor(Map<String, PathPolicy> policies) {
        this.policies = Map.copyOf(Objects.requireNonNull(policies, "policies"));
    }

    /** @return the immutable policy table, keyed by request path */
    public Map<String, PathPolicy> policies() {
        return policies;
    }

    @Override
    public boolean understands(SoapHeaderElement header) {
        return SECURITY_HEADER.equals(header.getName())
                && currentPath().map(policies::get).map(PathPolicy::isSecured).orElse(false);
    }

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) {
        Optional<String> path = currentPath();
        PathPolicy policy = path.map(policies::get).orElse(null);
        if (policy == null || !policy.isSecured()) {
            return true;
        }
        try {
            validate((SoapMessage) messageContext.getRequest(), policy);
            return true;
        }
        catch (WSSecurityException ex) {
            LOG.warn("WS-Security validation failed on {}: {}", path.get(), loggable(ex.getMessage()));
            writeFault(messageContext, ex.getErrorCode());
            return false;
        }
        catch (RuntimeException ex) {
            LOG.warn("WS-Security validation failed on {}: {}", path.get(), loggable(ex.toString()), ex);
            writeFault(messageContext, ErrorCode.INVALID_SECURITY);
            return false;
        }
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
        // Responses are not secured: the original services declared no outbound actions.
    }

    private static void validate(SoapMessage message, PathPolicy policy) throws WSSecurityException {
        Crypto sigVerCrypto = policy.sigVerCrypto() == null ? null : policy.sigVerCrypto().get();
        Crypto decCrypto = policy.decCrypto() == null ? null : policy.decCrypto().get();

        WSSConfig config = WSSConfig.getNewInstance();
        if (policy.samlValidator() != null) {
            config.setValidator(WSConstants.SAML_TOKEN, policy.samlValidator());
            config.setValidator(WSConstants.SAML2_TOKEN, policy.samlValidator());
        }

        // No subject or issuer certificate constraints and no revocation: the single self-signed "joe" certificate
        // is trusted directly because the keystore holds it, as WSS4J 1.6 trusted it; building a certificate path
        // instead would reject its MD5withRSA signature on Java 17.
        RequestData requestData = new RequestData();
        requestData.setWssConfig(config);
        requestData.setCallbackHandler(policy.callbackHandler());
        requestData.setSigVerCrypto(sigVerCrypto);
        requestData.setDecCrypto(decCrypto);
        requestData.setEnableRevocation(false);
        requestData.setValidateSamlSubjectConfirmation(policy.validateSamlSubjectConfirmation());
        requestData.setDisableBSPEnforcement(policy.disableBspEnforcement());

        // Written back so the endpoint reads the decrypted body, as Spring's Wss4jSecurityInterceptor does.
        Document document = message.getDocument();
        WSHandlerResult result = new WSSecurityEngine().processSecurityHeader(document, requestData);
        message.setDocument(document);

        if (result == null || result.getResults() == null || result.getResults().isEmpty()) {
            throw new WSSecurityException(ErrorCode.INVALID_SECURITY);
        }
        List<Integer> requiredActions = WSSecurityUtil.decodeAction(policy.requiredActions());
        if (!RECEIVER_RESULTS_CHECK.matches(result.getResults(), requiredActions)) {
            throw new WSSecurityException(ErrorCode.INVALID_SECURITY);
        }
    }

    private static void writeFault(MessageContext messageContext, ErrorCode errorCode) {
        boolean failedAuthentication = errorCode == ErrorCode.FAILED_AUTHENTICATION;
        QName faultCode = failedAuthentication ? FAILED_AUTHENTICATION_FAULT_CODE : INVALID_SECURITY_FAULT_CODE;
        String faultString = failedAuthentication ? FAILED_AUTHENTICATION_FAULT_STRING : INVALID_SECURITY_FAULT_STRING;

        SoapBody body = ((SoapMessage) messageContext.getResponse()).getSoapBody();
        if (body instanceof Soap11Body soap11Body) {
            // No locale, so no xml:lang on faultstring; the envelope styler removes any SAAJ adds.
            soap11Body.addFault(faultCode, faultString, null);
        }
        else {
            // SOAP 1.2 defines no custom fault codes; only a SOAP 1.2 message factory reaches this.
            body.addClientOrSenderFault(faultString, null);
        }
    }

    /**
     * The request path without the context path, decoded, without path parameters and with dot segments resolved.
     * The raw request URI is deliberately not used: an encoded, {@code ;}-suffixed or dotted spelling of a secured
     * path would miss that path's policy and pass through unsecured.
     */
    private static Optional<String> currentPath() {
        TransportContext context = TransportContextHolder.getTransportContext();
        if (context == null) {
            return Optional.empty();
        }
        WebServiceConnection connection = context.getConnection();
        if (!(connection instanceof HttpServletConnection servletConnection)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletConnection.getHttpServletRequest();
        return Optional.of(StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request)));
    }

    /** WSS4J messages can quote token content; line breaks are flattened so a log entry stays one line. */
    private static String loggable(String message) {
        return message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * The WS-Security requirements of one path.
     *
     * @param requiredActions                 WSS4J action names separated by white space, for example
     *                                        {@code UsernameToken Signature Timestamp}; blank for an unsecured path
     * @param callbackHandler                 password callback for UsernameToken and private keys, or {@code null}
     * @param sigVerCrypto                    signature verification crypto, resolved per request, or {@code null}
     * @param decCrypto                       decryption crypto, resolved per request, or {@code null}
     * @param samlValidator                   validator registered for SAML 1.1 and 2.0 assertions, or {@code null}
     *                                        for WSS4J's default
     * @param validateSamlSubjectConfirmation whether WSS4J checks the subject confirmation requirements of SAML
     *                                        assertions after processing the header
     * @param disableBspEnforcement           whether WSS4J's Basic Security Profile rules are switched off
     */
    public record PathPolicy(String requiredActions, CallbackHandler callbackHandler, Supplier<Crypto> sigVerCrypto,
            Supplier<Crypto> decCrypto, Validator samlValidator, boolean validateSamlSubjectConfirmation,
            boolean disableBspEnforcement) {

        /** A path whose requests are not checked. */
        public static final PathPolicy UNSECURED = new PathPolicy("", null, null, null, null, true, false);

        /**
         * Normalizes the action list to single spaces and rejects an action WSS4J does not know, so a mistyped
         * policy fails at start-up rather than on every request.
         *
         * @throws IllegalArgumentException when {@code requiredActions} names an unknown action
         */
        public PathPolicy {
            requiredActions = requiredActions == null ? "" : requiredActions.trim().replaceAll("\\s+", " ");
            if (!requiredActions.isEmpty()) {
                try {
                    WSSecurityUtil.decodeAction(requiredActions);
                }
                catch (WSSecurityException ex) {
                    throw new IllegalArgumentException("Unknown WS-Security action in '" + requiredActions + "'", ex);
                }
            }
        }

        /**
         * A policy with SAML subject-confirmation validation and BSP enforcement on, WSS4J's defaults.
         */
        public static PathPolicy of(String requiredActions, CallbackHandler callbackHandler,
                Supplier<Crypto> sigVerCrypto, Supplier<Crypto> decCrypto, Validator samlValidator) {
            return new PathPolicy(requiredActions, callbackHandler, sigVerCrypto, decCrypto, samlValidator, true, false);
        }

        /** @return whether requests to the path must carry the required actions */
        public boolean isSecured() {
            return !requiredActions.isBlank();
        }
    }

    /** Exposes WSS4J's protected receiver check; the handler's own options are never consulted by it. */
    private static final class ReceiverResultsCheck extends WSHandler {

        boolean matches(List<WSSecurityEngineResult> results, List<Integer> requiredActions) {
            return checkReceiverResultsAnyOrder(results, requiredActions);
        }

        @Override
        public Object getOption(String key) {
            return null;
        }

        @Override
        public Object getProperty(Object msgContext, String key) {
            return null;
        }

        @Override
        public void setProperty(Object msgContext, String key, Object value) {
            // The receiver check reads and writes no handler properties.
        }

        @Override
        public String getPassword(Object msgContext) {
            return null;
        }

        @Override
        public void setPassword(Object msgContext, String password) {
            // The receiver check handles no passwords.
        }
    }
}
