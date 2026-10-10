package org.example.subsidy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.example.subsidy.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminPersistenceTest {
    @TempDir Path directory;
    private Rule rule(Rule current, double share) {
        return new Rule(current.code(), current.name(), current.description(), current.enabled(), current.severity(),
                current.documentTypes(), Map.of("minOwnFundsShare", share), current.paramSchema(), current.version(), "forged", current.updatedAt());
    }
    private DocumentRecognizer recognizer() {
        return (data, filename) -> new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.95, List.of(
                field("company_name", "Компания"), field("inn", "6161234567"), field("project_name", "Проект"),
                field("requested_amount", new BigDecimal("45")), field("total_expenses", new BigDecimal("100")), field("own_funds", new BigDecimal("55"))));
    }
    private ExtractedField field(String name, Object value) { return new ExtractedField(name, value, "RECOGNIZED", 0.95, "Источник", false, null, null, null); }
    private void waitDone(ApplicationService applications, UUID id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (applications.get(id).documents().stream().anyMatch(document -> !Set.of("DONE", "FAILED").contains(document.processingStatus()))) {
            if (System.nanoTime() > deadline) fail("Processing did not finish"); Thread.sleep(20);
        }
        assertTrue(applications.get(id).documents().stream().allMatch(document -> "DONE".equals(document.processingStatus())));
    }
    @Test void versionsThresholdHistoryAndApplicationSurviveDatabaseReopen() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("subsidy").toString().replace('\\', '/');
        var store = new StateStore(url); var settings = new CheckSettings(store);
        assertEquals(3, settings.programs(true).size());
        settings.saveProgram("TEST", new Program("TEST", "Тест", true, List.of(new ProgramDocument(DocumentType.APPLICATION, "REQUIRED"),
                new ProgramDocument(DocumentType.COST_JUSTIFICATION, "REQUIRED"))), true, "admin");
        var rule = settings.snapshot().get("OWN_FUNDS_SHARE");
        var applications = new ApplicationService(recognizer(), settings, 240, store);
        UUID id; UUID documentId;
        try {
            id = applications.create(new CreateApplication("TEST")).id();
            documentId = applications.upload(id, DocumentType.APPLICATION, new MockMultipartFile("file", "app.txt", "text/plain", "Original".getBytes())).id();
            applications.upload(id, DocumentType.COST_JUSTIFICATION, new MockMultipartFile("file", "cost.txt", "text/plain", "Costs".getBytes()));
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> applications.decide(id, new DecisionRequest(DecisionCode.ACCEPTED, null), "anna")).getStatusCode().value());
            waitDone(applications, id);
            assertTrue(applications.check(id).findings().stream().anyMatch(finding -> "OWN_FUNDS_SHARE".equals(finding.code()) && "OK".equals(finding.severity())));
            var changed = settings.saveRule(rule.code(), rule(rule, 0.6), "admin");
            assertEquals(rule.version() + 1, changed.version()); assertEquals("admin", changed.updatedBy());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> settings.saveRule(rule.code(), rule(rule, 0.7), "other")).getStatusCode().value());
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> settings.saveRule(rule.code(), rule(changed, 1.01), "admin")).getStatusCode().value());
            assertEquals("ERRORS", applications.check(id).status());
            applications.decide(id, new DecisionRequest(DecisionCode.ACCEPTED, null), "anna");
            applications.decide(id, new DecisionRequest(DecisionCode.RETURNED, "Уточнить затраты"), "anna");
            assertNotNull(applications.journal(id).get(applications.journal(id).size() - 1).before());
        } finally { applications.stop(); }
        var reopened = new StateStore(url); var restoredSettings = new CheckSettings(reopened);
        assertEquals(0.6, ((Number) restoredSettings.snapshot().get(rule.code()).params().get("minOwnFundsShare")).doubleValue());
        assertEquals(1, restoredSettings.journal(100, null, "RULE_CHANGED").size());
        var event = restoredSettings.journal(100, null, "RULE_CHANGED").get(0);
        assertNotNull(event.before()); assertNotNull(event.after()); assertEquals("RULE", event.objectType());
        assertTrue(restoredSettings.journal(100, event.at(), "RULE_CHANGED").isEmpty());
        var restored = new ApplicationService(recognizer(), restoredSettings, 240, reopened);
        try {
            assertEquals(DecisionCode.RETURNED, restored.get(id).decision().decision());
            assertEquals("anna", restored.get(id).decision().decidedBy());
            assertArrayEquals("Original".getBytes(), restored.download(id, documentId));
            assertEquals(new BigDecimal("45"), restored.get(id).requestedAmount());
            assertEquals("ERRORS", restored.get(id).report().status());
            assertEquals(2, restored.journal(id).stream().filter(entry -> "DECISION_MADE".equals(entry.action())).count());
        } finally { restored.stop(); }
    }
    @Test void projectAttributesHaveTrueFalseUnknownSemanticsAndInvalidateReport() throws Exception {
        var settings = new CheckSettings();
        settings.saveProgram("TEST", new Program("TEST", "Тест", true, List.of(new ProgramDocument(DocumentType.APPLICATION, "REQUIRED"),
                new ProgramDocument(DocumentType.CONFORMITY_CERTIFICATE, "CONDITIONAL", "если нужна сертификация", "requiresCertification"))), true, "admin");
        var applications = new ApplicationService(recognizer(), Runnable::run, settings, 240);
        try {
            UUID id = applications.create(new CreateApplication("TEST")).id();
            assertTrue(applications.check(id).completeness().stream().anyMatch(row -> "CONDITIONAL_UNKNOWN".equals(row.status())));
            applications.patchApplication(id, new ApplicationPatch(Map.of("requiresCertification", true)), "anna");
            assertNull(applications.get(id).report());
            assertTrue(applications.check(id).completeness().stream().anyMatch(row -> "CONDITIONAL_MISSING".equals(row.status())));
            applications.patchApplication(id, new ApplicationPatch(Map.of("requiresCertification", false)), "anna");
            assertTrue(applications.check(id).completeness().stream().noneMatch(row -> row.type() == DocumentType.CONFORMITY_CERTIFICATE));
            var unknown = new HashMap<String, Boolean>(); unknown.put("requiresCertification", null);
            applications.patchApplication(id, new ApplicationPatch(unknown), "anna");
            assertTrue(applications.check(id).completeness().stream().anyMatch(row -> "CONDITIONAL_UNKNOWN".equals(row.status())));
            assertNotNull(applications.journal(id).stream().filter(entry -> "APPLICATION_ATTRIBUTES_CHANGED".equals(entry.action())).findFirst().orElseThrow().before());
        } finally { applications.stop(); }
    }
}
