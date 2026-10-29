/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;

import org.springframework.stereotype.Component;

/**
 * Writes element trees as the bytes DataWeave 1.0's {@code %output application/xml} writer produced
 * (shared component SC-4). SC-4 reference implementation (AAP 0.4.4); copies change only the package line.
 * It is copied into {@code xml-only-soap-webservice-java}, {@code upload-to-ftp-after-converting-json-to-xml-java},
 * {@code get-customer-list-from-netsuite-java}, {@code salesforce-data-retrieval-java},
 * {@code importing-an-email-attachment-using-the-IMAP-connector-java},
 * {@code importing-an-email-attachment-using-the-POP3-connector-java} and
 * {@code service-orchestration-and-choice-routing-java}, together with its test; do not add project-specific
 * logic.
 *
 * <p>The mappers that reimplement the original {@code %dw 1.0} scripts build their result either with the
 * {@link Element} builder (for qualified names, attributes and repeated keys) or from a plain value tree with
 * {@link #fromObject(String, String, String, Object)}, and serialize it here. The output is assembled by hand
 * rather than through a DOM serializer, because no JDK serializer emits DataWeave's single-quoted declaration
 * and tab layout byte for byte.
 *
 * <p>Layout, taken from DataWeave-produced files such as
 * {@code service-orchestration-and-choice-routing/src/test/resources/sample_data/summary_1.xml}:
 * <ul>
 *   <li>{@link #DECLARATION}, a line feed, then the root element at depth 0.</li>
 *   <li>Every element starts a new line indented with one tab per depth.</li>
 *   <li>An element with text is one line, {@code <q>text</q>}; empty text gives {@code <q></q>}.</li>
 *   <li>An element with neither text nor children is {@code <q/>}.</li>
 *   <li>An element with children writes its start tag, each child at depth + 1, then its end tag on its own
 *       line at its own depth. An element cannot hold both text and children.</li>
 *   <li>Line ends are LF only.</li>
 * </ul>
 *
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <ns0:OrderTshirt xmlns:ns0="http://mulesoft.org/tshirt-service">
 *     <email>customerID@gmail.com</email>
 *     <size>L</size>
 * </ns0:OrderTshirt>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs)
 *
 * <p>Terminal line feed: nothing follows the root end tag, for every caller alike (SOAP bodies, HTTP
 * responses, uploaded and written files), because DataWeave 1.0 writes the same bytes to each of them.
 * Recorded DataWeave output shows it: {@code salesforce-data-retrieval/src/test/resources/index.html} is
 * {@code salesforce-data-retrieval/src/main/resources/retrieval/index.html} rendered with
 * {@code #[message.payload]} (line 17) replaced by the DataWeave XML payload, and that payload's {@code </div>}
 * (line 311) is followed directly by the template's {@code </select>} line (line 312), with no empty line
 * between; {@code service-orchestration-and-choice-routing/src/test/resources/sample_data/summary_1.xml}
 * (490 bytes) ends at {@code </summary>} (line 22). The final line feed of
 * {@code upload-to-ftp-after-converting-json-to-xml/src/test/resources/reply.xml} is not writer output: that
 * file was edited by hand (its street values lost their inner spaces), and the original test compares it with
 * all whitespace removed (line 98 of the same example's
 * {@code src/test/java/org/mule/examples/UploadToFtpAfterConvertingJsonToXmlIT.java}), so the writer does not
 * follow it. Output recorded from the original runtime that differs from this writer is fixed in this writer,
 * never by editing the recorded output.
 *
 * <p>Start tags hold the qualified name ({@code prefix:name}, or {@code name} without a prefix), then the
 * namespace declarations, then the attributes in insertion order. An element declares its explicit
 * {@link Element#namespace(String, String)} entries in insertion order, followed by its own binding, and
 * skips every binding an ancestor already declared; a declaration's scope ends with its element, so
 * siblings each declare it again.
 *
 * <p>Names are checked when they are given, by {@link #element(String, String, String)},
 * {@link Element#namespace(String, String)} and {@link Element#attribute(String, String)}, and for a map key of
 * {@link #fromObject(String, String, String, Object)} when it becomes an element, so a name taken from caller data is
 * refused before anything is written and never becomes markup. The rules are those of XML 1.0 (Fifth Edition) and
 * Namespaces in XML 1.0, read by code point, so a supplementary character counts once and an unpaired surrogate is
 * never a name character:
 * <ul>
 *   <li>An element's local name is an {@code NCName} (an XML {@code Name} without {@code ':'}), optionally followed
 *       by U+0020 spaces and nothing else. Those spaces are written as they are, in the start and the end tag, where
 *       XML allows white space before {@code >} and before an attribute, so the element name a script spelled with
 *       trailing spaces ({@code "Bill "}) stays the one it wrote: {@code <ns1:Bill  xmlns:ns1="...">} ...
 *       {@code </ns1:Bill >}. Any other space, tab, line feed or carriage return is refused.</li>
 *   <li>An element's prefix and a {@link Element#namespace(String, String)} prefix are {@code null}, empty or an
 *       {@code NCName}, never {@code xmlns}.</li>
 *   <li>An attribute name is a {@code QName} ({@code NCName} or {@code NCName:NCName}), never {@code xmlns} or
 *       {@code xmlns:*}: namespace declarations are added with {@link Element#namespace(String, String)}. An
 *       attribute's prefix needs no declaration on the element ({@code xsi:type}, {@code xml:lang}).</li>
 *   <li>Bindings, an element's own and each declaration: {@code xml} is bound only to
 *       {@code http://www.w3.org/XML/1998/namespace}, that namespace only to {@code xml}, and nothing to
 *       {@code http://www.w3.org/2000/xmlns/}.</li>
 * </ul>
 * A refused name raises {@link IllegalArgumentException} whose message names the first character that breaks the
 * rule by its code point and its offset in code points ({@code Invalid XML element name: character U+003E at offset 1
 * is not allowed}), never the name itself, because the name may come from a request and the message may reach a
 * SOAP fault or a JSON error body. How DataWeave 1.0 treated a dynamic key that is not an XML name is not documented;
 * it is a Tier 2A verification point.
 *
 * <p>Text escapes {@code &}, {@code <} and {@code >}; attribute values and
 * namespace URIs also escape {@code "}. A character XML 1.0 does not allow in a text, attribute value or
 * namespace URI (a C0 control other than tab, line feed and carriage return, an unpaired surrogate, U+FFFE or
 * U+FFFF) makes {@link #write(Element)} and {@link #writeBytes(Element)} throw {@link IllegalStateException}
 * naming its code point, as DataWeave's StAX-based writer fails on it, so no ill-formed document is written; the
 * exact DataWeave failure text is a Tier 2A verification point. Every other character, non-ASCII included, is
 * written as it is and encoded in UTF-8 by {@link #writeBytes(Element)}.
 *
 * <p>The writer is stateless and thread-safe. {@link Element} is a mutable builder meant to be filled by one
 * thread and then written.
 */
@Component
public class DwXmlWriter {

    /** The XML declaration DataWeave 1.0 writes, with single-quoted pseudo-attributes. */
    public static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    private static final String DEFAULT_PREFIX = "";

    /** The roles a refused name is reported under, as in {@code Invalid XML element name: ...}. */
    private static final String ELEMENT_NAME = "element name";
    private static final String ELEMENT_PREFIX = "element prefix";
    private static final String NAMESPACE_PREFIX = "namespace prefix";
    private static final String ATTRIBUTE_NAME = "attribute name";

    /** Creates a writer; it holds no state, so one instance serves every caller. */
    public DwXmlWriter() {
    }

    /**
     * Renders an element tree in DataWeave 1.0 XML layout.
     *
     * @param root the document element
     * @return the declaration and the document, without a trailing line feed
     * @throws IllegalArgumentException when {@code root} is {@code null}
     * @throws IllegalStateException when an element holds both text and children, a prefixed element
     *         has no namespace URI and its prefix is not bound by an ancestor, or a text, attribute value or
     *         namespace URI holds a character XML 1.0 does not allow; the message names the first such character
     *         by its code point ({@code U+0001}) and where it occurs, never the character itself
     */
    public String write(Element root) {
        if (root == null) {
            throw new IllegalArgumentException("XML output requires a root element");
        }
        StringBuilder out = new StringBuilder(DECLARATION).append('\n');
        writeElement(root, 0, Map.of(), out);
        return out.toString();
    }

    /**
     * Renders an element tree in DataWeave 1.0 XML layout, encoded in UTF-8.
     *
     * @param root the document element
     * @return the UTF-8 bytes of {@link #write(Element)}
     * @throws IllegalArgumentException when {@code root} is {@code null}
     * @throws IllegalStateException under the conditions of {@link #write(Element)}
     */
    public byte[] writeBytes(Element root) {
        return write(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds an unqualified root element from a value tree, as a script {@code name: value} does.
     *
     * @param name the root element name
     * @param value the root content, converted as {@link #fromObject(String, String, String, Object)} states
     * @return the root element
     * @throws IllegalArgumentException when {@code value} is an {@link Iterable} or an array, or a name, the root's
     *         or that of a map key that becomes an element, is empty or not an element name by the rules of the class
     *         description
     */
    public Element fromObject(String name, Object value) {
        return fromObject(null, null, name, value);
    }

    /**
     * Builds a root element from a value tree, as a script {@code prefix#name: value} with
     * {@code %namespace prefix uri} does. The root carries the given prefix and URI; every descendant is
     * unqualified, as a script key written without {@code ns#}.
     *
     * <ul>
     *   <li>A {@link Map} becomes one child per entry of {@code entrySet()}, in iteration order, named
     *       {@code String.valueOf(key)}. Repeated keys in the entry set produce repeated children. Every key that
     *       becomes an element is checked as an element local name when that element is created, before anything is
     *       written; a key whose value is an empty {@link Iterable} or array gives no element and is not checked.</li>
     *   <li>An {@link Iterable} or array (object or primitive) under a key repeats that key's element once per
     *       item, each item converted by these rules; a nested iterable repeats the same key again.</li>
     *   <li>{@code null} becomes an element with no text and no children.</li>
     *   <li>Any other value becomes the element's text, rendered as {@link Element#text(Object)} states.</li>
     * </ul>
     *
     * @param prefix the root prefix; {@code null} or empty for none
     * @param uri the root namespace URI; {@code null} or empty for none
     * @param name the root element name
     * @param value the root content
     * @return the root element
     * @throws IllegalArgumentException when {@code value} is an {@link Iterable} or an array; when the root's prefix,
     *         URI and name are refused as {@link #element(String, String, String)} states; or when a map key that
     *         becomes an element is empty or not an element local name
     */
    public Element fromObject(String prefix, String uri, String name, Object value) {
        if (isRepeated(value)) {
            throw new IllegalArgumentException("XML output requires a single root element");
        }
        Element root = element(prefix, uri, name);
        fill(root, value);
        return root;
    }

    /**
     * Creates an unqualified element.
     *
     * @param name the local name: an {@code NCName}, optionally followed by U+0020 spaces
     * @return the element
     * @throws IllegalArgumentException when {@code name} is {@code null}, empty or not such a name; the message
     *         names the first offending character by code point and offset, never the name
     */
    public static Element element(String name) {
        return new Element(null, null, name);
    }

    /**
     * Creates an element with a prefix and namespace URI.
     *
     * @param prefix the prefix; {@code null} or empty for the default namespace or none, otherwise an
     *        {@code NCName} other than {@code xmlns}
     * @param uri the namespace URI; {@code null} or empty when the element declares none
     * @param name the local name: an {@code NCName}, optionally followed by U+0020 spaces
     * @return the element
     * @throws IllegalArgumentException when {@code name} is {@code null}, empty or not such a name, when a non-empty
     *         {@code prefix} is not such a prefix, or when the binding of {@code prefix} to {@code uri} misuses
     *         {@code xml} or a reserved namespace; the message names the first offending
     *         character by code point and offset, or the rule broken, never the caller's name or URI
     */
    public static Element element(String prefix, String uri, String name) {
        return new Element(prefix, uri, name);
    }

    private static void fill(Element target, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                appendChildren(target, String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (value != null) {
            target.text(value);
        }
    }

    private static void appendChildren(Element parent, String name, Object value) {
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                appendChildren(parent, name, item);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendChildren(parent, name, Array.get(value, i));
            }
        } else {
            Element child = element(name);
            fill(child, value);
            parent.child(child);
        }
    }

    private static boolean isRepeated(Object value) {
        return value instanceof Iterable<?> || (value != null && value.getClass().isArray());
    }

    private static String render(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private static String normalizePrefix(String prefix) {
        return prefix == null ? DEFAULT_PREFIX : prefix;
    }

    /**
     * Returns {@code name} once it is known to be an element local name: an {@code NCName} followed by nothing or by
     * U+0020 spaces only.
     *
     * @throws IllegalArgumentException when {@code name} is {@code null}, empty or not such a name
     */
    private static String requireElementName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("An XML element name must not be null or empty");
        }
        int end = ncNameEnd(name, 0);
        int at = end;
        while (end > 0 && at < name.length() && name.charAt(at) == ' ') {
            at++;
        }
        if (at < name.length()) {
            // Without an NCName the first character is reported; after one, the character that ends it, which is
            // also the first space of a run that something other than spaces follows.
            throw invalidName(ELEMENT_NAME, name, end);
        }
        return name;
    }

    /**
     * Checks an element prefix or a namespace declaration's prefix: {@code null}, empty or an {@code NCName}, never
     * {@code xmlns}, which only declares namespaces.
     *
     * @param kind the name's role in the message, {@link #ELEMENT_PREFIX} or {@link #NAMESPACE_PREFIX}
     */
    private static void checkPrefix(String prefix, String kind) {
        if (prefix == null || prefix.isEmpty()) {
            return;
        }
        int end = ncNameEnd(prefix, 0);
        if (end < prefix.length()) {
            throw invalidName(kind, prefix, end);
        }
        if (XMLConstants.XMLNS_ATTRIBUTE.equals(prefix)) {
            throw new IllegalArgumentException(
                    "Invalid XML " + kind + ": the prefix reserved for namespace declarations is not allowed");
        }
    }

    /**
     * Checks an attribute name: a {@code QName}, an {@code NCName} or two joined by one {@code ':'}, that is not a
     * namespace declaration ({@code xmlns} or {@code xmlns:*}). Its prefix is not required to be bound.
     */
    private static void checkAttributeName(String name) {
        int end = ncNameEnd(name, 0);
        if (end < name.length()) {
            if (end == 0 || name.charAt(end) != ':') {
                throw invalidName(ATTRIBUTE_NAME, name, end);
            }
            int localEnd = ncNameEnd(name, end + 1);
            if (localEnd < name.length()) {
                throw invalidName(ATTRIBUTE_NAME, name, localEnd);
            }
            if (localEnd == end + 1) {
                // Nothing follows the ':', so the ':' itself cannot stand where it does.
                throw invalidName(ATTRIBUTE_NAME, name, end);
            }
        }
        if (XMLConstants.XMLNS_ATTRIBUTE.equals(name.substring(0, end))) {
            throw new IllegalArgumentException("Invalid XML " + ATTRIBUTE_NAME
                    + ": namespace declarations are added with namespace(prefix, uri), not as attributes");
        }
    }

    /**
     * Checks a binding of {@code prefix} ({@code ""} for the default namespace) to {@code uri} against Namespaces in
     * XML 1.0, section 3: {@code xml} is bound only to its namespace, that namespace only to {@code xml}, and the
     * namespace of namespace declarations to nothing. A {@code null} or empty {@code uri} binds nothing here.
     */
    private static void checkBinding(String prefix, String uri) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        boolean xmlPrefix = XMLConstants.XML_NS_PREFIX.equals(prefix);
        if (xmlPrefix != XMLConstants.XML_NS_URI.equals(uri)) {
            throw new IllegalArgumentException("Invalid XML namespace binding: " + (xmlPrefix
                    ? "the prefix 'xml' is bound only to " + XMLConstants.XML_NS_URI
                    : XMLConstants.XML_NS_URI + " is bound only to the prefix 'xml'"));
        }
        if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(uri)) {
            throw new IllegalArgumentException(
                    "Invalid XML namespace binding: " + XMLConstants.XMLNS_ATTRIBUTE_NS_URI + " cannot be bound");
        }
    }

    /**
     * The index at which the {@code NCName} starting at {@code from} ends: {@code from} itself when no
     * {@code NameStartChar} is there, otherwise the index of the first code point that is not a {@code NameChar}, or
     * the length of {@code value}. The value is read by code point, so a supplementary character counts as one
     * character and an unpaired surrogate matches no range.
     */
    private static int ncNameEnd(String value, int from) {
        if (from >= value.length() || !isNameStartChar(value.codePointAt(from))) {
            return from;
        }
        int at = from + Character.charCount(value.codePointAt(from));
        while (at < value.length()) {
            int codePoint = value.codePointAt(at);
            if (!isNameChar(codePoint)) {
                break;
            }
            at += Character.charCount(codePoint);
        }
        return at;
    }

    /** XML 1.0 (Fifth Edition) {@code NameStartChar} without {@code ':'}. */
    private static boolean isNameStartChar(int c) {
        return (c >= 'A' && c <= 'Z') || c == '_' || (c >= 'a' && c <= 'z')
                || (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || (c >= 0xF8 && c <= 0x2FF)
                || (c >= 0x370 && c <= 0x37D) || (c >= 0x37F && c <= 0x1FFF) || (c >= 0x200C && c <= 0x200D)
                || (c >= 0x2070 && c <= 0x218F) || (c >= 0x2C00 && c <= 0x2FEF) || (c >= 0x3001 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0xEFFFF);
    }

    /** XML 1.0 (Fifth Edition) {@code NameChar} without {@code ':'}. */
    private static boolean isNameChar(int c) {
        return isNameStartChar(c) || c == '-' || c == '.' || (c >= '0' && c <= '9') || c == 0xB7
                || (c >= 0x300 && c <= 0x36F) || (c >= 0x203F && c <= 0x2040);
    }

    /**
     * The failure for the code point at {@code index} of {@code name}, named by its value and its offset in code
     * points, so that the message, which may reach a SOAP fault or a JSON error body, never carries the name.
     *
     * @param kind the name's role, for example {@link #ELEMENT_NAME}
     */
    private static IllegalArgumentException invalidName(String kind, String name, int index) {
        return new IllegalArgumentException(String.format(
                "Invalid XML %s: character U+%04X at offset %d is not allowed",
                kind, name.codePointAt(index), name.codePointCount(0, index)));
    }

    private static void writeElement(Element element, int depth, Map<String, String> inScope, StringBuilder out) {
        String qualifiedName = element.qualifiedName();
        if (element.text != null && !element.children.isEmpty()) {
            throw new IllegalStateException("Element <" + qualifiedName
                    + "> holds both text and child elements; DataWeave XML output has no mixed content");
        }
        Map<String, String> declarations = declarationsOf(element, inScope);
        Map<String, String> scope = inScope;
        if (!declarations.isEmpty()) {
            scope = new HashMap<>(inScope);
            scope.putAll(declarations);
        }

        String indent = "\t".repeat(depth);
        out.append(indent).append('<').append(qualifiedName);
        for (Map.Entry<String, String> declaration : declarations.entrySet()) {
            String prefix = declaration.getKey();
            out.append(prefix.isEmpty() ? " xmlns" : " xmlns:" + prefix).append("=\"");
            int invalid = escape(declaration.getValue(), true, out);
            if (invalid >= 0) {
                throw invalidCharacter(declaration.getValue(), invalid, "the namespace URI of "
                        + (prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix) + " on element <" + qualifiedName + ">");
            }
            out.append('"');
        }
        for (Map.Entry<String, String> attribute : element.attributes.entrySet()) {
            out.append(' ').append(attribute.getKey()).append("=\"");
            int invalid = escape(attribute.getValue(), true, out);
            if (invalid >= 0) {
                throw invalidCharacter(attribute.getValue(), invalid,
                        "attribute '" + attribute.getKey() + "' of element <" + qualifiedName + ">");
            }
            out.append('"');
        }

        if (element.children.isEmpty()) {
            if (element.text == null) {
                out.append("/>");
            } else {
                out.append('>');
                int invalid = escape(element.text, false, out);
                if (invalid >= 0) {
                    throw invalidCharacter(element.text, invalid, "the text of element <" + qualifiedName + ">");
                }
                out.append("</").append(qualifiedName).append('>');
            }
            return;
        }
        out.append('>');
        for (Element child : element.children) {
            out.append('\n');
            writeElement(child, depth + 1, scope, out);
        }
        out.append('\n').append(indent).append("</").append(qualifiedName).append('>');
    }

    /**
     * The bindings an element writes: its explicit declarations not already in scope, then its own binding
     * when neither an ancestor nor an explicit declaration provides it.
     */
    private static Map<String, String> declarationsOf(Element element, Map<String, String> inScope) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (Map.Entry<String, String> namespace : element.namespaces.entrySet()) {
            if (!namespace.getValue().equals(inScope.get(namespace.getKey()))) {
                declarations.put(namespace.getKey(), namespace.getValue());
            }
        }

        String prefix = normalizePrefix(element.prefix);
        String uri = element.uri;
        if (uri == null || uri.isEmpty()) {
            if (!prefix.isEmpty() && !inScope.containsKey(prefix) && !element.namespaces.containsKey(prefix)) {
                throw new IllegalStateException("Prefix '" + prefix + "' of element <" + element.qualifiedName()
                        + "> is not bound to a namespace URI");
            }
            return declarations;
        }
        String explicit = element.namespaces.get(prefix);
        if (explicit != null) {
            if (!explicit.equals(uri)) {
                throw new IllegalStateException("Element <" + element.qualifiedName() + "> is in namespace '"
                        + uri + "' but declares its prefix for '" + explicit + "'");
            }
            return declarations;
        }
        if (!uri.equals(inScope.get(prefix))) {
            declarations.put(prefix, uri);
        }
        return declarations;
    }

    /**
     * Appends {@code value} with its markup characters escaped, up to the first character XML 1.0 does not allow.
     * A high surrogate followed by a low surrogate is one supplementary character, which XML allows, and is
     * appended as both halves; any other surrogate is unpaired.
     *
     * @return the index of the first character XML 1.0 does not allow, or {@code -1} when all of {@code value}
     *         was appended
     */
    private static int escape(String value, boolean attribute, StringBuilder out) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(attribute ? "&quot;" : "\"");
                default -> {
                    if (isXmlChar(c)) {
                        out.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < length
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(i + 1));
                        i++;
                    } else {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * True for a character the XML 1.0 {@code Char} production allows on its own: tab, line feed, carriage return,
     * U+0020 to U+D7FF and U+E000 to U+FFFD. Surrogates are excluded; {@link #escape} accepts them in pairs.
     */
    private static boolean isXmlChar(char c) {
        return c >= '\u0020'
                ? c < Character.MIN_SURROGATE || (c > Character.MAX_SURROGATE && c <= '\uFFFD')
                : c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * The failure for the character at {@code index} of {@code value}, named by its code point so that the
     * message, which may reach a SOAP fault or a JSON error body, never carries the character itself.
     *
     * @param location where the value is written, for example {@code the text of element <name>}
     */
    private static IllegalStateException invalidCharacter(String value, int index, String location) {
        return new IllegalStateException(
                String.format("Invalid XML 1.0 character U+%04X in %s", (int) value.charAt(index), location));
    }

    /**
     * One XML element: a name with an optional prefix and namespace URI, extra namespace declarations,
     * attributes, and either text or child elements. Every mutator returns this element, so a tree is built
     * fluently:
     *
     * <pre>{@code
     * DwXmlWriter.element("div")
     *         .child(DwXmlWriter.element("option").attribute("value", "Account").text("Account"));
     * }</pre>
     *
     * <p>Instances are mutable and not thread-safe.
     */
    public static final class Element {

        private final String prefix;
        private final String uri;
        private final String name;
        private final Map<String, String> namespaces = new LinkedHashMap<>();
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Element> children = new ArrayList<>();
        private String text;

        private Element(String prefix, String uri, String name) {
            this.name = requireElementName(name);
            checkPrefix(prefix, ELEMENT_PREFIX);
            checkBinding(normalizePrefix(prefix), uri);
            this.prefix = prefix;
            this.uri = uri;
        }

        /**
         * Adds a namespace declaration to this element, written before its own binding. A binding an
         * ancestor already declared with the same URI is not written again.
         *
         * @param prefix the prefix to bind; {@code null} or empty for the default namespace ({@code xmlns}),
         *        otherwise an {@code NCName} other than {@code xmlns}
         * @param uri the namespace URI; may be empty only for the default namespace
         * @return this element
         * @throws IllegalArgumentException when {@code prefix} is not such a prefix (the message names the first
         *         offending character by code point and offset, never the prefix), when {@code uri} is {@code null},
         *         or empty for a prefix, or when the binding misuses {@code xml},
         *         {@code http://www.w3.org/XML/1998/namespace} or {@code http://www.w3.org/2000/xmlns/}
         */
        public Element namespace(String prefix, String uri) {
            String key = normalizePrefix(prefix);
            checkPrefix(key, NAMESPACE_PREFIX);
            if (uri == null || (!key.isEmpty() && uri.isEmpty())) {
                throw new IllegalArgumentException("Namespace prefix '" + key + "' needs a namespace URI");
            }
            checkBinding(key, uri);
            namespaces.put(key, uri);
            return this;
        }

        /**
         * Sets an attribute, keeping the order in which attributes were first set.
         *
         * @param name the attribute name, a {@code QName} written as given (for example {@code xsi:type}); its prefix
         *        needs no declaration on this element
         * @param value the attribute value; {@code null} removes the attribute
         * @return this element
         * @throws IllegalArgumentException when {@code name} is {@code null}, empty, not a {@code QName}, or
         *         {@code xmlns} or {@code xmlns:*}, which {@link #namespace(String, String)} declares; the message
         *         names the first offending character by code point and offset, never the name
         */
        public Element attribute(String name, String value) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("An XML attribute name must not be null or empty");
            }
            checkAttributeName(name);
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
            return this;
        }

        /**
         * Sets this element's text: a {@link String} as it is, a {@link BigDecimal} through
         * {@code toPlainString()} so its scale is kept ({@code 27.60}), anything else through
         * {@code String.valueOf}.
         *
         * @param value the text value; {@code null} clears the text
         * @return this element
         */
        public Element text(Object value) {
            this.text = value == null ? null : render(value);
            return this;
        }

        /**
         * Appends a child element.
         *
         * @param child the child
         * @return this element
         * @throws IllegalArgumentException when {@code child} is {@code null}
         */
        public Element child(Element child) {
            if (child == null) {
                throw new IllegalArgumentException("An XML child element must not be null");
            }
            children.add(child);
            return this;
        }

        /**
         * Appends child elements in iteration order.
         *
         * @param children the children
         * @return this element
         * @throws IllegalArgumentException when {@code children} or one of its elements is {@code null}
         */
        public Element children(Iterable<Element> children) {
            if (children == null) {
                throw new IllegalArgumentException("XML child elements must not be null");
            }
            for (Element child : children) {
                child(child);
            }
            return this;
        }

        /**
         * Appends child elements in argument order.
         *
         * @param children the children
         * @return this element
         * @throws IllegalArgumentException when {@code children} or one of its elements is {@code null}
         */
        public Element children(Element... children) {
            if (children == null) {
                throw new IllegalArgumentException("XML child elements must not be null");
            }
            return children(Arrays.asList(children));
        }

        /** @return the local name */
        public String getName() {
            return name;
        }

        /** @return the prefix as given, {@code null} for an element created without one */
        public String getPrefix() {
            return prefix;
        }

        /** @return the namespace URI as given, {@code null} for an element created without one */
        public String getUri() {
            return uri;
        }

        /** @return the attributes in insertion order, unmodifiable */
        public Map<String, String> getAttributes() {
            return Collections.unmodifiableMap(attributes);
        }

        /** @return the explicit namespace declarations in insertion order, the default namespace keyed {@code ""} */
        public Map<String, String> getNamespaces() {
            return Collections.unmodifiableMap(namespaces);
        }

        /** @return the rendered text, or {@code null} when the element has none */
        public String getText() {
            return text;
        }

        /** @return the children in order, unmodifiable */
        public List<Element> getChildren() {
            return Collections.unmodifiableList(children);
        }

        private String qualifiedName() {
            return prefix == null || prefix.isEmpty() ? name : prefix + ":" + name;
        }
    }
}
