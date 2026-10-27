/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests {@link InvalidPercentEncodingFilter} on mock requests: a request URI holding a {@code %} not followed by two
 * ASCII hexadecimal digits, anywhere in the path, is answered 400 with an empty body and no Content-Type and never
 * reaches the chain; every other request, a malformed escape in its query string included, reaches the chain
 * unchanged. That Undertow writes the rejection so on the wire is asserted by {@code FimProxyControllerTest} and
 * {@code OAuthControllerTest}.
 */
class InvalidPercentEncodingFilterTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/FIM/x%zz", "/FIM/x%", "/FIM/x%2", "/FIM/%g0", "/FIM/%0g", "/FIM/a%20b%zz", "/FIM/%25%", "/oauth%zz",
            "/zz%zz", "/%", "/FIM/%\u0663\u0663"
    })
    void pathWithMalformedPercentEscapeIsRejected400WithAnEmptyBody(String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InvalidPercentEncodingFilter().doFilter(request, response, chain);

        assertNull(chain.getRequest(), "the chain must not run");
        assertEquals(400, response.getStatus());
        assertNull(response.getErrorMessage(), "sendError would make Undertow write an HTML error page");
        assertEquals("0", response.getHeader("Content-Length"));
        assertNull(response.getContentType());
        assertArrayEquals(new byte[0], response.getContentAsByteArray());
        assertTrue(response.isCommitted());
    }

    @ParameterizedTest(name = "[{index}] {0} ? {1}")
    @CsvSource(nullValues = "null", value = {
            "/FIM/a/b, null",
            "/FIM/a%20b%2F%c3%a9%7b, null",
            "/FIM/x, q=%zz",
            "/FIM/{client_id}/\\x, id=%",
            "/oauth, ''",
            "/, null"
    })
    void pathWithoutMalformedPercentEscapeIsPassedOnUnchanged(String requestUri, String queryString)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setQueryString(queryString);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InvalidPercentEncodingFilter().doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertSame(response, chain.getResponse());
        assertEquals(200, response.getStatus());
        assertFalse(response.isCommitted());
    }
}
