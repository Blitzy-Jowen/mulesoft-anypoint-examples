package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support;

import java.io.StringWriter;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Hand-written port of the three DataWeave 1.0 scripts and the Workday assertion of the MUnit suite
 * {@code adding-a-new-customer-to-workday-revenue-management/src/test/munit/integration-test.xml}, of which this class
 * is the single owner (AAP 0.5.8); tests call it rather than building these documents themselves:
 * <ul>
 * <li>line 37, "Input data": the {@code root/Account} customer XML posted to the add-customer flow
 * ({@link #customerXml(String)});</li>
 * <li>line 57, {@code assert-on-equals expectedValue="1"} on
 * {@code payload.Get_Customers_Response.Response_Results.Total_Results} ({@link #totalResults(Document)});</li>
 * <li>line 64, "Create GetCustomers request": {@code wd:Get_Customers_Request} by {@code Customer_Reference_ID}
 * ({@link #getCustomersRequest(String)});</li>
 * <li>line 87, "Create PutCustomer request": the inactivating {@code wd:Put_Customer_Request}
 * ({@link #inactivateRequest(String, Document, String, String)}).</li>
 * </ul>
 * The two Workday requests are returned as re-readable {@link DOMSource}s, which callers send through
 * {@code client/WorkdayRevenueManagementClient.sendRaw}, so they reach the wire with the same UsernameToken, timeouts
 * and failure handling as the application's own {@code Put_Customer}. Workday exposes no customer delete: a test
 * customer is cleaned up by inactivating it, as the original {@code inactivateCustomerInWorkdayFlow} did.
 * <p>
 * Only JDK XML APIs are used, through the JDK's built-in DOM and transformer implementations, so the serialized bytes
 * do not depend on which XML libraries the test classpath happens to carry.
 */
public final class WorkdayTestRequests {

    /** Workday Web Services namespace, the scripts' {@code %namespace ns0 urn:com.workday/bsvc}. */
    public static final String WD_NS = "urn:com.workday/bsvc";

    /** Prefix bound to {@link #WD_NS} on every Workday request this class builds. */
    public static final String WD_PREFIX = "wd";

    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private static final String GET_CUSTOMERS_RESPONSE = "Get_Customers_Response";

    private static final String CUSTOMER_CATEGORY_ID = "Customer_Category_ID";

    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = documentBuilderFactory();

    private static final TransformerFactory TRANSFORMER_FACTORY = transformerFactory();

    private WorkdayTestRequests() {
        throw new AssertionError("WorkdayTestRequests is a static helper and is never instantiated");
    }

    /**
     * Port of the "Input data" script (lines 37-49): {@code root: { Account: { CustomerName, BusinessEntityName,
     * Customer_Category_Reference_Type: "Customer_Category_ID", Customer_Category_Reference_Value: "CUSTOMER_CATEGORY-5",
     * Customer_Status_Reference_Type: "Business_Entity_Status_Value_ID", Customer_Status_Reference_Value: "ACTIVE" } }}.
     * <p>
     * The un-namespaced document follows the sequence of {@code root.xsd} and the shape of
     * {@code src/test/resources/customer.xml}; it is serialized without indentation behind a double-quoted UTF-8
     * declaration, and the serializer escapes any markup characters in {@code name}. The MUnit before-test named the
     * customer {@code "John" + System.currentTimeMillis()}; callers generate that name themselves and post the result to
     * {@code /} as {@code application/xml} encoded in UTF-8.
     *
     * @param name the customer name, written to both {@code CustomerName} and {@code BusinessEntityName}
     * @return the customer XML, starting with {@code <?xml version="1.0" encoding="UTF-8"?><root><Account>}
     */
    public static String customerXml(String name) {
        Objects.requireNonNull(name, "name");
        Document document = newDocument();
        Element root = document.createElementNS(null, "root");
        document.appendChild(root);
        Element account = plain(document, root, "Account", null);
        plain(document, account, "CustomerName", name);
        plain(document, account, "BusinessEntityName", name);
        plain(document, account, "Customer_Category_Reference_Type", CUSTOMER_CATEGORY_ID);
        plain(document, account, "Customer_Category_Reference_Value", "CUSTOMER_CATEGORY-5");
        plain(document, account, "Customer_Status_Reference_Type", "Business_Entity_Status_Value_ID");
        plain(document, account, "Customer_Status_Reference_Value", "ACTIVE");
        // The JDK serializer's own declaration carries standalone="no"; writing it here keeps the bytes identical to
        // customer.xml's declaration.
        return XML_DECLARATION + serializeWithoutDeclaration(document);
    }

    /**
     * Port of the "Create GetCustomers request" script (lines 64-78): {@code ns0#Get_Customers_Request:
     * { ns0#Request_Criteria: { ns0#Customer_Reference_ID: name }, ns0#Response_Group: { ns0#Include_Reference: true,
     * ns0#Include_Customer_Data: true } }}. {@code Request_Criteria} precedes {@code Response_Group}, the order of the
     * v25.0 {@code Get_Customers_RequestType} sequence.
     *
     * @param name the {@code Customer_Reference_ID} the customer was added under
     * @return a {@link DOMSource} whose node is the whole {@link Document}, rooted at {@code wd:Get_Customers_Request}
     */
    public static Source getCustomersRequest(String name) {
        Objects.requireNonNull(name, "name");
        Document document = newDocument();
        Element request = workdayRoot(document, "Get_Customers_Request");
        Element criteria = wd(document, request, "Request_Criteria");
        wd(document, criteria, "Customer_Reference_ID", name);
        Element responseGroup = wd(document, request, "Response_Group");
        wd(document, responseGroup, "Include_Reference", "true");
        wd(document, responseGroup, "Include_Customer_Data", "true");
        return new DOMSource(document);
    }

    /**
     * Port of the "Create PutCustomer request" script (lines 87-116), which inactivates the customer found by
     * {@link #getCustomersRequest(String)}. In {@code urn:com.workday/bsvc}, every {@code type} attribute
     * {@code wd:}-qualified:
     * <pre>
     * Put_Customer_Request
     *   Customer_Reference/ID type="Customer_Reference_ID"                  = name
     *   Customer_Data
     *     Business_Entity_Data/Business_Entity_Name                         = name
     *     Customer_Name                                                     = name
     *     Customer_Category_Reference/ID type="Customer_Category_ID"        = &lt;category&gt;
     *     Customer_Status_Data
     *       Customer_Status_Value_Reference/ID type="WID"                   = statusId
     *       Reason_for_Customer_Status_Change_Reference/ID type="WID"       = reasonId
     * </pre>
     * {@code <category>} is {@code (customer.Customer_Category_Reference.*ID filter $.@type == "Customer_Category_ID")[0]}
     * with {@code customer = payload.Get_Customers_Response.Response_Data.Customer.Customer_Data}: each step takes the
     * first matching child, and every {@code ID} of the reference is tested because Workday lists its {@code WID}
     * first. When any step or the matching {@code ID} is absent, DataWeave wrote an empty element, and so does this
     * method. The response may be the bare {@code Get_Customers_Response} that {@code sendRaw} returns or a whole SOAP
     * envelope containing it; it is only read, never modified. Elements are written in the script's order, which reaches
     * the wire unchanged because the request is sent raw.
     *
     * @param name the customer name and {@code Customer_Reference_ID}
     * @param getCustomersResponse the response to {@link #getCustomersRequest(String)} for the same customer
     * @param statusId the inactive status WID, the original {@code p('wday.status.id')}
     * @param reasonId the status-change reason WID, the original {@code p('wday.reason.id')}
     * @return a {@link DOMSource} whose node is the whole {@link Document}, rooted at {@code wd:Put_Customer_Request}
     */
    public static Source inactivateRequest(String name, Document getCustomersResponse, String statusId,
            String reasonId) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(getCustomersResponse, "getCustomersResponse");
        Objects.requireNonNull(statusId, "statusId");
        Objects.requireNonNull(reasonId, "reasonId");
        String customerCategoryId = customerCategoryId(getCustomersResponse);

        Document document = newDocument();
        Element request = workdayRoot(document, "Put_Customer_Request");
        Element customerReference = wd(document, request, "Customer_Reference");
        wdType(wd(document, customerReference, "ID", name), "Customer_Reference_ID");

        Element customerData = wd(document, request, "Customer_Data");
        Element businessEntityData = wd(document, customerData, "Business_Entity_Data");
        wd(document, businessEntityData, "Business_Entity_Name", name);
        wd(document, customerData, "Customer_Name", name);
        Element categoryReference = wd(document, customerData, "Customer_Category_Reference");
        wdType(wd(document, categoryReference, "ID", customerCategoryId), CUSTOMER_CATEGORY_ID);

        Element statusData = wd(document, customerData, "Customer_Status_Data");
        Element statusReference = wd(document, statusData, "Customer_Status_Value_Reference");
        wdType(wd(document, statusReference, "ID", statusId), "WID");
        Element reasonReference = wd(document, statusData, "Reason_for_Customer_Status_Change_Reference");
        wdType(wd(document, reasonReference, "ID", reasonId), "WID");
        return new DOMSource(document);
    }

    /**
     * Port of the value the MUnit {@code assert-on-equals expectedValue="1"} (line 57) compared:
     * {@code payload.Get_Customers_Response.Response_Results.Total_Results}, first match at each step, text trimmed.
     *
     * @param response the response to {@link #getCustomersRequest(String)}, bare or inside a SOAP envelope
     * @return the {@code Total_Results} text, or {@code null} when the response or any step is absent
     */
    public static String totalResults(Document response) {
        if (response == null) {
            return null;
        }
        Element results = firstChild(getCustomersResponse(response), "Response_Results");
        Element total = firstChild(results, "Total_Results");
        return total == null ? null : total.getTextContent().trim();
    }

    /** The {@code Customer_Category_ID} entry of the retrieved customer, or empty text when there is none. */
    private static String customerCategoryId(Document response) {
        Element customer = firstChild(firstChild(firstChild(getCustomersResponse(response), "Response_Data"),
                "Customer"), "Customer_Data");
        Element categoryReference = firstChild(customer, "Customer_Category_Reference");
        if (categoryReference == null) {
            return "";
        }
        for (Node child = categoryReference.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && "ID".equals(localName(child))
                    && CUSTOMER_CATEGORY_ID.equals(typeAttribute((Element) child))) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    /**
     * The {@code Get_Customers_Response} element: the document element when it is one, otherwise its first descendant
     * of that name in document order (the payload inside a SOAP body); {@code null} when there is none.
     */
    private static Element getCustomersResponse(Document response) {
        Element root = response.getDocumentElement();
        if (root == null || GET_CUSTOMERS_RESPONSE.equals(localName(root))) {
            return root;
        }
        return firstDescendant(root, GET_CUSTOMERS_RESPONSE);
    }

    private static Element firstDescendant(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (localName.equals(localName(child))) {
                return (Element) child;
            }
            Element found = firstDescendant((Element) child, localName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** First element child with the given local name, any namespace; {@code null} when {@code parent} is null. */
    private static Element firstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(child))) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Value of the attribute whose local name is {@code type} in any namespace (DataWeave's {@code $.@type}), or
     * {@code null}. Namespace declarations are skipped, so {@code xmlns:type="..."} never matches.
     */
    private static String typeAttribute(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String qualifiedName = attribute.getNodeName();
            boolean declaration = XMLNS_NS.equals(attribute.getNamespaceURI())
                    || XMLConstants.XMLNS_ATTRIBUTE.equals(qualifiedName)
                    || qualifiedName.startsWith(XMLConstants.XMLNS_ATTRIBUTE + ":");
            if (!declaration && "type".equals(localName(attribute))) {
                return attribute.getValue();
            }
        }
        return null;
    }

    /** Local name of a node, also for documents parsed without namespace awareness. */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String nodeName = node.getNodeName();
        int colon = nodeName.indexOf(':');
        return colon < 0 ? nodeName : nodeName.substring(colon + 1);
    }

    /** Creates the Workday root element, declares the {@code wd} prefix on it and appends it to the document. */
    private static Element workdayRoot(Document document, String localName) {
        Element root = wd(document, localName);
        // Declared explicitly so every serializer, SAAJ's included, writes a well-formed xmlns:wd on the root.
        root.setAttributeNS(XMLNS_NS, XMLConstants.XMLNS_ATTRIBUTE + ":" + WD_PREFIX, WD_NS);
        document.appendChild(root);
        return root;
    }

    private static Element wd(Document document, String localName) {
        return document.createElementNS(WD_NS, WD_PREFIX + ":" + localName);
    }

    /** Appends an empty container element in the Workday namespace. */
    private static Element wd(Document document, Element parent, String localName) {
        Element element = wd(document, localName);
        parent.appendChild(element);
        return element;
    }

    /** Appends a Workday element holding {@code text}; {@code null} writes an empty element, as DataWeave did. */
    private static Element wd(Document document, Element parent, String localName, String text) {
        Element element = wd(document, parent, localName);
        element.setTextContent(text == null ? "" : text);
        return element;
    }

    /** DataWeave's {@code @(ns0#type: value)}: a {@code wd:}-qualified attribute, never a bare {@code type}. */
    private static void wdType(Element element, String value) {
        element.setAttributeNS(WD_NS, WD_PREFIX + ":type", value);
    }

    /** Appends an un-namespaced element, holding {@code text} unless it is {@code null}. */
    private static Element plain(Document document, Element parent, String localName, String text) {
        Element element = document.createElementNS(null, localName);
        if (text != null) {
            element.setTextContent(text);
        }
        parent.appendChild(element);
        return element;
    }

    private static String serializeWithoutDeclaration(Document document) {
        try {
            Transformer transformer = newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException("Could not serialize the customer XML", e);
        }
    }

    private static Document newDocument() {
        try {
            // DocumentBuilderFactory is not guaranteed thread-safe; builders are created one at a time.
            synchronized (DOCUMENT_BUILDER_FACTORY) {
                return DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().newDocument();
            }
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Could not create an XML document builder", e);
        }
    }

    private static Transformer newTransformer() throws TransformerConfigurationException {
        // TransformerFactory is not guaranteed thread-safe; transformers are created one at a time.
        synchronized (TRANSFORMER_FACTORY) {
            return TRANSFORMER_FACTORY.newTransformer();
        }
    }

    private static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not configure a hardened XML document builder factory", e);
        }
        return factory;
    }

    private static TransformerFactory transformerFactory() {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (TransformerConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not configure a hardened XML transformer factory", e);
        }
        return factory;
    }
}
