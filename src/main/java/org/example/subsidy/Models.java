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
                                  List<Requisite> requisites) {
    }

    public record DocumentView(UUID id, DocumentType type, String filename, long size, Instant uploadedAt,
                               String processingStatus, String processingError, DocumentType detectedType,
                               Double confidence, String typeSource, String typeChangedBy, Instant typeChangedAt,
                               List<ExtractedField> fields) {
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
                          UUID documentId, String field) {
    }

    public record JournalEntry(UUID id, Instant at, String actor, String actorType, String action,
                               String message, UUID applicationId, UUID documentId, String outcome, String reason) {
    }

    public record Completeness(DocumentType type, String status, String requirement, List<UUID> documentIds,
                                boolean canUpload) { }

    public record Program(String code, String name, boolean active, List<ProgramDocument> documents) { }
    public record ProgramDocument(DocumentType type, String requirement) { }

    public record CheckReport(String status, Instant checkedAt, List<Finding> findings, List<Completeness> completeness) {
    }
}
