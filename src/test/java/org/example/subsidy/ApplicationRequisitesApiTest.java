package org.example.subsidy;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP endpoints, parser subprocess and Ollama client, with a local model response fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationRequisitesApiTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpServer MODEL = startModel();
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("ai.ollama.url", () -> "http://localhost:" + MODEL.getAddress().getPort());
    }
    @AfterAll static void stopModel() { MODEL.stop(0); }

    private static HttpServer startModel() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", exchange -> {
                var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                var document = JSON.readTree(request.get("prompt").asString());
                String content = document.get("document_text").asString();
                String response = content.contains("Заявитель") ? """
                        {"document_type":"APPLICATION","confidence":0.94,"fields":[
                          {"name":"company_name","value":"ООО Ромашка","confidence":0.94,"source":"Заявитель: ООО Ромашка"},
                          {"name":"inn","value":null,"confidence":null,"source":null},
                          {"name":"requested_amount","value":850000,"confidence":0.75,"source":"Субсидия: 850000"},
                          {"name":"date","value":"2026-10-10","confidence":0.9,"source":"Дата: 2026-10-10"}
                        ]}
                        """ : "{\"document_type\":\"UNKNOWN\",\"confidence\":null,\"fields\":[]}";
                byte[] body = JSON.writeValueAsString(Map.of("response", response, "done_reason", "stop")).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private HttpResponse<String> request(String path, String method, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void emptyAndNullProgramRequestsCreateEmptyCards() throws Exception {
        for (String body : List.of("{}", "{\"programCode\":null}", "{\"programCode\":\"INNOVATION\"}")) {
            var response = request("/api/v1/applications", "POST", body);
            assertEquals(201, response.statusCode());
            var app = JSON.readTree(response.body());
            assertTrue(app.get("applicantName").isNull());
            assertTrue(app.get("inn").isNull());
            assertTrue(app.get("requestedAmount").isNull());
            assertEquals(5, app.get("requisites").size());
            assertFalse(app.get("programDetected").asBoolean());
        }
    }

    @Test
    void uploadWithoutTypeRunsModelAndRepeatedPatchesUpdateCardAndJournal() throws Exception {
        var created = request("/api/v1/applications", "POST", "{}");
        String appPath = created.headers().firstValue("Location").orElseThrow();
        String boundary = "requisites-api-boundary";
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"application.txt\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\nЗаявитель: ООО Ромашка\nСубсидия: 850000\nДата: 2026-10-10\r\n--" + boundary + "--\r\n";
        var uploaded = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + appPath + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode());
        assertEquals("UPLOADED", JSON.readTree(uploaded.body()).get("processingStatus").asString());
        String docPath = uploaded.headers().firstValue("Location").orElseThrow();
        JsonNode app = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            app = JSON.readTree(request(appPath, "GET", null).body());
            if (Set.of("DONE", "FAILED").contains(app.get("documents").get(0).get("processingStatus").asString())) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals("DONE", app.get("documents").get(0).get("processingStatus").asString(), app.toString());
        assertEquals("ООО Ромашка", app.get("applicantName").asString());
        assertEquals("850000", app.get("requestedAmount").toString());
        assertTrue(app.get("inn").isNull());
        assertEquals(200, request(docPath, "PATCH", "{\"fields\":[{\"name\":\"requested_amount\",\"value\":900000}]}").statusCode());
        var second = request(docPath, "PATCH", "{\"fields\":[{\"name\":\"requested_amount\",\"value\":950000},{\"name\":\"inn\",\"value\":\"6161234567\"}]}");
        assertEquals(200, second.statusCode());
        var field = find(JSON.readTree(second.body()).get("fields"), "requested_amount");
        assertEquals("850000", field.get("originalValue").toString());
        assertTrue(field.get("manuallyCorrected").asBoolean());
        assertEquals("Специалист", field.get("correctedBy").asString());
        app = JSON.readTree(request(appPath, "GET", null).body());
        assertEquals("950000", app.get("requestedAmount").toString());
        assertEquals("6161234567", app.get("inn").asString());
        assertEquals("850000", find(app.get("requisites"), "requested_amount").get("originalValue").toString());
        assertEquals(200, request(appPath + "/journal", "GET", null).statusCode());
        assertTrue(request(appPath + "/journal", "GET", null).body().contains("FIELD_CHANGED"));
        assertEquals(400, request(docPath, "PATCH", "{\"fields\":[{\"name\":\"requested_amount\",\"value\":\"не число\"}]}").statusCode());
        var invalid = request(docPath, "PATCH", "{\"fields\":[null]}");
        assertEquals(400, invalid.statusCode());
        assertEquals("Проверьте формат запроса и значения полей", JSON.readTree(invalid.body()).get("detail").asString());
    }

    private static JsonNode find(JsonNode fields, String name) {
        for (var field : fields) if (name.equals(field.get("name").asString())) return field;
        throw new AssertionError("Missing field " + name);
    }

    @Test
    void officePreviewIsPendingThenStaticHtmlAndDecisionIsStored() throws Exception {
        String appPath = request("/api/v1/applications", "POST", "{\"programCode\":\"INNOVATION\"}").headers().firstValue("Location").orElseThrow();
        for (String extension : List.of("docx", "xls", "xlsx")) {
            byte[] content;
            try (var output = new java.io.ByteArrayOutputStream()) {
                if ("docx".equals(extension)) {
                    try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
                        word.createParagraph().createRun().setText("Документ <script>alert(1)</script>"); word.write(output);
                    }
                } else {
                    try (org.apache.poi.ss.usermodel.Workbook book = "xls".equals(extension) ? new org.apache.poi.hssf.usermodel.HSSFWorkbook() : new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
                        book.createSheet("Расходы").createRow(0).createCell(0).setCellValue("<script>alert(1)</script>"); book.write(output);
                    }
                }
                content = output.toByteArray();
            }
            String boundary = "office-preview-boundary";
            var multipart = new java.io.ByteArrayOutputStream();
            multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test." + extension + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            multipart.write(content); multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var upload = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + appPath + "/documents"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, upload.statusCode(), upload.body());
            String docPath = upload.headers().firstValue("Location").orElseThrow();
            var preview = request(docPath + "/preview", "GET", null);
            assertEquals(202, preview.statusCode());
            assertEquals("3", preview.headers().firstValue("Retry-After").orElseThrow());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            do { Thread.sleep(20); preview = request(docPath + "/preview", "GET", null); }
            while (preview.statusCode() == 202 && System.nanoTime() < deadline);
            assertEquals(200, preview.statusCode(), preview.body());
            assertTrue(preview.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
            assertTrue(preview.body().contains("&lt;script&gt;"));
            assertFalse(preview.body().contains("<script>"));
            assertTrue(preview.headers().firstValue("Content-Security-Policy").orElseThrow().contains("sandbox"));
            var original = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + docPath)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(content, original.body());
        }
        var decision = request(appPath + "/decision", "POST", "{\"decision\":\"RETURNED\",\"comment\":\"Дослать документы\"}");
        assertEquals(200, decision.statusCode());
        assertEquals("RETURNED", JSON.readTree(request(appPath, "GET", null).body()).get("decision").get("decision").asString());
        assertEquals(400, request(appPath + "/decision", "POST", "{\"decision\":\"REJECTED\",\"comment\":\" \"}").statusCode());
        assertTrue(request(appPath + "/journal", "GET", null).body().contains("DECISION_MADE"));
    }

    @Test
    void adminProgramCrudAndValidationWorkThroughHttp() throws Exception {
        var body = "{\"code\":\"HTTP_TEST\",\"name\":\"Программа\",\"active\":true,\"documents\":[{\"type\":\"APPLICATION\",\"requirement\":\"REQUIRED\"}]}";
        assertEquals(201, request("/api/v1/admin/programs", "POST", body).statusCode());
        assertTrue(request("/api/v1/programs", "GET", null).body().contains("HTTP_TEST"));
        assertEquals(200, request("/api/v1/admin/programs/HTTP_TEST", "PUT", body.replace("\"active\":true", "\"active\":false")).statusCode());
        assertFalse(request("/api/v1/programs", "GET", null).body().contains("HTTP_TEST"));
        assertEquals(400, request("/api/v1/applications", "POST", "{\"programCode\":\"HTTP_TEST\"}").statusCode());
        assertEquals(400, request("/api/v1/admin/programs/HTTP_TEST", "PUT", body.replace("HTTP_TEST", "OTHER")).statusCode());
        assertEquals(400, request("/api/v1/admin/programs", "POST", "{}").statusCode());
        var rules = JSON.readTree(request("/api/v1/admin/rules", "GET", null).body());
        var rule = rules.get(0);
        assertEquals(200, request("/api/v1/admin/rules/" + rule.get("code").asString(), "PUT", rule.toString()).statusCode());
        assertTrue(request("/api/v1/admin/journal", "GET", null).body().contains("PROGRAM_CHANGED"));
    }

    @Test
    void elevenMiBPdfPassesMultipartAndIsolatedParser() throws Exception {
        byte[] pdf;
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(output);
            pdf = Arrays.copyOf(output.toByteArray(), 11 * 1024 * 1024);
            Arrays.fill(pdf, output.size(), pdf.length, (byte) ' ');
        }
        String appPath = request("/api/v1/applications", "POST", "{}").headers().firstValue("Location").orElseThrow();
        String boundary = "large-pdf-boundary";
        var multipart = new java.io.ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var uploaded = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + appPath + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        assertEquals(11 * 1024 * 1024, JSON.readTree(uploaded.body()).get("size").asInt());
        JsonNode app;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            app = JSON.readTree(request(appPath, "GET", null).body());
            if (Set.of("DONE", "FAILED").contains(app.get("documents").get(0).get("processingStatus").asString())) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals("DONE", app.get("documents").get(0).get("processingStatus").asString(), app.toString());
    }
}
