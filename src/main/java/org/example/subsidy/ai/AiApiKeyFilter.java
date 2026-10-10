
package org.example.subsidy.ai;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AiApiKeyFilter implements Filter {

    private static final String HEADER_NAME = "X-Api-Key";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] expectedKey;

    public AiApiKeyFilter(String apiKey) {
        this.expectedKey = apiKey == null
                || apiKey.length() < MIN_KEY_LENGTH
                ? new byte[0]
                : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {

        HttpServletRequest httpRequest =
                (HttpServletRequest) request;

        HttpServletResponse httpResponse =
                (HttpServletResponse) response;

        httpResponse.setHeader("Cache-Control", "no-store");

        if (expectedKey.length == 0) {
            httpResponse.sendError(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AI access is not configured"
            );
            return;
        }

        String suppliedKey =
                httpRequest.getHeader(HEADER_NAME);

        if (suppliedKey == null
                || !MessageDigest.isEqual(
                expectedKey,
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        )) {

            httpResponse.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized"
            );
            return;
        }

        chain.doFilter(request, response);
    }
}
