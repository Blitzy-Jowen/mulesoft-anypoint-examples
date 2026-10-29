package com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped response element of the original {@code CreditAgencyService.getCreditProfile(Customer)}
 * operation, formerly derived by CXF from the {@code @WebService} interface: {@code getCreditProfileResponse} in
 * the namespace CXF derived from the original credit agency package, holding the credit profile as the unqualified
 * child {@code return}, the JAX-WS default name for a result without {@code @WebResult}.
 *
 * <p>The namespace is stated here rather than derived from this class's package, which no longer matches the
 * original one. The {@link CreditProfile} inside stays unannotated, so JAXB binds its properties in name order
 * ({@code creditHistory}, then {@code creditScore}), as CXF's JAXB databinding did and
 * {@code wsdl/CreditAgencyService.wsdl} declares.
 */
@XmlRootElement(name = "getCreditProfileResponse", namespace = GetCreditProfileResponse.NAMESPACE)
@XmlType(name = "getCreditProfileResponse", namespace = GetCreditProfileResponse.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class GetCreditProfileResponse
{
    /** Target namespace of the original credit agency service. */
    public static final String NAMESPACE = "http://creditagency.loanbroker.example.mule.org/";

    /** {@code return} is a Java keyword; the element name comes from the annotation. A null profile is omitted. */
    @XmlElement(name = "return")
    private CreditProfile _return;

    public GetCreditProfileResponse()
    {
        super();
    }

    public GetCreditProfileResponse(CreditProfile creditProfile)
    {
        this._return = creditProfile;
    }

    public CreditProfile getReturn()
    {
        return _return;
    }

    public void setReturn(CreditProfile value)
    {
        this._return = value;
    }
}
