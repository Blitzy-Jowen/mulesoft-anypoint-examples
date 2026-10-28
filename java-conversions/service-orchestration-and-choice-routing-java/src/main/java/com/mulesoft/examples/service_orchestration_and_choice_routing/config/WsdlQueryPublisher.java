package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Publishes WSDL and schema metadata the way a CXF {@code jaxws-service} did: {@code GET <path>?wsdl} returns the
 * registered WSDL with every {@code soap:address}/{@code soap12:address} {@code location} set to the request URL, and
 * {@code GET <path>?xsd=<location>} returns each schema the WSDL imports or includes, directly or transitively, with
 * every reference rewritten to {@code <request URL>?xsd=<location>}.
 *
 * <p>Instances are created by the consuming configuration ({@code new WsdlQueryPublisher().register(...)}) and
 * installed through a {@code FilterRegistrationBean}; this class is deliberately not a Spring component.
 *
 * <ul>
 *   <li>Every document is read, parsed and resolved when it is registered, so a missing, unreadable or unparseable
 *       WSDL or schema fails start-up with an {@link IllegalStateException}. Requests parse a fresh DOM from the
 *       cached bytes, so concurrent requests share no mutable state.</li>
 *   <li>References are {@code xsd:import}, {@code xsd:include} and {@code xsd:redefine} ({@code schemaLocation}) and
 *       {@code wsdl:import} ({@code location}), matched by namespace URI and local name, never by prefix. A reference
 *       without a location, or whose location carries a URI scheme, is neither resolved nor rewritten.</li>
 *   <li>A location resolves relative to the referring document first and, when nothing exists there, from the
 *       classpath root. The key under which a schema is served is the location exactly as written; within one
 *       registration a key must always denote the same resource, otherwise {@link #register} throws
 *       {@link IllegalStateException}. Keys of different registrations are independent.</li>
 *   <li>Only {@code GET} is handled, and its query is read as CXF's {@code WSDLGetInterceptor} read it: split at
 *       {@code &}, empty parameters skipped, each name lower-cased and taken raw, a repeated name keeping its last
 *       value. A {@code wsdl} parameter, in any case or position and with or without a value, selects the
 *       registration's WSDL and takes precedence over {@code xsd}; its value is not consulted, because each
 *       registration publishes exactly one WSDL (CXF's {@code ""} key). Otherwise an {@code xsd} parameter selects the
 *       schema whose registered key equals its value after URL-decoding as UTF-8. A request whose path matches no
 *       registration, whose query selects nothing (an unknown key or a malformed escape included), or that uses
 *       another method continues down the filter chain untouched. The servlet parameter API is never called,
 *       because it can consume a form-encoded body the chain must still read.</li>
 *   <li>A served request is logged at DEBUG with registration-owned identity only (the document kind, its registered
 *       WSDL or schema key and resource, and the registration's patterns), never the request's query, parameters,
 *       path or URL, which carry caller-supplied data. A location that cannot be resolved relative to its referring
 *       document is logged at DEBUG as well, before the classpath-root fallback is tried.</li>
 *   <li>Log lines and exception messages name a resource as {@code class path resource [<path>]} or
 *       {@code URL [<url>]}, and a schema location or key as written, each without URL user information, query or
 *       fragment; control, line-separator and format characters are escaped ({@code \n}, {@code \r}, {@code \t}
 *       or a Java-style Unicode escape), a backslash is doubled, and the text is cut to 256 characters with a
 *       truncation marker. A resource without a URL is named by its type, because its description is free text.
 *       Parser and I/O failures are reported by exception type, and a parse error by its line and column, never by
 *       the failure's own message, which can quote document content or a full URL. A parse failure stays attached as
 *       the cause of the start-up exception; an I/O failure is reported by type only and not attached, because the
 *       JDK's I/O messages can carry the full URL with its user information and query.</li>
 *   <li>Documents are parsed with DOCTYPE declarations, external entities, external DTDs and XInclude disabled, and
 *       serialized without added indentation behind the declaration {@code <?xml version="1.0" encoding="UTF-8"?>}.</li>
 * </ul>
 */
public class WsdlQueryPublisher extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WsdlQueryPublisher.class);

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP11_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String ADDRESS = "address";
    private static final String LOCATION = "location";
    private static final String SCHEMA_LOCATION = "schemaLocation";

    private static final String WSDL_PARAMETER = "wsdl";
    private static final String XSD_PARAMETER = "xsd";
    private static final String XSD_QUERY_PREFIX = XSD_PARAMETER + "=";
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final String QUERY_SAFE_PUNCTUATION = "-._~/:@!$'()*,;";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** Longest diagnostic text, escapes included, before {@link #TRUNCATED} replaces the rest. */
    private static final int DIAGNOSTIC_LIMIT = 256;
    /** Appended where {@link #escapedAndBounded(String)} cuts a diagnostic text short. */
    private static final String TRUNCATED = "...(truncated)";
    /** URL user information, in a nested URL ({@code jar:http://user:password@host/...}) as well. */
    private static final Pattern USER_INFO = Pattern.compile("//[^/]*@");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<Published> published = new CopyOnWriteArrayList<>();
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    /**
     * Creates a publisher with no registrations; every request passes through until {@link #register} is called.
     */
    public WsdlQueryPublisher() {
        super();
    }

    /**
     * One published WSDL: the request paths it is served on and the schema closure it references.
     *
     * @param wsdl     the WSDL served for {@code ?wsdl}
     * @param patterns Ant-style patterns matched against the request path within the application
     * @param schemas  every referenced schema, keyed by its location exactly as written in the referring document
     */
    public record Registration(Resource wsdl, List<String> patterns, Map<String, Resource> schemas) {

        public Registration {
            Assert.notNull(wsdl, "wsdl must not be null");
            Assert.notNull(patterns, "patterns must not be null");
            Assert.notNull(schemas, "schemas must not be null");
            patterns = List.copyOf(patterns);
            schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
        }
    }

    /** Registration plus the cached bytes served for it; the arrays are never written after construction. */
    private record Published(Registration registration, byte[] wsdl, Map<String, byte[]> schemas) {
    }

    /** A document waiting for its references to be resolved during the breadth-first walk. */
    private record Pending(Resource resource, Document document) {
    }

    /** A reference element, the attribute carrying its location, and that location as written. */
    private record Reference(Element element, String attribute, String location) {
    }

    /**
     * The registration a request matched, the cached document its query selects, and that document's description
     * for diagnostics, built from registration data alone.
     */
    private record Selection(Published target, byte[] document, String description) {
    }

    /**
     * Publishes {@code wsdl} on every request path matching one of {@code antPatterns}. Registrations are consulted
     * in registration order and the first one with a matching pattern serves the request.
     *
     * @param wsdl        the WSDL document; its imports and includes are resolved and cached immediately
     * @param antPatterns Ant-style patterns for the request path within the application, for example a listener path
     *                    and the same path followed by {@code /**}
     * @return this publisher, for chaining
     * @throws IllegalStateException when the WSDL or a referenced schema does not exist, cannot be read or cannot be
     *                               parsed, or when one location key resolves to two different resources
     */
    public synchronized WsdlQueryPublisher register(Resource wsdl, String... antPatterns) {
        Assert.notNull(wsdl, "wsdl must not be null");
        Assert.notEmpty(antPatterns, "at least one path pattern is required");
        Assert.noNullElements(antPatterns, "path patterns must not be null");

        byte[] wsdlBytes = read(wsdl);
        Map<String, Resource> schemas = new LinkedHashMap<>();
        Map<String, byte[]> documentsByIdentity = new HashMap<>();
        documentsByIdentity.put(identity(wsdl), wsdlBytes);

        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(wsdl, parse(wsdlBytes, wsdl)));
        while (!queue.isEmpty()) {
            Pending current = queue.poll();
            for (Reference reference : references(current.document())) {
                Resource resolved = resolve(current.resource(), reference.location());
                String resolvedIdentity = identity(resolved);
                Resource previous = schemas.putIfAbsent(reference.location(), resolved);
                if (previous != null && !identity(previous).equals(resolvedIdentity)) {
                    throw new IllegalStateException("Schema location '" + safeLocation(reference.location())
                            + "' in " + safeResource(current.resource()) + " resolves to " + safeResource(resolved)
                            + ", but the same location already denotes " + safeResource(previous)
                            + " in the closure of " + safeResource(wsdl));
                }
                if (!documentsByIdentity.containsKey(resolvedIdentity)) {
                    byte[] bytes = read(resolved);
                    documentsByIdentity.put(resolvedIdentity, bytes);
                    queue.add(new Pending(resolved, parse(bytes, resolved)));
                }
            }
        }

        Map<String, byte[]> schemaBytes = new HashMap<>();
        schemas.forEach((key, resource) -> schemaBytes.put(key, documentsByIdentity.get(identity(resource))));
        Registration registration = new Registration(wsdl, List.of(antPatterns), schemas);
        published.add(new Published(registration, wsdlBytes, Map.copyOf(schemaBytes)));
        registrations.add(registration);
        log.info("Publishing {} for {} with {} referenced schema(s)", safeResource(wsdl),
                registration.patterns(), schemas.size());
        return this;
    }

    /**
     * Returns the registrations in registration order.
     *
     * @return an unmodifiable view that reflects later registrations
     */
    public List<Registration> registrations() {
        return Collections.unmodifiableList(registrations);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Selection selection = HttpMethod.GET.matches(request.getMethod()) ? select(request) : null;
        if (selection == null) {
            chain.doFilter(request, response);
            return;
        }
        String requestUrl = request.getRequestURL().toString();
        byte[] body = rewrite(selection.document(), selection.target().registration().schemas().keySet(), requestUrl);
        log.debug("Serving {} for {}", selection.description(), selection.target().registration().patterns());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * The registration and cached document a metadata query asks for, or {@code null} when it is not served here.
     * The description is built from the registration: its WSDL resource, or the closure's own schema key and resource,
     * each in its credential-free diagnostic form.
     */
    private Selection select(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) {
            return null;
        }
        Published target = matchingRegistration(pathWithinApplication(request));
        if (target == null) {
            return null;
        }
        Registration registration = target.registration();
        Map<String, String> parameters = queryParameters(query);
        if (parameters.containsKey(WSDL_PARAMETER)) {
            return new Selection(target, target.wsdl(), "WSDL " + safeResource(registration.wsdl()));
        }
        String key = decodedQueryValue(parameters.get(XSD_PARAMETER));
        if (key != null) {
            for (Map.Entry<String, Resource> schema : registration.schemas().entrySet()) {
                if (schema.getKey().equals(key)) {
                    return new Selection(target, target.schemas().get(schema.getKey()),
                            "schema '" + safeLocation(schema.getKey()) + "' (" + safeResource(schema.getValue()) + ")");
                }
            }
        }
        return null;
    }

    /**
     * {@code query} as CXF's {@code UrlUtils.parseQueryString} read it for {@code WSDLGetInterceptor}: split at every
     * {@code &} with empty parameters skipped; a parameter's name is the text before its first {@code =}, or all of
     * it, lower-cased; its value is the raw text after that {@code =}, or empty without one. Neither is
     * percent-decoded here, and a repeated name keeps its last value.
     */
    private static Map<String, String> queryParameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        for (String parameter : query.split("&")) {
            if (parameter.isEmpty()) {
                continue;
            }
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            String value = separator < 0 ? "" : parameter.substring(separator + 1);
            parameters.put(name.toLowerCase(Locale.ROOT), value);
        }
        return parameters;
    }

    /**
     * {@code value} URL-decoded as UTF-8, as CXF's {@code WSDLGetUtils} decoded the {@code xsd} value before looking
     * its schema up, or {@code null} when there is no value or it holds a malformed escape such as {@code %zz}, which
     * therefore selects no schema.
     */
    private static String decodedQueryValue(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            return null;
        }
    }

    private Published matchingRegistration(String path) {
        for (Published candidate : published) {
            for (String pattern : candidate.registration().patterns()) {
                if (pathMatcher.match(pattern, path)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Parses {@code source}, points every SOAP address at {@code requestUrl} and every reference whose location is a
     * key of the closure at {@code requestUrl?xsd=<location>}, then serializes the result.
     */
    private static byte[] rewrite(byte[] source, Set<String> closureKeys, String requestUrl) {
        Document document = parse(source, null);
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (isSoapAddress(element)) {
                element.setAttributeNS(null, LOCATION, requestUrl);
            }
        }
        for (Reference reference : references(document)) {
            if (closureKeys.contains(reference.location())) {
                reference.element().setAttributeNS(null, reference.attribute(),
                        requestUrl + "?" + XSD_QUERY_PREFIX + encodeQueryValue(reference.location()));
            }
        }
        return serialize(document);
    }

    private static boolean isSoapAddress(Element element) {
        String namespace = element.getNamespaceURI();
        return ADDRESS.equals(element.getLocalName())
                && (SOAP11_BINDING_NS.equals(namespace) || SOAP12_BINDING_NS.equals(namespace));
    }

    /** Every resolvable reference of {@code document} in document order: relative, non-blank locations only. */
    private static List<Reference> references(Document document) {
        List<Reference> found = new ArrayList<>();
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String attribute = referenceAttribute(element);
            if (attribute == null || !element.hasAttributeNS(null, attribute)) {
                continue;
            }
            String location = element.getAttributeNS(null, attribute);
            if (!location.isBlank() && !URI_SCHEME.matcher(location).find()) {
                found.add(new Reference(element, attribute, location));
            }
        }
        return found;
    }

    /**
     * The attribute that carries the location of a reference element, or {@code null} for any other element.
     * {@code xsd:redefine} pulls a schema in exactly as {@code xsd:include} does, so it is followed and rewritten too;
     * leaving it unrewritten would publish a schema whose redefined component cannot be fetched.
     */
    private static String referenceAttribute(Element element) {
        String namespace = element.getNamespaceURI();
        String localName = element.getLocalName();
        if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace)
                && ("import".equals(localName) || "include".equals(localName) || "redefine".equals(localName))) {
            return SCHEMA_LOCATION;
        }
        if (WSDL_NS.equals(namespace) && "import".equals(localName)) {
            return LOCATION;
        }
        return null;
    }

    /**
     * Resolves {@code location} relative to {@code referrer}, falling back to the classpath root.
     * The fallback uses the referrer's own class loader when the referrer is a classpath resource, so a WSDL
     * registered from a specific class loader finds its root-relative imports in that same loader rather than in
     * the thread context class loader; any other referrer falls back to the default class loader.
     */
    private static Resource resolve(Resource referrer, String location) {
        try {
            Resource relative = referrer.createRelative(location);
            if (relative.exists()) {
                return relative;
            }
        } catch (IOException ex) {
            log.debug("{} cannot resolve '{}' relative to itself: {}", safeResource(referrer), safeLocation(location),
                    failure(ex));
        }
        ClassLoader loader = referrer instanceof ClassPathResource classPathReferrer
                ? classPathReferrer.getClassLoader() : null;
        Resource fromRoot = new ClassPathResource(location, loader);
        if (fromRoot.exists()) {
            return fromRoot;
        }
        throw new IllegalStateException("Schema location '" + safeLocation(location) + "' referenced by "
                + safeResource(referrer) + " exists neither relative to it nor at the classpath root");
    }

    /** The URL identifying {@code resource}; resources without one are identified by their description. */
    private static String identity(Resource resource) {
        try {
            return resource.getURL().toExternalForm();
        } catch (IOException ex) {
            return "description:" + resource.getDescription();
        }
    }

    /**
     * The credential-free name of {@code resource} for log lines and exception messages. A classpath resource keeps
     * the form operators know, {@code class path resource [<path>]}; any other resource is named
     * {@code URL [<url>]}. Both are reduced by {@link #safeLocation(String)}. A resource without a URL is named by its
     * type alone, because its description is free text that may hold anything.
     */
    private static String safeResource(Resource resource) {
        if (resource instanceof ClassPathResource classPathResource) {
            return "class path resource [" + safeLocation(classPathResource.getPath()) + "]";
        }
        try {
            return "URL [" + safeLocation(resource.getURL().toExternalForm()) + "]";
        } catch (IOException ex) {
            return resource.getClass().getName();
        }
    }

    /**
     * {@code location} as diagnostic text: URL user information (nested URLs included) removed, everything from the
     * first {@code ?} or {@code #} omitted, and the rest escaped and bounded by {@link #escapedAndBounded(String)}.
     * User information is removed first, so a password holding {@code ?} or {@code #} cannot survive the cut.
     */
    private static String safeLocation(String location) {
        String withoutUserInfo = USER_INFO.matcher(location).replaceAll("//");
        int end = withoutUserInfo.length();
        int query = withoutUserInfo.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int fragment = withoutUserInfo.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return escapedAndBounded(withoutUserInfo.substring(0, end));
    }

    /**
     * Escapes every character that could break or forge a log line, so the text always stays on one line, and cuts
     * the result at {@link #DIAGNOSTIC_LIMIT} characters, never inside an escape or a surrogate pair.
     */
    private static String escapedAndBounded(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), DIAGNOSTIC_LIMIT) + TRUNCATED.length());
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            String escaped = escape(codePoint);
            if (out.length() + escaped.length() > DIAGNOSTIC_LIMIT) {
                return out.append(TRUNCATED).toString();
            }
            out.append(escaped);
            index += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /**
     * {@code \n}, {@code \r} and {@code \t} by name, a backslash doubled so an escape cannot be forged, every other
     * control, line or paragraph separator, format character and unpaired surrogate as Java-style Unicode escapes of
     * its UTF-16 units, and any other code point unchanged.
     */
    private static String escape(int codePoint) {
        String named = switch (codePoint) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\\' -> "\\\\";
            default -> null;
        };
        if (named != null) {
            return named;
        }
        int type = Character.getType(codePoint);
        if (!Character.isISOControl(codePoint) && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR && type != Character.FORMAT && type != Character.SURROGATE) {
            return new String(Character.toChars(codePoint));
        }
        StringBuilder escaped = new StringBuilder(12);
        for (char unit : Character.toChars(codePoint)) {
            escaped.append('\\').append('u').append(HEX[(unit >> 12) & 0x0F]).append(HEX[(unit >> 8) & 0x0F])
                    .append(HEX[(unit >> 4) & 0x0F]).append(HEX[unit & 0x0F]);
        }
        return escaped.toString();
    }

    /**
     * Safe metadata of a failure: its type and, for a parse error, its line and column. The failure's message is
     * never used, because parser and I/O messages can quote document content or a full URL.
     */
    private static String failure(Exception ex) {
        if (ex instanceof SAXParseException parseError) {
            return ex.getClass().getName() + " at line " + parseError.getLineNumber() + ", column "
                    + parseError.getColumnNumber();
        }
        return ex.getClass().getName();
    }

    private static byte[] read(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException(safeResource(resource) + " does not exist");
        }
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException ex) {
            // Not attached as the cause: its message can quote the full URL, user information and query included.
            throw new IllegalStateException("Cannot read " + safeResource(resource) + ": " + failure(ex));
        }
    }

    /**
     * Percent-encodes the UTF-8 bytes of {@code value} that cannot stand literally in a query value, so the URL
     * decoding {@link #select} applies to an {@code xsd} value returns the location exactly as written; ordinary
     * locations (letters, digits, {@code / . - _}) pass through unchanged.
     */
    private static String encodeQueryValue(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean literal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || (c < 0x80 && QUERY_SAFE_PUNCTUATION.indexOf(c) >= 0);
            if (literal) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return encoded.toString();
    }

    /**
     * Parses with the hardened factory. {@code resource} names the document, in its credential-free form, in warnings
     * and failure messages; {@code null} marks a re-parse of bytes that were already parsed successfully at
     * registration.
     */
    private static Document parse(byte[] bytes, Resource resource) {
        String description = resource == null ? "cached metadata document" : safeResource(resource);
        try {
            DocumentBuilder builder = newDocumentBuilder();
            builder.setErrorHandler(new FailingErrorHandler(description));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (SAXException | IOException ex) {
            throw new IllegalStateException("Cannot parse " + description + ": " + failure(ex), ex);
        }
    }

    private static DocumentBuilder newDocumentBuilder() {
        try {
            // The JDK's built-in implementation, so the hardening features below are always recognised.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("Cannot configure a secure XML parser", ex);
        }
    }

    /**
     * Serializes without indentation, keeping the document's own whitespace. {@code setXmlStandalone(true)} keeps
     * {@code standalone="no"} out of the declaration.
     */
    private static byte[] serialize(Document document) {
        document.setXmlStandalone(true);
        try {
            TransformerFactory factory = TransformerFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerConfigurationException ex) {
            throw new IllegalStateException("Cannot configure a secure XML serializer", ex);
        } catch (TransformerException ex) {
            throw new IllegalStateException("Cannot serialize metadata document", ex);
        }
    }

    /**
     * Turns every parser error into an exception instead of the default handler's report on standard error, and logs
     * warnings by position and type only: a warning's message, public ID and system ID can quote document content.
     */
    private static final class FailingErrorHandler implements ErrorHandler {

        /** The parsed document's credential-free name. */
        private final String description;

        private FailingErrorHandler(String description) {
            this.description = description;
        }

        @Override
        public void warning(SAXParseException exception) {
            log.warn("XML warning in {}: {}", description, failure(exception));
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
