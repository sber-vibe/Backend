
package org.example.subsidy.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AiSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<AiApiKeyFilter> aiApiKeyFilter(
            @Value("${AI_API_KEY:}") String apiKey
    ) {

        FilterRegistrationBean<AiApiKeyFilter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(new AiApiKeyFilter(apiKey));

        registration.addUrlPatterns(
                "/api/v1/ai",
                "/api/v1/ai/*"
        );

        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

        return registration;
    }
}
