package org.example.subsidy;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {
    }

    public record CreateApplication(@Size(max = 100) String programCode) {
    }

    public record ApplicationView(UUID id, String applicantName, String inn, String projectName,
                                  BigDecimal requestedAmount, Instant createdAt,
                                  List<DocumentView> documents, CheckReport report,
                                  String programCode, String programName, boolean programDetected,
                                  List<Requisite> requisites, Decision decision) {
    }

    public record DocumentView(UUID id, DocumentType type, String filename, long size, Instant uploadedAt,
                               String processingStatus, String processingError, DocumentType detectedType,
                               Double confidence, String typeSource, String typeChangedBy, Instant typeChangedAt,
                               List<ExtractedField> fields, String outcome) {
    }

    public record ExtractedField(String name, Object value, String status, Double confidence, String source,
                                 boolean manuallyCorrected, Object originalValue, String correctedBy,
                                 Instant correctedAt) {
    }

    public record Requisite(String name, Object value, String status, UUID documentId, Double confidence,
                            String source, boolean manuallyCorrected, Object originalValue,
                            String correctedBy, Instant correctedAt) {
    }

    public record FieldPatch(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String name, Object value) {
    }

    public record DocumentPatch(DocumentType type, @Size(max = 100) List<@NotNull @Valid FieldPatch> fields) {
    }

    public record Finding(String code, String message, DocumentType documentType, String severity,
                          UUID documentId, String field, String rule) {
        public Finding(String code, String message, DocumentType documentType, String severity, UUID documentId, String field) {
            this(code, message, documentType, severity, documentId, field, code);
        }
    }

    public record JournalEntry(UUID id, Instant at, String actor, String actorType, String action,
                               String message, UUID applicationId, UUID documentId, String outcome, String reason) {
    }

    public record Completeness(DocumentType type, String status, String requirement, List<UUID> documentIds,
                                boolean canUpload, String condition) {
        public Completeness(DocumentType type, String status, String requirement, List<UUID> documentIds, boolean canUpload) {
            this(type, status, requirement, documentIds, canUpload, null);
        }
    }

    public record Program(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String code,
                          @NotBlank @Size(max = 200) String name, boolean active,
                          @NotNull @Size(max = 30) List<@NotNull @Valid ProgramDocument> documents,
                          @Size(max = 4000) String description) {
        public Program(String code, String name, boolean active, List<ProgramDocument> documents) {
            this(code, name, active, documents, null);
        }
    }
    public record ProgramDocument(@NotNull DocumentType type,
                                  @NotNull @Pattern(regexp = "REQUIRED|CONDITIONAL") String requirement,
                                  @Size(max = 1000) String condition) {
        public ProgramDocument(DocumentType type, String requirement) { this(type, requirement, null); }
    }
    public enum DecisionCode { ACCEPTED, RETURNED, REJECTED }
    public record DecisionRequest(@NotNull DecisionCode decision, @Size(max = 4000) String comment) { }
    public record Decision(DecisionCode decision, String comment, String decidedBy, Instant decidedAt) { }
    public record Rule(@NotBlank String code, @NotBlank @Size(max = 200) String name,
                       @Size(max = 4000) String description, boolean enabled,
                       @NotNull @Pattern(regexp = "ERROR|WARNING|INFO") String severity,
                       @Size(max = 30) List<@NotNull DocumentType> documentTypes,
                       java.util.Map<String, Object> params) { }

    public record CheckReport(String status, Instant checkedAt, List<Finding> findings, List<Completeness> completeness) {
    }
}
