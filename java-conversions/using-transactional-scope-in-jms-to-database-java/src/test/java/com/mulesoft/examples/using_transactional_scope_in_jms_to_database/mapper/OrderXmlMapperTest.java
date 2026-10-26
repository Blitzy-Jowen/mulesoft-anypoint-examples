/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

/**
 * Unit test of {@link OrderXmlMapper}, the replacement of the flow's {@code mulexml:xml-to-object-transformer}
 * "Convert XML to Order" with alias {@code order} ({@code transactions.xml} lines 8-10).
 *
 * <p>The success case reads the committed {@code message.xml}, the message the original test sent to queue
 * {@code in}; its expected values are that document's children and the original {@code Order.toString()}.
 * Every rejected input must surface as {@link IllegalArgumentException} with the mapper's message prefix, so the
 * transacted listener rolls the message back as the platform did.
 */
class OrderXmlMapperTest {

    private static final String PREFIX = "Failed to convert XML to Order: ";

    private final OrderXmlMapper mapper = new OrderXmlMapper();

    @Test
    void convertsClasspathMessageXml() throws IOException {
        String xml;
        try (InputStream in = new ClassPathResource("message.xml").getInputStream()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Order order = mapper.toOrder(xml);

        assertThat(order).isNotNull();
        assertThat(order.getItemId()).isEqualTo(1);
        assertThat(order.getItemUnits()).isEqualTo(2);
        assertThat(order.getCustomerId()).isEqualTo(1);
        assertThat(order.toString()).isEqualTo("Order [item_id=1, item_units=2, customer_id=1]");
    }

    static Stream<Arguments> documentsWithMissingChild() {
        return Stream.of(
                Arguments.of("<order><itemId>7</itemId><itemUnits>3</itemUnits></order>", 7, 3, 0),
                Arguments.of("<order><itemId>7</itemId><customerId>3</customerId></order>", 7, 0, 3));
    }

    @ParameterizedTest
    @MethodSource("documentsWithMissingChild")
    void missingChildDefaultsToZero(String xml, int itemId, int itemUnits, int customerId) {
        Order order = mapper.toOrder(xml);

        assertThat(order.getItemId()).isEqualTo(itemId);
        assertThat(order.getItemUnits()).isEqualTo(itemUnits);
        assertThat(order.getCustomerId()).isEqualTo(customerId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsEmptyInput(String xml) {
        assertThatThrownBy(() -> mapper.toOrder(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX);
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                // Rejected while the StAX reader is created, before unmarshalling starts.
                Arguments.of("XML declaration without version",
                        "<?xml encoding=\"UTF-8\"?><order><itemId>1</itemId><itemUnits>2</itemUnits>"
                                + "<customerId>1</customerId></order>"),
                Arguments.of("unclosed root", "<order><itemId>1</itemId>"),
                Arguments.of("mismatched end tag", "<order><itemId>1</order>"),
                Arguments.of("root other than order",
                        "<purchase><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId></purchase>"),
                Arguments.of("unknown child element",
                        "<order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId>"
                                + "<discount>5</discount></order>"),
                Arguments.of("non-numeric value",
                        "<order><itemId>abc</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsInvalidDocument(String description, String xml) {
        assertThatThrownBy(() -> mapper.toOrder(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX)
                .cause().isNotNull();
    }

    @Test
    void rejectsExternalEntityWithoutLeakingContent(@TempDir Path tmp) throws IOException {
        String marker = "XXE-MARKER-" + UUID.randomUUID();
        Path markerFile = Files.writeString(tmp.resolve("secret.txt"), marker);
        String xml = "<!DOCTYPE order [<!ENTITY xxe SYSTEM \"" + markerFile.toUri() + "\">]>"
                + "<order><itemId>&xxe;</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>";

        Throwable thrown = catchThrowable(() -> mapper.toOrder(xml));

        assertThat(thrown)
                .as("an external entity must not be resolved into an Order")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(PREFIX);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.toString()).doesNotContain(marker);
            if (t.getMessage() != null) {
                assertThat(t.getMessage()).doesNotContain(marker);
            }
        }
    }
}
