package org.example.subsidy;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.example.subsidy.Models.*;

@Service
public class ApplicationService {
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 50 * 1024 * 1024;
    private static final EnumSet<DocumentType> REQUIRED = EnumSet.of(
            DocumentType.APPLICATION, DocumentType.REGISTRATION_EXTRACT, DocumentType.COST_ESTIMATE);
    private final Map<UUID, ApplicationState> applications = new LinkedHashMap<>();
    private long storedBytes;

    public List<DocumentType> requiredTypes() {
        return List.copyOf(REQUIRED);
    }

    public synchronized ApplicationView create(CreateApplication request) {
        if (applications.size() >= 100) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Demo application limit reached");
        }
        var state = new ApplicationState(UUID.randomUUID(), request, Instant.now());
        applications.put(state.id, state);
        return view(state);
    }

    public synchronized List<ApplicationView> list() {
        return applications.values().stream().map(this::view)
                .sorted(Comparator.comparing(ApplicationView::createdAt).reversed()).toList();
    }

    public synchronized ApplicationView get(UUID id) {
        return view(require(id));
    }

    public synchronized DocumentView upload(UUID id, DocumentType type, MultipartFile file) throws IOException {
        var state = require(id);
        if (state.documents.size() >= 20) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Demo document limit reached");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE || storedBytes + file.getSize() > MAX_TOTAL_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Demo storage limit exceeded");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        if (!filename.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|docx|txt|png|jpg|jpeg)$")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Allowed extensions: pdf, docx, txt, png, jpg, jpeg");
        }
        byte[] content = file.getBytes();
        if (content.length > MAX_FILE_SIZE || storedBytes + content.length > MAX_TOTAL_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Demo storage limit exceeded");
        }
        var document = new DocumentView(UUID.randomUUID(), type, filename, content.length, Instant.now());
        state.documents.put(document.id(), new StoredDocument(document, content));
        storedBytes += content.length;
        state.report = null;
        return document;
    }

    public synchronized byte[] download(UUID id, UUID documentId) {
        var document = require(id).documents.get(documentId);
        if (document == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        return document.content().clone();
    }

    public synchronized void deleteDocument(UUID id, UUID documentId) {
        var state = require(id);
        var removed = state.documents.remove(documentId);
        if (removed == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        storedBytes -= removed.content().length;
        state.report = null;
    }

    public synchronized CheckReport check(UUID id) {
        var state = require(id);
        var findings = new ArrayList<Finding>();
        for (DocumentType type : REQUIRED) {
            long count = state.documents.values().stream().filter(document -> document.view().type() == type).count();
            if (count == 0) {
                findings.add(new Finding("MISSING_DOCUMENT", "Отсутствует обязательный документ", type));
            } else if (count > 1) {
                findings.add(new Finding("DUPLICATE_DOCUMENT", "Загружено несколько документов одного типа", type));
            }
        }
        boolean incomplete = findings.stream().anyMatch(finding -> finding.code().equals("MISSING_DOCUMENT"));
        findings.add(new Finding("MANUAL_REVIEW_REQUIRED",
                "Проверена только комплектность. Содержание, подписи и соответствие регламенту требуют проверки экспертом.", null));
        state.report = new CheckReport(incomplete ? "INCOMPLETE" : "REVIEW_REQUIRED", Instant.now(), List.copyOf(findings));
        return state.report;
    }

    private ApplicationState require(UUID id) {
        var state = applications.get(id);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found");
        }
        return state;
    }

    private ApplicationView view(ApplicationState state) {
        return new ApplicationView(state.id, state.request.applicantName(), state.request.inn(),
                state.request.projectName(), state.request.requestedAmount(), state.createdAt,
                state.documents.values().stream().map(StoredDocument::view).toList(), state.report);
    }

    private record StoredDocument(DocumentView view, byte[] content) {
    }

    private static final class ApplicationState {
        private final UUID id;
        private final CreateApplication request;
        private final Instant createdAt;
        private final Map<UUID, StoredDocument> documents = new LinkedHashMap<>();
        private CheckReport report;

        private ApplicationState(UUID id, CreateApplication request, Instant createdAt) {
            this.id = id;
            this.request = request;
            this.createdAt = createdAt;
        }
    }
}
