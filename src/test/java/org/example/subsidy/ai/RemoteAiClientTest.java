package org.example.subsidy.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.example.subsidy.DocumentType;
import static org.junit.jupiter.api.Assertions.*;

class RemoteAiClientTest {
    @Test void sendsMultipartKeyAndMapsLanResponseWithoutInventingConfidence() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        var credential = new AtomicReference<String>();
        server.createContext("/analyze", request -> {
            credential.set(request.getRequestHeaders().getFirst("X-Api-Key"));
            received.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"document_type\":\"APPLICATION\",\"fields\":{\"organization\":\"Тест\",\"requested_amount\":\"100000\"},\"needs_review\":true}".getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json");
            request.sendResponseHeaders(200, body.length);
            request.getResponseBody().write(body); request.close();
        });
        server.start();
        try {
            String key = "a".repeat(32);
            var remote = new RemoteAiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/analyze", key);
            var result = new ApplicationDocumentRecognizer(null, remote).recognize("LAN test document".getBytes(StandardCharsets.UTF_8), "test.txt");
            assertEquals(key, credential.get());
            assertTrue(received.get().contains("filename=\"test.txt\""));
            assertTrue(received.get().contains("LAN test document"));
            assertEquals(DocumentType.APPLICATION, result.detectedType());
            assertNull(result.confidence());
            assertEquals(new BigDecimal("100000"), result.fields().stream().filter(f -> f.name().equals("requested_amount")).findFirst().orElseThrow().value());
            assertTrue(result.fields().stream().anyMatch(f -> f.name().equals("company_name") && "Тест".equals(f.value())));
        } finally { server.stop(0); }
    }
    @Test void mapsConsentAndRejectsInvalidResponseOrMissingCredentials() {
        var result = ApplicationDocumentRecognizer.parse(RemoteAiClient.adapt("{\"document_type\":\"PERSONAL_DATA_CONSENT\",\"fields\":{}}"));
        assertEquals(DocumentType.PD_CONSENT, result.detectedType());
        assertThrows(RuntimeException.class, () -> RemoteAiClient.adapt("{}"));
        assertThrows(RuntimeException.class, () -> new RemoteAiClient("http://127.0.0.1/analyze", ""));
        assertFalse(new RemoteAiClient("", "").enabled());
    }
}
