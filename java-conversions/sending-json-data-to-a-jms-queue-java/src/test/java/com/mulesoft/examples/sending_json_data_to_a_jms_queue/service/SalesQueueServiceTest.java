/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jms.JmsException;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception.UndecodableFormException;

/**
 * Unit test of {@link SalesQueueService}, which replaces the {@code byte-array-to-string-transformer}, the one-way
 * {@code jms:outbound-endpoint queue="sales"} and the {@code logger level="INFO"} of flow {@code json-to-jmsFlow}
 * (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:8-10), with a mocked {@link JmsTemplate}: no
 * Spring context and no broker.
 *
 * <p>The byte path, {@link SalesQueueService#send(byte[], String)}, is the transformer: its tests cover the charset
 * a {@code Content-Type} names, the JVM's default charset taken when it names none, and every header from which
 * UTF-8 is taken instead, assert the text queued and the bytes returned, and assert that a failed send or an
 * absent body logs nothing. {@link SalesQueueService#responseContentType(String)} is checked against the
 * {@code Content-Type} the original listener derived from each of those headers.
 *
 * <p>The form path, {@link SalesQueueService#sendForm(byte[], String)}, is the listener's parameter map handed to
 * the transformer: its expected texts are the output of the real Mule 3.8.0 classes for the same bodies, and a
 * {@code Content-Type} the listener's Guava 18.0 parser rejected and an undecodable body are each asserted to fail
 * with the listener's status and message before anything is sent or logged.
 * {@link SalesQueueService#isFormUrlEncoded} is checked against the headers the listener decoded as a form.
 *
 * <p>The original logger declares no message, so it logged Mule's description of the message, which names the
 * payload's class and never the body. The log tests therefore assert that description and the body's absence
 * rather than the body itself, and prove the flow order by its presence after a successful send and its absence
 * after a failed one.
 *
 * <p>{@link CapturedOutput} also holds the output of the methods that ran earlier in this class, so the log
 * tests read the last description written, or compare counts taken before and after the call, never a bare
 * {@code contains}. Each case sends its own payload literal, so no assertion can match another case's output.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SalesQueueServiceTest {

    private static final String SALES = "sales";

    /**
     * First line of every message description the service logs. The Mule class names in this class are text of
     * that log line, not references to Mule types; the project has no Mule dependency.
     */
    private static final String DESCRIPTION_HEADER = "org.mule.DefaultMuleMessage";

    /** Lines in one description: header, braces, seven message fields, a blank line and the properties section. */
    private static final int DESCRIPTION_LINES = 16;

    /** A description's id line: a version 4 UUID as {@link java.util.UUID#toString()} renders it. */
    private static final String ID_LINE = "  id=[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Mock
    private JmsTemplate jmsTemplate;

    @Test
    void sendReturnsSamePayloadAndSendsOnceToSales() {
        // A fresh instance, so assertSame proves the payload itself is returned and sent, not an equal copy.
        String payload = new String("{ \"ITEM_ID\"=\"001\", \"case\"=\"happy\" }");

        String result = new SalesQueueService(jmsTemplate, SALES).send(payload);

        assertSame(payload, result);
        verify(jmsTemplate).convertAndSend(eq(SALES), same(payload));
        verifyNoMoreInteractions(jmsTemplate);
    }

    @Test
    void sendUsesConfiguredQueue() {
        String payload = "orders-payload-2";

        new SalesQueueService(jmsTemplate, "orders").send(payload);

        verify(jmsTemplate).convertAndSend(eq("orders"), same(payload));
        verify(jmsTemplate, never()).convertAndSend(eq(SALES), ArgumentMatchers.<Object>any());
        verifyNoMoreInteractions(jmsTemplate);
    }

    @Test
    void sendPropagatesJmsExceptionUnchanged() {
        UncategorizedJmsException ex = new UncategorizedJmsException("broker down");
        doThrow(ex).when(jmsTemplate).convertAndSend(anyString(), any(Object.class));
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);

        JmsException thrown = assertThrows(JmsException.class, () -> service.send("failing-payload-3"));

        assertSame(ex, thrown);
    }

    /** A request body with the payload class its description names; an absent body, which Mule held as NullPayload. */
    static Stream<Arguments> loggedPayloads() {
        return Stream.of(
                Arguments.of("{ \"ITEM_ID\"=\"004\", \"case\"=\"logged-body\" }", "java.lang.String"),
                Arguments.of(null, "org.mule.transport.NullPayload"));
    }

    @ParameterizedTest
    @MethodSource("loggedPayloads")
    void logsMessageDescriptionAtInfoAfterSuccessfulSend(String payload, String payloadClass, CapturedOutput output) {
        new SalesQueueService(jmsTemplate, SALES).send(payload);

        verify(jmsTemplate).convertAndSend(SALES, payload);
        List<String> lines = output.getOut().lines().toList();
        int header = lines.lastIndexOf(DESCRIPTION_HEADER);
        assertThat(header).as("index of the description header in:%n%s", output.getOut()).isPositive();
        // The message opens with a line separator, so the event's level and logger sit alone on the line above.
        assertThat(lines.get(header - 1)).contains("INFO").contains("SalesQueueService");
        List<String> description = lines.subList(header, Math.min(lines.size(), header + DESCRIPTION_LINES));
        String idLine = description.size() > 2 ? description.get(2) : "";
        assertThat(idLine).matches(ID_LINE);
        assertThat(description).containsExactly(
                DESCRIPTION_HEADER,
                "{",
                idLine,
                "  payload=" + payloadClass,
                "  correlationId=<not set>",
                "  correlationGroup=-1",
                "  correlationSeq=-1",
                "  encoding=UTF-8",
                "  exceptionPayload=<not set>",
                "",
                "Message properties:",
                "  INVOCATION scoped properties:",
                "  INBOUND scoped properties:",
                "  OUTBOUND scoped properties:",
                "  SESSION scoped properties:",
                "}");
        if (payload != null) {
            assertThat(output.getAll()).as("request body kept out of the log").doesNotContain(payload);
        }
    }

    @Test
    void eachDescriptionCarriesItsOwnId(CapturedOutput output) {
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);

        service.send("first-id-payload-5");
        service.send("second-id-payload-6");

        // The last two id lines are this method's two sends.
        List<String> ids = output.getOut().lines().filter(line -> line.matches(ID_LINE)).toList();
        assertThat(ids).hasSizeGreaterThanOrEqualTo(2);
        assertThat(ids.get(ids.size() - 1)).isNotEqualTo(ids.get(ids.size() - 2));
    }

    @Test
    void logsNothingWhenSendFails(CapturedOutput output) {
        String payload = "failing-payload-7";
        doThrow(new UncategorizedJmsException("broker down"))
                .when(jmsTemplate).convertAndSend(anyString(), any(Object.class));
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        assertThrows(JmsException.class, () -> service.send(payload));

        assertThat(descriptionCount(output)).as("descriptions logged by the failed send").isEqualTo(descriptionsBefore);
        assertThat(output.getAll()).doesNotContain(payload);
    }

    /**
     * A request's {@code Content-Type}, its raw body and the text the transformer must queue. The UTF-8 bodies
     * carry a multi-byte character, so decoding them with any other charset would queue different text. A
     * header that names no charset takes the JVM's default charset, so those bodies are encoded with it and the
     * expected text is what that charset holds of the original.
     */
    static Stream<Arguments> bytePathBodies() {
        String json = "{\n\t\"ITEM_ID\"= 008, \n\t\"ITEM_NAME\" = \"Shirt\u2122\", \n\t\"QTY\" = 1, "
                + "\n\t\"PRICE\" = 20\n}";
        String noCharset = "no-charset-payload-14 \u20ac";
        return Stream.of(
                // No charset parameter on a JSON type: the JVM's default charset, as the original listener set it.
                Arguments.of("application/json", jvmDefault(json), inJvmDefault(json)),
                // The named charset: four ISO-8859-1 bytes, the last 0xE9 for the e acute.
                Arguments.of("text/plain; charset=ISO-8859-1", new byte[] {'C', 'a', 'f', (byte) 0xE9}, "Caf\u00e9"),
                // The same, with the parameter name in another letter case.
                Arguments.of("Text/Plain; Charset=ISO-8859-1", new byte[] {'C', 'a', 'f', (byte) 0xE9}, "Caf\u00e9"),
                Arguments.of(null, utf8("null-type-payload-9 \u20ac"), "null-type-payload-9 \u20ac"),
                Arguments.of("   ", utf8("blank-type-payload-10 \u20ac"), "blank-type-payload-10 \u20ac"),
                Arguments.of(";;;", utf8("unparseable-type-payload-11 \u20ac"), "unparseable-type-payload-11 \u20ac"),
                Arguments.of("text/plain; charset=bogus", utf8("unsupported-charset-payload-12 \u20ac"),
                        "unsupported-charset-payload-12 \u20ac"),
                Arguments.of("text/plain; CHARSET=bogus", utf8("unsupported-charset-payload-16 \u20ac"),
                        "unsupported-charset-payload-16 \u20ac"),
                Arguments.of("text/plain; charset=x*y", utf8("illegal-charset-payload-13 \u20ac"),
                        "illegal-charset-payload-13 \u20ac"),
                Arguments.of("text/plain", jvmDefault(noCharset), inJvmDefault(noCharset)),
                Arguments.of("application/json", new byte[0], ""));
    }

    @ParameterizedTest(name = "{index}: Content-Type [{0}]")
    @MethodSource("bytePathBodies")
    void bytePathQueuesDecodedTextAndReturnsSameBytes(String contentType, byte[] body, String expectedText) {
        byte[] result = new SalesQueueService(jmsTemplate, SALES).send(body, contentType);

        verify(jmsTemplate).convertAndSend(eq(SALES), eq(expectedText));
        verifyNoMoreInteractions(jmsTemplate);
        assertThat(result).containsExactly(body);
    }

    /**
     * A request's {@code Content-Type} and the response {@code Content-Type} the original listener derived from
     * it: the lower-cased {@code type/subtype}, {@code "; charset="} and the charset's canonical name, or none
     * ({@code null}) when the request has none or it is <code>&#42;/&#42;</code>. The {@code charset} parameter
     * name is read ignoring case, as the original read it. Unparseable headers send none, and an unusable charset
     * is replaced by UTF-8, the plan defaults the byte path decodes with.
     */
    static Stream<Arguments> responseContentTypes() {
        String jvmDefault = "; charset=" + Charset.defaultCharset().name();
        return Stream.of(
                Arguments.of("application/json", "application/json" + jvmDefault),
                Arguments.of("APPLICATION/JSON", "application/json" + jvmDefault),
                Arguments.of("application/json; foo=bar", "application/json" + jvmDefault),
                Arguments.of("application/xml", "application/xml" + jvmDefault),
                Arguments.of("text/*", "text/*" + jvmDefault),
                Arguments.of("application/json; charset=UTF-8", "application/json; charset=UTF-8"),
                Arguments.of("Text/Plain;Charset=ISO-8859-1", "text/plain; charset=ISO-8859-1"),
                Arguments.of("text/plain; charset=ISO-8859-1", "text/plain; charset=ISO-8859-1"),
                Arguments.of("text/plain; charset=utf-8", "text/plain; charset=UTF-8"),
                Arguments.of("text/plain; charset=\"latin1\"", "text/plain; charset=ISO-8859-1"),
                Arguments.of("text/plain; CHARSET=\"latin1\"", "text/plain; charset=ISO-8859-1"),
                Arguments.of("text/plain; charset=bogus", "text/plain; charset=UTF-8"),
                Arguments.of("text/plain; Charset=bogus", "text/plain; charset=UTF-8"),
                Arguments.of("text/plain; charset=x*y", "text/plain; charset=UTF-8"),
                Arguments.of("*/*", null),
                Arguments.of("*/*; charset=ISO-8859-1", null),
                Arguments.of(null, null),
                Arguments.of("   ", null),
                Arguments.of(";;;", null),
                Arguments.of("text", null));
    }

    @ParameterizedTest(name = "{index}: Content-Type [{0}]")
    @MethodSource("responseContentTypes")
    void responseContentTypeIsTheOneTheOriginalListenerWrote(String requestContentType, String expected) {
        String actual = new SalesQueueService(jmsTemplate, SALES).responseContentType(requestContentType);

        assertThat(actual).isEqualTo(expected);
        verifyNoInteractions(jmsTemplate);
    }

    @Test
    void bytePathPropagatesJmsExceptionUnchangedAndLogsNothing(CapturedOutput output) {
        String payload = "failing-byte-payload-15";
        UncategorizedJmsException ex = new UncategorizedJmsException("broker down");
        doThrow(ex).when(jmsTemplate).convertAndSend(anyString(), any(Object.class));
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        JmsException thrown = assertThrows(JmsException.class,
                () -> service.send(utf8(payload), "text/plain; charset=UTF-8"));

        assertSame(ex, thrown);
        assertThat(descriptionCount(output)).as("descriptions logged by the failed send").isEqualTo(descriptionsBefore);
        assertThat(output.getAll()).doesNotContain(payload);
    }

    @Test
    void bytePathRejectsNullBodyWithoutSendingOrLogging(CapturedOutput output) {
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        assertThrows(NullPointerException.class, () -> service.send((byte[]) null, "application/json"));

        verifyNoInteractions(jmsTemplate);
        assertThat(descriptionCount(output)).as("descriptions logged for an absent body").isEqualTo(descriptionsBefore);
    }

    /**
     * A form request's {@code Content-Type}, its raw body, the text the Mule 3.8 listener and transformer queued
     * for it, and the charset the response bytes are encoded with. Each expected text is the output of the real
     * Mule 3.8.0 classes ({@code HttpParser.decodeString} into a {@code ParameterMap}, rendered by
     * {@code ObjectToString}): entries in the hash order of the map's entry set, not the body's; a repeated name
     * with its last value; {@code null} for a pair without {@code =}; {@code password} masked. A header without a
     * charset URL-decodes with the JVM's default charset; those bodies are ASCII, so their text does not depend on
     * it. The last rows are headers the listener's Guava 18.0 {@code MediaType} parser accepted, as the real Guava
     * 18.0 and Mule 3.8.0 classes answer for them: whitespace after a {@code ;}, a quoted value with an escaped
     * quote, a repeated charset that names one charset, and parameters other than {@code charset}. The two final
     * rows are headers Guava accepted and Spring's parser rejects, a brace in a token and a backslash escape in a
     * quoted charset: the listener resolved ISO-8859-1 from each and URL-decoded with it, while the response bytes
     * follow the UTF-8 the response's {@code Content-Type} names for a header Spring cannot parse.
     */
    static Stream<Arguments> formBodies() {
        String form = "application/x-www-form-urlencoded";
        Charset jvmDefault = Charset.defaultCharset();
        String pairs = "a=1&b=2";
        String rendering = "{a=1, b=2}";
        return Stream.of(
                Arguments.of(form, "a=1&b=2", "{a=1, b=2}", jvmDefault),
                Arguments.of(form, "b=2&a=1", "{b=2, a=1}", jvmDefault),
                Arguments.of(form, "z=1&a=1", "{a=1, z=1}", jvmDefault),
                Arguments.of(form, "a=1&a=2", "{a=2}", jvmDefault),
                Arguments.of(form, "a", "{a=null}", jvmDefault),
                Arguments.of(form, "a=1&a", "{a=null}", jvmDefault),
                Arguments.of(form, "&a=1", "{=null, a=1}", jvmDefault),
                Arguments.of(form, "a=1&", "{a=1}", jvmDefault),
                Arguments.of(form, "&&", "{}", jvmDefault),
                Arguments.of(form, "   ", "{}", jvmDefault),
                Arguments.of(form, "user=mule&password=secret", "{password=*****, user=mule}", jvmDefault),
                Arguments.of(form, "password", "{password=null}", jvmDefault),
                Arguments.of(form, "a=b+c%20d", "{a=b c d}", jvmDefault),
                Arguments.of(form, "=1", "{=1}", jvmDefault),
                Arguments.of(form, "ITEM_ID=001&ITEM_NAME=Shirt&QTY=1&PRICE=20",
                        "{QTY=1, ITEM_ID=001, PRICE=20, ITEM_NAME=Shirt}", jvmDefault),
                Arguments.of(form, "{\"ITEM_ID\"= 001}", "{{\"ITEM_ID\"= 001}}", jvmDefault),
                Arguments.of(form + "; charset=UTF-8", "x=%E2%82%AC", "{x=\u20ac}", StandardCharsets.UTF_8),
                Arguments.of(form + "; charset=ISO-8859-1", "name=caf%E9", "{name=caf\u00e9}",
                        StandardCharsets.ISO_8859_1),
                Arguments.of("Application/X-WWW-Form-URLEncoded", pairs, rendering, jvmDefault),
                Arguments.of(form + ";charset=ISO-8859-1", pairs, rendering, StandardCharsets.ISO_8859_1),
                Arguments.of(form + ";  charset=utf-8", pairs, rendering, StandardCharsets.UTF_8),
                Arguments.of(form + ";\tcharset=utf-8", pairs, rendering, StandardCharsets.UTF_8),
                Arguments.of(form + "; charset=\"utf-8\"", pairs, rendering, StandardCharsets.UTF_8),
                Arguments.of(form + "; foo=\"a\\\"b\"", pairs, rendering, jvmDefault),
                Arguments.of(form + "; charset=utf-8; charset=UTF-8", pairs, rendering, StandardCharsets.UTF_8),
                Arguments.of(form + "; foo=bar", pairs, rendering, jvmDefault),
                Arguments.of(form + "; foo=\"a b\"", pairs, rendering, jvmDefault),
                Arguments.of(form + "; charset=iso-8859-1; x={y}", "name=caf%E9", "{name=caf\u00e9}",
                        StandardCharsets.UTF_8),
                Arguments.of(form + "; charset=\"iso\\-8859-1\"", "name=caf%E9", "{name=caf\u00e9}",
                        StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{index}: [{1}] with Content-Type [{0}]")
    @MethodSource("formBodies")
    void formPathQueuesParameterMapRenderingAndReturnsItsBytes(String contentType, String body, String expectedText,
            Charset responseCharset) throws UndecodableFormException {
        byte[] result = new SalesQueueService(jmsTemplate, SALES)
                .sendForm(body.getBytes(StandardCharsets.US_ASCII), contentType);

        verify(jmsTemplate).convertAndSend(eq(SALES), eq(expectedText));
        verifyNoMoreInteractions(jmsTemplate);
        assertThat(result).containsExactly(expectedText.getBytes(responseCharset));
    }

    @ParameterizedTest(name = "{index}: [{0}]")
    @ValueSource(strings = {"a=%zz", "a=%2", "a=%-1"})
    void formPathRejectsMalformedEscapeWithoutSendingOrLogging(String body, CapturedOutput output) {
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        UndecodableFormException thrown = assertThrows(UndecodableFormException.class,
                () -> service.sendForm(body.getBytes(StandardCharsets.US_ASCII), "application/x-www-form-urlencoded"));

        assertThat(thrown).hasMessage("Cannot decode x-www-form-urlencoded payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getStatus()).isEqualTo(400);
        verifyNoInteractions(jmsTemplate);
        assertThat(descriptionCount(output)).as("descriptions logged for an undecodable form")
                .isEqualTo(descriptionsBefore);
    }

    /**
     * A form entity's {@code Content-Type} that the Mule 3.8 listener rejected before decoding the body, with the
     * status and message its response carried, as the real Guava 18.0 {@code MediaType} and Mule 3.8.0 classes
     * answer for each header: a header the grammar rejects gives 400 {@code Could not parse '<header>'}; a single
     * charset the JVM cannot use gives 400 with its lower-cased name, empty for {@code charset=""}; two different
     * charsets give 500 {@code Server encountered a problem}.
     */
    static Stream<Arguments> rejectedFormContentTypes() {
        String form = "application/x-www-form-urlencoded";
        return Stream.of(
                Arguments.of(form + "; charset=bogus", 400, "bogus"),
                Arguments.of(form + "; charset=BOGUS", 400, "bogus"),
                Arguments.of(form + "; Charset=Bogus", 400, "bogus"),
                Arguments.of(form + "; charset=x*y", 400, "x*y"),
                Arguments.of(form + "; charset=\"\"", 400, ""),
                unparseable(form + "; bad"),
                unparseable(form + ";"),
                unparseable(form + ";charset=utf-8;"),
                unparseable(form + " ; charset=utf-8"),
                unparseable(form + "; charset = utf-8"),
                unparseable(form + "; charset=utf-8 "),
                unparseable(form + " "),
                unparseable(" " + form),
                unparseable(form + ";;charset=utf-8"),
                unparseable(form + "; =a"),
                unparseable(form + "; foo="),
                unparseable(form + "; foo=a b"),
                unparseable(form + "; foo=\"unterminated"),
                unparseable(form + "; charset=\"utf\\"),
                unparseable(form + "; foo=\u00e9"),
                unparseable(form + "; foo=\"\u00e9\""),
                unparseable(form + "; foo=\"a\\\u00e9\""),
                Arguments.of(form + "; charset=utf-8; charset=iso-8859-1", 500, "Server encountered a problem"));
    }

    @ParameterizedTest(name = "{index}: Content-Type [{0}]")
    @MethodSource("rejectedFormContentTypes")
    void formPathRejectsContentTypeTheListenerRejectedWithoutSendingOrLogging(String contentType, int status,
            String message, CapturedOutput output) {
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        UndecodableFormException thrown = assertThrows(UndecodableFormException.class,
                () -> service.sendForm("a=1&b=2".getBytes(StandardCharsets.US_ASCII), contentType));

        assertThat(thrown.getStatus()).isEqualTo(status);
        assertThat(thrown).hasMessage(message);
        verifyNoInteractions(jmsTemplate);
        assertThat(descriptionCount(output)).as("descriptions logged for a rejected Content-Type")
                .isEqualTo(descriptionsBefore);
    }

    /**
     * A request's {@code Content-Type} and whether the Mule 3.8 listener decoded its entity as a form: the
     * {@code type/subtype} is {@code application/x-www-form-urlencoded} in any letter case, whatever the parameters,
     * unless the header contains {@code multipart}; an absent, blank or unparseable header is not a form.
     */
    static Stream<Arguments> formContentTypes() {
        return Stream.of(
                Arguments.of("application/x-www-form-urlencoded", true),
                Arguments.of("APPLICATION/X-WWW-FORM-URLENCODED; charset=UTF-8", true),
                Arguments.of("application/x-www-form-urlencoded;charset=ISO-8859-1", true),
                Arguments.of("application/x-www-form-urlencoded; charset=bogus", true),
                Arguments.of(null, false),
                Arguments.of("   ", false),
                Arguments.of(";;;", false),
                Arguments.of("application/json", false),
                Arguments.of("text/plain", false),
                Arguments.of("multipart/form-data; boundary=x", false),
                Arguments.of("application/x-www-form-urlencoded; boundary=multipart", false));
    }

    @ParameterizedTest(name = "{index}: Content-Type [{0}]")
    @MethodSource("formContentTypes")
    void isFormUrlEncodedMatchesTheListenersFormTypes(String contentType, boolean expected) {
        assertThat(new SalesQueueService(jmsTemplate, SALES).isFormUrlEncoded(contentType)).isEqualTo(expected);
        verifyNoInteractions(jmsTemplate);
    }

    @Test
    void formPathLogsStringPayloadDescriptionAfterSendAndNeverTheBody(CapturedOutput output)
            throws UndecodableFormException {
        String body = "case=form-logged-17&password=form-secret-18";
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        service.sendForm(body.getBytes(StandardCharsets.US_ASCII), "application/x-www-form-urlencoded");

        verify(jmsTemplate).convertAndSend(SALES, "{case=form-logged-17, password=*****}");
        assertThat(descriptionCount(output)).as("descriptions logged by the form send")
                .isEqualTo(descriptionsBefore + 1);
        List<String> lines = output.getOut().lines().toList();
        int header = lines.lastIndexOf(DESCRIPTION_HEADER);
        assertThat(lines.get(header + 3)).isEqualTo("  payload=java.lang.String");
        assertThat(output.getAll()).as("form body and rendering kept out of the log")
                .doesNotContain("form-logged-17").doesNotContain("form-secret-18");
    }

    /** An absent body with a form header, and a body with an absent header, which Guava's parser also refused. */
    static Stream<Arguments> absentFormArguments() {
        return Stream.of(
                Arguments.of(null, "application/x-www-form-urlencoded"),
                Arguments.of("a=1".getBytes(StandardCharsets.US_ASCII), null));
    }

    @ParameterizedTest(name = "{index}: Content-Type [{1}]")
    @MethodSource("absentFormArguments")
    void formPathRejectsNullArgumentWithoutSendingOrLogging(byte[] body, String contentType, CapturedOutput output) {
        SalesQueueService service = new SalesQueueService(jmsTemplate, SALES);
        long descriptionsBefore = descriptionCount(output);

        assertThrows(NullPointerException.class, () -> service.sendForm(body, contentType));

        verifyNoInteractions(jmsTemplate);
        assertThat(descriptionCount(output)).as("descriptions logged for an absent argument")
                .isEqualTo(descriptionsBefore);
    }

    /** A header the listener's Guava 18.0 parser rejected: 400 with Guava's message naming the whole header. */
    private static Arguments unparseable(String contentType) {
        return Arguments.of(contentType, 400, "Could not parse '" + contentType + "'");
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jvmDefault(String text) {
        return text.getBytes(Charset.defaultCharset());
    }

    /** {@code text} as the JVM's default charset holds it: the same text whenever that charset can encode it. */
    private static String inJvmDefault(String text) {
        return new String(jvmDefault(text), Charset.defaultCharset());
    }

    private static long descriptionCount(CapturedOutput output) {
        return output.getOut().lines().filter(DESCRIPTION_HEADER::equals).count();
    }
}
