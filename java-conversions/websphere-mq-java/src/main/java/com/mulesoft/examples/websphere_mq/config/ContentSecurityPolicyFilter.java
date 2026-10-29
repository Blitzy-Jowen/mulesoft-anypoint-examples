/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends the restrictive {@link #POLICY} in the {@code Content-Security-Policy} header of every response whose
 * Content-Type the application sets to HTML, which in this application is the copied docroot page
 * {@code static/services/wmqExample/index.html}, so that ordinary script carried in a dequeued MQ message, such as an
 * injected event-handler attribute, does not run in a subscriber's browser. This is partial hardening against stored
 * script injection, not its elimination: the residual risk below still lets a message run code.
 *
 * <p>Why it exists: the page's {@code dequeueCallback} inserts the text of every delivery on the dequeue channel as
 * markup ({@code innerHTML}, index.html:43,47). A text such as {@code <img src=x onerror=alert(document.domain)>},
 * published by any client on the enqueue channel, keeps its markup through queue {@code in}, the appended
 * {@code " - processed"} and queue {@code out}, and would run as script in every subscribed page (CWE-79). The page
 * is served byte for byte as the original docroot held it and cannot be edited, so the server restricts what script
 * may run instead, with a header the original Jetty docroot did not send.
 *
 * <p>The policy restricts script and nothing else:
 * <ul>
 *   <li>{@code script-src 'self'} loads {@code mule-resource/js/dojo/dojo.js} and {@code mule-resource/js/mule.js}
 *       (index.html:17-18) and the same-origin scripts {@code mule.js} writes into the document
 *       ({@code dojo/dojo.js}, {@code _mule.js});</li>
 *   <li>{@code 'unsafe-eval'}: {@code _mule.js} requires {@code dojox.cometd}, {@code dojox.cometd.timestamp} and
 *       {@code dojox.uuid.generateRandomUuid}, which Dojo's loader fetches by XHR and evaluates, and
 *       {@code dojo.fromJson} evaluates every Bayeux response; without it the page fails with an {@code EvalError}
 *       while loading {@code dojox/cometd.js};</li>
 *   <li>four hashes match exactly the page's own inline code: the inline script (index.html:21-62), the
 *       {@code onload} and {@code onunload} handlers of {@code body} (index.html:66) and the {@code onclick} handler of
 *       the Send button (index.html:73); {@code 'unsafe-hashes'} is what lets a hash allow an event-handler
 *       attribute;</li>
 *   <li>{@code object-src 'none'} stops plugin content, and {@code base-uri 'none'} stops a {@code <base>} element
 *       from re-pointing the page's relative script URLs.</li>
 * </ul>
 * No {@code 'unsafe-inline'} is allowed and no other directive is set, so markup without script in a message (bold
 * text, inline styles, images) renders as before, and so does the page's own {@code style} attribute. In message
 * markup, event-handler attributes other than the three hashed bodies, {@code javascript:} URLs, inline scripts,
 * plugins and {@code <base>} stop working. The page's scripts and handlers, the Bayeux traffic and the MQ payload
 * bytes are unchanged, so the difference is for hostile input only; {@code DECISIONS.md} section 8 records it.
 *
 * <p>Residual risk: the policy cannot stop three ways a message can still run code, because the copied page and its
 * Dojo client need {@code 'self'}, {@code 'unsafe-eval'} and the hashed handlers:
 * <ul>
 *   <li>the copied Dojo evaluates the {@code djConfig} attribute of the script element that loads it, so a message
 *       carrying {@code <iframe srcdoc="<script src='mule-resource/js/dojo/dojo.js' djConfig='...'>">} executes
 *       arbitrary same-origin JavaScript in a frame that inherits this policy; {@code frame-src} and
 *       {@code child-src} do not govern {@code srcdoc} frames;</li>
 *   <li>an injected event-handler attribute whose body equals one of the three hashed handler bodies runs that body:
 *       {@code <img src=x onerror="dispose();">} unsubscribes the receiving page from the dequeue channel until the
 *       page is reloaded;</li>
 *   <li>a script element loading the same-origin {@code /ajax/cometd} with a {@code jsonp} callback, for example in
 *       such a frame, remains allowed by {@code 'self'}; its response calls a dotted function reference of the
 *       caller's choice with a Bayeux array.</li>
 * </ul>
 * Closing them needs nonces or integrity attributes on the page's own tags, or a page that inserts message text as
 * text rather than markup: an edit of the copied page, which must stay byte-identical to the original. The policy
 * therefore narrows stored script injection on this page but does not fully protect against it.
 *
 * <p>The filter wraps the response and, whenever its Content-Type is set to the media type {@code text/html}, with
 * any parameters and in any letter case, through {@code setContentType}, or through {@code setHeader} or
 * {@code addHeader} with the header name {@code Content-Type} in any letter case, sets the policy header, replacing
 * rather than repeating it. Every route that yields the page carries it: {@code <server path>/} forwarded to
 * {@code index.html}, {@code index.html} itself, and {@code HEAD} of either. The scripts, the redirect of
 * {@code <server path>}, the {@code text/plain} 404 and 405 answers and the Bayeux responses ({@code text/json},
 * {@code text/javascript}) do not. Nothing is buffered, and status, other headers and body reach the container's
 * response unchanged. Spring Boot registers a {@link OncePerRequestFilter} for every dispatcher type; it wraps only on
 * the request dispatch, and the FORWARD to the welcome file and the ASYNC dispatches of held {@code /ajax/cometd}
 * connects write through that same wrapper.
 */
@Component
public class ContentSecurityPolicyFilter extends OncePerRequestFilter {

    /** The response header that carries {@link #POLICY}. */
    public static final String HEADER_NAME = "Content-Security-Policy";

    /** Media type of the responses that carry the policy, compared ignoring letter case and parameters. */
    static final String HTML_MEDIA_TYPE = "text/html";

    /** Source allowing the same-origin scripts: Dojo, {@code mule.js} and the scripts {@code mule.js} writes. */
    static final String SELF = "'self'";

    /** Source allowing the evaluation Dojo's module loader and {@code dojo.fromJson} perform. */
    static final String UNSAFE_EVAL = "'unsafe-eval'";

    /** Source letting the handler hashes below allow event-handler attributes, not only inline scripts. */
    static final String UNSAFE_HASHES = "'unsafe-hashes'";

    /**
     * Hash of the page's inline script, index.html:21-62: the text between {@code <script type="text/javascript">}
     * and {@code </script>}, which defines {@code init}, {@code dispose}, {@code dequeueCallback} and
     * {@code rpcEnqueue}.
     */
    static final String INLINE_SCRIPT_HASH = "'sha256-SHdqOMdTx7uIUr5JlTWG1XWU8vtIQoXkNsiBetb7hFk='";

    /** Hash of {@code init();}, the {@code onload} handler of the page's {@code body}, index.html:66. */
    static final String ONLOAD_HANDLER_HASH = "'sha256-GEnM5q1nYY/iACnyMTdov+tNp9OFcBnnDgNXUXaVNXc='";

    /** Hash of {@code dispose();}, the {@code onunload} handler of the page's {@code body}, index.html:66. */
    static final String ONUNLOAD_HANDLER_HASH = "'sha256-WOJYx795a549qUu34ZFzKWSxhsCOems6+XSmQmJn7kg='";

    /** Hash of {@code rpcEnqueue();}, the {@code onclick} handler of the page's Send button, index.html:73. */
    static final String ONCLICK_HANDLER_HASH = "'sha256-w5NLw4gThl1/Q5CjWK2U7SWO76NMJzun9kBEfXpfPss='";

    /** Source allowing nothing, for plugin content and {@code <base>} URLs. */
    static final String NONE = "'none'";

    /** The policy every HTML response carries in {@link #HEADER_NAME}. */
    public static final String POLICY = "script-src " + SELF + " " + UNSAFE_EVAL + " " + UNSAFE_HASHES
            + " " + INLINE_SCRIPT_HASH + " " + ONLOAD_HANDLER_HASH + " " + ONUNLOAD_HANDLER_HASH
            + " " + ONCLICK_HANDLER_HASH
            + "; object-src " + NONE
            + "; base-uri " + NONE;

    /** Passes the request on with a response that sends {@link #POLICY} once its Content-Type is HTML. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, new PolicyOnHtmlResponse(response));
    }

    /**
     * Whether {@code contentType} names the media type {@code text/html}: the part before any {@code ;} parameters,
     * trimmed, equal to it ignoring letter case.
     *
     * @param contentType a Content-Type value, or {@code null}
     * @return {@code false} for {@code null} and for every other media type
     */
    static boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return HTML_MEDIA_TYPE.equalsIgnoreCase(mediaType.trim());
    }

    /** A pass-through response that sets the policy header whenever its Content-Type is set to HTML. */
    private static final class PolicyOnHtmlResponse extends HttpServletResponseWrapper {

        /**
         * Wraps {@code response}, which receives every call unchanged, plus the policy header once an HTML
         * Content-Type is set.
         *
         * @param response the container's response for the request being filtered
         */
        PolicyOnHtmlResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(type);
            applyPolicyIfHtml(type);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, value);
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                applyPolicyIfHtml(value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, value);
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                applyPolicyIfHtml(value);
            }
        }

        /**
         * Sets {@link #HEADER_NAME} to {@link #POLICY} on the wrapped response, replacing any earlier value, when
         * {@code contentType} names HTML as {@link #isHtml(String)} decides; otherwise leaves the headers unchanged.
         *
         * @param contentType the Content-Type value just set on the response, or {@code null}
         */
        private void applyPolicyIfHtml(String contentType) {
            if (isHtml(contentType)) {
                super.setHeader(HEADER_NAME, POLICY);
            }
        }
    }
}
