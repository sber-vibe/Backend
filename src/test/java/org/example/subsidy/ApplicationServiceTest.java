package org.example.subsidy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationServiceTest {
    private final ApplicationService service = new ApplicationService(
            (content, filename) -> new DocumentRecognizer.Recognition(DocumentType.UNKNOWN, null, java.util.List.of()), Runnable::run);

    private UUID create() {
        return service.create(new Models.CreateApplication(null)).id();
    }

    private MockMultipartFile file(String filename) {
        return new MockMultipartFile("file", filename, "text/plain", "demo".getBytes());
    }

    @Test
    void missingDocumentsAreReported() {
        var report = service.check(create());
        assertEquals("INCOMPLETE", report.status());
        assertEquals(3, report.findings().stream().filter(finding -> finding.code().equals("MISSING_DOCUMENT")).count());
    }

    @Test
    void completePackageStillRequiresReviewAndChangesInvalidateReport() throws Exception {
        UUID id = create();
        for (DocumentType type : service.requiredTypes()) {
            service.upload(id, type, file("document.txt"));
        }
        assertEquals("REVIEW_REQUIRED", service.check(id).status());
        var duplicate = service.upload(id, DocumentType.APPLICATION, file("duplicate.txt"));
        assertNull(service.get(id).report());
        assertTrue(service.check(id).findings().stream().anyMatch(finding -> finding.code().equals("DUPLICATE_DOCUMENT")));
        assertArrayEquals("demo".getBytes(), service.download(id, duplicate.id()));
        service.deleteDocument(id, duplicate.id());
        assertNull(service.get(id).report());
        assertThrows(ResponseStatusException.class, () -> service.download(id, duplicate.id()));
    }

    @Test
    void unsafeAndEmptyUploadsAreRejected() {
        UUID id = create();
        assertThrows(ResponseStatusException.class, () -> service.upload(id, DocumentType.APPLICATION, file("../doc.txt")));
        assertThrows(ResponseStatusException.class, () -> service.upload(id, DocumentType.APPLICATION, file("script.exe")));
        assertThrows(ResponseStatusException.class, () -> service.upload(id, DocumentType.APPLICATION,
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])));
    }

    @Test
    void unknownApplicationReturnsNotFound() {
        var exception = assertThrows(ResponseStatusException.class, () -> service.get(UUID.randomUUID()));
        assertEquals(404, exception.getStatusCode().value());
    }
}
