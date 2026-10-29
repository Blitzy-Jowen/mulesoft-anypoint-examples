package com.mulesoft.examples.foreach_processing_and_choice_routing.model.soap;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * The document/literal-wrapped response element of the original
 * {@code BankService.getLoanQuote(LoanBrokerQuoteRequest)} operation, which CXF's JAX-WS runtime derived from the
 * interface: {@code getLoanQuoteResponse} with the single child {@code return} (the default name, as the operation
 * declares no {@code @WebResult}), as {@code wsdl/BankService.wsdl} states it.
 *
 * <p>The namespace is the one JAX-WS derived from the original bank service's Java package; it is declared here
 * because this class's own package would yield a different one. The child is unqualified
 * ({@code elementFormDefault="unqualified"}), so this package has no {@code package-info.java}.
 */
@XmlRootElement(name = "getLoanQuoteResponse", namespace = "http://bank.loanbroker.example.mule.org/")
@XmlType(name = "getLoanQuoteResponse", namespace = "http://bank.loanbroker.example.mule.org/")
@XmlAccessorType(XmlAccessType.FIELD)
public class GetLoanQuoteResponse
{
    @XmlElement(name = "return")
    private LoanQuote _return;

    public GetLoanQuoteResponse()
    {
        super();
    }

    public GetLoanQuoteResponse(LoanQuote quote)
    {
        this._return = quote;
    }

    /**
     * @return the bank's quote, or {@code null} when the response carries no {@code return} element
     */
    public LoanQuote getReturn()
    {
        return _return;
    }

    public void setReturn(LoanQuote value)
    {
        this._return = value;
    }
}
