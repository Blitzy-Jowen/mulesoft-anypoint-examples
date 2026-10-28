package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.Port;
import javax.wsdl.PortType;
import javax.wsdl.Service;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.soap.SOAPAddress;
import javax.wsdl.factory.WSDLFactory;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.mulesoft.examples.foreach_processing_and_choice_routing.client.BankClient;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import com.mulesoft.examples.foreach_processing_and_choice_routing.service.Bank;
import com.mulesoft.examples.foreach_processing_and_choice_routing.support.HttpTestClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.TestSocketUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Endpoint test of the five mock banks, which the original exposed as the flows {@code Bank1Flow} to
 * {@code Bank5Flow} of {@code loanbroker-simple.xml}: each an HTTP listener (10080 {@code /mule/TheBank1} to 50080
 * {@code /mule/TheBank5}, {@code path="/*"}, every method) in front of a {@code cxf:jaxws-service} of {@code Bank} and
 * a singleton {@code Bank} named {@code Bank #1} to {@code Bank #5}.
 *
 * <p>The whole application runs on Undertow and every request goes over a socket, through ST-1
 * {@link HttpTestClient} or the production {@link BankClient}, so the CXF wire contract is asserted as the listeners
 * write it:
 * <ul>
 *   <li>the exact response bytes of {@code getLoanQuote} on every bank, only the random rate matched by pattern;</li>
 *   <li>each port answering as its own bank, on its base path and on the {@code /*} path the loan broker calls, and
 *       from its own {@code Bank} instance and rates, proven with controlled rates no other bank holds;</li>
 *   <li>the original rate selection: three rates per bank, fixed for the bank's lifetime, chosen by the credit-score
 *       bands {@code < 500}, {@code 500..699} and {@code >= 700}, lowest for the best band;</li>
 *   <li>the published {@code BankService.wsdl} with its JAX-WS identities and the request URL as address;</li>
 *   <li>the no-listener 404 for another bank's path, and the SOAP 1.1 faults for unreadable requests, for operations
 *       the port's service does not have, and for a request the bank fails on.</li>
 * </ul>
 *
 * <p>The six SOAP listener ports are chosen here instead of the original fixed ones: application contexts of other
 * test classes stay cached in the same JVM with their listeners bound, so a fixed port could already be taken.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankEndpointTest {

    private static final int BANK_COUNT = 5;

    /** Ports the original listeners fixed (main, credit agency, Banks 1 to 5), which cached contexts may hold. */
    private static final Set<Integer> ORIGINAL_PORTS = Set.of(11081, 18080, 10080, 20080, 30080, 40080, 50080);

    /**
     * Start of Linux's default ephemeral range: a chosen port stays below it, so no outgoing connection elsewhere on
     * the host can take it between the availability check and the listener's bind.
     */
    private static final int EPHEMERAL_PORT_MIN = 32768;

    private static final int MAX_PORT_ATTEMPTS = 1000;

    /** Credit agency port followed by the ports of Banks 1 to 5, all distinct. */
    private static final int[] LISTENER_PORTS = distinctFreePorts(1 + BANK_COUNT);

    private static final int CREDIT_AGENCY_PORT = LISTENER_PORTS[0];

    private static final int[] BANK_PORTS = Arrays.copyOfRange(LISTENER_PORTS, 1, 1 + BANK_COUNT);

    private static final String CREDIT_AGENCY_PATH = "/mule/TheCreditAgencyService";

    private static final String BANK_NS = "http://bank.loanbroker.example.mule.org/";

    private static final String CREDIT_AGENCY_NS = "http://creditagency.loanbroker.example.mule.org/";

    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String SOAP_CONTENT_TYPE = "text/xml;charset=UTF-8";

    private static final int PLATINUM_SCORE = 750;

    private static final int GOLD_SCORE = 600;

    private static final int BASIC_SCORE = 400;

    /** Everything a CXF fault writes before the fault code. */
    private static final String FAULT_PREFIX =
            "<soap:Envelope xmlns:soap=\"" + SOAP11_NS + "\"><soap:Body><soap:Fault><faultcode>";

    private static final String CLIENT_FAULT_PREFIX = FAULT_PREFIX + "soap:Client</faultcode><faultstring>";

    private static final String SERVER_FAULT_PREFIX = FAULT_PREFIX + "soap:Server</faultcode><faultstring>";

    private static final String FAULT_SUFFIX = "</faultstring></soap:Fault></soap:Body></soap:Envelope>";

    /** The interest rate as JAXB writes a {@code double}: {@link Double#toString(double)}, exponent included. */
    private static final String RATE_GROUP = "(-?\\d+(?:\\.\\d+)?(?:E-?\\d+)?)";

    @Autowired
    private BankClient bankClient;

    @Autowired
    private BankEndpoint bankEndpoint;

    /**
     * Binds the credit agency and the banks to the chosen ports. The additional listeners of {@code application.yml}
     * take their ports from these keys ({@code port: ${ports.bank1}} and so on), so the listeners, the port routing
     * and the bank chosen per port all follow them.
     */
    @DynamicPropertySource
    static void listenerPorts(DynamicPropertyRegistry registry) {
        registry.add("ports.credit-agency", () -> CREDIT_AGENCY_PORT);
        for (int bank = 1; bank <= BANK_COUNT; bank++) {
            int port = bankPort(bank);
            registry.add("ports.bank" + bank, () -> port);
        }
        registry.add("loanbroker.outbound-host", () -> "localhost");
    }

    @ParameterizedTest(name = "Bank #{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void quoteResponseHasTheCxfEnvelopeBytes(int bank) {
        HttpTestClient.Response response = post(bankPort(bank), bankPath(bank),
                envelope(loanQuoteRequest(PLATINUM_SCORE)));

        assertThat(response.statusLine()).as("status line").isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).doesNotStartWith("<?xml").doesNotContain(":Header");
        double rate = rateIn(response, bank);
        assertThat(rate).isGreaterThanOrEqualTo(0.0).isLessThan(10.0);
        assertThat(rate).isEqualTo(rate(bank, PLATINUM_SCORE));
    }

    @ParameterizedTest(name = "Bank #{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void eachBankPortAnswersAsItsOwnBank(int bank) {
        LoanQuote quote = quote(bank, GOLD_SCORE);
        assertThat(quote.getBankName()).isEqualTo("Bank #" + bank);

        HttpTestClient.Response response = post(bankPort(bank), bankPath(bank) + "/*",
                envelope(loanQuoteRequest(GOLD_SCORE)));
        assertThat(response.status()).isEqualTo(200);
        assertThat(rateIn(response, bank)).isEqualTo(quote.getInterestRate());
    }

    @ParameterizedTest(name = "Bank #{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void rateIsFixedWithinACreditBand(int bank) {
        assertThat(rate(bank, 850)).as("platinum").isEqualTo(rate(bank, 750));
        assertThat(rate(bank, 650)).as("gold").isEqualTo(rate(bank, 550));
        assertThat(rate(bank, 450)).as("basic").isEqualTo(rate(bank, 300));
    }

    @ParameterizedTest(name = "Bank #{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void betterBandsGetLowerRatesAndBandsSplitAt500And700(int bank) {
        double platinum = rate(bank, 800);
        double gold = rate(bank, 600);
        double basic = rate(bank, 400);

        assertThat(platinum).as("platinum <= gold").isLessThanOrEqualTo(gold);
        assertThat(gold).as("gold <= basic").isLessThanOrEqualTo(basic);
        assertThat(rate(bank, 499)).as("499 is basic").isEqualTo(basic);
        assertThat(rate(bank, 500)).as("500 is gold").isEqualTo(gold);
        assertThat(rate(bank, 699)).as("699 is gold").isEqualTo(gold);
        assertThat(rate(bank, 700)).as("700 is platinum").isEqualTo(platinum);
    }

    /**
     * Each bank port is served by its own {@code Bank} instance with its own rates, as each flow had its own
     * singleton. Random rates may coincide between banks, so each bank's own {@code rates} array is given controlled
     * values no other bank holds, and every port must answer with its own bank's values in all three bands: a port
     * served by another bank, or a rates array shared between banks, answers with another bank's values. The drawn
     * rates are restored afterwards for the other tests of this application context.
     */
    @Test
    void everyBankPortIsServedByItsOwnBankWithItsOwnRates() {
        Map<?, ?> banksByPort = (Map<?, ?>) ReflectionTestUtils.getField(bankEndpoint, "banksByPort");
        assertThat(banksByPort).as("banksByPort").isNotNull();
        assertThat(banksByPort.keySet().stream().mapToInt(port -> (Integer) port).toArray())
                .as("bank ports, in the order of ports.bank1 to ports.bank5").containsExactly(BANK_PORTS);

        Bank[] banks = new Bank[BANK_COUNT];
        double[][] rates = new double[BANK_COUNT][];
        for (int bank = 1; bank <= BANK_COUNT; bank++) {
            banks[bank - 1] = (Bank) banksByPort.get(bankPort(bank));
            assertThat(banks[bank - 1].getBankName()).as("bank on the port of Bank #%d", bank)
                    .isEqualTo("Bank #" + bank);
            rates[bank - 1] = (double[]) ReflectionTestUtils.getField(banks[bank - 1], "rates");
            assertThat(rates[bank - 1]).as("rates of Bank #%d", bank).hasSameSizeAs(controlledRates(bank));
            for (int other = 1; other < bank; other++) {
                assertThat(banks[bank - 1]).as("Bank #%d and Bank #%d", other, bank).isNotSameAs(banks[other - 1]);
                assertThat(rates[bank - 1]).as("rates of Bank #%d and Bank #%d", other, bank)
                        .isNotSameAs(rates[other - 1]);
            }
        }

        double[][] drawnRates = new double[BANK_COUNT][];
        for (int bank = 1; bank <= BANK_COUNT; bank++) {
            drawnRates[bank - 1] = rates[bank - 1].clone();
        }
        try {
            for (int bank = 1; bank <= BANK_COUNT; bank++) {
                double[] controlled = controlledRates(bank);
                System.arraycopy(controlled, 0, rates[bank - 1], 0, controlled.length);
            }
            for (int bank = 1; bank <= BANK_COUNT; bank++) {
                double[] controlled = controlledRates(bank);
                assertThat(rate(bank, PLATINUM_SCORE)).as("platinum rate of Bank #%d", bank).isEqualTo(controlled[0]);
                assertThat(rate(bank, GOLD_SCORE)).as("gold rate of Bank #%d", bank).isEqualTo(controlled[1]);
                assertThat(rate(bank, BASIC_SCORE)).as("basic rate of Bank #%d", bank).isEqualTo(controlled[2]);
            }
        }
        finally {
            for (int bank = 1; bank <= BANK_COUNT; bank++) {
                System.arraycopy(drawnRates[bank - 1], 0, rates[bank - 1], 0, drawnRates[bank - 1].length);
            }
        }
    }

    @ParameterizedTest(name = "Bank #{0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void wsdlQueryPublishesBankServiceAtTheRequestUrl(int bank) throws WSDLException {
        HttpTestClient.Response response = HttpTestClient.localhost(bankPort(bank)).get(bankPath(bank) + "?wsdl");

        assertThat(response.status()).isEqualTo(200);
        assertThat(normalizedContentType(response)).isEqualTo("text/xml;charset=utf-8");

        WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();
        reader.setFeature("javax.wsdl.verbose", false);
        Definition definition = reader.readWSDL(null, new InputSource(new ByteArrayInputStream(response.body())));
        assertThat(definition.getTargetNamespace()).isEqualTo(BANK_NS);

        PortType portType = definition.getPortType(new QName(BANK_NS, "BankService"));
        assertThat(portType).as("portType BankService").isNotNull();
        assertThat(portType.getOperation("getLoanQuote", null, null)).as("operation getLoanQuote").isNotNull();

        Binding binding = definition.getBinding(new QName(BANK_NS, "BankServiceSoapBinding"));
        assertThat(binding).as("binding BankServiceSoapBinding").isNotNull();
        assertThat(binding.getPortType().getQName()).isEqualTo(portType.getQName());

        Service service = definition.getService(new QName(BANK_NS, "BankService"));
        assertThat(service).as("service BankService").isNotNull();
        Port port = service.getPort("BankPort");
        assertThat(port).as("port BankPort").isNotNull();
        assertThat(port.getBinding().getQName()).isEqualTo(binding.getQName());

        List<?> extensibilityElements = port.getExtensibilityElements();
        SOAPAddress address = extensibilityElements.stream()
                .filter(SOAPAddress.class::isInstance)
                .map(SOAPAddress.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("BankPort has no soap:address"));
        assertThat(address.getLocationURI()).isEqualTo("http://localhost:" + bankPort(bank) + bankPath(bank));
    }

    @Test
    void anotherBanksPathIsNotServedOnThisBanksPort() {
        HttpTestClient.Response response = post(bankPort(1), bankPath(2), envelope(loanQuoteRequest(GOLD_SCORE)));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /mule/TheBank2");
    }

    static Stream<Arguments> unreadableRequests() {
        return Stream.of(
                Arguments.of("malformed XML", "POST", "<soapenv:Envelope"),
                Arguments.of("empty body", "POST", ""),
                Arguments.of("body root other than the envelope", "POST", loanQuoteRequest(GOLD_SCORE)),
                Arguments.of("GET without a metadata query", "GET", null));
    }

    /**
     * Bank listeners accept every method, as the original's did, so a {@code GET} that is no metadata query is read
     * as a message, and fails like any other request without a readable envelope.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableRequests")
    void unreadableRequestIsAnsweredWithAClientFault(String description, String method, String body) {
        HttpTestClient.Response response = send(bankPort(1), method, bankPath(1), body);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).startsWith(CLIENT_FAULT_PREFIX).endsWith(FAULT_SUFFIX);
        assertThat(faultcode(response)).isEqualTo("soap:Client");
        assertThat(faultstring(response)).isNotBlank();
    }

    static Stream<Arguments> operationsTheListenersServiceDoesNotHave() {
        return Stream.of(
                Arguments.of("credit agency operation on a bank port", bankPort(5), bankPath(5),
                        "<ca:getCreditProfile xmlns:ca=\"" + CREDIT_AGENCY_NS + "\"><arg0><name>Muley</name>"
                                + "<ssn>1234</ssn></arg0></ca:getCreditProfile>",
                        CreditAgencyEndpoint.PAYLOAD_ROOT),
                Arguments.of("unknown body root on a bank port", bankPort(1), bankPath(1),
                        "<u:doSomething xmlns:u=\"urn:example:unknown\"/>",
                        new QName("urn:example:unknown", "doSomething")),
                Arguments.of("bank operation on the credit agency port", CREDIT_AGENCY_PORT, CREDIT_AGENCY_PATH,
                        loanQuoteRequest(GOLD_SCORE), BankEndpoint.PAYLOAD_ROOT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationsTheListenersServiceDoesNotHave")
    void operationTheListenersServiceDoesNotHaveIsAnsweredWithCxfsUnknownPartFault(String description, int port,
            String path, String payload, QName payloadRoot) {
        HttpTestClient.Response response = post(port, path, envelope(payload));

        String faultString = "Message part " + payloadRoot + " was not recognized.  (Does it exist in service WSDL?)";
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).isEqualTo(CLIENT_FAULT_PREFIX + faultString + FAULT_SUFFIX);
        assertThat(faultcode(response)).isEqualTo("soap:Client");
        assertThat(faultstring(response)).isEqualTo(faultString);
    }

    /** The bank dereferences the request's credit profile, so a request without one fails inside the bank. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "<bank:getLoanQuote xmlns:bank=\"" + BANK_NS + "\"/>",
            "<bank:getLoanQuote xmlns:bank=\"" + BANK_NS + "\"><arg0/></bank:getLoanQuote>"})
    void quoteRequestWithoutACreditProfileIsAnsweredWithAServerFault(String payload) {
        HttpTestClient.Response response = post(bankPort(1), bankPath(1), envelope(payload));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.header("Content-Type")).startsWith("text/xml");
        assertThat(response.bodyAsString()).startsWith(SERVER_FAULT_PREFIX).endsWith(FAULT_SUFFIX);
        assertThat(faultcode(response)).isEqualTo("soap:Server");
        assertThat(faultstring(response)).isNotBlank();
    }

    private static int bankPort(int bank) {
        return BANK_PORTS[bank - 1];
    }

    private static String bankPath(int bank) {
        return "/mule/TheBank" + bank;
    }

    /** A SOAP 1.1 POST as the CXF client sent it: {@code text/xml} in UTF-8 and the empty {@code SOAPAction}. */
    private static HttpTestClient.Response post(int port, String path, String body) {
        return send(port, "POST", path, body);
    }

    /**
     * Sends {@code body} with the SOAP 1.1 request headers of {@link #post}, or, when {@code body} is {@code null}, a
     * request with neither body nor those headers.
     */
    private static HttpTestClient.Response send(int port, String method, String path, String body) {
        if (body == null) {
            return HttpTestClient.localhost(port).send(method, path, null, null);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", SOAP_CONTENT_TYPE);
        headers.put("SOAPAction", "\"\"");
        return HttpTestClient.localhost(port).send(method, path, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String envelope(String bodyXml) {
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\"><soapenv:Header/><soapenv:Body>" + bodyXml
                + "</soapenv:Body></soapenv:Envelope>";
    }

    private static String loanQuoteRequest(int creditScore) {
        return "<bank:getLoanQuote xmlns:bank=\"" + BANK_NS + "\"><arg0><creditProfile>"
                + "<creditHistory>10</creditHistory><creditScore>" + creditScore + "</creditScore>"
                + "</creditProfile></arg0></bank:getLoanQuote>";
    }

    /** The bank's quote for a credit score, through the production client and the bank's {@code /*} path. */
    private LoanQuote quote(int bank, int creditScore) {
        CreditProfile profile = new CreditProfile();
        profile.setCreditHistory(10);
        profile.setCreditScore(creditScore);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(profile);
        return bankClient.getLoanQuote(URI.create("http://localhost:" + bankPort(bank) + bankPath(bank)), request);
    }

    private double rate(int bank, int creditScore) {
        return quote(bank, creditScore).getInterestRate();
    }

    /**
     * Rates for {@code Bank #<bank>} that no other bank's controlled rates repeat, in the bank's own order: platinum,
     * gold and basic, ascending and within the original's [0, 10).
     */
    private static double[] controlledRates(int bank) {
        return new double[] {bank, bank + 0.25, bank + 0.5};
    }

    /**
     * Matches the whole body against CXF's {@code getLoanQuoteResponse} envelope of {@code Bank #<bank>}, every byte
     * literal except the rate, and returns the rate.
     */
    private static double rateIn(HttpTestClient.Response response, int bank) {
        Pattern quoteResponse = Pattern.compile(
                Pattern.quote("<soap:Envelope xmlns:soap=\"" + SOAP11_NS + "\"><soap:Body>"
                        + "<ns2:getLoanQuoteResponse xmlns:ns2=\"" + BANK_NS + "\"><return><bankName>Bank #" + bank
                        + "</bankName><interestRate>")
                        + RATE_GROUP
                        + Pattern.quote("</interestRate></return></ns2:getLoanQuoteResponse></soap:Body>"
                                + "</soap:Envelope>"));
        Matcher matcher = quoteResponse.matcher(response.bodyAsString());
        assertThat(matcher.matches()).as("CXF-style getLoanQuoteResponse of Bank #%d in%n%s", bank, response)
                .isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    /** The response's {@code Content-Type}, lower-cased and without spaces. */
    private static String normalizedContentType(HttpTestClient.Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type").isNotNull();
        return contentType.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String faultcode(HttpTestClient.Response response) {
        return unqualifiedChildText(fault(response), "faultcode");
    }

    private static String faultstring(HttpTestClient.Response response) {
        return unqualifiedChildText(fault(response), "faultstring");
    }

    /** The single SOAP 1.1 {@code Fault} of a response whose root is the SOAP 1.1 {@code Envelope}. */
    private static Element fault(HttpTestClient.Response response) {
        Document document = parse(response.body());
        Element envelope = document.getDocumentElement();
        assertThat(envelope.getNamespaceURI()).as("envelope namespace").isEqualTo(SOAP11_NS);
        assertThat(envelope.getLocalName()).as("root element").isEqualTo("Envelope");
        NodeList faults = document.getElementsByTagNameNS(SOAP11_NS, "Fault");
        assertThat(faults.getLength()).as("soap:Fault elements").isEqualTo(1);
        return (Element) faults.item(0);
    }

    private static String unqualifiedChildText(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getNamespaceURI() == null
                    && localName.equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        return fail("soap:Fault has no unqualified <%s> child", localName);
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        }
        catch (ParserConfigurationException | SAXException | IOException ex) {
            return fail("Response body is not well-formed XML", ex);
        }
    }

    /**
     * {@code count} distinct free TCP ports, none of them an original listener port and all below
     * {@link #EPHEMERAL_PORT_MIN}.
     *
     * @throws IllegalStateException when {@link #MAX_PORT_ATTEMPTS} draws do not yield enough such ports
     */
    private static int[] distinctFreePorts(int count) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (int attempt = 0; ports.size() < count; attempt++) {
            if (attempt == MAX_PORT_ATTEMPTS) {
                throw new IllegalStateException("Found only " + ports.size() + " of " + count
                        + " distinct free TCP ports below " + EPHEMERAL_PORT_MIN + " in " + MAX_PORT_ATTEMPTS
                        + " attempts");
            }
            int port = TestSocketUtils.findAvailableTcpPort();
            if (port < EPHEMERAL_PORT_MIN && !ORIGINAL_PORTS.contains(port)) {
                ports.add(port);
            }
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }
}
