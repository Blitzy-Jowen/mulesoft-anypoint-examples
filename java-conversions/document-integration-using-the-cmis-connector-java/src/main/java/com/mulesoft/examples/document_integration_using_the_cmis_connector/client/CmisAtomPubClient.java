/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.config.CmisProperties;

/**
 * CMIS 1.1 AtomPub adapter for the configured repository (OpenKM in the original example).
 *
 * <p>Replaces the connector operation of the original flow {@code CMIS}
 * ({@code cmis-document-integration.xml}):
 * {@code <cmis:create-document-by-path-from-content config-ref="openkm" filename="pic#[System.currentTimeMillis()].jpg"
 * folderPath="/okm:root/" mimeType="image/jpg" objectType="cmis:document" versioningState="NONE"/>}, run by
 * {@code mule-module-cmis} 2.0.0 over Apache Chemistry OpenCMIS with the AtomPub binding. The same wire exchange
 * is made here directly: the service document is read for the repository's {@code objectbypath} URI template,
 * the folder entry is fetched by path for its children feed, and an Atom entry carrying the Base64 content and
 * the {@code cmis:objectTypeId} and {@code cmis:name} properties is posted to that feed. The new document's
 * {@code cmis:objectId} is returned unchanged.
 *
 * <p>OpenCMIS is not used: its client bindings declare a compile dependency on
 * {@code org.apache.cxf:cxf-rt-frontend-jaxws}, which the project's JAX-WS/CXF dependency ban rejects.
 *
 * <p>Repository URL, repository ID, service account and timeouts come only from {@link CmisProperties}. Every
 * request authenticates with HTTP Basic as that service account, so both {@code cmis.username} and
 * {@code cmis.password} are required: when either is blank, {@link #createDocumentByPath} fails with an
 * {@link IllegalStateException} naming the key before any request is made. Construction accepts blank values, so
 * the application starts with the credentials unset. HTTP failures propagate unchanged as Spring's
 * {@code RestClientResponseException} subtypes (4xx/5xx) or {@code ResourceAccessException} (transport); missing
 * protocol elements and unreadable XML raise {@link IllegalStateException}. No call is retried. The class holds no
 * mutable state and is safe to share.
 */
@Component
public class CmisAtomPubClient {

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String APP = "http://www.w3.org/2007/app";
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    private static final String SERVICE_DOCUMENT_TYPE = "application/atomsvc+xml";
    private static final String ENTRY_TYPE = "application/atom+xml;type=entry";
    private static final MediaType ENTRY_MEDIA_TYPE = MediaType.parseMediaType(ENTRY_TYPE);
    private static final String FEED_TYPE = "application/atom+xml;type=feed";

    /** The fixed {@code atom:id} OpenCMIS writes into every entry it posts; servers assign the real ID. */
    private static final String PLACEHOLDER_ENTRY_ID = "urn:uuid:00000000-0000-0000-0000-00000000000";

    /** Mule's default response timeout, used where a timeout property is unset. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Fails the parse on errors and fatal errors instead of the JDK default handler, which also prints each one
     * to {@code System.err}; warnings leave the parsed document intact and are not reported.
     */
    private static final ErrorHandler FAIL_ON_ERROR = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Intentionally silent: a warning never changes the document that is returned.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Configuration key of the service-account user name. */
    private static final String USERNAME_KEY = "cmis.username";

    /** Configuration key of the service-account password. */
    private static final String PASSWORD_KEY = "cmis.password";

    private final CmisProperties properties;

    private final RestClient restClient;

    /** First unset service-account key, {@code cmis.username} before {@code cmis.password}; null if both are set. */
    private final String missingCredentialKey;

    /**
     * Builds the HTTP client from the connection settings; makes no request. An unset service account is accepted
     * here, so the application starts without it, and is rejected by {@link #createDocumentByPath} instead.
     *
     * @param properties repository URL, repository ID, service account and timeouts
     */
    public CmisAtomPubClient(CmisProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(orDefault(properties.getConnectTimeout()));
        requestFactory.setReadTimeout(orDefault(properties.getReadTimeout()));
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        String username = properties.getUsername();
        String password = properties.getPassword();
        if (!StringUtils.hasText(username)) {
            this.missingCredentialKey = USERNAME_KEY;
        } else if (!StringUtils.hasText(password)) {
            this.missingCredentialKey = PASSWORD_KEY;
        } else {
            this.missingCredentialKey = null;
            builder.defaultHeaders(headers -> headers.setBasicAuth(username, password));
        }
        this.restClient = builder.build();
    }

    /**
     * Creates a document from content in the folder at {@code folderPath}, as OpenCMIS
     * {@code createDocument} over AtomPub does.
     *
     * @param folderPath      repository path of the target folder, for example {@code /okm:root/}
     * @param name            document name, written as {@code atom:title} and {@code cmis:name}
     * @param mimeType        media type of the content
     * @param objectTypeId    CMIS object type, for example {@code cmis:document}
     * @param versioningState CMIS versioning state ({@code NONE}, {@code MAJOR}, ...), or null to send none
     * @param content         document bytes
     * @return the {@code cmis:objectId} the repository assigned to the new document
     * @throws IllegalStateException when {@code cmis.username} or {@code cmis.password} is not configured, before
     *                               any request is made
     */
    public String createDocumentByPath(String folderPath, String name, String mimeType, String objectTypeId,
            String versioningState, byte[] content) {
        Objects.requireNonNull(folderPath, "folderPath");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(objectTypeId, "objectTypeId");
        Objects.requireNonNull(content, "content");
        requireServiceAccount();

        String repositoryId = properties.getRepositoryId();
        Document serviceDocument = get(serviceDocumentUri(), SERVICE_DOCUMENT_TYPE, "service document");
        String template = objectByPathTemplate(serviceDocument, repositoryId);

        URI folderEntryUri = URI.create(expandObjectByPathTemplate(template, folderPath));
        Document folderEntry = get(folderEntryUri, ENTRY_TYPE, "folder entry");
        URI createUri = withVersioningState(childrenFeedUri(folderEntry, folderEntryUri, folderPath), versioningState);

        byte[] created = restClient.post()
                .uri(createUri)
                .contentType(ENTRY_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT, ENTRY_TYPE)
                .body(entry(name, mimeType, objectTypeId, content).getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(byte[].class);
        return objectId(parse(created, "create-document"));
    }

    private static Duration orDefault(Duration timeout) {
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /** Fails the operation, naming the first unset service-account key, before any repository request. */
    private void requireServiceAccount() {
        if (USERNAME_KEY.equals(missingCredentialKey)) {
            throw new IllegalStateException("CMIS service-account user name (cmis.username) is not configured");
        }
        if (PASSWORD_KEY.equals(missingCredentialKey)) {
            throw new IllegalStateException("CMIS service-account password (cmis.password) is not configured");
        }
    }

    private URI serviceDocumentUri() {
        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("CMIS service document URL (cmis.base-url) is not configured");
        }
        return URI.create(baseUrl.trim());
    }

    /** URIs are always passed as {@link URI}: a String would be re-expanded and re-encoded by RestClient. */
    private Document get(URI uri, String accept, String what) {
        byte[] body = restClient.get()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, accept)
                .retrieve()
                .body(byte[].class);
        return parse(body, what);
    }

    private static String objectByPathTemplate(Document serviceDocument, String repositoryId) {
        Element service = serviceDocument.getDocumentElement();
        if (isElement(service, APP, "service")) {
            for (Element workspace : children(service, APP, "workspace")) {
                if (servesRepository(workspace, repositoryId)) {
                    for (Element uriTemplate : children(workspace, CMISRA, "uritemplate")) {
                        if ("objectbypath".equals(childText(uriTemplate, CMISRA, "type"))) {
                            String template = childText(uriTemplate, CMISRA, "template");
                            if (StringUtils.hasLength(template)) {
                                return template;
                            }
                        }
                    }
                    throw new IllegalStateException(
                            "CMIS workspace '" + repositoryId + "' has no 'objectbypath' URI template");
                }
            }
        }
        throw new IllegalStateException(
                "CMIS service document has no workspace for repository '" + repositoryId + "'");
    }

    private static boolean servesRepository(Element workspace, String repositoryId) {
        for (Element repositoryInfo : children(workspace, CMISRA, "repositoryInfo")) {
            for (Element id : children(repositoryInfo, CMIS, "repositoryId")) {
                if (id.getTextContent().trim().equals(repositoryId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Expands the template as OpenCMIS {@code UrlBuilder.expandUriTemplate} does: {@code {path}} becomes the
     * form-encoded folder path, every other variable is unset and becomes empty, and an unclosed brace is
     * copied as it stands.
     */
    private static String expandObjectByPathTemplate(String template, String folderPath) {
        StringBuilder expanded = new StringBuilder(template.length() + 3 * folderPath.length());
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            int close = current == '{' ? template.indexOf('}', index + 1) : -1;
            if (close < 0) {
                expanded.append(current);
                index++;
            } else {
                if ("path".equals(template.substring(index + 1, close))) {
                    expanded.append(URLEncoder.encode(folderPath, StandardCharsets.UTF_8));
                }
                index = close + 1;
            }
        }
        return expanded.toString();
    }

    /** The children feed ({@code rel=down}, Atom feed), not the {@code application/cmistree+xml} descendants. */
    private static URI childrenFeedUri(Document folderEntry, URI folderEntryUri, String folderPath) {
        Element entry = folderEntry.getDocumentElement();
        if (isElement(entry, ATOM, "entry")) {
            for (Element link : children(entry, ATOM, "link")) {
                String href = link.getAttribute("href").trim();
                if ("down".equals(link.getAttribute("rel").trim())
                        && FEED_TYPE.equalsIgnoreCase(StringUtils.trimAllWhitespace(link.getAttribute("type")))
                        && !href.isEmpty()) {
                    return folderEntryUri.resolve(href);
                }
            }
        }
        throw new IllegalStateException("CMIS folder entry for path '" + folderPath
                + "' has no children link (rel=down, type=" + FEED_TYPE + ")");
    }

    /** A null state adds no parameter, as OpenCMIS {@code UrlBuilder.addParameter} skips null values. */
    private static URI withVersioningState(URI childrenUri, String versioningState) {
        if (versioningState == null) {
            return childrenUri;
        }
        String separator = childrenUri.getRawQuery() == null ? "?" : "&";
        return URI.create(childrenUri + separator + "versioningState="
                + URLEncoder.encode(versioningState.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8));
    }

    private static String objectId(Document createdEntry) {
        Element entry = createdEntry.getDocumentElement();
        if (isElement(entry, ATOM, "entry")) {
            for (Element object : children(entry, CMISRA, "object")) {
                for (Element objectProperties : children(object, CMIS, "properties")) {
                    for (Element property : children(objectProperties, CMIS, "propertyId")) {
                        if ("cmis:objectId".equals(property.getAttribute("propertyDefinitionId"))) {
                            String value = childText(property, CMIS, "value");
                            if (StringUtils.hasLength(value)) {
                                return value;
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("CMIS create-document response has no cmis:objectId");
    }

    /**
     * Parses a response body without DTDs, external entities or XInclude; the XML declaration, not the
     * Content-Type, decides the charset.
     */
    private static Document parse(byte[] body, String what) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("CMIS " + what + " response has no body");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(FAIL_ON_ERROR);
            return builder.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("CMIS " + what + " response is not well-formed XML", e);
        }
    }

    /** The Atom entry OpenCMIS {@code AtomEntryWriter} posts for a document with content, in its child order. */
    private static String entry(String name, String mimeType, String objectTypeId, byte[] content) {
        String escapedName = escapeXml(name);
        String base64 = Base64.getEncoder().encodeToString(content);
        return new StringBuilder(base64.length() + 1024)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<atom:entry xmlns:atom=\"").append(ATOM)
                .append("\" xmlns:cmis=\"").append(CMIS)
                .append("\" xmlns:cmisra=\"").append(CMISRA).append("\">")
                .append("<atom:id>").append(PLACEHOLDER_ENTRY_ID).append("</atom:id>")
                .append("<atom:title>").append(escapedName).append("</atom:title>")
                .append("<atom:updated>")
                .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
                .append("</atom:updated>")
                .append("<cmisra:content>")
                .append("<cmisra:mediatype>").append(escapeXml(mimeType)).append("</cmisra:mediatype>")
                .append("<cmisra:base64>").append(base64).append("</cmisra:base64>")
                .append("</cmisra:content>")
                .append("<cmisra:object><cmis:properties>")
                .append("<cmis:propertyId propertyDefinitionId=\"cmis:objectTypeId\"><cmis:value>")
                .append(escapeXml(objectTypeId)).append("</cmis:value></cmis:propertyId>")
                .append("<cmis:propertyString propertyDefinitionId=\"cmis:name\"><cmis:value>")
                .append(escapedName).append("</cmis:value></cmis:propertyString>")
                .append("</cmis:properties></cmisra:object>")
                .append("</atom:entry>")
                .toString();
    }

    private static String escapeXml(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static boolean isElement(Element element, String namespace, String localName) {
        return element != null && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    /** Direct child elements matched by namespace URI and local name; servers choose their own prefixes. */
    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && isElement(element, namespace, localName)) {
                matches.add(element);
            }
        }
        return matches;
    }

    /** Trimmed text of the first matching direct child, or null when there is none. */
    private static String childText(Element parent, String namespace, String localName) {
        List<Element> matches = children(parent, namespace, localName);
        return matches.isEmpty() ? null : matches.get(0).getTextContent().trim();
    }
}
