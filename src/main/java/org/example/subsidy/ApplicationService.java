package org.example.subsidy;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.example.subsidy.Models.*;

@Service
public class ApplicationService {
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 50 * 1024 * 1024;
    private static final List<String> REQUISITES = List.of("company_name", "inn", "kpp", "project_name", "requested_amount");
    private static final EnumSet<DocumentType> REQUIRED = EnumSet.of(DocumentType.APPLICATION, DocumentType.REGISTRATION_EXTRACT, DocumentType.COST_ESTIMATE);
    private final Map<UUID, ApplicationState> applications = new LinkedHashMap<>();
    private final DocumentRecognizer recognizer;
    private final Executor processingQueue;
    private long storedBytes;

    @Autowired
    public ApplicationService(DocumentRecognizer recognizer) {
        this(recognizer, Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "application-document-processing");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ApplicationService(DocumentRecognizer recognizer, Executor processingQueue) {
        this.recognizer = recognizer;
        this.processingQueue = processingQueue;
    }

    @PreDestroy
    public void stop() {
        if (processingQueue instanceof ExecutorService executor) executor.shutdownNow();
    }

    public List<DocumentType> requiredTypes() { return List.copyOf(REQUIRED); }
    public List<DocumentType> documentTypes() { return Arrays.stream(DocumentType.values()).filter(type -> type != DocumentType.UNKNOWN).toList(); }
    public List<Program> programs() {
        return List.of(new Program("INNOVATION", "Инновационный проект", true,
                REQUIRED.stream().map(type -> new ProgramDocument(type, "REQUIRED")).toList()));
    }

    public synchronized ApplicationView create(CreateApplication request) {
        if (applications.size() >= 100) throw error(HttpStatus.CONFLICT, "Достигнут лимит: 100 заявок");
        var state = new ApplicationState(UUID.randomUUID(), blankToNull(request.programCode()), Instant.now());
        applications.put(state.id, state);
        journal(state, null, "APPLICATION_CREATED", "Создана заявка", "Система", "SYSTEM", null, null);
        return view(state);
    }

    public synchronized List<ApplicationView> list() {
        return applications.values().stream().map(this::view).sorted(Comparator.comparing(ApplicationView::createdAt).reversed()).toList();
    }
    public synchronized ApplicationView get(UUID id) { return view(require(id)); }
    public synchronized List<JournalEntry> journal(UUID id) { return List.copyOf(require(id).journal); }

    public synchronized DocumentView upload(UUID id, DocumentType type, MultipartFile file) throws IOException {
        var state = require(id);
        if (state.documents.size() >= 20) throw error(HttpStatus.CONFLICT, "Достигнут лимит: 20 документов в заявке");
        if (file.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Файл пустой");
        if (file.getSize() > MAX_FILE_SIZE || storedBytes + file.getSize() > MAX_TOTAL_SIZE) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Превышен лимит: 50 МБ на файл, 50 МБ на все документы");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.length() > 255 || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) throw error(HttpStatus.BAD_REQUEST, "Недопустимое имя файла");
        if (!filename.toLowerCase(Locale.ROOT).matches(".+\\.(pdf|docx|txt|png|jpg|jpeg)$")) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Допустимые форматы: PDF, DOCX, TXT, PNG, JPG, JPEG");
        byte[] content = file.getBytes();
        if (content.length > MAX_FILE_SIZE || storedBytes + content.length > MAX_TOTAL_SIZE) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Превышен лимит: 50 МБ на файл, 50 МБ на все документы");
        var document = new StoredDocument(UUID.randomUUID(), type, filename, content, Instant.now());
        state.documents.put(document.id, document);
        storedBytes += content.length;
        state.report = null;
        journal(state, document.id, "DOCUMENT_UPLOADED", "Загружен документ «" + filename + "»", "Система", "SYSTEM", "UPLOADED", null);
        var response = document.view();
        enqueue(state, document);
        return response;
    }

    private void enqueue(ApplicationState state, StoredDocument document) {
        long revision = ++document.revision;
        try { processingQueue.execute(() -> process(state.id, document.id, revision)); }
        catch (RejectedExecutionException exception) {
            document.processingStatus = "FAILED";
            document.processingError = "Очередь обработки остановлена. Повторите обработку после запуска сервера.";
            journal(state, document.id, "PROCESSING_FAILED", "Очередь обработки недоступна", "Система", "SYSTEM", "FAILED", document.processingError);
        }
    }

    private void process(UUID id, UUID documentId, long revision) {
        byte[] content;
        String filename;
        synchronized (this) {
            var document = current(id, documentId, revision);
            if (document == null) return;
            document.processingStatus = document.type == null ? "CLASSIFYING" : "EXTRACTING";
            content = document.content;
            filename = document.filename;
        }
        try {
            var result = recognizer.recognize(content, filename);
            synchronized (this) {
                var document = current(id, documentId, revision);
                if (document == null) return;
                var state = require(id);
                document.detectedType = result.detectedType();
                document.confidence = result.confidence();
                if (!"SPECIALIST".equals(document.typeSource)) {
                    document.type = result.detectedType();
                    document.typeSource = "MODEL";
                }
                document.processingStatus = "EXTRACTING";
                var fields = new LinkedHashMap<String, ExtractedField>();
                for (var field : result.fields()) fields.put(field.name(), field);
                // Concurrent manual corrections must survive the model result.
                document.fields.values().stream().filter(ExtractedField::manuallyCorrected).forEach(field -> fields.put(field.name(), field));
                document.fields.clear();
                document.fields.putAll(fields);
                document.processingStatus = "DONE";
                document.processingError = null;
                journal(state, documentId, "DOCUMENT_CLASSIFIED", "Определён тип документа «" + filename + "»", "Модель", "SYSTEM", "DONE", null);
                journal(state, documentId, "FIELDS_EXTRACTED", "Извлечены реквизиты документа «" + filename + "»", "Модель", "SYSTEM", "DONE", null);
                updatePendingReport(state);
            }
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (this) {
                var document = current(id, documentId, revision);
                if (document == null) return;
                document.processingStatus = "FAILED";
                document.processingError = processingError(exception);
                var state = require(id);
                journal(state, documentId, "PROCESSING_FAILED", "Не удалось обработать «" + filename + "»", "Система", "SYSTEM", "FAILED", document.processingError);
                updatePendingReport(state);
            }
        }
    }

    private void updatePendingReport(ApplicationState state) {
        boolean pending = state.report != null && "PROCESSING".equals(state.report.status());
        state.report = pending ? buildReport(state) : null;
    }

    private StoredDocument current(UUID id, UUID documentId, long revision) {
        var state = applications.get(id);
        var document = state == null ? null : state.documents.get(documentId);
        return document != null && document.revision == revision ? document : null;
    }

    public synchronized byte[] download(UUID id, UUID documentId) { return requireDocument(require(id), documentId).content.clone(); }

    public synchronized void deleteDocument(UUID id, UUID documentId) {
        var state = require(id);
        var removed = requireDocument(state, documentId);
        state.documents.remove(documentId);
        storedBytes -= removed.content.length;
        state.report = null;
        journal(state, documentId, "DOCUMENT_DELETED", "Удалён документ «" + removed.filename + "»", "Система", "SYSTEM", null, null);
    }

    public synchronized DocumentView patch(UUID id, UUID documentId, DocumentPatch patch, String actor) {
        var state = require(id);
        var document = requireDocument(state, documentId);
        if (patch.type() == null && (patch.fields() == null || patch.fields().isEmpty())) throw error(HttpStatus.BAD_REQUEST, "Укажите тип документа или поля для исправления");
        var values = new LinkedHashMap<String, Object>();
        if (patch.fields() != null) for (var field : patch.fields()) {
            String name = FieldValues.canonicalName(field.name());
            try { values.put(name, FieldValues.normalize(name, field.value())); }
            catch (IllegalArgumentException exception) { throw error(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        }
        if (document.fields.size() + values.keySet().stream().filter(name -> !document.fields.containsKey(name)).count() > 100) throw error(HttpStatus.BAD_REQUEST, "В документе допускается не более 100 полей");
        Instant now = Instant.now();
        String specialist = blankToNull(actor);
        if (specialist == null) specialist = "Специалист";
        if (patch.type() != null) {
            document.type = patch.type();
            document.typeSource = "SPECIALIST";
            document.typeChangedBy = specialist;
            document.typeChangedAt = now;
            journal(state, documentId, "DOCUMENT_TYPE_CHANGED", "Исправлен тип документа", specialist, "SPECIALIST", null, null);
        }
        for (var entry : values.entrySet()) {
            var previous = document.fields.get(entry.getKey());
            Object original = previous == null ? null : previous.manuallyCorrected() ? previous.originalValue() : previous.value();
            document.fields.put(entry.getKey(), new ExtractedField(entry.getKey(), entry.getValue(), entry.getValue() == null ? "NOT_RECOGNIZED" : "RECOGNIZED", previous == null ? null : previous.confidence(), previous == null ? null : previous.source(), true, original, specialist, now));
            journal(state, documentId, "FIELD_CHANGED", "Исправлено поле «" + entry.getKey() + "»", specialist, "SPECIALIST", null, null);
        }
        state.report = null;
        return document.view();
    }

    public synchronized DocumentView reprocess(UUID id, UUID documentId) {
        var state = require(id);
        var document = requireDocument(state, documentId);
        if (!"FAILED".equals(document.processingStatus)) throw error(HttpStatus.CONFLICT, "Повторная обработка доступна только после ошибки");
        document.processingStatus = "UPLOADED";
        document.processingError = null;
        state.report = null;
        var response = document.view();
        enqueue(state, document);
        return response;
    }

    public synchronized CheckReport check(UUID id) {
        var state = require(id);
        state.report = buildReport(state);
        journal(state, null, "CHECK_COMPLETED", "Выполнена проверка пакета", "Система", "SYSTEM", state.report.status(), null);
        return state.report;
    }

    private CheckReport buildReport(ApplicationState state) {
        var findings = new ArrayList<Finding>();
        var completeness = REQUIRED.stream().map(type -> {
            var ids = state.documents.values().stream().filter(document -> document.type == type).map(document -> document.id).toList();
            return new Completeness(type, ids.isEmpty() ? "MISSING" : "FOUND", "REQUIRED", ids, ids.isEmpty());
        }).toList();
        if (state.documents.values().stream().anyMatch(StoredDocument::processing)) return new CheckReport("PROCESSING", Instant.now(), List.of(new Finding("DOCUMENT_PROCESSING", "Документы ещё обрабатываются. Проверка будет завершена после распознавания.", null, "INFO", null, null)), completeness);
        for (DocumentType type : REQUIRED) {
            long count = state.documents.values().stream().filter(document -> document.type == type).count();
            if (count == 0) findings.add(new Finding("MISSING_DOCUMENT", "Отсутствует обязательный документ: " + typeTitle(type), type, "ERROR", null, null));
            else if (count > 1) findings.add(new Finding("DUPLICATE_DOCUMENT", "Несколько документов одного типа: " + typeTitle(type), type, "WARNING", null, null));
        }
        for (var document : state.documents.values()) {
            if ("FAILED".equals(document.processingStatus)) findings.add(new Finding("PROCESSING_FAILED", document.processingError, document.type, "WARNING", document.id, null));
            for (var field : document.fields.values()) if (field.value() != null && field.confidence() != null && field.confidence() < 0.8 && !field.manuallyCorrected()) findings.add(new Finding("LOW_CONFIDENCE", "Требуется проверить распознавание поля «" + field.name() + "»", document.type, "WARNING", document.id, field.name()));
        }
        var application = firstApplication(state);
        BigDecimal requested = number(application, "requested_amount");
        if (requested == null) findings.add(new Finding("AMOUNT_NOT_RECOGNIZED", "Не удалось проверить: сумма субсидии не распознана", DocumentType.APPLICATION, "WARNING", application == null ? null : application.id, "requested_amount"));
        else if (requested.signum() <= 0) findings.add(new Finding("INVALID_AMOUNT", "Сумма субсидии должна быть больше нуля", DocumentType.APPLICATION, "ERROR", application.id, "requested_amount"));
        for (var document : state.documents.values()) {
            if (document.type == DocumentType.COST_ESTIMATE || document.type == DocumentType.COST_JUSTIFICATION) checkArithmetic(document, findings);
            if (document != application && document.fields.containsKey("requested_amount")) {
                BigDecimal amount = number(document, "requested_amount");
                if (requested == null || amount == null) findings.add(new Finding("AMOUNTS_NOT_CHECKED", "Не удалось сверить суммы между документами: сумма не распознана", document.type, "WARNING", document.id, "requested_amount"));
                else findings.add(new Finding("AMOUNTS_MATCH", requested.compareTo(amount) == 0 ? "Сумма субсидии совпадает с заявлением" : "Сумма субсидии отличается от заявления", document.type, requested.compareTo(amount) == 0 ? "OK" : "ERROR", document.id, "requested_amount"));
            }
        }
        findings.add(new Finding("MANUAL_REVIEW_REQUIRED", "Подписи и соответствие регламенту требуют проверки специалистом", null, "INFO", null, null));
        boolean missing = findings.stream().anyMatch(finding -> "MISSING_DOCUMENT".equals(finding.code()));
        boolean errors = findings.stream().anyMatch(finding -> "ERROR".equals(finding.severity()));
        return new CheckReport(missing ? "INCOMPLETE" : errors ? "ERRORS" : "REVIEW_REQUIRED", Instant.now(), List.copyOf(findings), completeness);
    }

    private static void checkArithmetic(StoredDocument document, List<Finding> findings) {
        BigDecimal total = number(document, "total_expenses"), own = number(document, "own_funds"), subsidy = number(document, "requested_amount");
        if (total == null || own == null || subsidy == null) {
            findings.add(new Finding("ARITHMETIC_NOT_CHECKED", "Не удалось проверить арифметику: общие затраты, собственные средства или сумма субсидии не распознаны", document.type, "WARNING", document.id, "total_expenses"));
            return;
        }
        boolean equal = own.add(subsidy).compareTo(total) == 0;
        findings.add(new Finding("EXPENSES_SUM", equal ? "Собственные средства и субсидия совпадают с общими затратами" : "Сумма собственных средств и субсидии не совпадает с общими затратами", document.type, equal ? "OK" : "ERROR", document.id, "total_expenses"));
        if (total.signum() > 0 && own.signum() >= 0 && own.compareTo(total) <= 0) {
            String share = own.multiply(new BigDecimal("100")).divide(total, 2, java.math.RoundingMode.HALF_UP).toPlainString();
            findings.add(new Finding("OWN_FUNDS_SHARE", "Доля собственных средств: " + share + "%", document.type, "INFO", document.id, "own_funds"));
        } else findings.add(new Finding("INVALID_OWN_FUNDS", "Собственные средства или общие затраты указаны некорректно", document.type, "ERROR", document.id, "own_funds"));
    }

    private static BigDecimal number(StoredDocument document, String field) {
        var value = document == null ? null : document.fields.get(field);
        return value != null && "RECOGNIZED".equals(value.status()) && value.value() instanceof BigDecimal number ? number : null;
    }

    private static StoredDocument firstApplication(ApplicationState state) { return state.documents.values().stream().filter(document -> document.type == DocumentType.APPLICATION).findFirst().orElse(null); }
    private ApplicationState require(UUID id) {
        var state = applications.get(id);
        if (state == null) throw error(HttpStatus.NOT_FOUND, "Заявка не найдена");
        return state;
    }
    private static StoredDocument requireDocument(ApplicationState state, UUID documentId) {
        var document = state.documents.get(documentId);
        if (document == null) throw error(HttpStatus.NOT_FOUND, "Документ не найден");
        return document;
    }

    private ApplicationView view(ApplicationState state) {
        var application = firstApplication(state);
        var names = new LinkedHashSet<>(REQUISITES);
        if (application != null) names.addAll(application.fields.keySet());
        var requisites = names.stream().map(name -> {
            var field = application == null ? null : application.fields.get(name);
            return field == null ? new Requisite(name, null, "NOT_RECOGNIZED", application == null ? null : application.id, null, null, false, null, null, null)
                    : new Requisite(name, field.value(), field.status(), application.id, field.confidence(), field.source(), field.manuallyCorrected(), field.originalValue(), field.correctedBy(), field.correctedAt());
        }).toList();
        String program = state.programCode;
        boolean detected = false;
        if (program == null) {
            program = text(application, "program_code");
            if (program == null && "Инновационный проект".equalsIgnoreCase(text(application, "program_name"))) program = "INNOVATION";
            detected = program != null;
        }
        String programName = detected ? text(application, "program_name") : null;
        if (programName == null && "INNOVATION".equals(program)) programName = "Инновационный проект";
        return new ApplicationView(state.id, text(application, "company_name"), text(application, "inn"), text(application, "project_name"), number(application, "requested_amount"), state.createdAt,
                state.documents.values().stream().map(StoredDocument::view).toList(), state.report, program, programName, detected, requisites);
    }

    private static String text(StoredDocument document, String name) {
        var field = document == null ? null : document.fields.get(name);
        return field != null && field.value() != null && "RECOGNIZED".equals(field.status()) ? field.value().toString() : null;
    }
    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }
    private static ResponseStatusException error(HttpStatus status, String detail) { return new ResponseStatusException(status, detail); }
    private static String typeTitle(DocumentType type) { return switch (type) { case APPLICATION -> "заявление"; case REGISTRATION_EXTRACT -> "выписка ЕГРЮЛ/ЕГРИП"; case COST_ESTIMATE -> "смета расходов"; default -> "документ"; }; }
    private static String processingError(Exception exception) {
        if (exception instanceof ResponseStatusException response) return switch (response.getStatusCode().value()) {
            case 504 -> "Модель не ответила в отведённое время. Повторите обработку позже.";
            case 503 -> "Модель недоступна. Проверьте подключение и повторите обработку.";
            case 422 -> "Не удалось прочитать документ. Проверьте файл и качество скана.";
            default -> "Модель вернула некорректный ответ. Повторите обработку.";
        };
        if (exception instanceof IOException || exception instanceof IllegalArgumentException) return "Не удалось прочитать документ. Проверьте файл и качество скана.";
        if (exception instanceof InterruptedException) return "Обработка остановлена. Повторите её после запуска сервера.";
        return "Не удалось распознать документ. Повторите обработку или укажите значения вручную.";
    }
    private static void journal(ApplicationState state, UUID documentId, String action, String message, String actor, String actorType, String outcome, String reason) {
        if (state.journal.size() >= 1000) state.journal.remove(0);
        state.journal.add(new JournalEntry(UUID.randomUUID(), Instant.now(), actor, actorType, action, message, state.id, documentId, outcome, reason));
    }

    private static final class StoredDocument {
        final UUID id;
        final String filename;
        final byte[] content;
        final Instant uploadedAt;
        DocumentType type, detectedType;
        Double confidence;
        String typeSource, typeChangedBy, processingStatus = "UPLOADED", processingError;
        Instant typeChangedAt;
        long revision;
        final Map<String, ExtractedField> fields = new LinkedHashMap<>();
        StoredDocument(UUID id, DocumentType type, String filename, byte[] content, Instant uploadedAt) {
            this.id = id; this.type = type; this.filename = filename; this.content = content; this.uploadedAt = uploadedAt;
            if (type != null) typeSource = "SPECIALIST";
        }
        boolean processing() { return !Set.of("DONE", "FAILED").contains(processingStatus); }
        DocumentView view() { return new DocumentView(id, type, filename, content.length, uploadedAt, processingStatus, processingError, detectedType, confidence, typeSource, typeChangedBy, typeChangedAt, List.copyOf(fields.values())); }
    }
    private static final class ApplicationState {
        final UUID id;
        final String programCode;
        final Instant createdAt;
        final Map<UUID, StoredDocument> documents = new LinkedHashMap<>();
        final List<JournalEntry> journal = new ArrayList<>();
        CheckReport report;
        ApplicationState(UUID id, String programCode, Instant createdAt) { this.id = id; this.programCode = programCode; this.createdAt = createdAt; }
    }
}
