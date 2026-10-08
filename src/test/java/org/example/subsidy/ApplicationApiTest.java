package org.example.subsidy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationApiTest {
    @LocalServerPort
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> create(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/applications"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validApplicationCanBeCreatedAndChecked() throws Exception {
        var response = create("""
                {"applicantName":"Demo","inn":"6161000000","projectName":"Innovation","requestedAmount":1000}
                """);
        assertEquals(201, response.statusCode());
        String location = response.headers().firstValue("Location").orElseThrow();
        var report = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + location + "/check"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, report.statusCode());
        assertTrue(report.body().contains("INCOMPLETE"));
    }

    @Test
    void invalidApplicationIsRejected() throws Exception {
        var response = create("""
                {"applicantName":"","inn":"123","projectName":"Demo","requestedAmount":-1}
                """);
        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
    }

    @Test
    void multipartUploadCanBeDownloadedAndDeleted() throws Exception {
        var application = create("""
                {"applicantName":"Demo","inn":"6161000000","projectName":"Upload","requestedAmount":1000}
                """);
        String location = application.headers().firstValue("Location").orElseThrow();
        String boundary = "test-document-boundary";
        String multipart = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"type\"\r\n\r\nAPPLICATION\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"demo.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\ndemo content\r\n--" + boundary + "--\r\n";
        var upload = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + location + "/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, upload.statusCode());
        URI documentUri = URI.create("http://localhost:" + port + upload.headers().firstValue("Location").orElseThrow());
        var download = client.send(HttpRequest.newBuilder(documentUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, download.statusCode());
        assertEquals("demo content", download.body());
        var deletion = client.send(HttpRequest.newBuilder(documentUri).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, deletion.statusCode());
        var missing = client.send(HttpRequest.newBuilder(documentUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());
    }
}
