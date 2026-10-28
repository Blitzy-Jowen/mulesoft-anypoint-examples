package com.mulesoft.examples.xml_only_soap_webservice.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.xml_only_soap_webservice.config.SoapEnvelopeStyleInterceptor;
import com.mulesoft.examples.xml_only_soap_webservice.config.WebServiceConfig;

/**
 * Unit test of {@link EhrServiceClient}, the replacement of sub-flow {@code invokeEHRService}
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:85-88}): a {@code cxf:proxy-client
 * payload="body"} that wraps the payload in a SOAP 1.1 envelope, then {@code http:request method="POST"
 * path="EHRService"} on the application's own listener.
 * <p>
 * A WireMock server on a dynamic loopback port stands in for the {@code EHRService} mock listener. The template comes
 * from {@link WebServiceConfig}'s own bean methods, called without a Spring context, so the production message factory,
 * single-attempt message sender and fault settings are what is exercised; the environment is a {@link MockEnvironment}
 * carrying the two target keys the client reads on every call. The request has the shape of the
 * {@code Hospital_Admissions_SOA.xml} {@code createEpisode} script (line 73) and the success reply that of the
 * {@code mocks.xml} {@code createEpisodeResponse} script (line 52). Every failure must reach the caller as the exact
 * Spring WS exception type after exactly one request, because the original sub-flow declared no exception strategy and
 * no retry. No {@code SOAPAction} value is asserted: the original call sent none of the malformed
 * {@code http://http://...} actions of {@code EHRService.wsdl}, and the mock dispatches on the payload's local name.
 */
class EhrServiceClientTest {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String MESSAGE_NS = "http://www.mule-health.com/SOA/message/1.0";

    private static final String MODEL_NS = "http://www.mule-health.com/SOA/model/1.0";

    private static final Map<String, String> NAMESPACES = Map.of("soap", SOAP_NS, "ns", MESSAGE_NS, "ns1", MODEL_NS);

    /** {@code http.request.response-timeout} of application.yml, Mule's default response timeout. */
    private static final long RESPONSE_TIMEOUT_MS = 10000;

    private static final String SOAP_CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final String PATIENT_ID = "2016-05-10T10:15:30.123-03:00";

    private static final String NOW = "2016-05-10T10:15:31.456-03:00";

    /** The {@code createEpisode} payload, carrying the PatientId the PatientService mock returned. */
    private static final String CREATE_EPISODE =
            "<ns:createEpisode xmlns:ns=\"http://www.mule-health.com/SOA/message/1.0\""
                    + " xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\"><ns1:PatientId>" + PATIENT_ID
                    + "</ns1:PatientId></ns:createEpisode>";

    private static final String CREATE_EPISODE_RESPONSE_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<ns0:createEpisodeResponse xmlns:ns0=\"http://www.mule-health.com/SOA/message/1.0\">"
                    + "<ns1:Episode xmlns:ns1=\"http://www.mule-health.com/SOA/model/1.0\">"
                    + "<episodeId>" + NOW + "</episodeId>"
                    + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>"
                    + "<admission>Elective</admission>"
                    + "<startDate>" + NOW + "</startDate>"
                    + "<endDate>" + NOW + "</endDate>"
                    + "<care>Private</care>"
                    + "</ns1:Episode></ns0:createEpisodeResponse></soap:Body></soap:Envelope>";

    private static final String EMPTY_BODY_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>";

    private static final String FAULT_STRING = "EHR store unavailable";

    private static final String FAULT_ENVELOPE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Server</faultcode><faultstring>" + FAULT_STRING + "</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    private MockEnvironment environment;

    private EhrServiceClient client;

    @BeforeEach
    void setUp() throws Exception {
        SoapEnvelopeStyleInterceptor styler = new SoapEnvelopeStyleInterceptor();
        WebServiceConfig config = new WebServiceConfig(styler);
        SaajSoapMessageFactory messageFactory = config.messageFactory();
        messageFactory.afterPropertiesSet();
        WebServiceTemplate template = config.webServiceTemplate(messageFactory, RESPONSE_TIMEOUT_MS);

        environment = new MockEnvironment()
                .withProperty(EhrServiceClient.HOST_KEY, "localhost")
                .withProperty(EhrServiceClient.PORT_KEY, String.valueOf(upstream.getPort()));
        client = new EhrServiceClient(template, environment, styler);
    }

    @Test
    void sendsSoap11EnvelopeWithPayloadAndNoHeader() throws Exception {
        stubEhrService(200, CREATE_EPISODE_RESPONSE_ENVELOPE);

        client.send(requestPayload());

        upstream.verify(exactly(1), postRequestedFor(urlEqualTo(WebServiceConfig.EHR_PATH))
                .withHeader("Content-Type", containing("text/xml"))
                .withRequestBody(matchingXPath("/soap:Envelope/soap:Body/ns:createEpisode/ns1:PatientId", NAMESPACES)));

        String sent = upstream.findAll(postRequestedFor(urlEqualTo(WebServiceConfig.EHR_PATH)))
                .get(0)
                .getBodyAsString();
        Element envelope = parse(sent).getDocumentElement();
        assertThat(envelope.getNamespaceURI()).isEqualTo(SOAP_NS);
        assertThat(envelope.getLocalName()).isEqualTo("Envelope");
        assertThat(envelope.getPrefix()).isEqualTo("soap");

        JAXPXPathEngine xpath = new JAXPXPathEngine();
        xpath.setNamespaceContext(NAMESPACES);
        assertThat(xpath.evaluate("count(/soap:Envelope/soap:Header)", Input.fromString(sent).build())).isEqualTo("0");
        List<Node> bodyChildren = new ArrayList<>();
        xpath.selectNodes("/soap:Envelope/soap:Body/*", Input.fromString(sent).build()).forEach(bodyChildren::add);
        assertThat(bodyChildren).hasSize(1);

        Diff diff = DiffBuilder.compare(Input.fromString(CREATE_EPISODE))
                .withTest(Input.fromNode(bodyChildren.get(0)))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as("payload carried unchanged: %s", diff).isFalse();
    }

    @Test
    void returnsBodyRootOfResponse() throws Exception {
        stubEhrService(200, CREATE_EPISODE_RESPONSE_ENVELOPE);

        Element response = client.send(requestPayload());

        assertThat(response).isNotNull();
        assertThat(response.getLocalName()).isEqualTo("createEpisodeResponse");
        assertThat(response.getNamespaceURI()).isEqualTo(MESSAGE_NS);
        List<Element> children = childElements(response);
        assertThat(children).hasSize(1);
        Element episode = children.get(0);
        assertThat(episode.getLocalName()).isEqualTo("Episode");
        assertThat(episode.getNamespaceURI()).isEqualTo(MODEL_NS);

        List<Element> fields = childElements(episode);
        assertThat(fields).extracting(Element::getLocalName)
                .containsExactly("episodeId", "PatientId", "admission", "startDate", "endDate", "care");
        Element patientId = fields.get(1);
        assertThat(patientId.getNamespaceURI()).isEqualTo(MODEL_NS);
        assertThat(patientId.getTextContent()).isEqualTo(PATIENT_ID);
        Element admission = fields.get(2);
        assertThat(admission.getNamespaceURI()).isNull();
        assertThat(admission.getTextContent()).isEqualTo("Elective");
    }

    @Test
    void returnsNullForEmptyBody() throws Exception {
        stubEhrService(200, EMPTY_BODY_ENVELOPE);

        assertThat(client.send(requestPayload())).isNull();
    }

    @Test
    void soapFaultPropagatesAsSoapFaultClientException() throws Exception {
        stubEhrService(500, FAULT_ENVELOPE);
        Element payload = requestPayload();

        assertThatThrownBy(() -> client.send(payload))
                .isExactlyInstanceOf(SoapFaultClientException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(SoapFaultClientException.class))
                .extracting(SoapFaultClientException::getFaultStringOrReason)
                .isEqualTo(FAULT_STRING);
        upstream.verify(exactly(1), postRequestedFor(urlEqualTo(WebServiceConfig.EHR_PATH)));
    }

    @Test
    void httpErrorPropagatesAsWebServiceTransportException() throws Exception {
        upstream.stubFor(post(urlEqualTo(WebServiceConfig.EHR_PATH))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>Not Found</html>")));
        Element payload = requestPayload();

        assertThatThrownBy(() -> client.send(payload))
                .isExactlyInstanceOf(WebServiceTransportException.class)
                .hasMessageContaining("404");
        upstream.verify(exactly(1), postRequestedFor(urlEqualTo(WebServiceConfig.EHR_PATH)));
    }

    @Test
    void refusedConnectionPropagatesAsWebServiceIOException() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        // Changed after the client was built: the target port must be read on every call.
        environment.setProperty(EhrServiceClient.PORT_KEY, String.valueOf(closedPort));
        Element payload = requestPayload();

        assertThatThrownBy(() -> client.send(payload))
                .isExactlyInstanceOf(WebServiceIOException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    /**
     * A plain {@code HttpURLConnection} silently re-sends a POST whose response read fails; exactly one request proves
     * the production sender never does.
     */
    @Test
    void connectionResetPropagatesWithoutRetry() throws Exception {
        upstream.stubFor(post(urlEqualTo(WebServiceConfig.EHR_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        Element payload = requestPayload();

        assertThatThrownBy(() -> client.send(payload))
                .isExactlyInstanceOf(WebServiceIOException.class)
                .hasRootCauseInstanceOf(SocketException.class);
        upstream.verify(exactly(1), postRequestedFor(urlEqualTo(WebServiceConfig.EHR_PATH)));
    }

    private static void stubEhrService(int status, String envelope) {
        upstream.stubFor(post(urlEqualTo(WebServiceConfig.EHR_PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", SOAP_CONTENT_TYPE)
                        .withBody(envelope)));
    }

    private static Element requestPayload() throws Exception {
        return parse(CREATE_EPISODE).getDocumentElement();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }
}
