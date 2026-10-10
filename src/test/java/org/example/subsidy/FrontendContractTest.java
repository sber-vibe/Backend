package org.example.subsidy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.example.subsidy.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class FrontendContractTest {
    private ExtractedField field(String name, Object value) {
        return new ExtractedField(name, value, value == null ? "NOT_RECOGNIZED" : "RECOGNIZED", 0.95, "Источник", false, null, null, null);
    }
    private List<ExtractedField> fields(boolean certification) {
        return List.of(field("company_name", "ООО Пример"), field("inn", "6161234567"), field("project_name", "Проект"),
                field("requested_amount", new BigDecimal("100")), field("requires_certification", certification ? "true" : "false"));
    }
    private CheckSettings settings() {
        var settings = new CheckSettings();
        settings.saveProgram("TEST", new Program("TEST", "Тестовая программа", true, List.of(
                new ProgramDocument(DocumentType.APPLICATION, "REQUIRED"),
                new ProgramDocument(DocumentType.CONFORMITY_CERTIFICATE, "CONDITIONAL", "requires_certification=true"))), true, "admin");
        var manual = settings.snapshot().get("MANUAL_REVIEW_REQUIRED");
        settings.saveRule(manual.code(), new Rule(manual.code(), manual.name(), null, false, manual.severity(), List.of(), manual.params()), "admin");
        return settings;
    }
    private MockMultipartFile file(String name) { return new MockMultipartFile("file", name, "text/plain", "test".getBytes()); }

    @Test void completenessUsesProgramAndConditionalFieldsAndOutcomesAreExplicit() throws Exception {
        var settings = settings();
        try (var fixture = new Fixture(new ApplicationService((data, name) -> new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.95, fields(false)), Runnable::run, settings, 240))) {
            var service = fixture.service;
            UUID id = service.create(new CreateApplication("TEST")).id();
            var uploaded = service.upload(id, null, file("one.txt"));
            assertNull(uploaded.outcome());
            assertEquals("PASSED", service.check(id).status());
            assertEquals("PASSED", service.get(id).documents().get(0).outcome());
            assertEquals(1, service.get(id).report().completeness().size());
            service.patch(id, uploaded.id(), new DocumentPatch(null, List.of(new FieldPatch("requires_certification", "true"))), "anna");
            assertNull(service.get(id).report());
            assertNull(service.get(id).documents().get(0).outcome());
            assertEquals("ATTENTION", service.check(id).status());
            assertTrue(service.get(id).report().completeness().stream().anyMatch(row -> "CONDITIONAL_MISSING".equals(row.status())));
            assertTrue(service.get(id).report().findings().stream().allMatch(finding -> finding.rule() != null));
            service.patch(id, uploaded.id(), new DocumentPatch(DocumentType.UNKNOWN, null), "anna");
            assertEquals("ERRORS", service.check(id).status());
            assertEquals("ATTENTION", service.get(id).documents().get(0).outcome());
        }
    }
    @Test void changedRuleAppliesNextCheckAndDecisionSurvivesReportInvalidation() throws Exception {
        var settings = settings();
        var values = new ArrayList<>(fields(false));
        values.add(field("total_expenses", new BigDecimal("200")));
        values.add(field("own_funds", new BigDecimal("100")));
        try (var fixture = new Fixture(new ApplicationService((data, name) -> new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.95, values), Runnable::run, settings, 240))) {
            var service = fixture.service;
            UUID id = service.create(new CreateApplication("TEST")).id();
            var doc = service.upload(id, DocumentType.APPLICATION, file("app.txt"));
            service.upload(id, DocumentType.COST_JUSTIFICATION, file("cost.txt"));
            assertEquals("PASSED", service.check(id).status());
            var rule = settings.snapshot().get("OWN_FUNDS_SHARE");
            settings.saveRule(rule.code(), new Rule(rule.code(), rule.name(), null, true, "ERROR", List.of(DocumentType.COST_JUSTIFICATION), Map.of("minOwnFundsShare", 0.75)), "admin");
            var report = service.check(id);
            assertEquals("ERRORS", report.status());
            assertTrue(report.findings().stream().anyMatch(finding -> "OWN_FUNDS_SHARE".equals(finding.rule()) && "ERROR".equals(finding.severity())));
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.decide(id, new DecisionRequest(DecisionCode.RETURNED, " "), "anna"));
            var decision = service.decide(id, new DecisionRequest(DecisionCode.RETURNED, "Уточнить расходы"), "anna").decision();
            assertEquals("anna", decision.decidedBy());
            service.patch(id, doc.id(), new DocumentPatch(null, List.of(new FieldPatch("company_name", "Исправлено"))), "anna");
            assertNull(service.get(id).report());
            assertEquals(decision, service.get(id).decision());
            assertTrue(service.journal(id).stream().anyMatch(entry -> "DECISION_MADE".equals(entry.action())));
            assertTrue(settings.journal(200).stream().anyMatch(entry -> "RULE_CHANGED".equals(entry.action())));
        }
    }
    @Test void stuckJobFailsAndNextJobRunsAndRetryIsJournalled() throws Exception {
        var settings = settings();
        var queue = new ArrayDeque<Runnable>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var fixture = new Fixture(new ApplicationService((data, name) -> {
            if (attempts.getAndIncrement() == 0) Thread.sleep(30_000);
            return new DocumentRecognizer.Recognition(DocumentType.APPLICATION, 0.95, fields(false));
        }, queue::add, settings, 1))) {
            var service = fixture.service;
            UUID id = service.create(new CreateApplication("TEST")).id();
            var first = service.upload(id, null, file("first.txt"));
            service.upload(id, null, file("second.txt"));
            assertEquals("PROCESSING", service.check(id).status());
            queue.remove().run();
            queue.remove().run();
            assertEquals("FAILED", service.get(id).documents().get(0).processingStatus());
            assertEquals("DONE", service.get(id).documents().get(1).processingStatus());
            assertNotEquals("PASSED", service.check(id).status());
            assertEquals("ERRORS", service.get(id).documents().get(0).outcome());
            assertNotNull(service.get(id).documents().get(0).processingError());
            service.reprocess(id, first.id());
            assertTrue(service.journal(id).stream().anyMatch(entry -> "DOCUMENT_REPROCESS_REQUESTED".equals(entry.action())));
            queue.remove().run();
            assertEquals("DONE", service.get(id).documents().get(0).processingStatus());
        }
    }
    private record Fixture(ApplicationService service) implements AutoCloseable {
        public void close() { service.stop(); }
    }
}
