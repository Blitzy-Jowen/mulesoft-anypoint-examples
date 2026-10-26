package com.mulesoft.examples.legacy_modernization.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li>Only {@code GET} is handled. A request whose path matches no registration, whose query is neither
 *       {@code wsdl} (any case) nor {@code xsd=<registered key>}, or that uses another method continues down the
 *       filter chain untouched.</li>
 *   <li>A served request is logged at DEBUG with registration-owned identity only (the document kind, its registered
 *       WSDL or schema key and resource, and the registration's patterns), never the request's query, parameters,
 *       path or URL, which carry caller-supplied data.</li>
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

    private static final String WSDL_QUERY = "wsdl";
    private static final String XSD_PARAMETER = "xsd";
    private static final String XSD_QUERY_PREFIX = XSD_PARAMETER + "=";
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final String QUERY_SAFE_PUNCTUATION = "-._~/:@!$'()*,;";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

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
                    throw new IllegalStateException("Schema location '" + reference.location() + "' in "
                            + current.resource().getDescription() + " resolves to " + resolved.getDescription()
                            + ", but the same location already denotes " + previous.getDescription()
                            + " in the closure of " + wsdl.getDescription());
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
        log.info("Publishing {} for {} with {} referenced schema(s)", wsdl.getDescription(),
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
     * The description is built from the registration: its WSDL resource, or the closure's own schema key and resource.
     */
    private Selection select(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null) {
            return null;
        }
        Published target = matchingRegistration(pathWithinApplication(request));
        if (target == null) {
            return null;
        }
        Registration registration = target.registration();
        if (WSDL_QUERY.equalsIgnoreCase(query)) {
            return new Selection(target, target.wsdl(), "WSDL " + registration.wsdl().getDescription());
        }
        if (query.startsWith(XSD_QUERY_PREFIX)) {
            String key = request.getParameter(XSD_PARAMETER);
            for (Map.Entry<String, Resource> schema : registration.schemas().entrySet()) {
                if (schema.getKey().equals(key)) {
                    return new Selection(target, target.schemas().get(schema.getKey()),
                            "schema '" + schema.getKey() + "' (" + schema.getValue().getDescription() + ")");
                }
            }
        }
        return null;
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
            log.debug("{} cannot resolve '{}' relative to itself: {}", referrer.getDescription(), location,
                    ex.getMessage());
        }
        ClassLoader loader = referrer instanceof ClassPathResource classPathReferrer
                ? classPathReferrer.getClassLoader() : null;
        Resource fromRoot = new ClassPathResource(location, loader);
        if (fromRoot.exists()) {
            return fromRoot;
        }
        throw new IllegalStateException("Schema location '" + location + "' referenced by "
                + referrer.getDescription() + " exists neither relative to it nor at the classpath root");
    }

    /** The URL identifying {@code resource}; resources without one are identified by their description. */
    private static String identity(Resource resource) {
        try {
            return resource.getURL().toExternalForm();
        } catch (IOException ex) {
            return "description:" + resource.getDescription();
        }
    }

    private static byte[] read(Resource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException(resource.getDescription() + " does not exist");
        }
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + resource.getDescription(), ex);
        }
    }

    /**
     * Percent-encodes the UTF-8 bytes of {@code value} that cannot stand literally in a query value, so the
     * container's parameter decoding returns the location exactly as written; ordinary locations (letters, digits,
     * {@code / . - _}) pass through unchanged.
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
     * Parses with the hardened factory. {@code resource} names the document in failure messages; {@code null} marks
     * a re-parse of bytes that were already parsed successfully at registration.
     */
    private static Document parse(byte[] bytes, Resource resource) {
        String description = resource == null ? "cached metadata document" : resource.getDescription();
        try {
            DocumentBuilder builder = newDocumentBuilder();
            builder.setErrorHandler(new FailingErrorHandler(description));
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (SAXException | IOException ex) {
            throw new IllegalStateException("Cannot parse " + description + ": " + ex.getMessage(), ex);
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
     * warnings.
     */
    private static final class FailingErrorHandler implements ErrorHandler {

        private final String description;

        private FailingErrorHandler(String description) {
            this.description = description;
        }

        @Override
        public void warning(SAXParseException exception) {
            log.warn("XML warning in {}: {}", description, exception.getMessage());
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
