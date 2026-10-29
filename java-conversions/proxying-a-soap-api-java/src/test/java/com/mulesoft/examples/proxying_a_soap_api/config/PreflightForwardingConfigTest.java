/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExecutionChain;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.proxying_a_soap_api.client.ShopServiceClient;
import com.mulesoft.examples.proxying_a_soap_api.config.PreflightForwardingConfig.PreflightForwardingHandlerMapping;
import com.mulesoft.examples.proxying_a_soap_api.controller.SoapProxyController;
import com.mulesoft.examples.proxying_a_soap_api.exception.GlobalExceptionHandler;
import com.mulesoft.examples.proxying_a_soap_api.mapper.ProxyHeaderMapper;
import com.mulesoft.examples.proxying_a_soap_api.service.SoapProxyService;
import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient;

/**
 * Tests {@link PreflightForwardingConfig}: a CORS pre-flight sent to the proxy's root path reaches the upstream as the
 * one {@code POST} the original flow sent, with its {@code Origin} and {@code Access-Control-Request-*} headers, and
 * the caller receives the upstream's answer with no {@code Access-Control-*} header the upstream did not send, as the
 * original listener without {@code allowedMethods} and without a CORS policy answered it.
 *
 * <p>The wire cases run the real controller, service, header mapper and client on Undertow ({@link WireConfig},
 * random port on IPv4 loopback) against a WireMock upstream, and call the proxy through ST-1
 * {@link HttpTestClient}, so the status line, headers and body are asserted as the listener writes them. The unit
 * cases check the handler mapping directly: without CORS configuration it keeps the matched handler, and a declared
 * configuration is still processed by Spring.
 */
@SpringBootTest(
        classes = PreflightForwardingConfigTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets, and reads the
        // WSDL from the offline stand-in, which ShopServiceClient requires to be configured.
        properties = {
                "server.address=" + PreflightForwardingConfigTest.LOOPBACK,
                "proxy.wsdl-location=classpath:upstream/ShopService.wsdl"})
class PreflightForwardingConfigTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String PATH = "/";

    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String ORIGIN = "http://app.example.com";

    private static final String REQUESTED_HEADERS = "content-type, soapaction";

    private static final String UPSTREAM_BODY =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body/>"
                    + "</soapenv:Envelope>";

    private static final String ACCESS_CONTROL_PREFIX = "Access-Control-";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort() + UPSTREAM_PATH);
    }

    /**
     * Requested method of the pre-flight, then the {@code Access-Control-*} headers the upstream answers with. A
     * pre-flight requesting {@code OPTIONS} is matched by the controller's {@code OPTIONS} mapping, every other one by
     * its mapping without methods.
     */
    private static Stream<Arguments> preflights() {
        Map<String, String> upstreamCorsHeaders = new LinkedHashMap<>();
        upstreamCorsHeaders.put("Access-Control-Allow-Origin", ORIGIN);
        upstreamCorsHeaders.put("Access-Control-Allow-Methods", "POST, OPTIONS");
        upstreamCorsHeaders.put("Access-Control-Allow-Headers", REQUESTED_HEADERS);
        upstreamCorsHeaders.put("Access-Control-Max-Age", "600");
        return Stream.of(
                Arguments.of("POST", Map.of()),
                Arguments.of("POST", upstreamCorsHeaders),
                Arguments.of("OPTIONS", Map.of()));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, upstream CORS headers {1}")
    @MethodSource("preflights")
    void preflightIsForwardedAndAnsweredWithTheUpstreamResponse(String requestedMethod,
                                                                Map<String, String> upstreamCorsHeaders) {
        upstream.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(withHeaders(
                aResponse().withStatus(200).withHeader("Content-Type", "text/xml;charset=UTF-8")
                        .withBody(UPSTREAM_BODY),
                upstreamCorsHeaders)));
        Map<String, String> preflight = new LinkedHashMap<>();
        preflight.put("Origin", ORIGIN);
        preflight.put("Access-Control-Request-Method", requestedMethod);
        preflight.put("Access-Control-Request-Headers", REQUESTED_HEADERS);

        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).send("OPTIONS", PATH, preflight, null);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).as(response::toString).isEqualTo(UPSTREAM_BODY.getBytes(UTF_8));
        // Exactly the upstream's own Access-Control-* headers with their values unchanged, none when it sent none.
        // Undertow writes response headers in its own map order, so the order is not compared.
        List<String> callerCorsHeaders = response.headers().stream()
                .filter(header -> header.getKey().regionMatches(true, 0, ACCESS_CONTROL_PREFIX, 0,
                        ACCESS_CONTROL_PREFIX.length()))
                .map(PreflightForwardingConfigTest::headerLine)
                .toList();
        assertThat(callerCorsHeaders).as(response::toString).containsExactlyInAnyOrderElementsOf(
                upstreamCorsHeaders.entrySet().stream().map(PreflightForwardingConfigTest::headerLine).toList());
        upstream.verify(1, anyRequestedFor(anyUrl()));
        upstream.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withHeader("Origin", equalTo(ORIGIN))
                .withHeader("Access-Control-Request-Method", equalTo(requestedMethod))
                .withHeader("Access-Control-Request-Headers", equalTo(REQUESTED_HEADERS)));
    }

    @Test
    void preflightWithoutCorsConfigurationKeepsTheMatchedHandler() {
        Object handler = new Object();
        HandlerExecutionChain chain = new HandlerExecutionChain(handler);

        HandlerExecutionChain result = new PreflightForwardingHandlerMapping()
                .getCorsHandlerExecutionChain(preflightRequest(), chain, null);

        assertThat(result).isSameAs(chain);
        assertThat(result.getHandler()).isSameAs(handler);
        assertThat(result.getInterceptorList()).isEmpty();
    }

    @Test
    void preflightWithCorsConfigurationIsStillAnsweredBySpring() {
        Object handler = new Object();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin(ORIGIN);

        HandlerExecutionChain result = new PreflightForwardingHandlerMapping()
                .getCorsHandlerExecutionChain(preflightRequest(), new HandlerExecutionChain(handler), config);

        assertThat(result.getHandler()).isNotSameAs(handler);
    }

    private static MockHttpServletRequest preflightRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", PATH);
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Access-Control-Request-Method", "POST");
        return request;
    }

    /** Adds every given header to the stubbed upstream response, in the map's order. */
    private static ResponseDefinitionBuilder withHeaders(ResponseDefinitionBuilder response,
                                                         Map<String, String> headers) {
        headers.forEach(response::withHeader);
        return response;
    }

    /** {@code name: value} with the name lower-cased, so header names compare ignoring case. */
    private static String headerLine(Map.Entry<String, String> header) {
        return header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue();
    }

    /**
     * The wire cases' whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightForwardingConfig}
     * and the proxy's own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the proxy and nothing else; and it is not a {@code @SpringBootConfiguration}, so the project's
     * other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightForwardingConfig.class, SoapProxyController.class, SoapProxyService.class,
            ProxyHeaderMapper.class, ShopServiceClient.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }
}
