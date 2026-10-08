package org.example.subsidy;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {
    }

    public record CreateApplication(
            @NotBlank @Size(max = 200) String applicantName,
            @NotBlank @Pattern(regexp = "[0-9]{10}|[0-9]{12}") String inn,
            @NotBlank @Size(max = 300) String projectName,
            @NotNull @DecimalMin("0.01") BigDecimal requestedAmount) {
    }

    public record ApplicationView(UUID id, String applicantName, String inn, String projectName,
                                  BigDecimal requestedAmount, Instant createdAt,
                                  List<DocumentView> documents, CheckReport report) {
    }

    public record DocumentView(UUID id, DocumentType type, String filename, long size, Instant uploadedAt) {
    }

    public record Finding(String code, String message, DocumentType documentType) {
    }

    public record CheckReport(String status, Instant checkedAt, List<Finding> findings) {
    }
}
