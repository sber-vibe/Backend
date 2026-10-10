package org.example.subsidy.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationChunkIntegrationTest {
    @Test void chunksKeepSourcesFillMissingFieldsAndDoNotGuessConflictingAmounts() throws Exception {
        var json = JsonMapper.builder().build();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            int index = calls.getAndIncrement();
            assertTrue(json.readTree(request.get("prompt").asString()).get("document_text").asString().length() <= 4000);
            if (index > 0) assertTrue(request.get("system").asString().contains("Не меняй тип"));
            String extracted = """
                    {"document_type":"APPLICATION","confidence":0.93,"fields":[
                    {"name":"company_name","value":"Компания","confidence":0.93,"source":"Источник организации"},
                    {"name":"inn","value":%s,"confidence":0.9,"source":"ИНН заявителя"},
                    {"name":"requested_amount","value":%s,"confidence":0.95,"source":"Сумма из фрагмента"}]}
                    """.formatted(index == 0 ? "null" : "\"6161234567\"", index == 0 ? "100" : "200");
            byte[] body = json.writeValueAsString(Map.of("response", extracted, "done_reason", "stop")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new VisionAnalysisClient("http://localhost:" + server.getAddress().getPort(), "test-model");
            var result = ApplicationDocumentRecognizer.parse(client.analyzeDocumentContent("A".repeat(4000) + "B".repeat(1000), List.of()));
            assertEquals(2, calls.get());
            var fields = new HashMap<String, org.example.subsidy.Models.ExtractedField>(); result.fields().forEach(field -> fields.put(field.name(), field));
            assertEquals("Компания", fields.get("company_name").value());
            assertEquals("Источник организации", fields.get("company_name").source());
            assertEquals("6161234567", fields.get("inn").value());
            assertNull(fields.get("requested_amount").value());
            assertEquals("NOT_RECOGNIZED", fields.get("requested_amount").status());
            assertNull(fields.get("requested_amount").confidence());
        } finally { server.stop(0); }
    }
}
