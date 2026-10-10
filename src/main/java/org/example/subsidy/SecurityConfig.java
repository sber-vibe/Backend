package org.example.subsidy;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Проверка JWT от Keycloak включается, если задан JWKS (SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI).
 * Без него API открыт — демо-режим без Keycloak.
 */
@Configuration
public class SecurityConfig {
    static final String JWK_SET_URI = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";
    /** Realm-роли Keycloak, которым доступен API. */
    static final String[] API_ROLES = {"specialist", "admin"};

    @Bean
    @ConditionalOnProperty(JWK_SET_URI)
    SecurityFilterChain jwtSecurity(HttpSecurity http) throws Exception {
        var bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
        var bearerAccessDenied = new BearerTokenAccessDeniedHandler();
        return stateless(http)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("admin")
                        .requestMatchers("/api/**").hasAnyRole(API_ROLES)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakAuthenticationConverter()))
                        // заголовок WWW-Authenticate от Spring + тело в формате Problem Details, как у остального API
                        .authenticationEntryPoint((request, response, exception) -> {
                            bearerEntryPoint.commence(request, response, exception);
                            writeProblem(response, HttpStatus.UNAUTHORIZED);
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            bearerAccessDenied.handle(request, response, exception);
                            writeProblem(response, HttpStatus.FORBIDDEN);
                        }))
                .build();
    }

    @Bean
    @ConditionalOnExpression("'${" + JWK_SET_URI + ":}' == ''")
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        return stateless(http).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    /** Keycloak кладёт роли realm в claim realm_access.roles; имя пользователя — в preferred_username. */
    static JwtAuthenticationConverter keycloakAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        });
        return converter;
    }

    // токен в заголовке, cookie и сессий нет — CSRF не нужен
    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }

    // без detail: frontend показывает своё сообщение по статусу
    private static void writeProblem(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + "}");
    }
}
