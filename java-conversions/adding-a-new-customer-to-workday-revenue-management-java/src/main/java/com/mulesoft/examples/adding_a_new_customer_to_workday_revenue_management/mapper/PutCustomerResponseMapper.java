package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;

/**
 * Hand-written reimplementation of the "Extract Customer Name" DataWeave 1.0 script of
 * {@code add-customer-flow} (header at line 44 of {@code add_a_new_customer.xml}), which runs on the
 * {@code Put_Customer} response:
 *
 * <pre>
 * %output application/java
 * %namespace ns0 urn:com.workday/bsvc
 * ---
 * payload.ns0#Put_Customer_Response.ns0#Customer_Reference.@ns0#Descriptor
 * </pre>
 *
 * This class is the script's only owner (script-owner table of the conversion plan, AAP 0.5.8). It returns the
 * {@code wd:Descriptor} attribute of {@code Put_Customer_Response/Customer_Reference} unchanged (no trimming);
 * the caller places it in the flow's {@code Added customer: #[payload]} response.
 * <p>
 * Null rule: DataWeave selects {@code null} when the response, its {@code Customer_Reference} (optional in the
 * v25.0 schema) or the {@code Descriptor} attribute is absent, and this mapper renders that {@code null} as the
 * four-character text {@code null}, so the flow answers {@code Added customer: null}. How the original runtime
 * rendered a null payload in that template is a Tier 2A verification point.
 * <p>
 * Stateless and thread-safe.
 */
@Component
public class PutCustomerResponseMapper {

    /**
     * Selects the descriptor of the customer Workday added.
     *
     * @param response the unwrapped {@code Put_Customer_Response} (a caller holding a {@code JAXBElement} passes
     *                 its value); may be {@code null}
     * @return the {@code Customer_Reference} descriptor as Workday sent it, or {@code "null"} when the response,
     *         the reference or the descriptor is absent
     */
    public String descriptor(PutCustomerResponseType response) {
        String descriptor = null;
        if (response != null) {
            CustomerObjectType reference = response.getCustomerReference();
            if (reference != null) {
                descriptor = reference.getDescriptor();
            }
        }
        return String.valueOf((Object) descriptor);
    }
}
