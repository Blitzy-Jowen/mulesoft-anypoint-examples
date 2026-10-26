package com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped request element of the credit agency's
 * <code>getCreditProfile(Customer)</code> operation, which the JAX-WS runtime
 * used to derive from the <code>CreditAgencyService</code> interface.
 * <p>
 * The namespace is the one JAX-WS derived from the original credit agency
 * package and is stated explicitly, never derived from this class's package,
 * so the wire contract survives the move to the new package. The single child keeps
 * the JAX-WS default name <code>arg0</code> and no namespace, matching
 * <code>elementFormDefault="unqualified"</code> of the published WSDL; a null
 * customer is omitted rather than written as <code>xsi:nil</code>.
 */
@XmlRootElement(name = "getCreditProfile", namespace = GetCreditProfile.NAMESPACE)
@XmlType(name = "getCreditProfile", namespace = GetCreditProfile.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class GetCreditProfile
{
    /**
     * Target namespace of the credit agency service.
     */
    public static final String NAMESPACE = "http://creditagency.loanbroker.example.mule.org/";

    @XmlElement(name = "arg0")
    private Customer arg0;

    public GetCreditProfile()
    {
        super();
    }

    public GetCreditProfile(Customer arg0)
    {
        this.arg0 = arg0;
    }

    public Customer getArg0()
    {
        return arg0;
    }

    public void setArg0(Customer arg0)
    {
        this.arg0 = arg0;
    }
}
