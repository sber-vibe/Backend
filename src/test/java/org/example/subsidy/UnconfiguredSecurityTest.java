package org.example.subsidy;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "storage.jdbc-url=jdbc:h2:mem:unconfigured;DB_CLOSE_DELAY=-1")
class UnconfiguredSecurityTest {
    @LocalServerPort int port;
    @Test void missingJwtConfigurationDoesNotExposeApi() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : new String[]{"/api/v1/applications", "/api/v1/admin/rules"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode()); assertTrue(response.body().contains("detail"));
        }
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
