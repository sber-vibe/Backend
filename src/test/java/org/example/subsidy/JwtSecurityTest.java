package org.example.subsidy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Режим с Keycloak: подписанные RSA-ключом токены, JWKS отдаёт локальный HTTP-сервер. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtSecurityTest {
    private static final String ISSUER = "http://localhost/kc/realms/subsidy";
    private static final String AUDIENCE = "subsidy-backend";
    private static final RSAKey KEY = generateKey("test-key");
    private static final HttpServer JWKS = startJwksServer(KEY);

    @LocalServerPort
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/certs");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        var response = get("/api/v1/applications", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
        assertTrue(response.body().contains("\"status\":401"));
    }

    @Test
    void specialistTokenIsAccepted() throws Exception {
        assertEquals(200, get("/api/v1/applications", token(KEY, claims().build())).statusCode());
    }

    @Test
    void tokenWithoutApiRoleIsForbidden() throws Exception {
        var response = get("/api/v1/applications", token(KEY, claims().claim("realm_access", Map.of("roles", List.of("offline_access"))).build()));
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
    }

    @Test
    void invalidTokensAreRejected() throws Exception {
        assertEquals(401, get("/api/v1/applications", token(KEY, claims().issuer("http://evil/realms/subsidy").build())).statusCode());
        assertEquals(401, get("/api/v1/applications", token(KEY, claims().audience(List.of("account")).build())).statusCode());
        assertEquals(401, get("/api/v1/applications", token(KEY, claims()
                .issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(600)).build())).statusCode());
        // подпись чужим ключом с тем же kid
        assertEquals(401, get("/api/v1/applications", token(generateKey("test-key"), claims().build())).statusCode());
        assertEquals(401, get("/api/v1/applications", "not-a-jwt").statusCode());
    }

    @Test
    void healthIsPublic() throws Exception {
        assertEquals(200, get("/actuator/health", null).statusCode());
    }

    @Test
    void correctionActorComesFromVerifiedToken() throws Exception {
        String jwt = token(KEY, claims().claim("preferred_username", "anna").build());
        String base = "http://localhost:" + port;
        var created = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/applications"))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String appPath = created.headers().firstValue("Location").orElseThrow();
        String boundary = "jwt-correction-boundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\nAPPLICATION\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\ntest\r\n--" + boundary + "--\r\n";
        var upload = client.send(HttpRequest.newBuilder(URI.create(base + appPath + "/documents"))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, upload.statusCode());
        String docPath = upload.headers().firstValue("Location").orElseThrow();
        var patch = client.send(HttpRequest.newBuilder(URI.create(base + docPath))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"fields\":[{\"name\":\"inn\",\"value\":\"6161234567\"}],\"correctedBy\":\"forged\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, patch.statusCode());
        assertTrue(patch.body().contains("\"correctedBy\":\"anna\""));
        assertFalse(patch.body().contains("forged"));
        var journal = get(appPath + "/journal", jwt);
        assertTrue(journal.body().contains("\"actor\":\"anna\""));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JwtClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("user-1")
                .audience(List.of(AUDIENCE, "account"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("preferred_username", "specialist")
                .claim("realm_access", Map.of("roles", List.of("specialist", "offline_access")));
    }

    private static String token(RSAKey key, JwtClaimsSet claims) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(keyId).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer(RSAKey key) {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
