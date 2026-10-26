package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.BusinessEntityStatusValueObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.BusinessEntityStatusValueObjectType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.BusinessEntityWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerCategoryObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerCategoryObjectType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerStatusDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;

import jakarta.xml.bind.JAXBElement;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "XML to Workday PutCustomerRequest XML"
 * ({@code adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml}, header line 12,
 * script lines 12-39), of which this class is the sole owner (AAP 0.5.8). The script ran on the DOM produced by
 * {@code mulexml:xml-to-dom-transformer} and wrote, in namespace {@code urn:com.workday/bsvc}:
 * <pre>
 * Put_Customer_Request/Customer_Data
 *   Customer_ID, Customer_Reference_ID, Customer_Name    = root.Account.CustomerName
 *   Business_Entity_Data/Business_Entity_Name             = root.Account.BusinessEntityName
 *   Customer_Category_Reference/ID @type                  = root.Account.Customer_Category_Reference_Type
 *   Customer_Category_Reference/ID text                   = root.Account.Customer_Category_Reference_Value
 *   Customer_Status_Data/Customer_Status_Value_Reference/ID @type = root.Account.Customer_Status_Reference_Type
 *   Customer_Status_Data/Customer_Status_Value_Reference/ID text  = root.Account.Customer_Status_Reference_Value
 * </pre>
 * The result is the XJC binding of the Revenue_Management v25.0 schema, which the Workday client marshals; nothing
 * else is set, so {@code Customer_Reference}, {@code wd:Add_Only} and {@code wd:version} stay absent exactly as the
 * script never wrote them.
 * <p>
 * Input is read as DataWeave 1.0's un-prefixed selectors read it: elements are matched by local name whatever their
 * namespace or prefix, only element children count, the first match wins, and each value is the element's text
 * content unchanged. The input is the un-namespaced document of {@code root.xsd}; it is not validated, as the
 * original never validated it.
 * <p>
 * Wire facts of the marshalled request:
 * <ul>
 * <li>both {@code type} attributes are {@code wd:}-qualified ({@code attributeFormDefault="qualified"}), matching the
 * script's {@code @(ns0#type: ...)}; the namespace prefix itself ({@code ns0} in DataWeave) is not significant;</li>
 * <li>children follow the schema sequence of {@code Customer_WWS_DataType}: {@code Customer_ID},
 * {@code Customer_Reference_ID}, {@code Customer_Name}, {@code Customer_Category_Reference},
 * {@code Business_Entity_Data}, {@code Customer_Status_Data}.</li>
 * </ul>
 * Tier 2A verification points (behaviour the committed sources do not settle, to be confirmed against captures of
 * the running original):
 * <ul>
 * <li>an absent value element ({@code CustomerName}, {@code BusinessEntityName}, {@code *_Value}) yields an empty
 * output element;</li>
 * <li>an absent type element ({@code *_Type}) yields an {@code ID} without {@code wd:type}; a present but empty one
 * yields {@code wd:type=""};</li>
 * <li>{@code Customer_Category_Reference} precedes {@code Business_Entity_Data}, the schema order, although the script
 * lists them the other way round.</li>
 * </ul>
 * Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class PutCustomerRequestMapper {

    /** Target namespace of the Workday Revenue_Management schema (the script's {@code ns0}). */
    public static final String WORKDAY_NAMESPACE = "urn:com.workday/bsvc";

    private static final String ROOT = "root";
    private static final String ACCOUNT = "Account";
    private static final String CUSTOMER_NAME = "CustomerName";
    private static final String BUSINESS_ENTITY_NAME = "BusinessEntityName";
    private static final String CUSTOMER_CATEGORY_REFERENCE_TYPE = "Customer_Category_Reference_Type";
    private static final String CUSTOMER_CATEGORY_REFERENCE_VALUE = "Customer_Category_Reference_Value";
    private static final String CUSTOMER_STATUS_REFERENCE_TYPE = "Customer_Status_Reference_Type";
    private static final String CUSTOMER_STATUS_REFERENCE_VALUE = "Customer_Status_Reference_Value";

    /** Written for an absent value element, so the mandatory output element is still emitted. */
    private static final String ABSENT_VALUE = "";

    private final ObjectFactory objectFactory = new ObjectFactory();

    /**
     * Builds the {@code Put_Customer_Request} for the posted customer document.
     *
     * @param input the parsed {@code root/Account} document; {@code null}, a differently named root, a missing
     *              {@code Account} or a missing field never fails, each absent part being treated as an absent field
     * @return the {@code wd:Put_Customer_Request} element holding one {@code Customer_Data}
     */
    public JAXBElement<PutCustomerRequestType> map(Document input) {
        Element account = firstChildElement(firstChildElement(input, ROOT), ACCOUNT);
        String customerName = value(account, CUSTOMER_NAME);

        CustomerWWSDataType data = new CustomerWWSDataType();
        data.setCustomerID(customerName);
        data.setCustomerReferenceID(customerName);
        data.setCustomerName(customerName);

        CustomerCategoryObjectIDType categoryId = new CustomerCategoryObjectIDType();
        categoryId.setValue(value(account, CUSTOMER_CATEGORY_REFERENCE_VALUE));
        categoryId.setType(type(account, CUSTOMER_CATEGORY_REFERENCE_TYPE));
        CustomerCategoryObjectType categoryReference = new CustomerCategoryObjectType();
        categoryReference.getID().add(categoryId);
        data.setCustomerCategoryReference(categoryReference);

        BusinessEntityWWSDataType businessEntity = new BusinessEntityWWSDataType();
        businessEntity.setBusinessEntityName(value(account, BUSINESS_ENTITY_NAME));
        data.setBusinessEntityData(businessEntity);

        BusinessEntityStatusValueObjectIDType statusId = new BusinessEntityStatusValueObjectIDType();
        statusId.setValue(value(account, CUSTOMER_STATUS_REFERENCE_VALUE));
        statusId.setType(type(account, CUSTOMER_STATUS_REFERENCE_TYPE));
        BusinessEntityStatusValueObjectType statusValueReference = new BusinessEntityStatusValueObjectType();
        statusValueReference.getID().add(statusId);
        CustomerStatusDataType statusData = new CustomerStatusDataType();
        statusData.setCustomerStatusValueReference(statusValueReference);
        data.getCustomerStatusData().add(statusData);

        PutCustomerRequestType request = new PutCustomerRequestType();
        request.setCustomerData(data);
        return objectFactory.createPutCustomerRequest(request);
    }

    /** Text of the named field, or {@link #ABSENT_VALUE} when the field (or any ancestor) is absent. */
    private static String value(Element account, String fieldName) {
        Element field = firstChildElement(account, fieldName);
        return field == null ? ABSENT_VALUE : field.getTextContent();
    }

    /** Text of the named type field, or {@code null} when absent, which leaves the {@code wd:type} attribute out. */
    private static String type(Element account, String fieldName) {
        Element field = firstChildElement(account, fieldName);
        return field == null ? null : field.getTextContent();
    }

    /**
     * First element child of {@code parent} with the given local name; {@code null} when {@code parent} is
     * {@code null} or has no such child. For a {@link Document} the only element child is the document element.
     */
    private static Element firstChildElement(Node parent, String localName) {
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
     * Local name of an element; a DOM built without namespace awareness reports none, so the node name is used with
     * any {@code prefix:} removed ({@code indexOf} yields -1 without a prefix, keeping the whole name).
     */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        return localName != null ? localName : node.getNodeName().substring(node.getNodeName().indexOf(':') + 1);
    }
}
