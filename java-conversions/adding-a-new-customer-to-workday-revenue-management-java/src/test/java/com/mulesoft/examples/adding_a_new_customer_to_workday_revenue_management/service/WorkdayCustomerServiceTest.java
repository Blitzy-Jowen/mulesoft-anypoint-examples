/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.UpstreamSystem;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueManagementClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.BusinessEntityStatusValueObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerCategoryObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerWWSDataType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerRequestType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerRequestMapper;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerResponseMapper;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;

/**
 * Unit test of {@link WorkdayCustomerService}, the orchestration of {@code add-customer-flow}
 * ({@code add_a_new_customer.xml:7-54}): the namespace-aware parse of l9, the request script of l12, the
 * {@code Put_Customer} invocation of l42, the descriptor script of l44 and the {@code Added customer: #[payload]}
 * response of l52, in that order.
 * <p>
 * The Workday client is a Mockito mock; the mappers are mocks where the order and identity of what passes between the
 * steps is asserted, and the real classes where the request reaching Workday or the answer built from its response is
 * asserted. No Spring context, network or credentials are used.
 * <p>
 * It fixes the flow contract: the body is parsed namespace-aware in the encoding it declares; a body that is not
 * well-formed XML or that declares a DOCTYPE fails with the parser's own exception before anything else runs, without
 * a {@code [Fatal Error]} line on standard error; and every failure of the Workday call (the three upstream exceptions
 * of the connector failure handling and the vendor failure itself) reaches the caller as the same instance, with no
 * retry and no answer built.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("WorkdayCustomerService (add-customer-flow, add_a_new_customer.xml:7-54)")
class WorkdayCustomerServiceTest {

    private static final String WD_NS = "urn:com.workday/bsvc";

    private static final String CUSTOMER_NS = "urn:example:customer";

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String VALIDATION_FAULT_STRING =
            "Validation error occurred. Invalid ID value. 'NOPE' is not a valid ID value for type = 'Customer_ID'";

    private static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();

    @Mock
    private PutCustomerRequestMapper requestMapper;

    @Mock
    private WorkdayRevenueManagementClient client;

    @Mock
    private PutCustomerResponseMapper responseMapper;

    @Captor
    private ArgumentCaptor<Document> documentCaptor;

    @Captor
    private ArgumentCaptor<JAXBElement<PutCustomerRequestType>> requestCaptor;

    @Captor
    private ArgumentCaptor<PutCustomerResponseType> responseCaptor;

    private WorkdayCustomerService service;

    @BeforeEach
    void setUp() {
        service = new WorkdayCustomerService(requestMapper, client, responseMapper);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wellFormedBodies")
    @DisplayName("add_a_new_customer.xml:9-52 parses the body namespace-aware, then maps, calls Put_Customer and "
            + "answers with the descriptor, in that order")
    void runsTheFlowStepsInOrderOverANamespaceAwareDom(String description, byte[] body, String namespace,
            String prefix) throws Exception {
        JAXBElement<PutCustomerRequestType> request =
                OBJECT_FACTORY.createPutCustomerRequest(new PutCustomerRequestType());
        PutCustomerResponseType response = new PutCustomerResponseType();
        when(requestMapper.map(any(Document.class))).thenReturn(request);
        when(client.putCustomer(request)).thenReturn(response);
        when(responseMapper.descriptor(response)).thenReturn("NAME");

        String answer = service.addCustomer(body);

        assertThat(answer).isEqualTo("Added customer: NAME");
        InOrder order = inOrder(requestMapper, client, responseMapper);
        order.verify(requestMapper).map(documentCaptor.capture());
        order.verify(client).putCustomer(requestCaptor.capture());
        order.verify(responseMapper).descriptor(responseCaptor.capture());
        verifyNoMoreInteractions(requestMapper, client, responseMapper);
        assertThat(requestCaptor.getValue()).isSameAs(request);
        assertThat(responseCaptor.getValue()).isSameAs(response);

        // A DOM built without namespace awareness reports a null local name and namespace for every node, and
        // getElementsByTagNameNS matches nothing in it.
        Element root = documentCaptor.getValue().getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("root");
        assertThat(root.getNamespaceURI()).isEqualTo(namespace);
        assertThat(root.getPrefix()).isEqualTo(prefix);
        NodeList customerNames = root.getElementsByTagNameNS("*", "CustomerName");
        assertThat(customerNames.getLength()).isEqualTo(1);
        assertThat(customerNames.item(0).getNamespaceURI()).isEqualTo(namespace);
        assertThat(customerNames.item(0).getTextContent()).isEqualTo("NAME");
    }

    @Test
    @DisplayName("customer.xml reaches Put_Customer as the request PutCustomerRequestMapper builds from it")
    void sendsCustomerXmlAsThePutCustomerRequest() throws Exception {
        when(client.putCustomer(any())).thenReturn(response("NAME"));

        String answer = serviceWithRealMappers().addCustomer(customerXml());

        assertThat(answer).isEqualTo("Added customer: NAME");
        verify(client).putCustomer(requestCaptor.capture());
        verifyNoMoreInteractions(client);
        JAXBElement<PutCustomerRequestType> request = requestCaptor.getValue();
        assertThat(request.getName()).isEqualTo(new QName(WD_NS, "Put_Customer_Request"));
        CustomerWWSDataType data = request.getValue().getCustomerData();
        assertThat(data.getCustomerID()).isEqualTo("NAME");
        assertThat(data.getCustomerReferenceID()).isEqualTo("NAME");
        assertThat(data.getCustomerName()).isEqualTo("NAME");
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("NAME");
        List<CustomerCategoryObjectIDType> categoryIds = data.getCustomerCategoryReference().getID();
        assertThat(categoryIds).hasSize(1);
        assertThat(categoryIds.get(0).getType()).isEqualTo("Customer_Category_ID");
        assertThat(categoryIds.get(0).getValue()).isEqualTo("CUSTOMER_CATEGORY-5");
        assertThat(data.getCustomerStatusData()).hasSize(1);
        List<BusinessEntityStatusValueObjectIDType> statusIds =
                data.getCustomerStatusData().get(0).getCustomerStatusValueReference().getID();
        assertThat(statusIds).hasSize(1);
        assertThat(statusIds.get(0).getType()).isEqualTo("Business_Entity_Status_Value_ID");
        assertThat(statusIds.get(0).getValue()).isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"UTF-8", "ISO-8859-1", "UTF-16"})
    @DisplayName("add_a_new_customer.xml:9 reads the posted bytes in the encoding their XML declaration names")
    void readsTheBodyInTheEncodingItDeclares(String encoding) throws Exception {
        String name = "Zoë Ångström";
        byte[] body = ("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>"
                + "<root><Account>"
                + "<CustomerName>" + name + "</CustomerName>"
                + "<BusinessEntityName>" + name + "</BusinessEntityName>"
                + "</Account></root>").getBytes(Charset.forName(encoding));
        when(client.putCustomer(any())).thenReturn(response(name));

        String answer = serviceWithRealMappers().addCustomer(body);

        assertThat(answer).isEqualTo("Added customer: " + name);
        verify(client).putCustomer(requestCaptor.capture());
        CustomerWWSDataType data = requestCaptor.getValue().getValue().getCustomerData();
        assertThat(data.getCustomerName()).isEqualTo(name);
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo(name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putCustomerResponses")
    @DisplayName("add_a_new_customer.xml:44-52 answers 'Added customer: ' followed by the descriptor the response "
            + "mapper selects, the text null when it is absent")
    void answersWithTheDescriptorOfTheResponse(String description, PutCustomerResponseType response, String expected)
            throws Exception {
        when(client.putCustomer(any())).thenReturn(response);

        String answer = serviceWithRealMappers().addCustomer(customerXml());

        assertThat(answer).isEqualTo(expected);
        verify(client).putCustomer(any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBodies")
    @DisplayName("add_a_new_customer.xml:9 fails a body that is not well-formed or declares a DOCTYPE before "
            + "anything else runs")
    void rejectsTheBodyBeforeAnyStepRuns(String description, byte[] body, String message, CapturedOutput output) {
        SAXParseException failure = assertThrows(SAXParseException.class, () -> service.addCustomer(body));

        assertThat(failure).hasMessageContaining(message);
        verifyNoInteractions(requestMapper, client, responseMapper);
        assertThat(output.getErr()).doesNotContain("[Fatal Error]");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("putCustomerFailures")
    @DisplayName("add_a_new_customer.xml:42 lets every Put_Customer failure reach the caller as the same instance, "
            + "with no retry and no answer built")
    void passesPutCustomerFailuresThroughUnchanged(String description, Exception failure) throws Exception {
        JAXBElement<PutCustomerRequestType> request =
                OBJECT_FACTORY.createPutCustomerRequest(new PutCustomerRequestType());
        when(requestMapper.map(any(Document.class))).thenReturn(request);
        when(client.putCustomer(request)).thenThrow(failure);

        Exception thrown = assertThrows(Exception.class, () -> service.addCustomer(customerXml()));

        assertThat(thrown).isSameAs(failure);
        verify(requestMapper).map(any(Document.class));
        verify(client).putCustomer(request);
        verifyNoMoreInteractions(requestMapper, client);
        verifyNoInteractions(responseMapper);
    }

    @Test
    @DisplayName("the parser's error handler ignores warnings and rethrows errors and fatal errors unchanged")
    void errorHandlerIgnoresWarningsAndRethrowsErrors() {
        WorkdayCustomerService.RethrowingErrorHandler handler = new WorkdayCustomerService.RethrowingErrorHandler();
        SAXParseException warning = new SAXParseException("warning", null);
        SAXParseException error = new SAXParseException("error", null);
        SAXParseException fatalError = new SAXParseException("fatal error", null);

        assertDoesNotThrow(() -> handler.warning(warning));
        assertThat(assertThrows(SAXParseException.class, () -> handler.error(error))).isSameAs(error);
        assertThat(assertThrows(SAXParseException.class, () -> handler.fatalError(fatalError))).isSameAs(fatalError);
    }

    static Stream<Arguments> wellFormedBodies() throws IOException {
        return Stream.of(
                Arguments.of("customer.xml, the un-namespaced root.xsd document", customerXml(), null, null),
                Arguments.of("root in a default namespace",
                        utf8("<root xmlns=\"" + CUSTOMER_NS + "\"><Account><CustomerName>NAME</CustomerName>"
                                + "</Account></root>"),
                        CUSTOMER_NS, null),
                Arguments.of("root under a bound prefix",
                        utf8("<c:root xmlns:c=\"" + CUSTOMER_NS + "\"><c:Account><c:CustomerName>NAME"
                                + "</c:CustomerName></c:Account></c:root>"),
                        CUSTOMER_NS, "c"));
    }

    static Stream<Arguments> putCustomerResponses() {
        return Stream.of(
                Arguments.of("Customer_Reference with a Descriptor", response("NAME"), "Added customer: NAME"),
                Arguments.of("Descriptor kept as sent, untrimmed", response(" Acme & Co "),
                        "Added customer:  Acme & Co "),
                Arguments.of("empty Descriptor", response(""), "Added customer: "),
                Arguments.of("Customer_Reference without a Descriptor", response(null), "Added customer: null"),
                Arguments.of("no Customer_Reference", new PutCustomerResponseType(), "Added customer: null"),
                Arguments.of("empty Put_Customer response body", null, "Added customer: null"));
    }

    static Stream<Arguments> rejectedBodies() {
        return Stream.of(
                Arguments.of("absent body", null, "Premature end of file."),
                Arguments.of("empty body", new byte[0], "Premature end of file."),
                Arguments.of("plain text", utf8("CustomerName=NAME"), "Content is not allowed in prolog."),
                Arguments.of("unclosed element", utf8("<root><Account>"),
                        "XML document structures must start and end within the same entity."),
                Arguments.of("unbound prefix", utf8("<c:root><c:Account/></c:root>"),
                        "The prefix \"c\" for element \"c:root\" is not bound."),
                Arguments.of("DOCTYPE declaration", utf8("<?xml version=\"1.0\"?><!DOCTYPE root><root/>"),
                        "DOCTYPE is disallowed"),
                Arguments.of("external entity",
                        utf8("<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                                + "<root><Account><CustomerName>&xxe;</CustomerName></Account></root>"),
                        "DOCTYPE is disallowed"),
                Arguments.of("nested entity expansion",
                        utf8("<!DOCTYPE root [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
                                + "<root><Account><CustomerName>&b;</CustomerName></Account></root>"),
                        "DOCTYPE is disallowed"));
    }

    static Stream<Arguments> putCustomerFailures() throws Exception {
        WorkdayRevenueManagementClient.HttpStatusException unauthorized =
                new WorkdayRevenueManagementClient.HttpStatusException("Unauthorized [401]", 401, null);
        WorkdayRevenueManagementClient.HttpStatusException throttled =
                new WorkdayRevenueManagementClient.HttpStatusException("Too Many Requests [429]", 429, "30");
        return Stream.of(
                Arguments.of("UpstreamUnavailableException",
                        new UpstreamUnavailableException(UpstreamSystem.WORKDAY, "Read timed out",
                                new SocketTimeoutException("Read timed out"))),
                Arguments.of("UpstreamAuthenticationException",
                        new UpstreamAuthenticationException(UpstreamSystem.WORKDAY, "Unauthorized [401]",
                                unauthorized)),
                Arguments.of("UpstreamRateLimitException",
                        new UpstreamRateLimitException(UpstreamSystem.WORKDAY, "Too Many Requests [429]", "30",
                                throttled)),
                Arguments.of("SoapFaultClientException", validationFault()),
                Arguments.of("unexpected response body",
                        new IllegalStateException("Unexpected Workday Put_Customer response: java.lang.String")));
    }

    private WorkdayCustomerService serviceWithRealMappers() {
        return new WorkdayCustomerService(new PutCustomerRequestMapper(), client, new PutCustomerResponseMapper());
    }

    private static PutCustomerResponseType response(String descriptor) {
        CustomerObjectType reference = new CustomerObjectType();
        reference.setDescriptor(descriptor);
        PutCustomerResponseType response = new PutCustomerResponseType();
        response.setCustomerReference(reference);
        return response;
    }

    /** Workday's validation fault, parsed from a SOAP 1.1 envelope and wrapped as Spring WS raises it. */
    private static SoapFaultClientException validationFault() throws Exception {
        String envelope = "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_11_NS + "\">"
                + "<SOAP-ENV:Body>"
                + "<SOAP-ENV:Fault xmlns:wd=\"" + WD_NS + "\">"
                + "<faultcode>SOAP-ENV:Client.validationError</faultcode>"
                + "<faultstring>" + VALIDATION_FAULT_STRING + "</faultstring>"
                + "</SOAP-ENV:Fault>"
                + "</SOAP-ENV:Body>"
                + "</SOAP-ENV:Envelope>";
        SaajSoapMessageFactory factory =
                new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        factory.afterPropertiesSet();
        SoapFaultClientException fault =
                new SoapFaultClientException(factory.createWebServiceMessage(new ByteArrayInputStream(utf8(envelope))));
        assertThat(fault).hasMessage(VALIDATION_FAULT_STRING);
        return fault;
    }

    private static byte[] customerXml() throws IOException {
        try (InputStream in = WorkdayCustomerServiceTest.class.getResourceAsStream("/customer.xml")) {
            if (in == null) {
                throw new IllegalStateException("customer.xml is not on the test classpath");
            }
            return in.readAllBytes();
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
