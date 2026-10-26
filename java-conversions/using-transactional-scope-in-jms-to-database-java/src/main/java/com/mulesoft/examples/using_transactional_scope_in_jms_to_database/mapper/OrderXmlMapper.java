package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import java.io.StringReader;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

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
 * unmarshalling below has the same outcomes, so an absent child yields {@code 0}. The text of each of those three
 * children (character data, CDATA and entities concatenated; comments and processing instructions skipped) is
 * converted the way XStream's {@code IntConverter} did: {@link Long#decode(String)}, which also accepts the octal,
 * hexadecimal, {@code #}-prefixed and non-ASCII digit forms, followed by its range check of
 * [{@link Integer#MIN_VALUE}, {@code 0xFFFFFFFF}], with a value above {@link Integer#MAX_VALUE} taken as its 32-bit
 * two's complement. Whitespace anywhere in the text, a misplaced or repeated sign, a sign alone and empty text are
 * therefore rejected. A plain decimal beyond that range is the one exception: it keeps the JAXB runtime's
 * conversion, which wraps it into the {@code int} range where XStream failed the conversion. That difference is
 * accepted because such a message is rolled back by the transaction's failing component either way.
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
     *         {@code order}, contains an unknown element, has an {@code itemId}, {@code itemUnits} or
     *         {@code customerId} whose text is not an integer in the form and range described above, or references
     *         an entity other than the predefined XML ones (DTDs are not processed, so no declared entity is ever
     *         expanded)
     */
    public Order toOrder(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException(FAILURE_PREFIX + "input is empty");
        }
        XMLStreamReader reader = null;
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            // Any installed handler makes the JAXB RI report unexpected elements, which it otherwise skips silently;
            // answering false aborts the unmarshal on the first such event. Integer text never reaches the JAXB
            // integer parser unchecked: IntegerTextReader converts or rejects it first.
            unmarshaller.setEventHandler(event -> false);
            reader = new IntegerTextReader(newSecureInputFactory().createXMLStreamReader(new StringReader(xml)));
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

    /**
     * Presents the text of each integer child of {@code order} to the JAXB runtime as XStream's {@code IntConverter}
     * converted it. When the cursor leaves the start tag of a direct child of the root named {@code itemId},
     * {@code itemUnits} or {@code customerId} without a namespace, the element's whole text is read and converted,
     * and one {@code CHARACTERS} event carrying the converted text replaces the element's content; the event after
     * it is the element's end tag. Every other event passes through unchanged. {@link #getElementText()} and
     * {@link #nextTag()} move the cursor through {@link #next()}, so no path skips the conversion.
     * <p>
     * The wrapped reader must stand at the start of the document or on the root's start tag. Not thread-safe, like
     * the reader it wraps.
     */
    static final class IntegerTextReader extends StreamReaderDelegate {

        private static final Set<String> INTEGER_ELEMENTS = Set.of("itemId", "itemUnits", "customerId");

        private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?[1-9][0-9]*");

        private static final long UNSIGNED_INT_MAX = 0xFFFFFFFFL;

        /** Depth of the current element: 1 on the root's start tag, 2 on a child's, one less on an end tag. */
        private int depth;

        /** The converted text while the synthesized {@code CHARACTERS} event is current, otherwise {@code null}. */
        private String synthesized;

        IntegerTextReader(XMLStreamReader reader) {
            super(reader);
            this.depth = reader.isStartElement() ? 1 : 0;
        }

        /**
         * The text presented for an integer element: XStream's {@code IntConverter} result in decimal, or the text
         * unchanged for a plain decimal beyond that converter's range.
         *
         * @throws XMLStreamException naming the element but not its text, with the {@link NumberFormatException} as
         *         cause, when the text is not an integer
         */
        private static String toIntegerText(String element, String text) throws XMLStreamException {
            NumberFormatException failure;
            try {
                long value = Long.decode(text);
                if (value >= Integer.MIN_VALUE && value <= UNSIGNED_INT_MAX) {
                    return Integer.toString((int) value);
                }
                failure = new NumberFormatException(
                        "value outside [" + Integer.MIN_VALUE + ", " + UNSIGNED_INT_MAX + "]");
            } catch (NumberFormatException e) {
                failure = e;
            }
            // A plain decimal beyond that range is handed on unchanged so that the JAXB runtime wraps it into the
            // int range, the out-of-range contract this mapper documents, where XStream failed the conversion.
            if (PLAIN_DECIMAL.matcher(text).matches()) {
                return text;
            }
            throw new XMLStreamException("element " + element + " does not hold an integer", failure);
        }

        @Override
        public int next() throws XMLStreamException {
            if (synthesized != null) {
                // The wrapped reader already stands on the end tag at which its getElementText() stopped.
                synthesized = null;
                depth--;
                return XMLStreamConstants.END_ELEMENT;
            }
            if (isIntegerElementStart()) {
                String element = super.getLocalName();
                String text = super.getElementText();
                try {
                    synthesized = toIntegerText(element, text);
                } catch (XMLStreamException rejected) {
                    // The cursor is left on the element's end tag, where the wrapped reader stopped.
                    depth--;
                    throw rejected;
                }
                return XMLStreamConstants.CHARACTERS;
            }
            int event = super.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
            return event;
        }

        private boolean isIntegerElementStart() {
            if (depth != 2 || super.getEventType() != XMLStreamConstants.START_ELEMENT) {
                return false;
            }
            String namespace = super.getNamespaceURI();
            return (namespace == null || XMLConstants.NULL_NS_URI.equals(namespace))
                    && INTEGER_ELEMENTS.contains(super.getLocalName());
        }

        /** The reference algorithm of {@link XMLStreamReader#getElementText()}, driven by {@link #next()}. */
        @Override
        public String getElementText() throws XMLStreamException {
            if (getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("parser must be on START_ELEMENT to read next text", getLocation());
            }
            StringBuilder content = new StringBuilder();
            for (int event = next(); event != XMLStreamConstants.END_ELEMENT; event = next()) {
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    content.append(getText());
                } else if (event != XMLStreamConstants.PROCESSING_INSTRUCTION
                        && event != XMLStreamConstants.COMMENT) {
                    throw new XMLStreamException(
                            "element text content may not contain event type " + event, getLocation());
                }
            }
            return content.toString();
        }

        /** The reference algorithm of {@link XMLStreamReader#nextTag()}, driven by {@link #next()}. */
        @Override
        public int nextTag() throws XMLStreamException {
            int event = next();
            while (((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && isWhiteSpace())
                    || event == XMLStreamConstants.SPACE || event == XMLStreamConstants.PROCESSING_INSTRUCTION
                    || event == XMLStreamConstants.COMMENT) {
                event = next();
            }
            if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                throw new XMLStreamException("expected start or end tag but found event type " + event, getLocation());
            }
            return event;
        }

        @Override
        public int getEventType() {
            return synthesized != null ? XMLStreamConstants.CHARACTERS : super.getEventType();
        }

        @Override
        public boolean isCharacters() {
            return synthesized != null || super.isCharacters();
        }

        @Override
        public boolean isStartElement() {
            return synthesized == null && super.isStartElement();
        }

        @Override
        public boolean isEndElement() {
            return synthesized == null && super.isEndElement();
        }

        @Override
        public boolean hasText() {
            return synthesized != null || super.hasText();
        }

        @Override
        public boolean hasName() {
            return synthesized == null && super.hasName();
        }

        /** The synthesized text is an integer literal, which is never whitespace. */
        @Override
        public boolean isWhiteSpace() {
            return synthesized == null && super.isWhiteSpace();
        }

        @Override
        public String getText() {
            return synthesized != null ? synthesized : super.getText();
        }

        @Override
        public char[] getTextCharacters() {
            return synthesized != null ? synthesized.toCharArray() : super.getTextCharacters();
        }

        @Override
        public int getTextCharacters(int sourceStart, char[] target, int targetStart, int length)
                throws XMLStreamException {
            if (synthesized == null) {
                return super.getTextCharacters(sourceStart, target, targetStart, length);
            }
            Objects.checkFromIndexSize(targetStart, length, target.length);
            if (sourceStart < 0) {
                throw new IndexOutOfBoundsException("sourceStart " + sourceStart + " is negative");
            }
            int from = Math.min(sourceStart, synthesized.length());
            int count = Math.min(length, synthesized.length() - from);
            synthesized.getChars(from, from + count, target, targetStart);
            return count;
        }

        @Override
        public int getTextStart() {
            return synthesized != null ? 0 : super.getTextStart();
        }

        @Override
        public int getTextLength() {
            return synthesized != null ? synthesized.length() : super.getTextLength();
        }
    }
}
