package com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped request element of the original
 * {@code BankService.getLoanQuote(LoanBrokerQuoteRequest)} operation, which CXF's JAX-WS runtime derived from the
 * interface: {@code getLoanQuote} with the single child {@code arg0} (the default name, as the operation declares no
 * {@code @WebParam}), as {@code wsdl/BankService.wsdl} states it.
 *
 * <p>The namespace is the one JAX-WS derived from the original bank service's Java package; it is declared here
 * because this class's own package would yield a different one. The child is unqualified
 * ({@code elementFormDefault="unqualified"}), so this package has no {@code package-info.java}, and a null request
 * is omitted rather than written as {@code xsi:nil}. {@link LoanBrokerQuoteRequest} stays unannotated, so JAXB's
 * default binding orders its children by property name, as CXF's databinding did.
 */
@XmlRootElement(name = "getLoanQuote", namespace = GetLoanQuote.NAMESPACE)
@XmlType(name = "getLoanQuote", namespace = GetLoanQuote.NAMESPACE)
@XmlAccessorType(XmlAccessType.FIELD)
public class GetLoanQuote
{
    /**
     * Target namespace of the bank service.
     */
    public static final String NAMESPACE = "http://bank.loanbroker.example.mule.org/";

    @XmlElement(name = "arg0")
    private LoanBrokerQuoteRequest arg0;

    public GetLoanQuote()
    {
        super();
    }

    public GetLoanQuote(LoanBrokerQuoteRequest arg0)
    {
        this.arg0 = arg0;
    }

    /**
     * @return the loan broker's quote request, or {@code null} when the request carries no {@code arg0} element
     */
    public LoanBrokerQuoteRequest getArg0()
    {
        return arg0;
    }

    public void setArg0(LoanBrokerQuoteRequest arg0)
    {
        this.arg0 = arg0;
    }
}
