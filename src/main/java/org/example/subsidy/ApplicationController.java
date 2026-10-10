package org.example.subsidy;

import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.example.subsidy.Models.*;

@RestController
@RequestMapping("/api/v1")
public class ApplicationController {
    private final ApplicationService service;
    private final PreviewService previews;

    public ApplicationController(ApplicationService service, PreviewService previews) {
        this.service = service;
        this.previews = previews;
    }

    @GetMapping("/document-types")
    public List<DocumentType> documentTypes() {
        return service.documentTypes();
    }

    @GetMapping("/programs")
    public List<Program> programs() { return service.programs(); }

    @PostMapping("/applications")
    public ResponseEntity<ApplicationView> create(@Valid @RequestBody CreateApplication request) {
        var application = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/applications/" + application.id())).body(application);
    }

    @GetMapping("/applications")
    public List<ApplicationView> list() {
        return service.list();
    }

    @GetMapping("/applications/{id}")
    public ApplicationView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping(value = "/applications/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentView> upload(@PathVariable UUID id, @RequestParam(required = false) DocumentType type,
                                              @RequestParam MultipartFile file) throws IOException {
        var document = service.upload(id, type, file);
        return ResponseEntity.created(URI.create("/api/v1/applications/" + id + "/documents/" + document.id()))
                .body(document);
    }

    @GetMapping("/applications/{id}/documents/{documentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable UUID documentId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + documentId + ".bin\"")
                .header("X-Content-Type-Options", "nosniff").body(service.download(id, documentId));
    }

    @DeleteMapping("/applications/{id}/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        service.deleteDocument(id, documentId);
        previews.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/applications/{id}/check")
    public CheckReport check(@PathVariable UUID id) {
        return service.check(id);
    }

    @PatchMapping("/applications/{id}/documents/{documentId}")
    public DocumentView patch(@PathVariable UUID id, @PathVariable UUID documentId,
                              @Valid @RequestBody DocumentPatch patch, Principal principal) {
        return service.patch(id, documentId, patch, principal == null ? null : principal.getName());
    }

    @PostMapping("/applications/{id}/documents/{documentId}/reprocess")
    public DocumentView reprocess(@PathVariable UUID id, @PathVariable UUID documentId) {
        return service.reprocess(id, documentId);
    }

    @GetMapping("/applications/{id}/journal")
    public List<JournalEntry> journal(@PathVariable UUID id) {
        return service.journal(id);
    }

    @PostMapping("/applications/{id}/decision")
    public ApplicationView decision(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, Principal principal) {
        return service.decide(id, request, principal == null ? null : principal.getName());
    }

    @GetMapping("/applications/{id}/documents/{documentId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id, @PathVariable UUID documentId) {
        var document = service.document(id, documentId);
        var preview = previews.request(documentId, document.filename(), () -> service.download(id, documentId));
        if (!preview.ready()) return ResponseEntity.accepted().header("Retry-After", "3").build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header("Cache-Control", "no-store").body(preview.html());
    }
}
