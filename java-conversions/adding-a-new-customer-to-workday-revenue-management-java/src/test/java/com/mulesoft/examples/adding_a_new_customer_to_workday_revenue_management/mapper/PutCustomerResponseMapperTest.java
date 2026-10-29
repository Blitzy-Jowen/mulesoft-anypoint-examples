package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.stream.Stream;

import javax.xml.transform.stream.StreamSource;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectIDType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.CustomerObjectType;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.ObjectFactory;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.generated.PutCustomerResponseType;

/**
 * Contract of {@link PutCustomerResponseMapper}, the owner of the "Extract Customer Name" script at line 44 of
 * {@code add_a_new_customer.xml}:
 * {@code payload.ns0#Put_Customer_Response.ns0#Customer_Reference.@ns0#Descriptor} with
 * {@code %output application/java}. The descriptor attribute comes back exactly as Workday sent it; an absent
 * response, {@code Customer_Reference} or {@code Descriptor} yields the text {@code "null"}. The
 * {@code Added customer: } prefix of the flow's response belongs to the service and is not asserted here.
 * <p>
 * The mapper is built directly over the XJC-generated Workday v25.0 classes; no Spring context is started.
 */
final class PutCustomerResponseMapperTest {

    private static final String WD_NS = "urn:com.workday/bsvc";

    /** A {@code Put_Customer_Response} as Workday returns it: qualified elements and qualified attributes. */
    private static final String RESPONSE_WITH_REFERENCE = "<wd:Put_Customer_Response xmlns:wd=\"" + WD_NS + "\""
            + " wd:version=\"v25.0\">"
            + "<wd:Customer_Reference wd:Descriptor=\"John1700000000000\">"
            + "<wd:ID wd:type=\"WID\">0123456789abcdef</wd:ID>"
            + "<wd:ID wd:type=\"Customer_ID\">John1700000000000</wd:ID>"
            + "</wd:Customer_Reference>"
            + "</wd:Put_Customer_Response>";

    /** The same response without the optional {@code Customer_Reference} (0..1 in the v25.0 schema). */
    private static final String RESPONSE_WITHOUT_REFERENCE = "<wd:Put_Customer_Response xmlns:wd=\"" + WD_NS + "\""
            + " wd:version=\"v25.0\"/>";

    /** Built once: the context spans every generated Workday class and is thread-safe. */
    private static JAXBContext workdayContext;

    private final PutCustomerResponseMapper mapper = new PutCustomerResponseMapper();

    @BeforeAll
    static void createContext() throws JAXBException {
        workdayContext = JAXBContext.newInstance(ObjectFactory.class);
    }

    @ParameterizedTest(name = "descriptor \"{0}\" is returned unchanged")
    @ValueSource(strings = {"Acme", ""})
    @DisplayName("add_a_new_customer.xml:44 returns the Customer_Reference descriptor as sent, an empty one included")
    void returnsTheDescriptorUnchanged(String descriptor) {
        assertThat(mapper.descriptor(response(descriptor))).isEqualTo(descriptor);
    }

    @Test
    @DisplayName("add_a_new_customer.xml:44 reads the Descriptor attribute and ignores the reference IDs")
    void readsTheDescriptorAttributeNotTheIds() {
        PutCustomerResponseType response = response("Acme");
        response.getCustomerReference().getID().add(id("WID", "abc123"));
        response.getCustomerReference().getID().add(id("Customer_ID", "Acme"));

        assertThat(mapper.descriptor(response)).isEqualTo("Acme");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentDescriptors")
    @DisplayName("add_a_new_customer.xml:44 selects null, rendered as the text \"null\", when any step is absent")
    void rendersAnAbsentDescriptorAsNullText(String description, PutCustomerResponseType response) {
        assertThat(mapper.descriptor(response)).isEqualTo("null");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workdayResponses")
    @DisplayName("add_a_new_customer.xml:44 over a Put_Customer_Response unmarshalled from Workday's wire form")
    void readsTheDescriptorFromTheWireForm(String description, String xml, String expected) throws JAXBException {
        assertThat(mapper.descriptor(unmarshal(xml))).isEqualTo(expected);
    }

    static Stream<Arguments> absentDescriptors() {
        return Stream.of(
                Arguments.of("no Put_Customer_Response", null),
                Arguments.of("no Customer_Reference", new PutCustomerResponseType()),
                Arguments.of("no Descriptor attribute", response(null)));
    }

    static Stream<Arguments> workdayResponses() {
        return Stream.of(
                Arguments.of("Customer_Reference with wd:Descriptor", RESPONSE_WITH_REFERENCE, "John1700000000000"),
                Arguments.of("Customer_Reference absent", RESPONSE_WITHOUT_REFERENCE, "null"));
    }

    private static PutCustomerResponseType response(String descriptor) {
        CustomerObjectType reference = new CustomerObjectType();
        reference.setDescriptor(descriptor);
        PutCustomerResponseType response = new PutCustomerResponseType();
        response.setCustomerReference(reference);
        return response;
    }

    private static CustomerObjectIDType id(String type, String value) {
        CustomerObjectIDType id = new CustomerObjectIDType();
        id.setType(type);
        id.setValue(value);
        return id;
    }

    private static PutCustomerResponseType unmarshal(String xml) throws JAXBException {
        return workdayContext.createUnmarshaller()
                .unmarshal(new StreamSource(new StringReader(xml)), PutCustomerResponseType.class)
                .getValue();
    }
}
