package org.example.subsidy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.example.subsidy.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationRequisitesTest {
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private DocumentRecognizer.Recognition next = result("Компания", new BigDecimal("850000"));
    private final ApplicationService service = new ApplicationService((data, file) -> next, queue::add);

    private static DocumentRecognizer.Recognition result(String company, BigDecimal amount) {
        return new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.93, List.of(
                field("company_name", company, 0.93), field("inn", null, null), field("requested_amount", amount, 0.7)));
    }
    private static ExtractedField field(String name, Object value, Double confidence) {
        return new ExtractedField(name, value, value == null ? "NOT_RECOGNIZED" : "RECOGNIZED", confidence,
                value == null ? null : "Источник: " + value, false, null, null, null);
    }
    private static MockMultipartFile file() { return new MockMultipartFile("file", "application.txt", "text/plain", "demo".getBytes()); }
    private UUID create() { return service.create(new CreateApplication(null)).id(); }
    private void process() { queue.removeFirst().run(); }

    @Test
    void emptyCardThenRecognitionUsesSourceAndNoInventedValues() throws Exception {
        UUID id = create();
        assertNull(service.get(id).applicantName());
        assertNull(service.get(id).requestedAmount());
        assertEquals(5, service.get(id).requisites().size());
        var uploaded = service.upload(id, null, file());
        assertEquals("UPLOADED", uploaded.processingStatus());
        assertEquals("PROCESSING", service.check(id).status());
        process();
        var app = service.get(id);
        assertEquals("Компания", app.applicantName());
        assertEquals(new BigDecimal("850000"), app.requestedAmount());
        assertNull(app.inn());
        assertEquals("DONE", app.documents().get(0).processingStatus());
        var company = app.requisites().stream().filter(f -> f.name().equals("company_name")).findFirst().orElseThrow();
        assertEquals(uploaded.id(), company.documentId());
        assertEquals("Источник: Компания", company.source());
        assertEquals("NOT_RECOGNIZED", app.requisites().stream().filter(f -> f.name().equals("inn")).findFirst().orElseThrow().status());
        assertNotEquals("PROCESSING", app.report().status());
        assertTrue(app.report().findings().stream().anyMatch(f -> f.code().equals("LOW_CONFIDENCE") && f.severity().equals("WARNING")));
    }

    @Test
    void repeatedCorrectionsKeepOriginalValueAndActorAndInvalidateReport() throws Exception {
        UUID id = create();
        var document = service.upload(id, null, file()); process(); service.check(id);
        service.patch(id, document.id(), new DocumentPatch(null, List.of(new FieldPatch("requested_amount", 900000))), "anna");
        service.patch(id, document.id(), new DocumentPatch(null, List.of(new FieldPatch("requested_amount", "950000"), new FieldPatch("inn", "6161234567"))), "petr");
        var app = service.get(id);
        assertNull(app.report());
        assertEquals(new BigDecimal("950000"), app.requestedAmount());
        var amount = app.requisites().stream().filter(f -> f.name().equals("requested_amount")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("850000"), amount.originalValue());
        assertEquals("petr", amount.correctedBy());
        assertNotNull(amount.correctedAt());
        assertTrue(amount.manuallyCorrected());
        var inn = app.requisites().stream().filter(f -> f.name().equals("inn")).findFirst().orElseThrow();
        assertNull(inn.originalValue());
        assertEquals("6161234567", inn.value());
        assertEquals(3, service.journal(id).stream().filter(e -> e.action().equals("FIELD_CHANGED")).count());
        var changed = service.patch(id, document.id(), new DocumentPatch(DocumentType.GUARANTEE_LETTER, null), "anna");
        assertEquals(DocumentType.APPLICATION, changed.detectedType());
        assertEquals("SPECIALIST", changed.typeSource());
        assertEquals("anna", changed.typeChangedBy());
        assertNull(service.get(id).requestedAmount());
    }

    @Test
    void firstUploadedApplicationWinsRegardlessOfCorrectionsAndRemoval() throws Exception {
        UUID id = create();
        var first = service.upload(id, null, file()); process();
        next = result("Другая компания", new BigDecimal("1200"));
        var second = service.upload(id, null, file()); process();
        assertEquals("Компания", service.get(id).applicantName());
        service.deleteDocument(id, first.id());
        assertEquals("Другая компания", service.get(id).applicantName());
        service.deleteDocument(id, second.id());
        assertNull(service.get(id).applicantName());
    }

    @Test
    void deletedQueuedDocumentDoesNotReturnOrConsumeModelCall() throws Exception {
        UUID id = create(); var document = service.upload(id, null, file());
        service.deleteDocument(id, document.id()); process();
        assertTrue(service.get(id).documents().isEmpty());
        assertNull(service.get(id).inn());
    }

    @Test
    void programSelectedBySpecialistWinsOverDetectedProgramAndBlankIsNotGuessed() throws Exception {
        next = new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.9, List.of(field("program_code", "INNOVATION", 0.9), field("program_name", "Инновационный проект", 0.9)));
        UUID automatic = create(); service.upload(automatic, null, file()); process();
        assertEquals("INNOVATION", service.get(automatic).programCode());
        assertTrue(service.get(automatic).programDetected());
        UUID selected = service.create(new CreateApplication("SELECTED")).id();
        service.upload(selected, null, file()); process();
        assertEquals("SELECTED", service.get(selected).programCode());
        assertFalse(service.get(selected).programDetected());
        UUID blank = create(); next = result("Компания", null); service.upload(blank, null, file()); process();
        assertNull(service.get(blank).programCode());
    }

    @Test
    void unavailableModelIsFailedInRussianAndRetryRetainsManualCorrections() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new ApplicationService((data, name) -> {
            if (attempts.getAndIncrement() == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
            return result("Компания", new BigDecimal("850000"));
        }, queue::add);
        UUID id = failed.create(new CreateApplication(null)).id();
        var document = failed.upload(id, null, file()); process();
        var state = failed.get(id).documents().get(0);
        assertEquals("FAILED", state.processingStatus());
        assertTrue(state.processingError().startsWith("Модель недоступна"));
        failed.patch(id, document.id(), new DocumentPatch(DocumentType.APPLICATION, List.of(new FieldPatch("requested_amount", 123))), "anna");
        assertEquals("UPLOADED", failed.reprocess(id, document.id()).processingStatus()); process();
        assertEquals(new BigDecimal("123"), failed.get(id).requestedAmount());
        assertNull(failed.get(id).requisites().stream().filter(f -> f.name().equals("requested_amount")).findFirst().orElseThrow().originalValue());
    }

    @Test
    void missingAmountWarnsAndMathUsesOnlyRecognizedNumbers() throws Exception {
        UUID id = create(); next = result("Компания", null);
        service.upload(id, null, file()); process();
        assertTrue(service.check(id).findings().stream().anyMatch(f -> f.code().equals("AMOUNT_NOT_RECOGNIZED") && f.severity().equals("WARNING")));
        var estimate = service.upload(id, DocumentType.COST_ESTIMATE, file()); process();
        service.patch(id, estimate.id(), new DocumentPatch(null, List.of(new FieldPatch("total_expenses", 1500), new FieldPatch("own_funds", 500), new FieldPatch("requested_amount", 1000))), "anna");
        assertTrue(service.check(id).findings().stream().anyMatch(f -> f.code().equals("EXPENSES_SUM") && f.severity().equals("OK")));
        service.patch(id, estimate.id(), new DocumentPatch(null, List.of(new FieldPatch("requested_amount", null))), "anna");
        var report = service.check(id);
        assertFalse(report.findings().stream().anyMatch(f -> f.code().equals("EXPENSES_SUM") && f.severity().equals("OK")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("ARITHMETIC_NOT_CHECKED")));
    }

    @Test
    void queueDoesNotHoldApplicationLockWhileModelIsBusy() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var async = new ApplicationService((data, name) -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout"); return result("Компания", null); });
        try {
            UUID id = async.create(new CreateApplication(null)).id();
            var first = async.upload(id, null, file());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = async.upload(id, null, file());
            assertEquals("UPLOADED", async.get(id).documents().get(1).processingStatus());
            async.patch(id, first.id(), new DocumentPatch(null, List.of(new FieldPatch("company_name", "Исправлено"))), "anna");
            async.deleteDocument(id, second.id()); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!async.get(id).documents().get(0).processingStatus().equals("DONE") && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("Исправлено", async.get(id).applicantName());
            assertEquals(1, async.get(id).documents().size());
        } finally { release.countDown(); async.stop(); }
    }
}
