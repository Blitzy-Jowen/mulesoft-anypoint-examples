/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueManagementClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerRequestMapper;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerResponseMapper;

import jakarta.xml.bind.JAXBElement;

/**
 * Orchestration of the Mule flow {@code add-customer-flow}
 * ({@code adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:7-54},
 * {@code processingStrategy="synchronous"}): every processor after the HTTP listener, in the original order, one
 * statement each.
 * <ul>
 * <li>l9 {@code mulexml:xml-to-dom-transformer}: the posted bytes are parsed into a namespace-aware DOM;</li>
 * <li>l12 {@code %dw 1.0} "XML to Workday PutCustomerRequest XML": {@link PutCustomerRequestMapper#map};</li>
 * <li>l42 {@code wd-connector:invoke type="Revenue_Management||Put_Customer"}:
 * {@link WorkdayRevenueManagementClient#putCustomer};</li>
 * <li>l44 {@code %dw 1.0} "Extract Customer Name" ({@code Customer_Reference/@Descriptor}):
 * {@link PutCustomerResponseMapper#descriptor};</li>
 * <li>l52 {@code set-payload value="Added customer: #[payload]"}: {@link #RESPONSE_PREFIX} followed by the
 * descriptor.</li>
 * </ul>
 * The HTTP listener of l8 ({@code POST /}) is bound by the controller.
 * <p>
 * The original declares no {@code logger} element and no exception strategy, so this class logs nothing and handles
 * no failure: every exception reaches the advices unchanged, the upstream advice (SC-9, highest order) answering the
 * three upstream exceptions with 502/429/503 and the generic advice (SC-1, lowest order) answering any other with 500
 * {@code { "message": ... }}. Retries live only inside the client's upstream call executor; the request document is
 * not validated against {@code root.xsd}, which the original never did.
 * <p>
 * Stateless and thread-safe: a parser is created per request.
 */
@Service
public class WorkdayCustomerService {

    /** Literal part of the original {@code set-payload} template {@code Added customer: #[payload]}. */
    static final String RESPONSE_PREFIX = "Added customer: ";

    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";

    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    private final PutCustomerRequestMapper requestMapper;

    private final WorkdayRevenueManagementClient client;

    private final PutCustomerResponseMapper responseMapper;

    /**
     * @param requestMapper  owner of the {@code Put_Customer_Request} script
     * @param client         Workday Revenue_Management v25.0 client
     * @param responseMapper owner of the {@code Customer_Reference} descriptor script
     */
    public WorkdayCustomerService(PutCustomerRequestMapper requestMapper,
                                  WorkdayRevenueManagementClient client,
                                  PutCustomerResponseMapper responseMapper) {
        this.requestMapper = requestMapper;
        this.client = client;
        this.responseMapper = responseMapper;
    }

    /**
     * Adds the posted customer to Workday and answers as the original flow did.
     *
     * @param xml the request body, an un-namespaced {@code root/Account} document of {@code root.xsd}; {@code null}
     *            is read as an empty body
     * @return {@code Added customer: <descriptor>}, with the text {@code null} in place of an absent descriptor
     * @throws SAXParseException            the body is empty, malformed or declares a DOCTYPE (for example
     *                                      {@code Premature end of file.})
     * @throws ParserConfigurationException the JDK parser rejects the hardened configuration
     * @throws IOException                  the body cannot be read
     * @throws Exception                    from the Workday call: {@code UpstreamAuthenticationException},
     *                                      {@code UpstreamRateLimitException}, {@code UpstreamUnavailableException}
     *                                      or the vendor failure unchanged (for example a SOAP fault)
     */
    public String addCustomer(byte[] xml) throws Exception {
        Document document = parse(xml);
        JAXBElement<PutCustomerRequestType> request = requestMapper.map(document);
        PutCustomerResponseType response = client.putCustomer(request);
        String descriptor = responseMapper.descriptor(response);
        return RESPONSE_PREFIX + descriptor;
    }

    /**
     * Parses the body as Mule 3.8's secure XML processing did: namespace-aware, no DOCTYPE, no external entities, no
     * XInclude. The bytes go to the parser directly so the encoding of the XML declaration is honoured.
     */
    private static Document parse(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        byte[] body = xml == null ? new byte[0] : xml;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new RethrowingErrorHandler());
        return builder.parse(new ByteArrayInputStream(body));
    }

    /**
     * Fails the parse on every error and fatal error with the parser's own exception, and writes nothing to standard
     * error, where the JDK's default handler prints a {@code [Fatal Error]} line.
     */
    static final class RethrowingErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) {
            // Warnings do not fail the parse, matching the JDK's default handler.
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    }
}
