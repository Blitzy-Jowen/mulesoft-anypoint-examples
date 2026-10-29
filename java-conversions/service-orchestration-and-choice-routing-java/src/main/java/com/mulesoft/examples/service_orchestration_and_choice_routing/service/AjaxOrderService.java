/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.Objects;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.client.OrderProcessingSoapClient;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.AjaxOrderXmlMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.OrderSummaryJsonMapper;
import com.mulesoft.examples.service_orchestration_and_choice_routing.mapper.SoapEnvelopeTransformer;

/**
 * The three flows behind the Ajax inbound endpoints of {@code mule-config.xml}, each registered as the handler of
 * its channel on {@link AjaxChannelBroker}, which answers an rpc publication with the handler's result on its
 * {@code replyTo} channel:
 * <ul>
 *   <li>{@code orderRequest} (lines 5-37), channel {@code <server path>/request}: the JSON order the page publishes
 *       becomes the {@code order} XML ({@link AjaxOrderXmlMapper}), is wrapped in the {@code processOrder} SOAP
 *       envelope ({@code XmlToSoapEnvelope.xsl}), posted to the 1080 {@code /orders} service, reduced to its
 *       {@code summary} ({@code SoapEnvelopeToBodyChild.xslt}) and returned as the summary JSON
 *       ({@link OrderSummaryJsonMapper}) the page parses.</li>
 *   <li>{@code orderProxy} (lines 38-42), channel {@code <server path>/soap}: the published SOAP envelope is posted
 *       unchanged to the 1080 {@code /orders} service and its reply returned as text.</li>
 *   <li>{@code manufacturers} (lines 43-46), channel {@code <server path>/manufacturers}: the constant
 *       {@value #MANUFACTURERS}.</li>
 * </ul>
 * With the configured server path {@code /orders} ({@code ajax.server-path}, the path of the connector's
 * {@code serverUrl}) these are the original channels {@code /orders/request}, {@code /orders/soap} and
 * {@code /orders/manufacturers}.
 *
 * <p>Each handler runs its flow's processors in their original order, and every {@code object-to-string-transformer}
 * between them is the {@code String} each step already returns. No failure is caught: as no flow declares an
 * exception strategy, a failing step ends the call, and the broker logs it and sends no reply. What the original
 * replied in that case is a Tier 2A verification point.
 *
 * <p>Bayeux framing, channel state and timeouts belong to the broker and the long-polling controller; the
 * transformations belong to the mappers. The class holds no mutable state and is thread-safe.
 */
@Service
public class AjaxOrderService {

    /**
     * Reply of the {@code manufacturers} flow, the {@code expression-transformer} literal
     * {@code #['["Samsung","Philips","Sony"]']} (line 45), byte for byte.
     */
    public static final String MANUFACTURERS = "[\"Samsung\",\"Philips\",\"Sony\"]";

    /** Channel of the {@code orderRequest} flow below the server path ({@code /orders/request}, line 6). */
    public static final String REQUEST_SUFFIX = "/request";

    /** Channel of the {@code orderProxy} flow below the server path ({@code /orders/soap}, line 39). */
    public static final String SOAP_SUFFIX = "/soap";

    /** Channel of the {@code manufacturers} flow below the server path ({@code /orders/manufacturers}, line 44). */
    public static final String MANUFACTURERS_SUFFIX = "/manufacturers";

    private static final String SLASH = "/";

    private final AjaxChannelBroker broker;

    private final AjaxOrderXmlMapper ajaxOrderXmlMapper;

    private final SoapEnvelopeTransformer soapEnvelopeTransformer;

    private final OrderProcessingSoapClient orderProcessingSoapClient;

    private final OrderSummaryJsonMapper orderSummaryJsonMapper;

    private final ObjectMapper objectMapper;

    private final String serverPath;

    /**
     * @param broker                    the Ajax channel broker the three channels are registered on
     * @param ajaxOrderXmlMapper        owner of the "JSON to XML" script (lines 7-17)
     * @param soapEnvelopeTransformer   the two XSLT steps (lines 19 and 23)
     * @param orderProcessingSoapClient the two {@code http:request} calls to the 1080 order service (lines 21 and 40)
     * @param orderSummaryJsonMapper    owner of the "XML to JSON" script (lines 25-35)
     * @param objectMapper              the application's Jackson mapper, which renders a published value that is not
     *                                  text as JSON
     * @param serverPath                {@code ajax.server-path}, the path of the connector's {@code serverUrl}
     *                                  (line 3); normalized as {@code config.AjaxResourceConfig} normalizes it, so
     *                                  the channels hang off the same path the docroot is served at
     */
    public AjaxOrderService(AjaxChannelBroker broker, AjaxOrderXmlMapper ajaxOrderXmlMapper,
            SoapEnvelopeTransformer soapEnvelopeTransformer, OrderProcessingSoapClient orderProcessingSoapClient,
            OrderSummaryJsonMapper orderSummaryJsonMapper, ObjectMapper objectMapper,
            @Value("${ajax.server-path:/orders}") String serverPath) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.ajaxOrderXmlMapper = Objects.requireNonNull(ajaxOrderXmlMapper, "ajaxOrderXmlMapper");
        this.soapEnvelopeTransformer = Objects.requireNonNull(soapEnvelopeTransformer, "soapEnvelopeTransformer");
        this.orderProcessingSoapClient = Objects.requireNonNull(orderProcessingSoapClient,
                "orderProcessingSoapClient");
        this.orderSummaryJsonMapper = Objects.requireNonNull(orderSummaryJsonMapper, "orderSummaryJsonMapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.serverPath = normalize(serverPath);
    }

    /**
     * Registers the three flows as the handlers of their channels, as the Mule runtime started their inbound
     * endpoints when the application was deployed.
     *
     * @throws IllegalStateException when a channel already has a handler
     */
    @PostConstruct
    public void register() {
        broker.registerInbound(serverPath + REQUEST_SUFFIX, this::orderRequest);
        broker.registerInbound(serverPath + SOAP_SUFFIX, this::orderProxy);
        broker.registerInbound(serverPath + MANUFACTURERS_SUFFIX, this::manufacturers);
    }

    /**
     * The {@code orderRequest} flow: submits the published order to the 1080 order service and returns its summary.
     *
     * @param data the published order, the page's {@code JSON.stringify(order)}
     * @return the summary JSON text the page reads with {@code JSON.parse(message.data)}
     * @throws IllegalArgumentException when the order or the extracted summary cannot be read
     * @throws IllegalStateException when an XSLT input cannot be parsed or a transformation fails
     * @throws org.springframework.web.client.RestClientException when the order service answers with an error
     *         status or cannot be reached
     */
    public String orderRequest(Object data) {
        String json = asText(data);
        String xml = ajaxOrderXmlMapper.toXml(json);
        String envelope = soapEnvelopeTransformer.toEnvelope(xml);
        String response = orderProcessingSoapClient.postOrderRequest(envelope);
        String summary = soapEnvelopeTransformer.toBodyChild(response);
        return orderSummaryJsonMapper.toJson(summary);
    }

    /**
     * The {@code orderProxy} flow: forwards the published SOAP envelope to the 1080 order service.
     *
     * @param data the published envelope, the text of the page's SOAP request field
     * @return the service's reply as text, which the page shows unchanged
     * @throws org.springframework.web.client.RestClientException when the order service answers with an error
     *         status, such as a SOAP fault, or cannot be reached
     */
    public String orderProxy(Object data) {
        return orderProcessingSoapClient.proxy(asText(data));
    }

    /**
     * The {@code manufacturers} flow, whose expression transformer replaces whatever was published.
     *
     * @param data the published value, ignored
     * @return {@value #MANUFACTURERS}
     */
    public String manufacturers(Object data) {
        return MANUFACTURERS;
    }

    /**
     * The published value as the text the flows read: the pages publish strings, which pass unchanged; nothing
     * published is the empty string; any other value, as a Bayeux client that does not stringify its data sends
     * it, is rendered as JSON.
     */
    private String asText(Object data) {
        if (data == null) {
            return "";
        }
        if (data instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Ajax publication cannot be rendered as JSON", e);
        }
    }

    /**
     * Trims the configured path, adds a missing leading {@code /} and removes trailing ones, so that {@code /orders/}
     * names the channels {@code /orders/request} and not {@code /orders//request}. The value's validity is checked
     * where it becomes the docroot path, at {@code config.AjaxResourceConfig}.
     */
    private static String normalize(String configured) {
        String path = configured == null ? "" : configured.trim();
        if (!path.startsWith(SLASH)) {
            path = SLASH + path;
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
