
package org.example.subsidy.ai;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiApiKeyFilterTest {

    private static final String KEY =
            "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsValidKey() throws Exception {

        AiApiKeyFilter filter = new AiApiKeyFilter(KEY);

        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/v1/ai/analyze"
                );

        request.addHeader("X-Api-Key", KEY);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsMissingKey() throws Exception {

        AiApiKeyFilter filter = new AiApiKeyFilter(KEY);

        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/v1/ai/analyze"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());

        verifyNoInteractions(chain);
    }

    @Test
    void rejectsIncorrectKey() throws Exception {

        AiApiKeyFilter filter = new AiApiKeyFilter(KEY);

        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/v1/ai/analyze"
                );

        request.addHeader(
                "X-Api-Key",
                "abcdef0123456789abcdef0123456789"
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());

        verifyNoInteractions(chain);
    }

    @Test
    void rejectsUnconfiguredKey() throws Exception {

        AiApiKeyFilter filter = new AiApiKeyFilter("");

        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/v1/ai/analyze"
                );

        request.addHeader("X-Api-Key", KEY);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());

        verifyNoInteractions(chain);
    }
}
