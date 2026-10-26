package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import java.io.StringReader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

/**
 * Converts the XML text of a JMS message into an {@link Order}, replacing the Mule transformer
 * {@code mulexml:xml-to-object-transformer} "Convert XML to Order", which returned the original example's
 * {@code Order} class under the alias {@code order}
 * ({@code using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml} lines 8-10).
 * <p>
 * The accepted document is the one the alias defined: root element {@code order} without a namespace, children
 * {@code itemId}, {@code itemUnits} and {@code customerId} holding integers. Mule's XStream-based transformer
 * failed on an unknown element or an unparsable integer and left an absent field at its Java default; the strict
 * unmarshalling below has the same outcomes, so an absent child yields {@code 0}. Integer text is converted by the
 * JAXB runtime, which rejects any character other than digits, signs and whitespace but does not range-check, so a
 * value beyond the {@code int} range wraps. That is accepted rather than adding a schema beside the model's bindings: such a message is rolled
 * back by the transaction's failing component either way.
 * <p>
 * In the original flow the transformer runs before {@code ee:multi-transactional}, so this conversion runs outside
 * the database transaction. A failure is thrown as {@link IllegalArgumentException} and is neither logged nor
 * retried here: it leaves the session-transacted JMS listener, which rolls the message back for redelivery, as
 * the platform did for a flow without its own exception strategy.
 * <p>
 * Thread-safe: the {@link JAXBContext} is shared, while an {@link Unmarshaller} and a StAX factory are created
 * per call because neither is guaranteed to be thread-safe.
 */
@Component
public class OrderXmlMapper {

    private static final String FAILURE_PREFIX = "Failed to convert XML to Order: ";

    private final JAXBContext context;

    public OrderXmlMapper() {
        try {
            this.context = JAXBContext.newInstance(Order.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to create JAXBContext for Order", e);
        }
    }

    /**
     * Unmarshals one {@code order} document.
     *
     * @param xml the message text, used as received (no trimming or declaration stripping)
     * @return the order the document describes
     * @throws IllegalArgumentException when the input is empty, is not well-formed XML, has a root other than
     *         {@code order}, contains an unknown element or a non-integer value, or references an entity other
     *         than the predefined XML ones (DTDs are not processed, so no declared entity is ever expanded)
     */
    public Order toOrder(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException(FAILURE_PREFIX + "input is empty");
        }
        XMLStreamReader reader = null;
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            // Any installed handler makes the JAXB RI report unexpected elements and integer parse failures, which
            // it otherwise skips silently; answering false aborts the unmarshal on the first such event.
            unmarshaller.setEventHandler(event -> false);
            reader = newSecureInputFactory().createXMLStreamReader(new StringReader(xml));
            // The root-checking overload: a Class-taking overload would accept any root element name.
            return (Order) unmarshaller.unmarshal(reader);
        } catch (JAXBException | XMLStreamException | ClassCastException e) {
            throw new IllegalArgumentException(FAILURE_PREFIX + describe(e), e);
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * The JDK StAX implementation, pinned so behaviour does not depend on a provider on the classpath, with DTDs
     * and external entities disabled so a message cannot make the parser read files or URLs.
     */
    private static XMLInputFactory newSecureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return factory;
    }

    /**
     * JAXB's {@code UnmarshalException} usually carries no message of its own, only a linked exception, which
     * {@link JAXBException#getCause()} returns; the cause's message is therefore the fallback for every type caught.
     */
    private static String describe(Exception e) {
        if (e.getMessage() != null) {
            return e.getMessage();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return e.getClass().getName();
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException closeFailure) {
            // Deliberately ignored: the reader wraps an in-memory StringReader, so nothing leaks, and a close
            // failure must not replace the conversion's own result or exception.
        }
    }
}
