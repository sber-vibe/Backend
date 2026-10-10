package org.example.subsidy;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
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
    JwtDecoder jwtDecoder(@Value("${" + JWK_SET_URI + "}") String jwks,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.audiences:subsidy-backend}") String audiences) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        var expected = Arrays.stream(audiences.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (issuer.isBlank() || expected.isEmpty()) throw new IllegalArgumentException("Для JWT укажите issuer и audience");
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtTypeValidator.jwt());
        validators.add(new JwtTimestampValidator(Duration.ofSeconds(30)));
        validators.add(new JwtClaimValidator<Instant>("exp", java.util.Objects::nonNull));
        validators.add(new JwtIssuerValidator(issuer));
        validators.add(new JwtClaimValidator<List<String>>("aud", actual -> actual != null && expected.stream().anyMatch(actual::contains)));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

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

    private static void writeProblem(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        String detail = status == HttpStatus.UNAUTHORIZED ? "Требуется вход: токен отсутствует, недействителен или срок его действия истёк" : "Недостаточно прав для этой операции";
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}
